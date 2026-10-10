// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers, MouseButton, MouseEvent, MouseEventKind};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::buffer::Buffer;
use ratatui::layout::Rect;
use ratatui::text::{Line, Span};
use unicode_width::UnicodeWidthStr;

use super::model::{
    TimelineEntry, TimelineKind, TimelineStatus, TurnState, UiAction, UiEvent, UiSnapshot,
};
use super::render;
use super::state::UiState;
use super::transcript::{Transcript, TranscriptBlock};

/// 构造明确身份的条目，让插页、快照更新和浏览锚点都按实际会话键运行。
fn entry(id: &str, text: &str, kind: TimelineKind, status: TimelineStatus) -> TimelineEntry {
    TimelineEntry {
        id: id.into(),
        kind,
        text: text.into(),
        detail: None,
        status: Some(status),
    }
}

/// 显式限定正文和提示行几何，隔离测试 Transcript 对共享 RenderPlan 坐标的消费。
fn layout(transcript: &mut Transcript, width: u16, body_height: u16) {
    transcript.update_layout(
        width,
        Rect::new(0, 0, width, body_height),
        Rect::new(0, body_height, width, 1),
        Vec::new(),
    );
}

/// 以真实 TestBackend 读取正文与控制行，宽字符续格按显示列跳过。
fn draw(transcript: &Transcript, width: u16, body_height: u16) -> Buffer {
    let mut terminal = Terminal::new(TestBackend::new(width, body_height + 1)).unwrap();
    terminal
        .draw(|frame| transcript.render(frame))
        .expect("transcript should render to the application buffer");
    terminal.backend().buffer().clone()
}

/// 按 Ratatui 的物理行回读，不把宽字符续格误认为复制内容或有效布局空白。
fn rows(buffer: &Buffer) -> Vec<String> {
    let width = usize::from(buffer.area.width.max(1));
    buffer
        .content()
        .chunks(width)
        .map(|row| {
            let mut text = String::new();
            let mut next_column = 0;
            for (column, cell) in row.iter().enumerate() {
                if column < next_column {
                    continue;
                }
                text.push_str(cell.symbol());
                next_column = column + UnicodeWidthStr::width(cell.symbol()).max(1);
            }
            text
        })
        .collect()
}

/// 给终端输入提供稳定身份与较长历史，保证滚动动作确实穿过正文分页边界。
fn history_state(thread_id: &str, has_older: bool, count: usize) -> UiState {
    let timeline = (0..count)
        .map(|index| {
            entry(
                &format!("entry-{index:03}"),
                &format!("HISTORY_{index:03}\n第二行内容"),
                TimelineKind::Assistant,
                TimelineStatus::Complete,
            )
        })
        .collect();
    UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some(thread_id.into()),
            model_identifier: Some("gpt-6-sol".into()),
            has_older_history: has_older,
            timeline,
            ..UiSnapshot::default()
        },
        80,
        24,
    )
}

