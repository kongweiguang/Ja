// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::io::{self, Write};

use crossterm::cursor::Show;
use crossterm::event::{
    DisableBracketedPaste, DisableMouseCapture, EnableBracketedPaste, EnableMouseCapture,
};
use crossterm::execute;
use crossterm::terminal::{self, EnterAlternateScreen, LeaveAlternateScreen};

/// 一项模式操作对应一次可失败的终端变更，测试可精确注入中途 I/O 错误。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum TerminalOperation {
    AlternateScreen(bool),
    BracketedPaste(bool),
    MouseCapture(bool),
    RawMode(bool),
    ShowCursor,
}

/// 将终端副作用收敛到单一边界，避免恢复测试改写全局进程终端状态。
pub(super) trait TerminalOperations {
    /// 执行一项模式变更；错误时调用方保留恢复意图，因为宿主可能已收到部分字节。
    fn run(&mut self, operation: TerminalOperation) -> io::Result<()>;
}

/// 生产终端适配器只持有 Crossterm 的进程级终端 API，不创建第二个终端控制器。
#[derive(Default)]
pub(super) struct CrosstermTerminalOperations;

impl TerminalOperations for CrosstermTerminalOperations {
    /// 逐项映射 Crossterm 命令，保留可注入层与生产输出之间的一一操作语义。
    fn run(&mut self, operation: TerminalOperation) -> io::Result<()> {
        match operation {
            TerminalOperation::AlternateScreen(true) => {
                execute!(io::stdout(), EnterAlternateScreen)
            }
            TerminalOperation::AlternateScreen(false) => {
                execute!(io::stdout(), LeaveAlternateScreen)
            }
            TerminalOperation::BracketedPaste(true) => {
                execute!(io::stdout(), EnableBracketedPaste)
            }
            TerminalOperation::BracketedPaste(false) => {
                execute!(io::stdout(), DisableBracketedPaste)
            }
            TerminalOperation::MouseCapture(true) => execute!(io::stdout(), EnableMouseCapture),
            TerminalOperation::MouseCapture(false) => execute!(io::stdout(), DisableMouseCapture),
            TerminalOperation::RawMode(true) => terminal::enable_raw_mode(),
            TerminalOperation::RawMode(false) => terminal::disable_raw_mode(),
            TerminalOperation::ShowCursor => execute!(io::stdout(), Show),
        }
    }
}

/// 已尝试启用的模式逐项登记，使部分写入失败时 Drop 仍能尽力发送对应关闭命令。
pub(super) struct TerminalGuard<O: TerminalOperations = CrosstermTerminalOperations> {
    operations: O,
    raw_mode: bool,
    mouse_capture: bool,
    bracketed_paste: bool,
    alternate_screen: bool,
}

impl TerminalGuard<CrosstermTerminalOperations> {
    /// 应用入口固定使用系统 Crossterm 适配器，避免把测试注入点暴露到 UI 控制面。
    pub(super) fn activate() -> io::Result<Self> {
        Self::activate_with(CrosstermTerminalOperations)
    }
}

impl<O: TerminalOperations> TerminalGuard<O> {
    /// 先获取清理责任再进入全屏；任一启用失败都由局部 guard 回滚已尝试的模式。
    pub(super) fn activate_with(operations: O) -> io::Result<Self> {
        let mut guard = Self {
            operations,
            raw_mode: false,
            mouse_capture: false,
            bracketed_paste: false,
            alternate_screen: false,
        };
        guard.activate_modes()?;
        Ok(guard)
    }

    /// 外部程序启动前完整释放 Ja 的输入与屏幕模式，清理错误仍保留待 Drop 重试的标记。
    pub(super) fn suspend_for_external_program(&mut self) -> io::Result<()> {
        self.restore_modes()
    }

    /// 子进程退出后仅补齐已确认关闭的模式，避免对仍由 Ja 持有的模式重复进入。
    pub(super) fn resume_after_external_program(&mut self) -> io::Result<()> {
        self.activate_modes()
    }

