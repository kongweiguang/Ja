// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 单向 client-request、双向 response/notification JSONL session。
//!
//! stdout 永久 reader、stdin single-writer actor、stderr 独立 drain 和 pending
//! registry 各自拥有单一职责；这样 Java 等待 Rust approval 时 reader 仍可收发。

pub(crate) mod events;
pub(crate) mod wire;

use super::{PendingRegistry, ResolveDisposition, deadline_after};
use crate::app_server_process::error::{AppServerProcessError, QueueKind};
use crate::app_server_process::protocol::{self as codec, Limits, RpcFrame, valid_ready_token};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use wire::{
    EventPriority, MAX_WRITER_DATA_QUEUE_BYTES, WriterHandle, control_queue_byte_budget,
    fail_closed, push_event, spawn_reader, spawn_stderr_reader, writer_loop,
};

pub(crate) const CONTROL_QUEUE_CAPACITY: usize = 64;
const MAX_TERMINAL_IDENTITIES: usize = 8_192;
const STDERR_REDACTED_SUMMARY: &str = "sidecar stderr output redacted";
const MAX_OPERATION_TIMEOUT: Duration = Duration::from_secs(3_600);
const MAX_WRITER_JOIN_GRACE: Duration = Duration::from_secs(2);

/// session 对外暴露的事件；响应在 pending 内部消费，避免 caller 误把未知 response 当业务事实。
#[derive(Debug, Clone, PartialEq)]
pub enum SessionEvent {
    Notification(RpcFrame),
    StderrLine(String),
    StderrTruncated,
    ResponseRejected,
    ProtocolFault(codec::CodecError),
    /// writer watchdog 到期；supervisor 将其映射为可恢复的 DeadlineExceeded。
    WriterTimedOut,
    HandshakeFailed,
    Eof,
    QueueOverflow(QueueKind),
    QueueFatalOverflow(QueueKind),
    ProcessExited {
        generation: u64,
        code: Option<i32>,
    },
}

use events::EventQueue;

/// 进程 stdin/stdout/stderr 被拆成泛型 IO 后，测试可以用内存 pipe 而不启动真实 Java。
pub struct Session {
    pub(crate) inner: Arc<SessionInner>,
    writer_join: Arc<WriterJoinState>,
}

/// 唯一外部事件消费句柄；不实现 Clone，避免多个 UI reducer 竞争同一事件序列。
pub struct EventPump {
    inner: Arc<SessionInner>,
}

impl EventPump {
    /// 由唯一 owner 消费事件；request 等待路径使用独立 pending completion。
    pub fn next_event(&mut self, timeout: Duration) -> Option<SessionEvent> {
        self.inner.events.pop(timeout)
    }
}

/// 连接终止原因供唯一 lifecycle owner 区分 fault、自然退出和主动关闭。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TerminalReason {
    Fault,
    ProcessExited,
    Closed,
}

/// 连接终态回调；由 supervisor 注入以便协议线程立即终止其拥有的进程树。
pub type TerminalCallback = Arc<dyn Fn(TerminalReason) + Send + Sync + 'static>;

struct WriterCompletion {
    done: AtomicBool,
    wait_lock: Mutex<()>,
    wake: Condvar,
}

impl WriterCompletion {
    /// 创建 writer completion gate，使生命周期 owner 可等待而不轮询。
    fn new() -> Self {
        Self {
            done: AtomicBool::new(false),
            wait_lock: Mutex::new(()),
            wake: Condvar::new(),
        }
    }

