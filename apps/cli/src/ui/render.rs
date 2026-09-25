// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::io;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use ratatui::backend::Backend;
use ratatui::layout::{Constraint, Direction, Layout, Position, Rect};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span, Text};
use ratatui::widgets::{Clear, Paragraph, Widget};
use ratatui::{Frame, Terminal};

use super::markdown::render_markdown;
use super::model::{
    InteractionQuestionKind, PendingPrompt, TimelineEntry, TimelineKind, TimelineStatus, TurnState,
    UiChoice,
};
use super::state::{
    ChoiceKind, DetailScroll, Panel, UiState, command_description, command_label, matching_commands,
};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

const MUTED: Color = Color::Rgb(136, 136, 150);
const MAX_SELECTOR_ITEMS: usize = 8;
pub(super) const WORKING_INDICATOR_INTERVAL: Duration = Duration::from_millis(240);
const WORKING_INLINE_HINT_MIN_WIDTH: usize = 32;

/// 选择行只保留名称和简述；具体模型、会话与权限身份仍由 UiState 的候选列表持有。
struct SelectionRow {
    name: String,
    description: Option<String>,
    tag: Option<String>,
}

/// 仿照 Pi 编辑器与 Codex 命令列表，把候选接在输入框下方；对话保持原终端滚动历史。
pub fn render(frame: &mut Frame<'_>, state: &UiState) {
    let area = frame.area();
    let Some(plan) = render_plan(area, state) else {
        return;
    };
    let regions = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(plan.leading_height),
            Constraint::Length(plan.history_height),
            Constraint::Length(plan.welcome_gap_height),
            Constraint::Length(plan.first_height),
            Constraint::Length(plan.second_height),
            Constraint::Length(plan.footer_height),
        ])
        .split(area);
    if plan.history_height > 0 {
        render_history(frame, regions[1], &plan.history);
    }
    if plan.pending {
        if plan.panel_height > 0 {
            render_pending_inline(frame, regions[3], state);
        }
        if plan.show_composer {
            render_composer(
                frame,
                regions[4],
                state,
                plan.width,
                &plan.composer_lines,
                plan.visible_composer_lines,
            );
        }
    } else if plan.full_choice {
        render_choice_view(frame, regions[3], state);
    } else {
        render_composer(
            frame,
            regions[3],
            state,
            plan.width,
            &plan.composer_lines,
            plan.visible_composer_lines,
        );
        if plan.panel_height > 0 {
            render_selector(frame, regions[4], state);
        }
    }
    if plan.footer_height > 0 {
        render_footer(frame, regions[5], state);
    }
    render_panel(frame, area, state);
}

struct RenderPlan {
    width: usize,
    composer_lines: Vec<String>,
    visible_composer_lines: usize,
    pending: bool,
    full_choice: bool,
    show_composer: bool,
    composer_height: u16,
    panel_height: u16,
    footer_height: u16,
    history: Vec<Line<'static>>,
    history_height: u16,
    leading_height: u16,
    welcome_gap_height: u16,
    first_height: u16,
    second_height: u16,
}

