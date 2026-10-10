// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime owner 与 Workspace binding 的锁顺序回归测试。

use super::*;
use crate::app_runtime::{
    ApprovalResponseInput, AttachmentImportInput, AttachmentMetadata, ConfigurationReadParams,
    ConfigurationRequest, ConfigurationResponse, HistoryRequest, HistoryResponse,
    ManualRecoveryConfirmation, RuntimeBridgePort, RuntimePlatformPort, RuntimeRecoveryState,
    RuntimeStatus, RuntimeStatusKind, RuntimeStorageInfo, SettingsRequest, SettingsResponse,
    TurnAccepted, TurnCancelInput, TurnCancelResult, TurnStartInput, WorkspaceDto,
    WorkspaceRuntimeSource, WorkspaceUpdateInput,
};
use crate::terminal::TerminalWorkspaceResolver;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

/// 可重复武装的确定性栅栏只暂停一个 fake port 调用，使测试能在真实临界点检查锁是否空闲。
struct CallGate {
    armed: AtomicBool,
    entered: AtomicBool,
    released: AtomicBool,
    lock: Mutex<()>,
    wake: Condvar,
}

impl CallGate {
    /// 创建默认直通的栅栏，避免 Host 准备阶段被测试同步逻辑改变。
    fn new() -> Self {
        Self {
            armed: AtomicBool::new(false),
            entered: AtomicBool::new(false),
            released: AtomicBool::new(false),
            lock: Mutex::new(()),
            wake: Condvar::new(),
        }
    }

    /// 在启动 worker 前重置并武装单次调用；同一测试不会并发重复武装。
    fn arm(&self) {
        self.entered.store(false, Ordering::Release);
        self.released.store(false, Ordering::Release);
        self.armed.store(true, Ordering::Release);
    }

    /// 等待 fake port 已进入跨 owner 调用点，使用同一绝对期限避免任意 sleep。
    fn wait_until_entered(&self, deadline: Instant) -> bool {
        let Ok(mut guard) = self.lock.lock() else {
            return false;
        };
        while !self.entered.load(Ordering::Acquire) {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let Ok((next, timeout)) = self.wake.wait_timeout(guard, remaining) else {
                return false;
            };
            guard = next;
            if timeout.timed_out() && !self.entered.load(Ordering::Acquire) {
                return false;
            }
        }
        true
    }

    /// 先发布释放事实再唤醒 fake port，保证 worker 离开跨 owner 调用点后可有界收口。
    fn release(&self) {
        self.released.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    /// 仅在已武装时暂停当前调用；退出时自动解除武装，避免后续清理再次等待。
    fn pause_if_armed(&self) {
        if !self.armed.load(Ordering::Acquire) {
            return;
        }
        self.entered.store(true, Ordering::Release);
        self.wake.notify_all();
        let Ok(mut guard) = self.lock.lock() else {
            return;
        };
        while !self.released.load(Ordering::Acquire) {
            let Ok(next) = self.wake.wait(guard) else {
                return;
            };
            guard = next;
        }
        self.armed.store(false, Ordering::Release);
    }
}

/// Fake bridge 只实现 Host application 的端口合同，并为两条可能反向取锁的调用提供栅栏。
struct LockOrderBridge {
    root: PathBuf,
    metadata_root: Mutex<PathBuf>,
    metadata_name: Mutex<String>,
    metadata_revision: AtomicUsize,
    state_gate: Arc<CallGate>,
    runtime_busy: AtomicBool,
    workspace_open_gate: Arc<CallGate>,
    start_calls: AtomicUsize,
    configuration_calls: AtomicUsize,
    workspace_open_calls: AtomicUsize,
    workspace_open_by_id_calls: AtomicUsize,
    fail_workspace_update_readback: AtomicBool,
    fail_workspace_read_once: AtomicBool,
    health_calls: AtomicUsize,
    runtime_generation: AtomicUsize,
    workspace_set_trust_calls: AtomicUsize,
    workspace_trust: Mutex<String>,
    attachment_import_gate: Arc<CallGate>,
    attachment_imported_workspace_ids: Mutex<Vec<String>>,
}

impl LockOrderBridge {
    /// 绑定单个测试 Workspace；不创建进程、文件或隐藏的 Runtime 状态。
    fn new(root: PathBuf) -> Self {
        Self {
            metadata_root: Mutex::new(root.clone()),
            metadata_name: Mutex::new("Lock order".to_owned()),
            metadata_revision: AtomicUsize::new(1),
            root,
            state_gate: Arc::new(CallGate::new()),
            runtime_busy: AtomicBool::new(false),
            workspace_open_gate: Arc::new(CallGate::new()),
            start_calls: AtomicUsize::new(0),
            configuration_calls: AtomicUsize::new(0),
            workspace_open_calls: AtomicUsize::new(0),
            workspace_open_by_id_calls: AtomicUsize::new(0),
            fail_workspace_update_readback: AtomicBool::new(false),
            fail_workspace_read_once: AtomicBool::new(false),
            health_calls: AtomicUsize::new(0),
            runtime_generation: AtomicUsize::new(1),
            workspace_set_trust_calls: AtomicUsize::new(0),
            workspace_trust: Mutex::new("untrusted".to_owned()),
            attachment_import_gate: Arc::new(CallGate::new()),
            attachment_imported_workspace_ids: Mutex::new(Vec::new()),
        }
    }
}

/// 构造所有成功生命周期调用共享的 Ready 投影，避免 fake 自己产生状态分支。
fn ready_status(generation: usize) -> RuntimeStatus {
    RuntimeStatus {
        status: RuntimeStatusKind::Ready,
        generation: generation as u64,
        server_instance_id: Some("srv_lock_order".to_owned()),
    }
}

impl RuntimeBridgePort for LockOrderBridge {
    /// 启动直接进入 Ready；本测试只验证 application 锁顺序，不模拟进程生命周期。
    fn start(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        self.start_calls.fetch_add(1, Ordering::AcqRel);
        Ok(ready_status(
            self.runtime_generation.load(Ordering::Acquire),
        ))
    }

