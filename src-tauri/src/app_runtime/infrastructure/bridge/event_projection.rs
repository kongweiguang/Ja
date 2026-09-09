// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// event projection 负责 generation fence、脱敏投影和终态信号。

use super::*;

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 保持底层 EventPump 为唯一 reader，只把已验证 observation 路由回 lifecycle actor。
pub(super) fn spawn_event_drain(spec: EventDrainSpec) -> Result<EventDrain, RuntimeCommandError> {
    let (cancel, cancel_receiver) = mpsc::sync_channel(1);
    let completion = Arc::new(Completion::new());
    let worker_completion = Arc::clone(&completion);
    let generation = spec.generation;
    let detached_event_generation = Arc::clone(&spec.detached_event_generation);
    let join = thread::Builder::new()
        .name(format!("ja-runtime-events-{generation}"))
        .spawn(move || {
            event_drain_loop(EventDrainContext {
                events: spec.events,
                generation: spec.generation,
                supervisor_generation: spec.supervisor_generation,
                server_instance_id: spec.server_instance_id,
                ready_token: spec.ready_token,
                sink: spec.sink,
                terminal_fault: spec.terminal_fault,
                current_generation: spec.current_generation,
                cancel_receiver,
                task_observations: spec.task_observations,
            });
            let _ = detached_event_generation.compare_exchange(
                generation,
                0,
                Ordering::AcqRel,
                Ordering::Acquire,
            );
            worker_completion.mark_done();
        })
        .map_err(|_| RuntimeCommandError::unavailable())?;
    Ok(EventDrain {
        cancel,
        completion,
        generation,
        detached_event_generation: spec.detached_event_generation,
        join: Some(join),
    })
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 连续轮询通知，并在到达 UI 前拒绝 stale identity。
pub(super) fn event_drain_loop(mut context: EventDrainContext) {
    loop {
        if !event_is_current(
            &context.cancel_receiver,
            &context.current_generation,
            context.generation,
        ) {
            break;
        }
        let Some(event) = context.events.next_event(EVENT_POLL_TIMEOUT) else {
            continue;
        };
        // EventPump 可能与 stop/new-generation 并发返回；此处复检可阻止 stale frame
        // 到达 WebView。
        if !event_is_current(
            &context.cancel_receiver,
            &context.current_generation,
            context.generation,
        ) {
            break;
        }
        match event {
            SessionEvent::Notification(frame) => {
                if !notification_matches_identity(
                    &frame,
                    &context.server_instance_id,
                    context.generation,
                ) {
                    continue;
                }
                if frame.method() == Some("task/progress")
                    && !task_progress_is_observed(
                        &frame,
                        context.generation,
                        &context.task_observations,
                    )
                {
                    continue;
                }
                if let Err(error) = emit_frame(&context.sink, &frame) {
                    if projection_failure_is_terminal(&error) {
                        context
                            .terminal_fault
                            .publish(context.generation, TERMINAL_SIDECAR_PROTOCOL_FRAME);
                        break;
                    }
                    tracing::warn!(
                        generation = context.generation,
                        "runtime WebView event delivery failed; sidecar remains active"
                    );
                    continue;
                }
            }
            SessionEvent::QueueOverflow(_) => {
                if emit_status(
                    &context.sink,
                    RuntimeStatusKind::Busy,
                    context.generation,
                    Some(&context.server_instance_id),
                    "event_queue_overflow",
                    Some(&context.ready_token),
                )
                .is_err()
                {
                    tracing::warn!(
                        generation = context.generation,
                        "runtime WebView status delivery failed; sidecar remains active"
                    );
                }
            }
            SessionEvent::ProcessExited {
                generation: observed,
                code,
            } if observed == context.supervisor_generation => {
                context
                    .terminal_fault
                    .publish(context.generation, terminal_exit_reason(code));
                break;
            }
            SessionEvent::Eof => {
                context
                    .terminal_fault
                    .publish(context.generation, TERMINAL_SIDECAR_EOF);
                break;
            }
            SessionEvent::HandshakeFailed => {
                context
                    .terminal_fault
                    .publish(context.generation, TERMINAL_SIDECAR_HANDSHAKE);
                break;
            }
            SessionEvent::WriterTimedOut => {
                context
                    .terminal_fault
                    .publish(context.generation, TERMINAL_SIDECAR_WRITER_TIMEOUT);
                break;
            }
            SessionEvent::ProtocolFault(error) => {
                context
                    .terminal_fault
                    .publish(context.generation, terminal_protocol_reason(&error));
                break;
            }
            SessionEvent::QueueFatalOverflow(_) => {
                context
                    .terminal_fault
                    .publish(context.generation, TERMINAL_SIDECAR_QUEUE);
                break;
            }
            SessionEvent::ResponseRejected => {
                context
                    .terminal_fault
                    .publish(context.generation, TERMINAL_SIDECAR_RESPONSE);
                break;
            }
            SessionEvent::ProcessExited { .. }
            | SessionEvent::StderrLine(_)
            | SessionEvent::StderrTruncated => {}
        }
    }
}

/// Progress 仅在 observation handle 仍属于当前 sidecar generation 时投影，reload 后旧帧被丢弃。
pub(crate) fn task_progress_is_observed(
    frame: &RpcFrame,
    generation: u64,
    registry: &TaskObservationRegistry,
) -> bool {
    frame
        .params()
        .and_then(|params| params.get("observationId"))
        .and_then(Value::as_str)
        .is_some_and(|observation_id| registry.is_active(observation_id, generation))
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 将 child exit status 收敛为稳定且不含 secret 的诊断类别；原始 OS code 不进入 WebView 契约。
pub(super) fn terminal_exit_reason(code: Option<i32>) -> u8 {
    match code {
        Some(0) => TERMINAL_SIDECAR_EXIT_ZERO,
        Some(_) => TERMINAL_SIDECAR_EXIT_NONZERO,
        None => TERMINAL_SIDECAR_EXIT_UNKNOWN,
    }
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 区分非法 Java envelope 与 transport/framing fault，同时禁止把 frame 内容、路径、prompt
/// 或 credential 序列化到 UI event。
pub(super) fn terminal_protocol_reason(error: &CodecError) -> u8 {
    match error {
        CodecError::InvalidEnvelope => TERMINAL_SIDECAR_PROTOCOL_ENVELOPE,
        CodecError::InvalidId => TERMINAL_SIDECAR_PROTOCOL_INVALID_ID,
        CodecError::InvalidErrorCatalog => TERMINAL_SIDECAR_PROTOCOL_INVALID_ERROR_CATALOG,
        CodecError::InvalidJson => TERMINAL_SIDECAR_PROTOCOL_INVALID_JSON,
        CodecError::PartialFrame => TERMINAL_SIDECAR_PROTOCOL_PARTIAL_FRAME,
        CodecError::Io => TERMINAL_SIDECAR_PROTOCOL_IO,
        CodecError::InvalidUtf8 => TERMINAL_SIDECAR_PROTOCOL_INVALID_UTF8,
        CodecError::DuplicateKey => TERMINAL_SIDECAR_PROTOCOL_DUPLICATE_KEY,
        CodecError::EmptyFrame => TERMINAL_SIDECAR_PROTOCOL_EMPTY_FRAME,
        CodecError::NonObject => TERMINAL_SIDECAR_PROTOCOL_NON_OBJECT,
        CodecError::FrameTooLarge { .. } => TERMINAL_SIDECAR_PROTOCOL_FRAME_TOO_LARGE,
        CodecError::InvalidLimit => TERMINAL_SIDECAR_PROTOCOL_INVALID_LIMIT,
        // EOF 与握手失败通常在 reader 中走专用事件；保留稳定兜底避免未来路由
        // 调整时把内部 enum 或原始载荷带入桌面诊断。
        CodecError::UnexpectedEof | CodecError::HandshakeFailed => TERMINAL_SIDECAR_PROTOCOL_FRAME,
    }
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 在 poll 前和 emit 前各检查一次 cancellation，关闭竞态窗口。
pub(super) fn event_is_current(
    cancel_receiver: &Receiver<()>,
    current_generation: &AtomicU64,
    generation: u64,
) -> bool {
    cancel_receiver.try_recv().is_err() && generation_is_current(current_generation, generation)
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 保持 generation 比较为纯函数，使 stop/new-generation race 无需创建 live EventPump 或第二
/// reader 即可测试。
pub(super) fn generation_is_current(current_generation: &AtomicU64, generation: u64) -> bool {
    current_generation.load(Ordering::Acquire) == generation
}

/// 设计原因：非法 Java 通知破坏已冻结的 JA-RPC 契约，必须关闭当前 generation；窗口
/// emit 失败只表示 renderer 暂时不可达，Rust 仍拥有健康 sidecar，不能因此销毁用户回合。
pub(crate) fn projection_failure_is_terminal(error: &RuntimeCommandError) -> bool {
    error.code != "RUNTIME_EVENT_DELIVERY_FAILED"
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 发送普通 client-owned approval response；Java runtime 持久化并验证 approval CAS，Rust
/// 不接收或保存私有 server-request ID。
pub(super) fn respond_approval(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: ApprovalResponseInput,
    exit_control: &ExitControl,
) -> Result<(), RuntimeCommandError> {
    if exit_control.is_cancelled() {
        return Err(RuntimeCommandError::shutdown_timeout());
    }
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request("approval/respond", approval_params(&input)?, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    let result = value
        .get("result")
        .and_then(Value::as_object)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if result.get("accepted").and_then(Value::as_bool) != Some(true) {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(())
}

/// 聚合 terminal cleanup 依赖，使所有失败路径消耗同一 actor-owned exit deadline，避免扩张
/// 函数签名。
pub(super) struct TerminalCleanupContext<'a> {
    pub(super) config: &'a LaunchConfig,
    pub(super) sink: &'a EventSink,
    pub(super) runtime: &'a mut Option<RunningRuntime>,
    pub(super) current_generation: &'a Arc<AtomicU64>,
    pub(super) terminal_fault: &'a Arc<TerminalFault>,
    pub(super) cleanup_fault: &'a Arc<CleanupFault>,
    pub(super) exit_control: &'a Arc<ExitControl>,
    pub(super) runtime_control: &'a Arc<dyn RuntimeControlPort>,
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// drain 保留 terminal wake lane，并原子观察处理请求期间无法写入 wake slot 的 fault。
pub(super) fn drain_terminal_fault(
    context: TerminalCleanupContext<'_>,
    wake_receiver: &Receiver<()>,
) {
    let TerminalCleanupContext {
        config,
        sink,
        runtime,
        current_generation,
        terminal_fault,
        cleanup_fault,
        exit_control,
        runtime_control,
    } = context;
    while wake_receiver.try_recv().is_ok() {}
    let Some(current) = runtime.as_ref() else {
        return;
    };
    let Some(reason) = terminal_fault.take(current.generation) else {
        return;
    };
    terminal_runtime(
        TerminalCleanupContext {
            config,
            sink,
            runtime,
            current_generation,
            terminal_fault,
            cleanup_fault,
            exit_control,
            runtime_control,
        },
        terminal_reason(reason),
    );
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 将紧凑 atomic reason 映射为稳定生命周期投影，不泄漏内部错误载荷。
pub(crate) fn terminal_reason(reason: u8) -> &'static str {
    match reason {
        TERMINAL_SIGNAL_QUEUE => "runtime_signal_queue_full",
        TERMINAL_SIDECAR_EOF => "sidecar_eof",
        TERMINAL_SIDECAR_HANDSHAKE => "sidecar_handshake_failed",
        TERMINAL_SIDECAR_WRITER_TIMEOUT => "sidecar_writer_timeout",
        TERMINAL_SIDECAR_PROTOCOL_ENVELOPE => "sidecar_protocol_invalid_envelope",
        TERMINAL_SIDECAR_PROTOCOL_FRAME => "sidecar_protocol_frame_failed",
        TERMINAL_SIDECAR_PROTOCOL_INVALID_ID => "sidecar_protocol_invalid_id",
        TERMINAL_SIDECAR_PROTOCOL_INVALID_ERROR_CATALOG => "sidecar_protocol_invalid_error_catalog",
        TERMINAL_SIDECAR_PROTOCOL_INVALID_JSON => "sidecar_protocol_invalid_json",
        TERMINAL_SIDECAR_PROTOCOL_PARTIAL_FRAME => "sidecar_protocol_partial_frame",
        TERMINAL_SIDECAR_PROTOCOL_IO => "sidecar_protocol_io",
        TERMINAL_SIDECAR_PROTOCOL_INVALID_UTF8 => "sidecar_protocol_invalid_utf8",
        TERMINAL_SIDECAR_PROTOCOL_DUPLICATE_KEY => "sidecar_protocol_duplicate_key",
        TERMINAL_SIDECAR_PROTOCOL_EMPTY_FRAME => "sidecar_protocol_empty_frame",
        TERMINAL_SIDECAR_PROTOCOL_NON_OBJECT => "sidecar_protocol_non_object",
        TERMINAL_SIDECAR_PROTOCOL_FRAME_TOO_LARGE => "sidecar_protocol_frame_too_large",
        TERMINAL_SIDECAR_PROTOCOL_INVALID_LIMIT => "sidecar_protocol_invalid_limit",
        TERMINAL_SIDECAR_QUEUE => "sidecar_queue_overflow",
        TERMINAL_SIDECAR_RESPONSE => "sidecar_response_rejected",
        TERMINAL_SIDECAR_EXIT_ZERO => "sidecar_exit_zero",
        TERMINAL_SIDECAR_EXIT_NONZERO => "sidecar_exit_nonzero",
        TERMINAL_SIDECAR_EXIT_UNKNOWN => "sidecar_exit_unknown",
        _ => "sidecar_terminated",
    }
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 关闭失败 generation，并且只发布一次 crash 投影。
pub(super) fn terminal_runtime(context: TerminalCleanupContext<'_>, reason: &str) {
    let TerminalCleanupContext {
        config,
        sink,
        runtime,
        current_generation,
        terminal_fault,
        cleanup_fault,
        exit_control,
        runtime_control,
    } = context;
    let Some(mut current) = runtime.take() else {
        return;
    };
    current_generation.store(0, Ordering::Release);
    terminal_fault.clear();
    let deadline = cleanup_deadline(exit_control, config.shutdown_timeout);
    let generation = current.generation;
    let server_instance_id = current.server_instance_id.clone();
    eprintln!("Ja runtime sidecar terminated: reason={reason} generation={generation}");
    if shutdown_components(&mut current, deadline, runtime_control.as_ref()).is_err() {
        // Terminal notification 不能证明进程树已 reap；保留 runtime owner 并阻止新 generation，
        // 直到后续 stop attempt 确认同一 generation 已清理。
        cleanup_fault.mark(generation);
        *runtime = Some(current);
    } else {
        cleanup_fault.clear(generation);
    }
    if emit_status(
        sink,
        RuntimeStatusKind::Crashed,
        generation,
        Some(&server_instance_id),
        reason,
        None,
    )
    .is_err()
    {
        tracing::error!("runtime event projection failed during terminal cleanup");
    }
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 投影前把每个 JA-RPC 1.0 notification fence 到精确 Java 进程实例与 host generation；任一字段
/// 缺失或漂移都代表迟到/跨代数据，必须在进入 sanitizer 与 WebView 前丢弃。
pub(crate) fn notification_matches_identity(
    frame: &RpcFrame,
    expected_instance: &str,
    expected_generation: u64,
) -> bool {
    frame.params().is_some_and(|params| {
        params.get("serverInstanceId").and_then(Value::as_str) == Some(expected_instance)
            && params.get("generation").and_then(Value::as_u64) == Some(expected_generation)
    })
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 读取底层 lifecycle machine 后返回 state snapshot，避免旁路缓存漂移。
pub(super) fn snapshot(
    runtime: &mut Option<RunningRuntime>,
    cleanup_fault: &CleanupFault,
    pending_cleanup: Option<&SidecarSupervisor>,
) -> RuntimeStatus {
    let projected = if let Some(current) = runtime.as_mut() {
        current_status(current)
    } else {
        RuntimeStatus {
            status: RuntimeStatusKind::Stopped,
            generation: 0,
            server_instance_id: None,
        }
    };
    let debt_generation = cleanup_fault
        .generation()
        .max(pending_cleanup.map_or(0, SidecarSupervisor::generation));
    if debt_generation == 0 {
        return projected;
    }
    RuntimeStatus {
        status: RuntimeStatusKind::Crashed,
        generation: debt_generation,
        server_instance_id: projected.server_instance_id,
    }
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 在 actor 串行化点投影 supervisor lifecycle，禁止 cached status 越过 terminal signal。
pub(super) fn current_status(runtime: &mut RunningRuntime) -> RuntimeStatus {
    RuntimeStatus {
        status: RuntimeStatusKind::from_lifecycle(runtime.supervisor.state()),
        generation: runtime.generation,
        server_instance_id: Some(runtime.server_instance_id.clone()),
    }
}
