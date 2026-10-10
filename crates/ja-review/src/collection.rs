// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Bounded, read-only Git materialization shared by desktop and CLI review flows.

use crate::{
    ReviewFileLayer, ReviewFileStatus, ReviewSnapshot, ReviewSnapshotBuilder, ReviewSnapshotSource,
    SnapshotError, SnapshotFileInput, SnapshotPartialReason, validate_relative_path,
};
use sha2::{Digest, Sha256};
use std::fs::{self, File};
use std::io::Read;
use std::path::{Component, Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use thiserror::Error;
use tokio_util::sync::CancellationToken;

const MAX_GIT_STATUS_BYTES: usize = 16 * 1024 * 1024;
const MAX_GIT_ERROR_BYTES: usize = 64 * 1024;
const PROCESS_POLL_INTERVAL: Duration = Duration::from_millis(10);

/// Cancellation is borrowed so each host keeps ownership of its own turn and signal lifecycle.
pub trait SnapshotCancellation {
    /// Read the host's cancellation state without exposing the token's mutation capability.
    fn is_cancellation_requested(&self) -> bool;
}

impl SnapshotCancellation for AtomicBool {
    /// Map the CLI's signal flag to the shared review cancellation contract.
    fn is_cancellation_requested(&self) -> bool {
        self.load(Ordering::Acquire)
    }
}

impl SnapshotCancellation for CancellationToken {
    /// Preserve the desktop operation token's cross-thread cancellation visibility.
    fn is_cancellation_requested(&self) -> bool {
        self.is_cancelled()
    }
}

/// Stable collection failures avoid retaining command output, credentials, or repository paths.
#[derive(Debug, Error, Clone, Copy, PartialEq, Eq)]
pub enum SnapshotCollectionError {
    #[error("review snapshot collection was cancelled")]
    Cancelled,
    #[error("review snapshot collection deadline elapsed")]
    DeadlineExceeded,
    #[error("Git executable is unavailable")]
    GitUnavailable,
    #[error("workspace is not an admitted Git worktree")]
    InvalidRepository,
    #[error("Git selector is invalid or does not resolve to a commit")]
    InvalidSelector,
    #[error("Git command failed")]
    GitCommandFailed,
    #[error("Git or snapshot output exceeded its declared budget")]
    OutputLimitExceeded,
    #[error("repository changed while the snapshot was being collected")]
    StaleSnapshot,
    #[error("review snapshot could not be built or verified")]
    InvalidSnapshot,
}

impl From<SnapshotError> for SnapshotCollectionError {
    /// Keep builder details behind the stable collection error surface.
    fn from(_: SnapshotError) -> Self {
        Self::InvalidSnapshot
    }
}

/// Resolve a user selector once and return only the full object ID accepted by the snapshot contract.
pub fn resolve_commit_id(
    root: &Path,
    selector: &str,
    cancellation: &impl SnapshotCancellation,
    deadline: Instant,
) -> Result<String, SnapshotCollectionError> {
    check_budget(cancellation, deadline)?;
    if selector.is_empty()
        || selector.len() > 1_024
        || selector.starts_with('-')
        || selector.chars().any(char::is_control)
    {
        return Err(SnapshotCollectionError::InvalidSelector);
    }
    let repository = Repository::open(root, cancellation, deadline)?;
    let resolved = repository.resolve_selector(selector, cancellation, deadline)?;
    if !valid_object_id(&resolved) {
        return Err(SnapshotCollectionError::InvalidSelector);
    }
    Ok(resolved)
}

/// Collect a frozen, budgeted snapshot and reject repository or file changes observed during collection.
pub fn create_snapshot(
    root: &Path,
    workspace_id: &str,
    source: ReviewSnapshotSource,
    cancellation: &impl SnapshotCancellation,
    deadline: Instant,
) -> Result<ReviewSnapshot, SnapshotCollectionError> {
    check_budget(cancellation, deadline)?;
    let repository = Repository::open(root, cancellation, deadline)?;
    let plan = repository.plan(&source, cancellation, deadline)?;
    let mut builder = ReviewSnapshotBuilder::new(
        workspace_id.to_owned(),
        source,
        plan.source_revision.clone(),
    )?;
    let mut worktree_evidence: Vec<(String, [u8; 32])> = Vec::new();

    for file in plan.files {
        check_budget(cancellation, deadline)?;
        if builder.remaining_files() == 0 {
            builder.mark_partial(SnapshotPartialReason::FileLimit);
            break;
        }
        if !validate_snapshot_file_path(&file.path)
            || file
                .old_path
                .as_deref()
                .is_some_and(|path| !validate_snapshot_file_path(path))
        {
            builder.mark_partial(SnapshotPartialReason::NonUtf8);
            continue;
        }

        let mut remaining = builder.remaining_bytes();
        let base_content = match &file.base_spec {
            Some(spec) => match repository.read_blob(spec, remaining, cancellation, deadline)? {
                Some(bytes) => {
                    remaining = remaining.saturating_sub(bytes.len());
                    Some(bytes)
                }
                None => {
                    builder.mark_partial(SnapshotPartialReason::ByteLimit);
                    break;
                }
            },
            None => None,
        };
        let content = match &file.content {
            ContentSource::Blob(spec) => {
                match repository.read_blob(spec, remaining, cancellation, deadline)? {
                    Some(bytes) => {
                        remaining = remaining.saturating_sub(bytes.len());
                        Some(bytes)
                    }
                    None => {
                        builder.mark_partial(SnapshotPartialReason::ByteLimit);
                        break;
                    }
                }
            }
            ContentSource::Worktree(path) => {
                match read_worktree_file(&repository.root, path, remaining, cancellation, deadline)?
                {
                    Some(bytes) => {
                        remaining = remaining.saturating_sub(bytes.len());
                        worktree_evidence.push((path.clone(), Sha256::digest(&bytes).into()));
                        Some(bytes)
                    }
                    None => {
                        builder.mark_partial(SnapshotPartialReason::ByteLimit);
                        break;
                    }
                }
            }
            ContentSource::Absent => None,
        };
        let patch = match &file.patch_args {
            Some(args) => match repository.read_git(args, remaining, cancellation, deadline)? {
                Some(bytes) => Some(bytes),
                None => {
                    builder.mark_partial(SnapshotPartialReason::ByteLimit);
                    break;
                }
            },
            None => None,
        };
        if file.conflicted {
            builder.mark_partial(SnapshotPartialReason::ContentUnavailable);
        }
        if !builder.push_file(SnapshotFileInput {
            path: file.path,
            old_path: file.old_path,
            status: file.status,
            layer: file.layer,
            binary: false,
            base_content,
            content,
            patch,
        })? {
            break;
        }
    }

    repository.verify_fence(&plan.fence, cancellation, deadline)?;
    for (path, expected_hash) in worktree_evidence {
        check_budget(cancellation, deadline)?;
        let bytes = read_worktree_file(
            &repository.root,
            &path,
            crate::MAX_SNAPSHOT_BYTES,
            cancellation,
            deadline,
        )?
        .ok_or(SnapshotCollectionError::StaleSnapshot)?;
        let actual: [u8; 32] = Sha256::digest(&bytes).into();
        if actual != expected_hash {
            return Err(SnapshotCollectionError::StaleSnapshot);
        }
    }
    repository.verify_fence(&plan.fence, cancellation, deadline)?;
    builder.finish().map_err(Into::into)
}

/// One Git invocation retains only bounded output and never passes through a shell.
struct Repository {
    root: PathBuf,
    git_program: PathBuf,
}

/// A single operation's identity fence covers HEAD, porcelain state, and exact index object entries.
#[derive(Clone, PartialEq, Eq)]
struct RepositoryFence {
    mutable: bool,
    head: Option<String>,
    status: Vec<u8>,
    index: Vec<u8>,
}

/// Every materialized row names its immutable sources before any blob or patch is read.
struct PlannedFile {
    path: String,
    old_path: Option<String>,
    status: ReviewFileStatus,
    layer: ReviewFileLayer,
    base_spec: Option<String>,
    content: ContentSource,
    patch_args: Option<Vec<String>>,
    conflicted: bool,
}

/// Current content can come from an immutable Git object, a fenced worktree read, or nowhere.
enum ContentSource {
    Blob(String),
    Worktree(String),
    Absent,
}

/// A resolved immutable comparison or mutable-worktree snapshot plan.
struct SnapshotPlan {
    source_revision: String,
    fence: RepositoryFence,
    files: Vec<PlannedFile>,
}

/// A subprocess result keeps exit status separate so expected missing objects can be handled explicitly.
struct GitOutput {
    status: ExitStatus,
    stdout: Vec<u8>,
}

/// Bounded pipe result reports overflow without preserving the excess bytes.
struct PipeOutput {
    bytes: Vec<u8>,
    exceeded: bool,
}

impl Repository {
    /// Canonicalize the requested root and admit only a worktree whose Git metadata stays inside it.
    fn open(
        root: &Path,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<Self, SnapshotCollectionError> {
        check_budget(cancellation, deadline)?;
        let root =
            fs::canonicalize(root).map_err(|_| SnapshotCollectionError::InvalidRepository)?;
        if !root.is_dir() {
            return Err(SnapshotCollectionError::InvalidRepository);
        }
        let git_program = resolve_git_program().ok_or(SnapshotCollectionError::GitUnavailable)?;
        let repository = Self { root, git_program };
        let top = repository.success(
            &["rev-parse", "--show-toplevel"],
            MAX_GIT_ERROR_BYTES,
            cancellation,
            deadline,
        )?;
        let top = output_path(&top.stdout)?;
        let git_dir = repository.success(
            &["rev-parse", "--absolute-git-dir"],
            MAX_GIT_ERROR_BYTES,
            cancellation,
            deadline,
        )?;
        let git_dir = fs::canonicalize(output_path(&git_dir.stdout)?)
            .map_err(|_| SnapshotCollectionError::InvalidRepository)?;
        if top != repository.root || !git_dir.starts_with(&repository.root) {
            return Err(SnapshotCollectionError::InvalidRepository);
        }
        Ok(repository)
    }

    /// Resolve a rev selector using Git's option terminator and require a full lowercase commit ID.
    fn resolve_selector(
        &self,
        selector: &str,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<String, SnapshotCollectionError> {
        let expression = format!("{selector}^{{commit}}");
        let output = self.run(
            &["rev-parse", "--verify", "--end-of-options", &expression],
            MAX_GIT_ERROR_BYTES,
            cancellation,
            deadline,
        )?;
        if !output.status.success() {
            return Err(SnapshotCollectionError::InvalidSelector);
        }
        let value = output_text(&output.stdout)?;
        if valid_object_id(value) {
            Ok(value.to_owned())
        } else {
            Err(SnapshotCollectionError::InvalidSelector)
        }
    }

    /// Require an already frozen full object ID to exist as a commit before it enters a plan.
    fn verify_object_id(
        &self,
        object_id: &str,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<String, SnapshotCollectionError> {
        if !valid_object_id(object_id) {
            return Err(SnapshotCollectionError::InvalidSelector);
        }
        let resolved = self.resolve_selector(object_id, cancellation, deadline)?;
        if resolved != object_id {
            return Err(SnapshotCollectionError::InvalidSelector);
        }
        Ok(resolved)
    }

    /// Capture a full Git fence, then retain only the selected mutable layer so staged and worktree evidence never mix.
    fn plan(
        &self,
        source: &ReviewSnapshotSource,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<SnapshotPlan, SnapshotCollectionError> {
        match source {
            ReviewSnapshotSource::Uncommitted
            | ReviewSnapshotSource::Staged
            | ReviewSnapshotSource::Unstaged => {
                let fence = self.read_fence(cancellation, deadline)?;
                let mut files = self.plan_uncommitted(&fence, cancellation, deadline)?;
                let source_tag: &[u8] = match source {
                    ReviewSnapshotSource::Uncommitted => b"uncommitted",
                    ReviewSnapshotSource::Staged => b"staged",
                    ReviewSnapshotSource::Unstaged => b"unstaged",
                    ReviewSnapshotSource::Base { .. } | ReviewSnapshotSource::Commit { .. } => {
                        return Err(SnapshotCollectionError::InvalidSnapshot);
                    }
                };
                match source {
                    ReviewSnapshotSource::Staged => {
                        files.retain(|file| file.layer == ReviewFileLayer::Staged);
                    }
                    ReviewSnapshotSource::Unstaged => {
                        files.retain(|file| {
                            matches!(
                                file.layer,
                                ReviewFileLayer::Unstaged | ReviewFileLayer::Untracked
                            )
                        });
                    }
                    ReviewSnapshotSource::Uncommitted => {}
                    ReviewSnapshotSource::Base { .. } | ReviewSnapshotSource::Commit { .. } => {
                        return Err(SnapshotCollectionError::InvalidSnapshot);
                    }
                }
                let revision = source_revision(&[
                    source_tag,
                    fence.head.as_deref().unwrap_or("unborn").as_bytes(),
                    &fence.status,
                    &fence.index,
                ]);
                Ok(SnapshotPlan {
                    source_revision: revision,
                    fence,
                    files,
                })
            }
            ReviewSnapshotSource::Base { base_commit_id } => {
                let base = self.verify_object_id(base_commit_id, cancellation, deadline)?;
                let head = self.read_head(cancellation, deadline)?;
                let target = match &head {
                    Some(value) => value.clone(),
                    None => self.empty_tree(cancellation, deadline)?,
                };
                let fence = RepositoryFence {
                    mutable: false,
                    head,
                    status: Vec::new(),
                    index: Vec::new(),
                };
                let files = self.plan_comparison(&base, &target, cancellation, deadline)?;
                Ok(SnapshotPlan {
                    source_revision: source_revision(&[base.as_bytes(), target.as_bytes()]),
                    fence,
                    files,
                })
            }
            ReviewSnapshotSource::Commit { commit_id } => {
                let commit = self.verify_object_id(commit_id, cancellation, deadline)?;
                let parent = self.parent_or_empty_tree(&commit, cancellation, deadline)?;
                let fence = RepositoryFence {
                    mutable: false,
                    head: None,
                    status: Vec::new(),
                    index: Vec::new(),
                };
                let files = self.plan_comparison(&parent, &commit, cancellation, deadline)?;
                Ok(SnapshotPlan {
                    source_revision: source_revision(&[parent.as_bytes(), commit.as_bytes()]),
                    fence,
                    files,
                })
            }
        }
    }

    /// Expand porcelain-v2 entries into distinct staged, unstaged, untracked, and conflict evidence.
    fn plan_uncommitted(
        &self,
        fence: &RepositoryFence,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<Vec<PlannedFile>, SnapshotCollectionError> {
        let rows = parse_status(&fence.status)?;
        let mut files = Vec::new();
        for row in rows {
            check_budget(cancellation, deadline)?;
            if !validate_snapshot_file_path(&row.path)
                || row
                    .old_path
                    .as_deref()
                    .is_some_and(|path| !validate_snapshot_file_path(path))
            {
                files.push(PlannedFile {
                    path: row.path,
                    old_path: row.old_path,
                    status: ReviewFileStatus::Untracked,
                    layer: ReviewFileLayer::Untracked,
                    base_spec: None,
                    content: ContentSource::Absent,
                    patch_args: None,
                    conflicted: false,
                });
                continue;
            }
            if row.untracked {
                files.push(PlannedFile {
                    path: row.path.clone(),
                    old_path: None,
                    status: ReviewFileStatus::Untracked,
                    layer: ReviewFileLayer::Untracked,
                    base_spec: None,
                    content: ContentSource::Worktree(row.path),
                    patch_args: None,
                    conflicted: false,
                });
                continue;
            }
            if row.conflicted {
                files.push(PlannedFile {
                    path: row.path.clone(),
                    old_path: row.old_path,
                    status: ReviewFileStatus::Conflicted,
                    layer: ReviewFileLayer::Unstaged,
                    base_spec: None,
                    content: if row.worktree_status == Some('D') {
                        ContentSource::Absent
                    } else {
                        ContentSource::Worktree(row.path.clone())
                    },
                    patch_args: Some(diff_path_args(false, &row.path, None)),
                    conflicted: true,
                });
                continue;
            }
            if let Some(status) = row.index_status.filter(|value| *value != '.') {
                let status = status_from_char(status);
                let old_path =
                    if matches!(status, ReviewFileStatus::Renamed | ReviewFileStatus::Copied) {
                        row.old_path.clone()
                    } else {
                        None
                    };
                let base_spec = if status == ReviewFileStatus::Added || fence.head.is_none() {
                    None
                } else {
                    Some(blob_spec(
                        fence.head.as_deref().unwrap_or("HEAD"),
                        old_path.as_deref().unwrap_or(&row.path),
                    ))
                };
                let content = if status == ReviewFileStatus::Deleted {
                    ContentSource::Absent
                } else {
                    ContentSource::Blob(format!(":{}", row.path))
                };
                files.push(PlannedFile {
                    path: row.path.clone(),
                    old_path,
                    status,
                    layer: ReviewFileLayer::Staged,
                    base_spec,
                    content,
                    patch_args: Some(diff_path_args(true, &row.path, row.old_path.as_deref())),
                    conflicted: false,
                });
            }
            if let Some(status) = row.worktree_status.filter(|value| *value != '.') {
                let status = status_from_char(status);
                let old_path =
                    if matches!(status, ReviewFileStatus::Renamed | ReviewFileStatus::Copied) {
                        row.old_path.clone()
                    } else {
                        None
                    };
                let base_spec = if status == ReviewFileStatus::Added {
                    None
                } else {
                    Some(blob_spec(":", old_path.as_deref().unwrap_or(&row.path)))
                };
                let content = if status == ReviewFileStatus::Deleted {
                    ContentSource::Absent
                } else {
                    ContentSource::Worktree(row.path.clone())
                };
                files.push(PlannedFile {
                    path: row.path.clone(),
                    old_path,
                    status,
                    layer: ReviewFileLayer::Unstaged,
                    base_spec,
                    content,
                    patch_args: Some(diff_path_args(false, &row.path, row.old_path.as_deref())),
                    conflicted: false,
                });
            }
        }
        files.sort_by(|left, right| {
            left.path
                .cmp(&right.path)
                .then(left.layer.cmp(&right.layer))
        });
        Ok(files)
    }

    /// Parse one immutable object comparison with NUL-delimited name-status paths and exact blob IDs.
    fn plan_comparison(
        &self,
        before: &str,
        after: &str,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<Vec<PlannedFile>, SnapshotCollectionError> {
        let output = self.success(
            &[
                "--literal-pathspecs",
                "diff",
                "--name-status",
                "-z",
                "--find-renames",
                before,
                after,
            ],
            MAX_GIT_STATUS_BYTES,
            cancellation,
            deadline,
        )?;
        let rows = parse_name_status(&output.stdout)?;
        let mut files = Vec::with_capacity(rows.len());
        for row in rows {
            check_budget(cancellation, deadline)?;
            let patch_args = Some(diff_comparison_args(
                before,
                after,
                &row.path,
                row.old_path.as_deref(),
            ));
            let old_path = row.old_path;
            let base_spec = (row.status != ReviewFileStatus::Added)
                .then(|| blob_spec(before, old_path.as_deref().unwrap_or(&row.path)));
            let content = if row.status == ReviewFileStatus::Deleted {
                ContentSource::Absent
            } else {
                ContentSource::Blob(blob_spec(after, &row.path))
            };
            files.push(PlannedFile {
                path: row.path.clone(),
                old_path,
                status: row.status,
                layer: ReviewFileLayer::Comparison,
                base_spec,
                content,
                patch_args,
                conflicted: false,
            });
        }
        Ok(files)
    }

    /// Read the mutation fence in a stable order before or after collecting mutable worktree evidence.
    fn read_fence(
        &self,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<RepositoryFence, SnapshotCollectionError> {
        let head = self.read_head(cancellation, deadline)?;
        let status = self
            .success(
                &["status", "--porcelain=v2", "-z", "--untracked-files=all"],
                MAX_GIT_STATUS_BYTES,
                cancellation,
                deadline,
            )?
            .stdout;
        let index = self
            .success(
                &["ls-files", "--stage", "-z"],
                MAX_GIT_STATUS_BYTES,
                cancellation,
                deadline,
            )?
            .stdout;
        Ok(RepositoryFence {
            mutable: true,
            head,
            status,
            index,
        })
    }

    /// Reject a snapshot if the selected Git state moved while its evidence was materialized.
    fn verify_fence(
        &self,
        expected: &RepositoryFence,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<(), SnapshotCollectionError> {
        if !expected.mutable {
            if expected.head.is_none() || self.read_head(cancellation, deadline)? == expected.head {
                return Ok(());
            }
            return Err(SnapshotCollectionError::StaleSnapshot);
        }
        let actual = self.read_fence(cancellation, deadline)?;
        if actual == *expected {
            Ok(())
        } else {
            Err(SnapshotCollectionError::StaleSnapshot)
        }
    }

    /// Return the current full HEAD object ID, treating an unborn branch as an explicit absence.
    fn read_head(
        &self,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<Option<String>, SnapshotCollectionError> {
        let output = self.run(
            &["rev-parse", "--verify", "--quiet", "HEAD^{commit}"],
            MAX_GIT_ERROR_BYTES,
            cancellation,
            deadline,
        )?;
        if !output.status.success() {
            if output.status.code() == Some(1) {
                return Ok(None);
            }
            return Err(SnapshotCollectionError::GitCommandFailed);
        }
        let value = output_text(&output.stdout)?;
        if valid_object_id(value) {
            Ok(Some(value.to_owned()))
        } else {
            Err(SnapshotCollectionError::GitCommandFailed)
        }
    }

    /// Resolve Git's canonical empty-tree object without writing an object into the user's repository.
    fn empty_tree(
        &self,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<String, SnapshotCollectionError> {
        let output = self.success(
            &["hash-object", "-t", "tree", "--stdin"],
            MAX_GIT_ERROR_BYTES,
            cancellation,
            deadline,
        )?;
        let value = output_text(&output.stdout)?;
        if valid_object_id(value) {
            Ok(value.to_owned())
        } else {
            Err(SnapshotCollectionError::GitCommandFailed)
        }
    }

    /// Select the first parent of a commit, using the canonical empty tree for a root commit.
    fn parent_or_empty_tree(
        &self,
        commit: &str,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<String, SnapshotCollectionError> {
        let output = self.success(
            &["rev-list", "--parents", "-n", "1", commit],
            MAX_GIT_ERROR_BYTES,
            cancellation,
            deadline,
        )?;
        let fields = output_text(&output.stdout)?
            .split_ascii_whitespace()
            .collect::<Vec<_>>();
        if fields.first().copied() != Some(commit) {
            return Err(SnapshotCollectionError::GitCommandFailed);
        }
        if let Some(parent) = fields.get(1) {
            if valid_object_id(parent) {
                Ok((*parent).to_owned())
            } else {
                Err(SnapshotCollectionError::GitCommandFailed)
            }
        } else {
            self.empty_tree(cancellation, deadline)
        }
    }

    /// Read one immutable Git blob and stop the child as soon as the caller's remaining byte budget is crossed.
    fn read_blob(
        &self,
        spec: &str,
        limit: usize,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<Option<Vec<u8>>, SnapshotCollectionError> {
        let output = match self.run(&["cat-file", "blob", spec], limit, cancellation, deadline) {
            Ok(output) => output,
            Err(SnapshotCollectionError::OutputLimitExceeded) => return Ok(None),
            Err(error) => return Err(error),
        };
        if !output.status.success() {
            return Err(SnapshotCollectionError::GitCommandFailed);
        }
        Ok(Some(output.stdout))
    }

    /// Execute a fixed Git argument vector and return None only when output exceeded the supplied limit.
    fn read_git(
        &self,
        args: &[String],
        limit: usize,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<Option<Vec<u8>>, SnapshotCollectionError> {
        let args = args.iter().map(String::as_str).collect::<Vec<_>>();
        match self.run(&args, limit, cancellation, deadline) {
            Ok(output) if output.status.success() => Ok(Some(output.stdout)),
            Ok(_) => Err(SnapshotCollectionError::GitCommandFailed),
            Err(SnapshotCollectionError::OutputLimitExceeded) => Ok(None),
            Err(error) => Err(error),
        }
    }

    /// Require successful fixed Git commands while keeping stderr private to the collector.
    fn success(
        &self,
        args: &[&str],
        limit: usize,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<GitOutput, SnapshotCollectionError> {
        let output = self.run(args, limit, cancellation, deadline)?;
        if output.status.success() {
            Ok(output)
        } else {
            Err(SnapshotCollectionError::GitCommandFailed)
        }
    }

    /// Start a sanitized Git process, bound both pipes, and enforce caller cancellation and absolute deadline.
    fn run(
        &self,
        args: &[&str],
        stdout_limit: usize,
        cancellation: &impl SnapshotCancellation,
        deadline: Instant,
    ) -> Result<GitOutput, SnapshotCollectionError> {
        check_budget(cancellation, deadline)?;
        let mut command = Command::new(&self.git_program);
        command
            .current_dir(&self.root)
            .env_clear()
            .args([
                "--no-optional-locks",
                "--no-pager",
                "--literal-pathspecs",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "core.quotepath=false",
                "-c",
                "diff.external=",
                "-c",
                "diff.trustExitCode=false",
            ])
            .args(args)
            .env("GIT_CONFIG_NOSYSTEM", "1")
            .env("GIT_CONFIG_GLOBAL", null_device())
            .env("GIT_CONFIG_SYSTEM", null_device())
            .env("GIT_TERMINAL_PROMPT", "0")
            .env("GIT_ASKPASS", "")
            .env("SSH_ASKPASS", "")
            .env("GCM_INTERACTIVE", "Never")
            .env("GIT_NO_REPLACE_OBJECTS", "1")
            .env("GIT_NO_LAZY_FETCH", "1")
            .env("LC_ALL", "C")
            .env("LANG", "C")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        configure_windows_process(&mut command);
        let mut child = command
            .spawn()
            .map_err(|_| SnapshotCollectionError::GitUnavailable)?;
        let stdout = child
            .stdout
            .take()
            .ok_or(SnapshotCollectionError::GitCommandFailed)?;
        let stderr = child
            .stderr
            .take()
            .ok_or(SnapshotCollectionError::GitCommandFailed)?;
        let stdout_overflow = Arc::new(AtomicBool::new(false));
        let stderr_overflow = Arc::new(AtomicBool::new(false));
        let stdout_reader = spawn_pipe_reader(stdout, stdout_limit, Arc::clone(&stdout_overflow));
        let stderr_reader =
            spawn_pipe_reader(stderr, MAX_GIT_ERROR_BYTES, Arc::clone(&stderr_overflow));
        let mut terminal_error = None;
        let status = loop {
            if cancellation.is_cancellation_requested() {
                terminal_error = Some(SnapshotCollectionError::Cancelled);
                break None;
            }
            if stdout_overflow.load(Ordering::Acquire) || stderr_overflow.load(Ordering::Acquire) {
                terminal_error = Some(SnapshotCollectionError::OutputLimitExceeded);
                break None;
            }
            match child.try_wait() {
                Ok(Some(status)) => break Some(status),
                Ok(None) => {}
                Err(_) => {
                    terminal_error = Some(SnapshotCollectionError::GitCommandFailed);
                    break None;
                }
            }
            if Instant::now() >= deadline {
                terminal_error = Some(SnapshotCollectionError::DeadlineExceeded);
                break None;
            }
            thread::park_timeout(PROCESS_POLL_INTERVAL);
        };
        if terminal_error.is_some() {
            terminate_child(&mut child);
        }
        let stdout = join_pipe_reader(stdout_reader)?;
        let stderr = join_pipe_reader(stderr_reader)?;
        if let Some(error) = terminal_error {
            return Err(error);
        }
        let status = status.ok_or(SnapshotCollectionError::GitCommandFailed)?;
        if stdout.exceeded || stderr.exceeded {
            return Err(SnapshotCollectionError::OutputLimitExceeded);
        }
        Ok(GitOutput {
            status,
            stdout: stdout.bytes,
        })
    }
}

/// Parse only valid porcelain-v2 worktree records and preserve NUL-delimited path boundaries.
fn parse_status(bytes: &[u8]) -> Result<Vec<StatusRow>, SnapshotCollectionError> {
    let mut records = bytes.split(|byte| *byte == 0).peekable();
    let mut result = Vec::new();
    while let Some(record) = records.next() {
        if record.is_empty() || record[0] == b'#' {
            continue;
        }
        match record[0] {
            b'1' => {
                let fields = split_fixed_fields(record, 9)?;
                let status = fields[1];
                result.push(StatusRow::changed(
                    utf8_path(fields[8])?,
                    None,
                    status[0],
                    status[1],
                    false,
                )?);
            }
            b'2' => {
                let fields = split_fixed_fields(record, 10)?;
                let status = fields[1];
                let old_path = records
                    .next()
                    .ok_or(SnapshotCollectionError::GitCommandFailed)?;
                result.push(StatusRow::changed(
                    utf8_path(fields[9])?,
                    Some(utf8_path(old_path)?),
                    status[0],
                    status[1],
                    false,
                )?);
            }
            b'u' => {
                let fields = split_fixed_fields(record, 11)?;
                let status = fields[1];
                result.push(StatusRow::changed(
                    utf8_path(fields[10])?,
                    None,
                    status[0],
                    status[1],
                    true,
                )?);
            }
            b'?' => result.push(StatusRow {
                path: utf8_path(
                    record
                        .get(2..)
                        .ok_or(SnapshotCollectionError::GitCommandFailed)?,
                )?,
                old_path: None,
                index_status: None,
                worktree_status: None,
                untracked: true,
                conflicted: false,
            }),
            _ => return Err(SnapshotCollectionError::GitCommandFailed),
        }
    }
    Ok(result)
}

/// One parsed status record retains status-layer identity and rename endpoints.
struct StatusRow {
    path: String,
    old_path: Option<String>,
    index_status: Option<char>,
    worktree_status: Option<char>,
    untracked: bool,
    conflicted: bool,
}

impl StatusRow {
    /// Reject malformed two-character status before it can be interpreted as a file transition.
    fn changed(
        path: String,
        old_path: Option<String>,
        index: u8,
        worktree: u8,
        conflicted: bool,
    ) -> Result<Self, SnapshotCollectionError> {
        let index = char::from(index);
        let worktree = char::from(worktree);
        if !valid_status_char(index) || !valid_status_char(worktree) {
            return Err(SnapshotCollectionError::GitCommandFailed);
        }
        Ok(Self {
            path,
            old_path,
            index_status: Some(index),
            worktree_status: Some(worktree),
            untracked: false,
            conflicted,
        })
    }
}

/// A comparison status row carries only the path transition needed by the shared snapshot envelope.
struct NameStatusRow {
    path: String,
    old_path: Option<String>,
    status: ReviewFileStatus,
}

/// Parse NUL-delimited Git name-status output, including two-path rename and copy records.
fn parse_name_status(bytes: &[u8]) -> Result<Vec<NameStatusRow>, SnapshotCollectionError> {
    let mut records = bytes.split(|byte| *byte == 0).peekable();
    let mut result = Vec::new();
    while let Some(status) = records.next() {
        if status.is_empty() {
            continue;
        }
        let kind = *status
            .first()
            .ok_or(SnapshotCollectionError::GitCommandFailed)?;
        let file_status = status_from_char(char::from(kind));
        let (old_path, path) = if matches!(
            file_status,
            ReviewFileStatus::Renamed | ReviewFileStatus::Copied
        ) {
            let old_path = records
                .next()
                .ok_or(SnapshotCollectionError::GitCommandFailed)?;
            let path = records
                .next()
                .ok_or(SnapshotCollectionError::GitCommandFailed)?;
            (Some(utf8_path(old_path)?), utf8_path(path)?)
        } else {
            let path = records
                .next()
                .ok_or(SnapshotCollectionError::GitCommandFailed)?;
            (None, utf8_path(path)?)
        };
        result.push(NameStatusRow {
            path,
            old_path,
            status: file_status,
        });
    }
    Ok(result)
}

/// Split a fixed porcelain record while leaving all remaining bytes in its final path field.
fn split_fixed_fields(record: &[u8], count: usize) -> Result<Vec<&[u8]>, SnapshotCollectionError> {
    let mut fields = Vec::with_capacity(count);
    let mut remaining = record;
    for _ in 1..count {
        let separator = remaining
            .iter()
            .position(|byte| *byte == b' ')
            .ok_or(SnapshotCollectionError::GitCommandFailed)?;
        fields.push(&remaining[..separator]);
        remaining = &remaining[separator + 1..];
    }
    fields.push(remaining);
    if fields.iter().any(|field| field.is_empty())
        || fields.get(1).is_none_or(|field| field.len() != 2)
    {
        return Err(SnapshotCollectionError::GitCommandFailed);
    }
    Ok(fields)
}

/// Decode a Git path strictly so lossy replacement cannot merge distinct repository entries.
fn utf8_path(bytes: &[u8]) -> Result<String, SnapshotCollectionError> {
    String::from_utf8(bytes.to_vec()).map_err(|_| SnapshotCollectionError::InvalidSnapshot)
}

/// Map Git's closed status alphabet into the snapshot file status vocabulary.
fn status_from_char(value: char) -> ReviewFileStatus {
    match value {
        'A' => ReviewFileStatus::Added,
        'D' => ReviewFileStatus::Deleted,
        'R' => ReviewFileStatus::Renamed,
        'C' => ReviewFileStatus::Copied,
        'U' => ReviewFileStatus::Conflicted,
        _ => ReviewFileStatus::Modified,
    }
}

/// Admit only porcelain status bytes that Git documents for XY and conflict states.
fn valid_status_char(value: char) -> bool {
    matches!(value, '.' | ' ' | 'M' | 'T' | 'A' | 'D' | 'R' | 'C' | 'U')
}

/// Build a revision:path blob operand only from a resolved revision and a validated Git path.
fn blob_spec(revision: &str, path: &str) -> String {
    if revision == ":" {
        format!(":{path}")
    } else {
        format!("{revision}:{path}")
    }
}

/// Build a literal path-scoped diff command for one mutable index or worktree layer.
fn diff_path_args(staged: bool, path: &str, old_path: Option<&str>) -> Vec<String> {
    let mut args = vec!["diff".to_owned()];
    if staged {
        args.push("--cached".to_owned());
    }
    args.extend([
        "--no-ext-diff".to_owned(),
        "--no-color".to_owned(),
        "--no-textconv".to_owned(),
        "--binary".to_owned(),
        "--find-renames".to_owned(),
        "--".to_owned(),
        path.to_owned(),
    ]);
    if let Some(old_path) = old_path.filter(|old| *old != path) {
        args.push(old_path.to_owned());
    }
    args
}

/// Build an immutable comparison patch while constraining Git to the validated transition paths.
fn diff_comparison_args(
    before: &str,
    after: &str,
    path: &str,
    old_path: Option<&str>,
) -> Vec<String> {
    let mut args = vec![
        "diff".to_owned(),
        "--no-ext-diff".to_owned(),
        "--no-color".to_owned(),
        "--no-textconv".to_owned(),
        "--binary".to_owned(),
        "--find-renames".to_owned(),
        before.to_owned(),
        after.to_owned(),
        "--".to_owned(),
        path.to_owned(),
    ];
    if let Some(old_path) = old_path.filter(|old| *old != path) {
        args.push(old_path.to_owned());
    }
    args
}

/// Read a regular worktree file only after rejecting path escapes, symlinks, and reparse points.
fn read_worktree_file(
    root: &Path,
    relative: &str,
    max_bytes: usize,
    cancellation: &impl SnapshotCancellation,
    deadline: Instant,
) -> Result<Option<Vec<u8>>, SnapshotCollectionError> {
    check_budget(cancellation, deadline)?;
    if !validate_snapshot_file_path(relative) {
        return Err(SnapshotCollectionError::InvalidSnapshot);
    }
    let candidate = root.join(relative);
    let mut current = root.to_path_buf();
    let components = Path::new(relative)
        .components()
        .filter_map(|component| match component {
            Component::Normal(value) => Some(value),
            _ => None,
        })
        .collect::<Vec<_>>();
    if components.is_empty() {
        return Err(SnapshotCollectionError::InvalidSnapshot);
    }
    for (index, component) in components.iter().enumerate() {
        current.push(component);
        let metadata =
            fs::symlink_metadata(&current).map_err(|_| SnapshotCollectionError::StaleSnapshot)?;
        if metadata_is_reparse(&metadata) {
            return Err(SnapshotCollectionError::InvalidSnapshot);
        }
        if index + 1 < components.len() && !metadata.is_dir() {
            return Err(SnapshotCollectionError::InvalidSnapshot);
        }
        if index + 1 == components.len() && !metadata.is_file() {
            return Err(SnapshotCollectionError::InvalidSnapshot);
        }
    }
    let canonical =
        fs::canonicalize(&candidate).map_err(|_| SnapshotCollectionError::StaleSnapshot)?;
    if !canonical.starts_with(root) {
        return Err(SnapshotCollectionError::InvalidSnapshot);
    }
    let mut file = File::open(&canonical).map_err(|_| SnapshotCollectionError::StaleSnapshot)?;
    let before = file
        .metadata()
        .map_err(|_| SnapshotCollectionError::StaleSnapshot)?;
    if before.len() > max_bytes as u64 {
        return Ok(None);
    }
    let mut bytes = Vec::with_capacity(before.len().min(1024 * 1024) as usize);
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        check_budget(cancellation, deadline)?;
        let count = file
            .read(&mut buffer)
            .map_err(|_| SnapshotCollectionError::StaleSnapshot)?;
        if count == 0 {
            break;
        }
        if bytes.len().saturating_add(count) > max_bytes {
            return Ok(None);
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
    let after = file
        .metadata()
        .map_err(|_| SnapshotCollectionError::StaleSnapshot)?;
    if before.len() != after.len() || bytes.len() as u64 != after.len() {
        return Err(SnapshotCollectionError::StaleSnapshot);
    }
    Ok(Some(bytes))
}

/// Check every snapshot path before either filesystem IO or subprocess pathspec construction.
fn validate_snapshot_file_path(path: &str) -> bool {
    validate_relative_path(path).is_ok()
}

/// Detect Windows reparse points in addition to portable symlink metadata.
fn metadata_is_reparse(metadata: &fs::Metadata) -> bool {
    #[cfg(windows)]
    {
        use std::os::windows::fs::MetadataExt;
        const FILE_ATTRIBUTE_REPARSE_POINT: u32 = 0x0400;
        metadata.is_symlink() || metadata.file_attributes() & FILE_ATTRIBUTE_REPARSE_POINT != 0
    }
    #[cfg(not(windows))]
    {
        metadata.is_symlink()
    }
}

/// Find and canonicalize Git before child environments are cleared.
fn resolve_git_program() -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    let names: &[&str] = if cfg!(windows) {
        &["git.exe", "git"]
    } else {
        &["git"]
    };
    for directory in std::env::split_paths(&path) {
        for name in names {
            let candidate = directory.join(name);
            if candidate.is_file()
                && let Ok(canonical) = candidate.canonicalize()
            {
                return Some(canonical);
            }
        }
    }
    None
}

/// Read one pipe on a worker so a full stderr stream cannot block the Git process supervisor.
fn spawn_pipe_reader<R: Read + Send + 'static>(
    mut reader: R,
    limit: usize,
    overflow: Arc<AtomicBool>,
) -> JoinHandle<PipeOutput> {
    thread::spawn(move || {
        let mut bytes = Vec::with_capacity(limit.min(64 * 1024));
        let mut exceeded = false;
        let mut buffer = [0_u8; 16 * 1024];
        while let Ok(count) = reader.read(&mut buffer) {
            if count == 0 {
                break;
            }
            let available = limit.saturating_sub(bytes.len());
            bytes.extend_from_slice(&buffer[..count.min(available)]);
            if count > available {
                exceeded = true;
                overflow.store(true, Ordering::Release);
                break;
            }
        }
        PipeOutput { bytes, exceeded }
    })
}

/// Join a pipe reader after process completion and convert panics into a stable collection failure.
fn join_pipe_reader(reader: JoinHandle<PipeOutput>) -> Result<PipeOutput, SnapshotCollectionError> {
    reader
        .join()
        .map_err(|_| SnapshotCollectionError::GitCommandFailed)
}

/// Force-stop the direct Git child after cancellation, deadline, or wait failure.
fn terminate_child(child: &mut Child) {
    let _ = child.kill();
    let _ = child.wait();
}

/// Check caller cancellation before deadline so explicit cancellation retains its own result.
fn check_budget(
    cancellation: &impl SnapshotCancellation,
    deadline: Instant,
) -> Result<(), SnapshotCollectionError> {
    if cancellation.is_cancellation_requested() {
        return Err(SnapshotCollectionError::Cancelled);
    }
    if Instant::now() >= deadline {
        return Err(SnapshotCollectionError::DeadlineExceeded);
    }
    Ok(())
}

/// Hash only source identities, never raw paths or command output, into the builder's source revision.
fn source_revision(parts: &[&[u8]]) -> String {
    let mut digest = Sha256::new();
    for part in parts {
        digest.update((part.len() as u64).to_le_bytes());
        digest.update(part);
    }
    let bytes = digest.finalize();
    let mut result = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        use std::fmt::Write as _;
        let _ = write!(result, "{byte:02x}");
    }
    result
}

/// Require Git text output to be strict UTF-8 and trim only its protocol line ending.
fn output_text(bytes: &[u8]) -> Result<&str, SnapshotCollectionError> {
    let value =
        std::str::from_utf8(bytes).map_err(|_| SnapshotCollectionError::GitCommandFailed)?;
    Ok(value.trim_end_matches(['\r', '\n']))
}

/// Canonicalize a Git-reported path before comparing it with the requested root.
fn output_path(bytes: &[u8]) -> Result<PathBuf, SnapshotCollectionError> {
    let text = output_text(bytes)?;
    fs::canonicalize(text).map_err(|_| SnapshotCollectionError::InvalidRepository)
}

/// Accept only full lowercase SHA-1 or SHA-256 object IDs returned by Git.
fn valid_object_id(value: &str) -> bool {
    matches!(value.len(), 40 | 64)
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

/// Select the platform null device used to suppress user and system Git configuration.
fn null_device() -> &'static str {
    if cfg!(windows) { "NUL" } else { "/dev/null" }
}

/// Suppress the Windows console window for short-lived native Git review queries.
#[cfg(windows)]
fn configure_windows_process(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    command.creation_flags(CREATE_NO_WINDOW);
}

/// Keep the process builder portable while applying no platform-specific flags elsewhere.
#[cfg(not(windows))]
fn configure_windows_process(_command: &mut Command) {}
