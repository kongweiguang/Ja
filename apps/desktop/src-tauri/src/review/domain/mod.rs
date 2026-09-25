// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 领域模型与纯不变量。
//
// 该层只描述 source、revision、target、action 与快照事实，不接触 Git、文件系统或
// Tauri。外部能力只能在 application 端口之后进入，避免领域对象被原生实现反向污染。

pub(crate) mod model;

pub(crate) use model::ReviewWorktreeEvidence;
pub use model::{
    MAX_REVIEW_DIFF_BYTES, MAX_REVIEW_DIFF_LINES, MAX_REVIEW_FILES, ReviewAction,
    ReviewApplyResult, ReviewCatalog, ReviewCatalogLimit, ReviewCommit, ReviewCommitId, ReviewFile,
    ReviewFileDiff, ReviewFileId, ReviewFileLayer, ReviewFileStatus, ReviewHunk, ReviewHunkId,
    ReviewLine, ReviewLineKind, ReviewOperationId, ReviewRef, ReviewRefId, ReviewRevision,
    ReviewSnapshot, ReviewSource, ReviewStats, ReviewTarget,
};
