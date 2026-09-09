// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime、Workspace、Turn 与 Approval 的强类型领域命令与结果。

use super::{valid_frozen_turn_id, valid_protocol_id, valid_text_id};

const MAX_SAFE_JSON_INTEGER: u64 = 9_007_199_254_740_991;

/// 领域校验只表达“意图不满足不变量”，稳定 IPC code 与可重试语义由外层映射。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct DomainValidationError;

/// 选择 workspace 的强类型命令输入；客户端只提供 `cwd`，权威 workspace identity 由 Java 返回。
#[derive(Debug, Clone)]
pub struct WorkspaceOpenInput {
    pub cwd: String,
    pub display_name: Option<String>,
    pub trust: String,
}

/// workspace 选择成功后只返回 Java-owned identity 与脱敏投影，config revision 和配置文档均不得跨越 IPC。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeConfigurationStatus {
    pub accepted: bool,
    pub workspace_id: Option<String>,
    pub cwd: String,
    pub display_name: String,
    pub trust: String,
}

/// Rust 安全 ingress 完成后提交给 Java 受管存储的固定命令；源路径与 staging 路径不属于该类型。
#[derive(Debug, Clone)]
pub struct AttachmentImportInput {
    pub ingress_token: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub sha256: String,
}

impl AttachmentImportInput {
    /// 在进入 actor 前验证 Rust-only token、展示名、产品配额与摘要，避免损坏状态扩张文件能力。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        let token = self.ingress_token.as_bytes();
        let digest = self.sha256.as_bytes();
        if token.len() != 32
            || !token.iter().all(u8::is_ascii_hexdigit)
            || self.display_name.is_empty()
            || self.display_name.len() > 1_024
            || self.display_name.chars().any(char::is_control)
            || self.size_bytes > 100 * 1024 * 1024
            || digest.len() != 64
            || !digest.iter().all(u8::is_ascii_hexdigit)
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// 草稿附件删除只接受 Java 签发的不透明 identity，物理存储路径始终由 App Server 持有。
#[derive(Debug, Clone)]
pub struct AttachmentDiscardInput {
    pub attachment_id: String,
}

impl AttachmentDiscardInput {
    /// 限制 opaque identity 的前缀、长度与字符集，禁止借 discard command 构造任意 RPC 参数。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.attachment_id, "att_", 128) {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// 将 WebView 的审批响应限制为业务审批 identity；私有 JSON-RPC request ID 始终留在原生 bridge 内部。
#[derive(Debug, Clone)]
pub struct ApprovalResponseInput {
    pub approval_id: String,
    pub turn_id: String,
    pub decision: String,
    pub expected_thread_revision: u64,
}

impl ApprovalResponseInput {
    /// 在普通客户端请求进入有界 sidecar session 前校验业务审批 identity 与 revision，避免陈旧响应跨会话生效。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !self.approval_id.starts_with("appr_")
            || !valid_text_id(&self.approval_id, 128)
            || !valid_protocol_id(&self.turn_id, "turn_", 101)
            || !matches!(self.decision.as_str(), "approve" | "deny")
            || self.expected_thread_revision > MAX_SAFE_JSON_INTEGER
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}
/// Turn 启动的强类型输入；拒绝未知字段可防止 WebView 夹带可执行文件、环境或 shell 设置。
#[derive(Debug, Clone)]
pub struct TurnStartInput {
    pub thread_id: String,
    pub content: Vec<TurnContentPart>,
    pub deadline_ms: Option<u64>,
}

/// Turn content 用枚举关闭自由 JSON；引用只携带 Java 可复核 identity，不携带文件正文或 Skill 快照。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TurnContentPart {
    Text {
        text: String,
    },
    Attachment {
        attachment_id: String,
    },
    WorkspaceReference {
        workspace_id: String,
        relative_path: String,
        kind: String,
    },
    SkillReference {
        skill_id: String,
    },
}

impl TurnStartInput {
    /// 在进入进程请求队列前校验身份与输入上限；JSON 映射由 infrastructure 固定完成。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.thread_id, "thr_", 100)
            || self.content.is_empty()
            || self.content.len() > 64
            || self
                .deadline_ms
                .is_some_and(|value| !(1_000..=86_400_000).contains(&value))
        {
            return Err(DomainValidationError);
        }
        validate_turn_content(&self.content, 4_000_000)
    }
}

