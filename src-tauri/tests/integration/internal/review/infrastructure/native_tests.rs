// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::parse::{parse_diff, synthetic_added_patch};
use crate::review::domain::model::{MAX_REVIEW_SNAPSHOT_BYTES, MAX_REVIEW_SNAPSHOT_FILES};
use crate::review::domain::{
    ReviewAction, ReviewCommitId, ReviewFileLayer, ReviewFileStatus, ReviewOperationId,
    ReviewRevision, ReviewSource, ReviewTarget,
};
use crate::review::infrastructure::snapshot_materializer::{
    enforce_snapshot_budget, read_bounded_with_cancellation,
};
use crate::review::{ReviewError, ReviewService, compose_service};
use crate::workspace::WorkspaceRegistry;
use sha2::Digest;
use std::fs;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Instant;
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

/// 拥有真实 temporary Git fixture，并在每个测试后回收。
struct TempRepo(PathBuf);

impl TempRepo {
    /// 创建名称不会与并行 test process 冲突的 isolated directory。
    fn create() -> Option<Self> {
        let path = std::env::temp_dir().join(format!("ja-review-test-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).ok()?;
        Some(Self(path))
    }

    /// 返回 fixture command helper 使用的 filesystem root。
    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempRepo {
    /// 仅删除当前 fixture 唯一创建的 test directory。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// 运行固定 test-only Git command，不复用 production argv helper 以保持测试独立。
fn git(repo: &Path, args: &[&str]) -> Option<Vec<u8>> {
    let output = Command::new("git")
        .current_dir(repo)
        .args(args)
        .output()
        .ok()?;
    output.status.success().then_some(output.stdout)
}

/// 创建可被 production Git validator 接受的 committed repository。
fn committed_repo() -> Option<TempRepo> {
    let repo = TempRepo::create()?;
    git(repo.path(), &["init", "-q"])?;
    git(repo.path(), &["config", "user.name", "Ja Review Test"])?;
    git(
        repo.path(),
        &["config", "user.email", "review@example.invalid"],
    )?;
    fs::write(repo.path().join("tracked.txt"), b"one\ntwo\n").ok()?;
    git(repo.path(), &["add", "--all"])?;
    git(repo.path(), &["commit", "-q", "-m", "initial"])?;
    Some(repo)
}

/// Git-only fixture 直接组合当前四来源 Review service，不再装配已删除的 Turn tracker。
fn git_only_service(workspace: crate::workspace::WorkspaceHandle) -> ReviewService {
    compose_service(workspace).expect("review service")
}

/// 生成足以让旧实现跨过 eager aggregate patch 上限的 tracked 文本。
fn performance_tracked_text(marker: char, index: usize) -> String {
    format!("{marker}-{index:04}-{}\n", marker.to_string().repeat(4_096))
}

/// 用真实 Git 仓库记录 Review 首开、连续点击和 2,000 文件边界耗时。
///
/// 该测试默认忽略，因为它用于发布前的同机 before/after 性能取证，而不是把机器负载
/// 波动引入普通 correctness suite；核心文件数和 lazy diff 正确性仍使用硬断言收口。
#[test]
#[ignore = "manual Review performance evidence"]
fn review_large_repository_performance_baseline() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let tracked_root = repo.path().join("perf-tracked");
    let untracked_root = repo.path().join("perf-untracked");
    fs::create_dir_all(&tracked_root).expect("create tracked performance directory");
    fs::create_dir_all(&untracked_root).expect("create untracked performance directory");
    for index in 0..500 {
        fs::write(
            tracked_root.join(format!("tracked-{index:04}.txt")),
            performance_tracked_text('a', index),
        )
        .expect("write tracked performance baseline");
    }
    git(repo.path(), &["add", "--", "perf-tracked"]).expect("stage tracked performance baseline");
    git(repo.path(), &["commit", "-q", "-m", "performance baseline"])
        .expect("commit tracked performance baseline");
    for index in 0..500 {
        fs::write(
            tracked_root.join(format!("tracked-{index:04}.txt")),
            performance_tracked_text('b', index),
        )
        .expect("modify tracked performance file");
        fs::write(
            untracked_root.join(format!("untracked-{index:04}.txt")),
            format!("untracked-{index:04}\n"),
        )
        .expect("write untracked performance file");
    }

    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(repo.path())
        .expect("register performance workspace");
    let workspace = registry.get(info.id).expect("performance workspace handle");
    let service = git_only_service(workspace);
    let snapshot_started = Instant::now();
    let snapshot = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("materialize 1,000-file performance snapshot");
    let snapshot_elapsed = snapshot_started.elapsed();
    assert_eq!(snapshot.stats.files, 1_000);
    let selected = snapshot
        .files
        .iter()
        .filter(|file| file.layer == ReviewFileLayer::Unstaged)
        .take(10)
        .cloned()
        .collect::<Vec<_>>();
    assert_eq!(selected.len(), 10);

    let clicks_started = Instant::now();
    let mut click_samples = Vec::with_capacity(selected.len());
    for file in &selected {
        let click_started = Instant::now();
        let diff = service
            .file_diff(
                ReviewSource::Uncommitted,
                &snapshot.revision,
                &file.file_id,
                &CancellationToken::new(),
            )
            .expect("materialize selected performance diff");
        assert_eq!(diff.file.file_id, file.file_id);
        assert!(!diff.file.metadata_only);
        click_samples.push(click_started.elapsed().as_millis());
    }
    let clicks_elapsed = clicks_started.elapsed();
    let mut sorted_click_samples = click_samples.clone();
    sorted_click_samples.sort_unstable();
    let click_p50 = sorted_click_samples[sorted_click_samples.len() / 2];
    let click_p95 = *sorted_click_samples.last().expect("click p95 sample");

    for index in 500..1_500 {
        fs::write(
            untracked_root.join(format!("untracked-{index:04}.txt")),
            format!("untracked-{index:04}\n"),
        )
        .expect("extend untracked performance boundary");
    }
    let boundary_started = Instant::now();
    let boundary = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("materialize 2,000-file performance boundary");
    let boundary_elapsed = boundary_started.elapsed();
    assert_eq!(boundary.stats.files, MAX_REVIEW_SNAPSHOT_FILES as u64);
    eprintln!(
        "JA_REVIEW_PERF snapshot_1000_ms={} clicks_10_ms={} click_samples_ms={:?} click_p50_ms={} click_p95_ms={} snapshot_2000_ms={}",
        snapshot_elapsed.as_millis(),
        clicks_elapsed.as_millis(),
        click_samples,
        click_p50,
        click_p95,
        boundary_elapsed.as_millis()
    );
}

/// 以 100 个父目录复测 metadata guard，防止只在少数目录的理想 fixture 中获得收益。
#[test]
#[ignore = "manual Review scattered-directory performance evidence"]
fn review_scattered_directory_performance_baseline() {
    let Some(repo) = committed_repo() else {
        return;
    };
    for directory in 0..100 {
        let tracked_root = repo.path().join(format!("tracked-{directory:03}"));
        fs::create_dir_all(&tracked_root).expect("create scattered tracked directory");
        for file in 0..5 {
            let index = directory * 5 + file;
            fs::write(
                tracked_root.join(format!("file-{file:02}.txt")),
                performance_tracked_text('a', index),
            )
            .expect("write scattered tracked baseline");
        }
    }
    git(repo.path(), &["add", "--all"]).expect("stage scattered tracked baseline");
    git(
        repo.path(),
        &["commit", "-q", "-m", "scattered performance baseline"],
    )
    .expect("commit scattered tracked baseline");
    for directory in 0..100 {
        let tracked_root = repo.path().join(format!("tracked-{directory:03}"));
        let untracked_root = repo.path().join(format!("untracked-{directory:03}"));
        fs::create_dir_all(&untracked_root).expect("create scattered untracked directory");
        for file in 0..5 {
            let index = directory * 5 + file;
            fs::write(
                tracked_root.join(format!("file-{file:02}.txt")),
                performance_tracked_text('b', index),
            )
            .expect("modify scattered tracked file");
            fs::write(
                untracked_root.join(format!("file-{file:02}.txt")),
                format!("untracked-{index:04}\n"),
            )
            .expect("write scattered untracked file");
        }
    }

    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(repo.path())
        .expect("register scattered performance workspace");
    let workspace = registry.get(info.id).expect("scattered workspace handle");
    let service = git_only_service(workspace);
    let snapshot_started = Instant::now();
    let snapshot = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("materialize scattered snapshot");
    let snapshot_elapsed = snapshot_started.elapsed();
    assert_eq!(snapshot.stats.files, 1_000);
    let selected = snapshot
        .files
        .iter()
        .filter(|file| file.layer == ReviewFileLayer::Unstaged)
        .take(10)
        .cloned()
        .collect::<Vec<_>>();
    let mut samples = Vec::with_capacity(selected.len());
    for file in &selected {
        let started = Instant::now();
        service
            .file_diff(
                ReviewSource::Uncommitted,
                &snapshot.revision,
                &file.file_id,
                &CancellationToken::new(),
            )
            .expect("load scattered selected diff");
        samples.push(started.elapsed().as_millis());
    }
    let mut sorted = samples.clone();
    sorted.sort_unstable();
    eprintln!(
        "JA_REVIEW_PERF_SCATTERED snapshot_1000_ms={} click_samples_ms={:?} click_p50_ms={} click_p95_ms={}",
        snapshot_elapsed.as_millis(),
        samples,
        sorted[sorted.len() / 2],
        sorted.last().expect("scattered p95 sample")
    );
}

/// 树只携带身份与统计；读取未跟踪文件后必须保留该身份，不能误报为截断。
#[test]
fn metadata_tree_defers_patch_and_reads_untracked_with_exact_identity() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::write(repo.path().join("new.txt"), b"new\nline").expect("new file");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register");
    let service = git_only_service(registry.get(info.id).expect("workspace"));
    let snapshot = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("tree");
    let file = snapshot
        .files
        .iter()
        .find(|file| file.path == "new.txt")
        .expect("new entry");
    assert!(!file.diff_loaded);
    assert!(!file.metadata_only);
    assert!(file.patch.is_empty());
    assert!(file.hunks.is_empty());
    assert_eq!(file.additions, Some(2));
    let diff = service
        .file_diff(
            ReviewSource::Uncommitted,
            &snapshot.revision,
            &file.file_id,
            &CancellationToken::new(),
        )
        .expect("read selected");
    assert_eq!(diff.file.file_id, file.file_id);
    assert_eq!(diff.file.layer, ReviewFileLayer::Untracked);
    assert!(diff.file.diff_loaded);
    assert!(!diff.file.patch.is_empty());
}

/// 目标文件即使恢复相同长度与 mtime，缓存命中也必须通过 SHA 发现变化。
#[test]
fn cached_selected_diff_rejects_same_size_content_with_restored_mtime() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let path = repo.path().join("tracked.txt");
    fs::write(&path, b"ONE\ntwo\n").expect("initial edit");
    let modified = fs::metadata(&path)
        .expect("metadata")
        .modified()
        .expect("mtime");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register");
    let service = git_only_service(registry.get(info.id).expect("workspace"));
    let snapshot = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("tree");
    let selected = snapshot
        .files
        .iter()
        .find(|file| file.path == "tracked.txt")
        .expect("selected");
    fs::write(&path, b"TWO\ntwo\n").expect("same size edit");
    fs::File::options()
        .write(true)
        .open(&path)
        .expect("open")
        .set_times(fs::FileTimes::new().set_modified(modified))
        .expect("restore mtime");
    let result = service.file_diff(
        ReviewSource::Uncommitted,
        &snapshot.revision,
        &selected.file_id,
        &CancellationToken::new(),
    );
    assert!(matches!(result, Err(ReviewError::ReviewStale)));
}

/// 全局轻量 manifest 仍检测未选文件的普通变更，而不是只校验当前选中文件。
#[test]
fn cached_diff_rejects_changed_unselected_manifest_entry() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::write(repo.path().join("tracked.txt"), b"ONE\ntwo\n").expect("tracked edit");
    fs::write(repo.path().join("new.txt"), b"first\n").expect("new file");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register");
    let service = git_only_service(registry.get(info.id).expect("workspace"));
    let snapshot = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("tree");
    let selected = snapshot
        .files
        .iter()
        .find(|file| file.path == "tracked.txt")
        .expect("selected");
    fs::write(repo.path().join("new.txt"), b"changed size\n").expect("external edit");
    let result = service.file_diff(
        ReviewSource::Uncommitted,
        &snapshot.revision,
        &selected.file_id,
        &CancellationToken::new(),
    );
    assert!(matches!(result, Err(ReviewError::ReviewStale)));
}

