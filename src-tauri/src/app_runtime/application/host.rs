// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 由 Tauri 管理的惰性 Runtime composition。

use super::{
    ConfigurationRequest, ConfigurationResponse, HistoryRequest, HistoryResponse,
    RuntimeBridgePort, RuntimeCommandError, RuntimePlatformPort, SettingsRequest, SettingsResponse,
    TurnChangeCaptureContext,
};
use crate::app_runtime::domain::{
    ApprovalResponseInput, AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata,
    GeneralWorkspace, ManualRecoveryConfirmation, RuntimeConfigurationStatus, RuntimeRecoveryState,
    RuntimeStatus, RuntimeStatusKind, RuntimeStorageInfo, ToolArtifactReadInput,
    ToolArtifactReadResult, TurnAccepted, TurnCancelInput, TurnCancelResult,
    TurnChangeSetReadInput, TurnChangeSetReadResult, TurnQueuedInput, TurnQueuedInputResult,
    TurnStartInput, WorkspaceDto, WorkspaceOpenInput,
};
use crate::workspace::{WorkspaceHandle, WorkspaceRegistry};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Instant;

/// 持有受信任启动配置，并只在恢复门禁清除后惰性创建唯一 bridge；因此 setup 可先呈现恢复 UI，而不会在未知旧进程上启动新 sidecar。
#[derive(Clone)]
pub struct RuntimeHost {
    platform: Arc<dyn RuntimePlatformPort>,
    pub(crate) bridge: Arc<Mutex<Option<Arc<dyn RuntimeBridgePort>>>>,
    pub(crate) workspace: Arc<Mutex<Option<ConfiguredWorkspace>>>,
}

/// 将协议 workspace id 绑定到 Runtime host 准入的规范 handle；内部 registry UUID 永远不跨越该边界。
#[derive(Clone)]
pub(crate) struct ConfiguredWorkspace {
    workspace_id: Option<String>,
    projection_root: Option<String>,
    display_name: String,
    trust: String,
    revision: Option<u64>,
    handle: WorkspaceHandle,
}

/// 描述强类型 workspace command 无法准入的稳定原因，既不暴露 root 路径，也不允许调用方选择任意 handle。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WorkspaceLookup {
    Unconfigured,
    Unknown,
}

impl RuntimeHost {
    /// composition root 注入冻结的原生 platform；application 不知道 LaunchConfig、进程或文件实现。
    pub(crate) fn compose(platform: Arc<dyn RuntimePlatformPort>) -> Self {
        Self {
            platform,
            bridge: Arc::new(Mutex::new(None)),
            workspace: Arc::new(Mutex::new(None)),
        }
    }

