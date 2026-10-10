// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 当前 Thread 用量的本地投影门控；权威统计留在 Java，失败只影响这一块展示。

use ja_cli::ui::{UiEvent, UiUsageSnapshot};
use ja_runtime::app_server_process::{
    ThreadContextUsageDto, ThreadUsageSummary, UsageProtocolError, parse_thread_context_usage,
};
use serde_json::Value;
use std::time::{Duration, Instant};

const SUMMARY_REFRESH_DEBOUNCE: Duration = Duration::from_millis(100);

/// `/status` 会尽力走完所有只读阶段；历史/元数据失败不能跳过用量回读或缓存详情重建。
/// @author kongweiguang
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum StatusAttemptStep {
    Metadata,
    History,
    Usage,
    Details,
}

/// `/status` 的非用量读取错误按普通刷新口径择一返回，详情错误只在前序读取成功时返回。
pub(super) fn run_status_attempts<E>(
    mut attempt: impl FnMut(StatusAttemptStep) -> Result<(), E>,
    history_is_retryable: impl FnOnce(&E) -> bool,
) -> Option<E> {
    let metadata = attempt(StatusAttemptStep::Metadata).err();
    let history = attempt(StatusAttemptStep::History).err();
    let _ = attempt(StatusAttemptStep::Usage);
    let details = attempt(StatusAttemptStep::Details).err();
    match (metadata, history) {
        (Some(metadata), Some(history)) => Some(if history_is_retryable(&history) {
            metadata
        } else {
            history
        }),
        (Some(error), None) | (None, Some(error)) => Some(error),
        (None, None) => details,
    }
}

/// debounce 到期后先消费当下已排队事实；慢事件处理后产生的新事实也会再次并入同一批。
/// @author kongweiguang
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum ReadyEventDrain<T> {
    Handled,
    Idle(T),
}

/// @author kongweiguang
/// 队列空闲才返回可读身份；帧数或既有轮询时间片耗尽时让出循环处理用户动作。
pub(super) fn drain_ready_before<T, E>(
    started_at: Instant,
    maximum_events: usize,
    time_slice: Duration,
    mut process_one: impl FnMut() -> Result<ReadyEventDrain<T>, E>,
) -> Result<Option<T>, E> {
    for _ in 0..maximum_events {
        match process_one()? {
            ReadyEventDrain::Handled if started_at.elapsed() >= time_slice => return Ok(None),
            ReadyEventDrain::Handled => {}
            ReadyEventDrain::Idle(value) => return Ok(Some(value)),
        }
    }
    Ok(None)
}

/// @author kongweiguang
/// 只读元数据或历史失败必须在覆盖 footer 的状态面板内可见，并提供现有 F5 重试路径。
pub(super) fn status_read_warning(read_diagnostic: Option<&str>) -> String {
    read_diagnostic
        .map(|diagnostic| format!("\n\n状态部分未核实；按 F5 重试。\n{diagnostic}"))
        .unwrap_or_default()
}

/// 绑定一次摘要读取的会话与连接代际，迟到回包不能写进新身份。
#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) struct UsageReadIdentity {
    pub thread_id: String,
    pub connection_generation: u64,
}

/// 切换 Thread 时先按仍显示的旧身份清除 UI 快照；同 Thread 刷新继续保留已有用量。
/// @author kongweiguang
pub(super) fn clear_event_before_thread_switch(
    current_thread_id: Option<&str>,
    next_thread_id: &str,
) -> Option<UiEvent> {
    let current_thread_id = current_thread_id.filter(|id| *id != next_thread_id)?;
    Some(UiEvent::SetUsage {
        thread_id: current_thread_id.to_owned(),
        usage: None,
    })
}

/// 仅缓存当前 Thread；summary revision 单调前进，context 每次随 thread/read 原样替换。
#[derive(Default)]
pub(super) struct UsageState {
    thread_id: Option<String>,
    connection_generation: u64,
    summary: Option<ThreadUsageSummary>,
    context: Option<ThreadContextUsageDto>,
    stale: bool,
    refresh_due: Option<Instant>,
}

