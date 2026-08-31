// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// Session 关闭、准入 gate、pending 唤醒与 EventPump 所有权测试。

use super::*;
use crate::app_server_process;
use crate::app_server_process::protocol::{self as codec, CodecError, Limits};
use crate::unit_support_tests::*;
use serde_json::json;
use std::sync::mpsc;
use std::sync::{Arc, Barrier, Mutex};
use std::thread;
use std::time::Duration;

#[test]
fn session_rejects_non_client_request_frame() {
    let frame = br#"{"jsonrpc":"2.0","id":"s:forbidden","method":"configuration/read","params":{}}
"#;
    assert_eq!(
        codec::decode_frame(frame, Limits::default().max_frame_bytes),
        Err(CodecError::InvalidId)
    );
}

/// stdout EOF 与 writer fault 都必须一次性关闭 pending，确保调用方不会无限等待。
#[test]
fn session_closes_pending_on_eof_and_writer_fault() {
    let (_server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let (host_to_server_reader, host_to_server_writer) = pipe_pair();
    let session = session_from_io(
        EmptyReader,
        host_to_server_writer,
        EmptyReader,
        8,
        Limits::default(),
    )
    .unwrap();
    let mut event_pump = session.take_event_pump().unwrap();
    drop(host_to_server_reader);
    assert!(matches!(
        event_pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::Eof)
    ));
    assert!(matches!(
        session.request("runtime/health", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    ));
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let session = session_from_io(
        server_to_host_reader,
        FailingWriter,
        EmptyReader,
        9,
        Limits::default(),
    )
    .unwrap();
    let mut event_pump = session.take_event_pump().unwrap();
    assert_eq!(
        session.request("runtime/health", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert!(matches!(
        event_pump.next_event(Duration::from_secs(1)),
        Some(SessionEvent::ProtocolFault(CodecError::Io))
    ));
    assert!(matches!(
        session.request("runtime/health", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    ));
}

/// 并发上限必须在 writer 前执行；关闭 session 后所有已准入 waiter 都收到同一终态。
#[test]
fn session_enforces_minimum_in_flight_and_closes_waiters() {
    let (ack_sender, ack_receiver) = mpsc::channel();
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let limits = Limits {
        max_in_flight_requests: 2,
        max_pending_requests: 8,
        ..Limits::default()
    };
    let session = session_from_io(
        server_to_host_reader,
        AckWriter { writes: ack_sender },
        EmptyReader,
        10,
        limits,
    )
    .unwrap();
    let first = session.clone();
    let first_waiter =
        thread::spawn(move || first.request("parallel/one", json!({}), Duration::from_secs(10)));
    let second = session.clone();
    let second_waiter =
        thread::spawn(move || second.request("parallel/two", json!({}), Duration::from_secs(10)));
    ack_receiver.recv_timeout(Duration::from_secs(1)).unwrap();
    ack_receiver.recv_timeout(Duration::from_secs(1)).unwrap();
    assert_eq!(
        session.request("parallel/three", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::PendingLimit)
    );
    close_session(&session);
    assert_eq!(
        first_waiter.join().unwrap(),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
    assert_eq!(
        second_waiter.join().unwrap(),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
}

/// close 通过 completion channel 唤醒请求，避免用 sleep 轮询造成不确定的释放延迟。
#[test]
fn session_close_during_wait_wakes_request_without_sleep() {
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let (host_to_server_reader, host_to_server_writer) = pipe_pair();
    let session = session_from_io(
        server_to_host_reader,
        host_to_server_writer,
        EmptyReader,
        11,
        Limits::default(),
    )
    .unwrap();
    let caller = session.clone();
    let waiter = thread::spawn(move || caller.request("wait", json!({}), Duration::from_secs(10)));
    drop(host_to_server_reader);
    close_session(&session);
    assert_eq!(
        waiter.join().unwrap(),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
}

/// EventPump 只能被唯一 owner 取得，且 shutdown gate 必须与并发请求准入保持线性顺序。
#[test]
fn event_pump_is_take_once_and_shutdown_gate_is_linearized() {
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let (ack_sender, ack_receiver) = mpsc::channel();
    let session = session_from_io(
        server_to_host_reader,
        AckWriter { writes: ack_sender },
        EmptyReader,
        13,
        Limits::default(),
    )
    .unwrap();
    let _pump = session.take_event_pump().unwrap();
    assert!(matches!(
        session.take_event_pump(),
        Err(app_server_process::AppServerProcessError::InvalidState)
    ));

    let gate = Arc::new(Mutex::new(false));
    let barrier = Arc::new(Barrier::new(2));
    let caller = session.clone();
    let caller_gate = Arc::clone(&gate);
    let caller_barrier = Arc::clone(&barrier);
    let waiter = thread::spawn(move || {
        caller_barrier.wait();
        caller.request_with_gate(
            "race/first",
            json!({}),
            Duration::from_secs(5),
            &caller_gate,
        )
    });
    barrier.wait();
    ack_receiver
        .recv_timeout(Duration::from_secs(1))
        .expect("first request crossed admission gate");
    let Ok(mut stopping) = gate.lock() else {
        panic!("admission fixture gate must remain healthy");
    };
    *stopping = true;
    drop(stopping);
    assert_eq!(
        session.request_with_gate("race/second", json!({}), Duration::from_secs(1), &gate),
        Err(app_server_process::AppServerProcessError::ShuttingDown)
    );
    close_session(&session);
    assert_eq!(
        waiter.join().unwrap(),
        Err(app_server_process::AppServerProcessError::SessionClosed)
    );
}

/// request ID 账本中毒后不能从可能重复的 next 值继续分配；Session 必须关闭并返回
/// 稳定 ProtocolFault，避免重复 ID 被写入 sidecar。
#[test]
fn poisoned_request_ledger_fails_closed_before_writer_enqueue() {
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let (host_to_server_reader, host_to_server_writer) = pipe_pair();
    let session = session_from_io(
        server_to_host_reader,
        host_to_server_writer,
        EmptyReader,
        14,
        Limits::default(),
    )
    .expect("session fixture");
    poison_mutex(&session.inner.outbound_request_ids);

    assert_eq!(
        session.request("workspace/list", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::ProtocolFault)
    );
    assert!(
        session
            .inner
            .closed
            .load(std::sync::atomic::Ordering::Acquire)
    );
    drop(host_to_server_reader);
    close_session(&session);
}

/// pending registry 中毒后 request 必须在注册阶段返回 ProtocolFault 且不自锁；
/// fail-closed cleanup 只有在 PoisonError guard 已释放后才能再次触及同一 registry。
#[test]
fn poisoned_pending_registry_fails_closed_without_deadlock() {
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let (host_to_server_reader, host_to_server_writer) = pipe_pair();
    let session = session_from_io(
        server_to_host_reader,
        host_to_server_writer,
        EmptyReader,
        16,
        Limits::default(),
    )
    .expect("session fixture");
    poison_mutex(&session.inner.pending);

    assert_eq!(
        session.request("workspace/list", json!({}), Duration::from_secs(1)),
        Err(app_server_process::AppServerProcessError::ProtocolFault)
    );
    assert!(
        session
            .inner
            .closed
            .load(std::sync::atomic::Ordering::Acquire)
    );
    drop(host_to_server_reader);
    close_session(&session);
}

/// admission gate 中毒表示 shutdown 与新请求的先后关系已不可证明；Session 必须按
/// ShuttingDown 拒绝并关闭，而不是读取 poisoned bool 猜测允许发送。
#[test]
fn poisoned_admission_gate_rejects_and_closes_session() {
    let (server_to_host_reader, _server_to_host_writer) = pipe_pair();
    let (host_to_server_reader, host_to_server_writer) = pipe_pair();
    let session = session_from_io(
        server_to_host_reader,
        host_to_server_writer,
        EmptyReader,
        15,
        Limits::default(),
    )
    .expect("session fixture");
    let gate = Mutex::new(false);
    poison_mutex(&gate);

    assert_eq!(
        session.request_with_gate("workspace/list", json!({}), Duration::from_secs(1), &gate,),
        Err(app_server_process::AppServerProcessError::ShuttingDown)
    );
    assert!(
        session
            .inner
            .closed
            .load(std::sync::atomic::Ordering::Acquire)
    );
    drop(host_to_server_reader);
    close_session(&session);
}
