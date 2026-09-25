// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::HomeLayout;

/// 只创建有明确 owner 的目录外壳；数据库仍由 Java 创建，已淘汰的 backups 空壳不得复活。
#[test]
fn layout_does_not_precreate_database_file() {
    let root = std::env::temp_dir().join(format!(
        "ja-home-layout-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock")
            .as_nanos()
    ));
    let home = HomeLayout::from_root(root.clone()).expect("home layout");
    assert!(home.paths().data_dir().is_dir());
    assert!(!home.paths().data_dir().join("ja.db").exists());
    assert!(!root.join("backups").exists());
    let _ = std::fs::remove_dir_all(root);
}
