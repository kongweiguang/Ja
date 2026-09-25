// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 终端 controller 串行消费用户动作与 JA-RPC 事件，Java 是所有业务状态的唯一 owner。

use super::{
    CliError, ThreadContext,
    attachment::{self, ImportedAttachment},
    canonical_workspace, create_thread, open_workspace, projection, required_str, required_u64,
    rpc::Connection,
};
use base64::Engine;
use ja_cli::ui::{
    PendingPrompt, TimelineEntry, TimelineKind, TimelineStatus, TuiBridge, UiAction, UiChoice,
    UiCommand, UiEvent, UiReference, UiReferenceKind, UiSnapshot, UiSubmitMode,
};
use ja_runtime::app_server_process::{EventPump, SessionEvent};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::time::{Duration, Instant};
use unicode_segmentation::UnicodeSegmentation;

const EVENT_WAIT: Duration = Duration::from_millis(50);
const REFRESH_INTERVAL: Duration = Duration::from_secs(3);
const MAX_DISCOVERY_PAGES: usize = 100;
const DISCOVERY_DEADLINE: Duration = Duration::from_secs(5);

struct Interactive {
    connection: Connection,
    pump: EventPump,
    bridge: TuiBridge,
    thread: Option<ThreadContext>,
    thread_list_workspace_id: String,
    history: Value,
    older_items: Vec<Value>,
    older_cursor: Option<String>,
    interaction: Value,
    config: Value,
    pending_model_id: Option<String>,
    file_choices: HashMap<String, String>,
    skill_choices: HashMap<String, String>,
    skill_catalog: Option<(Instant, Vec<UiChoice>, bool)>,
    attachments: Vec<ImportedAttachment>,
    interaction_key: Option<(String, String)>,
    last_stream_seq: u64,
    live_assistant_visible: bool,
    next_refresh: Instant,
}

/// 恢复候选绑定启动目录或 Thread 的 Workspace identity，避免全局历史混入其他项目；分页复用同一身份。
pub fn run(
    cwd: Option<PathBuf>,
    initial_prompt: Option<String>,
    resume_id: Option<String>,
    resume_mode: bool,
) -> Result<(), CliError> {
    let mut connection = Connection::connect_or_start()?;
    let (thread, thread_list_workspace_id, choices, thread_next_cursor) = if let Some(id) =
        resume_id
    {
        let thread = load_context(&mut connection, &id)?;
        let workspace_id = thread.workspace_id.clone();
        (Some(thread), workspace_id, Vec::new(), None)
    } else if resume_mode {
        let root = canonical_workspace(cwd)?;
        let workspace = open_workspace(&mut connection, &root)?;
        let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
        let (choices, next_cursor) = list_thread_choices(&mut connection, &workspace_id, "", None)?;
        (None, workspace_id, choices, next_cursor)
    } else {
        let root = canonical_workspace(cwd)?;
        let thread = create_thread(&mut connection, &root, true)?;
        let workspace_id = thread.workspace_id.clone();
        (Some(thread), workspace_id, Vec::new(), None)
    };
    if let Some(thread) = &thread {
        connection.observe(&thread.thread_id)?;
    }
    let pump = connection.take_event_pump()?;
    let (history, interaction, config) = match &thread {
        Some(thread) => read_baseline(&mut connection, &thread.thread_id, &thread.workspace_id)?,
        None => (Value::Null, Value::Null, Value::Null),
    };
    let mut snapshot = match &thread {
        Some(thread) => {
            projection::project(thread, &history, &interaction, &config, Some(&thread.title))?
        }
        None => UiSnapshot::default(),
    };
    snapshot.has_older_history = history.get("nextCursor").and_then(Value::as_str).is_some();
    if resume_mode && thread.is_none() && choices.is_empty() {
        snapshot.notice = Some("当前项目暂无可恢复会话；可用 /new 新建，或 /resume 重试".into());
    }
    snapshot.thread_choices = choices;
    snapshot.thread_next_cursor = thread_next_cursor;
    let interaction_key = match &snapshot.pending_prompt {
        Some(PendingPrompt::Clarification {
            request_id,
            idempotency_key,
            ..
        }) => Some((request_id.clone(), idempotency_key.clone())),
        _ => None,
    };
    let bridge = TuiBridge::spawn(snapshot).map_err(CliError::transport)?;
    let mut session = Interactive {
        connection,
        pump,
        bridge,
        thread,
        thread_list_workspace_id,
        history,
        older_items: Vec::new(),
        older_cursor: None,
        interaction,
        config,
        pending_model_id: None,
        file_choices: HashMap::new(),
        skill_choices: HashMap::new(),
        skill_catalog: None,
        attachments: Vec::new(),
        interaction_key,
        last_stream_seq: 0,
        live_assistant_visible: false,
        next_refresh: Instant::now() + REFRESH_INTERVAL,
    };
    session.older_cursor = session
        .history
        .get("nextCursor")
        .and_then(Value::as_str)
        .map(str::to_owned);
    session.reset_stream_cursor();
    if let Some(prompt) = initial_prompt {
        if let Err(error) = session.submit(prompt.clone(), Vec::new(), UiSubmitMode::Immediate) {
            session.send(UiEvent::RestoreDraft(prompt));
            session.notice(submission_notice(&error));
        } else {
            session.send(UiEvent::ClearDraft);
        }
    }
    let result = session.event_loop();
    for attachment in &session.attachments {
        let _ = session
            .connection
            .request("attachment/discard", json!({"attachmentId":attachment.id}));
    }
    if let Some(thread) = &session.thread {
        let _ = session.connection.unobserve(&thread.thread_id);
    }
    let _ = session.connection.disconnect();
    let _ = session.bridge.shutdown();
    result
}

impl Interactive {
    /// 单个 controller 轮流处理键盘动作和连接通知，避免两个线程争夺同一审批 CAS。
    fn event_loop(&mut self) -> Result<(), CliError> {
        loop {
            while let Some(action) = self.bridge.try_recv_action().map_err(CliError::transport)? {
                if matches!(action, UiAction::Quit) {
                    return Ok(());
                }
                let result = self.handle_action(action);
                if self.connection.take_replaced() {
                    self.restore_replaced_connection()?;
                }
                if let Err(error) = result {
                    self.notice(error.message().to_owned());
                    // 单次动作失败可能只是本地文件或瞬时 RPC 故障；会话观察循环继续运行，
                    // 由 EOF/重连分支重新取得权威状态，绝不重发刚才的动作。
                }
            }
            match self.pump.next_event(EVENT_WAIT) {
                Some(SessionEvent::Notification(frame)) => {
                    if let (Some(method), Some(params)) = (frame.method(), frame.params()) {
                        self.handle_notification(method, params)?;
                    }
                }
                Some(
                    SessionEvent::Eof
                    | SessionEvent::ProcessExited { .. }
                    | SessionEvent::HandshakeFailed
                    | SessionEvent::ProtocolFault(_)
                    | SessionEvent::QueueFatalOverflow(_),
                ) => {
                    self.reconnect()?;
                }
                Some(SessionEvent::QueueOverflow(_)) => self.refresh()?,
                _ => {}
            }
            if self.latest_turn_is_active() && Instant::now() >= self.next_refresh {
                self.refresh()?;
            }
        }
    }

    /// 掉线后只重新读取权威事实；任何 Turn、审批或 Tool 请求均不重发。
    fn reconnect(&mut self) -> Result<(), CliError> {
        let thread_id = self.thread.as_ref().map(|thread| thread.thread_id.clone());
        self.notice("连接中断，正在读取后台状态…".into());
        let mut connection = Connection::connect_existing()?;
        if let Some(thread_id) = &thread_id {
            connection.observe(thread_id)?;
        }
        let pump = connection.take_event_pump()?;
        self.connection = connection;
        self.pump = pump;
        self.refresh()?;
        self.notice("已恢复后台连接".into());
        Ok(())
    }

