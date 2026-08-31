// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Turn dirty baseline 与冻结 tree-to-tree diff 的原生实现。

use super::git::{GitError, GitReadOnly, GitStatusKind};
use super::git_query::{NameStatusRecord, SourceKind, diff_args, parse_name_status};
use super::parse::{ParsedFilePatch, parse_diff};
use super::transaction::GitIndexTransaction;
use crate::review::application::ReviewError;
use crate::review::domain::{
    MAX_REVIEW_DIFF_BYTES, MAX_REVIEW_DIFF_LINES, ReviewFileStatus, TurnChangeArtifact,
    TurnChangeFile, TurnChangeSet, TurnChangeStats, TurnChangeUnavailableReason,
};
use crate::workspace::WorkspaceHandle;
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::ffi::OsString;
use tokio_util::sync::CancellationToken;

const MAX_TURN_CHANGE_FILES: usize = 10_000;

/// turn/start 前冻结的工作区树与 native adapter；树包含当时 staged/unstaged/untracked 状态。
pub(crate) struct TurnChangeBaseline {
    workspace_id: String,
    capture: Result<CapturedTree, TurnChangeUnavailableReason>,
}

struct CapturedTree {
    git: GitReadOnly,
    workspace: WorkspaceHandle,
    tree_id: String,
    untracked_paths: BTreeSet<String>,
}

impl TurnChangeBaseline {
    /// 在真正投递 turn/start 前通过临时 index 捕获工作树；失败不阻止 Turn，但必须保留显式未知原因。
    pub(crate) fn capture(workspace_id: String, workspace: WorkspaceHandle) -> Self {
        let capture = match std::fs::symlink_metadata(workspace.root_path().join(".git")) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                Err(TurnChangeUnavailableReason::NotGit)
            }
            _ => capture_tree(workspace).map_err(map_capture_error),
        };
        Self {
            workspace_id,
            capture,
        }
    }

    /// 构造并发归属不明确的 baseline；不再运行 Git，以免后来误把共享变化归给某个 Turn。
    pub(crate) fn concurrent(workspace_id: String) -> Self {
        Self {
            workspace_id,
            capture: Err(TurnChangeUnavailableReason::ConcurrentTurn),
        }
    }

    /// 返回 App Server 已分配且 Rust 当前绑定的 workspace identity。
    pub(crate) fn workspace_id(&self) -> &str {
        &self.workspace_id
    }

    /// terminal 后捕获第二棵树并物化冻结差异；任何失败都降级为显式 unavailable，而非零文件。
    pub(crate) fn finish(self) -> TurnChangeSet {
        let baseline = match self.capture {
            Ok(capture) => capture,
            Err(reason) => return TurnChangeSet::unavailable(reason),
        };
        match finish_capture(baseline) {
            Ok(change_set) => change_set,
            Err(error) => TurnChangeSet::unavailable(map_capture_error(error)),
        }
    }
}

/// 使用 native-owned temporary index 将工作区现状写为 Git tree；真实 index 与 worktree 不被修改。
fn capture_tree(workspace: WorkspaceHandle) -> Result<CapturedTree, ReviewError> {
    let cancellation = CancellationToken::new();
    let git = GitReadOnly::new(workspace.clone()).map_err(ReviewError::from)?;
    // 全量 temporary index 会把每个 dirty/untracked 文件写入对象库。先用只读 status 执行同一
    // 文件数预算，超限时返回 diff_too_large，避免大型或误生成工作区阻塞 Turn 并遗留 Git 临时包。
    let status = git
        .review_status_all(&cancellation)
        .map_err(ReviewError::from)?;
    if status.len() > MAX_TURN_CHANGE_FILES {
        return Err(ReviewError::OutputLimitExceeded);
    }
    let untracked_paths = status
        .into_iter()
        .filter(|entry| entry.kind == GitStatusKind::Untracked)
        .map(|entry| entry.path)
        .collect();
    let real_index = git
        .review_index_path(&cancellation)
        .map_err(ReviewError::from)?;
    let transaction = GitIndexTransaction::begin(&git, &real_index, &cancellation)?;
    git.run_review_command_with_index(
        &[
            OsString::from("add"),
            OsString::from("-A"),
            OsString::from("--"),
            OsString::from("."),
        ],
        transaction.temporary_path(),
        &cancellation,
    )
    .map_err(ReviewError::from)?;
    let tree = git
        .run_review_command_with_index(
            &[OsString::from("write-tree")],
            transaction.temporary_path(),
            &cancellation,
        )
        .map_err(ReviewError::from)?;
    let tree_id = String::from_utf8(tree)
        .map_err(|_| ReviewError::Parse)?
        .trim()
        .to_owned();
    if !matches!(tree_id.len(), 40 | 64) || !tree_id.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(ReviewError::Parse);
    }
    Ok(CapturedTree {
        git,
        workspace,
        tree_id,
        untracked_paths,
    })
}

