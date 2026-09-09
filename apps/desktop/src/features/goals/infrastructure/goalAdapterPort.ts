// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  AcceptanceEvidence as WireEvidence,
  Goal as WireGoal,
  GoalEvaluation as WireEvaluation,
  PlanDraft as WireDraft,
  PlanProjection,
  PlanRevision as WireRevision,
  PlanStepExecution,
} from "@/api/protocol/goal";
import type { GoalAdapter, GoalMutationResult } from "@/api/tauri/goals";
import type {
  AcceptanceEvidence,
  GoalEvaluation,
  GoalReadModel,
  GoalSummary,
  PlanDraft,
  PlanReadModel,
  PlanRevision,
  PlanSummary,
} from "../domain/goalModel";
import type { GoalEvent, GoalPort } from "../application/ports";

export interface GoalEventSource {
  subscribe(listener: (event: GoalEvent) => void): () => void;
}

/** evaluator 只决定当前 criterion 的展示结论，不能用步骤全绿替代独立验收。 */
function criterionStatus(
  evaluation: WireEvaluation | null,
  criterionId: string,
): "pending" | "met" | "not_met" | "inconclusive" {
  return (
    evaluation?.criteria.find((criterion) => criterion.criterionId === criterionId)?.verdict ??
    "pending"
  );
}

/** 传输 revision 映射为 UI 只读模型，执行状态仅来自同一 PlanProjection。 */
function revisionFromWire(
  revision: WireRevision,
  projection: Pick<PlanProjection, "approval" | "stepExecutions">,
  evaluation: WireEvaluation | null,
): PlanRevision {
  const executions = new Map(
    projection.stepExecutions.map((execution) => [execution.stepId, execution]),
  );
  return {
    planRevisionId: revision.planRevisionId,
    planId: revision.planId,
    revisionNumber: revision.revisionNumber,
    planHash: revision.planHash,
    objective: revision.objective,
    scope: revision.scope,
    nonGoals: revision.nonGoals,
    constraints: revision.constraints,
    dependencies: revision.dependencies,
    steps: revision.steps.map((step) => {
      const execution: PlanStepExecution | undefined = executions.get(step.stepId);
      return {
        stepId: step.stepId,
        title: step.title,
        description: step.description,
        required: step.required,
        dependencyStepIds: step.dependsOn,
        status: execution?.status ?? "pending",
        blockingReason:
          execution?.status === "blocked" || execution?.status === "failed"
            ? execution.summary
            : null,
      };
    }),
    acceptanceCriteria: revision.acceptanceCriteria.map((criterion) => ({
      criterionId: criterion.criterionId,
      description: criterion.description,
      required: criterion.required,
      status: criterionStatus(evaluation, criterion.criterionId),
      evidenceIds: [],
    })),
    risks: revision.risks,
    verificationStrategy: revision.verificationStrategy,
    createdAt: revision.createdAt,
    approvedAt:
      projection.approval?.planRevisionId === revision.planRevisionId
        ? projection.approval.approvedAt
        : null,
  };
}

/** draft 保留 wire 中全部权威定义字段，UI 未展示的 dependencies 也会原样回传。 */
function draftFromWire(draft: WireDraft): PlanDraft {
  return {
    draftId: draft.planDraftId,
    planId: draft.planId,
    draftRevision: draft.draftRevision,
    basePlanRevisionId: draft.basePlanRevisionId,
    objective: draft.objective,
    scope: draft.scope,
    nonGoals: draft.nonGoals,
    constraints: draft.constraints,
    dependencies: draft.dependencies,
    steps: draft.steps.map((step) => ({
      stepId: step.stepId,
      title: step.title,
      description: step.description,
      required: step.required,
      dependencyStepIds: step.dependsOn,
    })),
    acceptanceCriteria: draft.acceptanceCriteria.map((criterion) => ({
      criterionId: criterion.criterionId,
      description: criterion.description,
      required: criterion.required,
    })),
    risks: draft.risks,
    verificationStrategy: draft.verificationStrategy,
    updatedAt: draft.updatedAt,
  };
}

/** UI draft 转换为严格 wire definition；本地 identity 与非合同字段不得越过 IPC。 */
function draftToWire(draft: PlanDraft) {
  return {
    objective: draft.objective,
    scope: [...draft.scope],
    nonGoals: [...draft.nonGoals],
    constraints: [...draft.constraints],
    acceptanceCriteria: draft.acceptanceCriteria.map((criterion) => ({
      criterionId: criterion.criterionId,
      description: criterion.description,
      required: criterion.required,
    })),
    steps: draft.steps.map((step) => ({
      stepId: step.stepId,
      title: step.title,
      description: step.description,
      required: step.required,
      dependsOn: [...step.dependencyStepIds],
    })),
    dependencies: [...(draft.dependencies ?? [])],
    risks: [...draft.risks],
    verificationStrategy: [...draft.verificationStrategy],
  };
}

