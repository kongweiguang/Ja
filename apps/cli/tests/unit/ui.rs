// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::time::{Duration, Instant};
use std::{
    sync::{Arc, mpsc},
    thread,
};

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ja_cli::ui::{
    EventDelivery, InteractionQuestion, InteractionQuestionKind, PendingPrompt, TimelineEntry,
    TimelineKind, TimelineStatus, TurnState, UiAction, UiChoice, UiCommand, UiEvent, UiEventSender,
    UiSnapshot, UiState, insert_scrollback, render,
};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::layout::Rect;
use ratatui::style::Color;
use ratatui::{TerminalOptions, Viewport};
use unicode_segmentation::UnicodeSegmentation;

/// 字素级编辑删除整个家庭 Emoji 和组合音标，不切开 Unicode 字符。
#[test]
fn composer_edits_graphemes() {
    let mut composer = ja_cli::ui::Composer::new();
    composer.insert_text("A👨‍👩‍👧‍👦e\u{301}");
    composer.backspace();
    assert_eq!(composer.text(), "A👨‍👩‍👧‍👦");
    composer.backspace();
    assert_eq!(composer.text(), "A");
}

/// 短行往返保留目标列，宽字符和软折行只在字素边界移动。
#[test]
fn composer_vertical_navigation_preserves_display_column() {
    let mut composer = ja_cli::ui::Composer::new();
    composer.insert_text("ab中文\nx\nabcdef");
    composer.move_left();
    composer.move_vertical(12, false);
    let middle = composer.cursor();
    composer.move_vertical(12, true);
    assert_eq!(
        composer.cursor(),
        "ab中文\nx\nabcde".graphemes(true).count()
    );
    composer.move_vertical(12, false);
    assert_eq!(composer.cursor(), middle);
}

/// Ctrl+词移动和删除必须可以一步撤销，不能把组合字形切成 UTF-8 残片。
#[test]
fn composer_word_edit_and_undo_restore_cursor() {
    let mut composer = ja_cli::ui::Composer::new();
    composer.insert_text("修复 tests e\u{301}");
    let before = composer.cursor();
    composer.delete_word_left();
    assert_eq!(composer.text(), "修复 tests ");
    composer.undo();
    assert_eq!(composer.text(), "修复 tests e\u{301}");
    assert_eq!(composer.cursor(), before);
    composer.redo();
    assert_eq!(composer.text(), "修复 tests ");
}

/// 同名与前缀相似文本只绑定被选择的区间，删除后撤销恢复相同身份。
#[test]
fn composer_reference_identity_tracks_edits_and_undo() {
    let mut composer = ja_cli::ui::Composer::new();
    composer.insert_text("@a @ab");
    composer.replace_with_reference(
        0,
        2,
        "@ab",
        ja_cli::ui::UiReferenceKind::Workspace {
            workspace_id: "ws_test".into(),
            relative_path: "ab".into(),
            kind: "file".into(),
        },
        false,
    );
    assert_eq!(composer.text(), "@ab @ab");
    assert_eq!(composer.references().len(), 1);
    assert_eq!(
        (composer.references()[0].start, composer.references()[0].end),
        (0, 3)
    );
    composer.home();
    composer.move_right();
    composer.delete();
    assert!(composer.references().is_empty());
    composer.undo();
    assert_eq!(composer.references().len(), 1);
    assert_eq!(composer.text(), "@ab @ab");
}

/// 粘贴模式中的换行进入草稿，只有 controller 确认后才清空。
#[test]
fn pasted_newlines_do_not_send_until_enter_and_ack() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("第一行\n第二行");
    assert!(
        state
            .handle_key(key(KeyCode::Char('x'), KeyModifiers::NONE))
            .is_empty()
    );
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(
        actions,
        vec![UiAction::Submit {
            text: "第一行\n第二行x".to_owned(),
            references: vec![],
            mode: ja_cli::ui::UiSubmitMode::Immediate,
        }]
    );
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.apply(UiEvent::ClearDraft);
    assert!(state.composer().is_empty());
}

/// 活动 Turn 的 Enter 和 Tab 必须保留不同的用户意图，不能都退化为普通队列。
#[test]
fn active_turn_enter_steers_and_tab_queues() {
    let mut steer = UiState::new(UiSnapshot {
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    });
    steer.handle_paste("调整方向");
    assert!(matches!(
        steer
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::Submit {
            mode: ja_cli::ui::UiSubmitMode::Steer,
            ..
        }]
    ));
    let mut queue = UiState::new(UiSnapshot {
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    });
    queue.handle_paste("下一步");
    assert!(matches!(
        queue
            .handle_key(key(KeyCode::Tab, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::Submit {
            mode: ja_cli::ui::UiSubmitMode::Queue,
            ..
        }]
    ));
}

/// 粘贴中的 ANSI 控制字节不会进入终端 cell，CRLF 与 Tab 转成可见正文。
#[test]
fn pasted_terminal_controls_are_sanitized() {
    let mut composer = ja_cli::ui::Composer::new();
    composer.insert_text("ok\u{1b}[31m\tline\r\nnext");
    assert_eq!(composer.text(), "ok[31m    line\nnext");
}

/// 大段粘贴只接受完整字素并受固定草稿上限约束。
#[test]
fn composer_draft_budget_is_bounded() {
    let mut composer = ja_cli::ui::Composer::new();
    assert!(!composer.insert_text(&"界".repeat(20_000)));
    assert!(composer.text().len() <= 32 * 1024);
    assert!(composer.text().is_char_boundary(composer.text().len()));
}

/// 超限粘贴不能把提示词截一半后误发送，界面明确保留原草稿。
#[test]
fn oversized_paste_keeps_existing_draft_and_shows_notice() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("原草稿");
    state.handle_paste(&"界".repeat(20_000));
    assert_eq!(state.composer().text(), "原草稿");
    assert!(
        state
            .snapshot()
            .notice
            .as_deref()
            .unwrap_or("")
            .contains("未改变")
    );
}

/// 裸 ASCII 键事件形成的快速粘贴 burst 中，紧随其后的回车不会误触发送。
#[test]
fn plain_key_paste_burst_suppresses_enter_send() {
    let mut state = UiState::new(UiSnapshot::default());
    for ch in "rapidpaste".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(state.composer().text(), "rapidpaste\n");
}

/// 截断和终端控制字符清理同时作用于权威 snapshot 与后续 delta。
#[test]
fn streamed_text_is_sanitized_and_bounded() {
    let mut state = UiState::new(UiSnapshot {
        timeline: vec![TimelineEntry {
            id: "m1".to_owned(),
            kind: TimelineKind::Assistant,
            text: String::new(),
            detail: None,
            status: None,
        }],
        ..UiSnapshot::default()
    });
    state.apply(UiEvent::AppendAssistantDelta {
        entry_id: "m1".to_owned(),
        delta: "ok\u{1b}[2J".to_owned(),
    });
    assert_eq!(state.snapshot().timeline[0].text, "ok[2J");
    state.apply(UiEvent::AppendAssistantDelta {
        entry_id: "missing".to_owned(),
        delta: "must not fabricate".to_owned(),
    });
    assert_eq!(state.snapshot().timeline.len(), 1);
}

