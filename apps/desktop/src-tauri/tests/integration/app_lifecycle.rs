// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 完整退出状态机使用独立 Cargo test 进程，避免不可逆的进程级 Watcher admission
//! 与其它白盒集成测试共享全局生命周期。

#![allow(dead_code, unused_imports, unused_macros)]

#[macro_use]
#[path = "../../src/lib.rs"]
mod production;

pub(crate) use production::*;

/// 独立退出生命周期 test target 复刻生产固定窗口 identity，保证托盘模块重新编译时不改写产品边界。
const MAIN_WINDOW_LABEL: &str = "main";

#[path = "../support/app_runtime.rs"]
pub(crate) mod runtime_test_support;

mod tests {
    include!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/integration/app_lifecycle_tests.rs"
    ));
}
