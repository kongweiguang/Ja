// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::*;
use std::fs;
use std::sync::atomic::AtomicUsize;
use std::sync::mpsc;
use std::thread;

static WATCH_TEST_LOCK: Mutex<()> = Mutex::new(());

/// 直接读取隔离 admission 的单调位，因为测试模块是生产模块的私有子模块；
/// 这样可以验证 Release/Acquire 栅栏，又不需要在生产实现上保留测试专用方法。
fn shutdown_has_started(admission: &WatchAdmission) -> bool {
    admission.shutdown_started.load(Ordering::Acquire)
}

/// 复用生产 stop 事务及其有界 deadline，但把便捷封装留在测试模块，避免测试 API
/// 进入生产编译单元或绕过 lifecycle owner。
fn stop_session(workspace_id: crate::workspace::WorkspaceId) -> Result<bool, WorkspaceError> {
    let deadline = stop_deadline_after(WATCH_STOP_TIMEOUT);
    let _lifecycle = lock_lifecycle_until(deadline)?;
    stop_session_until(workspace_id, deadline)
}

/// 以一次共享 deadline 驱动生产的旧 Workspace 清理算法，保证测试不会按 session
/// 数量放大等待预算，也不要求生产层暴露第二个停止入口。
fn stop_other_sessions(current: crate::workspace::WorkspaceId) -> Result<(), WorkspaceError> {
    stop_other_sessions_until(current, stop_deadline_after(WATCH_STOP_TIMEOUT))
}

/// 安装只在收到生产 stop token 后退出的 worker，并通过相同 handshake 报告清理完成。
fn insert_cooperative_session(workspace_id: crate::workspace::WorkspaceId, generation: u64) {
    let (stop_sender, stop_receiver) = mpsc::sync_channel(1);
    let (exit_sender, exit_receiver) = mpsc::sync_channel(1);
    let join = thread::spawn(move || {
        let _ = stop_receiver.recv();
        let _ = exit_sender.send(WatchWorkerExit::Clean);
    });
    sessions().lock().expect("watcher state").insert(
        workspace_id,
        WatchSession {
            generation,
            stop: stop_sender,
            exit: exit_receiver,
            join: Some(join),
            stop_requested: false,
            reported_exit: None,
        },
    );
}

/// 安装由测试控制 stop 后清理的 worker，无需 sleep 或真实操作系统 Watcher 即可断言 deadline。
fn insert_blocked_session(
    workspace_id: crate::workspace::WorkspaceId,
    generation: u64,
) -> mpsc::SyncSender<()> {
    let (stop_sender, stop_receiver) = mpsc::sync_channel(1);
    let (release_sender, release_receiver) = mpsc::sync_channel(1);
    let (exit_sender, exit_receiver) = mpsc::sync_channel(1);
    let join = thread::spawn(move || {
        let _ = stop_receiver.recv();
        let _ = release_receiver.recv();
        let _ = exit_sender.send(WatchWorkerExit::Clean);
    });
    sessions().lock().expect("watcher state").insert(
        workspace_id,
        WatchSession {
            generation,
            stop: stop_sender,
            exit: exit_receiver,
            join: Some(join),
            stop_requested: false,
            reported_exit: None,
        },
    );
    release_sender
}

/// 相对事件路径必须归一化为 slash，并拒绝 Workspace 外部路径。
#[test]
fn event_path_projection_is_contained() {
    let root = if cfg!(windows) {
        PathBuf::from(r"C:\workspace")
    } else {
        PathBuf::from("/workspace")
    };
    let child = root.join("src").join("main.rs");
    assert_eq!(
        relative_event_path(&root, &child).as_deref(),
        Some("src/main.rs")
    );
    let outside = if cfg!(windows) {
        PathBuf::from(r"C:\other\main.rs")
    } else {
        PathBuf::from("/other/main.rs")
    };
    assert!(relative_event_path(&root, &outside).is_none());
}

