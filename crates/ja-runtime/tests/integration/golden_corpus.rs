// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

#![allow(dead_code, unused_imports)]

#[path = "../../src/lib.rs"]
mod production;

pub(crate) use production::app_server_process;

use crate::app_server_process::protocol::{decode_frame, valid_protocol_timestamp};
use crate::app_server_process::{
    AttachmentPreviewCloseResult, AttachmentPreviewOpenResult, AttachmentPreviewReadResult, Limits,
    TurnChangeSetReadResult,
};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

const REQUEST_METHODS: &[&str] = &[
    "runtime/initialize",
    "runtime/health",
    "runtime/shutdown",
    "workspace/open",
    "workspace/open-general",
    "workspace/list",
    "workspace/path/search",
    "workspace/set-trust",
    "workspace/unregister",
    "thread/create",
    "thread/list",
    "thread/search",
    "thread/read",
    "thread/rename",
    "thread/pin",
    "thread/seen",
    "thread/preferences/update",
    "thread/archive",
    "thread/restore",
    "thread/delete",
    "thread/compact",
    "interaction/read",
    "interaction/observe",
    "interaction/unobserve",
    "interaction/draft/save",
    "interaction/respond",
    "interaction/cancel",
    "goal/read",
    "goal/events/read",
    "goal/observe",
    "goal/unobserve",
    "plan/read",
    "plan/revisions/list",
    "plan/current/read",
    "plan/events/read",
    "plan/observe",
    "plan/unobserve",
    "plan/evidence/list",
    "goal/evidence/list",
    "goal/create",
    "goal/plan/attach",
    "goal/plan/detach",
    "goal/pause",
    "goal/resume",
    "goal/stop",
    "plan/create",
    "plan/draft/save",
    "plan/draft/discard",
    "plan/propose",
    "plan/execute",
    "plan/reject",
    "plan/pause",
    "plan/resume",
    "plan/stop",
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
    "attachment/import",
    "attachment/discard",
    "attachment/preview/open",
    "attachment/preview/read",
    "attachment/preview/close",
    "turn/start",
    "turn/resume",
    "turn/cancel",
    "turn/input/enqueue",
    "turn/input/prioritize",
    "turn/input/update",
    "turn/input/delete",
    "turn/change-set/read",
    "approval/respond",
    "configuration/read",
    "configuration/patch",
    "configuration/replace",
    "configuration/reset",
    "credential/set",
    "credential/delete",
    "skill/list",
    "mcp/list",
    "mcp/test",
    "model/test",
    "mcp/list-tools",
    "tool/artifact/read",
];
const EVENT_METHODS: &[&str] = &[
    "runtime/status-changed",
    "runtime/initialized",
    "turn/state-changed",
    "turn/input-queue-changed",
    "turn/input-consumed",
    "turn/messages_received",
    "assistant/model-step-committed",
    "assistant/text-delta",
    "assistant/reasoning-summary-delta",
    "tool/started",
    "tool/batch-committed",
    "approval/requested",
    "approval/resolved",
    "context/compaction-started",
    "context/compacted",
    "context/compaction-failed",
    "workspace/dirty",
    "turn/terminal",
    "thread/metadata-changed",
    "configuration/changed",
    "task/activity",
    "task/progress",
    "task/mailbox-changed",
    "goal/changed",
    "goal/activity",
    "interaction/changed",
    "plan/changed",
];
const CAPABILITY_EVENT_METHODS: &[&str] = &[
    "runtime/status-changed",
    "turn/state-changed",
    "turn/input-queue-changed",
    "turn/input-consumed",
    "turn/messages_received",
    "assistant/model-step-committed",
    "assistant/text-delta",
    "assistant/reasoning-summary-delta",
    "tool/started",
    "tool/batch-committed",
    "approval/requested",
    "approval/resolved",
    "context/compaction-started",
    "context/compacted",
    "context/compaction-failed",
    "workspace/dirty",
    "turn/terminal",
    "thread/metadata-changed",
    "configuration/changed",
    "task/activity",
    "task/progress",
    "task/mailbox-changed",
    "goal/changed",
    "goal/activity",
    "interaction/changed",
    "plan/changed",
];

/// 语料测试从生产协商对象取得帧上限，避免为测试重新开放已删除的常量 façade。
fn max_frame_bytes() -> usize {
    Limits::default().max_frame_bytes
}

/// 使用生产 Codec 解析每个正向 Frame，并固定精确 Kernel Identity，避免测试使用宽松替代解析器。
#[test]
fn consumes_every_positive_frame_and_pins_kernel_identity() {
    let mut frames = 0usize;
    let mut kernel_identity = false;
    for file in corpus_files(false) {
        let source = fs::read(&file).expect("positive corpus file must be readable");
        assert!(!contains_unsupported_vocabulary(&source));
        let mut pending_methods = HashMap::new();
        for (document_index, document) in documents(&file, &source).into_iter().enumerate() {
            let value: Value =
                serde_json::from_slice(&document).expect("positive frame must remain strict JSON");
            decode_frame(&transport_frame(&document), max_frame_bytes()).unwrap_or_else(|error| {
                panic!(
                    "positive frame must pass the production codec: {}:{}: {error:?}",
                    file.display(),
                    document_index + 1
                )
            });
            validate_correlated_contract(&value, &mut pending_methods).unwrap_or_else(|error| {
                panic!(
                    "positive frame must satisfy the v1 boundary: {}:{}: {error}",
                    file.display(),
                    document_index + 1
                )
            });
            if value.pointer("/id").and_then(Value::as_str) == Some("c:init") {
                kernel_identity = value
                    .pointer("/result/runtime/engine")
                    .and_then(Value::as_str)
                    == Some("ja-kernel")
                    && value
                        .pointer("/result/runtime/engineVersion")
                        .and_then(Value::as_str)
                        .is_some_and(|version| !version.is_empty());
            }
            frames += 1;
        }
    }
    assert!(
        frames > 0,
        "positive corpus must contain at least one frame"
    );
    assert!(kernel_identity);
}

/// 要求每个负向 Frame 在严格解码或封闭 Method/Event 合同校验中失败，防止非法 Fixture 被静默接纳。
#[test]
fn rejects_every_negative_frame() {
    let mut frames = 0usize;
    for file in corpus_files(true) {
        let source = fs::read(&file).expect("negative corpus file must be readable");
        let correlated = file
            .components()
            .any(|component| component.as_os_str() == "correlated");
        let mut pending_methods = HashMap::new();
        let mut correlated_rejected = false;
        for (document_index, document) in documents(&file, &source).into_iter().enumerate() {
            let rejected = match decode_frame(&transport_frame(&document), max_frame_bytes()) {
                Err(_) => true,
                Ok(_) => serde_json::from_slice::<Value>(&document).map_or(true, |value| {
                    validate_correlated_contract(&value, &mut pending_methods).is_err()
                }),
            };
            if correlated {
                correlated_rejected |= rejected;
                frames += 1;
                continue;
            }
            assert!(
                rejected,
                "negative frame reached the Rust consumer boundary: {}:{}",
                file.display(),
                document_index + 1
            );
            frames += 1;
        }
        if correlated {
            assert!(
                correlated_rejected,
                "negative correlated corpus never violated its request/response contract: {}",
                file.display()
            );
        }
    }
    assert!(
        frames > 0,
        "negative corpus must contain at least one frame"
    );
}

/// 动态消费机器错误目录，证明每个 tuple 可解码且任一 code/errorCode/category/retryable
/// 漂移都会被 production codec 拒绝；测试不复制条目数或 digest。
#[test]
fn consumes_frozen_error_catalog_without_aliases() {
    let catalog_path = golden_root()
        .parent()
        .expect("contracts root")
        .parent()
        .expect("repository contracts directory")
        .join("ja-rpc")
        .join("v1")
        .join("error-catalog.json");
    let catalog: Value =
        serde_json::from_slice(&fs::read(&catalog_path).expect("error catalog must be readable"))
            .expect("error catalog must be JSON");
    let entries = catalog
        .get("errors")
        .and_then(Value::as_array)
        .filter(|entries| !entries.is_empty())
        .expect("error catalog must contain entries");
    let mut codes = HashSet::with_capacity(entries.len());
    let mut error_codes = HashSet::with_capacity(entries.len());
    for (index, entry) in entries.iter().enumerate() {
        let code = entry.get("code").and_then(Value::as_i64).expect("code");
        let error_code = entry
            .get("errorCode")
            .and_then(Value::as_str)
            .expect("errorCode");
        let category = entry
            .get("category")
            .and_then(Value::as_str)
            .expect("category");
        let retryable = entry
            .get("retryable")
            .and_then(Value::as_bool)
            .expect("retryable");
        assert!(codes.insert(code), "duplicate catalog code: {code}");
        assert!(
            error_codes.insert(error_code),
            "duplicate catalog errorCode: {error_code}"
        );
        let mut frame = serde_json::json!({
            "jsonrpc": "2.0",
            "id": format!("c:catalog-{index}"),
            "error": {
                "code": code,
                "message": "catalog failure",
                "data": {
                    "errorCode": error_code,
                    "category": category,
                    "retryable": retryable,
                    "errorId": format!("err_{index:032x}")
                }
            }
        });
        if retryable {
            frame["error"]["data"]["retryAfterMs"] = serde_json::json!(250);
        }
        let encoded = serde_json::to_vec(&frame).expect("catalog frame encodes");
        let decoded = decode_frame(&transport_frame(&encoded), max_frame_bytes())
            .expect("catalog tuple must pass production codec");
        assert_eq!(
            decoded.error().expect("error response").data(),
            frame.pointer("/error/data").expect("error data")
        );

        let mut wrong_retryable = frame;
        *wrong_retryable
            .pointer_mut("/error/data/retryable")
            .expect("retryable path") = Value::Bool(!retryable);
        let encoded = serde_json::to_vec(&wrong_retryable).expect("mutation encodes");
        assert!(decode_frame(&transport_frame(&encoded), max_frame_bytes()).is_err());
    }
}

/// 解码显式启用的 Java Smoke Capture，用于诊断 Transport 漂移，但不把环境采样保留到 Git。
#[test]
#[ignore = "requires JA_KERNEL_FRAME_CAPTURE"]
fn decodes_external_java_capture() {
    let path = PathBuf::from(
        env::var_os("JA_KERNEL_FRAME_CAPTURE").expect("JA_KERNEL_FRAME_CAPTURE must be set"),
    );
    let source = fs::read(&path).expect("Java capture must be readable");
    for (index, document) in documents(&path, &source).into_iter().enumerate() {
        decode_frame(&transport_frame(&document), max_frame_bytes())
            .unwrap_or_else(|error| panic!("capture frame {index} failed: {error:?}"));
    }
}

/// 重新附加生产 Streaming Decoder 要求的 LF 分隔符，确保测试输入与真实 Transport 一致。
fn transport_frame(document: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(document.len() + 1);
    frame.extend_from_slice(document);
    frame.push(b'\n');
    frame
}

/// 优先从 Gate 环境定位 v1 共享 Corpus，否则从 Crate 目录向上查找，避免测试依赖当前工作目录。
fn golden_root() -> PathBuf {
    if let Some(configured) = env::var_os("JA_GOLDEN_PATH") {
        let configured = PathBuf::from(configured);
        return if configured.join("v1").is_dir() {
            configured.join("v1")
        } else {
            configured
        };
    }
    let mut current = env::current_dir().expect("current directory must be available");
    loop {
        let candidate = current.join("contracts").join("golden").join("v1");
        if candidate.is_dir() {
            return candidate;
        }
        assert!(current.pop(), "golden corpus is unavailable");
    }
}

/// 确定性选择 JSON 输入，并让负向 Fixture 保持独立 Pass，避免正负样本次序影响诊断。
fn corpus_files(invalid: bool) -> Vec<PathBuf> {
    fn visit(root: &Path, directory: &Path, invalid: bool, output: &mut Vec<PathBuf>) {
        for entry in fs::read_dir(directory).expect("corpus directory must be readable") {
            let path = entry.expect("corpus entry must be readable").path();
            if path.is_dir() {
                visit(root, &path, invalid, output);
                continue;
            }
            let supported = matches!(
                path.extension().and_then(|value| value.to_str()),
                Some("json" | "jsonl")
            );
            let is_invalid = path
                .strip_prefix(root)
                .expect("corpus path must remain beneath its root")
                .components()
                .any(|component| component.as_os_str() == "invalid");
            if supported && is_invalid == invalid {
                output.push(path);
            }
        }
    }

    let root = golden_root();
    let mut output = Vec::new();
    visit(&root, &root, invalid, &mut output);
    output.sort_by_key(|path| {
        path.strip_prefix(&root)
            .expect("corpus path must remain beneath its root")
            .to_path_buf()
    });
    output
}

/// 除 Transport 行终止符外保留每个 JSONL Document 的原始字节序列，避免 Fixture 被重新序列化改写。
fn documents(path: &Path, source: &[u8]) -> Vec<Vec<u8>> {
    if path.extension().and_then(|value| value.to_str()) == Some("json") {
        return vec![source.to_vec()];
    }
    source
        .split(|byte| *byte == b'\n')
        .filter_map(|line| {
            let line = line.strip_suffix(b"\r").unwrap_or(line);
            (!line.is_empty()).then(|| line.to_vec())
        })
        .collect()
}

/// 补充通用 framing codec 有意不承担的 v1 方法、事件与响应方向闭集。
fn validate_contract(value: &Value) -> Result<(), &'static str> {
    let method = value.get("method").and_then(Value::as_str);
    let allow_credential_secret = method == Some("credential/set")
        && value.get("id").is_some()
        && value
            .pointer("/params/secret")
            .and_then(Value::as_str)
            .is_some_and(|secret| !secret.is_empty());
    let allow_preview_authorization =
        method == Some("attachment/preview/open") && value.get("id").is_some();
    if contains_forbidden_secret_key(value, allow_credential_secret, allow_preview_authorization) {
        return Err("secret-shaped field crossed the Rust consumer boundary");
    }
    if let Some(method) = value.get("method").and_then(Value::as_str) {
        if value.get("id").is_some() {
            let id = value
                .get("id")
                .and_then(Value::as_str)
                .ok_or("request identity is invalid")?;
            let params = value.get("params").ok_or("request params are missing")?;
            if !id.starts_with("c:") {
                return Err("request identity is outside the client namespace");
            }
            return validate_request(method, params);
        }
        return validate_notification(
            method,
            value.get("params").ok_or("event params are missing")?,
        );
    }
    let id = value
        .get("id")
        .and_then(Value::as_str)
        .ok_or("response identity is invalid")?;
    if (value.get("result").is_some() == value.get("error").is_some()) || !id.starts_with("c:") {
        return Err("response role is invalid");
    }
    if value
        .pointer("/error/data/errorCode")
        .and_then(Value::as_str)
        .is_some_and(|code| {
            matches!(
                code,
                "INVALID_INPUT"
                    | "WORKSPACE_ESCAPE"
                    | "FILE_TOO_LARGE"
                    | "STALE_CONTENT"
                    | "ATOMIC_UNSUPPORTED"
                    | "HOST_BUSY"
                    | "IO_ERROR"
            )
        })
    {
        return Err("host error used client response identity");
    }
    if let Some(result) = value.get("result") {
        validate_response_result(result)?;
    }
    Ok(())
}

/// 关联同一 fixture 文件中的 client request/response，使 Resume 的 queued 常量和方法专属结果可验证。
fn validate_correlated_contract(
    value: &Value,
    pending_methods: &mut HashMap<String, String>,
) -> Result<(), &'static str> {
    validate_contract(value)?;
    let Some(id) = value.get("id").and_then(Value::as_str) else {
        return Ok(());
    };
    if let Some(method) = value.get("method").and_then(Value::as_str) {
        pending_methods.insert(id.to_owned(), method.to_owned());
        return Ok(());
    }
    let Some(method) = pending_methods.remove(id) else {
        return Ok(());
    };
    if method == "turn/resume"
        && value.get("result").is_some()
        && value
            .get("result")
            .and_then(|result| result.get("queued"))
            .and_then(Value::as_bool)
            != Some(true)
    {
        return Err("turn resume result is not queued");
    }
    if let Some(result) = value.get("result") {
        match method.as_str() {
            "thread/list" => validate_thread_list_result(result)?,
            "thread/search" => validate_thread_page_result(result)?,
            "thread/create"
            | "thread/rename"
            | "thread/preferences/update"
            | "thread/pin"
            | "thread/seen"
            | "thread/archive"
            | "thread/restore" => {
                validate_thread_result(result)?;
                if method == "thread/seen"
                    && result.get("latestTurnSeen").and_then(Value::as_bool) != Some(true)
                {
                    return Err("thread seen result did not advance the durable boundary");
                }
            }
            "workspace/path/search" => validate_workspace_path_search_result(result)?,
            "mcp/list" => validate_mcp_page_result(result)?,
            "mcp/test" => validate_mcp_test_result(result)?,
            "task/create"
            | "task/list"
            | "task/read"
            | "task/observe"
            | "task/unobserve"
            | "task/seen"
            | "thread/message/send"
            | "task/followup"
            | "task/cancel"
            | "task/tree/delete"
            | "task/close" => validate_task_result(&method, result)?,
            "goal/read" | "goal/create" | "goal/plan/attach" | "goal/plan/detach"
            | "goal/pause" | "goal/resume" | "goal/stop" => {
                validate_goal_projection_result(result)?
            }
            "plan/read" | "plan/create" | "plan/draft/save" | "plan/draft/discard"
            | "plan/propose" | "plan/execute" | "plan/reject" | "plan/pause" | "plan/resume"
            | "plan/stop" => validate_plan_projection_result(result)?,
            "plan/observe" => validate_plan_observe_result(result)?,
            "interaction/read"
            | "interaction/draft/save"
            | "interaction/respond"
            | "interaction/cancel" => validate_interaction_projection_result(result, false)?,
            "interaction/observe" => {
                validate_interaction_projection_result(result, true)?;
            }
            "plan/current/read" => validate_current_plan_result(result)?,
            "plan/events/read" => validate_plan_events_result(result)?,
            "plan/evidence/list" => validate_plan_evidence_result(result)?,
            _ => {}
        }
    }
    Ok(())
}

/// Golden Consumer 复核 MCP 列表的脱敏摘要与 configured 状态，锁定 Java/TS/Rust 共同投影。
fn validate_mcp_page_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(result, &["items", "nextCursor"])?;
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("MCP page items are invalid")?;
    items.iter().try_for_each(validate_mcp_projection)?;
    if !result
        .get("nextCursor")
        .is_some_and(|cursor| cursor.is_null() || bounded_string(Some(cursor), 1, 512))
    {
        return Err("MCP page cursor is invalid");
    }
    Ok(())
}

/// Golden Consumer 复核 MCP probe 的完整脱敏 descriptor，特别覆盖 Java 的 available 状态。
fn validate_mcp_test_result(result: &Value) -> Result<(), &'static str> {
    validate_mcp_descriptor(result, &["healthy", "available", "degraded", "unavailable"])
}

/// MCP list/test 共享 descriptor 字段，但各自的状态闭集保持显式，避免启用事实冒充 probe 事实。
fn validate_mcp_descriptor(result: &Value, statuses: &[&str]) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &["mcpId", "name", "transport", "status", "toolCount"],
    )?;
    if !valid_prefixed_id(result.get("mcpId"), "mcp_")
        || !bounded_string(result.get("name"), 1, 512)
        || result
            .get("name")
            .and_then(Value::as_str)
            .is_some_and(|name| name.chars().any(char::is_control))
        || !matches!(
            result.get("transport").and_then(Value::as_str),
            Some("stdio" | "streamable_http")
        )
        || !result
            .get("status")
            .and_then(Value::as_str)
            .is_some_and(|status| statuses.contains(&status))
        || !integer_in_bounds(result.get("toolCount"), 0, 2_000)
    {
        return Err("MCP descriptor is invalid");
    }
    Ok(())
}

/// MCP list descriptor 允许 configured，表示定义已保存但尚未完成一次 probe。
fn validate_mcp_projection(result: &Value) -> Result<(), &'static str> {
    validate_mcp_descriptor(
        result,
        &[
            "healthy",
            "degraded",
            "unavailable",
            "disabled",
            "configured",
        ],
    )
}

/// Goal 投影独立校验 evaluator 绑定，防止不存在的 criterion 被客户端误认为有效验收结论。
fn validate_goal_projection_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(result, &["goal", "eventSequence"])?;
    let goal = result.get("goal").ok_or("goal projection is missing")?;
    let goal_id = goal
        .get("goalId")
        .and_then(Value::as_str)
        .ok_or("goal identity is missing")?;
    let criterion_ids = goal
        .get("acceptanceCriteria")
        .and_then(Value::as_array)
        .ok_or("goal criteria are missing")?
        .iter()
        .filter_map(|criterion| criterion.get("criterionId").and_then(Value::as_str))
        .collect::<HashSet<_>>();
    if let Some(evaluation) = goal
        .get("latestEvaluation")
        .filter(|value| !value.is_null())
    {
        if evaluation.get("goalId").and_then(Value::as_str) != Some(goal_id) {
            return Err("goal evaluation owner is invalid");
        }
        let criteria = evaluation
            .get("criteria")
            .and_then(Value::as_array)
            .ok_or("goal evaluation criteria are missing")?;
        if criteria.iter().any(|criterion| {
            criterion
                .get("criterionId")
                .and_then(Value::as_str)
                .is_none_or(|id| !criterion_ids.contains(id))
        }) {
            return Err("goal evaluation criterion is dangling");
        }
    }
    Ok(())
}

/// Plan 投影要求 active revision、冻结 revision 与 approval 三方绑定，避免批准状态指向不可见版本。
fn validate_plan_projection_result(result: &Value) -> Result<(), &'static str> {
    let mut allowed = vec![
        "plan",
        "draft",
        "currentRevision",
        "approval",
        "stepExecutions",
        "eventSequence",
    ];
    if result.get("observationId").is_some() {
        allowed.push("observationId");
    }
    ensure_object_keys(result, &allowed)?;
    let plan = result.get("plan").ok_or("plan projection is missing")?;
    let plan_id = plan
        .get("planId")
        .and_then(Value::as_str)
        .ok_or("plan identity is missing")?;
    let active_revision_id = plan.get("activePlanRevisionId").and_then(Value::as_str);
    let current_revision = result
        .get("currentRevision")
        .filter(|value| !value.is_null());
    if active_revision_id.is_some()
        && current_revision
            .and_then(|revision| revision.get("planRevisionId"))
            .and_then(Value::as_str)
            != active_revision_id
    {
        return Err("active plan revision is not projected");
    }
    if current_revision
        .is_some_and(|revision| revision.get("planId").and_then(Value::as_str) != Some(plan_id))
    {
        return Err("plan revision owner is invalid");
    }
    if let Some(approval) = result.get("approval").filter(|value| !value.is_null()) {
        let revision = current_revision.ok_or("approved revision is missing")?;
        if approval.get("planId") != plan.get("planId")
            || approval.get("planRevisionId") != revision.get("planRevisionId")
            || approval.get("planHash") != revision.get("planHash")
        {
            return Err("plan approval binding is invalid");
        }
    }
    Ok(())
}

