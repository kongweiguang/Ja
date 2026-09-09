// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 附件预览 JA-RPC v1 的类型化参数与结果投影。
//!
//! 该边界只携带 opaque identity、受限元数据和有界 Base64 分段，不允许路径、hash、
//! blob key 或任意文件读取参数进入 host 调用面。

use super::handshake::valid_schema_id;
use serde::Serialize;
use serde_json::Value;
use std::fmt::{Display, Formatter};

pub const ATTACHMENT_PREVIEW_READ_MIN_BYTES: u64 = 4;
pub const ATTACHMENT_PREVIEW_READ_MAX_BYTES: u64 = 65_536;
const MAX_ATTACHMENT_BYTES: u64 = 100 * 1024 * 1024;

/// 类型化预览 DTO 的稳定本地错误；不携带 wire value，避免诊断泄露附件内容。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AttachmentPreviewContractError {
    InvalidInput,
    InvalidResult,
}

impl Display for AttachmentPreviewContractError {
    /// 使用固定错误文本，调用方不得依赖或展示被拒绝的原始协议内容。
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidInput => formatter.write_str("invalid attachment preview input"),
            Self::InvalidResult => formatter.write_str("invalid attachment preview result"),
        }
    }
}

impl std::error::Error for AttachmentPreviewContractError {}

/// 预览授权是互斥判别联合：草稿只绑定当前 Workspace，历史附件必须绑定 Thread。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum AttachmentPreviewAuthorization {
    Draft {
        #[serde(rename = "workspaceId")]
        workspace_id: String,
    },
    Thread {
        #[serde(rename = "threadId")]
        thread_id: String,
    },
}

/// `attachment/preview/open` 的封闭参数；只能通过 DRAFT/THREAD 构造器创建。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentPreviewOpenParams {
    attachment_id: String,
    authorization: AttachmentPreviewAuthorization,
}

impl AttachmentPreviewOpenParams {
    /// DRAFT 必须同时携带当前 Workspace identity，防止仅凭附件 ID 跨 Workspace 预览。
    pub fn draft(
        attachment_id: impl Into<String>,
        workspace_id: impl Into<String>,
    ) -> Result<Self, AttachmentPreviewContractError> {
        let attachment_id = attachment_id.into();
        let workspace_id = workspace_id.into();
        if !valid_schema_id(&attachment_id, "att_", 128)
            || !valid_schema_id(&workspace_id, "ws_", 128)
        {
            return Err(AttachmentPreviewContractError::InvalidInput);
        }
        Ok(Self {
            attachment_id,
            authorization: AttachmentPreviewAuthorization::Draft { workspace_id },
        })
    }

    /// THREAD 必须携带会话 identity，让 App Server 校验排队预留或消息绑定归属。
    pub fn thread(
        attachment_id: impl Into<String>,
        thread_id: impl Into<String>,
    ) -> Result<Self, AttachmentPreviewContractError> {
        let attachment_id = attachment_id.into();
        let thread_id = thread_id.into();
        if !valid_schema_id(&attachment_id, "att_", 128)
            || !valid_schema_id(&thread_id, "thr_", 100)
        {
            return Err(AttachmentPreviewContractError::InvalidInput);
        }
        Ok(Self {
            attachment_id,
            authorization: AttachmentPreviewAuthorization::Thread { thread_id },
        })
    }

    /// 在唯一 RPC bridge 内序列化已验证参数，不向调用方开放可修改的 JSON object。
    pub(crate) fn into_value(self) -> Result<Value, AttachmentPreviewContractError> {
        serde_json::to_value(self).map_err(|_| AttachmentPreviewContractError::InvalidInput)
    }
}

/// `attachment/preview/read` 的有界分页参数；limit 与 App Server 的 64 KiB 硬上限一致。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentPreviewReadParams {
    preview_session_id: String,
    offset_bytes: u64,
    limit_bytes: u64,
}

