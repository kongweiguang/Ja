// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime application 的窄原生端口与固定操作模型。

use super::error::RuntimeCommandError;
use crate::app_runtime::domain::{
    ApprovalResponseInput, AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata,
    GoalRequest, GoalResponse, ManualRecoveryConfirmation, RuntimeRecoveryState, RuntimeStatus,
    RuntimeStorageInfo, TaskCreateInput, TaskCreateResult, TaskFollowupInput, TaskFollowupResult,
    TaskListInput, TaskListResult, TaskMessageInput, TaskMessageResult, TaskMutationInput,
    TaskObserveInput, TaskObserveResult, TaskReadInput, TaskReadResult, TaskSeenInput, TaskSummary,
    TaskTreeDeleteInput, TaskTreeDeleteResult, TaskUnobserveInput, ToolArtifactReadInput,
    ToolArtifactReadResult, TurnAccepted, TurnCancelInput, TurnCancelResult,
    TurnChangeSetReadInput, TurnChangeSetReadResult, TurnInputDelete, TurnInputEnqueue,
    TurnInputPrioritize, TurnInputResult, TurnInputUpdate, TurnResumeInput, TurnStartInput,
    WorkspaceDto, WorkspacePathSearchInput, WorkspacePathSearchResult,
};
use ja_runtime::app_server_process::{
    AttachmentPreviewCloseParams, AttachmentPreviewOpenParams, AttachmentPreviewOpenResult,
    AttachmentPreviewReadParams, AttachmentPreviewReadResult,
};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;

/// 事件投递失败只保留队列或交付类别，避免 application 依赖 Tauri、窗口或原生错误文本。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EventEmitError {
    QueueFull,
    DeliveryFailed,
}

const MAX_ENCODED_PAYLOAD_BYTES: usize = 4 * 1024 * 1024;

/// 为每个冻结 operation 生成不同的 nominal payload；共同实现只负责不可绕过的大小准入，
/// 不提供 Debug、借用或通用转换，避免 application 读取内容或日志意外输出用户数据。
macro_rules! define_operation_payload {
    ($($name:ident),+ $(,)?) => {
        $(
            pub(crate) struct $name(Vec<u8>);

            impl $name {
                /// 构造 operation-specific payload 时立即执行统一体积上限；空 payload 不是合法 JA-RPC params/result。
                pub(crate) fn try_new(bytes: Vec<u8>) -> Result<Self, RuntimeCommandError> {
                    if bytes.is_empty() || bytes.len() > MAX_ENCODED_PAYLOAD_BYTES {
                        return Err(RuntimeCommandError::invalid_params());
                    }
                    Ok(Self(bytes))
                }

                /// 只允许固定 infrastructure/interface 消费完整所有权，不暴露可变或共享字节视图。
                pub(crate) fn into_bytes(self) -> Vec<u8> {
                    self.0
                }
            }
        )+
    };
}

define_operation_payload!(
    ConfigurationReadParams,
    ConfigurationReadResult,
    ConfigurationPatchParams,
    ConfigurationPatchResult,
    ConfigurationReplaceParams,
    ConfigurationReplaceResult,
    ConfigurationResetParams,
    ConfigurationResetResult,
    CredentialSetParams,
    CredentialSetResult,
    CredentialDeleteParams,
    CredentialDeleteResult,
    WorkspaceListParams,
    WorkspaceListResultData,
    ThreadCreateParams,
    ThreadCreateResultData,
    ThreadListParams,
    ThreadListResultData,
    ThreadSearchParams,
    ThreadSearchResultData,
    ThreadReadParams,
    ThreadReadResultData,
    ThreadRenameParams,
    ThreadRenameResultData,
    ThreadPinParams,
    ThreadPinResultData,
    ThreadSeenParams,
    ThreadSeenResultData,
    ThreadPreferencesUpdateParams,
    ThreadPreferencesUpdateResultData,
    ThreadCompactParams,
    ThreadCompactResultData,
    ThreadArchiveParams,
    ThreadArchiveResultData,
    ThreadRestoreParams,
    ThreadRestoreResultData,
    ThreadDeleteParams,
    ThreadDeleteResultData,
    SkillListParams,
    SkillListResultData,
    McpListParams,
    McpListResultData,
    McpTestParams,
    McpTestResultData,
    ModelTestParams,
    ModelTestResultData,
    McpToolsReadParams,
    McpToolsReadResultData,
);

