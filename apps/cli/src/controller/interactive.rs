// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 终端 controller 串行消费用户动作与 JA-RPC 事件，Java 是所有业务状态的唯一 owner。

use super::args::{ResumeRequest, ResumeTarget};
use super::{
    CliError, ThreadContext,
    args::ReviewSource,
    attachment::{self, ImportedAttachment},
    canonical_workspace, create_thread,
    external_editor::{create_editor_draft, editor_command, run_editor},
    model_settings::{self, ModelSettingsRunner},
    open_workspace,
    preference_recovery::{
        PreferenceResolution, matches_preferences, resolve, update_then_readback_once,
    },
    projection, required_str, required_u64,
    resync_notification::{ResyncNotificationDeduper, parse_resync_required},
    review,
    rpc::{self, Connection},
    suspended_recovery::{
        PendingSuspendedControl, SuspendedTurnAction, confirms_acknowledgement,
        may_release_control_lock, observed_status, request_once, requires_history_poll,
    },
    usage::{
        self, UsageReadIdentity, UsageState, clear_event_before_thread_switch,
        context_usage_from_history, status_lines,
    },
};
#[path = "fork_recovery.rs"]
pub(super) mod fork_recovery;
#[path = "plan_recovery.rs"]
pub(super) mod plan_recovery;
use base64::Engine;
use fork_recovery::{ForkRecoveryPort, PendingFork};
use ja_cli::ui::{
    Composer, ModelSettingsOutcome, ModelSettingsRequest, ModelSettingsResult, PendingPrompt,
    TimelineEntry, TimelineKind, TimelineStatus, TuiBridge, UiAction, UiChoice, UiCommand, UiEvent,
    UiReference, UiReferenceKind, UiSnapshot, UiSubmitMode,
};
use ja_runtime::app_server_process::{EventPump, SessionEvent, parse_thread_usage_summary};
use plan_recovery::{PlanRunAction, can_resume_plan_creation, confirms_plan_transition};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, mpsc};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use unicode_segmentation::UnicodeSegmentation;

const EVENT_WAIT: Duration = Duration::from_millis(50);
const USAGE_REQUEST_TIMEOUT: Duration = Duration::from_secs(2);
const STATUS_READ_DEADLINE: Duration = Duration::from_secs(5);
/// 单轮只消费默认 EventPump 的帧预算，随后让动作循环处理退出、取消与显式刷新。
const MAX_READY_EVENTS_PER_USAGE_DRAIN: usize = 256;
const REFRESH_INTERVAL: Duration = Duration::from_secs(3);
const MAX_DISCOVERY_PAGES: usize = 100;
const DISCOVERY_DEADLINE: Duration = Duration::from_secs(5);
const ITEM_CONTENT_PAGE_CHARACTERS: u64 = 32_768;
const ITEM_DETAIL_MAX_BYTES: usize = 96 * 1024;
const ITEM_DETAIL_DEADLINE: Duration = Duration::from_secs(15);
const DIFF_READ_DEADLINE: Duration = Duration::from_secs(5);
const MAX_CLIPBOARD_DIAGNOSTIC_BYTES: usize = 512;
const EXPORT_DEADLINE: Duration = Duration::from_secs(60);
const MAX_EXPORT_PAGES: usize = 500;
const MAX_EXPORT_BYTES: usize = 64 * 1024 * 1024;
const MAX_EXPORT_ARTIFACT_BYTES: usize = 4 * 1024 * 1024;
const REVIEW_COLLECTION_DEADLINE: Duration = Duration::from_secs(120);
const REVIEW_POLL_INTERVAL: Duration = Duration::from_millis(750);

/// 回执未知时保留写操作前的 Plan/Run 身份，只允许用该身份核实目标状态。
struct PlanMutationAttempt<'a> {
    action: PlanRunAction,
    method: &'a str,
    thread_id: &'a str,
    plan_id: &'a str,
    previous_revision: u64,
    previous_run_id: Option<&'a str>,
}

/// @author kongweiguang
/// 将已核验的单页内容按语义命名，避免调用方误换正文、Turn 元数据和下一游标的顺序。
struct OlderHistoryPage {
    items: Vec<Value>,
    turns: Vec<Value>,
    entries: Vec<TimelineEntry>,
    next_cursor: Option<String>,
}

/// Thread 只有在元数据、首屏历史和展示投影都已通过校验后才可成为当前会话。
pub(super) struct PreparedThreadActivation {
    thread: ThreadContext,
    history: Value,
    interaction: Value,
    config: Value,
    context_usage: Option<ja_runtime::app_server_process::ThreadContextUsageDto>,
    snapshot: UiSnapshot,
    fork_reply_choices: Vec<UiChoice>,
}

struct Interactive {
    connection: Connection,
    pump: EventPump,
    bridge: TuiBridge,
    model_settings: Option<ModelSettingsRunner>,
    thread: Option<ThreadContext>,
    usage: UsageState,
    workspace_root: PathBuf,
    thread_list_workspace_id: String,
    history: Value,
    older_items: Vec<Value>,
    older_turns: Vec<Value>,
    older_cursor: Option<String>,
    fork_reply_base_choices: Vec<UiChoice>,
    fork_reply_older_choices: Vec<UiChoice>,
    fork_reply_cursor: Option<String>,
    fork_retry_item_id: Option<String>,
    interaction: Value,
    config: Value,
    pending_model_id: Option<String>,
    preferences_sync_pending: bool,
    file_choices: HashMap<String, String>,
    skill_choices: HashMap<String, String>,
    skill_catalog: Option<(Instant, Vec<UiChoice>, bool)>,
    attachments: Vec<ImportedAttachment>,
    interaction_key: Option<(String, String)>,
    last_stream_seq: u64,
    live_assistant_entry_id: Option<String>,
    live_reasoning_entry_id: Option<String>,
    work_activity_pending: bool,
    seen_resync_notifications: ResyncNotificationDeduper,
    next_refresh: Instant,
    refresh_pending: bool,
    connection_lost: bool,
    pending_input: Option<PendingInput>,
    pending_fork: Option<PendingFork>,
    fork_journal_directory: PathBuf,
    pending_suspended_control: Option<PendingSuspendedControl>,
    thread_list_scope_all: bool,
    resume_prompt: Option<String>,
    review_collection: Option<ReviewCollection>,
    active_reviews: Vec<ActiveReview>,
    review_notices: HashMap<String, String>,
    diff_detail: Option<DiffDetail>,
    diff_worker: Option<JoinHandle<()>>,
}

/// 面板拥有冻结轮次和取消标记；重试不会切换到读取期间新完成的 Turn。
struct DiffDetail {
    entry_id: String,
    thread_id: String,
    history: Value,
    cancelled: Arc<AtomicBool>,
    pending: bool,
}

impl Drop for DiffDetail {
    /// 关闭、切换或退出立即取消后续读取；在途只读 RPC 由总 deadline 收口。
    fn drop(&mut self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

/// @author kongweiguang
/// thread/read 的来源标记是执行权限边界；缺字段时不能把继承历史推断成当前会话事实。
pub(super) fn validate_history_origins(history: &Value) -> Result<(), CliError> {
    for (field, label) in [("items", "历史项"), ("turns", "Turn")] {
        let values = history
            .get(field)
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol(format!("thread/read 缺少 {field}")))?;
        for value in values {
            history_origin(value, label)?;
        }
    }
    Ok(())
}

/// @author kongweiguang
/// origin 必须来自服务端明示；默认 native 会让只读继承项看起来可执行。
fn history_origin(value: &Value, label: &str) -> Result<&'static str, CliError> {
    match required_str(value, "origin")? {
        "native" => Ok("native"),
        "inherited" => Ok("inherited"),
        _ => Err(CliError::protocol(format!(
            "{label} origin 必须是 native 或 inherited"
        ))),
    }
}

/// @author kongweiguang
/// 候选只从已读取的 FINAL_ANSWER 投影生成，并保留真实 Item identity 作为服务端截断锚点。
pub(super) fn fork_reply_choices(history: &Value) -> Result<Vec<UiChoice>, CliError> {
    validate_history_origins(history)?;
    let items = history
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 items"))?;
    let turns = history
        .get("turns")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 turns"))?;
    let turns_by_id = turns
        .iter()
        .map(|turn| Ok((required_str(turn, "turnId")?, history_origin(turn, "Turn")?)))
        .collect::<Result<HashMap<_, _>, CliError>>()?;
    let mut choices = Vec::new();
    for item in items {
        if item.get("kind").and_then(Value::as_str) != Some("final_answer") {
            continue;
        }
        let turn_id = required_str(item, "turnId")?;
        let turn_origin = turns_by_id
            .get(turn_id)
            .copied()
            .ok_or_else(|| CliError::protocol("final_answer 没有关联 Turn"))?;
        let item_origin = history_origin(item, "历史项")?;
        if item_origin != turn_origin {
            return Err(CliError::protocol("历史项 origin 与所属 Turn 不一致"));
        }
        let timestamp = item
            .get("createdAt")
            .and_then(Value::as_str)
            .map(|value| {
                let display = value.replace('T', " ");
                if let Some(utc) = display.strip_suffix('Z') {
                    format!("{utc} UTC")
                } else {
                    display
                }
            })
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| "时间未知".to_owned());
        let summary = item
            .get("text")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");
        let summary = summary.graphemes(true).take(96).collect::<String>();
        let summary = if item.get("truncated").and_then(Value::as_bool) == Some(true) {
            format!("{summary}…")
        } else if summary.is_empty() {
            "（暂无正文摘要）".to_owned()
        } else {
            summary
        };
        choices.push(UiChoice {
            id: required_str(item, "itemId")?.to_owned(),
            label: timestamp,
            detail: Some(format!(
                "{} · {summary}",
                if item_origin == "inherited" {
                    "继承"
                } else {
                    "本会话"
                }
            )),
        });
    }
    Ok(choices)
}

/// @author kongweiguang
/// 分叉选择器的游标属于当前权威历史页，不能借用会被 Transcript 消费的旧页游标。
pub(super) fn fork_reply_page_cursor(history: &Value) -> Option<String> {
    history
        .get("nextCursor")
        .and_then(Value::as_str)
        .map(str::to_owned)
}

/// @author kongweiguang
/// 旧页先于当前页展示，按 Item identity 去重以容忍两个独立游标读到相同边界页。
fn combined_fork_reply_choices(older: &[UiChoice], current: &[UiChoice]) -> Vec<UiChoice> {
    let mut seen = HashSet::new();
    older
        .iter()
        .chain(current)
        .filter(|choice| seen.insert(choice.id.as_str()))
        .cloned()
        .collect()
}

/// @author kongweiguang
/// 冲突后仅在原 Item 已加载时消费优先选择；未加载前保留 identity 供后续摘要页定位。
pub(super) fn take_loaded_fork_retry_item(
    preferred_item_id: &mut Option<String>,
    choices: &[UiChoice],
) -> Option<String> {
    if preferred_item_id
        .as_deref()
        .is_some_and(|item_id| choices.iter().any(|choice| choice.id == item_id))
    {
        preferred_item_id.take()
    } else {
        None
    }
}

/// @author kongweiguang
/// 先完成新 Thread 的全部只读读取和投影校验，再允许调用方切换活动 identity。
pub(super) fn prepare_thread_activation(
    mut thread: ThreadContext,
    read: impl FnOnce(&ThreadContext) -> Result<(Value, Value, Value), CliError>,
) -> Result<PreparedThreadActivation, CliError> {
    let (history, interaction, config) = read(&thread)?;
    validate_history_origins(&history)?;
    let revision = required_u64(&history, "revision")?;
    thread.revision = revision;
    let context_usage = context_usage_from_history(&history)
        .map_err(|_| CliError::protocol("thread/read.contextUsage 无效"))?;
    let snapshot = projection::project(
        &thread,
        &history,
        &interaction,
        &config,
        Some(&thread.title),
    )?;
    let fork_reply_choices = fork_reply_choices(&history)?;
    Ok(PreparedThreadActivation {
        thread,
        history,
        interaction,
        config,
        context_usage,
        snapshot,
        fork_reply_choices,
    })
}

/// 已知 child 的 journal 只允许打开既有 Thread；只有还没有 child identity 时才能提交。
#[derive(Debug, Eq, PartialEq)]
pub(super) enum PendingForkAction {
    OpenExistingChild(String),
    SubmitFrozenRequest,
}

/// @author kongweiguang
/// 重试入口统一保留原 source owner 和 child identity，避免已创建操作退化为第二次创建。
pub(super) fn pending_fork_action(
    request: &PendingFork,
    current_thread_id: Option<&str>,
) -> Result<PendingForkAction, CliError> {
    if !request.allows_opening_from(current_thread_id) {
        return Err(CliError::usage(
            "待核实分叉属于其他会话；切回来源会话后按 F5 核实或重试。",
        ));
    }
    Ok(match &request.child_thread_id {
        Some(child_thread_id) => PendingForkAction::OpenExistingChild(child_thread_id.clone()),
        None => PendingForkAction::SubmitFrozenRequest,
    })
}

/// @author kongweiguang
/// 只识别服务端明确的 fork source revision CAS 拒绝，其他 RPC 失败仍必须保留原操作身份。
pub(super) fn is_fork_revision_conflict_message(message: &str) -> bool {
    message.contains("thread fork source revision changed")
}

/// 仅保留回执未知的原输入身份；F5 查询账本，不生成第二个 Turn 或重放正文。
struct PendingInput {
    operation_key: String,
    method: &'static str,
    text: String,
}

/// Git 快照采集在可取消 worker 上运行，控制器只导入并提交冻结结果。
struct ReviewCollection {
    thread_id: String,
    workspace_id: String,
    cancellation: Arc<AtomicBool>,
    receiver: mpsc::Receiver<Result<ja_review::ReviewSnapshot, CliError>>,
    worker: Option<JoinHandle<()>>,
    result: Option<Result<ja_review::ReviewSnapshot, CliError>>,
    cancel_requested: bool,
}

/// Review 报告在终态读回前始终绑定来源 Thread、Turn 和确切快照。
struct ActiveReview {
    run: review::ReviewRun,
    snapshot: ja_review::ReviewSnapshot,
    status: String,
    next_poll: Instant,
    cancel_requested: bool,
    read_error_noticed: bool,
}