/// 鼠标范围选择按源字素恢复中文、Emoji、组合符和原始空格，异步失败不得清空选择。
#[test]
fn selection_copy_preserves_unicode_and_failure_keeps_the_selection() {
    let source = "中🙂e\u{301} z";
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread-copy".into()),
            timeline: vec![entry(
                "copy-entry",
                source,
                TimelineKind::Assistant,
                TimelineStatus::Complete,
            )],
            ..UiSnapshot::default()
        },
        40,
        10,
    );
    let screen = draw_state(&mut state, 40, 10);
    let body_row = rows(&screen)
        .iter()
        .position(|row| row.contains("中🙂e\u{301}"))
        .expect("reply should be visible in transcript");
    let row = rows(&screen)
        .into_iter()
        .nth(body_row)
        .expect("located reply row should exist");
    let start_column = UnicodeWidthStr::width(&row[..row.find('中').unwrap()]) as u16;
    let end_column = start_column + UnicodeWidthStr::width(source) as u16 - 1;
    for event in [
        mouse(
            MouseEventKind::Down(MouseButton::Left),
            start_column,
            body_row as u16,
        ),
        mouse(
            MouseEventKind::Drag(MouseButton::Left),
            end_column,
            body_row as u16,
        ),
        mouse(
            MouseEventKind::Up(MouseButton::Left),
            end_column,
            body_row as u16,
        ),
    ] {
        state.handle_mouse(event);
    }

    let actions = state.handle_key(KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL));
    let [UiAction::CopyTranscriptSelection { selection_id, text }] = actions.as_slice() else {
        panic!("selection should produce one copy action: {actions:?}");
    };
    assert_eq!(text, source);
    state.apply(UiEvent::TranscriptSelectionCopyFailed {
        selection_id: *selection_id,
        notice: "剪贴板不可用".into(),
    });
    assert!(state.transcript.borrow().has_selection());
    assert_eq!(state.snapshot().notice.as_deref(), Some("剪贴板不可用"));

    let retry_actions = state.handle_key(KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL));
    let [
        UiAction::CopyTranscriptSelection {
            selection_id: retry_id,
            text: retry_text,
        },
    ] = retry_actions.as_slice()
    else {
        panic!("retained selection should be retryable: {retry_actions:?}");
    };
    assert_ne!(retry_id, selection_id);
    assert_eq!(retry_text, source);
    state.apply(UiEvent::TranscriptSelectionCopied {
        selection_id: *selection_id,
    });
    assert!(state.transcript.borrow().has_selection());
    assert_eq!(state.snapshot().notice.as_deref(), Some("剪贴板不可用"));
    state.apply(UiEvent::TranscriptSelectionCopied {
        selection_id: *retry_id,
    });
    assert!(!state.transcript.borrow().has_selection());
    assert_eq!(state.snapshot().notice.as_deref(), Some("选区已复制"));
}

/// 松手越过正文边缘只结束拖选；同一提示行随后仍能作为普通返回入口使用。
#[test]
fn selection_release_over_return_control_does_not_interrupt_browsing() {
    let mut transcript = Transcript::default();
    for index in 0..12 {
        let id = format!("drag-{index}");
        transcript.append(
            entry(
                &id,
                &format!("DRAG_{index:02}\n可选择正文"),
                TimelineKind::Assistant,
                TimelineStatus::Complete,
            ),
            id,
            0,
        );
    }
    layout(&mut transcript, 40, 6);
    transcript.jump_top();
    let screen = rows(&draw(&transcript, 40, 6));
    let row = screen
        .iter()
        .position(|row| row.contains("DRAG_00"))
        .unwrap();
    assert!(transcript.handle_mouse(mouse(
        MouseEventKind::Down(MouseButton::Left),
        0,
        row as u16
    )));
    assert!(transcript.handle_mouse(mouse(MouseEventKind::Drag(MouseButton::Left), 8, 6)));
    assert!(transcript.handle_mouse(mouse(MouseEventKind::Up(MouseButton::Left), 8, 6)));
    assert!(transcript.is_browsing());
    assert!(transcript.has_selection());
    assert!(!transcript.handle_mouse(mouse(MouseEventKind::Drag(MouseButton::Left), 8, 6)));
    assert!(transcript.handle_mouse(mouse(MouseEventKind::Down(MouseButton::Left), 8, 6)));
    assert!(!transcript.is_browsing());
}

