// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 严格 JSON-RPC decode 与 ready-token/forbidden-token 安全策略。
//!
//! Frame 值对象、资源 limits、错误脱敏和 BufRead framing 分属独立模块；这里仅编排
//! 原始 JSON 审计与 typed envelope 构造，避免安全校验出现第二份实现。

use super::catalog::{V1_CLIENT_METHODS, V1_EVENT_METHODS};
use super::error_policy::{CodecError, parse_error};
use super::frame::{Present, RpcFrame, valid_client_id, valid_wire_id};
use super::json as codec_json;
use super::limits::MAX_METHOD_BYTES;
use serde_json::{Map, Value};
use std::collections::HashSet;

const READY_TOKEN_FIELD: &str = "readyToken";
const READY_TOKEN_HEX_BYTES: usize = 32;

/// 为不参与握手状态机的调用方提供严格解码入口，并复用同一套原始字段审计；
/// 空 forbidden 集合只表示当前没有 active challenge，不得放宽 envelope 或 readyToken 校验。
pub fn decode_frame(frame: &[u8], max_frame_bytes: usize) -> Result<RpcFrame, CodecError> {
    decode_frame_with_forbidden(frame, max_frame_bytes, &HashSet::new())
}

/// 解码单帧并在全部原始字段拒绝当前握手 challenge，包括 typed frame 会丢弃的未知扩展。
pub(crate) fn decode_frame_with_forbidden(
    frame: &[u8],
    max_frame_bytes: usize,
    forbidden: &HashSet<String>,
) -> Result<RpcFrame, CodecError> {
    if frame.is_empty() {
        return Err(CodecError::UnexpectedEof);
    }
    if !frame.ends_with(b"\n") {
        return Err(CodecError::PartialFrame);
    }
    let payload = &frame[..frame.len() - 1];
    if payload.is_empty() {
        return Err(CodecError::EmptyFrame);
    }
    if payload.len() > max_frame_bytes {
        return Err(CodecError::FrameTooLarge {
            actual: payload.len(),
            max: max_frame_bytes,
        });
    }
    if payload.contains(&b'\n') || payload.ends_with(b"\r") {
        return Err(CodecError::InvalidEnvelope);
    }
    let text = std::str::from_utf8(payload).map_err(|_| CodecError::InvalidUtf8)?;
    let value = codec_json::parse_strict_value(text)?;
    let object = value.as_object().ok_or(CodecError::NonObject)?;
    let ready_frame = object.get("method").and_then(Value::as_str)
        == Some("runtime/status-changed")
        && object
            .get("params")
            .and_then(Value::as_object)
            .and_then(|params| params.get("status"))
            .and_then(Value::as_str)
            == Some("ready");
    let initialized_frame =
        object.get("method").and_then(Value::as_str) == Some("runtime/initialized");
    let allow_ready_token_path = initialized_frame || ready_frame;
    reject_ready_token_markers(&value, allow_ready_token_path)?;
    reject_forbidden_tokens(&value, forbidden, allow_ready_token_path)?;
    reject_unknown_root_fields(object)?;
    if object.get("jsonrpc") != Some(&Value::String("2.0".to_owned())) {
        return Err(CodecError::InvalidEnvelope);
    }
    let id = match object.get("id") {
        None => None,
        Some(Value::String(id)) if valid_wire_id(id) => Some(id.clone()),
        Some(Value::String(_)) => return Err(CodecError::InvalidId),
        Some(_) => return Err(CodecError::InvalidId),
    };
    let method = match object.get("method") {
        None => None,
        Some(Value::String(method)) if !method.is_empty() && method.len() <= MAX_METHOD_BYTES => {
            Some(method.clone())
        }
        Some(_) => return Err(CodecError::InvalidEnvelope),
    };
    validate_decoded_method(id.as_deref(), method.as_deref())?;
    let params = object.get("params").cloned();
    if method.is_some() && !params.as_ref().is_some_and(Value::is_object) {
        return Err(CodecError::InvalidEnvelope);
    }
    let result_present = object.contains_key("result");
    let result = if result_present {
        let result = object.get("result").ok_or(CodecError::InvalidEnvelope)?;
        if !result.is_object() {
            return Err(CodecError::InvalidEnvelope);
        }
        Present::some(result.clone())
    } else {
        Present::missing()
    };
    let error = match object.get("error") {
        None => None,
        Some(Value::Object(error)) => Some(parse_error(error)?),
        Some(_) => return Err(CodecError::InvalidEnvelope),
    };
    let frame = RpcFrame {
        id,
        method,
        params,
        result,
        error,
    };
    frame.validate()?;
    Ok(frame)
}

