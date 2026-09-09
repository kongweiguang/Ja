// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use crate::attachment_preview::interface::protocol::attachment_protocol_response;
use image::codecs::gif::{GifEncoder, Repeat};
use image::{Delay, Frame, ImageFormat, Rgba, RgbaImage};
use std::io::Cursor;
use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Default)]
struct FakeClock(AtomicU64);

impl FakeClock {
    /// 测试显式推进单调时钟，避免 sleep 让 TTL 用例变慢或抖动。
    fn advance(&self, seconds: u64) {
        self.0.fetch_add(seconds, Ordering::SeqCst);
    }
}

impl AttachmentPreviewClock for FakeClock {
    /// 测试时钟只暴露受控单调值，使 TTL/LRU 断言不依赖真实墙钟抖动。
    fn now(&self) -> Duration {
        Duration::from_secs(self.0.load(Ordering::SeqCst))
    }
}

/// 构造完整 descriptor，而不是让测试绕过生产 session identity 校验。
fn descriptor(id: &str, kind: AttachmentPreviewKind) -> AttachmentPreviewDescriptor {
    AttachmentPreviewDescriptor {
        preview_session_id: AttachmentPreviewSessionId::try_new(id.to_owned()).expect("session id"),
        attachment_id: "att_12345678".to_owned(),
        display_name: match kind {
            AttachmentPreviewKind::Image => "image.png",
            AttachmentPreviewKind::Text => "notes.txt",
        }
        .to_owned(),
        size_bytes: 64,
        media_kind: match kind {
            AttachmentPreviewKind::Image => "image",
            AttachmentPreviewKind::Text => "text",
        }
        .to_owned(),
        media_type: match kind {
            AttachmentPreviewKind::Image => "image/png",
            AttachmentPreviewKind::Text => "text/plain",
        }
        .to_owned(),
        preview_kind: kind,
    }
}

/// 用最小合法 PNG 派生数据表达缓存字节预算，测试不重复覆盖图片解码实现。
fn derivatives(thumbnail_bytes: usize, preview_bytes: usize) -> AttachmentImageDerivatives {
    AttachmentImageDerivatives {
        thumbnail_png: vec![1; thumbnail_bytes],
        preview_png: vec![2; preview_bytes],
    }
}

/// 注入时钟和缓存上限，使淘汰顺序与到期边界在单元测试中完全确定。
fn host_with_clock(clock: Arc<FakeClock>, cache_bytes: usize) -> AttachmentPreviewHost {
    AttachmentPreviewHost::with_clock(
        AttachmentPreviewLimits {
            idle_ttl: Duration::from_secs(300),
            max_sessions: 32,
            max_cache_bytes: cache_bytes,
        },
        clock,
    )
    .expect("preview host")
}

/// 协议请求夹具只接受测试签发 URL，避免单测绕过真实 URL 解析与授权边界。
fn request_from_url(url: &str) -> AttachmentProtocolRequest {
    parse_attachment_protocol_uri(url).expect("protocol request")
}

/// 辅助窗口即使取得不可猜 token 也不能读取缓存；失败不能延长该 token 的 TTL。
#[test]
fn resource_is_restricted_to_main_webview() {
    let clock = Arc::new(FakeClock::default());
    let host = host_with_clock(clock, 1024);
    let result = host
        .open(
            "main",
            "ws_12345678".to_owned(),
            descriptor("apv_12345678", AttachmentPreviewKind::Image),
            Some(derivatives(8, 16)),
        )
        .expect("open image");
    let request = request_from_url(result.resource_url.as_deref().expect("resource url"));
    let error = host
        .resource("preview_1", request.variant, request.token)
        .expect_err("child webview must be rejected");
    assert_eq!(error.code, AttachmentPreviewErrorCode::WrongWindow);
    assert_eq!(host.cache_bytes().expect("cache bytes"), 24);
}

