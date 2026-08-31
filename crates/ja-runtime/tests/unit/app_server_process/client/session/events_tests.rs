// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::*;
use crate::unit_support_tests::poison_mutex;

/// data 接近 64 MiB 时仍必须保留独立 control/overflow 预算，不能伪造 fatal。
#[test]
fn data_near_cap_keeps_control_notice_reachable() {
    let queue = EventQueue::new(128, 4 * 1024 * 1024);
    for _ in 0..64 {
        queue.push(
            SessionEvent::StderrLine("x".repeat(1024 * 1024)),
            EventPriority::Data,
            QueueKind::Stderr,
        );
    }
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification("runtime/status-changed", serde_json::json!({})).unwrap(),
        ),
        EventPriority::Control,
        QueueKind::Control,
    );
    queue.push(
        SessionEvent::StderrLine("overflow".to_owned()),
        EventPriority::Data,
        QueueKind::Stderr,
    );
    assert!(matches!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::Notification(_))
    ));
    assert!(matches!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::QueueOverflow(QueueKind::Stderr))
    ));
    assert!(!queue.is_fatal());
}

/// event reserve 与 writer 使用同一协商 frame 预算，确保合法 control frame 不会被路由拒绝。
#[test]
fn control_budget_tracks_frame_limit() {
    let max_frame = 1_024;
    let queue = EventQueue::new(4, max_frame);
    assert_eq!(
        queue.max_control_bytes,
        control_queue_byte_budget(max_frame)
    );
    queue.push(
        SessionEvent::Eof,
        EventPriority::Control,
        QueueKind::Control,
    );
    assert!(!queue.is_fatal());
}

/// 连续 runtime control event 在有限 burst 后必须让已入队 data event 前进，同时不能
/// 隐藏 approval request。
#[test]
fn control_burst_allows_data_progress() {
    let queue = EventQueue::new(32, 4 * 1024 * 1024);
    for _ in 0..16 {
        queue.push(
            SessionEvent::Notification(
                RpcFrame::notification("runtime/status-changed", serde_json::json!({})).unwrap(),
            ),
            EventPriority::Control,
            QueueKind::Control,
        );
    }
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification("assistant/text-delta", serde_json::json!({})).unwrap(),
        ),
        EventPriority::Data,
        QueueKind::Data,
    );
    for _ in 0..MAX_EVENT_CONTROL_BURST {
        assert!(matches!(
            queue.pop(Duration::ZERO),
            Some(SessionEvent::Notification(_))
        ));
    }
    let Some(SessionEvent::Notification(frame)) = queue.pop(Duration::ZERO) else {
        panic!("data event must make progress");
    };
    assert_eq!(frame.method(), Some("assistant/text-delta"));
}

/// data lane 饱和时只报告一次 overflow，但仍经 control 投递唯一 terminal 事实，使 UI
/// 可以可靠关闭 Turn。
#[test]
fn turn_terminal_survives_data_overflow_exactly_once() {
    let queue = EventQueue::new(1, 4 * 1024 * 1024);
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification("assistant/reasoning-summary-delta", serde_json::json!({}))
                .unwrap(),
        ),
        EventPriority::Data,
        QueueKind::Data,
    );
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification("assistant/text-delta", serde_json::json!({})).unwrap(),
        ),
        EventPriority::Data,
        QueueKind::Data,
    );
    let terminal = RpcFrame::notification(
        "turn/terminal",
        serde_json::json!({
            "serverInstanceId": "srv_one",
            "eventId": "evt_terminal_one",
            "threadId": "thr_one",
            "turnId": "turn_one",
            "threadRevision": 1,
            "occurredAt": "2026-08-25T12:00:00Z",
            "state": "completed",
            "summary": "completed"
        }),
    )
    .unwrap();
    queue.push(
        SessionEvent::Notification(terminal),
        EventPriority::Control,
        QueueKind::Control,
    );

    let mut overflow_count = 0;
    let mut terminal_count = 0;
    let mut dropped_delta_count = 0;
    while let Some(event) = queue.pop(Duration::ZERO) {
        match event {
            SessionEvent::QueueOverflow(QueueKind::Data) => overflow_count += 1,
            SessionEvent::Notification(frame) if frame.method() == Some("turn/terminal") => {
                terminal_count += 1
            }
            SessionEvent::Notification(frame) if frame.method() == Some("assistant/text-delta") => {
                dropped_delta_count += 1
            }
            _ => {}
        }
    }
    assert_eq!(overflow_count, 1);
    assert_eq!(terminal_count, 1);
    assert_eq!(dropped_delta_count, 0);
    assert!(!queue.is_fatal());
}