/// 验证 hunk 坐标、additions 与 CRLF/no-final-newline 在解析后保持，不依赖 locale/display format。
#[test]
fn parses_hunk_and_synthetic_untracked_patch() {
    let patch = b"diff --git a/tracked.txt b/tracked.txt\n--- a/tracked.txt\n+++ b/tracked.txt\n@@ -1,2 +1,2 @@\n-one\r\n+ONE\r\n\\ No newline at end of file\n";
    let files = parse_diff(patch).expect("parse patch");
    assert_eq!(files.len(), 1);
    assert_eq!(files[0].additions, Some(1));
    assert_eq!(files[0].deletions, Some(1));
    assert_eq!(files[0].hunks[0].old_start, 1);
    assert_eq!(files[0].hunks[0].new_lines, 2);

    let synthetic = synthetic_added_patch("目录/新文件.txt", "新内容\n".as_bytes());
    assert_eq!(synthetic.new_path.as_deref(), Some("目录/新文件.txt"));
    assert_eq!(synthetic.additions, Some(1));
    assert!(!synthetic.hunks[0].raw_patch.is_empty());

    let quoted = br#"diff --git "a/quoted \"name\".txt" "b/quoted \"name\".txt"
--- "a/quoted \"name\".txt"
+++ "b/quoted \"name\".txt"
@@ -1 +1 @@
-before
+after
"#;
    let quoted_files = parse_diff(quoted).expect("parse quoted path patch");
    assert_eq!(
        quoted_files[0].new_path.as_deref(),
        Some("quoted \"name\".txt")
    );
}