/// 达到 idle TTL 后资源与两个衍生物一起失效，后续 URL 不能恢复同一授权。
#[test]
fn expired_token_removes_session_and_cache() {
    let clock = Arc::new(FakeClock::default());
    let host = host_with_clock(Arc::clone(&clock), 1024);
    let result = host
        .open(
            "main",
            "ws_12345678".to_owned(),
            descriptor("apv_12345678", AttachmentPreviewKind::Image),
            Some(derivatives(8, 16)),
        )
        .expect("open image");
    let request = request_from_url(result.resource_url.as_deref().expect("resource url"));
    clock.advance(300);
    let error = host
        .resource("main", request.variant, request.token)
        .expect_err("expired token");
    assert_eq!(error.code, AttachmentPreviewErrorCode::TokenExpired);
    assert_eq!(host.cache_bytes().expect("cache bytes"), 0);
    let repeated = host
        .resource("main", request.variant, request.token)
        .expect_err("removed token");
    assert_eq!(repeated.code, AttachmentPreviewErrorCode::TokenNotFound);
}

/// Workspace 切换先取得全部 server identity，显式 clear 随后覆盖本地资源释放。
#[test]
fn workspace_switch_and_exit_clear_owned_resources() {
    let host = host_with_clock(Arc::new(FakeClock::default()), 1024);
    for (id, workspace) in [
        ("apv_12345678", "ws_old1234"),
        ("apv_87654321", "ws_new1234"),
    ] {
        host.open(
            "main",
            workspace.to_owned(),
            descriptor(id, AttachmentPreviewKind::Image),
            Some(derivatives(8, 16)),
        )
        .expect("open image");
    }
    assert_eq!(host.cache_bytes().expect("cache bytes"), 48);
    assert_eq!(host.server_sessions().expect("server sessions").len(), 2);
    assert_eq!(host.clear().expect("clear"), 2);
    assert_eq!(host.cache_bytes().expect("cache bytes"), 0);
}

/// 插入新 session 时按最近访问淘汰最旧衍生物，但 session 自身仍存在并安全返回缺失。
#[test]
fn cache_is_byte_bounded_and_lru() {
    let host = host_with_clock(Arc::new(FakeClock::default()), 40);
    let first = host
        .open(
            "main",
            "ws_12345678".to_owned(),
            descriptor("apv_12345678", AttachmentPreviewKind::Image),
            Some(derivatives(10, 10)),
        )
        .expect("first image");
    let first_preview = request_from_url(first.resource_url.as_deref().expect("preview url"));
    host.resource("main", first_preview.variant, first_preview.token)
        .expect("refresh first preview");
    let second = host
        .open(
            "main",
            "ws_12345678".to_owned(),
            descriptor("apv_87654321", AttachmentPreviewKind::Image),
            Some(derivatives(15, 15)),
        )
        .expect("second image");
    assert_eq!(host.cache_bytes().expect("cache bytes"), 40);
    let first_thumbnail = request_from_url(first.thumbnail_url.as_deref().expect("thumbnail url"));
    assert_eq!(
        host.resource("main", first_thumbnail.variant, first_thumbnail.token)
            .expect_err("oldest thumbnail evicted")
            .code,
        AttachmentPreviewErrorCode::TokenNotFound
    );
    let second_preview = request_from_url(second.resource_url.as_deref().expect("preview url"));
    assert_eq!(
        host.resource("main", second_preview.variant, second_preview.token)
            .expect("second preview")
            .len(),
        15
    );
}

/// parser 同时覆盖原生 custom scheme 与 Windows 的 http localhost 映射，并拒绝任意路径/query。
#[test]
fn protocol_parser_accepts_only_controlled_origins_and_paths() {
    let token = "0123456789abcdef0123456789abcdef";
    let native =
        parse_attachment_protocol_uri(&format!("ja-attachment://localhost/preview/{token}"))
            .expect("native URL");
    let windows =
        parse_attachment_protocol_uri(&format!("http://ja-attachment.localhost/preview/{token}"))
            .expect("Windows URL");
    assert_eq!(native, windows);
    for invalid in [
        format!("file://localhost/preview/{token}"),
        format!("ja-attachment://localhost/preview/{token}?path=C:/secret"),
        format!("ja-attachment://localhost/preview/../{token}"),
        format!("ja-attachment://localhost/file/{token}"),
        format!("ja-attachment://evil/preview/{token}"),
    ] {
        assert!(
            parse_attachment_protocol_uri(&invalid).is_err(),
            "must reject {invalid}"
        );
    }
}