/// 拒绝冻结 request/event 闭集之外的所有 decoded method；
/// `initialized` 是唯一 client notification，且不会被公布为
/// server event capability 也不能借 notification 方向扩张。
fn validate_decoded_method(id: Option<&str>, method: Option<&str>) -> Result<(), CodecError> {
    match (id, method) {
        (_, None) => Ok(()),
        (Some(id), Some(method)) if valid_client_id(id) && V1_CLIENT_METHODS.contains(&method) => {
            Ok(())
        }
        (None, Some("runtime/initialized")) => Ok(()),
        (None, Some(method)) if V1_EVENT_METHODS.contains(&method) => Ok(()),
        _ => Err(CodecError::InvalidEnvelope),
    }
}

/// 在错误投影和未知字段丢弃之前审计整帧，防止 ready challenge 藏入 error.detail。
///
/// `RpcError` 只保留稳定 catalog 字段，因此必须在构造受限 projection 之前递归检查
/// 原始 JSON；否则 `error.data.details.readyToken` 会在 parse_error 后永久丢失。
pub(super) fn reject_ready_token_markers(
    value: &Value,
    allow_ready_token_path: bool,
) -> Result<(), CodecError> {
    fn visit(value: &Value, path: &mut Vec<String>, allow_ready_token_path: bool) -> bool {
        match value {
            Value::Object(object) => object.iter().any(|(key, child)| {
                path.push(key.clone());
                let legal_ready_path = allow_ready_token_path
                    && path.len() == 2
                    && path[0] == "params"
                    && key == "readyToken";
                let forbidden_key = is_ready_token_key(key) && !legal_ready_path;
                let nested = !legal_ready_path && visit(child, path, allow_ready_token_path);
                path.pop();
                forbidden_key || nested
            }),
            Value::Array(values) => values.iter().enumerate().any(|(index, child)| {
                path.push(format!("[{index}]"));
                let nested = visit(child, path, allow_ready_token_path);
                path.pop();
                nested
            }),
            Value::Null | Value::Bool(_) | Value::Number(_) | Value::String(_) => false,
        }
    }

    let mut path = Vec::new();
    if visit(value, &mut path, allow_ready_token_path) {
        return Err(if allow_ready_token_path {
            CodecError::HandshakeFailed
        } else {
            CodecError::InvalidEnvelope
        });
    }
    Ok(())
}

/// 只拒绝当前 active challenge，而不是所有合法的 32-hex 业务标识；
/// 合法 32-hex 业务标识；合法握手字段仍是唯一
/// 唯一握手例外由 session 状态机精确等值校验。
fn reject_forbidden_tokens(
    value: &Value,
    forbidden: &HashSet<String>,
    allow_ready_token_path: bool,
) -> Result<(), CodecError> {
    fn visit(
        value: &Value,
        path: &mut Vec<String>,
        forbidden: &HashSet<String>,
        allow_ready_token_path: bool,
    ) -> bool {
        match value {
            Value::Object(object) => object.iter().any(|(key, child)| {
                path.push(key.clone());
                let legal_ready_path = allow_ready_token_path
                    && path.len() == 2
                    && path[0] == "params"
                    && key == READY_TOKEN_FIELD;
                let rejected = !legal_ready_path
                    && (forbidden.contains(key)
                        || child.as_str().is_some_and(|text| forbidden.contains(text))
                        || visit(child, path, forbidden, allow_ready_token_path));
                path.pop();
                rejected
            }),
            Value::Array(values) => values.iter().enumerate().any(|(index, child)| {
                path.push(format!("[{index}]"));
                let rejected = visit(child, path, forbidden, allow_ready_token_path);
                path.pop();
                rejected
            }),
            Value::String(text) => forbidden.contains(text),
            Value::Null | Value::Bool(_) | Value::Number(_) => false,
        }
    }

    if forbidden.is_empty() {
        return Ok(());
    }
    let mut path = Vec::new();
    if visit(value, &mut path, forbidden, allow_ready_token_path) {
        return Err(if allow_ready_token_path {
            CodecError::HandshakeFailed
        } else {
            CodecError::InvalidEnvelope
        });
    }
    Ok(())
}