/// 在同一 canonical workspace 捕获终态，并用两棵树消除 Turn 开始前已存在的 dirty 内容。
fn finish_capture(baseline: CapturedTree) -> Result<TurnChangeSet, ReviewError> {
    if worktree_still_matches(&baseline)? {
        return materialize_change_set(Vec::new(), Vec::new());
    }
    let final_tree = capture_tree(baseline.workspace.clone())?;
    let cancellation = CancellationToken::new();
    let kind = SourceKind::Range {
        left: baseline.tree_id,
        right: final_tree.tree_id,
    };
    let mut names_args = diff_args(&kind, true);
    names_args.push(OsString::from("--"));
    let records = baseline
        .git
        .run_review_command(&names_args, &cancellation)
        .map_err(ReviewError::from)
        .and_then(|bytes| parse_name_status(&baseline.workspace, &bytes))?;
    if records.len() > MAX_TURN_CHANGE_FILES {
        return Err(ReviewError::OutputLimitExceeded);
    }
    let mut patch_args = diff_args(&kind, false);
    patch_args.push(OsString::from("--"));
    let patch = baseline
        .git
        .run_review_command(&patch_args, &cancellation)
        .map_err(ReviewError::from)?;
    materialize_change_set(records, patch)
}

/// 只读 Turn 优先把 baseline tree 直接与当前 worktree 比较，避免为零修改再次执行全量 `git add`。
/// baseline 中原本 untracked 的文件已经进入 tree，tracked diff 能检测其修改/删除；额外比较两次
/// untracked 集合用于捕获本轮新增文件，并在返回前复核一次以缩小扫描期间的竞态窗口。
fn worktree_still_matches(baseline: &CapturedTree) -> Result<bool, ReviewError> {
    let cancellation = CancellationToken::new();
    for _ in 0..2 {
        let args = [
            OsString::from("diff"),
            OsString::from("--quiet"),
            OsString::from("--no-ext-diff"),
            OsString::from("--no-textconv"),
            OsString::from(&baseline.tree_id),
            OsString::from("--"),
            OsString::from("."),
        ];
        match baseline.git.run_review_command(&args, &cancellation) {
            Ok(_) => {}
            Err(GitError::CommandFailed { code: Some(1) }) => return Ok(false),
            Err(error) => return Err(ReviewError::from(error)),
        }
        let current = baseline
            .git
            .review_status_all(&cancellation)
            .map_err(ReviewError::from)?
            .into_iter()
            .filter(|entry| entry.kind == GitStatusKind::Untracked)
            .map(|entry| entry.path)
            .collect::<BTreeSet<_>>();
        if current != baseline.untracked_paths {
            return Ok(false);
        }
    }
    Ok(true)
}