/// 成功响应固定 PNG/no-store/nosniff/CSP，并对错误 token 返回无诊断 body 的 404。
#[test]
fn protocol_response_has_minimal_security_headers() {
    let host = host_with_clock(Arc::new(FakeClock::default()), 1024);
    let opened = host
        .open(
            "main",
            "ws_12345678".to_owned(),
            descriptor("apv_12345678", AttachmentPreviewKind::Image),
            Some(derivatives(8, 16)),
        )
        .expect("open image");
    let response = attachment_protocol_response(
        &host,
        "main",
        opened.resource_url.as_deref().expect("resource url"),
    );
    assert_eq!(response.status(), 200);
    assert_eq!(
        response.headers()[tauri::http::header::CONTENT_TYPE],
        "image/png"
    );
    assert_eq!(
        response.headers()[tauri::http::header::CACHE_CONTROL],
        "no-store, max-age=0"
    );
    assert_eq!(response.headers()["X-Content-Type-Options"], "nosniff");
    assert_eq!(
        response.headers()["Cross-Origin-Resource-Policy"],
        "cross-origin"
    );
    assert_eq!(response.body().len(), 16);

    let missing = attachment_protocol_response(
        &host,
        "main",
        "ja-attachment://localhost/preview/ffffffffffffffffffffffffffffffff",
    );
    assert_eq!(missing.status(), 404);
    assert!(missing.body().is_empty());
    assert_eq!(
        missing.headers()[tauri::http::header::CACHE_CONTROL],
        "no-store, max-age=0"
    );
}

/// 50MP BMP 仅含恶意 header 即在实际像素分配前被拒绝。
#[test]
fn oversized_image_header_is_rejected_before_decode() {
    let mut bmp = vec![0_u8; 54];
    bmp[0..2].copy_from_slice(b"BM");
    bmp[2..6].copy_from_slice(&54_u32.to_le_bytes());
    bmp[10..14].copy_from_slice(&54_u32.to_le_bytes());
    bmp[14..18].copy_from_slice(&40_u32.to_le_bytes());
    bmp[18..22].copy_from_slice(&10_000_i32.to_le_bytes());
    bmp[22..26].copy_from_slice(&5_000_i32.to_le_bytes());
    bmp[26..28].copy_from_slice(&1_u16.to_le_bytes());
    bmp[28..30].copy_from_slice(&24_u16.to_le_bytes());
    let error = derive_attachment_image(&bmp).expect_err("50MP image must fail");
    assert_eq!(error.code, AttachmentPreviewErrorCode::ImageBudgetExceeded);
}

/// GIF 输出必须冻结首帧，避免预览缓存保存动画或在不同 decoder 时机显示不同内容。
#[test]
fn gif_derivative_uses_first_frame() {
    let red = RgbaImage::from_pixel(2, 2, Rgba([255, 0, 0, 255]));
    let blue = RgbaImage::from_pixel(2, 2, Rgba([0, 0, 255, 255]));
    let mut gif = Vec::new();
    {
        let mut encoder = GifEncoder::new(&mut gif);
        encoder.set_repeat(Repeat::Infinite).expect("repeat");
        encoder
            .encode_frames([
                Frame::from_parts(red, 0, 0, Delay::from_numer_denom_ms(10, 1)),
                Frame::from_parts(blue, 0, 0, Delay::from_numer_denom_ms(10, 1)),
            ])
            .expect("encode gif");
    }
    let derived = derive_attachment_image(&gif).expect("derive GIF");
    let preview =
        image::load(Cursor::new(derived.preview_png), ImageFormat::Png).expect("decode preview");
    assert_eq!(preview.to_rgba8().get_pixel(0, 0), &Rgba([255, 0, 0, 255]));
}

/// 损坏图只影响衍生物创建，错误结构不包含原始字节或 decoder 文本。
#[test]
fn corrupt_image_returns_stable_redacted_error() {
    let error = derive_attachment_image(b"not an image").expect_err("corrupt image");
    assert_eq!(error.code, AttachmentPreviewErrorCode::InvalidImage);
    let serialized = serde_json::to_string(&error).expect("serialize error");
    assert!(!serialized.contains("not an image"));
    assert!(!serialized.contains("decoder"));
}
