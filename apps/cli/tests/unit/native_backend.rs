// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[path = "../../src/ui/native_backend.rs"]
mod native_backend;

#[path = "../support/transcript_view.rs"]
mod transcript_view;

use ja_cli::ui::{
    TimelineEntry, TimelineKind, TimelineStatus, TurnState, UiEvent, UiSnapshot, UiState,
};
use native_backend::NativeBackend;
use ratatui::backend::Backend;
use ratatui::buffer::Buffer;
use ratatui::layout::Rect;
use ratatui::widgets::{Paragraph, Widget};
use unicode_segmentation::UnicodeSegmentation;

/// NativeBackend 输出宽字符时跳过占位 cell，但保留真实空格与跨行更新。
#[test]
fn native_output_skips_wide_placeholders_and_keeps_real_spaces() {
    let mut buffer = Buffer::empty(Rect::new(0, 0, 13, 2));
    Paragraph::new("中文 🙂 x\n下一行").render(buffer.area, &mut buffer);
    let mut output = Vec::new();
    let mut backend = NativeBackend::new(&mut output);
    backend
        .draw(
            buffer
                .content
                .iter()
                .enumerate()
                .map(|(index, cell)| ((index % 13) as u16, (index / 13) as u16, cell)),
        )
        .unwrap();
    let output = String::from_utf8(output).unwrap();
    assert!(output.contains("中\u{1b}[1;3H文"), "{output:?}");
    assert!(output.contains("🙂\u{1b}[1;8H x"), "{output:?}");
    assert!(
        output.contains("下\u{1b}[2;3H一\u{1b}[2;5H行"),
        "{output:?}"
    );
    assert!(!output.contains("中 "), "{output:?}");
}

/// 稀疏绘制的宽字跳过范围只覆盖同一行，不能吞掉后续列或独立行更新。
#[test]
fn sparse_updates_preserve_independent_cells() {
    let wide = ratatui::buffer::Cell::new("中");
    let ordinary = ratatui::buffer::Cell::new("x");
    let mut output = Vec::new();
    NativeBackend::new(&mut output)
        .draw([(2, 1, &wide), (4, 1, &ordinary), (0, 2, &ordinary)].into_iter())
        .unwrap();
    let output = String::from_utf8(output).unwrap();
    assert!(output.contains("中\u{1b}[2;5Hx\u{1b}[3;1Hx"), "{output:?}");
}

/// 全屏对话增长与缩放共用固定 dock；每个物理尺寸下流式增量不能移动 Composer/footer。
#[test]
fn fullscreen_dock_stays_fixed_while_stream_grows_across_window_sizes() {
    let mut state = UiState::new_for_terminal(
        UiSnapshot {
            thread_id: Some("thread-fixed-dock".into()),
            model_identifier: Some("gpt-6-sol".into()),
            turn_state: TurnState::Working,
            timeline: vec![TimelineEntry {
                id: "dock-answer".into(),
                kind: TimelineKind::Assistant,
                text: "首段回复".into(),
                detail: None,
                status: Some(TimelineStatus::Running),
            }],
            ..UiSnapshot::default()
        },
        120,
        32,
    );
    state.handle_paste("保留草稿🙂\n第二行\n第三行");

    for (index, (width, height)) in [(120, 32), (80, 24), (40, 10), (120, 32)]
        .into_iter()
        .enumerate()
    {
        let (before, composer_before, footer_before) = dock_rows(&mut state, width, height);
        let marker = format!("STREAM_UPDATE_{index}");
        state.apply(UiEvent::AppendStreamDelta {
            entry_id: "dock-answer".into(),
            delta: format!("{marker} 中文🙂\n"),
        });
        let (after, composer_after, footer_after) = dock_rows(&mut state, width, height);
        assert_eq!(
            composer_before, composer_after,
            "{width}x{height}: {after:?}"
        );
        assert_eq!(footer_before, footer_after, "{width}x{height}: {after:?}");
        assert!(composer_after < footer_after, "{width}x{height}: {after:?}");
        assert!(
            footer_after < usize::from(height),
            "{width}x{height}: {after:?}"
        );
        assert_eq!(state.composer().text(), "保留草稿🙂\n第二行\n第三行");
        assert_eq!(
            state.composer().cursor(),
            "保留草稿🙂\n第二行\n第三行".graphemes(true).count()
        );
        assert_ne!(
            before, after,
            "new reply content should update the transcript"
        );
        assert!(
            after.join("\n").contains(&marker),
            "{width}x{height}: {after:?}"
        );
    }
}

/// 从应用 buffer 按显示行查找 dock 标记，按同一全屏布局验证输入区和底栏位置。
fn dock_rows(state: &mut UiState, width: u16, height: u16) -> (Vec<String>, usize, usize) {
    state.set_terminal_size(width, height);
    let buffer = transcript_view::render_buffer(state, width, height);
    let rows = transcript_view::rows(&buffer);
    let composer = rows
        .iter()
        .rposition(|row| row.trim_start().starts_with('›'))
        .expect("fixed composer should render");
    let footer = rows
        .iter()
        .rposition(|row| row.contains("gpt-6-sol"))
        .expect("thread footer should render");
    (rows, composer, footer)
}
