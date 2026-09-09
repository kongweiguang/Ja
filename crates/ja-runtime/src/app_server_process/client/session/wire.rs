// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Sidecar session 内部的 stdio wire pump 与 terminal 传播。
//!
//! 每个方向只有一个 owner：reader 解析 stdout、writer 独占 stdin、stderr 独立 drain，
//! 从而协议推进不会等待 UI 工作。

use super::ResolveDisposition;
use super::{STDERR_REDACTED_SUMMARY, SessionEvent, SessionInner, TerminalReason};
use crate::app_server_process::error::{AppServerProcessError, QueueKind};
use crate::app_server_process::protocol::{
    self as codec, FrameKind, RpcFrame, valid_schema_id, valid_timestamp,
};
use std::io::{BufReader, Read, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::sync::{Condvar, Mutex};
use std::thread;
use std::time::Duration;

/// 数据队列的预算必须独立于控制保留区，否则 delta 可以把审批/关闭饿死。
pub(crate) const MAX_WRITER_DATA_QUEUE_BYTES: usize = 64 * 1024 * 1024;
/// 控制帧保留独立预算，至少容纳一个协商允许的最大 frame（含 JSONL 换行）。
pub(crate) fn control_queue_byte_budget(max_frame_bytes: usize) -> usize {
    max_frame_bytes.saturating_add(1)
}

/// 限制单个完整 stdin frame 的写入时间，防止 wedged child 让 writer actor 超过 lifecycle
/// deadline；到期由 terminal callback 取消进程树。
pub(crate) const DEFAULT_WRITE_WATCHDOG_TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EventPriority {
    Control,
    Data,
}

pub(crate) struct WriterHandle {
    pub(super) control: SyncSender<Vec<u8>>,
    pub(super) data: SyncSender<Vec<u8>>,
    pub(super) closed: Arc<std::sync::atomic::AtomicBool>,
    control_queued_bytes: Arc<AtomicUsize>,
    data_queued_bytes: Arc<AtomicUsize>,
    control_max_bytes: usize,
    data_max_bytes: usize,
    pub(crate) wake: Arc<WriterWake>,
}

pub(crate) struct WriterWake {
    pub(crate) sequence: Mutex<u64>,
    condition: Condvar,
}

impl WriterWake {
    /// 创建无轮询的 writer 唤醒状态，发送者只递增 sequence 并通知 actor。
    fn new() -> Self {
        Self {
            sequence: Mutex::new(0),
            condition: Condvar::new(),
        }
    }

    /// 记录新 frame 或 close 事实，避免 writer 在空队列上固定 sleep。
    fn notify(&self) -> bool {
        let Ok(mut sequence) = self.sequence.lock() else {
            // sequence 只用于避免 lost wake；中毒后直接唤醒 waiter 并让 Session 关闭，
            // 不能继续依据可能回退的序号接收新 frame。
            self.condition.notify_all();
            return false;
        };
        *sequence = sequence.wrapping_add(1);
        self.condition.notify_one();
        true
    }

    /// 读取当前 sequence，供 actor 在检查双队列后检测竞态唤醒。
    fn snapshot(&self) -> Option<u64> {
        self.sequence.lock().ok().map(|sequence| *sequence)
    }
}

impl WriterHandle {
    /// 创建带独立唤醒序列的 writer handle，保证 session 不需要了解队列实现细节。
    pub(crate) fn new(
        control: SyncSender<Vec<u8>>,
        data: SyncSender<Vec<u8>>,
        closed: Arc<std::sync::atomic::AtomicBool>,
        control_queued_bytes: Arc<AtomicUsize>,
        data_queued_bytes: Arc<AtomicUsize>,
        control_max_bytes: usize,
        data_max_bytes: usize,
    ) -> Self {
        Self {
            control,
            data,
            closed,
            control_queued_bytes,
            data_queued_bytes,
            control_max_bytes,
            data_max_bytes,
            wake: Arc::new(WriterWake::new()),
        }
    }

    /// 先编码再入队，确保 queue 中的每个项目都是完整单 frame。
    pub(crate) fn send(
        &self,
        frame: Vec<u8>,
        priority: EventPriority,
    ) -> Result<(), AppServerProcessError> {
        if self.closed.load(Ordering::Acquire) {
            return Err(AppServerProcessError::SessionClosed);
        }
        let frame_bytes = frame.len();
        let (queued_bytes, max_bytes) = match priority {
            EventPriority::Control => (&self.control_queued_bytes, self.control_max_bytes),
            EventPriority::Data => (&self.data_queued_bytes, self.data_max_bytes),
        };
        if !reserve_bytes(queued_bytes, frame_bytes, max_bytes) {
            return Err(match priority {
                EventPriority::Control => AppServerProcessError::QueueFull(QueueKind::Control),
                EventPriority::Data => AppServerProcessError::QueueFull(QueueKind::Data),
            });
        }
        if self.closed.load(Ordering::Acquire) {
            queued_bytes.fetch_sub(frame_bytes, Ordering::AcqRel);
            return Err(AppServerProcessError::SessionClosed);
        }
        let result = match priority {
            EventPriority::Control => self.control.try_send(frame).map_err(|error| match error {
                mpsc::TrySendError::Full(_) => AppServerProcessError::QueueFull(QueueKind::Control),
                mpsc::TrySendError::Disconnected(_) => {
                    AppServerProcessError::QueueClosed(QueueKind::Control)
                }
            }),
            EventPriority::Data => self.data.try_send(frame).map_err(|error| match error {
                mpsc::TrySendError::Full(_) => AppServerProcessError::QueueFull(QueueKind::Data),
                mpsc::TrySendError::Disconnected(_) => {
                    AppServerProcessError::QueueClosed(QueueKind::Data)
                }
            }),
        };
        if result.is_err() {
            queued_bytes.fetch_sub(frame_bytes, Ordering::AcqRel);
        } else if !self.wake.notify() {
            self.closed.store(true, Ordering::Release);
            return Err(AppServerProcessError::ProtocolFault);
        }
        result
    }

    /// writer actor 消费成功入队的 frame 后释放对应字节预算。
    fn release(&self, priority: EventPriority, bytes: usize) {
        let counter = match priority {
            EventPriority::Control => &self.control_queued_bytes,
            EventPriority::Data => &self.data_queued_bytes,
        };
        counter.fetch_sub(bytes, Ordering::AcqRel);
    }

    /// 关闭 writer actor 的输入并唤醒等待者，保证 shutdown 不依赖轮询间隔。
    pub(super) fn close(&self) {
        self.closed.store(true, Ordering::Release);
        let _ = self.wake.notify();
    }
}

/// 以 CAS 保证多个 request 线程不能同时突破 writer 总字节预算。
fn reserve_bytes(counter: &AtomicUsize, bytes: usize, max: usize) -> bool {
    let mut current = counter.load(Ordering::Acquire);
    loop {
        if bytes > max.saturating_sub(current) {
            return false;
        }
        match counter.compare_exchange_weak(
            current,
            current.saturating_add(bytes),
            Ordering::AcqRel,
            Ordering::Acquire,
        ) {
            Ok(_) => return true,
            Err(next) => current = next,
        }
    }
}

/// writer actor 是 stdin 唯一 owner；control frame 保留区确保 shutdown 始终可达。
pub(super) fn writer_loop<W: Write + Send + 'static>(
    mut writer: W,
    control: Receiver<Vec<u8>>,
    data: Receiver<Vec<u8>>,
    inner: Arc<SessionInner>,
) {
    let closed = Arc::clone(&inner.closed);
    let wake = Arc::clone(&inner.writer.wake);
    let Some(mut observed_sequence) = wake.snapshot() else {
        fail_closed(&inner);
        return;
    };
    let mut control_burst = 0_usize;
    while !closed.load(Ordering::Acquire) {
        // 优先处理 control，但有限 burst 防止连续 telemetry stream 让已入队 data frame 饥饿。
        let frame = next_frame(&control, &data, &mut control_burst);
        let Some((frame, priority)) = frame else {
            let sequence = wake.sequence.lock();
            let mut sequence = match sequence {
                Ok(sequence) => sequence,
                Err(poisoned) => {
                    drop(poisoned);
                    fail_closed(&inner);
                    break;
                }
            };
            if closed.load(Ordering::Acquire) {
                break;
            }
            if *sequence == observed_sequence {
                let next = wake.condition.wait(sequence);
                sequence = match next {
                    Ok(sequence) => sequence,
                    Err(poisoned) => {
                        drop(poisoned);
                        fail_closed(&inner);
                        break;
                    }
                };
            }
            observed_sequence = *sequence;
            continue;
        };
        inner.writer.release(priority, frame.len());
        let is_initialized = codec::decode_frame(&frame, inner.limits.max_frame_bytes)
            .ok()
            .and_then(|frame| frame.method().map(str::to_owned))
            .as_deref()
            == Some("runtime/initialized");
        let written =
            write_frame_with_watchdog(writer, frame, inner.write_timeout, &inner, is_initialized);
        let next_writer = match written {
            Ok(next_writer) => next_writer,
            Err(error) => {
                let event = if matches!(error, AppServerProcessError::DeadlineExceeded) {
                    SessionEvent::WriterTimedOut
                } else {
                    SessionEvent::ProtocolFault(codec::CodecError::Io)
                };
                publish_terminal_and_fail_closed(&inner, event);
                break;
            }
        };
        writer = next_writer;
        let Some(next_sequence) = wake.snapshot() else {
            fail_closed(&inner);
            break;
        };
        observed_sequence = next_sequence;
    }
    if !closed.load(Ordering::Acquire) {
        fail_closed(&inner);
    }
}

