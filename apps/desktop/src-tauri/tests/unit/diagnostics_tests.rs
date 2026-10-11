// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::io::{self, Write};
use std::sync::{Arc, Mutex};

use tracing_subscriber::prelude::*;

use super::RedactedEventFormat;

// 验证外部事件的真实 target 契约，不为测试暴露生产模块的私有常量。
const WEBSOCKET_PUMP_DIAGNOSTIC_TARGET: &str = "ja.diagnostics.websocket_pump";
const PREVIEW_NAVIGATION_DIAGNOSTIC_TARGET: &str = "ja.diagnostics.preview_navigation";

/// 在 tracing 写入器工厂与断言之间共享内存 sink，避免测试输出引入任何文件路径。
#[derive(Clone)]
struct SharedWriter(Arc<Mutex<Vec<u8>>>);

impl Write for SharedWriter {
    /// 把单次写入追加到共享缓冲区，使断言能够观察 formatter 的完整输出边界。
    fn write(&mut self, buffer: &[u8]) -> io::Result<usize> {
        self.0
            .lock()
            .expect("test log sink poisoned")
            .extend_from_slice(buffer);
        Ok(buffer.len())
    }

    /// 内存 sink 没有外部缓冲层，flush 保持成功即可维持 `Write` 契约。
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// 验证原生日志丢弃敏感载荷，只保留 Rust 白名单允许的 UI 故障码和有界指标字段。
#[test]
fn formatter_omits_native_payloads_and_keeps_only_allowlisted_ui_codes() {
    let output = Arc::new(Mutex::new(Vec::new()));
    let writer_output = Arc::clone(&output);
    let subscriber = tracing_subscriber::fmt::layer()
        .with_ansi(false)
        .event_format(RedactedEventFormat::default())
        .with_writer(move || SharedWriter(Arc::clone(&writer_output)));
    tracing::subscriber::with_default(tracing_subscriber::registry().with(subscriber), || {
        tracing::error!(
            path = %r"C:\private\prompt.txt",
            command = %"print secret",
            "sensitive payload"
        );
        tracing::event!(
            target: tauri_plugin_log::WEBVIEW_TARGET,
            tracing::Level::ERROR,
            message = %"ui.react_error_boundary",
            file = %r"C:\private\ui.tsx"
        );
        tracing::event!(
            target: tauri_plugin_log::WEBVIEW_TARGET,
            tracing::Level::ERROR,
            message = %"prompt content must not pass"
        );
        tracing::info!(
            target: "ja.metrics.event_queue",
            metric = "task_progress_coalesced_total",
            count = 8_u64,
            lane = "data",
            task_thread_id = "thr_secret",
            "runtime event queue metric"
        );
        tracing::info!(
            target: "ja.metrics.event_queue",
            metric = "unknown_metric",
            count = 99_u64,
            path = %r"C:\private\metric.txt",
            "unknown runtime metric"
        );
    });
    let rendered = String::from_utf8(output.lock().expect("test log sink poisoned").clone())
        .expect("formatter must emit UTF-8");

    assert!(rendered.contains("code=ui.react_error_boundary"));
    assert!(rendered.contains("metric=task_progress_coalesced_total lane=data count=8"));
    assert!(!rendered.contains("unknown_metric"));
    assert!(!rendered.contains("thr_secret"));
    assert!(!rendered.contains("private"));
    assert!(!rendered.contains("print secret"));
    assert!(!rendered.contains("sensitive payload"));
    assert!(!rendered.contains("prompt content"));
}

/// 验证启动诊断只保留安全的阶段、错误类别和数值代际，异常字段不会穿过 formatter。
#[test]
fn formatter_keeps_allowlisted_runtime_startup_diagnostics_only() {
    let output = Arc::new(Mutex::new(Vec::new()));
    let writer_output = Arc::clone(&output);
    let subscriber = tracing_subscriber::fmt::layer()
        .with_ansi(false)
        .event_format(RedactedEventFormat::default())
        .with_writer(move || SharedWriter(Arc::clone(&writer_output)));
    tracing::subscriber::with_default(tracing_subscriber::registry().with(subscriber), || {
        tracing::warn!(
            target: "ja.diagnostics.runtime_startup",
            phase = "supervisor_start",
            variant = "codec_invalid_envelope",
            generation = 41_u64,
            elapsed_ms = 12_u64,
            path = %r"C:\private\prompt.txt",
            ready_token = %"0123456789abcdef0123456789abcdef",
            command = %"java --secret argument",
            environment = %"API_KEY=private",
            "runtime startup failed"
        );
        tracing::warn!(
            target: "ja.diagnostics.runtime_startup",
            phase = "unlisted_stage",
            variant = "unlisted_variant",
            generation = 42_u64,
            elapsed_ms = 99_u64,
            path = %r"C:\private\unknown.txt",
            "unknown startup classification"
        );
        tracing::info!(
            target: "ja.diagnostics.runtime_startup",
            phase = "spawn",
            variant = "entered",
            elapsed_ms = 84_u64,
            path = %r"C:\private\runtime.exe",
            "runtime startup phase"
        );
        tracing::info!(
            target: "ja.diagnostics.runtime_startup",
            phase = "complete",
            variant = "success",
            elapsed_ms = 910_u64,
            token = "private-token-sentinel",
            "runtime startup completed"
        );
    });
    let rendered = String::from_utf8(output.lock().expect("test log sink poisoned").clone())
        .expect("formatter must emit UTF-8");

    assert!(rendered.contains(
        "phase=supervisor_start variant=codec_invalid_envelope generation=41 elapsed_ms=12"
    ));
    assert!(rendered.contains("generation=42"));
    assert!(rendered.contains("phase=spawn variant=entered elapsed_ms=84"));
    assert!(rendered.contains("phase=complete variant=success elapsed_ms=910"));
    assert!(rendered.contains("elapsed_ms=99"));
    assert!(!rendered.contains("unlisted_stage"));
    assert!(!rendered.contains("unlisted_variant"));
    assert!(!rendered.contains("private"));
    assert!(!rendered.contains("0123456789abcdef"));
    assert!(!rendered.contains("java --secret"));
    assert!(!rendered.contains("API_KEY"));
    assert!(!rendered.contains("runtime startup failed"));
    assert!(!rendered.contains("runtime startup phase"));
    assert!(!rendered.contains("runtime startup completed"));
    assert!(!rendered.contains("runtime.exe"));
    assert!(!rendered.contains("private-token-sentinel"));
}

/// 验证 WebSocket pump 的真实 tracing formatter 只输出安全类别与合法关闭码。
#[test]
fn formatter_keeps_allowlisted_websocket_pump_diagnostics_only() {
    let output = Arc::new(Mutex::new(Vec::new()));
    let writer_output = Arc::clone(&output);
    let subscriber = tracing_subscriber::fmt::layer()
        .with_ansi(false)
        .event_format(RedactedEventFormat::default())
        .with_writer(move || SharedWriter(Arc::clone(&writer_output)));
    tracing::subscriber::with_default(tracing_subscriber::registry().with(subscriber), || {
        tracing::warn!(
            target: WEBSOCKET_PUMP_DIAGNOSTIC_TARGET,
            exit_category = "websocket_read_error",
            error_variant = "protocol",
            error_detail = "reset_without_closing_handshake",
            io_kind = "none",
            close_code = 0_u64,
            close_reason = "private-close-reason-sentinel",
            payload = "private-frame-payload-sentinel",
            "shared WebSocket pump exited"
        );
        tracing::info!(
            target: WEBSOCKET_PUMP_DIAGNOSTIC_TARGET,
            exit_category = "peer_close",
            error_variant = "none",
            error_detail = "none",
            io_kind = "none",
            close_code = 1001_u64,
            close_reason = "another-private-close-reason-sentinel",
            "shared WebSocket pump exited"
        );
        for close_code in [1000_u64, 1003, 1007, 1013, 3000, 3999, 4000, 4999] {
            tracing::info!(
                target: WEBSOCKET_PUMP_DIAGNOSTIC_TARGET,
                exit_category = "peer_close",
                error_variant = "none",
                error_detail = "none",
                io_kind = "none",
                close_code,
                "allowed close-code boundary"
            );
        }
        for close_code in [0_u64, 1004, 1005, 1006, 1014, 1015, 1016, 2000, 2999, 5000] {
            tracing::info!(
                target: WEBSOCKET_PUMP_DIAGNOSTIC_TARGET,
                exit_category = "peer_close",
                error_variant = "none",
                error_detail = "none",
                io_kind = "none",
                close_code,
                "disallowed close-code boundary"
            );
        }
        tracing::warn!(
            target: WEBSOCKET_PUMP_DIAGNOSTIC_TARGET,
            exit_category = "websocket_read_error",
            error_variant = "io",
            error_detail = "none",
            io_kind = "connection_reset",
            close_code = 0_u64,
            "shared WebSocket pump exited"
        );
        tracing::warn!(
            target: WEBSOCKET_PUMP_DIAGNOSTIC_TARGET,
            exit_category = "peer_close",
            error_variant = "none",
            error_detail = "none",
            io_kind = "none",
            close_code = ?String::from("private-debug-close-code-sentinel"),
            "malformed pump close code"
        );
        tracing::warn!(
            target: WEBSOCKET_PUMP_DIAGNOSTIC_TARGET,
            exit_category = "untrusted-category-sentinel",
            error_variant = "untrusted-variant-sentinel",
            error_detail = "untrusted-detail-sentinel",
            io_kind = "untrusted-io-kind-sentinel",
            close_code = 9000_u64,
            "untrusted pump classification"
        );
    });
    let rendered = String::from_utf8(output.lock().expect("test log sink poisoned").clone())
        .expect("formatter must emit UTF-8");

    assert!(rendered.contains(
        "exit_category=websocket_read_error error_variant=protocol error_detail=reset_without_closing_handshake io_kind=none"
    ));
    assert!(rendered.contains(
        "exit_category=peer_close error_variant=none error_detail=none io_kind=none close_code=1001"
    ));
    assert!(rendered.contains(
        "exit_category=websocket_read_error error_variant=io error_detail=none io_kind=connection_reset"
    ));
    for close_code in [1000_u64, 1003, 1007, 1013, 3000, 3999, 4000, 4999] {
        assert!(rendered.contains(&format!("close_code={close_code}")));
    }
    assert_eq!(rendered.matches("close_code=1001").count(), 1);
    assert!(!rendered.contains("close_code=0\n"));
    for close_code in [1004_u64, 1005, 1006, 1014, 1015, 1016, 2000, 2999, 5000] {
        assert!(!rendered.contains(&format!("close_code={close_code}")));
    }
    assert!(!rendered.contains("close_code=9000"));
    assert!(!rendered.contains("private-debug-close-code-sentinel"));
    assert!(!rendered.contains("untrusted-category-sentinel"));
    assert!(!rendered.contains("untrusted-variant-sentinel"));
    assert!(!rendered.contains("untrusted-detail-sentinel"));
    assert!(!rendered.contains("untrusted-io-kind-sentinel"));
    assert!(!rendered.contains("private-close-reason-sentinel"));
    assert!(!rendered.contains("private-frame-payload-sentinel"));
    assert!(!rendered.contains("another-private-close-reason-sentinel"));
    assert!(!rendered.contains("untrusted pump classification"));
}

/// Preview 导航诊断仅保留原生身份与状态数值，路径、URL、消息和不完整事件都不可见。
/// @author kongweiguang
#[test]
fn formatter_keeps_preview_navigation_status_without_payloads() {
    let output = Arc::new(Mutex::new(Vec::new()));
    let writer_output = Arc::clone(&output);
    let subscriber = tracing_subscriber::fmt::layer()
        .with_ansi(false)
        .event_format(RedactedEventFormat::default())
        .with_writer(move || SharedWriter(Arc::clone(&writer_output)));
    tracing::subscriber::with_default(tracing_subscriber::registry().with(subscriber), || {
        tracing::info!(
            target: PREVIEW_NAVIGATION_DIAGNOSTIC_TARGET,
            navigation_id = 51_u64,
            generation = 7_u64,
            succeeded = false,
            web_error_status_available = true,
            web_error_status = 14_i32,
            url = "file:///C:/private/audio.wav",
            message = "private navigation detail",
            "native navigation completed"
        );
        tracing::info!(
            target: PREVIEW_NAVIGATION_DIAGNOSTIC_TARGET,
            navigation_id = 52_u64,
            generation = 8_u64,
            succeeded = true,
            web_error_status_available = false,
            web_error_status = 0_i32,
            "native navigation completed"
        );
        tracing::info!(
            target: PREVIEW_NAVIGATION_DIAGNOSTIC_TARGET,
            navigation_id = 53_u64,
            succeeded = false,
            web_error_status_available = true,
            web_error_status = 14_i32,
            url = "file:///C:/private/incomplete.wav",
            "incomplete navigation identity"
        );
    });
    let rendered = String::from_utf8(output.lock().expect("test log sink poisoned").clone())
        .expect("formatter must emit UTF-8");

    assert!(rendered.contains(
        "navigation_id=51 generation=7 succeeded=false web_error_status_available=true web_error_status=14"
    ));
    assert!(rendered.contains(
        "navigation_id=52 generation=8 succeeded=true web_error_status_available=false web_error_status=0"
    ));
    assert!(!rendered.contains("navigation_id=53"));
    assert!(!rendered.contains("private/audio.wav"));
    assert!(!rendered.contains("private/incomplete.wav"));
    assert!(!rendered.contains("private navigation detail"));
    assert!(!rendered.contains("native navigation completed"));
}
