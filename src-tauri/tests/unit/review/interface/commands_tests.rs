// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::review::domain::{
    ReviewFile, ReviewFileDiff, ReviewFileId, ReviewFileStatus, ReviewHunk, ReviewHunkId,
    ReviewLine, ReviewLineKind, ReviewRevision, ReviewSnapshot, ReviewStats,
};
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
        path: "a.txt".to_owned(),
        old_path: None,
        status: ReviewFileStatus::Modified,
        additions: Some(1),
        deletions: Some(1),
        binary: false,
        metadata_only: false,
        hunks: vec![hunk],
        patch: b"@@ -1,1 +1,1 @@\n-new\n+newer\n".to_vec(),
        revision_evidence: b"fixture".to_vec(),
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
    assert!(value.get("file").is_none());
    assert_eq!(value["lines"][0]["kind"], "addition");
    assert_eq!(value["lines"][0]["newLine"], 1);
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