/// 历史审批选项回传原有 revision 和 ID，TUI 不合成服务端审批决定。
#[test]
fn tool_approval_action_keeps_owner_revision() {
    let mut state = UiState::new(UiSnapshot {
        pending_prompt: Some(PendingPrompt::ToolApproval {
            approval_id: "approval_1".to_owned(),
            thread_id: "thr_1".to_owned(),
            turn_id: "turn_1".to_owned(),
            expected_thread_revision: 42,
            prompt: "允许执行吗？".to_owned(),
            choices: vec![choice("allow", "允许"), choice("deny", "拒绝")],
        }),
        ..UiSnapshot::default()
    });
    assert!(
        state
            .handle_key(key(KeyCode::Down, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::RespondToolApproval {
            approval_id: "approval_1".to_owned(),
            thread_id: "thr_1".to_owned(),
            turn_id: "turn_1".to_owned(),
            expected_thread_revision: 42,
            choice_id: "deny".to_owned(),
        }]
    );
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
}

/// 多道 interaction 题在全部完成前不发送，最后一次动作回传完整 answers。
#[test]
fn clarification_submits_complete_answer_set_once() {
    let mut state = UiState::new(UiSnapshot {
        pending_prompt: Some(PendingPrompt::Clarification {
            thread_id: "thr_1".to_owned(),
            request_id: "req_1".to_owned(),
            expected_revision: 9,
            idempotency_key: "idem_1".to_owned(),
            questions: vec![
                InteractionQuestion {
                    question_id: "q1".to_owned(),
                    prompt: "选择一个".to_owned(),
                    kind: InteractionQuestionKind::Single,
                    options: vec![choice("a", "A"), choice("b", "B")],
                    allow_skip: false,
                    allow_free_text: false,
                },
                InteractionQuestion {
                    question_id: "q2".to_owned(),
                    prompt: "补充说明".to_owned(),
                    kind: InteractionQuestionKind::Text,
                    options: Vec::new(),
                    allow_skip: false,
                    allow_free_text: true,
                },
            ],
        }),
        ..UiSnapshot::default()
    });
    assert!(
        state
            .handle_key(key(KeyCode::Down, KeyModifiers::NONE))
            .is_empty()
    );
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.handle_paste("需要时间");
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(
        actions,
        vec![UiAction::RespondInteraction {
            thread_id: "thr_1".to_owned(),
            request_id: "req_1".to_owned(),
            expected_revision: 9,
            idempotency_key: "idem_1".to_owned(),
            answers: vec![
                ja_cli::ui::InteractionAnswer {
                    question_id: "q1".to_owned(),
                    option_ids: vec!["b".to_owned()],
                    free_text: None,
                    skipped: false,
                },
                ja_cli::ui::InteractionAnswer {
                    question_id: "q2".to_owned(),
                    option_ids: Vec::new(),
                    free_text: Some("需要时间".to_owned()),
                    skipped: false,
                },
            ],
        }]
    );
}

/// 事件洪峰不扩大缓冲，溢出返回重读信号且完整快照可以覆盖旧增量。
#[test]
fn event_queue_stays_bounded_and_requests_snapshot() {
    let events = UiEventSender::default();
    let mut saw_overflow = false;
    for index in 0..100_000 {
        let delivery = events
            .send(UiEvent::UpsertEntry(TimelineEntry {
                id: format!("entry-{index}"),
                kind: TimelineKind::Assistant,
                text: "x".to_owned(),
                detail: None,
                status: None,
            }))
            .expect("non-blocking projection send should stay available");
        saw_overflow |= delivery == EventDelivery::SnapshotRequired;
        assert!(events.buffered_len() <= 32);
    }
    assert!(saw_overflow);
    assert_eq!(
        events.take_snapshot_refresh_action(),
        Some(UiAction::RefreshSnapshot)
    );
    assert_eq!(events.take_snapshot_refresh_action(), None);
    events
        .send(UiEvent::ReplaceSnapshot(Box::default()))
        .expect("authoritative snapshot must recover after overflow");
    assert_eq!(events.buffered_len(), 1);
}

/// 同类状态事件在压力下覆盖旧值，让终端只追到最新确定状态。
#[test]
fn event_queue_coalesces_status_updates() {
    let events = UiEventSender::default();
    for index in 0..10_000 {
        let state = if index % 2 == 0 {
            TurnState::Working
        } else {
            TurnState::Idle
        };
        events.send(UiEvent::SetTurnState(state)).unwrap();
    }
    assert_eq!(events.buffered_len(), 1);
}

/// 渲染器在窄屏和常用宽度下均绘制合法 buffer。
#[test]
fn renderer_handles_small_and_wide_terminals() {
    for (width, height) in [(24, 8), (100, 24)] {
        let backend = TestBackend::new(width, height);
        let mut terminal = Terminal::new(backend).unwrap();
        let state = UiState::new(UiSnapshot::default());
        terminal.draw(|frame| render(frame, &state)).unwrap();
        let output = terminal
            .backend()
            .buffer()
            .content()
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>();
        assert!(output.contains("Ja"));
        if width == 100 {
            let compact = output
                .chars()
                .filter(|ch| !ch.is_whitespace())
                .collect::<String>();
            assert!(compact.contains("让Ja完成任何任务"), "{output}");
        }
    }
}

/// 首屏保留卡片到输入区的呼吸空间；普通对话与选择器紧贴底部操作行。
#[test]
fn inline_layout_anchors_interaction_at_terminal_bottom() {
    let base = UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_identifier: Some("gpt-6-sol".to_owned()),
        workspace_path: Some("C:\\dev\\rust\\ja".to_owned()),
        model_choices: vec![choice("model_a", "gpt-6-sol")],
        ..UiSnapshot::default()
    };
    let welcome = screen_rows(&UiState::new(base.clone()), 80, 24);
    assert!(welcome[0].contains('╭'), "{welcome:?}");
    assert!(
        welcome[9].replace(' ', "").contains("提示：/命令"),
        "{welcome:?}"
    );
    assert!(
        welcome[21].replace(' ', "").contains("让Ja完成任何任务"),
        "{welcome:?}"
    );
    assert!(welcome[23].contains("gpt-6-sol"), "{welcome:?}");
    assert!(welcome[12..20].iter().all(|row| row.trim().is_empty()));

    let mut conversation = base.clone();
    conversation.timeline.push(TimelineEntry {
        id: "active-user".to_owned(),
        kind: TimelineKind::User,
        text: "检查测试".to_owned(),
        detail: None,
        status: Some(TimelineStatus::Running),
    });
    let conversation = screen_rows(&UiState::new(conversation), 80, 24);
    let message_row = conversation
        .iter()
        .position(|row| row.replace(' ', "").contains("检查测试"))
        .unwrap();
    assert!(message_row >= 17, "{conversation:?}");
    assert!(
        conversation[21]
            .replace(' ', "")
            .contains("让Ja完成任何任务")
    );
    assert!(conversation[23].contains("gpt-6-sol"));

    let mut picker = UiState::new(base);
    picker.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        picker.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    picker.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let picker = screen_rows(&picker, 80, 24);
    assert!(
        picker[19..23]
            .iter()
            .any(|row| row.replace(' ', "").contains("选择模型"))
    );
    assert!(picker[23].replace(' ', "").contains("Enter确认"));
}