    /// 停止返回稳定终态，使测试结束时可以走正常 Host 清理入口。
    fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        Ok(RuntimeStatus {
            status: RuntimeStatusKind::Stopped,
            generation: 1,
            server_instance_id: None,
        })
    }

    /// 状态查询在栅栏处模拟 actor 等待，并可投影 Busy；元数据测试要确认 Java 而非 Ready-only 门禁裁决更新。
    fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        self.state_gate.pause_if_armed();
        let mut status = ready_status(
            self.runtime_generation.load(Ordering::Acquire),
        );
        if self.runtime_busy.load(Ordering::Acquire) {
            status.status = RuntimeStatusKind::Busy;
        }
        Ok(status)
    }

    /// 配置不属于本回归场景，显式拒绝可防止测试误用 fake 扩大证明范围。
    fn configuration(
        &self,
        _request: ConfigurationRequest,
    ) -> Result<ConfigurationResponse, RuntimeCommandError> {
        self.configuration_calls.fetch_add(1, Ordering::AcqRel);
        Err(RuntimeCommandError::unavailable())
    }

    /// Workspace 打开在栅栏处模拟 JA-RPC 往返，并只返回绑定测试根的固定 identity。
    fn workspace_open(
        &self,
        root: PathBuf,
        display_name: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.workspace_open_calls.fetch_add(1, Ordering::AcqRel);
        self.workspace_open_gate.pause_if_armed();
        if root != self.root {
            return Err(RuntimeCommandError::invalid_params());
        }
        Ok(WorkspaceDto {
            workspace_id: "ws_lock_order".to_owned(),
            root: root.to_string_lossy().into_owned(),
            display_name,
            trust: "untrusted".to_owned(),
            revision: 1,
            kind: crate::app_runtime::WorkspaceKind::Project,
        })
    }

    /// 模拟 Java 持久化授信并签发新 revision，后续按 ID 重开须读回该权威信任状态。
    fn workspace_set_trust(
        &self,
        workspace_id: String,
        trust: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.workspace_set_trust_calls
            .fetch_add(1, Ordering::AcqRel);
        if workspace_id != "ws_lock_order" || !matches!(trust.as_str(), "trusted" | "untrusted") {
            return Err(RuntimeCommandError::invalid_params());
        }
        *self
            .workspace_trust
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())? = trust.clone();
        self.metadata_revision.store(7, Ordering::Release);
        Ok(WorkspaceDto {
            workspace_id,
            root: self.root.to_string_lossy().into_owned(),
            display_name: "Lock order".to_owned(),
            trust,
            revision: self.metadata_revision.load(Ordering::Acquire) as u64,
            kind: crate::app_runtime::WorkspaceKind::Project,
        })
    }

    /// 仅按 ID 查询的假实现重开预先登记的项目或会话目录，且从不接受调用方提供的路径。
    fn workspace_open_by_id(
        &self,
        workspace_id: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.workspace_open_by_id_calls
            .fetch_add(1, Ordering::AcqRel);
        let (directory, kind) = match workspace_id.as_str() {
            "ws_lock_order" => (".", crate::app_runtime::WorkspaceKind::Project),
            "ws_session_a" => ("session_a", crate::app_runtime::WorkspaceKind::Session),
            "ws_session_b" => ("session_b", crate::app_runtime::WorkspaceKind::Session),
            _ => return Err(RuntimeCommandError::invalid_params()),
        };
        let root = self.root.join(directory);
        if !root.is_dir() {
            return Err(RuntimeCommandError::invalid_params());
        }
        Ok(WorkspaceDto {
            workspace_id,
            root: if kind == crate::app_runtime::WorkspaceKind::Project {
                self.metadata_root
                    .lock()
                    .map_err(|_| RuntimeCommandError::unavailable())?
                    .to_string_lossy()
                    .into_owned()
            } else {
                root.to_string_lossy().into_owned()
            },
            display_name: if kind == crate::app_runtime::WorkspaceKind::Project {
                self.metadata_name
                    .lock()
                    .map_err(|_| RuntimeCommandError::unavailable())?
                    .clone()
            } else {
                directory.to_owned()
            },
            trust: if kind == crate::app_runtime::WorkspaceKind::Project {
                self.workspace_trust
                    .lock()
                    .map_err(|_| RuntimeCommandError::unavailable())?
                    .clone()
            } else {
                "trusted".to_owned()
            },
            revision: if kind == crate::app_runtime::WorkspaceKind::Project {
                self.metadata_revision.load(Ordering::Acquire) as u64
            } else {
                2
            },
            kind,
        })
    }

    /// Metadata reconciliation 测试按 ID 读取可变 fake authority，不把 root 从 Host 参数带入。
    fn workspace_read(&self, workspace_id: String) -> Result<WorkspaceDto, RuntimeCommandError> {
        if workspace_id != "ws_lock_order" {
            return Err(RuntimeCommandError::invalid_params());
        }
        if self.fail_workspace_read_once.swap(false, Ordering::AcqRel) {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(WorkspaceDto {
            workspace_id,
            root: self
                .metadata_root
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())?
                .to_string_lossy()
                .into_owned(),
            display_name: self
                .metadata_name
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())?
                .clone(),
            trust: self
                .workspace_trust
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())?
                .clone(),
            revision: self.metadata_revision.load(Ordering::Acquire) as u64,
            kind: crate::app_runtime::WorkspaceKind::Project,
        })
    }

    /// Fake CAS can lose a committed update reply and its readback once, matching the transport ambiguity Host must fence.
    fn workspace_update(
        &self,
        input: WorkspaceUpdateInput,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        if input.workspace_id != "ws_lock_order" {
            return Err(RuntimeCommandError::invalid_params());
        }
        let current_revision = self.metadata_revision.load(Ordering::Acquire) as u64;
        if current_revision != input.expected_revision {
            return Err(RuntimeCommandError {
                code: "CONFLICT",
                message: "workspace revision changed".into(),
                retryable: true,
            });
        }
        let display_name = match input.display_name {
            Some(name) => name,
            None => self
                .metadata_name
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())?
                .clone(),
        };
        *self.metadata_name.lock().map_err(|_| RuntimeCommandError::unavailable())? =
            display_name.clone();
        if let Some(cwd) = input.cwd {
            *self
                .metadata_root
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())? = PathBuf::from(cwd);
            *self
                .workspace_trust
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())? = "untrusted".to_owned();
        }
        let revision = self.metadata_revision.fetch_add(1, Ordering::AcqRel) as u64 + 1;
        if self
            .fail_workspace_update_readback
            .swap(false, Ordering::AcqRel)
        {
            self.fail_workspace_read_once.store(true, Ordering::Release);
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(WorkspaceDto {
            workspace_id: input.workspace_id,
            root: self
                .metadata_root
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())?
                .to_string_lossy()
                .into_owned(),
            display_name,
            trust: self
                .workspace_trust
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())?
                .clone(),
            revision,
            kind: crate::app_runtime::WorkspaceKind::Project,
        })
    }

    /// Fake 没有外部健康资源；Ready 状态即满足本测试的最小健康合同。
    fn health(&self) -> Result<(), RuntimeCommandError> {
        self.health_calls.fetch_add(1, Ordering::AcqRel);
        Ok(())
    }

    /// Turn 不属于此应用锁测试，拒绝调用可避免伪造 Java admission 事实。
    fn turn_start(&self, _input: TurnStartInput) -> Result<TurnAccepted, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// 取消不属于此应用锁测试，保持 fail-closed。
    fn turn_cancel(
        &self,
        _input: TurnCancelInput,
    ) -> Result<TurnCancelResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Approval 不属于此应用锁测试，保持 fail-closed。
    fn approval_respond(&self, _input: ApprovalResponseInput) -> Result<(), RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// 暂停附件 RPC 后记录 Host 提交的原始 Workspace ID，以覆盖导入期间切换当前 Thread 的竞态。
    fn attachment_import(
        &self,
        workspace_id: String,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        self.attachment_import_gate.pause_if_armed();
        self.attachment_imported_workspace_ids
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())?
            .push(workspace_id);
        Ok(AttachmentMetadata {
            attachment_id: "att_lock_order".to_owned(),
            display_name: input.display_name,
            size_bytes: input.size_bytes,
            media_kind: "text".to_owned(),
            media_type: Some("text/plain".to_owned()),
            state: "draft".to_owned(),
        })
    }

    /// History 不属于此应用锁测试，保持 fail-closed。
    fn history(&self, _request: HistoryRequest) -> Result<HistoryResponse, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Settings 不属于此应用锁测试，保持 fail-closed。
    fn settings(&self, _request: SettingsRequest) -> Result<SettingsResponse, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Fake shutdown 没有外部 owner，只确认调用已到达端口边界。
    fn shutdown(&self) -> Result<(), RuntimeCommandError> {
        Ok(())
    }

    /// Fake 不消费真实退出预算，但保留调用方传入同一绝对 deadline 的签名。
    fn shutdown_until(&self, _deadline: Instant) -> Result<(), RuntimeCommandError> {
        Ok(())
    }

    /// Fake 从不创建进程或 worker，因此始终满足最终退出证明。
    fn exit_ready(&self) -> bool {
        true
    }

    /// Fake 没有可持久化的强制退出事实，调用保持无副作用。
    fn record_forced_exit(&self) {}
}

