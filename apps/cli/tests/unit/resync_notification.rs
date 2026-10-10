// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

#[path = "../../src/controller/resync_notification.rs"]
mod resync_notification;

use resync_notification::{ResyncNotificationDeduper, parse_resync_required};
use serde_json::{Value, json};

/// 返回含公共通知序号的 JA-RPC 闭集 payload，不把该序号混作 Thread revision。
fn valid_params() -> Value {
    json!({
        "serverInstanceId": "srv_cli_fixture",
        "eventId": "evt_cli_resync",
        "sequence": 66,
        "occurredAt": "2026-09-27T00:00:00Z",
        "generation": 1,
        "workspaceId": "ws_cli_fixture",
        "threadId": "thr_cli_fixture",
        "turnId": "turn_cli_fixture",
        "reasonCode": "projection_failed"
    })
}

/// CLI 校验连接序号但不使用它恢复；额外 Thread revision 不能伪装成持久游标。
#[test]
fn parser_accepts_the_ordered_notification_envelope_and_rejects_cursor_fields() {
    let parsed = parse_resync_required(&valid_params()).expect("valid resync notification");
    assert_eq!(parsed.event_id, "evt_cli_resync");
    assert_eq!(parsed.workspace_id, "ws_cli_fixture");
    assert_eq!(parsed.thread_id, "thr_cli_fixture");

    let mut invalid_sequence = valid_params();
    invalid_sequence["sequence"] = json!(0);
    assert!(parse_resync_required(&invalid_sequence).is_err());

    let mut fabricated_cursor = valid_params();
    fabricated_cursor["threadRevision"] = json!(9);
    assert!(parse_resync_required(&fabricated_cursor).is_err());

    let mut unsupported_reason = valid_params();
    unsupported_reason["reasonCode"] = json!("terminal_missing");
    assert!(parse_resync_required(&unsupported_reason).is_err());

    let mut malformed_time = valid_params();
    malformed_time["occurredAt"] = json!("not-a-timestamp");
    assert!(parse_resync_required(&malformed_time).is_err());
}

/// 单个故障信号只触发一次回读，缓存有上限且淘汰后仍允许将来重投恢复。
#[test]
fn deduper_is_idempotent_and_bounded() {
    let mut deduper = ResyncNotificationDeduper::default();
    assert!(deduper.remember_once("evt_first"));
    assert!(!deduper.remember_once("evt_first"));
    for index in 0..128 {
        assert!(deduper.remember_once(&format!("evt_{index}")));
    }
    assert!(deduper.remember_once("evt_first"));
}