/// 领域预算在精确边界仍允许正常仓库，并把文件数、字节数和算术溢出统一映射到 REVIEW_LIMIT。
#[test]
fn snapshot_budget_enforces_interactive_file_and_byte_boundaries() {
    assert!(
        enforce_snapshot_budget(
            MAX_REVIEW_SNAPSHOT_FILES,
            MAX_REVIEW_SNAPSHOT_BYTES,
            std::iter::empty()
        )
        .is_ok()
    );
    assert!(matches!(
        enforce_snapshot_budget(MAX_REVIEW_SNAPSHOT_FILES + 1, 0, std::iter::empty()),
        Err(ReviewError::OutputLimitExceeded)
    ));
    assert!(matches!(
        enforce_snapshot_budget(1, MAX_REVIEW_SNAPSHOT_BYTES, [1]),
        Err(ReviewError::OutputLimitExceeded)
    ));
    assert!(matches!(
        enforce_snapshot_budget(1, u64::MAX, [1]),
        Err(ReviewError::OutputLimitExceeded)
    ));
}

/// 超出总字节预算的 untracked 文件必须在正文读取前失败，不能退化成 metadata-only 大 payload。
#[test]
fn snapshot_preflight_rejects_oversized_untracked_total() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let oversized = fs::File::create(repo.path().join("oversized-untracked.bin"))
        .expect("create oversized untracked fixture");
    oversized
        .set_len(MAX_REVIEW_SNAPSHOT_BYTES + 1)
        .expect("extend oversized untracked fixture");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");

    assert!(matches!(
        git_only_service(workspace).snapshot(ReviewSource::Unstaged, &CancellationToken::new()),
        Err(ReviewError::OutputLimitExceeded)
    ));
}

/// staged 与 untracked 各自未超限时仍必须共享一次聚合文件预算，不能按层获得两份额度。
#[test]
fn uncommitted_snapshot_enforces_one_file_budget_across_layers() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let staged_count = MAX_REVIEW_SNAPSHOT_FILES / 2 + 1;
    let untracked_count = MAX_REVIEW_SNAPSHOT_FILES - staged_count + 1;
    fs::create_dir_all(repo.path().join("staged-budget")).expect("create staged budget directory");
    fs::create_dir_all(repo.path().join("untracked-budget"))
        .expect("create untracked budget directory");
    for index in 0..staged_count {
        fs::write(
            repo.path()
                .join("staged-budget")
                .join(format!("{index:04}.txt")),
            b"s\n",
        )
        .expect("write staged budget fixture");
    }
    git(repo.path(), &["add", "--", "staged-budget"]).expect("stage budget fixtures");
    for index in 0..untracked_count {
        fs::write(
            repo.path()
                .join("untracked-budget")
                .join(format!("{index:04}.txt")),
            b"u\n",
        )
        .expect("write untracked budget fixture");
    }
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");

    assert!(matches!(
        git_only_service(workspace).snapshot(ReviewSource::Uncommitted, &CancellationToken::new()),
        Err(ReviewError::OutputLimitExceeded)
    ));
}

