// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review Tauri wire DTO 与稳定错误映射。

use crate::app_runtime::WorkspaceLookup;
use crate::review::application::{ReviewError, ReviewErrorCode};
use crate::review::domain::{
    ReviewAction, ReviewCommitId, ReviewFileId, ReviewFileLayer, ReviewFileStatus, ReviewHunkId,
    ReviewRefId, ReviewSource, ReviewTarget,
};
use serde::{Deserialize, Serialize};
use std::fmt::{Display, Formatter};
/// 仅含 metadata 的 invalidation event；消费者必须 refetch，不能把 hint 当事务结果。
pub const JA_REVIEW_INVALIDATED_EVENT: &str = "review/invalidated";

/// TypeScript adapter 消费的稳定 command error envelope。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct ReviewCommandError {
    pub code: ReviewErrorCodeDto,
}

/// Tauri wire 专用错误码；application error vocabulary 不携带 serde 契约。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ReviewErrorCodeDto {
    InvalidInput,
    NotConfigured,
    UnknownWorkspace,
    ReviewStale,
    ReviewReadOnly,
    ReviewConflict,
    GitUnavailable,
    NotGitRepository,
    GitFailed,
    WorkspaceEscape,
    ReviewLimit,
    Cancelled,
    IoError,
    ParseError,
}

impl From<ReviewErrorCode> for ReviewErrorCodeDto {
    /// 在 IPC 最外层将内部恢复语义映射为既有 wire 字符串，避免 serde 进入 application。
    fn from(value: ReviewErrorCode) -> Self {
        match value {
            ReviewErrorCode::InvalidInput => Self::InvalidInput,
            ReviewErrorCode::NotConfigured => Self::NotConfigured,
            ReviewErrorCode::UnknownWorkspace => Self::UnknownWorkspace,
            ReviewErrorCode::ReviewStale => Self::ReviewStale,
            ReviewErrorCode::ReadOnlySource => Self::ReviewReadOnly,
            ReviewErrorCode::Conflict => Self::ReviewConflict,
            ReviewErrorCode::GitUnavailable => Self::GitUnavailable,
            ReviewErrorCode::NotRepository => Self::NotGitRepository,
            ReviewErrorCode::GitFailed => Self::GitFailed,
            ReviewErrorCode::ExternalWorktree => Self::WorkspaceEscape,
            ReviewErrorCode::OutputLimitExceeded => Self::ReviewLimit,
            ReviewErrorCode::Cancelled => Self::Cancelled,
            ReviewErrorCode::Io => Self::IoError,
            ReviewErrorCode::Parse => Self::ParseError,
        }
    }
}

impl ReviewCommandError {
    /// 投影内部 service failure，不让路径或 Git subprocess output 进入 WebView。
    pub(super) fn from_review(error: ReviewError) -> Self {
        Self {
            code: error.code().into(),
        }
    }

    /// 将 workspace lookup failure 投影到同一封闭 error vocabulary。
    pub(super) fn from_lookup(error: WorkspaceLookup) -> Self {
        Self {
            code: match error {
                WorkspaceLookup::Unconfigured => ReviewErrorCodeDto::NotConfigured,
                WorkspaceLookup::Unknown => ReviewErrorCodeDto::UnknownWorkspace,
            },
        }
    }

    /// 在 workspace lookup 与 Git 启动前创建 request validation error。
    pub(super) const fn invalid_input() -> Self {
        Self {
            code: ReviewErrorCodeDto::InvalidInput,
        }
    }
}

