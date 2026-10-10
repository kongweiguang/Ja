// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 由 Tauri 管理的惰性 Runtime composition。

use super::{
    ConfigurationRequest, ConfigurationResponse, HistoryRequest, HistoryResponse,
    RuntimeBridgePort, RuntimeCommandError, RuntimePlatformPort, SettingsRequest, SettingsResponse,
};
use crate::app_runtime::domain::valid_protocol_id;
use crate::app_runtime::domain::{
    ApprovalResponseInput, AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata,
    ManualRecoveryConfirmation, RuntimeRecoveryState, RuntimeStatus, RuntimeStatusKind,
    RuntimeStorageInfo, TaskCloseInput, TaskCloseResult, TaskCreateInput, TaskCreateResult,
    TaskFollowupInput, TaskFollowupResult, TaskListInput, TaskListResult, TaskMessageInput,
    TaskMessageResult, TaskMutationInput, TaskObserveInput, TaskObserveResult, TaskReadInput,
    TaskReadResult, TaskSeenInput, TaskSummary, TaskTreeDeleteInput, TaskTreeDeleteResult,
    TaskUnobserveInput, ToolArtifactReadInput, ToolArtifactReadResult, ToolRecoveryResponse,
    ToolRecoveryResponseInput, TurnAccepted, TurnCancelInput, TurnCancelResult,
    TurnChangeSetReadInput, TurnChangeSetReadResult, TurnContinueInput, TurnInputDelete,
    TurnInputEnqueue, TurnInputPrioritize, TurnInputResult, TurnInputUpdate, TurnReaskInput,
    TurnResumeInput, TurnStartInput, WorkflowRequest, WorkflowResponse, WorkspaceActivation,
    WorkspaceDto, WorkspaceKind, WorkspaceOpenInput, WorkspacePathSearchInput,
    WorkspacePathSearchResult, WorkspaceUpdateInput,
};
use crate::workspace::{WorkspaceHandle, WorkspaceRegistry};
use ja_runtime::app_server_process::{
    AttachmentPreviewCloseParams, AttachmentPreviewOpenParams, AttachmentPreviewOpenResult,
    AttachmentPreviewReadParams, AttachmentPreviewReadResult,
};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use std::{
    collections::{HashMap, HashSet},
    path::Path,
};

const WORKSPACE_REBIND_DRAIN_TIMEOUT: Duration = Duration::from_secs(5);

/// 持有受信任启动配置，惰性创建共享后台客户端 bridge；是否存在 Java owner 由连接握手决定。
#[derive(Clone)]
pub struct RuntimeHost {
    platform: Arc<dyn RuntimePlatformPort>,
    pub(crate) bridge: Arc<Mutex<Option<Arc<dyn RuntimeBridgePort>>>>,
    pub(crate) workspace: Arc<Mutex<WorkspaceBindings>>,
}

/// App Server 进程可重置 generation；原生 Workspace capability 因此必须绑定双字段身份。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct RuntimeIdentity {
    server_instance_id: String,
    generation: u64,
}

impl RuntimeIdentity {
    /// 只接受完整且符合 JA-RPC 标识约束的 Ready Runtime 身份，缺字段时不复用旧授权。
    pub(crate) fn new(
        server_instance_id: String,
        generation: u64,
    ) -> Result<Self, RuntimeCommandError> {
        if generation == 0 || !valid_protocol_id(&server_instance_id, "srv_", 101) {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(Self {
            server_instance_id,
            generation,
        })
    }

    /// 从服务端状态冻结 identity；跨实例 generation 不比较大小，只比较完整元组。
    fn from_status(status: &RuntimeStatus) -> Result<Self, RuntimeCommandError> {
        if !matches!(
            status.status,
            RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
        ) {
            return Err(RuntimeCommandError::unavailable());
        }
        Self::new(
            status
                .server_instance_id
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable)?,
            status.generation,
        )
    }
}

/// 将协议 workspace id 绑定到 Runtime host 准入的规范 handle；内部 registry UUID 永远不跨越该边界。
#[derive(Clone)]
pub(crate) struct ConfiguredWorkspace {
    workspace_id: String,
    projection_root: Option<String>,
    display_name: String,
    trust: String,
    revision: Option<u64>,
    kind: WorkspaceKind,
    runtime_identity: RuntimeIdentity,
    handle: WorkspaceHandle,
}

impl ConfiguredWorkspace {
    /// 将 Java 投影与发起该投影的服务身份一并冻结，迟到响应不得跨实例创建能力。
    pub(crate) fn from_projection(
        projection: WorkspaceDto,
        handle: WorkspaceHandle,
        runtime_identity: RuntimeIdentity,
    ) -> Self {
        Self {
            workspace_id: projection.workspace_id,
            projection_root: Some(projection.root),
            display_name: projection.display_name,
            trust: projection.trust,
            revision: Some(projection.revision),
            kind: projection.kind,
            runtime_identity,
            handle,
        }
    }
}

/// 待处理槽位仅用于 Java 启动前选定的项目；此后每个 Java 签发的工作区都按身份保存，因此快速切换 Thread 不会丢弃仍在使用的原生句柄。
#[derive(Default)]
pub(crate) struct WorkspaceBindings {
    pub(crate) pending_project: Option<PendingProjectWorkspace>,
    pub(crate) active_workspace_id: Option<String>,
    pub(crate) by_id: HashMap<String, ConfiguredWorkspace>,
    pending_rebind: HashSet<String>,
    pending_metadata_update: HashSet<String>,
    pending_metadata_revision: HashMap<String, u64>,
    unresolved_workspace_updates: HashSet<String>,
    runtime_identity: Option<RuntimeIdentity>,
}

impl WorkspaceBindings {
    /// 任一 identity 字段改变都撤销旧 capability；启动前的显式项目选择继续等待 Java 签发 ID。
    pub(crate) fn synchronize_identity(&mut self, identity: RuntimeIdentity) {
        if self.runtime_identity.as_ref() != Some(&identity) {
            for binding in self.by_id.values() {
                binding.handle.revoke();
            }
            self.by_id.clear();
            self.active_workspace_id = None;
            self.pending_rebind.clear();
            self.pending_metadata_update.clear();
            self.pending_metadata_revision.clear();
            self.unresolved_workspace_updates.clear();
        }
        self.runtime_identity = Some(identity);
    }

    /// 只提交仍属于当前服务身份的投影，并保留稳定 Workspace root 对应的原生 handle。
    pub(crate) fn insert_binding(
        &mut self,
        mut binding: ConfiguredWorkspace,
    ) -> Result<ConfiguredWorkspace, RuntimeCommandError> {
        if self.runtime_identity.as_ref() != Some(&binding.runtime_identity) {
            return Err(RuntimeCommandError::unavailable());
        }
        if let Some(existing) = self.by_id.get(&binding.workspace_id) {
            if existing.runtime_identity != binding.runtime_identity
                || existing.kind != binding.kind
                || !existing.handle.same_physical_root(&binding.handle)
            {
                return Err(RuntimeCommandError::configuration());
            }
            binding.handle = existing.handle.clone();
        }
        self.by_id
            .insert(binding.workspace_id.clone(), binding.clone());
        Ok(binding)
    }

    /// 根目录 CAS 成功后允许同 ID 替换物理 root，并先撤销旧句柄，防止克隆 handle 跨代继续 IO。
    fn replace_binding(
        &mut self,
        binding: ConfiguredWorkspace,
    ) -> Result<ConfiguredWorkspace, RuntimeCommandError> {
        if self.runtime_identity.as_ref() != Some(&binding.runtime_identity) {
            return Err(RuntimeCommandError::unavailable());
        }
        if let Some(existing) = self.by_id.get(&binding.workspace_id) {
            if existing.kind != binding.kind {
                return Err(RuntimeCommandError::configuration());
            }
            if !existing.handle.same_physical_root(&binding.handle) {
                existing.handle.revoke();
            }
        }
        self.by_id
            .insert(binding.workspace_id.clone(), binding.clone());
        Ok(binding)
    }
}

type WorkspaceMetadataReconciler = dyn Fn(String, u64, String, u64) + Send + Sync + 'static;

