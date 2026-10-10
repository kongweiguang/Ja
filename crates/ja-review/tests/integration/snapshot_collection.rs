// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use ja_review::{
    ReviewFileLayer, ReviewFileStatus, ReviewSnapshotSource, SnapshotCancellation,
    SnapshotCollectionError, create_snapshot, resolve_commit_id,
};
use serde_json::json;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

static NEXT_REPOSITORY_ID: AtomicU64 = AtomicU64::new(1);

struct TemporaryRepository {
    root: PathBuf,
}

impl TemporaryRepository {
    /// Create an isolated Git worktree whose local config is independent of the user's profile.
    fn new() -> Self {
        let id = NEXT_REPOSITORY_ID.fetch_add(1, Ordering::Relaxed);
        let root = std::env::temp_dir().join(format!("ja-review-{}-{id}", std::process::id()));
        fs::create_dir_all(&root).expect("create temporary repository directory");
        git(&root, &["init", "-q"]);
        git(&root, &["config", "user.name", "Ja Tests"]);
        git(&root, &["config", "user.email", "ja-tests@example.invalid"]);
        Self { root }
    }

    /// Commit the repository's current index and return the immutable commit ID.
    fn commit(&self, message: &str) -> String {
        git(&self.root, &["add", "--all"]);
        git(&self.root, &["commit", "-q", "-m", message]);
        String::from_utf8(git(&self.root, &["rev-parse", "HEAD"]).stdout)
            .expect("commit ID is UTF-8")
            .trim()
            .to_owned()
    }
}

impl Drop for TemporaryRepository {
    /// Remove only this test's uniquely named temporary repository.
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

/// Run a fixed Git command in the temporary repository and preserve stderr only in test diagnostics.
fn git(root: &Path, args: &[&str]) -> Output {
    let output = Command::new("git")
        .current_dir(root)
        .args(args)
        .output()
        .expect("Git is available for integration tests");
    assert!(
        output.status.success(),
        "git command failed: {:?}: {}",
        args,
        String::from_utf8_lossy(&output.stderr)
    );
    output
}

/// Give every Git operation one bounded deadline and no cancellation unless the test requests it.
fn deadline() -> Instant {
    Instant::now() + Duration::from_secs(20)
}

/// Stage one version, leave a second worktree edit, and verify both layers and an untracked file.
#[test]
fn collects_staged_unstaged_and_untracked_evidence() {
    let repository = TemporaryRepository::new();
    fs::create_dir_all(repository.root.join("src")).expect("create source directory");
    fs::write(repository.root.join("src/a file.rs"), "fn value() { 1 }\n")
        .expect("write initial source");
    let base = repository.commit("initial source");
    fs::write(repository.root.join("src/a file.rs"), "fn value() { 2 }\n")
        .expect("write second source");
    let head = repository.commit("second source");

    let cancelled = AtomicBool::new(false);
    let based = create_snapshot(
        &repository.root,
        "ws_test",
        ReviewSnapshotSource::Base {
            base_commit_id: base.clone(),
        },
        &cancelled,
        deadline(),
    )
    .expect("collect base comparison");
    assert_eq!(based.files.len(), 1);
    assert_eq!(
        based.files[0].base_content.as_deref(),
        Some("fn value() { 1 }\n")
    );
    assert_eq!(
        based.files[0].content.as_deref(),
        Some("fn value() { 2 }\n")
    );

    fs::write(repository.root.join("src/a file.rs"), "fn value() { 3 }\n")
        .expect("write staged source");
    git(&repository.root, &["add", "--", "src/a file.rs"]);
    fs::write(repository.root.join("src/a file.rs"), "fn value() { 4 }\n")
        .expect("write worktree source");
    fs::write(repository.root.join("new file.rs"), "fn added() {}\n")
        .expect("write untracked source");

    let snapshot = create_snapshot(
        &repository.root,
        "ws_test",
        ReviewSnapshotSource::Uncommitted,
        &cancelled,
        deadline(),
    )
    .expect("collect uncommitted snapshot");
    assert!(snapshot.verify_hash().expect("verify snapshot hash"));
    assert_eq!(snapshot.files.len(), 3);

    let staged = snapshot
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Staged)
        .expect("staged layer");
    assert_eq!(staged.path, "src/a file.rs");
    assert_eq!(staged.status, ReviewFileStatus::Modified);
    assert_eq!(staged.base_content.as_deref(), Some("fn value() { 2 }\n"));
    assert_eq!(staged.content.as_deref(), Some("fn value() { 3 }\n"));
    assert!(
        staged
            .patch
            .as_deref()
            .is_some_and(|patch| patch.contains("fn value() { 3 }"))
    );