    /// bridge slot 存放唯一原生 owner；中毒表示某次 mutation 可能只完成了一部分，
    /// application 无法证明其进程与 actor 一致性，因此必须关闭失败而不得取出中毒数据。
    fn bridge_guard(
        &self,
    ) -> Result<MutexGuard<'_, Option<Arc<dyn RuntimeBridgePort>>>, RuntimeCommandError> {
        self.bridge
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())
    }

    /// Workspace binding 同时承载 identity、trust 与 capability handle；中毒后任一字段都不再可作为授权事实。
    fn workspace_guard(
        &self,
    ) -> Result<MutexGuard<'_, Option<ConfiguredWorkspace>>, RuntimeCommandError> {
        self.workspace
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())
    }

    /// 每次重新检查原生恢复状态后才返回 bridge；mutex 只覆盖构造，不覆盖慢速 sidecar I/O 或命令等待。
    fn ensure_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        let mut bridge = self.bridge_guard()?;
        if let Some(current) = bridge.as_ref() {
            return Ok(current.clone());
        }
        if self.platform.recovery_state().required {
            return Err(RuntimeCommandError::recovery_required());
        }
        let current = self.platform.create_bridge()?;
        *bridge = Some(current.clone());
        Ok(current)
    }

    /// 通过所有强类型命令共用的唯一原生 owner 启动惰性 bridge；任何 WebView 字段都不能选择可执行文件或环境。
    pub fn start(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        let bridge = match self.ensure_bridge() {
            Ok(bridge) => bridge,
            Err(error) => {
                self.clear_workspace()?;
                return Err(error);
            }
        };
        let status = match bridge.start() {
            Ok(status) => status,
            Err(error) => {
                self.clear_workspace()?;
                return Err(error);
            }
        };
        if status.status != RuntimeStatusKind::Ready {
            self.clear_workspace()?;
            return Err(RuntimeCommandError::unavailable());
        }
        let result = self
            .open_configured_workspace_if_present(&bridge)
            .and_then(|_| bridge.health());
        if let Err(error) = result {
            self.clear_workspace()?;
            let _ = bridge.stop();
            return Err(error);
        }
        Ok(status)
    }

    /// 停止既有 bridge；若未解决 marker 禁止创建 owner，则保留 recovery-required 结果而非伪造干净停止。
    pub fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone();
        let result = match bridge {
            Some(bridge) => bridge.stop(),
            None if self.platform.recovery_state().required => {
                Err(RuntimeCommandError::recovery_required())
            }
            None => Ok(RuntimeStatus {
                status: RuntimeStatusKind::Stopped,
                generation: 0,
                server_instance_id: None,
            }),
        };
        // Stop 会使完整 binding 失效，因此再次启动必须重新显式配置并调用 workspace/open，不能回放隐式 active-workspace cache。
        self.clear_workspace()?;
        result
    }

    /// bridge 存在时返回其状态，否则仅向 UI shell 暴露最小且不含 token 的 RecoveryRequired/Stopped 投影。
    pub fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone();
        match bridge {
            Some(bridge) => bridge.state(),
            None if self.platform.recovery_state().required => Ok(RuntimeStatus {
                status: RuntimeStatusKind::RecoveryRequired,
                generation: 0,
                server_instance_id: None,
            }),
            None => Ok(RuntimeStatus {
                status: RuntimeStatusKind::Stopped,
                generation: 0,
                server_instance_id: None,
            }),
        }
    }

    /// 投影 host-owned runtime 目录，不虚构当前 sidecar 未实际创建的 log 或 cache 存储；JVM debug 启动只由固定 `-jar` 参数识别，WebView 输入不能影响该策略。
    pub fn storage_info(&self) -> RuntimeStorageInfo {
        self.platform.storage_info()
    }

    /// 仅从已 Ready generation 读取 Java-owned general workspace，再把 server identity 绑定到原生 capability handle；禁止创建本地 fallback 目录或 ID。
    pub fn general_workspace(&self) -> Result<GeneralWorkspace, RuntimeCommandError> {
        self.clear_workspace()?;
        let bridge = self.ready_bridge()?;
        let projection = bridge.general_workspace()?;
        let registry = WorkspaceRegistry::default();
        let workspace_info = registry
            .register(std::path::Path::new(&projection.root))
            .map_err(|_| RuntimeCommandError::unavailable())?;
        let workspace_handle = registry
            .get(workspace_info.id)
            .map_err(|_| RuntimeCommandError::unavailable())?;
        let root_path = workspace_handle
            .root_path()
            .to_str()
            .filter(|value| !value.is_empty())
            .ok_or_else(RuntimeCommandError::unavailable)?
            .to_owned();
        let public = GeneralWorkspace {
            workspace_id: projection.workspace_id.clone(),
            display_name: projection.display_name.clone(),
            trust: projection.trust.clone(),
            root_path,
        };
        *self.workspace_guard()? = Some(ConfiguredWorkspace {
            workspace_id: Some(projection.workspace_id),
            projection_root: Some(projection.root),
            display_name: projection.display_name,
            trust: projection.trust,
            revision: Some(projection.revision),
            handle: workspace_handle,
        });
        Ok(public)
    }

    /// 仅通过已通过恢复与受信任配置检查的 bridge 路由强类型 Turn，确保生命周期门禁唯一。
    pub fn turn_start(&self, input: TurnStartInput) -> Result<TurnAccepted, RuntimeCommandError> {
        let bridge = self.ensure_bridge()?;
        let capture = {
            let workspace = self.workspace_guard()?;
            let binding = workspace
                .as_ref()
                .ok_or_else(RuntimeCommandError::unavailable)?;
            TurnChangeCaptureContext {
                workspace_id: binding
                    .workspace_id
                    .clone()
                    .ok_or_else(RuntimeCommandError::unavailable)?,
                workspace: binding.handle.clone(),
            }
        };
        bridge.turn_start_with_workspace(input, capture)
    }

    /// 通过当前 bridge 路由取消而不替换或停止 sidecar；完成事实仍只能从事件通道到达。
    pub fn turn_cancel(
        &self,
        input: TurnCancelInput,
    ) -> Result<TurnCancelResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_cancel(input)
    }

    /// 在当前 Java-owned Turn 上排队 steering，不把 FIFO 投影到 Rust 状态，也不暴露通用协议 method。
    pub fn turn_steer(
        &self,
        input: TurnQueuedInput,
    ) -> Result<TurnQueuedInputResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_steer(input)
    }

    /// 在完成边界排队 follow-up，同时维持 Java 对 admission、持久化、取消与恢复的唯一所有权。
    pub fn turn_follow_up(
        &self,
        input: TurnQueuedInput,
    ) -> Result<TurnQueuedInputResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_follow_up(input)
    }

    /// active Workspace binding 是 artifact reader 的第一层授权；Java 再校验 Thread/Turn/Artifact
    /// 关联，Rust 不保存本地副本或回退到当前 worktree。
    pub fn turn_change_set_read(
        &self,
        workspace_id: &str,
        input: TurnChangeSetReadInput,
    ) -> Result<TurnChangeSetReadResult, RuntimeCommandError> {
        self.authorize_workspace_identity(workspace_id)?;
        self.ready_bridge()?.turn_change_set_read(input)
    }

    /// Tool artifact 使用同一 active Workspace 门禁，并把 call identity 原样交给 Java owner 复核。
    pub fn tool_artifact_read(
        &self,
        workspace_id: &str,
        input: ToolArtifactReadInput,
    ) -> Result<ToolArtifactReadResult, RuntimeCommandError> {
        self.authorize_workspace_identity(workspace_id)?;
        self.ready_bridge()?.tool_artifact_read(input)
    }

    /// 只比较 App Server 分配的 opaque workspace identity；调用方不能通过 reader 选择路径。
    fn authorize_workspace_identity(&self, workspace_id: &str) -> Result<(), RuntimeCommandError> {
        self.with_configured_workspace(workspace_id, |_| ())
            .map_err(|lookup| match lookup {
                WorkspaceLookup::Unconfigured => RuntimeCommandError::unavailable(),
                WorkspaceLookup::Unknown => RuntimeCommandError::invalid_params(),
            })
    }

    /// 仅通过已配置且 Ready 的 generation 路由白名单 history request，防止查询启动或重配置 sidecar。
    pub(crate) fn history_request(
        &self,
        request: HistoryRequest,
    ) -> Result<HistoryResponse, RuntimeCommandError> {
        // History 是查询面而非生命周期触发器；要求 bridge 已存在可阻止启动前命令构造尚未被 `ja_runtime_start` 准入的新 actor。
        let bridge = self.bridge_guard()?.clone().ok_or(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready",
            retryable: true,
        })?;
        let status = bridge.state()?;
        if status.status != RuntimeStatusKind::Ready {
            return Err(RuntimeCommandError {
                code: "RUNTIME_NOT_READY",
                message: "runtime is not ready",
                retryable: true,
            });
        }
        bridge.history(request)
    }

    /// general-workspace 读取跨越 stdio 前要求 sidecar 完成当前握手；该命令绝不隐式启动 Java，也不回退到 Rust-owned 存储。
    fn ready_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone().ok_or(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready",
            retryable: true,
        })?;
        if bridge.state()?.status != RuntimeStatusKind::Ready {
            return Err(RuntimeCommandError {
                code: "RUNTIME_NOT_READY",
                message: "runtime is not ready",
                retryable: true,
            });
        }
        Ok(bridge)
    }

    /// 配置与凭据请求在已启动的 Ready/Busy generation 上直接复用 bridge，避免每次读取都
    /// 重放 workspace/open 与 health；只有未启动或故障状态才进入完整 start 恢复链。
    fn configuration_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        let bridge = match self.ensure_bridge() {
            Ok(bridge) => bridge,
            Err(error) => {
                self.clear_workspace()?;
                return Err(error);
            }
        };
        if bridge.state().is_ok_and(|status| {
            matches!(
                status.status,
                RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
            )
        }) {
            return Ok(bridge);
        }
        self.start()?;
        Ok(bridge)
    }

    /// 通过当前 Ready generation 路由固定 Skills/MCP settings 查询；与配置入口不同，该命令绝不启动 sidecar。
    pub(crate) fn settings_query(
        &self,
        request: SettingsRequest,
    ) -> Result<SettingsResponse, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone().ok_or(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready",
            retryable: true,
        })?;
        let status = bridge.state()?;
        if status.status != RuntimeStatusKind::Ready {
            return Err(RuntimeCommandError {
                code: "RUNTIME_NOT_READY",
                message: "runtime is not ready",
                retryable: true,
            });
        }
        bridge.settings(request)
    }

    /// 通过当前或恢复后的 Ready generation 路由固定 Java 配置与凭据白名单；调用侧 DTO
    /// 负责 method-specific 校验，Host 负责避免健康 generation 的重复启动与 Workspace 重放。
    pub(crate) fn config_request(
        &self,
        request: ConfigurationRequest,
    ) -> Result<ConfigurationResponse, RuntimeCommandError> {
        self.configuration_bridge()?.configuration(request)
    }

    /// 校验原生 cwd 并保存其 capability handle；Java workspace identity 必须在启动后由 `workspace/open` 分配。
    pub fn open_workspace(
        &self,
        input: WorkspaceOpenInput,
    ) -> Result<RuntimeConfigurationStatus, RuntimeCommandError> {
        let source = self.platform.workspace_source(
            &input.cwd,
            input.display_name.as_deref(),
            &input.trust,
        )?;
        // 通过既有 registry 准入规范 root，使所有 read/Git command 共享同一物理 identity，且任何命令都不能替换自己的绝对路径。
        let registry = WorkspaceRegistry::default();
        let workspace_info = registry
            .register(&source.root)
            .map_err(|_| RuntimeCommandError::configuration())?;
        let workspace_handle = registry
            .get(workspace_info.id)
            .map_err(|_| RuntimeCommandError::configuration())?;
        let mut display_name = if source.display_name.is_empty() {
            source
                .root
                .file_name()
                .and_then(|name| name.to_str())
                .filter(|name| !name.is_empty())
                .unwrap_or("Workspace")
                .to_owned()
        } else {
            source.display_name.clone()
        };
        let existing = self.bridge_guard()?.clone();
        let mut workspace_id = None;
        let mut projection_root = None;
        let mut workspace_revision = None;
        let mut effective_trust = source.trust.clone();
        if let Some(bridge) = existing
            && bridge.state()?.status == RuntimeStatusKind::Ready
        {
            let projection = bridge.workspace_open(
                workspace_handle.root_path().to_path_buf(),
                display_name.clone(),
                source.trust.clone(),
            )?;
            bridge.health()?;
            workspace_id = Some(projection.workspace_id);
            projection_root = Some(projection.root);
            display_name = projection.display_name;
            effective_trust = projection.trust;
            workspace_revision = Some(projection.revision);
        }
        let result_workspace_id = workspace_id.clone();
        let result_cwd = workspace_handle.root_path().to_string_lossy().into_owned();
        let result_display_name = display_name.clone();
        let result_trust = effective_trust.clone();
        *self.workspace_guard()? = Some(ConfiguredWorkspace {
            workspace_id,
            projection_root,
            display_name,
            trust: effective_trust,
            revision: workspace_revision,
            handle: workspace_handle,
        });
        Ok(RuntimeConfigurationStatus {
            accepted: true,
            workspace_id: result_workspace_id,
            cwd: result_cwd,
            display_name: result_display_name,
            trust: result_trust,
        })
    }

    /// 打开一个项目并返回生产 history adapter 所需的精确 Java-owned Workspace DTO；旧 Host acceptance 状态不得跨越 WebView IPC。
    pub fn open_workspace_projection(
        &self,
        input: WorkspaceOpenInput,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.open_workspace(input)?;
        let workspace = self.workspace_guard()?;
        let binding = workspace
            .as_ref()
            .ok_or_else(RuntimeCommandError::unavailable)?;
        Ok(WorkspaceDto {
            workspace_id: binding
                .workspace_id
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable)?,
            root: binding
                .projection_root
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable)?,
            display_name: binding.display_name.clone(),
            trust: binding.trust.clone(),
            revision: binding
                .revision
                .ok_or_else(RuntimeCommandError::unavailable)?,
        })
    }

    /// 把“启动当前 generation”与“注册 Workspace”组合为一个 application 用例；interface 只提交
    /// intent，不能在 command 中复制生命周期顺序。启动或 workspace/open 任一步失败都沿用现有
    /// Host 清理与错误语义，不额外引入补偿状态。
    pub fn start_and_open_workspace(
        &self,
        input: WorkspaceOpenInput,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        // 先完成握手、已配置 Workspace 回放与 health，再允许新的显式 Workspace 信任动作。
        self.start()?;
        // open_workspace_projection 负责原生路径准入、Java identity 分配和本地 binding 提交。
        self.open_workspace_projection(input)
    }

    /// 先在不持有 Workspace 锁时确认 Runtime generation 仍 Ready，再持有 binding lock
    /// 执行只读操作。该固定顺序避免 Runtime actor 的 Turn 回调反向等待 Workspace 锁，
    /// 同时仍让 Workspace 切换与 handle 使用在线性化临界区内互斥。
    pub(crate) fn with_configured_workspace<T>(
        &self,
        workspace_id: &str,
        operation: impl FnOnce(&WorkspaceHandle) -> T,
    ) -> Result<T, WorkspaceLookup> {
        // 生命周期查询会进入单 owner actor；必须在取得 Workspace binding 锁之前完成，
        // 否则 actor 内的 Turn/dirty 回调会形成 Workspace -> actor -> Workspace 的互锁环。
        if !self.workspace_access_ready() {
            return Err(WorkspaceLookup::Unknown);
        }
        let workspace = self
            .workspace
            .lock()
            .map_err(|_| WorkspaceLookup::Unknown)?;
        let Some(binding) = workspace.as_ref() else {
            return Err(WorkspaceLookup::Unconfigured);
        };
        // Ready 检查之后仍按当前 binding 的 Java identity 做二次归属校验；并发切换即使
        // 发生在两个阶段之间，也不能让旧调用方取得新 Workspace 的 capability handle。
        if binding.workspace_id.as_deref() != Some(workspace_id) {
            return Err(WorkspaceLookup::Unknown);
        }
        Ok(operation(&binding.handle))
    }

    /// 返回当前 lifecycle 是否仍持有可读 Workspace。调用方不得已经持有 Workspace 锁；
    /// 该约束统一全模块为 Runtime owner → Workspace binding 的锁顺序，故障 generation
    /// 与尚未启动的惰性配置均保持 fail-closed。
    fn workspace_access_ready(&self) -> bool {
        let bridge = match self.bridge.lock() {
            Ok(bridge) => bridge.clone(),
            Err(_) => return false,
        };
        match bridge {
            // configure 成功只会冻结 binding 而不会启动 generation；在权威 Ready 投影出现前，命令始终保持关闭。
            None => false,
            Some(bridge) => bridge
                .state()
                .map(|status| status.status == RuntimeStatusKind::Ready)
                .unwrap_or(false),
        }
    }

    /// 当配置本身不再权威（例如应用 shutdown）时删除完整原生 binding，避免旧 capability 延续到下一生命周期。
    fn clear_workspace(&self) -> Result<(), RuntimeCommandError> {
        self.workspace_guard()?.take();
        Ok(())
    }

    /// 在当前 Java generation 打开已配置规范 root，并在准入任何 history 或 Turn request
    /// 前保存其 opaque result。跨进程调用只使用 binding 快照且不持有 Workspace 锁；返回后
    /// 通过 handle identity 做 CAS，避免 actor 回调反向取锁或陈旧结果覆盖并发配置。
    fn open_configured_workspace_if_present(
        &self,
        bridge: &Arc<dyn RuntimeBridgePort>,
    ) -> Result<String, RuntimeCommandError> {
        // 第一阶段只在锁内复制受信 capability 与显示字段；任何 actor/stdio 等待都不能
        // 跨越 Workspace 锁，否则 Turn admission callback 会与启动重放形成锁顺序反转。
        let Some((expected_handle_id, root, display_name, trust)) = ({
            let workspace = self.workspace_guard()?;
            workspace.as_ref().map(|binding| {
                (
                    binding.handle.id(),
                    binding.handle.root_path().to_path_buf(),
                    binding.display_name.clone(),
                    binding.trust.clone(),
                )
            })
        }) else {
            return Ok(String::new());
        };

        // 第二阶段由 actor 串行执行 Java workspace/open；此时 Workspace 锁为空闲，事件回调
        // 可以读取当前 binding，且跨进程等待不会阻塞本地 Files/Review capability 查询。
        let projection = bridge.workspace_open(root, display_name, trust)?;

        // 第三阶段重新取得 binding 并做 handle CAS。若并发配置已经提交，它对应的 actor
        // 请求必然位于本请求之后；保留较新的 Java identity，禁止旧结果回写。
        let mut workspace = self.workspace_guard()?;
        let Some(binding) = workspace.as_mut() else {
            return Err(RuntimeCommandError::unavailable());
        };
        if binding.handle.id() != expected_handle_id {
            return binding
                .workspace_id
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable);
        }
        binding.workspace_id = Some(projection.workspace_id.clone());
        binding.projection_root = Some(projection.root);
        binding.display_name = projection.display_name;
        binding.trust = projection.trust;
        binding.revision = Some(projection.revision);
        Ok(projection.workspace_id)
    }

    /// 仅将用户审批路由到当前持有的 sidecar session；该命令不像通用 RPC 那样能指定任意 method 或 response ID，从而保持 WebView 投影强类型。
    pub fn approval_respond(
        &self,
        input: ApprovalResponseInput,
    ) -> Result<(), RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let bridge = self
            .bridge_guard()?
            .clone()
            .ok_or_else(RuntimeCommandError::unavailable)?;
        bridge.approval_respond(input)
    }

    /// 把 Rust-only ingress token 与当前已配置 Workspace identity 绑定后提交给 Java；调用者不能替换 workspaceId。
    pub(crate) fn attachment_import(
        &self,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let bridge = self.ready_bridge()?;
        let workspace_id = self
            .workspace_guard()?
            .as_ref()
            .and_then(|workspace| workspace.workspace_id.clone())
            .ok_or_else(RuntimeCommandError::unavailable)?;
        bridge.attachment_import(workspace_id, input)
    }

    /// 删除草稿附件只经过当前 Ready Java owner；Rust 不维护第二份附件状态或物理 blob 映射。
    pub(crate) fn attachment_discard(
        &self,
        input: AttachmentDiscardInput,
    ) -> Result<(), RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.attachment_discard(input)
    }

    /// 只暴露构造强类型确认所需的已脱敏 recovery identity；marker 路径与进程细节必须留在原生侧。
    pub fn recovery_state(&self) -> RuntimeRecoveryState {
        self.platform.recovery_state()
    }

    /// 原子确认当前 marker/tombstone 后继续保持 bridge 惰性；只有持久门禁确实消失，下一次启动才创建新 owner。
    pub fn acknowledge_recovery(
        &self,
        confirmation: &ManualRecoveryConfirmation,
    ) -> Result<RuntimeRecoveryState, RuntimeCommandError> {
        if self.bridge_guard()?.is_some() {
            return Err(RuntimeCommandError::unavailable());
        }
        self.platform.acknowledge_recovery(confirmation)
    }

    /// Tauri 请求应用退出时执行有界 actor/process 清理；未解决 recovery marker 不存在可停止的活动 owner。
    pub fn shutdown(&self) -> Result<(), RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone();
        let result = match bridge {
            Some(bridge) => bridge.shutdown(),
            None => Ok(()),
        };
        // 即使原生清理需要恢复，Shutdown 也会使所有旧 workspace handle 失效；后续启动前必须重新 configure。
        self.clear_workspace()?;
        result
    }

    /// 在调用方持有的同一个绝对 deadline 内执行 host 清理，避免关闭其他原生资源后又重新获得完整 bridge timeout。
    pub fn shutdown_until(&self, deadline: Instant) -> Result<(), RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone();
        let result = match bridge {
            Some(bridge) => bridge.shutdown_until(deadline),
            None => Ok(()),
        };
        // 即使原生清理需要恢复，Shutdown 也会使所有旧 workspace handle 失效；后续启动前必须重新 configure。
        self.clear_workspace()?;
        result
    }

    /// 报告当前受管 owner 是否允许最终 Tauri Exit；没有 bridge 的 host 已不存在 child process owner。
    pub fn exit_ready(&self) -> bool {
        self.bridge
            .lock()
            .map(|bridge| bridge.as_ref().is_none_or(|bridge| bridge.exit_ready()))
            .unwrap_or(false)
    }

    /// 仅当强制平台退出前无法清理活动 bridge 时持久化原生诊断 marker，避免正常退出制造伪恢复状态。
    pub fn record_forced_exit(&self) {
        match self.bridge.lock() {
            Ok(bridge) => {
                if let Some(bridge) = bridge.as_ref() {
                    bridge.record_forced_exit();
                }
            }
            Err(_) => {
                tracing::error!("runtime bridge owner lock poisoned before forced-exit record");
            }
        }
    }
}
