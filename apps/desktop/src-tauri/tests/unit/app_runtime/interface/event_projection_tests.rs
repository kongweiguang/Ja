// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;

/// Ready 投影必须接受 2.1 的完整三 feature 集，并在 WebView serialization 前消费 challenge。
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
            "features": ["task_threads", "plan", "interaction"],
            "readyToken": "0123456789abcdef0123456789abcdef"
        }
    });
    let sanitized = sanitize_webview_value(value).expect("ready projection is safe");
    assert_eq!(
        sanitized["params"]["features"],
        json!(["task_threads", "plan", "interaction"])
    );
    assert!(sanitized["params"].get("readyToken").is_none());
}

/// Workspace invalidation needs the shared server fence and exact projection shape before native bindings are revoked.
#[test]
fn workspace_metadata_changed_requires_complete_revision_fence() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "workspace/metadata-changed",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_workspace_1",
            "sequence": 7,
            "occurredAt": "2026-08-18T00:00:00Z",
            "generation": 2,
            "workspaceId": "ws_1",
            "revision": 3
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    let mut stale_shape = value;
    stale_shape["params"]
        .as_object_mut()
        .unwrap()
        .remove("generation");
    assert!(sanitize_webview_value(stale_shape).is_err());
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
            "text": "", "totalCharacters": 0, "truncated": false,
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

/// 恢复通知校验连接排序序号，但不把它解释为持久事件游标或业务诊断正文。
#[test]
fn resync_required_projection_accepts_only_the_frozen_identity_shape() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "turn/resync-required",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_resync_1",
            "sequence": 66,
            "occurredAt": "2026-09-27T00:00:00Z",
            "generation": 1,
            "workspaceId": "ws_1",
            "threadId": "thr_1",
            "turnId": "turn_1",
            "reasonCode": "projection_failed"
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    let mut unknown_reason = value.clone();
    unknown_reason["params"]["reasonCode"] = json!("provider_failure");
    assert!(sanitize_webview_value(unknown_reason).is_err());

    let mut invalid_sequence = value.clone();
    invalid_sequence["params"]["sequence"] = json!(0);
    assert!(sanitize_webview_value(invalid_sequence).is_err());

    let mut fabricated_cursor = value;
    fabricated_cursor["params"]["threadRevision"] = json!(1);
    assert!(sanitize_webview_value(fabricated_cursor).is_err());
}

/// 派发估算必须经 Rust 代理按 UNKNOWN/本地数据传递，并保留冻结的窗口身份。
#[test]
fn context_estimate_projection_accepts_local_dispatch_and_rejects_mismatch() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "assistant/context-estimated",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_estimate_1",
            "sequence": 3, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 3,
            "occurredAt": "2026-09-26T00:00:03Z",
            "usage": {
                "modelRequestId": "modelreq_1", "modelRequestOrdinal": 2, "modelRound": 2,
                "purpose": "assistant", "certainty": "unknown",
                "profile": {
                    "providerId": "provider_1", "modelId": "model_1", "api": "openai_responses",
                    "upstreamModel": "gpt-5", "requestedReasoning": "medium",
                    "effectiveReasoning": "medium", "accessMode": "approval_required",
                    "collaborationMode": "default", "configGeneration": "cfg_1",
                    "promptRevision": "prompt_2", "toolCatalogRevision": "tools_1",
                    "contextWindowTokens": 128000, "maxOutputTokens": 4096
                },
                "inputTokens": null, "outputTokens": null, "totalTokens": null,
                "measuredAt": "2026-09-26T00:00:03Z",
                "contextEstimate": {
                    "certainty": "estimated", "source": "local", "usedTokens": 18500,
                    "limitTokens": 128000, "measuredAt": "2026-09-26T00:00:03Z"
                }
            }
        }
    });

    let sanitized = sanitize_webview_value(value.clone()).expect("local dispatch estimate is safe");
    assert_eq!(
        sanitized["params"]["usage"]["contextEstimate"]["usedTokens"],
        18500
    );

    let mut wrong_window = value.clone();
    wrong_window["params"]["usage"]["contextEstimate"]["limitTokens"] = json!(1);
    assert_eq!(
        sanitize_webview_value(wrong_window).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );

    let mut measured_with_estimate = value;
    measured_with_estimate["params"]["usage"]["certainty"] = json!("known");
    measured_with_estimate["params"]["usage"]["inputTokens"] = json!(1);
    measured_with_estimate["params"]["usage"]["outputTokens"] = json!(1);
    measured_with_estimate["params"]["usage"]["totalTokens"] = json!(2);
    assert_eq!(
        sanitize_webview_value(measured_with_estimate)
            .unwrap_err()
            .code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// 重试状态只公开当前尝试，超过旧六次上限仍可投影；诊断内容不得进入 WebView。
#[test]
fn retry_started_projection_accepts_unbounded_attempts_without_old_maximum() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "turn/retry-started",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_retry_1",
            "sequence": 2,
            "occurredAt": "2026-09-23T00:00:00Z",
            "generation": 1,
            "workspaceId": "ws_1",
            "threadId": "thr_1",
            "turnId": "turn_1",
            "threadRevision": 2,
            "attempt": 7
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    for (field, invalid) in [
        ("attempt", json!(1)),
        ("attempt", json!(9_007_199_254_740_992_u64)),
    ] {
        let mut malformed = value.clone();
        malformed["params"][field] = invalid;
        assert!(sanitize_webview_value(malformed).is_err());
    }

    let mut removed_maximum = value.clone();
    removed_maximum["params"]["maxAttempts"] = json!(6);
    assert!(sanitize_webview_value(removed_maximum).is_err());

    let mut leaked_diagnostic = value;
    leaked_diagnostic["params"]["error"] = json!("provider body");
    assert!(sanitize_webview_value(leaked_diagnostic).is_err());
}

