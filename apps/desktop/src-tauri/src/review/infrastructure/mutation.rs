// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review stage、unstage、revert 的事务编排与 selection 解析。

use super::git::{CancellationToken, DiffOptions};
use super::native::{NativeReviewAdapter, map_mutation_error};
use super::transaction::{
    GitIndexTransaction, WorktreeRecovery, action_mutates_index, action_mutates_worktree,
    apply_patch_file, check_patch_file, map_worktree_patch_failure, required_index,
    run_review_command, selected_path_args,
};
use crate::review::application::ReviewError;
use crate::review::domain::{
    ReviewAction, ReviewFile, ReviewFileLayer, ReviewFileStatus, ReviewRevision, ReviewSnapshot,
    ReviewSource, ReviewTarget,
};
use crate::workspace::{PathMutationQueue, resolve_relative};
use std::collections::BTreeSet;
use std::ffi::OsString;
use std::fs;
use std::path::Path;

impl NativeReviewAdapter {
    /// 在统一路径锁内执行 Git/index/worktree 事务，并在 commit 后重新物化权威快照。
    pub(super) fn execute_apply_transaction(
        &self,
        source: &ReviewSource,
        revision: &ReviewRevision,
        action: ReviewAction,
        target: &ReviewTarget,
        initial: &ReviewSnapshot,
        cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError> {
        // 锁集合只能来自 application 刚验收的同 source/revision snapshot；不一致时不能
        // 继续解析 path，否则内部误接线也可能锁错资源。
        if initial.source != *source || &initial.revision != revision {
            return Err(ReviewError::ReviewStale);
        }
        let paths = selected_paths(&initial.files, target)?;
        let index_path = action_mutates_index(source, action)
            .then(|| self.git.review_index_path(cancellation))
            .transpose()?;
        let mut lock_paths = paths
            .iter()
            .map(|path| {
                resolve_relative(self.workspace.root_path(), path).map_err(map_mutation_error)
            })
            .collect::<Result<Vec<_>, _>>()?;
        if let Some(index_path) = index_path.as_ref() {
            lock_paths.push(index_path.clone());
        }
        PathMutationQueue::global().with_paths(&lock_paths, || {
            // 锁内重新物化并执行第二次 revision CAS，封闭锁外 snapshot 与真实写入间的竞态。
            let mut current = self.materialize(source, cancellation)?;
            if current.revision != revision.as_str() {
                return Err(ReviewError::ReviewStale);
            }
            if let ReviewTarget::Hunk { file_id, .. } = target {
                let index = current
                    .files
                    .iter()
                    .position(|file| file.file_id == *file_id)
                    .ok_or(ReviewError::InvalidInput)?;
                let materialized =
                    self.materialize_one_file(source, &current.files[index], cancellation)?;
                // path-specific patch 读取后再次重算 strong revision；外部写入不遵守 Ja 路径锁，
                // 因此不能仅依赖锁前 CAS。
                if self.probe_revision(source, cancellation)? != revision.as_str() {
                    return Err(ReviewError::ReviewStale);
                }
                current.files[index] = materialized;
            }
            let selected = select_mutations(&current.files, target)?;
            let mut recovery = action_mutates_worktree(source, action)
                .then(|| WorktreeRecovery::capture(&self.workspace, &selected))
                .transpose()?;
            let mut index = index_path
                .as_ref()
                .map(|path| GitIndexTransaction::begin(&self.git, path, cancellation))
                .transpose()?;
            let mutation = self.apply_selected(
                source,
                action,
                &selected,
                index.as_ref().map(GitIndexTransaction::temporary_path),
                cancellation,
            );
            if let Err(error) = mutation {
                // mutation 失败时先恢复 worktree；恢复失败比原始 Git 错误更严重，统一上报 IO。
                if recovery
                    .as_mut()
                    .is_some_and(|value| value.rollback().is_err())
                {
                    return Err(ReviewError::Io);
                }
                return Err(error);
            }
            if let Some(index) = index.as_mut()
                && let Err(error) = index.commit(cancellation)
            {
                // index commit CAS 失败时 worktree 也必须回到 preimage，不能留下半事务结果。
                if recovery
                    .as_mut()
                    .is_some_and(|value| value.rollback().is_err())
                {
                    return Err(ReviewError::Io);
                }
                return Err(error);
            }
            if let Some(recovery) = recovery.as_mut() {
                // 只有 index publish 成功后，才解除 Drop 自动回滚。
                recovery.commit();
            }
            let after = self.materialize(source, cancellation)?;
            let snapshot = ReviewSnapshot {
                revision: ReviewRevision::parse(after.revision).map_err(|_| ReviewError::Parse)?,
                source: source.clone(),
                files: after.files,
                stats: after.stats,
            };
            super::snapshot_cache::remember(self.workspace.id(), &snapshot);
            Ok(snapshot)
        })
    }

    /// 只按已验证的来源与动作路由固定 Git 参数；单独未暂存撤销沿用工作树入口，
    /// 聚合来源由外层事务传递临时 index，原生删除仅用于已校验的未跟踪文件。
    pub(super) fn apply_selected(
        &self,
        source: &ReviewSource,
        action: ReviewAction,
        selected: &[SelectedMutation],
        index: Option<&Path>,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        if cancellation.is_cancelled() {
            return Err(ReviewError::Cancelled);
        }
        match (source, action) {
            (ReviewSource::Uncommitted, action) => {
                self.apply_uncommitted(action, selected, index, cancellation)
            }
            (ReviewSource::Unstaged, ReviewAction::Stage) => {
                self.stage(selected, required_index(index)?, cancellation)
            }
            (ReviewSource::Staged, ReviewAction::Unstage) => {
                self.unstage(selected, required_index(index)?, cancellation)
            }
            (ReviewSource::Unstaged, ReviewAction::Revert) => {
                self.revert_unstaged(selected, None, cancellation)
            }
            (ReviewSource::Staged, ReviewAction::Revert) => {
                self.revert_staged(selected, required_index(index)?, cancellation)
            }
            _ => Err(ReviewError::ReadOnlySource),
        }
    }

    /// 聚合来源按文件层路由既有事务原语；涉及 index 的 Git 调用共用外层临时 index，
    /// 防止工作树恢复刷新真实 index 后触发事务自身的 CAS 冲突。
    fn apply_uncommitted(
        &self,
        action: ReviewAction,
        selected: &[SelectedMutation],
        index: Option<&Path>,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        let staged = selected
            .iter()
            .filter(|selection| selection.layer == ReviewFileLayer::Staged)
            .cloned()
            .collect::<Vec<_>>();
        let unstaged = selected
            .iter()
            .filter(|selection| {
                matches!(
                    selection.layer,
                    ReviewFileLayer::Unstaged | ReviewFileLayer::Untracked
                )
            })
            .cloned()
            .collect::<Vec<_>>();
        match action {
            ReviewAction::Stage if !unstaged.is_empty() => {
                self.stage(&unstaged, required_index(index)?, cancellation)
            }
            ReviewAction::Unstage if !staged.is_empty() => {
                self.unstage(&staged, required_index(index)?, cancellation)
            }
            ReviewAction::Revert if !staged.is_empty() || !unstaged.is_empty() => {
                // 先消除 worktree 层，随后 staged revert 的 overlap 检查才不会把同路径的
                // 已选未暂存变更当成外部竞态；任一步失败仍由外层 recovery/temporary index 回滚。
                if !unstaged.is_empty() {
                    self.revert_unstaged(&unstaged, Some(required_index(index)?), cancellation)?;
                }
                if !staged.is_empty() {
                    self.revert_staged(&staged, required_index(index)?, cancellation)?;
                }
                Ok(())
            }
            _ => Err(ReviewError::ReadOnlySource),
        }
    }

    /// 将 selected file 或精确 hunk 写入 temporary index；path 只来自当前 snapshot。
    pub(super) fn stage(
        &self,
        selected: &[SelectedMutation],
        index: &Path,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        let files = selected
            .iter()
            .filter(|selection| selection.hunk.is_none())
            .collect::<Vec<_>>();
        if !files.is_empty() {
            for selection in files {
                self.stage_path(selection, index, cancellation)?;
            }
        }
        for selection in selected.iter().filter(|selection| selection.hunk.is_some()) {
            let patch = selection.hunk.as_deref().ok_or(ReviewError::InvalidInput)?;
            apply_patch_file(&self.git, patch, true, false, Some(index), cancellation)?;
        }
        Ok(())
    }

    /// 移除 selected index change，同时保留 worktree bytes。
    pub(super) fn unstage(
        &self,
        selected: &[SelectedMutation],
        index: &Path,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        let files = selected
            .iter()
            .filter(|selection| selection.hunk.is_none())
            .collect::<Vec<_>>();
        if !files.is_empty() {
            let mut args = vec![
                OsString::from("restore"),
                OsString::from("--staged"),
                OsString::from("--"),
            ];
            args.extend(selected_path_args(&files));
            self.git
                .run_review_command_with_index(&args, index, cancellation)?;
        }
        for selection in selected.iter().filter(|selection| selection.hunk.is_some()) {
            let patch = selection.hunk.as_deref().ok_or(ReviewError::InvalidInput)?;
            apply_patch_file(&self.git, patch, true, true, Some(index), cancellation)?;
        }
        Ok(())
    }

    /// 文件恢复沿用调用方的 index 绑定，使聚合事务隔离 Git 的元数据刷新；
    /// 区块恢复与已校验未跟踪文件的删除保持各自原有的工作树语义。
    pub(super) fn revert_unstaged(
        &self,
        selected: &[SelectedMutation],
        index: Option<&Path>,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        let files = selected
            .iter()
            .filter(|selection| {
                selection.hunk.is_none() && selection.status != ReviewFileStatus::Untracked
            })
            .collect::<Vec<_>>();
        if !files.is_empty() {
            for selection in files {
                self.restore_worktree_selection(selection, index, cancellation)?;
            }
        }
        let hunk_selections = selected
            .iter()
            .filter(|selection| selection.hunk.is_some())
            .collect::<Vec<_>>();
        for selection in hunk_selections {
            let patch = selection.hunk.as_deref().ok_or(ReviewError::InvalidInput)?;
            apply_patch_file(&self.git, patch, false, true, None, cancellation)?;
        }
        for selection in selected.iter().filter(|selection| {
            selection.hunk.is_none() && selection.status == ReviewFileStatus::Untracked
        }) {
            let path = self.workspace.resolve_file(&selection.path)?;
            fs::remove_file(path).map_err(|_| ReviewError::Io)?;
        }
        Ok(())
    }

    /// 通过固定 Git command surface stage 单一路径。
    pub(super) fn stage_path(
        &self,
        selection: &SelectedMutation,
        index: &Path,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        let mut args = vec![
            OsString::from("add"),
            OsString::from("--all"),
            OsString::from("--"),
        ];
        args.extend(selected_path_args(&[selection]));
        self.git
            .run_review_command_with_index(&args, index, cancellation)?;
        Ok(())
    }

    /// 文件恢复使用事务提供的临时 index 读取基线并承接 Git 的刷新，真实 index 仍由
    /// 外层 CAS 提交；未提供绑定时沿用单独工作树入口，重命名仍覆盖已验证的两侧路径。
    pub(super) fn restore_worktree_selection(
        &self,
        selection: &SelectedMutation,
        index: Option<&Path>,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        let mut args = vec![
            OsString::from("restore"),
            OsString::from("--worktree"),
            OsString::from("--"),
        ];
        args.extend(selected_path_args(&[selection]));
        run_review_command(&self.git, &args, index, cancellation)?;
        Ok(())
    }

    /// 同路径无独立 worktree delta 时才 revert staged file；hunk 则组合 index/worktree inverse patch，
    /// 以保留不重叠的用户编辑。
    pub(super) fn revert_staged(
        &self,
        selected: &[SelectedMutation],
        index: &Path,
        cancellation: &CancellationToken,
    ) -> Result<(), ReviewError> {
        let hunk_selections = selected
            .iter()
            .filter(|selection| selection.hunk.is_some())
            .collect::<Vec<_>>();
        if !hunk_selections.is_empty() {
            let mut patches = Vec::with_capacity(hunk_selections.len());
            // staged hunk 是 compound operation：变更任一侧前，inverse 必须同时通过 temporary
            // index 与 current worktree；Git context check 用于检测独立 unstaged edit overlap。
            for selection in hunk_selections {
                let patch = selection.hunk.as_deref().ok_or(ReviewError::InvalidInput)?;
                check_patch_file(&self.git, patch, true, true, Some(index), cancellation)?;
                check_patch_file(&self.git, patch, false, true, None, cancellation)
                    .map_err(map_worktree_patch_failure)?;
                patches.push(patch.to_vec());
            }
            // 先 mutation temporary index，再 mutation worktree；此后失败由 WorktreeRecovery
            // 恢复，index transaction 在最终 atomic swap 前不会 publish。
            for patch in &patches {
                apply_patch_file(&self.git, patch, true, true, Some(index), cancellation)?;
            }
            for patch in &patches {
                apply_patch_file(&self.git, patch, false, true, None, cancellation)?;
            }
            return Ok(());
        }
        for selection in selected.iter().filter(|selection| selection.hunk.is_none()) {
            if selection.status == ReviewFileStatus::Untracked {
                continue;
            }
            let mut paths = vec![selection.path.as_str()];
            if let Some(old_path) = selection.old_path.as_deref() {
                paths.push(old_path);
            }
            for path in paths {
                let diff = self.git.diff(
                    &DiffOptions {
                        staged: false,
                        relative_path: Some(path.to_owned()),
                    },
                    cancellation,
                )?;
                if !diff.bytes.is_empty() {
                    return Err(ReviewError::Conflict);
                }
            }
        }
        let files = selected
            .iter()
            .filter(|selection| selection.hunk.is_none())
            .collect::<Vec<_>>();
        if !files.is_empty() {
            let mut args = vec![
                OsString::from("restore"),
                OsString::from("--staged"),
                OsString::from("--worktree"),
                OsString::from("--"),
            ];
            args.extend(selected_path_args(&files));
            self.git
                .run_review_command_with_index(&args, index, cancellation)?;
        }
        for selection in selected.iter().filter(|selection| selection.hunk.is_some()) {
            let patch = selection.hunk.as_deref().ok_or(ReviewError::InvalidInput)?;
            apply_patch_file(&self.git, patch, true, true, Some(index), cancellation)?;
        }
        Ok(())
    }

    /// workspace-relative path 成为 Git 参数或 filesystem lookup 前完成 containment 校验。
    pub(super) fn validate_relative_path(&self, path: &str) -> Result<(), ReviewError> {
        self.workspace
            .validate_git_path(path)
            .map_err(ReviewError::from)
    }

    /// 生成固定 Git argv 使用的已校验路径拼写。
    pub(super) fn validated_path_arg(&self, path: &str) -> Result<String, ReviewError> {
        self.validate_relative_path(path)?;
        Ok(path.to_owned())
    }
}

/// opaque id 针对当前 materialized snapshot 解析后的 selected file/hunk。
#[derive(Debug, Clone)]
pub(super) struct SelectedMutation {
    pub(super) path: String,
    pub(super) old_path: Option<String>,
    pub(super) layer: ReviewFileLayer,
    pub(super) status: ReviewFileStatus,
    pub(super) hunk: Option<Vec<u8>>,
}

/// 为 lock acquisition 选择路径；target id 未校验前不返回 native patch data。
fn selected_paths(
    files: &[ReviewFile],
    target: &ReviewTarget,
) -> Result<BTreeSet<String>, ReviewError> {
    let mut paths = BTreeSet::new();
    match target {
        ReviewTarget::All => {
            for file in files {
                paths.insert(file.path.clone());
                if let Some(old_path) = file.old_path.as_deref() {
                    paths.insert(old_path.to_owned());
                }
            }
        }
        ReviewTarget::File { file_id } | ReviewTarget::Hunk { file_id, .. } => {
            let file = files
                .iter()
                .find(|file| file.file_id == *file_id)
                .ok_or(ReviewError::InvalidInput)?;
            paths.insert(file.path.clone());
            if let Some(old_path) = file.old_path.as_deref() {
                paths.insert(old_path.to_owned());
            }
        }
    }
    if paths.is_empty() {
        return Err(ReviewError::InvalidInput);
    }
    Ok(paths)
}

/// 将 opaque file/hunk id 解析为 native-only mutation selection。
fn select_mutations(
    files: &[ReviewFile],
    target: &ReviewTarget,
) -> Result<Vec<SelectedMutation>, ReviewError> {
    match target {
        ReviewTarget::All => Ok(files
            .iter()
            .map(|file| SelectedMutation {
                path: file.path.clone(),
                old_path: file.old_path.clone(),
                layer: file.layer,
                status: file.status,
                hunk: None,
            })
            .collect()),
        ReviewTarget::File { file_id } => {
            let file = files
                .iter()
                .find(|file| file.file_id == *file_id)
                .ok_or(ReviewError::InvalidInput)?;
            Ok(vec![SelectedMutation {
                path: file.path.clone(),
                old_path: file.old_path.clone(),
                layer: file.layer,
                status: file.status,
                hunk: None,
            }])
        }
        ReviewTarget::Hunk { file_id, hunk_id } => {
            let file = files
                .iter()
                .find(|file| file.file_id == *file_id)
                .ok_or(ReviewError::InvalidInput)?;
            let hunk = file
                .hunks
                .iter()
                .find(|hunk| hunk.hunk_id == *hunk_id)
                .ok_or(ReviewError::InvalidInput)?;
            Ok(vec![SelectedMutation {
                path: file.path.clone(),
                old_path: file.old_path.clone(),
                layer: file.layer,
                status: file.status,
                hunk: Some(hunk.raw_patch.clone()),
            }])
        }
    }
}