/// @author kongweiguang
/// 恢复候选绑定 Workspace identity；启动正文先复用编辑器预算校验，避免接入后台后才发现无法展示或恢复。
/// 若当前恢复的来源 Thread 有待核实分叉，启动即只读核验 owner 回执并打开既有 child，避免恢复操作依赖用户先发现 F5。
pub fn run(
    cwd: Option<PathBuf>,
    initial_prompt: Option<String>,
    resume: Option<ResumeRequest>,
) -> Result<(), CliError> {
    let resume_prompt = resume.as_ref().and_then(|request| request.prompt.clone());
    if [initial_prompt.as_deref(), resume_prompt.as_deref()]
        .into_iter()
        .flatten()
        .any(|prompt| !Composer::new().set_text(prompt))
    {
        return Err(CliError::usage(
            "输入内容过长，超过交互编辑器的 32 KiB 容量；请缩短正文或使用文件引用。",
        ));
    }
    let mut connection = Connection::connect_or_start()?;
    let fork_journal_directory = rpc::runtime_home()?.join("data/cli/forks");
    let journal_scan = PendingFork::read_journals(&fork_journal_directory);
    let mut pending_forks = journal_scan.pending;
    let fork_journal_error =
        (!journal_scan.errors.is_empty()).then(|| journal_scan.errors.join("；"));
    let resume_target = resume.as_ref().map(|request| request.target.clone());
    let (
        thread,
        workspace_root,
        thread_list_workspace_id,
        choices,
        thread_next_cursor,
        global_scope,
    ) = if let Some(ResumeTarget::ThreadId(id)) = resume_target.as_ref() {
        let thread = load_context(&mut connection, id)?;
        let workspace_id = thread.workspace_id.clone();
        let root = PathBuf::from(&thread.workspace_root);
        (Some(thread), root, workspace_id, Vec::new(), None, false)
    } else if matches!(resume_target, Some(ResumeTarget::All)) {
        let root = canonical_workspace(cwd)?;
        let workspace = open_workspace(&mut connection, &root)?;
        let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
        let (choices, next_cursor) = list_all_thread_choices(&mut connection, "", None)?;
        (None, root, workspace_id, choices, next_cursor, true)
    } else if matches!(resume_target, Some(ResumeTarget::Last)) {
        let root = canonical_workspace(cwd)?;
        let workspace = open_workspace(&mut connection, &root)?;
        let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
        match last_thread_in_workspace(&mut connection, &workspace_id)? {
            Some(id) => {
                let thread = load_context(&mut connection, &id)?;
                (Some(thread), root, workspace_id, Vec::new(), None, false)
            }
            None => (None, root, workspace_id, Vec::new(), None, false),
        }
    } else if resume.is_some() {
        let root = canonical_workspace(cwd)?;
        let workspace = open_workspace(&mut connection, &root)?;
        let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
        let (choices, next_cursor) = list_thread_choices(&mut connection, &workspace_id, "", None)?;
        (None, root, workspace_id, choices, next_cursor, false)
    } else {
        let root = canonical_workspace(cwd)?;
        let workspace = open_workspace(&mut connection, &root)?;
        let workspace_id = required_str(&workspace, "workspaceId")?.to_owned();
        let configuration =
            connection.request("configuration/read", json!({"workspaceId":workspace_id}))?;
        let has_default_model = configuration.get("effective").is_some_and(|effective| {
            effective
                .get("default_provider_id")
                .and_then(Value::as_str)
                .is_some()
                && effective
                    .get("default_model_id")
                    .and_then(Value::as_str)
                    .is_some()
        });
        if has_default_model {
            let thread = create_thread(&mut connection, &root, true)?;
            (Some(thread), root, workspace_id, Vec::new(), None, false)
        } else {
            let (choices, next_cursor) =
                list_thread_choices(&mut connection, &workspace_id, "", None)?;
            (None, root, workspace_id, choices, next_cursor, false)
        }
    };
    let pending_fork = thread
        .as_ref()
        .and_then(|thread| PendingFork::take_for_thread(&mut pending_forks, &thread.thread_id));
    let has_other_pending_forks = !pending_forks.is_empty();
    if resume_prompt.is_some() && thread.is_none() && choices.is_empty() {
        return Err(CliError::usage("没有可续接的会话；请先恢复或新建会话"));
    }
    if let Some(thread) = &thread {
        connection.observe(&thread.thread_id)?;
    }
    let pump = connection.take_event_pump()?;
    let (history, interaction, config) = match &thread {
        Some(thread) => read_baseline(
            &mut connection,
            &thread.thread_id,
            &thread.workspace_id,
            &Value::Null,
            None,
        )?,
        None => (Value::Null, Value::Null, Value::Null),
    };
    let mut usage = UsageState::default();
    let usage_now = Instant::now();
    usage.bind_connection_generation(1, usage_now);
    if let Some(thread) = &thread {
        usage.bind_thread(Some(&thread.thread_id), usage_now);
        usage.set_context(
            &thread.thread_id,
            usage.connection_generation(),
            context_usage_from_history(&history)
                .map_err(|_| CliError::protocol("thread/read.contextUsage 无效"))?,
        );
    }
    let mut snapshot = match &thread {
        Some(thread) => {
            projection::project(thread, &history, &interaction, &config, Some(&thread.title))?
        }
        None => UiSnapshot::default(),
    };
    snapshot.usage = usage.snapshot();
    snapshot.has_older_history = history.get("nextCursor").and_then(Value::as_str).is_some();
    if resume.is_some() && thread.is_none() && choices.is_empty() {
        snapshot.notice = Some("当前项目暂无可恢复会话；可用 /new 新建，或 /resume 重试".into());
    } else if resume.is_none() && thread.is_none() {
        snapshot.notice =
            Some("尚未配置可用模型；用 /model 打开管理模型，或先浏览项目历史。".into());
    }
    snapshot.thread_choices = choices;
    snapshot.thread_next_cursor = thread_next_cursor;
    let interaction_key = match &snapshot.pending_prompt {
        Some(PendingPrompt::Clarification {
            interaction_id,
            operation_key,
            ..
        }) => Some((interaction_id.clone(), operation_key.clone())),
        _ => None,
    };
    let bridge = TuiBridge::spawn(snapshot).map_err(CliError::transport)?;
    let mut session = Interactive {
        connection,
        pump,
        bridge,
        model_settings: None,
        thread,
        usage,
        workspace_root,
        thread_list_workspace_id,
        history,
        older_items: Vec::new(),
        older_turns: Vec::new(),
        older_cursor: None,
        fork_reply_base_choices: Vec::new(),
        fork_reply_older_choices: Vec::new(),
        fork_reply_cursor: None,
        fork_retry_item_id: None,
        interaction,
        config,
        pending_model_id: None,
        preferences_sync_pending: false,
        file_choices: HashMap::new(),
        skill_choices: HashMap::new(),
        skill_catalog: None,
        attachments: Vec::new(),
        interaction_key,
        last_stream_seq: 0,
        live_assistant_entry_id: None,
        live_reasoning_entry_id: None,
        work_activity_pending: false,
        seen_resync_notifications: ResyncNotificationDeduper::default(),
        next_refresh: Instant::now() + REFRESH_INTERVAL,
        refresh_pending: false,
        connection_lost: false,
        pending_input: None,
        pending_fork,
        fork_journal_directory,
        pending_suspended_control: None,
        thread_list_scope_all: global_scope,
        resume_prompt,
        review_collection: None,
        active_reviews: Vec::new(),
        review_notices: HashMap::new(),
        diff_detail: None,
        diff_worker: None,
    };
    session.older_cursor = session
        .history
        .get("nextCursor")
        .and_then(Value::as_str)
        .map(str::to_owned);
    session.fork_reply_cursor = fork_reply_page_cursor(&session.history);
    session.reset_stream_cursor();
    if let Some(prompt) = initial_prompt {
        session.send(UiEvent::BeginSubmission(prompt.clone()));
        if let Err(error) =
            session.submit(prompt.clone(), Vec::new(), UiSubmitMode::Immediate, None)
        {
            if session.pending_input.is_none() {
                session.send(UiEvent::RestoreDraft(prompt));
            }
            session.notice(submission_notice(&error));
        }
    }
    if session.thread.is_some()
        && let Some(prompt) = session.resume_prompt.take()
    {
        session.send_resume_prompt(prompt);
    }
    if let Some(error) = fork_journal_error {
        session.notice(format!("{error}；请保留记录并检查 Ja data 目录。"));
    }
    if session.pending_fork.is_some() {
        session.notice("存在待核实的分叉操作；按 F5 查询原回执。".into());
    } else if has_other_pending_forks {
        session.notice("其他会话仍有待核实的分叉；切回来源后可恢复。".into());
    }
    if session.pending_fork.is_some() {
        session.reconcile_pending_fork();
    }
    let result = session.event_loop();
    session.diff_detail = None;
    session.cancel_review_collection_on_exit();
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
    /// @author kongweiguang
    /// 串行处理动作并用同一退避节奏观察活动 Turn；用量读取前先排空已就绪事件以合并持久事实。
    fn event_loop(&mut self) -> Result<(), CliError> {
        loop {
            self.poll_model_settings();
            self.poll_diff_read();
            while let Some(action) = self.bridge.try_recv_action().map_err(CliError::transport)? {
                if matches!(action, UiAction::Quit) {
                    self.cancel_review_collection_on_exit();
                    return Ok(());
                }
                let show_status_after_reconnect =
                    matches!(&action, UiAction::Command(UiCommand::ShowStatus));
                if self.connection_lost
                    && (matches!(action, UiAction::RefreshSnapshot) || show_status_after_reconnect)
                {
                    let result = self.reconnect();
                    self.handle_connection_result(result)?;
                    if show_status_after_reconnect
                        && !self.connection_lost
                        && let Err(error) = self.show_status()
                    {
                        self.notice(error.message().to_owned());
                    }
                    continue;
                }
                let result = self.handle_action(action);
                if self.connection.take_replaced() {
                    let result = self.restore_replaced_connection();
                    self.handle_connection_result(result)?;
                }
                if let Err(error) = result {
                    self.notice(error.message().to_owned());
                    // 单次动作失败可能只是本地文件或瞬时 RPC 故障；会话观察循环继续运行，
                    // 由 EOF/重连分支重新取得权威状态，绝不重发刚才的动作。
                }
            }
            if self.connection_lost {
                if Instant::now() >= self.next_refresh {
                    let result = self.reconnect();
                    self.handle_connection_result(result)?;
                }
                self.poll_review_collection()?;
                // 已关闭的 pump 会立即返回 EOF；等待键盘而不是对死连接忙轮询。
                std::thread::sleep(EVENT_WAIT);
                continue;
            }
            if let Some(event) = self.pump.next_event(EVENT_WAIT) {
                self.handle_session_event(event)?;
            }
            self.poll_review_collection()?;
            self.poll_active_reviews()?;
            if requires_history_poll(
                self.latest_turn_is_active(),
                self.refresh_pending,
                self.pending_suspended_control.is_some(),
            ) && Instant::now() >= self.next_refresh
            {
                let result = self.refresh();
                self.handle_background_result(result)?;
            }
            let drain_started = Instant::now();
            let due_identity = usage::drain_ready_before(
                drain_started,
                MAX_READY_EVENTS_PER_USAGE_DRAIN,
                EVENT_WAIT,
                || {
                    if self.connection_lost {
                        return Ok(usage::ReadyEventDrain::Idle(None));
                    }
                    if let Some(event) = self.pump.next_event(Duration::ZERO) {
                        self.handle_session_event(event)?;
                        return Ok(usage::ReadyEventDrain::Handled);
                    }
                    Ok(usage::ReadyEventDrain::Idle(
                        self.usage.take_due_refresh(Instant::now()),
                    ))
                },
            )?;
            if let Some(Some(identity)) = due_identity {
                self.read_usage_summary(identity);
            }
        }
    }

    /// @author kongweiguang
    /// 把控制队列中的持久事实交给同一 reducer；重连事件可能替换 pump，调用方随后重查新队列。
    fn handle_session_event(&mut self, event: SessionEvent) -> Result<(), CliError> {
        match event {
            SessionEvent::Notification(frame) => {
                if let (Some(method), Some(params)) = (frame.method(), frame.params()) {
                    let result = self.handle_notification(method, params);
                    self.handle_background_result(result)?;
                }
            }
            SessionEvent::Eof
            | SessionEvent::ProcessExited { .. }
            | SessionEvent::HandshakeFailed
            | SessionEvent::ProtocolFault(_)
            | SessionEvent::QueueFatalOverflow(_) => {
                let result = self.reconnect();
                self.handle_connection_result(result)?;
            }
            SessionEvent::QueueOverflow(_) => {
                let result = self.refresh();
                self.handle_background_result(result)?;
            }
            _ => {}
        }
        Ok(())
    }

    /// 断线恢复只连接已有 owner；持续失败时保留输入并允许 F5 或退出，绝不启动副作用重放。
    fn handle_connection_result(&mut self, result: Result<(), CliError>) -> Result<(), CliError> {
        if recover_background_read(result, &mut self.connection_lost, &mut self.next_refresh)?
            .is_some()
        {
            self.notice("后台连接中断，草稿已保留；F5 重连。".into());
        }
        if self.connection_lost
            && let Some(identity) = self.usage.active_identity()
            && self.usage.mark_summary_stale(&identity)
        {
            self.publish_usage();
        }
        Ok(())
    }

    /// 恢复提示独占操作行；只安排下一次读取，不清空草稿、关闭面板或解除未知提交锁。
    fn handle_background_result(&mut self, result: Result<(), CliError>) -> Result<(), CliError> {
        if let Some(message) =
            recover_background_read(result, &mut self.refresh_pending, &mut self.next_refresh)?
        {
            self.notice(message);
        }
        Ok(())
    }

    /// 掉线后核实偏好、对话与原发送回执；成功连接不能覆盖待核实操作的恢复入口，也不重发业务请求。
    fn reconnect(&mut self) -> Result<(), CliError> {
        let thread_id = self.thread.as_ref().map(|thread| thread.thread_id.clone());
        self.notice("连接中断，正在读取后台状态…".into());
        let mut connection = self.connection.reconnect_existing()?;
        if let Some(thread_id) = &thread_id {
            connection.observe(thread_id)?;
        }
        let pump = connection.take_event_pump()?;
        self.connection = connection;
        self.pump = pump;
        self.usage.advance_connection_generation(Instant::now());
        self.publish_usage();
        self.refresh()?;
        self.refresh_usage_summary_now();
        self.connection_lost = false;
        if self.pending_input.is_some() {
            self.refresh_snapshot();
        } else {
            self.notice("已恢复后台连接".into());
        }
        Ok(())
    }

    /// 替换 WebSocket 后恢复唯一观察并核实偏好，避免旧底栏与新连接的对话状态混用。
    fn restore_replaced_connection(&mut self) -> Result<(), CliError> {
        if let Some(thread) = &self.thread {
            self.connection.observe(&thread.thread_id)?;
        }
        self.pump = self.connection.take_event_pump()?;
        self.usage.advance_connection_generation(Instant::now());
        self.publish_usage();
        self.refresh()?;
        self.refresh_usage_summary_now();
        self.connection_lost = false;
        Ok(())
    }

    /// 两类公开增量共用权威序号；摘要实时接续，同一次模型正文保持身份以防终态重复。
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
        if method == "turn/resync-required" {
            let notification = parse_resync_required(params)
                .map_err(|reason| CliError::protocol(format!("无效的恢复通知：{reason}")))?;
            if notification.thread_id != thread.thread_id
                || notification.workspace_id != thread.workspace_id
            {
                return Ok(());
            }
            if self
                .seen_resync_notifications
                .remember_once(&notification.event_id)
            {
                // 观察仍绑定同一 Thread；只读最新快照恢复投影，不重新 observe 或重发业务动作。
                self.usage.schedule_summary_refresh(Instant::now());
                self.publish_usage();
                return self.refresh();
            }
            return Ok(());
        }
        if params.get("threadId").and_then(Value::as_str) != Some(&thread.thread_id) {
            return Ok(());
        }
        if UsageState::summary_changes_on_notification(method) {
            self.usage.schedule_summary_refresh(Instant::now());
            self.publish_usage();
        }
        if method == "thread/metadata-changed" {
            return self.refresh();
        }
        if method == "turn/retry-started" {
            let turn_id = required_str(params, "turnId")?.to_owned();
            self.live_assistant_entry_id = None;
            self.live_reasoning_entry_id = None;
            if !self.work_activity_pending {
                self.send(UiEvent::FinishAssistantStream { turn_id });
            }
            self.work_activity_pending = true;
            return self.refresh();
        }
        if matches!(
            method,
            "assistant/text-delta" | "assistant/reasoning-summary-delta"
        ) {
            let turn_id = required_str(params, "turnId")?;
            if projection::turn_is_terminal(&self.history, turn_id) {
                return Ok(());
            }
            let sequence = required_u64(params, "streamSeq")?;
            if sequence <= self.last_stream_seq {
                return Ok(());
            }
            if sequence != self.last_stream_seq + 1 {
                return self.refresh();
            }
            self.last_stream_seq = sequence;
            let text = params
                .get("text")
                .and_then(Value::as_str)
                .ok_or_else(|| CliError::protocol("流事件缺少 text"))?;
            let reasoning = method == "assistant/reasoning-summary-delta";
            let prefix = if reasoning { "live-reasoning" } else { "live" };
            let active_id = if reasoning {
                &self.live_reasoning_entry_id
            } else {
                &self.live_assistant_entry_id
            };
            let current_id = active_id
                .as_deref()
                .filter(|id| id.starts_with(&format!("{prefix}:{turn_id}:")))
                .map(str::to_owned);
            if let Some(id) = current_id {
                self.send(UiEvent::AppendStreamDelta {
                    entry_id: id,
                    delta: text.into(),
                });
            } else {
                let id = format!("{prefix}:{turn_id}:{sequence}");
                self.send(UiEvent::UpsertEntry(TimelineEntry {
                    id: id.clone(),
                    kind: if reasoning {
                        TimelineKind::ReasoningSummary
                    } else {
                        TimelineKind::Assistant
                    },
                    text: text.into(),
                    detail: None,
                    status: Some(TimelineStatus::Running),
                }));
                if reasoning {
                    self.live_reasoning_entry_id = Some(id);
                } else {
                    self.live_assistant_entry_id = Some(id);
                }
            }
            self.work_activity_pending = reasoning;
        } else {
            self.refresh()?;
        }
        Ok(())
    }

    /// 跨端改名与偏好更新从完整 Thread 元数据回读；未知写入回执在读回前禁止再次提交。
    fn reload_metadata(&mut self) -> Result<(), CliError> {
        let connection = &mut self.connection;
        reload_thread_metadata(
            &mut self.thread,
            &mut self.preferences_sync_pending,
            |workspace_id, thread_id| list_thread_in_workspace(connection, workspace_id, thread_id),
        )
    }

    /// 先完整解析整个权威快照再改本地身份，避免不完整偏好解除写入锁或留下半更新 revision。
    fn apply_thread_metadata(&mut self, metadata: &Value) -> Result<(), CliError> {
        apply_thread_metadata_snapshot(
            &mut self.thread,
            &mut self.preferences_sync_pending,
            metadata,
        )
    }

    /// 标题与偏好先从权威 Thread 元数据核实；读失败保留已确认值，再独立尝试刷新对话。
    fn refresh(&mut self) -> Result<(), CliError> {
        let metadata_result = self.reload_metadata();
        let history_result = self.refresh_history();
        match (metadata_result, history_result) {
            (Ok(()), Ok(())) => Ok(()),
            (Err(error), Ok(())) | (Ok(()), Err(error)) => Err(error),
            (Err(metadata_error), Err(history_error)) => {
                if !history_error.read_retryable {
                    Err(history_error)
                } else {
                    Err(metadata_error)
                }
            }
        }
    }

    /// @author kongweiguang
    /// 普通刷新保留各 RPC 的常规时限；诊断刷新可传入绝对预算而不改变其他调用方。
    fn refresh_history(&mut self) -> Result<(), CliError> {
        self.refresh_history_with_deadline(None)
    }

    /// @author kongweiguang
    /// 完整复用快照投影与待处理控制对账；状态诊断的所有读取共用一个绝对截止时间。
    fn refresh_history_with_deadline(&mut self, deadline: Option<Instant>) -> Result<(), CliError> {
        let Some((thread_id, workspace_id)) = self
            .thread
            .as_ref()
            .map(|thread| (thread.thread_id.clone(), thread.workspace_id.clone()))
        else {
            return Ok(());
        };
        let usage_generation = self.usage.connection_generation();
        let (history, interaction, config) = read_baseline(
            &mut self.connection,
            &thread_id,
            &workspace_id,
            &self.history,
            deadline,
        )?;
        let context_usage = context_usage_from_history(&history)
            .map_err(|_| CliError::protocol("thread/read.contextUsage 无效"))?;
        let revision = required_u64(&history, "revision")?;
        if deadline.is_some()
            && self
                .thread
                .as_ref()
                .is_some_and(|thread| revision < thread.revision)
        {
            return Err(CliError::protocol("thread/read 修订早于当前会话元数据"));
        }
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
        if self.fork_reply_older_choices.is_empty() {
            self.fork_reply_cursor = fork_reply_page_cursor(&self.history);
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
        if !self
            .usage
            .set_context(&thread_id, usage_generation, context_usage)
        {
            return Err(CliError::protocol(
                "thread/read.contextUsage 与当前会话连接不一致",
            ));
        }
        snapshot.usage = self.usage.snapshot();
        snapshot.has_older_history = self.older_cursor.is_some();
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
        self.fork_reply_base_choices = fork_reply_choices(&merged_history)?;
        self.send(UiEvent::ReplaceSnapshot(Box::new(snapshot)));
        self.send(UiEvent::SetForkReplyPage {
            choices: combined_fork_reply_choices(
                &self.fork_reply_older_choices,
                &self.fork_reply_base_choices,
            ),
            next_cursor: self.fork_reply_cursor.clone(),
            selected_id: None,
            prepend: false,
        });
        // 权威快照仍显示同一请求时允许用户显式重试；绝不由客户端自动重发审批或答案。
        self.send(UiEvent::ReleasePromptSubmission);
        self.next_refresh = Instant::now() + REFRESH_INTERVAL;
        if self.refresh_pending {
            let message = if self.pending_suspended_control.is_some() {
                "后台状态已恢复；恢复或取消仍待核实，后台会继续只读观察，按 F5 可立即核实。"
            } else {
                "后台状态已恢复，可以继续操作。"
            };
            self.notice(message.into());
        }
        self.refresh_pending = false;
        self.reconcile_pending_suspended_control(false);
        Ok(())
    }

    /// 快照游标跳过已读增量，正文和摘要分别沿用首段身份，重连不会把摘要再次追加到回答后。
    fn reset_stream_cursor(&mut self) {
        let stream = self
            .history
            .get("liveStream")
            .filter(|stream| !stream.is_null());
        self.last_stream_seq = stream
            .and_then(|value| value.get("streamSeq"))
            .and_then(Value::as_u64)
            .unwrap_or(0);
        self.live_assistant_entry_id = stream.and_then(resumed_live_assistant_entry_id);
        self.live_reasoning_entry_id = stream.and_then(resumed_live_reasoning_entry_id);
        self.work_activity_pending = stream
            .and_then(|value| value.get("segments"))
            .and_then(Value::as_array)
            .and_then(|segments| segments.last())
            .is_some_and(|segment| {
                segment.get("kind").and_then(Value::as_str) != Some("assistant")
            });
    }

    /// @author kongweiguang
    /// 单次动作只读一页并按 Thread 身份回传增量，避免翻历史时刷新配置或用量而重置视口。
    fn load_older(&mut self, requested_thread_id: &str) -> Result<(), CliError> {
        if !self
            .thread
            .as_ref()
            .is_some_and(|thread| thread.thread_id == requested_thread_id)
        {
            return Ok(());
        }
        let Some(cursor) = self.older_cursor.clone() else {
            self.send(UiEvent::OlderHistoryLoaded {
                thread_id: requested_thread_id.to_owned(),
                entries: Vec::new(),
                has_older: false,
            });
            return Ok(());
        };

        match self.read_older_page(requested_thread_id, &cursor) {
            Ok(page) => {
                if !self
                    .thread
                    .as_ref()
                    .is_some_and(|thread| thread.thread_id == requested_thread_id)
                {
                    return Ok(());
                }
                let mut combined = page.items;
                combined.append(&mut self.older_items);
                self.older_items = combined;
                let mut combined_turns = page.turns;
                combined_turns.append(&mut self.older_turns);
                self.older_turns = combined_turns;
                self.older_cursor = page.next_cursor;
                self.send(UiEvent::OlderHistoryLoaded {
                    thread_id: requested_thread_id.to_owned(),
                    entries: page.entries,
                    has_older: self.older_cursor.is_some(),
                });
            }
            Err(error) => {
                if self
                    .thread
                    .as_ref()
                    .is_some_and(|thread| thread.thread_id == requested_thread_id)
                {
                    self.send(UiEvent::OlderHistoryFailed {
                        thread_id: requested_thread_id.to_owned(),
                        notice: format!(
                            "读取更早历史失败；当前内容已保留，按 F5 重试。({})",
                            error.message()
                        ),
                    });
                }
            }
        }
        Ok(())
    }

    /// @author kongweiguang
    /// Picker 翻页只解析同 revision 的摘要并推进独立游标；正文 hydration 和 Transcript 位置保持不变。
    fn load_more_fork_replies(&mut self, requested_thread_id: &str) -> Result<(), CliError> {
        if !self
            .thread
            .as_ref()
            .is_some_and(|thread| thread.thread_id == requested_thread_id)
        {
            return Ok(());
        }
        let Some(cursor) = self.fork_reply_cursor.clone() else {
            return Ok(());
        };
        let expected_revision = self
            .thread
            .as_ref()
            .map(|thread| thread.revision)
            .ok_or_else(|| CliError::protocol("分叉回复分页期间 Thread 已切换"))?;
        let result = (|| {
            let page = self.connection.request(
                "thread/read",
                json!({"threadId":requested_thread_id,"tail":true,"cursor":cursor,"limit":200}),
            )?;
            if required_str(&page, "threadId")? != requested_thread_id {
                return Err(CliError::protocol("分叉回复页属于另一个 Thread"));
            }
            validate_history_origins(&page)?;
            if required_u64(&page, "revision")? != expected_revision {
                return Err(CliError::usage(
                    "来源历史在加载期间发生变化；请退出 /fork 后重新打开以刷新候选",
                ));
            }
            let next_cursor = match page.get("nextCursor") {
                Some(Value::Null) => None,
                Some(Value::String(value)) => Some(value.clone()),
                _ => return Err(CliError::protocol("分叉回复页缺少有效 nextCursor")),
            };
            if next_cursor.as_deref() == Some(cursor.as_str()) {
                return Err(CliError::protocol("分叉回复分页游标未前进"));
            }
            Ok((fork_reply_choices(&page)?, next_cursor))
        })();
        match result {
            Ok((choices, next_cursor)) => {
                if !self
                    .thread
                    .as_ref()
                    .is_some_and(|thread| thread.thread_id == requested_thread_id)
                {
                    return Ok(());
                }
                self.fork_reply_older_choices =
                    combined_fork_reply_choices(&choices, &self.fork_reply_older_choices);
                self.fork_reply_cursor = next_cursor;
                let page_choices = combined_fork_reply_choices(
                    &self.fork_reply_older_choices,
                    &self.fork_reply_base_choices,
                );
                let selected_id =
                    take_loaded_fork_retry_item(&mut self.fork_retry_item_id, &page_choices);
                self.send(UiEvent::SetForkReplyPage {
                    choices,
                    next_cursor: self.fork_reply_cursor.clone(),
                    selected_id,
                    prepend: true,
                });
            }
            Err(error) => {
                if self
                    .thread
                    .as_ref()
                    .is_some_and(|thread| thread.thread_id == requested_thread_id)
                {
                    self.notice(format!(
                        "加载更早分叉回复失败；现有候选已保留，可按 PageDown 重试或退出后重开 /fork。({})",
                        error.message()
                    ));
                }
            }
        }
        Ok(())
    }

    /// @author kongweiguang
    /// 只将尚未展示的当前页转成增量，完整正文通过公共内容读取接口补齐后再投影。
    fn read_older_page(
        &mut self,
        thread_id: &str,
        cursor: &str,
    ) -> Result<OlderHistoryPage, CliError> {
        let mut page = self.connection.request(
            "thread/read",
            json!({"threadId":thread_id,"tail":true,"cursor":cursor,"limit":200}),
        )?;
        if required_str(&page, "threadId")? != thread_id {
            return Err(CliError::protocol("历史页属于另一个 Thread"));
        }
        validate_history_origins(&page)?;
        let next_cursor = match page.get("nextCursor") {
            Some(Value::Null) => None,
            Some(Value::String(value)) => Some(value.clone()),
            _ => return Err(CliError::protocol("历史页缺少有效 nextCursor")),
        };
        if next_cursor.as_deref() == Some(cursor) {
            return Err(CliError::protocol("历史分页游标未前进"));
        }
        let page_items = page
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("历史页缺少 items"))?;
        let latest_items = self
            .history
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("thread/read 缺少 items"))?;
        let mut items = unseen_history_page_items(&self.older_items, latest_items, page_items)?;
        for item in &mut items {
            hydrate_public_history_item(item, |item_id, total_characters| {
                read_public_item_content(
                    &mut self.connection,
                    thread_id,
                    item_id,
                    total_characters,
                    None,
                )
            })?;
        }
        let current = self
            .thread
            .as_ref()
            .filter(|thread| thread.thread_id == thread_id)
            .ok_or_else(|| CliError::protocol("历史页读取期间 Thread 已切换"))?;
        let item_turn_ids = items
            .iter()
            .filter_map(|item| item.get("turnId").and_then(Value::as_str))
            .collect::<HashSet<_>>();
        let turns = page
            .get("turns")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("历史页缺少 turns"))?
            .iter()
            .filter(|turn| {
                turn.get("turnId")
                    .and_then(Value::as_str)
                    .is_some_and(|turn_id| item_turn_ids.contains(turn_id))
            })
            .cloned()
            .collect::<Vec<_>>();
        page["items"] = Value::Array(items.clone());
        page["turns"] = Value::Array(turns.clone());
        page["liveStream"] = Value::Null;
        let snapshot = projection::project(
            current,
            &page,
            &self.interaction,
            &self.config,
            Some(&current.title),
        )?;
        Ok(OlderHistoryPage {
            items,
            turns,
            entries: snapshot.timeline,
            next_cursor,
        })
    }

    /// @author kongweiguang
    /// 失败时不清除选区；成功事件由 UI 据 selection identity 决定何时关闭高亮。
    fn copy_transcript_selection(&self, selection_id: u64, text: &str) -> Result<(), CliError> {
        let event = match copy_to_clipboard(text) {
            Ok(()) => UiEvent::TranscriptSelectionCopied { selection_id },
            Err(error) => UiEvent::TranscriptSelectionCopyFailed {
                selection_id,
                notice: format!("复制失败，已保留选区：{}", error.message()),
            },
        };
        self.send(event);
        Ok(())
    }

    /// @author kongweiguang
    /// 最新页与已请求旧页在渲染前按持久身份合并，避免丢失回复来源及对应 Turn 元数据。
    fn merged_history(&self) -> Result<Value, CliError> {
        let latest = self
            .history
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("thread/read 缺少 items"))?;
        let mut positions = HashMap::with_capacity(self.older_items.len() + latest.len());
        let mut items = Vec::with_capacity(self.older_items.len() + latest.len());
        for item in self.older_items.iter().chain(latest) {
            let id = required_str(item, "itemId")?;
            if let Some(position) = positions.get(id).copied() {
                items[position] = item.clone();
            } else {
                positions.insert(id.to_owned(), items.len());
                items.push(item.clone());
            }
        }
        let mut merged = self.history.clone();
        merged["items"] = Value::Array(items);
        let latest_turns = self
            .history
            .get("turns")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("thread/read 缺少 turns"))?;
        merged["turns"] = Value::Array(merged_history_turns(&self.older_turns, latest_turns)?);
        Ok(merged)
    }

    /// 同一澄清请求跨事件刷新始终使用同一个幂等键，响应未知时不得换键重发。
    fn stabilize_interaction_key(&mut self, snapshot: &mut UiSnapshot) {
        match &mut snapshot.pending_prompt {
            Some(PendingPrompt::Clarification {
                interaction_id,
                operation_key,
                ..
            }) => {
                if let Some((known_interaction, known_key)) = &self.interaction_key
                    && known_interaction == interaction_id
                {
                    *operation_key = known_key.clone();
                    return;
                }
                self.interaction_key = Some((interaction_id.clone(), operation_key.clone()));
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

    /// 用独立、堆分配的 Usage 事件更新 Thread，避免膨胀事件队列或替换 Composer/Timeline。
    fn publish_usage(&self) {
        let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.clone()) else {
            return;
        };
        self.send(UiEvent::SetUsage {
            thread_id,
            usage: self.usage.snapshot().map(Box::new),
        });
    }

    /// @author kongweiguang
    /// F5 与成功重连共用固定时限；失败仅保存 stale 标记，不改写动作提示。
    fn refresh_usage_summary_now(&mut self) {
        if let Some(identity) = self.usage.request_refresh_now() {
            self.read_usage_summary(identity);
        }
    }

    /// 聚合 revision 必须追上最近 thread/read 水位，低于其时保留旧值并提示待刷新。
    fn read_usage_summary(&mut self, identity: UsageReadIdentity) {
        self.read_usage_summary_with_timeout(identity, USAGE_REQUEST_TIMEOUT);
    }

    /// @author kongweiguang
    /// 诊断读取复用同一身份门控，并把单次 RPC 限制在 `/status` 的剩余绝对 deadline 内。
    fn read_usage_summary_with_timeout(&mut self, identity: UsageReadIdentity, timeout: Duration) {
        let Some(minimum_revision) = self
            .thread
            .as_ref()
            .filter(|thread| thread.thread_id == identity.thread_id)
            .map(|thread| thread.revision)
        else {
            return;
        };
        let result = self
            .connection
            .request_with_timeout(
                "thread/usage/read",
                json!({"threadId":identity.thread_id.clone()}),
                timeout,
            )
            .and_then(|value| {
                parse_thread_usage_summary(value)
                    .map_err(|_| CliError::protocol("thread/usage/read 返回无效摘要"))
            });
        match result {
            Ok(summary) => {
                if !self
                    .usage
                    .apply_summary(&identity, minimum_revision, summary)
                {
                    self.usage.mark_summary_stale(&identity);
                }
            }
            Err(_) => {
                self.usage.mark_summary_stale(&identity);
            }
        }
        self.publish_usage();
    }

    /// @author kongweiguang
    /// 动作校验身份与 CAS；新动作必须绑定 Thread/选区身份，避免异步 UI 回执改写已切换视图。
    fn handle_action(&mut self, action: UiAction) -> Result<(), CliError> {
        if self.pending_suspended_control.is_some()
            && !matches!(
                &action,
                UiAction::RefreshSnapshot
                    | UiAction::Command(UiCommand::ShowStatus)
                    | UiAction::OpenModelSettings
                    | UiAction::ModelSettings(_)
                    | UiAction::CloseModelSettings { .. }
                    | UiAction::EditDraft { .. }
                    | UiAction::OpenDetails { .. }
                    | UiAction::CloseDetails { .. }
                    | UiAction::LoadOlder { .. }
                    | UiAction::LoadMoreForkReplies { .. }
                    | UiAction::CopyTranscriptSelection { .. }
                    | UiAction::Quit
            )
        {
            if let UiAction::Submit { text, .. } = &action {
                self.send(UiEvent::RestoreDraft(text.clone()));
            }
            self.notice(
                "恢复或取消结果待核实；按 F5 只读核实，当前 Thread 与 Turn 已锁定。".into(),
            );
            return Ok(());
        }
        match action {
            UiAction::OpenModelSettings => self.open_model_settings()?,
            UiAction::ModelSettings(request) => self.request_model_settings(request),
            UiAction::CloseModelSettings { start_conversation } => {
                if start_conversation && self.thread.is_none() {
                    self.start_after_model_settings(false)?;
                } else {
                    self.model_settings = None;
                    if self.thread.is_some() {
                        self.refresh()?;
                    }
                    self.show_model_choices()?;
                }
            }
            UiAction::ConfirmModelSettingsWorkspaceTrust { trusted } => {
                if trusted {
                    self.start_after_model_settings(true)?;
                }
            }
            UiAction::Submit {
                text,
                references,
                mode,
            } => {
                if let Err(error) = self.submit(text.clone(), references, mode, None) {
                    if self.pending_input.is_none() {
                        self.send(UiEvent::RestoreDraft(text));
                    }
                    self.notice(submission_notice(&error));
                }
            }
            UiAction::CancelTurn => {
                if let Err(error) = self.cancel_turn() {
                    self.send(UiEvent::CancellationFailed(format!(
                        "中断尚未确认；F5 核实状态，Esc 可重试。({})",
                        error.message()
                    )));
                }
            }
            UiAction::ResumeSuspendedTurn { thread_id } => {
                self.control_suspended_turn(&thread_id, SuspendedTurnAction::Resume)?;
            }
            UiAction::CancelSuspendedTurn { thread_id } => {
                self.control_suspended_turn(&thread_id, SuspendedTurnAction::Cancel)?;
            }
            UiAction::CancelLocalOperation => {
                self.cancel_local_operation()?;
            }
            UiAction::ContinueReply => self.continue_reply()?,
            UiAction::Command(command) => self.command(command)?,
            UiAction::CreatePlan { objective } => {
                if let Err(error) = self.create_plan(&objective) {
                    if self.pending_input.is_none() {
                        self.send(UiEvent::RestoreDraft(format!("/plan create {objective}")));
                    }
                    let message = if error.is_uncertain() {
                        format!(
                            "Plan 创建结果待核实；运行 /plan status 确认当前状态后再试。({})",
                            error.message()
                        )
                    } else {
                        format!("Plan 创建失败：{}", error.message())
                    };
                    self.notice(message);
                }
            }
            UiAction::EditDraft { draft } => self.edit_draft(&draft)?,
            UiAction::ExportThread { path } => self.export_thread(path)?,
            UiAction::ForkReply { item_id } => {
                if let Err(error) = self.fork_thread_at(&item_id) {
                    self.send(UiEvent::RestoreForkDraft);
                    return Err(error);
                }
            }
            UiAction::SelectModel { id } => self.select_model(&id)?,
            UiAction::SelectReasoning { id } => self.select_reasoning(&id)?,
            UiAction::SelectPermission { id } => self.select_permission(&id)?,
            UiAction::SelectThread { id } => self.switch_thread(&id)?,
            UiAction::LoadOlder { thread_id } => self.load_older(&thread_id)?,
            UiAction::LoadMoreForkReplies { thread_id } => {
                self.load_more_fork_replies(&thread_id)?
            }
            UiAction::CopyTranscriptSelection { selection_id, text } => {
                self.copy_transcript_selection(selection_id, &text)?
            }
            UiAction::RefreshSnapshot => self.refresh_snapshot(),
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
                if result.is_err() && self.refresh().is_err() {
                    self.notice("提交结果暂未核实；按 F5 读取后台状态，Esc 返回。".into());
                    return Ok(());
                }
                result?;
            }
            UiAction::RespondInteraction {
                thread_id,
                interaction_id,
                expected_interaction_revision,
                operation_key,
                answers,
            } => {
                let result = self.respond_interaction(
                    &thread_id,
                    &interaction_id,
                    expected_interaction_revision,
                    &operation_key,
                    answers,
                );
                if result.is_err() && self.refresh().is_err() {
                    self.notice("提交结果暂未核实；按 F5 读取后台状态，Esc 返回。".into());
                    return Ok(());
                }
                result?;
            }
            UiAction::OpenDetails { entry_id } if entry_id.starts_with("diff:") => {
                self.retry_diff(&entry_id);
            }
            UiAction::OpenDetails { entry_id } => self.open_details(&entry_id)?,
            UiAction::CloseDetails { entry_id } => {
                if self
                    .diff_detail
                    .as_ref()
                    .is_some_and(|detail| detail.entry_id == entry_id)
                {
                    self.diff_detail = None;
                }
            }
            UiAction::Quit => {}
        }
        Ok(())
    }

    /// @author kongweiguang
    /// 刷新前保留原 Thread 身份；F5 先恢复匹配该会话的 operation journal，再只读核实其原回执。
    fn refresh_snapshot(&mut self) {
        self.refresh_usage_summary_now();
        if let Some(pending) = &self.pending_input {
            match self
                .connection
                .lookup_operation(&pending.operation_key, pending.method)
            {
                Ok(receipt) if receipt.get("accepted").and_then(Value::as_bool) == Some(true) => {
                    self.pending_input = None;
                    self.accept_input();
                }
                Ok(receipt) if receipt.get("accepted").and_then(Value::as_bool) == Some(false) => {
                    let pending = self.pending_input.take().expect("原操作仍待核实");
                    self.send(UiEvent::RestoreDraft(pending.text));
                    self.notice("发送未被接纳，输入已恢复；修正后按 Enter 重试。".into());
                    return;
                }
                Ok(_) | Err(_) => {
                    self.notice("发送结果待核实；按 F5 查询原操作，勿重复发送。".into());
                    return;
                }
            }
        }
        if self.pending_fork.is_none()
            && let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.clone())
        {
            self.restore_pending_fork_for_thread(&thread_id);
        }
        if self.pending_fork.is_some() {
            self.reconcile_pending_fork();
            return;
        }
        let had_unverified_preferences = self.preferences_sync_pending;
        let metadata_result = self.reload_metadata();
        let snapshot_result = self.refresh_history();
        let snapshot_read_succeeded = snapshot_result.is_ok();

        match (metadata_result, snapshot_result) {
            (Ok(()), Ok(())) if had_unverified_preferences && !self.preferences_sync_pending => {
                self.notice("偏好已由后台核实，界面状态已更新；可以继续操作。".into());
            }
            (Ok(()), Ok(())) if self.preferences_sync_pending => {
                self.notice("偏好尚未核实；按 F5 重读后台状态，Esc 返回。".into());
            }
            (Ok(()), Ok(())) => self.notice("后台状态已更新。".into()),
            (Err(metadata_error), Ok(())) if self.preferences_sync_pending => self.notice(format!(
                "偏好尚未核实，后台对话状态已更新；按 F5 重读，Esc 返回。({})",
                metadata_error.message()
            )),
            (Err(metadata_error), Ok(())) => self.notice(format!(
                "后台对话状态已更新，但偏好元数据未读取；按 F5 重试。({})",
                metadata_error.message()
            )),
            (Ok(()), Err(snapshot_error)) if self.preferences_sync_pending => self.notice(format!(
                "偏好尚未核实；对话界面未更新，按 F5 重试，Esc 返回。({})",
                snapshot_error.message()
            )),
            (Ok(()), Err(snapshot_error)) if had_unverified_preferences => self.notice(format!(
                "偏好已由后台核实，但对话界面未更新；按 F5 重试，Esc 返回。({})",
                snapshot_error.message()
            )),
            (Ok(()), Err(snapshot_error)) => self.notice(format!(
                "后台状态刷新失败；按 F5 重试。({})",
                snapshot_error.message()
            )),
            (Err(metadata_error), Err(snapshot_error)) if self.preferences_sync_pending => self
                .notice(format!(
                    "偏好尚未核实，后台状态也未更新；按 F5 重试，Esc 返回。({}); ({})",
                    metadata_error.message(),
                    snapshot_error.message()
                )),
            (Err(metadata_error), Err(snapshot_error)) => self.notice(format!(
                "后台状态刷新失败；按 F5 重试。({}); ({})",
                metadata_error.message(),
                snapshot_error.message()
            )),
        }
        if snapshot_read_succeeded {
            self.reconcile_pending_suspended_control(true);
        }
    }

    /// 先读当前 Thread 再固定 Turn/revision；恢复与取消各发送一次，未知回执只读核实。
    fn control_suspended_turn(
        &mut self,
        requested_thread_id: &str,
        action: SuspendedTurnAction,
    ) -> Result<(), CliError> {
        if self.pending_suspended_control.is_some() {
            self.notice("恢复或取消结果待核实；按 F5 只读核实，不能重复提交。".into());
            return Ok(());
        }
        let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.clone()) else {
            self.send(UiEvent::SetSuspendedControlPending(false));
            return Err(CliError::usage("请先选择会话"));
        };
        if thread_id != requested_thread_id {
            self.send(UiEvent::SetSuspendedControlPending(false));
            self.notice("会话已变化，未发送恢复或取消；请先读取当前状态。".into());
            return Ok(());
        }
        if let Err(error) = self.refresh_history() {
            self.send(UiEvent::SetSuspendedControlPending(false));
            return Err(error);
        }
        if self.thread.as_ref().map(|thread| thread.thread_id.as_str()) != Some(requested_thread_id)
        {
            self.send(UiEvent::SetSuspendedControlPending(false));
            self.notice("会话已变化，未发送恢复或取消；请先读取当前状态。".into());
            return Ok(());
        }
        if self
            .interaction
            .get("request")
            .filter(|request| !request.is_null())
            .is_some_and(|request| request.get("status").and_then(Value::as_str) == Some("pending"))
        {
            self.send(UiEvent::SetSuspendedControlPending(false));
            self.notice("当前会话仍有待补充问题；请先处理问题卡片。".into());
            return Ok(());
        }
        let latest_is_suspended = self
            .latest_native_turn()
            .is_some_and(|turn| turn.get("status").and_then(Value::as_str) == Some("suspended"));
        if !latest_is_suspended {
            self.send(UiEvent::SetSuspendedControlPending(false));
            self.notice("后台状态已变化；未发送恢复或取消，请按 F5 核实。".into());
            return Ok(());
        }
        let turn_id = match projection::earliest_suspended_turn_id(&self.history) {
            Ok(Some(turn_id)) => turn_id,
            Ok(None) => {
                self.send(UiEvent::SetSuspendedControlPending(false));
                return Err(CliError::protocol("暂停状态没有可定位的挂起 Turn"));
            }
            Err(error) => {
                self.send(UiEvent::SetSuspendedControlPending(false));
                return Err(error);
            }
        };
        let expected_revision = match self.thread.as_ref().map(|thread| thread.revision) {
            Some(revision) => revision,
            None => {
                self.send(UiEvent::SetSuspendedControlPending(false));
                return Err(CliError::usage("请先选择会话"));
            }
        };
        self.pending_suspended_control = Some(PendingSuspendedControl {
            thread_id: requested_thread_id.to_owned(),
            turn_id: turn_id.clone(),
            action,
            acknowledged: false,
        });
        self.send(UiEvent::SetSuspendedControlPending(true));

        let result = request_once(action, &turn_id, expected_revision, |method, params| {
            self.connection.request(method, params)
        });
        match result {
            Ok(response)
                if confirms_acknowledgement(action, &turn_id, expected_revision, &response) =>
            {
                if let Some(pending) = &mut self.pending_suspended_control {
                    pending.acknowledged = true;
                }
            }
            Ok(_) => {}
            Err(error) if error.rpc_code.is_some() => {
                self.pending_suspended_control = None;
                self.send(UiEvent::SetSuspendedControlPending(false));
                return Err(error);
            }
            Err(_) => {}
        }

        if let Err(error) = self.refresh_history() {
            self.refresh_pending = error.read_retryable;
            self.notice(format!(
                "恢复或取消结果待核实；按 F5 只读核实，不会自动重试。({})",
                error.message()
            ));
            return Ok(());
        }
        Ok(())
    }

    /// 同身份读回按动作核实；挂起态需显式 F5，取消还必须读到终态才解锁。
    fn reconcile_pending_suspended_control(&mut self, explicit_read: bool) {
        let Some(pending) = self.pending_suspended_control.as_ref().cloned() else {
            return;
        };
        if self.thread.as_ref().map(|thread| thread.thread_id.as_str())
            != Some(pending.thread_id.as_str())
        {
            return;
        }
        let Some(status) = observed_status(&self.history, &pending.thread_id, &pending.turn_id)
        else {
            let message = if explicit_read {
                "F5 未能核实原 Thread/Turn；保持操作锁，再按 F5 重读。"
            } else {
                "原 Thread/Turn 尚未核实；保持操作锁，后台会继续只读观察，按 F5 可立即核实。"
            };
            self.notice(message.into());
            return;
        };
        if !may_release_control_lock(pending.action, status, explicit_read) {
            let notice = if pending.action == SuspendedTurnAction::Cancel {
                format!(
                    "取消尚未读到终态（当前为 {status}）；保持操作锁，后台继续只读观察，按 F5 可立即核实。"
                )
            } else {
                format!(
                    "原 Turn 状态仍未确认（当前为 {status}）；保持操作锁，后台继续只读观察，按 F5 可立即核实。"
                )
            };
            self.notice(notice);
            return;
        }
        self.pending_suspended_control = None;
        self.send(UiEvent::SetSuspendedControlPending(false));
        if status == "suspended" {
            let detail = if pending.acknowledged {
                "请求收到成功回执，但后台仍显示暂停或中断"
            } else {
                "恢复或取消结果未确认，后台仍显示暂停或中断"
            };
            self.notice(format!("{detail}；可重新按 F6 恢复或 F7 取消。"));
        } else {
            let action = match pending.action {
                SuspendedTurnAction::Resume => "恢复",
                SuspendedTurnAction::Cancel => "取消",
            };
            self.notice(format!("已核实原 Turn 的{action}状态：{status}。"));
        }
    }

    /// 输入按原协议提交；Plan 规划 Turn 可要求 PLAN 模式并拒绝转成已有 Turn 的 steering。
    fn submit(
        &mut self,
        text: String,
        mut references: Vec<UiReference>,
        mode: UiSubmitMode,
        required_collaboration_mode: Option<&str>,
    ) -> Result<(), CliError> {
        let thread_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .thread_id
            .clone();
        if self
            .review_collection
            .as_ref()
            .is_some_and(|collection| collection.thread_id == thread_id)
        {
            return Err(CliError::usage(
                "正在读取当前会话的审查快照；完成或取消后再发送任务",
            ));
        }
        if self.has_active_review(&thread_id) {
            return Err(CliError::usage("代码审查运行中；完成或取消后再发送任务"));
        }
        if let Some(required) = required_collaboration_mode {
            self.ensure_preferences_verified()?;
            let preferences = self.current_preferences()?;
            if required_str(&preferences, "collaborationMode")? != required {
                return Err(CliError::usage(
                    "Thread 规划模式已变化；未启动 Plan 规划 Turn，请重新运行 /plan create",
                ));
            }
        }
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
            .latest_native_turn()
            .filter(|turn| {
                matches!(
                    turn.get("status").and_then(Value::as_str),
                    Some("queued" | "running" | "waiting_input" | "waiting_approval" | "suspended")
                )
            })
            .and_then(|turn| turn.get("turnId"))
            .and_then(Value::as_str);
        if self.pending_input.is_some() {
            return Err(CliError::usage("上次发送结果待核实；按 F5 查询原操作"));
        }
        if required_collaboration_mode.is_some() && active_turn.is_some() {
            return Err(CliError::usage(
                "当前 Thread 已有活动 Turn；Plan 创建不会把目标追加到该 Turn",
            ));
        }
        let operation_key = format!("op_{}", uuid::Uuid::new_v4().simple());
        let (method, params) = if let Some(turn_id) = active_turn {
            let kind = match mode {
                UiSubmitMode::Queue => "follow_up",
                _ => "steering",
            };
            (
                "turn/input/enqueue",
                json!({"turnId":turn_id,"content":content,"kind":kind}),
            )
        } else {
            (
                "turn/start",
                json!({"threadId":thread.thread_id,"content":content}),
            )
        };
        let result =
            match self
                .connection
                .request_operation_with_key(method, params, &operation_key)
            {
                Ok(result) => result,
                Err(error) if error.rpc_code.is_some() => return Err(error),
                Err(error) => {
                    self.pending_input = Some(PendingInput {
                        operation_key,
                        method,
                        text: text.clone(),
                    });
                    return Err(error);
                }
            };
        match result.get("accepted").and_then(Value::as_bool) {
            Some(true) => self.accept_input(),
            Some(false) => return Err(CliError::usage("输入未被服务端接纳")),
            None => {
                self.pending_input = Some(PendingInput {
                    operation_key,
                    method,
                    text,
                });
                return Err(CliError::protocol("发送回执缺少接纳状态；按 F5 核实原操作"));
            }
        }
        if let Err(error) = self.refresh() {
            self.refresh_pending = error.read_retryable;
            self.notice(format!(
                "输入已发送，状态刷新失败；按 F5 重试：{}",
                error.message()
            ));
        }
        Ok(())
    }

    /// 接纳回执立即更新输入区；附件与发送锁只结算一次，后续刷新不延迟用户继续编辑。
    fn accept_input(&mut self) {
        self.send(UiEvent::ClearDraft);
        self.attachments.clear();
        self.send_attachments();
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

    /// @author kongweiguang
    /// Slash command 复用 JA-RPC owner；Fork 首次读取失败须结束 UI loading 并显示可恢复错误。
    fn command(&mut self, command: UiCommand) -> Result<(), CliError> {
        match command {
            UiCommand::NewThread => {
                if self.pending_input.is_some() {
                    return Err(CliError::usage("发送结果待核实；按 F5 核实后再新建会话"));
                }
                let root = canonical_workspace(Some(self.workspace_root.clone()))?;
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
            UiCommand::CreatePlan => {
                return Err(CliError::usage("请在 /plan create 后填写目标"));
            }
            UiCommand::ShowPlan => self.show_plan()?,
            UiCommand::TogglePlanMode => self.toggle_plan_mode()?,
            UiCommand::ExecutePlan => self.change_plan_run(PlanRunAction::Execute)?,
            UiCommand::PausePlan => self.change_plan_run(PlanRunAction::Pause)?,
            UiCommand::ResumePlan => self.change_plan_run(PlanRunAction::Resume)?,
            UiCommand::StopPlan => self.change_plan_run(PlanRunAction::Stop)?,
            UiCommand::CompactThread => self.compact_thread()?,
            UiCommand::ShowStatus => self.show_status()?,
            UiCommand::ShowSkills => self.show_skills()?,
            UiCommand::ShowMcp => self.show_mcp()?,
            UiCommand::ReviewThread => self.start_review()?,
            UiCommand::ForkThread => {
                if let Err(error) = self.show_fork_choices() {
                    self.send(UiEvent::ForkReplyLoadFailed);
                    self.send(UiEvent::RestoreForkDraft);
                    return Err(error);
                }
            }
            UiCommand::CopyLastReply => self.copy_last_reply()?,
            UiCommand::ExportThread => self.export_thread(None)?,
        }
        Ok(())
    }

    /// 面板连接不接管当前 Thread；Composer 与后台观察继续由原 UI 和连接持有。
    fn open_model_settings(&mut self) -> Result<(), CliError> {
        self.model_settings = Some(ModelSettingsRunner::spawn()?);
        let operation_id = uuid::Uuid::new_v4().simple().to_string();
        self.send(UiEvent::OpenModelSettings {
            operation_id: operation_id.clone(),
            standalone: false,
        });
        self.request_model_settings(ModelSettingsRequest::Read { operation_id });
        Ok(())
    }

    /// 提交失败也回传相同操作身份，让面板解除加载并保留表单，而不写进对话历史。
    fn request_model_settings(&mut self, request: ModelSettingsRequest) {
        let operation_id = model_settings::operation_id(&request).to_owned();
        let result = match &mut self.model_settings {
            Some(runner) => runner.submit(request),
            None => Err(CliError::usage("模型设置已关闭，请重新打开。")),
        };
        if let Err(error) = result {
            self.send(UiEvent::ModelSettingsResult(ModelSettingsResult {
                operation_id,
                outcome: ModelSettingsOutcome::Failed {
                    message: error.message,
                },
            }));
        }
    }

    /// 每轮只收已有结果；会话保存成功复用关闭与刷新路径返回选择，当前模型只能由用户显式切换。
    fn poll_model_settings(&mut self) {
        let mut results = Vec::new();
        if let Some(runner) = &mut self.model_settings {
            while let Some(result) = runner.poll() {
                results.push(result);
            }
        }
        for result in results {
            let return_to_models = self.thread.is_some()
                && matches!(&result.outcome, ModelSettingsOutcome::Saved { .. });
            self.send(UiEvent::ModelSettingsResult(result));
            if return_to_models
                && let Err(error) = self.handle_action(UiAction::CloseModelSettings {
                    start_conversation: false,
                })
            {
                self.notice(error.message);
            }
        }
    }

    /// 开始对话前由 Java 核对信任；TUI 内用确认面板，避免阻塞 stdin 或静默授予权限。
    fn start_after_model_settings(&mut self, trust_confirmed: bool) -> Result<(), CliError> {
        if self.pending_input.is_some() {
            return Err(CliError::usage("会话操作结果待核实；请先按 F5 核实。"));
        }
        let root = canonical_workspace(Some(self.workspace_root.clone()))?;
        let workspace = open_workspace(&mut self.connection, &root)?;
        if workspace.get("trust").and_then(Value::as_str) != Some("trusted") {
            if !trust_confirmed {
                self.send(UiEvent::ModelSettingsTrustRequired {
                    cwd: root.to_string_lossy().into_owned(),
                });
                return Ok(());
            }
            let result = self.connection.request(
                "workspace/set-trust",
                json!({
                    "workspaceId": required_str(&workspace, "workspaceId")?, "trust":"trusted",
                }),
            )?;
            if result.get("accepted").and_then(Value::as_bool) != Some(true) {
                return Err(CliError::protocol("项目信任设置未获 Java 确认"));
            }
        }
        let thread = create_thread(&mut self.connection, &root, false)?;
        self.activate_thread(thread)?;
        self.model_settings = None;
        Ok(())
    }

    /// 无模型启动也能进入管理入口；候选仍按 Java 的项目有效配置计算，保持全局/项目边界。
    fn show_model_choices(&mut self) -> Result<(), CliError> {
        let workspace_id = self
            .thread
            .as_ref()
            .map(|thread| thread.workspace_id.clone())
            .unwrap_or_else(|| self.thread_list_workspace_id.clone());
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
        if let Some(thread) = &self.thread {
            let selected_exists = self
                .config
                .get("effective")
                .and_then(|effective| super::configured_model(effective, &thread.model_id).ok())
                .is_some_and(|(provider_id, _)| provider_id == thread.provider_id);
            if !selected_exists {
                self.notice(
                    "当前会话模型已不可用；请在 /model 重新选择。在途工作仍使用原冻结配置。".into(),
                );
            }
        }
        Ok(())
    }

    /// @author kongweiguang
    /// 先读取并校验新 Thread 首屏，再切换订阅和活动 identity；读取失败时来源仍是唯一写入目标。
    fn activate_thread(&mut self, thread: ThreadContext) -> Result<(), CliError> {
        if self.pending_input.is_some() {
            return Err(CliError::usage("发送结果待核实；按 F5 核实后再切换会话"));
        }
        let thread_id = thread.thread_id.clone();
        let previous_thread_id = self.thread.as_ref().map(|old| old.thread_id.clone());
        let needs_new_observation = previous_thread_id.as_deref() != Some(thread_id.as_str());
        if needs_new_observation {
            self.connection.observe(&thread_id)?;
        }
        let prepared = match prepare_thread_activation(thread, |candidate| {
            read_baseline(
                &mut self.connection,
                &candidate.thread_id,
                &candidate.workspace_id,
                &Value::Null,
                None,
            )
        }) {
            Ok(prepared) => prepared,
            Err(error) => {
                if needs_new_observation {
                    let _ = self.connection.unobserve(&thread_id);
                }
                return Err(error);
            }
        };
        if let Some(old_thread_id) = previous_thread_id
            .as_deref()
            .filter(|old_thread_id| *old_thread_id != thread_id)
            && let Err(error) = self.connection.unobserve(old_thread_id)
        {
            if needs_new_observation {
                let _ = self.connection.unobserve(&thread_id);
            }
            return Err(error);
        }

        // 读路径与投影已全部成功；此处之后不再有会把焦点留在半激活 Thread 的 fallible RPC。
        let PreparedThreadActivation {
            thread,
            history,
            interaction,
            config,
            context_usage,
            mut snapshot,
            fork_reply_choices,
        } = prepared;
        self.diff_detail = None;
        if let Some(event) = clear_event_before_thread_switch(
            self.thread
                .as_ref()
                .map(|current| current.thread_id.as_str()),
            &thread.thread_id,
        ) {
            self.send(event);
        }
        self.thread_list_workspace_id = thread.workspace_id.clone();
        self.thread_list_scope_all = false;
        self.workspace_root = PathBuf::from(&thread.workspace_root);
        self.thread = Some(thread);
        self.restore_pending_fork_for_thread(&thread_id);
        self.usage.bind_thread(
            self.thread.as_ref().map(|thread| thread.thread_id.as_str()),
            Instant::now(),
        );
        let usage_generation = self.usage.connection_generation();
        if self.thread.is_some() {
            let _ = self
                .usage
                .set_context(&thread_id, usage_generation, context_usage);
        }
        self.publish_usage();
        self.attachments.clear();
        self.file_choices.clear();
        self.skill_choices.clear();
        self.skill_catalog = None;
        self.pending_model_id = None;
        self.older_items.clear();
        self.older_turns.clear();
        self.older_cursor = history
            .get("nextCursor")
            .and_then(Value::as_str)
            .map(str::to_owned);
        self.fork_reply_base_choices = fork_reply_choices;
        self.fork_reply_older_choices.clear();
        self.fork_reply_cursor = fork_reply_page_cursor(&history);
        self.fork_retry_item_id = None;
        self.history = history;
        self.interaction = interaction;
        self.config = config;
        self.reset_stream_cursor();
        snapshot.usage = self.usage.snapshot();
        snapshot.has_older_history = self.older_cursor.is_some();
        self.stabilize_interaction_key(&mut snapshot);
        self.send(UiEvent::ReplaceSnapshot(Box::new(snapshot)));
        self.send(UiEvent::SetForkReplyPage {
            choices: self.fork_reply_base_choices.clone(),
            next_cursor: self.fork_reply_cursor.clone(),
            selected_id: None,
            prepend: false,
        });
        self.send(UiEvent::ReleasePromptSubmission);
        self.next_refresh = Instant::now() + REFRESH_INTERVAL;
        if self.refresh_pending {
            let message = if self.pending_suspended_control.is_some() {
                "后台状态已恢复；恢复或取消仍待核实，后台会继续只读观察，按 F5 可立即核实。"
            } else {
                "后台状态已恢复，可以继续操作。"
            };
            self.notice(message.into());
        }
        self.refresh_pending = false;
        self.reconcile_pending_suspended_control(false);
        self.refresh_usage_summary_now();
        self.sync_review_operation();
        if let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.clone())
            && let Some(notice) = self.review_notices.remove(&thread_id)
        {
            self.notice(notice);
        }
        Ok(())
    }

    /// @author kongweiguang
    /// 切换时只激活来源或已知 child 的一份 journal；其他操作文件留存，不会覆盖或被误清理。
    fn restore_pending_fork_for_thread(&mut self, thread_id: &str) {
        if self
            .pending_fork
            .as_ref()
            .is_some_and(|pending| pending.matches_thread_id(thread_id))
        {
            return;
        }
        self.pending_fork = None;
        let mut scan = PendingFork::read_journals(&self.fork_journal_directory);
        self.pending_fork = PendingFork::take_for_thread(&mut scan.pending, thread_id);
        if !scan.errors.is_empty() {
            self.notice(format!(
                "部分待核实分叉记录无法读取，其他记录已保留：{}",
                scan.errors.join("；")
            ));
        }
        if self.pending_fork.is_some() {
            self.notice("已恢复当前会话的待核实分叉；按 F5 查询原回执。".into());
        }
    }

    /// 选择器 identity 只能来自服务端发现列表，最终仍读取完整 Thread 元数据核验。
    fn switch_thread(&mut self, thread_id: &str) -> Result<(), CliError> {
        let thread = load_context(&mut self.connection, thread_id)?;
        self.activate_thread(thread)?;
        if let Some(prompt) = self.resume_prompt.take() {
            self.send_resume_prompt(prompt);
        }
        Ok(())
    }

    /// 续接 prompt 会创建新的用户 Turn；已有工作通过 turn/resume 恢复，避免重放旧工具调用。
    fn send_resume_prompt(&mut self, prompt: String) {
        self.send(UiEvent::BeginSubmission(prompt.clone()));
        if let Err(error) = self.submit(prompt.clone(), Vec::new(), UiSubmitMode::Immediate, None) {
            if self.pending_input.is_none() {
                self.send(UiEvent::RestoreDraft(prompt));
            }
            self.notice(submission_notice(&error));
        }
    }

    /// 打开选择器先读最近一页，后续查询和翻页都复用同一 Java keyset 接口。
    fn show_thread_choices(&mut self) -> Result<(), CliError> {
        self.search_threads("", None)
    }

    /// 服务端按当前 Workspace 和标题过滤且返回游标，CLI 不扫描其它项目或物化完整历史。
    fn search_threads(&mut self, query: &str, cursor: Option<&str>) -> Result<(), CliError> {
        let (choices, next_cursor) = if self.thread_list_scope_all {
            list_all_thread_choices(&mut self.connection, query, cursor)?
        } else {
            list_thread_choices(
                &mut self.connection,
                &self.thread_list_workspace_id,
                query,
                cursor,
            )?
        };
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

    /// 未核实上次写入前不接受新候选；支持推理等级的模型先建立候选，避免偷偷套用默认等级。
    fn select_model(&mut self, id: &str) -> Result<(), CliError> {
        self.ensure_preferences_verified()?;
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
        if !self.preferences_sync_pending {
            self.pending_model_id = None;
        }
        Ok(())
    }

    /// 模型候选写入后按 App Server metadata 核实，ACK 丢失时不以新 revision 重放旧意图。
    fn commit_model(
        &mut self,
        provider_id: &str,
        model_id: &str,
        reasoning: Value,
    ) -> Result<(), CliError> {
        self.ensure_preferences_verified()?;
        let prefs = self.current_preferences()?;
        let (thread_id, workspace_id, revision) = self
            .thread
            .as_ref()
            .map(|thread| {
                (
                    thread.thread_id.clone(),
                    thread.workspace_id.clone(),
                    thread.revision,
                )
            })
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let access_mode = required_str(&prefs, "accessMode")?.to_owned();
        let collaboration_mode = prefs
            .get("collaborationMode")
            .and_then(Value::as_str)
            .unwrap_or("default")
            .to_owned();
        let requested = json!({
            "threadId":thread_id,
            "providerId":provider_id,
            "modelId":model_id,
            "reasoningLevel":reasoning,
            "accessMode":access_mode,
            "collaborationMode":collaboration_mode,
            "expectedThreadRevision":revision
        });
        self.save_preferences(requested, &workspace_id, &thread_id)
    }

    /// 权限候选沿用最新读回的模型与协作设置，以 Java revision CAS 防止覆盖桌面上的并发修改。
    fn select_permission(&mut self, id: &str) -> Result<(), CliError> {
        if !matches!(id, "approval_required" | "full_access") {
            return Err(CliError::usage("权限选择无效"));
        }
        self.ensure_preferences_verified()?;
        let prefs = self.current_preferences()?;
        let (thread_id, workspace_id, revision) = self
            .thread
            .as_ref()
            .map(|thread| {
                (
                    thread.thread_id.clone(),
                    thread.workspace_id.clone(),
                    thread.revision,
                )
            })
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let provider_id = required_str(&prefs, "providerId")?.to_owned();
        let model_id = required_str(&prefs, "modelId")?.to_owned();
        let reasoning = prefs.get("reasoningLevel").cloned().unwrap_or(Value::Null);
        let collaboration_mode = prefs
            .get("collaborationMode")
            .and_then(Value::as_str)
            .unwrap_or("default")
            .to_owned();
        let requested = json!({
            "threadId":thread_id,
            "providerId":provider_id,
            "modelId":model_id,
            "reasoningLevel":reasoning,
            "accessMode":id,
            "collaborationMode":collaboration_mode,
            "expectedThreadRevision":revision
        });
        self.save_preferences(requested, &workspace_id, &thread_id)
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
        self.apply_thread_metadata(&metadata)?;
        metadata
            .get("preferences")
            .filter(|value| !value.is_null())
            .cloned()
            .ok_or_else(|| CliError::protocol("会话缺少偏好"))
    }

    /// 未核实的写入期间保留当前候选，只有 F5 成功读回权威偏好后才接受下一次保存。
    fn ensure_preferences_verified(&self) -> Result<(), CliError> {
        if self.preferences_sync_pending {
            return Err(CliError::usage(
                "上次偏好保存结果尚未核实；按 F5 同步后台状态后再试",
            ));
        }
        Ok(())
    }

    /// 模型与权限共用一次写入、一次完整读回，避免 ACK 不明时重放偏好副作用。
    fn save_preferences(
        &mut self,
        requested: Value,
        workspace_id: &str,
        thread_id: &str,
    ) -> Result<(), CliError> {
        self.ensure_preferences_verified()?;
        let workspace_id = workspace_id.to_owned();
        let thread_id = thread_id.to_owned();
        self.preferences_sync_pending = true;
        let attempt = update_then_readback_once(
            &mut self.connection,
            |connection| {
                connection
                    .request("thread/preferences/update", requested.clone())
                    .map(|_| ())
            },
            |connection| list_thread_in_workspace(connection, &workspace_id, &thread_id),
        );
        let readback_matches = attempt
            .readback
            .as_ref()
            .ok()
            .and_then(|metadata| matches_preferences(metadata, &requested));
        let resolution = resolve(&attempt.update, readback_matches);
        if let Ok(metadata) = &attempt.readback
            && let Err(error) = self.apply_thread_metadata(metadata)
        {
            self.preferences_sync_pending = true;
            self.notice(format!(
                "设置结果暂未核实；按 F5 读取后台状态，Esc 返回。({})",
                error.message()
            ));
            return Ok(());
        }
        match resolution {
            PreferenceResolution::Applied => {
                self.preferences_sync_pending = false;
                if let Err(error) = self.refresh_history() {
                    self.next_refresh = Instant::now() + REFRESH_INTERVAL;
                    self.notice(format!(
                        "设置已保存并由后台核实，界面状态未刷新；按 F5 更新。({})",
                        error.message()
                    ));
                }
                Ok(())
            }
            PreferenceResolution::NotApplied | PreferenceResolution::Superseded => {
                let failure = attempt
                    .update
                    .as_ref()
                    .err()
                    .map(CliError::message)
                    .unwrap_or("");
                let refresh_error = if attempt.readback.is_ok() {
                    self.refresh_history().err()
                } else {
                    self.refresh().err()
                };
                let outcome = if resolution == PreferenceResolution::NotApplied {
                    format!("设置未生效；后台当前值已读回。{failure}")
                } else {
                    "设置后偏好被其他客户端再次修改；已读回当前值".into()
                };
                let next_step = refresh_error
                    .as_ref()
                    .map(|error| format!("界面刷新失败，按 F5 更新后可重试。({})", error.message()))
                    .unwrap_or_else(|| "可重新选择后重试".into());
                Err(CliError::usage(format!("{outcome}；{next_step}")))
            }
            PreferenceResolution::Unknown => {
                let message = if attempt.update.is_ok() {
                    "设置请求已受理，但当前偏好尚未读回；按 F5 核实后再操作。"
                } else {
                    "设置结果暂未核实；按 F5 核实后再重试。"
                };
                self.notice(message.into());
                Ok(())
            }
        }
    }

    /// 文件候选沿用 App Server 的受预算搜索；紧凑选择器不再额外占用状态行显示截断提示。
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

    /// UI 回传的审批 identity 必须匹配当前快照；ACK 后若刷新失败，保留锁并交给 F5 核实。
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
            self.next_refresh = Instant::now() + REFRESH_INTERVAL;
            self.notice(format!(
                "审批已受理，当前状态未刷新；按 F5 核实，Esc 返回。({})",
                error.message()
            ));
        }
        Ok(())
    }

    /// 多题答案整体提交后先读权威状态；读回失败时提示核实入口并保留 UI 去重锁。
    fn respond_interaction(
        &mut self,
        thread_id: &str,
        interaction_id: &str,
        interaction_revision: u64,
        operation_key: &str,
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
                "threadId":thread_id,"interactionId":interaction_id,
                "expectedInteractionRevision":interaction_revision,
                "operationKey":operation_key,"answers":answers
            }),
        )?;
        if let Err(error) = self.refresh() {
            self.next_refresh = Instant::now() + REFRESH_INTERVAL;
            self.notice(format!(
                "回答已受理，当前状态未刷新；按 F5 核实，Esc 返回。({})",
                error.message()
            ));
        }
        Ok(())
    }

    /// @author kongweiguang
    /// 工具列表激活时已同步打开安全预览；异步读取只更新仍打开的同一详情，避免 Esc 后迟到结果重开面板。
    fn open_details(&mut self, entry_id: &str) -> Result<(), CliError> {
        let (thread_id, title, item, mut body) = {
            let thread = self
                .thread
                .as_ref()
                .ok_or_else(|| CliError::usage("请先选择会话"))?;
            let history = self.merged_history()?;
            let item = history
                .get("items")
                .and_then(Value::as_array)
                .and_then(|items| {
                    items
                        .iter()
                        .find(|item| item.get("itemId").and_then(Value::as_str) == Some(entry_id))
                })
                .cloned()
                .ok_or_else(|| CliError::usage("详情已过期"))?;
            let snapshot = projection::project(
                thread,
                &history,
                &self.interaction,
                &self.config,
                Some(&thread.title),
            )?;
            let entry = snapshot
                .timeline
                .iter()
                .find(|entry| entry.id == entry_id)
                .ok_or_else(|| CliError::usage("详情已过期"))?;
            let title = match &entry.kind {
                TimelineKind::Tool { action, target, .. } => format!("{action}  {target}"),
                _ => entry
                    .text
                    .lines()
                    .next()
                    .filter(|line| !line.is_empty())
                    .unwrap_or("消息")
                    .to_owned(),
            };
            (
                thread.thread_id.clone(),
                title,
                item,
                entry.detail.clone().unwrap_or_else(|| entry.text.clone()),
            )
        };
        let kind = required_str(&item, "kind")?;
        if kind == "tool_call" {
            let presentation = item
                .get("presentation")
                .ok_or_else(|| CliError::protocol("工具历史缺少 presentation"))?;
            let truncated = presentation.get("truncated").and_then(Value::as_bool) == Some(true);
            let artifact_id = presentation
                .get("artifactId")
                .and_then(Value::as_str)
                .filter(|value| !value.is_empty())
                .map(str::to_owned);
            body = tool_detail_body(&body, truncated, artifact_id.is_some(), None);
            self.send(UiEvent::UpdateDetailsIfOpen {
                entry_id: entry_id.into(),
                title: title.clone(),
                body,
            });

            if let Some(artifact_id) = artifact_id {
                let read_result = (|| {
                    let turn_id = required_str(&item, "turnId")?;
                    let call_id = required_str(&item, "callId")?;
                    read_tool_artifact(
                        &mut self.connection,
                        &thread_id,
                        turn_id,
                        call_id,
                        &artifact_id,
                    )
                })();
                match read_result {
                    Ok(artifact) => self.send(UiEvent::UpdateDetailsIfOpen {
                        entry_id: entry_id.into(),
                        title,
                        body: tool_detail_body("", truncated, true, Some(&artifact)),
                    }),
                    Err(error) => self.notice(format!(
                        "完整工具产物读取失败，仍显示安全预览；按 F5 重读。({})",
                        error.message()
                    )),
                }
            }
            return Ok(());
        }
        if item.get("truncated").and_then(Value::as_bool) == Some(true)
            && matches!(
                kind,
                "user_input"
                    | "assistant_progress"
                    | "final_answer"
                    | "reasoning_summary"
                    | "thread_message"
            )
        {
            let total_characters = required_u64(&item, "totalCharacters")?;
            let full_text = read_public_item_content(
                &mut self.connection,
                &thread_id,
                entry_id,
                total_characters,
                Some(ITEM_DETAIL_MAX_BYTES),
            )?;
            body = if kind == "user_input" {
                projection::user_input_display(&item, Some(&full_text))?
            } else {
                full_text
            };
        }
        self.send(UiEvent::ShowDetails {
            entry_id: entry_id.into(),
            title,
            body,
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
                TimelineKind::Tool { action, target, .. } => Some(format!(
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

    /// 冻结最近 native Turn 后立即打开详情；网络读取不占用动作循环，草稿和返回操作保持可用。
    fn show_diff(&mut self) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let history = json!({"turns": self.latest_native_turn().into_iter().collect::<Vec<_>>()});
        let entry_id = format!("diff:{}", uuid::Uuid::new_v4().simple());
        self.diff_detail = Some(DiffDetail {
            entry_id: entry_id.clone(),
            thread_id: thread.thread_id.clone(),
            history,
            cancelled: Arc::new(AtomicBool::new(false)),
            pending: true,
        });
        self.send(UiEvent::ShowDetails {
            entry_id,
            title: "本次变更".into(),
            body: "正在读取本次变更…".into(),
        });
        self.poll_diff_read();
        Ok(())
    }

    /// F5 保持原 Turn 身份，并允许尚未捕获的同轮补齐；在途读取不重复创建线程或请求。
    fn retry_diff(&mut self, entry_id: &str) {
        if self
            .diff_worker
            .as_ref()
            .is_some_and(|worker| !worker.is_finished())
        {
            return;
        }
        if let Some(detail) = self
            .diff_detail
            .as_mut()
            .filter(|detail| detail.entry_id == entry_id)
        {
            refresh_uncaptured_diff_turn(&mut detail.history, &self.history);
            detail.pending = true;
            self.send(UiEvent::UpdateDetailsIfOpen {
                entry_id: entry_id.into(),
                title: "本次变更".into(),
                body: "正在读取本次变更…".into(),
            });
            self.poll_diff_read();
        }
    }

    /// 至多保留一个后台读取；旧面板取消后，新面板等有界在途请求结束再读取，避免连续开关积累线程。
    fn poll_diff_read(&mut self) {
        if self
            .diff_worker
            .as_ref()
            .is_some_and(|worker| !worker.is_finished())
        {
            return;
        }
        if let Some(worker) = self.diff_worker.take()
            && worker.join().is_err()
            && let Some(detail) = self.diff_detail.as_ref().filter(|detail| !detail.pending)
        {
            self.send(UiEvent::UpdateDetailsIfOpen {
                entry_id: detail.entry_id.clone(),
                title: "本次变更".into(),
                body: "本次变更读取意外结束\n\n按 F5 重试，Esc 返回。".into(),
            });
        }
        let Some(detail) = self.diff_detail.as_mut().filter(|detail| detail.pending) else {
            return;
        };
        detail.pending = false;
        let entry_id = detail.entry_id.clone();
        let thread_id = detail.thread_id.clone();
        let history = detail.history.clone();
        let cancelled = Arc::clone(&detail.cancelled);
        let connect = self.connection.read_connection_factory(DIFF_READ_DEADLINE);
        let sender = self.bridge.event_sender();
        let failure_id = entry_id.clone();
        match std::thread::Builder::new()
            .name("ja-diff-read".into())
            .spawn(move || {
                let deadline = Instant::now() + DIFF_READ_DEADLINE;
                let mut connect = Some(connect);
                let mut connection = None;
                let result = read_turn_diff(&thread_id, &history, |params| {
                    if cancelled.load(Ordering::Acquire) {
                        return Err(CliError::transport("差异读取已取消"));
                    }
                    if connection.is_none() {
                        connection = Some(connect.take().expect("connect once")()?);
                    }
                    let remaining = deadline.saturating_duration_since(Instant::now());
                    if remaining.is_zero() || cancelled.load(Ordering::Acquire) {
                        return Err(CliError::transport("差异读取超时，请按 F5 重试"));
                    }
                    connection
                        .as_mut()
                        .expect("connected")
                        .request_with_timeout("turn/change-set/read", params, remaining)
                });
                if let Some(mut connection) = connection {
                    let _ = connection.disconnect();
                }
                if !cancelled.load(Ordering::Acquire) {
                    let body = result.unwrap_or_else(|error| {
                        format!(
                            "本次变更读取失败\n{}\n\n按 F5 重试，Esc 返回。",
                            terminal_safe(error.message())
                        )
                    });
                    let _ = sender.send(UiEvent::UpdateDetailsIfOpen {
                        entry_id,
                        title: "本次变更".into(),
                        body,
                    });
                }
            }) {
            Ok(worker) => self.diff_worker = Some(worker),
            Err(error) => self.send(UiEvent::UpdateDetailsIfOpen {
                entry_id: failure_id,
                title: "本次变更".into(),
                body: format!(
                    "无法开始读取本次变更\n{}\n\n按 F5 重试，Esc 返回。",
                    terminal_safe(&error.to_string())
                ),
            }),
        }
    }

    /// Plan 以当前 Thread 的服务端投影为唯一事实来源，不从客户端状态推断其生命周期。
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

    /// Plan 创建先恢复权威 PLAN 偏好和匹配 DRAFT，再以独立 turn/start 启动模型规划。
    fn create_plan(&mut self, objective: &str) -> Result<(), CliError> {
        let objective = objective.trim();
        if objective.is_empty() {
            return Err(CliError::usage("用法：/plan create <目标>"));
        }
        self.ensure_preferences_verified()?;
        if self.pending_input.is_some() {
            return Err(CliError::usage("上次发送结果待核实；按 F5 查询原操作"));
        }
        let (thread_id, workspace_id) = self
            .thread
            .as_ref()
            .map(|thread| (thread.thread_id.clone(), thread.workspace_id.clone()))
            .ok_or_else(|| CliError::usage("请先选择会话"))?;

        self.refresh_history()?;
        self.ensure_plan_creation_idle(&thread_id)?;
        let preferences = self.current_preferences()?;
        if required_str(&preferences, "collaborationMode")? != "plan" {
            let revision = self
                .thread
                .as_ref()
                .ok_or_else(|| CliError::usage("请先选择会话"))?
                .revision;
            let requested = json!({
                "threadId":thread_id,
                "providerId":required_str(&preferences,"providerId")?,
                "modelId":required_str(&preferences,"modelId")?,
                "reasoningLevel":preferences.get("reasoningLevel").cloned().unwrap_or(Value::Null),
                "accessMode":required_str(&preferences,"accessMode")?,
                "collaborationMode":"plan",
                "expectedThreadRevision":revision
            });
            self.save_preferences(requested, &workspace_id, &thread_id)?;
            if self.preferences_sync_pending {
                return Err(CliError::usage(
                    "规划模式保存结果尚未核实；按 F5 同步后台状态后再试",
                ));
            }
        }

        let verified_preferences = self.current_preferences()?;
        if required_str(&verified_preferences, "collaborationMode")? != "plan" {
            return Err(CliError::usage("规划模式未生效；未创建 Plan"));
        }
        self.refresh_history()?;
        self.ensure_plan_creation_idle(&thread_id)?;

        let current = self.read_current_plan_if_any(&thread_id)?;
        let reuse_current = current
            .as_ref()
            .map(|snapshot| can_resume_plan_creation(snapshot, &thread_id, objective))
            .transpose()
            .map_err(CliError::usage)?
            .unwrap_or(false);
        let current = if reuse_current {
            current.expect("reusable Plan snapshot was checked above")
        } else {
            let expected_thread_revision = self
                .thread
                .as_ref()
                .ok_or_else(|| CliError::usage("请先选择会话"))?
                .revision;
            let operation_key = format!("op_{}", uuid::Uuid::new_v4().simple());
            let created = self.connection.request(
                "plan/create",
                json!({
                    "owner":{"kind":"thread","threadId":thread_id},
                    "objective":objective,
                    "expectedThreadRevision":expected_thread_revision,
                    "operationKey":operation_key
                }),
            );
            match created {
                Ok(_) => self
                    .read_current_plan_if_any(&thread_id)?
                    .ok_or_else(|| CliError::protocol("Plan 创建已返回，但当前 Plan 未读回"))?,
                Err(write_error) => {
                    let recovered = self.read_current_plan_if_any(&thread_id);
                    match recovered {
                        Ok(Some(snapshot))
                            if can_resume_plan_creation(&snapshot, &thread_id, objective)
                                .is_ok_and(|matches| matches) =>
                        {
                            snapshot
                        }
                        _ => return Err(write_error),
                    }
                }
            }
        };
        if !can_resume_plan_creation(&current, &thread_id, objective).map_err(CliError::usage)? {
            return Err(CliError::protocol("Plan 创建后未读回对应的 DRAFT"));
        }

        self.refresh_history()?;
        self.ensure_plan_creation_idle(&thread_id)?;
        let prompt =
            format!("请为以下目标制定结构化计划，并调用 `plan_propose` 提交草案：\n\n{objective}");
        self.submit(prompt, Vec::new(), UiSubmitMode::Immediate, Some("plan"))
    }

    /// Plan 规划不能加入已有 Turn，也不能与当前 Thread 的 Review 读取并行。
    fn ensure_plan_creation_idle(&self, thread_id: &str) -> Result<(), CliError> {
        if self.pending_input.is_some() {
            return Err(CliError::usage("上次发送结果待核实；按 F5 查询原操作"));
        }
        if self
            .review_collection
            .as_ref()
            .is_some_and(|collection| collection.thread_id == thread_id)
            || self.has_active_review(thread_id)
        {
            return Err(CliError::usage(
                "当前会话代码审查仍在运行；完成或取消后再规划",
            ));
        }
        let active_turn = self.latest_native_turn().is_some_and(|turn| {
            matches!(
                turn.get("status").and_then(Value::as_str),
                Some("queued" | "running" | "waiting_input" | "waiting_approval" | "suspended")
            )
        });
        if active_turn {
            return Err(CliError::usage(
                "当前 Thread 已有活动 Turn；Plan 创建不会把目标追加到该 Turn",
            ));
        }
        Ok(())
    }

    /// 规划模式只修改持久 Thread 偏好；Plan 执行仍须单独显式操作。
    fn toggle_plan_mode(&mut self) -> Result<(), CliError> {
        self.ensure_preferences_verified()?;
        let preferences = self.current_preferences()?;
        let (thread_id, workspace_id, revision) = self
            .thread
            .as_ref()
            .map(|thread| {
                (
                    thread.thread_id.clone(),
                    thread.workspace_id.clone(),
                    thread.revision,
                )
            })
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let current_mode = required_str(&preferences, "collaborationMode")?;
        let collaboration_mode = if current_mode == "plan" {
            "default"
        } else {
            "plan"
        };
        let requested = json!({
            "threadId":thread_id,
            "providerId":required_str(&preferences,"providerId")?,
            "modelId":required_str(&preferences,"modelId")?,
            "reasoningLevel":preferences.get("reasoningLevel").cloned().unwrap_or(Value::Null),
            "accessMode":required_str(&preferences,"accessMode")?,
            "collaborationMode":collaboration_mode,
            "expectedThreadRevision":revision
        });
        self.save_preferences(requested, &workspace_id, &thread_id)?;
        if !self.preferences_sync_pending {
            self.notice(if collaboration_mode == "plan" {
                "规划模式已开启；执行 Plan 仍需单独确认。".into()
            } else {
                "规划模式已关闭。".into()
            });
        }
        Ok(())
    }

    /// Plan Run 写入只发一次；可恢复故障后读取权威状态，不能因未知回执重放动作。
    fn change_plan_run(&mut self, action: PlanRunAction) -> Result<(), CliError> {
        let thread_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .thread_id
            .clone();
        let current = self.read_current_plan(&thread_id)?;
        let plan = current
            .get("plan")
            .ok_or_else(|| CliError::protocol("Plan 投影缺少 plan"))?;
        let plan_id = required_str(plan, "planId")?.to_owned();
        let expected_plan_revision = required_u64(plan, "revision")?;
        let status = required_str(plan, "status")?;
        let previous_run_id = plan
            .get("activeRunId")
            .and_then(Value::as_str)
            .map(str::to_owned);
        let operation_key = format!("op_{}", uuid::Uuid::new_v4().simple());
        let (method, params) = match action {
            PlanRunAction::Execute => {
                if !matches!(status, "awaiting_approval" | "approved") {
                    return Err(CliError::usage("当前 Plan 没有可执行的已定稿版本"));
                }
                let revision = current
                    .get("currentRevision")
                    .filter(|value| !value.is_null())
                    .ok_or_else(|| CliError::usage("Plan 尚未定稿，无法执行"))?;
                (
                    "plan/execute",
                    json!({
                        "threadId":thread_id,
                        "planId":plan_id,
                        "expectedPlanRevision":expected_plan_revision,
                        "operationKey":operation_key,
                        "planRevisionId":required_str(revision,"planRevisionId")?,
                        "planHash":required_str(revision,"planHash")?
                    }),
                )
            }
            PlanRunAction::Pause | PlanRunAction::Resume | PlanRunAction::Stop => {
                let run_id = plan
                    .get("activeRunId")
                    .and_then(Value::as_str)
                    .ok_or_else(|| CliError::usage("当前 Plan 没有可控制的 Run"))?;
                let method = match action {
                    PlanRunAction::Pause => {
                        if !matches!(status, "executing" | "verifying") {
                            return Err(CliError::usage("当前 Plan Run 不能暂停"));
                        }
                        "plan/pause"
                    }
                    PlanRunAction::Resume => {
                        if status != "paused" {
                            return Err(CliError::usage("当前 Plan Run 未暂停"));
                        }
                        "plan/resume"
                    }
                    PlanRunAction::Stop => {
                        if matches!(status, "completed" | "stopped") {
                            return Err(CliError::usage("当前 Plan Run 已结束"));
                        }
                        "plan/stop"
                    }
                    PlanRunAction::Execute => unreachable!(),
                };
                (
                    method,
                    json!({
                        "threadId":thread_id,
                        "planId":plan_id,
                        "expectedPlanRevision":expected_plan_revision,
                        "operationKey":operation_key,
                        "runId":run_id
                    }),
                )
            }
        };
        match self.connection.request(method, params) {
            Ok(_) => {
                self.show_plan_after_mutation(&thread_id, method)?;
                self.notice(format!("Plan {method} 请求已提交；状态以当前投影为准。"));
                Ok(())
            }
            Err(error) if error.read_retryable => {
                self.reconcile_plan_mutation(
                    PlanMutationAttempt {
                        action,
                        method,
                        thread_id: &thread_id,
                        plan_id: &plan_id,
                        previous_revision: expected_plan_revision,
                        previous_run_id: previous_run_id.as_deref(),
                    },
                    &error,
                );
                Ok(())
            }
            Err(error) => Err(error),
        }
    }

    /// 写请求回执未知时最多执行权威读取；状态不匹配也不自动重发 Plan 动作。
    fn reconcile_plan_mutation(
        &mut self,
        attempt: PlanMutationAttempt<'_>,
        write_error: &CliError,
    ) {
        let PlanMutationAttempt {
            action,
            method,
            thread_id,
            plan_id,
            previous_revision,
            previous_run_id,
        } = attempt;
        let current = match self.read_current_plan(thread_id) {
            Ok(current) => Ok(current),
            Err(error) if error.read_retryable => {
                let _ = self.reconnect();
                self.read_current_plan(thread_id)
            }
            Err(error) => Err(error),
        };
        match current {
            Ok(current) => {
                let plan = current.get("plan");
                let body = format_plan(&current);
                if let Ok(body) = body {
                    self.send(UiEvent::ShowDetails {
                        entry_id: "plan".into(),
                        title: "Plan".into(),
                        body,
                    });
                }
                let applied = plan.is_some_and(|plan| {
                    confirms_plan_transition(
                        action,
                        plan_id,
                        previous_revision,
                        previous_run_id,
                        plan,
                    )
                });
                if applied {
                    self.notice(format!("Plan {method} 的提交结果已由后台状态核实。"));
                } else {
                    self.notice(format!(
                        "Plan {method} 回执未确认（{}）；已刷新当前状态，没有自动重试。运行 /plan status 再检查。",
                        write_error.message()
                    ));
                }
            }
            Err(read_error) => self.notice(format!(
                "Plan {method} 提交结果仍待核实（{}；状态读取失败：{}）。没有自动重试；恢复连接后运行 /plan status。",
                write_error.message(),
                read_error.message()
            )),
        }
    }

    /// 每次都通过 Java 读取当前 Plan；无 Plan 的场景由内部 Option 读取保留为正常状态。
    fn read_current_plan(&mut self, thread_id: &str) -> Result<Value, CliError> {
        self.read_current_plan_if_any(thread_id)?
            .ok_or_else(|| CliError::usage("当前会话没有 Plan"))
    }

    /// 只读取 Java 当前 Plan 投影；创建操作用 None 区分首次创建与已有 Plan。
    fn read_current_plan_if_any(&mut self, thread_id: &str) -> Result<Option<Value>, CliError> {
        let result = self
            .connection
            .request("plan/current/read", json!({"threadId":thread_id}))?;
        Ok(result
            .get("current")
            .filter(|value| !value.is_null())
            .cloned())
    }

    /// 写操作回执后读取权威 Plan 投影；读取失败保留为可见、可重试的提示。
    fn show_plan_after_mutation(&mut self, thread_id: &str, action: &str) -> Result<(), CliError> {
        match self.read_current_plan(thread_id) {
            Ok(current) => {
                let body = format_plan(&current)?;
                self.send(UiEvent::ShowDetails {
                    entry_id: "plan".into(),
                    title: "Plan".into(),
                    body,
                });
            }
            Err(error) => self.notice(format!(
                "Plan {action} 已提交，但状态未刷新；按 F5 重试读取。({})",
                error.message()
            )),
        }
        Ok(())
    }

    /// 手动压缩使用 Thread revision CAS；回执不明确时不重复提交请求。
    fn compact_thread(&mut self) -> Result<(), CliError> {
        self.ensure_preferences_verified()?;
        self.reload_metadata()?;
        let (thread_id, revision) = self
            .thread
            .as_ref()
            .map(|thread| (thread.thread_id.clone(), thread.revision))
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let result = match self.connection.request(
            "thread/compact",
            json!({"threadId":thread_id,"expectedThreadRevision":revision}),
        ) {
            Ok(result) => result,
            Err(error) if error.read_retryable => {
                let _ = self.refresh();
                self.notice("压缩结果待核实；未重复提交，请按 F5 查看当前状态。".into());
                return Ok(());
            }
            Err(error) => return Err(error),
        };
        let outcome = required_str(&result, "outcome")?;
        let before = required_u64(&result, "inputTokensBefore")?;
        let after = required_u64(&result, "inputTokensAfter")?;
        self.refresh()?;
        self.notice(if outcome == "compacted" {
            format!("上下文已压缩：估算输入 tokens {before} → {after}。")
        } else if outcome == "unchanged" {
            format!("当前上下文无需压缩：{before} tokens。")
        } else {
            return Err(CliError::protocol("上下文压缩返回未知结果"));
        });
        Ok(())
    }

    /// @author kongweiguang
    /// 先呈现缓存，再在 5 秒绝对预算内独立核对元数据、历史和用量；失败时仍重建详情并保留诊断。
    fn show_status(&mut self) -> Result<(), CliError> {
        if self.thread.is_none() {
            return Err(CliError::usage("请先选择会话"));
        }
        self.publish_status_details(None, None, None, true)?;
        let deadline = Instant::now() + STATUS_READ_DEADLINE;
        let mut metadata = None;
        let mut turn_status = None;
        let mut read_diagnostic: Option<String> = None;
        let error = usage::run_status_attempts(
            |step| match step {
                usage::StatusAttemptStep::Metadata => match self.read_status_metadata(deadline) {
                    Ok(value) => {
                        metadata = Some(value);
                        Ok(())
                    }
                    Err(error) => {
                        read_diagnostic = Some(format!("元数据：{}", error.message()));
                        Err(error)
                    }
                },
                usage::StatusAttemptStep::History => match self.read_status_history(deadline) {
                    Ok(value) => {
                        turn_status = value;
                        Ok(())
                    }
                    Err(error) => {
                        let history_error = format!("历史：{}", error.message());
                        read_diagnostic = Some(match read_diagnostic.take() {
                            Some(previous) => format!("{previous}\n{history_error}"),
                            None => history_error,
                        });
                        Err(error)
                    }
                },
                usage::StatusAttemptStep::Usage => {
                    self.refresh_usage_summary_until(deadline);
                    Ok(())
                }
                usage::StatusAttemptStep::Details => self.publish_status_details(
                    metadata.as_ref(),
                    turn_status.as_deref(),
                    read_diagnostic.as_deref(),
                    false,
                ),
            },
            |error| error.read_retryable,
        );
        error.map_or(Ok(()), Err)
    }

    /// @author kongweiguang
    /// 用有界线程目录读取完整偏好快照；metadata 失败时保留先前已确认的 Thread 字段。
    fn read_status_metadata(&mut self, deadline: Instant) -> Result<Value, CliError> {
        let (workspace_id, thread_id) = self
            .thread
            .as_ref()
            .map(|thread| (thread.workspace_id.clone(), thread.thread_id.clone()))
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let metadata = list_thread_in_workspace_until(
            &mut self.connection,
            &workspace_id,
            &thread_id,
            deadline,
            remaining_status,
        )?;
        self.apply_thread_metadata(&metadata)?;
        Ok(metadata)
    }

    /// @author kongweiguang
    /// 复用完整快照投影与待处理控制对账，并共享状态读取 deadline，避免只更新详情而遗失新快照。
    fn read_status_history(&mut self, deadline: Instant) -> Result<Option<String>, CliError> {
        self.refresh_history_with_deadline(Some(deadline))?;
        let status = self
            .latest_native_turn()
            .and_then(|turn| turn.get("status"))
            .and_then(Value::as_str)
            .map(thread_status_label)
            .unwrap_or("尚无任务");
        Ok(Some(status.to_owned()))
    }

    /// @author kongweiguang
    /// 用量刷新与其他状态读取共享绝对 deadline，过期时走现有 stale 路径而不延长退出等待。
    fn refresh_usage_summary_until(&mut self, deadline: Instant) {
        if let Some(identity) = self.usage.request_refresh_now() {
            let timeout = remaining_status(deadline)
                .unwrap_or(Duration::ZERO)
                .min(USAGE_REQUEST_TIMEOUT);
            self.read_usage_summary_with_timeout(identity, timeout);
        }
    }

    /// @author kongweiguang
    /// 身份只列一次以留出计量覆盖空间；缓存详情的偏好不可读时写未知，不伪装成默认模式。
    fn publish_status_details(
        &self,
        metadata: Option<&Value>,
        latest_turn_status: Option<&str>,
        read_diagnostic: Option<&str>,
        open_if_closed: bool,
    ) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let cached_turn_status = self
            .latest_native_turn()
            .and_then(|turn| turn.get("status"))
            .and_then(Value::as_str)
            .map(thread_status_label)
            .unwrap_or("尚无任务");
        let turn_status = latest_turn_status.unwrap_or(cached_turn_status);
        let collaboration = metadata
            .and_then(|metadata| metadata.get("preferences"))
            .and_then(|preferences| preferences.get("collaborationMode"))
            .and_then(Value::as_str)
            .map(|mode| if mode == "plan" { "规划" } else { "默认" })
            .unwrap_or("暂未读取");
        let usage_snapshot = self.usage.snapshot();
        let usage_details = status_lines(usage_snapshot.as_ref()).join("\n");
        let read_warning = usage::status_read_warning(read_diagnostic);
        let body = format!(
            "{}\n\n工作区：{}\n会话：{}\n状态：{}\n模式：{}\n权限：{}\nProvider：{}\n模型：{}\n修订：{}\n\n用量：\n{}{}",
            thread.title,
            thread.workspace_root,
            thread.thread_id,
            turn_status,
            collaboration,
            if thread.access_mode == "full_access" {
                "完全访问（使用宿主权限）"
            } else {
                "需要审批（Shell 仍使用宿主权限）"
            },
            thread.provider_id,
            thread.model_id,
            thread.revision,
            usage_details,
            read_warning
        );
        let event = if open_if_closed {
            UiEvent::ShowDetails {
                entry_id: "status".into(),
                title: "会话状态".into(),
                body,
            }
        } else {
            UiEvent::UpdateDetailsIfOpen {
                entry_id: "status".into(),
                title: "会话状态".into(),
                body,
            }
        };
        self.send(event);
        Ok(())
    }

    /// Skills 从 Java 当前生效目录分页读取；详情仅展示状态和范围，不展示文件根路径或正文。
    fn show_skills(&mut self) -> Result<(), CliError> {
        let workspace_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .workspace_id
            .clone();
        let (items, truncated) = self.read_catalog_pages("skill/list", &workspace_id)?;
        let body = format_skill_catalog(&items, truncated)?;
        self.send(UiEvent::ShowDetails {
            entry_id: "skills".into(),
            title: "Skills".into(),
            body,
        });
        Ok(())
    }

    /// MCP status reveals configured service names and health only; configuration values stay in Java.
    fn show_mcp(&mut self) -> Result<(), CliError> {
        let workspace_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .workspace_id
            .clone();
        let (items, truncated) = self.read_catalog_pages("mcp/list", &workspace_id)?;
        let body = format_mcp_catalog(&items, truncated)?;
        self.send(UiEvent::ShowDetails {
            entry_id: "mcp".into(),
            title: "MCP 连接".into(),
            body,
        });
        Ok(())
    }

    /// 目录扫描共用一个截止时间和分页上限，避免大型项目让终端无限等待。
    fn read_catalog_pages(
        &mut self,
        method: &str,
        workspace_id: &str,
    ) -> Result<(Vec<Value>, bool), CliError> {
        let deadline = Instant::now() + DISCOVERY_DEADLINE;
        let mut cursor = None;
        let mut seen = HashSet::new();
        let mut items = Vec::new();
        for _ in 0..5 {
            let mut params = json!({"workspaceId":workspace_id,"limit":200});
            if let Some(value) = &cursor {
                params["cursor"] = json!(value);
            }
            let page =
                self.connection
                    .request_with_timeout(method, params, remaining_scan(deadline)?)?;
            let page_items = page
                .get("items")
                .and_then(Value::as_array)
                .ok_or_else(|| CliError::protocol(format!("{method} 缺少 items")))?;
            for item in page_items {
                let identity = if method == "skill/list" {
                    required_str(item, "skillId")?
                } else {
                    required_str(item, "mcpId")?
                };
                if !seen.insert(identity.to_owned()) {
                    return Err(CliError::protocol(format!("{method} 返回重复 identity")));
                }
                items.push(item.clone());
            }
            cursor = page
                .get("nextCursor")
                .and_then(Value::as_str)
                .map(str::to_owned);
            if cursor.is_none() {
                return Ok((items, false));
            }
        }
        Ok((items, cursor.is_some()))
    }

    /// @author kongweiguang
    /// @author kongweiguang
    /// 选择器先用当前投影打开；冻结请求存在时 /fork 只重试原 tuple，不能改选锚点。
    fn show_fork_choices(&mut self) -> Result<(), CliError> {
        if self.pending_input.is_some() {
            return Err(CliError::usage("发送结果待核实；按 F5 核实后再分叉"));
        }
        if let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.clone()) {
            self.restore_pending_fork_for_thread(&thread_id);
        }
        if let Some(pending) = self.pending_fork.clone() {
            let current = self.thread.as_ref().map(|thread| thread.thread_id.as_str());
            return match pending_fork_action(&pending, current)? {
                PendingForkAction::OpenExistingChild(_) => self.open_pending_fork(),
                PendingForkAction::SubmitFrozenRequest => self.submit_pending_fork(&pending),
            };
        }
        if self.thread.is_none() {
            return Err(CliError::usage("请先选择会话"));
        }
        self.refresh_history()?;
        let history = self.merged_history()?;
        self.fork_reply_base_choices = fork_reply_choices(&history)?;
        if self.fork_reply_older_choices.is_empty() {
            self.fork_reply_cursor = fork_reply_page_cursor(&self.history);
        }
        let choices = combined_fork_reply_choices(
            &self.fork_reply_older_choices,
            &self.fork_reply_base_choices,
        );
        let selected_id = take_loaded_fork_retry_item(&mut self.fork_retry_item_id, &choices);
        self.send(UiEvent::SetForkReplyPage {
            choices,
            next_cursor: self.fork_reply_cursor.clone(),
            selected_id,
            prepend: false,
        });
        Ok(())
    }

    /// @author kongweiguang
    /// @author kongweiguang
    /// 提交 UI 指定的持久 final_answer item；Thread 可继续工作，但流式与过程项不具备锚点资格。
    fn fork_thread_at(&mut self, item_id: &str) -> Result<(), CliError> {
        if self.pending_input.is_some() {
            return Err(CliError::usage("发送结果待核实；按 F5 核实后再分叉"));
        }
        if let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.clone()) {
            self.restore_pending_fork_for_thread(&thread_id);
        }
        if let Some(pending) = self.pending_fork.clone() {
            if pending.source_item_id != item_id
                || self.thread.as_ref().map(|thread| thread.thread_id.as_str())
                    != Some(pending.source_thread_id.as_str())
            {
                return Err(CliError::usage(
                    "已有分叉操作固定在原回复；按 F5 核实后再选择其他位置。",
                ));
            }
            return match pending_fork_action(
                &pending,
                self.thread.as_ref().map(|thread| thread.thread_id.as_str()),
            )? {
                PendingForkAction::OpenExistingChild(_) => self.open_pending_fork(),
                PendingForkAction::SubmitFrozenRequest => self.submit_pending_fork(&pending),
            };
        }
        self.refresh_history()?;
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let thread_id = thread.thread_id.clone();
        let revision = thread.revision;
        let history = self.merged_history()?;
        self.fork_reply_base_choices = fork_reply_choices(&history)?;
        let loaded_choices = combined_fork_reply_choices(
            &self.fork_reply_older_choices,
            &self.fork_reply_base_choices,
        );
        if !loaded_choices.iter().any(|choice| choice.id == item_id) {
            return Err(CliError::usage("所选回复已不可用；请重新打开 /fork 选择。"));
        }
        if self.fork_retry_item_id.as_deref() != Some(item_id) {
            self.fork_retry_item_id = None;
        }
        let request = PendingFork {
            operation_key: format!("op_{}", uuid::Uuid::new_v4().simple()),
            source_thread_id: thread_id,
            source_item_id: item_id.to_owned(),
            expected_thread_revision: revision,
            child_thread_id: None,
        };
        request
            .write_journal(&self.fork_journal_directory)
            .map_err(CliError::configuration)?;
        self.pending_fork = Some(request.clone());
        self.submit_pending_fork(&request)
    }

    /// @author kongweiguang
    /// 重试前先查来源 owner 的 operation/read；仅显式 unknown 重放原 tuple，避免暂态错误丢失身份。
    fn submit_pending_fork(&mut self, request: &PendingFork) -> Result<(), CliError> {
        let result = match fork_recovery::read_then_retry(&mut self.connection, request) {
            Ok(result) => result,
            Err(fork_recovery::ForkRecoveryError::Read(mut error)) => {
                error
                    .message
                    .push_str("。来源回执核查失败；原分叉请求已保留，按 F5 重查后再试。");
                return Err(error);
            }
            Err(fork_recovery::ForkRecoveryError::InvalidReceipt(message)) => {
                return Err(CliError::protocol(message));
            }
            Err(fork_recovery::ForkRecoveryError::Submit(error)) => {
                return self.handle_pending_fork_submission_error(request, error);
            }
        };
        self.activate_fork(request, &result).map_err(|mut error| {
            error
                .message
                .push_str("；已保存分叉身份，按 F5 重试核实或打开。 ");
            error
        })
    }

    /// @author kongweiguang
    /// 只有精确 revision 冲突或桌面共享的明确拒绝码清理 journal；其他结果保留 tuple，短提示让 F5 可见且不泄漏操作键。
    fn handle_pending_fork_submission_error(
        &mut self,
        request: &PendingFork,
        mut error: CliError,
    ) -> Result<(), CliError> {
        if error.rpc_code.is_some() && is_fork_revision_conflict_message(&error.message) {
            self.pending_fork = None;
            if let Err(cleanup_error) =
                PendingFork::clear_journal(&self.fork_journal_directory, &request.operation_key)
            {
                return Err(CliError::configuration(format!(
                    "分叉因来源版本冲突被拒绝，但待核实记录无法清理：{cleanup_error}"
                )));
            }
            self.fork_retry_item_id = Some(request.source_item_id.clone());
            return match self.refresh_history() {
                Ok(()) => {
                    self.fork_reply_cursor = self
                        .history
                        .get("nextCursor")
                        .and_then(Value::as_str)
                        .map(str::to_owned);
                    Err(CliError::usage(
                        "来源历史已更新；再次打开 /fork 时会选中刚才的同一条回复，可直接重试。",
                    ))
                }
                Err(refresh_error) => Err(CliError::usage(format!(
                    "来源版本已变化，刷新候选失败；按 F5 刷新后重新打开 /fork，选择同一条回复重试。({})",
                    refresh_error.message()
                ))),
            };
        }
        if fork_recovery::is_definitive_fork_rejection(error.rpc_code) {
            self.pending_fork = None;
            if let Err(cleanup_error) =
                PendingFork::clear_journal(&self.fork_journal_directory, &request.operation_key)
            {
                return Err(CliError::configuration(format!(
                    "分叉已被明确拒绝，但待核实记录无法清理：{cleanup_error}"
                )));
            }
            return Err(error);
        }
        error.message = "分叉结果待核实；按 F5 查询原回执。".into();
        Err(error)
    }

    /// @author kongweiguang
    /// F5 与提交前核查共用 sourceThreadId，但只有提交流程会在明确 unknown 后重放请求。
    fn read_pending_fork_operation(
        &mut self,
        request: &PendingFork,
    ) -> Result<Option<Value>, CliError> {
        let lookup = self
            .connection
            .read_operation(&request.operation_key, &request.source_thread_id)?;
        fork_recovery::fork_operation_read_result(&lookup, request).map_err(CliError::protocol)
    }

    /// @author kongweiguang
    /// F5 只读查询携带来源 Thread；迟到回执只记录 child，除非焦点仍属来源或 child，否则不抢焦点。
    fn reconcile_pending_fork(&mut self) {
        let Some(request) = self.pending_fork.clone() else {
            return;
        };
        let current_id = self.thread.as_ref().map(|thread| thread.thread_id.as_str());
        if request.child_thread_id.is_some() {
            if request.allows_opening_from(current_id) {
                if self.open_pending_fork().is_err() {
                    self.notice("已创建的分叉会话尚未打开；按 /fork 重试打开。".into());
                }
            } else {
                self.refresh_pending_fork_listing();
            }
            return;
        }
        match self.read_pending_fork_operation(&request) {
            Ok(Some(result)) => {
                if self.activate_fork(&request, &result).is_err() {
                    self.notice("已创建的分叉会话尚未打开；按 /fork 重试打开。".into());
                }
            }
            Ok(None) => self.notice(
                "原操作账本仍为 unknown；回执身份已保留，重试 /fork 时才会沿用原 tuple 提交。"
                    .into(),
            ),
            Err(_error) => self.notice("分叉结果仍待核实；原请求已保留，按 F5 重查。".into()),
        }
    }

    /// @author kongweiguang
    /// 先验证回执中的 source tuple，再持久化 child ID；只有焦点仍属于来源/child 才打开会话。
    fn activate_fork(&mut self, request: &PendingFork, result: &Value) -> Result<(), CliError> {
        let child_id = request
            .fork_id(result)
            .map_err(CliError::protocol)?
            .to_owned();
        let Some(pending) = self.pending_fork.as_mut() else {
            return Err(CliError::protocol("分叉回执已失去本地操作身份"));
        };
        if pending.operation_key != request.operation_key
            || pending.source_thread_id != request.source_thread_id
            || pending.source_item_id != request.source_item_id
            || pending.expected_thread_revision != request.expected_thread_revision
        {
            return Err(CliError::protocol("分叉回执与本地冻结请求不一致"));
        }
        pending.child_thread_id = Some(child_id.clone());
        pending
            .write_journal(&self.fork_journal_directory)
            .map_err(CliError::configuration)?;
        let current_id = self.thread.as_ref().map(|thread| thread.thread_id.as_str());
        if self
            .pending_fork
            .as_ref()
            .is_some_and(|pending| pending.allows_opening_from(current_id))
        {
            self.open_pending_fork()
        } else {
            self.refresh_pending_fork_listing();
            Ok(())
        }
    }

    /// @author kongweiguang
    /// 迟到创建回执仅刷新当前历史列表并保留焦点；不能把当前会话切到另一个来源的 child。
    fn refresh_pending_fork_listing(&mut self) {
        match self.search_threads("", None) {
            Ok(()) => self.notice(
                "分叉已创建；当前停留在其他会话，历史列表已刷新，切回来源后按 /fork 打开。".into(),
            ),
            Err(error) => self.notice(format!(
                "分叉已创建；当前停留在其他会话，切回来源后按 /fork 打开。历史列表刷新失败：{}",
                error.message()
            )),
        }
    }

    /// @author kongweiguang
    /// 只有当前仍在来源或已经切到 child 时才打开；恢复旧 journal 不得夺走其他 Thread 焦点。
    fn open_pending_fork(&mut self) -> Result<(), CliError> {
        let request = self
            .pending_fork
            .clone()
            .ok_or_else(|| CliError::protocol("没有待打开的分叉会话"))?;
        let child_id = request
            .child_thread_id
            .as_deref()
            .ok_or_else(|| CliError::protocol("分叉回执尚未提供 child identity"))?;
        let current_id = self.thread.as_ref().map(|thread| thread.thread_id.as_str());
        if current_id == Some(child_id) {
            self.refresh()?;
        } else if current_id == Some(request.source_thread_id.as_str()) {
            let fork = load_context(&mut self.connection, child_id)
                .map_err(|_| CliError::usage("已创建，打开失败；输入 /fork 仅重试打开。"))?;
            self.activate_thread(fork)
                .map_err(|_| CliError::usage("已创建，打开失败；输入 /fork 仅重试打开。"))?;
        } else {
            self.notice("分叉已创建；当前仍停留在其他会话，切回来源后可打开分叉。".into());
            return Ok(());
        }
        PendingFork::clear_journal(&self.fork_journal_directory, &request.operation_key).map_err(
            |error| CliError::configuration(format!("分叉已打开，但恢复记录未清理：{error}")),
        )?;
        self.pending_fork = None;
        self.send(UiEvent::ClearForkDraft);
        self.notice("已从此回复新建会话。".into());
        Ok(())
    }

    /// 快照采集不阻塞 TUI；只有通过校验的冻结快照才进入 Java Review。
    fn start_review(&mut self) -> Result<(), CliError> {
        if self.pending_input.is_some() {
            return Err(CliError::usage(
                "发送回执尚待核实；按 F5 确认原操作后再发起代码审查",
            ));
        }
        if self.review_collection.is_some() {
            return Err(CliError::usage("已有 Git 快照采集正在运行"));
        }
        self.refresh()?;
        self.poll_active_reviews()?;
        if self.preferences_sync_pending {
            return Err(CliError::usage(
                "会话偏好尚未核实；按 F5 刷新后再发起代码审查",
            ));
        }
        if self.latest_turn_is_active() {
            return Err(CliError::usage(
                "当前任务仍在运行；完成或停止后再发起代码审查",
            ));
        }
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let thread_id = thread.thread_id.clone();
        if self.active_reviews.iter().any(|active| {
            active.run.thread_id == thread_id
                && matches!(
                    active.status.as_str(),
                    "queued" | "running" | "waiting_approval" | "waiting_input" | "suspended"
                )
        }) {
            return Err(CliError::usage("当前会话已有代码审查正在运行"));
        }
        let workspace_id = thread.workspace_id.clone();
        let root = PathBuf::from(&thread.workspace_root);
        let workspace = open_workspace(&mut self.connection, &root)?;
        if required_str(&workspace, "workspaceId")? != workspace_id {
            return Err(CliError::protocol(
                "Review Workspace identity 与 Thread 不一致",
            ));
        }
        if workspace.get("trust").and_then(Value::as_str) == Some("untrusted") {
            return Err(CliError::needs_trust());
        }
        let cancellation = Arc::new(AtomicBool::new(false));
        let worker_cancellation = Arc::clone(&cancellation);
        let worker_workspace_id = workspace_id.clone();
        let source = ReviewSource::Uncommitted;
        let deadline = Instant::now() + REVIEW_COLLECTION_DEADLINE;
        let (sender, receiver) = mpsc::channel();
        let worker = std::thread::Builder::new()
            .name("ja-cli-review-snapshot".into())
            .spawn(move || {
                let result = review::collect_snapshot(
                    &root,
                    &worker_workspace_id,
                    &source,
                    worker_cancellation.as_ref(),
                    deadline,
                );
                let _ = sender.send(result);
            })
            .map_err(|error| CliError::configuration(format!("无法启动 Git 快照任务：{error}")))?;
        self.review_collection = Some(ReviewCollection {
            thread_id,
            workspace_id,
            cancellation,
            receiver,
            worker: Some(worker),
            result: None,
            cancel_requested: false,
        });
        self.sync_review_operation();
        Ok(())
    }

    /// 取消操作绑定当前 Thread，并由采集器或 Review/read 权威状态核实。
    fn cancel_local_operation(&mut self) -> Result<(), CliError> {
        let thread_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("当前没有可取消的本地后台操作"))?
            .thread_id
            .clone();
        if let Some(collection) = self
            .review_collection
            .as_mut()
            .filter(|collection| collection.thread_id == thread_id)
        {
            if collection.cancel_requested {
                self.notice("Git 快照取消仍在核实；请稍候。".into());
                return Ok(());
            }
            collection.cancel_requested = true;
            collection.cancellation.store(true, Ordering::Release);
            self.send(UiEvent::SetLocalOperation(Some("取消 Git 快照".into())));
            self.notice("已请求取消 Git 快照；正在等待本地进程退出。".into());
            return Ok(());
        }
        let Some(index) = self.active_reviews.iter().position(|active| {
            active.run.thread_id == thread_id
                && matches!(
                    active.status.as_str(),
                    "queued" | "running" | "waiting_approval" | "waiting_input" | "suspended"
                )
        }) else {
            return Err(CliError::usage("当前没有可取消的本地后台操作"));
        };
        if self.active_reviews[index].cancel_requested {
            self.notice("代码审查取消仍在核实；请稍候。".into());
            return Ok(());
        }
        self.active_reviews[index].cancel_requested = true;
        let turn_id = self.active_reviews[index].run.turn_id.clone();
        match self
            .connection
            .request("turn/cancel", json!({"turnId":turn_id}))
        {
            Ok(_) => {
                self.sync_review_operation();
                self.notice("已请求停止代码审查；正在核实最终状态。".into());
            }
            Err(error) if error.is_uncertain() || error.read_retryable => {
                self.sync_review_operation();
                self.notice(format!(
                    "停止请求结果待核实；正在读取审查状态，不会重复提交。({})",
                    error.message()
                ));
            }
            Err(error) => {
                self.active_reviews[index].cancel_requested = false;
                self.notice(format!("停止代码审查失败，可重试：{}", error.message()));
            }
        }
        Ok(())
    }

    /// Git worker 完成后先回收线程，再允许导入附件或启动 Review Turn。
    fn poll_review_collection(&mut self) -> Result<(), CliError> {
        if let Some(collection) = self.review_collection.as_mut()
            && collection.result.is_none()
        {
            match collection.receiver.try_recv() {
                Ok(result) => collection.result = Some(result),
                Err(mpsc::TryRecvError::Empty) => {}
                Err(mpsc::TryRecvError::Disconnected) => {
                    collection.result = Some(Err(CliError::review_failed(
                        "Git 快照任务异常退出，未启动代码审查",
                    )));
                }
            }
        }
        if self.connection_lost
            || !self
                .review_collection
                .as_ref()
                .is_some_and(|collection| collection.result.is_some())
        {
            return Ok(());
        }
        let mut collection = self
            .review_collection
            .take()
            .expect("completed Review collection exists");
        if let Some(worker) = collection.worker.take()
            && worker.join().is_err()
        {
            collection.result = Some(Err(CliError::review_failed(
                "Git 快照任务异常退出，未启动代码审查",
            )));
        }
        if collection.cancel_requested || collection.cancellation.load(Ordering::Acquire) {
            self.review_notice(&collection.thread_id, "代码审查已取消。".into());
            self.sync_review_operation();
            return Ok(());
        }
        match collection
            .result
            .take()
            .expect("worker result is set before collection is consumed")
        {
            Ok(snapshot) => {
                if let Err(error) = self.start_review_from_snapshot(&collection, snapshot) {
                    self.review_notice(&collection.thread_id, error.message().to_owned());
                }
            }
            Err(error) => self.review_notice(&collection.thread_id, error.message().to_owned()),
        }
        self.sync_review_operation();
        Ok(())
    }

    /// 使用最新 Thread 元数据提供 CAS revision，同时保持快照绑定原 Workspace。
    fn start_review_from_snapshot(
        &mut self,
        collection: &ReviewCollection,
        snapshot: ja_review::ReviewSnapshot,
    ) -> Result<(), CliError> {
        let thread = load_context(&mut self.connection, &collection.thread_id)?;
        if thread.workspace_id != collection.workspace_id
            || snapshot.workspace_id != collection.workspace_id
        {
            return Err(CliError::protocol(
                "Review 快照与来源 Thread 的 Workspace identity 不一致",
            ));
        }
        let attachment_id = review::import_snapshot(&mut self.connection, &thread, &snapshot)?;
        let run = match review::start_review(
            &mut self.connection,
            &thread.thread_id,
            thread.revision,
            &attachment_id,
        ) {
            Ok(run) => run,
            Err(error) => {
                if !error.is_uncertain() {
                    let _ = self
                        .connection
                        .request("attachment/discard", json!({"attachmentId":attachment_id}));
                }
                return Err(error);
            }
        };
        let thread_id = run.thread_id.clone();
        self.active_reviews.push(ActiveReview {
            run,
            snapshot,
            status: "queued".into(),
            next_poll: Instant::now(),
            cancel_requested: false,
            read_error_noticed: false,
        });
        self.review_notice(
            &thread_id,
            "代码审查已启动；结果将显示在对应会话中。".into(),
        );
        Ok(())
    }

    /// 只轮询当前选中的 Thread；非活动会话的结果继续保留自身身份。
    fn poll_active_reviews(&mut self) -> Result<(), CliError> {
        let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.clone()) else {
            return Ok(());
        };
        let now = Instant::now();
        let Some(index) = self
            .active_reviews
            .iter()
            .position(|active| active.run.thread_id == thread_id && active.next_poll <= now)
        else {
            return Ok(());
        };
        self.active_reviews[index].next_poll = now + REVIEW_POLL_INTERVAL;
        let run = self.active_reviews[index].run.clone();
        let snapshot = self.active_reviews[index].snapshot.clone();
        let old_status = self.active_reviews[index].status.clone();
        let read = match review::read_review(&mut self.connection, &run.thread_id, &run.turn_id) {
            Ok(read) => read,
            Err(error) => {
                if !self.active_reviews[index].read_error_noticed {
                    self.active_reviews[index].read_error_noticed = true;
                    self.notice(format!(
                        "代码审查状态暂不可读，稍后重试：{}",
                        error.message()
                    ));
                }
                return Ok(());
            }
        };
        self.active_reviews[index].status = read.status.clone();
        self.active_reviews[index].read_error_noticed = false;
        if matches!(
            read.status.as_str(),
            "queued" | "running" | "waiting_approval" | "waiting_input" | "suspended"
        ) {
            if old_status != read.status
                && matches!(read.status.as_str(), "waiting_approval" | "waiting_input")
                && let Err(error) = self.refresh()
            {
                self.notice(format!(
                    "審查需要会话输入；状态刷新失败：{}",
                    error.message()
                ));
            }
            self.sync_review_operation();
            return Ok(());
        }

        let active = self.active_reviews.remove(index);
        let entry = match read.status.as_str() {
            "completed" => match read
                .report
                .as_ref()
                .ok_or_else(|| CliError::protocol("已完成的 Review 缺少冻结报告"))
                .and_then(|report| review::render_report(report, &snapshot))
            {
                Ok(text) => TimelineEntry {
                    id: format!("review:{}", active.run.turn_id),
                    kind: TimelineKind::FinalAnswer,
                    text,
                    detail: Some("Ja Review · 冻结快照报告".into()),
                    status: Some(TimelineStatus::Complete),
                },
                Err(error) => TimelineEntry {
                    id: format!("review:{}", active.run.turn_id),
                    kind: TimelineKind::Assistant,
                    text: format!("Review 报告校验失败：{}", error.message()),
                    detail: None,
                    status: Some(TimelineStatus::Failed),
                },
            },
            "failed" => TimelineEntry {
                id: format!("review:{}", active.run.turn_id),
                kind: TimelineKind::Assistant,
                text: "代码审查未完成；可检查会话诊断后重新发起。".into(),
                detail: None,
                status: Some(TimelineStatus::Failed),
            },
            "cancelled" => TimelineEntry {
                id: format!("review:{}", active.run.turn_id),
                kind: TimelineKind::Assistant,
                text: "代码审查已取消。".into(),
                detail: None,
                status: Some(TimelineStatus::Complete),
            },
            _ => return Err(CliError::protocol("Review/read 返回未知终态")),
        };
        if let Err(error) = self.refresh() {
            self.notice(format!("审查已结束，历史暂时无法刷新：{}", error.message()));
        }
        self.sync_review_operation();
        self.send(UiEvent::UpsertEntry(entry));
        Ok(())
    }

    /// 仅当操作所属 Thread 与当前会话一致时才显示工作状态。
    fn sync_review_operation(&self) {
        let Some(thread_id) = self.thread.as_ref().map(|thread| thread.thread_id.as_str()) else {
            self.send(UiEvent::SetLocalOperation(None));
            return;
        };
        let label = if let Some(collection) = self
            .review_collection
            .as_ref()
            .filter(|collection| collection.thread_id == thread_id)
        {
            Some(if collection.cancel_requested {
                "取消 Git 快照"
            } else {
                "读取 Git 快照"
            })
        } else if let Some(active) = self.active_reviews.iter().find(|active| {
            active.run.thread_id == thread_id
                && matches!(
                    active.status.as_str(),
                    "queued" | "running" | "waiting_approval" | "waiting_input" | "suspended"
                )
        }) {
            Some(if active.cancel_requested {
                "取消代码审查"
            } else {
                "审查工作区变更"
            })
        } else {
            None
        };
        self.send(UiEvent::SetLocalOperation(label.map(str::to_owned)));
    }

    /// 后台失败提示保留到用户返回其来源 Thread 时再展示。
    fn review_notice(&mut self, thread_id: &str, message: String) {
        self.review_notices
            .insert(thread_id.to_owned(), message.clone());
        if self
            .thread
            .as_ref()
            .is_some_and(|thread| thread.thread_id == thread_id)
        {
            self.review_notices.remove(thread_id);
            self.notice(message);
        }
    }

    /// 退出 TUI 只停止本地 Git 采集；已提交的 Java Review Turn 仍可恢复。
    fn cancel_review_collection_on_exit(&mut self) {
        if let Some(mut collection) = self.review_collection.take() {
            collection.cancellation.store(true, Ordering::Release);
            if let Some(worker) = collection.worker.take() {
                let _ = worker.join();
            }
        }
    }

    /// Review 活动状态只按稳定 Thread identity 查找，不使用选择序号或标题。
    fn has_active_review(&self, thread_id: &str) -> bool {
        self.active_reviews.iter().any(|active| {
            active.run.thread_id == thread_id
                && matches!(
                    active.status.as_str(),
                    "queued" | "running" | "waiting_approval" | "waiting_input" | "suspended"
                )
        })
    }

    /// 仅复制最新的完整公开答复；截断预览会先补齐正文。
    fn copy_last_reply(&mut self) -> Result<(), CliError> {
        let thread_id = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?
            .thread_id
            .clone();
        let item =
            self.history
                .get("items")
                .and_then(Value::as_array)
                .and_then(|items| {
                    items.iter().rev().find(|item| {
                        item.get("kind").and_then(Value::as_str) == Some("final_answer")
                    })
                })
                .ok_or_else(|| CliError::usage("当前会话没有最终回复可复制"))?;
        let text = if item.get("truncated").and_then(Value::as_bool) == Some(true) {
            read_public_item_content(
                &mut self.connection,
                &thread_id,
                required_str(item, "itemId")?,
                required_u64(item, "totalCharacters")?,
                None,
            )?
        } else {
            required_str(item, "text")?.to_owned()
        };
        copy_to_clipboard(&text)?;
        self.notice("最后一条完整回复已复制。".into());
        Ok(())
    }

    /// TUI 确认挂起后才把终端交给外部编辑器；失败时恢复原草稿。
    fn edit_draft(&mut self, draft: &str) -> Result<(), CliError> {
        let editor = match editor_command() {
            Ok(Some(editor)) => editor,
            Ok(None) => {
                self.notice("未设置 VISUAL 或 EDITOR；设置后按 Ctrl+G 编辑草稿。".into());
                return Ok(());
            }
            Err(error) => {
                self.notice(format!("编辑器配置无效，原草稿已保留：{error}"));
                return Ok(());
            }
        };
        let temporary = match create_editor_draft(draft) {
            Ok(value) => value,
            Err(error) => {
                self.notice(format!("无法准备编辑文件，原草稿已保留：{}", error));
                return Ok(());
            }
        };
        let path = temporary.path.clone();
        if let Err(error) = self.bridge.suspend_for_external_program() {
            self.notice(format!("终端未能交给编辑器，原草稿已保留：{error}"));
            return Ok(());
        }
        let edit_result = run_editor(&editor, &path);
        let resume_result = self.bridge.resume_after_external_program();
        match resume_result {
            Ok(()) => {}
            Err(error) => return Err(CliError::transport(error)),
        }
        match edit_result {
            Ok(Some(text)) => self.send(UiEvent::ReplaceDraft(text)),
            Ok(None) => self.notice("编辑器已取消或退出异常，原草稿已保留。".into()),
            Err(error) => self.notice(format!("编辑器启动失败，原草稿已保留：{error}")),
        }
        Ok(())
    }

    /// 导出在同一 Thread revision 下读取全部历史页，完整文档校验通过后才写入。
    fn export_thread(&mut self, path: Option<PathBuf>) -> Result<(), CliError> {
        let thread = self
            .thread
            .as_ref()
            .ok_or_else(|| CliError::usage("请先选择会话"))?;
        let thread_id = thread.thread_id.clone();
        let title = thread.title.clone();
        let workspace_root = self.workspace_root.clone();
        let target = path.unwrap_or_else(|| workspace_root.join(format!("{thread_id}.md")));
        self.notice("正在读取完整会话历史…".into());
        let (revision, items) = read_export_history(&mut self.connection, &thread_id)?;
        let document =
            format_export_document(&mut self.connection, &thread_id, &title, revision, &items)?;
        if document.len() > MAX_EXPORT_BYTES {
            return Err(CliError::usage("会话导出超过 64 MiB；未创建目标文件"));
        }
        write_new_file_atomically(&target, document.as_bytes())?;
        self.notice(format!(
            "已导出 {} 条历史记录到 {}。",
            items.len(),
            target.display()
        ));
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

    /// 取消只读取最近 native Turn；inherited Turn 是冻结展示历史，没有本会话执行能力。
    fn latest_active_turn(&self) -> Result<String, CliError> {
        self.latest_native_turn()
            .filter(|turn| {
                matches!(
                    turn.get("status").and_then(Value::as_str),
                    Some("queued" | "running" | "waiting_input" | "waiting_approval" | "suspended")
                )
            })
            .and_then(|turn| turn.get("turnId"))
            .and_then(Value::as_str)
            .map(str::to_owned)
            .ok_or_else(|| CliError::usage("当前没有可取消的 Turn"))
    }

    /// 只有最近 native Turn 决定工作轮询；inherited 状态不能让新会话误显示为运行中。
    fn latest_turn_is_active(&self) -> bool {
        self.latest_native_turn().is_some_and(|turn| {
            matches!(
                turn.get("status").and_then(Value::as_str),
                Some("queued" | "running" | "waiting_input" | "waiting_approval" | "suspended")
            )
        })
    }

    /// 继承 Turn 不代表当前会话的执行状态，所有取消、续答和状态说明统一取最新 native Turn。
    fn latest_native_turn(&self) -> Option<&Value> {
        self.history
            .get("turns")
            .and_then(Value::as_array)
            .and_then(|turns| {
                turns
                    .iter()
                    .rev()
                    .find(|turn| turn.get("origin").and_then(Value::as_str) != Some("inherited"))
            })
    }
}