/// 工作提示紧接回复；宽屏将取消快捷键并入状态行，窄屏和草稿态保留可用操作提示。
#[test]
fn working_indicator_follows_reply_and_animates_without_footer_duplication() {
    let snapshot = UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        turn_state: TurnState::Working,
        model_identifier: Some("gpt-6-sol".to_owned()),
        workspace_path: Some("C:\\dev\\rust\\ja".to_owned()),
        timeline: vec![TimelineEntry {
            id: "progress".to_owned(),
            kind: TimelineKind::Commentary,
            text: "正在检查项目".to_owned(),
            detail: None,
            status: Some(TimelineStatus::Running),
        }],
        ..UiSnapshot::default()
    };
    let mut state = UiState::new(snapshot.clone());
    let first = working_indicator_dot_colors(&state);
    let deadline = Instant::now() + Duration::from_secs(2);
    let mut next = first.clone();
    while next == first && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(40));
        next = working_indicator_dot_colors(&state);
    }
    assert_eq!(
        next.len(),
        3,
        "working indicator should render three dots; snapshot={:?}, rows={:?}",
        state.snapshot(),
        screen_rows(&state, 80, 16)
    );
    assert_ne!(first, next, "活动点应在持续刷新时轮换");

    let rows = screen_rows(&state, 80, 16);
    let compact_row = |row: &str| {
        row.chars()
            .filter(|character| !character.is_whitespace())
            .collect::<String>()
    };
    let reply_row = rows
        .iter()
        .position(|row| compact_row(row).contains("正在检查项目"))
        .expect("回复正文应在可见历史中");
    let working_row = rows
        .iter()
        .position(|row| compact_row(row).contains("正在工作"))
        .expect("工作提示应在回复之后");
    let footer_row = rows
        .iter()
        .position(|row| row.contains("gpt-6-sol"))
        .expect("底栏应保留模型信息");
    assert!(
        reply_row < working_row && working_row < footer_row,
        "{rows:?}"
    );
    assert!(
        !compact_row(&rows[footer_row]).contains("正在工作"),
        "{rows:?}"
    );
    assert!(
        compact_row(&rows[working_row]).contains("Esc中断"),
        "{rows:?}"
    );
    assert!(
        !compact_row(&rows[footer_row]).contains("Esc中断"),
        "{rows:?}"
    );

    state.handle_paste("后续补充");
    let draft_rows = screen_rows(&state, 80, 16);
    let draft_status = draft_rows
        .iter()
        .find(|row| compact_row(row).contains("正在工作"))
        .expect("草稿输入期间仍应显示工作状态");
    let draft_footer = draft_rows
        .iter()
        .find(|row| row.contains("gpt-6-sol"))
        .expect("底栏应保留模型信息");
    assert!(
        !compact_row(draft_status).contains("Esc中断"),
        "{draft_rows:?}"
    );
    assert!(
        compact_row(draft_footer).contains("Enter调整")
            && compact_row(draft_footer).contains("Tab排队"),
        "{draft_rows:?}"
    );

    let narrow_state = UiState::new(snapshot);
    let narrow_rows = screen_rows(&narrow_state, 24, 10);
    let narrow_status = narrow_rows
        .iter()
        .find(|row| compact_row(row).contains("正在工作"))
        .expect("窄屏也应显示工作状态");
    let narrow_footer = narrow_rows
        .iter()
        .find(|row| compact_row(row).contains("Esc中断"))
        .expect("窄屏底栏应保留取消快捷键");
    assert!(
        !compact_row(narrow_status).contains("Esc中断"),
        "{narrow_rows:?}"
    );
    assert!(
        compact_row(narrow_footer).contains("Esc中断"),
        "{narrow_rows:?}"
    );
}

/// 菜单挤压时保留完整卡片或整块让位，不能留下孤立的欢迎框边缘。
#[test]
fn compact_selector_never_clips_welcome_card() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_identifier: Some("gpt-6-sol".to_owned()),
        model_choices: vec![choice("model_a", "gpt-6-sol")],
        ..UiSnapshot::default()
    });
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    let medium = screen_rows(&state, 80, 16);
    assert!(medium[0].contains('╭'), "{medium:?}");
    assert!(medium.iter().any(|row| row.contains('╰')), "{medium:?}");
    assert!(
        !medium
            .iter()
            .any(|row| row.replace(' ', "").contains("提示："))
    );
    assert!(
        medium.iter().any(|row| row.contains("/model")),
        "{medium:?}"
    );
    let narrow = screen_rows(&state, 30, 10);
    assert!(
        !narrow
            .iter()
            .any(|row| row.contains('╭') || row.contains('╰'))
    );
    assert!(
        narrow.iter().any(|row| row.contains("/model")),
        "{narrow:?}"
    );

    for ch in "model".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let medium = screen_rows(&state, 80, 16);
    assert!(medium[0].contains('╭'), "{medium:?}");
    assert!(medium.iter().any(|row| row.contains('╰')), "{medium:?}");
    let narrow = screen_rows(&state, 30, 10);
    assert!(
        !narrow
            .iter()
            .any(|row| row.contains('╭') || row.contains('╰'))
    );
    assert!(
        narrow.iter().any(|row| row.contains("gpt-6-sol")),
        "{narrow:?}"
    );
}

/// 窄屏隐藏的长说明可通过 F2 完整浏览，返回仍确认原来选中的模型。
#[test]
fn model_description_viewer_restores_selected_choice() {
    let long_description = format!("{}\n结尾：适合复杂任务", "能力与限制说明。".repeat(30));
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_choices: vec![
            choice("model_a", "gpt-6-sol"),
            UiChoice {
                id: "model_b".to_owned(),
                label: "gpt-6-astra".to_owned(),
                detail: Some(long_description),
            },
        ],
        ..UiSnapshot::default()
    });
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    assert!(screen_text(&state, 40, 15).contains("F2"));
    state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    state.handle_key(key(KeyCode::End, KeyModifiers::NONE));
    let detail = screen_text(&state, 40, 15).replace(' ', "");
    assert!(detail.contains("适合复杂任务"), "{detail}");
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    let picker = screen_text(&state, 40, 15).replace(' ', "");
    assert!(picker.contains("选择模型"), "{picker}");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectModel {
            id: "model_b".to_owned()
        }]
    );
}

/// 模型列表刷新时详情始终绑定原 ID；原项消失后不允许回到邻近项直接确认。
#[test]
fn model_detail_survives_reorder_and_rejects_removed_choice() {
    let mut state = UiState::new(UiSnapshot {
        model_choices: vec![choice("model_a", "模型 A"), choice("model_b", "模型 B")],
        ..UiSnapshot::default()
    });
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    state.apply(UiEvent::SetModelChoices(vec![
        UiChoice {
            id: "model_b".into(),
            label: "模型 B".into(),
            detail: Some("更新后的说明".into()),
        },
        choice("model_a", "模型 A"),
    ]));
    let detail = screen_text(&state, 40, 15).replace(' ', "");
    assert!(detail.contains("更新后的说明"), "{detail}");
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectModel {
            id: "model_b".into()
        }]
    );

    let mut removed = UiState::new(UiSnapshot {
        model_choices: vec![choice("model_a", "模型 A"), choice("model_b", "模型 B")],
        ..UiSnapshot::default()
    });
    removed.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        removed.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    removed.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    removed.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    removed.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    removed.apply(UiEvent::SetModelChoices(vec![choice("model_a", "模型 A")]));
    removed.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert!(
        removed
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert!(
        screen_text(&removed, 80, 18)
            .replace(' ', "")
            .contains("请重新打开列表选择")
    );
}

/// 直接选择时的权威重排同样以 ID 对账，移除原项后 Enter 不能落到邻项。
#[test]
fn model_picker_refresh_preserves_identity_or_requires_reopen() {
    let initial = UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_choices: vec![choice("model_a", "模型 A"), choice("model_b", "模型 B")],
        ..UiSnapshot::default()
    };
    let mut reordered = UiState::new(initial.clone());
    reordered.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        reordered.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    reordered.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    reordered.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    reordered.apply(UiEvent::SetModelChoices(vec![
        choice("model_b", "模型 B"),
        choice("model_a", "模型 A"),
    ]));
    assert_eq!(
        reordered.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectModel {
            id: "model_b".into()
        }]
    );

    let mut removed = UiState::new(initial.clone());
    removed.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        removed.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    removed.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    removed.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    removed.apply(UiEvent::SetModelChoices(vec![choice("model_a", "模型 A")]));
    assert!(
        removed
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert!(
        screen_text(&removed, 80, 18)
            .replace(' ', "")
            .contains("请重新打开列表选择")
    );

    let mut snapshot_refresh = UiState::new(initial);
    snapshot_refresh.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        snapshot_refresh.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    snapshot_refresh.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    snapshot_refresh.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    snapshot_refresh.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_choices: vec![choice("model_b", "模型 B"), choice("model_a", "模型 A")],
        ..UiSnapshot::default()
    })));
    assert_eq!(
        snapshot_refresh.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectModel {
            id: "model_b".into()
        }]
    );
}

