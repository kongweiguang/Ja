// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// Session 请求账本与事件队列溢出不变量测试。

use super::*;
use crate::app_server_process::protocol::{self as codec, Limits, RpcFrame};
use crate::unit_support_tests::session_from_io;
use std::time::Duration;

/// 证明 outbound c: ID 由单调整数保证不复用，不需要随请求总量增长的 seen 集合。
#[test]
fn outbound_request_ids_are_never_reused_or_evicted() {
    let mut ledger = OutboundRequestLedger::new();
    let mut seen = HashSet::new();
    for _ in 0..10_000 {
        assert!(seen.insert(ledger.allocate().unwrap()));
    }
    ledger.next = u64::MAX;
    assert_eq!(
        ledger.allocate(),
        Err(AppServerProcessError::RequestLedgerExhausted)
    );
}

/// 同一 generation 的重复 terminal 必须只投递一次并关闭协议，不能让 UI
/// reducer 对同一 Turn 执行两次终态副作用。
#[test]
fn duplicate_turn_terminal_is_delivered_once_then_faults() {
    use std::io::Cursor;

    let terminal = RpcFrame::notification(
        "turn/terminal",
        serde_json::json!({
            "serverInstanceId": "srv_one",
            "eventId": "evt_terminal_one",
            "threadId": "thr_one",
            "turnId": "turn_one",
            "threadRevision": 3,
            "occurredAt": "2026-08-25T12:00:00Z",
            "state": "completed",
            "summary": "done"
        }),
    )
    .unwrap()
    .encode(Limits::default().max_frame_bytes)
    .unwrap();
    let mut input = terminal.clone();
    input.extend_from_slice(&terminal);
    let session = session_from_io(
        Cursor::new(input),
        Cursor::new(Vec::<u8>::new()),
        Cursor::new(Vec::<u8>::new()),
        1,
        Limits::default(),
    )
    .unwrap();
    let mut events = session.take_event_pump().unwrap();
    let mut terminal_count = 0;
    let mut protocol_fault = false;
    while let Some(event) = events.next_event(Duration::from_secs(1)) {
        match event {
            SessionEvent::Notification(frame) if frame.method() == Some("turn/terminal") => {
                terminal_count += 1;
            }
            SessionEvent::ProtocolFault(codec::CodecError::InvalidEnvelope) => {
                protocol_fault = true;
            }
            _ => {}
        }
    }
    assert_eq!(terminal_count, 1);
    assert!(protocol_fault);
}

/// 证明 data overflow 只发布一次可重试事件，不会把 session 错误关闭。
#[test]
fn data_overflow_is_nonfatal_and_reported_once() {
    let queue = EventQueue::new(1, Limits::default().max_frame_bytes);
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification("assistant/text-delta", serde_json::json!({})).unwrap(),
        ),
        EventPriority::Data,
        QueueKind::Data,
    );
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification("assistant/reasoning-summary-delta", serde_json::json!({}))
                .unwrap(),
        ),
        EventPriority::Data,
        QueueKind::Data,
    );
    assert_eq!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::QueueOverflow(QueueKind::Data))
    );
    assert!(matches!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::Notification(_))
    ));
    assert_eq!(queue.pop(Duration::ZERO), None);
}

/// 证明 control overflow 仍是 fatal，防止 shutdown/approval 事实被静默丢弃。
#[test]
fn control_overflow_is_fatal() {
    let queue = EventQueue::new(1, Limits::default().max_frame_bytes);
    for _ in 0..=CONTROL_QUEUE_CAPACITY {
        queue.push(
            SessionEvent::Eof,
            EventPriority::Control,
            QueueKind::Control,
        );
    }
    assert_eq!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::QueueFatalOverflow(QueueKind::Control))
    );
}
