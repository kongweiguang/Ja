// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

mod model {
    /// 测试模块只映射生产模块依赖的展示枚举，保持 #[path] 加载不装配整套 UI。
    #[derive(Clone, Copy, Debug, Eq, PartialEq)]
    pub(crate) enum StreamPresentation {
        Markdown,
        CodeLabel,
        Code,
        Literal,
        Spacer,
    }
}

#[path = "../../src/ui/markdown.rs"]
#[allow(dead_code, unused_imports)]
mod markdown;
#[path = "../../src/ui/markdown_stream.rs"]
mod markdown_stream;

use markdown_stream::MarkdownStream;
use model::StreamPresentation;
use ratatui::text::Line;
use unicode_width::UnicodeWidthStr;

/// 小断言函数让每条用例检查同一组偏移与呈现事实，避免只验证输出文本。
fn only_piece(
    pieces: &[markdown_stream::MarkdownStreamPiece],
    presentation: StreamPresentation,
) -> &markdown_stream::MarkdownStreamPiece {
    let matching = pieces
        .iter()
        .filter(|piece| piece.presentation == presentation)
        .collect::<Vec<_>>();
    assert_eq!(matching.len(), 1, "{pieces:?}");
    matching[0]
}

/// 将相同正文分别按整段、逐字符和伪随机分片喂入账本，保留未消费字节直至下一片。
fn stream_with_chunks(source: &str, mode: usize) -> Vec<markdown_stream::MarkdownStreamPiece> {
    let characters = source.chars().collect::<Vec<_>>();
    let mut stream = MarkdownStream::default();
    let mut pending = String::new();
    let mut pieces = Vec::new();
    let mut offset = 0usize;
    let mut random = 0x9e37_79b9_u32;
    while offset < characters.len() {
        let count = match mode {
            0 => characters.len(),
            1 => 1,
            _ => {
                random ^= random << 13;
                random ^= random >> 17;
                random ^= random << 5;
                (random as usize % 17) + 1
            }
        };
        let end = offset.saturating_add(count).min(characters.len());
        pending.extend(characters[offset..end].iter().copied());
        offset = end;
        let (consumed, next) = stream.consume(&pending, 80, false);
        pending.drain(..consumed);
        pieces.extend(next);
    }
    let (consumed, final_pieces) = stream.finish(&pending, 80);
    assert_eq!(
        consumed,
        pending.len(),
        "finish must account for every source byte"
    );
    pieces.extend(final_pieces);
    pieces
}

/// 将流账本片段按生产 renderer 路径合并为行，验证归档与整段 Markdown 的最终布局相同。
fn render_stream_pieces(
    pieces: &[markdown_stream::MarkdownStreamPiece],
    width: usize,
) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    for piece in pieces {
        match piece.presentation {
            StreamPresentation::Markdown => {
                lines.extend(markdown::render_markdown(&piece.text, width).lines);
            }
            StreamPresentation::CodeLabel => {
                lines.push(markdown::code_label_line(&piece.text));
            }
            StreamPresentation::Code => {
                lines.extend(markdown::render_code_lines(&piece.text, width));
            }
            StreamPresentation::Literal => {
                lines.extend(
                    piece
                        .text
                        .split('\n')
                        .map(|line| Line::from(line.to_owned())),
                );
            }
            StreamPresentation::Spacer => lines.push(Line::default()),
        }
    }
    lines
}

/// 未闭合块不会借解析器 EOF 提前归档，终态则保留原文并交给最终 renderer。
#[test]
fn keeps_open_markdown_live_until_terminal_finish() {
    let mut stream = MarkdownStream::default();
    let source = "## 尚未确认的标题";
    let (consumed, pieces) = stream.consume(source, 40, false);
    assert_eq!(consumed, 0);
    assert!(pieces.is_empty());
    assert_eq!(stream.tail_presentation(), StreamPresentation::Markdown);

    let (finished, pieces) = stream.finish(source, 40);
    assert_eq!(finished, source.len());
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Markdown).text,
        source
    );
}

