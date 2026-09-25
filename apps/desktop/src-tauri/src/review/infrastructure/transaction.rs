// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 的临时 index、patch 执行与 worktree 恢复事务。
//
// `NativeReviewAdapter` 仍是唯一 façade 和 mutation owner；本模块只封装必须共同生存的
// 临时资源与回滚机制，避免 Git 查询/物化代码同时承担提交和恢复细节。

use super::git::{CancellationToken, GitError, GitReadOnly};
use super::mutation::SelectedMutation;
use crate::review::application::ReviewError;
use crate::review::domain::{MAX_REVIEW_DIFF_BYTES, ReviewAction, ReviewSource};
use crate::workspace::{WorkspaceHandle, is_reparse_point, replace_atomically, resolve_relative};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::ffi::OsString;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use uuid::Uuid;

const MAX_REVIEW_RECOVERY_BYTES: u64 = 512 * 1024 * 1024;

/// hardened Git context check 通过后应用有界 patch；wrapper 防止 caller 跳过 check。
pub(super) fn apply_patch_file(
    git: &GitReadOnly,
    patch: &[u8],
    cached: bool,
    reverse: bool,
    index: Option<&Path>,
    cancellation: &CancellationToken,
) -> Result<(), ReviewError> {
    run_patch_file(git, patch, cached, reverse, index, cancellation, true)
}

/// 不改变 temporary index/worktree 地检查 patch；staged hunk revert 借此提前拒绝 overlap。
pub(super) fn check_patch_file(
    git: &GitReadOnly,
    patch: &[u8],
    cached: bool,
    reverse: bool,
    index: Option<&Path>,
    cancellation: &CancellationToken,
) -> Result<(), ReviewError> {
    run_patch_file(git, patch, cached, reverse, index, cancellation, false)
}

/// 使用 create-new 临时 patch，先 check 再 apply，并在所有终态删除 native-owned 文件。
fn run_patch_file(
    git: &GitReadOnly,
    patch: &[u8],
    cached: bool,
    reverse: bool,
    index: Option<&Path>,
    cancellation: &CancellationToken,
    apply: bool,
) -> Result<(), ReviewError> {
    if patch.is_empty() || patch.len() > MAX_REVIEW_DIFF_BYTES {
        return Err(ReviewError::InvalidInput);
    }
    let path = std::env::temp_dir().join(format!("ja-review-{}.patch", Uuid::new_v4()));
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .map_err(|_| ReviewError::Io)?;
    file.write_all(patch).map_err(|_| ReviewError::Io)?;
    file.flush().map_err(|_| ReviewError::Io)?;
    let mut args = vec![
        OsString::from("apply"),
        OsString::from("--recount"),
        OsString::from("--whitespace=nowarn"),
    ];
    if cached {
        args.push(OsString::from("--cached"));
    }
    if reverse {
        args.push(OsString::from("--reverse"));
    }
    args.push(path.as_os_str().to_owned());
    let mut check_args = args.clone();
    check_args.insert(1, OsString::from("--check"));
    let result = run_review_command(git, &check_args, index, cancellation)
        .and_then(|_| {
            if apply {
                run_review_command(git, &args, index, cancellation)
            } else {
                Ok(Vec::new())
            }
        })
        .map(|_| ())
        .map_err(ReviewError::from);
    let _ = fs::remove_file(path);
    result
}

/// 将 Git worktree context failure 映射为 Review conflict；cancel/IO/policy 保留原语义。
pub(super) fn map_worktree_patch_failure(error: ReviewError) -> ReviewError {
    match error {
        ReviewError::NativeCommandFailed => ReviewError::Conflict,
        other => other,
    }
}

/// 涉及 index state 时一律路由到 temporary index；worktree-only 保留 hardened default env。
pub(super) fn run_review_command(
    git: &GitReadOnly,
    args: &[OsString],
    index: Option<&Path>,
    cancellation: &CancellationToken,
) -> Result<Vec<u8>, GitError> {
    match index {
        Some(index) => git.run_review_command_with_index(args, index, cancellation),
        None => git.run_review_command(args, cancellation),
    }
}

/// 返回 index mutation 必需的 temporary index；缺失时失败，不回退 real index。
pub(super) fn required_index(index: Option<&Path>) -> Result<&Path, ReviewError> {
    index.ok_or(ReviewError::Io)
}

/// 识别必须先在 temporary index 准备、全部验证成功后才 publish 的 action。
pub(super) fn action_mutates_index(source: &ReviewSource, action: ReviewAction) -> bool {
    matches!(
        (source, action),
        (ReviewSource::Uncommitted, _)
            | (ReviewSource::Unstaged, ReviewAction::Stage)
            | (
                ReviewSource::Staged,
                ReviewAction::Unstage | ReviewAction::Revert
            )
    )
}

/// 识别需要 exact preimage 才能在失败时 rollback 的 destructive worktree operation。
pub(super) fn action_mutates_worktree(source: &ReviewSource, action: ReviewAction) -> bool {
    action == ReviewAction::Revert
        && matches!(
            source,
            ReviewSource::Uncommitted | ReviewSource::Unstaged | ReviewSource::Staged
        )
}