/// 首次读取后取消 token 的 reader，用于证明分块读取不会等到整个文件完成才观察取消。
struct CancelAfterFirstChunk {
    cancellation: CancellationToken,
    first: bool,
}

impl Read for CancelAfterFirstChunk {
    /// 只返回一个首块并立即取消；第二次 `read` 表示生产循环漏掉了块间取消检查。
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if !self.first {
            panic!("reader must not be polled after cancellation");
        }
        self.first = false;
        buffer[0] = 1;
        self.cancellation.cancel();
        Ok(1)
    }
}

/// 有界读取循环必须在首块后返回 Cancelled，而不是继续读取至单文件上限。
#[test]
fn bounded_untracked_read_observes_cancellation_between_chunks() {
    let cancellation = CancellationToken::new();
    let reader = CancelAfterFirstChunk {
        cancellation: cancellation.clone(),
        first: true,
    };
    assert!(matches!(
        read_bounded_with_cancellation(reader, 1024, &cancellation),
        Err(ReviewError::Cancelled)
    ));
}

/// 在 Windows-compatible temp worktree 验证真实 Git status、untracked synthesis、stale、Stage/Unstage。
#[test]
fn review_service_handles_untracked_stage_unstage_and_stale() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::write(repo.path().join("tracked.txt"), b"one\r\nchanged").expect("edit tracked");
    fs::write(repo.path().join("目录-新文件.txt"), "新文件\n").expect("write untracked");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let source = ReviewSource::Unstaged;
    let snapshot = service
        .snapshot(source.clone(), &CancellationToken::new())
        .expect("unstaged snapshot");
    assert!(snapshot.files.iter().any(|file| file.path == "tracked.txt"));
    assert!(
        snapshot
            .files
            .iter()
            .any(|file| file.path == "目录-新文件.txt")
    );
    let stale_revision = snapshot.revision.clone();
    fs::write(repo.path().join("tracked.txt"), b"one\r\nchanged-again").expect("race edit");
    let stale_target = ReviewTarget::All;
    let stale = service.apply(
        source.clone(),
        &stale_revision,
        ReviewAction::Stage,
        stale_target,
        &CancellationToken::new(),
    );
    assert!(matches!(
        stale,
        Err(crate::review::ReviewError::ReviewStale)
    ));

    let current = service
        .snapshot(source.clone(), &CancellationToken::new())
        .expect("fresh unstaged snapshot");
    let tracked = current
        .files
        .iter()
        .find(|file| file.path == "tracked.txt")
        .expect("tracked file");
    let staged = service
        .apply(
            source,
            &current.revision,
            ReviewAction::Stage,
            ReviewTarget::File {
                file_id: tracked.file_id.clone(),
            },
            &CancellationToken::new(),
        )
        .expect("stage file");
    let staged_snapshot = service
        .snapshot(ReviewSource::Staged, &CancellationToken::new())
        .expect("staged snapshot");
    assert!(
        staged_snapshot
            .files
            .iter()
            .any(|file| file.path == "tracked.txt")
    );
    service
        .apply(
            ReviewSource::Staged,
            &staged_snapshot.revision,
            ReviewAction::Unstage,
            ReviewTarget::All,
            &CancellationToken::new(),
        )
        .expect("unstage all");
    let after = service
        .snapshot(ReviewSource::Staged, &CancellationToken::new())
        .expect("staged after unstage");
    assert!(after.files.is_empty());
    assert!(!staged.snapshot.files.is_empty() || !staged.snapshot.stats.files.eq(&0));
}

/// 聚合快照保留同路径两层 identity，文件统计去重而行统计逐层累积。
#[test]
fn uncommitted_snapshot_preserves_partial_staging_layers_and_routes_file_actions() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::write(repo.path().join("tracked.txt"), b"ONE\ntwo\n").expect("write staged edit");
    git(repo.path(), &["add", "--", "tracked.txt"]).expect("stage first edit");
    fs::write(repo.path().join("tracked.txt"), b"ONE\nTWO\n").expect("write unstaged edit");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);

    let snapshot = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("uncommitted snapshot");
    let staged = snapshot
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Staged)
        .expect("staged layer");
    let unstaged = snapshot
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Unstaged)
        .expect("unstaged layer");
    assert_eq!(staged.path, "tracked.txt");
    assert_eq!(unstaged.path, "tracked.txt");
    assert_ne!(staged.file_id, unstaged.file_id);
    assert_eq!(snapshot.stats.files, 1);
    assert_eq!(snapshot.stats.additions, 2);
    assert_eq!(snapshot.stats.deletions, 2);

    let staged_id = staged.file_id.clone();
    let after = service
        .apply(
            ReviewSource::Uncommitted,
            &snapshot.revision,
            ReviewAction::Unstage,
            ReviewTarget::File { file_id: staged_id },
            &CancellationToken::new(),
        )
        .expect("route aggregate unstage to index layer")
        .snapshot;
    assert!(
        after
            .files
            .iter()
            .all(|file| file.layer != ReviewFileLayer::Staged)
    );
    assert!(
        after
            .files
            .iter()
            .any(|file| file.layer == ReviewFileLayer::Unstaged)
    );
}

/// unborn 仓库仍按 index/worktree 权威层聚合，不能把缺少 HEAD 误报为比较失败。
#[test]
fn uncommitted_snapshot_supports_unborn_repository_and_untracked_layer() {
    let Some(repo) = TempRepo::create() else {
        return;
    };
    git(repo.path(), &["init", "-q"]).expect("init unborn repo");
    fs::write(repo.path().join("staged.txt"), b"staged\n").expect("write staged fixture");
    git(repo.path(), &["add", "--", "staged.txt"]).expect("stage unborn fixture");
    fs::write(repo.path().join("未跟踪 文件.txt"), "内容\n").expect("write untracked fixture");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");

    let snapshot = git_only_service(workspace)
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("unborn uncommitted snapshot");
    assert_eq!(snapshot.stats.files, 2);
    assert!(
        snapshot
            .files
            .iter()
            .any(|file| { file.path == "staged.txt" && file.layer == ReviewFileLayer::Staged })
    );
    assert!(snapshot.files.iter().any(|file| {
        file.path == "未跟踪 文件.txt" && file.layer == ReviewFileLayer::Untracked
    }));
}

