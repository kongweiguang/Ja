// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "support/help_evidence.rs"]
mod help_evidence;

use crossterm::event::{KeyCode, KeyEvent, KeyModifiers};
use ja_cli::ui::{
    TimelineEntry, TimelineKind, TimelineStatus, TurnState, UiEvent, UiSnapshot, UiState, render,
};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use std::path::PathBuf;

/// 显式指定证据目录时通过公开终端 API 导出布局，普通测试不产生文件副作用。
#[test]
fn exports_help_layout_when_requested() {
    let Some(directory) = std::env::var_os("JA_HELP_EVIDENCE_DIR") else {
        return;
    };
    let directory = PathBuf::from(directory);
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            model_identifier: Some("gpt-6-sol".into()),
            workspace_path: Some("C:\\dev\\rust\\ja".into()),
            thread_title: Some("快捷键布局验收".into()),
            ..UiSnapshot::default()
        },
        120,
        32,
    );
    state.handle_key(KeyEvent::new(KeyCode::Char('?'), KeyModifiers::NONE));
    let mut terminal = Terminal::new(TestBackend::new(120, 32)).unwrap();
    terminal.draw(|frame| render(frame, &state)).unwrap();
    help_evidence::write("help-120x32", terminal.backend().buffer(), &directory).unwrap();
}

/// 与帮助共用实际 Buffer 导出，保留正文、灰底 Composer 和提示的真实样式，避免用示意图评价美观。
#[test]
fn exports_fullscreen_layout_when_requested() {
    let Some(directory) = std::env::var_os("JA_HELP_EVIDENCE_DIR") else {
        return;
    };
    let directory = PathBuf::from(directory);
    let mut timeline = (0..18)
        .map(|index| TimelineEntry {
            id: format!("visual-history-{index}"),
            kind: TimelineKind::Assistant,
            text: format!(
                "历史记录 {index:02}\n\n阅读历史时，新回复应留在下方，草稿与光标保持原位。"
            ),
            detail: None,
            status: Some(TimelineStatus::Complete),
        })
        .collect::<Vec<_>>();
    timeline.push(TimelineEntry {
        id: "visual-question".into(),
        kind: TimelineKind::User,
        text: "让正文独立滚动，输入框与底栏固定在下面。".into(),
        detail: None,
        status: Some(TimelineStatus::Complete),
    });
    timeline.push(TimelineEntry {
        id: "visual-live-answer".into(),
        kind: TimelineKind::Assistant,
        text: "## 当前效果\n\n输入区保持贴底，浏览历史时不会被新回复打断。\n\n- 滚轮与 PageUp / PageDown 浏览正文。\n- Ctrl+End 或 Esc 返回最新。\n- 中文、Emoji 🙂 与组合字符 e\u{301} 按显示列排版。\n\n```rust\n// 输入区与正文共享布局计划\nrender(frame, &state);\n```\n\n正在核对窄窗与返回最新的行为。".into(),
        detail: None,
        status: Some(TimelineStatus::Running),
    });
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("visual-fullscreen".into()),
            thread_title: Some("正文独立滚动与固定输入区".into()),
            model_identifier: Some("gpt-6.1-sol".into()),
            reasoning_label: Some("高".into()),
            workspace_path: Some("C:\\kong\\personal-projects\\Ja\\代码\\ja".into()),
            turn_state: TurnState::Working,
            timeline,
            ..UiSnapshot::default()
        },
        120,
        32,
    );
    state.handle_paste("继续核对返回最新和复制体验");
    write_fullscreen_buffer(&mut state, 120, 32, "fullscreen-latest", &directory);
    state.handle_key(KeyEvent::new(KeyCode::PageUp, KeyModifiers::NONE));
    state.apply(UiEvent::AppendStreamDelta {
        entry_id: "visual-live-answer".into(),
        delta: "\n\n新回复已到达，阅读位置保持稳定。".into(),
    });
    write_fullscreen_buffer(&mut state, 120, 32, "fullscreen-browsing", &directory);
    for (width, height) in [(80, 24), (40, 10), (120, 32)] {
        write_fullscreen_buffer(
            &mut state,
            width,
            height,
            &format!("fullscreen-browsing-{width}x{height}"),
            &directory,
        );
    }
    state.handle_key(KeyEvent::new(KeyCode::End, KeyModifiers::CONTROL));
    state.apply(UiEvent::ReplaceDraft(
        "多行草稿向上扩展\n底部位置继续保持".into(),
    ));
    write_fullscreen_buffer(&mut state, 80, 24, "fullscreen-multiline", &directory);
}

/// 绘制与尺寸状态同步更新，让导出包含当前 RenderPlan 的真实几何而非旧终端高度。
fn write_fullscreen_buffer(
    state: &mut UiState,
    width: u16,
    height: u16,
    name: &str,
    directory: &std::path::Path,
) {
    state.set_terminal_size(width, height);
    let mut terminal = Terminal::new(TestBackend::new(width, height)).unwrap();
    terminal.draw(|frame| render(frame, state)).unwrap();
    help_evidence::write(name, terminal.backend().buffer(), directory).unwrap();
}
