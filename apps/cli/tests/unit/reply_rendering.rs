// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[allow(dead_code)]
#[path = "../../src/ui/theme.rs"]
mod theme;

use std::collections::HashMap;

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ja_cli::ui::{
    TimelineEntry, TimelineKind, TimelineStatus, TurnState, UiAction, UiEvent, UiSnapshot, UiState,
    render,
};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::buffer::Buffer;
use ratatui::style::{Color, Modifier};
use unicode_width::UnicodeWidthStr;

#[path = "../support/transcript_view.rs"]
mod transcript_view;

type StyledRow = Vec<StyledCell>;

#[derive(Clone, Debug, Eq, PartialEq)]
struct StyledCell {
    column: usize,
    symbol: String,
    foreground: Color,
    background: Color,
    modifier: Modifier,
}

const RICH_REPLY: &str = "开头段落 marker-paragraph：**粗体**、*斜体*、`行内代码`、[Ja 链接](https://example.test/ja) 🙂，组合字 e\u{301}。\n\n## 二级标题 marker-heading\n\n3. 有序第一项 marker-ordered-start，内容足够长以检查折行后续行缩进仍从正文开始。\n4. 有序第二项\n   - 嵌套项目 marker-nested，内容也会折行并保持嵌套层级的缩进。\n\n> 引用块 marker-quote，包含中文与 Emoji 🐎。\n> 引用第二行。\n\n| 项目 | 内容 |\n|---|---|\n| Ja | marker-table 🙂 |\n\n```rust\nfn main() {\n\tprintln!(\"marker-code🙂\");\n\n}\n```\n\n结尾段落 marker-final。\n";

/// 按 UTF-8 字符边界生成指定分片，专门覆盖分隔符和字素跨 delta 的路径。
fn reply_chunks(source: &str, mode: usize) -> Vec<String> {
    let characters = source.chars().collect::<Vec<_>>();
    let mut chunks = Vec::new();
    let mut offset = 0;
    let mut random = 0x9e37_79b9_u32;
    while offset < characters.len() {
        let length = match mode {
            0 => characters.len(),
            1 => 1,
            _ => {
                random ^= random << 13;
                random ^= random >> 17;
                random ^= random << 5;
                (random as usize % 17) + 1
            }
        };
        let end = offset.saturating_add(length).min(characters.len());
        chunks.push(characters[offset..end].iter().collect());
        offset = end;
    }
    chunks
}

/// 从 Ratatui 实际绘制的 Backend 读取有样式 cell，避免测试只比较 Markdown 原文。
fn styled_rows(buffer: &Buffer) -> Vec<StyledRow> {
    let width = usize::from(buffer.area.width);
    if width == 0 {
        return Vec::new();
    }
    buffer
        .content()
        .chunks(width)
        .map(|row| {
            row.iter()
                .enumerate()
                .map(|(column, cell)| StyledCell {
                    column,
                    symbol: cell.symbol().to_owned(),
                    foreground: cell.fg,
                    background: cell.bg,
                    modifier: cell.modifier,
                })
                .collect()
        })
        .collect()
}

/// Draw stable entries into the application-owned transcript viewport with spare body height.
fn render_transcript(entries: &[TimelineEntry], width: u16, height: u16) -> Vec<StyledRow> {
    let state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread_reply_render".into()),
            timeline: entries.to_vec(),
            ..UiSnapshot::default()
        },
        width,
        height,
    );
    render_state_transcript(&state, width, height)
}

/// Give the body spare rows without exceeding Ratatui's u16 cell-area limit at wide display widths.
fn render_state_transcript(state: &UiState, width: u16, height: u16) -> Vec<StyledRow> {
    let safe_height = height.max(512).min(u16::MAX / width.max(1));
    let buffer = transcript_view::render_buffer(state, width, safe_height);
    let body = styled_transcript_rows(&buffer);
    assert!(
        !body.is_empty(),
        "transcript unexpectedly empty at {width}: {:?}",
        transcript_view::rows(&buffer)
            .into_iter()
            .take(18)
            .collect::<Vec<_>>()
    );
    body
}

/// Slice at the real Composer row so full-buffer rendering can compare Markdown rows without dock noise.
fn styled_transcript_rows(buffer: &Buffer) -> Vec<StyledRow> {
    let mut rows = styled_rows(buffer)
        .into_iter()
        .take_while(|row| !row_text(row).trim_start().starts_with('›'))
        .collect::<Vec<_>>();
    while rows
        .last()
        .is_some_and(|row| row.iter().all(|cell| cell.symbol.trim().is_empty()))
    {
        rows.pop();
    }
    rows
}

/// 详情和菜单使用全屏 TestBackend，验证操作反馈与正文视口共存。
fn render_screen(state: &UiState, width: u16, height: u16) -> Vec<StyledRow> {
    let mut terminal = Terminal::new(TestBackend::new(width, height))
        .expect("TestBackend should be constructible");
    terminal
        .draw(|frame| render(frame, state))
        .expect("UI should render to TestBackend");
    styled_rows(terminal.backend().buffer())
}

