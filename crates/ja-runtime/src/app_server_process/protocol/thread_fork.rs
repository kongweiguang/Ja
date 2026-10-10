// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Strict JA-RPC DTOs for persistent Thread forks.

use serde::{Deserialize, Serialize};

const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;

/// 将明确的 JSON null 与缺失字段区分，保持 Thread 投影的 wire 闭集。
fn required_option<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}

/// Fork 只引用冻结来源 Item；新 Thread ID 由服务端生成，不能由调用方指定。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadForkParams {
    pub source_thread_id: String,
    pub source_item_id: String,
    pub expected_thread_revision: u64,
    pub operation_key: String,
}

impl ThreadForkParams {
    /// 在产生持久分叉副作用前验证来源、CAS revision 与幂等身份。
    pub fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.source_thread_id, "thr_", 100)
            || !valid_prefixed(&self.source_item_id, "item_", 101)
            || self.expected_thread_revision > MAX_SAFE_INTEGER
            || !valid_operation_key(&self.operation_key)
        {
            return Err("thread fork parameters are invalid");
        }
        Ok(())
    }
}

/// 返回新 Thread 时同时给出冻结来源身份，便于核验分叉来源且不传递执行状态。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadForkSource {
    pub thread_id: String,
    pub item_id: String,
    pub thread_revision: u64,
}

/// 严格嵌套分叉回执，防止任务谱系或来源会话活动状态泄漏到新 Thread。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadForkResult {
    pub thread: ThreadForkThread,
    pub source: ThreadForkSource,
}

impl ThreadForkResult {
    /// 单独校验回执投影，使 operation/read 也能验证冻结来源而无需重建原始请求。
    pub fn validate_projection(&self) -> Result<(), &'static str> {
        self.thread.validate()?;
        if !valid_prefixed(&self.source.thread_id, "thr_", 100)
            || !valid_prefixed(&self.source.item_id, "item_", 101)
            || self.source.thread_revision > MAX_SAFE_INTEGER
            || self.thread.thread_id == self.source.thread_id
        {
            return Err("thread fork result projection is invalid");
        }
        Ok(())
    }

    /// 将回执来源 Item 绑定到 CAS 请求，并要求新 Thread 是独立且空闲的普通会话。
    pub fn validate_for(&self, request: &ThreadForkParams) -> Result<(), &'static str> {
        request.validate()?;
        self.validate_projection()?;
        if self.source.thread_id != request.source_thread_id
            || self.source.item_id != request.source_item_id
            || self.source.thread_revision != request.expected_thread_revision
        {
            return Err("thread fork result is not bound to its frozen source");
        }
        Ok(())
    }
}

/// Thread fields needed by the durable history projection, with no task or execution extensions.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadForkThread {
    pub thread_id: String,
    pub workspace_id: String,
    pub workspace_kind: ForkWorkspaceKind,
    #[serde(deserialize_with = "required_option")]
    pub preferences: Option<ThreadForkPreferences>,
    pub title: String,
    pub status: ForkThreadStatus,
    pub pinned: bool,
    #[serde(deserialize_with = "required_option")]
    pub latest_turn_status: Option<ForkTurnStatus>,
    pub latest_turn_seen: bool,
    pub revision: u64,
    pub created_at: String,
    pub updated_at: String,
}

impl ThreadForkThread {
    /// Fork 建立无进行中 Turn 的普通活动 Thread，来源执行身份留在原 Thread。
    fn validate(&self) -> Result<(), &'static str> {
        if !valid_prefixed(&self.thread_id, "thr_", 100)
            || !valid_prefixed(&self.workspace_id, "ws_", 99)
            || self.title.is_empty()
            || self.title.chars().count() > 512
            || self.title.chars().any(char::is_control)
            || self.status != ForkThreadStatus::Active
            || self.latest_turn_status.is_some()
            || !self.latest_turn_seen
            || self.revision > MAX_SAFE_INTEGER
            || !super::valid_protocol_timestamp(&self.created_at)
            || !super::valid_protocol_timestamp(&self.updated_at)
            || self
                .preferences
                .as_ref()
                .is_some_and(|value| !value.validate())
        {
            return Err("fork thread projection is not a fresh idle thread");
        }
        Ok(())
    }
}

/// 新 Thread 的用户偏好以闭集对象投影，避免来源活动状态混入偏好字段。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ThreadForkPreferences {
    pub provider_id: String,
    pub model_id: String,
    #[serde(deserialize_with = "required_option")]
    pub reasoning_level: Option<String>,
    pub access_mode: ForkAccessMode,
    pub collaboration_mode: ForkCollaborationMode,
    pub title_source: ForkTitleSource,
}

impl ThreadForkPreferences {
    /// 复用 Thread RPC 的 Provider/Model 身份与推理等级词汇，阻止继承未知设置。
    fn validate(&self) -> bool {
        valid_prefixed(&self.provider_id, "provider_", 128)
            && valid_prefixed(&self.model_id, "model_", 128)
            && self.reasoning_level.as_ref().is_none_or(|value| {
                matches!(
                    value.as_str(),
                    "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max"
                )
            })
    }
}

/// 区分持久项目工作区与普通本地会话工作区。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForkWorkspaceKind {
    Project,
    Session,
}

/// 分叉结果只能是新的活动 Thread，不能复用来源的生命周期状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForkThreadStatus {
    Active,
    Archived,
    Deleted,
}

/// 保留历史 Turn 状态枚举，但新分叉的空 Thread 不得携带最新 Turn 状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForkTurnStatus {
    Queued,
    Running,
    WaitingInput,
    WaitingApproval,
    Suspended,
    Completed,
    Failed,
    Cancelled,
}

/// 分叉 Thread 显式保存访问模式，不从来源历史或审批状态推断权限。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForkAccessMode {
    ApprovalRequired,
    FullAccess,
}

/// 协作模式只接受 Ja 当前支持的默认与 Plan 两种行为。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForkCollaborationMode {
    Default,
    Plan,
}

/// 标题来源区分占位、自动生成与用户手动设置，避免错误继承来源标题语义。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ForkTitleSource {
    Placeholder,
    Auto,
    Manual,
}

/// 校验带前缀的不透明身份，不将其内容解释为宿主路径。
fn valid_prefixed(value: &str, prefix: &str, maximum: usize) -> bool {
    let Some(suffix) = value.strip_prefix(prefix) else {
        return false;
    };
    !suffix.is_empty()
        && value.len() <= maximum
        && suffix.as_bytes()[0].is_ascii_alphanumeric()
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// 使用 JA-RPC 操作账本的同一小写随机键格式保证持久变更幂等。
fn valid_operation_key(value: &str) -> bool {
    value.len() == 35
        && value.starts_with("op_")
        && value.as_bytes()[3..]
            .iter()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(byte))
}
