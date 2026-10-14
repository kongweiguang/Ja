// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 将 Windows Terminal 的 CSI-u Shift+Enter 输入归并为单个 Crossterm 按键。
//!
//! 只识别完整的七键按下序列；未完成或不匹配的前缀按原样回放，Paste 正文不解析。
//! 固定截止时间限制 Escape 前缀的延迟，终端交接时由调用方丢弃旧输入源的部分状态。
//!
//! Adapted from Codex `codex-rs/tui/src/tui/windows_key_sequence.rs` at
//! `822e58cc3d666166c7446c5b1ea2e52f5d09594c` (Copyright 2025 OpenAI, Apache-2.0).
//! Ja replaces the async stream with this bounded synchronous reducer; license and notices are
//! retained in `apps/cli/third_party/` and the modification scope is recorded in `apps/cli/NOTICE.md`.

use std::time::{Duration, Instant};

use crossterm::event::{Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};

const SEQUENCE: [KeyCode; 7] = [
    KeyCode::Esc,
    KeyCode::Char('['),
    KeyCode::Char('1'),
    KeyCode::Char('3'),
    KeyCode::Char(';'),
    KeyCode::Char('2'),
    KeyCode::Char('u'),
];
const SEQUENCE_TIMEOUT: Duration = Duration::from_millis(50);

/// 有界同步解码器不拥有终端流；bridge 以同一个事件循环时钟推进 deadline。
/// 每个序列键至多暂存一次 Press 与一次 Release，整体容量不超过14个事件。
#[derive(Default)]
pub(super) struct WindowsKeySequence {
    pending: Vec<KeyEvent>,
    next: usize,
    deadline: Option<Instant>,
    final_release: Option<KeyEvent>,
}

impl WindowsKeySequence {
    /// 将候选序列保持在最多七个按键内，避免拆出第二套异步事件流。
    pub(super) fn new() -> Self {
        Self::default()
    }

    /// 按输入时间强制截止后再处理新事件，迟到的后缀不能误组为 Shift+Enter。
    pub(super) fn push(&mut self, event: Event, now: Instant) -> Vec<Event> {
        let mut output = self.flush_if_due(now);
        self.accept(event, now, &mut output);
        output
    }

    /// 由 bridge 的周期 tick 释放超时前缀，保证终端空闲时 Escape 也不会被吞住。
    pub(super) fn flush_if_due(&mut self, now: Instant) -> Vec<Event> {
        if self.deadline.is_some_and(|deadline| now >= deadline) {
            self.flush_pending()
        } else {
            Vec::new()
        }
    }

    /// 给终端 poll 提供候选序列剩余时限，避免固定轮询间隔越过50毫秒回放边界。
    pub(super) fn remaining_timeout(&self, now: Instant) -> Option<Duration> {
        self.deadline
            .map(|deadline| deadline.saturating_duration_since(now))
    }

    /// 输入源交接时只丢弃旧控制台的半截按键，防止跨越编辑器边界拼出快捷键。
    pub(super) fn reset(&mut self) {
        self.pending.clear();
        self.next = 0;
        self.deadline = None;
        self.final_release = None;
    }

    /// 识别完整前缀并保留所有失败事件；Escape 不匹配时可作为下一候选的起点。
    fn accept(&mut self, event: Event, now: Instant, output: &mut Vec<Event>) {
        if let Some(release) = self.final_release.take()
            && event == Event::Key(release)
        {
            return;
        }
        if let Event::Key(key) = event {
            if let Some(previous) = self.pending.last()
                && previous.kind == KeyEventKind::Press
                && key
                    == (KeyEvent {
                        kind: KeyEventKind::Release,
                        ..*previous
                    })
            {
                self.pending.push(key);
                return;
            }
            if key.code == SEQUENCE[self.next]
                && key.modifiers.is_empty()
                && key.kind == KeyEventKind::Press
            {
                if self.pending.is_empty() {
                    self.deadline = Some(now + SEQUENCE_TIMEOUT);
                }
                self.pending.push(key);
                self.next += 1;
                if self.next == SEQUENCE.len() {
                    self.pending.clear();
                    self.next = 0;
                    self.deadline = None;
                    self.final_release = Some(KeyEvent {
                        kind: KeyEventKind::Release,
                        ..key
                    });
                    output.push(Event::Key(KeyEvent::new(
                        KeyCode::Enter,
                        KeyModifiers::SHIFT,
                    )));
                }
                return;
            }
        }

        output.extend(self.flush_pending());
        if matches!(event, Event::Key(key) if key == KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE))
        {
            self.accept(event, now, output);
        } else {
            output.push(event);
        }
    }

    /// 失败或超时时完整回放 Press/Release 对，维持用户显式输入的原始事件语义。
    fn flush_pending(&mut self) -> Vec<Event> {
        self.next = 0;
        self.deadline = None;
        self.pending.drain(..).map(Event::Key).collect()
    }
}