/// 拼接 Ratatui cell 时跳过宽字的续格占位列，同时保留正文里真实出现的普通空格。
fn row_text(row: &StyledRow) -> String {
    let mut text = String::new();
    let mut continuation_column = 0;
    for cell in row {
        if cell.column < continuation_column {
            continue;
        }
        text.push_str(&cell.symbol);
        continuation_column = cell.column + UnicodeWidthStr::width(cell.symbol.as_str()).max(1);
    }
    text
}

/// 把真实 cell 转为带行界的文本，只用于断言正文没有被流式账本重复或遗漏。
fn rows_text(rows: &[StyledRow]) -> String {
    rows.iter().map(row_text).collect::<Vec<_>>().join("\n")
}

/// 把行样式差异压缩到首行与首格，便于多个分片模式一起报告且不倾倒整份缓冲。
fn row_difference(actual: &[StyledRow], expected: &[StyledRow]) -> Option<String> {
    let row_index = actual
        .iter()
        .zip(expected)
        .position(|(actual, expected)| actual != expected)
        .or_else(|| (actual.len() != expected.len()).then_some(actual.len().min(expected.len())));
    row_index.map(|row_index| {
        let actual_row = actual.get(row_index);
        let expected_row = expected.get(row_index);
        let column = actual_row
            .into_iter()
            .flatten()
            .zip(expected_row.into_iter().flatten())
            .position(|(actual, expected)| actual != expected);
        format!(
            "row {row_index}, column {column:?}; expected={:?}; actual={:?}; row_count expected/actual={}/{}",
            expected_row.map(row_text),
            actual_row.map(row_text),
            expected.len(),
            actual.len()
        )
    })
}

/// 创建所有分片方式共用的活动回复投影，Turn 身份用于终态快照与流账本对账。
fn live_reply_state() -> UiState {
    let turn_id = "turn_reply_render";
    let entry_id = format!("live:{turn_id}:1");
    let mut turn_ids = HashMap::new();
    turn_ids.insert(entry_id.clone(), turn_id.to_owned());
    UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread_reply_render".into()),
            turn_state: TurnState::Working,
            timeline: vec![TimelineEntry {
                id: entry_id,
                kind: TimelineKind::Assistant,
                text: String::new(),
                detail: None,
                status: Some(TimelineStatus::Running),
            }],
            turn_id_by_entry_id: turn_ids,
            ..UiSnapshot::default()
        },
        80,
        24,
    )
}

/// Reconcile the same streamed answer twice so screen-level checks catch duplicate transcript entries.
fn streamed_reply_state(source: &str, chunks: Vec<String>) -> UiState {
    let turn_id = "turn_reply_render";
    let live_id = format!("live:{turn_id}:1");
    let mut state = live_reply_state();
    for delta in chunks {
        state.apply(UiEvent::AppendStreamDelta {
            entry_id: live_id.clone(),
            delta,
        });
    }
    let mut final_turns = HashMap::new();
    final_turns.insert("answer-final".to_owned(), turn_id.to_owned());
    let final_snapshot = UiSnapshot {
        thread_id: Some("thread_reply_render".into()),
        timeline: final_reply_timeline(source),
        turn_id_by_entry_id: final_turns,
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(final_snapshot.clone())));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(final_snapshot)));
    let rendered = transcript_view::transcript_text(&state, 80, 512);
    assert!(
        !rendered.trim().is_empty(),
        "the settled stream should remain visible in the rendered transcript"
    );
    state
}

/// Build the authoritative final timeline used to compare restored and streamed rendering.
fn snapshot_reply_entries(source: &str) -> Vec<TimelineEntry> {
    final_reply_timeline(source)
}

/// 权威完成态追加既有工作摘要，确保 FinalAnswer 从实际快照路径进入 Transcript。
fn final_reply_timeline(source: &str) -> Vec<TimelineEntry> {
    let mut timeline = vec![TimelineEntry {
        id: "answer-final".into(),
        kind: TimelineKind::FinalAnswer,
        text: source.into(),
        detail: None,
        status: Some(TimelineStatus::Complete),
    }];
    timeline.extend((0..3).map(|index| TimelineEntry {
        id: format!("work-summary-{index}"),
        kind: TimelineKind::WorkSummary { elapsed_seconds: 7 },
        text: String::new(),
        detail: None,
        status: Some(TimelineStatus::Complete),
    }));
    timeline
}

