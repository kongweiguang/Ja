// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 终态 Turn 修改记录的单文件 JA-RPC 合同。
//!
//! 该合同只允许按持久化 artifact 中的安全相对路径读取一次完整正文；运行中预览、
//! 分页游标和工作区文件读取都不属于此边界。

use super::handshake::valid_schema_id;
use serde::Serialize;
use serde_json::{Map, Value};
use std::fmt::{Display, Formatter};

pub const TURN_CHANGE_SET_MAX_BYTES: u64 = 2 * 1024 * 1024;
const MAX_PATH_CHARACTERS: usize = 4_096;
const MAX_BASE64_CHARACTERS: usize = ((TURN_CHANGE_SET_MAX_BYTES as usize + 2) / 3) * 4;

/// 合同错误保持固定分类，防止被拒绝的路径或正文进入日志与 WebView。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnChangeSetContractError {
    InvalidInput,
    InvalidResult,
}

impl Display for TurnChangeSetContractError {
    /// 固定错误文本只表达合同阶段，不回显任何用户内容。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::InvalidInput => "invalid turn change set input",
            Self::InvalidResult => "invalid turn change set result",
        })
    }
}

impl std::error::Error for TurnChangeSetContractError {}

/// 冻结读取只携带持久化四元身份，不接受分页、缓存或当前 Workspace 路径参数。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnChangeSetReadParams {
    thread_id: String,
    turn_id: String,
    artifact_id: String,
    file_path: String,
}

impl TurnChangeSetReadParams {
    /// 在进入 session pending 表前关闭非法 identity 与路径，避免一次完整读取扩大为任意文件探测。
    pub fn new(
        thread_id: impl Into<String>,
        turn_id: impl Into<String>,
        artifact_id: impl Into<String>,
        file_path: impl Into<String>,
    ) -> Result<Self, TurnChangeSetContractError> {
        let result = Self {
            thread_id: thread_id.into(),
            turn_id: turn_id.into(),
            artifact_id: artifact_id.into(),
            file_path: file_path.into(),
        };
        if !valid_schema_id(&result.thread_id, "thr_", 100)
            || !valid_schema_id(&result.turn_id, "turn_", 101)
            || !valid_schema_id(&result.artifact_id, "artifact_", 128)
            || !valid_relative_path(&result.file_path)
        {
            return Err(TurnChangeSetContractError::InvalidInput);
        }
        Ok(result)
    }

    /// 序列化已验证闭集，使只读 lease 无法被用作 generic RPC tunnel。
    pub(crate) fn into_value(self) -> Result<Value, TurnChangeSetContractError> {
        serde_json::to_value(self).map_err(|_| TurnChangeSetContractError::InvalidInput)
    }
}

/// Java 返回的单文件完整载荷；字节解码、UTF-8 与摘要复核由拥有相应依赖的 Tauri 边界完成。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnChangeSetReadResult {
    pub artifact_id: String,
    pub file_path: String,
    pub byte_length: u64,
    pub sha256: String,
    pub content_base64: String,
}

impl TryFrom<&Value> for TurnChangeSetReadResult {
    type Error = TurnChangeSetContractError;

    /// 严格拒绝未知字段、越界长度和非规范摘要/Base64 表面，避免大帧进入后续解码阶段。
    fn try_from(value: &Value) -> Result<Self, Self::Error> {
        let object = exact_object(
            value,
            &[
                "artifactId",
                "filePath",
                "byteLength",
                "sha256",
                "contentBase64",
            ],
        )?;
        let result = Self {
            artifact_id: required_text(object.get("artifactId"))?.to_owned(),
            file_path: required_text(object.get("filePath"))?.to_owned(),
            byte_length: object
                .get("byteLength")
                .and_then(Value::as_u64)
                .ok_or(TurnChangeSetContractError::InvalidResult)?,
            sha256: required_text(object.get("sha256"))?.to_owned(),
            content_base64: object
                .get("contentBase64")
                .and_then(Value::as_str)
                .ok_or(TurnChangeSetContractError::InvalidResult)?
                .to_owned(),
        };
        if !valid_schema_id(&result.artifact_id, "artifact_", 128)
            || !valid_relative_path(&result.file_path)
            || result.byte_length > TURN_CHANGE_SET_MAX_BYTES
            || !valid_sha256(&result.sha256)
            || !valid_standard_base64_surface(&result.content_base64)
        {
            return Err(TurnChangeSetContractError::InvalidResult);
        }
        Ok(result)
    }
}

/// Generic supervisor 也复核精确四字段，避免调用方绕过 typed 构造器恢复旧分页参数。
pub(crate) fn validate_turn_change_set_request(method: &str, params: &Value) -> bool {
    if method != "turn/change-set/read" {
        return false;
    }
    let Some(object) = params.as_object() else {
        return false;
    };
    exact_keys(object, &["threadId", "turnId", "artifactId", "filePath"])
        && object
            .get("threadId")
            .and_then(Value::as_str)
            .is_some_and(|value| valid_schema_id(value, "thr_", 100))
        && object
            .get("turnId")
            .and_then(Value::as_str)
            .is_some_and(|value| valid_schema_id(value, "turn_", 101))
        && object
            .get("artifactId")
            .and_then(Value::as_str)
            .is_some_and(|value| valid_schema_id(value, "artifact_", 128))
        && object
            .get("filePath")
            .and_then(Value::as_str)
            .is_some_and(valid_relative_path)
}

/// 路径必须是规范化的 workspace 相对引用，不允许盘符、反斜杠、空段或遍历。
fn valid_relative_path(value: &str) -> bool {
    !value.is_empty()
        && value.chars().count() <= MAX_PATH_CHARACTERS
        && !value.contains('\0')
        && !value.contains('\\')
        && !value.starts_with('/')
        && !value.ends_with('/')
        && !value.contains(':')
        && value
            .split('/')
            .all(|segment| !segment.is_empty() && segment != "." && segment != "..")
}

/// 摘要固定为小写 hex，避免等价但非规范拼写造成跨端 identity 漂移。
fn valid_sha256(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

/// 这里只验证标准 Base64 的规范表面与理论大小；真实 padding 和解码结果由 Tauri 严格复核。
fn valid_standard_base64_surface(value: &str) -> bool {
    if value.len() > MAX_BASE64_CHARACTERS || value.len() % 4 != 0 {
        return false;
    }
    let padding = value.bytes().rev().take_while(|byte| *byte == b'=').count();
    padding <= 2
        && value[..value.len().saturating_sub(padding)]
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'/'))
}

/// 结果对象必须完全匹配冻结字段闭集，不接受分页兼容字段。
fn exact_object<'a>(
    value: &'a Value,
    fields: &[&str],
) -> Result<&'a Map<String, Value>, TurnChangeSetContractError> {
    let object = value
        .as_object()
        .ok_or(TurnChangeSetContractError::InvalidResult)?;
    if !exact_keys(object, fields) {
        return Err(TurnChangeSetContractError::InvalidResult);
    }
    Ok(object)
}

/// 精确键集合阻止协议实现悄悄恢复旧字段或夹带诊断。
fn exact_keys(object: &Map<String, Value>, fields: &[&str]) -> bool {
    object.len() == fields.len() && fields.iter().all(|field| object.contains_key(*field))
}

/// 文本字段必须存在且非空；路径和 ID 的具体规则由各自校验器承担。
fn required_text(value: Option<&Value>) -> Result<&str, TurnChangeSetContractError> {
    value
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty())
        .ok_or(TurnChangeSetContractError::InvalidResult)
}
