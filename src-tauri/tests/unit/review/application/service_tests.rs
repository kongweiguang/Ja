// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::{CancellationToken, ReviewError, ReviewNativePort, ReviewService};
use crate::review::domain::{
    ReviewAction, ReviewCatalog, ReviewCatalogLimit, ReviewCommitId, ReviewFile, ReviewFileId,
    ReviewFileLayer, ReviewFileStatus, ReviewOperationId, ReviewRevision, ReviewSnapshot,
    ReviewSource, ReviewStats, ReviewTarget,
};
use std::sync::{Arc, Barrier, Mutex};
use uuid::Uuid;

/// 每个用例创建独立的原生取消令牌，避免测试重新引入已删除的 callback 兼容层。
fn active_cancellation() -> CancellationToken {
    CancellationToken::new()
}

/// fake port 只记录 application 的调用阶段，避免用真实 Git 掩盖用例层不变量。
struct FakeReviewPort {
    snapshot: Mutex<ReviewSnapshot>,
    apply_calls: Mutex<usize>,
    materialize_calls: Mutex<usize>,
    drift_on_materialize: bool,
}

impl FakeReviewPort {
    /// 使用指定 revision 构造最小 fake，使测试可以精确控制 stale 分支。
    fn new(revision: &str, metadata_only: bool) -> Self {
        Self {
            snapshot: Mutex::new(snapshot_fixture(revision, metadata_only)),
            apply_calls: Mutex::new(0),
            materialize_calls: Mutex::new(0),
            drift_on_materialize: false,
        }
    }

    /// 配置补读后 revision 漂移，用于复现 lazy native read 与确认 snapshot 之间的竞态。
    fn with_materialize_drift(mut self) -> Self {
        self.drift_on_materialize = true;
        self
    }

    /// 返回事务调用次数，用于证明只读与 stale 请求不会进入副作用端口。
    fn apply_calls(&self) -> usize {
        *self.apply_calls.lock().expect("apply counter")
    }

    /// 返回 lazy materialize 次数，用于证明普通文件不会产生重复原生读取。
    fn materialize_calls(&self) -> usize {
        *self.materialize_calls.lock().expect("materialize counter")
    }
}

impl ReviewNativePort for FakeReviewPort {
    /// catalog 与当前用例测试无关，返回固定空结果以满足窄端口。
    fn catalog(
        &self,
        _max_commits: ReviewCatalogLimit,
        _cancellation: &CancellationToken,
    ) -> Result<ReviewCatalog, ReviewError> {
        Ok(ReviewCatalog {
            head: None,
            branch: None,
            base_refs: Vec::new(),
            commits: Vec::new(),
        })
    }