const TURN_DIFF_DISPLAY_MAX_BYTES: usize = 96 * 1024;
const TURN_DIFF_MAX_FILES: usize = 12;
const TURN_DIFF_LIMIT_NOTICE: &str = "\n\n（差异详情已达 96 KiB 展示上限；后续文件未读取）";
const TURN_DIFF_FILE_LIMIT_NOTICE: &str = "\n\n（仅展示前 12 个文件）";

/// @author kongweiguang
/// F5 只补齐最初冻结的同一 native Turn；已有快照不可被后续状态改写，避免差异身份漂移。
pub(super) fn refresh_uncaptured_diff_turn(frozen_history: &mut Value, current_history: &Value) {
    let Some(frozen_turn) = frozen_history
        .get_mut("turns")
        .and_then(Value::as_array_mut)
        .and_then(|turns| {
            turns
                .iter_mut()
                .rev()
                .find(|turn| turn.get("origin").and_then(Value::as_str) == Some("native"))
        })
    else {
        return;
    };
    if frozen_turn
        .get("changeSet")
        .is_none_or(|change_set| !change_set.is_null())
    {
        return;
    }
    let Some(turn_id) = frozen_turn
        .get("turnId")
        .and_then(Value::as_str)
        .map(str::to_owned)
    else {
        return;
    };
    let Some(change_set) = current_history
        .get("turns")
        .and_then(Value::as_array)
        .and_then(|turns| {
            turns.iter().rev().find(|turn| {
                turn.get("origin").and_then(Value::as_str) == Some("native")
                    && turn.get("turnId").and_then(Value::as_str) == Some(turn_id.as_str())
            })
        })
        .and_then(|turn| turn.get("changeSet"))
        .filter(|change_set| !change_set.is_null())
    else {
        return;
    };
    if let Some(frozen_turn) = frozen_turn.as_object_mut() {
        frozen_turn.insert("changeSet".into(), change_set.clone());
    }
}

