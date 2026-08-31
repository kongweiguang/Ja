// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::workspace::application::{WorkspaceMutationTransaction, WorkspaceSavePort};
use crate::workspace::domain::{
    DropImportCommand, SaveFileCommand, TextContent, TrashCommitCommand,
};
use crate::workspace::{
    FileRevision, LineEnding, TextEncoding, WorkspaceError, WorkspaceHandle, WorkspaceRegistry,
};
use std::fs;
use std::path::{Path, PathBuf};
use uuid::Uuid;

/// 唯一临时目录只承载本测试创建的资源，允许在失败态保留恢复证据而不污染用户 Workspace。
struct TempDirectory {
    path: PathBuf,
}

impl TempDirectory {
    /// UUID 隔离并行测试的全局 token、mutation ledger 与原生路径锁。
    fn create(label: &str) -> Self {
        let path = std::env::temp_dir().join(format!("ja-{label}-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).expect("create temporary directory");
        Self { path }
    }

    /// 测试只借用根路径，避免 helper 把目录所有权复制给异步或全局状态。
    fn path(&self) -> &Path {
        &self.path
    }
}

impl Drop for TempDirectory {
    /// 清理限定在 UUID 目录；恢复测试刻意留下的 staging/marker 也由该边界统一回收。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

/// Workspace fixture 同时保留物理根和生产 handle，使阶段断言不绕过 containment 与 revision。
struct PreparedWorkspace {
    directory: TempDirectory,
    handle: WorkspaceHandle,
}

impl PreparedWorkspace {
    /// 通过真实 registry admission 创建 handle，避免测试手工伪造 Workspace identity。
    fn create() -> Self {
        let directory = TempDirectory::create("workspace-prepared");
        let registry = WorkspaceRegistry::default();
        let info = registry
            .register(directory.path())
            .expect("register workspace fixture");
        let handle = registry.get(info.id).expect("resolve workspace fixture");
        Self { directory, handle }
    }

    /// Revision 始终从生产 metadata 路径读取，CAS fixture 不复制 hash 或时间戳算法。
    fn revision(&self, relative_path: &str) -> FileRevision {
        self.handle
            .metadata(relative_path, MAX_EDIT_BYTES)
            .expect("read fixture revision")
            .revision
    }

    /// 根路径仅用于验证真实磁盘结果，生产调用仍只使用相对路径和 opaque handle。
    fn root(&self) -> &Path {
        self.directory.path()
    }
}

/// 只返回 Ja 事务资源，避免把普通用户 fixture 文件误认为 staging 或 recovery 证据。
fn ja_entries(root: &Path, prefix: &str) -> Vec<PathBuf> {
    let mut entries = fs::read_dir(root)
        .expect("read transaction directory")
        .filter_map(Result::ok)
        .filter(|entry| entry.file_name().to_string_lossy().starts_with(prefix))
        .map(|entry| entry.path())
        .collect::<Vec<_>>();
    entries.sort();
    entries
}

/// 保存命令显式传入 revision 与 mutation id，阶段测试不依赖 interface DTO 默认值。
fn save_command(workspace: &PreparedWorkspace, mutation_id: &str, text: &str) -> SaveFileCommand {
    SaveFileCommand::new(
        "note.txt".to_owned(),
        workspace.revision("note.txt"),
        mutation_id.to_owned(),
        TextContent {
            text: text.to_owned(),
            encoding: TextEncoding::Utf8,
            line_ending: LineEnding::Lf,
        },
    )
    .expect("create save command")
}

/// Stage 自身清理失败前的局部资源，因此没有 prepared state 时 rollback 必须保留原始错误而非升级恢复态。
#[test]
fn native_transaction_treats_clean_stage_failure_as_rolled_back() {
    let workspace = PreparedWorkspace::create();
    fs::write(workspace.root().join("note.txt"), b"before").expect("seed save target");
    let mut command = save_command(&workspace, "stage-failure", "a\r\nb\n");
    command.content.line_ending = LineEnding::Mixed;
    let port = NativeWorkspaceMutationPort::new(workspace.handle.clone());
    let mut transaction = port.begin_save(command).expect("begin save transaction");

    let result = transaction.with_ordered_lock(|transaction| {
        transaction.admit_idempotency()?;
        transaction.verify_cas()?;
        let stage_error = transaction
            .stage()
            .expect_err("mixed content must fail stage");
        transaction.rollback()?;
        Err::<(), WorkspaceError>(stage_error)
    });

    assert!(matches!(result, Err(WorkspaceError::MixedLineEndings)));
    assert_eq!(
        fs::read(workspace.root().join("note.txt")).unwrap(),
        b"before"
    );
    assert!(ja_entries(workspace.root(), ".ja-").is_empty());
}

/// Save stage 必须先创建并同步真实同目录候选，rollback 再只删除候选且不触碰旧目标。
#[test]
fn save_stage_and_rollback_have_real_candidate_state() {
    let workspace = PreparedWorkspace::create();
    fs::write(workspace.root().join("note.txt"), b"before").expect("seed save target");
    let expected = workspace.revision("note.txt");
    let mut prepared =
        prepare_save(&workspace.handle, "note.txt", &expected, b"after").expect("prepare save");

    assert_eq!(ja_entries(workspace.root(), ".ja-write-").len(), 1);
    assert_eq!(
        fs::read(workspace.root().join("note.txt")).unwrap(),
        b"before"
    );
    rollback_prepared_save(&workspace.handle, &mut prepared).expect("rollback prepared save");

    assert!(ja_entries(workspace.root(), ".ja-").is_empty());
    assert_eq!(
        fs::read(workspace.root().join("note.txt")).unwrap(),
        b"before"
    );
}

/// Save 发布后发现旧目标已被竞争写入时，rollback 必须恢复竞争者字节并清除全部 Ja 恢复节点。
#[test]
fn save_post_publish_conflict_restores_displaced_file() {
    let workspace = PreparedWorkspace::create();
    let target = workspace.root().join("note.txt");
    fs::write(&target, b"before").expect("seed save target");
    let expected = workspace.revision("note.txt");
    let mut prepared =
        prepare_save(&workspace.handle, "note.txt", &expected, b"after").expect("prepare save");

    let result = commit_prepared_save_with(&workspace.handle, &mut prepared, |path| {
        fs::write(path, b"external")
            .map_err(|error| WorkspaceError::io("test_external_write", error))
    });
    assert!(matches!(result, Err(WorkspaceError::RevisionConflict)));
    assert_eq!(fs::read(&target).unwrap(), b"after");
    assert!(!ja_entries(workspace.root(), ".ja-").is_empty());

    rollback_prepared_save(&workspace.handle, &mut prepared).expect("restore displaced target");
    assert_eq!(fs::read(&target).unwrap(), b"external");
    assert!(ja_entries(workspace.root(), ".ja-").is_empty());
}

/// Save 补偿前若已发布目标又被替换，rollback 必须失败关闭，recovery 只保留现场而不删除未知节点。
#[test]
fn save_recovery_preserves_uncertain_physical_evidence() {
    let workspace = PreparedWorkspace::create();
    let target = workspace.root().join("note.txt");
    fs::write(&target, b"before").expect("seed save target");
    let expected = workspace.revision("note.txt");
    let mut prepared =
        prepare_save(&workspace.handle, "note.txt", &expected, b"after").expect("prepare save");
    let result = commit_prepared_save_with(&workspace.handle, &mut prepared, |path| {
        fs::write(path, b"external")
            .map_err(|error| WorkspaceError::io("test_external_write", error))
    });
    assert!(matches!(result, Err(WorkspaceError::RevisionConflict)));

    fs::rename(&target, workspace.root().join("published-candidate.txt"))
        .expect("move published candidate");
    fs::write(&target, b"foreign").expect("replace target with foreign node");
    assert!(matches!(
        rollback_prepared_save(&workspace.handle, &mut prepared),
        Err(WorkspaceError::RecoveryRequired)
    ));
    preserve_prepared_save(&prepared).expect("preserve uncertain save evidence");

    assert_eq!(fs::read(&target).unwrap(), b"foreign");
    assert!(!ja_entries(workspace.root(), ".ja-").is_empty());
}

/// Move stage 的同步 marker 是真实恢复证据；未发布 rollback 只能删除 marker，源和目标保持原状。
#[test]
fn move_stage_and_rollback_manage_real_recovery_marker() {
    let workspace = PreparedWorkspace::create();
    fs::write(workspace.root().join("source.txt"), b"source").expect("seed move source");
    let expected = workspace.revision("source.txt");
    let mut prepared = prepare_move(&workspace.handle, "source.txt", "target.txt", &expected)
        .expect("prepare move");

    assert_eq!(ja_entries(workspace.root(), ".ja-move-").len(), 1);
    rollback_prepared_move(&workspace.handle, &mut prepared).expect("rollback staged move");

    assert_eq!(
        fs::read(workspace.root().join("source.txt")).unwrap(),
        b"source"
    );
    assert!(!workspace.root().join("target.txt").exists());
    assert!(ja_entries(workspace.root(), ".ja-move-").is_empty());
}

/// Move 提交前出现竞争目标时 no-replace 必须失败，rollback 清理 marker 而不覆盖竞争者。
#[test]
fn move_commit_conflict_rolls_back_marker_without_clobbering() {
    let workspace = PreparedWorkspace::create();
    let source = workspace.root().join("source.txt");
    let target = workspace.root().join("target.txt");
    fs::write(&source, b"source").expect("seed move source");
    let expected = workspace.revision("source.txt");
    let mut prepared = prepare_move(&workspace.handle, "source.txt", "target.txt", &expected)
        .expect("prepare move");

    let result = commit_prepared_move_with(&workspace.handle, &mut prepared, |_, destination| {
        fs::write(destination, b"competitor")
            .map_err(|error| WorkspaceError::io("test_move_competitor", error))
    });
    assert!(result.is_err());
    rollback_prepared_move(&workspace.handle, &mut prepared).expect("rollback move conflict");

    assert_eq!(fs::read(source).unwrap(), b"source");
    assert_eq!(fs::read(target).unwrap(), b"competitor");
    assert!(ja_entries(workspace.root(), ".ja-move-").is_empty());
}

/// Windows 在发布完成但 marker 暂时无法删除时必须进入 recovery，并以目标身份加 marker 证明现场可恢复。
#[cfg(windows)]
#[test]
fn move_recovery_keeps_marker_and_moved_identity() {
    use std::os::windows::fs::OpenOptionsExt;

    let workspace = PreparedWorkspace::create();
    let source = workspace.root().join("source.txt");
    let target = workspace.root().join("target.txt");
    fs::write(&source, b"source").expect("seed move source");
    let expected = workspace.revision("source.txt");
    let mut prepared = prepare_move(&workspace.handle, "source.txt", "target.txt", &expected)
        .expect("prepare move");
    let marker = ja_entries(workspace.root(), ".ja-move-")
        .into_iter()
        .next()
        .expect("move marker");
    let marker_lock = fs::OpenOptions::new()
        .read(true)
        .share_mode(0)
        .open(&marker)
        .expect("lock move marker");

    assert!(matches!(
        commit_prepared_move(&workspace.handle, &mut prepared),
        Err(WorkspaceError::RecoveryRequired)
    ));
    preserve_prepared_move(&prepared).expect("preserve moved recovery state");
    assert!(!source.exists());
    assert_eq!(fs::read(&target).unwrap(), b"source");
    assert!(marker.exists());

    drop(marker_lock);
    rollback_prepared_move(&workspace.handle, &mut prepared).expect("cleanup recovered move");
    assert_eq!(fs::read(source).unwrap(), b"source");
    assert!(!target.exists());
    assert!(!marker.exists());
}

/// Drop stage 消费 capability 并完成真实 staging；rollback 清理 staging 后必须允许同 token 再次 stage。
#[test]
fn drop_stage_and_rollback_restore_staging_and_token() {
    let workspace = PreparedWorkspace::create();
    let source_directory = TempDirectory::create("drop-source");
    let source = source_directory.path().join("drop.txt");
    fs::write(&source, b"drop").expect("seed drop source");
    let token = issue_native_drop([source]).expect("issue drop token");
    let command = DropImportCommand::new(
        String::new(),
        workspace.revision(""),
        token.clone(),
        "drop-stage".to_owned(),
    )
    .expect("create drop command");
    let mut prepared = prepare_drop_import(&workspace.handle, &command).expect("stage drop");

    assert!(matches!(
        peek_native_drop_plan(&token),
        Err(WorkspaceError::DropTokenInvalid)
    ));
    assert_eq!(ja_entries(workspace.root(), ".ja-drop-").len(), 1);
    rollback_prepared_drop(&workspace.handle, &mut prepared).expect("rollback staged drop");
    assert!(ja_entries(workspace.root(), ".ja-drop-").is_empty());
    assert!(peek_native_drop_plan(&token).is_ok());

    let retry = DropImportCommand::new(
        String::new(),
        workspace.revision(""),
        token,
        "drop-stage-retry".to_owned(),
    )
    .expect("create retry command");
    let mut retried = prepare_drop_import(&workspace.handle, &retry).expect("retry drop stage");
    rollback_prepared_drop(&workspace.handle, &mut retried).expect("cleanup retried drop");
}

/// Drop source 在 capability 签发后被替换时 stage 必须清理现场并返还 token，不能留下半复制目录。
#[test]
fn drop_stage_failure_cleans_staging_and_restores_token() {
    let workspace = PreparedWorkspace::create();
    let source_directory = TempDirectory::create("drop-source-swap");
    let source = source_directory.path().join("drop.txt");
    fs::write(&source, b"original").expect("seed drop source");
    let token = issue_native_drop([source.clone()]).expect("issue drop token");
    fs::rename(&source, source_directory.path().join("old.txt")).expect("move issued source");
    fs::write(&source, b"replacement").expect("replace issued source");
    let command = DropImportCommand::new(
        String::new(),
        workspace.revision(""),
        token.clone(),
        "drop-stage-failure".to_owned(),
    )
    .expect("create drop command");

    assert!(prepare_drop_import(&workspace.handle, &command).is_err());
    assert!(ja_entries(workspace.root(), ".ja-drop-").is_empty());
    assert!(peek_native_drop_plan(&token).is_ok());
}

/// Drop 部分发布失败后必须逆序移回 staging、删除 staging 并返还 token，最终目录不能出现半成品。
#[test]
fn drop_partial_publish_failure_rolls_back_all_targets() {
    let workspace = PreparedWorkspace::create();
    let source_directory = TempDirectory::create("drop-partial-source");
    let first = source_directory.path().join("first.txt");
    let second = source_directory.path().join("second.txt");
    fs::write(&first, b"first").expect("seed first source");
    fs::write(&second, b"second").expect("seed second source");
    let token = issue_native_drop([first, second]).expect("issue drop token");
    let command = DropImportCommand::new(
        String::new(),
        workspace.revision(""),
        token.clone(),
        "drop-partial".to_owned(),
    )
    .expect("create drop command");
    let mut prepared = prepare_drop_import(&workspace.handle, &command).expect("stage drop");
    let mut promotions = 0usize;

    let result = commit_prepared_drop_with(
        &workspace.handle,
        &mut prepared,
        |staged, target, verify| {
            promotions += 1;
            if promotions == 2 {
                return Err(WorkspaceError::RevisionConflict);
            }
            rename_no_replace(staged, target, verify)
        },
    );
    assert!(matches!(result, Err(WorkspaceError::RevisionConflict)));
    assert!(workspace.root().join("first.txt").exists());
    assert_eq!(ja_entries(workspace.root(), ".ja-drop-").len(), 1);

    rollback_prepared_drop(&workspace.handle, &mut prepared).expect("rollback partial drop");
    assert!(!workspace.root().join("first.txt").exists());
    assert!(!workspace.root().join("second.txt").exists());
    assert!(ja_entries(workspace.root(), ".ja-drop-").is_empty());
    assert!(peek_native_drop_plan(&token).is_ok());
}

/// Drop 已发布节点被外部替换时补偿不能删除未知文件，recovery 保留 staging/目标且 token 继续保持已消费。
#[test]
fn drop_recovery_preserves_partial_publish_evidence() {
    let workspace = PreparedWorkspace::create();
    let source_directory = TempDirectory::create("drop-recovery-source");
    let first = source_directory.path().join("first.txt");
    let second = source_directory.path().join("second.txt");
    fs::write(&first, b"first").expect("seed first source");
    fs::write(&second, b"second").expect("seed second source");
    let token = issue_native_drop([first, second]).expect("issue drop token");
    let command = DropImportCommand::new(
        String::new(),
        workspace.revision(""),
        token.clone(),
        "drop-recovery".to_owned(),
    )
    .expect("create drop command");
    let mut prepared = prepare_drop_import(&workspace.handle, &command).expect("stage drop");
    let mut promotions = 0usize;
    let mut published_target = None;

    let result = commit_prepared_drop_with(
        &workspace.handle,
        &mut prepared,
        |staged, target, verify| {
            promotions += 1;
            if promotions == 2 {
                return Err(WorkspaceError::RevisionConflict);
            }
            rename_no_replace(staged, target, verify)?;
            published_target = Some(target.to_owned());
            Ok(())
        },
    );
    assert!(matches!(result, Err(WorkspaceError::RevisionConflict)));
    let target = published_target.expect("first promoted target");
    fs::rename(&target, workspace.root().join("held-promoted.txt")).expect("move promoted node");
    fs::write(&target, b"foreign").expect("replace promoted target");

    assert!(matches!(
        rollback_prepared_drop(&workspace.handle, &mut prepared),
        Err(WorkspaceError::RecoveryRequired)
    ));
    preserve_prepared_drop(&prepared).expect("preserve partial drop state");
    assert_eq!(fs::read(&target).unwrap(), b"foreign");
    assert_eq!(ja_entries(workspace.root(), ".ja-drop-").len(), 1);
    assert!(matches!(
        peek_native_drop_plan(&token),
        Err(WorkspaceError::DropTokenInvalid)
    ));
}

/// Trash 平台调用前失败时 rollback 必须把已消费计划放回活动表，同 token 可以再次进入 commit stage。
#[test]
fn trash_pre_platform_failure_rolls_back_consumed_token() {
    let workspace = PreparedWorkspace::create();
    fs::write(workspace.root().join("trash.txt"), b"trash").expect("seed trash target");
    let expected = workspace.revision("trash.txt");
    let prepared_plan = prepare_trash_plan(&workspace.handle, "trash.txt".to_owned(), &expected)
        .expect("stage trash plan");
    let plan = commit_prepared_trash_plan(&prepared_plan).expect("publish trash plan");
    let command = TrashCommitCommand::new(
        "trash.txt".to_owned(),
        expected,
        plan.operation_token,
        "trash-pre-platform".to_owned(),
    )
    .expect("create trash command");
    let mut prepared =
        prepare_trash_commit(&workspace.handle, &command).expect("stage trash commit");

    let result = commit_prepared_trash_with(
        &workspace.handle,
        &mut prepared,
        |_| Err(WorkspaceError::RevisionConflict),
        |_, _| unreachable!("platform adapter must not run before rescan succeeds"),
    );
    assert!(matches!(result, Err(WorkspaceError::RevisionConflict)));
    rollback_prepared_trash(&workspace.handle, &mut prepared).expect("restore trash token");

    let mut retry = prepare_trash_commit(&workspace.handle, &command).expect("retry trash stage");
    rollback_prepared_trash(&workspace.handle, &mut retry).expect("restore retry token");
    let _consumed =
        prepare_trash_commit(&workspace.handle, &command).expect("consume cleanup token");
    assert!(workspace.root().join("trash.txt").exists());
}

/// Trash 平台结果不确定时 recovery 必须隔离计划而不是返还活动 token，避免换 mutation id 重复删除。
#[test]
fn trash_recovery_quarantines_plan_from_normal_retry() {
    let workspace = PreparedWorkspace::create();
    fs::write(workspace.root().join("trash.txt"), b"trash").expect("seed trash target");
    let expected = workspace.revision("trash.txt");
    let prepared_plan = prepare_trash_plan(&workspace.handle, "trash.txt".to_owned(), &expected)
        .expect("stage trash plan");
    let plan = commit_prepared_trash_plan(&prepared_plan).expect("publish trash plan");
    let command = TrashCommitCommand::new(
        "trash.txt".to_owned(),
        expected,
        plan.operation_token,
        "trash-recovery".to_owned(),
    )
    .expect("create trash command");
    let mut prepared =
        prepare_trash_commit(&workspace.handle, &command).expect("stage trash commit");

    let result = commit_prepared_trash_with(
        &workspace.handle,
        &mut prepared,
        |_| Ok(()),
        |_, verify| {
            verify()?;
            Err(WorkspaceError::RecoveryRequired)
        },
    );
    assert!(matches!(result, Err(WorkspaceError::RecoveryRequired)));
    preserve_prepared_trash(&prepared).expect("quarantine trash recovery plan");
    preserve_prepared_trash(&prepared).expect("repeat quarantine is idempotent");

    assert!(matches!(
        prepare_trash_commit(&workspace.handle, &command),
        Err(WorkspaceError::TrashTokenInvalid)
    ));
    assert!(workspace.root().join("trash.txt").exists());
}

/// 任一原生事务进入恢复态后，同一 Workspace 的后续写入必须失败关闭，而权威读取仍可核对现场。
#[test]
fn native_recovery_latches_workspace_mutation_gate() {
    let workspace = PreparedWorkspace::create();
    fs::write(workspace.root().join("note.txt"), b"before").expect("seed save target");
    let command = save_command(&workspace, "recovery-gate-source", "after");
    let port = NativeWorkspaceMutationPort::new(workspace.handle.clone());
    let mut transaction = port.begin_save(command).expect("begin recovery transaction");

    let recovery = transaction.with_ordered_lock(|transaction| {
        transaction.admit_idempotency()?;
        transaction.verify_cas()?;
        transaction.stage()?;
        transaction.recover()?;
        Err::<(), WorkspaceError>(WorkspaceError::RecoveryRequired)
    });
    assert!(matches!(recovery, Err(WorkspaceError::RecoveryRequired)));
    assert_eq!(
        workspace
            .handle
            .metadata("note.txt", MAX_EDIT_BYTES)
            .expect("read remains available")
            .revision,
        workspace.revision("note.txt")
    );

    let blocked = NativeWorkspaceMutationPort::new(workspace.handle.clone())
        .begin_save(save_command(
            &workspace,
            "recovery-gate-blocked",
            "must-not-publish",
        ))
        .expect("begin blocked transaction")
        .with_ordered_lock(|transaction| {
            transaction.admit_idempotency()?;
            transaction.verify_cas()?;
            transaction.stage()?;
            transaction.commit()
        });
    assert!(matches!(blocked, Err(WorkspaceError::RecoveryRequired)));
    assert_eq!(fs::read(workspace.root().join("note.txt")).unwrap(), b"before");
}
