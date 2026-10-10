// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  AcceptanceEvidence as WireEvidence,
  PlanDraft as WireDraft,
  PlanProjection,
  PlanRevision as WireRevision,
  PlanStepExecution,
} from "@/api/protocol/plan";
import type { PlanAdapter } from "@/api/tauri/plans";
import type {
  AcceptanceEvidence,
  PlanDraft,
  PlanReadModel,
  PlanRevision,
  PlanSummary,
} from "../domain/planModel";
import { planProgressFromRevision } from "../domain/planModel";
import type { PlanEvent, PlanPort } from "../application/ports";

export interface PlanEventSource {
  subscribe(listener: (event: PlanEvent) => void): () => void;
}

/** 执行步骤只读取同一 Plan 投影；验收条件在证据查询前不推断为已满足。 */
function revisionFromWire(
  revision: WireRevision,
  projection: Pick<PlanProjection, "approval" | "stepExecutions">,
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
      status: "pending" as const,
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

/** 草稿保留 wire 中全部定义字段，未展示的 dependencies 也原样回传。 */
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

/** 本地编辑字段映射为严格定义，UI 临时 identity 不越过 IPC。 */
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

/** Plan 聚合本身持有 Thread owner、版本和 Run 身份。 */
function summaryFromWire(projection: PlanProjection): PlanSummary {
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

/** Mutation/read 的完整投影是唯一 UI 权威，ACK 不由局部字段拼接。 */
function modelFromWire(projection: PlanProjection): PlanReadModel {
  const revision =
    projection.currentRevision === null
      ? null
      : revisionFromWire(projection.currentRevision, projection);
  return {
    plan: summaryFromWire(projection),
    progress: planProgressFromRevision(revision),
    revision,
    revisionHydrationRequired: false,
    draft: projection.draft === null ? null : draftFromWire(projection.draft),
    approvedPlanRevisionId: projection.approval?.planRevisionId ?? null,
    eventSequence: projection.eventSequence,
  };
}

/** 证据只映射 Plan 身份，保留当前 revision 和 Run 的绑定。 */
function evidenceFromWire(evidence: WireEvidence): AcceptanceEvidence {
  return {
    evidenceId: evidence.evidenceId,
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

/** 类型化 Tauri adapter 仅暴露 Plan 操作；详情与订阅保持懒加载。 */
export function createPlanPort(adapter: PlanAdapter, events: PlanEventSource): PlanPort {
  return {
    readPlan: async ({ ownerThreadId, planId }) =>
      modelFromWire(await adapter.read({ threadId: ownerThreadId, planId })),
    currentPlan: async (ownerThreadId) => {
      const result = await adapter.currentRead({ threadId: ownerThreadId });
      return result.current === null ? undefined : modelFromWire(result.current);
    },
    observePlan: async ({ ownerThreadId, planId }) => {
      const result = await adapter.observe({ threadId: ownerThreadId, planId });
      return { observationId: result.observationId, plan: modelFromWire(result) };
    },
    unobservePlan: async ({ observationId }) => {
      await adapter.unobserve({ observationId });
    },
    planRevisions: async ({ ownerThreadId, planId }) => {
      const [plan, result] = await Promise.all([
        adapter.read({ threadId: ownerThreadId, planId }),
        adapter.revisionsList({ threadId: ownerThreadId, planId, limit: 200 }),
      ]);
      return { items: result.items.map((revision) => revisionFromWire(revision, plan)) };
    },
    readPlanEvidence: async ({ ownerThreadId, planId, planRevisionId, runId }) => {
      const result = await adapter.evidenceList({
        threadId: ownerThreadId,
        planId,
        planRevisionId,
        runId,
        limit: 200,
      });
      return { items: result.items.map(evidenceFromWire) };
    },
    createPlan: async ({ ownerThreadId, objective, expectedThreadRevision, operationKey }) =>
      modelFromWire(
        await adapter.create({
          owner: { kind: "thread", threadId: ownerThreadId },
          objective,
          expectedThreadRevision,
          operationKey,
        }),
      ),
    saveDraft: async ({ ownerThreadId, planId, expectedPlanRevision, draft, operationKey }) =>
      modelFromWire(
        await adapter.draftSave({
          threadId: ownerThreadId,
          planId,
          expectedPlanRevision,
          operationKey,
          draft: draftToWire(draft),
        }),
      ),
    discardDraft: async ({ ownerThreadId, ...input }) =>
      modelFromWire(await adapter.draftDiscard({ threadId: ownerThreadId, ...input })),
    propose: async ({ ownerThreadId, ...input }) =>
      modelFromWire(await adapter.propose({ threadId: ownerThreadId, ...input })),
    execute: async ({ ownerThreadId, ...input }) =>
      modelFromWire(await adapter.execute({ threadId: ownerThreadId, ...input })),
    pausePlan: async ({ ownerThreadId, ...input }) =>
      modelFromWire(await adapter.pause({ threadId: ownerThreadId, ...input })),
    resumePlan: async ({ ownerThreadId, ...input }) =>
      modelFromWire(await adapter.resume({ threadId: ownerThreadId, ...input })),
    stopPlan: async ({ ownerThreadId, ...input }) =>
      modelFromWire(await adapter.stop({ threadId: ownerThreadId, ...input })),
    reject: async ({ ownerThreadId, ...input }) =>
      modelFromWire(
        await adapter.reject({
          threadId: ownerThreadId,
          ...input,
          reason: "用户拒绝当前计划版本",
        }),
      ),
    subscribe: (listener) => events.subscribe(listener),
  };
}