/// Fake platform 只负责把同一个 bridge 和 canonical 测试根注入 Host application。
struct LockOrderPlatform {
    root: PathBuf,
    bridge: Arc<LockOrderBridge>,
}

impl LockOrderPlatform {
    /// 创建不拥有进程、恢复文件或第二份 Workspace 状态的最小 application port。
    fn new(root: PathBuf, bridge: Arc<LockOrderBridge>) -> Self {
        Self { root, bridge }
    }
}

impl RuntimePlatformPort for LockOrderPlatform {
    /// 返回同一个 fake bridge；测试只检查 application 与 bridge 的锁顺序。
    fn create_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        Ok(self.bridge.clone())
    }

    /// 测试没有恢复 marker，启动门始终开放。
    fn recovery_state(&self) -> RuntimeRecoveryState {
        RuntimeRecoveryState {
            required: false,
            acknowledgeable: false,
            recovery_id: None,
            revision: None,
        }
    }

    /// 无 marker 时确认请求没有意义，返回当前关闭状态而不创建恢复事实。
    fn acknowledge_recovery(
        &self,
        _confirmation: &ManualRecoveryConfirmation,
    ) -> Result<RuntimeRecoveryState, RuntimeCommandError> {
        Ok(self.recovery_state())
    }

    /// Storage 投影只使用测试根，不读取真实用户目录。
    fn storage_info(&self) -> RuntimeStorageInfo {
        RuntimeStorageInfo {
            native_image: false,
            storage_recovery: None,
            data_path: self.root.to_string_lossy().into_owned(),
            log_path: None,
            cache_path: None,
            last_backup: None,
        }
    }

    /// 只接受 fixture 的精确 canonical root，防止 fake 绕过生产路径归属语义。
    fn workspace_source(
        &self,
        cwd: &str,
        display_name: Option<&str>,
    ) -> Result<WorkspaceRuntimeSource, RuntimeCommandError> {
        if self.root.as_path() != std::path::Path::new(cwd) {
            return Err(RuntimeCommandError::invalid_params());
        }
        Ok(WorkspaceRuntimeSource {
            root: self.root.clone(),
            display_name: display_name.unwrap_or("Lock order").to_owned(),
        })
    }
}

