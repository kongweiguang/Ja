// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  AcceptanceEvidence,
  GoalReadModel,
  PlanDraft,
  PlanReadModel,
  PlanRevision,
} from "../domain/goalModel";

export type GoalMutationAction =
  | "create"
  | "create_plan"
  | "pause"
  | "resume"
  | "stop"
  | "respond_input"
  | "save_draft"
  | "discard_draft"
  | "propose"
  | "approve"
  | "execute"
  | "attach_plan"
  | "detach_plan"
  | "reject";

export interface GoalEvent {
  readonly method: "goal/changed" | "goal/activity" | "goal/input-requested";
  readonly goalId: string;
  readonly goalRevision: number;
  readonly eventSequence: number;
  readonly occurredAt: string;
  readonly ownerThreadId?: string;
}

export interface GoalPortError extends Error {
  readonly code:
    | "GOAL_NOT_FOUND"
    | "GOAL_REVISION_CONFLICT"
    | "GOAL_INVALID_STATE"
    | "PLAN_INVALID"
    | "PLAN_APPROVAL_STALE"
    | "GOAL_EVIDENCE_INCOMPLETE"
    | "GOAL_RECOVERY_REQUIRED"
    | "GOAL_INPUT_EXPIRED"
    | string;
}

export interface GoalPort {
  read(input: { goalId: string }): Promise<GoalReadModel>;
  readPlan(input: { ownerThreadId: string; planId: string }): Promise<PlanReadModel>;
  observe(input: {
    goalId: string;
    expectedGoalRevision: number;
  }): Promise<{ observationId: string; goalRevision: number }>;
  unobserve(input: { observationId: string }): Promise<void>;
  revisions(input: { goalId: string }): Promise<{ items: PlanRevision[] }>;
  planRevisions(input: {
    ownerThreadId: string;
    planId: string;
  }): Promise<{ items: PlanRevision[] }>;
  evidence(input: {
    goalId: string;
    goalDefinitionRevision: number;
    planRevisionId: string | null;
  }): Promise<{ items: AcceptanceEvidence[] }>;
  create(input: {
    ownerThreadId: string;
    objective: string;
    expectedGoalRevision: number;
    idempotencyKey: string;
  }): Promise<GoalReadModel>;
  createPlan(input: {
    ownerThreadId: string;
    objective: string;
    expectedThreadRevision: number;
    idempotencyKey: string;
  }): Promise<PlanReadModel>;
  attachPlan(input: {
    goalId: string;
    planId: string;
    planRevisionId: string;
    planHash: string;
    expectedGoalRevision: number;
    idempotencyKey: string;
  }): Promise<GoalReadModel>;
  detachPlan(input: {
    goalId: string;
    expectedGoalRevision: number;
    idempotencyKey: string;
  }): Promise<GoalReadModel>;
  pause(input: {
    goalId: string;
    expectedGoalRevision: number;
    idempotencyKey: string;
  }): Promise<GoalReadModel>;
  resume(input: {
    goalId: string;
    expectedGoalRevision: number;
    idempotencyKey: string;
  }): Promise<GoalReadModel>;
  stop(input: {
    goalId: string;
    expectedGoalRevision: number;
    idempotencyKey: string;
  }): Promise<GoalReadModel>;
  respondInput(input: {
    goalId: string;
    requestId: string;
    response: string;
    expectedGoalRevision: number;
    idempotencyKey: string;
  }): Promise<GoalReadModel>;
  saveDraft(input: {
    ownerThreadId: string;
    planId: string;
    draft: PlanDraft;
    expectedPlanRevision: number;
    idempotencyKey: string;
  }): Promise<PlanReadModel>;
  discardDraft(input: {
    ownerThreadId: string;
    planId: string;
    expectedPlanRevision: number;
    idempotencyKey: string;
  }): Promise<PlanReadModel>;
  propose(input: {
    ownerThreadId: string;
    planId: string;
    expectedPlanRevision: number;
    idempotencyKey: string;
  }): Promise<PlanReadModel>;
  approve(input: {
    ownerThreadId: string;
    planId: string;
    planRevisionId: string;
    planHash: string;
    expectedPlanRevision: number;
    idempotencyKey: string;
  }): Promise<PlanReadModel>;
  execute(input: {
    ownerThreadId: string;
    planId: string;
    planRevisionId: string;
    planHash: string;
    expectedPlanRevision: number;
    idempotencyKey: string;
  }): Promise<PlanReadModel>;
  reject(input: {
    ownerThreadId: string;
    planId: string;
    expectedPlanRevision: number;
    idempotencyKey: string;
  }): Promise<PlanReadModel>;
  subscribe(listener: (event: GoalEvent) => void): () => void;
}
