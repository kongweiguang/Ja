// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::cell::RefCell;
use std::collections::{HashMap, HashSet, VecDeque};
use std::time::{Duration, Instant, SystemTime};

use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers, MouseEvent};
use sha2::{Digest, Sha256};
use unicode_segmentation::UnicodeSegmentation;

use super::composer::Composer;
use super::markdown_stream::{MarkdownStream, MarkdownStreamPiece};
use super::model::{
    InteractionAnswer, InteractionQuestion, InteractionQuestionKind,
    MESSAGE_PREVIEW_TRUNCATED_NOTICE, PendingPrompt, StreamPresentation, TimelineEntry,
    TimelineKind, TimelineStatus, TurnState, UiAction, UiChoice, UiCommand, UiEvent,
    UiReferenceKind, UiSnapshot, UiSubmitMode,
};
use super::model_settings::{ModelSettingsEffect, ModelSettingsOutcome, ModelSettingsState};
use super::paste_burst::{CharDecision, ControlDecision, FlushResult};
use super::transcript::Transcript;

const MAX_DETAIL_BYTES: usize = 96 * 1024;
const MAX_CHOICES: usize = 64;
const MODEL_SETTINGS_CHOICE_ID: &str = "ui:manage-models";
const MAX_CHOICE_DETAIL_BYTES: usize = 4 * 1024;
const MAX_INPUT_HISTORY_BYTES: usize = 32 * 1024;
const FILE_SEARCH_DEBOUNCE: Duration = Duration::from_millis(140);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum ChoiceKind {
    Models,
    Reasoning,
    Permissions,
    Threads,
    ForkReplies,
    InputHistory,
    Files,
    Skills,
    Attachments,
    ToolSteps,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ActiveFileQuery {
    start: usize,
    end: usize,
    query: String,
}

#[derive(Clone)]
struct StreamProgress {
    source_key: String,
    turn_id: String,
    kind: StreamKind,
    committed_bytes: usize,
    committed_hash: Sha256,
    first_fragment: bool,
    layout: MarkdownStream,
}

#[derive(Clone)]
struct StreamReceipt {
    turn_id: String,
    kind: StreamKind,
    byte_len: usize,
    digest: [u8; 32],
}

/// 公开摘要与正文共用接续账本，但同文不能跨类别互相去重。
#[derive(Clone, Copy, Eq, PartialEq)]
enum StreamKind {
    Assistant,
    Reasoning,
}

/// 多题回答共用同一份服务端 CAS 身份，避免按键分支重复排列参数时漏传 revision。
struct ClarificationContext {
    thread_id: String,
    interaction_id: String,
    expected_interaction_revision: u64,
    operation_key: String,
    question_count: usize,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) enum Panel {
    Commands {
        selected: usize,
    },
    Choices {
        kind: ChoiceKind,
        selected: usize,
    },
    ChoiceDetail {
        kind: ChoiceKind,
        choice_id: String,
        scroll: DetailScroll,
    },
    Pending,
    ModelSettings,
    Help {
        scroll: DetailScroll,
    },
    Details {
        entry_id: String,
        title: String,
        body: String,
        scroll: DetailScroll,
        return_to_pending: bool,
        copy_ready: bool,
    },
}

/// 详情的滚动锚点保留相对首尾的位置，终端缩放后仍能从末尾向上逐行浏览。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum DetailScroll {
    FromTop(usize),
    FromBottom(usize),
}

/// 保留完整发送缓冲与发送前最后的问题身份，权威问题先到时可去掉临时副本，且不误合并历史相同文本。
enum PendingSubmission {
    Message {
        composer: Box<Composer>,
        previous_user_id: Option<String>,
    },
    Attachment(String),
}

/// 取消等待与失败可重试共用一份状态；自动快照不能盖掉尚未处理的错误提示。
#[derive(Clone, Copy, Eq, PartialEq)]
enum CancellationState {
    Idle,
    Pending,
    Retry,
}

/// 状态 reducer 是展示事实的唯一写入口，恢复与副作用仍由 controller 所有。
pub struct UiState {
    snapshot: UiSnapshot,
    composer: Composer,
    fork_source_composers: HashMap<String, Composer>,
    active_fork_source: Option<String>,
    terminal_width: usize,
    terminal_height: usize,
    deferred_composer: Option<Composer>,
    panel: Option<Panel>,
    model_settings: Option<ModelSettingsState>,
    settings_return_panel: Option<Panel>,
    settings_start_pending: bool,
    question_index: usize,
    selected_prompt_choice: usize,
    selected_multiple: HashSet<String>,
    thread_query: String,
    input_history_query: String,
    input_history_texts: Vec<(String, String, bool)>,
    tool_choices: Vec<UiChoice>,
    tool_detail_return_id: Option<String>,
    interaction_answers: Vec<InteractionAnswer>,
    using_free_text: bool,
    prompt_responded: bool,
    pending_submission: Option<PendingSubmission>,
    suspended_control_pending: bool,
    continuation_pending: bool,
    cancellation: CancellationState,
    exit_confirmation: Option<Instant>,
    scheduled_file_search: Option<(String, u64, Instant)>,
    scheduled_input_history_search: Option<(String, Option<String>, Instant)>,
    query_sequence: u64,
    active_query_id: Option<u64>,
    reference_popup_dismissed: bool,
    committed_ids: HashSet<String>,
    pub(super) transcript: RefCell<Transcript>,
    older_page_loading: bool,
    older_page_failed: bool,
    work_activity: Option<String>,
    stream_progress: HashMap<String, StreamProgress>,
    stream_receipts: VecDeque<StreamReceipt>,
    welcome_visible: bool,
    next_stream_output_id: u64,
    closed: bool,
}

impl UiState {
    /// 使用 owner 的权威快照初始化；恢复会话列表选择无需凭空创建 thread。
    pub fn new(snapshot: UiSnapshot) -> Self {
        Self::new_for_terminal(snapshot, 80, 24)
    }

    /// 首次加载保留完整语义条目，已确定正文进入应用视口；流账本继续核对快照与增量。
    pub fn new_for_terminal(snapshot: UiSnapshot, width: u16, height: u16) -> Self {
        let snapshot = sanitize_snapshot(snapshot);
        let panel = if snapshot.pending_prompt.is_some() {
            Some(Panel::Pending)
        } else if snapshot.thread_id.is_none() && !snapshot.thread_choices.is_empty() {
            Some(Panel::Choices {
                kind: ChoiceKind::Threads,
                selected: 0,
            })
        } else {
            None
        };
        let mut state = Self {
            snapshot,
            composer: Composer::new(),
            fork_source_composers: HashMap::new(),
            active_fork_source: None,
            terminal_width: usize::from(width.max(1)),
            terminal_height: usize::from(height.max(1)),
            deferred_composer: None,
            panel,
            model_settings: None,
            settings_return_panel: None,
            settings_start_pending: false,
            question_index: 0,
            selected_prompt_choice: 0,
            selected_multiple: HashSet::new(),
            thread_query: String::new(),
            input_history_query: String::new(),
            input_history_texts: Vec::new(),
            tool_choices: Vec::new(),
            tool_detail_return_id: None,
            interaction_answers: Vec::new(),
            using_free_text: false,
            prompt_responded: false,
            pending_submission: None,
            suspended_control_pending: false,
            continuation_pending: false,
            cancellation: CancellationState::Idle,
            exit_confirmation: None,
            scheduled_file_search: None,
            scheduled_input_history_search: None,
            query_sequence: 0,
            active_query_id: None,
            reference_popup_dismissed: false,
            committed_ids: HashSet::new(),
            transcript: RefCell::new(Transcript::default()),
            older_page_loading: false,
            older_page_failed: false,
            work_activity: None,
            stream_progress: HashMap::new(),
            stream_receipts: VecDeque::new(),
            welcome_visible: false,
            next_stream_output_id: 1,
            closed: false,
        };
        remove_unstarted_duplicate_streams(&mut state.snapshot, &state.stream_progress);
        state.welcome_visible = state.snapshot.thread_id.is_some()
            && state.snapshot.timeline.is_empty()
            && !state.snapshot.has_older_history;
        state.flush_stable_prefix();
        if state.has_live_stream_entries() {
            state.flush_stable_prefix();
            state.sync_snapshot_streams();
        }
        state
    }

    /// 提供当前投影的只读视图，controller 不可绕过 reducer 改写 UI 状态。
    pub fn snapshot(&self) -> &UiSnapshot {
        &self.snapshot
    }

    /// 暴露草稿只读内容，避免界面和提交控制器各自持有可变副本。
    pub fn composer(&self) -> &Composer {
        &self.composer
    }

    /// 线程收到本地退出或 controller 关闭事件后停止读取终端输入。
    pub fn is_closed(&self) -> bool {
        self.closed
    }

    /// 新会话介绍属于同一对话文档，首条消息到来后也能向上翻回；恢复历史不重复插入介绍。
    pub(crate) fn should_show_welcome(&self) -> bool {
        self.welcome_visible
    }

    /// 单次更新列宽与可用行数，让折行、历史预算和短窗详情共同响应终端缩放。
    pub fn set_terminal_size(&mut self, width: u16, height: u16) {
        self.terminal_width = usize::from(width.max(1));
        self.terminal_height = usize::from(height.max(1));
    }

    /// Composer 上限取物理终端高度，不能随活动视口收缩再次减少可见草稿行数。
    pub(crate) fn terminal_height(&self) -> usize {
        self.terminal_height
    }

    /// 稳定条目由 transcript 唯一持有，快照只提供未提交的活动尾部和状态。
    pub(crate) fn visible_entries(&self) -> impl DoubleEndedIterator<Item = &TimelineEntry> {
        self.snapshot
            .timeline
            .iter()
            .filter(|entry| !self.is_committed(&entry.id))
    }

    /// 首个前缀进入对话文档后，活动尾部只显示缩进续行。
    pub(crate) fn stream_has_started(&self, entry_id: &str) -> bool {
        self.stream_progress
            .get(entry_id)
            .is_some_and(|progress| !progress.first_fragment)
    }

    /// 活动尾部读取同一分块器的样式和间距，避免代码正文在归档前被重新解释成 Markdown。
    pub(crate) fn stream_tail_layout(&self, entry_id: &str) -> Option<(StreamPresentation, bool)> {
        self.stream_progress.get(entry_id).map(|progress| {
            (
                progress.layout.tail_presentation(),
                progress.layout.tail_needs_spacing(),
            )
        })
    }

    /// 源身份和已消费字节属于流账本，排版重算和片段结算不能重新生成阅读锚点。
    pub(super) fn transcript_source(&self, entry_id: &str) -> (String, usize) {
        self.stream_progress.get(entry_id).map_or_else(
            || (entry_id.to_owned(), 0),
            |progress| (progress.source_key.clone(), progress.committed_bytes),
        )
    }

    /// 只有顶部的显式浏览请求加载旧页；并发滚轮合并为一页，失败等待 F5 而不循环重试。
    fn request_older_history(&mut self) -> Vec<UiAction> {
        if self.older_page_loading || !self.snapshot.has_older_history {
            return Vec::new();
        }
        let Some(thread_id) = self.snapshot.thread_id.clone() else {
            return Vec::new();
        };
        if self.older_page_failed {
            self.snapshot.notice = None;
        }
        self.older_page_loading = true;
        self.older_page_failed = false;
        vec![UiAction::LoadOlder { thread_id }]
    }

    /// 翻历史仅占用现有操作反馈行，不增加会挤动 Composer 的独立加载组件。
    pub(super) fn older_history_loading(&self) -> bool {
        self.older_page_loading
    }

    /// 分页重试属于原请求的继续，底栏需要区别于其它 F5 权威状态核实操作。
    pub(super) fn older_history_failed(&self) -> bool {
        self.older_page_failed
    }

    /// 阶段文字只附加在活动 Turn 的原状态行，避免压缩进度覆盖更高优先级 notice。
    pub(super) fn work_activity(&self) -> Option<&str> {
        self.work_activity.as_deref()
    }

    /// 鼠标坐标只由当前 RenderPlan 对应的正文视口解释，覆盖层不把动作透传到背后对话。
    /// @author kongweiguang
    pub fn handle_mouse(&mut self, mouse: MouseEvent) -> Vec<UiAction> {
        if self.closed
            || matches!(
                self.panel,
                Some(Panel::Details { .. } | Panel::ChoiceDetail { .. } | Panel::ModelSettings)
            )
        {
            return Vec::new();
        }
        let cancel_selection = if matches!(
            mouse.kind,
            crossterm::event::MouseEventKind::Down(crossterm::event::MouseButton::Left)
        ) {
            self.transcript.get_mut().pending_selection_copy_id()
        } else {
            None
        };
        let changed = self.transcript.get_mut().handle_mouse(mouse);
        let copy_identity_changed = cancel_selection.is_some_and(|selection_id| {
            self.transcript.borrow().pending_selection_copy_id() != Some(selection_id)
        });
        let mut actions = if changed && copy_identity_changed {
            cancel_selection
                .map(|selection_id| UiAction::CancelTranscriptSelection { selection_id })
                .into_iter()
                .collect::<Vec<_>>()
        } else {
            Vec::new()
        };
        if changed && !self.older_page_failed && self.transcript.get_mut().needs_older() {
            actions.extend(self.request_older_history());
        }
        actions
    }

    /// Apply expired text in the reducer so the terminal loop never renders a held character as lost input.
    /// @author kongweiguang
    pub(super) fn flush_paste_burst(&mut self, now: Instant) -> bool {
        let result = self.composer.flush_paste_burst(now);
        self.apply_paste_flush(result, now)
    }

    /// Let the bridge wake at the classifier boundary instead of adding another fixed poll interval.
    /// @author kongweiguang
    pub(super) fn next_paste_burst_deadline(&self) -> Option<Instant> {
        self.composer.paste_burst_deadline()
    }

    /// End an editor handoff only after visible pending text has been committed to the Composer.
    /// @author kongweiguang
    pub(super) fn reset_input_source_handoff(&mut self) {
        let result = self.composer.flush_paste_burst_before_modified_input();
        self.apply_paste_flush(result, Instant::now());
        self.composer.reset_paste_burst();
    }

    /// Expose whether the active details panel represents a user-facing message.
    /// @author kongweiguang
    pub(super) fn is_message_detail_entry_open(&self) -> bool {
        matches!(
            self.panel,
            Some(Panel::Details { ref entry_id, .. }) if self.is_message_detail_entry(entry_id)
        )
    }

    /// The footer advertises copy only after the controller confirms a complete bounded message read.
    /// @author kongweiguang
    pub(super) fn message_detail_copy_ready(&self) -> bool {
        matches!(
            self.panel,
            Some(Panel::Details {
                ref entry_id,
                copy_ready: true,
                ..
            }) if self.is_message_detail_entry(entry_id)
        )
    }

    /// Advertise F2 only when the current single-message selection or reading anchor resolves to a message.
    /// @author kongweiguang
    pub(super) fn has_message_detail_target(&self) -> bool {
        let transcript = self.transcript.borrow();
        let entry_id = if transcript.has_selection() {
            transcript.selected_entry_id()
        } else {
            transcript.anchor_entry_id()
        };
        entry_id
            .and_then(|entry_id| transcript.entry(&entry_id))
            .is_some_and(|entry| is_message_entry_kind(&entry.kind))
    }

    /// Resolve detail actions against the transcript source so tool artifacts never inherit message-only copy semantics.
    /// @author kongweiguang
    fn is_message_detail_entry(&self, entry_id: &str) -> bool {
        self.transcript
            .borrow()
            .entry(entry_id)
            .is_some_and(|entry| is_message_entry_kind(&entry.kind))
    }

    /// Prompt text uses the same bounded editor while keeping command and workspace-query shortcuts out of answers.
    /// @author kongweiguang
    fn is_prompt_text_editor(&self) -> bool {
        matches!(self.panel, Some(Panel::Pending))
            && matches!(
                self.snapshot.pending_prompt.as_ref(),
                Some(PendingPrompt::Clarification { questions, .. })
                    if questions.get(self.question_index).is_some_and(|question| {
                        question.kind == InteractionQuestionKind::Text || self.using_free_text
                    })
            )
    }

    /// Only visible composer and free-text prompt input may own delayed keystrokes.
    /// @author kongweiguang
    fn is_text_editing_context(&self) -> bool {
        matches!(self.panel, None | Some(Panel::Commands { .. })) || self.is_prompt_text_editor()
    }

    /// Apply held input in order; pasted bursts enter the bounded editor atomically so overflow cannot leave a prefix.
    /// @author kongweiguang
    fn apply_paste_flush(&mut self, result: FlushResult, now: Instant) -> bool {
        match result {
            FlushResult::None => false,
            FlushResult::Typed(ch) => {
                if self.is_prompt_text_editor() {
                    self.insert_edit_text(&ch.to_string());
                } else {
                    self.accept_composer_character(ch, now);
                }
                true
            }
            FlushResult::Paste(text) => {
                if !self.composer.insert_text(&text) {
                    self.snapshot.notice = Some("粘贴超过 32 KiB，草稿未改变；请缩短后重试".into());
                } else if !self.is_prompt_text_editor() {
                    self.after_composer_text(now);
                }
                true
            }
            FlushResult::Rejected => {
                self.snapshot.notice = Some("粘贴超过 32 KiB，草稿未改变；请缩短后重试".into());
                true
            }
        }
    }

    /// Recompute suggestions only after the full accepted text is present, so burst-held markers are not missed.
    /// @author kongweiguang
    fn after_composer_text(&mut self, now: Instant) {
        self.schedule_file_search(now);
        if matches!(self.panel, Some(Panel::Commands { .. })) {
            self.panel = if self.composer.text().starts_with('/')
                && !self.composer.text().chars().any(char::is_whitespace)
            {
                Some(Panel::Commands { selected: 0 })
            } else {
                None
            };
        }
    }

    /// Shortcut characters take effect only after the paste classifier releases them as ordinary typing.
    /// @author kongweiguang
    fn accept_composer_character(&mut self, ch: char, now: Instant) {
        if self.is_prompt_text_editor() {
            self.insert_edit_text(&ch.to_string());
            return;
        }
        if ch == '?' && self.composer.is_empty() && self.panel.is_none() {
            self.panel = Some(Panel::Help {
                scroll: DetailScroll::FromTop(0),
            });
            return;
        }
        let opening_command = ch == '/' && self.composer.is_empty();
        self.insert_edit_text(&ch.to_string());
        if opening_command {
            self.panel = Some(Panel::Commands { selected: 0 });
            self.snapshot.notice = None;
        }
        self.after_composer_text(now);
    }

    /// Consume a newline only after applying any prior expired text, preserving Enter's send boundary.
    /// @author kongweiguang
    fn consume_paste_newline(&mut self, now: Instant) -> ControlDecision {
        let input = self.composer.consume_paste_newline(now);
        self.apply_paste_flush(input.flushed, now);
        input.decision
    }

