// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use std::fs;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

struct TempDir(PathBuf);

impl TempDir {
    /// 使用唯一且包含 Unicode/空格的路径，使序列化在普通临时 fixture 的同一边界接受验证。
    fn create() -> Self {
        let suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let path =
            std::env::temp_dir().join(format!("ja workspace 测试 {} {suffix}", std::process::id()));
        fs::create_dir_all(&path).expect("create fixture root");
        Self(path)
    }
}

impl Drop for TempDir {
    /// 测试清理刻意采用尽力语义，因为断言已证明生产 reader 从不拥有 fixture 目录。
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// fixture 通过生产调用方使用的同一 opaque-id 路径注册，避免测试绕过 Workspace 准入。
fn workspace(root: &TempDir) -> WorkspaceHandle {
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register fixture root");
    registry.get(info.id).expect("lookup workspace")
}

/// 验证目录优先且同类名称稳定的分页顺序，同时确保 cursor 与 containment 仍拒绝陈旧快照和 traversal。
#[test]
fn registry_tree_pages_and_containment_are_bounded() {
    let root = TempDir::create();
    fs::write(root.0.join("b space.txt"), "b").expect("write b");
    fs::write(root.0.join("a.txt"), "a").expect("write a");
    fs::create_dir(root.0.join("src")).expect("create src");
    fs::write(root.0.join("src").join("main.rs"), "fn main() {}").expect("write main");
    let handle = workspace(&root);
    assert!(matches!(
        handle.resolve_file("../escape"),
        Err(WorkspaceError::InvalidRelativePath)
    ));
    assert!(matches!(
        handle.resolve_file("./a.txt"),
        Err(WorkspaceError::InvalidRelativePath)
    ));
    assert!(matches!(
        handle.resolve_file("a:b"),
        Err(WorkspaceError::InvalidRelativePath)
    ));
    assert!(matches!(
        handle.resolve_file(""),
        Err(WorkspaceError::NotFile)
    ));
    assert_eq!(
        handle.metadata("", 1024).expect("root metadata").kind,
        EntryKind::Directory
    );

    let reader = TreeReader::new(
        handle,
        TreePolicy {
            max_page_size: 1,
            ..TreePolicy::default()
        },
    );
    let first = reader
        .read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: None,
            page_size: Some(1),
            snapshot_token: None,
        })
        .expect("first page");
    assert_eq!(first.entries.len(), 1);
    assert_eq!(first.entries[0].name, "src");
    assert_eq!(first.next_cursor.as_deref(), Some("1"));
    assert_eq!(first.directory_revision.kind(), EntryKind::Directory);
    assert_eq!(first.directory_revision.sha256(), None);
    assert!(!first.snapshot_token.is_empty());
    let second = reader
        .read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: first.next_cursor.clone(),
            page_size: Some(1),
            snapshot_token: Some(first.snapshot_token.clone()),
        })
        .expect("second page");
    assert_eq!(second.entries.len(), 1);
    assert_eq!(second.entries[0].name, "a.txt");

    fs::write(root.0.join("b space.txt"), "changed").expect("change cursor fixture");
    assert!(matches!(
        reader.read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: second.next_cursor.clone(),
            page_size: Some(1),
            snapshot_token: Some(second.snapshot_token.clone()),
        }),
        Err(WorkspaceError::StaleCursor)
    ));

    let refreshed = reader
        .read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: None,
            page_size: Some(1),
            snapshot_token: None,
        })
        .expect("refreshed first page");
    fs::write(root.0.join("new-after-snapshot.txt"), "new").expect("add cursor fixture");
    assert!(matches!(
        reader.read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: refreshed.next_cursor,
            page_size: Some(1),
            snapshot_token: Some(refreshed.snapshot_token),
        }),
        Err(WorkspaceError::StaleCursor)
    ));

    let bounded = TreeReader::new(
        workspace(&root),
        TreePolicy {
            max_entries_per_page_scan: 2,
            ..TreePolicy::default()
        },
    );
    assert!(matches!(
        bounded.read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: None,
            page_size: None,
            snapshot_token: None,
        }),
        Err(WorkspaceError::EntryBudgetExceeded)
    ));
}