/// Codex 首屏卡片与模型列表是两种互斥视图；选择器替换 Composer，不残留提示文字。
#[test]
fn codex_welcome_and_model_picker_have_distinct_layouts() {
    let home = std::env::var("USERPROFILE")
        .or_else(|_| std::env::var("HOME"))
        .unwrap_or_else(|_| "C:\\dev\\rust\\ja".to_owned());
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        workspace_path: Some(home.clone()),
        model_identifier: Some("gpt-6-sol".to_owned()),
        permission_label: Some("需要审批".to_owned()),
        model_choices: vec![
            choice("model_a", "gpt-6-sol"),
            choice("model_b", "gpt-6-luna"),
        ],
        ..UiSnapshot::default()
    });
    let welcome = screen_text(&state, 80, 24);
    let compact_welcome = welcome
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(
        welcome.contains("╭") && compact_welcome.contains("模型："),
        "{welcome}"
    );
    assert!(
        compact_welcome.contains("目录：") && welcome.contains('~'),
        "{welcome}"
    );
    assert!(compact_welcome.contains("权限："), "{welcome}");
    assert!(welcome.contains("›"), "{welcome}");
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let picker = screen_text(&state, 80, 24);
    assert!(picker.contains("1.") && picker.contains("2."), "{picker}");
    assert!(!picker.contains("让 Ja 完成任何任务"), "{picker}");
}

/// 中文模型名与两位数编号不能把说明列推歪，列宽按终端 cell 而非 UTF-8 字节计算。
#[test]
fn model_picker_description_column_stays_aligned_for_wide_labels() {
    let models = (0..12)
        .map(|index| UiChoice {
            id: format!("model_{index}"),
            label: if index == 0 {
                "中文模型".to_owned()
            } else {
                format!("gpt-{index}")
            },
            detail: Some("说明".to_owned()),
        })
        .collect();
    let mut state = UiState::new(UiSnapshot {
        model_choices: models,
        ..UiSnapshot::default()
    });
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let description_columns = |state: &UiState| {
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();
        terminal.draw(|frame| render(frame, state)).unwrap();
        let cells = terminal.backend().buffer().content();
        (0..24)
            .filter_map(|row| (0..80).find(|column| cells[row * 80 + column].symbol() == "说"))
            .collect::<Vec<_>>()
    };
    let first = description_columns(&state);
    assert!(
        first.len() >= 6 && first.iter().all(|column| *column == first[0]),
        "{first:?}"
    );
    for _ in 0..11 {
        state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    }
    let last = description_columns(&state);
    assert!(
        last.len() >= 3 && last.iter().all(|column| *column == first[0]),
        "{last:?}"
    );
}

/// 恢复会话的历史已写入终端滚动区时，空活动尾部不重复显示首次欢迎文案。
#[test]
fn restored_history_does_not_repeat_welcome_line() {
    let state = UiState::new(UiSnapshot {
        timeline: vec![TimelineEntry {
            id: "historical-user".to_owned(),
            kind: TimelineKind::User,
            text: "已提交的任务".to_owned(),
            detail: None,
            status: None,
        }],
        ..UiSnapshot::default()
    });
    assert!(!screen_text(&state, 80, 16).contains("Harness"));

    let mut chooser = UiState::new(UiSnapshot {
        thread_choices: vec![choice("thr_demo", "已有会话")],
        ..UiSnapshot::default()
    });
    chooser.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(
        !screen_text(&chooser, 80, 16).contains("Harness"),
        "恢复选择与权威快照之间不能闪现欢迎文案"
    );
}

/// 斜杠菜单只展示匹配命令，Tab 补全与 Enter 执行同一候选；退出项不提交为用户消息。
#[test]
fn slash_menu_filters_completes_and_quits() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    let all = screen_text(&state, 52, 16);
    assert_eq!(all.matches("/new").count(), 1, "{all}");
    assert!(all.contains("1/11"), "{all}");
    let narrow = screen_text(&state, 30, 10);
    assert!(narrow.contains("Tab") && narrow.contains("Esc"), "{narrow}");

    for ch in "mod".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    let filtered = screen_text(&state, 52, 16);
    assert!(filtered.contains("/model"), "{filtered}");
    assert!(!filtered.contains("/new"), "{filtered}");
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "/model");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Command(UiCommand::SelectModel)]
    );

    let mut quit = UiState::new(UiSnapshot::default());
    quit.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for _ in 0..10 {
        quit.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    }
    assert!(screen_text(&quit, 52, 16).contains("/quit"));
    assert_eq!(
        quit.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Quit]
    );
    assert!(quit.is_closed());

    let mut unknown = UiState::new(UiSnapshot::default());
    unknown.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    unknown.handle_paste("no-such-command");
    assert!(
        unknown
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(unknown.composer().text(), "/no-such-command");

    let mut pasted_command = UiState::new(UiSnapshot::default());
    pasted_command.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    pasted_command.handle_paste("attach \"C:\\tmp\\my file.txt\"");
    assert!(matches!(
        pasted_command
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::AttachPaths { paths }] if paths.len() == 1
    ));
}

/// 面板操作失败时错误必须覆盖快捷键提示，否则用户会误以为确认键没有响应。
#[test]
fn chooser_error_is_visible_above_menu_hint() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    state.apply(UiEvent::SetNotice(Some("模型列表读取失败".to_owned())));
    let screen = screen_text(&state, 52, 12);
    let compact = screen
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(compact.contains("模型列表读取失败"), "{screen}");
}

/// 裸附件命令进入路径输入；实际导入未获 ACK 前草稿保持，失败时仍可原地修正。
#[test]
fn attach_command_keeps_path_until_import_ack() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("/attach");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Command(UiCommand::Attach)]
    );
    assert_eq!(state.composer().text(), "/attach ");
    state.handle_paste("\"C:\\tmp\\my file.txt\"");
    let draft = state.composer().text().to_owned();
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(matches!(
        actions.as_slice(),
        [UiAction::AttachPaths { paths }] if paths.len() == 1 && paths[0].to_string_lossy() == "C:\\tmp\\my file.txt"
    ));
    assert_eq!(state.composer().text(), draft);
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.apply(UiEvent::ReleaseDraft);
    assert_eq!(state.composer().text(), draft);
    assert!(matches!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::AttachPaths { .. }]
    ));
    state.apply(UiEvent::ClearDraft);
    assert!(state.composer().is_empty());
}

/// 长工具正文可以到达尾部、返回顶部；浏览器内粘贴与 Ctrl+C 不暗中清空原草稿。
#[test]
fn details_viewer_scrolls_and_preserves_composer() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("保留草稿");
    state.apply(UiEvent::ShowDetails {
        entry_id: "tool-1".to_owned(),
        title: "运行测试".to_owned(),
        body: (1..=30).map(|index| format!("LINE-{index:02}\n")).collect(),
    });
    let first = screen_text(&state, 60, 12);
    assert!(first.contains("LINE-01"), "{first}");
    assert!(!first.contains("LINE-30"), "{first}");
    state.handle_paste("不能进入隐藏草稿");
    assert_eq!(state.composer().text(), "保留草稿");
    state.handle_key(key(KeyCode::End, KeyModifiers::NONE));
    let last = screen_text(&state, 60, 12);
    assert!(last.contains("LINE-30"), "{last}");
    state.handle_key(key(KeyCode::Home, KeyModifiers::NONE));
    assert!(screen_text(&state, 60, 12).contains("LINE-01"));
    state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL));
    assert_eq!(state.composer().text(), "保留草稿");
}

/// Esc 只收起可跳过问题；跳过必须显式选中，F2 全文查看后仍返回原题。
#[test]
fn clarification_skip_is_explicit_and_full_question_returns() {
    let mut state = UiState::new(UiSnapshot {
        pending_prompt: Some(PendingPrompt::Clarification {
            thread_id: "thr_demo".to_owned(),
            request_id: "req_demo".to_owned(),
            expected_revision: 7,
            idempotency_key: "idem_demo".to_owned(),
            questions: vec![InteractionQuestion {
                question_id: "q1".to_owned(),
                prompt: "需要哪一种方案？".repeat(20),
                kind: InteractionQuestionKind::Single,
                options: vec![choice("a", "方案 A"), choice("b", "方案 B")],
                allow_skip: true,
                allow_free_text: false,
            }],
        }),
        ..UiSnapshot::default()
    });
    let prompt_screen = screen_text(&state, 30, 10);
    assert!(
        prompt_screen.contains("F2") && prompt_screen.contains('…'),
        "{prompt_screen}"
    );
    state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    assert!(screen_text(&state, 40, 10).contains("Esc"));
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert!(
        state
            .handle_key(key(KeyCode::Esc, KeyModifiers::NONE))
            .is_empty()
    );
    assert!(screen_text(&state, 40, 10).contains("Enter"));
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(matches!(
        actions.as_slice(),
        [UiAction::RespondInteraction { answers, .. }] if answers.len() == 1 && answers[0].skipped
    ));
}