/// 单 writer actor 执行写入，已纳入 join 的 watchdog 触发 terminal cancellation；生产
/// ChildStdin 通过关闭自有 process tree 解阻塞，不创建第二操作线程或 detached JoinHandle。
fn write_frame_with_watchdog<W: Write>(
    mut writer: W,
    frame: Vec<u8>,
    timeout: Duration,
    inner: &Arc<SessionInner>,
    is_initialized: bool,
) -> Result<W, AppServerProcessError> {
    // 两次 I/O 调用都持有 reader claim ready 使用的同一 gate，使握手线性化不依赖 pipe 时序。
    let initialized_guard = if is_initialized {
        Some(inner.initialized_barrier.begin_send()?)
    } else {
        None
    };
    let (cancel_sender, cancel_receiver) = mpsc::sync_channel(1);
    let timed_out = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let watchdog_timed_out = Arc::clone(&timed_out);
    let watchdog_inner = Arc::clone(inner);
    let watchdog = match thread::Builder::new()
        .name("ja-sidecar-write-watchdog".to_owned())
        .spawn(move || {
            match cancel_receiver.recv_timeout(timeout) {
                Ok(()) | Err(mpsc::RecvTimeoutError::Disconnected) => false,
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    watchdog_timed_out.store(true, Ordering::Release);
                    // JSONL 写到半帧时不能安全中断；terminal callback 通过终止自有
                    // process tree 并关闭 session 来解除 ChildStdin 阻塞。
                    fail_closed(&watchdog_inner);
                    true
                }
            }
        }) {
        Ok(watchdog) => watchdog,
        Err(_) => {
            if let Some(guard) = initialized_guard {
                guard.complete(false);
            }
            return Err(AppServerProcessError::Spawn);
        }
    };
    let write_result = writer.write_all(&frame);
    let result = write_result.and_then(|_| writer.flush());
    let _ = cancel_sender.send(());
    let watchdog_fired = match watchdog.join() {
        Ok(fired) => fired || timed_out.load(Ordering::Acquire),
        Err(_) => {
            if let Some(guard) = initialized_guard {
                guard.complete(false);
            }
            return Err(AppServerProcessError::Spawn);
        }
    };
    // watchdog join 前不发布 Confirmed；timeout 后迟到的 write completion 对所有等待
    // ready 的路径都必须保持 fail-closed。
    if let Some(guard) = initialized_guard {
        guard.complete(result.is_ok() && !watchdog_fired && !inner.closed.load(Ordering::Acquire));
    }
    if watchdog_fired || timed_out.load(Ordering::Acquire) {
        return Err(AppServerProcessError::DeadlineExceeded);
    }
    match result {
        Ok(()) => Ok(writer),
        Err(_) => Err(AppServerProcessError::SessionClosed),
    }
}

