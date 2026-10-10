// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::markdown::stable_markdown_prefix;
use super::model::StreamPresentation;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

const MAX_PENDING_BLOCK_BYTES: usize = 64 * 1024;

/// 保留终端历史追加时所需的呈现类型，源文本字节仍由调用方按 consumed 记账。
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct MarkdownStreamPiece {
    pub presentation: StreamPresentation,
    pub text: String,
}

/// 记录跨 delta 的根围栏身份，只有同字符且长度足够的围栏才能结束代码块。
#[derive(Clone, Debug, Eq, PartialEq)]
struct FenceState {
    marker: char,
    length: usize,
    indent: usize,
    label: Option<String>,
    label_emitted: bool,
    visible: bool,
    body_emitted: bool,
    indent_pending: bool,
}

/// 有界消费稳定 Markdown 块，同时让未闭合代码块的完整源行及时进入终端历史。
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub(crate) struct MarkdownStream {
    fence: Option<FenceState>,
    literal_fallback: bool,
    needs_spacing: bool,
}

impl MarkdownStream {
    /// 提交稳定前缀或明确要求排空的尾部；类别切换时保留围栏和局部回退状态。
    pub(crate) fn consume(
        &mut self,
        text: &str,
        width: usize,
        flush_all: bool,
    ) -> (usize, Vec<MarkdownStreamPiece>) {
        self.consume_inner(text, width, flush_all, false)
    }

    /// 终态排空所有剩余字节，让未闭合的普通 Markdown 也由同一解析器最终呈现。
    pub(crate) fn finish(&mut self, text: &str, width: usize) -> (usize, Vec<MarkdownStreamPiece>) {
        let result = self.consume_inner(text, width, true, true);
        self.fence = None;
        self.literal_fallback = false;
        self.needs_spacing = false;
        result
    }

    /// 活动尾部处于代码围栏内时沿用代码样式，避免流式换行后同块变回 Markdown。
    pub(crate) fn tail_presentation(&self) -> StreamPresentation {
        if self.literal_fallback {
            StreamPresentation::Literal
        } else if self.fence.is_some() {
            StreamPresentation::Code
        } else {
            StreamPresentation::Markdown
        }
    }

    /// 只有新根块需要与历史分开；同一围栏或超长块的续行保持连续。
    pub(crate) fn tail_needs_spacing(&self) -> bool {
        self.needs_spacing && self.fence.is_none() && !self.literal_fallback
    }

