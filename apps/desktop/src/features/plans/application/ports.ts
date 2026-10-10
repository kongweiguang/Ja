// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  AcceptanceEvidence,
  PlanDraft,
  PlanProgress,
  PlanReadModel,
  PlanRevision,
  PlanSummary,
} from "../domain/planModel";

export type PlanMutationAction =
  | "create_plan"
  | "save_draft"
  | "discard_draft"
  | "propose"
  | "execute"
  | "pause"
  | "resume"
  | "stop"
  | "reject";

export interface PlanEvent {
  readonly method: "plan/changed";
  readonly planId: string;
  readonly ownerThreadId: string;
  readonly planRevision: number;
  readonly eventSequence: number;
  readonly plan: PlanSummary;
  readonly progress: PlanProgress;
  readonly occurredAt: string;
}

export interface PlanPort {
  readPlan(input: { ownerThreadId: string; planId: string }): Promise<PlanReadModel>;
  currentPlan(ownerThreadId: string): Promise<PlanReadModel | undefined>;
  observePlan(input: {
    ownerThreadId: string;
    planId: string;
  }): Promise<{ observationId: string; plan: PlanReadModel }>;
  unobservePlan(input: { observationId: string }): Promise<void>;
  planRevisions(input: {
    ownerThreadId: string;
    planId: string;
  }): Promise<{ items: PlanRevision[] }>;
  readPlanEvidence(input: {
    ownerThreadId: string;
    planId: string;
    planRevisionId: string;
    runId: string;
  }): Promise<{ items: AcceptanceEvidence[] }>;
  createPlan(input: {
    ownerThreadId: string;
    objective: string;
    expectedThreadRevision: number;
    operationKey: string;
  }): Promise<PlanReadModel>;
  saveDraft(input: {
    ownerThreadId: string;
    planId: string;
    draft: PlanDraft;
    expectedPlanRevision: number;
    operationKey: string;
  }): Promise<PlanReadModel>;
  discardDraft(input: PlanMutationInput): Promise<PlanReadModel>;
  propose(input: PlanMutationInput): Promise<PlanReadModel>;
  execute(
    input: PlanMutationInput & { planRevisionId: string; planHash: string },
  ): Promise<PlanReadModel>;
  pausePlan(input: PlanMutationInput & { runId: string }): Promise<PlanReadModel>;
  resumePlan(input: PlanMutationInput & { runId: string }): Promise<PlanReadModel>;
  stopPlan(input: PlanMutationInput & { runId: string }): Promise<PlanReadModel>;
  reject(input: PlanMutationInput): Promise<PlanReadModel>;
  subscribe(listener: (event: PlanEvent) => void): () => void;
}

export interface PlanMutationInput {
  readonly ownerThreadId: string;
  readonly planId: string;
  readonly expectedPlanRevision: number;
  readonly operationKey: string;
}
