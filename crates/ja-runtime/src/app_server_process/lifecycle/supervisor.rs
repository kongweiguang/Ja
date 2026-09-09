// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Sidecar 生命周期编排。
//!
//! 配置、client handle 与 child 所有权位于独立内部模块；本模块是唯一生命周期
//! 转换 owner，避免旧 generation 的迟到 terminal callback 复活新进程。

use super::{LifecycleMachine, LifecycleState};
use crate::app_server_process::client::{EventPump, Session, SessionEvent, TerminalReason};
use crate::app_server_process::error::AppServerProcessError;
use crate::app_server_process::protocol::{
    AttachmentPreviewCloseParams, AttachmentPreviewOpenParams, AttachmentPreviewReadParams, Limits,
    MAX_READY_TIMEOUT, MAX_SHUTDOWN_TIMEOUT, RpcFrame, TurnChangeSetReadParams, V1_CLIENT_METHODS,
    checked_deadline, error_is_incompatible, generate_ready_token, is_ready_notification,
    is_runtime_ready_notification, valid_schema_id, validate_attachment_preview_request,
    validate_capabilities, validate_remote_limits, validate_turn_change_set_request,
};
use serde_json::Value;
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::app_server_process::process::{
    RunningProcess, SidecarConfig, TerminalSignal, spawn_process,
};

/// 唯一控制真实 sidecar 的宿主状态；所有状态变更回到 lifecycle 单线程对象。
pub struct SidecarSupervisor {
    config: SidecarConfig,
    pub(crate) lifecycle: LifecycleMachine,
    process: Option<Arc<RunningProcess>>,
    pub(crate) session: Option<Session>,
    event_pump: Option<EventPump>,
    expected_server_instance: Option<String>,
    ready_token_echo: Option<String>,
    pub(crate) terminal_signals: Arc<Mutex<VecDeque<TerminalSignal>>>,
    pub(crate) stopping: Arc<Mutex<bool>>,
}

/// 当前 sidecar generation 的窄只读句柄；它只允许读取冻结 ChangeSet，不能驱动生命周期或 Turn。
pub struct TurnChangeSetReadLease {
    session: Session,
    stopping: Arc<Mutex<bool>>,
    generation: u64,
}

impl TurnChangeSetReadLease {
    /// 固定方法与 typed 参数在 session 内并发等待，5 秒超时仅留下 request tombstone，不关闭 runtime。
    pub fn read(
        &self,
        params: TurnChangeSetReadParams,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let params = params
            .into_value()
            .map_err(|_| AppServerProcessError::ProtocolFault)?;
        self.session
            .request_with_gate("turn/change-set/read", params, timeout, &self.stopping)
    }

    /// generation 供宿主在完整读取结束后拒绝旧 sidecar 的迟到结果。
    pub const fn generation(&self) -> u64 {
        self.generation
    }
}

impl SidecarSupervisor {
    /// 校验边界后才创建生命周期 owner，避免无效配置进入 crash-loop。
    pub fn new(config: SidecarConfig) -> Result<Self, AppServerProcessError> {
        config.validate()?;
        let lifecycle = LifecycleMachine::new(config.restart)?;
        Ok(Self {
            config,
            lifecycle,
            process: None,
            session: None,
            event_pump: None,
            expected_server_instance: None,
            ready_token_echo: None,
            terminal_signals: Arc::new(Mutex::new(VecDeque::new())),
            stopping: Arc::new(Mutex::new(false)),
        })
    }

    /// 暴露生命周期快照并先归属 terminal signal，避免 UI 不消费 event 时状态仍停在 Ready。
    pub fn state(&mut self) -> LifecycleState {
        self.sync_terminal_signals();
        self.lifecycle.state()
    }

    /// 返回当前 generation，供外部把 response/event 绑定到同一 sidecar 实例。
    pub fn generation(&self) -> u64 {
        self.lifecycle.generation()
    }

    /// 返回 initialize 捕获并校验的 server identity；host 直接据此绑定 notification，
    /// 不重新打开第二条协议路径。
    pub fn server_instance_id(&self) -> Option<&str> {
        self.expected_server_instance.as_deref()
    }