impl Display for ReviewCommandError {
    /// 保持 Tauri fallback text 稳定且路径脱敏。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self.code {
            ReviewErrorCodeDto::InvalidInput => "Review request is invalid",
            ReviewErrorCodeDto::NotConfigured => "workspace is not configured",
            ReviewErrorCodeDto::UnknownWorkspace => "workspace is unknown",
            ReviewErrorCodeDto::ReviewStale => "Review changed; reload before applying",
            ReviewErrorCodeDto::ReviewReadOnly => "this Review source is read-only",
            ReviewErrorCodeDto::ReviewConflict => "Review mutation conflicts with newer changes",
            ReviewErrorCodeDto::GitUnavailable => "Git is unavailable",
            ReviewErrorCodeDto::NotGitRepository => "workspace is not a Git repository",
            ReviewErrorCodeDto::GitFailed => "Git operation failed",
            ReviewErrorCodeDto::WorkspaceEscape => "Git worktree is not allowed",
            ReviewErrorCodeDto::ReviewLimit => "Review output exceeded its limit",
            ReviewErrorCodeDto::Cancelled => "Review operation was cancelled",
            ReviewErrorCodeDto::IoError => "Review operation failed",
            ReviewErrorCodeDto::ParseError => "Review output could not be parsed",
        })
    }
}

impl std::error::Error for ReviewCommandError {}

/// Review source 的 wire tagged union；反序列化完成后必须转换为领域值对象。
#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum ReviewSourceDto {
    Uncommitted,
    Unstaged,
    Staged,
    Branch {
        #[serde(rename = "refId")]
        ref_id: String,
    },
    Commit {
        #[serde(rename = "commitId")]
        commit_id: String,
    },
}

impl TryFrom<ReviewSourceDto> for ReviewSource {
    type Error = ReviewCommandError;

    /// 把 wire 字符串收敛为强类型 selector，后续层不再重复长度、控制字符或 Thread 前缀判断。
    fn try_from(value: ReviewSourceDto) -> Result<Self, Self::Error> {
        match value {
            ReviewSourceDto::Uncommitted => Ok(Self::Uncommitted),
            ReviewSourceDto::Unstaged => Ok(Self::Unstaged),
            ReviewSourceDto::Staged => Ok(Self::Staged),
            ReviewSourceDto::Branch { ref_id } => ReviewRefId::parse(ref_id)
                .map(|ref_id| Self::Branch { ref_id })
                .map_err(|_| ReviewCommandError::invalid_input()),
            ReviewSourceDto::Commit { commit_id } => ReviewCommitId::parse(commit_id)
                .map(|commit_id| Self::Commit { commit_id })
                .map_err(|_| ReviewCommandError::invalid_input()),
        }
    }
}

impl From<ReviewSource> for ReviewSourceDto {
    /// 将可信 domain selector 投影回既有 tagged wire shape，不暴露值对象内部表示。
    fn from(value: ReviewSource) -> Self {
        match value {
            ReviewSource::Uncommitted => Self::Uncommitted,
            ReviewSource::Unstaged => Self::Unstaged,
            ReviewSource::Staged => Self::Staged,
            ReviewSource::Branch { ref_id } => Self::Branch {
                ref_id: ref_id.into_string(),
            },
            ReviewSource::Commit { commit_id } => Self::Commit {
                commit_id: commit_id.into_string(),
            },
        }
    }
}

/// Git 比较层的稳定 wire 枚举；该字段是 file identity 的组成部分，不允许缺省推断。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewFileLayerDto {
    Staged,
    Unstaged,
    Untracked,
    Comparison,
}

impl From<ReviewFileLayer> for ReviewFileLayerDto {
    /// 在 IPC 边界逐项映射层身份，避免前端从 source/status 猜测部分暂存关系。
    fn from(value: ReviewFileLayer) -> Self {
        match value {
            ReviewFileLayer::Staged => Self::Staged,
            ReviewFileLayer::Unstaged => Self::Unstaged,
            ReviewFileLayer::Untracked => Self::Untracked,
            ReviewFileLayer::Comparison => Self::Comparison,
        }
    }
}

/// mutation action 的 wire enum；领域层不需要知道 serde 命名规则。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ReviewActionDto {
    Stage,
    Unstage,
    Revert,
}

