// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::app_runtime::infrastructure::bridge::event_projection::task_progress_is_observed;

/// 复用完整 TaskSummary fixture，使关联性测试只改变目标 identity，不弱化 wire 闭集。
fn task_summary(task_thread_id: &str, root_thread_id: &str) -> Value {
    json!({
        "taskThreadId": task_thread_id, "parentThreadId": root_thread_id,
        "rootThreadId": root_thread_id, "originTurnId": "turn_parent",
        "taskName": "检查测试", "depth": 1, "taskKind": "side_task",
        "lifecycle": "independent", "state": "running", "revision": 2,
        "latestActivitySequence": 1, "unreadCount": 0, "descendantCount": 0,
        "runningDescendantCount": 0, "needsAttentionCount": 0,
        "latestSafeSummary": "已派发", "startedAt": "2026-09-03T08:00:00Z",
        "completedAt": null, "updatedAt": "2026-09-03T08:00:01Z"
    })
}

/// Task method 枚举覆盖全部固定 JA-RPC 拼写，不提供 generic passthrough。
#[test]
fn task_method_is_a_closed_wire_catalog() {
    let methods = [
        TaskMethod::Create,
        TaskMethod::List,
        TaskMethod::Read,
        TaskMethod::Observe,
        TaskMethod::Unobserve,
        TaskMethod::Seen,
        TaskMethod::MessageSend,
        TaskMethod::Followup,
        TaskMethod::Cancel,
        TaskMethod::TreeDelete,
        TaskMethod::Close,
    ];
    assert_eq!(
        methods.map(TaskMethod::wire_name),
        [
            "task/create",
            "task/list",
            "task/read",
            "task/observe",
            "task/unobserve",
            "task/seen",
            "thread/message/send",
            "task/followup",
            "task/cancel",
            "task/tree/delete",
            "task/close",
        ]
    );
}

/// TaskSummary 的 nullable 字段仍然是 required；缺失不能被 serde `Option` 伪装成 null。
#[test]
fn task_summary_parser_requires_all_nullable_fields() {
    let summary = json!({
        "taskThreadId": "thr_child", "parentThreadId": "thr_parent", "rootThreadId": "thr_parent",
        "originTurnId": null, "taskName": "检查测试", "depth": 1,
        "taskKind": "side_task", "lifecycle": "independent", "state": "queued", "revision": 0,
        "latestActivitySequence": 1, "unreadCount": 0, "descendantCount": 0,
        "runningDescendantCount": 0, "needsAttentionCount": 0, "latestSafeSummary": null,
        "startedAt": null, "completedAt": null, "updatedAt": "2026-09-03T08:00:00Z"
    });
    assert!(parse_task_summary_value(&summary).is_ok());
    let mut missing = summary;
    missing
        .as_object_mut()
        .expect("summary object")
        .remove("completedAt");
    assert!(parse_task_summary_value(&missing).is_err());
}

/// TaskSummary 的 kind/lifecycle tuple 必须成对，禁止 side task 被投影为 attached。
#[test]
fn task_summary_parser_rejects_invalid_lifecycle_pair() {
    let summary = json!({
        "taskThreadId": "thr_child", "parentThreadId": "thr_parent", "rootThreadId": "thr_parent",
        "originTurnId": "turn_parent", "taskName": "检查测试", "depth": 1,
        "taskKind": "side_task", "lifecycle": "attached", "state": "running", "revision": 2,
        "latestActivitySequence": 2, "unreadCount": 0, "descendantCount": 0,
        "runningDescendantCount": 0, "needsAttentionCount": 0, "latestSafeSummary": "检查中",
        "startedAt": "2026-09-03T08:00:00Z", "completedAt": null,
        "updatedAt": "2026-09-03T08:00:00Z"
    });
    assert!(parse_task_summary_value(&summary).is_err());
}

/// Subagent 必须绑定触发它的父 Turn，避免取消传播和因果树失去锚点。
#[test]
fn task_summary_parser_requires_subagent_origin_turn() {
    let mut summary = task_summary("thr_child", "thr_root");
    summary["taskKind"] = json!("subagent");
    summary["lifecycle"] = json!("attached");
    summary["originTurnId"] = Value::Null;
    assert!(parse_task_summary_value(&summary).is_err());
}

