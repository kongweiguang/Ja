// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use ja_runtime::app_server_process::{ThreadContextUsageDto, ThreadUsageSummary};
use std::collections::HashMap;
use std::path::PathBuf;
use std::time::SystemTime;

/// Render-only marker carried in `TimelineEntry::detail`; source text and selection copying stay intact.
pub const MESSAGE_PREVIEW_TRUNCATED_NOTICE: &str = "正文未完整加载，F2 查看全文";

/// 对话行使用稳定 ID，流式增量才能只更新对应内容。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TimelineEntry {
    pub id: String,
    pub kind: TimelineKind,
    pub text: String,
    pub detail: Option<String>,
    pub status: Option<TimelineStatus>,
}

/// 视图层只接收少量展示类型，避免泄漏 JA-RPC 内部命名。
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum TimelineKind {
    User,
    Assistant,
    FinalAnswer,
    Commentary,
    ReasoningSummary,
    /// 由现有 Turn 起止时间投影，只展示用时，不伪造成助手正文。
    WorkSummary {
        elapsed_seconds: u64,
    },
    AssistantFragment {
        first: bool,
        presentation: StreamPresentation,
        /// 归档仍保留摘要语义，不能把弱化的思考段变成普通回答。
        reasoning: bool,
    },
    Tool {
        action: String,
        target: String,
        /// 沿用服务端截断事实，让短预览也能说明完整结果仍需显式读取。
        preview_truncated: bool,
    },
}

impl TimelineKind {
    /// 初始预览和后台详情共用语义标题，正文首行不能在回包后取代标题并重复显示。
    /// @author kongweiguang
    pub fn message_detail_title(&self) -> &'static str {
        match self {
            Self::User => "你的消息 · 完整正文",
            Self::Assistant | Self::FinalAnswer => "Ja 回复 · 完整正文",
            Self::Commentary => "回复过程 · 完整正文",
            Self::ReasoningSummary => "思考摘要 · 完整正文",
            Self::WorkSummary { .. } | Self::AssistantFragment { .. } | Self::Tool { .. } => {
                "消息正文"
            }
        }
    }
}

/// 稳定区块与代码行共用正文渲染；代码标签独立于正文以保持流式和历史外观一致。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum StreamPresentation {
    Markdown,
    CodeLabel,
    Code,
    Literal,
    Spacer,
}

/// 工具状态仅由 controller 映射真实通知后展示。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TimelineStatus {
    Running,
    Complete,
    Failed,
}

/// 只有仍可中断的 Turn 显示“正在工作”；显式暂停状态保留可恢复性但不伪装为活动执行。
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum TurnState {
    #[default]
    Idle,
    Working,
    Paused,
    Failed,
}

/// 选项 ID 保留 owner 的权威身份，界面只负责展示标签和次要说明。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UiChoice {
    pub id: String,
    pub label: String,
    pub detail: Option<String>,
}

/// 待处理请求连同版本条件传递，避免 UI 自行猜测批准或澄清上下文。
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PendingPrompt {
    ToolApproval {
        approval_id: String,
        thread_id: String,
        turn_id: String,
        expected_thread_revision: u64,
        prompt: String,
        choices: Vec<UiChoice>,
    },
    Clarification {
        thread_id: String,
        interaction_id: String,
        expected_interaction_revision: u64,
        operation_key: String,
        questions: Vec<InteractionQuestion>,
    },
}

/// 每道澄清题保留服务端题目类型，由此决定键盘输入和选项行为。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InteractionQuestion {
    pub question_id: String,
    pub prompt: String,
    pub kind: InteractionQuestionKind,
    pub options: Vec<UiChoice>,
    pub allow_skip: bool,
    pub allow_free_text: bool,
}

/// 单选、多选和文本题采用独立的键盘闭环，不把用户输入伪装成预设答案。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum InteractionQuestionKind {
    Single,
    Multiple,
    Text,
}

/// 最终答案保留选项 ID、自由文本和显式跳过事实。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct InteractionAnswer {
    pub question_id: String,
    pub option_ids: Vec<String>,
    pub free_text: Option<String>,
    pub skipped: bool,
}

