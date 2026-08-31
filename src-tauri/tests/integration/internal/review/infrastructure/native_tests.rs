// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::TurnChangeBaseline;
use super::parse::{parse_diff, synthetic_added_patch};
use crate::review::TurnChangeSet;
use crate::review::domain::TurnChangeUnavailableReason;
use crate::review::domain::{
    ReviewAction, ReviewCommitId, ReviewFileStatus, ReviewOperationId, ReviewRevision,
    ReviewSource, ReviewTarget,
};
use crate::review::{ReviewError, ReviewService, compose_service};
use crate::workspace::WorkspaceRegistry;
use sha2::Digest;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
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
    let file = staged.files.first().expect("staged file");
    let hunk = file.hunks.first().expect("staged hunk");
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
    let file = staged.files.first().expect("staged file");
    let hunk = file.hunks.first().expect("staged hunk");
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

/// 证明 dirty baseline 在两棵树中相消，并覆盖 rename/delete/untracked/binary 与真实 index 不变。
#[test]
fn turn_change_baseline_materializes_only_changes_after_start() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::write(repo.path().join("deleted.txt"), b"delete-me\n").expect("write delete fixture");
    git(repo.path(), &["add", "--all"]).expect("stage delete fixture");
    git(
        repo.path(),
        &["commit", "-q", "-m", "turn baseline fixture"],
    )
    .expect("commit turn fixture");

    fs::write(repo.path().join("tracked.txt"), b"dirty-before-turn\n")
        .expect("write preexisting dirty file");
    git(repo.path(), &["add", "--", "tracked.txt"]).expect("stage preexisting dirty file");
    fs::write(
        repo.path().join("preexisting.txt"),
        b"rename-line-1\nrename-line-2\nrename-line-3\nrename-line-4\n",
    )
    .expect("write preexisting untracked file");
    let cached_before =
        git(repo.path(), &["diff", "--cached", "--binary"]).expect("read cached baseline");

    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let baseline = TurnChangeBaseline::capture("ws_turn_test".to_owned(), workspace);

    fs::write(repo.path().join("tracked.txt"), b"changed-by-turn\n")
        .expect("modify tracked during turn");
    fs::rename(
        repo.path().join("preexisting.txt"),
        repo.path().join("renamed.txt"),
    )
    .expect("rename during turn");
    fs::write(
        repo.path().join("renamed.txt"),
        b"rename-line-1\nrename-line-2-updated\nrename-line-3\nrename-line-4\n",
    )
    .expect("modify renamed file during turn");
    fs::remove_file(repo.path().join("deleted.txt")).expect("delete during turn");
    fs::write(repo.path().join("binary.bin"), [0_u8, 1, 2, 3]).expect("write binary during turn");

    let change_set = baseline.finish();
    let TurnChangeSet::Available {
        files,
        stats,
        artifact,
    } = change_set
    else {
        panic!("Git turn change-set should be available");
    };
    assert_eq!(stats.files, 4);
    assert_eq!(stats.binary_files, 1);
    assert!(!stats.truncated);
    assert!(
        files.iter().any(|file| {
            file.path == "tracked.txt" && file.status == ReviewFileStatus::Modified
        })
    );
    assert!(files.iter().any(|file| {
        file.path == "renamed.txt"
            && file.old_path.as_deref() == Some("preexisting.txt")
            && file.status == ReviewFileStatus::Renamed
            && file.additions == Some(1)
            && file.deletions == Some(1)
    }));
    assert!(
        files
            .iter()
            .any(|file| { file.path == "deleted.txt" && file.status == ReviewFileStatus::Deleted })
    );
    assert!(files.iter().any(|file| {
        file.path == "binary.bin" && file.status == ReviewFileStatus::Added && file.binary
    }));
    let artifact = artifact.expect("bounded frozen diff artifact");
    assert_eq!(artifact.byte_length, artifact.unified_diff.len() as u64);
    assert_eq!(
        artifact.sha256,
        sha2::Sha256::digest(artifact.unified_diff.as_bytes())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    );
    assert!(artifact.unified_diff.contains("tracked.txt"));
    assert_eq!(
        git(repo.path(), &["diff", "--cached", "--binary"]).expect("read cached final"),
        cached_before,
        "temporary baseline index must never publish to the real index"
    );
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

/// 证明 pre-dirty 与 untracked 工作区在只读 Turn 后仍返回可靠零修改，并回收全部 temporary index。
#[test]
fn turn_change_baseline_returns_available_zero_when_workspace_is_unchanged() {
    let Some(repo) = committed_repo() else {
        return;
    };
    fs::write(repo.path().join("tracked.txt"), b"dirty-before-turn\n")
        .expect("write dirty tracked fixture");
    fs::write(repo.path().join("untracked.txt"), b"preexisting\n")
        .expect("write untracked fixture");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(repo.path()).expect("register workspace");
    let workspace = registry.get(info.id).expect("workspace handle");

    let change_set = TurnChangeBaseline::capture("ws_zero_test".to_owned(), workspace).finish();
    let TurnChangeSet::Available {
        files,
        stats,
        artifact,
    } = change_set
    else {
        panic!("unchanged Git worktree should have a reliable zero change-set");
    };
    assert!(files.is_empty());
    assert_eq!(stats.files, 0);
    assert_eq!(stats.additions, 0);
    assert_eq!(stats.deletions, 0);
    assert!(artifact.is_none());
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

/// 非 Git 与同 Workspace 并发必须是显式 unavailable，不能以空 available 伪装零修改。
#[test]
fn turn_change_baseline_reports_unavailable_reasons() {
    let directory = TempRepo::create().expect("create non-git workspace");
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(directory.path())
        .expect("register non-git workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    assert!(matches!(
        TurnChangeBaseline::capture("ws_non_git".to_owned(), workspace).finish(),
        TurnChangeSet::Unavailable {
            reason: TurnChangeUnavailableReason::NotGit
        }
    ));
    assert!(matches!(
        TurnChangeBaseline::concurrent("ws_concurrent".to_owned()).finish(),
        TurnChangeSet::Unavailable {
            reason: TurnChangeUnavailableReason::ConcurrentTurn
        }
    ));
}
