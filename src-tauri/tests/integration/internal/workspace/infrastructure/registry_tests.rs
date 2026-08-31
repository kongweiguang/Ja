// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// 验证普通 canonical 准入，并证明目录别名会在 canonicalization 抹去原始 spelling 前被拒绝。
#[test]
fn register_checks_raw_alias_before_canonicalize() {
    let base = std::env::temp_dir().join(format!("ja-workspace-registry-{}", Uuid::new_v4()));
    let root = base.join("root");
    let alias = base.join("alias");
    fs::create_dir_all(&root).expect("workspace root");
    let registry = WorkspaceRegistry::default();
    let info = registry.register(&root).expect("normal canonical root");
    let handle = registry.get(info.id).expect("registered handle");
    assert_eq!(
        handle.root_path(),
        fs::canonicalize(&root).expect("canonical root")
    );

    if create_directory_alias(&root, &alias) {
        assert!(matches!(
            registry.register(&alias),
            Err(WorkspaceError::InvalidRoot)
        ));
    }
    let _ = fs::remove_dir_all(base);
}

/// 为原始路径准入回归创建平台原生别名；不支持的平台只保留普通根目录断言。
fn create_directory_alias(target: &Path, alias: &Path) -> bool {
    #[cfg(unix)]
    {
        std::os::unix::fs::symlink(target, alias).is_ok()
    }
    #[cfg(windows)]
    {
        std::os::windows::fs::symlink_dir(target, alias).is_ok()
    }
    #[cfg(not(any(unix, windows)))]
    {
        let _ = (target, alias);
        false
    }
}

/// 验证没有 deadline 时 hash 仍受读取预算限制；精确上限可读，普通和 sparse 超限文件均拒绝。
/// 测试与私有算法同模块放置，避免为测试扩大 production 可见性或新增 `*_for_test` API。
#[test]
fn hash_cap_is_bounded_without_deadline() {
    let base = std::env::temp_dir().join(format!("ja-workspace-hash-{}", Uuid::new_v4()));
    fs::create_dir_all(&base).expect("create hash fixture");

    let exact = base.join("exact.bin");
    fs::write(&exact, [1_u8; 8]).expect("write exact file");
    assert!(
        hash_file_until(&exact, 8, None).is_ok(),
        "a file at the limit remains hashable"
    );

    let oversized = base.join("oversized.bin");
    fs::write(&oversized, [2_u8; 9]).expect("write oversized file");
    assert!(matches!(
        hash_file_until(&oversized, 8, None),
        Err(WorkspaceError::FileTooLarge)
    ));

    let sparse = base.join("sparse.bin");
    let sparse_file = fs::File::create(&sparse).expect("create sparse file");
    sparse_file.set_len(9).expect("extend sparse file");
    assert!(matches!(
        hash_file_until(&sparse, 8, None),
        Err(WorkspaceError::FileTooLarge)
    ));

    let _ = fs::remove_dir_all(base);
}