/// 初始化快照由 controller 从权威读取结果组装，UI 不读取配置或会话存储。
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct UiSnapshot {
    pub project_label: Option<String>,
    pub workspace_path: Option<String>,
    pub thread_id: Option<String>,
    pub thread_title: Option<String>,
    pub model_identifier: Option<String>,
    /// 用量事实由 Java 读取，CLI 只携带当前 Thread 的权威摘要和最近上下文请求。
    pub usage: Option<UiUsageSnapshot>,
    pub current_model_key: Option<String>,
    pub reasoning_label: Option<String>,
    pub provider_name: Option<String>,
    pub permission_label: Option<String>,
    pub turn_state: TurnState,
    /// 当前 Thread 的快照采集或 Review Turn 显示取消入口，不改变普通消息提交语义。
    pub local_operation: Option<String>,
    /// 使用后台受理时间，刷新、恢复和终端重开不会重新开始计时。
    pub turn_started_at: Option<SystemTime>,
    pub continuation_available: bool,
    pub has_older_history: bool,
    pub timeline: Vec<TimelineEntry>,
    pub turn_id_by_entry_id: HashMap<String, String>,
    pub attachments: Vec<UiChoice>,
    pub pending_prompt: Option<PendingPrompt>,
    pub model_choices: Vec<UiChoice>,
    pub reasoning_choices: Vec<UiChoice>,
    pub reasoning_model_identifier: Option<String>,
    pub permission_choices: Vec<UiChoice>,
    pub thread_choices: Vec<UiChoice>,
    pub thread_next_cursor: Option<String>,
    /// 回复选择器仅携带当前已加载历史页；翻页由 controller 延迟请求。
    pub fork_reply_choices: Vec<UiChoice>,
    pub fork_reply_next_cursor: Option<String>,
    /// Fork picker 在候选页尚未回到 UI 前显示加载态，避免把空缓存误报为无历史。
    pub fork_reply_loading: bool,
    /// 首次候选读取失败时保留可恢复错误提示，不把失败伪装成已加载的空历史。
    pub fork_reply_load_failed: bool,
    pub input_history_choices: Vec<UiChoice>,
    pub input_history_next_cursor: Option<String>,
    pub file_choices: Vec<UiChoice>,
    pub skill_choices: Vec<UiChoice>,
    pub notice: Option<String>,
}

/// 将累计账本与最近请求上下文并列传递；摘要读取失败只标 stale，不污染对话状态。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UiUsageSnapshot {
    pub summary: Option<ThreadUsageSummary>,
    pub stale: bool,
    pub context: Option<ThreadContextUsageDto>,
}

impl UiUsageSnapshot {
    /// 总量与输出必须覆盖同一组已计量请求，才能相减得到包含缓存的输入；缺项不借缓存子样本补齐。
    pub fn cumulative_input_tokens(&self) -> Option<u64> {
        let summary = self.summary.as_ref()?;
        if summary.measured_request_count == 0
            || summary.total_request_count != summary.measured_request_count
            || summary.output_request_count != summary.measured_request_count
        {
            return None;
        }
        summary.total_tokens.checked_sub(summary.output_tokens)
    }
}

/// 斜杠命令只是用户意图，controller 负责确认能力并映射到真实操作。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum UiCommand {
    NewThread,
    ResumeThread,
    SelectModel,
    SelectPermissions,
    Attach,
    ShowTools,
    ShowDiff,
    TogglePlanMode,
    CreatePlan,
    ShowPlan,
    ExecutePlan,
    PausePlan,
    ResumePlan,
    StopPlan,
    CompactThread,
    ShowStatus,
    ShowSkills,
    ShowMcp,
    ReviewThread,
    ForkThread,
    CopyLastReply,
    ExportThread,
    Help,
    Quit,
}

/// 引用种类携带 Java owner 的稳定身份；用户可见文本不承担协议身份。
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum UiReferenceKind {
    Workspace {
        workspace_id: String,
        relative_path: String,
        kind: String,
    },
    Skill {
        skill_id: String,
    },
}

/// 引用身份绑定编辑区的确切字素区间，编辑重排后不能靠字符串前缀重新猜测目标。
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct UiReference {
    pub id: String,
    pub start: usize,
    pub end: usize,
    pub kind: UiReferenceKind,
}

