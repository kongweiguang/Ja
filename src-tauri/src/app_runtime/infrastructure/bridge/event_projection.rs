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
                signal_sender: spec.signal_sender,
                terminal_fault: spec.terminal_fault,
                current_generation: spec.current_generation,
                cancel_receiver,
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
                if !notification_matches_identity(&frame, &context.server_instance_id) {
                    continue;
                }
                if let Some((thread_id, turn_id)) = terminal_turn_identity(&frame) {
                    // terminal 必须等 frozen change-set 提交完成后再到达 React；否则最终答复会先于
                    // 本 Turn 修改事实出现，历史恢复也可能观察到不完整终态。
                    if !queue_turn_terminal(
                        &context.signal_sender,
                        context.generation,
                        thread_id,
                        turn_id,
                        frame,
                        &context.terminal_fault,
                    ) {
                        break;
                    }
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
/// 通过同一有界 actor lane 路由 terminal Turn identity，使 Java 关闭对应 Turn 后 pending
/// approval 能同步退役。
pub(super) fn queue_turn_terminal(
    sender: &SyncSender<BridgeSignal>,
    generation: u64,
    thread_id: String,
    turn_id: String,
    frame: RpcFrame,
    terminal_fault: &Arc<TerminalFault>,
) -> bool {
    match sender.try_send(BridgeSignal::TurnTerminal {
        generation,
        thread_id,
        turn_id,
        frame,
    }) {
        Ok(()) => true,
        Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {
            terminal_fault.publish(generation, TERMINAL_SIGNAL_QUEUE);
            false
        }
    }
}

/// 设计原因：该函数先验证 generation 与进程身份再投影事件，避免重启竞态串流。
/// 从 terminal notification 只提取已验证 Turn identity，其它 event data 继续走既有 WebView
/// 投影路径。
pub(super) fn terminal_turn_identity(frame: &RpcFrame) -> Option<(String, String)> {
    if frame.method() != Some("turn/terminal") {
        return None;
    }
    let params = frame.params()?.as_object()?;
    let thread_id = params
        .get("threadId")
        .and_then(Value::as_str)
        .filter(|value| value.starts_with("thr_") && valid_id(value, 128))?;
    let turn_id = params
        .get("turnId")
        .and_then(Value::as_str)
        .filter(|value| value.starts_with("turn_") && valid_id(value, 128))?;
    Some((thread_id.to_owned(), turn_id.to_owned()))
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
/// 处理异步 server request，同时禁止 stale generation 修改新 supervisor 或暴露私有 request ID。
pub(super) fn drain_signals(
    config: &LaunchConfig,
    sink: &EventSink,
    runtime: &mut Option<RunningRuntime>,
    receiver: &Receiver<BridgeSignal>,
    pending_turn_changes: &mut HashMap<String, TurnChangeBaseline>,
    host_turn_workspaces: &mut HashMap<String, String>,
    exit_control: &ExitControl,
) {
    while let Ok(signal) = receiver.try_recv() {
        match signal {
            BridgeSignal::TurnTerminal {
                generation,
                thread_id,
                turn_id,
                frame,
            } => {
                if runtime
                    .as_ref()
                    .is_some_and(|current| current.generation == generation)
                {
                    let commit = finish_host_turn_change(
                        &turn_id,
                        pending_turn_changes,
                        host_turn_workspaces,
                    )
                    .map(|(workspace_id, change_set)| {
                        commit_turn_change_set_runtime(
                            config,
                            runtime,
                            &thread_id,
                            &turn_id,
                            &workspace_id,
                            &change_set,
                            exit_control,
                        )
                    });
                    // 只有本 host 准入的 Turn 才能提交 change-set；恢复或其它 session 的
                    // terminal 没有 ownership 记录，继续只投影 Java 权威事实。
                    if let Some(commit) = commit {
                        emit_terminal_after_change_set(sink, &frame, generation, commit);
                    } else if emit_frame(sink, &frame).is_err() {
                        tracing::warn!(
                            generation,
                            "runtime terminal event delivery failed after change-set processing"
                        );
                    }
                }
            }
        }
    }
}

/// 从独立 host identity 账本消费 Turn；若 baseline 意外缺失或 workspace 不一致，提交显式
/// capture_failed，不能把 `null` 或空 available 当成可靠零修改。
pub(crate) fn finish_host_turn_change(
    turn_id: &str,
    pending_turn_changes: &mut HashMap<String, TurnChangeBaseline>,
    host_turn_workspaces: &mut HashMap<String, String>,
) -> Option<(String, TurnChangeSet)> {
    let workspace_id = host_turn_workspaces.remove(turn_id)?;
    let change_set = match pending_turn_changes.remove(turn_id) {
        Some(baseline) if baseline.workspace_id() == workspace_id => baseline.finish(),
        Some(_) | None => TurnChangeSet::unavailable(
            crate::review::domain::TurnChangeUnavailableReason::CaptureFailed,
        ),
    };
    Some((workspace_id, change_set))
}

/// Change-set commit 失败时仍投递 terminal，作为 UI 发起权威 Thread 重读的安全触发器；
/// 失败只写稳定 code，不记录正文、路径或 RPC payload，也不盲重试可能已提交的 artifact。
pub(crate) fn emit_terminal_after_change_set(
    sink: &EventSink,
    frame: &RpcFrame,
    generation: u64,
    commit: Result<(), RuntimeCommandError>,
) {
    if let Err(error) = commit {
        tracing::warn!(
            generation,
            error_code = error.code,
            "Turn change-set commit was not confirmed; terminal requires authoritative refresh"
        );
    }
    if emit_frame(sink, frame).is_err() {
        tracing::warn!(
            generation,
            "runtime terminal event delivery failed after change-set processing"
        );
    }
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
/// 投影前把每个 v2 notification fence 到精确 Java 进程实例；任何 event type 都不能省略 identity，
/// 否则重启前后的数据会混流。
pub(crate) fn notification_matches_identity(frame: &RpcFrame, expected: &str) -> bool {
    frame
        .params()
        .and_then(|params| params.get("serverInstanceId"))
        .and_then(Value::as_str)
        .is_some_and(|instance| instance == expected)
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
