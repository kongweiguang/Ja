// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[allow(dead_code)]
#[path = "../../src/ui/theme.rs"]
mod theme;

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ja_cli::ui::{
    TimelineEntry, TimelineKind, TimelineStatus, TurnState, UiAction, UiEvent, UiSnapshot, UiState,
    render,
};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::buffer::Buffer;
use ratatui::style::Modifier;
use unicode_width::UnicodeWidthStr;

/// 用真实渲染器核对宽窗列布局、说明列对齐和语义颜色，避免只测试排版辅助函数。
#[test]
fn wide_help_aligns_two_columns_and_keeps_identity() {
    let state = help_state(120, 32);
    let screen = draw(&state, 120, 32);
    let input = locate(&screen, "输入").unwrap();
    let session = locate(&screen, "会话").unwrap();
    let browse = locate(&screen, "浏览").unwrap();
    assert_eq!(input.1, session.1, "{:?}", rows(&screen));
    assert!(browse.1 > input.1, "{:?}", rows(&screen));
    assert!(input.0 < session.0);
    assert_eq!(
        locate(&screen, "打开命令").unwrap().0,
        locate(&screen, "引用文件").unwrap().0
    );
    assert_eq!(
        locate(&screen, "引用文件").unwrap().0,
        locate(&screen, "搜索输入历史").unwrap().0
    );
    assert_eq!(
        locate(&screen, "发送；工作中调整任务").unwrap().0,
        locate(&screen, "恢复会话").unwrap().0
    );
    let file = locate(&screen, "@").unwrap();
    assert_eq!(screen[file].fg, theme::BLUE);
    assert!(screen[input].modifier.contains(Modifier::BOLD));
    let title = locate(&screen, "Ja 快捷键").unwrap();
    assert_eq!(input.1, title.1 + 2);
    let composer = locate(&screen, "›").unwrap();
    let identity = locate(&screen, "gpt-6-sol").unwrap();
    let actions = locate(&screen, "Esc/?").unwrap();
    assert!(composer.1 < identity.1 && identity.1 < actions.1);
}

/// 列数随实际内容收敛，窄窗纵排后仍保留输入和返回动作，不靠固定截图宽度分支。
#[test]
fn help_reflows_to_two_then_one_column() {
    for (width, height) in [(80, 32), (60, 32), (40, 32)] {
        let screen = draw(&help_state(width, height), width, height);
        let input = locate(&screen, "输入").unwrap();
        let session = locate(&screen, "会话").unwrap();
        let browse = locate(&screen, "浏览").unwrap();
        if width == 80 {
            assert_eq!(input.1, session.1);
            assert!(browse.1 > input.1);
        } else {
            assert_eq!(input.0, session.0);
            assert_eq!(session.0, browse.0);
            assert!(input.1 < session.1 && session.1 < browse.1);
        }
        assert!(locate(&screen, "引用 Skill").is_some());
        let composer_arrow = locate(&screen, "›");
        let browsing = locate(&screen, "浏览");
        let identity = locate(&screen, "gpt-6-sol");
        let footer = locate(&screen, "Esc/?");
        let help_precedes_composer = matches!(
            (browsing, composer_arrow),
            (Some((_, browsing_y)), Some((_, composer_y))) if browsing_y < composer_y
        );
        assert!(
            help_precedes_composer,
            "{width}x{height}: visible help body must end before the composer ({browsing:?}, {composer_arrow:?}); {:?}",
            rows(&screen)
        );
        let chrome_is_ordered = matches!(
            (composer_arrow, identity, footer),
            (Some((_, composer_y)), Some((_, identity_y)), Some((_, footer_y)))
                if composer_y < identity_y && identity_y < footer_y
        );
        assert!(
            chrome_is_ordered,
            "{width}x{height}: composer, identity, and footer must remain ordered ({composer_arrow:?}, {identity:?}, {footer:?})"
        );
    }
}