pub(crate) const MAX_CONTROL_BURST: usize = 8;

/// 只匹配必须穿越满 data lane 的 Turn terminal notification；payload 校验仍由协议 decoder
/// 负责，使 malformed terminal frame 与正常 frame 采用同一路由策略后再 fail closed。
fn is_turn_terminal_notification(frame: &RpcFrame) -> bool {
    frame.method() == Some("turn/terminal")
}

/// Context started/compacted/failed 是一次压缩操作的不可丢弃生命周期事实；即使普通
/// delta lane 已满，也必须通过 control reserve 到达 Thread 投影或显式终止 generation。
fn is_context_lifecycle_notification(frame: &RpcFrame) -> bool {
    matches!(
        frame.method(),
        Some("context/compaction-started" | "context/compacted" | "context/compaction-failed")
    )
}

/// Task activity/mailbox 与 Approval 是不可重建的即时交互信号；它们使用 control reserve，
/// 队列耗尽时终止 generation，也不能像 progress/delta 一样静默丢弃。
fn is_durable_interaction_notification(frame: &RpcFrame) -> bool {
    matches!(
        frame.method(),
        Some("task/activity" | "task/mailbox-changed" | "approval/requested" | "approval/resolved")
    )
}

/// 保持既有 runtime control lane，并提升 Turn terminal 与 Context 生命周期；queue kind
/// 跟随 lane，因此 control reserve 真正耗尽时仍报告 fatal overflow，不静默丢最终事实。
pub(crate) fn notification_routing(frame: &RpcFrame) -> (EventPriority, QueueKind) {
    if is_turn_terminal_notification(frame)
        || is_context_lifecycle_notification(frame)
        || is_durable_interaction_notification(frame)
    {
        return (EventPriority::Control, QueueKind::Control);
    }
    if frame
        .method()
        .is_some_and(|method| method.starts_with("runtime/"))
    {
        (EventPriority::Control, QueueKind::Data)
    } else {
        (EventPriority::Data, QueueKind::Data)
    }
}

