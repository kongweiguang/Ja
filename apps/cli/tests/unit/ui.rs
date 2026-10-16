// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::time::{Duration, Instant, SystemTime};
use std::{
    sync::{Arc, mpsc},
    thread,
};

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ja_cli::ui::{
    EventDelivery, InteractionQuestion, InteractionQuestionKind, PendingPrompt, TimelineEntry,
    TimelineKind, TimelineStatus, TurnState, UiAction, UiChoice, UiCommand, UiEvent, UiEventSender,
    UiSnapshot, UiState, render,
};
use ratatui::Terminal;
use ratatui::backend::{Backend, TestBackend};
use ratatui::style::Color;
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

#[path = "usage.rs"]
mod usage;

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

/// @author kongweiguang
/// `/btw` is a local create-then-send command: retain Unicode, inner and trailing whitespace, and
/// the original retry line so it never reaches the parent model as an ordinary prompt.
#[test]
fn btw_preserves_full_body_and_empty_command_opens_idle_side_chat() {
    let mut state = UiState::new(UiSnapshot::default());
    let command = "/btw  第一行\n中文 🙂 ";
    state.handle_paste(command);
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::CreateSideChat {
            content: " 第一行\n中文 🙂 ".into(),
            command_text: command.into(),
        }]
    );

    let mut empty = UiState::new(UiSnapshot::default());
    empty.handle_paste("/btw");
    assert_eq!(
        empty.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::CreateSideChat {
            content: String::new(),
            command_text: "/btw".into(),
        }]
    );
}

/// @author kongweiguang
/// 按 Thread 身份构造独立快照，验证返回时恢复原草稿且新子会话不会继承父草稿。
#[test]
fn composer_drafts_are_isolated_by_thread_identity() {
    let mut state = UiState::new(UiSnapshot::default());
    let first = UiSnapshot {
        thread_id: Some("thr_parent".into()),
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(first)));
    state.handle_paste("父会话未发送草稿");

    let child = UiSnapshot {
        thread_id: Some("thr_side".into()),
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(child)));
    assert!(state.composer().is_empty());
    state.handle_paste("侧聊单独草稿");

    let returned = UiSnapshot {
        thread_id: Some("thr_parent".into()),
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(returned)));
    assert_eq!(state.composer().text(), "父会话未发送草稿");

    let closed = UiSnapshot {
        thread_id: Some("thr_parent".into()),
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(closed)));
    state.apply(UiEvent::ForgetThreadDrafts(vec!["thr_side".into()]));
    let revisit_child = UiSnapshot {
        thread_id: Some("thr_side".into()),
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(revisit_child)));
    assert!(state.composer().is_empty());
}

/// @author kongweiguang
/// An RPC completion may restore a safe picker command only into an empty Composer, preserving newer edits.
#[test]
fn safe_side_chat_recovery_route_does_not_overwrite_newer_draft() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_parent".into()),
        ..UiSnapshot::default()
    });
    state.handle_paste("用户等待期间新写的草稿");
    state.apply(UiEvent::RestoreDraft("/resume".into()));
    assert_eq!(state.composer().text(), "用户等待期间新写的草稿");
}

/// @author kongweiguang
/// 显式构造审批过期后的暂停子会话，确保既有 F6 动作继续绑定所选 Thread。
#[test]
fn suspended_thread_keeps_f6_resume_action() {
    let mut state = UiState::new(UiSnapshot::default());
    let snapshot = UiSnapshot {
        thread_id: Some("thr_suspended_child".into()),
        turn_state: TurnState::Paused,
        ..UiSnapshot::default()
    };
    state.apply(UiEvent::ReplaceSnapshot(Box::new(snapshot)));

    assert_eq!(
        state.handle_key(key(KeyCode::F(6), KeyModifiers::NONE)),
        vec![UiAction::ResumeSuspendedTurn {
            thread_id: "thr_suspended_child".into(),
        }]
    );
}

/// Ctrl+G 应把当前普通草稿交给外部编辑器动作，同时保留本地正文与光标。
#[test]
fn ctrl_g_requests_external_edit_without_clearing_draft() {
    let mut state = UiState::new(UiSnapshot::default());
    let draft = "保留中文 draft 🙂";
    state.handle_paste(draft);
    let cursor = state.composer().cursor();

    let actions = state.handle_key(key(KeyCode::Char('g'), KeyModifiers::CONTROL));

    assert_eq!(
        actions,
        vec![UiAction::EditDraft {
            draft: draft.to_owned()
        }]
    );
    assert_eq!(state.composer().text(), draft);
    assert_eq!(state.composer().cursor(), cursor);
}

/// 真实编辑器重试接纳新正文后应结清旧失败反馈；超限回执仍保留原草稿及可见诊断。
/// @author kongweiguang
#[test]
fn editor_retry_clears_failure_only_after_draft_is_accepted() {
    let mut state = UiState::new(UiSnapshot::default());
    let original = "原草稿🙂";
    state.handle_paste(original);
    state.apply(UiEvent::SetNotice(Some(
        "编辑器已取消或退出异常，原草稿已保留。".into(),
    )));

    state.apply(UiEvent::ReplaceDraft("文".repeat(32 * 1024)));
    assert_eq!(state.composer().text(), original);
    assert!(state.snapshot().notice.as_deref().unwrap().contains("超过"));

    let edited = "原草稿🙂\n重试写回中文";
    state.apply(UiEvent::ReplaceDraft(edited.into()));
    assert_eq!(state.composer().text(), edited);
    assert_eq!(state.snapshot().notice, None);
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

/// 粘贴内的换行不发送；显式 Enter 立即腾出编辑区，等待回执期间不会重复提交。
#[test]
fn pasted_newlines_do_not_send_until_enter_and_ack() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("第一行\n第二行");
    assert!(
        state
            .handle_key(key(KeyCode::Char('x'), KeyModifiers::NONE))
            .is_empty()
    );
    flush_input_boundary(&mut state);
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
    assert!(state.composer().is_empty());
    state.apply(UiEvent::ClearDraft);
    assert!(state.composer().is_empty());
}

/// 发送立即呈现问题并腾出输入，ACK 不删除下一条编辑；权威快照接管正文身份且只显示一次。
#[test]
fn message_submit_immediately_moves_input_and_ack_preserves_next_draft() {
    let snapshot = UiSnapshot {
        thread_id: Some("thr_send".into()),
        ..UiSnapshot::default()
    };
    let mut state = UiState::new_for_terminal(snapshot.clone(), 80, 24);
    let text = "发送检查🙂\n第二行中文";
    state.handle_paste(text);
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(matches!(actions.as_slice(), [UiAction::Submit { text: sent, .. }] if sent == text));
    assert!(state.composer().is_empty());
    let rows = screen_rows(&state, 80, 24);
    assert_eq!(
        rows.iter()
            .filter(|row| row.replace(' ', "").contains("发送检查"))
            .count(),
        1
    );
    assert!(rows[23].replace(' ', "").contains("正在发送"), "{rows:?}");
    assert!(!rows[23].replace(' ', "").contains("草稿"));
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.handle_paste("下一条输入");
    state.apply(UiEvent::ClearDraft);
    assert_eq!(state.composer().text(), "下一条输入");
    let rows = screen_rows(&state, 80, 24);
    assert_eq!(
        rows.iter()
            .filter(|row| row.replace(' ', "").contains("发送检查"))
            .count(),
        1
    );
    assert!(!rows[23].replace(' ', "").contains("正在发送"));

    let mut confirmed = snapshot;
    confirmed.timeline.push(TimelineEntry {
        id: "msg_server_confirmed".into(),
        kind: TimelineKind::User,
        text: text.into(),
        detail: None,
        status: Some(TimelineStatus::Complete),
    });
    state.apply(UiEvent::ReplaceSnapshot(Box::new(confirmed.clone())));
    assert_eq!(
        state
            .snapshot()
            .timeline
            .iter()
            .filter(|entry| entry.id == "msg_server_confirmed")
            .count(),
        1
    );
    let confirmed_screen = screen_text(&state, 80, 24).replace(' ', "");
    assert_eq!(confirmed_screen.matches("发送检查🙂").count(), 1);
    assert_eq!(state.composer().text(), "下一条输入");
    state.apply(UiEvent::ReplaceSnapshot(Box::new(confirmed)));
    assert_eq!(
        screen_text(&state, 80, 24)
            .replace(' ', "")
            .matches("发送检查🙂")
            .count(),
        1
    );
}

/// 明确拒绝恢复原引用和字素光标；等待时新写的输入通过撤销保留，不能被失败恢复覆盖丢失。
#[test]
fn rejected_send_restores_references_cursor_and_retains_next_input_in_undo() {
    let mut state = UiState::new(UiSnapshot::default());
    state.apply(UiEvent::InsertFileReference {
        workspace_id: "ws_send".into(),
        relative_path: "中文/fixture.rs".into(),
        kind: "file".into(),
    });
    state.handle_paste("检查🙂引用");
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    let original = state.composer().text().to_owned();
    let references = state.composer().references().to_vec();
    let cursor = state.composer().cursor();
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(
        matches!(actions.as_slice(), [UiAction::Submit { references: sent, .. }] if sent == &references)
    );
    assert!(state.composer().is_empty());
    state.handle_paste("等待时新写的🙂输入");
    state.apply(UiEvent::RestoreDraft(original.clone()));
    assert_eq!(state.composer().text(), original);
    assert_eq!(state.composer().references(), references);
    assert_eq!(state.composer().cursor(), cursor);
    state.handle_key(key(KeyCode::Char('z'), KeyModifiers::CONTROL));
    assert_eq!(state.composer().text(), "等待时新写的🙂输入");
    state.handle_key(key(KeyCode::Char('y'), KeyModifiers::CONTROL));
    assert_eq!(state.composer().text(), original);
    assert_eq!(state.composer().references(), references);
    assert!(matches!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::Submit { .. }]
    ));
}

/// 未知回执不因普通快照解锁；问题保持一次可见，F5 提示和下一条编辑在核实期间均保留。
#[test]
fn unknown_send_receipt_keeps_submission_lock_across_snapshot_refresh() {
    let snapshot = UiSnapshot {
        thread_id: Some("thr_unknown_send".into()),
        ..UiSnapshot::default()
    };
    let mut state = UiState::new_for_terminal(snapshot.clone(), 80, 24);
    state.handle_paste("未知回执的输入");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.apply(UiEvent::SetNotice(Some(
        "发送结果待核实；按 F5 核实原操作".into(),
    )));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(snapshot)));
    let rows = screen_rows(&state, 80, 24);
    assert_eq!(
        rows.iter()
            .filter(|row| row.replace(' ', "").contains("未知回执的输入"))
            .count(),
        1
    );
    assert!(rows[23].contains("F5"), "{rows:?}");
    assert!(state.composer().is_empty());
    state.handle_paste("下一条尚未发送");
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::RefreshSnapshot]
    );
    assert_eq!(state.composer().text(), "下一条尚未发送");
    state.apply(UiEvent::ClearDraft);
    assert_eq!(state.composer().text(), "下一条尚未发送");
    assert!(matches!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::Submit { .. }]
    ));
}

/// 权威问题可能先于 ACK 到达；只显示一次，核实锁和新输入保留，之后 ACK 不再追加临时副本。
#[test]
fn authoritative_input_does_not_repeat_unknown_receipt_placeholder() {
    let snapshot = UiSnapshot {
        thread_id: Some("thr_observed_input".into()),
        ..UiSnapshot::default()
    };
    let mut state = UiState::new(snapshot.clone());
    state.handle_paste("权威问题只显示一次");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        timeline: vec![TimelineEntry {
            id: "authoritative-input-1".into(),
            kind: TimelineKind::User,
            text: "权威问题只显示一次".into(),
            detail: None,
            status: None,
        }],
        ..snapshot
    })));
    let text = screen_rows(&state, 80, 24).join("\n").replace(' ', "");
    assert_eq!(text.matches("权威问题只显示一次").count(), 1, "{text}");
    state.handle_paste("下一条仍可编辑");
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.apply(UiEvent::ClearDraft);
    assert_eq!(state.snapshot().timeline.len(), 1);
    assert_eq!(state.composer().text(), "下一条仍可编辑");
}

/// 发送相同正文仍是新的动作；既有问题的同文不能隐藏当前未获回执的输入。
#[test]
fn identical_previous_question_does_not_hide_new_submission() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_identical_input".into()),
        timeline: vec![TimelineEntry {
            id: "previous-input".into(),
            kind: TimelineKind::User,
            text: "再问一次".into(),
            detail: None,
            status: None,
        }],
        ..UiSnapshot::default()
    });
    state.handle_paste("再问一次");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let text = screen_rows(&state, 80, 24).join("\n").replace(' ', "");
    assert!(text.contains("再问一次"), "{text}");
    state.apply(UiEvent::ClearDraft);
    assert_eq!(state.snapshot().timeline.len(), 2);
}

/// 启动参数沿用相同提交锁和完整输入预算，失败恢复不被普通时间线的展示截断预算削短。
#[test]
fn initial_prompt_submission_restores_complete_input_and_protects_next_draft() {
    let mut state = UiState::new(UiSnapshot::default());
    let prompt = "中文🙂".repeat(2_500);
    assert!(prompt.len() > 16 * 1024 && prompt.len() < 32 * 1024);
    state.apply(UiEvent::BeginSubmission(prompt.clone()));
    state.handle_paste("随后输入");
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.apply(UiEvent::RestoreDraft(prompt.clone()));
    assert_eq!(state.composer().text(), prompt);
    state.handle_key(key(KeyCode::Char('z'), KeyModifiers::CONTROL));
    assert_eq!(state.composer().text(), "随后输入");
}

/// 生产通过权威快照推进终态，取消锁不能依赖未发送的 SetTurnState 事件而遗留到下一轮。
#[test]
fn cancellation_lock_releases_on_authoritative_terminal_snapshot() {
    let working = UiSnapshot {
        thread_id: Some("thr_cancel_retry".into()),
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    };
    let mut state = UiState::new(working.clone());
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CancelTurn]
    );
    assert!(
        state
            .handle_key(key(KeyCode::Esc, KeyModifiers::NONE))
            .is_empty()
    );
    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        turn_state: TurnState::Idle,
        ..working.clone()
    })));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(working)));
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CancelTurn]
    );
}

/// 压缩重试在窄列优先显示阶段和序号；普通 notice 不遮住底栏取消键，Thread 切换清理旧状态。
#[test]
fn work_activity_shows_compaction_and_retry_without_stale_thread_state() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_compaction_a".into()),
        turn_state: TurnState::Working,
        turn_started_at: Some(SystemTime::now() - Duration::from_secs(30)),
        notice: Some("保留的操作反馈".into()),
        ..UiSnapshot::default()
    });
    state.apply(UiEvent::SetWorkActivity(Some("正在压缩上下文".into())));
    let compacting = screen_text(&state, 96, 24).replace(' ', "");
    assert!(
        compacting.contains("正在工作·正在压缩上下文"),
        "{compacting}"
    );
    assert!(compacting.contains("保留的操作反馈"), "{compacting}");

    state.apply(UiEvent::SetWorkActivity(Some(
        "摘要重试 · 第 2 次请求".into(),
    )));
    let retrying = screen_text(&state, 96, 24).replace(' ', "");
    assert!(retrying.contains("摘要重试·第2次请求"), "{retrying}");
    assert!(retrying.contains("正在工作"), "{retrying}");

    for width in [32, 40, 80] {
        let rows = screen_rows(&state, width, 18);
        let work_rows = rows
            .iter()
            .filter(|row| row.replace(' ', "").contains("正在工作"))
            .collect::<Vec<_>>();
        assert_eq!(work_rows.len(), 1, "width={width}: {rows:?}");
        let status = work_rows[0].replace(' ', "");
        assert!(status.contains("摘要重试"), "width={width}: {status}");
        assert!(
            UnicodeWidthStr::width(status.as_str()) <= width as usize,
            "width={width}: {status}"
        );
        let screen = rows.join("").replace(' ', "");
        assert!(screen.contains("Esc中断"), "底栏应保留取消提示: {rows:?}");
        if width == 40 || width == 80 {
            assert!(status.contains("摘要重试·第2次请求"), "{status}");
        }
        if width == 32 || width == 40 {
            assert!(
                !status.contains("Esc中断"),
                "窄屏省略重复行内提示: {status}"
            );
        } else {
            assert!(status.contains("Esc中断"), "宽屏保留行内提示: {status}");
            assert!(status.contains("30秒"), "宽屏保留工作用时: {status}");
        }
    }

    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_compaction_b".into()),
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    })));
    let switched = screen_text(&state, 96, 24).replace(' ', "");
    assert!(!switched.contains("压缩上下文"), "{switched}");
    assert!(switched.contains("正在工作"), "{switched}");
}