/// 快照、逐字符与伪随机分片必须产生相同的终端行和样式，Markdown 内容只出现一次。
#[test]
fn rich_reply_rendering_matches_snapshot_for_all_delta_boundaries() {
    let snapshot_entries = snapshot_reply_entries(RICH_REPLY);
    let expected = render_transcript(&snapshot_entries, 80, 24);
    assert!(
        rows_text(&expected).contains("marker-final"),
        "final snapshot rows: {:?}; stable entries: {:?}",
        rows_text(&expected),
        snapshot_entries
            .iter()
            .map(|entry| (&entry.id, &entry.text))
            .collect::<Vec<_>>()
    );
    let mut differences = Vec::new();
    for mode in [0, 1, 2] {
        let state = streamed_reply_state(RICH_REPLY, reply_chunks(RICH_REPLY, mode));
        let streamed = render_state_transcript(&state, 80, 24);
        if let Some(difference) = row_difference(&streamed, &expected) {
            differences.push(format!("stream chunk mode {mode}: {difference}"));
        }
        let output = rows_text(&streamed);
        for marker in [
            "marker-paragraph",
            "marker-heading",
            "marker-ordered-start",
            "marker-nested",
            "marker-quote",
            "marker-table",
            "marker-code",
            "marker-final",
        ] {
            let count = output.matches(marker).count();
            if count != 1 {
                differences.push(format!(
                    "stream chunk mode {mode}: {marker} count is {count}, expected 1"
                ));
            }
        }
        if !output.contains("e\u{301}") {
            differences.push(format!(
                "stream chunk mode {mode}: combining grapheme was lost"
            ));
        }
        if !output.contains("🙂") {
            differences.push(format!("stream chunk mode {mode}: emoji was lost"));
        }
        if output.contains('�') {
            differences.push(format!(
                "stream chunk mode {mode}: replacement glyph appeared"
            ));
        }
    }
    assert!(differences.is_empty(), "{}", differences.join("\n"));
}

/// 真实 transcript 同时覆盖紧凑列表的样式边界、流式分片、终态对账和缩放，防止整段与逐字都渲染错却彼此相等。
#[test]
fn tight_list_inline_content_stays_contiguous_across_stream_and_resize() {
    let source = "## 业务范围\n\n- **挂号**：接诊（`is_received` / `receive_time`）；[文档](docs/README.md)。\n- HIS 集成：`record_stage`（DRAFT/FINAL）。\n\n结束🙂。\n";
    for mode in [0, 1, 2] {
        let state = streamed_reply_state(source, reply_chunks(source, mode));
        for width in [40, 80, 120, 240] {
            let expected = render_transcript(&snapshot_reply_entries(source), width, 24);
            let actual = render_state_transcript(&state, width, 24);
            assert_eq!(
                row_difference(&actual, &expected),
                None,
                "mode={mode}, width={width}"
            );
            if width >= 120 {
                let output = rows_text(&actual);
                assert!(
                    output.contains(
                        "• 挂号：接诊（is_received / receive_time）；文档 (docs/README.md)。"
                    ),
                    "{output}"
                );
                assert!(
                    output.contains("• HIS 集成：record_stage（DRAFT/FINAL）。"),
                    "{output}"
                );
            }
        }
    }
}

/// 小型边界样本覆盖空围栏、CommonMark 三空格去缩进及 EOF 后续增量的闭合判定。
#[test]
fn fenced_code_boundaries_match_authoritative_snapshot_across_stream_splits() {
    let mut differences = Vec::new();
    let samples = [
        (
            "empty_fences",
            "开头。\n\n```rust\n```\n\n```\n```\n\n结尾 marker-empty-fences。\n",
            "marker-empty-fences",
        ),
        (
            "three_space_indent",
            "   ```text\n   marker-indented-code🙂\n   ```\n\n后续段落 marker-indented-tail。\n",
            "marker-indented-code",
        ),
    ];
    for (name, source, marker) in samples {
        let expected = render_transcript(&snapshot_reply_entries(source), 80, 24);
        assert!(
            rows_text(&expected).contains(marker),
            "{name}: marker missing; rows={:?}; entries={:?}",
            rows_text(&expected),
            snapshot_reply_entries(source)
                .iter()
                .map(|entry| (&entry.id, &entry.text))
                .collect::<Vec<_>>()
        );
        for mode in [0, 1, 2] {
            let state = streamed_reply_state(source, reply_chunks(source, mode));
            let actual = render_state_transcript(&state, 80, 24);
            if let Some(difference) = row_difference(&actual, &expected) {
                differences.push(format!("{name}, chunk mode {mode}: {difference}"));
            }
            let count = rows_text(&actual).matches(marker).count();
            if count != 1 {
                differences.push(format!(
                    "{name}, chunk mode {mode}: marker count is {count}"
                ));
            }
        }
    }

    let fence_at_eof = "```text\nmarker-fence-eof🙂\n```";
    let source = format!("{fence_at_eof}x");
    let expected = render_transcript(&snapshot_reply_entries(&source), 80, 24);
    let state = streamed_reply_state(&source, vec![fence_at_eof.to_owned(), "x".to_owned()]);
    let actual = render_state_transcript(&state, 80, 24);
    if let Some(difference) = row_difference(&actual, &expected) {
        differences.push(format!(
            "fence closed at delta EOF then extended: {difference}"
        ));
    }
    let count = rows_text(&actual).matches("marker-fence-eof").count();
    if count != 1 {
        differences.push(format!(
            "fence closed at delta EOF: marker count is {count}"
        ));
    }
    assert!(differences.is_empty(), "{}", differences.join("\n"));
}

