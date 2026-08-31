// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime Host 应用层：负责编排生命周期、串行准入和跨能力事务边界。

pub(crate) mod error;
pub(crate) mod host;
pub(crate) mod ports;

pub use error::RuntimeCommandError;
pub use host::RuntimeHost;
pub(crate) use host::WorkspaceLookup;
pub use ports::EventEmitError;
pub(crate) use ports::{
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
