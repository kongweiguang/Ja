// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::collections::VecDeque;
use std::fmt;
use std::io;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, MutexGuard, mpsc};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crossterm::event::{self, Event};
use crossterm::terminal;
use ratatui::Terminal;

#[path = "terminal_guard.rs"]
mod terminal_guard;

use self::terminal_guard::{HandoffInputBoundary, TerminalGuard, TerminalUpdateGuard};
use super::model::{UiAction, UiEvent, UiSnapshot};
use super::native_backend::NativeBackend;
use super::render::{WORKING_INDICATOR_INTERVAL, render};
use super::state::UiState;
use super::windows_key_sequence::WindowsKeySequence;

const EVENT_QUEUE_CAPACITY: usize = 32;
const EVENT_DRAIN_LIMIT: usize = 64;
const POLL_INTERVAL: Duration = Duration::from_millis(8);
const MIN_FRAME_INTERVAL: Duration = Duration::from_millis(16);
const STARTUP_TIMEOUT: Duration = Duration::from_secs(5);
/// 外部编辑器的模式交接有超时边界，避免 controller 无限等待已退出的终端线程。
const TERMINAL_CONTROL_TIMEOUT: Duration = Duration::from_secs(5);

enum TerminalControl {
    Suspend(mpsc::SyncSender<Result<(), String>>),
    Resume(mpsc::SyncSender<Result<(), String>>),
}
/// 队列接纳结果允许 controller 识别合并和重同步，不阻塞 RPC 通知读取线程。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum EventDelivery {
    Queued,
    Coalesced,
    SnapshotRequired,
}

#[derive(Default)]
struct QueueState {
    events: VecDeque<UiEvent>,
    resync_needed: bool,
    snapshot_requested: bool,
    closed: bool,
}

/// 有界非阻塞事件入口；过载时要求 controller 重读权威快照，避免卡住协议 pump。
#[derive(Clone, Default)]
pub struct UiEventSender {
    state: Arc<Mutex<QueueState>>,
}

impl UiEventSender {
    /// 立即排队或合并一条投影，不等待终端线程；不可恢复的过载由返回值显式报告。
    pub fn send(&self, event: UiEvent) -> Result<EventDelivery, TuiError> {
        let mut state = lock_queue(&self.state);
        if state.closed {
            return Err(TuiError::EventQueueClosed);
        }
        if matches!(&event, UiEvent::Shutdown) {
            state.events.clear();
            state.events.push_back(event);
            state.closed = true;
            return Ok(EventDelivery::Queued);
        }
        if matches!(&event, UiEvent::ReplaceSnapshot(_)) {
            let critical = state
                .events
                .iter()
                .filter(|queued| is_critical_event(queued))
                .cloned()
                .collect::<Vec<_>>();
            if critical.len() + 1 > EVENT_QUEUE_CAPACITY {
                return Err(TuiError::EventQueueFull);
            }
            state.events.clear();
            state.events.push_back(event);
            state.events.extend(critical);
            state.resync_needed = false;
            state.snapshot_requested = false;
            return Ok(EventDelivery::Queued);
        }
        if (state.resync_needed || state.snapshot_requested) && !is_critical_event(&event) {
            return Ok(EventDelivery::SnapshotRequired);
        }
        if coalesce_event(&mut state.events, &event) {
            return Ok(if state.resync_needed {
                EventDelivery::SnapshotRequired
            } else {
                EventDelivery::Coalesced
            });
        }
        if state.events.len() >= EVENT_QUEUE_CAPACITY {
            state.resync_needed = true;
            if is_critical_event(&event) {
                let Some(index) = state
                    .events
                    .iter()
                    .position(|queued| !is_critical_event(queued))
                else {
                    return Err(TuiError::EventQueueFull);
                };
                state.events.remove(index);
                state.events.push_back(event);
            }
            return Ok(EventDelivery::SnapshotRequired);
        }
        state.events.push_back(event);
        Ok(if state.resync_needed {
            EventDelivery::SnapshotRequired
        } else {
            EventDelivery::Queued
        })
    }

    /// 暴露有限队列长度用于压力测试和可观测性，不提供跳过预算的容量修改接口。
    pub fn buffered_len(&self) -> usize {
        lock_queue(&self.state).events.len()
    }

