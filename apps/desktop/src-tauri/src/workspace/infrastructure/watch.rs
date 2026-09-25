// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 使用有界事件合同的原生 Workspace Watcher。

use super::changes::{ChangeDetector, PollingChangeDetector, PollingPolicy};
use super::registry::WorkspaceHandle;
use super::search::is_default_ignored_relative_path;
use crate::workspace::WorkspaceError;
use crate::workspace::application::WorkspaceWatchPort;
use crate::workspace::domain::{
    FileRevision, WatchCommand, WatchRescanResult, WatchStartResult, WatchStopResult,
    WorkspaceChange,
};
use notify::{Config, Event, RecommendedWatcher, RecursiveMode, Watcher};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock, TryLockError};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const COALESCE_WINDOW: Duration = Duration::from_millis(150);
const STOP_POLL_INTERVAL: Duration = Duration::from_millis(10);
pub(crate) const WATCH_STOP_TIMEOUT: Duration = Duration::from_secs(2);
pub(crate) const MAX_PENDING_NATIVE_EVENTS: usize = 1_024;
pub(crate) const MAX_COALESCED_PATHS: usize = 64;

/// Event sink 只接受路径脱敏的领域事件，使 infrastructure 不依赖 Tauri Event API。
pub(crate) type WorkspaceWatchEventSink =
    Arc<dyn Fn(WorkspaceChange) -> Result<(), WorkspaceError> + Send + Sync + 'static>;

pub(crate) struct WatchSession {
    pub(crate) generation: u64,
    pub(crate) stop: SyncSender<()>,
    pub(crate) exit: Receiver<WatchWorkerExit>,
    pub(crate) join: Option<JoinHandle<()>>,
    pub(crate) stop_requested: bool,
    pub(crate) reported_exit: Option<WatchWorkerExit>,
}

/// 只有原生 Watcher 释放后才报告 worker 完成；捕获到的 panic 因而与正常停止保持可区分。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WatchWorkerExit {
    Clean,
    Panicked,
}

/// 单调的进程级启动栅栏；应用退出一旦开始，当前进程内不再允许创建新的
/// native watcher。测试使用独立实例，生产实例没有 reset 入口。
pub(crate) struct WatchAdmission {
    pub(crate) shutdown_started: AtomicBool,
}

impl WatchAdmission {
    /// 构造仅用于进程启动或隔离测试的开放状态；生产全局实例创建后不会替换。
    pub(crate) const fn accepting() -> Self {
        Self {
            shutdown_started: AtomicBool::new(false),
        }
    }

    /// 在等待 lifecycle owner 之前永久关闭 admission，使晚到 start 即使遇到
    /// 正在执行的启动事务，也会立即得到稳定且不含路径的错误。
    pub(crate) fn begin_shutdown(&self) {
        self.shutdown_started.store(true, Ordering::Release);
    }

    /// 在资源构造前检查单调位；Acquire 与 shutdown 的 Release 配对，确保看到
    /// shutdown 后绝不会继续执行 watcher/thread factory。
    pub(crate) fn ensure_start_allowed(&self) -> Result<(), WorkspaceError> {
        if self.shutdown_started.load(Ordering::Acquire) {
            Err(WorkspaceError::WatchUnavailable)
        } else {
            Ok(())
        }
    }
}

static WATCH_SESSIONS: OnceLock<Mutex<HashMap<crate::workspace::WorkspaceId, WatchSession>>> =
    OnceLock::new();
static POLLING_DETECTORS: OnceLock<
    Mutex<HashMap<crate::workspace::WorkspaceId, PollingChangeDetector>>,
> = OnceLock::new();
static WATCH_LIFECYCLE: OnceLock<Mutex<()>> = OnceLock::new();
static WATCH_ADMISSION: WatchAdmission = WatchAdmission::accepting();

/// 返回以不透明 id 为 key 的进程内 Watcher 所有权表，只有 infrastructure 可访问。
pub(crate) fn sessions() -> &'static Mutex<HashMap<crate::workspace::WorkspaceId, WatchSession>> {
    WATCH_SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 返回与每个原生 Watcher 配对的权威 polling fallback，生命周期必须同步回收。