/// 队列 revision 与 Thread revision 分流：changed 不携带 threadRevision，consumed 则原子携带
/// 用户消息、剩余队列和可选 Assistant 结算。
#[test]
fn turn_input_events_enforce_queue_and_thread_revision_boundaries() {
    let queued_input = json!({
        "inputId": "input_1", "turnId": "turn_1",
        "content": [{"type":"text","text":"下一步检查测试"}],
        "attachments": [],
        "kind": "steering", "status":"pending", "issue":null,
        "inputRevision": 2, "createdAt": "2026-09-01T01:00:00Z"
    });
    let changed = json!({
        "jsonrpc": "2.0", "method": "turn/input-queue-changed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_queue_1", "sequence": 2,
            "occurredAt": "2026-09-01T01:00:01Z", "generation": 1,
            "workspaceId": "ws_1", "threadId": "thr_1", "turnId": "turn_1",
            "inputQueue": {"turnId": "turn_1", "revision": 2, "accepting": true,
                "items": [queued_input.clone()]}
        }
    });
    assert!(sanitize_webview_value(changed.clone()).is_ok());
    let mut mixed_revision = changed;
    mixed_revision["params"]["threadRevision"] = json!(7);
    assert!(sanitize_webview_value(mixed_revision).is_err());

    let consumed = json!({
        "jsonrpc": "2.0", "method": "turn/input-consumed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_queue_2", "sequence": 3,
            "occurredAt": "2026-09-01T01:00:02Z", "generation": 1,
            "workspaceId": "ws_1", "threadId": "thr_1", "turnId": "turn_1",
            "threadRevision": 8, "input": queued_input,
            "userItem": {"itemId": "item_user_1", "createdAt": "2026-09-01T01:00:02Z",
                "turnId": "turn_1", "kind": "user_input",
                "content": [{"type":"text","text":"下一步检查测试"}], "attachments": [],
                "totalCharacters": 7, "truncated": false},
            "inputQueue": {"turnId": "turn_1", "revision": 3, "accepting": true, "items": []},
            "assistantSettlement": {"messageId": "item_assistant_1", "text": "上一轮完成。",
                "totalCharacters": 6, "truncated": false, "modelRound": 1}
        }
    });
    assert!(sanitize_webview_value(consumed.clone()).is_ok());
    let mut mismatched_user = consumed;
    mismatched_user["params"]["userItem"]["content"][0]["text"] = json!("其它文本");
    assert!(sanitize_webview_value(mismatched_user).is_err());
}