impl From<ReviewActionDto> for ReviewAction {
    /// 逐项映射封闭 action，保持未知 variant 由 serde 在 interface 直接拒绝。
    fn from(value: ReviewActionDto) -> Self {
        match value {
            ReviewActionDto::Stage => Self::Stage,
            ReviewActionDto::Unstage => Self::Unstage,
            ReviewActionDto::Revert => Self::Revert,
        }
    }
}

/// mutation target 的 wire tagged union；file/hunk identity 只在转换时校验一次。
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase", deny_unknown_fields)]
pub enum ReviewTargetDto {
    All,
    File {
        #[serde(rename = "fileId")]
        file_id: String,
    },
    Hunk {
        #[serde(rename = "fileId")]
        file_id: String,
        #[serde(rename = "hunkId")]
        hunk_id: String,
    },
}

impl TryFrom<ReviewTargetDto> for ReviewTarget {
    type Error = ReviewCommandError;

    /// 同时转换 file/hunk identity，任一非法都在副作用发生前返回稳定输入错误。
    fn try_from(value: ReviewTargetDto) -> Result<Self, Self::Error> {
        match value {
            ReviewTargetDto::All => Ok(Self::All),
            ReviewTargetDto::File { file_id } => ReviewFileId::parse(file_id)
                .map(|file_id| Self::File { file_id })
                .map_err(|_| ReviewCommandError::invalid_input()),
            ReviewTargetDto::Hunk { file_id, hunk_id } => Ok(Self::Hunk {
                file_id: ReviewFileId::parse(file_id)
                    .map_err(|_| ReviewCommandError::invalid_input())?,
                hunk_id: ReviewHunkId::parse(hunk_id)
                    .map_err(|_| ReviewCommandError::invalid_input())?,
            }),
        }
    }
}

/// file status 的 wire enum，保持既有 snake_case contract。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewFileStatusDto {
    Added,
    Modified,
    Deleted,
    Renamed,
    Copied,
    Untracked,
    #[serde(rename = "conflicted")]
    Conflict,
}

impl From<ReviewFileStatus> for ReviewFileStatusDto {
    /// 在 interface 逐项投影状态，避免领域枚举承担序列化兼容责任。
    fn from(value: ReviewFileStatus) -> Self {
        match value {
            ReviewFileStatus::Added => Self::Added,
            ReviewFileStatus::Modified => Self::Modified,
            ReviewFileStatus::Deleted => Self::Deleted,
            ReviewFileStatus::Renamed => Self::Renamed,
            ReviewFileStatus::Copied => Self::Copied,
            ReviewFileStatus::Untracked => Self::Untracked,
            ReviewFileStatus::Conflict => Self::Conflict,
        }
    }
}

/// 请求 configured workspace 的 catalog metadata。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewCatalogInput {
    pub workspace_id: String,
    #[serde(default)]
    pub max_commits: Option<usize>,
}

/// 请求一个权威 source snapshot。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewSnapshotInput {
    pub workspace_id: String,
    pub source: ReviewSourceDto,
}

/// 通过 opaque file id 与 revision 请求 lazy file diff。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewFileDiffInput {
    pub workspace_id: String,
    pub source: ReviewSourceDto,
    pub revision: String,
    pub file_id: String,
}

/// 请求 all/file/hunk mutation，并携带可取消 operation identity。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewApplyInput {
    pub workspace_id: String,
    pub source: ReviewSourceDto,
    pub revision: String,
    pub action: ReviewActionDto,
    pub target: ReviewTargetDto,
    pub operation_id: String,
}

/// 请求取消一个 live native operation。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ReviewCancelInput {
    pub workspace_id: String,
    pub operation_id: String,
}

/// 与当前 TS adapter 对齐的 catalog ref projection。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewRefDto {
    pub ref_id: String,
    pub label: String,
    pub kind: ReviewRefKind,
}

/// 区分 selector 的 base、local 与 remote ref candidate。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ReviewRefKind {
    Base,
    Local,
    Remote,
}