    /// UI 消费端批量取出有限消息，单帧不会被持续通知洪峰饿死。
    fn drain(&self) -> Vec<UiEvent> {
        let mut state = lock_queue(&self.state);
        let count = state.events.len().min(EVENT_DRAIN_LIMIT);
        state.events.drain(..count).collect()
    }

    /// 取走一次溢出恢复动作；自建消费循环也可据此读取权威快照。
    pub fn take_snapshot_refresh_action(&self) -> Option<UiAction> {
        let mut state = lock_queue(&self.state);
        if state.resync_needed && !state.snapshot_requested {
            state.snapshot_requested = true;
            return Some(UiAction::RefreshSnapshot);
        }
        None
    }
}

/// 锁状态损坏后继续使用最后一份有界队列，避免异常退出时阻塞终端清理。
fn lock_queue(state: &Mutex<QueueState>) -> MutexGuard<'_, QueueState> {
    state
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// 投影只覆盖同一实体；摘要与正文使用不同流身份，增量只合并相邻同一条目以保持顺序。
fn coalesce_event(events: &mut VecDeque<UiEvent>, incoming: &UiEvent) -> bool {
    if let Some(UiEvent::AppendStreamDelta { entry_id, delta }) = events.back_mut()
        && let UiEvent::AppendStreamDelta {
            entry_id: incoming_id,
            delta: incoming_delta,
        } = incoming
        && entry_id == incoming_id
        && delta.len().saturating_add(incoming_delta.len()) <= 16 * 1024
    {
        delta.push_str(incoming_delta);
        return true;
    }
    if let Some(index) = events
        .iter()
        .rposition(|queued| same_projection_key(queued, incoming))
    {
        events[index] = incoming.clone();
        return true;
    }
    false
}

/// 比较可安全覆盖的展示键，保留 thread/event 的逻辑顺序边界。
fn same_projection_key(left: &UiEvent, right: &UiEvent) -> bool {
    match (left, right) {
        (UiEvent::UpsertEntry(left), UiEvent::UpsertEntry(right)) => left.id == right.id,
        (UiEvent::SetTurnState(_), UiEvent::SetTurnState(_))
        | (UiEvent::SetLocalOperation(_), UiEvent::SetLocalOperation(_))
        | (UiEvent::SetContinuationAvailable(_), UiEvent::SetContinuationAvailable(_))
        | (UiEvent::SetHasOlderHistory(_), UiEvent::SetHasOlderHistory(_))
        | (UiEvent::SetModel { .. }, UiEvent::SetModel { .. })
        | (UiEvent::SetPermission(_), UiEvent::SetPermission(_))
        | (UiEvent::SetPendingPrompt(_), UiEvent::SetPendingPrompt(_))
        | (UiEvent::SetModelChoices(_), UiEvent::SetModelChoices(_))
        | (UiEvent::SetPermissionChoices(_), UiEvent::SetPermissionChoices(_))
        | (UiEvent::SetThreadChoices(_), UiEvent::SetThreadChoices(_))
        | (UiEvent::SetAttachments(_), UiEvent::SetAttachments(_))
        | (UiEvent::SetNotice(_), UiEvent::SetNotice(_))
        | (UiEvent::SetWorkActivity(_), UiEvent::SetWorkActivity(_)) => true,
        (
            UiEvent::FinishAssistantStream { turn_id: left },
            UiEvent::FinishAssistantStream { turn_id: right },
        ) => left == right,
        (
            UiEvent::SetFileChoices { query_id: left, .. },
            UiEvent::SetFileChoices {
                query_id: right, ..
            },
        )
        | (
            UiEvent::SetSkillChoices { query_id: left, .. },
            UiEvent::SetSkillChoices {
                query_id: right, ..
            },
        ) => left == right,
        _ => false,
    }
}

