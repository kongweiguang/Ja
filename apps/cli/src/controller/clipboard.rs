// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Session-owned native text clipboard worker.
//!
//! The request queue and delivery gate adapt the bounded, session-lived worker pattern from
//! Codex source paths codex-rs/tui/src/clipboard_copy.rs and clipboard_copy/worker.rs at
//! commit 822e58cc3d666166c7446c5b1ea2e52f5d09594c. Ja keeps only acknowledged native text writes:
//! no terminal escape fallback, HTML, image, or external clipboard process is involved.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Condvar, Mutex, mpsc};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const SETUP_DEADLINE: Duration = Duration::from_secs(5);

type BackendFactory = Arc<dyn Fn() -> Box<dyn ClipboardBackend> + Send + Sync + 'static>;

/// One copy's consumer identity lets the controller ignore late acknowledgements after a switch.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct ClipboardEvent {
    pub(super) request_id: u64,
    pub(super) thread_id: String,
    pub(super) selection_id: Option<u64>,
    pub(super) state: CopyState,
}

/// Delivery is a separate state because a native clipboard write cannot be cancelled once begun.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) enum CopyState {
    Delivering,
    Copied,
    OutcomeUnknown,
    Failed(String),
    Cancelled,
    Superseded,
    TimedOut,
}

/// Injected backend boundary keeps queue and acknowledgement rules testable without the OS clipboard.
pub(super) trait ClipboardBackend: 'static {
    /// @author kongweiguang
    /// Initialize native handles before the shared deadline gate permits a clipboard mutation.
    fn prepare(&mut self) -> Result<(), String>;

    /// @author kongweiguang
    /// Perform exactly one native write; callers never retry an uncertain OS operation.
    fn set_text(&mut self, text: &str) -> Result<(), String>;
}

struct RequestState {
    phase: Mutex<RequestPhase>,
    deadline: Instant,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum RequestPhase {
    Preparing,
    Delivering,
    Terminal,
}

struct CopyRequest {
    request_id: u64,
    thread_id: String,
    selection_id: Option<u64>,
    text: String,
    state: RequestState,
}

struct QueueState {
    pending: Option<Arc<CopyRequest>>,
    running: Option<Arc<CopyRequest>>,
    shutting_down: bool,
}

struct Shared {
    queue: Mutex<QueueState>,
    wake: Condvar,
    events: mpsc::Sender<ClipboardEvent>,
}

/// A lazily started worker keeps TUI startup usable when the native clipboard is unavailable.
pub(super) struct ClipboardWorker {
    shared: Option<Arc<Shared>>,
    events: Option<mpsc::Receiver<ClipboardEvent>>,
    worker: Option<JoinHandle<()>>,
    backend_factory: BackendFactory,
    next_request_id: Option<u64>,
    setup_deadline: Duration,
}

impl ClipboardWorker {
    /// @author kongweiguang
    /// Defer worker creation until the first copy so thread or desktop initialization cannot block startup.
    pub(super) fn new() -> Self {
        Self::with_backend_factory(|| Box::new(NativeClipboard::default()))
    }

    /// @author kongweiguang
    /// Provide a worker-local backend factory so state-machine tests need no desktop session.
    pub(super) fn with_backend_factory(
        factory: impl Fn() -> Box<dyn ClipboardBackend> + Send + Sync + 'static,
    ) -> Self {
        Self::with_backend_factory_and_deadline(factory, SETUP_DEADLINE)
    }

    /// @author kongweiguang
    /// Shorten setup time only for deterministic timeout tests without changing production policy.
    pub(super) fn with_backend_factory_and_deadline(
        factory: impl Fn() -> Box<dyn ClipboardBackend> + Send + Sync + 'static,
        setup_deadline: Duration,
    ) -> Self {
        Self {
            shared: None,
            events: None,
            worker: None,
            backend_factory: Arc::new(factory),
            next_request_id: Some(1),
            setup_deadline,
        }
    }