/// 创建已保存但尚未启动的 Workspace binding，使测试能分别观察启动重放和普通查询阶段。
fn fixture_host(root: PathBuf) -> (RuntimeHost, Arc<LockOrderBridge>) {
    // 使用与生产 WorkspaceRegistry 相同的规范根，避免 Windows verbatim path 表示差异
    // 让 fake 把合法 capability 误判为越界输入。
    let root = std::fs::canonicalize(root).expect("canonicalize lock-order fixture");
    let bridge = Arc::new(LockOrderBridge::new(root.clone()));
    let platform = Arc::new(LockOrderPlatform::new(root.clone(), bridge.clone()));
    let host = RuntimeHost::compose(platform);
    host.open_workspace(WorkspaceOpenInput {
        cwd: root.to_string_lossy().into_owned(),
        display_name: Some("Lock order".to_owned()),
    })
    .expect("store fixture workspace binding");
    (host, bridge)
}

/// 用可变 fake authority 和生产 metadata router 建立闭环，测试通知回读不经 UI 猜测 root。
fn fixture_host_with_workspace_event_router(
    root: PathBuf,
) -> (
    RuntimeHost,
    Arc<LockOrderBridge>,
    crate::app_runtime::WorkspaceMetadataEventRouter,
) {
    let root = std::fs::canonicalize(root).expect("canonicalize metadata fixture");
    let bridge = Arc::new(LockOrderBridge::new(root.clone()));
    let platform = Arc::new(LockOrderPlatform::new(root.clone(), bridge.clone()));
    let router = crate::app_runtime::WorkspaceMetadataEventRouter::default();
    let host = RuntimeHost::compose_with_event_router(platform, &router);
    host.open_workspace(WorkspaceOpenInput {
        cwd: root.to_string_lossy().into_owned(),
        display_name: Some("Lock order".to_owned()),
    })
    .expect("store fixture workspace binding");
    (host, bridge, router)
}

/// 构建已通过 production projection 的最小完整 metadata envelope，覆盖 router 使用的双 runtime fence。
fn workspace_metadata_event(revision: u64) -> serde_json::Value {
    serde_json::json!({
        "method": "workspace/metadata-changed",
        "params": {
            "serverInstanceId": "srv_lock_order",
            "eventId": uuid::Uuid::new_v4().to_string(),
            "sequence": revision,
            "occurredAt": "2026-10-10T00:00:00Z",
            "generation": 1,
            "workspaceId": "ws_lock_order",
            "revision": revision,
        }
    })
}

/// 测试沿生产 interface 解码后再调用 application，固定协议 JSON 不越过边界进入 router。
fn observe_workspace_metadata_event(
    router: &crate::app_runtime::WorkspaceMetadataEventRouter,
    revision: u64,
) {
    let event = crate::app_runtime::interface::event_projection::workspace_metadata_changed_event(
        &workspace_metadata_event(revision),
    )
    .expect("metadata event has the required typed fields");
    router.observe(
        &event.server_instance_id,
        &event.workspace_id,
        event.revision,
        event.generation,
    );
}