/// 类别切换排空语法完整的末尾段落时仍保留 Markdown 语义，不降级成纯文本。
#[test]
fn flush_keeps_complete_markdown_tail_formatted() {
    let mut stream = MarkdownStream::default();
    let source = "## 已闭合标题 **强调**";
    let (consumed, pieces) = stream.consume(source, 48, true);
    assert_eq!(consumed, source.len());
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Markdown).text,
        source
    );
    assert_eq!(stream.tail_presentation(), StreamPresentation::Markdown);
}

/// 根围栏按字符和长度匹配，语言标签只输出一次且 CRLF 源偏移仍完整记账。
#[test]
fn streams_code_lines_and_matches_fence_marker_and_length() {
    let mut stream = MarkdownStream::default();
    let first = "~~~rust\r\n空行前\r\n````\r\n";
    let (consumed, pieces) = stream.consume(first, 40, false);
    assert_eq!(consumed, first.len());
    assert_eq!(
        only_piece(&pieces, StreamPresentation::CodeLabel).text,
        "rust"
    );
    assert_eq!(
        pieces
            .iter()
            .filter(|piece| piece.presentation == StreamPresentation::Code)
            .map(|piece| piece.text.as_str())
            .collect::<Vec<_>>(),
        ["空行前", "````"]
    );
    assert_eq!(stream.tail_presentation(), StreamPresentation::Code);

    let close = "~~~~\r\n后续正文";
    let (closed, pieces) = stream.consume(close, 40, false);
    assert_eq!(closed, "~~~~\r\n".len());
    assert!(pieces.is_empty());
    assert_eq!(stream.tail_presentation(), StreamPresentation::Markdown);
    assert!(stream.tail_needs_spacing());
}

/// 空代码源行保留为空 Code 片段，避免把源行换行折叠成内容丢失。
#[test]
fn preserves_empty_code_lines_and_finishes_open_fence() {
    let mut stream = MarkdownStream::default();
    let opening = "```rust\n\nfirst\n";
    let (consumed, pieces) = stream.consume(opening, 32, false);
    assert_eq!(consumed, opening.len());
    assert!(
        pieces.iter().any(|piece| {
            piece.presentation == StreamPresentation::Code && piece.text.is_empty()
        })
    );

    let (finished, pieces) = stream.finish("last", 32);
    assert_eq!(finished, "last".len());
    assert_eq!(only_piece(&pieces, StreamPresentation::Code).text, "last");
    assert!(
        pieces
            .iter()
            .all(|piece| piece.presentation != StreamPresentation::CodeLabel)
    );
    assert_eq!(stream.tail_presentation(), StreamPresentation::Markdown);
}

/// 终态 EOF 可闭合无末尾换行的围栏，不把语法标记误显示成代码正文。
#[test]
fn terminal_finish_hides_closing_fence_without_final_newline() {
    let mut stream = MarkdownStream::default();
    let opening = "```rust\nbody\n";
    let (consumed, _) = stream.consume(opening, 32, false);
    assert_eq!(consumed, opening.len());
    let closing = "```";
    let (finished, pieces) = stream.finish(closing, 32);
    assert_eq!(finished, closing.len());
    assert!(pieces.is_empty());
    assert_eq!(stream.tail_presentation(), StreamPresentation::Markdown);
}

/// 终态短于闭围栏长度的反引号仍是正文，finish 必须记账并保留显示。
#[test]
fn terminal_finish_preserves_short_fence_candidate_as_code() {
    let mut stream = MarkdownStream::default();
    let opening = "```rust\nbody\n";
    let (consumed, _) = stream.consume(opening, 32, false);
    assert_eq!(consumed, opening.len());

    let malformed = "``";
    let (consumed, pieces) = stream.finish(malformed, 32);
    assert_eq!(consumed, malformed.len());
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Code).text,
        malformed
    );
}

