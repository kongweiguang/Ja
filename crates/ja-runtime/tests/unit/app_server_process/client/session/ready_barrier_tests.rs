// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// initialized/ready 线性化门、writer watchdog 与 generation 晋升测试。

use super::*;
use crate::app_server_process;
use crate::app_server_process::protocol::{CodecError, Limits, RpcFrame};
use crate::unit_support_tests::*;
use serde_json::json;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::time::{Duration, Instant};

/// 只在外置测试中观察 initialized barrier；生产 API 不为等待测试断言暴露额外方法。
fn wait_initialized_confirmation(session: &Session, timeout: Duration) -> bool {
    let deadline = Instant::now()
        .checked_add(timeout)
        .unwrap_or_else(Instant::now);
    let barrier = &session.inner.initialized_barrier;
    let Ok(mut state) = barrier.state.lock() else {
        return false;
    };
    loop {
        match *state {
            InitializedBarrierState::Confirmed => return true,
            InitializedBarrierState::Failed => return false,
            InitializedBarrierState::NotSent | InitializedBarrierState::Sending => {}
        }
        // 测试与生产 writer 共用 Condvar，只观察真实状态转换，不用 sleep 猜测竞态。
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return false;
        }
        let Ok((next, wait)) = barrier.wake.wait_timeout(state, remaining) else {
            return false;
        };
        state = next;
        if wait.timed_out() {
            return *state == InitializedBarrierState::Confirmed;
        }
    }
}

/// 在测试中持有生产 ready/terminal gate 后观察 token 校验，避免保留公开测试 wrapper。
fn ready_after_initialized_barrier(session: &Session, frame: &RpcFrame) -> bool {
    let Ok(_gate) = session.inner.ready_terminal_gate.lock() else {
        return false;
    };
    session.ready_after_initialized_barrier_locked(frame)
}

fn ready_promotion_fixture(
    generation: u64,
) -> (
    Session,
    PipeWriter,
    app_server_process::client::EventPump,
    RpcFrame,
) {
    let (server_reader, server_writer) = pipe_pair();
    let token = "fedcba9876543210fedcba9876543210";
    let ready = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_gate",
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .unwrap();
    let (ack_sender, ack_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        AckWriter { writes: ack_sender },
        EmptyReader,
        generation,
        Limits::default(),
    )
    .unwrap();
    session
        .install_ready_token_challenge(token.to_owned())
        .unwrap();
    let pump = session.take_event_pump().unwrap();
    session
        .notify("runtime/initialized", json!({"readyToken":token}))
        .unwrap();
    ack_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("initialized frame must reach writer");
    assert!(wait_initialized_confirmation(
        &session,
        Duration::from_secs(1)
    ));
    (session, server_writer, pump, ready)
}

/// ready/terminal gate 中毒后 promotion 必须先释放 PoisonError guard 再 fail-closed，
/// 返回 ProtocolFault 且不得在 terminal cleanup 中自锁。
#[test]
fn poisoned_ready_terminal_gate_rejects_promotion_without_deadlock() {
    let (session, _server_writer, _pump, frame) = ready_promotion_fixture(17);
    poison_mutex(&session.inner.ready_terminal_gate);

    assert_eq!(
        session.with_ready_promotion(&frame, || Ok(())),
        Err(app_server_process::AppServerProcessError::ProtocolFault)
    );
    assert!(session.inner.closed.load(Ordering::Acquire));
    close_session(&session);
}

/// 证明 duplicate ready 在首帧出队后仍先赢 terminal gate，lifecycle closure 不会执行。
#[test]
fn ready_promotion_gate_rejects_duplicate_before_lifecycle_mark() {
    let (session, server_writer, mut pump, ready) = ready_promotion_fixture(36);
    let ready_bytes = ready.encode(Limits::default().max_frame_bytes).unwrap();
    server_writer.sender.send(ready_bytes.clone()).unwrap();
    let SessionEvent::Notification(frame) = pump
        .next_event(Duration::from_secs(1))
        .expect("first ready notification")
    else {
        panic!("expected first ready notification");
    };
    server_writer.sender.send(ready_bytes).unwrap();
    assert!(matches!(
        pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::HandshakeFailed)
    ));

    let mut promoted = false;
    assert_eq!(
        session.with_ready_promotion(&frame, || {
            promoted = true;
            Ok(())
        }),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert!(!promoted);
    close_session(&session);
}