    /// 标记 writer actor 已返回，唤醒所有 bounded join waiter。
    fn mark_done(&self) {
        self.done.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    /// 在绝对 deadline 内等待 writer actor 结束，避免 shutdown 持锁或无界 sleep。
    fn wait_until(&self, deadline: Instant) -> bool {
        if self.done.load(Ordering::Acquire) {
            return true;
        }
        let Ok(mut lock) = self.wait_lock.lock() else {
            return self.done.load(Ordering::Acquire);
        };
        loop {
            if self.done.load(Ordering::Acquire) {
                return true;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let Ok((next, wait)) = self.wake.wait_timeout(lock, remaining) else {
                return self.done.load(Ordering::Acquire);
            };
            lock = next;
            if wait.timed_out() {
                return self.done.load(Ordering::Acquire);
            }
        }
    }
}

struct WriterJoinState {
    handle: Mutex<Option<thread::JoinHandle<()>>>,
    completion: Arc<WriterCompletion>,
}

impl WriterJoinState {
    /// 创建 writer handle 所有权槽，保证成功启动的 actor 不被立即 detached。
    fn new() -> Self {
        Self {
            handle: Mutex::new(None),
            completion: Arc::new(WriterCompletion::new()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum InitializedBarrierState {
    NotSent,
    Sending,
    Confirmed,
    Failed,
}

/// initialized 的唯一发送/接收线性化门；reader 只能在 writer 完成 write+flush 后观察 Confirmed。
pub(crate) struct InitializedBarrier {
    pub(crate) state: Mutex<InitializedBarrierState>,
    pub(crate) wake: Condvar,
}

/// writer 持有此 guard 覆盖整个 initialized write+flush，避免 ready 在中间窗口越过握手门。
struct InitializedSendGuard<'a> {
    barrier: &'a InitializedBarrier,
    state: Option<MutexGuard<'a, InitializedBarrierState>>,
}

impl InitializedBarrier {
    /// 创建尚未发送 initialized 的握手门，避免 ready 在任何写入前被误判为有效。
    fn new() -> Self {
        Self {
            state: Mutex::new(InitializedBarrierState::NotSent),
            wake: Condvar::new(),
        }
    }

    /// 新 generation 重新打开握手门；调用方保证旧 writer 已经停止使用该 generation。
    fn reset(&self) -> Result<(), AppServerProcessError> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        *state = InitializedBarrierState::NotSent;
        self.wake.notify_all();
        Ok(())
    }

    /// 在 writer 开始 I/O 前占住门，后续 guard 生命周期覆盖 write_all 与 flush。
    fn begin_send(&self) -> Result<InitializedSendGuard<'_>, AppServerProcessError> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        if *state != InitializedBarrierState::NotSent {
            return Err(AppServerProcessError::HandshakeFailed);
        }
        *state = InitializedBarrierState::Sending;
        Ok(InitializedSendGuard {
            barrier: self,
            state: Some(state),
        })
    }

    /// 只有成功完成发送的 initialized 才能使 ready 观察到 Confirmed。
    fn is_confirmed(&self) -> bool {
        self.state
            .lock()
            .is_ok_and(|state| *state == InitializedBarrierState::Confirmed)
    }

    /// ready 必须等待同一把门；因此 Sending 期间会短暂阻塞，Failed 期间永远拒绝。
    fn claim_ready(&self, claimed: &AtomicBool, closed: &AtomicBool) -> bool {
        let Ok(state) = self.state.lock() else {
            return false;
        };
        if closed.load(Ordering::Acquire) || *state != InitializedBarrierState::Confirmed {
            return false;
        }
        claimed
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }
}

impl InitializedSendGuard<'_> {
    /// 提交成功或失败的最终态后释放门并唤醒等待 ready 的 reader。
    fn complete(mut self, confirmed: bool) {
        if let Some(mut state) = self.state.take() {
            *state = if confirmed {
                InitializedBarrierState::Confirmed
            } else {
                InitializedBarrierState::Failed
            };
            self.barrier.wake.notify_all();
        }
    }
}

impl Drop for InitializedSendGuard<'_> {
    /// 任意未正常提交的异常路径都 fail-closed，避免门永远停留在 Sending。
    fn drop(&mut self) {
        if let Some(mut state) = self.state.take() {
            *state = InitializedBarrierState::Failed;
            self.barrier.wake.notify_all();
        }
    }
}

pub(crate) struct SessionInner {
    generation: u64,
    limits: Limits,
    events: Arc<EventQueue>,
    pub(crate) pending: Mutex<PendingRegistry>,
    pub(crate) outbound_request_ids: Mutex<OutboundRequestLedger>,
    terminal_identities: Mutex<TerminalLedger>,
    writer: WriterHandle,
    write_timeout: Duration,
    pub(crate) closed: Arc<AtomicBool>,
    /// ready promotion 与 terminal close 共用此 mutex，保证 frame 校验到 lifecycle
    /// `mark_ready` 之间不能插入不可见 fault。
    pub(crate) ready_terminal_gate: Mutex<()>,
    event_pump_claimed: AtomicBool,
    /// callback 在 Session 生命周期内不可变，AtomicBool 只负责一次性领取；这样 terminal
    /// cleanup 不依赖可能中毒的 Mutex 才能终止进程树。
    terminal_callback: Option<TerminalCallback>,
    terminal_callback_taken: AtomicBool,
    /// writer 与 reader 共用的 initialized 线性化门，避免 write/flush 中间的 ready 竞态。
    pub(crate) initialized_barrier: InitializedBarrier,
    /// 当前 generation 的一次性 challenge；token 只存在内存中且不进入诊断。
    ready_token_challenge: Mutex<Option<String>>,
    /// 当前 generation 的 token 只用于递归拒绝重放，不对外暴露原值。
    forbidden_ready_tokens: Mutex<HashSet<String>>,
    /// initialized notification 只能成功发送一次，重复发送必须终止握手。
    initialized_sent: AtomicBool,
    /// ready notification 先由 reader 原子占位，再由 lifecycle owner 消费一次。
    ready_notification_claimed: AtomicBool,
    ready_token_consumed: AtomicBool,
    stderr_bytes: AtomicU64,
    stderr_truncated: AtomicBool,
    writer_data_overflow_reported: AtomicBool,
}