/// 三个消息入口共用同一内容不变量，保证首轮、队列与恢复不会因 Rust 路径不同而分叉。
pub(super) fn validate_turn_content(
    content: &[TurnContentPart],
    max_payload_bytes: usize,
) -> Result<(), DomainValidationError> {
    if content.is_empty() || content.len() > 64 {
        return Err(DomainValidationError);
    }
    let mut total_bytes = 0_usize;
    let mut attachment_ids = std::collections::HashSet::new();
    let mut workspace_references = std::collections::HashSet::new();
    let mut workspace_ids = std::collections::HashSet::new();
    let mut skill_ids = std::collections::HashSet::new();
    let mut phase = 0_u8;
    let mut text_count = 0_usize;
    let mut sendable = false;
    for part in content {
        match part {
            TurnContentPart::Text { text } => {
                phase = 2;
                text_count += 1;
                sendable = true;
                if text_count > 1 || text.is_empty() || text.contains('\0') {
                    return Err(DomainValidationError);
                }
                total_bytes = total_bytes
                    .checked_add(text.len())
                    .ok_or(DomainValidationError)?;
            }
            TurnContentPart::Attachment { attachment_id } => {
                if phase > 1 {
                    return Err(DomainValidationError);
                }
                phase = 1;
                sendable = true;
                if !valid_protocol_id(attachment_id, "att_", 128)
                    || !attachment_ids.insert(attachment_id)
                    || attachment_ids.len() > 10
                {
                    return Err(DomainValidationError);
                }
                total_bytes = total_bytes
                    .checked_add(attachment_id.len())
                    .ok_or(DomainValidationError)?;
            }
            TurnContentPart::WorkspaceReference {
                workspace_id,
                relative_path,
                kind,
            } => {
                if phase != 0
                    || !valid_protocol_id(workspace_id, "ws_", 100)
                    || !valid_relative_reference_path(relative_path)
                    || !matches!(kind.as_str(), "file" | "directory")
                    || !workspace_references.insert((workspace_id.as_str(), relative_path.as_str()))
                {
                    return Err(DomainValidationError);
                }
                workspace_ids.insert(workspace_id);
                if workspace_ids.len() > 1 {
                    return Err(DomainValidationError);
                }
                sendable = true;
                total_bytes = total_bytes
                    .checked_add(workspace_id.len())
                    .and_then(|value| value.checked_add(relative_path.len()))
                    .ok_or(DomainValidationError)?;
            }
            TurnContentPart::SkillReference { skill_id } => {
                if phase != 0
                    || !valid_protocol_id(skill_id, "skill_", 101)
                    || !skill_ids.insert(skill_id)
                {
                    return Err(DomainValidationError);
                }
                total_bytes = total_bytes
                    .checked_add(skill_id.len())
                    .ok_or(DomainValidationError)?;
            }
        }
    }
    if !sendable || total_bytes > max_payload_bytes {
        return Err(DomainValidationError);
    }
    Ok(())
}

/// Workspace 引用只接受协议相对路径；真实 containment 与 reparse 校验仍由 Java owner 完成。
fn valid_relative_reference_path(path: &str) -> bool {
    let bytes = path.as_bytes();
    let has_drive_prefix = bytes.len() >= 2 && bytes[0].is_ascii_alphabetic() && bytes[1] == b':';
    !path.is_empty()
        && path.chars().count() <= 4_096
        && !path.starts_with('/')
        && !has_drive_prefix
        && !path.contains('\\')
        && !path.chars().any(char::is_control)
        && !path.split('/').any(|segment| segment == "..")
}

/// 强类型取消输入保持 host 请求收敛，防止一次 cancel 点击变成通用 sidecar method 或 payload 隧道。
#[derive(Debug, Clone)]
pub struct TurnCancelInput {
    pub turn_id: String,
    pub expected_thread_revision: u64,
}