/// 证明 monitor fault 在 ready 出队后也赢得同一 gate，避免 supervisor 误标 Ready。
#[test]
fn ready_promotion_gate_rejects_monitor_fault_before_lifecycle_mark() {
    let (session, server_writer, mut pump, ready) = ready_promotion_fixture(37);
    server_writer
        .sender
        .send(ready.encode(Limits::default().max_frame_bytes).unwrap())
        .unwrap();
    let SessionEvent::Notification(frame) = pump
        .next_event(Duration::from_secs(1))
        .expect("first ready notification")
    else {
        panic!("expected first ready notification");
    };
    session.report_process_fault();
    assert!(matches!(
        pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::ProtocolFault(codec::CodecError::Io))
    ));

    let mut promoted = false;
    assert_eq!(
        session.with_ready_promotion(&frame, || {
            promoted = true;
            Ok(())
        }),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert!(!promoted);
    close_session(&session);
}

/// 证明 stdout EOF 在 ready 出队后仍阻止 lifecycle mark_ready，且 session 已关闭。
#[test]
fn ready_promotion_gate_rejects_eof_before_lifecycle_mark() {
    let (session, server_writer, mut pump, ready) = ready_promotion_fixture(38);
    server_writer
        .sender
        .send(ready.encode(Limits::default().max_frame_bytes).unwrap())
        .unwrap();
    let SessionEvent::Notification(frame) = pump
        .next_event(Duration::from_secs(1))
        .expect("first ready notification")
    else {
        panic!("expected first ready notification");
    };
    drop(server_writer);
    assert!(matches!(
        pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::Eof)
    ));

    let mut promoted = false;
    assert_eq!(
        session.with_ready_promotion(&frame, || {
            promoted = true;
            Ok(())
        }),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert!(!promoted);
    close_session(&session);
}

/// initialized 尚未完成时预排队的 ready 必须被拒绝，防止 child 绕过 challenge barrier。
#[test]
fn prequeued_ready_is_rejected_by_ready_token_barrier() {
    let (server_reader, server_writer) = pipe_pair();
    let token = "0123456789abcdef0123456789abcdef";
    let ready = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_ready",
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .unwrap()
    .encode(Limits::default().max_frame_bytes)
    .unwrap();
    let _server_writer = server_writer;
    let (ack_sender, _ack_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        AckWriter { writes: ack_sender },
        EmptyReader,
        31,
        Limits::default(),
    )
    .unwrap();
    session
        .install_ready_token_challenge(token.to_owned())
        .unwrap();
    _server_writer.sender.send(ready).unwrap();
    let mut pump = session.take_event_pump().unwrap();
    assert!(matches!(
        pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::HandshakeFailed)
    ));
    assert_eq!(
        session.notify("runtime/initialized", json!({"readyToken":token})),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    close_session(&session);
}

