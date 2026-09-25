// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! ja-runtime 强类型 preview DTO 到 native host 模型的唯一投影。

use super::error::{AttachmentPreviewError, AttachmentPreviewErrorCode};
use super::model::{
    ATTACHMENT_PREVIEW_TEXT_BYTES, AttachmentPreviewDescriptor, AttachmentPreviewKind,
    AttachmentPreviewSessionId, AttachmentTextReadResult,
};
use base64::Engine;
use ja_runtime::app_server_process::{AttachmentPreviewOpenResult, AttachmentPreviewReadResult};

/// runtime 已拒绝扩展字段；这里再把字符串 kind 收紧为 enum，并冻结 server session 为 native-only 值。
pub fn project_preview_descriptor(
    result: AttachmentPreviewOpenResult,
) -> Result<AttachmentPreviewDescriptor, AttachmentPreviewError> {
    let preview_kind = match result.preview_kind.as_str() {
        "image" => AttachmentPreviewKind::Image,
        "text" => AttachmentPreviewKind::Text,
        _ => return Err(source_error()),
    };
    let preview_session_id =
        AttachmentPreviewSessionId::try_new(result.preview_session_id).ok_or_else(source_error)?;
    Ok(AttachmentPreviewDescriptor {
        preview_session_id,
        attachment_id: result.attachment_id,
        display_name: result.display_name,
        size_bytes: result.size_bytes,
        media_kind: result.media_kind,
        media_type: result.media_type,
        preview_kind,
    })
}

/// 文本分段解码为 UTF-8，并把 public session identity 与 App Server identity 分离。
pub fn project_text_preview_chunk(
    public_session_id: String,
    server_session_id: &AttachmentPreviewSessionId,
    result: AttachmentPreviewReadResult,
) -> Result<AttachmentTextReadResult, AttachmentPreviewError> {
    if result.preview_session_id != server_session_id.as_str()
        || result.next_offset_bytes > ATTACHMENT_PREVIEW_TEXT_BYTES
        || (result.truncated && !result.eof)
    {
        return Err(source_error());
    }
    let decoded = decode_base64(&result.content_base64)?;
    let content = String::from_utf8(decoded).map_err(|_| source_error())?;
    Ok(AttachmentTextReadResult {
        preview_session_id: public_session_id,
        offset_bytes: result.offset_bytes,
        next_offset_bytes: result.next_offset_bytes,
        end_of_file: result.eof,
        truncated: result.truncated,
        content,
    })
}

/// 标准 Base64 是 JA-RPC 的唯一 binary 表示；禁止宽松 URL-safe 或忽略 padding 的 fallback。
fn decode_base64(value: &str) -> Result<Vec<u8>, AttachmentPreviewError> {
    base64::engine::general_purpose::STANDARD
        .decode(value)
        .map_err(|_| source_error())
}

/// 所有 wire/解码漂移收敛为同一脱敏、可重试错误，不泄漏 chunk 内容或 server payload。
const fn source_error() -> AttachmentPreviewError {
    AttachmentPreviewError::new(AttachmentPreviewErrorCode::SourceUnavailable)
}
