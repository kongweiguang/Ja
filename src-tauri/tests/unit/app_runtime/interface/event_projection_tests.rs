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
            "features": ["task_threads_v1", "plan_goal_v1"],
            "readyToken": "0123456789abcdef0123456789abcdef"
        }
    });
    let sanitized = sanitize_webview_value(value).expect("ready projection is safe");
    assert_eq!(
        sanitized["params"]["features"],
        json!(["task_threads_v1", "plan_goal_v1"])
    );
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
                "content": [{"type":"text","text":"下一步检查测试"}], "attachments": []},
            "inputQueue": {"turnId": "turn_1", "revision": 3, "accepting": true, "items": []},
            "assistantSettlement": {"messageId": "item_assistant_1", "text": "上一轮完成。",
                "modelRound": 1}
        }
    });
    assert!(sanitize_webview_value(consumed.clone()).is_ok());
    let mut mismatched_user = consumed;
    mismatched_user["params"]["userItem"]["content"][0]["text"] = json!("其它文本");
    assert!(sanitize_webview_value(mismatched_user).is_err());
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
        "activitySequence": 3, "activityId": "activity_3", "taskThreadId": "thr_child",
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

/// Goal changed 是首次发现 identity 的低频事件；Rust 必须接受严格完整投影，同时拒绝
/// 外层 Goal identity 与内层快照不一致，避免跨 Goal 状态被送入 React。
#[test]
fn goal_changed_projection_accepts_discovery_and_rejects_cross_goal_payload() {
    let value = json!({
        "jsonrpc": "2.0", "method": "goal/changed",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_goal_1", "sequence": 6,
            "occurredAt": "2026-09-04T08:00:01Z", "generation": 1,
            "goalId": "goal_demo", "goalRevision": 1, "eventSequence": 1,
            "goal": {
                "goalId": "goal_demo", "owner": {"kind": "thread", "threadId": "thr_root"},
                "objective": "交付 Plan Goal", "goalDefinitionRevision": 1,
                "acceptanceCriteria": [], "status": "active", "phase": "working",
                "revision": 1, "planLink": null,
                "currentRunId": "run_demo", "currentStepId": null, "completedRequiredSteps": 0,
                "totalRequiredSteps": 0, "pendingInput": null, "attentionReason": null,
                "latestEvaluation": null,
                "createdAt": "2026-09-04T08:00:00Z", "updatedAt": "2026-09-04T08:00:00Z",
                "achievedAt": null, "stoppedAt": null
            }
        }
    });
    assert!(sanitize_webview_value(value.clone()).is_ok());

    let mut cross_goal = value;
    cross_goal["params"]["goal"]["goalId"] = json!("goal_other");
    assert_eq!(
        sanitize_webview_value(cross_goal).unwrap_err().code,
        "SENSITIVE_EVENT_BLOCKED"
    );
}

/// Goal activity/input 使用独立 revision 流且保持严格闭集；未知字段不能借助非 Turn
/// notification 绕过 WebView 投影边界。
#[test]
fn goal_activity_and_input_projection_enforce_closed_payloads() {
    let activity = json!({
        "jsonrpc": "2.0", "method": "goal/activity",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_goal_2", "sequence": 7,
            "occurredAt": "2026-09-04T08:00:02Z", "generation": 1,
            "goalId": "goal_demo", "goalRevision": 2, "eventSequence": 3,
            "activity": {"kind": "step", "status": "working",
                "summary": "正在同步合同", "stepId": "step_contract"}
        }
    });
    assert!(sanitize_webview_value(activity).is_ok());

    let input = json!({
        "jsonrpc": "2.0", "method": "goal/input-requested",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_goal_3", "sequence": 8,
            "occurredAt": "2026-09-04T08:00:03Z", "generation": 1,
            "goalId": "goal_demo", "goalRevision": 3, "eventSequence": 4,
            "input": {"inputRequestId": "goalinput_demo", "prompt": "是否继续？",
                "expiresAt": "2026-09-04T09:00:00Z", "createdAt": "2026-09-04T08:00:03Z"}
        }
    });
    assert!(sanitize_webview_value(input.clone()).is_ok());
    let mut unknown = input;
    unknown["params"]["input"]["response"] = json!("继续");
    assert!(sanitize_webview_value(unknown).is_err());
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
                "activitySequence": 4, "activityId": "activity_4", "taskThreadId": "thr_other",
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
            "features": ["task_threads_v1", "plan_goal_v1"],
            "reason": "runtime_lifecycle"
        }
    });
    assert!(sanitize_webview_value(failed.clone()).is_ok());

    let mut unknown_feature = failed;
    unknown_feature["params"]["features"] =
        json!(["task_threads_v1", "plan_goal_v1", "unknown_feature_v1"]);
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
        json!(["task_threads_v1", "plan_goal_v1"])
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
            "occurredAt": "2026-08-18T00:00:01Z", "callId": "call_1", "ordinal": 0
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

/// Terminal 投影要求 completion 携带 final message、failure 携带稳定 error，并始终原子携带
/// Java 冻结的最终 ChangeSet。
#[test]
fn terminal_projection_enforces_state_dependent_fields() {
    let completed = json!({
        "jsonrpc": "2.0", "method": "turn/terminal",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_terminal_1",
            "sequence": 5, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 5,
            "occurredAt": "2026-08-18T00:00:02Z", "state": "completed",
            "summary": "done", "finalMessage": {"messageId": "item_2", "text": "done"},
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

    let failed = json!({
        "jsonrpc": "2.0", "method": "turn/terminal",
        "params": {
            "serverInstanceId": "srv_1", "eventId": "evt_terminal_2",
            "sequence": 6, "generation": 1, "workspaceId": "ws_1",
            "threadId": "thr_1", "turnId": "turn_1", "threadRevision": 6,
            "occurredAt": "2026-08-18T00:00:03Z", "state": "failed",
            "summary": "failed", "errorCode": "MODEL_ERROR", "errorMessage": "failed",
            "changeSet": {
                "state": "partial", "incompleteReasons": ["commit_unconfirmed"],
                "files": [], "stats": {"files": 0, "additions": 0, "deletions": 0,
                    "binaryFiles": 0, "truncated": false}
            },
            "usage": {
                "requestId": "request_1", "requestOrdinal": 1, "modelRound": 1,
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
    assert!(sanitize_webview_value(failed.clone()).is_ok());
    let mut missing_collaboration_mode = failed;
    missing_collaboration_mode["params"]["usage"]["profile"]
        .as_object_mut()
        .expect("provider profile")
        .remove("collaborationMode");
    assert!(sanitize_webview_value(missing_collaboration_mode).is_err());

    let mut missing_change_set = completed;
    missing_change_set["params"]
        .as_object_mut()
        .expect("params")
        .remove("changeSet");
    assert!(sanitize_webview_value(missing_change_set).is_err());
}
