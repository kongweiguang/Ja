// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::review_git_support::{fixture_repo, run_git};
use super::*;
use crate::workspace::WorkspaceRegistry;
use std::fs;

#[test]
fn typed_git_read_operations_are_machine_safe_and_read_only() {
    let Some((root, git)) = fixture_repo() else {
        return;
    };
    run_git(
        &git,
        &root.0,
        &["config", "alias.status", "!echo MALICIOUS"],
    );
    run_git(&git, &root.0, &["config", "core.pager", "!echo MALICIOUS"]);
    fs::write(root.0.join("文件 name.txt"), "changed\n").expect("edit tracked fixture");
    fs::write(root.0.join("untracked space.txt"), "untracked\n").expect("write untracked fixture");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register git root");
    let workspace = registry.get(info.id).expect("get git root");
    let adapter = GitReadOnly::new(workspace).expect("git adapter");
    let token = CancellationToken::default();
    let status = adapter.review_status_all(&token).expect("status");
    assert!(status.iter().any(|entry| entry.path == "文件 name.txt"));
    assert!(
        status
            .iter()
            .any(|entry| entry.path == "untracked space.txt")
    );
    let diff = adapter.diff(&DiffOptions::default(), &token).expect("diff");
    assert!(String::from_utf8_lossy(&diff.bytes).contains("changed"));
    let log = adapter.log(10, &token).expect("log");
    assert_eq!(log.len(), 1);
    assert!(log[0].parents.is_empty());
    assert_eq!(log[0].author, "Ja Test");
    assert_eq!(log[0].subject, "fixture");
}

/// 证明类型化入口拒绝路径穿越，并能在执行前观察取消。
#[test]
fn invalid_paths_and_cancellation_are_rejected() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register git root");
    let adapter =
        GitReadOnly::new(registry.get(info.id).expect("get git root")).expect("git adapter");
    let token = CancellationToken::default();
    assert!(matches!(
        adapter.diff(
            &DiffOptions {
                staged: false,
                relative_path: Some("../escape".to_owned()),
            },
            &token,
        ),
        Err(GitError::InvalidPath)
    ));
    assert!(matches!(
        adapter.diff(
            &DiffOptions {
                staged: false,
                relative_path: Some("foo\\bar".to_owned()),
            },
            &token,
        ),
        Err(GitError::InvalidPath)
    ));
    #[cfg(windows)]
    assert!(matches!(
        adapter.diff(
            &DiffOptions {
                staged: false,
                relative_path: Some("NUL".to_owned()),
            },
            &token,
        ),
        Err(GitError::InvalidPath)
    ));
    token.cancel();
    assert!(matches!(
        adapter.review_status_all(&token),
        Err(GitError::Cancelled)
    ));
}

/// 证明非法 UTF-8 会被拒绝，而不是有损替换成 Workbench 后续可能请求的另一个文件名。
#[test]
fn parser_rejects_non_utf8_path_records() {
    assert!(matches!(
        super::parse::parse_status(&[b'?', b' ', 0xff, 0], 10),
        Err(GitError::Parse)
    ));
}

/// 证明每条 porcelain 记录都消耗共享预算，rename 的 NUL 分隔原路径无法绕过状态记录上限。
#[test]
fn status_parser_counts_all_record_types_and_rename_companion() {
    let bytes = b"# branch.head main\0\
1 XY N... 100644 100644 100644 abcdef abcdef file.txt\0\
2 R. N... 100644 100644 100644 abcdef abcdef R100 renamed.txt\0old.txt\0\
u UU N... 100644 100644 100644 100644 abcdef abcdef abcdef conflict.txt\0\
? untracked\0! ignored\0";
    assert_eq!(
        super::parse::parse_status(bytes, 7)
            .expect("exact status record budget")
            .len(),
        6
    );
    assert!(matches!(
        super::parse::parse_status(bytes, 6),
        Err(GitError::Parse)
    ));
}

/// 证明短 NUL 记录刚超过显式预算时会在部分结果逸出前失败。
#[test]
fn status_parser_rejects_record_overflow_without_partial_result() {
    let max_records = 4096;
    let mut bytes = Vec::with_capacity((max_records + 1) * 4);
    for _ in 0..=max_records {
        bytes.extend_from_slice(b"? x\0");
    }
    assert!(matches!(
        super::parse::parse_status(&bytes, max_records),
        Err(GitError::Parse)
    ));
    bytes.truncate(bytes.len() - 4);
    assert_eq!(
        super::parse::parse_status(&bytes, max_records)
            .expect("near-limit status records")
            .len(),
        max_records
    );
}
