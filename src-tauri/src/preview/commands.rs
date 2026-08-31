// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 仅 URL Preview 模型的 Tauri/WebView adapter。
//
// Preview window 使用主 capability 文件中不存在的 label，因此不会获得任何 Tauri command。
// URL/generation 校验仍以模型为权威；Wry callback 只报告已收紧的事件。

use super::error::{PreviewError, PreviewErrorCode};
use super::load_watchdog::{LoadTimeoutRuntime, LoadTimeoutTask, PreviewLoadWatchdog};
use super::model::{
    NavigationSource, PreviewEvent, PreviewId, PreviewLimits, PreviewSessionSnapshot,
    PreviewShutdownReport,
};
use super::session::{PreviewCloseTicket, PreviewManager};
#[cfg(windows)]
use crate::native_shortcuts::{NativeShortcutHost, install_preview_webview};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use tauri::{
    Emitter, LogicalPosition, LogicalSize, Manager, Rect, Runtime, WebviewBuilder, WebviewUrl,
    webview::PageLoadEvent,
};
use url::Url;
#[cfg(windows)]
use webview2_com::{
    Microsoft::Web::WebView2::Win32::ICoreWebView2, NavigationCompletedEventHandler,
};
#[cfg(windows)]
use windows_core::BOOL;

/// Preview window 与主 UI 共用的事件名，禁止调用方选择任意 channel。
pub const PREVIEW_EVENT: &str = "ja://preview";

/// Wry 0.55 只公开 start/finish，不公开 WebView2 navigation error status；
/// 因此超过有界时间仍无终态 callback 时必须报告失败。
const PREVIEW_LOAD_TIMEOUT: Duration = Duration::from_secs(30);

#[cfg(windows)]
/// 原生 handler 安装与页面加载分别设定边界，避免卡住的 UI thread 留下
/// 由模型持有但无人观察的子 WebView。
const PREVIEW_NATIVE_HANDLER_INSTALL_TIMEOUT: Duration = Duration::from_secs(2);

/// 静态 UI 消息不包含 engine 诊断或请求 URL，防止原生隐私数据泄漏。
const PREVIEW_LOAD_TIMEOUT_MESSAGE: &str = "preview load timed out";
const PREVIEW_NAVIGATION_FAILED_MESSAGE: &str = "preview navigation failed";
const PREVIEW_NAVIGATION_BLOCKED_MESSAGE: &str = "preview navigation was blocked";

/// 包装 Tauri 全局 runtime 的任务句柄；Preview 状态层只看到可取消能力，
/// 不获取 framework handle 或执行器类型。
struct TauriLoadTimeoutTask(tauri::async_runtime::JoinHandle<()>);

impl LoadTimeoutTask for TauriLoadTimeoutTask {
    /// 将 session 生命周期取消映射到 Tauri task abort，不等待任务结束，避免阻塞 UI callback。
    fn abort(&self) {
        self.0.abort();
    }
}

/// composition adapter 负责把 framework-neutral 延迟请求派发到 Tauri 全局 runtime；
/// 该 runtime 可从 WebView2 原生 callback 线程安全访问，不要求当前 Tokio context。
struct TauriLoadTimeoutRuntime;

impl LoadTimeoutRuntime for TauriLoadTimeoutRuntime {
    /// Tauri 全局 runtime 拥有 timer 与 callback；返回的 handle 仍由 watchdog registry
    /// 按 session/generation 取消，避免 detached task 越过 Preview 生命周期。
    fn schedule(
        &self,
        delay: Duration,
        callback: Box<dyn FnOnce() + Send + 'static>,
    ) -> Box<dyn LoadTimeoutTask> {
        let task = tauri::async_runtime::spawn(async move {
            tokio::time::sleep(delay).await;
            callback();
        });
        Box::new(TauriLoadTimeoutTask(task))
    }
}

/// 脱敏 recovery 计数永不公开不透明子 WebView identity。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewRecoveryReport {
    pub observed: usize,
    pub recovered: usize,
    pub failed: usize,
    pub pending: usize,
}

/// 将等待 retry 的 identity 与当前 recovery attempt 拥有的 identity 分开，
/// 防止并发 command 重复关闭同一子窗口。
#[derive(Default)]
struct PreviewUnownedRecoveryState {
    pending: HashSet<PreviewId>,
    claimed: HashSet<PreviewId>,
}

/// 有界 registry 记录打开失败且未向 renderer 返回 session identity 的原生子窗口；
/// 具体状态仍以模型为权威。
#[derive(Clone)]
pub(crate) struct PreviewUnownedRecoveryRegistry {
    max_identities: usize,
    state: Arc<Mutex<PreviewUnownedRecoveryState>>,
}

impl PreviewUnownedRecoveryRegistry {
    /// 使用与活动 Preview session 相同的上限，使 recovery 元数据不会超过其代表的原生资源。
    pub(crate) fn new(max_identities: usize) -> Self {
        Self {
            max_identities,
            state: Arc::new(Mutex::new(PreviewUnownedRecoveryState::default())),
        }
    }

    /// 标记刚创建的原生子窗口并原子领取首次 rollback，任何并发 recovery command
    /// 都看不到未领取的转换窗口。
    pub(crate) fn begin_created_rollback(&self, id: PreviewId) -> Result<(), PreviewError> {
        let mut state = self.lock_state()?;
        if state.claimed.contains(&id) {
            return Err(PreviewError::new(PreviewErrorCode::NativeClosePending));
        }
        if state.pending.remove(&id) {
            state.claimed.insert(id);
            return Ok(());
        }
        if state.pending.len().saturating_add(state.claimed.len()) >= self.max_identities {
            return Err(PreviewError::new(
                PreviewErrorCode::InternalStateUnavailable,
            ));
        }
        state.claimed.insert(id);
        Ok(())
    }

