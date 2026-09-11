// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 面向受信任 WebView 的严格事件与状态投影。

use crate::app_runtime::{EventSink, RuntimeCommandError, RuntimeStatusKind};
use ja_runtime::app_server_process::{CodecError, Limits, RpcFrame, valid_ready_token};
use serde_json::{Value, json};
use std::time::{SystemTime, UNIX_EPOCH};

/// 前端 RuntimeProvider 消费的稳定事件名，避免 feature 自行拼接事件通道。
pub const RPC_FRAME_EVENT: &str = "ja://rpc/frame";
const STATUS_EVENT_PREFIX: &str = "evt_host_";
const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;
/// Host emitter 与 Java event validator 共享唯一 2.1 feature 顺序，避免两条 Ready 来源漂移。
const RUNTIME_STATUS_FEATURES: [&str; 3] = ["task_threads_v1", "plan_goal_v1", "interaction_v1"];
const NOTIFICATION_COMMON_FIELDS: [&str; 5] = [
    "serverInstanceId",
    "eventId",
    "sequence",
    "occurredAt",
    "generation",
];
const TURN_COMMON_FIELDS: [&str; 9] = [
    "serverInstanceId",
    "eventId",
    "sequence",
    "occurredAt",
    "generation",
    "workspaceId",
    "threadId",
    "turnId",
    "threadRevision",
];
const TASK_COMMON_FIELDS: [&str; 8] = [
    "serverInstanceId",
    "eventId",
    "sequence",
    "occurredAt",
    "generation",
    "rootThreadId",
    "taskThreadId",
    "taskRevision",
];

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 消费私有 ready challenge 后发送一个 response/event frame，确保 challenge 不跨越 IPC。
pub(crate) fn emit_frame(sink: &EventSink, frame: &RpcFrame) -> Result<(), RuntimeCommandError> {
    let value = frame_to_value(frame).map_err(|_| RuntimeCommandError::unavailable())?;
    sink(sanitize_webview_value(value)?).map_err(|_| RuntimeCommandError::event_delivery())
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 将已校验 foundation frame 转回 JSON，不复制 parser 或 writer；尾部换行只在 IPC 边界移除。
pub(crate) fn frame_to_value(frame: &RpcFrame) -> Result<Value, CodecError> {
    let encoded = frame.encode(Limits::default().max_frame_bytes)?;
    serde_json::from_slice(&encoded[..encoded.len().saturating_sub(1)])
        .map_err(|_| CodecError::InvalidJson)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 先校验完整原生 frame，再消费唯一合法 ready echo，并在投递 WebView 前拒绝所有其他保留 marker；顺序不可调换，因为 lifecycle 校验必须看到 challenge，而序列化投影绝不能保留它。
pub(crate) fn sanitize_webview_value(mut value: Value) -> Result<Value, RuntimeCommandError> {
    validate_webview_event(&value)?;
    strip_ready_token(&mut value)?;
    scrub_tokens(&mut value, &mut Vec::new())?;
    Ok(value)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 只从精确协议 leaf 删除已校验的 Java ready challenge；畸形或错位 marker 留给 `scrub_tokens` 拒绝。
fn strip_ready_token(value: &mut Value) -> Result<(), RuntimeCommandError> {
    let is_ready = value.get("method").and_then(Value::as_str) == Some("runtime/status-changed")
        && value.pointer("/params/status").and_then(Value::as_str) == Some("ready");
    let Some(params) = value.get_mut("params").and_then(Value::as_object_mut) else {
        return Ok(());
    };
    if let Some(token) = params.get("readyToken") {
        if !is_ready || !token.as_str().is_some_and(valid_ready_token) {
            return Err(invalid_projection());
        }
        params.remove("readyToken");
    }
    Ok(())
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 原生 handshake leaf 被消费后，递归拒绝所有残留 readyToken 字段，防止敏感标记从其他位置穿透。
fn scrub_tokens(value: &mut Value, path: &mut Vec<String>) -> Result<(), RuntimeCommandError> {
    match value {
        Value::Object(object) => {
            let keys: Vec<String> = object.keys().cloned().collect();
            for key in keys {
                let normalized = key
                    .chars()
                    .filter(|character| !matches!(character, '_' | '-'))
                    .flat_map(char::to_lowercase)
                    .collect::<String>();
                if normalized == "readytoken" {
                    return Err(RuntimeCommandError {
                        code: "SENSITIVE_EVENT_BLOCKED",
                        message: "runtime event contains protected data",
                        retryable: false,
                    });
                }
                if normalized.ends_with("token")
                    || normalized.contains("apikey")
                    || normalized.contains("authorization")
                    || normalized.contains("password")
                    || normalized.contains("secret")
                {
                    return Err(invalid_projection());
                }
                path.push(key.clone());
                if let Some(child) = object.get_mut(&key) {
                    scrub_tokens(child, path)?;
                }
                path.pop();
            }
        }
        Value::Array(values) => {
            for child in values {
                scrub_tokens(child, path)?;
            }
        }
        Value::Null | Value::Bool(_) | Value::Number(_) | Value::String(_) => {}
    }
    Ok(())
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 在 event revision 到达 WebView 状态前拒绝未知版本；不为 Kernel 未拥有的兼容路径保留任何未知 method 或字段。
fn validate_webview_event(value: &Value) -> Result<(), RuntimeCommandError> {
    let root = value.as_object().ok_or_else(invalid_projection)?;
    if !exact_keys(root, &["jsonrpc", "method", "params"], &[])
        || root.get("jsonrpc").and_then(Value::as_str) != Some("2.0")
    {
        return Err(invalid_projection());
    }
    let method = root
        .get("method")
        .and_then(Value::as_str)
        .ok_or_else(invalid_projection)?;
    let params = root
        .get("params")
        .and_then(Value::as_object)
        .ok_or_else(invalid_projection)?;
    if method == "configuration/changed" {
        validate_config_changed_event(params)
    } else if method.starts_with("runtime/") {
        validate_runtime_event(method, params)
    } else {
        validate_kernel_event(method, params)
    }
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 应用 configuration-specific scope 规则前先校验冻结的 notification metadata，使每个 invalidation 投影保持有序并受 restart fence 约束。
fn validate_config_changed_event(
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if !valid_notification_common(params)
        || !exact_keys_with_common(
            params,
            &NOTIFICATION_COMMON_FIELDS,
            &["scope", "version"],
            &["workspaceId"],
        )
        || !valid_config_version(params.get("version"))
    {
        return Err(invalid_projection());
    }
    let scope = params.get("scope").and_then(Value::as_str);
    let workspace_id = params.get("workspaceId");
    if !matches!(scope, Some("user" | "project"))
        || (scope == Some("project") && !valid_prefixed(workspace_id, "ws_", 99))
        || (scope == Some("user") && workspace_id.is_some())
    {
        return Err(invalid_projection());
    }
    Ok(())
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
fn valid_config_version(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|version| {
        version.strip_prefix("cfg_").is_some_and(|suffix| {
            !suffix.is_empty()
                && suffix.len() <= 252
                && suffix
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
        })
    })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 使用所有 v1 notification 共用的进程级顺序 metadata 校验唯一 runtime lifecycle 事件，避免建立第二套时序规则。
fn validate_runtime_event(
    method: &str,
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if !valid_notification_common(params) {
        return Err(invalid_projection());
    }
    match method {
        "runtime/status-changed" => {
            if !exact_keys_with_common(
                params,
                &NOTIFICATION_COMMON_FIELDS,
                &["status", "features"],
                &["reason", "readyToken"],
            ) {
                return Err(invalid_projection());
            }
            let status = params.get("status").and_then(Value::as_str);
            if !status.is_some_and(|value| {
                matches!(
                    value,
                    "starting" | "ready" | "shutting_down" | "stopped" | "failed"
                )
            }) || params.get("features") != Some(&json!(RUNTIME_STATUS_FEATURES))
                || !valid_runtime_status(
                    status.unwrap_or_default(),
                    params.get("reason"),
                    params.get("readyToken"),
                )
            {
                return Err(invalid_projection());
            }
        }
        _ => return Err(invalid_projection()),
    }
    Ok(())
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 强制执行 v1 lifecycle status/reason/token tuple，使失败 shutdown 不会伪装成干净停止，并阻止 ready challenge 跨状态变体出现。
fn valid_runtime_status(status: &str, reason: Option<&Value>, ready_token: Option<&Value>) -> bool {
    let reason = reason.and_then(Value::as_str);
    match status {
        "starting" => reason == Some("initialize") && ready_token.is_none(),
        "ready" => {
            reason.is_none()
                && ready_token
                    .and_then(Value::as_str)
                    .is_some_and(valid_ready_token)
        }
        "shutting_down" => {
            matches!(reason, Some("user_requested" | "host_shutdown")) && ready_token.is_none()
        }
        "stopped" => reason == Some("shutdown_complete") && ready_token.is_none(),
        "failed" => reason == Some("runtime_lifecycle") && ready_token.is_none(),
        _ => false,
    }
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 校验封闭的 Ja Kernel 事件词汇及每个 method-specific 字段，未知扩展一律 fail-closed。
fn validate_kernel_event(
    method: &str,
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if method == "plan/changed" {
        return validate_plan_event(params);
    }
    if method.starts_with("goal/") {
        return validate_goal_event(method, params);
    }
    if method == "interaction/changed" {
        return validate_interaction_event(params);
    }
    if method.starts_with("task/") {
        return validate_task_event(method, params);
    }
    if method == "thread/metadata-changed" {
        return validate_thread_metadata_event(params);
    }
    if matches!(
        method,
        "context/compaction-started" | "context/compacted" | "context/compaction-failed"
    ) {
        return validate_context_event(method, params);
    }
    if method == "turn/input-queue-changed" {
        return validate_input_queue_changed_event(params);
    }
    if matches!(
        method,
        "assistant/text-delta" | "assistant/reasoning-summary-delta"
    ) {
        if !valid_turn_common(params)
            || !exact_keys_with_common(params, &TURN_COMMON_FIELDS, &["streamSeq", "text"], &[])
            || !positive_integer(params.get("streamSeq"))
            || !bounded_text(params.get("text"), 0, 1_048_576)
        {
            return Err(invalid_projection());
        }
        return Ok(());
    }
    if !valid_turn_common(params) {
        return Err(invalid_projection());
    }
    let exact = |required: &[&str], optional: &[&str]| {
        exact_keys_with_common(params, &TURN_COMMON_FIELDS, required, optional)
    };
    match method {
        "turn/state-changed" => {
            if !exact(&["from", "to"], &[])
                || !valid_turn_state(params.get("from"))
                || !valid_turn_state(params.get("to"))
                || !valid_turn_transition(params.get("from"), params.get("to"))
            {
                return Err(invalid_projection());
            }
        }
        "assistant/model-step-committed" => {
            if !exact(
                &["messageId", "text", "modelRound", "toolCalls"],
                &["reasoningSummary", "usage"],
            ) || !valid_prefixed(params.get("messageId"), "item_", 101)
                || !bounded_text(params.get("text"), 0, 1_048_576)
                || !integer_in_range(params.get("modelRound"), 1, 128)
                || !optional_bounded_text(params.get("reasoningSummary"), 0, 1_048_576)
                || !optional_usage(params.get("usage"))
                || !usage_matches_model_round(params.get("usage"), params.get("modelRound"))
                || !valid_tool_calls(params.get("toolCalls"))
            {
                return Err(invalid_projection());
            }
        }
        "tool/started" => {
            if !exact(&["callId", "ordinal"], &[])
                || !valid_prefixed(params.get("callId"), "call_", 101)
                || !integer_in_range(params.get("ordinal"), 0, 1_023)
            {
                return Err(invalid_projection());
            }
        }
        "tool/batch-committed" => {
            if !exact(&["results"], &[]) || !valid_tool_results(params.get("results")) {
                return Err(invalid_projection());
            }
        }
        "approval/requested" => {
            if !exact(
                &[
                    "approvalId",
                    "callId",
                    "toolName",
                    "reason",
                    "expiresAt",
                    "from",
                    "to",
                ],
                &[],
            ) || !valid_prefixed(params.get("approvalId"), "appr_", 101)
                || !valid_prefixed(params.get("callId"), "call_", 101)
                || !valid_identifier(params.get("toolName"), 256)
                || !bounded_text(params.get("reason"), 0, 1_048_576)
                || !valid_timestamp(params.get("expiresAt"))
                || params.get("from").and_then(Value::as_str) != Some("running")
                || params.get("to").and_then(Value::as_str) != Some("waiting_approval")
            {
                return Err(invalid_projection());
            }
        }
        "approval/resolved" => {
            if !exact(&["approvalId", "decision", "from", "to"], &[])
                || !valid_prefixed(params.get("approvalId"), "appr_", 101)
                || !params
                    .get("decision")
                    .and_then(Value::as_str)
                    .is_some_and(|decision| matches!(decision, "approve" | "deny"))
                || params.get("from").and_then(Value::as_str) != Some("waiting_approval")
                || params.get("to").and_then(Value::as_str) != Some("running")
            {
                return Err(invalid_projection());
            }
        }
        "workspace/dirty" => {
            if !exact(&["dirty", "reason"], &[])
                || params.get("dirty").and_then(Value::as_bool).is_none()
                || !params
                    .get("reason")
                    .and_then(Value::as_str)
                    .is_some_and(|reason| {
                        matches!(
                            reason,
                            "tool_write" | "shell" | "external_change" | "reconciled"
                        )
                    })
            {
                return Err(invalid_projection());
            }
        }
        "turn/input-consumed" => {
            if !exact(
                &["input", "userItem", "inputQueue"],
                &["assistantSettlement"],
            ) || !valid_input_consumed_payload(params)
            {
                return Err(invalid_projection());
            }
        }
        "turn/messages_received" => {
            if !exact(&["items"], &[])
                || !valid_thread_message_items(params.get("items"), params.get("turnId"))
            {
                return Err(invalid_projection());
            }
        }
        "turn/terminal" => {
            if !exact(
                &["state", "summary", "changeSet"],
                &["finalMessage", "usage", "errorCode", "errorMessage"],
            ) || !bounded_text(params.get("summary"), 0, 1_048_576)
                || !optional_terminal_message(params.get("finalMessage"))
                || !optional_terminal_usage(params.get("usage"))
                || !optional_identifier(params.get("errorCode"), 256)
                || !optional_bounded_text(params.get("errorMessage"), 0, 1_048_576)
                || !valid_change_set(params.get("changeSet"))
                || !valid_terminal_condition(params)
            {
                return Err(invalid_projection());
            }
        }
        _ => return Err(invalid_projection()),
    }
    Ok(())
}

/// Goal notification 使用独立 Goal revision/event sequence；changed 复用类型化完整投影，
/// activity/input 保持小型闭集，避免错误落入要求 Turn identity 的通用分支。
fn validate_goal_event(
    method: &str,
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if !valid_notification_common(params)
        || !valid_prefixed(params.get("goalId"), "goal_", 101)
        || !non_negative_integer(params.get("goalRevision"))
        || !non_negative_integer(params.get("eventSequence"))
    {
        return Err(invalid_projection());
    }
    let exact = |required: &[&str]| {
        exact_keys_with_common(
            params,
            &NOTIFICATION_COMMON_FIELDS,
            &["goalId", "goalRevision", "eventSequence"]
                .into_iter()
                .chain(required.iter().copied())
                .collect::<Vec<_>>(),
            &[],
        )
    };
    let valid = match method {
        "goal/changed" => {
            let projection = json!({
                "goal": params.get("goal").cloned().unwrap_or(Value::Null),
                "eventSequence": params.get("eventSequence").cloned().unwrap_or(Value::Null)
            });
            exact(&["goal"])
                && serde_json::from_value::<super::goal::GoalProjectionResultDto>(projection)
                    .is_ok_and(|value| {
                        params.get("goalId").and_then(Value::as_str)
                            == Some(value.goal.goal_id.as_str())
                            && params.get("goalRevision").and_then(Value::as_u64)
                                == Some(value.goal.revision)
                            && params.get("eventSequence").and_then(Value::as_u64)
                                == Some(value.event_sequence)
                    })
        }
        "goal/activity" => {
            exact(&["activity"])
                && params
                    .get("activity")
                    .and_then(Value::as_object)
                    .is_some_and(|activity| {
                        exact_keys(activity, &["kind", "status", "summary", "stepId"], &[])
                            && activity
                                .get("kind")
                                .and_then(Value::as_str)
                                .is_some_and(|kind| {
                                    matches!(kind, "run" | "step" | "evaluation" | "recovery")
                                })
                            && activity.get("status").and_then(Value::as_str).is_some_and(
                                |status| {
                                    matches!(
                                        status,
                                        "planning"
                                            | "awaiting_approval"
                                            | "working"
                                            | "waiting_approval"
                                            | "waiting_input"
                                            | "verifying"
                                            | "needs_attention"
                                            | "paused"
                                            | "achieved"
                                            | "stopped"
                                    )
                                },
                            )
                            && bounded_text(activity.get("summary"), 1, 32_768)
                            && activity.get("stepId").is_some_and(|step_id| {
                                step_id.is_null() || valid_prefixed(Some(step_id), "step_", 101)
                            })
                    })
        }
        _ => false,
    };
    valid.then_some(()).ok_or_else(invalid_projection)
}

/// Interaction 事件只携带轻量 revision/kind，不把问题正文或答案作为通知重复发送。
fn validate_interaction_event(
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if !valid_notification_common(params)
        || !valid_prefixed(params.get("threadId"), "thr_", 100)
        || !valid_prefixed(params.get("requestId"), "interaction_", 128)
        || !non_negative_integer(params.get("requestRevision"))
        || !non_negative_integer(params.get("eventSequence"))
        || !params
            .get("kind")
            .and_then(Value::as_str)
            .is_some_and(|value| {
                matches!(
                    value,
                    "created" | "draft_changed" | "answered" | "cancelled" | "superseded"
                )
            })
        || !exact_keys_with_common(
            params,
            &NOTIFICATION_COMMON_FIELDS,
            &[
                "threadId",
                "requestId",
                "requestRevision",
                "eventSequence",
                "kind",
            ],
            &[],
        )
    {
        return Err(invalid_projection());
    }
    Ok(())
}

/// Plan 事件同时携带完整计划摘要与独立进度投影；两者共用同一事件水位，避免 UI 将旧步骤进度拼到新计划上。
fn validate_plan_event(params: &serde_json::Map<String, Value>) -> Result<(), RuntimeCommandError> {
    if !valid_notification_common(params)
        || !valid_prefixed(params.get("ownerThreadId"), "thr_", 100)
        || !valid_prefixed(params.get("planId"), "plan_", 101)
        || !non_negative_integer(params.get("planRevision"))
        || !non_negative_integer(params.get("eventSequence"))
        || !exact_keys_with_common(
            params,
            &NOTIFICATION_COMMON_FIELDS,
            &[
                "ownerThreadId",
                "planId",
                "planRevision",
                "eventSequence",
                "plan",
                "progress",
            ],
            &[],
        )
    {
        return Err(invalid_projection());
    }
    let plan = params.get("plan").and_then(Value::as_object);
    let progress = params.get("progress").and_then(Value::as_object);
    let valid_plan = plan.is_some_and(|value| {
        exact_keys(
            value,
            &[
                "planId",
                "owner",
                "objective",
                "status",
                "revision",
                "activePlanRevisionId",
                "activeRunId",
                "createdAt",
                "updatedAt",
            ],
            &[],
        ) && valid_prefixed(value.get("planId"), "plan_", 101)
            && valid_timestamp(value.get("createdAt"))
            && valid_timestamp(value.get("updatedAt"))
            && value
                .get("status")
                .and_then(Value::as_str)
                .is_some_and(|status| {
                    matches!(
                        status,
                        "draft"
                            | "awaiting_approval"
                            | "approved"
                            | "executing"
                            | "verifying"
                            | "paused"
                            | "completed"
                            | "stopped"
                    )
                })
            && value
                .get("owner")
                .and_then(Value::as_object)
                .is_some_and(|owner| {
                    exact_keys(owner, &["kind", "threadId"], &[])
                        && owner.get("kind").and_then(Value::as_str) == Some("thread")
                        && valid_prefixed(owner.get("threadId"), "thr_", 100)
                })
            && value
                .get("activePlanRevisionId")
                .is_some_and(|id| id.is_null() || valid_prefixed(Some(id), "planrev_", 104))
            && value
                .get("activeRunId")
                .is_some_and(|id| id.is_null() || valid_prefixed(Some(id), "run_", 100))
    });
    let valid_progress = progress.is_some_and(|value| {
        exact_keys(
            value,
            &[
                "currentStepId",
                "currentStepTitle",
                "completedRequiredSteps",
                "totalRequiredSteps",
            ],
            &[],
        ) && value
            .get("currentStepId")
            .is_some_and(|id| id.is_null() || valid_prefixed(Some(id), "step_", 101))
            && value
                .get("currentStepTitle")
                .is_some_and(|title| title.is_null() || bounded_text(Some(title), 1, 240))
            && integer_in_range(value.get("completedRequiredSteps"), 0, 256)
            && integer_in_range(value.get("totalRequiredSteps"), 0, 256)
            && value.get("completedRequiredSteps").and_then(Value::as_u64)
                <= value.get("totalRequiredSteps").and_then(Value::as_u64)
    });
    let identity_matches = plan.is_some_and(|value| {
        value.get("planId") == params.get("planId")
            && value.get("revision").and_then(Value::as_u64)
                == params.get("planRevision").and_then(Value::as_u64)
    });
    if valid_plan && valid_progress && identity_matches {
        Ok(())
    } else {
        Err(invalid_projection())
    }
}

/// 三类 Task notification 使用独立 task revision 流；activity/mailbox 是不可丢事实，
/// progress 额外绑定 connection-scoped observation handle。
fn validate_task_event(
    method: &str,
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if !valid_task_common(params) {
        return Err(invalid_projection());
    }
    let exact =
        |required: &[&str]| exact_keys_with_common(params, &TASK_COMMON_FIELDS, required, &[]);
    match method {
        "task/activity" => {
            if !exact(&["activity", "task"]) {
                return Err(invalid_projection());
            }
            let activity =
                crate::app_runtime::infrastructure::bridge::tasks::parse_task_activity_value(
                    params.get("activity").unwrap_or(&Value::Null),
                )
                .map_err(|_| invalid_projection())?;
            let task = crate::app_runtime::infrastructure::bridge::tasks::parse_task_summary_value(
                params.get("task").unwrap_or(&Value::Null),
            )
            .map_err(|_| invalid_projection())?;
            let task_thread_id = params.get("taskThreadId").and_then(Value::as_str);
            let root_thread_id = params.get("rootThreadId").and_then(Value::as_str);
            let task_revision = params.get("taskRevision").and_then(Value::as_u64);
            if task_thread_id != Some(activity.task_thread_id.as_str())
                || task_thread_id != Some(task.task_thread_id.as_str())
                || root_thread_id != Some(task.root_thread_id.as_str())
                || task_revision != Some(task.revision)
                || activity.activity_sequence != task.latest_activity_sequence
                || task.latest_safe_summary.as_deref() != Some(activity.summary.as_str())
            {
                return Err(invalid_projection());
            }
        }
        "task/progress" => {
            if !exact(&["observationId", "progressRevision", "safeSummary"])
                || !valid_prefixed(params.get("observationId"), "observe_", 103)
                || !non_negative_integer(params.get("progressRevision"))
                || !bounded_text(params.get("safeSummary"), 0, 32_768)
            {
                return Err(invalid_projection());
            }
        }
        "task/mailbox-changed" => {
            if !exact(&["mailboxSequence", "unreadCount"])
                || !positive_integer(params.get("mailboxSequence"))
                || !non_negative_integer(params.get("unreadCount"))
            {
                return Err(invalid_projection());
            }
        }
        _ => return Err(invalid_projection()),
    }
    Ok(())
}

/// Task 事件使用 root/task/revision，而非 Workspace/Turn identity；两套 revision 不可混用。
fn valid_task_common(params: &serde_json::Map<String, Value>) -> bool {
    valid_notification_common(params)
        && valid_prefixed(params.get("rootThreadId"), "thr_", 100)
        && valid_prefixed(params.get("taskThreadId"), "thr_", 100)
        && non_negative_integer(params.get("taskRevision"))
}

/// 队列变更只参与独立 queue revision 流，因此明确排除 threadRevision，避免 reducer 混用两套 CAS。
fn validate_input_queue_changed_event(
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    let required = [
        "serverInstanceId",
        "eventId",
        "sequence",
        "occurredAt",
        "generation",
        "workspaceId",
        "threadId",
        "turnId",
        "inputQueue",
    ];
    let turn_id = params.get("turnId").and_then(Value::as_str);
    if !valid_notification_common(params)
        || !exact_keys(params, &required, &[])
        || !valid_prefixed(params.get("workspaceId"), "ws_", 99)
        || !valid_prefixed(params.get("threadId"), "thr_", 100)
        || !valid_prefixed(params.get("turnId"), "turn_", 101)
        || turn_id.is_none_or(|turn_id| {
            crate::app_runtime::infrastructure::bridge::operations::parse_input_queue(
                params.get("inputQueue"),
                turn_id,
            )
            .is_err()
        })
    {
        return Err(invalid_projection());
    }
    Ok(())
}

/// 消费事件把队列行、公开 user item 与可选上一轮 Assistant 结算原子绑定，防止 UI 看到重复或错序消息。
fn valid_input_consumed_payload(params: &serde_json::Map<String, Value>) -> bool {
    let Some(turn_id) = params.get("turnId").and_then(Value::as_str) else {
        return false;
    };
    let Ok(input) = crate::app_runtime::infrastructure::bridge::operations::parse_queued_input(
        params.get("input").unwrap_or(&Value::Null),
        turn_id,
    ) else {
        return false;
    };
    let Ok(queue) = crate::app_runtime::infrastructure::bridge::operations::parse_input_queue(
        params.get("inputQueue"),
        turn_id,
    ) else {
        return false;
    };
    let Some(user_item) = params.get("userItem").and_then(Value::as_object) else {
        return false;
    };
    let user_content = crate::app_runtime::infrastructure::bridge::operations::parse_turn_content(
        user_item.get("content"),
        turn_id,
    );
    let user_attachments = user_content.as_ref().ok().and_then(|content| {
        crate::app_runtime::infrastructure::bridge::operations::parse_attachment_summaries(
            user_item.get("attachments"),
            content,
        )
        .ok()
    });
    let user_item_valid = exact_keys(
        user_item,
        &[
            "itemId",
            "createdAt",
            "turnId",
            "kind",
            "content",
            "attachments",
        ],
        &[],
    ) && valid_prefixed(user_item.get("itemId"), "item_", 101)
        && valid_timestamp(user_item.get("createdAt"))
        && user_item.get("turnId").and_then(Value::as_str) == Some(turn_id)
        && user_item.get("kind").and_then(Value::as_str) == Some("user_input")
        && user_content.is_ok_and(|content| content == input.content)
        && user_attachments.is_some_and(|attachments| attachments == input.attachments);
    user_item_valid
        && !queue
            .items
            .iter()
            .any(|remaining| remaining.input_id == input.input_id)
        && params
            .get("assistantSettlement")
            .is_none_or(valid_assistant_settlement)
}

/// 可选 Assistant 结算镜像 model-step 的公开字段，但不允许 Tool calls 混入队列消费原子事件。
fn valid_assistant_settlement(value: &Value) -> bool {
    value.as_object().is_some_and(|settlement| {
        exact_keys(
            settlement,
            &["messageId", "text", "modelRound"],
            &["usage", "reasoningSummary"],
        ) && valid_prefixed(settlement.get("messageId"), "item_", 101)
            && bounded_text(settlement.get("text"), 0, 1_048_576)
            && integer_in_range(settlement.get("modelRound"), 1, 128)
            && optional_usage(settlement.get("usage"))
            && usage_matches_model_round(settlement.get("usage"), settlement.get("modelRound"))
            && optional_bounded_text(settlement.get("reasoningSummary"), 0, 1_048_576)
    })
}

/// Mailbox 消费事件只接收非空 `thread_message` item 批次，并把每条消息绑定到当前 Turn。
fn valid_thread_message_items(value: Option<&Value>, turn_id: Option<&Value>) -> bool {
    let Some(turn_id) = turn_id.and_then(Value::as_str) else {
        return false;
    };
    value.and_then(Value::as_array).is_some_and(|items| {
        !items.is_empty()
            && items.len() <= 256
            && items.iter().all(|item| {
                item.as_object().is_some_and(|item| {
                    super::history_model::valid_thread_message_item_wire(item, Some(turn_id))
                })
            })
    })
}

/// 设计原因：Thread 标题通知不属于 Turn，因此不能复用要求 `turnId/threadRevision` 的
/// Turn 公共字段；这里严格镜像 JA-RPC v1 golden，允许 admission 已提交的 provisional、
/// 自动与人工标题刷新，同时拒绝旧 `profileId` 或未提交的标题进入 renderer。
fn validate_thread_metadata_event(
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if !valid_notification_common(params)
        || !exact_keys_with_common(
            params,
            &NOTIFICATION_COMMON_FIELDS,
            &[
                "workspaceId",
                "threadId",
                "revision",
                "title",
                "titleSource",
            ],
            &[],
        )
        || !valid_prefixed(params.get("workspaceId"), "ws_", 99)
        || !valid_prefixed(params.get("threadId"), "thr_", 99)
        || !non_negative_integer(params.get("revision"))
        || !bounded_text(params.get("title"), 1, 512)
        || !params
            .get("titleSource")
            .and_then(Value::as_str)
            .is_some_and(|source| matches!(source, "placeholder" | "auto" | "manual"))
    {
        return Err(invalid_projection());
    }
    Ok(())
}

/// 校验三类 Thread 级上下文压缩事件；显式 nullable turnId 允许手动压缩与自动 Turn
/// 复用同一时序模型，而 before/after 的状态相关约束阻止 UI 把 started/failed 误判为提交。
fn validate_context_event(
    method: &str,
    params: &serde_json::Map<String, Value>,
) -> Result<(), RuntimeCommandError> {
    if !valid_context_common(params) {
        return Err(invalid_projection());
    }
    let common_fields = [
        "serverInstanceId",
        "eventId",
        "sequence",
        "occurredAt",
        "generation",
        "workspaceId",
        "threadId",
        "turnId",
        "threadRevision",
    ];
    let base_fields = [
        "compactionId",
        "trigger",
        "sourceRevision",
        "inputTokensBefore",
        "inputTokensAfter",
        "strategyVersion",
    ];
    if !valid_prefixed(params.get("compactionId"), "cmp_", 100)
        || !params
            .get("trigger")
            .and_then(Value::as_str)
            .is_some_and(|trigger| matches!(trigger, "automatic" | "manual" | "overflow_recovery"))
        || !non_negative_integer(params.get("sourceRevision"))
        || params.get("strategyVersion").and_then(Value::as_str) != Some("ja-context-v1")
        || params
            .get("sourceRevision")
            .and_then(Value::as_u64)
            .zip(params.get("threadRevision").and_then(Value::as_u64))
            .is_none_or(|(source, current)| source > current)
    {
        return Err(invalid_projection());
    }
    let mut required = Vec::with_capacity(common_fields.len() + base_fields.len() + 2);
    required.extend_from_slice(&common_fields);
    required.extend_from_slice(&base_fields);
    let valid = match method {
        "context/compaction-started" => {
            exact_keys(params, &required, &[])
                && non_negative_integer(params.get("inputTokensBefore"))
                && params.get("inputTokensAfter").is_some_and(Value::is_null)
        }
        "context/compacted" => {
            required.push("checkpointId");
            exact_keys(params, &required, &[])
                && valid_prefixed(params.get("checkpointId"), "checkpoint_", 107)
                && non_negative_integer(params.get("inputTokensBefore"))
                && non_negative_integer(params.get("inputTokensAfter"))
                && params
                    .get("inputTokensBefore")
                    .and_then(Value::as_u64)
                    .zip(params.get("inputTokensAfter").and_then(Value::as_u64))
                    .is_some_and(|(before, after)| after < before)
        }
        "context/compaction-failed" => {
            required.push("errorCode");
            exact_keys(params, &required, &[])
                && params
                    .get("inputTokensBefore")
                    .is_some_and(|value| value.is_null() || non_negative_integer(Some(value)))
                && params.get("inputTokensAfter").is_some_and(Value::is_null)
                && valid_error_code(params.get("errorCode"))
        }
        _ => false,
    };
    valid.then_some(()).ok_or_else(invalid_projection)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 校验 lifecycle、configuration 与 Turn 事件共享的进程级 notification identity；sequence 和 generation 必须存在，以拒绝陈旧或无序数据。
fn valid_notification_common(params: &serde_json::Map<String, Value>) -> bool {
    valid_prefixed(params.get("serverInstanceId"), "srv_", 100)
        && valid_prefixed(params.get("eventId"), "evt_", 100)
        && positive_integer(params.get("sequence"))
        && valid_timestamp(params.get("occurredAt"))
        && positive_integer(params.get("generation"))
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 用前端 reducer 所需的完整 Turn identity 扩展进程级 fence，将 workspace 与 revision 校验集中在统一入口而非 method-specific 分支。
fn valid_turn_common(params: &serde_json::Map<String, Value>) -> bool {
    valid_notification_common(params)
        && valid_prefixed(params.get("workspaceId"), "ws_", 99)
        && valid_prefixed(params.get("threadId"), "thr_", 100)
        && valid_prefixed(params.get("turnId"), "turn_", 101)
        && non_negative_integer(params.get("threadRevision"))
}

/// Context 生命周期始终携带 turnId 字段，但手动压缩必须使用显式 null；自动与 overflow
/// 路径可携带冻结 Turn identity，缺失字段不作为 null 的兼容别名接受。
fn valid_context_common(params: &serde_json::Map<String, Value>) -> bool {
    valid_notification_common(params)
        && valid_prefixed(params.get("workspaceId"), "ws_", 99)
        && valid_prefixed(params.get("threadId"), "thr_", 100)
        && params
            .get("turnId")
            .is_some_and(|value| value.is_null() || valid_prefixed(Some(value), "turn_", 101))
        && non_negative_integer(params.get("threadRevision"))
}

/// 压缩失败只接受公共合同冻结的应用错误闭集，不允许未来任意大写文本绕过三端同步。
fn valid_error_code(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|code| {
        matches!(
            code,
            "THREAD_NOT_FOUND"
                | "CONFLICT"
                | "THREAD_BUSY"
                | "SUMMARY_FAILURE"
                | "CONTEXT_LIMIT"
                | "CANCELLED"
                | "INVALID_STATE"
        )
    })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 要求 schema 字段与命名完全一致，使 minor-version alias 也按 fail-closed 处理。
fn exact_keys(
    object: &serde_json::Map<String, Value>,
    required: &[&str],
    optional: &[&str],
) -> bool {
    required.iter().all(|key| object.contains_key(*key))
        && object
            .keys()
            .all(|key| required.contains(&key.as_str()) || optional.contains(&key.as_str()))
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 由共享 metadata 集合与 method-specific 字段构建封闭 schema，避免各 notification 变体的重复白名单独立漂移。
fn exact_keys_with_common(
    object: &serde_json::Map<String, Value>,
    common: &[&str],
    required: &[&str],
    optional: &[&str],
) -> bool {
    let mut fields = Vec::with_capacity(common.len() + required.len());
    fields.extend_from_slice(common);
    fields.extend_from_slice(required);
    exact_keys(object, &fields, optional)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 使用共享 ASCII grammar 校验带前缀协议 identity，避免各事件分支放宽格式。
fn valid_prefixed(value: Option<&Value>, prefix: &str, max: usize) -> bool {
    value.and_then(Value::as_str).is_some_and(|text| {
        text.starts_with(prefix)
            && text.len() > prefix.len()
            && text.len() <= max
            && text[prefix.len()..].chars().all(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.')
            })
    })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 校验 callId、toolName 等不带前缀的 Kernel identifier，并保持统一长度与字符边界。
fn valid_identifier(value: Option<&Value>, max: usize) -> bool {
    value.and_then(Value::as_str).is_some_and(|text| {
        !text.is_empty()
            && text.len() <= max
            && text.chars().all(|character| {
                character.is_ascii_alphanumeric() || matches!(character, '_' | '-' | '.' | ':')
            })
    })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 允许 identifier 缺省，但禁止显式 null 或空白 alias，避免多种缺失语义并存。
fn optional_identifier(value: Option<&Value>, max: usize) -> bool {
    value.is_none() || valid_identifier(value, max)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 对必填事件字符串应用相同有界文本规则，避免 method 分支出现不同限制。
fn bounded_text(value: Option<&Value>, min: usize, max: usize) -> bool {
    value
        .and_then(Value::as_str)
        .is_some_and(|text| (min..=max).contains(&text.chars().count()) && !text.contains('\0'))
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 允许可选有界文本字段，但拒绝 null 与控制字符，保持缺省语义唯一。
fn optional_bounded_text(value: Option<&Value>, min: usize, max: usize) -> bool {
    value.is_none() || bounded_text(value, min, max)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 仅接受冻结的小写 snake_case Turn 状态词汇，禁止兼容别名扩展状态空间。
fn valid_turn_state(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|state| {
        matches!(
            state,
            "queued"
                | "running"
                | "waiting_approval"
                | "suspended"
                | "completed"
                | "failed"
                | "cancelled"
        )
    })
}

/// Turn 状态转换只接受当前持久执行模型的边；`suspended` 不影响进程 lifecycle，也不能直达 running。
fn valid_turn_transition(from: Option<&Value>, to: Option<&Value>) -> bool {
    matches!(
        (from.and_then(Value::as_str), to.and_then(Value::as_str),),
        (
            Some("queued"),
            Some("running" | "suspended" | "completed" | "failed" | "cancelled")
        ) | (
            Some("running"),
            Some("waiting_approval" | "suspended" | "completed" | "failed" | "cancelled")
        ) | (
            Some("waiting_approval"),
            Some("running" | "suspended" | "completed" | "failed" | "cancelled")
        ) | (Some("suspended"), Some("queued" | "cancelled"))
    )
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 拒绝零 sequence 和 JavaScript 无法安全表示的数值，维持跨语言排序精度。
fn positive_integer(value: Option<&Value>) -> bool {
    integer_in_range(value, 1, MAX_SAFE_INTEGER)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 只接受在 JavaScript 中仍能精确表示的非负整数，防止投影后数值失真。
fn non_negative_integer(value: Option<&Value>) -> bool {
    integer_in_range(value, 0, MAX_SAFE_INTEGER)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 按显式封闭 schema 范围校验必填整数，避免调用方依赖隐式截断。
fn integer_in_range(value: Option<&Value>, minimum: u64, maximum: u64) -> bool {
    value
        .and_then(Value::as_u64)
        .is_some_and(|number| (minimum..=maximum).contains(&number))
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 校验可选的逐 round token accounting object，不推导或伪造 Java 未提交的 usage。
fn optional_usage(value: Option<&Value>) -> bool {
    value.is_none() || value.is_some_and(valid_usage)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 请求级 Usage 绑定唯一请求、用途和必填画像；UNKNOWN 只允许 Token 为空。
fn valid_usage(value: &Value) -> bool {
    let Some(usage) = value.as_object() else {
        return false;
    };
    if !exact_keys(
        usage,
        &[
            "requestId",
            "requestOrdinal",
            "modelRound",
            "purpose",
            "certainty",
            "profile",
            "inputTokens",
            "outputTokens",
            "totalTokens",
            "measuredAt",
        ],
        &[],
    ) || !valid_prefixed(usage.get("requestId"), "request_", 103)
        || !integer_in_range(usage.get("requestOrdinal"), 1, MAX_SAFE_INTEGER)
        || !integer_in_range(usage.get("modelRound"), 1, 128)
        || !matches!(
            usage.get("purpose").and_then(Value::as_str),
            Some("assistant" | "summary")
        )
        || !valid_timestamp(usage.get("measuredAt"))
    {
        return false;
    }
    if !usage
        .get("profile")
        .is_some_and(valid_provider_request_profile)
    {
        return false;
    }
    match usage.get("certainty").and_then(Value::as_str) {
        Some("known") => {
            let input = usage.get("inputTokens").and_then(Value::as_u64);
            let output = usage.get("outputTokens").and_then(Value::as_u64);
            let total = usage.get("totalTokens").and_then(Value::as_u64);
            input
                .zip(output)
                .and_then(|(left, right)| left.checked_add(right))
                .zip(total)
                .is_some_and(|(minimum, total)| total <= MAX_SAFE_INTEGER && total >= minimum)
        }
        Some("unknown") => ["inputTokens", "outputTokens", "totalTokens"]
            .iter()
            .all(|field| usage.get(*field).is_some_and(Value::is_null)),
        _ => false,
    }
}

/// 外层模型事件与嵌套 Usage 必须归属同一 round；缺失 Usage 仍是合法的无计量事件。
fn usage_matches_model_round(usage: Option<&Value>, model_round: Option<&Value>) -> bool {
    usage.is_none() || usage.and_then(|value| value.get("modelRound")) == model_round
}

/// 请求画像是一次真实调用的完整非敏感事实，任何缺项都不能由 WebView 当前设置补齐。
fn valid_provider_request_profile(value: &Value) -> bool {
    let Some(profile) = value.as_object() else {
        return false;
    };
    exact_keys(
        profile,
        &[
            "providerId",
            "modelId",
            "api",
            "upstreamModel",
            "requestedReasoning",
            "effectiveReasoning",
            "accessMode",
            "collaborationMode",
            "configGeneration",
            "promptRevision",
            "toolCatalogRevision",
            "contextWindowTokens",
            "maxOutputTokens",
        ],
        &[],
    ) && valid_prefixed(profile.get("providerId"), "provider_", 128)
        && valid_prefixed(profile.get("modelId"), "model_", 128)
        && matches!(
            profile.get("api").and_then(Value::as_str),
            Some("anthropic_messages" | "openai_responses" | "openai_chat_completions")
        )
        && bounded_text(profile.get("upstreamModel"), 1, 512)
        && ["requestedReasoning", "effectiveReasoning"]
            .iter()
            .all(|field| {
                profile.get(*field).is_some_and(|reasoning| {
                    reasoning.is_null()
                        || reasoning.as_str().is_some_and(|value| {
                            matches!(
                                value,
                                "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
                            )
                        })
                })
            })
        && matches!(
            profile.get("accessMode").and_then(Value::as_str),
            Some("approval_required" | "full_access")
        )
        && matches!(
            profile.get("collaborationMode").and_then(Value::as_str),
            Some("default" | "plan")
        )
        && profile
            .get("configGeneration")
            .and_then(Value::as_str)
            .is_some_and(|value| value.starts_with("cfg_") && value.len() <= 128)
        && valid_identifier(profile.get("promptRevision"), 256)
        && valid_identifier(profile.get("toolCatalogRevision"), 256)
        && integer_in_range(profile.get("contextWindowTokens"), 1, MAX_SAFE_INTEGER)
        && integer_in_range(profile.get("maxOutputTokens"), 1, MAX_SAFE_INTEGER)
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 要求非空且有界的 model-step Tool call 数组，并在私有 Java frame 复制到 WebView 前校验每条嵌套记录。
fn valid_tool_calls(value: Option<&Value>) -> bool {
    value.and_then(Value::as_array).is_some_and(|calls| {
        (1..=128).contains(&calls.len())
            && calls.iter().all(|call| {
                call.as_object().is_some_and(|call| {
                    exact_keys(
                        call,
                        &["callId", "toolName", "presentation", "ordinal"],
                        &[],
                    ) && valid_prefixed(call.get("callId"), "call_", 101)
                        && valid_identifier(call.get("toolName"), 256)
                        && valid_tool_presentation(call.get("presentation"))
                        && integer_in_range(call.get("ordinal"), 0, 1_023)
                })
            })
    })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 要求非空的已提交 Tool result batch，且不在 Rust-owned Review 与 Last-turn 状态之外保留第二份文件变更账本。
fn valid_tool_results(value: Option<&Value>) -> bool {
    value.and_then(Value::as_array).is_some_and(|results| {
        (1..=128).contains(&results.len()) && results.iter().all(valid_tool_result)
    })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 校验一条已提交 Tool result 及其可选稳定错误码，禁止内部诊断穿透。
fn valid_tool_result(value: &Value) -> bool {
    value.as_object().is_some_and(|result| {
        exact_keys(
            result,
            &["callId", "outcome", "presentation", "ordinal"],
            &["errorCode"],
        ) && valid_prefixed(result.get("callId"), "call_", 101)
            && result
                .get("outcome")
                .and_then(Value::as_str)
                .is_some_and(|outcome| matches!(outcome, "succeeded" | "failed" | "cancelled"))
            && valid_tool_presentation(result.get("presentation"))
            && integer_in_range(result.get("ordinal"), 0, 1_023)
            && optional_identifier(result.get("errorCode"), 256)
    })
}

/// Tool 展示 DTO 是 Java 安全投影的唯一 WebView 边界；Rust 再次执行闭集、大小、路径与
/// artifact identity 校验，确保 raw arguments/result 或绝对路径无法借事件透传。
fn valid_tool_presentation(value: Option<&Value>) -> bool {
    let Some(presentation) = value.and_then(Value::as_object) else {
        return false;
    };
    if !exact_keys(
        presentation,
        &["kind", "title", "status", "relativePaths", "truncated"],
        &[
            "inputPreview",
            "outputPreview",
            "command",
            "relativeCwd",
            "stdout",
            "stderr",
            "exitCode",
            "durationMs",
            "artifactId",
        ],
    ) || !presentation
        .get("kind")
        .and_then(Value::as_str)
        .is_some_and(|kind| matches!(kind, "read" | "edit" | "write" | "shell" | "mcp"))
        || !safe_title(presentation.get("title"))
        || !presentation
            .get("status")
            .and_then(Value::as_str)
            .is_some_and(|status| {
                matches!(
                    status,
                    "pending" | "running" | "waiting_approval" | "success" | "error" | "cancelled"
                )
            })
        || !optional_bounded_text(presentation.get("inputPreview"), 0, 32_768)
        || !optional_bounded_text(presentation.get("outputPreview"), 0, 32_768)
        || !optional_bounded_text(presentation.get("command"), 0, 32_768)
        || !optional_bounded_text(presentation.get("stdout"), 0, 32_768)
        || !optional_bounded_text(presentation.get("stderr"), 0, 32_768)
        || !optional_relative_path(presentation.get("relativeCwd"))
        || !optional_signed_integer(presentation.get("exitCode"), i32::MIN, i32::MAX)
        || !optional_non_negative_integer(presentation.get("durationMs"))
        || !presentation.get("truncated").is_some_and(Value::is_boolean)
        || !optional_prefixed(presentation.get("artifactId"), "artifact_", 128)
    {
        return false;
    }
    presentation
        .get("relativePaths")
        .and_then(Value::as_array)
        .is_some_and(|paths| {
            paths.len() <= 64
                && paths.iter().all(|path| valid_relative_path(Some(path)))
                && paths
                    .iter()
                    .enumerate()
                    .all(|(index, path)| !paths[..index].iter().any(|previous| previous == path))
        })
}

/// Tool title 禁止 CR/LF 与 NUL，避免事件文本改变卡片结构或日志边界。
fn safe_title(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|title| {
        (1..=512).contains(&title.chars().count())
            && !title
                .chars()
                .any(|character| matches!(character, '\0' | '\r' | '\n'))
    })
}

/// 工作区相对路径使用 `/` 分隔且拒绝盘符、绝对路径、控制字符与父级逃逸段。
fn valid_relative_path(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|path| {
        let bytes = path.as_bytes();
        let has_drive_prefix =
            bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
        !path.is_empty()
            && path.chars().count() <= 4_096
            && !path.starts_with('/')
            && !has_drive_prefix
            && !path.contains('\\')
            && !path.chars().any(char::is_control)
            && !path.split('/').any(|segment| segment == "..")
    })
}

/// 可选相对路径只允许缺省，不接受 null 作为第二种缺失语义。
fn optional_relative_path(value: Option<&Value>) -> bool {
    value.is_none() || valid_relative_path(value)
}

/// 可选协议 identity 只允许缺省，不接受空串或 null alias。
fn optional_prefixed(value: Option<&Value>, prefix: &str, max: usize) -> bool {
    value.is_none() || valid_prefixed(value, prefix, max)
}

/// 可选非负安全整数沿用 JSON 精度上限，拒绝浮点与负值。
fn optional_non_negative_integer(value: Option<&Value>) -> bool {
    value.is_none() || non_negative_integer(value)
}

/// Shell exit code 是有符号 32 位值；单独校验避免 `as_u64` 错拒负退出码。
fn optional_signed_integer(value: Option<&Value>, minimum: i32, maximum: i32) -> bool {
    value.is_none()
        || value
            .and_then(Value::as_i64)
            .is_some_and(|number| (i64::from(minimum)..=i64::from(maximum)).contains(&number))
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 校验 terminal commit 保留的可选最终 assistant message，并施加统一文本边界。
fn optional_terminal_message(value: Option<&Value>) -> bool {
    value.is_none()
        || value.and_then(Value::as_object).is_some_and(|message| {
            exact_keys(message, &["messageId", "text"], &[])
                && valid_prefixed(message.get("messageId"), "item_", 101)
                && bounded_text(message.get("text"), 0, 1_048_576)
        })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// Terminal 与其它请求结算复用同一完整 Usage，不另建宽松计量形状。
fn optional_terminal_usage(value: Option<&Value>) -> bool {
    optional_usage(value)
}

/// 终态 ChangeSet 使用 JA-RPC 1.0 的完整冻结形状，逐文件统计和总计必须互相证明。
fn valid_change_set(value: Option<&Value>) -> bool {
    let Some(change_set) = value.and_then(Value::as_object) else {
        return false;
    };
    if !exact_keys(
        change_set,
        &["state", "incompleteReasons", "files", "stats"],
        &["artifactId"],
    ) || !valid_change_integrity(change_set.get("state"), change_set.get("incompleteReasons"))
        || !optional_prefixed(change_set.get("artifactId"), "artifact_", 128)
    {
        return false;
    }
    let Some(files) = change_set.get("files").and_then(Value::as_array) else {
        return false;
    };
    files.len() <= 256
        && files.iter().all(valid_change_file)
        && valid_change_stats(change_set.get("stats"), Some(files))
}

/// 文件摘要精确镜像 Java 文本 tracker，拒绝 rename、oldPath 和二进制兼容分支。
fn valid_change_file(value: &Value) -> bool {
    let Some(file) = value.as_object() else {
        return false;
    };
    exact_keys(
        file,
        &[
            "path",
            "status",
            "additions",
            "deletions",
            "binary",
            "truncated",
        ],
        &[],
    ) && valid_relative_path(file.get("path"))
        && file
            .get("status")
            .and_then(Value::as_str)
            .is_some_and(|status| matches!(status, "added" | "modified" | "deleted"))
        && non_negative_integer(file.get("additions"))
        && non_negative_integer(file.get("deletions"))
        && file.get("binary").and_then(Value::as_bool) == Some(false)
        && file.get("truncated").is_some_and(Value::is_boolean)
}

/// 预览通知可只校验统计边界；终态携带文件列表时还必须逐项汇总完全一致。
fn valid_change_stats(value: Option<&Value>, files: Option<&[Value]>) -> bool {
    let Some(stats) = value.and_then(Value::as_object) else {
        return false;
    };
    if !exact_keys(
        stats,
        &[
            "files",
            "additions",
            "deletions",
            "binaryFiles",
            "truncated",
        ],
        &[],
    ) || !integer_in_range(stats.get("files"), 0, 256)
        || !non_negative_integer(stats.get("additions"))
        || !non_negative_integer(stats.get("deletions"))
        || !integer_in_range(stats.get("binaryFiles"), 0, 256)
        || !stats.get("truncated").is_some_and(Value::is_boolean)
        || stats.get("binaryFiles").and_then(Value::as_u64)
            > stats.get("files").and_then(Value::as_u64)
    {
        return false;
    }
    let Some(files) = files else {
        return true;
    };
    let additions = files.iter().try_fold(0_u64, |total, file| {
        total.checked_add(file.get("additions")?.as_u64()?)
    });
    let deletions = files.iter().try_fold(0_u64, |total, file| {
        total.checked_add(file.get("deletions")?.as_u64()?)
    });
    stats.get("files").and_then(Value::as_u64) == Some(files.len() as u64)
        && stats.get("additions").and_then(Value::as_u64) == additions
        && stats.get("deletions").and_then(Value::as_u64) == deletions
        && stats.get("binaryFiles").and_then(Value::as_u64)
            == Some(
                files
                    .iter()
                    .filter(|file| file.get("binary") == Some(&Value::Bool(true)))
                    .count() as u64,
            )
        && stats.get("truncated").and_then(Value::as_bool)
            == Some(
                files
                    .iter()
                    .any(|file| file.get("truncated") == Some(&Value::Bool(true))),
            )
}

/// 完整性二态和七项原因闭集必须同步成立，重复原因也视为损坏通知。
fn valid_change_integrity(state: Option<&Value>, reasons: Option<&Value>) -> bool {
    let Some(state) = state.and_then(Value::as_str) else {
        return false;
    };
    let Some(reasons) = reasons.and_then(Value::as_array) else {
        return false;
    };
    reasons.len() <= 7
        && reasons.iter().enumerate().all(|(index, reason)| {
            reason.as_str().is_some_and(valid_change_incomplete_reason)
                && !reasons[..index].iter().any(|previous| previous == reason)
        })
        && matches!(
            (state, reasons.is_empty()),
            ("complete", true) | ("partial", false)
        )
}

/// 原因词汇保持协议闭集，未知扩展必须等 Rust 与 React 显式升级后再接收。
fn valid_change_incomplete_reason(value: &str) -> bool {
    matches!(
        value,
        "unknown_mutator"
            | "mutation_chain_broken"
            | "outside_workspace"
            | "limit_exceeded"
            | "capture_failed"
            | "commit_unconfirmed"
            | "recovery_boundary"
    )
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 强制执行依赖 terminal-state 的字段规则，使 completed、failed 或 cancelled 事务不能投影矛盾的恢复数据。
fn valid_terminal_condition(params: &serde_json::Map<String, Value>) -> bool {
    match params.get("state").and_then(Value::as_str) {
        Some("completed") => {
            params.contains_key("finalMessage")
                && !params.contains_key("errorCode")
                && !params.contains_key("errorMessage")
        }
        Some("failed") => params.contains_key("errorCode") && params.contains_key("errorMessage"),
        Some("cancelled") => {
            !params.contains_key("errorCode") && !params.contains_key("errorMessage")
        }
        _ => false,
    }
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 执行有界 RFC3339 形态校验，但不引入第二套 clock parser，避免时间语义分叉。
fn valid_timestamp(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|timestamp| {
        !timestamp.is_empty()
            && timestamp.len() <= 64
            && timestamp.contains('T')
            && (timestamp.ends_with('Z')
                || timestamp
                    .as_bytes()
                    .get(10..)
                    .is_some_and(|tail| tail.contains(&b'+') || tail.contains(&b'-')))
            && !timestamp.contains('\0')
    })
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 对未知 revision 与敏感字段统一返回不含 payload 的错误，避免通过错误差异泄漏内容。
fn invalid_projection() -> RuntimeCommandError {
    RuntimeCommandError {
        code: "SENSITIVE_EVENT_BLOCKED",
        message: "runtime event contains protected or unsupported data",
        retryable: false,
    }
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 发送一条 host status；每个 `ready` 投影都要求已校验 generation challenge，包括规范化为 wire-ready 的 Busy 状态。
pub(crate) fn emit_status(
    sink: &EventSink,
    status: RuntimeStatusKind,
    generation: u64,
    server_instance_id: Option<&str>,
    reason: &str,
    ready_token: Option<&str>,
) -> Result<(), RuntimeCommandError> {
    let server_instance_id = server_instance_id.unwrap_or("srv_host");
    let status_name = status.protocol_name();
    if status_name == "ready" {
        if !ready_token.is_some_and(valid_ready_token) {
            return Err(invalid_projection());
        }
    } else if ready_token.is_some() {
        return Err(invalid_projection());
    }
    let mut params = serde_json::Map::from_iter([
        (
            "serverInstanceId".to_owned(),
            Value::String(server_instance_id.to_owned()),
        ),
        (
            "eventId".to_owned(),
            Value::String(format!("{STATUS_EVENT_PREFIX}{generation}_{reason}")),
        ),
        ("occurredAt".to_owned(), Value::String(now_timestamp())),
        ("status".to_owned(), Value::String(status_name.to_owned())),
        ("features".to_owned(), json!(RUNTIME_STATUS_FEATURES)),
        ("reason".to_owned(), Value::String(reason.to_owned())),
    ]);
    if generation > 0 {
        params.insert("generation".to_owned(), Value::from(generation));
    }
    let value = json!({"jsonrpc": "2.0", "method": "runtime/status-changed", "params": params});
    sink(value).map_err(|_| RuntimeCommandError::event_delivery())
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 使用无依赖 UTC formatter，使 lifecycle 事件保持有界且确定，同时避免引入第二套时间日期抽象。
pub(super) fn now_timestamp() -> String {
    let elapsed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let days = (elapsed.as_secs() / 86_400) as i64;
    let seconds = elapsed.as_secs() % 86_400;
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        seconds / 3_600,
        (seconds / 60) % 60,
        seconds % 60,
        elapsed.subsec_millis()
    )
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 将 Unix days 转为 UTC calendar 字段而不新增日期 crate，维持投影层依赖边界。
fn civil_from_days(days_since_epoch: i64) -> (i64, i64, i64) {
    let z = days_since_epoch + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let month_part = (5 * doy + 2) / 153;
    let day = doy - (153 * month_part + 2) / 5 + 1;
    let month = month_part + if month_part < 10 { 3 } else { -9 };
    let year = year + if month <= 2 { 1 } else { 0 };
    (year, month, day)
}