/// Plan observe 必须同时返回连接级观察身份，避免只读快照被误当成已订阅状态。
fn validate_plan_observe_result(result: &Value) -> Result<(), &'static str> {
    validate_plan_projection_result(result)?;
    if !valid_prefixed_id(result.get("observationId"), "observe_") {
        return Err("plan observation identity is invalid");
    }
    Ok(())
}

/// Interaction 快照只暴露问答聚合的稳定字段；request/draft 的 owner 必须回指同一 Thread。
fn validate_interaction_projection_result(
    result: &Value,
    require_observation_id: bool,
) -> Result<(), &'static str> {
    let mut allowed = vec![
        "threadId",
        "eventSequence",
        "request",
        "draft",
        "resumeState",
    ];
    if require_observation_id {
        allowed.push("observationId");
    }
    ensure_object_keys(result, &allowed)?;
    if !valid_prefixed_id(result.get("threadId"), "thr_")
        || !integer_in_bounds(result.get("eventSequence"), 0, 9_007_199_254_740_991)
        || (require_observation_id && !valid_prefixed_id(result.get("observationId"), "observe_"))
    {
        return Err("interaction snapshot identity is invalid");
    }
    if let Some(request) = result.get("request").filter(|value| !value.is_null()) {
        validate_interaction_request(request, result.get("threadId"))?;
    }
    if let Some(draft) = result.get("draft").filter(|value| !value.is_null()) {
        validate_interaction_draft(draft, result.get("threadId"))?;
    }
    if result.get("resumeState").is_some_and(|value| {
        !matches!(
            value.as_str(),
            Some(
                "none"
                    | "waiting_for_answer"
                    | "waiting_to_resume"
                    | "resuming"
                    | "settled"
                    | "closed"
            )
        )
    }) {
        return Err("interaction resume state is invalid");
    }
    Ok(())
}

/// Interaction request 的 nullable 绑定字段必须保持命名空间隔离，问题和答案数量有界。
fn validate_interaction_request(
    value: &Value,
    expected_thread_id: Option<&Value>,
) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "requestId",
            "threadId",
            "turnId",
            "toolCallId",
            "planRevisionId",
            "runId",
            "goalId",
            "status",
            "revision",
            "questions",
            "answers",
            "createdAt",
            "updatedAt",
        ],
    )?;
    if value.get("threadId") != expected_thread_id
        || !valid_prefixed_id(value.get("requestId"), "interaction_")
        || !value
            .get("turnId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "turn_"))
        || !value
            .get("toolCallId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "call_"))
        || !value
            .get("planRevisionId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "planrev_"))
        || !value
            .get("runId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "run_"))
        || !value
            .get("goalId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "goal_"))
        || !matches!(
            value.get("status").and_then(Value::as_str),
            Some("pending" | "answered" | "cancelled" | "superseded")
        )
        || !integer_in_bounds(value.get("revision"), 0, 9_007_199_254_740_991)
        || !bounded_string(value.get("createdAt"), 1, 64)
        || !bounded_string(value.get("updatedAt"), 1, 64)
    {
        return Err("interaction request is invalid");
    }
    let questions = value
        .get("questions")
        .and_then(Value::as_array)
        .filter(|items| (1..=3).contains(&items.len()))
        .ok_or("interaction questions are invalid")?;
    let mut question_ids = HashSet::new();
    for question in questions {
        ensure_object_keys(
            question,
            &[
                "questionId",
                "prompt",
                "type",
                "required",
                "allowFreeText",
                "options",
            ],
        )?;
        if !valid_prefixed_id(question.get("questionId"), "question_")
            || !question_ids.insert(
                question
                    .get("questionId")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
            )
            || !bounded_string(question.get("prompt"), 1, 4_000)
            || !matches!(
                question.get("type").and_then(Value::as_str),
                Some("single" | "multiple" | "text")
            )
            || question.get("required").and_then(Value::as_bool).is_none()
            || question
                .get("allowFreeText")
                .and_then(Value::as_bool)
                .is_none()
        {
            return Err("interaction question is invalid");
        }
        let options = question
            .get("options")
            .and_then(Value::as_array)
            .filter(|items| items.len() <= 32)
            .ok_or("interaction options are invalid")?;
        let mut option_ids = HashSet::new();
        for option in options {
            ensure_object_keys(option, &["optionId", "label", "description", "recommended"])?;
            if !valid_prefixed_id(option.get("optionId"), "option_")
                || !option_ids.insert(
                    option
                        .get("optionId")
                        .and_then(Value::as_str)
                        .unwrap_or_default(),
                )
                || !bounded_string(option.get("label"), 1, 512)
                || !bounded_string(option.get("description"), 0, 2_000)
                || option.get("recommended").and_then(Value::as_bool).is_none()
            {
                return Err("interaction option is invalid");
            }
        }
        if question.get("type").and_then(Value::as_str) == Some("text") && !options.is_empty() {
            return Err("text interaction question has options");
        }
    }
    validate_interaction_answers(value.get("answers"))
}

/// 草稿和已提交答案共享同一结构，跳过答案不能携带选项或自填文本。
fn validate_interaction_answer(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(value, &["questionId", "optionIds", "freeText", "skipped"])?;
    let option_ids = value
        .get("optionIds")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 32)
        .ok_or("interaction answer options are invalid")?;
    let mut unique = HashSet::new();
    if !valid_prefixed_id(value.get("questionId"), "question_")
        || option_ids.iter().any(|id| {
            !valid_prefixed_id(Some(id), "option_")
                || !unique.insert(id.as_str().unwrap_or_default())
        })
        || !value
            .get("freeText")
            .is_some_and(|text| text.is_null() || bounded_string(Some(text), 0, 16_000))
        || value.get("skipped").and_then(Value::as_bool).is_none()
        || (value.get("skipped").and_then(Value::as_bool) == Some(true)
            && (!option_ids.is_empty() || value.get("freeText").and_then(Value::as_str).is_some()))
    {
        return Err("interaction answer is invalid");
    }
    Ok(())
}

/// 每个问题在一次 interaction 提交中只能出现一次，避免重复答案在三端产生不同合并结果。
fn validate_interaction_answers(value: Option<&Value>) -> Result<(), &'static str> {
    let answers = value
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 3)
        .ok_or("interaction answers are invalid")?;
    let mut question_ids = HashSet::new();
    for answer in answers {
        validate_interaction_answer(answer)?;
        if !question_ids.insert(
            answer
                .get("questionId")
                .and_then(Value::as_str)
                .unwrap_or_default(),
        ) {
            return Err("interaction answer question is duplicated");
        }
    }
    Ok(())
}

/// 草稿投影携带自己的 revision 与分页状态，owner/request identity 必须可回溯。
fn validate_interaction_draft(
    value: &Value,
    expected_thread_id: Option<&Value>,
) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "threadId",
            "requestId",
            "answers",
            "page",
            "collapsed",
            "revision",
            "updatedAt",
        ],
    )?;
    if value.get("threadId") != expected_thread_id
        || !valid_prefixed_id(value.get("requestId"), "interaction_")
        || !integer_in_bounds(value.get("page"), 0, 2)
        || value.get("collapsed").and_then(Value::as_bool).is_none()
        || !integer_in_bounds(value.get("revision"), 0, 9_007_199_254_740_991)
        || !bounded_string(value.get("updatedAt"), 1, 64)
    {
        return Err("interaction draft is invalid");
    }
    let answers = value
        .get("answers")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 3)
        .ok_or("interaction draft answers are invalid")?;
    answers.iter().try_for_each(validate_interaction_answer)
}

/// current/read 复用完整 Plan projection，空 current 是合法的无计划状态。
fn validate_current_plan_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(result, &["current"])?;
    if let Some(current) = result.get("current").filter(|value| !value.is_null()) {
        validate_plan_projection_result(current)?;
    }
    Ok(())
}

/// Plan 事件页只公开有界摘要和独立 revision 水位，cursor 仍由服务端解释。
fn validate_plan_events_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "planId",
            "planRevision",
            "eventSequence",
            "items",
            "nextCursor",
        ],
    )?;
    validate_plan_page_base(result)?;
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("plan events are invalid")?;
    for item in items {
        ensure_object_keys(item, &["eventSequence", "kind", "summary", "occurredAt"])?;
        if !integer_in_bounds(item.get("eventSequence"), 1, 9_007_199_254_740_991)
            || !bounded_string(item.get("kind"), 1, 128)
            || !bounded_string(item.get("summary"), 0, 32_768)
            || !bounded_string(item.get("occurredAt"), 1, 64)
        {
            return Err("plan event item is invalid");
        }
    }
    validate_plan_cursor(result.get("nextCursor"))
}

/// Plan evidence 页固定 revision/run 过滤身份，防止不同执行批次的证据混页。
fn validate_plan_evidence_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "planId",
            "planRevision",
            "eventSequence",
            "planRevisionId",
            "runId",
            "items",
            "nextCursor",
        ],
    )?;
    validate_plan_page_base(result)?;
    if !valid_prefixed_id(result.get("planRevisionId"), "planrev_")
        || !valid_prefixed_id(result.get("runId"), "run_")
    {
        return Err("plan evidence binding is invalid");
    }
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("plan evidence is invalid")?;
    items.iter().try_for_each(validate_evidence_item)?;
    validate_plan_cursor(result.get("nextCursor"))
}

/// Plan 与 Goal 共用 opaque 游标编码，但页验证仍要求显式 null 或有界字符串。
fn validate_plan_cursor(value: Option<&Value>) -> Result<(), &'static str> {
    if value.is_some_and(|cursor| cursor.is_null() || bounded_string(Some(cursor), 1, 512)) {
        Ok(())
    } else {
        Err("plan cursor is invalid")
    }
}

/// Plan page 共用 aggregate identity、revision/event 水位和 nullable cursor 边界。
fn validate_plan_page_base(result: &Value) -> Result<(), &'static str> {
    if !valid_prefixed_id(result.get("planId"), "plan_")
        || !integer_in_bounds(result.get("planRevision"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(result.get("eventSequence"), 0, 9_007_199_254_740_991)
    {
        return Err("plan page identity is invalid");
    }
    Ok(())
}

/// 证据项只接受公开摘要与冻结来源字段；空页仍由外层页结构负责校验。
fn validate_evidence_item(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "evidenceId",
            "goalId",
            "planId",
            "goalDefinitionRevision",
            "runId",
            "planRevisionId",
            "criterionId",
            "stepId",
            "sourceType",
            "sourceId",
            "summary",
            "digest",
            "observedAt",
            "createdAt",
        ],
    )?;
    if !valid_prefixed_id(value.get("evidenceId"), "evidence_")
        || !value
            .get("goalId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "goal_"))
        || !value
            .get("planId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "plan_"))
        || !value.get("goalDefinitionRevision").is_some_and(|revision| {
            revision.is_null() || integer_in_bounds(Some(revision), 1, 9_007_199_254_740_991)
        })
        || !valid_prefixed_id(value.get("runId"), "run_")
        || !value
            .get("planRevisionId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "planrev_"))
        || !value
            .get("criterionId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "criterion_"))
        || !value
            .get("stepId")
            .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "step_"))
        || !matches!(
            value.get("sourceType").and_then(Value::as_str),
            Some(
                "tool_result"
                    | "test_report"
                    | "build_artifact"
                    | "repository_state"
                    | "ui_assertion"
                    | "user_acceptance"
            )
        )
        || !bounded_string(value.get("sourceId"), 1, 256)
        || !bounded_string(value.get("summary"), 0, 32_768)
        || !value
            .get("digest")
            .and_then(Value::as_str)
            .is_some_and(|digest| {
                digest.len() == 64
                    && digest
                        .bytes()
                        .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
            })
        || !bounded_string(value.get("observedAt"), 1, 64)
        || !bounded_string(value.get("createdAt"), 1, 64)
    {
        return Err("evidence item is invalid");
    }
    Ok(())
}

/// `thread/list` 同时承载 Workspace page 与全局 discovery page；空页两种投影均合法，
/// 非空页必须保持单一条目形状，避免混合投影让不同消费者选择不同解释。
fn validate_thread_list_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(result, &["items", "nextCursor"])?;
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("thread list items are invalid")?;
    let discovery = items.first().is_some_and(|item| item.get("kind").is_some());
    if discovery {
        items.iter().try_for_each(validate_thread_discovery_item)?;
    } else {
        items.iter().try_for_each(validate_thread_result)?;
    }
    if !result
        .get("nextCursor")
        .is_some_and(|cursor| cursor.is_null() || bounded_string(Some(cursor), 1, 512))
    {
        return Err("thread list cursor is invalid");
    }
    Ok(())
}

/// 全局 discovery 只接受身份、标题、Thread 类型、Workspace 与运行状态，禁止带入完整 Thread 元数据。
fn validate_thread_discovery_item(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &["threadId", "title", "kind", "workspaceId", "status"],
    )?;
    if !valid_prefixed_id(value.get("threadId"), "thr_")
        || !bounded_string(value.get("title"), 1, 512)
        || !matches!(
            value.get("kind").and_then(Value::as_str),
            Some("main" | "side_chat" | "subagent")
        )
        || !valid_prefixed_id(value.get("workspaceId"), "ws_")
        || !matches!(
            value.get("status").and_then(Value::as_str),
            Some(
                "idle"
                    | "queued"
                    | "running"
                    | "waiting_approval"
                    | "suspended"
                    | "completed"
                    | "failed"
                    | "cancelled"
            )
        )
    {
        return Err("thread discovery item is invalid");
    }
    Ok(())
}

/// Thread 分页与单体 mutation 复用同一严格投影，搜索中的 archived 项也不能绕过已读字段。
fn validate_thread_page_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(result, &["items", "nextCursor"])?;
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("thread page items are invalid")?;
    items.iter().try_for_each(validate_thread_result)?;
    if !result
        .get("nextCursor")
        .is_some_and(|cursor| cursor.is_null() || bounded_string(Some(cursor), 1, 512))
    {
        return Err("thread page cursor is invalid");
    }
    Ok(())
}

/// 锁定完整 Thread wire shape；seen 与最新状态保持正交，但无 Turn 时不能伪造未读提醒。
fn validate_thread_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "threadId",
            "workspaceId",
            "preferences",
            "title",
            "status",
            "pinned",
            "latestTurnStatus",
            "latestTurnSeen",
            "activeGoalId",
            "revision",
            "createdAt",
            "updatedAt",
        ],
    )?;
    let latest_status = result.get("latestTurnStatus");
    if !valid_prefixed_id(result.get("threadId"), "thr_")
        || !valid_prefixed_id(result.get("workspaceId"), "ws_")
        || !bounded_string(result.get("title"), 1, 512)
        || !matches!(
            result.get("status").and_then(Value::as_str),
            Some("active" | "archived" | "deleted")
        )
        || result.get("pinned").and_then(Value::as_bool).is_none()
        || !latest_status.is_some_and(|status| {
            status.is_null()
                || matches!(
                    status.as_str(),
                    Some(
                        "queued"
                            | "running"
                            | "waiting_approval"
                            | "suspended"
                            | "completed"
                            | "failed"
                            | "cancelled"
                    )
                )
        })
        || result
            .get("latestTurnSeen")
            .and_then(Value::as_bool)
            .is_none()
        || latest_status.is_some_and(Value::is_null)
            && result.get("latestTurnSeen").and_then(Value::as_bool) != Some(true)
        || !result
            .get("activeGoalId")
            .is_some_and(|goal_id| goal_id.is_null() || valid_prefixed_id(Some(goal_id), "goal_"))
        || !integer_in_bounds(result.get("revision"), 0, 9_007_199_254_740_991)
        || !bounded_string(result.get("createdAt"), 1, 64)
        || !bounded_string(result.get("updatedAt"), 1, 64)
    {
        return Err("thread result metadata is invalid");
    }
    let preferences = result
        .get("preferences")
        .ok_or("thread preferences are missing")?;
    if preferences.is_null() {
        return Ok(());
    }
    ensure_object_keys(
        preferences,
        &[
            "providerId",
            "modelId",
            "reasoningLevel",
            "accessMode",
            "collaborationMode",
            "titleSource",
        ],
    )?;
    if !valid_prefixed_id(preferences.get("providerId"), "provider_")
        || !valid_prefixed_id(preferences.get("modelId"), "model_")
        || !preferences.get("reasoningLevel").is_some_and(|reasoning| {
            reasoning.is_null()
                || matches!(
                    reasoning.as_str(),
                    Some("off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max")
                )
        })
        || !matches!(
            preferences.get("accessMode").and_then(Value::as_str),
            Some("approval_required" | "full_access")
        )
        || !matches!(
            preferences.get("collaborationMode").and_then(Value::as_str),
            Some("default" | "plan")
        )
        || !matches!(
            preferences.get("titleSource").and_then(Value::as_str),
            Some("placeholder" | "auto" | "manual")
        )
    {
        return Err("thread preferences are invalid");
    }
    Ok(())
}

/// 按请求方法校验 Task 成功结果，避免相同 `accepted` 前缀让不同生命周期回执互相冒充。
fn validate_task_result(method: &str, result: &Value) -> Result<(), &'static str> {
    match method {
        "task/create" => {
            ensure_object_keys(result, &["accepted", "task"])?;
            require_task_accepted(result)?;
            validate_task_summary(result.get("task").ok_or("task summary is missing")?)?;
        }
        "task/list" => {
            ensure_object_keys(result, &["items"])?;
            let items = result
                .get("items")
                .and_then(Value::as_array)
                .filter(|items| items.len() <= 64)
                .ok_or("task list is invalid")?;
            items.iter().try_for_each(validate_task_summary)?;
            validate_task_tree(items)?;
        }
        "task/read" => validate_task_read_result(result)?,
        "task/observe" => {
            ensure_object_keys(result, &["observationId", "taskThreadId", "revision"])?;
            if !valid_prefixed_id(result.get("observationId"), "observe_")
                || !valid_prefixed_id(result.get("taskThreadId"), "thr_")
                || !integer_in_bounds(result.get("revision"), 0, 9_007_199_254_740_991)
            {
                return Err("task observation result is invalid");
            }
        }
        "task/unobserve" => {
            ensure_object_keys(result, &["accepted"])?;
            require_task_accepted(result)?;
        }
        "task/seen" | "task/cancel" => {
            ensure_object_keys(result, &["accepted", "task"])?;
            require_task_accepted(result)?;
            validate_task_summary(result.get("task").ok_or("task summary is missing")?)?;
        }
        "thread/message/send" => {
            ensure_object_keys(result, &["accepted", "messageId", "mailboxSequence"])?;
            require_task_accepted(result)?;
            if !valid_prefixed_id(result.get("messageId"), "msg_")
                || !integer_in_bounds(result.get("mailboxSequence"), 1, 9_007_199_254_740_991)
            {
                return Err("task mailbox result is invalid");
            }
        }
        "task/close" => {
            ensure_object_keys(result, &["closed"])?;
            if result.get("closed").and_then(Value::as_bool) != Some(true) {
                return Err("task close result was not closed");
            }
        }
        "task/followup" => {
            ensure_object_keys(result, &["accepted", "messageId", "turnId", "task"])?;
            require_task_accepted(result)?;
            if !valid_prefixed_id(result.get("messageId"), "msg_")
                || !valid_prefixed_id(result.get("turnId"), "turn_")
            {
                return Err("task follow-up identity is invalid");
            }
            validate_task_summary(result.get("task").ok_or("task summary is missing")?)?;
        }
        "task/tree/delete" => {
            ensure_object_keys(result, &["accepted", "deletedTaskCount"])?;
            require_task_accepted(result)?;
            if !integer_in_bounds(result.get("deletedTaskCount"), 1, 64) {
                return Err("task delete count is invalid");
            }
        }
        _ => return Err("task result method is invalid"),
    }
    Ok(())
}

/// `accepted` 不允许 false 或缺失，避免拒绝结果被误投影成已提交状态。
fn require_task_accepted(result: &Value) -> Result<(), &'static str> {
    (result.get("accepted").and_then(Value::as_bool) == Some(true))
        .then_some(())
        .ok_or("task result was not accepted")
}

