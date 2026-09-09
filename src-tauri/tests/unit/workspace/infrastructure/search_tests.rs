// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use std::io::Cursor;

/// deadline 耗尽必须投影为局部搜索信号，而不是让整个 Search Tab 失败的 command error。
#[test]
fn bounded_read_reports_deadline_as_partial() {
    let result = read_bounded_until(Cursor::new(vec![1_u8; 32]), 64, Instant::now())
        .expect("deadline is a bounded result");
    assert!(result.is_none());
}

/// 生成与 cache 目录策略保持显式闭集，不能因名称相似而静默排除普通源码目录。
#[test]
fn default_ignored_directory_policy_is_narrow() {
    assert!(is_default_ignored_directory(".git"));
    assert!(is_default_ignored_directory("TARGET"));
    assert!(is_default_ignored_directory("target-workspace-switch-fix"));
    assert!(is_default_ignored_directory(".codex-target"));
    assert!(is_default_ignored_directory(".tmp-ci-artifacts"));
    assert!(!is_default_ignored_directory("src"));
    assert!(!is_default_ignored_directory("vendor"));
    assert!(!is_default_ignored_directory(".codex-target-backup"));
}

/// Watcher 使用相对路径过滤时必须识别任意层级和 Windows separator，同时保留源码目录。
#[test]
fn ignored_relative_path_policy_matches_nested_generated_directories() {
    assert!(is_default_ignored_relative_path(
        "app-server/target/classes/App.class"
    ));
    assert!(is_default_ignored_relative_path(
        r"apps\desktop\node_modules\vite\index.js"
    ));
    assert!(is_default_ignored_relative_path(
        r".codex-target\debug\deps\ja.exe"
    ));
    assert!(!is_default_ignored_relative_path(
        "src/targeting/service.rs"
    ));
}