    /// 仅向可信 host 返回当前 generation 的已校验 challenge，使 ready 投影保持协议要求
    /// 的精确 echo，不把 challenge 传播给普通调用方。
    pub fn ready_token_echo(&mut self) -> Result<String, AppServerProcessError> {
        self.sync_terminal_signals();
        if !matches!(
            self.lifecycle.state(),
            LifecycleState::Ready | LifecycleState::Busy
        ) {
            return Err(AppServerProcessError::NotReady);
        }
        self.ready_token_echo
            .clone()
            .ok_or(AppServerProcessError::HandshakeFailed)
    }

    /// 为当前 generation 生成不可预测 challenge；ready 只接受本 session 的精确值。
    fn next_ready_token(&self) -> Result<String, AppServerProcessError> {
        generate_ready_token()
    }

    /// 启动 generation 时先允许 host 注册新 session，再发起可能阻塞的握手请求，确保
    /// 退出控制在 slow start 窗口内仍能找到资源 owner。
    pub fn start_with_session_hook(
        &mut self,
        session_hook: Option<&dyn Fn(Session)>,
    ) -> Result<(), AppServerProcessError> {
        let deadline = checked_deadline(self.config.ready_timeout, MAX_READY_TIMEOUT)?;
        self.start_until_with_session_hook(deadline, session_hook)
    }