/// 将 file selection 展开到 rename 两侧并去重 fixed argv，不暴露 raw pathspec 构造。
pub(super) fn selected_path_args(selected: &[&SelectedMutation]) -> Vec<OsString> {
    let mut paths = BTreeSet::new();
    for selection in selected {
        paths.insert(selection.path.as_str());
        if let Some(old_path) = selection.old_path.as_deref() {
            paths.insert(old_path);
        }
    }
    paths.into_iter().map(OsString::from).collect()
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum IndexFingerprint {
    Missing,
    Present { bytes: u64, sha256: [u8; 32] },
}

/// 捕获 exact index identity 而不在内存保留 bytes；temporary index 是有界 preparation copy。
fn index_fingerprint(path: &Path) -> Result<IndexFingerprint, ReviewError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(IndexFingerprint::Missing);
        }
        Err(_) => return Err(ReviewError::Io),
    };
    if !metadata.is_file() || metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
        return Err(ReviewError::Io);
    }
    let mut file = File::open(path).map_err(|_| ReviewError::Io)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer).map_err(|_| ReviewError::Io)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(IndexFingerprint::Present {
        bytes: metadata.len(),
        sha256: hasher.finalize().into(),
    })
}

/// 通过 write-capable handle flush native-owned file，因为 Windows 拒绝只读 handle。
fn sync_owned_file(path: &Path) -> Result<(), ReviewError> {
    OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .and_then(|file| file.sync_all())
        .map_err(|_| ReviewError::Io)
}

/// Git 将 `GIT_INDEX_FILE` 的互斥文件命名为原路径追加 `.lock`；集中派生可避免 with_extension
/// 把 `.tmp` 错换成 `.lock`，从而遗漏真实 companion lock。
fn index_lock_path(index: &Path) -> PathBuf {
    let mut value = index.as_os_str().to_os_string();
    value.push(".lock");
    PathBuf::from(value)
}

/// 回收 Ja 唯一拥有的 unpublished temporary index 及 Git companion lock；清理是 best-effort，
/// 原始 mutation/capture 失败仍由调用链保留，不能被删除错误覆盖。
fn remove_temporary_index(index: &Path) {
    let _ = fs::remove_file(index_lock_path(index));
    let _ = fs::remove_file(index);
}

/// 拥有 same-directory temporary Git index，仅 real index identity 未变时原子 publish。
pub(super) struct GitIndexTransaction {
    real: PathBuf,
    temporary: PathBuf,
    before: IndexFingerprint,
    committed: bool,
}

impl GitIndexTransaction {
    /// mutation 前复制 admitted real index，unborn repository 则初始化唯一 temporary index。
    pub(super) fn begin(
        git: &GitReadOnly,
        real: &Path,
        cancellation: &CancellationToken,
    ) -> Result<Self, ReviewError> {
        let before = index_fingerprint(real)?;
        let parent = real.parent().ok_or(ReviewError::Io)?;
        let temporary = parent.join(format!(".ja-review-index-{}.tmp", Uuid::new_v4()));
        let reservation = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
            .map_err(|_| ReviewError::Io)?;
        drop(reservation);
        let prepared = match &before {
            IndexFingerprint::Present { .. } => fs::copy(real, &temporary)
                .map(|_| ())
                .map_err(|_| ReviewError::Io),
            IndexFingerprint::Missing => {
                fs::remove_file(&temporary).map_err(|_| ReviewError::Io)?;
                let head = git.run_review_command(
                    &[
                        OsString::from("rev-parse"),
                        OsString::from("--verify"),
                        OsString::from("HEAD"),
                    ],
                    cancellation,
                );
                let args = match head {
                    Ok(_) => vec![OsString::from("read-tree"), OsString::from("HEAD")],
                    Err(GitError::CommandFailed { .. }) => {
                        vec![OsString::from("read-tree"), OsString::from("--empty")]
                    }
                    Err(error) => return Err(error.into()),
                };
                git.run_review_command_with_index(&args, &temporary, cancellation)
                    .map(|_| ())
                    .map_err(ReviewError::from)
            }
        };
        if let Err(error) = prepared {
            remove_temporary_index(&temporary);
            return Err(error);
        }
        if let Err(error) = sync_owned_file(&temporary) {
            remove_temporary_index(&temporary);
            return Err(error);
        }
        Ok(Self {
            real: real.to_path_buf(),
            temporary,
            before,
            committed: false,
        })
    }

    /// 返回 mutation helper 唯一允许传给 Git 的 index path。
    pub(super) fn temporary_path(&self) -> &Path {
        &self.temporary
    }