    /// 核实旧连接提交时替换 TCP 后，重新建立本会话观察并领取唯一事件泵。
    fn restore_replaced_connection(&mut self) -> Result<(), CliError> {
        if let Some(thread) = &self.thread {
            self.connection.observe(&thread.thread_id)?;
        }
        self.pump = self.connection.take_event_pump()?;
        self.refresh()
    }

    /// 通知按 thread identity 过滤；流增量按 streamSeq 去重，语义事件读回持久基线。
    fn handle_notification(&mut self, method: &str, params: &Value) -> Result<(), CliError> {
        if method == "configuration/changed" {
            let applies = self.thread.as_ref().is_some_and(|thread| {
                params.get("scope").and_then(Value::as_str) == Some("user")
                    || params.get("workspaceId").and_then(Value::as_str)
                        == Some(&thread.workspace_id)
            });
            if applies {
                self.refresh()?;
            }
            return Ok(());
        }
        let Some(thread) = &self.thread else {
            return Ok(());
        };
        if params.get("threadId").and_then(Value::as_str) != Some(&thread.thread_id) {
            return Ok(());
        }
        if method == "thread/metadata-changed" {
            self.reload_metadata()?;
            return self.refresh();
        }
        if matches!(
            method,
            "assistant/text-delta" | "assistant/reasoning-summary-delta"
        ) {
            let sequence = required_u64(params, "streamSeq")?;
            if sequence <= self.last_stream_seq {
                return Ok(());
            }
            if sequence != self.last_stream_seq + 1 {
                return self.refresh();
            }
            self.last_stream_seq = sequence;
            if method == "assistant/text-delta" {
                let turn_id = required_str(params, "turnId")?;
                let text = params
                    .get("text")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CliError::protocol("流事件缺少 text"))?;
                let id = format!("live:{turn_id}");
                if self.live_assistant_visible {
                    self.send(UiEvent::AppendAssistantDelta {
                        entry_id: id,
                        delta: text.into(),
                    });
                } else {
                    self.send(UiEvent::UpsertEntry(TimelineEntry {
                        id,
                        kind: TimelineKind::Assistant,
                        text: text.into(),
                        detail: None,
                        status: Some(TimelineStatus::Running),
                    }));
                    self.live_assistant_visible = true;
                }
            }
        } else {
            self.refresh()?;
        }
        Ok(())
    }

    /// 跨端改名与偏好更新从完整 Thread 元数据回读，避免旧模型标签留在终端状态行。
    fn reload_metadata(&mut self) -> Result<(), CliError> {
        let Some((workspace_id, thread_id)) = self
            .thread
            .as_ref()
            .map(|thread| (thread.workspace_id.clone(), thread.thread_id.clone()))
        else {
            return Ok(());
        };
        let metadata = list_thread_in_workspace(&mut self.connection, &workspace_id, &thread_id)?;
        if let Some(thread) = &mut self.thread {
            thread.title = required_str(&metadata, "title")?.to_owned();
            thread.revision = required_u64(&metadata, "revision")?;
            if let Some(prefs) = metadata.get("preferences").filter(|value| !value.is_null()) {
                thread.provider_id = required_str(prefs, "providerId")?.to_owned();
                thread.model_id = required_str(prefs, "modelId")?.to_owned();
                thread.reasoning_level = prefs
                    .get("reasoningLevel")
                    .and_then(Value::as_str)
                    .map(str::to_owned);
                thread.access_mode = required_str(prefs, "accessMode")?.to_owned();
            }
        }
        Ok(())
    }

    /// 读回先于渲染，UI 每次只拿同一 Java revision 的完整事实；本地草稿和选择保留在 controller。
    fn refresh(&mut self) -> Result<(), CliError> {
        let Some((thread_id, workspace_id)) = self
            .thread
            .as_ref()
            .map(|thread| (thread.thread_id.clone(), thread.workspace_id.clone()))
        else {
            return Ok(());
        };
        let (history, interaction, config) =
            read_baseline(&mut self.connection, &thread_id, &workspace_id)?;
        let revision = required_u64(&history, "revision")?;
        if let Some(thread) = &mut self.thread {
            thread.revision = revision;
        }
        self.history = history;
        if self.older_cursor.is_none() && self.older_items.is_empty() {
            self.older_cursor = self
                .history
                .get("nextCursor")
                .and_then(Value::as_str)
                .map(str::to_owned);
        }
        self.interaction = interaction;
        self.config = config;
        self.reset_stream_cursor();
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::protocol("会话在刷新期间丢失"))?;
        let merged_history = self.merged_history()?;
        let mut snapshot = projection::project(
            thread,
            &merged_history,
            &self.interaction,
            &self.config,
            Some(&thread.title),
        )?;
        snapshot.has_older_history = self.older_cursor.is_some() && self.older_items.len() < 1_000;
        snapshot.attachments = self
            .attachments
            .iter()
            .map(|item| UiChoice {
                id: item.id.clone(),
                label: item.name.clone(),
                detail: Some(format!("{} B", item.size)),
            })
            .collect();
        self.stabilize_interaction_key(&mut snapshot);
        snapshot.file_choices = self
            .file_choices
            .keys()
            .map(|path| UiChoice {
                id: path.clone(),
                label: path.clone(),
                detail: None,
            })
            .collect();
        self.send(UiEvent::ReplaceSnapshot(Box::new(snapshot)));
        // 权威快照仍显示同一请求时允许用户显式重试；绝不由客户端自动重发审批或答案。
        self.send(UiEvent::ReleasePromptSubmission);
        self.next_refresh = Instant::now() + REFRESH_INTERVAL;
        Ok(())
    }

    /// 从 liveStream 基线记录已接纳的最后增量，读取过程中排队的旧通知不得重复追加。
    fn reset_stream_cursor(&mut self) {
        let stream = self
            .history
            .get("liveStream")
            .filter(|stream| !stream.is_null());
        self.last_stream_seq = stream
            .and_then(|value| value.get("streamSeq"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        self.live_assistant_visible = stream
            .and_then(|value| value.get("segments"))
            .and_then(Value::as_array)
            .is_some_and(|segments| {
                segments
                    .iter()
                    .any(|segment| segment.get("kind").and_then(Value::as_str) == Some("assistant"))
            });
    }

    /// 旧页只按用户请求加载，合并时用持久 itemId 去重以处理分页期间新提交的记录。
    fn load_older(&mut self) -> Result<(), CliError> {
        let thread_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .thread_id
            .clone();
        let cursor = self
            .older_cursor
            .clone()
            .ok_or_else(|| CliError::usage("没有更早记录"))?;
        if self.older_items.len() >= 1_000 {
            return Err(CliError::usage("已达到本次查看的历史上限"));
        }
        let page = self.connection.request(
            "thread/read",
            json!({
                "threadId":thread_id,"tail":true,"cursor":cursor,"limit":200
            }),
        )?;
        let items = page
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("历史页缺少 items"))?;
        let mut earlier = items.clone();
        earlier.append(&mut self.older_items);
        self.older_items = earlier;
        self.older_cursor = page
            .get("nextCursor")
            .and_then(Value::as_str)
            .map(str::to_owned);
        self.refresh()
    }

    /// 最新页与已请求旧页在渲染前合并，不修改服务端返回的 Turn/revision/liveStream 基线。
    fn merged_history(&self) -> Result<Value, CliError> {
        let latest = self
            .history
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("thread/read 缺少 items"))?;
        let mut seen = HashSet::new();
        let mut items = Vec::with_capacity(self.older_items.len() + latest.len());
        for item in self.older_items.iter().chain(latest) {
            let id = required_str(item, "itemId")?;
            if seen.insert(id.to_owned()) {
                items.push(item.clone());
            }
        }
        let mut merged = self.history.clone();
        merged["items"] = Value::Array(items);
        Ok(merged)
    }

    /// 同一澄清请求跨事件刷新始终使用同一个幂等键，响应未知时不得换键重发。
    fn stabilize_interaction_key(&mut self, snapshot: &mut UiSnapshot) {
        match &mut snapshot.pending_prompt {
            Some(PendingPrompt::Clarification {
                request_id,
                idempotency_key,
                ..
            }) => {
                if let Some((known_request, known_key)) = &self.interaction_key
                    && known_request == request_id
                {
                    *idempotency_key = known_key.clone();
                    return;
                }
                self.interaction_key = Some((request_id.clone(), idempotency_key.clone()));
            }
            _ => self.interaction_key = None,
        }
    }

    /// UI 事件发送失败表示终端线程已退出；后台任务仍由 Java 管理。
    fn send(&self, event: UiEvent) {
        let _ = self.bridge.events().send(event);
    }

    /// 提示只说明已知事实，不把 Tool 原始输出或配置 secret 放入状态行。
    fn notice(&self, message: String) {
        self.send(UiEvent::SetNotice(Some(message)));
    }

    /// 每个动作都检查当前 Thread identity 与 Java CAS，不让 UI 选择值直接成为业务事实。
    fn handle_action(&mut self, action: UiAction) -> Result<(), CliError> {
        match action {
            UiAction::Submit {
                text,
                references,
                mode,
            } => {
                if let Err(error) = self.submit(text.clone(), references, mode) {
                    self.send(UiEvent::RestoreDraft(text));
                    self.notice(submission_notice(&error));
                } else {
                    self.send(UiEvent::ClearDraft);
                }
            }
            UiAction::CancelTurn => self.cancel_turn()?,
            UiAction::ContinueReply => self.continue_reply()?,
            UiAction::Command(command) => self.command(command)?,
            UiAction::SelectModel { id } => self.select_model(&id)?,
            UiAction::SelectReasoning { id } => self.select_reasoning(&id)?,
            UiAction::SelectPermission { id } => self.select_permission(&id)?,
            UiAction::SelectThread { id } => self.switch_thread(&id)?,
            UiAction::LoadOlder => self.load_older()?,
            UiAction::RefreshSnapshot => self.refresh()?,
            UiAction::SearchFiles {
                query,
                thread_id,
                query_id,
            } => {
                if self.thread.as_ref().map(|thread| &thread.thread_id) == thread_id.as_ref() {
                    self.search_files(&query, thread_id, query_id)?;
                }
            }
            UiAction::SearchSkills {
                query,
                thread_id,
                query_id,
            } => {
                if self.thread.as_ref().map(|thread| &thread.thread_id) == thread_id.as_ref() {
                    self.search_skills(&query, thread_id, query_id)?;
                }
            }
            UiAction::SearchThreads { query, cursor } => {
                self.search_threads(&query, cursor.as_deref())?
            }
            UiAction::SearchInputHistory { query, cursor } => {
                self.search_input_history(&query, cursor.as_deref())?
            }
            UiAction::SelectFileReference { id } => self.select_file(&id)?,
            UiAction::SelectSkillReference { id } => self.select_skill(&id)?,
            UiAction::AttachPaths { paths } => {
                if let Err(error) = self.attach_paths(&paths) {
                    self.send(UiEvent::ReleaseDraft);
                    return Err(error);
                }
                self.send(UiEvent::ClearDraft);
            }
            UiAction::PreviewAttachment { id } => self.preview_attachment(&id)?,
            UiAction::RemoveAttachment { id } => self.remove_attachment(&id)?,
            UiAction::RespondToolApproval {
                approval_id,
                thread_id,
                turn_id,
                expected_thread_revision,
                choice_id,
            } => {
                let result = self.respond_approval(
                    &approval_id,
                    &thread_id,
                    &turn_id,
                    expected_thread_revision,
                    &choice_id,
                );
                if result.is_err() {
                    let _ = self.refresh();
                }
                result?;
            }
            UiAction::RespondInteraction {
                thread_id,
                request_id,
                expected_revision,
                idempotency_key,
                answers,
            } => {
                let result = self.respond_interaction(
                    &thread_id,
                    &request_id,
                    expected_revision,
                    &idempotency_key,
                    answers,
                );
                if result.is_err() {
                    let _ = self.refresh();
                }
                result?;
            }
            UiAction::OpenDetails { entry_id } => self.open_details(&entry_id)?,
            UiAction::Quit => {}
        }
        Ok(())
    }

    /// 输入引用按协议排序为 workspace reference、附件、正文；活动 Turn 进入已有服务端队列。
    fn submit(
        &mut self,
        text: String,
        mut references: Vec<UiReference>,
        mode: UiSubmitMode,
    ) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let mut plain = text.clone();
        let mut content = Vec::new();
        references.sort_by_key(|reference| reference.start);
        let positions = text
            .grapheme_indices(true)
            .map(|(index, _)| index)
            .chain(std::iter::once(text.len()))
            .collect::<Vec<_>>();
        let mut previous_end = 0;
        let mut ranges = Vec::new();
        let mut has_skill = false;
        for reference in &references {
            if reference.start < previous_end
                || reference.end <= reference.start
                || reference.end >= positions.len()
            {
                return Err(CliError::usage("引用区间已过期，请重新选择"));
            }
            let start = positions[reference.start];
            let end = positions[reference.end];
            let displayed = &text[start..end];
            match &reference.kind {
                UiReferenceKind::Workspace {
                    workspace_id,
                    relative_path,
                    kind,
                } => {
                    if !displayed.starts_with('@') || workspace_id != &thread.workspace_id {
                        return Err(CliError::usage("文件引用与当前会话不一致"));
                    }
                    content.push(json!({"type":"workspace_reference","workspaceId":workspace_id,"relativePath":relative_path,"kind":kind}));
                }
                UiReferenceKind::Skill { skill_id } => {
                    if !displayed.starts_with('$') {
                        return Err(CliError::usage("技能引用已被修改"));
                    }
                    has_skill = true;
                    content.push(json!({"type":"skill_reference","skillId":skill_id}));
                }
            }
            previous_end = reference.end;
            ranges.push((start, end));
        }
        for (start, end) in ranges.into_iter().rev() {
            plain.replace_range(start..end, "");
        }
        for attachment in &self.attachments {
            content.push(json!({"type":"attachment","attachmentId":attachment.id}));
        }
        if !plain.trim().is_empty() {
            content.push(json!({"type":"text","text":plain.trim()}));
        }
        if content.is_empty() {
            return Err(CliError::usage("输入不能为空"));
        }
        if has_skill && plain.trim().is_empty() {
            return Err(CliError::usage("引用技能后请补充任务内容"));
        }
        let active_turn = self
            .history
            .get("turns")
            .and_then(Value::as_array)
            .and_then(|turns| turns.last())
            .filter(|turn| {
                matches!(
                    turn.get("status").and_then(Value::as_str),
                    Some("queued" | "running" | "waiting_approval" | "suspended")
                )
            })
            .and_then(|turn| turn.get("turnId"))
            .and_then(Value::as_str);
        let result = if let Some(turn_id) = active_turn {
            let kind = match mode {
                UiSubmitMode::Queue => "follow_up",
                _ => "steering",
            };
            self.connection.request_operation(
                "turn/input/enqueue",
                json!({"turnId":turn_id,"content":content,"kind":kind}),
            )?
        } else {
            self.connection.request_operation(
                "turn/start",
                json!({"threadId":thread.thread_id,"content":content}),
            )?
        };
        if result.get("accepted").and_then(Value::as_bool) != Some(true) {
            return Err(CliError::protocol("输入未被服务端接纳"));
        }
        self.attachments.clear();
        self.send_attachments();
        if let Err(error) = self.refresh() {
            self.notice(format!("输入已提交，状态暂无法刷新：{}", error.message()));
        }
        Ok(())
    }

    /// 取消只针对 Java 最新活动 Turn；请求 ACK 不冒充终态，后续仍按事件或快照更新。
    fn cancel_turn(&mut self) -> Result<(), CliError> {
        let turn_id = self.latest_active_turn()?;
        self.connection
            .request("turn/cancel", json!({"turnId":turn_id}))?;
        self.refresh()
    }

    /// 续答以 Thread revision CAS 准入，不携带旧正文，防止重跑未知 Tool。
    fn continue_reply(&mut self) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        self.connection.request_operation(
            "turn/continue",
            json!({
                "threadId":thread.thread_id,"expectedThreadRevision":thread.revision
            }),
        )?;
        if let Err(error) = self.refresh() {
            self.notice(format!("续答已提交，状态暂无法刷新：{}", error.message()));
        }
        Ok(())
    }

    /// 斜杠菜单只路由已有真实能力；复杂事实通过只读详情查看器呈现。
    fn command(&mut self, command: UiCommand) -> Result<(), CliError> {
        match command {
            UiCommand::NewThread => {
                let root = canonical_workspace(
                    self.thread
                        .as_ref()
                        .map(|thread| PathBuf::from(&thread.workspace_root)),
                )?;
                let thread = create_thread(&mut self.connection, &root, false)?;
                self.activate_thread(thread)?;
            }
            UiCommand::ResumeThread => self.show_thread_choices()?,
            UiCommand::SelectModel => self.show_model_choices()?,
            UiCommand::SelectPermissions => {
                self.send(UiEvent::SetPermissionChoices(
                    projection::permission_choices(),
                ));
            }
            UiCommand::Attach | UiCommand::Help | UiCommand::Quit => {}
            UiCommand::ShowTools => self.show_tools()?,
            UiCommand::ShowDiff => self.show_diff()?,
            UiCommand::ShowPlan => self.show_plan()?,
            UiCommand::ShowGoal => self.show_goal()?,
        }
        Ok(())
    }

    /// 打开模型选择时重读 Java 当前有效配置，再把真实上游 ID 候选投影给已有菜单。
    fn show_model_choices(&mut self) -> Result<(), CliError> {
        let workspace_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .workspace_id
            .clone();
        let config = match self
            .connection
            .request("configuration/read", json!({"workspaceId":workspace_id}))
        {
            Ok(config) => config,
            Err(error) => {
                self.send(UiEvent::SetModelChoices(Vec::new()));
                return Err(error);
            }
        };
        let choices = projection::model_choices(&config);
        self.config = config;
        self.send(UiEvent::SetModelChoices(choices));
        Ok(())
    }

    /// 切换先解除旧观察，再订阅新会话并读取基线；后续恢复搜索随选中 Thread 切换 Workspace。
    fn activate_thread(&mut self, thread: ThreadContext) -> Result<(), CliError> {
        if let Some(old) = &self.thread {
            self.connection.unobserve(&old.thread_id)?;
        }
        self.connection.observe(&thread.thread_id)?;
        self.thread_list_workspace_id = thread.workspace_id.clone();
        self.thread = Some(thread);
        self.attachments.clear();
        self.file_choices.clear();
        self.skill_choices.clear();
        self.skill_catalog = None;
        self.pending_model_id = None;
        self.older_items.clear();
        self.older_cursor = None;
        self.refresh()
    }

    /// 选择器 identity 只能来自服务端发现列表，最终仍读取完整 Thread 元数据核验。
    fn switch_thread(&mut self, thread_id: &str) -> Result<(), CliError> {
        let thread = load_context(&mut self.connection, thread_id)?;
        self.activate_thread(thread)
    }

    /// 打开选择器先读最近一页，后续查询和翻页都复用同一 Java keyset 接口。
    fn show_thread_choices(&mut self) -> Result<(), CliError> {
        self.search_threads("", None)
    }

    /// 服务端按当前 Workspace 和标题过滤且返回游标，CLI 不扫描其它项目或物化完整历史。
    fn search_threads(&mut self, query: &str, cursor: Option<&str>) -> Result<(), CliError> {
        let (choices, next_cursor) = list_thread_choices(
            &mut self.connection,
            &self.thread_list_workspace_id,
            query,
            cursor,
        )?;
        self.send(UiEvent::SetThreadPage {
            query: query.to_owned(),
            choices,
            next_cursor,
            append: cursor.is_some(),
        });
        Ok(())
    }

    /// 只用 Java 的权威用户输入索引；预览与完整草稿分开，超限条目不会被误提交。
    fn search_input_history(&mut self, query: &str, cursor: Option<&str>) -> Result<(), CliError> {
        let mut params = json!({"query":query,"limit":10});
        if let Some(cursor) = cursor {
            params["cursor"] = json!(cursor);
        }
        let result = self.connection.request("history/input/search", params)?;
        let items = result
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("输入历史缺少 items"))?
            .iter()
            .map(|item| {
                let text = required_str(item, "text")?;
                let preview = text
                    .lines()
                    .next()
                    .unwrap_or_default()
                    .chars()
                    .take(120)
                    .collect::<String>();
                let truncated = item
                    .get("truncated")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                let detail = item
                    .get("createdAt")
                    .and_then(Value::as_str)
                    .unwrap_or_default();
                Ok((
                    UiChoice {
                        id: required_str(item, "itemId")?.into(),
                        label: preview,
                        detail: Some(if truncated {
                            format!("{detail} · 过长，仅预览")
                        } else {
                            detail.to_owned()
                        }),
                    },
                    text.to_owned(),
                    truncated,
                ))
            })
            .collect::<Result<Vec<_>, CliError>>()?;
        self.send(UiEvent::SetInputHistoryPage {
            query: query.to_owned(),
            items,
            next_cursor: result
                .get("nextCursor")
                .and_then(Value::as_str)
                .map(str::to_owned),
            append: cursor.is_some(),
        });
        Ok(())
    }

    /// 第一层模型选择只建立待提交候选；模型支持推理等级时先给用户选择，避免偷偷套用默认等级。
    fn select_model(&mut self, id: &str) -> Result<(), CliError> {
        let (provider_id, model_id) = id
            .split_once('/')
            .ok_or_else(|| CliError::usage("模型选择无效"))?;
        let providers = self
            .config
            .get("effective")
            .and_then(|value| value.get("providers"))
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("配置缺少模型目录"))?;
        let model = providers
            .iter()
            .find(|provider| {
                provider.get("provider_id").and_then(Value::as_str) == Some(provider_id)
            })
            .and_then(|provider| provider.get("models"))
            .and_then(Value::as_array)
            .and_then(|models| {
                models
                    .iter()
                    .find(|model| model.get("model_id").and_then(Value::as_str) == Some(model_id))
            })
            .ok_or_else(|| CliError::usage("选择的模型已不存在"))?;
        let levels = model.get("reasoning_level_map").and_then(Value::as_object);
        let choices: Vec<UiChoice> = ["off", "minimal", "low", "medium", "high", "xhigh", "max"]
            .into_iter()
            .filter(|level| levels.is_some_and(|map| map.contains_key(*level)))
            .map(|level| UiChoice {
                id: level.into(),
                label: if model.get("default_reasoning_level").and_then(Value::as_str)
                    == Some(level)
                {
                    format!("{}（默认）", reasoning_label(level))
                } else {
                    reasoning_label(level).into()
                },
                detail: Some(reasoning_description(level).into()),
            })
            .collect();
        if !choices.is_empty() {
            let default = model.get("default_reasoning_level").and_then(Value::as_str);
            let current = self
                .thread
                .as_ref()
                .filter(|thread| thread.provider_id == provider_id && thread.model_id == model_id)
                .and_then(|thread| thread.reasoning_level.as_deref());
            let selected_id = current
                .or(default)
                .filter(|level| choices.iter().any(|choice| choice.id == *level))
                .or_else(|| {
                    choices
                        .iter()
                        .find(|choice| choice.id == "medium")
                        .map(|choice| choice.id.as_str())
                })
                .map(str::to_owned);
            let model_identifier = required_str(model, "model")?.to_owned();
            self.pending_model_id = Some(id.to_owned());
            self.send(UiEvent::SetReasoningChoices {
                model_identifier,
                choices,
                selected_id,
            });
            return Ok(());
        }
        self.commit_model(provider_id, model_id, Value::Null)
    }

    /// 第二层选择重新核验模型及等级，防止旧弹窗或伪造 UI ID 改写服务端偏好。
    fn select_reasoning(&mut self, level: &str) -> Result<(), CliError> {
        let id = self
            .pending_model_id
            .as_deref()
            .ok_or_else(|| CliError::usage("请先选择模型"))?;
        let (provider_id, model_id) = id
            .split_once('/')
            .ok_or_else(|| CliError::usage("模型选择无效"))?;
        let valid = self
            .config
            .get("effective")
            .and_then(|value| value.get("providers"))
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter(|provider| {
                provider.get("provider_id").and_then(Value::as_str) == Some(provider_id)
            })
            .filter_map(|provider| provider.get("models").and_then(Value::as_array))
            .flatten()
            .find(|model| model.get("model_id").and_then(Value::as_str) == Some(model_id))
            .and_then(|model| model.get("reasoning_level_map"))
            .and_then(Value::as_object)
            .is_some_and(|map| map.contains_key(level));
        if !valid {
            return Err(CliError::usage("选择的推理等级已不可用"));
        }
        let provider_id = provider_id.to_owned();
        let model_id = model_id.to_owned();
        self.commit_model(&provider_id, &model_id, json!(level))?;
        self.pending_model_id = None;
        Ok(())
    }

    /// 偏好提交沿用当前协作模式和 revision CAS；成功后回读权威状态供两端同步。
    fn commit_model(
        &mut self,
        provider_id: &str,
        model_id: &str,
        reasoning: Value,
    ) -> Result<(), CliError> {
        let prefs = self.current_preferences()?;
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        self.connection.request(
            "thread/preferences/update",
            json!({
                "threadId":thread.thread_id,"providerId":provider_id,"modelId":model_id,
                "reasoningLevel":reasoning,"accessMode":thread.access_mode,
                "collaborationMode":prefs.get("collaborationMode").and_then(Value::as_str).unwrap_or("default"),
                "expectedThreadRevision":thread.revision
            }),
        )?;
        if let Some(thread) = &mut self.thread {
            thread.provider_id = provider_id.into();
            thread.model_id = model_id.into();
            thread.reasoning_level = reasoning.as_str().map(str::to_owned);
        }
        self.refresh()
    }

    /// 权限更新也以 Java 当前 revision CAS 提交，不覆盖用户同时在桌面修改的偏好。
    fn select_permission(&mut self, id: &str) -> Result<(), CliError> {
        if !matches!(id, "approval_required" | "full_access") {
            return Err(CliError::usage("权限选择无效"));
        }
        let prefs = self.current_preferences()?;
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        self.connection.request("thread/preferences/update",json!({
            "threadId":thread.thread_id,"providerId":thread.provider_id,"modelId":thread.model_id,
            "reasoningLevel":prefs.get("reasoningLevel").cloned().unwrap_or(Value::Null),
            "accessMode":id,"collaborationMode":prefs.get("collaborationMode").and_then(Value::as_str).unwrap_or("default"),
            "expectedThreadRevision":thread.revision
        }))?;
        if let Some(thread) = &mut self.thread {
            thread.access_mode = id.into();
        }
        self.refresh()
    }

    /// 通过所在 Workspace 的完整 Thread 条目取得偏好；不以配置默认值覆盖已有会话选择。
    fn current_preferences(&mut self) -> Result<Value, CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let metadata = list_thread_in_workspace(
            &mut self.connection,
            &thread.workspace_id,
            &thread.thread_id,
        )?;
        metadata
            .get("preferences")
            .filter(|value| !value.is_null())
            .cloned()
            .ok_or_else(|| CliError::protocol("会话缺少偏好"))
    }

    /// 文件候选来自 App Server 的受预算搜索；服务端截断事实要显式提示用户缩小查询。
    fn search_files(
        &mut self,
        query: &str,
        thread_id: Option<String>,
        query_id: u64,
    ) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let result = self.connection.request("workspace/path/search",json!({
            "threadId":thread.thread_id,"workspaceId":thread.workspace_id,"query":query,"limit":50
        }))?;
        self.file_choices.clear();
        let choices = result
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("文件搜索缺少 items"))?
            .iter()
            .map(|item| {
                let path = required_str(item, "relativePath")?.to_owned();
                let kind = required_str(item, "kind")?.to_owned();
                self.file_choices.insert(path.clone(), kind.clone());
                Ok(UiChoice {
                    id: path.clone(),
                    label: path,
                    detail: Some(kind),
                })
            })
            .collect::<Result<Vec<_>, CliError>>()?;
        self.send(UiEvent::SetFileChoices {
            thread_id,
            query_id,
            choices,
        });
        if result.get("truncated").and_then(Value::as_bool) == Some(true) {
            self.notice("文件搜索已截断；继续输入以缩小范围".into());
        }
        Ok(())
    }

    /// 用户选择才把搜索候选变成结构化引用，`@path` 只是可编辑的视觉提示。
    fn select_file(&mut self, path: &str) -> Result<(), CliError> {
        let kind = self
            .file_choices
            .get(path)
            .cloned()
            .ok_or_else(|| CliError::usage("文件候选已过期"))?;
        let workspace_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .workspace_id
            .clone();
        self.send(UiEvent::InsertFileReference {
            workspace_id,
            relative_path: path.into(),
            kind,
        });
        Ok(())
    }

    /// Skill 目录短时缓存使连续输入只做内存过滤；分页、描述长度和总数仍有硬上限。
    fn search_skills(
        &mut self,
        query: &str,
        thread_id: Option<String>,
        query_id: u64,
    ) -> Result<(), CliError> {
        let needs_refresh = self
            .skill_catalog
            .as_ref()
            .is_none_or(|(loaded, _, _)| loaded.elapsed() >= Duration::from_secs(10));
        if needs_refresh {
            self.skill_catalog = Some(self.load_skill_catalog()?);
        }
        let (_, catalog, truncated) = self
            .skill_catalog
            .as_ref()
            .ok_or_else(|| CliError::protocol("技能目录未加载"))?;
        let query = query.to_lowercase();
        let matching = catalog
            .iter()
            .filter(|choice| choice.label.to_lowercase().contains(&query));
        let count = matching.clone().count();
        let choices: Vec<UiChoice> = matching.take(64).cloned().collect();
        self.skill_choices = choices
            .iter()
            .map(|choice| (choice.id.clone(), choice.label.clone()))
            .collect();
        self.send(UiEvent::SetSkillChoices {
            thread_id,
            query_id,
            choices,
        });
        if *truncated {
            self.notice("技能目录超过 1000 项，仅扫描了前 1000 项".into());
        } else if count > 64 {
            self.notice(format!(
                "匹配 {count} 个技能，仅显示前 64 个；继续输入以缩小范围"
            ));
        }
        Ok(())
    }

    /// 首次 `$` 输入按 JA-RPC 分页读取可用技能，缓存仅存短描述和 ID，不读取 Skill 正文。
    fn load_skill_catalog(&mut self) -> Result<(Instant, Vec<UiChoice>, bool), CliError> {
        let workspace_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .workspace_id
            .clone();
        let deadline = Instant::now() + DISCOVERY_DEADLINE;
        let mut cursor: Option<String> = None;
        let mut catalog = Vec::new();
        for _ in 0..5 {
            let mut params = json!({"workspaceId":workspace_id,"limit":200});
            if let Some(value) = &cursor {
                params["cursor"] = json!(value);
            }
            let page = self.connection.request_with_timeout(
                "skill/list",
                params,
                remaining_scan(deadline)?,
            )?;
            let items = page
                .get("items")
                .and_then(Value::as_array)
                .ok_or_else(|| CliError::protocol("技能目录缺少 items"))?;
            for item in items {
                if item.get("enabled").and_then(Value::as_bool) != Some(true)
                    || item.get("status").and_then(Value::as_str) != Some("healthy")
                {
                    continue;
                }
                catalog.push(UiChoice {
                    id: required_str(item, "skillId")?.to_owned(),
                    label: required_str(item, "name")?.to_owned(),
                    detail: item
                        .get("description")
                        .and_then(Value::as_str)
                        .map(|description| description.chars().take(240).collect()),
                });
            }
            cursor = page
                .get("nextCursor")
                .and_then(Value::as_str)
                .map(str::to_owned);
            if cursor.is_none() {
                break;
            }
        }
        Ok((Instant::now(), catalog, cursor.is_some()))
    }

    /// 选中的真实 skillId 留在 controller，Composer 只插入可编辑的 `$名称` 提示。
    fn select_skill(&mut self, id: &str) -> Result<(), CliError> {
        let name = self
            .skill_choices
            .get(id)
            .cloned()
            .ok_or_else(|| CliError::usage("技能候选已过期"))?;
        self.send(UiEvent::InsertSkillReference {
            skill_id: id.to_owned(),
            name,
        });
        Ok(())
    }

    /// 本地来源先经安全 staging 后由 Java 导入，UI 只收到脱敏名称与可移除 identity。
    fn attach_paths(&mut self, paths: &[PathBuf]) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let workspace_id = thread.workspace_id.clone();
        let root = PathBuf::from(&thread.workspace_root);
        if self.attachments.len() + paths.len() > 10 {
            return Err(CliError::usage("一次最多添加 10 个附件"));
        }
        let resolved = paths
            .iter()
            .map(|path| {
                if path.is_absolute() {
                    path.clone()
                } else {
                    root.join(path)
                }
            })
            .collect::<Vec<_>>();
        let mut imported =
            attachment::import_paths(&mut self.connection, &workspace_id, &resolved)?;
        self.attachments.append(&mut imported);
        self.send_attachments();
        Ok(())
    }

    /// 已导入但未发送的附件只有显式移除才调用 Java discard，避免本地 UI 伪删。
    fn remove_attachment(&mut self, id: &str) -> Result<(), CliError> {
        if !self.attachments.iter().any(|item| item.id == id) {
            return Err(CliError::usage("附件不在当前草稿"));
        }
        self.connection
            .request("attachment/discard", json!({"attachmentId":id}))?;
        self.attachments.retain(|item| item.id != id);
        self.send_attachments();
        Ok(())
    }

    /// 预览走 Java 的有界只读 session，关闭句柄后再将安全内容送给查看器。
    fn preview_attachment(&mut self, id: &str) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let item = self
            .attachments
            .iter()
            .find(|item| item.id == id)
            .ok_or_else(|| CliError::usage("附件不在当前草稿"))?;
        if !matches!(item.media_kind.as_str(), "text" | "image") {
            self.send(UiEvent::ShowDetails {
                entry_id: id.into(),
                title: item.name.clone(),
                body: format!("{} 附件 · {} B\n已加入草稿。", item.media_kind, item.size),
            });
            return Ok(());
        }
        let opened = self.connection.request(
            "attachment/preview/open",
            json!({
                "attachmentId":id,"authorization":{"kind":"draft","workspaceId":thread.workspace_id}
            }),
        )?;
        let session_id = required_str(&opened, "previewSessionId")?.to_owned();
        if opened.get("previewKind").and_then(Value::as_str) == Some("image") {
            let _ = self.connection.request(
                "attachment/preview/close",
                json!({"previewSessionId":session_id}),
            );
            self.send(UiEvent::ShowDetails {
                entry_id: id.into(),
                title: item.name.clone(),
                body: format!("图像附件 · {} B\n图像内容可在 Ja 桌面预览。", item.size),
            });
            return Ok(());
        }
        let result = self.connection.request(
            "attachment/preview/read",
            json!({
                "previewSessionId":session_id,"offsetBytes":0,"limitBytes":65536
            }),
        );
        let _ = self.connection.request(
            "attachment/preview/close",
            json!({"previewSessionId":session_id}),
        );
        let content = result?;
        let preview = content
            .get("contentBase64")
            .and_then(Value::as_str)
            .unwrap_or("");
        let bytes = base64::engine::general_purpose::STANDARD
            .decode(preview)
            .map_err(CliError::transport)?;
        let mut body = terminal_safe(&String::from_utf8_lossy(&bytes));
        if content.get("truncated").and_then(Value::as_bool) == Some(true) {
            body.push_str("\n（预览已截断）");
        }
        self.send(UiEvent::ShowDetails {
            entry_id: id.into(),
            title: item.name.clone(),
            body,
        });
        Ok(())
    }

    /// UI 回传的审批 identity 必须匹配当前快照，服务端 CAS 再裁决多客户端竞争。
    fn respond_approval(
        &mut self,
        approval_id: &str,
        thread_id: &str,
        turn_id: &str,
        revision: u64,
        choice: &str,
    ) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        if thread.thread_id != thread_id || !matches!(choice, "approve" | "deny") {
            return Err(CliError::usage("审批选择已过期"));
        }
        self.connection.request_operation("approval/respond",json!({
            "approvalId":approval_id,"turnId":turn_id,"decision":choice,"expectedThreadRevision":revision
        }))?;
        if let Err(error) = self.refresh() {
            self.notice(format!("审批已提交，状态暂无法刷新：{}", error.message()));
        }
        Ok(())
    }

    /// 多题答案整体提交，idempotency key 与 revision 原样来自当前交互快照。
    fn respond_interaction(
        &mut self,
        thread_id: &str,
        request_id: &str,
        revision: u64,
        idempotency_key: &str,
        answers: Vec<ja_cli::ui::InteractionAnswer>,
    ) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        if thread.thread_id != thread_id {
            return Err(CliError::usage("澄清请求已切换会话"));
        }
        let answers = answers.into_iter().map(|answer| json!({
            "questionId":answer.question_id,"optionIds":answer.option_ids,"freeText":answer.free_text,
            "skipped":answer.skipped
        })).collect::<Vec<_>>();
        self.connection.request(
            "interaction/respond",
            json!({
                "threadId":thread_id,"requestId":request_id,"expectedRevision":revision,
                "idempotencyKey":idempotency_key,"answers":answers
            }),
        )?;
        self.refresh()
    }

    /// 展示 Tool 的公开 detail；不存在的行直接拒绝，不能用任意文件路径打开查看器。
    fn open_details(&self, entry_id: &str) -> Result<(), CliError> {
        let snapshot = self.thread.as_ref().and_then(|thread| {
            projection::project(
                thread,
                &self.history,
                &self.interaction,
                &self.config,
                Some(&thread.title),
            )
            .ok()
        });
        let entry = snapshot
            .as_ref()
            .and_then(|snapshot| snapshot.timeline.iter().find(|entry| entry.id == entry_id))
            .ok_or_else(|| CliError::usage("详情已过期"))?;
        let title = match &entry.kind {
            TimelineKind::Tool { action, target } => format!("{action}  {target}"),
            _ => entry.text.lines().next().unwrap_or("详情").to_owned(),
        };
        self.send(UiEvent::ShowDetails {
            entry_id: entry_id.into(),
            title,
            body: entry.detail.clone().unwrap_or_else(|| entry.text.clone()),
        });
        Ok(())
    }

    /// 工具总览只投影安全 presentation；缺失会话或空结果必须显式反馈而非静默白屏。
    fn show_tools(&self) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let history = self.merged_history()?;
        let snapshot = projection::project(
            thread,
            &history,
            &self.interaction,
            &self.config,
            Some(&thread.title),
        )?;
        let body = snapshot
            .timeline
            .iter()
            .filter_map(|entry| match &entry.kind {
                TimelineKind::Tool { action, target } => Some(format!(
                    "{action}  {target}\n{}",
                    entry.detail.as_deref().unwrap_or("")
                )),
                _ => None,
            })
            .collect::<Vec<_>>()
            .join("\n\n");
        self.send(UiEvent::ShowDetails {
            entry_id: "tools".into(),
            title: "工具记录".into(),
            body: if body.is_empty() {
                "当前会话还没有工具记录".to_owned()
            } else {
                body
            },
        });
        Ok(())
    }

    /// Diff 内容通过 artifact identity 和逐文件受限读取取得，查看器只读且总量有界。
    fn show_diff(&mut self) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let turn = self
            .history
            .get("turns")
            .and_then(Value::as_array)
            .and_then(|turns| turns.last())
            .ok_or_else(|| CliError::usage("当前会话没有变更"))?;
        let turn_id = required_str(turn, "turnId")?.to_owned();
        let change_set = turn
            .get("changeSet")
            .filter(|value| !value.is_null())
            .ok_or_else(|| CliError::usage("当前 Turn 没有变更记录"))?;
        let artifact_id = required_str(change_set, "artifactId")?.to_owned();
        let files = change_set
            .get("files")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("变更记录缺少 files"))?;
        let mut body = String::new();
        for file in files.iter().take(12) {
            let path = required_str(file, "path")?;
            let result = self.connection.request("turn/change-set/read",json!({
                "threadId":thread.thread_id,"turnId":turn_id,"artifactId":artifact_id,"filePath":path
            }))?;
            let encoded = required_str(&result, "contentBase64")?;
            let bytes = base64::engine::general_purpose::STANDARD
                .decode(encoded)
                .map_err(CliError::transport)?;
            body.push_str(&format!(
                "\n--- {path} ---\n{}\n",
                terminal_safe(&String::from_utf8_lossy(&bytes))
            ));
            if body.len() > 300_000 {
                body.truncate(300_000);
                body.push_str("\n（详情已截断）");
                break;
            }
        }
        if files.len() > 12 {
            body.push_str("\n（仅显示前 12 个文件）");
        }
        self.send(UiEvent::ShowDetails {
            entry_id: "diff".into(),
            title: "本次变更".into(),
            body,
        });
        Ok(())
    }

    /// Plan 是独立能力，只读取当前 Thread 的服务端投影，不由 Goal 存在推断。
    fn show_plan(&mut self) -> Result<(), CliError> {
        let thread_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .thread_id
            .clone();
        let result = self
            .connection
            .request("plan/current/read", json!({"threadId":thread_id}))?;
        let body = match result.get("current").filter(|value| !value.is_null()) {
            Some(current) => format_plan(current)?,
            None => "当前会话没有 Plan".into(),
        };
        self.send(UiEvent::ShowDetails {
            entry_id: "plan".into(),
            title: "Plan".into(),
            body,
        });
        Ok(())
    }

    /// Goal 通过 Thread.activeGoalId 的权威关联读取，保持它与 Plan 各自独立。
    fn show_goal(&mut self) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let metadata = list_thread_in_workspace(
            &mut self.connection,
            &thread.workspace_id,
            &thread.thread_id,
        )?;
        let body = if let Some(goal_id) = metadata.get("activeGoalId").and_then(Value::as_str) {
            let result = self
                .connection
                .request("goal/read", json!({"goalId":goal_id}))?;
            format_goal(&result)?
        } else {
            "当前会话没有 Goal".into()
        };
        self.send(UiEvent::ShowDetails {
            entry_id: "goal".into(),
            title: "Goal".into(),
            body,
        });
        Ok(())
    }

    /// 展示已导入的草稿附件；发送或移除后立即重投影。
    fn send_attachments(&self) {
        self.send(UiEvent::SetAttachments(
            self.attachments
                .iter()
                .map(|item| UiChoice {
                    id: item.id.clone(),
                    label: item.name.clone(),
                    detail: Some(format!("{} B", item.size)),
                })
                .collect(),
        ));
    }

    /// 所有活动状态都由最近 Turn 快照读出，UI 本地动画不是取消目标来源。
    fn latest_active_turn(&self) -> Result<String, CliError> {
        self.history
            .get("turns")
            .and_then(Value::as_array)
            .and_then(|turns| turns.last())
            .filter(|turn| {
                matches!(
                    turn.get("status").and_then(Value::as_str),
                    Some("queued" | "running" | "waiting_approval" | "suspended")
                )
            })
            .and_then(|turn| turn.get("turnId"))
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| CliError::usage("当前没有可取消的 Turn"))
    }

    /// 空闲会话不轮询完整历史；活动 Turn 才用低频快照兜底丢失事件。
    fn latest_turn_is_active(&self) -> bool {
        self.history
            .get("turns")
            .and_then(Value::as_array)
            .and_then(|turns| turns.last())
            .is_some_and(|turn| {
                matches!(
                    turn.get("status").and_then(Value::as_str),
                    Some("queued" | "running" | "waiting_approval" | "suspended")
                )
            })
    }
}