    /// 注册一个 host-owned cancellation hook，使握手等待可被退出请求
    /// 主动唤醒，而不是等到固定 protocol timeout 自然返回。
    pub fn start_until_with_session_hook(
        &mut self,
        deadline: Instant,
        session_hook: Option<&dyn Fn(Session)>,
    ) -> Result<(), AppServerProcessError> {
        // 第一阶段同步异步终态并清理上一 generation 的残留；资源仍被持有时禁止开启新生命周期。
        self.sync_terminal_signals();
        self.set_stopping_state(false)?;
        if matches!(
            self.lifecycle.state(),
            LifecycleState::Exited | LifecycleState::Backoff
        ) && (self.process.is_some() || self.session.is_some())
        {
            self.fail_process_only_until(deadline);
            if self.owns_resources() {
                return Err(AppServerProcessError::ProcessTree);
            }
        }
        // 只有生命周期机准入成功后才创建进程；spawn 失败必须把同一 generation 标记为 faulted。
        let generation = self.lifecycle.begin_start()?;
        self.expected_server_instance = None;
        self.ready_token_echo = None;
        let (process, session) =
            match spawn_process(&self.config, generation, Arc::clone(&self.terminal_signals)) {
                Ok(value) => value,
                Err(error) => {
                    let _ = self.lifecycle.mark_faulted(generation);
                    return Err(error);
                }
            };
        // 先把 process/session 交给 supervisor，再通知 host hook，确保取消方始终能找到唯一资源 owner。
        self.process = Some(process);
        self.session = Some(session.clone());
        if let Some(session_hook) = session_hook {
            session_hook(session.clone());
        }
        self.event_pump = match session.take_event_pump() {
            Ok(pump) => Some(pump),
            Err(error) => {
                self.fail_generation_until(generation, deadline);
                return Err(error);
            }
        };

        // 每个 generation 安装一次新的 ready-token challenge，旧 token 永远不能提升当前 session。
        let ready_token = match self.next_ready_token() {
            Ok(token) => token,
            Err(error) => {
                self.fail_generation_until(generation, deadline);
                return Err(error);
            }
        };
        if let Err(error) = session.install_ready_token_challenge(ready_token.clone()) {
            self.fail_generation_until(generation, deadline);
            return Err(error);
        }

        // Initialize RPC 只能消费调用方绝对 deadline 的剩余预算；协议不兼容与普通故障进入不同终态。
        let initialize_timeout = deadline
            .saturating_duration_since(Instant::now())
            .min(self.config.ready_timeout);
        if initialize_timeout.is_zero() {
            self.fail_generation_until(generation, deadline);
            return Err(AppServerProcessError::DeadlineExceeded);
        }
        let initialize = match session.request(
            "runtime/initialize",
            crate::app_server_process::protocol::default_initialize_params(&self.config.limits),
            initialize_timeout,
        ) {
            Ok(response) => response,
            Err(error) => {
                self.fail_generation_until(generation, deadline);
                return Err(error);
            }
        };
        if let Some(error) = initialize.error() {
            let incompatible = error_is_incompatible(error.code(), error.data());
            self.fail_process_only_until(deadline);
            if incompatible {
                let _ = self.lifecycle.mark_incompatible(generation);
                return Err(AppServerProcessError::Incompatible);
            }
            let _ = self.lifecycle.mark_faulted(generation);
            return Err(AppServerProcessError::ProtocolFault);
        }
        let result = initialize
            .result()
            .value()
            .ok_or(AppServerProcessError::ProtocolFault)
            .and_then(|result| self.check_initialize_result(result));
        if let Err(error) = result {
            let incompatible = matches!(error, AppServerProcessError::Incompatible);
            self.fail_process_only_until(deadline);
            if incompatible {
                let _ = self.lifecycle.mark_incompatible(generation);
            } else {
                let _ = self.lifecycle.mark_faulted(generation);
            }
            return Err(error);
        }

        // Initialize 响应通过校验后才发送 initialized 通知，随后只接受带正确身份与 token 的 ready 事件。
        let initialized_params = match session.initialized_params() {
            Ok(params) => params,
            Err(error) => {
                self.fail_generation_until(generation, deadline);
                return Err(error);
            }
        };
        if let Err(error) = session.notify("runtime/initialized", initialized_params) {
            self.fail_generation_until(generation, deadline);
            return Err(error);
        }
        // 终态等待始终受同一 deadline 约束；任何 EOF、队列故障或协议错误都统一回收本 generation。
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                self.fail_generation_until(generation, deadline);
                return Err(AppServerProcessError::DeadlineExceeded);
            }
            match self
                .event_pump
                .as_mut()
                .and_then(|pump| pump.next_event(remaining))
            {
                Some(SessionEvent::Notification(frame))
                    if is_runtime_ready_notification(&frame) =>
                {
                    if !is_ready_notification(&frame, self.expected_server_instance.as_deref()) {
                        self.fail_generation_until(generation, deadline);
                        return Err(AppServerProcessError::HandshakeFailed);
                    }
                    let promotion = session
                        .with_ready_promotion(&frame, || self.lifecycle.mark_ready(generation));
                    if let Err(error) = promotion {
                        self.fail_generation_until(generation, deadline);
                        return Err(error);
                    }
                    self.ready_token_echo = Some(ready_token.clone());
                    return Ok(());
                }
                Some(SessionEvent::ProcessExited { .. } | SessionEvent::Eof) => {
                    self.fail_generation_until(generation, deadline);
                    return Err(AppServerProcessError::ProcessExited);
                }
                Some(SessionEvent::HandshakeFailed) => {
                    self.fail_generation_until(generation, deadline);
                    return Err(AppServerProcessError::HandshakeFailed);
                }
                Some(SessionEvent::WriterTimedOut) => {
                    self.fail_generation_until(generation, deadline);
                    return Err(AppServerProcessError::DeadlineExceeded);
                }
                Some(
                    SessionEvent::ProtocolFault(_)
                    | SessionEvent::QueueFatalOverflow(_)
                    | SessionEvent::ResponseRejected,
                ) => {
                    self.fail_generation_until(generation, deadline);
                    return Err(AppServerProcessError::ProtocolFault);
                }
                Some(_) => {}
                None => {
                    self.fail_generation_until(generation, deadline);
                    return Err(AppServerProcessError::DeadlineExceeded);
                }
            }
        }
    }

    /// 用同一 v1 client 闭集提供短生命周期 supervisor request。
    pub fn request(
        &mut self,
        method: &str,
        params: Value,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        if !V1_CLIENT_METHODS.contains(&method) {
            return Err(AppServerProcessError::ProtocolFault);
        }
        validate_turn_identity(method, &params)?;
        self.sync_terminal_signals();
        if self.stopping_state()? {
            return Err(AppServerProcessError::ShuttingDown);
        }
        if self.lifecycle.state() != LifecycleState::Ready {
            return Err(match self.lifecycle.state() {
                LifecycleState::Stopping => AppServerProcessError::ShuttingDown,
                _ => AppServerProcessError::NotReady,
            });
        }
        let generation = self.lifecycle.generation();
        self.lifecycle.mark_busy(generation)?;
        let session = self
            .session
            .clone()
            .ok_or(AppServerProcessError::NotReady)?;
        let result = session.request_with_gate(method, params, timeout, &self.stopping);
        let _ = self.lifecycle.mark_ready_again(generation);
        self.sync_terminal_signals();
        result
    }

    /// 仅通过类型化 DRAFT/BOUND 授权发起预览，防止 generic method lane 夹带路径或宽权限。
    pub fn attachment_preview_open(
        &mut self,
        params: AttachmentPreviewOpenParams,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let params = params
            .into_value()
            .map_err(|_| AppServerProcessError::ProtocolFault)?;
        self.request("attachment/preview/open", params, timeout)
    }

    /// 使用已验证的 64 KiB 分段参数读取预览，保持业务错误仍由 RpcFrame 原样返回。
    pub fn attachment_preview_read(
        &mut self,
        params: AttachmentPreviewReadParams,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let params = params
            .into_value()
            .map_err(|_| AppServerProcessError::ProtocolFault)?;
        self.request("attachment/preview/read", params, timeout)
    }

    /// 关闭 opaque preview session；固定方法避免上层自行拼接任意资源关闭请求。
    pub fn attachment_preview_close(
        &mut self,
        params: AttachmentPreviewCloseParams,
        timeout: Duration,
    ) -> Result<RpcFrame, AppServerProcessError> {
        let params = params
            .into_value()
            .map_err(|_| AppServerProcessError::ProtocolFault)?;
        self.request("attachment/preview/close", params, timeout)
    }

    /// Actor 只在 Ready 线性化点签发当前 generation 的窄句柄，正文等待不占用生命周期 owner。
    pub fn turn_change_set_read_lease(
        &mut self,
    ) -> Result<TurnChangeSetReadLease, AppServerProcessError> {
        self.sync_terminal_signals();
        if self.stopping_state()? {
            return Err(AppServerProcessError::ShuttingDown);
        }
        if self.lifecycle.state() != LifecycleState::Ready {
            return Err(match self.lifecycle.state() {
                LifecycleState::Stopping => AppServerProcessError::ShuttingDown,
                _ => AppServerProcessError::NotReady,
            });
        }
        Ok(TurnChangeSetReadLease {
            session: self
                .session
                .clone()
                .ok_or(AppServerProcessError::NotReady)?,
            stopping: Arc::clone(&self.stopping),
            generation: self.lifecycle.generation(),
        })
    }

    /// 仅为 host cancellation 返回当前 session clone；不会创建第二个 event-pump
    /// consumer 或 supervisor owner。
    pub fn session_for_cancellation(&self) -> Option<Session> {
        self.session.clone()
    }

    /// 向 host exit gate 暴露 session 的有界 close；适配器保留在 lifecycle 层，使
    /// writer join 消耗调用方同一个绝对 deadline，而不公开 Session 内部方法。
    pub fn close_session_until(
        session: &Session,
        deadline: Instant,
    ) -> Result<(), AppServerProcessError> {
        session.close_until(deadline)
    }

    /// 一次性移交唯一事件 pump；移交后 supervisor 不再提供 next_event 消费路径。
    pub fn take_event_pump(&mut self) -> Result<EventPump, AppServerProcessError> {
        self.sync_terminal_signals();
        self.event_pump
            .take()
            .ok_or(AppServerProcessError::InvalidState)
    }

    /// 使用调用方已经建立的绝对 deadline，避免 bridge 的退出预算在
    /// supervisor 边界被重新计算而延长整个 Tauri 关闭流程。
    pub fn shutdown_until(&mut self, deadline: Instant) -> Result<(), AppServerProcessError> {
        let stopping_poisoned = self.set_stopping_state(true).is_err();
        self.sync_terminal_signals();
        let generation = self.lifecycle.generation();
        let state = self.lifecycle.state();
        let owns_resources =
            self.process.is_some() || self.session.is_some() || self.event_pump.is_some();
        let already_stopping = state == LifecycleState::Stopping;
        let active = matches!(
            state,
            LifecycleState::Starting | LifecycleState::Ready | LifecycleState::Busy
        );
        if !active && !already_stopping && !owns_resources {
            // faulted/incompatible generation 已无 process owner 且处于终态，无需重复
            // 标记或重试清理。
            return if stopping_poisoned {
                Err(AppServerProcessError::Faulted)
            } else {
                Ok(())
            };
        }
        if active && !already_stopping {
            self.lifecycle.begin_stop(generation)?;
        }
        if active
            && !already_stopping
            && let Some(session) = self.session.clone()
        {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if !remaining.is_zero() {
                let _ = session.request(
                    "runtime/shutdown",
                    serde_json::json!({}),
                    remaining.min(self.config.shutdown_timeout),
                );
            }
        }

        // writer 与 process-tree adapter 都确认清理前保留全部 owner；失败尝试继续保留
        // 精确 handle 供后续重试，不能发布虚假的 Exited 状态。
        let cleanup_result = self.cleanup_owner_until(deadline);
        cleanup_result?;
        self.sync_terminal_signals();
        let _ = self.lifecycle.mark_exited(generation);
        if stopping_poisoned {
            Err(AppServerProcessError::Faulted)
        } else {
            Ok(())
        }
    }

    /// 严格验证 initialize 的闭集结果、实例、runtime、能力和 effective limits 后才允许 ready。
    fn check_initialize_result(&mut self, result: &Value) -> Result<(), AppServerProcessError> {
        self.expected_server_instance =
            Some(validate_initialize_result(result, &self.config.limits)?);
        Ok(())
    }

    /// 将 terminal signal 映射到生命周期；只处理 current generation，拒绝旧信号污染新实例。
    fn sync_terminal_signals(&mut self) {
        let signals = {
            match self.terminal_signals.lock() {
                Ok(mut queue) => Some(queue.drain(..).collect::<Vec<_>>()),
                Err(_) => None,
            }
        };
        let Some(signals) = signals else {
            // signal 顺序决定 generation 归属；中毒后不能读取可能只消费一半的队列。
            // 先结束真实资源，再把当前 generation 标为 Faulted，阻止继续准入。
            let generation = self.lifecycle.generation();
            self.fail_process_only();
            let _ = self.lifecycle.mark_faulted(generation);
            return;
        };
        for signal in signals {
            if !self.lifecycle.is_current(signal.generation) {
                continue;
            }
            match signal.reason {
                TerminalReason::Fault | TerminalReason::ProcessExited => {
                    match self.lifecycle.state() {
                        LifecycleState::Starting | LifecycleState::Ready | LifecycleState::Busy => {
                            let _ = self.lifecycle.record_crash(signal.generation);
                        }
                        LifecycleState::Stopping if !self.owns_resources() => {
                            // monitor signal 只描述 leader；tree reap 失败时仍保留 process
                            // 与 session owner，因此不能发布 Exited。
                            let _ = self.lifecycle.mark_exited(signal.generation);
                        }
                        LifecycleState::Stopping => {}
                        _ => {}
                    }
                }
                TerminalReason::Closed => {
                    if self.lifecycle.state() == LifecycleState::Stopping && !self.owns_resources()
                    {
                        let _ = self.lifecycle.mark_exited(signal.generation);
                    }
                }
            }
        }
    }

    /// 读取 shutdown admission gate；锁中毒说明 start/stop 线性化已经失去证明，
    /// 因此立即清理当前 generation 并返回稳定 Faulted，而不是猜测布尔值。
    fn stopping_state(&mut self) -> Result<bool, AppServerProcessError> {
        let state = { self.stopping.lock().ok().map(|state| *state) };
        if let Some(state) = state {
            return Ok(state);
        }
        let generation = self.lifecycle.generation();
        self.fail_process_only();
        let _ = self.lifecycle.mark_faulted(generation);
        Err(AppServerProcessError::Faulted)
    }

    /// 更新 shutdown admission gate；中毒时不覆盖不可信状态，先清理唯一资源 owner，
    /// 让调用方通过 Faulted 决定是否重建整个 supervisor。
    fn set_stopping_state(&mut self, stopping: bool) -> Result<(), AppServerProcessError> {
        let updated = {
            self.stopping
                .lock()
                .map(|mut state| *state = stopping)
                .is_ok()
        };
        if updated {
            return Ok(());
        }
        let generation = self.lifecycle.generation();
        self.fail_process_only();
        let _ = self.lifecycle.mark_faulted(generation);
        Err(AppServerProcessError::Faulted)
    }

    /// 把握手失败清理限制在调用方绝对预算内，避免 slow start 在 host 已退出时重新创建
    /// 一段 shutdown 窗口。
    fn fail_generation_until(&mut self, generation: u64, deadline: Instant) {
        self.fail_process_only_until(deadline);
        let _ = self.lifecycle.mark_faulted(generation);
    }

    /// 幂等释放 process/session 资源；用于 fault、restart、shutdown 和 Drop。
    fn fail_process_only(&mut self) {
        let deadline = checked_deadline(self.config.shutdown_timeout, MAX_SHUTDOWN_TIMEOUT)
            .unwrap_or_else(|_| Instant::now());
        self.fail_process_only_until(deadline);
    }

    /// 在给定绝对 deadline 内清理 process/session owner，使 start、shutdown 与 Drop
    /// 的重试不能相互叠加延长总等待。
    fn fail_process_only_until(&mut self, deadline: Instant) {
        if let Err(error) = self.cleanup_owner_until(deadline) {
            // caller 仍可能持有 supervisor 并重试；Drop 只是最终兜底所有权路径，
            // 此处不能擦除 handle。
            tracing::error!(?error, "sidecar failure cleanup remains owned");
        }
    }

    /// 判断 process/session/event owner 是否仍存活；只有全部释放后 lifecycle signal
    /// 才能标记 Exited，避免状态早于资源事实。
    fn owns_resources(&self) -> bool {
        self.process.is_some() || self.session.is_some() || self.event_pump.is_some()
    }

    /// 在同一个绝对 deadline 内收口 writer、session 和完整 process tree；
    /// 任一阶段失败都保留所有 owner，保证下一次 shutdown 仍能重试真实句柄。
    fn cleanup_owner_until(&mut self, deadline: Instant) -> Result<(), AppServerProcessError> {
        // 已耗尽预算时不启动任何可能碰巧快速完成的清理步骤；否则相同调用会因调度
        // 时序随机返回成功或超时，且成功路径会提前释放下一次重试需要的精确 handle。
        if self.owns_resources() && deadline.saturating_duration_since(Instant::now()).is_zero() {
            return Err(AppServerProcessError::ShutdownTimeout);
        }
        let mut first_error = None;
        if let Some(session) = self.session.as_ref() {
            if let Err(error) = session.close_until(deadline) {
                first_error = Some(error);
            }
            session.detach_terminal_callback();
        }
        if let Some(process) = self.process.as_ref()
            && let Err(error) = process.terminate_tree_until(deadline)
        {
            // monitor completion 可作为下次重试证据，但不能替代 process-tree 结果作为
            // 全树已回收的证明。
            let _ = process.wait_until(deadline);
            first_error.get_or_insert(error);
        }
        if let Some(error) = first_error {
            return Err(error);
        }
        self.event_pump = None;
        self.session = None;
        self.process = None;
        self.ready_token_echo = None;
        Ok(())
    }
}