/// ready 在 initialized write 尚未确认时只能等待，不能提前推进 lifecycle generation。
#[test]
fn ready_during_initialized_write_waits_for_successful_barrier() {
    let (server_reader, server_writer) = pipe_pair();
    let token = "fedcba9876543210fedcba9876543210";
    let ready = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_preflush",
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .unwrap();
    let (entered_sender, entered_receiver) = mpsc::channel();
    let (release_sender, release_receiver) = mpsc::channel();
    let (completed_sender, completed_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        GateWriter {
            entered: entered_sender,
            release: release_receiver,
            completed: completed_sender,
            blocked: false,
        },
        EmptyReader,
        32,
        Limits::default(),
    )
    .unwrap();
    session
        .install_ready_token_challenge(token.to_owned())
        .unwrap();
    let mut pump = session.take_event_pump().unwrap();
    session
        .notify("runtime/initialized", json!({"readyToken":token}))
        .unwrap();
    entered_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("writer entered initialized frame");
    server_writer
        .sender
        .send(ready.encode(Limits::default().max_frame_bytes).unwrap())
        .unwrap();
    assert!(pump.next_event(Duration::from_millis(50)).is_none());
    release_sender.send(()).unwrap();
    completed_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("initialized flush completed");
    let SessionEvent::Notification(frame) = pump
        .next_event(Duration::from_secs(1))
        .expect("ready after initialized barrier")
    else {
        panic!("expected ready notification after successful barrier");
    };
    assert!(ready_after_initialized_barrier(&session, &frame));
    close_session(&session);
    session
        .close_until(Instant::now() + Duration::from_secs(1))
        .unwrap();
}

/// flush 刚完成即到达的 ready 只能晋升一次，重复通知必须关闭当前 session。
#[test]
fn immediate_ready_after_initialized_flush_accepts_once_and_duplicate_fails() {
    let (server_reader, server_writer) = pipe_pair();
    let token = "fedcba9876543210fedcba9876543210";
    let ready = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_immediate",
            "occurredAt":"2000-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .unwrap();
    let (ack_sender, ack_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        AckWriter { writes: ack_sender },
        EmptyReader,
        32,
        Limits::default(),
    )
    .unwrap();
    session
        .install_ready_token_challenge(token.to_owned())
        .unwrap();
    let mut pump = session.take_event_pump().unwrap();
    session
        .notify("runtime/initialized", json!({"readyToken":token}))
        .unwrap();
    ack_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("initialized flush completed");
    assert!(wait_initialized_confirmation(
        &session,
        Duration::from_secs(1)
    ));
    let ready_bytes = ready.encode(Limits::default().max_frame_bytes).unwrap();
    server_writer.sender.send(ready_bytes.clone()).unwrap();
    let SessionEvent::Notification(frame) = pump
        .next_event(Duration::from_secs(1))
        .expect("prequeued ready")
    else {
        panic!("expected ready notification");
    };
    assert!(ready_after_initialized_barrier(&session, &frame));
    server_writer.sender.send(ready_bytes).unwrap();
    assert!(matches!(
        pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::HandshakeFailed)
    ));
    close_session(&session);
}

/// 以显式 cleanup 运行一轮 writer barrier，使 assertion 失败也不会让 blocked writer
/// actor 脱离测试进程。
fn run_immediate_ready_barrier_round(round: u64) -> Result<(), String> {
    let (server_reader, server_writer) = pipe_pair();
    let token = if round.is_multiple_of(2) {
        "fedcba9876543210fedcba9876543210"
    } else {
        "0123456789abcdef0123456789abcdef"
    };
    let ready = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":format!("evt_barrier_{round}"),
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .map_err(|error| format!("ready frame construction: {error}"))?
    .encode(Limits::default().max_frame_bytes)
    .map_err(|error| format!("ready frame encoding: {error}"))?;
    let (ready_sent_sender, ready_sent_receiver) = mpsc::channel();
    let (release_sender, release_receiver) = mpsc::channel();
    let (flush_done_sender, flush_done_receiver) = mpsc::channel();
    let writer = ImmediateReadyWriter {
        ready_sender: server_writer.sender.clone(),
        ready_payload: Some(ready),
        ready_sent: ready_sent_sender,
        release_flush: release_receiver,
        flush_done: flush_done_sender,
    };
    let session = session_from_io(
        server_reader,
        writer,
        EmptyReader,
        round + 100,
        Limits::default(),
    )
    .map_err(|error| format!("session construction: {error}"))?;
    session
        .install_ready_token_challenge(token.to_owned())
        .map_err(|error| format!("challenge install: {error}"))?;
    let mut pump = session
        .take_event_pump()
        .map_err(|error| format!("event pump: {error}"))?;
    let notify_result = session.notify("runtime/initialized", json!({"readyToken":token}));
    let ready_sent_result = ready_sent_receiver.recv_timeout(Duration::from_secs(1));
    let release_result = release_sender.send(());
    let flush_result = flush_done_receiver.recv_timeout(Duration::from_secs(1));
    let event = pump.next_event(Duration::from_secs(1));
    let promotion_result = match &event {
        Some(SessionEvent::Notification(frame)) => session
            .with_ready_promotion(frame, || Ok(()))
            .map_err(|error| format!("ready promotion: {error}")),
        _ => Err("immediate ready was rejected before barrier publication".to_owned()),
    };

    // close 前 release writer，确保 event assertion 失败时仍有有界 join 路径；该 cleanup
    // 边界与生产一致。
    close_session(&session);
    let join_result = session.close_until(Instant::now() + Duration::from_secs(1));

    notify_result.map_err(|error| format!("initialized notify: {error}"))?;
    ready_sent_result.map_err(|error| format!("ready publication: {error}"))?;
    release_result.map_err(|error| format!("flush release: {error}"))?;
    flush_result.map_err(|error| format!("flush completion: {error}"))?;
    join_result.map_err(|error| format!("writer join: {error}"))?;
    promotion_result
}

