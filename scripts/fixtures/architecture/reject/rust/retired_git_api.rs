// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

/// 拒绝样例故意恢复旧 Tauri Git API，门禁必须命中。
fn ja_git_status() {}

/// 同一拒绝样例覆盖旧 diff API，避免未来只保护其中一个符号。
fn ja_git_diff() {}

/// 同一拒绝样例覆盖旧 snapshot API，避免未来重建通用 Git façade。
fn ja_git_snapshot() {}
