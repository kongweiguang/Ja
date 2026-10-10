// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use ja_cli::ui::{UiState, render};
use ratatui::Terminal;
use ratatui::backend::TestBackend;
use ratatui::buffer::Buffer;
use unicode_width::UnicodeWidthStr;

/// 从生产渲染入口读取应用拥有的正文与固定底栏，避免测试自建布局掩盖坐标回归。
pub(crate) fn render_buffer(state: &UiState, width: u16, height: u16) -> Buffer {
    let mut terminal = Terminal::new(TestBackend::new(width.max(1), height.max(1)))
        .expect("TestBackend should be constructible");
    terminal
        .draw(|frame| render(frame, state))
        .expect("UI should render to TestBackend");
    terminal.backend().buffer().clone()
}

/// 按物理行回读并保留真实空格，底栏固定性依赖实际显示坐标而非拼接文本。
pub(crate) fn rows(buffer: &Buffer) -> Vec<String> {
    let width = usize::from(buffer.area.width.max(1));
    buffer
        .content()
        .chunks(width)
        .map(|row| {
            let mut text = String::new();
            let mut continuation_column = 0;
            for (column, cell) in row.iter().enumerate() {
                if column < continuation_column {
                    continue;
                }
                let symbol = cell.symbol();
                text.push_str(symbol);
                continuation_column = column + UnicodeWidthStr::width(symbol).max(1);
            }
            text
        })
        .collect()
}

/// 仅提取 Composer 上方的正文行，检查回复时不把底栏和末尾留白算入历史内容。
#[allow(dead_code)]
pub(crate) fn transcript_rows(buffer: &Buffer) -> Vec<String> {
    let mut transcript = rows(buffer)
        .into_iter()
        .take_while(|row| !row.trim_start().starts_with('›'))
        .collect::<Vec<_>>();
    while transcript.last().is_some_and(|row| row.trim().is_empty()) {
        transcript.pop();
    }
    transcript
}

/// 只拼接应用缓冲区中的正文，用于验证投影去重与可见内容。
#[allow(dead_code)]
pub(crate) fn transcript_text(state: &UiState, width: u16, height: u16) -> String {
    transcript_rows(&render_buffer(state, width, height)).join("\n")
}
