// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "../../src/controller/server_stop.rs"]
mod server_stop;

use serde_json::{Value, json};
use server_stop::{StopServerError, stop_server};

/// 普通停止不带 force，且只有服务端确认状态和 accepted 字段后才成功。
#[test]
fn graceful_stop_uses_shutdown_rpc_and_requires_confirmation() {
    let mut called = false;
    let result = stop_server(false, |method, params| {
        called = true;
        assert_eq!(method, "runtime/shutdown");
        assert_eq!(params, json!({}));
        Ok::<Value, String>(json!({"accepted":true,"status":"shutting_down"}))
    });

    assert!(called);
    assert_eq!(result, Ok(()));
}

/// 强制停止也使用同一 shutdown RPC，只把 true 覆盖传给 Java 权威校验。
#[test]
fn forced_stop_sends_force_and_requires_confirmation() {
    let result = stop_server(true, |method, params| {
        assert_eq!(method, "runtime/shutdown");
        assert_eq!(params, json!({"force":true}));
        Ok::<Value, String>(json!({"accepted":true,"status":"shutting_down"}))
    });

    assert_eq!(result, Ok(()));
}

/// Java 的拒绝错误必须原样返回给 CLI，不能变成笼统 transport failure。
#[test]
fn request_error_is_preserved() {
    let result = stop_server(false, |_, _| Err("-32009 runtime has pending work"));

    assert_eq!(
        result,
        Err(StopServerError::Request("-32009 runtime has pending work"))
    );
}

/// 缺少 accepted、拒绝状态或非 shutting_down 回执都不能报告停止成功。
#[test]
fn rejection_and_invalid_receipts_are_not_success() {
    for response in [
        json!({"accepted":false,"status":"shutting_down"}),
        json!({"accepted":true,"status":"running"}),
        json!({"status":"shutting_down"}),
    ] {
        let result = stop_server(false, |_, _| Ok::<Value, String>(response));
        assert_eq!(result, Err(StopServerError::Unconfirmed));
    }
}