/// 无 sleep 重复生产 Session reader/writer barrier 路径，在不同 scheduler interleaving
/// 下持续覆盖 immediate child reply 竞态。
#[test]
fn immediate_ready_writer_barrier_is_stable_for_100_rounds() {
    for round in 0..100 {
        run_immediate_ready_barrier_round(round)
            .unwrap_or_else(|error| panic!("writer barrier round {round} failed: {error}"));
    }
}

/// 证明 ready 在 write 返回前到达时会等待同一把门，成功 flush 后才能进入事件队列。
#[test]
fn ready_before_write_return_waits_then_promotes() {
    let (server_reader, server_writer) = pipe_pair();
    let token = "0123456789abcdef0123456789abcdef";
    let ready = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_write_window",
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .unwrap()
    .encode(Limits::default().max_frame_bytes)
    .unwrap();
    let (ready_sent_sender, ready_sent_receiver) = mpsc::channel();
    let (release_sender, release_receiver) = mpsc::channel();
    let (write_done_sender, write_done_receiver) = mpsc::channel();
    let (flush_done_sender, flush_done_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        ReadyBeforeWriteReturnWriter {
            ready_sender: server_writer.sender.clone(),
            ready_payload: Some(ready),
            ready_sent: ready_sent_sender,
            release_write: release_receiver,
            write_done: write_done_sender,
            flush_done: flush_done_sender,
        },
        EmptyReader,
        140,
        Limits::default(),
    )
    .unwrap();
    session
        .install_ready_token_challenge(token.to_owned())
        .unwrap();
    let mut pump = session.take_event_pump().unwrap();
    session
        .notify("runtime/initialized", json!({"readyToken":token}))
        .unwrap();
    ready_sent_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("ready sent before write returned");
    assert!(pump.next_event(Duration::from_millis(50)).is_none());
    release_sender.send(()).unwrap();
    write_done_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("write returned");
    flush_done_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("flush completed");
    assert!(wait_initialized_confirmation(
        &session,
        Duration::from_secs(1)
    ));
    let SessionEvent::Notification(frame) = pump
        .next_event(Duration::from_secs(1))
        .expect("ready after write barrier")
    else {
        panic!("expected ready notification after write barrier");
    };
    assert!(ready_after_initialized_barrier(&session, &frame));
    close_session(&session);
    session
        .close_until(Instant::now() + Duration::from_secs(1))
        .unwrap();
}