impl UsageState {
    /// 激活新 Thread 时清除前一会话事实；同一 Thread 的刷新保留最近值供断线时查看。
    pub(super) fn bind_thread(&mut self, thread_id: Option<&str>, now: Instant) -> bool {
        if self.thread_id.as_deref() == thread_id {
            return false;
        }
        self.thread_id = thread_id.map(str::to_owned);
        self.summary = None;
        self.context = None;
        self.stale = false;
        self.refresh_due = thread_id.map(|_| now);
        true
    }

    /// 新连接代际保留同 Thread 最后一次展示并安排权威回读，避免短暂断线时清空信息。
    pub(super) fn bind_connection_generation(&mut self, generation: u64, now: Instant) {
        if self.connection_generation == generation {
            return;
        }
        self.connection_generation = generation;
        if self.thread_id.is_some() {
            self.stale = self.summary.is_some();
            self.refresh_due = Some(now);
        }
    }

    /// 重连或连接替换递增本地代际，旧读取凭证因此不能被当前视图接受。
    pub(super) fn advance_connection_generation(&mut self, now: Instant) {
        let generation = self.connection_generation.wrapping_add(1).max(1);
        self.bind_connection_generation(generation, now);
    }

    /// 返回 controller 当前连接代际，供同步历史解析结果绑定其读取来源。
    pub(super) fn connection_generation(&self) -> u64 {
        self.connection_generation
    }

    /// 摘要失败需要标 stale 时捕获当前身份，防止调用方把错误归到已切换的 Thread。
    pub(super) fn active_identity(&self) -> Option<UsageReadIdentity> {
        Some(UsageReadIdentity {
            thread_id: self.thread_id.clone()?,
            connection_generation: self.connection_generation,
        })
    }

    /// Thread 快照中的显式 null 或 UNKNOWN 都替换旧值，禁止把前一请求实测回退成当前状态。
    pub(super) fn set_context(
        &mut self,
        thread_id: &str,
        connection_generation: u64,
        context: Option<ThreadContextUsageDto>,
    ) -> bool {
        if self.thread_id.as_deref() != Some(thread_id)
            || self.connection_generation != connection_generation
        {
            return false;
        }
        self.context = context;
        true
    }

    /// 仅接受同 Thread、同连接代际、覆盖当前历史 revision 且自身不倒退的摘要回包。
    pub(super) fn apply_summary(
        &mut self,
        identity: &UsageReadIdentity,
        minimum_revision: u64,
        summary: ThreadUsageSummary,
    ) -> bool {
        if self.thread_id.as_deref() != Some(identity.thread_id.as_str())
            || self.connection_generation != identity.connection_generation
            || summary.thread_id != identity.thread_id
            || summary.snapshot_revision < minimum_revision
            || self
                .summary
                .as_ref()
                .is_some_and(|current| summary.snapshot_revision < current.snapshot_revision)
        {
            return false;
        }
        self.summary = Some(summary);
        self.stale = false;
        self.refresh_due = None;
        true
    }

    /// 摘要暂时不可读时保留同会话数值并独立标 stale，不生成聊天提示或阻断对话。
    pub(super) fn mark_summary_stale(&mut self, identity: &UsageReadIdentity) -> bool {
        if self.thread_id.as_deref() != Some(identity.thread_id.as_str())
            || self.connection_generation != identity.connection_generation
        {
            return false;
        }
        self.stale = true;
        true
    }

    /// 持久用量事实在同批事件内合并，避免每条提交通知分别读取 SQLite 汇总。
    pub(super) fn schedule_summary_refresh(&mut self, now: Instant) {
        if self.thread_id.is_some() {
            self.stale = self.summary.is_some();
            self.refresh_due = Some(now + SUMMARY_REFRESH_DEBOUNCE);
        }
    }