/// 复用 App Server 的 Workspace 过滤、标题搜索与游标，避免客户端全局枚举后再过滤。
fn list_thread_choices(
    connection: &mut Connection,
    workspace_id: &str,
    query: &str,
    cursor: Option<&str>,
) -> Result<(Vec<UiChoice>, Option<String>), CliError> {
    let mut params = json!({
        "scope":"all",
        "workspaceId":workspace_id,
        "query":query,
        "limit":50
    });
    if let Some(cursor) = cursor {
        params["cursor"] = json!(cursor);
    }
    let result = connection.request("thread/list", params)?;
    let choices = result
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("历史列表缺少 items"))?
        .iter()
        .map(|item| {
            Ok(UiChoice {
                id: required_str(item, "threadId")?.into(),
                label: required_str(item, "title")?.into(),
                detail: item
                    .get("status")
                    .and_then(Value::as_str)
                    .map(|status| thread_status_label(status).to_owned()),
            })
        })
        .collect::<Result<Vec<_>, CliError>>()?;
    let next_cursor = result
        .get("nextCursor")
        .and_then(Value::as_str)
        .map(str::to_owned);
    Ok((choices, next_cursor))
}

/// 会话候选只翻译冻结的状态枚举，不对未知 wire 状态猜测完成事实。
fn thread_status_label(status: &str) -> &'static str {
    match status {
        "idle" => "空闲",
        "queued" | "running" | "waiting_approval" | "suspended" => "正在工作",
        "completed" => "已完成",
        "failed" => "失败",
        "cancelled" => "已取消",
        _ => "状态未知",
    }
}

