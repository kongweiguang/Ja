// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 终端 PTY worker 与关闭期间的资源回收。
//
// 将阻塞读写、resize、child wait 和 JoinHandle accounting 放到独立模块，
// 是为了让 session facade 只负责生命周期状态机，而不是让一个文件同时
// 承担公开 API、事件发布和平台 worker 的所有职责。

use super::{TerminalError, TerminalErrorCode, TerminalEventKind, TerminalRuntime, TerminalSize};
use portable_pty::{Child, PtySize};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const PTY_DRAIN_GRACE: Duration = Duration::from_millis(100);
const WORKER_FINISH_POLL: Duration = Duration::from_millis(1);
/// worker 预留数集中定义，确保启动失败的补偿计数与实际 worker 类型保持一致。
pub(crate) const WORKER_COUNT: usize = 4;

/// portable-pty 的 resize DTO 转换集中在一个平台无关函数，避免 command 层重复映射。
pub(crate) fn to_pty_size(size: TerminalSize) -> PtySize {
    PtySize {
        rows: size.rows,
        cols: size.cols,
        pixel_width: size.pixel_width,
        pixel_height: size.pixel_height,
    }
}

/// 启动四个 worker；每个 worker 绑定自己的资源，失败时统一触发 bounded cleanup。
pub(crate) fn spawn_workers(
    runtime: &Arc<TerminalRuntime>,
    reader: Box<dyn Read + Send>,
    writer: Box<dyn Write + Send>,
    child: Box<dyn Child + Send + Sync>,
) -> Result<(), TerminalError> {
    let reader_runtime = runtime.clone();
    let writer_runtime = runtime.clone();
    let resize_runtime = runtime.clone();
    let child_runtime = runtime.clone();
    let reader_result = thread::Builder::new()
        .name("ja-terminal-reader".to_owned())
        .spawn(move || reader_worker(reader_runtime, reader));
    match reader_result {
        Ok(handle) => {
            runtime.workers.register(handle)?;
        }
        Err(_) => {
            // spawn 前已预留四个槽位；失败槽位及后续未启动槽位必须全部补偿，close 才不会
            // 等待不存在的 worker。
            runtime.workers.complete_slots(WORKER_COUNT);
            return Err(TerminalError::new(TerminalErrorCode::SpawnFailed));
        }
    }
    let writer_result = thread::Builder::new()
        .name("ja-terminal-writer".to_owned())
        .spawn(move || writer_worker(writer_runtime, writer));
    match writer_result {
        Ok(handle) => {
            runtime.workers.register(handle)?;
        }
        Err(_) => {
            runtime.workers.complete_slots(WORKER_COUNT - 1);
            return Err(TerminalError::new(TerminalErrorCode::SpawnFailed));
        }
    }
    let resize_result = thread::Builder::new()
        .name("ja-terminal-resize".to_owned())
        .spawn(move || resize_worker(resize_runtime));
    match resize_result {
        Ok(handle) => {
            runtime.workers.register(handle)?;
        }
        Err(_) => {
            runtime.workers.complete_slots(WORKER_COUNT - 2);
            return Err(TerminalError::new(TerminalErrorCode::SpawnFailed));
        }
    }
    let child_result = thread::Builder::new()
        .name("ja-terminal-child".to_owned())
        .spawn(move || child_worker(child_runtime, child));
    match child_result {
        Ok(handle) => {
            runtime.workers.register(handle)?;
        }
        Err(_) => {
            runtime.workers.complete_slots(WORKER_COUNT - 3);
            return Err(TerminalError::new(TerminalErrorCode::SpawnFailed));
        }
    }
    Ok(())
}

/// reader 以固定 buffer 读 raw bytes，跨 chunk 的 UTF-8/ANSI 由前端重组。
fn reader_worker(runtime: Arc<TerminalRuntime>, mut reader: Box<dyn Read + Send>) {
    let _guard = WorkerGuard::new(runtime.workers.clone());
    let mut buffer = vec![0_u8; runtime.limits.max_output_batch_bytes.min(64 * 1024)];
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => {
                if runtime.reader_can_finish() {
                    break;
                }
                thread::sleep(Duration::from_millis(5));
            }
            Ok(count) => {
                if !runtime.publish_output(buffer[..count].to_vec()) {
                    break;
                }
            }
            Err(error) => {
                if runtime.stop.load(Ordering::Acquire) {
                    break;
                }
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::Interrupted | std::io::ErrorKind::WouldBlock
                ) {
                    continue;
                }
                if !runtime.reader_can_finish() {
                    tracing::debug!(error_kind = ?error.kind(), "terminal PTY reader failed");
                    runtime.fail(TerminalErrorCode::PtyFailed);
                }
                break;
            }
        }
    }
    runtime.reader_finished();
}