pub(crate) fn polling_detectors()
-> &'static Mutex<HashMap<crate::workspace::WorkspaceId, PollingChangeDetector>> {
    POLLING_DETECTORS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 串行化 start/stop/shutdown 事务，防止并发 start 覆盖 session 记录并遗留孤儿 worker。
pub(crate) fn lifecycle() -> &'static Mutex<()> {
    WATCH_LIFECYCLE.get_or_init(|| Mutex::new(()))
}

/// 返回唯一的生产 admission；保持独立函数可让测试验证相同算法而不污染这个
/// 不可逆的进程状态。
pub(crate) fn process_admission() -> &'static WatchAdmission {
    &WATCH_ADMISSION
}

/// 在公开 stop deadline 内取得 lifecycle 所有权，不无限等待并发 start 或 Workspace switch。
pub(crate) fn lock_lifecycle_until(
    deadline: Instant,
) -> Result<MutexGuard<'static, ()>, WorkspaceError> {
    loop {
        match lifecycle().try_lock() {
            Ok(guard) => return Ok(guard),
            Err(TryLockError::Poisoned(_)) => {
                return Err(WorkspaceError::Io {
                    operation: "watch_state",
                    kind: std::io::ErrorKind::Other.into(),
                });
            }
            Err(TryLockError::WouldBlock) => {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Err(WorkspaceError::WatchShutdownTimeout);
                }
                thread::sleep(remaining.min(STOP_POLL_INTERVAL));
            }
        }
    }
}

/// 把 start 的线性化检查和全部资源构造放进同一个 lifecycle 临界区。锁前检查
/// 让 shutdown 后的晚请求快速失败，锁后复检关闭“检查通过但等待期间退出开始”
/// 的窗口；闭包未执行即意味着没有 watcher、线程或 ownership 记录被创建。
pub(crate) fn with_start_admission_until<T, F>(
    admission: &WatchAdmission,
    deadline: Instant,
    start: F,
) -> Result<T, WorkspaceError>
where
    F: FnOnce() -> Result<T, WorkspaceError>,
{
    admission.ensure_start_allowed()?;
    let _lifecycle = lock_lifecycle_until(deadline)?;
    admission.ensure_start_allowed()?;
    start()
}

/// 只在调用方 shutdown deadline 内取得 watcher ownership，避免等待另一个 stop 变成隐藏 join。
fn lock_sessions_until(
    deadline: Instant,
) -> Result<MutexGuard<'static, HashMap<crate::workspace::WorkspaceId, WatchSession>>, WorkspaceError>
{
    loop {
        match sessions().try_lock() {
            Ok(guard) => return Ok(guard),
            Err(TryLockError::Poisoned(_)) => {
                return Err(WorkspaceError::Io {
                    operation: "watch_state",
                    kind: std::io::ErrorKind::Other.into(),
                });
            }
            Err(TryLockError::WouldBlock) => {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Err(WorkspaceError::WatchShutdownTimeout);
                }
                thread::sleep(remaining.min(STOP_POLL_INTERVAL));
            }
        }
    }
}

/// detector reconciliation 使用同一有界锁预算，避免 rescan 让 stop 超过公开 deadline。
fn lock_detectors_until(
    deadline: Instant,
) -> Result<
    MutexGuard<'static, HashMap<crate::workspace::WorkspaceId, PollingChangeDetector>>,
    WorkspaceError,
> {
    loop {
        match polling_detectors().try_lock() {
            Ok(guard) => return Ok(guard),
            Err(TryLockError::Poisoned(_)) => {
                return Err(WorkspaceError::Io {
                    operation: "watch_state",
                    kind: std::io::ErrorKind::Other.into(),
                });
            }
            Err(TryLockError::WouldBlock) => {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    return Err(WorkspaceError::WatchShutdownTimeout);
                }
                thread::sleep(remaining.min(STOP_POLL_INTERVAL));
            }
        }
    }
}