/// 以有界 control 优先级选择下一帧，既不轮询等待，也不让 data 永久饥饿。
pub(crate) fn next_frame(
    control: &Receiver<Vec<u8>>,
    data: &Receiver<Vec<u8>>,
    control_burst: &mut usize,
) -> Option<(Vec<u8>, EventPriority)> {
    if *control_burst >= MAX_CONTROL_BURST
        && let Ok(frame) = data.try_recv()
    {
        *control_burst = 0;
        return Some((frame, EventPriority::Data));
    }
    if let Ok(frame) = control.try_recv() {
        *control_burst = (*control_burst).saturating_add(1);
        return Some((frame, EventPriority::Control));
    }
    if let Ok(frame) = data.try_recv() {
        *control_burst = 0;
        return Some((frame, EventPriority::Data));
    }
    None
}

/// stdout reader 永久运行并只把完整合法 frame 交给 session dispatcher。
pub(super) fn spawn_reader<R: Read + Send + 'static>(
    reader: R,
    inner: Arc<SessionInner>,
) -> Result<(), AppServerProcessError> {
    thread::Builder::new()
        .name("ja-sidecar-reader".to_owned())
        .spawn(move || reader_loop(reader, inner))
        .map(|_| ())
        .map_err(|_| AppServerProcessError::Spawn)
}