/// 只允许握手的两个精确 params 位置携带 readyToken，防止未知 root 扩展绕过脱敏边界。
pub(super) fn validate_ready_token_fields(method: &str, params: &Value) -> Result<(), CodecError> {
    let object = params.as_object().ok_or(CodecError::InvalidEnvelope)?;
    match method {
        "runtime/initialized"
            if object.len() != 1
                || !object
                    .get(READY_TOKEN_FIELD)
                    .and_then(Value::as_str)
                    .is_some_and(valid_ready_token) =>
        {
            return Err(CodecError::HandshakeFailed);
        }
        "runtime/initialized" => {}
        "runtime/status-changed"
            if object.get("status").and_then(Value::as_str) == Some("ready") =>
        {
            if !object
                .get(READY_TOKEN_FIELD)
                .and_then(Value::as_str)
                .is_some_and(valid_ready_token)
            {
                return Err(CodecError::HandshakeFailed);
            }
            for (key, value) in object {
                if key != READY_TOKEN_FIELD
                    && (is_ready_token_key(key) || contains_ready_token_marker(value))
                {
                    return Err(CodecError::HandshakeFailed);
                }
            }
        }
        _ if contains_ready_token_marker(params) => return Err(CodecError::InvalidEnvelope),
        _ => {}
    }
    Ok(())
}

/// 根 envelope 是 v1 的闭集；拒绝未知字段，避免 typed projection 静默
/// 丢弃新旧协议扩展后仍把 frame 当作已完整验证。
fn reject_unknown_root_fields(object: &Map<String, Value>) -> Result<(), CodecError> {
    const KNOWN: &[&str] = &["jsonrpc", "id", "method", "params", "result", "error"];
    if object.keys().any(|key| !KNOWN.contains(&key.as_str())) {
        return Err(CodecError::InvalidEnvelope);
    }
    Ok(())
}

/// 只识别保留的 readyToken 字段名；任意 hex 标识属于业务数据，必须在 session 范围
/// 与当前 challenge 精确比较后才能拒绝。
pub(super) fn contains_ready_token_marker(value: &Value) -> bool {
    match value {
        Value::Object(object) => object
            .iter()
            .any(|(key, child)| is_ready_token_key(key) || contains_ready_token_marker(child)),
        Value::Array(values) => values.iter().any(contains_ready_token_marker),
        Value::Null | Value::Bool(_) | Value::Number(_) | Value::String(_) => false,
    }
}

/// 校验 v1 challenge 的固定小写十六进制文本，不接受短值、Unicode 或大小写变体。
pub fn valid_ready_token(value: &str) -> bool {
    value.len() == READY_TOKEN_HEX_BYTES
        && value
            .as_bytes()
            .iter()
            .all(|byte| matches!(byte, b'0'..=b'9' | b'a'..=b'f'))
}

/// 识别 token 的固定长度 ASCII hex 形状，大小写不敏感但不接受 Unicode hex。
pub(super) fn is_token_shaped(value: &str) -> bool {
    value.len() == READY_TOKEN_HEX_BYTES && value.as_bytes().iter().all(u8::is_ascii_hexdigit)
}

/// 识别 readyToken 字段的 ASCII 大小写变体，只有精确字段名才能走握手例外。
pub(crate) fn is_ready_token_key(key: &str) -> bool {
    key.eq_ignore_ascii_case(READY_TOKEN_FIELD)
}
