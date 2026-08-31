// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime owner 与 Workspace binding 的锁顺序回归测试。

use super::*;
use crate::app_runtime::{
    ApprovalResponseInput, ConfigurationReadParams, ConfigurationRequest, ConfigurationResponse,
    HistoryRequest, HistoryResponse, ManualRecoveryConfirmation, RuntimeBridgePort,
    RuntimePlatformPort, RuntimeRecoveryState, RuntimeStatus, RuntimeStatusKind, RuntimeStorageInfo,
    SettingsRequest, SettingsResponse, TurnAccepted, TurnCancelInput, TurnCancelResult,
    TurnQueuedInput, TurnQueuedInputResult, TurnStartInput, WorkspaceDto, WorkspaceRuntimeSource,
};
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
    state_gate: Arc<CallGate>,
    workspace_open_gate: Arc<CallGate>,
    start_calls: AtomicUsize,
    configuration_calls: AtomicUsize,
    workspace_open_calls: AtomicUsize,
    health_calls: AtomicUsize,
}

impl LockOrderBridge {
    /// 绑定单个测试 Workspace；不创建进程、文件或隐藏的 Runtime 状态。
    fn new(root: PathBuf) -> Self {
        Self {
            root,
            state_gate: Arc::new(CallGate::new()),
            workspace_open_gate: Arc::new(CallGate::new()),
            start_calls: AtomicUsize::new(0),
            configuration_calls: AtomicUsize::new(0),
            workspace_open_calls: AtomicUsize::new(0),
            health_calls: AtomicUsize::new(0),
        }
    }
}

/// 构造所有成功生命周期调用共享的 Ready 投影，避免 fake 自己产生状态分支。
fn ready_status() -> RuntimeStatus {
    RuntimeStatus {
        status: RuntimeStatusKind::Ready,
        generation: 1,
        server_instance_id: Some("srv_lock_order".to_owned()),
    }
}

impl RuntimeBridgePort for LockOrderBridge {
    /// 启动直接进入 Ready；本测试只验证 application 锁顺序，不模拟进程生命周期。
    fn start(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        self.start_calls.fetch_add(1, Ordering::AcqRel);
        Ok(ready_status())
    }

    /// 停止返回稳定终态，使测试结束时可以走正常 Host 清理入口。
    fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        Ok(RuntimeStatus {
            status: RuntimeStatusKind::Stopped,
            generation: 1,
            server_instance_id: None,
        })
    }

    /// 状态查询在栅栏处模拟 actor 等待；外层不得同时持有 Workspace binding 锁。
    fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        self.state_gate.pause_if_armed();
        Ok(ready_status())
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
        trust: String,
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
            trust,
            revision: 1,
        })
    }

    /// 通用 Workspace 复用同一固定投影，但当前测试不会从该入口建立 binding。
    fn general_workspace(&self) -> Result<WorkspaceDto, RuntimeCommandError> {
        Ok(WorkspaceDto {
            workspace_id: "ws_lock_order".to_owned(),
            root: self.root.to_string_lossy().into_owned(),
            display_name: "Lock order".to_owned(),
            trust: "trusted".to_owned(),
            revision: 1,
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

    /// Steering 不属于此应用锁测试，保持 fail-closed。
    fn turn_steer(
        &self,
        _input: TurnQueuedInput,
    ) -> Result<TurnQueuedInputResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Follow-up 不属于此应用锁测试，保持 fail-closed。
    fn turn_follow_up(
        &self,
        _input: TurnQueuedInput,
    ) -> Result<TurnQueuedInputResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }

    /// Approval 不属于此应用锁测试，保持 fail-closed。
    fn approval_respond(&self, _input: ApprovalResponseInput) -> Result<(), RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
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
        trust: &str,
    ) -> Result<WorkspaceRuntimeSource, RuntimeCommandError> {
        if self.root.as_path() != std::path::Path::new(cwd) || trust != "trusted" {
            return Err(RuntimeCommandError::invalid_params());
        }
        Ok(WorkspaceRuntimeSource {
            root: self.root.clone(),
            display_name: display_name.unwrap_or("Lock order").to_owned(),
            trust: trust.to_owned(),
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
        trust: "trusted".to_owned(),
    })
    .expect("store fixture workspace binding");
    (host, bridge)
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