/** 共享渲染与视口测量的同一份几何预算，避免动态高度偏离真实控件布局。 */
fn render_plan(area: Rect, state: &UiState) -> Option<RenderPlan> {
    if area.width == 0 || area.height == 0 {
        return None;
    }
    let width = area.width.max(4) as usize;
    let composer_lines = state.composer().visual_lines(width);
    let max_composer_lines = (area.height as usize / 3).max(1);
    let visible_composer_lines = composer_lines.len().min(max_composer_lines);
    let pending = matches!(state.panel(), Some(Panel::Pending));
    let full_choice = matches!(
        state.panel(),
        Some(Panel::Choices { kind, .. }) if !matches!(kind, ChoiceKind::Files | ChoiceKind::Skills)
    );
    let show_composer = (!pending && !full_choice) || (pending && pending_editor_visible(state));
    let composer_height = if show_composer {
        (visible_composer_lines as u16 + 1).min(area.height)
    } else {
        0
    };
    let footer_height = u16::from(area.height >= 2);
    let panel_budget = area.height.saturating_sub(composer_height + footer_height);
    let mut panel_height = if pending {
        panel_budget.min(8)
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
    let card_height = if width < 20 { 1 } else { 7 };
    let panel_floor = if panel_height == 0 {
        0
    } else if pending || full_choice {
        5
    } else {
        4
    };
    let show_welcome = shows_welcome(state) && interaction_room >= card_height + panel_floor;
    if show_welcome {
        panel_height = panel_height.min(interaction_room.saturating_sub(card_height));
    }
    let history_budget = area
        .height
        .saturating_sub(composer_height + panel_height + footer_height);
    let show_welcome_tip = show_welcome && width >= 40 && history_budget >= card_height + 3;
    let history = history_lines(state, width, show_welcome, show_welcome_tip);
    let history_height = u16::try_from(history.len())
        .unwrap_or(u16::MAX)
        .min(history_budget);
    let spacer_height = history_budget.saturating_sub(history_height);
    let (leading_height, welcome_gap_height) = if show_welcome {
        (0, spacer_height)
    } else {
        (spacer_height, 0)
    };
    let first_height = if pending || full_choice {
        panel_height
    } else {
        composer_height
    };
    let second_height = if pending || full_choice {
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
        show_composer,
        composer_height,
        panel_height,
        footer_height,
        history,
        history_height,
        leading_height,
        welcome_gap_height,
        first_height,
        second_height,
    })
}

/** 只占用实际内容所需的行数，并给已提交回复与编辑区留一行间距；长草稿和详情保留完整空间。 */
pub fn desired_viewport_height(state: &UiState, width: u16, max_height: u16) -> u16 {
    let max_height = max_height.max(1);
    if state
        .composer()
        .visual_lines(usize::from(width.max(4)))
        .len()
        > 1
        || matches!(
            state.panel(),
            Some(Panel::Help | Panel::Details { .. } | Panel::ChoiceDetail { .. })
        )
    {
        return max_height;
    }
    let area = Rect::new(0, 0, width.max(1), max_height);
    let Some(plan) = render_plan(area, state) else {
        return 1;
    };
    let content_height = plan
        .history_height
        .saturating_add(plan.composer_height)
        .saturating_add(plan.panel_height)
        .saturating_add(plan.footer_height)
        .max(1);
    let separates_scrollback_from_composer = plan.history_height == 0
        && state.snapshot().turn_state != TurnState::Working
        && state.panel().is_none()
        && !state.snapshot().timeline.is_empty()
        && content_height < max_height;
    content_height
        .saturating_add(u16::from(separates_scrollback_from_composer))
        .min(max_height)
}

/// 首屏卡片留在终端历史起点，其余活动消息贴近底部输入区；空白只能位于二者之间。
fn shows_welcome(state: &UiState) -> bool {
    state.snapshot().thread_id.is_some()
        && state.snapshot().timeline.is_empty()
        && !state.snapshot().has_older_history
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

/// 只把稳定段插入 Inline viewport 之前；Paragraph 按当前列宽求真实行数，resize 不会重放。
pub fn insert_scrollback<B: Backend>(
    terminal: &mut Terminal<B>,
    entries: &[TimelineEntry],
) -> io::Result<()> {
    if entries.is_empty() {
        return Ok(());
    }
    let area = terminal.size()?;
    if area.width == 0 {
        return Ok(());
    }
    let lines = wrap_scrollback_lines(scrollback_lines(entries), area.width as usize);
    for chunk in lines.chunks(4096) {
        let paragraph = Paragraph::new(Text::from(chunk.to_vec()));
        terminal.insert_before(chunk.len() as u16, |buffer| {
            paragraph.render(buffer.area, buffer)
        })?;
    }
    Ok(())
}

/// 将一个批次渲染成完整稳定行，批次间空行只由此处加入一次。
fn scrollback_lines(entries: &[TimelineEntry]) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    for (index, entry) in entries.iter().enumerate() {
        if index > 0 {
            lines.push(Line::default());
        }
        append_entry_lines(&mut lines, entry);
    }
    lines
}

