// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use serde_json::{Value, json};

#[derive(Debug, PartialEq, Eq)]
pub enum StopServerError<E> {
    Request(E),
    Unconfirmed,
}

/// 把两种停止模式统一在 JA-RPC 确认边界，保留服务端错误并拒绝不完整的成功回执。
pub fn stop_server<E>(
    force: bool,
    request: impl FnOnce(&str, Value) -> Result<Value, E>,
) -> Result<(), StopServerError<E>> {
    let params = if force {
        json!({"force":true})
    } else {
        json!({})
    };
    let result = request("runtime/shutdown", params).map_err(StopServerError::Request)?;
    if result.get("accepted").and_then(Value::as_bool) != Some(true)
        || result.get("status").and_then(Value::as_str) != Some("shutting_down")
    {
        return Err(StopServerError::Unconfirmed);
    }
    Ok(())
}