/// 取消失败保留可操作提示及真实工作状态；自动快照不盖掉错误，用户可先核查再显式重试。
#[test]
fn failed_cancellation_preserves_work_and_allows_explicit_retry() {
    let mut state = UiState::new(UiSnapshot {
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    });
    assert_eq!(
        state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL)),
        vec![UiAction::CancelTurn]
    );
    state.apply(UiEvent::CancellationFailed(
        "中断尚未确认；F5 核实状态，Esc 可重试。".into(),
    ));
    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        notice: None,
        ..state.snapshot().clone()
    })));
    assert!(
        state
            .snapshot()
            .notice
            .as_deref()
            .unwrap()
            .contains("Esc 可重试")
    );
    assert_eq!(state.snapshot().turn_state, TurnState::Working);
    let rows = screen_rows(&state, 80, 24);
    assert!(rows[23].replace(' ', "").contains("Esc重试"), "{rows:?}");
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::RefreshSnapshot]
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CancelTurn]
    );
}

/// 重连成功或其它提示不能遮断 F5；显式读取原操作仍保持待核实消息的发送锁和下一条输入。
#[test]
fn refresh_key_survives_notice_changes_without_replaying_pending_input() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("原操作待核实");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.handle_paste("下一条输入");
    state.apply(UiEvent::SetNotice(Some("已恢复后台连接".into())));
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::RefreshSnapshot]
    );
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(state.composer().text(), "下一条输入");
}

/// 容量边界可完整恢复；超限事件不能建立空发送或覆盖已有编辑缓冲。
#[test]
fn initial_prompt_capacity_boundary_rejects_empty_submission() {
    let mut state = UiState::new(UiSnapshot::default());
    let boundary = "x".repeat(32 * 1024);
    state.apply(UiEvent::BeginSubmission(boundary.clone()));
    state.apply(UiEvent::RestoreDraft(boundary.clone()));
    assert_eq!(state.composer().text(), boundary);
    state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL));
    state.handle_paste("已有输入");
    state.apply(UiEvent::BeginSubmission("界".repeat(12 * 1024)));
    assert_eq!(state.composer().text(), "已有输入");
    assert!(state.snapshot().notice.as_deref().unwrap().contains("过长"));
    assert!(matches!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)).as_slice(),
        [UiAction::Submit { text, .. }] if text == "已有输入"
    ));
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
/// @author kongweiguang
#[test]
fn plain_key_paste_burst_suppresses_enter_send() {
    let mut state = UiState::new(UiSnapshot::default());
    for ch in "rapidpaste".chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
    }
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(
        !actions
            .iter()
            .any(|action| matches!(action, UiAction::Submit { .. }))
    );
    assert!(state.composer().text().is_empty());
    // Left ends the burst synchronously, exercising the same flush boundary as idle polling without a timing sleep.
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "rapidpaste\n");
}

/// 终端控制字符清理作用于流 delta，未知条目不会被增量伪造。
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
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "m1".to_owned(),
        delta: "ok\u{1b}[2J".to_owned(),
    });
    assert_eq!(state.snapshot().timeline[0].text, "ok[2J");
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "missing".to_owned(),
        delta: "must not fabricate".to_owned(),
    });
    assert_eq!(state.snapshot().timeline.len(), 1);
}

/// @author kongweiguang
/// 重试会创建新的 live generation；上一段已提交正文保留，后续 generation 按顺序续接且各只显示一次。
#[test]
fn retry_generation_appends_after_finalized_stream_without_duplication() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_retry_generation".into()),
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    });
    for (generation, body) in [(1, "FIRST_RETRY_PARTIAL"), (2, "SECOND_RETRY_COMPLETION")] {
        let entry_id = format!("live:turn_retry_generation:{generation}");
        state.apply(UiEvent::UpsertEntry(TimelineEntry {
            id: entry_id.clone(),
            kind: TimelineKind::Assistant,
            text: body.into(),
            detail: None,
            status: Some(TimelineStatus::Running),
        }));
        state.apply(UiEvent::FinishAssistantStream {
            turn_id: "turn_retry_generation".into(),
        });
    }

    let visible = screen_text(&state, 120, 32);
    assert_eq!(
        visible.matches("FIRST_RETRY_PARTIAL").count(),
        1,
        "{visible}"
    );
    assert_eq!(
        visible.matches("SECOND_RETRY_COMPLETION").count(),
        1,
        "{visible}"
    );
    assert!(
        visible.find("FIRST_RETRY_PARTIAL") < visible.find("SECOND_RETRY_COMPLETION"),
        "{visible}"
    );
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
            interaction_id: "interaction_1".to_owned(),
            expected_interaction_revision: 9,
            operation_key: "idem_1".to_owned(),
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
            interaction_id: "interaction_1".to_owned(),
            expected_interaction_revision: 9,
            operation_key: "idem_1".to_owned(),
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

/// @author kongweiguang
/// 快照替换应保留分页与复制的异步完成回执，避免 UI 永久停在加载或复制中状态。
#[test]
fn event_queue_snapshot_keeps_history_and_copy_completions() {
    let completions = [
        UiEvent::OlderHistoryLoaded {
            thread_id: "thread-page".into(),
            entries: Vec::new(),
            has_older: true,
        },
        UiEvent::OlderHistoryFailed {
            thread_id: "thread-page".into(),
            notice: "读取失败".into(),
        },
        UiEvent::TranscriptSelectionCopied { selection_id: 7 },
        UiEvent::TranscriptSelectionCopyFailed {
            selection_id: 7,
            notice: "剪贴板不可用".into(),
        },
        UiEvent::ShowDetails {
            entry_id: "entry-message".into(),
            title: "消息全文".into(),
            body: "正在读取全文".into(),
        },
        UiEvent::UpdateDetailsIfOpen {
            entry_id: "entry-message".into(),
            title: "消息全文".into(),
            body: "完整正文".into(),
        },
        UiEvent::SetDetailsCopyReady {
            entry_id: "entry-message".into(),
            ready: true,
        },
    ];

    for completion in completions {
        let events = UiEventSender::default();
        for index in 0..32 {
            events
                .send(UiEvent::UpsertEntry(TimelineEntry {
                    id: format!("entry-{index}"),
                    kind: TimelineKind::Assistant,
                    text: "增量".into(),
                    detail: None,
                    status: None,
                }))
                .unwrap();
        }
        assert_eq!(events.buffered_len(), 32);

        assert!(matches!(
            events.send(completion),
            Ok(EventDelivery::SnapshotRequired)
        ));
        events
            .send(UiEvent::ReplaceSnapshot(Box::default()))
            .expect("snapshot and critical completion should remain deliverable");
        assert_eq!(
            events.buffered_len(),
            2,
            "snapshot replacement must retain the async completion"
        );
    }
}

/// Queue overflow must retain every message-detail phase in order so preview data cannot be mistaken for a complete copy.
/// @author kongweiguang
#[test]
fn event_queue_pressure_preserves_detail_read_sequence() {
    let events = UiEventSender::default();
    for index in 0..32 {
        events
            .send(UiEvent::UpsertEntry(TimelineEntry {
                id: format!("entry-{index}"),
                kind: TimelineKind::Assistant,
                text: "incremental".into(),
                detail: None,
                status: None,
            }))
            .unwrap();
    }
    for event in [
        UiEvent::ShowDetails {
            entry_id: "message-1".into(),
            title: "消息全文".into(),
            body: "正在读取安全预览".into(),
        },
        UiEvent::UpdateDetailsIfOpen {
            entry_id: "message-1".into(),
            title: "消息全文".into(),
            body: "完整正文".into(),
        },
        UiEvent::SetDetailsCopyReady {
            entry_id: "message-1".into(),
            ready: true,
        },
    ] {
        assert_eq!(events.send(event).unwrap(), EventDelivery::SnapshotRequired);
    }
    events
        .send(UiEvent::ReplaceSnapshot(Box::default()))
        .expect("the resync snapshot must retain all three critical detail phases");
    assert_eq!(events.buffered_len(), 4);
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

/// 窄屏和常用宽度下保留输入符，不依赖占位文案维持可发现性。
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
        assert!(output.contains('›'), "{output}");
        assert!(!output.contains("让 Ja 完成任何任务"), "{output}");
    }
}

/// Expose the F2 action only when the visible transcript resolves a single ordinary message.
/// @author kongweiguang
#[test]
fn footer_advertises_message_details_only_when_a_target_exists() {
    let empty = screen_text(&UiState::new(UiSnapshot::default()), 80, 16);
    assert!(!empty.replace(' ', "").contains("F2查看全文"), "{empty}");

    let message = screen_text(
        &UiState::new(UiSnapshot {
            timeline: vec![TimelineEntry {
                id: "footer-message".into(),
                kind: TimelineKind::Assistant,
                text: "visible".into(),
                detail: None,
                status: Some(TimelineStatus::Complete),
            }],
            ..UiSnapshot::default()
        }),
        80,
        16,
    );
    assert!(message.replace(' ', "").contains("F2查看全文"), "{message}");
}

/// 全屏底栏按屏幕底部排列；正文状态变化时 Composer 和身份 footer 坐标保持固定。
#[test]
fn inline_layout_anchors_interaction_at_terminal_bottom() {
    let base = UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        thread_title: Some("说明项目用途".to_owned()),
        model_identifier: Some("gpt-6-sol".to_owned()),
        workspace_path: Some("C:\\dev\\rust\\ja".to_owned()),
        model_choices: vec![choice("model_a", "gpt-6-sol")],
        ..UiSnapshot::default()
    };
    let welcome = screen_rows(&UiState::new(base.clone()), 80, 24);
    assert!(welcome[0].contains("Ja"), "{welcome:?}");
    assert!(
        welcome
            .iter()
            .all(|row| !row.contains('╭') && !row.contains('╰'))
    );
    assert!(
        welcome.iter().all(|row| !row.contains("提示：/命令")),
        "{welcome:?}"
    );
    assert!(welcome[19].trim_start().starts_with('›'), "{welcome:?}");
    assert!(welcome[21].contains("gpt-6-sol"), "{welcome:?}");
    assert!(welcome[21].replace(' ', "").contains("说明项目用途"));
    assert!(!welcome[21].contains('?'));
    assert!(welcome[23].replace(' ', "").contains("?查看快捷键"));
    assert!(welcome[12..19].iter().all(|row| row.trim().is_empty()));
    let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();
    terminal
        .draw(|frame| render(frame, &UiState::new(base.clone())))
        .unwrap();
    let footer = terminal
        .backend()
        .buffer()
        .content()
        .chunks(80)
        .nth(21)
        .unwrap();
    assert!(
        footer
            .iter()
            .any(|cell| cell.symbol() == "g" && cell.fg == Color::Rgb(249, 226, 175))
    );
    assert!(
        footer
            .iter()
            .any(|cell| cell.symbol() == "C" && cell.fg == Color::Rgb(166, 217, 146))
    );
    let action_footer = terminal
        .backend()
        .buffer()
        .content()
        .chunks(80)
        .nth(23)
        .unwrap();
    assert_eq!(action_footer[2].symbol(), "?");
    assert_eq!(action_footer[2].fg, Color::Rgb(180, 186, 198));
    let mut drafting = UiState::new(base.clone());
    drafting.handle_paste("正在编辑");
    assert!(!screen_rows(&drafting, 80, 24)[23].contains('?'));
    let narrow = screen_rows(&UiState::new(base.clone()), 30, 10);
    assert!(narrow[7].contains("gpt-6-sol"), "{narrow:?}");
    assert!(!narrow[7].contains('?'), "{narrow:?}");
    let narrow_working = UiState::new(UiSnapshot {
        turn_state: TurnState::Working,
        ..base.clone()
    });
    let narrow_working = screen_rows(&narrow_working, 30, 10);
    assert!(
        narrow_working[9].replace(' ', "").contains("Esc中断"),
        "{narrow_working:?}"
    );
    let very_narrow = screen_rows(&UiState::new(base.clone()), 20, 10);
    assert!(very_narrow[7].contains("gpt-6-sol"), "{very_narrow:?}");
    assert!(!very_narrow[7].contains('?'), "{very_narrow:?}");
    let mut refreshed_title = base.clone();
    refreshed_title.thread_title = Some("更新后的标题".to_owned());
    let mut title_state = UiState::new(base.clone());
    title_state.apply(UiEvent::ReplaceSnapshot(Box::new(refreshed_title)));
    let refreshed_footer = screen_rows(&title_state, 80, 24)[21].replace(' ', "");
    assert!(
        refreshed_footer.contains("更新后的标题"),
        "{refreshed_footer}"
    );
    assert!(
        !refreshed_footer.contains("说明项目用途"),
        "{refreshed_footer}"
    );

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
    assert!(
        message_row + 1 < 20,
        "正文从追加点向下显示，输入区独立贴底: {conversation:?}"
    );
    assert!(conversation[19].trim_start().starts_with('›'));
    assert!(conversation[21].contains("gpt-6-sol"));

    let mut picker = UiState::new(base);
    open_command_menu(&mut picker);
    type_chars(&mut picker, "model");
    picker.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let picker = screen_rows(&picker, 80, 24);
    assert!(
        picker[16..22]
            .iter()
            .any(|row| row.replace(' ', "").contains("选择模型"))
    );
    assert!(picker[23].replace(' ', "").contains("Enter确认"));

    let baseline = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".into()),
        ..UiSnapshot::default()
    });
    let baseline_rows = screen_rows(&baseline, 80, 24);
    let baseline_input_row = baseline_rows
        .iter()
        .position(|row| row.trim_start().starts_with('›'))
        .unwrap();

    let mut file_picker = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".into()),
        ..UiSnapshot::default()
    });
    file_picker.handle_paste("@Cargo");
    let UiAction::SearchFiles { query_id, .. } = file_picker
        .take_due_file_search(Instant::now() + Duration::from_secs(1))
        .unwrap()
    else {
        panic!("expected file search")
    };
    file_picker.apply(UiEvent::SetFileChoices {
        thread_id: Some("thr_demo".into()),
        query_id,
        choices: vec![choice("Cargo.toml", "Cargo.toml")],
    });
    let file_picker_rows = screen_rows(&file_picker, 80, 24);
    let candidate_row = file_picker_rows
        .iter()
        .position(|row| row.contains("Cargo.toml"))
        .unwrap();
    let input_row = file_picker_rows
        .iter()
        .position(|row| row.replace(' ', "").contains("@Cargo"))
        .unwrap();
    assert!(candidate_row < input_row, "{file_picker_rows:?}");
    assert_eq!(
        file_picker_rows.len() - input_row,
        baseline_rows.len() - baseline_input_row,
        "{file_picker_rows:?}"
    );
    let footer = file_picker_rows.last().unwrap();
    assert!(
        footer.replace(' ', "").contains("Enter引用")
            && footer.replace(' ', "").contains("Esc返回"),
        "{file_picker_rows:?}"
    );
}