/// 集中维护退出就绪不变量；即使 worker 不存在，残留 polling baseline 也不能被误报为完全释放。
pub(crate) const fn ownership_tables_are_empty(
    session_count: usize,
    detector_count: usize,
) -> bool {
    session_count == 0 && detector_count == 0
}

/// 只有 native worker 与 polling baseline 都释放才报告完成；poisoned ownership 保守判为未完成。
pub fn is_shutdown_complete() -> bool {
    let Ok(sessions) = sessions().lock() else {
        return false;
    };
    let Ok(detectors) = polling_detectors().lock() else {
        return false;
    };
    ownership_tables_are_empty(sessions.len(), detectors.len())
}

enum WatchWait {
    Event(notify::Result<Event>),
    Timeout,
    Stopped,
    Disconnected,
}

/// 将 stop 消费或 sender 断开都视为终止；这样 ownership 记录异常释放时，
/// worker 也不会继续持有 native watcher 和工作区句柄。
fn stop_requested(stop_receiver: &Receiver<()>) -> bool {
    matches!(
        stop_receiver.try_recv(),
        Ok(()) | Err(TryRecvError::Disconnected)
    )
}

/// notify callback 只进入有界队列；溢出通过原子位升级为 authoritative rescan，不能伪装完整事件流。
pub(crate) fn enqueue_native_event(
    sender: &SyncSender<notify::Result<Event>>,
    overflow: &AtomicBool,
    result: notify::Result<Event>,
) {
    match sender.try_send(result) {
        Ok(()) => {}
        Err(TrySendError::Full(_)) => overflow.store(true, Ordering::Release),
        Err(TrySendError::Disconnected(_)) => {}
    }
}

/// 以 10ms 切片等待 notify，确保 150ms 合并窗口和事件洪峰期间仍可及时响应
/// stop，而不会把一次阻塞 recv 变成隐藏的退出延迟。
fn receive_with_stop(
    receiver: &Receiver<notify::Result<Event>>,
    stop_receiver: &Receiver<()>,
    timeout: Duration,
) -> WatchWait {
    let deadline = Instant::now()
        .checked_add(timeout)
        .unwrap_or_else(Instant::now);
    loop {
        if stop_requested(stop_receiver) {
            return WatchWait::Stopped;
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return WatchWait::Timeout;
        }
        let wait = if remaining < STOP_POLL_INTERVAL {
            remaining
        } else {
            STOP_POLL_INTERVAL
        };
        match receiver.recv_timeout(wait) {
            Ok(result) => return WatchWait::Event(result),
            Err(mpsc::RecvTimeoutError::Timeout) => continue,
            Err(mpsc::RecvTimeoutError::Disconnected) => return WatchWait::Disconnected,
        }
    }
}

/// 只把 root 内原生路径投影为 slash-separated 相对路径，转换失败必须升级 rescan。
pub(crate) fn relative_event_path(root: &Path, path: &Path) -> Option<String> {
    let relative = path.strip_prefix(root).ok()?;
    let value = relative.to_str()?.replace('\\', "/");
    Some(value)
}

/// 在 notify callback 进入有界队列前丢弃构建/cache 目录事件；如果只在 worker
/// 消费阶段过滤，持续编译仍会先灌满队列并错误升级为根级 rescan。
pub(crate) fn retain_relevant_event_paths(
    root: &Path,
    result: notify::Result<Event>,
) -> Option<notify::Result<Event>> {
    match result {
        Ok(mut event) => {
            event.paths.retain(|path| {
                relative_event_path(root, path)
                    .is_none_or(|relative| !is_default_ignored_relative_path(&relative))
            });
            (!event.paths.is_empty()).then_some(Ok(event))
        }
        Err(error) => Some(Err(error)),
    }
}

/// 发出已经归一化的 workspace 事件；该边界只接受相对路径和扫描所得 revision，
/// 让 polling rescan 无需为同一批路径再次读取或 hash 文件。
fn emit_projected_change(
    sink: &WorkspaceWatchEventSink,
    relative_path: String,
    generation: u64,
    revision: Option<FileRevision>,
    requires_rescan: bool,
) -> Result<(), WorkspaceError> {
    sink(WorkspaceChange {
        relative_path,
        generation,
        revision,
        requires_rescan,
    })
}