    /// A tab inside a classified burst belongs to text; otherwise the existing picker/navigation action remains.
    /// @author kongweiguang
    fn consume_paste_tab(&mut self, now: Instant) -> ControlDecision {
        let input = self.composer.consume_paste_tab(now);
        self.apply_paste_flush(input.flushed, now);
        input.decision
    }

    /// 选区复制始终先于取消；装饰性空选区也消费复制键，避免误中断正在运行的任务。
    /// @author kongweiguang
    fn transcript_key(&mut self, key: KeyEvent) -> Option<Vec<UiAction>> {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        if key.code == KeyCode::F(2) {
            let transcript = self.transcript.get_mut();
            let entry_id = if transcript.has_selection() {
                transcript.selected_entry_id()
            } else {
                transcript.anchor_entry_id()
            }?;
            let entry = transcript.entry(&entry_id)?.clone();
            if !is_message_entry_kind(&entry.kind) {
                return Some(Vec::new());
            }
            let truncated = entry.detail.as_deref() == Some(MESSAGE_PREVIEW_TRUNCATED_NOTICE);
            let message = if truncated {
                "正文未完整加载；正在读取全文。"
            } else {
                "正在读取全文；当前正文作为安全预览。"
            };
            self.panel = Some(Panel::Details {
                entry_id: entry.id.clone(),
                title: entry.kind.message_detail_title().to_owned(),
                body: format!("{message}\n\n{}", entry.text),
                scroll: DetailScroll::FromTop(0),
                return_to_pending: false,
                copy_ready: false,
            });
            return Some(vec![UiAction::OpenDetails { entry_id: entry.id }]);
        }
        let copy_key = control && matches!(key.code, KeyCode::Char('c' | 'C') | KeyCode::Insert);
        if copy_key && self.transcript.get_mut().has_selection() {
            if let Some((selection_id, text)) = self.transcript.get_mut().selection_copy() {
                return Some(vec![UiAction::CopyTranscriptSelection {
                    selection_id,
                    text,
                }]);
            }
            self.snapshot.notice = Some("选区没有可复制文字；Esc 清除选区".into());
            return Some(Vec::new());
        }
        match key.code {
            KeyCode::Esc if self.transcript.get_mut().has_selection() => {
                let cancel = self
                    .transcript
                    .get_mut()
                    .pending_selection_copy_id()
                    .map(|selection_id| UiAction::CancelTranscriptSelection { selection_id });
                self.transcript.get_mut().clear_selection();
                let mut actions = cancel.into_iter().collect::<Vec<_>>();
                if !self.older_page_failed && self.transcript.get_mut().needs_older() {
                    actions.extend(self.request_older_history());
                }
                return Some(actions);
            }
            KeyCode::Esc if self.transcript.get_mut().is_browsing() => {
                self.transcript.get_mut().follow_latest();
            }
            KeyCode::PageUp => {
                self.transcript.get_mut().page_by(-1);
            }
            KeyCode::PageDown => {
                self.transcript.get_mut().page_by(1);
            }
            KeyCode::Home if control => self.transcript.get_mut().jump_top(),
            KeyCode::End if control => self.transcript.get_mut().follow_latest(),
            _ => return None,
        }
        Some(
            if !self.older_page_failed && self.transcript.get_mut().needs_older() {
                self.request_older_history()
            } else {
                Vec::new()
            },
        )
    }

    /// 公开流移入 transcript 后只留稳定身份，避免 reducer 再保留一份完整正文。
    fn clear_committed_assistant_text(&mut self) {
        let thread_prefix = format!("{}\0", self.snapshot.thread_id.as_deref().unwrap_or(""));
        let committed_ids = &self.committed_ids;
        for entry in &mut self.snapshot.timeline {
            let committed_key = format!("{thread_prefix}{}", entry.id);
            if committed_ids.contains(&committed_key)
                && matches!(
                    &entry.kind,
                    TimelineKind::Assistant
                        | TimelineKind::FinalAnswer
                        | TimelineKind::ReasoningSummary
                )
            {
                entry.text.clear();
            }
        }
    }

    /// @author kongweiguang
    /// 快照先对账再归档；异步用量与详情增量不得触碰草稿、流账本、操作锁或已关闭面板。
    /// 编辑器的新正文只有被 Composer 接纳后才结清旧反馈，拒绝或在途提交仍保留原状态。
    pub fn apply(&mut self, event: UiEvent) {
        match event {
            UiEvent::ReplaceSnapshot(snapshot) => {
                let mut snapshot = sanitize_snapshot(*snapshot);
                let was_pending = self.snapshot.pending_prompt.is_some();
                let thread_changed = self.snapshot.thread_id != snapshot.thread_id;
                let previous_thread_id = self.snapshot.thread_id.clone();
                if thread_changed
                    && let Some(thread_id) = previous_thread_id.as_deref()
                    && self.active_fork_source.as_deref() != Some(thread_id)
                    && let Some(draft) = self.fork_source_composers.get_mut(thread_id)
                {
                    *draft = self.composer.clone();
                }
                if thread_changed && self.settings_start_pending {
                    self.close_model_settings(false);
                    self.settings_start_pending = false;
                }
                if !thread_changed && self.snapshot.local_operation.is_some() {
                    snapshot.local_operation = self.snapshot.local_operation.clone();
                    snapshot.turn_state = TurnState::Working;
                    snapshot.turn_started_at = self.snapshot.turn_started_at;
                }
                if thread_changed || snapshot.turn_state != TurnState::Working {
                    self.cancellation = CancellationState::Idle;
                }
                if !thread_changed
                    && (self.pending_submission.is_some()
                        || self.cancellation == CancellationState::Retry)
                    && snapshot.notice.is_none()
                {
                    snapshot.notice = self.snapshot.notice.clone();
                }
                let active_choice_id = self.panel.as_ref().and_then(|panel| match panel {
                    Panel::Choices { kind, selected } => self
                        .choices(*kind)
                        .get(*selected)
                        .map(|choice| choice.id.clone()),
                    _ => None,
                });
                if thread_changed {
                    self.work_activity = None;
                    self.tool_choices.clear();
                    self.tool_detail_return_id = None;
                    self.active_query_id = None;
                    self.scheduled_file_search = None;
                    self.scheduled_input_history_search = None;
                    if matches!(
                        self.panel,
                        Some(Panel::Choices {
                            kind: ChoiceKind::InputHistory,
                            ..
                        })
                    ) {
                        self.panel = None;
                        self.input_history_query.clear();
                        self.input_history_texts.clear();
                    }
                    self.finalize_all_streams();
                    self.stream_receipts.clear();
                    self.transcript.get_mut().clear();
                    self.committed_ids.clear();
                    self.older_page_loading = false;
                    self.older_page_failed = false;
                    self.welcome_visible = snapshot.thread_id.is_some()
                        && snapshot.timeline.is_empty()
                        && !snapshot.has_older_history;
                }
                remove_unstarted_duplicate_streams(&mut snapshot, &self.stream_progress);
                if !thread_changed {
                    self.reconcile_snapshot_streams(&mut snapshot);
                }
                if !thread_changed {
                    match self.panel.as_ref() {
                        Some(Panel::Choices {
                            kind: ChoiceKind::Reasoning,
                            ..
                        }) => {
                            snapshot.reasoning_choices = self.snapshot.reasoning_choices.clone();
                            snapshot.reasoning_model_identifier =
                                self.snapshot.reasoning_model_identifier.clone();
                        }
                        Some(Panel::Choices {
                            kind: ChoiceKind::Skills,
                            ..
                        }) => {
                            snapshot.skill_choices = self.snapshot.skill_choices.clone();
                        }
                        Some(Panel::Choices {
                            kind: ChoiceKind::Files,
                            ..
                        }) => {
                            snapshot.file_choices = self.snapshot.file_choices.clone();
                        }
                        Some(Panel::Choices {
                            kind: ChoiceKind::Threads,
                            ..
                        }) => {
                            snapshot.thread_choices = self.snapshot.thread_choices.clone();
                            snapshot.thread_next_cursor = self.snapshot.thread_next_cursor.clone();
                        }
                        Some(Panel::Choices {
                            kind: ChoiceKind::InputHistory,
                            ..
                        }) => {
                            snapshot.input_history_choices =
                                self.snapshot.input_history_choices.clone();
                            snapshot.input_history_next_cursor =
                                self.snapshot.input_history_next_cursor.clone();
                        }
                        Some(Panel::Choices {
                            kind: ChoiceKind::ForkReplies,
                            ..
                        }) => {
                            snapshot.fork_reply_choices = self.snapshot.fork_reply_choices.clone();
                            snapshot.fork_reply_next_cursor =
                                self.snapshot.fork_reply_next_cursor.clone();
                            snapshot.fork_reply_loading = self.snapshot.fork_reply_loading;
                            snapshot.fork_reply_load_failed = self.snapshot.fork_reply_load_failed;
                        }
                        _ => {}
                    }
                }
                let prompt_changed = !same_pending_identity(
                    self.snapshot.pending_prompt.as_ref(),
                    snapshot.pending_prompt.as_ref(),
                );
                if !thread_changed {
                    self.suppress_streamed_entries(&mut snapshot);
                }
                self.snapshot = snapshot;
                self.clear_committed_assistant_text();
                self.rebuild_tool_choices();
                if thread_changed || prompt_changed {
                    self.reset_prompt(was_pending);
                } else {
                    self.clamp_visible_panel(active_choice_id.as_deref());
                }
                if thread_changed {
                    if let Some(thread_id) = self.snapshot.thread_id.as_deref()
                        && let Some(composer) = self.fork_source_composers.get(thread_id)
                    {
                        self.composer = composer.clone();
                    } else if self.active_fork_source.is_some() {
                        // 分叉创建只复制来源草稿快照；新 child 的编辑器必须从空白状态开始。
                        self.composer = Composer::new();
                    }
                }
                self.flush_stable_prefix();
                self.sync_snapshot_streams();
            }
            UiEvent::OlderHistoryLoaded {
                thread_id,
                entries,
                has_older,
            } => {
                if self.snapshot.thread_id.as_deref() == Some(&thread_id) {
                    self.older_page_loading = false;
                    self.older_page_failed = false;
                    self.snapshot.has_older_history = has_older;
                    let entries = entries
                        .into_iter()
                        .map(sanitize_entry)
                        .filter(|entry| !self.is_committed(&entry.id))
                        .collect::<Vec<_>>();
                    for entry in &entries {
                        self.mark_committed(&entry.id);
                    }
                    self.transcript.get_mut().prepend(entries);
                }
            }
            UiEvent::OlderHistoryFailed { thread_id, notice } => {
                if self.snapshot.thread_id.as_deref() == Some(&thread_id) {
                    self.older_page_loading = false;
                    self.older_page_failed = true;
                    self.snapshot.notice = Some(sanitize_text(&notice, 1024));
                }
            }
            UiEvent::TranscriptSelectionCopied { selection_id } => {
                if self.transcript.get_mut().selection_copied(selection_id) {
                    self.snapshot.notice = Some("选区已复制".into());
                }
            }
            UiEvent::TranscriptSelectionCopyFailed {
                selection_id,
                notice,
            } => {
                if self
                    .transcript
                    .get_mut()
                    .selection_copy_failed(selection_id)
                {
                    self.snapshot.notice = Some(sanitize_text(&notice, 1024));
                }
            }
            UiEvent::UpsertEntry(entry) => {
                self.transcript.get_mut().notify_activity();
                let tool_changed = matches!(entry.kind, TimelineKind::Tool { .. });
                self.upsert_entry(entry);
                if tool_changed {
                    self.rebuild_tool_choices();
                }
            }
            UiEvent::AppendStreamDelta { entry_id, delta } => {
                if !delta.is_empty() {
                    self.transcript.get_mut().notify_activity();
                }
                self.append_stream_delta(&entry_id, &delta);
            }
            UiEvent::FinishAssistantStream { turn_id } => {
                self.finalize_turn_streams(&turn_id);
            }
            UiEvent::SetTurnState(status) => {
                if matches!(status, TurnState::Idle | TurnState::Failed) {
                    self.finalize_all_streams();
                }
                self.snapshot.turn_state = status;
                self.continuation_pending = false;
                self.cancellation = CancellationState::Idle;
            }
            UiEvent::SetLocalOperation(label) => {
                if label.is_some() && self.snapshot.local_operation.is_none() {
                    self.snapshot.turn_state = TurnState::Working;
                    self.snapshot.turn_started_at = Some(SystemTime::now());
                } else if label.is_none() && self.snapshot.local_operation.is_some() {
                    self.snapshot.turn_state = TurnState::Idle;
                    self.snapshot.turn_started_at = None;
                }
                self.snapshot.local_operation = label;
                self.cancellation = CancellationState::Idle;
            }
            UiEvent::SetSuspendedControlPending(pending) => {
                self.suspended_control_pending = pending;
            }
            UiEvent::CancellationFailed(notice) => {
                self.cancellation = CancellationState::Retry;
                self.snapshot.notice = Some(sanitize_text(&notice, 1024));
            }
            UiEvent::SetContinuationAvailable(available) => {
                self.snapshot.continuation_available = available;
                self.continuation_pending = false;
            }
            UiEvent::SetHasOlderHistory(available) => {
                self.snapshot.has_older_history = available;
            }
            UiEvent::SetUsage { thread_id, usage } => {
                if self.snapshot.thread_id.as_deref() == Some(thread_id.as_str()) {
                    self.snapshot.usage = usage.map(|usage| *usage);
                }
            }
            UiEvent::SetModel {
                model_identifier,
                provider_name,
            } => {
                self.snapshot.model_identifier =
                    model_identifier.map(|value| sanitize_text(&value, 512));
                self.snapshot.provider_name = provider_name.map(|value| sanitize_text(&value, 256));
            }
            UiEvent::SetPermission(value) => {
                self.snapshot.permission_label = value.map(|label| sanitize_text(&label, 256));
            }
            UiEvent::SetPendingPrompt(prompt) => {
                let was_pending = self.snapshot.pending_prompt.is_some();
                let next = prompt.map(sanitize_prompt);
                let changed =
                    !same_pending_identity(self.snapshot.pending_prompt.as_ref(), next.as_ref());
                self.snapshot.pending_prompt = next;
                if changed {
                    self.reset_prompt(was_pending);
                }
            }
            UiEvent::SetModelChoices(choices) => {
                let selected_id = self.selected_choice_id(ChoiceKind::Models);
                self.snapshot.model_choices = sanitize_model_choices(choices);
                self.reconcile_choice_selection(ChoiceKind::Models, selected_id.as_deref());
            }
            UiEvent::OpenModelSettings {
                operation_id,
                standalone,
            } => {
                self.open_model_settings(operation_id, standalone);
            }
            UiEvent::ModelSettingsResult(result) => {
                let saved = matches!(&result.outcome, ModelSettingsOutcome::Saved { .. });
                if let Some(settings) = self.model_settings.as_mut() {
                    let accepted = settings.apply_result(result);
                    if accepted && saved && self.snapshot.thread_id.is_some() {
                        self.close_model_settings(true);
                    }
                }
            }
            UiEvent::ModelSettingsTrustRequired { cwd } => {
                if let Some(settings) = self.model_settings.as_mut() {
                    settings.require_trust(cwd);
                } else {
                    self.model_settings = Some(ModelSettingsState::trust_required(cwd, false));
                    self.settings_return_panel = self.panel.take();
                }
                self.panel = Some(Panel::ModelSettings);
            }
            UiEvent::SetReasoningChoices {
                model_identifier,
                choices,
                selected_id,
            } => {
                self.snapshot.reasoning_model_identifier =
                    Some(sanitize_text(&model_identifier, 512));
                self.snapshot.reasoning_choices = sanitize_choices(choices);
                let selected = selected_id
                    .as_deref()
                    .and_then(|id| {
                        self.snapshot
                            .reasoning_choices
                            .iter()
                            .position(|choice| choice.id == id)
                    })
                    .unwrap_or(0);
                self.panel = Some(Panel::Choices {
                    kind: ChoiceKind::Reasoning,
                    selected,
                });
            }
            UiEvent::SetPermissionChoices(choices) => {
                let selected_id = self.selected_choice_id(ChoiceKind::Permissions);
                self.snapshot.permission_choices = sanitize_choices(choices);
                self.reconcile_choice_selection(ChoiceKind::Permissions, selected_id.as_deref());
            }
            UiEvent::SetThreadChoices(choices) => {
                self.snapshot.thread_choices = sanitize_choices(choices);
                self.clamp_choice_selection(ChoiceKind::Threads);
            }
            UiEvent::SetThreadPage {
                query,
                choices,
                next_cursor,
                append,
            } => {
                if !matches!(
                    self.panel,
                    Some(Panel::Choices {
                        kind: ChoiceKind::Threads,
                        ..
                    })
                ) || self.thread_query != query
                {
                    return;
                }
                let choices = sanitize_choices(choices);
                let mut first_new_id = None;
                if append {
                    let mut seen = self
                        .snapshot
                        .thread_choices
                        .iter()
                        .map(|choice| choice.id.clone())
                        .collect::<HashSet<_>>();
                    for choice in choices {
                        if seen.insert(choice.id.clone()) {
                            first_new_id.get_or_insert_with(|| choice.id.clone());
                            self.snapshot.thread_choices.push(choice);
                        }
                    }
                } else {
                    self.snapshot.thread_choices = choices;
                }
                let excess = self
                    .snapshot
                    .thread_choices
                    .len()
                    .saturating_sub(MAX_CHOICES);
                if excess > 0 {
                    self.snapshot.thread_choices.drain(..excess);
                }
                self.snapshot.thread_next_cursor = next_cursor;
                if excess > 0
                    && let Some(id) = first_new_id
                    && let Some(selected) = self
                        .snapshot
                        .thread_choices
                        .iter()
                        .position(|choice| choice.id == id)
                {
                    self.set_choice_selection(ChoiceKind::Threads, selected);
                } else {
                    self.clamp_choice_selection(ChoiceKind::Threads);
                }
            }
            UiEvent::SetForkReplyPage {
                choices,
                next_cursor,
                selected_id,
                prepend,
            } => {
                if !matches!(
                    self.panel,
                    Some(Panel::Choices {
                        kind: ChoiceKind::ForkReplies,
                        ..
                    })
                ) {
                    return;
                }
                self.snapshot.fork_reply_loading = false;
                self.snapshot.fork_reply_load_failed = false;
                let selected_id =
                    selected_id.or_else(|| self.selected_choice_id(ChoiceKind::ForkReplies));
                let mut choices = sanitize_fork_reply_choices(choices);
                if prepend {
                    let existing = std::mem::take(&mut self.snapshot.fork_reply_choices);
                    let mut seen = choices
                        .iter()
                        .map(|choice| choice.id.clone())
                        .collect::<HashSet<_>>();
                    choices.extend(
                        existing
                            .into_iter()
                            .filter(|choice| seen.insert(choice.id.clone())),
                    );
                }
                self.snapshot.fork_reply_choices = choices;
                self.snapshot.fork_reply_next_cursor = next_cursor;
                let selected = selected_id
                    .as_deref()
                    .and_then(|id| {
                        self.snapshot
                            .fork_reply_choices
                            .iter()
                            .position(|choice| choice.id == id)
                    })
                    .unwrap_or_else(|| self.snapshot.fork_reply_choices.len().saturating_sub(1));
                self.set_choice_selection(ChoiceKind::ForkReplies, selected);
            }
            UiEvent::ForkReplyLoadFailed => {
                if matches!(
                    self.panel,
                    Some(Panel::Choices {
                        kind: ChoiceKind::ForkReplies,
                        ..
                    })
                ) {
                    self.snapshot.fork_reply_loading = false;
                    self.snapshot.fork_reply_load_failed = true;
                }
            }
            UiEvent::SetInputHistoryPage {
                query,
                items,
                next_cursor,
                append,
            } => {
                if !matches!(
                    self.panel,
                    Some(Panel::Choices {
                        kind: ChoiceKind::InputHistory,
                        ..
                    })
                ) || self.input_history_query != query
                {
                    return;
                }
                if !append {
                    self.input_history_texts.clear();
                    self.snapshot.input_history_choices.clear();
                }
                let mut first_new_id = None;
                for (choice, text, truncated) in items.into_iter().take(MAX_CHOICES) {
                    let choice = sanitize_choice(choice);
                    if !self
                        .input_history_texts
                        .iter()
                        .any(|(id, _, _)| id == &choice.id)
                    {
                        first_new_id.get_or_insert_with(|| choice.id.clone());
                        let exceeds_budget = text.len() > MAX_INPUT_HISTORY_BYTES;
                        self.input_history_texts.push((
                            choice.id.clone(),
                            sanitize_text(&text, MAX_INPUT_HISTORY_BYTES),
                            truncated || exceeds_budget,
                        ));
                        self.snapshot.input_history_choices.push(choice);
                    }
                }
                let excess = self.input_history_texts.len().saturating_sub(MAX_CHOICES);
                if excess > 0 {
                    self.input_history_texts.drain(..excess);
                    self.snapshot.input_history_choices.drain(..excess);
                }
                self.snapshot.input_history_next_cursor = next_cursor;
                if excess > 0
                    && let Some(id) = first_new_id
                    && let Some(selected) = self
                        .snapshot
                        .input_history_choices
                        .iter()
                        .position(|choice| choice.id == id)
                {
                    self.set_choice_selection(ChoiceKind::InputHistory, selected);
                } else {
                    self.clamp_choice_selection(ChoiceKind::InputHistory);
                }
            }
            UiEvent::SetFileChoices {
                thread_id,
                query_id,
                choices,
            } => {
                if thread_id != self.snapshot.thread_id
                    || self.active_query_id != Some(query_id)
                    || self.active_file_query().is_none()
                {
                    return;
                }
                self.snapshot.file_choices = sanitize_choices(choices);
                self.clamp_choice_selection(ChoiceKind::Files);
                if !self.reference_popup_dismissed && self.active_file_query().is_some() {
                    self.panel = Some(Panel::Choices {
                        kind: ChoiceKind::Files,
                        selected: 0,
                    });
                }
            }
            UiEvent::SetSkillChoices {
                thread_id,
                query_id,
                choices,
            } => {
                if thread_id != self.snapshot.thread_id
                    || self.active_query_id != Some(query_id)
                    || self.active_skill_query().is_none()
                {
                    return;
                }
                self.snapshot.skill_choices = sanitize_choices(choices);
                self.clamp_choice_selection(ChoiceKind::Skills);
                if !self.reference_popup_dismissed && self.active_skill_query().is_some() {
                    self.panel = Some(Panel::Choices {
                        kind: ChoiceKind::Skills,
                        selected: 0,
                    });
                }
            }
            UiEvent::SetAttachments(choices) => {
                self.snapshot.attachments = sanitize_choices(choices);
                self.clamp_choice_selection(ChoiceKind::Attachments);
            }
            UiEvent::OpenThreadChooser => self.open_picker(ChoiceKind::Threads),
            UiEvent::InsertFileReference {
                workspace_id,
                relative_path,
                kind,
            } => {
                let label = format!("@{}", sanitize_text(&relative_path, 4096));
                let range = self
                    .active_file_query()
                    .map(|query| (query.start, query.end));
                self.insert_reference(
                    label,
                    range,
                    UiReferenceKind::Workspace {
                        workspace_id,
                        relative_path,
                        kind,
                    },
                );
            }
            UiEvent::InsertSkillReference { skill_id, name } => {
                let label = format!("${}", sanitize_text(&name, 512));
                let range = self
                    .active_skill_query()
                    .map(|query| (query.start, query.end));
                self.insert_reference(label, range, UiReferenceKind::Skill { skill_id });
            }
            UiEvent::BeginSubmission(text) => {
                let mut submitted = Composer::new();
                if submitted.set_text(&text) {
                    self.retain_message_submission(submitted);
                    self.snapshot.notice = None;
                } else {
                    self.snapshot.notice = Some("输入内容过长，请缩短正文或使用文件引用。".into());
                }
            }
            UiEvent::ClearDraft => {
                let needs_placeholder = self.visible_pending_message_text().is_some();
                match self.pending_submission.take() {
                    Some(PendingSubmission::Message {
                        composer: submitted,
                        ..
                    }) if needs_placeholder => {
                        // ACK 是接纳事实；临时问题保留在可重绘尾部，权威快照替换它之前不写入历史。
                        let id = format!("submitted-input:{}", self.next_stream_output_id);
                        self.next_stream_output_id = self.next_stream_output_id.saturating_add(1);
                        self.upsert_entry(TimelineEntry {
                            id,
                            kind: TimelineKind::User,
                            text: submitted.text().to_owned(),
                            detail: None,
                            status: Some(TimelineStatus::Running),
                        });
                    }
                    Some(PendingSubmission::Attachment(submitted))
                        if self.composer.text() == submitted =>
                    {
                        self.composer.clear()
                    }
                    _ => {}
                }
            }
            UiEvent::RestoreDraft(draft) => {
                if let Some(PendingSubmission::Message {
                    composer: submitted,
                    ..
                }) = self.pending_submission.take()
                    && submitted.text() == draft
                {
                    self.composer.restore_submission(*submitted);
                } else if self.composer.is_empty() {
                    self.composer.set_text(&draft);
                }
            }
            UiEvent::RestoreForkDraft => {
                self.restore_fork_draft();
            }
            UiEvent::ClearForkDraft => self.active_fork_source = None,
            UiEvent::ReplaceDraft(draft) => {
                if self.pending_submission.is_none() {
                    if self.composer.set_text(&draft) {
                        self.snapshot.notice = None;
                    } else {
                        self.snapshot.notice =
                            Some("编辑后的草稿超过 32 KiB 容量，已保留原草稿。".into());
                    }
                }
            }
            UiEvent::ReleaseDraft => self.pending_submission = None,
            UiEvent::ReleasePromptSubmission => self.prompt_responded = false,
            UiEvent::ShowDetails {
                entry_id,
                title,
                body,
            } => {
                let scroll = match self.panel.as_ref() {
                    Some(Panel::Details {
                        entry_id: active_entry_id,
                        scroll,
                        ..
                    }) if active_entry_id == &entry_id => *scroll,
                    _ => DetailScroll::FromTop(0),
                };
                self.panel = Some(Panel::Details {
                    entry_id: sanitize_text(&entry_id, 512),
                    title: sanitize_text(&title, 256),
                    body: sanitize_text(&body, MAX_DETAIL_BYTES),
                    scroll,
                    return_to_pending: false,
                    copy_ready: false,
                });
            }
            UiEvent::UpdateDetailsIfOpen {
                entry_id,
                title,
                body,
            } => {
                if let Some(Panel::Details {
                    entry_id: active_entry_id,
                    title: active_title,
                    body: active_body,
                    copy_ready,
                    ..
                }) = self.panel.as_mut()
                    && active_entry_id == &entry_id
                    // Once the full body is acknowledged, only a new retry may replace it.
                    && !*copy_ready
                {
                    *active_title = sanitize_text(&title, 256);
                    *active_body = sanitize_text(&body, MAX_DETAIL_BYTES);
                }
            }
            UiEvent::SetDetailsCopyReady { entry_id, ready } => {
                let is_message = self.is_message_detail_entry(&entry_id);
                if let Some(Panel::Details {
                    entry_id: active_entry_id,
                    copy_ready,
                    ..
                }) = self.panel.as_mut()
                    && active_entry_id == &entry_id
                    && is_message
                {
                    *copy_ready = ready;
                }
            }
            UiEvent::SetNotice(notice) => {
                if self.cancellation == CancellationState::Retry {
                    self.cancellation = CancellationState::Idle;
                }
                self.snapshot.notice = notice.map(|value| sanitize_text(&value, 1024));
            }
            UiEvent::SetWorkActivity(activity) => {
                self.work_activity = activity.map(|value| sanitize_text(&value, 256));
            }
            UiEvent::Shutdown => {
                self.finalize_all_streams();
                self.closed = true;
            }
        }
    }