/// 异步详情正文与复制就绪结果必须跨快照替换和队列溢出保留；迟到结果仍由 reducer 核验身份。
fn is_critical_event(event: &UiEvent) -> bool {
    matches!(
        event,
        UiEvent::Shutdown
            | UiEvent::SetLocalOperation(_)
            | UiEvent::InsertFileReference { .. }
            | UiEvent::InsertSkillReference { .. }
            | UiEvent::ClearDraft
            | UiEvent::RestoreDraft(_)
            | UiEvent::RestoreForkDraft
            | UiEvent::ClearForkDraft
            | UiEvent::ReplaceDraft(_)
            | UiEvent::ReleaseDraft
            | UiEvent::ReleasePromptSubmission
            | UiEvent::ShowDetails { .. }
            | UiEvent::UpdateDetailsIfOpen { .. }
            | UiEvent::SetDetailsCopyReady { .. }
            | UiEvent::OpenModelSettings { .. }
            | UiEvent::ModelSettingsResult(_)
            | UiEvent::ModelSettingsTrustRequired { .. }
            | UiEvent::OlderHistoryLoaded { .. }
            | UiEvent::OlderHistoryFailed { .. }
            | UiEvent::TranscriptSelectionCopied { .. }
            | UiEvent::TranscriptSelectionCopyFailed { .. }
    )
}

/// 专属终端线程持有终端 I/O；controller 可投递事件并单独读取用户动作。
pub struct TuiBridge {
    events: UiEventSender,
    actions: mpsc::Receiver<UiAction>,
    terminal_control: mpsc::Sender<TerminalControl>,
    thread: Option<JoinHandle<Result<(), TuiError>>>,
}

impl TuiBridge {
    /// 终端初始化成功后才返回；5 秒内无法接管时请求退出且不遗留 raw mode。
    pub fn spawn(snapshot: UiSnapshot) -> Result<Self, TuiError> {
        let events = UiEventSender::default();
        let worker_events = events.clone();
        let (action_tx, action_rx) = mpsc::channel();
        let (terminal_control_tx, terminal_control_rx) = mpsc::channel();
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let thread = thread::Builder::new()
            .name("ja-tui".to_owned())
            .spawn(move || {
                match catch_unwind(AssertUnwindSafe(|| {
                    terminal_loop(
                        snapshot,
                        worker_events,
                        action_tx,
                        terminal_control_rx,
                        ready_tx,
                    )
                })) {
                    Ok(result) => result,
                    Err(_) => Err(TuiError::ThreadPanicked),
                }
            })
            .map_err(TuiError::Io)?;
        match ready_rx.recv_timeout(STARTUP_TIMEOUT) {
            Ok(Ok(())) => Ok(Self {
                events,
                actions: action_rx,
                terminal_control: terminal_control_tx,
                thread: Some(thread),
            }),
            Ok(Err(message)) => {
                let _ = thread.join();
                Err(TuiError::Startup(message))
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                let _ = events.send(UiEvent::Shutdown);
                Err(TuiError::StartupTimeout)
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                let _ = thread.join();
                Err(TuiError::ThreadPanicked)
            }
        }
    }

    /// controller 使用有界非阻塞 sender，队列过载不会卡住 JA-RPC 消息泵。
    pub fn events(&self) -> &UiEventSender {
        &self.events
    }

    /// 克隆事件 sender 供 controller 的独立通知处理任务共享。
    pub fn event_sender(&self) -> UiEventSender {
        self.events.clone()
    }

    /// 阻塞读取单消费者动作，调用方应在自己的 controller 任务中执行。
    pub fn recv_action(&self) -> Result<UiAction, TuiError> {
        self.actions
            .recv()
            .map_err(|_| TuiError::ActionChannelClosed)
    }

    /// 非阻塞读取动作，便于接入已存在的 CLI 控制循环。
    pub fn try_recv_action(&self) -> Result<Option<UiAction>, TuiError> {
        match self.actions.try_recv() {
            Ok(action) => Ok(Some(action)),
            Err(mpsc::TryRecvError::Empty) => Ok(None),
            Err(mpsc::TryRecvError::Disconnected) => Err(TuiError::ActionChannelClosed),
        }
    }

    /// 外部编辑器启动前等待终端完全释放，避免子进程与 Ja 同时读取或切换屏幕模式。
    pub fn suspend_for_external_program(&self) -> Result<(), TuiError> {
        self.change_terminal_mode(true)
    }

    /// 子进程退出后等待全屏和输入模式重新建立，确保编辑器返回时保留原草稿继续绘制。
    pub fn resume_after_external_program(&self) -> Result<(), TuiError> {
        self.change_terminal_mode(false)
    }

