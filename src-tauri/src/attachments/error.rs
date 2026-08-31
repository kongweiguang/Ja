// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use serde::Serialize;

/// ingress 对调用方公开稳定、脱敏的失败类别；路径与文件内容永远不进入错误载荷。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum AttachmentIngressErrorCode {
    TooManyFiles,
    FileTooLarge,
    BatchTooLarge,
    UnsupportedPath,
    NotRegularFile,
    LinkNotAllowed,
    SourceChanged,
    SourceReadFailed,
    StagingFailed,
    LifecycleClosed,
    TokenNotFound,
    CleanupFailed,
    StateUnavailable,
}

/// 错误对象刻意不保存 `PathBuf` 或底层错误字符串，防止 Debug、序列化与日志意外泄漏源路径。
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, Serialize)]
#[error("attachment ingress failed: {code:?}")]
pub(crate) struct AttachmentIngressError {
    pub(crate) code: AttachmentIngressErrorCode,
}

impl AttachmentIngressError {
    /// 从稳定类别构造脱敏错误；底层 IO 只由发生点以 kind/raw code 记录。
    pub(crate) const fn new(code: AttachmentIngressErrorCode) -> Self {
        Self { code }
    }
}

/// 记录足以诊断平台边界、但不包含路径或文件名的 IO 事实。
pub(super) fn map_io_error(
    code: AttachmentIngressErrorCode,
    operation: &'static str,
    error: std::io::Error,
) -> AttachmentIngressError {
    tracing::debug!(
        operation,
        error_kind = ?error.kind(),
        raw_os_error = error.raw_os_error(),
        "attachment ingress filesystem operation failed"
    );
    AttachmentIngressError::new(code)
}