    /// 详情复制同时接受终端普遍可达的普通 C 与 Ctrl+Insert；只复制确认完整的消息，Ctrl+C 仍保留原关闭/取消语义。
    /// @author kongweiguang
    pub fn handle_key(&mut self, key: KeyEvent) -> Vec<UiAction> {
        if key.kind != KeyEventKind::Press || self.closed {
            return Vec::new();
        }
        if self.is_text_editing_context() && key_ends_paste_candidate(key) {
            let result = self.composer.flush_paste_burst_before_modified_input();
            self.apply_paste_flush(result, Instant::now());
        }
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        if matches!(self.panel, Some(Panel::ModelSettings))
            && control
            && key.code == KeyCode::Char('d')
        {
            self.close_model_settings(true);
            self.closed = true;
            return vec![UiAction::Quit];
        }
        if matches!(self.panel, Some(Panel::ModelSettings)) {
            return self.model_settings_key(key);
        }
        if self.panel.is_none() {
            let control = key.modifiers.contains(KeyModifiers::CONTROL);
            if control
                && matches!(key.code, KeyCode::Char('c' | 'C') | KeyCode::Insert)
                && let Some(actions) = self.transcript_key(key)
            {
                return actions;
            }
            if key.code == KeyCode::F(5) && self.older_page_failed {
                return self.request_older_history();
            }
        }
        if key.code == KeyCode::F(5) {
            let retry_message = matches!(
                self.panel.as_ref(),
                Some(Panel::Details { entry_id, .. }) if self.is_message_detail_entry(entry_id)
            );
            if retry_message
                && let Some(Panel::Details {
                    entry_id,
                    copy_ready,
                    ..
                }) = self.panel.as_mut()
            {
                *copy_ready = false;
                return vec![UiAction::OpenDetails {
                    entry_id: entry_id.clone(),
                }];
            }
            return vec![match &self.panel {
                Some(Panel::Details { entry_id, .. }) if entry_id == "status" => {
                    UiAction::Command(UiCommand::ShowStatus)
                }
                Some(Panel::Details { entry_id, .. })
                    if entry_id.starts_with("diff:")
                        || self.tool_detail_return_id.as_deref() == Some(entry_id) =>
                {
                    UiAction::OpenDetails {
                        entry_id: entry_id.clone(),
                    }
                }
                _ => UiAction::RefreshSnapshot,
            }];
        }
        // 普通 C 只在消息详情里映射复制，避免终端截获 Ctrl+Insert 后没有可执行入口。
        let ordinary_detail_copy = matches!(key.code, KeyCode::Char('c' | 'C'))
            && !key
                .modifiers
                .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT)
            && self.is_message_detail_entry_open();
        if (ordinary_detail_copy || (control && key.code == KeyCode::Insert))
            && let Some(Panel::Details {
                entry_id,
                body,
                copy_ready,
                ..
            }) = self.panel.as_ref()
        {
            if *copy_ready && self.is_message_detail_entry(entry_id) {
                return vec![UiAction::CopyDetails {
                    entry_id: entry_id.clone(),
                    text: body.clone(),
                }];
            }
            self.snapshot.notice = Some("消息全文尚未确认读取，暂不能复制".into());
            return Vec::new();
        }
        if control && key.code == KeyCode::Char('d') && self.composer.is_empty() {
            self.closed = true;
            return vec![UiAction::Quit];
        }
        if control && key.code == KeyCode::Char('c') {
            if let Some(Panel::Details {
                entry_id,
                return_to_pending,
                ..
            }) = self.panel.clone()
            {
                return self.close_detail_panel(entry_id, return_to_pending);
            }
            if self.snapshot.local_operation.is_some()
                && self.cancellation != CancellationState::Pending
            {
                self.cancellation = CancellationState::Pending;
                self.snapshot.notice = None;
                return vec![UiAction::CancelLocalOperation];
            }
            if let Some(Panel::ChoiceDetail {
                kind, choice_id, ..
            }) = self.panel.clone()
            {
                self.restore_choice_detail(kind, &choice_id);
                return Vec::new();
            }
            if matches!(self.panel, Some(Panel::Choices { .. } | Panel::Help { .. })) {
                let cancel_history = matches!(
                    self.panel,
                    Some(Panel::Choices {
                        kind: ChoiceKind::InputHistory,
                        ..
                    })
                );
                if matches!(
                    self.panel.as_ref(),
                    Some(Panel::Choices {
                        kind: ChoiceKind::Threads,
                        ..
                    })
                ) {
                    self.set_resume_picker_exit_notice();
                }
                if matches!(
                    self.panel.as_ref(),
                    Some(Panel::Choices {
                        kind: ChoiceKind::ForkReplies,
                        ..
                    })
                ) {
                    self.restore_fork_draft();
                }
                self.panel = None;
                if cancel_history {
                    return vec![self.cancel_input_history_search()];
                }
                return Vec::new();
            }
            let actions = self.composer_key(key);
            if matches!(self.panel, Some(Panel::Commands { .. })) && self.composer.is_empty() {
                self.panel = None;
            }
            return actions;
        }
        match self.panel.clone() {
            Some(Panel::Commands { selected }) => return self.command_panel_key(key, selected),
            Some(Panel::Choices { kind, selected }) => {
                return self.choice_panel_key(key, kind, selected);
            }
            Some(Panel::ChoiceDetail {
                kind,
                choice_id,
                scroll,
            }) => return self.choice_detail_key(key, kind, choice_id, scroll),
            Some(Panel::Pending) => return self.pending_panel_key(key),
            Some(Panel::ModelSettings) => return self.model_settings_key(key),
            Some(panel @ Panel::Details { .. }) => return self.detail_panel_key(key, panel),
            Some(Panel::Help { scroll }) => {
                if key.code == KeyCode::Esc
                    || (key.code == KeyCode::Char('?')
                        && !key
                            .modifiers
                            .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT))
                {
                    self.panel = None;
                    return Vec::new();
                }
                let next = match key.code {
                    KeyCode::Up => scroll.up(1),
                    KeyCode::Down => scroll.down(1),
                    KeyCode::PageUp => scroll.up(10),
                    KeyCode::PageDown => scroll.down(10),
                    KeyCode::Home => DetailScroll::FromTop(0),
                    KeyCode::End => DetailScroll::FromBottom(0),
                    _ => return Vec::new(),
                };
                self.panel = Some(Panel::Help { scroll: next });
                return Vec::new();
            }
            None => {}
        }
        if let Some(actions) = self.transcript_key(key) {
            return actions;
        }
        if self.snapshot.pending_prompt.is_some() {
            if self.panel.is_none() {
                if key.code == KeyCode::Enter {
                    self.panel = Some(Panel::Pending);
                    return Vec::new();
                }
                return self.composer_key(key);
            }
            return self.pending_panel_key(key);
        }
        if key.modifiers.is_empty()
            && self.panel.is_none()
            && self.snapshot.local_operation.is_none()
            && self.snapshot.turn_state == TurnState::Paused
            && !self.suspended_control_pending
        {
            match key.code {
                KeyCode::F(6) => {
                    self.suspended_control_pending = true;
                    let Some(thread_id) = self.snapshot.thread_id.clone() else {
                        self.suspended_control_pending = false;
                        return Vec::new();
                    };
                    return vec![UiAction::ResumeSuspendedTurn { thread_id }];
                }
                KeyCode::F(7) => {
                    self.suspended_control_pending = true;
                    let Some(thread_id) = self.snapshot.thread_id.clone() else {
                        self.suspended_control_pending = false;
                        return Vec::new();
                    };
                    return vec![UiAction::CancelSuspendedTurn { thread_id }];
                }
                _ => {}
            }
        }
        self.composer_key(key)
    }

    /// 只在可见编辑态接纳粘贴，详情或选择器不能暗中改写被遮住的草稿。
    /// @author kongweiguang
    pub fn handle_paste(&mut self, text: &str) {
        if matches!(self.panel, Some(Panel::ModelSettings)) {
            if let Some(settings) = self.model_settings.as_mut() {
                settings.handle_paste(text);
            }
            return;
        }
        let editing = match self.panel.as_ref() {
            None | Some(Panel::Commands { .. }) => true,
            Some(Panel::Pending) => matches!(
                self.snapshot.pending_prompt.as_ref(),
                Some(PendingPrompt::Clarification { questions, .. })
                    if questions.get(self.question_index).is_some_and(|question| {
                        question.kind == InteractionQuestionKind::Text || self.using_free_text
                    })
            ),
            Some(
                Panel::Choices { .. }
                | Panel::ChoiceDetail { .. }
                | Panel::Help { .. }
                | Panel::Details { .. }
                | Panel::ModelSettings,
            ) => false,
        };
        if !editing {
            return;
        }
        let now = Instant::now();
        let pending = self.composer.flush_paste_burst_before_modified_input();
        self.apply_paste_flush(pending, now);
        self.composer.reset_paste_burst();
        if !self.composer.insert_text(text) {
            self.snapshot.notice = Some("粘贴超过 32 KiB，草稿未改变；请缩短后重试".into());
            return;
        }
        if !self.is_prompt_text_editor() {
            self.after_composer_text(now);
        }
    }

    /// 到期后只向 controller 发出最新文件查询，避免输入速度放大搜索请求。
    pub fn take_due_file_search(&mut self, now: Instant) -> Option<UiAction> {
        let (query, query_id, started) = self.scheduled_file_search.as_ref()?;
        if now.saturating_duration_since(*started) < FILE_SEARCH_DEBOUNCE {
            return None;
        }
        let query = query.clone();
        let query_id = *query_id;
        self.scheduled_file_search = None;
        if let Some(skill_query) = query.strip_prefix('$') {
            Some(UiAction::SearchSkills {
                query: skill_query.to_owned(),
                thread_id: self.snapshot.thread_id.clone(),
                query_id,
            })
        } else {
            Some(UiAction::SearchFiles {
                query,
                thread_id: self.snapshot.thread_id.clone(),
                query_id,
            })
        }
    }

    /// Emit only the latest debounced history query while its picker still owns focus.
    /// @author kongweiguang
    pub(super) fn take_due_input_history_search(&mut self, now: Instant) -> Option<UiAction> {
        if !matches!(
            self.panel,
            Some(Panel::Choices {
                kind: ChoiceKind::InputHistory,
                ..
            })
        ) {
            self.scheduled_input_history_search = None;
            return None;
        }
        let (query, cursor, started) = self.scheduled_input_history_search.as_ref()?;
        if now.saturating_duration_since(*started) < FILE_SEARCH_DEBOUNCE {
            return None;
        }
        let action = UiAction::SearchInputHistory {
            query: query.clone(),
            cursor: cursor.clone(),
        };
        self.scheduled_input_history_search = None;
        Some(action)
    }

    /// Keep one newest query locally and cancel any older controller read before debounce expires.
    /// @author kongweiguang
    fn schedule_input_history_search(&mut self, query: String, now: Instant) -> Vec<UiAction> {
        self.input_history_query = query.clone();
        self.scheduled_input_history_search = Some((query, None, now));
        vec![UiAction::CancelInputHistorySearch]
    }

    /// Closing the picker clears both its delayed request and controller-side result ownership.
    /// @author kongweiguang
    fn cancel_input_history_search(&mut self) -> UiAction {
        self.scheduled_input_history_search = None;
        UiAction::CancelInputHistorySearch
    }

    /// 命令输入与可见前缀候选同源；Plan 创建先留在编辑区补齐目标，避免空参数触发普通 Turn。
    fn command_panel_key(&mut self, key: KeyEvent, selected: usize) -> Vec<UiAction> {
        let candidates = matching_commands(self.composer.text());
        match key.code {
            KeyCode::Esc => self.panel = None,
            KeyCode::Up => {
                self.panel = Some(Panel::Commands {
                    selected: if selected == 0 {
                        candidates.len().saturating_sub(1)
                    } else {
                        selected - 1
                    },
                })
            }
            KeyCode::Down => {
                self.panel = Some(Panel::Commands {
                    selected: if candidates.is_empty() {
                        0
                    } else {
                        (selected + 1) % candidates.len()
                    },
                })
            }
            KeyCode::Tab => {
                if let Some(command) = candidates.get(selected).copied() {
                    self.composer.set_text(command_label(command));
                    self.panel = None;
                }
            }
            KeyCode::Enter => {
                let Some(command) = candidates.get(selected).copied() else {
                    return Vec::new();
                };
                self.panel = None;
                self.composer.clear();
                self.snapshot.notice = None;
                if command == UiCommand::Quit {
                    self.closed = true;
                    return vec![UiAction::Quit];
                }
                if command == UiCommand::CreatePlan {
                    self.composer.set_text("/plan create ");
                    return Vec::new();
                }
                self.prepare_command(command);
                return vec![UiAction::Command(command)];
            }
            KeyCode::Backspace | KeyCode::Delete | KeyCode::Char(_)
                if !key.modifiers.contains(KeyModifiers::CONTROL) =>
            {
                let actions = self.composer_key(key);
                if self.composer.text().starts_with('/')
                    && !self.composer.text().chars().any(char::is_whitespace)
                {
                    self.panel = Some(Panel::Commands { selected: 0 });
                } else {
                    self.panel = None;
                }
                self.snapshot.notice = None;
                return actions;
            }
            _ => {}
        }
        Vec::new()
    }

    /// Details closure preserves draft and anchor state while invalidating the owned asynchronous read.
    /// @author kongweiguang
    fn detail_panel_key(&mut self, key: KeyEvent, panel: Panel) -> Vec<UiAction> {
        let Panel::Details {
            entry_id,
            title,
            body,
            scroll,
            return_to_pending,
            copy_ready,
        } = panel
        else {
            return Vec::new();
        };
        if key.code == KeyCode::Esc {
            return self.close_detail_panel(entry_id, return_to_pending);
        }
        let next = match key.code {
            KeyCode::Up => scroll.up(1),
            KeyCode::Down => scroll.down(1),
            KeyCode::PageUp => scroll.up(10),
            KeyCode::PageDown => scroll.down(10),
            KeyCode::Home => DetailScroll::FromTop(0),
            KeyCode::End => DetailScroll::FromBottom(0),
            _ => return Vec::new(),
        };
        self.panel = Some(Panel::Details {
            entry_id,
            title,
            body,
            scroll: next,
            return_to_pending,
            copy_ready,
        });
        Vec::new()
    }

    /// Close through one path so Esc and Ctrl+C invalidate the same read while restoring the correct parent panel.
    /// @author kongweiguang
    fn close_detail_panel(&mut self, entry_id: String, return_to_pending: bool) -> Vec<UiAction> {
        self.panel = if return_to_pending && self.snapshot.pending_prompt.is_some() {
            Some(Panel::Pending)
        } else {
            None
        };
        if !return_to_pending && self.tool_detail_return_id.as_deref() == Some(&entry_id) {
            self.rebuild_tool_choices();
            if let Some(selected) = self
                .tool_choices
                .iter()
                .position(|choice| choice.id == entry_id)
            {
                self.panel = Some(Panel::Choices {
                    kind: ChoiceKind::ToolSteps,
                    selected,
                });
            }
            self.tool_detail_return_id = None;
        }
        close_details_action(entry_id)
    }

    /// 候选说明独立滚动，返回时使用当前权威列表修正位置而不丢失草稿。
    fn choice_detail_key(
        &mut self,
        key: KeyEvent,
        kind: ChoiceKind,
        choice_id: String,
        scroll: DetailScroll,
    ) -> Vec<UiAction> {
        if key.code == KeyCode::Esc {
            self.restore_choice_detail(kind, &choice_id);
            return Vec::new();
        }
        let next = match key.code {
            KeyCode::Up => scroll.up(1),
            KeyCode::Down => scroll.down(1),
            KeyCode::PageUp => scroll.up(10),
            KeyCode::PageDown => scroll.down(10),
            KeyCode::Home => DetailScroll::FromTop(0),
            KeyCode::End => DetailScroll::FromBottom(0),
            _ => return Vec::new(),
        };
        self.panel = Some(Panel::ChoiceDetail {
            kind,
            choice_id,
            scroll: next,
        });
        Vec::new()
    }

    /// 详情关闭时按稳定 ID 寻回选项，已移除的候选必须重新选择以免误确认邻项。
    fn restore_choice_detail(&mut self, kind: ChoiceKind, choice_id: &str) {
        if let Some(selected) = self
            .choices(kind)
            .iter()
            .position(|choice| choice.id == choice_id)
        {
            self.set_choice_selection(kind, selected);
        } else {
            self.panel = None;
            self.snapshot.notice = Some("该选项已更新，请重新打开列表选择".to_owned());
        }
    }

    /// 候选面板只接受 controller 提供的身份；无活动 Thread 时退出恢复面板要给出可继续的下一步。
    fn choice_panel_key(
        &mut self,
        key: KeyEvent,
        kind: ChoiceKind,
        selected: usize,
    ) -> Vec<UiAction> {
        if key.code == KeyCode::F(2)
            && let Some(choice) = self.choices(kind).get(selected)
        {
            self.panel = Some(Panel::ChoiceDetail {
                kind,
                choice_id: choice.id.clone(),
                scroll: DetailScroll::FromTop(0),
            });
            return Vec::new();
        }
        if kind == ChoiceKind::InputHistory {
            match key.code {
                KeyCode::Backspace => {
                    if self.input_history_query.pop().is_none() {
                        return Vec::new();
                    }
                    self.input_history_texts.clear();
                    self.snapshot.input_history_choices.clear();
                    self.snapshot.input_history_next_cursor = None;
                    self.panel = Some(Panel::Choices { kind, selected: 0 });
                    return self.schedule_input_history_search(
                        self.input_history_query.clone(),
                        Instant::now(),
                    );
                }
                KeyCode::Char(ch)
                    if !key
                        .modifiers
                        .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT)
                        && self.input_history_query.len() < 256 =>
                {
                    self.input_history_query.push(ch);
                    self.input_history_texts.clear();
                    self.snapshot.input_history_choices.clear();
                    self.snapshot.input_history_next_cursor = None;
                    self.panel = Some(Panel::Choices { kind, selected: 0 });
                    return self.schedule_input_history_search(
                        self.input_history_query.clone(),
                        Instant::now(),
                    );
                }
                KeyCode::PageDown if self.snapshot.input_history_next_cursor.is_some() => {
                    let mut actions = vec![self.cancel_input_history_search()];
                    actions.push(UiAction::SearchInputHistory {
                        query: self.input_history_query.clone(),
                        cursor: self.snapshot.input_history_next_cursor.clone(),
                    });
                    return actions;
                }
                _ => {}
            }
        }
        if kind == ChoiceKind::ForkReplies && key.code == KeyCode::PageDown {
            if self.snapshot.fork_reply_next_cursor.is_some() {
                return self
                    .snapshot
                    .thread_id
                    .clone()
                    .map_or_else(Vec::new, |thread_id| {
                        vec![UiAction::LoadMoreForkReplies { thread_id }]
                    });
            }
            return Vec::new();
        }
        if kind == ChoiceKind::Threads {
            match key.code {
                KeyCode::Backspace => {
                    self.thread_query.pop();
                    self.snapshot.thread_choices.clear();
                    self.snapshot.thread_next_cursor = None;
                    self.panel = Some(Panel::Choices { kind, selected: 0 });
                    return vec![UiAction::SearchThreads {
                        query: self.thread_query.clone(),
                        cursor: None,
                    }];
                }
                KeyCode::Char(ch)
                    if !key
                        .modifiers
                        .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT)
                        && self.thread_query.len() < 256 =>
                {
                    self.thread_query.push(ch);
                    self.snapshot.thread_choices.clear();
                    self.snapshot.thread_next_cursor = None;
                    self.panel = Some(Panel::Choices { kind, selected: 0 });
                    return vec![UiAction::SearchThreads {
                        query: self.thread_query.clone(),
                        cursor: None,
                    }];
                }
                KeyCode::PageDown if self.snapshot.thread_next_cursor.is_some() => {
                    return vec![UiAction::SearchThreads {
                        query: self.thread_query.clone(),
                        cursor: self.snapshot.thread_next_cursor.clone(),
                    }];
                }
                _ => {}
            }
        }
        match key.code {
            KeyCode::Esc => {
                if matches!(kind, ChoiceKind::Files | ChoiceKind::Skills) {
                    self.reference_popup_dismissed = true;
                    self.active_query_id = None;
                }
                if kind == ChoiceKind::Threads {
                    self.set_resume_picker_exit_notice();
                }
                if kind == ChoiceKind::ForkReplies {
                    self.restore_fork_draft();
                }
                self.panel = if kind == ChoiceKind::Reasoning {
                    Some(Panel::Choices {
                        kind: ChoiceKind::Models,
                        selected: 0,
                    })
                } else {
                    None
                };
                if kind == ChoiceKind::InputHistory {
                    return vec![self.cancel_input_history_search()];
                }
            }
            KeyCode::Up => self.set_choice_selection(
                kind,
                if selected == 0 {
                    self.choices(kind).len().saturating_sub(1)
                } else {
                    selected - 1
                },
            ),
            KeyCode::Down => self.set_choice_selection(
                kind,
                if self.choices(kind).is_empty() {
                    0
                } else {
                    (selected + 1) % self.choices(kind).len()
                },
            ),
            KeyCode::Enter | KeyCode::Tab => return self.activate_choice(kind, selected, false),
            KeyCode::Char(ch)
                if !matches!(kind, ChoiceKind::Files | ChoiceKind::Skills)
                    && !key
                        .modifiers
                        .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT)
                    && ch.is_ascii_digit() =>
            {
                let index = ch.to_digit(10).unwrap_or(0) as usize;
                if index > 0 && index <= self.choices(kind).len() {
                    return self.activate_choice(kind, index - 1, false);
                }
            }
            KeyCode::Char('v') if kind == ChoiceKind::Attachments => {
                return self.activate_choice(kind, selected, true);
            }
            KeyCode::Delete if kind == ChoiceKind::Attachments => {
                return self.remove_attachment(selected);
            }
            KeyCode::Backspace | KeyCode::Delete | KeyCode::Char(_)
                if matches!(kind, ChoiceKind::Files | ChoiceKind::Skills)
                    && !key.modifiers.contains(KeyModifiers::CONTROL) =>
            {
                self.panel = None;
                return self.composer_key(key);
            }
            _ => {}
        }
        Vec::new()
    }

    /// 仅 startup resume 没有活动 Thread；取消时恢复项目空态或未选择提示，并清掉旧搜索游标。
    fn set_resume_picker_exit_notice(&mut self) {
        if self.snapshot.thread_id.is_some() {
            return;
        }
        let notice = if !self.thread_query.is_empty() {
            "当前项目没有匹配项；可用 /new 新建，或 /resume 重试"
        } else if self.snapshot.thread_choices.is_empty() {
            "当前项目暂无可恢复会话；可用 /new 新建，或 /resume 重试"
        } else {
            "未选择会话；可用 /resume 重新查找，或 /new 新建"
        };
        self.snapshot.notice = Some(notice.to_owned());
        self.thread_query.clear();
        self.snapshot.thread_choices.clear();
        self.snapshot.thread_next_cursor = None;
    }

    /// 审批和澄清以独立键盘状态处理，避免普通 Submit 意外响应服务端交互。
    fn pending_panel_key(&mut self, key: KeyEvent) -> Vec<UiAction> {
        if self.prompt_responded {
            if key.code == KeyCode::Esc {
                self.panel = None;
            }
            return Vec::new();
        }
        if key.code == KeyCode::F(2) {
            let (title, body) = match self.snapshot.pending_prompt.as_ref() {
                Some(PendingPrompt::ToolApproval { prompt, .. }) => ("审批详情", prompt.as_str()),
                Some(PendingPrompt::Clarification { questions, .. }) => {
                    let Some(question) = questions.get(self.question_index) else {
                        return Vec::new();
                    };
                    ("问题全文", question.prompt.as_str())
                }
                None => return Vec::new(),
            };
            self.panel = Some(Panel::Details {
                entry_id: "pending-prompt".to_owned(),
                title: title.to_owned(),
                body: body.to_owned(),
                scroll: DetailScroll::FromTop(0),
                return_to_pending: true,
                copy_ready: false,
            });
            return Vec::new();
        }
        match self.snapshot.pending_prompt.clone() {
            Some(PendingPrompt::ToolApproval {
                approval_id,
                thread_id,
                turn_id,
                expected_thread_revision,
                choices,
                ..
            }) => {
                if let Some(index) = number_shortcut(key, choices.len()) {
                    self.selected_prompt_choice = index;
                    return self
                        .pending_panel_key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
                }
                match key.code {
                    KeyCode::Esc => self.panel = None,
                    KeyCode::Up => {
                        self.selected_prompt_choice = self.selected_prompt_choice.saturating_sub(1)
                    }
                    KeyCode::Down => {
                        self.selected_prompt_choice =
                            (self.selected_prompt_choice + 1).min(choices.len().saturating_sub(1))
                    }
                    KeyCode::Enter => {
                        let Some(choice) = choices.get(self.selected_prompt_choice) else {
                            return Vec::new();
                        };
                        self.prompt_responded = true;
                        return vec![UiAction::RespondToolApproval {
                            approval_id,
                            thread_id,
                            turn_id,
                            expected_thread_revision,
                            choice_id: choice.id.clone(),
                        }];
                    }
                    _ => {}
                }
            }
            Some(PendingPrompt::Clarification {
                thread_id,
                interaction_id,
                expected_interaction_revision,
                operation_key,
                questions,
            }) => {
                let Some(question) = questions.get(self.question_index).cloned() else {
                    return Vec::new();
                };
                return self.clarification_key(
                    key,
                    question,
                    ClarificationContext {
                        thread_id,
                        interaction_id,
                        expected_interaction_revision,
                        operation_key,
                        question_count: questions.len(),
                    },
                );
            }
            None => self.panel = None,
        }
        Vec::new()
    }

    /// 单选、多选与自由文本按 schema 分流；Esc 仅收起，跳过必须由明确动作触发。
    fn clarification_key(
        &mut self,
        key: KeyEvent,
        question: InteractionQuestion,
        context: ClarificationContext,
    ) -> Vec<UiAction> {
        if key.code == KeyCode::Esc {
            self.panel = None;
            return Vec::new();
        }
        if question.allow_free_text && key.code == KeyCode::Tab {
            self.using_free_text = !self.using_free_text;
            self.composer.clear();
            return Vec::new();
        }
        if question.kind == InteractionQuestionKind::Text || self.using_free_text {
            return self.question_text_key(key, question, context);
        }
        let shortcut_count = question.options.len()
            + usize::from(question.kind == InteractionQuestionKind::Single && question.allow_skip);
        if let Some(index) = number_shortcut(key, shortcut_count) {
            self.selected_prompt_choice = index;
            let code = if question.kind == InteractionQuestionKind::Multiple {
                KeyCode::Char(' ')
            } else {
                KeyCode::Enter
            };
            return self.clarification_key(
                KeyEvent::new(code, KeyModifiers::NONE),
                question,
                context,
            );
        }
        match question.kind {
            InteractionQuestionKind::Single => match key.code {
                KeyCode::Up => {
                    self.selected_prompt_choice = self.selected_prompt_choice.saturating_sub(1)
                }
                KeyCode::Down => {
                    self.selected_prompt_choice = (self.selected_prompt_choice + 1).min(
                        question
                            .options
                            .len()
                            .saturating_sub(usize::from(!question.allow_skip)),
                    )
                }
                KeyCode::Enter => {
                    if question.allow_skip && self.selected_prompt_choice == question.options.len()
                    {
                        return self.complete_question(
                            InteractionAnswer {
                                question_id: question.question_id,
                                option_ids: Vec::new(),
                                free_text: None,
                                skipped: true,
                            },
                            context,
                        );
                    }
                    let Some(choice) = question.options.get(self.selected_prompt_choice) else {
                        return Vec::new();
                    };
                    return self.complete_question(
                        InteractionAnswer {
                            question_id: question.question_id,
                            option_ids: vec![choice.id.clone()],
                            free_text: None,
                            skipped: false,
                        },
                        context,
                    );
                }
                _ => {}
            },
            InteractionQuestionKind::Multiple => match key.code {
                KeyCode::Up => {
                    self.selected_prompt_choice = self.selected_prompt_choice.saturating_sub(1)
                }
                KeyCode::Down => {
                    self.selected_prompt_choice = (self.selected_prompt_choice + 1)
                        .min(question.options.len().saturating_sub(1))
                }
                KeyCode::Char(' ') => {
                    if let Some(choice) = question.options.get(self.selected_prompt_choice)
                        && !self.selected_multiple.remove(&choice.id)
                        && self.selected_multiple.len() < 32
                    {
                        self.selected_multiple.insert(choice.id.clone());
                    }
                }
                KeyCode::Enter => {
                    let option_ids = question
                        .options
                        .iter()
                        .filter(|choice| self.selected_multiple.contains(&choice.id))
                        .map(|choice| choice.id.clone())
                        .collect::<Vec<_>>();
                    if option_ids.is_empty() && !question.allow_skip {
                        return Vec::new();
                    }
                    let skipped = option_ids.is_empty();
                    return self.complete_question(
                        InteractionAnswer {
                            question_id: question.question_id,
                            option_ids,
                            free_text: None,
                            skipped,
                        },
                        context,
                    );
                }
                _ => {}
            },
            InteractionQuestionKind::Text => {}
        }
        Vec::new()
    }

    /// Text answers share paste ordering while keeping Composer-only shortcuts out of responses.
    /// @author kongweiguang
    fn question_text_key(
        &mut self,
        key: KeyEvent,
        question: InteractionQuestion,
        context: ClarificationContext,
    ) -> Vec<UiAction> {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        match key.code {
            KeyCode::Tab => match self.consume_paste_tab(Instant::now()) {
                ControlDecision::Buffered => return Vec::new(),
                ControlDecision::Insert => {
                    self.insert_edit_text("\t");
                    return Vec::new();
                }
                ControlDecision::NotPaste => return Vec::new(),
            },
            KeyCode::Enter
                if key.modifiers.contains(KeyModifiers::SHIFT)
                    || key.modifiers.contains(KeyModifiers::CONTROL) =>
            {
                self.insert_edit_text("\n");
            }
            KeyCode::Char(ch) if ch == 'j' && key.modifiers.contains(KeyModifiers::CONTROL) => {
                self.insert_edit_text("\n");
            }
            KeyCode::Enter => {
                match self.consume_paste_newline(Instant::now()) {
                    ControlDecision::Buffered => return Vec::new(),
                    ControlDecision::Insert => {
                        self.insert_edit_text("\n");
                        return Vec::new();
                    }
                    ControlDecision::NotPaste => {}
                }
                let text = self.composer.text().trim().to_owned();
                if text.is_empty() && !question.allow_skip {
                    return Vec::new();
                }
                let skipped = text.is_empty();
                let option_ids = if question.kind == InteractionQuestionKind::Multiple {
                    question
                        .options
                        .iter()
                        .filter(|choice| self.selected_multiple.contains(&choice.id))
                        .map(|choice| choice.id.clone())
                        .collect()
                } else {
                    Vec::new()
                };
                return self.complete_question(
                    InteractionAnswer {
                        question_id: question.question_id,
                        option_ids,
                        free_text: (!text.is_empty()).then_some(text),
                        skipped,
                    },
                    context,
                );
            }
            KeyCode::Char('c') if control => self.composer.clear(),
            KeyCode::Left => self.composer.move_left(),
            KeyCode::Right => self.composer.move_right(),
            KeyCode::Home => self.composer.home(),
            KeyCode::End => self.composer.end(),
            KeyCode::Backspace => self.composer.backspace(),
            KeyCode::Delete => self.composer.delete(),
            KeyCode::Char(ch) if !key.modifiers.contains(KeyModifiers::CONTROL) => {
                let now = Instant::now();
                let input = self.composer.observe_plain_char(ch, now);
                self.apply_paste_flush(input.flushed, now);
                if input.decision != CharDecision::Insert {
                    return Vec::new();
                }
                self.insert_edit_text(&ch.to_string());
            }
            _ => {}
        }
        Vec::new()
    }

    /// 中间题进入本地答案集；最后一题在服务端 ACK 前保留选择和文字，失败后可原位修正。
    fn complete_question(
        &mut self,
        answer: InteractionAnswer,
        context: ClarificationContext,
    ) -> Vec<UiAction> {
        if self.question_index + 1 < context.question_count {
            self.interaction_answers.push(answer);
            self.question_index += 1;
            self.selected_prompt_choice = 0;
            self.selected_multiple.clear();
            self.using_free_text = false;
            self.composer.clear();
            return Vec::new();
        }
        self.prompt_responded = true;
        let mut answers = self.interaction_answers.clone();
        answers.push(answer);
        vec![UiAction::RespondInteraction {
            thread_id: context.thread_id,
            interaction_id: context.interaction_id,
            expected_interaction_revision: context.expected_interaction_revision,
            operation_key: context.operation_key,
            answers,
        }]
    }

    /// 选择器回调绑定精确身份和字素范围，失败时保持原输入及焦点。
    fn insert_reference(
        &mut self,
        label: String,
        range: Option<(usize, usize)>,
        kind: UiReferenceKind,
    ) {
        let (start, end) = range.unwrap_or((self.composer.cursor(), self.composer.cursor()));
        let separator = self
            .composer
            .text()
            .graphemes(true)
            .nth(end)
            .is_none_or(|part| {
                part.chars()
                    .next()
                    .is_some_and(|ch| ch.is_alphanumeric() || ch == '_')
            });
        if !self
            .composer
            .replace_with_reference(start, end, &label, kind, separator)
        {
            self.snapshot.notice = Some("引用超出输入上限，草稿未改变".into());
            return;
        }
        self.panel = None;
        self.scheduled_file_search = None;
        self.active_query_id = None;
    }

    /// 输入超过固定预算时保持原草稿，并在同一状态行告诉用户没有接受该按键。
    fn insert_edit_text(&mut self, value: &str) {
        if !self.composer.insert_text(value) {
            self.snapshot.notice = Some("输入超过 32 KiB，草稿未改变".into());
        }
    }

    /// 发送立即腾出输入区；外部编辑只复制无结构引用的草稿，避免编辑器文本使引用身份失配。
    /// @author kongweiguang
    fn composer_key(&mut self, key: KeyEvent) -> Vec<UiAction> {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        let shift = key.modifiers.contains(KeyModifiers::SHIFT);
        let mut actions = Vec::new();
        match key.code {
            KeyCode::Esc
                if self.snapshot.local_operation.is_some()
                    && self.cancellation != CancellationState::Pending =>
            {
                self.cancellation = CancellationState::Pending;
                self.snapshot.notice = None;
                actions.push(UiAction::CancelLocalOperation);
            }
            KeyCode::Esc
                if self.snapshot.turn_state == TurnState::Working
                    && self.composer.is_empty()
                    && self.cancellation != CancellationState::Pending =>
            {
                self.cancellation = CancellationState::Pending;
                self.snapshot.notice = None;
                actions.push(UiAction::CancelTurn);
            }
            KeyCode::Char('c') if control => {
                if !self.composer.is_empty() {
                    self.composer.clear();
                    self.scheduled_file_search = None;
                } else if self.snapshot.turn_state == TurnState::Working
                    && self.cancellation != CancellationState::Pending
                {
                    self.cancellation = CancellationState::Pending;
                    self.snapshot.notice = None;
                    actions.push(UiAction::CancelTurn);
                } else if self.snapshot.turn_state != TurnState::Working {
                    let now = Instant::now();
                    if self
                        .exit_confirmation
                        .is_some_and(|at| now.duration_since(at) <= Duration::from_secs(1))
                    {
                        self.closed = true;
                        actions.push(UiAction::Quit);
                    } else {
                        self.exit_confirmation = Some(now);
                        self.snapshot.notice = Some("再次按 Ctrl+C 退出 Ja".to_owned());
                    }
                }
            }
            KeyCode::Char('d') if control && self.composer.is_empty() => {
                self.closed = true;
                actions.push(UiAction::Quit);
            }
            KeyCode::Char('r') if control => {
                self.open_picker(ChoiceKind::InputHistory);
                actions.extend(self.schedule_input_history_search(String::new(), Instant::now()));
            }
            KeyCode::Char('g') if control => {
                if self.composer.references().is_empty() {
                    actions.push(UiAction::EditDraft {
                        draft: self.composer.text().to_owned(),
                    });
                } else {
                    self.snapshot.notice =
                        Some("草稿含有文件或技能引用；请先移除引用，再按 Ctrl+G 编辑".into());
                }
            }
            KeyCode::Char('z') if control => self.composer.undo(),
            KeyCode::Char('y') if control => self.composer.redo(),
            KeyCode::Char('p') if control => {
                self.panel = Some(Panel::Choices {
                    kind: ChoiceKind::Attachments,
                    selected: 0,
                });
            }
            KeyCode::Up if control && self.snapshot.has_older_history => {
                actions.extend(self.request_older_history());
            }
            KeyCode::Char('o') if control => {
                self.rebuild_tool_choices();
                if self.tool_choices.is_empty() {
                    self.snapshot.notice = Some("当前会话尚无工具步骤".to_owned());
                } else {
                    self.open_picker(ChoiceKind::ToolSteps);
                }
            }
            KeyCode::Enter if shift || control => {
                self.insert_edit_text("\n");
                self.schedule_file_search(Instant::now());
            }
            KeyCode::Char('j') if control => {
                self.insert_edit_text("\n");
                self.schedule_file_search(Instant::now());
            }
            KeyCode::Enter => {
                match self.consume_paste_newline(Instant::now()) {
                    ControlDecision::Buffered => return actions,
                    ControlDecision::Insert => {
                        self.insert_edit_text("\n");
                        self.schedule_file_search(Instant::now());
                        return actions;
                    }
                    ControlDecision::NotPaste => {}
                }
                let mode = if self.snapshot.turn_state == TurnState::Working
                    && self.snapshot.local_operation.is_none()
                {
                    UiSubmitMode::Steer
                } else {
                    UiSubmitMode::Immediate
                };
                if let Some(action) = self.submit_or_continue(mode) {
                    actions.push(action);
                }
            }
            KeyCode::Esc => {
                self.panel = None;
                self.scheduled_file_search = None;
                self.active_query_id = None;
                self.reference_popup_dismissed = true;
            }
            KeyCode::Tab => {
                match self.consume_paste_tab(Instant::now()) {
                    ControlDecision::Buffered => return actions,
                    ControlDecision::Insert => {
                        self.insert_edit_text("\t");
                        self.schedule_file_search(Instant::now());
                        return actions;
                    }
                    ControlDecision::NotPaste => {}
                }
                let draft = self.composer.text().trim();
                if draft.starts_with('/') && !draft.chars().any(char::is_whitespace) {
                    self.panel = Some(Panel::Commands { selected: 0 });
                } else if let Some(query) = self.active_file_query() {
                    let query_id = self.start_immediate_search();
                    self.panel = Some(Panel::Choices {
                        kind: ChoiceKind::Files,
                        selected: 0,
                    });
                    actions.push(UiAction::SearchFiles {
                        query: query.query,
                        thread_id: self.snapshot.thread_id.clone(),
                        query_id,
                    });
                } else if let Some(query) = self.active_skill_query() {
                    let query_id = self.start_immediate_search();
                    self.panel = Some(Panel::Choices {
                        kind: ChoiceKind::Skills,
                        selected: 0,
                    });
                    actions.push(UiAction::SearchSkills {
                        query: query.query,
                        thread_id: self.snapshot.thread_id.clone(),
                        query_id,
                    });
                } else {
                    let mode = if self.snapshot.turn_state == TurnState::Working
                        && self.snapshot.local_operation.is_none()
                    {
                        UiSubmitMode::Queue
                    } else {
                        UiSubmitMode::Immediate
                    };
                    if let Some(action) = self.submit_or_continue(mode) {
                        actions.push(action);
                    }
                }
            }
            KeyCode::Left => {
                if control {
                    self.composer.move_word_left();
                } else {
                    self.composer.move_left();
                }
                self.schedule_file_search(Instant::now());
            }
            KeyCode::Right => {
                if control {
                    self.composer.move_word_right();
                } else {
                    self.composer.move_right();
                }
                self.schedule_file_search(Instant::now());
            }
            KeyCode::Up => self.composer.move_vertical(self.terminal_width, false),
            KeyCode::Down => self.composer.move_vertical(self.terminal_width, true),
            KeyCode::Home => {
                self.composer.home();
                self.schedule_file_search(Instant::now());
            }
            KeyCode::End => {
                self.composer.end();
                self.schedule_file_search(Instant::now());
            }
            KeyCode::Backspace => {
                if control {
                    self.composer.delete_word_left();
                } else {
                    self.composer.backspace();
                }
                self.schedule_file_search(Instant::now());
            }
            KeyCode::Delete => {
                if control {
                    self.composer.delete_word_right();
                } else {
                    self.composer.delete();
                }
                self.schedule_file_search(Instant::now());
            }
            KeyCode::Char(ch) if !control => {
                let now = Instant::now();
                let input = self.composer.observe_plain_char(ch, now);
                self.apply_paste_flush(input.flushed, now);
                if input.decision == CharDecision::Insert {
                    self.accept_composer_character(ch, now);
                }
            }
            _ => {}
        }
        actions
    }

    /// 普通消息立即搬出编辑区并显示待发送问题；完整缓冲用于拒绝恢复，未知回执继续锁住重复提交。
    fn submit_or_continue(&mut self, mode: UiSubmitMode) -> Option<UiAction> {
        if self.pending_submission.is_some()
            || self.snapshot.pending_prompt.is_some()
            || self.suspended_control_pending
        {
            return None;
        }
        let text = self.composer.text().to_owned();
        if text.trim().is_empty() {
            if self.snapshot.turn_state == TurnState::Failed
                && self.snapshot.continuation_available
                && !self.continuation_pending
            {
                self.continuation_pending = true;
                return Some(UiAction::ContinueReply);
            }
            return None;
        }
        if let Some(action) = parse_command(text.trim()) {
            if matches!(action, UiAction::AttachPaths { .. }) {
                self.pending_submission = Some(PendingSubmission::Attachment(text));
                self.scheduled_file_search = None;
                return Some(action);
            }
            if action == UiAction::Command(UiCommand::ForkThread) {
                let source_thread_id = self.snapshot.thread_id.clone().unwrap_or_default();
                self.fork_source_composers
                    .insert(source_thread_id.clone(), self.composer.clone());
                self.active_fork_source = Some(source_thread_id);
            }
            self.composer.clear();
            self.scheduled_file_search = None;
            if let UiAction::Command(command) = action {
                self.prepare_command(command);
            }
            if action == UiAction::Quit {
                self.closed = true;
            }
            return Some(action);
        }
        let submitted = std::mem::take(&mut self.composer);
        let references = submitted.references().to_vec();
        self.retain_message_submission(submitted);
        self.scheduled_file_search = None;
        self.snapshot.notice = None;
        Some(UiAction::Submit {
            text,
            references,
            mode,
        })
    }

    /// @author kongweiguang
    /// 需要先查询候选的命令会清空旧列表；fork 空缓存先标为加载，响应后才显示真正空历史。
    fn prepare_command(&mut self, command: UiCommand) {
        match command {
            UiCommand::ForkThread => self.open_picker(ChoiceKind::ForkReplies),
            UiCommand::ResumeThread => self.open_picker(ChoiceKind::Threads),
            UiCommand::SelectModel => self.open_picker(ChoiceKind::Models),
            UiCommand::SelectPermissions => self.open_picker(ChoiceKind::Permissions),
            UiCommand::Attach => {
                self.composer.set_text("/attach ");
            }
            UiCommand::Help => {
                self.panel = Some(Panel::Help {
                    scroll: DetailScroll::FromTop(0),
                })
            }
            _ => {}
        }
    }

    /// @author kongweiguang
    /// 工具列表只读已加载投影；Fork 清除陈旧候选并显示加载态直到 owner 返回本页。
    fn open_picker(&mut self, kind: ChoiceKind) {
        self.snapshot.notice = None;
        let selected = if kind == ChoiceKind::Models {
            self.snapshot
                .current_model_key
                .as_deref()
                .and_then(|id| {
                    self.snapshot
                        .model_choices
                        .iter()
                        .position(|choice| choice.id == id)
                })
                .unwrap_or(0)
        } else if kind == ChoiceKind::ToolSteps {
            self.tool_choices.len().saturating_sub(1)
        } else if kind == ChoiceKind::ForkReplies {
            self.snapshot.fork_reply_choices.len().saturating_sub(1)
        } else {
            0
        };
        match kind {
            ChoiceKind::Models | ChoiceKind::Permissions => {}
            ChoiceKind::Reasoning => {}
            ChoiceKind::Threads => {
                self.thread_query.clear();
                self.snapshot.thread_choices.clear();
                self.snapshot.thread_next_cursor = None;
            }
            ChoiceKind::InputHistory => {
                self.input_history_query.clear();
                self.input_history_texts.clear();
                self.snapshot.input_history_choices.clear();
                self.snapshot.input_history_next_cursor = None;
            }
            ChoiceKind::ForkReplies => {
                self.snapshot.fork_reply_choices.clear();
                self.snapshot.fork_reply_next_cursor = None;
                self.snapshot.fork_reply_loading = true;
                self.snapshot.fork_reply_load_failed = false;
            }
            ChoiceKind::Files => self.snapshot.file_choices.clear(),
            ChoiceKind::Skills => self.snapshot.skill_choices.clear(),
            ChoiceKind::Attachments | ChoiceKind::ToolSteps => {}
        }
        self.panel = Some(Panel::Choices { kind, selected });
    }

    /// 选择后回传 owner 给出的 ID；附件操作与文件引用选择保持不同语义。
    /// 工具预览先同步占住只读面板，使用失败后仍成立的标签以保留返回与重试边界。
    fn activate_choice(
        &mut self,
        kind: ChoiceKind,
        selected: usize,
        preview: bool,
    ) -> Vec<UiAction> {
        let Some(choice) = self.choices(kind).get(selected).cloned() else {
            return Vec::new();
        };
        if (kind != ChoiceKind::Threads || self.snapshot.thread_id.as_deref() == Some(&choice.id))
            && (kind != ChoiceKind::Models || choice.id != MODEL_SETTINGS_CHOICE_ID)
        {
            self.panel = None;
        }
        let action = match kind {
            ChoiceKind::Models if choice.id == MODEL_SETTINGS_CHOICE_ID => {
                UiAction::OpenModelSettings
            }
            ChoiceKind::Models => UiAction::SelectModel { id: choice.id },
            ChoiceKind::Reasoning => UiAction::SelectReasoning { id: choice.id },
            ChoiceKind::Permissions => UiAction::SelectPermission { id: choice.id },
            ChoiceKind::Threads => UiAction::SelectThread { id: choice.id },
            ChoiceKind::ForkReplies => UiAction::ForkReply { item_id: choice.id },
            ChoiceKind::InputHistory => {
                if let Some((_, text, truncated)) = self
                    .input_history_texts
                    .iter()
                    .find(|(id, _, _)| id == &choice.id)
                {
                    if *truncated {
                        self.snapshot.notice = Some("这条历史输入过长，无法完整恢复".to_owned());
                        self.panel = Some(Panel::Choices { kind, selected });
                        return Vec::new();
                    }
                    if !self.composer.set_text(text) {
                        self.snapshot.notice = Some("历史输入超过草稿上限".to_owned());
                        self.panel = Some(Panel::Choices { kind, selected });
                    }
                }
                return vec![self.cancel_input_history_search()];
            }
            ChoiceKind::Files => UiAction::SelectFileReference { id: choice.id },
            ChoiceKind::Skills => UiAction::SelectSkillReference { id: choice.id },
            ChoiceKind::Attachments if preview => UiAction::PreviewAttachment { id: choice.id },
            ChoiceKind::Attachments => UiAction::PreviewAttachment { id: choice.id },
            ChoiceKind::ToolSteps => {
                self.tool_detail_return_id = Some(choice.id.clone());
                // 立即占住只读面板；读取期间 Esc 仍返回列表，迟到回读只能更新尚未关闭的同一详情。
                // 预览在失败时会保留，因此正文不永久声称读取仍在进行。
                let preview = self
                    .snapshot
                    .timeline
                    .iter()
                    .find(|entry| entry.id == choice.id)
                    .map(|entry| entry.detail.as_deref().unwrap_or(&entry.text))
                    .unwrap_or_default();
                self.panel = Some(Panel::Details {
                    entry_id: choice.id.clone(),
                    title: choice.label,
                    body: format!("以下为安全预览；读取成功后更新工具详情。\n\n{preview}"),
                    scroll: DetailScroll::FromTop(0),
                    return_to_pending: false,
                    copy_ready: false,
                });
                UiAction::OpenDetails {
                    entry_id: choice.id,
                }
            }
        };
        vec![action]
    }

    /// 附件删除始终用独立动作表达，controller 负责 discard 与列表更新。
    fn remove_attachment(&mut self, selected: usize) -> Vec<UiAction> {
        self.choices(ChoiceKind::Attachments)
            .get(selected)
            .map(|choice| {
                vec![UiAction::RemoveAttachment {
                    id: choice.id.clone(),
                }]
            })
            .unwrap_or_default()
    }

    /// 列表更新或缩小时修正焦点，确保候选索引始终有效。
    fn set_choice_selection(&mut self, kind: ChoiceKind, selected: usize) {
        let selected = selected.min(self.choices(kind).len().saturating_sub(1));
        self.panel = Some(Panel::Choices { kind, selected });
    }

    /// 候选刷新前取出稳定 ID；只靠行号会把重排后的另一模型或权限误当成用户所选。
    fn selected_choice_id(&self, kind: ChoiceKind) -> Option<String> {
        match self.panel.as_ref() {
            Some(Panel::Choices {
                kind: active,
                selected,
            }) if *active == kind => self
                .choices(kind)
                .get(*selected)
                .map(|choice| choice.id.clone()),
            _ => None,
        }
    }

    /// 高风险偏好选择按 ID 恢复，原项消失时关闭面板并要求重新选择。
    fn reconcile_choice_selection(&mut self, kind: ChoiceKind, selected_id: Option<&str>) {
        let Some(Panel::Choices {
            kind: active,
            selected,
        }) = self.panel.clone()
        else {
            return;
        };
        if active != kind {
            return;
        }
        if let Some(id) = selected_id {
            if let Some(index) = self.choices(kind).iter().position(|choice| choice.id == id) {
                self.set_choice_selection(kind, index);
                return;
            }
            if matches!(
                kind,
                ChoiceKind::Models | ChoiceKind::Reasoning | ChoiceKind::Permissions
            ) {
                self.panel = None;
                self.snapshot.notice = Some("可选项已更新，请重新打开列表选择".to_owned());
                return;
            }
        }
        self.set_choice_selection(kind, selected);
    }

    /// 新候选只影响对应面板，不在 reducer 里重排 controller 提供的权威顺序。
    fn clamp_choice_selection(&mut self, kind: ChoiceKind) {
        if let Some(Panel::Choices {
            kind: active,
            selected,
        }) = self.panel.clone()
            && active == kind
        {
            self.set_choice_selection(kind, selected);
        }
    }

    /// 同会话快照刷新只夹紧当前焦点，不能关闭选择器或把多行草稿光标重置到末尾。
    fn clamp_visible_panel(&mut self, selected_id: Option<&str>) {
        match self.panel.clone() {
            Some(Panel::Choices { kind, .. }) => self.reconcile_choice_selection(kind, selected_id),
            Some(Panel::Commands { selected }) => {
                let count = matching_commands(self.composer.text()).len();
                self.panel = Some(Panel::Commands {
                    selected: selected.min(count.saturating_sub(1)),
                });
            }
            _ => {}
        }
    }

    /// renderer 与键盘选择读取同一份列表，避免视觉顺序和实际动作错位。
    pub(super) fn choices(&self, kind: ChoiceKind) -> &[UiChoice] {
        match kind {
            ChoiceKind::Models => &self.snapshot.model_choices,
            ChoiceKind::Reasoning => &self.snapshot.reasoning_choices,
            ChoiceKind::Permissions => &self.snapshot.permission_choices,
            ChoiceKind::Threads => &self.snapshot.thread_choices,
            ChoiceKind::InputHistory => &self.snapshot.input_history_choices,
            ChoiceKind::ForkReplies => &self.snapshot.fork_reply_choices,
            ChoiceKind::Files => &self.snapshot.file_choices,
            ChoiceKind::Skills => &self.snapshot.skill_choices,
            ChoiceKind::Attachments => &self.snapshot.attachments,
            ChoiceKind::ToolSteps => &self.tool_choices,
        }
    }

    /// 工具选择器只缓存已加载的安全身份；刷新按 ID 保留焦点，旧步骤移出窗口时不误选相邻操作。
    fn rebuild_tool_choices(&mut self) {
        let selected_id = self.selected_choice_id(ChoiceKind::ToolSteps);
        self.tool_choices = self
            .snapshot
            .timeline
            .iter()
            .filter_map(|entry| {
                let TimelineKind::Tool { action, target, .. } = &entry.kind else {
                    return None;
                };
                Some(UiChoice {
                    id: entry.id.clone(),
                    label: format!("{action} {target}"),
                    detail: None,
                })
            })
            .collect();
        if let Some(id) = selected_id {
            if let Some(selected) = self.tool_choices.iter().position(|choice| choice.id == id) {
                self.set_choice_selection(ChoiceKind::ToolSteps, selected);
            } else {
                self.panel = None;
                self.snapshot.notice = Some("工具步骤已更新，请重新打开列表选择".to_owned());
            }
        }
    }

    /// renderer 只读取当前 panel，确认动作仍由 reducer 产生。
    pub(super) fn panel(&self) -> Option<&Panel> {
        self.panel.as_ref()
    }

    /// 打开 controller 已发出读取的共享设置面板，只遮罩 panel 而完整保留 Composer 与引用状态。
    fn open_model_settings(&mut self, operation_id: String, standalone: bool) {
        if self.model_settings.is_some() {
            return;
        }
        self.settings_return_panel = self.panel.take();
        self.model_settings = Some(ModelSettingsState::open(operation_id, standalone));
        self.settings_start_pending = false;
        self.panel = Some(Panel::ModelSettings);
    }

    /// 面板的副作用先还原为 UiAction；请求与信任变更仍由 controller 执行。
    fn apply_model_settings_effect(&mut self, effect: ModelSettingsEffect) -> Vec<UiAction> {
        match effect {
            ModelSettingsEffect::None => Vec::new(),
            ModelSettingsEffect::Request(request) => vec![UiAction::ModelSettings(request)],
            ModelSettingsEffect::Close { start_conversation } => {
                if start_conversation {
                    if let Some(settings) = self.model_settings.as_mut() {
                        settings.starting_conversation();
                    }
                    self.settings_start_pending = true;
                } else {
                    self.close_model_settings(true);
                }
                vec![UiAction::CloseModelSettings { start_conversation }]
            }
            ModelSettingsEffect::Trust { trusted } => {
                if !trusted {
                    self.settings_start_pending = false;
                    let returned_to_settings = self
                        .model_settings
                        .as_mut()
                        .is_some_and(ModelSettingsState::trust_denied);
                    if !returned_to_settings {
                        self.close_model_settings(true);
                    }
                }
                vec![UiAction::ConfirmModelSettingsWorkspaceTrust { trusted }]
            }
        }
    }

    /// 关闭覆盖层时先擦除凭据，再选择恢复原面板或将焦点交给新会话。
    fn close_model_settings(&mut self, restore_return_panel: bool) {
        if let Some(settings) = self.model_settings.as_mut() {
            settings.clear_secrets();
        }
        self.model_settings = None;
        self.panel = if restore_return_panel {
            self.settings_return_panel.take()
        } else {
            None
        };
        if !restore_return_panel {
            self.settings_return_panel = None;
        }
    }

    /// Renderer 仅获得无凭据正文的展示投影。
    pub(super) fn model_settings_view(&self) -> Option<super::model_settings::ModelSettingsView> {
        self.model_settings
            .as_ref()
            .map(ModelSettingsState::view_for_render)
    }

    /// 面板拥有全部按键，F5 和 Esc 不得被通用快照刷新或 Composer 路径截获。
    fn model_settings_key(&mut self, key: KeyEvent) -> Vec<UiAction> {
        let has_thread = self.snapshot.thread_id.is_some();
        let effect = self
            .model_settings
            .as_mut()
            .map(|settings| settings.handle_key(key, has_thread));
        effect.map_or_else(Vec::new, |effect| self.apply_model_settings_effect(effect))
    }

    /// 线程搜索词只用于选择器展示；权威结果仍必须由 Java 返回。
    pub(super) fn thread_query(&self) -> &str {
        &self.thread_query
    }

    /// 历史搜索词只驱动服务端查询，原草稿在选择前保持原样。
    pub(super) fn input_history_query(&self) -> &str {
        &self.input_history_query
    }

    /// renderer 获得当前澄清题焦点与勾选集合，不可直接改动最终答案。
    pub(crate) fn prompt_view(
        &self,
    ) -> Option<(&PendingPrompt, usize, usize, &HashSet<String>, bool)> {
        self.snapshot.pending_prompt.as_ref().map(|prompt| {
            (
                prompt,
                self.question_index,
                self.selected_prompt_choice,
                &self.selected_multiple,
                self.using_free_text,
            )
        })
    }

    /// 提交锁只表示回执尚未核实，不能把等待内容继续显示成编辑草稿。
    pub(crate) fn has_pending_submission(&self) -> bool {
        self.pending_submission.is_some()
    }

    /// 恢复或取消回执待核实期间隐藏 F6/F7 重试入口，并避免重复提交草稿。
    pub(crate) fn suspended_control_pending(&self) -> bool {
        self.suspended_control_pending
    }

    /// Esc 只有空输入且后台仍工作时才可重试取消，底栏提示必须与按键入口一致。
    pub(crate) fn can_retry_cancellation(&self) -> bool {
        self.cancellation == CancellationState::Retry
            && self.snapshot.turn_state == TurnState::Working
            && self.composer.is_empty()
    }

    /// 待回执消息只在可重绘区域呈现；未接纳的输入不能提前进入不可撤回的原生历史。
    pub(crate) fn pending_message_text(&self) -> Option<&str> {
        match self.pending_submission.as_ref() {
            Some(PendingSubmission::Message { composer, .. }) => Some(composer.text()),
            _ => None,
        }
    }

    /// 只对账发送后新增且正文完全一致的问题；原回执仍待核实时仅隐藏副本，不释放发送锁。
    pub(crate) fn visible_pending_message_text(&self) -> Option<&str> {
        let Some(PendingSubmission::Message {
            composer,
            previous_user_id,
        }) = &self.pending_submission
        else {
            return None;
        };
        let latest_user = self
            .snapshot
            .timeline
            .iter()
            .rev()
            .find(|entry| entry.kind == TimelineKind::User);
        if latest_user.is_some_and(|entry| {
            Some(entry.id.as_str()) != previous_user_id.as_deref() && entry.text == composer.text()
        }) {
            return None;
        }
        Some(composer.text())
    }

    /// 发送前记录一个既有问题身份，复用同一待提交状态，不通过相似正文猜测或另建历史账本。
    fn retain_message_submission(&mut self, composer: Composer) {
        self.transcript.get_mut().follow_latest();
        let previous_user_id = self
            .snapshot
            .timeline
            .iter()
            .rev()
            .find(|entry| entry.kind == TimelineKind::User)
            .map(|entry| entry.id.clone());
        self.pending_submission = Some(PendingSubmission::Message {
            composer: Box::new(composer),
            previous_user_id,
        });
    }

    /// UI 仅展示审批/澄清已提交而未获权威基线确认，不将等待态解释成成功。
    pub(crate) fn prompt_submission_pending(&self) -> bool {
        self.prompt_responded
    }

    /// 回答已锁定且出现操作反馈时允许只读核实，普通的提交等待仍显示进行中。
    pub(crate) fn prompt_response_needs_refresh(&self) -> bool {
        self.prompt_responded
            && self.snapshot.pending_prompt.is_some()
            && self.snapshot.notice.is_some()
    }

    /// 输入期间保存最后一次文件查询，并在停顿后由 bridge 投递给 controller。
    fn schedule_file_search(&mut self, now: Instant) {
        self.reference_popup_dismissed = false;
        self.query_sequence = self.query_sequence.wrapping_add(1);
        let query_id = self.query_sequence;
        self.scheduled_file_search = self
            .active_file_query()
            .map(|query| (query.query, query_id, now))
            .or_else(|| {
                self.active_skill_query()
                    .map(|query| (format!("${}", query.query), query_id, now))
            });
        self.active_query_id = self.scheduled_file_search.as_ref().map(|(_, id, _)| *id);
    }

    /// Tab 的即时查询替代待发 debounce，后续响应只能命中这个最新版本。
    fn start_immediate_search(&mut self) -> u64 {
        self.query_sequence = self.query_sequence.wrapping_add(1);
        self.active_query_id = Some(self.query_sequence);
        self.scheduled_file_search = None;
        self.query_sequence
    }

    /// 仅识别 token 起始的 @，邮件地址中的 @ 不会触发工作区搜索。
    fn active_file_query(&self) -> Option<ActiveFileQuery> {
        self.active_reference_query('@')
    }

    /// `$` 只在词首触发技能建议；写 Shell 变量时 Esc 可关闭候选而不改动原文。
    fn active_skill_query(&self) -> Option<ActiveFileQuery> {
        self.active_reference_query('$')
    }

    /// 文件与技能共享字素安全的 token 定位，插入时仅替换当前光标前的活动 token。
    fn active_reference_query(&self, marker: char) -> Option<ActiveFileQuery> {
        let value = self.composer.text();
        let prefix = prefix_at_grapheme(value, self.composer.cursor());
        let at = prefix.rfind(marker)?;
        if at > 0 && !prefix[..at].chars().next_back()?.is_whitespace() {
            return None;
        }
        let query = &prefix[at + 1..];
        if query.chars().any(char::is_whitespace) {
            return None;
        }
        Some(ActiveFileQuery {
            start: prefix[..at].graphemes(true).count(),
            end: self.composer.cursor(),
            query: query.to_owned(),
        })
    }

    /// 切换公开流先提交前段尾部，再接续新类别；各自保留摘要，不把过程拖到最终回答后。
    fn upsert_entry(&mut self, entry: TimelineEntry) {
        let entry = sanitize_entry(entry);
        let live_entry_id = is_live_stream_entry(&entry).then(|| entry.id.clone());
        if is_live_stream_entry(&entry) && !self.stream_progress.contains_key(&entry.id) {
            self.flush_other_stream_tails(&entry.id);
            self.flush_stable_prefix();
        }
        if let Some(existing) = self
            .snapshot
            .timeline
            .iter_mut()
            .find(|row| row.id == entry.id)
        {
            *existing = entry;
        } else {
            self.snapshot.timeline.push(entry.clone());
        }
        if let Some(entry_id) = live_entry_id {
            self.begin_stream(&entry_id);
            self.flush_stream_pending(&entry_id, false);
        }
        self.flush_stable_prefix();
    }

    /// 公开增量按来源顺序接续；仅 live 身份归档流片段，普通条目仍保留原有安全追加语义。
    fn append_stream_delta(&mut self, entry_id: &str, delta: &str) {
        let Some(index) = self
            .snapshot
            .timeline
            .iter()
            .position(|entry| entry.id == entry_id)
        else {
            return;
        };
        let live = is_live_stream_entry(&self.snapshot.timeline[index]);
        if live {
            self.flush_other_stream_tails(entry_id);
            if !self.stream_progress.contains_key(entry_id) {
                self.flush_stable_prefix();
                self.begin_stream(entry_id);
            }
        }
        let delta = sanitize_source(delta, usize::MAX, true);
        self.snapshot.timeline[index].text.push_str(&delta);
        if live {
            self.flush_stream_pending(entry_id, false);
        }
    }

    /// 两类公开流共用 Markdown 与摘要账本；类别随稳定身份冻结，避免同文摘要吞掉回答。
    fn begin_stream(&mut self, entry_id: &str) {
        let Some(kind) = self
            .snapshot
            .timeline
            .iter()
            .find(|entry| entry.id == entry_id)
            .and_then(stream_kind)
        else {
            return;
        };
        let turn_id = live_turn_id(entry_id).unwrap_or_default();
        self.stream_progress
            .entry(entry_id.to_owned())
            .or_insert_with(|| StreamProgress {
                source_key: format!("{entry_id}:{}", self.next_stream_output_id),
                turn_id,
                kind,
                committed_bytes: 0,
                committed_hash: Sha256::new(),
                first_fragment: true,
                layout: MarkdownStream::default(),
            });
    }

    /// 原生历史不可回插；切换类别先排空已有尾部，但保留其累计摘要供后续 delta 与终态对账。
    fn flush_other_stream_tails(&mut self, entry_id: &str) {
        for id in self.ordered_stream_ids() {
            if id != entry_id {
                self.flush_stream_pending(&id, true);
            }
        }
    }

    /// 多个公开流按可见时间线收口，避免 HashMap 的随机遍历把思考放到最终回答后。
    fn ordered_stream_ids(&self) -> Vec<String> {
        let mut ids = self.stream_progress.keys().cloned().collect::<Vec<_>>();
        ids.sort_by_key(|id| {
            (
                self.snapshot
                    .timeline
                    .iter()
                    .position(|entry| entry.id == *id)
                    .unwrap_or(usize::MAX),
                id.clone(),
            )
        });
        ids
    }

    /// 同一帧只归档已确定的区块，源字节摘要与未闭合尾部各自保留，避免分片改变正文语义。
    fn flush_stream_pending(&mut self, entry_id: &str, flush_all: bool) {
        let Some(mut progress) = self.stream_progress.remove(entry_id) else {
            return;
        };
        let Some(entry_index) = self
            .snapshot
            .timeline
            .iter()
            .position(|entry| entry.id == entry_id)
        else {
            self.stream_progress.insert(entry_id.to_owned(), progress);
            return;
        };
        let tail = std::mem::take(&mut self.snapshot.timeline[entry_index].text);
        let width = self.terminal_width.saturating_sub(2).max(1);
        let (consumed, pieces) = progress.layout.consume(&tail, width, flush_all);
        let source_start = progress.committed_bytes;
        progress.committed_hash.update(&tail.as_bytes()[..consumed]);
        progress.committed_bytes = progress.committed_bytes.saturating_add(consumed);
        self.snapshot.timeline[entry_index].text = tail[consumed..].to_owned();
        self.queue_stream_pieces(&mut progress, pieces, &tail[..consumed], source_start);
        self.stream_progress.insert(entry_id.to_owned(), progress);
    }

    /// 结束一个流段时排空末行并记录摘要，供后续权威快照确认持久消息身份。
    fn finalize_stream_with_tail(
        &mut self,
        entry_id: &str,
        mut progress: StreamProgress,
        tail: String,
    ) -> StreamReceipt {
        let width = self.terminal_width.saturating_sub(2).max(1);
        let (consumed, pieces) = progress.layout.finish(&tail, width);
        let source_start = progress.committed_bytes;
        progress.committed_hash.update(&tail.as_bytes()[..consumed]);
        progress.committed_bytes = progress.committed_bytes.saturating_add(consumed);
        self.queue_stream_pieces(&mut progress, pieces, &tail[..consumed], source_start);
        let receipt = StreamReceipt {
            turn_id: progress.turn_id,
            kind: progress.kind,
            byte_len: progress.committed_bytes,
            digest: progress.committed_hash.finalize().into(),
        };
        self.remember_stream_receipt(receipt.clone());
        self.mark_committed(entry_id);
        self.snapshot.timeline.retain(|entry| entry.id != entry_id);
        receipt
    }

    /// 已确定片段移入同一对话文档；原始流偏移贯穿开放尾部与结算片段，缩放不会改变阅读点。
    fn queue_stream_pieces(
        &mut self,
        progress: &mut StreamProgress,
        pieces: Vec<MarkdownStreamPiece>,
        source: &str,
        source_start: usize,
    ) {
        self.transcript.get_mut().register_source(
            progress.source_key.clone(),
            source_start,
            source.to_owned(),
        );
        let mut source_cursor = 0;
        for piece in pieces {
            let first = progress.first_fragment;
            if piece.presentation != StreamPresentation::Spacer {
                progress.first_fragment = false;
            }
            let id = format!("stream-output:{}", self.next_stream_output_id);
            self.next_stream_output_id = self.next_stream_output_id.saturating_add(1);
            let offset = if piece.text.is_empty() {
                source_cursor
            } else {
                source[source_cursor..]
                    .find(&piece.text)
                    .map_or(source_cursor, |index| source_cursor + index)
            };
            source_cursor = offset.saturating_add(piece.text.len()).min(source.len());
            while !source.is_char_boundary(source_cursor) {
                source_cursor = source_cursor.saturating_sub(1);
            }
            if self.mark_committed(&id) {
                self.transcript.get_mut().append(
                    TimelineEntry {
                        id,
                        kind: TimelineKind::AssistantFragment {
                            first,
                            presentation: piece.presentation,
                            reasoning: progress.kind == StreamKind::Reasoning,
                        },
                        text: piece.text,
                        detail: None,
                        status: Some(TimelineStatus::Complete),
                    },
                    progress.source_key.clone(),
                    source_start.saturating_add(offset),
                );
            }
        }
    }

    /// 摘要与当前 Thread 身份一起保留，长任务不能因显示缓存预算丢失去重证据。
    fn remember_stream_receipt(&mut self, receipt: StreamReceipt) {
        self.stream_receipts.push_back(receipt);
    }

    /// 重试或进入审批时按展示顺序结束该 Turn 的公开尾部，正文与摘要都只收口一次。
    fn finalize_turn_streams(&mut self, turn_id: &str) {
        let ids = self
            .ordered_stream_ids()
            .into_iter()
            .filter(|id| {
                self.stream_progress
                    .get(id)
                    .is_some_and(|progress| progress.turn_id == turn_id)
            })
            .collect::<Vec<_>>();
        for id in ids {
            self.finalize_stream(&id);
        }
    }

    /// 终态、切换或退出按原顺序排空公开尾部，不能因摘要与正文并存而随机交换位置。
    fn finalize_all_streams(&mut self) {
        let ids = self.ordered_stream_ids();
        for id in ids {
            self.finalize_stream(&id);
        }
    }

    /// 消费尚未归档到 transcript 的尾部并记录摘要，终态快照无需重复展示这段正文。
    fn finalize_stream(&mut self, entry_id: &str) {
        let Some(progress) = self.stream_progress.remove(entry_id) else {
            return;
        };
        let tail = self
            .snapshot
            .timeline
            .iter()
            .find(|entry| entry.id == entry_id)
            .map_or_else(String::new, |entry| entry.text.clone());
        self.finalize_stream_with_tail(entry_id, progress, tail);
    }

    /// 终态释放 liveStream 后按原顺序核对同类别正文，补齐尚未通知的尾部并保留后续去重凭据。
    fn reconcile_snapshot_streams(&mut self, snapshot: &mut UiSnapshot) {
        let live = snapshot
            .timeline
            .iter()
            .filter(|entry| is_live_stream_entry(entry))
            .map(|entry| (entry.id.clone(), (entry.text.clone(), entry.status)))
            .collect::<HashMap<_, _>>();
        let existing = self.ordered_stream_ids();
        for id in existing {
            let Some(progress) = self.stream_progress.remove(&id) else {
                continue;
            };
            let Some((full_text, status)) = live.get(&id) else {
                let old_tail = self
                    .snapshot
                    .timeline
                    .iter()
                    .find(|entry| entry.id == id)
                    .map_or_else(String::new, |entry| entry.text.clone());
                let tail = self
                    .match_persisted_stream_tail(snapshot, &progress, &old_tail)
                    .unwrap_or(old_tail);
                self.finalize_stream_with_tail(&id, progress, tail);
                continue;
            };
            // 已归档摘要只核对稳定前缀；未归档尾部也必须连续，落后快照不能擦除已显示字节。
            let old_tail = self
                .snapshot
                .timeline
                .iter()
                .find(|entry| entry.id == id)
                .map_or_else(String::new, |entry| entry.text.clone());
            let matching_tail = matching_stream_tail(&progress, full_text).and_then(|tail| {
                if tail.starts_with(&old_tail) {
                    Some(tail)
                } else if *status == Some(TimelineStatus::Running) && old_tail.starts_with(&tail) {
                    Some(old_tail.clone())
                } else {
                    None
                }
            });
            if let Some(tail) = matching_tail {
                if *status == Some(TimelineStatus::Running) {
                    if let Some(entry) = snapshot.timeline.iter_mut().find(|entry| entry.id == id) {
                        entry.text = tail;
                    }
                    self.stream_progress.insert(id, progress);
                } else {
                    self.finalize_stream_with_tail(&id, progress, tail);
                    if let Some(entry) = snapshot.timeline.iter_mut().find(|entry| entry.id == id) {
                        entry.text.clear();
                    }
                }
            } else {
                self.finalize_stream_with_tail(&id, progress, old_tail);
                self.forget_committed(&id);
            }
        }
    }

    /// 只接续同 Turn、同类别且前缀摘要匹配的唯一持久消息；正文与思考同文也不能相互替代。
    fn match_persisted_stream_tail(
        &self,
        snapshot: &UiSnapshot,
        progress: &StreamProgress,
        old_tail: &str,
    ) -> Option<String> {
        if progress.committed_bytes == 0 && old_tail.is_empty() {
            return None;
        }
        let mut matches = snapshot
            .timeline
            .iter()
            .filter(|entry| {
                !is_live_stream_entry_by_id(&entry.id)
                    && !self.is_committed(&entry.id)
                    && stream_kind(entry) == Some(progress.kind)
                    && is_stable_entry(entry)
                    && snapshot.turn_id_by_entry_id.get(&entry.id) == Some(&progress.turn_id)
            })
            .filter_map(|entry| matching_stream_tail(progress, &entry.text))
            .filter(|tail| tail.starts_with(old_tail));
        let tail = matches.next()?;
        matches.next().is_none().then_some(tail)
    }

    /// 按 Turn、类别和摘要匹配持久条目；公开思考实时输出后不会在最终回答后再补一份。
    fn suppress_streamed_entries(&mut self, snapshot: &mut UiSnapshot) {
        for entry in &snapshot.timeline {
            let Some(kind) = stream_kind(entry) else {
                continue;
            };
            if !is_stable_entry(entry) {
                continue;
            }
            let Some(turn_id) = snapshot.turn_id_by_entry_id.get(&entry.id) else {
                continue;
            };
            let digest: [u8; 32] = Sha256::digest(entry.text.as_bytes()).into();
            let receipt_index = self.stream_receipts.iter().position(|receipt| {
                receipt.turn_id == *turn_id
                    && receipt.kind == kind
                    && receipt.byte_len == entry.text.len()
                    && receipt.digest == digest
            });
            if let Some(index) = receipt_index {
                self.stream_receipts.remove(index);
                self.mark_committed(&entry.id);
            }
        }
    }

    /// 仅首次发现流时提交前段尾部；已知流的快照只是对账，不能把旧摘要重放当成类别切换。
    fn sync_snapshot_streams(&mut self) {
        let streams = self
            .snapshot
            .timeline
            .iter()
            .filter(|entry| is_live_stream_entry(entry))
            .map(|entry| (entry.id.clone(), entry.status))
            .collect::<Vec<_>>();
        for (id, status) in streams {
            if self.is_committed(&id) {
                continue;
            }
            if !self.stream_progress.contains_key(&id) {
                self.begin_stream(&id);
                self.flush_other_stream_tails(&id);
            }
            self.flush_stream_pending(&id, status != Some(TimelineStatus::Running));
            if status != Some(TimelineStatus::Running) {
                self.finalize_stream(&id);
            }
        }
    }

    /// 检查权威基线是否包含活动助手正文，以便初始化时先写入其前序历史。
    fn has_live_stream_entries(&self) -> bool {
        self.snapshot.timeline.iter().any(is_live_stream_entry)
    }

    /// 只提交稳定前缀，避免后到的工具终态跑到活动助手正文之前。
    fn flush_stable_prefix(&mut self) {
        let entries = self
            .snapshot
            .timeline
            .iter()
            .take_while(|entry| self.is_committed(&entry.id) || is_stable_entry(entry))
            .filter(|entry| !self.is_committed(&entry.id))
            .cloned()
            .collect::<Vec<_>>();
        for entry in entries {
            if self.mark_committed(&entry.id) {
                let source_key = entry.id.clone();
                self.transcript.get_mut().append(entry, source_key, 0);
                self.clear_committed_assistant_text();
            }
        }
    }

    /// 身份账本与当前会话正文一起存活，避免浏览长历史时淘汰 ID 导致重连重复。
    fn mark_committed(&mut self, id: &str) -> bool {
        let key = self.committed_key(id);
        self.committed_ids.insert(key)
    }

    /// 流摘要不匹配时允许新代际显示，已归档正文仍留在当前会话文档中。
    fn forget_committed(&mut self, id: &str) {
        let key = self.committed_key(id);
        self.committed_ids.remove(&key);
    }

    /// 消息 ID 常按 thread 局部生成，因此去重键同时包含当前会话身份。
    fn committed_key(&self, id: &str) -> String {
        format!("{}\0{id}", self.snapshot.thread_id.as_deref().unwrap_or(""))
    }

    /// 按当前 thread 检查稳定消息，切换会话不会把另一个会话的同名 ID 隐藏。
    fn is_committed(&self, id: &str) -> bool {
        self.committed_ids.contains(&self.committed_key(id))
    }

    /// @author kongweiguang
    /// 取消选择器时还原来源 Composer 的完整快照，同时保留按 Thread 缓存供稍后切回恢复。
    fn restore_fork_draft(&mut self) {
        if let Some(source_thread_id) = self.active_fork_source.take()
            && let Some(composer) = self.fork_source_composers.get(&source_thread_id)
        {
            self.composer = composer.clone();
        }
        self.panel = None;
    }

    /// 新交互暂存原任务草稿和字素光标；结束后还原，切换请求时清理旧 CAS 答案。
    fn reset_prompt(&mut self, was_pending: bool) {
        match (was_pending, self.snapshot.pending_prompt.is_some()) {
            (false, true) => {
                self.deferred_composer = Some(std::mem::take(&mut self.composer));
            }
            (true, false) => {
                self.composer = self.deferred_composer.take().unwrap_or_default();
            }
            (true, true) => self.composer.clear(),
            (false, false) => {}
        }
        self.question_index = 0;
        self.selected_prompt_choice = 0;
        self.selected_multiple.clear();
        self.interaction_answers.clear();
        self.using_free_text = false;
        self.prompt_responded = false;
        self.panel = self
            .snapshot
            .pending_prompt
            .as_ref()
            .map(|_| Panel::Pending);
    }
}