/// 与当前 TS adapter 对齐的 catalog commit projection。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewCommitDto {
    pub commit_id: String,
    pub subject: String,
    pub author: String,
    pub authored_at: String,
}

/// catalog projection 只携带协议 workspace id，不包含 native root。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewCatalogDto {
    pub workspace_id: String,
    pub repository_name: String,
    pub current_branch: Option<String>,
    pub head_commit_id: Option<String>,
    pub base_refs: Vec<ReviewRefDto>,
    pub commits: Vec<ReviewCommitDto>,
}

/// hunk wire projection 刻意省略 native raw patch；详细 line 由 lazy file-diff 返回。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewHunkDto {
    pub hunk_id: String,
    pub header: String,
    pub old_start: u64,
    pub old_lines: u64,
    pub new_start: u64,
    pub new_lines: u64,
}

/// file tree projection 使用 TS contract 的 `truncated` 字段名。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewFileDto {
    pub file_id: String,
    pub layer: ReviewFileLayerDto,
    pub path: String,
    pub old_path: Option<String>,
    pub status: ReviewFileStatusDto,
    pub additions: Option<u64>,
    pub deletions: Option<u64>,
    pub binary: bool,
    pub truncated: bool,
    pub hunks: Vec<ReviewHunkDto>,
}

/// stats projection 按当前 TS shape 保留单一 boolean truncation bit。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewStatsDto {
    pub files: u64,
    pub additions: u64,
    pub deletions: u64,
    pub binary_files: u64,
    pub truncated: bool,
}

/// mutation capability 从 source 推导，React 不得自行猜测。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewCapabilitiesDto {
    pub stage: bool,
    pub unstage: bool,
    pub revert: bool,
}

/// Review controller 消费的权威 snapshot projection。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewSnapshotDto {
    pub workspace_id: String,
    pub source: ReviewSourceDto,
    pub revision: String,
    pub files: Vec<ReviewFileDto>,
    pub stats: ReviewStatsDto,
    pub capabilities: ReviewCapabilitiesDto,
}

/// 带 old/new 坐标的 rendered diff line，供 CodeMirror projection 使用。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewDiffLineDto {
    pub kind: ReviewDiffLineKind,
    pub old_line: Option<u64>,
    pub new_line: Option<u64>,
    pub text: String,
}

/// TypeScript adapter 接受的封闭 diff line category。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum ReviewDiffLineKind {
    Context,
    Addition,
    Deletion,
}

/// 与当前 TS adapter 有界 shape 对齐的 lazy file diff projection。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewFileDiffDto {
    pub workspace_id: String,
    pub source: ReviewSourceDto,
    pub revision: String,
    pub file_id: String,
    pub layer: ReviewFileLayerDto,
    pub path: String,
    pub old_path: Option<String>,
    pub status: ReviewFileStatusDto,
    pub binary: bool,
    pub truncated: bool,
    pub original: Option<String>,
    pub modified: Option<String>,
    pub unified: Option<String>,
    pub hunks: Vec<ReviewHunkDto>,
    pub lines: Vec<ReviewDiffLineDto>,
}

/// mutation response 始终包含 fresh snapshot 与 operation id。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewApplyResultDto {
    pub workspace_id: String,
    pub operation_id: String,
    pub applied: bool,
    pub snapshot: ReviewSnapshotDto,
}

/// cancel response 报告是否找到 live worker。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewCancelResultDto {
    pub workspace_id: String,
    pub operation_id: String,
    pub cancelled: bool,
}

/// composition root emitter 可使用的稳定 invalidation payload。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReviewInvalidatedEventDto {
    pub workspace_id: String,
    pub generation: u64,
    pub reason: ReviewInvalidatedReason,
}

/// 与 TypeScript event schema 对齐的封闭 invalidation reason。
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ReviewInvalidatedReason {
    Mutation,
    External,
    TurnCompleted,
    RepositoryChanged,
}