/// 通过 Host 可观察的激活结果与 handle 身份验证授信保留 Workspace，不穿透私有 binding。
#[test]
fn workspace_trust_refresh_preserves_active_binding() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-trust-lock-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    std::fs::create_dir_all(&root).expect("create trust fixture");
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start runtime and register project");
    let prior_handle_id = host
        .with_configured_workspace("ws_lock_order", |handle| handle.id())
        .expect("configured workspace handle");

    host.set_workspace_trust("ws_lock_order".to_owned(), "trusted".to_owned())
        .expect("set project trust");

    let activation = host
        .activate_workspace("ws_lock_order".to_owned())
        .expect("reactivate trusted project");
    assert_eq!(activation.trust, "trusted");
    let current_handle_id = host
        .with_configured_workspace("ws_lock_order", |handle| handle.id())
        .expect("configured workspace handle after trust");
    assert_eq!(current_handle_id, prior_handle_id);
    assert_eq!(bridge.workspace_set_trust_calls.load(Ordering::Acquire), 1);
    std::fs::remove_dir_all(root).expect("remove trust fixture");
}

/// 同 root 改名的 metadata event 经独立 authority read 后更新投影，已有会话与 native handle 连续可用。
#[test]
fn workspace_metadata_name_change_preserves_active_handle() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-metadata-name-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    std::fs::create_dir_all(&root).expect("create metadata fixture");
    let (host, bridge, router) = fixture_host_with_workspace_event_router(root.clone());
    host.start().expect("start runtime and register project");
    let previous_handle_id = host
        .with_configured_workspace("ws_lock_order", |handle| handle.id())
        .expect("configured workspace handle");
    *bridge.metadata_name.lock().expect("metadata name lock") = "Renamed project".to_owned();
    bridge.metadata_revision.store(2, Ordering::Release);
    observe_workspace_metadata_event(&router, 2);

    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if host
            .with_configured_workspace("ws_lock_order", |handle| handle.id())
            .is_ok_and(|handle_id| handle_id == previous_handle_id)
        {
            let activation = host
                .activate_workspace("ws_lock_order".to_owned())
                .expect("renamed project remains activatable");
            assert_eq!(activation.display_name, "Renamed project");
            break;
        }
        assert!(
            Instant::now() < deadline,
            "metadata name refresh did not settle"
        );
        std::thread::sleep(Duration::from_millis(10));
    }
    std::fs::remove_dir_all(root).expect("remove metadata fixture");
}

/// Update 与 authority readback 同时丢失时立即撤销旧 clone，懒 IO 不得绕过 fence，显式 ID-open 才恢复绑定。
#[test]
fn workspace_update_and_readback_double_failure_fences_root_until_explicit_open() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-update-unknown-root-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    let replacement = root.join("replacement");
    std::fs::create_dir_all(&root).expect("create unknown root fixture");
    std::fs::create_dir_all(&replacement).expect("create replacement root");
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start fake runtime");
    let old_handle = host
        .with_configured_workspace("ws_lock_order", WorkspaceHandle::clone)
        .expect("configured workspace handle");
    bridge
        .fail_workspace_update_readback
        .store(true, Ordering::Release);

    let error = host
        .update_workspace(WorkspaceUpdateInput {
            workspace_id: "ws_lock_order".to_owned(),
            expected_revision: 1,
            display_name: None,
            cwd: Some(replacement.to_string_lossy().into_owned()),
        })
        .expect_err("lost update plus failed readback must not be reported as success");
    assert_eq!(error.code, "RUNTIME_UNAVAILABLE");
    assert!(old_handle.resolve_directory("").is_err());
    assert!(matches!(
        host.with_configured_workspace("ws_lock_order", |_| ()),
        Err(crate::app_runtime::WorkspaceLookup::Unknown)
    ));
    assert_eq!(bridge.workspace_open_by_id_calls.load(Ordering::Acquire), 0);

    let projection = host
        .open_workspace_projection_by_id("ws_lock_order".to_owned())
        .expect("explicit open recovers the authority root");
    assert_eq!(projection.revision, 2);
    assert_eq!(
        std::fs::canonicalize(&projection.root).expect("canonicalize returned root"),
        std::fs::canonicalize(&replacement).expect("canonicalize replacement")
    );
    assert_eq!(bridge.workspace_open_by_id_calls.load(Ordering::Acquire), 1);
    let bound_root = host
        .with_configured_workspace("ws_lock_order", |handle| handle.resolve_directory(""))
        .expect("recovered workspace handle")
        .expect("recovered root resolves");
    assert_eq!(bound_root, std::fs::canonicalize(replacement).expect("canonicalize root"));

    host.shutdown().expect("shutdown fake runtime");
    std::fs::remove_dir_all(root).expect("remove unknown root fixture");
}