/// A stale snapshot must not regress the active transcript, and duplicate final snapshots render one answer.
#[test]
fn stale_and_new_live_snapshots_preserve_uncommitted_tail_once() {
    let turn_id = "turn_snapshot_tail";
    let live_id = format!("live:{turn_id}:1");
    let old_tail = "ZERO_STABLE_OLD_TAIL";
    let live_snapshot = |text: &str| UiSnapshot {
        thread_id: Some("thread_snapshot_tail".into()),
        turn_state: TurnState::Working,
        timeline: vec![TimelineEntry {
            id: live_id.clone(),
            kind: TimelineKind::Assistant,
            text: text.to_owned(),
            detail: None,
            status: Some(TimelineStatus::Running),
        }],
        ..UiSnapshot::default()
    };
    let mut state = UiState::new_for_terminal(live_snapshot(old_tail), 80, 24);
    assert!(
        transcript_view::transcript_text(&state, 80, 512).contains(old_tail),
        "the open first paragraph remains visible in the application transcript"
    );

    let before_stale_refresh = transcript_view::transcript_text(&state, 80, 512);
    state.apply(UiEvent::ReplaceSnapshot(Box::new(live_snapshot(
        "ZERO_STABLE_",
    ))));
    assert_eq!(state.snapshot().timeline[0].text, old_tail);
    assert_eq!(
        transcript_view::transcript_text(&state, 80, 512),
        before_stale_refresh,
        "a stale snapshot must preserve the displayed suffix"
    );

    let new_tail = format!("{old_tail}\n\nNEW_SNAPSHOT_SUFFIX");
    state.apply(UiEvent::ReplaceSnapshot(Box::new(live_snapshot(&new_tail))));
    let mut turn_ids = HashMap::new();
    turn_ids.insert("answer-snapshot-tail".to_owned(), turn_id.to_owned());
    let final_snapshot = UiSnapshot {
        thread_id: Some("thread_snapshot_tail".into()),
        timeline: vec![TimelineEntry {
            id: "answer-snapshot-tail".into(),
            kind: TimelineKind::FinalAnswer,
            text: new_tail,
            detail: None,
            status: Some(TimelineStatus::Complete),
        }],
        turn_id_by_entry_id: turn_ids,
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(final_snapshot.clone())));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(final_snapshot)));

    let text = transcript_view::transcript_text(&state, 80, 512);
    assert_eq!(
        text.matches(old_tail).count(),
        1,
        "old tail was repeated or lost"
    );
    assert_eq!(
        text.matches("NEW_SNAPSHOT_SUFFIX").count(),
        1,
        "new snapshot suffix was repeated or lost"
    );
}

/// 思考摘要以弱化斜体绘制，最终答案仍保留正常前景和字重。
#[test]
fn reasoning_summary_uses_muted_italic_style_without_tinting_final_answer() {
    let state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread_reasoning_render".into()),
            timeline: vec![
                TimelineEntry {
                    id: "reasoning".into(),
                    kind: TimelineKind::ReasoningSummary,
                    text: "THOUGHT_MARKER 思考摘要。".into(),
                    detail: None,
                    status: Some(TimelineStatus::Complete),
                },
                TimelineEntry {
                    id: "final".into(),
                    kind: TimelineKind::FinalAnswer,
                    text: "FINAL_MARKER 最终答案。".into(),
                    detail: None,
                    status: Some(TimelineStatus::Complete),
                },
            ],
            ..UiSnapshot::default()
        },
        80,
        20,
    );
    let rows = render_state_transcript(&state, 80, 20);
    let thought = rows
        .iter()
        .flatten()
        .find(|cell| cell.symbol == "T")
        .expect("reasoning text should be visible");
    let answer = rows
        .iter()
        .flatten()
        .find(|cell| cell.symbol == "F")
        .expect("final answer should be visible");
    assert!(thought.modifier.contains(Modifier::ITALIC));
    assert_ne!(thought.foreground, Color::Reset);
    assert!(!answer.modifier.contains(Modifier::ITALIC));
    assert_ne!(answer.foreground, thought.foreground);
}

