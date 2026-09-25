// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

// Worker 并发测试与生产实现分文件，并通过模块私有观察端口固定难以复现的调度交错。

use super::*;
use std::sync::Barrier;
use std::sync::atomic::AtomicUsize;

/// 借助测试模块的私有可见性制造 poison，生产 worker tracker 不扩大 API。
fn poison_mutex<T: Send>(mutex: &Mutex<T>) {
    thread::scope(|scope| {
        let worker = scope.spawn(|| {
            let _guard = match mutex.lock() {
                Ok(guard) => guard,
                Err(_) => return,
            };
            panic!("intentional worker tracker poison");
        });
        assert!(worker.join().is_err());
    });
}

/// 模拟首个 worker 已启动、后续三个 worker 创建失败，关闭必须释放全部预留槽位。
#[test]
fn unstarted_worker_slots_are_released() {
    let tracker = Arc::new(WorkerTracker::new(WORKER_COUNT));
    let worker_tracker = Arc::clone(&tracker);
    let started = Arc::new(Barrier::new(2));
    let worker_started = Arc::clone(&started);
    tracker
        .register(thread::spawn(move || {
            worker_started.wait();
            worker_tracker.done();
        }))
        .expect("register worker");
    started.wait();
    tracker.complete_slots(WORKER_COUNT - 1);
    assert!(matches!(
        tracker.wait_until(Instant::now() + Duration::from_secs(1)),
        WorkerReap::Complete
    ));
    assert!(tracker.is_reaped());
}

/// 同一 accounting mutex 必须覆盖 predicate 检查与 Condvar 阻塞，避免完成通知永久丢失。
#[test]
fn wait_until_does_not_lose_done_notification() {
    let tracker = Arc::new(WorkerTracker::new(1));
    let entered = Arc::new(Barrier::new(2));
    let release = Arc::new(Barrier::new(2));
    let waiter_tracker = Arc::clone(&tracker);
    let waiter_entered = Arc::clone(&entered);
    let waiter_release = Arc::clone(&release);
    let waiter = thread::spawn(move || {
        let mut gate = Some((waiter_entered, waiter_release));
        waiter_tracker.wait_until_with(Instant::now() + Duration::from_secs(1), move || {
            if let Some((entered, release)) = gate.take() {
                entered.wait();
                release.wait();
            }
        })
    });
    entered.wait();

    let done_tracker = Arc::clone(&tracker);
    let done_started = Arc::new(Barrier::new(2));
    let done_start = Arc::clone(&done_started);
    let done = thread::spawn(move || {
        done_start.wait();
        done_tracker.done();
    });
    done_started.wait();
    release.wait();

    assert!(done.join().is_ok());
    assert!(matches!(waiter.join(), Ok(WorkerReap::Complete)));
}

/// 句柄回收不能因第一个 panic 短路，否则后续 worker 会被分离并破坏 session 可回收不变量。
#[test]
fn join_worker_handles_consumes_all_handles_after_first_panic() {
    let handles = vec![
        thread::spawn(|| panic!("intentional worker panic")),
        thread::spawn(|| {}),
    ];
    let joined = AtomicUsize::new(0);

    let all_joined = join_worker_handles(handles, |handle| {
        joined.fetch_add(1, Ordering::Relaxed);
        handle.join().is_ok()
    });

    assert!(!all_joined);
    assert_eq!(joined.load(Ordering::Acquire), 2);
}

/// 完整 wait 路径必须在发现 worker panic 后清空句柄，禁止把失败线程留给后续 generation。
#[test]
fn wait_until_reaps_handles_after_worker_panic() {
    let tracker = Arc::new(WorkerTracker::new(2));
    let first_tracker = Arc::clone(&tracker);
    tracker
        .register(thread::spawn(move || {
            let _guard = WorkerGuard::new(first_tracker);
            panic!("intentional worker panic");
        }))
        .expect("register first worker");
    let second_tracker = Arc::clone(&tracker);
    tracker
        .register(thread::spawn(move || {
            let _guard = WorkerGuard::new(second_tracker);
        }))
        .expect("register second worker");

    assert!(matches!(
        tracker.wait_until(Instant::now() + Duration::from_secs(1)),
        WorkerReap::JoinFailed
    ));
    assert!(tracker.is_reaped());
}

/// remaining accounting poison 后不能猜测 worker 已结束，关闭必须返回 JoinFailed 且禁止回收 generation。
#[test]
fn poisoned_worker_accounting_fails_join_and_blocks_reclaim() {
    let tracker = WorkerTracker::new(1);
    poison_mutex(&tracker.state);

    assert!(matches!(
        tracker.wait_until(Instant::now() + Duration::from_millis(10)),
        WorkerReap::JoinFailed
    ));
    assert!(!tracker.is_reaped());
    let handles = match tracker.handles.lock() {
        Ok(handles) => handles,
        Err(_) => panic!("failed ledger must be rebuilt for cleanup"),
    };
    assert!(handles.is_empty());
}

/// resize state poison 后必须丢弃 pending 尺寸并让 worker 退出，不得重放旧尺寸。
#[test]
fn poisoned_resize_queue_rebuilds_as_empty_and_closed() {
    let queue = ResizeQueue::new();
    queue
        .set(TerminalSize::default())
        .expect("seed pending resize");
    poison_mutex(&queue.state);

    let error = queue
        .set(TerminalSize::default())
        .expect_err("poisoned resize queue must close");
    assert_eq!(error.code(), TerminalErrorCode::QueueClosed);
    assert_eq!(queue.pop(), None);
    let state = match queue.state.lock() {
        Ok(state) => state,
        Err(_) => panic!("failed resize queue must be rebuilt"),
    };
    assert!(state.closed);
    assert!(state.pending.is_none());
}

/// handle ledger poison 意味无法证明 JoinHandle 归属，新 worker 不得加入失效 tracker。
#[test]
fn poisoned_worker_handle_ledger_rejects_registration() {
    let tracker = WorkerTracker::new(1);
    poison_mutex(&tracker.handles);
    let error = tracker
        .register(thread::spawn(|| {}))
        .expect_err("poisoned handle ledger must reject registration");

    assert_eq!(error.code(), TerminalErrorCode::WorkerShutdownTimeout);
    assert!(matches!(
        tracker.wait_until(Instant::now() + Duration::from_millis(10)),
        WorkerReap::JoinFailed
    ));
    assert!(!tracker.is_reaped());
}
