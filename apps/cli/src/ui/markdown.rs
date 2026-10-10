// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::ops::Range;

use pulldown_cmark::{Alignment, CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span, Text};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

/// 解析后的节点保留原始字节范围，供表格降级和流式稳定边界复用。
struct Node<'a> {
    kind: NodeKind<'a>,
    range: Range<usize>,
}

/// 容器保留 pulldown-cmark 的标签，叶子保留事件以便统一处理 inline 样式。
enum NodeKind<'a> {
    Container {
        tag: Tag<'a>,
        children: Vec<Node<'a>>,
    },
    Leaf(Event<'a>),
}

/// inline 文本段在折行前携带独立样式，避免嵌套标签污染后续文本。
struct Segment {
    text: String,
    style: Style,
}

/// 将 Markdown 渲染为有列宽约束的 Ratatui 文本，外层留白由调用方统一负责。
pub(crate) fn render_markdown(source: &str, width: usize) -> Text<'static> {
    let nodes = parse_nodes(source);
    Text::from(render_blocks(source, &nodes, width.max(1)))
}

/// 计算可安全提交到稳定滚动区的前缀；EOF 不闭合语法，未完成的表格/列表续行保留其上下文。
pub(crate) fn stable_markdown_prefix(source: &str) -> usize {
    if !source.ends_with('\n') {
        let line_start = physical_line_start(source, source.len());
        if is_partial_root_fence_prefix(&source[line_start..]) {
            return stable_markdown_prefix(&source[..line_start]).min(line_start);
        }
    }
    let nodes = parse_nodes(source);
    let Some(last_index) = nodes.len().checked_sub(1) else {
        return source.len();
    };
    let last = &nodes[last_index];

    if let Some(previous) = last_index.checked_sub(1).and_then(|index| nodes.get(index))
        && unstable_block_continuation(source, previous, last)
    {
        return previous.range.start;
    }

    if let NodeKind::Container {
        tag: Tag::CodeBlock(CodeBlockKind::Fenced(_)),
        ..
    } = &last.kind
    {
        if let Some(stable_end) = closed_fence_newline_end(source, &last.range) {
            return stable_end;
        }
        return physical_line_start(source, last.range.start);
    }

    last.range.start
}

/// 对 pulldown 可能省略的根围栏前导空格回退到物理行首，留给流解析器还原语义。
fn physical_line_start(source: &str, offset: usize) -> usize {
    source[..offset.min(source.len())]
        .rfind('\n')
        .map_or(0, |newline| newline + 1)
}

/// 避免只由空格或围栏前缀构成的首个 delta 被提前记账，后续缩进围栏才能完整识别。
fn is_partial_root_fence_prefix(source: &str) -> bool {
    let indent = source.bytes().take_while(|byte| *byte == b' ').count();
    if indent > 3 {
        return false;
    }
    let candidate = &source[indent..];
    if candidate.is_empty() {
        return indent > 0;
    }
    let Some(marker) = candidate.chars().next() else {
        return false;
    };
    if !matches!(marker, '`' | '~') {
        return false;
    }
    let run = candidate
        .chars()
        .take_while(|character| *character == marker)
        .count();
    marker == '~' || !candidate[run..].contains('`')
}

/// 未换行的候选表格行或列表续行仍可能并入前一根块，保留前块直到下一条边界确认。
fn unstable_block_continuation(source: &str, previous: &Node<'_>, last: &Node<'_>) -> bool {
    let NodeKind::Container {
        tag: Tag::Paragraph,
        ..
    } = &last.kind
    else {
        return false;
    };
    let Some(tail) = source.get(last.range.start..) else {
        return false;
    };
    if tail.ends_with('\n') {
        return false;
    }
    let Some(gap) = source.get(previous.range.end..last.range.start) else {
        return false;
    };
    if gap.matches('\n').count() > 1 || !gap.chars().all(char::is_whitespace) {
        return false;
    }
    match &previous.kind {
        NodeKind::Container {
            tag: Tag::Table(_), ..
        } => tail.trim_start().starts_with('|'),
        NodeKind::Container {
            tag: Tag::List(_), ..
        } => true,
        _ => false,
    }
}

/// 提供共享的代码正文样式，归档代码块和流式代码片段保持相同视觉语义。
pub(crate) fn code_body_style() -> Style {
    Style::default()
        .fg(Color::Rgb(218, 222, 232))
        .bg(Color::Rgb(34, 38, 48))
}

