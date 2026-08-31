// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 原生能力 façade。
//
// 该类型只组合 canonical workspace 与 hardened Git，并把 application 调用
// 分派给 catalog、snapshot materializer 和 mutation 模块。查询、解析和事务规则不再堆叠
// 在 façade 中，但所有 mutation 仍通过同一个 `NativeReviewAdapter` 进入。

use super::git::{CancellationToken, GitError, GitReadOnly};
use crate::review::application::{ReviewError, ReviewNativePort};
use crate::review::domain::{
    ReviewAction, ReviewCatalog, ReviewCatalogLimit, ReviewFile, ReviewRevision, ReviewSnapshot,
    ReviewSource, ReviewTarget,
};
use crate::workspace::{MutationInfrastructureError, WorkspaceError, WorkspaceHandle};

/// 将 hardened Git 错误压缩为 application 的稳定恢复语义，不携带 argv 或 stderr。
impl From<GitError> for ReviewError {
    fn from(error: GitError) -> Self {
        match error {
            GitError::ExternalWorktree | GitError::Workspace => Self::ExternalWorktree,
            GitError::OutputLimitExceeded => Self::OutputLimitExceeded,
            GitError::Cancelled => Self::Cancelled,
            GitError::Parse => Self::Parse,
            GitError::GitUnavailable => Self::GitUnavailable,
            GitError::NotRepository => Self::NotRepository,
            GitError::CommandFailed { .. } => Self::NativeCommandFailed,
            _ => Self::GitFailed,
        }
    }
}

/// 将 workspace containment 错误统一映射为脱敏 escape 语义。
impl From<WorkspaceError> for ReviewError {
    fn from(_error: WorkspaceError) -> Self {
        Self::ExternalWorktree
    }
}

/// 将共享 mutation coordinator 的错误映射到 Review 既有稳定词汇。
impl From<MutationInfrastructureError> for ReviewError {
    fn from(error: MutationInfrastructureError) -> Self {
        match error {
            MutationInfrastructureError::OutsideWorkspace => Self::ExternalWorktree,
            MutationInfrastructureError::Busy => Self::Conflict,
            MutationInfrastructureError::Io | MutationInfrastructureError::AtomicUnsupported => {
                Self::Io
            }
        }
    }
}

/// 供 mutation 闭包复用脱敏错误映射，避免闭包捕获 façade 之外的状态。
pub(super) fn map_mutation_error(error: MutationInfrastructureError) -> ReviewError {
    error.into()
}

/// 原生 Review 适配器；只对 application port 可见，不是第二个用例 owner。
#[derive(Clone)]
pub(crate) struct NativeReviewAdapter {
    pub(super) workspace: WorkspaceHandle,
    pub(super) git: GitReadOnly,
}

impl NativeReviewAdapter {
    /// 从已准入的 canonical workspace 构造 adapter，并复用同一 hardened Git 入口。
    pub(crate) fn new(workspace: WorkspaceHandle) -> Result<Self, ReviewError> {
        let git = GitReadOnly::new(workspace.clone())?;
        Ok(Self { workspace, git })
    }
}

impl ReviewNativePort for NativeReviewAdapter {
    /// 将有界 catalog 查询分派到固定 Git 查询模块，不接受任意 ref 参数。
    fn catalog(
        &self,
        max_commits: ReviewCatalogLimit,
        cancellation: &CancellationToken,
    ) -> Result<ReviewCatalog, ReviewError> {
        self.catalog_with_limit(max_commits.get(), cancellation)
    }

    /// 通过唯一 snapshot materializer 生成 query 结果，确保 mutation CAS 复用同一 revision 算法。
    fn snapshot(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError> {
        self.materialize_snapshot(source, cancellation)
    }

    /// 只补读 application 已从新鲜 snapshot 解析出的文件，禁止把 caller 字符串当路径。
    fn materialize_file(
        &self,
        source: &ReviewSource,
        file: &ReviewFile,
        cancellation: &CancellationToken,
    ) -> Result<ReviewFile, ReviewError> {
        self.materialize_one_file(source, file, cancellation)
    }

    /// 把 CAS、路径锁、临时 index 与 worktree recovery 统一分派给 mutation 模块。
    fn apply_transaction(
        &self,
        source: &ReviewSource,
        expected_revision: &ReviewRevision,
        action: ReviewAction,
        target: &ReviewTarget,
        initial: &ReviewSnapshot,
        cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError> {
        self.execute_apply_transaction(
            source,
            expected_revision,
            action,
            target,
            initial,
            cancellation,
        )
    }
}
