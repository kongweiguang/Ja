// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Ja App Server sidecar bridge 的 Tauri composition surface。

pub(crate) mod application;
pub(crate) mod domain;
pub(crate) mod infrastructure;
pub(crate) mod interface;

pub use application::RuntimeHost;
pub(crate) use application::WorkspaceLookup;
pub(crate) use application::{
    ConfigurationPatchParams, ConfigurationPatchResult, ConfigurationReadParams,
    ConfigurationReadResult, ConfigurationReplaceParams, ConfigurationReplaceResult,
    ConfigurationRequest, ConfigurationResetParams, ConfigurationResetResult,
    ConfigurationResponse, CredentialDeleteParams, CredentialDeleteResult, CredentialSetParams,
    CredentialSetResult, HistoryRequest, HistoryResponse, McpListParams, McpListResultData,
    McpTestParams, McpTestResultData, McpToolsReadParams, McpToolsReadResultData, ModelTestParams,
    ModelTestResultData, RuntimeBridgePort, RuntimePlatformPort, SettingsRequest, SettingsResponse,
    SkillListParams, SkillListResultData, ThreadArchiveParams, ThreadArchiveResultData,
    ThreadCompactParams, ThreadCompactResultData, ThreadCreateParams, ThreadCreateResultData,
    ThreadDeleteParams, ThreadDeleteResultData, ThreadListParams, ThreadListResultData,
    ThreadPreferencesUpdateParams, ThreadPreferencesUpdateResultData, ThreadReadParams,
    ThreadReadResultData, ThreadRenameParams, ThreadRenameResultData, ThreadSearchParams,
    ThreadSearchResultData, TurnChangeCaptureContext, WorkspaceListParams, WorkspaceListResultData,
    WorkspaceRuntimeSource,
};
pub use application::{EventEmitError, RuntimeCommandError};
pub(crate) use domain::valid_frozen_turn_id;
pub use domain::{
    ApprovalResponseInput, AttachmentDiscardInput, AttachmentImportInput, AttachmentMetadata,
    GeneralWorkspace, ManualRecoveryConfirmation, ManualRecoveryReason, RuntimeConfigurationStatus,
    RuntimeRecoveryState, RuntimeStatus, RuntimeStatusKind, RuntimeStorageInfo,
    ToolArtifactReadInput, ToolArtifactReadResult, TurnAccepted, TurnCancelInput, TurnCancelResult,
    TurnChangeSetReadInput, TurnChangeSetReadResult, TurnContentPart, TurnQueuedInput,
    TurnQueuedInputResult, TurnStartInput, WorkspaceDto, WorkspaceOpenInput,
};
pub use infrastructure::EventSink;
pub(crate) use infrastructure::HomeLayout;
pub(crate) use infrastructure::NativeRuntimePlatform;
pub(crate) use infrastructure::recovery_store::{
    clear_recovery_record, ensure_recovery_clear, persist_recovery_record, recovery_marker_path,
    recovery_state,
};
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
}
