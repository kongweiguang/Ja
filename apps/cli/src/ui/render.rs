// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ratatui::Frame;
use ratatui::layout::{Constraint, Direction, Layout, Position, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{Block, Clear, Paragraph};

use super::composer::input_visual_lines;
use super::markdown::{code_label_line, render_code_lines, render_markdown};
use super::model::{
    InteractionQuestionKind, PendingPrompt, StreamPresentation, TimelineEntry, TimelineKind,
    TimelineStatus, TurnState, UiChoice,
};
use super::state::{
    ChoiceKind, DetailScroll, Panel, UiState, command_description, command_label, matching_commands,
};
use super::transcript::TranscriptBlock;
use super::usage::usage_line;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

const MUTED: Color = Color::Rgb(136, 136, 150);
const COMPOSER_BACKGROUND: Color = Color::Rgb(38, 40, 43);
const COMPOSER_FOREGROUND: Color = Color::Rgb(238, 239, 241);
const MAX_SELECTOR_ITEMS: usize = 8;
pub(super) const WORKING_INDICATOR_INTERVAL: Duration = Duration::from_millis(240);
const WORKING_INLINE_HINT_MIN_WIDTH: usize = 32;

/// 选择行只保留名称和简述；具体模型、会话与权限身份仍由 UiState 的候选列表持有。
struct SelectionRow {
    name: String,
    description: Option<String>,
    tag: Option<String>,
}

/// 正文与底部交互区共享唯一几何计划；正文追加和滚动不会改变 Composer 的行坐标。
pub fn render(frame: &mut Frame<'_>, state: &UiState) {
    let area = frame.area();
    let Some(plan) = render_plan(area, state) else {
        return;
    };
    let regions = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(plan.history_height),
            Constraint::Length(plan.spacer_height),
            Constraint::Length(plan.first_height),
            Constraint::Length(plan.second_height),
            Constraint::Length(plan.footer_height),
        ])
        .split(area);
    let mut transcript = state.transcript.borrow_mut();
    transcript.set_intro(state.should_show_welcome().then(|| {
        let lines = welcome_lines(state, plan.width);
        let source = lines
            .iter()
            .map(ToString::to_string)
            .collect::<Vec<_>>()
            .join("\n");
        TranscriptBlock {
            key: "welcome".into(),
            source_key: "welcome".into(),
            source_start: 0,
            source,
            lines: wrap_transcript_lines(lines, plan.width),
        }
    }));
    transcript.update_layout(
        area.width,
        regions[0],
        regions[1],
        transcript_active_blocks(state, plan.width),
    );
    transcript.render(frame);
    drop(transcript);
    if plan.pending {
        if plan.panel_height > 0 {
            render_pending_inline(frame, regions[2], state);
        }
        if plan.show_composer {
            render_composer(frame, regions[3], state, &plan);
        }
    } else if plan.full_choice {
        render_choice_view(frame, regions[2], state);
    } else if plan.selector_above_composer || plan.help {
        if plan.help {
            render_help_panel(frame, regions[2], state);
        } else {
            render_selector(frame, regions[2], state);
        }
        render_composer(frame, regions[3], state, &plan);
    } else {
        render_composer(frame, regions[2], state, &plan);
        if plan.panel_height > 0 {
            render_selector(frame, regions[3], state);
        }
    }
    if plan.footer_height > 0 {
        render_footer(frame, regions[4], state);
    }
    render_panel(frame, area, state);
}

struct RenderPlan {
    width: usize,
    composer_lines: Vec<String>,
    visible_composer_lines: usize,
    pending: bool,
    full_choice: bool,
    selector_above_composer: bool,
    help: bool,
    show_composer: bool,
    vertical_padding: u16,
    panel_height: u16,
    footer_height: u16,
    history_height: u16,
    spacer_height: u16,
    first_height: u16,
    second_height: u16,
}

/** 先固定编辑与必要反馈预算，再分配正文；内容长度与浏览状态不参与底部坐标计算。 */
fn render_plan(area: Rect, state: &UiState) -> Option<RenderPlan> {
    if area.width == 0 || area.height == 0 {
        return None;
    }
    let width = area.width as usize;
    let composer_lines = state.composer().visual_lines(width);
    let max_composer_lines = (state.terminal_height() / 3)
        .max(1)
        .min(area.height.saturating_sub(2).max(1) as usize);
    let visible_composer_lines = composer_lines.len().min(max_composer_lines);
    let pending = matches!(state.panel(), Some(Panel::Pending));
    let help = matches!(state.panel(), Some(Panel::Help { .. }));
    let help_content_height = if help {
        help_panel_content_height(width)
    } else {
        0
    };
    let full_choice = matches!(
        state.panel(),
        Some(Panel::Choices { kind, .. }) if !matches!(kind, ChoiceKind::Files | ChoiceKind::Skills)
    );
    let selector_above_composer = matches!(
        state.panel(),
        Some(Panel::Choices {
            kind: ChoiceKind::Files | ChoiceKind::Skills,
            ..
        })
    );
    let show_composer = (!pending && !full_choice) || (pending && pending_editor_visible(state));
    let required_panel_room = if pending || full_choice {
        5
    } else if help {
        help_content_height
    } else {
        selector_rows(state)
            .map(|(rows, _)| rows.len().min(4) as u16)
            .unwrap_or(0)
    };
    let minimum_panel = if help {
        help_content_height.min(2)
    } else {
        required_panel_room
    };
    // 优先保留编辑、面板最小操作区和双行反馈，再为回复尾部及返回入口预留最多五行。
    // 极矮窗缩减正文预算；同尺寸下预算不随回复增长或 Turn 终态变化。
    let history_floor = 5u16.min(
        area.height.saturating_sub(
            u16::from(show_composer)
                .saturating_mul(visible_composer_lines as u16)
                .saturating_add(minimum_panel)
                .saturating_add(2),
        ),
    );
    let minimum_interaction = u16::from(show_composer)
        .saturating_mul(visible_composer_lines as u16)
        .saturating_add(minimum_panel)
        .saturating_add(history_floor);
    let footer_height = if state.snapshot().thread_id.is_some()
        && area.height >= minimum_interaction.saturating_add(3)
    {
        3
    } else if area.height == 1 {
        u16::from(!show_composer)
    } else {
        area.height.saturating_sub(1).min(2)
    };
    let vertical_padding = u16::from(
        area.height
            .saturating_sub(footer_height)
            .saturating_sub(visible_composer_lines as u16)
            .saturating_sub(required_panel_room)
            .saturating_sub(history_floor)
            >= 2,
    );
    let composer_height = if show_composer {
        (visible_composer_lines as u16 + vertical_padding * 2)
            .min(area.height.saturating_sub(footer_height))
    } else {
        0
    };
    let panel_budget = area.height.saturating_sub(composer_height + footer_height);
    let mut panel_height = if pending {
        panel_budget.min(8)
    } else if help {
        // 帮助至少保留标题与一行正文；其余空间先留给活动尾部，短窗仍可滚动到全部按键。
        let reserved_history = if state.snapshot().turn_state == TurnState::Working {
            history_floor.min(panel_budget.saturating_sub(2))
        } else {
            0
        };
        panel_budget
            .saturating_sub(reserved_history)
            .min(help_content_height)
    } else if full_choice {
        selector_rows(state)
            .map(|(rows, _)| rows.len().min(8).saturating_add(3) as u16)
            .unwrap_or(0)
            .min(panel_budget)
    } else {
        selector_rows(state)
            .map(|(rows, _)| {
                let visible = rows.len().min(MAX_SELECTOR_ITEMS);
                (visible + usize::from(rows.len() > visible)) as u16
            })
            .unwrap_or(0)
            .min(panel_budget)
    };
    let interaction_room = area.height.saturating_sub(composer_height + footer_height);
    panel_height = panel_height.min(interaction_room.saturating_sub(history_floor));
    let history_budget = area
        .height
        .saturating_sub(composer_height + panel_height + footer_height);
    let spacer_height = u16::from(history_budget >= 2);
    let history_height = history_budget.saturating_sub(spacer_height);
    let panel_before_composer = pending || full_choice || selector_above_composer || help;
    let first_height = if panel_before_composer {
        panel_height
    } else {
        composer_height
    };
    let second_height = if panel_before_composer {
        composer_height
    } else {
        panel_height
    };
    Some(RenderPlan {
        width,
        composer_lines,
        visible_composer_lines,
        pending,
        full_choice,
        selector_above_composer,
        help,
        show_composer,
        vertical_padding,
        panel_height,
        footer_height,
        history_height,
        spacer_height,
        first_height,
        second_height,
    })
}

/// Codex 的审批列表取代 Composer；只有文本题或自由回答仍需要硬件输入光标。
fn pending_editor_visible(state: &UiState) -> bool {
    let Some((prompt, question_index, _, _, using_free_text)) = state.prompt_view() else {
        return false;
    };
    match prompt {
        PendingPrompt::ToolApproval { .. } => false,
        PendingPrompt::Clarification { questions, .. } => {
            using_free_text
                || questions
                    .get(question_index)
                    .is_some_and(|question| question.kind == InteractionQuestionKind::Text)
        }
    }
}