/// Task 摘要固定 lineage、种类/生命周期配对及有界统计，UI 无需从 Child transcript 推导状态。
fn validate_task_summary(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "taskThreadId",
            "parentThreadId",
            "rootThreadId",
            "originTurnId",
            "taskName",
            "depth",
            "taskKind",
            "lifecycle",
            "state",
            "revision",
            "latestActivitySequence",
            "unreadCount",
            "descendantCount",
            "runningDescendantCount",
            "needsAttentionCount",
            "latestSafeSummary",
            "startedAt",
            "completedAt",
            "updatedAt",
        ],
    )?;
    let kind = value.get("taskKind").and_then(Value::as_str);
    let lifecycle = value.get("lifecycle").and_then(Value::as_str);
    let origin_valid = value
        .get("originTurnId")
        .is_some_and(|turn| turn.is_null() || valid_prefixed_id(Some(turn), "turn_"));
    let nullable_times_valid = ["startedAt", "completedAt"].iter().all(|field| {
        value
            .get(*field)
            .is_some_and(|time| time.is_null() || bounded_string(Some(time), 1, 64))
    });
    if !valid_prefixed_id(value.get("taskThreadId"), "thr_")
        || !valid_prefixed_id(value.get("parentThreadId"), "thr_")
        || !valid_prefixed_id(value.get("rootThreadId"), "thr_")
        || !origin_valid
        || (kind == Some("subagent")
            && !value
                .get("originTurnId")
                .is_some_and(|turn| valid_prefixed_id(Some(turn), "turn_")))
        || !valid_task_name(value.get("taskName"))
        || !integer_in_bounds(value.get("depth"), 1, 4)
        || !matches!(
            (kind, lifecycle),
            (Some("side_task"), Some("independent")) | (Some("subagent"), Some("attached"))
        )
        || !matches!(
            value.get("state").and_then(Value::as_str),
            Some(
                "queued"
                    | "idle"
                    | "running"
                    | "waiting_approval"
                    | "suspended"
                    | "completed"
                    | "failed"
                    | "cancelled"
            )
        )
        || !integer_in_bounds(value.get("revision"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(
            value.get("latestActivitySequence"),
            1,
            9_007_199_254_740_991,
        )
        || !integer_in_bounds(value.get("unreadCount"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(value.get("descendantCount"), 0, 64)
        || !integer_in_bounds(value.get("runningDescendantCount"), 0, 64)
        || !integer_in_bounds(value.get("needsAttentionCount"), 0, 64)
        || !value
            .get("latestSafeSummary")
            .is_some_and(|summary| summary.is_null() || bounded_string(Some(summary), 0, 32_768))
        || !nullable_times_valid
        || !bounded_string(value.get("updatedAt"), 1, 64)
    {
        return Err("task summary is invalid");
    }
    Ok(())
}

/// Task 列表必须有唯一 identity，且每个非根层级都能证明直接父节点与 depth。
fn validate_task_tree(items: &[Value]) -> Result<(), &'static str> {
    let mut by_id = HashMap::with_capacity(items.len());
    for item in items {
        let id = item
            .get("taskThreadId")
            .and_then(Value::as_str)
            .ok_or("task identity is missing")?;
        if by_id.insert(id, item).is_some() {
            return Err("task identity is duplicated");
        }
    }
    for item in items {
        let id = item
            .get("taskThreadId")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let parent = item
            .get("parentThreadId")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let root = item
            .get("rootThreadId")
            .and_then(Value::as_str)
            .unwrap_or_default();
        let depth = item
            .get("depth")
            .and_then(Value::as_u64)
            .unwrap_or_default();
        if id == root || id == parent {
            return Err("task lineage self-references");
        }
        if depth == 1 {
            if parent != root {
                return Err("depth-one task is detached");
            }
        } else {
            let parent_task = by_id.get(parent).ok_or("task parent is missing")?;
            if parent_task.get("rootThreadId").and_then(Value::as_str) != Some(root)
                || parent_task
                    .get("depth")
                    .and_then(Value::as_u64)
                    .map(|value| value + 1)
                    != Some(depth)
            {
                return Err("task parent depth is invalid");
            }
        }
    }
    Ok(())
}

/// Task read 仅返回 seed、低频 activity/mailbox 和 cursor，禁止夹带完整 Child transcript。
fn validate_task_read_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "task",
            "thread",
            "contextSeed",
            "activities",
            "mailbox",
            "nextCursor",
        ],
    )?;
    validate_task_summary(result.get("task").ok_or("task summary is missing")?)?;
    validate_thread_result(result.get("thread").ok_or("task thread is missing")?)?;
    let seed = result
        .get("contextSeed")
        .ok_or("task context seed is missing")?;
    ensure_object_keys(
        seed,
        &[
            "contextSeedId",
            "parentRevision",
            "inheritanceMode",
            "taskBrief",
            "inheritedContextSummary",
            "inheritedContextPreview",
            "fingerprint",
            "createdAt",
        ],
    )?;
    let inherited_preview = seed
        .get("inheritedContextPreview")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 24)
        .ok_or("task context preview is invalid")?;
    let inherited_code_points = inherited_preview.iter().try_fold(0usize, |total, item| {
        ensure_object_keys(item, &["role", "text", "attachmentIds"])?;
        let role = item.get("role").and_then(Value::as_str);
        let text = item.get("text").ok_or("task context text is missing")?;
        let attachments = item
            .get("attachmentIds")
            .and_then(Value::as_array)
            .filter(|values| values.len() <= 10)
            .ok_or("task context attachments are invalid")?;
        let text_points = if text.is_null() {
            0
        } else {
            let value = text.as_str().ok_or("task context text is invalid")?;
            if value.chars().count() > 512 || value.contains('\0') {
                return Err("task context text is invalid");
            }
            value.chars().count()
        };
        if !matches!(role, Some("user" | "assistant"))
            || attachments
                .iter()
                .any(|id| !valid_prefixed_id(Some(id), "att_"))
            || (text_points == 0 && attachments.is_empty())
        {
            return Err("task context preview item is invalid");
        }
        Ok(total + text_points)
    })?;
    let inheritance_mode = seed.get("inheritanceMode").and_then(Value::as_str);
    let inheritance_projection_matches = match inheritance_mode {
        Some("brief_only") => {
            seed.get("inheritedContextSummary") == Some(&Value::Null)
                && inherited_preview.is_empty()
        }
        Some("effective_context") => seed
            .get("inheritedContextSummary")
            .is_some_and(Value::is_string),
        _ => false,
    };
    let task_brief_matches = match inheritance_mode {
        Some("effective_context") => seed.get("taskBrief").is_some_and(Value::is_null),
        Some("brief_only") => validate_turn_content(seed.get("taskBrief")).is_ok(),
        _ => false,
    };
    if !valid_prefixed_id(seed.get("contextSeedId"), "seed_")
        || !integer_in_bounds(seed.get("parentRevision"), 0, 9_007_199_254_740_991)
        || !inheritance_projection_matches
        || !task_brief_matches
        || inherited_code_points > 4_096
        || !seed
            .get("inheritedContextSummary")
            .is_some_and(|summary| summary.is_null() || bounded_string(Some(summary), 0, 32_768))
        || !seed
            .get("fingerprint")
            .and_then(Value::as_str)
            .is_some_and(|fingerprint| {
                fingerprint.len() == 64
                    && fingerprint
                        .bytes()
                        .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            })
        || !bounded_string(seed.get("createdAt"), 1, 64)
    {
        return Err("task context seed is invalid");
    }
    let activities = result
        .get("activities")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("task activities are invalid")?;
    activities.iter().try_for_each(validate_task_activity)?;
    let task = result.get("task").ok_or("task summary is missing")?;
    let task_thread_id = task.get("taskThreadId");
    let latest_activity_sequence = task.get("latestActivitySequence").and_then(Value::as_u64);
    let latest_safe_summary = task.get("latestSafeSummary");
    let mut previous_activity_sequence = 0;
    for activity in activities {
        let sequence = activity
            .get("activitySequence")
            .and_then(Value::as_u64)
            .ok_or("task activity sequence is missing")?;
        if sequence <= previous_activity_sequence
            || Some(sequence) > latest_activity_sequence
            || activity.get("taskThreadId") != task_thread_id
            || (Some(sequence) == latest_activity_sequence
                && activity
                    .get("summary")
                    .and_then(|summary| summary.get("text"))
                    != latest_safe_summary)
        {
            return Err("task activity page is inconsistent");
        }
        previous_activity_sequence = sequence;
    }
    let mailbox = result
        .get("mailbox")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("task mailbox is invalid")?;
    mailbox.iter().try_for_each(validate_task_mailbox_message)?;
    let mut previous_mailbox_sequence = 0;
    for message in mailbox {
        let sequence = message
            .get("mailboxSequence")
            .and_then(Value::as_u64)
            .ok_or("task mailbox sequence is missing")?;
        if sequence <= previous_mailbox_sequence {
            return Err("task mailbox page is inconsistent");
        }
        previous_mailbox_sequence = sequence;
    }
    if !result
        .get("nextCursor")
        .is_some_and(|cursor| cursor.is_null() || valid_task_cursor(cursor))
    {
        return Err("task cursor is invalid");
    }
    Ok(())
}

/// Task cursor 固定为两个非负十进制 sequence，不能复用其它列表的 opaque cursor。
fn valid_task_cursor(value: &Value) -> bool {
    let Some(value) = value.as_str().filter(|value| value.len() <= 256) else {
        return false;
    };
    let mut parts = value.split(':');
    matches!(parts.next(), Some("task"))
        && parts.next().is_some_and(valid_task_cursor_sequence)
        && parts.next().is_some_and(valid_task_cursor_sequence)
        && parts.next().is_none()
}

/// Cursor sequence 只接受 JavaScript-safe 非负整数，防止消费者之间发生精度漂移。
fn valid_task_cursor_sequence(value: &str) -> bool {
    !value.is_empty()
        && value.bytes().all(|byte| byte.is_ascii_digit())
        && value
            .parse::<u64>()
            .is_ok_and(|number| number <= 9_007_199_254_740_991)
}

/// Activity 只允许协议定义的安全摘要与稳定因果身份，不携带原始 reasoning 或 Tool payload；
/// rootThreadId 固定记录活动所属 Task 树，供事件 envelope 和父级投影做一致性校验。
fn validate_task_activity(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "activitySequence",
            "activityId",
            "rootThreadId",
            "taskThreadId",
            "actorThreadId",
            "causalTurnId",
            "kind",
            "summary",
            "createdAt",
        ],
    )?;
    let summary = value.get("summary").ok_or("activity summary is missing")?;
    ensure_object_keys(summary, &["text"])?;
    if !integer_in_bounds(value.get("activitySequence"), 1, 9_007_199_254_740_991)
        || !valid_prefixed_id(value.get("activityId"), "activity_")
        || !valid_prefixed_id(value.get("rootThreadId"), "thr_")
        || !valid_prefixed_id(value.get("taskThreadId"), "thr_")
        || !valid_prefixed_id(value.get("actorThreadId"), "thr_")
        || !value
            .get("causalTurnId")
            .is_some_and(|turn| turn.is_null() || valid_prefixed_id(Some(turn), "turn_"))
        || !matches!(
            value.get("kind").and_then(Value::as_str),
            Some(
                "created"
                    | "dispatched"
                    | "message_sent"
                    | "follow_up_queued"
                    | "progress"
                    | "waiting_approval"
                    | "resumed"
                    | "completed"
                    | "failed"
                    | "cancelled"
                    | "suspended"
            )
        )
        || !bounded_string(summary.get("text"), 0, 32_768)
        || !bounded_string(value.get("createdAt"), 1, 64)
    {
        return Err("task activity is invalid");
    }
    Ok(())
}

/// Mailbox 行保持结构化内容和闭集消费状态，避免把内部投递 token 暴露给客户端。
fn validate_task_mailbox_message(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "mailboxSequence",
            "messageId",
            "senderThreadId",
            "targetThreadId",
            "causalTurnId",
            "kind",
            "content",
            "state",
            "boundTurnId",
            "createdAt",
            "updatedAt",
            "consumedAt",
        ],
    )?;
    if !integer_in_bounds(value.get("mailboxSequence"), 1, 9_007_199_254_740_991)
        || !valid_prefixed_id(value.get("messageId"), "msg_")
        || !valid_prefixed_id(value.get("senderThreadId"), "thr_")
        || !valid_prefixed_id(value.get("targetThreadId"), "thr_")
        || !matches!(
            value.get("kind").and_then(Value::as_str),
            Some("message" | "follow_up" | "final_answer")
        )
        || !matches!(
            value.get("state").and_then(Value::as_str),
            Some("pending" | "bound" | "consumed" | "cancelled")
        )
        || !["causalTurnId", "boundTurnId"].iter().all(|field| {
            value
                .get(*field)
                .is_some_and(|turn| turn.is_null() || valid_prefixed_id(Some(turn), "turn_"))
        })
        || !["createdAt", "updatedAt"]
            .iter()
            .all(|field| bounded_string(value.get(*field), 1, 64))
        || !value
            .get("consumedAt")
            .is_some_and(|time| time.is_null() || bounded_string(Some(time), 1, 64))
    {
        return Err("task mailbox message is invalid");
    }
    validate_turn_content(value.get("content"))
}

/// Path search 响应必须回显 Thread、Workspace、generation 与 query 栅栏，并保持 50 项相对路径上限。
fn validate_workspace_path_search_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "threadId",
            "workspaceId",
            "generation",
            "query",
            "items",
            "truncated",
        ],
    )?;
    if !valid_prefixed_id(result.get("threadId"), "thr_")
        || !valid_prefixed_id(result.get("workspaceId"), "ws_")
        || !integer_in_bounds(result.get("generation"), 1, 9_007_199_254_740_991)
        || !bounded_string(result.get("query"), 0, 256)
        || result
            .get("query")
            .and_then(Value::as_str)
            .is_some_and(|query| query.chars().any(char::is_control))
        || result.get("truncated").and_then(Value::as_bool).is_none()
    {
        return Err("workspace path search metadata is invalid");
    }
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 50)
        .ok_or("workspace path search items are invalid")?;
    let mut paths = HashSet::new();
    for item in items {
        ensure_object_keys(item, &["relativePath", "kind"])?;
        let path = item
            .get("relativePath")
            .and_then(Value::as_str)
            .filter(|path| valid_relative_reference_path(path))
            .ok_or("workspace path search item path is invalid")?;
        if !paths.insert(path)
            || !matches!(
                item.get("kind").and_then(Value::as_str),
                Some("file" | "directory")
            )
        {
            return Err("workspace path search item is invalid");
        }
    }
    Ok(())
}

/// 对能从封闭字段识别的响应投影执行当前版形状校验，避免旧 CAS 或 layer 字段因响应无 method
/// 而绕过通用 JSON-RPC envelope。
fn validate_response_result(result: &Value) -> Result<(), &'static str> {
    if result.get("effective").is_some()
        || result.get("user").is_some()
        || result.get("project").is_some()
        || result.get("credentials").is_some()
        || result.get("cas").is_some()
    {
        validate_config_read_result(result)?;
    }
    if result.get("outcome").is_some() {
        validate_context_compaction_result(result)?;
    }
    if result.get("attachmentId").is_some() && result.get("state").is_some() {
        validate_attachment_result(result)?;
    }
    if result.get("artifactId").is_some()
        && result.get("filePath").is_some()
        && result.get("contentBase64").is_some()
    {
        TurnChangeSetReadResult::try_from(result)
            .map_err(|_| "turn change set read result is invalid")?;
    } else if result.get("previewSessionId").is_some() && result.get("previewKind").is_some() {
        AttachmentPreviewOpenResult::try_from(result)
            .map_err(|_| "attachment preview open result is invalid")?;
    } else if result.get("previewSessionId").is_some() && result.get("contentBase64").is_some() {
        AttachmentPreviewReadResult::try_from(result)
            .map_err(|_| "attachment preview read result is invalid")?;
    } else if result.get("previewSessionId").is_some() && result.get("closed").is_some() {
        AttachmentPreviewCloseResult::try_from(result)
            .map_err(|_| "attachment preview close result is invalid")?;
    }
    if result.get("threadId").is_some()
        && result.get("revision").is_some()
        && result.get("items").is_some()
        && result.get("nextCursor").is_some()
    {
        validate_thread_read_result(result)?;
    }
    if result.get("accepted").is_some()
        && result.get("turnId").is_some()
        && result.get("queued").is_some()
    {
        ensure_object_keys(result, &["accepted", "turnId", "queued", "threadRevision"])?;
        if result.get("accepted").and_then(Value::as_bool) != Some(true)
            || result.get("queued").and_then(Value::as_bool).is_none()
            || !valid_prefixed_id(result.get("turnId"), "turn_")
            || !integer_in_bounds(result.get("threadRevision"), 0, 9_007_199_254_740_991)
        {
            return Err("turn accepted result is invalid");
        }
    }
    if result.get("accepted").is_some() && result.get("inputId").is_some() {
        ensure_object_keys(result, &["accepted", "inputId", "inputQueue"])?;
        let expected_turn_id = result
            .get("inputQueue")
            .and_then(|queue| queue.get("turnId"));
        if result.get("accepted").and_then(Value::as_bool) != Some(true)
            || !valid_prefixed_id(result.get("inputId"), "input_")
            || !valid_input_queue(result.get("inputQueue"), expected_turn_id)
        {
            return Err("turn input result is invalid");
        }
    }
    if result.get("accepted").is_some()
        && result.get("turnId").is_some()
        && result.get("status").is_some()
        && result.get("threadRevision").is_some()
        && result.get("inputId").is_none()
    {
        ensure_object_keys(result, &["accepted", "turnId", "status", "threadRevision"])?;
        if result.get("accepted").and_then(Value::as_bool) != Some(true)
            || !valid_prefixed_id(result.get("turnId"), "turn_")
            || !matches!(
                result.get("status").and_then(Value::as_str),
                Some(
                    "queued"
                        | "running"
                        | "waiting_approval"
                        | "suspended"
                        | "completed"
                        | "failed"
                        | "cancelled"
                )
            )
            || !integer_in_bounds(result.get("threadRevision"), 0, 9_007_199_254_740_991)
        {
            return Err("turn cancel result is invalid");
        }
    }
    if result.get("artifactId").is_some() && result.get("content").is_some() {
        validate_artifact_page_result(result)?;
    }
    Ok(())
}

/// 校验公开附件结果闭集，确保 hash、token 与路径无法伪装为 UI metadata。
fn validate_attachment_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "attachmentId",
            "workspaceId",
            "displayName",
            "sizeBytes",
            "mediaKind",
            "mediaType",
            "state",
            "createdAt",
            "expiresAt",
            "boundMessageId",
        ],
    )?;
    if !valid_prefixed_id(result.get("attachmentId"), "att_")
        || !valid_prefixed_id(result.get("workspaceId"), "ws_")
        || !bounded_string(result.get("displayName"), 1, 512)
        || !integer_in_bounds(result.get("sizeBytes"), 0, 104_857_600)
        || !matches!(
            result.get("mediaKind").and_then(Value::as_str),
            Some("text" | "image" | "pdf" | "binary")
        )
        || !bounded_string(result.get("mediaType"), 3, 128)
        || !bounded_string(result.get("createdAt"), 1, 64)
        || !bounded_string(result.get("expiresAt"), 1, 64)
    {
        return Err("attachment result metadata is invalid");
    }
    let state = require_text(result, "state")?;
    let bound = result
        .get("boundMessageId")
        .is_some_and(|value| !value.is_null() && valid_prefixed_id(Some(value), "item_"));
    match state {
        "bound" if bound => Ok(()),
        "draft" | "discarded" | "expired"
            if result.get("boundMessageId").is_some_and(Value::is_null) =>
        {
            Ok(())
        }
        _ => Err("attachment result state is invalid"),
    }
}

/// 校验平坦历史必须携带真实 Turn 归属，并锁定附件不泄露 workspace/hash/path。
fn validate_thread_read_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "threadId",
            "revision",
            "turns",
            "items",
            "taskActivities",
            "goalActivities",
            "inputQueue",
            "contextUsage",
            "nextCursor",
        ],
    )?;
    if !valid_prefixed_id(result.get("threadId"), "thr_")
        || !integer_in_bounds(result.get("revision"), 0, 9_007_199_254_740_991)
    {
        return Err("thread read identity is invalid");
    }
    let turns = result
        .get("turns")
        .and_then(Value::as_array)
        .filter(|turns| turns.len() <= 200)
        .ok_or("thread turns are invalid")?;
    for turn in turns {
        ensure_object_keys(
            turn,
            &[
                "turnId",
                "status",
                "requestedAt",
                "updatedAt",
                "completedAt",
                "errorCode",
                "changeSet",
            ],
        )?;
        if !valid_prefixed_id(turn.get("turnId"), "turn_") {
            return Err("thread turn identity is invalid");
        }
        if !matches!(
            turn.get("status").and_then(Value::as_str),
            Some(
                "queued"
                    | "running"
                    | "waiting_approval"
                    | "suspended"
                    | "completed"
                    | "failed"
                    | "cancelled"
            )
        ) {
            return Err("thread turn status is invalid");
        }
        if let Some(change_set) = turn.get("changeSet")
            && !change_set.is_null()
        {
            validate_turn_change_set(change_set)?;
        }
    }
    validate_thread_task_activities(result)?;
    validate_thread_goal_activities(result)?;
    let queue_valid = result.get("inputQueue").is_some_and(|queue| {
        queue.is_null()
            || turns.iter().any(|turn| {
                matches!(
                    turn.get("status").and_then(Value::as_str),
                    Some("queued" | "running" | "waiting_approval" | "suspended")
                ) && valid_input_queue(Some(queue), turn.get("turnId"))
            })
    });
    if !queue_valid {
        return Err("thread input queue is invalid");
    }
    validate_thread_context_usage(result.get("contextUsage"))?;
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 200)
        .ok_or("thread items are invalid")?;
    for item in items {
        if !valid_prefixed_id(item.get("turnId"), "turn_") {
            return Err("thread item turn identity is invalid");
        }
        match item.get("kind").and_then(Value::as_str) {
            Some("user_input") => {
                ensure_object_keys(
                    item,
                    &[
                        "itemId",
                        "createdAt",
                        "turnId",
                        "kind",
                        "content",
                        "attachments",
                    ],
                )?;
                validate_turn_content(item.get("content"))?;
                validate_attachment_summaries(item.get("content"), item.get("attachments"))?;
            }
            Some("final_answer") => {
                ensure_object_keys(item, &["itemId", "createdAt", "turnId", "kind", "text"])?;
                require_text(item, "text")?;
            }
            Some("thread_message") => {
                ensure_object_keys(
                    item,
                    &[
                        "itemId",
                        "createdAt",
                        "turnId",
                        "kind",
                        "sourceThreadId",
                        "sourceTitle",
                        "content",
                    ],
                )?;
                if !valid_prefixed_id(item.get("itemId"), "item_")
                    || !valid_prefixed_id(item.get("sourceThreadId"), "thr_")
                    || !bounded_string(item.get("sourceTitle"), 1, 512)
                    || !bounded_string(item.get("content"), 0, 1_048_576)
                {
                    return Err("thread message item is invalid");
                }
            }
            Some("assistant_progress" | "reasoning_summary") => {
                ensure_object_keys(
                    item,
                    &[
                        "itemId",
                        "createdAt",
                        "turnId",
                        "kind",
                        "text",
                        "modelRound",
                    ],
                )?;
                require_text(item, "text")?;
                if !integer_in_bounds(item.get("modelRound"), 1, 128) {
                    return Err("thread model round is invalid");
                }
            }
            Some("tool_call" | "tool_result") => {
                ensure_object_keys(
                    item,
                    &[
                        "itemId",
                        "createdAt",
                        "turnId",
                        "kind",
                        "callId",
                        "toolName",
                        "ordinal",
                        "presentation",
                    ],
                )?;
                if !valid_prefixed_id(item.get("callId"), "call_")
                    || !integer_in_bounds(item.get("ordinal"), 0, 1023)
                {
                    return Err("thread tool identity is invalid");
                }
                validate_tool_presentation(
                    item.get("presentation")
                        .ok_or("tool presentation is missing")?,
                )?;
            }
            Some("approval") => ensure_object_keys(
                item,
                &[
                    "itemId",
                    "createdAt",
                    "turnId",
                    "kind",
                    "approvalId",
                    "expiresAt",
                    "decision",
                ],
            )?,
            _ => return Err("thread item kind is invalid"),
        }
    }
    Ok(())
}

/// `thread/read` 只投影当前 Thread 直接委派的 Subagent Activity，并按序号严格递增；
/// Side chat 是独立生命周期，不能因为共享 root 而回流到主 Thread。该 owner/Kind 关联无法由
/// JSON Schema 表达，Golden consumer 需要和生产三端一起失败关闭。
fn validate_thread_task_activities(result: &Value) -> Result<(), &'static str> {
    let root_thread_id = result
        .get("threadId")
        .and_then(Value::as_str)
        .ok_or("thread task activity root is invalid")?;
    let entries = result
        .get("taskActivities")
        .and_then(Value::as_array)
        .filter(|entries| entries.len() <= 128)
        .ok_or("thread task activities are invalid")?;
    let mut previous_sequence = None;
    let mut activity_ids = HashSet::with_capacity(entries.len());
    for entry in entries {
        ensure_object_keys(entry, &["activity", "task"])?;
        let activity = entry
            .get("activity")
            .ok_or("thread task activity is missing")?;
        let task = entry.get("task").ok_or("thread task summary is missing")?;
        validate_task_activity(activity)?;
        validate_task_summary(task)?;
        let sequence = activity
            .get("activitySequence")
            .and_then(Value::as_u64)
            .ok_or("thread task activity sequence is invalid")?;
        let latest_sequence = task
            .get("latestActivitySequence")
            .and_then(Value::as_u64)
            .ok_or("thread task latest sequence is invalid")?;
        let activity_id = activity
            .get("activityId")
            .and_then(Value::as_str)
            .ok_or("thread task activity identity is invalid")?;
        if activity.get("taskThreadId") != task.get("taskThreadId")
            || task.get("parentThreadId").and_then(Value::as_str) != Some(root_thread_id)
            || task.get("taskKind").and_then(Value::as_str) != Some("subagent")
            || sequence > latest_sequence
            || previous_sequence.is_some_and(|previous| sequence <= previous)
            || !activity_ids.insert(activity_id)
        {
            return Err("thread task activity relationship is invalid");
        }
        previous_sequence = Some(sequence);
    }
    Ok(())
}

