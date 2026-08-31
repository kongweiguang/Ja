// @author kongweiguang
// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 单个 sidecar session 内部的有界事件路由。
//!
//! 控制事实与数据 delta 使用独立队列，避免慢 UI 用普通输出遮蔽生命周期终态。

use super::wire::{EventPriority, control_queue_byte_budget, is_terminal_event};
use super::{MAX_OPERATION_TIMEOUT, SessionEvent};
use crate::app_server_process::error::QueueKind;
use crate::app_server_process::protocol::RpcFrame;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
use std::time::{Duration, Instant};

pub(crate) const CONTROL_QUEUE_CAPACITY: usize = 64;
const MAX_EVENT_QUEUE_BYTES: usize = 64 * 1024 * 1024;
pub(crate) const MAX_EVENT_CONTROL_BURST: usize = 8;

pub(crate) struct EventQueue {
    pub(crate) state: Mutex<EventQueueState>,
    wake: Condvar,
    data_capacity: usize,
    control_capacity: usize,
    max_data_bytes: usize,
    pub(crate) max_control_bytes: usize,
    max_frame_bytes: usize,
    poisoned: AtomicBool,
    poison_reported: AtomicBool,
}

pub(crate) struct EventQueueState {
    data: VecDeque<SessionEvent>,
    control: VecDeque<SessionEvent>,
    data_bytes: usize,
    control_bytes: usize,
    fatal: Option<QueueKind>,
    fatal_reported: bool,
    data_overflow_reported: bool,
    control_burst: usize,
    closed: bool,
}

/// 提取持久 Thread 通知的排序键；公共进程元数据已由协议入口统一校验，
/// 队列这里只比较同一 Thread 的 revision，避免把跨 Thread 事件错误串行化。
fn thread_event_order(frame: &RpcFrame) -> Option<(&str, u64)> {
    let params = frame.params()?.as_object()?;
    Some((
        params.get("threadId")?.as_str()?,
        params.get("threadRevision")?.as_u64()?,
    ))
}

/// 保持 Thread 内顺序且不把终态移回可能溢出的数据队列；终态继续占用保留控制槽，
/// 但必须等待同一 Thread 的更早事件完成消费，其他 Thread 的控制事件仍可优先推进。
fn terminal_waits_for_prior_data(control: &SessionEvent, data: &VecDeque<SessionEvent>) -> bool {
    let SessionEvent::Notification(terminal) = control else {
        return false;
    };
    if terminal.method() != Some("turn/terminal") {
        return false;
    }
    let Some((thread_id, terminal_seq)) = thread_event_order(terminal) else {
        return false;
    };
    data.iter().any(|event| {
        let SessionEvent::Notification(frame) = event else {
            return false;
        };
        thread_event_order(frame).is_some_and(|(candidate_thread, seq)| {
            candidate_thread == thread_id && seq < terminal_seq
        })
    })
}

/// 估算事件驻留字节，和数量上限一起限制慢消费者造成的内存增长。
fn event_size(event: &SessionEvent, max_frame_bytes: usize) -> usize {
    match event {
        SessionEvent::Notification(frame) => frame
            .encode(max_frame_bytes)
            .map(|encoded| encoded.len())
            .unwrap_or(max_frame_bytes.saturating_add(1)),
        SessionEvent::StderrLine(line) => line.len(),
        SessionEvent::ProtocolFault(_) => 128,
        SessionEvent::WriterTimedOut => 64,
        SessionEvent::HandshakeFailed => 64,
        SessionEvent::ResponseRejected => 64,
        SessionEvent::StderrTruncated
        | SessionEvent::Eof
        | SessionEvent::QueueOverflow(_)
        | SessionEvent::QueueFatalOverflow(_)
        | SessionEvent::ProcessExited { .. } => 64,
    }
}

impl EventQueue {
    /// 将控制事实与普通 delta 分队，保证退出/协议故障不会被慢 UI 挤掉。
    pub(crate) fn new(data_capacity: usize, max_frame_bytes: usize) -> Self {
        Self {
            state: Mutex::new(EventQueueState {
                data: VecDeque::with_capacity(data_capacity),
                control: VecDeque::with_capacity(CONTROL_QUEUE_CAPACITY),
                data_bytes: 0,
                control_bytes: 0,
                fatal: None,
                fatal_reported: false,
                data_overflow_reported: false,
                control_burst: 0,
                closed: false,
            }),
            wake: Condvar::new(),
            data_capacity,
            control_capacity: CONTROL_QUEUE_CAPACITY,
            max_data_bytes: MAX_EVENT_QUEUE_BYTES,
            // event control reserve 与 writer framing 使用同一预算，确保协商后的最大
            // control frame 不会在事件路由层被二次拒绝。
            max_control_bytes: control_queue_byte_budget(max_frame_bytes),
            max_frame_bytes,
            poisoned: AtomicBool::new(false),
            poison_reported: AtomicBool::new(false),
        }
    }