/// 装饰性语言标签没有正文可复制时要给出诊断，不能让复制快捷键落入运行取消分支。
#[test]
fn copying_only_a_code_label_keeps_selection_and_does_not_cancel_work() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            turn_state: TurnState::Working,
            timeline: vec![entry(
                "label-only",
                "```rust\nlet value = 1;\n```",
                TimelineKind::Assistant,
                TimelineStatus::Complete,
            )],
            ..UiSnapshot::default()
        },
        80,
        24,
    );
    let screen = rows(&draw_state(&mut state, 80, 24));
    let (row, line) = screen
        .iter()
        .enumerate()
        .find(|(_, row)| row.contains("╭─ rust"))
        .unwrap();
    let column = UnicodeWidthStr::width(&line[..line.find("rust").unwrap()]) as u16;
    for event in [
        mouse(MouseEventKind::Down(MouseButton::Left), column, row as u16),
        mouse(
            MouseEventKind::Drag(MouseButton::Left),
            column + 3,
            row as u16,
        ),
        mouse(
            MouseEventKind::Up(MouseButton::Left),
            column + 3,
            row as u16,
        ),
    ] {
        state.handle_mouse(event);
    }
    for key in [
        KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL),
        KeyEvent::new(
            KeyCode::Char('c'),
            KeyModifiers::CONTROL | KeyModifiers::SHIFT,
        ),
        KeyEvent::new(KeyCode::Insert, KeyModifiers::CONTROL),
    ] {
        assert!(state.handle_key(key).is_empty());
        assert!(state.transcript.borrow().has_selection());
        assert_eq!(
            state.snapshot().notice.as_deref(),
            Some("选区没有可复制文字；Esc 清除选区")
        );
    }
    assert!(
        state
            .handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE))
            .is_empty()
    );
    assert!(!state.transcript.borrow().has_selection());
    assert_eq!(
        state.handle_key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CancelTurn]
    );
}

/// 新正文可以与剪贴板回执交错；稳定选区、失败诊断及重试成功都必须保留请求身份。
#[test]
fn streaming_updates_preserve_inflight_copy_failure_and_retry() {
    let mut state = history_state("copy-during-stream", false, 30);
    let _ = draw_state(&mut state, 80, 24);
    state.handle_key(KeyEvent::new(KeyCode::Home, KeyModifiers::CONTROL));
    let screen = rows(&draw_state(&mut state, 80, 24));
    let (row, line) = screen
        .iter()
        .enumerate()
        .find(|(_, row)| row.contains("HISTORY_000"))
        .unwrap();
    let column = UnicodeWidthStr::width(&line[..line.find("HISTORY_000").unwrap()]) as u16;
    for event in [
        mouse(MouseEventKind::Down(MouseButton::Left), column, row as u16),
        mouse(
            MouseEventKind::Drag(MouseButton::Left),
            column + 10,
            row as u16,
        ),
        mouse(
            MouseEventKind::Up(MouseButton::Left),
            column + 10,
            row as u16,
        ),
    ] {
        state.handle_mouse(event);
    }
    let copy_key = KeyEvent::new(KeyCode::Char('c'), KeyModifiers::CONTROL);
    let actions = state.handle_key(copy_key);
    let [UiAction::CopyTranscriptSelection { selection_id, text }] = actions.as_slice() else {
        panic!("copy action expected: {actions:?}");
    };
    assert_eq!(text, "HISTORY_000");
    state.apply(UiEvent::UpsertEntry(entry(
        "stream-copy",
        "新正文",
        TimelineKind::Assistant,
        TimelineStatus::Running,
    )));
    let _ = draw_state(&mut state, 80, 24);
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "stream-copy".into(),
        delta: "持续增长🙂".into(),
    });
    let _ = draw_state(&mut state, 80, 24);
    state.apply(UiEvent::TranscriptSelectionCopyFailed {
        selection_id: *selection_id,
        notice: "剪贴板暂时占用".into(),
    });
    assert_eq!(state.snapshot().notice.as_deref(), Some("剪贴板暂时占用"));
    assert!(state.transcript.borrow().has_selection());
    let retry = state.handle_key(copy_key);
    let [
        UiAction::CopyTranscriptSelection {
            selection_id: retry_id,
            text: retry_text,
        },
    ] = retry.as_slice()
    else {
        panic!("retry expected: {retry:?}");
    };
    assert_ne!(retry_id, selection_id);
    assert_eq!(retry_text, text);
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "stream-copy".into(),
        delta: "\n后续片段".into(),
    });
    let _ = draw_state(&mut state, 80, 24);
    state.apply(UiEvent::TranscriptSelectionCopied {
        selection_id: *retry_id,
    });
    assert!(!state.transcript.borrow().has_selection());
    assert!(state.transcript.borrow().is_browsing());
}