    /// 在一次 lock 周期内领取当前 pending snapshot；之后新增的 identity 刻意留给
    /// 下一次显式 recovery command。
    fn claim_pending(&self) -> Result<Vec<PreviewId>, PreviewError> {
        let mut state = self.lock_state()?;
        let identities = state.pending.iter().copied().collect::<Vec<_>>();
        for id in &identities {
            state.pending.remove(id);
            state.claimed.insert(*id);
        }
        Ok(identities)
    }

    /// 将失败 attempt 放回 retryable 集合，同时保证 identity 不重复。
    pub(crate) fn release(&self, id: PreviewId) -> Result<(), PreviewError> {
        let mut state = self.lock_state()?;
        if state.claimed.remove(&id) || state.pending.contains(&id) {
            state.pending.insert(id);
            return Ok(());
        }
        Err(PreviewError::new(
            PreviewErrorCode::InternalStateUnavailable,
        ))
    }

    /// 只有原生 close 与模型 finalization 都成功后才清除 recovery 元数据；
    /// 普通 owner close 在此是无害 no-op。
    fn clear_after_finalize(&self, id: PreviewId) -> Result<(), PreviewError> {
        let mut state = self.lock_state()?;
        state.pending.remove(&id);
        state.claimed.remove(&id);
        Ok(())
    }

    /// 同时统计 retryable 与 in-progress identity，避免另一个 recovery 仍持有子窗口时
    /// 并发调用方误读 `pending = 0`。
    pub(crate) fn unresolved_count(&self) -> Result<usize, PreviewError> {
        let state = self.lock_state()?;
        Ok(state.pending.len().saturating_add(state.claimed.len()))
    }

    /// poisoned registry 映射为同一个稳定且已脱敏的 host-state 故障。
    fn lock_state(&self) -> Result<MutexGuard<'_, PreviewUnownedRecoveryState>, PreviewError> {
        self.state
            .lock()
            .map_err(|_| PreviewError::new(PreviewErrorCode::InternalStateUnavailable))
    }
}

/// command 准入与 session 状态分开追踪，使 shutdown 可以等待已线性化的原生工作，
/// 同时拒绝任何后续操作。
#[derive(Clone, Default)]
pub(crate) struct PreviewOperationFence {
    pub(crate) shared: Arc<(Mutex<PreviewOperationState>, Condvar)>,
}

#[derive(Default)]
pub(crate) struct PreviewOperationState {
    pub(crate) shutdown_started: bool,
    pub(crate) in_flight: usize,
}

/// RAII 保证每个已准入 command 在所有模型与原生 cleanup 路径中都持续计数。
pub(crate) struct PreviewOperationPermit {
    fence: PreviewOperationFence,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct PreviewOperationDrain {
    pub(crate) drained: bool,
    pub(crate) in_flight: usize,
}

impl PreviewOperationFence {
    /// shutdown 前先线性化 command，并使用 checked accounting；计数溢出时失败关闭，
    /// 不能静默丢失 permit。
    fn enter(&self) -> Result<PreviewOperationPermit, PreviewError> {
        let (lock, _) = &*self.shared;
        let mut state = Self::lock_state(lock)?;
        if state.shutdown_started {
            return Err(PreviewError::new(PreviewErrorCode::ShutdownStarted));
        }
        state.in_flight = state.in_flight.checked_add(1).ok_or(PreviewError::new(
            PreviewErrorCode::InternalStateUnavailable,
        ))?;
        Ok(PreviewOperationPermit {
            fence: self.clone(),
        })
    }

    /// 永久关闭准入；重复 shutdown attempt 仍有效，使 pending 原生 close identity
    /// 可在同一 fence 下重试。
    fn start_shutdown(&self) -> Result<(), PreviewError> {
        let (lock, condition) = &*self.shared;
        let mut state = Self::lock_state(lock)?;
        state.shutdown_started = true;
        condition.notify_all();
        Ok(())
    }

    /// 只等待到调用方绝对 deadline，并报告 outstanding operation，不能伪装为已取消或已完成。
    fn wait_until(&self, deadline: Instant) -> Result<PreviewOperationDrain, PreviewError> {
        let (lock, condition) = &*self.shared;
        let mut state = Self::lock_state(lock)?;
        while state.in_flight > 0 {
            let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
                break;
            };
            if remaining.is_zero() {
                break;
            }
            let (next, timeout) = condition
                .wait_timeout(state, remaining)
                .map_err(|_| PreviewError::new(PreviewErrorCode::InternalStateUnavailable))?;
            state = next;
            if timeout.timed_out() && state.in_flight > 0 {
                break;
            }
        }
        Ok(PreviewOperationDrain {
            drained: state.in_flight == 0,
            in_flight: state.in_flight,
        })
    }

    /// poison 映射为 Preview registry 使用的同一稳定 host-state error。
    pub(crate) fn lock_state(
        lock: &Mutex<PreviewOperationState>,
    ) -> Result<MutexGuard<'_, PreviewOperationState>, PreviewError> {
        lock.lock()
            .map_err(|_| PreviewError::new(PreviewErrorCode::InternalStateUnavailable))
    }
}

