// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type CollaborationMode = "default" | "plan";
export type GoalStatus = "active" | "paused" | "achieved" | "stopped";
export type GoalPhase =
  | "working"
  | "waiting_approval"
  | "waiting_input"
  | "verifying"
  | "needs_attention"
  | "paused"
  | "achieved"
  | "stopped";
export type PlanStepStatus =
  | "pending"
  | "ready"
  | "running"
  | "blocked"
  | "succeeded"
  | "failed"
  | "skipped";
export type CriterionStatus = "pending" | "met" | "not_met" | "inconclusive";
export type EvaluationVerdict = "met" | "not_met" | "inconclusive";
export type PlanStatus =
  | "draft"
  | "awaiting_approval"
  | "approved"
  | "verifying"
  | "paused"
  | "executing"
  | "completed"
  | "stopped";

export interface GoalSummary {
  readonly goalId: string;
  readonly ownerThreadId: string;
  readonly revision: number;
  readonly status: GoalStatus;
  readonly phase: GoalPhase;
  readonly objective: string;
  readonly goalDefinitionRevision: number;
  readonly acceptanceCriteria: readonly PlanDraftCriterion[];
  readonly activePlanId: string | null;
  readonly activePlanRevisionId: string | null;
  readonly activePlanHash: string | null;
  readonly currentStepId: string | null;
  readonly completedRequiredSteps: number;
  readonly totalRequiredSteps: number;
  readonly attentionSummary: string | null;
  readonly updatedAt: string;
}

export interface PlanStep {
  readonly stepId: string;
  readonly title: string;
  readonly description: string;
  readonly required: boolean;
  readonly dependencyStepIds: readonly string[];
  readonly status: PlanStepStatus;
  readonly blockingReason: string | null;
}

export interface AcceptanceCriterion {
  readonly criterionId: string;
  readonly title?: string;
  readonly description: string;
  readonly required: boolean;
  readonly status: CriterionStatus;
  readonly evidenceIds: readonly string[];
}

export interface PlanRevision {
  readonly planRevisionId: string;
  readonly planId: string;
  readonly revisionNumber: number;
  readonly planHash: string;
  readonly objective: string;
  readonly scope: readonly string[];
  readonly nonGoals: readonly string[];
  readonly constraints: readonly string[];
  readonly dependencies?: readonly string[];
  readonly steps: readonly PlanStep[];
  readonly acceptanceCriteria: readonly AcceptanceCriterion[];
  readonly risks: readonly string[];
  readonly verificationStrategy: readonly string[];
  readonly createdAt: string;
  readonly approvedAt: string | null;
}

export interface PlanDraftStep {
  readonly stepId: string;
  readonly title: string;
  readonly description: string;
  readonly required: boolean;
  readonly dependencyStepIds: readonly string[];
}

export interface PlanDraftCriterion {
  readonly criterionId: string;
  readonly title?: string;
  readonly description: string;
  readonly required: boolean;
}

export interface PlanDraft {
  readonly draftId: string;
  readonly planId: string;
  readonly draftRevision: number;
  readonly basePlanRevisionId: string | null;
  readonly objective: string;
  readonly scope: readonly string[];
  readonly nonGoals: readonly string[];
  readonly constraints: readonly string[];
  readonly dependencies?: readonly string[];
  readonly steps: readonly PlanDraftStep[];
  readonly acceptanceCriteria: readonly PlanDraftCriterion[];
  readonly risks: readonly string[];
  readonly verificationStrategy: readonly string[];
  readonly updatedAt: string;
}

export type EvidenceSource =
  | "tool_result"
  | "test_report"
  | "build_artifact"
  | "repository_state"
  | "ui_assertion"
  | "user_acceptance";

export interface AcceptanceEvidence {
  readonly evidenceId: string;
  readonly goalDefinitionRevision: number | null;
  readonly criterionId: string | null;
  readonly runId: string;
  readonly planRevisionId: string | null;
  readonly source: EvidenceSource;
  readonly sourceId: string;
  readonly summary: string;
  readonly digest: string;
  readonly recordedAt: string;
}

