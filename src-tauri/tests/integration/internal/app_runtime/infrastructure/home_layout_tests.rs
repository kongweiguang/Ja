// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::HomeLayout;

/// 只验证 Rust 创建目录外壳而不提前创建数据库，确保 SQLite 的创建与迁移仍由 Java 唯一负责。
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
    let _ = std::fs::remove_dir_all(root);
}