    /// A bounded control handshake prevents launching a child against a still-owned terminal.
    fn change_terminal_mode(&self, suspend: bool) -> Result<(), TuiError> {
        let (reply_tx, reply_rx) = mpsc::sync_channel(1);
        let request = if suspend {
            TerminalControl::Suspend(reply_tx)
        } else {
            TerminalControl::Resume(reply_tx)
        };
        self.terminal_control
            .send(request)
            .map_err(|_| TuiError::TerminalControlClosed)?;
        match reply_rx.recv_timeout(TERMINAL_CONTROL_TIMEOUT) {
            Ok(Ok(())) => Ok(()),
            Ok(Err(message)) => Err(TuiError::TerminalControlFailed(message)),
            Err(mpsc::RecvTimeoutError::Timeout) => Err(TuiError::TerminalControlTimeout),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(TuiError::TerminalControlClosed),
        }
    }

    /// 显式退出并等待 guard 恢复终端模式，错误保留给调用方处理。
    pub fn shutdown(&mut self) -> Result<(), TuiError> {
        self.stop_and_join()
    }

    /// Drop 与显式关闭共享同一清理序列，已自然退出的 UI 不重复关闭。
    fn stop_and_join(&mut self) -> Result<(), TuiError> {
        if let Some(thread) = self.thread.take() {
            let _ = self.events.send(UiEvent::Shutdown);
            thread.join().map_err(|_| TuiError::ThreadPanicked)??;
        }
        Ok(())
    }
}

impl Drop for TuiBridge {
    /// 兜底恢复 raw mode，避免 controller 的提前返回把当前 shell 留在异常状态。
    fn drop(&mut self) {
        let _ = self.stop_and_join();
    }
}

/// 终端初始化、通道和 I/O 错误均可向上返回给 CLI controller。
#[derive(Debug)]
pub enum TuiError {
    Io(io::Error),
    Startup(String),
    StartupTimeout,
    EventQueueClosed,
    EventQueueFull,
    ActionChannelClosed,
    TerminalControlClosed,
    TerminalControlTimeout,
    TerminalControlFailed(String),
    ThreadPanicked,
}

impl fmt::Display for TuiError {
    /// 错误文案不携带草稿、路径或协议正文。
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "terminal I/O failed: {error}"),
            Self::Startup(message) => write!(formatter, "terminal startup failed: {message}"),
            Self::StartupTimeout => formatter.write_str("terminal startup timed out"),
            Self::EventQueueClosed => formatter.write_str("terminal event queue is closed"),
            Self::EventQueueFull => formatter.write_str("terminal critical event queue is full"),
            Self::ActionChannelClosed => formatter.write_str("terminal action channel closed"),
            Self::TerminalControlClosed => formatter.write_str("terminal control channel closed"),
            Self::TerminalControlTimeout => formatter.write_str("terminal control timed out"),
            Self::TerminalControlFailed(message) => {
                write!(formatter, "terminal control failed: {message}")
            }
            Self::ThreadPanicked => formatter.write_str("terminal thread panicked"),
        }
    }
}

impl std::error::Error for TuiError {
    /// 将底层 I/O 错误纳入错误链，其余错误是本地 TUI 状态。
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            _ => None,
        }
    }
}