/// 合并逻辑限制重复 notify 路径，并把错误升级为 rescan 提示，不能伪造 revision。
#[test]
fn event_collection_coalesces_duplicate_paths_and_errors() {
    let root = if cfg!(windows) {
        PathBuf::from(r"C:\workspace")
    } else {
        PathBuf::from("/workspace")
    };
    let child = root.join("src").join("main.rs");
    let mut pending = std::collections::BTreeSet::new();
    let mut requires_rescan = false;
    collect_event(
        &root,
        Ok(Event::new(notify::event::EventKind::Modify(
            notify::event::ModifyKind::Any,
        ))
        .add_path(child.clone())
        .add_path(child)),
        &mut pending,
        &mut requires_rescan,
    );
    assert_eq!(pending.len(), 1);
    assert!(!requires_rescan);
    collect_event(
        &root,
        Err(notify::Error::generic("overflow")),
        &mut pending,
        &mut requires_rescan,
    );
    assert!(requires_rescan);
}

/// callback 队列饱和后仍保持有界，并记录 overflow 供 worker 发出下一次 rescan marker，
/// 不能阻塞 notify backend。
#[test]
fn native_event_queue_marks_overflow_without_growing() {
    let (sender, receiver) = mpsc::sync_channel(1);
    let overflow = AtomicBool::new(false);
    enqueue_native_event(
        &sender,
        &overflow,
        Ok(Event::new(notify::event::EventKind::Any)),
    );
    enqueue_native_event(
        &sender,
        &overflow,
        Ok(Event::new(notify::event::EventKind::Any)),
    );
    assert!(overflow.load(Ordering::Acquire));
    assert!(receiver.try_recv().is_ok());
    assert!(matches!(receiver.try_recv(), Err(TryRecvError::Empty)));
}

/// 超过细粒度路径预算的 notify 批次必须丢弃完整路径集合并升级为 rescan，
/// 从源头阻止后续逐文件 revision/hash 投影。
#[test]
fn coalesced_path_budget_escalates_to_rescan_without_retaining_paths() {
    let root = if cfg!(windows) {
        PathBuf::from(r"C:\workspace")
    } else {
        PathBuf::from("/workspace")
    };
    let mut pending = std::collections::BTreeSet::new();
    let mut requires_rescan = false;
    for index in 0..=MAX_COALESCED_PATHS {
        collect_event(
            &root,
            Ok(Event::new(notify::event::EventKind::Any)
                .add_path(root.join(format!("file-{index}.txt")))),
            &mut pending,
            &mut requires_rescan,
        );
    }
    assert!(requires_rescan);
    assert!(pending.is_empty());
}

/// 构造远大于细粒度预算的真实路径批次，证明 overflow 只产生一个根级
/// marker；普通 flush 也会在首个投影后消费 stop，不继续遍历剩余路径。
#[test]
fn overflow_batch_collapses_projection_and_flush_honors_stop() {
    let mut overflowed = (0..MAX_PENDING_NATIVE_EVENTS)
        .map(|index| format!("src/file-{index}.rs"))
        .collect::<std::collections::BTreeSet<_>>();
    let (_keep_stop_sender, stop_receiver) = mpsc::sync_channel(1);
    let mut projected = Vec::new();

    assert!(flush_events_with(
        &mut overflowed,
        true,
        &stop_receiver,
        |path, requires_rescan| projected.push((path, requires_rescan)),
    ));
    assert!(overflowed.is_empty());
    assert_eq!(projected, vec![(String::new(), true)]);

    let mut ordinary = (0..MAX_COALESCED_PATHS)
        .map(|index| format!("src/ordinary-{index}.rs"))
        .collect::<std::collections::BTreeSet<_>>();
    let (stop_sender, stop_receiver) = mpsc::sync_channel(1);
    let mut emitted = 0usize;
    assert!(!flush_events_with(
        &mut ordinary,
        false,
        &stop_receiver,
        |_, _| {
            emitted = emitted.saturating_add(1);
            if emitted == 1 {
                stop_sender.try_send(()).expect("queue stop during flush");
            }
        },
    ));
    assert_eq!(emitted, 1);
}