/// 短结果直接可见，失败仅用动作颜色区分；两种终态沿用相同的预览缩进与详情入口。
#[test]
fn tool_timeline_shows_success_output_and_failure_diagnostics() {
    let state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread_tool_render".into()),
            timeline: vec![
                TimelineEntry {
                    id: "tool-success".into(),
                    kind: TimelineKind::Tool {
                        action: "读取".into(),
                        target: "NORMAL_TOOL_TARGET".into(),
                        preview_truncated: false,
                    },
                    text: String::new(),
                    detail: Some(
                        "SUCCESS_FIRST_LINE\nSUCCESS_SECOND_LINE\nSUCCESS_THIRD_LINE".into(),
                    ),
                    status: Some(TimelineStatus::Complete),
                },
                TimelineEntry {
                    id: "tool-failed".into(),
                    kind: TimelineKind::Tool {
                        action: "运行".into(),
                        target: "FAILED_TOOL_TARGET".into(),
                        preview_truncated: false,
                    },
                    text: String::new(),
                    detail: Some("stderr: FAILURE_DIAGNOSTIC\nexit code: 7".into()),
                    status: Some(TimelineStatus::Failed),
                },
            ],
            ..UiSnapshot::default()
        },
        100,
        24,
    );
    let rows = render_state_transcript(&state, 100, 24);
    let text_rows = rows.iter().map(row_text).collect::<Vec<_>>();
    let success_rows = text_rows
        .iter()
        .filter(|row| row.contains("NORMAL_TOOL_TARGET"))
        .collect::<Vec<_>>();
    assert_eq!(success_rows.len(), 1, "tool headers should not be repeated");
    for marker in [
        "SUCCESS_FIRST_LINE",
        "SUCCESS_SECOND_LINE",
        "SUCCESS_THIRD_LINE",
    ] {
        assert_eq!(
            text_rows.iter().filter(|row| row.contains(marker)).count(),
            1
        );
    }
    assert!(
        text_rows
            .iter()
            .any(|row| row.contains("FAILURE_DIAGNOSTIC"))
    );
    assert!(text_rows.iter().any(|row| row.contains("exit code: 7")));
    assert!(!text_rows.iter().any(|row| row.contains("Ctrl+O")));
    let failed_action = rows
        .iter()
        .flatten()
        .find(|cell| cell.symbol == "运")
        .expect("failed tool action should be visible");
    assert_eq!(failed_action.foreground, theme::RED);
}

/// Stable tool entries share one preview budget so success and failure cannot drift in the owned transcript.
#[test]
fn tool_preview_limits_success_and_failure_in_transcript_rows() {
    for status in [TimelineStatus::Complete, TimelineStatus::Failed] {
        let mut entry = tool_entry("preview-bounded", "运行", "PREVIEW_TARGET");
        entry.status = Some(status);
        entry.detail =
            Some("FIRST_RESULT\nSECOND_RESULT\nTHIRD_RESULT\nFOURTH_HIDDEN\nFIFTH_HIDDEN".into());
        let state = UiState::new_for_terminal(
            UiSnapshot {
                thread_id: Some("thread_bounded_preview".into()),
                timeline: vec![entry.clone()],
                turn_state: TurnState::Working,
                ..UiSnapshot::default()
            },
            80,
            24,
        );
        for rows in [
            render_screen(&state, 80, 24),
            render_transcript(&[entry.clone()], 80, 24),
        ] {
            let text = rows_text(&rows);
            for marker in [
                "FIRST_RESULT",
                "SECOND_RESULT",
                "THIRD_RESULT",
                "Ctrl+O 查看详情",
            ] {
                assert_eq!(text.matches(marker).count(), 1, "{status:?}: {text}");
            }
            assert!(!text.contains("FOURTH_HIDDEN"), "{status:?}: {text}");
            assert!(!text.contains("FIFTH_HIDDEN"), "{status:?}: {text}");
            let first = rows
                .iter()
                .find(|row| row_text(row).contains("FIRST_RESULT"))
                .unwrap();
            let second = rows
                .iter()
                .find(|row| row_text(row).contains("SECOND_RESULT"))
                .unwrap();
            assert!(row_text(first).starts_with("  └ FIRST_RESULT"));
            assert!(row_text(second).starts_with("    SECOND_RESULT"));
        }
    }
}

/// 宽中文、组合字与制表符不能把一行预览撑成多行；省略提示只描述显示限制，不虚构输出行数。
#[test]
fn tool_preview_truncates_unicode_to_terminal_columns() {
    for status in [TimelineStatus::Complete, TimelineStatus::Failed] {
        for width in [18, 40, 80] {
            let mut entry = tool_entry("preview-wide", "读取", "wide.txt");
            entry.status = Some(status);
            entry.detail = Some(format!("e\u{301}🙂\t{}HIDDEN_END", "中文宽字符".repeat(30)));
            let rows = render_transcript(&[entry], width, 24);
            let text = rows_text(&rows);
            let output_rows = rows
                .iter()
                .filter(|row| row_text(row).contains("e\u{301}🙂"))
                .collect::<Vec<_>>();
            assert_eq!(output_rows.len(), 1, "{width}: {text}");
            assert!(
                row_text(output_rows[0]).trim_end().ends_with('…'),
                "{width}: {text}"
            );
            assert!(text.contains("Ctrl+O"), "{width}: {text}");
            assert!(!text.contains("HIDDEN_END"), "{width}: {text}");
            assert!(rows.iter().all(|row| UnicodeWidthStr::width(row_text(row).as_str()) <= usize::from(width)));
        }
    }
}