    /// 用量提交、请求计量状态与压缩结算事件使摘要变脏；失败压缩也可能产生已结算请求。
    pub(super) fn summary_changes_on_notification(method: &str) -> bool {
        matches!(
            method,
            "assistant/model-step-committed"
                | "assistant/context-estimated"
                | "turn/input-consumed"
                | "turn/retry-started"
                | "turn/terminal"
                | "context/compacted"
                | "context/compaction-failed"
        )
    }

    /// 到达 debounce 截止时捕获身份并清除计划；网络失败不会自动重试或挤占用户操作。
    pub(super) fn take_due_refresh(&mut self, now: Instant) -> Option<UsageReadIdentity> {
        if !self.refresh_due.is_some_and(|due| due <= now) {
            return None;
        }
        self.refresh_due = None;
        Some(UsageReadIdentity {
            thread_id: self.thread_id.clone()?,
            connection_generation: self.connection_generation,
        })
    }

    /// 显式核实与连接恢复绕过 debounce，但仍用同一身份和摘要解析路径。
    pub(super) fn request_refresh_now(&mut self) -> Option<UsageReadIdentity> {
        self.refresh_due = None;
        Some(UsageReadIdentity {
            thread_id: self.thread_id.clone()?,
            connection_generation: self.connection_generation,
        })
    }

    /// UI 只在 Thread 已选中时拥有用量行；无会话状态不携带上一 Thread 的残留数据。
    pub(super) fn snapshot(&self) -> Option<UiUsageSnapshot> {
        self.thread_id.as_ref()?;
        Some(UiUsageSnapshot {
            summary: self.summary.clone(),
            stale: self.stale,
            context: self.context.clone(),
        })
    }
}

/// thread/read 的 contextUsage 是必需 nullable 槽位；缺失或畸形都按协议损坏处理。
pub(super) fn context_usage_from_history(
    history: &Value,
) -> Result<Option<ThreadContextUsageDto>, UsageProtocolError> {
    let value = history
        .get("contextUsage")
        .ok_or(UsageProtocolError::ContextUsageShape)?;
    parse_thread_context_usage(value)
}

/// `/status` 把缓存已计入的完整输入与未缓存新输入分开，并只在全体已计量请求覆盖齐全时派生累计输入。
pub(super) fn status_lines(usage: Option<&UiUsageSnapshot>) -> Vec<String> {
    let Some(usage) = usage else {
        return vec!["用量：尚无已报告用量".into()];
    };
    let mut lines = Vec::new();
    if let Some(summary) = &usage.summary {
        lines.push(if summary.measured_request_count == 0 {
            format!(
                "累计：尚无已报告用量（0/{} 个请求已计量）",
                summary.request_count
            )
        } else {
            format!(
                "累计：已计量 {}/{} 个请求",
                summary.measured_request_count, summary.request_count
            )
        });
        let cumulative_input = usage
            .cumulative_input_tokens()
            .map(|tokens| {
                reported_tokens(
                    tokens,
                    summary.measured_request_count,
                    summary.measured_request_count,
                )
            })
            .unwrap_or_else(|| {
                format!(
                    "未报告（总量 {}/{}、输出 {}/{} 个已计量请求，需完整覆盖且总量不少于输出）",
                    summary.total_request_count,
                    summary.measured_request_count,
                    summary.output_request_count,
                    summary.measured_request_count,
                )
            });
        lines.push(format!("输入（含缓存）：{cumulative_input}"));
        lines.push(format!(
            "新输入（未缓存）：{}",
            reported_tokens(
                summary.new_input_tokens,
                summary.new_input_request_count,
                summary.measured_request_count
            ),
        ));
        lines.push(format!(
            "输出：{}",
            reported_tokens(
                summary.output_tokens,
                summary.output_request_count,
                summary.measured_request_count
            ),
        ));
        lines.push(format!(
            "总计（输入含缓存 + 输出）：{}",
            reported_tokens(
                summary.total_tokens,
                summary.total_request_count,
                summary.measured_request_count
            ),
        ));
        lines.push(format!(
            "缓存明细（已含在输入中，不重复累加）：读取 {}；写入 {}",
            reported_tokens(
                summary.cache_read_tokens,
                summary.cache_read_request_count,
                summary.measured_request_count
            ),
            reported_tokens(
                summary.cache_write_tokens,
                summary.cache_write_request_count,
                summary.measured_request_count
            ),
        ));
        let cache_hit = if summary.cache_complete_request_count == 0 {
            format!(
                "未报告（0/{} 个完整样本请求）",
                summary.measured_request_count
            )
        } else if summary.cache_complete_input_tokens == 0 {
            format!(
                "无可计算输入（{} 个完整样本请求）",
                summary.cache_complete_request_count
            )
        } else {
            format!(
                "{}/{} tokens（{:.1}%）",
                exact_count(summary.cache_complete_read_tokens),
                exact_count(summary.cache_complete_input_tokens),
                summary.cache_complete_read_tokens as f64 * 100.0
                    / summary.cache_complete_input_tokens as f64,
            )
        };
        lines.push(format!(
            "缓存命中：{cache_hit}，完整样本覆盖 {}/{} 个已计量请求",
            summary.cache_complete_request_count, summary.measured_request_count,
        ));
    } else if usage.stale {
        lines.push("累计：尚无已报告用量；读取失败，按 F5 重试".into());
    } else {
        lines.push("累计：尚无已报告用量".into());
    }
    if usage.stale && usage.summary.is_some() {
        lines.push("累计摘要待刷新；按 F5 重试".into());
    }
    lines.push(context_status_line(usage.context.as_ref()));
    lines.push(
        "口径：累计为本会话多次请求合计；最近请求上下文按输入（含缓存）+输出计量。发送前显示本地输入估算，Provider 实测返回后校正；成功压缩会降低占用。".into(),
    );
    lines
}

