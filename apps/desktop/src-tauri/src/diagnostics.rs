// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 原生日志诊断边界刻意丢弃事件载荷字段，防止路径、提示词和命令输出泄漏。

use std::fmt;
use std::fs;
use std::path::Path;

use tracing::field::{Field, Visit};
use tracing::{Event, Subscriber};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_subscriber::fmt::format::Writer;
use tracing_subscriber::fmt::time::{FormatTime, SystemTime};
use tracing_subscriber::fmt::{FmtContext, FormatEvent, FormatFields};
use tracing_subscriber::registry::LookupSpan;
use tracing_subscriber::util::SubscriberInitExt;

/// 只有封闭集合中的 UI 故障码可以从 WebView 进入原生日志；白名单由 Rust 持有，
/// 避免未来调用方把插件的字符串接口变成提示词、路径或命令输出的泄漏通道。
const SAFE_WEBVIEW_EVENT_CODES: &[&str] = &[
    "ui.clipboard_write_failed",
    "ui.external_link_open_failed",
    "ui.notification_delivery_failed",
    "ui.notification_permission_failed",
    "ui.react_error_boundary",
];

const EVENT_QUEUE_METRIC_TARGET: &str = "ja.metrics.event_queue";
const SAFE_EVENT_QUEUE_METRICS: &[&str] = &[
    "task_progress_coalesced_total",
    "event_data_overflow_dropped_total",
    "event_control_overflow_total",
    "runtime_event_queue_totals",
];
const SAFE_EVENT_QUEUE_LANES: &[&str] = &["control", "data"];
const RUNTIME_STARTUP_DIAGNOSTIC_TARGET: &str = "ja.diagnostics.runtime_startup";
pub(crate) const PREVIEW_NAVIGATION_DIAGNOSTIC_TARGET: &str = "ja.diagnostics.preview_navigation";
const SAFE_RUNTIME_STARTUP_PHASES: &[&str] = &[
    "supervisor_start",
    "ready_token_echo",
    "event_pump_take",
    "initial_connect",
    "start_lock",
    "post_lock_connect",
    "spawn",
    "endpoint_wait",
    "endpoint_read",
    "tcp_connect",
    "websocket_handshake",
    "runtime_initialize",
    "runtime_ready",
    "context_register",
    "complete",
];
const SAFE_RUNTIME_STARTUP_VARIANTS: &[&str] = &[
    "entered",
    "success",
    "invalid_config",
    "invalid_timeout",
    "codec_unexpected_eof",
    "codec_partial_frame",
    "codec_empty_frame",
    "codec_invalid_utf8",
    "codec_invalid_json",
    "codec_duplicate_key",
    "codec_non_object",
    "codec_invalid_envelope",
    "codec_handshake_failed",
    "codec_invalid_error_catalog",
    "codec_invalid_id",
    "codec_invalid_limit",
    "codec_frame_too_large",
    "codec_io",
    "queue_full",
    "queue_closed",
    "pending_limit",
    "request_ledger_exhausted",
    "duplicate_request",
    "deadline_exceeded",
    "cancelled",
    "session_closed",
    "not_ready",
    "incompatible",
    "invalid_state",
    "spawn",
    "process_tree",
    "process_exited",
    "shutting_down",
    "handshake_failed",
    "protocol_fault",
    "invalid_error_catalog",
    "shutdown_timeout",
    "backoff",
    "faulted",
];
const WEBSOCKET_PUMP_DIAGNOSTIC_TARGET: &str = "ja.diagnostics.websocket_pump";
const SAFE_WEBSOCKET_PUMP_EXIT_CATEGORIES: &[&str] = &[
    "invalid_outbound_text",
    "websocket_send_error",
    "outbound_channel_closed",
    "inbound_consumer_closed",
    "inbound_text_too_large",
    "inbound_text_contains_line_break",
    "peer_close",
    "unsupported_message",
    "websocket_read_error",
];
const SAFE_WEBSOCKET_PUMP_ERROR_VARIANTS: &[&str] = &[
    "none",
    "connection_closed",
    "already_closed",
    "io",
    "tls",
    "capacity",
    "protocol",
    "write_buffer_full",
    "utf8",
    "attack_attempt",
    "url",
    "http",
    "http_format",
];
const SAFE_WEBSOCKET_PUMP_ERROR_DETAILS: &[&str] = &[
    "none",
    "too_many_headers",
    "message_too_long",
    "wrong_http_method",
    "wrong_http_version",
    "missing_connection_upgrade_header",
    "missing_upgrade_websocket_header",
    "missing_websocket_version_header",
    "missing_websocket_key",
    "websocket_accept_key_mismatch",
    "server_sent_unrequested_subprotocol",
    "invalid_subprotocol",
    "missing_subprotocol",
    "junk_after_request",
    "custom_response_successful",
    "invalid_header",
    "handshake_incomplete",
    "http_parse_error",
    "send_after_closing",
    "received_after_closing",
    "nonzero_reserved_bits",
    "unmasked_frame_from_client",
    "masked_frame_from_server",
    "fragmented_control_frame",
    "control_frame_too_big",
    "unknown_control_frame_type",
    "unknown_data_frame_type",
    "unexpected_continue_frame",
    "expected_fragment",
    "reset_without_closing_handshake",
    "invalid_opcode",
    "invalid_close_sequence",
];
const SAFE_WEBSOCKET_PUMP_IO_KINDS: &[&str] = &[
    "none",
    "not_found",
    "permission_denied",
    "connection_refused",
    "connection_reset",
    "connection_aborted",
    "not_connected",
    "addr_in_use",
    "addr_not_available",
    "broken_pipe",
    "already_exists",
    "would_block",
    "invalid_input",
    "invalid_data",
    "timed_out",
    "write_zero",
    "interrupted",
    "unsupported",
    "unexpected_eof",
    "out_of_memory",
    "other",
];