/// 推理等级短标签统一中文，底层等级标识保持原值供 Java 校验。
pub(super) fn reasoning_label(level: &str) -> &'static str {
    match level {
        "off" => "关闭",
        "minimal" => "极简",
        "low" => "低",
        "medium" => "中",
        "high" => "高",
        "xhigh" => "超高",
        "max" => "最高",
        _ => "未知",
    }
}

/// 描述只说明等级的预期取舍，不承诺某个 Provider 的具体延迟或额度。
fn reasoning_description(level: &str) -> &'static str {
    match level {
        "off" => "不显式使用推理",
        "minimal" => "使用最短的可用推理过程",
        "low" => "更快响应，推理较轻",
        "medium" => "兼顾速度与推理深度，适合日常任务",
        "high" => "适合复杂问题的深入推理",
        "xhigh" => "适合复杂问题的更深入推理",
        "max" => "该模型支持的最高推理等级",
        _ => "",
    }
}

/// 观察后按 `thread/read`、`interaction/read`、`configuration/read` 顺序取得 Java 的一致事实。
fn read_baseline(
    connection: &mut Connection,
    thread_id: &str,
    workspace_id: &str,
) -> Result<(Value, Value, Value), CliError> {
    let history = connection.request(
        "thread/read",
        json!({"threadId":thread_id,"tail":true,"limit":200}),
    )?;
    let interaction = connection.request("interaction/read", json!({"threadId":thread_id}))?;
    let config = connection.request("configuration/read", json!({"workspaceId":workspace_id}))?;
    Ok((history, interaction, config))
}

