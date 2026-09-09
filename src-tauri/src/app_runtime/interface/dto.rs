// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Tauri command DTO 与纯领域模型的显式映射。

use crate::app_runtime::domain;
use serde::{Deserialize, Serialize};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceOpenInputDto {
    pub cwd: String,
    pub display_name: Option<String>,
    pub trust: String,
}
impl From<WorkspaceOpenInputDto> for domain::WorkspaceOpenInput {
    /// interface 只复制已反序列化字段，路径与 trust 不变量仍由原生 platform 校验。
    fn from(value: WorkspaceOpenInputDto) -> Self {
        Self {
            cwd: value.cwd,
            display_name: value.display_name,
            trust: value.trust,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ApprovalResponseInputDto {
    pub approval_id: String,
    pub turn_id: String,
    pub decision: String,
    pub expected_thread_revision: u64,
}
impl From<ApprovalResponseInputDto> for domain::ApprovalResponseInput {
    /// 审批 DTO 不解释 decision；domain 与 infrastructure 共同执行闭集和 pending identity 校验。
    fn from(value: ApprovalResponseInputDto) -> Self {
        Self {
            approval_id: value.approval_id,
            turn_id: value.turn_id,
            decision: value.decision,
            expected_thread_revision: value.expected_thread_revision,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnStartInputDto {
    pub thread_id: String,
    pub content: Vec<TurnContentPartDto>,
    #[serde(default)]
    pub deadline_ms: Option<u64>,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields, tag = "type", rename_all = "snake_case")]
pub enum TurnContentPartDto {
    Text {
        text: String,
    },
    Attachment {
        #[serde(rename = "attachmentId")]
        attachment_id: String,
    },
    WorkspaceReference {
        #[serde(rename = "workspaceId")]
        workspace_id: String,
        #[serde(rename = "relativePath")]
        relative_path: String,
        kind: String,
    },
    SkillReference {
        #[serde(rename = "skillId")]
        skill_id: String,
    },
}
impl From<TurnStartInputDto> for domain::TurnStartInput {
    /// Turn parts 显式逐项映射，未知字段已由 serde 拒绝，内容上限由 domain 校验。
    fn from(value: TurnStartInputDto) -> Self {
        Self {
            thread_id: value.thread_id,
            content: value
                .content
                .into_iter()
                .map(|part| match part {
                    TurnContentPartDto::Text { text } => domain::TurnContentPart::Text { text },
                    TurnContentPartDto::Attachment { attachment_id } => {
                        domain::TurnContentPart::Attachment { attachment_id }
                    }
                    TurnContentPartDto::WorkspaceReference {
                        workspace_id,
                        relative_path,
                        kind,
                    } => domain::TurnContentPart::WorkspaceReference {
                        workspace_id,
                        relative_path,
                        kind,
                    },
                    TurnContentPartDto::SkillReference { skill_id } => {
                        domain::TurnContentPart::SkillReference { skill_id }
                    }
                })
                .collect(),
            deadline_ms: value.deadline_ms,
        }
    }
}

impl From<domain::TurnContentPart> for TurnContentPartDto {
    /// History 与 queue 复用同一显式映射，避免输出端重新发明不兼容的引用字段。
    fn from(value: domain::TurnContentPart) -> Self {
        match value {
            domain::TurnContentPart::Text { text } => Self::Text { text },
            domain::TurnContentPart::Attachment { attachment_id } => {
                Self::Attachment { attachment_id }
            }
            domain::TurnContentPart::WorkspaceReference {
                workspace_id,
                relative_path,
                kind,
            } => Self::WorkspaceReference {
                workspace_id,
                relative_path,
                kind,
            },
            domain::TurnContentPart::SkillReference { skill_id } => {
                Self::SkillReference { skill_id }
            }
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnCancelInputDto {
    pub turn_id: String,
    pub expected_thread_revision: u64,
}
impl From<TurnCancelInputDto> for domain::TurnCancelInput {
    /// 取消 DTO 只映射业务 identity 与 CAS revision，不恢复已删除的 reason/thread 字段。
    fn from(value: TurnCancelInputDto) -> Self {
        Self {
            turn_id: value.turn_id,
            expected_thread_revision: value.expected_thread_revision,
        }
    }
}

/// Resume DTO 与 JA-RPC 参数保持同一最小 CAS 形状，不允许 WebView 提交执行游标或运行时指纹。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnResumeInputDto {
    pub turn_id: String,
    pub expected_thread_revision: u64,
}

impl From<TurnResumeInputDto> for domain::TurnResumeInput {
    /// DTO 只传递 opaque identity 与 CAS；可恢复性、顺序和指纹均由 Java 原子裁决。
    fn from(value: TurnResumeInputDto) -> Self {
        Self {
            turn_id: value.turn_id,
            expected_thread_revision: value.expected_thread_revision,
        }
    }
}

/// 普通后续消息入队 DTO 不暴露 kind 或 priority，避免 renderer 绕过独立提升命令。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnInputEnqueueDto {
    pub turn_id: String,
    pub content: Vec<TurnContentPartDto>,
}
impl From<TurnInputEnqueueDto> for domain::TurnInputEnqueue {
    /// 入队 DTO 传递统一内容块；初始 follow-up 类型仍由 Java 合同固定。
    fn from(value: TurnInputEnqueueDto) -> Self {
        Self {
            turn_id: value.turn_id,
            content: value
                .content
                .into_iter()
                .map(|part| match part {
                    TurnContentPartDto::Text { text } => domain::TurnContentPart::Text { text },
                    TurnContentPartDto::Attachment { attachment_id } => {
                        domain::TurnContentPart::Attachment { attachment_id }
                    }
                    TurnContentPartDto::WorkspaceReference {
                        workspace_id,
                        relative_path,
                        kind,
                    } => domain::TurnContentPart::WorkspaceReference {
                        workspace_id,
                        relative_path,
                        kind,
                    },
                    TurnContentPartDto::SkillReference { skill_id } => {
                        domain::TurnContentPart::SkillReference { skill_id }
                    }
                })
                .collect(),
        }
    }
}

/// 提升命令只携带条目 CAS identity，点击顺序由 Java 在事务中分配。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnInputPrioritizeDto {
    pub turn_id: String,
    pub input_id: String,
    pub expected_input_revision: u64,
}

impl From<TurnInputPrioritizeDto> for domain::TurnInputPrioritize {
    /// 显式字段映射避免后续 DTO 扩展无审核进入领域命令。
    fn from(value: TurnInputPrioritizeDto) -> Self {
        Self {
            turn_id: value.turn_id,
            input_id: value.input_id,
            expected_input_revision: value.expected_input_revision,
        }
    }
}

/// 编辑命令把新文本和条目 CAS 绑定，杜绝读取后无条件覆盖。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnInputUpdateDto {
    pub turn_id: String,
    pub input_id: String,
    pub expected_input_revision: u64,
    pub content: Vec<TurnContentPartDto>,
}

impl From<TurnInputUpdateDto> for domain::TurnInputUpdate {
    /// 保持 update 的 nominal 类型，不复用可被错误路由到 delete 的通用 mutation DTO。
    fn from(value: TurnInputUpdateDto) -> Self {
        Self {
            turn_id: value.turn_id,
            input_id: value.input_id,
            expected_input_revision: value.expected_input_revision,
            content: value
                .content
                .into_iter()
                .map(|part| match part {
                    TurnContentPartDto::Text { text } => domain::TurnContentPart::Text { text },
                    TurnContentPartDto::Attachment { attachment_id } => {
                        domain::TurnContentPart::Attachment { attachment_id }
                    }
                    TurnContentPartDto::WorkspaceReference {
                        workspace_id,
                        relative_path,
                        kind,
                    } => domain::TurnContentPart::WorkspaceReference {
                        workspace_id,
                        relative_path,
                        kind,
                    },
                    TurnContentPartDto::SkillReference { skill_id } => {
                        domain::TurnContentPart::SkillReference { skill_id }
                    }
                })
                .collect(),
        }
    }
}

/// 删除命令只接受未消费条目的身份和 revision，不接受位置或队列 revision。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnInputDeleteDto {
    pub turn_id: String,
    pub input_id: String,
    pub expected_input_revision: u64,
}

impl From<TurnInputDeleteDto> for domain::TurnInputDelete {
    /// 删除 intent 保持最小字段集，消费竞态由 Java 返回权威错误与队列快照。
    fn from(value: TurnInputDeleteDto) -> Self {
        Self {
            turn_id: value.turn_id,
            input_id: value.input_id,
            expected_input_revision: value.expected_input_revision,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
pub enum ManualRecoveryReasonDto {
    SystemRestarted,
    ExternallyCleaned,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ManualRecoveryConfirmationDto {
    pub recovery_id: String,
    pub revision: u64,
    pub reason: ManualRecoveryReasonDto,
}
impl From<ManualRecoveryConfirmationDto> for domain::ManualRecoveryConfirmation {
    /// 恢复原因按封闭枚举映射，interface 不接受自由文本或路径。
    fn from(value: ManualRecoveryConfirmationDto) -> Self {
        Self {
            recovery_id: value.recovery_id,
            revision: value.revision,
            reason: match value.reason {
                ManualRecoveryReasonDto::SystemRestarted => {
                    domain::ManualRecoveryReason::SystemRestarted
                }
                ManualRecoveryReasonDto::ExternallyCleaned => {
                    domain::ManualRecoveryReason::ExternallyCleaned
                }
            },
        }
    }
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RuntimeStatusKindDto {
    Starting,
    Ready,
    Busy,
    Stopping,
    Stopped,
    RecoveryRequired,
    Crashed,
    Incompatible,
    Faulted,
}

impl PartialEq<domain::RuntimeStatusKind> for RuntimeStatusKindDto {
    /// Interface 合同测试可比较领域状态，但两层仍由显式闭集映射而非共享 serde 类型。
    fn eq(&self, other: &domain::RuntimeStatusKind) -> bool {
        matches!(
            (self, other),
            (Self::Starting, domain::RuntimeStatusKind::Starting)
                | (Self::Ready, domain::RuntimeStatusKind::Ready)
                | (Self::Busy, domain::RuntimeStatusKind::Busy)
                | (Self::Stopping, domain::RuntimeStatusKind::Stopping)
                | (Self::Stopped, domain::RuntimeStatusKind::Stopped)
                | (
                    Self::RecoveryRequired,
                    domain::RuntimeStatusKind::RecoveryRequired
                )
                | (Self::Crashed, domain::RuntimeStatusKind::Crashed)
                | (Self::Incompatible, domain::RuntimeStatusKind::Incompatible)
                | (Self::Faulted, domain::RuntimeStatusKind::Faulted)
        )
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeStatusDto {
    pub status: RuntimeStatusKindDto,
    pub generation: u64,
    pub server_instance_id: Option<String>,
    pub features: Vec<String>,
}
impl From<domain::RuntimeStatus> for RuntimeStatusDto {
    /// 生命周期枚举映射为稳定 renderer 拼写，Busy 保持独立 UI 状态。
    fn from(value: domain::RuntimeStatus) -> Self {
        Self {
            status: match value.status {
                domain::RuntimeStatusKind::Starting => RuntimeStatusKindDto::Starting,
                domain::RuntimeStatusKind::Ready => RuntimeStatusKindDto::Ready,
                domain::RuntimeStatusKind::Busy => RuntimeStatusKindDto::Busy,
                domain::RuntimeStatusKind::Stopping => RuntimeStatusKindDto::Stopping,
                domain::RuntimeStatusKind::Stopped => RuntimeStatusKindDto::Stopped,
                domain::RuntimeStatusKind::RecoveryRequired => {
                    RuntimeStatusKindDto::RecoveryRequired
                }
                domain::RuntimeStatusKind::Crashed => RuntimeStatusKindDto::Crashed,
                domain::RuntimeStatusKind::Incompatible => RuntimeStatusKindDto::Incompatible,
                domain::RuntimeStatusKind::Faulted => RuntimeStatusKindDto::Faulted,
            },
            generation: value.generation,
            server_instance_id: value.server_instance_id,
            features: vec!["task_threads_v1".to_owned(), "plan_goal_v1".to_owned()],
        }
    }
}

/// Runtime 存储界面只投影脱敏目录事实，不暴露启动参数或环境来源。
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeStorageInfoDto {
    pub native_image: bool,
    pub data_path: String,
    pub log_path: Option<String>,
    pub cache_path: Option<String>,
    pub last_backup: Option<String>,
}

impl From<domain::RuntimeStorageInfo> for RuntimeStorageInfoDto {
    /// 显式枚举 renderer 允许的存储字段，避免 domain 新增诊断后被自动带入 wire 合同。
    fn from(value: domain::RuntimeStorageInfo) -> Self {
        Self {
            native_image: value.native_image,
            data_path: value.data_path,
            log_path: value.log_path,
            cache_path: value.cache_path,
            last_backup: value.last_backup,
        }
    }
}

/// Java-owned 通用 Workspace 的 Tauri 投影；路径仅用于展示，不是原生 capability。
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GeneralWorkspaceDto {
    pub workspace_id: String,
    pub display_name: String,
    pub trust: String,
    pub root_path: String,
}

impl From<domain::GeneralWorkspace> for GeneralWorkspaceDto {
    /// 逐字段投影 Java 权威结果，不在 interface 重建 Workspace identity 或 trust 规则。
    fn from(value: domain::GeneralWorkspace) -> Self {
        Self {
            workspace_id: value.workspace_id,
            display_name: value.display_name,
            trust: value.trust,
            root_path: value.root_path,
        }
    }
}

/// Turn 准入响应保留 Java 返回的 revision，后续完成事实仍由事件投影承载。
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnAcceptedDto {
    pub accepted: bool,
    pub turn_id: String,
    pub queued: bool,
    pub thread_revision: u64,
}

impl From<domain::TurnAccepted> for TurnAcceptedDto {
    /// 只映射冻结的准入字段，避免 command 根据当地状态推测排队或 revision。
    fn from(value: domain::TurnAccepted) -> Self {
        Self {
            accepted: value.accepted,
            turn_id: value.turn_id,
            queued: value.queued,
            thread_revision: value.thread_revision,
        }
    }
}

/// Turn 取消回执的稳定 wire 形状，不携带 actor 队列或进程细节。
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnCancelResultDto {
    pub accepted: bool,
    pub turn_id: String,
    pub status: String,
    pub thread_revision: u64,
}

impl From<domain::TurnCancelResult> for TurnCancelResultDto {
    /// 保留 Java 判定的取消状态和 revision，Rust interface 不为重复取消制造本地结果。
    fn from(value: domain::TurnCancelResult) -> Self {
        Self {
            accepted: value.accepted,
            turn_id: value.turn_id,
            status: value.status,
            thread_revision: value.thread_revision,
        }
    }
}

/// 单条排队输入的稳定 Tauri 投影；数组位置而非本地排序决定下一消费顺序。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct QueuedInputDto {
    pub input_id: String,
    pub turn_id: String,
    pub content: Vec<TurnContentPartDto>,
    pub attachments: Vec<AttachmentSummaryDto>,
    pub kind: String,
    pub status: String,
    pub issue: Option<QueuedInputIssueDto>,
    pub input_revision: u64,
    pub created_at: String,
}

impl From<domain::QueuedInput> for QueuedInputDto {
    /// 逐字段映射 Java 已验证记录，避免 Rust 对 kind 或顺序做二次推导。
    fn from(value: domain::QueuedInput) -> Self {
        Self {
            input_id: value.input_id,
            turn_id: value.turn_id,
            content: value.content.into_iter().map(Into::into).collect(),
            attachments: value.attachments.into_iter().map(Into::into).collect(),
            kind: value.kind,
            status: value.status,
            issue: value.issue.map(Into::into),
            input_revision: value.input_revision,
            created_at: value.created_at,
        }
    }
}

/// Renderer 只接收所属输入的展示摘要，预览授权仍需独立 thread/draft tag。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AttachmentSummaryDto {
    pub attachment_id: String,
    pub display_name: String,
    pub size_bytes: u64,
    pub media_kind: String,
    pub media_type: String,
}

impl From<domain::AttachmentSummary> for AttachmentSummaryDto {
    /// 严格逐字段投影，不从扩展名或 content block 猜测元数据。
    fn from(value: domain::AttachmentSummary) -> Self {
        Self {
            attachment_id: value.attachment_id,
            display_name: value.display_name,
            size_bytes: value.size_bytes,
            media_kind: value.media_kind,
            media_type: value.media_type,
        }
    }
}

/// 队列问题 DTO 只保留 Composer 恢复所需字段，不暴露 sidecar 内部 error identity。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct QueuedInputIssueDto {
    pub error_code: String,
    pub message: String,
    pub retryable: bool,
}

impl From<domain::QueuedInputIssue> for QueuedInputIssueDto {
    /// 精简问题投影保持稳定恢复信息，同时截断内部诊断边界。
    fn from(value: domain::QueuedInputIssue) -> Self {
        Self {
            error_code: value.error_code,
            message: value.message,
            retryable: value.retryable,
        }
    }
}

/// `@` 路径搜索请求保持最小 typed 输入；扫描预算不允许 renderer 覆盖。
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspacePathSearchInputDto {
    pub thread_id: String,
    pub workspace_id: String,
    pub query: String,
    #[serde(default)]
    pub limit: Option<u32>,
}

impl From<WorkspacePathSearchInputDto> for domain::WorkspacePathSearchInput {
    /// 逐字段映射竞态栅栏与 query，路径读取能力不会跨越该接口。
    fn from(value: WorkspacePathSearchInputDto) -> Self {
        Self {
            thread_id: value.thread_id,
            workspace_id: value.workspace_id,
            query: value.query,
            limit: value.limit,
        }
    }
}

/// Composer 路径候选只返回相对路径和文件系统类型。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspacePathSearchItemDto {
    pub relative_path: String,
    pub kind: String,
}

/// Search 回显 Thread/Workspace/generation/query，供 renderer 完成迟到结果栅栏。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspacePathSearchResultDto {
    pub thread_id: String,
    pub workspace_id: String,
    pub generation: u64,
    pub query: String,
    pub items: Vec<WorkspacePathSearchItemDto>,
    pub truncated: bool,
}

impl From<domain::WorkspacePathSearchResult> for WorkspacePathSearchResultDto {
    /// Rust 不派生 name 或绝对路径；前端从已验证 relativePath 提取展示标签。
    fn from(value: domain::WorkspacePathSearchResult) -> Self {
        Self {
            thread_id: value.thread_id,
            workspace_id: value.workspace_id,
            generation: value.generation,
            query: value.query,
            items: value
                .items
                .into_iter()
                .map(|item| WorkspacePathSearchItemDto {
                    relative_path: item.relative_path,
                    kind: item.kind,
                })
                .collect(),
            truncated: value.truncated,
        }
    }
}

/// ACK、thread/read 与事件共享的完整队列 wire 形状。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InputQueueDto {
    pub turn_id: String,
    pub revision: u64,
    pub accepting: bool,
    pub items: Vec<QueuedInputDto>,
}

impl From<domain::InputQueue> for InputQueueDto {
    /// 保留 Java 返回的 items 原始顺序，Rust 不按 kind 或时间重新排序。
    fn from(value: domain::InputQueue) -> Self {
        Self {
            turn_id: value.turn_id,
            revision: value.revision,
            accepting: value.accepting,
            items: value.items.into_iter().map(Into::into).collect(),
        }
    }
}

/// 四个队列 mutation 共用 `{accepted,inputId,inputQueue}`，让 ACK 能权威覆盖陈旧事件。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TurnInputResultDto {
    pub accepted: bool,
    pub input_id: String,
    pub input_queue: InputQueueDto,
}

impl From<domain::TurnInputResult> for TurnInputResultDto {
    /// 结果不添加本地 status 或 kind 别名，当前队列对象是唯一可恢复事实。
    fn from(value: domain::TurnInputResult) -> Self {
        Self {
            accepted: value.accepted,
            input_id: value.input_id,
            input_queue: value.input_queue.into(),
        }
    }
}

/// frozen Turn diff reader 的 Tauri 输入；workspaceId 只在 Rust 授权，绝不转发 Java。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnChangeSetReadInputDto {
    pub workspace_id: String,
    pub thread_id: String,
    pub turn_id: String,
    pub artifact_id: String,
    pub file_path: String,
}

impl TurnChangeSetReadInputDto {
    /// 分离 native workspace identity 与 Java 三元 artifact identity，避免动态 JSON 转发。
    pub(crate) fn into_domain(self) -> (String, domain::TurnChangeSetReadInput) {
        (
            self.workspace_id,
            domain::TurnChangeSetReadInput {
                thread_id: self.thread_id,
                turn_id: self.turn_id,
                artifact_id: self.artifact_id,
                file_path: self.file_path,
            },
        )
    }
}

/// frozen Turn diff 的完整单文件投影。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnChangeSetReadResultDto {
    pub artifact_id: String,
    pub file_path: String,
    pub byte_length: u64,
    pub sha256: String,
    pub content: String,
}

impl From<domain::TurnChangeSetReadResult> for TurnChangeSetReadResultDto {
    /// 只映射 Java 已验证页，不加入 current worktree 或本地 artifact 状态。
    fn from(value: domain::TurnChangeSetReadResult) -> Self {
        Self {
            artifact_id: value.artifact_id,
            file_path: value.file_path,
            byte_length: value.byte_length,
            sha256: value.sha256,
            content: value.content,
        }
    }
}

/// Tool artifact reader 的 Tauri 输入；workspaceId 仅用于 active binding containment。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct ToolArtifactReadInputDto {
    pub workspace_id: String,
    pub thread_id: String,
    pub turn_id: String,
    pub call_id: String,
    pub artifact_id: String,
    pub offset_characters: u64,
    pub limit_characters: u64,
}