    /// @author kongweiguang
    /// Queue a request with one active and one replaceable pending slot, bounding stale copy work.
    pub(super) fn submit(
        &mut self,
        thread_id: String,
        selection_id: Option<u64>,
        text: String,
    ) -> Result<u64, String> {
        if text.is_empty() {
            return Err("没有可复制的文本".into());
        }
        self.ensure_started()?;
        let shared = self
            .shared
            .as_ref()
            .ok_or_else(|| "剪贴板 worker 未启动".to_owned())?;
        shared.expire_due_requests();
        let request_id = self
            .next_request_id
            .ok_or_else(|| "剪贴板请求编号已耗尽".to_owned())?;
        self.next_request_id = request_id.checked_add(1);
        let request = Arc::new(CopyRequest {
            request_id,
            thread_id,
            selection_id,
            text,
            state: RequestState {
                phase: Mutex::new(RequestPhase::Preparing),
                deadline: Instant::now() + self.setup_deadline,
            },
        });

        let replaced = {
            let mut queue = lock(&shared.queue);
            if queue.shutting_down {
                return Err("剪贴板 worker 已停止".into());
            }
            let replaced = queue.pending.replace(Arc::clone(&request));
            if let Some(previous) = &replaced {
                previous.finish_before_delivery();
            }
            shared.wake.notify_one();
            replaced
        };
        if let Some(previous) = replaced {
            shared.send(&previous, CopyState::Superseded);
        }
        Ok(request_id)
    }

    /// @author kongweiguang
    /// Cancel only work whose shared phase gate has not admitted a native write.
    pub(super) fn cancel(&mut self, request_id: u64) -> bool {
        let Some(shared) = self.shared.as_ref() else {
            return false;
        };
        shared.expire_due_requests();
        let (cancelled, request) = {
            let mut queue = lock(&shared.queue);
            let pending_match = queue
                .pending
                .as_ref()
                .is_some_and(|request| request.request_id == request_id);
            let running_match = queue
                .running
                .as_ref()
                .is_some_and(|request| request.request_id == request_id);
            let request = if pending_match {
                queue.pending.take()
            } else if running_match {
                queue.running.clone()
            } else {
                None
            };
            let Some(request) = request else {
                return false;
            };
            let cancelled = request.cancel_before_delivery();
            (cancelled, Some(request))
        };
        if cancelled {
            if let Some(request) = request {
                shared.send(&request, CopyState::Cancelled);
            }
            true
        } else {
            false
        }
    }

    /// @author kongweiguang
    /// Poll one result without holding up terminal input or redraw work.
    pub(super) fn try_recv(&self) -> Option<ClipboardEvent> {
        self.shared.as_ref()?.expire_due_requests();
        self.events.as_ref()?.try_recv().ok()
    }

    /// @author kongweiguang
    /// Spawn the one session worker on demand so systems without a clipboard still start normally.
    fn ensure_started(&mut self) -> Result<(), String> {
        if self.shared.is_some() {
            return Ok(());
        }
        let (event_tx, event_rx) = mpsc::channel();
        let shared = Arc::new(Shared {
            queue: Mutex::new(QueueState {
                pending: None,
                running: None,
                shutting_down: false,
            }),
            wake: Condvar::new(),
            events: event_tx,
        });
        let worker_shared = Arc::clone(&shared);
        let factory = Arc::clone(&self.backend_factory);
        let handle = thread::Builder::new()
            .name("ja-clipboard".into())
            .spawn(move || worker_loop(worker_shared, factory))
            .map_err(|error| format!("无法启动剪贴板 worker：{error}"))?;
        self.shared = Some(shared);
        self.events = Some(event_rx);
        self.worker = Some(handle);
        Ok(())
    }
}

impl Drop for ClipboardWorker {
    /// @author kongweiguang
    /// Stop queued work on session exit but detach an uninterruptible native delivery call.
    fn drop(&mut self) {
        let Some(shared) = self.shared.as_ref() else {
            return;
        };
        let mut queue = lock(&shared.queue);
        queue.shutting_down = true;
        if let Some(pending) = queue.pending.take() {
            pending.finish_before_delivery();
        }
        if let Some(running) = queue.running.as_ref() {
            running.cancel_before_delivery();
        }
        shared.wake.notify_all();
        // Dropping JoinHandle detaches the worker. A native clipboard call cannot be interrupted,
        // so joining here would let an OS stall freeze TUI shutdown.
        self.worker.take();
    }
}

impl CopyRequest {
    /// Expire preparation from the UI poller so a blocked native setup cannot hide its deadline.
    fn expire_if_due(&self) -> bool {
        let mut phase = lock(&self.state.phase);
        if *phase != RequestPhase::Preparing || Instant::now() < self.state.deadline {
            return false;
        }
        *phase = RequestPhase::Terminal;
        true
    }