impl TurnCancelInput {
    /// 校验冻结 Turn identity 与 revision；wire envelope 只由 infrastructure 创建。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_frozen_turn_id(&self.turn_id)
            || self.expected_thread_revision > MAX_SAFE_JSON_INTEGER
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// 恢复输入只携带既有 Turn identity 与 Thread revision CAS；执行游标和运行时指纹始终由 Java 持有。
#[derive(Debug, Clone)]
pub struct TurnResumeInput {
    pub turn_id: String,
    pub expected_thread_revision: u64,
}

impl TurnResumeInput {
    /// 在进入 actor 前拒绝非冻结 Turn identity 与 JavaScript 不安全整数，避免恢复请求成为状态隧道。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_frozen_turn_id(&self.turn_id)
            || self.expected_thread_revision > MAX_SAFE_JSON_INTEGER
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// 只确认 sidecar 的冻结取消结果；完成仍是事件事实，因此该响应不能声称 Turn 已经结束。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnCancelResult {
    pub accepted: bool,
    pub turn_id: String,
    pub status: String,
    pub thread_revision: u64,
}

/// 活动 Turn 新增一条普通后续消息；优先级只能通过独立 prioritize 意图改变。
#[derive(Debug, Clone)]
pub struct TurnInputEnqueue {
    pub turn_id: String,
    pub content: Vec<TurnContentPart>,
}

impl TurnInputEnqueue {
    /// 队列输入复用首轮内容不变量，并施加更小的 512 KiB admission 预算。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_frozen_turn_id(&self.turn_id) {
            return Err(DomainValidationError);
        }
        validate_turn_content(&self.content, 524_288)
    }
}

/// 已存在队列条目的共同 CAS identity；具体命令使用不同 nominal type 防止 method/payload 组合。
#[derive(Debug, Clone)]
pub struct TurnInputPrioritize {
    pub turn_id: String,
    pub input_id: String,
    pub expected_input_revision: u64,
}

impl TurnInputPrioritize {
    /// 校验 Turn/Input 归属标识与 JavaScript-safe CAS，不在 Rust 推断当前队列 revision。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        validate_turn_input_identity(&self.turn_id, &self.input_id, self.expected_input_revision)
    }
}

/// 编辑尚未消费的队列条目；文本与条目 revision 必须在同一命令中提交。
#[derive(Debug, Clone)]
pub struct TurnInputUpdate {
    pub turn_id: String,
    pub input_id: String,
    pub expected_input_revision: u64,
    pub content: Vec<TurnContentPart>,
}

impl TurnInputUpdate {
    /// 编辑沿用结构化队列预算，并通过 item revision 防止覆盖已提升或已消费的条目。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        validate_turn_input_identity(&self.turn_id, &self.input_id, self.expected_input_revision)?;
        validate_turn_content(&self.content, 524_288)
    }
}

/// 删除尚未消费的队列条目；物理删除和消费竞态仅由 Java 持久化 owner 决定。
#[derive(Debug, Clone)]
pub struct TurnInputDelete {
    pub turn_id: String,
    pub input_id: String,
    pub expected_input_revision: u64,
}

impl TurnInputDelete {
    /// 删除只携带冻结 identity 与 item CAS，不接受队列位置或 kind 等可伪造调度字段。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        validate_turn_input_identity(&self.turn_id, &self.input_id, self.expected_input_revision)
    }
}

/// 四个队列 mutation 共用的 identity 校验，避免某一路径放宽 opaque ID 或数值精度。
fn validate_turn_input_identity(
    turn_id: &str,
    input_id: &str,
    expected_input_revision: u64,
) -> Result<(), DomainValidationError> {
    if !valid_frozen_turn_id(turn_id)
        || !valid_protocol_id(input_id, "input_", 128)
        || expected_input_revision == 0
        || expected_input_revision > MAX_SAFE_JSON_INTEGER
    {
        return Err(DomainValidationError);
    }
    Ok(())
}

/// Java 返回的单条权威队列记录；items 数组顺序就是下一步真实消费顺序。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueuedInput {
    pub input_id: String,
    pub turn_id: String,
    pub content: Vec<TurnContentPart>,
    pub attachments: Vec<AttachmentSummary>,
    pub kind: String,
    pub status: String,
    pub issue: Option<QueuedInputIssue>,
    pub input_revision: u64,
    pub created_at: String,
}

/// USER Message 与队列项内联的安全附件摘要，不包含路径、hash、Workspace 或生命周期状态。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentSummary {
    pub attachment_id: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub media_kind: String,
    pub media_type: String,
}

/// 排队引用在消费前失效时使用的最小可恢复问题，不泄漏 Java errorId 或内部路径。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueuedInputIssue {
    pub error_code: String,
    pub message: String,
    pub retryable: bool,
}

