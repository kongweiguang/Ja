// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use ja_review::{
    ReviewFileLayer, ReviewFileStatus, ReviewSnapshotBuilder, ReviewSnapshotSource,
    SnapshotFileInput, SnapshotPartialReason,
};

/// Build the same logical evidence in different arrival orders to lock down canonical identity.
fn snapshot(reverse: bool) -> ja_review::ReviewSnapshot {
    let files = [
        SnapshotFileInput {
            path: "src/a.rs".to_owned(),
            old_path: None,
            status: ReviewFileStatus::Modified,
            layer: ReviewFileLayer::Unstaged,
            binary: false,
            base_content: Some(b"fn a() {}\n".to_vec()),
            content: Some(b"fn a() { 1 }\n".to_vec()),
            patch: Some(b"@@ -1 +1 @@\n-fn a() {}\n+fn a() { 1 }\n".to_vec()),
        },
        SnapshotFileInput {
            path: "src/b.rs".to_owned(),
            old_path: None,
            status: ReviewFileStatus::Added,
            layer: ReviewFileLayer::Untracked,
            binary: false,
            base_content: None,
            content: Some(b"pub fn b() {}\n".to_vec()),
            patch: None,
        },
    ];
    let mut builder = ReviewSnapshotBuilder::new(
        "ws_test".to_owned(),
        ReviewSnapshotSource::Uncommitted,
        "native-revision".to_owned(),
    )
    .expect("valid builder");
    let entries = if reverse {
        files.into_iter().rev().collect::<Vec<_>>()
    } else {
        files.into_iter().collect::<Vec<_>>()
    };
    for file in entries {
        assert!(builder.push_file(file).expect("file accepted"));
    }
    builder.finish().expect("valid snapshot")
}

/// Snapshot IDs and serialized bytes must not depend on filesystem enumeration order.
#[test]
fn canonical_snapshot_is_order_independent_and_verifiable() {
    let first = snapshot(false);
    let second = snapshot(true);

    assert_eq!(first.snapshot_id, second.snapshot_id);
    assert_eq!(first.snapshot_hash, second.snapshot_hash);
    assert_eq!(
        first.canonical_json().expect("serialize"),
        second.canonical_json().expect("serialize")
    );
    assert!(first.verify_hash().expect("verify"));
}

/// Path and duplicate admission fail before a finding can bind to an ambiguous file identity.
#[test]
fn rejects_escape_paths_and_duplicate_layers() {
    let mut builder = ReviewSnapshotBuilder::new(
        "ws_test".to_owned(),
        ReviewSnapshotSource::Uncommitted,
        "native-revision".to_owned(),
    )
    .expect("valid builder");
    let escaped = SnapshotFileInput {
        path: "../outside.rs".to_owned(),
        old_path: None,
        status: ReviewFileStatus::Modified,
        layer: ReviewFileLayer::Unstaged,
        binary: false,
        base_content: None,
        content: None,
        patch: None,
    };
    assert_eq!(
        builder.push_file(escaped.clone()),
        Err(ja_review::SnapshotError::InvalidPath)
    );

    let valid = SnapshotFileInput {
        path: "src/a.rs".to_owned(),
        ..escaped
    };
    assert!(builder.push_file(valid.clone()).expect("first layer"));
    assert_eq!(
        builder.push_file(valid),
        Err(ja_review::SnapshotError::DuplicateFile)
    );
}

/// Binary and invalid UTF-8 evidence stays hash-addressed but cannot be presented as line-addressable text.
#[test]
fn binary_evidence_marks_coverage_partial_and_hides_raw_bytes() {
    let mut builder = ReviewSnapshotBuilder::new(
        "ws_test".to_owned(),
        ReviewSnapshotSource::Uncommitted,
        "native-revision".to_owned(),
    )
    .expect("valid builder");
    assert!(
        builder
            .push_file(SnapshotFileInput {
                path: "assets/data.bin".to_owned(),
                old_path: None,
                status: ReviewFileStatus::Added,
                layer: ReviewFileLayer::Untracked,
                binary: false,
                base_content: None,
                content: Some(vec![0xff, 0x00, 0x01]),
                patch: None,
            })
            .expect("file accepted")
    );
    let snapshot = builder.finish().expect("valid snapshot");

    assert!(!snapshot.coverage.complete);
    assert!(
        snapshot
            .coverage
            .partial_reasons
            .contains(&SnapshotPartialReason::BinaryFile)
    );
    assert!(
        snapshot
            .coverage
            .partial_reasons
            .contains(&SnapshotPartialReason::NonUtf8)
    );
    assert!(snapshot.files[0].content.is_none());
    assert!(snapshot.files[0].content_sha256.is_some());
    assert!(snapshot.verify_hash().expect("verify"));
}

/// JSON 转义会膨胀控制字符；超出附件上限时停止采集并如实发布 byte_limit 覆盖状态。
#[test]
fn escaped_snapshot_payload_limit_becomes_partial_coverage() {
    let mut builder = ReviewSnapshotBuilder::new(
        "ws_test".to_owned(),
        ReviewSnapshotSource::Uncommitted,
        "native-revision".to_owned(),
    )
    .expect("valid builder");
    assert!(
        !builder
            .push_file(SnapshotFileInput {
                path: "src/control.rs".to_owned(),
                old_path: None,
                status: ReviewFileStatus::Added,
                layer: ReviewFileLayer::Untracked,
                binary: false,
                base_content: None,
                content: Some(vec![1; 17 * 1024 * 1024]),
                patch: None,
            })
            .expect("over-budget file is a coverage boundary")
    );

    let snapshot = builder
        .finish()
        .expect("partial snapshot remains exportable");

    assert!(!snapshot.coverage.complete);
    assert!(
        snapshot
            .coverage
            .partial_reasons
            .contains(&SnapshotPartialReason::ByteLimit)
    );
    assert!(snapshot.files.is_empty());
    assert!(
        snapshot.canonical_json().expect("bounded envelope").len()
            <= ja_review::MAX_SNAPSHOT_JSON_BYTES
    );
    assert!(snapshot.verify_hash().expect("verify bounded snapshot"));
}

/// Rechecking after import detects any field mutation before the report is associated with the artifact.
#[test]
fn rejects_snapshot_content_mutation_after_freeze() {
    let mut snapshot = snapshot(false);
    snapshot.files[0].content = Some("changed\n".to_owned());

    assert!(!snapshot.verify_hash().expect("verify"));
}