/// 用户按键决定活动 Turn 的下一安全点如何消费，不由 controller 猜测键盘意图。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum UiSubmitMode {
    Immediate,
    Steer,
    Queue,
}

/// 终端界面输出用户动作，不代表服务端已接受或执行该动作。
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum UiAction {
    Submit {
        text: String,
        references: Vec<UiReference>,
        mode: UiSubmitMode,
    },
    CancelTurn,
    CancelLocalOperation,
    ResumeSuspendedTurn {
        thread_id: String,
    },
    CancelSuspendedTurn {
        thread_id: String,
    },
    ContinueReply,
    /// 操作绑定 Thread identity，避免切换会话后排队的滚动动作读取另一会话。
    LoadOlder {
        thread_id: String,
    },
    /// 离开正文浏览取消未完成读取，不让迟到历史改变新的阅读面。
    CancelOlderHistory,
    /// @author kongweiguang
    /// 分叉候选分页独立于正文历史游标，避免 picker 翻页误推进 transcript 阅读位置。
    LoadMoreForkReplies {
        thread_id: String,
    },
    /// 选区正文只经进程内事件和原生剪贴板传递，不放入命令参数。
    CopyTranscriptSelection {
        selection_id: u64,
        text: String,
    },
    RefreshSnapshot,
    Command(UiCommand),
    CreatePlan {
        objective: String,
    },
    EditDraft {
        draft: String,
    },
    ExportThread {
        path: Option<PathBuf>,
    },
    SelectModel {
        id: String,
    },
    /// 打开共享全局供应商/模型管理器，同时保留当前 Composer 状态。
    OpenModelSettings,
    /// 转发模型设置面板生成的配置请求。
    ModelSettings(super::model_settings::ModelSettingsRequest),
    /// 关闭设置覆盖层，并可在保存完成后开始新对话。
    CloseModelSettings {
        start_conversation: bool,
    },
    /// Workspace 信任由 controller 修改，UI 只提交用户明确选择。
    ConfirmModelSettingsWorkspaceTrust {
        trusted: bool,
    },
    SelectReasoning {
        id: String,
    },
    SelectPermission {
        id: String,
    },
    SelectThread {
        id: String,
    },
    PreviewAttachment {
        id: String,
    },
    RemoveAttachment {
        id: String,
    },
    SearchFiles {
        query: String,
        thread_id: Option<String>,
        query_id: u64,
    },
    SearchSkills {
        query: String,
        thread_id: Option<String>,
        query_id: u64,
    },
    SearchThreads {
        query: String,
        cursor: Option<String>,
    },
    SearchInputHistory {
        query: String,
        cursor: Option<String>,
    },
    /// 关闭选择器后失效正在读取的查询，保留实际草稿。
    CancelInputHistorySearch,
    CancelTranscriptSelection {
        selection_id: u64,
    },
    /// Fork 锚点使用服务端 item identity，UI 不推导 Turn 边界或上下文。
    ForkReply {
        item_id: String,
    },
    SelectFileReference {
        id: String,
    },
    SelectSkillReference {
        id: String,
    },
    AttachPaths {
        paths: Vec<PathBuf>,
    },
    RespondToolApproval {
        approval_id: String,
        thread_id: String,
        turn_id: String,
        expected_thread_revision: u64,
        choice_id: String,
    },
    RespondInteraction {
        thread_id: String,
        interaction_id: String,
        expected_interaction_revision: u64,
        operation_key: String,
        answers: Vec<InteractionAnswer>,
    },
    OpenDetails {
        entry_id: String,
    },
    CloseDetails {
        entry_id: String,
    },
    /// 仅完整消息详情允许复制，预览不能借此冒充全文。
    CopyDetails {
        entry_id: String,
        text: String,
    },
    Quit,
}