impl Drop for PreviewOperationPermit {
    /// 精确释放一个 operation，并唤醒受 deadline 约束的 shutdown waiter。
    fn drop(&mut self) {
        let (lock, condition) = &*self.fence.shared;
        let Ok(mut state) = lock.lock() else {
            return;
        };
        state.in_flight = state.in_flight.saturating_sub(1);
        condition.notify_all();
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum NativeCloseOutcome {
    Acknowledged,
    AlreadyAbsent,
}

/// 只关闭权威 Preview snapshot 标识的子窗口；仅在 Tauri 确认窗口消失后
/// 才释放其 accelerator registration。
fn close_native_preview<R: Runtime>(
    app: &tauri::AppHandle<R>,
    snapshot: &PreviewSessionSnapshot,
) -> Result<NativeCloseOutcome, PreviewError> {
    let outcome = match app.get_webview(snapshot.window.label()) {
        Some(webview) => webview
            .close()
            .map(|_| NativeCloseOutcome::Acknowledged)
            .map_err(|_| PreviewError::new(PreviewErrorCode::DependencyRequest))?,
        None => NativeCloseOutcome::AlreadyAbsent,
    };
    #[cfg(windows)]
    if let Some(shortcuts) = app.try_state::<NativeShortcutHost>() {
        shortcuts.uninstall_after_close(snapshot.window.label());
    }
    Ok(outcome)
}

/// 受管 Preview 状态；原生窗口 handle 仍由 Tauri 拥有。
#[derive(Clone)]
pub struct PreviewCommandHost {
    pub(crate) manager: PreviewManager,
    load_watchdog: PreviewLoadWatchdog,
    pub(crate) operation_fence: PreviewOperationFence,
    pub(crate) unowned_recovery: PreviewUnownedRecoveryRegistry,
}

impl PreviewCommandHost {
    /// 通过可失败构造校验产品默认预算，使配置错误在 Tauri setup 阶段可诊断退出，
    /// 而不是把本应可恢复的初始化故障升级为进程 panic。
    pub fn new() -> Result<Self, PreviewError> {
        let recovery_limit = PreviewLimits::default().max_sessions;
        Ok(Self {
            manager: PreviewManager::default_manager()?,
            load_watchdog: PreviewLoadWatchdog::new(Arc::new(TauriLoadTimeoutRuntime)),
            operation_fence: PreviewOperationFence::default(),
            unowned_recovery: PreviewUnownedRecoveryRegistry::new(recovery_limit),
        })
    }

    /// 返回 shutdown 与 command adapter 共用的 manager，维持单一状态 owner。
    pub fn manager(&self) -> &PreviewManager {
        &self.manager
    }

    /// 无 handle shutdown 在仍存在原生 identity 时保持失败关闭；composition root
    /// 应使用 `shutdown_until` 等待 close ACK。
    pub fn shutdown(&self) -> Result<(), PreviewError> {
        let deadline = Instant::now()
            .checked_add(Duration::from_secs(10))
            .ok_or(PreviewError::new(PreviewErrorCode::ShutdownDeadline))?;
        let drain = self.begin_shutdown_until(deadline)?;
        if !drain.drained {
            return Err(PreviewError::new(PreviewErrorCode::ShutdownDeadline));
        }
        if self.manager.active_count()? > 0 || self.unowned_recovery.unresolved_count()? > 0 {
            return Err(PreviewError::new(PreviewErrorCode::NativeClosePending));
        }
        Ok(())
    }

    /// 永久隔离 command，等待已准入工作直至绝对 deadline，关闭每个子 WebView，
    /// 并返回脱敏 ACK 计数。
    pub fn shutdown_until<R: Runtime>(
        &self,
        app: &tauri::AppHandle<R>,
        deadline: Instant,
    ) -> Result<PreviewShutdownReport, PreviewError> {
        self.shutdown_with_closer_until(deadline, |snapshot| close_native_preview(app, snapshot))
    }

    /// 接触模型或原生状态前获取一个进程生命周期 operation permit。
    pub(crate) fn enter_operation(&self) -> Result<PreviewOperationPermit, PreviewError> {
        self.operation_fence.enter()
    }

    /// 等待前启动全部单调 fence，使 callback 与 timer 不能在已准入 command 排空时重新填充状态。
    pub(crate) fn begin_shutdown_until(
        &self,
        deadline: Instant,
    ) -> Result<PreviewOperationDrain, PreviewError> {
        self.operation_fence.start_shutdown()?;
        let manager_result = self.manager.begin_shutdown();
        let watchdog_result = self.load_watchdog.cancel_all();
        manager_result?;
        watchdog_result?;
        self.operation_fence.wait_until(deadline)
    }

    /// 完成一个已 ACK 的原生 close 后，才遗忘与该模型 session 配对且无 renderer owner
    /// 的 recovery identity。
    fn finalize_acknowledged_close(
        &self,
        ticket: PreviewCloseTicket,
    ) -> Result<PreviewSessionSnapshot, PreviewError> {
        let id = ticket.snapshot().id;
        if let Err(error) = self.load_watchdog.cancel(id) {
            let _ = self.manager.abort_close(&ticket);
            return Err(error);
        }
        let snapshot = match self.manager.finalize_close(ticket.clone()) {
            Ok(snapshot) => snapshot,
            Err(error) => {
                let _ = self.manager.abort_close(&ticket);
                return Err(error);
            }
        };
        self.unowned_recovery.clear_after_finalize(id)?;
        Ok(snapshot)
    }

    /// close attempt 与精确模型 claim 配对；原生失败会释放 claim，
    /// 使同一 identity 仍可重试。
    fn close_ticket_with<F>(
        &self,
        ticket: PreviewCloseTicket,
        closer: F,
    ) -> Result<(PreviewSessionSnapshot, NativeCloseOutcome), PreviewError>
    where
        F: FnOnce(&PreviewSessionSnapshot) -> Result<NativeCloseOutcome, PreviewError>,
    {
        let outcome = match closer(ticket.snapshot()) {
            Ok(outcome) => outcome,
            Err(error) => {
                self.manager.abort_close(&ticket)?;
                return Err(error);
            }
        };
        let snapshot = self.finalize_acknowledged_close(ticket)?;
        Ok((snapshot, outcome))
    }

    /// 首次 rollback 副作用前先标记子窗口；close 或模型失败时将 identity
    /// 移入显式 retry queue。
    fn rollback_created_webview<R: Runtime>(
        &self,
        app: &tauri::AppHandle<R>,
        id: PreviewId,
    ) -> Result<(), PreviewError> {
        self.rollback_created_with_closer(id, |snapshot| close_native_preview(app, snapshot))
    }

    /// 原生 closer 保持可注入，使测试能证明首次 rollback 失败会进入 pending，
    /// 而不是丢失子窗口 identity。
    pub(crate) fn rollback_created_with_closer<F>(
        &self,
        id: PreviewId,
        closer: F,
    ) -> Result<(), PreviewError>
    where
        F: FnOnce(&PreviewSessionSnapshot) -> Result<NativeCloseOutcome, PreviewError>,
    {
        self.unowned_recovery.begin_created_rollback(id)?;
        // open 失败后已无 renderer owner 消费 load event，因此即使原生 close 需要稍后重试，
        // 也必须取消 timer。
        let _ = self.load_watchdog.cancel(id);
        let result = self.recover_claimed_identity(id, closer);
        if let Err(error) = result {
            self.unowned_recovery.release(id)?;
            return Err(error);
        }
        Ok(())
    }

    /// 关闭并 finalizes 已由首次 rollback 或有界显式 recovery pass 领取的 identity。
    fn recover_claimed_identity<F>(
        &self,
        id: PreviewId,
        closer: F,
    ) -> Result<NativeCloseOutcome, PreviewError>
    where
        F: FnOnce(&PreviewSessionSnapshot) -> Result<NativeCloseOutcome, PreviewError>,
    {
        let ticket = self.manager.prepare_close(id)?;
        self.close_ticket_with(ticket, closer)
            .map(|(_, outcome)| outcome)
    }

    /// 只恢复内部标记且无 renderer owner 的 identity，并返回脱敏计数；
    /// 有 owner 的 Preview session 绝不隐式清扫。
    pub(crate) fn recover_pending_with_closer<F>(
        &self,
        mut closer: F,
    ) -> Result<PreviewRecoveryReport, PreviewError>
    where
        F: FnMut(&PreviewSessionSnapshot) -> Result<NativeCloseOutcome, PreviewError>,
    {
        let identities = self.unowned_recovery.claim_pending()?;
        let mut report = PreviewRecoveryReport {
            observed: identities.len(),
            recovered: 0,
            failed: 0,
            pending: identities.len(),
        };
        for id in identities {
            match self.recover_claimed_identity(id, &mut closer) {
                Ok(_) => {
                    report.recovered = report.recovered.saturating_add(1);
                }
                Err(_) => {
                    self.unowned_recovery.release(id)?;
                    report.failed = report.failed.saturating_add(1);
                }
            }
        }
        report.pending = self.unowned_recovery.unresolved_count()?;
        Ok(report)
    }

    /// 与生产代码共用 shutdown 状态机测试，只注入窄原生 close acknowledgement 边界。
    pub(crate) fn shutdown_with_closer_until<F>(
        &self,
        deadline: Instant,
        mut closer: F,
    ) -> Result<PreviewShutdownReport, PreviewError>
    where
        F: FnMut(&PreviewSessionSnapshot) -> Result<NativeCloseOutcome, PreviewError>,
    {
        let drain = self.begin_shutdown_until(deadline)?;
        let snapshots = self.manager.pending_snapshots()?;
        let mut report = PreviewShutdownReport {
            shutdown_started: true,
            in_flight_operations: drain.in_flight,
            sessions_observed: snapshots.len(),
            close_acknowledged: 0,
            already_absent: 0,
            close_failed: 0,
            pending_sessions: snapshots.len(),
            deadline_exceeded: !drain.drained,
            complete: false,
        };
        if !drain.drained {
            return Ok(report);
        }
        for snapshot in snapshots {
            if Instant::now() >= deadline {
                report.deadline_exceeded = true;
                break;
            }
            let ticket = match self.manager.prepare_close(snapshot.id) {
                Ok(ticket) => ticket,
                Err(_) => {
                    report.close_failed = report.close_failed.saturating_add(1);
                    continue;
                }
            };
            match self.close_ticket_with(ticket, &mut closer) {
                Ok((_, outcome)) => match outcome {
                    NativeCloseOutcome::Acknowledged => {
                        report.close_acknowledged = report.close_acknowledged.saturating_add(1);
                    }
                    NativeCloseOutcome::AlreadyAbsent => {
                        report.already_absent = report.already_absent.saturating_add(1);
                    }
                },
                Err(_) => {
                    report.close_failed = report.close_failed.saturating_add(1);
                }
            }
        }
        report.pending_sessions = self.manager.active_count()?;
        let pending_recovery = self.unowned_recovery.unresolved_count()?;
        report.complete = report.in_flight_operations == 0
            && report.pending_sessions == 0
            && pending_recovery == 0
            && report.close_failed == 0
            && !report.deadline_exceeded;
        Ok(report)
    }
}

/// 在隔离子 WebView 中打开一个已验证 HTTP(S) URL。
#[tauri::command]
pub async fn ja_preview_open(
    input: PreviewUrlInput,
    app: tauri::AppHandle,
    state: tauri::State<'_, PreviewCommandHost>,
) -> Result<super::model::PreviewOpenResult, PreviewError> {
    let _operation = state.enter_operation()?;
    input.viewport.validate_open()?;
    let opened = state.manager.open(&input.url)?;
    let parsed = match Url::parse(opened.window.url().as_str()) {
        Ok(parsed) => parsed,
        Err(_) => {
            state.manager.finalize_absent(opened.snapshot.id)?;
            return Err(PreviewError::new(PreviewErrorCode::UrlInvalid));
        }
    };
    let id = opened.snapshot.id;
    let generation = Arc::new(AtomicU64::new(opened.snapshot.generation));
    let callback_manager = state.manager.clone();
    let callback_app = app.clone();
    let callback_generation = generation.clone();
    let callback_watchdog = state.load_watchdog.clone();
    let label = opened.window.label().to_owned();
    let title_manager = state.manager.clone();
    let title_app = app.clone();
    let title_generation = generation.clone();
    let load_manager = state.manager.clone();
    let load_app = app.clone();
    let load_generation = generation.clone();
    let load_watchdog = state.load_watchdog.clone();
    let webview_builder = WebviewBuilder::new(&label, WebviewUrl::External(parsed))
        // Preview 是外部页面，因此 navigation 只能通过校验初始地址的同一 URL policy 准入。
        .on_navigation(move |url| {
            let current = callback_generation.load(Ordering::Acquire);
            match callback_manager.callback_navigation(id, current, url.as_str()) {
                Ok(event) => {
                    let _ = callback_watchdog.cancel(id);
                    let committed_generation = event.generation;
                    callback_generation.store(committed_generation, Ordering::Release);
                    emit_preview_event(&callback_app, event);
                    if let Err(error) = arm_preview_load_timeout(
                        &callback_watchdog,
                        &callback_manager,
                        &callback_app,
                        id,
                        committed_generation,
                    ) {
                        tracing::debug!(
                            code = ?error.code(),
                            "preview navigation watchdog could not be armed"
                        );
                    }
                    true
                }
                Err(error) => {
                    if is_navigation_policy_error(error.code()) {
                        emit_preview_load_failure(
                            &callback_manager,
                            &callback_watchdog,
                            &callback_app,
                            id,
                            current,
                            PREVIEW_NAVIGATION_BLOCKED_MESSAGE,
                        );
                    }
                    false
                }
            }
        })
        // popup 不得继承 opener 或静默创建特权窗口；用户只能通过显式 Preview command 打开其他 URL。
        .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny)
        // Preview 永不将下载写入用户文件系统。
        .on_download(|_, _| false)
        .on_document_title_changed(move |_window, title| {
            let current = title_generation.load(Ordering::Acquire);
            if let Ok(event) = title_manager.callback_title(id, current, &title) {
                emit_preview_event(&title_app, event);
            }
        })
        // 当前锁定的 Tauri/Wry 版本不公开 WebView2 `IsSuccess`；
        // 因此 Finished 只确认收到 engine 终态 callback。
        .on_page_load(move |_webview, payload| {
            let current = load_generation.load(Ordering::Acquire);
            match payload.event() {
                PageLoadEvent::Started => {
                    if let Err(error) = arm_preview_load_timeout(
                        &load_watchdog,
                        &load_manager,
                        &load_app,
                        id,
                        current,
                    ) {
                        tracing::debug!(
                            code = ?error.code(),
                            "preview load watchdog could not be armed"
                        );
                    }
                }
                PageLoadEvent::Finished => {
                    #[cfg(not(windows))]
                    match load_manager.callback_load_finished(id, current, payload.url().as_str()) {
                        Ok(Some(event)) => {
                            if let Err(error) = load_watchdog.complete(id, current) {
                                tracing::debug!(
                                    code = ?error.code(),
                                    "preview load completion could not reconcile watchdog"
                                );
                            }
                            emit_preview_event(&load_app, event);
                        }
                        // 不同或内部 URL 可能是已取消 redirect 的 completion，也可能是 engine error page；
                        // 保持 watchdog 激活可避免把任一情况误报为成功。
                        Ok(None) => {}
                        Err(error) => {
                            tracing::debug!(
                                code = ?error.code(),
                                "preview load completion state was unavailable"
                            );
                        }
                    }
                    #[cfg(windows)]
                    {
                        // Wry 丢弃 `IsSuccess`，因此 WebView2 raw `NavigationCompleted` callback
                        // 必须同时承载成功与失败。
                        let _ = (current, payload);
                    }
                }
            }
        });
    let parent = app
        .get_window("main")
        .ok_or(PreviewError::new(PreviewErrorCode::DependencyRequest));
    let parent = match parent {
        Ok(parent) => parent,
        Err(error) => {
            let _ = state.manager.finalize_absent(id);
            return Err(error);
        }
    };
    if let Err(error) = arm_preview_load_timeout(
        &state.load_watchdog,
        &state.manager,
        &app,
        id,
        opened.snapshot.generation,
    ) {
        let _ = state.manager.finalize_absent(id);
        return Err(error);
    }
    let webview_result = parent.add_child(
        webview_builder,
        input.viewport.logical_position(),
        input.viewport.logical_size(),
    );
    // 原生 build 失败不得留下没有窗口 owner 的模型 session；在此关闭可保证
    // active-session limit 反映真实资源。
    let webview = match webview_result {
        Ok(webview) => webview,
        Err(_) => {
            let _ = state.load_watchdog.cancel(id);
            let _ = state.manager.finalize_absent(id);
            return Err(PreviewError::new(PreviewErrorCode::DependencyRequest));
        }
    };

    #[cfg(windows)]
    {
        if install_preview_navigation_completed(
            webview.clone(),
            app.clone(),
            state.manager.clone(),
            state.load_watchdog.clone(),
            id,
            generation.clone(),
        )
        .await
        .is_err()
        {
            state.rollback_created_webview(&app, id)?;
            return Err(PreviewError::new(PreviewErrorCode::DependencyRequest));
        }
        let Some(shortcuts) = app.try_state::<NativeShortcutHost>() else {
            state.rollback_created_webview(&app, id)?;
            return Err(PreviewError::new(PreviewErrorCode::DependencyRequest));
        };
        if install_preview_webview(webview.clone(), app.clone(), shortcuts.inner().clone())
            .await
            .is_err()
        {
            state.rollback_created_webview(&app, id)?;
            return Err(PreviewError::new(PreviewErrorCode::DependencyRequest));
        }
    }

    // React owner 在 project/unmount cleanup 时显式关闭子窗口；应用 shutdown
    // 则原子销毁 parent 与全部 children。
    if webview.show().is_err() {
        state.rollback_created_webview(&app, id)?;
        return Err(PreviewError::new(PreviewErrorCode::DependencyRequest));
    }
    // `add_child` 可能同步调用 navigation callback；返回 fresh snapshot 可防止 UI
    // 用旧 generation 覆盖 callback 已提交的新状态。
    match state.manager.authoritative_open_result(id) {
        Ok(authoritative) => Ok(authoritative),
        Err(error) => {
            state.rollback_created_webview(&app, id)?;
            Err(error)
        }
    }
}

/// policy 校验后导航已有 Preview；WebView callback 提交权威 generation 并发出结果事件。
#[tauri::command]
pub fn ja_preview_navigate(
    input: PreviewNavigateInput,
    app: tauri::AppHandle,
    state: tauri::State<'_, PreviewCommandHost>,
) -> Result<super::model::PreviewSessionSnapshot, PreviewError> {
    let _operation = state.enter_operation()?;
    let request = state.manager.navigation_request(
        input.session_id,
        input.generation,
        input.source,
        &input.url,
    )?;
    let label = state
        .manager
        .snapshot(input.session_id)?
        .window
        .label()
        .to_owned();
    let webview = app
        .get_webview(&label)
        .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
    let url = Url::parse(request.url.as_str())
        .map_err(|_| PreviewError::new(PreviewErrorCode::UrlInvalid))?;
    arm_preview_load_timeout(
        &state.load_watchdog,
        &state.manager,
        &app,
        input.session_id,
        input.generation,
    )?;
    if webview.navigate(url).is_err() {
        emit_preview_load_failure(
            &state.manager,
            &state.load_watchdog,
            &app,
            input.session_id,
            input.generation,
            PREVIEW_NAVIGATION_FAILED_MESSAGE,
        );
        return Err(PreviewError::new(PreviewErrorCode::DependencyRequest));
    }
    state.manager.snapshot(input.session_id)
}

/// 更新已有子 WebView 的 logical bounds，或在其 Tab inactive 时隐藏，
/// 防止原生内容覆盖相邻面板。
#[tauri::command]
pub fn ja_preview_layout(
    input: PreviewLayoutInput,
    app: tauri::AppHandle,
    state: tauri::State<'_, PreviewCommandHost>,
) -> Result<super::model::PreviewSessionSnapshot, PreviewError> {
    let _operation = state.enter_operation()?;
    input.viewport.validate()?;
    let snapshot = state.manager.snapshot(input.session_id)?;
    let webview = app
        .get_webview(snapshot.window.label())
        .ok_or(PreviewError::new(PreviewErrorCode::SessionNotFound))?;
    if input.viewport.visible {
        input.viewport.validate_open()?;
        webview
            .set_bounds(input.viewport.logical_rect())
            .and_then(|_| webview.show())
            .map_err(|_| PreviewError::new(PreviewErrorCode::DependencyRequest))?;
    } else {
        webview
            .hide()
            .map_err(|_| PreviewError::new(PreviewErrorCode::DependencyRequest))?;
    }
    Ok(snapshot)
}

/// 领取模型 identity，等待原生 close acknowledgement，再删除 session 及其 event queue；
/// 失败时释放 claim 供重试。
#[tauri::command]
pub fn ja_preview_close(
    input: PreviewSessionInput,
    app: tauri::AppHandle,
    state: tauri::State<'_, PreviewCommandHost>,
) -> Result<super::model::PreviewSessionSnapshot, PreviewError> {
    let _operation = state.enter_operation()?;
    let ticket = state.manager.prepare_close(input.session_id)?;
    state
        .close_ticket_with(ticket, |snapshot| close_native_preview(&app, snapshot))
        .map(|(snapshot, _)| snapshot)
}

/// 只重试已经原生创建、但在不透明 session identity 返回 renderer 前失败的子 WebView。
#[tauri::command]
pub fn ja_preview_recover_pending(
    app: tauri::AppHandle,
    state: tauri::State<'_, PreviewCommandHost>,
) -> Result<PreviewRecoveryReport, PreviewError> {
    let _operation = state.enter_operation()?;
    state.recover_pending_with_closer(|snapshot| close_native_preview(&app, snapshot))
}

/// 返回 reload/reconnect 投影所需的有界事件批次。
#[tauri::command]
pub fn ja_preview_events(
    input: PreviewEventsInput,
    state: tauri::State<'_, PreviewCommandHost>,
) -> Result<Vec<PreviewEvent>, PreviewError> {
    let _operation = state.enter_operation()?;
    state
        .manager
        .drain_events(input.session_id, input.max_events)
}

/// 返回当前权威 Preview snapshot，调用方不得从本地事件猜测。
#[tauri::command]
pub fn ja_preview_state(
    input: PreviewSessionInput,
    state: tauri::State<'_, PreviewCommandHost>,
) -> Result<super::model::PreviewSessionSnapshot, PreviewError> {
    let _operation = state.enter_operation()?;
    state.manager.snapshot(input.session_id)
}

/// 构建任何 WebView 前由模型重新校验 raw URL 输入。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewUrlInput {
    pub url: String,
    pub viewport: PreviewViewportInput,
}

