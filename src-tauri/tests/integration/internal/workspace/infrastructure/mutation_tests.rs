// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crate::workspace::application::WorkspaceMutationService;
use crate::workspace::domain::{
    DropImportCommand, MoveEntryCommand, SaveFileCommand, TextContent, TrashCommitCommand,
    TrashPrepareCommand,
};
use crate::workspace::infrastructure::NativeWorkspaceMutationPort;
use crate::workspace::{
    EntryKind, FileRevision, LineEnding, MutationInfrastructureError, PathMutationQueue,
    TextEncoding, TreePageRequest, TreePolicy, TreeReader, WorkspaceError, WorkspaceHandle,
    WorkspaceRegistry, issue_native_drop,
};
use std::fs;
use std::path::PathBuf;
use uuid::Uuid;

/// 临时 Workspace 同时保留 registry handle 与物理根，真实 IO 断言不经过 mock。
struct TempWorkspace {
    root: PathBuf,
    handle: WorkspaceHandle,
}

impl TempWorkspace {
    /// 唯一目录避免并行测试共享 mutation ledger、路径锁或 Trash token。
    fn create() -> Self {
        let root = std::env::temp_dir().join(format!("ja-workspace-mutation-{}", Uuid::new_v4()));
        fs::create_dir_all(&root).expect("create workspace fixture");
        let registry = WorkspaceRegistry::default();
        let info = registry.register(&root).expect("register workspace");
        let handle = registry.get(info.id).expect("workspace handle");
        Self { root, handle }
    }

    /// 元数据通过生产 containment 与 hash 路径读取，fixture 不手写 CAS 值。
    fn revision(&self, relative_path: &str) -> FileRevision {
        self.handle
            .metadata(relative_path, 16 * 1024 * 1024)
            .expect("fixture metadata")
            .revision
    }

    /// 每个 service 固定绑定同一 handle，模拟 interface 已完成 Workspace admission 的状态。
    fn service(&self) -> WorkspaceMutationService<NativeWorkspaceMutationPort> {
        WorkspaceMutationService::new(NativeWorkspaceMutationPort::new(self.handle.clone()))
    }
}

impl Drop for TempWorkspace {
    /// 清理只删除测试创建的唯一临时根；Trash commit 已移走的文件不会被再次访问。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.root);
    }
}

/// 统一文本 fixture 保持编码与换行显式，避免测试依赖平台默认值。
fn text(value: &str) -> TextContent {
    TextContent {
        text: value.to_owned(),
        encoding: TextEncoding::Utf8,
        line_ending: LineEnding::Lf,
    }
}

/// Save 在真实同目录原子替换后返回新 hash，最终文件只包含完整新内容。
#[test]
fn save_commits_atomically_and_returns_authoritative_revision() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("note.txt"), b"before").expect("seed file");
    let result = workspace
        .service()
        .save_file(
            SaveFileCommand::new(
                "note.txt".to_owned(),
                workspace.revision("note.txt"),
                "save-atomic".to_owned(),
                text("after"),
            )
            .expect("save command"),
        )
        .expect("save commit");
    assert_eq!(fs::read(workspace.root.join("note.txt")).unwrap(), b"after");
    assert_eq!(result.revision, workspace.revision("note.txt"));
    assert_eq!(result.revision.kind(), EntryKind::File);
}

/// Stale CAS 在 staging 前失败，磁盘必须保留外部编辑后的真实字节。
#[test]
fn stale_save_rejects_without_overwriting_external_edit() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("note.txt"), b"before").expect("seed file");
    let stale = workspace.revision("note.txt");
    fs::write(workspace.root.join("note.txt"), b"external").expect("external edit");
    assert!(matches!(
        workspace.service().save_file(
            SaveFileCommand::new(
                "note.txt".to_owned(),
                stale,
                "save-stale".to_owned(),
                text("client"),
            )
            .expect("save command")
        ),
        Err(WorkspaceError::RevisionConflict)
    ));
    assert_eq!(
        fs::read(workspace.root.join("note.txt")).unwrap(),
        b"external"
    );
}

/// 相同 mutation id 即使携带最新 revision 也只能提交一次，第二次不得改变文件。
#[test]
fn mutation_id_is_single_use_across_save_retries() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("note.txt"), b"zero").expect("seed file");
    workspace
        .service()
        .save_file(
            SaveFileCommand::new(
                "note.txt".to_owned(),
                workspace.revision("note.txt"),
                "save-once".to_owned(),
                text("one"),
            )
            .expect("first command"),
        )
        .expect("first save");
    assert!(matches!(
        workspace.service().save_file(
            SaveFileCommand::new(
                "note.txt".to_owned(),
                workspace.revision("note.txt"),
                "save-once".to_owned(),
                text("two"),
            )
            .expect("second command")
        ),
        Err(WorkspaceError::MutationAlreadyUsed)
    ));
    assert_eq!(fs::read(workspace.root.join("note.txt")).unwrap(), b"one");
}