/// 稳定与活动正文复用相同排版入口；缓存由 transcript 按条目身份和宽度管理。
pub(super) fn transcript_entry_lines(entry: &TimelineEntry, width: usize) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    append_entry_lines(&mut lines, entry, false, width);
    wrap_transcript_lines(lines, width)
}

/// 字素折行保留整行样式，正文与面板共享显示列宽语义。
fn wrap_transcript_lines(lines: Vec<Line<'static>>, width: usize) -> Vec<Line<'static>> {
    let width = width.max(1);
    let mut wrapped = Vec::new();
    for line in lines {
        let mut current = Line::default().style(line.style);
        let mut cells = 0usize;
        for span in line.spans {
            let mut segment = String::new();
            for grapheme in span.content.graphemes(true) {
                if grapheme == "\n" {
                    push_wrapped_span(&mut current, &mut segment, span.style);
                    wrapped.push(current);
                    current = Line::default().style(line.style);
                    cells = 0;
                    continue;
                }
                let cell_width = UnicodeWidthStr::width(grapheme).max(1);
                if cells > 0 && cells.saturating_add(cell_width) > width {
                    push_wrapped_span(&mut current, &mut segment, span.style);
                    wrapped.push(current);
                    current = Line::default().style(line.style);
                    cells = 0;
                }
                segment.push_str(grapheme);
                cells = cells.saturating_add(cell_width);
            }
            push_wrapped_span(&mut current, &mut segment, span.style);
        }
        wrapped.push(current);
    }
    wrapped
}

/// 合并同样式相邻字素，降低长消息历史行的分配数量。
fn push_wrapped_span(line: &mut Line<'static>, text: &mut String, style: Style) {
    if !text.is_empty() {
        line.spans.push(Span::styled(std::mem::take(text), style));
    }
}

/// 新会话首屏只写品牌、实际工作目录和当前权限，模型统一留在状态栏。
fn welcome_lines(state: &UiState, width: usize) -> Vec<Line<'static>> {
    let directory = state
        .snapshot()
        .workspace_path
        .as_deref()
        .or(state.snapshot().project_label.as_deref())
        .unwrap_or("当前目录");
    let permission = state
        .snapshot()
        .permission_label
        .as_deref()
        .unwrap_or("默认");
    let brand = truncate_cells(">_ Ja", width);
    let directory_label = if width >= 6 {
        "目录： "
    } else if width >= 4 {
        "目录"
    } else {
        ""
    };
    let permission_label = if width >= 6 {
        "权限： "
    } else if width >= 4 {
        "权限"
    } else {
        ""
    };
    let version = truncate_cells(
        &format!(" v{}", env!("CARGO_PKG_VERSION")),
        width.saturating_sub(UnicodeWidthStr::width(brand.as_str())),
    );
    let directory = truncate_cells(
        &display_directory(directory),
        width.saturating_sub(UnicodeWidthStr::width(directory_label)),
    );
    let permission = truncate_cells(
        permission,
        width.saturating_sub(UnicodeWidthStr::width(permission_label)),
    );
    vec![
        Line::from(vec![
            Span::styled(
                brand,
                Style::default()
                    .fg(Color::Blue)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::styled(version, Style::default().fg(MUTED)),
        ]),
        Line::from(vec![
            Span::styled(directory_label, Style::default().fg(MUTED)),
            Span::styled(directory, Style::default().fg(Color::Green)),
        ]),
        Line::from(vec![
            Span::styled(permission_label, Style::default().fg(MUTED)),
            Span::styled(permission, Style::default().fg(Color::Reset)),
        ]),
    ]
}

/// 活动块携带归档后的同一源身份；状态动效保留在正文尾部，不改变底部区域预算。
fn transcript_active_blocks(state: &UiState, width: usize) -> Vec<TranscriptBlock> {
    let mut blocks = Vec::new();
    for entry in state.visible_entries() {
        let mut lines = Vec::new();
        let stream_continuation = state.stream_has_started(&entry.id);
        if stream_continuation && entry.text.is_empty() {
            continue;
        }
        if let Some((presentation, spacing)) = state.stream_tail_layout(&entry.id) {
            if !stream_continuation || spacing {
                append_reply_gap(&mut lines);
            }
            append_stream_fragment(
                &mut lines,
                &entry.text,
                presentation,
                width,
                matches!(entry.kind, TimelineKind::ReasoningSummary),
            );
        } else {
            append_entry_lines(&mut lines, entry, stream_continuation, width);
        }
        let (source_key, source_start) = state.transcript_source(&entry.id);
        blocks.push(TranscriptBlock {
            key: entry.id.clone(),
            source_key,
            source_start,
            source: entry.text.clone(),
            lines: wrap_transcript_lines(lines, width),
        });
    }
    let mut lines = Vec::new();
    if state.snapshot().turn_state == TurnState::Working
        && state.snapshot().pending_prompt.is_none()
    {
        lines.push(Line::default());
        let elapsed = state
            .snapshot()
            .turn_started_at
            .and_then(|started| SystemTime::now().duration_since(started).ok())
            .map(|elapsed| elapsed.as_secs());
        lines.push(working_indicator_line(
            state.panel().is_none()
                && state.composer().is_empty()
                && width >= WORKING_INLINE_HINT_MIN_WIDTH,
            elapsed.filter(|_| width >= 48),
        ));
    } else if state.snapshot().turn_state == TurnState::Paused {
        lines.push(Line::default());
        lines.push(paused_indicator_line());
    }
    if !lines.is_empty() {
        blocks.push(TranscriptBlock {
            key: "turn-status".into(),
            source_key: "turn-status".into(),
            source_start: 0,
            source: String::new(),
            lines: wrap_transcript_lines(lines, width),
        });
    }
    if let Some(text) = state.visible_pending_message_text() {
        let mut lines = Vec::new();
        append_input_lines(&mut lines, text, width);
        blocks.push(TranscriptBlock {
            key: "pending-message".into(),
            source_key: "pending-message".into(),
            source_start: 0,
            source: text.into(),
            lines: wrap_transcript_lines(lines, width),
        });
    }
    blocks
}

/// 快照只证明执行已挂起，未提供暂停来源，因此稳定显示通用状态而不推断 Plan 操作。
fn paused_indicator_line() -> Line<'static> {
    Line::from(Span::styled(
        "• 运行已暂停或中断",
        Style::default().fg(MUTED),
    ))
}

/// 保留三点动效；用时取后台起点，窄窗优先留下取消按键，未来或无效时间不显示。
fn working_indicator_line(show_cancel_hint: bool, elapsed: Option<u64>) -> Line<'static> {
    let phase = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        / WORKING_INDICATOR_INTERVAL.as_millis();
    let active_dot = phase % 3;
    let mut spans = vec![
        Span::styled("• ", Style::default().fg(Color::Reset)),
        Span::styled("正在工作 ", Style::default().fg(MUTED)),
    ];
    for index in 0..3 {
        let color = if index as u128 == active_dot {
            Color::Rgb(205, 205, 214)
        } else {
            MUTED
        };
        spans.push(Span::styled(".", Style::default().fg(color)));
    }
    if let Some(seconds) = elapsed {
        spans.push(Span::styled(
            format!(" · {}", work_duration(seconds)),
            Style::default().fg(MUTED),
        ));
    }
    if show_cancel_hint {
        spans.push(Span::styled(" · Esc 中断", Style::default().fg(MUTED)));
    }
    Line::from(spans)
}

/// 与完成态共用中文时长格式，亚秒任务保留真实精度边界，不显示虚构的零秒。
fn work_duration(seconds: u64) -> String {
    if seconds == 0 {
        "不足1秒".into()
    } else if seconds < 60 {
        format!("{seconds}秒")
    } else if seconds < 3600 {
        format!("{}分{}秒", seconds / 60, seconds % 60)
    } else {
        format!("{}小时{}分", seconds / 3600, seconds % 3600 / 60)
    }
}

/// 发送问题与可编辑草稿使用成对前景和底色，保证历史留白与正文的可读性一致。
fn input_style() -> Style {
    Style::default()
        .fg(COMPOSER_FOREGROUND)
        .bg(COMPOSER_BACKGROUND)
}

/// Paragraph 的空行样式不填充整行，用真实空格延展灰底，确保历史留白和行尾与输入一致。
fn input_history_line(mut text: String, width: usize) -> Line<'static> {
    let remaining = width.saturating_sub(UnicodeWidthStr::width(text.as_str()));
    text.push_str(&" ".repeat(remaining));
    Line::raw(text).style(input_style())
}

/// 待发送与权威问题复用字素折行和整行灰底，测量和绘制不能维护两种问题宽度。
fn append_input_lines(lines: &mut Vec<Line<'static>>, text: &str, width: usize) {
    lines.push(Line::default());
    lines.push(input_history_line(String::new(), width));
    lines.extend(
        input_visual_lines(text, width)
            .into_iter()
            .map(|text| input_history_line(text, width)),
    );
    lines.push(input_history_line(String::new(), width));
}