    /// @author kongweiguang
    /// Check expiry before touching the backend; deadline expiry is terminal for this request.
    fn preflight(&self) -> Preflight {
        let mut phase = lock(&self.state.phase);
        match *phase {
            RequestPhase::Terminal => Preflight::Terminal,
            RequestPhase::Delivering => Preflight::Terminal,
            RequestPhase::Preparing if Instant::now() >= self.state.deadline => {
                *phase = RequestPhase::Terminal;
                Preflight::TimedOut
            }
            RequestPhase::Preparing => Preflight::Ready,
        }
    }

    /// @author kongweiguang
    /// Atomically decide whether cancellation or the deadline won before the OS write begins.
    fn begin_delivery(&self) -> Preflight {
        let mut phase = lock(&self.state.phase);
        match *phase {
            RequestPhase::Terminal | RequestPhase::Delivering => Preflight::Terminal,
            RequestPhase::Preparing if Instant::now() >= self.state.deadline => {
                *phase = RequestPhase::Terminal;
                Preflight::TimedOut
            }
            RequestPhase::Preparing => {
                *phase = RequestPhase::Delivering;
                Preflight::Ready
            }
        }
    }

    /// @author kongweiguang
    /// Emit a setup failure only if neither cancellation nor delivery has already claimed the request.
    fn fail_preparation(&self, error: String) -> Option<CopyState> {
        let mut phase = lock(&self.state.phase);
        if *phase != RequestPhase::Preparing {
            return None;
        }
        *phase = RequestPhase::Terminal;
        Some(if Instant::now() >= self.state.deadline {
            CopyState::TimedOut
        } else {
            CopyState::Failed(error)
        })
    }

    /// @author kongweiguang
    /// Complete an admitted native write once and preserve the backend's actual acknowledgement.
    fn finish_delivery(&self, result: Result<(), String>) -> Option<CopyState> {
        let mut phase = lock(&self.state.phase);
        if *phase != RequestPhase::Delivering {
            return None;
        }
        *phase = RequestPhase::Terminal;
        Some(match result {
            Ok(()) => CopyState::Copied,
            Err(error) => CopyState::Failed(error),
        })
    }

    /// Preserve uncertainty when a panic escapes after the native write gate admitted delivery.
    fn finish_delivery_unknown(&self) -> Option<CopyState> {
        let mut phase = lock(&self.state.phase);
        if *phase != RequestPhase::Delivering {
            return None;
        }
        *phase = RequestPhase::Terminal;
        Some(CopyState::OutcomeUnknown)
    }

    /// @author kongweiguang
    /// Mark a queued request terminal before removing it so its worker cannot later write stale text.
    fn finish_before_delivery(&self) -> bool {
        let mut phase = lock(&self.state.phase);
        if *phase != RequestPhase::Preparing {
            return false;
        }
        *phase = RequestPhase::Terminal;
        true
    }