    /// 将事件账本中毒提升为不可恢复 control fault；独立 AtomicBool 不读取 poisoned
    /// 队列内容，因此 consumer 可以稳定观察一次终态并停止 Session。
    fn mark_poisoned(&self) {
        self.poisoned.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    /// 非阻塞入队；data 满只发布一次可观测 overflow，control 满才终止 session。
    pub(crate) fn push(&self, event: SessionEvent, priority: EventPriority, kind: QueueKind) {
        let bytes = event_size(&event, self.max_frame_bytes);
        let Ok(mut state) = self.state.lock() else {
            self.mark_poisoned();
            return;
        };
        let capacity = match priority {
            EventPriority::Control => self.control_capacity,
            EventPriority::Data => self.data_capacity,
        };
        let queue_len = match priority {
            EventPriority::Control => state.control.len(),
            EventPriority::Data => state.data.len(),
        };
        let queue_bytes = if matches!(priority, EventPriority::Control) {
            state.control_bytes
        } else {
            state.data_bytes
        };
        let byte_limit = if matches!(priority, EventPriority::Control) {
            self.max_control_bytes
        } else {
            self.max_data_bytes
        };
        if queue_len >= capacity || queue_bytes.saturating_add(bytes) > byte_limit {
            if matches!(priority, EventPriority::Data) {
                if !state.data_overflow_reported {
                    state.data_overflow_reported = true;
                    let notice = SessionEvent::QueueOverflow(kind);
                    let notice_bytes = event_size(&notice, self.max_frame_bytes);
                    let control_full = state.control.len() >= self.control_capacity
                        || state.control_bytes.saturating_add(notice_bytes)
                            > self.max_control_bytes;
                    if control_full {
                        state.fatal.get_or_insert(QueueKind::Control);
                    } else {
                        state.control.push_back(notice);
                        state.control_bytes = state.control_bytes.saturating_add(notice_bytes);
                    }
                }
            } else if state.fatal.is_none() {
                state.fatal = Some(kind);
            }
        } else {
            match priority {
                EventPriority::Control => {
                    state.control.push_back(event);
                    state.control_bytes = state.control_bytes.saturating_add(bytes);
                }
                EventPriority::Data => {
                    state.data.push_back(event);
                    state.data_bytes = state.data_bytes.saturating_add(bytes);
                }
            }
        }
        self.wake.notify_all();
    }

    /// 先消费控制队列，再消费数据队列，确保 shutdown/EOF 的可达性。
    pub(crate) fn pop(&self, timeout: Duration) -> Option<SessionEvent> {
        let deadline = Instant::now().checked_add(timeout.min(MAX_OPERATION_TIMEOUT));
        let Ok(mut state) = self.state.lock() else {
            self.mark_poisoned();
            return (!self.poison_reported.swap(true, Ordering::AcqRel))
                .then_some(SessionEvent::QueueFatalOverflow(QueueKind::Control));
        };
        loop {
            if self.poisoned.load(Ordering::Acquire) {
                return (!self.poison_reported.swap(true, Ordering::AcqRel))
                    .then_some(SessionEvent::QueueFatalOverflow(QueueKind::Control));
            }
            if let Some(kind) = state.fatal.take()
                && !state.fatal_reported
            {
                state.fatal_reported = true;
                return Some(SessionEvent::QueueFatalOverflow(kind));
            }
            if state.control_burst >= MAX_EVENT_CONTROL_BURST
                && let Some(event) = state.data.pop_front()
            {
                state.data_bytes = state
                    .data_bytes
                    .saturating_sub(event_size(&event, self.max_frame_bytes));
                state.control_burst = 0;
                return Some(event);
            }
            let terminal_is_waiting = state
                .control
                .front()
                .is_some_and(|event| terminal_waits_for_prior_data(event, &state.data));
            if !terminal_is_waiting && let Some(event) = state.control.pop_front() {
                state.control_bytes = state
                    .control_bytes
                    .saturating_sub(event_size(&event, self.max_frame_bytes));
                state.control_burst = state.control_burst.saturating_add(1);
                return Some(event);
            }
            if let Some(event) = state.data.pop_front() {
                state.data_bytes = state
                    .data_bytes
                    .saturating_sub(event_size(&event, self.max_frame_bytes));
                state.control_burst = 0;
                return Some(event);
            }
            if state.fatal_reported || state.closed {
                return None;
            }
            let deadline = deadline?;
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return None;
            }
            let Ok((next_state, wait)) = self.wake.wait_timeout(state, remaining) else {
                self.mark_poisoned();
                return (!self.poison_reported.swap(true, Ordering::AcqRel))
                    .then_some(SessionEvent::QueueFatalOverflow(QueueKind::Control));
            };
            state = next_state;
            if wait.timed_out() {
                return None;
            }
        }
    }

    /// 返回不可恢复 overflow 标志，供 session 立即关闭 writer 和 pending。
    pub(crate) fn is_fatal(&self) -> bool {
        if self.poisoned.load(Ordering::Acquire) {
            return true;
        }
        match self.state.lock() {
            Ok(state) => state.fatal.is_some(),
            Err(_) => {
                self.mark_poisoned();
                true
            }
        }
    }

    /// 终止 session 时只保留终态控制事实，避免敏感 command/delta 长期驻留。
    pub(crate) fn close(&self) {
        let Ok(mut state) = self.state.lock() else {
            self.mark_poisoned();
            return;
        };
        state.closed = true;
        state.data.clear();
        state.data_bytes = 0;
        state.control.retain(is_terminal_event);
        state.control_bytes = state
            .control
            .iter()
            .map(|event| event_size(event, self.max_frame_bytes))
            .sum();
        self.wake.notify_all();
    }
}