/// 重复 shutdown 只继续对账资源，不会重开 admission；独立状态避免测试把
/// 不可逆的生产全局位永久置位并污染同进程中的其它 watcher 测试。
#[test]
fn shutdown_all_is_idempotent_when_no_sessions_exist() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let admission = WatchAdmission::accepting();
    assert!(
        shutdown_all_with_admission_until(&admission, Instant::now() + Duration::from_secs(1))
            .is_ok()
    );
    assert!(
        shutdown_all_with_admission_until(&admission, Instant::now() + Duration::from_secs(1))
            .is_ok()
    );
    assert!(shutdown_has_started(&admission));
    assert!(process_admission().ensure_start_allowed().is_ok());
    assert!(
        polling_detectors()
            .lock()
            .expect("detector state")
            .is_empty()
    );
}

/// shutdown 线性化后，晚到 start 必须在资源闭包之前得到固定脱敏错误；用
/// 计数器证明 factory 没有执行，也不需要为测试重置生产全局栅栏。
#[test]
fn shutdown_fence_rejects_late_start_before_resource_creation() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let admission = WatchAdmission::accepting();
    shutdown_all_with_admission_until(&admission, stop_deadline_after(Duration::from_secs(1)))
        .expect("shutdown empty watcher state");

    let factory_calls = AtomicUsize::new(0);
    let result = with_start_admission_until(
        &admission,
        stop_deadline_after(Duration::from_secs(1)),
        || {
            factory_calls.fetch_add(1, Ordering::SeqCst);
            Ok(())
        },
    );

    assert!(matches!(result, Err(WorkspaceError::WatchUnavailable)));
    assert_eq!(factory_calls.load(Ordering::SeqCst), 0);
    assert_eq!(
        WorkspaceError::WatchUnavailable.to_string(),
        "workspace watcher is unavailable"
    );
    assert!(is_shutdown_complete());
}

/// 已通过 admission 的 start 与 shutdown 并发时，shutdown 先关闭晚请求再
/// 等待 lifecycle owner，并在该启动提交 session 后完整 join；晚请求的
/// factory 不执行，因此没有第二条 worker 或 ownership 记录。
#[test]
fn concurrent_start_and_shutdown_drain_accepted_session_and_reject_late_start() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let admission = Arc::new(WatchAdmission::accepting());
    let accepted_workspace = crate::workspace::WorkspaceId::new();
    let late_workspace = crate::workspace::WorkspaceId::new();
    let (entered_sender, entered_receiver) = mpsc::sync_channel(1);
    let (release_sender, release_receiver) = mpsc::sync_channel(1);

    let start_admission = Arc::clone(&admission);
    let accepted_start = thread::spawn(move || {
        with_start_admission_until(
            start_admission.as_ref(),
            stop_deadline_after(Duration::from_secs(5)),
            || {
                insert_cooperative_session(accepted_workspace, 1);
                entered_sender.send(()).expect("signal accepted start");
                release_receiver.recv().expect("release accepted start");
                Ok(())
            },
        )
    });
    entered_receiver.recv().expect("accepted start entered");

    let shutdown_admission = Arc::clone(&admission);
    let shutdown = thread::spawn(move || {
        shutdown_all_with_admission_until(
            shutdown_admission.as_ref(),
            stop_deadline_after(Duration::from_secs(5)),
        )
    });
    let fence_deadline = stop_deadline_after(Duration::from_secs(1));
    while !shutdown_has_started(&admission) {
        assert!(
            Instant::now() < fence_deadline,
            "shutdown fence was not published"
        );
        thread::yield_now();
    }

    let late_factory_calls = AtomicUsize::new(0);
    let late_start = with_start_admission_until(
        admission.as_ref(),
        stop_deadline_after(Duration::from_secs(1)),
        || {
            late_factory_calls.fetch_add(1, Ordering::SeqCst);
            insert_cooperative_session(late_workspace, 2);
            Ok(())
        },
    );
    assert!(matches!(late_start, Err(WorkspaceError::WatchUnavailable)));
    assert_eq!(late_factory_calls.load(Ordering::SeqCst), 0);
    assert!(
        !sessions()
            .lock()
            .expect("watcher state")
            .contains_key(&late_workspace)
    );

    release_sender.send(()).expect("release accepted start");
    accepted_start
        .join()
        .expect("accepted start thread")
        .expect("accepted start result");
    shutdown
        .join()
        .expect("shutdown thread")
        .expect("shutdown result");
    assert!(is_shutdown_complete());
}