impl AttachmentPreviewReadParams {
    /// 在进入 request queue 前拒绝越界分页，避免无效调用占用有限 preview session。
    pub fn new(
        preview_session_id: impl Into<String>,
        offset_bytes: u64,
        limit_bytes: u64,
    ) -> Result<Self, AttachmentPreviewContractError> {
        let preview_session_id = preview_session_id.into();
        if !valid_preview_session_id(&preview_session_id)
            || offset_bytes > MAX_ATTACHMENT_BYTES
            || !(ATTACHMENT_PREVIEW_READ_MIN_BYTES..=ATTACHMENT_PREVIEW_READ_MAX_BYTES)
                .contains(&limit_bytes)
        {
            return Err(AttachmentPreviewContractError::InvalidInput);
        }
        Ok(Self {
            preview_session_id,
            offset_bytes,
            limit_bytes,
        })
    }

    /// 在唯一 RPC bridge 内序列化已验证参数，不接受任意 offset/limit 字段别名。
    pub(crate) fn into_value(self) -> Result<Value, AttachmentPreviewContractError> {
        serde_json::to_value(self).map_err(|_| AttachmentPreviewContractError::InvalidInput)
    }
}

/// `attachment/preview/close` 只接受 opaque session identity，不接受附件或路径选择器。
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentPreviewCloseParams {
    preview_session_id: String,
}

impl AttachmentPreviewCloseParams {
    /// 关闭请求预先校验 namespace，使任意字符串不能进入 App Server session registry。
    pub fn new(
        preview_session_id: impl Into<String>,
    ) -> Result<Self, AttachmentPreviewContractError> {
        let preview_session_id = preview_session_id.into();
        if !valid_preview_session_id(&preview_session_id) {
            return Err(AttachmentPreviewContractError::InvalidInput);
        }
        Ok(Self { preview_session_id })
    }

    /// 在唯一 RPC bridge 内序列化已验证参数，保持 close wire shape 为单字段闭集。
    pub(crate) fn into_value(self) -> Result<Value, AttachmentPreviewContractError> {
        serde_json::to_value(self).map_err(|_| AttachmentPreviewContractError::InvalidInput)
    }
}

/// generic supervisor request 仍是公共兼容面，因此对三个预览方法重复执行同一闭集 admission。
pub(crate) fn validate_attachment_preview_request(method: &str, params: &Value) -> bool {
    let Some(object) = params.as_object() else {
        return false;
    };
    let exact = |fields: &[&str]| {
        object.len() == fields.len() && object.keys().all(|field| fields.contains(&field.as_str()))
    };
    match method {
        "attachment/preview/open" => {
            if !exact(&["attachmentId", "authorization"])
                || !object
                    .get("attachmentId")
                    .and_then(Value::as_str)
                    .is_some_and(|value| valid_schema_id(value, "att_", 128))
            {
                return false;
            }
            let Some(authorization) = object.get("authorization").and_then(Value::as_object) else {
                return false;
            };
            match authorization.get("kind").and_then(Value::as_str) {
                Some("draft") => {
                    authorization.len() == 2
                        && authorization
                            .keys()
                            .all(|field| matches!(field.as_str(), "kind" | "workspaceId"))
                        && authorization
                            .get("workspaceId")
                            .and_then(Value::as_str)
                            .is_some_and(|value| valid_schema_id(value, "ws_", 128))
                }
                Some("thread") => {
                    authorization.len() == 2
                        && authorization
                            .keys()
                            .all(|field| matches!(field.as_str(), "kind" | "threadId"))
                        && authorization
                            .get("threadId")
                            .and_then(Value::as_str)
                            .is_some_and(|value| valid_schema_id(value, "thr_", 100))
                }
                _ => false,
            }
        }
        "attachment/preview/read" => {
            exact(&["previewSessionId", "offsetBytes", "limitBytes"])
                && object
                    .get("previewSessionId")
                    .and_then(Value::as_str)
                    .is_some_and(valid_preview_session_id)
                && object
                    .get("offsetBytes")
                    .and_then(Value::as_u64)
                    .is_some_and(|value| value <= MAX_ATTACHMENT_BYTES)
                && object
                    .get("limitBytes")
                    .and_then(Value::as_u64)
                    .is_some_and(|value| {
                        (ATTACHMENT_PREVIEW_READ_MIN_BYTES..=ATTACHMENT_PREVIEW_READ_MAX_BYTES)
                            .contains(&value)
                    })
        }
        "attachment/preview/close" => {
            exact(&["previewSessionId"])
                && object
                    .get("previewSessionId")
                    .and_then(Value::as_str)
                    .is_some_and(valid_preview_session_id)
        }
        _ => false,
    }
}

