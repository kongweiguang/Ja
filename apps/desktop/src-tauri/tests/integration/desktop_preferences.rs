// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 桌面偏好真实文件持久化的独立 integration target；不把 filesystem IO 放入 unit target。

#![allow(dead_code, unused_imports, unused_macros)]

#[macro_use]
#[path = "../../src/lib.rs"]
mod production;

pub(crate) use production::*;

/// 外置白盒 crate 复刻生产组合根使用的主窗口 identity，使生产模块保持同一解析环境。
const MAIN_WINDOW_LABEL: &str = "main";

mod desktop_preferences_scope {
    pub(crate) use crate::production::app_runtime::interface::desktop_preferences::*;
    pub(crate) use uuid::Uuid;

    mod tests {
        include!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/tests/integration/desktop_preferences_tests.rs"
        ));
    }
}
