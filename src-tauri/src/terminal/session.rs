// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 终端 session supervisor 与 worker ownership。
//
// 每个 session 把 child、master、reader、writer、resize worker 和 byte queues
// 绑定到同一个 generation。关闭先发终态、再终止进程树并等待 worker，防止 late
// output 重新污染已经关闭的 UI terminal。

use super::error::{TerminalError, TerminalErrorCode};
use super::model::{
    CloseReason, LaunchRequest, ShellProfile, TerminalEvent, TerminalEventKind, TerminalId,
    TerminalSize,
};
use super::policy::TerminalPolicy;
use super::process::{self, ProcessTree};
use super::queue::{EventQueue, InputQueue};
use portable_pty::{ChildKiller, CommandBuilder, MasterPty, native_pty_system};
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

#[path = "session_workers.rs"]
pub(crate) mod session_workers;

use session_workers::{ResizeQueue, WorkerReap, WorkerTracker};

/// 管理 workspace 下的多个用户终端，并为每个 id 保留唯一 owner。
#[derive(Clone)]
pub struct TerminalSupervisor {
    pub(crate) inner: Arc<SupervisorInner>,
}

pub(crate) struct SupervisorInner {
    policy: TerminalPolicy,
    pub(crate) sessions: Mutex<HashMap<TerminalId, Arc<TerminalRuntime>>>,
    pub(crate) failed: AtomicBool,
}

impl TerminalSupervisor {
    /// 由 host 以一个已验证 workspace policy 创建 supervisor。
    pub fn new(policy: TerminalPolicy) -> Self {
        Self {
            inner: Arc::new(SupervisorInner {
                policy,
                sessions: Mutex::new(HashMap::new()),
                failed: AtomicBool::new(false),
            }),
        }
    }

    /// 返回 owner session 数量，使 workspace reconfiguration 与 shutdown 能拒绝遗弃存活 PTY tree。
    pub fn active_count(&self) -> usize {
        match self.lock_sessions() {
            Ok(sessions) => sessions.len(),
            Err(_) => self.inner.policy.limits().max_sessions.max(1),
        }
    }

    /// 启动受控 shell；同一 supervisor 的 session 数受 policy 上限保护。
    pub fn open(&self, request: LaunchRequest) -> Result<SessionHandle, TerminalError> {
        let mut sessions = self.lock_sessions()?;
        // SessionHandle 可在不持有 supervisor 时自行关闭；这里回收 terminal generation，
        // 避免已关闭 UI Tab 永久占用全局 session quota。
        sessions.retain(|_, runtime| !runtime.is_reclaimable());
        if sessions.len() >= self.inner.policy.limits().max_sessions {
            return Err(TerminalError::new(TerminalErrorCode::SessionLimit));
        }
        let id = TerminalId::new();
        let generation = 1;
        let runtime = TerminalRuntime::spawn(id, generation, self.inner.policy.clone(), request)?;
        sessions.insert(id, runtime.clone());
        Ok(SessionHandle {
            runtime,
            generation,
        })
    }

    /// 以 generation 获取现有 owner，拒绝旧 UI 持有的 stale token。
    pub fn get(&self, id: TerminalId, generation: u64) -> Result<SessionHandle, TerminalError> {
        let sessions = self.lock_sessions()?;
        let runtime = sessions
            .get(&id)
            .cloned()
            .ok_or(TerminalError::new(TerminalErrorCode::SessionNotFound))?;
        runtime.validate_token(generation)?;
        Ok(SessionHandle {
            runtime,
            generation,
        })
    }

    /// 删除唯一 owner 后执行 bounded close，避免关闭完成前被新请求复用。
    pub fn close(
        &self,
        id: TerminalId,
        generation: u64,
        reason: CloseReason,
    ) -> Result<(), TerminalError> {
        let runtime = {
            let sessions = self.lock_sessions()?;
            let runtime = sessions
                .get(&id)
                .cloned()
                .ok_or(TerminalError::new(TerminalErrorCode::SessionNotFound))?;
            runtime.validate_token(generation)?;
            runtime
        };
        let result = runtime.close(reason);
        if result.is_ok() {
            let mut sessions = self.lock_sessions()?;
            if sessions
                .get(&id)
                .is_some_and(|candidate| Arc::ptr_eq(candidate, &runtime))
            {
                sessions.remove(&id);
            }
        }
        result
    }