    /// 返回测试控制的 snapshot；clone 模拟每次权威读取都是独立值。
    fn snapshot(
        &self,
        _source: &ReviewSource,
        _cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError> {
        Ok(self.snapshot.lock().expect("snapshot").clone())
    }

    /// fake 不跨 service 调用保存 cache，强制用例覆盖 fresh snapshot fallback。
    fn cached_file(
        &self,
        _source: &ReviewSource,
        _revision: &ReviewRevision,
        _file_id: &ReviewFileId,
    ) -> Option<ReviewFile> {
        None
    }

    /// 记录 lazy 补读，并在同一 fake 调用中模拟 native revision probe。
    fn load_file_at_revision(
        &self,
        _source: &ReviewSource,
        expected: &ReviewRevision,
        file: &ReviewFile,
        validate_revision: bool,
        _cancellation: &CancellationToken,
    ) -> Result<ReviewFile, ReviewError> {
        let mut expanded = file.clone();
        if file.requires_diff_load() {
            *self.materialize_calls.lock().expect("materialize counter") += 1;
            if self.drift_on_materialize {
                self.snapshot.lock().expect("snapshot").revision = revision("revision_drifted");
            }
            expanded.metadata_only = false;
            expanded.diff_loaded = true;
        }
        if validate_revision && self.snapshot.lock().expect("snapshot").revision != *expected {
            return Err(ReviewError::ReviewStale);
        }
        Ok(expanded)
    }

    /// 记录事务边界并返回新的 revision，模拟 native commit 后重新物化。
    fn apply_transaction(
        &self,
        source: &ReviewSource,
        _expected_revision: &ReviewRevision,
        _action: ReviewAction,
        _target: &ReviewTarget,
        initial: &ReviewSnapshot,
        _cancellation: &CancellationToken,
    ) -> Result<ReviewSnapshot, ReviewError> {
        *self.apply_calls.lock().expect("apply counter") += 1;
        let mut after = initial.clone();
        after.source = source.clone();
        after.revision = revision("revision_after");
        Ok(after)
    }
}

/// 测试只通过公开 parse 构造 revision，保证 fixture 同样遵守生产领域约束。
fn revision(value: &str) -> ReviewRevision {
    ReviewRevision::parse(value).expect("valid review revision fixture")
}

/// 测试只通过公开 parse 构造 file id，防止 fixture 绕过 target 不变量。
fn file_id(value: &str) -> ReviewFileId {
    ReviewFileId::parse(value).expect("valid review file id fixture")
}

/// 构造一个包含 opaque file id 的 domain snapshot，测试不接触路径解析或 Git。
fn snapshot_fixture(revision: &str, metadata_only: bool) -> ReviewSnapshot {
    ReviewSnapshot {
        revision: self::revision(revision),
        source: ReviewSource::Unstaged,
        files: vec![ReviewFile {
            file_id: file_id("file_fixture"),
            layer: ReviewFileLayer::Unstaged,
            path: "file.txt".to_owned(),
            old_path: None,
            status: ReviewFileStatus::Modified,
            additions: Some(1),
            deletions: Some(1),
            binary: false,
            metadata_only,
            hunks: Vec::new(),
            diff_loaded: !metadata_only,
            patch: Vec::new(),
            revision_evidence: b"fixture".to_vec(),
            state_evidence: b"fixture".to_vec(),
            worktree_evidence: None,
        }],
        stats: ReviewStats {
            files: 1,
            additions: 1,
            deletions: 1,
            binary_files: 0,
            truncated_files: 0,
        },
    }
}

/// 验证 application 在进入 native transaction 前拒绝只读 source。
#[test]
fn read_only_source_never_enters_mutation_port() {
    let fake = Arc::new(FakeReviewPort::new("revision_current", false));
    let service = ReviewService::new(fake.clone());
    let cancellation = active_cancellation();
    let result = service.apply(
        ReviewSource::Commit {
            commit_id: ReviewCommitId::parse("deadbeef").expect("commit id"),
        },
        &revision("revision_current"),
        ReviewAction::Revert,
        ReviewTarget::All,
        &cancellation,
    );
    assert!(matches!(result, Err(ReviewError::ReadOnlySource)));
    assert_eq!(fake.apply_calls(), 0);
}

/// 验证锁外首次 CAS 会快速拒绝 stale 请求，避免进入全局路径锁。
#[test]
fn stale_revision_never_enters_mutation_port() {
    let fake = Arc::new(FakeReviewPort::new("revision_current", false));
    let service = ReviewService::new(fake.clone());
    let cancellation = active_cancellation();
    let result = service.apply(
        ReviewSource::Unstaged,
        &revision("revision_stale"),
        ReviewAction::Stage,
        ReviewTarget::All,
        &cancellation,
    );
    assert!(matches!(result, Err(ReviewError::ReviewStale)));
    assert_eq!(fake.apply_calls(), 0);
}

/// 验证 lazy diff 只对 snapshot 标记的 metadata-only 文件调用补读端口。
#[test]
fn lazy_diff_materializes_only_metadata_file() {
    let fake = Arc::new(FakeReviewPort::new("revision_current", true));
    let service = ReviewService::new(fake.clone());
    let cancellation = active_cancellation();
    let diff = service
        .file_diff(
            ReviewSource::Unstaged,
            &revision("revision_current"),
            &file_id("file_fixture"),
            &cancellation,
        )
        .expect("file diff");
    assert!(!diff.file.metadata_only);
    assert_eq!(fake.materialize_calls(), 1);
}

/// 验证 lazy 补读后发生 revision drift 时返回 stale，不混合两个 snapshot 的内容。
#[test]
fn lazy_diff_rejects_revision_drift_after_materialize() {
    let fake = Arc::new(FakeReviewPort::new("revision_current", true).with_materialize_drift());
    let service = ReviewService::new(fake);
    let cancellation = active_cancellation();
    let result = service.file_diff(
        ReviewSource::Unstaged,
        &revision("revision_current"),
        &file_id("file_fixture"),
        &cancellation,
    );
    assert!(matches!(result, Err(ReviewError::ReviewStale)));
}

/// 验证成功事务只接受 native commit 后返回的新权威 revision。
#[test]
fn apply_returns_committed_snapshot_from_native_port() {
    let fake = Arc::new(FakeReviewPort::new("revision_current", false));
    let service = ReviewService::new(fake.clone());
    let cancellation = active_cancellation();
    let result = service
        .apply(
            ReviewSource::Unstaged,
            &revision("revision_current"),
            ReviewAction::Stage,
            ReviewTarget::All,
            &cancellation,
        )
        .expect("apply");
    assert_eq!(result.snapshot.revision.as_str(), "revision_after");
    assert_eq!(fake.apply_calls(), 1);
}

/// 验证 operation registry 拒绝重复 identity，并在 owner Drop 后释放 cancel 映射。
#[test]
fn operation_registry_is_unique_cancellable_and_drop_scoped() {
    let operation_id = format!("review-operation-{}", Uuid::new_v4());
    let operation_id = ReviewOperationId::parse(operation_id).expect("operation id");
    let operation = ReviewService::operation(Some(operation_id.clone())).expect("operation");
    let cancellation = operation.cancellation();
    assert!(matches!(
        ReviewService::operation(Some(operation_id.clone())),
        Err(ReviewError::InvalidInput)
    ));
    assert!(ReviewService::cancel_operation(&operation_id).expect("cancel live operation"));
    assert!(cancellation.is_cancelled());
    drop(operation);
    assert!(!ReviewService::cancel_operation(&operation_id).expect("cancel completed operation"));
}

/// 验证取消先于 worker 注册时会被后续 operation 消费，而不是静默丢失取消意图。
#[test]
fn cancellation_before_registration_cancels_the_later_operation() {
    let operation_id =
        ReviewOperationId::parse(format!("review-early-{}", Uuid::new_v4())).expect("operation id");
    assert!(ReviewService::cancel_operation(&operation_id).expect("record early cancel"));
    assert!(ReviewService::cancel_operation(&operation_id).expect("repeat early cancel"));
    let operation = ReviewService::operation(Some(operation_id.clone())).expect("operation");
    assert!(operation.cancellation().is_cancelled());
    drop(operation);
    assert!(!ReviewService::cancel_operation(&operation_id).expect("cancel completed operation"));
    assert!(matches!(
        ReviewService::operation(Some(operation_id)),
        Err(ReviewError::InvalidInput)
    ));
}

/// 并发制造 begin/cancel 的先后不确定性；无论谁先取得注册表锁，worker 都必须观察到取消。
#[test]
fn operation_spawn_and_cancel_race_is_lossless() {
    for _ in 0..100 {
        let operation_id = ReviewOperationId::parse(format!("review-race-{}", Uuid::new_v4()))
            .expect("operation id");
        let start = Arc::new(Barrier::new(2));
        let finish = Arc::new(Barrier::new(2));
        let worker_id = operation_id.clone();
        let worker_start = start.clone();
        let worker_finish = finish.clone();
        let worker = std::thread::spawn(move || {
            worker_start.wait();
            let operation = ReviewService::operation(Some(worker_id)).expect("operation");
            let cancellation = operation.cancellation();
            worker_finish.wait();
            let cancelled = cancellation.is_cancelled();
            drop(operation);
            cancelled
        });
        let cancel_id = operation_id.clone();
        let cancel_start = start.clone();
        let cancel_finish = finish.clone();
        let canceller = std::thread::spawn(move || {
            cancel_start.wait();
            let accepted = ReviewService::cancel_operation(&cancel_id).expect("cancel operation");
            cancel_finish.wait();
            accepted
        });
        assert!(canceller.join().expect("canceller join"));
        assert!(worker.join().expect("worker join"));
        assert!(!ReviewService::cancel_operation(&operation_id).expect("completed operation"));
    }
}
