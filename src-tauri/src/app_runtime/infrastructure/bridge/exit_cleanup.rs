// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// exit cleanup 统一持有绝对 deadline、清理债务、回收与恢复记录。

use super::*;

/// 按 generation 保留 cleanup failure，直到后续 attempt 证明同一进程树和 event worker 确实
/// 消失；actor 不能因自身 loop 已返回就把失败的 kill/reap 标记为 completed。
pub(crate) struct CleanupFault {
    pub(super) generation: AtomicU64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct ExitAttempt {
    pub(super) id: u64,
    pub(crate) deadline: Instant,
}

pub(super) type ExitCancellationHook = Arc<dyn Fn(Instant) + Send + Sync + 'static>;

/// 每个显式 exit attempt 保存一个不可变 deadline；只有前一个 actor attempt 完成后 retry 才
/// 创建新编号，因此 callback 不能静默延长 in-flight exit。
pub(crate) struct ExitControl {
    pub(crate) attempt: Mutex<Option<ExitAttempt>>,
    pub(super) cancelled: AtomicBool,
    pub(super) faulted: AtomicBool,
    pub(super) cancellation_hook: Mutex<Option<ExitCancellationHook>>,
    pub(super) timeout: Duration,
}

pub(super) struct SessionCancellationGuard<'a> {
    pub(super) control: &'a ExitControl,
}

impl<'a> SessionCancellationGuard<'a> {
    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 将 session cancellation hook 限定到一个 blocking operation，避免后续 generation 继承
    /// 陈旧的退出回调。
    pub(super) fn new(control: &'a ExitControl) -> Self {
        Self { control }
    }
}

impl Drop for SessionCancellationGuard<'_> {
    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 只清除本 operation 持有的 hook；共享 attempt 和 deadline 在显式 retry 前保持不可变。
    fn drop(&mut self) {
        self.control.clear_session();
    }
}