/// 空记录与未完整捕获的记录不要求 artifact；变更详情只绑定最近 native Turn，不解释冻结父历史。
pub(super) fn read_turn_diff(
    thread_id: &str,
    history: &Value,
    mut read_file: impl FnMut(Value) -> Result<Value, CliError>,
) -> Result<String, CliError> {
    let Some(turn) = history
        .get("turns")
        .and_then(Value::as_array)
        .and_then(|turns| {
            turns
                .iter()
                .rev()
                .find(|turn| turn.get("origin").and_then(Value::as_str) != Some("inherited"))
        })
    else {
        return Ok("当前会话还没有回复记录".into());
    };
    let turn_id = required_str(turn, "turnId")?;
    let change_set = turn
        .get("changeSet")
        .ok_or_else(|| CliError::protocol("变更记录缺少 changeSet"))?;
    if change_set.is_null() {
        return Ok("本次回复尚无冻结变更记录，无法确认文件修改情况".into());
    }
    let partial = match required_str(change_set, "state")? {
        "complete" => false,
        "partial" => true,
        _ => return Err(CliError::protocol("变更记录状态无效")),
    };
    let files = change_set
        .get("files")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("变更记录缺少 files"))?;
    if files.is_empty() {
        return Ok(if partial {
            "本次变更记录不完整，尚无可展示的已确认文件差异；可通过 Git 检查当前工作区".into()
        } else {
            "本次回复没有文件修改".into()
        });
    }
    let artifact_id = match change_set.get("artifactId") {
        None => None,
        Some(Value::String(value)) if !value.is_empty() => Some(value.as_str()),
        Some(_) => return Err(CliError::protocol("变更记录 artifactId 无效")),
    };
    let mut body = if partial {
        "变更记录不完整，以下仅展示已确认的文件差异。\n".to_owned()
    } else {
        String::new()
    };
    let mut display_truncated = false;
    for file in files.iter().take(TURN_DIFF_MAX_FILES) {
        let path = required_str(file, "path")?;
        if !append_turn_diff_text(&mut body, &format!("\n--- {} ---\n", terminal_safe(path))) {
            display_truncated = true;
            break;
        }
        let file_truncated = file
            .get("truncated")
            .and_then(Value::as_bool)
            .ok_or_else(|| CliError::protocol("变更文件缺少 truncated 标记"))?;
        if file_truncated || artifact_id.is_none() {
            if !append_turn_diff_text(&mut body, "此文件的差异正文未捕获，无法展示\n")
            {
                display_truncated = true;
                break;
            }
            continue;
        }
        let content = read_turn_diff_file(
            thread_id,
            turn_id,
            artifact_id.ok_or_else(|| CliError::protocol("变更文件缺少冻结 artifact 身份"))?,
            path,
            &mut read_file,
        )?;
        let mut safe_content = terminal_safe(&content);
        safe_content.push('\n');
        if !append_turn_diff_text(&mut body, &safe_content) {
            display_truncated = true;
            break;
        }
    }
    if !display_truncated && files.len() > TURN_DIFF_MAX_FILES {
        body.push_str(TURN_DIFF_FILE_LIMIT_NOTICE);
    }
    Ok(body)
}