/// Name-only 双失败也阻断后续新 IO，但不会在用户显式复核前触碰已打开的 Thread 文件资源。
#[test]
fn workspace_name_update_and_readback_double_failure_fences_new_io() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-update-unknown-name-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    std::fs::create_dir_all(&root).expect("create unknown name fixture");
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start fake runtime");
    let old_handle = host
        .with_configured_workspace("ws_lock_order", WorkspaceHandle::clone)
        .expect("configured workspace handle");
    bridge
        .fail_workspace_update_readback
        .store(true, Ordering::Release);

    let error = host
        .update_workspace(WorkspaceUpdateInput {
            workspace_id: "ws_lock_order".to_owned(),
            expected_revision: 1,
            display_name: Some("Renamed with lost reply".to_owned()),
            cwd: None,
        })
        .expect_err("name update with failed readback remains uncertain");
    assert_eq!(error.code, "RUNTIME_UNAVAILABLE");
    assert!(old_handle.resolve_directory("").is_err());
    assert!(matches!(
        host.with_configured_workspace("ws_lock_order", |_| ()),
        Err(crate::app_runtime::WorkspaceLookup::Unknown)
    ));
    assert_eq!(bridge.workspace_open_by_id_calls.load(Ordering::Acquire), 0);

    host.open_workspace_projection_by_id("ws_lock_order".to_owned())
        .expect("explicit open recovers name-only update fence");
    assert_eq!(bridge.workspace_open_by_id_calls.load(Ordering::Acquire), 1);
    host.shutdown().expect("shutdown fake runtime");
    std::fs::remove_dir_all(root).expect("remove unknown name fixture");
}

/// 名称 CAS 在 Runtime Busy 且磁盘根已被替换为普通文件时仍完成；旧 capability 不会因此授权新对象。
#[test]
fn workspace_name_only_update_reuses_binding_during_busy_with_unavailable_root() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-metadata-busy-name-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    let moved_root = root.with_extension("moved");
    std::fs::create_dir_all(&root).expect("create busy metadata fixture");
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start fake runtime");
    let prior_handle_id = host
        .with_configured_workspace("ws_lock_order", |handle| handle.id())
        .expect("configured workspace handle");

    std::fs::rename(&root, &moved_root).expect("move project root away");
    std::fs::write(&root, b"not a directory").expect("replace root spelling with a file");
    bridge.runtime_busy.store(true, Ordering::Release);

    let updated = host
        .update_workspace(WorkspaceUpdateInput {
            workspace_id: "ws_lock_order".to_owned(),
            expected_revision: 1,
            display_name: Some("Renamed while running".to_owned()),
            cwd: None,
        })
        .expect("metadata-only CAS must reach the Java authority while runtime is Busy");
    assert_eq!(updated.display_name, "Renamed while running");
    assert_eq!(updated.revision, 2);
    assert_eq!(
        host.with_configured_workspace("ws_lock_order", |handle| handle.id())
            .expect("existing binding remains available"),
        prior_handle_id
    );
    assert!(matches!(
        host.with_configured_workspace("ws_lock_order", |handle| handle.resolve_directory("")),
        Ok(Err(_))
    ));

    host.shutdown().expect("shutdown fake runtime");
    std::fs::remove_file(&root).expect("remove replacement file");
    std::fs::remove_dir_all(moved_root).expect("remove moved fixture");
}

/// 新 root 被 authority 确认后立即撤销旧 clone，随后按同一稳定 ID 只能绑定新 root。
#[test]
fn workspace_metadata_root_change_revokes_old_handle() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-metadata-root-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    let new_root = root.join("replacement");
    std::fs::create_dir_all(&root).expect("create metadata fixture");
    std::fs::create_dir_all(&new_root).expect("create replacement root");
    let (host, bridge, router) = fixture_host_with_workspace_event_router(root.clone());
    host.start().expect("start runtime and register project");
    let old_handle = host
        .with_configured_workspace("ws_lock_order", WorkspaceHandle::clone)
        .expect("configured workspace handle");
    let old_handle_id = old_handle.id();
    *bridge.metadata_root.lock().expect("metadata root lock") =
        std::fs::canonicalize(&new_root).expect("canonicalize replacement root");
    bridge.metadata_revision.store(2, Ordering::Release);
    observe_workspace_metadata_event(&router, 2);

    let (sender, receiver) = std::sync::mpsc::sync_channel(1);
    std::thread::spawn(move || {
        let _ = sender.send(old_handle.resolve_directory(""));
    });
    assert!(
        receiver
            .recv_timeout(Duration::from_secs(5))
            .expect("old handle must leave metadata fence")
            .is_err(),
        "old handle resolved after root replacement"
    );
    let (new_handle_id, resolved_root) = host
        .with_configured_workspace("ws_lock_order", |handle| {
            (handle.id(), handle.resolve_directory(""))
        })
        .expect("reopen from authoritative project identity");
    assert_ne!(new_handle_id, old_handle_id);
    assert_eq!(
        resolved_root.expect("new root resolves"),
        std::fs::canonicalize(new_root).expect("canonicalize replacement root")
    );
    std::fs::remove_dir_all(root).expect("remove metadata fixture");
}

/// 启动重放不得在等待 workspace/open actor 时持有 binding 锁；Turn/dirty 回调需要按
/// actor → Workspace 的同一顺序读取归属，否则真实启动与并发事件会互锁。
#[test]
fn configured_workspace_replay_releases_binding_before_actor_call() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-replay-lock-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    std::fs::create_dir_all(&root).expect("create lock-order fixture");
    let (host, bridge) = fixture_host(root.clone());
    bridge.workspace_open_gate.arm();

    let worker_host = host.clone();
    let (result_sender, result_receiver) = std::sync::mpsc::sync_channel(1);
    let worker = std::thread::spawn(move || {
        let _ = result_sender.send(worker_host.start());
    });
    assert!(
        bridge
            .workspace_open_gate
            .wait_until_entered(Instant::now() + Duration::from_secs(5)),
        "workspace/open fake port was not reached"
    );
    let binding_was_free = host.workspace.try_lock().is_ok();
    bridge.workspace_open_gate.release();
    let result = result_receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("start worker must finish after gate release");
    worker.join().expect("start worker must not panic");

    assert!(
        binding_was_free,
        "workspace/open must run without the binding lock"
    );
    result.expect("workspace replay remains successful");
    host.shutdown().expect("fake host shutdown");
    std::fs::remove_dir_all(root).expect("remove lock-order fixture");
}

