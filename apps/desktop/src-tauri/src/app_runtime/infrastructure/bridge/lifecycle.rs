// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// lifecycle 只负责 generation、启动状态转换和原生 Runtime owner 交接。

use super::*;
use ja_runtime::app_server_process::AppServerProcessError;

/// 设计原因：启动错误最终会被压成稳定 UI 错误码，因此原生日志只需保留闭集类别。
/// 不读取错误文本或携带数据，避免路径、协议载荷、challenge 和子进程参数进入诊断。
fn runtime_startup_error_variant(error: &AppServerProcessError) -> &'static str {
    match error {
        AppServerProcessError::InvalidConfig => "invalid_config",
        AppServerProcessError::InvalidTimeout => "invalid_timeout",
        AppServerProcessError::Codec(codec_error) => match codec_error {
            CodecError::UnexpectedEof => "codec_unexpected_eof",
            CodecError::PartialFrame => "codec_partial_frame",
            CodecError::EmptyFrame => "codec_empty_frame",
            CodecError::InvalidUtf8 => "codec_invalid_utf8",
            CodecError::InvalidJson => "codec_invalid_json",
            CodecError::DuplicateKey => "codec_duplicate_key",
            CodecError::NonObject => "codec_non_object",
            CodecError::InvalidEnvelope => "codec_invalid_envelope",
            CodecError::HandshakeFailed => "codec_handshake_failed",
            CodecError::InvalidErrorCatalog => "codec_invalid_error_catalog",
            CodecError::InvalidId => "codec_invalid_id",
            CodecError::InvalidLimit => "codec_invalid_limit",
            CodecError::FrameTooLarge { .. } => "codec_frame_too_large",
            CodecError::Io => "codec_io",
        },
        AppServerProcessError::QueueFull(_) => "queue_full",
        AppServerProcessError::QueueClosed(_) => "queue_closed",
        AppServerProcessError::PendingLimit => "pending_limit",
        AppServerProcessError::RequestLedgerExhausted => "request_ledger_exhausted",
        AppServerProcessError::DuplicateRequest => "duplicate_request",
        AppServerProcessError::DeadlineExceeded => "deadline_exceeded",
        AppServerProcessError::Cancelled => "cancelled",
        AppServerProcessError::SessionClosed => "session_closed",
        AppServerProcessError::NotReady => "not_ready",
        AppServerProcessError::Incompatible => "incompatible",
        AppServerProcessError::InvalidState => "invalid_state",
        AppServerProcessError::Spawn => "spawn",
        AppServerProcessError::ProcessTree => "process_tree",
        AppServerProcessError::ProcessExited => "process_exited",
        AppServerProcessError::ShuttingDown => "shutting_down",
        AppServerProcessError::HandshakeFailed => "handshake_failed",
        AppServerProcessError::ProtocolFault => "protocol_fault",
        AppServerProcessError::InvalidErrorCatalog => "invalid_error_catalog",
        AppServerProcessError::ShutdownTimeout => "shutdown_timeout",
        AppServerProcessError::Backoff { .. } => "backoff",
        AppServerProcessError::Faulted => "faulted",
    }
}

/// 设计原因：握手排障需要区分生命周期阶段，但记录原始错误会暴露协议或宿主数据。
/// phase 由调用点固定，variant 由枚举映射固定，generation 只用于定位本次启动尝试。
fn log_runtime_startup_failure(
    phase: &'static str,
    error: &AppServerProcessError,
    generation: u64,
) {
    tracing::warn!(
        target: "ja.diagnostics.runtime_startup",
        phase,
        variant = runtime_startup_error_variant(error),
        generation,
        "runtime startup failed"
    );
}

pub(super) struct EventDrain {
    pub(super) cancel: SyncSender<()>,
    pub(super) completion: Arc<Completion>,
    pub(super) generation: u64,
    pub(super) detached_event_generation: Arc<AtomicU64>,
    pub(super) join: Option<JoinHandle<()>>,
}

pub(super) struct EventDrainContext {
    pub(super) events: EventPump,
    pub(super) generation: u64,
    pub(super) client_generation: u64,
    pub(super) server_instance_id: String,
    pub(super) ready_token: String,
    pub(super) sink: EventSink,
    pub(super) terminal_fault: Arc<TerminalFault>,
    pub(super) current_generation: Arc<AtomicU64>,
    pub(super) cancel_receiver: Receiver<()>,
    pub(super) task_observations: Arc<TaskObservationRegistry>,
}

