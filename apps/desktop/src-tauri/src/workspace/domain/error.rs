// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use thiserror::Error;

/// IO 错误在领域层只保留恢复决策需要的闭集，避免 `std::io` 成为 domain 依赖。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IoFailureKind {
    NotFound,
    PermissionDenied,
    AlreadyExists,
    WouldBlock,
    InvalidInput,
    InvalidData,
    TimedOut,
    Interrupted,
    Unsupported,
    Other,
}

impl std::fmt::Display for IoFailureKind {
    /// 使用稳定词汇而非平台错误文本，确保 IPC 日志既可诊断又不泄露路径信息。
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::NotFound => "not_found",
            Self::PermissionDenied => "permission_denied",
            Self::AlreadyExists => "already_exists",
            Self::WouldBlock => "would_block",
            Self::InvalidInput => "invalid_input",
            Self::InvalidData => "invalid_data",
            Self::TimedOut => "timed_out",
            Self::Interrupted => "interrupted",
            Self::Unsupported => "unsupported",
            Self::Other => "other",
        })
    }
}

/// 稳定且经过路径脱敏的错误闭集阻止文件系统细节进入 IPC 层，并让 interface
/// 只依据恢复语义映射错误，不依赖平台消息文本。
#[derive(Debug, Error)]
pub enum WorkspaceError {
    #[error("workspace root is invalid")]
    InvalidRoot,
    #[error("workspace is not registered")]
    WorkspaceNotFound,
    #[error("relative path is invalid")]
    InvalidRelativePath,
    #[error("path is outside the workspace")]
    OutsideWorkspace,
    #[error("workspace path identity changed")]
    PathChanged,
    #[error("symlink or reparse point is not readable through this policy")]
    LinkNotAllowed,
    #[error("path does not exist")]
    PathNotFound,
    #[error("path is not a directory")]
    NotDirectory,
    #[error("path is not a regular file")]
    NotFile,
    #[error("workspace entry budget exceeded")]
    EntryBudgetExceeded,
    #[error("workspace depth limit exceeded")]
    DepthLimitExceeded,
    #[error("workspace scan deadline exceeded")]
    ScanDeadlineExceeded,
    #[error("tree cursor is stale and requires a fresh snapshot")]
    StaleCursor,
    #[error("file exceeds the configured size limit")]
    FileTooLarge,
    #[error("file changed while it was being read")]
    ChangedDuringRead,
    #[error("workspace entry already exists")]
    AlreadyExists,
    #[error("workspace mutation conflicts with the current revision")]
    RevisionConflict,
    #[error("workspace mutation id was already used")]
    MutationAlreadyUsed,
    #[error("workspace mutation id is invalid")]
    InvalidMutationId,
    #[error("workspace revision is invalid")]
    InvalidRevision,
    #[error("workspace content cannot be written with this encoding")]
    UnsupportedContent,
    #[error("workspace content has mixed line endings")]
    MixedLineEndings,
    #[error("workspace write exceeds the size limit")]
    WriteTooLarge,
    #[error("workspace mutation requires manual recovery")]
    RecoveryRequired,
    #[error("trash operation token is invalid")]
    TrashTokenInvalid,
    #[error("trash operation token expired")]
    TrashTokenExpired,
    #[error("system recycle bin is unavailable for this volume")]
    RecycleUnavailable,
    #[error("native drop token is invalid or expired")]
    DropTokenInvalid,
    #[error("workspace watcher is unavailable")]
    WatchUnavailable,
    #[error("workspace watcher shutdown exceeded its deadline")]
    WatchShutdownTimeout,
    #[error("I/O operation {operation} failed: {kind}")]
    Io {
        operation: &'static str,
        kind: IoFailureKind,
    },
}

impl WorkspaceError {
    /// 泛型转换点让 infrastructure 映射平台错误，domain 本身只认识稳定失败闭集。
    pub(crate) fn io<E>(operation: &'static str, error: E) -> Self
    where
        E: Into<IoFailureKind>,
    {
        Self::Io {
            operation,
            kind: error.into(),
        }
    }
}
