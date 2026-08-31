// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Native Review 轻量 DDD 边界。
//
// domain 只保存可序列化事实与不变量，application 负责编排用例和事务边界，
// infrastructure 封装 Git/文件/恢复副作用，interface 只做 Tauri DTO 适配。

pub(crate) mod application;
pub(crate) mod domain;
pub(crate) mod infrastructure;
pub mod interface;

pub(crate) use application::ReviewService;
pub use application::{ReviewError, ReviewErrorCode};
pub(crate) use domain::TurnChangeSet;
pub use domain::{
    ReviewAction, ReviewApplyResult, ReviewCatalog, ReviewCatalogLimit, ReviewCommit,
    ReviewCommitId, ReviewFile, ReviewFileDiff, ReviewFileId, ReviewFileStatus, ReviewHunk,
    ReviewHunkId, ReviewLine, ReviewLineKind, ReviewOperationId, ReviewRef, ReviewRefId,
    ReviewRevision, ReviewSnapshot, ReviewSource, ReviewStats, ReviewTarget,
};
pub(crate) use infrastructure::TurnChangeBaseline;
pub use interface::{
    JA_REVIEW_INVALIDATED_EVENT, ReviewApplyInput, ReviewApplyResultDto, ReviewCancelInput,
    ReviewCancelResultDto, ReviewCatalogDto, ReviewCatalogInput, ReviewCommandError,
    ReviewFileDiffDto, ReviewFileDiffInput, ReviewInvalidatedEventDto, ReviewInvalidatedReason,
    ReviewSnapshotDto, ReviewSnapshotInput, ja_review_apply, ja_review_cancel, ja_review_catalog,
    ja_review_file_diff, ja_review_snapshot, ja_turn_change_set_read,
};

/// 在 composition 边界把 canonical workspace 与唯一 native adapter 组装为 application service。
///
/// 该 factory 是唯一知道 native adapter 的入口；application 不直接创建 Git 或临时资源，
/// interface 与测试也因此不能绕过相同的 containment/transaction 实现。
pub(crate) fn compose_service(
    workspace: crate::workspace::WorkspaceHandle,
) -> Result<ReviewService, ReviewError> {
    let native = infrastructure::NativeReviewAdapter::new(workspace)?;
    Ok(ReviewService::new(std::sync::Arc::new(native)))
}