/// 证明 flush 失败会把共享门置为 Failed，ready 既不能晋级也不能留下 confirmation。
fn run_flush_failure_barrier_round(round: u64) -> Result<(), String> {
    let (server_reader, server_writer) = pipe_pair();
    let token = "fedcba9876543210fedcba9876543210";
    let ready_frame = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_flush_failure",
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .unwrap();
    let ready = ready_frame
        .encode(Limits::default().max_frame_bytes)
        .unwrap();
    let (ready_sent_sender, ready_sent_receiver) = mpsc::channel();
    let (release_sender, release_receiver) = mpsc::channel();
    let (flush_done_sender, flush_done_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        FlushFailureWriter {
            ready_sender: server_writer.sender.clone(),
            ready_payload: Some(ready),
            ready_sent: ready_sent_sender,
            release_flush: release_receiver,
            flush_done: flush_done_sender,
        },
        EmptyReader,
        round + 141,
        Limits::default(),
    )
    .map_err(|error| format!("session construction failed: {error}"))?;

    let result = (|| {
        session
            .install_ready_token_challenge(token.to_owned())
            .map_err(|error| format!("challenge install failed: {error}"))?;
        let mut pump = session
            .take_event_pump()
            .map_err(|error| format!("event pump failed: {error}"))?;
        session
            .notify("runtime/initialized", json!({"readyToken":token}))
            .map_err(|error| format!("initialized notify failed: {error}"))?;
        ready_sent_receiver
            .recv_timeout(Duration::from_secs(1))
            .map_err(|_| "ready was not sent during blocked flush".to_owned())?;
        if pump.next_event(Duration::from_millis(50)).is_some() {
            return Err("ready escaped while flush still held the barrier".to_owned());
        }
        release_sender
            .send(())
            .map_err(|_| "flush release failed".to_owned())?;
        flush_done_receiver
            .recv_timeout(Duration::from_secs(1))
            .map_err(|_| "flush did not reach its failure boundary".to_owned())?;

        // writer 与 reader 发布独立 terminal 事实，其 queue 顺序由 scheduler 决定且不属于
        // 公共顺序合同；允许任一合法事实，但不接受 unknown/empty 结果。
        let event = pump
            .next_event(Duration::from_secs(1))
            .ok_or_else(|| "terminal event queue returned empty".to_owned())?;
        match event {
            SessionEvent::HandshakeFailed | SessionEvent::ProtocolFault(CodecError::Io) => {}
            SessionEvent::Notification(_) => {
                return Err("ready notification escaped after flush failure".to_owned());
            }
            _ => return Err("unexpected terminal event classification".to_owned()),
        }
        if wait_initialized_confirmation(&session, Duration::from_millis(50)) {
            return Err("failed flush left initialized confirmation set".to_owned());
        }
        if ready_after_initialized_barrier(&session, &ready_frame) {
            return Err("failed flush allowed ready promotion".to_owned());
        }
        Ok(())
    })();

    // join 前始终 release fixture，避免 assertion 失败使刻意阻塞的 writer 超出测试 deadline。
    let _ = release_sender.send(());
    close_session(&session);
    let cleanup = session
        .close_until(Instant::now() + Duration::from_secs(1))
        .map_err(|error| format!("writer cleanup failed: {error}"));
    match (result, cleanup) {
        (Ok(()), Ok(())) => Ok(()),
        (Err(error), Ok(())) | (Ok(()), Err(error)) => Err(error),
        (Err(error), Err(cleanup_error)) => Err(format!("{error}; {cleanup_error}")),
    }
}

/// 刻意重复 flush failure 顺序，证明允许的 terminal-fact 竞态不会重新引入 ready
/// promotion 偶发失败。
#[test]
fn ready_during_flush_failure_fails_closed_without_confirmation() {
    for round in 0..100 {
        run_flush_failure_barrier_round(round)
            .unwrap_or_else(|error| panic!("flush failure round {round} failed: {error}"));
    }
}