impl ToolArtifactReadInputDto {
    /// 分离 native workspace identity 与 Java 四元 Tool artifact identity。
    pub(crate) fn into_domain(self) -> (String, domain::ToolArtifactReadInput) {
        (
            self.workspace_id,
            domain::ToolArtifactReadInput {
                thread_id: self.thread_id,
                turn_id: self.turn_id,
                call_id: self.call_id,
                artifact_id: self.artifact_id,
                offset_characters: self.offset_characters,
                limit_characters: self.limit_characters,
            },
        )
    }
}

/// Tool artifact 的稳定 code-point page 投影。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolArtifactReadResultDto {
    pub artifact_id: String,
    pub offset_characters: u64,
    pub next_offset_characters: Option<u64>,
    pub total_characters: u64,
    pub truncated: bool,
    pub content: String,
}

impl From<domain::ToolArtifactReadResult> for ToolArtifactReadResultDto {
    /// 只映射 Java 已脱敏并校验归属的 Tool 输出页。
    fn from(value: domain::ToolArtifactReadResult) -> Self {
        Self {
            artifact_id: value.artifact_id,
            offset_characters: value.offset_characters,
            next_offset_characters: value.next_offset_characters,
            total_characters: value.total_characters,
            truncated: value.truncated,
            content: value.content,
        }
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeRecoveryStateDto {
    pub required: bool,
    pub acknowledgeable: bool,
    pub recovery_id: Option<String>,
    pub revision: Option<u64>,
}
impl From<domain::RuntimeRecoveryState> for RuntimeRecoveryStateDto {
    /// 恢复投影只暴露 CAS 所需 identity/revision，不暴露 marker 路径。
    fn from(value: domain::RuntimeRecoveryState) -> Self {
        Self {
            required: value.required,
            acknowledgeable: value.acknowledgeable,
            recovery_id: value.recovery_id,
            revision: value.revision,
        }
    }
}

/// task/create 的 Tauri 输入精确镜像 JA-RPC 参数，不允许 renderer 选择 task kind 或生命周期。
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskCreateInputDto {
    pub parent_thread_id: String,
    #[serde(deserialize_with = "required_nullable")]
    pub parent_turn_id: Option<String>,
    pub expected_parent_revision: u64,
    pub task_name: String,
    pub content: Vec<TurnContentPartDto>,
}

/// required nullable 字段必须显式出现在 wire object 中；`Option` 默认接受缺失会破坏严格 schema。
fn required_nullable<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}

impl From<TaskCreateInputDto> for domain::TaskCreateInput {
    /// 创建 DTO 只传递用户意图；SIDE_TASK/INDEPENDENT 与上下文冻结由 Java 用例固定。
    fn from(value: TaskCreateInputDto) -> Self {
        Self {
            parent_thread_id: value.parent_thread_id,
            parent_turn_id: value.parent_turn_id,
            expected_parent_revision: value.expected_parent_revision,
            task_name: value.task_name,
            content: value.content.into_iter().map(task_content_part).collect(),
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskListInputDto {
    pub root_thread_id: String,
}

impl From<TaskListInputDto> for domain::TaskListInput {
    /// list DTO 不携带分页或正文开关，保持最多 64 个投影的固定服务端边界。
    fn from(value: TaskListInputDto) -> Self {
        Self {
            root_thread_id: value.root_thread_id,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskReadInputDto {
    pub task_thread_id: String,
    #[serde(default, deserialize_with = "optional_non_null")]
    pub cursor: Option<String>,
    #[serde(default)]
    pub limit: Option<u16>,
}

/// 缺失字段通过 `default` 表示第一页，显式 null 则在 Tauri IPC 边界失败而不降级为缺省。
fn optional_non_null<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    T::deserialize(deserializer).map(Some)
}

impl From<TaskReadInputDto> for domain::TaskReadInput {
    /// read DTO 只映射服务端 cursor 和有界页大小，不能请求 transcript 或 raw reasoning。
    fn from(value: TaskReadInputDto) -> Self {
        Self {
            task_thread_id: value.task_thread_id,
            cursor: value.cursor,
            limit: value.limit,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskObserveInputDto {
    pub task_thread_id: String,
    pub expected_task_revision: u64,
}

impl From<TaskObserveInputDto> for domain::TaskObserveInput {
    /// observe DTO 保留 revision fence，详情关闭由独立 unobserve command 释放 handle。
    fn from(value: TaskObserveInputDto) -> Self {
        Self {
            task_thread_id: value.task_thread_id,
            expected_task_revision: value.expected_task_revision,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskUnobserveInputDto {
    pub observation_id: String,
}

impl From<TaskUnobserveInputDto> for domain::TaskUnobserveInput {
    /// unobserve DTO 只传 opaque handle，不允许 renderer 指定 connection 或 task identity。
    fn from(value: TaskUnobserveInputDto) -> Self {
        Self {
            observation_id: value.observation_id,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskSeenInputDto {
    pub task_thread_id: String,
    pub expected_task_revision: u64,
    pub through_activity_sequence: u64,
}

impl From<TaskSeenInputDto> for domain::TaskSeenInput {
    /// 已读 DTO 显式携带 activity sequence 和 projection CAS，避免本地 unread 反算。
    fn from(value: TaskSeenInputDto) -> Self {
        Self {
            task_thread_id: value.task_thread_id,
            expected_task_revision: value.expected_task_revision,
            through_activity_sequence: value.through_activity_sequence,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskMessageInputDto {
    pub sender_thread_id: String,
    pub target_thread_id: String,
    pub content: Vec<TurnContentPartDto>,
    pub idempotency_key: String,
}

impl From<TaskMessageInputDto> for domain::TaskMessageInput {
    /// Mailbox DTO 不暴露 kind/state/boundTurnId，这些字段只能由 Java 事务生成。
    fn from(value: TaskMessageInputDto) -> Self {
        Self {
            sender_thread_id: value.sender_thread_id,
            target_thread_id: value.target_thread_id,
            content: value.content.into_iter().map(task_content_part).collect(),
            idempotency_key: value.idempotency_key,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskFollowupInputDto {
    pub sender_thread_id: String,
    pub target_thread_id: String,
    pub content: Vec<TurnContentPartDto>,
    pub idempotency_key: String,
    pub expected_task_revision: u64,
}

impl From<TaskFollowupInputDto> for domain::TaskFollowupInput {
    /// followup DTO 显式组合消息与启动 CAS，不复用可被错误路由的 generic payload。
    fn from(value: TaskFollowupInputDto) -> Self {
        Self {
            message: domain::TaskMessageInput {
                sender_thread_id: value.sender_thread_id,
                target_thread_id: value.target_thread_id,
                content: value.content.into_iter().map(task_content_part).collect(),
                idempotency_key: value.idempotency_key,
            },
            expected_task_revision: value.expected_task_revision,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskMutationInputDto {
    pub task_thread_id: String,
    pub expected_task_revision: u64,
}

impl From<TaskMutationInputDto> for domain::TaskMutationInput {
    /// mutation DTO 不携带传播开关，ATTACHED/INDEPENDENT 规则只能由 Java lineage 决定。
    fn from(value: TaskMutationInputDto) -> Self {
        Self {
            task_thread_id: value.task_thread_id,
            expected_task_revision: value.expected_task_revision,
        }
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TaskTreeDeleteInputDto {
    pub task_thread_id: String,
    pub expected_task_revision: u64,
    pub confirm_task_thread_id: String,
}

impl From<TaskTreeDeleteInputDto> for domain::TaskTreeDeleteInput {
    /// delete DTO 保留重复 identity，domain 会在进入 actor 前要求二者精确相等。
    fn from(value: TaskTreeDeleteInputDto) -> Self {
        Self {
            mutation: domain::TaskMutationInput {
                task_thread_id: value.task_thread_id,
                expected_task_revision: value.expected_task_revision,
            },
            confirm_task_thread_id: value.confirm_task_thread_id,
        }
    }
}

/// 任务输入与 Mailbox 投影共用同一内容枚举映射，避免两条 Tauri 路径产生不同 tag。
fn task_content_part(part: TurnContentPartDto) -> domain::TurnContentPart {
    match part {
        TurnContentPartDto::Text { text } => domain::TurnContentPart::Text { text },
        TurnContentPartDto::Attachment { attachment_id } => {
            domain::TurnContentPart::Attachment { attachment_id }
        }
        TurnContentPartDto::WorkspaceReference {
            workspace_id,
            relative_path,
            kind,
        } => domain::TurnContentPart::WorkspaceReference {
            workspace_id,
            relative_path,
            kind,
        },
        TurnContentPartDto::SkillReference { skill_id } => {
            domain::TurnContentPart::SkillReference { skill_id }
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TaskSummaryDto {
    pub task_thread_id: String,
    pub parent_thread_id: String,
    pub root_thread_id: String,
    pub origin_turn_id: Option<String>,
    pub task_name: String,
    pub depth: u8,
    pub task_kind: String,
    pub lifecycle: String,
    pub state: String,
    pub revision: u64,
    pub latest_activity_sequence: u64,
    pub unread_count: u64,
    pub descendant_count: u8,
    pub running_descendant_count: u8,
    pub needs_attention_count: u8,
    pub latest_safe_summary: Option<String>,
    pub started_at: Option<String>,
    pub completed_at: Option<String>,
    pub updated_at: String,
}

impl From<domain::TaskSummary> for TaskSummaryDto {
    /// summary 逐字段映射 Java 权威投影，不在 Rust 重算后代、未读或耗时。
    fn from(value: domain::TaskSummary) -> Self {
        Self {
            task_thread_id: value.task_thread_id,
            parent_thread_id: value.parent_thread_id,
            root_thread_id: value.root_thread_id,
            origin_turn_id: value.origin_turn_id,
            task_name: value.task_name,
            depth: value.depth,
            task_kind: value.task_kind,
            lifecycle: value.lifecycle,
            state: value.state,
            revision: value.revision,
            latest_activity_sequence: value.latest_activity_sequence,
            unread_count: value.unread_count,
            descendant_count: value.descendant_count,
            running_descendant_count: value.running_descendant_count,
            needs_attention_count: value.needs_attention_count,
            latest_safe_summary: value.latest_safe_summary,
            started_at: value.started_at,
            completed_at: value.completed_at,
            updated_at: value.updated_at,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TaskActivityDto {
    pub activity_sequence: u64,
    pub activity_id: String,
    pub task_thread_id: String,
    pub actor_thread_id: String,
    pub causal_turn_id: Option<String>,
    pub kind: String,
    pub summary: TaskActivitySummaryDto,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TaskActivitySummaryDto {
    pub text: String,
}

impl From<domain::TaskActivity> for TaskActivityDto {
    /// activity 只投影 Java 已脱敏的 summary text，不接受 raw reasoning 或 Tool payload。
    fn from(value: domain::TaskActivity) -> Self {
        Self {
            activity_sequence: value.activity_sequence,
            activity_id: value.activity_id,
            task_thread_id: value.task_thread_id,
            actor_thread_id: value.actor_thread_id,
            causal_turn_id: value.causal_turn_id,
            kind: value.kind,
            summary: TaskActivitySummaryDto {
                text: value.summary,
            },
            created_at: value.created_at,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TaskMailboxMessageDto {
    pub mailbox_sequence: u64,
    pub message_id: String,
    pub sender_thread_id: String,
    pub target_thread_id: String,
    pub causal_turn_id: Option<String>,
    pub kind: String,
    pub content: Vec<TurnContentPartDto>,
    pub state: String,
    pub bound_turn_id: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub consumed_at: Option<String>,
}

impl From<domain::TaskMailboxMessage> for TaskMailboxMessageDto {
    /// mailbox 逐字段映射持久事实，数组顺序与消费状态不由 Rust 重排。
    fn from(value: domain::TaskMailboxMessage) -> Self {
        Self {
            mailbox_sequence: value.mailbox_sequence,
            message_id: value.message_id,
            sender_thread_id: value.sender_thread_id,
            target_thread_id: value.target_thread_id,
            causal_turn_id: value.causal_turn_id,
            kind: value.kind,
            content: value.content.into_iter().map(Into::into).collect(),
            state: value.state,
            bound_turn_id: value.bound_turn_id,
            created_at: value.created_at,
            updated_at: value.updated_at,
            consumed_at: value.consumed_at,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TaskContextSeedDto {
    pub context_seed_id: String,
    pub parent_revision: u64,
    pub inheritance_mode: String,
    pub task_brief: Vec<TurnContentPartDto>,
    pub inherited_context_summary: Option<String>,
    pub inherited_context_preview: Vec<TaskContextPreviewItemDto>,
    pub fingerprint: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TaskContextPreviewItemDto {
    pub role: String,
    pub text: Option<String>,
    pub attachment_ids: Vec<String>,
}

impl From<domain::TaskContextPreviewItem> for TaskContextPreviewItemDto {
    /// 安全预览只映射已验证的公开 role、短文本和附件 ID，不携带完整冻结上下文。
    fn from(value: domain::TaskContextPreviewItem) -> Self {
        Self {
            role: value.role,
            text: value.text,
            attachment_ids: value.attachment_ids,
        }
    }
}

impl From<domain::TaskContextSeed> for TaskContextSeedDto {
    /// seed 只暴露安全摘要与指纹，不暴露冻结 prompt、Secret 或原始隐藏推理。
    fn from(value: domain::TaskContextSeed) -> Self {
        Self {
            context_seed_id: value.context_seed_id,
            parent_revision: value.parent_revision,
            inheritance_mode: value.inheritance_mode,
            task_brief: value.task_brief.into_iter().map(Into::into).collect(),
            inherited_context_summary: value.inherited_context_summary,
            inherited_context_preview: value
                .inherited_context_preview
                .into_iter()
                .map(Into::into)
                .collect(),
            fingerprint: value.fingerprint,
            created_at: value.created_at,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskCreateResultDto {
    pub accepted: bool,
    pub task: TaskSummaryDto,
    pub turn_id: String,
}

impl From<domain::TaskCreateResult> for TaskCreateResultDto {
    /// Java 成功结果固定 accepted=true，Rust 不根据本地 actor 状态重写准入事实。
    fn from(value: domain::TaskCreateResult) -> Self {
        Self {
            accepted: true,
            task: value.task.into(),
            turn_id: value.turn_id,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskListResultDto {
    pub items: Vec<TaskSummaryDto>,
}

impl From<domain::TaskListResult> for TaskListResultDto {
    /// list 保留 Java 返回的树前序，不在 Rust 重排或按状态分组。
    fn from(value: domain::TaskListResult) -> Self {
        Self {
            items: value.items.into_iter().map(Into::into).collect(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskReadResultDto {
    pub task: TaskSummaryDto,
    pub context_seed: TaskContextSeedDto,
    pub activities: Vec<TaskActivityDto>,
    pub mailbox: Vec<TaskMailboxMessageDto>,
    pub next_cursor: Option<String>,
}

impl From<domain::TaskReadResult> for TaskReadResultDto {
    /// read 输出保持单页原子投影，防止界面拼接不同 revision 的 seed/activity/mailbox。
    fn from(value: domain::TaskReadResult) -> Self {
        Self {
            task: value.task.into(),
            context_seed: value.context_seed.into(),
            activities: value.activities.into_iter().map(Into::into).collect(),
            mailbox: value.mailbox.into_iter().map(Into::into).collect(),
            next_cursor: value.next_cursor,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskObserveResultDto {
    pub observation_id: String,
    pub task_thread_id: String,
    pub revision: u64,
}

impl From<domain::TaskObserveResult> for TaskObserveResultDto {
    /// observation 回执仅暴露 handle、Task 和 revision，connection identity 留在 App Server。
    fn from(value: domain::TaskObserveResult) -> Self {
        Self {
            observation_id: value.observation_id,
            task_thread_id: value.task_thread_id,
            revision: value.revision,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskAcceptedResultDto {
    pub accepted: bool,
}

impl TaskAcceptedResultDto {
    /// 仅在 Java 明确确认幂等释放后构造成功回执，避免本地提前声明 handle 已清理。
    pub(crate) const fn ok() -> Self {
        Self { accepted: true }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskMessageResultDto {
    pub accepted: bool,
    pub message_id: String,
    pub mailbox_sequence: u64,
}

impl From<domain::TaskMessageResult> for TaskMessageResultDto {
    /// message ACK 只映射持久 identity/sequence，不声称目标 Turn 已消费。
    fn from(value: domain::TaskMessageResult) -> Self {
        Self {
            accepted: true,
            message_id: value.message_id,
            mailbox_sequence: value.mailbox_sequence,
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskFollowupResultDto {
    pub accepted: bool,
    pub message_id: String,
    pub turn_id: String,
    pub task: TaskSummaryDto,
}

impl From<domain::TaskFollowupResult> for TaskFollowupResultDto {
    /// followup ACK 同时返回绑定 Turn 与最新 Task 投影，避免 renderer 竞态拼接。
    fn from(value: domain::TaskFollowupResult) -> Self {
        Self {
            accepted: true,
            message_id: value.message_id,
            turn_id: value.turn_id,
            task: value.task.into(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskMutationResultDto {
    pub accepted: bool,
    pub task: TaskSummaryDto,
}

impl From<domain::TaskSummary> for TaskMutationResultDto {
    /// mutation 成功时直接返回 Java 最新投影，Rust 不维护乐观 revision。
    fn from(value: domain::TaskSummary) -> Self {
        Self {
            accepted: true,
            task: value.into(),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TaskTreeDeleteResultDto {
    pub accepted: bool,
    pub deleted_task_count: u8,
}

impl From<domain::TaskTreeDeleteResult> for TaskTreeDeleteResultDto {
    /// 删除回执只返回事务确认的数量，不保留已删除 Task identity 列表。
    fn from(value: domain::TaskTreeDeleteResult) -> Self {
        Self {
            accepted: true,
            deleted_task_count: value.deleted_task_count,
        }
    }
}
