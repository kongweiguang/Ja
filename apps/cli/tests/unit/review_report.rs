// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

#[path = "../../src/controller/review_report.rs"]
mod review_report;

use ja_review::{
    ReviewFileLayer, ReviewFileStatus, ReviewSnapshot, ReviewSnapshotFile, ReviewSnapshotSource,
    SnapshotCoverage, SnapshotPartialReason,
};
use serde_json::{Value, json};

/// Build protocol-shaped snapshots so report validation tests exercise real shared evidence types.
fn snapshot(partial: bool, files: Vec<ReviewSnapshotFile>) -> ReviewSnapshot {
    ReviewSnapshot {
        version: 1,
        snapshot_id: "rev_abcdef0123456789abcdef01".into(),
        workspace_id: "ws_review".into(),
        source: ReviewSnapshotSource::Uncommitted,
        revision: "b".repeat(64),
        snapshot_hash: "a".repeat(64),
        coverage: SnapshotCoverage {
            complete: !partial,
            files: files.len(),
            bytes: 0,
            max_files: 2_000,
            max_bytes: 64 * 1024 * 1024,
            partial_reasons: if partial {
                vec![SnapshotPartialReason::BinaryFile]
            } else {
                Vec::new()
            },
        },
        files,
    }
}

/// Keep the fixtures small while retaining enough evidence to check finding lines.
fn file(path: &str, content: Option<&str>) -> ReviewSnapshotFile {
    ReviewSnapshotFile {
        path: path.into(),
        old_path: None,
        status: ReviewFileStatus::Modified,
        layer: ReviewFileLayer::Unstaged,
        binary: content.is_none(),
        base_content: content.map(str::to_owned),
        content: content.map(str::to_owned),
        patch: None,
        base_sha256: None,
        content_sha256: None,
        patch_sha256: None,
    }
}

/// Construct the frozen report envelope consumed by the CLI formatter.
fn report(
    snapshot: &ReviewSnapshot,
    files_reviewed: Value,
    review_complete: bool,
    findings: Value,
) -> Value {
    let mut coverage = serde_json::to_value(&snapshot.coverage).unwrap();
    coverage["filesReviewed"] = files_reviewed;
    coverage["reviewComplete"] = json!(review_complete);
    json!({
        "reportId":"report_0123456789abcdef01234567",
        "snapshotId":snapshot.snapshot_id,
        "snapshotHash":snapshot.snapshot_hash,
        "revision":snapshot.revision,
        "summary":"Review summary",
        "coverage":coverage,
        "findings":findings,
    })
}

/// A report finding uses the same path, layer, side, and line range as the frozen file content.
fn finding(path: &str) -> Value {
    json!({
        "findingId":"finding_0123456789abcdef01234567",
        "severity":"P1",
        "title":"Unchecked result",
        "body":"The value can be absent.",
        "path":path,
        "layer":"unstaged",
        "startLine":1,
        "endLine":1,
        "side":"current",
        "evidence":"let value = items[0];",
    })
}

/// Matching a path alone cannot authorize evidence from another Git layer in the frozen snapshot.
#[test]
fn rejects_finding_from_a_different_layer_of_the_same_path() {
    let mut staged = file("src/lib.rs", Some("staged line"));
    staged.layer = ReviewFileLayer::Staged;
    let snapshot = snapshot(
        false,
        vec![staged, file("src/lib.rs", Some("unstaged line"))],
    );
    let value = report(
        &snapshot,
        json!([{"path":"src/lib.rs","layer":"staged"}]),
        false,
        json!([finding("src/lib.rs")]),
    );

    assert!(review_report::render_report(&value, &snapshot).is_err());
}

/// The golden contract carries reviewed file identities, not a numeric count.
#[test]
fn accepts_full_report_with_protocol_file_identities() {
    let snapshot = snapshot(
        false,
        vec![file("src/lib.rs", Some("let value = items[0];"))],
    );
    let value = report(
        &snapshot,
        json!([{"path":"src/lib.rs","layer":"unstaged"}]),
        true,
        json!([finding("src/lib.rs")]),
    );

    let rendered = review_report::render_report(&value, &snapshot).unwrap();
    assert!(rendered.contains("覆盖：1/1 个文件 · 完整"));
    assert!(rendered.contains("src/lib.rs:1-1"));
}

/// Incomplete evidence can still report its actual subset without overstating completeness.
#[test]
fn accepts_partial_report_and_shows_the_limited_scope() {
    let snapshot = snapshot(true, vec![file("assets/image.bin", None)]);
    let value = report(&snapshot, json!([]), false, json!([]));

    let rendered = review_report::render_report(&value, &snapshot).unwrap();
    assert!(rendered.contains("覆盖：0/1 个文件 · 部分"));
    assert!(rendered.contains("包含二进制文件"));
}

/// A model cannot claim a path or layer that the frozen snapshot never supplied.
#[test]
fn rejects_reviewed_file_identity_outside_snapshot() {
    let snapshot = snapshot(false, vec![file("src/lib.rs", Some("line"))]);
    let value = report(
        &snapshot,
        json!([{"path":"src/other.rs","layer":"unstaged"}]),
        true,
        json!([]),
    );

    assert!(review_report::render_report(&value, &snapshot).is_err());
}

/// Findings must stay within the reviewed subset even when their path exists in the full snapshot.
#[test]
fn rejects_finding_outside_reported_coverage() {
    let snapshot = snapshot(
        true,
        vec![
            file("src/reviewed.rs", Some("line")),
            file("src/unreviewed.rs", Some("line")),
        ],
    );
    let value = report(
        &snapshot,
        json!([{"path":"src/reviewed.rs","layer":"unstaged"}]),
        false,
        json!([finding("src/unreviewed.rs")]),
    );

    assert!(review_report::render_report(&value, &snapshot).is_err());
}

/// Completeness is derived from the source coverage and identity set, never trusted as a free flag.
#[test]
fn rejects_inconsistent_completeness_and_legacy_numeric_count() {
    let snapshot = snapshot(false, vec![file("src/lib.rs", Some("line"))]);
    let missing_file = report(&snapshot, json!([]), true, json!([]));
    let legacy_count = report(&snapshot, json!(1), true, json!([]));

    assert!(review_report::render_report(&missing_file, &snapshot).is_err());
    assert!(review_report::render_report(&legacy_count, &snapshot).is_err());
}
