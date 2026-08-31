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
    assert!(is_default_ignored_directory(".tmp-ci-artifacts"));
    assert!(!is_default_ignored_directory("src"));
    assert!(!is_default_ignored_directory("vendor"));
}