/// 在整个 Tauri 生命周期内持有非阻塞写入器，确保正常退出时能刷新缓冲记录。
#[derive(Debug)]
pub(crate) struct NativeTracingGuard {
    _worker_guard: WorkerGuard,
}

/// 初始化失败只暴露稳定分类；启动错误若返回文件路径或日志实现细节，
/// 会绕过日志记录本身的脱敏边界。
#[derive(Debug, thiserror::Error)]
pub(crate) enum NativeTracingError {
    #[error("native log directory could not be prepared")]
    PrepareDirectory,
    #[error("native tracing subscriber could not be installed")]
    InstallSubscriber,
}

/// 安装 Ja 唯一的全局 tracing subscriber，并按天滚动输出。
/// `RedactedEventFormat` 负责删除事件字段，非阻塞写入器则隔离 IPC、PTY 与退出关键线程上的 IO。
pub(crate) fn initialize_native_tracing(
    log_dir: &Path,
) -> Result<NativeTracingGuard, NativeTracingError> {
    fs::create_dir_all(log_dir).map_err(|_| NativeTracingError::PrepareDirectory)?;
    let appender = tracing_appender::rolling::daily(log_dir, "ja-native.log");
    let (writer, worker_guard) = tracing_appender::non_blocking(appender);
    tracing_subscriber::fmt()
        .with_ansi(false)
        .with_max_level(tracing::Level::INFO)
        .event_format(RedactedEventFormat::default())
        .with_writer(writer)
        .finish()
        .try_init()
        .map_err(|_| NativeTracingError::InstallSubscriber)?;
    Ok(NativeTracingGuard {
        _worker_guard: worker_guard,
    })
}

/// 只格式化时间、级别、编译期 target，以及固定白名单中的 WebView 故障码、运行时计数、启动类别和 WS 退出诊断。
/// 原生消息可能合法包含路径或工具输出，因此绝不序列化自由文本字段。
#[derive(Default)]
pub(crate) struct RedactedEventFormat {
    timer: SystemTime,
}

