// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::collections::VecDeque;
use std::fmt;
use std::io;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, MutexGuard, mpsc};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

use crossterm::event::{self, DisableBracketedPaste, EnableBracketedPaste, Event};
use crossterm::execute;
use crossterm::terminal;
use ratatui::backend::CrosstermBackend;
use ratatui::{Terminal, TerminalOptions, Viewport};

use super::model::{UiAction, UiEvent, UiSnapshot};
use super::render::{WORKING_INDICATOR_INTERVAL, insert_scrollback, render};
use super::state::UiState;

const EVENT_QUEUE_CAPACITY: usize = 32;
const EVENT_DRAIN_LIMIT: usize = 64;
const POLL_INTERVAL: Duration = Duration::from_millis(16);
const MIN_FRAME_INTERVAL: Duration = Duration::from_millis(33);
const STARTUP_TIMEOUT: Duration = Duration::from_secs(5);
/// 留出历史行，同时容纳八个候选、选择器标题和 Composer。
const MAX_INLINE_VIEWPORT_HEIGHT: u16 = 16;

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

/// 只有无状态或同一实体的投影可以替换，文本增量只合并相邻同一条目。
fn coalesce_event(events: &mut VecDeque<UiEvent>, incoming: &UiEvent) -> bool {
    if let Some(UiEvent::AppendAssistantDelta { entry_id, delta }) = events.back_mut()
        && let UiEvent::AppendAssistantDelta {
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
        | (UiEvent::SetContinuationAvailable(_), UiEvent::SetContinuationAvailable(_))
        | (UiEvent::SetHasOlderHistory(_), UiEvent::SetHasOlderHistory(_))
        | (UiEvent::SetModel { .. }, UiEvent::SetModel { .. })
        | (UiEvent::SetPermission(_), UiEvent::SetPermission(_))
        | (UiEvent::SetPendingPrompt(_), UiEvent::SetPendingPrompt(_))
        | (UiEvent::SetModelChoices(_), UiEvent::SetModelChoices(_))
        | (UiEvent::SetPermissionChoices(_), UiEvent::SetPermissionChoices(_))
        | (UiEvent::SetThreadChoices(_), UiEvent::SetThreadChoices(_))
        | (UiEvent::SetAttachments(_), UiEvent::SetAttachments(_))
        | (UiEvent::SetNotice(_), UiEvent::SetNotice(_)) => true,
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

/// 草稿确认、文件引用和详情不能被普通展示更新覆盖。
fn is_critical_event(event: &UiEvent) -> bool {
    matches!(
        event,
        UiEvent::Shutdown
            | UiEvent::InsertFileReference { .. }
            | UiEvent::InsertSkillReference { .. }
            | UiEvent::ClearDraft
            | UiEvent::RestoreDraft(_)
            | UiEvent::ReleaseDraft
            | UiEvent::ReleasePromptSubmission
            | UiEvent::ShowDetails { .. }
    )
}

/// 专属终端线程持有终端 I/O；controller 可投递事件并单独读取用户动作。
pub struct TuiBridge {
    events: UiEventSender,
    actions: mpsc::Receiver<UiAction>,
    thread: Option<JoinHandle<Result<(), TuiError>>>,
}

impl TuiBridge {
    /// 终端初始化成功后才返回；5 秒内无法接管时请求退出且不遗留 raw mode。
    pub fn spawn(snapshot: UiSnapshot) -> Result<Self, TuiError> {
        let events = UiEventSender::default();
        let worker_events = events.clone();
        let (action_tx, action_rx) = mpsc::channel();
        let (ready_tx, ready_rx) = mpsc::sync_channel(1);
        let thread = thread::Builder::new()
            .name("ja-tui".to_owned())
            .spawn(move || {
                match catch_unwind(AssertUnwindSafe(|| {
                    terminal_loop(snapshot, worker_events, action_tx, ready_tx)
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

/// 终端 raw mode 和 bracketed paste 分开记录，初始化失败时只恢复已开启项。
struct TerminalGuard {
    raw_mode: bool,
    bracketed_paste: bool,
}

impl TerminalGuard {
    /// 先完成终端输出模式，再启用 raw mode；之后第一步即发送启动确认。
    fn activate() -> Result<Self, TuiError> {
        let mut guard = Self {
            raw_mode: false,
            bracketed_paste: false,
        };
        execute!(io::stdout(), EnableBracketedPaste).map_err(TuiError::Io)?;
        guard.bracketed_paste = true;
        guard.raw_mode = true;
        terminal::enable_raw_mode().map_err(TuiError::Io)?;
        Ok(guard)
    }
}

impl Drop for TerminalGuard {
    /// 每项恢复独立尝试；一次清理失败不跳过剩余 shell 状态恢复。
    fn drop(&mut self) {
        if self.bracketed_paste {
            let _ = execute!(io::stdout(), DisableBracketedPaste);
            self.bracketed_paste = false;
        }
        let _ = execute!(io::stdout(), crossterm::cursor::Show);
        if self.raw_mode {
            let _ = terminal::disable_raw_mode();
            self.raw_mode = false;
        }
    }
}

/// 终端只占用底部最多 16 行，让 insert_before 写入的稳定对话留在可见区域上方；
/// 活动回复按固定间隔重绘临时状态点，选择器和审批布局仍共享同一有界 viewport。
fn terminal_loop(
    snapshot: UiSnapshot,
    events: UiEventSender,
    action_tx: mpsc::Sender<UiAction>,
    ready_tx: mpsc::SyncSender<Result<(), String>>,
) -> Result<(), TuiError> {
    let (columns, rows) = match terminal::size() {
        Ok(size) => size,
        Err(error) => {
            let _ = ready_tx.send(Err(error.to_string()));
            return Err(TuiError::Io(error));
        }
    };
    let viewport_height = rows.saturating_sub(1).clamp(1, MAX_INLINE_VIEWPORT_HEIGHT);
    let mut state = UiState::new_for_terminal(snapshot, columns, viewport_height);
    let initial_scrollback = state.take_scrollback_entries();
    let setup = (|| {
        // Ratatui 的整屏 insert_before 路径会把宽字符后继占位 cell 输出为额外空格；
        // 留一行进入 scrolling-regions 的 diff 路径，稳定中文历史才不会变成“中 文”。
        let backend = CrosstermBackend::new(io::stdout());
        let options = TerminalOptions {
            viewport: Viewport::Inline(viewport_height),
        };
        let terminal = Terminal::with_options(backend, options).map_err(TuiError::Io)?;
        let guard = TerminalGuard::activate()?;
        Ok::<_, TuiError>((terminal, guard))
    })();
    let (mut terminal, guard) = match setup {
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
    insert_scrollback(&mut terminal, &initial_scrollback).map_err(TuiError::Io)?;
    let mut dirty = true;
    let mut last_frame: Option<Instant> = None;
    let mut last_working_indicator_frame: Option<Instant> = None;
    loop {
        for event in events.drain() {
            state.apply(event);
            dirty = true;
        }
        let stable_entries = state.take_scrollback_entries();
        if !stable_entries.is_empty() {
            insert_scrollback(&mut terminal, &stable_entries).map_err(TuiError::Io)?;
            dirty = true;
        }
        if let Some(action) = events.take_snapshot_refresh_action()
            && action_tx.send(action).is_err()
        {
            state.apply(UiEvent::Shutdown);
        }
        if let Some(action) = state.take_due_file_search(Instant::now())
            && action_tx.send(action).is_err()
        {
            state.apply(UiEvent::Shutdown);
        }
        if event::poll(POLL_INTERVAL).map_err(TuiError::Io)? {
            match event::read().map_err(TuiError::Io)? {
                Event::Key(key) => {
                    for action in state.handle_key(key) {
                        let quitting = action == UiAction::Quit;
                        if action_tx.send(action).is_err() || quitting {
                            state.apply(UiEvent::Shutdown);
                        }
                    }
                    dirty = true;
                }
                Event::Paste(text) => {
                    state.handle_paste(&text);
                    dirty = true;
                }
                Event::Resize(width, _) => {
                    state.set_terminal_width(width);
                    dirty = true;
                }
                _ => {}
            }
        }
        if state.is_closed() {
            break;
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
            terminal
                .draw(|frame| render(frame, &state))
                .map_err(TuiError::Io)?;
            last_frame = Some(now);
            dirty = false;
        }
    }
    drop(terminal);
    drop(guard);
    Ok(())
}