pub(crate) struct OutboundRequestLedger {
    pub(crate) next: u64,
}

struct TerminalLedger {
    by_thread: HashMap<String, TerminalCursor>,
}

struct TerminalCursor {
    turn_id: String,
    thread_revision: u64,
}

impl SessionInner {
    /// Reader 先占用唯一 ready 槽位，防止多个 ready 在 lifecycle owner 处理前排队。
    fn claim_ready_notification(&self) -> bool {
        self.initialized_barrier
            .claim_ready(&self.ready_notification_claimed, &self.closed)
    }
}

impl OutboundRequestLedger {
    /// 为 c: request 分配连接内永不复用的 ID，达到整数边界时轮换 session。
    pub(crate) fn new() -> Self {
        Self { next: 1 }
    }

    /// 单调分配 outbound ID；u64 溢出前关闭 generation，避免为永久不复用
    /// 维护随请求总量增长的 seen 集合。
    pub(crate) fn allocate(&mut self) -> Result<String, AppServerProcessError> {
        let number = self.next;
        self.next = self
            .next
            .checked_add(1)
            .ok_or(AppServerProcessError::RequestLedgerExhausted)?;
        Ok(format!("c:rpc-{number}"))
    }
}

impl Session {
    /// 启动 reader/writer/stderr 三条单向线程，并把终态直接转发给进程树 owner。
    pub(crate) fn from_io_with_terminal<R, W, E>(
        reader: R,
        writer: W,
        stderr: E,
        generation: u64,
        limits: Limits,
        terminal_callback: Option<TerminalCallback>,
        write_timeout: Duration,
    ) -> Result<Self, AppServerProcessError>
    where
        R: Read + Send + 'static,
        W: Write + Send + 'static,
        E: Read + Send + 'static,
    {
        Self::from_io_with_terminal_timeout(
            reader,
            writer,
            stderr,
            generation,
            limits,
            terminal_callback,
            write_timeout,
        )
    }