/// Java metadata 通知先冻结短暂的旧 root IO，再异步回读权威 projection；同 root 只更新元数据。
#[derive(Clone, Default)]
pub struct WorkspaceMetadataEventRouter {
    workspace: Arc<Mutex<WorkspaceBindings>>,
    reconciler: Arc<Mutex<Option<Arc<WorkspaceMetadataReconciler>>>>,
}

impl WorkspaceMetadataEventRouter {
    /// 注入 Host-owned 异步回读，保证 event-pump 不同步重入自己的 JA-RPC actor。
    fn install_reconciler(&self, reconciler: Arc<WorkspaceMetadataReconciler>) {
        if let Ok(mut slot) = self.reconciler.lock() {
            *slot = Some(reconciler);
        }
    }

    /// application 只消费 interface 已提取的身份字段，避免协议 JSON 渗入用例层；迟到 revision 仍由运行时身份围栏拒绝。
    pub(crate) fn observe(
        &self,
        server_id: &str,
        workspace_id: &str,
        revision: u64,
        generation: u64,
    ) {
        let Ok(mut bindings) = self.workspace.lock() else {
            return;
        };
        let same_runtime = bindings.runtime_identity.as_ref().is_some_and(|identity| {
            identity.server_instance_id == server_id && identity.generation == generation
        });
        if !same_runtime || bindings.pending_metadata_update.contains(workspace_id) {
            return;
        }
        let Some(binding) = bindings.by_id.get(workspace_id) else {
            return;
        };
        if binding
            .revision
            .is_none_or(|bound_revision| revision <= bound_revision)
        {
            return;
        }
        let binding = binding.clone();
        let pending = bindings
            .pending_metadata_revision
            .entry(workspace_id.to_owned())
            .or_insert(revision);
        *pending = (*pending).max(revision);
        binding.handle.freeze_for_metadata_refresh();
        drop(bindings);

        let Ok(reconciler) = self.reconciler.lock() else {
            return;
        };
        let Some(reconciler) = reconciler.clone() else {
            return;
        };
        let workspace_id = workspace_id.to_owned();
        let server_id = server_id.to_owned();
        let _ = std::thread::Builder::new()
            .name("ja-workspace-metadata-refresh".to_owned())
            .spawn(move || reconciler(workspace_id, revision, server_id, generation));
    }
}

/// Authority read 的版本低于事件时保留 frozen fence；只有确认同一物理 root 才更新 metadata 并唤醒旧句柄。
fn reconcile_workspace_metadata_projection(
    workspace: &Arc<Mutex<WorkspaceBindings>>,
    expected_identity: &RuntimeIdentity,
    workspace_id: &str,
    announced_revision: u64,
    observed: WorkspaceDto,
) -> bool {
    if observed.workspace_id != workspace_id || observed.revision < announced_revision {
        return false;
    }
    let Ok(mut bindings) = workspace.lock() else {
        return false;
    };
    if bindings.runtime_identity.as_ref() != Some(expected_identity) {
        return false;
    }
    let Some(previous) = bindings.by_id.get(workspace_id).cloned() else {
        bindings.pending_metadata_revision.remove(workspace_id);
        return false;
    };
    let previous_revision = previous.revision.unwrap_or(0);
    if previous_revision >= observed.revision {
        if bindings
            .pending_metadata_revision
            .get(workspace_id)
            .is_some_and(|pending| *pending <= previous_revision)
        {
            bindings.pending_metadata_revision.remove(workspace_id);
            previous.handle.thaw_after_aborted_rebind();
        }
        return false;
    }

    let same_root = previous.kind == observed.kind
        && workspace_root_matches_handle(&observed.root, &previous.handle);
    if same_root {
        if let Some(current) = bindings.by_id.get_mut(workspace_id)
            && current.revision == previous.revision
            && current.handle.same_physical_root(&previous.handle)
        {
            current.projection_root = Some(observed.root);
            current.display_name = observed.display_name;
            current.trust = observed.trust;
            current.revision = Some(observed.revision);
            if bindings
                .pending_metadata_revision
                .get(workspace_id)
                .is_some_and(|pending| *pending <= observed.revision)
            {
                bindings.pending_metadata_revision.remove(workspace_id);
                previous.handle.thaw_after_aborted_rebind();
            }
        }
        return false;
    }

    drop(bindings);
    // Root replacement is already frozen. Drain best-effort, then revoke even after timeout so old clones cannot reenter.
    let _ = previous
        .handle
        .freeze_and_drain(WORKSPACE_REBIND_DRAIN_TIMEOUT);
    let Ok(mut bindings) = workspace.lock() else {
        previous.handle.revoke();
        return true;
    };
    let still_previous = bindings.by_id.get(workspace_id).is_some_and(|current| {
        current.runtime_identity == *expected_identity
            && current.revision == previous.revision
            && current.handle.same_physical_root(&previous.handle)
    });
    if still_previous {
        if let Some(binding) = bindings.by_id.remove(workspace_id) {
            binding.handle.revoke();
        }
        if bindings.active_workspace_id.as_deref() == Some(workspace_id) {
            bindings.active_workspace_id = None;
        }
        if bindings
            .pending_metadata_revision
            .get(workspace_id)
            .is_some_and(|pending| *pending <= observed.revision)
        {
            bindings.pending_metadata_revision.remove(workspace_id);
        }
    }
    true
}

/// 在惰性 Java 所有者分配持久工作区 ID 前，保留原生项目根目录。
pub(crate) struct PendingProjectWorkspace {
    display_name: String,
    handle: WorkspaceHandle,
}

impl PendingProjectWorkspace {
    /// 保留未绑定目录供首次 Ready 时重放；它不是任何旧服务实例的 capability。
    pub(crate) fn new(display_name: String, handle: WorkspaceHandle) -> Self {
        Self {
            display_name,
            handle,
        }
    }
}

/// 描述强类型 workspace command 无法准入的稳定原因，既不暴露 root 路径，也不允许调用方选择任意 handle。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WorkspaceLookup {
    Unknown,
}

impl RuntimeHost {
    /// composition root 注入冻结的原生 platform；application 不知道 LaunchConfig、进程或文件实现。
    pub(crate) fn compose(platform: Arc<dyn RuntimePlatformPort>) -> Self {
        Self::compose_with_workspace(platform, Arc::new(Mutex::new(WorkspaceBindings::default())))
    }

    /// 生产 Host 与 sink 侧 metadata router 必须共享同一 binding map，通知到达即撤销过期 root。
    fn compose_with_workspace(
        platform: Arc<dyn RuntimePlatformPort>,
        workspace: Arc<Mutex<WorkspaceBindings>>,
    ) -> Self {
        Self {
            platform,
            bridge: Arc::new(Mutex::new(None)),
            workspace,
        }
    }

    /// 接收 EventSink 共用 router，并在异步 worker 中用同一 bridge 回读 Java authority。
    pub(crate) fn compose_with_event_router(
        platform: Arc<dyn RuntimePlatformPort>,
        router: &WorkspaceMetadataEventRouter,
    ) -> Self {
        let bridge: Arc<Mutex<Option<Arc<dyn RuntimeBridgePort>>>> = Arc::new(Mutex::new(None));
        let workspace = Arc::clone(&router.workspace);
        let reconciler_workspace = Arc::clone(&workspace);
        let reconciler_bridge = Arc::clone(&bridge);
        router.install_reconciler(Arc::new(
            move |workspace_id, revision, server_id, generation| {
                let bridge = reconciler_bridge
                    .lock()
                    .ok()
                    .and_then(|bridge| bridge.clone());
                let Some(bridge) = bridge else {
                    return;
                };
                let Ok(expected_identity) = RuntimeIdentity::new(server_id, generation) else {
                    return;
                };
                let Ok(observed) = bridge.workspace_read(workspace_id.clone()) else {
                    return;
                };
                let _ = reconcile_workspace_metadata_projection(
                    &reconciler_workspace,
                    &expected_identity,
                    &workspace_id,
                    revision,
                    observed,
                );
            },
        ));
        Self {
            platform,
            bridge,
            workspace,
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
    fn workspace_guard(&self) -> Result<MutexGuard<'_, WorkspaceBindings>, RuntimeCommandError> {
        self.workspace
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())
    }