/// 生成的回复/列表前缀不得消耗源前导空格；Tab 的多个显示列复制时还原原始字节。
#[test]
fn selection_source_mapping_skips_generated_prefix_and_restores_tab() {
    let mut bullet = Transcript::default();
    let source = "   item";
    bullet.update_layout(
        40,
        Rect::new(0, 0, 40, 4),
        Rect::new(0, 4, 40, 1),
        vec![TranscriptBlock {
            key: "bullet".into(),
            source_key: "bullet".into(),
            source_start: 0,
            source: source.into(),
            lines: vec![Line::from(vec![
                Span::raw("  • ".to_owned()),
                Span::raw(source.to_owned()),
            ])],
        }],
    );
    select_transcript_range(&mut bullet, 0, 4, 6);
    let (_, copied_bullet_indent) = bullet
        .selection_copy()
        .expect("source indentation remains independently selectable");
    assert_eq!(copied_bullet_indent, "   ");

    let mut tabbed = Transcript::default();
    let source = "\tvalue";
    tabbed.update_layout(
        40,
        Rect::new(0, 0, 40, 4),
        Rect::new(0, 4, 40, 1),
        vec![TranscriptBlock {
            key: "tabbed".into(),
            source_key: "tabbed".into(),
            source_start: 0,
            source: source.into(),
            lines: vec![Line::from(vec![
                Span::raw("  ".to_owned()),
                Span::raw("    value".to_owned()),
            ])],
        }],
    );
    select_transcript_range(&mut tabbed, 0, 2, 10);
    let (_, copied_tab) = tabbed
        .selection_copy()
        .expect("expanded Tab and its following text form one selectable source range");
    assert_eq!(copied_tab, "\tvalue");
}

/// 实际灰底 User 单 Span 含提示符前缀，选择正文前导空格时仍须回读原始完整字符串。
#[test]
fn user_prompt_prefix_does_not_consume_leading_source_spaces() {
    let source = "  leading";
    let entry = entry(
        "leading-user",
        source,
        TimelineKind::User,
        TimelineStatus::Complete,
    );
    let lines = render::transcript_entry_lines(&entry, 40);
    let mut transcript = Transcript::default();
    transcript.update_layout(
        40,
        Rect::new(0, 0, 40, 5),
        Rect::new(0, 5, 40, 1),
        vec![TranscriptBlock {
            key: entry.id.clone(),
            source_key: entry.id,
            source_start: 0,
            source: source.into(),
            lines,
        }],
    );
    let screen_rows = rows(&draw(&transcript, 40, 5));
    let (row_index, row) = screen_rows
        .iter()
        .enumerate()
        .find(|(_, row)| row.contains("leading"))
        .expect("user source should be visible with its real composer prompt");
    let prompt_start = row.find('›').expect("user prompt marker");
    let source_column =
        UnicodeWidthStr::width(&row[..prompt_start]) as u16 + UnicodeWidthStr::width("› ") as u16;
    select_transcript_range(
        &mut transcript,
        row_index as u16,
        source_column,
        source_column + UnicodeWidthStr::width(source) as u16 - 1,
    );
    let (_, copied) = transcript
        .selection_copy()
        .expect("the actual prompt does not prevent source text selection");
    assert_eq!(copied, source);
}

/// 实际 Assistant 行跨 source identity 选择时使用可读段落间距，不把 UI 空白或消息直接粘连。
#[test]
fn selection_copy_separates_distinct_assistant_messages() {
    let first = entry(
        "first-message",
        "FIRST_MESSAGE_BODY",
        TimelineKind::Assistant,
        TimelineStatus::Complete,
    );
    let second = entry(
        "second-message",
        "SECOND_MESSAGE_BODY",
        TimelineKind::Assistant,
        TimelineStatus::Complete,
    );
    let mut transcript = Transcript::default();
    transcript.update_layout(
        48,
        Rect::new(0, 0, 48, 20),
        Rect::new(0, 20, 48, 1),
        Vec::new(),
    );
    transcript.append(first.clone(), first.id.clone(), 0);
    transcript.append(second.clone(), second.id.clone(), 0);
    select_visible_markers(
        &mut transcript,
        48,
        20,
        "FIRST_MESSAGE_BODY",
        "SECOND_MESSAGE_BODY",
    );
    let (_, copied) = transcript
        .selection_copy()
        .expect("dragging over two assistant messages should create a source selection");
    assert_eq!(copied, "FIRST_MESSAGE_BODY\n\nSECOND_MESSAGE_BODY");
}

