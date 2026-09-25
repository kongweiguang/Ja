// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use pulldown_cmark::{Event, HeadingLevel, Parser, Tag, TagEnd};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span, Text};

/// 将 Markdown 转为 Ratatui cell 文本，避免模型输出进入终端 escape 流。
pub(crate) fn render_markdown(source: &str) -> Text<'static> {
    let mut lines = vec![Line::default()];
    let mut style = Style::default();
    let mut style_stack = Vec::new();
    let mut links = Vec::new();
    let mut list_depth = 0usize;
    let mut in_code_block = false;
    for event in Parser::new(source) {
        match event {
            Event::Start(Tag::Paragraph) => start_line(&mut lines),
            Event::End(TagEnd::Paragraph) => end_line(&mut lines),
            Event::Start(Tag::Heading { level, .. }) => {
                start_line(&mut lines);
                style_stack.push(style);
                style = heading_style(level);
            }
            Event::End(TagEnd::Heading(_)) => {
                restore_style(&mut style_stack, &mut style);
                end_line(&mut lines);
            }
            Event::Start(Tag::List(_)) => {
                list_depth = list_depth.saturating_add(1);
                start_line(&mut lines);
            }
            Event::End(TagEnd::List(_)) => {
                list_depth = list_depth.saturating_sub(1);
                end_line(&mut lines);
            }
            Event::Start(Tag::Item) => {
                start_line(&mut lines);
                push_text(
                    &mut lines,
                    &format!("{}• ", "  ".repeat(list_depth.saturating_sub(1))),
                    style,
                );
            }
            Event::End(TagEnd::Item) => end_line(&mut lines),
            Event::Start(Tag::CodeBlock(_)) => {
                start_line(&mut lines);
                style_stack.push(style);
                style = Style::default()
                    .fg(Color::Rgb(210, 210, 225))
                    .bg(Color::Rgb(35, 35, 46));
                in_code_block = true;
            }
            Event::End(TagEnd::CodeBlock) => {
                in_code_block = false;
                restore_style(&mut style_stack, &mut style);
                end_line(&mut lines);
            }
            Event::Start(Tag::Strong) => {
                style_stack.push(style);
                style = style.add_modifier(Modifier::BOLD);
            }
            Event::End(TagEnd::Strong) => restore_style(&mut style_stack, &mut style),
            Event::Start(Tag::Emphasis) => {
                style_stack.push(style);
                style = style.add_modifier(Modifier::ITALIC);
            }
            Event::End(TagEnd::Emphasis) => restore_style(&mut style_stack, &mut style),
            Event::Start(Tag::Strikethrough) => {
                style_stack.push(style);
                style = style.add_modifier(Modifier::CROSSED_OUT);
            }
            Event::End(TagEnd::Strikethrough) => restore_style(&mut style_stack, &mut style),
            Event::Start(Tag::Link { dest_url, .. }) => {
                style_stack.push(style);
                style = style.fg(Color::Cyan).add_modifier(Modifier::UNDERLINED);
                links.push(dest_url.to_string());
            }
            Event::End(TagEnd::Link) => {
                restore_style(&mut style_stack, &mut style);
                if let Some(destination) = links.pop() {
                    push_text(
                        &mut lines,
                        &format!(" ({destination})"),
                        Style::default().fg(Color::DarkGray),
                    );
                }
            }
            Event::Text(text) => push_text(&mut lines, &text, style),
            Event::Code(code) => push_text(
                &mut lines,
                &code,
                Style::default()
                    .fg(Color::Rgb(200, 190, 235))
                    .bg(Color::Rgb(42, 38, 52)),
            ),
            Event::SoftBreak => push_text(&mut lines, " ", style),
            Event::HardBreak => lines.push(Line::default()),
            Event::Rule => {
                start_line(&mut lines);
                push_text(
                    &mut lines,
                    "────────────────",
                    Style::default().fg(Color::DarkGray),
                );
                end_line(&mut lines);
            }
            Event::TaskListMarker(checked) => {
                let marker = if checked { "[x] " } else { "[ ] " };
                push_text(&mut lines, marker, Style::default().fg(Color::Cyan));
            }
            Event::Html(html) | Event::InlineHtml(html) => push_text(&mut lines, &html, style),
            Event::DisplayMath(math) | Event::InlineMath(math) => {
                push_text(&mut lines, &math, style)
            }
            _ => {
                if in_code_block {
                    push_text(&mut lines, "", style);
                }
            }
        }
    }
    while lines.len() > 1 && lines.last().is_some_and(|line| line.spans.is_empty()) {
        lines.pop();
    }
    Text::from(lines)
}

/// 标题开始时只补必要换行，避免流式分块留下大片空白。
fn start_line(lines: &mut Vec<Line<'static>>) {
    if lines.last().is_some_and(|line| !line.spans.is_empty()) {
        lines.push(Line::default());
    }
}

/// 块级元素结束时只在非空行后换行，内联 Markdown 不被拆散。
fn end_line(lines: &mut Vec<Line<'static>>) {
    if lines.last().is_some_and(|line| !line.spans.is_empty()) {
        lines.push(Line::default());
    }
}

/// 将文本按源换行切成 Ratatui 行，正文永远作为 cell 数据而非原始 ANSI 写入。
fn push_text(lines: &mut Vec<Line<'static>>, text: &str, style: Style) {
    let mut parts = text.split('\n').peekable();
    while let Some(part) = parts.next() {
        if !part.is_empty() {
            lines
                .last_mut()
                .expect("markdown always starts with one line")
                .spans
                .push(Span::styled(part.to_owned(), style));
        }
        if parts.peek().is_some() {
            lines.push(Line::default());
        }
    }
}

/// 恢复嵌套的 inline 样式，避免一个链接或强调区域污染后续文字。
fn restore_style(stack: &mut Vec<Style>, current: &mut Style) {
    if let Some(previous) = stack.pop() {
        *current = previous;
    }
}

/// 标题层级只影响轻量字重和色彩，不在内联终端绘制大型标题块。
fn heading_style(level: HeadingLevel) -> Style {
    let color = match level {
        HeadingLevel::H1 => Color::Rgb(205, 174, 255),
        HeadingLevel::H2 => Color::Rgb(188, 163, 235),
        _ => Color::Rgb(173, 158, 212),
    };
    Style::default().fg(color).add_modifier(Modifier::BOLD)
}