/// 为小型 native notify 批次读取当前 revision；overflow 路径不会进入这里，
/// 删除事件保持 null，tree/read 仍负责最终权威核对。
fn emit_change(
    sink: &WorkspaceWatchEventSink,
    workspace: &WorkspaceHandle,
    relative_path: String,
    generation: u64,
    requires_rescan: bool,
) -> Result<(), WorkspaceError> {
    let revision = if relative_path.is_empty() {
        None
    } else {
        match workspace.metadata(&relative_path, 4 * 1024 * 1024) {
            Ok(metadata) => Some(metadata.revision),
            Err(WorkspaceError::PathNotFound) => None,
            Err(_) => None,
        }
    };
    emit_projected_change(sink, relative_path, generation, revision, requires_rescan)
}

/// 收集有限数量的相对路径；一旦路径集合或事件可信度超出预算，立即清空细粒度
/// 投影并升级为根级 rescan，避免后续对 1024 个文件逐个计算内容 hash。
pub(crate) fn collect_event(
    root: &Path,
    result: notify::Result<Event>,
    pending: &mut std::collections::BTreeSet<String>,
    requires_rescan: &mut bool,
) {
    if *requires_rescan {
        return;
    }
    match result {
        Ok(event) => {
            for path in event.paths {
                if let Some(relative) = relative_event_path(root, &path) {
                    if is_default_ignored_relative_path(&relative) {
                        continue;
                    }
                    if !pending.contains(&relative) && pending.len() >= MAX_COALESCED_PATHS {
                        pending.clear();
                        *requires_rescan = true;
                        return;
                    }
                    pending.insert(relative);
                } else {
                    pending.clear();
                    *requires_rescan = true;
                    return;
                }
            }
        }
        Err(_) => {
            pending.clear();
            *requires_rescan = true;
        }
    }
}

/// 投影一个合并批次并在每个潜在 hash 前检查 stop；overflow 只发一个空路径
/// marker，既限制工作量，也让 2 秒停止预算不再取决于队列中的文件数量。
pub(crate) fn flush_events_with<F>(
    pending: &mut std::collections::BTreeSet<String>,
    requires_rescan: bool,
    stop_receiver: &Receiver<()>,
    mut emit: F,
) -> bool
where
    F: FnMut(String, bool),
{
    if stop_requested(stop_receiver) {
        pending.clear();
        return false;
    }
    if requires_rescan {
        pending.clear();
        emit(String::new(), true);
        return !stop_requested(stop_receiver);
    }
    let paths = std::mem::take(pending);
    for relative in paths {
        if stop_requested(stop_receiver) {
            return false;
        }
        emit(relative, false);
    }
    !stop_requested(stop_receiver)
}

/// 将纯批次决策接到 Tauri Event；发送失败不伪造 watcher 失败，后续 focus
/// reconciliation 仍能通过 tree/read 恢复权威状态。
fn flush_events(
    sink: &WorkspaceWatchEventSink,
    workspace: &WorkspaceHandle,
    pending: &mut std::collections::BTreeSet<String>,
    generation: u64,
    requires_rescan: bool,
    stop_receiver: &Receiver<()>,
) -> bool {
    flush_events_with(
        pending,
        requires_rescan,
        stop_receiver,
        |relative, rescan| {
            let _ = emit_change(sink, workspace, relative, generation, rescan);
        },
    )
}