/// 提供共享的语言标签行，首个 info token 是唯一展示的语言标识。
pub(crate) fn code_label_line(code_info: &str) -> Line<'static> {
    let language = code_info.split_whitespace().next().unwrap_or("code");
    Line::from(Span::styled(
        format!("╭─ {language}"),
        Style::default()
            .fg(Color::Rgb(137, 148, 168))
            .add_modifier(Modifier::BOLD),
    ))
}

/// 展开固定四列制表位后按字素折行，空源码行仍作为独立显示行保留。
pub(crate) fn render_code_lines(source: &str, width: usize) -> Vec<Line<'static>> {
    let width = width.max(1);
    if source.is_empty() {
        return vec![Line::default()];
    }

    let mut source_lines: Vec<&str> = source.split('\n').collect();
    if source.ends_with('\n') {
        source_lines.pop();
    }
    let style = code_body_style();
    source_lines
        .into_iter()
        .flat_map(|line| {
            let line = line.strip_suffix('\r').unwrap_or(line);
            let expanded = expand_code_tabs(line);
            wrap_plain_line(&expanded, width, style)
        })
        .collect()
}

/// 使用启用表格、任务清单和删除线的事件流构建保留源码偏移的轻量树。
fn parse_nodes(source: &str) -> Vec<Node<'_>> {
    let options =
        Options::ENABLE_TABLES | Options::ENABLE_TASKLISTS | Options::ENABLE_STRIKETHROUGH;
    let mut roots = Vec::new();
    let mut stack: Vec<(Tag<'_>, usize, Vec<Node<'_>>)> = Vec::new();

    for (event, range) in Parser::new_ext(source, options).into_offset_iter() {
        match event {
            Event::Start(tag) => stack.push((tag, range.start, Vec::new())),
            Event::End(_) => {
                if let Some((tag, start, children)) = stack.pop() {
                    let node = Node {
                        kind: NodeKind::Container { tag, children },
                        range: start..range.end,
                    };
                    if let Some((_, _, parent)) = stack.last_mut() {
                        parent.push(node);
                    } else {
                        roots.push(node);
                    }
                }
            }
            leaf => {
                let node = Node {
                    kind: NodeKind::Leaf(leaf),
                    range,
                };
                if let Some((_, _, parent)) = stack.last_mut() {
                    parent.push(node);
                } else {
                    roots.push(node);
                }
            }
        }
    }

    while let Some((tag, start, children)) = stack.pop() {
        let node = Node {
            kind: NodeKind::Container { tag, children },
            range: start..source.len(),
        };
        if let Some((_, _, parent)) = stack.last_mut() {
            parent.push(node);
        } else {
            roots.push(node);
        }
    }
    roots
}

