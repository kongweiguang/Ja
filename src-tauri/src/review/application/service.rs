// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 应用服务。

use super::{ReviewError, ReviewNativePort, ReviewOperation};
use crate::review::domain::{
    ReviewAction, ReviewApplyResult, ReviewCatalog, ReviewCatalogLimit, ReviewFileDiff,
    ReviewFileId, ReviewOperationId, ReviewRevision, ReviewSnapshot, ReviewSource, ReviewTarget,
};
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

/// Review 的唯一用例入口，集中维护 stale、只读 source 与事务调用边界。
#[derive(Clone)]
pub(crate) struct ReviewService {
    native: Arc<dyn ReviewNativePort>,
}

impl ReviewService {
    /// 从 composition 注入原生端口，使 application 不构造 Git、路径或临时资源。
    pub(crate) fn new(native: Arc<dyn ReviewNativePort>) -> Self {
        Self { native }
    }

    /// 读取默认有界 catalog；默认上限属于产品用例而不是 Git adapter 配置。
    pub(crate) fn catalog(
        &self,
        cancellation: &CancellationToken,
    ) -> Result<ReviewCatalog, ReviewError> {
        self.catalog_with_limit(ReviewCatalogLimit::default_window(), cancellation)
    }

    /// 校验历史窗口后调用 native port，避免 interface 与 adapter 维护不同上限。
    pub(crate) fn catalog_with_limit(
        &self,
        max_commits: ReviewCatalogLimit,
        cancellation: &CancellationToken,
    ) -> Result<ReviewCatalog, ReviewError> {
        self.native.catalog(max_commits, cancellation)
    }

    /// 读取权威 snapshot；source 选择规则由 domain 表达，native 只执行有界 IO。
    pub(crate) fn snapshot(
        &self,
        source: ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError> {
        self.native.snapshot(&source, cancellation)
    }

    /// 在同一新鲜 snapshot 上解析 opaque file id，并只对 metadata-only 文件补读内容。
    pub(crate) fn file_diff(
        &self,
        source: ReviewSource,
        revision: &ReviewRevision,
        file_id: &ReviewFileId,
        cancellation: &CancellationToken,
    ) -> Result<ReviewFileDiff, ReviewError> {
        let snapshot = self.native.snapshot(&source, cancellation)?;
        if &snapshot.revision != revision {
            return Err(ReviewError::ReviewStale);
        }
        let Some(file) = snapshot.files.iter().find(|file| &file.file_id == file_id) else {
            return Err(ReviewError::InvalidInput);
        };
        let file = if file.metadata_only && !file.binary {
            let fresh = self.native.materialize_file(&source, file, cancellation)?;
            // lazy 原生读取与首次 snapshot 之间仍可能发生外部 Git/worktree 变化；补读后再次
            // 校验 revision，避免把新内容装进旧 revision 的 file diff 响应。
            let confirmed = self.native.snapshot(&source, cancellation)?;
            if &confirmed.revision != revision
                || !confirmed.files.iter().any(|file| &file.file_id == file_id)
            {
                return Err(ReviewError::ReviewStale);
            }
            fresh
        } else {
            file.clone()
        };
        Ok(ReviewFileDiff {
            revision: snapshot.revision,
            source,
            file,
        })
    }

    /// 以 application 为事务用例边界执行 apply，并要求 native port 在锁内完成二次 CAS。
    pub(crate) fn apply(
        &self,
        source: ReviewSource,
        revision: &ReviewRevision,
        action: ReviewAction,
        target: ReviewTarget,
        cancellation: &CancellationToken,
    ) -> Result<ReviewApplyResult, ReviewError> {
        if source.is_read_only() {
            return Err(ReviewError::ReadOnlySource);
        }

        // 第一阶段在锁外快速拒绝过期 UI 请求，避免无意义地进入全局路径队列。
        let initial = self.native.snapshot(&source, cancellation)?;
        if &initial.revision != revision {
            return Err(ReviewError::ReviewStale);
        }

        // 第二阶段由 native port 在同一锁域内完成 CAS、临时 index、回滚和 commit；
        // application 只接受 commit 后重新物化的 snapshot，不暴露中间状态。
        let snapshot = self.native.apply_transaction(
            &source,
            revision,
            action,
            &target,
            &initial,
            cancellation,
        )?;
        Ok(ReviewApplyResult { snapshot })
    }

    /// 注册可取消操作，确保 operation id 唯一性与生命周期由 application 收口。
    pub(crate) fn operation(
        operation_id: Option<ReviewOperationId>,
    ) -> Result<ReviewOperation, ReviewError> {
        ReviewOperation::begin(operation_id)
    }

    /// 幂等取消指定操作；找不到 live worker 时返回 false，不创建第二套状态事实。
    pub(crate) fn cancel_operation(operation_id: &ReviewOperationId) -> Result<bool, ReviewError> {
        ReviewOperation::cancel(operation_id)
    }
}