/// 合并普通变更；队列溢出、非法路径或路径预算超限时立即结束当前细粒度批次，
/// 只发根级 marker。所有等待和 flush 都检查 stop，确保事件洪峰可中断。
fn run_watcher(
    root: PathBuf,
    workspace: WorkspaceHandle,
    sink: WorkspaceWatchEventSink,
    generation: u64,
    receiver: Receiver<notify::Result<Event>>,
    stop_receiver: Receiver<()>,
    overflow: Arc<AtomicBool>,
) {
    let mut pending = std::collections::BTreeSet::new();
    loop {
        if stop_requested(&stop_receiver) {
            break;
        }
        if overflow.swap(false, Ordering::AcqRel) {
            if !flush_events(
                &sink,
                &workspace,
                &mut pending,
                generation,
                true,
                &stop_receiver,
            ) {
                break;
            }
            continue;
        }
        match receive_with_stop(&receiver, &stop_receiver, COALESCE_WINDOW) {
            WatchWait::Event(result) => {
                let mut requires_rescan = false;
                collect_event(&root, result, &mut pending, &mut requires_rescan);
                // 每个事件后重新开始完整 timeout 才是真正的静默 debounce 窗口；连续保存会合并为一批。
                loop {
                    if requires_rescan {
                        let _ = overflow.swap(false, Ordering::AcqRel);
                        pending.clear();
                        break;
                    }
                    if overflow.swap(false, Ordering::AcqRel) {
                        pending.clear();
                        requires_rescan = true;
                        break;
                    }
                    match receive_with_stop(&receiver, &stop_receiver, COALESCE_WINDOW) {
                        WatchWait::Event(result) => {
                            collect_event(&root, result, &mut pending, &mut requires_rescan)
                        }
                        WatchWait::Timeout => break,
                        WatchWait::Stopped | WatchWait::Disconnected => return,
                    }
                }
                requires_rescan |= overflow.swap(false, Ordering::AcqRel);
                if requires_rescan {
                    pending.clear();
                }
                if !flush_events(
                    &sink,
                    &workspace,
                    &mut pending,
                    generation,
                    requires_rescan,
                    &stop_receiver,
                ) {
                    break;
                }
            }
            WatchWait::Timeout => {
                if overflow.swap(false, Ordering::AcqRel)
                    && !flush_events(
                        &sink,
                        &workspace,
                        &mut pending,
                        generation,
                        true,
                        &stop_receiver,
                    )
                {
                    break;
                }
            }
            WatchWait::Stopped | WatchWait::Disconnected => break,
        }
    }
}

/// Watcher worker 停止后删除 polling detector，同时确保 detector 锁竞争不突破调用方 stop deadline。
fn clear_detector_until(
    workspace_id: crate::workspace::WorkspaceId,
    deadline: Instant,
) -> Result<(), WorkspaceError> {
    lock_detectors_until(deadline)?.remove(&workspace_id);
    Ok(())
}

/// 计算 stop deadline 时对单调时钟溢出失败关闭，避免 cleanup 路径 panic。
pub(crate) fn stop_deadline_after(timeout: Duration) -> Instant {
    Instant::now()
        .checked_add(timeout)
        .unwrap_or_else(Instant::now)
}

enum SessionStopOutcome {
    Finished { worker_panicked: bool },
    TimedOut,
}

/// stop 请求使用容量一队列且不阻塞；队列已满表示唯一 token 已等待 worker 消费。
fn request_session_stop(session: &mut WatchSession) {
    if session.stop_requested {
        return;
    }
    match session.stop.try_send(()) {
        Ok(()) | Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {
            session.stop_requested = true;
        }
    }
}

/// 只等待到调用方 deadline，且仅在 Rust 报告线程结束后 join；超时时必须保留完整 session，
/// 供后续 Workspace switch 或应用退出继续对账。
fn finish_session_until(session: &mut WatchSession, deadline: Instant) -> SessionStopOutcome {
    request_session_stop(session);
    loop {
        if session.reported_exit.is_none() {
            match session.exit.try_recv() {
                Ok(exit) => session.reported_exit = Some(exit),
                Err(TryRecvError::Empty | TryRecvError::Disconnected) => {}
            }
        }
        let finished = session
            .join
            .as_ref()
            .is_none_or(std::thread::JoinHandle::is_finished);
        if finished {
            let join_panicked = session.join.take().is_some_and(|join| join.join().is_err());
            return SessionStopOutcome::Finished {
                worker_panicked: join_panicked
                    || session.reported_exit == Some(WatchWorkerExit::Panicked),
            };
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return SessionStopOutcome::TimedOut;
        }
        let wait = remaining.min(STOP_POLL_INTERVAL);
        if session.reported_exit.is_some() {
            thread::sleep(wait);
            continue;
        }
        match session.exit.recv_timeout(wait) {
            Ok(exit) => session.reported_exit = Some(exit),
            Err(mpsc::RecvTimeoutError::Timeout | mpsc::RecvTimeoutError::Disconnected) => {}
        }
    }
}