/// 控制器将已验证的会话事实投影为事件；增量只更新已知行，不创建虚构状态。
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum UiEvent {
    ReplaceSnapshot(Box<UiSnapshot>),
    /// 用量包含冻结画像，堆分配可让高频文本事件不为这块稀疏更新承担队列内存。
    SetUsage {
        thread_id: String,
        usage: Option<Box<UiUsageSnapshot>>,
    },
    UpsertEntry(TimelineEntry),
    AppendStreamDelta {
        entry_id: String,
        delta: String,
    },
    FinishAssistantStream {
        turn_id: String,
    },
    SetTurnState(TurnState),
    SetLocalOperation(Option<String>),
    SetSuspendedControlPending(bool),
    CancellationFailed(String),
    SetContinuationAvailable(bool),
    SetHasOlderHistory(bool),
    /// UI 前插一页更早正文时使用稳定锚点保持当前阅读位置。
    OlderHistoryLoaded {
        thread_id: String,
        entries: Vec<TimelineEntry>,
        has_older: bool,
    },
    /// 历史读取失败保留当前正文，并提示用户用 F5 重试。
    OlderHistoryFailed {
        thread_id: String,
        notice: String,
    },
    /// 仅在宿主确认写入剪贴板后清除选区。
    TranscriptSelectionCopied {
        selection_id: u64,
    },
    /// 剪贴板写入失败时保留选区并返回诊断。
    TranscriptSelectionCopyFailed {
        selection_id: u64,
        notice: String,
    },
    SetModel {
        model_identifier: Option<String>,
        provider_name: Option<String>,
    },
    SetPermission(Option<String>),
    SetPendingPrompt(Option<PendingPrompt>),
    SetModelChoices(Vec<UiChoice>),
    /// 打开共享模型设置面板；operation ID 属于已经发出的读取请求。
    OpenModelSettings {
        operation_id: String,
        standalone: bool,
    },
    /// 只有匹配当前 pending 操作的结果才能更新设置草稿。
    ModelSettingsResult(super::model_settings::ModelSettingsResult),
    /// 未信任工作区须先让用户明确选择是否授权创建新对话。
    ModelSettingsTrustRequired {
        cwd: String,
    },
    SetReasoningChoices {
        model_identifier: String,
        choices: Vec<UiChoice>,
        selected_id: Option<String>,
    },
    SetPermissionChoices(Vec<UiChoice>),
    SetThreadChoices(Vec<UiChoice>),
    SetThreadPage {
        query: String,
        choices: Vec<UiChoice>,
        next_cursor: Option<String>,
        append: bool,
    },
    /// 只投影 controller 已加载的历史页，游标为空表示没有更早记录。
    SetForkReplyPage {
        choices: Vec<UiChoice>,
        next_cursor: Option<String>,
        selected_id: Option<String>,
        /// 更早页插到候选前面，同时保持当前选中 Item identity。
        prepend: bool,
    },
    /// Fork 候选首次读取失败，结束 loading 并保留失败态直到用户重开选择器。
    ForkReplyLoadFailed,
    SetInputHistoryPage {
        query: String,
        items: Vec<(UiChoice, String, bool)>,
        next_cursor: Option<String>,
        append: bool,
    },
    SetFileChoices {
        thread_id: Option<String>,
        query_id: u64,
        choices: Vec<UiChoice>,
    },
    SetSkillChoices {
        thread_id: Option<String>,
        query_id: u64,
        choices: Vec<UiChoice>,
    },
    SetAttachments(Vec<UiChoice>),
    OpenThreadChooser,
    InsertFileReference {
        workspace_id: String,
        relative_path: String,
        kind: String,
    },
    InsertSkillReference {
        skill_id: String,
        name: String,
    },
    BeginSubmission(String),
    ClearDraft,
    RestoreDraft(String),
    /// 取消或失败时精确恢复进入 `/fork` 前的 Composer，包括字素光标与引用。
    RestoreForkDraft,
    /// Fork 已成功打开后丢弃隐藏的来源命令草稿。
    ClearForkDraft,
    ReplaceDraft(String),
    ReleaseDraft,
    ReleasePromptSubmission,
    ShowDetails {
        entry_id: String,
        title: String,
        body: String,
    },
    /// 异步刷新只替换仍打开的同一详情，关闭或切页后的迟到回包不能重新夺回焦点。
    UpdateDetailsIfOpen {
        entry_id: String,
        title: String,
        body: String,
    },
    /// 完整读取确认后才开放复制，关闭面板后的迟到状态不得重新启用。
    SetDetailsCopyReady {
        entry_id: String,
        ready: bool,
    },
    SetNotice(Option<String>),
    Shutdown,
}