/// 全局发现提供 workspace identity，随后读取完整条目以恢复原模型、权限和 Workspace 根。
fn load_context(connection: &mut Connection, thread_id: &str) -> Result<ThreadContext, CliError> {
    let deadline = Instant::now() + DISCOVERY_DEADLINE;
    let mut cursor: Option<String> = None;
    let mut workspace_id = None;
    for _ in 0..MAX_DISCOVERY_PAGES {
        let mut params = json!({"scope":"all","limit":200});
        if let Some(cursor) = &cursor {
            params["cursor"] = json!(cursor);
        }
        let result =
            connection.request_with_timeout("thread/list", params, remaining_scan(deadline)?)?;
        let items = result
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("全局会话目录缺少 items"))?;
        if let Some(found) = items
            .iter()
            .find(|item| item.get("threadId").and_then(Value::as_str) == Some(thread_id))
        {
            workspace_id = Some(required_str(found, "workspaceId")?.to_owned());
            break;
        }
        cursor = result
            .get("nextCursor")
            .and_then(Value::as_str)
            .map(str::to_owned);
        if cursor.is_none() {
            break;
        }
    }
    let workspace_id =
        workspace_id.ok_or_else(|| CliError::usage("未在有界历史目录中找到该会话"))?;
    let metadata = list_thread_in_workspace(connection, &workspace_id, thread_id)?;
    let record = list_workspace_by_id(connection, &workspace_id)?;
    let workspace = if record.get("kind").and_then(Value::as_str) == Some("project") {
        connection.request(
            "workspace/open",
            json!({
                "cwd":required_str(&record,"root")?,
                "displayName":required_str(&record,"displayName")?
            }),
        )?
    } else {
        connection.request("workspace/open", json!({"workspaceId":workspace_id}))?
    };
    if required_str(&workspace, "workspaceId")? != workspace_id {
        return Err(CliError::protocol("恢复会话的 Workspace identity 已变化"));
    }
    let root = required_str(&workspace, "root")?.to_owned();
    let prefs = metadata
        .get("preferences")
        .filter(|value| !value.is_null())
        .ok_or_else(|| CliError::protocol("会话缺少执行偏好"))?;
    Ok(ThreadContext {
        thread_id: thread_id.into(),
        title: required_str(&metadata, "title")?.into(),
        workspace_id,
        workspace_root: root,
        provider_id: required_str(prefs, "providerId")?.into(),
        model_id: required_str(prefs, "modelId")?.into(),
        reasoning_level: prefs
            .get("reasoningLevel")
            .and_then(Value::as_str)
            .map(str::to_owned),
        access_mode: required_str(prefs, "accessMode")?.into(),
        revision: required_u64(&metadata, "revision")?,
    })
}