/// 读取线程只负责 framing/dispatch，避免业务等待阻塞 stdout 消费。
fn reader_loop<R: Read>(reader: R, inner: Arc<SessionInner>) {
    let mut reader = BufReader::new(reader);
    while !inner.closed.load(Ordering::Acquire) {
        // blocking I/O 前 clone 有界 challenge set，防止 token 安装与 shutdown 等待
        // stdout reader 的 I/O 阻塞。
        let forbidden = match inner.forbidden_ready_tokens.lock() {
            Ok(forbidden) => forbidden.clone(),
            Err(_) => {
                publish_terminal_and_fail_closed(
                    &inner,
                    SessionEvent::ProtocolFault(codec::CodecError::InvalidEnvelope),
                );
                break;
            }
        };
        match codec::read_frame_with_forbidden(
            &mut reader,
            inner.limits.max_frame_bytes,
            &forbidden,
        ) {
            Ok(frame) => dispatch_frame(frame, &inner),
            Err(codec::CodecError::UnexpectedEof) => {
                publish_terminal_and_fail_closed(&inner, SessionEvent::Eof);
                break;
            }
            Err(codec::CodecError::HandshakeFailed) => {
                publish_terminal_and_fail_closed(&inner, SessionEvent::HandshakeFailed);
                break;
            }
            Err(error) => {
                publish_terminal_and_fail_closed(&inner, SessionEvent::ProtocolFault(error));
                break;
            }
        }
    }
}

/// response 走 pending，server notification 进入独立 bounded event queue。
fn dispatch_frame(frame: RpcFrame, inner: &Arc<SessionInner>) {
    match frame.validate() {
        Ok(FrameKind::Response) => {
            if !frame_payload_is_safe(&frame, inner, false) {
                publish_terminal_and_fail_closed(
                    inner,
                    SessionEvent::ProtocolFault(codec::CodecError::InvalidEnvelope),
                );
                return;
            }
            let pending = inner.pending.lock();
            let disposition = match pending {
                Ok(mut pending) => pending.resolve(frame),
                Err(poisoned) => {
                    drop(poisoned);
                    publish_terminal_and_fail_closed(
                        inner,
                        SessionEvent::ProtocolFault(codec::CodecError::InvalidEnvelope),
                    );
                    return;
                }
            };
            // 本地 request deadline 只终止对应 waiter；迟到响应已被有界 tombstone 识别，
            // 可以安全丢弃，不能因此关闭仍在执行 Turn 的健康 sidecar。
            if matches!(
                disposition,
                ResolveDisposition::Delivered | ResolveDisposition::LateResponse
            ) {
                return;
            }
            publish_terminal_and_fail_closed(inner, SessionEvent::ResponseRejected);
        }
        Ok(FrameKind::Notification) => {
            let is_ready = frame.method() == Some("runtime/status-changed")
                && frame
                    .params()
                    .and_then(|params| params.get("status"))
                    .and_then(serde_json::Value::as_str)
                    == Some("ready");
            let is_initialized = frame.method() == Some("runtime/initialized");
            if !frame_payload_is_safe(&frame, inner, is_ready) {
                publish_terminal_and_fail_closed(
                    inner,
                    if is_ready || is_initialized {
                        SessionEvent::HandshakeFailed
                    } else {
                        SessionEvent::ProtocolFault(codec::CodecError::InvalidEnvelope)
                    },
                );
                return;
            }
            if is_ready && !inner.claim_ready_notification() {
                publish_terminal_and_fail_closed(inner, SessionEvent::HandshakeFailed);
                return;
            }
            if frame.method() == Some("turn/terminal") && !claim_terminal_identity(&frame, inner) {
                publish_terminal_and_fail_closed(
                    inner,
                    SessionEvent::ProtocolFault(codec::CodecError::InvalidEnvelope),
                );
                return;
            }
            let (priority, kind) = notification_routing(&frame);
            push_event(inner, frame.into_notification(), priority, kind);
        }
        Ok(FrameKind::ClientRequest) | Err(_) => {
            publish_terminal_and_fail_closed(
                inner,
                SessionEvent::ProtocolFault(codec::CodecError::InvalidEnvelope),
            );
        }
    }
}

