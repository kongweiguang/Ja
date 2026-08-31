// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// Ready 投影必须在 WebView serialization 前消费 challenge。
#[test]
fn ready_projection_consumes_the_validated_echo() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "runtime/status-changed",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_ready_1",
            "sequence": 1,
            "occurredAt": "2026-08-18T00:00:00Z",
            "status": "ready",
            "generation": 1,
            "readyToken": "0123456789abcdef0123456789abcdef"
        }
    });
    let sanitized = sanitize_webview_value(value).expect("ready projection is safe");
    assert!(sanitized["params"].get("readyToken").is_none());
}

/// 已脱敏展示文本可能与 challenge 共享 32-hex shape；Session 消费精确 challenge 后，该普通
/// preview 仍必须保留。
#[test]
fn arbitrary_hex_business_id_is_preserved() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "assistant/model-step-committed",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_tool_1",
            "sequence": 1,
            "generation": 1,
            "workspaceId": "ws_1",
            "threadId": "thr_1",
            "turnId": "turn_1",
            "threadRevision": 1,
            "occurredAt": "2026-08-18T00:00:00Z",
            "messageId": "item_assistant_1",
            "text": "",
            "modelRound": 1,
            "toolCalls": [{
                "callId": "call_1",
                "toolName": "shell",
                "presentation": {
                    "kind": "shell", "title": "运行命令", "status": "pending",
                    "inputPreview": "0123456789abcdef0123456789abcdef",
                    "relativePaths": [], "command": "git status --short",
                    "relativeCwd": "workspace", "truncated": false
                },
                "ordinal": 0
            }]
        }
    });
    let sanitized = sanitize_webview_value(value).expect("business id is not a challenge");
    assert_eq!(
        sanitized["params"]["toolCalls"][0]["presentation"]["inputPreview"],
        "0123456789abcdef0123456789abcdef"
    );
}

/// semantic event 必须携带冻结 threadRevision identity，并拒绝已删除的 per-event seq field。
#[test]
fn semantic_projection_requires_thread_revision() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "turn/state-changed",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_user_1",
            "sequence": 1,
            "generation": 1,
            "workspaceId": "ws_1",
            "threadId": "thr_1",
            "turnId": "turn_1",
            "threadRevision": 2,
            "occurredAt": "2026-08-18T00:00:00Z",
            "from": "queued",
            "to": "running"
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    let mut invalid = value;
    invalid["params"]
        .as_object_mut()
        .expect("params")
        .remove("threadRevision");
    invalid["params"]["seq"] = json!(2);
    assert_eq!(
        sanitize_webview_value(invalid).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// 三类 Context 生命周期接受显式 nullable turnId，并按状态绑定 after/checkpoint/error 字段。
#[test]
fn context_compaction_events_enforce_thread_level_lifecycle() {
    let started = json!({
        "jsonrpc": "2.0",
        "method": "context/compaction-started",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_compaction_1",
            "sequence": 1, "occurredAt": "2026-08-29T00:00:00Z", "generation": 1,
            "workspaceId": "ws_1", "threadId": "thr_1", "turnId": null,
            "threadRevision": 4, "compactionId": "cmp_1", "trigger": "manual",
            "sourceRevision": 4, "inputTokensBefore": 100, "inputTokensAfter": null,
            "strategyVersion": "ja-context-v3"
        }
    });
    assert!(sanitize_webview_value(started.clone()).is_ok());

    let compacted = json!({
        "jsonrpc": "2.0",
        "method": "context/compacted",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_compaction_2",
            "sequence": 2, "occurredAt": "2026-08-29T00:00:01Z", "generation": 1,
            "workspaceId": "ws_1", "threadId": "thr_1", "turnId": "turn_1",
            "threadRevision": 5, "compactionId": "cmp_2", "trigger": "automatic",
            "sourceRevision": 4, "inputTokensBefore": 100, "inputTokensAfter": 60,
            "strategyVersion": "ja-context-v3", "checkpointId": "checkpoint_1"
        }
    });
    assert!(sanitize_webview_value(compacted.clone()).is_ok());

    let failed = json!({
        "jsonrpc": "2.0",
        "method": "context/compaction-failed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_compaction_3",
            "sequence": 3, "occurredAt": "2026-08-29T00:00:02Z", "generation": 1,
            "workspaceId": "ws_1", "threadId": "thr_1", "turnId": "turn_1",
            "threadRevision": 5, "compactionId": "cmp_3", "trigger": "overflow_recovery",
            "sourceRevision": 5, "inputTokensBefore": null, "inputTokensAfter": null,
            "strategyVersion": "ja-context-v3", "errorCode": "TOKEN_COUNT_UNAVAILABLE"
        }
    });
    assert!(sanitize_webview_value(failed.clone()).is_ok());

    let mut started_with_after = started;
    started_with_after["params"]["inputTokensAfter"] = json!(99);
    assert!(sanitize_webview_value(started_with_after).is_err());

    let mut compacted_without_checkpoint = compacted;
    compacted_without_checkpoint["params"]
        .as_object_mut()
        .expect("params")
        .remove("checkpointId");
    assert!(sanitize_webview_value(compacted_without_checkpoint).is_err());

    let mut failed_with_unknown_error = failed;
    failed_with_unknown_error["params"]["errorCode"] = json!("PROVIDER_SECRET_FAILURE");
    assert!(sanitize_webview_value(failed_with_unknown_error).is_err());
}