/// 配置用例闭集；每个 variant 都绑定自己的语义，字节只是在 interface 与 infrastructure
/// 间运输已验证 payload，application 不能遍历、拼接或改写 JSON。
pub(crate) enum ConfigurationRequest {
    Read(ConfigurationReadParams),
    Patch(ConfigurationPatchParams),
    Replace(ConfigurationReplaceParams),
    Reset(ConfigurationResetParams),
    CredentialSet(CredentialSetParams),
    CredentialDelete(CredentialDeleteParams),
}

/// 配置响应保持与请求同构的封闭 variant；Host 必须验证 operation 配对后才返回 interface。
pub(crate) enum ConfigurationResponse {
    Read(ConfigurationReadResult),
    Patch(ConfigurationPatchResult),
    Replace(ConfigurationReplaceResult),
    Reset(ConfigurationResetResult),
    CredentialSet(CredentialSetResult),
    CredentialDelete(CredentialDeleteResult),
}

/// History 用例闭集；不接受 method 字符串或可递归动态树。
pub(crate) enum HistoryRequest {
    WorkspaceList(WorkspaceListParams),
    ThreadCreate(ThreadCreateParams),
    ThreadList(ThreadListParams),
    ThreadSearch(ThreadSearchParams),
    ThreadRead(ThreadReadParams),
    ThreadRename(ThreadRenameParams),
    ThreadPin(ThreadPinParams),
    ThreadSeen(ThreadSeenParams),
    ThreadPreferencesUpdate(ThreadPreferencesUpdateParams),
    ThreadCompact(ThreadCompactParams),
    ThreadArchive(ThreadArchiveParams),
    ThreadRestore(ThreadRestoreParams),
    ThreadDelete(ThreadDeleteParams),
}

/// History 返回 variant 与用例一一对应，防止调用方把一种响应按另一种 DTO 解析。
pub(crate) enum HistoryResponse {
    WorkspaceList(WorkspaceListResultData),
    ThreadCreate(ThreadCreateResultData),
    ThreadList(ThreadListResultData),
    ThreadSearch(ThreadSearchResultData),
    ThreadRead(ThreadReadResultData),
    ThreadRename(ThreadRenameResultData),
    ThreadPin(ThreadPinResultData),
    ThreadSeen(ThreadSeenResultData),
    ThreadPreferencesUpdate(ThreadPreferencesUpdateResultData),
    ThreadCompact(ThreadCompactResultData),
    ThreadArchive(ThreadArchiveResultData),
    ThreadRestore(ThreadRestoreResultData),
    ThreadDelete(ThreadDeleteResultData),
}

/// Settings 查询闭集；MCP 与 Skill 查询不能退化成任意 JA-RPC tunnel。
pub(crate) enum SettingsRequest {
    SkillList(SkillListParams),
    McpList(McpListParams),
    McpTest(McpTestParams),
    ModelTest(ModelTestParams),
    McpToolsRead(McpToolsReadParams),
}

/// Settings 响应保留具体查询身份，interface 必须按对应 schema 解析。
pub(crate) enum SettingsResponse {
    SkillList(SkillListResultData),
    McpList(McpListResultData),
    McpTest(McpTestResultData),
    ModelTest(ModelTestResultData),
    McpToolsRead(McpToolsReadResultData),
}