    /// 用一个显式 write deadline 构造全部 pump，让超时 owner 保持在 Session，避免
    /// transport 实现各自建立不可合并的等待窗口。
    fn from_io_with_terminal_timeout<R, W, E>(
        reader: R,
        writer: W,
        stderr: E,
        generation: u64,
        limits: Limits,
        terminal_callback: Option<TerminalCallback>,
        write_timeout: Duration,
    ) -> Result<Self, AppServerProcessError>
    where
        R: Read + Send + 'static,
        W: Write + Send + 'static,
        E: Read + Send + 'static,
    {
        // 第一阶段先验证全部容量预算，再创建有界队列与共享账本，避免任一 pump 拥有独立的无界状态。
        limits.validate()?;
        let events = Arc::new(EventQueue::new(
            limits.inbound_queue_frames,
            limits.max_frame_bytes,
        ));
        let closed = Arc::new(AtomicBool::new(false));
        let (control, control_receiver) = mpsc::sync_channel(CONTROL_QUEUE_CAPACITY);
        let (data, data_receiver) = mpsc::sync_channel(limits.outbound_queue_frames);
        let control_queued_bytes = Arc::new(AtomicUsize::new(0));
        let data_queued_bytes = Arc::new(AtomicUsize::new(0));
        let writer_handle = WriterHandle::new(
            control,
            data,
            Arc::clone(&closed),
            control_queued_bytes,
            data_queued_bytes,
            control_queue_byte_budget(limits.max_frame_bytes),
            MAX_WRITER_DATA_QUEUE_BYTES,
        );
        let max_active_requests = limits
            .max_pending_requests
            .min(limits.max_in_flight_requests);
        let pending = PendingRegistry::new(max_active_requests, limits.max_tombstones)?;
        let writer_join = Arc::new(WriterJoinState::new());
        let inner = Arc::new(SessionInner {
            generation,
            limits: limits.clone(),
            events: Arc::clone(&events),
            pending: Mutex::new(pending),
            outbound_request_ids: Mutex::new(OutboundRequestLedger::new()),
            terminal_identities: Mutex::new(TerminalLedger {
                by_thread: HashMap::with_capacity(MAX_TERMINAL_IDENTITIES),
            }),
            writer: writer_handle,
            write_timeout,
            closed: Arc::clone(&closed),
            ready_terminal_gate: Mutex::new(()),
            event_pump_claimed: AtomicBool::new(false),
            terminal_callback,
            terminal_callback_taken: AtomicBool::new(false),
            initialized_barrier: InitializedBarrier::new(),
            ready_token_challenge: Mutex::new(None),
            forbidden_ready_tokens: Mutex::new(HashSet::new()),
            initialized_sent: AtomicBool::new(false),
            ready_notification_claimed: AtomicBool::new(false),
            ready_token_consumed: AtomicBool::new(false),
            stderr_bytes: AtomicU64::new(0),
            stderr_truncated: AtomicBool::new(false),
            writer_data_overflow_reported: AtomicBool::new(false),
        });
        // Writer 先启动并立即登记 join handle；后续 reader 启动失败时才能在同一失败路径可靠收口。
        let writer_inner = Arc::clone(&inner);
        let writer_completion = Arc::clone(&writer_join.completion);
        let writer_thread = thread::Builder::new()
            .name("ja-sidecar-writer".to_owned())
            .spawn(move || {
                writer_loop(writer, control_receiver, data_receiver, writer_inner);
                writer_completion.mark_done();
            })
            .map_err(|_| {
                fail_closed(&inner);
                AppServerProcessError::Spawn
            })?;
        match writer_join.handle.lock() {
            Ok(mut handle) => *handle = Some(writer_thread),
            Err(_) => {
                // 新建 owner 槽理论上不会中毒；一旦运行环境破坏该前提，立即关闭
                // writer 并同步 join 局部 handle，不能把它静默 detach。
                fail_closed(&inner);
                let _ = writer_thread.join();
                return Err(AppServerProcessError::ProtocolFault);
            }
        }
        // Reader 或 stderr pump 失败时先 fail-closed 拒绝 pending，再等待 writer 退出，避免返回后遗留后台线程。
        if let Err(error) = spawn_reader(reader, Arc::clone(&inner)) {
            fail_closed(&inner);
            if join_writer_state_until(
                &writer_join,
                Instant::now()
                    .checked_add(MAX_WRITER_JOIN_GRACE)
                    .unwrap_or_else(Instant::now),
            )
            .is_err()
            {
                std::process::abort();
            }
            return Err(error);
        }
        if let Err(error) = spawn_stderr_reader(stderr, Arc::clone(&inner)) {
            fail_closed(&inner);
            if join_writer_state_until(
                &writer_join,
                Instant::now()
                    .checked_add(MAX_WRITER_JOIN_GRACE)
                    .unwrap_or_else(Instant::now),
            )
            .is_err()
            {
                std::process::abort();
            }
            return Err(error);
        }
        // 三个 pump 均已建立后才发布 Session，调用方不会观察到缺少 reader 或 cleanup handle 的半初始化对象。
        Ok(Self { inner, writer_join })
    }

    /// Request 只供 crate 内 supervisor/client 编排；公开调用必须经过方法闭集与
    /// generation 校验，避免直接持有 Session 绕过准入。
    pub(crate) fn request(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        self.request_inner(method, params, timeout, None)
    }

    /// 在线性化准入锁内完成 pending 注册与 writer 入队；该入口保持 crate 私有，
    /// 防止外部持 Session 绕过 shutdown fence。
    pub(crate) fn request_with_gate(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
        gate: &Mutex<bool>,
    ) -> Result<RpcFrame, AppServerProcessError> {
        self.request_inner(method, params, timeout, Some(gate))
    }

