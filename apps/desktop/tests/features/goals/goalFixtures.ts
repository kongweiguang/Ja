// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { AcceptanceEvidence, GoalReadModel, PlanRevision, PlanStatus } from "@/features/goals";

export const planRevision: PlanRevision = {
  planRevisionId: "planrev_2",
  planId: "plan_1",
  revisionNumber: 2,
  planHash: "a".repeat(64),
  objective: "交付生产级 Plan 与 Goal",
  scope: ["React 投影", "Goal Workbench"],
  nonGoals: ["发布"],
  constraints: ["Java 是唯一状态 owner"],
  steps: [
    {
      stepId: "step_contract",
      title: "冻结契约",
      description: "完成三端类型闭集",
      required: true,
      dependencyStepIds: [],
      status: "succeeded",
      blockingReason: null,
    },
    {
      stepId: "step_ui",
      title: "实现界面",
      description: "接入结构化计划与证据",
      required: true,
      dependencyStepIds: ["step_contract"],
      status: "running",
      blockingReason: null,
    },
  ],
  acceptanceCriteria: [
    {
      criterionId: "criterion_ui",
      description: "计划编辑与审批形成闭环",
      required: true,
      status: "met",
      evidenceIds: ["evidence_1"],
    },
  ],
  risks: ["崩溃恢复需真窗验证"],
  verificationStrategy: ["运行聚焦组件测试"],
  createdAt: "2026-09-04T10:00:00+08:00",
  approvedAt: null,
};

export const evidence: AcceptanceEvidence = {
  evidenceId: "evidence_1",
  goalDefinitionRevision: 1,
  criterionId: "criterion_ui",
  runId: "run_1",
  planRevisionId: "planrev_2",
  source: "test_report",
  sourceId: "vitest-goals",
  summary: "Goal UI tests passed",
  digest: "b".repeat(64),
  recordedAt: "2026-09-04T10:10:00+08:00",
};

export const goalOnlyEvidence: AcceptanceEvidence = {
  ...evidence,
  evidenceId: "evidence_goal_only",
  criterionId: null,
  planRevisionId: null,
  summary: "Goal-only recovery verified",
};

/** 测试只覆盖 feature projection，不伪造 Provider 或 SQLite 行。 */
export function goalModel(
  phase: GoalReadModel["goal"]["phase"] = "working",
  planStatus: PlanStatus = "awaiting_approval",
): GoalReadModel {
  return {
    goal: {
      goalId: "goal_1",
      ownerThreadId: "thr_1",
      revision: 7,
      status: phase === "paused" || phase === "needs_attention" ? "paused" : "active",
      phase,
      objective: "交付生产级 Plan 与 Goal",
      goalDefinitionRevision: 1,
      acceptanceCriteria: [],
      activePlanId: "plan_1",
      activePlanRevisionId: "planrev_2",
      activePlanHash: "a".repeat(64),
      currentStepId: "step_ui",
      completedRequiredSteps: 1,
      totalRequiredSteps: 2,
      attentionSummary: phase === "needs_attention" ? "验收仍有缺口" : null,
      updatedAt: "2026-09-04T10:10:00+08:00",
    },
    eventSequence: 7,
    planState: {
      planId: "plan_1",
      ownerThreadId: "thr_1",
      objective: "交付生产级 Plan 与 Goal",
      status: planStatus,
      revision: 3,
      activePlanRevisionId: planStatus === "approved" ? "planrev_2" : null,
      activeRunId: null,
      createdAt: "2026-09-04T10:00:00+08:00",
      updatedAt: "2026-09-04T10:10:00+08:00",
    },
    planEventSequence: 3,
    plan: planRevision,
    draft: null,
    evaluation:
      phase === "needs_attention"
        ? {
            evaluationId: "evaluation_1",
            planRevisionId: "planrev_2",
            verdict: "not_met",
            summary: "真窗恢复尚未验收",
            criteria: [
              { criterionId: "criterion_ui", verdict: "not_met", reason: "缺少运行时证据" },
            ],
            evaluatedAt: "2026-09-04T10:12:00+08:00",
          }
        : null,
  };
}
