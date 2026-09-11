// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use super::*;

/// 缺失文件保持后台默认值，且首次读取不会制造持久化副作用。
#[test]
fn missing_preferences_use_background_without_writing() {
    let directory = std::env::temp_dir().join(format!("ja-close-behavior-{}", Uuid::new_v4()));
    let path = DesktopPreferences::file_path(&directory);
    let preferences = DesktopPreferences::load(path.clone());

    assert_eq!(preferences.read(), CloseBehavior::Background);
    assert!(!path.exists());
}

/// 保存只在原子替换完成后更新内存，并可由新实例读取相同值。
#[test]
fn save_round_trips_through_atomic_file() {
    let directory = std::env::temp_dir().join(format!("ja-close-behavior-{}", Uuid::new_v4()));
    let path = DesktopPreferences::file_path(&directory);
    let preferences = DesktopPreferences::load(path.clone());

    preferences.save(CloseBehavior::Exit).expect("save preference");
    assert_eq!(preferences.read(), CloseBehavior::Exit);
    assert_eq!(DesktopPreferences::load(path).read(), CloseBehavior::Exit);

    let _ = std::fs::remove_dir_all(directory);
}

/// 非法文档只回退内存默认值，不覆盖原始文件，便于用户恢复或重试。
#[test]
fn invalid_document_does_not_get_overwritten() {
    let directory = std::env::temp_dir().join(format!("ja-close-behavior-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&directory).expect("create test directory");
    let path = DesktopPreferences::file_path(&directory);
    let invalid = br##"{"schemaVersion":1,"closeBehavior":"invalid"}"##;
    std::fs::write(&path, invalid).expect("write invalid preference");

    let preferences = DesktopPreferences::load(path.clone());
    assert_eq!(preferences.read(), CloseBehavior::Background);
    assert_eq!(std::fs::read(path).expect("read invalid preference"), invalid);

    let _ = std::fs::remove_dir_all(directory);
}

/// 目标目录不可写时保存返回可重试错误，并继续使用之前已经生效的内存值。
#[test]
fn failed_save_keeps_previous_memory_value() {
    let root = std::env::temp_dir().join(format!("ja-close-behavior-{}", Uuid::new_v4()));
    std::fs::create_dir_all(&root).expect("create test directory");
    let parent_file = root.join("not-a-directory");
    std::fs::write(&parent_file, b"occupied").expect("create blocking file");
    let path = parent_file.join("desktop-preferences.json");
    let preferences = DesktopPreferences::load(path);

    assert_eq!(
        preferences.save(CloseBehavior::Exit),
        Err("DESKTOP_PREFERENCES_WRITE_FAILED")
    );
    assert_eq!(preferences.read(), CloseBehavior::Background);

    let _ = std::fs::remove_dir_all(root);
}