    /// 共用 request 编码、pending 注册和关闭准入约束，等待路径只接收对应 response。
    fn request_inner(
        &self,
        method: &str,
        params: Value,
        timeout: Duration,
        admission: Option<&Mutex<bool>>,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let request_timeout_limit =
            Duration::from_millis(self.inner.limits.request_deadline_ms).min(MAX_OPERATION_TIMEOUT);
        if timeout > request_timeout_limit {
            return Err(AppServerProcessError::InvalidTimeout);
        }
        let admission_guard = if let Some(gate) = admission {
            let guard = match gate.lock() {
                Ok(guard) => guard,
                Err(_) => {
                    fail_closed(&self.inner);
                    return Err(AppServerProcessError::ShuttingDown);
                }
            };
            if *guard {
                return Err(AppServerProcessError::ShuttingDown);
            }
            Some(guard)
        } else {
            None
        };
        if self.inner.closed.load(Ordering::Acquire) {
            return Err(AppServerProcessError::SessionClosed);
        }
        // 在分配不可回退的 request ID 前拒绝 challenge marker；只有两个精确握手
        // notification 路径允许携带 token。
        if contains_forbidden_ready_token(&params, &self.inner.forbidden_ready_tokens)
            .inspect_err(|_| fail_closed(&self.inner))?
        {
            return Err(AppServerProcessError::HandshakeFailed);
        }
        let id = {
            let mut ledger = self.inner.outbound_request_ids.lock().map_err(|_| {
                fail_closed(&self.inner);
                AppServerProcessError::ProtocolFault
            })?;
            match ledger.allocate() {
                Ok(id) => id,
                Err(error) => {
                    fail_closed(&self.inner);
                    return Err(error);
                }
            }
        };
        let frame = RpcFrame::client_request(id.clone(), method.to_owned(), params)?;
        // 只有完整编码并成功送入 writer 后才消耗 initialized 一次性状态，避免本地编码失败让握手永久卡死。
        let encoded = match frame.encode(self.inner.limits.max_frame_bytes) {
            Ok(encoded) => encoded,
            Err(error) => {
                if method == "runtime/initialized" {
                    self.inner.initialized_sent.store(false, Ordering::Release);
                }
                return Err(error.into());
            }
        };
        // Deadline 在 pending 注册和入队前开始；后续阶段不得为同一 request 新建超时窗口。
        let deadline = deadline_after(timeout)?;
        let receiver = {
            let pending = self.inner.pending.lock();
            let mut pending = match pending {
                Ok(pending) => pending,
                Err(error) => {
                    // 先释放 PoisonError 持有的 guard，再执行会再次触及 pending 的
                    // fail-closed cleanup，避免异常路径自锁。
                    drop(error);
                    fail_closed(&self.inner);
                    return Err(AppServerProcessError::ProtocolFault);
                }
            };
            pending.register(id, deadline)?
        };
        let priority = if method == "runtime/shutdown" || method.starts_with("runtime/") {
            EventPriority::Control
        } else {
            EventPriority::Data
        };
        if let Err(error) = self.inner.writer.send(encoded, priority) {
            handle_writer_error(&self.inner, &error);
            let pending = self.inner.pending.lock();
            match pending {
                Ok(mut pending) => {
                    let _ = pending.cancel(frame.id());
                }
                Err(poisoned) => {
                    drop(poisoned);
                    fail_closed(&self.inner);
                }
            }
            drop(admission_guard);
            return Err(error);
        }
        // 只串行化 admission，response 等待仍保持并发，避免持锁跨越外部响应时间。
        drop(admission_guard);
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            let pending = self.inner.pending.lock();
            let mut pending = match pending {
                Ok(pending) => pending,
                Err(error) => {
                    drop(error);
                    fail_closed(&self.inner);
                    return Err(AppServerProcessError::ProtocolFault);
                }
            };
            pending.expire(Instant::now());
            return Err(AppServerProcessError::DeadlineExceeded);
        }
        match receiver.recv_timeout(remaining) {
            Ok(result) => result,
            Err(RecvTimeoutError::Disconnected) => Err(AppServerProcessError::SessionClosed),
            Err(RecvTimeoutError::Timeout) => {
                let pending = self.inner.pending.lock();
                let mut pending = match pending {
                    Ok(pending) => pending,
                    Err(error) => {
                        drop(error);
                        fail_closed(&self.inner);
                        return Err(AppServerProcessError::ProtocolFault);
                    }
                };
                pending.expire(Instant::now());
                Err(AppServerProcessError::DeadlineExceeded)
            }
        }
    }

    /// Notification 只供 crate 内初始化握手使用；拒绝其它 method，避免测试探针
    /// 演化成第二条通用 outbound 通道。
    pub(crate) fn notify(&self, method: &str, params: Value) -> Result<(), AppServerProcessError> {
        if method != "runtime/initialized" {
            return Err(AppServerProcessError::ProtocolFault);
        }
        if self.inner.closed.load(Ordering::Acquire) {
            return Err(AppServerProcessError::SessionClosed);
        }
        if method == "runtime/initialized" {
            self.validate_initialized_params(&params)?;
            if self
                .inner
                .initialized_sent
                .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
                .is_err()
            {
                return Err(AppServerProcessError::HandshakeFailed);
            }
            self.inner
                .ready_token_consumed
                .store(false, Ordering::Release);
        }
        let frame = match RpcFrame::notification(method.to_owned(), params) {
            Ok(frame) => frame,
            Err(error) => {
                if method == "runtime/initialized" {
                    self.inner.initialized_sent.store(false, Ordering::Release);
                }
                return Err(error.into());
            }
        };
        // 只有完整编码并成功送入 writer 后才消耗 initialized 一次性状态，避免本地编码失败让握手永久卡死。
        let encoded = match frame.encode(self.inner.limits.max_frame_bytes) {
            Ok(encoded) => encoded,
            Err(error) => {
                if method == "runtime/initialized" {
                    self.inner.initialized_sent.store(false, Ordering::Release);
                }
                return Err(error.into());
            }
        };
        let result = self.inner.writer.send(encoded, EventPriority::Control);
        if let Err(error) = &result {
            handle_writer_error(&self.inner, error);
            if method == "runtime/initialized" {
                self.inner.initialized_sent.store(false, Ordering::Release);
            }
        }
        result
    }

    /// 只接受当前 generation 的单字段 initialized challenge，防止重复或嵌套伪造。
    fn validate_initialized_params(&self, params: &Value) -> Result<(), AppServerProcessError> {
        let expected = self
            .inner
            .ready_token_challenge
            .lock()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?
            .clone()
            .ok_or(AppServerProcessError::HandshakeFailed)?;
        let valid = params.as_object().is_some_and(|object| {
            object.len() == 1
                && object.get("readyToken").and_then(Value::as_str) == Some(expected.as_str())
                && valid_ready_token(&expected)
        });
        if valid {
            Ok(())
        } else {
            Err(AppServerProcessError::HandshakeFailed)
        }
    }

    /// 为 supervisor 构造当前 generation 的唯一 initialized DTO，不让 token 成为公开 accessor。
    pub(crate) fn initialized_params(&self) -> Result<Value, AppServerProcessError> {
        let token = self
            .inner
            .ready_token_challenge
            .lock()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?
            .clone()
            .ok_or(AppServerProcessError::HandshakeFailed)?;
        Ok(serde_json::json!({"readyToken": token}))
    }

    /// 安装当前 generation 的 challenge；ready 接受只比较当前 session 的精确 token。
    pub(crate) fn install_ready_token_challenge(
        &self,
        token: String,
    ) -> Result<(), AppServerProcessError> {
        if !valid_ready_token(&token) {
            return Err(AppServerProcessError::HandshakeFailed);
        }
        let mut forbidden = self
            .inner
            .forbidden_ready_tokens
            .lock()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        let mut challenge = self
            .inner
            .ready_token_challenge
            .lock()
            .map_err(|_| AppServerProcessError::HandshakeFailed)?;
        forbidden.clear();
        // 不保留有限历史 raw token：ready 接受必须精确等于当前 session challenge，
        // raw decoder 也以同一精确集合审计未知扩展，避免误伤合法 32-hex 业务 ID。
        forbidden.insert(token.clone());
        *challenge = Some(token);
        drop(challenge);
        drop(forbidden);
        self.inner.initialized_sent.store(false, Ordering::Release);
        self.inner.initialized_barrier.reset()?;
        self.inner
            .ready_notification_claimed
            .store(false, Ordering::Release);
        self.inner
            .ready_token_consumed
            .store(false, Ordering::Release);
        Ok(())
    }

    /// 在线性化 gate 内完成 ready 校验和 lifecycle 提交，防止 terminal fault
    /// 在两步之间把 supervisor 留在 Ready。
    pub(crate) fn with_ready_promotion<F>(
        &self,
        frame: &RpcFrame,
        promote: F,
    ) -> Result<(), AppServerProcessError>
    where
        F: FnOnce() -> Result<(), AppServerProcessError>,
    {
        let ready_gate = self.inner.ready_terminal_gate.lock();
        let _gate = match ready_gate {
            Ok(gate) => gate,
            Err(error) => {
                // PoisonError 自身持有 gate guard；必须先释放再进入 terminal cleanup，
                // 否则 fail_closed 会在同一 Mutex 上自锁。
                drop(error);
                fail_closed(&self.inner);
                return Err(AppServerProcessError::ProtocolFault);
            }
        };
        if self.inner.closed.load(Ordering::Acquire) {
            return Err(AppServerProcessError::SessionClosed);
        }
        if !self.ready_after_initialized_barrier_locked(frame) {
            return Err(AppServerProcessError::HandshakeFailed);
        }
        promote()
    }

    /// 在已持有 ready/terminal gate 时执行不再可被 fault 打断的 token 晋级。
    pub(crate) fn ready_after_initialized_barrier_locked(&self, frame: &RpcFrame) -> bool {
        let Some(params) = frame.params() else {
            return false;
        };
        if self.inner.closed.load(Ordering::Acquire)
            || !self.inner.initialized_barrier.is_confirmed()
        {
            return false;
        }
        if !self
            .inner
            .ready_notification_claimed
            .load(Ordering::Acquire)
        {
            return false;
        }
        let Ok(challenge) = self.inner.ready_token_challenge.lock() else {
            return false;
        };
        let Some(expected) = challenge.clone() else {
            return false;
        };
        if params.get("readyToken").and_then(Value::as_str) != Some(expected.as_str())
            || !valid_ready_token(&expected)
            || !ready_params_are_safe(params, &expected, &self.inner.forbidden_ready_tokens)
        {
            return false;
        }
        self.inner
            .ready_token_consumed
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }

    /// 领取连接唯一的事件消费权，防止 Session clone 产生多个竞争 reducer。
    pub(crate) fn take_event_pump(&self) -> Result<EventPump, AppServerProcessError> {
        if self
            .inner
            .event_pump_claimed
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_err()
        {
            return Err(AppServerProcessError::InvalidState);
        }
        Ok(EventPump {
            inner: Arc::clone(&self.inner),
        })
    }

    /// 把 monitor 的退出事实送入同一个控制队列，保证 supervisor 可统一 poll。
    pub(crate) fn report_process_exit(&self, code: Option<i32>) {
        push_event(
            &self.inner,
            SessionEvent::ProcessExited {
                generation: self.inner.generation,
                code,
            },
            EventPriority::Control,
            QueueKind::Control,
        );
        wire::fail_closed_with_reason(&self.inner, TerminalReason::ProcessExited);
    }

    /// 报告 monitor 无法取得稳定退出码，并释放 pending waiters，避免 wait 失败留下悬挂请求。
    pub(crate) fn report_process_fault(&self) {
        push_event(
            &self.inner,
            SessionEvent::ProtocolFault(codec::CodecError::Io),
            EventPriority::Control,
            QueueKind::Control,
        );
        fail_closed(&self.inner);
    }

    /// 让 supervisor 把 writer join 纳入同一个 shutdown deadline。
    pub(crate) fn close_until(&self, deadline: Instant) -> Result<(), AppServerProcessError> {
        wire::fail_closed_with_reason(&self.inner, TerminalReason::Closed);
        let result = join_writer_state_until(&self.writer_join, deadline);
        if result.is_err() {
            push_event(
                &self.inner,
                SessionEvent::ProtocolFault(codec::CodecError::Io),
                EventPriority::Control,
                QueueKind::Control,
            );
        }
        result
    }

    /// 生命周期 owner 丢弃 session 前清除 callback，避免 stale client 触发旧 generation 路由。
    pub(crate) fn detach_terminal_callback(&self) {
        self.inner
            .terminal_callback_taken
            .store(true, Ordering::Release);
    }
}

