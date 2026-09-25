// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! `ja-attachment://` 到 Tauri HTTP response 的唯一适配边界。

use crate::attachment_preview::host::AttachmentPreviewHost;
use crate::attachment_preview::protocol::{
    AttachmentProtocolHeaders, attachment_protocol_status, parse_attachment_protocol_uri,
};

/// Tauri async protocol adapter 将纯解析结果交给 native host；body 从缓存 Arc 复制到独立
/// response，因此 responder 生命周期不持有 host lock，也不会延长 session 授权。
pub(crate) fn attachment_protocol_response(
    host: &AttachmentPreviewHost,
    window_label: &str,
    uri: &str,
) -> tauri::http::Response<Vec<u8>> {
    let result = parse_attachment_protocol_uri(uri)
        .and_then(|request| host.resource(window_label, request.variant, request.token));
    match result {
        Ok(bytes) => build_protocol_response(200, bytes.as_ref().to_vec()),
        Err(error) => build_protocol_response(attachment_protocol_status(error), Vec::new()),
    }
}

/// setup 尚未托管 host 的异常窗口使用空 404，避免 responder panic 或暴露 composition 时序。
pub(crate) fn attachment_protocol_unavailable_response() -> tauri::http::Response<Vec<u8>> {
    build_protocol_response(404, Vec::new())
}

/// 成功与失败使用同一防缓存、防 sniff 头，避免错误分支重新变成可探测或可持久化资源。
fn build_protocol_response(status: u16, body: Vec<u8>) -> tauri::http::Response<Vec<u8>> {
    let headers = AttachmentProtocolHeaders::default();
    tauri::http::Response::builder()
        .status(status)
        .header(tauri::http::header::CONTENT_TYPE, headers.content_type)
        .header(
            tauri::http::header::CONTENT_SECURITY_POLICY,
            headers.content_security_policy,
        )
        .header(tauri::http::header::CACHE_CONTROL, headers.cache_control)
        .header(
            tauri::http::header::CONTENT_DISPOSITION,
            headers.content_disposition,
        )
        .header(
            "Cross-Origin-Resource-Policy",
            headers.cross_origin_resource_policy,
        )
        .header("X-Content-Type-Options", headers.x_content_type_options)
        .body(body)
        // 头和值全部为编译期常量，builder 失败只可能来自当前代码漂移；退化为无头空响应仍不得 panic。
        .unwrap_or_else(|_| tauri::http::Response::new(Vec::new()))
}