/// 项目 Workspace 只能按已登记 root 重开；session 才允许按 ID 打开。
fn list_workspace_by_id(
    connection: &mut Connection,
    workspace_id: &str,
) -> Result<Value, CliError> {
    let deadline = Instant::now() + DISCOVERY_DEADLINE;
    let mut cursor: Option<String> = None;
    for _ in 0..MAX_DISCOVERY_PAGES {
        let mut params = json!({"limit":200});
        if let Some(cursor) = &cursor {
            params["cursor"] = json!(cursor);
        }
        let result =
            connection.request_with_timeout("workspace/list", params, remaining_scan(deadline)?)?;
        let items = result
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("Workspace 列表缺少 items"))?;
        if let Some(found) = items
            .iter()
            .find(|item| item.get("workspaceId").and_then(Value::as_str) == Some(workspace_id))
        {
            return Ok(found.clone());
        }
        cursor = result
            .get("nextCursor")
            .and_then(Value::as_str)
            .map(str::to_owned);
        if cursor.is_none() {
            break;
        }
    }
    Err(CliError::usage("未在有界 Workspace 目录中找到会话根"))
}

/// Workspace 级列表分页查找精确 Thread，不把 discovery 的简化状态冒充完整偏好。
fn list_thread_in_workspace(
    connection: &mut Connection,
    workspace_id: &str,
    thread_id: &str,
) -> Result<Value, CliError> {
    let deadline = Instant::now() + DISCOVERY_DEADLINE;
    let mut cursor: Option<String> = None;
    for _ in 0..MAX_DISCOVERY_PAGES {
        let mut params = json!({"workspaceId":workspace_id,"limit":200});
        if let Some(cursor) = &cursor {
            params["cursor"] = json!(cursor);
        }
        let result =
            connection.request_with_timeout("thread/list", params, remaining_scan(deadline)?)?;
        let items = result
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("Workspace 会话列表缺少 items"))?;
        if let Some(found) = items
            .iter()
            .find(|item| item.get("threadId").and_then(Value::as_str) == Some(thread_id))
        {
            return Ok(found.clone());
        }
        cursor = result
            .get("nextCursor")
            .and_then(Value::as_str)
            .map(str::to_owned);
        if cursor.is_none() {
            break;
        }
    }
    Err(CliError::usage("未在 Workspace 的有界历史目录中找到该会话"))
}