/// 将固定 name-status 与完整 patch 关联；匹配失败只标记该文件 truncated，不猜测行统计。
fn materialize_change_set(
    records: Vec<NameStatusRecord>,
    patch: Vec<u8>,
) -> Result<TurnChangeSet, ReviewError> {
    // available 表示 Review 能读取完整冻结事实；超过预算时不能只保留文件列表并把缺失
    // artifact 伪装成可用，因此统一降级为显式 diff_too_large。
    if patch.len() > MAX_REVIEW_DIFF_BYTES {
        return Err(ReviewError::OutputLimitExceeded);
    }
    let parsed = parse_diff(&patch)?;
    let mut used = BTreeSet::new();
    let mut files = Vec::with_capacity(records.len());
    for record in records {
        let parsed_file = parsed.iter().enumerate().find_map(|(index, value)| {
            if used.contains(&index) {
                return None;
            }
            (value.new_path.as_deref() == Some(record.path.as_str())
                || value.old_path.as_deref() == Some(record.path.as_str())
                || record
                    .old_path
                    .as_deref()
                    .is_some_and(|old| value.old_path.as_deref() == Some(old)))
            .then_some((index, value))
        });
        if let Some((index, _)) = parsed_file {
            used.insert(index);
        }
        files.push(project_change_file(
            record,
            parsed_file.map(|(_, value)| value),
        ));
    }
    let artifact = if patch.is_empty() {
        None
    } else {
        let unified_diff = String::from_utf8(patch.clone()).map_err(|_| ReviewError::Parse)?;
        Some(TurnChangeArtifact {
            sha256: hex_digest(Sha256::digest(&patch)),
            byte_length: patch.len() as u64,
            unified_diff,
        })
    };
    let mut stats = TurnChangeStats {
        files: files.len() as u64,
        truncated: false,
        ..TurnChangeStats::default()
    };
    for file in &files {
        stats.additions = stats
            .additions
            .saturating_add(file.additions.unwrap_or_default());
        stats.deletions = stats
            .deletions
            .saturating_add(file.deletions.unwrap_or_default());
        stats.binary_files = stats.binary_files.saturating_add(u64::from(file.binary));
        stats.truncated |= file.truncated;
    }
    Ok(TurnChangeSet::Available {
        files,
        stats,
        artifact,
    })
}

/// 单文件只投影能从完整 parsed block 证明的统计；binary 与超限文件保持未知。
fn project_change_file(
    record: NameStatusRecord,
    parsed: Option<&ParsedFilePatch>,
) -> TurnChangeFile {
    let binary = parsed.is_some_and(|value| value.binary);
    let line_limit_hit = parsed.is_some_and(|value| {
        value
            .hunks
            .iter()
            .any(|hunk| hunk.lines.len() >= MAX_REVIEW_DIFF_LINES)
    });
    let truncated = parsed.is_none() || line_limit_hit;
    TurnChangeFile {
        path: record.path,
        old_path: record.old_path,
        status: normalize_turn_status(record.status),
        additions: (!binary && !truncated)
            .then(|| parsed.and_then(|value| value.additions))
            .flatten(),
        deletions: (!binary && !truncated)
            .then(|| parsed.and_then(|value| value.deletions))
            .flatten(),
        binary,
        truncated,
    }
}

/// tree-to-tree diff 不应产生 untracked/conflict；仍收敛到当前协议闭集，避免 wire 扩张。
fn normalize_turn_status(status: ReviewFileStatus) -> ReviewFileStatus {
    match status {
        ReviewFileStatus::Added | ReviewFileStatus::Untracked => ReviewFileStatus::Added,
        ReviewFileStatus::Deleted => ReviewFileStatus::Deleted,
        ReviewFileStatus::Renamed | ReviewFileStatus::Copied => ReviewFileStatus::Renamed,
        ReviewFileStatus::Modified | ReviewFileStatus::Conflict => ReviewFileStatus::Modified,
    }
}

/// 将内部 Review/Git 错误压缩为持久化稳定原因，不暴露仓库结构或命令状态。
fn map_capture_error(error: ReviewError) -> TurnChangeUnavailableReason {
    match error {
        ReviewError::GitUnavailable | ReviewError::ExternalWorktree => {
            TurnChangeUnavailableReason::NotGit
        }
        ReviewError::OutputLimitExceeded => TurnChangeUnavailableReason::DiffTooLarge,
        _ => TurnChangeUnavailableReason::CaptureFailed,
    }
}

/// artifact hash 使用固定小写十六进制，Java 可在持久化前复核同一 byte identity。
fn hex_digest(digest: impl AsRef<[u8]>) -> String {
    digest
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}

impl From<GitError> for TurnChangeUnavailableReason {
    /// 保留给窄 native caller 的统一降级映射，避免日志或 RPC 包含 Git error 文本。
    fn from(error: GitError) -> Self {
        map_capture_error(ReviewError::from(error))
    }
}