export interface GoalEvaluation {
  readonly evaluationId: string;
  readonly planRevisionId: string | null;
  readonly verdict: EvaluationVerdict;
  readonly summary: string;
  readonly criteria: readonly {
    readonly criterionId: string;
    readonly verdict: CriterionStatus;
    readonly reason: string;
  }[];
  readonly evaluatedAt: string;
}

export interface GoalReadModel {
  readonly goal: GoalSummary;
  /** JA-RPC adapter 提供单调事件序号；不经过传输边界的纯展示模型可不携带。 */
  readonly eventSequence?: number;
  readonly planState: PlanSummary | null;
  readonly planEventSequence?: number;
  readonly plan: PlanRevision | null;
  readonly draft: PlanDraft | null;
  readonly evaluation: GoalEvaluation | null;
}

export interface PlanSummary {
  readonly planId: string;
  readonly ownerThreadId: string;
  readonly objective: string;
  readonly status: PlanStatus;
  readonly revision: number;
  readonly activePlanRevisionId: string | null;
  readonly activeRunId: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface PlanProgress {
  readonly currentStepId: string | null;
  readonly currentStepTitle: string | null;
  readonly completedRequiredSteps: number;
  readonly totalRequiredSteps: number;
}

export interface PlanReadModel {
  readonly plan: PlanSummary;
  readonly progress: PlanProgress;
  readonly revision: PlanRevision | null;
  /** Plan 摘要已前进但新 immutable revision 尚未回读时禁止展示旧正文。 */
  readonly revisionHydrationRequired: boolean;
  readonly draft: PlanDraft | null;
  readonly approvedPlanRevisionId: string | null;
  readonly eventSequence: number;
}

/** 从完整 revision 派生首次读取所需的轻量摘要；后续高频状态由 plan/changed.progress 更新。 */
export function planProgressFromRevision(revision: PlanRevision | null): PlanProgress {
  const steps = revision?.steps ?? [];
  const current = steps.find((step) => step.status === "running" || step.status === "blocked");
  const required = steps.filter((step) => step.required);
  return {
    currentStepId: current?.stepId ?? null,
    currentStepTitle: current?.title ?? null,
    completedRequiredSteps: required.filter((step) => step.status === "succeeded").length,
    totalRequiredSteps: required.length,
  };
}

/** 终态 Goal 进入时间线后释放常驻区域，Composer 只保留可继续处理的目标。 */
export function isGoalActive(goal: GoalSummary): boolean {
  return goal.status !== "achieved" && goal.status !== "stopped";
}

/** 进度只统计必要步骤；空计划显示 0%，避免把尚未批准的目标误呈现为完成。 */
export function goalProgress(goal: GoalSummary): number {
  if (goal.totalRequiredSteps <= 0) return 0;
  return Math.min(1, Math.max(0, goal.completedRequiredSteps / goal.totalRequiredSteps));
}

/** 阶段文案保持简短且可穷举，未知服务端状态必须在协议层失败关闭。 */
export function goalPhaseLabel(phase: GoalPhase): string {
  switch (phase) {
    case "working":
      return "执行中";
    case "waiting_approval":
      return "等待工具审批";
    case "waiting_input":
      return "等待输入";
    case "verifying":
      return "验证中";
    case "needs_attention":
      return "需要处理";
    case "paused":
      return "已暂停";
    case "achieved":
      return "已达成";
    case "stopped":
      return "已停止";
  }
}

/** Plan 生命周期与 Goal 阶段分开呈现，批准不能被误读为已经启动执行。 */
export function planStatusLabel(status: PlanStatus): string {
  switch (status) {
    case "draft":
      return "草稿";
    case "awaiting_approval":
      return "待执行";
    case "approved":
      return "已准备";
    case "verifying":
      return "验证中";
    case "paused":
      return "已暂停";
    case "executing":
      return "执行中";
    case "completed":
      return "已完成";
    case "stopped":
      return "已停止";
  }
}

/** 步骤状态用文字与图标共同表达，不能只依赖颜色区分。 */
export function planStepStatusLabel(status: PlanStepStatus): string {
  switch (status) {
    case "pending":
      return "待处理";
    case "ready":
      return "可执行";
    case "running":
      return "执行中";
    case "blocked":
      return "阻塞";
    case "succeeded":
      return "已完成";
    case "failed":
      return "未通过";
    case "skipped":
      return "已跳过";
  }
}