impl ExitControl {
    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 创建尚无 attempt 与 session cancellation hook 的 exit gate。
    pub(crate) fn new(timeout: Duration) -> Self {
        Self {
            attempt: Mutex::new(None),
            cancelled: AtomicBool::new(false),
            faulted: AtomicBool::new(false),
            cancellation_hook: Mutex::new(None),
            timeout,
        }
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 只启动一次首个 exit attempt 并唤醒 active session；caller deadline 只能缩短内部预算。
    pub(crate) fn trigger(&self) -> ExitAttempt {
        self.trigger_until(None)
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 应用 outer absolute deadline，重复 exit callback 不得延长已在执行的 attempt；caller
    /// 持有整体应用预算，因此 gate 保存该预算与 bridge 常规 shutdown timeout 的最小值。
    pub(super) fn trigger_until(&self, caller_deadline: Option<Instant>) -> ExitAttempt {
        let mut attempt = match self.attempt.lock() {
            Ok(attempt) => attempt,
            Err(_) => return self.mark_poisoned_exit(0),
        };
        if let Some(current) = *attempt {
            self.cancelled.store(true, Ordering::Release);
            let bounded = caller_deadline.map_or(current.deadline, |deadline| {
                std::cmp::min(current.deadline, deadline)
            });
            if bounded == current.deadline {
                return current;
            }
            let shortened = ExitAttempt {
                id: current.id,
                deadline: bounded,
            };
            *attempt = Some(shortened);
            drop(attempt);
            self.invoke_cancellation_hook(shortened.deadline);
            return self.fail_closed_attempt(shortened);
        }
        let current = ExitAttempt {
            id: 1,
            deadline: bounded_shutdown_deadline(self.timeout, caller_deadline),
        };
        *attempt = Some(current);
        self.cancelled.store(true, Ordering::Release);
        drop(attempt);
        self.invoke_cancellation_hook(current.deadline);
        self.fail_closed_attempt(current)
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 启动 retry 时新内部预算仍受 caller absolute deadline 限制；仅当前一 actor attempt 已完成
    /// 才允许 retry，因此不能延长 in-flight attempt。
    pub(super) fn retry_until(&self, caller_deadline: Option<Instant>) -> ExitAttempt {
        let mut attempt = match self.attempt.lock() {
            Ok(attempt) => attempt,
            Err(_) => return self.mark_poisoned_exit(0),
        };
        let id = attempt.map_or(1, |current| current.id.saturating_add(1));
        let current = ExitAttempt {
            id,
            deadline: bounded_shutdown_deadline(self.timeout, caller_deadline),
        };
        *attempt = Some(current);
        self.cancelled.store(true, Ordering::Release);
        drop(attempt);
        self.invoke_cancellation_hook(current.deadline);
        self.fail_closed_attempt(current)
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 返回 active attempt，不创建新 operation 预算。
    pub(super) fn attempt(&self) -> Option<ExitAttempt> {
        if self.faulted.load(Ordering::Acquire) {
            return Some(self.poisoned_attempt(0));
        }
        match self.attempt.lock() {
            Ok(attempt) => *attempt,
            Err(_) => Some(self.mark_poisoned_exit(0)),
        }
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 返回 actor poll 与 cleanup 共用的 active absolute deadline。
    pub(crate) fn deadline(&self) -> Option<Instant> {
        self.attempt().map(|attempt| attempt.deadline)
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// exit 请求后让同步 operation 停止准入新工作。
    pub(crate) fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Acquire) || self.faulted.load(Ordering::Acquire)
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 先在仍可写入的 session 上确认服务端业务清理，再关闭管道唤醒剩余等待；
    /// 直接先关 session 会使 supervisor 的 shutdown RPC 永远无法发送，临时侧聊只能遗留到下次启动。
    pub(super) fn attach_session(&self, session: Session) {
        let hook: ExitCancellationHook = Arc::new(move |deadline| {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if !remaining.is_zero() {
                let _ = SidecarSupervisor::request_session_shutdown_until(&session, deadline);
            }
            if let Err(error) = SidecarSupervisor::close_session_until(&session, deadline) {
                tracing::debug!(
                    ?error,
                    "session cancellation did not finish before exit deadline"
                );
            }
        });
        let already_cancelled = self.cancelled.load(Ordering::Acquire);
        match self.cancellation_hook.lock() {
            Ok(mut slot) => *slot = Some(hook.clone()),
            Err(_) => {
                // Hook slot 中毒时无法证明旧 session 是否仍被持有；立即关闭刚注册的 session，
                // 同时使整个 exit gate 失效，避免 actor 继续准入新 operation。
                let attempt = self.mark_poisoned_exit(0);
                hook(attempt.deadline);
                return;
            }
        }
        if already_cancelled && let Some(deadline) = self.deadline() {
            hook(deadline);
        }
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 移除已完成 operation 的 hook，防止 stale cancellation callback 关闭后续 generation。
    pub(super) fn clear_session(&self) {
        match self.cancellation_hook.lock() {
            Ok(mut hook) => *hook = None,
            Err(_) => {
                self.mark_poisoned_exit(0);
            }
        }
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 在 mutex 外调用复制的 hook，避免 close/join 阻塞后续 retry 注册。
    pub(super) fn invoke_cancellation_hook(&self, deadline: Instant) {
        let hook = match self.cancellation_hook.lock() {
            Ok(hook) => hook.clone(),
            Err(_) => {
                self.mark_poisoned_exit(0);
                return;
            }
        };
        if let Some(hook) = hook {
            hook(deadline);
        }
    }

    /// attempt 或 hook 锁中毒意味着退出状态机可能只提交了一半；这里单调发布 fault，
    /// 并把共享 deadline 收紧到当前时刻，使 actor 立即停止准入而不是恢复未知 guard。
    fn mark_poisoned_exit(&self, id: u64) -> ExitAttempt {
        self.cancelled.store(true, Ordering::Release);
        self.faulted.store(true, Ordering::Release);
        self.poisoned_attempt(id)
    }

    /// 构造只用于 fail-closed 路径的已到期 attempt；保留 id 仅供脱敏 recovery 关联，
    /// 不允许基于中毒状态递增或推断正常生命周期。
    fn poisoned_attempt(&self, id: u64) -> ExitAttempt {
        ExitAttempt {
            id,
            deadline: Instant::now(),
        }
    }

    /// hook 调用期间若发现共享状态已经中毒，返回已到期 attempt，确保上层不会继续使用
    /// 先前计算的较宽 deadline。
    fn fail_closed_attempt(&self, attempt: ExitAttempt) -> ExitAttempt {
        if self.faulted.load(Ordering::Acquire) {
            self.poisoned_attempt(attempt.id)
        } else {
            attempt
        }
    }
}

impl CleanupFault {
    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 创建空 fault ledger；零值明确表示没有 cleanup debt。
    pub(crate) fn new() -> Self {
        Self {
            generation: AtomicU64::new(0),
        }
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 记录进程清理仍待确认的 generation。
    pub(super) fn mark(&self, generation: u64) {
        self.generation.store(generation, Ordering::Release);
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 报告先前 cleanup attempt 是否仍未确认。
    pub(super) fn is_pending(&self) -> bool {
        self.generation.load(Ordering::Acquire) != 0
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 返回 debt generation，防止 state projection 把未确认 process owner 降级为误导性的
    /// generation 为零的停止状态。
    pub(super) fn generation(&self) -> u64 {
        self.generation.load(Ordering::Acquire)
    }

    /// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
    /// 只有 owner generation 完成全部 cleanup 阶段后才清除 debt。
    pub(super) fn clear(&self, generation: u64) {
        let _ =
            self.generation
                .compare_exchange(generation, 0, Ordering::AcqRel, Ordering::Acquire);
    }
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 在同一 absolute deadline 下停止 event worker 与 supervisor；允许 process close 唤醒未观察
/// cancel 的 worker。
pub(super) fn shutdown_components(
    current: &mut RunningRuntime,
    deadline: Instant,
    runtime_control: &dyn RuntimeControlPort,
) -> Result<(), RuntimeCommandError> {
    current.event_drain.request_stop();
    let grace_deadline = std::cmp::min(
        deadline,
        Instant::now()
            .checked_add(EVENT_CANCEL_GRACE)
            .unwrap_or(deadline),
    );
    let _ = current.event_drain.wait_until(grace_deadline);
    let remaining = deadline.saturating_duration_since(Instant::now());
    let supervisor_result = if remaining.is_zero() {
        Err(RuntimeCommandError::shutdown_timeout())
    } else {
        runtime_control.shutdown_supervisor_until(&mut current.supervisor, deadline)
    };
    let event_result = current.event_drain.finish(deadline);
    supervisor_result.and(event_result)
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 按 event-worker、进程树顺序执行有界 shutdown；显式 owner 引用把 cleanup mutation 限定在
/// 该窄 bridge primitive，避免只为一次共享 deadline 读取制造状态 wrapper。
#[allow(clippy::too_many_arguments)]
pub(super) fn stop_runtime(
    sink: &EventSink,
    runtime: &mut Option<RunningRuntime>,
    pending_cleanup: &mut Option<SidecarSupervisor>,
    current_generation: &Arc<AtomicU64>,
    terminal_fault: &Arc<TerminalFault>,
    cleanup_fault: &Arc<CleanupFault>,
    deadline: Instant,
    exit_control: &ExitControl,
    runtime_control: &dyn RuntimeControlPort,
) -> Result<RuntimeStatus, RuntimeCommandError> {
    let deadline = effective_shutdown_deadline(deadline, exit_control);
    if exit_control
        .deadline()
        .is_some_and(|active| Instant::now() >= active)
    {
        return Err(RuntimeCommandError::shutdown_timeout());
    }
    if let Some(mut supervisor) = pending_cleanup.take() {
        let generation = supervisor.generation();
        let mut cleanup_result =
            shutdown_supervisor_until(&mut supervisor, deadline, runtime_control);
        if cleanup_result.is_ok()
            && exit_control
                .deadline()
                .is_some_and(|active| Instant::now() >= active)
        {
            cleanup_result = Err(RuntimeCommandError::shutdown_timeout());
        }
        if let Err(error) = cleanup_result {
            cleanup_fault.mark(generation);
            *pending_cleanup = Some(supervisor);
            return Err(error);
        }
        cleanup_fault.clear(generation);
    }
    let Some(mut current) = runtime.take() else {
        if cleanup_fault.is_pending() {
            return Err(RuntimeCommandError::shutdown_timeout());
        }
        current_generation.store(0, Ordering::Release);
        terminal_fault.clear();
        emit_status(sink, RuntimeStatusKind::Stopped, 0, None, "stopped", None)?;
        return Ok(RuntimeStatus {
            status: RuntimeStatusKind::Stopped,
            generation: 0,
            server_instance_id: None,
        });
    };
    current_generation.store(0, Ordering::Release);
    terminal_fault.clear();
    let generation = current.generation;
    let server_instance_id = current.server_instance_id.clone();
    let mut result = shutdown_components(&mut current, deadline, runtime_control);
    // cleanup 执行期间 caller 可缩短共享 deadline；若 cleanup 越过边界才返回，则保留
    // unconfirmed owner，使 caller 继续阻止退出并可显式 retry。
    if result.is_ok()
        && exit_control
            .deadline()
            .is_some_and(|active| Instant::now() >= active)
    {
        result = Err(RuntimeCommandError::shutdown_timeout());
    }
    if result.is_err() {
        // supervisor 与 worker handle 继续由 actor 持有，使后续高优先级 shutdown 可重试同一
        // generation；actor 终止或 status emission 都不能清除该 fault。
        cleanup_fault.mark(current.generation);
        *runtime = Some(current);
    } else {
        cleanup_fault.clear(generation);
    }
    let status = RuntimeStatus {
        status: if result.is_ok() {
            RuntimeStatusKind::Stopped
        } else {
            RuntimeStatusKind::Crashed
        },
        generation,
        server_instance_id: Some(server_instance_id.clone()),
    };
    let reason = if result.is_ok() {
        "stopped"
    } else {
        "stop_failed"
    };
    let emit_result = emit_status(
        sink,
        status.status,
        status.generation,
        Some(&server_instance_id),
        reason,
        None,
    );
    result.and(emit_result).map(|_| status)
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 在同一 absolute deadline 下给每个失败 pre-runtime owner 一次 cleanup attempt；确认失败时
/// 保留其 process/session。
pub(super) fn retain_failed_supervisor(
    mut supervisor: SidecarSupervisor,
    pending_cleanup: &mut Option<SidecarSupervisor>,
    cleanup_fault: &Arc<CleanupFault>,
    deadline: Instant,
    runtime_control: &dyn RuntimeControlPort,
) {
    let generation = supervisor.generation();
    if let Err(error) = shutdown_supervisor_until(&mut supervisor, deadline, runtime_control) {
        cleanup_fault.mark(generation);
        *pending_cleanup = Some(supervisor);
        tracing::error!(?error, generation, "sidecar failure cleanup deferred");
    } else {
        cleanup_fault.clear(generation);
    }
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 只转换一次剩余绝对预算；supervisor 不创建会让 Tauri exit 越过 caller deadline 的新 timeout。
pub(super) fn shutdown_supervisor_until(
    supervisor: &mut SidecarSupervisor,
    deadline: Instant,
    runtime_control: &dyn RuntimeControlPort,
) -> Result<(), RuntimeCommandError> {
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err(RuntimeCommandError::shutdown_timeout());
    }
    runtime_control.shutdown_supervisor_until(supervisor, deadline)
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 在 owner 边界创建 monotonic deadline；下游只消费返回 instant，不能重置 shutdown 预算。
pub(super) fn shutdown_deadline(timeout: Duration) -> Instant {
    Instant::now()
        .checked_add(timeout)
        .unwrap_or_else(Instant::now)
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 构造 bridge 常规 absolute budget，并受 caller 已创建 deadline 限制，使 Terminal、Preview
/// 与 Java cleanup 共用一个 monotonic exit 预算。
pub(super) fn bounded_shutdown_deadline(
    timeout: Duration,
    caller_deadline: Option<Instant>,
) -> Instant {
    let internal = shutdown_deadline(timeout);
    caller_deadline.map_or(internal, |deadline| std::cmp::min(internal, deadline))
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 每个 cleanup 边界重新读取共享 exit attempt，避免 caller 缩短应用预算后 actor 仍使用 stale
/// 请求截止时间。
pub(super) fn effective_shutdown_deadline(
    request_deadline: Instant,
    control: &ExitControl,
) -> Instant {
    control.deadline().map_or(request_deadline, |active| {
        std::cmp::min(request_deadline, active)
    })
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// 只有后续显式 retry 可确认延迟但已 clean 的 actor；即便已无 process debt 需要实际 reap，
/// 首个 caller 的过期预算仍保持 failed exit attempt。
pub(super) fn can_promote_completed_retry(
    explicit_retry: bool,
    completion_done: bool,
    quarantine_empty: bool,
    cleanup_fault_pending: bool,
    detached_event_generation: u64,
    deadline: Instant,
) -> bool {
    explicit_retry
        && completion_done
        && quarantine_empty
        && !cleanup_fault_pending
        && detached_event_generation == 0
        && Instant::now() < deadline
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// shutdown 已在执行时用不可变 exit deadline 限制 protocol request；其它时候保留常规请求上限。
pub(super) fn operation_timeout(
    configured: Duration,
    exit_control: &ExitControl,
) -> Result<Duration, RuntimeCommandError> {
    if let Some(deadline) = exit_control.deadline() {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(RuntimeCommandError::shutdown_timeout());
        }
        Ok(configured.min(remaining))
    } else {
        Ok(configured)
    }
}

/// 设计原因：该函数复用调用方绝对 deadline，并保留未确认 cleanup 债务供恢复或重试。
/// cleanup 复用首个 exit deadline；仅在应用尚未请求退出时创建 local budget。
pub(super) fn cleanup_deadline(exit_control: &ExitControl, fallback: Duration) -> Instant {
    exit_control
        .deadline()
        .unwrap_or_else(|| shutdown_deadline(fallback))
}