/// Goal 终态按全局事件序号升序且 identity 唯一；完整计划不允许混入 thread/read。
fn validate_thread_goal_activities(result: &Value) -> Result<(), &'static str> {
    let entries = result
        .get("goalActivities")
        .and_then(Value::as_array)
        .filter(|entries| entries.len() <= 128)
        .ok_or("thread goal activities are invalid")?;
    let mut previous_sequence = None;
    let mut goal_ids = HashSet::with_capacity(entries.len());
    for entry in entries {
        ensure_object_keys(
            entry,
            &[
                "goalId",
                "objective",
                "status",
                "goalRevision",
                "eventSequence",
                "occurredAt",
            ],
        )?;
        let goal_id = require_text(entry, "goalId")?;
        let objective = require_text(entry, "objective")?;
        let status = require_text(entry, "status")?;
        let goal_revision = entry.get("goalRevision").and_then(Value::as_u64);
        let sequence = entry.get("eventSequence").and_then(Value::as_u64);
        if !goal_id.starts_with("goal_")
            || objective.len() > 32_768
            || !matches!(status, "achieved" | "stopped")
            || !goal_revision.is_some_and(|value| value > 0 && value <= 9_007_199_254_740_991)
            || !sequence.is_some_and(|value| value > 0 && value <= 9_007_199_254_740_991)
            || previous_sequence.is_some_and(|previous| sequence.unwrap_or(0) <= previous)
            || !goal_ids.insert(goal_id)
        {
            return Err("thread goal activity relationship is invalid");
        }
        previous_sequence = sequence;
    }
    Ok(())
}

/// 最近 Usage 绑定唯一请求与必填画像；UNKNOWN 只表示 Token 计量不可得。
fn validate_thread_context_usage(usage: Option<&Value>) -> Result<(), &'static str> {
    let Some(usage) = usage else {
        return Err("thread context usage is missing");
    };
    if usage.is_null() {
        return Ok(());
    }
    ensure_object_keys(
        usage,
        &[
            "turnId",
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
    )?;
    let certainty = require_text(usage, "certainty")?;
    let tokens_valid = match certainty {
        "known" => {
            let input = usage.get("inputTokens").and_then(Value::as_u64);
            let output = usage.get("outputTokens").and_then(Value::as_u64);
            let total = usage.get("totalTokens").and_then(Value::as_u64);
            match (input, output, total) {
                (Some(input), Some(output), Some(total)) => {
                    input <= 9_007_199_254_740_991
                        && output <= 9_007_199_254_740_991
                        && total <= 9_007_199_254_740_991
                        && input
                            .checked_add(output)
                            .is_some_and(|minimum| total >= minimum)
                }
                _ => false,
            }
        }
        "unknown" => ["inputTokens", "outputTokens", "totalTokens"]
            .iter()
            .all(|field| usage.get(*field).is_some_and(Value::is_null)),
        _ => false,
    };
    let profile_valid = usage
        .get("profile")
        .is_some_and(valid_provider_request_profile);
    if !valid_prefixed_id(usage.get("turnId"), "turn_")
        || !valid_prefixed_id(usage.get("requestId"), "request_")
        || !integer_in_bounds(usage.get("requestOrdinal"), 1, 9_007_199_254_740_991)
        || !integer_in_bounds(usage.get("modelRound"), 1, 128)
        || !matches!(
            usage.get("purpose").and_then(Value::as_str),
            Some("assistant" | "summary")
        )
        || !profile_valid
        || !tokens_valid
        || !bounded_string(usage.get("measuredAt"), 1, 64)
    {
        return Err("thread context usage is invalid");
    }
    Ok(())
}

/// Golden 的请求画像必须完整且预算为正，避免 schema 外的消费者接受部分 current 事实。
fn valid_provider_request_profile(profile: &Value) -> bool {
    ensure_object_keys(
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
    )
    .is_ok()
        && valid_prefixed_id(profile.get("providerId"), "provider_")
        && valid_prefixed_id(profile.get("modelId"), "model_")
        && matches!(
            profile.get("api").and_then(Value::as_str),
            Some("anthropic_messages" | "openai_responses" | "openai_chat_completions")
        )
        && bounded_string(profile.get("upstreamModel"), 1, 512)
        && ["requestedReasoning", "effectiveReasoning"]
            .iter()
            .all(|field| {
                profile.get(*field).is_some_and(|reasoning| {
                    reasoning.is_null()
                        || matches!(
                            reasoning.as_str(),
                            Some("off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max")
                        )
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
        && bounded_string(profile.get("promptRevision"), 1, 256)
        && bounded_string(profile.get("toolCatalogRevision"), 1, 256)
        && integer_in_bounds(profile.get("contextWindowTokens"), 1, 9_007_199_254_740_991)
        && integer_in_bounds(profile.get("maxOutputTokens"), 1, 9_007_199_254_740_991)
}

/// 锁定 thread/compact 的显式 nullable identity 与 Token 关系，使 unchanged 不能伪装为提交。
fn validate_context_compaction_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "outcome",
            "compactionId",
            "checkpointId",
            "threadRevision",
            "inputTokensBefore",
            "inputTokensAfter",
        ],
    )?;
    let outcome = require_text(result, "outcome")?;
    let revision = result
        .get("threadRevision")
        .and_then(Value::as_u64)
        .ok_or("compact result revision is invalid")?;
    let before = result
        .get("inputTokensBefore")
        .and_then(Value::as_u64)
        .ok_or("compact before tokens are invalid")?;
    let after = result
        .get("inputTokensAfter")
        .and_then(Value::as_u64)
        .ok_or("compact after tokens are invalid")?;
    if revision > 9_007_199_254_740_991
        || before > 9_007_199_254_740_991
        || after > 9_007_199_254_740_991
    {
        return Err("compact result integer is unsafe");
    }
    match outcome {
        "compacted"
            if valid_prefixed_id(result.get("compactionId"), "cmp_")
                && valid_prefixed_id(result.get("checkpointId"), "checkpoint_")
                && after < before =>
        {
            Ok(())
        }
        "unchanged"
            if result.get("compactionId").is_some_and(Value::is_null)
                && result.get("checkpointId").is_some_and(Value::is_null)
                && after == before =>
        {
            Ok(())
        }
        _ => Err("compact result outcome is invalid"),
    }
}

/// 固定 configuration/read 的唯一 CAS 投影；layer 不得重复 version，顶层也不得出现
/// 闭集外的 credentialVersion。
fn validate_config_read_result(result: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        result,
        &[
            "workspaceId",
            "effective",
            "user",
            "project",
            "credentials",
            "cas",
            "diagnostics",
            "trusted",
        ],
    )?;
    let workspace_id = result.get("workspaceId").ok_or("workspace id is missing")?;
    if !workspace_id.is_null() && !valid_prefixed_id(Some(workspace_id), "ws_") {
        return Err("workspace id is invalid");
    }
    if !result.get("effective").is_some_and(Value::is_object) {
        return Err("effective configuration is invalid");
    }
    validate_config_layer(result.get("user"))?;
    validate_config_layer(result.get("project"))?;
    validate_config_credentials(result.get("credentials"))?;
    let cas = result.get("cas").ok_or("configuration cas is missing")?;
    ensure_object_keys(cas, &["userVersion", "projectVersion", "credentialVersion"])?;
    for field in ["userVersion", "projectVersion", "credentialVersion"] {
        validate_version(cas.get(field))?;
    }
    let diagnostics = result
        .get("diagnostics")
        .and_then(Value::as_array)
        .filter(|values| values.len() <= 32)
        .ok_or("configuration diagnostics are invalid")?;
    if diagnostics.iter().any(|value| {
        !value.as_str().is_some_and(|text| {
            !text.is_empty()
                && text.len() <= 64
                && text.bytes().enumerate().all(|(index, byte)| {
                    byte.is_ascii_uppercase()
                        || (index > 0 && (byte.is_ascii_digit() || byte == b'_'))
                })
        })
    }) || result.get("trusted").and_then(Value::as_bool).is_none()
    {
        return Err("configuration read projection is invalid");
    }
    Ok(())
}

/// 校验配置 layer 当前只携带状态和 document，不允许恢复已删除的 per-layer version。
fn validate_config_layer(value: Option<&Value>) -> Result<(), &'static str> {
    let layer = value.ok_or("configuration layer is missing")?;
    ensure_object_keys(layer, &["present", "trusted", "status", "document"])?;
    if layer.get("present").and_then(Value::as_bool).is_none()
        || layer.get("trusted").and_then(Value::as_bool).is_none()
        || !matches!(
            layer.get("status").and_then(Value::as_str),
            Some("missing" | "valid" | "untrusted" | "corrupt" | "io_error")
        )
        || !layer
            .get("document")
            .is_some_and(|document| document.is_null() || document.is_object())
    {
        return Err("configuration layer is invalid");
    }
    Ok(())
}

/// 限制 credential 投影为 configured 布尔值，Secret 和版本均不得出现在读取响应。
fn validate_config_credentials(value: Option<&Value>) -> Result<(), &'static str> {
    let credentials = value
        .and_then(Value::as_object)
        .ok_or("configuration credentials are invalid")?;
    for (credential_id, projection) in credentials {
        if !valid_prefixed_id(Some(&Value::String(credential_id.clone())), "cred_") {
            return Err("credential id is invalid");
        }
        ensure_object_keys(projection, &["configured"])?;
        if projection
            .get("configured")
            .and_then(Value::as_bool)
            .is_none()
        {
            return Err("credential projection is invalid");
        }
    }
    Ok(())
}

