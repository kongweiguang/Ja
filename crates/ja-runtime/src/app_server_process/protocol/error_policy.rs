// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! JA-RPC v2 错误目录投影、消息脱敏与稳定 codec 错误分类。

use super::catalog as codec_catalog;
use super::codec::{is_ready_token_key, is_token_shaped};
use serde_json::{Map, Value};
use std::fmt::{Display, Formatter};

/// parser 失败必须是可枚举的稳定分类；不把原始 JSON、路径或 secret 放进错误文本。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CodecError {
    UnexpectedEof,
    PartialFrame,
    EmptyFrame,
    InvalidUtf8,
    InvalidJson,
    DuplicateKey,
    NonObject,
    InvalidEnvelope,
    HandshakeFailed,
    InvalidErrorCatalog,
    InvalidId,
    InvalidLimit,
    FrameTooLarge { actual: usize, max: usize },
    Io,
}

impl Display for CodecError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UnexpectedEof => formatter.write_str("unexpected eof"),
            Self::PartialFrame => formatter.write_str("partial jsonl frame"),
            Self::EmptyFrame => formatter.write_str("empty jsonl frame"),
            Self::InvalidUtf8 => formatter.write_str("invalid utf8"),
            Self::InvalidJson => formatter.write_str("invalid json"),
            Self::DuplicateKey => formatter.write_str("duplicate object key"),
            Self::NonObject => formatter.write_str("json-rpc frame is not an object"),
            Self::InvalidEnvelope => formatter.write_str("invalid json-rpc envelope"),
            Self::HandshakeFailed => formatter.write_str("handshake failed"),
            Self::InvalidErrorCatalog => formatter.write_str("invalid error catalog entry"),
            Self::InvalidId => formatter.write_str("invalid request id"),
            Self::InvalidLimit => formatter.write_str("invalid frame limit"),
            Self::FrameTooLarge { actual, max } => {
                write!(formatter, "frame exceeds limit ({actual} > {max})")
            }
            Self::Io => formatter.write_str("jsonl reader io failure"),
        }
    }
}

impl std::error::Error for CodecError {}

#[derive(Clone, PartialEq)]
pub struct RpcError {
    pub(super) code: i64,
    pub(super) message: String,
    pub(super) data: Value,
}

impl std::fmt::Debug for RpcError {
    /// 保留可定位的错误结构，同时移除 challenge 值与 marker key。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("RpcError")
            .field("code", &self.code)
            .field("message", &redact_debug_text(&self.message))
            .field("data", &redact_debug_value(&self.data))
            .finish()
    }
}

impl RpcError {
    /// 返回 response error code，供稳定错误路由使用。
    pub fn code(&self) -> i64 {
        self.code
    }

    /// 返回经过 bounded 脱敏校验的 message；分类仍由冻结 typed data 元组决定。
    pub fn message(&self) -> &str {
        &self.message
    }

    /// 返回脱敏错误 data 投影；未知 detail 在 decode 时已被丢弃。
    pub fn data(&self) -> &Value {
        &self.data
    }
}

/// 脱敏精确 challenge 形状文本，且不在 debug buffer 保留原值。
pub(super) fn redact_debug_text(value: &str) -> String {
    if is_token_shaped(value) {
        "<redacted>".to_owned()
    } else {
        value.to_owned()
    }
}

/// 为 Debug 递归脱敏 token key、token 形状 key 与 token 形状 value。
pub(super) fn redact_debug_value(value: &Value) -> Value {
    match value {
        Value::Object(object) => Value::Object(
            object
                .iter()
                .map(|(key, child)| {
                    let safe_key = if is_ready_token_key(key) || is_token_shaped(key) {
                        "<redacted>".to_owned()
                    } else {
                        key.to_owned()
                    };
                    (safe_key, redact_debug_value(child))
                })
                .collect(),
        ),
        Value::Array(values) => Value::Array(values.iter().map(redact_debug_value).collect()),
        Value::String(text) => Value::String(redact_debug_text(text)),
        other => other.clone(),
    }
}