/// 验证 reader 不猜测 encoding，也不返回超过预算的内容。
#[test]
fn content_reader_classifies_text_binary_encoding_and_size() {
    let root = TempDir::create();
    fs::write(root.0.join("utf8.txt"), "hello 世界").expect("write utf8");
    fs::write(root.0.join("bom.txt"), [0xef, 0xbb, 0xbf, b'h', b'i']).expect("write bom");
    fs::write(root.0.join("utf16.txt"), [0xff, 0xfe, b'h', 0, b'i', 0]).expect("write utf16");
    fs::write(root.0.join("binary.bin"), [0, 1, 2, 3]).expect("write binary");
    fs::write(root.0.join("large.bin"), [1, 2, 3, 4, 5, 6, 7, 8, 9]).expect("write large");
    let reader = FileReader::new(
        workspace(&root),
        ContentPolicy {
            max_bytes: 8,
            hash_limit_bytes: 8,
        },
    );
    assert_eq!(
        reader.read("utf8.txt").expect("utf8").kind,
        ContentKind::TooLarge
    );
    let bom = reader.read("bom.txt").expect("bom");
    assert_eq!(bom.encoding, Some(TextEncoding::Utf8Bom));
    let utf16 = reader.read("utf16.txt").expect("utf16");
    assert_eq!(utf16.encoding, Some(TextEncoding::Utf16Le));
    assert_eq!(
        reader.read("binary.bin").expect("binary").kind,
        ContentKind::Binary
    );
    assert_eq!(
        reader.read("large.bin").expect("large").kind,
        ContentKind::TooLarge
    );
}

/// 验证搜索截断对调用方可见，且 polling 能报告外部编辑。
#[test]
fn search_and_polling_detector_report_bounds_and_external_changes() {
    let root = TempDir::create();
    fs::create_dir(root.0.join("nested")).expect("create nested");
    fs::write(
        root.0.join("nested").join("note.txt"),
        "needle\nsecond needle",
    )
    .expect("write note");
    fs::write(root.0.join("binary"), [0, 1, 2]).expect("write binary");
    let handle = workspace(&root);
    let search = TextSearch::new(
        handle.clone(),
        SearchPolicy {
            max_results: 1,
            ..SearchPolicy::default()
        },
    );
    let result = search.search("", "needle").expect("search");
    assert_eq!(result.hits.len(), 1);
    assert!(result.truncated);
    assert_eq!(result.hits[0].line, 1);

    let mut detector = PollingChangeDetector::new(
        handle,
        PollingPolicy {
            min_interval_millis: 1,
            ..PollingPolicy::default()
        },
    )
    .expect("detector");
    std::thread::sleep(Duration::from_millis(3));
    fs::write(root.0.join("nested").join("note.txt"), "needle changed").expect("external edit");
    let batch = detector.poll().expect("poll");
    assert_eq!(batch.state, PollState::Updated);
    assert!(
        batch
            .changes
            .iter()
            .any(|change| change.relative_path == "nested/note.txt")
    );
}

/// 验证后续超限快照会保持 overflow，直到显式 rescan，不能静默替换先前完整 baseline。
#[test]
fn polling_budget_reports_overflow() {
    let root = TempDir::create();
    fs::write(root.0.join("small.txt"), "ok").expect("write small file");
    let handle = workspace(&root);
    let mut detector = PollingChangeDetector::new(
        handle,
        PollingPolicy {
            min_interval_millis: 1,
            max_total_bytes: 16,
            ..PollingPolicy::default()
        },
    )
    .expect("bounded detector");
    fs::write(root.0.join("large.txt"), [1_u8; 128]).expect("write large file");
    std::thread::sleep(Duration::from_millis(3));
    let batch = detector.poll().expect("overflow poll");
    assert_eq!(batch.state, PollState::Overflow);
    assert!(batch.requires_rescan);
    assert!(matches!(
        detector.rescan(),
        Ok(ChangeBatch {
            state: PollState::Overflow,
            requires_rescan: true,
            ..
        })
    ));
}

#[cfg(unix)]
/// 验证 link 只作为 opaque tree entry 展示，且任何 reader 都不能继续遍历。
#[test]
fn symlink_is_visible_but_never_followed() {
    use std::os::unix::fs::symlink;
    let root = TempDir::create();
    let outside = TempDir::create();
    fs::write(outside.0.join("secret.txt"), "secret").expect("outside file");
    symlink(&outside.0, root.0.join("linked")).expect("directory link");
    let handle = workspace(&root);
    assert!(matches!(
        handle.resolve_directory("linked"),
        Err(WorkspaceError::LinkNotAllowed)
    ));
    let page = TreeReader::new(handle, TreePolicy::default())
        .read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: None,
            page_size: None,
            snapshot_token: None,
        })
        .expect("tree page");
    assert_eq!(page.entries[0].metadata.kind, EntryKind::Symlink);
    assert!(!page.entries[0].can_expand);
}

#[cfg(windows)]
/// 验证 Windows reparse link 使用同一 opaque 且不可遍历的安全边界。
#[test]
fn reparse_point_is_visible_but_never_followed() {
    use std::os::windows::fs::symlink_dir;
    let root = TempDir::create();
    let outside = TempDir::create();
    fs::write(outside.0.join("secret.txt"), "secret").expect("outside file");
    if symlink_dir(&outside.0, root.0.join("linked")).is_err() {
        return;
    }
    let handle = workspace(&root);
    assert!(matches!(
        handle.resolve_directory("linked"),
        Err(WorkspaceError::LinkNotAllowed)
    ));
    let page = TreeReader::new(handle, TreePolicy::default())
        .read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: None,
            page_size: None,
            snapshot_token: None,
        })
        .expect("tree page");
    assert_eq!(page.entries[0].metadata.kind, EntryKind::ReparsePoint);
    assert!(!page.entries[0].can_expand);
}

