// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review application 的稳定错误词汇。
//
// 这里只表达调用方可恢复语义，不保留 Git、路径或已删除基础设施的实现类型；具体错误
// 映射由 infrastructure 完成，避免 application 反向依赖 adapter。

use std::fmt::{Display, Formatter};

/// typed desktop adapter 消费的稳定 Review error code。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReviewErrorCode {
    InvalidInput,
    NotConfigured,
    UnknownWorkspace,
    ReviewStale,
    ReadOnlySource,
    Conflict,
    GitUnavailable,
    NotRepository,
    GitFailed,
    ExternalWorktree,
    OutputLimitExceeded,
    Cancelled,
    Io,
    Parse,
}

/// Review 用例错误只保留恢复语义，避免泄露命令行、绝对路径与 Git stderr。
#[derive(Debug)]
pub enum ReviewError {
    InvalidInput,
    ReviewStale,
    ReadOnlySource,
    Conflict,
    OutputLimitExceeded,
    GitUnavailable,
    NotRepository,
    NativeCommandFailed,
    GitFailed,
    ExternalWorktree,
    Cancelled,
    Io,
    Parse,
}

impl ReviewError {
    /// 将用例错误映射为稳定 command code；新增内部 adapter 错误不能穿透此边界。
    pub fn code(&self) -> ReviewErrorCode {
        match self {
            Self::InvalidInput => ReviewErrorCode::InvalidInput,
            Self::ReviewStale => ReviewErrorCode::ReviewStale,
            Self::ReadOnlySource => ReviewErrorCode::ReadOnlySource,
            Self::Conflict => ReviewErrorCode::Conflict,
            Self::OutputLimitExceeded => ReviewErrorCode::OutputLimitExceeded,
            Self::GitUnavailable => ReviewErrorCode::GitUnavailable,
            Self::NotRepository => ReviewErrorCode::NotRepository,
            Self::NativeCommandFailed => ReviewErrorCode::GitFailed,
            Self::GitFailed => ReviewErrorCode::GitFailed,
            Self::ExternalWorktree => ReviewErrorCode::ExternalWorktree,
            Self::Cancelled => ReviewErrorCode::Cancelled,
            Self::Io => ReviewErrorCode::Io,
            Self::Parse => ReviewErrorCode::Parse,
        }
    }
}

impl Display for ReviewError {
    /// 保持 fallback 文本通用，确保 Tauri 序列化不会泄露路径或子进程诊断。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self.code() {
            ReviewErrorCode::InvalidInput => "Review request is invalid",
            ReviewErrorCode::NotConfigured => "workspace is not configured",
            ReviewErrorCode::UnknownWorkspace => "workspace is unknown",
            ReviewErrorCode::ReviewStale => "Review changed; reload before applying",
            ReviewErrorCode::ReadOnlySource => "this Review source is read-only",
            ReviewErrorCode::Conflict => "Review mutation conflicts with newer worktree changes",
            ReviewErrorCode::GitUnavailable => "Git is unavailable",
            ReviewErrorCode::NotRepository => "workspace is not a Git repository",
            ReviewErrorCode::GitFailed => "Git operation failed",
            ReviewErrorCode::ExternalWorktree => "Git worktree is not allowed",
            ReviewErrorCode::OutputLimitExceeded => "Review output exceeded its limit",
            ReviewErrorCode::Cancelled => "Review operation was cancelled",
            ReviewErrorCode::Io => "Review operation failed",
            ReviewErrorCode::Parse => "Review output could not be parsed",
        })
    }
}

impl std::error::Error for ReviewError {}