    let unstaged = snapshot
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Unstaged)
        .expect("unstaged layer");
    assert_eq!(unstaged.base_content.as_deref(), Some("fn value() { 3 }\n"));
    assert_eq!(unstaged.content.as_deref(), Some("fn value() { 4 }\n"));

    let untracked = snapshot
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Untracked)
        .expect("untracked layer");
    assert_eq!(untracked.path, "new file.rs");
    assert_eq!(untracked.content.as_deref(), Some("fn added() {}\n"));

    let staged_only = create_snapshot(
        &repository.root,
        "ws_test",
        ReviewSnapshotSource::Staged,
        &cancelled,
        deadline(),
    )
    .expect("collect staged snapshot");
    assert_eq!(staged_only.files.len(), 1);
    assert_eq!(staged_only.files[0].layer, ReviewFileLayer::Staged);

    let unstaged_only = create_snapshot(
        &repository.root,
        "ws_test",
        ReviewSnapshotSource::Unstaged,
        &cancelled,
        deadline(),
    )
    .expect("collect unstaged snapshot");
    assert_eq!(unstaged_only.files.len(), 2);
    assert!(unstaged_only.files.iter().all(|file| matches!(
        file.layer,
        ReviewFileLayer::Unstaged | ReviewFileLayer::Untracked
    )));
    assert!(
        unstaged_only
            .files
            .iter()
            .any(|file| file.path == "new file.rs")
    );
    assert_eq!(
        serde_json::to_value(&staged_only.source).expect("serialize staged source"),
        json!({ "kind": "staged" })
    );
    assert_eq!(
        serde_json::to_value(&unstaged_only.source).expect("serialize unstaged source"),
        json!({ "kind": "unstaged" })
    );

    let selected = resolve_commit_id(&repository.root, "HEAD", &cancelled, deadline())
        .expect("resolve HEAD selector");
    assert_eq!(selected, head);
    let committed = create_snapshot(
        &repository.root,
        "ws_test",
        ReviewSnapshotSource::Commit { commit_id: base },
        &cancelled,
        deadline(),
    )
    .expect("collect commit snapshot");
    assert_eq!(committed.files.len(), 1);
    assert_eq!(committed.files[0].layer, ReviewFileLayer::Comparison);
    assert_eq!(
        committed.files[0].content.as_deref(),
        Some("fn value() { 1 }\n")
    );
}

/// Preserve cancellation and expired deadlines as distinct outcomes before touching the workspace.
#[test]
fn reports_cancellation_and_deadline_without_starting_git() {
    let cancelled = AtomicBool::new(true);
    assert_eq!(
        create_snapshot(
            Path::new("missing"),
            "ws_test",
            ReviewSnapshotSource::Uncommitted,
            &cancelled,
            deadline(),
        ),
        Err(SnapshotCollectionError::Cancelled)
    );

    let active = AtomicBool::new(false);
    assert_eq!(
        resolve_commit_id(Path::new("missing"), "HEAD", &active, Instant::now(),),
        Err(SnapshotCollectionError::DeadlineExceeded)
    );
}

/// A Windows-compatible cancellation implementation must expose the same read-only contract.
#[test]
fn atomic_signal_flag_implements_snapshot_cancellation() {
    let cancelled = AtomicBool::new(true);
    assert!(cancelled.is_cancellation_requested());
}

/// Refuse an untracked symlink before reading its target, even when the target stays inside the root.
#[cfg(unix)]
#[test]
fn rejects_untracked_symlinks() {
    use std::os::unix::fs::symlink;

    let repository = TemporaryRepository::new();
    symlink(
        repository.root.join(".git/config"),
        repository.root.join("link.rs"),
    )
    .expect("create symlink");

    let cancelled = AtomicBool::new(false);
    let result = create_snapshot(
        &repository.root,
        "ws_test",
        ReviewSnapshotSource::Uncommitted,
        &cancelled,
        deadline(),
    );
    assert_eq!(result, Err(SnapshotCollectionError::InvalidSnapshot));
}

/// Refuse a Windows reparse-point file before reading through it into Git metadata.
#[cfg(windows)]
#[test]
fn rejects_untracked_windows_reparse_files() {
    use std::os::windows::fs::symlink_file;

    let repository = TemporaryRepository::new();
    let link = repository.root.join("link.rs");
    if let Err(error) = symlink_file(repository.root.join(".git/config"), &link) {
        if error.raw_os_error() == Some(1314) {
            return;
        }
        panic!("create symlink fixture: {error:?}");
    }

    let cancelled = AtomicBool::new(false);
    let result = create_snapshot(
        &repository.root,
        "ws_test",
        ReviewSnapshotSource::Uncommitted,
        &cancelled,
        deadline(),
    );
    assert_eq!(result, Err(SnapshotCollectionError::InvalidSnapshot));
}
