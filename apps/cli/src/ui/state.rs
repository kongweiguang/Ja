// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::collections::{HashSet, VecDeque};
use std::time::{Duration, Instant};

use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use unicode_segmentation::UnicodeSegmentation;
use unicode_width::UnicodeWidthStr;

use super::composer::Composer;
use super::model::{
    InteractionAnswer, InteractionQuestion, InteractionQuestionKind, PendingPrompt, TimelineEntry,
    TimelineKind, TurnState, UiAction, UiChoice, UiCommand, UiEvent, UiReferenceKind, UiSnapshot,
    UiSubmitMode,
};

const MAX_TIMELINE_ENTRIES: usize = 400;
const MAX_ENTRY_BYTES: usize = 16 * 1024;
const MAX_DETAIL_BYTES: usize = 96 * 1024;
const MAX_CHOICES: usize = 64;
const MAX_CHOICE_DETAIL_BYTES: usize = 4 * 1024;
const MAX_INPUT_HISTORY_BYTES: usize = 32 * 1024;
const MAX_SCROLLBACK_ENTRIES: usize = 200;
const MAX_INITIAL_SCROLLBACK_BYTES: usize = 192 * 1024;
const MAX_COMMITTED_IDS: usize = 8192;
const FILE_SEARCH_DEBOUNCE: Duration = Duration::from_millis(140);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum ChoiceKind {
    Models,
    Reasoning,
    Permissions,
    Threads,
    InputHistory,
    Files,
    Skills,
    Attachments,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct ActiveFileQuery {
    start: usize,
    end: usize,
    query: String,
}

/// 多题回答共用同一份服务端 CAS 身份，避免按键分支重复排列参数时漏传 revision。
struct ClarificationContext {
    thread_id: String,
    request_id: String,
    expected_revision: u64,
    idempotency_key: String,
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
    Help,
    Details {
        entry_id: String,
        title: String,
        body: String,
        scroll: DetailScroll,
        return_to_pending: bool,
    },
}

/// 详情的滚动锚点保留相对首尾的位置，终端缩放后仍能从末尾向上逐行浏览。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(super) enum DetailScroll {
    FromTop(usize),
    FromBottom(usize),
}

/// 状态 reducer 是展示事实的唯一写入口，恢复与副作用仍由 controller 所有。
pub struct UiState {
    snapshot: UiSnapshot,
    composer: Composer,
    terminal_width: usize,
    terminal_height: usize,
    deferred_composer: Option<Composer>,
    panel: Option<Panel>,
    question_index: usize,
    selected_prompt_choice: usize,
    selected_multiple: HashSet<String>,
    thread_query: String,
    input_history_query: String,
    input_history_texts: Vec<(String, String, bool)>,
    interaction_answers: Vec<InteractionAnswer>,
    using_free_text: bool,
    prompt_responded: bool,
    pending_submission: Option<String>,
    continuation_pending: bool,
    cancel_pending: bool,
    exit_confirmation: Option<Instant>,
    scheduled_file_search: Option<(String, u64, Instant)>,
    query_sequence: u64,
    active_query_id: Option<u64>,
    reference_popup_dismissed: bool,
    committed_ids: HashSet<String>,
    committed_order: VecDeque<String>,
    pending_scrollback: VecDeque<TimelineEntry>,
    closed: bool,
}

impl UiState {
    /// 使用 owner 的权威快照初始化；恢复会话列表选择无需凭空创建 thread。
    pub fn new(snapshot: UiSnapshot) -> Self {
        Self::new_for_terminal(snapshot, 80, 24)
    }