pub(super) struct EventDrainSpec {
    pub(super) events: EventPump,
    pub(super) generation: u64,
    pub(super) client_generation: u64,
    pub(super) server_instance_id: String,
    pub(super) ready_token: String,
    pub(super) sink: EventSink,
    pub(super) terminal_fault: Arc<TerminalFault>,
    pub(super) current_generation: Arc<AtomicU64>,
    pub(super) detached_event_generation: Arc<AtomicU64>,
    pub(super) task_observations: Arc<TaskObservationRegistry>,
}

impl EventDrain {
    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 只请求 cancellation 而不等待，使 EventPump 未及时观察信号时 supervisor 仍可关闭
    /// 阻塞 session。
    pub(super) fn request_stop(&self) {
        let _ = self.cancel.try_send(());
    }

    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 等待唯一 EventPump consumer，并复用共享绝对 deadline，禁止用第二个 timeout 延长退出。
    pub(super) fn wait_until(&self, deadline: Instant) -> bool {
        self.completion.wait_until(deadline)
    }

    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 只在确认完成后 join；未完成 worker 通过释放 handle 脱离，并报告为有界 shutdown fault。
    pub(super) fn finish(&mut self, deadline: Instant) -> Result<(), RuntimeCommandError> {
        if !self.completion.done.load(Ordering::Acquire) && !self.wait_until(deadline) {
            tracing::error!("runtime event worker exceeded its bounded stop deadline");
            self.detached_event_generation
                .store(self.generation, Ordering::Release);
            self.join.take();
            return Err(RuntimeCommandError::shutdown_timeout());
        }
        if let Some(join) = self.join.take() {
            join.join()
                .map_err(|_| RuntimeCommandError::unavailable())?;
        }
        Ok(())
    }
}

pub(super) struct RunningRuntime {
    pub(super) client: SharedAppServerClient,
    pub(super) event_drain: EventDrain,
    pub(super) generation: u64,
    pub(super) server_instance_id: String,
}

pub(super) enum QuarantineOwner {
    Runtime(Box<RunningRuntime>),
    Pending(Box<SharedAppServerClient>),
}

/// quarantine 的 owner 与公开状态属于同一提交单元；拆成两个锁会允许 reader 观察到
/// 新 owner 搭配旧状态，因此必须在一个 mutex 内原子更新。
pub(crate) struct ExitQuarantineState {
    pub(super) owner: Option<QuarantineOwner>,
    pub(super) status: RuntimeStatus,
}

/// actor 共享退出 deadline 到期后仍保留未确认 process owner，使后续 retry 可达；retry 只能由
/// managed bridge owner 执行，不能交给无界 detached worker，也不能靠丢弃 child handle。
pub(crate) struct ExitQuarantine {
    pub(crate) state: Mutex<ExitQuarantineState>,
    pub(super) faulted: AtomicBool,
}

impl ExitQuarantine {
    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 创建空 quarantine；没有 cleanup debt 时只能投影为 stopped。
    pub(crate) fn new() -> Self {
        Self {
            state: Mutex::new(ExitQuarantineState {
                owner: None,
                status: RuntimeStatus {
                    status: RuntimeStatusKind::Stopped,
                    generation: 0,
                    server_instance_id: None,
                },
            }),
            faulted: AtomicBool::new(false),
        }
    }

    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 保留完整 running runtime owner，并发布 crash 投影，供后续有界回收。
    pub(super) fn retain_runtime(
        &self,
        current: RunningRuntime,
        cleanup_fault: &CleanupFault,
    ) -> Result<(), RuntimeCommandError> {
        cleanup_fault.mark(current.generation);
        let status = RuntimeStatus {
            status: RuntimeStatusKind::Crashed,
            generation: current.generation,
            server_instance_id: Some(current.server_instance_id.clone()),
        };
        let mut state = self.state_guard()?;
        if state.owner.is_some() {
            self.faulted.store(true, Ordering::Release);
            return Err(RuntimeCommandError::unavailable());
        }
        state.owner = Some(QuarantineOwner::Runtime(Box::new(current)));
        state.status = status;
        Ok(())
    }

    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 退出前无法确认 handshake 清理时保留 pre-runtime supervisor，使精确进程 owner 可重试。
    pub(super) fn retain_pending(
        &self,
        client: SharedAppServerClient,
        cleanup_fault: &CleanupFault,
    ) -> Result<(), RuntimeCommandError> {
        let generation = client.generation();
        cleanup_fault.mark(generation);
        let mut state = self.state_guard()?;
        if state.owner.is_some() {
            self.faulted.store(true, Ordering::Release);
            return Err(RuntimeCommandError::unavailable());
        }
        state.owner = Some(QuarantineOwner::Pending(Box::new(client)));
        state.status = RuntimeStatus {
            status: RuntimeStatusKind::Crashed,
            generation,
            server_instance_id: None,
        };
        Ok(())
    }

    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// quarantine owner 仍存活期间持续投影 crash，禁止伪装为 stopped。
    pub(crate) fn state(
        &self,
        cleanup_fault: &CleanupFault,
    ) -> Result<RuntimeStatus, RuntimeCommandError> {
        let mut status = self.state_guard()?.status.clone();
        if cleanup_fault.is_pending() {
            status.status = RuntimeStatusKind::Crashed;
            if status.generation == 0 {
                status.generation = cleanup_fault.generation();
            }
        }
        Ok(status)
    }

    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 报告是否已无可供后续 retry 的 process owner；bridge 与 fault ledger 联合判定，
    /// 只有 owner 与 debt 同时清空后 successful retry 才能发布完成。
    pub(crate) fn is_empty(&self) -> bool {
        if self.faulted.load(Ordering::Acquire) {
            return false;
        }
        match self.state.lock() {
            Ok(state) => state.owner.is_none(),
            Err(_) => {
                self.faulted.store(true, Ordering::Release);
                false
            }
        }
    }