/// 问题与草稿共用折行和灰底；工具预览限制显示行数，不读取完整产物或改变执行状态。
fn append_entry_lines(
    lines: &mut Vec<Line<'static>>,
    entry: &TimelineEntry,
    stream_continuation: bool,
    width: usize,
) {
    match &entry.kind {
        TimelineKind::User => {
            append_input_lines(lines, &entry.text, width);
        }
        TimelineKind::WorkSummary { elapsed_seconds } => {
            lines.push(Line::default());
            lines.push(Line::from(Span::styled(
                format!("  工作用时 {}", work_duration(*elapsed_seconds)),
                Style::default().fg(MUTED),
            )));
        }
        TimelineKind::Assistant
        | TimelineKind::FinalAnswer
        | TimelineKind::Commentary
        | TimelineKind::ReasoningSummary => {
            if !stream_continuation {
                append_reply_gap(lines);
            }
            append_reply_lines(
                lines,
                render_markdown(&entry.text, width.saturating_sub(2).max(1)).lines,
                matches!(entry.kind, TimelineKind::ReasoningSummary),
            );
            if entry.status == Some(TimelineStatus::Failed)
                && let Some(detail) = entry.detail.as_deref()
            {
                append_plain(
                    lines,
                    &bounded_lines(detail, 3),
                    "   ",
                    if entry.status == Some(TimelineStatus::Failed) {
                        Color::Red
                    } else {
                        Color::Reset
                    },
                );
            }
        }
        TimelineKind::AssistantFragment {
            first,
            presentation,
            reasoning,
        } => {
            if *first {
                append_reply_gap(lines);
            }
            append_stream_fragment(lines, &entry.text, *presentation, width, *reasoning);
        }
        TimelineKind::Tool {
            action,
            target,
            preview_truncated,
        } => {
            append_reply_gap(lines);
            let action_color = if entry.status == Some(TimelineStatus::Failed) {
                Color::Red
            } else {
                Color::Yellow
            };
            lines.push(Line::from(vec![
                Span::raw("  "),
                Span::styled(
                    action.clone(),
                    Style::default()
                        .fg(action_color)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(" "),
                Span::styled(
                    truncate_cells(
                        target,
                        width.saturating_sub(UnicodeWidthStr::width(action.as_str()) + 5),
                    ),
                    Style::default().fg(Color::Cyan),
                ),
                Span::styled(" ▸", Style::default().fg(MUTED)),
            ]));
            append_tool_preview(lines, entry, width, *preview_truncated);
        }
    }
}

/// 成功与失败共用三行安全预览；同时保留源端截断事实，不能把短预览误当完整结果。
fn append_tool_preview(
    lines: &mut Vec<Line<'static>>,
    entry: &TimelineEntry,
    width: usize,
    preview_truncated: bool,
) {
    let indent = width.saturating_sub(1).min(4);
    let content_width = width.saturating_sub(indent);
    let detail = entry
        .detail
        .as_deref()
        .filter(|text| !text.trim().is_empty());
    if detail.is_none() && entry.status != Some(TimelineStatus::Running) {
        lines.push(Line::from(Span::styled(
            truncate_cells("  └ （暂无结果预览）", width),
            Style::default().fg(MUTED),
        )));
    }
    let mut source_lines = detail.unwrap_or_default().lines();
    let mut truncated = preview_truncated;
    for index in 0..3 {
        let Some(text) = source_lines.next() else {
            break;
        };
        let text = text.replace('\t', "    ");
        truncated |= UnicodeWidthStr::width(text.as_str()) > content_width;
        let prefix = if index == 0 && indent == 4 {
            "  └ ".to_owned()
        } else {
            " ".repeat(indent)
        };
        lines.push(Line::from(Span::styled(
            format!("{prefix}{}", truncate_cells(&text, content_width)),
            Style::default().fg(MUTED),
        )));
    }
    if truncated || source_lines.next().is_some() {
        lines.push(Line::from(Span::styled(
            format!(
                "{}{}",
                " ".repeat(indent),
                truncate_cells("… Ctrl+O 查看详情", content_width),
            ),
            Style::default().fg(MUTED),
        )));
    }
}

/// 已归档区块与活动尾部共用宽度和代码渲染，正文类别只影响色彩而不改写源文本。
fn append_stream_fragment(
    lines: &mut Vec<Line<'static>>,
    text: &str,
    presentation: StreamPresentation,
    width: usize,
    reasoning: bool,
) {
    let content_width = width.saturating_sub(2).max(1);
    let mut rendered = match presentation {
        StreamPresentation::Markdown => render_markdown(text, content_width).lines,
        StreamPresentation::CodeLabel => vec![code_label_line(text)],
        StreamPresentation::Code => render_code_lines(text, content_width),
        StreamPresentation::Literal => text
            .lines()
            .map(|line| Line::raw(line.replace('\t', "    ")))
            .collect::<Vec<_>>(),
        StreamPresentation::Spacer => vec![Line::default()],
    };
    if presentation == StreamPresentation::Spacer {
        // 区块间距独立于代码正文里的真实空行；流账本只产生一次 Spacer。
        lines.push(Line::default());
    } else {
        append_reply_lines(lines, std::mem::take(&mut rendered), reasoning);
    }
}

/// 留白只在区块边界增加一次；源代码的真实空白行由代码 renderer 自行保留。
fn append_reply_gap(lines: &mut Vec<Line<'static>>) {
    if lines.last().is_none_or(|line| !line.spans.is_empty()) {
        lines.push(Line::default());
    }
}

/// 外层只加两列正文边距，列表和引用的续行缩进由共享 renderer 测量，避免二次布局分歧。
fn append_reply_lines(
    lines: &mut Vec<Line<'static>>,
    rendered: Vec<Line<'static>>,
    reasoning: bool,
) {
    for mut line in rendered {
        if reasoning {
            for span in &mut line.spans {
                span.style = span.style.fg(MUTED).add_modifier(Modifier::ITALIC);
            }
        }
        if !line.spans.is_empty() {
            line.spans.insert(0, Span::raw("  "));
        }
        lines.push(line);
    }
}

/// 将普通用户或 Commentary 正文拆为行，避免显示重复的角色标题。
/// 纯文本只强调首行身份符号，正文及后续缩进行由调用方的语义颜色控制。
fn append_plain(lines: &mut Vec<Line<'static>>, value: &str, prefix: &str, color: Color) {
    for (index, line) in value.lines().enumerate() {
        let mut spans = Vec::new();
        if index == 0 && !prefix.is_empty() {
            spans.push(Span::styled(
                prefix.to_owned(),
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD),
            ));
        } else if index > 0 && !prefix.is_empty() {
            spans.push(Span::raw("  "));
        }
        spans.push(Span::styled(line.to_owned(), Style::default().fg(color)));
        lines.push(Line::from(spans));
    }
}

/// 失败工具诊断自动展开最多三行，避免异常输出挤掉输入区。
fn bounded_lines(value: &str, limit: usize) -> String {
    let mut lines = value
        .lines()
        .take(limit)
        .map(|line| truncate_detail_line(line, 512))
        .collect::<Vec<_>>();
    if value.lines().count() > limit {
        lines.push("…诊断已截断".to_owned());
    }
    lines.join("\n")
}

/// 控制台行内诊断逐行设限，完整正文仍可由详情面板按需读取。
fn truncate_detail_line(value: &str, max_bytes: usize) -> String {
    let mut output = String::new();
    let mut truncated = false;
    for ch in value.chars() {
        if output.len().saturating_add(ch.len_utf8()) > max_bytes {
            truncated = true;
            break;
        }
        output.push(ch);
    }
    if truncated {
        output.push('…');
    }
    output
}

/// 箭头与已发送问题共用左缘，只保留上下留白；绘制与光标共享实际内容矩形。
fn render_composer(frame: &mut Frame<'_>, area: Rect, state: &UiState, plan: &RenderPlan) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    let padding_rows = plan.vertical_padding.min(area.height / 2);
    let input_area = Rect::new(
        area.x,
        area.y + padding_rows,
        area.width,
        area.height.saturating_sub(padding_rows * 2),
    );
    if input_area.width == 0 || input_area.height == 0 {
        return;
    }
    frame.render_widget(Block::default().style(input_style()), area);
    let (cursor_row, cursor_column) = state.composer().visual_cursor(plan.width);
    let visible_lines = plan.visible_composer_lines.min(input_area.height as usize);
    let first_visible = cursor_row.saturating_add(1).saturating_sub(visible_lines);
    let visible = if state.composer().is_empty() && state.panel().is_none() {
        vec![Line::from(Span::styled(
            if input_area.width >= 2 { "› " } else { "›" },
            input_style().add_modifier(Modifier::BOLD),
        ))]
    } else {
        plan.composer_lines
            .iter()
            .skip(first_visible)
            .take(input_area.height as usize)
            .map(|line| Line::from(line.clone()))
            .collect::<Vec<_>>()
    };
    frame.render_widget(
        Paragraph::new(Text::from(visible)).style(input_style()),
        input_area,
    );
    let row = cursor_row
        .saturating_sub(first_visible)
        .min(input_area.height.saturating_sub(1) as usize);
    let column = cursor_column.min(input_area.width.saturating_sub(1) as usize);
    if !matches!(
        state.panel(),
        Some(Panel::Details { .. } | Panel::ChoiceDetail { .. } | Panel::ModelSettings)
    ) {
        frame.set_cursor_position(Position::new(
            input_area.x + column as u16,
            input_area.y + row as u16,
        ));
    }
}