/// TaskActivity 的 causalTurnId 是 required-nullable；字段缺失必须与显式 null 区分。
#[test]
fn task_activity_parser_requires_nullable_causal_turn_id() {
    let activity = json!({
        "activitySequence": 1, "activityId": "activity_created", "rootThreadId": "thr_parent",
        "taskThreadId": "thr_child",
        "actorThreadId": "thr_parent", "causalTurnId": null, "kind": "dispatched",
        "summary": {"text": "已派发"}, "createdAt": "2026-09-03T08:00:00Z"
    });
    assert!(parse_task_activity_value(&activity).is_ok());
    let mut missing = activity;
    missing
        .as_object_mut()
        .expect("activity object")
        .remove("causalTurnId");
    assert!(parse_task_activity_value(&missing).is_err());
}

/// create/list parser 必须把 idle 创建结果绑定到本次父任务与根任务请求，且不携带 Turn。
#[test]
fn task_collection_results_reject_cross_tree_projection() {
    let task = task_summary("thr_child", "thr_root");
    let mut create_task = task.clone();
    create_task["state"] = json!("idle");
    create_task["startedAt"] = Value::Null;
    let create = json!({"accepted": true, "task": create_task});
    assert!(
        parse_task_create_result(create.clone(), "thr_root", Some("turn_parent"), "检查测试")
            .is_ok()
    );
    assert!(
        parse_task_create_result(create, "thr_other", Some("turn_parent"), "检查测试").is_err()
    );

    let list = json!({"items": [task_summary("thr_child", "thr_root")]});
    assert!(parse_task_list_result(list.clone(), "thr_root").is_ok());
    assert!(parse_task_list_result(list, "thr_other").is_err());

    let duplicate = json!({"items": [
        task_summary("thr_child", "thr_root"),
        task_summary("thr_child", "thr_root")
    ]});
    assert!(parse_task_list_result(duplicate, "thr_root").is_err());

    let mut detached = task_summary("thr_grandchild", "thr_root");
    detached["depth"] = json!(2);
    detached["parentThreadId"] = json!("thr_missing");
    assert!(parse_task_list_result(json!({"items": [detached]}), "thr_root").is_err());
}

/// observe 与 mutation 回包必须精确关联请求 identity/revision，不能只验证字段格式。
#[test]
fn task_identity_results_reject_request_mismatch() {
    let observe = json!({
        "observationId": "observe_child", "taskThreadId": "thr_child", "revision": 2
    });
    assert!(parse_task_observe_result(observe.clone(), "thr_child", 2).is_ok());
    assert!(parse_task_observe_result(observe, "thr_other", 2).is_err());

    let mutation = json!({
        "accepted": true, "task": task_summary("thr_child", "thr_root")
    });
    assert!(parse_task_mutation_result(mutation.clone(), "thr_child").is_ok());
    assert!(parse_task_mutation_result(mutation, "thr_other").is_err());

    let followup = json!({
        "accepted": true, "messageId": "msg_followup", "turnId": "turn_followup",
        "task": task_summary("thr_child", "thr_root")
    });
    assert!(parse_task_followup_result(followup.clone(), "thr_child").is_ok());
    assert!(parse_task_followup_result(followup, "thr_other").is_err());
}