/// 在主 WebView 内测得的 logical CSS-pixel rectangle。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewViewportInput {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
    pub visible: bool,
}

impl PreviewViewportInput {
    /// 数值到达平台窗口 API 前拒绝 NaN、infinity、负坐标和不合理的大 rectangle。
    pub(crate) fn validate(self) -> Result<(), PreviewError> {
        let values = [self.x, self.y, self.width, self.height];
        if values.iter().any(|value| !value.is_finite())
            || self.x < 0.0
            || self.y < 0.0
            || self.width < 0.0
            || self.height < 0.0
            || values.iter().any(|value| *value > 100_000.0)
        {
            return Err(PreviewError::new(PreviewErrorCode::ViewportInvalid));
        }
        Ok(())
    }

    /// 已打开或可见 WebView 需要非零 viewport；hidden layout 可从 inactive Tab
    /// 合法报告零尺寸。
    pub(crate) fn validate_open(self) -> Result<(), PreviewError> {
        self.validate()?;
        if !self.visible || self.width < 1.0 || self.height < 1.0 {
            return Err(PreviewError::new(PreviewErrorCode::ViewportInvalid));
        }
        Ok(())
    }

    /// 将浏览器 CSS 坐标转换为 Tauri logical coordinate，使 Windows display scaling
    /// 只由原生 Runtime 应用一次。
    fn logical_position(self) -> LogicalPosition<f64> {
        LogicalPosition::new(self.x, self.y)
    }

