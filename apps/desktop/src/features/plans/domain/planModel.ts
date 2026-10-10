// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type CollaborationMode = "default" | "plan";
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
  readonly criterionId: string | null;
  readonly runId: string;
  readonly planRevisionId: string | null;
  readonly source: EvidenceSource;
  readonly sourceId: string;
  readonly summary: string;
  readonly digest: string;
  readonly recordedAt: string;
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

/** Plan 生命周期明确区分定稿与执行，批准不能被误读为已经启动。 */
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