/// 文件身份保留规范化路径，三行底栏的身份行仍还原 Windows 路径以节省窄窗宽度。
#[test]
fn windows_directory_display_keeps_drive_and_unc_targets_readable() {
    for (path, expected) in [
        (r"\\?\C:\工作区\项目", r"C:\工作区\项目"),
        (r"\\?\UNC\server\共享\项目", r"\\server\共享\项目"),
    ] {
        let state = UiState::new(UiSnapshot {
            thread_id: Some("thr_path".into()),
            workspace_path: Some(path.into()),
            ..UiSnapshot::default()
        });
        let rows = screen_rows(&state, 80, 24);
        assert!(
            rows[1]
                .replace(' ', "")
                .contains(&expected.replace(' ', ""))
        );
        assert!(
            rows[21]
                .replace(' ', "")
                .contains(&expected.replace(' ', ""))
        );
        assert!(!rows.join("\n").contains(r"\\?\"));
        assert_eq!(state.snapshot().workspace_path.as_deref(), Some(path));
    }
}

/// 在产品支持的短窗下，帮助和选择同时保留真实可见的返回按键。
#[test]
fn tiny_windows_keep_panel_return_action_visible() {
    for (width, height) in [(40, 10), (40, 8)] {
        let mut help = UiState::new(UiSnapshot::default());
        open_help_shortcut(&mut help, KeyModifiers::NONE);
        assert!(
            screen_text(&help, width, height).contains("Esc"),
            "help {width}x{height}: {:?}",
            screen_rows(&help, width, height)
        );
        help.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
        assert_eq!(help.composer().text(), "");

        let mut picker = UiState::new(UiSnapshot {
            model_choices: vec![choice("model", "真实模型")],
            ..UiSnapshot::default()
        });
        picker.handle_paste("/model");
        picker.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
        assert!(
            screen_text(&picker, width, height).contains("Esc"),
            "picker {width}x{height}: {:?}",
            screen_rows(&picker, width, height)
        );
        picker.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
        assert!(picker.composer().is_empty());
    }
}

/// 完整草稿由 Composer 持有，缩放后固定输入与 footer 坐标并在恢复宽高后可完整回读。
#[test]
fn composer_preserves_multiline_draft_across_terminal_sizes() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            model_identifier: Some("gpt-6-sol".into()),
            ..UiSnapshot::default()
        },
        80,
        31,
    );
    let draft = "第一行中文🙂\n第二行组合é\n第三行保留？";
    state.handle_paste(draft);
    let cursor = state.composer().cursor();
    for (width, height) in [(120, 32), (80, 24), (40, 10), (120, 32)] {
        state.set_terminal_size(width, height);
        let rows = screen_rows(&state, width, height);
        let composer = rows
            .iter()
            .position(|row| row.trim_start().starts_with('›'))
            .unwrap_or_else(|| panic!("composer missing at {width}x{height}: {rows:?}"));
        let footer = rows
            .iter()
            .rposition(|row| row.contains("gpt-6-sol"))
            .unwrap_or_else(|| panic!("footer missing at {width}x{height}: {rows:?}"));
        let final_draft_row = rows
            .iter()
            .rposition(|row| row.replace(' ', "").contains("第三行保留？"))
            .unwrap_or_else(|| panic!("draft tail missing at {width}x{height}: {rows:?}"));
        assert!(
            composer <= final_draft_row && final_draft_row < footer,
            "{width}x{height}: {rows:?}"
        );
        assert_eq!(footer, height as usize - 2, "{width}x{height}: {rows:?}");
        assert_eq!(state.composer().text(), draft);
        assert_eq!(state.composer().cursor(), cursor);
    }
    let restored = screen_text(&state, 120, 32).replace(' ', "");
    for line in ["第一行中文", "第二行组合", "第三行保留？"] {
        assert!(restored.contains(line), "{restored}");
    }
}

/// 候选行出现在 Composer 上方并保留选中态；全屏底部仍由输入区和状态区占据。
/// @author kongweiguang
#[test]
fn file_reference_candidate_uses_transcript_without_moving_composer() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thr_padding".into()),
            ..UiSnapshot::default()
        },
        80,
        31,
    );
    state.handle_paste("@Cargo");
    let UiAction::SearchFiles { query_id, .. } = state
        .take_due_file_search(Instant::now() + Duration::from_secs(1))
        .unwrap()
    else {
        panic!("file query");
    };
    state.apply(UiEvent::SetFileChoices {
        thread_id: Some("thr_padding".into()),
        query_id,
        choices: vec![choice("Cargo.toml", "Cargo.toml")],
    });
    let height = 31;
    let mut terminal = Terminal::new(TestBackend::new(80, height)).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    let buffer = terminal.backend().buffer();
    let row = buffer
        .content()
        .chunks(80)
        .position(|row| row.iter().any(|cell| cell.symbol() == "›"))
        .unwrap();
    let rows = screen_rows(&state, 80, height);
    let candidate = rows
        .iter()
        .position(|line| line.contains("Cargo.toml"))
        .expect("file candidate label should remain visible");
    assert_eq!(
        candidate, row,
        "selected marker and candidate must share a row"
    );
    let marker_column = buffer
        .content()
        .chunks(80)
        .nth(row)
        .unwrap()
        .iter()
        .position(|cell| cell.symbol() == "›")
        .unwrap();
    assert_eq!(
        buffer[(marker_column as u16, row as u16)].fg,
        Color::Rgb(137, 220, 235)
    );
    let composer = rows
        .iter()
        .position(|line| line.contains('›') && line.contains("@Cargo"))
        .unwrap();
    assert!(
        candidate < composer,
        "candidate must remain above the bottom editor: {rows:?}"
    );
}

/// Reasoning 和回答稳定归档到 Transcript 后仍各显示一次，工作指示紧随回复正文。
#[test]
fn transcript_reasoning_and_answer_keep_one_blank_row_before_working() {
    for (width, height) in [(120, 32), (80, 24), (40, 10)] {
        let turn_id = "turn_stream_spacing";
        let mut turn_ids = std::collections::HashMap::new();
        let timeline = [
            (
                "live-reasoning",
                TimelineKind::ReasoningSummary,
                "REASON_A\n",
            ),
            ("live", TimelineKind::Assistant, "REPLY_B\n"),
        ]
        .into_iter()
        .enumerate()
        .map(|(index, (prefix, kind, text))| {
            let id = format!("{prefix}:{turn_id}:{}", index + 1);
            turn_ids.insert(id.clone(), turn_id.to_owned());
            TimelineEntry {
                id,
                kind,
                text: text.into(),
                detail: None,
                status: Some(TimelineStatus::Running),
            }
        })
        .collect();
        let state = UiState::new_for_terminal(
            UiSnapshot {
                thread_id: Some("thr_stream_spacing".into()),
                turn_state: TurnState::Working,
                timeline,
                turn_id_by_entry_id: turn_ids,
                ..UiSnapshot::default()
            },
            width,
            height,
        );
        let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
        terminal.draw(|frame| render(frame, &state)).unwrap();
        let rows = terminal
            .backend()
            .buffer()
            .content()
            .chunks(usize::from(width))
            .map(|cells| cells.iter().map(|cell| cell.symbol()).collect::<String>())
            .collect::<Vec<_>>();
        let reply_row = rows
            .iter()
            .position(|row| row.contains("REPLY_B"))
            .unwrap_or_else(|| panic!("{rows:?}"));
        let working_row = rows
            .iter()
            .position(|row| row.replace(' ', "").contains("正在工作"))
            .unwrap();
        assert_eq!(working_row, reply_row + 2, "{width}x{height}: {rows:?}");
        assert_eq!(
            rows.iter()
                .filter(|row| row.replace(' ', "").contains("REASON_A"))
                .count(),
            1
        );
    }
}

/// 工作提示在回复后留一行呼吸空间；窄屏和草稿态仍保留可用操作提示。
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
        reply_row + 2 == working_row && working_row < footer_row,
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
    let draft_footer = draft_rows.last().expect("底栏应保留操作提示");
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

/// 稳定用时行在最终回复后一行留白处显示，使用已受理时间而非本地临时计时。
#[test]
fn work_summary_follows_final_reply_with_one_blank_row() {
    let state = UiState::new(UiSnapshot {
        thread_id: Some("thr_work_summary".into()),
        turn_started_at: Some(SystemTime::UNIX_EPOCH),
        timeline: vec![
            TimelineEntry {
                id: "final_answer".into(),
                kind: TimelineKind::FinalAnswer,
                text: "FINAL_WORK_SUMMARY_REPLY".into(),
                detail: None,
                status: Some(TimelineStatus::Complete),
            },
            TimelineEntry {
                id: "work_summary".into(),
                kind: TimelineKind::WorkSummary { elapsed_seconds: 4 },
                text: String::new(),
                detail: None,
                status: Some(TimelineStatus::Complete),
            },
        ],
        ..UiSnapshot::default()
    });
    let rows = screen_rows(&state, 80, 16);
    let compact = |row: &str| {
        row.chars()
            .filter(|ch| !ch.is_whitespace())
            .collect::<String>()
    };
    let reply_row = rows
        .iter()
        .position(|row| compact(row).contains("FINAL_WORK_SUMMARY_REPLY"))
        .unwrap();
    let summary_row = rows
        .iter()
        .position(|row| compact(row).contains("工作用时4秒"))
        .expect("稳定 WorkSummary 应显示耗时");
    assert_eq!(summary_row, reply_row + 2, "{rows:?}");
    assert_eq!(
        state.snapshot().turn_started_at,
        Some(SystemTime::UNIX_EPOCH)
    );
}

/// 菜单空间不足时欢迎信息整块让位，操作、模型身份和返回仍可见。
#[test]
fn compact_selector_never_clips_welcome_card() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_identifier: Some("gpt-6-sol".to_owned()),
        model_choices: vec![choice("model_a", "gpt-6-sol")],
        ..UiSnapshot::default()
    });
    open_command_menu(&mut state);
    let medium = screen_rows(&state, 80, 16);
    assert!(medium[0].contains("Ja"), "{medium:?}");
    assert!(
        medium
            .iter()
            .all(|row| !row.contains('╭') && !row.contains('╰'))
    );
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

    type_chars(&mut state, "model");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let medium = screen_rows(&state, 80, 16);
    assert!(
        medium
            .iter()
            .any(|row| row.contains("选择") || row.replace(' ', "").contains("选择")),
        "{medium:?}"
    );
    assert!(
        medium
            .iter()
            .all(|row| !row.contains('╭') && !row.contains('╰'))
    );
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
    open_command_menu(&mut state);
    type_chars(&mut state, "model");
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
    open_command_menu(&mut state);
    type_chars(&mut state, "model");
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
    open_command_menu(&mut removed);
    type_chars(&mut removed, "model");
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
    open_command_menu(&mut reordered);
    type_chars(&mut reordered, "model");
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
    open_command_menu(&mut removed);
    type_chars(&mut removed, "model");
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
    open_command_menu(&mut snapshot_refresh);
    type_chars(&mut snapshot_refresh, "model");
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

/// 启动区只显示品牌、真实目录和权限，模型只留在底栏；选择器使用真实候选。
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
        welcome.contains("Ja") && welcome.matches("gpt-6-sol").count() == 1,
        "{welcome}"
    );
    assert!(
        compact_welcome.contains("目录") && welcome.contains(&home),
        "{welcome}"
    );
    assert!(compact_welcome.contains("权限"), "{welcome}");
    assert!(welcome.contains("›"), "{welcome}");
    open_command_menu(&mut state);
    type_chars(&mut state, "model");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let picker = screen_text(&state, 80, 24);
    assert!(picker.contains("1.") && picker.contains("2."), "{picker}");
    assert!(!picker.contains("让 Ja 完成任何任务"), "{picker}");
}

/// 中文与两位编号的候选说明按 cell 对齐；只测编号行，避免把底栏的“F2 说明”计入列宽。
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
    open_command_menu(&mut state);
    type_chars(&mut state, "model");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let description_columns = |state: &UiState| {
        let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();
        terminal.draw(|frame| render(frame, state)).unwrap();
        let cells = terminal.backend().buffer().content();
        (0..24)
            .filter_map(|row| {
                let row_cells = &cells[row * 80..(row + 1) * 80];
                let prefix = row_cells[..8]
                    .iter()
                    .map(|cell| cell.symbol())
                    .collect::<String>();
                if !prefix.contains(". ")
                    || !prefix.chars().any(|character| character.is_ascii_digit())
                {
                    return None;
                }
                row_cells.iter().position(|cell| cell.symbol() == "说")
            })
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

/// 新会话介绍属于 Transcript；首条消息和同 Thread 快照对账后仍只保留一个介绍。
#[test]
fn welcome_intro_remains_in_transcript_without_duplication() {
    let initial = UiSnapshot {
        thread_id: Some("thr_welcome_archive".to_owned()),
        thread_title: Some("新对话".to_owned()),
        model_identifier: Some("gpt-6-sol".to_owned()),
        workspace_path: Some("C:\\dev\\rust\\ja".to_owned()),
        ..UiSnapshot::default()
    };
    let mut state = UiState::new(initial.clone());
    assert!(screen_text(&state, 80, 24).contains("Ja"));

    let question = TimelineEntry {
        id: "first-question".to_owned(),
        kind: TimelineKind::User,
        text: "第一个问题".to_owned(),
        detail: None,
        status: None,
    };
    state.apply(UiEvent::UpsertEntry(question.clone()));
    let after_question = screen_text(&state, 80, 24);
    assert!(
        after_question.replace(' ', "").contains("第一个问题"),
        "{after_question}"
    );
    assert_eq!(
        after_question.matches(">_ Ja").count(),
        1,
        "{after_question}"
    );

    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        timeline: vec![question],
        ..initial
    })));
    let after_refresh = screen_text(&state, 80, 24);
    assert_eq!(after_refresh.matches(">_ Ja").count(), 1, "{after_refresh}");
    assert_eq!(
        after_refresh.replace(' ', "").matches("第一个问题").count(),
        1,
        "{after_refresh}"
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

/// @author kongweiguang
/// 斜杠菜单只展示匹配命令，Tab 补全与 Enter 执行同一候选；退出项不提交为用户消息。
#[test]
fn slash_menu_filters_completes_and_quits() {
    let mut state = UiState::new(UiSnapshot::default());
    open_command_menu(&mut state);
    let all = screen_text(&state, 52, 16);
    assert_eq!(all.matches("/new").count(), 1, "{all}");
    assert!(all.contains("1/21"), "{all}");
    let narrow = screen_text(&state, 30, 10);
    assert!(narrow.contains("Tab") && narrow.contains("Esc"), "{narrow}");

    type_chars(&mut state, "mod");
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
    open_command_menu(&mut quit);
    quit.handle_paste("quit");
    assert!(screen_text(&quit, 52, 16).contains("/quit"));
    assert_eq!(
        quit.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Quit]
    );
    assert!(quit.is_closed());

    let mut unknown = UiState::new(UiSnapshot::default());
    open_command_menu(&mut unknown);
    unknown.handle_paste("no-such-command");
    assert!(
        unknown
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(unknown.composer().text(), "/no-such-command");

    let mut pasted_command = UiState::new(UiSnapshot::default());
    open_command_menu(&mut pasted_command);
    pasted_command.handle_paste("attach \"C:\\tmp\\my file.txt\"");
    assert!(matches!(
        pasted_command
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::AttachPaths { paths }] if paths.len() == 1
    ));
}

/// 本地快照工作可取消，但不改变普通消息的提交语义或草稿内容。
#[test]
fn cancellable_local_operation_keeps_submission_immediate() {
    let mut state = UiState::new(UiSnapshot::default());
    state.apply(UiEvent::SetLocalOperation(Some("读取 Git 快照".into())));
    state.handle_paste("继续提问");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Submit {
            text: "继续提问".into(),
            references: vec![],
            mode: ja_cli::ui::UiSubmitMode::Immediate,
        }]
    );

    let mut state = UiState::new(UiSnapshot::default());
    state.apply(UiEvent::SetLocalOperation(Some("读取 Git 快照".into())));
    state.handle_paste("保留草稿");
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CancelLocalOperation]
    );
    assert_eq!(state.composer().text(), "保留草稿");
    assert_eq!(
        state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL)),
        Vec::<UiAction>::new()
    );
}