    /// 保留小数 CSS size，直到 Tauri 按活动 DPI 转换。
    fn logical_size(self) -> LogicalSize<f64> {
        LogicalSize::new(self.width, self.height)
    }

    /// 原子更新 position 与 size，避免 inspector splitter 移动时出现可见中间 frame。
    fn logical_rect(self) -> Rect {
        Rect {
            position: self.logical_position().into(),
            size: self.logical_size().into(),
        }
    }
}

/// 标识子 WebView 及其最近测得的 inspector rectangle。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewLayoutInput {
    pub session_id: PreviewId,
    pub viewport: PreviewViewportInput,
}

/// 标识 Preview session，但不暴露原生窗口 handle。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewSessionInput {
    pub session_id: PreviewId,
}

/// 通过唯一 URL/generation policy 路径导航 session。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewNavigateInput {
    pub session_id: PreviewId,
    pub generation: u64,
    pub source: NavigationSource,
    pub url: String,
}

/// 即使陈旧 UI 请求巨大批次，单次 drain 仍受固定上限约束。
#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PreviewEventsInput {
    pub session_id: PreviewId,
    #[serde(default = "default_event_limit")]
    pub max_events: usize,
}

/// 默认只回放 128 个事件，使未显式给出上限的 UI 请求也受固定内存边界约束；该值与
/// generation 无关，避免 stale session 通过重复 drain 放大队列占用。
fn default_event_limit() -> usize {
    128
}