/// 以 Ratatui 的实际列宽将每个样式字素手工折行，使 insert_before 的高度精确且稳定。
fn wrap_scrollback_lines(lines: Vec<Line<'static>>, width: usize) -> Vec<Line<'static>> {
    let width = width.max(1);
    let mut wrapped = Vec::new();
    for line in lines {
        let mut current = Line::default();
        let mut cells = 0usize;
        for span in line.spans {
            let mut segment = String::new();
            for grapheme in span.content.graphemes(true) {
                if grapheme == "\n" {
                    push_wrapped_span(&mut current, &mut segment, span.style);
                    wrapped.push(current);
                    current = Line::default();
                    cells = 0;
                    continue;
                }
                let cell_width = UnicodeWidthStr::width(grapheme).max(1);
                if cells > 0 && cells.saturating_add(cell_width) > width {
                    push_wrapped_span(&mut current, &mut segment, span.style);
                    wrapped.push(current);
                    current = Line::default();
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

/// 欢迎卡只展示权威模型、目录和权限；小窗口退化为短标签而不折断边框。
fn welcome_card(state: &UiState, width: usize) -> Vec<Line<'static>> {
    if width < 20 {
        return vec![Line::from("Ja")];
    }
    let card_width = width.min(50);
    let model = state
        .snapshot()
        .model_identifier
        .as_deref()
        .unwrap_or("未配置");
    let model = if let Some(level) = state.snapshot().reasoning_label.as_deref() {
        format!("{model} {level}")
    } else {
        model.to_owned()
    };
    let directory = state
        .snapshot()
        .workspace_path
        .as_deref()
        .or(state.snapshot().project_label.as_deref())
        .unwrap_or("当前目录");
    let directory = abbreviate_home(directory);
    let permission = state
        .snapshot()
        .permission_label
        .as_deref()
        .unwrap_or("默认");
    let border = Style::default().fg(Color::DarkGray);
    let mut lines = vec![Line::from(Span::styled(
        format!("╭{}╮", "─".repeat(card_width - 2)),
        border,
    ))];
    lines.push(welcome_title_row(card_width));
    lines.push(welcome_blank_row(card_width));
    lines.push(welcome_model_row(&model, card_width));
    lines.push(welcome_field_row("目录：       ", &directory, card_width));
    lines.push(welcome_field_row("权限：       ", permission, card_width));
    lines.push(Line::from(Span::styled(
        format!("╰{}╯", "─".repeat(card_width - 2)),
        border,
    )));
    lines
}

/// 模型切换命令只在卡片列宽足够时靠右显示，模拟 Codex 的蓝色可发现入口。
fn welcome_model_row(model: &str, width: usize) -> Line<'static> {
    let label = "模型：       ";
    let link = "  /model 切换";
    let room = width.saturating_sub(4);
    let used = UnicodeWidthStr::width(label)
        + UnicodeWidthStr::width(model)
        + UnicodeWidthStr::width(link);
    if used > room {
        return welcome_field_row(label, model, width);
    }
    Line::from(vec![
        Span::styled("│ ", Style::default().fg(Color::DarkGray)),
        Span::styled(label, Style::default().fg(MUTED)),
        Span::styled(model.to_owned(), Style::default().fg(Color::Reset)),
        Span::raw(" ".repeat(room - used)),
        Span::styled(link, Style::default().fg(Color::Cyan)),
        Span::styled(" │", Style::default().fg(Color::DarkGray)),
    ])
}

/// 标题与版本分开着色，保持 Codex 卡片里品牌先于次要版本信息的视觉层级。
fn welcome_title_row(width: usize) -> Line<'static> {
    let title = ">_ Ja";
    let version = format!(" (v{})", env!("CARGO_PKG_VERSION"));
    let room = width.saturating_sub(4);
    let used = UnicodeWidthStr::width(title) + UnicodeWidthStr::width(version.as_str());
    Line::from(vec![
        Span::styled("│ ", Style::default().fg(Color::DarkGray)),
        Span::styled(
            title,
            Style::default()
                .fg(Color::Reset)
                .add_modifier(Modifier::BOLD),
        ),
        Span::styled(version, Style::default().fg(MUTED)),
        Span::raw(" ".repeat(room.saturating_sub(used))),
        Span::styled(" │", Style::default().fg(Color::DarkGray)),
    ])
}

/// 卡片固定标签列按终端显示宽度补齐，长目录只截断值而不移动后续行的起点。
fn welcome_field_row(label: &str, value: &str, width: usize) -> Line<'static> {
    let room = width.saturating_sub(4);
    let label_width = UnicodeWidthStr::width(label);
    let value = truncate_cells(value, room.saturating_sub(label_width));
    let used = label_width + UnicodeWidthStr::width(value.as_str());
    Line::from(vec![
        Span::styled("│ ", Style::default().fg(Color::DarkGray)),
        Span::styled(label.to_owned(), Style::default().fg(MUTED)),
        Span::styled(value, Style::default().fg(Color::Reset)),
        Span::raw(" ".repeat(room.saturating_sub(used))),
        Span::styled(" │", Style::default().fg(Color::DarkGray)),
    ])
}

/// 仅在当前目录确实位于宿主 Home 内时缩写为 ~，避免把别的用户目录误标成自己的。
fn abbreviate_home(path: &str) -> String {
    let path = if let Some(unc) = path.strip_prefix("\\\\?\\UNC\\") {
        format!("\\\\{unc}")
    } else {
        path.strip_prefix("\\\\?\\").unwrap_or(path).to_owned()
    };
    let home = std::env::var_os("USERPROFILE")
        .or_else(|| std::env::var_os("HOME"))
        .map(|value| value.to_string_lossy().into_owned());
    let Some(home) = home else {
        return path;
    };
    let home = home.trim_end_matches(['\\', '/']);
    let Some(prefix) = path.get(..home.len()) else {
        return path;
    };
    let matches = if cfg!(windows) {
        prefix.eq_ignore_ascii_case(home)
    } else {
        prefix == home
    };
    if !matches {
        return path.to_owned();
    }
    let suffix = &path[home.len()..];
    if suffix.is_empty() || suffix.starts_with(['\\', '/']) {
        format!("~{suffix}")
    } else {
        path
    }
}

/// 空行只用于卡片标题和参数之间的呼吸间距，不引入额外框架或装饰。
fn welcome_blank_row(width: usize) -> Line<'static> {
    Line::from(vec![
        Span::styled("│ ", Style::default().fg(Color::DarkGray)),
        Span::raw(" ".repeat(width.saturating_sub(4))),
        Span::styled(" │", Style::default().fg(Color::DarkGray)),
    ])
}

/// 初始提示只占用有余量的首屏；工作提示紧随最近回复，宽度允许时把 Esc 操作也放在同一行。
fn history_lines(
    state: &UiState,
    width: usize,
    show_welcome: bool,
    show_welcome_tip: bool,
) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    if show_welcome {
        let mut welcome = welcome_card(state, width);
        if show_welcome_tip {
            welcome.push(Line::default());
            welcome.push(Line::default());
            welcome.push(Line::from(vec![
                Span::raw("  "),
                Span::styled(
                    "提示：",
                    Style::default()
                        .fg(Color::Reset)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::styled("/ 命令 · @ 引用文件", Style::default().fg(MUTED)),
            ]));
        }
        return welcome;
    }
    if state.snapshot().has_older_history {
        lines.push(Line::from(Span::styled(
            "↑  Ctrl+↑ 加载更早记录",
            Style::default().fg(MUTED),
        )));
    }
    let mut first_entry = true;
    for entry in state
        .visible_entries()
        .rev()
        .take(18)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
    {
        if !first_entry {
            lines.push(Line::default());
        }
        append_entry_lines(&mut lines, entry);
        first_entry = false;
    }
    if state.snapshot().turn_state == TurnState::Working
        && state.snapshot().pending_prompt.is_none()
    {
        lines.push(working_indicator_line(
            state.composer().is_empty() && width >= WORKING_INLINE_HINT_MIN_WIDTH,
        ));
    }
    if !lines.is_empty() {
        lines.push(Line::default());
    }
    wrap_scrollback_lines(lines, width)
}

/// 固定宽度的三个点依次提亮；窄窗或已有草稿时把取消提示留给底栏。
fn working_indicator_line(show_cancel_hint: bool) -> Line<'static> {
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
    if show_cancel_hint {
        spans.push(Span::styled(" · Esc 中断", Style::default().fg(MUTED)));
    }
    Line::from(spans)
}