/// 同一 Thread 的快照刷新保留本地操作；切换 Thread 必须清除旧 Thread 的工作状态。
#[test]
fn local_operation_is_scoped_to_its_thread_across_refresh() {
    let mut initial = UiSnapshot {
        thread_id: Some("thr_local".into()),
        ..UiSnapshot::default()
    };
    let mut state = UiState::new(initial.clone());
    state.apply(UiEvent::SetLocalOperation(Some("读取 Git 快照".into())));

    initial.thread_title = Some("same thread refresh".into());
    state.apply(UiEvent::ReplaceSnapshot(Box::new(initial)));
    assert_eq!(
        state.snapshot().local_operation.as_deref(),
        Some("读取 Git 快照")
    );
    assert_eq!(state.snapshot().turn_state, TurnState::Working);

    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_other".into()),
        ..UiSnapshot::default()
    })));
    assert_eq!(state.snapshot().local_operation, None);
    assert_eq!(state.snapshot().turn_state, TurnState::Idle);
}

/// 面板操作失败时错误必须覆盖快捷键提示，否则用户会误以为确认键没有响应。
#[test]
fn chooser_error_is_visible_above_menu_hint() {
    let mut state = UiState::new(UiSnapshot::default());
    open_command_menu(&mut state);
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
/// @author kongweiguang
#[test]
fn details_viewer_scrolls_and_preserves_composer() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("保留草稿");
    state.apply(UiEvent::ShowDetails {
        entry_id: "tool-1".to_owned(),
        title: "运行测试".to_owned(),
        body: (1..=30).map(|index| format!("LINE-{index:02}\n")).collect(),
    });
    assert!(
        state
            .handle_key(key(KeyCode::Char('c'), KeyModifiers::NONE))
            .is_empty()
    );
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

/// F2 reads from the visible transcript entry; C/Ctrl+Insert copy only acknowledged full text, while Ctrl+C remains close.
/// @author kongweiguang
#[test]
fn ordinary_message_details_gate_copy_and_ignore_late_preview() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_detail_copy".into()),
        timeline: vec![TimelineEntry {
            id: "message-1".into(),
            kind: TimelineKind::User,
            text: "SAFE_PREVIEW".into(),
            detail: Some("正文未完整加载，F2 查看全文".into()),
            status: None,
        }],
        ..UiSnapshot::default()
    });
    state.handle_paste("保留草稿🙂");
    let _ = screen_text(&state, 80, 16);

    assert_eq!(
        state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "message-1".into()
        }]
    );
    let reading = screen_text(&state, 80, 16);
    let reading_compact = reading.replace(' ', "");
    assert!(reading_compact.contains("读取全文中"), "{reading}");
    assert!(!reading_compact.contains("C复制"), "{reading}");
    assert!(
        state
            .handle_key(key(KeyCode::Insert, KeyModifiers::CONTROL))
            .is_empty()
    );
    assert!(
        state
            .handle_key(key(KeyCode::Char('c'), KeyModifiers::NONE))
            .is_empty(),
        "a transcript preview must never enter the full-message copy flow"
    );
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "message-1".into(),
        title: "完整消息".into(),
        body: "FULL_MESSAGE_BODY".into(),
    });
    state.apply(UiEvent::SetDetailsCopyReady {
        entry_id: "message-1".into(),
        ready: true,
    });
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "message-1".into(),
        title: "旧预览".into(),
        body: "LATE_PREVIEW".into(),
    });
    let wide = screen_text(&state, 80, 16);
    let wide_compact = wide.replace(' ', "");
    assert!(wide_compact.contains("C复制全文"), "{wide}");
    assert!(wide_compact.contains("Ctrl+Insert"), "{wide}");
    let narrow = screen_text(&state, 24, 10);
    let narrow_compact = narrow.replace(' ', "");
    assert!(narrow_compact.contains("C复制"), "{narrow}");
    assert!(narrow_compact.contains("Esc"), "{narrow}");
    assert!(!narrow_compact.contains("Ctrl+Insert"), "{narrow}");
    assert_eq!(
        state.handle_key(key(KeyCode::Char('c'), KeyModifiers::NONE)),
        vec![UiAction::CopyDetails {
            entry_id: "message-1".into(),
            text: "FULL_MESSAGE_BODY".into(),
        }]
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Char('C'), KeyModifiers::SHIFT)),
        vec![UiAction::CopyDetails {
            entry_id: "message-1".into(),
            text: "FULL_MESSAGE_BODY".into(),
        }]
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Insert, KeyModifiers::CONTROL)),
        vec![UiAction::CopyDetails {
            entry_id: "message-1".into(),
            text: "FULL_MESSAGE_BODY".into(),
        }]
    );
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "message-1".into()
        }]
    );
    state.apply(UiEvent::SetNotice(Some(
        "消息全文读取失败，请按 F5 重试".into(),
    )));
    let failed = screen_text(&state, 80, 16);
    let failed_compact = failed.replace(' ', "");
    assert!(failed_compact.contains("读取失败"), "{failed}");
    assert!(!failed_compact.contains("C复制"), "{failed}");
    assert!(
        state
            .handle_key(key(KeyCode::Insert, KeyModifiers::CONTROL))
            .is_empty()
    );
    assert!(
        state
            .handle_key(key(KeyCode::Char('C'), KeyModifiers::SHIFT))
            .is_empty(),
        "a failed full read must not make the prior preview copyable"
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL)),
        vec![UiAction::CloseDetails {
            entry_id: "message-1".into()
        }]
    );
    state.apply(UiEvent::SetDetailsCopyReady {
        entry_id: "message-1".into(),
        ready: true,
    });
    assert_eq!(state.composer().text(), "保留草稿🙂");
    assert!(!screen_text(&state, 80, 16).contains("FULL_MESSAGE_BODY"));
}

/// Flush each typed key because the paste-burst classifier intentionally withholds single-character input briefly.
/// @author kongweiguang
#[test]
fn composer_c_remains_text_outside_complete_message_details() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_key(key(KeyCode::Char('c'), KeyModifiers::NONE));
    flush_input_boundary(&mut state);
    assert_eq!(state.composer().text(), "c");
    assert!(
        state
            .handle_key(key(KeyCode::Char('C'), KeyModifiers::SHIFT))
            .is_empty()
    );
    flush_input_boundary(&mut state);
    assert_eq!(state.composer().text(), "cC");
}

/// Diff 重试绑定面板身份；取消或切换后迟到更新不能覆盖新面板，草稿完整保留。
#[test]
fn diff_details_retry_close_and_late_result_preserve_draft() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("保留中文草稿🙂");
    state.apply(UiEvent::ShowDetails {
        entry_id: "diff:first".into(),
        title: "本次变更".into(),
        body: "正在读取本次变更…".into(),
    });
    assert!(
        screen_text(&state, 60, 12)
            .replace(' ', "")
            .contains("正在读取")
    );
    assert!(screen_text(&state, 60, 12).replace(' ', "").contains("F5"));
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::OpenDetails {
            entry_id: "diff:first".into()
        }]
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CloseDetails {
            entry_id: "diff:first".into()
        }]
    );
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "diff:first".into(),
        title: "本次变更".into(),
        body: "迟到正文".into(),
    });
    assert!(
        !screen_text(&state, 60, 12)
            .replace(' ', "")
            .contains("迟到正文")
    );
    assert_eq!(state.composer().text(), "保留中文草稿🙂");
    state.apply(UiEvent::ShowDetails {
        entry_id: "diff:second".into(),
        title: "本次变更".into(),
        body: "新轮次".into(),
    });
    state.apply(UiEvent::UpdateDetailsIfOpen {
        entry_id: "diff:first".into(),
        title: "本次变更".into(),
        body: "旧轮次".into(),
    });
    assert!(
        screen_text(&state, 60, 12)
            .replace(' ', "")
            .contains("新轮次")
    );
    assert!(
        !screen_text(&state, 60, 12)
            .replace(' ', "")
            .contains("旧轮次")
    );
}

/// Esc 只收起可跳过问题；跳过必须显式选中，F2 全文查看后仍返回原题。
#[test]
fn clarification_skip_is_explicit_and_full_question_returns() {
    let mut state = UiState::new(UiSnapshot {
        pending_prompt: Some(PendingPrompt::Clarification {
            thread_id: "thr_demo".to_owned(),
            interaction_id: "interaction_demo".to_owned(),
            expected_interaction_revision: 7,
            operation_key: "idem_demo".to_owned(),
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
            interaction_id: "interaction_demo".to_owned(),
            expected_interaction_revision: 2,
            operation_key: "idem_demo".to_owned(),
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
    open_help_shortcut(&mut help, KeyModifiers::SHIFT);
    assert!(screen_text(&help, 60, 12).contains("Esc"));
    help.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));

    let mut picker = UiState::new(UiSnapshot {
        model_choices: vec![choice("model_a", "model-a"), choice("model_b", "model-b")],
        ..UiSnapshot::default()
    });
    open_command_menu(&mut picker);
    type_chars(&mut picker, "model");
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
    let compact = output
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(!output.contains("thr_"));
    assert!(
        output.contains("测 试 一") && output.contains("测 试 二"),
        "{output}"
    );
    assert!(compact.contains("当前项目"), "{output}");
    assert!(compact.contains("PgDn加载更多"), "{output}");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::SelectThread { id: "thr_1".into() }]
    );
    assert!(
        screen_text(&state, 80, 18)
            .replace(' ', "")
            .contains("恢复会话")
    );
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

/// 没有活动 Thread 时 Esc 和 Ctrl+C 都必须关闭恢复页、清除旧搜索并保留下一步指引。
#[test]
fn resume_picker_cancel_resets_search_and_shows_next_action() {
    for cancel in [
        key(KeyCode::Esc, KeyModifiers::NONE),
        key(KeyCode::Char('c'), KeyModifiers::CONTROL),
    ] {
        let mut state = UiState::new(UiSnapshot {
            thread_choices: vec![choice("thr_old", "旧任务")],
            thread_next_cursor: Some("old-page".into()),
            ..UiSnapshot::default()
        });
        assert_eq!(
            state.handle_key(key(KeyCode::Char('x'), KeyModifiers::NONE)),
            vec![UiAction::SearchThreads {
                query: "x".into(),
                cursor: None,
            }]
        );
        state.apply(UiEvent::SetThreadPage {
            query: "x".into(),
            choices: Vec::new(),
            next_cursor: None,
            append: false,
        });

        assert!(state.handle_key(cancel).is_empty());
        assert_eq!(
            state.snapshot().notice.as_deref(),
            Some("当前项目没有匹配项；可用 /new 新建，或 /resume 重试")
        );
        assert!(state.snapshot().thread_choices.is_empty());
        assert!(state.snapshot().thread_next_cursor.is_none());
    }
}

/// 窄终端也必须同时显示恢复选择所需的导航、确认和返回按键。
#[test]
fn resume_chooser_keeps_actions_visible_at_narrow_width() {
    let state = UiState::new(UiSnapshot {
        thread_choices: vec![choice("thr_one", "一个会话")],
        ..UiSnapshot::default()
    });
    let screen = screen_text(&state, 22, 18)
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();

    assert!(screen.contains("↑↓选"), "{screen}");
    assert!(screen.contains("Enter恢复"), "{screen}");
    assert!(screen.contains("Esc"), "{screen}");
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
        vec![UiAction::CancelInputHistorySearch]
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Char('测'), KeyModifiers::NONE)),
        vec![UiAction::CancelInputHistorySearch]
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
    flush_input_boundary(&mut state);
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
            interaction_id: "interaction_demo".to_owned(),
            expected_interaction_revision: 4,
            operation_key: "idem_demo".to_owned(),
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
    flush_input_boundary(&mut state);
    assert_eq!(state.composer().text(), "原任务草X稿");
}

/// @author kongweiguang
/// PageDown 追加旧回复摘要后仍按 item identity 确认选择，不因列表前插而分叉到另一答复。
#[test]
fn fork_reply_page_load_preserves_selected_item_identity() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_fork_source".to_owned()),
        fork_reply_choices: vec![
            choice("item_older", "较早回复"),
            choice("item_latest", "最新回复"),
        ],
        fork_reply_next_cursor: Some("cursor_older".to_owned()),
        ..UiSnapshot::default()
    });
    state.handle_paste("/fork");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Command(UiCommand::ForkThread)]
    );
    state.apply(UiEvent::SetForkReplyPage {
        choices: vec![
            choice("item_older", "较早回复"),
            choice("item_latest", "最新回复"),
        ],
        next_cursor: Some("cursor_older".to_owned()),
        selected_id: None,
        prepend: false,
    });
    state.handle_key(key(KeyCode::Up, KeyModifiers::NONE));
    assert_eq!(
        state.handle_key(key(KeyCode::PageDown, KeyModifiers::NONE)),
        vec![UiAction::LoadMoreForkReplies {
            thread_id: "thr_fork_source".to_owned()
        }]
    );
    state.apply(UiEvent::SetForkReplyPage {
        choices: vec![choice("item_earliest", "更早回复")],
        next_cursor: None,
        selected_id: None,
        prepend: true,
    });
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::ForkReply {
            item_id: "item_older".to_owned()
        }]
    );
}

/// @author kongweiguang
/// 加载、读取失败、成功空历史和可分页状态分别显示，且只有实际安装游标才提示 PageDown。
#[test]
fn fork_reply_picker_distinguishes_loading_failure_empty_and_paging_states() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_fork_source".to_owned()),
        ..UiSnapshot::default()
    });
    state.handle_paste("/fork");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));

    let loading = screen_text(&state, 100, 18);
    let loading_compact = loading.split_whitespace().collect::<String>();
    assert!(loading_compact.contains("正在加载回复"), "{loading}");
    assert!(!loading_compact.contains("还没有可分叉"), "{loading}");
    assert!(!loading_compact.contains("PgDn更早"));

    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_fork_source".to_owned()),
        ..UiSnapshot::default()
    })));
    let refreshed_loading = screen_text(&state, 100, 18);
    let refreshed_loading_compact = refreshed_loading.split_whitespace().collect::<String>();
    assert!(
        refreshed_loading_compact.contains("正在加载回复"),
        "{refreshed_loading}"
    );

    state.apply(UiEvent::SetForkReplyPage {
        choices: vec![
            choice("item_older", "较早回复"),
            choice("item_latest", "最新回复"),
        ],
        next_cursor: Some("cursor_older".to_owned()),
        selected_id: None,
        prepend: false,
    });
    let pageable = screen_text(&state, 100, 18);
    let pageable_compact = pageable.split_whitespace().collect::<String>();
    assert!(pageable_compact.contains("PgDn更早"));
    assert!(!pageable.contains("正在加载回复"), "{pageable}");

    state.apply(UiEvent::SetForkReplyPage {
        choices: vec![],
        next_cursor: None,
        selected_id: None,
        prepend: false,
    });
    let last_page = screen_text(&state, 100, 18);
    let last_page_compact = last_page.split_whitespace().collect::<String>();
    assert!(!last_page_compact.contains("PgDn更早"));
    assert!(
        last_page_compact.contains("当前会话还没有可分叉"),
        "{last_page}"
    );

    let mut failed = UiState::new(UiSnapshot {
        thread_id: Some("thr_fork_source".to_owned()),
        ..UiSnapshot::default()
    });
    failed.handle_paste("/fork");
    failed.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    failed.apply(UiEvent::ForkReplyLoadFailed);
    let failed_text = screen_text(&failed, 100, 18);
    let failed_compact = failed_text.split_whitespace().collect::<String>();
    assert!(failed_compact.contains("读取回复失败"), "{failed_text}");
    failed.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    let closed_compact = screen_text(&failed, 100, 18)
        .split_whitespace()
        .collect::<String>();
    assert!(!closed_compact.contains("从回复处新建会话"));
}