/// 身份、用量与操作反馈各有固定位置；空闲帮助也留在操作行，避免挤占会话身份。
/// 矮窗先让用量退出；刷新入口让位于审批和恢复反馈，不能用计量错误覆盖聊天操作。
/// @author kongweiguang
fn render_footer(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    let snapshot = state.snapshot();
    let identity_height = u16::from(area.height >= 2);
    if identity_height > 0 {
        let identity = status_identity(snapshot, area.width as usize);
        frame.render_widget(
            Paragraph::new(identity),
            Rect::new(area.x, area.y, area.width, 1),
        );
    }
    if area.height >= 3 {
        frame.render_widget(
            Paragraph::new(usage_line(snapshot.usage.as_ref(), area.width as usize)),
            Rect::new(area.x, area.y + 1, area.width, 1),
        );
    }
    let area = Rect::new(area.x, area.y + area.height - 1, area.width, 1);
    if state.older_history_failed() && state.panel().is_none() {
        render_status_message(
            frame,
            area,
            snapshot.notice.as_deref().unwrap_or("读取更早历史失败"),
            if area.width < 12 { "F5" } else { "F5 重试" },
        );
        return;
    }
    if state.prompt_response_needs_refresh()
        || snapshot
            .notice
            .as_deref()
            .is_some_and(|notice| notice.contains("F5"))
    {
        let hint = match (
            state.can_retry_cancellation(),
            state.panel().is_some(),
            area.width < 18,
        ) {
            (true, _, false) => "F5 核实 · Esc 重试",
            (false, true, false) => "F5 核实 · Esc 返回",
            (_, true, true) | (true, _, true) => "F5 · Esc",
            (false, false, true) => "F5",
            (false, false, false) => "F5 核实",
        };
        render_status_message(
            frame,
            area,
            snapshot.notice.as_deref().unwrap_or("提交结果暂未核实"),
            hint,
        );
        return;
    }
    if state.prompt_submission_pending() && matches!(state.panel(), Some(Panel::Pending)) {
        frame.render_widget(
            Paragraph::new(truncate_cells(
                "正在发送回答 · Esc 收起",
                area.width as usize,
            ))
            .style(Style::default().fg(MUTED)),
            area,
        );
        return;
    }
    if state.has_pending_submission() {
        let message = if state.pending_message_text().is_some() {
            "正在发送"
        } else {
            "正在导入附件"
        };
        render_status_message(frame, area, message, "");
        return;
    }
    if let Some(notice) = snapshot.notice.as_deref() {
        if let Some(hint) = notice_panel_hint(state, area.width) {
            render_status_message(frame, area, notice, hint);
        } else {
            frame.render_widget(
                Paragraph::new(truncate_cells(notice, area.width as usize)).style(
                    Style::default()
                        .fg(Color::Reset)
                        .add_modifier(Modifier::BOLD),
                ),
                area,
            );
        }
        return;
    }
    if state.older_history_loading() {
        render_status_message(frame, area, "正在读取更早历史", "");
        return;
    }
    let hint = match state.panel() {
        Some(Panel::Commands { .. }) if area.width < 48 => Some("  Tab 补全 · Enter 执行 · Esc"),
        Some(Panel::Commands { .. }) => Some("  ↑↓ 选择 · Enter 执行 · Tab 补全 · Esc 返回"),
        Some(Panel::Choices {
            kind: ChoiceKind::Files,
            ..
        }) => Some("  ↑↓ 选择 · Enter 引用 · Esc 返回"),
        Some(Panel::Choices {
            kind: ChoiceKind::Skills,
            ..
        }) => Some("  ↑↓ 选择 · Enter 引用技能 · Esc 返回"),
        Some(Panel::Choices {
            kind: ChoiceKind::Attachments,
            ..
        }) => Some("  Enter 预览 · Del 移除 · Esc 返回"),
        Some(Panel::Choices {
            kind: ChoiceKind::ToolSteps,
            ..
        }) => Some("  ↑↓ 选择 · Enter 详情 · Esc 返回"),
        Some(Panel::Choices {
            kind: ChoiceKind::Reasoning,
            ..
        }) => Some("  Enter 确认 · F2 说明 · Esc 返回模型"),
        Some(Panel::Choices {
            kind: ChoiceKind::Models | ChoiceKind::Permissions,
            ..
        }) if area.width < 40 => Some("  Enter 确认 · F2 说明 · Esc"),
        Some(Panel::Choices {
            kind: ChoiceKind::Models | ChoiceKind::Permissions,
            ..
        }) => Some("  Enter 确认 · F2 说明 · Esc 返回"),
        Some(Panel::Choices {
            kind: ChoiceKind::Threads,
            ..
        }) if area.width < 22 => Some("Enter恢复 Esc"),
        Some(Panel::Choices {
            kind: ChoiceKind::Threads,
            ..
        }) if area.width < 29 => Some("↑↓选 · Enter恢复 · Esc"),
        Some(Panel::Choices {
            kind: ChoiceKind::Threads,
            ..
        }) => Some("  ↑↓ 选择 · Enter 恢复 · Esc 返回"),
        Some(Panel::Choices {
            kind: ChoiceKind::ForkReplies,
            ..
        }) if snapshot.fork_reply_next_cursor.is_some() => {
            Some("  ↑↓ 选择 · PgDn 更早 · Enter 分叉 · Esc 返回")
        }
        Some(Panel::Choices {
            kind: ChoiceKind::ForkReplies,
            ..
        }) => Some("  ↑↓ 选择 · Enter 分叉 · Esc 返回"),
        Some(Panel::Choices { .. }) => Some("  Enter 确认或 Esc 返回"),
        Some(Panel::Pending) => Some(pending_footer_hint(state, area.width)),
        Some(Panel::Help { .. }) if snapshot.turn_state == TurnState::Working => {
            Some(if area.width < 24 {
                "Esc/? 收起"
            } else if area.width < 64 {
                "回复继续 · ↑↓ 浏览 · Esc/? 收起"
            } else {
                "回复继续 · ↑↓/PgUp/PgDn/Home/End 浏览 · Esc/? 收起"
            })
        }
        Some(Panel::Help { .. }) if area.width < 40 => Some("↑↓/Pg 翻页 · Esc/? 返回"),
        Some(Panel::Help { .. }) => Some("↑↓/PgUp/PgDn/Home/End 浏览 · Esc/? 返回"),
        Some(Panel::Details { .. } | Panel::ChoiceDetail { .. }) => Some("  Esc 返回"),
        Some(Panel::ModelSettings) => None,
        None => None,
    };
    if let Some(hint) = hint {
        let hint = if UnicodeWidthStr::width(hint) > area.width as usize {
            match state.panel() {
                Some(Panel::Help { .. }) => "Esc/? · ↑↓",
                Some(Panel::Commands { .. }) => "Esc · Enter · Tab",
                Some(Panel::Pending) => "Esc · Enter · F2",
                Some(Panel::Choices { .. }) => "Esc · Enter",
                Some(Panel::Details { .. } | Panel::ChoiceDetail { .. }) => "Esc",
                Some(Panel::ModelSettings) => "Esc",
                None => hint,
            }
        } else {
            hint
        };
        frame.render_widget(
            Paragraph::new(truncate_cells(hint, area.width as usize))
                .style(Style::default().fg(MUTED)),
            area,
        );
        return;
    }
    if let Some(prompt) = snapshot.pending_prompt.as_ref() {
        let hint = match prompt {
            PendingPrompt::ToolApproval { .. } => "  待审批 · Enter 继续处理",
            PendingPrompt::Clarification { .. } => "  待补充信息 · Enter 继续处理",
        };
        frame.render_widget(
            Paragraph::new(truncate_cells(hint, area.width as usize)).style(
                Style::default()
                    .fg(Color::Reset)
                    .add_modifier(Modifier::BOLD),
            ),
            area,
        );
        return;
    }
    let attaching = state.composer().text().starts_with("/attach ");
    if attaching {
        render_status_message(frame, area, "附件路径 · 含空格请加引号", "Enter 导入");
        return;
    }
    if snapshot.turn_state == TurnState::Paused {
        let hint = if state.suspended_control_pending() {
            "F5 核实恢复结果"
        } else {
            "F6 恢复任务 · F7 取消任务"
        };
        frame.render_widget(
            Paragraph::new(truncate_cells(hint, area.width as usize))
                .style(Style::default().fg(MUTED)),
            area,
        );
        return;
    }
    let hint = match snapshot.turn_state {
        TurnState::Idle if snapshot.usage.as_ref().is_some_and(|usage| usage.stale) => {
            Some(if area.width < 16 {
                "F5 刷新"
            } else {
                "  F5 刷新用量"
            })
        }
        TurnState::Idle if state.composer().is_empty() => Some(if area.width < 6 {
            "?"
        } else if area.width < 14 {
            "? 帮助"
        } else {
            "  ? 查看快捷键"
        }),
        TurnState::Working if snapshot.local_operation.is_some() => Some("Esc 取消"),
        TurnState::Working if state.composer().is_empty() => Some("Esc 中断"),
        TurnState::Working => Some("Enter 调整 · Tab 排队"),
        TurnState::Paused if area.width < 40 => Some("/status 核实"),
        TurnState::Paused => Some("/status 核实状态 · /plan status 查看计划"),
        TurnState::Failed if snapshot.continuation_available && state.composer().is_empty() => {
            Some("Enter 继续回复")
        }
        _ => None,
    };
    if let Some(hint) = hint {
        frame.render_widget(
            Paragraph::new(truncate_cells(hint, area.width as usize))
                .style(Style::default().fg(MUTED)),
            area,
        );
    }
}