/// 空格与 Unicode path 必须精确关联各自 quoted patch；删除项也必须保持自身状态与正文。
#[test]
fn uncommitted_snapshot_matches_special_and_deleted_paths_without_diff_fallback() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let special = "目录/quoted name.txt";
    fs::create_dir_all(repo.path().join("目录")).expect("create special directory");
    fs::write(repo.path().join(special), b"before\n").expect("write special baseline");
    fs::write(repo.path().join("deleted.txt"), b"delete\n").expect("write delete baseline");
    git(repo.path(), &["add", "--all"]).expect("stage baselines");
    git(repo.path(), &["commit", "-q", "-m", "special baselines"]).expect("commit baselines");
    fs::write(repo.path().join(special), b"after\n").expect("edit special path");
    fs::remove_file(repo.path().join("deleted.txt")).expect("delete tracked path");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");

    let snapshot = git_only_service(workspace)
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("special path snapshot");
    let special_file = snapshot
        .files
        .iter()
        .find(|file| file.path == special)
        .expect("special path entry");
    let deleted = snapshot
        .files
        .iter()
        .find(|file| file.path == "deleted.txt")
        .expect("deleted entry");
    assert_eq!(special_file.status, ReviewFileStatus::Modified);
    assert_eq!(special_file.additions, Some(1));
    assert_eq!(special_file.deletions, Some(1));
    assert_eq!(deleted.status, ReviewFileStatus::Deleted);
    assert_ne!(special_file.revision_evidence, deleted.revision_evidence);
}

/// 生成单文件低于 lazy 上限、双文件合计超过聚合 eager 上限的文本，避免用超限单文件伪造场景。
fn large_text_fixture(marker: &str) -> String {
    let padding = "x".repeat(56);
    (0..9_000)
        .map(|index| format!("{marker}-{index:04}-{padding}\n"))
        .collect()
}

/// 大聚合 patch 的第二个 metadata-only 文件 lazy 展开后必须保留原选择身份并匹配自身正文。
#[test]
fn uncommitted_lazy_file_diff_preserves_snapshot_identity_for_large_aggregate() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let before = large_text_fixture("before");
    fs::write(repo.path().join("large-a.txt"), &before).expect("write first large baseline");
    fs::write(repo.path().join("large-b.txt"), &before).expect("write second large baseline");
    git(repo.path(), &["add", "--all"]).expect("stage large baselines");
    git(repo.path(), &["commit", "-q", "-m", "large baselines"]).expect("commit large baselines");
    let after_a = large_text_fixture("after-a");
    let after_b = large_text_fixture("after-b");
    fs::write(repo.path().join("large-a.txt"), &after_a).expect("edit first large file");
    fs::write(repo.path().join("large-b.txt"), &after_b).expect("edit second large file");

    let aggregate_patch = git(repo.path(), &["diff", "--binary", "--no-ext-diff"])
        .expect("read aggregate patch size");
    assert!(aggregate_patch.len() > crate::review::domain::MAX_REVIEW_DIFF_BYTES);
    let selected_patch = git(
        repo.path(),
        &["diff", "--binary", "--no-ext-diff", "--", "large-b.txt"],
    )
    .expect("read selected patch size");
    assert!(selected_patch.len() < crate::review::domain::MAX_REVIEW_DIFF_BYTES);

    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let snapshot = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("large aggregate snapshot");
    let selected = snapshot
        .files
        .iter()
        .find(|file| file.path == "large-b.txt")
        .expect("second metadata-only file")
        .clone();
    assert!(!selected.metadata_only);
    assert!(!selected.diff_loaded);

    let diff = service
        .file_diff(
            ReviewSource::Uncommitted,
            &snapshot.revision,
            &selected.file_id,
            &CancellationToken::new(),
        )
        .expect("lazy selected file diff");
    assert_eq!(diff.file.file_id, selected.file_id);
    assert_eq!(diff.file.layer, selected.layer);
    assert_eq!(diff.file.path, selected.path);
    assert_eq!(diff.file.old_path, selected.old_path);
    assert_eq!(diff.file.status, selected.status);
    assert!(!diff.file.metadata_only);
    assert_eq!(diff.file.additions, Some(9_000));
    assert_eq!(diff.file.deletions, Some(9_000));
    assert!(
        diff.file
            .hunks
            .iter()
            .flat_map(|hunk| &hunk.lines)
            .any(|line| line.text.contains("after-b"))
    );
    assert!(
        diff.file
            .hunks
            .iter()
            .flat_map(|hunk| &hunk.lines)
            .all(|line| !line.text.contains("after-a"))
    );
}

/// 验证 operation 在进入真实 Git 查询前已取消时，同一原生 token 不会丢失信号。
#[test]
fn cancelled_operation_reaches_native_git_boundary() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let operation_id =
        ReviewOperationId::parse(format!("cancel-{}", Uuid::new_v4())).expect("operation id");
    let operation = ReviewService::operation(Some(operation_id.clone())).expect("operation");
    let cancellation = operation.cancellation();
    assert!(ReviewService::cancel_operation(&operation_id).expect("cancel operation"));
    assert!(matches!(
        service.snapshot(ReviewSource::Unstaged, &cancellation),
        Err(ReviewError::Cancelled)
    ));
}