/// 递归检查普通 payload 的字段和值，避免当前 challenge 在扩展字段中重放。
fn contains_forbidden_ready_token(
    value: &Value,
    forbidden: &Mutex<HashSet<String>>,
) -> Result<bool, AppServerProcessError> {
    let forbidden = forbidden
        .lock()
        .map_err(|_| AppServerProcessError::ProtocolFault)?;
    Ok(contains_forbidden_ready_token_with_set(value, &forbidden))
}

/// 在已持有当前 token 集合时递归执行 marker 检查，不返回原始 token 诊断。
fn contains_forbidden_ready_token_with_set(value: &Value, forbidden: &HashSet<String>) -> bool {
    match value {
        Value::Object(object) => object.iter().any(|(key, child)| {
            codec::is_ready_token_key(key)
                || forbidden.contains(key)
                || contains_forbidden_ready_token_with_set(child, forbidden)
        }),
        Value::Array(values) => values
            .iter()
            .any(|child| contains_forbidden_ready_token_with_set(child, forbidden)),
        Value::String(text) => forbidden.contains(text),
        _ => false,
    }
}

/// 验证 ready 的单个 token 例外，同时拒绝其余位置的嵌套 marker/value。
fn ready_params_are_safe(
    params: &Value,
    expected: &str,
    forbidden: &Mutex<HashSet<String>>,
) -> bool {
    let Some(object) = params.as_object() else {
        return false;
    };
    let Ok(forbidden) = forbidden.lock() else {
        return false;
    };
    object.iter().all(|(key, child)| {
        if key == "readyToken" {
            child.as_str() == Some(expected) && valid_ready_token(expected)
        } else {
            !forbidden.contains(key)
                && !codec::is_ready_token_key(key)
                && !contains_forbidden_ready_token_with_set(child, &forbidden)
        }
    })
}

