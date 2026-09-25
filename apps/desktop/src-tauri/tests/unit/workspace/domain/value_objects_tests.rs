// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// 相对路径词法规则必须跨平台一致，防止同一 IPC 输入在 Windows 与 Unix 产生不同目标。
#[test]
fn relative_path_rejects_aliases_and_traversal() {
    for invalid in [
        "/absolute",
        "../escape",
        "a/../b",
        "a//b",
        "a\\b",
        "C:/drive",
        "CON.txt",
        "trailing.",
    ] {
        assert!(matches!(
            RelativePath::parse(invalid.to_owned()),
            Err(WorkspaceError::InvalidRelativePath)
        ));
    }
    assert!(
        RelativePath::parse(String::new())
            .expect("root path")
            .is_root()
    );
    assert_eq!(
        RelativePath::parse_entry("src/main.rs".to_owned())
            .expect("entry path")
            .as_str(),
        "src/main.rs"
    );
}

/// Mutation 与 capability token 的错误类型必须保持分离，便于 interface 给出正确恢复动作。
#[test]
fn opaque_ids_are_bounded_and_typed() {
    assert!(matches!(
        MutationId::parse(String::new()),
        Err(WorkspaceError::InvalidMutationId)
    ));
    assert!(matches!(
        TrashToken::parse("\n".to_owned()),
        Err(WorkspaceError::TrashTokenInvalid)
    ));
    assert!(matches!(
        DropToken::parse("x".repeat(129)),
        Err(WorkspaceError::DropTokenInvalid)
    ));
}

/// FileRevision 只有完整 SHA-256 才能成为 CAS 证据，短 hash 与非 hex 必须在领域边界拒绝。
#[test]
fn file_revision_requires_complete_sha256() {
    assert!(matches!(
        FileRevision::try_new(EntryKind::File, 1, None, Some("abc".to_owned())),
        Err(WorkspaceError::InvalidRevision)
    ));
    assert!(matches!(
        FileRevision::try_new(EntryKind::File, 1, None, Some("z".repeat(64))),
        Err(WorkspaceError::InvalidRevision)
    ));
    let hash = "a".repeat(64);
    let revision = FileRevision::try_new(EntryKind::File, 1, Some(7), Some(hash.clone()))
        .expect("valid revision");
    assert_eq!(revision.kind(), EntryKind::File);
    assert_eq!(revision.sha256(), Some(hash.as_str()));
}