    /// 应用退出时按同一个绝对 deadline 收口全部 terminal process trees。
    pub fn shutdown_until(&self, deadline: Instant) -> Result<(), TerminalError> {
        let runtimes = {
            let sessions = self.lock_sessions()?;
            sessions
                .iter()
                .map(|(id, runtime)| (*id, runtime.clone()))
                .collect::<Vec<_>>()
        };
        let mut failed = false;
        for (id, runtime) in runtimes {
            if runtime.close_until(CloseReason::Shutdown, deadline).is_ok() {
                let mut sessions = self.lock_sessions()?;
                if sessions
                    .get(&id)
                    .is_some_and(|candidate| Arc::ptr_eq(candidate, &runtime))
                {
                    sessions.remove(&id);
                }
            } else {
                failed = true;
            }
        }
        if failed {
            Err(TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout))
        } else {
            Ok(())
        }
    }

    /// session map 的 poison 可能使 quota 与 owner 集合不一致；首次检测后
    /// 永久关闭 supervisor admission，并进入唯一的 owner 重建路径。
    fn lock_sessions(
        &self,
    ) -> Result<MutexGuard<'_, HashMap<TerminalId, Arc<TerminalRuntime>>>, TerminalError> {
        if self.inner.failed.load(Ordering::Acquire) {
            return Err(TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout));
        }
        match self.inner.sessions.lock() {
            Ok(sessions) => Ok(sessions),
            Err(error) => {
                drop(error);
                self.fail_closed_sessions();
                Err(TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout))
            }
        }
    }

    /// 失败关闭只在原子栅栏后清除 poison，将所有可见 owner
    /// drain 出 map 并逐个有界关闭；旧 map 不会再用于查询或 quota 判断。
    fn fail_closed_sessions(&self) {
        if self.inner.failed.swap(true, Ordering::AcqRel) {
            return;
        }
        self.inner.sessions.clear_poison();
        let runtimes = match self.inner.sessions.lock() {
            Ok(mut sessions) => sessions
                .drain()
                .map(|(_, runtime)| runtime)
                .collect::<Vec<_>>(),
            Err(_) => Vec::new(),
        };
        let deadline = Instant::now()
            .checked_add(self.inner.policy.limits().operation_timeout)
            .unwrap_or_else(|| Instant::now() + Duration::from_secs(30));
        for runtime in runtimes {
            let _ = runtime.close_until(CloseReason::Shutdown, deadline);
        }
    }
}

impl Drop for TerminalSupervisor {
    /// 最后一个 supervisor owner 执行一次有界 shutdown，禁止 map 无提示地丢弃存活 PTY worker。
    fn drop(&mut self) {
        if Arc::strong_count(&self.inner) == 1 {
            let deadline = Instant::now()
                .checked_add(self.inner.policy.limits().operation_timeout)
                .unwrap_or_else(|| Instant::now() + Duration::from_secs(30));
            let _ = self.shutdown_until(deadline);
        }
    }
}

/// 一个 generation 的终端句柄；clone 不会创建第二个 owner，只共享同一 bounded runtime。
#[derive(Clone)]
pub struct SessionHandle {
    pub(crate) runtime: Arc<TerminalRuntime>,
    pub(crate) generation: u64,
}

impl SessionHandle {
    /// 返回不透明 session id，前端可持久化但不能自行生成合法 owner。
    pub fn id(&self) -> TerminalId {
        self.runtime.id
    }

    /// 返回用于拒绝 late event/request 的 generation。
    pub fn generation(&self) -> u64 {
        self.generation
    }

    /// 返回 generation 打开时冻结的 allow-listed profile，防止原生路径 quoting 随后续 UI 变化漂移。
    pub(crate) fn profile(&self) -> ShellProfile {
        self.runtime.profile
    }

    /// 在 timeout 到期前向 single writer queue 追加原始 input bytes。
    pub fn send_input(&self, data: &[u8], timeout: Duration) -> Result<(), TerminalError> {
        let deadline = Instant::now()
            .checked_add(timeout)
            .ok_or(TerminalError::new(TerminalErrorCode::DeadlineExceeded))?;
        self.send_input_until(data, deadline)
    }