/// Codex 列表的数字快捷键只识别当前可见的 1–9 号项，Ctrl/Alt 组合交给宿主终端。
fn number_shortcut(key: KeyEvent, count: usize) -> Option<usize> {
    if key
        .modifiers
        .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT)
    {
        return None;
    }
    let KeyCode::Char(ch) = key.code else {
        return None;
    };
    let number = ch.to_digit(10)? as usize;
    (number > 0 && number <= count).then_some(number - 1)
}

/// 将字素光标转换为字节边界，文件查询只看当前光标前的文本。
fn prefix_at_grapheme(value: &str, grapheme: usize) -> &str {
    use unicode_segmentation::UnicodeSegmentation;
    value
        .grapheme_indices(true)
        .nth(grapheme)
        .map_or(value, |(byte, _)| &value[..byte])
}

/// 冷启动同类别只保留持久条目；已有流进度留下完整 synthetic 尾部供同 Turn 摘要对账。
fn remove_unstarted_duplicate_streams(
    snapshot: &mut UiSnapshot,
    stream_progress: &HashMap<String, StreamProgress>,
) {
    let duplicates = snapshot
        .timeline
        .iter()
        .filter(|entry| {
            is_live_stream_entry(entry)
                && entry.status == Some(TimelineStatus::Complete)
                && !stream_progress.contains_key(&entry.id)
        })
        .filter(|stream| {
            let Some(turn_id) = snapshot.turn_id_by_entry_id.get(&stream.id) else {
                return false;
            };
            snapshot.timeline.iter().any(|entry| {
                !is_live_stream_entry_by_id(&entry.id)
                    && stream_kind(entry) == stream_kind(stream)
                    && is_stable_entry(entry)
                    && snapshot.turn_id_by_entry_id.get(&entry.id) == Some(turn_id)
                    && entry.text == stream.text
            })
        })
        .map(|entry| entry.id.clone())
        .collect::<HashSet<_>>();
    snapshot
        .timeline
        .retain(|entry| !duplicates.contains(&entry.id));
    snapshot
        .turn_id_by_entry_id
        .retain(|id, _| !duplicates.contains(id));
}