/// 为单个 session/generation 启动 timeout；watchdog 必须绑定 generation，避免旧导航的
/// timeout 关闭新页面。callback 统一进入失败清理路径，只发送有界 `load_failed` 事件和
/// 固定非诊断消息，确保超时、显式失败与窗口关闭遵循同一取消和脱敏策略。
fn arm_preview_load_timeout(
    watchdog: &PreviewLoadWatchdog,
    manager: &PreviewManager,
    app: &tauri::AppHandle,
    session_id: PreviewId,
    generation: u64,
) -> Result<(), PreviewError> {
    let timeout_watchdog = watchdog.clone();
    let timeout_manager = manager.clone();
    let timeout_app = app.clone();
    watchdog.arm(session_id, generation, PREVIEW_LOAD_TIMEOUT, move || {
        emit_preview_load_failure(
            &timeout_manager,
            &timeout_watchdog,
            &timeout_app,
            session_id,
            generation,
            PREVIEW_LOAD_TIMEOUT_MESSAGE,
        );
    })
}

/// 取消 watchdog，并在有界 replay queue 与 live event stream 同时记录脱敏故障；
/// stale/closed session 直接忽略。
fn emit_preview_load_failure(
    manager: &PreviewManager,
    watchdog: &PreviewLoadWatchdog,
    app: &tauri::AppHandle,
    session_id: PreviewId,
    generation: u64,
    message: &'static str,
) {
    let _ = watchdog.cancel(session_id);
    match manager.callback_load_error(session_id, generation, message) {
        Ok(event) => emit_preview_event(app, event),
        Err(error) => {
            tracing::debug!(
                code = ?error.code(),
                "preview load failure callback was stale or unavailable"
            );
        }
    }
}

