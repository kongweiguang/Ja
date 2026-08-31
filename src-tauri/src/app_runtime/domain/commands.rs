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

/// Turn content 用枚举排除 text/attachment 字段混用；附件只携带 Java identity，绝不包含路径。
#[derive(Debug, Clone)]
pub enum TurnContentPart {
    Text { text: String },
    Attachment { attachment_id: String },
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
        let mut total_text = 0_usize;
        let mut attachment_ids = std::collections::HashSet::new();
        for part in &self.content {
            match part {
                TurnContentPart::Text { text } => {
                    if text.is_empty() || text.len() > 4_000_000 || text.contains('\0') {
                        return Err(DomainValidationError);
                    }
                    total_text = total_text
                        .checked_add(text.len())
                        .ok_or(DomainValidationError)?;
                    if total_text > 4_000_000 {
                        return Err(DomainValidationError);
                    }
                }
                TurnContentPart::Attachment { attachment_id } => {
                    if !valid_protocol_id(attachment_id, "att_", 128)
                        || !attachment_ids.insert(attachment_id)
                        || attachment_ids.len() > 10
                    {
                        return Err(DomainValidationError);
                    }
                }
            }
        }
        Ok(())
    }
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

/// 只确认 sidecar 的冻结取消结果；完成仍是事件事实，因此该响应不能声称 Turn 已经结束。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnCancelResult {
    pub accepted: bool,
    pub turn_id: String,
    pub status: String,
    pub thread_revision: u64,
}

/// 已活动 Turn 的单条排队消息；method 保持强类型命令选择，WebView 无法指定任意 JA-RPC method。
#[derive(Debug, Clone)]
pub struct TurnQueuedInput {
    pub turn_id: String,
    pub text: String,
}

impl TurnQueuedInput {
    /// 校验一条有界用户消息且不添加 revision；FIFO admission 与终态竞态由 Java 独占。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_frozen_turn_id(&self.turn_id)
            || self.text.is_empty()
            || self.text.len() > 4_000_000
            || self.text.contains('\0')
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// Java 持久追加一条 FIFO item 后返回的严格投影，不在 Rust 复制队列事实。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnQueuedInputResult {
    pub accepted: bool,
    pub input_id: String,
    pub turn_id: String,
    pub kind: String,
    pub status: String,
}

/// `ja_turn_start` 的 accepted 响应；后续事实只能通过固定事件到达。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnAccepted {
    pub accepted: bool,
    pub turn_id: String,
    pub queued: bool,
    pub thread_revision: u64,
}

/// 冻结 Turn diff artifact 的有界读取输入；workspace 授权由 Host binding 单独校验。
#[derive(Debug, Clone)]
pub struct TurnChangeSetReadInput {
    pub thread_id: String,
    pub turn_id: String,
    pub artifact_id: String,
    pub offset_bytes: u64,
    pub limit_bytes: u64,
}

impl TurnChangeSetReadInput {
    /// 校验 byte 分页与三元归属 identity；UTF-8 边界由持有 artifact 的 Java owner 复核。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.thread_id, "thr_", 100)
            || !valid_protocol_id(&self.turn_id, "turn_", 101)
            || !valid_protocol_id(&self.artifact_id, "artifact_", 128)
            || self.offset_bytes > MAX_SAFE_JSON_INTEGER
            || !(1..=65_536).contains(&self.limit_bytes)
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// Java 持久化 Turn diff 的单页文本；next 为 None 表示已到精确 byteLength。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TurnChangeSetReadResult {
    pub artifact_id: String,
    pub offset_bytes: u64,
    pub next_offset_bytes: Option<u64>,
    pub byte_length: u64,
    pub truncated: bool,
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