/// 缺少安全结果时只说明没有预览，不把缺失字段冒充真实空输出；运行中不提前报告结果。
#[test]
fn tool_preview_distinguishes_unavailable_results_from_running_tools() {
    for status in [
        TimelineStatus::Complete,
        TimelineStatus::Failed,
        TimelineStatus::Running,
    ] {
        let mut entry = tool_entry("preview-empty", "运行", "EMPTY_TARGET");
        entry.status = Some(status);
        entry.detail = Some(" \n\t".into());
        let text = rows_text(&render_transcript(&[entry], 80, 24));
        assert_eq!(
            text.contains("暂无结果预览"),
            status != TimelineStatus::Running,
            "{text}"
        );
        assert!(!text.contains("Ctrl+O"), "{text}");
    }
}

/// 源端只提供短预览或没有预览时仍保留详情入口，不能用本地行数推断产物完整性。
#[test]
fn tool_preview_source_truncation_keeps_detail_hint_for_short_output() {
    for status in [TimelineStatus::Complete, TimelineStatus::Failed] {
        for detail in [Some("SOURCE_FIRST\nSOURCE_SECOND"), None] {
            let mut entry = tool_entry("preview-source-truncated", "读取", "partial.txt");
            entry.status = Some(status);
            entry.detail = detail.map(str::to_owned);
            let TimelineKind::Tool {
                preview_truncated, ..
            } = &mut entry.kind
            else {
                unreachable!()
            };
            *preview_truncated = true;
            let rows = render_state_transcript(
                &UiState::new_for_terminal(
                    UiSnapshot {
                        thread_id: Some("thread_source_truncated".into()),
                        timeline: vec![entry],
                        ..UiSnapshot::default()
                    },
                    80,
                    24,
                ),
                80,
                24,
            );
            let text = rows_text(&rows);
            assert_eq!(
                text.matches("SOURCE_FIRST").count(),
                usize::from(detail.is_some()),
                "{text}"
            );
            assert_eq!(
                text.matches("SOURCE_SECOND").count(),
                usize::from(detail.is_some()),
                "{text}"
            );
            assert_eq!(text.contains("暂无结果预览"), detail.is_none(), "{text}");
            assert_eq!(text.matches("Ctrl+O 查看详情").count(), 1, "{text}");
        }
    }
}

/// 用三步稳定工具记录创建列表场景，供快捷键测试验证身份与选择恢复。
fn tool_steps_snapshot() -> UiSnapshot {
    UiSnapshot {
        thread_id: Some("thread_tool_steps".into()),
        timeline: vec![
            tool_entry("tool-old", "读取", "OLD_TOOL"),
            tool_entry("tool-middle", "搜索", "MIDDLE_TOOL"),
            tool_entry("tool-newest", "运行", "NEWEST_TOOL"),
        ],
        ..UiSnapshot::default()
    }
}

/// 统一构造权威工具投影；详情与快捷键动作都必须使用这个稳定 entry ID。
fn tool_entry(id: &str, action: &str, target: &str) -> TimelineEntry {
    TimelineEntry {
        id: id.into(),
        kind: TimelineKind::Tool {
            action: action.into(),
            target: target.into(),
            preview_truncated: false,
        },
        text: String::new(),
        detail: Some(format!("DETAIL_{id}")),
        status: Some(TimelineStatus::Complete),
    }
}