/// App Server 签发的安全预览元数据；只包含 renderer 展示和资源路由所需字段。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentPreviewOpenResult {
    pub preview_session_id: String,
    pub attachment_id: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub media_kind: String,
    pub media_type: String,
    pub preview_kind: String,
}

impl TryFrom<&Value> for AttachmentPreviewOpenResult {
    type Error = AttachmentPreviewContractError;

    /// 严格校验 open 结果闭集与预览类型，防止路径或未来内部字段被宽松 DTO 吞掉。
    fn try_from(value: &Value) -> Result<Self, Self::Error> {
        let object = exact_object(
            value,
            &[
                "previewSessionId",
                "attachmentId",
                "displayName",
                "sizeBytes",
                "mediaKind",
                "mediaType",
                "previewKind",
            ],
        )?;
        let result = Self {
            preview_session_id: required_text(object.get("previewSessionId"))?.to_owned(),
            attachment_id: required_text(object.get("attachmentId"))?.to_owned(),
            display_name: required_text(object.get("displayName"))?.to_owned(),
            size_bytes: object
                .get("sizeBytes")
                .and_then(Value::as_u64)
                .ok_or(AttachmentPreviewContractError::InvalidResult)?,
            media_kind: required_text(object.get("mediaKind"))?.to_owned(),
            media_type: required_text(object.get("mediaType"))?.to_owned(),
            preview_kind: required_text(object.get("previewKind"))?.to_owned(),
        };
        if !valid_preview_session_id(&result.preview_session_id)
            || !valid_schema_id(&result.attachment_id, "att_", 128)
            || result.display_name.len() > 1_024
            || result.display_name.chars().any(char::is_control)
            || result.size_bytes > MAX_ATTACHMENT_BYTES
            || !matches!(result.media_kind.as_str(), "image" | "text")
            || result.media_kind != result.preview_kind
            || !valid_media_type(&result.media_type)
        {
            return Err(AttachmentPreviewContractError::InvalidResult);
        }
        Ok(result)
    }
}

/// App Server 返回的单段预览内容；Base64 解码长度必须与 offset 差值完全一致。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentPreviewReadResult {
    pub preview_session_id: String,
    pub offset_bytes: u64,
    pub next_offset_bytes: u64,
    pub content_base64: String,
    pub eof: bool,
    pub truncated: bool,
}

impl TryFrom<&Value> for AttachmentPreviewReadResult {
    type Error = AttachmentPreviewContractError;

    /// 校验分页单调性、64 KiB 上限和 Base64 长度，阻止伪 EOF 或错位分段进入缓存。
    fn try_from(value: &Value) -> Result<Self, Self::Error> {
        let object = exact_object(
            value,
            &[
                "previewSessionId",
                "offsetBytes",
                "nextOffsetBytes",
                "contentBase64",
                "eof",
                "truncated",
            ],
        )?;
        let result = Self {
            preview_session_id: required_text(object.get("previewSessionId"))?.to_owned(),
            offset_bytes: object
                .get("offsetBytes")
                .and_then(Value::as_u64)
                .ok_or(AttachmentPreviewContractError::InvalidResult)?,
            next_offset_bytes: object
                .get("nextOffsetBytes")
                .and_then(Value::as_u64)
                .ok_or(AttachmentPreviewContractError::InvalidResult)?,
            content_base64: object
                .get("contentBase64")
                .and_then(Value::as_str)
                .ok_or(AttachmentPreviewContractError::InvalidResult)?
                .to_owned(),
            eof: object
                .get("eof")
                .and_then(Value::as_bool)
                .ok_or(AttachmentPreviewContractError::InvalidResult)?,
            truncated: object
                .get("truncated")
                .and_then(Value::as_bool)
                .ok_or(AttachmentPreviewContractError::InvalidResult)?,
        };
        let span = result
            .next_offset_bytes
            .checked_sub(result.offset_bytes)
            .filter(|span| *span <= ATTACHMENT_PREVIEW_READ_MAX_BYTES)
            .ok_or(AttachmentPreviewContractError::InvalidResult)?;
        if !valid_preview_session_id(&result.preview_session_id)
            || result.next_offset_bytes > MAX_ATTACHMENT_BYTES
            || (!result.eof && span == 0)
            || (result.truncated && !result.eof)
            || decoded_base64_len(&result.content_base64) != Some(span)
        {
            return Err(AttachmentPreviewContractError::InvalidResult);
        }
        Ok(result)
    }
}