    /// 以稳定偏移消费源字节，围栏识别、预算回退和 flush 语义集中在同一状态机。
    fn consume_inner(
        &mut self,
        text: &str,
        width: usize,
        flush_all: bool,
        finish: bool,
    ) -> (usize, Vec<MarkdownStreamPiece>) {
        let width = width.max(1);
        let mut consumed = 0usize;
        let mut pieces = Vec::new();

        while consumed < text.len() {
            let remaining = &text[consumed..];

            if self.fence.is_some() {
                let closes_at_eof = self.fence.as_ref().is_some_and(|fence| {
                    !remaining.contains('\n')
                        && is_closing_fence(remaining.trim_end_matches('\r'), fence)
                });
                if finish && closes_at_eof {
                    consumed = text.len();
                    self.close_fence(&mut pieces);
                    continue;
                }

                if flush_all && !finish && remaining.ends_with('\r') {
                    break;
                }

                if flush_all
                    && !finish
                    && !remaining.contains('\n')
                    && self
                        .fence
                        .as_ref()
                        .is_some_and(|fence| is_possible_closing_fence_prefix(remaining, fence))
                {
                    break;
                }

                if let Some(newline) = remaining.find('\n') {
                    let line = remaining[..newline].trim_end_matches('\r');
                    let source_bytes = newline + 1;
                    if self
                        .fence
                        .as_ref()
                        .is_some_and(|fence| is_closing_fence(line, fence))
                    {
                        consumed += source_bytes;
                        self.close_fence(&mut pieces);
                        continue;
                    }

                    self.emit_code_label(&mut pieces);
                    self.begin_content(&mut pieces);
                    let (display_line, presentation) = self.code_line(line, true);
                    pieces.push(MarkdownStreamPiece {
                        presentation,
                        text: display_line,
                    });
                    consumed += source_bytes;
                    continue;
                }

                if remaining.len() > MAX_PENDING_BLOCK_BYTES {
                    let boundary = wrap_prefix_boundary(remaining, width);
                    self.emit_code_label(&mut pieces);
                    self.begin_content(&mut pieces);
                    let (display_line, presentation) =
                        self.code_line(&remaining[..boundary], false);
                    pieces.push(MarkdownStreamPiece {
                        presentation,
                        text: display_line,
                    });
                    self.literal_fallback = true;
                    consumed += boundary;
                    continue;
                }

                if finish || flush_all {
                    self.emit_code_label(&mut pieces);
                    self.begin_content(&mut pieces);
                    let (display_line, presentation) = self.code_line(remaining, false);
                    pieces.push(MarkdownStreamPiece {
                        presentation,
                        text: display_line,
                    });
                    consumed = text.len();
                    continue;
                }
                break;
            }

            if self.literal_fallback {
                if let Some(boundary) = blank_line_boundary(remaining) {
                    if boundary.content_end > MAX_PENDING_BLOCK_BYTES {
                        let chunk = wrap_prefix_boundary(remaining, width);
                        self.begin_content(&mut pieces);
                        pieces.push(MarkdownStreamPiece {
                            presentation: StreamPresentation::Literal,
                            text: remaining[..chunk].to_owned(),
                        });
                        consumed += chunk;
                        continue;
                    }
                    let content = &remaining[..boundary.content_end];
                    if !content.is_empty() {
                        self.begin_content(&mut pieces);
                        pieces.push(MarkdownStreamPiece {
                            presentation: StreamPresentation::Literal,
                            text: content.to_owned(),
                        });
                    }
                    consumed += boundary.source_end;
                    self.literal_fallback = false;
                    self.needs_spacing = true;
                    continue;
                }

                if remaining.len() > MAX_PENDING_BLOCK_BYTES {
                    let boundary = wrap_prefix_boundary(remaining, width);
                    self.begin_content(&mut pieces);
                    pieces.push(MarkdownStreamPiece {
                        presentation: StreamPresentation::Literal,
                        text: remaining[..boundary].to_owned(),
                    });
                    consumed += boundary;
                    continue;
                }

                if finish || flush_all {
                    self.begin_content(&mut pieces);
                    pieces.push(MarkdownStreamPiece {
                        presentation: StreamPresentation::Literal,
                        text: remaining.to_owned(),
                    });
                    consumed = text.len();
                    continue;
                }
                break;
            }

            let blank_prefix = blank_line_prefix_bytes(remaining);
            if blank_prefix > 0 {
                consumed += blank_prefix;
                continue;
            }

            let can_finish_opening_line = remaining.contains('\n') || finish || flush_all;
            if can_finish_opening_line && let Some((fence, source_bytes)) = opening_fence(remaining)
            {
                let has_label = fence.label.is_some();
                self.fence = Some(fence);
                consumed += source_bytes;
                if has_label {
                    self.emit_code_label(&mut pieces);
                }
                continue;
            }

            let stable_bytes = stable_markdown_prefix(remaining).min(remaining.len());
            if stable_bytes > 0 {
                let stable = &remaining[..stable_bytes];
                self.begin_content(&mut pieces);
                let visible_end = stable.trim_end_matches(['\r', '\n']).len();
                if visible_end > 0 {
                    pieces.push(MarkdownStreamPiece {
                        presentation: StreamPresentation::Markdown,
                        text: stable[..visible_end].to_owned(),
                    });
                    self.needs_spacing = true;
                }
                consumed += stable_bytes;
                self.literal_fallback = false;
                continue;
            }

            if remaining.len() > MAX_PENDING_BLOCK_BYTES {
                let boundary = wrap_prefix_boundary(remaining, width);
                self.begin_content(&mut pieces);
                pieces.push(MarkdownStreamPiece {
                    presentation: StreamPresentation::Literal,
                    text: remaining[..boundary].to_owned(),
                });
                consumed += boundary;
                self.literal_fallback = true;
                self.needs_spacing = false;
                continue;
            }

            if finish {
                self.begin_content(&mut pieces);
                pieces.push(MarkdownStreamPiece {
                    presentation: StreamPresentation::Markdown,
                    text: remaining.to_owned(),
                });
                consumed = text.len();
                self.needs_spacing = true;
                continue;
            }

            if flush_all {
                self.begin_content(&mut pieces);
                let is_balanced = markdown_inline_balanced(remaining);
                pieces.push(MarkdownStreamPiece {
                    presentation: if is_balanced {
                        StreamPresentation::Markdown
                    } else {
                        StreamPresentation::Literal
                    },
                    text: remaining.to_owned(),
                });
                consumed = text.len();
                self.literal_fallback = !is_balanced;
                self.needs_spacing = is_balanced;
                continue;
            }
            break;
        }

        if finish && self.fence.is_some() {
            self.emit_empty_code_body(&mut pieces);
        }

        (consumed, pieces)
    }

    /// 围栏语言只输出一次，并与首行代码保持紧邻以避免伪造独立块间距。
    fn emit_code_label(&mut self, pieces: &mut Vec<MarkdownStreamPiece>) {
        let label = {
            let Some(fence) = self.fence.as_mut() else {
                return;
            };
            if fence.label_emitted {
                return;
            }
            fence.label_emitted = true;
            let label = fence.label.clone();
            fence.visible |= label.is_some();
            label
        };
        if let Some(label) = label {
            self.begin_content(pieces);
            pieces.push(MarkdownStreamPiece {
                presentation: StreamPresentation::CodeLabel,
                text: label,
            });
        }
    }