/// Mailbox 消费只把结构化跨会话消息放入目标 Turn；错 Turn 或普通历史 item 不得伪装成通信事件。
#[test]
fn messages_received_event_requires_bound_thread_messages() {
    let value = json!({
        "jsonrpc": "2.0",
        "method": "turn/messages_received",
        "params": {
            "serverInstanceId": "srv_1",
            "eventId": "evt_messages_1",
            "sequence": 6,
            "occurredAt": "2026-09-05T00:00:01Z",
            "generation": 1,
            "workspaceId": "ws_target",
            "threadId": "thr_target",
            "turnId": "turn_target",
            "threadRevision": 3,
            "items": [{
                "itemId": "item_message_1",
                "createdAt": "2026-09-05T00:00:00Z",
                "turnId": "turn_target",
                "kind": "thread_message",
                "sourceThreadId": "thr_source",
                "sourceTitle": "临时侧聊",
                "content": "来自侧聊的结果", "totalCharacters": 7, "truncated": false
            }]
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    let mut snapshot_only_origin = value.clone();
    snapshot_only_origin["params"]["items"][0]["origin"] = json!("native");
    assert!(sanitize_webview_value(snapshot_only_origin).is_err());

    let mut wrong_turn = value.clone();
    wrong_turn["params"]["items"][0]["turnId"] = json!("turn_other");
    assert!(sanitize_webview_value(wrong_turn).is_err());

    let mut ordinary_item = value;
    ordinary_item["params"]["items"][0] = json!({
        "itemId": "item_assistant_1",
        "createdAt": "2026-09-05T00:00:00Z",
        "turnId": "turn_target",
        "kind": "final_answer",
        "text": "不得作为消息事件"
    });
    assert!(sanitize_webview_value(ordinary_item).is_err());
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
            "strategyVersion": "ja-context-v1"
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
            "strategyVersion": "ja-context-v1", "checkpointId": "checkpoint_1"
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
            "strategyVersion": "ja-context-v1", "errorCode": "SUMMARY_FAILURE"
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

/// Config invalidation 只保留公共 v1 metadata、scope、opaque version 与 optional server
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

/// Task activity/progress/mailbox 使用独立 root/task revision，并只允许经过脱敏的摘要字段。
#[test]
fn task_events_are_strictly_projected_without_transcript_materialization() {
    let task = json!({
        "taskThreadId": "thr_child", "parentThreadId": "thr_parent", "rootThreadId": "thr_parent",
        "originTurnId": "turn_parent", "taskName": "检查测试", "depth": 1,
        "taskKind": "subagent", "lifecycle": "attached", "state": "running", "revision": 2,
        "latestActivitySequence": 3, "unreadCount": 1, "descendantCount": 0,
        "runningDescendantCount": 0, "needsAttentionCount": 0, "latestSafeSummary": "正在检查",
        "startedAt": "2026-09-03T08:00:00Z", "completedAt": null,
        "updatedAt": "2026-09-03T08:00:01Z"
    });
    let base = json!({
        "serverInstanceId": "srv_1", "eventId": "evt_task_1", "sequence": 4,
        "occurredAt": "2026-09-03T08:00:01Z", "generation": 1,
        "rootThreadId": "thr_parent", "taskThreadId": "thr_child", "taskRevision": 2
    });
    let mut activity = base.clone();
    activity["activity"] = json!({
        "activitySequence": 3, "activityId": "activity_3", "rootThreadId": "thr_parent",
        "taskThreadId": "thr_child",
        "actorThreadId": "thr_parent", "causalTurnId": "turn_parent", "kind": "progress",
        "summary": {"text": "正在检查"}, "createdAt": "2026-09-03T08:00:01Z"
    });
    assert!(
        crate::app_runtime::infrastructure::bridge::tasks::parse_task_summary_value(&task).is_ok()
    );
    activity["task"] = task;
    assert!(
        crate::app_runtime::infrastructure::bridge::tasks::parse_task_activity_value(
            &activity["activity"]
        )
        .is_ok()
    );
    assert!(
        sanitize_webview_value(json!({
            "jsonrpc": "2.0", "method": "task/activity", "params": activity
        }))
        .is_ok()
    );

    let mut progress = base.clone();
    progress["observationId"] = json!("observe_child");
    progress["progressRevision"] = json!(0);
    progress["safeSummary"] = json!("读取测试结果");
    assert!(
        sanitize_webview_value(json!({
            "jsonrpc": "2.0", "method": "task/progress", "params": progress
        }))
        .is_ok()
    );

    let mut mailbox = base;
    mailbox["mailboxSequence"] = json!(5);
    mailbox["unreadCount"] = json!(2);
    assert!(
        sanitize_webview_value(json!({
            "jsonrpc": "2.0", "method": "task/mailbox-changed", "params": mailbox
        }))
        .is_ok()
    );
}

/// Close 通知必须路由到存活顶层 root，并携带请求 root 的完整唯一 purge 身份集。
#[test]
fn task_closed_event_requires_exact_live_purge_ids_and_top_level_route() {
    let value = json!({
        "jsonrpc": "2.0", "method": "task/closed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_task_closed", "sequence": 7,
            "occurredAt": "2026-09-03T08:00:02Z", "generation": 1,
            "rootThreadId": "thr_top_root", "taskThreadId": "thr_closed_root",
            "closedThreadIds": ["thr_closed_root", "thr_subagent"]
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    let mut missing_closed_root = value.clone();
    missing_closed_root["params"]["closedThreadIds"] = json!(["thr_subagent"]);
    assert_eq!(
        sanitize_webview_value(missing_closed_root)
            .unwrap_err()
            .code,
        "SENSITIVE_EVENT_BLOCKED"
    );

    let mut duplicate_ids = value.clone();
    duplicate_ids["params"]["closedThreadIds"] = json!(["thr_closed_root", "thr_closed_root"]);
    assert_eq!(
        sanitize_webview_value(duplicate_ids).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );

    let mut old_revision_shape = value.clone();
    old_revision_shape["params"]["taskRevision"] = json!(4);
    assert_eq!(
        sanitize_webview_value(old_revision_shape).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );

    let mut self_routed_event = value;
    self_routed_event["params"]["rootThreadId"] = json!("thr_closed_root");
    assert_eq!(
        sanitize_webview_value(self_routed_event).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// Plan 事件的外层身份与快照必须一致，避免陈旧进度拼接到另一个计划。
#[test]
fn plan_changed_projection_rejects_mismatched_identity() {
    let value = json!({
        "jsonrpc": "2.0", "method": "plan/changed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_plan_1", "sequence": 6,
            "occurredAt": "2026-09-04T08:00:01Z", "generation": 1,
            "ownerThreadId": "thr_root", "planId": "plan_demo", "planRevision": 1,
            "eventSequence": 1,
            "plan": {
                "planId": "plan_demo", "owner": {"kind": "thread", "threadId": "thr_root"},
                "objective": "完成独立计划", "status": "draft", "revision": 1,
                "activePlanRevisionId": null, "activeRunId": null,
                "createdAt": "2026-09-04T08:00:00Z", "updatedAt": "2026-09-04T08:00:00Z"
            },
            "progress": {
                "currentStepId": null, "currentStepTitle": null,
                "completedRequiredSteps": 0, "totalRequiredSteps": 0
            }
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    let mut mismatched = value;
    mismatched["params"]["plan"]["planId"] = json!("plan_other");
    assert_eq!(
        sanitize_webview_value(mismatched).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// Activity 内嵌 Task 与外层 taskRevision 必须一致，防止跨任务或陈旧投影拼接。
#[test]
fn task_activity_rejects_cross_task_projection() {
    let value = json!({
        "jsonrpc": "2.0", "method": "task/activity",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_task_2", "sequence": 5,
            "occurredAt": "2026-09-03T08:00:02Z", "generation": 1,
            "rootThreadId": "thr_parent", "taskThreadId": "thr_child", "taskRevision": 2,
            "activity": {
                "activitySequence": 4, "activityId": "activity_4", "rootThreadId": "thr_parent",
                "taskThreadId": "thr_other",
                "actorThreadId": "thr_parent", "causalTurnId": null, "kind": "completed",
                "summary": {"text": "完成"}, "createdAt": "2026-09-03T08:00:02Z"
            },
            "task": {
                "taskThreadId": "thr_child", "parentThreadId": "thr_parent", "rootThreadId": "thr_parent",
                "originTurnId": "turn_parent", "taskName": "检查测试", "depth": 1,
                "taskKind": "subagent", "lifecycle": "attached", "state": "completed", "revision": 2,
                "latestActivitySequence": 4, "unreadCount": 1, "descendantCount": 0,
                "runningDescendantCount": 0, "needsAttentionCount": 0, "latestSafeSummary": "完成",
                "startedAt": "2026-09-03T08:00:00Z", "completedAt": "2026-09-03T08:00:02Z",
                "updatedAt": "2026-09-03T08:00:02Z"
            }
        }
    });
    assert_eq!(
        sanitize_webview_value(value).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// 精确镜像 v1.0 lifecycle 与 feature 闭集，使 cleanup failure 保持可见，并阻止未知
/// capability 或 legacy degraded/crashed 拼写进入 WebView。
#[test]
fn runtime_status_projection_matches_v1_lifecycle_union() {
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
            "features": ["task_threads", "plan", "interaction"],
            "reason": "runtime_lifecycle"
        }
    });
    assert!(sanitize_webview_value(failed.clone()).is_ok());

    let mut unknown_feature = failed;
    unknown_feature["params"]["features"] = json!(["task_threads", "plan", "unknown_feature_v1"]);
    assert_eq!(
        sanitize_webview_value(unknown_feature).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );

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

/// Host 自生 Ready 事件必须发出与 Java JA-RPC 1.0 握手相同的三 feature，并保持 React host
/// lifecycle schema 的独立形状；它不是 Java notification，不能伪造 sequence 或回传 challenge。
#[test]
fn status_emitter_uses_the_v1_feature_set() {
    let received = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    let target = std::sync::Arc::clone(&received);
    let sink: EventSink = std::sync::Arc::new(move |value| {
        target
            .lock()
            .map_err(|_| crate::app_runtime::EventEmitError::DeliveryFailed)?
            .push(value);
        Ok(())
    });
    emit_status(
        &sink,
        RuntimeStatusKind::Ready,
        7,
        Some("srv_1"),
        "ready",
        Some("0123456789abcdef0123456789abcdef"),
    )
    .expect("host ready event");
    let emitted = match received.lock() {
        Ok(emitted) => emitted[0].clone(),
        Err(_) => panic!("status fixture mutex must remain consistent"),
    };
    assert_eq!(
        emitted["params"]["features"],
        json!(["task_threads", "plan", "interaction"])
    );
    assert_eq!(emitted["params"]["status"], "ready");
    assert_eq!(emitted["params"]["generation"], 7);
    assert_eq!(emitted["params"]["serverInstanceId"], "srv_1");
    assert!(emitted["params"].get("sequence").is_none());
    assert!(emitted["params"].get("readyToken").is_none());
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

/// 使用共享 canonical approval fixture 验证生产投影接受安全 pending 展示并原样转发公共身份。
#[test]
fn approval_notification_is_forwarded_without_private_request_id() {
    let canonical = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../contracts/golden/v2/valid/approval-presentation.jsonl"
    ));
    let event: Value = serde_json::from_str(canonical.trim()).expect("canonical approval fixture");
    let params = event
        .get("params")
        .expect("canonical approval params")
        .clone();
    let received = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
    let target = std::sync::Arc::clone(&received);
    let sink: EventSink = std::sync::Arc::new(move |value| {
        let mut received = target
            .lock()
            .map_err(|_| crate::app_runtime::EventEmitError::DeliveryFailed)?;
        received.push(value);
        Ok(())
    });
    let frame =
        RpcFrame::notification("approval/requested", params).expect("approval notification");
    emit_frame(&sink, &frame).expect("notification sink");
    let received = match received.lock() {
        Ok(received) => received,
        Err(_) => panic!("event fixture mutex must remain consistent"),
    };
    let value = &received[0];
    assert!(value.get("id").is_none());
    assert_eq!(value["method"], "approval/requested");
    assert_eq!(value["params"]["approvalId"], "appr_fixture");
    assert_eq!(value["params"]["presentation"]["status"], "pending");
}

/// 共享 canonical 负例必须经真实事件投影器拒绝，覆盖缺失/非 pending 展示、原始参数和媒体。
#[test]
fn approval_projection_rejects_canonical_invalid_presentations() {
    let invalid = include_str!(concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/../../../contracts/golden/v2/invalid/approval-presentation.jsonl"
    ));
    for (line, fixture) in invalid.lines().enumerate() {
        let event: Value = serde_json::from_str(fixture).expect("canonical invalid approval JSON");
        let error = sanitize_webview_value(event)
            .expect_err("invalid approval presentation must be blocked before WebView delivery");
        assert_eq!(
            error.code,
            "SENSITIVE_EVENT_BLOCKED",
            "canonical approval negative {} must use the sensitive event boundary",
            line + 1
        );
    }
}

/// Suspended 只作为 Turn 事件状态透传；显式 Resume 先回到 queued，不能跳过 Java 队列直接 running。
#[test]
fn suspended_turn_transition_requires_resume_queue_admission() {
    let resumed = json!({
        "jsonrpc": "2.0",
        "method": "turn/state-changed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_resume_1",
            "sequence": 2, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 8,
            "occurredAt": "2026-09-01T00:00:00Z",
            "from": "suspended", "to": "queued"
        }
    });
    assert!(sanitize_webview_value(resumed.clone()).is_ok());

    let mut skipped_queue = resumed;
    skipped_queue["params"]["to"] = json!("running");
    assert_eq!(
        sanitize_webview_value(skipped_queue).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
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
            "messageId": "item_1", "text": "", "totalCharacters": 0, "truncated": false,
            "modelRound": 1,
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
            "messageId": "item_2", "text": "", "totalCharacters": 0, "truncated": false,
            "modelRound": 1,
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

/// Composite model/Tool commit 只接受 Java 已脱敏且有界的 ToolPresentation 摘要，旧 raw result 与
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
            "messageId": "item_1", "text": "", "totalCharacters": 0, "truncated": false,
            "modelRound": 1,
            "reasoningSummary": "需要先检查工作树。", "reasoningSummaryTotalCharacters": 9,
            "reasoningSummaryTruncated": false,
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

    let started = json!({
        "jsonrpc": "2.0", "method": "tool/started",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_started_1",
            "sequence": 4, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 4,
            "occurredAt": "2026-08-18T00:00:01Z", "callId": "toolu_01Jabc:part.2", "ordinal": 0
        }
    });
    assert!(sanitize_webview_value(started.clone()).is_ok());
    let mut raw_started = started.clone();
    raw_started["params"]["arguments"] = json!({"command": "must-not-cross"});
    assert!(sanitize_webview_value(raw_started).is_err());
    let mut invalid_ordinal = started;
    invalid_ordinal["params"]["ordinal"] = json!(-1);
    assert!(sanitize_webview_value(invalid_ordinal).is_err());

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
                    "outputPreview": "ok", "summary": "命令执行成功", "relativePaths": [],
                    "command": "git status --short", "relativeCwd": "workspace",
                    "stdout": "ok", "exitCode": 0, "durationMs": 12,
                    "truncated": false
                }, "ordinal": 0}]
        }
    });
    assert!(sanitize_webview_value(batch.clone()).is_ok());
    assert_eq!(
        batch["params"]["results"][0]["presentation"]["summary"],
        json!("命令执行成功")
    );
    let mut clarification = batch.clone();
    clarification["params"]["results"][0]["presentation"] = json!({
        "kind": "mcp", "title": "request_user_input", "status": "success",
        "relativePaths": [], "truncated": false,
        "interactionAnswers": [
            {"question": "要继续吗？", "answers": ["继续"], "skipped": false},
            {"question": "补充信息", "answers": [], "skipped": true}
        ]
    });
    assert!(sanitize_webview_value(clarification.clone()).is_ok());

    let mut extra_interaction_field = clarification.clone();
    extra_interaction_field["params"]["results"][0]["presentation"]["interactionAnswers"][0]["privateId"] =
        json!("must-not-cross");
    assert!(sanitize_webview_value(extra_interaction_field).is_err());
    let mut answered_skip = clarification.clone();
    answered_skip["params"]["results"][0]["presentation"]["interactionAnswers"][1]["answers"] =
        json!(["not allowed"]);
    assert!(sanitize_webview_value(answered_skip).is_err());
    let mut blank_question = clarification.clone();
    blank_question["params"]["results"][0]["presentation"]["interactionAnswers"][0]["question"] =
        json!(" \u{feff} ");
    assert!(sanitize_webview_value(blank_question).is_err());
    let mut blank_answer = clarification.clone();
    blank_answer["params"]["results"][0]["presentation"]["interactionAnswers"][0]["answers"][0] =
        json!("\u{00a0}\u{feff}");
    assert!(sanitize_webview_value(blank_answer).is_err());
    let mut nul_answer = clarification.clone();
    nul_answer["params"]["results"][0]["presentation"]["interactionAnswers"][0]["answers"][0] =
        json!("unsafe\0answer");
    assert!(sanitize_webview_value(nul_answer).is_err());
    let mut too_many_answers = clarification.clone();
    too_many_answers["params"]["results"][0]["presentation"]["interactionAnswers"][0]["answers"] =
        json!(vec!["answer"; 65]);
    assert!(sanitize_webview_value(too_many_answers).is_err());
    let mut too_many_interactions = clarification.clone();
    too_many_interactions["params"]["results"][0]["presentation"]["interactionAnswers"] = json!([
        {"question": "Q", "answers": [], "skipped": false},
        {"question": "Q", "answers": [], "skipped": false},
        {"question": "Q", "answers": [], "skipped": false},
        {"question": "Q", "answers": [], "skipped": false}
    ]);
    assert!(sanitize_webview_value(too_many_interactions).is_err());

    let mut max_question_length = clarification.clone();
    max_question_length["params"]["results"][0]["presentation"]["interactionAnswers"][0]["question"] =
        json!("q".repeat(4_096));
    assert!(sanitize_webview_value(max_question_length.clone()).is_ok());
    max_question_length["params"]["results"][0]["presentation"]["interactionAnswers"][0]["question"] =
        json!("q".repeat(4_097));
    assert!(sanitize_webview_value(max_question_length).is_err());
    let mut max_answer_length = clarification.clone();
    max_answer_length["params"]["results"][0]["presentation"]["interactionAnswers"][0]["answers"]
        [0] = json!("a".repeat(4_097));
    assert!(sanitize_webview_value(max_answer_length).is_err());

    let mut utf16_boundary = clarification;
    utf16_boundary["params"]["results"][0]["presentation"]["interactionAnswers"][0]["question"] =
        json!("🙂".repeat(2_048));
    assert!(sanitize_webview_value(utf16_boundary.clone()).is_ok());
    utf16_boundary["params"]["results"][0]["presentation"]["interactionAnswers"][0]["question"] =
        json!("🙂".repeat(2_049));
    assert!(sanitize_webview_value(utf16_boundary).is_err());

    let mut unknown_outcome = batch.clone();
    unknown_outcome["params"]["results"][0]["outcome"] = json!("unknown");
    assert!(sanitize_webview_value(unknown_outcome).is_err());
    let mut unknown_status = batch.clone();
    unknown_status["params"]["results"][0]["presentation"]["status"] = json!("unknown");
    assert!(sanitize_webview_value(unknown_status).is_err());
    let mut recovery_snapshot = batch.clone();
    recovery_snapshot["params"]["results"][0]["presentation"]["recovery"] = json!({"revision": 1});
    assert!(sanitize_webview_value(recovery_snapshot.clone()).is_ok());
    recovery_snapshot["params"]["results"][0]["presentation"]["recovery"]["command"] =
        json!("hidden");
    assert!(sanitize_webview_value(recovery_snapshot).is_err());
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

/// 显式接受普通澄清的 waiting_input 状态边，并拒绝将等待输入直接伪装成审批。
#[test]
fn turn_input_wait_state_is_a_closed_transition() {
    let waiting = json!({
        "jsonrpc": "2.0", "method": "turn/state-changed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_waiting_input_1",
            "sequence": 4, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 4,
            "occurredAt": "2026-08-18T00:00:01Z", "from": "running", "to": "waiting_input"
        }
    });
    assert!(sanitize_webview_value(waiting.clone()).is_ok());
    let mut fake_approval = waiting;
    fake_approval["params"]["from"] = json!("waiting_input");
    fake_approval["params"]["to"] = json!("waiting_approval");
    assert!(sanitize_webview_value(fake_approval).is_err());
}

/// Terminal 投影要求完成态有消息和 ChangeSet，失败/取消则保留真实 ChangeSet 或明确 null。
#[test]
fn terminal_projection_enforces_state_dependent_fields() {
    let completed = json!({
        "jsonrpc": "2.0", "method": "turn/terminal",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_terminal_1",
            "sequence": 5, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 5,
            "occurredAt": "2026-08-18T00:00:02Z", "state": "completed",
            "summary": "done", "summaryTotalCharacters": 4, "summaryTruncated": false,
            "finalMessage": {"messageId": "item_2", "text": "done", "totalCharacters": 4,
                "truncated": false},
            "changeSet": {
                "state": "complete", "incompleteReasons": [],
                "files": [{"path": "src/main.rs", "status": "modified",
                    "additions": 2, "deletions": 1, "binary": false, "truncated": false}],
                "stats": {"files": 1, "additions": 2, "deletions": 1,
                    "binaryFiles": 0, "truncated": false},
                "artifactId": "artifact_turn_1"
            }
        }
    });
    assert!(sanitize_webview_value(completed.clone()).is_ok());
    let mut missing_message = completed.clone();
    missing_message["params"]
        .as_object_mut()
        .expect("params")
        .remove("finalMessage");
    assert!(sanitize_webview_value(missing_message).is_err());
    let mut cancelled = completed.clone();
    cancelled["params"]["state"] = json!("cancelled");
    cancelled["params"]
        .as_object_mut()
        .expect("params")
        .remove("finalMessage");
    assert!(sanitize_webview_value(cancelled.clone()).is_ok());
    cancelled["params"]["finalMessage"] = json!({"messageId": "item_3", "text": "not a final",
        "totalCharacters": 11, "truncated": false});
    assert!(sanitize_webview_value(cancelled).is_err());

    let failed = json!({
        "jsonrpc": "2.0", "method": "turn/terminal",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_terminal_2",
            "sequence": 6, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 6,
            "occurredAt": "2026-08-18T00:00:03Z", "state": "failed",
            "summary": "failed", "summaryTotalCharacters": 6, "summaryTruncated": false,
            "errorCode": "MODEL_ERROR", "errorMessage": "failed",
            "changeSet": {
                "state": "partial", "incompleteReasons": ["commit_unconfirmed"],
                "files": [], "stats": {"files": 0, "additions": 0, "deletions": 0,
                    "binaryFiles": 0, "truncated": false}
            },
            "usage": {
                "modelRequestId": "modelreq_1", "modelRequestOrdinal": 1, "modelRound": 1,
                "purpose": "assistant", "certainty": "unknown",
                "profile": {
                    "providerId": "provider_1", "modelId": "model_1", "api": "openai_responses",
                    "upstreamModel": "gpt-5", "requestedReasoning": "medium",
                    "effectiveReasoning": "medium", "accessMode": "approval_required",
                    "collaborationMode": "default", "configGeneration": "cfg_1",
                    "promptRevision": "prompt_1", "toolCatalogRevision": "tools_1",
                    "contextWindowTokens": 128000, "maxOutputTokens": 4096
                },
                "inputTokens": null, "outputTokens": null, "totalTokens": null,
                "measuredAt": "2026-08-18T00:00:03Z"
            }
        }
    });
    let projected_failed =
        sanitize_webview_value(failed.clone()).expect("persisted ChangeSet survives");
    assert_eq!(
        projected_failed["params"]["changeSet"],
        failed["params"]["changeSet"]
    );
    let mut failed_with_null_change_set = failed.clone();
    failed_with_null_change_set["params"]["changeSet"] = Value::Null;
    assert!(sanitize_webview_value(failed_with_null_change_set.clone()).is_ok());
    failed_with_null_change_set["params"]
        .as_object_mut()
        .expect("params")
        .remove("changeSet");
    assert!(sanitize_webview_value(failed_with_null_change_set).is_err());
    let mut failed_with_message = failed.clone();
    failed_with_message["params"]["finalMessage"] = json!({"messageId": "item_3", "text": "partial", "totalCharacters": 7,
            "truncated": false});
    assert!(sanitize_webview_value(failed_with_message).is_ok());
    let mut missing_collaboration_mode = failed.clone();
    missing_collaboration_mode["params"]["usage"]["profile"]
        .as_object_mut()
        .expect("provider profile")
        .remove("collaborationMode");
    assert!(sanitize_webview_value(missing_collaboration_mode).is_err());

    let mut missing_change_set = completed.clone();
    missing_change_set["params"]
        .as_object_mut()
        .expect("params")
        .remove("changeSet");
    assert!(sanitize_webview_value(missing_change_set).is_err());

    let mut completed_without_change_set = completed.clone();
    completed_without_change_set["params"]["changeSet"] = Value::Null;
    assert!(sanitize_webview_value(completed_without_change_set).is_err());

    let mut malformed_failed_change_set = failed.clone();
    malformed_failed_change_set["params"]["changeSet"] = json!("unknown");
    assert!(sanitize_webview_value(malformed_failed_change_set).is_err());

    let mut cancelled_with_null_change_set = completed;
    cancelled_with_null_change_set["params"]["state"] = json!("cancelled");
    cancelled_with_null_change_set["params"]
        .as_object_mut()
        .expect("params")
        .remove("finalMessage");
    cancelled_with_null_change_set["params"]["changeSet"] = Value::Null;
    assert!(sanitize_webview_value(cancelled_with_null_change_set.clone()).is_ok());
    cancelled_with_null_change_set["params"]
        .as_object_mut()
        .expect("params")
        .remove("changeSet");
    assert!(sanitize_webview_value(cancelled_with_null_change_set).is_err());
}

