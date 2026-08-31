// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::review_git_support::{TempDir, fixture_repo, git_program, run_git};
use super::*;
use crate::workspace::WorkspaceRegistry;
use std::fs;

/// 普通目录不是 Git 故障或 Runtime 故障；在启动 Git 子进程前返回独立语义，供 Workbench
/// 显示真实不可用状态。
#[test]
fn non_git_workspace_is_reported_before_git_execution() {
    let root = TempDir::create();
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register non-git root");
    let workspace = registry.get(info.id).expect("get non-git root");

    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::NotRepository)
    ));
}

/// 证明 linked worktree 不能让外部 Git 目录获得信任。
#[test]
fn external_gitdir_pointer_is_rejected() {
    let root = TempDir::create();
    let outside = TempDir::create();
    fs::write(
        root.0.join(".git"),
        format!("gitdir: {}\n", outside.0.display()),
    )
    .expect("write external gitdir pointer");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register root");
    let workspace = registry.get(info.id).expect("get root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明两种 alternates transport 即使指回工作区也会被拒绝，使 `show HEAD` 无法访问外部对象。
#[test]
fn alternates_files_are_rejected_before_object_reads() {
    for file_name in ["alternates", "http-alternates"] {
        for value in ["../objects\n", "external-object-store\n"] {
            let Some((root, _git)) = fixture_repo() else {
                return;
            };
            let path = root
                .0
                .join(".git")
                .join("objects")
                .join("info")
                .join(file_name);
            fs::write(path, value).expect("write alternates fixture");
            let registry = WorkspaceRegistry::default();
            let info = registry
                .register(&root.0)
                .expect("register alternates root");
            let workspace = registry.get(info.id).expect("get alternates root");
            assert!(matches!(
                GitReadOnly::new(workspace),
                Err(GitError::ExternalWorktree)
            ));
        }
    }
}

/// 证明空文件是唯一允许的 alternates 状态，畸形 UTF-8 会被拒绝且不会进行有损路径解释。
#[test]
fn alternates_empty_and_invalid_bytes_are_bounded() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let path = root
        .0
        .join(".git")
        .join("objects")
        .join("info")
        .join("alternates");
    fs::write(&path, []).expect("write empty alternates");
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register empty alternates root");
    let workspace = registry.get(info.id).expect("get empty alternates root");
    assert!(GitReadOnly::new(workspace.clone()).is_ok());
    fs::write(path, [0xff]).expect("write invalid alternates");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明 alternates 文件不能消耗无界元数据缓冲，也不能通过硬链接别名引用工作区外文件。
#[test]
fn alternates_size_and_hard_link_are_rejected() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let path = root
        .0
        .join(".git")
        .join("objects")
        .join("info")
        .join("alternates");
    fs::write(&path, vec![b'a'; 4 * 1024 + 1]).expect("write oversized alternates");
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register oversized alternates root");
    let workspace = registry
        .get(info.id)
        .expect("get oversized alternates root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));

    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let outside = TempDir::create();
    let outside_file = outside.0.join("objects.txt");
    fs::write(&outside_file, "external\n").expect("write external alternates target");
    let path = root
        .0
        .join(".git")
        .join("objects")
        .join("info")
        .join("alternates");
    if fs::hard_link(&outside_file, &path).is_err() {
        return;
    }
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register hard-linked alternates root");
    let workspace = registry
        .get(info.id)
        .expect("get hard-linked alternates root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明本地配置不能 include 其他文件、启用 lazy object，或通过 Git 配置调用外部进程与网络路径。
#[test]
fn unsafe_local_git_config_is_rejected_without_following_paths() {
    let outside = TempDir::create();
    let outside_path = outside.0.display().to_string();
    let cases = [
        format!("[include]\n\tpath = {outside_path}\n"),
        format!("[includeIf \"gitdir:ja\"]\n\tpath = {outside_path}\n"),
        "[extensions]\n\tpartialClone = origin\n".to_owned(),
        "[remote \"origin\"]\n\tpromisor = true\n".to_owned(),
        format!("[core]\n\tworktree = {outside_path}\n"),
        "[core]\n\tfsmonitor = true\n".to_owned(),
        "[core]\n\tsshCommand = ssh external\n".to_owned(),
        "[diff]\n\texternal = !echo external\n".to_owned(),
    ];
    for config in cases {
        let Some((root, _git)) = fixture_repo() else {
            return;
        };
        fs::write(root.0.join(".git").join("config"), config).expect("write unsafe config");
        let registry = WorkspaceRegistry::default();
        let info = registry
            .register(&root.0)
            .expect("register unsafe config root");
        let workspace = registry.get(info.id).expect("get unsafe config root");
        assert!(matches!(
            GitReadOnly::new(workspace),
            Err(GitError::ExternalWorktree)
        ));
    }
}

/// 证明标准 worktreeConfig 扩展可用于普通仓库；专属配置仍由同一安全解析器约束。
#[test]
fn safe_worktree_config_extension_is_accepted() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    fs::write(
        root.0.join(".git").join("config"),
        "[core]\n\trepositoryformatversion = 0\n\tbare = false\n[extensions]\n\tworktreeConfig = true\n",
    )
    .expect("write worktree config extension");
    fs::write(
        root.0.join(".git").join("config.worktree"),
        "[core]\n\tsymlinks = false\n",
    )
    .expect("write safe worktree config");
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register safe worktree config root");
    let workspace = registry
        .get(info.id)
        .expect("get safe worktree config root");

    GitReadOnly::new(workspace).expect("accept safely validated worktree config");
}

/// 证明 Git 中断后遗留的不可寻址 loose/pack 临时对象不会让普通仓库被误判为非 Git；这些
/// 普通文件不会被 Git 按对象或 pack identity 读取，边界元数据与任何链接仍继续安全校验。
#[test]
fn stale_git_temporary_objects_are_ignored() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let fanout = root.0.join(".git").join("objects").join("aa");
    fs::create_dir_all(&fanout).expect("create temporary-object fanout");
    fs::write(fanout.join("tmp_obj_interrupted"), b"incomplete").expect("write stale object");
    fs::write(
        root.0
            .join(".git")
            .join("objects")
            .join("pack")
            .join("tmp_pack_aB3dE9"),
        b"incomplete",
    )
    .expect("write stale pack");
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register stale-object root");
    let workspace = registry.get(info.id).expect("get stale-object root");

    GitReadOnly::new(workspace).expect("ignore unaddressable ordinary Git temporary files");
}

/// 证明 worktree 专属配置在内部 Git 命令采纳外部路径前，也使用同一有界解析器校验。
#[test]
fn unsafe_worktree_config_is_rejected_without_following_paths() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let outside = TempDir::create();
    fs::write(
        root.0.join(".git").join("config.worktree"),
        format!("[core]\n\tworktree = {}\n", outside.0.display()),
    )
    .expect("write unsafe worktree config");
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register unsafe worktree config root");
    let workspace = registry
        .get(info.id)
        .expect("get unsafe worktree config root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明 promisor pack 标记与 submodule 元数据不会被准入，避免 Git 查询另一对象或仓库信任根。
#[test]
fn promisor_and_submodule_metadata_are_rejected() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    fs::write(
        root.0
            .join(".git")
            .join("objects")
            .join("pack")
            .join("pack.promisor"),
        [],
    )
    .expect("write promisor marker");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register promisor root");
    let workspace = registry.get(info.id).expect("get promisor root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));

    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    fs::write(root.0.join(".gitmodules"), "[submodule \"external\"]\n")
        .expect("write submodule metadata");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register submodule root");
    let workspace = registry.get(info.id).expect("get submodule root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明普通仓库的 commondir 指针即使 `.git` 保持本地，也不能把对象读取重定向到根目录外。
#[test]
fn external_commondir_pointer_is_rejected() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let outside = TempDir::create();
    fs::write(
        root.0.join(".git").join("commondir"),
        format!("{}\n", outside.0.display()),
    )
    .expect("write external commondir");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register commondir root");
    let workspace = registry.get(info.id).expect("get commondir root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明 bare 仓库不能通过把 HEAD 与 alternates 直接放在已准入工作区根目录而绕过对象存储校验。
#[test]
fn bare_repository_alternates_are_rejected() {
    let Some(git) = git_program() else {
        return;
    };
    let root = TempDir::create();
    run_git(&git, &root.0, &["init", "--bare", "-q"]);
    fs::write(
        root.0.join("objects").join("info").join("alternates"),
        "external-object-store\n",
    )
    .expect("write bare alternates fixture");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register bare root");
    let workspace = registry.get(info.id).expect("get bare root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明能够改变 Git 读取边界的对象元数据 hard-link 会在 adapter 读取前被拒绝；松散对象是
/// 内容寻址数据，不逐文件打开复核，以保证大型仓库的 Turn 启动时间有界。
#[test]
fn external_object_metadata_hard_links_are_rejected() {
    let targets = [
        ".git/objects/pack/pack-0123456789abcdef0123456789abcdef01234567.pack",
        ".git/objects/pack/pack-0123456789abcdef0123456789abcdef01234567.idx",
        ".git/objects/pack/multi-pack-index",
        ".git/objects/info/packs",
        ".git/objects/info/commit-graph",
        ".git/objects/info/commit-graphs/graph-0123456789abcdef0123456789abcdef01234567.graph",
        ".git/objects/info/commit-graphs/commit-graph-chain",
    ];
    for target in targets {
        let Some((root, _git)) = fixture_repo() else {
            return;
        };
        let outside = TempDir::create();
        let secret = outside.0.join("EXTERNAL-SECRET");
        fs::write(&secret, b"EXTERNAL-SECRET").expect("write external object secret");
        let object_path = root
            .0
            .join(target.replace('/', std::path::MAIN_SEPARATOR_STR));
        fs::create_dir_all(object_path.parent().expect("object parent"))
            .expect("create object metadata parent");
        fs::hard_link(&secret, &object_path).expect("create external object hard link");
        let registry = WorkspaceRegistry::default();
        let info = registry
            .register(&root.0)
            .expect("register hard-linked object root");
        let workspace = registry.get(info.id).expect("get hard-linked object root");
        assert!(matches!(
            GitReadOnly::new(workspace),
            Err(GitError::ExternalWorktree)
        ));
    }
}

/// 证明 fanout 目录被 symlink/reparse point 替换时，会在递归遍历跨越目标前失败关闭。
#[test]
fn fanout_directory_link_is_rejected() {
    let Some((root, _git)) = fixture_repo() else {
        return;
    };
    let outside = TempDir::create();
    let fanout = root.0.join(".git").join("objects").join("cc");
    #[cfg(unix)]
    let linked = std::os::unix::fs::symlink(&outside.0, &fanout).is_ok();
    #[cfg(windows)]
    let linked = std::os::windows::fs::symlink_dir(&outside.0, &fanout).is_ok();
    #[cfg(not(any(unix, windows)))]
    let linked = false;
    if !linked {
        return;
    }
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register linked fanout root");
    let workspace = registry.get(info.id).expect("get linked fanout root");
    assert!(matches!(
        GitReadOnly::new(workspace),
        Err(GitError::ExternalWorktree)
    ));
}

/// 证明 Git 将松散对象压缩为 pack/index 元数据后，严格语法仍能准入普通仓库。
#[test]
fn packed_repository_remains_readable() {
    let Some((root, git)) = fixture_repo() else {
        return;
    };
    run_git(&git, &root.0, &["gc", "--aggressive", "--no-quiet"]);
    run_git(&git, &root.0, &["update-server-info"]);
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root.0).expect("register packed root");
    let workspace = registry.get(info.id).expect("get packed root");
    let adapter = GitReadOnly::new(workspace).expect("packed git adapter");
    let token = CancellationToken::default();
    assert_eq!(adapter.log(10, &token).expect("packed log").len(), 1);
}

/// 证明 split commit-graph 元数据受严格 info-tree 语法约束，同时普通 Git 读取入口仍可用。
#[test]
fn split_commit_graph_repository_remains_readable() {
    let Some((root, git)) = fixture_repo() else {
        return;
    };
    run_git(
        &git,
        &root.0,
        &["commit-graph", "write", "--reachable", "--split"],
    );
    let registry = WorkspaceRegistry::default();
    let info = registry
        .register(&root.0)
        .expect("register split commit graph root");
    let workspace = registry.get(info.id).expect("get split commit graph root");
    let adapter = GitReadOnly::new(workspace).expect("split commit graph adapter");
    assert_eq!(
        adapter
            .log(10, &CancellationToken::default())
            .expect("split commit graph log")
            .len(),
        1
    );
}