/// 独占全屏终端绘制应用内 transcript；poll 不越过粘贴/CSI-u 截止，交接失败由 Drop 重试模式恢复。
/// @author kongweiguang
fn terminal_loop(
    snapshot: UiSnapshot,
    events: UiEventSender,
    action_tx: mpsc::Sender<UiAction>,
    terminal_control_rx: mpsc::Receiver<TerminalControl>,
    ready_tx: mpsc::SyncSender<Result<(), String>>,
) -> Result<(), TuiError> {
    let (columns, rows) = match terminal::size() {
        Ok(size) => size,
        Err(error) => {
            let _ = ready_tx.send(Err(error.to_string()));
            return Err(TuiError::Io(error));
        }
    };
    let mut state = UiState::new_for_terminal(snapshot, columns, rows.max(1));
    let setup = (|| {
        let guard = TerminalGuard::activate().map_err(TuiError::Io)?;
        // NativeBackend 只修正宽字符占位格输出；终端历史与视口现在完全由应用状态管理。
        let backend = NativeBackend::new(io::stdout());
        let mut terminal = Terminal::new(backend).map_err(TuiError::Io)?;
        terminal.clear().map_err(TuiError::Io)?;
        Ok::<_, TuiError>((terminal, guard))
    })();
    let (mut terminal, mut guard) = match setup {
        Ok(ready) => ready,
        Err(error) => {
            let _ = ready_tx.send(Err(error.to_string()));
            return Err(error);
        }
    };
    if ready_tx.send(Ok(())).is_err() {
        drop(guard);
        return Err(TuiError::StartupTimeout);
    }
    let mut dirty = true;
    let mut last_frame: Option<Instant> = None;
    let mut last_working_indicator_frame: Option<Instant> = None;
    let mut suspended = false;
    let mut windows_key_sequence = WindowsKeySequence::new();
    loop {
        while let Ok(control) = terminal_control_rx.try_recv() {
            let (result, next_suspended, reply) = match control {
                TerminalControl::Suspend(reply) => {
                    windows_key_sequence.reset();
                    state.reset_input_source_handoff();
                    let result = terminal
                        .clear()
                        .and_then(|()| guard.suspend_for_external_program());
                    if result.is_err() {
                        // 清理命令可能只写出前缀，不能把不确定模式上的重新启用当作安全恢复。
                        state.apply(UiEvent::Shutdown);
                    }
                    (result.map_err(|error| error.to_string()), true, reply)
                }
                TerminalControl::Resume(reply) => {
                    let result = guard
                        .resume_after_external_program()
                        .and_then(|()| drain_handoff_input(&mut state))
                        .and_then(|()| terminal.clear())
                        .and_then(|()| terminal.autoresize())
                        .map_err(|error| error.to_string());
                    windows_key_sequence.reset();
                    state.reset_input_source_handoff();
                    if result.is_err() {
                        // 挂起尝试尽力清理恢复到一半的模式；即使成功，也不能让已挂起的 UI 永久忽略输入。
                        let _ = guard.suspend_for_external_program();
                        state.apply(UiEvent::Shutdown);
                    }
                    (result, false, reply)
                }
            };
            let succeeded = result.is_ok();
            if succeeded {
                suspended = next_suspended;
                if !suspended {
                    dirty = true;
                }
            }
            let _ = reply.send(result);
            if state.is_closed() {
                break;
            }
        }
        if state.is_closed() {
            break;
        }
        for event in events.drain() {
            state.apply(event);
            dirty = true;
        }
        if let Some(action) = events.take_snapshot_refresh_action()
            && action_tx.send(action).is_err()
        {
            state.apply(UiEvent::Shutdown);
        }
        let now = Instant::now();
        #[cfg(windows)]
        for event in windows_key_sequence.flush_if_due(now) {
            dirty |= apply_terminal_event(event, &mut state, &action_tx);
        }
        if state.flush_paste_burst(now) {
            dirty = true;
        }
        if let Some(action) = state.take_due_input_history_search(now) {
            if action_tx.send(action).is_err() {
                state.apply(UiEvent::Shutdown);
            } else {
                dirty = true;
            }
        }
        if let Some(action) = state.take_due_file_search(now)
            && action_tx.send(action).is_err()
        {
            state.apply(UiEvent::Shutdown);
        }
        // 关闭后立即退出终端线程，避免再轮询并消费一条已无人处理的按键。
        if state.is_closed() {
            break;
        }
        if !suspended {
            let poll_now = Instant::now();
            let mut poll_timeout = POLL_INTERVAL;
            #[cfg(windows)]
            if let Some(remaining) = windows_key_sequence.remaining_timeout(poll_now) {
                poll_timeout = poll_timeout.min(remaining);
            }
            if let Some(deadline) = state.next_paste_burst_deadline() {
                poll_timeout = poll_timeout.min(deadline.saturating_duration_since(poll_now));
            }
            if event::poll(poll_timeout).map_err(TuiError::Io)? {
                let event = event::read().map_err(TuiError::Io)?;
                #[cfg(windows)]
                let ready_events = windows_key_sequence.push(event, Instant::now());
                #[cfg(not(windows))]
                let ready_events = [event].into_iter().collect::<Vec<_>>();
                for event in ready_events {
                    dirty |= apply_terminal_event(event, &mut state, &action_tx);
                }
            }
        }
        // 按键或鼠标动作可以同步关闭 UI；退出前跳过工作提示更新与最后一帧绘制。
        if state.is_closed() {
            break;
        }
        if suspended {
            thread::sleep(POLL_INTERVAL);
            continue;
        }
        let now = Instant::now();
        let working_indicator_visible = state.snapshot().turn_state
            == super::model::TurnState::Working
            && state.snapshot().pending_prompt.is_none();
        if working_indicator_visible {
            if last_working_indicator_frame.is_none_or(|previous| {
                now.saturating_duration_since(previous) >= WORKING_INDICATOR_INTERVAL
            }) {
                last_working_indicator_frame = Some(now);
                dirty = true;
            }
        } else {
            last_working_indicator_frame = None;
        }
        if dirty
            && last_frame.is_none_or(|previous| {
                now.saturating_duration_since(previous) >= MIN_FRAME_INTERVAL
            })
        {
            let update = TerminalUpdateGuard::begin(io::stdout()).map_err(TuiError::Io)?;
            terminal.autoresize().map_err(TuiError::Io)?;
            let area = terminal.get_frame().area();
            state.set_terminal_size(area.width, area.height.max(1));
            terminal
                .draw(|frame| render(frame, &state))
                .map_err(TuiError::Io)?;
            update.finish().map_err(TuiError::Io)?;
            last_frame = Some(now);
            dirty = false;
        }
    }
    drop(terminal);
    drop(guard);
    Ok(())
}

