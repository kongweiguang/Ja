// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review catalog 与 Git ref/commit 查询。

use super::git::{CancellationToken, GitError};
use super::git_query::{base_ref_priority, trim_ref_record_terminators, validate_ref};
use super::native::NativeReviewAdapter;
use super::snapshot_materializer::trim_ascii_line;
use crate::review::application::ReviewError;
use crate::review::domain::{ReviewCatalog, ReviewCommit, ReviewRef};
use std::ffi::OsString;

pub(super) const EMPTY_TREE: &str = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
const MAX_CATALOG_REFS: usize = 64;

impl NativeReviewAdapter {
    /// 读取安全 base refs 和最近 commits，不暴露 native root 或 raw ref grammar。
    pub(super) fn catalog_with_limit(
        &self,
        max_commits: usize,
        cancellation: &CancellationToken,
    ) -> Result<ReviewCatalog, ReviewError> {
        let head = self.head(cancellation)?;
        let branch = self.branch(cancellation)?;
        let refs = self.base_refs(cancellation)?;
        let commits = self
            .git
            .log(max_commits, cancellation)?
            .into_iter()
            .map(|commit| ReviewCommit {
                commit_id: commit.object_id,
                parents: commit.parents,
                author: commit.author,
                subject: commit.subject,
                authored_at: commit.authored_at,
            })
            .collect();
        Ok(ReviewCatalog {
            head,
            branch,
            base_refs: refs,
            commits,
        })
    }

    /// 通过固定 rev-parse 读取 `HEAD`；unborn repository 返回 None 而不是错误。
    pub(super) fn head(
        &self,
        cancellation: &CancellationToken,
    ) -> Result<Option<String>, ReviewError> {
        let args = [
            OsString::from("rev-parse"),
            OsString::from("--verify"),
            OsString::from("--quiet"),
            OsString::from("HEAD"),
        ];
        match self.git.run_review_command(&args, cancellation) {
            Ok(bytes) => Ok(Some(trim_ascii_line(&bytes)?)),
            Err(GitError::CommandFailed { .. }) => Ok(None),
            Err(error) => Err(error.into()),
        }
    }

    /// 读取 symbolic branch name，不跟随 remote display data。
    pub(super) fn branch(
        &self,
        cancellation: &CancellationToken,
    ) -> Result<Option<String>, ReviewError> {
        let args = [
            OsString::from("symbolic-ref"),
            OsString::from("--quiet"),
            OsString::from("--short"),
            OsString::from("HEAD"),
        ];
        match self.git.run_review_command(&args, cancellation) {
            Ok(bytes) => Ok(Some(trim_ascii_line(&bytes)?)),
            Err(GitError::CommandFailed { .. }) => Ok(None),
            Err(error) => Err(error.into()),
        }
    }

    /// 按 Review selector 的确定性 fallback 顺序枚举有界 local/remote ref。
    /// 显式 Branch ref 仍权威；优先级留在 native，避免不同客户端产生不同 base 选择。
    pub(super) fn base_refs(
        &self,
        cancellation: &CancellationToken,
    ) -> Result<Vec<ReviewRef>, ReviewError> {
        let args = [
            OsString::from("for-each-ref"),
            OsString::from("--format=%(refname)%00%(objectname)%00"),
            OsString::from("refs/heads"),
            OsString::from("refs/remotes"),
        ];
        let bytes = self.git.run_review_command(&args, cancellation)?;
        let fields = bytes.split(|byte| *byte == 0).collect::<Vec<_>>();
        let mut refs = Vec::new();
        for pair in fields.chunks(2) {
            let Some(name) = pair
                .first()
                .map(|value| trim_ref_record_terminators(value))
                .filter(|value| !value.is_empty())
            else {
                continue;
            };
            let Some(object) = pair
                .get(1)
                .map(|value| trim_ref_record_terminators(value))
                .filter(|value| !value.is_empty())
            else {
                continue;
            };
            let full_ref = String::from_utf8(name.to_vec()).map_err(|_| ReviewError::Parse)?;
            let Some(ref_id) = full_ref
                .strip_prefix("refs/heads/")
                .or_else(|| full_ref.strip_prefix("refs/remotes/"))
            else {
                continue;
            };
            validate_ref(ref_id)?;
            let object_id = String::from_utf8(object.to_vec()).map_err(|_| ReviewError::Parse)?;
            refs.push(ReviewRef {
                ref_id: ref_id.to_owned(),
                object_id,
            });
        }
        refs.sort_by(|left, right| {
            base_ref_priority(&left.ref_id)
                .cmp(&base_ref_priority(&right.ref_id))
                .then_with(|| left.ref_id.cmp(&right.ref_id))
        });
        refs.dedup_by(|left, right| left.ref_id == right.ref_id);
        refs.truncate(MAX_CATALOG_REFS);
        Ok(refs)
    }

    /// 读取 commit parent list；root commit 按设计返回 None。
    pub(super) fn commit_parent(
        &self,
        commit_id: &str,
        cancellation: &CancellationToken,
    ) -> Result<Option<String>, ReviewError> {
        let args = [
            OsString::from("rev-list"),
            OsString::from("--parents"),
            OsString::from("-n1"),
            OsString::from(commit_id),
            OsString::from("--"),
        ];
        let bytes = self.git.run_review_command(&args, cancellation)?;
        let fields = String::from_utf8(bytes)
            .map_err(|_| ReviewError::Parse)?
            .split_whitespace()
            .map(str::to_owned)
            .collect::<Vec<_>>();
        Ok(fields.get(1).cloned())
    }
}
