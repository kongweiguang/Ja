// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 已验证 RpcFrame 值对象、方向判定与出站编码。

use super::codec::{
    contains_ready_token_marker, reject_ready_token_markers, validate_ready_token_fields,
};
use super::error_policy::{
    CodecError, RpcError, redact_debug_text, redact_debug_value, validate_error,
};
use super::limits::{MAX_METHOD_BYTES, MAX_REQUEST_ID_BYTES};
use serde_json::{Map, Value};
use std::fmt::Formatter;

/// 保留字段是否出现，避免 serde 的 `Option` 把缺失和显式 null 混成同一个状态。
#[derive(Debug, Clone, PartialEq)]
pub struct Present<T> {
    present: bool,
    value: Option<T>,
}

impl<T> Present<T> {
    /// 构造缺失字段，保留与显式 null 不同的协议语义。
    pub fn missing() -> Self {
        Self {
            present: false,
            value: None,
        }
    }

    /// 构造存在且带值的字段，供 response/result 和测试 fixture 共用。
    pub fn some(value: T) -> Self {
        Self {
            present: true,
            value: Some(value),
        }
    }

    /// 判断 wire object 是否显式包含此字段。
    pub fn is_present(&self) -> bool {
        self.present
    }

    /// 借用字段值，调用方无需消费整个 envelope。
    pub fn value(&self) -> Option<&T> {
        self.value.as_ref()
    }
}

/// 已通过根 envelope 校验的 JSON-RPC frame。
#[derive(Clone, PartialEq)]
pub struct RpcFrame {
    pub(super) id: Option<String>,
    pub(super) method: Option<String>,
    pub(super) params: Option<Value>,
    pub(super) result: Present<Value>,
    pub(super) error: Option<RpcError>,
}

impl std::fmt::Debug for RpcFrame {
    /// frame 进入日志或 UI 诊断前脱敏 challenge 形状文本，避免 Debug 绕过 wire 约束。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        let params = self.params.as_ref().map(redact_debug_value);
        let result = self.result.value().map(redact_debug_value);
        formatter
            .debug_struct("RpcFrame")
            .field("id", &self.id.as_deref().map(redact_debug_text))
            .field("method", &self.method.as_deref().map(redact_debug_text))
            .field("params", &params)
            .field("result_present", &self.result.is_present())
            .field("result", &result)
            .field("error", &self.error)
            .finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum FrameKind {
    ClientRequest,
    Notification,
    Response,
}

impl RpcFrame {
    /// 构造并立即校验 client namespace，防止业务线程把 server ID 发到 wire。
    pub fn client_request(
        id: impl Into<String>,
        method: impl Into<String>,
        params: Value,
    ) -> Result<Self, CodecError> {
        Self::request(id, method, params)
    }

    /// 构造并立即校验唯一的 client namespace，防止业务线程把其它方向的 ID 发到 wire。
    fn request(
        id: impl Into<String>,
        method: impl Into<String>,
        params: Value,
    ) -> Result<Self, CodecError> {
        let frame = Self {
            id: Some(id.into()),
            method: Some(method.into()),
            params: Some(params),
            result: Present::missing(),
            error: None,
        };
        if frame.validate()? != FrameKind::ClientRequest {
            return Err(CodecError::InvalidId);
        }
        Ok(frame)
    }

    /// 构造没有 ID 的 notification；notification 不会占用 pending 槽位。
    pub fn notification(method: impl Into<String>, params: Value) -> Result<Self, CodecError> {
        let frame = Self {
            id: None,
            method: Some(method.into()),
            params: Some(params),
            result: Present::missing(),
            error: None,
        };
        frame.validate()?;
        Ok(frame)
    }

    /// 返回 request/notification 的可选 method，避免调用方直接构造非法 envelope。
    pub fn method(&self) -> Option<&str> {
        self.method.as_deref()
    }

    /// 返回已校验 params 的只读借用，保持 RpcFrame 字段不变式由构造器持有。
    pub fn params(&self) -> Option<&Value> {
        self.params.as_ref()
    }

    /// 返回 response result 的存在性对象，显式 null 与缺失保持可区分。
    pub fn result(&self) -> &Present<Value> {
        &self.result
    }

    /// 返回受控错误投影，调用方不能绕过 catalog 修改 error 字段。
    pub fn error(&self) -> Option<&RpcError> {
        self.error.as_ref()
    }

    /// 返回安全的空默认 ID，避免诊断/拒绝路径因 malformed frame 再次 panic。
    pub fn id(&self) -> &str {
        self.id.as_deref().unwrap_or_default()
    }

    /// 返回可选原始 ID，供测试和路由区分缺失 ID 与空字符串。
    pub fn id_opt(&self) -> Option<&str> {
        self.id.as_deref()
    }