/// 窄审批内联列表同时保留实际选择和确认/收起提示，避免问题折行后隐藏关键动作。
#[test]
fn narrow_approval_keeps_choices_and_actions_visible() {
    let mut state = UiState::new(UiSnapshot {
        pending_prompt: Some(PendingPrompt::ToolApproval {
            approval_id: "appr_demo".to_owned(),
            thread_id: "thr_demo".to_owned(),
            turn_id: "turn_demo".to_owned(),
            expected_thread_revision: 1,
            prompt: "允许执行对项目文件的修改，并在完成后运行测试吗？".to_owned(),
            choices: vec![choice("allow", "允许本次"), choice("deny", "拒绝")],
        }),
        ..UiSnapshot::default()
    });
    let screen = screen_text(&state, 30, 10);
    let compact = screen
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(compact.contains("允许本次"), "{screen}");
    assert!(compact.contains("拒绝"), "{screen}");
    assert!(screen.contains("Enter"), "{screen}");
    assert!(screen.contains("Esc"), "{screen}");
    assert!(
        !screen.contains('─'),
        "审批不能显示无法编辑的空输入框：{screen}"
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Char('d'), KeyModifiers::CONTROL)),
        vec![UiAction::Quit]
    );
}

/// 可自由回答的选择题明确给出 Tab 入口，切换后正文输入仍在同一内联区域下方。
#[test]
fn clarification_shows_free_text_shortcut() {
    let mut state = UiState::new(UiSnapshot {
        pending_prompt: Some(PendingPrompt::Clarification {
            thread_id: "thr_demo".to_owned(),
            request_id: "req_demo".to_owned(),
            expected_revision: 2,
            idempotency_key: "idem_demo".to_owned(),
            questions: vec![InteractionQuestion {
                question_id: "q1".to_owned(),
                prompt: "选择方案或自行说明".to_owned(),
                kind: InteractionQuestionKind::Single,
                options: vec![choice("a", "方案 A")],
                allow_skip: false,
                allow_free_text: true,
            }],
        }),
        ..UiSnapshot::default()
    });
    let options = screen_text(&state, 60, 12);
    assert!(options.contains("Tab"));
    assert!(!options.contains('─'), "选项题尚未进入自由输入：{options}");
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    let editing = screen_text(&state, 60, 12);
    assert!(
        editing.contains('›'),
        "自由输入时必须显示编辑光标行：{editing}"
    );
    let compact = editing
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(compact.contains("在下方输入回答"), "{compact}");
}

/// Codex 式数字候选和问号帮助直接操作已有真实动作，空列表或组合键不能误触。
#[test]
fn codex_selection_shortcuts_are_direct_and_bounded() {
    let mut help = UiState::new(UiSnapshot::default());
    help.handle_key(key(KeyCode::Char('?'), KeyModifiers::SHIFT));
    assert!(screen_text(&help, 60, 12).contains("Esc"));
    help.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));

    let mut picker = UiState::new(UiSnapshot {
        model_choices: vec![choice("model_a", "model-a"), choice("model_b", "model-b")],
        ..UiSnapshot::default()
    });
    picker.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        picker.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    picker.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(
        picker.handle_key(key(KeyCode::Char('2'), KeyModifiers::NONE)),
        vec![UiAction::SelectModel {
            id: "model_b".to_owned()
        }]
    );

    let mut approval = UiState::new(UiSnapshot {
        pending_prompt: Some(PendingPrompt::ToolApproval {
            approval_id: "appr_shortcut".to_owned(),
            thread_id: "thr_demo".to_owned(),
            turn_id: "turn_demo".to_owned(),
            expected_thread_revision: 9,
            prompt: "是否允许？".to_owned(),
            choices: vec![choice("approve", "允许"), choice("deny", "拒绝")],
        }),
        ..UiSnapshot::default()
    });
    assert!(matches!(
        approval.handle_key(key(KeyCode::Char('2'), KeyModifiers::NONE)).as_slice(),
        [UiAction::RespondToolApproval { choice_id, .. }] if choice_id == "deny"
    ));
}

/// /resume 搜索由 Java 分页，旧查询结果不能覆盖新关键词，PgDn 只请求下页。
#[test]
fn resume_chooser_searches_and_pages_without_losing_draft() {
    let mut state = UiState::new(UiSnapshot {
        thread_choices: vec![choice("thr_old", "旧任务")],
        thread_next_cursor: Some("old-page".into()),
        ..UiSnapshot::default()
    });
    assert_eq!(
        state.handle_key(key(KeyCode::Char('测'), KeyModifiers::NONE)),
        vec![UiAction::SearchThreads {
            query: "测".into(),
            cursor: None
        }]
    );
    state.apply(UiEvent::SetThreadPage {
        query: "旧".into(),
        choices: vec![choice("thr_stale", "旧结果")],
        next_cursor: None,
        append: false,
    });
    state.apply(UiEvent::SetThreadPage {
        query: "测".into(),
        choices: vec![choice("thr_1", "测试一")],
        next_cursor: Some("next".into()),
        append: false,
    });
    assert_eq!(
        state.handle_key(key(KeyCode::PageDown, KeyModifiers::NONE)),
        vec![UiAction::SearchThreads {
            query: "测".into(),
            cursor: Some("next".into())
        }]
    );
    state.apply(UiEvent::SetThreadPage {
        query: "测".into(),
        choices: vec![choice("thr_2", "测试二")],
        next_cursor: None,
        append: true,
    });
    let output = screen_text(&state, 80, 18);
    assert!(!output.contains("thr_"));
    assert!(
        output.contains("测 试 一") && output.contains("测 试 二"),
        "{output}"
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectThread { id: "thr_1".into() }]
    );
    assert!(screen_text(&state, 80, 18).contains("恢 复 会 话"));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_1".into()),
        model_identifier: Some("gpt-6-luna".into()),
        ..UiSnapshot::default()
    })));
    let loaded = screen_text(&state, 80, 18);
    assert!(
        loaded.contains("gpt-6-luna") && !loaded.contains("未 配 置 模 型"),
        "{loaded}"
    );
}

/// 连续分页只保留有界窗口，换页后仍能选择新结果而不会选到已移除的旧行。
#[test]
fn resume_pagination_rolls_bounded_window_forward() {
    let mut state = UiState::new(UiSnapshot {
        thread_choices: vec![choice("thr_initial", "初始会话")],
        ..UiSnapshot::default()
    });
    for page in 0..10 {
        state.apply(UiEvent::SetThreadPage {
            query: String::new(),
            choices: (0..10)
                .map(|index| {
                    let number = page * 10 + index;
                    choice(&format!("thr_{number}"), &format!("会话 {number}"))
                })
                .collect(),
            next_cursor: Some(format!("page_{}", page + 1)),
            append: true,
        });
    }
    assert_eq!(state.snapshot().thread_choices.len(), 64);
    assert_eq!(state.snapshot().thread_choices.last().unwrap().id, "thr_99");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectThread {
            id: "thr_90".to_owned()
        }]
    );
}