/// 每份文件正文都必须匹配被冻结的四元身份和完整字节指纹，拒绝损坏内容而不做有损展示。
fn read_turn_diff_file(
    thread_id: &str,
    turn_id: &str,
    artifact_id: &str,
    file_path: &str,
    read_file: &mut impl FnMut(Value) -> Result<Value, CliError>,
) -> Result<String, CliError> {
    use sha2::Digest;

    let request = ja_runtime::app_server_process::TurnChangeSetReadParams::new(
        thread_id,
        turn_id,
        artifact_id,
        file_path,
    )
    .map_err(|_| CliError::protocol("变更文件读取身份无效"))?;
    let params = serde_json::to_value(request)
        .map_err(|_| CliError::protocol("变更文件读取请求无法编码"))?;
    let raw_result = read_file(params)?;
    let result = ja_runtime::app_server_process::TurnChangeSetReadResult::try_from(&raw_result)
        .map_err(|_| CliError::protocol("变更文件读取响应不符合协议"))?;
    if result.artifact_id != artifact_id || result.file_path != file_path {
        return Err(CliError::protocol("变更文件读取响应身份不一致"));
    }

    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&result.content_base64)
        .map_err(|_| CliError::protocol("变更文件正文不是有效 Base64"))?;
    if base64::engine::general_purpose::STANDARD.encode(&bytes) != result.content_base64 {
        return Err(CliError::protocol("变更文件正文 Base64 编码不规范"));
    }
    if u64::try_from(bytes.len()).ok() != Some(result.byte_length) {
        return Err(CliError::protocol("变更文件正文字节长度不一致"));
    }
    let digest = sha2::Sha256::digest(&bytes);
    let mut actual_digest = String::with_capacity(64);
    for byte in digest {
        actual_digest.push_str(&format!("{byte:02x}"));
    }
    if actual_digest != result.sha256 {
        return Err(CliError::protocol("变更文件正文 SHA-256 不匹配"));
    }
    String::from_utf8(bytes).map_err(|_| CliError::protocol("变更文件正文不是有效 UTF-8"))
}