/// 有覆盖时如实允许显示零；coverage 为零时返回未知文案而非从聚合默认值伪造零用量。
fn reported_tokens(tokens: u64, coverage: u64, measured_requests: u64) -> String {
    if coverage == 0 {
        return format!("未报告（0/{measured_requests} 个已计量请求）");
    }
    format!(
        "{} tokens（{coverage}/{measured_requests} 个已计量请求）",
        exact_count(tokens)
    )
}

/// 最近请求上下文依冻结画像显示实测或本地输入估算，绝不借用较旧的已知计量。
fn context_status_line(context: Option<&ThreadContextUsageDto>) -> String {
    let Some(context) = context else {
        return "最近请求上下文：尚无已报告的普通 Assistant 请求用量".into();
    };
    if context.purpose != "assistant" {
        return "最近请求上下文：尚无已报告的普通 Assistant 请求用量".into();
    }
    if let Some(estimate) = &context.context_estimate {
        return format!(
            "最近请求上下文：约 {} / {} tokens（{}，冻结模型 {}）",
            exact_count(estimate.used_tokens),
            exact_count(estimate.limit_tokens),
            if estimate.source == "local" {
                "Java 本地估算"
            } else {
                "本地估算"
            },
            context.profile.upstream_model,
        );
    }
    if context.certainty == "known"
        && let Some(total_tokens) = context.total_tokens
    {
        return format!(
            "最近请求上下文：{} / {} tokens（Provider 实测，冻结模型 {}）",
            exact_count(total_tokens),
            exact_count(context.profile.context_window_tokens),
            context.profile.upstream_model,
        );
    }
    format!(
        "最近请求上下文：未知（Provider 未报告且暂无本地估算，冻结模型 {}，窗口 {} tokens）",
        context.profile.upstream_model,
        exact_count(context.profile.context_window_tokens),
    )
}

/// 诊断面板不使用底栏缩写，保留账本中的精确十进制值且不依赖格式化 crate。
fn exact_count(value: u64) -> String {
    let digits = value.to_string();
    let mut grouped = String::with_capacity(digits.len() + digits.len() / 3);
    for (index, digit) in digits.chars().enumerate() {
        if index > 0 && (digits.len() - index).is_multiple_of(3) {
            grouped.push(',');
        }
        grouped.push(digit);
    }
    grouped
}
