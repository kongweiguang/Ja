// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::review::domain::{
    ReviewFile, ReviewFileDiff, ReviewFileId, ReviewFileLayer, ReviewFileStatus, ReviewHunk,
    ReviewHunkId, ReviewLine, ReviewLineKind, ReviewRevision, ReviewSnapshot, ReviewStats,
};
use ja_review::{ReviewSnapshotSource, SnapshotCoverage, SnapshotPartialReason};
use serde_json::json;

/// 锁定 Rust projection 与 typed adapter 的当前四来源和扁平 diff wire shape。
#[test]
fn review_wire_fixture_matches_typed_adapter() {
    let hunk = ReviewHunk {
        hunk_id: ReviewHunkId::parse("hunk_fixture").expect("hunk id"),
        header: "@@ -1,1 +1,1 @@".to_owned(),
        old_start: 1,
        old_lines: 1,
        new_start: 1,
        new_lines: 1,
        lines: vec![ReviewLine {
            kind: ReviewLineKind::Addition,
            text: "new".to_owned(),
        }],
        raw_patch: b"diff --git a/a.txt b/a.txt\n".to_vec(),
    };
    let file = ReviewFile {
        file_id: ReviewFileId::parse("file_fixture").expect("file id"),
        layer: ReviewFileLayer::Unstaged,
        path: "a.txt".to_owned(),
        old_path: None,
        status: ReviewFileStatus::Modified,
        additions: Some(1),
        deletions: Some(1),
        binary: false,
        metadata_only: false,
        hunks: vec![hunk],
        diff_loaded: true,
        patch: b"@@ -1,1 +1,1 @@\n-new\n+newer\n".to_vec(),
        revision_evidence: b"fixture".to_vec(),
        state_evidence: b"fixture".to_vec(),
        worktree_evidence: None,
    };
    let snapshot = project_snapshot(
        "ws_fixture",
        ReviewSnapshot {
            revision: ReviewRevision::parse("revision_fixture").expect("revision"),
            source: ReviewSource::Unstaged,
            files: vec![file.clone()],
            stats: ReviewStats {
                files: 1,
                additions: 1,
                deletions: 1,
                binary_files: 0,
                truncated_files: 0,
            },
        },
    );
    let value = serde_json::to_value(snapshot).expect("snapshot fixture");
    assert_eq!(value["workspaceId"], "ws_fixture");
    assert_eq!(value["source"]["kind"], "unstaged");
    assert_eq!(value["files"][0]["layer"], "unstaged");
    assert_eq!(value["files"][0]["truncated"], false);
    assert_eq!(value["capabilities"]["stage"], true);
    assert!(value["capabilities"].get("exact").is_none());

    let diff = project_file_diff(
        "ws_fixture",
        ReviewFileDiff {
            revision: ReviewRevision::parse("revision_fixture").expect("revision"),
            source: ReviewSource::Unstaged,
            file,
        },
    );
    let value = serde_json::to_value(diff).expect("file diff fixture");
    assert_eq!(value["fileId"], "file_fixture");
    assert_eq!(value["layer"], "unstaged");
    assert!(value.get("file").is_none());
    assert_eq!(value["lines"][0]["kind"], "addition");
    assert_eq!(value["lines"][0]["newLine"], 1);
}

/// 聚合来源必须是明确 tagged variant，不能由客户端把 staged/unstaged 数组临时拼接。
#[test]
fn uncommitted_source_wire_is_explicit() {
    assert_eq!(
        serde_json::to_value(ReviewSourceDto::Uncommitted).expect("source fixture"),
        json!({ "kind": "uncommitted" })
    );
}

/// Capture input binds the UI revision and cancellable operation to one explicit source selection.
#[test]
fn review_capture_input_requires_source_revision_and_operation_identity() {
    let input: ReviewCaptureInput = serde_json::from_value(json!({
        "workspaceId": "ws_fixture",
        "source": { "kind": "staged" },
        "expectedSnapshotRevision": "revision_fixture",
        "operationId": "review_capture_fixture"
    }))
    .expect("capture input");

    assert_eq!(input.workspace_id, "ws_fixture");
    assert_eq!(input.expected_snapshot_revision, "revision_fixture");
    assert_eq!(input.operation_id, "review_capture_fixture");
    assert!(matches!(input.source, ReviewSourceDto::Staged));
}

/// Capture result exposes only frozen identity, managed attachment identity, and measured coverage.
#[test]
fn review_capture_result_uses_shared_snapshot_source_and_coverage_wire() {
    let value = serde_json::to_value(ReviewCaptureResultDto {
        workspace_id: "ws_fixture".to_owned(),
        source: ReviewSnapshotSource::Base {
            base_commit_id: "a".repeat(40),
        },
        snapshot_id: "rev_fixture".to_owned(),
        snapshot_hash: "b".repeat(64),
        revision: "c".repeat(64),
        attachment_id: "att_fixture".to_owned(),
        coverage: SnapshotCoverage {
            complete: false,
            files: 1,
            bytes: 7,
            max_files: 2_000,
            max_bytes: 64 * 1024 * 1024,
            partial_reasons: vec![SnapshotPartialReason::BinaryFile],
        },
    })
    .expect("capture result");

    assert_eq!(
        value["source"],
        json!({ "kind": "base", "commitId": "a".repeat(40) })
    );
    assert_eq!(value["attachmentId"], "att_fixture");
    assert_eq!(value["coverage"]["complete"], false);
    assert_eq!(value["coverage"]["partialReasons"], json!(["binary_file"]));
    assert!(value.get("files").is_none());
}

/// Mutable-layer selectors map one-to-one to collector selectors without broadening to uncommitted.
#[test]
fn review_capture_preserves_staged_and_unstaged_source_scope() {
    let cancellation = tokio_util::sync::CancellationToken::new();
    let staged = freeze_source(
        Path::new("missing"),
        &ReviewSource::Staged,
        &cancellation,
        Instant::now() + Duration::from_secs(1),
    )
    .expect("staged source");
    let unstaged = freeze_source(
        Path::new("missing"),
        &ReviewSource::Unstaged,
        &cancellation,
        Instant::now() + Duration::from_secs(1),
    )
    .expect("unstaged source");

    assert_eq!(staged, ReviewSnapshotSource::Staged);
    assert_eq!(unstaged, ReviewSnapshotSource::Unstaged);
}

/// 锁定 invalidation event 只是一条 refetch hint，不能演化为第二份 mutation ledger。
#[test]
fn review_invalidation_event_fixture_is_stable() {
    assert_eq!(JA_REVIEW_INVALIDATED_EVENT, "review/invalidated");
    let value = serde_json::to_value(ReviewInvalidatedEventDto {
        workspace_id: "ws_fixture".to_owned(),
        generation: 7,
        reason: ReviewInvalidatedReason::Mutation,
    })
    .expect("invalidation event fixture");
    assert_eq!(
        value,
        json!({
            "workspaceId": "ws_fixture",
            "generation": 7,
            "reason": "mutation"
        })
    );
}