/// 拒绝已删除 Request，并校验 v1 Corpus 覆盖的高风险 Parameter Shape，确保旧协议入口不会回流。
fn validate_request(method: &str, params: &Value) -> Result<(), &'static str> {
    if !REQUEST_METHODS.contains(&method) {
        return Err("request method is unknown");
    }
    let allowed = match method {
        "runtime/initialize" => [
            "protocolMajor",
            "protocolMinor",
            "clientVersion",
            "capabilities",
            "limits",
        ]
        .as_slice(),
        "runtime/health" | "runtime/shutdown" => [].as_slice(),
        "workspace/open" => ["cwd", "displayName"].as_slice(),
        "workspace/open-general" => [].as_slice(),
        "workspace/list" | "mcp/list" => ["cursor", "limit"].as_slice(),
        "workspace/path/search" => ["threadId", "workspaceId", "query", "limit"].as_slice(),
        "skill/list" => ["workspaceId", "cursor", "limit"].as_slice(),
        "thread/list" => ["workspaceId", "scope", "query", "cursor", "limit"].as_slice(),
        "thread/search" => ["workspaceId", "query", "cursor", "limit"].as_slice(),
        "workspace/set-trust" => ["workspaceId", "trust"].as_slice(),
        "workspace/unregister" => ["workspaceId", "expectedRevision"].as_slice(),
        "thread/create" => [
            "cwd",
            "title",
            "providerId",
            "modelId",
            "reasoningLevel",
            "accessMode",
            "collaborationMode",
        ]
        .as_slice(),
        "thread/read" => ["threadId", "cursor", "limit"].as_slice(),
        "thread/rename" => ["threadId", "title", "expectedThreadRevision"].as_slice(),
        "thread/pin" => ["threadId", "pinned", "expectedThreadRevision"].as_slice(),
        "thread/preferences/update" => [
            "threadId",
            "providerId",
            "modelId",
            "reasoningLevel",
            "accessMode",
            "collaborationMode",
            "expectedThreadRevision",
        ]
        .as_slice(),
        "thread/compact" => ["threadId", "expectedThreadRevision"].as_slice(),
        "interaction/read" => ["threadId", "requestId"].as_slice(),
        "interaction/observe" => ["threadId"].as_slice(),
        "interaction/unobserve" => ["observationId"].as_slice(),
        "interaction/draft/save" => [
            "threadId",
            "requestId",
            "expectedDraftRevision",
            "idempotencyKey",
            "answers",
            "page",
            "collapsed",
        ]
        .as_slice(),
        "interaction/respond" => [
            "threadId",
            "requestId",
            "expectedRevision",
            "idempotencyKey",
            "answers",
        ]
        .as_slice(),
        "interaction/cancel" => [
            "threadId",
            "requestId",
            "expectedRevision",
            "idempotencyKey",
        ]
        .as_slice(),
        "goal/read" | "goal/observe" => ["goalId"].as_slice(),
        "plan/read" | "plan/revisions/list" | "plan/events/read" => {
            ["threadId", "planId", "cursor", "limit"].as_slice()
        }
        "plan/current/read" => ["threadId"].as_slice(),
        "goal/events/read" => ["goalId", "cursor", "limit"].as_slice(),
        "goal/unobserve" => ["observationId"].as_slice(),
        "goal/evidence/list" => [
            "goalId",
            "goalDefinitionRevision",
            "planRevisionId",
            "cursor",
            "limit",
        ]
        .as_slice(),
        "goal/create" => [
            "owner",
            "objective",
            "acceptanceCriteria",
            "expectedGoalRevision",
            "idempotencyKey",
        ]
        .as_slice(),
        "goal/plan/attach" => [
            "goalId",
            "expectedGoalRevision",
            "idempotencyKey",
            "planId",
            "planRevisionId",
            "planHash",
        ]
        .as_slice(),
        "goal/plan/detach" | "goal/pause" | "goal/resume" | "goal/stop" => {
            ["goalId", "expectedGoalRevision", "idempotencyKey"].as_slice()
        }
        "plan/create" => [
            "owner",
            "objective",
            "expectedThreadRevision",
            "idempotencyKey",
        ]
        .as_slice(),
        "plan/draft/save" => [
            "threadId",
            "planId",
            "expectedPlanRevision",
            "idempotencyKey",
            "draft",
        ]
        .as_slice(),
        "plan/draft/discard" | "plan/propose" => [
            "threadId",
            "planId",
            "expectedPlanRevision",
            "idempotencyKey",
        ]
        .as_slice(),
        "plan/execute" => [
            "threadId",
            "planId",
            "expectedPlanRevision",
            "idempotencyKey",
            "planRevisionId",
            "planHash",
        ]
        .as_slice(),
        "plan/observe" => ["threadId", "planId"].as_slice(),
        "plan/unobserve" => ["observationId"].as_slice(),
        "plan/evidence/list" => [
            "threadId",
            "planId",
            "planRevisionId",
            "runId",
            "cursor",
            "limit",
        ]
        .as_slice(),
        "plan/pause" | "plan/resume" | "plan/stop" => [
            "threadId",
            "planId",
            "expectedPlanRevision",
            "runId",
            "idempotencyKey",
        ]
        .as_slice(),
        "plan/reject" => [
            "threadId",
            "planId",
            "expectedPlanRevision",
            "idempotencyKey",
            "reason",
        ]
        .as_slice(),
        "task/create" => [
            "parentThreadId",
            "parentTurnId",
            "expectedParentRevision",
            "taskName",
            "preferences",
        ]
        .as_slice(),
        "task/list" => ["rootThreadId"].as_slice(),
        "task/read" => ["taskThreadId", "cursor", "limit"].as_slice(),
        "task/observe" => ["taskThreadId", "expectedTaskRevision"].as_slice(),
        "task/unobserve" => ["observationId"].as_slice(),
        "task/seen" => [
            "taskThreadId",
            "expectedTaskRevision",
            "throughActivitySequence",
        ]
        .as_slice(),
        "thread/message/send" => [
            "senderThreadId",
            "targetThreadId",
            "content",
            "idempotencyKey",
        ]
        .as_slice(),
        "task/close" => ["taskThreadId"].as_slice(),
        "task/followup" => [
            "senderThreadId",
            "targetThreadId",
            "content",
            "idempotencyKey",
            "expectedTaskRevision",
        ]
        .as_slice(),
        "task/cancel" => ["taskThreadId", "expectedTaskRevision"].as_slice(),
        "task/tree/delete" => [
            "taskThreadId",
            "expectedTaskRevision",
            "confirmTaskThreadId",
        ]
        .as_slice(),
        "thread/seen" | "thread/archive" | "thread/restore" | "thread/delete" => {
            ["threadId", "expectedThreadRevision"].as_slice()
        }
        "attachment/import" => [
            "ingressToken",
            "workspaceId",
            "displayName",
            "sizeBytes",
            "sha256",
        ]
        .as_slice(),
        "attachment/discard" => ["attachmentId"].as_slice(),
        "attachment/preview/open" => ["attachmentId", "authorization"].as_slice(),
        "attachment/preview/read" => ["previewSessionId", "offsetBytes", "limitBytes"].as_slice(),
        "attachment/preview/close" => ["previewSessionId"].as_slice(),
        "turn/start" => ["threadId", "content", "deadlineMs"].as_slice(),
        "turn/resume" => ["turnId", "expectedThreadRevision"].as_slice(),
        "turn/cancel" => ["turnId", "expectedThreadRevision"].as_slice(),
        "turn/input/enqueue" => ["turnId", "content"].as_slice(),
        "turn/input/prioritize" | "turn/input/delete" => {
            ["turnId", "inputId", "expectedInputRevision"].as_slice()
        }
        "turn/input/update" => ["turnId", "inputId", "expectedInputRevision", "content"].as_slice(),
        "turn/change-set/read" => ["threadId", "turnId", "artifactId", "filePath"].as_slice(),
        "tool/artifact/read" => [
            "threadId",
            "turnId",
            "callId",
            "artifactId",
            "offsetCharacters",
            "limitCharacters",
        ]
        .as_slice(),
        "approval/respond" => {
            ["approvalId", "turnId", "decision", "expectedThreadRevision"].as_slice()
        }
        "configuration/read" => ["workspaceId"].as_slice(),
        "configuration/patch" => ["scope", "workspaceId", "patch", "expectedVersion"].as_slice(),
        "configuration/replace" => {
            ["scope", "workspaceId", "document", "expectedVersion"].as_slice()
        }
        "configuration/reset" => ["scope", "workspaceId", "expectedVersion"].as_slice(),
        "credential/set" => ["credentialId", "secret", "expectedVersion"].as_slice(),
        "credential/delete" => ["credentialId", "expectedVersion"].as_slice(),
        "mcp/test" => ["mcpId"].as_slice(),
        "model/test" => ["providerId", "modelId"].as_slice(),
        "mcp/list-tools" => ["mcpId", "cursor", "limit"].as_slice(),
        _ => return Err("request method is unknown"),
    };
    ensure_object_keys(params, allowed)?;
    for field in ["limit"] {
        if let Some(limit) = params.get(field)
            && limit
                .as_u64()
                .is_none_or(|value| !(1..=200).contains(&value))
        {
            return Err("page limit is invalid");
        }
    }
    match method {
        "runtime/initialize" => {
            if params.get("protocolMajor").and_then(Value::as_u64) != Some(1)
                || params.get("protocolMinor").and_then(Value::as_u64) != Some(0)
            {
                return Err("protocol version is invalid");
            }
            require_text(params, "clientVersion")?;
            let max_frame = params
                .pointer("/limits/maxFrameBytes")
                .and_then(Value::as_u64)
                .ok_or("frame limit is missing")?;
            if !(1_024..=4_194_304).contains(&max_frame) {
                return Err("frame limit is invalid");
            }
            let methods = params
                .pointer("/capabilities/methods")
                .and_then(Value::as_array)
                .ok_or("method capability catalog is missing")?;
            if !string_array_equals(methods, REQUEST_METHODS) {
                return Err("method capability catalog is not the v1 closure");
            }
            let events = params
                .pointer("/capabilities/events")
                .and_then(Value::as_array)
                .ok_or("event capability catalog is missing")?;
            if !string_array_equals(events, CAPABILITY_EVENT_METHODS) {
                return Err("event capability catalog is not the v1 closure");
            }
            if params.pointer("/capabilities/features")
                != Some(&serde_json::json!([
                    "task_threads_v1",
                    "plan_goal_v1",
                    "interaction_v1"
                ]))
            {
                return Err("task feature capability is not the v1 closure");
            }
        }
        "workspace/open" => {
            validate_cwd(params.get("cwd"))?;
            if let Some(display_name) = params.get("displayName")
                && (!display_name.is_string() || display_name.as_str().is_none_or(str::is_empty))
            {
                return Err("workspace display name is invalid");
            }
        }
        "workspace/path/search"
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || !valid_prefixed_id(params.get("workspaceId"), "ws_")
                || !bounded_string(params.get("query"), 0, 256)
                || params
                    .get("query")
                    .and_then(Value::as_str)
                    .is_some_and(|query| query.chars().any(char::is_control))
                || params
                    .get("limit")
                    .is_some_and(|limit| !integer_in_bounds(Some(limit), 1, 50)) =>
        {
            return Err("workspace path search is invalid");
        }
        "thread/create" => {
            require_text(params, "title")?;
            if let Some(cwd) = params.get("cwd")
                && !cwd.is_null()
            {
                validate_cwd(Some(cwd))?;
            }
            if !valid_prefixed_id(params.get("providerId"), "provider_")
                || !valid_prefixed_id(params.get("modelId"), "model_")
                || !matches!(
                    params.get("accessMode").and_then(Value::as_str),
                    Some("approval_required" | "full_access")
                )
                || !matches!(
                    params.get("collaborationMode").and_then(Value::as_str),
                    Some("default" | "plan")
                )
                || params.get("reasoningLevel").is_none_or(|effort| {
                    !effort.is_null() && !matches!(effort.as_str(), Some("low" | "medium" | "high"))
                })
            {
                return Err("thread runtime preferences are invalid");
            }
        }
        "thread/list" => {
            if params.get("scope").is_some() {
                if params.get("scope").and_then(Value::as_str) != Some("all")
                    || params.get("query").is_some_and(|query| {
                        !bounded_string(Some(query), 0, 256)
                            || query
                                .as_str()
                                .is_some_and(|value| value.chars().any(char::is_control))
                    })
                    || params
                        .get("cursor")
                        .is_some_and(|cursor| !bounded_string(Some(cursor), 1, 512))
                    || params
                        .get("workspaceId")
                        .is_some_and(|workspace| !valid_prefixed_id(Some(workspace), "ws_"))
                {
                    return Err("thread discovery params are invalid");
                }
            } else if params.get("query").is_some()
                || !valid_prefixed_id(params.get("workspaceId"), "ws_")
                || params
                    .get("cursor")
                    .is_some_and(|cursor| !bounded_string(Some(cursor), 1, 512))
            {
                return Err("thread workspace list params are invalid");
            }
        }
        "goal/read" | "goal/observe" | "goal/events/read"
            if !valid_prefixed_id(params.get("goalId"), "goal_") =>
        {
            return Err("goal query identity is invalid");
        }
        "plan/read" | "plan/revisions/list"
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || !valid_prefixed_id(params.get("planId"), "plan_") =>
        {
            return Err("plan query identity is invalid");
        }
        "plan/current/read" if !valid_prefixed_id(params.get("threadId"), "thr_") => {
            return Err("current plan query identity is invalid");
        }
        "interaction/respond" | "interaction/draft/save"
            if params
                .get("answers")
                .and_then(Value::as_array)
                .is_none_or(|answers| {
                    answers.iter().any(|answer| {
                        !valid_prefixed_id(answer.get("questionId"), "question_")
                            || answer
                                .get("optionIds")
                                .and_then(Value::as_array)
                                .is_none_or(|ids| {
                                    ids.iter().any(|id| !valid_prefixed_id(Some(id), "option_"))
                                })
                    })
                }) =>
        {
            return Err("interaction answer identity is invalid");
        }
        "goal/evidence/list"
            if !valid_prefixed_id(params.get("goalId"), "goal_")
                || !integer_in_bounds(
                    params.get("goalDefinitionRevision"),
                    1,
                    9_007_199_254_740_991,
                )
                || params
                    .get("planRevisionId")
                    .is_some_and(|value| !valid_prefixed_id(Some(value), "planrev_")) =>
        {
            return Err("goal evidence query identity is invalid");
        }
        "goal/unobserve" if !valid_prefixed_id(params.get("observationId"), "observe_") => {
            return Err("goal observation identity is invalid");
        }
        "goal/create" => {
            let owner = params.get("owner").ok_or("goal owner is missing")?;
            let owner_valid = match owner.get("kind").and_then(Value::as_str) {
                Some("thread") => valid_prefixed_id(owner.get("threadId"), "thr_"),
                Some("independent_task") => valid_prefixed_id(owner.get("taskThreadId"), "thr_"),
                _ => false,
            };
            if !owner_valid
                || !valid_bounded_text(params.get("objective"), 1, 4_096)
                || !params
                    .get("acceptanceCriteria")
                    .is_some_and(Value::is_array)
                || !integer_in_bounds(params.get("expectedGoalRevision"), 0, 9_007_199_254_740_991)
                || !valid_bounded_text(params.get("idempotencyKey"), 1, 128)
            {
                return Err("goal create payload is invalid");
            }
        }
        "goal/plan/attach" => {
            validate_goal_mutation(params)?;
            validate_plan_binding(params)?;
            if !valid_prefixed_id(params.get("planId"), "plan_") {
                return Err("goal plan attachment is invalid");
            }
        }
        "goal/plan/detach" | "goal/pause" | "goal/resume" | "goal/stop" => {
            validate_goal_mutation(params)?
        }
        "plan/create" => {
            let owner = params.get("owner").ok_or("plan owner is missing")?;
            if owner.get("kind").and_then(Value::as_str) != Some("thread")
                || !valid_prefixed_id(owner.get("threadId"), "thr_")
                || !valid_bounded_text(params.get("objective"), 1, 4_096)
                || !integer_in_bounds(
                    params.get("expectedThreadRevision"),
                    0,
                    9_007_199_254_740_991,
                )
                || !valid_bounded_text(params.get("idempotencyKey"), 1, 128)
            {
                return Err("plan create payload is invalid");
            }
        }
        "plan/draft/discard" | "plan/propose" | "plan/reject" => validate_plan_mutation(params)?,
        "plan/draft/save" => {
            validate_plan_mutation(params)?;
            if !params.get("draft").is_some_and(Value::is_object) {
                return Err("plan draft must be structured");
            }
        }
        "plan/execute" => {
            validate_plan_mutation(params)?;
            validate_plan_binding(params)?;
        }
        "interaction/read"
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || params
                    .get("requestId")
                    .is_some_and(|request| !valid_prefixed_id(Some(request), "interaction_")) =>
        {
            return Err("interaction read identity is invalid");
        }
        "interaction/observe" if !valid_prefixed_id(params.get("threadId"), "thr_") => {
            return Err("interaction observation thread is invalid");
        }
        "interaction/unobserve" if !valid_prefixed_id(params.get("observationId"), "observe_") => {
            return Err("interaction observation identity is invalid");
        }
        "interaction/draft/save" => {
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || !valid_prefixed_id(params.get("requestId"), "interaction_")
                || !integer_in_bounds(
                    params.get("expectedDraftRevision"),
                    0,
                    9_007_199_254_740_991,
                )
                || !valid_bounded_text(params.get("idempotencyKey"), 1, 128)
                || !integer_in_bounds(params.get("page"), 0, 2)
                || params.get("collapsed").and_then(Value::as_bool).is_none()
            {
                return Err("interaction draft boundary is invalid");
            }
            validate_interaction_answers(params.get("answers"))?;
        }
        "interaction/respond" => {
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || !valid_prefixed_id(params.get("requestId"), "interaction_")
                || !integer_in_bounds(params.get("expectedRevision"), 0, 9_007_199_254_740_991)
                || !valid_bounded_text(params.get("idempotencyKey"), 1, 128)
            {
                return Err("interaction response boundary is invalid");
            }
            validate_interaction_answers(params.get("answers"))?;
        }
        "interaction/cancel"
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || !valid_prefixed_id(params.get("requestId"), "interaction_")
                || !integer_in_bounds(params.get("expectedRevision"), 0, 9_007_199_254_740_991)
                || !valid_bounded_text(params.get("idempotencyKey"), 1, 128) =>
        {
            return Err("interaction cancellation boundary is invalid");
        }
        "turn/start" => {
            require_text(params, "threadId")?;
            validate_turn_content(params.get("content"))?;
            if let Some(deadline) = params.get("deadlineMs")
                && deadline
                    .as_u64()
                    .is_none_or(|value| !(1_000..=86_400_000).contains(&value))
            {
                return Err("turn deadline is invalid");
            }
        }
        "task/create" => {
            if !valid_prefixed_id(params.get("parentThreadId"), "thr_")
                || !params
                    .get("parentTurnId")
                    .is_some_and(|turn| turn.is_null() || valid_prefixed_id(Some(turn), "turn_"))
                || !integer_in_bounds(
                    params.get("expectedParentRevision"),
                    0,
                    9_007_199_254_740_991,
                )
                || !valid_task_name(params.get("taskName"))
            {
                return Err("task create identity is invalid");
            }
            if params
                .get("preferences")
                .is_some_and(|preferences| !valid_task_create_preferences(Some(preferences)))
            {
                return Err("task create preferences are invalid");
            }
        }
        "task/list" if !valid_prefixed_id(params.get("rootThreadId"), "thr_") => {
            return Err("task root identity is invalid");
        }
        "task/read"
            if !valid_prefixed_id(params.get("taskThreadId"), "thr_")
                || params
                    .get("cursor")
                    .is_some_and(|cursor| !valid_task_cursor(cursor)) =>
        {
            return Err("task read boundary is invalid");
        }
        "task/observe" | "task/cancel"
            if !valid_prefixed_id(params.get("taskThreadId"), "thr_")
                || !integer_in_bounds(
                    params.get("expectedTaskRevision"),
                    0,
                    9_007_199_254_740_991,
                ) =>
        {
            return Err("task mutation identity is invalid");
        }
        "task/unobserve" if !valid_prefixed_id(params.get("observationId"), "observe_") => {
            return Err("task observation identity is invalid");
        }
        "task/seen"
            if !valid_prefixed_id(params.get("taskThreadId"), "thr_")
                || !integer_in_bounds(
                    params.get("expectedTaskRevision"),
                    0,
                    9_007_199_254_740_991,
                )
                || !integer_in_bounds(
                    params.get("throughActivitySequence"),
                    1,
                    9_007_199_254_740_991,
                ) =>
        {
            return Err("task seen boundary is invalid");
        }
        "thread/message/send" | "task/followup" => {
            if !valid_prefixed_id(params.get("senderThreadId"), "thr_")
                || !valid_prefixed_id(params.get("targetThreadId"), "thr_")
                || !valid_bounded_text(params.get("idempotencyKey"), 1, 128)
                || method == "task/followup"
                    && !integer_in_bounds(
                        params.get("expectedTaskRevision"),
                        0,
                        9_007_199_254_740_991,
                    )
            {
                return Err("task mailbox boundary is invalid");
            }
            validate_turn_content(params.get("content"))?;
        }
        "task/close" if !valid_prefixed_id(params.get("taskThreadId"), "thr_") => {
            return Err("task close identity is invalid");
        }
        "task/tree/delete"
            if !valid_prefixed_id(params.get("taskThreadId"), "thr_")
                || !integer_in_bounds(
                    params.get("expectedTaskRevision"),
                    0,
                    9_007_199_254_740_991,
                )
                || params.get("confirmTaskThreadId") != params.get("taskThreadId") =>
        {
            return Err("task tree confirmation is invalid");
        }
        "attachment/import"
            if !params
                .get("ingressToken")
                .and_then(Value::as_str)
                .is_some_and(|value| {
                    value.len() == 32
                        && value
                            .bytes()
                            .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
                })
                || !valid_prefixed_id(params.get("workspaceId"), "ws_")
                || !bounded_string(params.get("displayName"), 1, 512)
                || !integer_in_bounds(params.get("sizeBytes"), 0, 104_857_600)
                || !params
                    .get("sha256")
                    .and_then(Value::as_str)
                    .is_some_and(|value| {
                        value.len() == 64
                            && value
                                .bytes()
                                .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
                    }) =>
        {
            return Err("attachment import is invalid");
        }
        "attachment/discard" if !valid_prefixed_id(params.get("attachmentId"), "att_") => {
            return Err("attachment id is invalid");
        }
        "attachment/preview/open" => validate_attachment_preview_open(params)?,
        "attachment/preview/read"
            if !valid_prefixed_id(params.get("previewSessionId"), "apv_")
                || !integer_in_bounds(params.get("offsetBytes"), 0, 104_857_600)
                || !integer_in_bounds(params.get("limitBytes"), 4, 65_536) =>
        {
            return Err("attachment preview read is invalid");
        }
        "attachment/preview/close"
            if !valid_prefixed_id(params.get("previewSessionId"), "apv_") =>
        {
            return Err("attachment preview session id is invalid");
        }
        "turn/resume" | "turn/cancel" => {
            require_text(params, "turnId")?;
            if !integer_in_bounds(
                params.get("expectedThreadRevision"),
                0,
                9_007_199_254_740_991,
            ) {
                return Err("turn revision is invalid");
            }
        }
        "turn/input/enqueue"
            if !valid_prefixed_id(params.get("turnId"), "turn_")
                || validate_turn_content(params.get("content")).is_err()
                || !queued_content_within_budget(params.get("content")) =>
        {
            return Err("turn input enqueue is invalid");
        }
        "turn/input/prioritize" | "turn/input/delete"
            if !valid_prefixed_id(params.get("turnId"), "turn_")
                || !valid_prefixed_id(params.get("inputId"), "input_")
                || !integer_in_bounds(
                    params.get("expectedInputRevision"),
                    1,
                    9_007_199_254_740_991,
                ) =>
        {
            return Err("turn input mutation is invalid");
        }
        "turn/input/update"
            if !valid_prefixed_id(params.get("turnId"), "turn_")
                || !valid_prefixed_id(params.get("inputId"), "input_")
                || !integer_in_bounds(
                    params.get("expectedInputRevision"),
                    1,
                    9_007_199_254_740_991,
                )
                || validate_turn_content(params.get("content")).is_err()
                || !queued_content_within_budget(params.get("content")) =>
        {
            return Err("turn input update is invalid");
        }
        "turn/change-set/read" => {
            validate_artifact_read_identity(params, false)?;
            if !params
                .get("filePath")
                .and_then(Value::as_str)
                .is_some_and(valid_relative_path)
            {
                return Err("change set artifact identity is invalid");
            }
        }
        "tool/artifact/read" => {
            validate_artifact_read_identity(params, true)?;
            if !integer_in_bounds(params.get("offsetCharacters"), 0, 9_007_199_254_740_991)
                || !integer_in_bounds(params.get("limitCharacters"), 1, 65_536)
            {
                return Err("tool artifact page is invalid");
            }
        }
        "thread/compact"
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || !integer_in_bounds(
                    params.get("expectedThreadRevision"),
                    0,
                    9_007_199_254_740_991,
                ) =>
        {
            return Err("context compaction request is invalid");
        }
        "approval/respond" => {
            require_text(params, "approvalId")?;
            require_text(params, "turnId")?;
            require_text(params, "decision")?;
            if !params
                .get("decision")
                .and_then(Value::as_str)
                .is_some_and(|value| matches!(value, "approve" | "deny"))
            {
                return Err("approval decision is invalid");
            }
            if params
                .get("expectedThreadRevision")
                .and_then(Value::as_u64)
                .is_none_or(|value| value < 1)
            {
                return Err("approval revision is invalid");
            }
        }
        "configuration/read"
            if params.get("workspaceId").is_some()
                && !valid_prefixed_id(params.get("workspaceId"), "ws_") =>
        {
            return Err("workspace id is invalid");
        }
        "configuration/read" => {}
        "configuration/patch" => {
            validate_config_scope(params)?;
            validate_version(params.get("expectedVersion"))?;
            if !params.get("patch").is_some_and(Value::is_object) {
                return Err("configuration patch is invalid");
            }
            validate_config_value(params.get("patch"))?;
        }
        "configuration/replace" => {
            validate_config_scope(params)?;
            validate_version(params.get("expectedVersion"))?;
            validate_config_document(params.get("document"))?;
        }
        "configuration/reset" => {
            validate_config_scope(params)?;
            validate_version(params.get("expectedVersion"))?;
        }
        "credential/set" => {
            if !valid_prefixed_id(params.get("credentialId"), "cred_")
                || !params
                    .get("secret")
                    .and_then(Value::as_str)
                    .is_some_and(|secret| !secret.is_empty() && secret.len() <= 8192)
            {
                return Err("credential write is invalid");
            }
            validate_version(params.get("expectedVersion"))?;
        }
        "credential/delete" => {
            if !valid_prefixed_id(params.get("credentialId"), "cred_") {
                return Err("credential id is invalid");
            }
            validate_version(params.get("expectedVersion"))?;
        }
        _ => {}
    }
    Ok(())
}

/// 校验预览 open 的授权判别联合，DRAFT 与 BOUND 不能同时携带 Workspace/Thread identity。
fn validate_attachment_preview_open(params: &Value) -> Result<(), &'static str> {
    if !valid_prefixed_id(params.get("attachmentId"), "att_") {
        return Err("attachment preview id is invalid");
    }
    let authorization = params
        .get("authorization")
        .ok_or("attachment preview authorization is missing")?;
    match authorization.get("kind").and_then(Value::as_str) {
        Some("draft") => {
            ensure_object_keys(authorization, &["kind", "workspaceId"])?;
            if !valid_prefixed_id(authorization.get("workspaceId"), "ws_") {
                return Err("attachment preview workspace is invalid");
            }
        }
        Some("thread") => {
            ensure_object_keys(authorization, &["kind", "threadId"])?;
            if !valid_prefixed_id(authorization.get("threadId"), "thr_") {
                return Err("attachment preview thread is invalid");
            }
        }
        _ => return Err("attachment preview authorization kind is invalid"),
    }
    Ok(())
}

/// 校验四类消息块的闭集、序列化顺序和去重；这里只校验 wire identity，引用真实性仍由 Java 负责。
fn validate_turn_content(value: Option<&Value>) -> Result<(), &'static str> {
    let items = value
        .and_then(Value::as_array)
        .filter(|items| (1..=64).contains(&items.len()))
        .ok_or("turn content is missing")?;
    let mut attachment_ids = std::collections::HashSet::new();
    let mut workspace_references = std::collections::HashSet::new();
    let mut workspace_ids = std::collections::HashSet::new();
    let mut skill_ids = std::collections::HashSet::new();
    let mut total_text = 0usize;
    let mut phase = 0_u8;
    let mut text_count = 0_u8;
    let mut sendable = false;
    for item in items {
        let object = item.as_object().ok_or("turn content item is invalid")?;
        match object.get("type").and_then(Value::as_str) {
            Some("text") => {
                phase = 2;
                text_count += 1;
                ensure_object_keys(item, &["type", "text"])?;
                let text = require_text(item, "text")?;
                total_text = total_text.saturating_add(text.len());
                if text_count != 1 || total_text > 4_000_000 || text.contains('\0') {
                    return Err("turn text exceeds limit");
                }
                sendable = true;
            }
            Some("attachment") => {
                ensure_object_keys(item, &["type", "attachmentId"])?;
                if phase > 1 {
                    return Err("turn attachment order is invalid");
                }
                phase = 1;
                let id = item
                    .get("attachmentId")
                    .and_then(Value::as_str)
                    .filter(|_| valid_prefixed_id(item.get("attachmentId"), "att_"))
                    .ok_or("turn attachment id is invalid")?;
                if attachment_ids.len() >= 10 || !attachment_ids.insert(id) {
                    return Err("turn attachment limit or uniqueness is invalid");
                }
                sendable = true;
            }
            Some("workspace_reference") => {
                ensure_object_keys(item, &["type", "workspaceId", "relativePath", "kind"])?;
                if phase != 0 {
                    return Err("turn workspace reference order is invalid");
                }
                let workspace_id = item
                    .get("workspaceId")
                    .and_then(Value::as_str)
                    .filter(|_| valid_prefixed_id(item.get("workspaceId"), "ws_"))
                    .ok_or("turn workspace id is invalid")?;
                let path = item
                    .get("relativePath")
                    .and_then(Value::as_str)
                    .filter(|path| valid_relative_reference_path(path))
                    .ok_or("turn workspace path is invalid")?;
                workspace_ids.insert(workspace_id);
                if workspace_ids.len() != 1 || !workspace_references.insert((workspace_id, path)) {
                    return Err("turn workspace reference is duplicated or crosses workspace");
                }
                if !matches!(
                    item.get("kind").and_then(Value::as_str),
                    Some("file" | "directory")
                ) {
                    return Err("turn workspace reference kind is invalid");
                }
                sendable = true;
            }
            Some("skill_reference") => {
                ensure_object_keys(item, &["type", "skillId"])?;
                if phase != 0 {
                    return Err("turn skill reference order is invalid");
                }
                let skill_id = item
                    .get("skillId")
                    .and_then(Value::as_str)
                    .filter(|_| valid_prefixed_id(item.get("skillId"), "skill_"))
                    .ok_or("turn skill id is invalid")?;
                if !skill_ids.insert(skill_id) {
                    return Err("turn skill reference is duplicated");
                }
            }
            _ => return Err("turn content type is invalid"),
        }
    }
    if sendable {
        Ok(())
    } else {
        Err("skill references alone are not sendable")
    }
}

/// 队列内容以紧凑 JSON 实际字节计费，避免多字节路径和结构开销绕过 512 KiB 上限。
fn queued_content_within_budget(value: Option<&Value>) -> bool {
    value.is_some_and(|content| {
        serde_json::to_vec(content).is_ok_and(|bytes| bytes.len() <= 524_288)
    })
}

/// Workspace 引用和搜索结果仅允许规范化相对路径；reparse containment 由 Java 文件系统边界校验。
fn valid_relative_reference_path(path: &str) -> bool {
    let bytes = path.as_bytes();
    let has_drive_prefix = bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
    !path.is_empty()
        && path.chars().count() <= 4_096
        && !path.starts_with('/')
        && !has_drive_prefix
        && !path.contains('\\')
        && !path.chars().any(char::is_control)
        && !path.split('/').any(|segment| segment == "..")
}

/// 在 Fixture Consumer 中只校验 cwd 值而不解析路径；Canonicalization、Trust 与 Reparse Point
/// Policy 的唯一权威仍是 Java。
fn validate_cwd(value: Option<&Value>) -> Result<(), &'static str> {
    let cwd = value
        .and_then(Value::as_str)
        .filter(|cwd| !cwd.is_empty() && cwd.len() <= 4096)
        .ok_or("cwd is invalid")?;
    if cwd.chars().any(char::is_control) {
        return Err("cwd contains control characters");
    }
    Ok(())
}

/// 检查 scope/workspaceId 关系，项目配置必须绑定已注册身份，用户配置禁止夹带能力。
fn validate_config_scope(params: &Value) -> Result<(), &'static str> {
    let scope = require_text(params, "scope")?;
    if !matches!(scope, "user" | "project") {
        return Err("config scope is invalid");
    }
    if scope == "project" {
        if !valid_prefixed_id(params.get("workspaceId"), "ws_") {
            return Err("workspace id is invalid");
        }
    } else if params.get("workspaceId").is_some() {
        return Err("user configuration cannot carry workspace id");
    }
    Ok(())
}

/// 递归应用非 Secret 配置值边界，同时允许有界 Frame 内的普通 Scalar、Array 与 Object。
fn validate_config_value(value: Option<&Value>) -> Result<(), &'static str> {
    let value = value.ok_or("config value is missing")?;
    if serde_json::to_vec(value)
        .map_err(|_| "config value is not serializable")?
        .len()
        > 1_048_576
        || contains_forbidden_secret_key(value, false, false)
    {
        return Err("config value is invalid");
    }
    Ok(())
}

/// 只接受 opaque String Version；首次运行调用方使用显式 `cfg_missing` Marker，
/// 防止 null 绕过 Compare-and-swap Admission。
fn validate_version(value: Option<&Value>) -> Result<(), &'static str> {
    match value {
        Some(Value::String(version)) if is_valid_config_version(version) => Ok(()),
        _ => Err("configuration version is invalid"),
    }
}

/// 镜像 v1 opaque CAS Token Grammar，防止旧 Hash 或 Nullable Token 以虚假首次 Generation
/// 进入 Rust 侧 Request Fixture。
fn is_valid_config_version(version: &str) -> bool {
    version.strip_prefix("cfg_").is_some_and(|suffix| {
        !suffix.is_empty()
            && suffix.len() <= 252
            && suffix
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'-'))
    })
}