/// 类别切换不把 EOF 当作代码闭围栏，后续追加字符仍可将候选行解释为正文。
#[test]
fn flush_preserves_possible_closing_fence_until_next_delta() {
    let mut stream = MarkdownStream::default();
    let opening = "```rust\nbody\n";
    let (consumed, _) = stream.consume(opening, 32, false);
    assert_eq!(consumed, opening.len());

    let candidate = "```";
    let (consumed, pieces) = stream.consume(candidate, 32, true);
    assert_eq!(consumed, 0);
    assert!(pieces.is_empty());
    assert_eq!(stream.tail_presentation(), StreamPresentation::Code);

    let mut continued = candidate.to_owned();
    continued.push_str("x\n");
    let (consumed, pieces) = stream.consume(&continued, 32, false);
    assert_eq!(consumed, continued.len());
    assert_eq!(only_piece(&pieces, StreamPresentation::Code).text, "```x");

    let closing = "```\n";
    let (consumed, pieces) = stream.consume(closing, 32, false);
    assert_eq!(consumed, closing.len());
    assert!(pieces.is_empty());
    assert_eq!(stream.tail_presentation(), StreamPresentation::Markdown);
}

/// 类别切换只将未闭合 inline 当作局部 Literal，空行后重新接受 Markdown 块。
#[test]
fn flushes_incomplete_inline_as_literal_and_recovers_after_blank_line() {
    let mut stream = MarkdownStream::default();
    let pending = "**未闭合强调";
    let (consumed, pieces) = stream.consume(pending, 40, true);
    assert_eq!(consumed, pending.len());
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Literal).text,
        pending
    );
    assert_eq!(stream.tail_presentation(), StreamPresentation::Literal);

    let continuation = "继续**\n\n# 新块\n";
    let (consumed, pieces) = stream.consume(continuation, 40, false);
    assert_eq!(consumed, "继续**\n\n".len());
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Literal).text,
        "继续**\n"
    );
    assert!(stream.tail_needs_spacing());

    let (finished, pieces) = stream.finish("# 新块", 40);
    assert_eq!(finished, "# 新块".len());
    assert!(
        pieces
            .iter()
            .any(|piece| piece.presentation == StreamPresentation::Spacer)
    );
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Markdown).text,
        "# 新块"
    );
}

/// Markdown 块到根围栏的边界只放一个 Spacer，避免 renderer 尾部留白再叠加流式分隔。
#[test]
fn separates_markdown_and_streamed_code_with_one_spacer() {
    let mut stream = MarkdownStream::default();
    let source = "前段说明\n\n```rust\nfn main() {}\n";
    let (consumed, pieces) = stream.consume(source, 48, false);
    assert_eq!(consumed, source.len());
    assert_eq!(
        pieces
            .iter()
            .filter(|piece| piece.presentation == StreamPresentation::Spacer)
            .count(),
        1,
        "{pieces:?}"
    );
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Markdown).text,
        "前段说明"
    );
    assert_eq!(
        only_piece(&pieces, StreamPresentation::CodeLabel).text,
        "rust"
    );
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Code).text,
        "fn main() {}"
    );
}

/// 完整表格保持为一个 Markdown 语义块，未换行的数据行不提前提交为普通段落。
#[test]
fn keeps_table_context_until_partial_row_is_confirmed() {
    let mut stream = MarkdownStream::default();
    let header = "| 项目 | 内容 |\n|---|---|\n";
    let mut pending = header.to_owned();
    let (consumed, pieces) = stream.consume(&pending, 80, false);
    assert_eq!(consumed, 0);
    assert!(pieces.is_empty());

    let partial = "| Ja | marker-table 🙂 |";
    pending.push_str(partial);
    let (consumed, pieces) = stream.consume(&pending, 80, false);
    assert_eq!(consumed, 0);
    assert!(pieces.is_empty());

    let ending = "\n\n结尾段落。";
    pending.push_str(ending);
    let (consumed, pieces) = stream.consume(&pending, 80, false);
    assert_eq!(consumed, header.len() + partial.len() + "\n\n".len());
    assert_eq!(
        only_piece(&pieces, StreamPresentation::Markdown).text,
        format!("{header}{partial}")
    );
}