/// 临时问题与运行中公开摘要留在重绘区，稳定内容沿用原归档边界，避免半截摘要被当成完整消息。
fn is_stable_entry(entry: &TimelineEntry) -> bool {
    match &entry.kind {
        TimelineKind::User => entry.status != Some(TimelineStatus::Running),
        TimelineKind::Commentary
        | TimelineKind::AssistantFragment { .. }
        | TimelineKind::WorkSummary { .. } => true,
        TimelineKind::ReasoningSummary => entry.status != Some(TimelineStatus::Running),
        TimelineKind::Assistant | TimelineKind::FinalAnswer | TimelineKind::Tool { .. } => {
            matches!(
                entry.status,
                Some(super::model::TimelineStatus::Complete | super::model::TimelineStatus::Failed)
            )
        }
    }
}

/// 公开正文与摘要都按 Turn 和首段序号接续，其它 Commentary 不参与流去重。
fn is_live_stream_entry(entry: &TimelineEntry) -> bool {
    is_live_stream_entry_by_id(&entry.id)
        && stream_kind(entry).is_some()
        && matches!(
            entry.status,
            Some(TimelineStatus::Running | TimelineStatus::Complete)
        )
}

/// 流身份前缀与稳定 Thread message ID 分开，快照重放时才能验证已输出前缀。
fn is_live_stream_entry_by_id(id: &str) -> bool {
    id.strip_prefix("live:")
        .or_else(|| id.strip_prefix("live-reasoning:"))
        .is_some_and(|identity| identity.rsplit_once(':').is_some())
}