    /// 以严格单 frame JSONL 编码；调用方可在写队列入队前检查长度。
    pub fn encode(&self, max_frame_bytes: usize) -> Result<Vec<u8>, CodecError> {
        self.validate()?;
        let mut object = Map::new();
        object.insert("jsonrpc".to_owned(), Value::String("2.0".to_owned()));
        if let Some(id) = &self.id {
            object.insert("id".to_owned(), Value::String(id.clone()));
        }
        if let Some(method) = &self.method {
            object.insert("method".to_owned(), Value::String(method.clone()));
        }
        if let Some(params) = &self.params {
            object.insert("params".to_owned(), params.clone());
        }
        if self.result.is_present() {
            object.insert(
                "result".to_owned(),
                self.result.value().cloned().unwrap_or(Value::Null),
            );
        }
        if let Some(error) = &self.error {
            let mut error_object = Map::new();
            error_object.insert("code".to_owned(), Value::Number(error.code.into()));
            error_object.insert("message".to_owned(), Value::String(error.message.clone()));
            error_object.insert("data".to_owned(), error.data.clone());
            object.insert("error".to_owned(), Value::Object(error_object));
        }
        let value = Value::Object(object);
        let allow_ready_token_path = self.method.as_deref() == Some("runtime/initialized")
            || (self.method.as_deref() == Some("runtime/status-changed")
                && self
                    .params
                    .as_ref()
                    .and_then(|params| params.get("status"))
                    .and_then(Value::as_str)
                    == Some("ready"));
        // 重建 wire object 后再次执行 raw guard，确保未来加入 RpcFrame 的字段不能
        // 绕过 outbound 脱敏门。
        reject_ready_token_markers(&value, allow_ready_token_path)?;
        let mut encoded = serde_json::to_vec(&value).map_err(|_| CodecError::InvalidJson)?;
        if encoded.len() > max_frame_bytes {
            return Err(CodecError::FrameTooLarge {
                actual: encoded.len(),
                max: max_frame_bytes,
            });
        }
        encoded.push(b'\n');
        Ok(encoded)
    }

    /// 在手工构造和 decode 两条路径上共享同一 envelope 校验，防止内部 caller
    /// 通过构造器绕过唯一 client namespace、result/error oneOf 或稳定 error data 约束。
    pub(crate) fn validate(&self) -> Result<FrameKind, CodecError> {
        if let Some(id) = &self.id
            && !valid_wire_id(id)
        {
            return Err(CodecError::InvalidId);
        }
        if let Some(method) = &self.method {
            if method.is_empty() || method.len() > MAX_METHOD_BYTES {
                return Err(CodecError::InvalidEnvelope);
            }
            if !self.params.as_ref().is_some_and(Value::is_object) {
                return Err(CodecError::InvalidEnvelope);
            }
            let params = self.params.as_ref().ok_or(CodecError::InvalidEnvelope)?;
            validate_ready_token_fields(method, params)?;
        }
        if let Some(error) = &self.error {
            validate_error(error)?;
        }
        if self.result.value().is_some_and(contains_ready_token_marker)
            || self
                .error
                .as_ref()
                .is_some_and(|error| contains_ready_token_marker(&error.data))
        {
            return Err(CodecError::InvalidEnvelope);
        }
        let response = self.method.is_none()
            && self.id.is_some()
            && (self.result.is_present() ^ self.error.is_some());
        let request_or_notification =
            self.method.is_some() && !self.result.is_present() && self.error.is_none();
        if response {
            return Ok(match self.id.as_deref() {
                Some(id) if valid_wire_id(id) => FrameKind::Response,
                _ => return Err(CodecError::InvalidId),
            });
        }
        if request_or_notification {
            return Ok(match self.id.as_deref() {
                Some(id) if id.starts_with("c:") => FrameKind::ClientRequest,
                None => FrameKind::Notification,
                _ => return Err(CodecError::InvalidId),
            });
        }
        Err(CodecError::InvalidEnvelope)
    }
}

/// 检查冻结 c: namespace、ASCII 字符集和 98-byte 总长度。
pub(super) fn valid_wire_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    if bytes.is_empty() || bytes.len() > MAX_REQUEST_ID_BYTES {
        return false;
    }
    let Some((prefix, suffix)) = id.split_once(':') else {
        return false;
    };
    if prefix != "c" {
        return false;
    }
    let suffix_bytes = suffix.as_bytes();
    if suffix_bytes.is_empty() || suffix_bytes.len() > 96 {
        return false;
    }
    suffix_bytes[0].is_ascii_alphanumeric()
        && suffix_bytes[1..].iter().all(|byte| {
            byte.is_ascii_alphanumeric() || *byte == b'.' || *byte == b'_' || *byte == b'-'
        })
}

/// 把 client-owned request 严格限制在 `c:` namespace，避免与服务端 ID 冲突。
pub(super) fn valid_client_id(id: &str) -> bool {
    valid_wire_id(id) && id.starts_with("c:")
}
