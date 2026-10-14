// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

#![allow(dead_code)]

#[path = "../../src/controller/clipboard.rs"]
mod clipboard;

use clipboard::{ClipboardBackend, ClipboardEvent, ClipboardWorker, CopyState};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

#[derive(Default)]
struct FakeSnapshot {
    factory_started: usize,
    factory_panics: usize,
    block_factory: bool,
    allow_factory: bool,
    prepare_started: usize,
    prepare_finished: usize,
    prepare_panics: usize,
    delivery_started: usize,
    delivery_panics: usize,
    writes: Vec<String>,
    block_prepare: bool,
    allow_prepare: bool,
    block_delivery: bool,
    allow_delivery: bool,
}

#[derive(Default)]
struct FakeBackendState {
    snapshot: Mutex<FakeSnapshot>,
    changed: Condvar,
}

struct FakeBackend {
    shared: Arc<FakeBackendState>,
}

impl ClipboardBackend for FakeBackend {
    /// @author kongweiguang
    /// Hold preparation on a test gate to verify cancellation and deadlines before OS delivery.
    fn prepare(&mut self) -> Result<(), String> {
        let should_panic = {
            let mut state = lock(&self.shared.snapshot);
            state.prepare_started += 1;
            self.shared.changed.notify_all();
            while state.block_prepare && !state.allow_prepare {
                state = self
                    .shared
                    .changed
                    .wait(state)
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
            }
            state.prepare_finished += 1;
            let should_panic = state.prepare_panics > 0;
            state.prepare_panics = state
                .prepare_panics
                .saturating_sub(usize::from(should_panic));
            self.shared.changed.notify_all();
            should_panic
        };
        if should_panic {
            panic!("injected clipboard prepare panic");
        }
        Ok(())
    }

    /// @author kongweiguang
    /// Hold native delivery when requested and record text only after the test releases the OS call.
    fn set_text(&mut self, text: &str) -> Result<(), String> {
        let should_panic = {
            let mut state = lock(&self.shared.snapshot);
            state.delivery_started += 1;
            self.shared.changed.notify_all();
            while state.block_delivery && !state.allow_delivery {
                state = self
                    .shared
                    .changed
                    .wait(state)
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
            }
            // Record the external side effect before panicking to model a native API whose result is unknown.
            state.writes.push(text.to_owned());
            let should_panic = state.delivery_panics > 0;
            state.delivery_panics = state
                .delivery_panics
                .saturating_sub(usize::from(should_panic));
            self.shared.changed.notify_all();
            should_panic
        };
        if should_panic {
            panic!("injected clipboard write panic after side effect");
        }
        Ok(())
    }
}

/// @author kongweiguang
/// Share fake observations across a fresh backend created inside the worker thread.
fn make_worker(shared: Arc<FakeBackendState>, deadline: Duration) -> ClipboardWorker {
    ClipboardWorker::with_backend_factory_and_deadline(
        move || {
            let should_panic = {
                let mut state = lock(&shared.snapshot);
                state.factory_started += 1;
                shared.changed.notify_all();
                while state.block_factory && !state.allow_factory {
                    state = shared
                        .changed
                        .wait(state)
                        .unwrap_or_else(std::sync::PoisonError::into_inner);
                }
                let should_panic = state.factory_panics > 0;
                state.factory_panics = state
                    .factory_panics
                    .saturating_sub(usize::from(should_panic));
                shared.changed.notify_all();
                should_panic
            };
            if should_panic {
                panic!("injected clipboard factory panic");
            }
            Box::new(FakeBackend {
                shared: Arc::clone(&shared),
            })
        },
        deadline,
    )
}

/// @author kongweiguang
/// Verify definite setup panics cancel queued work and a later explicit request gets a fresh backend.
#[test]
fn factory_panic_fails_before_write_cancels_pending_and_allows_explicit_retry() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_factory = true;
        state.factory_panics = 1;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_secs(1));
    let failed = worker
        .submit("thr_factory_panic".into(), Some(1), "must not write".into())
        .unwrap();
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.factory_started == 1
    });
    let cancelled = worker
        .submit(
            "thr_factory_panic".into(),
            Some(2),
            "queued side effect".into(),
        )
        .unwrap();
    {
        let mut state = lock(&shared.snapshot);
        state.allow_factory = true;
        shared.changed.notify_all();
    }

    // Panic cleanup reports the pending cancellation before the active request's failure.
    assert_eq!(
        wait_event(&worker, cancelled, Duration::from_secs(1), |state| {
            *state == CopyState::Cancelled
        })
        .state,
        CopyState::Cancelled
    );
    let failure = wait_event(&worker, failed, Duration::from_secs(1), |state| {
        matches!(state, CopyState::Failed(_))
    });
    assert!(matches!(failure.state, CopyState::Failed(_)));
    assert!(lock(&shared.snapshot).writes.is_empty());

    let retry = worker
        .submit("thr_factory_panic".into(), Some(3), "explicit retry".into())
        .unwrap();
    assert_eq!(
        wait_event(&worker, retry, Duration::from_secs(1), |state| {
            *state == CopyState::Copied
        })
        .state,
        CopyState::Copied
    );
    let state = lock(&shared.snapshot);
    assert_eq!(state.factory_started, 2);
    assert_eq!(state.writes, ["explicit retry"]);
}