impl<S, N> FormatEvent<S, N> for RedactedEventFormat
where
    S: Subscriber + for<'lookup> LookupSpan<'lookup>,
    N: for<'writer> FormatFields<'writer> + 'static,
{
    /// 在格式化阶段直接丢弃不受信任字段，使任何上游事件都无法绕过统一脱敏策略。
    /// Preview 只通过专属 visitor 输出原生导航号、代际、成功位和错误码。
    /// @author kongweiguang
    fn format_event(
        &self,
        _context: &FmtContext<'_, S, N>,
        mut writer: Writer<'_>,
        event: &Event<'_>,
    ) -> fmt::Result {
        self.timer.format_time(&mut writer)?;
        let metadata = event.metadata();
        write!(
            writer,
            " level={} target={}",
            metadata.level(),
            metadata.target()
        )?;
        if metadata.target() == tauri_plugin_log::WEBVIEW_TARGET {
            let mut visitor = SafeWebviewEventVisitor::default();
            event.record(&mut visitor);
            if let Some(code) = visitor.code {
                write!(writer, " code={code}")?;
            }
        } else if metadata.target() == EVENT_QUEUE_METRIC_TARGET {
            let mut visitor = SafeEventQueueMetricVisitor::default();
            event.record(&mut visitor);
            visitor.write_to(&mut writer)?;
        } else if metadata.target() == RUNTIME_STARTUP_DIAGNOSTIC_TARGET {
            let mut visitor = SafeRuntimeStartupVisitor::default();
            event.record(&mut visitor);
            visitor.write_to(&mut writer)?;
        } else if metadata.target() == PREVIEW_NAVIGATION_DIAGNOSTIC_TARGET {
            let mut visitor = SafePreviewNavigationVisitor::default();
            event.record(&mut visitor);
            visitor.write_to(&mut writer)?;
        } else if metadata.target() == WEBSOCKET_PUMP_DIAGNOSTIC_TARGET {
            let mut visitor = SafeWebSocketPumpVisitor::default();
            event.record(&mut visitor);
            visitor.write_to(&mut writer)?;
        }
        writeln!(writer)
    }
}

/// EventQueue 只允许固定名称、固定 lane 与整数累计值进入日志，Task identity 和 frame 正文
/// 即使被未来调用方误加到同一事件也不会被 formatter 序列化。
#[derive(Default)]
struct SafeEventQueueMetricVisitor {
    metric: Option<&'static str>,
    lane: Option<&'static str>,
    count: Option<u64>,
    task_progress_coalesced_total: Option<u64>,
    event_data_overflow_dropped_total: Option<u64>,
    event_control_overflow_total: Option<u64>,
}

impl SafeEventQueueMetricVisitor {
    /// 输出顺序固定，便于本地采集器解析并避免 Debug 表示引入任意字符串。
    fn write_to(&self, writer: &mut Writer<'_>) -> fmt::Result {
        let Some(metric) = self.metric else {
            return Ok(());
        };
        write!(writer, " metric={metric}")?;
        if let Some(lane) = self.lane {
            write!(writer, " lane={lane}")?;
        }
        for (name, value) in [
            ("count", self.count),
            (
                "task_progress_coalesced_total",
                self.task_progress_coalesced_total,
            ),
            (
                "event_data_overflow_dropped_total",
                self.event_data_overflow_dropped_total,
            ),
            (
                "event_control_overflow_total",
                self.event_control_overflow_total,
            ),
        ] {
            if let Some(value) = value {
                write!(writer, " {name}={value}")?;
            }
        }
        Ok(())
    }
}

