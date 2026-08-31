// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// actor 只负责有界命令准入、单 owner 串行化和完成确认；进程实现留在 infrastructure。

use super::*;

pub(super) type Reply<T> = SyncSender<Result<T, RuntimeCommandError>>;

pub(crate) enum BridgeCommand {
    Start {
        reply: Reply<RuntimeStatus>,
    },
    Stop {
        reply: Reply<RuntimeStatus>,
    },
    State {
        reply: Reply<RuntimeStatus>,
    },
    Config {
        method: String,
        params: Value,
        reply: Reply<Value>,
    },
    WorkspaceOpen {
        root: PathBuf,
        display_name: String,
        trust: String,
        reply: Reply<WorkspaceDto>,
    },
    GeneralWorkspaceRead {
        reply: Reply<Value>,
    },
    HealthRead {
        reply: Reply<()>,
    },
    TurnStart {
        params: Value,
        baseline: Option<Box<TurnChangeBaseline>>,
        reply: Reply<TurnAccepted>,
    },
    TurnCancel {
        params: Value,
        reply: Reply<TurnCancelResult>,
    },
    TurnQueuedInput {
        method: &'static str,
        params: Value,
        reply: Reply<TurnQueuedInputResult>,
    },
    TurnChangeSetRead {
        input: TurnChangeSetReadInput,
        reply: Reply<TurnChangeSetReadResult>,
    },
    ToolArtifactRead {
        input: ToolArtifactReadInput,
        reply: Reply<ToolArtifactReadResult>,
    },
    ApprovalRespond {
        input: ApprovalResponseInput,
        reply: Reply<()>,
    },
    AttachmentImport {
        workspace_id: String,
        input: AttachmentImportInput,
        reply: Reply<AttachmentMetadata>,
    },
    AttachmentDiscard {
        input: AttachmentDiscardInput,
        reply: Reply<()>,
    },
    History {
        method: HistoryMethod,
        params: Value,
        reply: Reply<Value>,
    },
    SettingsQuery {
        method: SettingsQueryMethod,
        params: Value,
        reply: Reply<Value>,
    },
}

/// Shutdown 使用独立单槽 control lane，避免排队中的数据命令延迟应用退出；actor 必须完成
/// 最终进程树清理后才确认请求。
pub(crate) struct ShutdownRequest {
    pub(crate) reply: Reply<()>,
    pub(crate) deadline: Instant,
}

pub(super) enum BridgeSignal {
    TurnTerminal {
        generation: u64,
        thread_id: String,
        turn_id: String,
        frame: RpcFrame,
    },
}

/// 终态 fault 保留在数据命令 lane 之外，避免通知突发让 actor 无法观察 crash cleanup。
pub(super) struct TerminalFault {
    pub(super) generation: AtomicU64,
    pub(super) reason: AtomicU8,
    pub(super) wake: SyncSender<()>,
}

