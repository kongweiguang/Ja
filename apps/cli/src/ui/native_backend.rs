// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::io::{self, Write};

use ratatui::backend::{Backend, ClearType, CrosstermBackend, WindowSize};
use ratatui::buffer::Cell;
use ratatui::layout::{Position, Size};
use unicode_width::UnicodeWidthStr;

/// 复用 Crossterm 全屏换行滚动；ConPTY 的局部滚动会丢历史，原生全量绘制须跳过宽字符占位格。
pub(super) struct NativeBackend<W: Write>(CrosstermBackend<W>);

impl<W: Write> NativeBackend<W> {
    /// 唯一差异限定在绘制 cell，终端能力与模式仍由现有 backend 和 guard 管理。
    pub(super) fn new(writer: W) -> Self {
        Self(CrosstermBackend::new(writer))
    }
}

impl<W: Write> Backend for NativeBackend<W> {
    /// insert_before 会交付整行 cell；宽字符后继格不是文本，输出它会把中文拆成带空格的历史。
    fn draw<'a, I>(&mut self, content: I) -> io::Result<()>
    where
        I: Iterator<Item = (u16, u16, &'a Cell)>,
    {
        let mut covered: Option<(u16, u16)> = None;
        self.0.draw(content.filter(|&(x, y, cell)| {
            if covered.is_some_and(|(end, row)| row == y && x < end) {
                return false;
            }
            let width = u16::try_from(UnicodeWidthStr::width(cell.symbol())).unwrap_or(u16::MAX);
            covered = Some((x.saturating_add(width.max(1)), y));
            true
        }))
    }

    /// 使用底部换行让终端自己保存历史，避免局部滚动的宿主差异。
    fn append_lines(&mut self, count: u16) -> io::Result<()> {
        self.0.append_lines(count)
    }

    /// 光标隐藏由原 backend 执行，异常恢复仍由 guard 保证。
    fn hide_cursor(&mut self) -> io::Result<()> {
        self.0.hide_cursor()
    }

    /// 保持原 backend 的光标显示语义。
    fn show_cursor(&mut self) -> io::Result<()> {
        self.0.show_cursor()
    }

    /// 从真实终端查询坐标，不建立第二份光标状态。
    fn get_cursor_position(&mut self) -> io::Result<Position> {
        self.0.get_cursor_position()
    }

    /// 坐标沿用 Ratatui 的显示列语义。
    fn set_cursor_position<P: Into<Position>>(&mut self, position: P) -> io::Result<()> {
        self.0.set_cursor_position(position)
    }

    /// 清理范围由上层 viewport 决定，不顺带清空历史。
    fn clear(&mut self) -> io::Result<()> {
        self.0.clear()
    }

    /// 将局部清理交给 Crossterm，避免默认实现退化为整屏清理。
    fn clear_region(&mut self, clear_type: ClearType) -> io::Result<()> {
        self.0.clear_region(clear_type)
    }

    /// 缩放时直接读取宿主尺寸，保持测量和绘制一致。
    fn size(&self) -> io::Result<Size> {
        self.0.size()
    }

    /// 像素信息仍由宿主提供，不估算字体尺寸。
    fn window_size(&mut self) -> io::Result<WindowSize> {
        self.0.window_size()
    }

    /// 每批稳定内容及时刷新，错误继续返回给现有终端恢复路径。
    fn flush(&mut self) -> io::Result<()> {
        Backend::flush(&mut self.0)
    }
}

impl<W: Write> Write for NativeBackend<W> {
    /// 原始控制序列继续走同一 writer，避免 stdout 与 backend 两套输出互相覆盖。
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.0.write(bytes)
    }

    /// 控制序列与 cell 共用刷新边界。
    fn flush(&mut self) -> io::Result<()> {
        Write::flush(&mut self.0)
    }
}