/// Ctrl+R 搜索已提交的输入；迟到结果和 Esc 不得覆盖仍在编辑的草稿。
#[test]
fn input_history_search_restores_only_explicitly_selected_text() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("当前草稿");
    assert_eq!(
        state.handle_key(key(KeyCode::Char('r'), KeyModifiers::CONTROL)),
        vec![UiAction::SearchInputHistory {
            query: String::new(),
            cursor: None
        }]
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Char('测'), KeyModifiers::NONE)),
        vec![UiAction::SearchInputHistory {
            query: "测".into(),
            cursor: None
        }]
    );
    state.apply(UiEvent::SetInputHistoryPage {
        query: String::new(),
        items: vec![(choice("item_old", "旧结果"), "旧结果".into(), false)],
        next_cursor: None,
        append: false,
    });
    assert_eq!(state.composer().text(), "当前草稿");
    state.apply(UiEvent::SetInputHistoryPage {
        query: "测".into(),
        items: vec![(
            choice("item_1", "测试输入"),
            "测试输入\n下一行".into(),
            false,
        )],
        next_cursor: None,
        append: false,
    });
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "当前草稿");
    state.handle_key(key(KeyCode::Char('r'), KeyModifiers::CONTROL));
    state.apply(UiEvent::SetInputHistoryPage {
        query: String::new(),
        items: vec![(
            choice("item_1", "测试输入"),
            "测试输入\n下一行".into(),
            false,
        )],
        next_cursor: None,
        append: false,
    });
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "测试输入\n下一行");
}

/// 输入历史跨多页保持选择与文本同一身份，超长正文不能被裁短后误当成完整草稿。
#[test]
fn input_history_pages_remain_bounded_and_reject_partial_text() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("保留的草稿");
    state.handle_key(key(KeyCode::Char('r'), KeyModifiers::CONTROL));
    for page in 0..10 {
        state.apply(UiEvent::SetInputHistoryPage {
            query: String::new(),
            items: (0..10)
                .map(|index| {
                    let number = page * 10 + index;
                    (
                        choice(&format!("input_{number}"), &format!("历史 {number}")),
                        format!("正文 {number}"),
                        false,
                    )
                })
                .collect(),
            next_cursor: Some(format!("page_{}", page + 1)),
            append: page != 0,
        });
    }
    assert_eq!(state.snapshot().input_history_choices.len(), 64);
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "正文 90");

    state.handle_key(key(KeyCode::Char('r'), KeyModifiers::CONTROL));
    state.apply(UiEvent::SetInputHistoryPage {
        query: String::new(),
        items: vec![(choice("huge", "大段历史"), "x".repeat(40 * 1024), false)],
        next_cursor: None,
        append: false,
    });
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "正文 90");
    assert!(screen_text(&state, 80, 18).contains("无 法 完 整 恢 复"));
}

/// 空草稿的 Ctrl+C 需在一秒内重复，防止误碰立刻关闭界面并丢失当前观察位置。
#[test]
fn idle_ctrl_c_requires_quick_second_press() {
    let mut state = UiState::new(UiSnapshot::default());
    assert!(
        state
            .handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL))
            .is_empty()
    );
    assert!(!state.is_closed());
    assert_eq!(
        state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL)),
        vec![UiAction::Quit]
    );
    assert!(state.is_closed());
}

/// 模型第二层只确认真实推理等级；Esc 返回模型层且保留既有任务草稿。
#[test]
fn codex_reasoning_picker_preserves_draft_and_confirms_level() {
    let mut state = UiState::new(UiSnapshot {
        model_choices: vec![choice("provider/model", "gpt-6-sol")],
        ..UiSnapshot::default()
    });
    state.handle_paste("已有任务草稿");
    state.apply(UiEvent::SetReasoningChoices {
        model_identifier: "gpt-6-sol".into(),
        choices: vec![choice("low", "Low"), choice("medium", "Medium")],
        selected_id: Some("medium".into()),
    });
    let picker = screen_text(&state, 80, 18);
    assert!(
        picker.contains("gpt-6-sol") && picker.contains("2. Medium"),
        "{picker}"
    );
    assert!(!picker.contains("已有任务草稿"), "{picker}");
    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        model_choices: vec![choice("provider/model", "gpt-6-sol")],
        ..UiSnapshot::default()
    })));
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectReasoning {
            id: "medium".into()
        }]
    );
    assert_eq!(state.composer().text(), "已有任务草稿");
    state.apply(UiEvent::SetReasoningChoices {
        model_identifier: "gpt-6-sol".into(),
        choices: vec![choice("low", "Low")],
        selected_id: None,
    });
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    let back = screen_text(&state, 80, 18);
    assert!(back.contains("选 择 模 型"), "{back}");
}

/// `$` 菜单必须来自真实候选且支持继续编辑，插入后仅替换当前字素 token。
#[test]
fn codex_skill_suggestions_insert_reference_without_losing_task() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("请用 $app 完成任务");
    let suffix = " 完成任务";
    for _ in suffix.graphemes(true) {
        state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    }
    let due = Instant::now() + Duration::from_secs(1);
    let search = state.take_due_file_search(due).unwrap();
    let UiAction::SearchSkills {
        query, query_id, ..
    } = search
    else {
        panic!("expected skill search")
    };
    assert_eq!(query, "app");
    state.apply(UiEvent::SetSkillChoices {
        thread_id: None,
        query_id,
        choices: vec![UiChoice {
            id: "user:Apple UI".into(),
            label: "Apple UI".into(),
            detail: Some("视觉与交互设计".into()),
        }],
    });
    let rendered = screen_text(&state, 80, 18);
    assert!(
        rendered
            .chars()
            .filter(|ch| !ch.is_whitespace())
            .collect::<String>()
            .contains("[技能]"),
        "{rendered}"
    );
    state.apply(UiEvent::ReplaceSnapshot(Box::default()));
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectSkillReference {
            id: "user:Apple UI".into()
        }]
    );
    state.apply(UiEvent::InsertSkillReference {
        skill_id: "user:Apple UI".into(),
        name: "Apple UI".into(),
    });
    assert_eq!(state.composer().text(), "请用 $Apple UI 完成任务");
}

/// Esc 关闭引用菜单后，稍晚返回的旧搜索页不能再次抢焦点；继续输入才重新显示建议。
#[test]
fn dismissed_reference_popup_stays_closed_until_draft_changes() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("@ag");
    let suggestion = vec![choice("agents-example.md", "agents-example.md")];
    let UiAction::SearchFiles { query_id, .. } = state
        .take_due_file_search(Instant::now() + Duration::from_secs(1))
        .unwrap()
    else {
        panic!("expected file search")
    };
    state.apply(UiEvent::SetFileChoices {
        thread_id: None,
        query_id,
        choices: suggestion.clone(),
    });
    assert!(screen_text(&state, 80, 16).contains("agents-example.md"));
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    state.apply(UiEvent::SetFileChoices {
        thread_id: None,
        query_id,
        choices: suggestion.clone(),
    });
    assert!(!screen_text(&state, 80, 16).contains("agents-example.md"));
    state.handle_key(key(KeyCode::Char('e'), KeyModifiers::NONE));
    let UiAction::SearchFiles {
        query_id: fresh_id, ..
    } = state
        .take_due_file_search(Instant::now() + Duration::from_secs(1))
        .unwrap()
    else {
        panic!("expected fresh file search")
    };
    state.apply(UiEvent::SetFileChoices {
        thread_id: None,
        query_id: fresh_id,
        choices: suggestion,
    });
    assert!(screen_text(&state, 80, 16).contains("agents-example.md"));
}