    /// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
    /// 使用 caller-owned 有界 deadline 重试 quarantine owner，不生成额外预算。
    pub(crate) fn retry_until(
        &self,
        deadline: Instant,
        cleanup_fault: &CleanupFault,
        runtime_control: &dyn RuntimeControlPort,
    ) -> Result<(), RuntimeCommandError> {
        let mut state = self.state_guard()?;
        let Some(owner) = state.owner.as_mut() else {
            return Ok(());
        };
        let generation = match &*owner {
            QuarantineOwner::Runtime(current) => current.generation,
            QuarantineOwner::Pending(client) => client.generation(),
        };
        let result = match owner {
            QuarantineOwner::Runtime(current) => {
                shutdown_components(current.as_mut(), deadline, runtime_control)
            }
            QuarantineOwner::Pending(client) => {
                disconnect_client_until(client.as_mut(), deadline, runtime_control)
            }
        };
        if let Err(error) = result {
            cleanup_fault.mark(generation);
            return Err(error);
        }
        cleanup_fault.clear(generation);
        state.owner = None;
        state.status = RuntimeStatus {
            status: RuntimeStatusKind::Stopped,
            generation: 0,
            server_instance_id: None,
        };
        Ok(())
    }

    /// quarantine 锁中毒表示 owner 与状态的原子提交可能中断；调用方只能得到稳定错误，
    /// 同时永久保持 exit debt，禁止把未知 owner 解释为空。
    fn state_guard(
        &self,
    ) -> Result<std::sync::MutexGuard<'_, ExitQuarantineState>, RuntimeCommandError> {
        if self.faulted.load(Ordering::Acquire) {
            return Err(RuntimeCommandError::unavailable());
        }
        self.state.lock().map_err(|_| {
            self.faulted.store(true, Ordering::Release);
            RuntimeCommandError::unavailable()
        })
    }
}

/// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
/// 只有 runtime/process/event owner 与全部 cleanup debt ledger 都确认为空，actor 才可完成。
pub(super) fn cleanup_confirmed(
    runtime: &Option<RunningRuntime>,
    pending_cleanup: &Option<SharedAppServerClient>,
    cleanup_fault: &CleanupFault,
    detached_event_generation: &AtomicU64,
) -> bool {
    runtime.is_none()
        && pending_cleanup.is_none()
        && !cleanup_fault.is_pending()
        && detached_event_generation.load(Ordering::Acquire) == 0
}

/// 显式收拢 start 依赖，防止任意 command 路径注入进程实现。
pub(super) struct StartRuntimeContext<'a> {
    pub(super) config: &'a LaunchConfig,
    pub(super) sink: &'a EventSink,
    pub(super) runtime: &'a mut Option<RunningRuntime>,
    pub(super) pending_cleanup: &'a mut Option<SharedAppServerClient>,
    pub(super) next_generation: &'a mut u64,
    pub(super) terminal_fault: &'a Arc<TerminalFault>,
    pub(super) detached_event_generation: &'a Arc<AtomicU64>,
    pub(super) cleanup_fault: &'a Arc<CleanupFault>,
    pub(super) current_generation: &'a Arc<AtomicU64>,
    pub(super) exit_control: &'a Arc<ExitControl>,
    pub(super) runtime_control: &'a Arc<dyn RuntimeControlPort>,
    pub(super) task_observations: &'a Arc<TaskObservationRegistry>,
}

