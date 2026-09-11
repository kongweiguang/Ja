// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! Goal/Plan 的类型化 Tauri command 边界。

use crate::app_runtime::{GoalMethod, GoalPayload, GoalRequest, RuntimeCommandError, RuntimeHost};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

const MAX_SAFE_INTEGER: u64 = 9_007_199_254_740_991;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalIdInput {
    pub goal_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalPageInput {
    pub goal_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanIdInput {
    pub thread_id: String,
    pub plan_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanCurrentReadInput {
    pub thread_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanPageInput {
    pub thread_id: String,
    pub plan_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalEvidenceListInput {
    pub goal_id: String,
    pub goal_definition_revision: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plan_revision_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalUnobserveInput {
    pub observation_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalMutationInput {
    pub goal_id: String,
    pub expected_goal_revision: u64,
    pub idempotency_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanMutationInput {
    pub thread_id: String,
    pub plan_id: String,
    pub expected_plan_revision: u64,
    pub idempotency_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanControlInput {
    pub thread_id: String,
    pub plan_id: String,
    pub expected_plan_revision: u64,
    pub run_id: String,
    pub idempotency_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanEvidenceListInput {
    pub thread_id: String,
    pub plan_id: String,
    pub plan_revision_id: String,
    pub run_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionReadInput {
    pub thread_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionObserveInput {
    pub thread_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionUnobserveInput {
    pub observation_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionAnswerDto {
    pub question_id: String,
    pub option_ids: Vec<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub free_text: Option<String>,
    pub skipped: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionDraftSaveInput {
    pub thread_id: String,
    pub request_id: String,
    pub expected_draft_revision: u64,
    pub idempotency_key: String,
    pub answers: Vec<InteractionAnswerDto>,
    pub page: u32,
    pub collapsed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionRespondInput {
    pub thread_id: String,
    pub request_id: String,
    pub expected_revision: u64,
    pub idempotency_key: String,
    pub answers: Vec<InteractionAnswerDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionCancelInput {
    pub thread_id: String,
    pub request_id: String,
    pub expected_revision: u64,
    pub idempotency_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalPlanAttachInput {
    pub goal_id: String,
    pub expected_goal_revision: u64,
    pub idempotency_key: String,
    pub plan_id: String,
    pub plan_revision_id: String,
    pub plan_hash: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(
    tag = "kind",
    rename_all = "snake_case",
    rename_all_fields = "camelCase",
    deny_unknown_fields
)]
pub enum GoalOwnerDto {
    Thread { thread_id: String },
    IndependentTask { task_thread_id: String },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanOwnerDto {
    pub kind: PlanOwnerKindDto,
    pub thread_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanOwnerKindDto {
    Thread,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalCreateInput {
    pub owner: GoalOwnerDto,
    pub objective: String,
    pub acceptance_criteria: Vec<AcceptanceCriterionDto>,
    pub expected_goal_revision: u64,
    pub idempotency_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanCreateInput {
    pub owner: PlanOwnerDto,
    pub objective: String,
    pub expected_thread_revision: u64,
    pub idempotency_key: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AcceptanceCriterionDto {
    pub criterion_id: String,
    pub description: String,
    pub required: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanStepDto {
    pub step_id: String,
    pub title: String,
    pub description: String,
    pub required: bool,
    pub depends_on: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanDefinitionDto {
    pub objective: String,
    pub scope: Vec<String>,
    pub non_goals: Vec<String>,
    pub constraints: Vec<String>,
    pub acceptance_criteria: Vec<AcceptanceCriterionDto>,
    pub steps: Vec<PlanStepDto>,
    pub dependencies: Vec<String>,
    pub risks: Vec<String>,
    pub verification_strategy: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanDraftSaveInput {
    pub thread_id: String,
    pub plan_id: String,
    pub expected_plan_revision: u64,
    pub idempotency_key: String,
    pub draft: PlanDefinitionDto,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanExecuteInput {
    pub thread_id: String,
    pub plan_id: String,
    pub expected_plan_revision: u64,
    pub idempotency_key: String,
    pub plan_revision_id: String,
    pub plan_hash: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanRejectInput {
    pub thread_id: String,
    pub plan_id: String,
    pub expected_plan_revision: u64,
    pub idempotency_key: String,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionOptionDto {
    pub option_id: String,
    pub label: String,
    pub description: String,
    pub recommended: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionQuestionDto {
    pub question_id: String,
    pub prompt: String,
    #[serde(rename = "type")]
    pub question_type: String,
    pub required: bool,
    pub allow_free_text: bool,
    pub options: Vec<InteractionOptionDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionRequestDto {
    pub request_id: String,
    pub thread_id: String,
    #[serde(deserialize_with = "required_nullable")]
    pub turn_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub tool_call_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub plan_revision_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub run_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub goal_id: Option<String>,
    pub status: String,
    pub revision: u64,
    pub questions: Vec<InteractionQuestionDto>,
    pub answers: Vec<InteractionAnswerDto>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionDraftDto {
    pub thread_id: String,
    pub request_id: String,
    pub answers: Vec<InteractionAnswerDto>,
    pub page: u32,
    pub collapsed: bool,
    pub revision: u64,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionSnapshotDto {
    pub thread_id: String,
    pub event_sequence: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub request: Option<InteractionRequestDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub draft: Option<InteractionDraftDto>,
    pub resume_state: InteractionResumeStateDto,
}

/// 恢复状态来自 Java 同事务投影，不能把已回答误认为模型已继续执行。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum InteractionResumeStateDto {
    None,
    WaitingForAnswer,
    WaitingToResume,
    Resuming,
    Settled,
    Closed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct InteractionObserveResultDto {
    #[serde(flatten)]
    pub snapshot: InteractionSnapshotDto,
    pub observation_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoalStatusDto {
    Active,
    Paused,
    Achieved,
    Stopped,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoalPhaseDto {
    Working,
    WaitingApproval,
    WaitingInput,
    Verifying,
    NeedsAttention,
    Paused,
    Achieved,
    Stopped,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalDto {
    pub goal_id: String,
    pub owner: GoalOwnerDto,
    pub objective: String,
    pub goal_definition_revision: u64,
    pub acceptance_criteria: Vec<AcceptanceCriterionDto>,
    pub status: GoalStatusDto,
    pub phase: GoalPhaseDto,
    pub revision: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub plan_link: Option<GoalPlanLinkDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub current_run_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub current_step_id: Option<String>,
    pub completed_required_steps: u64,
    pub total_required_steps: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub attention_reason: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub latest_evaluation: Option<GoalEvaluationDto>,
    pub created_at: String,
    pub updated_at: String,
    #[serde(deserialize_with = "required_nullable")]
    pub achieved_at: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub stopped_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalPlanLinkDto {
    pub plan_id: String,
    pub plan_revision_id: String,
    pub plan_hash: String,
    pub link_revision: u64,
    pub attached_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanStatusDto {
    Draft,
    AwaitingApproval,
    Approved,
    Executing,
    Verifying,
    Paused,
    Completed,
    Stopped,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanDto {
    pub plan_id: String,
    pub owner: PlanOwnerDto,
    pub objective: String,
    pub status: PlanStatusDto,
    pub revision: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub active_plan_revision_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub active_run_id: Option<String>,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanRevisionDto {
    #[serde(flatten)]
    pub definition: PlanDefinitionDto,
    pub plan_revision_id: String,
    pub plan_id: String,
    pub revision_number: u64,
    pub plan_hash: String,
    pub created_by: PlanRevisionAuthorDto,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanRevisionAuthorDto {
    Agent,
    UserUi,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanDraftDto {
    #[serde(flatten)]
    pub definition: PlanDefinitionDto,
    pub plan_draft_id: String,
    pub plan_id: String,
    pub draft_revision: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub base_plan_revision_id: Option<String>,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanApprovalDto {
    pub approval_id: String,
    pub plan_id: String,
    pub plan_revision_id: String,
    pub plan_hash: String,
    pub approved_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanStepStatusDto {
    Pending,
    Ready,
    Running,
    Blocked,
    Succeeded,
    Failed,
    Skipped,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanStepExecutionDto {
    pub step_id: String,
    pub run_id: String,
    pub status: PlanStepStatusDto,
    pub attempt: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub failure_signature: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub summary: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub started_at: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub completed_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvaluationVerdictDto {
    Met,
    NotMet,
    Inconclusive,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CriterionEvaluationDto {
    pub criterion_id: String,
    pub verdict: EvaluationVerdictDto,
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalEvaluationDto {
    pub evaluation_id: String,
    pub goal_id: String,
    pub goal_definition_revision: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub plan_revision_id: Option<String>,
    pub run_id: String,
    pub verdict: EvaluationVerdictDto,
    pub criteria: Vec<CriterionEvaluationDto>,
    pub summary: String,
    pub completed_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanProjectionDto {
    pub plan: PlanDto,
    #[serde(deserialize_with = "required_nullable")]
    pub draft: Option<PlanDraftDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub current_revision: Option<PlanRevisionDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub approval: Option<PlanApprovalDto>,
    pub step_executions: Vec<PlanStepExecutionDto>,
    pub event_sequence: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanCurrentReadResultDto {
    #[serde(deserialize_with = "required_nullable")]
    pub current: Option<PlanProjectionDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanObserveResultDto {
    #[serde(flatten)]
    pub projection: PlanProjectionDto,
    pub observation_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalProjectionResultDto {
    pub goal: GoalDto,
    pub event_sequence: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalObserveResultDto {
    pub observation_id: String,
    pub goal: GoalDto,
    pub event_sequence: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AcceptedResultDto {
    pub accepted: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoalEventKindDto {
    Created,
    PlanAttached,
    PlanDetached,
    RunStarted,
    ToolApprovalRequested,
    ToolApprovalResolved,
    StepChanged,
    EvidenceAdded,
    ContinuationNoProgress,
    EvaluationStarted,
    EvaluationCompleted,
    Paused,
    Resumed,
    Stopped,
    Achieved,
    RecoveryRequired,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalEventItemDto {
    pub event_sequence: u64,
    pub kind: GoalEventKindDto,
    pub summary: String,
    pub occurred_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalEventsResultDto {
    pub goal_id: String,
    pub goal_revision: u64,
    pub event_sequence: u64,
    pub items: Vec<GoalEventItemDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanRevisionsResultDto {
    pub plan_id: String,
    pub plan_revision: u64,
    pub event_sequence: u64,
    pub items: Vec<PlanRevisionDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceSourceDto {
    ToolResult,
    TestReport,
    BuildArtifact,
    RepositoryState,
    UiAssertion,
    UserAcceptance,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AcceptanceEvidenceDto {
    pub evidence_id: String,
    #[serde(deserialize_with = "required_nullable")]
    pub goal_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub plan_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub goal_definition_revision: Option<u64>,
    pub run_id: String,
    #[serde(deserialize_with = "required_nullable")]
    pub plan_revision_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub criterion_id: Option<String>,
    #[serde(deserialize_with = "required_nullable")]
    pub step_id: Option<String>,
    pub source_type: EvidenceSourceDto,
    pub source_id: String,
    pub summary: String,
    pub digest: String,
    pub observed_at: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GoalEvidenceResultDto {
    pub goal_id: String,
    pub goal_revision: u64,
    pub goal_definition_revision: u64,
    #[serde(deserialize_with = "required_nullable")]
    pub plan_revision_id: Option<String>,
    pub event_sequence: u64,
    pub items: Vec<AcceptanceEvidenceDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PlanEvidenceResultDto {
    pub plan_id: String,
    pub plan_revision: u64,
    pub event_sequence: u64,
    pub plan_revision_id: String,
    pub run_id: String,
    pub items: Vec<AcceptanceEvidenceDto>,
    #[serde(deserialize_with = "required_nullable")]
    pub next_cursor: Option<String>,
}

/// required-nullable 字段必须显式出现在 JA-RPC 响应中，避免 serde 的 Option 缺省放宽严格 schema。
fn required_nullable<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}

/// 所有 Goal command 在同一 helper 中固定 request/result method identity，并拒绝错配响应。
fn request_goal<I: Serialize, O: DeserializeOwned>(
    host: &RuntimeHost,
    method: GoalMethod,
    input: I,
) -> Result<O, RuntimeCommandError> {
    let payload = GoalPayload::try_new(
        serde_json::to_vec(&input).map_err(|_| RuntimeCommandError::invalid_params())?,
    )?;
    let response = host.goal_request(GoalRequest { method, payload })?;
    if response.method != method {
        return Err(RuntimeCommandError::unavailable());
    }
    serde_json::from_slice(&response.payload.into_bytes())
        .map_err(|_| RuntimeCommandError::unavailable())
}

/// mutation 的 CAS 与幂等键统一准入，避免 Goal/Plan 分拆后任一命令绕过并发保护。
fn validate_mutation_boundary(revision: u64, idempotency: &str) -> Result<(), RuntimeCommandError> {
    if revision > MAX_SAFE_INTEGER || {
        let value = idempotency;
        value.len() < 8
            || value.len() > 128
            || !value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || b"._:-".contains(&byte))
    } {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// Goal identity 单独校验，禁止 Plan command 通过相似前缀进入错误聚合。
fn validate_goal_identity(goal_id: &str) -> Result<(), RuntimeCommandError> {
    valid_id(goal_id, "goal_", 101)
        .then_some(())
        .ok_or_else(RuntimeCommandError::invalid_params)
}

/// standalone Plan 始终绑定 owner Thread 与 Plan identity，Rust 不推断 Goal link。
fn validate_plan_identity(thread_id: &str, plan_id: &str) -> Result<(), RuntimeCommandError> {
    if valid_id(thread_id, "thr_", 100) && valid_id(plan_id, "plan_", 101) {
        Ok(())
    } else {
        Err(RuntimeCommandError::invalid_params())
    }
}

/// 结构化计划在 native 边界证明 identity 唯一、依赖存在且无环；权威 canonical hash 仍由 Java 计算。
pub(crate) fn validate_plan(plan: &PlanDefinitionDto) -> Result<(), RuntimeCommandError> {
    if plan.objective.is_empty()
        || plan.acceptance_criteria.is_empty()
        || plan.steps.is_empty()
        || plan.verification_strategy.is_empty()
        || plan.steps.len() > 256
        || plan.acceptance_criteria.len() > 256
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    let step_ids = plan
        .steps
        .iter()
        .map(|step| step.step_id.as_str())
        .collect::<HashSet<_>>();
    let criterion_ids = plan
        .acceptance_criteria
        .iter()
        .map(|item| item.criterion_id.as_str())
        .collect::<HashSet<_>>();
    if step_ids.len() != plan.steps.len()
        || criterion_ids.len() != plan.acceptance_criteria.len()
        || plan.steps.iter().any(|step| {
            !valid_id(&step.step_id, "step_", 101)
                || step
                    .depends_on
                    .iter()
                    .any(|id| id == &step.step_id || !step_ids.contains(id.as_str()))
        })
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    let dependencies = plan
        .steps
        .iter()
        .map(|step| {
            (
                step.step_id.as_str(),
                step.depends_on
                    .iter()
                    .map(String::as_str)
                    .collect::<Vec<_>>(),
            )
        })
        .collect::<HashMap<_, _>>();
    fn visit<'a>(
        id: &'a str,
        graph: &HashMap<&'a str, Vec<&'a str>>,
        visiting: &mut HashSet<&'a str>,
        done: &mut HashSet<&'a str>,
    ) -> bool {
        if done.contains(id) {
            return true;
        }
        if !visiting.insert(id) {
            return false;
        }
        if graph
            .get(id)
            .is_some_and(|items| items.iter().any(|next| !visit(next, graph, visiting, done)))
        {
            return false;
        }
        visiting.remove(id);
        done.insert(id);
        true
    }
    let mut visiting = HashSet::new();
    let mut done = HashSet::new();
    if !dependencies
        .keys()
        .all(|id| visit(id, &dependencies, &mut visiting, &mut done))
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    Ok(())
}

/// protocol identity 只接受 ASCII 且限制总长，防止路径、控制字符和跨协议别名进入 Java。
fn valid_id(value: &str, prefix: &str, max: usize) -> bool {
    let Some(suffix) = value.strip_prefix(prefix) else {
        return false;
    };
    value.len() <= max
        && !suffix.is_empty()
        && suffix
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

macro_rules! goal_query {
    ($name:ident, $input:ty, $output:ty, $method:expr, $validate:expr) => {
        #[tauri::command]
        pub async fn $name(
            input: $input,
            state: tauri::State<'_, RuntimeHost>,
        ) -> Result<$output, RuntimeCommandError> {
            ($validate)(&input)?;
            let host = state.inner().clone();
            super::runtime::run_blocking(move || request_goal(&host, $method, input)).await
        }
    };
}

/// 查询仅接受冻结 Goal identity，禁止把分页或 mutation 字段混入只读入口。
fn validate_id_input(input: &GoalIdInput) -> Result<(), RuntimeCommandError> {
    validate_goal_identity(&input.goal_id)
}
/// 分页查询共享 200 项上限，避免不同 Goal 资源产生不一致的内存预算。
fn validate_page(input: &GoalPageInput) -> Result<(), RuntimeCommandError> {
    validate_goal_identity(&input.goal_id)?;
    if input.limit.is_some_and(|v| v == 0 || v > 200) {
        Err(RuntimeCommandError::invalid_params())
    } else {
        Ok(())
    }
}
/// Plan 查询同时绑定 owner Thread，避免相同 Plan ID 被跨 Thread 读取。
fn validate_plan_id_input(input: &PlanIdInput) -> Result<(), RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)
}
/// 线程级恢复只验证 Thread identity；Plan 是否存在由 Java owner 以 nullable projection 表达。
fn validate_plan_current_read(input: &PlanCurrentReadInput) -> Result<(), RuntimeCommandError> {
    if valid_id(&input.thread_id, "thr_", 100) {
        Ok(())
    } else {
        Err(RuntimeCommandError::invalid_params())
    }
}
/// Plan revision 分页沿用查询 identity 与统一 200 项预算。
fn validate_plan_page(input: &PlanPageInput) -> Result<(), RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)?;
    validate_page_limit(input.limit)
}
/// 证据读取固定 Goal definition；Plan revision 仅为可选过滤，不再强制 Goal 必须绑定 Plan。
fn validate_evidence(input: &GoalEvidenceListInput) -> Result<(), RuntimeCommandError> {
    validate_goal_identity(&input.goal_id)?;
    if input.goal_definition_revision == 0
        || input.goal_definition_revision > MAX_SAFE_INTEGER
        || input
            .plan_revision_id
            .as_deref()
            .is_some_and(|value| !valid_id(value, "planrev_", 104))
    {
        Err(RuntimeCommandError::invalid_params())
    } else {
        validate_page_limit(input.limit)
    }
}
/// 所有分页入口共享同一上限，避免某种聚合绕过 bridge 内存预算。
fn validate_page_limit(limit: Option<u32>) -> Result<(), RuntimeCommandError> {
    if limit.is_some_and(|value| value == 0 || value > 200) {
        Err(RuntimeCommandError::invalid_params())
    } else {
        Ok(())
    }
}
/// 取消观察只接受服务端签发的 observation identity，不允许按 Goal 模糊取消。
fn validate_unobserve(input: &GoalUnobserveInput) -> Result<(), RuntimeCommandError> {
    if valid_id(&input.observation_id, "observe_", 103) {
        Ok(())
    } else {
        Err(RuntimeCommandError::invalid_params())
    }
}
/// 所有 mutation 共用 revision CAS 与幂等键，避免某个控制动作绕过并发保护。
fn validate_mutation(input: &GoalMutationInput) -> Result<(), RuntimeCommandError> {
    validate_goal_identity(&input.goal_id)?;
    validate_mutation_boundary(input.expected_goal_revision, &input.idempotency_key)
}
/// Plan mutation 使用独立 revision CAS，禁止旧 expectedGoalRevision 别名继续生效。
fn validate_plan_mutation(input: &PlanMutationInput) -> Result<(), RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)?;
    validate_mutation_boundary(input.expected_plan_revision, &input.idempotency_key)
}

/// Plan 控制绑定执行中的 run identity；陈旧窗口不能暂停或恢复新一轮执行。
fn validate_plan_control(input: &PlanControlInput) -> Result<(), RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)?;
    if !valid_id(&input.run_id, "run_", 100) {
        return Err(RuntimeCommandError::invalid_params());
    }
    validate_mutation_boundary(input.expected_plan_revision, &input.idempotency_key)
}

/// Plan evidence 查询固定 revision/run，避免把其它执行轮的验收事实拼入当前计划。
fn validate_plan_evidence(input: &PlanEvidenceListInput) -> Result<(), RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)?;
    if !valid_id(&input.plan_revision_id, "planrev_", 104) || !valid_id(&input.run_id, "run_", 100)
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    validate_page_limit(input.limit)
}

/// Interaction read/observe 只接受线程归属，requestId 缺省表示读取当前活动问题。
fn validate_interaction_identity(
    thread_id: &str,
    request_id: Option<&str>,
) -> Result<(), RuntimeCommandError> {
    if !valid_id(thread_id, "thr_", 100)
        || request_id.is_some_and(|value| !valid_id(value, "interaction_", 128))
    {
        Err(RuntimeCommandError::invalid_params())
    } else {
        Ok(())
    }
}

/// Interaction 答案只允许有限题数、页码和文本容量，避免 WebView 伪造超大恢复载荷。
fn validate_interaction_answers(
    answers: &[InteractionAnswerDto],
    page: Option<u32>,
) -> Result<(), RuntimeCommandError> {
    if answers.len() > 3
        || page.is_some_and(|value| value > 2)
        || answers.iter().any(|answer| {
            !valid_id(&answer.question_id, "question_", 128)
                || answer.option_ids.len() > 32
                || answer
                    .option_ids
                    .iter()
                    .any(|value| !valid_id(value, "option_", 128))
                || answer
                    .free_text
                    .as_deref()
                    .is_some_and(|value| value.len() > 16_000)
                || (answer.skipped && (!answer.option_ids.is_empty() || answer.free_text.is_some()))
        })
    {
        Err(RuntimeCommandError::invalid_params())
    } else {
        Ok(())
    }
}

/// 批准与执行共享精确 revision/hash 绑定；full_access 也不能绕过这条独立门禁。
fn validate_plan_execution(input: &PlanExecuteInput) -> Result<(), RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)?;
    validate_mutation_boundary(input.expected_plan_revision, &input.idempotency_key)?;
    if valid_id(&input.plan_revision_id, "planrev_", 104)
        && input.plan_hash.len() == 64
        && input
            .plan_hash
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        Ok(())
    } else {
        Err(RuntimeCommandError::invalid_params())
    }
}

goal_query!(
    ja_runtime_goal_read,
    GoalIdInput,
    GoalProjectionResultDto,
    GoalMethod::GoalRead,
    validate_id_input
);
goal_query!(
    ja_runtime_goal_events_read,
    GoalPageInput,
    GoalEventsResultDto,
    GoalMethod::GoalEventsRead,
    validate_page
);
goal_query!(
    ja_runtime_goal_observe,
    GoalIdInput,
    GoalObserveResultDto,
    GoalMethod::GoalObserve,
    validate_id_input
);
goal_query!(
    ja_runtime_goal_unobserve,
    GoalUnobserveInput,
    AcceptedResultDto,
    GoalMethod::GoalUnobserve,
    validate_unobserve
);
goal_query!(
    ja_runtime_plan_read,
    PlanIdInput,
    PlanProjectionDto,
    GoalMethod::PlanRead,
    validate_plan_id_input
);
goal_query!(
    ja_runtime_plan_current_read,
    PlanCurrentReadInput,
    PlanCurrentReadResultDto,
    GoalMethod::PlanCurrentRead,
    validate_plan_current_read
);
goal_query!(
    ja_runtime_plan_revisions_list,
    PlanPageInput,
    PlanRevisionsResultDto,
    GoalMethod::PlanRevisionsList,
    validate_plan_page
);
goal_query!(
    ja_runtime_goal_evidence_list,
    GoalEvidenceListInput,
    GoalEvidenceResultDto,
    GoalMethod::GoalEvidenceList,
    validate_evidence
);
goal_query!(
    ja_runtime_goal_pause,
    GoalMutationInput,
    GoalProjectionResultDto,
    GoalMethod::GoalPause,
    validate_mutation
);
goal_query!(
    ja_runtime_goal_resume,
    GoalMutationInput,
    GoalProjectionResultDto,
    GoalMethod::GoalResume,
    validate_mutation
);
goal_query!(
    ja_runtime_goal_stop,
    GoalMutationInput,
    GoalProjectionResultDto,
    GoalMethod::GoalStop,
    validate_mutation
);
goal_query!(
    ja_runtime_goal_plan_detach,
    GoalMutationInput,
    GoalProjectionResultDto,
    GoalMethod::GoalPlanDetach,
    validate_mutation
);
goal_query!(
    ja_runtime_plan_draft_discard,
    PlanMutationInput,
    PlanProjectionDto,
    GoalMethod::PlanDraftDiscard,
    validate_plan_mutation
);
goal_query!(
    ja_runtime_plan_propose,
    PlanMutationInput,
    PlanProjectionDto,
    GoalMethod::PlanPropose,
    validate_plan_mutation
);
goal_query!(
    ja_runtime_plan_observe,
    PlanIdInput,
    PlanObserveResultDto,
    GoalMethod::PlanObserve,
    validate_plan_id_input
);
goal_query!(
    ja_runtime_plan_unobserve,
    GoalUnobserveInput,
    AcceptedResultDto,
    GoalMethod::PlanUnobserve,
    validate_unobserve
);
goal_query!(
    ja_runtime_plan_events_read,
    PlanPageInput,
    PlanRevisionsResultDto,
    GoalMethod::PlanEventsRead,
    validate_plan_page
);
goal_query!(
    ja_runtime_plan_evidence_list,
    PlanEvidenceListInput,
    PlanEvidenceResultDto,
    GoalMethod::PlanEvidenceList,
    validate_plan_evidence
);
goal_query!(
    ja_runtime_plan_pause,
    PlanControlInput,
    PlanProjectionDto,
    GoalMethod::PlanPause,
    validate_plan_control
);
goal_query!(
    ja_runtime_plan_resume,
    PlanControlInput,
    PlanProjectionDto,
    GoalMethod::PlanResume,
    validate_plan_control
);
goal_query!(
    ja_runtime_plan_stop,
    PlanControlInput,
    PlanProjectionDto,
    GoalMethod::PlanStop,
    validate_plan_control
);

/// create 必须从 revision 0 开始，Goal owner identity 不在 Rust 转换成另一种拥有者。
#[tauri::command]
pub async fn ja_runtime_goal_create(
    input: GoalCreateInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<GoalProjectionResultDto, RuntimeCommandError> {
    if input.expected_goal_revision != 0
        || input.objective.is_empty()
        || input.objective.len() > 32_768
        || input.acceptance_criteria.len() > 256
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    let owner_id = match &input.owner {
        GoalOwnerDto::Thread { thread_id } => thread_id,
        GoalOwnerDto::IndependentTask { task_thread_id } => task_thread_id,
    };
    if !valid_id(owner_id, "thr_", 100) {
        return Err(RuntimeCommandError::invalid_params());
    }
    validate_mutation_boundary(0, &input.idempotency_key)?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::GoalCreate, input)).await
}

/// Goal attach 只传递已批准 Plan 的冻结 identity，不在 Rust 创建或复用 standalone run。
#[tauri::command]
pub async fn ja_runtime_goal_plan_attach(
    input: GoalPlanAttachInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<GoalProjectionResultDto, RuntimeCommandError> {
    validate_goal_identity(&input.goal_id)?;
    validate_mutation_boundary(input.expected_goal_revision, &input.idempotency_key)?;
    if !valid_id(&input.plan_id, "plan_", 101)
        || !valid_id(&input.plan_revision_id, "planrev_", 104)
        || input.plan_hash.len() != 64
        || !input
            .plan_hash
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::GoalPlanAttach, input))
        .await
}

/// standalone Plan 创建仅绑定 Thread revision，不隐式创建 Goal 或用户消息。
#[tauri::command]
pub async fn ja_runtime_plan_create(
    input: PlanCreateInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<PlanProjectionDto, RuntimeCommandError> {
    if !matches!(input.owner.kind, PlanOwnerKindDto::Thread)
        || !valid_id(&input.owner.thread_id, "thr_", 100)
        || input.objective.is_empty()
        || input.objective.len() > 32_768
    {
        return Err(RuntimeCommandError::invalid_params());
    }
    validate_mutation_boundary(input.expected_thread_revision, &input.idempotency_key)?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::PlanCreate, input)).await
}

/// Interaction read 允许 requestId 省略，以恢复线程当前活动问题。
#[tauri::command]
pub async fn ja_runtime_interaction_read(
    input: InteractionReadInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<InteractionSnapshotDto, RuntimeCommandError> {
    validate_interaction_identity(&input.thread_id, input.request_id.as_deref())?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::InteractionRead, input))
        .await
}

/// observe 建立服务端订阅并返回初始快照，不能用轮询代替 observation 生命周期。
#[tauri::command]
pub async fn ja_runtime_interaction_observe(
    input: InteractionObserveInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<InteractionObserveResultDto, RuntimeCommandError> {
    validate_interaction_identity(&input.thread_id, None)?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::InteractionObserve, input))
        .await
}

/// unobserve 只释放 observation handle，不取消或改变待回答问题。
#[tauri::command]
pub async fn ja_runtime_interaction_unobserve(
    input: InteractionUnobserveInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<AcceptedResultDto, RuntimeCommandError> {
    if !valid_id(&input.observation_id, "observe_", 103) {
        return Err(RuntimeCommandError::invalid_params());
    }
    let host = state.inner().clone();
    super::runtime::run_blocking(move || {
        request_goal(&host, GoalMethod::InteractionUnobserve, input)
    })
    .await
}

/// 草稿保存只提交可恢复 UI 状态，答案真正生效仍须经过 interaction/respond。
#[tauri::command]
pub async fn ja_runtime_interaction_draft_save(
    input: InteractionDraftSaveInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<InteractionSnapshotDto, RuntimeCommandError> {
    validate_interaction_identity(&input.thread_id, Some(&input.request_id))?;
    validate_mutation_boundary(input.expected_draft_revision, &input.idempotency_key)?;
    validate_interaction_answers(&input.answers, Some(input.page))?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || {
        request_goal(&host, GoalMethod::InteractionDraftSave, input)
    })
    .await
}

/// respond 由 Java 同一事务完成 CAS、ToolResult 结算和恢复调度，迟到回答不会复活旧请求。
#[tauri::command]
pub async fn ja_runtime_interaction_respond(
    input: InteractionRespondInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<InteractionSnapshotDto, RuntimeCommandError> {
    validate_interaction_identity(&input.thread_id, Some(&input.request_id))?;
    validate_mutation_boundary(input.expected_revision, &input.idempotency_key)?;
    validate_interaction_answers(&input.answers, None)?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::InteractionRespond, input))
        .await
}

/// cancel 只取消当前问题，不将用户未答内容解释为默认同意或答案。
#[tauri::command]
pub async fn ja_runtime_interaction_cancel(
    input: InteractionCancelInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<InteractionSnapshotDto, RuntimeCommandError> {
    validate_interaction_identity(&input.thread_id, Some(&input.request_id))?;
    validate_mutation_boundary(input.expected_revision, &input.idempotency_key)?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::InteractionCancel, input))
        .await
}

/// draft 保存前验证稳定 identity 与 DAG；canonical JSON/hash 只在 Java proposal 事务生成。
#[tauri::command]
pub async fn ja_runtime_plan_draft_save(
    input: PlanDraftSaveInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<PlanProjectionDto, RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)?;
    validate_mutation_boundary(input.expected_plan_revision, &input.idempotency_key)?;
    validate_plan(&input.draft)?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::PlanDraftSave, input))
        .await
}

/// execute 同时校验 revision/hash、记录用户意图并创建唯一 Plan-owned Run。
#[tauri::command]
pub async fn ja_runtime_plan_execute(
    input: PlanExecuteInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<PlanProjectionDto, RuntimeCommandError> {
    validate_plan_execution(&input)?;
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::PlanExecute, input)).await
}

/// reject 需要明确原因，避免将 dismiss 误解释成计划否决。
#[tauri::command]
pub async fn ja_runtime_plan_reject(
    input: PlanRejectInput,
    state: tauri::State<'_, RuntimeHost>,
) -> Result<PlanProjectionDto, RuntimeCommandError> {
    validate_plan_identity(&input.thread_id, &input.plan_id)?;
    validate_mutation_boundary(input.expected_plan_revision, &input.idempotency_key)?;
    if input.reason.is_empty() || input.reason.len() > 32_768 {
        return Err(RuntimeCommandError::invalid_params());
    }
    let host = state.inner().clone();
    super::runtime::run_blocking(move || request_goal(&host, GoalMethod::PlanReject, input)).await
}