/// 同一 Markdown 源跨稳定正文和活动后缀复制时保留块间距并去掉标题、列表及隐藏链接 URL。
#[test]
fn selection_copy_preserves_markdown_block_breaks_across_stable_and_active_content() {
    let source = "Paragraph [linked label](https://example.test/private) marker\n\n## Heading marker\n\n- List marker";
    let active_start = source.find("- List marker").unwrap();
    let stable_source = &source[..active_start];
    let active_source = &source[active_start..];
    let stable_entry = entry(
        "stable-markdown-prefix",
        stable_source,
        TimelineKind::Assistant,
        TimelineStatus::Complete,
    );
    let active_entry = entry(
        "active-markdown-suffix",
        active_source,
        TimelineKind::Assistant,
        TimelineStatus::Running,
    );
    let mut transcript = Transcript::default();
    transcript.register_source("markdown-message".into(), 0, source.into());
    transcript.update_layout(
        48,
        Rect::new(0, 0, 48, 24),
        Rect::new(0, 24, 48, 1),
        Vec::new(),
    );
    transcript.append(stable_entry, "markdown-message".into(), 0);
    transcript.update_layout(
        48,
        Rect::new(0, 0, 48, 24),
        Rect::new(0, 24, 48, 1),
        vec![TranscriptBlock {
            key: active_entry.id.clone(),
            source_key: "markdown-message".into(),
            source_start: active_start,
            source: active_source.into(),
            lines: render::transcript_entry_lines(&active_entry, 48),
        }],
    );
    select_visible_markers(&mut transcript, 48, 24, "Paragraph", "List marker");
    let (_, copied) = transcript
        .selection_copy()
        .expect("selection should span Markdown content across stable and active blocks");
    assert_eq!(
        copied,
        "Paragraph linked label (https://example.test/private) marker\n\nHeading marker\n\nList marker"
    );
    assert!(!copied.contains('#'), "heading syntax leaked: {copied:?}");
    assert!(!copied.contains("- "), "list syntax leaked: {copied:?}");
    assert!(
        !copied.contains("]("),
        "Markdown link syntax leaked: {copied:?}"
    );
}

/// 同源软折行不合成换行，重排宽度后同一源端点仍复制原连续字串。
#[test]
fn selection_copy_does_not_insert_soft_wrap_newlines_after_resize() {
    let item = entry(
        "soft-wrap",
        "SOFTWRAP_CONTIGUOUS_BODY",
        TimelineKind::Assistant,
        TimelineStatus::Complete,
    );
    let block = |width| TranscriptBlock {
        key: item.id.clone(),
        source_key: item.id.clone(),
        source_start: 0,
        source: item.text.clone(),
        lines: render::transcript_entry_lines(&item, width),
    };
    let mut transcript = Transcript::default();
    transcript.update_layout(
        12,
        Rect::new(0, 0, 12, 24),
        Rect::new(0, 24, 12, 1),
        vec![block(12)],
    );
    select_visible_markers(&mut transcript, 12, 24, "SOFTWRAP", "BODY");
    let first_copy = transcript.selection_copy().unwrap().1;
    assert_eq!(first_copy, item.text);

    transcript.update_layout(
        20,
        Rect::new(0, 0, 20, 24),
        Rect::new(0, 24, 20, 1),
        vec![block(20)],
    );
    let resized_copy = transcript
        .selection_copy()
        .expect("source endpoints should survive reflow")
        .1;
    assert_eq!(resized_copy, item.text);
    assert!(!resized_copy.contains('\n'));
}