/// terminal frame 使用保留 control lane，但不能越过同 Thread 的较早 item frame，
/// 避免健康 UI projection 产生虚假的 sequence gap。
#[test]
fn turn_terminal_waits_for_prior_same_thread_data() {
    let queue = EventQueue::new(8, 4 * 1024 * 1024);
    let event = |method: &str, seq: u64| {
        let mut params = serde_json::json!({
            "threadId": "thr_one",
            "threadRevision": seq
        });
        if method == "turn/terminal" {
            params["serverInstanceId"] = serde_json::json!("srv_one");
        }
        SessionEvent::Notification(
            RpcFrame::notification(method, params).expect("ordered event fixture"),
        )
    };
    queue.push(
        event("tool/batch-committed", 5),
        EventPriority::Data,
        QueueKind::Data,
    );
    queue.push(
        event("turn/terminal", 6),
        EventPriority::Control,
        QueueKind::Control,
    );

    let Some(SessionEvent::Notification(first)) = queue.pop(Duration::ZERO) else {
        panic!("prior data event must be delivered");
    };
    assert_eq!(first.method(), Some("tool/batch-committed"));
    let Some(SessionEvent::Notification(second)) = queue.pop(Duration::ZERO) else {
        panic!("terminal event must remain deliverable");
    };
    assert_eq!(second.method(), Some("turn/terminal"));
}

/// close 清除普通 data 与 overflow notice，但保留已入队 Turn terminal，使并发 shutdown
/// 仍能保存最终状态。
#[test]
fn close_retains_turn_terminal_once() {
    let queue = EventQueue::new(1, 4 * 1024 * 1024);
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification("assistant/text-delta", serde_json::json!({})).unwrap(),
        ),
        EventPriority::Data,
        QueueKind::Data,
    );
    queue.push(
        SessionEvent::Notification(
            RpcFrame::notification(
                "turn/terminal",
                serde_json::json!({
                    "serverInstanceId": "srv_one",
                    "eventId": "evt_terminal_one",
                    "threadId": "thr_one",
                    "turnId": "turn_one",
                    "threadRevision": 1,
                    "occurredAt": "2026-08-25T12:00:00Z",
                    "state": "failed",
                    "summary": "failed"
                }),
            )
            .unwrap(),
        ),
        EventPriority::Control,
        QueueKind::Control,
    );
    queue.close();
    assert!(matches!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::Notification(frame)) if frame.method() == Some("turn/terminal")
    ));
    assert!(queue.pop(Duration::ZERO).is_none());
}

/// control lane 真正耗尽时仍为 fatal；terminal reserve 不创建隐藏的第二队列，也不允许
/// control 无界增长。
#[test]
fn control_saturation_remains_fatal() {
    let queue = EventQueue::new(1, 4 * 1024);
    for _ in 0..CONTROL_QUEUE_CAPACITY + 1 {
        queue.push(
            SessionEvent::Notification(
                RpcFrame::notification("runtime/status-changed", serde_json::json!({})).unwrap(),
            ),
            EventPriority::Control,
            QueueKind::Control,
        );
    }
    assert!(queue.is_fatal());
    assert!(matches!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::QueueFatalOverflow(QueueKind::Control))
    ));
}

/// 事件账本中毒后必须只投递一次 control fatal 并拒绝继续消费队列内容，证明
/// fail-closed 路径不读取可能部分更新的 data/control 字节账本。
#[test]
fn poisoned_event_state_reports_one_fatal_without_recovery() {
    let queue = EventQueue::new(4, 4 * 1024);
    poison_mutex(&queue.state);

    queue.push(
        SessionEvent::Eof,
        EventPriority::Control,
        QueueKind::Control,
    );
    assert!(queue.is_fatal());
    assert!(matches!(
        queue.pop(Duration::ZERO),
        Some(SessionEvent::QueueFatalOverflow(QueueKind::Control))
    ));
    assert!(queue.pop(Duration::ZERO).is_none());
}