/// 统一详情层 96 KiB 上限并预留完整说明；截断按 UTF-8 边界发生且调用方据此停止后续读取。
fn append_turn_diff_text(body: &mut String, value: &str) -> bool {
    let reserved_notice = TURN_DIFF_LIMIT_NOTICE
        .len()
        .max(TURN_DIFF_FILE_LIMIT_NOTICE.len());
    let available = TURN_DIFF_DISPLAY_MAX_BYTES
        .saturating_sub(body.len())
        .saturating_sub(reserved_notice);
    if value.len() <= available {
        body.push_str(value);
        return true;
    }

    let mut end = available.min(value.len());
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    body.push_str(&value[..end]);
    body.push_str(TURN_DIFF_LIMIT_NOTICE);
    false
}

/// 恢复身份取同一活动 ModelStep 的首个 assistant 段，reasoning 分隔不能新建第二条回答。
pub(super) fn resumed_live_assistant_entry_id(stream: &Value) -> Option<String> {
    let turn_id = stream.get("turnId")?.as_str()?;
    let first_assistant = stream
        .get("segments")?
        .as_array()?
        .iter()
        .find(|segment| segment.get("kind").and_then(Value::as_str) == Some("assistant"))?;
    let start = first_assistant.get("segmentStartSeq")?.as_u64()?;
    Some(format!("live:{turn_id}:{start}"))
}

