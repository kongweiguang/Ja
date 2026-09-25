// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use std::path::PathBuf;

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
    Tool { action: String, target: String },
}

/// 工具状态仅由 controller 映射真实通知后展示。
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum TimelineStatus {
    Running,
    Complete,
    Failed,
}

/// 进行中的用户文案统一为“正在工作”，不暴露内部流阶段。
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum TurnState {
    #[default]
    Idle,
    Working,
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
        request_id: String,
        expected_revision: u64,
        idempotency_key: String,
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
    pub current_model_key: Option<String>,
    pub reasoning_label: Option<String>,
    pub provider_name: Option<String>,
    pub permission_label: Option<String>,
    pub turn_state: TurnState,
    pub continuation_available: bool,
    pub has_older_history: bool,
    pub timeline: Vec<TimelineEntry>,
    pub attachments: Vec<UiChoice>,
    pub pending_prompt: Option<PendingPrompt>,
    pub model_choices: Vec<UiChoice>,
    pub reasoning_choices: Vec<UiChoice>,
    pub reasoning_model_identifier: Option<String>,
    pub permission_choices: Vec<UiChoice>,
    pub thread_choices: Vec<UiChoice>,
    pub thread_next_cursor: Option<String>,
    pub input_history_choices: Vec<UiChoice>,
    pub input_history_next_cursor: Option<String>,
    pub file_choices: Vec<UiChoice>,
    pub skill_choices: Vec<UiChoice>,
    pub notice: Option<String>,
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
    ShowPlan,
    ShowGoal,
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
    ContinueReply,
    LoadOlder,
    RefreshSnapshot,
    Command(UiCommand),
    SelectModel {
        id: String,
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
        request_id: String,
        expected_revision: u64,
        idempotency_key: String,
        answers: Vec<InteractionAnswer>,
    },
    OpenDetails {
        entry_id: String,
    },
    Quit,
}

/// 控制器将已验证的会话事实投影为事件；增量只更新已知行，不创建虚构状态。
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum UiEvent {
    ReplaceSnapshot(Box<UiSnapshot>),
    UpsertEntry(TimelineEntry),
    AppendAssistantDelta {
        entry_id: String,
        delta: String,
    },
    SetTurnState(TurnState),
    SetContinuationAvailable(bool),
    SetHasOlderHistory(bool),
    SetModel {
        model_identifier: Option<String>,
        provider_name: Option<String>,
    },
    SetPermission(Option<String>),
    SetPendingPrompt(Option<PendingPrompt>),
    SetModelChoices(Vec<UiChoice>),
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
    ClearDraft,
    RestoreDraft(String),
    ReleaseDraft,
    ReleasePromptSubmission,
    ShowDetails {
        entry_id: String,
        title: String,
        body: String,
    },
    SetNotice(Option<String>),
    Shutdown,
}
