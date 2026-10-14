// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[allow(dead_code)]
#[path = "../../src/ui/theme.rs"]
mod theme;

#[path = "../../src/ui/markdown.rs"]
mod markdown;
#[path = "../../src/ui/wrapping.rs"]
mod wrapping;

use ratatui::style::Modifier;
use ratatui::text::{Line, Text};

/// 测试端只拼合可见 cell 字符，不把 Style 的 Debug 输出混入布局断言。
fn visible_text(text: &Text<'_>) -> String {
    text.lines
        .iter()
        .map(|line| {
            line.spans
                .iter()
                .map(|span| span.content.as_ref())
                .collect::<String>()
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// 读取一行的可见文本，便于精确验证列表前缀和续行缩进。
fn line_text(line: &Line<'_>) -> String {
    line.spans
        .iter()
        .map(|span| span.content.as_ref())
        .collect::<String>()
}

/// 标题的层级样式和块间距保持紧凑，且文档末尾没有装饰性空行。
#[test]
fn headings_use_compact_warm_styles_and_single_block_spacing() {
    let text = markdown::render_markdown("# 一级\n\n## 二级\n\n### 更深\n", 30);

    assert_eq!(text.lines.len(), 5);
    assert!(
        text.lines[0].spans[0]
            .style
            .add_modifier
            .contains(Modifier::BOLD)
    );
    assert!(
        text.lines[0].spans[0]
            .style
            .add_modifier
            .contains(Modifier::UNDERLINED)
    );
    assert!(
        text.lines[2].spans[0]
            .style
            .add_modifier
            .contains(Modifier::BOLD)
    );
    assert!(
        text.lines[4].spans[0]
            .style
            .add_modifier
            .contains(Modifier::BOLD)
    );
    assert!(text.lines[1].spans.is_empty());
    assert!(text.lines.last().is_some_and(|line| !line.spans.is_empty()));
}

/// inline 链接按实际标签显示目标，标签与目标相同则去重并恢复外层粗体。
#[test]
fn inline_styles_restore_and_duplicate_link_targets_are_omitted() {
    let text = markdown::render_markdown(
        "**前 [same](same) 与 [label](https://example.test) 后** 普通",
        80,
    );
    let visible = visible_text(&text);

    assert_eq!(visible, "前 same 与 label (https://example.test) 后 普通");
    assert!(text.lines[0].spans.iter().any(|span| {
        span.content.contains(" 后") && span.style.add_modifier.contains(Modifier::BOLD)
    }));
    assert!(text.lines[0].spans.iter().any(|span| {
        span.content.contains("普通") && !span.style.add_modifier.contains(Modifier::BOLD)
    }));
}

/// 有序起始值、任务状态和长条目续行都保留清楚的列表阅读层级。
#[test]
fn lists_keep_ordering_task_state_and_hanging_indent() {
    let source = "8. 第一项有较长正文需要在窄屏自然折行\n   - 嵌套项\n9. [x] 已完成\n10. 第二项";
    let text = markdown::render_markdown(source, 16);
    let lines = text.lines.iter().map(line_text).collect::<Vec<_>>();

    assert!(lines[0].starts_with("8. "));
    assert!(lines.iter().any(|line| line.starts_with("   ")));
    assert!(lines.iter().any(|line| line.starts_with("   • 嵌套项")));
    assert!(lines.iter().any(|line| line.starts_with("9. [x] ")));
    assert!(visible_text(&text).contains("10. 第二项"));

    let nested =
        markdown::render_markdown("- 普通父项\n  - [x] 子项已完成\n  - [ ] 子项未完成", 40);
    assert!(line_text(&nested.lines[0]).starts_with("• 普通父项"));
    assert!(visible_text(&nested).contains("[x] 子项已完成"));
    assert!(visible_text(&nested).contains("[ ] 子项未完成"));
}

/// 紧凑列表不产生 Paragraph 标签，行内样式和软换行仍必须属于同一条正文。
#[test]
fn tight_list_keeps_inline_nodes_in_one_paragraph() {
    let source = "- **挂号**：手工接诊（`is_received` / `receive_time`）；[文档](docs/README.md)。\n  软换行仍属于本项。\n- HIS 集成：`record_stage`（DRAFT/FINAL）。";
    let text = markdown::render_markdown(source, 120);
    assert_eq!(
        visible_text(&text),
        "• 挂号：手工接诊（is_received / receive_time）；文档 (docs/README.md)。 软换行仍属于本项。\n• HIS 集成：record_stage（DRAFT/FINAL）。"
    );
    assert!(text.lines[0].spans.iter().any(|span| {
        span.content.contains("挂号") && span.style.add_modifier.contains(Modifier::BOLD)
    }));
    assert!(
        text.lines[0]
            .spans
            .iter()
            .any(|span| { span.content.contains("is_received") && span.style.bg.is_some() })
    );
}

/// 行内节点合并仅作用于同一正文，嵌套列表、引用及真正的多段条目保留块边界。
#[test]
fn list_inline_runs_keep_nested_and_loose_block_boundaries() {
    let source = "- **父项**：`parent`\n  - 子项：`child`\n\n  第二段：**重点**\n\n  > 引用：`quote`\n\n- 最后：`end`";
    let text = markdown::render_markdown(source, 60);
    assert_eq!(
        visible_text(&text),
        "• 父项：parent\n\n  • 子项：child\n\n  第二段：重点\n\n  │ 引用：quote\n• 最后：end"
    );
}

/// 断词在所有样式合并后执行，词本身能放进整行时不因剩余空间不足被硬切碎。
#[test]
fn prose_wraps_at_word_boundaries_across_inline_styles() {
    let text = markdown::render_markdown("prefix **micro**service ready", 12);
    assert_eq!(visible_text(&text), "prefix\nmicroservice\nready");
    assert!(
        text.lines[1].spans[0]
            .style
            .add_modifier
            .contains(Modifier::BOLD)
    );
    assert!(
        !text.lines[1].spans[1]
            .style
            .add_modifier
            .contains(Modifier::BOLD)
    );

    let text = markdown::render_markdown("- abc `defgh` ij", 10);
    assert_eq!(visible_text(&text), "• abc\n  defgh ij");
}

/// 原生断词机会不能拆开组合字符、旗帜和 ZWJ Emoji；超窄窗允许完整字素超出一列。
#[test]
fn word_wrapping_keeps_compound_graphemes_and_explicit_breaks() {
    use unicode_segmentation::UnicodeSegmentation;
    use unicode_width::UnicodeWidthStr;

    let source = "界👩‍💻e\u{301}🇨🇳👍🏽👨‍👩‍👧‍👦结尾";
    for width in [1, 2, 3, 8, 80] {
        let text = markdown::render_markdown(source, width);
        let lines = text.lines.iter().map(line_text).collect::<Vec<_>>();
        assert_eq!(lines.concat(), source, "width={width}");
        for line in lines {
            assert!(!line.is_empty(), "width={width}");
            assert!(
                UnicodeWidthStr::width(line.as_str()) <= width || line.graphemes(true).count() == 1,
                "width={width}, line={line}"
            );
            for grapheme in line.graphemes(true) {
                assert!(
                    source.graphemes(true).any(|original| original == grapheme),
                    "split grapheme {grapheme:?}"
                );
            }
        }
    }
    assert_eq!(
        visible_text(&markdown::render_markdown("first  \nsecond", 30)),
        "first\nsecond"
    );
}

/// fenced code 保留语言标签、空源码行和四列制表位展开后的缩进。
#[test]
fn fenced_code_preserves_blank_lines_and_expands_tabs() {
    let source = "```rust extra\n\tlet value = 1;\n\nvalue\n```";
    let text = markdown::render_markdown(source, 50);
    let lines = text.lines.iter().map(line_text).collect::<Vec<_>>();

    assert_eq!(lines[0], "╭─ rust");
    assert_eq!(lines[1], "    let value = 1;");
    assert!(lines[2].is_empty());
    assert_eq!(lines[3], "value");
}

/// 代码按字素折行，宽字和组合字素不被拆分，固定 tabstop 不受屏宽变化影响。
#[test]
fn code_wrapping_uses_graphemes_and_display_columns() {
    let lines = markdown::render_code_lines("\t界e\u{301}", 4);
    let visible = lines.iter().map(line_text).collect::<Vec<_>>();

    assert_eq!(visible, vec!["    ", "界e\u{301}"]);
}

/// 引用竖线低对比延续到内容行，引用正文自身仍保留内联样式。
#[test]
fn quote_prefix_is_muted_and_keeps_inline_formatting() {
    let text = markdown::render_markdown("> 引用 **重点**  \n> 后续", 24);
    let first = &text.lines[0];

    assert!(line_text(first).starts_with("│ "));
    assert!(first.spans[0].style.fg.is_some());
    assert!(first.spans.iter().any(|span| {
        span.content.contains("重点") && span.style.add_modifier.contains(Modifier::BOLD)
    }));
    assert!(line_text(&text.lines[1]).starts_with("│ "));
}

/// 可用宽度足够时表格逐列折行，过窄时回退仍保留完整源表格标记。
#[test]
fn tables_wrap_cells_and_keep_raw_markdown_when_too_narrow() {
    let source = "| 名称 | 说明 |\n| --- | --- |\n| Ja | 很长的说明内容 |";
    let fitted = markdown::render_markdown(source, 15);
    let narrow = markdown::render_markdown(source, 4);
    let fitted_visible = visible_text(&fitted).replace('\n', "");
    let narrow_visible = visible_text(&narrow).replace('\n', "");

    assert!(fitted_visible.contains("名称"));
    assert!(fitted_visible.contains("很长的说"));
    assert!(fitted_visible.contains("明内容"));
    assert!(narrow_visible.contains("| 名称 | 说明 |"));
    assert!(narrow_visible.contains("| --- | --- |"));
    assert!(narrow_visible.contains("| Ja | 很长的说明内容 |"));
}

/// 普通段落和未终止的代码闭合行继续留在尾部，避免后续 delta 改写已经提交的内容。
#[test]
fn stable_prefix_leaves_ambiguous_tail_and_accepts_closed_fences() {
    assert_eq!(markdown::stable_markdown_prefix("partial"), 0);
    assert_eq!(markdown::stable_markdown_prefix("```rust\nlet x = 1;"), 0);

    let completed = "已完成段落\n\n```rust\nlet x = 1;\n```";
    let prefix = markdown::stable_markdown_prefix(completed);
    assert_eq!(&completed[..prefix], "已完成段落\n\n");

    let two_paragraphs = "稳定段落\n\n未完成尾段";
    let prefix = markdown::stable_markdown_prefix(two_paragraphs);
    assert_eq!(&two_paragraphs[..prefix], "稳定段落\n\n");
}

/// 围栏闭合行带换行后才能稳定提交，追加字符时未终止的围栏仍能继续代码块。
#[test]
fn stable_prefix_requires_a_newline_after_the_closing_fence() {
    let prefix_text = "前置段落\n\n";
    let code_block = "```rust\nlet x = 1;\n```";
    let unfinished = format!("{prefix_text}{code_block}");
    let prefix = markdown::stable_markdown_prefix(&unfinished);
    assert_eq!(&unfinished[..prefix], prefix_text);

    let continued = format!("{unfinished}x");
    let prefix = markdown::stable_markdown_prefix(&continued);
    assert_eq!(&continued[..prefix], prefix_text);
    assert!(visible_text(&markdown::render_markdown(&continued, 80)).contains("```x"));

    let terminated = format!("{unfinished}\n");
    assert_eq!(
        markdown::stable_markdown_prefix(&terminated),
        terminated.len()
    );
}

/// Setext 标题直到后续结构确定前仍整体留在流式活动尾部。
#[test]
fn stable_prefix_keeps_setext_heading_at_the_tail() {
    assert_eq!(markdown::stable_markdown_prefix("标题候选\n---"), 0);
}
