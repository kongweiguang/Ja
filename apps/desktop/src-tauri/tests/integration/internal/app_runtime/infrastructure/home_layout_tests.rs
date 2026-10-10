// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::HomeLayout;

/// 每个布局场景使用独立目录，不改变 USERPROFILE/HOME 或竞争进程级环境。
fn temporary_root() -> std::path::PathBuf {
    std::env::temp_dir().join(format!(
        "ja-home-layout-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock")
            .as_nanos()
    ))
}

/// 只创建有明确 owner 的目录外壳；数据库仍由 Java 创建，已淘汰的 backups 空壳不得复活。
#[test]
fn layout_does_not_precreate_database_file() {
    let root = temporary_root();
    let home = HomeLayout::from_root(root.clone()).expect("home layout");
    assert!(home.paths().data_dir().is_dir());
    assert!(!home.paths().data_dir().join("ja.db").exists());
    assert!(!root.join("backups").exists());
    let _ = std::fs::remove_dir_all(root);
}

/// 正式覆盖必须让配置、数据、日志和 endpoint 同源，并在 USERPROFILE 存在时仍优先选择 JA_HOME。
#[test]
fn ja_home_redirects_the_entire_layout() {
    let root = temporary_root();
    let old_profile = temporary_root();
    let home = HomeLayout::from_environment(|name| match name {
        "JA_HOME" => Some(root.clone().into_os_string()),
        "USERPROFILE" => Some(old_profile.clone().into_os_string()),
        _ => None,
    })
    .expect("explicit Ja home");
    assert_eq!(home.paths().root(), root);
    assert_eq!(home.paths().data_dir(), root.join("data"));
    assert_eq!(home.paths().run_dir(), root.join("run"));
    assert_eq!(home.paths().java_logs_dir(), root.join("logs/java"));
    assert!(!old_profile.exists());
    std::fs::remove_dir_all(root).expect("remove owned home");
}

/// 缺省选择保持已有用户布局，且 USERPROFILE 优先于跨平台 HOME。
#[test]
fn missing_ja_home_uses_the_user_profile() {
    let profile = temporary_root();
    let home = HomeLayout::from_environment(|name| match name {
        "USERPROFILE" => Some(profile.clone().into_os_string()),
        "HOME" => Some("ignored-profile".into()),
        _ => None,
    })
    .expect("default home");
    assert_eq!(home.paths().root(), profile.join(".ja"));
    std::fs::remove_dir_all(profile).expect("remove owned profile");
}

/// 显式错误不能回落默认库；拒绝发生在目录创建前，防止错误隔离参数修改用户历史。
#[test]
fn invalid_ja_home_never_falls_back_to_profile() {
    let profile = temporary_root();
    let root = profile.ancestors().last().expect("filesystem root");
    for input in [
        std::path::PathBuf::new(),
        "relative-home".into(),
        profile.join("../escape"),
        root.to_path_buf(),
    ] {
        let result = HomeLayout::from_environment(|name| match name {
            "JA_HOME" => Some(input.clone().into_os_string()),
            "USERPROFILE" => Some(profile.clone().into_os_string()),
            _ => None,
        });
        assert_eq!(
            result.expect_err("invalid explicit home"),
            super::HomeError::InvalidRoot
        );
        assert!(!profile.exists());
    }
}