/// 每个发现页共用一个 wall deadline；慢后端不能把有界页数放大为长时间阻塞。
fn remaining_scan(deadline: Instant) -> Result<Duration, CliError> {
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err(CliError::transport("会话目录读取超过 5 秒预算"));
    }
    Ok(remaining)
}

/// Plan 查看器突出目标、当前状态和步骤进度，隐藏与阅读无关的 Wire 标识。
fn format_plan(current: &Value) -> Result<String, CliError> {
    let plan = current
        .get("plan")
        .ok_or_else(|| CliError::protocol("Plan 投影缺少 plan"))?;
    let mut body = format!(
        "{}\n状态：{}",
        required_str(plan, "objective")?,
        required_str(plan, "status")?
    );
    let definition = current
        .get("currentRevision")
        .filter(|value| !value.is_null())
        .or_else(|| current.get("draft").filter(|value| !value.is_null()));
    if let Some(steps) = definition
        .and_then(|value| value.get("steps"))
        .and_then(Value::as_array)
    {
        let executions = current.get("stepExecutions").and_then(Value::as_array);
        body.push_str("\n\n步骤\n");
        for (index, step) in steps.iter().enumerate() {
            let step_id = required_str(step, "stepId")?;
            let status = executions
                .and_then(|rows| {
                    rows.iter()
                        .find(|row| row.get("stepId").and_then(Value::as_str) == Some(step_id))
                })
                .and_then(|row| row.get("status"))
                .and_then(Value::as_str)
                .unwrap_or("pending");
            body.push_str(&format!(
                "{}. {} · {}\n",
                index + 1,
                required_str(step, "title")?,
                status
            ));
        }
    }
    Ok(body)
}