/// 从活动条目 ID 读取 Turn 身份，Thread message ID 本身不携带该关系。
fn live_turn_id(id: &str) -> Option<String> {
    let identity = id
        .strip_prefix("live:")
        .or_else(|| id.strip_prefix("live-reasoning:"))?;
    let (turn_id, _) = identity.rsplit_once(':')?;
    (!turn_id.is_empty()).then(|| turn_id.to_owned())
}

/// 使用公开类别而非文本猜测流来源；错误、线程通信等 Commentary 不被思考去重规则吞掉。
fn stream_kind(entry: &TimelineEntry) -> Option<StreamKind> {
    match entry.kind {
        TimelineKind::Assistant | TimelineKind::FinalAnswer => Some(StreamKind::Assistant),
        TimelineKind::ReasoningSummary => Some(StreamKind::Reasoning),
        _ => None,
    }
}

/// 用流式前缀摘要验证快照重放，确认失败时不裁剪用户已见字符。
fn matching_stream_tail(progress: &StreamProgress, full_text: &str) -> Option<String> {
    let prefix = full_text.as_bytes().get(..progress.committed_bytes)?;
    let prefix = std::str::from_utf8(prefix).ok()?;
    let digest = Sha256::digest(prefix.as_bytes());
    (digest.as_slice() == progress.committed_hash.clone().finalize().as_slice())
        .then(|| full_text[progress.committed_bytes..].to_owned())
}