/// @author kongweiguang
/// Verify prepare panics are definite pre-write failures, discard the backend, and cancel queued copies.
#[test]
fn prepare_panic_fails_before_write_cancels_pending_and_allows_explicit_retry() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_prepare = true;
        state.prepare_panics = 1;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_secs(1));
    let failed = worker
        .submit("thr_prepare_panic".into(), Some(1), "must not write".into())
        .unwrap();
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.prepare_started == 1
    });
    let cancelled = worker
        .submit(
            "thr_prepare_panic".into(),
            Some(2),
            "queued side effect".into(),
        )
        .unwrap();
    {
        let mut state = lock(&shared.snapshot);
        state.allow_prepare = true;
        shared.changed.notify_all();
    }

    // Panic cleanup reports the pending cancellation before the active request's failure.
    assert_eq!(
        wait_event(&worker, cancelled, Duration::from_secs(1), |state| {
            *state == CopyState::Cancelled
        })
        .state,
        CopyState::Cancelled
    );
    assert!(matches!(
        wait_event(&worker, failed, Duration::from_secs(1), |state| matches!(
            state,
            CopyState::Failed(_)
        ))
        .state,
        CopyState::Failed(_)
    ));
    assert!(lock(&shared.snapshot).writes.is_empty());

    let retry = worker
        .submit("thr_prepare_panic".into(), Some(3), "explicit retry".into())
        .unwrap();
    assert_eq!(
        wait_event(&worker, retry, Duration::from_secs(1), |state| {
            *state == CopyState::Copied
        })
        .state,
        CopyState::Copied
    );
    let state = lock(&shared.snapshot);
    assert_eq!(state.factory_started, 2);
    assert_eq!(state.writes, ["explicit retry"]);
}

/// @author kongweiguang
/// Preserve an unknown native write result, cancel queued side effects, and require a fresh explicit retry.
#[test]
fn write_panic_reports_unknown_cancels_pending_and_never_replays() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_delivery = true;
        state.delivery_panics = 1;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_secs(1));
    let unknown = worker
        .submit("thr_write_panic".into(), Some(1), "possibly written".into())
        .unwrap();
    assert_eq!(
        wait_event(&worker, unknown, Duration::from_secs(1), |state| {
            *state == CopyState::Delivering
        })
        .state,
        CopyState::Delivering
    );
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.delivery_started == 1
    });
    let cancelled = worker
        .submit("thr_write_panic".into(), Some(2), "must not replay".into())
        .unwrap();
    {
        let mut state = lock(&shared.snapshot);
        state.allow_delivery = true;
        shared.changed.notify_all();
    }

    // A queued cancellation is drained first; the active unknown result must remain observable next.
    assert_eq!(
        wait_event(&worker, cancelled, Duration::from_secs(1), |state| {
            *state == CopyState::Cancelled
        })
        .state,
        CopyState::Cancelled
    );
    assert_eq!(
        wait_event(&worker, unknown, Duration::from_secs(1), |state| {
            *state == CopyState::OutcomeUnknown
        })
        .state,
        CopyState::OutcomeUnknown
    );
    assert_eq!(lock(&shared.snapshot).writes, ["possibly written"]);

    let retry = worker
        .submit("thr_write_panic".into(), Some(3), "explicit retry".into())
        .unwrap();
    assert_eq!(
        wait_event(&worker, retry, Duration::from_secs(1), |state| {
            *state == CopyState::Copied
        })
        .state,
        CopyState::Copied
    );
    let state = lock(&shared.snapshot);
    assert_eq!(state.factory_started, 2);
    assert_eq!(state.writes, ["possibly written", "explicit retry"]);
}