    /// 绝对 deadline 版本供 Tauri command 和 shutdown 编排复用。
    pub fn send_input_until(&self, data: &[u8], deadline: Instant) -> Result<(), TerminalError> {
        self.runtime.validate_open(self.generation)?;
        if Instant::now() >= deadline {
            return Err(TerminalError::new(TerminalErrorCode::DeadlineExceeded));
        }
        let max = self.runtime.limits.max_input_chunk_bytes;
        if data.is_empty() || data.len() > max {
            return Err(TerminalError::new(TerminalErrorCode::InputTooLarge));
        }
        self.runtime.input.push(data.to_vec())
    }

    /// 提交最新尺寸；resize worker 只保留最后一个 pending value 以避免拖垮 PTY。
    pub fn resize(&self, size: TerminalSize) -> Result<(), TerminalError> {
        self.runtime.validate_open(self.generation)?;
        if !size.validate() {
            return Err(TerminalError::new(TerminalErrorCode::InvalidSize));
        }
        self.runtime.resize(size)
    }

    /// 以绝对 deadline 消费一条已经批量化的 terminal event。
    pub fn recv_until(&self, deadline: Instant) -> Result<Option<TerminalEvent>, TerminalError> {
        self.runtime.validate_token(self.generation)?;
        Ok(self.runtime.events.recv_until(deadline))
    }

    /// 读取最近 bounded scrollback；bytes 不做 UTF-8 解码和重写。
    pub fn scrollback(&self) -> Result<Vec<u8>, TerminalError> {
        self.runtime.validate_token(self.generation)?;
        self.runtime.scrollback()
    }

    /// 幂等关闭当前 generation，并在 host deadline 内等待 worker 结束。
    pub fn close(&self, reason: CloseReason) -> Result<(), TerminalError> {
        self.runtime.validate_token(self.generation)?;
        self.runtime.close(reason)
    }

    /// 将用户取消映射为同一幂等 close 路径，避免额外的未收口 cancellation worker。
    pub fn cancel(&self) -> Result<(), TerminalError> {
        self.close(CloseReason::Timeout)
    }
}

pub(crate) struct TerminalRuntime {
    id: TerminalId,
    generation: u64,
    profile: ShellProfile,
    limits: super::policy::TerminalLimits,
    input: Arc<InputQueue>,
    events: Arc<EventQueue>,
    resize_queue: Arc<ResizeQueue>,
    pub(crate) master: Arc<Mutex<Option<Box<dyn MasterPty + Send>>>>,
    killer: Arc<Mutex<Box<dyn ChildKiller + Send + Sync>>>,
    process_tree: Arc<dyn ProcessTree>,
    pub(crate) stop: AtomicBool,
    pub(crate) failed: AtomicBool,
    pub(crate) lifecycle: Mutex<Lifecycle>,
    pub(crate) scrollback: Mutex<Scrollback>,
    workers: Arc<WorkerTracker>,
}

pub(crate) struct Lifecycle {
    terminal_sent: bool,
    closed: bool,
    reader_done: bool,
    exit_status: Option<portable_pty::ExitStatus>,
}

pub(crate) struct Scrollback {
    pub(crate) chunks: std::collections::VecDeque<Vec<u8>>,
    pub(crate) bytes: usize,
    pub(crate) limit: usize,
}

impl Scrollback {
    /// 保留最近 bytes，超过上限时从最旧 chunk 淘汰而不是无限累积。
    pub(crate) fn append(&mut self, mut data: Vec<u8>) {
        if data.len() >= self.limit {
            let start = data.len().saturating_sub(self.limit);
            data = data.split_off(start);
            self.chunks.clear();
            self.bytes = 0;
        }
        self.bytes = self.bytes.saturating_add(data.len());
        self.chunks.push_back(data);
        while self.bytes > self.limit {
            if let Some(chunk) = self.chunks.pop_front() {
                self.bytes = self.bytes.saturating_sub(chunk.len());
            } else {
                self.bytes = 0;
            }
        }
    }

