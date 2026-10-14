// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 用量只格式化服务端已验证的事实，不扫描历史或把未知计量折算为零。

use ratatui::style::{Color, Style};
use ratatui::text::{Line, Span};

use super::model::UiUsageSnapshot;
use super::theme;

const MUTED: Color = theme::MUTED;
const TOKEN_WIDTH: usize = 5;
const PERCENT_WIDTH: usize = 7;
const CONTEXT_WIDTH: usize = 6 + TOKEN_WIDTH * 2 + 2 + PERCENT_WIDTH;
const BRIEF_CONTEXT_WIDTH: usize = 6 + PERCENT_WIDTH;

/// 以格式器最大数值宽度选择固定字段布局，并用主题次级色保持低优先级字段仍可读。
pub(super) fn usage_line(usage: Option<&UiUsageSnapshot>, width: usize) -> Line<'static> {
    let padding = usize::from(width >= 4) * 2;
    let budget = width.saturating_sub(padding * 2);
    let (context, brief_context, compact_context, context_color) = context_label(usage);
    let mut fields = summary_fields(usage);
    // 缓存子项、输入输出依次让位，优先让用户看清累计总量与当前上下文。
    for priority in [2, 1] {
        if line_width(&fields, CONTEXT_WIDTH) <= budget {
            break;
        }
        fields.retain(|(_, field_priority)| *field_priority != priority);
    }
    let context = if line_width(&fields, CONTEXT_WIDTH) <= budget {
        context
    } else if line_width(&fields, BRIEF_CONTEXT_WIDTH) <= budget {
        // 精确上下文数量在 /status 可查；窄窗仍区分累计总量和当前占比。
        brief_context
    } else if CONTEXT_WIDTH <= budget {
        fields.clear();
        context
    } else if BRIEF_CONTEXT_WIDTH <= budget {
        fields.clear();
        brief_context
    } else if PERCENT_WIDTH <= budget {
        fields.clear();
        compact_context
    } else {
        fields.clear();
        String::new()
    };
    let mut spans = vec![Span::raw(" ".repeat(padding))];
    for (index, (field, _)) in fields.iter().enumerate() {
        if index > 0 {
            spans.push(Span::raw("  "));
        }
        spans.push(Span::styled(field.clone(), Style::default().fg(MUTED)));
    }
    if !fields.is_empty() && !context.is_empty() {
        spans.push(Span::styled(" · ", Style::default().fg(MUTED)));
    }
    spans.push(Span::styled(context, Style::default().fg(context_color)));
    Line::from(spans)
}

/// 冻结窗口与当前请求保持原口径；风险色使用固定高对比语义色，未知用占位而非零读数。
fn context_label(usage: Option<&UiUsageSnapshot>) -> (String, String, String, Color) {
    let Some(context) = usage
        .and_then(|usage| usage.context.as_ref())
        .filter(|context| context.purpose == "assistant")
    else {
        return ("上下文—/— —%".into(), "上下文—%".into(), "—%".into(), MUTED);
    };
    let limit = context.profile.context_window_tokens;
    let measurement = context
        .context_estimate
        .as_ref()
        .map(|estimate| estimate.used_tokens)
        .or_else(|| {
            (context.certainty == "known")
                .then_some(context.total_tokens)
                .flatten()
        });
    let Some(used) = measurement.filter(|_| limit > 0) else {
        return (
            format!("上下文—/{} —%", format_tokens(limit)),
            "上下文—%".into(),
            "—%".into(),
            MUTED,
        );
    };
    let percentage = used as f64 / limit as f64 * 100.0;
    let percent = if used > 0 && percentage < 0.1 {
        "<0.1%".to_owned()
    } else if percentage >= 1_000.0 {
        // 保留真实超窗比率；科学计数限制读数宽度，不让异常大数驱动标签折叠。
        format!("{percentage:.1e}%")
    } else {
        format!("{percentage:.1}%")
    };
    let color = if percentage >= 95.0 {
        theme::RED
    } else if percentage >= 80.0 {
        theme::YELLOW
    } else {
        theme::TEXT
    };
    (
        format!(
            "上下文{}/{} {percent}",
            format_tokens(used),
            format_tokens(limit)
        ),
        format!("上下文{percent}"),
        percent,
        color,
    )
}

/// 标签和顺序始终固定；缺失/部分计量与写缓存明细留在 /status，底栏仅替换可信数字或占位符。
fn summary_fields(usage: Option<&UiUsageSnapshot>) -> Vec<(String, u8)> {
    let summary = usage.and_then(|usage| usage.summary.as_ref());
    let total = summary
        .filter(|summary| summary.total_request_count > 0)
        .map(|summary| format_tokens(summary.total_tokens))
        .unwrap_or_else(|| "—".into());
    let input = usage
        .and_then(UiUsageSnapshot::cumulative_input_tokens)
        .map(format_tokens)
        .unwrap_or_else(|| "—".into());
    let output = summary
        .filter(|summary| summary.output_request_count > 0)
        .map(|summary| format_tokens(summary.output_tokens))
        .unwrap_or_else(|| "—".into());
    let cache_read = summary
        .filter(|summary| summary.cache_read_request_count > 0)
        .map(|summary| format_tokens(summary.cache_read_tokens))
        .unwrap_or_else(|| "—".into());
    vec![
        (format!("累计{total:<TOKEN_WIDTH$}"), 0),
        (
            format!("输入{input:<TOKEN_WIDTH$}  输出{output:<TOKEN_WIDTH$}"),
            1,
        ),
        (format!("含缓存读{cache_read:<TOKEN_WIDTH$}"), 2),
    ]
}

/// 每组预留最大紧凑读数宽度；仅终端 resize 才改变字段组合，避免回复中位数变化导致布局跳动。
fn line_width(fields: &[(String, u8)], context_width: usize) -> usize {
    let fields_width = fields
        .iter()
        .map(|(_, priority)| match priority {
            0 => 4 + TOKEN_WIDTH,
            1 => 8 + TOKEN_WIDTH * 2 + 2,
            _ => 8 + TOKEN_WIDTH,
        })
        .sum::<usize>()
        + fields.len().saturating_sub(1) * 2;
    fields_width + usize::from(!fields.is_empty() && context_width > 0) * 3 + context_width
}

/// 大数采用有限个单位缩写；舍入跨越单位时先升档，精确值由既有状态详情保留。
fn format_tokens(tokens: u64) -> String {
    let value = tokens as f64;
    for (scale, suffix) in [
        (1e18, "E"),
        (1e15, "P"),
        (1e12, "T"),
        (1e9, "B"),
        (1e6, "M"),
        (1e3, "k"),
    ] {
        if value >= scale || (value >= scale * 0.9995 && scale > 1e3) {
            let scaled = value / scale;
            return if scaled < 10.0 {
                format!("{scaled:.1}{suffix}")
            } else {
                format!("{scaled:.0}{suffix}")
            };
        }
    }
    tokens.to_string()
}