/// 澄清请求暂存普通任务草稿，RPC 未获 ACK 时保留答案供修改，权威完成后还原原光标缓冲。
#[test]
fn clarification_failure_keeps_answer_and_restores_task_draft() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        ..UiSnapshot::default()
    });
    state.handle_paste("原任务草稿");
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    state.apply(UiEvent::SetPendingPrompt(Some(
        PendingPrompt::Clarification {
            thread_id: "thr_demo".to_owned(),
            request_id: "req_demo".to_owned(),
            expected_revision: 4,
            idempotency_key: "idem_demo".to_owned(),
            questions: vec![InteractionQuestion {
                question_id: "q1".to_owned(),
                prompt: "请说明".to_owned(),
                kind: InteractionQuestionKind::Text,
                options: vec![],
                allow_skip: false,
                allow_free_text: true,
            }],
        },
    )));
    assert!(state.composer().is_empty());
    state.handle_paste("保留的回答");
    assert!(matches!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)).as_slice(),
        [UiAction::RespondInteraction { answers, .. }]
            if answers[0].free_text.as_deref() == Some("保留的回答")
    ));
    assert_eq!(state.composer().text(), "保留的回答");
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.apply(UiEvent::ReleasePromptSubmission);
    assert!(matches!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)).as_slice(),
        [UiAction::RespondInteraction { answers, .. }]
            if answers[0].free_text.as_deref() == Some("保留的回答")
    ));
    state.apply(UiEvent::SetPendingPrompt(None));
    assert_eq!(state.composer().text(), "原任务草稿");
    state.handle_key(key(KeyCode::Char('X'), KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "原任务草X稿");
}

/// 同一会话的权威刷新不得关闭模型列表、改写多行编辑光标或重置未提交的审批选择。
#[test]
fn same_thread_refresh_preserves_picker_cursor_and_pending_choice() {
    let mut editor = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        ..UiSnapshot::default()
    });
    editor.handle_paste("abc");
    editor.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    editor.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        ..UiSnapshot::default()
    })));
    editor.handle_key(key(KeyCode::Char('X'), KeyModifiers::NONE));
    assert_eq!(editor.composer().text(), "abXc");

    let mut picker = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_choices: vec![choice("provider_a/model_a", "model-a")],
        ..UiSnapshot::default()
    });
    picker.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    for ch in "model".chars() {
        picker.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    assert_eq!(
        picker.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Command(UiCommand::SelectModel)]
    );
    picker.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_choices: vec![
            choice("provider_a/model_a", "model-a"),
            choice("provider_b/model_b", "model-b"),
        ],
        ..UiSnapshot::default()
    })));
    let screen = screen_text(&picker, 52, 12);
    assert!(
        screen.contains("model-a") && screen.contains("model-b"),
        "{screen}"
    );
    picker.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    assert_eq!(
        picker.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectModel {
            id: "provider_b/model_b".to_owned()
        }]
    );

    let approval = PendingPrompt::ToolApproval {
        approval_id: "appr_demo".to_owned(),
        thread_id: "thr_demo".to_owned(),
        turn_id: "turn_demo".to_owned(),
        expected_thread_revision: 4,
        prompt: "允许吗？".to_owned(),
        choices: vec![choice("allow", "允许"), choice("deny", "拒绝")],
    };
    let mut pending = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        pending_prompt: Some(approval.clone()),
        ..UiSnapshot::default()
    });
    pending.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    pending.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        pending_prompt: Some(approval),
        ..UiSnapshot::default()
    })));
    assert!(matches!(
        pending.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)).as_slice(),
        [UiAction::RespondToolApproval { choice_id, .. }] if choice_id == "deny"
    ));
}

/// 恢复时最近稳定回答先留在可见 viewport，后续提交到 scrollback 也只出现一次。
#[test]
fn stable_history_is_inserted_before_inline_viewport_once() {
    let stable = TimelineEntry {
        id: "assistant-final".to_owned(),
        kind: TimelineKind::Assistant,
        text: "已完成的稳定回答".to_owned(),
        detail: None,
        status: Some(TimelineStatus::Complete),
    };
    let mut state = UiState::new(UiSnapshot {
        timeline: vec![stable.clone()],
        ..UiSnapshot::default()
    });
    let initial = state.take_scrollback_entries();
    assert!(initial.is_empty());
    let backend = TestBackend::new(48, 12);
    let mut terminal = Terminal::with_options(
        backend,
        TerminalOptions {
            viewport: Viewport::Inline(6),
        },
    )
    .unwrap();
    insert_scrollback(&mut terminal, &initial).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    assert!(buffer_text(terminal.backend().buffer()).contains("已"));
    state.apply(UiEvent::UpsertEntry(stable));
    let committed = state.take_scrollback_entries();
    assert_eq!(committed.len(), 1);
    insert_scrollback(&mut terminal, &committed).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    terminal.backend_mut().resize(60, 12);
    terminal.resize(Rect::new(0, 0, 60, 12)).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    let screen = buffer_text(terminal.backend().buffer());
    let scrollback = buffer_text(terminal.backend().scrollback());
    let output = format!("{scrollback}{screen}")
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert_eq!(output.matches("已完成的稳定回答").count(), 1, "{output:?}");
}

/// Codex 时间线把用户、过程、工具输出和最终回答按原序写入终端历史，终态不重复。
#[test]
fn timeline_uses_bullets_and_tool_tree_without_invented_terminal_state() {
    let entries = vec![
        TimelineEntry {
            id: "user".to_owned(),
            kind: TimelineKind::User,
            text: "查看目录".to_owned(),
            detail: None,
            status: None,
        },
        TimelineEntry {
            id: "commentary".to_owned(),
            kind: TimelineKind::Commentary,
            text: "我来检查".to_owned(),
            detail: None,
            status: None,
        },
        TimelineEntry {
            id: "tool".to_owned(),
            kind: TimelineKind::Tool {
                action: "运行".to_owned(),
                target: "Get-Location".to_owned(),
            },
            text: String::new(),
            detail: Some("stdout: C:\\tmp".to_owned()),
            status: Some(TimelineStatus::Complete),
        },
        TimelineEntry {
            id: "final".to_owned(),
            kind: TimelineKind::FinalAnswer,
            text: "目录已确认".to_owned(),
            detail: None,
            status: Some(TimelineStatus::Complete),
        },
    ];
    let mut state = UiState::new(UiSnapshot {
        timeline: entries,
        ..UiSnapshot::default()
    });
    let stable = state.take_scrollback_entries();
    let mut terminal = Terminal::with_options(
        TestBackend::new(80, 24),
        TerminalOptions {
            viewport: Viewport::Inline(24),
        },
    )
    .unwrap();
    insert_scrollback(&mut terminal, &stable).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    let output = format!(
        "{}{}",
        buffer_text(terminal.backend().scrollback()),
        buffer_text(terminal.backend().buffer())
    )
    .chars()
    .filter(|ch| !ch.is_whitespace())
    .collect::<String>();
    for expected in [
        "›查看目录",
        "•我来检查",
        "•运行Get-Location",
        "└stdout:C:\\tmp",
        "•目录已确认",
    ] {
        assert!(output.contains(expected), "missing {expected}: {output}");
    }
    let visible = buffer_text(terminal.backend().buffer())
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(visible.contains("•目录已确认"), "{visible}");
    assert!(!output.contains("done"), "{output}");
}

/// 按键构造统一为 key-down，测试只关注 reducer 输出而不依赖终端驱动。
fn key(code: KeyCode, modifiers: KeyModifiers) -> KeyEvent {
    KeyEvent::new(code, modifiers)
}

/// 测试候选只声明可见标签，省去未参与交互的次要 provider 信息。
fn choice(id: &str, label: &str) -> UiChoice {
    UiChoice {
        id: id.to_owned(),
        label: label.to_owned(),
        detail: None,
    }
}

/// 从真实 Ratatui 绘制结果读屏，验证用户能看见的候选、详情和键盘提示。
fn screen_text(state: &UiState, width: u16, height: u16) -> String {
    let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
    terminal.draw(|frame| render(frame, state)).unwrap();
    buffer_text(terminal.backend().buffer())
}

/// 保留行边界读取 Ratatui buffer，防止整屏拼接把上下布局回归掩盖掉。
fn screen_rows(state: &UiState, width: u16, height: u16) -> Vec<String> {
    let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
    terminal.draw(|frame| render(frame, state)).unwrap();
    terminal
        .backend()
        .buffer()
        .content()
        .chunks(width as usize)
        .map(|row| row.iter().map(|cell| cell.symbol()).collect())
        .collect()
}