impl TerminalFault {
    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 创建空 fault slot，并以 generation 作为线性化 key，防止跨代消费。
    pub(super) fn new(wake: SyncSender<()>) -> Self {
        Self {
            generation: AtomicU64::new(0),
            reason: AtomicU8::new(TERMINAL_NONE),
            wake,
        }
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 唤醒 actor 前先发布 generation fault；只有同一 generation 已写入 atomic pending 时，
    /// wake lane 满才不会丢失终态事实。
    pub(super) fn publish(&self, generation: u64, reason: u8) {
        self.reason.store(reason, Ordering::Release);
        let previous = self.generation.swap(generation, Ordering::AcqRel);
        if previous == generation {
            return;
        }
        match self.wake.try_send(()) {
            Ok(()) | Err(TrySendError::Full(())) => {}
            Err(TrySendError::Disconnected(())) => {
                tracing::error!(generation, "runtime terminal lane disconnected");
            }
        }
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 仅当 pending fault 仍属于已观察 generation 时才取出，避免旧 fault 清理新 sidecar。
    pub(super) fn take(&self, observed: u64) -> Option<u8> {
        let generation = self.generation.load(Ordering::Acquire);
        if generation == 0 || generation != observed {
            return None;
        }
        let reason = self.reason.load(Ordering::Acquire);
        self.generation
            .compare_exchange(generation, 0, Ordering::AcqRel, Ordering::Acquire)
            .ok()
            .map(|_| reason)
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// runtime 被显式停止或替换时清除对应 fault，不能跨 generation 清空。
    pub(super) fn clear(&self) {
        self.generation.store(0, Ordering::Release);
        self.reason.store(TERMINAL_NONE, Ordering::Release);
    }
}

/// condition-variable completion gate 允许 owner 无轮询等待 worker，避免把仍存活的
/// JoinHandle 丢成 detached thread。
pub(crate) struct Completion {
    pub(super) done: AtomicBool,
    pub(crate) lock: Mutex<()>,
    pub(super) wake: Condvar,
}

impl Completion {
    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 创建只允许单调进入 completed 的完成栅栏，终态不可逆转。
    pub(crate) fn new() -> Self {
        Self {
            done: AtomicBool::new(false),
            lock: Mutex::new(()),
            wake: Condvar::new(),
        }
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 唤醒有界 waiter 前先发布 worker 终止事实，保持 happens-before 顺序。
    pub(crate) fn mark_done(&self) {
        self.done.store(true, Ordering::Release);
        self.wake.notify_all();
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 等待 worker 发布完成或绝对 deadline 到期，不创建第二套 timeout 预算。
    pub(crate) fn wait_until(&self, deadline: Instant) -> bool {
        if self.done.load(Ordering::Acquire) {
            return true;
        }
        // Completion lock 中毒表示 worker 可能在发布终态前异常退出；此时不能仅凭 AtomicBool
        // 推断 Condvar 协议仍一致，调用方必须继续阻止应用退出。
        let mut guard = match self.lock.lock() {
            Ok(guard) => guard,
            Err(_) => return false,
        };
        while !self.done.load(Ordering::Acquire) {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let (next, wait) = match self.wake.wait_timeout(guard, remaining) {
                Ok(result) => result,
                Err(_) => return false,
            };
            guard = next;
            if wait.timed_out() && !self.done.load(Ordering::Acquire) {
                return false;
            }
        }
        true
    }
}

pub(crate) struct RuntimeBridgeInner {
    pub(crate) commands: SyncSender<BridgeCommand>,
    pub(crate) shutdown: SyncSender<ShutdownRequest>,
    pub(crate) completion: Arc<Completion>,
    pub(crate) detached_event_generation: Arc<AtomicU64>,
    pub(crate) cleanup_fault: Arc<CleanupFault>,
    pub(crate) exit_control: Arc<ExitControl>,
    pub(crate) quarantine: Arc<ExitQuarantine>,
    pub(crate) shutdown_completed: Arc<AtomicBool>,
    pub(crate) recovery_path: PathBuf,
    pub(crate) actor_join: Mutex<Option<JoinHandle<()>>>,
    pub(crate) runtime_control: Arc<dyn RuntimeControlPort>,
}

impl RuntimeBridgeInner {
    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 最后一个 managed-state owner 消失前发送高优先级 shutdown 并 join actor，
    /// 保证进程树始终有可追踪 owner。
    pub(crate) fn shutdown(&self) -> Result<(), RuntimeCommandError> {
        self.shutdown_with_retry(true)
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 复用 bridge 生命周期并消耗调用方绝对应用退出预算；内部 timeout 仅供非 Tauri 调用方
    /// 兜底，绝不能延长此 deadline。
    pub(super) fn shutdown_until(&self, deadline: Instant) -> Result<(), RuntimeCommandError> {
        self.shutdown_with_retry_until(true, Some(deadline))
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// Drop 清理不创建新 retry 预算；只有显式 Host exit 请求才能推进编号 attempt。
    pub(super) fn shutdown_without_retry(&self) -> Result<(), RuntimeCommandError> {
        self.shutdown_with_retry(false)
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 执行有界清理并可选启动显式 retry attempt；是否允许新用户操作由调用方决定。
    pub(super) fn shutdown_with_retry(&self, allow_retry: bool) -> Result<(), RuntimeCommandError> {
        self.shutdown_with_retry_until(allow_retry, None)
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 在可选 outer deadline 下执行统一 cleanup/retry 路径；direct caller 可使用默认预算，
    /// Tauri exit 则把同一绝对预算贯穿每个 bridge 阶段。
    pub(super) fn shutdown_with_retry_until(
        &self,
        allow_retry: bool,
        caller_deadline: Option<Instant>,
    ) -> Result<(), RuntimeCommandError> {
        self.runtime_control.record("inner_shutdown_begin");
        let needs_retry = self.completion.done.load(Ordering::Acquire)
            && (!self.shutdown_completed.load(Ordering::Acquire)
                || self.cleanup_fault.is_pending()
                || !self.quarantine.is_empty()
                || self.detached_event_generation.load(Ordering::Acquire) != 0);
        let explicit_retry = allow_retry && needs_retry;
        let attempt = if explicit_retry {
            self.exit_control.retry_until(caller_deadline)
        } else {
            self.exit_control.trigger_until(caller_deadline)
        };
        let deadline = attempt.deadline;
        if !self.completion.done.load(Ordering::Acquire) {
            let (reply, receiver) = mpsc::sync_channel(1);
            match self.shutdown.try_send(ShutdownRequest { reply, deadline }) {
                Ok(()) => {
                    self.runtime_control.record("inner_shutdown_sent");
                    let remaining = deadline.saturating_duration_since(Instant::now());
                    if remaining.is_zero() {
                        return Err(RuntimeCommandError::shutdown_timeout());
                    }
                    loop {
                        let remaining = deadline.saturating_duration_since(Instant::now());
                        if remaining.is_zero() {
                            self.runtime_control.record("inner_shutdown_reply_timeout");
                            /* actor 可能恰好在 deadline 边界发布两个生命周期完成标志，而 one-shot 回复尚不可见。
                             * 只能接受这份完整证明；未确认的边界属于 shutdown timeout，不能降级为通用 runtime
                             * availability 故障。 */
                            if self.completion.done.load(Ordering::Acquire)
                                && self.shutdown_completed.load(Ordering::Acquire)
                            {
                                break;
                            }
                            return Err(RuntimeCommandError::shutdown_timeout());
                        }
                        match receiver.recv_timeout(remaining.min(ACTOR_POLL_TIMEOUT)) {
                            Ok(Ok(())) => {
                                self.runtime_control.record("inner_shutdown_reply_ok");
                                break;
                            }
                            Ok(Err(error)) => {
                                self.runtime_control.record("inner_shutdown_reply_err");
                                return Err(error);
                            }
                            Err(RecvTimeoutError::Timeout)
                                if self.completion.done.load(Ordering::Acquire) =>
                            {
                                if self.shutdown_completed.load(Ordering::Acquire) {
                                    // actor 可能先观察到 exit cancellation，后看到请求；若已确认
                                    // clean completion，则与丢失的 acknowledgement 语义等价。
                                    self.runtime_control
                                        .record("inner_shutdown_completed_without_reply");
                                    break;
                                }
                                self.runtime_control
                                    .record("inner_shutdown_completed_unconfirmed");
                                return Err(RuntimeCommandError::shutdown_timeout());
                            }
                            Err(RecvTimeoutError::Timeout) => {}
                            Err(RecvTimeoutError::Disconnected) => {
                                self.runtime_control
                                    .record("inner_shutdown_reply_disconnected");
                                /*
                                 * actor 拥有 reply sender，因此干净退出可能在调用方观察最终 ACK 前断开 one-shot。
                                 * 此处复用 timeout 分支的同一权威完成证明；已断开但未确认的 actor 仍是 shutdown
                                 * 故障，绝不能隐式视为成功。
                                 */
                                if self.completion.done.load(Ordering::Acquire) {
                                    if self.shutdown_completed.load(Ordering::Acquire) {
                                        break;
                                    }
                                    return Err(RuntimeCommandError::shutdown_timeout());
                                }
                                return Err(RuntimeCommandError::unavailable());
                            }
                        }
                    }
                }
                Err(TrySendError::Full(_)) => {
                    self.runtime_control.record("inner_shutdown_lane_full");
                    if !self.completion.wait_until(deadline) {
                        self.runtime_control
                            .record("inner_shutdown_completion_timeout");
                        return Err(RuntimeCommandError::queue_full());
                    }
                }
                Err(TrySendError::Disconnected(_)) => {
                    self.runtime_control
                        .record("inner_shutdown_lane_disconnected");
                    if !self.completion.done.load(Ordering::Acquire) {
                        return Err(RuntimeCommandError::unavailable());
                    }
                }
            }
        }
        if self.cleanup_fault.is_pending() {
            let deadline = effective_shutdown_deadline(deadline, &self.exit_control);
            if let Err(error) = self.quarantine.retry_until(
                deadline,
                &self.cleanup_fault,
                self.runtime_control.as_ref(),
            ) {
                tracing::error!(?error, "quarantined runtime cleanup retry failed");
            }
            if self.quarantine.is_empty()
                && !self.cleanup_fault.is_pending()
                && self
                    .exit_control
                    .deadline()
                    .is_none_or(|active| Instant::now() < active)
            {
                self.shutdown_completed.store(true, Ordering::Release);
            }
        }
        // 延迟清理可能在首个 caller 已超时后才让所有真实 owner 为空；只有后续显式 retry
        // 可把已确认 empty 提升为 completed，首个 attempt 必须保持失败以满足 Tauri
        // prevent/retry 契约。
        if can_promote_completed_retry(
            explicit_retry,
            self.completion.done.load(Ordering::Acquire),
            self.quarantine.is_empty(),
            self.cleanup_fault.is_pending(),
            self.detached_event_generation.load(Ordering::Acquire),
            deadline,
        ) {
            self.shutdown_completed.store(true, Ordering::Release);
        }
        if !self.shutdown_completed.load(Ordering::Acquire) {
            self.runtime_control.record("inner_shutdown_not_completed");
            return Err(RuntimeCommandError::shutdown_timeout());
        }
        if self.detached_event_generation.load(Ordering::Acquire) != 0 {
            self.runtime_control.record("inner_shutdown_event_detached");
            return Err(RuntimeCommandError::shutdown_timeout());
        }
        if self.cleanup_fault.is_pending() {
            self.runtime_control.record("inner_shutdown_cleanup_fault");
            return Err(RuntimeCommandError::shutdown_timeout());
        }
        if !self.completion.wait_until(deadline) {
            self.runtime_control
                .record("inner_shutdown_completion_wait_timeout");
            return Err(RuntimeCommandError::unavailable());
        }
        // Join slot 中毒后无法证明 handle 是否已被另一条清理路径取走；恢复 guard 会把未知
        // owner 误判为可安全退出，因此稳定关闭失败并保留 recovery marker。
        let handle = self
            .actor_join
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())?
            .take();
        if let Some(handle) = handle {
            handle
                .join()
                .map_err(|_| RuntimeCommandError::unavailable())?;
        }
        if let Err(error) = clear_recovery_record(&self.recovery_path) {
            tracing::error!(
                ?error,
                "runtime recovery marker removal failed after clean exit"
            );
            return Err(RuntimeCommandError::recovery_required());
        }
        self.runtime_control.record("inner_shutdown_complete");
        Ok(())
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 报告 Tauri 是否可接受最终 Exit，前提是 actor、event worker 和 sidecar owner 都已清空。
    pub(crate) fn exit_ready(&self) -> bool {
        let actor_joined = self
            .actor_join
            .lock()
            .map(|handle| handle.is_none())
            .unwrap_or(false);
        actor_joined
            && self.completion.done.load(Ordering::Acquire)
            && self.shutdown_completed.load(Ordering::Acquire)
            && !self.cleanup_fault.is_pending()
            && self.detached_event_generation.load(Ordering::Acquire) == 0
            && self.quarantine.is_empty()
    }

    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// 平台无法继续阻止退出且清理未确认时，持久化脱敏 forced-exit marker 供下次恢复。
    pub(super) fn record_forced_exit(&self) {
        let attempt = self.exit_control.attempt();
        if let Err(error) = persist_recovery_record(
            &self.recovery_path,
            attempt.map(|value| value.id).unwrap_or(0),
            self.cleanup_fault.generation(),
        ) {
            tracing::error!(?error, "runtime recovery marker write failed");
        }
    }
}

impl Drop for RuntimeBridgeInner {
    /// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
    /// Drop 仅是最终保险；正常 Tauri 退出会显式调用 `shutdown` 并向 Host 返回有界清理结果。
    fn drop(&mut self) {
        if let Err(error) = self.shutdown_without_retry() {
            tracing::error!(?error, "runtime bridge drop cleanup was not confirmed");
            self.record_forced_exit();
        }
    }
}

pub(super) struct ActorContext {
    pub(super) config: LaunchConfig,
    pub(super) sink: EventSink,
    pub(super) signal_sender: SyncSender<BridgeSignal>,
    pub(super) current_generation: Arc<AtomicU64>,
    pub(super) terminal_fault: Arc<TerminalFault>,
    pub(super) detached_event_generation: Arc<AtomicU64>,
    pub(super) cleanup_fault: Arc<CleanupFault>,
    pub(super) exit_control: Arc<ExitControl>,
    pub(super) quarantine: Arc<ExitQuarantine>,
    pub(super) shutdown_completed: Arc<AtomicBool>,
    pub(super) completion: Arc<Completion>,
    pub(super) runtime_control: Arc<dyn RuntimeControlPort>,
}

/// 设计原因：该函数位于单 owner actor 边界，必须保持命令顺序、终态可见性与有界等待。
/// 串行执行 supervisor 操作，同时独立轮询 EventPump，避免请求阻塞事件消费。
pub(super) fn actor_loop(
    context: ActorContext,
    command_receiver: Receiver<BridgeCommand>,
    shutdown_receiver: Receiver<ShutdownRequest>,
    signal_receiver: Receiver<BridgeSignal>,
    terminal_receiver: Receiver<()>,
) {
    context
        .runtime_control
        .set_phase(RuntimeControlPhase::ActorEnter);
    context.runtime_control.record("actor_enter");
    context.runtime_control.before_actor_loop();
    let mut runtime: Option<RunningRuntime> = None;
    let mut pending_cleanup: Option<SidecarSupervisor> = None;
    let mut next_generation = 1_u64;
    let mut pending_turn_changes = HashMap::<String, TurnChangeBaseline>::new();
    // host-owned identity 与 baseline 分开保存，使异常丢失 baseline 时仍能提交显式
    // capture_failed；没有 host identity 的恢复/外部 Turn 不能被 Rust 越权改写。
    let mut host_turn_workspaces = HashMap::<String, String>::new();
    let mut shutdown_completed = false;
    loop {
        if let Ok(request) = shutdown_receiver.try_recv() {
            context
                .runtime_control
                .set_phase(RuntimeControlPhase::ShutdownReceived);
            context.runtime_control.record("actor_shutdown_received");
            let deadline = effective_shutdown_deadline(request.deadline, &context.exit_control);
            let result = stop_runtime(
                &context.sink,
                &mut runtime,
                &mut pending_cleanup,
                &context.current_generation,
                &context.terminal_fault,
                &context.cleanup_fault,
                deadline,
                &context.exit_control,
                context.runtime_control.as_ref(),
            );
            context.runtime_control.record(if result.is_ok() {
                "actor_shutdown_cleanup_ok"
            } else {
                "actor_shutdown_cleanup_err"
            });
            let cleanup_confirmed = cleanup_confirmed(
                &runtime,
                &pending_cleanup,
                &context.cleanup_fault,
                &context.detached_event_generation,
            );
            if cleanup_confirmed && Instant::now() < deadline {
                context
                    .runtime_control
                    .set_phase(RuntimeControlPhase::ShutdownConfirmed);
                context.runtime_control.record("actor_shutdown_confirmed");
                shutdown_completed = true;
                context.shutdown_completed.store(true, Ordering::Release);
            }
            // 确认请求前先发布 lifecycle `shutdown_completed`；独立 actor Completion gate
            // 在 reply 后标记，使 caller 仍等待 join ownership，且不混淆两个状态转换。
            let _ = request.reply.send(result.map(|_| ()));
            if cleanup_confirmed {
                break;
            }
        }
        if let Some(deadline) = context.exit_control.deadline() {
            if cleanup_confirmed(
                &runtime,
                &pending_cleanup,
                &context.cleanup_fault,
                &context.detached_event_generation,
            ) && Instant::now() < deadline
            {
                shutdown_completed = true;
                context.shutdown_completed.store(true, Ordering::Release);
                break;
            }
            if Instant::now() >= deadline {
                break;
            }
            if let Err(error) = stop_runtime(
                &context.sink,
                &mut runtime,
                &mut pending_cleanup,
                &context.current_generation,
                &context.terminal_fault,
                &context.cleanup_fault,
                deadline,
                &context.exit_control,
                context.runtime_control.as_ref(),
            ) {
                tracing::error!(?error, "runtime exit cleanup retry was not confirmed");
            }
            if !cleanup_confirmed(
                &runtime,
                &pending_cleanup,
                &context.cleanup_fault,
                &context.detached_event_generation,
            ) {
                thread::park_timeout(
                    deadline
                        .saturating_duration_since(Instant::now())
                        .min(ACTOR_POLL_TIMEOUT),
                );
            }
            continue;
        }
        drain_signals(
            &context.config,
            &context.sink,
            &mut runtime,
            &signal_receiver,
            &mut pending_turn_changes,
            &mut host_turn_workspaces,
            &context.exit_control,
        );
        drain_terminal_fault(
            TerminalCleanupContext {
                config: &context.config,
                sink: &context.sink,
                runtime: &mut runtime,
                current_generation: &context.current_generation,
                terminal_fault: &context.terminal_fault,
                cleanup_fault: &context.cleanup_fault,
                exit_control: &context.exit_control,
                runtime_control: &context.runtime_control,
            },
            &terminal_receiver,
        );
        if runtime.is_none() {
            pending_turn_changes.clear();
            host_turn_workspaces.clear();
        }
        match command_receiver.recv_timeout(ACTOR_POLL_TIMEOUT) {
            Ok(BridgeCommand::Start { reply }) => {
                context
                    .runtime_control
                    .set_phase(RuntimeControlPhase::StartReceived);
                context.runtime_control.record("actor_start_received");
                let result = start_runtime(StartRuntimeContext {
                    config: &context.config,
                    sink: &context.sink,
                    runtime: &mut runtime,
                    pending_cleanup: &mut pending_cleanup,
                    next_generation: &mut next_generation,
                    signal_sender: &context.signal_sender,
                    terminal_fault: &context.terminal_fault,
                    detached_event_generation: &context.detached_event_generation,
                    cleanup_fault: &context.cleanup_fault,
                    current_generation: &context.current_generation,
                    exit_control: &context.exit_control,
                    runtime_control: &context.runtime_control,
                });
                if result.is_err() {
                    context
                        .runtime_control
                        .set_phase(RuntimeControlPhase::StartFailed);
                    context.runtime_control.before_start_failure_reply();
                }
                context.runtime_control.record(if result.is_ok() {
                    "actor_start_reply_ok"
                } else {
                    "actor_start_reply_err"
                });
                let _ = reply.send(result);
            }
            Ok(BridgeCommand::Stop { reply }) => {
                context
                    .runtime_control
                    .set_phase(RuntimeControlPhase::StopReceived);
                context.runtime_control.record("actor_stop_received");
                let result = stop_runtime(
                    &context.sink,
                    &mut runtime,
                    &mut pending_cleanup,
                    &context.current_generation,
                    &context.terminal_fault,
                    &context.cleanup_fault,
                    shutdown_deadline(context.config.shutdown_timeout),
                    &context.exit_control,
                    context.runtime_control.as_ref(),
                );
                context.runtime_control.record(if result.is_ok() {
                    "actor_stop_reply_ok"
                } else {
                    "actor_stop_reply_err"
                });
                context
                    .runtime_control
                    .set_phase(RuntimeControlPhase::StopReplied);
                let _ = reply.send(result);
            }
            Ok(BridgeCommand::State { reply }) => {
                let _ = reply.send(Ok(snapshot(
                    &mut runtime,
                    &context.cleanup_fault,
                    pending_cleanup.as_ref(),
                )));
                context.runtime_control.state_command_processed();
            }
            Ok(BridgeCommand::Config {
                method,
                params,
                reply,
            }) => {
                let result = config_request_runtime(
                    &context.config,
                    &mut runtime,
                    method,
                    params,
                    &context.exit_control,
                );
                let _ = reply.send(result);
            }
            Ok(BridgeCommand::WorkspaceOpen {
                root,
                display_name,
                trust,
                reply,
            }) => {
                let _ = reply.send(workspace_open_runtime(
                    &context.config,
                    &mut runtime,
                    root,
                    display_name,
                    trust,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::GeneralWorkspaceRead { reply }) => {
                let _ = reply.send(general_workspace_read_runtime(
                    &context.config,
                    &mut runtime,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::HealthRead { reply }) => {
                let _ = reply.send(health_read_runtime(
                    &context.config,
                    &mut runtime,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::TurnStart {
                params,
                baseline,
                reply,
            }) => {
                let mut baseline = baseline.map(|value| *value);
                if let Some(candidate) = baseline.as_ref() {
                    let workspace_id = candidate.workspace_id().to_owned();
                    let concurrent = pending_turn_changes
                        .values()
                        .any(|value| value.workspace_id() == workspace_id);
                    if concurrent {
                        for value in pending_turn_changes.values_mut() {
                            if value.workspace_id() == workspace_id {
                                *value = TurnChangeBaseline::concurrent(workspace_id.clone());
                            }
                        }
                        baseline = Some(TurnChangeBaseline::concurrent(workspace_id));
                    }
                }
                let result =
                    turn_runtime(&context.config, &mut runtime, params, &context.exit_control);
                if let (Ok(accepted), Some(baseline)) = (&result, baseline) {
                    host_turn_workspaces
                        .insert(accepted.turn_id.clone(), baseline.workspace_id().to_owned());
                    pending_turn_changes.insert(accepted.turn_id.clone(), baseline);
                }
                let _ = reply.send(result);
            }
            Ok(BridgeCommand::TurnCancel { params, reply }) => {
                let _ = reply.send(turn_cancel_runtime(
                    &context.config,
                    &mut runtime,
                    params,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::TurnQueuedInput {
                method,
                params,
                reply,
            }) => {
                let _ = reply.send(turn_queued_input_runtime(
                    &context.config,
                    &mut runtime,
                    method,
                    params,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::TurnChangeSetRead { input, reply }) => {
                let _ = reply.send(turn_change_set_read_runtime(
                    &context.config,
                    &mut runtime,
                    input,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::ToolArtifactRead { input, reply }) => {
                let _ = reply.send(tool_artifact_read_runtime(
                    &context.config,
                    &mut runtime,
                    input,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::ApprovalRespond { input, reply }) => {
                let _ = reply.send(respond_approval(
                    &context.config,
                    &mut runtime,
                    input,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::AttachmentImport {
                workspace_id,
                input,
                reply,
            }) => {
                let _ = reply.send(attachment_import_runtime(
                    &context.config,
                    &mut runtime,
                    workspace_id,
                    input,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::AttachmentDiscard { input, reply }) => {
                let _ = reply.send(attachment_discard_runtime(
                    &context.config,
                    &mut runtime,
                    input,
                    &context.exit_control,
                ));
            }
            Ok(BridgeCommand::History {
                method,
                params,
                reply,
            }) => {
                let result = history_request_runtime(
                    &context.config,
                    &mut runtime,
                    method,
                    params,
                    &context.exit_control,
                );
                if let Err(error) = &result {
                    tracing::warn!(
                        history_method = method.wire_name(),
                        history_stage = "actor_reply",
                        error_code = error.code,
                        "history bridge command failed"
                    );
                }
                let _ = reply.send(result);
            }
            Ok(BridgeCommand::SettingsQuery {
                method,
                params,
                reply,
            }) => {
                let _ = reply.send(settings_query_runtime(
                    &context.config,
                    &mut runtime,
                    method,
                    params,
                    &context.exit_control,
                ));
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => {
                context.runtime_control.record("actor_command_disconnected");
                context.exit_control.trigger();
                break;
            }
        }
    }
    if !shutdown_completed {
        let deadline = context.exit_control.trigger().deadline;
        while !cleanup_confirmed(
            &runtime,
            &pending_cleanup,
            &context.cleanup_fault,
            &context.detached_event_generation,
        ) && Instant::now() < deadline
        {
            if let Err(error) = stop_runtime(
                &context.sink,
                &mut runtime,
                &mut pending_cleanup,
                &context.current_generation,
                &context.terminal_fault,
                &context.cleanup_fault,
                deadline,
                &context.exit_control,
                context.runtime_control.as_ref(),
            ) {
                tracing::error!(?error, "runtime actor cleanup retry was not confirmed");
            }
            if !cleanup_confirmed(
                &runtime,
                &pending_cleanup,
                &context.cleanup_fault,
                &context.detached_event_generation,
            ) {
                thread::park_timeout(
                    deadline
                        .saturating_duration_since(Instant::now())
                        .min(ACTOR_POLL_TIMEOUT),
                );
            }
        }
        if !cleanup_confirmed(
            &runtime,
            &pending_cleanup,
            &context.cleanup_fault,
            &context.detached_event_generation,
        ) {
            if let Some(current) = runtime.take()
                && let Err(error) = context
                    .quarantine
                    .retain_runtime(current, &context.cleanup_fault)
            {
                tracing::error!(?error, "runtime quarantine ownership transfer failed");
                shutdown_completed = false;
            }
            if let Some(supervisor) = pending_cleanup.take()
                && let Err(error) = context
                    .quarantine
                    .retain_pending(supervisor, &context.cleanup_fault)
            {
                tracing::error!(?error, "pending runtime quarantine transfer failed");
                shutdown_completed = false;
            }
        }
        if cleanup_confirmed(
            &runtime,
            &pending_cleanup,
            &context.cleanup_fault,
            &context.detached_event_generation,
        ) && Instant::now() < deadline
        {
            context.shutdown_completed.store(true, Ordering::Release);
        }
    }
    context.terminal_fault.clear();
    context.runtime_control.record(if shutdown_completed {
        "actor_completion_shutdown"
    } else {
        "actor_completion_unconfirmed"
    });
    context
        .runtime_control
        .set_phase(RuntimeControlPhase::ActorCompleted);
    context.completion.mark_done();
}