/// 保证恢复状态与审批系统关闭按显式事件投影，native callId 不被 Ja 前缀规则拒绝。
#[test]
fn recovery_and_approval_close_events_enforce_closed_contracts() {
    let recovery = json!({
        "jsonrpc": "2.0", "method": "tool/recovery-changed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_recovery_1",
            "sequence": 7, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 7,
            "occurredAt": "2026-08-18T00:00:04Z", "recoveryId": "recovery_1",
            "callId": "toolu_01Jabc:part.2", "revision": 1,
            "status": "pending", "requiresUserAction": true
        }
    });
    assert!(sanitize_webview_value(recovery.clone()).is_ok());
    let mut inconsistent_recovery = recovery;
    inconsistent_recovery["params"]["requiresUserAction"] = json!(false);
    assert!(sanitize_webview_value(inconsistent_recovery).is_err());

    let closed = json!({
        "jsonrpc": "2.0", "method": "approval/closed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_approval_closed_1",
            "sequence": 8, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 8,
            "occurredAt": "2026-08-18T00:00:05Z", "approvalId": "appr_1",
            "reason": "superseded"
        }
    });
    assert!(sanitize_webview_value(closed.clone()).is_ok());
    let mut expired = closed.clone();
    expired["params"]["reason"] = json!("expired");
    assert!(sanitize_webview_value(expired).is_ok());
    let mut fake_user_decision = closed;
    fake_user_decision["params"]["reason"] = json!("deny");
    assert!(sanitize_webview_value(fake_user_decision).is_err());
}