    /// 首次历史提交按实际终端列和行预算计算；后续缩放不能撤回已写入原生 scrollback 的段落。
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
            terminal_width: usize::from(width.max(1)),
            terminal_height: usize::from(height.max(1)),
            deferred_composer: None,
            panel,
            question_index: 0,
            selected_prompt_choice: 0,
            selected_multiple: HashSet::new(),
            thread_query: String::new(),
            input_history_query: String::new(),
            input_history_texts: Vec::new(),
            interaction_answers: Vec::new(),
            using_free_text: false,
            prompt_responded: false,
            pending_submission: None,
            continuation_pending: false,
            cancel_pending: false,
            exit_confirmation: None,
            scheduled_file_search: None,
            query_sequence: 0,
            active_query_id: None,
            reference_popup_dismissed: false,
            committed_ids: HashSet::new(),
            committed_order: VecDeque::new(),
            pending_scrollback: VecDeque::new(),
            closed: false,
        };
        state.seed_initial_scrollback();
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

    /// 编辑器的上下移动以最近一次终端列宽定位，避免缩放后沿旧折行跳转。
    pub fn set_terminal_width(&mut self, width: u16) {
        self.terminal_width = usize::from(width.max(4));
    }

    /// 取出刚变为稳定的段落，bridge 只插入一次到终端原生 scrollback。
    pub fn take_scrollback_entries(&mut self) -> Vec<TimelineEntry> {
        self.pending_scrollback.drain(..).collect()
    }

    /// 未完成的活动尾部留在可重绘 viewport，稳定行已提交给终端历史。
    pub(crate) fn visible_entries(&self) -> impl DoubleEndedIterator<Item = &TimelineEntry> {
        self.snapshot
            .timeline
            .iter()
            .filter(|entry| !self.is_committed(&entry.id))
    }

    /// 权威快照只更新事实；同会话菜单、回答进度和字素光标保留，身份或 CAS 变化才重置交互。
    pub fn apply(&mut self, event: UiEvent) {
        match event {
            UiEvent::ReplaceSnapshot(snapshot) => {
                let mut snapshot = sanitize_snapshot(*snapshot);
                let was_pending = self.snapshot.pending_prompt.is_some();
                let thread_changed = self.snapshot.thread_id != snapshot.thread_id;
                let active_choice_id = self.panel.as_ref().and_then(|panel| match panel {
                    Panel::Choices { kind, selected } => self
                        .choices(*kind)
                        .get(*selected)
                        .map(|choice| choice.id.clone()),
                    _ => None,
                });
                if thread_changed {
                    self.active_query_id = None;
                    self.scheduled_file_search = None;
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
                        _ => {}
                    }
                }
                let prompt_changed = !same_pending_identity(
                    self.snapshot.pending_prompt.as_ref(),
                    snapshot.pending_prompt.as_ref(),
                );
                self.snapshot = snapshot;
                if thread_changed || prompt_changed {
                    self.reset_prompt(was_pending);
                } else {
                    self.clamp_visible_panel(active_choice_id.as_deref());
                }
                if thread_changed {
                    self.seed_initial_scrollback();
                }
            }
            UiEvent::UpsertEntry(entry) => self.upsert_entry(entry),
            UiEvent::AppendAssistantDelta { entry_id, delta } => {
                if let Some(entry) = self
                    .snapshot
                    .timeline
                    .iter_mut()
                    .find(|entry| entry.id == entry_id && entry.kind == TimelineKind::Assistant)
                {
                    let delta = sanitize_text(&delta, MAX_ENTRY_BYTES);
                    append_bounded(&mut entry.text, &delta, MAX_ENTRY_BYTES);
                }
            }
            UiEvent::SetTurnState(status) => {
                self.snapshot.turn_state = status;
                self.continuation_pending = false;
                self.cancel_pending = false;
            }
            UiEvent::SetContinuationAvailable(available) => {
                self.snapshot.continuation_available = available;
                self.continuation_pending = false;
            }
            UiEvent::SetHasOlderHistory(available) => {
                self.snapshot.has_older_history = available;
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
                self.snapshot.model_choices = sanitize_choices(choices);
                self.reconcile_choice_selection(ChoiceKind::Models, selected_id.as_deref());
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
            UiEvent::ClearDraft => {
                if let Some(submitted) = self.pending_submission.take()
                    && self.composer.text() == submitted
                {
                    self.composer.clear();
                }
            }
            UiEvent::RestoreDraft(draft) => {
                if self.composer.is_empty() {
                    self.composer
                        .set_text(&sanitize_text(&draft, MAX_ENTRY_BYTES));
                }
                self.pending_submission = None;
            }
            UiEvent::ReleaseDraft => self.pending_submission = None,
            UiEvent::ReleasePromptSubmission => self.prompt_responded = false,
            UiEvent::ShowDetails {
                entry_id,
                title,
                body,
            } => {
                self.panel = Some(Panel::Details {
                    entry_id: sanitize_text(&entry_id, 512),
                    title: sanitize_text(&title, 256),
                    body: sanitize_text(&body, MAX_DETAIL_BYTES),
                    scroll: DetailScroll::FromTop(0),
                    return_to_pending: false,
                });
            }
            UiEvent::SetNotice(notice) => {
                self.snapshot.notice = notice.map(|value| sanitize_text(&value, 1024));
            }
            UiEvent::Shutdown => self.closed = true,
        }
    }

    /// 路由本地按键并返回意图；需要协议确认的动作等待 controller 更新状态。
    pub fn handle_key(&mut self, key: KeyEvent) -> Vec<UiAction> {
        if key.kind != KeyEventKind::Press || self.closed {
            return Vec::new();
        }
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        if control && key.code == KeyCode::Char('d') && self.composer.is_empty() {
            self.closed = true;
            return vec![UiAction::Quit];
        }
        if control && key.code == KeyCode::Char('c') {
            if let Some(Panel::ChoiceDetail {
                kind, choice_id, ..
            }) = self.panel.clone()
            {
                self.restore_choice_detail(kind, &choice_id);
                return Vec::new();
            }
            if matches!(
                self.panel,
                Some(Panel::Choices { .. } | Panel::Help | Panel::Details { .. })
            ) {
                self.panel = None;
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
            Some(Panel::Details {
                entry_id,
                title,
                body,
                scroll,
                return_to_pending,
            }) => {
                return self.detail_panel_key(
                    key,
                    entry_id,
                    title,
                    body,
                    scroll,
                    return_to_pending,
                );
            }
            Some(Panel::Help) => {
                if key.code == KeyCode::Esc {
                    self.panel = None;
                }
                return Vec::new();
            }
            None => {}
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
        if key.code == KeyCode::Char('?')
            && self.composer.is_empty()
            && !key
                .modifiers
                .intersects(KeyModifiers::CONTROL | KeyModifiers::ALT)
        {
            self.panel = Some(Panel::Help);
            return Vec::new();
        }
        self.composer_key(key)
    }

    /// 只在可见编辑态接纳粘贴，详情或选择器不能暗中改写被遮住的草稿。
    pub fn handle_paste(&mut self, text: &str) {
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
                | Panel::Help
                | Panel::Details { .. },
            ) => false,
        };
        if !editing {
            return;
        }
        self.composer.reset_paste_burst();
        if !self.composer.insert_text(text) {
            self.snapshot.notice = Some("粘贴超过 32 KiB，草稿未改变；请缩短后重试".into());
            return;
        }
        self.schedule_file_search(Instant::now());
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

    /// 命令输入与可见前缀候选同源，Tab 只补全、Enter 才执行，Esc 保留原草稿。
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

    /// 工具与 Diff 详情只改变本地浏览位置；关闭时草稿、服务端状态和焦点目标不变。
    fn detail_panel_key(
        &mut self,
        key: KeyEvent,
        entry_id: String,
        title: String,
        body: String,
        scroll: DetailScroll,
        return_to_pending: bool,
    ) -> Vec<UiAction> {
        if key.code == KeyCode::Esc {
            self.panel = if return_to_pending && self.snapshot.pending_prompt.is_some() {
                Some(Panel::Pending)
            } else {
                None
            };
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
        self.panel = Some(Panel::Details {
            entry_id,
            title,
            body,
            scroll: next,
            return_to_pending,
        });
        Vec::new()
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

    /// 候选面板只接受 controller 提供的身份，附件面板另提供预览和移除动作。
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
                    self.input_history_query.pop();
                    self.input_history_texts.clear();
                    self.snapshot.input_history_choices.clear();
                    self.snapshot.input_history_next_cursor = None;
                    self.panel = Some(Panel::Choices { kind, selected: 0 });
                    return vec![UiAction::SearchInputHistory {
                        query: self.input_history_query.clone(),
                        cursor: None,
                    }];
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
                    return vec![UiAction::SearchInputHistory {
                        query: self.input_history_query.clone(),
                        cursor: None,
                    }];
                }
                KeyCode::PageDown if self.snapshot.input_history_next_cursor.is_some() => {
                    return vec![UiAction::SearchInputHistory {
                        query: self.input_history_query.clone(),
                        cursor: self.snapshot.input_history_next_cursor.clone(),
                    }];
                }
                _ => {}
            }
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
                self.panel = if kind == ChoiceKind::Reasoning {
                    Some(Panel::Choices {
                        kind: ChoiceKind::Models,
                        selected: 0,
                    })
                } else {
                    None
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
                request_id,
                expected_revision,
                idempotency_key,
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
                        request_id,
                        expected_revision,
                        idempotency_key,
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

    /// 文本题共享同一字素编辑器，Tab 可返回选项题并保留协议答案类型。
    fn question_text_key(
        &mut self,
        key: KeyEvent,
        question: InteractionQuestion,
        context: ClarificationContext,
    ) -> Vec<UiAction> {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        if !matches!(key.code, KeyCode::Char(ch) if !control && ch.is_ascii())
            && key.code != KeyCode::Enter
        {
            self.composer.reset_paste_burst();
        }
        match key.code {
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
                if self.composer.consume_paste_newline(Instant::now()) {
                    self.insert_edit_text("\n");
                    return Vec::new();
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
                if ch.is_ascii() {
                    self.composer.observe_plain_char(ch, Instant::now());
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
            request_id: context.request_id,
            expected_revision: context.expected_revision,
            idempotency_key: context.idempotency_key,
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

    /// 普通输入只清空草稿等待 controller 的成功 ACK，响应不确定时不允许盲目重复提交。
    fn composer_key(&mut self, key: KeyEvent) -> Vec<UiAction> {
        let control = key.modifiers.contains(KeyModifiers::CONTROL);
        let shift = key.modifiers.contains(KeyModifiers::SHIFT);
        let mut actions = Vec::new();
        if !matches!(key.code, KeyCode::Char(ch) if !control && ch.is_ascii())
            && key.code != KeyCode::Enter
        {
            self.composer.reset_paste_burst();
        }
        match key.code {
            KeyCode::Esc
                if self.snapshot.turn_state == TurnState::Working
                    && self.composer.is_empty()
                    && !self.cancel_pending =>
            {
                self.cancel_pending = true;
                actions.push(UiAction::CancelTurn);
            }
            KeyCode::Char('c') if control => {
                if !self.composer.is_empty() {
                    self.composer.clear();
                    self.scheduled_file_search = None;
                } else if self.snapshot.turn_state == TurnState::Working && !self.cancel_pending {
                    self.cancel_pending = true;
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
                actions.push(UiAction::SearchInputHistory {
                    query: String::new(),
                    cursor: None,
                });
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
                actions.push(UiAction::LoadOlder);
            }
            KeyCode::Char('o') if control => {
                if let Some(entry) = self.snapshot.timeline.last() {
                    actions.push(UiAction::OpenDetails {
                        entry_id: entry.id.clone(),
                    });
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
                if self.composer.consume_paste_newline(Instant::now()) {
                    self.insert_edit_text("\n");
                    self.schedule_file_search(Instant::now());
                    return actions;
                }
                let mode = if self.snapshot.turn_state == TurnState::Working {
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
                    let mode = if self.snapshot.turn_state == TurnState::Working {
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
                let opening_command = ch == '/' && self.composer.is_empty();
                if ch.is_ascii() {
                    self.composer.observe_plain_char(ch, Instant::now());
                }
                self.insert_edit_text(&ch.to_string());
                if opening_command {
                    self.panel = Some(Panel::Commands { selected: 0 });
                    self.snapshot.notice = None;
                }
                if ch == '@'
                    || ch == '$'
                    || self.active_file_query().is_some()
                    || self.active_skill_query().is_some()
                {
                    self.schedule_file_search(Instant::now());
                }
            }
            _ => {}
        }
        actions
    }

    /// 空草稿只在 controller 确认失败后允许继续；未知服务端命令仍作为普通提示提交。
    fn submit_or_continue(&mut self, mode: UiSubmitMode) -> Option<UiAction> {
        if self.pending_submission.is_some() || self.snapshot.pending_prompt.is_some() {
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
                self.pending_submission = Some(text);
                self.scheduled_file_search = None;
                return Some(action);
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
        self.pending_submission = Some(text.clone());
        self.scheduled_file_search = None;
        Some(UiAction::Submit {
            text,
            references: self.composer.references().to_vec(),
            mode,
        })
    }

    /// 需要先查询候选的命令会清空旧列表，避免跨 workspace 选中陈旧会话或模型。
    fn prepare_command(&mut self, command: UiCommand) {
        match command {
            UiCommand::ResumeThread => self.open_picker(ChoiceKind::Threads),
            UiCommand::SelectModel => self.open_picker(ChoiceKind::Models),
            UiCommand::SelectPermissions => self.open_picker(ChoiceKind::Permissions),
            UiCommand::Attach => {
                self.composer.set_text("/attach ");
            }
            UiCommand::Help => self.panel = Some(Panel::Help),
            _ => {}
        }
    }

    /// 模型与权限先展示最近权威快照、再由 controller 更新；远端候选必须重新查询才清空。
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
            ChoiceKind::Files => self.snapshot.file_choices.clear(),
            ChoiceKind::Skills => self.snapshot.skill_choices.clear(),
            ChoiceKind::Attachments => {}
        }
        self.panel = Some(Panel::Choices { kind, selected });
    }

    /// 选择后回传 owner 给出的 ID；附件操作与文件引用选择保持不同语义。
    fn activate_choice(
        &mut self,
        kind: ChoiceKind,
        selected: usize,
        preview: bool,
    ) -> Vec<UiAction> {
        let Some(choice) = self.choices(kind).get(selected).cloned() else {
            return Vec::new();
        };
        if kind != ChoiceKind::Threads || self.snapshot.thread_id.as_deref() == Some(&choice.id) {
            self.panel = None;
        }
        let action = match kind {
            ChoiceKind::Models => UiAction::SelectModel { id: choice.id },
            ChoiceKind::Reasoning => UiAction::SelectReasoning { id: choice.id },
            ChoiceKind::Permissions => UiAction::SelectPermission { id: choice.id },
            ChoiceKind::Threads => UiAction::SelectThread { id: choice.id },
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
                return Vec::new();
            }
            ChoiceKind::Files => UiAction::SelectFileReference { id: choice.id },
            ChoiceKind::Skills => UiAction::SelectSkillReference { id: choice.id },
            ChoiceKind::Attachments if preview => UiAction::PreviewAttachment { id: choice.id },
            ChoiceKind::Attachments => UiAction::PreviewAttachment { id: choice.id },
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
            ChoiceKind::Files => &self.snapshot.file_choices,
            ChoiceKind::Skills => &self.snapshot.skill_choices,
            ChoiceKind::Attachments => &self.snapshot.attachments,
        }
    }

    /// renderer 只读取当前 panel，确认动作仍由 reducer 产生。
    pub(super) fn panel(&self) -> Option<&Panel> {
        self.panel.as_ref()
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

    /// renderer 查看有待 ACK 的原始草稿，避免将未知提交结果伪装成完成。
    pub(crate) fn has_pending_submission(&self) -> bool {
        self.pending_submission.is_some()
    }

    /// UI 仅展示审批/澄清已提交而未获权威基线确认，不将等待态解释成成功。
    pub(crate) fn prompt_submission_pending(&self) -> bool {
        self.prompt_responded
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

    /// 新增或更新一行按 ID 合并，旧会话历史只保留有限窗口供近期回看。
    fn upsert_entry(&mut self, entry: TimelineEntry) {
        let entry = sanitize_entry(entry);
        if let Some(existing) = self
            .snapshot
            .timeline
            .iter_mut()
            .find(|row| row.id == entry.id)
        {
            *existing = entry;
        } else {
            self.snapshot.timeline.push(entry.clone());
            if self.snapshot.timeline.len() > MAX_TIMELINE_ENTRIES {
                let overflow = self.snapshot.timeline.len() - MAX_TIMELINE_ENTRIES;
                self.snapshot.timeline.drain(..overflow);
            }
        }
        self.flush_stable_prefix();
    }

    /// 首次恢复只写有界的较早稳定段，较新的尾部留在 viewport 并保持时间顺序。
    fn seed_initial_scrollback(&mut self) {
        let mut selected = Vec::new();
        let mut bytes = 0usize;
        let stable_count = self
            .snapshot
            .timeline
            .iter()
            .take_while(|entry| is_stable_entry(entry))
            .count();
        let visible_line_budget = self.terminal_height.saturating_sub(7).max(3);
        let content_width = self.terminal_width.saturating_sub(3).max(1);
        let mut visible_lines = 0usize;
        let mut visible_count = 0usize;
        for entry in self.snapshot.timeline.iter().take(stable_count).rev() {
            let entry_lines = entry
                .text
                .lines()
                .map(|line| UnicodeWidthStr::width(line).div_ceil(content_width).max(1))
                .sum::<usize>()
                .max(1);
            if visible_count > 0 && visible_lines.saturating_add(entry_lines) > visible_line_budget
            {
                break;
            }
            visible_count += 1;
            visible_lines = visible_lines.saturating_add(entry_lines);
        }
        let older_count = stable_count.saturating_sub(visible_count);
        for entry in self.snapshot.timeline.iter().take(older_count) {
            if self.is_committed(&entry.id) {
                continue;
            }
            if !is_stable_entry(entry) {
                break;
            }
            let detail_bytes = entry
                .detail
                .as_ref()
                .map_or(0, |detail| detail.len().min(1536));
            let size = entry.text.len().saturating_add(detail_bytes);
            if selected.len() >= MAX_SCROLLBACK_ENTRIES
                || bytes.saturating_add(size) > MAX_INITIAL_SCROLLBACK_BYTES
            {
                break;
            }
            bytes = bytes.saturating_add(size);
            selected.push(entry.clone());
        }
        for entry in selected {
            if self.mark_committed(&entry.id) {
                self.pending_scrollback.push_back(entry);
            }
        }
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
                self.pending_scrollback.push_back(entry);
            }
        }
    }

    /// 有界记住近期已提交 ID，重复通知和宽度重绘不会打印相同段落。
    fn mark_committed(&mut self, id: &str) -> bool {
        let key = self.committed_key(id);
        if !self.committed_ids.insert(key.clone()) {
            return false;
        }
        self.committed_order.push_back(key);
        if self.committed_order.len() > MAX_COMMITTED_IDS
            && let Some(expired) = self.committed_order.pop_front()
        {
            self.committed_ids.remove(&expired);
        }
        true
    }

    /// 消息 ID 常按 thread 局部生成，因此去重键同时包含当前会话身份。
    fn committed_key(&self, id: &str) -> String {
        format!("{}\0{id}", self.snapshot.thread_id.as_deref().unwrap_or(""))
    }

    /// 按当前 thread 检查稳定消息，切换会话不会把另一个会话的同名 ID 隐藏。
    fn is_committed(&self, id: &str) -> bool {
        self.committed_ids.contains(&self.committed_key(id))
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

/// 只有不可再变的用户正文、Commentary 和明确终态才进入主屏 scrollback。
fn is_stable_entry(entry: &TimelineEntry) -> bool {
    match &entry.kind {
        TimelineKind::User | TimelineKind::Commentary => true,
        TimelineKind::Assistant | TimelineKind::FinalAnswer | TimelineKind::Tool { .. } => {
            matches!(
                entry.status,
                Some(super::model::TimelineStatus::Complete | super::model::TimelineStatus::Failed)
            )
        }
    }
}

/// 终端输入只保留换行并移除控制字符，避免工具输出伪造 ANSI 控制序列。
fn sanitize_text(value: &str, max_bytes: usize) -> String {
    let mut output = String::with_capacity(value.len().min(max_bytes));
    let mut truncated = false;
    for ch in value.chars() {
        let safe = match ch {
            '\n' => '\n',
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

/// 流式追加有严格上限，超出时写可见标记而不静默丢弃尾部。
fn append_bounded(target: &mut String, incoming: &str, max_bytes: usize) {
    let room = max_bytes.saturating_sub(target.len());
    let mut written = 0usize;
    for ch in incoming.chars() {
        if written + ch.len_utf8() > room {
            target.push_str("\n…内容已截断");
            return;
        }
        target.push(ch);
        written += ch.len_utf8();
    }
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
                request_id: old_id,
                expected_revision: old_revision,
                ..
            }),
            Some(PendingPrompt::Clarification {
                thread_id: new_thread,
                request_id: new_id,
                expected_revision: new_revision,
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
    snapshot.timeline = snapshot
        .timeline
        .into_iter()
        .rev()
        .take(MAX_TIMELINE_ENTRIES)
        .map(sanitize_entry)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    snapshot.attachments = sanitize_choices(snapshot.attachments);
    snapshot.model_choices = sanitize_choices(snapshot.model_choices);
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

/// 对话行的 ID、目标、正文和折叠详情分别设限，失败详情仍保持可读上界。
fn sanitize_entry(mut entry: TimelineEntry) -> TimelineEntry {
    entry.id = sanitize_text(&entry.id, 512);
    entry.text = sanitize_text(&entry.text, MAX_ENTRY_BYTES);
    entry.detail = entry
        .detail
        .map(|detail| sanitize_text(&detail, MAX_DETAIL_BYTES));
    if let TimelineKind::Tool { action, target } = &mut entry.kind {
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
            request_id,
            expected_revision,
            idempotency_key,
            questions,
        } => PendingPrompt::Clarification {
            thread_id: sanitize_text(&thread_id, 512),
            request_id: sanitize_text(&request_id, 512),
            expected_revision,
            idempotency_key: sanitize_text(&idempotency_key, 512),
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

/// 解析白名单斜杠命令；未知 `/...` 保留为普通提示，避免静默吞掉用户输入。
fn parse_command(text: &str) -> Option<UiAction> {
    let command_text = text.strip_prefix('/')?;
    let mut parts = command_text.splitn(2, char::is_whitespace);
    let command = parts.next()?.to_ascii_lowercase();
    let argument = parts.next().unwrap_or_default().trim();
    let command = match command.as_str() {
        "new" => UiCommand::NewThread,
        "resume" => UiCommand::ResumeThread,
        "model" => UiCommand::SelectModel,
        "permissions" => UiCommand::SelectPermissions,
        "attach" => {
            let paths = split_paths(argument);
            if !paths.is_empty() {
                return Some(UiAction::AttachPaths { paths });
            }
            UiCommand::Attach
        }
        "tools" => UiCommand::ShowTools,
        "diff" => UiCommand::ShowDiff,
        "plan" => UiCommand::ShowPlan,
        "goal" => UiCommand::ShowGoal,
        "help" => UiCommand::Help,
        "quit" => return Some(UiAction::Quit),
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
    UiCommand::ShowPlan,
    UiCommand::ShowGoal,
    UiCommand::Help,
    UiCommand::Quit,
];

/// 返回菜单渲染所需标签，避免直接显示 enum 名称。
pub(crate) fn command_label(command: UiCommand) -> &'static str {
    match command {
        UiCommand::NewThread => "/new",
        UiCommand::ResumeThread => "/resume",
        UiCommand::SelectModel => "/model",
        UiCommand::SelectPermissions => "/permissions",
        UiCommand::Attach => "/attach",
        UiCommand::ShowTools => "/tools",
        UiCommand::ShowDiff => "/diff",
        UiCommand::ShowPlan => "/plan",
        UiCommand::ShowGoal => "/goal",
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

/// 菜单描述只解释结果，不重复斜杠命令本身，窄窗口也能一眼识别动作。
pub(crate) fn command_description(command: UiCommand) -> &'static str {
    match command {
        UiCommand::NewThread => "新建会话",
        UiCommand::ResumeThread => "恢复会话",
        UiCommand::SelectModel => "切换模型",
        UiCommand::SelectPermissions => "执行权限",
        UiCommand::Attach => "添加附件",
        UiCommand::ShowTools => "工具记录",
        UiCommand::ShowDiff => "查看变更",
        UiCommand::ShowPlan => "查看计划",
        UiCommand::ShowGoal => "查看目标",
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