/// 显示操作错误时仍留下能退回或重试当前面板的按键，窄屏把 Esc 放在首位。
fn notice_panel_hint(state: &UiState, width: u16) -> Option<&'static str> {
    if width < 29 {
        return Some("Esc 返回");
    }
    match state.panel() {
        Some(Panel::Commands { .. }) => Some("Tab 补全 · Esc 返回"),
        Some(Panel::Choices { .. }) | Some(Panel::Pending) => Some("Enter 重试 · Esc 返回"),
        Some(Panel::Help { .. }) => Some("Esc/? 返回"),
        Some(Panel::Details { .. } | Panel::ChoiceDetail { .. }) => Some("Esc 返回"),
        Some(Panel::ModelSettings) => Some("Esc 返回"),
        None => None,
    }
}

/// 身份按模型、目录、标题连续排列；只在空间不足时分配剩余列，不用填充空格把标题推到右端。
fn status_identity(snapshot: &super::model::UiSnapshot, width: usize) -> Line<'static> {
    let model = snapshot.model_identifier.as_deref().unwrap_or("未配置模型");
    let model = snapshot
        .reasoning_label
        .as_deref()
        .map_or_else(|| model.to_owned(), |level| format!("{model} {level}"));
    let directory = snapshot
        .workspace_path
        .as_deref()
        .or(snapshot.project_label.as_deref())
        .unwrap_or("当前目录");
    let directory = display_directory(directory);
    let title = snapshot
        .thread_title
        .as_deref()
        .unwrap_or_default()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    let mut spans = Vec::new();
    let padding = usize::from(width >= 4) * 2;
    let mut remaining = width.saturating_sub(padding * 2);
    if padding > 0 {
        spans.push(Span::raw(" ".repeat(padding)));
    }
    let model = truncate_cells(&model, remaining);
    remaining = remaining.saturating_sub(UnicodeWidthStr::width(model.as_str()));
    if !model.is_empty() {
        spans.push(Span::styled(
            model,
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD),
        ));
    }
    // 长路径和长标题共享空间；短路径释放的列可供标题使用，不限制正常短值的自然位置。
    let title = if remaining >= 8 {
        let content_budget = remaining.saturating_sub(6);
        let directory_width = UnicodeWidthStr::width(directory.as_str());
        truncate_cells(
            &title,
            content_budget.saturating_sub(directory_width.min(content_budget / 2)),
        )
    } else {
        String::new()
    };
    let title_width = UnicodeWidthStr::width(title.as_str());
    let directory_budget =
        remaining.saturating_sub(3 + title_width + usize::from(title_width > 0) * 3);
    if directory_budget > 0 && remaining >= 4 {
        spans.push(Span::styled(" · ", Style::default().fg(MUTED)));
        let directory = truncate_cells(&directory, directory_budget);
        spans.push(Span::styled(directory, Style::default().fg(Color::Green)));
    }
    if title_width > 0 {
        spans.push(Span::styled(" · ", Style::default().fg(MUTED)));
        spans.push(Span::styled(title, Style::default().fg(Color::LightBlue)));
    }
    if padding > 0 {
        spans.push(Span::raw(" ".repeat(padding)));
    }
    Line::from(spans)
}

/// Windows 的规范化前缀用于文件身份，展示时还原用户可读的驱动器或 UNC 路径。
fn display_directory(path: &str) -> String {
    if let Some(unc) = path.strip_prefix(r"\\?\UNC\") {
        format!(r"\\{unc}")
    } else {
        path.strip_prefix(r"\\?\").unwrap_or(path).to_owned()
    }
}

/// 提交与附件导入暂时占用状态行时也保留原有左右对齐关系。
fn render_status_message(frame: &mut Frame<'_>, area: Rect, message: &str, hint: &str) {
    let width = area.width as usize;
    let right = truncate_cells(hint, width);
    let right_width = UnicodeWidthStr::width(right.as_str());
    let left_width = width.saturating_sub(right_width + 2);
    if left_width > 0 {
        frame.render_widget(
            Paragraph::new(truncate_cells(message, left_width)).style(Style::default().fg(MUTED)),
            Rect::new(area.x, area.y, left_width as u16, 1),
        );
    }
    if !right.is_empty() {
        frame.render_widget(
            Paragraph::new(right).style(
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD),
            ),
            Rect::new(
                area.x + (width - right_width) as u16,
                area.y,
                right_width as u16,
                1,
            ),
        );
    }
}

/// 窄终端先保留可执行按键；Codex 式选择提示不因超宽而把 Esc 或 Tab 截掉。
fn pending_footer_hint(state: &UiState, width: u16) -> &'static str {
    let narrow = width < 48;
    let Some((prompt, question_index, _, _, using_free_text)) = state.prompt_view() else {
        return "  Enter 确认 · Esc";
    };
    match prompt {
        PendingPrompt::ToolApproval { .. } if narrow => "  Enter 确认 · F2 全文 · Esc",
        PendingPrompt::ToolApproval { .. } => "  ↑↓ 选择 · Enter 确认 · F2 全文 · Esc 返回",
        PendingPrompt::Clarification { questions, .. } => {
            let Some(question) = questions.get(question_index) else {
                return "  Enter 确认 · Esc";
            };
            if question.allow_free_text && !using_free_text {
                if narrow {
                    "  Tab 自定义 · Enter 确认"
                } else {
                    "  ↑↓ 选择 · Tab 自定义回答 · Enter 确认 · Esc"
                }
            } else if question.kind == InteractionQuestionKind::Multiple && !using_free_text {
                if narrow {
                    "  Space 多选 · Enter 确认"
                } else {
                    "  ↑↓ 选择 · Space 多选 · Enter 确认 · Esc"
                }
            } else if question.kind == InteractionQuestionKind::Text || using_free_text {
                if narrow {
                    "  Enter 提交 · F2 全文 · Esc"
                } else {
                    "  输入回答 · Enter 提交 · F2 全文 · Esc"
                }
            } else if narrow {
                "  Enter 确认 · F2 全文 · Esc"
            } else {
                "  ↑↓ 选择 · Enter 确认 · F2 全文 · Esc"
            }
        }
    }
}

/// @author kongweiguang
/// 候选行共享名称/描述列；Fork 的空缓存、读取失败与成功空历史分别显示真实状态。
fn selector_rows(state: &UiState) -> Option<(Vec<SelectionRow>, Option<usize>)> {
    match state.panel()? {
        Panel::Commands { selected } => {
            let commands = matching_commands(state.composer().text());
            let selected = (!commands.is_empty()).then_some(*selected);
            let rows = if commands.is_empty() {
                vec![SelectionRow {
                    name: "没有匹配的命令".to_owned(),
                    description: None,
                    tag: None,
                }]
            } else {
                commands
                    .into_iter()
                    .map(|command| SelectionRow {
                        name: command_label(command).to_owned(),
                        description: Some(command_description(command).to_owned()),
                        tag: None,
                    })
                    .collect()
            };
            Some((rows, selected))
        }
        Panel::Choices { kind, selected } => {
            let choices = state.choices(*kind);
            let selected = (!choices.is_empty()).then_some(*selected);
            let rows = if choices.is_empty() {
                vec![SelectionRow {
                    name: match *kind {
                        ChoiceKind::ForkReplies if state.snapshot().fork_reply_loading => {
                            "正在加载回复…".to_owned()
                        }
                        ChoiceKind::ForkReplies if state.snapshot().fork_reply_load_failed => {
                            "读取回复失败；按 Esc 返回后重试 /fork".to_owned()
                        }
                        ChoiceKind::Threads if !state.thread_query().is_empty() => {
                            "当前项目没有匹配的会话".to_owned()
                        }
                        _ => empty_choice_label(*kind).to_owned(),
                    },
                    description: None,
                    tag: None,
                }]
            } else {
                choices
                    .iter()
                    .map(|choice| {
                        if *kind == ChoiceKind::Files {
                            let normalized = choice.label.replace('\\', "/");
                            let (directory, name) = normalized
                                .rsplit_once('/')
                                .map_or(("", normalized.as_str()), |(directory, name)| {
                                    (directory, name)
                                });
                            SelectionRow {
                                name: name.to_owned(),
                                description: Some(directory.to_owned()),
                                tag: choice.detail.as_deref().map(|kind| match kind {
                                    "file" => "文件".to_owned(),
                                    "directory" | "dir" => "目录".to_owned(),
                                    other => other.to_owned(),
                                }),
                            }
                        } else if *kind == ChoiceKind::Skills {
                            SelectionRow {
                                name: choice.label.clone(),
                                description: choice
                                    .detail
                                    .as_deref()
                                    .map(|description| format!("[技能] {description}")),
                                tag: None,
                            }
                        } else {
                            SelectionRow {
                                name: choice.label.clone(),
                                description: choice.detail.clone(),
                                tag: None,
                            }
                        }
                    })
                    .collect()
            };
            Some((rows, selected))
        }
        _ => None,
    }
}