/// 验证 rename 两侧的 temporary-index Stage/Unstage 与 compound revert，并确认无 index temp 残留。
#[test]
fn rename_all_stage_unstage_and_revert_is_transactional() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::rename(
        repo.path().join("tracked.txt"),
        repo.path().join("renamed.txt"),
    )
    .expect("rename fixture file");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let unstaged = service
        .snapshot(ReviewSource::Unstaged, &CancellationToken::new())
        .expect("renamed unstaged snapshot");
    service
        .apply(
            ReviewSource::Unstaged,
            &unstaged.revision,
            ReviewAction::Stage,
            ReviewTarget::All,
            &CancellationToken::new(),
        )
        .expect("stage rename");
    let staged = service
        .snapshot(ReviewSource::Staged, &CancellationToken::new())
        .expect("staged rename snapshot");
    assert!(staged.files.iter().any(|file| {
        file.path == "renamed.txt" && file.old_path.as_deref() == Some("tracked.txt")
    }));
    service
        .apply(
            ReviewSource::Staged,
            &staged.revision,
            ReviewAction::Unstage,
            ReviewTarget::All,
            &CancellationToken::new(),
        )
        .expect("unstage rename");
    assert!(repo.path().join("renamed.txt").is_file());
    assert!(!repo.path().join("tracked.txt").exists());

    let unstaged_again = service
        .snapshot(ReviewSource::Unstaged, &CancellationToken::new())
        .expect("unstaged rename again");
    service
        .apply(
            ReviewSource::Unstaged,
            &unstaged_again.revision,
            ReviewAction::Stage,
            ReviewTarget::All,
            &CancellationToken::new(),
        )
        .expect("restage rename");
    let staged_again = service
        .snapshot(ReviewSource::Staged, &CancellationToken::new())
        .expect("restaged rename snapshot");
    service
        .apply(
            ReviewSource::Staged,
            &staged_again.revision,
            ReviewAction::Revert,
            ReviewTarget::All,
            &CancellationToken::new(),
        )
        .expect("revert staged rename");
    assert!(repo.path().join("tracked.txt").is_file());
    assert!(!repo.path().join("renamed.txt").exists());
    let leftovers = fs::read_dir(repo.path().join(".git"))
        .expect("read git metadata")
        .filter_map(Result::ok)
        .filter(|entry| {
            entry
                .file_name()
                .to_string_lossy()
                .starts_with(".ja-review-index-")
        })
        .count();
    assert_eq!(leftovers, 0);
}

/// 构造产生独立 hunk 的 committed multi-line file，以验证 index/worktree composition。
fn committed_multihunk_repo() -> Option<(TempRepo, Vec<String>)> {
    let repo = committed_repo()?;
    let lines = (1..=12)
        .map(|line| format!("line-{line}"))
        .collect::<Vec<_>>();
    let content = format!("{}\n", lines.join("\n"));
    fs::write(repo.path().join("tracked.txt"), content).ok()?;
    git(repo.path(), &["add", "--all"])?;
    git(repo.path(), &["commit", "-q", "-m", "multihunk"])?;
    Some((repo, lines))
}

/// 聚合来源的 hunk stage/unstage 必须只移动所选层，worktree 正文始终保持用户当前内容。
#[test]
fn uncommitted_hunk_stage_and_unstage_route_to_selected_layer() {
    let Some((repo, mut content)) = committed_multihunk_repo() else {
        return;
    };
    content[1] = "staged-change".to_owned();
    fs::write(
        repo.path().join("tracked.txt"),
        format!("{}\n", content.join("\n")),
    )
    .expect("write staged hunk fixture");
    git(repo.path(), &["add", "--", "tracked.txt"]).expect("stage first hunk");
    content[9] = "worktree-change".to_owned();
    let current_worktree = format!("{}\n", content.join("\n")).into_bytes();
    fs::write(repo.path().join("tracked.txt"), &current_worktree)
        .expect("write unstaged hunk fixture");

    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let initial = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("initial aggregate snapshot");
    let unstaged = initial
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Unstaged)
        .expect("unstaged layer")
        .clone();
    let unstaged_diff = service
        .file_diff(
            ReviewSource::Uncommitted,
            &initial.revision,
            &unstaged.file_id,
            &CancellationToken::new(),
        )
        .expect("load unstaged hunk");
    let unstaged_hunk = unstaged_diff.file.hunks.first().expect("unstaged hunk");
    let after_stage = service
        .apply(
            ReviewSource::Uncommitted,
            &initial.revision,
            ReviewAction::Stage,
            ReviewTarget::Hunk {
                file_id: unstaged.file_id.clone(),
                hunk_id: unstaged_hunk.hunk_id.clone(),
            },
            &CancellationToken::new(),
        )
        .expect("stage aggregate unstaged hunk")
        .snapshot;
    assert!(
        after_stage
            .files
            .iter()
            .all(|file| file.layer != ReviewFileLayer::Unstaged)
    );
    let staged = after_stage
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Staged)
        .expect("combined staged layer")
        .clone();
    let staged_diff = service
        .file_diff(
            ReviewSource::Uncommitted,
            &after_stage.revision,
            &staged.file_id,
            &CancellationToken::new(),
        )
        .expect("load combined staged hunks");
    let moved_hunk = staged_diff
        .file
        .hunks
        .iter()
        .max_by_key(|hunk| hunk.old_start)
        .expect("second staged hunk");
    let after_unstage = service
        .apply(
            ReviewSource::Uncommitted,
            &after_stage.revision,
            ReviewAction::Unstage,
            ReviewTarget::Hunk {
                file_id: staged.file_id.clone(),
                hunk_id: moved_hunk.hunk_id.clone(),
            },
            &CancellationToken::new(),
        )
        .expect("unstage aggregate staged hunk")
        .snapshot;

    assert!(
        after_unstage
            .files
            .iter()
            .any(|file| { file.layer == ReviewFileLayer::Staged && file.path == "tracked.txt" })
    );
    assert!(
        after_unstage
            .files
            .iter()
            .any(|file| { file.layer == ReviewFileLayer::Unstaged && file.path == "tracked.txt" })
    );
    let cached = git(repo.path(), &["diff", "--cached", "--", "tracked.txt"])
        .expect("read cached diff after hunk routing");
    let unstaged_diff = git(repo.path(), &["diff", "--", "tracked.txt"])
        .expect("read worktree diff after hunk routing");
    assert!(String::from_utf8_lossy(&cached).contains("staged-change"));
    assert!(!String::from_utf8_lossy(&cached).contains("worktree-change"));
    assert!(String::from_utf8_lossy(&unstaged_diff).contains("worktree-change"));
    assert_eq!(
        fs::read(repo.path().join("tracked.txt")).expect("read current worktree"),
        current_worktree
    );
}