/// 读取占位详情可即时关闭；所有迟到结果按 entry ID 限定在仍打开的面板，草稿光标不受影响。
#[test]
fn tool_browser_preserves_identity_selection_and_composer_across_recovery() {
    let mut state = UiState::new_for_terminal(tool_steps_snapshot(), 80, 24);
    let draft = "保留草稿🙂e\u{301}";
    state.handle_paste(draft);
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    let cursor = state.composer().cursor();

    assert!(
        state
            .handle_key(key(KeyCode::Char('o'), KeyModifiers::CONTROL))
            .is_empty()
    );
    let first = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(
        first,
        vec![UiAction::OpenDetails {
            entry_id: "tool-newest".into()
        }]
    );
    let loading_detail = screen_text(&state, 80, 24);
    assert!(
        loading_detail.contains("以下为安全预览；读取成功后更新工具详情。"),
        "{loading_detail}"
    );
    assert!(!loading_detail.contains("正在读取"), "{loading_detail}");
    assert!(
        loading_detail.contains("DETAIL_tool-newest"),
        "{loading_detail}"
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CloseDetails {
            entry_id: "tool-newest".into()
        }],
        "closing cancels only the owned read, preserving the turn and operation"
    );
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "tool-newest".into(),
        title: "迟到的首次回包".into(),
        body: "EARLY_DETAIL_SENTINEL".into(),
    });
    let returned_to_list = screen_text(&state, 80, 24);
    assert!(returned_to_list.contains("工具步骤"), "{returned_to_list}");
    assert!(!returned_to_list.contains("EARLY_DETAIL_SENTINEL"));
    assert_eq!(state.composer().text(), draft);
    assert_eq!(state.composer().cursor(), cursor);

    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "tool-newest".into()
        }]
    );
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "tool-newest".into(),
        title: "运行 NEWEST_TOOL".into(),
        body: "详情内容 NEWEST_DETAIL".into(),
    });
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "tool-newest".into()
        }]
    );
    state.apply(UiEvent::SetNotice(Some(
        "读取工具详情失败，按 F5 重试".into(),
    )));
    let failed_detail = screen_text(&state, 80, 24);
    assert!(!failed_detail.contains("MIDDLE_TOOL"), "{failed_detail}");
    assert!(!failed_detail.contains(draft), "{failed_detail}");
    assert!(
        failed_detail.contains("读取工具详情失败"),
        "{failed_detail}"
    );

    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    let list = screen_text(&state, 80, 24);
    assert!(list.contains("工具步骤"), "{list}");
    state.handle_key(key(KeyCode::Up, KeyModifiers::NONE));
    let older = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(
        older,
        vec![UiAction::OpenDetails {
            entry_id: "tool-middle".into()
        }]
    );
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "tool-middle".into(),
        title: "搜索 MIDDLE_TOOL".into(),
        body: "详情内容 MIDDLE_DETAIL".into(),
    });
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "tool-middle".into()
        }]
    );
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));

    let snapshot = UiSnapshot {
        thread_id: Some("thread_tool_steps".into()),
        timeline: vec![
            tool_entry("tool-added", "读取", "ADDED_TOOL"),
            tool_entry("tool-newest", "运行", "NEWEST_TOOL"),
            tool_entry("tool-middle", "搜索", "MIDDLE_TOOL"),
            tool_entry("tool-old", "读取", "OLD_TOOL"),
        ],
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(snapshot)));
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "tool-middle".into()
        }],
        "snapshot reorder must preserve the selected tool identity"
    );
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "tool-middle".into(),
        title: "搜索 MIDDLE_TOOL".into(),
        body: "当前详情 MIDDLE_DETAIL".into(),
    });
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "tool-middle".into(),
        title: "迟到回包".into(),
        body: "LATE_DETAIL_SENTINEL".into(),
    });
    let returned = screen_text(&state, 80, 24);
    assert!(
        !returned.contains("工具步骤"),
        "closed list was reopened: {returned}"
    );
    assert!(
        !returned.contains("LATE_DETAIL_SENTINEL"),
        "closed detail was reopened: {returned}"
    );
    assert_eq!(state.composer().text(), draft);
    assert_eq!(state.composer().cursor(), cursor);
}