/// writer 是唯一向 PTY 写入的 worker，保证 input chunks 不会交叉。
fn writer_worker(runtime: Arc<TerminalRuntime>, mut writer: Box<dyn Write + Send>) {
    let _guard = WorkerGuard::new(runtime.workers.clone());
    while let Some(data) = runtime.input.pop() {
        if runtime.stop.load(Ordering::Acquire) {
            break;
        }
        if let Err(error) = writer.write_all(&data).and_then(|_| writer.flush()) {
            if !runtime.stop.load(Ordering::Acquire) {
                tracing::debug!(error_kind = ?error.kind(), "terminal PTY writer failed");
                runtime.fail(TerminalErrorCode::PtyFailed);
            }
            break;
        }
    }
}

/// resize worker 只消费 coalesced latest value，避免 terminal resize storm。
fn resize_worker(runtime: Arc<TerminalRuntime>) {
    let _guard = WorkerGuard::new(runtime.workers.clone());
    while let Some(size) = runtime.resize_queue.pop() {
        if runtime.stop.load(Ordering::Acquire) {
            break;
        }
        let result = match runtime.master.lock() {
            Ok(master) => master
                .as_ref()
                .map(|master| master.resize(to_pty_size(size))),
            Err(error) => {
                drop(error);
                runtime.fail(TerminalErrorCode::PtyFailed);
                break;
            }
        };
        match result {
            Some(Ok(())) => runtime.publish_control(TerminalEventKind::Resized { size }),
            Some(Err(error)) => {
                tracing::debug!(error = %error, "terminal resize failed");
                runtime.fail(TerminalErrorCode::PtyFailed);
                break;
            }
            None => break,
        }
    }
}

/// child worker 以 bounded poll 观察退出，正常/异常退出都经过同一终态路径。
fn child_worker(runtime: Arc<TerminalRuntime>, mut child: Box<dyn Child + Send + Sync>) {
    let _guard = WorkerGuard::new(runtime.workers.clone());
    let deadline = Instant::now()
        .checked_add(runtime.limits.operation_timeout)
        .unwrap_or_else(|| Instant::now() + Duration::from_secs(30));
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                runtime.child_exited(status);
                // child status 可能早于 PTY reader 观察到 EOF；短暂有界 grace 保留末尾 output，
                // 随后显式关闭 master，保证 ConPTY reader 不会因 pipe 持续可读而永久存活。
                thread::sleep(PTY_DRAIN_GRACE);
                runtime.drop_master();
                break;
            }
            Ok(None) => {}
            Err(error) => {
                tracing::debug!(error_kind = ?error.kind(), "terminal child wait failed");
                runtime.fail(TerminalErrorCode::PtyFailed);
                break;
            }
        }
        if runtime.stop.load(Ordering::Acquire) && Instant::now() >= deadline {
            runtime.fail(TerminalErrorCode::WorkerShutdownTimeout);
            break;
        }
        thread::sleep(Duration::from_millis(10));
    }
}

/// 单槽 resize queue；设置新尺寸会覆盖尚未应用的旧尺寸。
pub(crate) struct ResizeQueue {
    pub(crate) state: Mutex<ResizeState>,
    wake: Condvar,
    failed: AtomicBool,
}

pub(crate) struct ResizeState {
    pub(crate) pending: Option<TerminalSize>,
    pub(crate) closed: bool,
}

impl ResizeQueue {
    /// 创建 coalescing queue，而不是为每次窗口像素变化分配消息。
    pub(crate) fn new() -> Self {
        Self {
            state: Mutex::new(ResizeState {
                pending: None,
                closed: false,
            }),
            wake: Condvar::new(),
            failed: AtomicBool::new(false),
        }
    }

    /// 覆盖 pending size，后端只需应用最后一个有效尺寸。
    pub(crate) fn set(&self, size: TerminalSize) -> Result<(), TerminalError> {
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
        state.pending = Some(size);
        self.wake.notify_one();
        Ok(())
    }