/// 正文源锚点跨宽度重排和旧页前插保持同一可读行，分页以条目身份去重。
#[test]
fn source_anchor_survives_resize_and_deduplicated_prepend() {
    let mut transcript = Transcript::default();
    transcript.append(
        entry(
            "anchor",
            &(0..24)
                .map(|index| format!("ANCHOR_{index:02} 中文🙂\n"))
                .collect::<String>(),
            TimelineKind::Assistant,
            TimelineStatus::Complete,
        ),
        "answer-source".into(),
        0,
    );
    layout(&mut transcript, 32, 4);
    transcript.jump_top();
    transcript.scroll_by(-3);
    let before_resize = rows(&draw(&transcript, 32, 4));
    assert!(before_resize.iter().any(|row| row.contains("ANCHOR_00")));
    transcript.update_layout(
        16,
        Rect::new(0, 0, 16, 4),
        Rect::new(0, 4, 16, 1),
        Vec::new(),
    );
    let resized = rows(&draw(&transcript, 16, 4));
    assert!(transcript.is_browsing());
    assert!(
        resized.iter().any(|row| row.contains("ANCHOR_")),
        "{resized:?}"
    );

    let anchor_row = resized
        .iter()
        .position(|row| row.contains("ANCHOR_00"))
        .expect("the source anchor should remain visible after width reflow");
    let duplicate = entry(
        "anchor",
        "replacement duplicate must not appear",
        TimelineKind::Assistant,
        TimelineStatus::Complete,
    );
    transcript.prepend(vec![
        entry(
            "older-1",
            "OLDER_1",
            TimelineKind::Assistant,
            TimelineStatus::Complete,
        ),
        entry(
            "older-2",
            "OLDER_2",
            TimelineKind::Assistant,
            TimelineStatus::Complete,
        ),
        duplicate,
        entry(
            "older-1",
            "duplicate page item",
            TimelineKind::Assistant,
            TimelineStatus::Complete,
        ),
    ]);
    let after_prepend = rows(&draw(&transcript, 16, 4));
    assert!(transcript.is_browsing());
    assert_eq!(
        after_prepend
            .iter()
            .position(|row| row.contains("ANCHOR_00")),
        Some(anchor_row),
        "the prepend must preserve the same source line in the same viewport row: {after_prepend:?}"
    );
    layout(&mut transcript, 16, 120);
    let all_rows = rows(&draw(&transcript, 16, 120));
    let all_text = all_rows.join("\n");
    for marker in ["OLDER_1", "OLDER_2", "ANCHOR_00"] {
        assert_eq!(all_text.matches(marker).count(), 1, "{all_text}");
    }
    assert!(!all_text.contains("replacement duplicate"), "{all_text}");
    assert!(all_text.find("OLDER_1").unwrap() < all_text.find("OLDER_2").unwrap());
    assert!(all_text.find("OLDER_2").unwrap() < all_text.find("ANCHOR_00").unwrap());
}