/// 候选作为普通行参与布局，不清屏或画浮窗，避免终端缩放时遮住当前项。
fn render_selector(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    let Some((rows, selected)) = selector_rows(state) else {
        return;
    };
    if area.height == 0 || area.width == 0 {
        return;
    }
    let inset = u16::from(area.width >= 4) * 2;
    let content = Rect::new(
        area.x + inset,
        area.y,
        area.width.saturating_sub(inset),
        area.height,
    );
    let counter = rows.len() > content.height as usize && content.height >= 2;
    let visible_count = (content.height as usize - usize::from(counter)).max(1);
    let selected_index = selected.unwrap_or(0).min(rows.len().saturating_sub(1));
    let start = selected_index
        .saturating_sub(visible_count.saturating_sub(1))
        .min(rows.len().saturating_sub(visible_count));
    let label_width = rows
        .iter()
        .map(|row| UnicodeWidthStr::width(row.name.as_str()))
        .max()
        .unwrap_or(0)
        .saturating_add(2)
        .min(28)
        .min((content.width as usize / 2).max(1));
    let mut lines = Vec::with_capacity(content.height as usize);
    for (index, row) in rows.iter().enumerate().skip(start).take(visible_count) {
        let active = selected == Some(index);
        let marker = if active { "› " } else { "  " };
        let active_style = Style::default()
            .fg(Color::Cyan)
            .add_modifier(Modifier::BOLD);
        let mut spans = vec![Span::styled(
            marker,
            if active {
                active_style
            } else {
                Style::default().fg(Color::Reset)
            },
        )];
        let room = content.width.saturating_sub(2) as usize;
        let show_description = content.width >= 48 && row.description.is_some();
        let name_room = if show_description {
            label_width.min(room)
        } else {
            room
        };
        let name = truncate_cells(&row.name, name_room);
        let name_cells = UnicodeWidthStr::width(name.as_str());
        spans.push(Span::styled(
            name,
            if active {
                active_style
            } else {
                Style::default().fg(Color::Reset)
            },
        ));
        if show_description {
            let padding = label_width.saturating_sub(name_cells);
            spans.push(Span::raw(" ".repeat(padding)));
            let remaining = room.saturating_sub(label_width);
            let tag = row.tag.as_deref().filter(|_| content.width >= 60);
            let tag_width = tag.map(UnicodeWidthStr::width).unwrap_or(0);
            let description_width =
                remaining.saturating_sub(tag_width + usize::from(tag.is_some()) * 2);
            if let Some(description) = row.description.as_deref() {
                let description = truncate_cells(description, description_width);
                let used = UnicodeWidthStr::width(description.as_str());
                spans.push(Span::styled(
                    description,
                    if active {
                        active_style
                    } else {
                        Style::default().fg(MUTED)
                    },
                ));
                if let Some(tag) = tag {
                    spans.push(Span::raw(
                        " ".repeat(remaining.saturating_sub(used + tag_width)),
                    ));
                    spans.push(Span::styled(
                        tag.to_owned(),
                        if active {
                            active_style
                        } else {
                            Style::default().fg(MUTED)
                        },
                    ));
                }
            }
        }
        lines.push(Line::from(spans));
    }
    if counter {
        lines.push(Line::from(Span::styled(
            format!("  {}/{}", selected_index + 1, rows.len()),
            Style::default().fg(MUTED),
        )));
    }
    frame.render_widget(Paragraph::new(Text::from(lines)), content);
}

/// 模型列表只显示标题与可核实的配置事实；其它选择器保留操作简述，避免模型页重复展示实现来源。
fn render_choice_view(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    let Some(Panel::Choices { kind, .. }) = state.panel() else {
        return;
    };
    let Some((rows, selected)) = selector_rows(state) else {
        return;
    };
    if area.width == 0 || area.height == 0 {
        return;
    }
    let inset = u16::from(area.width >= 4) * 2;
    let content = Rect::new(
        area.x + inset,
        area.y,
        area.width.saturating_sub(inset),
        area.height,
    );
    let (title, description) = match kind {
        ChoiceKind::Models => ("选择模型".to_owned(), ""),
        ChoiceKind::Reasoning => (
            format!(
                "选择 {} 的推理等级",
                state
                    .snapshot()
                    .reasoning_model_identifier
                    .as_deref()
                    .unwrap_or("模型")
            ),
            "按模型实际支持的等级选择",
        ),
        ChoiceKind::Permissions => ("选择权限".to_owned(), "更改当前会话的执行权限"),
        ChoiceKind::Threads => ("恢复会话".to_owned(), "当前项目 · 搜索标题 · PgDn 加载更多"),
        ChoiceKind::ForkReplies => (
            "从回复处新建会话".to_owned(),
            "包含所选回复及此前历史 · PgDn 加载更早回复",
        ),
        ChoiceKind::InputHistory => (
            "搜索输入历史".to_owned(),
            "搜索输入 · PgDn 更多 · 列表保留最近 64 条",
        ),
        ChoiceKind::Attachments => ("附件".to_owned(), "Enter 预览，Del 移除"),
        ChoiceKind::Files => ("引用文件".to_owned(), "选择工作区文件"),
        ChoiceKind::Skills => ("引用技能".to_owned(), "选择可用技能"),
        ChoiceKind::ToolSteps => ("工具步骤".to_owned(), "Enter 查看详情 · Esc 返回回复"),
    };
    let description =
        if *kind == ChoiceKind::InputHistory && !state.input_history_query().is_empty() {
            format!(
                "搜索：{} · PgDn 更多 · 最近 64 条",
                state.input_history_query()
            )
        } else if *kind == ChoiceKind::Threads && !state.thread_query().is_empty() {
            format!("当前项目 · 搜索：{} · PgDn 加载更多", state.thread_query())
        } else {
            description.to_owned()
        };
    let mut lines = vec![Line::from(Span::styled(
        truncate_cells(&title, content.width as usize),
        Style::default()
            .fg(Color::Reset)
            .add_modifier(Modifier::BOLD),
    ))];
    if content.height >= 3 && !description.is_empty() {
        lines.push(Line::from(Span::styled(
            truncate_cells(&description, content.width as usize),
            Style::default().fg(MUTED),
        )));
        lines.push(Line::default());
    }
    let visible_count = (content.height as usize).saturating_sub(lines.len()).max(1);
    let selected_index = selected.unwrap_or(0).min(rows.len().saturating_sub(1));
    let start = selected_index
        .saturating_sub(visible_count.saturating_sub(1))
        .min(rows.len().saturating_sub(visible_count));
    let label_width = rows
        .iter()
        .map(|row| UnicodeWidthStr::width(row.name.as_str()))
        .max()
        .unwrap_or(0)
        .saturating_add(2)
        .min(28)
        .min((content.width as usize / 2).max(1));
    let number_digits = rows.len().to_string().len();
    for (index, row) in rows.iter().enumerate().skip(start).take(visible_count) {
        let active = selected == Some(index);
        let active_style = Style::default()
            .fg(Color::Cyan)
            .add_modifier(Modifier::BOLD);
        let marker = if active { "› " } else { "  " };
        let number = if selected.is_some() {
            format!("{:>number_digits$}. ", index + 1)
        } else {
            String::new()
        };
        let prefix_width = UnicodeWidthStr::width(number.as_str()) + 2;
        let room = (content.width as usize).saturating_sub(prefix_width);
        let name_room = if *kind == ChoiceKind::ToolSteps {
            room
        } else {
            label_width.min(room)
        };
        let name = truncate_cells(&row.name, name_room);
        let name_cells = UnicodeWidthStr::width(name.as_str());
        let mut spans = vec![
            Span::styled(
                marker,
                if active {
                    active_style
                } else {
                    Style::default().fg(Color::Reset)
                },
            ),
            Span::styled(
                number,
                if active {
                    active_style
                } else {
                    Style::default().fg(Color::Reset)
                },
            ),
            Span::styled(
                name,
                if active {
                    active_style
                } else {
                    Style::default().fg(Color::Reset)
                },
            ),
        ];
        if content.width >= 55 && *kind != ChoiceKind::ToolSteps {
            let padding = label_width.saturating_sub(name_cells);
            spans.push(Span::raw(" ".repeat(padding)));
            if let Some(description) = row.description.as_deref() {
                spans.push(Span::styled(
                    truncate_cells(description, room.saturating_sub(label_width)),
                    if active {
                        active_style
                    } else {
                        Style::default().fg(MUTED)
                    },
                ));
            }
        }
        lines.push(Line::from(spans));
    }
    frame.render_widget(Paragraph::new(Text::from(lines)), content);
}