impl Clone for Session {
    /// 只复制 Arc handle，不复制 pending 或创建第二套 reader/writer。
    fn clone(&self) -> Self {
        Self {
            inner: Arc::clone(&self.inner),
            writer_join: Arc::clone(&self.writer_join),
        }
    }
}

impl Drop for Session {
    /// 最后一个 client 丢弃时仍执行终止与 bounded join；无法 join 时 abort，
    /// 这样不会把运行中的 writer JoinHandle 静默 drop 成 detached thread。
    fn drop(&mut self) {
        if Arc::strong_count(&self.writer_join) != 1 {
            return;
        }
        wire::fail_closed_with_reason(&self.inner, TerminalReason::Closed);
        if join_writer_state_until(&self.writer_join, default_writer_join_deadline(&self.inner))
            .is_err()
        {
            std::process::abort();
        }
    }
}

/// 计算默认 close join deadline，限制恶意 write timeout 不能扩张到无界等待。
fn default_writer_join_deadline(inner: &SessionInner) -> Instant {
    let timeout = inner
        .write_timeout
        .min(MAX_OPERATION_TIMEOUT)
        .saturating_add(MAX_WRITER_JOIN_GRACE);
    Instant::now()
        .checked_add(timeout)
        .unwrap_or_else(Instant::now)
}