/// 每个 generation 只接受单 Thread 单调 revision 上的新 Turn terminal；账本按
/// Thread 有界，而不是按历史 Turn 无界增长。达到上限时 fail-closed，不淘汰身份。
fn claim_terminal_identity(frame: &RpcFrame, inner: &Arc<SessionInner>) -> bool {
    let Some(params) = frame.params().and_then(serde_json::Value::as_object) else {
        return false;
    };
    let Some(server_instance_id) = params
        .get("serverInstanceId")
        .and_then(serde_json::Value::as_str)
    else {
        return false;
    };
    let Some(turn_id) = params.get("turnId").and_then(serde_json::Value::as_str) else {
        return false;
    };
    let Some(thread_id) = params.get("threadId").and_then(serde_json::Value::as_str) else {
        return false;
    };
    let Some(event_id) = params.get("eventId").and_then(serde_json::Value::as_str) else {
        return false;
    };
    let Some(thread_revision) = params
        .get("threadRevision")
        .and_then(serde_json::Value::as_u64)
        .filter(|revision| *revision <= 9_007_199_254_740_991)
    else {
        return false;
    };
    if !valid_schema_id(server_instance_id, "srv_", 100)
        || !valid_schema_id(turn_id, "turn_", 101)
        || !valid_schema_id(thread_id, "thr_", 100)
        || !valid_schema_id(event_id, "evt_", 100)
        || !params
            .get("occurredAt")
            .and_then(serde_json::Value::as_str)
            .is_some_and(valid_timestamp)
        || !matches!(
            params.get("state").and_then(serde_json::Value::as_str),
            Some("completed" | "failed" | "cancelled")
        )
    {
        return false;
    }
    let Ok(mut identities) = inner.terminal_identities.lock() else {
        // terminal identity 是幂等与 revision 单调性的权威账本；中毒后拒绝事件，
        // dispatch 会把当前 Session 作为协议故障关闭。
        return false;
    };
    if let Some(cursor) = identities.by_thread.get_mut(thread_id) {
        if thread_revision <= cursor.thread_revision || turn_id == cursor.turn_id {
            return false;
        }
        cursor.turn_id = turn_id.to_owned();
        cursor.thread_revision = thread_revision;
        return true;
    }
    if identities.by_thread.len() >= super::MAX_TERMINAL_IDENTITIES {
        return false;
    }
    identities.by_thread.insert(
        thread_id.to_owned(),
        super::TerminalCursor {
            turn_id: turn_id.to_owned(),
            thread_revision,
        },
    );
    true
}

/// 递归审计 inbound frame；ready 例外只放行当前 token 的顶层 params 字段。
fn frame_payload_is_safe(frame: &RpcFrame, inner: &Arc<SessionInner>, ready: bool) -> bool {
    let Ok(forbidden) = inner.forbidden_ready_tokens.lock() else {
        return false;
    };
    if frame.id_opt().is_some_and(|id| forbidden.contains(id))
        || frame
            .method()
            .is_some_and(|method| forbidden.contains(method))
        || frame
            .error()
            .is_some_and(|error| forbidden.contains(error.message()))
        || frame
            .result()
            .value()
            .is_some_and(|value| contains_forbidden_token(value, &forbidden))
        || frame
            .error()
            .is_some_and(|error| contains_forbidden_token(error.data(), &forbidden))
    {
        return false;
    }
    let Some(params) = frame.params() else {
        return !ready;
    };
    if !ready {
        return !contains_forbidden_token(params, &forbidden);
    }
    let Ok(challenge) = inner.ready_token_challenge.lock() else {
        return false;
    };
    let Some(expected) = challenge.clone() else {
        return false;
    };
    let Some(object) = params.as_object() else {
        return false;
    };
    object.iter().all(|(key, value)| {
        if key == "readyToken" {
            value.as_str() == Some(expected.as_str()) && codec::valid_ready_token(&expected)
        } else {
            !forbidden.contains(key)
                && !codec::is_ready_token_key(key)
                && !contains_forbidden_token(value, &forbidden)
        }
    })
}

/// 递归检查 key/value，防止 token 通过 details、数组或扩展字段泄露。
fn contains_forbidden_token(
    value: &serde_json::Value,
    forbidden: &std::collections::HashSet<String>,
) -> bool {
    match value {
        serde_json::Value::Object(object) => object.iter().any(|(key, child)| {
            codec::is_ready_token_key(key)
                || forbidden.contains(key)
                || contains_forbidden_token(child, forbidden)
        }),
        serde_json::Value::Array(values) => values
            .iter()
            .any(|child| contains_forbidden_token(child, forbidden)),
        serde_json::Value::String(text) => forbidden.contains(text),
        _ => false,
    }
}