/// 详情继续使用全区查看层；帮助由 RenderPlan 在输入区上方预留空间。
fn render_panel(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    let Some(panel) = state.panel() else {
        return;
    };
    if matches!(panel, Panel::Pending) {
        return;
    }
    match panel {
        Panel::Commands { .. } | Panel::Choices { .. } => {}
        Panel::Help { .. } => {}
        Panel::Details {
            entry_id,
            title,
            body,
            scroll,
            return_to_pending,
        } => {
            let refreshable = entry_id == "status"
                || entry_id.starts_with("diff:")
                || state.snapshot().timeline.iter().any(|entry| {
                    entry.id == *entry_id && matches!(entry.kind, TimelineKind::Tool { .. })
                });
            render_details_panel(
                frame,
                area,
                title,
                body,
                *scroll,
                *return_to_pending,
                (
                    refreshable,
                    state.snapshot().notice.as_deref().filter(|_| refreshable),
                ),
            );
        }
        Panel::ChoiceDetail {
            kind,
            choice_id,
            scroll,
            ..
        } => {
            let choice = state
                .choices(*kind)
                .iter()
                .find(|choice| choice.id == *choice_id);
            let title = choice.map_or("候选已更新", |choice| choice.label.as_str());
            let body = choice
                .and_then(|choice| choice.detail.as_deref())
                .unwrap_or("没有更多说明");
            render_details_panel(frame, area, title, body, *scroll, false, (false, None));
        }
        Panel::Pending => unreachable!(),
        Panel::ModelSettings => {
            if let Some(view) = state.model_settings_view() {
                super::model_settings::render(frame, area, &view);
            }
        }
    }
}

/// 帮助与输入区共享窄窗边距规则，布局测量和绘制不能使用不同正文列宽。
fn help_inner_width(width: usize) -> usize {
    let inset = if width >= 8 { 4 } else { 0 };
    width.saturating_sub(inset).max(1)
}

/// 分组按显示列宽流入三、二或一列；刷新说明包含用量，入口与测量绘制保持一致。
fn help_body_lines(width: usize) -> Vec<Line<'static>> {
    let groups: [(&str, &[(&str, &str)]); 3] = [
        (
            "输入",
            &[
                ("/", "打开命令"),
                ("@", "引用文件"),
                ("$", "引用 Skill"),
                ("Tab", "补全或确认候选"),
                ("Ctrl+J", "换行（或 Shift+Enter）"),
                ("Ctrl+R", "搜索输入历史"),
            ],
        ),
        (
            "会话",
            &[
                ("Enter", "发送；工作中调整任务"),
                ("Tab", "工作中排队发送"),
                ("/resume", "恢复会话"),
                ("/plan create", "创建并规划目标"),
                ("/plan execute", "执行已定稿 Plan"),
                ("/plan status", "查看当前 Plan"),
                ("Esc", "返回；空草稿时取消任务"),
                ("F5", "刷新会话与用量"),
            ],
        ),
        (
            "浏览",
            &[
                ("滚轮 / PgUp / PgDn", "浏览正文；顶部加载更早记录"),
                ("Ctrl+Home / Ctrl+End", "已加载顶部 / 最新回复"),
                ("Esc", "清选区 / 回到底部 / 取消"),
                ("Ctrl+C / Ctrl+Insert", "有选区时复制原文"),
                ("Ctrl+O", "浏览工具步骤"),
                ("F2", "查看候选或问题详情"),
                ("↑ / ↓", "帮助或详情逐行滚动"),
                ("PgUp / PgDn", "帮助或详情按页滚动"),
                ("Home / End", "帮助或详情顶部 / 底部"),
            ],
        ),
    ];
    let width = help_inner_width(width);
    let mut body = vec![Line::default()];
    let mut first = 0;
    while first < groups.len() {
        let mut columns = Vec::new();
        let mut used = 0;
        for &(title, entries) in &groups[first..] {
            let column_width = help_group_width(title, entries).min(width);
            let gap = if columns.is_empty() { 0 } else { 4 };
            if used + gap + column_width > width {
                break;
            }
            columns.push((help_group_lines(title, entries, column_width), column_width));
            used += gap + column_width;
        }
        let height = columns
            .iter()
            .map(|(lines, _)| lines.len())
            .max()
            .unwrap_or(0);
        for row in 0..height {
            let mut line = Line::default();
            for (column, (lines, column_width)) in columns.iter().enumerate() {
                let content = lines.get(row).cloned().unwrap_or_default();
                let padding = column_width.saturating_sub(content.width());
                line.spans.extend(content.spans);
                if column + 1 < columns.len() {
                    line.spans.push(Span::raw(" ".repeat(padding + 4)));
                }
            }
            body.push(line);
        }
        body.push(Line::default());
        first += columns.len();
    }
    body.extend(help_group_lines(
        "",
        &[
            ("Ctrl+C", "清草稿 / 取消 / 连按退出"),
            ("Ctrl+D", "空草稿退出"),
        ],
        width,
    ));
    body
}

/// 列宽由真实按键和说明决定，中文标题按显示列计量，避免宽窗仍被任意上限截短。
fn help_group_width(title: &str, entries: &[(&str, &str)]) -> usize {
    let keys = entries
        .iter()
        .map(|(key, _)| key.width())
        .max()
        .unwrap_or(0);
    let descriptions = entries
        .iter()
        .map(|(_, text)| text.width())
        .max()
        .unwrap_or(0);
    title.width().max(keys + 2 + descriptions)
}

/// 说明续行对齐其文本列；极窄窗口将按键和说明分行，确保滚动后仍能读到完整操作。
fn help_group_lines(title: &str, entries: &[(&str, &str)], width: usize) -> Vec<Line<'static>> {
    let width = width.max(1);
    let mut lines = if title.is_empty() {
        Vec::new()
    } else {
        wrap_transcript_lines(
            vec![Line::from(Span::styled(
                title.to_owned(),
                Style::default().add_modifier(Modifier::BOLD),
            ))],
            width,
        )
    };
    let keys = entries
        .iter()
        .map(|(key, _)| key.width())
        .max()
        .unwrap_or(0);
    let stacked = width < keys + 2 + 8;
    let indent = if stacked {
        usize::from(width > 2) * 2
    } else {
        keys + 2
    };
    for &(key, description) in entries {
        if stacked {
            lines.extend(wrap_transcript_lines(
                vec![Line::from(Span::styled(key.to_owned(), Color::LightBlue))],
                width,
            ));
        }
        let wrapped = wrap_transcript_lines(
            vec![Line::raw(description.to_owned())],
            width.saturating_sub(indent).max(1),
        );
        for (row, mut content) in wrapped.into_iter().enumerate() {
            let mut spans = Vec::new();
            if !stacked && row == 0 {
                spans.push(Span::styled(key.to_owned(), Color::LightBlue));
                spans.push(Span::raw(" ".repeat(indent.saturating_sub(key.width()))));
            } else {
                spans.push(Span::raw(" ".repeat(indent)));
            }
            spans.append(&mut content.spans);
            lines.push(Line::from(spans));
        }
    }
    lines
}

/// 帮助高度由同一份折行结果测量，终端限制不足时再由 DetailScroll 提供浏览。
fn help_panel_content_height(width: usize) -> u16 {
    u16::try_from(help_body_lines(width).len().saturating_add(1)).unwrap_or(u16::MAX)
}

/// 标题与分组保留一行呼吸空间；短窗只滚动正文，输入及返回提示继续留在原区域。
fn render_help_panel(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    frame.render_widget(Clear, area);
    let inset = u16::from(area.width >= 8) * 2;
    let inner = Rect::new(
        area.x + inset,
        area.y,
        area.width.saturating_sub(inset * 2),
        area.height,
    );
    if inner.width == 0 || inner.height == 0 {
        return;
    }
    let scroll = match state.panel() {
        Some(Panel::Help { scroll }) => *scroll,
        _ => return,
    };
    let lines = help_body_lines(area.width as usize);
    let header_height = u16::from(inner.height >= 2);
    if header_height > 0 {
        frame.render_widget(
            Paragraph::new("Ja 快捷键").style(Style::default().add_modifier(Modifier::BOLD)),
            Rect::new(inner.x, inner.y, inner.width, 1),
        );
    }
    let content = inner.height.saturating_sub(header_height);
    if content == 0 {
        return;
    }
    let maximum = lines.len().saturating_sub(content as usize);
    let start = match scroll {
        DetailScroll::FromTop(offset) => offset.min(maximum),
        DetailScroll::FromBottom(offset) => maximum.saturating_sub(offset),
    };
    frame.render_widget(
        Paragraph::new(Text::from(
            lines
                .into_iter()
                .skip(start)
                .take(content as usize)
                .collect::<Vec<_>>(),
        )),
        Rect::new(inner.x, inner.y + header_height, inner.width, content),
    );
}

