// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

#![allow(dead_code, unused_imports)]

#[path = "../../src/lib.rs"]
mod production;

pub(crate) use production::app_server_process;

use crate::app_server_process::Limits;
use crate::app_server_process::protocol::decode_frame;
use serde_json::Value;
use std::collections::HashSet;
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
    "workspace/set-trust",
    "workspace/unregister",
    "thread/create",
    "thread/list",
    "thread/search",
    "thread/read",
    "thread/rename",
    "thread/preferences/update",
    "thread/archive",
    "thread/delete",
    "thread/compact",
    "attachment/import",
    "attachment/discard",
    "turn/start",
    "turn/cancel",
    "turn/steer",
    "turn/follow-up",
    "turn/change-set/commit",
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
    "assistant/model-step-committed",
    "assistant/text-delta",
    "assistant/reasoning-summary-delta",
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
];
const CAPABILITY_EVENT_METHODS: &[&str] = &[
    "runtime/status-changed",
    "turn/state-changed",
    "assistant/model-step-committed",
    "assistant/text-delta",
    "assistant/reasoning-summary-delta",
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
            validate_contract(&value).unwrap_or_else(|error| {
                panic!(
                    "positive frame must satisfy the v2 boundary: {}:{}: {error}",
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
        for (document_index, document) in documents(&file, &source).into_iter().enumerate() {
            let rejected = match decode_frame(&transport_frame(&document), max_frame_bytes()) {
                Err(_) => true,
                Ok(_) => serde_json::from_slice::<Value>(&document)
                    .map_or(true, |value| validate_contract(&value).is_err()),
            };
            assert!(
                rejected,
                "negative frame reached the Rust consumer boundary: {}:{}",
                file.display(),
                document_index + 1
            );
            frames += 1;
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
        .join("v2")
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

/// 优先从 Gate 环境定位 v2 共享 Corpus，否则从 Crate 目录向上查找，避免测试依赖当前工作目录。
fn golden_root() -> PathBuf {
    if let Some(configured) = env::var_os("JA_GOLDEN_PATH") {
        let configured = PathBuf::from(configured);
        return if configured.join("v2").is_dir() {
            configured.join("v2")
        } else {
            configured
        };
    }
    let mut current = env::current_dir().expect("current directory must be available");
    loop {
        let candidate = current.join("contracts").join("golden").join("v2");
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

/// 补充通用 framing codec 有意不承担的 v2 方法、事件与响应方向闭集。
fn validate_contract(value: &Value) -> Result<(), &'static str> {
    let method = value.get("method").and_then(Value::as_str);
    let allow_credential_secret = method == Some("credential/set")
        && value.get("id").is_some()
        && value
            .pointer("/params/secret")
            .and_then(Value::as_str)
            .is_some_and(|secret| !secret.is_empty());
    if contains_forbidden_secret_key(value, allow_credential_secret) {
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
    if result.get("threadId").is_some()
        && result.get("revision").is_some()
        && result.get("items").is_some()
        && result.get("nextCursor").is_some()
    {
        validate_thread_read_result(result)?;
    }
    if result.get("accepted").is_some() && result.get("changeSet").is_some() {
        ensure_object_keys(result, &["accepted", "changeSet"])?;
        if result.get("accepted").and_then(Value::as_bool) != Some(true) {
            return Err("change set commit result is invalid");
        }
        validate_turn_change_set(result.get("changeSet").ok_or("change set is missing")?)?;
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
            "boundTurnId",
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
        .get("boundTurnId")
        .is_some_and(|value| !value.is_null() && valid_prefixed_id(Some(value), "turn_"));
    match state {
        "bound" if bound => Ok(()),
        "draft" | "discarded" | "expired"
            if result.get("boundTurnId").is_some_and(Value::is_null) =>
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
                "runtime",
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
        if let Some(change_set) = turn.get("changeSet")
            && !change_set.is_null()
        {
            validate_turn_change_set(change_set)?;
        }
    }
    validate_thread_context_usage(result.get("contextUsage"), turns)?;
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
            Some("user_input" | "final_answer") => {
                ensure_object_keys(item, &["itemId", "createdAt", "turnId", "kind", "text"])?;
                require_text(item, "text")?;
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
            Some("attachment") => {
                ensure_object_keys(
                    item,
                    &[
                        "itemId",
                        "createdAt",
                        "kind",
                        "attachmentId",
                        "turnId",
                        "displayName",
                        "sizeBytes",
                        "mediaKind",
                        "mediaType",
                        "state",
                    ],
                )?;
                if !valid_prefixed_id(item.get("attachmentId"), "att_") {
                    return Err("thread attachment identity is invalid");
                }
            }
            _ => return Err("thread item kind is invalid"),
        }
    }
    Ok(())
}

/// 最近 Usage 必须是显式 nullable；存在时绑定本页 Turn，并保持精确计量与时间字段闭集。
fn validate_thread_context_usage(
    usage: Option<&Value>,
    turns: &[Value],
) -> Result<(), &'static str> {
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
            "modelRound",
            "inputTokens",
            "outputTokens",
            "totalTokens",
            "measuredAt",
        ],
    )?;
    let turn_id = require_text(usage, "turnId")?;
    let input = usage
        .get("inputTokens")
        .and_then(Value::as_u64)
        .ok_or("thread context usage input is invalid")?;
    let output = usage
        .get("outputTokens")
        .and_then(Value::as_u64)
        .ok_or("thread context usage output is invalid")?;
    let total = usage
        .get("totalTokens")
        .and_then(Value::as_u64)
        .ok_or("thread context usage total is invalid")?;
    let token_sum_valid = input
        .checked_add(output)
        .is_some_and(|minimum| total >= minimum);
    if !valid_prefixed_id(usage.get("turnId"), "turn_")
        || !integer_in_bounds(usage.get("modelRound"), 1, 128)
        || input > 9_007_199_254_740_991
        || output > 9_007_199_254_740_991
        || total > 9_007_199_254_740_991
        || !token_sum_valid
        || !bounded_string(usage.get("measuredAt"), 1, 64)
        || !turns
            .iter()
            .any(|turn| turn.get("turnId").and_then(Value::as_str) == Some(turn_id))
    {
        return Err("thread context usage is invalid");
    }
    Ok(())
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

/// 固定 configuration/read 的唯一 CAS 投影；layer 不得重复 version，顶层也不得保留
/// credentialVersion 等旧字段。
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

/// 拒绝已删除 Request，并校验 v2 Corpus 覆盖的高风险 Parameter Shape，确保旧协议入口不会回流。
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
        "workspace/list" | "skill/list" | "mcp/list" => ["cursor", "limit"].as_slice(),
        "thread/list" => ["workspaceId", "cursor", "limit"].as_slice(),
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
        ]
        .as_slice(),
        "thread/read" => ["threadId", "cursor", "limit"].as_slice(),
        "thread/rename" => ["threadId", "title", "expectedThreadRevision"].as_slice(),
        "thread/preferences/update" => [
            "threadId",
            "providerId",
            "modelId",
            "reasoningLevel",
            "accessMode",
            "expectedThreadRevision",
        ]
        .as_slice(),
        "thread/compact" => ["threadId", "expectedThreadRevision"].as_slice(),
        "thread/archive" | "thread/delete" => ["threadId", "expectedThreadRevision"].as_slice(),
        "attachment/import" => [
            "ingressToken",
            "workspaceId",
            "displayName",
            "sizeBytes",
            "sha256",
        ]
        .as_slice(),
        "attachment/discard" => ["attachmentId"].as_slice(),
        "turn/start" => ["threadId", "content", "deadlineMs"].as_slice(),
        "turn/cancel" => ["turnId", "expectedThreadRevision"].as_slice(),
        "turn/steer" | "turn/follow-up" => ["turnId", "text"].as_slice(),
        "turn/change-set/commit" => [
            "threadId",
            "turnId",
            "workspaceId",
            "state",
            "reason",
            "files",
            "stats",
            "artifact",
        ]
        .as_slice(),
        "turn/change-set/read" => [
            "threadId",
            "turnId",
            "artifactId",
            "offsetBytes",
            "limitBytes",
        ]
        .as_slice(),
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
            if params.get("protocolMajor").and_then(Value::as_u64) != Some(2)
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
                return Err("method capability catalog is not the v2 closure");
            }
            let events = params
                .pointer("/capabilities/events")
                .and_then(Value::as_array)
                .ok_or("event capability catalog is missing")?;
            if !string_array_equals(events, CAPABILITY_EVENT_METHODS) {
                return Err("event capability catalog is not the v2 closure");
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
                || params.get("reasoningLevel").is_none_or(|effort| {
                    !effort.is_null() && !matches!(effort.as_str(), Some("low" | "medium" | "high"))
                })
            {
                return Err("thread runtime preferences are invalid");
            }
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
        "turn/cancel" => {
            require_text(params, "turnId")?;
            if params
                .get("expectedThreadRevision")
                .and_then(Value::as_u64)
                .is_none_or(|value| value < 1)
            {
                return Err("turn revision is invalid");
            }
        }
        "turn/change-set/commit" => validate_turn_change_set_commit(params)?,
        "turn/change-set/read" => {
            validate_artifact_read_identity(params, false)?;
            if !integer_in_bounds(params.get("offsetBytes"), 0, 2_097_152)
                || !integer_in_bounds(params.get("limitBytes"), 1, 65_536)
            {
                return Err("change set artifact page is invalid");
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

/// 校验 `turn/start.content` 判别联合及每轮十个附件上限，拒绝旧 input/text 顶层和重复绑定。
fn validate_turn_content(value: Option<&Value>) -> Result<(), &'static str> {
    let items = value
        .and_then(Value::as_array)
        .filter(|items| (1..=64).contains(&items.len()))
        .ok_or("turn content is missing")?;
    let mut attachment_ids = std::collections::HashSet::new();
    let mut attachment_count = 0usize;
    let mut total_text = 0usize;
    for item in items {
        let object = item.as_object().ok_or("turn content item is invalid")?;
        match object.get("type").and_then(Value::as_str) {
            Some("text") => {
                ensure_object_keys(item, &["type", "text"])?;
                let text = require_text(item, "text")?;
                total_text = total_text.saturating_add(text.len());
                if total_text > 4_000_000 || text.contains('\0') {
                    return Err("turn text exceeds limit");
                }
            }
            Some("attachment") => {
                ensure_object_keys(item, &["type", "attachmentId"])?;
                let id = item
                    .get("attachmentId")
                    .and_then(Value::as_str)
                    .filter(|_| valid_prefixed_id(item.get("attachmentId"), "att_"))
                    .ok_or("turn attachment id is invalid")?;
                attachment_count += 1;
                if attachment_count > 10 || !attachment_ids.insert(id) {
                    return Err("turn attachment limit or uniqueness is invalid");
                }
            }
            _ => return Err("turn content type is invalid"),
        }
    }
    Ok(())
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
        || contains_forbidden_secret_key(value, false)
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

/// 镜像 v2 opaque CAS Token Grammar，防止旧 Hash 或 Nullable Token 以虚假首次 Generation
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
        ],
    )?;
    if object.get("schema_version").and_then(Value::as_u64) != Some(4)
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
    validate_config_mcp_servers(object.get("mcp_servers"))?;
    validate_config_skills(object.get("skills"))?;
    validate_config_value(Some(document))
}

/// 校验有界 Provider 与其嵌套 Model 目录，稳定 ID 不得因显示名变化而重用。
fn validate_config_providers(value: Option<&Value>) -> Result<(), &'static str> {
    let providers = value
        .and_then(Value::as_array)
        .filter(|providers| providers.len() <= 512)
        .ok_or("providers are invalid")?;
    let mut ids = HashSet::with_capacity(providers.len());
    for provider in providers {
        let object = provider.as_object().ok_or("provider is not an object")?;
        ensure_object_keys(
            provider,
            &[
                "provider_id",
                "name",
                "provider",
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
            || !matches!(
                object.get("provider").and_then(Value::as_str),
                Some("openai" | "anthropic")
            )
            || !matches!(
                object.get("api").and_then(Value::as_str),
                Some("openai_responses" | "anthropic_messages")
            )
            || !bounded_string(object.get("base_url"), 1, 2_048)
            || !valid_prefixed_id(object.get("credential_id"), "cred_")
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

/// 校验 Provider 声明的模型容量，使 Rust consumer 与 v2 schema 使用同一当前字段闭集。
fn validate_config_capabilities(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("profile capabilities are invalid")?;
    ensure_object_keys(
        value.ok_or("profile capabilities are missing")?,
        &["context_window_tokens", "max_output_tokens"],
    )?;
    if !integer_in_bounds(object.get("context_window_tokens"), 4_096, 4_000_000)
        || !integer_in_bounds(object.get("max_output_tokens"), 1, 1_000_000)
    {
        return Err("profile capabilities value is invalid");
    }
    Ok(())
}

/// 校验当前唯一的自动压缩策略开关；旧字符预算字段不得重新进入 v2 consumer。
fn validate_config_context(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("profile context is invalid")?;
    ensure_object_keys(
        value.ok_or("profile context is missing")?,
        &["auto_compact"],
    )?;
    if !matches!(object.get("auto_compact"), Some(Value::Bool(_))) {
        return Err("profile context value is invalid");
    }
    Ok(())
}

/// 校验 v2 Document 中的 Model Round/Tool Call Budget Object，保持预算字段闭集。
fn validate_config_turn_limits(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("profile turn limits are invalid")?;
    ensure_object_keys(
        value.ok_or("profile turn limits are missing")?,
        &["max_model_rounds", "max_tool_calls", "wall_timeout_ms"],
    )?;
    if !integer_in_bounds(object.get("max_model_rounds"), 1, 128)
        || !integer_in_bounds(object.get("max_tool_calls"), 0, 1_024)
        || !integer_in_bounds(object.get("wall_timeout_ms"), 1_000, 86_400_000)
    {
        return Err("profile turn limits value is invalid");
    }
    Ok(())
}

/// 校验显式 Provider Network Timeout Object，避免使用隐式或无界超时。
fn validate_config_network_timeouts(value: Option<&Value>) -> Result<(), &'static str> {
    let object = value
        .and_then(Value::as_object)
        .ok_or("profile network timeouts are invalid")?;
    ensure_object_keys(
        value.ok_or("profile network timeouts are missing")?,
        &["connect_timeout_ms", "request_timeout_ms"],
    )?;
    if !integer_in_bounds(object.get("connect_timeout_ms"), 100, 120_000)
        || !integer_in_bounds(object.get("request_timeout_ms"), 1_000, 3_600_000)
    {
        return Err("profile network timeout value is invalid");
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

/// 应用兼容 Schema 的 String Length Bound，且不对值执行 Coercion。
fn bounded_string(value: Option<&Value>, minimum: usize, maximum: usize) -> bool {
    value
        .and_then(Value::as_str)
        .is_some_and(|text| (minimum..=maximum).contains(&text.chars().count()))
}

/// 应用非负 Integer Bound，同时拒绝 Floating-point JSON，避免数值语义漂移。
fn integer_in_bounds(value: Option<&Value>, minimum: u64, maximum: u64) -> bool {
    value
        .and_then(Value::as_u64)
        .is_some_and(|number| (minimum..=maximum).contains(&number))
}

/// 校验 v2 Wire Schema 使用的 Prefixed Identifier，保持各身份命名空间隔离。
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
        || params.get("strategyVersion").and_then(Value::as_str) != Some("ja-context-v3")
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
                            | "TOKEN_COUNT_UNAVAILABLE"
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
        "turn/terminal" => allowed.extend([
            "state",
            "summary",
            "finalMessage",
            "usage",
            "errorCode",
            "errorMessage",
        ]),
        _ => return Err("notification method is unknown"),
    }
    ensure_object_keys(params, &allowed)?;
    validate_turn_event_metadata(params)?;
    if method == "turn/terminal" && !valid_terminal(params)? {
        return Err("terminal state is invalid");
    }
    if method == "turn/state-changed" {
        let from = require_text(params, "from")?;
        let to = require_text(params, "to")?;
        if !matches!(
            (from, to),
            ("queued", "running" | "completed" | "failed" | "cancelled")
                | (
                    "running",
                    "waiting_approval" | "completed" | "failed" | "cancelled"
                )
                | (
                    "waiting_approval",
                    "running" | "completed" | "failed" | "cancelled"
                )
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

/// 校验冻结 Turn change set 的状态互斥、文件闭集和统计上限，不把 unavailable 冒充零修改。
fn validate_turn_change_set(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(value, &["state", "reason", "files", "stats", "artifactId"])?;
    let state = require_text(value, "state")?;
    let files = value
        .get("files")
        .and_then(Value::as_array)
        .filter(|files| files.len() <= 10_000)
        .ok_or("change files are invalid")?;
    for file in files {
        validate_turn_change_file(file)?;
    }
    let stats = value.get("stats").ok_or("change stats are missing")?;
    validate_turn_change_stats(stats)?;
    if stats.get("files").and_then(Value::as_u64) != Some(files.len() as u64) {
        return Err("change stats file count is inconsistent");
    }
    match state {
        "available" if value.get("reason").is_none() => {
            if let Some(artifact_id) = value.get("artifactId")
                && !valid_prefixed_id(Some(artifact_id), "artifact_")
            {
                return Err("change artifact identity is invalid");
            }
            Ok(())
        }
        "unavailable"
            if files.is_empty()
                && value.get("artifactId").is_none()
                && value.pointer("/stats/files").and_then(Value::as_u64) == Some(0)
                && value.pointer("/stats/additions").and_then(Value::as_u64) == Some(0)
                && value.pointer("/stats/deletions").and_then(Value::as_u64) == Some(0)
                && value.pointer("/stats/binaryFiles").and_then(Value::as_u64) == Some(0)
                && value.pointer("/stats/truncated").and_then(Value::as_bool) == Some(false)
                && matches!(
                    value.get("reason").and_then(Value::as_str),
                    Some("concurrent_turn" | "not_git" | "capture_failed" | "diff_too_large")
                ) =>
        {
            Ok(())
        }
        _ => Err("change set state is invalid"),
    }
}

/// 校验单文件变化的 rename/binary 条件字段，确保 UI 不推导不存在的行数或旧路径。
fn validate_turn_change_file(value: &Value) -> Result<(), &'static str> {
    ensure_object_keys(
        value,
        &[
            "path",
            "oldPath",
            "status",
            "additions",
            "deletions",
            "binary",
            "truncated",
        ],
    )?;
    let path = require_text(value, "path")?;
    let status = require_text(value, "status")?;
    let binary = value
        .get("binary")
        .and_then(Value::as_bool)
        .ok_or("change binary flag is invalid")?;
    if !valid_relative_path(path)
        || !matches!(status, "added" | "modified" | "deleted" | "renamed")
        || value.get("truncated").and_then(Value::as_bool).is_none()
        || if status == "renamed" {
            !value
                .get("oldPath")
                .and_then(Value::as_str)
                .is_some_and(valid_relative_path)
        } else {
            value.get("oldPath").is_some()
        }
        || (binary && (value.get("additions").is_some() || value.get("deletions").is_some()))
        || value
            .get("additions")
            .is_some_and(|line| !integer_in_bounds(Some(line), 0, 9_007_199_254_740_991))
        || value
            .get("deletions")
            .is_some_and(|line| !integer_in_bounds(Some(line), 0, 9_007_199_254_740_991))
    {
        return Err("change file is invalid");
    }
    Ok(())
}

/// 校验 Turn 汇总的整数范围；文件数与逐项一致性由 App Server 持久化事务继续保证。
fn validate_turn_change_stats(value: &Value) -> Result<(), &'static str> {
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
    if !integer_in_bounds(value.get("files"), 0, 10_000)
        || !integer_in_bounds(value.get("additions"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(value.get("deletions"), 0, 9_007_199_254_740_991)
        || !integer_in_bounds(value.get("binaryFiles"), 0, 10_000)
        || value.get("truncated").and_then(Value::as_bool).is_none()
    {
        return Err("change stats are invalid");
    }
    Ok(())
}

/// 校验两类 artifact page 的互斥字段与内容上限，避免字符 offset 和 byte offset 被混用。
fn validate_artifact_page_result(value: &Value) -> Result<(), &'static str> {
    let tool_page = value.get("offsetCharacters").is_some();
    let allowed = if tool_page {
        [
            "artifactId",
            "offsetCharacters",
            "nextOffsetCharacters",
            "totalCharacters",
            "truncated",
            "content",
        ]
        .as_slice()
    } else {
        [
            "artifactId",
            "offsetBytes",
            "nextOffsetBytes",
            "byteLength",
            "truncated",
            "content",
        ]
        .as_slice()
    };
    ensure_object_keys(value, allowed)?;
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

/// 校验 Rust 提交给 Java 的 change set 与可选冻结 diff，unavailable 状态不得携带 artifact。
fn validate_turn_change_set_commit(value: &Value) -> Result<(), &'static str> {
    if !valid_prefixed_id(value.get("threadId"), "thr_")
        || !valid_prefixed_id(value.get("turnId"), "turn_")
        || !valid_prefixed_id(value.get("workspaceId"), "ws_")
    {
        return Err("change set commit identity is invalid");
    }
    let mut projection = value.clone();
    let object = projection
        .as_object_mut()
        .ok_or("change set commit is not an object")?;
    object.remove("threadId");
    object.remove("turnId");
    object.remove("workspaceId");
    if let Some(artifact) = object.remove("artifact") {
        ensure_object_keys(&artifact, &["sha256", "byteLength", "unifiedDiff"])?;
        if !artifact
            .get("sha256")
            .and_then(Value::as_str)
            .is_some_and(|sha| {
                sha.len() == 64
                    && sha
                        .bytes()
                        .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            })
            || !integer_in_bounds(artifact.get("byteLength"), 0, 2_097_152)
            || !artifact
                .get("unifiedDiff")
                .and_then(Value::as_str)
                .is_some_and(|diff| diff.len() <= 2_097_152 && !diff.contains('\0'))
        {
            return Err("change set artifact is invalid");
        }
        object.insert(
            "artifactId".to_owned(),
            Value::String("artifact_validation".to_owned()),
        );
    }
    validate_turn_change_set(&projection)
}

/// 强制 Completed/Failed/Cancelled 的 Terminal Field 闭集，拒绝跨终态残留字段。
fn valid_terminal(params: &Value) -> Result<bool, &'static str> {
    let state = require_text(params, "state")?;
    params
        .get("summary")
        .and_then(Value::as_str)
        .ok_or("terminal summary")?;
    Ok(match state {
        "completed" => {
            params
                .get("finalMessage")
                .and_then(Value::as_object)
                .is_some()
                && params.get("errorCode").is_none()
                && params.get("errorMessage").is_none()
        }
        "failed" => {
            params.get("errorCode").and_then(Value::as_str).is_some()
                && params.get("errorMessage").and_then(Value::as_str).is_some()
        }
        "cancelled" => params.get("errorCode").is_none() && params.get("errorMessage").is_none(),
        _ => false,
    })
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

/// 除精确 credential/set Request Parameter 外，所有位置都禁止 Secret-shaped Key；
/// Result 与 Notification 因此不能回显 Secret。
fn contains_forbidden_secret_key(value: &Value, allow_credential_secret: bool) -> bool {
    fn visit(value: &Value, allow_secret: bool, in_params: bool) -> bool {
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
                let permitted = allow_secret && in_params && key == "secret";
                (is_secret && !permitted)
                    || visit(child, allow_secret, in_params || key == "params")
            }),
            Value::Array(values) => values
                .iter()
                .any(|child| visit(child, allow_secret, in_params)),
            _ => false,
        }
    }

    visit(value, allow_credential_secret, false)
}

/// 要求非空 String，且不把 Number 或 Boolean 强制转换为文本。
fn require_text<'a>(value: &'a Value, field: &str) -> Result<&'a str, &'static str> {
    value
        .get(field)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .ok_or("required text is missing")
}

/// 从所有正向 Fixture 排除通用 Unsupported-value Sentinel，同时不保留已删除 Engine
/// 与 Wire Shape 的旧名称。
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