/// @author kongweiguang
/// 分叉开 child 时编辑器为空白；回到来源后按 Thread 恢复原 Composer 文本及字素光标。
#[test]
fn fork_child_keeps_source_composer_for_later_thread_return() {
    let source = UiSnapshot {
        thread_id: Some("thr_fork_source".to_owned()),
        ..UiSnapshot::default()
    };
    let mut state = UiState::new(source.clone());
    state.handle_paste("/fork");
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::Command(UiCommand::ForkThread)]
    );
    state.apply(UiEvent::SetForkReplyPage {
        choices: vec![choice("item_reply", "待分叉回复")],
        next_cursor: None,
        selected_id: None,
        prepend: false,
    });
    state.apply(UiEvent::ReplaceSnapshot(Box::new(UiSnapshot {
        thread_id: Some("thr_fork_child".to_owned()),
        ..UiSnapshot::default()
    })));
    state.apply(UiEvent::ClearForkDraft);
    assert!(state.composer().is_empty());
    state.apply(UiEvent::ReplaceSnapshot(Box::new(source)));
    assert_eq!(state.composer().text(), "/fork");
    state.handle_key(key(KeyCode::Char('X'), KeyModifiers::NONE));
    flush_input_boundary(&mut state);
    assert_eq!(state.composer().text(), "/foXrk");
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
    flush_input_boundary(&mut editor);
    assert_eq!(editor.composer().text(), "abXc");

    let mut picker = UiState::new(UiSnapshot {
        thread_id: Some("thr_demo".to_owned()),
        model_choices: vec![choice("provider_a/model_a", "model-a")],
        ..UiSnapshot::default()
    });
    open_command_menu(&mut picker);
    type_chars(&mut picker, "model");
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

/// 恢复历史与同 ID 的稳定更新共用 Transcript，缩放重绘不重复追加正文。
#[test]
fn stable_history_update_renders_once_after_resize() {
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
    let initial = screen_text(&state, 48, 12);
    assert!(
        initial.replace(' ', "").contains("已完成的稳定回答"),
        "{initial}"
    );
    state.apply(UiEvent::UpsertEntry(stable));
    let output = screen_text(&state, 60, 12);
    assert_eq!(
        output.replace(' ', "").matches("已完成的稳定回答").count(),
        1,
        "{output:?}"
    );
}

/// 回复终态更新仍更新同一条 Transcript；全屏 Composer 和底栏维持底部位置。
#[test]
fn completed_reply_updates_in_place_while_composer_stays_docked() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_viewport".to_owned()),
        turn_state: TurnState::Working,
        timeline: vec![TimelineEntry {
            id: "answer".to_owned(),
            kind: TimelineKind::Assistant,
            text: "你好".to_owned(),
            detail: None,
            status: Some(TimelineStatus::Running),
        }],
        ..UiSnapshot::default()
    });
    state.apply(UiEvent::UpsertEntry(TimelineEntry {
        id: "answer".to_owned(),
        kind: TimelineKind::Assistant,
        text: "你好".to_owned(),
        detail: None,
        status: Some(TimelineStatus::Complete),
    }));
    state.apply(UiEvent::SetTurnState(TurnState::Idle));
    let rows = screen_rows(&state, 80, 16);
    let composer = rows
        .iter()
        .position(|row| row.trim_start().starts_with('›'))
        .expect("composer remains visible after terminal answer");
    assert!(composer < rows.len(), "{rows:?}");
    assert!(
        rows[..composer]
            .iter()
            .any(|row| row.replace(' ', "").contains("你好")),
        "{rows:?}"
    );
}

/// 时间线以留白区分过程和正文，工具只显示一次标题与结果预览，不重复合成正常终态。
#[test]
fn timeline_uses_plain_replies_and_compact_tools_without_invented_terminal_state() {
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
                preview_truncated: false,
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
    let state = UiState::new(UiSnapshot {
        timeline: entries,
        ..UiSnapshot::default()
    });
    let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    let output = buffer_text(terminal.backend().buffer())
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    for expected in ["›查看目录", "我来检查", "运行Get-Location▸", "目录已确认"]
    {
        assert!(output.contains(expected), "missing {expected}: {output}");
    }
    assert!(!output.contains("────"), "{output}");
    assert_eq!(output.matches("stdout:C:\\tmp").count(), 1, "{output}");
    let visible = buffer_text(terminal.backend().buffer())
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(visible.contains("目录已确认"), "{visible}");
    assert!(!output.contains("done"), "{output}");
}

/// 暂停态 F6/F7 保留草稿和光标，并在首次动作待核实时屏蔽重复提交。
#[test]
fn paused_turn_shortcuts_preserve_draft_and_prevent_duplicate_controls() {
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thr_paused".into()),
        turn_state: TurnState::Paused,
        ..UiSnapshot::default()
    });
    state.handle_paste("保留草稿🙂");
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    let draft = state.composer().text().to_owned();
    let cursor = state.composer().cursor();

    assert_eq!(
        state.handle_key(key(KeyCode::F(6), KeyModifiers::NONE)),
        vec![UiAction::ResumeSuspendedTurn {
            thread_id: "thr_paused".into()
        }]
    );
    assert!(
        state
            .handle_key(key(KeyCode::F(7), KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(state.composer().text(), draft);
    assert_eq!(state.composer().cursor(), cursor);

    state.apply(UiEvent::SetSuspendedControlPending(false));
    assert_eq!(
        state.handle_key(key(KeyCode::F(7), KeyModifiers::NONE)),
        vec![UiAction::CancelSuspendedTurn {
            thread_id: "thr_paused".into()
        }]
    );
    assert_eq!(state.composer().text(), draft);
    assert_eq!(state.composer().cursor(), cursor);
}

/// F6/F7 仅在无面板、无待处理问题的暂停状态生效，错误状态展示 F5 核实入口。
#[test]
fn suspended_turn_shortcuts_obey_ui_gates_and_render_recovery_hints() {
    let mut paused = UiState::new(UiSnapshot {
        thread_id: Some("thr_paused".into()),
        turn_state: TurnState::Paused,
        ..UiSnapshot::default()
    });
    let paused_rendered = screen_text(&paused, 80, 24);
    let paused_visible = paused_rendered
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(
        paused_visible.contains("F6恢复任务·F7取消任务"),
        "{paused_rendered}"
    );
    open_help_shortcut(&mut paused, KeyModifiers::NONE);
    assert!(
        paused
            .handle_key(key(KeyCode::F(6), KeyModifiers::NONE))
            .is_empty()
    );

    let mut pending_question = UiState::new(UiSnapshot {
        thread_id: Some("thr_paused".into()),
        turn_state: TurnState::Paused,
        pending_prompt: Some(PendingPrompt::ToolApproval {
            approval_id: "approval_1".into(),
            thread_id: "thr_paused".into(),
            turn_id: "turn_paused".into(),
            expected_thread_revision: 3,
            prompt: "确认工具".into(),
            choices: vec![],
        }),
        ..UiSnapshot::default()
    });
    let action = pending_question.handle_key(key(KeyCode::F(7), KeyModifiers::NONE));
    assert!(!action.iter().any(|action| matches!(
        action,
        UiAction::ResumeSuspendedTurn { .. } | UiAction::CancelSuspendedTurn { .. }
    )));

    let mut pending = UiState::new(UiSnapshot {
        thread_id: Some("thr_paused".into()),
        turn_state: TurnState::Paused,
        ..UiSnapshot::default()
    });
    pending.apply(UiEvent::SetSuspendedControlPending(true));
    let rendered = screen_text(&pending, 80, 24);
    let rendered_visible = rendered
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(rendered_visible.contains("F5核实恢复结果"), "{rendered}");
    assert!(!rendered_visible.contains("F7取消任务"), "{rendered}");

    let mut working = UiState::new(UiSnapshot {
        thread_id: Some("thr_working".into()),
        turn_state: TurnState::Working,
        ..UiSnapshot::default()
    });
    assert!(
        working
            .handle_key(key(KeyCode::F(6), KeyModifiers::NONE))
            .is_empty()
    );
}

/// 按键构造统一为 key-down，测试只关注 reducer 输出而不依赖终端驱动。
fn key(code: KeyCode, modifiers: KeyModifiers) -> KeyEvent {
    KeyEvent::new(code, modifiers)
}

/// Flush one ASCII boundary so reducer tests model a completed ordinary keystroke, not a paste burst.
/// @author kongweiguang
fn open_command_menu(state: &mut UiState) {
    state.handle_key(key(KeyCode::Char('/'), KeyModifiers::NONE));
    flush_input_boundary(state);
}

/// Release the question-mark shortcut at an explicit input boundary for deterministic UI assertions.
/// @author kongweiguang
fn open_help_shortcut(state: &mut UiState, modifiers: KeyModifiers) {
    state.handle_key(key(KeyCode::Char('?'), modifiers));
    flush_input_boundary(state);
}

/// Complete one ordinary typing burst without invoking a user-visible command such as F5 refresh.
/// @author kongweiguang
fn flush_input_boundary(state: &mut UiState) {
    state.handle_key(key(KeyCode::Null, KeyModifiers::NONE));
}

/// Model separately delivered keystrokes so command-palette tests do not accidentally simulate paste.
/// @author kongweiguang
fn type_chars(state: &mut UiState, text: &str) {
    for ch in text.chars() {
        state.handle_key(key(KeyCode::Char(ch), KeyModifiers::NONE));
        flush_input_boundary(state);
    }
}

/// 用量与操作提示位于身份行之后；菜单或错误反馈不能替换模型、目录与会话标题。
#[test]
fn footer_keeps_identity_while_feedback_changes() {
    let directory = "C:\\dev\\projects\\a-long-workspace-name\\frontend-and-agent-runtime";
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thr_footer".into()),
            model_identifier: Some("gpt-6-sol".into()),
            reasoning_label: Some("high".into()),
            workspace_path: Some(directory.into()),
            thread_title: Some("接口回归验证".into()),
            ..UiSnapshot::default()
        },
        120,
        32,
    );
    for notice in [None, Some("保存失败，请重试".to_owned())] {
        state.apply(UiEvent::SetNotice(notice));
        let rows = screen_rows(&state, 120, 32);
        assert!(rows[29].contains("gpt-6-sol high"), "{rows:?}");
        assert!(rows[29].contains(directory), "{rows:?}");
        assert!(
            rows[29].replace(' ', "").contains("接口回归验证"),
            "{rows:?}"
        );
    }
    assert!(
        screen_rows(&state, 120, 32)[31]
            .replace(' ', "")
            .contains("保存失败")
    );
    state.apply(UiEvent::SetNotice(None));
    open_command_menu(&mut state);
    let rows = screen_rows(&state, 120, 32);
    let input = rows
        .iter()
        .position(|row| row.trim_start().starts_with('›'))
        .unwrap();
    let command = rows.iter().position(|row| row.contains("/model")).unwrap();
    assert!(input < command, "{rows:?}");
    assert!(rows[29].contains(directory), "{rows:?}");
    assert!(
        rows[29].replace(' ', "").contains("接口回归验证"),
        "{rows:?}"
    );
    assert!(rows[31].contains("Esc"), "{rows:?}");
}

/// 标题紧接路径而不推到右端；窄窗保留模型并按显示列安全截断中文与 Emoji。
#[test]
fn footer_places_title_after_directory_and_preserves_model_on_narrow_widths() {
    let compact_state = UiState::new_for_terminal(
        UiSnapshot {
            model_identifier: Some("gpt-6-luna".into()),
            reasoning_label: Some("最高".into()),
            workspace_path: Some("C:\\dev\\rust\\ja".into()),
            thread_title: Some("会话🙂TITLE-END".into()),
            ..UiSnapshot::default()
        },
        80,
        24,
    );
    let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();
    terminal
        .draw(|frame| render(frame, &compact_state))
        .unwrap();
    let cells = terminal
        .backend()
        .buffer()
        .content()
        .chunks(80)
        .nth(22)
        .unwrap();
    let compact = cells
        .iter()
        .map(|cell| cell.symbol())
        .collect::<String>()
        .replace(' ', "");
    assert!(compact.contains("gpt-6-luna最高"), "{compact:?}");
    assert!(compact.contains("C:\\dev\\rust\\ja"), "{compact:?}");
    assert!(compact.contains("会话🙂TITLE-END"), "{compact:?}");
    let model_column = cells.iter().position(|cell| cell.symbol() == "g").unwrap();
    let path_column = cells.iter().position(|cell| cell.symbol() == "C").unwrap();
    let title_column = cells.iter().position(|cell| cell.symbol() == "会").unwrap();
    let title_end_column = cells.iter().position(|cell| cell.symbol() == "D").unwrap();
    assert!(
        model_column < path_column && path_column < title_column,
        "{compact:?}"
    );
    assert_eq!(title_column, path_column + "C:\\dev\\rust\\ja".width() + 3);
    assert!(title_end_column < 77, "短身份行应自然排列: {compact:?}");
    assert_eq!(cells[title_column].fg, Color::Rgb(137, 180, 250));

    let long_state = UiSnapshot {
        model_identifier: Some("gpt-6-luna".into()),
        reasoning_label: Some("最高".into()),
        workspace_path: Some(
            "C:\\dev\\projects\\中文🙂workspace\\frontend-and-agent-runtime\\packages\\desktop"
                .into(),
        ),
        thread_title: Some("长会话标题🙂回归验证".repeat(8)),
        ..UiSnapshot::default()
    };
    for width in [80, 60, 40] {
        let mut terminal = Terminal::new(TestBackend::new(width, 24)).unwrap();
        terminal
            .draw(|frame| render(frame, &UiState::new(long_state.clone())))
            .unwrap();
        let cells = terminal
            .backend()
            .buffer()
            .content()
            .chunks(usize::from(width))
            .nth(22)
            .unwrap();
        let footer = cells
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>()
            .replace(' ', "");
        assert!(
            footer.contains("gpt-6-luna最高"),
            "width={width}: {footer:?}"
        );
        assert!(
            !footer.contains('\u{fffd}'),
            "截断不能拆坏 Emoji 或中文字素，width={width}: {footer:?}"
        );
        assert_eq!(cells.len(), usize::from(width));
        for (column, cell) in cells.iter().enumerate() {
            let symbol_width = UnicodeWidthStr::width(cell.symbol());
            assert!(
                symbol_width == 0 || column + symbol_width <= usize::from(width),
                "cell 绘制越界: width={width}, column={column}, symbol={:?}",
                cell.symbol()
            );
        }
    }
}

/// 帮助是可滚动的局部面板；开关只改变显示，普通问号和本地命令各自保留编辑语义。
#[test]
fn inline_help_scrolls_and_preserves_composer_and_footer() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            model_identifier: Some("gpt-6-sol".into()),
            workspace_path: Some("C:\\dev\\rust\\ja".into()),
            ..UiSnapshot::default()
        },
        40,
        12,
    );
    let idle = screen_rows(&state, 40, 12);
    open_help_shortcut(&mut state, KeyModifiers::SHIFT);
    let top = screen_rows(&state, 40, 12);
    let heading = top
        .iter()
        .position(|row| row.replace(' ', "").contains("快捷键"))
        .unwrap();
    let composer = top
        .iter()
        .position(|row| row.trim_start().starts_with('›'))
        .unwrap();
    assert!(heading < composer, "{top:?}");
    assert!(top[10].contains("gpt-6-sol"), "{top:?}");
    assert!(top[11].contains("Esc") && top[11].contains('?'), "{top:?}");
    state.handle_key(key(KeyCode::End, KeyModifiers::NONE));
    let bottom = screen_text(&state, 40, 12).replace(' ', "");
    assert!(bottom.contains("Ctrl+D"), "{bottom}");
    assert_ne!(screen_rows(&state, 40, 12), top);
    state.handle_key(key(KeyCode::Home, KeyModifiers::NONE));
    assert_eq!(screen_rows(&state, 40, 12), top);
    state.handle_key(key(KeyCode::PageDown, KeyModifiers::NONE));
    assert_ne!(screen_rows(&state, 40, 12), top);
    open_help_shortcut(&mut state, KeyModifiers::SHIFT);
    assert_eq!(screen_rows(&state, 40, 12), idle);
    state.handle_paste("任务？");
    open_help_shortcut(&mut state, KeyModifiers::SHIFT);
    assert_eq!(state.composer().text(), "任务？?");
    state.handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL));
    state.handle_paste("/help");
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    assert!(state.composer().is_empty());
    assert!(
        screen_text(&state, 40, 12)
            .replace(' ', "")
            .contains("快捷键")
    );
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert_eq!(screen_rows(&state, 40, 12), idle);
}