    /// 围栏闭合时为空正文补出一行，保留空代码块自身与后续 Spacer 的独立语义。
    fn close_fence(&mut self, pieces: &mut Vec<MarkdownStreamPiece>) {
        self.emit_empty_code_body(pieces);
        if let Some(fence) = self.fence.take() {
            self.needs_spacing |= fence.visible;
        }
        self.literal_fallback = false;
    }

    /// 仅对从未输出正文行的围栏补空 Code 片段，避免空块在流式归档中消失。
    fn emit_empty_code_body(&mut self, pieces: &mut Vec<MarkdownStreamPiece>) {
        let is_empty = self.fence.as_ref().is_some_and(|fence| !fence.body_emitted);
        if !is_empty {
            return;
        }
        self.emit_code_label(pieces);
        self.begin_content(pieces);
        pieces.push(MarkdownStreamPiece {
            presentation: StreamPresentation::Code,
            text: String::new(),
        });
        if let Some(fence) = self.fence.as_mut() {
            fence.body_emitted = true;
            fence.visible = true;
        }
    }

    /// 对每条代码源行去除围栏缩进一次，长行分片延续时不重复裁掉正文空格。
    fn code_line(&mut self, source: &str, complete_line: bool) -> (String, StreamPresentation) {
        let presentation = if self.literal_fallback {
            StreamPresentation::Literal
        } else {
            StreamPresentation::Code
        };
        let Some(fence) = self.fence.as_mut() else {
            return (source.to_owned(), presentation);
        };
        let display = if fence.indent_pending {
            strip_fence_indent(source, fence.indent)
        } else {
            source.to_owned()
        };
        fence.indent_pending = complete_line;
        fence.body_emitted |= !source.is_empty() || complete_line;
        fence.visible |= !source.is_empty() || complete_line;
        (display, presentation)
    }

    /// 新根内容前只插入一个 spacer，内部 Markdown 的空行仍交给统一 renderer 处理。
    fn begin_content(&mut self, pieces: &mut Vec<MarkdownStreamPiece>) {
        if self.needs_spacing {
            pieces.push(MarkdownStreamPiece {
                presentation: StreamPresentation::Spacer,
                text: String::new(),
            });
        }
        self.needs_spacing = false;
    }
}

/// 围栏必须是根块行首且最多缩进三列，避免把列表或引用中的围栏误当根围栏。
fn opening_fence(source: &str) -> Option<(FenceState, usize)> {
    let line_end = source.find('\n').unwrap_or(source.len());
    let line = source[..line_end].trim_end_matches('\r');
    let indent = line.len() - line.trim_start_matches(' ').len();
    if indent > 3 {
        return None;
    }
    let candidate = &line[indent..];
    let marker = candidate.chars().next()?;
    if !matches!(marker, '`' | '~') {
        return None;
    }
    let length = candidate.chars().take_while(|ch| *ch == marker).count();
    if length < 3 {
        return None;
    }
    let info = candidate[length..].trim();
    if marker == '`' && info.contains('`') {
        return None;
    }
    let label = info.split_whitespace().next().map(str::to_owned);
    Some((
        FenceState {
            marker,
            length,
            indent,
            label,
            label_emitted: false,
            visible: false,
            body_emitted: false,
            indent_pending: true,
        },
        line_end + usize::from(line_end < source.len()),
    ))
}

/// 跳过根块之间的空白行源码字节；布局只由一个显式 Spacer 表达。
fn blank_line_prefix_bytes(source: &str) -> usize {
    let mut bytes = 0usize;
    for line in source.split_inclusive('\n') {
        if !line.ends_with('\n') {
            break;
        }
        let content = line.trim_end_matches(['\r', '\n']);
        if !content.trim().is_empty() {
            break;
        }
        bytes += line.len();
    }
    bytes
}

/// 按围栏开头的缩进列裁掉代码行前缀，与 Markdown renderer 的根围栏语义一致。
fn strip_fence_indent(source: &str, indent: usize) -> String {
    let removed = source
        .bytes()
        .take_while(|byte| *byte == b' ')
        .count()
        .min(indent);
    source[removed..].to_owned()
}

/// 闭合行复用原围栏字符与最小长度，并拒绝带内容的伪闭合行。
fn is_closing_fence(line: &str, fence: &FenceState) -> bool {
    let trimmed = line.trim_start_matches(' ');
    let indent = line.len() - trimmed.len();
    if indent > 3 {
        return false;
    }
    let run = trimmed.chars().take_while(|ch| *ch == fence.marker).count();
    run >= fence.length && trimmed[run..].trim().is_empty()
}