/// 空围栏正文仍占一行，两个根代码块之间和其后各自只保留一行间隔。
#[test]
fn empty_fenced_blocks_match_markdown_for_all_delta_boundaries() {
    let source = "开头。\n\n```rust\n```\n\n```\n```\n\n结尾段落 marker-empty-fences。\n";
    let expected = markdown::render_markdown(source, 80).lines;
    for mode in 0..3 {
        let pieces = stream_with_chunks(source, mode);
        assert_eq!(
            render_stream_pieces(&pieces, 80),
            expected,
            "streaming mode {mode} diverged: {pieces:?}"
        );
    }
}

/// 围栏缩进按 Markdown 规则从正文各行剥离，三种分片方式呈现结果一致。
#[test]
fn indented_fenced_blocks_match_markdown_for_all_delta_boundaries() {
    let source = "前言。\n\n   ```text\n     marker-indented-code🙂\n   ```\n\n尾段。\n";
    let expected = markdown::render_markdown(source, 80).lines;
    for mode in 0..3 {
        let pieces = stream_with_chunks(source, mode);
        assert_eq!(
            render_stream_pieces(&pieces, 80),
            expected,
            "streaming mode {mode} diverged: {pieces:?}"
        );
    }
}

/// 表格分片即使把管道符和分隔行拆开也必须最终保留同一表格结构。
#[test]
fn table_streaming_matches_markdown_for_all_delta_boundaries() {
    let source = "| 项目 | 内容 |\n|---|---|\n| Ja | marker-table 🙂 |\n\n结尾段落。\n";
    let expected = markdown::render_markdown(source, 80).lines;
    for mode in 0..3 {
        let pieces = stream_with_chunks(source, mode);
        assert_eq!(
            render_stream_pieces(&pieces, 80),
            expected,
            "streaming mode {mode} diverged: {pieces:?}"
        );
    }
}

/// 有序列表的新编号可能被 EOF 暂解析为段落，流稳定边界必须保留前一列表块上下文。
#[test]
fn ordered_list_streaming_matches_markdown_for_all_delta_boundaries() {
    let source = "1. 第一项 marker-list-one\n2. 第二项 marker-list-two\n\n结尾段落。\n";
    let expected = markdown::render_markdown(source, 80).lines;
    for mode in 0..3 {
        let pieces = stream_with_chunks(source, mode);
        assert_eq!(
            render_stream_pieces(&pieces, 80),
            expected,
            "streaming mode {mode} diverged: {pieces:?}"
        );
    }
}

/// 超过 64 KiB 的未闭合块按显示列切在字素边界，回退只作用于当前块。
#[test]
fn bounds_long_open_block_on_display_grapheme_boundary() {
    let mut stream = MarkdownStream::default();
    let source = "界🙂e\u{301}".repeat(18_000);
    let (consumed, pieces) = stream.consume(&source, 24, false);
    assert!(consumed > 0 && consumed < source.len());
    assert!(std::str::from_utf8(&source.as_bytes()[..consumed]).is_ok());
    assert!(
        pieces
            .iter()
            .all(|piece| piece.presentation == StreamPresentation::Literal)
    );
    assert!(
        pieces
            .iter()
            .all(|piece| UnicodeWidthStr::width(piece.text.as_str()) <= 24)
    );
    assert_eq!(
        pieces
            .iter()
            .map(|piece| piece.text.as_str())
            .collect::<String>(),
        source[..consumed]
    );
    assert_eq!(stream.tail_presentation(), StreamPresentation::Literal);
}

/// Markdown 尾部经过宽度折分时，消费计数仍对应 UTF-8 字节而不拆 Emoji 字素。
#[test]
fn width_fallback_counts_exact_source_bytes() {
    let mut stream = MarkdownStream::default();
    let source = format!("{}{}", "正文 ".repeat(20_000), "🙂结尾");
    let (consumed, pieces) = stream.consume(&source, 32, false);
    assert!(consumed > 0 && consumed < source.len());
    assert!(std::str::from_utf8(&source.as_bytes()[..consumed]).is_ok());
    assert!(
        pieces
            .iter()
            .all(|piece| piece.presentation == StreamPresentation::Literal)
    );
    assert!(
        pieces
            .iter()
            .all(|piece| { UnicodeWidthStr::width(piece.text.as_str()) <= 32 })
    );
}