/// 只有两个 ownership 表都为空，composition root 才可报告就绪；仅残留 detector 也属于退出失败。
#[test]
fn shutdown_readiness_requires_watchers_and_detectors_to_be_empty() {
    assert!(ownership_tables_are_empty(0, 0));
    assert!(!ownership_tables_are_empty(1, 0));
    assert!(!ownership_tables_are_empty(0, 1));
    assert!(!ownership_tables_are_empty(1, 1));
}

/// 即使没有 worker，已过期 deadline 也必须返回稳定 timeout error，使有界合同可观察。
#[test]
fn shutdown_all_reports_an_expired_deadline() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let admission = WatchAdmission::accepting();
    let deadline = Instant::now()
        .checked_sub(Duration::from_millis(1))
        .expect("instant subtraction");
    assert!(matches!(
        shutdown_all_with_admission_until(&admission, deadline),
        Err(WorkspaceError::WatchShutdownTimeout)
    ));
    assert!(shutdown_has_started(&admission));
}

/// lifecycle owner 竞争仍受 deadline 约束，但 admission 在等待前已关闭；因此
/// shutdown 即使超时，晚 start 也快速失败而不会排队到锁释放后创建资源。
#[test]
fn shutdown_deadline_bounds_lifecycle_lock_contention() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let admission = WatchAdmission::accepting();
    let (locked_sender, locked_receiver) = mpsc::sync_channel(1);
    let (release_sender, release_receiver) = mpsc::sync_channel(1);
    let holder = thread::spawn(move || {
        let _guard = lifecycle().lock().expect("lifecycle state");
        locked_sender.send(()).expect("signal lifecycle lock");
        let _ = release_receiver.recv();
    });
    locked_receiver.recv().expect("lifecycle acquired");

    let started = Instant::now();
    assert!(matches!(
        shutdown_all_with_admission_until(
            &admission,
            stop_deadline_after(Duration::from_millis(25))
        ),
        Err(WorkspaceError::WatchShutdownTimeout)
    ));
    assert!(started.elapsed() < Duration::from_secs(1));
    let late_factory_calls = AtomicUsize::new(0);
    assert!(matches!(
        with_start_admission_until(
            &admission,
            stop_deadline_after(Duration::from_secs(1)),
            || {
                late_factory_calls.fetch_add(1, Ordering::SeqCst);
                Ok(())
            },
        ),
        Err(WorkspaceError::WatchUnavailable)
    ));
    assert_eq!(late_factory_calls.load(Ordering::SeqCst), 0);

    release_sender.send(()).expect("release lifecycle");
    holder.join().expect("lifecycle holder");
    assert!(
        shutdown_all_with_admission_until(&admission, stop_deadline_after(Duration::from_secs(1)))
            .is_ok()
    );
}

/// composition-root shutdown 会在 join 每个已注册 worker 前发送 stop，确保 session 表不保留已完成 listener。
#[test]
fn shutdown_all_joins_registered_session() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let admission = WatchAdmission::accepting();
    let workspace_id = crate::workspace::WorkspaceId::new();
    insert_cooperative_session(workspace_id, 1);

    assert!(
        shutdown_all_with_admission_until(&admission, Instant::now() + Duration::from_secs(5))
            .is_ok()
    );
    assert!(
        !sessions()
            .lock()
            .expect("watcher state")
            .contains_key(&workspace_id)
    );
}