/// 完整 Replacement Document 必须在到达 Java Configuration Owner 前通过校验；
/// 通用 JSON Object 被明确视为不充分，以保持配置闭集。
fn validate_config_document(value: Option<&Value>) -> Result<(), &'static str> {
    let document = value.ok_or("repair document is missing")?;
    let object = document
        .as_object()
        .ok_or("repair document is not an object")?;
    ensure_object_keys(
        document,
        &[
            "schema_version",
            "config_revision",
            "default_access_mode",
            "default_provider_id",
            "default_model_id",
            "default_reasoning_level",
            "providers",
            "mcp_servers",
            "skills",
            "subagents",
        ],
    )?;
    if object.get("schema_version").and_then(Value::as_u64) != Some(1)
        || object
            .get("config_revision")
            .and_then(Value::as_u64)
            .is_none()
    {
        return Err("repair document header is invalid");
    }
    if !matches!(
        object.get("default_access_mode").and_then(Value::as_str),
        Some("approval_required" | "full_access")
    ) {
        return Err("permission mode is invalid");
    }
    if object
        .get("default_provider_id")
        .is_none_or(|value| !value.is_null() && !valid_prefixed_id(Some(value), "provider_"))
        || object
            .get("default_model_id")
            .is_none_or(|value| !value.is_null() && !valid_prefixed_id(Some(value), "model_"))
        || object.get("default_reasoning_level").is_none_or(|value| {
            !value.is_null()
                && !matches!(
                    value.as_str(),
                    Some("off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max")
                )
        })
    {
        return Err("default model selection is invalid");
    }
    validate_config_providers(object.get("providers"))?;
    validate_config_subagents(object.get("subagents"))?;
    validate_config_mcp_servers(object.get("mcp_servers"))?;
    validate_config_skills(object.get("skills"))?;
    validate_config_value(Some(document))
}

/// 子智能体策略必须完整；跟随父任务时不得孤立覆盖思考等级，避免跨端产生不同的模型参数。
fn validate_config_subagents(value: Option<&Value>) -> Result<(), &'static str> {
    let value = value.ok_or("subagent policy is missing")?;
    ensure_object_keys(
        value,
        &["enabled", "provider_id", "model_id", "reasoning_level"],
    )?;
    let reasoning = value
        .get("reasoning_level")
        .ok_or("subagent reasoning is missing")?;
    if !reasoning.is_null()
        && !matches!(
            reasoning.as_str(),
            Some("off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max")
        )
    {
        return Err("subagent reasoning is invalid");
    }
    if value.get("enabled").and_then(Value::as_bool).is_none() {
        return Err("subagent enabled is invalid");
    }
    let provider = value
        .get("provider_id")
        .ok_or("subagent provider is missing")?;
    let model = value.get("model_id").ok_or("subagent model is missing")?;
    if provider.is_null() && model.is_null() {
        if !reasoning.is_null() {
            return Err("following parent cannot override reasoning");
        }
        return Ok(());
    }
    if !valid_prefixed_id(Some(provider), "provider_") || !valid_prefixed_id(Some(model), "model_")
    {
        return Err("subagent model selection is invalid");
    }
    Ok(())
}

/// API 是唯一 Wire 路由闭集；Provider 显示名称不得缩小或改写协议选择。
fn valid_provider_api(api: Option<&str>) -> bool {
    matches!(
        api,
        Some("anthropic_messages" | "openai_chat_completions" | "openai_responses")
    )
}

/// 校验有界 Provider 与其嵌套 Model 目录，稳定 ID 不得因显示名变化而重用。
fn validate_config_providers(value: Option<&Value>) -> Result<(), &'static str> {
    let providers = value
        .and_then(Value::as_array)
        .filter(|providers| providers.len() <= 512)
        .ok_or("providers are invalid")?;
    let mut ids = HashSet::with_capacity(providers.len());
    let mut credential_ids = HashSet::with_capacity(providers.len());
    for provider in providers {
        let object = provider.as_object().ok_or("provider is not an object")?;
        ensure_object_keys(
            provider,
            &[
                "provider_id",
                "name",
                "api",
                "base_url",
                "credential_id",
                "network_timeouts",
                "agent_defaults",
                "models",
            ],
        )?;
        if !valid_prefixed_id(object.get("provider_id"), "provider_")
            || !ids.insert(
                object
                    .get("provider_id")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
            )
            || !bounded_string(object.get("name"), 1, 512)
            || !valid_provider_api(object.get("api").and_then(Value::as_str))
            || !bounded_string(object.get("base_url"), 1, 2_048)
            || !valid_prefixed_id(object.get("credential_id"), "cred_")
            || !credential_ids.insert(
                object
                    .get("credential_id")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
            )
        {
            return Err("provider fields are invalid");
        }
        validate_config_network_timeouts(object.get("network_timeouts"))?;
        validate_config_agent_defaults(object.get("agent_defaults"))?;
        validate_config_models(object.get("models"))?;
    }
    Ok(())
}

/// Provider 默认值按嵌套对象校验，防止 Model 配置重复连接与 Agent 约束。
fn validate_config_agent_defaults(value: Option<&Value>) -> Result<(), &'static str> {
    let defaults = value
        .and_then(Value::as_object)
        .ok_or("agent defaults are invalid")?;
    ensure_object_keys(
        value.ok_or("agent defaults are missing")?,
        &["context", "turn_limits"],
    )?;
    validate_config_context(defaults.get("context"))?;
    validate_config_turn_limits(defaults.get("turn_limits"))
}

/// 模型目录只允许稳定 ID、能力与思考档位，不得携带 Provider 凭据或端点。
fn validate_config_models(value: Option<&Value>) -> Result<(), &'static str> {
    let models = value
        .and_then(Value::as_array)
        .filter(|models| !models.is_empty() && models.len() <= 512)
        .ok_or("models are invalid")?;
    let mut ids = HashSet::with_capacity(models.len());
    for model in models {
        let object = model.as_object().ok_or("model is not an object")?;
        ensure_object_keys(
            model,
            &[
                "model_id",
                "name",
                "model",
                "capabilities",
                "reasoning_level_map",
                "default_reasoning_level",
            ],
        )?;
        if !valid_prefixed_id(object.get("model_id"), "model_")
            || !ids.insert(
                object
                    .get("model_id")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
            )
            || !bounded_string(object.get("name"), 1, 512)
            || !bounded_string(object.get("model"), 1, 512)
        {
            return Err("model fields are invalid");
        }
        validate_config_capabilities(object.get("capabilities"))?;
        let efforts = object
            .get("reasoning_level_map")
            .and_then(Value::as_object)
            .filter(|values| values.len() <= 7)
            .ok_or("reasoning efforts are invalid")?;
        if efforts.iter().any(|(level, upstream)| {
            !matches!(
                level.as_str(),
                "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
            ) || !bounded_string(Some(upstream), 1, 64)
        }) {
            return Err("reasoning efforts are invalid");
        }
        if let Some(default) = object.get("default_reasoning_level")
            && !default.is_null()
            && default
                .as_str()
                .is_none_or(|value| !efforts.contains_key(value))
        {
            return Err("default reasoning effort is invalid");
        }
    }
    Ok(())
}

/// 校验 Provider 声明的模型容量，使 Rust consumer 与 v1 schema 使用同一当前字段闭集。
fn validate_config_capabilities(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("model capabilities are invalid")?;
    ensure_object_keys(
        value.ok_or("model capabilities are missing")?,
        &["context_window_tokens", "max_output_tokens"],
    )?;
    if !integer_in_bounds(object.get("context_window_tokens"), 4_096, 4_000_000)
        || !integer_in_bounds(object.get("max_output_tokens"), 1, 1_000_000)
    {
        return Err("model capabilities value is invalid");
    }
    Ok(())
}

/// 校验当前唯一的自动压缩策略开关；闭集外字符预算字段不得进入 v1 consumer。
fn validate_config_context(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("provider context is invalid")?;
    ensure_object_keys(
        value.ok_or("provider context is missing")?,
        &["auto_compact"],
    )?;
    if !matches!(object.get("auto_compact"), Some(Value::Bool(_))) {
        return Err("provider context value is invalid");
    }
    Ok(())
}

/// 校验 v1 Document 中的 Model Round/Tool Call Budget Object，保持预算字段闭集。
fn validate_config_turn_limits(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("provider turn limits are invalid")?;
    ensure_object_keys(
        value.ok_or("provider turn limits are missing")?,
        &["max_model_rounds", "max_tool_calls", "wall_timeout_ms"],
    )?;
    if !integer_in_bounds(object.get("max_model_rounds"), 1, 128)
        || !integer_in_bounds(object.get("max_tool_calls"), 0, 1_024)
        || !integer_in_bounds(object.get("wall_timeout_ms"), 1_000, 86_400_000)
    {
        return Err("provider turn limits value is invalid");
    }
    Ok(())
}

/// 校验显式 Provider Network Timeout Object，避免使用隐式或无界超时。
fn validate_config_network_timeouts(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("provider network timeouts are invalid")?;
    ensure_object_keys(
        value.ok_or("provider network timeouts are missing")?,
        &["connect_timeout_ms", "request_timeout_ms"],
    )?;
    if !integer_in_bounds(object.get("connect_timeout_ms"), 100, 120_000)
        || !integer_in_bounds(object.get("request_timeout_ms"), 1_000, 3_600_000)
    {
        return Err("provider network timeout value is invalid");
    }
    Ok(())
}

/// 校验有界 MCP Server List 及其嵌套 Authentication Union，确保认证形状保持封闭。
fn validate_config_mcp_servers(value: Option<&Value>) -> Result<(), &'static str> {
    let servers = value
        .and_then(Value::as_array)
        .filter(|servers| servers.len() <= 512)
        .ok_or("mcp servers are invalid")?;
    let mut ids = HashSet::with_capacity(servers.len());
    for server in servers {
        let object = server.as_object().ok_or("mcp server is not an object")?;
        ensure_object_keys(
            server,
            &[
                "mcp_id",
                "name",
                "transport",
                "endpoint",
                "args",
                "env",
                "headers",
                "auth",
                "enabled",
            ],
        )?;
        if !valid_prefixed_id(object.get("mcp_id"), "mcp_")
            || !ids.insert(
                object
                    .get("mcp_id")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
            )
            || !bounded_string(object.get("name"), 1, 512)
            || !matches!(
                object.get("transport").and_then(Value::as_str),
                Some("stdio" | "streamable_http")
            )
            || !bounded_string(object.get("endpoint"), 1, 4_096)
            || object.get("enabled").and_then(Value::as_bool).is_none()
        {
            return Err("mcp server fields are invalid");
        }
        validate_string_array(object.get("args"), 128, 4_096)?;
        validate_string_map(object.get("env"), 8_192)?;
        validate_string_map(object.get("headers"), 8_192)?;
        validate_config_mcp_auth(object.get("auth"))?;
    }
    Ok(())
}

/// 校验 MCP Authentication Tagged Union，同时不暴露 Credential Data。
fn validate_config_mcp_auth(value: Option<&Value>) -> Result<(), &'static str> {
    let auth = value
        .and_then(Value::as_object)
        .ok_or("mcp auth is invalid")?;
    let kind = auth
        .get("kind")
        .and_then(Value::as_str)
        .ok_or("mcp auth kind is missing")?;
    match kind {
        "none" => {
            ensure_object_keys(value.ok_or("mcp auth is missing")?, &["kind"])?;
        }
        "env" => {
            ensure_object_keys(
                value.ok_or("mcp auth is missing")?,
                &["kind", "name", "credential_id"],
            )?;
            if !auth
                .get("name")
                .and_then(Value::as_str)
                .is_some_and(|name| {
                    !name.is_empty()
                        && name.len() <= 128
                        && name.chars().enumerate().all(|(index, character)| {
                            character.is_ascii_alphanumeric()
                                && (index > 0 || character.is_ascii_alphabetic())
                                || index == 0 && character == '_'
                                || index > 0 && character == '_'
                        })
                })
                || !valid_prefixed_id(auth.get("credential_id"), "cred_")
            {
                return Err("mcp env auth is invalid");
            }
        }
        "bearer" => {
            ensure_object_keys(
                value.ok_or("mcp auth is missing")?,
                &["kind", "credential_id"],
            )?;
            if !valid_prefixed_id(auth.get("credential_id"), "cred_") {
                return Err("mcp bearer auth is invalid");
            }
        }
        "header" => {
            ensure_object_keys(
                value.ok_or("mcp auth is missing")?,
                &["kind", "name", "credential_id"],
            )?;
            if !bounded_string(auth.get("name"), 1, 128)
                || !valid_prefixed_id(auth.get("credential_id"), "cred_")
            {
                return Err("mcp header auth is invalid");
            }
        }
        _ => return Err("mcp auth kind is invalid"),
    }
    Ok(())
}

/// 校验有界 Skill List 及其严格 Public Projection Field，禁止内部字段越过协议边界。
fn validate_config_skills(value: Option<&Value>) -> Result<(), &'static str> {
    let skills = value
        .and_then(Value::as_array)
        .filter(|skills| skills.len() <= 512)
        .ok_or("skills are invalid")?;
    let mut ids = HashSet::with_capacity(skills.len());
    for skill in skills {
        let object = skill.as_object().ok_or("skill is not an object")?;
        ensure_object_keys(
            skill,
            &["skill_id", "name", "scope", "enabled", "description"],
        )?;
        if !valid_prefixed_id(object.get("skill_id"), "skill_")
            || !ids.insert(
                object
                    .get("skill_id")
                    .and_then(Value::as_str)
                    .unwrap_or_default(),
            )
            || !bounded_string(object.get("name"), 1, 512)
            || !bounded_string(object.get("scope"), 1, 64)
            || object.get("enabled").and_then(Value::as_bool).is_none()
            || !bounded_string(object.get("description"), 0, 8_192)
        {
            return Err("skill fields are invalid");
        }
    }
    Ok(())
}

/// 校验有界 String Array，并保持 Schema maxItems 规则不被通用 Helper 放宽。
fn validate_string_array(
    value: Option<&Value>,
    maximum_items: usize,
    maximum_length: usize,
) -> Result<(), &'static str> {
    let values = value
        .and_then(Value::as_array)
        .filter(|values| values.len() <= maximum_items)
        .ok_or("string array is invalid")?;
    if values
        .iter()
        .any(|value| !bounded_string(Some(value), 0, maximum_length))
    {
        return Err("string array item is invalid");
    }
    Ok(())
}

/// 校验 MCP Environment 或 Header Map 等 String-valued Object，不接受非字符串值。
fn validate_string_map(value: Option<&Value>, maximum_length: usize) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("string map is invalid")?;
    if object
        .values()
        .any(|value| !bounded_string(Some(value), 0, maximum_length))
    {
        return Err("string map value is invalid");
    }
    Ok(())
}

/// 校验带 opaque Prefix 的唯一 Identifier List，避免重复或跨类型身份混入。
fn validate_unique_prefixed_array(
    value: Option<&Value>,
    prefix: &str,
    maximum_items: usize,
) -> Result<(), &'static str> {
    let values = value
        .and_then(Value::as_array)
        .filter(|values| values.len() <= maximum_items)
        .ok_or("identifier array is invalid")?;
    let mut ids = HashSet::with_capacity(values.len());
    for value in values {
        if !valid_prefixed_id(Some(value), prefix)
            || !ids.insert(value.as_str().unwrap_or_default())
        {
            return Err("identifier array item is invalid");
        }
    }
    Ok(())
}

/// 应用当前 Schema 的 String Length Bound，且不对值执行 Coercion。
fn bounded_string(value: Option<&Value>, minimum: usize, maximum: usize) -> bool {
    value
        .and_then(Value::as_str)
        .is_some_and(|text| (minimum..=maximum).contains(&text.chars().count()))
}

/// Task 名称是用户可见身份，除长度外还必须拒绝控制字符和首尾空白，保持三端合同一致。
fn valid_task_name(value: Option<&Value>) -> bool {
    value.and_then(Value::as_str).is_some_and(|text| {
        !text.is_empty()
            && text.chars().count() <= 96
            && text.trim() == text
            && !text.chars().any(char::is_control)
    })
}

/// 侧边任务偏好必须是完整且封闭的执行选择；省略整个对象才表示继承父 Thread。
fn valid_task_create_preferences(value: Option<&Value>) -> bool {
    let Some(object) = value.and_then(Value::as_object) else {
        return false;
    };
    if object.len() != 5
        || !valid_prefixed_id(object.get("providerId"), "provider_")
        || !valid_prefixed_id(object.get("modelId"), "model_")
        || !matches!(
            object.get("accessMode").and_then(Value::as_str),
            Some("approval_required" | "full_access")
        )
        || !matches!(
            object.get("collaborationMode").and_then(Value::as_str),
            Some("default" | "plan")
        )
    {
        return false;
    }
    object.get("reasoningLevel").is_some_and(|value| {
        value.is_null()
            || matches!(
                value.as_str(),
                Some("off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max")
            )
    })
}

/// 幂等键等控制面文本不能包含不可见控制字符，避免三端对同一请求形成不同身份。
fn valid_bounded_text(value: Option<&Value>, minimum: usize, maximum: usize) -> bool {
    value.and_then(Value::as_str).is_some_and(|text| {
        (minimum..=maximum).contains(&text.chars().count()) && !text.chars().any(char::is_control)
    })
}

/// 应用非负 Integer Bound，同时拒绝 Floating-point JSON，避免数值语义漂移。
fn integer_in_bounds(value: Option<&Value>, minimum: u64, maximum: u64) -> bool {
    value
        .and_then(Value::as_u64)
        .is_some_and(|number| (minimum..=maximum).contains(&number))
}

/// 校验 v1 Wire Schema 使用的 Prefixed Identifier，保持各身份命名空间隔离。
fn valid_prefixed_id(value: Option<&Value>, prefix: &str) -> bool {
    let Some(id) = value.and_then(Value::as_str) else {
        return false;
    };
    let Some(suffix) = id.strip_prefix(prefix) else {
        return false;
    };
    !suffix.is_empty()
        && suffix.len() <= 95
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// 校验所有服务端语义事件共享的实例身份、事件身份、全局序号、时间和 generation。
fn validate_event_metadata(params: &Value) -> Result<(), &'static str> {
    if !valid_prefixed_id(params.get("serverInstanceId"), "srv_")
        || !valid_prefixed_id(params.get("eventId"), "evt_")
        || !integer_in_bounds(params.get("sequence"), 1, 9_007_199_254_740_991)
        || !integer_in_bounds(params.get("generation"), 1, 9_007_199_254_740_991)
    {
        return Err("event metadata is invalid");
    }
    require_text(params, "occurredAt")?;
    Ok(())
}

/// Goal mutation 必须携带同一套 CAS 与幂等边界，避免任一快捷命令绕过版本批准。
fn validate_goal_mutation(params: &Value) -> Result<(), &'static str> {
    if !valid_prefixed_id(params.get("goalId"), "goal_")
        || !integer_in_bounds(params.get("expectedGoalRevision"), 0, 9_007_199_254_740_991)
        || !valid_bounded_text(params.get("idempotencyKey"), 1, 128)
    {
        return Err("goal mutation boundary is invalid");
    }
    Ok(())
}

/// Standalone Plan 使用 Thread owner 与独立 CAS，不能复用 Goal revision 字段形成隐式归属。
fn validate_plan_mutation(params: &Value) -> Result<(), &'static str> {
    if !valid_prefixed_id(params.get("threadId"), "thr_")
        || !valid_prefixed_id(params.get("planId"), "plan_")
        || !integer_in_bounds(params.get("expectedPlanRevision"), 0, 9_007_199_254_740_991)
        || !valid_bounded_text(params.get("idempotencyKey"), 1, 128)
    {
        return Err("plan mutation identity is invalid");
    }
    Ok(())
}

/// 批准、执行和 Goal attach 都绑定同一冻结 revision/hash，缺任一字段均不得进入执行路径。
fn validate_plan_binding(params: &Value) -> Result<(), &'static str> {
    if !valid_prefixed_id(params.get("planRevisionId"), "planrev_")
        || !params
            .get("planHash")
            .and_then(Value::as_str)
            .is_some_and(|hash| {
                hash.len() == 64
                    && hash
                        .bytes()
                        .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
            })
    {
        return Err("plan approval identity is invalid");
    }
    Ok(())
}

/// Goal 事件共享 revision/sequence fence，并对状态投影使用严格字段闭集。
fn validate_goal_notification(method: &str, params: &Value) -> Result<(), &'static str> {
    let mut allowed = vec![
        "serverInstanceId",
        "eventId",
        "sequence",
        "generation",
        "occurredAt",
        "goalId",
        "goalRevision",
        "eventSequence",
    ];
    match method {
        "goal/changed" => allowed.push("goal"),
        "goal/activity" => allowed.push("activity"),
        _ => return Err("goal notification method is invalid"),
    }
    ensure_object_keys(params, &allowed)?;
    validate_event_metadata(params)?;
    if !valid_prefixed_id(params.get("goalId"), "goal_")
        || !integer_in_bounds(params.get("goalRevision"), 1, 9_007_199_254_740_991)
        || !integer_in_bounds(params.get("eventSequence"), 1, 9_007_199_254_740_991)
    {
        return Err("goal notification fence is invalid");
    }
    if method == "goal/changed" {
        let goal = params.get("goal").ok_or("goal projection is missing")?;
        ensure_object_keys(
            goal,
            &[
                "goalId",
                "owner",
                "objective",
                "goalDefinitionRevision",
                "acceptanceCriteria",
                "status",
                "phase",
                "revision",
                "planLink",
                "currentRunId",
                "currentStepId",
                "completedRequiredSteps",
                "totalRequiredSteps",
                "attentionReason",
                "latestEvaluation",
                "createdAt",
                "updatedAt",
                "achievedAt",
                "stoppedAt",
            ],
        )?;
        if goal.get("goalId") != params.get("goalId")
            || goal.get("revision") != params.get("goalRevision")
        {
            return Err("goal changed projection is inconsistent");
        }
    }
    Ok(())
}

