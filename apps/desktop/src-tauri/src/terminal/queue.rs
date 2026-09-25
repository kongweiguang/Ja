// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 终端输入与事件的有界队列。
//
// `sync_channel` 只限制消息数，不能限制大块 bytes；这里同时记录数量和字节，
// 让高频输出无法把 host 内存推到不可预测状态，并保持单 writer 的顺序。

use super::error::{TerminalError, TerminalErrorCode};
use super::model::{TerminalEvent, TerminalEventKind, TerminalId};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::Instant;

const OUTPUT_DROPPED_EVENT_BYTES: usize = 64;
pub(crate) const MAX_OUTPUT_DROPPED_BYTES: usize = if usize::BITS > 53 {
    9_007_199_254_740_991_u64 as usize
} else {
    usize::MAX
};

/// 终端输入队列；caller 只负责追加，writer worker 独占消费。
pub(crate) struct InputQueue {
    pub(crate) state: Mutex<InputState>,
    wake: Condvar,
    max_bytes: usize,
    failed: AtomicBool,
}

pub(crate) struct InputState {
    pub(crate) queue: VecDeque<Vec<u8>>,
    pub(crate) bytes: usize,
    pub(crate) closed: bool,
}

impl InputQueue {
    /// 用 byte budget 初始化队列，确保单次 input 不会无界复制。
    pub(crate) fn new(max_bytes: usize) -> Self {
        Self {
            state: Mutex::new(InputState {
                queue: VecDeque::new(),
                bytes: 0,
                closed: false,
            }),
            wake: Condvar::new(),
            max_bytes,
            failed: AtomicBool::new(false),
        }
    }

    /// 非阻塞追加 input；UI 卡顿时立即返回 queue full，不能把 PTY writer 反压到 IPC 线程。
    pub(crate) fn push(&self, data: Vec<u8>) -> Result<(), TerminalError> {
        if self.failed.load(Ordering::Acquire) {
            return Err(TerminalError::new(TerminalErrorCode::QueueClosed));
        }
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(error) => {
                drop(error);
                self.fail_closed();
                return Err(TerminalError::new(TerminalErrorCode::QueueClosed));
            }
        };
        if state.closed {
            return Err(TerminalError::new(TerminalErrorCode::QueueClosed));
        }
        if data.len() > self.max_bytes.saturating_sub(state.bytes) {
            return Err(TerminalError::new(TerminalErrorCode::QueueFull));
        }
        state.bytes = state.bytes.saturating_add(data.len());
        state.queue.push_back(data);
        self.wake.notify_one();
        Ok(())
    }

    /// writer worker 阻塞取下一块，close 后保证最终返回 None。
    pub(crate) fn pop(&self) -> Option<Vec<u8>> {
        if self.failed.load(Ordering::Acquire) {
            return None;
        }
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(error) => {
                drop(error);
                self.fail_closed();
                return None;
            }
        };
        loop {
            if let Some(data) = state.queue.pop_front() {
                state.bytes = state.bytes.saturating_sub(data.len());
                return Some(data);
            }
            if state.closed {
                return None;
            }
            state = match self.wake.wait(state) {
                Ok(state) => state,
                Err(error) => {
                    drop(error);
                    self.fail_closed();
                    return None;
                }
            };
        }
    }

    /// 关闭 input 队列并唤醒 writer，使 close 不依赖额外 sentinel bytes。
    pub(crate) fn close(&self) {
        self.failed.store(true, Ordering::Release);
        self.rebuild_closed_state();
    }

    /// poison 表示 byte accounting 可能已不完整；此时不继续消费旧数据，
    /// 而是永久拒绝新输入并重建为空关闭态，使 writer 可确定退出。
    fn fail_closed(&self) {
        self.failed.store(true, Ordering::Release);
        self.rebuild_closed_state();
    }

    /// 只在原子失败栅栏生效后清空队列；重建不保留任何可疑 byte 计数。
    fn rebuild_closed_state(&self) {
        self.state.clear_poison();
        if let Ok(mut state) = self.state.lock() {
            state.closed = true;
            state.queue.clear();
            state.bytes = 0;
        }
        self.wake.notify_all();
    }
}