/// @author kongweiguang
/// Wait for backend checkpoints without relying on scheduler timing.
fn wait_snapshot(
    shared: &FakeBackendState,
    timeout: Duration,
    predicate: impl Fn(&FakeSnapshot) -> bool,
) {
    let deadline = Instant::now() + timeout;
    let mut state = lock(&shared.snapshot);
    while !predicate(&state) {
        let remaining = deadline.saturating_duration_since(Instant::now());
        assert!(
            !remaining.is_zero(),
            "timed out waiting for fake clipboard state"
        );
        let (next, result) = shared
            .changed
            .wait_timeout(state, remaining)
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        state = next;
        assert!(
            !result.timed_out() || predicate(&state),
            "timed out waiting for fake clipboard state"
        );
    }
}

/// @author kongweiguang
/// Poll worker events until one request reaches the requested observable state.
fn wait_event(
    worker: &ClipboardWorker,
    request_id: u64,
    timeout: Duration,
    predicate: impl Fn(&CopyState) -> bool,
) -> ClipboardEvent {
    let deadline = Instant::now() + timeout;
    loop {
        while let Some(event) = worker.try_recv() {
            if event.request_id == request_id && predicate(&event.state) {
                return event;
            }
        }
        assert!(
            Instant::now() < deadline,
            "timed out waiting for clipboard request {request_id}"
        );
        thread::sleep(Duration::from_millis(1));
    }
}

/// @author kongweiguang
/// Recover poisoned test locks so a failed assertion does not mask the behavior under test.
fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// @author kongweiguang
/// UI polling reports timeout while native preparation remains blocked and the gate prevents a late write.
#[test]
fn setup_deadline_prevents_late_delivery() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_prepare = true;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_millis(40));
    let request_id = worker
        .submit("thr_timeout".into(), Some(17), "text".into())
        .unwrap();
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.prepare_started == 1
    });
    thread::sleep(Duration::from_millis(60));
    let event = wait_event(&worker, request_id, Duration::from_secs(1), |state| {
        *state == CopyState::TimedOut
    });
    assert_eq!(event.thread_id, "thr_timeout");
    assert_eq!(event.selection_id, Some(17));
    assert_eq!(lock(&shared.snapshot).prepare_finished, 0);
    assert!(lock(&shared.snapshot).writes.is_empty());
    {
        let mut state = lock(&shared.snapshot);
        state.allow_prepare = true;
        shared.changed.notify_all();
    }

    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.prepare_finished == 1
    });
    assert!(lock(&shared.snapshot).writes.is_empty());
}

/// @author kongweiguang
/// A queued request must time out during an older blocked setup instead of waiting behind it.
#[test]
fn pending_request_expires_while_worker_is_blocked() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_prepare = true;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_millis(50));
    let running = worker
        .submit("thr_queue_timeout".into(), Some(1), "running".into())
        .unwrap();
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.prepare_started == 1
    });
    let pending = worker
        .submit("thr_queue_timeout".into(), Some(2), "pending".into())
        .unwrap();

    let deadline = Instant::now() + Duration::from_secs(1);
    let mut timed_out = Vec::new();
    while timed_out.len() < 2 {
        if let Some(event) = worker.try_recv()
            && event.state == CopyState::TimedOut
        {
            timed_out.push((event.request_id, event.selection_id));
            continue;
        }
        assert!(
            Instant::now() < deadline,
            "both running and pending requests should time out while prepare stays blocked"
        );
        thread::sleep(Duration::from_millis(1));
    }
    assert!(timed_out.contains(&(running, Some(1))));
    assert!(timed_out.contains(&(pending, Some(2))));
    assert_eq!(lock(&shared.snapshot).prepare_finished, 0);
    {
        let mut state = lock(&shared.snapshot);
        state.allow_prepare = true;
        shared.changed.notify_all();
    }
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.prepare_finished == 1
    });
    assert!(lock(&shared.snapshot).writes.is_empty());
}

/// Cancellation while native setup is pending wins the shared phase gate and suppresses delivery.
/// @author kongweiguang
#[test]
fn cancel_before_delivery_never_writes() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_prepare = true;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_secs(1));
    let request_id = worker
        .submit("thr_cancel".into(), Some(3), "do not write".into())
        .unwrap();
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.prepare_started == 1
    });

    assert!(worker.cancel(request_id));
    let event = wait_event(&worker, request_id, Duration::from_secs(1), |state| {
        *state == CopyState::Cancelled
    });
    assert_eq!(event.selection_id, Some(3));
    {
        let mut state = lock(&shared.snapshot);
        state.allow_prepare = true;
        shared.changed.notify_all();
    }
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.prepare_finished == 1
    });
    assert!(lock(&shared.snapshot).writes.is_empty());
}