/// application 只观察固定 Runtime 能力；进程、Session、JSON 与事件读取均由 infrastructure 实现。
pub(crate) trait RuntimeBridgePort: Send + Sync {
    fn start(&self) -> Result<RuntimeStatus, RuntimeCommandError>;
    fn stop(&self) -> Result<RuntimeStatus, RuntimeCommandError>;
    fn state(&self) -> Result<RuntimeStatus, RuntimeCommandError>;
    fn configuration(
        &self,
        request: ConfigurationRequest,
    ) -> Result<ConfigurationResponse, RuntimeCommandError>;
    fn workspace_open(
        &self,
        root: PathBuf,
        display_name: String,
        trust: String,
    ) -> Result<WorkspaceDto, RuntimeCommandError>;
    fn general_workspace(&self) -> Result<WorkspaceDto, RuntimeCommandError>;
    /// 路径搜索默认失败关闭，只有生产 Java owner 或明确 fixture 可返回当前 generation 结果。
    fn workspace_path_search(
        &self,
        _input: WorkspacePathSearchInput,
    ) -> Result<WorkspacePathSearchResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    fn health(&self) -> Result<(), RuntimeCommandError>;
    fn turn_start(&self, input: TurnStartInput) -> Result<TurnAccepted, RuntimeCommandError>;
    fn turn_cancel(&self, input: TurnCancelInput) -> Result<TurnCancelResult, RuntimeCommandError>;
    /// 既有 fake 默认不声称支持持久恢复；生产 bridge 必须显式覆盖并路由到 Java owner。
    fn turn_resume(&self, _input: TurnResumeInput) -> Result<TurnAccepted, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// 旧 fake 默认失败关闭新队列能力；生产 bridge 必须显式覆盖四个固定 mutation。
    fn turn_input_enqueue(
        &self,
        _input: TurnInputEnqueue,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// prioritize 不回退为旧 steering admission，防止测试双轨掩盖生产协议漂移。
    fn turn_input_prioritize(
        &self,
        _input: TurnInputPrioritize,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// update 默认关闭，只有真实 Java owner 或明确实现队列 CAS 的 fake 可返回成功。
    fn turn_input_update(
        &self,
        _input: TurnInputUpdate,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// delete 默认关闭，避免 fake 在没有消费竞态模型时伪造删除结果。
    fn turn_input_delete(
        &self,
        _input: TurnInputDelete,
    ) -> Result<TurnInputResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// task/create 默认失败关闭；只有生产 Java owner 或显式任务 fixture 能创建 Child Thread。
    fn task_create(
        &self,
        _input: TaskCreateInput,
    ) -> Result<TaskCreateResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// task/list 默认不扫描本地缓存，防止 fake 掩盖 Java projection 缺失。
    fn task_list(&self, _input: TaskListInput) -> Result<TaskListResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// task/read 默认不物化 transcript，生产 bridge 必须走固定 JA-RPC reader。
    fn task_read(&self, _input: TaskReadInput) -> Result<TaskReadResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// observation 只能由真实 connection owner 创建，fake 不返回伪 handle。
    fn task_observe(
        &self,
        _input: TaskObserveInput,
    ) -> Result<TaskObserveResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// unobserve 默认失败关闭，避免调用方把未释放的 handle 误判为已清理。
    fn task_unobserve(&self, _input: TaskUnobserveInput) -> Result<(), RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// Reload 清理默认失败关闭，只有维护真实 connection registry 的生产 bridge 可以成功。
    fn release_task_observations(
        &self,
        _owner: &'static str,
    ) -> Result<usize, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// seen 的 unread CAS 只由 Java 投影返回，不从 renderer 计数派生。
    fn task_seen(&self, _input: TaskSeenInput) -> Result<TaskSummary, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// QueueOnly message 默认关闭，fake 不在内存中模拟 exactly-once Mailbox。
    fn task_message_send(
        &self,
        _input: TaskMessageInput,
    ) -> Result<TaskMessageResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// followup 默认关闭，避免未建模的 fake 伪造 Child Turn 调度。
    fn task_followup(
        &self,
        _input: TaskFollowupInput,
    ) -> Result<TaskFollowupResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// cancel 传播由 Java lineage 决定，application port 不接受传播开关。
    fn task_cancel(&self, _input: TaskMutationInput) -> Result<TaskSummary, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// 整树删除只在真实持久化事务确认后返回数量。
    fn task_tree_delete(
        &self,
        _input: TaskTreeDeleteInput,
    ) -> Result<TaskTreeDeleteResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// Goal/Plan 默认失败关闭；只有生产 Java owner 或显式协议 fixture 可实现持久状态变更。
    fn goal(&self, _request: GoalRequest) -> Result<GoalResponse, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// 冻结 diff 只能通过固定 reader 读取；默认关闭可防止 fake 绕过 Java 持久化归属校验。
    fn turn_change_set_read(
        &self,
        _input: TurnChangeSetReadInput,
    ) -> Result<TurnChangeSetReadResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// Tool artifact 同样保持四元 identity；默认端口不提供内存 fallback。
    fn tool_artifact_read(
        &self,
        _input: ToolArtifactReadInput,
    ) -> Result<ToolArtifactReadResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    fn approval_respond(&self, input: ApprovalResponseInput) -> Result<(), RuntimeCommandError>;
    fn attachment_import(
        &self,
        _workspace_id: String,
        _input: AttachmentImportInput,
    ) -> Result<AttachmentMetadata, RuntimeCommandError> {
        // 既有 fake port 默认关闭新能力，只有明确实现附件 lane 的 production/fake 才可观察成功。
        Err(RuntimeCommandError::unavailable())
    }
    fn attachment_discard(
        &self,
        _input: AttachmentDiscardInput,
    ) -> Result<(), RuntimeCommandError> {
        // 默认失败关闭可避免测试 port 在未实现持久化语义时伪造 discard 成功。
        Err(RuntimeCommandError::unavailable())
    }
    /// 附件预览 open 默认失败关闭，fake 必须显式实现才能伪造 Java 授权。
    fn attachment_preview_open(
        &self,
        _input: AttachmentPreviewOpenParams,
    ) -> Result<AttachmentPreviewOpenResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// 附件预览 read 默认失败关闭，防止测试端口绕过 opaque session。
    fn attachment_preview_read(
        &self,
        _input: AttachmentPreviewReadParams,
    ) -> Result<AttachmentPreviewReadResult, RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    /// 附件预览 close 默认失败关闭，只有真实 owner 可确认 session 已释放。
    fn attachment_preview_close(
        &self,
        _input: AttachmentPreviewCloseParams,
    ) -> Result<(), RuntimeCommandError> {
        Err(RuntimeCommandError::unavailable())
    }
    fn history(&self, request: HistoryRequest) -> Result<HistoryResponse, RuntimeCommandError>;
    fn settings(&self, request: SettingsRequest) -> Result<SettingsResponse, RuntimeCommandError>;
    fn shutdown(&self) -> Result<(), RuntimeCommandError>;
    fn shutdown_until(&self, deadline: Instant) -> Result<(), RuntimeCommandError>;
    fn exit_ready(&self) -> bool;
    fn record_forced_exit(&self);
}

/// Workspace 输入经原生路径策略解析后才形成 application 可使用的受信 capability source。
pub(crate) struct WorkspaceRuntimeSource {
    pub(crate) root: PathBuf,
    pub(crate) display_name: String,
    pub(crate) trust: String,
}

/// Host platform 端口拥有启动配置、恢复文件、目录投影与 bridge factory。
/// application 因此不依赖 LaunchConfig、sidecar、文件系统或测试控制实现。
pub(crate) trait RuntimePlatformPort: Send + Sync {
    fn create_bridge(&self) -> Result<Arc<dyn RuntimeBridgePort>, RuntimeCommandError>;
    fn recovery_state(&self) -> RuntimeRecoveryState;
    fn acknowledge_recovery(
        &self,
        confirmation: &ManualRecoveryConfirmation,
    ) -> Result<RuntimeRecoveryState, RuntimeCommandError>;
    fn storage_info(&self) -> RuntimeStorageInfo;
    fn workspace_source(
        &self,
        cwd: &str,
        display_name: Option<&str>,
        trust: &str,
    ) -> Result<WorkspaceRuntimeSource, RuntimeCommandError>;
}