/// 类别切换时保留可能继续成长为闭围栏的未换行行尾，避免后续字符被误写进代码。
fn is_possible_closing_fence_prefix(source: &str, fence: &FenceState) -> bool {
    let line = source.trim_end_matches('\r');
    let trimmed = line.trim_start_matches(' ');
    let indent = line.len() - trimmed.len();
    if indent > 3 {
        return false;
    }
    let run = trimmed
        .chars()
        .take_while(|character| *character == fence.marker)
        .count();
    run > 0 && trimmed[run..].trim().is_empty()
}

struct BlankLineBoundary {
    content_end: usize,
    source_end: usize,
}

/// 查找 Markdown 块分隔空行，以该边界结束当前的超长纯文本回退。
fn blank_line_boundary(source: &str) -> Option<BlankLineBoundary> {
    let mut line_start = 0usize;
    for segment in source.split_inclusive('\n') {
        let line = segment.trim_end_matches(['\r', '\n']);
        if line.trim().is_empty() {
            return Some(BlankLineBoundary {
                content_end: line_start,
                source_end: line_start + segment.len(),
            });
        }
        line_start += segment.len();
    }
    None
}

/// 以显示列而非 UTF-8 字节选取回退前缀，确保永不拆开 Emoji 或组合字素。
fn wrap_prefix_boundary(source: &str, width: usize) -> usize {
    let mut cells = 0usize;
    let mut last_space = None;
    let mut last_boundary = 0usize;
    for (byte, grapheme) in source.grapheme_indices(true) {
        let cell_width = UnicodeWidthStr::width(grapheme).max(1);
        if cells.saturating_add(cell_width) > width {
            break;
        }
        cells += cell_width;
        last_boundary = byte + grapheme.len();
        if grapheme.chars().all(char::is_whitespace) {
            last_space = Some(last_boundary);
        }
    }
    last_space
        .filter(|space| *space >= width / 2)
        .unwrap_or(last_boundary)
        .max(source.graphemes(true).next().map_or(0, str::len))
}

/// 切换时只把语法闭合的尾部交给 Markdown renderer，未闭合标记原样显示且局部回退。
fn markdown_inline_balanced(source: &str) -> bool {
    let bytes = source.as_bytes();
    if bytes.last() == Some(&b'\\') {
        return false;
    }
    let mut index = 0usize;
    let mut markers = std::collections::HashMap::<&'static str, usize>::new();
    let mut code_ticks = 0usize;
    let mut bracket_depth = 0usize;
    let mut link_depth = 0usize;
    while index < bytes.len() {
        if bytes[index] == b'\\' {
            index = (index + 2).min(bytes.len());
            continue;
        }
        if bytes[index] == b'`' {
            let start = index;
            while bytes.get(index) == Some(&b'`') {
                index += 1;
            }
            let run = index - start;
            if code_ticks == 0 {
                code_ticks = run;
            } else if code_ticks == run {
                code_ticks = 0;
            }
            continue;
        }
        if code_ticks > 0 {
            index += 1;
            continue;
        }
        if link_depth > 0 {
            match bytes[index] {
                b'(' => link_depth = link_depth.saturating_add(1),
                b')' => link_depth = link_depth.saturating_sub(1),
                _ => {}
            }
            index += 1;
            continue;
        }
        if bytes[index] == b'[' {
            bracket_depth = bracket_depth.saturating_add(1);
            index += 1;
            continue;
        }
        if bytes[index] == b']' && bracket_depth > 0 {
            bracket_depth -= 1;
            if bytes.get(index + 1) == Some(&b'(') {
                link_depth = 1;
                index += 2;
            } else {
                index += 1;
            }
            continue;
        }
        let marker = if bytes[index..].starts_with(b"**") {
            Some(("**", 2))
        } else if bytes[index..].starts_with(b"__") {
            Some(("__", 2))
        } else if bytes[index..].starts_with(b"~~") {
            Some(("~~", 2))
        } else if matches!(bytes[index], b'*' | b'_') {
            Some((if bytes[index] == b'*' { "*" } else { "_" }, 1))
        } else {
            None
        };
        if let Some((token, length)) = marker {
            if token == "_" {
                let previous_word = source[..index]
                    .chars()
                    .next_back()
                    .is_some_and(char::is_alphanumeric);
                let next_word = source[index + length..]
                    .chars()
                    .next()
                    .is_some_and(char::is_alphanumeric);
                if previous_word && next_word {
                    index += length;
                    continue;
                }
            }
            *markers.entry(token).or_default() ^= 1;
            index += length;
            continue;
        }
        index += 1;
    }
    code_ticks == 0
        && bracket_depth == 0
        && link_depth == 0
        && markers.values().all(|count| *count == 0)
}