/// 设计原因：该函数维护 generation 生命周期转换，启动失败与停止都必须留下可确认的 owner 状态。
/// 最多启动一个 Ja App Server sidecar generation；此边界返回不含 token 的 Host 状态前，
/// handshake 必须已消费 ready-token echo。WebView 在重载窗口内暂时不可达时，状态投影可由
/// 后续 `state` snapshot 补回，因此不能将投递失败升级为 sidecar 启动失败并销毁用户会话。
/// 启动错误日志只投影固定阶段、枚举类别和 generation，不记录 wire 内容或原始错误文本。
pub(super) fn start_runtime(
    context: StartRuntimeContext<'_>,
) -> Result<RuntimeStatus, RuntimeCommandError> {
    let StartRuntimeContext {
        config,
        sink,
        runtime,
        pending_cleanup,
        next_generation,
        terminal_fault,
        detached_event_generation,
        cleanup_fault,
        current_generation,
        exit_control,
        runtime_control,
        task_observations,
    } = context;
    if exit_control.is_cancelled() {
        return Err(RuntimeCommandError::shutdown_timeout());
    }
    if detached_event_generation.load(Ordering::Acquire) != 0
        || cleanup_fault.is_pending()
        || pending_cleanup.is_some()
    {
        return Err(RuntimeCommandError::shutdown_timeout());
    }
    if let Some(current) = runtime.as_mut() {
        return Ok(current_status(current));
    }
    if let Err(error) = emit_status(sink, RuntimeStatusKind::Starting, 0, None, "starting", None)
        && projection_failure_is_terminal(&error)
    {
        return Err(error);
    }
    runtime_control.record("start_client_connect");
    // 本地请求编号只约束并发启动；事件和状态必须统一使用后台的权威 generation。
    let host_generation = *next_generation;
    let mut client = runtime_control
        .connect_client(config, host_generation)
        .inspect_err(|_| runtime_control.record("start_client_connect_error"))?;
    if let Some(session) = client.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    if exit_control.is_cancelled() {
        retain_failed_client(
            client,
            pending_cleanup,
            cleanup_fault,
            cleanup_deadline(exit_control, config.shutdown_timeout),
            runtime_control.as_ref(),
        );
        return Err(RuntimeCommandError::shutdown_timeout());
    }
    let ready_token = client.ready_token_echo().to_owned();
    let generation = client.runtime_generation();
    let server_instance_id = client.server_instance_id().to_owned();
    let events = match client.take_event_pump() {
        Ok(events) => events,
        Err(error) => {
            log_runtime_startup_failure("event_pump_take", &error, generation);
            retain_failed_client(
                client,
                pending_cleanup,
                cleanup_fault,
                cleanup_deadline(exit_control, config.shutdown_timeout),
                runtime_control.as_ref(),
            );
            return Err(RuntimeCommandError::from_process(&error));
        }
    };
    *next_generation = next_generation.saturating_add(1);
    current_generation.store(generation, Ordering::Release);
    terminal_fault.clear();
    let event_drain = match spawn_event_drain(EventDrainSpec {
        events,
        generation,
        client_generation: generation,
        server_instance_id: server_instance_id.clone(),
        ready_token: ready_token.clone(),
        sink: sink.clone(),
        terminal_fault: Arc::clone(terminal_fault),
        current_generation: Arc::clone(current_generation),
        detached_event_generation: Arc::clone(detached_event_generation),
        task_observations: Arc::clone(task_observations),
    }) {
        Ok(event_drain) => event_drain,
        Err(error) => {
            current_generation.store(0, Ordering::Release);
            retain_failed_client(
                client,
                pending_cleanup,
                cleanup_fault,
                cleanup_deadline(exit_control, config.shutdown_timeout),
                runtime_control.as_ref(),
            );
            return Err(error);
        }
    };
    let current = RunningRuntime {
        client,
        event_drain,
        generation,
        server_instance_id: server_instance_id.clone(),
    };
    if let Err(error) = emit_status(
        sink,
        RuntimeStatusKind::Ready,
        generation,
        Some(&server_instance_id),
        "ready",
        Some(&ready_token),
    ) {
        if projection_failure_is_terminal(&error) {
            current_generation.store(0, Ordering::Release);
            let mut current = current;
            if let Err(cleanup_error) = shutdown_components(
                &mut current,
                cleanup_deadline(exit_control, config.shutdown_timeout),
                runtime_control.as_ref(),
            ) {
                cleanup_fault.mark(generation);
                *runtime = Some(current);
                tracing::error!(
                    ?cleanup_error,
                    generation,
                    "ready projection cleanup deferred"
                );
            } else {
                cleanup_fault.clear(generation);
            }
            return Err(error);
        }
        tracing::warn!(
            generation,
            "runtime ready status delivery failed; sidecar remains active"
        );
    }
    let status = RuntimeStatus {
        status: RuntimeStatusKind::Ready,
        generation,
        server_instance_id: Some(server_instance_id),
    };
    *runtime = Some(current);
    Ok(status)
}