/// 浏览新流只刷新提示；Ctrl+End 清提示并跟随最新正文，输入编辑状态完全独立。
#[test]
fn reading_anchor_stays_put_while_stream_grows_then_returns_to_latest() {
    let mut state = history_state("thread-reading", false, 32);
    state.handle_paste("草稿🙂");
    let cursor = state.composer().cursor();
    let _ = draw_state(&mut state, 80, 18);
    state.handle_key(KeyEvent::new(KeyCode::Home, KeyModifiers::CONTROL));
    let before = rows(&draw_state(&mut state, 80, 18));

    state.apply(UiEvent::UpsertEntry(entry(
        "live:reading:1",
        "",
        TimelineKind::Assistant,
        TimelineStatus::Running,
    )));
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "live:reading:1".into(),
        delta: (0..12)
            .map(|index| format!("NEW_{index:02} 🙂\n"))
            .collect(),
    });
    let during = rows(&draw_state(&mut state, 80, 18));
    assert!(state.transcript.borrow().is_browsing());
    assert!(during.iter().any(|row| row.contains("有新内容")));
    let before_history = before
        .iter()
        .filter(|row| row.contains("HISTORY_"))
        .cloned()
        .collect::<Vec<_>>();
    let during_history = during
        .iter()
        .filter(|row| row.contains("HISTORY_"))
        .cloned()
        .collect::<Vec<_>>();
    assert_eq!(before_history, during_history);
    assert_eq!(state.composer().text(), "草稿🙂");
    assert_eq!(state.composer().cursor(), cursor);

    assert!(
        state
            .handle_key(KeyEvent::new(KeyCode::End, KeyModifiers::CONTROL))
            .is_empty()
    );
    let latest = rows(&draw_state(&mut state, 80, 18));
    assert!(!state.transcript.borrow().is_browsing());
    assert!(latest.iter().any(|row| row.contains("NEW_11")));
    assert!(!latest.iter().any(|row| row.contains("有新内容")));
    assert_eq!(state.composer().text(), "草稿🙂");
    assert_eq!(state.composer().cursor(), cursor);
}

/// 旧页只对当前 Thread 生效；失败后 F5 重试同一目标并以一页身份顺序前插。
#[test]
fn older_page_failure_retry_prepend_and_late_thread_result_are_closed_loop() {
    let mut state = history_state("thread-page", true, 20);
    let _ = draw_state(&mut state, 80, 18);
    let request = state.handle_key(KeyEvent::new(KeyCode::Home, KeyModifiers::CONTROL));
    assert_eq!(
        request,
        vec![UiAction::LoadOlder {
            thread_id: "thread-page".into()
        }]
    );
    let existing_rows = rows(&draw_state(&mut state, 80, 18));
    let before_prepend = rows(&draw_state(&mut state, 80, 120)).join("\n");
    let existing_history_count = (0..20)
        .filter(|index| before_prepend.contains(&format!("HISTORY_{index:03}")))
        .count();
    assert_eq!(existing_history_count, 20, "{before_prepend}");

    state.apply(UiEvent::OlderHistoryFailed {
        thread_id: "thread-page".into(),
        notice: "历史暂时不可读".into(),
    });
    assert_eq!(
        state.handle_key(KeyEvent::new(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::LoadOlder {
            thread_id: "thread-page".into()
        }]
    );
    let older = entry(
        "older-page-1",
        "OLDER_PAGE_1",
        TimelineKind::Assistant,
        TimelineStatus::Complete,
    );
    state.apply(UiEvent::OlderHistoryLoaded {
        thread_id: "another-thread".into(),
        entries: vec![older.clone()],
        has_older: false,
    });
    let after_wrong_thread = rows(&draw_state(&mut state, 80, 120)).join("\n");
    assert_eq!(after_wrong_thread, before_prepend);

    state.apply(UiEvent::OlderHistoryLoaded {
        thread_id: "thread-page".into(),
        entries: vec![
            older,
            entry(
                "older-page-1",
                "duplicate page row",
                TimelineKind::Assistant,
                TimelineStatus::Complete,
            ),
        ],
        has_older: true,
    });
    let all_after = rows(&draw_state(&mut state, 80, 120)).join("\n");
    assert_eq!(all_after.matches("OLDER_PAGE_1").count(), 1, "{all_after}");
    assert!(!all_after.contains("duplicate page row"), "{all_after}");
    assert!(all_after.find("OLDER_PAGE_1").unwrap() < all_after.find("HISTORY_000").unwrap());
    let after = rows(&draw_state(&mut state, 80, 18));
    let existing_history = existing_rows
        .iter()
        .filter(|row| row.contains("HISTORY_"))
        .cloned()
        .collect::<Vec<_>>();
    let after_history = after
        .iter()
        .filter(|row| row.contains("HISTORY_"))
        .cloned()
        .collect::<Vec<_>>();
    let common_visible = existing_history.len().min(after_history.len());
    assert_eq!(
        &after_history[..common_visible],
        &existing_history[..common_visible],
        "overlapping visible history rows retain their source positions after prepend"
    );
    assert!(state.snapshot().has_older_history);

    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thread-switched".into()),
        timeline: vec![entry(
            "new-thread-entry",
            "NEW_THREAD_ONLY",
            TimelineKind::Assistant,
            TimelineStatus::Complete,
        )],
        ..UiSnapshot::default()
    })));
    state.apply(UiEvent::OlderHistoryLoaded {
        thread_id: "thread-page".into(),
        entries: vec![entry(
            "late-old-thread-entry",
            "MUST_NOT_APPEAR",
            TimelineKind::Assistant,
            TimelineStatus::Complete,
        )],
        has_older: false,
    });
    let switched = rows(&draw_state(&mut state, 80, 24)).join("\n");
    assert!(switched.contains("NEW_THREAD_ONLY"), "{switched}");
    assert!(!switched.contains("OLDER_PAGE_1"), "{switched}");
    assert!(!switched.contains("HISTORY_000"), "{switched}");
    assert!(!switched.contains("MUST_NOT_APPEAR"), "{switched}");
}