/// Interaction 通知只携带对账水位和请求身份，答案正文必须通过 interaction/read 获取。
fn validate_interaction_notification(params: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        params,
        &[
            "serverInstanceId",
            "eventId",
            "sequence",
            "occurredAt",
            "generation",
            "threadId",
            "requestId",
            "requestRevision",
            "eventSequence",
            "kind",
        ],
    )?;
    validate_event_metadata(params)?;
    if !valid_prefixed_id(params.get("threadId"), "thr_")
        || !valid_prefixed_id(params.get("requestId"), "interaction_")
        || !integer_in_bounds(params.get("requestRevision"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(params.get("eventSequence"), 0, 9_007_199_254_740_991)
        || !matches!(
            params.get("kind").and_then(Value::as_str),
            Some("created" | "draft_changed" | "answered" | "cancelled" | "superseded")
        )
    {
        return Err("interaction notification is invalid");
    }
    Ok(())
}

/// Plan 通知保留轻量状态行，但要求 owner、Plan revision 和 progress 完整一致。
fn validate_plan_notification(params: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        params,
        &[
            "serverInstanceId",
            "eventId",
            "sequence",
            "occurredAt",
            "generation",
            "ownerThreadId",
            "planId",
            "planRevision",
            "eventSequence",
            "plan",
            "progress",
        ],
    )?;
    validate_event_metadata(params)?;
    if !valid_prefixed_id(params.get("ownerThreadId"), "thr_")
        || !valid_prefixed_id(params.get("planId"), "plan_")
        || !integer_in_bounds(params.get("planRevision"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(params.get("eventSequence"), 0, 9_007_199_254_740_991)
    {
        return Err("plan notification identity is invalid");
    }
    let plan = params
        .get("plan")
        .ok_or("plan notification projection is missing")?;
    ensure_object_keys(
        plan,
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
    )?;
    if plan.get("planId") != params.get("planId")
        || plan
            .get("owner")
            .and_then(|owner| owner.get("kind"))
            .and_then(Value::as_str)
            != Some("thread")
        || plan.pointer("/owner/threadId") != params.get("ownerThreadId")
    {
        return Err("plan notification projection is inconsistent");
    }
    let progress = params
        .get("progress")
        .ok_or("plan notification progress is missing")?;
    ensure_object_keys(
        progress,
        &[
            "currentStepId",
            "currentStepTitle",
            "completedRequiredSteps",
            "totalRequiredSteps",
        ],
    )?;
    if !progress
        .get("currentStepId")
        .is_some_and(|id| id.is_null() || valid_prefixed_id(Some(id), "step_"))
        || !progress
            .get("currentStepTitle")
            .is_some_and(|title| title.is_null() || bounded_string(Some(title), 0, 240))
        || !integer_in_bounds(
            progress.get("completedRequiredSteps"),
            0,
            9_007_199_254_740_991,
        )
        || !integer_in_bounds(progress.get("totalRequiredSteps"), 0, 9_007_199_254_740_991)
    {
        return Err("plan notification progress is invalid");
    }
    Ok(())
}

/// 在公共事件元数据之上校验工作区、Thread、Turn 和 revision 的完整关联链。
fn validate_turn_event_metadata(params: &Value) -> Result<(), &'static str> {
    validate_event_metadata(params)?;
    if !valid_prefixed_id(params.get("workspaceId"), "ws_")
        || !valid_prefixed_id(params.get("threadId"), "thr_")
        || !valid_prefixed_id(params.get("turnId"), "turn_")
        || params
            .get("threadRevision")
            .and_then(Value::as_u64)
            .is_none()
    {
        return Err("turn event metadata is invalid");
    }
    Ok(())
}

/// Golden consumer 锁定队列对象的身份、容量和条目闭集；数组顺序保持原样，不另行排序。
fn valid_input_queue(value: Option<&Value>, expected_turn_id: Option<&Value>) -> bool {
    let Some(queue) = value.and_then(Value::as_object) else {
        return false;
    };
    if ensure_object_keys(
        &Value::Object(queue.clone()),
        &["turnId", "revision", "accepting", "items"],
    )
    .is_err()
        || queue.get("turnId") != expected_turn_id
        || !integer_in_bounds(queue.get("revision"), 0, 9_007_199_254_740_991)
        || queue.get("accepting").and_then(Value::as_bool).is_none()
    {
        return false;
    }
    queue
        .get("items")
        .and_then(Value::as_array)
        .is_some_and(|items| {
            items.len() <= 8
                && items
                    .iter()
                    .all(|item| valid_queued_input(Some(item), expected_turn_id))
        })
}

/// 单条队列输入必须绑定同一 Turn、复用统一 content，并形成 pending/needs_attention 的严格闭环。
fn valid_queued_input(value: Option<&Value>, expected_turn_id: Option<&Value>) -> bool {
    let Some(item) = value.and_then(Value::as_object) else {
        return false;
    };
    ensure_object_keys(
        &Value::Object(item.clone()),
        &[
            "inputId",
            "turnId",
            "content",
            "attachments",
            "kind",
            "status",
            "issue",
            "inputRevision",
            "createdAt",
        ],
    )
    .is_ok()
        && valid_prefixed_id(item.get("inputId"), "input_")
        && item.get("turnId") == expected_turn_id
        && validate_turn_content(item.get("content")).is_ok()
        && validate_attachment_summaries(item.get("content"), item.get("attachments")).is_ok()
        && queued_content_within_budget(item.get("content"))
        && matches!(
            item.get("kind").and_then(Value::as_str),
            Some("follow_up" | "steering")
        )
        && valid_queued_input_recovery_state(item)
        && integer_in_bounds(item.get("inputRevision"), 1, 9_007_199_254_740_991)
        && item.get("createdAt").and_then(Value::as_str).is_some()
}

/// 附件摘要必须与 content 中的 attachment block 同序同 ID，防止预览授权指向另一资源。
fn validate_attachment_summaries(
    content: Option<&Value>,
    summaries: Option<&Value>,
) -> Result<(), &'static str> {
    let attachment_ids = content
        .and_then(Value::as_array)
        .ok_or("attachment content is invalid")?
        .iter()
        .filter(|block| block.get("type").and_then(Value::as_str) == Some("attachment"))
        .map(|block| block.get("attachmentId").and_then(Value::as_str))
        .collect::<Vec<_>>();
    let summaries = summaries
        .and_then(Value::as_array)
        .filter(|items| items.len() <= 10)
        .ok_or("attachment summaries are invalid")?;
    if attachment_ids.len() != summaries.len() {
        return Err("attachment summary count is invalid");
    }
    for (attachment_id, summary) in attachment_ids.into_iter().zip(summaries) {
        ensure_object_keys(
            summary,
            &[
                "attachmentId",
                "displayName",
                "sizeBytes",
                "mediaKind",
                "mediaType",
            ],
        )?;
        if attachment_id.is_none()
            || summary.get("attachmentId").and_then(Value::as_str) != attachment_id
            || !bounded_string(summary.get("displayName"), 1, 512)
            || !integer_in_bounds(summary.get("sizeBytes"), 0, 104_857_600)
            || !matches!(
                summary.get("mediaKind").and_then(Value::as_str),
                Some("text" | "image" | "pdf" | "binary")
            )
            || !bounded_string(summary.get("mediaType"), 3, 128)
        {
            return Err("attachment summary is invalid");
        }
    }
    Ok(())
}

/// pending 不得携带问题；needs_attention 必须携带稳定且可展示的引用、Skill、附件或容量错误。
fn valid_queued_input_recovery_state(item: &serde_json::Map<String, Value>) -> bool {
    match item.get("status").and_then(Value::as_str) {
        Some("pending") => item.get("issue").is_some_and(Value::is_null),
        Some("needs_attention") => {
            let Some(issue) = item.get("issue") else {
                return false;
            };
            ensure_object_keys(issue, &["errorCode", "message", "retryable"]).is_ok()
                && matches!(
                    issue.get("errorCode").and_then(Value::as_str),
                    Some(
                        "WORKSPACE_REFERENCE_INVALID"
                            | "SKILL_UNAVAILABLE"
                            | "SKILL_LOAD_FAILED"
                            | "CONTENT_TOO_LARGE"
                            | "ATTACHMENT_UNAVAILABLE"
                    )
                )
                && bounded_string(issue.get("message"), 1, 512)
                && !issue
                    .get("message")
                    .and_then(Value::as_str)
                    .is_some_and(|message| {
                        message.contains('\0') || message.contains('\r') || message.contains('\n')
                    })
                && issue.get("retryable").and_then(Value::as_bool).is_some()
        }
        _ => false,
    }
}

/// Context 生命周期与 Turn 事件共享 Thread fence，但手动压缩用显式 null turnId，不能把
/// 缺失字段当作兼容别名。
fn validate_context_event_metadata(params: &Value) -> Result<(), &'static str> {
    validate_event_metadata(params)?;
    if !valid_prefixed_id(params.get("workspaceId"), "ws_")
        || !valid_prefixed_id(params.get("threadId"), "thr_")
        || !params
            .get("turnId")
            .is_some_and(|turn_id| turn_id.is_null() || valid_prefixed_id(Some(turn_id), "turn_"))
        || !integer_in_bounds(params.get("threadRevision"), 0, 9_007_199_254_740_991)
    {
        return Err("context event metadata is invalid");
    }
    Ok(())
}

/// 校验 started/compacted/failed 的状态相关字段，保证只有 committed 事件携带 checkpoint
/// 与 after Token，失败只携带冻结机器码。
fn validate_context_notification(method: &str, params: &Value) -> Result<(), &'static str> {
    let mut allowed = vec![
        "serverInstanceId",
        "eventId",
        "sequence",
        "occurredAt",
        "generation",
        "workspaceId",
        "threadId",
        "turnId",
        "threadRevision",
        "compactionId",
        "trigger",
        "sourceRevision",
        "inputTokensBefore",
        "inputTokensAfter",
        "strategyVersion",
    ];
    match method {
        "context/compacted" => allowed.push("checkpointId"),
        "context/compaction-failed" => allowed.push("errorCode"),
        "context/compaction-started" => {}
        _ => return Err("context notification method is invalid"),
    }
    ensure_object_keys(params, &allowed)?;
    validate_context_event_metadata(params)?;
    if !valid_prefixed_id(params.get("compactionId"), "cmp_")
        || !matches!(
            params.get("trigger").and_then(Value::as_str),
            Some("automatic" | "manual" | "overflow_recovery")
        )
        || !integer_in_bounds(params.get("sourceRevision"), 0, 9_007_199_254_740_991)
        || params.get("strategyVersion").and_then(Value::as_str) != Some("ja-context-v1")
        || params
            .get("sourceRevision")
            .and_then(Value::as_u64)
            .zip(params.get("threadRevision").and_then(Value::as_u64))
            .is_none_or(|(source, current)| source > current)
    {
        return Err("context notification identity is invalid");
    }
    match method {
        "context/compaction-started" => {
            if !integer_in_bounds(params.get("inputTokensBefore"), 0, 9_007_199_254_740_991)
                || !params.get("inputTokensAfter").is_some_and(Value::is_null)
            {
                return Err("context started tokens are invalid");
            }
        }
        "context/compacted" => {
            let before = params
                .get("inputTokensBefore")
                .and_then(Value::as_u64)
                .ok_or("context compacted before tokens are invalid")?;
            let after = params
                .get("inputTokensAfter")
                .and_then(Value::as_u64)
                .ok_or("context compacted after tokens are invalid")?;
            if before > 9_007_199_254_740_991
                || after >= before
                || !valid_prefixed_id(params.get("checkpointId"), "checkpoint_")
            {
                return Err("context compacted result is invalid");
            }
        }
        "context/compaction-failed" => {
            if !params.get("inputTokensBefore").is_some_and(|value| {
                value.is_null() || integer_in_bounds(Some(value), 0, 9_007_199_254_740_991)
            }) || !params.get("inputTokensAfter").is_some_and(Value::is_null)
                || !matches!(
                    params.get("errorCode").and_then(Value::as_str),
                    Some(
                        "THREAD_NOT_FOUND"
                            | "CONFLICT"
                            | "THREAD_BUSY"
                            | "SUMMARY_FAILURE"
                            | "CONTEXT_LIMIT"
                            | "CANCELLED"
                            | "INVALID_STATE"
                    )
                )
            {
                return Err("context failure is invalid");
            }
        }
        _ => return Err("context notification method is invalid"),
    }
    Ok(())
}

/// 校验运行时字段、终态词汇和封闭的通知集合；标题来源包含 admission 持有的 placeholder。
fn validate_notification(method: &str, params: &Value) -> Result<(), &'static str> {
    if method == "runtime/initialized" {
        ensure_object_keys(params, &["readyToken"])?;
        let token = require_text(params, "readyToken")?;
        return (token.len() == 32 && token.bytes().all(|byte| byte.is_ascii_hexdigit()))
            .then_some(())
            .ok_or("ready token is invalid");
    }
    if method == "runtime/status-changed" {
        ensure_object_keys(
            params,
            &[
                "serverInstanceId",
                "eventId",
                "sequence",
                "occurredAt",
                "status",
                "generation",
                "features",
                "readyToken",
                "reason",
            ],
        )?;
        validate_event_metadata(params)?;
        let status = require_text(params, "status")?;
        let generation = params
            .get("generation")
            .and_then(Value::as_u64)
            .filter(|number| *number >= 1);
        let ready_token = params.get("readyToken").and_then(Value::as_str);
        if params.get("features")
            != Some(&serde_json::json!([
                "task_threads_v1",
                "plan_goal_v1",
                "interaction_v1"
            ]))
        {
            return Err("runtime feature capability is not the v1 closure");
        }
        let valid_ready_token = ready_token.is_some_and(|token| {
            token.len() == 32
                && token
                    .bytes()
                    .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
        });
        let reason = params.get("reason").and_then(Value::as_str);
        if generation.is_none()
            || !match status {
                "starting" => reason == Some("initialize") && ready_token.is_none(),
                "ready" => reason.is_none() && valid_ready_token,
                "shutting_down" => {
                    matches!(reason, Some("user_requested" | "host_shutdown"))
                        && ready_token.is_none()
                }
                "stopped" => reason == Some("shutdown_complete") && ready_token.is_none(),
                "failed" => reason == Some("runtime_lifecycle") && ready_token.is_none(),
                _ => false,
            }
        {
            return Err("runtime generation is invalid");
        }
        return Ok(());
    }
    if matches!(method, "goal/changed" | "goal/activity") {
        return validate_goal_notification(method, params);
    }
    if method == "interaction/changed" {
        return validate_interaction_notification(params);
    }
    if method == "plan/changed" {
        return validate_plan_notification(params);
    }
    if matches!(method, "interaction/changed" | "plan/changed") {
        return validate_plan_interaction_notification(method, params);
    }
    if method == "configuration/changed" {
        ensure_object_keys(
            params,
            &[
                "serverInstanceId",
                "eventId",
                "sequence",
                "occurredAt",
                "generation",
                "scope",
                "version",
                "workspaceId",
            ],
        )?;
        validate_event_metadata(params)?;
        let scope = require_text(params, "scope")?;
        if !matches!(scope, "user" | "project") {
            return Err("config change scope is invalid");
        }
        if scope == "project" {
            if !valid_prefixed_id(params.get("workspaceId"), "ws_") {
                return Err("project config change workspace is invalid");
            }
        } else if params.get("workspaceId").is_some() {
            return Err("user config change cannot carry workspace");
        }
        validate_version(params.get("version"))?;
        return Ok(());
    }
    if method == "thread/metadata-changed" {
        ensure_object_keys(
            params,
            &[
                "serverInstanceId",
                "eventId",
                "sequence",
                "occurredAt",
                "generation",
                "workspaceId",
                "threadId",
                "revision",
                "title",
                "titleSource",
            ],
        )?;
        validate_event_metadata(params)?;
        if !valid_prefixed_id(params.get("workspaceId"), "ws_")
            || !valid_prefixed_id(params.get("threadId"), "thr_")
            || !integer_in_bounds(params.get("revision"), 0, 9_007_199_254_740_991)
            || !bounded_string(params.get("title"), 1, 512)
            || !matches!(
                params.get("titleSource").and_then(Value::as_str),
                Some("placeholder" | "auto" | "manual")
            )
        {
            return Err("thread metadata change is invalid");
        }
        return Ok(());
    }
    if method == "turn/messages_received" {
        return validate_thread_message_notification(params);
    }
    if !EVENT_METHODS.contains(&method) {
        return Err("notification method is unknown");
    }
    if matches!(
        method,
        "context/compaction-started" | "context/compacted" | "context/compaction-failed"
    ) {
        return validate_context_notification(method, params);
    }
    if matches!(
        method,
        "task/activity" | "task/progress" | "task/mailbox-changed"
    ) {
        return validate_task_notification(method, params);
    }
    if method == "turn/input-queue-changed" {
        ensure_object_keys(
            params,
            &[
                "serverInstanceId",
                "eventId",
                "sequence",
                "occurredAt",
                "generation",
                "workspaceId",
                "threadId",
                "turnId",
                "inputQueue",
            ],
        )?;
        validate_event_metadata(params)?;
        if !valid_prefixed_id(params.get("workspaceId"), "ws_")
            || !valid_prefixed_id(params.get("threadId"), "thr_")
            || !valid_prefixed_id(params.get("turnId"), "turn_")
            || !valid_input_queue(params.get("inputQueue"), params.get("turnId"))
        {
            return Err("input queue change is invalid");
        }
        return Ok(());
    }
    if matches!(
        method,
        "assistant/text-delta" | "assistant/reasoning-summary-delta"
    ) {
        ensure_object_keys(
            params,
            &[
                "serverInstanceId",
                "eventId",
                "sequence",
                "occurredAt",
                "generation",
                "workspaceId",
                "threadId",
                "turnId",
                "threadRevision",
                "streamSeq",
                "text",
            ],
        )?;
        validate_turn_event_metadata(params)?;
        let stream_seq = params
            .get("streamSeq")
            .and_then(Value::as_u64)
            .filter(|value| *value >= 1)
            .ok_or("delta stream sequence is invalid")?;
        let text = require_text(params, "text")?;
        if stream_seq > 1_000_000 || text.len() > 1_048_576 {
            return Err("delta payload is invalid");
        }
        return Ok(());
    }
    if method == "tool/started" {
        ensure_object_keys(
            params,
            [
                "threadId",
                "turnId",
                "threadRevision",
                "serverInstanceId",
                "eventId",
                "sequence",
                "occurredAt",
                "generation",
                "workspaceId",
                "callId",
                "ordinal",
            ]
            .as_slice(),
        )?;
        validate_turn_event_metadata(params)?;
        if !valid_prefixed_id(params.get("callId"), "call_")
            || params
                .get("ordinal")
                .and_then(Value::as_u64)
                .is_none_or(|ordinal| ordinal > 1_023)
        {
            return Err("tool started correlation is invalid");
        }
        return Ok(());
    }
    if method == "tool/batch-committed" {
        ensure_object_keys(
            params,
            [
                "threadId",
                "turnId",
                "threadRevision",
                "serverInstanceId",
                "eventId",
                "sequence",
                "occurredAt",
                "generation",
                "workspaceId",
                "results",
            ]
            .as_slice(),
        )?;
        validate_turn_event_metadata(params)?;
        if !valid_tool_batch(params)? {
            return Err("tool batch is invalid");
        }
        return Ok(());
    }
    let mut allowed = vec![
        "serverInstanceId",
        "eventId",
        "sequence",
        "generation",
        "workspaceId",
        "threadId",
        "turnId",
        "occurredAt",
        "threadRevision",
    ];
    match method {
        "turn/state-changed" => allowed.extend(["from", "to"]),
        "assistant/model-step-committed" => allowed.extend([
            "messageId",
            "text",
            "modelRound",
            "reasoningSummary",
            "usage",
            "toolCalls",
        ]),
        "approval/requested" => allowed.extend([
            "approvalId",
            "callId",
            "toolName",
            "reason",
            "expiresAt",
            "from",
            "to",
        ]),
        "approval/resolved" => allowed.extend(["approvalId", "decision", "from", "to"]),
        "workspace/dirty" => allowed.extend(["dirty", "reason"]),
        "turn/input-consumed" => {
            allowed.extend(["input", "userItem", "inputQueue", "assistantSettlement"])
        }
        "turn/terminal" => allowed.extend([
            "state",
            "summary",
            "finalMessage",
            "usage",
            "errorCode",
            "errorMessage",
            "changeSet",
        ]),
        _ => return Err("notification method is unknown"),
    }
    ensure_object_keys(params, &allowed)?;
    validate_turn_event_metadata(params)?;
    if method == "turn/input-consumed"
        && (!valid_queued_input(params.get("input"), params.get("turnId"))
            || !valid_input_queue(params.get("inputQueue"), params.get("turnId"))
            || !params.get("userItem").is_some_and(|item| {
                valid_prefixed_id(item.get("itemId"), "item_")
                    && item.get("turnId") == params.get("turnId")
                    && item.get("kind").and_then(Value::as_str) == Some("user_input")
                    && ensure_object_keys(
                        item,
                        &[
                            "itemId",
                            "createdAt",
                            "turnId",
                            "kind",
                            "content",
                            "attachments",
                        ],
                    )
                    .is_ok()
                    && validate_turn_content(item.get("content")).is_ok()
                    && validate_attachment_summaries(item.get("content"), item.get("attachments"))
                        .is_ok()
            })
            || params.get("input").and_then(|input| input.get("content"))
                != params.get("userItem").and_then(|item| item.get("content"))
            || params
                .get("input")
                .and_then(|input| input.get("attachments"))
                != params
                    .get("userItem")
                    .and_then(|item| item.get("attachments")))
    {
        return Err("consumed input is invalid");
    }
    if method == "turn/terminal" && !valid_terminal(params)? {
        return Err("terminal state is invalid");
    }
    if method == "turn/state-changed" {
        let from = require_text(params, "from")?;
        let to = require_text(params, "to")?;
        if !matches!(
            (from, to),
            (
                "queued",
                "running" | "suspended" | "completed" | "failed" | "cancelled"
            ) | (
                "running",
                "waiting_approval" | "suspended" | "completed" | "failed" | "cancelled"
            ) | (
                "waiting_approval",
                "running" | "suspended" | "completed" | "failed" | "cancelled"
            ) | ("suspended", "queued" | "cancelled")
        ) {
            return Err("turn state transition is invalid");
        }
    }
    if method == "assistant/model-step-committed" && !valid_model_step(params)? {
        return Err("model step is invalid");
    }
    if method == "tool/batch-committed" && !valid_tool_batch(params)? {
        return Err("tool batch is invalid");
    }
    if method == "approval/requested"
        && (require_text(params, "from")? != "running"
            || require_text(params, "to")? != "waiting_approval")
    {
        return Err("approval request transition is invalid");
    }
    if method == "approval/resolved"
        && (require_text(params, "from")? != "waiting_approval"
            || require_text(params, "to")? != "running")
    {
        return Err("approval resolution transition is invalid");
    }
    Ok(())
}

/// 交互与 Plan 事件共享服务端序号，但各自的资源身份和公开投影保持隔离，避免事件串线。
fn validate_plan_interaction_notification(
    method: &str,
    params: &Value,
) -> Result<(), &'static str> {
    validate_event_metadata(params)?;
    match method {
        "interaction/changed" => {
            ensure_object_keys(
                params,
                &[
                    "serverInstanceId",
                    "eventId",
                    "sequence",
                    "occurredAt",
                    "generation",
                    "threadId",
                    "requestId",
                    "requestRevision",
                    "eventSequence",
                    "kind",
                ],
            )?;
            if !valid_prefixed_id(params.get("threadId"), "thr_")
                || !valid_prefixed_id(params.get("requestId"), "interaction_")
                || !integer_in_bounds(params.get("requestRevision"), 0, 9_007_199_254_740_991)
                || !integer_in_bounds(params.get("eventSequence"), 0, 9_007_199_254_740_991)
                || !matches!(
                    params.get("kind").and_then(Value::as_str),
                    Some("created" | "draft_changed" | "answered" | "cancelled" | "superseded")
                )
            {
                return Err("interaction change notification is invalid");
            }
        }
        "plan/changed" => {
            ensure_object_keys(
                params,
                &[
                    "serverInstanceId",
                    "eventId",
                    "sequence",
                    "occurredAt",
                    "generation",
                    "ownerThreadId",
                    "planId",
                    "planRevision",
                    "eventSequence",
                    "plan",
                    "progress",
                ],
            )?;
            if !valid_prefixed_id(params.get("ownerThreadId"), "thr_")
                || !valid_prefixed_id(params.get("planId"), "plan_")
                || !integer_in_bounds(params.get("planRevision"), 0, 9_007_199_254_740_991)
                || !integer_in_bounds(params.get("eventSequence"), 0, 9_007_199_254_740_991)
            {
                return Err("plan change notification fence is invalid");
            }
            validate_plan_changed_projection(
                params.get("plan").ok_or("plan projection is missing")?,
                params.get("planId"),
            )?;
            let progress = params.get("progress").ok_or("plan progress is missing")?;
            ensure_object_keys(
                progress,
                &[
                    "currentStepId",
                    "currentStepTitle",
                    "completedRequiredSteps",
                    "totalRequiredSteps",
                ],
            )?;
            if !progress
                .get("currentStepId")
                .is_some_and(|value| value.is_null() || valid_prefixed_id(Some(value), "step_"))
                || !progress
                    .get("currentStepTitle")
                    .is_some_and(|value| value.is_null() || bounded_string(Some(value), 1, 240))
                || !integer_in_bounds(progress.get("completedRequiredSteps"), 0, 256)
                || !integer_in_bounds(progress.get("totalRequiredSteps"), 0, 256)
                || progress
                    .get("completedRequiredSteps")
                    .and_then(Value::as_u64)
                    > progress.get("totalRequiredSteps").and_then(Value::as_u64)
            {
                return Err("plan progress is invalid");
            }
        }
        _ => return Err("plan interaction notification is invalid"),
    }
    Ok(())
}