/// 只在 completion gate 已满足时调用 JoinHandle::join，避免 deadline 后阻塞。
fn join_writer_state_until(
    state: &Arc<WriterJoinState>,
    deadline: Instant,
) -> Result<(), AppServerProcessError> {
    // join owner 锁一直持有到 completion 判定，阻止两个 shutdown caller 竞争取走
    // 同一个 JoinHandle；writer actor 不访问该锁，因此不会形成等待环。
    let mut slot = state
        .handle
        .lock()
        .map_err(|_| AppServerProcessError::ProtocolFault)?;
    let mut handle = slot.take();
    let Some(writer) = handle.take() else {
        return Ok(());
    };
    if writer.thread().id() == thread::current().id() {
        *slot = Some(writer);
        return Err(AppServerProcessError::InvalidState);
    }
    if !state.completion.wait_until(deadline) {
        *slot = Some(writer);
        return Err(AppServerProcessError::ShutdownTimeout);
    }
    drop(slot);
    writer
        .join()
        .map_err(|_| AppServerProcessError::ProtocolFault)
}

/// 把 writer 背压分成可重试 data 溢出与不可恢复 control 故障，避免误杀 session。
fn handle_writer_error(inner: &Arc<SessionInner>, error: &AppServerProcessError) {
    match error {
        AppServerProcessError::QueueFull(QueueKind::Data)
            if !inner
                .writer_data_overflow_reported
                .swap(true, Ordering::AcqRel) =>
        {
            push_event(
                inner,
                SessionEvent::QueueOverflow(QueueKind::Data),
                EventPriority::Control,
                QueueKind::Control,
            );
        }
        AppServerProcessError::QueueFull(QueueKind::Data) => {}
        AppServerProcessError::QueueFull(QueueKind::Control) => {
            push_event(
                inner,
                SessionEvent::QueueFatalOverflow(QueueKind::Control),
                EventPriority::Control,
                QueueKind::Control,
            );
            fail_closed(inner);
        }
        AppServerProcessError::QueueClosed(_) => fail_closed(inner),
        AppServerProcessError::SessionClosed => {}
        _ => {}
    }
}