/// 让所有状态行都走生产 frame renderer，并在调用前同步实际窗口尺寸。
fn draw_state(state: &mut UiState, width: u16, height: u16) -> Buffer {
    state.set_terminal_size(width, height);
    let mut terminal = Terminal::new(TestBackend::new(width.max(1), height.max(1))).unwrap();
    terminal
        .draw(|frame| render::render(frame, state))
        .expect("UI should render to the application-owned frame");
    terminal.backend().buffer().clone()
}

/// 鼠标输入明确携带物理坐标；测试中的正文行从实际渲染 buffer 读取而不是硬编码。
fn mouse(kind: MouseEventKind, column: u16, row: u16) -> MouseEvent {
    MouseEvent {
        kind,
        column,
        row,
        modifiers: KeyModifiers::NONE,
    }
}

/// 鼠标选择按显示列驱动真实 Transcript 命中测试，避免源偏移用例依赖私有光标实现。
fn select_transcript_range(transcript: &mut Transcript, row: u16, start: u16, end: u16) {
    for event in [
        mouse(MouseEventKind::Down(MouseButton::Left), start, row),
        mouse(MouseEventKind::Drag(MouseButton::Left), end, row),
        mouse(MouseEventKind::Up(MouseButton::Left), end, row),
    ] {
        assert!(transcript.handle_mouse(event));
    }
}

/// 从真实显示行的正文标记定位端点，避免测试以源码偏移代替鼠标命中路径。
fn select_visible_markers(
    transcript: &mut Transcript,
    width: u16,
    body_height: u16,
    first_marker: &str,
    last_marker: &str,
) {
    let screen = rows(&draw(transcript, width, body_height));
    let (first_row, first_column) = screen
        .iter()
        .enumerate()
        .find_map(|(index, row)| {
            let start = row.find(first_marker)?;
            Some((index as u16, UnicodeWidthStr::width(&row[..start]) as u16))
        })
        .unwrap_or_else(|| panic!("first marker {first_marker:?} missing: {screen:?}"));
    let (last_row, last_end_column) = screen
        .iter()
        .enumerate()
        .find_map(|(index, row)| {
            let start = row.find(last_marker)?;
            Some((
                index as u16,
                UnicodeWidthStr::width(&row[..start]) as u16
                    + UnicodeWidthStr::width(last_marker) as u16
                    - 1,
            ))
        })
        .unwrap_or_else(|| panic!("last marker {last_marker:?} missing: {screen:?}"));
    for event in [
        mouse(
            MouseEventKind::Down(MouseButton::Left),
            first_column,
            first_row,
        ),
        mouse(
            MouseEventKind::Drag(MouseButton::Left),
            last_end_column,
            last_row,
        ),
        mouse(
            MouseEventKind::Up(MouseButton::Left),
            last_end_column,
            last_row,
        ),
    ] {
        assert!(
            transcript.handle_mouse(event),
            "transcript should own selection"
        );
    }
}