/// 普通块只有一层空行间隔，列表和引用内部沿用同一规则。
fn render_blocks(source: &str, nodes: &[Node<'_>], width: usize) -> Vec<Line<'static>> {
    let mut output = Vec::new();
    for node in nodes {
        let block = render_node(source, node, width.max(1));
        if block.is_empty() {
            continue;
        }
        if !output.is_empty() {
            output.push(Line::default());
        }
        output.extend(block);
    }
    output
}

/// 按块类型分派布局，保持解析、样式与列宽处理各自单一职责。
fn render_node(source: &str, node: &Node<'_>, width: usize) -> Vec<Line<'static>> {
    match &node.kind {
        NodeKind::Leaf(Event::Rule) => vec![
            wrap_plain_line(
                "────────────────",
                width,
                Style::default().fg(Color::DarkGray),
            )
            .into_iter()
            .next()
            .unwrap_or_default(),
        ],
        NodeKind::Leaf(Event::Html(html) | Event::InlineHtml(html)) => {
            wrap_plain_text(html, width, Style::default())
        }
        NodeKind::Leaf(Event::Text(text)) => wrap_plain_text(text, width, Style::default()),
        NodeKind::Container { tag, children } => match tag {
            Tag::Paragraph => wrap_segments(&inline_segments(children, Style::default()), width),
            Tag::Heading { level, .. } => {
                wrap_segments(&inline_segments(children, heading_style(*level)), width)
            }
            Tag::CodeBlock(kind) => render_code_block(children, kind, width),
            Tag::BlockQuote(_) => render_quote(source, children, width),
            Tag::List(start) => render_list(source, children, *start, width),
            Tag::Table(alignments) => render_table(source, node, children, alignments, width),
            Tag::TableHead | Tag::TableRow | Tag::TableCell | Tag::Item => {
                render_blocks(source, children, width)
            }
            Tag::HtmlBlock => wrap_plain_text(&nodes_plain_text(children), width, Style::default()),
            _ => render_blocks(source, children, width),
        },
        NodeKind::Leaf(event) => match event {
            Event::Code(code) => wrap_plain_text(code, width, inline_code_style()),
            Event::InlineMath(math) | Event::DisplayMath(math) => {
                wrap_plain_text(math, width, Style::default())
            }
            Event::FootnoteReference(reference) => {
                wrap_plain_text(reference, width, Style::default().fg(Color::Cyan))
            }
            Event::SoftBreak | Event::HardBreak | Event::TaskListMarker(_) => Vec::new(),
            _ => Vec::new(),
        },
    }
}

/// 代码块共用流式代码片段的标签和正文 helper，空行及 tab 语义不再分叉。
fn render_code_block(
    children: &[Node<'_>],
    kind: &CodeBlockKind<'_>,
    width: usize,
) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    if let CodeBlockKind::Fenced(info) = kind
        && !info.trim().is_empty()
    {
        lines.push(code_label_line(info));
    }
    lines.extend(render_code_lines(&nodes_plain_text(children), width));
    lines
}

/// 引用块在可用宽度内缩进正文，并用低对比竖线延续到每一行。
fn render_quote(source: &str, children: &[Node<'_>], width: usize) -> Vec<Line<'static>> {
    let body_width = width.saturating_sub(2).max(1);
    render_blocks(source, children, body_width)
        .into_iter()
        .map(|line| prefix_line(line, "│ ", quote_bar_style()))
        .collect()
}

/// 列表前缀按序号、嵌套缩进和任务状态生成，续行保留 hanging indent。
fn render_list(
    source: &str,
    children: &[Node<'_>],
    start: Option<u64>,
    width: usize,
) -> Vec<Line<'static>> {
    let mut output = Vec::new();
    let mut item_index = 0u64;
    for item in children {
        let NodeKind::Container {
            tag: Tag::Item,
            children: item_children,
        } = &item.kind
        else {
            continue;
        };

        let task = find_task_marker(item_children);
        let marker = match (start, task) {
            (Some(first), Some(checked)) => format!(
                "{}. {} ",
                first.saturating_add(item_index),
                if checked { "[x]" } else { "[ ]" }
            ),
            (Some(first), None) => format!("{}. ", first.saturating_add(item_index)),
            (None, Some(checked)) => format!("• {} ", if checked { "[x]" } else { "[ ]" }),
            (None, None) => "• ".to_owned(),
        };
        let marker_width = UnicodeWidthStr::width(marker.as_str());
        let body_width = width.saturating_sub(marker_width).max(1);
        let mut item_lines = render_blocks(source, item_children, body_width);
        if item_lines.is_empty() {
            item_lines.push(Line::default());
        }
        for (line_index, line) in item_lines.into_iter().enumerate() {
            if line.spans.is_empty() {
                output.push(line);
                continue;
            }
            let prefix = if line_index == 0 {
                marker.as_str().to_owned()
            } else {
                " ".repeat(marker_width)
            };
            output.push(prefix_line(line, &prefix, Style::default()));
        }
        item_index = item_index.saturating_add(1);
    }
    output
}

/// 只读取当前 item 的任务标记；嵌套列表独立处理，避免子任务把普通父项误画成勾选框。
fn find_task_marker(nodes: &[Node<'_>]) -> Option<bool> {
    for node in nodes {
        match &node.kind {
            NodeKind::Leaf(Event::TaskListMarker(checked)) => return Some(*checked),
            NodeKind::Container {
                tag: Tag::List(_), ..
            } => continue,
            NodeKind::Container { children, .. } => {
                if let Some(checked) = find_task_marker(children) {
                    return Some(checked);
                }
            }
            _ => {}
        }
    }
    None
}

/// 表格按显示列宽收缩并逐格折行，空间不足时回退完整原始 Markdown。
fn render_table(
    source: &str,
    node: &Node<'_>,
    children: &[Node<'_>],
    alignments: &[Alignment],
    width: usize,
) -> Vec<Line<'static>> {
    let rows = table_rows(children);
    let columns = alignments
        .len()
        .max(rows.iter().map(Vec::len).max().unwrap_or(0));
    if columns == 0 {
        return Vec::new();
    }
    let separator_width = columns.saturating_sub(1).saturating_mul(3);
    let available = width.saturating_sub(separator_width);
    if available < columns {
        let raw = source.get(node.range.clone()).unwrap_or_default();
        return wrap_plain_text(raw, width, Style::default().fg(Color::Gray));
    }

    let mut widths = vec![1usize; columns];
    for row in &rows {
        for (index, cell) in row.iter().enumerate() {
            widths[index] = widths[index].max(UnicodeWidthStr::width(cell.as_str()));
        }
    }
    while widths.iter().sum::<usize>() > available {
        let Some((index, _)) = widths.iter().enumerate().max_by_key(|(_, value)| **value) else {
            break;
        };
        if widths[index] <= 1 {
            break;
        }
        widths[index] -= 1;
    }

    let mut output = Vec::new();
    for (row_index, row) in rows.iter().enumerate() {
        let mut wrapped = Vec::with_capacity(columns);
        for (column, column_width) in widths.iter().enumerate() {
            let cell = row.get(column).map(String::as_str).unwrap_or_default();
            wrapped.push(wrap_plain_text(
                cell,
                *column_width,
                table_cell_style(row_index == 0),
            ));
        }
        let height = wrapped.iter().map(Vec::len).max().unwrap_or(1).max(1);
        for line_index in 0..height {
            let mut line = Line::default();
            for (column, cell_lines) in wrapped.iter().enumerate() {
                if column > 0 {
                    append_text(&mut line, " │ ", table_separator_style());
                }
                let cell = cell_lines.get(line_index).cloned().unwrap_or_default();
                let cell_width = line_width(&cell);
                let alignment = alignments.get(column).copied().unwrap_or(Alignment::None);
                let padding = widths[column].saturating_sub(cell_width);
                let left_padding = match alignment {
                    Alignment::Right => padding,
                    Alignment::Center => padding / 2,
                    _ => 0,
                };
                append_text(&mut line, &" ".repeat(left_padding), Style::default());
                line.spans.extend(cell.spans);
                let right_padding = padding.saturating_sub(left_padding);
                append_text(&mut line, &" ".repeat(right_padding), Style::default());
            }
            output.push(line);
        }
        if row_index == 0 && rows.len() > 1 {
            let mut divider = Line::default();
            for (column, cell_width) in widths.iter().enumerate() {
                if column > 0 {
                    append_text(&mut divider, "─┼─", table_separator_style());
                }
                append_text(
                    &mut divider,
                    &"─".repeat(*cell_width),
                    table_separator_style(),
                );
            }
            output.push(divider);
        }
    }
    output
}

/// 抽出表头与数据行时只取 cell 文本，列对齐由 Table 标签统一提供。
fn table_rows(children: &[Node<'_>]) -> Vec<Vec<String>> {
    let mut rows = Vec::new();
    for child in children {
        let NodeKind::Container { tag, children } = &child.kind else {
            continue;
        };
        if !matches!(tag, Tag::TableHead | Tag::TableRow) {
            continue;
        }
        let row = children
            .iter()
            .filter_map(|cell| match &cell.kind {
                NodeKind::Container {
                    tag: Tag::TableCell,
                    children,
                } => Some(nodes_plain_text(children)),
                _ => None,
            })
            .collect::<Vec<_>>();
        rows.push(row);
    }
    rows
}

/// 递归拼合事件文本供代码正文、HTML 块和表格降级测宽使用。
fn nodes_plain_text(nodes: &[Node<'_>]) -> String {
    let mut output = String::new();
    for node in nodes {
        match &node.kind {
            NodeKind::Leaf(event) => match event {
                Event::Text(text)
                | Event::Code(text)
                | Event::InlineMath(text)
                | Event::DisplayMath(text)
                | Event::Html(text)
                | Event::InlineHtml(text)
                | Event::FootnoteReference(text) => output.push_str(text),
                Event::SoftBreak => output.push(' '),
                Event::HardBreak => output.push('\n'),
                _ => {}
            },
            NodeKind::Container { children, .. } => output.push_str(&nodes_plain_text(children)),
        }
    }
    output
}

/// 将 inline 事件转换成带样式段，链接只在可见标签不同于目标时补充目标。
fn inline_segments(nodes: &[Node<'_>], base_style: Style) -> Vec<Segment> {
    let mut segments = Vec::new();
    collect_inline_segments(nodes, base_style, &mut segments);
    segments
}

/// 递归应用局部样式并在链接边界恢复外层样式，避免嵌套状态泄漏。
fn collect_inline_segments(nodes: &[Node<'_>], style: Style, output: &mut Vec<Segment>) {
    for node in nodes {
        match &node.kind {
            NodeKind::Leaf(event) => match event {
                Event::Text(text) | Event::InlineMath(text) | Event::DisplayMath(text) => {
                    push_segment(output, text, style)
                }
                Event::Code(code) => push_segment(output, code, inline_code_style()),
                Event::SoftBreak => push_segment(output, " ", style),
                Event::HardBreak => push_segment(output, "\n", style),
                Event::Html(html) | Event::InlineHtml(html) => push_segment(output, html, style),
                Event::FootnoteReference(reference) => {
                    push_segment(output, reference, style.fg(Color::Cyan))
                }
                Event::TaskListMarker(_) | Event::Rule | Event::Start(_) | Event::End(_) => {}
            },
            NodeKind::Container { tag, children } => match tag {
                Tag::Emphasis => {
                    collect_inline_segments(children, style.add_modifier(Modifier::ITALIC), output)
                }
                Tag::Strong => {
                    collect_inline_segments(children, style.add_modifier(Modifier::BOLD), output)
                }
                Tag::Strikethrough => collect_inline_segments(
                    children,
                    style.add_modifier(Modifier::CROSSED_OUT),
                    output,
                ),
                Tag::Link { dest_url, .. } => {
                    let start = output.len();
                    collect_inline_segments(
                        children,
                        style.fg(Color::Cyan).add_modifier(Modifier::UNDERLINED),
                        output,
                    );
                    let label = output[start..]
                        .iter()
                        .map(|segment| segment.text.as_str())
                        .collect::<String>();
                    if !dest_url.is_empty() && label != dest_url.as_ref() {
                        push_segment(
                            output,
                            &format!(" ({dest_url})"),
                            Style::default().fg(Color::DarkGray),
                        );
                    }
                }
                Tag::Image { .. } => collect_inline_segments(children, style, output),
                _ => collect_inline_segments(children, style, output),
            },
        }
    }
}

/// 空段不进入折行序列，减少样式拼接和空链接对布局的干扰。
fn push_segment(output: &mut Vec<Segment>, text: &str, style: Style) {
    if !text.is_empty() {
        output.push(Segment {
            text: text.to_owned(),
            style,
        });
    }
}

/// 按 Unicode 字素和显示列宽折行，保留段落内的样式边界与显式换行。
fn wrap_segments(segments: &[Segment], width: usize) -> Vec<Line<'static>> {
    let mut lines = vec![Line::default()];
    let mut column = 0usize;
    for segment in segments {
        for grapheme in segment.text.graphemes(true) {
            if grapheme == "\n" {
                lines.push(Line::default());
                column = 0;
                continue;
            }
            let grapheme_width = UnicodeWidthStr::width(grapheme);
            if column > 0 && column.saturating_add(grapheme_width) > width {
                lines.push(Line::default());
                column = 0;
            }
            append_text(
                lines.last_mut().expect("renderer starts with a line"),
                grapheme,
                segment.style,
            );
            column = column.saturating_add(grapheme_width);
        }
    }
    lines
}

/// 纯文本按逻辑换行逐行折叠，空行和宽字素不转成字节级截断。
fn wrap_plain_text(text: &str, width: usize, style: Style) -> Vec<Line<'static>> {
    if text.is_empty() {
        return Vec::new();
    }
    let mut lines = Vec::new();
    for source_line in text.split('\n') {
        let source_line = source_line.strip_suffix('\r').unwrap_or(source_line);
        lines.extend(wrap_plain_line(source_line, width.max(1), style));
    }
    lines
}

/// 单行折行器允许完整宽字素略超一列，避免中文或 emoji 被拆开或丢弃。
fn wrap_plain_line(text: &str, width: usize, style: Style) -> Vec<Line<'static>> {
    let width = width.max(1);
    let mut lines = vec![Line::default()];
    let mut column = 0usize;
    for grapheme in text.graphemes(true) {
        let grapheme_width = UnicodeWidthStr::width(grapheme);
        if column > 0 && column.saturating_add(grapheme_width) > width {
            lines.push(Line::default());
            column = 0;
        }
        append_text(
            lines.last_mut().expect("renderer starts with a line"),
            grapheme,
            style,
        );
        column = column.saturating_add(grapheme_width);
    }
    lines
}

/// 将 tab 按源行中的显示列展开，固定 tabstop 使终端宽度变化不改代码缩进。
fn expand_code_tabs(source_line: &str) -> String {
    let mut output = String::new();
    let mut column = 0usize;
    for grapheme in source_line.graphemes(true) {
        if grapheme == "\t" {
            let spaces = 4 - column % 4;
            output.push_str(&" ".repeat(spaces));
            column += spaces;
        } else {
            output.push_str(grapheme);
            column = column.saturating_add(UnicodeWidthStr::width(grapheme));
        }
    }
    output
}

/// 在现有行前插入带独立样式的列表或引用前缀，不重建正文 Span。
fn prefix_line(mut line: Line<'static>, prefix: &str, style: Style) -> Line<'static> {
    if !prefix.is_empty() {
        let mut spans = vec![Span::styled(prefix.to_owned(), style)];
        spans.extend(line.spans);
        line.spans = spans;
    }
    line
}

/// 追加文本时合并相邻同样式 Span，减少长回复形成的 Ratatui 节点数量。
fn append_text(line: &mut Line<'static>, text: &str, style: Style) {
    if text.is_empty() {
        return;
    }
    if let Some(last) = line.spans.last_mut()
        && last.style == style
    {
        last.content.to_mut().push_str(text);
        return;
    }
    line.spans.push(Span::styled(text.to_owned(), style));
}

/// 按字素计算显示列数，表格填充不依赖 UTF-8 字节长度。
fn line_width(line: &Line<'_>) -> usize {
    line.spans
        .iter()
        .map(|span| UnicodeWidthStr::width(span.content.as_ref()))
        .sum()
}

/// 只有匹配的闭合围栏行已经带换行才稳定，EOF 处的围栏可能被后续字符改写。
fn closed_fence_newline_end(source: &str, range: &Range<usize>) -> Option<usize> {
    let raw = source.get(range.start..)?;
    let mut lines = raw.split_inclusive('\n');
    let opening_line = lines.next()?;
    let opening = fence_line(opening_line.strip_suffix('\n').unwrap_or(opening_line))?;
    let mut offset = range.start + opening_line.len();

    for line in lines {
        let terminated = line.ends_with('\n');
        let content = line.strip_suffix('\n').unwrap_or(line);
        let Some((marker, count, remainder)) = fence_line(content) else {
            offset += line.len();
            continue;
        };
        if marker == opening.0 && count >= opening.1 && remainder.trim().is_empty() {
            return terminated.then_some(offset + line.len());
        }
        offset += line.len();
    }
    None
}

/// 识别最多三个前导空格后的反引号或波浪号序列，并返回尾部信息文本。
fn fence_line(line: &str) -> Option<(char, usize, &str)> {
    let line = line.strip_suffix('\r').unwrap_or(line);
    let indent = line.bytes().take_while(|byte| *byte == b' ').count();
    if indent > 3 {
        return None;
    }
    let tail = &line[indent..];
    let marker = tail.chars().next()?;
    if marker != '`' && marker != '~' {
        return None;
    }
    let count = tail
        .chars()
        .take_while(|character| *character == marker)
        .count();
    (count >= 3).then_some((marker, count, &tail[count..]))
}

/// 标题维持紧凑终端层级，一级暖色强调并加下划线、二级及更深仅逐级降色。
fn heading_style(level: HeadingLevel) -> Style {
    match level {
        HeadingLevel::H1 => Style::default()
            .fg(Color::Rgb(255, 197, 112))
            .add_modifier(Modifier::BOLD | Modifier::UNDERLINED),
        HeadingLevel::H2 => Style::default()
            .fg(Color::Rgb(236, 188, 117))
            .add_modifier(Modifier::BOLD),
        _ => Style::default()
            .fg(Color::Rgb(201, 162, 111))
            .add_modifier(Modifier::BOLD),
    }
}

/// inline code 与 fenced code 使用同一组暖紫字色，背景只作用于有内容的 cell。
fn inline_code_style() -> Style {
    Style::default()
        .fg(Color::Rgb(211, 195, 239))
        .bg(Color::Rgb(43, 40, 53))
}

/// 引用符号降低对比度，让引用层级清晰但不抢占正文注意力。
fn quote_bar_style() -> Style {
    Style::default().fg(Color::Rgb(103, 112, 128))
}

/// 表头使用粗体，数据单元格保持默认字重以控制终端视觉密度。
fn table_cell_style(header: bool) -> Style {
    if header {
        Style::default().add_modifier(Modifier::BOLD)
    } else {
        Style::default()
    }
}

/// 表格线统一使用低对比灰色，窄列内容仍比装饰线优先获得空间。
fn table_separator_style() -> Style {
    Style::default().fg(Color::DarkGray)
}