/// 编码 stage 失败必须在 commit 前补偿结束，原文件和目录中都不能出现 Ja 临时文件。
#[test]
fn save_stage_failure_leaves_no_temp_or_content_change() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("note.txt"), b"before").expect("seed file");
    let content = TextContent {
        text: "a\r\nb\n".to_owned(),
        encoding: TextEncoding::Utf8,
        line_ending: LineEnding::Mixed,
    };
    assert!(matches!(
        workspace.service().save_file(
            SaveFileCommand::new(
                "note.txt".to_owned(),
                workspace.revision("note.txt"),
                "save-mixed".to_owned(),
                content,
            )
            .expect("save command")
        ),
        Err(WorkspaceError::MixedLineEndings)
    ));
    assert_eq!(
        fs::read(workspace.root.join("note.txt")).unwrap(),
        b"before"
    );
    assert!(fs::read_dir(&workspace.root).unwrap().all(|entry| {
        !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".ja-")
    }));
}

/// Move 使用 no-replace 提交点，已有 destination 时 source 与 destination 都保持原样。
#[test]
fn move_never_replaces_existing_destination() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("source.txt"), b"source").expect("source");
    fs::write(workspace.root.join("target.txt"), b"target").expect("target");
    assert!(matches!(
        workspace.service().move_entry(
            MoveEntryCommand::new(
                "source.txt".to_owned(),
                "target.txt".to_owned(),
                workspace.revision("source.txt"),
                "move-conflict".to_owned(),
            )
            .expect("move command")
        ),
        Err(WorkspaceError::AlreadyExists)
    ));
    assert_eq!(
        fs::read(workspace.root.join("source.txt")).unwrap(),
        b"source"
    );
    assert_eq!(
        fs::read(workspace.root.join("target.txt")).unwrap(),
        b"target"
    );
}

/// 成功 Move 必须保留物理内容、删除旧名称并返回新名称下的权威 revision。
#[test]
fn move_commits_both_endpoint_state_changes() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("source.txt"), b"source").expect("source");
    let expected = workspace.revision("source.txt");
    let result = workspace
        .service()
        .move_entry(
            MoveEntryCommand::new(
                "source.txt".to_owned(),
                "target.txt".to_owned(),
                expected,
                "move-success".to_owned(),
            )
            .expect("move command"),
        )
        .expect("move");
    assert!(!workspace.root.join("source.txt").exists());
    assert_eq!(
        fs::read(workspace.root.join("target.txt")).unwrap(),
        b"source"
    );
    assert_eq!(result.revision, workspace.revision("target.txt"));
}

/// 已被桌面 mutation queue 持有的两个端点不能被反向 Move 绕过，失败后状态保持不变。
#[test]
fn move_uses_one_ordered_lock_set_for_both_endpoints() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("source.txt"), b"source").expect("source");
    let source = fs::canonicalize(workspace.root.join("source.txt")).unwrap();
    let destination = workspace.root.join("target.txt");
    let expected = workspace.revision("source.txt");
    let result = PathMutationQueue::global().with_paths::<_, MutationInfrastructureError, _>(
        &[destination.clone(), source.clone()],
        || {
            Ok(workspace.service().move_entry(
                MoveEntryCommand::new(
                    "source.txt".to_owned(),
                    "target.txt".to_owned(),
                    expected,
                    "move-locked".to_owned(),
                )
                .expect("move command"),
            ))
        },
    );
    assert!(result.expect("outer lock").is_err());
    assert!(workspace.root.join("source.txt").exists());
    assert!(!workspace.root.join("target.txt").exists());
}