/// 在进入 pending/queue 前锁定 Turn 与通用 Workspace 请求的身份边界；其余方法
/// 继续由 typed Tauri adapter 与 Java schema 校验，避免 process owner 复制整份业务 schema。
pub(crate) fn validate_turn_identity(
    method: &str,
    params: &Value,
) -> Result<(), AppServerProcessError> {
    let Some(object) = params.as_object() else {
        return Err(AppServerProcessError::ProtocolFault);
    };
    let valid_revision = |key: &str| {
        object
            .get(key)
            .and_then(Value::as_u64)
            .is_some_and(|revision| revision <= 9_007_199_254_740_991)
    };
    let exact_keys = |allowed: &[&str]| object.keys().all(|key| allowed.contains(&key.as_str()));
    let valid_id = |key: &str, prefix: &str, max: usize| {
        object
            .get(key)
            .and_then(Value::as_str)
            .is_some_and(|value| valid_schema_id(value, prefix, max))
    };
    let valid = match method {
        "attachment/preview/open" | "attachment/preview/read" | "attachment/preview/close" => {
            validate_attachment_preview_request(method, params)
        }
        "turn/change-set/read" => validate_turn_change_set_request(method, params),
        "turn/start" => {
            exact_keys(&["threadId", "content", "deadlineMs"])
                && valid_id("threadId", "thr_", 100)
                && valid_turn_content(object.get("content"))
                && object.get("deadlineMs").is_none_or(|value| {
                    value
                        .as_u64()
                        .is_some_and(|millis| (1_000..=86_400_000).contains(&millis))
                })
        }
        "turn/cancel" => {
            exact_keys(&["turnId", "expectedThreadRevision"])
                && valid_id("turnId", "turn_", 101)
                && valid_revision("expectedThreadRevision")
        }
        "turn/input/enqueue" => {
            exact_keys(&["turnId", "content"])
                && valid_id("turnId", "turn_", 101)
                && valid_turn_content(object.get("content"))
                && queued_content_within_budget(object.get("content"))
        }
        "turn/input/prioritize" | "turn/input/delete" => {
            exact_keys(&["turnId", "inputId", "expectedInputRevision"])
                && valid_id("turnId", "turn_", 101)
                && valid_id("inputId", "input_", 128)
                && valid_revision("expectedInputRevision")
                && object.get("expectedInputRevision").and_then(Value::as_u64) != Some(0)
        }
        "turn/input/update" => {
            exact_keys(&["turnId", "inputId", "expectedInputRevision", "content"])
                && valid_id("turnId", "turn_", 101)
                && valid_id("inputId", "input_", 128)
                && valid_revision("expectedInputRevision")
                && object.get("expectedInputRevision").and_then(Value::as_u64) != Some(0)
                && valid_turn_content(object.get("content"))
                && queued_content_within_budget(object.get("content"))
        }
        "approval/respond" => {
            exact_keys(&["approvalId", "turnId", "decision", "expectedThreadRevision"])
                && valid_id("approvalId", "appr_", 101)
                && valid_id("turnId", "turn_", 101)
                && matches!(
                    object.get("decision").and_then(Value::as_str),
                    Some("approve" | "deny")
                )
                && valid_revision("expectedThreadRevision")
        }
        // General Workspace identity 由 Java 读取并持有；这里固定 params 为空对象，
        // 防止调用方借共享 request lane 夹带 cwd 或 ID。
        "workspace/open-general" => object.is_empty(),
        "workspace/path/search" => {
            exact_keys(&["threadId", "workspaceId", "query", "limit"])
                && valid_id("threadId", "thr_", 100)
                && valid_id("workspaceId", "ws_", 100)
                && object
                    .get("query")
                    .and_then(Value::as_str)
                    .is_some_and(|query| {
                        query.chars().count() <= 256 && !query.chars().any(char::is_control)
                    })
                && object.get("limit").is_none_or(|limit| {
                    limit
                        .as_u64()
                        .is_some_and(|limit| (1..=50).contains(&limit))
                })
        }
        _ => true,
    };
    if valid {
        Ok(())
    } else {
        Err(AppServerProcessError::ProtocolFault)
    }
}