/// 摘要与正文共享序号但分别接续，恢复时取首个公开 reasoning 段，不接触私有推理。
pub(super) fn resumed_live_reasoning_entry_id(stream: &Value) -> Option<String> {
    let turn_id = stream.get("turnId")?.as_str()?;
    let first =
        stream.get("segments")?.as_array()?.iter().find(|segment| {
            segment.get("kind").and_then(Value::as_str) == Some("reasoningSummary")
        })?;
    let start = first.get("segmentStartSeq")?.as_u64()?;
    Some(format!("live-reasoning:{turn_id}:{start}"))
}

/// 先完整读取并校验 metadata，再一次性更新本地 Thread；失败保留此前已确认的标题与偏好。
pub(super) fn reload_thread_metadata(
    thread: &mut Option<ThreadContext>,
    preferences_sync_pending: &mut bool,
    read: impl FnOnce(&str, &str) -> Result<Value, CliError>,
) -> Result<(), CliError> {
    let Some(current) = thread.as_ref() else {
        return Ok(());
    };
    let metadata = read(&current.workspace_id, &current.thread_id)?;
    apply_thread_metadata_snapshot(thread, preferences_sync_pending, &metadata)
}

/// 只在标题、revision 和偏好都符合已有协议后提交快照，避免部分字段来自不同读取。
fn apply_thread_metadata_snapshot(
    thread: &mut Option<ThreadContext>,
    preferences_sync_pending: &mut bool,
    metadata: &Value,
) -> Result<(), CliError> {
    if thread.is_none() {
        return Ok(());
    }
    let title = required_str(metadata, "title")?.to_owned();
    let revision = required_u64(metadata, "revision")?;
    let preferences_value = metadata.get("preferences").filter(|value| !value.is_null());
    if *preferences_sync_pending && preferences_value.is_none() {
        return Err(CliError::protocol("偏好核实快照缺少 preferences"));
    }
    let preferences = preferences_value
        .map(|prefs| {
            let reasoning_level = match prefs.get("reasoningLevel") {
                Some(Value::Null) => None,
                Some(Value::String(level)) => Some(level.clone()),
                _ => return Err(CliError::protocol("偏好核实快照缺少有效推理等级")),
            };
            required_str(prefs, "collaborationMode")?;
            Ok::<_, CliError>((
                required_str(prefs, "providerId")?.to_owned(),
                required_str(prefs, "modelId")?.to_owned(),
                reasoning_level,
                required_str(prefs, "accessMode")?.to_owned(),
            ))
        })
        .transpose()?;
    if let Some(current) = thread.as_mut() {
        current.title = title;
        current.revision = revision;
        if let Some((provider_id, model_id, reasoning_level, access_mode)) = preferences {
            current.provider_id = provider_id;
            current.model_id = model_id;
            current.reasoning_level = reasoning_level;
            current.access_mode = access_mode;
        }
    }
    if preferences_value.is_some() {
        *preferences_sync_pending = false;
    }
    Ok(())
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

/// 全局续接复用服务端 keyset 分页，并让每条结果绑定 Java Thread identity。
fn list_all_thread_choices(
    connection: &mut Connection,
    query: &str,
    cursor: Option<&str>,
) -> Result<(Vec<UiChoice>, Option<String>), CliError> {
    let mut params = json!({"scope":"all","query":query,"limit":50});
    if let Some(cursor) = cursor {
        params["cursor"] = json!(cursor);
    }
    let result = connection.request("thread/list", params)?;
    let choices = result
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("全局历史列表缺少 items"))?
        .iter()
        .map(|item| {
            let status = item
                .get("status")
                .and_then(Value::as_str)
                .map(thread_status_label);
            let workspace = item
                .get("workspaceName")
                .or_else(|| item.get("workspaceId"))
                .and_then(Value::as_str);
            Ok(UiChoice {
                id: required_str(item, "threadId")?.into(),
                label: required_str(item, "title")?.into(),
                detail: match (status, workspace) {
                    (Some(status), Some(workspace)) => Some(format!("{status} · {workspace}")),
                    (Some(status), None) => Some(status.to_owned()),
                    (None, Some(workspace)) => Some(workspace.to_owned()),
                    (None, None) => None,
                },
            })
        })
        .collect::<Result<Vec<_>, CliError>>()?;
    let next_cursor = result
        .get("nextCursor")
        .and_then(Value::as_str)
        .map(str::to_owned);
    Ok((choices, next_cursor))
}

/// `--last` asks Java for the newest workspace Thread instead of inferring recency from local history.
fn last_thread_in_workspace(
    connection: &mut Connection,
    workspace_id: &str,
) -> Result<Option<String>, CliError> {
    let result = connection.request(
        "thread/list",
        json!({"scope":"all","workspaceId":workspace_id,"limit":1}),
    )?;
    let items = result
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("最近会话查询缺少 items"))?;
    items
        .first()
        .map(|item| required_str(item, "threadId").map(str::to_owned))
        .transpose()
}

/// 会话候选只翻译冻结的状态枚举，不对未知 wire 状态猜测完成事实。
fn thread_status_label(status: &str) -> &'static str {
    match status {
        "idle" => "空闲",
        "queued" | "running" | "waiting_approval" | "suspended" => "正在工作",
        "waiting_input" => "等待回答",
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

/// @author kongweiguang
/// 终态回答先读全再投影，复用已核实正文；预览不得覆盖全文或让迟到通知丢失回答。
fn read_baseline(
    connection: &mut Connection,
    thread_id: &str,
    workspace_id: &str,
    previous: &Value,
    deadline: Option<Instant>,
) -> Result<(Value, Value, Value), CliError> {
    let mut history = request_with_deadline(
        connection,
        "thread/read",
        json!({"threadId":thread_id,"tail":true,"limit":200}),
        deadline,
    )?;
    validate_history_origins(&history)?;
    hydrate_history_public_items(&mut history, previous, |item_id, total_characters| {
        // 正文归档使用无显示字节上限；diagnostic deadline 仍约束全部页与 RPC。
        match deadline {
            Some(deadline) => read_public_item_content_until(
                connection,
                thread_id,
                item_id,
                total_characters,
                None,
                deadline,
            ),
            None => {
                read_public_item_content(connection, thread_id, item_id, total_characters, None)
            }
        }
    })?;
    let interaction = request_with_deadline(
        connection,
        "interaction/read",
        json!({"threadId":thread_id}),
        deadline,
    )?;
    let config = request_with_deadline(
        connection,
        "configuration/read",
        json!({"workspaceId":workspace_id}),
        deadline,
    )?;
    Ok((history, interaction, config))
}

/// @author kongweiguang
/// 投影归档前补齐当前页全部公开文本；先复用上一快照的完整正文，避免轮询重复读取旧条目。
pub(super) fn hydrate_history_public_items(
    history: &mut Value,
    previous: &Value,
    mut read_content: impl FnMut(&str, u64) -> Result<String, CliError>,
) -> Result<(), CliError> {
    retain_complete_public_text(history, previous)?;
    let items = history
        .get_mut("items")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 items"))?;
    for item in items {
        hydrate_public_history_item(item, |item_id, total_characters| {
            read_content(item_id, total_characters)
        })?;
    }
    Ok(())
}

/// @author kongweiguang
/// 所有公开文本按同一持久身份及总长复用全文，避免预览覆盖缓存或重复正文 RPC。
fn retain_complete_public_text(history: &mut Value, previous: &Value) -> Result<(), CliError> {
    let Some(previous_items) = previous.get("items").and_then(Value::as_array) else {
        return Ok(());
    };
    let items = history
        .get_mut("items")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| CliError::protocol("thread/read 缺少 items"))?;
    for item in items
        .iter_mut()
        .filter(|item| item.get("truncated").and_then(Value::as_bool) == Some(true))
    {
        let Some(kind) = item.get("kind").and_then(Value::as_str) else {
            continue;
        };
        if !is_public_text_kind(kind) {
            continue;
        }
        let (Some(item_id), Some(turn_id), Some(total_characters)) = (
            item.get("itemId").and_then(Value::as_str),
            item.get("turnId").and_then(Value::as_str),
            item.get("totalCharacters").and_then(Value::as_u64),
        ) else {
            continue;
        };
        let cached = previous_items.iter().find(|candidate| {
            candidate.get("itemId").and_then(Value::as_str) == Some(item_id)
                && candidate.get("turnId").and_then(Value::as_str) == Some(turn_id)
                && candidate.get("kind").and_then(Value::as_str) == Some(kind)
                && candidate.get("totalCharacters").and_then(Value::as_u64)
                    == Some(total_characters)
                && candidate.get("truncated").and_then(Value::as_bool) == Some(false)
        });
        if let Some(text) = cached.and_then(public_history_item_text) {
            let text = text.to_owned();
            hydrate_public_history_item(item, |_, _| Ok(text.clone()))?;
        }
    }
    Ok(())
}

/// @author kongweiguang
/// 公开正文不同 kind 使用不同协议槽位；只接受已知文本位置，不读取工具或推理私有载荷。
fn public_history_item_text(item: &Value) -> Option<&str> {
    match item.get("kind")?.as_str()? {
        "user_input" => item
            .get("content")?
            .as_array()?
            .iter()
            .find(|part| part.get("type").and_then(Value::as_str) == Some("text"))?
            .get("text")?
            .as_str(),
        "thread_message" => item.get("content")?.as_str(),
        "assistant_progress" | "final_answer" | "reasoning_summary" => item.get("text")?.as_str(),
        _ => None,
    }
}

/// @author kongweiguang
/// 白名单与 hydrate_public_history_item 同步，未知 Item 类型保留安全预览且不寻址私有正文。
fn is_public_text_kind(kind: &str) -> bool {
    matches!(
        kind,
        "user_input"
            | "assistant_progress"
            | "final_answer"
            | "reasoning_summary"
            | "thread_message"
    )
}

/// @author kongweiguang
/// 诊断快照的多次只读 RPC 共用一个 deadline；其他刷新继续使用既有单请求超时。
fn request_with_deadline(
    connection: &mut Connection,
    method: &str,
    params: Value,
    deadline: Option<Instant>,
) -> Result<Value, CliError> {
    match deadline {
        Some(deadline) => {
            connection.request_with_timeout(method, params, remaining_status(deadline)?)
        }
        None => connection.request(method, params),
    }
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
    list_thread_in_workspace_until(
        connection,
        workspace_id,
        thread_id,
        Instant::now() + DISCOVERY_DEADLINE,
        remaining_scan,
    )
}

/// @author kongweiguang
/// 同一分页读取可注入调用方的绝对预算，`/status` 不会为每页或后续读取重置超时。
fn list_thread_in_workspace_until(
    connection: &mut Connection,
    workspace_id: &str,
    thread_id: &str,
    deadline: Instant,
    remaining: impl Fn(Instant) -> Result<Duration, CliError>,
) -> Result<Value, CliError> {
    let mut cursor: Option<String> = None;
    for _ in 0..MAX_DISCOVERY_PAGES {
        let mut params = json!({"workspaceId":workspace_id,"limit":200});
        if let Some(cursor) = &cursor {
            params["cursor"] = json!(cursor);
        }
        let result =
            connection.request_with_timeout("thread/list", params, remaining(deadline)?)?;
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

/// 分页共用调用方的 deadline；发现与正文操作使用不同期限，错误不冒充固定秒数。
fn remaining_scan(deadline: Instant) -> Result<Duration, CliError> {
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err(CliError::transport("分页读取超过当前操作时限"));
    }
    Ok(remaining)
}

/// @author kongweiguang
/// `/status` 多个只读 RPC 共用五秒上限，超时诊断不无限叠加默认十五秒请求窗口。
fn remaining_status(deadline: Instant) -> Result<Duration, CliError> {
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err(CliError::transport("状态读取超过当前诊断时限"));
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

/// 渲染有界 Skills 目录，不暴露安装路径或 Markdown 正文。
fn format_skill_catalog(items: &[Value], truncated: bool) -> Result<String, CliError> {
    if items.is_empty() {
        return Ok("没有可用 Skills".into());
    }
    let mut body = format!("共 {} 个 Skills\n\n", items.len());
    for item in items {
        let scope = match required_str(item, "scope")? {
            "user" => "用户",
            "ja" => "Ja",
            "project" => "项目",
            _ => return Err(CliError::protocol("Skill 返回未知 scope")),
        };
        let state = match required_str(item, "status")? {
            "healthy" => "可用",
            "invalid" => "无效",
            "unavailable" => "不可用",
            _ => return Err(CliError::protocol("Skill 返回未知状态")),
        };
        let enabled = item
            .get("enabled")
            .and_then(Value::as_bool)
            .unwrap_or(false);
        body.push_str(&format!(
            "{}  · {} · {}{}\n",
            required_str(item, "name")?,
            scope,
            state,
            if enabled {
                " · 已启用"
            } else {
                " · 未启用"
            }
        ));
    }
    if truncated {
        body.push_str("\n目录较大，仅显示前 1000 项。\n");
    }
    Ok(body)
}

/// MCP 视图只渲染状态事实；服务配置与凭据不进入 TUI。
fn format_mcp_catalog(items: &[Value], truncated: bool) -> Result<String, CliError> {
    if items.is_empty() {
        return Ok("当前工作区没有 MCP 连接".into());
    }
    let mut body = format!("共 {} 个 MCP 连接\n\n", items.len());
    for item in items {
        let state = match required_str(item, "status")? {
            "healthy" => "健康",
            "degraded" => "降级",
            "unavailable" => "不可用",
            "disabled" => "已停用",
            "configured" => "尚未探测",
            _ => return Err(CliError::protocol("MCP 返回未知状态")),
        };
        let scope = match required_str(item, "scope")? {
            "global" => "全局",
            "project" => "项目",
            _ => return Err(CliError::protocol("MCP 返回未知 scope")),
        };
        let transport = match required_str(item, "transport")? {
            "stdio" => "stdio",
            "streamable_http" => "HTTP",
            _ => return Err(CliError::protocol("MCP 返回未知传输类型")),
        };
        body.push_str(&format!(
            "{}  · {} · {} · {} 个工具 · {}\n",
            required_str(item, "name")?,
            scope,
            transport,
            required_u64(item, "toolCount")?,
            state
        ));
    }
    if truncated {
        body.push_str("\n目录较大，仅显示前 1000 项。\n");
    }
    Ok(body)
}

/// @author kongweiguang
/// 正文只经 UTF-8 stdin 传入子进程；优先 pwsh，Set-Clipboard 缺失才回退 5.1，并静音 stdout、限长收集 stderr。
fn copy_to_clipboard(text: &str) -> Result<(), CliError> {
    #[cfg(target_os = "windows")]
    let candidates: &[(&str, &[&str])] = &[
        (
            "pwsh.exe",
            &[
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "$ErrorActionPreference='Stop'; if (-not (Get-Command Set-Clipboard -ErrorAction SilentlyContinue)) { throw 'JA_CLIPBOARD_COMMAND_MISSING' }; [Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); $text=[Console]::In.ReadToEnd(); Set-Clipboard -Value $text",
            ],
        ),
        (
            "powershell.exe",
            &[
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "$ErrorActionPreference='Stop'; if (-not (Get-Command Set-Clipboard -ErrorAction SilentlyContinue)) { throw 'JA_CLIPBOARD_COMMAND_MISSING' }; [Console]::InputEncoding=[System.Text.UTF8Encoding]::new($false); $text=[Console]::In.ReadToEnd(); Set-Clipboard -Value $text",
            ],
        ),
    ];
    #[cfg(target_os = "macos")]
    let candidates: &[(&str, &[&str])] = &[("pbcopy", &[])];
    #[cfg(all(unix, not(target_os = "macos")))]
    let candidates: &[(&str, &[&str])] =
        &[("wl-copy", &[]), ("xclip", &["-selection", "clipboard"])];

    let mut last_error = None;
    for (index, (program, arguments)) in candidates.iter().enumerate() {
        let mut child = match Command::new(program)
            .args(*arguments)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn()
        {
            Ok(child) => child,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                last_error = Some(format!("{program} 不存在：{error}"));
                continue;
            }
            Err(error) => return Err(CliError::transport(error)),
        };
        let Some(stderr) = child.stderr.take() else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(CliError::transport("剪贴板进程未提供错误输出管道"));
        };
        let stderr_reader = match std::thread::Builder::new()
            .name("ja-clipboard-diagnostic".into())
            .spawn(move || read_bounded_clipboard_diagnostic(stderr))
        {
            Ok(reader) => reader,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(CliError::transport(error));
            }
        };
        let Some(mut stdin) = child.stdin.take() else {
            let _ = child.kill();
            let _ = child.wait();
            let _ = stderr_reader.join();
            return Err(CliError::transport("剪贴板进程未提供输入管道"));
        };
        let write_result = write_clipboard_input(&mut stdin, text);
        drop(stdin);
        if let Err(error) = write_result {
            let _ = child.kill();
            let _ = child.wait();
            let _ = stderr_reader.join();
            return Err(CliError::transport(error));
        }
        let status = match child.wait() {
            Ok(status) => status,
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = stderr_reader.join();
                return Err(CliError::transport(error));
            }
        };
        let diagnostic = stderr_reader
            .join()
            .map_err(|_| CliError::transport("剪贴板错误输出读取线程异常"))?
            .map_err(CliError::transport)?;
        if status.success() {
            return Ok(());
        }
        let command_missing = diagnostic.contains("JA_CLIPBOARD_COMMAND_MISSING");
        last_error = Some(if diagnostic.is_empty() {
            format!("{program} exited with {status}")
        } else {
            format!("{program} exited with {status}: {diagnostic}")
        });
        if command_missing && index + 1 < candidates.len() {
            continue;
        }
        break;
    }
    Err(CliError::usage(format!(
        "无法写入系统剪贴板{}",
        last_error.map_or_else(String::new, |error| format!("：{error}"))
    )))
}