    /// 意图先于副作用登记，原子命令包写入失败时也关闭可能已经到达宿主的 VT 前缀。
    fn activate_modes(&mut self) -> io::Result<()> {
        if !self.alternate_screen {
            self.alternate_screen = true;
            self.operations
                .run(TerminalOperation::AlternateScreen(true))?;
        }
        if !self.bracketed_paste {
            self.bracketed_paste = true;
            self.operations
                .run(TerminalOperation::BracketedPaste(true))?;
        }
        if !self.mouse_capture {
            self.mouse_capture = true;
            self.operations.run(TerminalOperation::MouseCapture(true))?;
        }
        if !self.raw_mode {
            self.raw_mode = true;
            self.operations.run(TerminalOperation::RawMode(true))?;
        }
        Ok(())
    }

    /// 以“清理标记成功后才清除”的规则处理单项，失败不阻断后续模式恢复并留给 Drop 重试。
    fn restore_one(
        operations: &mut O,
        active: &mut bool,
        operation: TerminalOperation,
        first_error: &mut Option<io::Error>,
    ) {
        if !*active {
            return;
        }
        match operations.run(operation) {
            Ok(()) => *active = false,
            Err(error) => {
                if first_error.is_none() {
                    *first_error = Some(error);
                }
            }
        }
    }

    /// 按实际启用逆序逐项恢复 raw、鼠标、粘贴、全屏与光标，记录首错但继续清理。
    fn restore_modes(&mut self) -> io::Result<()> {
        let mut first_error = None;
        Self::restore_one(
            &mut self.operations,
            &mut self.raw_mode,
            TerminalOperation::RawMode(false),
            &mut first_error,
        );
        Self::restore_one(
            &mut self.operations,
            &mut self.mouse_capture,
            TerminalOperation::MouseCapture(false),
            &mut first_error,
        );
        Self::restore_one(
            &mut self.operations,
            &mut self.bracketed_paste,
            TerminalOperation::BracketedPaste(false),
            &mut first_error,
        );
        Self::restore_one(
            &mut self.operations,
            &mut self.alternate_screen,
            TerminalOperation::AlternateScreen(false),
            &mut first_error,
        );
        if let Err(error) = self.operations.run(TerminalOperation::ShowCursor)
            && first_error.is_none()
        {
            first_error = Some(error);
        }
        first_error.map_or(Ok(()), Err)
    }
}

impl<O: TerminalOperations> Drop for TerminalGuard<O> {
    /// 异常返回或 panic 也逐项恢复；显式清理失败留下的标记在此获得最后一次重试。
    fn drop(&mut self) {
        let _ = self.restore_modes();
    }
}

/// 保持完整帧原子呈现；泛型 writer 让部分写入和 flush 失败可在不碰物理终端时复现。
pub(super) struct TerminalUpdateGuard<W: Write> {
    output: W,
    active: bool,
}

impl<W: Write> TerminalUpdateGuard<W> {
    /// 开始同步输出前先建立 Drop 责任，写入或 flush 中断都会尝试关闭光标和同步模式。
    pub(super) fn begin(output: W) -> io::Result<Self> {
        let mut guard = Self {
            output,
            active: true,
        };
        guard.output.write_all(b"\x1b[?2026h\x1b[?25l")?;
        guard.output.flush()?;
        Ok(guard)
    }

    /// 结束完整帧后交由 Ratatui 决定光标位置；失败时 Drop 仍补发完整恢复序列。
    pub(super) fn finish(mut self) -> io::Result<()> {
        let result = self
            .output
            .write_all(b"\x1b[?2026l")
            .and_then(|()| self.output.flush());
        if result.is_ok() {
            self.active = false;
        }
        result
    }

    /// 分开尝试显光标、结束同步更新和 flush，单个失败不跳过余下的恢复动作。
    fn restore_terminal(output: &mut W) -> io::Result<()> {
        let mut first_error = None;
        for sequence in [b"\x1b[?25h".as_slice(), b"\x1b[?2026l".as_slice()] {
            if let Err(error) = output.write_all(sequence)
                && first_error.is_none()
            {
                first_error = Some(error);
            }
        }
        if let Err(error) = output.flush()
            && first_error.is_none()
        {
            first_error = Some(error);
        }
        first_error.map_or(Ok(()), Err)
    }
}

impl<W: Write> Drop for TerminalUpdateGuard<W> {
    /// 绘制提前返回或 panic 时也独立关闭同步输出并恢复光标，不依赖后端完成当前帧。
    fn drop(&mut self) {
        if self.active {
            let _ = Self::restore_terminal(&mut self.output);
            self.active = false;
        }
    }
}