/// 将合法 notification 包装为事件，保持事件 API 不暴露无方向的裸 frame。
trait IntoNotification {
    /// 将已通过 codec 校验的 notification 包装成定向 session 事件。
    fn into_notification(self) -> SessionEvent;
}

impl IntoNotification for RpcFrame {
    /// 保持 notification 的方向信息，避免后续 caller 把它当 response 消费。
    fn into_notification(self) -> SessionEvent {
        SessionEvent::Notification(self)
    }
}

/// stderr 永久 drain；超限只截断诊断，不阻塞 stdout 协议 reader。
pub(super) fn spawn_stderr_reader<E: Read + Send + 'static>(
    stderr: E,
    inner: Arc<SessionInner>,
) -> Result<(), AppServerProcessError> {
    thread::Builder::new()
        .name("ja-sidecar-stderr".to_owned())
        .spawn(move || stderr_loop(stderr, inner))
        .map(|_| ())
        .map_err(|_| AppServerProcessError::Spawn)
}

/// 持续读取 stderr，即使诊断超预算也继续 drain 以免阻塞 stdout 协议。
fn stderr_loop<E: Read>(mut stderr: E, inner: Arc<SessionInner>) {
    let max = inner.limits.max_stderr_line_bytes;
    let max_total = inner.limits.max_log_bytes as u64;
    let mut line = Vec::with_capacity(max.min(4096));
    let mut byte = [0_u8; 1];
    loop {
        match stderr.read(&mut byte) {
            Ok(0) => break,
            Ok(_) if byte[0] == b'\n' => {
                let offset = inner.stderr_bytes.fetch_add(1, Ordering::AcqRel);
                if offset < max_total && !inner.stderr_truncated.load(Ordering::Acquire) {
                    emit_stderr_line(&mut line, &inner);
                } else {
                    emit_stderr_truncated(&inner);
                    line.clear();
                }
            }
            Ok(_) => {
                let offset = inner.stderr_bytes.fetch_add(1, Ordering::AcqRel);
                if offset >= max_total {
                    emit_stderr_truncated(&inner);
                    line.clear();
                } else if line.len() < max {
                    line.push(byte[0]);
                } else {
                    drain_stderr_line(&mut stderr, &mut byte, &inner);
                    emit_stderr_truncated(&inner);
                    line.clear();
                }
            }
            Err(_) => break,
        }
    }
    if !line.is_empty()
        && !inner.stderr_truncated.load(Ordering::Acquire)
        && inner.stderr_bytes.load(Ordering::Acquire) <= max_total
    {
        emit_stderr_line(&mut line, &inner);
    }
}

/// 丢弃超长诊断行的剩余字节，避免 stderr 反压阻塞 stdout 协议 reader。
fn drain_stderr_line<E: Read>(stderr: &mut E, byte: &mut [u8; 1], inner: &Arc<SessionInner>) {
    while let Ok(read) = stderr.read(byte) {
        if read == 0 {
            break;
        }
        let offset = inner.stderr_bytes.fetch_add(1, Ordering::AcqRel);
        if offset >= inner.limits.max_log_bytes as u64 {
            emit_stderr_truncated(inner);
        }
        if byte[0] == b'\n' {
            break;
        }
    }
}

/// 只发送一次稳定截断事件，随后继续 drain 但不把诊断内容留在内存。
fn emit_stderr_truncated(inner: &Arc<SessionInner>) {
    if !inner.stderr_truncated.swap(true, Ordering::AcqRel) {
        push_event(
            inner,
            SessionEvent::StderrTruncated,
            EventPriority::Data,
            QueueKind::Stderr,
        );
    }
}

/// 将预算内的一行转换为脱敏事件并立即清空暂存 buffer。
fn emit_stderr_line(line: &mut Vec<u8>, inner: &Arc<SessionInner>) {
    // raw stderr 可能包含 API key、用户路径、prompt 或源码；只发布固定诊断，保证
    // event queue 与 Debug output 不泄密。
    line.clear();
    push_event(
        inner,
        SessionEvent::StderrLine(STDERR_REDACTED_SUMMARY.to_owned()),
        EventPriority::Data,
        QueueKind::Stderr,
    );
}

