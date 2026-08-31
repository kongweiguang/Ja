// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use crate::workspace::ProtocolValue as Value;

/// owner 根 alias 不能掩盖 application 对动态 JSON 的依赖。
fn accept_value(value: Value) -> Value {
    value
}
