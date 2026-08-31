// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Preview 加载超时任务的所有权与代际单元测试。

use super::*;
use crate::preview::{PreviewEventKind, PreviewManager};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, mpsc};
use tokio::sync::oneshot;

/// 记录确定性测试任务是否已被 watchdog 生命周期取消。
struct ManualLoadTimeoutTask {
    cancelled: Arc<AtomicBool>,
}

impl LoadTimeoutTask for ManualLoadTimeoutTask {
    /// 测试句柄只翻转共享标记，使稍后执行的 callback 能观察真实取消顺序。
    fn abort(&self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

/// 保存一个尚未由测试显式推进的 timeout，避免 wall-clock 与 Tokio runtime
/// 影响 session/generation 竞争断言。
struct ManualScheduledTimeout {
    cancelled: Arc<AtomicBool>,
    callback: Box<dyn FnOnce() + Send + 'static>,
}

/// 确定性 runtime 只在测试调用 `run_all` 时派发 callback；它不依赖 Tokio context，
/// 因此也能模拟 WebView2 原生事件线程的调用条件。
#[derive(Clone, Default)]
struct ManualLoadTimeoutRuntime {
    pending: Arc<Mutex<Vec<ManualScheduledTimeout>>>,
}

impl ManualLoadTimeoutRuntime {
    /// 一次领取当前批次，并跳过已取消任务；callback 新增的任务留给下一轮，
    /// 防止测试调度器隐式制造递归执行语义。
    fn run_all(&self) {
        let scheduled = {
            let mut pending = self.pending.lock().expect("manual runtime state");
            std::mem::take(&mut *pending)
        };
        for timeout in scheduled {
            if !timeout.cancelled.load(Ordering::Acquire) {
                (timeout.callback)();
            }
        }
    }
}

impl LoadTimeoutRuntime for ManualLoadTimeoutRuntime {
    /// 忽略真实 delay 并保存 callback；测试显式推进仍保留 abort-before-run 的所有权语义。
    fn schedule(
        &self,
        _delay: Duration,
        callback: Box<dyn FnOnce() + Send + 'static>,
    ) -> Box<dyn LoadTimeoutTask> {
        let cancelled = Arc::new(AtomicBool::new(false));
        self.pending
            .lock()
            .expect("manual runtime state")
            .push(ManualScheduledTimeout {
                cancelled: cancelled.clone(),
                callback,
            });
        Box::new(ManualLoadTimeoutTask { cancelled })
    }
}

/// 为每个用例创建独立 runtime/watchdog，避免已进入 shutdown 的状态泄漏到其他断言。
fn manual_watchdog() -> (ManualLoadTimeoutRuntime, PreviewLoadWatchdog) {
    let runtime = ManualLoadTimeoutRuntime::default();
    let watchdog = PreviewLoadWatchdog::new(Arc::new(runtime.clone()));
    (runtime, watchdog)
}

/// 原生 WebView callback 不运行在 Tokio worker 上；watchdog 必须仍能通过注入的宿主
/// 调度器启动，否则导航开始阶段会把不可 unwind 的 COM callback 直接打崩。
#[test]
fn watchdog_can_be_armed_from_native_callback_thread() {
    let (runtime, watchdog) = manual_watchdog();
    let (sender, receiver) = mpsc::channel();
    std::thread::spawn(move || {
        watchdog
            .arm(
                PreviewId::new(),
                1,
                Duration::from_millis(1),
                move || {
                    let _ = sender.send(());
                },
            )
            .expect("arm timeout outside Tokio context");
    })
    .join()
    .expect("native callback thread");
    runtime.run_all();
    receiver
        .recv_timeout(Duration::from_secs(2))
        .expect("injected runtime executes timeout callback");
}

/// 确认超时只记录一次既有的有界 `load_failed` 事件，并立即释放对应任务。
#[tokio::test]
async fn watchdog_timeout_records_existing_load_failed_event_once() {
    let (runtime, watchdog) = manual_watchdog();
    let manager = PreviewManager::default_manager().expect("manager");
    let opened = manager.open("https://example.test/").expect("open");
    let callback_manager = manager.clone();
    let id = opened.snapshot.id;
    let generation = opened.snapshot.generation;
    let (sender, receiver) = oneshot::channel();
    watchdog
        .arm(id, generation, Duration::from_millis(1), move || {
            let recorded = callback_manager
                .callback_load_error(id, generation, "preview load timed out")
                .is_ok();
            let _ = sender.send(recorded);
        })
        .expect("arm timeout");
    runtime.run_all();
    assert!(
        tokio::time::timeout(Duration::from_secs(2), receiver)
            .await
            .expect("timeout callback deadline")
            .expect("timeout callback")
    );
    assert!(
        !watchdog
            .complete(id, generation)
            .expect("expired timeout must already be removed")
    );
    assert!(
        manager
            .drain_events(id, 8)
            .expect("events")
            .iter()
            .any(|event| matches!(event.kind, PreviewEventKind::LoadFailed { .. }))
    );
}

/// 确认陈旧完成回调不能取消新代际，只有当前代际能够释放唯一保留的任务。
#[tokio::test]
async fn watchdog_completion_is_generation_scoped_and_bounded() {
    let (_runtime, watchdog) = manual_watchdog();
    let id = PreviewId::new();
    watchdog
        .arm(id, 7, Duration::from_secs(60), || {})
        .expect("first arm");
    watchdog
        .arm(id, 8, Duration::from_secs(60), || {})
        .expect("replacement arm");
    assert!(!watchdog.complete(id, 7).expect("stale completion"));
    assert!(watchdog.complete(id, 8).expect("current completion"));
    assert!(!watchdog.complete(id, 8).expect("released completion"));
}

/// 确认关停会释放全部会话任务，并永久拒绝晚到的超时注册。
#[tokio::test]
async fn watchdog_cancel_all_releases_all_pending_tasks() {
    let (_runtime, watchdog) = manual_watchdog();
    let first = PreviewId::new();
    let second = PreviewId::new();
    watchdog
        .arm(first, 1, Duration::from_secs(60), || {})
        .expect("first arm");
    watchdog
        .arm(second, 1, Duration::from_secs(60), || {})
        .expect("second arm");
    watchdog.cancel_all().expect("cancel all");
    assert!(!watchdog.complete(first, 1).expect("first task released"));
    assert!(!watchdog.complete(second, 1).expect("second task released"));
    assert_eq!(
        watchdog
            .arm(PreviewId::new(), 1, Duration::from_secs(60), || {})
            .expect_err("late arm must fail")
            .code(),
        PreviewErrorCode::ShutdownStarted
    );
}