    /// 将 bounded chunk 拼成快照；只有上限范围内的数据会被复制给 caller。
    pub(crate) fn snapshot(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(self.bytes);
        for chunk in &self.chunks {
            result.extend_from_slice(chunk);
        }
        result
    }
}

impl TerminalRuntime {
    /// 创建 PTY、绑定 tree guard，再启动四个有明确 ownership 的 worker。
    fn spawn(
        id: TerminalId,
        generation: u64,
        policy: TerminalPolicy,
        request: LaunchRequest,
    ) -> Result<Arc<Self>, TerminalError> {
        let limits = policy.limits();
        let prepared = policy.prepare(&request)?;
        let pty_system = native_pty_system();
        let pair = pty_system
            .openpty(session_workers::to_pty_size(prepared.size))
            .map_err(|error| {
                tracing::debug!(error = %error, "terminal PTY open failed");
                TerminalError::new(TerminalErrorCode::PtyFailed)
            })?;
        let reader = pair.master.try_clone_reader().map_err(|error| {
            tracing::debug!(error = %error, "terminal PTY reader clone failed");
            TerminalError::new(TerminalErrorCode::PtyFailed)
        })?;
        let writer = pair.master.take_writer().map_err(|error| {
            tracing::debug!(error = %error, "terminal PTY writer acquisition failed");
            TerminalError::new(TerminalErrorCode::PtyFailed)
        })?;
        let mut command = CommandBuilder::new(&prepared.shell.program);
        command.args(&prepared.shell.args);
        command.cwd(&prepared.cwd);
        // `CommandBuilder::new` 已经从 Ja 进程建立完整宿主环境（Windows 还会合并系统
        // 与用户环境）。只应用显式 override；清空后重建会让 APPDATA、GH_CONFIG_DIR、
        // 代理和用户安装工具等正常 CLI 依赖消失，造成“Ja 终端不像用户终端”。
        for (key, value) in &prepared.environment {
            command.env(key, value);
        }
        let mut child = pair.slave.spawn_command(command).map_err(|error| {
            tracing::debug!(error = %error, "terminal shell spawn failed");
            TerminalError::new(TerminalErrorCode::SpawnFailed)
        })?;
        drop(pair.slave);
        let killer = child.clone_killer();
        let tree = match process::attach(child.as_ref()) {
            Ok(tree) => tree,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error);
            }
        };
        let runtime = Arc::new(Self {
            id,
            generation,
            profile: request.profile,
            limits,
            input: Arc::new(InputQueue::new(limits.max_input_queue_bytes)),
            events: Arc::new(EventQueue::new(
                id,
                generation,
                limits.max_output_queue_bytes,
                limits.max_event_count,
            )),
            resize_queue: Arc::new(ResizeQueue::new()),
            master: Arc::new(Mutex::new(Some(pair.master))),
            killer: Arc::new(Mutex::new(killer)),
            process_tree: Arc::from(tree),
            stop: AtomicBool::new(false),
            failed: AtomicBool::new(false),
            lifecycle: Mutex::new(Lifecycle {
                terminal_sent: false,
                closed: false,
                reader_done: false,
                exit_status: None,
            }),
            scrollback: Mutex::new(Scrollback {
                chunks: std::collections::VecDeque::new(),
                bytes: 0,
                limit: limits.max_scrollback_bytes,
            }),
            workers: Arc::new(WorkerTracker::new(session_workers::WORKER_COUNT)),
        });
        let spawn_result = session_workers::spawn_workers(&runtime, reader, writer, child);
        if spawn_result.is_err() {
            let _ = runtime.request_stop();
            let deadline = Instant::now()
                .checked_add(limits.operation_timeout)
                .unwrap_or_else(|| Instant::now() + Duration::from_secs(30));
            let _ = runtime.workers.wait_until(deadline);
            runtime.events.close();
            return Err(TerminalError::new(TerminalErrorCode::SpawnFailed));
        }
        Ok(runtime)
    }

    /// 检查 caller token 是否仍指向同一 session generation。
    fn validate_token(&self, generation: u64) -> Result<(), TerminalError> {
        if generation != self.generation {
            return Err(TerminalError::new(TerminalErrorCode::StaleGeneration));
        }
        Ok(())
    }

    /// command/resize 需要 open 状态；recv/scrollback 则只需要 generation token。
    fn validate_open(&self, generation: u64) -> Result<(), TerminalError> {
        self.validate_token(generation)?;
        if self.failed.load(Ordering::Acquire) {
            return Err(TerminalError::new(TerminalErrorCode::SessionClosed));
        }
        let lifecycle = match self.lifecycle.lock() {
            Ok(lifecycle) => lifecycle,
            Err(error) => {
                drop(error);
                self.fail_closed_state(TerminalErrorCode::PtyFailed);
                return Err(TerminalError::new(TerminalErrorCode::SessionClosed));
            }
        };
        if lifecycle.closed || lifecycle.terminal_sent {
            Err(TerminalError::new(TerminalErrorCode::SessionClosed))
        } else {
            Ok(())
        }
    }

    /// 只有 closed 或自然退出且 worker 已回收的 runtime 才能释放 supervisor slot。
    fn is_reclaimable(&self) -> bool {
        if self.failed.load(Ordering::Acquire) {
            return self.workers.is_reaped();
        }
        match self.lifecycle.lock() {
            Ok(lifecycle) => lifecycle.terminal_sent && self.workers.is_reaped(),
            Err(error) => {
                drop(error);
                self.fail_closed_state(TerminalErrorCode::PtyFailed);
                false
            }
        }
    }

    /// 以 latest-value semantics 写入 resize queue，避免窗口拖动产生无限请求。
    fn resize(&self, size: TerminalSize) -> Result<(), TerminalError> {
        self.resize_queue.set(size)
    }

    /// 每个 byte 都进入有界 scrollback；event queue 把 renderer backpressure 转换为
    /// `OutputDropped` delta，而不是终止 PTY。
    pub(crate) fn publish_output(&self, data: Vec<u8>) -> bool {
        if self.failed.load(Ordering::Acquire) {
            return false;
        }
        let allowed = {
            let lifecycle = match self.lifecycle.lock() {
                Ok(lifecycle) => lifecycle,
                Err(error) => {
                    drop(error);
                    self.fail_closed_state(TerminalErrorCode::PtyFailed);
                    return false;
                }
            };
            !lifecycle.closed
                && !lifecycle.terminal_sent
                // child exit 会在 PTY EOF 前触发进程树回收；仅在该窄窗口继续接收 buffered bytes，
                // 显式 close/failure 后的 late output 仍必须拒绝。
                && (!self.stop.load(Ordering::Acquire) || lifecycle.exit_status.is_some())
        };
        if !allowed {
            return false;
        }
        match self.scrollback.lock() {
            Ok(mut scrollback) => scrollback.append(data.clone()),
            Err(error) => {
                drop(error);
                self.fail_closed_state(TerminalErrorCode::PtyFailed);
                return false;
            }
        }
        self.events.push_output(data)
    }

    /// ConPTY 在 child 仍可交互时可能暂时返回零字节；只有观察到 terminal/close 状态才允许 reader 退出。
    fn reader_can_finish(&self) -> bool {
        if self.failed.load(Ordering::Acquire) {
            return true;
        }
        match self.lifecycle.lock() {
            Ok(lifecycle) => {
                lifecycle.terminal_sent || lifecycle.closed || lifecycle.exit_status.is_some()
            }
            Err(error) => {
                drop(error);
                self.fail_closed_state(TerminalErrorCode::PtyFailed);
                true
            }
        }
    }

    /// 发布 resize 事件；resize failure 由 worker 映射为稳定错误。
    fn publish_control(&self, kind: TerminalEventKind) {
        if !self.events.push_control(kind) {
            self.fail(TerminalErrorCode::OutputLimitExceeded);
        }
    }

    /// 只发布一次 Error 终态，并立即停止进程树。
    fn fail(&self, code: TerminalErrorCode) {
        let should_publish = {
            let mut lifecycle = match self.lifecycle.lock() {
                Ok(lifecycle) => lifecycle,
                Err(error) => {
                    drop(error);
                    self.fail_closed_state(code);
                    return;
                }
            };
            if lifecycle.terminal_sent {
                false
            } else {
                lifecycle.terminal_sent = true;
                lifecycle.closed = true;
                true
            }
        };
        if should_publish {
            self.events
                .push_terminal(TerminalEventKind::Error { code: code as u16 });
        }
        let _ = self.request_stop();
        self.events.close();
    }

    /// 正常 child exit 也必须杀掉可能仍存活的 descendants，再通知 UI。
    fn child_exited(&self, status: portable_pty::ExitStatus) {
        let status_to_publish = {
            let mut lifecycle = match self.lifecycle.lock() {
                Ok(lifecycle) => lifecycle,
                Err(error) => {
                    drop(error);
                    self.fail_closed_state(TerminalErrorCode::PtyFailed);
                    return;
                }
            };
            if lifecycle.terminal_sent {
                None
            } else if lifecycle.reader_done {
                lifecycle.terminal_sent = true;
                Some(status)
            } else {
                lifecycle.exit_status = Some(status);
                None
            }
        };
        let _ = self.process_tree.terminate();
        // reader 排空 PTY 已缓冲 bytes 前保持 master 存活，此处提前关闭会丢失末尾 shell output。
        let _ = self.request_stop_preserve_master();
        if let Some(status) = status_to_publish {
            self.events.push_terminal(TerminalEventKind::Exited {
                code: status.exit_code(),
                signal: status.signal().map(ToOwned::to_owned),
            });
            self.events.close();
        }
    }

    /// reader EOF 证明 PTY backlog 已经消费完，再发布 Exited 以免丢失最后一批 bytes。
    fn reader_finished(&self) {
        let status_to_publish = {
            let mut lifecycle = match self.lifecycle.lock() {
                Ok(lifecycle) => lifecycle,
                Err(error) => {
                    drop(error);
                    self.fail_closed_state(TerminalErrorCode::PtyFailed);
                    return;
                }
            };
            lifecycle.reader_done = true;
            if lifecycle.terminal_sent {
                None
            } else if let Some(status) = lifecycle.exit_status.take() {
                lifecycle.terminal_sent = true;
                Some(status)
            } else {
                None
            }
        };
        if let Some(status) = status_to_publish {
            self.events.push_terminal(TerminalEventKind::Exited {
                code: status.exit_code(),
                signal: status.signal().map(ToOwned::to_owned),
            });
            self.events.close();
        }
        let _ = self.release_master();
    }

    /// 有界 post-exit drain 窗口后关闭 PTY master，防止等待 ConPTY EOF 的平台 reader
    /// 永久超出 session 生命周期。
    fn drop_master(&self) {
        let _ = self.release_master();
    }

    /// 用户 close 立即变成终态，再使用同一 deadline 等待所有 worker。
    fn close(&self, reason: CloseReason) -> Result<(), TerminalError> {
        let deadline = Instant::now()
            .checked_add(self.limits.operation_timeout)
            .ok_or(TerminalError::new(TerminalErrorCode::DeadlineExceeded))?;
        self.close_until(reason, deadline)
    }

    /// shutdown 复用一个绝对 deadline，避免每个 session 单独延长应用退出时间。
    fn close_until(&self, reason: CloseReason, deadline: Instant) -> Result<(), TerminalError> {
        let (lifecycle_valid, should_publish) = match self.lifecycle.lock() {
            Ok(mut lifecycle) => {
                let should_publish = if lifecycle.terminal_sent {
                    lifecycle.closed = true;
                    false
                } else {
                    lifecycle.terminal_sent = true;
                    lifecycle.closed = true;
                    true
                };
                (true, should_publish)
            }
            Err(error) => {
                drop(error);
                self.fail_closed_state(TerminalErrorCode::PtyFailed);
                (false, false)
            }
        };
        if should_publish {
            self.events
                .push_terminal(TerminalEventKind::Closed { reason });
        }
        let cleanup = self.request_stop();
        let workers_stopped = self.workers.wait_until(deadline);
        self.events.close();
        match (lifecycle_valid, cleanup.is_ok(), workers_stopped) {
            (false, _, _) => Err(TerminalError::new(TerminalErrorCode::ProcessCleanupFailed)),
            (true, true, WorkerReap::Complete) => Ok(()),
            (true, false, _) => Err(TerminalError::new(TerminalErrorCode::ProcessCleanupFailed)),
            (true, _, WorkerReap::Timeout | WorkerReap::JoinFailed) => {
                Err(TerminalError::new(TerminalErrorCode::WorkerShutdownTimeout))
            }
        }
    }

    /// 发送 stop、关闭 queues、终止 tree 和释放 master，所有调用都可重复。
    fn request_stop(&self) -> Result<(), TerminalError> {
        self.request_stop_inner(true)
    }

    /// child exit 使用保留 master 的 stop 变体，让 buffered PTY output 能先完成排空。
    fn request_stop_preserve_master(&self) -> Result<(), TerminalError> {
        self.request_stop_inner(false)
    }

    /// 共享 stop 路径保持幂等；close deadline 要求释放时，重复调用仍会关闭 master。
    fn request_stop_inner(&self, drop_master: bool) -> Result<(), TerminalError> {
        let first_stop = !self.stop.swap(true, Ordering::AcqRel);
        if first_stop {
            self.input.close();
            self.resize_queue.close();
        }
        let mut first_error = self.process_tree.terminate().err();
        // direct child killer 仅作 fallback；Job Object/process-group 已成功终止后再调用，
        // 会把预期的 already-exited child 错判为 cleanup failure。
        let mut resource_failed = false;
        if first_error.is_some() {
            match self.killer.lock() {
                Ok(mut killer) => {
                    if let Err(error) = killer.kill() {
                        first_error.get_or_insert(error);
                    }
                }
                Err(_) => {
                    // killer 内部状态无法重建；process tree 仍是主清理 owner，
                    // 但 fallback 不可用必须显式返回 cleanup failure。
                    resource_failed = true;
                }
            }
        }
        if drop_master && self.release_master().is_err() {
            resource_failed = true;
        }
        if let Some(error) = first_error.as_ref() {
            tracing::debug!(error_kind = ?error.kind(), "terminal process cleanup failed");
        }
        if first_error.is_some() || resource_failed {
            Err(TerminalError::new(TerminalErrorCode::ProcessCleanupFailed))
        } else {
            Ok(())
        }
    }

    /// 将 scrollback 快照限定为内部 byte budget，避免 UI 请求导致二次无界增长。
    fn scrollback(&self) -> Result<Vec<u8>, TerminalError> {
        if self.failed.load(Ordering::Acquire) {
            return Err(TerminalError::new(TerminalErrorCode::SessionClosed));
        }
        match self.scrollback.lock() {
            Ok(scrollback) => Ok(scrollback.snapshot()),
            Err(error) => {
                drop(error);
                self.fail_closed_state(TerminalErrorCode::PtyFailed);
                Err(TerminalError::new(TerminalErrorCode::SessionClosed))
            }
        }
    }

    /// lifecycle/scrollback poison 后不再信任终态顺序；独立原子栅栏保证
    /// 只发布一次错误意图，然后终止进程树、关闭队列并拒绝 late output。
    fn fail_closed_state(&self, code: TerminalErrorCode) {
        let first_failure = !self.failed.swap(true, Ordering::AcqRel);
        if first_failure {
            self.events
                .push_terminal(TerminalEventKind::Error { code: code as u16 });
        }
        let _ = self.request_stop();
        self.events.close();
    }

    /// master 是单一 `Option` 资源槽；poison 时先封闭 runtime，再将该槽
    /// 明确重建为 `None`，而不继续调用可能处于中间态的 PTY 对象。
    pub(crate) fn release_master(&self) -> Result<(), TerminalError> {
        match self.master.lock() {
            Ok(mut master) => {
                master.take();
                Ok(())
            }
            Err(error) => {
                drop(error);
                self.failed.store(true, Ordering::Release);
                self.master.clear_poison();
                if let Ok(mut master) = self.master.lock() {
                    master.take();
                }
                Err(TerminalError::new(TerminalErrorCode::ProcessCleanupFailed))
            }
        }
    }
}

impl Drop for TerminalRuntime {
    /// supervisor 崩溃或 owner 被遗弃时仍尽力终止 child；Drop 不阻塞等待线程。
    fn drop(&mut self) {
        let _ = self.request_stop();
        self.events.close();
    }
}