/// 一旦发生不可恢复 queue/protocol 故障，统一停止 writer 并释放 pending waiters。
/// 只执行一次 session 终止，确保所有 reader/writer fault 都释放 pending。
pub(super) fn fail_closed(inner: &Arc<SessionInner>) {
    fail_closed_with_reason(inner, TerminalReason::Fault);
}

/// 只执行一次终止通知，并在回调前关闭事实置位，避免回调重入重复旋转 generation。
pub(super) fn fail_closed_with_reason(inner: &Arc<SessionInner>, reason: TerminalReason) {
    terminate_session(inner, reason, None);
}

/// 原子提交终态和对应事件；`closed` 必须先于事件对 consumer 可见，而事件队列要到
/// 入队后才关闭，才能同时阻止 ready 晋级并避免等待方错过终态。
fn publish_terminal_and_fail_closed(inner: &Arc<SessionInner>, event: SessionEvent) {
    publish_terminal_and_fail_closed_with_reason(inner, TerminalReason::Fault, event);
}

/// 允许 process monitor 保留 `ProcessExited` 原因，同时复用同一终态线性化顺序；
/// 原因只进入受控 callback，事件仍使用脱敏的领域枚举。
pub(super) fn publish_terminal_and_fail_closed_with_reason(
    inner: &Arc<SessionInner>,
    reason: TerminalReason,
    event: SessionEvent,
) {
    terminate_session(inner, reason, Some(event));
}

/// ready/terminal gate 是生命周期线性化点；终态事件直接写入 control lane，避免通过
/// `push_event` 的 overflow 回调重入同一 gate。首次终止负责完整资源清理，重复终止只补事实。
fn terminate_session(
    inner: &Arc<SessionInner>,
    reason: TerminalReason,
    terminal_event: Option<SessionEvent>,
) {
    // 正常路径持有 ready gate，保证 promotion 与 terminal 二选一；gate 自身中毒时
    // promotion 已失去可信状态，仍以 closed atomic 为最终 fail-closed 事实继续清理。
    let _ready_terminal_gate = inner.ready_terminal_gate.lock().ok();
    let first_terminal = !inner.closed.swap(true, Ordering::AcqRel);
    if let Some(event) = terminal_event {
        inner
            .events
            .push(event, EventPriority::Control, QueueKind::Control);
    }
    if first_terminal {
        // reader/writer thread 可能是 EOF 或 I/O fault 的唯一 observer；此处直接调用
        // process owner，不等待 UI poll。
        inner.writer.close();
        inner.events.close();
        if !inner.terminal_callback_taken.swap(true, Ordering::AcqRel)
            && let Some(callback) = inner.terminal_callback.as_ref()
        {
            callback(reason);
        }
        if let Ok(mut pending) = inner.pending.lock() {
            pending.close();
        }
    }
}

/// 把事件写入 bounded queue 后立刻检查 overflow，避免 fatal 被 data queue 延迟遮蔽。
pub(super) fn push_event(
    inner: &Arc<SessionInner>,
    event: SessionEvent,
    priority: EventPriority,
    kind: QueueKind,
) {
    if inner.closed.load(Ordering::Acquire) && !is_terminal_event(&event) {
        return;
    }
    inner.events.push(event, priority, kind);
    if inner.events.is_fatal() {
        fail_closed(inner);
    }
}

/// 终止后只允许 fault/exit 事实继续入队，防止 stderr 或 delta 在 close 后复留。
pub(crate) fn is_terminal_event(event: &SessionEvent) -> bool {
    matches!(
        event,
        SessionEvent::ProtocolFault(_)
            | SessionEvent::WriterTimedOut
            | SessionEvent::HandshakeFailed
            | SessionEvent::Eof
            | SessionEvent::QueueFatalOverflow(_)
            | SessionEvent::ProcessExited { .. }
            | SessionEvent::ResponseRejected
    ) || matches!(
        event,
        SessionEvent::Notification(frame)
            if is_turn_terminal_notification(frame) || is_durable_interaction_notification(frame)
    )
}
