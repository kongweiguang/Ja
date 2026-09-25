// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! `ja-attachment://` 的纯解析、状态映射与最小安全响应头模型。

use super::error::{AttachmentPreviewError, AttachmentPreviewErrorCode};
use super::model::{
    ATTACHMENT_PREVIEW_HOST, ATTACHMENT_PREVIEW_SCHEME, AttachmentResourceToken,
    AttachmentResourceVariant,
};

/// 注册 adapter 只需把 URI 转为此闭集，不得将 path/query 传给 filesystem API。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AttachmentProtocolRequest {
    pub variant: AttachmentResourceVariant,
    pub token: AttachmentResourceToken,
}

/// protocol response 只服务已编码 PNG，不反射请求头、扩展名或上游 MIME。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AttachmentProtocolHeaders {
    pub content_type: &'static str,
    pub content_security_policy: &'static str,
    pub cache_control: &'static str,
    pub content_disposition: &'static str,
    pub cross_origin_resource_policy: &'static str,
    pub x_content_type_options: &'static str,
}

impl Default for AttachmentProtocolHeaders {
    /// 所有响应统一使用最小图片头，错误分支不得从请求或上游 MIME 反射额外能力。
    fn default() -> Self {
        Self {
            content_type: "image/png",
            content_security_policy: "default-src 'none'; sandbox",
            cache_control: "no-store, max-age=0",
            content_disposition: "inline",
            // 自定义协议在 Windows 映射为独立 localhost origin；授权由随机 token +
            // requester window 双重校验承担，因此必须允许主页面跨 origin 嵌入图片。
            cross_origin_resource_policy: "cross-origin",
            x_content_type_options: "nosniff",
        }
    }
}

/// 只接受无 query/fragment/userinfo/port 的 canonical URL 和两个固定 path segment。
pub fn parse_attachment_protocol_uri(
    uri: &str,
) -> Result<AttachmentProtocolRequest, AttachmentPreviewError> {
    let parsed = url::Url::parse(uri)
        .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::InvalidInput))?;
    let canonical_origin = parsed.scheme() == ATTACHMENT_PREVIEW_SCHEME
        && parsed.host_str() == Some(ATTACHMENT_PREVIEW_HOST);
    let windows_origin =
        parsed.scheme() == "http" && parsed.host_str() == Some("ja-attachment.localhost");
    if (!canonical_origin && !windows_origin)
        || parsed.port().is_some()
        || !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some()
    {
        return Err(AttachmentPreviewError::new(
            AttachmentPreviewErrorCode::InvalidInput,
        ));
    }
    let segments = parsed
        .path_segments()
        .map(|segments| segments.collect::<Vec<_>>())
        .unwrap_or_default();
    if segments.len() != 2 {
        return Err(AttachmentPreviewError::new(
            AttachmentPreviewErrorCode::InvalidInput,
        ));
    }
    let variant = AttachmentResourceVariant::parse(segments[0])
        .ok_or_else(|| AttachmentPreviewError::new(AttachmentPreviewErrorCode::InvalidInput))?;
    let token = AttachmentResourceToken::parse(segments[1])
        .ok_or_else(|| AttachmentPreviewError::new(AttachmentPreviewErrorCode::TokenNotFound))?;
    Ok(AttachmentProtocolRequest { variant, token })
}

/// 注册失败响应保持固定 HTTP 状态，不把 token 是否曾存在变成可枚举的网络 oracle。
pub const fn attachment_protocol_status(error: AttachmentPreviewError) -> u16 {
    match error.code {
        AttachmentPreviewErrorCode::WrongWindow => 403,
        AttachmentPreviewErrorCode::InvalidInput => 400,
        _ => 404,
    }
}