/// 已处于 Ready 的配置读取只能进入 configuration operation，不能再次 start、重放
/// workspace/open 或 health；否则一次项目 scope 同步会放大为整条 Runtime 启动链。
#[test]
fn ready_configuration_request_skips_start_and_workspace_replay() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-config-fast-path-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    std::fs::create_dir_all(&root).expect("create configuration fast-path fixture");
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start fake runtime");

    let request = ConfigurationRequest::Read(
        ConfigurationReadParams::try_new(b"{}".to_vec()).expect("configuration params"),
    );
    let error = match host.config_request(request) {
        Ok(_) => panic!("fake configuration response must remain unavailable"),
        Err(error) => error,
    };

    assert_eq!(error.code, "RUNTIME_UNAVAILABLE");
    assert_eq!(bridge.start_calls.load(Ordering::Acquire), 1);
    assert_eq!(bridge.workspace_open_calls.load(Ordering::Acquire), 1);
    assert_eq!(bridge.health_calls.load(Ordering::Acquire), 1);
    assert_eq!(bridge.configuration_calls.load(Ordering::Acquire), 1);
    host.shutdown().expect("fake host shutdown");
    std::fs::remove_dir_all(root).expect("remove configuration fast-path fixture");
}

/// Workspace capability 查询必须先完成 actor state 检查再取得 binding 锁；这是修复
/// Review Turn 已被 Java 接纳但 Rust 回执因锁顺序反转而超时的直接回归合同。
#[test]
fn configured_workspace_query_checks_runtime_before_binding_lock() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-query-lock-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    std::fs::create_dir_all(&root).expect("create lock-order fixture");
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start fake runtime");
    bridge.state_gate.arm();

    let worker_host = host.clone();
    let (result_sender, result_receiver) = std::sync::mpsc::sync_channel(1);
    let worker = std::thread::spawn(move || {
        let result = worker_host.with_configured_workspace("ws_lock_order", |_| ());
        let _ = result_sender.send(result);
    });
    assert!(
        bridge
            .state_gate
            .wait_until_entered(Instant::now() + Duration::from_secs(5)),
        "state fake port was not reached"
    );
    let binding_was_free = host.workspace.try_lock().is_ok();
    bridge.state_gate.release();
    let result = result_receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("workspace query worker must finish after gate release");
    worker
        .join()
        .expect("workspace query worker must not panic");

    assert!(
        binding_was_free,
        "state query must run before the binding lock"
    );
    result.expect("current workspace remains authorized");
    host.shutdown().expect("fake host shutdown");
    std::fs::remove_dir_all(root).expect("remove lock-order fixture");
}

/// 激活另一个 Thread 后仍保留之前的原生句柄，并绑定对应的 Java 根目录；惰性旧版查询不得窃取当前会话的作用域。
#[test]
fn activation_keeps_java_workspace_roots_independent() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-workspace-activation-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    for directory in ["session_a", "session_b"] {
        std::fs::create_dir_all(root.join(directory)).expect("create registered workspace root");
    }
    std::fs::create_dir_all(&root).expect("create activation parent root");
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start fake runtime");

    let first = host
        .activate_workspace("ws_session_a".to_owned())
        .expect("activate first Java session");
    let first_root = std::fs::canonicalize(root.join("session_a")).unwrap();
    assert_eq!(first.root_path, first_root.to_string_lossy());
    let second = host
        .activate_workspace("ws_session_b".to_owned())
        .expect("activate second Java session");
    assert_eq!(
        second.root_path,
        std::fs::canonicalize(root.join("session_b"))
            .unwrap()
            .to_string_lossy()
    );
    assert_eq!(
        host.with_configured_workspace("ws_session_a", |workspace| workspace
            .root_path()
            .to_path_buf())
            .expect("first session remains registered"),
        first_root
    );
    assert_eq!(
        host.resolve_terminal_workspace("ws_session_a")
            .expect("terminal resolver keeps session A root bound"),
        first_root
    );
    assert_eq!(
        host.resolve_terminal_workspace("ws_session_b")
            .expect("terminal resolver follows session B activation"),
        std::fs::canonicalize(root.join("session_b")).unwrap()
    );

    let bindings = host.workspace.lock().unwrap();
    assert_eq!(
        bindings.active_workspace_id.as_deref(),
        Some("ws_session_b")
    );
    assert!(bindings.by_id.contains_key("ws_session_a"));
    drop(bindings);

    bridge.runtime_generation.store(2, Ordering::Release);
    assert_eq!(host.state().expect("new generation status").generation, 2);
    let bindings = host.workspace.lock().unwrap();
    assert!(
        bindings.by_id.is_empty(),
        "old generation handles are revoked"
    );
    assert_eq!(bindings.active_workspace_id, None);
    drop(bindings);

    let renewed = host
        .activate_workspace("ws_session_a".to_owned())
        .expect("session can be rebound from the new Java generation");
    assert_eq!(renewed.root_path, first_root.to_string_lossy());
    let bindings = host.workspace.lock().unwrap();
    assert_eq!(
        bindings.active_workspace_id.as_deref(),
        Some("ws_session_a")
    );
    assert_eq!(bindings.by_id.len(), 1);
    assert!(bindings.by_id.contains_key("ws_session_a"));
    drop(bindings);

    host.shutdown().expect("shutdown fake runtime");
    assert!(host.workspace.lock().unwrap().by_id.is_empty());
    std::fs::remove_dir_all(root).expect("remove activation fixture");
}