#[cfg(windows)]
/// 在子 UI thread 安装单个 WebView2 completion observer，并且只等待 registration ACK。
/// handler 不捕获 controller/WebView，因此 controller destruction 仍是原生生命周期 owner，
/// 不形成 COM cycle。
async fn install_preview_navigation_completed(
    webview: tauri::Webview,
    app: tauri::AppHandle,
    manager: PreviewManager,
    watchdog: PreviewLoadWatchdog,
    session_id: PreviewId,
    generation: Arc<AtomicU64>,
) -> Result<(), PreviewError> {
    let (sender, receiver) = tokio::sync::oneshot::channel();
    webview
        .with_webview(move |platform_webview| {
            let result = (|| {
                let controller = platform_webview.controller();
                let native_webview: ICoreWebView2 = unsafe { controller.CoreWebView2() }
                    .map_err(|_| PreviewError::new(PreviewErrorCode::DependencyRequest))?;
                let handler = NavigationCompletedEventHandler::create(Box::new(move |_, args| {
                    let Some(args) = args else {
                        return Ok(());
                    };
                    let mut succeeded = BOOL::default();
                    if unsafe { args.IsSuccess(&mut succeeded) }.is_err() {
                        tracing::debug!("preview navigation completion status was unavailable");
                        return Ok(());
                    }
                    handle_preview_navigation_completed(
                        &manager,
                        &watchdog,
                        &app,
                        session_id,
                        generation.load(Ordering::Acquire),
                        succeeded.as_bool(),
                    );
                    Ok(())
                }));
                let mut _token = 0i64;
                unsafe { native_webview.add_NavigationCompleted(&handler, &mut _token) }
                    .map_err(|_| PreviewError::new(PreviewErrorCode::DependencyRequest))?;
                Ok(())
            })();
            let _ = sender.send(result);
        })
        .map_err(|_| PreviewError::new(PreviewErrorCode::DependencyRequest))?;
    match tokio::time::timeout(PREVIEW_NATIVE_HANDLER_INSTALL_TIMEOUT, receiver).await {
        Ok(Ok(result)) => result,
        Ok(Err(_)) | Err(_) => Err(PreviewError::new(PreviewErrorCode::DependencyRequest)),
    }
}

