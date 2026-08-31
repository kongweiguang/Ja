// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use std::collections::BTreeMap;

/// 改名后的完整 JSON 树仍属于动态协议值，不应由 application 持有。
enum PayloadNode {
    Empty,
    Flag(bool),
    Negative(i64),
    Positive(u64),
    Fraction(String),
    Text(String),
    Sequence(Vec<Self>),
    Fields(BTreeMap<String, Self>),
}
