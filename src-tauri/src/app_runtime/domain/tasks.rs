// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Child Thread 任务协议的纯领域输入与权威投影。
//!
//! Rust 只验证 WebView 可提交的闭集并承载 Java 返回的安全投影，不保存任务树、
//! Mailbox 或 observation 的第二份权威状态。

use super::commands::{DomainValidationError, TurnContentPart, validate_turn_content};
use super::{valid_frozen_turn_id, valid_protocol_id};

const MAX_SAFE_JSON_INTEGER: u64 = 9_007_199_254_740_991;

#[derive(Debug, Clone)]
pub struct TaskCreateInput {
    pub parent_thread_id: String,
    pub parent_turn_id: Option<String>,
    pub expected_parent_revision: u64,
    pub task_name: String,
    pub content: Vec<TurnContentPart>,
}

impl TaskCreateInput {
    /// 在进入 sidecar actor 前关闭 task/create 的字段与大小边界；父子关系和 revision
    /// 仍由 Java 在同一事务裁决，Rust 不建立本地任务树。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.parent_thread_id, "thr_", 100)
            || self
                .parent_turn_id
                .as_deref()
                .is_some_and(|turn_id| !valid_frozen_turn_id(turn_id))
            || self.expected_parent_revision > MAX_SAFE_JSON_INTEGER
            || self.task_name.trim() != self.task_name
            || self.task_name.is_empty()
            || self.task_name.chars().count() > 96
            || self.task_name.chars().any(char::is_control)
        {
            return Err(DomainValidationError);
        }
        validate_turn_content(&self.content, 4_000_000)
    }
}

#[derive(Debug, Clone)]
pub struct TaskListInput {
    pub root_thread_id: String,
}

impl TaskListInput {
    /// task/list 只接受树根 identity，避免 renderer 请求全局任务扫描或 Child transcript。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        valid_protocol_id(&self.root_thread_id, "thr_", 100)
            .then_some(())
            .ok_or(DomainValidationError)
    }
}

#[derive(Debug, Clone)]
pub struct TaskReadInput {
    pub task_thread_id: String,
    pub cursor: Option<String>,
    pub limit: Option<u16>,
}

impl TaskReadInput {
    /// 读取任务详情只接受活动/Mailbox 双序号 cursor，防止畸形 opaque 值绕过服务端分页边界。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.task_thread_id, "thr_", 100)
            || self.limit.is_some_and(|limit| !(1..=200).contains(&limit))
            || self
                .cursor
                .as_deref()
                .is_some_and(|cursor| !valid_task_cursor(cursor))
        {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// Task cursor 是 `task:<activitySequence>:<mailboxSequence>`；两段十进制允许 0 表示第一页边界。
pub(crate) fn valid_task_cursor(value: &str) -> bool {
    if value.len() > 256 {
        return false;
    }
    let mut parts = value.split(':');
    matches!(parts.next(), Some("task"))
        && parts.next().is_some_and(valid_cursor_sequence)
        && parts.next().is_some_and(valid_cursor_sequence)
        && parts.next().is_none()
}

/// Cursor sequence 禁止空串、符号和超过 JavaScript safe integer 的值。
fn valid_cursor_sequence(value: &str) -> bool {
    !value.is_empty()
        && value.bytes().all(|byte| byte.is_ascii_digit())
        && value
            .parse::<u64>()
            .is_ok_and(|number| number <= MAX_SAFE_JSON_INTEGER)
}

#[derive(Debug, Clone)]
pub struct TaskObserveInput {
    pub task_thread_id: String,
    pub expected_task_revision: u64,
}

impl TaskObserveInput {
    /// observation 绑定 Java projection revision；Rust 不从已见事件推断或修正该 CAS。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        validate_task_revision(&self.task_thread_id, self.expected_task_revision)
    }
}

#[derive(Debug, Clone)]
pub struct TaskUnobserveInput {
    pub observation_id: String,
}

impl TaskUnobserveInput {
    /// 释放只接受 App Server 签发的 connection-scoped handle，禁止把 Thread ID 当 handle。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        valid_protocol_id(&self.observation_id, "observe_", 103)
            .then_some(())
            .ok_or(DomainValidationError)
    }
}

#[derive(Debug, Clone)]
pub struct TaskSeenInput {
    pub task_thread_id: String,
    pub expected_task_revision: u64,
    pub through_activity_sequence: u64,
}