/// 将标准输入事件交给同一状态机；共用入口保证解码回放和实时输入行为一致。
fn apply_terminal_event(
    event: Event,
    state: &mut UiState,
    action_tx: &mpsc::Sender<UiAction>,
) -> bool {
    match event {
        Event::Key(key) => {
            for action in state.handle_key(key) {
                let quitting = action == UiAction::Quit;
                if action_tx.send(action).is_err() || quitting {
                    state.apply(UiEvent::Shutdown);
                }
            }
            true
        }
        Event::Paste(text) => {
            state.handle_paste(&text);
            true
        }
        Event::Mouse(mouse) => {
            for action in state.handle_mouse(mouse) {
                let quitting = action == UiAction::Quit;
                if action_tx.send(action).is_err() || quitting {
                    state.apply(UiEvent::Shutdown);
                }
            }
            true
        }
        Event::Resize(width, height) => {
            state.set_terminal_size(width, height.max(1));
            true
        }
        _ => false,
    }
}

/// 编辑器退出后只消费恢复前已排队的事件，保留窗口尺寸并等待50毫秒输入静默。
/// @author kongweiguang
fn drain_handoff_input(state: &mut UiState) -> io::Result<()> {
    let mut boundary = HandoffInputBoundary::new(Instant::now());
    loop {
        let now = Instant::now();
        if boundary.is_quiet(now, windows_console_input_queue_empty()?)?
            && !event::poll(Duration::ZERO)?
            && windows_console_input_queue_empty()?
        {
            return Ok(());
        }
        let timeout = boundary.poll_timeout(Instant::now(), POLL_INTERVAL)?;
        if event::poll(timeout)? {
            let event = event::read()?;
            if let Some((width, height)) = boundary.observe_event(&event, Instant::now())? {
                state.set_terminal_size(width, height.max(1));
            }
        }
    }
}

/// 交接静默确认同时查询 Win32 控制台队列；ConPTY/Crossterm事件层外的记录不能漏给UI。
#[cfg(windows)]
fn windows_console_input_queue_empty() -> io::Result<bool> {
    use windows::Win32::System::Console::{
        GetNumberOfConsoleInputEvents, GetStdHandle, STD_INPUT_HANDLE,
    };

    let input = unsafe { GetStdHandle(STD_INPUT_HANDLE) }.map_err(io::Error::other)?;
    let mut count = 0;
    unsafe { GetNumberOfConsoleInputEvents(input, &mut count) }.map_err(io::Error::other)?;
    Ok(count == 0)
}

/// 非Windows终端以 Crossterm 自身事件队列作为输入来源，不调用系统专属控制台 API。
#[cfg(not(windows))]
fn windows_console_input_queue_empty() -> io::Result<bool> {
    Ok(true)
}