/// 校验统一消息内容闭集、顺序与去重；引用只携带 identity，不接收正文快照或绝对路径。
fn valid_turn_content(value: Option<&Value>) -> bool {
    let Some(items) = value.and_then(Value::as_array) else {
        return false;
    };
    if !(1..=64).contains(&items.len()) {
        return false;
    }
    let mut attachment_ids = std::collections::HashSet::new();
    let mut workspace_references = std::collections::HashSet::new();
    let mut workspace_ids = std::collections::HashSet::new();
    let mut skill_ids = std::collections::HashSet::new();
    let mut total_text = 0usize;
    let mut phase = 0_u8;
    let mut text_count = 0_u8;
    let mut sendable = false;
    for item in items {
        let Some(item) = item.as_object() else {
            return false;
        };
        let valid = match item.get("type").and_then(Value::as_str) {
            Some("text") => {
                phase = 2;
                text_count += 1;
                sendable = true;
                item.len() == 2
                    && text_count == 1
                    && item
                        .get("text")
                        .and_then(Value::as_str)
                        .is_some_and(|text| {
                            total_text = total_text.saturating_add(text.len());
                            !text.is_empty() && total_text <= 4_000_000 && !text.contains('\0')
                        })
            }
            Some("attachment") => {
                sendable = true;
                let ordered = phase <= 1;
                phase = 1;
                item.len() == 2
                    && ordered
                    && item
                        .get("attachmentId")
                        .and_then(Value::as_str)
                        .is_some_and(|id| {
                            attachment_ids.len() < 10
                                && valid_schema_id(id, "att_", 128)
                                && attachment_ids.insert(id)
                        })
            }
            Some("workspace_reference") => {
                sendable = true;
                item.len() == 4
                    && phase == 0
                    && item
                        .get("workspaceId")
                        .and_then(Value::as_str)
                        .is_some_and(|workspace_id| {
                            workspace_ids.insert(workspace_id);
                            workspace_ids.len() == 1
                                && valid_schema_id(workspace_id, "ws_", 100)
                                && item
                                    .get("relativePath")
                                    .and_then(Value::as_str)
                                    .is_some_and(|path| {
                                        valid_relative_reference_path(path)
                                            && workspace_references.insert((workspace_id, path))
                                    })
                        })
                    && matches!(
                        item.get("kind").and_then(Value::as_str),
                        Some("file" | "directory")
                    )
            }
            Some("skill_reference") => {
                item.len() == 2
                    && phase == 0
                    && item
                        .get("skillId")
                        .and_then(Value::as_str)
                        .is_some_and(|id| {
                            valid_schema_id(id, "skill_", 101) && skill_ids.insert(id)
                        })
            }
            _ => false,
        };
        if !valid {
            return false;
        }
    }
    sendable
}