/// Workspace switch 会停止所有旧 owner，但当前请求的 Workspace 会保持活动，直到收到自身显式 stop。
#[test]
fn workspace_switch_stops_only_previous_sessions() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let previous = crate::workspace::WorkspaceId::new();
    let current = crate::workspace::WorkspaceId::new();
    insert_cooperative_session(previous, 1);
    insert_cooperative_session(current, 2);

    stop_other_sessions(current).expect("stop previous workspace");

    let state = sessions().lock().expect("watcher state");
    assert!(!state.contains_key(&previous));
    assert!(state.contains_key(&current));
    drop(state);
    assert!(stop_session(current).expect("stop current workspace"));
    assert!(is_shutdown_complete());
}

/// 旧 React effect 的 cleanup 受 generation 约束，只释放自身订阅，不能终止替换后的 Watcher。
#[test]
fn stale_generation_stop_preserves_replacement_session() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let workspace_id = crate::workspace::WorkspaceId::new();
    insert_cooperative_session(workspace_id, 2);

    assert!(!stop_session_generation(workspace_id, 1).expect("stale stop"));
    assert_eq!(
        sessions()
            .lock()
            .expect("watcher state")
            .get(&workspace_id)
            .map(|session| session.generation),
        Some(2)
    );
    assert!(stop_session_generation(workspace_id, 2).expect("current stop"));
    assert!(is_shutdown_complete());
}

/// 超过 deadline 的 worker 仍由 session 完整持有并与 detector 配对；清理完成后第二次退出对账
/// 会 join 它并清空两个表，不泄漏隐藏线程。
#[test]
fn shutdown_timeout_retains_ownership_until_reconciled() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let admission = WatchAdmission::accepting();
    let root = std::env::temp_dir().join(format!(
        "ja-watch-timeout-{}-{}",
        std::process::id(),
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&root).expect("watch fixture");
    let registry = crate::workspace::WorkspaceRegistry::default();
    let info = registry.register(&root).expect("workspace");
    let workspace = registry.get(info.id).expect("workspace handle");
    let release = insert_blocked_session(info.id, 1);
    polling_detectors().lock().expect("detector state").insert(
        info.id,
        PollingChangeDetector::new(workspace, PollingPolicy::default()).expect("detector"),
    );

    let started = Instant::now();
    assert!(matches!(
        shutdown_all_with_admission_until(
            &admission,
            stop_deadline_after(Duration::from_millis(25))
        ),
        Err(WorkspaceError::WatchShutdownTimeout)
    ));
    assert!(started.elapsed() < Duration::from_secs(1));
    assert!(
        sessions()
            .lock()
            .expect("watcher state")
            .contains_key(&info.id)
    );
    assert!(
        polling_detectors()
            .lock()
            .expect("detector state")
            .contains_key(&info.id)
    );
    assert!(!is_shutdown_complete());

    release.send(()).expect("release cleanup");
    assert!(
        shutdown_all_with_admission_until(&admission, stop_deadline_after(Duration::from_secs(1)))
            .is_ok()
    );
    assert!(is_shutdown_complete());
    let _ = fs::remove_dir_all(root);
}

/// 捕获到 panic 的 worker 会被 join 并移除，但仍返回显式失败；destroy 不能跨错误状态误报正常停止。
#[test]
fn worker_panic_is_reaped_without_false_success() {
    let _serial = WATCH_TEST_LOCK.lock().expect("watch test lock");
    let workspace_id = crate::workspace::WorkspaceId::new();
    let (stop_sender, stop_receiver) = mpsc::sync_channel(1);
    let (exit_sender, exit_receiver) = mpsc::sync_channel(1);
    let join = thread::spawn(move || {
        let _ = stop_receiver.recv();
        let _ = exit_sender.send(WatchWorkerExit::Panicked);
    });
    sessions().lock().expect("watcher state").insert(
        workspace_id,
        WatchSession {
            generation: 1,
            stop: stop_sender,
            exit: exit_receiver,
            join: Some(join),
            stop_requested: false,
            reported_exit: None,
        },
    );

    assert!(matches!(
        stop_session_until(workspace_id, stop_deadline_after(Duration::from_secs(1))),
        Err(WorkspaceError::Io {
            operation: "watch_join",
            ..
        })
    ));
    assert!(is_shutdown_complete());
}