/// 在真实 deadline 前停止 watcher；超时重插 ownership 与 detector，避免仍有 worker 时误报已停止。
pub(crate) fn stop_session_until(
    workspace_id: crate::workspace::WorkspaceId,
    deadline: Instant,
) -> Result<bool, WorkspaceError> {
    let mut session_table = lock_sessions_until(deadline)?;
    let Some(mut session) = session_table.remove(&workspace_id) else {
        drop(session_table);
        clear_detector_until(workspace_id, deadline)?;
        return if Instant::now() > deadline {
            Err(WorkspaceError::WatchShutdownTimeout)
        } else {
            Ok(false)
        };
    };

    let outcome = finish_session_until(&mut session, deadline);
    if matches!(outcome, SessionStopOutcome::TimedOut) {
        session_table.insert(workspace_id, session);
        return Err(WorkspaceError::WatchShutdownTimeout);
    }
    drop(session_table);
    clear_detector_until(workspace_id, deadline)?;
    if matches!(
        outcome,
        SessionStopOutcome::Finished {
            worker_panicked: true
        }
    ) {
        return Err(WorkspaceError::io(
            "watch_join",
            std::io::Error::from(std::io::ErrorKind::Other),
        ));
    }
    if Instant::now() > deadline {
        return Err(WorkspaceError::WatchShutdownTimeout);
    }
    Ok(true)
}

/// 只停止调用方持有的 generation，晚到 React cleanup 不得终止已替换它的新 session。
pub(crate) fn stop_session_generation(
    workspace_id: crate::workspace::WorkspaceId,
    generation: u64,
) -> Result<bool, WorkspaceError> {
    let deadline = stop_deadline_after(WATCH_STOP_TIMEOUT);
    let _lifecycle = lock_lifecycle_until(deadline)?;
    let active_generation = lock_sessions_until(deadline)?
        .get(&workspace_id)
        .map(|session| session.generation);
    if active_generation != Some(generation) {
        return Ok(false);
    }
    stop_session_until(workspace_id, deadline)
}

/// 删除仅有 detector 的启动残留，但保留与超时 worker 配对的 baseline，这是退出对账不变量。
fn clear_orphan_detectors_until(deadline: Instant) -> Result<(), WorkspaceError> {
    let active = lock_sessions_until(deadline)?
        .keys()
        .copied()
        .collect::<HashSet<_>>();
    lock_detectors_until(deadline)?.retain(|workspace_id, _| active.contains(workspace_id));
    Ok(())
}

/// 先永久关闭指定 admission，再在共享 deadline 内回收其已获准的 watcher。
/// 即使拿锁或 join 超时，栅栏也保持关闭，重试只继续清理而不会重新接纳资源。
pub(crate) fn shutdown_all_with_admission_until(
    admission: &WatchAdmission,
    deadline: Instant,
) -> Result<(), WorkspaceError> {
    admission.begin_shutdown();
    let _lifecycle = lock_lifecycle_until(deadline)?;
    let mut workspace_ids = HashSet::new();
    workspace_ids.extend(lock_sessions_until(deadline)?.keys().copied());
    workspace_ids.extend(lock_detectors_until(deadline)?.keys().copied());

    let mut failure = None;
    for workspace_id in workspace_ids {
        match stop_session_until(workspace_id, deadline) {
            Ok(_) => {}
            Err(error) if failure.is_none() => failure = Some(error),
            Err(_) => {}
        }
    }
    clear_orphan_detectors_until(deadline)?;
    if let Some(failure) = failure {
        return Err(failure);
    }
    if !is_shutdown_complete() || Instant::now() > deadline {
        return Err(WorkspaceError::WatchShutdownTimeout);
    }
    Ok(())
}

