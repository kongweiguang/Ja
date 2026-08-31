// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// release 构建依附桌面窗口而不打开第二个 console；debug 构建保留 console 供本地诊断。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

/// 将常规 composition 委派给 library，使 Tauri 在桌面目标与未来 mobile-specific attribute
/// 下仍只有一个原生入口。
fn main() {
    ja_lib::run()
}