/// Once delivery begins, cancellation is refused and success waits for the backend acknowledgement.
/// @author kongweiguang
#[test]
fn inflight_delivery_cannot_be_cancelled_or_reported_early() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_delivery = true;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_millis(40));
    let request_id = worker
        .submit("thr_delivery".into(), None, "payload".into())
        .unwrap();
    wait_event(&worker, request_id, Duration::from_secs(1), |state| {
        *state == CopyState::Delivering
    });
    wait_snapshot(&shared, Duration::from_secs(1), |state| {
        state.delivery_started == 1
    });

    thread::sleep(Duration::from_millis(60));
    assert!(!worker.cancel(request_id));
    assert!(worker.try_recv().is_none());
    {
        let mut state = lock(&shared.snapshot);
        state.allow_delivery = true;
        shared.changed.notify_all();
    }
    let event = wait_event(&worker, request_id, Duration::from_secs(1), |state| {
        *state == CopyState::Copied
    });
    assert_eq!(event.state, CopyState::Copied);
    assert_eq!(lock(&shared.snapshot).writes, ["payload"]);
}

/// Replacing the pending slot keeps one running and one latest request without replaying stale text.
/// @author kongweiguang
#[test]
fn pending_queue_keeps_only_latest_copy() {
    let shared = Arc::new(FakeBackendState::default());
    {
        let mut state = lock(&shared.snapshot);
        state.block_delivery = true;
    }
    let mut worker = make_worker(Arc::clone(&shared), Duration::from_secs(1));
    let first = worker
        .submit("thr_queue".into(), Some(1), "first".into())
        .unwrap();
    wait_event(&worker, first, Duration::from_secs(1), |state| {
        *state == CopyState::Delivering
    });
    let stale = worker
        .submit("thr_queue".into(), Some(2), "stale".into())
        .unwrap();
    let latest = worker
        .submit("thr_queue".into(), Some(3), "latest".into())
        .unwrap();
    wait_event(&worker, stale, Duration::from_secs(1), |state| {
        *state == CopyState::Superseded
    });
    assert!(!worker.cancel(first));

    {
        let mut state = lock(&shared.snapshot);
        state.allow_delivery = true;
        shared.changed.notify_all();
    }
    wait_event(&worker, first, Duration::from_secs(1), |state| {
        *state == CopyState::Copied
    });
    wait_event(&worker, latest, Duration::from_secs(1), |state| {
        *state == CopyState::Copied
    });
    assert_eq!(lock(&shared.snapshot).writes, ["first", "latest"]);
}

struct RestoreClipboard {
    clipboard: arboard::Clipboard,
    original: String,
    restored: bool,
}

impl RestoreClipboard {
    /// @author kongweiguang
    /// Restore the saved text before assertions return control or unwind after a test panic.
    fn restore(&mut self) -> Result<(), String> {
        self.clipboard
            .set_text(self.original.clone())
            .map_err(|error| format!("恢复原剪贴板失败：{error}"))?;
        self.restored = true;
        Ok(())
    }
}

impl Drop for RestoreClipboard {
    /// @author kongweiguang
    /// Make a best-effort restoration on panic after the test has changed the user's clipboard.
    fn drop(&mut self) {
        if !self.restored {
            let _ = self.clipboard.set_text(self.original.clone());
        }
    }
}

/// Exercise native Unicode text delivery only on explicit request and refuse to run without restorable text.
/// @author kongweiguang
#[test]
#[ignore = "requires a desktop clipboard; run explicitly to preserve and restore its existing text"]
fn native_clipboard_round_trip_preserves_existing_text() {
    let mut clipboard =
        arboard::Clipboard::new().expect("native clipboard must be available for this opt-in test");
    let original = clipboard
        .get_text()
        .expect("refusing to modify clipboard when its existing text cannot be saved");
    let mut restore = RestoreClipboard {
        clipboard,
        original,
        restored: false,
    };
    let payload = "Ja 原生剪贴板 Unicode：中文、é、🙂";
    let mut worker = ClipboardWorker::new();
    let request_id = worker
        .submit("thr_native_test".into(), None, payload.into())
        .unwrap();
    let event = wait_event(&worker, request_id, Duration::from_secs(10), |state| {
        matches!(state, CopyState::Copied | CopyState::Failed(_))
    });
    assert_eq!(event.state, CopyState::Copied);
    assert_eq!(
        restore.clipboard.get_text().unwrap(),
        payload,
        "native clipboard must return the exact Unicode payload"
    );
    drop(worker);
    restore.restore().unwrap();
    assert_eq!(
        restore.clipboard.get_text().unwrap(),
        restore.original,
        "the opt-in native test must restore the previous clipboard text"
    );
}