/// 从 Wire error 读取 bounded typed data，拒绝未知字段、伪造目录元组和泄密消息。
pub(super) fn parse_error(object: &Map<String, Value>) -> Result<RpcError, CodecError> {
    const ERROR_FIELDS: &[&str] = &["code", "message", "data"];
    if object
        .keys()
        .any(|key| !ERROR_FIELDS.contains(&key.as_str()))
    {
        return Err(CodecError::InvalidEnvelope);
    }
    let code = object
        .get("code")
        .and_then(Value::as_i64)
        .ok_or(CodecError::InvalidEnvelope)?;
    let message = object
        .get("message")
        .and_then(Value::as_str)
        .filter(|message| valid_error_message(message))
        .ok_or(CodecError::InvalidEnvelope)?
        .to_owned();
    let data = object.get("data").ok_or(CodecError::InvalidEnvelope)?;
    let data_object = data.as_object().ok_or(CodecError::InvalidEnvelope)?;
    let error_code = data_object
        .get("errorCode")
        .and_then(Value::as_str)
        .ok_or(CodecError::InvalidEnvelope)?;
    let category = data_object
        .get("category")
        .and_then(Value::as_str)
        .ok_or(CodecError::InvalidEnvelope)?;
    let retryable = data_object
        .get("retryable")
        .and_then(Value::as_bool)
        .ok_or(CodecError::InvalidEnvelope)?;
    let error_id = data_object
        .get("errorId")
        .and_then(Value::as_str)
        .filter(|identifier| valid_error_id(identifier))
        .ok_or(CodecError::InvalidEnvelope)?;
    const DATA_FIELDS: &[&str] = &[
        "errorCode",
        "category",
        "retryable",
        "errorId",
        "retryAfterMs",
    ];
    if data_object
        .keys()
        .any(|key| !DATA_FIELDS.contains(&key.as_str()))
    {
        return Err(CodecError::InvalidEnvelope);
    }
    let retry_after_ms = match data_object.get("retryAfterMs") {
        Some(value) => Some(
            value
                .as_u64()
                .filter(|millis| (1..=3_600_000).contains(millis) && retryable)
                .ok_or(CodecError::InvalidEnvelope)?,
        ),
        None => None,
    };
    // 重新构造安全投影，绝不保留可能携带路径、提示词、源码或凭据的服务端扩展字段。
    let mut safe_data = Map::new();
    safe_data.insert("errorCode".to_owned(), Value::String(error_code.to_owned()));
    safe_data.insert("category".to_owned(), Value::String(category.to_owned()));
    safe_data.insert("retryable".to_owned(), Value::Bool(retryable));
    safe_data.insert("errorId".to_owned(), Value::String(error_id.to_owned()));
    if let Some(retry_after_ms) = retry_after_ms {
        safe_data.insert(
            "retryAfterMs".to_owned(),
            Value::Number(retry_after_ms.into()),
        );
    }
    let error = RpcError {
        code,
        message,
        data: Value::Object(safe_data),
    };
    validate_error(&error)?;
    Ok(error)
}

/// 检查稳定错误代码的全大写 ASCII 形状，避免把原始异常文本当 errorCode。
fn valid_error_code(code: &str) -> bool {
    let bytes = code.as_bytes();
    !bytes.is_empty()
        && bytes.len() >= 3
        && bytes.len() <= 64
        && bytes[0].is_ascii_uppercase()
        && bytes[1..]
            .iter()
            .all(|byte| byte.is_ascii_uppercase() || byte.is_ascii_digit() || *byte == b'_')
}

/// 只接受固定长度的小写十六进制关联 ID，禁止借该字段携带主机信息。
fn valid_error_id(identifier: &str) -> bool {
    identifier.len() == 36
        && identifier.strip_prefix("err_").is_some_and(|suffix| {
            suffix
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        })
}

/// 复用 decode 的 error 约束校验手工 response，防止内部 caller 绕过 schema。
pub(super) fn validate_error(error: &RpcError) -> Result<(), CodecError> {
    if !valid_error_message(&error.message) {
        return Err(CodecError::InvalidEnvelope);
    }
    if !(-32_768..=-32_000).contains(&error.code) {
        return Err(CodecError::InvalidErrorCatalog);
    }
    let Some(data) = error.data.as_object() else {
        return Err(CodecError::InvalidEnvelope);
    };
    let error_code = data
        .get("errorCode")
        .and_then(Value::as_str)
        .filter(|code| valid_error_code(code))
        .ok_or(CodecError::InvalidErrorCatalog)?;
    let category = data
        .get("category")
        .and_then(Value::as_str)
        .ok_or(CodecError::InvalidErrorCatalog)?;
    let retryable = data
        .get("retryable")
        .and_then(Value::as_bool)
        .ok_or(CodecError::InvalidErrorCatalog)?;
    // code/errorCode/category/retryable 是唯一稳定分类元组，展示消息不得参与目录判定。
    catalog_entry(error.code, error_code, category, retryable)
        .ok_or(CodecError::InvalidErrorCatalog)?;
    Ok(())
}

/// 只允许 bounded、可显示的错误 message，拒绝把路径、凭据或 challenge
/// 形状的原始诊断带入 UI/日志，同时保留合法本地化文案。
fn valid_error_message(message: &str) -> bool {
    if message.is_empty() || message.len() > 512 {
        return false;
    }
    ErrorMessageScanner::new(message).is_safe()
}

/// 对用户可见错误执行有界词法扫描；全部安全检查共用一次遍历，避免新增 marker 规则
/// 绕过既有路径、URI 或 secret 规则。
struct ErrorMessageScanner<'a> {
    message: &'a str,
    bytes: &'a [u8],
}