impl Visit for SafeEventQueueMetricVisitor {
    /// 字符串字段只能映射到编译期白名单，未知 metric/lane 保持缺失。
    fn record_str(&mut self, field: &Field, value: &str) {
        match field.name() {
            "metric" => {
                self.metric = SAFE_EVENT_QUEUE_METRICS
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            "lane" => {
                self.lane = SAFE_EVENT_QUEUE_LANES
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            _ => {}
        }
    }

    /// 只接收已知累计字段，任何 identity、长度或业务值不会被顺带发布。
    fn record_u64(&mut self, field: &Field, value: u64) {
        match field.name() {
            "count" => self.count = Some(value),
            "task_progress_coalesced_total" => self.task_progress_coalesced_total = Some(value),
            "event_data_overflow_dropped_total" => {
                self.event_data_overflow_dropped_total = Some(value);
            }
            "event_control_overflow_total" => self.event_control_overflow_total = Some(value),
            _ => {}
        }
    }

    /// formatter 不接受 Debug 字段，防止非 typed tracing 调用绕过数值与白名单约束。
    fn record_debug(&mut self, _field: &Field, _value: &dyn fmt::Debug) {}
}

/// 启动诊断只承载阶段、枚举分类、代际和整数耗时；其它调用者字段一律丢弃。
#[derive(Default)]
struct SafeRuntimeStartupVisitor {
    phase: Option<&'static str>,
    variant: Option<&'static str>,
    generation: Option<u64>,
    elapsed_ms: Option<u64>,
}

impl SafeRuntimeStartupVisitor {
    /// 固定字段顺序输出闭集分类和整数耗时，确保错误载荷不会借 formatter 回流。
    fn write_to(&self, writer: &mut Writer<'_>) -> fmt::Result {
        if let Some(phase) = self.phase {
            write!(writer, " phase={phase}")?;
        }
        if let Some(variant) = self.variant {
            write!(writer, " variant={variant}")?;
        }
        if let Some(generation) = self.generation {
            write!(writer, " generation={generation}")?;
        }
        if let Some(elapsed_ms) = self.elapsed_ms {
            write!(writer, " elapsed_ms={elapsed_ms}")?;
        }
        Ok(())
    }
}

impl Visit for SafeRuntimeStartupVisitor {
    /// 阶段和类别只接受编译期白名单，避免自由字符串把错误字段变成日志通道。
    fn record_str(&mut self, field: &Field, value: &str) {
        match field.name() {
            "phase" => {
                self.phase = SAFE_RUNTIME_STARTUP_PHASES
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            "variant" => {
                self.variant = SAFE_RUNTIME_STARTUP_VARIANTS
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            _ => {}
        }
    }

    /// 只输出 typed u64 generation 和耗时，避免错误文本或任意 Debug 值伪装成诊断数字。
    fn record_u64(&mut self, field: &Field, value: u64) {
        match field.name() {
            "generation" => self.generation = Some(value),
            "elapsed_ms" => self.elapsed_ms = Some(value),
            _ => {}
        }
    }

    /// 不接受 Debug 字段，使 path、token、message 和命令不可能通过非 typed 值绕过白名单。
    fn record_debug(&mut self, _field: &Field, _value: &dyn fmt::Debug) {}
}

/// Preview 导航诊断只输出有限数值和布尔状态，丢弃所有 path、URL 与正文载荷。
#[derive(Default)]
struct SafePreviewNavigationVisitor {
    navigation_id: Option<u64>,
    generation: Option<u64>,
    succeeded: Option<bool>,
    status_available: Option<bool>,
    web_error_status: Option<i64>,
}

impl SafePreviewNavigationVisitor {
    /// 字段不完整时整组隐藏，避免缺失数据被默认值伪装成有效错误码。
    /// @author kongweiguang
    fn write_to(&self, writer: &mut Writer<'_>) -> fmt::Result {
        let (
            Some(navigation_id),
            Some(generation),
            Some(succeeded),
            Some(status_available),
            Some(web_error_status),
        ) = (
            self.navigation_id,
            self.generation,
            self.succeeded,
            self.status_available,
            self.web_error_status,
        )
        else {
            return Ok(());
        };
        write!(
            writer,
            " navigation_id={navigation_id} generation={generation} succeeded={succeeded} web_error_status_available={status_available} web_error_status={web_error_status}"
        )
    }
}

impl Visit for SafePreviewNavigationVisitor {
    /// 只接受导航身份的 typed 整数；自由格式的字符串和 Debug 值永远不会进入日志。
    /// @author kongweiguang
    fn record_u64(&mut self, field: &Field, value: u64) {
        match field.name() {
            "navigation_id" => self.navigation_id = Some(value),
            "generation" => self.generation = Some(value),
            _ => {}
        }
    }

    /// 错误码保留为 typed 整数以便和当前 WebView2 投影对照。
    /// @author kongweiguang
    fn record_i64(&mut self, field: &Field, value: i64) {
        if field.name() == "web_error_status" {
            self.web_error_status = Some(value);
        }
    }

    /// 成功位与 status 可用位是闭合布尔量，不接受字符串替代。
    /// @author kongweiguang
    fn record_bool(&mut self, field: &Field, value: bool) {
        match field.name() {
            "succeeded" => self.succeeded = Some(value),
            "web_error_status_available" => self.status_available = Some(value),
            _ => {}
        }
    }

    /// 非 typed 数值字段一律丢弃，尤其是不允许消息、URL 或文件路径。
    /// @author kongweiguang
    fn record_debug(&mut self, _field: &Field, _value: &dyn fmt::Debug) {}
}

/// WebSocket pump 仅投影固定退出类别、错误变体、标准 I/O 类别和合法数值关闭码。
#[derive(Default)]
struct SafeWebSocketPumpVisitor {
    exit_category: Option<&'static str>,
    error_variant: Option<&'static str>,
    error_detail: Option<&'static str>,
    io_kind: Option<&'static str>,
    close_code: Option<u16>,
}

impl SafeWebSocketPumpVisitor {
    /// 无法识别退出类别时整条诊断字段保持隐藏，避免该 target 成为通用字符串日志入口。
    fn write_to(&self, writer: &mut Writer<'_>) -> fmt::Result {
        let Some(exit_category) = self.exit_category else {
            return Ok(());
        };
        write!(writer, " exit_category={exit_category}")?;
        if let Some(error_variant) = self.error_variant {
            write!(writer, " error_variant={error_variant}")?;
        }
        if let Some(error_detail) = self.error_detail {
            write!(writer, " error_detail={error_detail}")?;
        }
        if let Some(io_kind) = self.io_kind {
            write!(writer, " io_kind={io_kind}")?;
        }
        if let Some(close_code) = self.close_code {
            write!(writer, " close_code={close_code}")?;
        }
        Ok(())
    }
}

impl Visit for SafeWebSocketPumpVisitor {
    /// 分类字段逐项匹配编译期闭集；其它字符串或额外事件字段完全不进入日志。
    fn record_str(&mut self, field: &Field, value: &str) {
        match field.name() {
            "exit_category" => {
                self.exit_category = SAFE_WEBSOCKET_PUMP_EXIT_CATEGORIES
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            "error_variant" => {
                self.error_variant = SAFE_WEBSOCKET_PUMP_ERROR_VARIANTS
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            "error_detail" => {
                self.error_detail = SAFE_WEBSOCKET_PUMP_ERROR_DETAILS
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            "io_kind" => {
                self.io_kind = SAFE_WEBSOCKET_PUMP_IO_KINDS
                    .iter()
                    .copied()
                    .find(|allowed| *allowed == value);
            }
            _ => {}
        }
    }

    /// 仅输出与 tungstenite CloseCode::is_allowed 一致的 typed 数值；0、Status/Reserved/Bad 均不输出。
    fn record_u64(&mut self, field: &Field, value: u64) {
        if field.name() == "close_code" && matches!(value, 1000..=1003 | 1007..=1013 | 3000..=4999)
        {
            self.close_code = u16::try_from(value).ok();
        }
    }

    /// Debug/Display 字段一律丢弃，避免自由文本通过同名 close_code 字段进入诊断。
    fn record_debug(&mut self, _field: &Field, _value: &dyn fmt::Debug) {}
}

/// 只从插件的 `message` 字段提取已知诊断码；位置、文件、键值和任意消息字段全部丢弃。
#[derive(Default)]
struct SafeWebviewEventVisitor {
    code: Option<&'static str>,
}

impl Visit for SafeWebviewEventVisitor {
    /// 仅接受白名单字符串的 Debug 表示，其他字段与未知值保持为空而不是尝试兜底解析。
    fn record_debug(&mut self, field: &Field, value: &dyn fmt::Debug) {
        if field.name() != "message" {
            return;
        }
        let rendered = format!("{value:?}");
        let candidate = rendered.trim_matches('"');
        self.code = SAFE_WEBVIEW_EVENT_CODES
            .iter()
            .copied()
            .find(|allowed| *allowed == candidate);
    }
}

// 诊断单元测试物理放在 `tests/unit`，避免测试 sink 与生产日志实现混居。