/// Config invalidation 只保留公共 v2 metadata、scope、opaque version 与 optional server
/// Workspace identity；继续禁止 renderer-facing cwd alias。
#[test]
fn config_changed_projection_requires_workspace_identity_for_projects() {
    let user = json!({
        "jsonrpc": "2.0",
        "method": "configuration/changed",
        "params": {"serverInstanceId": "srv_1", "eventId": "evt_config_1",
            "sequence": 1, "occurredAt": "2026-08-18T00:00:00Z", "generation": 1,
            "scope": "user", "version": "cfg_user_1"}
    });
    assert!(sanitize_webview_value(user).is_ok());

    let project = json!({
        "jsonrpc": "2.0",
        "method": "configuration/changed",
        "params": {"serverInstanceId": "srv_1", "eventId": "evt_config_2",
            "sequence": 2, "occurredAt": "2026-08-18T00:00:01Z", "generation": 1,
            "scope": "project", "workspaceId": "ws_project", "version": "cfg_project_1"}
    });
    assert!(sanitize_webview_value(project).is_ok());

    let missing_workspace = json!({
        "jsonrpc": "2.0",
        "method": "configuration/changed",
        "params": {"serverInstanceId": "srv_1", "eventId": "evt_config_3",
            "sequence": 3, "occurredAt": "2026-08-18T00:00:02Z", "generation": 1,
            "scope": "project", "version": "cfg_project_1"}
    });
    assert!(sanitize_webview_value(missing_workspace).is_err());

    let cwd_alias = json!({
        "jsonrpc": "2.0",
        "method": "configuration/changed",
        "params": {"serverInstanceId": "srv_1", "eventId": "evt_config_4",
            "sequence": 4, "occurredAt": "2026-08-18T00:00:03Z", "generation": 1,
            "scope": "project", "workspaceId": "ws_project", "cwd": "C:/private",
            "version": "cfg_project_1"}
    });
    assert!(sanitize_webview_value(cwd_alias).is_err());
}

/// admission provisional 标题及后续自动/人工标题都已持久化；它们没有 Turn identity，
/// 不能被 Turn 公共字段校验误拒绝并触发 runtime 终止。
#[test]
fn thread_metadata_projection_accepts_committed_title_sources() {
    for source in ["placeholder", "auto", "manual"] {
        let value = json!({
            "jsonrpc": "2.0",
            "method": "thread/metadata-changed",
            "params": {
                "serverInstanceId": "srv_1",
                "eventId": format!("evt_thread_metadata_{source}"),
                "sequence": 4,
                "occurredAt": "2026-08-30T00:00:00Z",
                "generation": 1,
                "workspaceId": "ws_1",
                "threadId": "thr_1",
                "revision": 2,
                "title": "Migration plan",
                "titleSource": source
            }
        });
        assert!(sanitize_webview_value(value).is_ok());
    }
}

/// 精确镜像 v2 lifecycle union，使 cleanup failure 保持可见，并阻止 legacy degraded/crashed
/// 拼写进入 WebView。
#[test]
fn runtime_status_projection_matches_v2_lifecycle_union() {
    let failed = json!({
        "jsonrpc": "2.0",
        "method": "runtime/status-changed",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_failed_1",
            "sequence": 1,
            "occurredAt": "2026-08-25T12:00:04Z",
            "status": "failed",
            "generation": 1,
            "reason": "runtime_lifecycle"
        }
    });
    assert!(sanitize_webview_value(failed).is_ok());

    for status in ["degraded", "crashed"] {
        let legacy = json!({
            "jsonrpc": "2.0",
            "method": "runtime/status-changed",
            "params": {
                "serverInstanceId": "srv_1",
                "eventId": "evt_legacy_1",
                "sequence": 1,
                "occurredAt": "2026-08-25T12:00:04Z",
                "status": status,
                "generation": 1,
                "reason": "runtime_lifecycle"
            }
        });
        assert!(sanitize_webview_value(legacy).is_err());
    }
}