/// 在应用退出前永久关闭进程级 watcher admission，并回收所有已获准 worker。
/// 超时后的再次调用只对账残留 ownership；同一进程内任何 start 都不能重开。
pub fn shutdown_all_until(deadline: Instant) -> Result<(), WorkspaceError> {
    shutdown_all_with_admission_until(process_admission(), deadline)
}

/// 即使 UI 的显式 stop 晚到，一个 RuntimeHost 在 Workspace switch 后也最多持有一个活跃 Watcher。
pub(crate) fn stop_other_sessions_until(
    current: crate::workspace::WorkspaceId,
    deadline: Instant,
) -> Result<(), WorkspaceError> {
    let ids = lock_sessions_until(deadline)?
        .keys()
        .copied()
        .filter(|id| *id != current)
        .collect::<Vec<_>>();
    for id in ids {
        let _ = stop_session_until(id, deadline)?;
    }
    Ok(())
}

/// 通过进程 admission 后才启动 notify 并建立 polling 基线；整个资源构造与
/// ownership 提交都持有 lifecycle owner，因此退出要么拒绝该启动，要么随后
/// 接管并回收一个完整 session，不会留下未登记 worker。
fn start_session(
    workspace: WorkspaceHandle,
    sink: WorkspaceWatchEventSink,
    generation: u64,
) -> Result<(bool, u64), WorkspaceError> {
    let generation = generation.max(1);
    let stop_deadline = stop_deadline_after(WATCH_STOP_TIMEOUT);
    with_start_admission_until(process_admission(), stop_deadline, || {
        let active_generation = lock_sessions_until(stop_deadline)?
            .get(&workspace.id())
            .map(|session| session.generation);
        if let Some(active_generation) = active_generation.filter(|active| *active >= generation) {
            return Ok((false, active_generation));
        }
        stop_other_sessions_until(workspace.id(), stop_deadline)?;
        let _ = stop_session_until(workspace.id(), stop_deadline)?;
        let (event_sender, event_receiver) = mpsc::sync_channel(MAX_PENDING_NATIVE_EVENTS);
        let event_overflow = Arc::new(AtomicBool::new(false));
        let callback_overflow = Arc::clone(&event_overflow);
        let callback_root = workspace.root_path().to_path_buf();
        let (stop_sender, stop_receiver) = mpsc::sync_channel(1);
        let (exit_sender, exit_receiver) = mpsc::sync_channel(1);
        let mut watcher = RecommendedWatcher::new(
            move |result| {
                if let Some(relevant) = retain_relevant_event_paths(&callback_root, result) {
                    enqueue_native_event(&event_sender, &callback_overflow, relevant);
                }
            },
            Config::default().with_poll_interval(Duration::from_millis(100)),
        )
        .map_err(|_| WorkspaceError::WatchUnavailable)?;
        watcher
            .watch(workspace.root_path(), RecursiveMode::Recursive)
            .map_err(|_| WorkspaceError::WatchUnavailable)?;
        // notify 已开始接收事件，切换关键路径只登记惰性 detector；初始文件树本身是
        // 权威快照，后续 focus/overflow 才需要构建轮询基线。
        let detector =
            PollingChangeDetector::new_uninitialized(workspace.clone(), PollingPolicy::default());
        // ownership 表在 native 资源构造完成后才提交；锁获取失败时局部 watcher
        // 会随函数返回而释放，不会留下未注册的 OS listener。
        let registration_deadline = stop_deadline_after(WATCH_STOP_TIMEOUT);
        let mut session_table = lock_sessions_until(registration_deadline)?;
        let mut detector_table = lock_detectors_until(registration_deadline)?;
        if session_table.contains_key(&workspace.id())
            || detector_table.contains_key(&workspace.id())
        {
            return Err(WorkspaceError::WatchUnavailable);
        }
        let root = workspace.root_path().to_path_buf();
        let worker_workspace = workspace.clone();
        let worker_sink = sink.clone();
        let join = thread::Builder::new()
            .name("ja-workspace-watch".to_owned())
            .spawn(move || {
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    // 完成信号位于 catch 边界之外，只有 Watcher drop 释放原生资源后才发送。
                    run_watcher(
                        root,
                        worker_workspace,
                        worker_sink,
                        generation,
                        event_receiver,
                        stop_receiver,
                        event_overflow,
                    );
                    drop(watcher);
                }));
                let _ = exit_sender.send(if outcome.is_ok() {
                    WatchWorkerExit::Clean
                } else {
                    WatchWorkerExit::Panicked
                });
            })
            .map_err(|_| WorkspaceError::WatchUnavailable)?;
        session_table.insert(
            workspace.id(),
            WatchSession {
                generation,
                stop: stop_sender,
                exit: exit_receiver,
                join: Some(join),
                stop_requested: false,
                reported_exit: None,
            },
        );
        detector_table.insert(workspace.id(), detector);
        drop(detector_table);
        drop(session_table);
        Ok((true, generation))
    })
}