/// ID-only 历史打开返回 Java 的完整投影，同时保留 Project 与 Session 原有 ID 和各自根目录绑定。
#[test]
fn workspace_open_by_id_returns_full_projection_for_project_and_session() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-workspace-open-by-id-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    for directory in ["session_a", "session_b"] {
        std::fs::create_dir_all(root.join(directory)).expect("create registered session root");
    }
    std::fs::create_dir_all(&root).expect("create project root");
    let (host, _) = fixture_host(root.clone());
    host.start().expect("start fake runtime");

    let project = host
        .start_and_open_workspace_by_id("ws_lock_order".to_owned())
        .expect("open project by persistent ID");
    assert_eq!(project.workspace_id, "ws_lock_order");
    assert_eq!(
        project.root,
        std::fs::canonicalize(&root)
            .unwrap()
            .to_string_lossy()
            .into_owned()
    );
    assert_eq!(project.trust, "untrusted");
    assert_eq!(project.kind, crate::app_runtime::WorkspaceKind::Project);

    let session = host
        .start_and_open_workspace_by_id("ws_session_a".to_owned())
        .expect("open session by persistent ID");
    assert_eq!(session.workspace_id, "ws_session_a");
    assert_eq!(
        session.root,
        std::fs::canonicalize(root.join("session_a"))
            .unwrap()
            .to_string_lossy()
            .into_owned()
    );
    assert_eq!(session.trust, "trusted");
    assert_eq!(session.kind, crate::app_runtime::WorkspaceKind::Session);
    assert_eq!(
        host.resolve_terminal_workspace("ws_session_a")
            .expect("ID open binds the session root"),
        std::fs::canonicalize(root.join("session_a")).unwrap()
    );

    host.shutdown().expect("shutdown fake runtime");
    std::fs::remove_dir_all(root).expect("remove ID-open fixture");
}

/// 审查等冻结来源必须把附件导入原 Workspace；即使 RPC 等待期间激活另一 Thread，也不能读取新的 active ID。
#[test]
fn frozen_workspace_attachment_import_survives_active_workspace_switch() {
    let root = std::env::temp_dir().join(format!(
        "ja-runtime-attachment-workspace-fence-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    for directory in ["session_a", "session_b"] {
        std::fs::create_dir_all(root.join(directory)).expect("create session fixture");
    }
    let (host, bridge) = fixture_host(root.clone());
    host.start().expect("start fake runtime");
    host.activate_workspace("ws_session_a".to_owned())
        .expect("activate frozen source workspace");
    bridge.attachment_import_gate.arm();

    let worker_host = host.clone();
    let (result_sender, result_receiver) = std::sync::mpsc::sync_channel(1);
    let worker = std::thread::spawn(move || {
        let result = worker_host.attachment_import_for_workspace(
            "ws_session_a",
            AttachmentImportInput {
                ingress_token: "a".repeat(32),
                display_name: "review.json".to_owned(),
                size_bytes: 16,
                sha256: "b".repeat(64),
            },
        );
        let _ = result_sender.send(result);
    });
    assert!(
        bridge
            .attachment_import_gate
            .wait_until_entered(Instant::now() + Duration::from_secs(5)),
        "attachment import fake port was not reached"
    );
    host.activate_workspace("ws_session_b".to_owned())
        .expect("switch active workspace while import is pending");
    bridge.attachment_import_gate.release();
    let result = result_receiver
        .recv_timeout(Duration::from_secs(5))
        .expect("attachment import worker must finish after gate release");
    worker
        .join()
        .expect("attachment import worker must not panic");

    result.expect("frozen workspace import remains successful");
    host.attachment_import(AttachmentImportInput {
        ingress_token: "c".repeat(32),
        display_name: "active.txt".to_owned(),
        size_bytes: 8,
        sha256: "d".repeat(64),
    })
    .expect("ordinary import follows current active workspace");
    assert_eq!(
        bridge
            .attachment_imported_workspace_ids
            .lock()
            .expect("attachment import trace")
            .as_slice(),
        &["ws_session_a", "ws_session_b"]
    );
    assert_eq!(
        host.workspace
            .lock()
            .expect("workspace bindings")
            .active_workspace_id
            .as_deref(),
        Some("ws_session_b")
    );
    host.shutdown().expect("fake host shutdown");
    std::fs::remove_dir_all(root).expect("remove attachment fence fixture");
}
