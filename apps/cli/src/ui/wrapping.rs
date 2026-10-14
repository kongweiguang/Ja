// SPDX-License-Identifier: Apache-2.0
// Copyright 2025 OpenAI
// @author kongweiguang
// Adapted from openai/codex, codex-rs/tui/src/wrapping.rs at
// 822e58cc3d666166c7446c5b1ea2e52f5d09594c: flatten_line and slice_line_spans.
// Ja uses textwrap's first-fit algorithm with terminal-width fragments; compound
// graphemes remain indivisible without importing Codex's UI or width policies.

use std::ops::Range;

use ratatui::style::Style;
use ratatui::text::{Line, Span};
use textwrap::WordSeparator;
use textwrap::core::Fragment;
use textwrap::wrap_algorithms::wrap_first_fit;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

/// 词片段保留源码范围，度量以终端字素为准，不使用逐码点累加的 Emoji 宽度。
#[derive(Debug)]
struct TerminalWord {
    range: Range<usize>,
    width: usize,
    whitespace_width: usize,
}

impl Fragment for TerminalWord {
    /// First-fit 只接收显示列数，字节范围仍由本地切片映射持有。
    fn width(&self) -> f64 {
        self.width as f64
    }

    /// 行末空白不参与正文占用，但同一行内的词间空白保留原始列数。
    fn whitespace_width(&self) -> f64 {
        self.whitespace_width as f64
    }

    /// 路径和模型标识不插入合成连字符，复制时也无需删除装饰字符。
    fn penalty_width(&self) -> f64 {
        0.0
    }
}

/// 沿用 Codex 的 flatten/slice 结构；仅正文使用词边界，代码仍由调用方按字素硬折行。
pub(crate) fn word_wrap_line(line: &Line<'_>, width: usize) -> Vec<Line<'static>> {
    let width = width.max(1);
    let (flat, span_bounds) = flatten_line(line);
    let mut output = Vec::new();
    let mut base = 0;
    for source_line in flat.split('\n') {
        let words = terminal_words(source_line, width);
        for row in wrap_first_fit(&words, &[width as f64]) {
            if row.is_empty() && !words.is_empty() {
                continue;
            }
            let range = match (row.first(), row.last()) {
                (Some(first), Some(last)) => (base + first.range.start)..(base + last.range.end),
                _ => base..base,
            };
            let sliced = slice_line_spans(line, &span_bounds, &range);
            output.push(Line {
                style: sliced.style,
                alignment: sliced.alignment,
                spans: sliced
                    .spans
                    .into_iter()
                    .map(|span| Span::styled(span.content.into_owned(), span.style))
                    .collect(),
            });
        }
        base += source_line.len() + 1;
    }
    output
}

/// 原生 Unicode 断词规则负责中文和英文边界，落在复合字素内部的机会必须合并后才能折行。
fn terminal_words(text: &str, width: usize) -> Vec<TerminalWord> {
    let mut boundaries = text
        .grapheme_indices(true)
        .map(|(offset, _)| offset)
        .collect::<Vec<_>>();
    boundaries.push(text.len());
    let mut words = Vec::new();
    let mut cursor = 0;
    let mut pending_start = 0;
    for word in WordSeparator::UnicodeBreakProperties.find_words(text) {
        let end = cursor + word.word.len();
        cursor = end + word.whitespace.len();
        if boundaries.binary_search(&cursor).is_err() {
            continue;
        }
        let word_end = if boundaries.binary_search(&end).is_ok() {
            end
        } else {
            cursor
        };
        push_word_fragments(
            text,
            pending_start..word_end,
            word_end..cursor,
            width,
            &mut words,
        );
        pending_start = cursor;
    }
    if pending_start < text.len() {
        push_word_fragments(
            text,
            pending_start..text.len(),
            text.len()..text.len(),
            width,
            &mut words,
        );
    }
    words
}

/// 只有单词本身超出整行宽度才硬折行；拆分点始终是字素边界，续片不会重复末尾空白。
fn push_word_fragments(
    text: &str,
    range: Range<usize>,
    whitespace: Range<usize>,
    width: usize,
    output: &mut Vec<TerminalWord>,
) {
    let mut start = range.start;
    let mut columns = 0usize;
    for (offset, grapheme) in text[range.clone()].grapheme_indices(true) {
        let grapheme_width = UnicodeWidthStr::width(grapheme);
        if columns > 0 && columns.saturating_add(grapheme_width) > width {
            let end = range.start + offset;
            output.push(TerminalWord {
                range: start..end,
                width: columns,
                whitespace_width: 0,
            });
            start = end;
            columns = 0;
        }
        columns = columns.saturating_add(grapheme_width);
    }
    output.push(TerminalWord {
        range: start..range.end,
        width: columns,
        whitespace_width: UnicodeWidthStr::width(&text[whitespace]),
    });
}

/// 改自 Codex：先合并所有 Span 再断词，样式边界不会干扰词或源码范围。
fn flatten_line(line: &Line<'_>) -> (String, Vec<(Range<usize>, Style)>) {
    let mut flat = String::new();
    let mut span_bounds = Vec::new();
    let mut acc = 0usize;
    for span in &line.spans {
        let text = span.content.as_ref();
        let start = acc;
        flat.push_str(text);
        acc += text.len();
        span_bounds.push((start..acc, span.style));
    }
    (flat, span_bounds)
}

/// 改自 Codex：按同一字节范围切回原样式，折行不丢粗体、链接或行内代码背景。
fn slice_line_spans<'a>(
    original: &'a Line<'a>,
    span_bounds: &[(Range<usize>, Style)],
    range: &Range<usize>,
) -> Line<'a> {
    let start_byte = range.start;
    let end_byte = range.end;
    let mut acc: Vec<Span<'a>> = Vec::new();
    for (i, (range, style)) in span_bounds.iter().enumerate() {
        let s = range.start;
        let e = range.end;
        if e <= start_byte {
            continue;
        }
        if s >= end_byte {
            break;
        }
        let seg_start = start_byte.max(s);
        let seg_end = end_byte.min(e);
        if seg_end > seg_start {
            let local_start = seg_start - s;
            let local_end = seg_end - s;
            let content = original.spans[i].content.as_ref();
            let slice = &content[local_start..local_end];
            acc.push(Span {
                style: *style,
                content: std::borrow::Cow::Borrowed(slice),
            });
        }
        if e >= end_byte {
            break;
        }
    }
    Line {
        style: original.style,
        alignment: original.alignment,
        spans: acc,
    }
}
