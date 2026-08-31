// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// 测试与生产实现分文件，既保持对模块私有不变量的覆盖，也避免生产文件承载测试体。

use super::*;
use std::sync::Mutex;
use std::time::Duration;

/// 只在外置测试中制造真实 std mutex poison，避免为生产类型暴露 test hook。
fn poison_mutex<T: Send>(mutex: &Mutex<T>) {
    std::thread::scope(|scope| {
        let worker = scope.spawn(|| {
            let _guard = match mutex.lock() {
                Ok(guard) => guard,
                Err(_) => return,
            };
            panic!("intentional queue poison");
        });
        assert!(worker.join().is_err());
    });
}

/// 队列满时必须立即失败而不是等待未定义时间，保护 IPC 调用线程。
#[test]
fn input_queue_is_bounded_by_bytes() {
    let queue = InputQueue::new(3);
    assert!(queue.push(vec![1, 2, 3]).is_ok());
    assert_eq!(
        queue.push(vec![4]).unwrap_err().code(),
        TerminalErrorCode::QueueFull
    );
    assert_eq!(queue.pop(), Some(vec![1, 2, 3]));
}

/// 终态必须能从满 output 队列中胜出，避免 UI 慢时无法显示关闭原因。
#[test]
fn terminal_event_replaces_queued_output() {
    let id = TerminalId::new();
    let queue = EventQueue::new(id, 1, 80, 8);
    assert!(queue.push_output(vec![1; 16]));
    assert!(queue.push_terminal(TerminalEventKind::Closed {
        reason: crate::terminal::model::CloseReason::User,
    }));
    let event = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .unwrap();
    assert!(matches!(event.kind, TerminalEventKind::Closed { .. }));
}

/// output 压力必须精确统计 rejected chunk 与为 marker 回收的 newest queued output，
/// marker 被消费后普通 output 应恢复。
#[test]
fn output_pressure_reports_delta_and_resumes() {
    let queue = EventQueue::new(TerminalId::new(), 1, 192, 3);
    assert!(queue.push_output(vec![1; 32]));
    assert!(queue.push_output(vec![2; 32]));
    assert!(queue.push_output(vec![3; 16]));

    let first = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .expect("retained output");
    assert!(matches!(
        first.kind,
        TerminalEventKind::Output { data } if data == vec![1; 32]
    ));
    let dropped = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .expect("drop delta");
    assert!(matches!(
        dropped.kind,
        TerminalEventKind::OutputDropped { bytes: 48 }
    ));

    assert!(queue.push_output(vec![4; 8]));
    let resumed = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .expect("resumed output");
    assert!(matches!(
        resumed.kind,
        TerminalEventKind::Output { data } if data == vec![4; 8]
    ));
}

/// 单个 pending marker 用 saturating arithmetic 吸收重复压力，stalled renderer 不能制造无界通知风暴。
#[test]
fn output_drop_delta_merges_and_saturates() {
    let accumulated = EventQueue::new(TerminalId::new(), 1, 128, 2);
    for _ in 0..5 {
        assert!(accumulated.push_output(vec![7; 100]));
    }
    let dropped = accumulated
        .recv_until(Instant::now() + Duration::from_secs(1))
        .expect("accumulated drop delta");
    assert!(matches!(
        dropped.kind,
        TerminalEventKind::OutputDropped { bytes: 500 }
    ));
    assert!(
        accumulated
            .recv_until(Instant::now() + Duration::from_millis(1))
            .is_none()
    );

    let queue = EventQueue::new(TerminalId::new(), 1, 128, 2);
    {
        let mut state = match queue.state.lock() {
            Ok(state) => state,
            Err(_) => panic!("fresh queue state must not be poisoned"),
        };
        queue.record_output_drop(&mut state, MAX_OUTPUT_DROPPED_BYTES - 3);
    }
    assert!(queue.push_output(vec![9; 8]));
    let dropped = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .expect("saturated drop delta");
    assert!(matches!(
        dropped.kind,
        TerminalEventKind::OutputDropped {
            bytes: MAX_OUTPUT_DROPPED_BYTES
        }
    ));
    assert!(
        queue
            .recv_until(Instant::now() + Duration::from_millis(1))
            .is_none()
    );
}

/// input byte accounting poison 后必须丢弃未确认输入并唤醒 writer，不得继续消费旧队列。
#[test]
fn poisoned_input_queue_rebuilds_as_empty_and_closed() {
    let queue = InputQueue::new(16);
    assert!(queue.push(vec![1, 2, 3]).is_ok());
    poison_mutex(&queue.state);

    assert_eq!(
        queue
            .push(vec![4])
            .expect_err("poison must close input")
            .code(),
        TerminalErrorCode::QueueClosed
    );
    assert_eq!(queue.pop(), None);
    let state = match queue.state.lock() {
        Ok(state) => state,
        Err(_) => panic!("failed queue must be rebuilt, not left poisoned"),
    };
    assert!(state.closed);
    assert_eq!(state.bytes, 0);
    assert!(state.queue.is_empty());
}

/// event sequence poison 后无法证明编号连续，必须清空并永久拒绝所有 late producer。
#[test]
fn poisoned_event_queue_discards_untrusted_sequence_and_closes() {
    let queue = EventQueue::new(TerminalId::new(), 1, 256, 8);
    assert!(queue.push_output(vec![1, 2, 3]));
    poison_mutex(&queue.state);

    assert!(!queue.push_output(vec![4]));
    assert!(!queue.push_control(TerminalEventKind::Resized {
        size: crate::terminal::model::TerminalSize::default(),
    }));
    assert!(
        queue
            .recv_until(Instant::now() + Duration::from_millis(10))
            .is_none()
    );
    let state = match queue.state.lock() {
        Ok(state) => state,
        Err(_) => panic!("failed event queue must be rebuilt, not left poisoned"),
    };
    assert!(state.closed);
    assert_eq!(state.bytes, 0);
    assert!(state.queue.is_empty());
}

/// 为 terminal fact 预留空间必须累计所有淘汰的 output byte，并把有界 delta 保持在终态之前。
#[test]
fn terminal_pressure_reports_evicted_output_delta() {
    let queue = EventQueue::new(TerminalId::new(), 1, 192, 3);
    assert!(queue.push_output(vec![1; 32]));
    assert!(queue.push_output(vec![2; 32]));
    assert!(queue.push_terminal(TerminalEventKind::Closed {
        reason: crate::terminal::model::CloseReason::User,
    }));
    let dropped = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .expect("terminal drop delta");
    assert!(matches!(
        dropped.kind,
        TerminalEventKind::OutputDropped { bytes: 64 }
    ));
    let terminal = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .expect("terminal fact");
    assert!(matches!(terminal.kind, TerminalEventKind::Closed { .. }));
}

/// 终态之后到达的 reader bytes 必须被丢弃，且事件身份固定在原 generation。
#[test]
fn late_output_is_rejected_after_terminal() {
    let id = TerminalId::new();
    let queue = EventQueue::new(id, 7, 256, 8);
    assert!(queue.push_terminal(TerminalEventKind::Closed {
        reason: crate::terminal::model::CloseReason::Timeout,
    }));
    assert!(!queue.push_output(vec![0xff, 0x1b, b'[', b'2', b'J']));
    let event = queue
        .recv_until(Instant::now() + Duration::from_secs(1))
        .unwrap();
    assert_eq!(event.generation, 7);
}
