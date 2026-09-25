// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::io;
use thiserror::Error;

/// Git 错误刻意省略 command line、cwd 与 raw stderr，防止未来 IPC mapper
/// 意外暴露用户路径或继承的 Secret。
#[derive(Debug, Error)]
pub enum GitError {
    #[error("Git executable is unavailable")]
    GitUnavailable,
    #[error("workspace is not a Git repository")]
    NotRepository,
    #[error("Git path argument is invalid")]
    InvalidPath,
    #[error("external Git worktree metadata is not allowed")]
    ExternalWorktree,
    #[error("Git command could not be started: {kind}")]
    Spawn { kind: io::ErrorKind },
    #[error("Git command failed with exit code {code:?}")]
    CommandFailed { code: Option<i32> },
    #[error("Git command timed out")]
    TimedOut,
    #[error("Git command was cancelled")]
    Cancelled,
    #[error("Git output exceeded the configured cap")]
    OutputLimitExceeded,
    #[error("Git process cleanup exceeded its deadline")]
    CleanupTimedOut,
    #[error("Git output could not be parsed")]
    Parse,
    #[error("workspace error")]
    Workspace,
}