/// 聚合来源的 hunk revert 必须按层逐个消除，并在 staged revert 后同步 index 与 worktree。
#[test]
fn uncommitted_hunk_revert_routes_to_selected_layer() {
    let Some((repo, mut content)) = committed_multihunk_repo() else {
        return;
    };
    content[1] = "staged-change".to_owned();
    fs::write(
        repo.path().join("tracked.txt"),
        format!("{}\n", content.join("\n")),
    )
    .expect("write staged hunk fixture");
    git(repo.path(), &["add", "--", "tracked.txt"]).expect("stage first hunk");
    content[9] = "worktree-change".to_owned();
    fs::write(
        repo.path().join("tracked.txt"),
        format!("{}\n", content.join("\n")),
    )
    .expect("write unstaged hunk fixture");

    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let initial = service
        .snapshot(ReviewSource::Uncommitted, &CancellationToken::new())
        .expect("initial aggregate snapshot");
    let unstaged = initial
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Unstaged)
        .expect("unstaged layer")
        .clone();
    let unstaged_diff = service
        .file_diff(
            ReviewSource::Uncommitted,
            &initial.revision,
            &unstaged.file_id,
            &CancellationToken::new(),
        )
        .expect("load unstaged hunk");
    let after_unstaged_revert = service
        .apply(
            ReviewSource::Uncommitted,
            &initial.revision,
            ReviewAction::Revert,
            ReviewTarget::Hunk {
                file_id: unstaged.file_id.clone(),
                hunk_id: unstaged_diff.file.hunks[0].hunk_id.clone(),
            },
            &CancellationToken::new(),
        )
        .expect("revert aggregate unstaged hunk")
        .snapshot;
    assert!(
        after_unstaged_revert
            .files
            .iter()
            .all(|file| file.layer != ReviewFileLayer::Unstaged)
    );
    let staged = after_unstaged_revert
        .files
        .iter()
        .find(|file| file.layer == ReviewFileLayer::Staged)
        .expect("remaining staged layer")
        .clone();
    let staged_diff = service
        .file_diff(
            ReviewSource::Uncommitted,
            &after_unstaged_revert.revision,
            &staged.file_id,
            &CancellationToken::new(),
        )
        .expect("load staged hunk");
    let after_staged_revert = service
        .apply(
            ReviewSource::Uncommitted,
            &after_unstaged_revert.revision,
            ReviewAction::Revert,
            ReviewTarget::Hunk {
                file_id: staged.file_id.clone(),
                hunk_id: staged_diff.file.hunks[0].hunk_id.clone(),
            },
            &CancellationToken::new(),
        )
        .expect("revert aggregate staged hunk")
        .snapshot;
    assert!(after_staged_revert.files.is_empty());
    let baseline = (1..=12)
        .map(|line| format!("line-{line}"))
        .collect::<Vec<_>>();
    assert_eq!(
        fs::read(repo.path().join("tracked.txt")).expect("read reverted worktree"),
        format!("{}\n", baseline.join("\n")).into_bytes()
    );
    assert!(
        git(repo.path(), &["diff", "--cached", "--", "tracked.txt"])
            .expect("read empty cached diff")
            .is_empty()
    );
}

/// 在两层 Git state 中 revert staged hunk，并保留独立 non-overlapping worktree edit。
#[test]
fn staged_hunk_revert_preserves_non_overlapping_worktree_change() {
    let Some((repo, mut baseline)) = committed_multihunk_repo() else {
        return;
    };
    baseline[1] = "staged-change".to_owned();
    fs::write(
        repo.path().join("tracked.txt"),
        format!("{}\n", baseline.join("\n")),
    )
    .expect("stage hunk fixture");
    git(repo.path(), &["add", "--", "tracked.txt"]).expect("stage fixture");
    baseline[9] = "worktree-change".to_owned();
    fs::write(
        repo.path().join("tracked.txt"),
        format!("{}\n", baseline.join("\n")),
    )
    .expect("independent worktree fixture");

    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let staged = service
        .snapshot(ReviewSource::Staged, &CancellationToken::new())
        .expect("staged snapshot");
    let file = staged.files.first().expect("staged file").clone();
    let diff = service
        .file_diff(
            ReviewSource::Staged,
            &staged.revision,
            &file.file_id,
            &CancellationToken::new(),
        )
        .expect("load staged hunk");
    let hunk = diff.file.hunks.first().expect("staged hunk");
    service
        .apply(
            ReviewSource::Staged,
            &staged.revision,
            ReviewAction::Revert,
            ReviewTarget::Hunk {
                file_id: file.file_id.clone(),
                hunk_id: hunk.hunk_id.clone(),
            },
            &CancellationToken::new(),
        )
        .expect("revert staged hunk");

    let mut expected = (1..=12)
        .map(|line| format!("line-{line}"))
        .collect::<Vec<_>>();
    expected[9] = "worktree-change".to_owned();
    assert_eq!(
        fs::read(repo.path().join("tracked.txt")).expect("worktree bytes"),
        format!("{}\n", expected.join("\n")).into_bytes()
    );
    assert!(
        git(repo.path(), &["diff", "--cached", "--", "tracked.txt"])
            .expect("cached diff")
            .is_empty()
    );
    assert!(
        !git(repo.path(), &["diff", "--", "tracked.txt"])
            .expect("worktree diff")
            .is_empty()
    );
}

