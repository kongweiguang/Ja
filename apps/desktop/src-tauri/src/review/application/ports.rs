// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 应用层端口。

use super::ReviewError;
use crate::review::domain::{
    ReviewAction, ReviewCatalog, ReviewCatalogLimit, ReviewFile, ReviewFileId, ReviewRevision,
    ReviewSnapshot, ReviewSource, ReviewTarget,
};
use tokio_util::sync::CancellationToken;

/// application 对原生 Review 能力的唯一端口。
///
/// 端口刻意保留原子的 `apply_transaction`，因为路径锁、临时 index 与 worktree
/// recovery 必须在同一 native 临界区完成；逐个暴露句柄会破坏锁生命周期。
pub(crate) trait ReviewNativePort: Send + Sync {
    /// 读取有界 repository catalog，不允许调用方传入任意 Git 参数。
    fn catalog(
        &self,
        max_commits: ReviewCatalogLimit,
        cancellation: &CancellationToken,
    ) -> Result<ReviewCatalog, ReviewError>;

    /// 物化一个 source 的权威快照；revision 必须覆盖所有可变选择证据。
    fn snapshot(
        &self,
        source: &ReviewSource,
        cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError>;

    /// 从 native-owned bounded cache 解析 opaque selector；miss 由 application 回退到 fresh snapshot。
    fn cached_file(
        &self,
        source: &ReviewSource,
        revision: &ReviewRevision,
        file_id: &ReviewFileId,
    ) -> Option<ReviewFile>;

    /// 在同一原生读取会话内按需补充 diff 并验证 revision，避免跨命令重复仓库扫描。
    fn load_file_at_revision(
        &self,
        source: &ReviewSource,
        expected: &ReviewRevision,
        file: &ReviewFile,
        validate_revision: bool,
        cancellation: &CancellationToken,
    ) -> Result<ReviewFile, ReviewError>;

    /// 在路径锁内重新检查 expected revision，并原子执行 Git/index/worktree mutation。
    ///
    /// `initial` 是 application 已验收的同一快照，用于确定锁集合；实现仍必须在锁内
    /// 重新物化并做 CAS。失败时必须回滚 worktree 并丢弃临时 index。
    fn apply_transaction(
        &self,
        source: &ReviewSource,
        expected_revision: &ReviewRevision,
        action: ReviewAction,
        target: &ReviewTarget,
        initial: &ReviewSnapshot,
        cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError>;
}