/// close 的幂等确认；session identity 必须与 opaque namespace 一致且 `closed` 为 true。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentPreviewCloseResult {
    pub preview_session_id: String,
    pub closed: bool,
}

impl TryFrom<&Value> for AttachmentPreviewCloseResult {
    type Error = AttachmentPreviewContractError;

    /// 拒绝 false 或扩展字段，避免调用方把未关闭 session 当成已释放资源。
    fn try_from(value: &Value) -> Result<Self, Self::Error> {
        let object = exact_object(value, &["previewSessionId", "closed"])?;
        let result = Self {
            preview_session_id: required_text(object.get("previewSessionId"))?.to_owned(),
            closed: object
                .get("closed")
                .and_then(Value::as_bool)
                .ok_or(AttachmentPreviewContractError::InvalidResult)?,
        };
        if !valid_preview_session_id(&result.preview_session_id) || !result.closed {
            return Err(AttachmentPreviewContractError::InvalidResult);
        }
        Ok(result)
    }
}

/// 只接受完整字段闭集，避免 serde 忽略未知字段造成 path/hash/blob key 泄露。
fn exact_object<'a>(
    value: &'a Value,
    fields: &[&str],
) -> Result<&'a serde_json::Map<String, Value>, AttachmentPreviewContractError> {
    let object = value
        .as_object()
        .ok_or(AttachmentPreviewContractError::InvalidResult)?;
    if object.len() != fields.len() || object.keys().any(|field| !fields.contains(&field.as_str()))
    {
        return Err(AttachmentPreviewContractError::InvalidResult);
    }
    Ok(object)
}

/// 要求非空文本，避免数字或 null 在 Rust 边界被隐式字符串化。
fn required_text(value: Option<&Value>) -> Result<&str, AttachmentPreviewContractError> {
    value
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or(AttachmentPreviewContractError::InvalidResult)
}

/// 固定 preview session namespace；值保持 opaque，不解析 UUID 或依赖生成算法。
fn valid_preview_session_id(value: &str) -> bool {
    valid_schema_id(value, "apv_", 128)
}

/// 校验展示 MIME 的受限 ASCII 形状，不接受参数、空白或控制字符。
fn valid_media_type(value: &str) -> bool {
    let Some((kind, subtype)) = value.split_once('/') else {
        return false;
    };
    !kind.is_empty()
        && !subtype.is_empty()
        && value.len() <= 128
        && value.bytes().all(|byte| {
            byte.is_ascii_lowercase() || byte.is_ascii_digit() || b".+-/".contains(&byte)
        })
}

/// 只计算标准 padded Base64 的解码长度；Rust host 不在协议 DTO 层复制或保存内容。
fn decoded_base64_len(value: &str) -> Option<u64> {
    if value.is_empty() {
        return Some(0);
    }
    if !value.as_bytes().chunks_exact(4).remainder().is_empty()
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'+' | b'/' | b'='))
    {
        return None;
    }
    let padding = value.bytes().rev().take_while(|byte| *byte == b'=').count();
    if padding > 2 || value[..value.len() - padding].contains('=') {
        return None;
    }
    let decoded = value
        .len()
        .checked_div(4)?
        .checked_mul(3)?
        .checked_sub(padding)?;
    u64::try_from(decoded).ok()
}
