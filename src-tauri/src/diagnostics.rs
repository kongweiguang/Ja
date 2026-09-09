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

/// 只格式化时间、级别、编译期 target，以及固定白名单中的 WebView 故障码和运行时计数。
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