/// 从 Ratatui 的真实绘制 buffer 读取三个动画点颜色；先去掉宽字 continuation cell 的填充空格。
fn working_indicator_dot_colors(state: &UiState) -> Vec<Color> {
    let width = 80usize;
    let mut terminal = Terminal::new(TestBackend::new(width as u16, 16)).unwrap();
    terminal.draw(|frame| render(frame, state)).unwrap();
    terminal
        .backend()
        .buffer()
        .content()
        .chunks(width)
        .find(|row| {
            row.iter()
                .map(|cell| cell.symbol())
                .collect::<String>()
                .replace(' ', "")
                .contains("正在工作")
        })
        .map(|row| {
            row.iter()
                .filter(|cell| cell.symbol() == ".")
                .take(3)
                .map(|cell| cell.fg)
                .collect()
        })
        .unwrap_or_default()
}

/// 将虚拟屏幕或 scrollback 的 cell 符号拼为文本，验证终端最终可见内容。
fn buffer_text(buffer: &ratatui::buffer::Buffer) -> String {
    buffer.content().iter().map(|cell| cell.symbol()).collect()
}

/// 搜索防抖回归用单调时钟验证时间预算。
#[test]
fn file_search_debounce_uses_elapsed_time() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("@Cargo");
    let due = Instant::now() + Duration::from_millis(200);
    assert!(state.take_due_file_search(due).is_some());
    assert!(
        state
            .take_due_file_search(due + Duration::from_secs(1))
            .is_none()
    );
}

/// 选择文件只替换光标前的 @query，中文后缀和第二个相同引用都保持原位。
#[test]
fn file_reference_replaces_only_active_grapheme_range() {
    let mut state = UiState::new(UiSnapshot::default());
    let draft = "前缀 @fi中文，正文 @fi重复引用";
    state.apply(UiEvent::RestoreDraft(draft.to_owned()));
    let suffix = "中文，正文 @fi重复引用";
    for _ in suffix.graphemes(true) {
        state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    }

    let actions = state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    let [
        UiAction::SearchFiles {
            query, query_id, ..
        },
    ] = actions.as_slice()
    else {
        panic!("expected file search")
    };
    assert_eq!(query, "fi");
    state.apply(UiEvent::SetFileChoices {
        thread_id: None,
        query_id: *query_id,
        choices: vec![choice("ref-1", "fixture.txt")],
    });
    assert_eq!(
        state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE)),
        vec![UiAction::SelectFileReference {
            id: "ref-1".to_owned(),
        }]
    );
    state.apply(UiEvent::InsertFileReference {
        workspace_id: "ws_test".into(),
        relative_path: "中文目录/fixture.txt".into(),
        kind: "file".into(),
    });

    let expected = "前缀 @中文目录/fixture.txt 中文，正文 @fi重复引用";
    assert_eq!(state.composer().text(), expected);
    assert_eq!(
        state.composer().cursor(),
        "前缀 @中文目录/fixture.txt ".graphemes(true).count()
    );
    assert!(
        matches!(state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)).as_slice(),
        [UiAction::Submit { text, references, .. }] if text == expected && references.len() == 1
            && matches!(&references[0].kind, ja_cli::ui::UiReferenceKind::Workspace { relative_path, .. } if relative_path == "中文目录/fixture.txt"))
    );
}

/// 一 MiB 工具诊断经展示预算和稳定段渲染后，缓存小于100 KiB且虚拟终端保持有限尺寸。
#[test]
fn mebibyte_tool_output_has_bounded_cache_and_render() {
    let entry = TimelineEntry {
        id: "large-tool".to_owned(),
        kind: TimelineKind::Tool {
            action: "读取".to_owned(),
            target: "large.log".to_owned(),
        },
        text: "读取失败".to_owned(),
        detail: Some("x".repeat(1024 * 1024)),
        status: Some(TimelineStatus::Failed),
    };
    let queued_payload_bytes = entry.text.len() + entry.detail.as_ref().unwrap().len();
    let events = UiEventSender::default();
    assert_eq!(
        events.send(UiEvent::UpsertEntry(entry.clone())).unwrap(),
        EventDelivery::Queued
    );
    assert_eq!(events.buffered_len(), 1);

    let mut state = UiState::new(UiSnapshot::default());
    state.apply(UiEvent::UpsertEntry(entry));
    let retained_bytes = state.snapshot().timeline[0].detail.as_ref().unwrap().len();
    assert!(retained_bytes <= 100 * 1024);
    let stable = state.take_scrollback_entries();
    assert_eq!(stable.len(), 1);

    let mut terminal = Terminal::with_options(
        TestBackend::new(80, 24),
        TerminalOptions {
            viewport: Viewport::Inline(8),
        },
    )
    .unwrap();
    insert_scrollback(&mut terminal, &stable).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    let output = format!(
        "{}{}",
        buffer_text(terminal.backend().scrollback()),
        buffer_text(terminal.backend().buffer())
    );
    let visible = output
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(visible.contains("large.log"));
    assert!(visible.contains("内容已截断"));
    assert!(terminal.backend().buffer().content().len() <= 80 * 24);
    assert!(terminal.backend().scrollback().area.height <= 24);
    eprintln!(
        "ui_large_output input_bytes={} event_queue_items={} estimated_queued_bytes={} retained_bytes={} screen_cells={} scrollback_rows={}",
        1024 * 1024,
        events.buffered_len(),
        queued_payload_bytes,
        retained_bytes,
        terminal.backend().buffer().content().len(),
        terminal.backend().scrollback().area.height,
    );
}

/// 十万条唯一事件洪峰下队列保持有限，reducer 的取消热键不受 producer 锁或队列排空拖住。
#[test]
fn hundred_thousand_event_flood_keeps_queue_bounded_and_cancel_responsive() {
    const EVENT_COUNT: usize = 100_000;

    let events = UiEventSender::default();
    let producer_events = events.clone();
    let high_water = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let producer_high_water = Arc::clone(&high_water);
    let (ready_tx, ready_rx) = mpsc::sync_channel(1);
    let started = Instant::now();
    let producer = thread::spawn(move || {
        let mut overflow_count = 0usize;
        for index in 0..EVENT_COUNT {
            let delivery = producer_events
                .send(UiEvent::UpsertEntry(TimelineEntry {
                    id: format!("stress-{index}"),
                    kind: TimelineKind::Assistant,
                    text: "x".to_owned(),
                    detail: None,
                    status: None,
                }))
                .expect("non-blocking event producer must remain available");
            overflow_count += usize::from(delivery == EventDelivery::SnapshotRequired);
            producer_high_water.fetch_max(
                producer_events.buffered_len(),
                std::sync::atomic::Ordering::Relaxed,
            );
            if index == 31 {
                ready_tx.send(()).unwrap();
            }
        }
        overflow_count
    });

    ready_rx.recv().unwrap();
    let mut state = UiState::new(UiSnapshot {
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    });
    let cancel_started = Instant::now();
    let actions = state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL));
    let cancel_elapsed = cancel_started.elapsed();
    let overflow_count = producer.join().unwrap();
    let queue_elapsed = started.elapsed();

    assert_eq!(actions, vec![UiAction::CancelTurn]);
    assert!(
        cancel_elapsed < Duration::from_millis(100),
        "cancel took {cancel_elapsed:?}"
    );
    assert_eq!(high_water.load(std::sync::atomic::Ordering::Relaxed), 32);
    assert!(overflow_count > 0);
    assert_eq!(
        events.take_snapshot_refresh_action(),
        Some(UiAction::RefreshSnapshot)
    );
    assert_eq!(events.take_snapshot_refresh_action(), None);
    eprintln!(
        "ui_pressure events={EVENT_COUNT} queue_cap=32 observed_peak={} overflow_events={overflow_count} producer_ms={} cancel_us={}",
        high_water.load(std::sync::atomic::Ordering::Relaxed),
        queue_elapsed.as_millis(),
        cancel_elapsed.as_micros(),
    );
}