/// reload、ACK 与事件均复用的完整队列投影；Rust 只校验，不建立第二个事实 owner。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InputQueue {
    pub turn_id: String,
    pub revision: u64,
    pub accepting: bool,
    pub items: Vec<QueuedInput>,
}

/// Java 完成任一队列 mutation 后返回的严格权威投影，ACK 可直接覆盖旧本地快照。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnInputResult {
    pub accepted: bool,
    pub input_id: String,
    pub input_queue: InputQueue,
}

/// Composer `@` 查询只描述当前 Thread/Workspace 与有界用户输入，扫描预算由 Java 固定。
#[derive(Debug, Clone)]
pub struct WorkspacePathSearchInput {
    pub thread_id: String,
    pub workspace_id: String,
    pub query: String,
    pub limit: Option<u32>,
}

impl WorkspacePathSearchInput {
    /// 在跨进程前限制 identity、控制字符与结果上限；路径 containment 仍由 Java 扫描器负责。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.thread_id, "thr_", 100)
            || !valid_protocol_id(&self.workspace_id, "ws_", 100)
            || self.query.chars().count() > 256
            || self.query.chars().any(char::is_control)
            || self.limit.is_some_and(|limit| !(1..=50).contains(&limit))
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// Workspace path suggestion 只返回相对路径与类型，不携带文件正文、摘要或绝对 root。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspacePathSearchItem {
    pub relative_path: String,
    pub kind: String,
}

/// Path search 响应回显全部竞态栅栏，renderer 可丢弃 Thread/Workspace/generation 的迟到结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspacePathSearchResult {
    pub thread_id: String,
    pub workspace_id: String,
    pub generation: u64,
    pub query: String,
    pub items: Vec<WorkspacePathSearchItem>,
    pub truncated: bool,
}

/// `ja_turn_start` 与 `ja_turn_resume` 共用的 accepted 响应；后续事实只能通过固定事件到达。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnAccepted {
    pub accepted: bool,
    pub turn_id: String,
    pub queued: bool,
    pub thread_revision: u64,
}

/// 冻结 Turn diff artifact 的单文件有界读取输入；workspace 授权由 Host binding 单独校验。
#[derive(Debug, Clone)]
pub struct TurnChangeSetReadInput {
    pub thread_id: String,
    pub turn_id: String,
    pub artifact_id: String,
    pub file_path: String,
}

impl TurnChangeSetReadInput {
    /// 将读取绑定到 artifact 内的安全相对路径，避免按文件优化退化成任意路径探测。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.thread_id, "thr_", 100)
            || !valid_protocol_id(&self.turn_id, "turn_", 101)
            || !valid_protocol_id(&self.artifact_id, "artifact_", 128)
            || !valid_relative_reference_path(&self.file_path)
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// Java 持久化 Turn diff 的完整单文件文本；长度与摘要已由原生边界复核。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnChangeSetReadResult {
    pub artifact_id: String,
    pub file_path: String,
    pub byte_length: u64,
    pub sha256: String,
    pub content: String,
}

/// Tool 输出 artifact 的有界 code-point 分页输入，四元 identity 阻止跨 Turn/Call 枚举。
#[derive(Debug, Clone)]
pub struct ToolArtifactReadInput {
    pub thread_id: String,
    pub turn_id: String,
    pub call_id: String,
    pub artifact_id: String,
    pub offset_characters: u64,
    pub limit_characters: u64,
}

impl ToolArtifactReadInput {
    /// 校验固定协议 identity 与字符窗口，完整关联授权仍由 Java 持久化 owner 执行。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.thread_id, "thr_", 100)
            || !valid_protocol_id(&self.turn_id, "turn_", 101)
            || !valid_protocol_id(&self.call_id, "call_", 101)
            || !valid_protocol_id(&self.artifact_id, "artifact_", 128)
            || self.offset_characters > MAX_SAFE_JSON_INTEGER
            || !(1..=65_536).contains(&self.limit_characters)
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// Java 持久化 Tool 输出的单页安全文本；总长度按 Unicode code point 计数。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolArtifactReadResult {
    pub artifact_id: String,
    pub offset_characters: u64,
    pub next_offset_characters: Option<u64>,
    pub total_characters: u64,
    pub truncated: bool,
    pub content: String,
}