#[cfg(windows)]
/// 将原生 completion bit 映射到现有有界 model/event contract；成功时只读取权威模型 URL，
/// 失败时发送固定消息，绝不转发 WebView2 status、URL 或 engine 诊断。
fn handle_preview_navigation_completed(
    manager: &PreviewManager,
    watchdog: &PreviewLoadWatchdog,
    app: &tauri::AppHandle,
    session_id: PreviewId,
    generation: u64,
    succeeded: bool,
) {
    if !succeeded {
        emit_preview_load_failure(
            manager,
            watchdog,
            app,
            session_id,
            generation,
            PREVIEW_NAVIGATION_FAILED_MESSAGE,
        );
        return;
    }
    let snapshot = match manager.snapshot(session_id) {
        Ok(snapshot) if snapshot.generation == generation => snapshot,
        _ => return,
    };
    match manager.callback_load_finished(session_id, generation, snapshot.url.as_str()) {
        Ok(Some(event)) => {
            let _ = watchdog.complete(session_id, generation);
            emit_preview_event(app, event);
        }
        Ok(None) => {}
        Err(error) => {
            tracing::debug!(
                code = ?error.code(),
                "preview native completion state was unavailable"
            );
        }
    }
}

/// 只把 URL-policy 拒绝类别报告为 load failure；stale、closed 与内部状态 callback
/// 不得投影为页面错误。
pub(crate) fn is_navigation_policy_error(code: PreviewErrorCode) -> bool {
    matches!(
        code,
        PreviewErrorCode::UrlTooLong
            | PreviewErrorCode::UrlControlCharacter
            | PreviewErrorCode::UrlInvalid
            | PreviewErrorCode::SchemeNotAllowed
            | PreviewErrorCode::HostMissing
            | PreviewErrorCode::UserInfoNotAllowed
            | PreviewErrorCode::PercentEscapeInvalid
            | PreviewErrorCode::NavigationBlocked
    )
}

/// 只发出 typed 有界模型事件；故障不向触发页面暴露 WebView 诊断。
fn emit_preview_event(app: &tauri::AppHandle, event: PreviewEvent) {
    if let Err(error) = app.emit(PREVIEW_EVENT, event) {
        tracing::debug!(error = %error, "preview event delivery failed");
    }
}

// 测试作为子模块保留对本模块私有状态的访问权，但测试体独立存放，避免生产实现与测试逻辑混杂。