/// 用户消息与 Composer 共用整行灰底、两列正文前缀和折行；真实光标跟随宽字续行。
#[test]
fn user_and_composer_share_gray_wrap_and_cursor_alignment() {
    let body = format!("SAME_HEAD{}🙂TAIL_Z", "界".repeat(24));
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            timeline: vec![TimelineEntry {
                id: "matching-user".into(),
                kind: TimelineKind::User,
                text: body.clone(),
                detail: None,
                status: Some(TimelineStatus::Complete),
            }],
            ..UiSnapshot::default()
        },
        40,
        16,
    );
    state.handle_paste(&body);
    let original = state.composer().text().to_owned();
    let original_cursor = state.composer().cursor();
    let mut terminal = Terminal::new(TestBackend::new(40, 16)).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    let cursor = terminal.backend_mut().get_cursor_position().unwrap();
    let buffer = terminal.backend().buffer();
    let rows = buffer.content().chunks(40).collect::<Vec<_>>();
    let matching_rows = rows
        .iter()
        .enumerate()
        .filter(|(_, row)| compact_cells(row).contains("SAME_HEAD"))
        .map(|(row, _)| row)
        .collect::<Vec<_>>();
    let tail_rows = rows
        .iter()
        .enumerate()
        .filter(|(_, row)| compact_cells(row).contains("TAIL_Z"))
        .map(|(row, _)| row)
        .collect::<Vec<_>>();
    assert_eq!(matching_rows.len(), 2, "{}", buffer_text(buffer));
    assert_eq!(tail_rows.len(), 2, "{}", buffer_text(buffer));
    let (user_row, composer_row) = (matching_rows[0], matching_rows[1]);
    let (user_continuation, composer_continuation) = (tail_rows[0], tail_rows[1]);
    assert!(user_row < user_continuation, "{}", buffer_text(buffer));
    assert!(
        user_continuation < composer_row && composer_row < composer_continuation,
        "{}",
        buffer_text(buffer)
    );
    for (row_index, row) in [
        (user_row - 1, rows[user_row - 1]),
        (user_row, rows[user_row]),
        (user_continuation, rows[user_continuation]),
        (user_continuation + 1, rows[user_continuation + 1]),
        (composer_row - 1, rows[composer_row - 1]),
        (composer_row, rows[composer_row]),
        (composer_continuation, rows[composer_continuation]),
        (composer_continuation + 1, rows[composer_continuation + 1]),
    ] {
        let mut next_rendered_column = 0;
        for (column, cell) in row.iter().enumerate() {
            if column < next_rendered_column {
                continue;
            }
            assert_eq!(
                cell.bg,
                Color::Rgb(38, 40, 43),
                "灰底需覆盖正文与上下留白，row={row_index}, column={column}"
            );
            assert_eq!(
                cell.fg,
                Color::Rgb(238, 239, 241),
                "灰底文字前景需保持可读，row={row_index}, column={column}"
            );
            next_rendered_column = column + UnicodeWidthStr::width(cell.symbol()).max(1);
        }
    }
    for row in [user_row, composer_row] {
        assert_eq!(rows[row][0].symbol(), "›");
        assert_eq!(rows[row][1].symbol(), " ");
        assert_eq!(rows[row][2].symbol(), "S");
    }
    for row in [user_continuation, composer_continuation] {
        assert_eq!(rows[row][0].symbol(), " ");
        assert_eq!(rows[row][1].symbol(), " ");
        assert_eq!(rows[row][2].symbol(), "界");
    }
    assert_eq!(
        rows[user_row]
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>(),
        rows[composer_row]
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>()
    );
    assert_eq!(
        rows[user_continuation]
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>(),
        rows[composer_continuation]
            .iter()
            .map(|cell| cell.symbol())
            .collect::<String>()
    );
    let cursor_column = rows[composer_continuation]
        .iter()
        .rposition(|cell| cell.symbol() == "Z")
        .unwrap() as u16
        + 1;
    assert_eq!(cursor.x, cursor_column);
    assert_eq!(usize::from(cursor.y), composer_continuation);

    for (width, height) in [(60, 12), (20, 8), (8, 5), (4, 3), (40, 16)] {
        state.set_terminal_size(width, height);
        screen_rows(&state, width, height);
        assert_eq!(state.composer().text(), original);
        assert_eq!(state.composer().cursor(), original_cursor);
    }
    state.handle_key(key(KeyCode::Up, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    assert_eq!(state.composer().cursor(), original_cursor);
    state.handle_key(key(KeyCode::Backspace, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), &body[..body.len() - 1]);
}

/// 模型候选与推理等级只是预览；失败和取消不能把未保存的选择写进稳定底栏。
#[test]
fn reasoning_preview_does_not_change_confirmed_footer() {
    let mut state = UiState::new(UiSnapshot {
        model_identifier: Some("confirmed-model".into()),
        reasoning_label: Some("low".into()),
        model_choices: vec![choice("new-model", "preview-model")],
        ..UiSnapshot::default()
    });
    state.handle_paste("保留的任务");
    let cursor = state.composer().cursor();
    state.apply(UiEvent::SetReasoningChoices {
        model_identifier: "preview-model".into(),
        choices: vec![choice("high", "high")],
        selected_id: Some("high".into()),
    });
    state.apply(UiEvent::SetNotice(Some("保存失败，请重试".into())));
    let rows = screen_rows(&state, 80, 18);
    assert!(rows[16].contains("confirmed-model low"), "{rows:?}");
    assert!(!rows[16].contains("preview-model"));
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert_eq!(state.composer().text(), "保留的任务");
    assert_eq!(state.composer().cursor(), cursor);
}

/// Composer 直接在全屏帧中按终端可用空间折行，缩放保留草稿与字素光标。
#[test]
fn composer_reflows_with_the_terminal_and_keeps_the_full_draft() {
    let draft = "第一行中文🙂\n第二行组合e\u{301}";
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thr_composer_resize".into()),
            model_identifier: Some("gpt-6-sol".into()),
            ..UiSnapshot::default()
        },
        80,
        32,
    );
    state.handle_paste(draft);
    let cursor = state.composer().cursor();
    for (width, height) in [(120, 32), (80, 24), (40, 10), (120, 32)] {
        state.set_terminal_size(width, height);
        let rows = screen_rows(&state, width, height);
        assert!(
            rows.iter().any(|row| row.trim_start().starts_with('›')),
            "{width}x{height}: {rows:?}"
        );
        assert!(
            rows.join("\n").contains("gpt-6-sol"),
            "{width}x{height}: {rows:?}"
        );
        assert_eq!(state.composer().text(), draft);
        assert_eq!(state.composer().cursor(), cursor);
    }
}
/// 回执未知保持提交锁；用户只能核实原操作或收起问题，不能重复响应审批。
#[test]
fn unknown_prompt_receipt_offers_refresh_without_replay() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            model_identifier: Some("gpt-6-sol".into()),
            pending_prompt: Some(PendingPrompt::ToolApproval {
                approval_id: "approval_unknown".into(),
                thread_id: "thr_unknown".into(),
                turn_id: "turn_unknown".into(),
                expected_thread_revision: 7,
                prompt: "允许执行操作？".into(),
                choices: vec![choice("approve", "允许"), choice("deny", "拒绝")],
            }),
            ..UiSnapshot::default()
        },
        60,
        12,
    );
    assert!(matches!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .as_slice(),
        [UiAction::RespondToolApproval { .. }]
    ));
    state.apply(UiEvent::SetNotice(Some(
        "回执未知，读取后台状态失败".repeat(20),
    )));
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    let rows = screen_rows(&state, 60, 12);
    assert!(rows[10].contains("gpt-6-sol"), "{rows:?}");
    assert!(
        rows[11].contains("F5") && rows[11].contains("Esc"),
        "{rows:?}"
    );
    assert_eq!(
        state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE)),
        vec![UiAction::RefreshSnapshot]
    );
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
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

/// 宽字续格会插入空白 cell，文字定位先压掉占位而保留真实绘制断言。
fn compact_cells(cells: &[ratatui::buffer::Cell]) -> String {
    cells
        .iter()
        .map(|cell| cell.symbol())
        .filter(|symbol| !symbol.chars().all(char::is_whitespace))
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

/// 将 Ratatui 绘制 buffer 的 cell 符号拼为文本，验证终端最终可见内容。
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

/// 大 Tool detail 在状态中按安全预算清理，Transcript 只绘制有界预览。
/// @author kongweiguang
#[test]
fn mebibyte_tool_detail_is_sanitized_and_rendered_as_bounded_preview() {
    let entry = TimelineEntry {
        id: "large-tool".to_owned(),
        kind: TimelineKind::Tool {
            action: "读取".to_owned(),
            target: "large.log".to_owned(),
            preview_truncated: false,
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
    let retained_detail = state.snapshot().timeline[0].detail.as_ref().unwrap();
    let retained_bytes = retained_detail.len();
    assert_eq!(retained_bytes, 96 * 1024 + "\n…内容已截断".len());
    assert!(retained_detail.ends_with("\n…内容已截断"));

    let mut terminal = Terminal::new(TestBackend::new(80, 24)).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    let output = buffer_text(terminal.backend().buffer());
    let visible = output
        .chars()
        .filter(|ch| !ch.is_whitespace())
        .collect::<String>();
    assert!(visible.contains("large.log"));
    assert!(visible.contains("Ctrl+O查看详情"));
    assert!(terminal.backend().buffer().content().len() <= 80 * 24);
    eprintln!(
        "ui_large_output input_bytes={} event_queue_items={} estimated_queued_bytes={} retained_bytes={} screen_cells={}",
        1024 * 1024,
        events.buffered_len(),
        queued_payload_bytes,
        retained_bytes,
        terminal.backend().buffer().content().len(),
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

/// Plan 创建菜单项只补入可编辑命令，提交动作携带完整目标而不会走普通消息分支。
#[test]
fn plan_create_menu_preserves_objective_for_the_controller() {
    let mut state = UiState::new(UiSnapshot::default());
    open_command_menu(&mut state);
    type_chars(&mut state, "plan");
    let rendered = screen_text(&state, 80, 24);
    assert!(
        rendered.replace(' ', "").contains("创建并规划目标"),
        "{rendered}"
    );
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    assert!(
        state
            .handle_key(key(KeyCode::Enter, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(state.composer().text(), "/plan create ");

    state.handle_paste("修复构建失败");
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::CreatePlan {
            objective: "修复构建失败".into()
        }]
    );
    assert!(state.composer().is_empty());
}

/// 帮助把创建与执行分开说明，Plan 的创建不会被误认为已授权执行。
#[test]
fn inline_help_explains_plan_creation_and_execution_commands() {
    let mut state = UiState::new(UiSnapshot::default());
    open_help_shortcut(&mut state, KeyModifiers::SHIFT);
    let help = screen_text(&state, 80, 24);
    assert!(help.contains("/plan create"), "{help}");
    assert!(help.contains("/plan execute"), "{help}");
}

/// 构造与当前 v2 用户层契约一致的模型配置读取响应，避免测试虚构字段形状。
fn model_settings_configuration(
    provider: Option<(&str, &str, &str, &str, &str)>,
    user_version: &str,
) -> serde_json::Value {
    let mut providers = Vec::new();
    let mut default_provider_id = serde_json::Value::Null;
    let mut default_model_id = serde_json::Value::Null;
    if let Some((provider_id, model_id, credential_id, base_url, provider_name)) = provider {
        default_provider_id = serde_json::Value::String(provider_id.to_owned());
        default_model_id = serde_json::Value::String(model_id.to_owned());
        providers.push(serde_json::json!({
            "provider_id": provider_id,
            "name": provider_name,
            "api": "openai_responses",
            "base_url": base_url,
            "credential_id": credential_id,
            "network_timeouts": {"connect_timeout_ms": 10000, "request_timeout_ms": 300000},
            "agent_defaults": {"context": {"auto_compact": true}},
            "models": [{
                "model_id": model_id,
                "name": "upstream-model",
                "model": "upstream-model",
                "capabilities": {"context_window_tokens": 128000, "max_output_tokens": 16384, "supports_output_schema": false},
                "reasoning_level_map": {},
                "default_reasoning_level": null
            }]
        }));
    }
    serde_json::json!({
        "user": {"status": "ok", "document": {
            "schema_version": 2,
            "config_revision": 0,
            "default_access_mode": "full_access",
            "interaction": {"clarification_enabled": true},
            "default_provider_id": default_provider_id,
            "default_model_id": default_model_id,
            "default_reasoning_level": null,
            "subagents": {"enabled": true, "provider_id": null, "model_id": null, "reasoning_level": null},
            "providers": providers,
            "mcp_servers": [],
            "disabled_skills": []
        }},
        "cas": {"userVersion": user_version, "credentialVersion": "credential-v1"},
        "credentials": {}
    })
}

/// 打开设置只切换渲染层；Composer 的正文和光标仍由主 UiState 原样持有。
fn open_model_settings_for_test(state: &mut UiState, configuration: serde_json::Value) {
    state.apply(UiEvent::OpenModelSettings {
        operation_id: "settings-read-1".to_owned(),
        standalone: true,
    });
    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id: "settings-read-1".to_owned(),
            outcome: ja_cli::ui::ModelSettingsOutcome::Loaded { configuration },
        },
    ));
}

/// 构造默认与子智能体均引用目标模型、且同一供应商另有可替代模型的删除快照。
fn model_settings_delete_configuration(subagent_model_id: &str) -> serde_json::Value {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let mut configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            "cred_0123456789abcdef0123456789abcdef",
            "https://api.example.test/v1",
            "示例供应商",
        )),
        "user-v1",
    );
    configuration["user"]["document"]["default_reasoning_level"] = serde_json::json!("high");
    let mut alternate = configuration["user"]["document"]["providers"][0]["models"][0].clone();
    alternate["model_id"] =
        serde_json::Value::String("model_abcdef0123456789abcdef0123456789".to_owned());
    alternate["model"] = serde_json::Value::String("upstream-alternate".to_owned());
    alternate["name"] = serde_json::Value::String("替代模型".to_owned());
    configuration["user"]["document"]["providers"][0]["models"]
        .as_array_mut()
        .unwrap()
        .push(alternate);
    configuration["user"]["document"]["subagents"] = serde_json::json!({
        "enabled": true,
        "provider_id": provider_id,
        "model_id": subagent_model_id,
        "reasoning_level": "high"
    });
    configuration
}

