// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Child Thread 任务的固定 JA-RPC 方法、参数构造与严格结果解析。

use super::*;
use crate::app_runtime::domain::tasks::valid_task_cursor;
use crate::app_runtime::domain::{
    TaskContextPreviewItem, TaskThreadPreferences, TaskThreadSummary, valid_protocol_id,
};
use crate::app_runtime::interface::history_model::{ThreadDto, parse_thread};
use serde::Deserialize;
use std::collections::{HashMap, HashSet};

const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;
pub(crate) const TASK_OBSERVATION_OWNER_MAIN: &str = "main";

#[derive(Debug, Clone, PartialEq, Eq)]
struct TaskObservationLease {
    owner: String,
    generation: u64,
}

#[derive(Default)]
pub(crate) struct TaskObservationRegistry {
    leases: Mutex<HashMap<String, TaskObservationLease>>,
}

impl TaskObservationRegistry {
    /// App Server 成功签发后才登记 handle；重复 identity 或锁中毒均失败关闭。
    pub(crate) fn register(
        &self,
        observation_id: &str,
        owner: &str,
        generation: u64,
    ) -> Result<(), RuntimeCommandError> {
        if !valid_protocol_id(observation_id, "observe_", 103)
            || owner.is_empty()
            || generation == 0
        {
            return Err(RuntimeCommandError::unavailable());
        }
        let mut leases = self
            .leases
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())?;
        if leases.contains_key(observation_id) {
            return Err(RuntimeCommandError::unavailable());
        }
        leases.insert(
            observation_id.to_owned(),
            TaskObservationLease {
                owner: owner.to_owned(),
                generation,
            },
        );
        Ok(())
    }

    /// 高频 progress 必须同时匹配 active handle 与 sidecar generation，旧页面事件直接丢弃。
    pub(crate) fn is_active(&self, observation_id: &str, generation: u64) -> bool {
        self.leases
            .lock()
            .ok()
            .and_then(|leases| leases.get(observation_id).cloned())
            .is_some_and(|lease| lease.generation == generation)
    }

    /// 显式 unobserve 只允许创建它的 WebView owner 释放；不存在仍交给 Java 做幂等裁决。
    pub(crate) fn ensure_owner(
        &self,
        observation_id: &str,
        owner: &str,
    ) -> Result<(), RuntimeCommandError> {
        let leases = self
            .leases
            .lock()
            .map_err(|_| RuntimeCommandError::unavailable())?;
        if leases
            .get(observation_id)
            .is_some_and(|lease| lease.owner != owner)
        {
            return Err(RuntimeCommandError::invalid_params());
        }
        Ok(())
    }

    /// Java ACK 或补偿失败后移除本地路由权，确保迟到 progress 不会重新进入 WebView。
    pub(crate) fn remove(&self, observation_id: &str) {
        if let Ok(mut leases) = self.leases.lock() {
            leases.remove(observation_id);
        }
    }

    /// Reload 先原子撤销 owner 的全部本地路由，再在锁外逐个向 Java 补偿释放。
    pub(crate) fn drain_owner(&self, owner: &str) -> Vec<String> {
        let Ok(mut leases) = self.leases.lock() else {
            return Vec::new();
        };
        let ids = leases
            .iter()
            .filter(|(_, lease)| lease.owner == owner)
            .map(|(id, _)| id.clone())
            .collect::<Vec<_>>();
        ids.iter().for_each(|id| {
            leases.remove(id);
        });
        ids
    }

    /// Connection 已不可用时清空全部路由事实；Java session 关闭负责最终资源释放。
    pub(crate) fn clear(&self) {
        if let Ok(mut leases) = self.leases.lock() {
            leases.clear();
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum TaskMethod {
    Create,
    List,
    Read,
    Observe,
    Unobserve,
    Seen,
    MessageSend,
    Followup,
    Cancel,
    TreeDelete,
    Close,
}

impl TaskMethod {
    /// 方法闭集在 Rust 内静态选择，防止 renderer 通过 generic request lane 调用其它 JA-RPC。
    pub(crate) const fn wire_name(self) -> &'static str {
        match self {
            Self::Create => "task/create",
            Self::List => "task/list",
            Self::Read => "task/read",
            Self::Observe => "task/observe",
            Self::Unobserve => "task/unobserve",
            Self::Seen => "task/seen",
            Self::MessageSend => "thread/message/send",
            Self::Followup => "task/followup",
            Self::Cancel => "task/cancel",
            Self::TreeDelete => "task/tree/delete",
            Self::Close => "task/close",
        }
    }
}

/// 在当前 supervised generation 上发送一个固定 task 请求；Java App Server 仍独占
/// lineage、Mailbox、projection 与 observation registry。
pub(super) fn task_request_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    method: TaskMethod,
    params: Value,
    exit_control: &ExitControl,
) -> Result<Value, RuntimeCommandError> {
    let current = runtime
        .as_mut()
        .ok_or_else(RuntimeCommandError::unavailable)?;
    if let Some(session) = current.supervisor.session_for_cancellation() {
        exit_control.attach_session(session);
    }
    let _session_cancellation_guard = SessionCancellationGuard::new(exit_control);
    let timeout = operation_timeout(config.request_timeout, exit_control)?;
    let response = current
        .supervisor
        .request(method.wire_name(), params, timeout)
        .map_err(|error| RuntimeCommandError::from_process(&error))?;
    let value = frame_to_value(&response).map_err(|_| RuntimeCommandError::unavailable())?;
    if let Some(error) = value.get("error") {
        return Err(command_error_from_rpc(error));
    }
    value
        .get("result")
        .cloned()
        .filter(Value::is_object)
        .ok_or_else(RuntimeCommandError::unavailable)
}