/// 验证根目录准入不会通过 canonicalize 抹去原始 link 身份证据。
#[cfg(unix)]
#[test]
fn raw_symlink_root_is_rejected() {
    use std::os::unix::fs::symlink;
    let target = TempDir::create();
    let link = target.0.with_extension("root-link");
    symlink(&target.0, &link).expect("create root link");
    assert!(matches!(
        WorkspaceRegistry::default().register(&link),
        Err(WorkspaceError::InvalidRoot)
    ));
    let _ = fs::remove_file(link);
}

/// 验证 Windows junction/symlink 根在 canonicalization 前即被拒绝。
#[cfg(windows)]
#[test]
fn raw_reparse_root_is_rejected() {
    use std::os::windows::fs::symlink_dir;
    let target = TempDir::create();
    let link = target.0.with_extension("root-link");
    if symlink_dir(&target.0, &link).is_err() {
        return;
    }
    assert!(matches!(
        WorkspaceRegistry::default().register(&link),
        Err(WorkspaceError::InvalidRoot)
    ));
    let _ = fs::remove_dir(link);
}

/// 验证 hard-linked 文件不会被误认为 Workspace 内独立文件。
#[test]
fn hard_link_file_is_rejected() {
    let root = TempDir::create();
    let original = root.0.join("original.txt");
    let alias = root.0.join("alias.txt");
    fs::write(&original, "secret").expect("write hardlink source");
    if fs::hard_link(&original, &alias).is_err() {
        return;
    }
    let handle = workspace(&root);
    assert!(matches!(
        handle.resolve_file("alias.txt"),
        Err(WorkspaceError::LinkNotAllowed)
    ));
}

/// 验证 Windows device name 与末尾点/空格 alias 不能以不同于 UI 请求的原生目标跨越路径边界。
#[cfg(windows)]
#[test]
fn windows_device_and_alias_paths_are_rejected() {
    let root = TempDir::create();
    let handle = workspace(&root);
    for path in ["CON.txt", "trailing.", "trailing "] {
        assert!(matches!(
            handle.resolve_file(path),
            Err(WorkspaceError::InvalidRelativePath)
        ));
    }
}

/// 验证非 UTF-8 原生文件名不能通过有损转换进入 IPC path。
#[cfg(unix)]
#[test]
fn non_utf8_tree_name_is_rejected() {
    use std::ffi::OsString;
    use std::os::unix::ffi::OsStringExt;

    let root = TempDir::create();
    let name = OsString::from_vec(vec![0xff, b'.', b't', b'x', b't']);
    fs::write(root.0.join(std::path::Path::new(&name)), "opaque").expect("write native name");
    let reader = TreeReader::new(workspace(&root), TreePolicy::default());
    assert!(matches!(
        reader.read_page(&TreePageRequest {
            relative_path: String::new(),
            cursor: None,
            page_size: None,
            snapshot_token: None,
        }),
        Err(WorkspaceError::InvalidRelativePath)
    ));
}

/// 验证已准入根被同 spelling 的另一目录替换后，旧 handle 不能继续使用该路径。
#[test]
fn root_identity_swap_fails_closed() {
    let root = TempDir::create();
    let handle = workspace(&root);
    let old_root = root.0.with_extension("old-root");
    fs::rename(&root.0, &old_root).expect("move admitted root");
    fs::create_dir_all(&root.0).expect("create replacement root");
    assert!(matches!(
        handle.resolve_directory(""),
        Err(WorkspaceError::PathChanged)
    ));
    let _ = fs::remove_dir_all(old_root);
}

/// 验证捕获的 component 身份能发现目录替换，即使替换目标仍位于 canonical Workspace 根内。
#[test]
fn resolved_component_swap_fails_closed() {
    let root = TempDir::create();
    fs::create_dir(root.0.join("src")).expect("create source directory");
    fs::write(root.0.join("src").join("main.rs"), "old").expect("write source");
    let handle = workspace(&root);
    let resolved = handle
        .resolve_guard("src/main.rs", Some(false))
        .expect("capture component guard");
    let old = root.0.join("src-old");
    fs::rename(root.0.join("src"), &old).expect("move source directory");
    fs::create_dir(root.0.join("src")).expect("create replacement directory");
    fs::write(root.0.join("src").join("main.rs"), "new").expect("write replacement");
    assert!(matches!(
        handle.verify_resolved(&resolved, Some(false)),
        Err(WorkspaceError::PathChanged)
    ));
    let _ = fs::remove_dir_all(old);
}