/// 锁定错误 token 会直接终止当前 generation，而不是拖到 ready deadline。
#[test]
fn ready_token_wrong_value_is_handshake_failure() {
    let (server_reader, server_writer) = pipe_pair();
    let token = "0123456789abcdef0123456789abcdef";
    let (ack_sender, ack_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        AckWriter { writes: ack_sender },
        EmptyReader,
        34,
        Limits::default(),
    )
    .unwrap();
    session
        .install_ready_token_challenge(token.to_owned())
        .unwrap();
    session
        .notify("runtime/initialized", json!({"readyToken":token}))
        .unwrap();
    assert!(wait_initialized_confirmation(
        &session,
        Duration::from_secs(1)
    ));
    let wrong = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_wrong",
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":"fedcba9876543210fedcba9876543210"
        }),
    )
    .unwrap();
    server_writer
        .sender
        .send(wrong.encode(Limits::default().max_frame_bytes).unwrap())
        .unwrap();
    let mut pump = session.take_event_pump().unwrap();
    assert!(matches!(
        pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::HandshakeFailed)
    ));
    assert_eq!(
        session.request("runtime/health", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    ack_receiver.recv_timeout(Duration::from_secs(1)).unwrap();
    close_session(&session);
}

/// challenge 只接受规范小写 hex，避免不同层对 token identity 产生大小写分歧。
#[test]
fn ready_token_install_rejects_uppercase_shape() {
    let (server_reader, _server_writer) = pipe_pair();
    let (ack_sender, _ack_receiver) = mpsc::channel();
    let session = session_from_io(
        server_reader,
        AckWriter { writes: ack_sender },
        EmptyReader,
        35,
        Limits::default(),
    )
    .unwrap();
    assert_eq!(
        session.install_ready_token_challenge("0123456789ABCDEF0123456789ABCDEF".to_owned()),
        Err(app_server_process::AppServerProcessError::HandshakeFailed)
    );
    close_session(&session);
}

/// writer 永久阻塞时 watchdog 必须关闭准入并允许显式 release 后完成 join，防止泄漏 actor。
#[test]
fn blocked_writer_watchdog_fails_closed_and_unblocks_operation() {
    let reader_release = Arc::new(AtomicBool::new(false));
    let writer_release = Arc::new(AtomicBool::new(false));
    let (entered_sender, entered_receiver) = mpsc::channel();
    let (finished_sender, finished_receiver) = mpsc::channel();
    let (terminal_sender, terminal_receiver) = mpsc::channel();
    let reader = ControlledReader {
        release: Arc::clone(&reader_release),
    };
    let writer = BlockingWriter {
        entered: entered_sender,
        release: Arc::clone(&writer_release),
        finished: finished_sender,
    };
    let callback_release = Arc::clone(&writer_release);
    let callback_reader = Arc::clone(&reader_release);
    let callback: TerminalCallback = Arc::new(move |reason: TerminalReason| {
        callback_release.store(true, Ordering::Release);
        callback_reader.store(true, Ordering::Release);
        let _ = terminal_sender.send(reason);
    });
    let session = session_with_terminal_watchdog(
        reader,
        writer,
        EmptyReader,
        33,
        Limits::default(),
        Some(callback),
        Duration::from_millis(40),
    )
    .unwrap();
    let watchdog_started = Instant::now();
    let request_session = session.clone();
    let blocked_request = thread::spawn(move || {
        request_session.request(
            "turn/start",
            json!({"input": "x".repeat(900 * 1024)}),
            Duration::from_secs(2),
        )
    });
    entered_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("writer operation entered");
    assert_eq!(
        terminal_receiver
            .recv_timeout(Duration::from_secs(1))
            .expect("watchdog terminal callback"),
        TerminalReason::Fault
    );
    assert!(
        watchdog_started.elapsed() < Duration::from_millis(500),
        "terminal callback exceeded bounded watchdog envelope"
    );
    finished_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("callback must unblock the blocked writer");
    assert_eq!(
        blocked_request.join().expect("blocked request thread"),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert_eq!(
        session.request("runtime/health", json!({}), Duration::from_millis(20)),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    close_session(&session);
    session
        .close_until(Instant::now() + Duration::from_secs(1))
        .expect("fake writer actor must be joined after callback cancellation");
}

/// 即使 write 随后完成，watchdog timeout 仍是终态；ready 必须停在失败 barrier 后，
/// cleanup 仍须完成 join。
#[test]
fn late_write_after_watchdog_timeout_cannot_promote_ready() {
    let reader_release = Arc::new(AtomicBool::new(false));
    let writer_release = Arc::new(AtomicBool::new(false));
    let (server_reader, server_writer) = pipe_pair();
    let token = "0123456789abcdef0123456789abcdef";
    let ready_frame = RpcFrame::notification(
        "runtime/status-changed",
        json!({
            "serverInstanceId":"srv_fixture",
            "eventId":"evt_late_watchdog",
            "occurredAt":"2099-01-01T00:00:00Z",
            "status":"ready",
            "readyToken":token
        }),
    )
    .unwrap();
    let ready = ready_frame
        .encode(Limits::default().max_frame_bytes)
        .unwrap();
    let (ready_sent_sender, ready_sent_receiver) = mpsc::channel();
    let (frame_read_sender, frame_read_receiver) = mpsc::channel();
    let (write_done_sender, write_done_receiver) = mpsc::channel();
    let (terminal_sender, terminal_receiver) = mpsc::channel();
    let writer_release_for_callback = Arc::clone(&writer_release);
    let reader_release_for_callback = Arc::clone(&reader_release);
    let callback: TerminalCallback = Arc::new(move |reason: TerminalReason| {
        writer_release_for_callback.store(true, Ordering::Release);
        reader_release_for_callback.store(true, Ordering::Release);
        let _ = terminal_sender.send(reason);
    });
    let session = session_with_terminal_watchdog(
        LateWatchdogReader {
            inner: server_reader,
            frame_read: frame_read_sender,
            release: Arc::clone(&reader_release),
            first_read: true,
        },
        LateWatchdogReadyWriter {
            ready_sender: server_writer.sender.clone(),
            ready_payload: Some(ready),
            ready_sent: ready_sent_sender,
            release_write: Arc::clone(&writer_release),
            write_done: write_done_sender,
        },
        EmptyReader,
        142,
        Limits::default(),
        Some(callback),
        Duration::from_millis(40),
    )
    .unwrap();
    session
        .install_ready_token_challenge(token.to_owned())
        .unwrap();
    let mut pump = session.take_event_pump().unwrap();
    session
        .notify("runtime/initialized", json!({"readyToken":token}))
        .unwrap();
    ready_sent_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("ready sent before late write completion");
    frame_read_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("ready frame read before watchdog timeout");
    assert_eq!(
        terminal_receiver
            .recv_timeout(Duration::from_secs(1))
            .expect("watchdog terminal callback"),
        TerminalReason::Fault
    );
    write_done_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("late write completed after timeout");
    assert!(!wait_initialized_confirmation(
        &session,
        Duration::from_millis(50)
    ));
    assert!(!ready_after_initialized_barrier(&session, &ready_frame));

    for _ in 0..3 {
        match pump.next_event(Duration::from_secs(1)) {
            Some(SessionEvent::Notification(_)) => {
                panic!("ready notification must not pass a timed-out barrier")
            }
            Some(SessionEvent::HandshakeFailed) => {
                // closed queue 可能保留该 terminal 分类；无论哪种结果都不得投递 ready notification。
            }
            Some(SessionEvent::WriterTimedOut | SessionEvent::ProtocolFault(_)) => {}
            Some(SessionEvent::Eof) => {}
            Some(_) | None => break,
        }
    }
    close_session(&session);
    session
        .close_until(Instant::now() + Duration::from_secs(1))
        .expect("late writer must be joined after watchdog timeout");
}
