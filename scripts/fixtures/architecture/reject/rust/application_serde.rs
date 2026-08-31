// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

use serde_json::json;

/// application 不应直接构造动态 JSON，此函数用于拒绝 fixture。
fn build_request() {
    let _request = json!({});
}