impl RuntimeBridge {
    /// task/create 通过专用 actor variant 提交，响应在越过 Tauri IPC 前完成严格解析。
    pub(crate) fn task_create(
        &self,
        input: TaskCreateInput,
    ) -> Result<TaskCreateResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let expected_parent_thread_id = input.parent_thread_id.clone();
        let expected_parent_turn_id = input.parent_turn_id.clone();
        let expected_task_name = input.task_name.clone();
        let mut params = json!({
            "parentThreadId": input.parent_thread_id,
            "parentTurnId": input.parent_turn_id,
            "expectedParentRevision": input.expected_parent_revision,
            "taskName": input.task_name,
        });
        if let Some(preferences) = input.preferences {
            params["preferences"] = json!({
                "providerId": preferences.provider_id,
                "modelId": preferences.model_id,
                "reasoningLevel": preferences.reasoning_level,
                "accessMode": preferences.access_mode,
                "collaborationMode": preferences.collaboration_mode,
            });
        }
        parse_task_create_result(
            self.task_request(TaskMethod::Create, params)?,
            &expected_parent_thread_id,
            expected_parent_turn_id.as_deref(),
            &expected_task_name,
        )
    }

    /// task/list 保留服务端树序并施加最多 64 个后代上限。
    pub(crate) fn task_list(
        &self,
        input: TaskListInput,
    ) -> Result<TaskListResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let expected_root_thread_id = input.root_thread_id.clone();
        parse_task_list_result(
            self.task_request(
                TaskMethod::List,
                json!({"rootThreadId": input.root_thread_id}),
            )?,
            &expected_root_thread_id,
        )
    }

    /// task/read 只在参数存在时发送 cursor/limit，缺失不编码为另一种 null 语义。
    pub(crate) fn task_read(
        &self,
        input: TaskReadInput,
    ) -> Result<TaskReadResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let expected_task_thread_id = input.task_thread_id.clone();
        let mut params = json!({"taskThreadId": input.task_thread_id});
        if let Some(cursor) = input.cursor {
            params["cursor"] = json!(cursor);
        }
        if let Some(limit) = input.limit {
            params["limit"] = json!(limit);
        }
        parse_task_read_result(
            self.task_request(TaskMethod::Read, params)?,
            &expected_task_thread_id,
        )
    }

    /// observe 通过专用 actor 命令登记 WebView owner，使 reload 与迟到 progress 可被证明地隔离。
    pub(crate) fn task_observe(
        &self,
        input: TaskObserveInput,
    ) -> Result<TaskObserveResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.call(|reply| BridgeCommand::TaskObserve {
            input,
            owner: TASK_OBSERVATION_OWNER_MAIN,
            reply,
        })
    }

    /// unobserve 由 actor 校验 owner 并等待 Java ACK，防止另一个 WebView 释放非自身句柄。
    pub(crate) fn task_unobserve(
        &self,
        input: TaskUnobserveInput,
    ) -> Result<(), RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        self.call(|reply| BridgeCommand::TaskUnobserve {
            input,
            owner: TASK_OBSERVATION_OWNER_MAIN,
            reply,
        })
    }

    /// Hard reload 只释放指定 renderer owner 的 observation，不取消任何 Child Task。
    pub(crate) fn release_task_observations(
        &self,
        owner: &'static str,
    ) -> Result<usize, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::TaskObservationsRelease { owner, reply })
    }

    /// seen 同时提交 activity sequence 与 projection CAS，并返回最新 Task projection。
    pub(crate) fn task_seen(
        &self,
        input: TaskSeenInput,
    ) -> Result<TaskSummary, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let expected_task_thread_id = input.task_thread_id.clone();
        parse_task_mutation_result(
            self.task_request(
                TaskMethod::Seen,
                json!({
                    "taskThreadId": input.task_thread_id,
                    "expectedTaskRevision": input.expected_task_revision,
                    "throughActivitySequence": input.through_activity_sequence,
                }),
            )?,
            &expected_task_thread_id,
        )
    }

    /// QueueOnly message 只携带结构化内容与幂等键，不接受 wake 或 Turn 字段。
    pub(crate) fn task_message_send(
        &self,
        input: TaskMessageInput,
    ) -> Result<TaskMessageResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let params = task_message_params(&input);
        parse_task_message_result(self.task_request(TaskMethod::MessageSend, params)?)
    }

    /// followup 使用独立方法和 revision CAS，不能降级为 QueueOnly message 加本地 Turn start。
    pub(crate) fn task_followup(
        &self,
        input: TaskFollowupInput,
    ) -> Result<TaskFollowupResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let expected_task_thread_id = input.message.target_thread_id.clone();
        let mut params = task_message_params(&input.message);
        params["expectedTaskRevision"] = json!(input.expected_task_revision);
        parse_task_followup_result(
            self.task_request(TaskMethod::Followup, params)?,
            &expected_task_thread_id,
        )
    }

    /// cancel 只提交目标与 revision；递归范围由 Java ATTACHED lineage 决定。
    pub(crate) fn task_cancel(
        &self,
        input: TaskMutationInput,
    ) -> Result<TaskSummary, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let expected_task_thread_id = input.task_thread_id.clone();
        parse_task_mutation_result(
            self.task_request(TaskMethod::Cancel, task_mutation_params(&input))?,
            &expected_task_thread_id,
        )
    }

    /// tree/delete 保留重复 identity 确认并只返回 Java 原子删除计数。
    pub(crate) fn task_tree_delete(
        &self,
        input: TaskTreeDeleteInput,
    ) -> Result<TaskTreeDeleteResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        let mut params = task_mutation_params(&input.mutation);
        params["confirmTaskThreadId"] = json!(input.confirm_task_thread_id);
        parse_task_tree_delete_result(self.task_request(TaskMethod::TreeDelete, params)?)
    }

    /// task/close 不携带 revision；Java 以 Thread 临时生命周期 owner 实现幂等关闭。
    pub(crate) fn task_close(
        &self,
        input: TaskCloseInput,
    ) -> Result<TaskCloseResult, RuntimeCommandError> {
        input
            .validate()
            .map_err(|_| RuntimeCommandError::invalid_params())?;
        parse_task_close_result(self.task_request(
            TaskMethod::Close,
            json!({"taskThreadId": input.task_thread_id}),
        )?)
    }

    /// 所有 task 方法共享同一 actor/generation fence，但 method 只能来自闭集枚举。
    fn task_request(
        &self,
        method: TaskMethod,
        params: Value,
    ) -> Result<Value, RuntimeCommandError> {
        self.call(|reply| BridgeCommand::Task {
            method,
            params,
            reply,
        })
    }
}