    /// 旧窗口退出标记不能阻止连接健康共享后台；mutex 只覆盖构造，不覆盖慢速握手。
    fn ensure_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        let mut bridge = self.bridge_guard()?;
        if let Some(current) = bridge.as_ref() {
            return Ok(current.clone());
        }
        let current = self.platform.create_bridge()?;
        *bridge = Some(current.clone());
        Ok(current)
    }

    /// 通过唯一原生 owner 启动 bridge，并按 serverInstanceId 与 generation 使旧 Workspace capability 失效。
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
        self.synchronize_workspace_identity(&status)?;
        if bridge
            .storage_recovery()?
            .is_some_and(|recovery| recovery.phase == "blocked")
        {
            return Ok(status);
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

    /// 显式重试沿用已鉴权进程，完整服务装配后重新执行工作区准入。
    pub fn retry_storage(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        let bridge = self.ensure_bridge()?;
        bridge.retry_storage()?;
        self.start()
    }

    /// 停止既有客户端连接；未建立连接时没有应影响共享后台的本地 owner。
    pub fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone();
        let result = match bridge {
            Some(bridge) => bridge.stop(),
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

    /// bridge 存在时返回其状态，并同步完整服务身份；非 Ready 状态清除旧 Workspace capability。
    pub fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone();
        match bridge {
            Some(bridge) => {
                let status = match bridge.state() {
                    Ok(status) => status,
                    Err(error) => {
                        self.clear_registered_workspaces()?;
                        return Err(error);
                    }
                };
                if matches!(
                    status.status,
                    RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
                ) {
                    self.synchronize_workspace_identity(&status)?;
                } else {
                    self.clear_registered_workspaces()?;
                }
                Ok(status)
            }
            None => Ok(RuntimeStatus {
                status: RuntimeStatusKind::Stopped,
                generation: 0,
                server_instance_id: None,
            }),
        }
    }

    /// 只在连接就绪时读取 Java 的存储事实；停止后的 bridge 仍存活，但没有可查询的 Session。
    /// 活动连接的健康读取失败继续向上传递，不能将真实存储阻塞误报为正常。
    pub fn storage_info(&self) -> Result<RuntimeStorageInfo, RuntimeCommandError> {
        let mut info = self.platform.storage_info();
        let bridge = self.bridge_guard()?.clone();
        info.storage_recovery = match bridge {
            Some(bridge) => match bridge.state()?.status {
                RuntimeStatusKind::Ready | RuntimeStatusKind::Busy => bridge.storage_recovery()?,
                _ => None,
            },
            None => None,
        };
        info.last_backup = info
            .storage_recovery
            .as_ref()
            .filter(|recovery| recovery.backup_available)
            .map(|_| "原数据库已保留".to_owned());
        Ok(info)
    }

    /// 请求身份必须匹配 Java 当前恢复事实，存在同名目录不能代替授权。
    pub fn open_storage_backup(&self, recovery_id: &str) -> Result<(), RuntimeCommandError> {
        let recovery = self
            .storage_info()?
            .storage_recovery
            .ok_or_else(RuntimeCommandError::unavailable)?;
        if recovery.id != recovery_id || !recovery.backup_available {
            return Err(RuntimeCommandError::invalid_params());
        }
        self.platform.open_storage_backup(recovery_id)
    }

    /// 仅通过已通过恢复与受信任配置检查的 bridge 路由强类型 Turn；修改归属完全由 Java tracker
    /// 持有，启动路径不得触发 Git、tree、watcher 或 snapshot。
    pub fn turn_start(&self, input: TurnStartInput) -> Result<TurnAccepted, RuntimeCommandError> {
        self.ensure_bridge()?.turn_start(input)
    }

    /// 通过当前 Java owner 准入隐藏 continuation；协议要求保留原问题，因此 Host 不生成可见 follow-up 消息。
    pub fn turn_continue(
        &self,
        input: TurnContinueInput,
    ) -> Result<TurnAccepted, RuntimeCommandError> {
        self.ensure_bridge()?.turn_continue(input)
    }

    /// 通过 Java 源消息 CAS 原子切换当前路径并准入；native Host 不重建历史，也不在本地回滚持久记录。
    pub fn turn_reask(&self, input: TurnReaskInput) -> Result<TurnAccepted, RuntimeCommandError> {
        self.ensure_bridge()?.turn_reask(input)
    }

    /// 通过当前 bridge 路由取消而不替换或停止 sidecar；完成事实仍只能从事件通道到达。
    pub fn turn_cancel(
        &self,
        input: TurnCancelInput,
    ) -> Result<TurnCancelResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_cancel(input)
    }

    /// 通过当前受 instance/generation fence 保护的 bridge 恢复 Turn；Rust 不读取或缓存执行游标。
    pub fn turn_resume(&self, input: TurnResumeInput) -> Result<TurnAccepted, RuntimeCommandError> {
        self.ensure_bridge()?.turn_resume(input)
    }

    /// 恢复裁决经当前 generation 的同一 bridge 转发，Host 不保存 Tool 或文件状态副本。
    pub fn turn_recovery_respond(
        &self,
        input: ToolRecoveryResponseInput,
    ) -> Result<ToolRecoveryResponse, RuntimeCommandError> {
        self.ensure_bridge()?.turn_recovery_respond(input)
    }

    /// 通过当前 generation 的固定 lane 入队普通消息，RuntimeHost 不缓存队列副本。
    pub fn turn_input_enqueue(
        &self,
        input: TurnInputEnqueue,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_input_enqueue(input)
    }

    /// 提升条目只转发 item CAS，点击顺序和安全消费点继续由 Java 独占。
    pub fn turn_input_prioritize(
        &self,
        input: TurnInputPrioritize,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_input_prioritize(input)
    }

    /// 编辑通过条目 revision 防止覆盖消费竞态，Host 不把该 CAS 扩张为 Turn execution mutation。
    pub fn turn_input_update(
        &self,
        input: TurnInputUpdate,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_input_update(input)
    }

    /// 删除仅作用于 Java durable queue，Host 不依据本地事件投影预判条目存在性。
    pub fn turn_input_delete(
        &self,
        input: TurnInputDelete,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        self.ensure_bridge()?.turn_input_delete(input)
    }

    /// 侧边任务创建必须经过当前 Java transaction，Host 不缓存草稿或 lineage。
    pub fn task_create(
        &self,
        input: TaskCreateInput,
    ) -> Result<TaskCreateResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_create(input)
    }

    /// 总览直接读取 Java projection，隐藏能力不会在 Host 扫描 Child transcript。
    pub fn task_list(&self, input: TaskListInput) -> Result<TaskListResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_list(input)
    }

    /// 任务详情读取复用 Java cursor，不在 Host 保存 activity/mailbox 副本。
    pub fn task_read(&self, input: TaskReadInput) -> Result<TaskReadResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_read(input)
    }

    /// 高频观察句柄由当前 sidecar connection 持有，Host 只通过 ready fence 准入。
    pub fn task_observe(
        &self,
        input: TaskObserveInput,
    ) -> Result<TaskObserveResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_observe(input)
    }

    /// 显式释放观察句柄；connection 关闭时的兜底回收仍属于 Java session owner。
    pub fn task_unobserve(&self, input: TaskUnobserveInput) -> Result<(), RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_unobserve(input)
    }

    /// Hard reload 直接使用既有 bridge 清理 renderer owner，不为清理动作惰性启动新 sidecar。
    pub fn release_task_observations(
        &self,
        owner: &'static str,
    ) -> Result<usize, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone();
        match bridge {
            Some(bridge) => bridge.release_task_observations(owner),
            None => Ok(0),
        }
    }

    /// 已读边界由 Java CAS 推进，不用最后收到的 event sequence 替代服务端 revision。
    pub fn task_seen(&self, input: TaskSeenInput) -> Result<TaskSummary, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_seen(input)
    }

    /// QueueOnly 消息走固定 Mailbox 方法，Host 不唤醒空闲任务。
    pub fn task_message_send(
        &self,
        input: TaskMessageInput,
    ) -> Result<TaskMessageResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_message_send(input)
    }

    /// followup 的 Turn 创建和排队保持在 Java 原子用例内，Host 只传递 typed intent。
    pub fn task_followup(
        &self,
        input: TaskFollowupInput,
    ) -> Result<TaskFollowupResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_followup(input)
    }

    /// 取消传播完全依据 Java lineage，Rust/Tauri 不遍历或推断 ATTACHED 后代。
    pub fn task_cancel(
        &self,
        input: TaskMutationInput,
    ) -> Result<TaskSummary, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_cancel(input)
    }

    /// 显式整树删除通过唯一 typed 入口代理，普通 Thread 删除不会走到这里。
    pub fn task_tree_delete(
        &self,
        input: TaskTreeDeleteInput,
    ) -> Result<TaskTreeDeleteResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_tree_delete(input)
    }

    /// 侧聊关闭只在 Java 确认包含请求 root 的完整 purge 集合后向 UI 返回成功。
    pub fn task_close(
        &self,
        input: TaskCloseInput,
    ) -> Result<TaskCloseResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.ready_bridge()?.task_close(input)
    }

    /// `@` 查询先验证 active Workspace binding，再由 Java 对 Thread 归属与路径 confinement 复核。
    pub fn workspace_path_search(
        &self,
        input: WorkspacePathSearchInput,
    ) -> Result<WorkspacePathSearchResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.authorize_workspace_identity(&input.workspace_id)?;
        self.ready_bridge()?.workspace_path_search(input)
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
            .map_err(|_| RuntimeCommandError::invalid_params())
    }

    /// 仅通过已配置且 Ready/Busy 的 generation 路由白名单 history request，防止查询启动或重配置 sidecar。
    pub(crate) fn history_request(
        &self,
        request: HistoryRequest,
    ) -> Result<HistoryResponse, RuntimeCommandError> {
        // History 是查询面而非生命周期触发器；读取前同步完整服务身份，阻止新实例沿用旧 Workspace binding。
        let bridge = self.bridge_guard()?.clone().ok_or(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready".into(),
            retryable: true,
        })?;
        let status = match bridge.state() {
            Ok(status) => status,
            Err(error) => {
                self.clear_registered_workspaces()?;
                return Err(error);
            }
        };
        if !matches!(
            status.status,
            RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
        ) {
            self.clear_registered_workspaces()?;
            return Err(RuntimeCommandError {
                code: "RUNTIME_NOT_READY",
                message: "runtime is not ready".into(),
                retryable: true,
            });
        }
        self.synchronize_workspace_identity(&status)?;
        bridge.history(request)
    }

    /// Plan/Interaction 只允许在已 Ready 的 Java generation 上执行，Host 不缓存 projection 或批准状态。
    pub(crate) fn workflow_request(
        &self,
        request: WorkflowRequest,
    ) -> Result<WorkflowResponse, RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone().ok_or(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready".into(),
            retryable: true,
        })?;
        bridge.workflow(request)
    }

    /// 已登记 Workspace 的读取只允许 Ready/Busy 服务身份；当前 Thread 切换不隐式启动 Java，也不回退到 Rust-owned 存储。
    fn ready_bridge_with_identity(
        &self,
    ) -> Result<(Arc<dyn RuntimeBridgePort>, RuntimeIdentity), RuntimeCommandError> {
        let bridge = self.bridge_guard()?.clone().ok_or(RuntimeCommandError {
            code: "RUNTIME_NOT_READY",
            message: "runtime is not ready".into(),
            retryable: true,
        })?;
        let status = match bridge.state() {
            Ok(status) => status,
            Err(error) => {
                self.clear_registered_workspaces()?;
                return Err(error);
            }
        };
        if !matches!(
            status.status,
            RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
        ) {
            self.clear_registered_workspaces()?;
            return Err(RuntimeCommandError {
                code: "RUNTIME_NOT_READY",
                message: "runtime is not ready".into(),
                retryable: true,
            });
        }
        let identity = self.synchronize_workspace_identity(&status)?;
        Ok((bridge, identity))
    }

    /// 普通 Runtime 命令复用 ready 检查，并在访问前按服务实例与 generation 同步原生 capability。
    fn ready_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        self.ready_bridge_with_identity().map(|(bridge, _)| bridge)
    }

    /// 配置与凭据请求在已启动的 Ready/Busy 服务身份上直接复用 bridge，避免每次读取都
    /// 重放 workspace/open 与 health；只有未启动或故障状态才进入完整 start 恢复链。
    fn configuration_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError> {
        let bridge = match self.ensure_bridge() {
            Ok(bridge) => bridge,
            Err(error) => {
                self.clear_workspace()?;
                return Err(error);
            }
        };
        match bridge.state() {
            Ok(status)
                if matches!(
                    status.status,
                    RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
                ) =>
            {
                self.synchronize_workspace_identity(&status)?;
                return Ok(bridge);
            }
            Ok(_) => self.clear_registered_workspaces()?,
            Err(_) => self.clear_registered_workspaces()?,
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
            message: "runtime is not ready".into(),
            retryable: true,
        })?;
        let status = bridge.state()?;
        if status.status != RuntimeStatusKind::Ready {
            return Err(RuntimeCommandError {
                code: "RUNTIME_NOT_READY",
                message: "runtime is not ready".into(),
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

    /// 校验原生 cwd 并保存 capability handle；Java 响应只可在发起请求的服务身份仍当前时提交。
    pub fn open_workspace(&self, input: WorkspaceOpenInput) -> Result<(), RuntimeCommandError> {
        let source = self
            .platform
            .workspace_source(&input.cwd, input.display_name.as_deref())?;
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
        let mut projection = None;
        if let Some(bridge) = existing {
            let status = match bridge.state() {
                Ok(status) => status,
                Err(error) => {
                    self.clear_registered_workspaces()?;
                    return Err(error);
                }
            };
            if matches!(
                status.status,
                RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
            ) {
                let expected_identity = self.synchronize_workspace_identity(&status)?;
                let opened_projection = bridge.workspace_open(
                    workspace_handle.root_path().to_path_buf(),
                    display_name.clone(),
                )?;
                bridge.health()?;
                self.ensure_workspace_identity(&expected_identity)?;
                // 用 Workspace registry 的物理 identity 比较 Java 根，避免 Windows 路径前缀、大小写与短名差异拒绝合法绑定。
                let opened_info = registry
                    .register(Path::new(&opened_projection.root))
                    .map_err(|_| RuntimeCommandError::configuration())?;
                let opened_handle = registry
                    .get(opened_info.id)
                    .map_err(|_| RuntimeCommandError::configuration())?;
                if !workspace_handle.same_physical_root(&opened_handle) {
                    return Err(RuntimeCommandError::configuration());
                }
                display_name = opened_projection.display_name.clone();
                // 保留完整的 Java 投影，直到按 ID 插入规范句柄。
                projection = Some((opened_projection, expected_identity));
            } else {
                self.clear_registered_workspaces()?;
            }
        }
        let mut bindings = self.workspace_guard()?;
        if let Some((projection, expected_identity)) = projection {
            if bindings.runtime_identity.as_ref() != Some(&expected_identity) {
                return Err(RuntimeCommandError::unavailable());
            }
            let binding = ConfiguredWorkspace::from_projection(
                projection,
                workspace_handle,
                expected_identity,
            );
            let binding = bindings.insert_binding(binding)?;
            bindings.active_workspace_id = Some(binding.workspace_id.clone());
            bindings.pending_project = None;
        } else {
            // 将启动前配置与 Java 签发身份分开；只有 `workspace/open` 成功后才允许激活。
            bindings.active_workspace_id = None;
            bindings.pending_project =
                Some(PendingProjectWorkspace::new(display_name, workspace_handle));
        }
        Ok(())
    }

    /// 打开项目后重读当前服务身份再返回 Java DTO；history caller 不能把迟到投影当作跨实例授权。
    pub fn open_workspace_projection(
        &self,
        input: WorkspaceOpenInput,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.open_workspace(input)?;
        let (_, current_identity) = self.ready_bridge_with_identity()?;
        let workspace = self.workspace_guard()?;
        let workspace_id = workspace
            .active_workspace_id
            .as_deref()
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let binding = workspace
            .by_id
            .get(workspace_id)
            .ok_or_else(RuntimeCommandError::unavailable)?;
        if binding.runtime_identity != current_identity
            || workspace.runtime_identity.as_ref() != Some(&current_identity)
        {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(WorkspaceDto {
            workspace_id: binding.workspace_id.clone(),
            root: binding
                .projection_root
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable)?,
            display_name: binding.display_name.clone(),
            trust: binding.trust.clone(),
            revision: binding
                .revision
                .ok_or_else(RuntimeCommandError::unavailable)?,
            kind: binding.kind,
        })
    }

    /// 把“启动当前 generation”与“注册 Workspace”组合为一个 application 用例；interface 只提交
    /// intent，不能在 command 中复制生命周期顺序。启动或 workspace/open 任一步失败都沿用现有
    /// Host 清理与错误语义，不额外引入补偿状态。
    pub fn start_and_open_workspace(
        &self,
        input: WorkspaceOpenInput,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        // 先完成握手、已配置 Workspace 回放与 health，再登记新的 Workspace。
        self.start()?;
        // open_workspace_projection 负责原生路径准入、Java identity 分配和本地 binding 提交。
        self.open_workspace_projection(input)
    }

    /// 仅授信已登记项目；失败只撤销发起请求的服务身份下的 binding，迟到错误不得伤及新实例。
    pub fn set_workspace_trust(
        &self,
        workspace_id: String,
        trust: String,
    ) -> Result<(), RuntimeCommandError> {
        if !valid_protocol_id(&workspace_id, "ws_", 99)
            || !matches!(trust.as_str(), "trusted" | "untrusted")
        {
            return Err(RuntimeCommandError::invalid_params());
        }
        let (bridge, expected_identity) = self.ready_bridge_with_identity()?;
        {
            let mut workspaces = self.workspace_guard()?;
            if workspaces.runtime_identity.as_ref() != Some(&expected_identity)
                || workspaces.pending_metadata_update.contains(&workspace_id)
            {
                return Err(RuntimeCommandError::workspace_busy());
            }
            workspaces
                .pending_metadata_update
                .insert(workspace_id.clone());
        }
        let projection = match bridge.workspace_set_trust(workspace_id.clone(), trust.clone()) {
            Ok(projection) => projection,
            Err(error) => {
                self.invalidate_workspace_binding(&workspace_id, &expected_identity)?;
                self.finish_workspace_update(&workspace_id)?;
                return Err(error);
            }
        };
        if let Err(error) = self.ensure_workspace_identity(&expected_identity) {
            self.finish_workspace_update(&workspace_id)?;
            return Err(error);
        }
        if projection.workspace_id != workspace_id
            || projection.kind != WorkspaceKind::Project
            || projection.trust != trust
        {
            self.invalidate_workspace_binding(&workspace_id, &expected_identity)?;
            self.finish_workspace_update(&workspace_id)?;
            return Err(RuntimeCommandError::unavailable());
        }
        if let Err(error) =
            self.register_workspace_projection(projection, expected_identity.clone())
        {
            self.invalidate_workspace_binding(&workspace_id, &expected_identity)?;
            self.finish_workspace_update(&workspace_id)?;
            return Err(error);
        }
        self.finish_workspace_update(&workspace_id)?;
        Ok(())
    }

    /// 只读数据库投影，不尝试 canonicalize 或绑定 root，因此磁盘目录丢失时历史仍可按原 ID 读取。
    pub fn read_workspace(
        &self,
        workspace_id: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        if !valid_protocol_id(&workspace_id, "ws_", 99) {
            return Err(RuntimeCommandError::invalid_params());
        }
        let (bridge, expected_identity) = self.ready_bridge_with_identity()?;
        let projection = bridge.workspace_read(workspace_id.clone())?;
        self.ensure_workspace_identity(&expected_identity)?;
        if projection.workspace_id != workspace_id {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(projection)
    }

    /// 按 revision 原子更新项目资料；换 root 前拒绝新 IO 并排空旧 handle，CAS 失败时回读确认后才解冻。
    pub fn update_workspace(
        &self,
        input: WorkspaceUpdateInput,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let (bridge, expected_identity) = self.ready_bridge_with_identity()?;
        let current = bridge.workspace_read(input.workspace_id.clone())?;
        self.ensure_workspace_identity(&expected_identity)?;
        if current.workspace_id != input.workspace_id || current.kind != WorkspaceKind::Project {
            return Err(RuntimeCommandError::invalid_params());
        }
        if current.revision != input.expected_revision {
            return Err(RuntimeCommandError {
                code: "CONFLICT",
                message: "workspace revision changed".into(),
                retryable: true,
            });
        }

        let candidate = input
            .cwd
            .as_deref()
            .map(workspace_handle_for_root)
            .transpose()?;
        let existing = self
            .workspace_guard()?
            .by_id
            .get(&input.workspace_id)
            .cloned();
        let root_changed = candidate.as_ref().is_some_and(|new_handle| {
            if let Some(existing) = existing.as_ref() {
                !existing.handle.same_physical_root(new_handle)
            } else {
                !workspace_root_matches_handle(&current.root, new_handle)
            }
        });

        {
            let mut bindings = self.workspace_guard()?;
            if bindings.runtime_identity.as_ref() != Some(&expected_identity)
                || bindings
                    .pending_metadata_update
                    .contains(&input.workspace_id)
                || bindings.pending_rebind.contains(&input.workspace_id)
            {
                return Err(RuntimeCommandError::workspace_busy());
            }
            bindings
                .pending_metadata_update
                .insert(input.workspace_id.clone());
            if root_changed {
                bindings.pending_rebind.insert(input.workspace_id.clone());
            }
        }

        if root_changed {
            if let Some(existing) = existing.as_ref()
                && !existing
                    .handle
                    .freeze_and_drain(WORKSPACE_REBIND_DRAIN_TIMEOUT)
            {
                existing.handle.thaw_after_aborted_rebind();
                self.finish_workspace_update(&input.workspace_id)?;
                return Err(RuntimeCommandError::workspace_busy());
            }
        }

        let response = bridge.workspace_update(input.clone());
        let projection = match response {
            Ok(projection) => projection,
            Err(error) => {
                // Request timeout/disconnect does not prove rollback; read the durable row before retrying or unfreezing.
                let observed = bridge.workspace_read(input.workspace_id.clone());
                match observed {
                    Ok(observed)
                        if workspace_update_matches(
                            &input,
                            &current,
                            &observed,
                            candidate.as_ref(),
                            root_changed,
                        ) =>
                    {
                        observed
                    }
                    Ok(observed) if observed.revision == current.revision => {
                        if root_changed && let Some(existing) = existing.as_ref() {
                            existing.handle.thaw_after_aborted_rebind();
                        }
                        self.finish_workspace_update(&input.workspace_id)?;
                        return Err(error);
                    }
                    Ok(observed) => {
                        self.reconcile_unexpected_workspace_update(
                            &input.workspace_id,
                            &expected_identity,
                            &observed,
                            &existing,
                        )?;
                        self.finish_workspace_update(&input.workspace_id)?;
                        return Err(error);
                    }
                    Err(_) => {
                        // Without authority readback, fence new local IO until explicit recovery; the frontend defers dirty-resource cleanup to that flow.
                        self.mark_workspace_update_unresolved(
                            &input.workspace_id,
                            &expected_identity,
                        )?;
                        self.finish_workspace_update(&input.workspace_id)?;
                        return Err(RuntimeCommandError::unavailable());
                    }
                }
            }
        };
        if let Err(error) = self.ensure_workspace_identity(&expected_identity) {
            if root_changed && let Some(existing) = existing.as_ref() {
                existing.handle.revoke();
            }
            self.finish_workspace_update(&input.workspace_id)?;
            return Err(error);
        }
        if projection.workspace_id != input.workspace_id
            || projection.kind != WorkspaceKind::Project
            || !workspace_update_matches(
                &input,
                &current,
                &projection,
                candidate.as_ref(),
                root_changed,
            )
        {
            self.revoke_workspace_binding(&input.workspace_id, &expected_identity)?;
            self.finish_workspace_update(&input.workspace_id)?;
            return Err(RuntimeCommandError::unavailable());
        }

        if let Err(error) = self.commit_workspace_update(
            projection.clone(),
            expected_identity.clone(),
            candidate,
            root_changed,
            &input.workspace_id,
        ) {
            self.revoke_workspace_binding(&input.workspace_id, &expected_identity)?;
            self.finish_workspace_update(&input.workspace_id)?;
            return Err(error);
        }
        Ok(projection)
    }

    /// 结束 metadata fence；调用方须先明确旧 handle 可恢复或已撤销，避免重开不确定 root。
    fn finish_workspace_update(&self, workspace_id: &str) -> Result<(), RuntimeCommandError> {
        let mut bindings = self.workspace_guard()?;
        bindings.pending_metadata_update.remove(workspace_id);
        bindings.pending_rebind.remove(workspace_id);
        bindings.pending_metadata_revision.remove(workspace_id);
        Ok(())
    }

    /// CAS 已确认后原子替换同 ID projection；仅显式 cwd 才重新做物理 root 比较，纯改名不要求目录可用。
    fn commit_workspace_update(
        &self,
        projection: WorkspaceDto,
        expected_identity: RuntimeIdentity,
        candidate: Option<WorkspaceHandle>,
        root_changed: bool,
        workspace_id: &str,
    ) -> Result<(), RuntimeCommandError> {
        let mut bindings = self.workspace_guard()?;
        if bindings.runtime_identity.as_ref() != Some(&expected_identity) {
            return Err(RuntimeCommandError::unavailable());
        }
        let existing = bindings.by_id.get(workspace_id).cloned();
        let handle = if root_changed {
            candidate.ok_or_else(RuntimeCommandError::configuration)?
        } else if let Some(existing) = existing {
            if candidate
                .as_ref()
                .is_some_and(|candidate| !existing.handle.same_physical_root(candidate))
            {
                return Err(RuntimeCommandError::unavailable());
            }
            existing.handle
        } else if let Some(candidate) = candidate {
            candidate
        } else {
            bindings.pending_metadata_update.remove(workspace_id);
            bindings.pending_rebind.remove(workspace_id);
            bindings.pending_metadata_revision.remove(workspace_id);
            return Ok(());
        };
        let binding = ConfiguredWorkspace::from_projection(projection, handle, expected_identity);
        if root_changed {
            bindings.replace_binding(binding)?;
        } else {
            bindings.insert_binding(binding)?;
        }
        bindings.unresolved_workspace_updates.remove(workspace_id);
        bindings.pending_metadata_update.remove(workspace_id);
        bindings.pending_rebind.remove(workspace_id);
        bindings.pending_metadata_revision.remove(workspace_id);
        Ok(())
    }

    /// 双读失败后的 root 身份不可推断；原子撤销旧 capability 并记录 fence，直到显式 ID-open 或新 root update 成功。
    fn mark_workspace_update_unresolved(
        &self,
        workspace_id: &str,
        expected_identity: &RuntimeIdentity,
    ) -> Result<(), RuntimeCommandError> {
        let mut bindings = self.workspace_guard()?;
        if bindings.runtime_identity.as_ref() == Some(expected_identity) {
            if let Some(binding) = bindings.by_id.remove(workspace_id) {
                binding.handle.revoke();
            }
            if bindings.active_workspace_id.as_deref() == Some(workspace_id) {
                bindings.active_workspace_id = None;
            }
            bindings.pending_metadata_revision.remove(workspace_id);
            bindings
                .unresolved_workspace_updates
                .insert(workspace_id.to_owned());
        }
        Ok(())
    }

    /// 不确定提交读回了不同 root 时立即撤销旧 handle；调用方必须重新加载权威资料后再显式绑定。
    fn reconcile_unexpected_workspace_update(
        &self,
        workspace_id: &str,
        expected_identity: &RuntimeIdentity,
        observed: &WorkspaceDto,
        previous: &Option<ConfiguredWorkspace>,
    ) -> Result<(), RuntimeCommandError> {
        let root_same = previous
            .as_ref()
            .is_some_and(|binding| workspace_root_matches_handle(&observed.root, &binding.handle));
        if !root_same {
            self.revoke_workspace_binding(workspace_id, expected_identity)?;
        } else if let Some(previous) = previous.as_ref() {
            previous.handle.thaw_after_aborted_rebind();
            let binding = ConfiguredWorkspace::from_projection(
                observed.clone(),
                previous.handle.clone(),
                expected_identity.clone(),
            );
            let mut bindings = self.workspace_guard()?;
            if bindings.runtime_identity.as_ref() == Some(expected_identity) {
                bindings.insert_binding(binding)?;
            }
        }
        Ok(())
    }

    /// 只撤销发起请求的 service identity，避免迟到错误删除新 generation 的 capability。
    fn revoke_workspace_binding(
        &self,
        workspace_id: &str,
        expected_identity: &RuntimeIdentity,
    ) -> Result<(), RuntimeCommandError> {
        let mut bindings = self.workspace_guard()?;
        if bindings.runtime_identity.as_ref() == Some(expected_identity) {
            if let Some(binding) = bindings.by_id.remove(workspace_id) {
                binding.handle.revoke();
            }
            bindings.pending_metadata_revision.remove(workspace_id);
            if bindings.active_workspace_id.as_deref() == Some(workspace_id) {
                bindings.active_workspace_id = None;
            }
        }
        Ok(())
    }

    /// 只激活当前服务身份的 Workspace；Java 重开回包经过双身份复核后才可登记原生 handle。
    pub fn activate_workspace(
        &self,
        workspace_id: String,
    ) -> Result<WorkspaceActivation, RuntimeCommandError> {
        self.activate_workspace_with_projection(workspace_id)
            .map(|(_, activation)| activation)
    }

    /// ID-only 打开沿用 activation 的全部身份、metadata 与物理目录校验，并返回同一份 Java 投影；
    /// 不再额外 read，避免 activation 与返回给历史层的 root/revision 来自两个不同瞬间。
    fn activate_workspace_with_projection(
        &self,
        workspace_id: String,
    ) -> Result<(WorkspaceDto, WorkspaceActivation), RuntimeCommandError> {
        if !valid_protocol_id(&workspace_id, "ws_", 99) {
            return Err(RuntimeCommandError::invalid_params());
        }
        let (bridge, expected_identity) = self.ready_bridge_with_identity()?;
        let projection = bridge.workspace_open_by_id(workspace_id.clone())?;
        self.ensure_workspace_identity(&expected_identity)?;
        if projection.workspace_id != workspace_id {
            return Err(RuntimeCommandError::unavailable());
        }
        let pending_metadata_revision = self
            .workspace
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())?
            .pending_metadata_revision
            .get(&workspace_id)
            .copied();
        if let Some(revision) = pending_metadata_revision {
            let _ = reconcile_workspace_metadata_projection(
                &self.workspace,
                &expected_identity,
                &workspace_id,
                revision,
                projection.clone(),
            );
            if self
                .workspace
                .lock()
                .map_err(|_| RuntimeCommandError::unavailable())?
                .pending_metadata_revision
                .contains_key(&workspace_id)
            {
                return Err(RuntimeCommandError::workspace_busy());
            }
        }
        let returned_projection = projection.clone();
        let binding = self.register_workspace_projection(projection, expected_identity.clone())?;
        let activation = self.activate_cached_workspace(binding, expected_identity.clone())?;
        let mut workspaces = self.workspace_guard()?;
        if workspaces.runtime_identity.as_ref() != Some(&expected_identity) {
            return Err(RuntimeCommandError::unavailable());
        }
        workspaces
            .unresolved_workspace_updates
            .remove(&workspace_id);
        Ok((returned_projection, activation))
    }

    /// 历史层按 Java 持久 ID 重开时同步建立真实原生绑定，并返回与该绑定同源的完整投影。
    pub fn open_workspace_projection_by_id(
        &self,
        workspace_id: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.activate_workspace_with_projection(workspace_id)
            .map(|(projection, _)| projection)
    }

    /// 让历史入口与 cwd 打开保持相同的 Ready 语义，同时按持久 ID 激活既有 Project 或 Session。
    pub fn start_and_open_workspace_by_id(
        &self,
        workspace_id: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError> {
        self.start()?;
        self.open_workspace_projection_by_id(workspace_id)
    }

    /// Java 回包返回后重新核对服务身份，再把根目录纳入现有物理身份注册表。
    fn register_workspace_projection(
        &self,
        projection: WorkspaceDto,
        expected_identity: RuntimeIdentity,
    ) -> Result<ConfiguredWorkspace, RuntimeCommandError> {
        self.ensure_workspace_identity(&expected_identity)?;
        let registry = WorkspaceRegistry::default();
        let info = registry
            .register(Path::new(&projection.root))
            .map_err(|_| RuntimeCommandError::configuration())?;
        let handle = registry
            .get(info.id)
            .map_err(|_| RuntimeCommandError::configuration())?;
        let binding = ConfiguredWorkspace::from_projection(projection, handle, expected_identity);
        let mut workspaces = self.workspace_guard()?;
        workspaces.insert_binding(binding)
    }

    /// 只发布当前服务身份已登记 handle 的规范路径；renderer 从不提供或选择该根目录。
    fn activate_cached_workspace(
        &self,
        binding: ConfiguredWorkspace,
        expected_identity: RuntimeIdentity,
    ) -> Result<WorkspaceActivation, RuntimeCommandError> {
        self.ensure_workspace_identity(&expected_identity)?;
        if binding.runtime_identity != expected_identity {
            return Err(RuntimeCommandError::unavailable());
        }
        let root_path = binding
            .handle
            .root_path()
            .to_str()
            .filter(|value| !value.is_empty())
            .ok_or_else(RuntimeCommandError::unavailable)?
            .to_owned();
        let activation = WorkspaceActivation {
            workspace_id: binding.workspace_id.clone(),
            root_path,
            display_name: binding.display_name.clone(),
            trust: binding.trust.clone(),
            kind: binding.kind,
        };
        let mut workspaces = self.workspace_guard()?;
        let binding = workspaces.insert_binding(binding)?;
        workspaces.active_workspace_id = Some(binding.workspace_id);
        Ok(activation)
    }

    /// 先确认服务双身份并克隆 handle，再释放 binding lock 执行 IO；gate 负责与重绑排空竞态。
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
        let pending_revision = self
            .workspace
            .lock()
            .map_err(|_| WorkspaceLookup::Unknown)?
            .pending_metadata_revision
            .get(workspace_id)
            .copied();
        if let Some(announced_revision) = pending_revision {
            let (bridge, expected_identity) = self
                .ready_bridge_with_identity()
                .map_err(|_| WorkspaceLookup::Unknown)?;
            let observed = bridge
                .workspace_read(workspace_id.to_owned())
                .map_err(|_| WorkspaceLookup::Unknown)?;
            self.ensure_workspace_identity(&expected_identity)
                .map_err(|_| WorkspaceLookup::Unknown)?;
            if reconcile_workspace_metadata_projection(
                &self.workspace,
                &expected_identity,
                workspace_id,
                announced_revision,
                observed,
            ) {
                return Err(WorkspaceLookup::Unknown);
            }
            let refreshed = self
                .workspace
                .lock()
                .map_err(|_| WorkspaceLookup::Unknown)?
                .pending_metadata_revision
                .contains_key(workspace_id);
            if refreshed {
                return Err(WorkspaceLookup::Unknown);
            }
        }
        let configured_handle = {
            let workspace = self
                .workspace
                .lock()
                .map_err(|_| WorkspaceLookup::Unknown)?;
            if workspace
                .unresolved_workspace_updates
                .contains(workspace_id)
            {
                return Err(WorkspaceLookup::Unknown);
            }
            if workspace.pending_rebind.contains(workspace_id) {
                return Err(WorkspaceLookup::Unknown);
            }
            if let Some(binding) = workspace.by_id.get(workspace_id) {
                if workspace.runtime_identity.as_ref() != Some(&binding.runtime_identity) {
                    return Err(WorkspaceLookup::Unknown);
                }
                Some(binding.handle.clone())
            } else {
                None
            }
        };
        if let Some(handle) = configured_handle {
            return Ok(operation(&handle));
        }

        // 旧会话的文件夹操作和恢复的会话可能在视图激活前到达；唯一允许的根目录来源是按 ID 查询 Java。
        let (bridge, expected_identity) = self
            .ready_bridge_with_identity()
            .map_err(|_| WorkspaceLookup::Unknown)?;
        if self
            .workspace
            .lock()
            .map_err(|_| WorkspaceLookup::Unknown)?
            .unresolved_workspace_updates
            .contains(workspace_id)
        {
            return Err(WorkspaceLookup::Unknown);
        }
        let projection = bridge
            .workspace_open_by_id(workspace_id.to_owned())
            .map_err(|_| WorkspaceLookup::Unknown)?;
        self.ensure_workspace_identity(&expected_identity)
            .map_err(|_| WorkspaceLookup::Unknown)?;
        if projection.workspace_id != workspace_id {
            return Err(WorkspaceLookup::Unknown);
        }
        {
            let workspace = self
                .workspace
                .lock()
                .map_err(|_| WorkspaceLookup::Unknown)?;
            if workspace
                .unresolved_workspace_updates
                .contains(workspace_id)
                || workspace.pending_rebind.contains(workspace_id)
            {
                return Err(WorkspaceLookup::Unknown);
            }
        }
        self.register_workspace_projection(projection, expected_identity.clone())
            .map_err(|_| WorkspaceLookup::Unknown)?;
        let workspace = self
            .workspace
            .lock()
            .map_err(|_| WorkspaceLookup::Unknown)?;
        let binding = workspace
            .by_id
            .get(workspace_id)
            .ok_or(WorkspaceLookup::Unknown)?;
        if workspace.runtime_identity.as_ref() != Some(&expected_identity)
            || binding.runtime_identity != expected_identity
        {
            return Err(WorkspaceLookup::Unknown);
        }
        let handle = binding.handle.clone();
        drop(workspace);
        Ok(operation(&handle))
    }

    /// 返回当前生命周期是否仍 Ready，并先同步实例与 generation；调用方不得已经持有 Workspace 锁。
    fn workspace_access_ready(&self) -> bool {
        let bridge = match self.bridge.lock() {
            Ok(bridge) => bridge.clone(),
            Err(_) => return false,
        };
        match bridge {
            // configure 成功只会冻结 binding 而不会启动 generation；在权威 Ready/Busy 投影出现前，命令始终保持关闭。
            None => false,
            Some(bridge) => match bridge.state() {
                Ok(status)
                    if matches!(
                        status.status,
                        RuntimeStatusKind::Ready | RuntimeStatusKind::Busy
                    ) =>
                {
                    self.synchronize_workspace_identity(&status).is_ok()
                }
                Ok(_) => {
                    let _ = self.clear_registered_workspaces();
                    false
                }
                Err(_) => {
                    let _ = self.clear_registered_workspaces();
                    false
                }
            },
        }
    }

    /// 当配置本身不再权威（例如应用 shutdown）时删除完整原生 binding，避免旧 capability 延续到下一生命周期。
    fn clear_workspace(&self) -> Result<(), RuntimeCommandError> {
        let mut workspace = self.workspace_guard()?;
        for binding in workspace.by_id.values() {
            binding.handle.revoke();
        }
        *workspace = WorkspaceBindings::default();
        Ok(())
    }

    /// 任一 Java identity 字段变化都撤销旧 capability；显式 pending project 不属于旧实例，继续保留。
    fn synchronize_workspace_identity(
        &self,
        status: &RuntimeStatus,
    ) -> Result<RuntimeIdentity, RuntimeCommandError> {
        let identity = match RuntimeIdentity::from_status(status) {
            Ok(identity) => identity,
            Err(error) => {
                self.clear_registered_workspaces()?;
                return Err(error);
            }
        };
        self.workspace_guard()?
            .synchronize_identity(identity.clone());
        Ok(identity)
    }

    /// 阻止已完成的旧实例 RPC 回包在当前 identity 改变后重新绑定原生 handle。
    fn ensure_workspace_identity(
        &self,
        expected: &RuntimeIdentity,
    ) -> Result<(), RuntimeCommandError> {
        let (_, current) = self.ready_bridge_with_identity()?;
        if &current != expected {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(())
    }

    /// 非 Ready 或未知实例下的旧 handle 不再授权；待处理项目是用户选择，仍可在后续启动重放。
    fn clear_registered_workspaces(&self) -> Result<(), RuntimeCommandError> {
        let mut workspaces = self.workspace_guard()?;
        for binding in workspaces.by_id.values() {
            binding.handle.revoke();
        }
        workspaces.by_id.clear();
        workspaces.active_workspace_id = None;
        workspaces.runtime_identity = None;
        Ok(())
    }

    /// 只撤销发起该 RPC 的实例 binding；旧错误响应不得删除新服务已重新确认的同 ID 句柄。
    fn invalidate_workspace_binding(
        &self,
        workspace_id: &str,
        expected_identity: &RuntimeIdentity,
    ) -> Result<(), RuntimeCommandError> {
        let mut workspaces = self.workspace_guard()?;
        if workspaces.runtime_identity.as_ref() == Some(expected_identity)
            && workspaces
                .by_id
                .get(workspace_id)
                .is_some_and(|binding| binding.runtime_identity == *expected_identity)
        {
            if let Some(binding) = workspaces.by_id.remove(workspace_id) {
                binding.handle.revoke();
            }
            workspaces.pending_metadata_revision.remove(workspace_id);
            if workspaces.active_workspace_id.as_deref() == Some(workspace_id) {
                workspaces.active_workspace_id = None;
            }
        }
        Ok(())
    }

    /// 仅草稿附件沿用已选 Workspace；先刷新服务双身份，不能把旧实例 active ID 作为新权限。
    fn active_workspace_id(&self) -> Result<String, RuntimeCommandError> {
        if !self.workspace_access_ready() {
            return Err(RuntimeCommandError::unavailable());
        }
        let workspaces = self.workspace_guard()?;
        let workspace_id = workspaces
            .active_workspace_id
            .as_ref()
            .ok_or_else(RuntimeCommandError::unavailable)?;
        let binding = workspaces
            .by_id
            .get(workspace_id)
            .ok_or_else(RuntimeCommandError::unavailable)?;
        if workspaces.runtime_identity.as_ref() != Some(&binding.runtime_identity)
            || workspaces.pending_rebind.contains(workspace_id)
        {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(binding.workspace_id.clone())
    }

    /// 在当前服务双身份下打开已配置 root；RPC 前后均不持有 Workspace 锁，旧实例回包不得消费 pending selection。
    fn open_configured_workspace_if_present(
        &self,
        bridge: &Arc<dyn RuntimeBridgePort>,
    ) -> Result<String, RuntimeCommandError> {
        // 第一阶段只在锁内复制受信 capability 与显示字段；任何 actor/stdio 等待都不能
        // 跨越 Workspace 锁，否则 Turn admission callback 会与启动重放形成锁顺序反转。
        let (expected_identity, expected_handle_id, root, display_name) = {
            let workspace = self.workspace_guard()?;
            let Some(binding) = workspace.pending_project.as_ref() else {
                return Ok(String::new());
            };
            let identity = workspace
                .runtime_identity
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable)?;
            (
                identity,
                binding.handle.id(),
                binding.handle.root_path().to_path_buf(),
                binding.display_name.clone(),
            )
        };

        // 第二阶段由 actor 串行执行 Java workspace/open；此时 Workspace 锁为空闲，事件回调
        // 可以读取当前 binding，且跨进程等待不会阻塞本地 Files/Review capability 查询。
        let projection = bridge.workspace_open(root, display_name)?;
        self.ensure_workspace_identity(&expected_identity)?;
        let result_workspace_id = projection.workspace_id.clone();

        // 第三阶段重新取得 binding 并做 handle CAS。若并发配置已经提交，它对应的 actor
        // 请求必然位于本请求之后；保留较新的 Java identity，禁止旧结果回写。
        if projection.kind != WorkspaceKind::Project {
            return Err(RuntimeCommandError::unavailable());
        }
        let mut workspace = self.workspace_guard()?;
        if workspace.runtime_identity.as_ref() != Some(&expected_identity) {
            return Err(RuntimeCommandError::unavailable());
        }
        let Some(binding) = workspace.pending_project.take() else {
            return workspace
                .active_workspace_id
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable);
        };
        if binding.handle.id() != expected_handle_id {
            workspace.pending_project = Some(binding);
            return workspace
                .active_workspace_id
                .clone()
                .ok_or_else(RuntimeCommandError::unavailable);
        }
        let configured =
            ConfiguredWorkspace::from_projection(projection, binding.handle, expected_identity);
        let configured = workspace.insert_binding(configured)?;
        workspace.active_workspace_id = Some(configured.workspace_id.clone());
        Ok(result_workspace_id)
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

    /// 普通附件导入在调用入口冻结 active Workspace，避免后续切换把同一 ingress token 绑定到另一会话。
    pub(crate) fn attachment_import(
        &self,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        let workspace_id = self.active_workspace_id()?;
        self.attachment_import_for_workspace(&workspace_id, input)
    }

    /// 审查快照等冻结来源按原 Workspace identity 导入；切换当前 Workspace 不得重绑定附件所有权。
    pub(crate) fn attachment_import_for_workspace(
        &self,
        workspace_id: &str,
        input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        if !valid_protocol_id(workspace_id, "ws_", 99) {
            return Err(RuntimeCommandError::invalid_params());
        }
        let bridge = self.ready_bridge()?;
        let workspace_id = self
            .with_configured_workspace(workspace_id, |_| workspace_id.to_owned())
            .map_err(|_| RuntimeCommandError::unavailable())?;
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

    /// 草稿授权只使用当前原生 Workspace owner，避免 renderer 的旧投影把刚导入的附件授权到另一 Workspace；
    /// Thread 分支保留会话 identity，由 Java 同时校验排队预留与消息绑定关系。
    pub(crate) fn attachment_preview_open_with_authorization(
        &self,
        attachment_id: String,
        authorization: crate::attachment_preview::AttachmentPreviewAuthorizationInput,
    ) -> Result<(AttachmentPreviewOpenResult, String), RuntimeCommandError> {
        let workspace_id = self.active_workspace_id()?;
        let input = match authorization {
            crate::attachment_preview::AttachmentPreviewAuthorizationInput::Draft => {
                AttachmentPreviewOpenParams::draft(attachment_id, workspace_id.clone())
            }
            crate::attachment_preview::AttachmentPreviewAuthorizationInput::Thread {
                thread_id,
            } => AttachmentPreviewOpenParams::thread(attachment_id, thread_id),
        }
        .map_err(|_| RuntimeCommandError::invalid_params())?;
        let bridge = self.ready_bridge()?;
        Ok((bridge.attachment_preview_open(input)?, workspace_id))
    }

    /// read 仅路由 ja-runtime 已验证的 session/offset/limit，不接受任意 method。
    pub(crate) fn attachment_preview_read(
        &self,
        input: AttachmentPreviewReadParams,
    ) -> Result<AttachmentPreviewReadResult, RuntimeCommandError> {
        self.ready_bridge()?.attachment_preview_read(input)
    }

    /// close 复用 Ready generation；应用退出时 App Server 仍会统一清空剩余 session。
    pub(crate) fn attachment_preview_close(
        &self,
        input: AttachmentPreviewCloseParams,
    ) -> Result<(), RuntimeCommandError> {
        self.ready_bridge()?.attachment_preview_close(input)
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

/// 新 root 必须先通过与 Workspace capability 相同的拒绝 junction/symlink canonical admission。
fn workspace_handle_for_root(root: &str) -> Result<WorkspaceHandle, RuntimeCommandError> {
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(Path::new(root))
        .map_err(|_| RuntimeCommandError::configuration())?;
    registry
        .get(info.id)
        .map_err(|_| RuntimeCommandError::configuration())
}

/// Java projection 与 handle 可能使用 Windows 大小写/扩展前缀别名；优先比较物理身份，缺失 root 时只接受原 canonical spelling。
fn workspace_root_matches_handle(root: &str, handle: &WorkspaceHandle) -> bool {
    WorkspaceRegistry::default().matches_root_or_missing_spelling(Path::new(root), handle)
}

/// RPC 结果或不确定回读只有与用户补丁一致才可视为已提交；目录以物理 identity 比较而非字符串拼写。
fn workspace_update_matches(
    input: &WorkspaceUpdateInput,
    previous: &WorkspaceDto,
    observed: &WorkspaceDto,
    candidate: Option<&WorkspaceHandle>,
    root_changed: bool,
) -> bool {
    observed.workspace_id == input.workspace_id
        && observed.kind == WorkspaceKind::Project
        && if root_changed {
            observed.trust == "untrusted"
        } else {
            observed.trust == previous.trust
        }
        && input
            .display_name
            .as_ref()
            .is_none_or(|name| observed.display_name == *name)
        && match candidate {
            Some(candidate) => workspace_root_matches_handle(&observed.root, candidate),
            None => observed.root == previous.root,
        }
}