/// 原生 Watch port 固定绑定 Workspace 与脱敏事件 sink，线程不会持有 Tauri handle。
pub(crate) struct NativeWorkspaceWatchPort {
    workspace: WorkspaceHandle,
    sink: WorkspaceWatchEventSink,
}

impl NativeWorkspaceWatchPort {
    /// interface 注入单一事件 sink；基础设施只产生领域事件，发送失败不暴露平台细节。
    pub(crate) fn new(workspace: WorkspaceHandle, sink: WorkspaceWatchEventSink) -> Self {
        Self { workspace, sink }
    }
}

impl WorkspaceWatchPort for NativeWorkspaceWatchPort {
    /// 两阶段 admission 在构造 watcher 前后复核 shutdown，避免退出竞态留下孤儿 worker。
    fn start(&self, command: WatchCommand) -> Result<WatchStartResult, WorkspaceError> {
        process_admission().ensure_start_allowed()?;
        let (started, generation) = start_session(
            self.workspace.clone(),
            Arc::clone(&self.sink),
            command.generation,
        )?;
        Ok(WatchStartResult {
            started,
            generation,
        })
    }

    /// 只停止匹配 generation 的 session，晚到请求不能回收新 workspace 的 worker。
    fn stop(&self, command: WatchCommand) -> Result<WatchStopResult, WorkspaceError> {
        Ok(WatchStopResult {
            stopped: stop_session_generation(self.workspace.id(), command.generation)?,
        })
    }

    /// Rescan 在扫描前后都核对 generation；中途切换时丢弃旧结果并失败关闭。
    fn rescan(&self, command: WatchCommand) -> Result<WatchRescanResult, WorkspaceError> {
        let generation = command.generation.max(1);
        let deadline = stop_deadline_after(WATCH_STOP_TIMEOUT);
        let active_generation = lock_sessions_until(deadline)?
            .get(&self.workspace.id())
            .map(|session| session.generation);
        if active_generation != Some(generation) {
            return Err(WorkspaceError::WatchUnavailable);
        }
        let batch = {
            let mut detectors = lock_detectors_until(deadline)?;
            detectors
                .get_mut(&self.workspace.id())
                .ok_or(WorkspaceError::WatchUnavailable)?
                .rescan()?
        };
        let still_active = lock_sessions_until(stop_deadline_after(WATCH_STOP_TIMEOUT))?
            .get(&self.workspace.id())
            .is_some_and(|session| session.generation == generation);
        if !still_active {
            return Err(WorkspaceError::WatchUnavailable);
        }
        let emitted_paths = if batch.requires_rescan {
            let _ = emit_projected_change(&self.sink, String::new(), generation, None, true);
            0
        } else {
            let count = batch.changes.len();
            for change in batch.changes {
                let _ = emit_projected_change(
                    &self.sink,
                    change.relative_path,
                    generation,
                    change.current,
                    false,
                );
            }
            if count == 0 {
                let _ = emit_projected_change(&self.sink, String::new(), generation, None, false);
            }
            count
        };
        Ok(WatchRescanResult {
            generation,
            requires_rescan: batch.requires_rescan,
            emitted_paths,
        })
    }
}