/// Goal 查看器只显示可判断的进度与待处理原因，避免把内部执行账本当成用户界面。
fn format_goal(result: &Value) -> Result<String, CliError> {
    let goal = result
        .get("goal")
        .ok_or_else(|| CliError::protocol("Goal 投影缺少 goal"))?;
    let mut body = format!(
        "{}\n状态：{} · {}",
        required_str(goal, "objective")?,
        required_str(goal, "status")?,
        required_str(goal, "phase")?
    );
    let complete = required_u64(goal, "completedRequiredSteps")?;
    let total = required_u64(goal, "totalRequiredSteps")?;
    if total > 0 {
        body.push_str(&format!("\n进度：{complete}/{total}"));
    }
    if let Some(reason) = goal.get("attentionReason").and_then(Value::as_str) {
        body.push_str(&format!("\n待处理：{reason}"));
    }
    if let Some(criteria) = goal.get("acceptanceCriteria").and_then(Value::as_array)
        && !criteria.is_empty()
    {
        body.push_str("\n\n验收条件\n");
        for criterion in criteria {
            body.push_str(&format!("• {}\n", required_str(criterion, "description")?));
        }
    }
    Ok(body)
}

/// 查看器只保留排版所需的换行与 Tab，外部文件内容不能向终端注入控制序列。
fn terminal_safe(value: &str) -> String {
    value
        .chars()
        .filter(|ch| *ch == '\n' || *ch == '\t' || !ch.is_control())
        .collect()
}

/// 已获服务端明确拒绝时给出可修正的失败提示，只有响应丢失才称“待核实”。
fn submission_notice(error: &CliError) -> String {
    let prefix = if error.is_uncertain() || (error.exit_code() == 4 && error.rpc_code.is_none()) {
        "提交结果待核实"
    } else {
        "发送失败"
    };
    format!("{prefix}：{}", error.message())
}