impl TaskSeenInput {
    /// 已读边界必须和 projection revision 一起提交，避免迟到详情覆盖更新后的未读事实。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        validate_task_revision(&self.task_thread_id, self.expected_task_revision)?;
        if !(1..=MAX_SAFE_JSON_INTEGER).contains(&self.through_activity_sequence) {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct TaskMessageInput {
    pub sender_thread_id: String,
    pub target_thread_id: String,
    pub content: Vec<TurnContentPart>,
    pub idempotency_key: String,
}

impl TaskMessageInput {
    /// Mailbox 写入只接收结构化内容与有界幂等键；关系授权和 exactly-once 由 Java 事务负责。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        if !valid_protocol_id(&self.sender_thread_id, "thr_", 100)
            || !valid_protocol_id(&self.target_thread_id, "thr_", 100)
            || self.idempotency_key.is_empty()
            || self.idempotency_key.len() > 128
            || self.idempotency_key.chars().any(char::is_control)
        {
            return Err(DomainValidationError);
        }
        validate_turn_content(&self.content, 524_288)
    }
}

#[derive(Debug, Clone)]
pub struct TaskFollowupInput {
    pub message: TaskMessageInput,
    pub expected_task_revision: u64,
}

impl TaskFollowupInput {
    /// followup 与 QueueOnly message 保持同一内容边界，但额外要求目标 revision 以启动 Child Turn。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        self.message.validate()?;
        if self.expected_task_revision > MAX_SAFE_JSON_INTEGER {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct TaskMutationInput {
    pub task_thread_id: String,
    pub expected_task_revision: u64,
}

impl TaskMutationInput {
    /// cancel/seen 等 mutation 共用 Thread identity 与 JavaScript-safe revision，不携带本地状态。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        validate_task_revision(&self.task_thread_id, self.expected_task_revision)
    }
}

#[derive(Debug, Clone)]
pub struct TaskTreeDeleteInput {
    pub mutation: TaskMutationInput,
    pub confirm_task_thread_id: String,
}

impl TaskTreeDeleteInput {
    /// 整树删除要求重复精确 identity，防止通用 Thread 删除入口或陈旧选中项误删后代。
    pub(crate) fn validate(&self) -> Result<(), DomainValidationError> {
        self.mutation.validate()?;
        if self.confirm_task_thread_id != self.mutation.task_thread_id {
            return Err(DomainValidationError);
        }
        Ok(())
    }
}

/// 所有 task projection CAS 入口复用同一 identity/整数边界，避免各命令产生细微漂移。
fn validate_task_revision(
    task_thread_id: &str,
    expected_task_revision: u64,
) -> Result<(), DomainValidationError> {
    if !valid_protocol_id(task_thread_id, "thr_", 100)
        || expected_task_revision > MAX_SAFE_JSON_INTEGER
    {
        return Err(DomainValidationError);
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskSummary {
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskActivity {
    pub activity_sequence: u64,
    pub activity_id: String,
    pub task_thread_id: String,
    pub actor_thread_id: String,
    pub causal_turn_id: Option<String>,
    pub kind: String,
    pub summary: String,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskMailboxMessage {
    pub mailbox_sequence: u64,
    pub message_id: String,
    pub sender_thread_id: String,
    pub target_thread_id: String,
    pub causal_turn_id: Option<String>,
    pub kind: String,
    pub content: Vec<TurnContentPart>,
    pub state: String,
    pub bound_turn_id: Option<String>,
    pub created_at: String,
    pub updated_at: String,
    pub consumed_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskContextSeed {
    pub context_seed_id: String,
    pub parent_revision: u64,
    pub inheritance_mode: String,
    pub task_brief: Vec<TurnContentPart>,
    pub inherited_context_summary: Option<String>,
    pub inherited_context_preview: Vec<TaskContextPreviewItem>,
    pub fingerprint: String,
    pub created_at: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskContextPreviewItem {
    pub role: String,
    pub text: Option<String>,
    pub attachment_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskCreateResult {
    pub task: TaskSummary,
    pub turn_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskListResult {
    pub items: Vec<TaskSummary>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskReadResult {
    pub task: TaskSummary,
    pub context_seed: TaskContextSeed,
    pub activities: Vec<TaskActivity>,
    pub mailbox: Vec<TaskMailboxMessage>,
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskObserveResult {
    pub observation_id: String,
    pub task_thread_id: String,
    pub revision: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskMessageResult {
    pub message_id: String,
    pub mailbox_sequence: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskFollowupResult {
    pub message_id: String,
    pub turn_id: String,
    pub task: TaskSummary,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskTreeDeleteResult {
    pub deleted_task_count: u8,
}