/** Plan 聚合与 Goal 正交投影，批准状态和 run identity 不从 Goal phase 推导。 */
function planSummaryFromWire(projection: PlanProjection): PlanSummary {
  return {
    planId: projection.plan.planId,
    ownerThreadId: projection.plan.owner.threadId,
    objective: projection.plan.objective,
    status: projection.plan.status,
    revision: projection.plan.revision,
    activePlanRevisionId: projection.plan.activePlanRevisionId,
    activeRunId: projection.plan.activeRunId,
    createdAt: projection.plan.createdAt,
    updatedAt: projection.plan.updatedAt,
  };
}

/** 独立 evaluator 输出逐条件保留，不接入执行对话或 Tool 能力。 */
function evaluationFromWire(evaluation: WireEvaluation | null): GoalEvaluation | null {
  if (evaluation === null) return null;
  return {
    evaluationId: evaluation.evaluationId,
    planRevisionId: evaluation.planRevisionId,
    verdict: evaluation.verdict,
    summary: evaluation.summary,
    criteria: evaluation.criteria,
    evaluatedAt: evaluation.completedAt,
  };
}

/** Plan mutation/read 始终返回完整 Plan 投影，客户端不拼接批准或执行状态。 */
function planModelFromWire(
  projection: PlanProjection,
  evaluation: WireEvaluation | null = null,
): PlanReadModel {
  return {
    plan: planSummaryFromWire(projection),
    revision:
      projection.currentRevision === null
        ? null
        : revisionFromWire(projection.currentRevision, projection, evaluation),
    draft: projection.draft === null ? null : draftFromWire(projection.draft),
    approvedPlanRevisionId: projection.approval?.planRevisionId ?? null,
    eventSequence: projection.eventSequence,
  };
}

/** Goal owner 只投影其真实 Thread identity，独立 Task 不会伪装成主 Thread。 */
function goalFromWire(goal: WireGoal): GoalSummary {
  return {
    goalId: goal.goalId,
    ownerThreadId: goal.owner.kind === "thread" ? goal.owner.threadId : goal.owner.taskThreadId,
    revision: goal.revision,
    status: goal.status,
    phase: goal.phase,
    objective: goal.objective,
    goalDefinitionRevision: goal.goalDefinitionRevision,
    acceptanceCriteria: goal.acceptanceCriteria,
    activePlanId: goal.planLink?.planId ?? null,
    activePlanRevisionId: goal.planLink?.planRevisionId ?? null,
    activePlanHash: goal.planLink?.planHash ?? null,
    currentStepId: goal.currentStepId,
    completedRequiredSteps: goal.completedRequiredSteps,
    totalRequiredSteps: goal.totalRequiredSteps,
    attentionSummary: goal.attentionReason,
    updatedAt: goal.updatedAt,
  };
}

/** Goal 与 linked Plan 只在服务端 link identity 精确一致时组合，旧 revision 不得回挂。 */
function goalModelFromWire(result: GoalMutationResult, linkedPlan?: PlanProjection): GoalReadModel {
  const link = result.goal.planLink;
  if (
    link !== null &&
    linkedPlan !== undefined &&
    (linkedPlan.plan.planId !== link.planId ||
      linkedPlan.currentRevision?.planRevisionId !== link.planRevisionId ||
      linkedPlan.currentRevision.planHash !== link.planHash)
  ) {
    throw new Error("linked plan projection is stale");
  }
  const evaluation = evaluationFromWire(result.goal.latestEvaluation);
  const planModel =
    linkedPlan === undefined ? null : planModelFromWire(linkedPlan, result.goal.latestEvaluation);
  return {
    goal: goalFromWire(result.goal),
    eventSequence: result.eventSequence,
    planState: planModel?.plan ?? null,
    planEventSequence: planModel?.eventSequence,
    plan: planModel?.revision ?? null,
    draft: planModel?.draft ?? null,
    inputRequest:
      result.goal.pendingInput === null
        ? null
        : {
            requestId: result.goal.pendingInput.inputRequestId,
            prompt: result.goal.pendingInput.prompt,
            expiresAt: result.goal.pendingInput.expiresAt,
          },
    evaluation,
  };
}

/** Goal-only 与 Plan-bound 证据共享同一投影；nullable binding 必须原样保留，不能由 Renderer 丢弃。 */
function evidenceFromWire(evidence: WireEvidence): AcceptanceEvidence {
  return {
    evidenceId: evidence.evidenceId,
    goalDefinitionRevision: evidence.goalDefinitionRevision,
    criterionId: evidence.criterionId,
    runId: evidence.runId,
    planRevisionId: evidence.planRevisionId,
    source: evidence.sourceType,
    sourceId: evidence.sourceId,
    summary: evidence.summary,
    digest: evidence.digest,
    recordedAt: evidence.createdAt,
  };
}