/// 内容超出高度时仍可到达末尾并返回顶部；缩窄到按键也需折行时不能丢失返回路径。
#[test]
fn short_help_scrolls_all_content_and_survives_resize() {
    let mut state = help_state(40, 12);
    let top = draw(&state, 40, 12);
    press(&mut state, KeyCode::End);
    let bottom = draw(&state, 40, 12);
    assert!(locate(&bottom, "Ctrl+D").is_some());
    assert_ne!(top, bottom);
    press(&mut state, KeyCode::Home);
    assert_eq!(top, draw(&state, 40, 12));
    press(&mut state, KeyCode::PageDown);
    assert_ne!(top, draw(&state, 40, 12));
    press(&mut state, KeyCode::PageUp);
    assert_eq!(top, draw(&state, 40, 12));
    for (width, height) in [(20, 8), (8, 5), (1, 3), (120, 32)] {
        draw(&state, width, height);
    }
    let mut narrow = help_state(20, 8);
    let mut observed = String::new();
    for _ in 0..150 {
        observed.push_str(&rows(&draw(&narrow, 20, 8)).join("\n"));
        press(&mut narrow, KeyCode::Down);
    }
    for key in [
        "Ctrl+J",
        "Ctrl+R",
        "/resume",
        "Ctrl+O",
        "PgUp / PgDn",
        "Home / End",
        "Ctrl+D",
    ] {
        assert!(observed.contains(key), "未能滚动到 {key}");
    }
    press(&mut narrow, KeyCode::Esc);
    assert!(narrow.composer().is_empty());
    assert!(locate(&draw(&narrow, 20, 8), "Ja 快捷键").is_none());
}

/// 两个入口共享帮助，退出不提交任务，普通草稿中的问号保持正文语义。
#[test]
fn help_entries_close_without_changing_draft_or_cursor() {
    let mut state = UiState::new_for_terminal(snapshot(), 80, 24);
    let idle = draw(&state, 80, 24);
    press(&mut state, KeyCode::Char('?'));
    press(&mut state, KeyCode::Char('?'));
    assert_eq!(idle, draw(&state, 80, 24));
    state.handle_paste("/help");
    assert_eq!(
        press(&mut state, KeyCode::Enter),
        vec![ja_cli::ui::UiAction::Command(ja_cli::ui::UiCommand::Help)]
    );
    assert!(state.composer().is_empty());
    assert!(locate(&draw(&state, 80, 24), "Ja 快捷键").is_some());
    press(&mut state, KeyCode::Esc);
    assert_eq!(idle, draw(&state, 80, 24));
    state.handle_paste("中文 👨‍👩‍👧‍👦");
    let before = state.composer().cursor();
    press(&mut state, KeyCode::Char('?'));
    assert_eq!(state.composer().text(), "中文 👨‍👩‍👧‍👦?");
    assert_eq!(state.composer().cursor(), before + 1);
    assert!(locate(&draw(&state, 80, 24), "Ja 快捷键").is_none());
}

/// 运行中帮助必须留下工作提示；窄窗滚动帮助不能把后台仍在回复的事实挤掉。
#[test]
fn working_help_keeps_reply_progress_visible_in_short_windows() {
    for (width, height) in [(120, 32), (80, 24), (60, 18), (40, 10), (40, 8)] {
        let mut state = UiState::new_for_terminal(working_snapshot(), width, height);
        assert!(press(&mut state, KeyCode::Char('?')).is_empty());
        let screen = draw(&state, width, height);
        assert!(
            locate(&screen, "正在工作").is_some(),
            "{width}x{height}: {:?}",
            rows(&screen)
        );
        assert!(locate(&screen, "Ja 快捷键").is_some());
        assert!(locate(&screen, "回复继续").is_some());
        assert!(locate(&screen, "Esc 中断").is_none());
        if height >= 10 {
            assert!(
                locate(&screen, "首段回复").is_some(),
                "{width}x{height}: {:?}",
                rows(&screen)
            );
        }
    }
}

/// 两个帮助入口和关闭键只操作面板；回到普通输入后再按 Esc 才发出取消动作。
#[test]
fn help_keys_during_work_never_cancel_the_turn() {
    for slash in [false, true] {
        for close in [KeyCode::Esc, KeyCode::Char('?')] {
            let mut state = UiState::new_for_terminal(working_snapshot(), 80, 24);
            if slash {
                state.handle_paste("/help");
                assert_eq!(
                    press(&mut state, KeyCode::Enter),
                    vec![UiAction::Command(ja_cli::ui::UiCommand::Help)]
                );
            } else {
                assert!(press(&mut state, KeyCode::Char('?')).is_empty());
            }
            for code in [KeyCode::Down, KeyCode::End, KeyCode::Home, KeyCode::Enter] {
                assert!(press(&mut state, code).is_empty());
                assert_eq!(state.snapshot().turn_state, TurnState::Working);
            }
            assert!(press(&mut state, close).is_empty());
            assert_eq!(state.snapshot().turn_state, TurnState::Working);
            assert!(!state.is_closed());
            assert!(state.composer().is_empty());
            assert!(locate(&draw(&state, 80, 24), "Ja 快捷键").is_none());
            assert_eq!(press(&mut state, KeyCode::Esc), vec![UiAction::CancelTurn]);
        }
    }
}