/// 有界事件队列；终态事件只淘汰必要的最旧输出以保证 close/错误始终可达。
pub(crate) struct EventQueue {
    pub(crate) state: Mutex<EventState>,
    wake: Condvar,
    session_id: TerminalId,
    generation: u64,
    max_bytes: usize,
    max_count: usize,
    terminal: AtomicBool,
    failed: AtomicBool,
}

pub(crate) struct EventState {
    pub(crate) queue: VecDeque<TerminalEvent>,
    pub(crate) bytes: usize,
    pub(crate) next_sequence: u64,
    pub(crate) closed: bool,
}

impl EventQueue {
    /// 创建与 session generation 绑定的事件通道，防止跨 session 复用事件身份。
    pub(crate) fn new(
        session_id: TerminalId,
        generation: u64,
        max_bytes: usize,
        max_count: usize,
    ) -> Self {
        Self {
            state: Mutex::new(EventState {
                queue: VecDeque::new(),
                bytes: 0,
                next_sequence: 1,
                closed: false,
            }),
            wake: Condvar::new(),
            session_id,
            generation,
            max_bytes,
            max_count,
            terminal: AtomicBool::new(false),
            failed: AtomicBool::new(false),
        }
    }

    /// 普通 output 入队不能阻塞 PTY reader；压力下用一个合并 delta marker 表示丢弃 bytes，
    /// 而不是终止 session。
    pub(crate) fn push_output(&self, data: Vec<u8>) -> bool {
        if self.failed.load(Ordering::Acquire) {
            return false;
        }
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(error) => {
                drop(error);
                self.fail_closed();
                return false;
            }
        };
        if state.closed || self.terminal.load(Ordering::Acquire) {
            return false;
        }
        if Self::merge_pending_output_drop(&mut state, data.len()) {
            self.wake.notify_one();
            return true;
        }
        let output_size = data.len().saturating_add(64);
        if self.has_capacity(&state, output_size, 1) {
            let event = self.make_event(&mut state, TerminalEventKind::Output { data });
            state.bytes = state.bytes.saturating_add(output_size);
            state.queue.push_back(event);
            self.wake.notify_one();
            return true;
        }
        self.record_output_drop(&mut state, data.len());
        self.wake.notify_one();
        true
    }

    /// 添加 resize 或其它非终态控制事件；控制事件很小，满时仍拒绝而不覆盖输出。
    pub(crate) fn push_control(&self, kind: TerminalEventKind) -> bool {
        self.push_kind(kind)
    }

    /// 只回收最新 queued event 后写入 terminal fact；由预留空间挤出的 output bytes 在 budget
    /// 允许时合并为一个 delta，并紧邻 terminal event 之前发布。
    pub(crate) fn push_terminal(&self, kind: TerminalEventKind) -> bool {
        if self.terminal.swap(true, Ordering::AcqRel) {
            return false;
        }
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(error) => {
                drop(error);
                self.fail_closed();
                return false;
            }
        };
        if state.closed {
            return false;
        }
        let terminal_size = event_kind_size(&kind);
        if terminal_size > self.max_bytes || self.max_count == 0 {
            state.closed = true;
            return false;
        }
        let mut dropped = Self::take_pending_output_drop(&mut state);
        while !self.has_capacity(
            &state,
            terminal_size.saturating_add(if dropped == 0 {
                0
            } else {
                OUTPUT_DROPPED_EVENT_BYTES
            }),
            if dropped == 0 { 1 } else { 2 },
        ) && !state.queue.is_empty()
        {
            dropped = add_dropped_bytes(dropped, Self::evict_newest(&mut state));
        }
        let include_drop = dropped > 0
            && self.has_capacity(
                &state,
                terminal_size.saturating_add(OUTPUT_DROPPED_EVENT_BYTES),
                2,
            );
        while !include_drop
            && !self.has_capacity(&state, terminal_size, 1)
            && !state.queue.is_empty()
        {
            dropped = add_dropped_bytes(dropped, Self::evict_newest(&mut state));
        }
        if !self.has_capacity(&state, terminal_size, 1) {
            state.closed = true;
            return false;
        }
        if include_drop {
            let event = self.make_event(
                &mut state,
                TerminalEventKind::OutputDropped { bytes: dropped },
            );
            state.bytes = state.bytes.saturating_add(OUTPUT_DROPPED_EVENT_BYTES);
            state.queue.push_back(event);
        }
        let event = self.make_event(&mut state, kind);
        state.bytes = state.bytes.saturating_add(terminal_size);
        state.queue.push_back(event);
        self.wake.notify_all();
        true
    }

    /// 按绝对 deadline 读取下一条事件，避免多次相对 timeout 累积超时。
    pub(crate) fn recv_until(&self, deadline: Instant) -> Option<TerminalEvent> {
        if self.failed.load(Ordering::Acquire) {
            return None;
        }
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(error) => {
                drop(error);
                self.fail_closed();
                return None;
            }
        };
        loop {
            if let Some(event) = state.queue.pop_front() {
                state.bytes = state.bytes.saturating_sub(event_size(&event));
                return Some(event);
            }
            if state.closed {
                return None;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return None;
            }
            let (next, result) = match self.wake.wait_timeout(state, remaining) {
                Ok(result) => result,
                Err(error) => {
                    drop(error);
                    self.fail_closed();
                    return None;
                }
            };
            state = next;
            if result.timed_out() {
                return None;
            }
        }
    }

    /// 关闭队列但保留已经排队的 terminal event，供 UI 最后消费。
    pub(crate) fn close(&self) {
        self.terminal.store(true, Ordering::Release);
        match self.state.lock() {
            Ok(mut state) => {
                // 正常 close 必须保留已排队终态；只有 poison 才丢弃无法验证的 sequence。
                state.closed = true;
                self.wake.notify_all();
            }
            Err(error) => {
                drop(error);
                self.fail_closed();
            }
        }
    }

    /// 写入一条有界事件；terminal bit 阻止 late producer 在终态后重新打开 generation。
    fn push_kind(&self, kind: TerminalEventKind) -> bool {
        if self.failed.load(Ordering::Acquire) {
            return false;
        }
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(error) => {
                drop(error);
                self.fail_closed();
                return false;
            }
        };
        if state.closed
            || self.terminal.load(Ordering::Acquire)
            || state.queue.len() >= self.max_count
        {
            return false;
        }
        let event = self.make_event(&mut state, kind);
        let size = event_size(&event);
        if state.bytes.saturating_add(size) > self.max_bytes {
            return false;
        }
        state.bytes = state.bytes.saturating_add(size);
        state.queue.push_back(event);
        self.wake.notify_one();
        true
    }

    /// event state poison 会破坏 sequence 和 byte budget 两个不变量；
    /// 因此丢弃未确认队列并永久关闭 generation，不尝试继续编号。
    fn fail_closed(&self) {
        self.failed.store(true, Ordering::Release);
        self.terminal.store(true, Ordering::Release);
        self.rebuild_closed_state();
    }

    /// 在 terminal/failed 原子栅栏之后将状态重建为空关闭队列，并唤醒所有等待者。
    fn rebuild_closed_state(&self) {
        self.state.clear_poison();
        if let Ok(mut state) = self.state.lock() {
            state.closed = true;
            state.queue.clear();
            state.bytes = 0;
        }
        self.wake.notify_all();
    }

    /// 使用饱和运算同时检查字节与数量上限，即便输入极端预算也保持失败关闭的准入策略。
    fn has_capacity(&self, state: &EventState, bytes: usize, count: usize) -> bool {
        state.bytes.saturating_add(bytes) <= self.max_bytes
            && state.queue.len().saturating_add(count) <= self.max_count
    }

    /// 将增量合并到唯一待处理丢弃标记；固定大小标记让持续生产者压力保持常量空间，
    /// 避免递归事件风暴。
    fn merge_pending_output_drop(state: &mut EventState, dropped: usize) -> bool {
        for event in &mut state.queue {
            if let TerminalEventKind::OutputDropped { bytes } = &mut event.kind {
                *bytes = add_dropped_bytes(*bytes, dropped);
                return true;
            }
        }
        false
    }

    /// 从 newest event 开始淘汰以预留 marker，既保持 UI 尚未消费的旧 output 顺序，也累计
    /// 每一个被移除的 output byte。
    pub(crate) fn record_output_drop(&self, state: &mut EventState, mut dropped: usize) {
        if dropped == 0 || Self::merge_pending_output_drop(state, dropped) {
            return;
        }
        while !self.has_capacity(state, OUTPUT_DROPPED_EVENT_BYTES, 1) && !state.queue.is_empty() {
            dropped = add_dropped_bytes(dropped, Self::evict_newest(state));
        }
        if self.has_capacity(state, OUTPUT_DROPPED_EVENT_BYTES, 1) {
            let event = self.make_event(state, TerminalEventKind::OutputDropped { bytes: dropped });
            state.bytes = state.bytes.saturating_add(OUTPUT_DROPPED_EVENT_BYTES);
            state.queue.push_back(event);
        }
    }

    /// 移除最新事件并只返回由此丢失的输出字节数；固定大小控制事件不会扩大前端增量。
    fn evict_newest(state: &mut EventState) -> usize {
        let Some(event) = state.queue.pop_back() else {
            return 0;
        };
        state.bytes = state.bytes.saturating_sub(event_size(&event));
        match event.kind {
            TerminalEventKind::Output { data } => data.len(),
            TerminalEventKind::OutputDropped { bytes } => bytes,
            _ => 0,
        }
    }

    /// 重建 terminal ordering 前移除既有 drop marker，并返回尚未投递的 delta，避免压力统计重复。
    fn take_pending_output_drop(state: &mut EventState) -> usize {
        let Some(index) = state
            .queue
            .iter()
            .position(|event| matches!(event.kind, TerminalEventKind::OutputDropped { .. }))
        else {
            return 0;
        };
        let Some(event) = state.queue.remove(index) else {
            return 0;
        };
        state.bytes = state.bytes.saturating_sub(event_size(&event));
        match event.kind {
            TerminalEventKind::OutputDropped { bytes } => bytes,
            _ => 0,
        }
    }

    /// 在 queue boundary 写入不可变 owner identity 与单调 sequence，producer 不能自行分配序号。
    fn make_event(&self, state: &mut EventState, kind: TerminalEventKind) -> TerminalEvent {
        let sequence = state.next_sequence;
        state.next_sequence = state.next_sequence.saturating_add(1);
        TerminalEvent {
            session_id: self.session_id,
            generation: self.generation,
            sequence,
            kind,
        }
    }
}

/// 估算 JSON/channel event 的驻留大小；output data 是唯一可大幅变化的字段。
fn event_size(event: &TerminalEvent) -> usize {
    event_kind_size(&event.kind)
}

/// 将 delta 限制在 JavaScript 精确整数上限内，使 typed frontend 能接收全部原生事件并使用
/// 相同 saturating accumulation。
fn add_dropped_bytes(current: usize, additional: usize) -> usize {
    current
        .saturating_add(additional)
        .min(MAX_OUTPUT_DROPPED_BYTES)
}

/// sequence 分配前估算 event kind，使拒绝事件不会制造可观察 gap，并维持有界 terminal reservation。
fn event_kind_size(kind: &TerminalEventKind) -> usize {
    match kind {
        TerminalEventKind::Output { data } => data.len().saturating_add(64),
        TerminalEventKind::OutputDropped { .. } => OUTPUT_DROPPED_EVENT_BYTES,
        TerminalEventKind::Exited { signal, .. } => {
            96usize.saturating_add(signal.as_ref().map_or(0, String::len))
        }
        TerminalEventKind::Resized { .. } => 64,
        TerminalEventKind::Closed { .. } | TerminalEventKind::Error { .. } => 64,
    }
}