/// 控件文本不保留制表符；公开正文另外保留语法所需缩进，控制字符始终不能进入终端 escape 流。
fn sanitize_text(value: &str, max_bytes: usize) -> String {
    sanitize_source(value, max_bytes, false)
}

/// 正文制表符交给 renderer 展开；只清理控制字符，完整历史不受显示预览预算裁剪。
fn sanitize_source(value: &str, max_bytes: usize, preserve_tabs: bool) -> String {
    let mut output = String::with_capacity(value.len().min(max_bytes));
    let mut truncated = false;
    for ch in value.chars() {
        let safe = match ch {
            '\n' => '\n',
            '\t' if preserve_tabs => '\t',
            '\t' => ' ',
            _ if ch.is_control() => continue,
            _ => ch,
        };
        if output.len() + safe.len_utf8() > max_bytes {
            truncated = true;
            break;
        }
        output.push(safe);
    }
    if truncated {
        output.push_str("\n…内容已截断");
    }
    output
}

/// 仅身份或 CAS revision 变化才重置用户交互；相同审批刷新保留局部选择与防重标志。
fn same_pending_identity(old: Option<&PendingPrompt>, new: Option<&PendingPrompt>) -> bool {
    match (old, new) {
        (None, None) => true,
        (
            Some(PendingPrompt::ToolApproval {
                approval_id: old_id,
                thread_id: old_thread,
                turn_id: old_turn,
                expected_thread_revision: old_revision,
                ..
            }),
            Some(PendingPrompt::ToolApproval {
                approval_id: new_id,
                thread_id: new_thread,
                turn_id: new_turn,
                expected_thread_revision: new_revision,
                ..
            }),
        ) => {
            old_id == new_id
                && old_thread == new_thread
                && old_turn == new_turn
                && old_revision == new_revision
        }
        (
            Some(PendingPrompt::Clarification {
                thread_id: old_thread,
                interaction_id: old_id,
                expected_interaction_revision: old_revision,
                ..
            }),
            Some(PendingPrompt::Clarification {
                thread_id: new_thread,
                interaction_id: new_id,
                expected_interaction_revision: new_revision,
                ..
            }),
        ) => old_thread == new_thread && old_id == new_id && old_revision == new_revision,
        _ => false,
    }
}