/// 详情明确显示全文复制与返回；预览不能冒充全文，成功反馈不能使用错误色。
/// @author kongweiguang
#[test]
fn message_detail_owns_opaque_surface_and_copy_hint() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread_detail_surface".into()),
            timeline: vec![TimelineEntry {
                id: "message-detail-surface".into(),
                kind: TimelineKind::Assistant,
                text: "PUBLIC_BODY".into(),
                detail: None,
                status: Some(TimelineStatus::Complete),
            }],
            ..UiSnapshot::default()
        },
        80,
        24,
    );
    state.handle_paste("UNRELATED_COMPOSER_DRAFT");
    let _ = screen_text(&state, 80, 24);
    assert_eq!(
        state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "message-detail-surface".into(),
        }]
    );

    for (width, height) in [(40, 10), (40, 16)] {
        let rows = render_screen(&state, width, height);
        let text = rows_text(&rows);
        let footer = row_text(rows.last().expect("detail screen has a footer row"));
        assert!(text.contains("正在读取全文"), "{width}x{height}: {text}");
        assert!(!text.contains("复制"), "{width}x{height}: {text}");
        assert!(
            footer.contains("F5") && footer.contains("Esc 返回"),
            "{footer}"
        );
    }

    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "message-detail-surface".into(),
        title: "消息详情".into(),
        body: "读取失败；按 F5 重试。".into(),
    });
    state.apply(UiEvent::SetDetailsCopyReady {
        entry_id: "message-detail-surface".into(),
        ready: false,
    });
    for (width, height) in [(40, 10), (40, 16)] {
        let rows = render_screen(&state, width, height);
        let text = rows_text(&rows);
        let footer = row_text(rows.last().expect("detail screen has a footer row"));
        assert!(text.contains("读取失败"), "{width}x{height}: {text}");
        assert!(!text.contains("复制"), "{width}x{height}: {text}");
        assert!(
            footer.contains("F5") && footer.contains("Esc 返回"),
            "{footer}"
        );
    }

    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "message-detail-surface".into(),
        title: "消息全文".into(),
        body: "COMPLETE_DETAIL_BODY".into(),
    });
    state.apply(UiEvent::SetDetailsCopyReady {
        entry_id: "message-detail-surface".into(),
        ready: true,
    });
    for (width, height) in [(40, 10), (40, 16), (80, 24), (120, 24)] {
        let rows = render_screen(&state, width, height);
        let text = rows_text(&rows);
        assert!(
            text.contains("COMPLETE_DETAIL_BODY"),
            "{width}x{height}: {text}"
        );
        assert!(
            !text.contains("UNRELATED_COMPOSER_DRAFT"),
            "{width}x{height}: {text}"
        );
        assert!(!text.contains("PUBLIC_BODY"), "{width}x{height}: {text}");
        let footer = row_text(rows.last().expect("detail screen has a footer row"));
        let footer_compact = footer.replace(' ', "");
        if width == 40 {
            assert!(
                footer_compact.contains("C复制全文"),
                "{width}x{height}: {footer}"
            );
            assert!(
                !footer_compact.contains("Ctrl+Insert"),
                "{width}x{height}: {footer}"
            );
            assert!(
                footer_compact.contains("Esc返回"),
                "{width}x{height}: {footer}"
            );
            assert!(!footer.contains('…'), "{width}x{height}: {footer}");
            assert!(UnicodeWidthStr::width(footer.trim()) <= usize::from(width));
        } else {
            assert!(
                footer_compact.contains("C复制全文") && footer_compact.contains("Ctrl+Insert"),
                "{width}x{height}: {footer}"
            );
            assert!(
                footer_compact.contains("Esc返回"),
                "{width}x{height}: {footer}"
            );
        }
    }
    state.apply(UiEvent::SetNotice(Some("完整消息详情已复制。".into())));
    let copied_rows = render_screen(&state, 40, 10);
    let feedback_row = &copied_rows[copied_rows.len() - 2];
    assert!(row_text(feedback_row).contains("已复制"));
    assert_eq!(feedback_row[2].foreground, Color::Rgb(238, 239, 241));
    assert!(feedback_row[2].modifier.contains(Modifier::BOLD));
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "UNRELATED_COMPOSER_DRAFT");
}

/// 无已加载工具时快捷键给出可见反馈，不发出读取动作或创建空列表。
#[test]
fn tool_browser_without_loaded_entries_shows_notice() {
    let mut state = UiState::new(UiSnapshot::default());
    assert!(
        state
            .handle_key(key(KeyCode::Char('o'), KeyModifiers::CONTROL))
            .is_empty()
    );
    assert!(screen_text(&state, 80, 12).contains("当前会话尚无工具步骤"));
}

/// 对话 resize 只重排显示列，不改变草稿内容、字素光标和组合 Emoji 的完整性。
#[test]
fn terminal_resize_reflows_reply_without_splitting_composer_graphemes() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread_resize".into()),
            timeline: vec![TimelineEntry {
                id: "resize-answer".into(),
                kind: TimelineKind::FinalAnswer,
                text: "宽屏与窄屏都应保留这段中文回复 marker-resize 🙂。".repeat(5),
                detail: None,
                status: Some(TimelineStatus::Complete),
            }],
            ..UiSnapshot::default()
        },
        60,
        20,
    );
    let draft = "组合字 e\u{301} 与家庭 Emoji 👨‍👩‍👧‍👦";
    state.handle_paste(draft);
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    let cursor = state.composer().cursor();
    let wide = render_state_transcript(&state, 60, 20);
    state.set_terminal_size(24, 12);
    let narrow = render_state_transcript(&state, 24, 12);
    let narrow_text = rows_text(&narrow);
    assert!(rows_text(&wide).contains("marker-resize"));
    assert!(narrow_text.contains("marker-resize"));
    let narrow_screen = rows_text(&render_screen(&state, 24, 12));
    assert!(!narrow_screen.contains('�'), "{narrow_screen}");
    assert_eq!(state.composer().text(), draft);
    assert_eq!(state.composer().cursor(), cursor);
}

/// 键盘事件保持同一 Press 语义，避免测试依赖真实 Windows 输入设备。
fn key(code: KeyCode, modifiers: KeyModifiers) -> KeyEvent {
    KeyEvent::new(code, modifiers)
}

/// 直接渲染状态缓冲以断言错误提示在人眼可见的 UI 中，而非只检查内部 Notice。
fn screen_text(state: &UiState, width: u16, height: u16) -> String {
    rows_text(&render_screen(state, width, height))
}