/// Trash prepare 创建短期计划但不删除文件；commit 只在平台回收站可用时移动目标，
/// 明确不可用的环境必须保留原文件并返回稳定错误，不能让测试退化为永久删除。
#[cfg(windows)]
#[test]
fn trash_prepare_and_commit_have_distinct_side_effect_boundaries() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("trash.txt"), b"trash").expect("trash file");
    let expected = workspace.revision("trash.txt");
    let prepared = workspace
        .service()
        .prepare_trash(
            TrashPrepareCommand::new(
                "trash.txt".to_owned(),
                expected.clone(),
                "trash-prepare".to_owned(),
            )
            .expect("prepare command"),
        )
        .expect("prepare");
    assert!(workspace.root.join("trash.txt").exists());
    let result = workspace.service().commit_trash(
        TrashCommitCommand::new(
            "trash.txt".to_owned(),
            expected,
            prepared.operation_token,
            "trash-commit".to_owned(),
        )
        .expect("commit command"),
    );
    match result {
        Ok(_) => assert!(!workspace.root.join("trash.txt").exists()),
        Err(WorkspaceError::RecycleUnavailable) => {
            assert_eq!(
                fs::read(workspace.root.join("trash.txt")).unwrap(),
                b"trash"
            );
        }
        Err(error) => panic!("unexpected trash result: {error:?}"),
    }
}

/// Prepare 后目标变化必须阻止 Trash commit，外部编辑保留且不能被旧 token 删除。
#[cfg(windows)]
#[test]
fn trash_commit_rechecks_snapshot_before_platform_side_effect() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("trash.txt"), b"before").expect("trash file");
    let expected = workspace.revision("trash.txt");
    let prepared = workspace
        .service()
        .prepare_trash(
            TrashPrepareCommand::new(
                "trash.txt".to_owned(),
                expected.clone(),
                "trash-stale-prepare".to_owned(),
            )
            .expect("prepare command"),
        )
        .expect("prepare");
    fs::write(workspace.root.join("trash.txt"), b"external").expect("external edit");
    assert!(
        workspace
            .service()
            .commit_trash(
                TrashCommitCommand::new(
                    "trash.txt".to_owned(),
                    expected,
                    prepared.operation_token,
                    "trash-stale-commit".to_owned(),
                )
                .expect("commit command")
            )
            .is_err()
    );
    assert_eq!(
        fs::read(workspace.root.join("trash.txt")).unwrap(),
        b"external"
    );
}

/// Drop 对 source 只读，完整 staging 成功后一次发布目标文件并返回相对路径。
#[test]
fn drop_import_publishes_complete_file_and_keeps_source() {
    let workspace = TempWorkspace::create();
    let source_root = std::env::temp_dir().join(format!("ja-drop-source-{}", Uuid::new_v4()));
    fs::create_dir_all(&source_root).expect("source root");
    let source = source_root.join("drop.txt");
    fs::write(&source, b"drop").expect("source file");
    let token = issue_native_drop([source.clone()]).expect("drop token");
    let tree_revision = TreeReader::new(workspace.handle.clone(), TreePolicy::default())
        .read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: None,
            page_size: Some(1),
            snapshot_token: None,
        })
        .expect("tree root revision")
        .directory_revision;
    let result = workspace
        .service()
        .import_drop(
            DropImportCommand::new(
                String::new(),
                tree_revision,
                token,
                "drop-success".to_owned(),
            )
            .expect("drop command"),
        )
        .expect("drop import");
    assert_eq!(result.imported_relative_paths, ["drop.txt"]);
    assert_eq!(fs::read(workspace.root.join("drop.txt")).unwrap(), b"drop");
    assert_eq!(fs::read(&source).unwrap(), b"drop");
    let _ = fs::remove_dir_all(source_root);
}

/// Drop 目标冲突在可见发布前失败，既有文件不变且 source capability 不会覆盖它。
#[test]
fn drop_conflict_leaves_existing_target_and_source_unchanged() {
    let workspace = TempWorkspace::create();
    fs::write(workspace.root.join("drop.txt"), b"existing").expect("existing target");
    let source_root = std::env::temp_dir().join(format!("ja-drop-conflict-{}", Uuid::new_v4()));
    fs::create_dir_all(&source_root).expect("source root");
    let source = source_root.join("drop.txt");
    fs::write(&source, b"source").expect("source file");
    let token = issue_native_drop([source.clone()]).expect("drop token");
    assert!(
        workspace
            .service()
            .import_drop(
                DropImportCommand::new(
                    String::new(),
                    workspace.revision(""),
                    token,
                    "drop-conflict".to_owned(),
                )
                .expect("drop command")
            )
            .is_err()
    );
    assert_eq!(
        fs::read(workspace.root.join("drop.txt")).unwrap(),
        b"existing"
    );
    assert_eq!(fs::read(&source).unwrap(), b"source");
    assert!(fs::read_dir(&workspace.root).unwrap().all(|entry| {
        !entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .starts_with(".ja-drop-")
    }));
    let _ = fs::remove_dir_all(source_root);
}