/// 快照与增量使用相同上限，防止恢复路径绕过内存预算。
fn sanitize_snapshot(mut snapshot: UiSnapshot) -> UiSnapshot {
    snapshot.project_label = snapshot.project_label.map(|text| sanitize_text(&text, 512));
    snapshot.workspace_path = snapshot
        .workspace_path
        .map(|text| sanitize_text(&text, 4096));
    snapshot.thread_id = snapshot.thread_id.map(|text| sanitize_text(&text, 512));
    snapshot.thread_title = snapshot.thread_title.map(|text| sanitize_text(&text, 512));
    snapshot.thread_next_cursor = snapshot
        .thread_next_cursor
        .map(|text| sanitize_text(&text, 512));
    snapshot.input_history_next_cursor = snapshot
        .input_history_next_cursor
        .map(|text| sanitize_text(&text, 512));
    snapshot.model_identifier = snapshot
        .model_identifier
        .map(|text| sanitize_text(&text, 512));
    snapshot.current_model_key = snapshot
        .current_model_key
        .map(|text| sanitize_text(&text, 512));
    snapshot.reasoning_label = snapshot
        .reasoning_label
        .map(|text| sanitize_text(&text, 64));
    snapshot.provider_name = snapshot.provider_name.map(|text| sanitize_text(&text, 256));
    snapshot.permission_label = snapshot
        .permission_label
        .map(|text| sanitize_text(&text, 256));
    snapshot.notice = snapshot.notice.map(|text| sanitize_text(&text, 1024));
    snapshot.timeline = snapshot.timeline.into_iter().map(sanitize_entry).collect();
    let retained_ids = snapshot
        .timeline
        .iter()
        .map(|entry| entry.id.as_str())
        .collect::<HashSet<_>>();
    snapshot
        .turn_id_by_entry_id
        .retain(|entry_id, _| retained_ids.contains(entry_id.as_str()));
    snapshot.turn_id_by_entry_id = snapshot
        .turn_id_by_entry_id
        .into_iter()
        .map(|(entry_id, turn_id)| (sanitize_text(&entry_id, 512), sanitize_text(&turn_id, 256)))
        .collect();
    snapshot.attachments = sanitize_choices(snapshot.attachments);
    snapshot.model_choices = sanitize_model_choices(snapshot.model_choices);
    snapshot.reasoning_choices = sanitize_choices(snapshot.reasoning_choices);
    snapshot.reasoning_model_identifier = snapshot
        .reasoning_model_identifier
        .map(|text| sanitize_text(&text, 512));
    snapshot.permission_choices = sanitize_choices(snapshot.permission_choices);
    snapshot.thread_choices = sanitize_choices(snapshot.thread_choices);
    snapshot.input_history_choices = sanitize_choices(snapshot.input_history_choices);
    snapshot.file_choices = sanitize_choices(snapshot.file_choices);
    snapshot.skill_choices = sanitize_choices(snapshot.skill_choices);
    snapshot.pending_prompt = snapshot.pending_prompt.map(sanitize_prompt);
    snapshot
}

/// 本地用时不承载正文；消息原文完整保留，界面预览预算只在具体控件排版时应用。
fn sanitize_entry(mut entry: TimelineEntry) -> TimelineEntry {
    entry.id = sanitize_text(&entry.id, 512);
    let text_budget = if matches!(entry.kind, TimelineKind::WorkSummary { .. }) {
        0
    } else {
        usize::MAX
    };
    entry.text = sanitize_source(
        &entry.text,
        text_budget,
        !matches!(
            entry.kind,
            TimelineKind::Tool { .. } | TimelineKind::WorkSummary { .. }
        ),
    );
    entry.detail = entry
        .detail
        .map(|detail| sanitize_text(&detail, MAX_DETAIL_BYTES));
    if let TimelineKind::Tool { action, target, .. } = &mut entry.kind {
        *action = sanitize_text(action, 128);
        *target = sanitize_text(target, 1024);
    }
    entry
}

/// 单项也要清理来自分页接口的动态结果，避免绕过快照入口的控制字符与大小预算。
fn sanitize_choice(choice: UiChoice) -> UiChoice {
    UiChoice {
        id: sanitize_text(&choice.id, 512),
        label: sanitize_text(&choice.label, 512),
        detail: choice
            .detail
            .map(|value| sanitize_text(&value, MAX_CHOICE_DETAIL_BYTES)),
    }
}

/// 候选说明保留足够的查看长度，同时把每页列表限制在有界内存内。
fn sanitize_choices(choices: Vec<UiChoice>) -> Vec<UiChoice> {
    choices
        .into_iter()
        .take(MAX_CHOICES)
        .map(sanitize_choice)
        .collect()
}

/// Fork 只显示客户端已按需读到的历史；不能套用普通配置候选上限而隐藏更早的可选回复。
fn sanitize_fork_reply_choices(choices: Vec<UiChoice>) -> Vec<UiChoice> {
    choices.into_iter().map(sanitize_choice).collect()
}

/// 模型选择器始终保留一个管理入口，即使服务端当前没有任何可选模型。
fn sanitize_model_choices(choices: Vec<UiChoice>) -> Vec<UiChoice> {
    let mut choices = sanitize_choices(choices)
        .into_iter()
        .filter(|choice| choice.id != MODEL_SETTINGS_CHOICE_ID)
        .take(MAX_CHOICES.saturating_sub(1))
        .collect::<Vec<_>>();
    choices.push(UiChoice {
        id: MODEL_SETTINGS_CHOICE_ID.to_owned(),
        label: "管理模型…".to_owned(),
        detail: Some("新增或编辑供应商、API Key 和默认模型".to_owned()),
    });
    choices
}

/// 交互题按服务端合同最多保留三题，每题最多保留 32 个多选选项。
fn sanitize_prompt(prompt: PendingPrompt) -> PendingPrompt {
    match prompt {
        PendingPrompt::ToolApproval {
            approval_id,
            thread_id,
            turn_id,
            expected_thread_revision,
            prompt,
            choices,
        } => PendingPrompt::ToolApproval {
            approval_id: sanitize_text(&approval_id, 512),
            thread_id: sanitize_text(&thread_id, 512),
            turn_id: sanitize_text(&turn_id, 512),
            expected_thread_revision,
            prompt: sanitize_text(&prompt, 2048),
            choices: sanitize_choices(choices),
        },
        PendingPrompt::Clarification {
            thread_id,
            interaction_id,
            expected_interaction_revision,
            operation_key,
            questions,
        } => PendingPrompt::Clarification {
            thread_id: sanitize_text(&thread_id, 512),
            interaction_id: sanitize_text(&interaction_id, 512),
            expected_interaction_revision,
            operation_key: sanitize_text(&operation_key, 512),
            questions: questions
                .into_iter()
                .take(3)
                .map(|question| InteractionQuestion {
                    question_id: sanitize_text(&question.question_id, 512),
                    prompt: sanitize_text(&question.prompt, 2048),
                    kind: question.kind,
                    options: sanitize_choices(question.options)
                        .into_iter()
                        .take(32)
                        .collect(),
                    allow_skip: question.allow_skip,
                    allow_free_text: question.allow_free_text,
                })
                .collect(),
        },
    }
}

/// A complete transcript source entry is required before the controller may copy an ordinary message body.
/// @author kongweiguang
fn is_message_entry_kind(kind: &TimelineKind) -> bool {
    matches!(
        kind,
        TimelineKind::User
            | TimelineKind::Assistant
            | TimelineKind::FinalAnswer
            | TimelineKind::Commentary
            | TimelineKind::ReasoningSummary
    )
}

/// Static details have no cancellable read; ordinary, diff, and tool details do.
/// @author kongweiguang
fn close_details_action(entry_id: String) -> Vec<UiAction> {
    if matches!(entry_id.as_str(), "status" | "pending-prompt") {
        Vec::new()
    } else {
        vec![UiAction::CloseDetails { entry_id }]
    }
}

/// Modified/navigation keys end the current burst; bare Enter and Tab must reach the classifier first.
/// @author kongweiguang
fn key_ends_paste_candidate(key: KeyEvent) -> bool {
    if key.modifiers.is_empty() {
        match key.code {
            KeyCode::Char(ch) if ch.is_ascii() => return false,
            KeyCode::Enter | KeyCode::Tab => return false,
            _ => {}
        }
    }
    true
}

/// 解析白名单斜杠命令；Plan 创建保留完整目标文本，缺少目标也作为命令交给 controller 报错。
fn parse_command(text: &str) -> Option<UiAction> {
    let command_text = text.strip_prefix('/')?;
    let mut parts = command_text.splitn(2, char::is_whitespace);
    let command = parts.next()?.to_ascii_lowercase();
    let argument = parts.next().unwrap_or_default().trim();
    let command = match command.as_str() {
        "new" if argument.is_empty() => UiCommand::NewThread,
        "resume" if argument.is_empty() => UiCommand::ResumeThread,
        "model" if argument.is_empty() => UiCommand::SelectModel,
        "permissions" if argument.is_empty() => UiCommand::SelectPermissions,
        "attach" => {
            let paths = split_paths(argument);
            if !paths.is_empty() {
                return Some(UiAction::AttachPaths { paths });
            }
            UiCommand::Attach
        }
        "tools" if argument.is_empty() => UiCommand::ShowTools,
        "diff" if argument.is_empty() => UiCommand::ShowDiff,
        "plan" => {
            let mut plan_parts = argument.splitn(2, char::is_whitespace);
            match plan_parts
                .next()
                .unwrap_or_default()
                .to_ascii_lowercase()
                .as_str()
            {
                "" => UiCommand::TogglePlanMode,
                "create" => {
                    return Some(UiAction::CreatePlan {
                        objective: plan_parts.next().unwrap_or_default().trim().to_owned(),
                    });
                }
                "status" => UiCommand::ShowPlan,
                "execute" => UiCommand::ExecutePlan,
                "pause" => UiCommand::PausePlan,
                "resume" => UiCommand::ResumePlan,
                "stop" => UiCommand::StopPlan,
                _ => return None,
            }
        }
        "compact" if argument.is_empty() => UiCommand::CompactThread,
        "status" if argument.is_empty() => UiCommand::ShowStatus,
        "skills" if argument.is_empty() => UiCommand::ShowSkills,
        "mcp" if argument.is_empty() => UiCommand::ShowMcp,
        "review" if argument.is_empty() => UiCommand::ReviewThread,
        "fork" if argument.is_empty() => UiCommand::ForkThread,
        "copy" if argument.is_empty() => UiCommand::CopyLastReply,
        "export" => {
            let paths = split_paths(argument);
            if paths.len() > 1 {
                return None;
            }
            return Some(UiAction::ExportThread {
                path: paths.into_iter().next(),
            });
        }
        "help" if argument.is_empty() => UiCommand::Help,
        "quit" if argument.is_empty() => return Some(UiAction::Quit),
        _ => return None,
    };
    Some(UiAction::Command(command))
}

/// 解析空格分隔并允许引号包裹的路径，减少包含空格的 Windows 路径误拆分。
fn split_paths(value: &str) -> Vec<std::path::PathBuf> {
    let mut paths = Vec::new();
    let mut token = String::new();
    let mut quote = None;
    let mut chars = value.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '\\' && quote.is_some() && matches!(chars.peek(), Some('\'' | '"' | '\\')) {
            token.push(chars.next().unwrap_or(ch));
        } else if matches!(ch, '\'' | '"') {
            if quote == Some(ch) {
                quote = None;
            } else if quote.is_none() {
                quote = Some(ch);
            } else {
                token.push(ch);
            }
        } else if ch.is_whitespace() && quote.is_none() {
            if !token.is_empty() {
                paths.push(std::path::PathBuf::from(std::mem::take(&mut token)));
            }
        } else {
            token.push(ch);
        }
    }
    if !token.is_empty() {
        paths.push(std::path::PathBuf::from(token));
    }
    paths
}

/// 菜单项顺序固定，命令补全与界面列表复用同一集合。
const COMMANDS: &[UiCommand] = &[
    UiCommand::NewThread,
    UiCommand::ResumeThread,
    UiCommand::SelectModel,
    UiCommand::SelectPermissions,
    UiCommand::Attach,
    UiCommand::ShowTools,
    UiCommand::ShowDiff,
    UiCommand::TogglePlanMode,
    UiCommand::CreatePlan,
    UiCommand::CompactThread,
    UiCommand::ShowStatus,
    UiCommand::ShowSkills,
    UiCommand::ShowMcp,
    UiCommand::ReviewThread,
    UiCommand::ForkThread,
    UiCommand::CopyLastReply,
    UiCommand::ExportThread,
    UiCommand::Help,
    UiCommand::Quit,
];

/// 返回精确命令标签；创建目标由用户在补全后继续输入。
pub(crate) fn command_label(command: UiCommand) -> &'static str {
    match command {
        UiCommand::NewThread => "/new",
        UiCommand::ResumeThread => "/resume",
        UiCommand::SelectModel => "/model",
        UiCommand::SelectPermissions => "/permissions",
        UiCommand::Attach => "/attach",
        UiCommand::ShowTools => "/tools",
        UiCommand::ShowDiff => "/diff",
        UiCommand::TogglePlanMode => "/plan",
        UiCommand::CreatePlan => "/plan create",
        UiCommand::ShowPlan => "/plan",
        UiCommand::ExecutePlan => "/plan execute",
        UiCommand::PausePlan => "/plan pause",
        UiCommand::ResumePlan => "/plan resume",
        UiCommand::StopPlan => "/plan stop",
        UiCommand::CompactThread => "/compact",
        UiCommand::ShowStatus => "/status",
        UiCommand::ShowSkills => "/skills",
        UiCommand::ShowMcp => "/mcp",
        UiCommand::ReviewThread => "/review",
        UiCommand::ForkThread => "/fork",
        UiCommand::CopyLastReply => "/copy",
        UiCommand::ExportThread => "/export",
        UiCommand::Help => "/help",
        UiCommand::Quit => "/quit",
    }
}

/// 仅保留当前斜杠前缀匹配的命令；菜单选择和执行共享此集合避免补全到错误动作。
pub(crate) fn matching_commands(draft: &str) -> Vec<UiCommand> {
    let query = draft
        .strip_prefix('/')
        .unwrap_or(draft)
        .to_ascii_lowercase();
    COMMANDS
        .iter()
        .copied()
        .filter(|command| command_label(*command)[1..].starts_with(&query))
        .collect()
}

/// 菜单只列真实可调用的常用入口；动作说明保持简短以适应窄终端。
pub(crate) fn command_description(command: UiCommand) -> &'static str {
    match command {
        UiCommand::NewThread => "新建会话",
        UiCommand::ResumeThread => "恢复会话",
        UiCommand::SelectModel => "切换或管理模型",
        UiCommand::SelectPermissions => "执行权限",
        UiCommand::Attach => "添加附件",
        UiCommand::ShowTools => "工具记录",
        UiCommand::ShowDiff => "查看变更",
        UiCommand::TogglePlanMode => "切换规划模式",
        UiCommand::CreatePlan => "创建并规划目标",
        UiCommand::ShowPlan => "查看当前 Plan",
        UiCommand::ExecutePlan => "执行已定稿 Plan",
        UiCommand::PausePlan => "暂停 Plan Run",
        UiCommand::ResumePlan => "继续 Plan Run",
        UiCommand::StopPlan => "停止 Plan Run",
        UiCommand::CompactThread => "压缩当前上下文",
        UiCommand::ShowStatus => "查看会话状态",
        UiCommand::ShowSkills => "查看可用 Skills",
        UiCommand::ShowMcp => "查看 MCP 连接",
        UiCommand::ReviewThread => "审查工作区变更",
        UiCommand::ForkThread => "从当前会话分叉",
        UiCommand::CopyLastReply => "复制最后回复",
        UiCommand::ExportThread => "导出会话 Markdown",
        UiCommand::Help => "快捷键",
        UiCommand::Quit => "退出 Ja",
    }
}

impl DetailScroll {
    /// 从尾部上移仍以尾部为锚，避免 End 后因视觉折行数变化而需要大量按键。
    fn up(self, rows: usize) -> Self {
        match self {
            Self::FromTop(offset) => Self::FromTop(offset.saturating_sub(rows)),
            Self::FromBottom(offset) => Self::FromBottom(offset.saturating_add(rows)),
        }
    }

    /// 从顶部或尾部下移，始终保持在可达的有界视觉行范围内。
    fn down(self, rows: usize) -> Self {
        match self {
            Self::FromTop(offset) => Self::FromTop(offset.saturating_add(rows)),
            Self::FromBottom(offset) => Self::FromBottom(offset.saturating_sub(rows)),
        }
    }
}