    /// resize worker 阻塞取最新尺寸，close 后最终返回 None。
    pub(crate) fn pop(&self) -> Option<TerminalSize> {
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
            if let Some(size) = state.pending.take() {
                return Some(size);
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

    /// 关闭并丢弃旧尺寸，防止 shutdown 之后 worker 重新触碰 master。
    pub(crate) fn close(&self) {
        self.failed.store(true, Ordering::Release);
        self.rebuild_closed_state();
    }

    /// resize poison 后不再应用可能部分更新的尺寸，直接关闭 worker 输入。
    fn fail_closed(&self) {
        self.failed.store(true, Ordering::Release);
        self.rebuild_closed_state();
    }

    /// 原子关闭后丢弃 pending resize，保证 worker 不会在失效 master 上重放。
    fn rebuild_closed_state(&self) {
        self.state.clear_poison();
        if let Ok(mut state) = self.state.lock() {
            state.closed = true;
            state.pending = None;
        }
        self.wake.notify_all();
    }
}

/// worker 数量由 runtime 所有，便于 close 使用 deadline 等待而不 join 自己。
pub(crate) struct WorkerTracker {
    wake: Condvar,
    pub(crate) state: Mutex<WorkerState>,
    pub(crate) handles: Mutex<Vec<JoinHandle<()>>>,
    failed: AtomicBool,
}

pub(crate) struct WorkerState {
    pub(crate) remaining: usize,
}

pub(crate) enum WorkerReap {
    Complete,
    Timeout,
    JoinFailed,
}

impl WorkerTracker {
    /// 预登记四类 worker，spawn 失败路径也可准确扣减。
    pub(crate) fn new(count: usize) -> Self {
        Self {
            wake: Condvar::new(),
            state: Mutex::new(WorkerState { remaining: count }),
            handles: Mutex::new(Vec::with_capacity(count)),
            failed: AtomicBool::new(false),
        }
    }

    /// 将 JoinHandle 留在 runtime owner 中，使超时重试仍能回收同一批 worker，而不会分离活跃 PTY。
    pub(crate) fn register(&self, handle: JoinHandle<()>) -> Result<(), TerminalError> {
        if self.failed.load(Ordering::Acquire) {
            self.retain_failed_handle(handle);
            return Err(TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout));
        }
        match self.handles.lock() {
            Ok(mut handles) => {
                handles.push(handle);
                Ok(())
            }
            Err(error) => {
                drop(error);
                self.mark_failed();
                self.retain_failed_handle(handle);
                Err(TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout))
            }
        }
    }

    /// worker 完成时通知 close waiter。
    pub(crate) fn done(&self) {
        let mut state = match self.state.lock() {
            Ok(state) => state,
            Err(_) => {
                self.mark_failed();
                return;
            }
        };
        if state.remaining != 0 {
            state.remaining -= 1;
            // predicate 更新和通知共用同一把锁，避免 waiter 在检查与阻塞之间丢失完成信号。
            self.wake.notify_all();
        }
    }

    /// 完成尚未启动的 worker 槽位，避免 thread spawn 失败留下虚假计数。
    pub(crate) fn complete_slots(&self, count: usize) {
        for _ in 0..count {
            self.done();
        }
    }

    /// 在绝对 deadline 内等待所有 worker 退出。
    pub(crate) fn wait_until(&self, deadline: Instant) -> WorkerReap {
        self.wait_until_with(deadline, || {})
    }

    /// 以模块私有观察端口执行等待算法，测试可确定性覆盖 check-then-wait 竞态，生产调用保持零额外状态。
    pub(crate) fn wait_until_with(
        &self,
        deadline: Instant,
        mut before_wait: impl FnMut(),
    ) -> WorkerReap {
        if self.failed.load(Ordering::Acquire) {
            return self.reap_failed_handles(deadline);
        }
        let mut guard = match self.state.lock() {
            Ok(state) => state,
            Err(error) => {
                drop(error);
                self.mark_failed();
                return self.reap_failed_handles(deadline);
            }
        };
        while guard.remaining != 0 {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return WorkerReap::Timeout;
            }
            // 观察端口在仍持有 predicate 锁时执行；生产传入空闭包，测试据此固定竞态交错。
            before_wait();
            let (next, result) = match self.wake.wait_timeout(guard, remaining) {
                Ok(result) => result,
                Err(error) => {
                    drop(error);
                    self.mark_failed();
                    return self.reap_failed_handles(deadline);
                }
            };
            guard = next;
            if result.timed_out() {
                // deadline 唤醒后仍重新检查 predicate，因为 worker 可能与 timeout 同时完成。
                continue;
            }
        }