/// Actor 在当前 connection 上创建 observation，并把 handle 与 sidecar generation 原子登记。
pub(super) fn task_observe_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: TaskObserveInput,
    owner: &str,
    registry: &TaskObservationRegistry,
    exit_control: &ExitControl,
) -> Result<TaskObserveResult, RuntimeCommandError> {
    let expected_task_thread_id = input.task_thread_id.clone();
    let expected_task_revision = input.expected_task_revision;
    let generation = runtime
        .as_ref()
        .map(|runtime| runtime.generation)
        .ok_or_else(RuntimeCommandError::unavailable)?;
    let result = parse_task_observe_result(
        task_request_runtime(
            config,
            runtime,
            TaskMethod::Observe,
            json!({
                "taskThreadId": input.task_thread_id,
                "expectedTaskRevision": expected_task_revision,
            }),
            exit_control,
        )?,
        &expected_task_thread_id,
        expected_task_revision,
    )?;
    registry.register(&result.observation_id, owner, generation)?;
    Ok(result)
}

/// 显式释放即使本地已无 lease 也调用 Java，保留 server-side unobserve 的幂等语义。
pub(super) fn task_unobserve_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    input: TaskUnobserveInput,
    owner: &str,
    registry: &TaskObservationRegistry,
    exit_control: &ExitControl,
) -> Result<(), RuntimeCommandError> {
    registry.ensure_owner(&input.observation_id, owner)?;
    let observation_id = input.observation_id;
    let result = task_request_runtime(
        config,
        runtime,
        TaskMethod::Unobserve,
        json!({"observationId": observation_id}),
        exit_control,
    )
    .and_then(parse_accepted);
    if result.is_ok() {
        registry.remove(&observation_id);
    }
    result
}

/// Reload/stop 先撤销本地 progress 路由，再逐个补偿 Java handle；失败只返回首个脱敏错误。
pub(super) fn release_task_observations_runtime(
    config: &LaunchConfig,
    runtime: &mut Option<RunningRuntime>,
    owner: &str,
    registry: &TaskObservationRegistry,
    exit_control: &ExitControl,
) -> Result<usize, RuntimeCommandError> {
    let observation_ids = registry.drain_owner(owner);
    let mut first_error = None;
    for observation_id in &observation_ids {
        let result = task_request_runtime(
            config,
            runtime,
            TaskMethod::Unobserve,
            json!({"observationId": observation_id}),
            exit_control,
        )
        .and_then(parse_accepted);
        if first_error.is_none() {
            first_error = result.err();
        }
    }
    if let Some(error) = first_error {
        return Err(error);
    }
    Ok(observation_ids.len())
}