/// Help overlays may consume stream completion without removing the answer from the owned transcript.
#[test]
fn help_keeps_streaming_and_observes_completion() {
    let mut state = UiState::new_for_terminal(working_snapshot(), 80, 24);
    press(&mut state, KeyCode::Char('?'));
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "live:turn_help:1".into(),
        delta: "，继续回复".into(),
    });
    let working = draw(&state, 80, 24);
    assert!(
        locate(&working, "首段回复，继续回复").is_some(),
        "{:?}",
        rows(&working)
    );
    assert!(locate(&working, "Ja 快捷键").is_some());
    let mut completed = state.snapshot().clone();
    completed.turn_state = TurnState::Idle;
    completed.timeline[0].status = Some(TimelineStatus::Complete);
    state.apply(UiEvent::ReplaceSnapshot(Box::new(completed)));
    assert_eq!(state.snapshot().turn_state, TurnState::Idle);
    assert!(locate(&draw(&state, 80, 24), "Ja 快捷键").is_some());
    assert!(press(&mut state, KeyCode::Esc).is_empty());
    let text = rows(&draw(&state, 80, 24)).join("\n");
    assert!(text.contains("首段回复，继续回复"), "{text}");
    assert_eq!(text.matches("首段回复，继续回复").count(), 1, "{text}");
    assert!(locate(&draw(&state, 80, 24), "正在工作").is_none());
}

/// 流条目保持未闭合文本，帮助展开后能直接验证活动正文和工作提示是否仍可见。
fn working_snapshot() -> UiSnapshot {
    UiSnapshot {
        thread_id: Some("thr_help".into()),
        turn_state: TurnState::Working,
        timeline: vec![TimelineEntry {
            id: "live:turn_help:1".into(),
            kind: TimelineKind::Assistant,
            text: "**首段回复".into(),
            detail: None,
            status: Some(TimelineStatus::Running),
        }],
        ..snapshot()
    }
}

/// 固定身份事实方便判断帮助覆盖问题，不连接 App Server 或创建实际会话。
fn snapshot() -> UiSnapshot {
    UiSnapshot {
        model_identifier: Some("gpt-6-sol".into()),
        workspace_path: Some("C:\\dev\\rust\\ja".into()),
        thread_title: Some("快捷键布局验收".into()),
        ..UiSnapshot::default()
    }
}

/// 使用正常帮助入口，测试不绕过状态机直接构造面板。
fn help_state(width: u16, height: u16) -> UiState {
    let mut state = UiState::new_for_terminal(snapshot(), width, height);
    press(&mut state, KeyCode::Char('?'));
    state
}

/// 返回真实动作；布局用例模拟分开的打字，给 ASCII 暂存到期后一次无动作 tick 边界。
/// 快速粘贴与其 Enter 归属由独立状态回归验证，不把同步测试调用误作同一粘贴批次。
fn press(state: &mut UiState, code: KeyCode) -> Vec<ja_cli::ui::UiAction> {
    let mut actions = state.handle_key(KeyEvent::new(code, KeyModifiers::NONE));
    if matches!(code, KeyCode::Char(ch) if ch.is_ascii()) {
        std::thread::sleep(std::time::Duration::from_millis(10));
        actions.extend(state.handle_key(KeyEvent::new(KeyCode::Null, KeyModifiers::NONE)));
    }
    actions
}

/// 每次从当前渲染器取完整 buffer，同时核对缩放后没有越界或旧行残留。
fn draw(state: &UiState, width: u16, height: u16) -> Buffer {
    let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
    terminal.draw(|frame| render(frame, state)).unwrap();
    terminal.backend().buffer().clone()
}

/// 跳过宽字符后继占位格，保留真实空格，以显示列而非 UTF-8 字节查找坐标。
fn rows(buffer: &Buffer) -> Vec<String> {
    (0..buffer.area.height)
        .map(|y| {
            let mut row = String::new();
            let mut x = 0;
            while x < buffer.area.width {
                let symbol = buffer[(x, y)].symbol();
                row.push_str(symbol);
                x += symbol.width().max(1) as u16;
            }
            row
        })
        .collect()
}

/// 文案坐标来自实际 cell，可直接核对不同分组和说明的对齐关系。
fn locate(buffer: &Buffer, text: &str) -> Option<(u16, u16)> {
    rows(buffer).iter().enumerate().find_map(|(y, row)| {
        row.find(text)
            .map(|start| (row[..start].width() as u16, y as u16))
    })
}