/// Plan changed 事件只携带公开聚合摘要，保持 owner、active revision/run 与状态的基本一致性。
fn validate_plan_changed_projection(
    plan: &Value,
    expected_plan_id: Option<&Value>,
) -> Result<(), &'static str> {
    ensure_object_keys(
        plan,
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
    )?;
    if plan.get("planId") != expected_plan_id
        || !valid_prefixed_id(plan.get("planId"), "plan_")
        || !bounded_string(plan.get("objective"), 1, 32_768)
        || !matches!(
            plan.get("status").and_then(Value::as_str),
            Some(
                "draft"
                    | "awaiting_approval"
                    | "approved"
                    | "executing"
                    | "verifying"
                    | "paused"
                    | "completed"
                    | "stopped"
            )
        )
        || !integer_in_bounds(plan.get("revision"), 0, 9_007_199_254_740_991)
        || !plan
            .get("activePlanRevisionId")
            .is_some_and(|value| value.is_null() || valid_prefixed_id(Some(value), "planrev_"))
        || !plan
            .get("activeRunId")
            .is_some_and(|value| value.is_null() || valid_prefixed_id(Some(value), "run_"))
        || !bounded_string(plan.get("createdAt"), 1, 64)
        || !bounded_string(plan.get("updatedAt"), 1, 64)
    {
        return Err("plan changed projection is invalid");
    }
    let owner = plan.get("owner").ok_or("plan owner is missing")?;
    ensure_object_keys(owner, &["kind", "threadId"])?;
    if owner.get("kind").and_then(Value::as_str) != Some("thread")
        || !valid_prefixed_id(owner.get("threadId"), "thr_")
    {
        return Err("plan owner is invalid");
    }
    Ok(())
}

/// 三类 Task 通知共享 lineage/revision 基座，但只有 activity 和 mailbox 是不可丢的持久事实。
fn validate_task_notification(method: &str, params: &Value) -> Result<(), &'static str> {
    let mut allowed = vec![
        "serverInstanceId",
        "eventId",
        "sequence",
        "occurredAt",
        "generation",
        "rootThreadId",
        "taskThreadId",
        "taskRevision",
    ];
    match method {
        "task/activity" => allowed.extend(["activity", "task"]),
        "task/progress" => allowed.extend(["observationId", "progressRevision", "safeSummary"]),
        "task/mailbox-changed" => allowed.extend(["mailboxSequence", "unreadCount"]),
        _ => return Err("task notification method is invalid"),
    }
    ensure_object_keys(params, &allowed)?;
    validate_event_metadata(params)?;
    if !valid_prefixed_id(params.get("rootThreadId"), "thr_")
        || !valid_prefixed_id(params.get("taskThreadId"), "thr_")
        || !integer_in_bounds(params.get("taskRevision"), 0, 9_007_199_254_740_991)
    {
        return Err("task notification identity is invalid");
    }
    match method {
        "task/activity" => {
            let activity = params.get("activity").ok_or("task activity is missing")?;
            let task = params.get("task").ok_or("task summary is missing")?;
            validate_task_activity(activity)?;
            validate_task_summary(task)?;
            if activity.get("rootThreadId") != params.get("rootThreadId")
                || activity.get("taskThreadId") != params.get("taskThreadId")
                || task.get("taskThreadId") != params.get("taskThreadId")
                || task.get("rootThreadId") != params.get("rootThreadId")
                || task.get("revision") != params.get("taskRevision")
                || activity.get("activitySequence") != task.get("latestActivitySequence")
                || activity
                    .get("summary")
                    .and_then(|summary| summary.get("text"))
                    != task.get("latestSafeSummary")
            {
                return Err("task activity notification is inconsistent");
            }
        }
        "task/progress" => {
            if !valid_prefixed_id(params.get("observationId"), "observe_")
                || !integer_in_bounds(params.get("progressRevision"), 0, 9_007_199_254_740_991)
                || !bounded_string(params.get("safeSummary"), 0, 32_768)
            {
                return Err("task progress is invalid");
            }
        }
        "task/mailbox-changed" => {
            if !integer_in_bounds(params.get("mailboxSequence"), 1, 9_007_199_254_740_991)
                || !integer_in_bounds(params.get("unreadCount"), 0, 9_007_199_254_740_991)
            {
                return Err("task mailbox invalidation is invalid");
            }
        }
        _ => unreachable!(),
    }
    Ok(())
}

/// 跨会话消息只携带冻结的来源身份和纯文本；批次必须绑定同一 Turn，避免通信内容
/// 伪装成当前用户输入或在重试时以重复 item 进入 Timeline。
fn validate_thread_message_notification(params: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        params,
        &[
            "serverInstanceId",
            "eventId",
            "sequence",
            "occurredAt",
            "generation",
            "workspaceId",
            "threadId",
            "turnId",
            "threadRevision",
            "items",
        ],
    )?;
    validate_event_metadata(params)?;
    if !valid_protocol_timestamp(
        params
            .get("occurredAt")
            .and_then(Value::as_str)
            .unwrap_or_default(),
    ) || !valid_prefixed_id(params.get("workspaceId"), "ws_")
        || !valid_prefixed_id(params.get("threadId"), "thr_")
        || !valid_prefixed_id(params.get("turnId"), "turn_")
        || !integer_in_bounds(params.get("threadRevision"), 0, 9_007_199_254_740_991)
    {
        return Err("thread message event metadata is invalid");
    }
    let turn_id = params
        .get("turnId")
        .ok_or("thread message turn is missing")?;
    let items = params
        .get("items")
        .and_then(Value::as_array)
        .filter(|items| (1..=256).contains(&items.len()))
        .ok_or("thread message items are invalid")?;
    let mut item_ids = HashSet::with_capacity(items.len());
    for item in items {
        ensure_object_keys(
            item,
            &[
                "itemId",
                "createdAt",
                "turnId",
                "kind",
                "sourceThreadId",
                "sourceTitle",
                "content",
            ],
        )?;
        if item.get("kind").and_then(Value::as_str) != Some("thread_message")
            || item.get("turnId") != Some(turn_id)
            || !valid_prefixed_id(item.get("itemId"), "item_")
            || !item
                .get("createdAt")
                .and_then(Value::as_str)
                .is_some_and(valid_protocol_timestamp)
            || !valid_prefixed_id(item.get("sourceThreadId"), "thr_")
            || !bounded_string(item.get("sourceTitle"), 1, 512)
            || !item
                .get("sourceTitle")
                .and_then(Value::as_str)
                .is_some_and(|title| {
                    !title.trim().is_empty()
                        && !title
                            .chars()
                            .any(|character| matches!(character, '\0' | '\r' | '\n'))
                })
            || !item
                .get("content")
                .and_then(Value::as_str)
                .is_some_and(|content| {
                    content.chars().count() <= 1_048_576 && !content.contains('\0')
                })
        {
            return Err("thread message item is invalid");
        }
        let item_id = item
            .get("itemId")
            .and_then(Value::as_str)
            .ok_or("thread message item identity is missing")?;
        if !item_ids.insert(item_id) {
            return Err("thread message item identity is duplicated");
        }
    }
    Ok(())
}

/// 校验 Corpus 表示的复合 Committed Model Step，确保相关事实作为单一事务闭环。
fn valid_model_step(params: &Value) -> Result<bool, &'static str> {
    require_text(params, "messageId")?;
    params
        .get("text")
        .and_then(Value::as_str)
        .ok_or("model text")?;
    let round = params
        .get("modelRound")
        .and_then(Value::as_u64)
        .ok_or("model round")?;
    let calls = params
        .get("toolCalls")
        .and_then(Value::as_array)
        .ok_or("tool calls")?;
    if !(1..=128).contains(&round) || calls.len() > 128 {
        return Ok(false);
    }
    for call in calls {
        ensure_object_keys(call, &["callId", "toolName", "ordinal", "presentation"])?;
        if !valid_prefixed_id(call.get("callId"), "call_")
            || !integer_in_bounds(call.get("ordinal"), 0, 1023)
        {
            return Ok(false);
        }
        validate_tool_presentation(
            call.get("presentation")
                .ok_or("tool presentation is missing")?,
        )?;
    }
    Ok(true)
}

/// 校验 Tool Result Batch 仅携带安全展示事实；文件变化由独立 TurnChangeSet 拥有。
fn valid_tool_batch(params: &Value) -> Result<bool, &'static str> {
    let results = params
        .get("results")
        .and_then(Value::as_array)
        .ok_or("tool results")?;
    if !(1..=128).contains(&results.len()) {
        return Ok(false);
    }
    for result in results {
        ensure_object_keys(
            result,
            &["callId", "outcome", "ordinal", "errorCode", "presentation"],
        )?;
        if !valid_prefixed_id(result.get("callId"), "call_")
            || !integer_in_bounds(result.get("ordinal"), 0, 1023)
            || !matches!(
                result.get("outcome").and_then(Value::as_str),
                Some("succeeded" | "failed" | "cancelled")
            )
        {
            return Ok(false);
        }
        validate_tool_presentation(
            result
                .get("presentation")
                .ok_or("tool presentation is missing")?,
        )?;
    }
    Ok(true)
}

/// 严格验证 App Server 签发的安全 Tool 投影，避免 raw arguments/result 或绝对路径重新进入 UI 合同。
fn validate_tool_presentation(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "kind",
            "title",
            "status",
            "inputPreview",
            "outputPreview",
            "relativePaths",
            "command",
            "relativeCwd",
            "stdout",
            "stderr",
            "exitCode",
            "durationMs",
            "truncated",
            "artifactId",
        ],
    )?;
    if !matches!(
        value.get("kind").and_then(Value::as_str),
        Some("read" | "edit" | "write" | "shell" | "mcp")
    ) || !bounded_string(value.get("title"), 1, 512)
        || !matches!(
            value.get("status").and_then(Value::as_str),
            Some("pending" | "running" | "waiting_approval" | "success" | "error" | "cancelled")
        )
        || value.get("truncated").and_then(Value::as_bool).is_none()
    {
        return Err("tool presentation header is invalid");
    }
    for field in [
        "inputPreview",
        "outputPreview",
        "command",
        "stdout",
        "stderr",
    ] {
        if let Some(text) = value.get(field)
            && !valid_preview_text(text)
        {
            return Err("tool presentation preview is invalid");
        }
    }
    let paths = value
        .get("relativePaths")
        .and_then(Value::as_array)
        .filter(|paths| paths.len() <= 64)
        .ok_or("tool relative paths are invalid")?;
    let mut unique = HashSet::new();
    for path in paths {
        let path = path.as_str().ok_or("tool path is not text")?;
        if !valid_relative_path(path) || !unique.insert(path) {
            return Err("tool relative path is invalid");
        }
    }
    if let Some(cwd) = value.get("relativeCwd")
        && !cwd.as_str().is_some_and(valid_relative_path)
    {
        return Err("tool cwd is invalid");
    }
    if let Some(artifact_id) = value.get("artifactId")
        && !valid_prefixed_id(Some(artifact_id), "artifact_")
    {
        return Err("tool artifact identity is invalid");
    }
    Ok(())
}

/// 验证安全预览的 NUL 与 32 KiB 边界；控制字符清理的权威实现仍在 Java projector。
fn valid_preview_text(value: &Value) -> bool {
    value
        .as_str()
        .is_some_and(|text| text.len() <= 32_768 && !text.contains('\0'))
}

/// 镜像协议的工作区相对路径约束，禁止盘符、反斜线和父目录逃逸进入 renderer。
fn valid_relative_path(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= 4096
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.chars().any(char::is_control)
        && !path.split('/').any(|segment| segment == "..")
        && !(path.len() >= 2
            && path.as_bytes()[0].is_ascii_alphabetic()
            && path.as_bytes()[1] == b':')
}

/// 校验冻结 Turn change set 的完整性二态、文件闭集和逐项统计，不接受 JA-RPC 2.0 兼容形状。
fn validate_turn_change_set(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &["state", "incompleteReasons", "files", "stats", "artifactId"],
    )?;
    if !valid_turn_change_integrity(value.get("state"), value.get("incompleteReasons")) {
        return Err("change set integrity is invalid");
    }
    let files = value
        .get("files")
        .and_then(Value::as_array)
        .filter(|files| files.len() <= 256)
        .ok_or("change files are invalid")?;
    for file in files {
        validate_turn_change_file(file)?;
    }
    let stats = value.get("stats").ok_or("change stats are missing")?;
    validate_turn_change_stats(stats, Some(files))?;
    if let Some(artifact_id) = value.get("artifactId")
        && !valid_prefixed_id(Some(artifact_id), "artifact_")
    {
        return Err("change artifact identity is invalid");
    }
    Ok(())
}

/// 校验精确文本 tracker 的三种文件净状态；rename、oldPath 和二进制兼容分支均已删除。
fn validate_turn_change_file(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "path",
            "status",
            "additions",
            "deletions",
            "binary",
            "truncated",
        ],
    )?;
    let path = require_text(value, "path")?;
    let status = require_text(value, "status")?;
    if !valid_relative_path(path)
        || !matches!(status, "added" | "modified" | "deleted")
        || value.get("truncated").and_then(Value::as_bool).is_none()
        || value.get("binary").and_then(Value::as_bool) != Some(false)
        || !integer_in_bounds(value.get("additions"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(value.get("deletions"), 0, 9_007_199_254_740_991)
    {
        return Err("change file is invalid");
    }
    Ok(())
}

/// 通知只校验统计边界；冻结终态还必须与逐文件计数、行数及 truncated 标记完全一致。
fn validate_turn_change_stats(value: &Value, files: Option<&[Value]>) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "files",
            "additions",
            "deletions",
            "binaryFiles",
            "truncated",
        ],
    )?;
    if !integer_in_bounds(value.get("files"), 0, 256)
        || !integer_in_bounds(value.get("additions"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(value.get("deletions"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(value.get("binaryFiles"), 0, 256)
        || value.get("truncated").and_then(Value::as_bool).is_none()
        || value.get("binaryFiles").and_then(Value::as_u64)
            > value.get("files").and_then(Value::as_u64)
    {
        return Err("change stats are invalid");
    }
    let Some(files) = files else {
        return Ok(());
    };
    let additions = files.iter().try_fold(0_u64, |total, file| {
        total.checked_add(file.get("additions")?.as_u64()?)
    });
    let deletions = files.iter().try_fold(0_u64, |total, file| {
        total.checked_add(file.get("deletions")?.as_u64()?)
    });
    if value.get("files").and_then(Value::as_u64) != Some(files.len() as u64)
        || value.get("additions").and_then(Value::as_u64) != additions
        || value.get("deletions").and_then(Value::as_u64) != deletions
        || value.get("binaryFiles").and_then(Value::as_u64) != Some(0)
        || value.get("truncated").and_then(Value::as_bool)
            != Some(
                files
                    .iter()
                    .any(|file| file.get("truncated") == Some(&Value::Bool(true))),
            )
    {
        return Err("change stats are inconsistent");
    }
    Ok(())
}

/// 完整性状态和七项去重原因必须同时成立，避免 partial 被错误显示为可证明完整。
fn valid_turn_change_integrity(state: Option<&Value>, reasons: Option<&Value>) -> bool {
    let Some(state) = state.and_then(Value::as_str) else {
        return false;
    };
    let Some(reasons) = reasons.and_then(Value::as_array) else {
        return false;
    };
    reasons.len() <= 7
        && reasons.iter().enumerate().all(|(index, reason)| {
            reason.as_str().is_some_and(|reason| {
                matches!(
                    reason,
                    "unknown_mutator"
                        | "mutation_chain_broken"
                        | "outside_workspace"
                        | "limit_exceeded"
                        | "capture_failed"
                        | "commit_unconfirmed"
                        | "recovery_boundary"
                )
            }) && !reasons[..index].iter().any(|previous| previous == reason)
        })
        && matches!(
            (state, reasons.is_empty()),
            ("complete", true) | ("partial", false)
        )
}

/// ChangeSet 已改为独立 Base64 完整读取；这里只校验 Tool artifact 的字符分页闭集。
fn validate_artifact_page_result(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "artifactId",
            "offsetCharacters",
            "nextOffsetCharacters",
            "totalCharacters",
            "truncated",
            "content",
        ],
    )?;
    if !valid_prefixed_id(value.get("artifactId"), "artifact_")
        || !valid_preview_text(value.get("content").ok_or("artifact content is missing")?)
        || value.get("truncated").and_then(Value::as_bool).is_none()
    {
        return Err("artifact page result is invalid");
    }
    Ok(())
}

/// 校验 artifact reader 的 Thread/Turn/Call 身份，防止分页接口跨会话读取安全投影。
fn validate_artifact_read_identity(value: &Value, tool: bool) -> Result<(), &'static str> {
    if !valid_prefixed_id(value.get("threadId"), "thr_")
        || !valid_prefixed_id(value.get("turnId"), "turn_")
        || !valid_prefixed_id(value.get("artifactId"), "artifact_")
        || (tool && !valid_prefixed_id(value.get("callId"), "call_"))
    {
        return Err("artifact reader identity is invalid");
    }
    Ok(())
}

/// 强制 Completed/Failed/Cancelled 的 Terminal Field 闭集，拒绝跨终态残留字段。
fn valid_terminal(params: &Value) -> Result<bool, &'static str> {
    let state = require_text(params, "state")?;
    params
        .get("summary")
        .and_then(Value::as_str)
        .ok_or("terminal summary")?;
    validate_turn_change_set(
        params
            .get("changeSet")
            .ok_or("terminal change set is missing")?,
    )?;
    Ok(match state {
        "completed" => {
            valid_terminal_message(params.get("finalMessage"))?
                && params.get("errorCode").is_none()
                && params.get("errorMessage").is_none()
        }
        "failed" => {
            valid_terminal_message(params.get("finalMessage"))?
                && params.get("errorCode").and_then(Value::as_str).is_some()
                && params.get("errorMessage").and_then(Value::as_str).is_some()
        }
        "cancelled" => {
            params.get("finalMessage").is_none()
                && params.get("errorCode").is_none()
                && params.get("errorMessage").is_none()
        }
        _ => false,
    })
}

/// 最终消息只允许稳定 item 身份与有界公开正文，避免 Consumer 接受空壳或扩展字段。
fn valid_terminal_message(value: Option<&Value>) -> Result<bool, &'static str> {
    let Some(message) = value else {
        return Ok(false);
    };
    ensure_object_keys(message, &["messageId", "text"])?;
    Ok(valid_prefixed_id(message.get("messageId"), "item_")
        && bounded_string(message.get("text"), 0, 1_048_576)
        && !message
            .get("text")
            .and_then(Value::as_str)
            .is_some_and(|text| text.contains('\0')))
}

/// 在 Rust Golden Consumer 边界拒绝 Unknown Field，防止类型化 Parser 把负向 Fixture
/// 静默转换为已接纳 Frame。
fn ensure_object_keys(value: &Value, allowed: &[&str]) -> Result<(), &'static str> {
    let object = value.as_object().ok_or("params must be an object")?;
    if object.keys().any(|key| !allowed.contains(&key.as_str())) {
        return Err("unknown field is not allowed");
    }
    Ok(())
}

/// 比较 Capability Array 时拒绝 Numeric、null 或重排条目；Initialize Catalog 是首版封闭合同。
fn string_array_equals(values: &[Value], expected: &[&str]) -> bool {
    values.len() == expected.len()
        && values
            .iter()
            .zip(expected)
            .all(|(value, expected)| value.as_str() == Some(*expected))
}

/// 仅允许 credential/set 的 secret 与 preview/open 的判别式 authorization；
/// 两者仍由方法专属闭集验证，Result 与 Notification 不能回显 Secret-shaped Key。
fn contains_forbidden_secret_key(
    value: &Value,
    allow_credential_secret: bool,
    allow_preview_authorization: bool,
) -> bool {
    fn visit(
        value: &Value,
        allow_secret: bool,
        allow_authorization: bool,
        in_params: bool,
    ) -> bool {
        match value {
            Value::Object(object) => object.iter().any(|(key, child)| {
                let lower = key.to_ascii_lowercase();
                let is_secret = matches!(
                    lower.as_str(),
                    "secret"
                        | "secretvalue"
                        | "credentialvalue"
                        | "tokenvalue"
                        | "apikey"
                        | "api_key"
                        | "authorization"
                        | "password"
                );
                let permitted = (allow_secret && in_params && key == "secret")
                    || (allow_authorization && in_params && key == "authorization");
                (is_secret && !permitted)
                    || visit(
                        child,
                        allow_secret,
                        allow_authorization,
                        in_params || key == "params",
                    )
            }),
            Value::Array(values) => values
                .iter()
                .any(|child| visit(child, allow_secret, allow_authorization, in_params)),
            _ => false,
        }
    }

    visit(
        value,
        allow_credential_secret,
        allow_preview_authorization,
        false,
    )
}

/// 要求非空 String，且不把 Number 或 Boolean 强制转换为文本。
fn require_text<'a>(value: &'a Value, field: &str) -> Result<&'a str, &'static str> {
    value
        .get(field)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .ok_or("required text is missing")
}

/// 从所有正向 Fixture 排除通用 Unsupported-value Sentinel，同时拒绝 Engine 与 Wire Shape
/// 的闭集外名称。
fn contains_unsupported_vocabulary(source: &[u8]) -> bool {
    let lowered = String::from_utf8_lossy(source).to_ascii_lowercase();
    [
        "unsupported_engine",
        "unsupported_model_api",
        "unsupported_event_method",
    ]
    .iter()
    .any(|marker| lowered.contains(marker))
}
