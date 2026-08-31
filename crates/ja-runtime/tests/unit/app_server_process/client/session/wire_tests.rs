// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 私有算法单元测试；与生产实现分文件，避免测试 seam 扩大公共 API。

use super::*;
use crate::unit_support_tests::poison_mutex;
use std::sync::atomic::AtomicBool;

/// 证明数据预算耗尽时控制保留区仍可承载审批/关闭帧，避免单一总计数造成死锁。
#[test]
fn data_budget_cannot_consume_control_reserve() {
    let (control_sender, _control_receiver) = mpsc::sync_channel(4);
    let (data_sender, _data_receiver) = mpsc::sync_channel(4);
    let handle = WriterHandle::new(
        control_sender,
        data_sender,
        Arc::new(AtomicBool::new(false)),
        Arc::new(AtomicUsize::new(0)),
        Arc::new(AtomicUsize::new(0)),
        4,
        4,
    );

    assert!(handle.send(vec![1, 2, 3, 4], EventPriority::Data).is_ok());
    assert_eq!(
        handle.send(vec![5], EventPriority::Data),
        Err(AppServerProcessError::QueueFull(QueueKind::Data))
    );
    assert!(
        handle
            .send(vec![6, 7, 8, 9], EventPriority::Control)
            .is_ok()
    );
}

/// writer wake sequence 中毒后 send 必须关闭 handle 并返回 ProtocolFault；已入队 frame
/// 不得在失去 lost-wake 证明后继续等待不确定的 actor 调度。
#[test]
fn poisoned_writer_wake_rejects_enqueue_and_closes_handle() {
    let (control_sender, _control_receiver) = mpsc::sync_channel(2);
    let (data_sender, _data_receiver) = mpsc::sync_channel(2);
    let closed = Arc::new(AtomicBool::new(false));
    let handle = WriterHandle::new(
        control_sender,
        data_sender,
        Arc::clone(&closed),
        Arc::new(AtomicUsize::new(0)),
        Arc::new(AtomicUsize::new(0)),
        128,
        128,
    );
    poison_mutex(&handle.wake.sequence);

    assert_eq!(
        handle.send(vec![1], EventPriority::Control),
        Err(AppServerProcessError::ProtocolFault)
    );
    assert!(closed.load(Ordering::Acquire));
}

/// 派生 reserve 必须容纳一个达到协商上限且包含 JSONL 换行的完整 frame。
#[test]
fn control_budget_accepts_negotiated_frame_boundary() {
    let max_frame = 1_024;
    let (control_sender, _control_receiver) = mpsc::sync_channel(2);
    let (data_sender, _data_receiver) = mpsc::sync_channel(2);
    let handle = WriterHandle::new(
        control_sender,
        data_sender,
        Arc::new(AtomicBool::new(false)),
        Arc::new(AtomicUsize::new(0)),
        Arc::new(AtomicUsize::new(0)),
        control_queue_byte_budget(max_frame),
        MAX_WRITER_DATA_QUEUE_BYTES,
    );
    assert!(
        handle
            .send(
                vec![b'x'; control_queue_byte_budget(max_frame)],
                EventPriority::Control
            )
            .is_ok()
    );
}

/// 连续 control 生产具有 burst 上限，使已入队 data frame 最终被写出而不会永久饥饿。
#[test]
fn control_burst_allows_data_progress() {
    let (control_sender, control_receiver) = mpsc::sync_channel(32);
    let (data_sender, data_receiver) = mpsc::sync_channel(4);
    for index in 0..16 {
        control_sender
            .send(vec![index as u8])
            .expect("control fixture queued");
    }
    data_sender.send(vec![99]).expect("data fixture queued");
    let mut burst = 0;
    for _ in 0..MAX_CONTROL_BURST {
        assert_eq!(
            next_frame(&control_receiver, &data_receiver, &mut burst)
                .expect("control frame available")
                .1,
            EventPriority::Control
        );
    }
    assert_eq!(
        next_frame(&control_receiver, &data_receiver, &mut burst)
            .expect("data must make progress")
            .1,
        EventPriority::Data
    );
}

/// 精确 terminal method 使用 control reserve；其它普通 turn/item notification 仍属 data，
/// Context 生命周期由独立测试锁定，避免普通 delta 消耗 control 空间。
#[test]
fn exact_turn_terminal_uses_control_routing() {
    let valid = RpcFrame::notification(
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
    .expect("valid terminal notification");
    assert_eq!(
        notification_routing(&valid),
        (EventPriority::Control, QueueKind::Control)
    );

    let minimal_terminal = RpcFrame::notification("turn/terminal", serde_json::json!({}))
        .expect("notification envelope");
    assert_eq!(
        notification_routing(&minimal_terminal),
        (EventPriority::Control, QueueKind::Control)
    );

    let ordinary_turn_event = RpcFrame::notification(
        "turn/state-changed",
        serde_json::json!({
            "serverInstanceId": "srv_one",
            "eventId": "evt_turn_one",
            "threadId": "thr_one",
            "turnId": "turn_one",
            "threadRevision": 1,
            "occurredAt": "2026-08-24T00:00:00Z",
            "from": "queued",
            "to": "running"
        }),
    )
    .expect("ordinary turn notification");
    assert_eq!(
        notification_routing(&ordinary_turn_event),
        (EventPriority::Data, QueueKind::Data)
    );
}

/// 上下文压缩终态不能因普通 delta 背压丢失，三类生命周期统一占用 control reserve。
#[test]
fn context_compaction_notifications_use_control_lane() {
    for method in [
        "context/compaction-started",
        "context/compacted",
        "context/compaction-failed",
    ] {
        let frame = RpcFrame::notification(method, serde_json::json!({})).expect("notification");
        assert_eq!(
            notification_routing(&frame),
            (EventPriority::Control, QueueKind::Control)
        );
    }
}