    /// @author kongweiguang
    /// Cancel preparation only; a request already admitted by the gate belongs to the OS call.
    fn cancel_before_delivery(&self) -> bool {
        self.finish_before_delivery()
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Preflight {
    Ready,
    TimedOut,
    Terminal,
}

impl Shared {
    /// Resolve both queue slots from the UI thread while sharing the worker's pre-delivery phase gate.
    fn expire_due_requests(&self) {
        let (expired_pending, expired_running) = {
            let mut queue = lock(&self.queue);
            let expired_pending = if queue
                .pending
                .as_ref()
                .is_some_and(|request| request.expire_if_due())
            {
                queue.pending.take()
            } else {
                None
            };
            let expired_running = queue
                .running
                .as_ref()
                .filter(|request| request.expire_if_due())
                .cloned();
            (expired_pending, expired_running)
        };
        if let Some(request) = expired_pending {
            self.send(&request, CopyState::TimedOut);
        }
        if let Some(request) = expired_running {
            self.send(&request, CopyState::TimedOut);
        }
    }

    /// @author kongweiguang
    /// Associate every asynchronous status with its original UI identity before delivery.
    fn send(&self, request: &CopyRequest, state: CopyState) {
        let _ = self.events.send(ClipboardEvent {
            request_id: request.request_id,
            thread_id: request.thread_id.clone(),
            selection_id: request.selection_id,
            state,
        });
    }

    /// Cancel queued side effects after backend panic; only a later explicit submit may initialize again.
    fn cancel_pending_after_panic(&self) {
        let pending = {
            let mut queue = lock(&self.queue);
            let pending = queue.pending.take();
            pending.filter(|request| request.finish_before_delivery())
        };
        if let Some(request) = pending {
            self.send(&request, CopyState::Cancelled);
        }
    }

    /// Release a terminal request slot before waiting for another explicit user request.
    fn clear_running(&self, request_id: u64) {
        let mut queue = lock(&self.queue);
        if queue
            .running
            .as_ref()
            .is_some_and(|running| running.request_id == request_id)
        {
            queue.running = None;
        }
        self.wake.notify_one();
    }
}

/// @author kongweiguang
/// Wait for bounded queue work, prepare the OS handle, then cross the shared delivery gate once.
fn worker_loop(shared: Arc<Shared>, factory: BackendFactory) {
    let mut backend: Option<Box<dyn ClipboardBackend>> = None;
    loop {
        let request = {
            let mut queue = lock(&shared.queue);
            loop {
                if queue.shutting_down && queue.pending.is_none() {
                    return;
                }
                if let Some(request) = queue.pending.take() {
                    queue.running = Some(Arc::clone(&request));
                    break request;
                }
                queue = shared
                    .wake
                    .wait(queue)
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
            }
        };

        let mut discard_backend = false;
        match request.preflight() {
            Preflight::Ready => {
                if backend.is_none() {
                    match catch_unwind(AssertUnwindSafe(|| factory())) {
                        Ok(created) => backend = Some(created),
                        Err(_) => {
                            let failure =
                                request.fail_preparation("剪贴板后端初始化异常；尚未写入".into());
                            shared.cancel_pending_after_panic();
                            if let Some(state) = failure {
                                shared.send(&request, state);
                            }
                        }
                    }
                }

                if let Some(active_backend) = backend.as_mut() {
                    match catch_unwind(AssertUnwindSafe(|| active_backend.prepare())) {
                        Ok(Ok(())) => match request.begin_delivery() {
                            Preflight::Ready => {
                                shared.send(&request, CopyState::Delivering);
                                match catch_unwind(AssertUnwindSafe(|| {
                                    active_backend.set_text(&request.text)
                                })) {
                                    Ok(result) => {
                                        if let Some(state) = request.finish_delivery(result) {
                                            shared.send(&request, state);
                                        }
                                    }
                                    Err(_) => {
                                        let outcome = request.finish_delivery_unknown();
                                        discard_backend = true;
                                        shared.cancel_pending_after_panic();
                                        if let Some(state) = outcome {
                                            shared.send(&request, state);
                                        }
                                    }
                                }
                            }
                            Preflight::TimedOut => shared.send(&request, CopyState::TimedOut),
                            Preflight::Terminal => {}
                        },
                        Ok(Err(error)) => {
                            if let Some(state) = request.fail_preparation(error) {
                                shared.send(&request, state);
                            }
                        }
                        Err(_) => {
                            let failure =
                                request.fail_preparation("剪贴板后端初始化异常；尚未写入".into());
                            discard_backend = true;
                            shared.cancel_pending_after_panic();
                            if let Some(state) = failure {
                                shared.send(&request, state);
                            }
                        }
                    }
                }
            }
            Preflight::TimedOut => shared.send(&request, CopyState::TimedOut),
            Preflight::Terminal => {}
        }

        if discard_backend {
            discard_backend_handle(&mut backend);
        }
        shared.clear_running(request.request_id);
    }
}

/// Drop a backend that panicked without allowing a secondary destructor panic to kill the worker.
fn discard_backend_handle(backend: &mut Option<Box<dyn ClipboardBackend>>) {
    if let Some(backend) = backend.take() {
        let _ = catch_unwind(AssertUnwindSafe(|| drop(backend)));
    }
}

/// @author kongweiguang
/// Recover poisoned bookkeeping locks so one prior panic does not corrupt future copy identity.
fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

#[derive(Default)]
struct NativeClipboard {
    clipboard: Option<arboard::Clipboard>,
}

impl ClipboardBackend for NativeClipboard {
    /// @author kongweiguang
    /// Create the native handle in the worker; retaining it also keeps Linux clipboard ownership alive.
    fn prepare(&mut self) -> Result<(), String> {
        if self.clipboard.is_none() {
            self.clipboard = Some(
                arboard::Clipboard::new()
                    .map_err(|error| format!("无法连接系统剪贴板：{error}"))?,
            );
        }
        Ok(())
    }

    /// @author kongweiguang
    /// Keep the handle for the worker lifetime and report only arboard's actual write result.
    fn set_text(&mut self, text: &str) -> Result<(), String> {
        self.clipboard
            .as_mut()
            .ok_or_else(|| "系统剪贴板尚未初始化".to_owned())?
            .set_text(text.to_owned())
            .map_err(|error| format!("系统剪贴板写入失败：{error}"))
    }
}