/// task/read 只接受与目标 Child 相连的 activity/mailbox，并校验 kind 与 seed 继承模式配对。
#[test]
fn task_read_result_rejects_cross_task_detail_rows() {
    let result = json!({
        "task": task_summary("thr_child", "thr_root"),
        "thread": {
            "threadId": "thr_child", "workspaceId": "ws_root", "activeGoalId": "goal_child",
            "preferences": {
                "providerId": "provider_side", "modelId": "model_side", "reasoningLevel": "high",
                "accessMode": "full_access", "collaborationMode": "plan", "titleSource": "manual"
            },
            "title": "检查测试", "status": "active", "pinned": false,
            "latestTurnStatus": "running", "latestTurnSeen": true, "revision": 2,
            "createdAt": "2026-09-03T08:00:00Z", "updatedAt": "2026-09-03T08:00:01Z"
        },
        "contextSeed": {
            "contextSeedId": "seed_child", "parentRevision": 1,
            "inheritanceMode": "effective_context",
            "taskBrief": [{"type": "text", "text": "检查"}],
            "inheritedContextSummary": "继承摘要",
            "inheritedContextPreview": [{
                "role": "user", "text": "检查", "attachmentIds": []
            }],
            "fingerprint": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "createdAt": "2026-09-03T08:00:00Z"
        },
        "activities": [{
            "activitySequence": 1, "activityId": "activity_created",
            "rootThreadId": "thr_root", "taskThreadId": "thr_child", "actorThreadId": "thr_root",
            "causalTurnId": null, "kind": "dispatched", "summary": {"text": "已派发"},
            "createdAt": "2026-09-03T08:00:00Z"
        }],
        "mailbox": [{
            "mailboxSequence": 1, "messageId": "msg_note", "senderThreadId": "thr_root",
            "targetThreadId": "thr_child", "causalTurnId": null, "kind": "message",
            "content": [{"type": "text", "text": "继续"}], "state": "pending",
            "boundTurnId": null, "createdAt": "2026-09-03T08:00:00Z",
            "updatedAt": "2026-09-03T08:00:00Z", "consumedAt": null
        }],
        "nextCursor": null
    });
    let parsed = parse_task_read_result(result.clone(), "thr_child").expect("task read result");
    assert_eq!(parsed.thread.thread_id, "thr_child");
    assert_eq!(parsed.thread.workspace_id, "ws_root");
    assert_eq!(parsed.thread.active_goal_id.as_deref(), Some("goal_child"));
    assert_eq!(parsed.thread.title, "检查测试");
    assert_eq!(parsed.thread.status, "active");
    assert!(!parsed.thread.pinned);
    assert_eq!(parsed.thread.latest_turn_status.as_deref(), Some("running"));
    assert!(parsed.thread.latest_turn_seen);
    assert_eq!(parsed.thread.revision, 2);
    assert_eq!(parsed.thread.created_at, "2026-09-03T08:00:00Z");
    assert_eq!(parsed.thread.updated_at, "2026-09-03T08:00:01Z");
    let preferences = parsed.thread.preferences.expect("thread preferences");
    assert_eq!(preferences.provider_id, "provider_side");
    assert_eq!(preferences.model_id, "model_side");
    assert_eq!(preferences.reasoning_level.as_deref(), Some("high"));
    assert_eq!(preferences.access_mode, "full_access");
    assert_eq!(preferences.collaboration_mode, "plan");
    assert_eq!(preferences.title_source, "manual");

    let mut idle = result.clone();
    idle["task"]["state"] = json!("idle");
    idle["task"]["startedAt"] = Value::Null;
    idle["contextSeed"]["taskBrief"] = Value::Null;
    idle["activities"][0]["kind"] = json!("created");
    assert!(parse_task_read_result(idle, "thr_child").is_ok());

    let mut mismatched_thread = result.clone();
    mismatched_thread["thread"]["threadId"] = json!("thr_other");
    assert!(parse_task_read_result(mismatched_thread, "thr_child").is_err());

    let mut foreign = result;
    foreign["mailbox"][0]["senderThreadId"] = json!("thr_other_parent");
    foreign["mailbox"][0]["targetThreadId"] = json!("thr_other_child");
    assert!(parse_task_read_result(foreign, "thr_child").is_err());
}