/// 首次创建缺 Key 时保留同一稳定实体；补 Key 重试只能发凭据写入且 patch 为空。
#[test]
fn model_settings_credential_retry_reuses_ids_without_duplicate_entities() {
    let mut state = UiState::new(UiSnapshot::default());
    state.handle_paste("保留中的草稿🙂");
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    let original_cursor = state.composer().cursor();
    open_model_settings_for_test(&mut state, model_settings_configuration(None, "user-v1"));
    assert_eq!(state.composer().text(), "保留中的草稿🙂");
    assert_eq!(state.composer().cursor(), original_cursor);

    state.handle_key(key(KeyCode::Char('a'), KeyModifiers::NONE));
    state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    let advanced_form = screen_text(&state, 80, 24);
    assert!(
        advanced_form.replace(' ', "").contains("连接超时"),
        "{advanced_form}"
    );
    state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_paste("https://api.example.test/v1");
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_paste("unit-test-secret");
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_paste("upstream-model");
    let credential_form = screen_text(&state, 80, 24);
    assert!(
        !credential_form.contains("unit-test-secret"),
        "{credential_form}"
    );
    let first_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));

    let (operation_id, provider_id, model_id, credential_id) = match first_actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                operation_id,
                patch,
                credential: Some(credential),
                ..
            }),
        ] => {
            let (provider_id, provider_patch) = patch["providers"]
                .as_object()
                .unwrap()
                .iter()
                .next()
                .unwrap();
            let (model_id, model_patch) = provider_patch["models"]
                .as_object()
                .unwrap()
                .iter()
                .next()
                .unwrap();
            assert!(!provider_patch.get("provider_id").is_some());
            assert!(!model_patch.get("model_id").is_some());
            assert_eq!(credential.provider_id.as_str(), provider_id.as_str());
            let debug = format!("{credential:?}");
            assert!(!debug.contains("unit-test-secret"));
            (
                operation_id.clone(),
                provider_id.clone(),
                model_id.clone(),
                credential.credential_id.clone(),
            )
        }
        other => panic!("expected initial model settings save, got {other:?}"),
    };
    drop(first_actions);

    let saved_configuration = model_settings_configuration(
        Some((
            &provider_id,
            &model_id,
            &credential_id,
            "https://api.example.test/v1",
            "新供应商",
        )),
        "user-v2",
    );
    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id,
            outcome: ja_cli::ui::ModelSettingsOutcome::NeedsCredential {
                configuration: saved_configuration,
                message: "credential write failed".to_owned(),
            },
        },
    ));
    let retry_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    match retry_actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                patch,
                credential: Some(credential),
                ..
            }),
        ] => {
            assert_eq!(patch, &serde_json::json!({}));
            assert_eq!(credential.provider_id, provider_id);
            assert_eq!(credential.credential_id, credential_id);
        }
        other => panic!("expected credential-only retry, got {other:?}"),
    }
}

/// 配置文本按桌面契约的 UTF-16 长度校验，中文不因 UTF-8 字节数提前拒绝。
#[test]
fn model_settings_text_limit_counts_utf16_units() {
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, model_settings_configuration(None, "user-v1"));
    state.handle_key(key(KeyCode::Char('a'), KeyModifiers::NONE));
    for _ in 0..4 {
        state.handle_key(key(KeyCode::Backspace, KeyModifiers::NONE));
    }
    state.handle_paste(&"中".repeat(171));
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_paste("https://api.example.test/v1");
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_paste("unit-test-secret");
    state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    state.handle_paste("upstream-model");

    let actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    assert!(
        matches!(
            actions.as_slice(),
            [UiAction::ModelSettings(
                ja_cli::ui::ModelSettingsRequest::Save { .. }
            )]
        ),
        "171 UTF-16 units should be accepted even though UTF-8 byte length exceeds 512"
    );
}

/// 冲突回读合并用户姓名意图，同时表单二次保存不把未编辑 URL 恢复成旧快照。
#[test]
fn model_settings_conflict_preserves_new_remote_fields_when_retrying() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let credential_id = "cred_0123456789abcdef0123456789abcdef";
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(
        &mut state,
        model_settings_configuration(
            Some((
                provider_id,
                model_id,
                credential_id,
                "https://old.example.test/v1",
                "现有供应商",
            )),
            "user-v1",
        ),
    );
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::End, KeyModifiers::NONE));
    for character in "-edited".chars() {
        state.handle_key(key(KeyCode::Char(character), KeyModifiers::NONE));
    }
    let first_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    let (operation_id, first_patch) = match first_actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                operation_id,
                patch,
                ..
            }),
        ] => (operation_id.clone(), patch.clone()),
        other => panic!("expected edited provider save, got {other:?}"),
    };
    assert_eq!(
        first_patch["providers"][provider_id]["name"],
        "现有供应商-edited"
    );
    let remote_configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            credential_id,
            "https://remote.example.test/v1",
            "现有供应商",
        )),
        "user-v2",
    );
    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id,
            outcome: ja_cli::ui::ModelSettingsOutcome::Conflict {
                configuration: Some(remote_configuration),
                message: "version changed".to_owned(),
            },
        },
    ));
    let retry_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    match retry_actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                patch,
                credential: None,
                ..
            }),
        ] => {
            assert_eq!(patch["providers"][provider_id]["name"], "现有供应商-edited");
            assert!(patch["providers"][provider_id].get("base_url").is_none());
        }
        other => panic!("expected rebased provider save, got {other:?}"),
    }
}

/// 删除最后一个模型时先转换成供应商确认，默认与子智能体引用随同一 patch 清理。
#[test]
fn model_settings_deleting_last_model_confirms_provider_and_clears_references() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let mut configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            "cred_0123456789abcdef0123456789abcdef",
            "https://api.example.test/v1",
            "示例供应商",
        )),
        "user-v1",
    );
    configuration["user"]["document"]["subagents"] = serde_json::json!({
        "enabled": true,
        "provider_id": provider_id,
        "model_id": model_id,
        "reasoning_level": null
    });
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration.clone());
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    let delete_actions = state.handle_key(key(KeyCode::Delete, KeyModifiers::NONE));
    assert!(delete_actions.is_empty());

    let confirmation = screen_text(&state, 100, 24);
    let compact_confirmation = confirmation.replace(' ', "");
    assert!(
        compact_confirmation.contains("删除供应商示例供应商"),
        "{confirmation}"
    );
    assert!(
        compact_confirmation.contains("并清除全局默认模型"),
        "{confirmation}"
    );
    assert!(
        compact_confirmation.contains("子智能体跟随主任务"),
        "{confirmation}"
    );
    assert!(
        !compact_confirmation.contains("删除模型upstream-model"),
        "{confirmation}"
    );

    let narrow_start = screen_text(&state, 40, 10);
    assert!(
        narrow_start
            .replace(' ', "")
            .contains("删除供应商示例供应商"),
        "最后一个模型应转换成可见的供应商删除确认：{narrow_start}"
    );
    state.handle_key(key(KeyCode::PageDown, KeyModifiers::NONE));
    let narrow_after_scroll = screen_text(&state, 40, 10);
    let narrow_confirmation = format!("{narrow_start}{narrow_after_scroll}")
        .chars()
        .filter(|character| !character.is_whitespace())
        .collect::<String>();
    assert!(
        narrow_confirmation.contains("子智能体跟随主任务"),
        "{narrow_confirmation}"
    );
    assert!(
        narrow_confirmation.contains("PgUp/Dn滚动"),
        "{narrow_confirmation}"
    );
    assert!(
        narrow_confirmation.contains("Esc取消"),
        "{narrow_confirmation}"
    );
    assert!(
        narrow_confirmation.contains("Ctrl+D退出"),
        "{narrow_confirmation}"
    );

    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    match actions.as_slice() {
        [UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save { patch, .. })] => {
            assert_eq!(patch["providers"][provider_id], serde_json::Value::Null);
            assert_eq!(
                patch["default_provider_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["default_model_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["provider_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["model_id"],
                serde_json::json!({"__ja_null": true})
            );
        }
        other => panic!("expected atomic provider deletion patch, got {other:?}"),
    }
}

/// 默认模型删除的每个确认动作都自带默认及子智能体影响，不保留隐式删除入口。
#[test]
fn model_settings_default_delete_actions_are_explicit_and_atomic() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let alternate_id = "model_abcdef0123456789abcdef0123456789";
    let configuration = model_settings_delete_configuration(model_id);
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration.clone());
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Delete, KeyModifiers::NONE));
    let confirmation = screen_text(&state, 110, 24);
    let compact_confirmation = confirmation.replace(' ', "");
    assert!(
        compact_confirmation.contains("删除模型upstream-model并改用upstream-alternate"),
        "{confirmation}"
    );
    assert!(
        compact_confirmation.contains("删除模型upstream-model并清除全局默认模型"),
        "{confirmation}"
    );
    assert_eq!(
        compact_confirmation.matches("子智能体跟随主任务").count(),
        2,
        "{confirmation}"
    );
    assert!(!compact_confirmation.contains("先选替代"), "{confirmation}");

    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let replacement_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    match replacement_actions.as_slice() {
        [UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save { patch, .. })] => {
            assert_eq!(
                patch["providers"][provider_id]["models"][model_id],
                serde_json::Value::Null
            );
            assert!(patch.get("default_provider_id").is_none());
            assert_eq!(patch["default_model_id"], alternate_id);
            assert_eq!(
                patch["default_reasoning_level"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["provider_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["model_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["reasoning_level"],
                serde_json::json!({"__ja_null": true})
            );
        }
        other => panic!("expected replacement deletion patch, got {other:?}"),
    }

    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration.clone());
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Delete, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let clear_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    let clear_operation_id = match clear_actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                operation_id,
                patch,
                ..
            }),
        ] => {
            assert_eq!(
                patch["providers"][provider_id]["models"][model_id],
                serde_json::Value::Null
            );
            assert_eq!(
                patch["default_provider_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["default_model_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["default_reasoning_level"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["provider_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["model_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["reasoning_level"],
                serde_json::json!({"__ja_null": true})
            );
            operation_id.clone()
        }
        other => panic!("expected explicit clear-default deletion patch, got {other:?}"),
    };

    let mut authoritative = configuration;
    authoritative["user"]["document"]["providers"][0]["models"]
        .as_array_mut()
        .unwrap()
        .retain(|model| model["model_id"] != model_id);
    authoritative["user"]["document"]["default_provider_id"] = serde_json::Value::Null;
    authoritative["user"]["document"]["default_model_id"] = serde_json::Value::Null;
    authoritative["user"]["document"]["default_reasoning_level"] = serde_json::Value::Null;
    authoritative["user"]["document"]["subagents"]["provider_id"] = serde_json::Value::Null;
    authoritative["user"]["document"]["subagents"]["model_id"] = serde_json::Value::Null;
    authoritative["user"]["document"]["subagents"]["reasoning_level"] = serde_json::Value::Null;
    authoritative["cas"]["userVersion"] = serde_json::json!("user-v2");
    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id: clear_operation_id,
            outcome: ja_cli::ui::ModelSettingsOutcome::Conflict {
                configuration: Some(authoritative),
                message: "remote already cleared references".to_owned(),
            },
        },
    ));
    assert!(
        state
            .handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL))
            .is_empty(),
        "conflict rebase must decode nullable sentinels instead of submitting them again"
    );
}

/// 清除模型默认推理档位走显式 null 哨兵，冲突合并后不把哨兵对象留进本地文档。
#[test]
fn model_settings_default_reasoning_null_survives_conflict_rebase() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let mut configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            "cred_0123456789abcdef0123456789abcdef",
            "https://api.example.test/v1",
            "示例供应商",
        )),
        "user-v1",
    );
    configuration["user"]["document"]["providers"][0]["models"][0]["default_reasoning_level"] =
        serde_json::json!("off");
    configuration["user"]["document"]["providers"][0]["models"][0]["reasoning_level_map"] =
        serde_json::json!({"off": "off"});
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration.clone());
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    for _ in 0..6 {
        state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    }
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    let save_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    let operation_id = match save_actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                operation_id,
                patch,
                ..
            }),
        ] => {
            assert_eq!(
                patch["providers"][provider_id]["models"][model_id]["default_reasoning_level"],
                serde_json::json!({"__ja_null": true})
            );
            operation_id.clone()
        }
        other => panic!("expected nullable reasoning patch, got {other:?}"),
    };

    configuration["user"]["document"]["providers"][0]["models"][0]["default_reasoning_level"] =
        serde_json::Value::Null;
    configuration["cas"]["userVersion"] = serde_json::json!("user-v2");
    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id,
            outcome: ja_cli::ui::ModelSettingsOutcome::Conflict {
                configuration: Some(configuration),
                message: "remote already cleared reasoning default".to_owned(),
            },
        },
    ));
    assert!(
        state
            .handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL))
            .is_empty(),
        "rebased nullable reasoning must be JSON null, not the protocol sentinel"
    );
}

/// 窄屏删除确认可用 PageDown 查看完整上游 ID 与关联影响，footer 始终保留退出和取消。
#[test]
fn model_settings_short_delete_confirmation_scrolls_target_and_impact() {
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let configuration = model_settings_delete_configuration(model_id);
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration);
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Delete, KeyModifiers::NONE));

    let first = screen_text(&state, 40, 10);
    let first_compact = first
        .chars()
        .filter(|character| !character.is_whitespace())
        .collect::<String>();
    assert!(first_compact.contains("确认删除"), "{first}");
    assert!(first_compact.contains("Enter确认"), "{first}");
    assert!(first_compact.contains("Esc取消"), "{first}");
    state.handle_key(key(KeyCode::PageDown, KeyModifiers::NONE));
    let second = screen_text(&state, 40, 10);
    let visible = format!("{first}{second}")
        .chars()
        .filter(|character| !character.is_whitespace())
        .collect::<String>();
    assert!(visible.contains("upstream-model"), "{visible}");
    assert!(visible.contains("upstream-alternate"), "{visible}");
    assert!(visible.contains("子智能体跟随主任务"), "{visible}");
    assert!(visible.contains("Ctrl+D退出"), "{visible}");
}

/// 删除被子智能体引用的非默认模型时，唯一删除动作仍明确说明将跟随主任务。
#[test]
fn model_settings_nondefault_delete_explains_subagent_change() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let subagent_id = "model_abcdef0123456789abcdef0123456789";
    let configuration = model_settings_delete_configuration(subagent_id);
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration);
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::Delete, KeyModifiers::NONE));

    let confirmation = screen_text(&state, 100, 24);
    let compact_confirmation = confirmation.replace(' ', "");
    assert!(
        compact_confirmation.contains("删除模型upstream-alternate"),
        "{confirmation}"
    );
    assert!(
        compact_confirmation.contains("子智能体跟随主任务"),
        "{confirmation}"
    );

    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    let actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    match actions.as_slice() {
        [UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save { patch, .. })] => {
            assert_eq!(
                patch["providers"][provider_id]["models"][subagent_id],
                serde_json::Value::Null
            );
            assert!(patch.get("default_model_id").is_none());
            assert_eq!(
                patch["subagents"]["provider_id"],
                serde_json::json!({"__ja_null": true})
            );
            assert_eq!(
                patch["subagents"]["model_id"],
                serde_json::json!({"__ja_null": true})
            );
        }
        other => panic!("expected non-default deletion patch, got {other:?}"),
    }
}