/// @author kongweiguang
/// 持续排空子进程错误管道但仅保留固定字节数，避免诊断输出阻塞或占满全屏终端。
pub(super) fn read_bounded_clipboard_diagnostic(mut reader: impl Read) -> std::io::Result<String> {
    let mut retained = Vec::with_capacity(MAX_CLIPBOARD_DIAGNOSTIC_BYTES);
    let mut chunk = [0_u8; 256];
    let mut truncated = false;
    loop {
        let count = reader.read(&mut chunk)?;
        if count == 0 {
            break;
        }
        let remaining = MAX_CLIPBOARD_DIAGNOSTIC_BYTES.saturating_sub(retained.len());
        let retained_count = count.min(remaining);
        retained.extend_from_slice(&chunk[..retained_count]);
        truncated |= retained_count < count;
    }
    let mut diagnostic = String::from_utf8_lossy(&retained).trim().to_owned();
    if truncated {
        diagnostic.push_str("…[诊断已截断]");
    }
    Ok(diagnostic)
}

/// @author kongweiguang
/// 剪贴板正文经 UTF-8 字节写入子进程 stdin，测试可验证 Unicode 不受 Windows argv/code page 影响。
pub(super) fn write_clipboard_input(writer: &mut impl Write, text: &str) -> std::io::Result<()> {
    writer.write_all(text.as_bytes())
}

/// @author kongweiguang
/// 去重身份只使用持久 itemId；新页按服务端旧到新顺序保留，避免并发新消息造成重复展示。
pub(super) fn unseen_history_page_items(
    older_items: &[Value],
    latest_items: &[Value],
    page_items: &[Value],
) -> Result<Vec<Value>, CliError> {
    let mut seen =
        HashSet::with_capacity(older_items.len() + latest_items.len() + page_items.len());
    for item in older_items.iter().chain(latest_items) {
        seen.insert(required_str(item, "itemId")?.to_owned());
    }
    page_items
        .iter()
        .filter_map(|item| match required_str(item, "itemId") {
            Ok(item_id) if seen.insert(item_id.to_owned()) => Some(Ok(item.clone())),
            Ok(_) => None,
            Err(error) => Some(Err(error)),
        })
        .collect()
}

/// @author kongweiguang
/// 旧 Turn 先占据历史位置；边界重复由最新快照覆盖状态，避免来源信息丢失或状态回退。
pub(super) fn merged_history_turns(
    older_turns: &[Value],
    latest_turns: &[Value],
) -> Result<Vec<Value>, CliError> {
    let mut positions = HashMap::with_capacity(older_turns.len() + latest_turns.len());
    let mut merged = Vec::with_capacity(older_turns.len() + latest_turns.len());
    for turn in older_turns.iter().chain(latest_turns) {
        let turn_id = required_str(turn, "turnId")?;
        if let Some(position) = positions.get(turn_id).copied() {
            // 保留首次出现的位置，但采用最新快照的权威状态和元数据。
            merged[position] = turn.clone();
        } else {
            positions.insert(turn_id.to_owned(), merged.len());
            merged.push(turn.clone());
        }
    }
    Ok(merged)
}

/// @author kongweiguang
/// 只补齐协议允许读取的公开正文，用户引用保持原序且私有 Tool/Reasoning 内容不可寻址。
pub(super) fn hydrate_public_history_item(
    item: &mut Value,
    mut read_content: impl FnMut(&str, u64) -> Result<String, CliError>,
) -> Result<(), CliError> {
    if item.get("truncated").and_then(Value::as_bool) != Some(true) {
        return Ok(());
    }
    let kind = required_str(item, "kind")?;
    if !matches!(
        kind,
        "user_input"
            | "assistant_progress"
            | "final_answer"
            | "reasoning_summary"
            | "thread_message"
    ) {
        return Ok(());
    }
    let item_id = required_str(item, "itemId")?;
    let total_characters = required_u64(item, "totalCharacters")?;
    let text = read_content(item_id, total_characters)?;
    match kind {
        "user_input" => {
            let content = item
                .get_mut("content")
                .and_then(Value::as_array_mut)
                .ok_or_else(|| CliError::protocol("截断的用户输入缺少 content"))?;
            let mut inserted = false;
            content.retain_mut(|part| {
                if part.get("type").and_then(Value::as_str) != Some("text") {
                    return true;
                }
                if inserted {
                    return false;
                }
                part["text"] = Value::String(text.clone());
                inserted = true;
                true
            });
            if !inserted {
                return Err(CliError::protocol("截断的用户输入缺少文本块"));
            }
        }
        "thread_message" => item["content"] = Value::String(text),
        _ => item["text"] = Value::String(text),
    }
    item["truncated"] = Value::Bool(false);
    Ok(())
}

/// 导出失败时只删除由当前操作创建的临时文件，不触碰用户指定目标。
struct TemporaryPath(PathBuf);

impl Drop for TemporaryPath {
    /// 清理失败不覆盖导出主错误或删除用户目标。
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// @author kongweiguang
/// 在固定 Thread revision 下原子收集全部历史页，并拒绝重复游标、条目身份或来源标记。
fn read_export_history(
    connection: &mut Connection,
    thread_id: &str,
) -> Result<(u64, Vec<Value>), CliError> {
    let deadline = Instant::now() + EXPORT_DEADLINE;
    let mut cursor: Option<String> = None;
    let mut seen_cursors = HashSet::new();
    let mut seen_items = HashSet::new();
    let mut pages = Vec::new();
    let mut revision = None;
    for _ in 0..MAX_EXPORT_PAGES {
        let mut params = json!({"threadId":thread_id,"tail":true,"limit":200});
        if let Some(value) = &cursor {
            if !seen_cursors.insert(value.clone()) {
                return Err(CliError::protocol("会话导出游标重复"));
            }
            params["cursor"] = json!(value);
        }
        let page =
            connection.request_with_timeout("thread/read", params, remaining_scan(deadline)?)?;
        validate_history_origins(&page)?;
        let page_revision = required_u64(&page, "revision")?;
        if let Some(expected) = revision {
            if page_revision != expected {
                return Err(CliError::usage(
                    "导出期间会话发生变化；未写入文件，请重新导出",
                ));
            }
        } else {
            revision = Some(page_revision);
        }
        let items = page
            .get("items")
            .and_then(Value::as_array)
            .ok_or_else(|| CliError::protocol("会话导出页缺少 items"))?;
        for item in items {
            let item_id = required_str(item, "itemId")?;
            if !seen_items.insert(item_id.to_owned()) {
                return Err(CliError::protocol("会话导出包含重复历史项"));
            }
        }
        pages.push(items.clone());
        cursor = page
            .get("nextCursor")
            .and_then(Value::as_str)
            .map(str::to_owned);
        if cursor.is_none() {
            let items = pages.into_iter().rev().flatten().collect();
            return Ok((revision.unwrap_or_default(), items));
        }
    }
    Err(CliError::usage("会话历史超过导出页数上限；未写入文件"))
}

/// 生成 Markdown 前补齐公开文本和允许读取的 Tool artifact；内容缺失或超限时拒绝导出。
fn format_export_document(
    connection: &mut Connection,
    thread_id: &str,
    title: &str,
    revision: u64,
    items: &[Value],
) -> Result<String, CliError> {
    let mut document = format!(
        "# {}\n\nThread: `{thread_id}`  \nRevision: `{revision}`\n\n",
        markdown_heading(title)
    );
    let mut total_bytes = document.len();
    for item in items {
        let kind = required_str(item, "kind")?;
        let created_at = required_str(item, "createdAt")?;
        let turn_id = required_str(item, "turnId")?;
        let (heading, body) = match kind {
            "user_input" => {
                let text = if item.get("truncated").and_then(Value::as_bool) == Some(true) {
                    let total = required_u64(item, "totalCharacters")?;
                    if total > MAX_EXPORT_BYTES as u64 {
                        return Err(CliError::usage("单条会话正文超过导出容量；未写入文件"));
                    }
                    read_public_item_content(
                        connection,
                        thread_id,
                        required_str(item, "itemId")?,
                        total,
                        Some(MAX_EXPORT_BYTES),
                    )?
                } else {
                    user_content_text(item)?
                };
                let attachments = item
                    .get("attachments")
                    .and_then(Value::as_array)
                    .into_iter()
                    .flatten()
                    .filter_map(|attachment| attachment.get("displayName").and_then(Value::as_str))
                    .map(|name| format!("- 附件：{name}"))
                    .collect::<Vec<_>>();
                let mut body = text;
                if !attachments.is_empty() {
                    if !body.is_empty() {
                        body.push_str("\n\n");
                    }
                    body.push_str(&attachments.join("\n"));
                }
                ("用户", body)
            }
            "thread_message" | "assistant_progress" | "reasoning_summary" | "final_answer" => {
                let field = if kind == "thread_message" {
                    "content"
                } else {
                    "text"
                };
                let text = if item.get("truncated").and_then(Value::as_bool) == Some(true) {
                    let total = required_u64(item, "totalCharacters")?;
                    if total > MAX_EXPORT_BYTES as u64 {
                        return Err(CliError::usage("单条会话正文超过导出容量；未写入文件"));
                    }
                    read_public_item_content(
                        connection,
                        thread_id,
                        required_str(item, "itemId")?,
                        total,
                        Some(MAX_EXPORT_BYTES),
                    )?
                } else {
                    required_str(item, field)?.to_owned()
                };
                let heading = match kind {
                    "thread_message" => "会话消息",
                    "assistant_progress" => "过程",
                    "reasoning_summary" => "思考摘要",
                    _ => "助手回复",
                };
                (heading, text)
            }
            "tool_call" => {
                let presentation = item
                    .get("presentation")
                    .ok_or_else(|| CliError::protocol("工具历史缺少 presentation"))?;
                let mut body = format!(
                    "工具：{} · {}",
                    required_str(item, "toolName")?,
                    required_str(presentation, "status")?
                );
                for field in ["summary", "command", "stdout", "stderr", "outputPreview"] {
                    if let Some(text) = presentation.get(field).and_then(Value::as_str) {
                        if !text.is_empty() {
                            body.push_str(&format!("\n\n{text}"));
                        }
                    } else if let Some(text) = presentation
                        .get(field)
                        .and_then(|value| value.get("text"))
                        .and_then(Value::as_str)
                        && !text.is_empty()
                    {
                        body.push_str(&format!("\n\n{text}"));
                    }
                }
                if let Some(artifact_id) = presentation.get("artifactId").and_then(Value::as_str) {
                    let artifact = read_tool_artifact(
                        connection,
                        thread_id,
                        turn_id,
                        required_str(item, "callId")?,
                        artifact_id,
                    )?;
                    body.push_str("\n\n完整工具产物：\n\n```text\n");
                    body.push_str(&artifact);
                    body.push_str("\n```");
                }
                ("工具", body)
            }
            "approval" => (
                "审批",
                format!(
                    "工具：{}\n原因：{}\n裁决：{}",
                    required_str(item, "toolName")?,
                    required_str(item, "reason")?,
                    item.get("decision")
                        .and_then(Value::as_str)
                        .unwrap_or("待处理")
                ),
            ),
            _ => return Err(CliError::protocol("会话导出遇到未知 item 类型")),
        };
        let block = format!("## {heading} · {created_at}\n\nTurn: `{turn_id}`\n\n{body}\n\n");
        total_bytes = total_bytes.saturating_add(block.len());
        if total_bytes > MAX_EXPORT_BYTES {
            return Err(CliError::usage("会话导出超过 64 MiB；未创建目标文件"));
        }
        document.push_str(&block);
    }
    Ok(document)
}

/// 按原顺序拼接文本块；非文本引用单独表示为附件摘要。
fn user_content_text(item: &Value) -> Result<String, CliError> {
    let content = item
        .get("content")
        .and_then(Value::as_array)
        .ok_or_else(|| CliError::protocol("用户历史缺少 content"))?;
    content
        .iter()
        .filter(|block| block.get("type").and_then(Value::as_str) == Some("text"))
        .map(|block| required_str(block, "text").map(str::to_owned))
        .collect::<Result<Vec<_>, _>>()
        .map(|parts| parts.join(""))
}

/// @author kongweiguang
/// 读取单个允许访问的 Tool artifact 的全部分页，并校验 artifact identity 与大小上限。
fn read_tool_artifact(
    connection: &mut Connection,
    thread_id: &str,
    turn_id: &str,
    call_id: &str,
    artifact_id: &str,
) -> Result<String, CliError> {
    read_tool_artifact_pages(
        thread_id,
        turn_id,
        call_id,
        artifact_id,
        |params, timeout| connection.request_with_timeout("tool/artifact/read", params, timeout),
    )
}

/// @author kongweiguang
/// 分页校验与连接分离，允许用确定性页面回归身份、游标和大小边界而不启动 App Server。
pub(super) fn read_tool_artifact_pages(
    thread_id: &str,
    turn_id: &str,
    call_id: &str,
    artifact_id: &str,
    mut read_page: impl FnMut(Value, Duration) -> Result<Value, CliError>,
) -> Result<String, CliError> {
    let deadline = Instant::now() + ITEM_DETAIL_DEADLINE;
    let mut offset = 0_u64;
    let mut total = None;
    let mut content = String::new();
    loop {
        let page = read_page(
            json!({
                "threadId":thread_id,
                "turnId":turn_id,
                "callId":call_id,
                "artifactId":artifact_id,
                "offsetCharacters":offset,
                "limitCharacters":ITEM_CONTENT_PAGE_CHARACTERS
            }),
            remaining_scan(deadline)?,
        )?;
        if required_str(&page, "artifactId")? != artifact_id
            || required_u64(&page, "offsetCharacters")? != offset
        {
            return Err(CliError::protocol("工具产物分页身份不一致"));
        }
        let page_total = required_u64(&page, "totalCharacters")?;
        if total.is_some_and(|expected| expected != page_total) {
            return Err(CliError::protocol("工具产物分页总长发生变化"));
        }
        total = Some(page_total);
        if page_total > MAX_EXPORT_ARTIFACT_BYTES as u64 {
            return Err(CliError::usage("工具产物超过 4 MiB；未写入导出文件"));
        }
        let text = required_str(&page, "content")?;
        let next = page.get("nextOffsetCharacters");
        let truncated = page.get("truncated").and_then(Value::as_bool);
        let actual_next = next.and_then(Value::as_u64);
        if truncated != Some(actual_next.is_some())
            || actual_next.is_some_and(|value| {
                value <= offset
                    || value > page_total
                    || text.chars().count() as u64 != value.saturating_sub(offset)
            })
            || (actual_next.is_none()
                && text.chars().count() as u64 != page_total.saturating_sub(offset))
        {
            return Err(CliError::protocol("工具产物分页长度不一致"));
        }
        if content.len().saturating_add(text.len()) > MAX_EXPORT_ARTIFACT_BYTES {
            return Err(CliError::usage("工具产物超过 4 MiB；未写入导出文件"));
        }
        content.push_str(text);
        match actual_next {
            Some(next) => offset = next,
            None => return Ok(content),
        }
    }
}

/// @author kongweiguang
/// 失败时安全预览仍保留，因此标签只说明成功回读后会更新，不能持续声称读取仍在进行。
pub(super) fn tool_detail_body(
    preview: &str,
    truncated: bool,
    has_artifact: bool,
    full_artifact: Option<&str>,
) -> String {
    if let Some(artifact) = full_artifact {
        return format!("工具产物\n\n{artifact}");
    }
    let label = match (truncated, has_artifact) {
        (true, true) => "结果已截断；以下为安全预览，完整产物读取成功后更新。",
        (true, false) => "结果已截断；以下为安全预览，当前没有可读取的完整产物。",
        (false, true) => "以下为安全展示预览，完整产物读取成功后更新。",
        (false, false) => "以下为安全展示信息；当前没有可读取的完整产物。",
    };
    format!("{label}\n\n{preview}")
}

/// 先写入同目录暂存文件并 flush，再原子创建目标，确保不会覆盖已有文件。
fn write_new_file_atomically(target: &Path, content: &[u8]) -> Result<(), CliError> {
    let parent = target
        .parent()
        .filter(|path| !path.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    if !parent.is_dir() {
        return Err(CliError::usage("导出目录不存在"));
    }
    let name = target
        .file_name()
        .ok_or_else(|| CliError::usage("导出目标需要文件名"))?;
    let mut last_error = None;
    for _ in 0..3 {
        let temporary = parent.join(format!(
            ".{}.{}.tmp",
            name.to_string_lossy(),
            uuid::Uuid::new_v4().simple()
        ));
        let cleanup = TemporaryPath(temporary.clone());
        let mut file = match OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)
        {
            Ok(file) => file,
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                last_error = Some(error);
                continue;
            }
            Err(error) => return Err(CliError::transport(error)),
        };
        file.write_all(content).map_err(CliError::transport)?;
        file.sync_all().map_err(CliError::transport)?;
        drop(file);
        match fs::hard_link(&temporary, target) {
            Ok(()) => {
                drop(cleanup);
                return Ok(());
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                return Err(CliError::usage("导出目标已存在；请指定另一个文件名"));
            }
            Err(error) => {
                return Err(CliError::transport(format!(
                    "无法原子创建导出文件：{error}"
                )));
            }
        }
    }
    Err(CliError::transport(last_error.unwrap_or_else(|| {
        std::io::Error::other("无法生成唯一导出暂存文件")
    })))
}

/// 将标题限制在单行，避免特殊 Thread 标题意外生成额外 Markdown 章节。
fn markdown_heading(value: &str) -> String {
    value
        .chars()
        .map(|character| {
            if character.is_control() {
                ' '
            } else {
                character
            }
        })
        .collect::<String>()
        .trim()
        .to_owned()
}

/// 查看器只保留排版所需的换行与 Tab，外部文件内容不能向终端注入控制序列。
fn terminal_safe(value: &str) -> String {
    value
        .chars()
        .filter(|ch| *ch == '\n' || *ch == '\t' || !ch.is_control())
        .collect()
}

/// 明确拒绝保留可修正诊断，未知回执只呈现核实动作；操作键是内部身份，不挤占用户提示。
fn submission_notice(error: &CliError) -> String {
    if error.is_uncertain() || (error.exit_code() == 4 && error.rpc_code.is_none()) {
        "发送结果待核实；按 F5 核实原操作，勿重复发送。".into()
    } else {
        format!("发送失败：{}", error.message())
    }
}

/// 正文分页核验身份与完整总长；详情有容量预算，对话归档不截断，失败仍保留原展示待刷新。
fn read_public_item_content(
    connection: &mut Connection,
    thread_id: &str,
    item_id: &str,
    expected_total: u64,
    maximum_bytes: Option<usize>,
) -> Result<String, CliError> {
    read_item_content_pages(
        thread_id,
        item_id,
        expected_total,
        maximum_bytes,
        |params, timeout| {
            connection.request_with_timeout("thread/item-content/read", params, timeout)
        },
    )
}

/// @author kongweiguang
/// 历史终答展开遵守状态诊断的总 deadline，而不让每个长答案分页重置十五秒预算。
fn read_public_item_content_until(
    connection: &mut Connection,
    thread_id: &str,
    item_id: &str,
    expected_total: u64,
    maximum_bytes: Option<usize>,
    deadline: Instant,
) -> Result<String, CliError> {
    read_item_content_pages_until(
        thread_id,
        item_id,
        expected_total,
        maximum_bytes,
        deadline,
        |params, page_timeout| {
            let timeout = page_timeout.min(remaining_status(deadline)?);
            connection.request_with_timeout("thread/item-content/read", params, timeout)
        },
    )
}

/// 分页只在身份、连续偏移与总长都吻合后返回全文；普通历史详情使用独立短 deadline。
pub(super) fn read_item_content_pages(
    thread_id: &str,
    item_id: &str,
    expected_total: u64,
    maximum_bytes: Option<usize>,
    read_page: impl FnMut(Value, Duration) -> Result<Value, CliError>,
) -> Result<String, CliError> {
    read_item_content_pages_until(
        thread_id,
        item_id,
        expected_total,
        maximum_bytes,
        Instant::now() + ITEM_DETAIL_DEADLINE,
        read_page,
    )
}

/// 使用调用方的绝对 deadline，避免正文分页重置 Thread 导出等上层操作的总耗时预算。
pub(super) fn read_item_content_pages_until(
    thread_id: &str,
    item_id: &str,
    expected_total: u64,
    maximum_bytes: Option<usize>,
    deadline: Instant,
    mut read_page: impl FnMut(Value, Duration) -> Result<Value, CliError>,
) -> Result<String, CliError> {
    if maximum_bytes.is_some_and(|maximum| expected_total > maximum as u64) {
        return Err(CliError::usage(
            "正文超出终端详情容量；对话中仍保留安全预览",
        ));
    }
    let mut offset = 0_u64;
    let mut content = String::new();
    loop {
        let page = read_page(
            json!({
                "threadId":thread_id,
                "itemId":item_id,
                "offsetCharacters":offset,
                "limitCharacters":ITEM_CONTENT_PAGE_CHARACTERS
            }),
            remaining_scan(deadline)?,
        )?;
        if required_str(&page, "itemId")? != item_id
            || required_u64(&page, "offsetCharacters")? != offset
            || required_u64(&page, "totalCharacters")? != expected_total
        {
            return Err(CliError::protocol("历史正文分页身份不一致"));
        }
        let page_text = page
            .get("content")
            .and_then(Value::as_str)
            .ok_or_else(|| CliError::protocol("历史正文分页缺少 content"))?;
        let page_characters =
            u64::try_from(page_text.chars().count()).map_err(CliError::transport)?;
        let end_offset = offset
            .checked_add(page_characters)
            .ok_or_else(|| CliError::protocol("历史正文偏移无效"))?;
        let truncated = page
            .get("truncated")
            .and_then(Value::as_bool)
            .ok_or_else(|| CliError::protocol("历史正文分页缺少 truncated"))?;
        let next_offset = match page.get("nextOffsetCharacters") {
            Some(Value::Null) => None,
            Some(value) => Some(
                value
                    .as_u64()
                    .ok_or_else(|| CliError::protocol("历史正文下一页偏移无效"))?,
            ),
            None => return Err(CliError::protocol("历史正文分页缺少下一页偏移")),
        };
        if (truncated
            && (page_characters == 0
                || next_offset != Some(end_offset)
                || end_offset >= expected_total))
            || (!truncated && (next_offset.is_some() || end_offset != expected_total))
        {
            return Err(CliError::protocol("历史正文分页状态不一致"));
        }
        if maximum_bytes
            .is_some_and(|maximum| content.len().saturating_add(page_text.len()) > maximum)
        {
            return Err(CliError::usage(
                "正文超出终端详情容量；对话中仍保留安全预览",
            ));
        }
        content.push_str(page_text);
        if !truncated {
            return Ok(content);
        }
        offset = end_offset;
    }
}

/// 仅临时读取错误转为可操作提示；致命错误保留原分类，终态读取失败也继续只读核查。
pub(super) fn recover_background_read(
    result: Result<(), CliError>,
    pending: &mut bool,
    next_refresh: &mut Instant,
) -> Result<Option<String>, CliError> {
    match result {
        Ok(()) => Ok(None),
        Err(error) if error.read_retryable => {
            *pending = true;
            *next_refresh = Instant::now() + REFRESH_INTERVAL;
            Ok(Some(format!(
                "后台状态暂未更新，将重新核查；F5 重试。({})",
                error.message()
            )))
        }
        Err(error) => Err(error),
    }
}