/// 详情页游标、活动与 Mailbox 必须保持各自的严格单调边界，避免分页重复覆盖新状态。
#[test]
fn task_read_rejects_invalid_cursor_and_non_monotonic_sequences() {
    let mut result = json!({
        "task": task_summary("thr_child", "thr_root"),
        "thread": {
            "threadId": "thr_child", "workspaceId": "ws_root", "activeGoalId": null,
            "preferences": null, "title": "检查测试", "status": "active", "pinned": false,
            "latestTurnStatus": "running", "latestTurnSeen": true, "revision": 2,
            "createdAt": "2026-09-03T08:00:00Z", "updatedAt": "2026-09-03T08:00:01Z"
        },
        "contextSeed": {
            "contextSeedId": "seed_child", "parentRevision": 1,
            "inheritanceMode": "effective_context",
            "taskBrief": [{"type": "text", "text": "检查"}],
            "inheritedContextSummary": "继承摘要",
            "inheritedContextPreview": [],
            "fingerprint": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "createdAt": "2026-09-03T08:00:00Z"
        },
        "activities": [
            {"activitySequence": 1, "activityId": "activity_one", "rootThreadId": "thr_root", "taskThreadId": "thr_child",
             "actorThreadId": "thr_root", "causalTurnId": null, "kind": "progress",
             "summary": {"text": "已派发"}, "createdAt": "2026-09-03T08:00:00Z"},
            {"activitySequence": 1, "activityId": "activity_two", "rootThreadId": "thr_root", "taskThreadId": "thr_child",
             "actorThreadId": "thr_root", "causalTurnId": null, "kind": "progress",
             "summary": {"text": "已派发"}, "createdAt": "2026-09-03T08:00:01Z"}
        ],
        "mailbox": [], "nextCursor": "task:1:0"
    });
    assert!(parse_task_read_result(result.clone(), "thr_child").is_err());
    result["activities"] = json!([]);
    result["nextCursor"] = json!("opaque_cursor");
    assert!(parse_task_read_result(result, "thr_child").is_err());
}

/// unobserve 只有收到无额外字段的 accepted=true 才能向 renderer 确认资源已释放。
#[test]
fn task_unobserve_requires_positive_exact_ack() {
    assert!(parse_accepted(json!({"accepted": true})).is_ok());
    assert!(parse_accepted(json!({"accepted": false})).is_err());
    assert!(parse_accepted(json!({"accepted": true, "observationId": "observe_child"})).is_err());
}

/// 侧聊关闭回执只有精确的 `closed=true` 才能释放本地生命周期资源。
#[test]
fn task_close_result_requires_strict_positive_ack() {
    assert_eq!(
        parse_task_close_result(json!({"closed": true})),
        Ok(TaskCloseResult { closed: true })
    );
    assert!(parse_task_close_result(json!({})).is_err());
    assert!(parse_task_close_result(json!({"closed": false})).is_err());
    assert!(
        parse_task_close_result(json!({"closed": true, "taskThreadId": "thr_side"})).is_err()
    );
}

/// Registry 同时绑定 owner 与 sidecar generation；reload drain 后迟到 progress 立即失效。
#[test]
fn task_observation_registry_fences_owner_and_generation() {
    let registry = TaskObservationRegistry::default();
    registry
        .register("observe_child", TASK_OBSERVATION_OWNER_MAIN, 3)
        .expect("register observation");
    assert!(registry.is_active("observe_child", 3));
    assert!(!registry.is_active("observe_child", 2));
    assert_eq!(
        registry.drain_owner(TASK_OBSERVATION_OWNER_MAIN),
        vec!["observe_child".to_owned()]
    );
    assert!(!registry.is_active("observe_child", 3));
}

/// Tauri caller 在 observe 回复前消失时，actor 必须取得成功 handle 以执行补偿 unobserve。
#[test]
fn dropped_task_observe_receiver_requests_compensation() {
    let (reply, receiver) = mpsc::sync_channel(1);
    drop(receiver);
    let observation = parse_task_observe_result(
        json!({
            "observationId": "observe_child",
            "taskThreadId": "thr_child",
            "revision": 2
        }),
        "thr_child",
        2,
    )
    .expect("valid observation");

    assert_eq!(
        deliver_task_observe_reply(reply, Ok(observation.clone())),
        Some(observation)
    );
}

/// Event drain 只投影当前 registry handle；同一合法 frame 在 reload drain 后必须被过滤。
#[test]
fn task_progress_filter_rejects_stale_observation() {
    let registry = TaskObservationRegistry::default();
    registry
        .register("observe_child", TASK_OBSERVATION_OWNER_MAIN, 4)
        .expect("register observation");
    let frame = RpcFrame::notification("task/progress", json!({"observationId": "observe_child"}))
        .expect("progress frame");
    assert!(task_progress_is_observed(&frame, 4, &registry));
    registry.drain_owner(TASK_OBSERVATION_OWNER_MAIN);
    assert!(!task_progress_is_observed(&frame, 4, &registry));
}