/// 迟到配置结果不覆盖表单，后台 Turn 事件继续规约，关闭后 Composer 引用、光标与撤销链仍原样可用。
#[test]
fn model_settings_overlay_preserves_composer_and_background_reducer_events() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let mut state = UiState::new(UiSnapshot {
        thread_id: Some("thread_settings_overlay".to_owned()),
        ..UiSnapshot::default()
    });
    state.handle_paste("前缀 @fi");
    state.apply(UiEvent::InsertFileReference {
        workspace_id: "ws_settings_test".to_owned(),
        relative_path: "中文目录/fixture.txt".to_owned(),
        kind: "file".to_owned(),
    });
    state.handle_paste("尾巴");
    state.handle_key(key(KeyCode::Left, KeyModifiers::NONE));
    let original_text = state.composer().text().to_owned();
    let original_cursor = state.composer().cursor();
    let original_references = state.composer().references().to_vec();
    assert_eq!(original_references.len(), 1);

    let configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            "cred_0123456789abcdef0123456789abcdef",
            "https://api.example.test/v1",
            "当前供应商",
        )),
        "user-v1",
    );
    open_model_settings_for_test(&mut state, configuration);
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));

    let stale_configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            "cred_0123456789abcdef0123456789abcdef",
            "https://stale.example.test/v1",
            "迟到配置",
        )),
        "stale-version",
    );
    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id: "settings-read-old".to_owned(),
            outcome: ja_cli::ui::ModelSettingsOutcome::Loaded {
                configuration: stale_configuration,
            },
        },
    ));
    let form = screen_text(&state, 100, 24).replace(' ', "");
    assert!(form.contains("当前供应商"), "{form}");
    assert!(!form.contains("迟到配置"), "{form}");

    state.apply(UiEvent::SetTurnState(TurnState::Working));
    state.apply(UiEvent::UpsertEntry(TimelineEntry {
        id: "background-reply".to_owned(),
        kind: TimelineKind::Assistant,
        text: "后台回复".to_owned(),
        detail: None,
        status: None,
    }));
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "background-reply".to_owned(),
        delta: "已完成".to_owned(),
    });
    state.apply(UiEvent::FinishAssistantStream {
        turn_id: "turn-settings-overlay".to_owned(),
    });
    state.apply(UiEvent::SetTurnState(TurnState::Idle));
    assert_eq!(state.snapshot().turn_state, TurnState::Idle);
    assert_eq!(state.snapshot().timeline[0].text, "后台回复已完成");
    assert!(
        screen_text(&state, 100, 24)
            .replace(' ', "")
            .contains("供应商设置")
    );

    assert!(
        state
            .handle_key(key(KeyCode::Esc, KeyModifiers::NONE))
            .is_empty()
    );
    assert_eq!(
        state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE)),
        vec![UiAction::CloseModelSettings {
            start_conversation: false
        }]
    );
    assert_eq!(state.composer().text(), original_text);
    assert_eq!(state.composer().cursor(), original_cursor);
    assert_eq!(state.composer().references(), original_references);

    state.handle_key(key(KeyCode::Char('z'), KeyModifiers::CONTROL));
    assert_eq!(state.composer().text(), "前缀 @中文目录/fixture.txt ");
    assert_eq!(state.composer().references(), original_references);
    state.handle_key(key(KeyCode::Char('z'), KeyModifiers::CONTROL));
    assert_eq!(state.composer().text(), "前缀 @fi");
    assert!(state.composer().references().is_empty());
}

/// 保存只在匹配操作确认后返回会话选择；独立设置继续管理，两个路径均保留草稿和原模型。
#[test]
fn model_settings_saved_returns_existing_thread_to_picker_only() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            "cred_0123456789abcdef0123456789abcdef",
            "https://api.example.test/v1",
            "现有供应商",
        )),
        "user-v1",
    );
    for has_thread in [true, false] {
        let mut state = UiState::new(UiSnapshot {
            thread_id: has_thread.then(|| "thread_settings_save".to_owned()),
            model_identifier: Some("原会话模型".to_owned()),
            model_choices: vec![choice("current-model", "原会话模型")],
            ..UiSnapshot::default()
        });
        if has_thread {
            state.handle_paste("/model");
            state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
        }
        state.apply(UiEvent::RestoreDraft("保留草稿🙂".to_owned()));
        let original_cursor = state.composer().cursor();
        open_model_settings_for_test(&mut state, configuration.clone());
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
        state.handle_key(key(KeyCode::End, KeyModifiers::NONE));
        state.handle_paste("-edited");
        let actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
        let operation_id = match actions.as_slice() {
            [
                UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                    operation_id,
                    ..
                }),
            ] => operation_id.clone(),
            other => panic!("expected provider save, got {other:?}"),
        };
        state.apply(UiEvent::ModelSettingsResult(
            ja_cli::ui::ModelSettingsResult {
                operation_id: "stale-save".to_owned(),
                outcome: ja_cli::ui::ModelSettingsOutcome::Saved {
                    configuration: configuration.clone(),
                },
            },
        ));
        assert!(
            screen_text(&state, 100, 24)
                .replace(' ', "")
                .contains("供应商设置")
        );

        state.apply(UiEvent::ModelSettingsResult(
            ja_cli::ui::ModelSettingsResult {
                operation_id,
                outcome: ja_cli::ui::ModelSettingsOutcome::Saved {
                    configuration: configuration.clone(),
                },
            },
        ));
        let screen = screen_text(&state, 100, 24).replace(' ', "");
        if has_thread {
            assert!(screen.contains("选择模型"), "{screen}");
            assert!(!screen.contains("模型设置"), "{screen}");
        } else {
            assert!(screen.contains("模型配置已保存并核实"), "{screen}");
            assert!(screen.contains("Ctrl+↵对话"), "{screen}");
        }
        assert_eq!(state.composer().text(), "保留草稿🙂");
        assert_eq!(state.composer().cursor(), original_cursor);
        assert_eq!(
            state.snapshot().model_identifier.as_deref(),
            Some("原会话模型")
        );
    }
}

/// 40×10 窗口展开高级字段并滚动到底部时仍显示校验错误和离开方式。
#[test]
fn model_settings_short_advanced_form_keeps_error_and_exit_footer_visible() {
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, model_settings_configuration(None, "user-v1"));
    state.handle_key(key(KeyCode::Char('a'), KeyModifiers::NONE));
    state.handle_key(key(KeyCode::F(2), KeyModifiers::NONE));
    for _ in 0..6 {
        state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    }
    assert!(
        state
            .handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL))
            .is_empty()
    );

    let screen = screen_text(&state, 40, 10);
    let compact_screen = screen.replace(' ', "");
    assert!(compact_screen.contains("BaseURL"), "{screen}");
    assert!(
        screen_rows(&state, 40, 10)
            .iter()
            .any(|row| row.contains("› ") && row.contains("Base URL")),
        "校验错误应把焦点移到可见 Base URL 字段：{screen}"
    );
    assert!(compact_screen.contains("Esc返回"), "{screen}");
    assert!(compact_screen.contains("Ctrl+D退出"), "{screen}");
}

/// 读取、保存及不确定回执期间统一锁定表单输入；只有核实或明确放弃可结束未知写入。
#[test]
fn model_settings_pending_operations_lock_keyboard_and_paste() {
    let provider_id = "provider_0123456789abcdef0123456789abcdef";
    let model_id = "model_0123456789abcdef0123456789abcdef";
    let credential_id = "cred_0123456789abcdef0123456789abcdef";
    let configuration = model_settings_configuration(
        Some((
            provider_id,
            model_id,
            credential_id,
            "https://api.example.test/v1",
            "现有供应商",
        )),
        "user-v1",
    );

    let mut reading = UiState::new(UiSnapshot::default());
    reading.apply(UiEvent::OpenModelSettings {
        operation_id: "pending-read".to_owned(),
        standalone: true,
    });
    reading.handle_paste("不能进入读取中的草稿");
    assert!(
        reading
            .handle_key(key(KeyCode::Char('a'), KeyModifiers::NONE))
            .is_empty()
    );
    let pending_read_screen = screen_text(&reading, 80, 24)
        .chars()
        .filter(|character| !character.is_whitespace())
        .collect::<String>();
    assert!(
        pending_read_screen.contains("正在从AppServer读取"),
        "{pending_read_screen}"
    );
    assert!(
        !pending_read_screen.contains("供应商设置"),
        "{pending_read_screen}"
    );

    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration.clone());
    state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    state.handle_key(key(KeyCode::End, KeyModifiers::NONE));
    for character in "-草稿".chars() {
        state.handle_key(key(KeyCode::Char(character), KeyModifiers::NONE));
    }
    let save_actions = state.handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL));
    let operation_id = match save_actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Save {
                operation_id, ..
            }),
        ] => operation_id.clone(),
        other => panic!("expected provider save, got {other:?}"),
    };

    state.handle_paste("保存中不能追加");
    state.handle_key(key(KeyCode::Char('!'), KeyModifiers::NONE));
    let pending_save = screen_text(&state, 100, 24).replace(' ', "");
    assert!(pending_save.contains("现有供应商-草稿"), "{pending_save}");
    assert!(!pending_save.contains("保存中不能追加"), "{pending_save}");
    assert!(!pending_save.contains("现有供应商-草稿!"), "{pending_save}");

    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id,
            outcome: ja_cli::ui::ModelSettingsOutcome::Uncertain {
                configuration: Some(configuration),
                message: "acknowledgement lost".to_owned(),
            },
        },
    ));
    state.handle_paste("未知写入期间不能追加");
    state.handle_key(key(KeyCode::Char('x'), KeyModifiers::NONE));
    let uncertain_form = screen_text(&state, 100, 24).replace(' ', "");
    assert!(
        uncertain_form.contains("现有供应商-草稿"),
        "{uncertain_form}"
    );
    assert!(!uncertain_form.contains("不能追加"), "{uncertain_form}");

    let verify_actions = state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE));
    let verify_operation_id = match verify_actions.as_slice() {
        [UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Verify { operation_id })] => {
            operation_id.clone()
        }
        other => panic!("expected an explicit verification request, got {other:?}"),
    };
    state.handle_paste("核实请求中不能追加");
    state.handle_key(key(KeyCode::Char('y'), KeyModifiers::NONE));
    state.apply(UiEvent::ModelSettingsResult(
        ja_cli::ui::ModelSettingsResult {
            operation_id: verify_operation_id,
            outcome: ja_cli::ui::ModelSettingsOutcome::Uncertain {
                configuration: None,
                message: "verification still inconclusive".to_owned(),
            },
        },
    ));
    state.handle_key(key(KeyCode::Esc, KeyModifiers::NONE));
    let leave_warning = screen_text(&state, 80, 24).replace(' ', "");
    assert!(leave_warning.contains("写入结果未核实"), "{leave_warning}");
    assert!(
        leave_warning.contains("离开后密钥需重输"),
        "{leave_warning}"
    );
    assert!(leave_warning.contains("放弃草稿并返回"), "{leave_warning}");
    state.handle_key(key(KeyCode::Down, KeyModifiers::NONE));
    assert_eq!(
        state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE)),
        vec![UiAction::CloseModelSettings {
            start_conversation: false
        }]
    );
}

/// IO_ERROR 无法提供安全原文备份；只允许 F5 只读重试，不触发修复或普通保存。
#[test]
fn model_settings_io_error_only_offers_read_retry() {
    let mut configuration = model_settings_configuration(None, "user-v1");
    configuration["user"]["status"] = serde_json::json!("io_error");
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration);

    let short_screen = screen_text(&state, 40, 10).replace(' ', "");
    assert!(short_screen.contains("存储不可用"), "{short_screen}");
    assert!(short_screen.contains("F5重读"), "{short_screen}");
    assert!(
        state
            .handle_key(key(KeyCode::Char('r'), KeyModifiers::NONE))
            .is_empty()
    );
    assert!(
        state
            .handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL))
            .is_empty()
    );
    let retry = state.handle_key(key(KeyCode::F(5), KeyModifiers::NONE));
    assert!(matches!(
        retry.as_slice(),
        [UiAction::ModelSettings(
            ja_cli::ui::ModelSettingsRequest::Read { .. }
        )]
    ));
}

/// 修复确认在窄屏完整展示备份风险、写入目标、取消和退出出口。
#[test]
fn model_settings_short_repair_confirmation_keeps_loss_and_cancel_visible() {
    let mut configuration = model_settings_configuration(None, "damaged-v1");
    configuration["user"]["status"] = serde_json::json!("partial");
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration);
    state.handle_key(key(KeyCode::Char('r'), KeyModifiers::NONE));

    let screen = screen_text(&state, 40, 10);
    let visible = screen.replace(' ', "");
    assert!(
        visible.contains("未显示的坏条目与未知字段可能舍弃"),
        "{screen}"
    );
    assert!(visible.contains("取消"), "{screen}");
    assert!(visible.contains("不写入配置"), "{screen}");
    assert!(visible.contains("Enter确认"), "{screen}");
    assert!(visible.contains("Esc取消"), "{screen}");
    assert!(visible.contains("Ctrl+D退出"), "{screen}");
}

/// 语法损坏的可读前缀不能经普通保存覆盖；修复须确认丢弃风险并绑定原文 CAS。
#[test]
fn model_settings_partial_user_requires_explicit_backup_repair() {
    let mut configuration = model_settings_configuration(None, "damaged-original-version");
    configuration["user"]["status"] = serde_json::json!("partial");
    configuration["issues"] = serde_json::json!([{
        "scope": "user", "reason": "TOML_PARSE_FAILED", "actions": ["repair"]
    }]);
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, configuration.clone());
    let screen = screen_text(&state, 100, 24);
    assert!(screen.replace(' ', "").contains("配置需要修复"), "{screen}");
    assert!(
        state
            .handle_key(key(KeyCode::Char('s'), KeyModifiers::CONTROL))
            .is_empty()
    );

    state.handle_key(key(KeyCode::Char('r'), KeyModifiers::NONE));
    let confirmation = screen_text(&state, 100, 24);
    assert!(
        confirmation.replace(' ', "").contains("备份并修复配置"),
        "{confirmation}"
    );
    assert!(
        confirmation
            .replace(' ', "")
            .contains("未显示的坏条目与未知字段可能舍弃"),
        "{confirmation}"
    );
    assert!(
        state
            .handle_key(key(KeyCode::Esc, KeyModifiers::NONE))
            .is_empty()
    );
    state.handle_key(key(KeyCode::Char('r'), KeyModifiers::NONE));
    let actions = state.handle_key(key(KeyCode::Enter, KeyModifiers::NONE));
    match actions.as_slice() {
        [
            UiAction::ModelSettings(ja_cli::ui::ModelSettingsRequest::Repair {
                expected_version,
                document,
                ..
            }),
        ] => {
            assert_eq!(expected_version, "damaged-original-version");
            assert_eq!(document, &configuration["user"]["document"]);
        }
        other => panic!("expected explicit backup repair, got {other:?}"),
    }
}

/// 项目语法或凭据故障不能把有效用户配置引向整层替换，保留独立配置所有权边界。
#[test]
fn model_settings_other_layer_issues_do_not_offer_user_repair() {
    for (scope, reason) in [
        ("project", "TOML_PARSE_FAILED"),
        ("credential", "CREDENTIALS_UNAVAILABLE"),
    ] {
        let mut configuration = model_settings_configuration(None, "valid-user-version");
        configuration["user"]["status"] = serde_json::json!("valid");
        configuration["project"] = serde_json::json!({"status": "partial"});
        configuration["issues"] = serde_json::json!([{"scope": scope, "reason": reason}]);
        let mut state = UiState::new(UiSnapshot::default());
        open_model_settings_for_test(&mut state, configuration);
        state.handle_key(key(KeyCode::Char('r'), KeyModifiers::NONE));
        let screen = screen_text(&state, 100, 24);
        assert!(
            screen.replace(' ', "").contains("无需修复"),
            "{scope}: {screen}"
        );
        assert!(
            !screen.replace(' ', "").contains("备份并修复配置"),
            "{scope}: {screen}"
        );
    }
}

/// 设置覆盖层的 Ctrl+D 显式退出并清密钥，不把 Ctrl+C 转成正在运行的 Turn 取消。
#[test]
fn model_settings_ctrl_d_exits_without_cancelling_turn() {
    let mut state = UiState::new(UiSnapshot::default());
    open_model_settings_for_test(&mut state, model_settings_configuration(None, "user-v1"));
    state.handle_key(key(KeyCode::Char('a'), KeyModifiers::NONE));
    for _ in 0..3 {
        state.handle_key(key(KeyCode::Tab, KeyModifiers::NONE));
    }
    state.handle_paste("secret-to-clear-on-exit");
    state.apply(UiEvent::SetTurnState(TurnState::Working));

    assert!(
        state
            .handle_key(key(KeyCode::Char('c'), KeyModifiers::CONTROL))
            .is_empty()
    );
    assert_eq!(state.snapshot().turn_state, TurnState::Working);
    assert!(!state.is_closed());
    assert_eq!(
        state.handle_key(key(KeyCode::Char('d'), KeyModifiers::CONTROL)),
        vec![UiAction::Quit]
    );
    assert!(state.is_closed());
    assert_eq!(state.snapshot().turn_state, TurnState::Working);
}