/// 历史比当前屏幕长时只显示活动尾部；稳定正文仍由 insert_before 保存到终端滚动区。
fn render_history(frame: &mut Frame<'_>, area: Rect, lines: &[Line<'static>]) {
    let start = lines.len().saturating_sub(area.height as usize);
    frame.render_widget(Paragraph::new(Text::from(lines[start..].to_vec())), area);
}

/// 按 Codex 的时间线语法投影：用户 ›、过程和回答 •、工具命令及有界树形输出。
fn append_entry_lines(lines: &mut Vec<Line<'static>>, entry: &TimelineEntry) {
    match &entry.kind {
        TimelineKind::User => append_plain(lines, &entry.text, "› ", Color::Reset),
        TimelineKind::Assistant | TimelineKind::FinalAnswer | TimelineKind::Commentary => {
            let mut rendered = render_markdown(&entry.text).lines;
            if let Some(first) = rendered.first_mut() {
                first.spans.insert(0, Span::raw("• "));
            } else {
                rendered.push(Line::raw("•"));
            }
            for line in rendered.iter_mut().skip(1) {
                line.spans.insert(0, Span::raw("  "));
            }
            lines.extend(rendered);
            if entry.status == Some(TimelineStatus::Failed)
                && let Some(detail) = entry.detail.as_deref()
            {
                append_plain(lines, &bounded_lines(detail, 3), "   ", Color::Reset);
            }
        }
        TimelineKind::Tool { action, target } => {
            lines.push(Line::from(vec![
                Span::raw("• "),
                Span::styled(
                    action.clone(),
                    Style::default()
                        .fg(Color::Reset)
                        .add_modifier(Modifier::BOLD),
                ),
                Span::raw(" "),
                Span::styled(target.clone(), Style::default().fg(Color::Reset)),
            ]));
            if let Some(detail) = entry.detail.as_deref() {
                let limit = if entry.status == Some(TimelineStatus::Failed) {
                    6
                } else {
                    4
                };
                for (index, text) in detail.lines().take(limit).enumerate() {
                    let prefix = if index == 0 { "  └ " } else { "    " };
                    lines.push(Line::from(Span::styled(
                        format!("{prefix}{}", truncate_detail_line(text, 512)),
                        Style::default().fg(MUTED),
                    )));
                }
                if detail.lines().count() > limit {
                    lines.push(Line::from(Span::styled(
                        "    …输出已截断，Ctrl+O 查看详情",
                        Style::default().fg(MUTED),
                    )));
                }
            }
        }
    }
}