/// One-shot receiver 消失时把成功 handle 交回 actor 做 unobserve 补偿，错误结果无需补偿。
pub(crate) fn deliver_task_observe_reply(
    reply: Reply<TaskObserveResult>,
    result: Result<TaskObserveResult, RuntimeCommandError>,
) -> Option<TaskObserveResult> {
    let compensation = result.as_ref().ok().cloned();
    reply.send(result).err().and(compensation)
}

/// Mailbox 两个写入口共享精确基础形状，followup 只在其上增加 revision CAS。
fn task_message_params(input: &TaskMessageInput) -> Value {
    json!({
        "senderThreadId": input.sender_thread_id,
        "targetThreadId": input.target_thread_id,
        "content": turn_content_value(&input.content),
        "idempotencyKey": input.idempotency_key,
    })
}

/// cancel/delete 共用最小 CAS envelope，删除确认由调用方显式追加。
fn task_mutation_params(input: &TaskMutationInput) -> Value {
    json!({
        "taskThreadId": input.task_thread_id,
        "expectedTaskRevision": input.expected_task_revision,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskSummaryWire {
    task_thread_id: String,
    parent_thread_id: String,
    root_thread_id: String,
    #[serde(deserialize_with = "required_nullable")]
    origin_turn_id: Option<String>,
    task_name: String,
    depth: u8,
    task_kind: String,
    lifecycle: String,
    state: String,
    revision: u64,
    latest_activity_sequence: u64,
    unread_count: u64,
    descendant_count: u8,
    running_descendant_count: u8,
    needs_attention_count: u8,
    #[serde(deserialize_with = "required_nullable")]
    latest_safe_summary: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    started_at: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    completed_at: Option<String>,
    updated_at: String,
}

impl TaskSummaryWire {
    /// 对 Java projection 执行闭集、identity、计数和安全文本校验后才映射到 Tauri DTO。
    fn into_domain(self) -> Result<TaskSummary, RuntimeCommandError> {
        let valid = valid_protocol_id(&self.task_thread_id, "thr_", 100)
            && valid_protocol_id(&self.parent_thread_id, "thr_", 100)
            && valid_protocol_id(&self.root_thread_id, "thr_", 100)
            && self
                .origin_turn_id
                .as_deref()
                .is_none_or(valid_frozen_turn_id)
            && self.task_name.trim() == self.task_name
            && !self.task_name.is_empty()
            && self.task_name.chars().count() <= 96
            && !self.task_name.chars().any(char::is_control)
            && (1..=4).contains(&self.depth)
            && matches!(self.task_kind.as_str(), "side_task" | "subagent")
            && matches!(self.lifecycle.as_str(), "independent" | "attached")
            && matches!(
                self.state.as_str(),
                "idle"
                    | "queued"
                    | "running"
                    | "waiting_approval"
                    | "suspended"
                    | "completed"
                    | "failed"
                    | "cancelled"
            )
            && self.revision <= MAX_SAFE_INTEGER
            && (1..=MAX_SAFE_INTEGER).contains(&self.latest_activity_sequence)
            && self.unread_count <= MAX_SAFE_INTEGER
            && self.descendant_count <= 64
            && self.running_descendant_count <= 64
            && self.needs_attention_count <= 64
            && self
                .latest_safe_summary
                .as_deref()
                .is_none_or(valid_preview)
            && self
                .started_at
                .as_deref()
                .is_none_or(valid_protocol_timestamp)
            && self
                .completed_at
                .as_deref()
                .is_none_or(valid_protocol_timestamp)
            && valid_protocol_timestamp(&self.updated_at)
            && matches!(
                (self.task_kind.as_str(), self.lifecycle.as_str()),
                ("side_task", "independent") | ("subagent", "attached")
            )
            && (self.task_kind != "subagent" || self.origin_turn_id.is_some());
        if !valid {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(TaskSummary {
            task_thread_id: self.task_thread_id,
            parent_thread_id: self.parent_thread_id,
            root_thread_id: self.root_thread_id,
            origin_turn_id: self.origin_turn_id,
            task_name: self.task_name,
            depth: self.depth,
            task_kind: self.task_kind,
            lifecycle: self.lifecycle,
            state: self.state,
            revision: self.revision,
            latest_activity_sequence: self.latest_activity_sequence,
            unread_count: self.unread_count,
            descendant_count: self.descendant_count,
            running_descendant_count: self.running_descendant_count,
            needs_attention_count: self.needs_attention_count,
            latest_safe_summary: self.latest_safe_summary,
            started_at: self.started_at,
            completed_at: self.completed_at,
            updated_at: self.updated_at,
        })
    }
}

/// 事件投影复用响应侧同一 TaskSummary 闭集，避免 command 与 notification 校验漂移。
pub(crate) fn parse_task_summary_value(value: &Value) -> Result<TaskSummary, RuntimeCommandError> {
    strict_result::<TaskSummaryWire>(value.clone())?.into_domain()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskActivitySummaryWire {
    text: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskActivityWire {
    activity_sequence: u64,
    activity_id: String,
    root_thread_id: String,
    task_thread_id: String,
    actor_thread_id: String,
    #[serde(deserialize_with = "required_nullable")]
    causal_turn_id: Option<String>,
    kind: String,
    summary: TaskActivitySummaryWire,
    created_at: String,
}

impl TaskActivityWire {
    /// activity 只接受低频闭集与有界安全摘要，原始 Tool/reasoning 不得穿越该结构。
    fn into_domain(self) -> Result<TaskActivity, RuntimeCommandError> {
        if !(1..=MAX_SAFE_INTEGER).contains(&self.activity_sequence)
            || !valid_protocol_id(&self.activity_id, "activity_", 105)
            || !valid_protocol_id(&self.root_thread_id, "thr_", 100)
            || !valid_protocol_id(&self.task_thread_id, "thr_", 100)
            || !valid_protocol_id(&self.actor_thread_id, "thr_", 100)
            || !self
                .causal_turn_id
                .as_deref()
                .is_none_or(valid_frozen_turn_id)
            || !matches!(
                self.kind.as_str(),
                "created"
                    | "dispatched"
                    | "message_sent"
                    | "follow_up_queued"
                    | "progress"
                    | "waiting_approval"
                    | "resumed"
                    | "completed"
                    | "failed"
                    | "cancelled"
                    | "suspended"
            )
            || !valid_preview(&self.summary.text)
            || !valid_protocol_timestamp(&self.created_at)
        {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(TaskActivity {
            activity_sequence: self.activity_sequence,
            activity_id: self.activity_id,
            root_thread_id: self.root_thread_id,
            task_thread_id: self.task_thread_id,
            actor_thread_id: self.actor_thread_id,
            causal_turn_id: self.causal_turn_id,
            kind: self.kind,
            summary: self.summary.text,
            created_at: self.created_at,
        })
    }
}

/// task/activity notification 复用详情页 activity 的严格解析与安全摘要边界。
pub(crate) fn parse_task_activity_value(
    value: &Value,
) -> Result<TaskActivity, RuntimeCommandError> {
    strict_result::<TaskActivityWire>(value.clone())?.into_domain()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskMailboxMessageWire {
    mailbox_sequence: u64,
    message_id: String,
    sender_thread_id: String,
    target_thread_id: String,
    #[serde(deserialize_with = "required_nullable")]
    causal_turn_id: Option<String>,
    kind: String,
    content: Value,
    state: String,
    #[serde(deserialize_with = "required_nullable")]
    bound_turn_id: Option<String>,
    created_at: String,
    updated_at: String,
    #[serde(deserialize_with = "required_nullable")]
    consumed_at: Option<String>,
}

impl TaskMailboxMessageWire {
    /// Mailbox 行在返回前校验身份、状态、时间和结构化内容，Rust 不接收内部消费字段。
    fn into_domain(self) -> Result<TaskMailboxMessage, RuntimeCommandError> {
        let content = parse_turn_content(Some(&self.content), "turn_task_mailbox")?;
        if !(1..=MAX_SAFE_INTEGER).contains(&self.mailbox_sequence)
            || !valid_protocol_id(&self.message_id, "msg_", 100)
            || !valid_protocol_id(&self.sender_thread_id, "thr_", 100)
            || !valid_protocol_id(&self.target_thread_id, "thr_", 100)
            || !self
                .causal_turn_id
                .as_deref()
                .is_none_or(valid_frozen_turn_id)
            || !matches!(self.kind.as_str(), "message" | "follow_up" | "final_answer")
            || !matches!(
                self.state.as_str(),
                "pending" | "bound" | "consumed" | "cancelled"
            )
            || !self
                .bound_turn_id
                .as_deref()
                .is_none_or(valid_frozen_turn_id)
            || !valid_protocol_timestamp(&self.created_at)
            || !valid_protocol_timestamp(&self.updated_at)
            || !self
                .consumed_at
                .as_deref()
                .is_none_or(valid_protocol_timestamp)
        {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(TaskMailboxMessage {
            mailbox_sequence: self.mailbox_sequence,
            message_id: self.message_id,
            sender_thread_id: self.sender_thread_id,
            target_thread_id: self.target_thread_id,
            causal_turn_id: self.causal_turn_id,
            kind: self.kind,
            content,
            state: self.state,
            bound_turn_id: self.bound_turn_id,
            created_at: self.created_at,
            updated_at: self.updated_at,
            consumed_at: self.consumed_at,
        })
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskContextSeedWire {
    context_seed_id: String,
    parent_revision: u64,
    inheritance_mode: String,
    task_brief: Value,
    #[serde(deserialize_with = "required_nullable")]
    inherited_context_summary: Option<String>,
    inherited_context_preview: Vec<TaskContextPreviewItemWire>,
    fingerprint: String,
    created_at: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskContextPreviewItemWire {
    role: String,
    #[serde(deserialize_with = "required_nullable")]
    text: Option<String>,
    attachment_ids: Vec<String>,
}

impl TaskContextPreviewItemWire {
    /// 单项预览只接受公开消息角色、最多 512 code points 文本和十个正式附件引用。
    fn into_domain(self) -> Result<TaskContextPreviewItem, RuntimeCommandError> {
        let has_text = self.text.as_deref().is_some_and(|text| !text.is_empty());
        if !matches!(self.role.as_str(), "user" | "assistant")
            || self
                .text
                .as_deref()
                .is_some_and(|text| text.chars().count() > 512 || text.contains('\0'))
            || self.attachment_ids.len() > 10
            || self
                .attachment_ids
                .iter()
                .any(|id| !valid_protocol_id(id, "att_", 128))
            || (!has_text && self.attachment_ids.is_empty())
        {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(TaskContextPreviewItem {
            role: self.role,
            text: self.text,
            attachment_ids: self.attachment_ids,
        })
    }
}

impl TaskContextSeedWire {
    /// seed 响应只允许任务 brief、有界安全预览与 SHA-256 指纹，拒绝原始上下文正文。
    fn into_domain(self) -> Result<TaskContextSeed, RuntimeCommandError> {
        let task_brief = if self.task_brief.is_null() {
            None
        } else {
            Some(parse_turn_content(
                Some(&self.task_brief),
                "turn_task_seed",
            )?)
        };
        if self.inherited_context_preview.len() > 24 {
            return Err(RuntimeCommandError::unavailable());
        }
        let inherited_context_preview = self
            .inherited_context_preview
            .into_iter()
            .map(TaskContextPreviewItemWire::into_domain)
            .collect::<Result<Vec<_>, _>>()?;
        let preview_code_points = inherited_context_preview
            .iter()
            .filter_map(|item| item.text.as_deref())
            .map(|text| text.chars().count())
            .sum::<usize>();
        let inheritance_projection_matches = match self.inheritance_mode.as_str() {
            "effective_context" => self.inherited_context_summary.is_some(),
            "brief_only" => {
                task_brief.is_some()
                    && self.inherited_context_summary.is_none()
                    && inherited_context_preview.is_empty()
            }
            _ => false,
        };
        if !valid_protocol_id(&self.context_seed_id, "seed_", 100)
            || self.parent_revision > MAX_SAFE_INTEGER
            || !inheritance_projection_matches
            || preview_code_points > 4_096
            || !self
                .inherited_context_summary
                .as_deref()
                .is_none_or(valid_preview)
            || self.fingerprint.len() != 64
            || !self
                .fingerprint
                .bytes()
                .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
            || !valid_protocol_timestamp(&self.created_at)
        {
            return Err(RuntimeCommandError::unavailable());
        }
        Ok(TaskContextSeed {
            context_seed_id: self.context_seed_id,
            parent_revision: self.parent_revision,
            inheritance_mode: self.inheritance_mode,
            task_brief,
            inherited_context_summary: self.inherited_context_summary,
            inherited_context_preview,
            fingerprint: self.fingerprint,
            created_at: self.created_at,
        })
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskCreateResultWire {
    accepted: bool,
    task: TaskSummaryWire,
}

/// create 结果必须确认 accepted=true，且 Child identity 仍绑定本次父级和用户任务名。
pub(crate) fn parse_task_create_result(
    value: Value,
    expected_parent_thread_id: &str,
    expected_parent_turn_id: Option<&str>,
    expected_task_name: &str,
) -> Result<TaskCreateResult, RuntimeCommandError> {
    let wire: TaskCreateResultWire = strict_result(value)?;
    if !wire.accepted {
        return Err(RuntimeCommandError::unavailable());
    }
    let task = wire.task.into_domain()?;
    if task.parent_thread_id != expected_parent_thread_id
        || task.origin_turn_id.as_deref() != expected_parent_turn_id
        || task.task_name != expected_task_name
        || task.task_kind != "side_task"
        || task.lifecycle != "independent"
        || task.state != "idle"
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskCreateResult { task })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskListResultWire {
    items: Vec<TaskSummaryWire>,
}

/// list 结果必须形成一棵可从 depth 逐级证明的完整树，拒绝重复、断链和自引用投影。
pub(crate) fn parse_task_list_result(
    value: Value,
    expected_root_thread_id: &str,
) -> Result<TaskListResult, RuntimeCommandError> {
    let wire: TaskListResultWire = strict_result(value)?;
    if wire.items.len() > 64 {
        return Err(RuntimeCommandError::unavailable());
    }
    let items = wire
        .items
        .into_iter()
        .map(TaskSummaryWire::into_domain)
        .collect::<Result<Vec<_>, _>>()?;
    if !valid_task_tree(&items, expected_root_thread_id) {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskListResult { items })
}

/// 以 identity 索引验证父节点和 depth；父 depth 严格减一同时排除了环和跨根链接。
fn valid_task_tree(items: &[TaskSummary], expected_root_thread_id: &str) -> bool {
    let mut identities = HashSet::with_capacity(items.len());
    if items
        .iter()
        .any(|task| !identities.insert(task.task_thread_id.as_str()))
    {
        return false;
    }
    let by_id = items
        .iter()
        .map(|task| (task.task_thread_id.as_str(), task))
        .collect::<HashMap<_, _>>();
    items.iter().all(|task| {
        task.root_thread_id == expected_root_thread_id
            && task.task_thread_id != task.root_thread_id
            && task.task_thread_id != task.parent_thread_id
            && if task.depth == 1 {
                task.parent_thread_id == task.root_thread_id
            } else {
                by_id
                    .get(task.parent_thread_id.as_str())
                    .is_some_and(|parent| {
                        parent.root_thread_id == task.root_thread_id
                            && parent.depth + 1 == task.depth
                    })
            }
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskReadResultWire {
    task: TaskSummaryWire,
    thread: Value,
    context_seed: TaskContextSeedWire,
    activities: Vec<TaskActivityWire>,
    mailbox: Vec<TaskMailboxMessageWire>,
    #[serde(deserialize_with = "required_nullable")]
    next_cursor: Option<String>,
}

/// History parser 已完成 wire 完整性校验；此处只做反腐映射，避免 domain 持有 interface 类型。
fn task_thread_from_history(thread: ThreadDto) -> TaskThreadSummary {
    TaskThreadSummary {
        thread_id: thread.thread_id,
        workspace_id: thread.workspace_id,
        active_goal_id: thread.active_goal_id,
        preferences: thread.preferences.map(|preferences| TaskThreadPreferences {
            provider_id: preferences.provider_id,
            model_id: preferences.model_id,
            reasoning_level: preferences.reasoning_level,
            access_mode: preferences.access_mode,
            collaboration_mode: preferences.collaboration_mode,
            title_source: preferences.title_source,
        }),
        title: thread.title,
        status: thread.status,
        pinned: thread.pinned,
        latest_turn_status: thread.latest_turn_status,
        latest_turn_seen: thread.latest_turn_seen,
        revision: thread.revision,
        created_at: thread.created_at,
        updated_at: thread.updated_at,
    }
}

/// read 结果施加分页上限，复用 History 的 Thread 校验，并验证所有详情行属于本次 Child。
pub(crate) fn parse_task_read_result(
    value: Value,
    expected_task_thread_id: &str,
) -> Result<TaskReadResult, RuntimeCommandError> {
    let wire: TaskReadResultWire = strict_result(value)?;
    if wire.activities.len() > 200
        || wire.mailbox.len() > 200
        || wire
            .next_cursor
            .as_deref()
            .is_some_and(|cursor| !valid_task_cursor(cursor))
    {
        return Err(RuntimeCommandError::unavailable());
    }
    let task = wire.task.into_domain()?;
    let thread = parse_thread(wire.thread)?;
    if thread.thread_id != task.task_thread_id {
        return Err(RuntimeCommandError::unavailable());
    }
    let thread = task_thread_from_history(thread);
    let context_seed = wire.context_seed.into_domain()?;
    let activities = wire
        .activities
        .into_iter()
        .map(TaskActivityWire::into_domain)
        .collect::<Result<Vec<_>, _>>()?;
    let mailbox = wire
        .mailbox
        .into_iter()
        .map(TaskMailboxMessageWire::into_domain)
        .collect::<Result<Vec<_>, _>>()?;
    let inheritance_matches = matches!(
        (
            task.task_kind.as_str(),
            context_seed.inheritance_mode.as_str()
        ),
        ("side_task", "effective_context") | ("subagent", "brief_only")
    );
    let activities_ordered = activities
        .windows(2)
        .all(|pair| pair[0].activity_sequence < pair[1].activity_sequence);
    let mailbox_ordered = mailbox
        .windows(2)
        .all(|pair| pair[0].mailbox_sequence < pair[1].mailbox_sequence);
    if task.task_thread_id != expected_task_thread_id
        || !inheritance_matches
        || !activities_ordered
        || !mailbox_ordered
        || activities.iter().any(|activity| {
            activity.task_thread_id != task.task_thread_id
                || activity.activity_sequence > task.latest_activity_sequence
                || (activity.activity_sequence == task.latest_activity_sequence
                    && task.latest_safe_summary.as_deref() != Some(activity.summary.as_str()))
        })
        || mailbox.iter().any(|message| {
            message.sender_thread_id != task.task_thread_id
                && message.target_thread_id != task.task_thread_id
        })
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskReadResult {
        task,
        thread,
        context_seed,
        activities,
        mailbox,
        next_cursor: wire.next_cursor,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskObserveResultWire {
    observation_id: String,
    task_thread_id: String,
    revision: u64,
}

/// observe 响应必须回显本次 Task/revision，避免把另一 connection handle 交给详情页。
pub(crate) fn parse_task_observe_result(
    value: Value,
    expected_task_thread_id: &str,
    expected_task_revision: u64,
) -> Result<TaskObserveResult, RuntimeCommandError> {
    let wire: TaskObserveResultWire = strict_result(value)?;
    if !valid_protocol_id(&wire.observation_id, "observe_", 103)
        || !valid_protocol_id(&wire.task_thread_id, "thr_", 100)
        || wire.revision > MAX_SAFE_INTEGER
        || wire.task_thread_id != expected_task_thread_id
        || wire.revision != expected_task_revision
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskObserveResult {
        observation_id: wire.observation_id,
        task_thread_id: wire.task_thread_id,
        revision: wire.revision,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AcceptedWire {
    accepted: bool,
}

/// 无投影 mutation 必须得到精确 accepted=true，false 不是可忽略成功。
pub(crate) fn parse_accepted(value: Value) -> Result<(), RuntimeCommandError> {
    let wire: AcceptedWire = strict_result(value)?;
    wire.accepted
        .then_some(())
        .ok_or_else(RuntimeCommandError::unavailable)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskMessageResultWire {
    accepted: bool,
    message_id: String,
    mailbox_sequence: u64,
}

/// message ACK 验证持久 identity/sequence，不把 accepted 当作已消费信号。
fn parse_task_message_result(value: Value) -> Result<TaskMessageResult, RuntimeCommandError> {
    let wire: TaskMessageResultWire = strict_result(value)?;
    if !wire.accepted
        || !valid_protocol_id(&wire.message_id, "msg_", 100)
        || !(1..=MAX_SAFE_INTEGER).contains(&wire.mailbox_sequence)
    {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskMessageResult {
        message_id: wire.message_id,
        mailbox_sequence: wire.mailbox_sequence,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskFollowupResultWire {
    accepted: bool,
    message_id: String,
    turn_id: String,
    task: TaskSummaryWire,
}

/// followup ACK 必须同时包含消息、Child Turn 与同一目标 Task 的最新 projection。
pub(crate) fn parse_task_followup_result(
    value: Value,
    expected_task_thread_id: &str,
) -> Result<TaskFollowupResult, RuntimeCommandError> {
    let wire: TaskFollowupResultWire = strict_result(value)?;
    if !wire.accepted
        || !valid_protocol_id(&wire.message_id, "msg_", 100)
        || !valid_frozen_turn_id(&wire.turn_id)
    {
        return Err(RuntimeCommandError::unavailable());
    }
    let task = wire.task.into_domain()?;
    if task.task_thread_id != expected_task_thread_id {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskFollowupResult {
        message_id: wire.message_id,
        turn_id: wire.turn_id,
        task,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskMutationResultWire {
    accepted: bool,
    task: TaskSummaryWire,
}

/// seen/cancel 必须返回同一目标的最新 projection，不能接纳合法但错配的 Task。
pub(crate) fn parse_task_mutation_result(
    value: Value,
    expected_task_thread_id: &str,
) -> Result<TaskSummary, RuntimeCommandError> {
    let wire: TaskMutationResultWire = strict_result(value)?;
    if !wire.accepted {
        return Err(RuntimeCommandError::unavailable());
    }
    let task = wire.task.into_domain()?;
    if task.task_thread_id != expected_task_thread_id {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(task)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskTreeDeleteResultWire {
    accepted: bool,
    deleted_task_count: u8,
}

/// delete 响应限制为 1..=64 个后代，异常计数不能扩张 renderer 状态。
fn parse_task_tree_delete_result(
    value: Value,
) -> Result<TaskTreeDeleteResult, RuntimeCommandError> {
    let wire: TaskTreeDeleteResultWire = strict_result(value)?;
    if !wire.accepted || !(1..=64).contains(&wire.deleted_task_count) {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskTreeDeleteResult {
        deleted_task_count: wire.deleted_task_count,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct TaskCloseResultWire {
    closed: bool,
}

/// 关闭必须得到严格且为真的终态回执；额外字段或 false 都不能声明本地资源已释放。
pub(crate) fn parse_task_close_result(
    value: Value,
) -> Result<TaskCloseResult, RuntimeCommandError> {
    let wire: TaskCloseResultWire = strict_result(value)?;
    if !wire.closed {
        return Err(RuntimeCommandError::unavailable());
    }
    Ok(TaskCloseResult { closed: true })
}

/// 所有 task 结果使用 deny_unknown_fields wire type 反序列化，避免新增字段静默穿过原生边界。
fn strict_result<T: for<'de> Deserialize<'de>>(value: Value) -> Result<T, RuntimeCommandError> {
    serde_json::from_value(value).map_err(|_| RuntimeCommandError::unavailable())
}

/// required nullable 响应字段缺失时必须失败，不能让 serde 的 `Option` 默认值伪造 null。
fn required_nullable<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}

/// 任务安全摘要沿用 32 KiB/NUL 边界，不把任意控制正文当作 progress 文本。
fn valid_preview(value: &str) -> bool {
    value.len() <= 32_768 && !value.contains('\0')
}