        // `done` 发生在 worker closure 返回前，因此 join 前只做有界完成轮询，绝不越过调用方 deadline。
        drop(guard);
        loop {
            let all_finished = {
                let handles = match self.handles.lock() {
                    Ok(handles) => handles,
                    Err(error) => {
                        drop(error);
                        self.mark_failed();
                        return self.reap_failed_handles(deadline);
                    }
                };
                handles.iter().all(JoinHandle::is_finished)
            };
            if all_finished {
                break;
            }
            let state = match self.state.lock() {
                Ok(state) => state,
                Err(error) => {
                    drop(error);
                    self.mark_failed();
                    return self.reap_failed_handles(deadline);
                }
            };
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return WorkerReap::Timeout;
            }
            let poll = remaining.min(WORKER_FINISH_POLL);
            if self.wake.wait_timeout(state, poll).is_err() {
                self.mark_failed();
                return self.reap_failed_handles(deadline);
            }
        }

        let handles = match self.handles.lock() {
            Ok(mut handles) => std::mem::take(&mut *handles),
            Err(error) => {
                drop(error);
                self.mark_failed();
                return self.reap_failed_handles(deadline);
            }
        };
        if join_worker_handles(handles, |handle| handle.join().is_ok()) {
            WorkerReap::Complete
        } else {
            WorkerReap::JoinFailed
        }
    }

    /// 只有计数归零且句柄全部消费后才允许回收 generation，避免复用仍有后台线程的 session。
    pub(crate) fn is_reaped(&self) -> bool {
        if self.failed.load(Ordering::Acquire) {
            return false;
        }
        let remaining = match self.state.lock() {
            Ok(state) => state.remaining,
            Err(_) => {
                self.mark_failed();
                return false;
            }
        };
        let handles_empty = match self.handles.lock() {
            Ok(handles) => handles.is_empty(),
            Err(_) => {
                self.mark_failed();
                return false;
            }
        };
        remaining == 0 && handles_empty
    }

    /// accounting 或 handle ledger poison 后无法证明所有 worker 已回收；
    /// 永久标记失败并唤醒 close waiter，禁止 generation 被重用。
    fn mark_failed(&self) {
        self.failed.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    /// ledger poison 后新创建的句柄仍必须归入清理 owner；此路径只为 join
    /// 重建容器，不恢复 accounting，因此 caller 仍收到关闭失败。
    fn retain_failed_handle(&self, handle: JoinHandle<()>) {
        self.handles.clear_poison();
        if let Ok(mut handles) = self.handles.lock() {
            handles.push(handle);
        }
    }

    /// close 线程在原 deadline 内回收失效 ledger 中所有句柄；即使 join
    /// 全部完成也返回 `JoinFailed`，因为 remaining 不变量已无法重建。
    fn reap_failed_handles(&self, deadline: Instant) -> WorkerReap {
        self.handles.clear_poison();
        loop {
            let all_finished = match self.handles.lock() {
                Ok(handles) => handles.iter().all(JoinHandle::is_finished),
                Err(error) => {
                    drop(error);
                    self.handles.clear_poison();
                    false
                }
            };
            if all_finished {
                let handles = match self.handles.lock() {
                    Ok(mut handles) => std::mem::take(&mut *handles),
                    Err(_) => return WorkerReap::JoinFailed,
                };
                let _ = join_worker_handles(handles, |handle| handle.join().is_ok());
                return WorkerReap::JoinFailed;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return WorkerReap::JoinFailed;
            }
            thread::sleep(remaining.min(WORKER_FINISH_POLL));
        }
    }
}

/// 顺序消费全部 JoinHandle，即使前一个 worker panic 也不能让后续句柄被直接丢弃。
pub(crate) fn join_worker_handles(
    handles: Vec<JoinHandle<()>>,
    mut join: impl FnMut(JoinHandle<()>) -> bool,
) -> bool {
    let mut all_joined = true;
    for handle in handles {
        if !join(handle) {
            all_joined = false;
        }
    }
    all_joined
}

/// RAII worker guard，确保 panic/early return 也会释放 tracker 计数。
pub(crate) struct WorkerGuard {
    tracker: Arc<WorkerTracker>,
}

impl WorkerGuard {
    /// 绑定当前 worker 的 completion accounting。
    pub(crate) fn new(tracker: Arc<WorkerTracker>) -> Self {
        Self { tracker }
    }
}

impl Drop for WorkerGuard {
    /// worker 退出时唤醒 bounded shutdown waiter。
    fn drop(&mut self) {
        self.tracker.done();
    }
}
