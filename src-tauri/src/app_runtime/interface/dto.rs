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
#[derive(Deserialize)]
#[serde(deny_unknown_fields, tag = "type", rename_all = "snake_case")]
pub enum TurnContentPartDto {
    Text {
        text: String,
    },
    Attachment {
        #[serde(rename = "attachmentId")]
        attachment_id: String,
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
                })
                .collect(),
            deadline_ms: value.deadline_ms,
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

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct TurnQueuedInputDto {
    pub turn_id: String,
    pub text: String,
}
impl From<TurnQueuedInputDto> for domain::TurnQueuedInput {
    /// 排队输入保持最小字段集，method 由具体 command 决定。
    fn from(value: TurnQueuedInputDto) -> Self {
        Self {
            turn_id: value.turn_id,
            text: value.text,
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

/// Steering/follow-up 入队结果的统一投影，kind 和 status 仍由 Java durable FIFO 决定。
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnQueuedInputResultDto {
    pub accepted: bool,
    pub input_id: String,
    pub turn_id: String,
    pub kind: String,
    pub status: String,
}

impl From<domain::TurnQueuedInputResult> for TurnQueuedInputResultDto {
    /// 显式传递队列 identity 与状态，不让后续增加的 domain 字段无审核扩大 Tauri 契约。
    fn from(value: domain::TurnQueuedInputResult) -> Self {
        Self {
            accepted: value.accepted,
            input_id: value.input_id,
            turn_id: value.turn_id,
            kind: value.kind,
            status: value.status,
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
    pub offset_bytes: u64,
    pub limit_bytes: u64,
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
                offset_bytes: self.offset_bytes,
                limit_bytes: self.limit_bytes,
            },
        )
    }
}

/// frozen Turn diff 的稳定 byte-page 投影。
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TurnChangeSetReadResultDto {
    pub artifact_id: String,
    pub offset_bytes: u64,
    pub next_offset_bytes: Option<u64>,
    pub byte_length: u64,
    pub truncated: bool,
    pub content: String,
}

impl From<domain::TurnChangeSetReadResult> for TurnChangeSetReadResultDto {
    /// 只映射 Java 已验证页，不加入 current worktree 或本地 artifact 状态。
    fn from(value: domain::TurnChangeSetReadResult) -> Self {
        Self {
            artifact_id: value.artifact_id,
            offset_bytes: value.offset_bytes,
            next_offset_bytes: value.next_offset_bytes,
            byte_length: value.byte_length,
            truncated: value.truncated,
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
