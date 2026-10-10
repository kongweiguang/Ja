// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! JA-RPC v2 的 framing、严格 JSON 与握手协议。
//!
//! 协议语义集中在这里，是为了让进程和 client 生命周期只能消费已校验 frame；
//! 子模块保持 crate 私有，外部调用方只能经过 `app_server_process` façade。

mod attachment_preview;
pub(crate) mod catalog;
pub(crate) mod codec;
mod error_policy;
mod frame;
mod framing;
pub(crate) mod handshake;
mod json;
mod limits;
mod process_session;
mod review;
mod thread_fork;
mod turn_change_set;
mod usage;

pub(crate) use attachment_preview::validate_attachment_preview_request;
pub use attachment_preview::{
    ATTACHMENT_PREVIEW_READ_MAX_BYTES, ATTACHMENT_PREVIEW_READ_MIN_BYTES,
    AttachmentPreviewCloseParams, AttachmentPreviewCloseResult, AttachmentPreviewContractError,
    AttachmentPreviewOpenParams, AttachmentPreviewOpenResult, AttachmentPreviewReadParams,
    AttachmentPreviewReadResult,
};
pub(crate) use catalog::CLIENT_METHODS;
pub(crate) use codec::decode_frame;
pub(crate) use codec::is_ready_token_key;
pub use codec::valid_ready_token;
pub use error_policy::CodecError;
pub(crate) use frame::FrameKind;
pub use frame::RpcFrame;
pub(crate) use framing::read_frame_with_forbidden;
pub(crate) use handshake::*;
pub use limits::Limits;
pub use process_session::{
    ProcessSessionListParams, ProcessSessionListResult, ProcessSessionReadParams,
    ProcessSessionSnapshot, ProcessSessionState, ProcessSessionStopParams, ProcessSessionSummary,
    ProcessSessionWriteParams,
};
pub use review::{
    ReviewCoverage, ReviewCoverageFile, ReviewFinding, ReviewLayer, ReviewPartialReason,
    ReviewReadParams, ReviewReadResult, ReviewReport, ReviewSeverity, ReviewSide,
    ReviewStartParams, ReviewStartResult, ReviewTurnStatus,
};
pub use thread_fork::{
    ForkAccessMode, ForkCollaborationMode, ForkThreadStatus, ForkTitleSource, ForkTurnStatus,
    ForkWorkspaceKind, ThreadForkParams, ThreadForkPreferences, ThreadForkResult, ThreadForkSource,
    ThreadForkThread,
};
pub(crate) use turn_change_set::validate_turn_change_set_request;
pub use turn_change_set::{
    TURN_CHANGE_SET_MAX_BYTES, TurnChangeSetContractError, TurnChangeSetReadParams,
    TurnChangeSetReadResult,
};
pub use usage::{
    ProviderRequestProfileDto, ThreadContextEstimateDto, ThreadContextUsageDto, ThreadUsageSummary,
    UsageProtocolError, parse_thread_context_usage, parse_thread_usage_summary,
};

/// 向 Tauri host 暴露与握手和事件一致的 RFC3339 校验，避免两层复制不同的时间解析器。
pub fn valid_protocol_timestamp(value: &str) -> bool {
    handshake::valid_timestamp(value)
}
