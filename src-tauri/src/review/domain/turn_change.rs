// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Turn 级工作区变化事实；只保存已冻结、可证明的 Git 差异，不复用当前 worktree 投影。

use super::ReviewFileStatus;

/// Rust 无法可靠证明一次 Turn 文件归属时使用的闭集原因。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TurnChangeUnavailableReason {
    NotGit,
    ConcurrentTurn,
    CaptureFailed,
    DiffTooLarge,
}

impl TurnChangeUnavailableReason {
    /// wire 只使用稳定原因词汇，原生 Git/路径错误不得跨越 JA-RPC。
    pub(crate) const fn as_str(self) -> &'static str {
        match self {
            Self::NotGit => "not_git",
            Self::ConcurrentTurn => "concurrent_turn",
            Self::CaptureFailed => "capture_failed",
            Self::DiffTooLarge => "diff_too_large",
        }
    }
}

/// 单个 Turn 冻结文件变化；行统计仅在完整文本 patch 可证明时存在。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct TurnChangeFile {
    pub(crate) path: String,
    pub(crate) old_path: Option<String>,
    pub(crate) status: ReviewFileStatus,
    pub(crate) additions: Option<u64>,
    pub(crate) deletions: Option<u64>,
    pub(crate) binary: bool,
    pub(crate) truncated: bool,
}

/// 聚合值只统计当前 files 中可证明的事实；`truncated` 阻止 UI 把未知当成零。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct TurnChangeStats {
    pub(crate) files: u64,
    pub(crate) additions: u64,
    pub(crate) deletions: u64,
    pub(crate) binary_files: u64,
    pub(crate) truncated: bool,
}

/// 交给 Java 持久化的完整 unified diff；artifact identity 最终由 App Server 分配。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct TurnChangeArtifact {
    pub(crate) sha256: String,
    pub(crate) byte_length: u64,
    pub(crate) unified_diff: String,
}

/// Turn 变化提交只允许 available 或显式 unavailable，禁止空数组隐式表达未知。
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum TurnChangeSet {
    Available {
        files: Vec<TurnChangeFile>,
        stats: TurnChangeStats,
        artifact: Option<TurnChangeArtifact>,
    },
    Unavailable {
        reason: TurnChangeUnavailableReason,
    },
}

impl TurnChangeSet {
    /// 构造显式 unavailable 事实，调用方仍需提交给 Java 才能在历史恢复后保持一致。
    pub(crate) const fn unavailable(reason: TurnChangeUnavailableReason) -> Self {
        Self::Unavailable { reason }
    }
}