/// 队列预算按实际紧凑 JSON bytes 校验，避免多字节路径或结构开销绕过 512 KiB 上限。
fn queued_content_within_budget(value: Option<&Value>) -> bool {
    value.is_some_and(|content| {
        serde_json::to_vec(content).is_ok_and(|bytes| bytes.len() <= 524_288)
    })
}

/// 相对引用拒绝盘符、反斜杠、控制字符与父级段；真实 reparse containment 由 Java 再验证。
fn valid_relative_reference_path(path: &str) -> bool {
    let bytes = path.as_bytes();
    let has_drive_prefix = bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
    !path.is_empty()
        && path.chars().count() <= 4_096
        && !path.starts_with('/')
        && !has_drive_prefix
        && !path.contains('\\')
        && !path.chars().any(char::is_control)
        && !path.split('/').any(|segment| segment == "..")
}

/// 校验 v1 initialize 的唯一六字段结果，并返回已绑定的 server instance。
///
/// `serverVersion` 不是冻结合同字段；拒绝任何额外字段可避免桌面端把实现诊断误当成
/// 可依赖能力，同时保留 runtime 的两字段 Kernel 身份约束。
pub(crate) fn validate_initialize_result(
    result: &Value,
    local_limits: &Limits,
) -> Result<String, AppServerProcessError> {
    const RESULT_FIELDS: [&str; 6] = [
        "protocolMajor",
        "protocolMinor",
        "serverInstanceId",
        "runtime",
        "capabilities",
        "limits",
    ];
    let object = result
        .as_object()
        .ok_or(AppServerProcessError::ProtocolFault)?;
    if object.len() != RESULT_FIELDS.len()
        || object
            .keys()
            .any(|key| !RESULT_FIELDS.contains(&key.as_str()))
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    let major = object
        .get("protocolMajor")
        .and_then(Value::as_i64)
        .ok_or(AppServerProcessError::ProtocolFault)?;
    let minor = object
        .get("protocolMinor")
        .and_then(Value::as_i64)
        .filter(|minor| (0..=i64::from(i32::MAX)).contains(minor))
        .ok_or(AppServerProcessError::ProtocolFault)?;
    if major != 1 || minor != 0 {
        return Err(AppServerProcessError::Incompatible);
    }
    let instance = object
        .get("serverInstanceId")
        .and_then(Value::as_str)
        .ok_or(AppServerProcessError::ProtocolFault)?;
    if !valid_schema_id(instance, "srv_", 101) {
        return Err(AppServerProcessError::ProtocolFault);
    }
    let runtime = object
        .get("runtime")
        .and_then(Value::as_object)
        .ok_or(AppServerProcessError::ProtocolFault)?;
    // Packaging（JVM/Native Image）不是 engine identity；要求精确两字段 Kernel 形状，
    // 防止 pre-Kernel Java runtime 经 debug 分支被错误 admission。
    if runtime.len() != 2
        || runtime.get("engine").and_then(Value::as_str) != Some("ja-kernel")
        || runtime.get("engineVersion").and_then(Value::as_str) != Some(env!("CARGO_PKG_VERSION"))
    {
        return Err(AppServerProcessError::ProtocolFault);
    }
    validate_capabilities(object.get("capabilities"))?;
    validate_remote_limits(object.get("limits"), local_limits)?;
    Ok(instance.to_owned())
}

impl Drop for SidecarSupervisor {
    /// Drop 也要收口完整 tree，不能只依赖 UI 调用 shutdown。
    fn drop(&mut self) {
        if let Ok(mut stopping) = self.stopping.lock() {
            *stopping = true;
        } else {
            // Drop 无法向 caller 返回错误，但仍继续关闭 session/process；不恢复 gate，
            // 避免 poisoned admission 被误认为一次正常 shutdown。
            tracing::error!("sidecar stopping gate is poisoned during drop");
        }
        self.fail_process_only();
        if !self.owns_resources() {
            let generation = self.lifecycle.generation();
            let _ = self.lifecycle.mark_exited(generation);
        } else {
            tracing::error!(
                generation = self.lifecycle.generation(),
                "sidecar drop could not confirm process-tree cleanup"
            );
        }
    }
}