/// Ready 投影外的 reserved marker key 始终被阻止，即使值是普通文本而非 token shape。
#[test]
fn nested_ready_token_marker_is_blocked() {
    let value =
        json!({"method": "runtime/notice", "params": {"details": {"READYTOKEN": "ordinary"}}});
    assert_eq!(
        sanitize_webview_value(value).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// Native emitter failure 必须以稳定可观察 command error 到达 bridge，不能隐藏在被丢弃的 Result 后。
#[test]
fn event_sink_failure_is_reported() {
    let sink: EventSink =
        std::sync::Arc::new(|_| Err(crate::app_runtime::EventEmitError::DeliveryFailed));
    let error = emit_status(
        &sink,
        RuntimeStatusKind::Ready,
        1,
        Some("srv_1"),
        "ready",
        Some("0123456789abcdef0123456789abcdef"),
    )
    .expect_err("event failure must be observable");
    assert_eq!(error.code, "RUNTIME_EVENT_DELIVERY_FAILED");
}

/// Java durable approval notification 原样转发；Rust 不合成第二套 sequence，也不向 WebView
/// 暴露私有 server-request identity。
#[test]
fn approval_notification_is_forwarded_without_private_request_id() {
    let received = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    let target = std::sync::Arc::clone(&received);
    let sink: EventSink = std::sync::Arc::new(move |value| {
        let mut received = target
            .lock()
            .map_err(|_| crate::app_runtime::EventEmitError::DeliveryFailed)?;
        received.push(value);
        Ok(())
    });
    let frame = RpcFrame::notification(
        "approval/requested",
        json!({
            "serverInstanceId": "srv_1",
            "eventId": "evt_approval_2",
            "sequence": 2,
            "generation": 1,
            "workspaceId": "ws_1",
            "threadId": "thr_1",
            "turnId": "turn_1",
            "threadRevision": 2,
            "occurredAt": "2026-08-18T00:00:00Z",
            "approvalId": "appr_1",
            "callId": "call_1",
            "toolName": "shell",
            "reason": "Tool requires approval",
            "expiresAt": "2026-08-18T00:05:00Z",
            "from": "running",
            "to": "waiting_approval"
        }),
    )
    .expect("approval notification");
    emit_frame(&sink, &frame).expect("notification sink");
    let received = match received.lock() {
        Ok(received) => received,
        Err(_) => panic!("event fixture mutex must remain consistent"),
    };
    let value = &received[0];
    assert!(value.get("id").is_none());
    assert_eq!(value["method"], "approval/requested");
    assert_eq!(value["params"]["approvalId"], "appr_1");
}

/// unknown event method 与旧 raw Tool argument 在 WebView delivery 前关闭失败。
#[test]
fn event_projection_rejects_unknown_methods_and_sensitive_arguments() {
    let unknown = json!({"jsonrpc": "2.0", "method": "unsupported/event", "params": {}});
    assert_eq!(
        sanitize_webview_value(unknown).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
    let sensitive = json!({
        "jsonrpc": "2.0",
        "method": "assistant/model-step-committed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_tool_1",
            "sequence": 1, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 1,
            "occurredAt": "2026-08-18T00:00:00Z",
            "messageId": "item_1", "text": "", "modelRound": 1,
            "toolCalls": [{"callId": "call_1", "toolName": "shell",
                "arguments": {"apiKey": "hidden"}, "ordinal": 0}]
        }
    });
    assert_eq!(
        sanitize_webview_value(sensitive).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );

    let unsafe_path = json!({
        "jsonrpc": "2.0", "method": "assistant/model-step-committed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_tool_2",
            "sequence": 2, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 2,
            "occurredAt": "2026-08-18T00:00:00Z",
            "messageId": "item_2", "text": "", "modelRound": 1,
            "toolCalls": [{"callId": "call_2", "toolName": "shell",
                "presentation": {
                    "kind": "shell", "title": "运行命令", "status": "pending",
                    "relativePaths": ["C:/Users/private.txt"], "truncated": false
                }, "ordinal": 0}]
        }
    });
    assert_eq!(
        sanitize_webview_value(unsafe_path).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// Composite model/Tool commit 只接受 Java 已脱敏的 ToolPresentation，旧 raw result 与
/// workspace dirty 字段均不得穿过 renderer 边界。
#[test]
fn composite_events_enforce_transaction_shapes() {
    let model_step = json!({
        "jsonrpc": "2.0", "method": "assistant/model-step-committed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_model_1",
            "sequence": 3, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 3,
            "occurredAt": "2026-08-18T00:00:00Z",
            "messageId": "item_1", "text": "", "modelRound": 1,
            "reasoningSummary": "需要先检查工作树。",
            "usage": {"inputTokens": 2, "outputTokens": 1, "totalTokens": 3},
            "toolCalls": [{"callId": "call_1", "toolName": "shell",
                "presentation": {
                    "kind": "shell", "title": "运行命令", "status": "waiting_approval",
                    "inputPreview": "检查工作树", "relativePaths": [],
                    "command": "git status --short", "relativeCwd": "workspace",
                    "truncated": false
                }, "ordinal": 0}]
        }
    });
    assert!(sanitize_webview_value(model_step.clone()).is_ok());
    let mut empty_calls = model_step;
    empty_calls["params"]["toolCalls"] = json!([]);
    assert!(sanitize_webview_value(empty_calls).is_err());

    let batch = json!({
        "jsonrpc": "2.0", "method": "tool/batch-committed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_batch_1",
            "sequence": 4, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 4,
            "occurredAt": "2026-08-18T00:00:01Z",
            "results": [{"callId": "call_1", "outcome": "succeeded",
                "presentation": {
                    "kind": "shell", "title": "运行命令", "status": "success",
                    "outputPreview": "ok", "relativePaths": [],
                    "command": "git status --short", "relativeCwd": "workspace",
                    "stdout": "ok", "exitCode": 0, "durationMs": 12,
                    "truncated": false
                }, "ordinal": 0}]
        }
    });
    assert!(sanitize_webview_value(batch.clone()).is_ok());
    let mut unknown_outcome = batch.clone();
    unknown_outcome["params"]["results"][0]["outcome"] = json!("unknown");
    assert!(sanitize_webview_value(unknown_outcome).is_err());
    let mut unknown_status = batch.clone();
    unknown_status["params"]["results"][0]["presentation"]["status"] = json!("unknown");
    assert!(sanitize_webview_value(unknown_status).is_err());
    let mut missing_identity = batch.clone();
    missing_identity["params"]
        .as_object_mut()
        .expect("params")
        .remove("serverInstanceId");
    assert!(sanitize_webview_value(missing_identity).is_err());
    let mut legacy_change_ledger = batch.clone();
    legacy_change_ledger["params"]["results"][0]["fileChanges"] = json!([]);
    assert!(sanitize_webview_value(legacy_change_ledger).is_err());

    let mut legacy_dirty = batch.clone();
    legacy_dirty["params"]["workspaceDirty"] = json!(false);
    assert!(sanitize_webview_value(legacy_dirty).is_err());
    let mut raw_result = batch;
    raw_result["params"]["results"][0]["content"] = json!("raw");
    assert!(sanitize_webview_value(raw_result).is_err());
}

/// Terminal 投影要求 completion 携带 final message、failure 携带稳定 error，cancellation 不得
/// 携带 error pair。
#[test]
fn terminal_projection_enforces_state_dependent_fields() {
    let completed = json!({
        "jsonrpc": "2.0", "method": "turn/terminal",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_terminal_1",
            "sequence": 5, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 5,
            "occurredAt": "2026-08-18T00:00:02Z", "state": "completed",
            "summary": "done", "finalMessage": {"messageId": "item_2", "text": "done"}
        }
    });
    assert!(sanitize_webview_value(completed.clone()).is_ok());
    let mut missing_message = completed;
    missing_message["params"]
        .as_object_mut()
        .expect("params")
        .remove("finalMessage");
    assert!(sanitize_webview_value(missing_message).is_err());

    let failed = json!({
        "jsonrpc": "2.0", "method": "turn/terminal",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_terminal_2",
            "sequence": 6, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 6,
            "occurredAt": "2026-08-18T00:00:03Z", "state": "failed",
            "summary": "failed", "errorCode": "MODEL_ERROR", "errorMessage": "failed"
        }
    });
    assert!(sanitize_webview_value(failed).is_ok());
}
