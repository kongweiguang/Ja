// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 附件图片/文本 Preview 的 native capability host。
//!
//! Tauri composition root 只需托管 `AttachmentPreviewHost`、注册三个 command，并将
//! `ja-attachment://` 请求交给 `interface::protocol`。纯协议规则不依赖 Tauri API。

pub mod bridge;
mod cache;
pub mod commands;
pub mod error;
pub mod host;
pub mod image_pipeline;
pub(crate) mod interface;
pub mod model;
pub mod protocol;

pub use bridge::{project_preview_descriptor, project_text_preview_chunk};
pub use commands::{
    ATTACHMENT_PREVIEW_CLOSE_COMMAND, ATTACHMENT_PREVIEW_OPEN_COMMAND,
    ATTACHMENT_PREVIEW_READ_COMMAND, AttachmentPreviewAuthorizationInput,
    AttachmentPreviewCloseInput, AttachmentPreviewOpenInput, AttachmentPreviewReadInput,
    AttachmentPreviewRuntimePort, AttachmentPreviewRuntimeState, RuntimeOpenedAttachmentPreview,
    ja_attachment_preview_close, ja_attachment_preview_open, ja_attachment_preview_read,
};
pub use error::{AttachmentPreviewError, AttachmentPreviewErrorCode};
pub use host::AttachmentPreviewHost;
pub use image_pipeline::{AttachmentImageDerivatives, derive_attachment_image};
pub use model::{
    ATTACHMENT_PREVIEW_MAIN_WINDOW, ATTACHMENT_PREVIEW_SCHEME, AttachmentPreviewClock,
    AttachmentPreviewCloseResult, AttachmentPreviewDescriptor, AttachmentPreviewKind,
    AttachmentPreviewLimits, AttachmentPreviewOpenResult, AttachmentPreviewSessionId,
    AttachmentResourceToken, AttachmentResourceVariant, AttachmentTextReadResult,
};
pub use protocol::{
    AttachmentProtocolHeaders, AttachmentProtocolRequest, attachment_protocol_status,
    parse_attachment_protocol_uri,
};