/**
 * 将严格 Tauri adapter 收窄为 feature port；Goal 与 Plan mutation 保持独立 CAS，只有显式
 * attach 才把已批准 Plan 连接到 Goal，Renderer 不生成第二份聚合状态。
 */
export function createGoalPort(adapter: GoalAdapter, events: GoalEventSource): GoalPort {
  return {
    read: async ({ goalId }) => goalModelFromWire(await adapter.read({ goalId })),
    readPlan: async ({ ownerThreadId, planId }) =>
      planModelFromWire(await adapter.planRead({ threadId: ownerThreadId, planId })),
    observe: async ({ goalId }) => {
      const result = await adapter.observe({ goalId });
      return { observationId: result.observationId, goalRevision: result.goal.revision };
    },
    unobserve: (input) => adapter.unobserve(input),
    revisions: async ({ goalId }) => {
      const current = await adapter.read({ goalId });
      const link = current.goal.planLink;
      if (link === null) return { items: [] };
      const ownerThreadId =
        current.goal.owner.kind === "thread"
          ? current.goal.owner.threadId
          : current.goal.owner.taskThreadId;
      const [plan, result] = await Promise.all([
        adapter.planRead({ threadId: ownerThreadId, planId: link.planId }),
        adapter.revisionsList({ threadId: ownerThreadId, planId: link.planId, limit: 200 }),
      ]);
      return {
        items: result.items.map((revision) =>
          revisionFromWire(revision, plan, current.goal.latestEvaluation),
        ),
      };
    },
    planRevisions: async ({ ownerThreadId, planId }) => {
      const [plan, result] = await Promise.all([
        adapter.planRead({ threadId: ownerThreadId, planId }),
        adapter.revisionsList({ threadId: ownerThreadId, planId, limit: 200 }),
      ]);
      return {
        items: result.items.map((revision) => revisionFromWire(revision, plan, null)),
      };
    },
    evidence: async ({ goalId, goalDefinitionRevision, planRevisionId }) => {
      const result = await adapter.evidenceList({
        goalId,
        goalDefinitionRevision,
        ...(planRevisionId === null ? {} : { planRevisionId }),
        limit: 200,
      });
      return { items: result.items.map(evidenceFromWire) };
    },
    create: async ({ ownerThreadId, objective, expectedGoalRevision, idempotencyKey }) => {
      if (expectedGoalRevision !== 0) throw new Error("goal create revision must be zero");
      return goalModelFromWire(
        await adapter.create({
          owner: { kind: "thread", threadId: ownerThreadId },
          objective,
          acceptanceCriteria: [],
          expectedGoalRevision,
          idempotencyKey,
        }),
      );
    },
    createPlan: async ({ ownerThreadId, objective, expectedThreadRevision, idempotencyKey }) =>
      planModelFromWire(
        await adapter.createPlan({
          owner: { kind: "thread", threadId: ownerThreadId },
          objective,
          expectedThreadRevision,
          idempotencyKey,
        }),
      ),
    attachPlan: async (input) => goalModelFromWire(await adapter.attachPlan(input)),
    detachPlan: async (input) => goalModelFromWire(await adapter.detachPlan(input)),
    pause: async (input) => goalModelFromWire(await adapter.pause(input)),
    resume: async (input) => goalModelFromWire(await adapter.resume(input)),
    stop: async (input) => goalModelFromWire(await adapter.stop(input)),
    respondInput: async ({ requestId, ...input }) =>
      goalModelFromWire(await adapter.inputRespond({ ...input, inputRequestId: requestId })),
    saveDraft: async ({ ownerThreadId, planId, expectedPlanRevision, draft, idempotencyKey }) =>
      planModelFromWire(
        await adapter.draftSave({
          threadId: ownerThreadId,
          planId,
          expectedPlanRevision,
          idempotencyKey,
          draft: draftToWire(draft),
        }),
      ),
    discardDraft: async ({ ownerThreadId, ...input }) =>
      planModelFromWire(await adapter.draftDiscard({ threadId: ownerThreadId, ...input })),
    propose: async ({ ownerThreadId, ...input }) =>
      planModelFromWire(await adapter.propose({ threadId: ownerThreadId, ...input })),
    approve: async ({ ownerThreadId, ...input }) =>
      planModelFromWire(await adapter.approve({ threadId: ownerThreadId, ...input })),
    execute: async ({ ownerThreadId, ...input }) =>
      planModelFromWire(await adapter.execute({ threadId: ownerThreadId, ...input })),
    reject: async ({ ownerThreadId, ...input }) =>
      planModelFromWire(
        await adapter.reject({
          threadId: ownerThreadId,
          ...input,
          reason: "用户拒绝当前计划版本",
        }),
      ),
    subscribe: (listener) => events.subscribe(listener),
  };
}
