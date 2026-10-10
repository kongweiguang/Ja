// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  AcceptanceEvidence,
  PlanReadModel,
  PlanRevision,
  PlanStatus,
  PlanSummary,
} from "@/features/plans";
import { planProgressFromRevision } from "@/features/plans";

export const planRevision: PlanRevision = {
  planRevisionId: "planrev_2",
  planId: "plan_1",
  revisionNumber: 2,
  planHash: "a".repeat(64),
  objective: "交付独立计划",
  scope: ["Plan 工作台"],
  nonGoals: ["发布"],
  constraints: ["Java 是唯一状态 owner"],
  steps: [
    {
      stepId: "step_contract",
      title: "冻结契约",
      description: "完成 JA-RPC 类型闭集",
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
      description: "计划编辑与执行形成闭环",
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
  criterionId: "criterion_ui",
  runId: "run_1",
  planRevisionId: "planrev_2",
  source: "test_report",
  sourceId: "vitest-plan-workbench",
  summary: "Plan 工作台测试通过",
  digest: "b".repeat(64),
  recordedAt: "2026-09-04T10:10:00+08:00",
};

/** 构造独立 Plan 投影，状态字段遵循 Java/JA-RPC 的 Plan 生命周期约束。 */
export function planModel(status: PlanStatus = "awaiting_approval"): PlanReadModel {
  const requiresRevision = ["approved", "executing", "verifying", "paused", "completed"].includes(
    status,
  );
  const requiresRun = ["executing", "verifying", "paused", "completed"].includes(status);
  const plan: PlanSummary = {
    planId: "plan_1",
    ownerThreadId: "thr_1",
    objective: planRevision.objective,
    status,
    revision: 3,
    activePlanRevisionId: requiresRevision ? planRevision.planRevisionId : null,
    activeRunId: requiresRun ? "run_1" : null,
    createdAt: planRevision.createdAt,
    updatedAt: "2026-09-04T10:10:00+08:00",
  };
  const revision = requiresRevision
    ? { ...planRevision, approvedAt: "2026-09-04T10:05:00+08:00" }
    : planRevision;

  return {
    plan,
    progress: planProgressFromRevision(revision),
    revision,
    revisionHydrationRequired: false,
    draft: null,
    approvedPlanRevisionId: requiresRevision ? planRevision.planRevisionId : null,
    eventSequence: 3,
  };
}
