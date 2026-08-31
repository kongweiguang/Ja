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
/// 使用所有 v2 notification 共用的进程级顺序 metadata 校验唯一 runtime lifecycle 事件，避免建立第二套时序规则。
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
                &["status"],
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
            }) || !valid_runtime_status(
                status.unwrap_or_default(),
                params.get("reason"),
                params.get("readyToken"),
            ) {
                return Err(invalid_projection());
            }
        }
        _ => return Err(invalid_projection()),
    }
    Ok(())
}

/// 设计原因：该函数先完成身份与大小校验再脱敏投影，防止跨 generation 或私密字段进入 WebView。
/// 强制执行 v2 lifecycle status/reason/token tuple，使失败 shutdown 不会伪装成干净停止，并阻止 ready challenge 跨状态变体出现。
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
    if method == "thread/metadata-changed" {
        return validate_thread_metadata_event(params);
    }
    if matches!(
        method,
        "context/compaction-started" | "context/compacted" | "context/compaction-failed"
    ) {
        return validate_context_event(method, params);
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
                || !valid_tool_calls(params.get("toolCalls"))
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
        "turn/terminal" => {
            if !exact(
                &["state", "summary"],
                &["finalMessage", "usage", "errorCode", "errorMessage"],
            ) || !bounded_text(params.get("summary"), 0, 1_048_576)
                || !optional_terminal_message(params.get("finalMessage"))
                || !optional_terminal_usage(params.get("usage"))
                || !optional_identifier(params.get("errorCode"), 256)
                || !optional_bounded_text(params.get("errorMessage"), 0, 1_048_576)
                || !valid_terminal_condition(params)
            {
                return Err(invalid_projection());
            }
        }
        _ => return Err(invalid_projection()),
    }
    Ok(())
}

/// 设计原因：Thread 标题通知不属于 Turn，因此不能复用要求 `turnId/threadRevision` 的
/// Turn 公共字段；这里严格镜像 JA-RPC v2 golden，允许 admission 已提交的 provisional、
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
        || params.get("strategyVersion").and_then(Value::as_str) != Some("ja-context-v3")
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
                | "TOKEN_COUNT_UNAVAILABLE"
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
            "queued" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled"
        )
    })
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
/// 要求冻结 v1 schema 中三个精确非负 usage counter，不接受别名或额外字段。
fn valid_usage(value: &Value) -> bool {
    value.as_object().is_some_and(|usage| {
        exact_keys(usage, &["inputTokens", "outputTokens", "totalTokens"], &[])
            && non_negative_integer(usage.get("inputTokens"))
            && non_negative_integer(usage.get("outputTokens"))
            && non_negative_integer(usage.get("totalTokens"))
    })
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
/// 校验可选 terminal usage，包括拥有该 usage 的 model round，保持归属事实完整。
fn optional_terminal_usage(value: Option<&Value>) -> bool {
    value.is_none()
        || value.and_then(Value::as_object).is_some_and(|usage| {
            exact_keys(
                usage,
                &["modelRound", "inputTokens", "outputTokens", "totalTokens"],
                &[],
            ) && integer_in_range(usage.get("modelRound"), 1, 128)
                && non_negative_integer(usage.get("inputTokens"))
                && non_negative_integer(usage.get("outputTokens"))
                && non_negative_integer(usage.get("totalTokens"))
        })
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