/// 将普通用户或 Commentary 正文拆为行，避免显示重复的角色标题。
fn append_plain(lines: &mut Vec<Line<'static>>, value: &str, prefix: &str, color: Color) {
    for (index, line) in value.lines().enumerate() {
        let mut spans = Vec::new();
        if index == 0 && !prefix.is_empty() {
            spans.push(Span::styled(
                prefix.to_owned(),
                Style::default()
                    .fg(Color::Reset)
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

/// Codex 式 Composer 不画边框；硬件光标保留字素位置供 Windows IME 定位。
fn render_composer(
    frame: &mut Frame<'_>,
    area: Rect,
    state: &UiState,
    width: usize,
    lines: &[String],
    visible_lines: usize,
) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    let input_area = Rect::new(area.x, area.y, area.width, area.height.saturating_sub(1));
    let (cursor_row, cursor_column) = state.composer().visual_cursor(width);
    let first_visible = cursor_row.saturating_add(1).saturating_sub(visible_lines);
    let visible = if state.composer().is_empty() && state.panel().is_none() {
        vec![Line::from(vec![
            Span::styled(
                "› ",
                Style::default()
                    .fg(Color::Reset)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::styled(
                truncate_cells("让 Ja 完成任何任务", area.width.saturating_sub(2) as usize),
                Style::default().fg(MUTED),
            ),
        ])]
    } else {
        lines
            .iter()
            .skip(first_visible)
            .take(input_area.height as usize)
            .map(|line| Line::from(line.clone()))
            .collect::<Vec<_>>()
    };
    frame.render_widget(Paragraph::new(Text::from(visible)), input_area);
    let row = cursor_row
        .saturating_sub(first_visible)
        .min(input_area.height.saturating_sub(1) as usize);
    let column = cursor_column.min(input_area.width.saturating_sub(1) as usize);
    if !matches!(
        state.panel(),
        Some(Panel::Help | Panel::Details { .. } | Panel::ChoiceDetail { .. })
    ) {
        frame.set_cursor_position(Position::new(
            input_area.x + column as u16,
            input_area.y + row as u16,
        ));
    }
}

/// 底栏按当前面板给出下一步按键，恢复选择明确显示导航、确认和返回动作。
fn render_footer(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    if area.width == 0 || area.height == 0 {
        return;
    }
    let snapshot = state.snapshot();
    if state.prompt_submission_pending() && matches!(state.panel(), Some(Panel::Pending)) {
        frame.render_widget(
            Paragraph::new(truncate_cells(
                "  正在提交回答，内容已暂存 · Esc 收起",
                area.width as usize,
            ))
            .style(Style::default().fg(MUTED)),
            area,
        );
        return;
    }
    if let Some(notice) = snapshot.notice.as_deref() {
        frame.render_widget(
            Paragraph::new(truncate_cells(notice, area.width as usize)).style(
                Style::default()
                    .fg(Color::Reset)
                    .add_modifier(Modifier::BOLD),
            ),
            area,
        );
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
        Some(Panel::Choices { .. }) => Some("  Enter 确认或 Esc 返回"),
        Some(Panel::Pending) => Some(pending_footer_hint(state, area.width)),
        Some(Panel::Help | Panel::Details { .. } | Panel::ChoiceDetail { .. }) => {
            Some("  Esc 返回")
        }
        None => None,
    };
    if let Some(hint) = hint {
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
    let (left, right) = if state.has_pending_submission() {
        ("  正在提交，草稿已暂存".to_owned(), Some("请稍候"))
    } else if attaching {
        ("  附件路径 · 含空格请加引号".to_owned(), Some("Enter 导入"))
    } else {
        let model = snapshot.model_identifier.as_deref().unwrap_or("未配置模型");
        let model = if let Some(level) = snapshot.reasoning_label.as_deref() {
            format!("{model} {level}")
        } else {
            model.to_owned()
        };
        let directory = snapshot
            .workspace_path
            .as_deref()
            .or(snapshot.project_label.as_deref())
            .unwrap_or("当前目录");
        let directory = truncate_cells(&abbreviate_home(directory), 48);
        let left = format!("  {model} · {directory}");
        let right = match snapshot.turn_state {
            TurnState::Working
                if state.composer().is_empty()
                    && (area.width as usize) < WORKING_INLINE_HINT_MIN_WIDTH =>
            {
                Some("Esc 中断")
            }
            TurnState::Working if state.composer().is_empty() => None,
            TurnState::Working => Some("Enter 调整 · Tab 排队"),
            TurnState::Failed if snapshot.continuation_available && state.composer().is_empty() => {
                Some("Enter 继续回复")
            }
            _ => None,
        };
        (left, right)
    };
    let width = area.width as usize;
    let right = right.map(|value| truncate_cells(value, width));
    let right_width = right.as_deref().map(UnicodeWidthStr::width).unwrap_or(0);
    let left_width = width.saturating_sub(right_width + usize::from(right_width > 0) * 2);
    if left_width > 0 {
        frame.render_widget(
            Paragraph::new(truncate_cells(&left, left_width))
                .style(Style::default().fg(Color::Reset)),
            Rect::new(area.x, area.y, left_width as u16, 1),
        );
    }
    if let Some(right) = right {
        frame.render_widget(
            Paragraph::new(right).style(
                Style::default()
                    .fg(Color::Reset)
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

/// 候选行共享名称/描述列；会话空态区分项目无历史与当前搜索无匹配项。
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
                    name: if *kind == ChoiceKind::Threads && !state.thread_query().is_empty() {
                        "当前项目没有匹配的会话".to_owned()
                    } else {
                        empty_choice_label(*kind).to_owned()
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

/// 候选作为输入框下方的普通行参与布局，不清屏、不画浮窗，缩放后仍能看见当前项。
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
        ChoiceKind::InputHistory => (
            "搜索输入历史".to_owned(),
            "搜索输入 · PgDn 更多 · 列表保留最近 64 条",
        ),
        ChoiceKind::Attachments => ("附件".to_owned(), "Enter 预览，Del 移除"),
        ChoiceKind::Files => ("引用文件".to_owned(), "选择工作区文件"),
        ChoiceKind::Skills => ("引用技能".to_owned(), "选择可用技能"),
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
        let name_room = label_width.min(room);
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
        if content.width >= 55 {
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

/// 审批和长文本暂用独立查看层；常见选择操作由输入框下方的行列表承载。
fn render_panel(frame: &mut Frame<'_>, area: Rect, state: &UiState) {
    let Some(panel) = state.panel() else {
        return;
    };
    if matches!(panel, Panel::Pending) {
        return;
    }
    match panel {
        Panel::Commands { .. } | Panel::Choices { .. } => {}
        Panel::Help => render_details_panel(
            frame,
            area,
            "Ja 快捷键",
            "Enter 发送或确认，Shift+Enter / Ctrl+J 换行\n/ 打开命令，Tab 补全，@ 引用文件\nEsc 关闭面板，Ctrl+R 搜索输入历史，/resume 恢复会话\nCtrl+↑ 加载更早记录，Ctrl+O 查看最近步骤\n详情用 ↑↓ / PgUp/PgDn 浏览；F2 查看完整候选说明或审批问题\nCtrl+C 清草稿 / 取消 / 退出，Ctrl+D 空草稿退出",
            DetailScroll::FromTop(0),
            false,
        ),
        Panel::Details {
            entry_id: _,
            title,
            body,
            scroll,
            return_to_pending,
        } => {
            render_details_panel(frame, area, title, body, *scroll, *return_to_pending);
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
            render_details_panel(frame, area, title, body, *scroll, false);
        }
        Panel::Pending => unreachable!(),
    }
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
    let wrapped_question = wrap_scrollback_lines(
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
        ChoiceKind::Files => "没有匹配的文件",
        ChoiceKind::Skills => "没有匹配的技能",
        ChoiceKind::Attachments => "暂无附件",
    }
}

/// 只读查看器沿用 Codex 的无侧框正文：标题、细分隔线、内容和固定操作行。
fn render_details_panel(
    frame: &mut Frame<'_>,
    area: Rect,
    title: &str,
    body: &str,
    scroll: DetailScroll,
    return_to_pending: bool,
) {
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
    let content_height = inner.height.saturating_sub(3);
    let source = body
        .split('\n')
        .map(|line| Line::raw(line.to_owned()))
        .collect();
    let lines = wrap_scrollback_lines(source, inner.width as usize);
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
    let footer = if inner.width < 50 {
        format!("↑↓ 滚动 · {return_label}  {end}/{total}")
    } else {
        format!("↑↓ 滚动 · PgUp/PgDn 翻页 · Home/End · {return_label}  {end}/{total}")
    };
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
