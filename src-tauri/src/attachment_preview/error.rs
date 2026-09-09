// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 附件预览宿主的稳定、脱敏错误闭集。

use serde::Serialize;

/// UI 只依赖可恢复语义，不接收图片解码器、锁、token 或 App Server 诊断文本。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, thiserror::Error)]
#[serde(rename_all = "snake_case")]
pub enum AttachmentPreviewErrorCode {
    #[error("invalid_input")]
    InvalidInput,
    #[error("unsupported")]
    Unsupported,
    #[error("token_not_found")]
    TokenNotFound,
    #[error("token_expired")]
    TokenExpired,
    #[error("wrong_window")]
    WrongWindow,
    #[error("source_unavailable")]
    SourceUnavailable,
    #[error("invalid_image")]
    InvalidImage,
    #[error("image_budget_exceeded")]
    ImageBudgetExceeded,
    #[error("cache_budget_exceeded")]
    CacheBudgetExceeded,
    #[error("state_unavailable")]
    StateUnavailable,
}

/// Command/protocol 共用同一错误结构，避免两个边界对重试语义作不同猜测。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, thiserror::Error)]
#[error("attachment preview failed: {code}")]
#[serde(rename_all = "camelCase")]
pub struct AttachmentPreviewError {
    pub code: AttachmentPreviewErrorCode,
    pub message: &'static str,
    pub retryable: bool,
}

impl AttachmentPreviewError {
    /// 只从稳定类别推导静态消息，底层错误永远不会进入 WebView 或日志载荷。
    pub const fn new(code: AttachmentPreviewErrorCode) -> Self {
        let (message, retryable) = match code {
            AttachmentPreviewErrorCode::InvalidInput => ("invalid preview request", false),
            AttachmentPreviewErrorCode::Unsupported => {
                ("preview is unavailable for this attachment", false)
            }
            AttachmentPreviewErrorCode::TokenNotFound => ("preview is no longer available", false),
            AttachmentPreviewErrorCode::TokenExpired => ("preview expired", true),
            AttachmentPreviewErrorCode::WrongWindow => {
                ("preview is unavailable in this window", false)
            }
            AttachmentPreviewErrorCode::SourceUnavailable => {
                ("attachment preview is temporarily unavailable", true)
            }
            AttachmentPreviewErrorCode::InvalidImage => {
                ("image preview could not be created", false)
            }
            AttachmentPreviewErrorCode::ImageBudgetExceeded => {
                ("image preview exceeds the safety limit", false)
            }
            AttachmentPreviewErrorCode::CacheBudgetExceeded => {
                ("image preview is too large", false)
            }
            AttachmentPreviewErrorCode::StateUnavailable => {
                ("attachment preview service is unavailable", true)
            }
        };
        Self {
            code,
            message,
            retryable,
        }
    }

    /// Runtime/sidecar 失败只继承恢复语义，不转发内部 code、message 或 RPC payload。
    pub const fn runtime(retryable: bool) -> Self {
        Self {
            code: AttachmentPreviewErrorCode::SourceUnavailable,
            message: "attachment preview is temporarily unavailable",
            retryable,
        }
    }
}