    /// atomic swap 前立即验证 external index drift；此前 cancel 保证 real index 未改变。
    pub(super) fn commit(&mut self, cancellation: &CancellationToken) -> Result<(), ReviewError> {
        if cancellation.is_cancelled() {
            return Err(ReviewError::Cancelled);
        }
        sync_owned_file(&self.temporary)?;
        if index_fingerprint(&self.real)? != self.before {
            return Err(ReviewError::ReviewStale);
        }
        replace_atomically(&self.temporary, &self.real)?;
        self.committed = true;
        Ok(())
    }
}

impl Drop for GitIndexTransaction {
    /// 仅移除 native-owned unpublished index；committed swap 已把临时路径移入权威位置。
    fn drop(&mut self) {
        if !self.committed {
            remove_temporary_index(&self.temporary);
        }
    }
}

struct WorktreeRecoveryEntry {
    target: PathBuf,
    backup: Option<PathBuf>,
    permissions: Option<fs::Permissions>,
}

/// 在 repository 外保存 exact worktree preimage，避免失败 mutation 留下部分 revert。
pub(super) struct WorktreeRecovery {
    directory: PathBuf,
    entries: Vec<WorktreeRecoveryEntry>,
    active: bool,
}

impl WorktreeRecovery {
    /// 首次 destructive operation 前，在总 byte budget 内捕获所有 selected path 与 rename 两侧。
    pub(super) fn capture(
        workspace: &WorkspaceHandle,
        selected: &[SelectedMutation],
    ) -> Result<Self, ReviewError> {
        let directory = std::env::temp_dir().join(format!("ja-review-recovery-{}", Uuid::new_v4()));
        fs::create_dir(&directory).map_err(|_| ReviewError::Io)?;
        let result = (|| {
            let mut paths = BTreeSet::new();
            for selection in selected {
                paths.insert(selection.path.as_str());
                if let Some(old_path) = selection.old_path.as_deref() {
                    paths.insert(old_path);
                }
            }
            let mut total = 0_u64;
            let mut entries = Vec::with_capacity(paths.len());
            for (index, relative) in paths.into_iter().enumerate() {
                let target = resolve_relative(workspace.root_path(), relative)?;
                let metadata = match fs::symlink_metadata(&target) {
                    Ok(metadata) => Some(metadata),
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => None,
                    Err(_) => return Err(ReviewError::Io),
                };
                let permissions = metadata.as_ref().map(fs::Metadata::permissions);
                let backup = if let Some(metadata) = metadata {
                    if !metadata.is_file()
                        || metadata.file_type().is_symlink()
                        || is_reparse_point(&metadata)
                    {
                        return Err(ReviewError::Io);
                    }
                    total = total
                        .checked_add(metadata.len())
                        .ok_or(ReviewError::OutputLimitExceeded)?;
                    if total > MAX_REVIEW_RECOVERY_BYTES {
                        return Err(ReviewError::OutputLimitExceeded);
                    }
                    let backup = directory.join(format!("{index}.bak"));
                    fs::copy(&target, &backup).map_err(|_| ReviewError::Io)?;
                    sync_owned_file(&backup)?;
                    Some(backup)
                } else {
                    None
                };
                entries.push(WorktreeRecoveryEntry {
                    target,
                    backup,
                    permissions,
                });
            }
            Ok(entries)
        })();
        match result {
            Ok(entries) => Ok(Self {
                directory,
                entries,
                active: true,
            }),
            Err(error) => {
                let _ = fs::remove_dir_all(directory);
                Err(error)
            }
        }
    }

    /// 逆序使用共享 atomic replace 恢复 preimage；absent preimage 删除新文件。
    pub(super) fn rollback(&mut self) -> Result<(), ReviewError> {
        for entry in self.entries.iter().rev() {
            if let Some(backup) = entry.backup.as_ref() {
                let parent = entry.target.parent().ok_or(ReviewError::Io)?;
                let temporary = parent.join(format!(".ja-review-rollback-{}.tmp", Uuid::new_v4()));
                fs::copy(backup, &temporary).map_err(|_| ReviewError::Io)?;
                if let Some(permissions) = entry.permissions.as_ref() {
                    fs::set_permissions(&temporary, permissions.clone())
                        .map_err(|_| ReviewError::Io)?;
                }
                sync_owned_file(&temporary)?;
                if let Err(error) = replace_atomically(&temporary, &entry.target) {
                    let _ = fs::remove_file(temporary);
                    return Err(error.into());
                }
            } else {
                match fs::remove_file(&entry.target) {
                    Ok(()) => {}
                    Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                    Err(_) => return Err(ReviewError::Io),
                }
            }
        }
        self.active = false;
        let _ = fs::remove_dir_all(&self.directory);
        Ok(())
    }

    /// 仅在全部 native mutation 与 index publish 成功后丢弃 preimage。
    pub(super) fn commit(&mut self) {
        self.active = false;
        let _ = fs::remove_dir_all(&self.directory);
    }
}

impl Drop for WorktreeRecovery {
    /// success/完整 rollback 后删除 recovery data；rollback 失败则保留供显式诊断。
    fn drop(&mut self) {
        if !self.active {
            let _ = fs::remove_dir_all(&self.directory);
        }
    }
}