/// Codex 审批列表的标题/问题/选项结构改为无边框内联区，保留 Ja 的回答 CAS 语义。
fn render_pending_inline(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    let Some((prompt, question_index, selected, multiple, using_free_text)) = state.prompt_view()
    else {
        return;
    };
    if area.width == 0 || area.height == 0 {
        return;
    }
    let (title, heading, choices, allow_skip, kind) = match prompt {
        PendingPrompt::ToolApproval {
            prompt, choices, ..
        } => (
            "需要审批".to_owned(),
            prompt.as_str(),
            choices.as_slice(),
            false,
            InteractionQuestionKind::Single,
        ),
        PendingPrompt::Clarification { questions, .. } => {
            let Some(question) = questions.get(question_index) else {
                return;
            };
            (
                if questions.len() > 1 {
                    format!("需要补充信息  {}/{}", question_index + 1, questions.len())
                } else {
                    "需要补充信息".to_owned()
                },
                question.prompt.as_str(),
                question.options.as_slice(),
                question.allow_skip,
                question.kind,
            )
        }
    };
    let inset = u16::from(area.width >= 4) * 2;
    let content = Rect::new(
        area.x + inset,
        area.y,
        area.width.saturating_sub(inset),
        area.height,
    );
    let mut lines = vec![Line::from(Span::styled(
        truncate_cells(&title, content.width as usize),
        Style::default()
            .fg(Color::Reset)
            .add_modifier(Modifier::BOLD),
    ))];
    let wrapped_question = wrap_transcript_lines(
        vec![Line::raw(heading.to_owned())],
        content.width.max(1) as usize,
    );
    let question_budget = (content.height as usize).saturating_sub(2).min(2);
    for (index, line) in wrapped_question.iter().take(question_budget).enumerate() {
        let text = line.to_string();
        let clipped = if index + 1 == question_budget && wrapped_question.len() > question_budget {
            format!(
                "{}…",
                truncate_cells(&text, content.width.saturating_sub(1) as usize)
            )
        } else {
            truncate_cells(&text, content.width as usize)
        };
        lines.push(Line::from(clipped));
    }
    let option_budget = (content.height as usize).saturating_sub(lines.len());
    if option_budget > 0 {
        if kind == InteractionQuestionKind::Text || using_free_text {
            lines.push(Line::from(Span::styled(
                "在下方输入回答",
                Style::default().fg(MUTED),
            )));
        } else {
            let total =
                choices.len() + usize::from(allow_skip && kind == InteractionQuestionKind::Single);
            let start = selected
                .saturating_sub(option_budget / 2)
                .min(total.saturating_sub(option_budget));
            for index in start..total.min(start + option_budget) {
                let active = index == selected;
                let label = if index == choices.len() {
                    "跳过此题".to_owned()
                } else {
                    let choice = &choices[index];
                    let prefix = if kind == InteractionQuestionKind::Multiple {
                        if multiple.contains(&choice.id) {
                            "[x] "
                        } else {
                            "[ ] "
                        }
                    } else {
                        ""
                    };
                    format!("{prefix}{}", choice_label(choice))
                };
                let label = format!("{}. {label}", index + 1);
                let marker = if active { "› " } else { "  " };
                lines.push(Line::from(vec![
                    Span::styled(marker, Style::default().fg(Color::Reset)),
                    Span::styled(
                        truncate_cells(&label, content.width.saturating_sub(2) as usize),
                        if active {
                            Style::default()
                                .fg(Color::Reset)
                                .add_modifier(Modifier::BOLD)
                        } else {
                            Style::default().fg(Color::Reset)
                        },
                    ),
                ]));
            }
            if total == 0 {
                lines.push(Line::from(Span::styled(
                    "在下方输入回答",
                    Style::default().fg(MUTED),
                )));
            }
        }
    }
    frame.render_widget(Paragraph::new(Text::from(lines)), content);
}

/// 保持真实模型 ID 为主标签，provider 只作为次要说明。
fn choice_label(choice: &UiChoice) -> String {
    choice
        .detail
        .as_ref()
        .map(|detail| format!("{}  ·  {detail}", choice.label))
        .unwrap_or_else(|| choice.label.clone())
}

/// 空列表指出当前项目没有可选会话，避免用户误以为列表仍在加载或搜索失效。
fn empty_choice_label(kind: ChoiceKind) -> &'static str {
    match kind {
        ChoiceKind::Models => "暂无可用模型，请先配置供应商",
        ChoiceKind::Reasoning => "当前模型没有可选推理等级",
        ChoiceKind::Permissions => "权限选项暂不可用",
        ChoiceKind::Threads => "当前项目暂无可恢复会话",
        ChoiceKind::InputHistory => "暂无匹配的输入历史",
        ChoiceKind::ForkReplies => "当前会话还没有可分叉的已结算回复",
        ChoiceKind::Files => "没有匹配的文件",
        ChoiceKind::Skills => "没有匹配的技能",
        ChoiceKind::Attachments => "暂无附件",
        ChoiceKind::ToolSteps => "当前会话尚无工具步骤",
    }
}

/// 只读详情将重读能力与诊断一同呈现，失败保留正文及固定返回操作，避免错误被整屏遮住。
fn render_details_panel(
    frame: &mut Frame<'_>,
    area: Rect,
    title: &str,
    body: &str,
    scroll: DetailScroll,
    return_to_pending: bool,
    feedback: (bool, Option<&str>),
) {
    let (refreshable, notice) = feedback;
    frame.render_widget(Clear, area);
    let inset = u16::from(area.width >= 4) * 2;
    let inner = Rect::new(
        area.x + inset,
        area.y,
        area.width.saturating_sub(inset * 2),
        area.height,
    );
    if inner.width == 0 || inner.height == 0 {
        return;
    }
    frame.render_widget(
        Paragraph::new(truncate_cells(title, inner.width as usize)).style(
            Style::default()
                .fg(Color::Reset)
                .add_modifier(Modifier::BOLD),
        ),
        Rect::new(inner.x, inner.y, inner.width, 1),
    );
    if inner.height >= 3 {
        frame.render_widget(
            Paragraph::new("─".repeat(inner.width as usize))
                .style(Style::default().fg(Color::DarkGray)),
            Rect::new(inner.x, inner.y + 1, inner.width, 1),
        );
    }
    let show_notice = notice.is_some() && inner.height >= 5;
    let content_height = inner.height.saturating_sub(3 + u16::from(show_notice));
    let source = body
        .split('\n')
        .map(|line| Line::raw(line.to_owned()))
        .collect();
    let lines = wrap_transcript_lines(source, inner.width as usize);
    let total = lines.len();
    let maximum = total.saturating_sub(content_height as usize);
    let start = match scroll {
        DetailScroll::FromTop(offset) => offset.min(maximum),
        DetailScroll::FromBottom(offset) => maximum.saturating_sub(offset),
    };
    if content_height > 0 {
        frame.render_widget(
            Paragraph::new(Text::from(
                lines
                    .into_iter()
                    .skip(start)
                    .take(content_height as usize)
                    .collect::<Vec<_>>(),
            )),
            Rect::new(inner.x, inner.y + 2, inner.width, content_height),
        );
    }
    let end = start.saturating_add(content_height as usize).min(total);
    let return_label = if return_to_pending {
        "Esc 返回问题"
    } else {
        "Esc 返回"
    };
    let refresh_hint = if refreshable { " · F5 重读" } else { "" };
    let footer = if inner.width < 50 {
        format!("↑↓ 滚动{refresh_hint} · {return_label}  {end}/{total}")
    } else {
        format!("↑↓ 滚动 · PgUp/PgDn 翻页 · Home/End{refresh_hint} · {return_label}  {end}/{total}")
    };
    if show_notice && let Some(notice) = notice {
        frame.render_widget(
            Paragraph::new(truncate_cells(notice, inner.width as usize))
                .style(Style::default().fg(Color::Red)),
            Rect::new(inner.x, inner.y + inner.height - 2, inner.width, 1),
        );
    }
    if inner.height >= 2 {
        frame.render_widget(
            Paragraph::new(truncate_cells(&footer, inner.width as usize))
                .style(Style::default().fg(MUTED)),
            Rect::new(inner.x, inner.y + inner.height - 1, inner.width, 1),
        );
    }
}

/// 终端列宽截断保留完整字素，所有候选和详情标题都以同一省略规则呈现。
fn truncate_cells(value: &str, width: usize) -> String {
    if width == 0 {
        return String::new();
    }
    if UnicodeWidthStr::width(value) <= width {
        return value.to_owned();
    }
    let mut text = String::new();
    let mut cells = 0usize;
    for grapheme in value.graphemes(true) {
        let needed = UnicodeWidthStr::width(grapheme).max(1);
        if cells.saturating_add(needed) >= width {
            break;
        }
        text.push_str(grapheme);
        cells += needed;
    }
    text.push('…');
    text
}