impl<'a> ErrorMessageScanner<'a> {
    /// 只借用已有长度上限的 message，使每次扫描都有固定输入预算。
    fn new(message: &'a str) -> Self {
        Self {
            message,
            bytes: message.as_bytes(),
        }
    }

    /// 拒绝控制字符与 URI/path/secret/token 词法标记，避免原始诊断进入 UI。
    fn is_safe(&self) -> bool {
        !self.message.chars().any(char::is_control)
            && !self.has_uri()
            && !self.has_posix_path()
            && !self.has_windows_path()
            && !self.has_secret_marker()
            && !self.has_hex_run()
    }

    /// 检测后接 `://` 的任意 ASCII scheme，包括 URL 前后缀和 query；不做 percent
    /// decode，避免解码差异改变安全结论。
    fn has_uri(&self) -> bool {
        self.bytes.windows(3).enumerate().any(|(index, window)| {
            if window != b"://" {
                return false;
            }
            let mut start = index;
            while start > 0 && is_scheme_byte(self.bytes[start - 1]) {
                start -= 1;
            }
            start < index && self.bytes[start].is_ascii_alphabetic()
        })
    }

    /// 只在词法边界识别 POSIX 绝对路径，同时允许 `失败/重试` 等普通中文斜线表达。
    fn has_posix_path(&self) -> bool {
        for index in 0..self.bytes.len() {
            if self.bytes[index] != b'/' || !is_path_boundary(self.bytes, index) {
                continue;
            }
            let segment_start = index + 1;
            if segment_start >= self.bytes.len() || !is_path_byte(self.bytes[segment_start]) {
                continue;
            }
            let mut end = segment_start + 1;
            while end < self.bytes.len() && is_path_byte(self.bytes[end]) {
                end += 1;
            }
            let segment = &self.bytes[segment_start..end];
            let has_nested_component = self.bytes.get(end) == Some(&b'/');
            let known_root = matches!(
                segment,
                b"etc"
                    | b"home"
                    | b"Users"
                    | b"users"
                    | b"private"
                    | b"tmp"
                    | b"var"
                    | b"usr"
                    | b"opt"
                    | b"root"
                    | b"dev"
                    | b"proc"
                    | b"sys"
                    | b"Volumes"
                    | b"volumes"
            );
            if has_nested_component || known_root {
                return true;
            }
        }
        false
    }

    /// 不解码、不 normalize 输入，直接识别盘符与 UNC 路径，避免规范化访问 filesystem。
    fn has_windows_path(&self) -> bool {
        self.bytes.windows(2).any(|window| window == b"\\\\")
            || self.bytes.windows(3).any(|window| {
                window[0].is_ascii_alphabetic()
                    && window[1] == b':'
                    && matches!(window[2], b'/' | b'\\')
            })
    }

    /// 识别 credential label 的大小写与分隔符变体，同时避免把普通本地化文案误判为 secret。
    fn has_secret_marker(&self) -> bool {
        let lower = self.message.to_ascii_lowercase();
        let compact = lower
            .bytes()
            .filter(|byte| byte.is_ascii_alphanumeric())
            .collect::<Vec<_>>();
        [
            b"secret".as_slice(),
            b"token".as_slice(),
            b"password".as_slice(),
            b"apikey".as_slice(),
            b"bearer".as_slice(),
            b"cookie".as_slice(),
            b"authorization".as_slice(),
        ]
        .iter()
        .any(|marker| {
            compact
                .windows(marker.len())
                .any(|window| window == *marker)
        }) || lower.contains("sk-")
            || lower.contains("sk_")
    }

    /// 识别可能携带 challenge 的连续 ASCII hex，即使它嵌在其它可显示文本中也要脱敏。
    fn has_hex_run(&self) -> bool {
        let mut run = 0_usize;
        for byte in self.bytes {
            if byte.is_ascii_hexdigit() {
                run = run.saturating_add(1);
                if run >= 32 {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        false
    }
}

/// Scheme 名只接受 RFC 风格 ASCII 字母、数字与 `+.-`，不做 Unicode 等价扩张。
fn is_scheme_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'-' | b'.')
}

/// 声明为路径前先把 segment 限制为 ASCII 词法字符，减少本地化文本误报。
fn is_path_byte(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-' | b'%')
}

/// 路径边界只认 ASCII 标点或空白；前导非 ASCII 字节保留常见中文斜线文案，不当作 `/path`。
fn is_path_boundary(bytes: &[u8], index: usize) -> bool {
    index == 0
        || bytes[index - 1].is_ascii_whitespace()
        || matches!(
            bytes[index - 1],
            b':' | b'=' | b'(' | b'[' | b'{' | b'"' | b','
        )
}

/// 通过独立 catalog 模块查询稳定错误映射，避免 framing 文件承担业务表维护。
fn catalog_entry(
    code: i64,
    error_code: &str,
    category: &str,
    retryable: bool,
) -> Option<(&'static str, &'static str)> {
    codec_catalog::catalog_entry(code, error_code, category, retryable)
}
