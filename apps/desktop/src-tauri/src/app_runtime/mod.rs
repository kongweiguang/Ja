// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Ja App Server sidecar bridge 的 Tauri composition surface。

pub(crate) mod application;
pub(crate) mod domain;
pub(crate) mod infrastructure;
pub(crate) mod interface;

pub(crate) use application::WorkspaceLookup;
pub(crate) use application::{
    ConfigurationPatchParams, ConfigurationPatchResult, ConfigurationReadParams,
    ConfigurationReadResult, ConfigurationReplaceParams, ConfigurationReplaceResult,
    ConfigurationRequest, ConfigurationResetParams, ConfigurationResetResult,
    ConfigurationResponse, CredentialDeleteParams, CredentialDeleteResult,
    CredentialRevealProviderParams, CredentialRevealProviderResult, CredentialSetParams,
    CredentialSetResult, HistoryRequest, HistoryResponse, ItemContentReadParams,
    ItemContentReadResultData, McpListParams, McpListResultData, McpTestParams, McpTestResultData,
    McpToolsReadParams, McpToolsReadResultData, ModelDiscoverParams, ModelDiscoverResultData,
    ModelTestParams, ModelTestResultData, OperationReadParams, OperationReadResultData,
    RuntimeBridgePort, RuntimePlatformPort, SettingsRequest, SettingsResponse, SkillListParams,
    SkillListResultData, ThreadArchiveParams, ThreadArchiveResultData, ThreadCompactCancelParams,
    ThreadCompactCancelResultData, ThreadCompactParams, ThreadCompactResultData,
    ThreadCreateParams, ThreadCreateResultData, ThreadDeleteParams, ThreadDeleteResultData,
    ThreadDiscoverParams, ThreadDiscoverResultData, ThreadListParams, ThreadListResultData,
    ThreadMcpReadParams, ThreadMcpReadResultData, ThreadObserveParams, ThreadObserveResultData,
    ThreadPinParams, ThreadPinResultData, ThreadPreferencesUpdateParams,
    ThreadPreferencesUpdateResultData, ThreadReadParams, ThreadReadResultData, ThreadRenameParams,
    ThreadRenameResultData, ThreadRestoreParams, ThreadRestoreResultData, ThreadSearchParams,
    ThreadSearchResultData, ThreadSeenParams, ThreadSeenResultData, ThreadUnobserveParams,
    ThreadUnobserveResultData, ThreadUsageReadParams, ThreadUsageReadResultData,
    WorkspaceListParams, WorkspaceListResultData, WorkspaceRuntimeSource,
};
pub use application::{EventEmitError, RuntimeCommandError};
pub use application::{RuntimeHost, WorkspaceMetadataEventRouter};
pub use domain::{
    ApprovalResponseInput, AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata,
    AttachmentSummary, InputQueue, ManualRecoveryConfirmation, ManualRecoveryReason, QueuedInput,
    QueuedInputIssue, RuntimeRecoveryState, RuntimeStatus, RuntimeStatusKind, RuntimeStorageInfo,
    StorageRecovery, TaskActivity, TaskCloseInput, TaskCloseResult, TaskContextPreviewItem,
    TaskContextSeed, TaskCreateInput, TaskCreateResult, TaskFollowupInput, TaskFollowupResult,
    TaskListInput, TaskListResult, TaskMailboxMessage, TaskMessageInput, TaskMessageResult,
    TaskMutationInput, TaskObserveInput, TaskObserveResult, TaskReadInput, TaskReadResult,
    TaskSeenInput, TaskSummary, TaskThreadPreferences, TaskThreadSummary, TaskTreeDeleteInput,
    TaskTreeDeleteResult, TaskUnobserveInput, ToolArtifactReadInput, ToolArtifactReadResult,
    ToolRecoveryResponse, ToolRecoveryResponseInput, TurnAccepted, TurnCancelInput,
    TurnCancelResult, TurnChangeSetReadInput, TurnChangeSetReadResult, TurnContentPart,
    TurnContinueInput, TurnInputDelete, TurnInputEnqueue, TurnInputPrioritize, TurnInputResult,
    TurnInputUpdate, TurnReaskInput, TurnResumeInput, TurnStartInput, WorkspaceActivation,
    WorkspaceDto, WorkspaceKind, WorkspaceOpenInput, WorkspacePathSearchInput,
    WorkspacePathSearchItem, WorkspacePathSearchResult, WorkspaceUpdateInput,
};
pub(crate) use domain::{
    WorkflowMethod, WorkflowPayload, WorkflowPayloadError, WorkflowRequest, WorkflowResponse,
    valid_frozen_turn_id,
};
pub use infrastructure::EventSink;
pub(crate) use infrastructure::HomeLayout;
pub(crate) use infrastructure::NativeRuntimePlatform;
pub use infrastructure::{
    LaunchConfig, RuntimeConfigSource, bundled_launch_config, bundled_launch_config_with_dirs,
    prepare_run_dir,
};
pub(crate) use interface::event_projection::{emit_frame, emit_status, frame_to_value};
pub use interface::*;

impl RuntimeHost {
    /// 生产唯一构造入口在 composition root 绑定原生 platform，application 只接收抽象端口。
    pub fn new(config: LaunchConfig, sink: EventSink) -> Self {
        Self::compose(std::sync::Arc::new(NativeRuntimePlatform::new(
            config, sink,
        )))
    }

    /// App Server event sink 与 Host 共用 metadata router，使新 root 通知在对前端发布前撤销旧 capability。
    pub(crate) fn new_with_workspace_event_router(
        config: LaunchConfig,
        sink: EventSink,
        router: &WorkspaceMetadataEventRouter,
    ) -> Self {
        Self::compose_with_event_router(
            std::sync::Arc::new(NativeRuntimePlatform::new(config, sink)),
            router,
        )
    }
}