/// 拒绝 inverse 与新 worktree edit overlap 的 staged hunk，并证明 index/worktree 均未改变。
#[test]
fn staged_hunk_revert_overlap_is_conflict_without_writes() {
    let Some((repo, mut baseline)) = committed_multihunk_repo() else {
        return;
    };
    baseline[1] = "staged-change".to_owned();
    fs::write(
        repo.path().join("tracked.txt"),
        format!("{}\n", baseline.join("\n")),
    )
    .expect("stage hunk fixture");
    git(repo.path(), &["add", "--", "tracked.txt"]).expect("stage fixture");
    baseline[1] = "overlapping-worktree-change".to_owned();
    let worktree_bytes = format!("{}\n", baseline.join("\n")).into_bytes();
    fs::write(repo.path().join("tracked.txt"), &worktree_bytes).expect("overlap fixture");

    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let staged = service
        .snapshot(ReviewSource::Staged, &CancellationToken::new())
        .expect("staged snapshot");
    let file = staged.files.first().expect("staged file").clone();
    let diff = service
        .file_diff(
            ReviewSource::Staged,
            &staged.revision,
            &file.file_id,
            &CancellationToken::new(),
        )
        .expect("load staged hunk");
    let hunk = diff.file.hunks.first().expect("staged hunk");
    let result = service.apply(
        ReviewSource::Staged,
        &staged.revision,
        ReviewAction::Revert,
        ReviewTarget::Hunk {
            file_id: file.file_id.clone(),
            hunk_id: hunk.hunk_id.clone(),
        },
        &CancellationToken::new(),
    );
    assert!(matches!(result, Err(crate::review::ReviewError::Conflict)));
    assert_eq!(
        fs::read(repo.path().join("tracked.txt")).expect("worktree bytes"),
        worktree_bytes
    );
    assert!(
        !git(repo.path(), &["diff", "--cached", "--", "tracked.txt"])
            .expect("cached diff")
            .is_empty()
    );
}

/// 验证 root commit 与 branch catalog，同时确保 historical source 不获得写 capability。
#[test]
fn catalog_and_root_commit_are_read_only() {
    let Some(repo) = committed_repo() else {
        return;
    };
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let service = git_only_service(workspace);
    let catalog = service.catalog(&CancellationToken::new()).expect("catalog");
    let commit = catalog.commits.first().expect("initial commit");
    let snapshot = service
        .snapshot(
            ReviewSource::Commit {
                commit_id: ReviewCommitId::parse(commit.commit_id.clone()).expect("commit id"),
            },
            &CancellationToken::new(),
        )
        .expect("root commit snapshot");
    assert!(!snapshot.files.is_empty());
    let result = service.apply(
        ReviewSource::Commit {
            commit_id: ReviewCommitId::parse(commit.commit_id.clone()).expect("commit id"),
        },
        &snapshot.revision,
        ReviewAction::Revert,
        ReviewTarget::All,
        &CancellationToken::new(),
    );
    assert!(matches!(
        result,
        Err(crate::review::ReviewError::ReadOnlySource)
    ));
}

/// 用真实双提交日志锁定 Catalog 的 NUL 记录边界，避免 Git transport 换行进入后续 commit id。
#[test]
fn catalog_commit_ids_exclude_transport_separators() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::write(repo.path().join("tracked.txt"), b"one\ntwo\nthree\n")
        .expect("update second commit fixture");
    git(repo.path(), &["add", "--all"]).expect("stage second commit fixture");
    git(repo.path(), &["commit", "-q", "-m", "second"]).expect("create second commit");
    let expected = String::from_utf8(
        git(repo.path(), &["rev-list", "--max-count=2", "HEAD"]).expect("read expected commits"),
    )
    .expect("commit ids are utf-8")
    .lines()
    .map(str::to_owned)
    .collect::<Vec<_>>();

    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let catalog = git_only_service(workspace)
        .catalog(&CancellationToken::new())
        .expect("catalog");
    let actual = catalog
        .commits
        .iter()
        .take(2)
        .map(|commit| commit.commit_id.clone())
        .collect::<Vec<_>>();

    assert_eq!(actual, expected);
    assert!(
        actual
            .iter()
            .all(|commit_id| !commit_id.chars().any(char::is_control))
    );
}

/// 枚举 local/remote ref 并让 conventional base 优先，React 无需制造 Git ref。
#[test]
fn catalog_lists_bounded_branch_base_candidates_in_priority_order() {
    let Some(repo) = committed_repo() else {
        return;
    };
    git(repo.path(), &["branch", "feature-z"]).expect("feature branch");
    git(
        repo.path(),
        &["update-ref", "refs/remotes/origin/HEAD", "HEAD"],
    )
    .expect("origin head ref");
    git(
        repo.path(),
        &["update-ref", "refs/remotes/origin/feature-z", "HEAD"],
    )
    .expect("origin feature ref");
    git(repo.path(), &["update-ref", "refs/heads/main", "HEAD"]).expect("main ref");
    git(repo.path(), &["update-ref", "refs/heads/master", "HEAD"]).expect("master ref");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let catalog = git_only_service(workspace)
        .catalog(&CancellationToken::new())
        .expect("catalog");
    let refs = catalog
        .base_refs
        .iter()
        .map(|item| item.ref_id.as_str())
        .collect::<Vec<_>>();
    assert_eq!(
        refs.first().copied(),
        Some("origin/HEAD"),
        "catalog refs: {refs:?}"
    );
    assert_eq!(refs.get(1).copied(), Some("main"));
    assert_eq!(refs.get(2).copied(), Some("master"));
    assert!(refs.contains(&"feature-z"));
    assert!(refs.contains(&"origin/feature-z"));
}
