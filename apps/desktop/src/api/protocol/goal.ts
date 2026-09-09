// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
const RevisionSchema = z.number().int().min(0).max(MAX_SAFE_INTEGER);
const PositiveRevisionSchema = z.number().int().min(1).max(MAX_SAFE_INTEGER);
const TimestampSchema = z.string().datetime({ offset: true }).max(64);
const DigestSchema = z.string().regex(/^[0-9a-f]{64}$/);
const CursorSchema = z
  .string()
  .regex(/^[A-Za-z0-9._~-]{1,256}$/)
  .max(256);
const ThreadIdSchema = z
  .string()
  .regex(/^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
export const GoalIdSchema = z
  .string()
  .regex(/^goal_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);
export const PlanIdSchema = z
  .string()
  .regex(/^plan_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);
const PlanRevisionIdSchema = z
  .string()
  .regex(/^planrev_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(104);
const PlanDraftIdSchema = z
  .string()
  .regex(/^plandraft_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(106);
const PlanStepIdSchema = z
  .string()
  .regex(/^step_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);
const CriterionIdSchema = z
  .string()
  .regex(/^criterion_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(106);
const RunIdSchema = z
  .string()
  .regex(/^run_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const EvidenceIdSchema = z
  .string()
  .regex(/^evidence_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(105);
const EvaluationIdSchema = z
  .string()
  .regex(/^evaluation_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(107);
const GoalInputIdSchema = z
  .string()
  .regex(/^goalinput_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(106);
const ObservationIdSchema = z
  .string()
  .regex(/^observe_[A-Za-z0-9][A-Za-z0-9._-]{7,95}$/)
  .max(103);
const ApprovalIdSchema = z
  .string()
  .regex(/^appr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);
const EventIdSchema = z
  .string()
  .regex(/^evt_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const ServerInstanceIdSchema = z
  .string()
  .regex(/^srv_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const text = (maximum: number, allowEmpty = false) =>
  z
    .string()
    .min(allowEmpty ? 0 : 1)
    .max(maximum)
    .refine((value) => !value.includes("\u0000"));
const SafeTextSchema = text(32_768);
const ShortTextSchema = text(1_024);
const DefinitionItemSchema = text(2_000);
const IdempotencyKeySchema = z
  .string()
  .min(8)
  .max(128)
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]+$/);

export const CollaborationModeSchema = z.enum(["default", "plan"]);
export const GoalStatusSchema = z.enum(["active", "paused", "achieved", "stopped"]);
export const GoalPhaseSchema = z.enum([
  "working",
  "waiting_approval",
  "waiting_input",
  "verifying",
  "needs_attention",
  "paused",
  "achieved",
  "stopped",
]);
export const PlanStatusSchema = z.enum([
  "draft",
  "awaiting_approval",
  "approved",
  "executing",
  "completed",
  "stopped",
]);
export const PlanStepStatusSchema = z.enum([
  "pending",
  "ready",
  "running",
  "blocked",
  "succeeded",
  "failed",
  "skipped",
]);

const GoalOwnerSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("thread"), threadId: ThreadIdSchema }).strict(),
  z.object({ kind: z.literal("independent_task"), taskThreadId: ThreadIdSchema }).strict(),
]);
const PlanOwnerSchema = z.object({ kind: z.literal("thread"), threadId: ThreadIdSchema }).strict();

export const AcceptanceCriterionSchema = z
  .object({
    criterionId: CriterionIdSchema,
    description: text(2_000),
    required: z.boolean(),
  })
  .strict();

export const PlanStepSchema = z
  .object({
    stepId: PlanStepIdSchema,
    title: text(240),
    description: text(4_000, true),
    required: z.boolean(),
    dependsOn: z.array(PlanStepIdSchema).max(64),
  })
  .strict()
  .superRefine((value, context) => {
    if (
      new Set(value.dependsOn).size !== value.dependsOn.length ||
      value.dependsOn.includes(value.stepId)
    ) {
      context.addIssue({
        code: "custom",
        path: ["dependsOn"],
        message: "step dependencies are invalid",
      });
    }
  });

export const PlanDefinitionSchema = z
  .object({
    objective: SafeTextSchema,
    scope: z.array(DefinitionItemSchema).max(128),
    nonGoals: z.array(DefinitionItemSchema).max(128),
    constraints: z.array(DefinitionItemSchema).max(128),
    acceptanceCriteria: z.array(AcceptanceCriterionSchema).min(1).max(256),
    steps: z.array(PlanStepSchema).min(1).max(256),
    dependencies: z.array(DefinitionItemSchema).max(128),
    risks: z.array(DefinitionItemSchema).max(128),
    verificationStrategy: z.array(DefinitionItemSchema).min(1).max(128),
  })
  .strict()
  .superRefine((value, context) => {
    const stepIds = new Set(value.steps.map((step) => step.stepId));
    const criterionIds = value.acceptanceCriteria.map((criterion) => criterion.criterionId);
    if (stepIds.size !== value.steps.length || new Set(criterionIds).size !== criterionIds.length) {
      context.addIssue({ code: "custom", path: [], message: "plan identities must be unique" });
    }
    value.steps.forEach((step, index) => {
      if (step.dependsOn.some((dependency) => !stepIds.has(dependency))) {
        context.addIssue({
          code: "custom",
          path: ["steps", index, "dependsOn"],
          message: "step dependency does not exist",
        });
      }
    });
  });

export const GoalPlanLinkSchema = z
  .object({
    planId: PlanIdSchema,
    planRevisionId: PlanRevisionIdSchema,
    planHash: DigestSchema,
    linkRevision: PositiveRevisionSchema,
    attachedAt: TimestampSchema,
  })
  .strict();

export const PlanRevisionSchema = PlanDefinitionSchema.extend({
  planRevisionId: PlanRevisionIdSchema,
  planId: PlanIdSchema,
  revisionNumber: PositiveRevisionSchema,
  planHash: DigestSchema,
  createdBy: z.enum(["agent", "user_ui"]),
  createdAt: TimestampSchema,
}).strict();

export const PlanDraftSchema = PlanDefinitionSchema.extend({
  planDraftId: PlanDraftIdSchema,
  planId: PlanIdSchema,
  draftRevision: RevisionSchema,
  basePlanRevisionId: PlanRevisionIdSchema.nullable(),
  updatedAt: TimestampSchema,
}).strict();

export const PlanApprovalSchema = z
  .object({
    approvalId: ApprovalIdSchema,
    planId: PlanIdSchema,
    planRevisionId: PlanRevisionIdSchema,
    planHash: DigestSchema,
    approvedAt: TimestampSchema,
  })
  .strict();

export const PlanSchema = z
  .object({
    planId: PlanIdSchema,
    owner: PlanOwnerSchema,
    objective: SafeTextSchema,
    status: PlanStatusSchema,
    revision: RevisionSchema,
    activePlanRevisionId: PlanRevisionIdSchema.nullable(),
    activeRunId: RunIdSchema.nullable(),
    createdAt: TimestampSchema,
    updatedAt: TimestampSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const running = value.status === "executing" || value.status === "completed";
    if (running !== (value.activePlanRevisionId !== null && value.activeRunId !== null)) {
      context.addIssue({
        code: "custom",
        path: ["activeRunId"],
        message: "plan execution identity is invalid",
      });
    }
    if (
      value.status === "approved" &&
      (value.activePlanRevisionId === null || value.activeRunId !== null)
    ) {
      context.addIssue({
        code: "custom",
        path: ["activePlanRevisionId"],
        message: "approved plan identity is invalid",
      });
    }
  });

export const GoalInputRequestSchema = z
  .object({
    inputRequestId: GoalInputIdSchema,
    prompt: SafeTextSchema,
    expiresAt: TimestampSchema,
    createdAt: TimestampSchema,
  })
  .strict();

export const GoalEvaluationSchema = z
  .object({
    evaluationId: EvaluationIdSchema,
    goalId: GoalIdSchema,
    goalDefinitionRevision: PositiveRevisionSchema,
    planRevisionId: PlanRevisionIdSchema.nullable(),
    runId: RunIdSchema,
    verdict: z.enum(["met", "not_met", "inconclusive"]),
    criteria: z
      .array(
        z
          .object({
            criterionId: CriterionIdSchema,
            verdict: z.enum(["met", "not_met", "inconclusive"]),
            reason: SafeTextSchema,
          })
          .strict(),
      )
      .max(256),
    summary: SafeTextSchema,
    completedAt: TimestampSchema,
  })
  .strict();

export const GoalSchema = z
  .object({
    goalId: GoalIdSchema,
    owner: GoalOwnerSchema,
    objective: SafeTextSchema,
    goalDefinitionRevision: PositiveRevisionSchema,
    acceptanceCriteria: z.array(AcceptanceCriterionSchema).max(256),
    status: GoalStatusSchema,
    phase: GoalPhaseSchema,
    revision: RevisionSchema,
    planLink: GoalPlanLinkSchema.nullable(),
    currentRunId: RunIdSchema.nullable(),
    currentStepId: PlanStepIdSchema.nullable(),
    completedRequiredSteps: RevisionSchema,
    totalRequiredSteps: RevisionSchema,
    pendingInput: GoalInputRequestSchema.nullable(),
    attentionReason: ShortTextSchema.nullable(),
    latestEvaluation: GoalEvaluationSchema.nullable(),
    createdAt: TimestampSchema,
    updatedAt: TimestampSchema,
    achievedAt: TimestampSchema.nullable(),
    stoppedAt: TimestampSchema.nullable(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.completedRequiredSteps > value.totalRequiredSteps) {
      context.addIssue({
        code: "custom",
        path: ["completedRequiredSteps"],
        message: "goal progress is invalid",
      });
    }
    if (
      value.latestEvaluation?.goalDefinitionRevision !== undefined &&
      value.latestEvaluation.goalDefinitionRevision !== value.goalDefinitionRevision
    ) {
      context.addIssue({
        code: "custom",
        path: ["latestEvaluation"],
        message: "evaluation definition is stale",
      });
    }
    if (value.latestEvaluation !== null) {
      const criterionIds = new Set(
        value.acceptanceCriteria.map((criterion) => criterion.criterionId),
      );
      if (
        value.latestEvaluation.goalId !== value.goalId ||
        value.latestEvaluation.criteria.some(
          (criterion) => !criterionIds.has(criterion.criterionId),
        )
      ) {
        context.addIssue({
          code: "custom",
          path: ["latestEvaluation"],
          message: "evaluation criterion is dangling",
        });
      }
    }
  });

export const PlanStepExecutionSchema = z
  .object({
    stepId: PlanStepIdSchema,
    runId: RunIdSchema,
    status: PlanStepStatusSchema,
    attempt: z.number().int().min(0).max(MAX_SAFE_INTEGER),
    failureSignature: DigestSchema.nullable(),
    summary: ShortTextSchema.nullable(),
    startedAt: TimestampSchema.nullable(),
    completedAt: TimestampSchema.nullable(),
  })
  .strict();

export const AcceptanceEvidenceSchema = z
  .object({
    evidenceId: EvidenceIdSchema,
    goalId: GoalIdSchema.nullable(),
    planId: PlanIdSchema.nullable(),
    goalDefinitionRevision: PositiveRevisionSchema.nullable(),
    runId: RunIdSchema,
    planRevisionId: PlanRevisionIdSchema.nullable(),
    criterionId: CriterionIdSchema.nullable(),
    stepId: PlanStepIdSchema.nullable(),
    sourceType: z.enum([
      "tool_result",
      "test_report",
      "build_artifact",
      "repository_state",
      "ui_assertion",
      "user_acceptance",
    ]),
    sourceId: z
      .string()
      .min(1)
      .max(256)
      .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]*$/),
    summary: SafeTextSchema,
    digest: DigestSchema,
    observedAt: TimestampSchema,
    createdAt: TimestampSchema,
  })
  .strict()
  .superRefine((value, context) => {
    if (value.goalId === null && value.planId === null) {
      context.addIssue({ code: "custom", path: ["goalId"], message: "evidence owner is required" });
    }
    if ((value.goalId === null) !== (value.goalDefinitionRevision === null)) {
      context.addIssue({
        code: "custom",
        path: ["goalDefinitionRevision"],
        message: "goal definition binding is invalid",
      });
    }
  });

export const PlanProjectionSchema = z
  .object({
    plan: PlanSchema,
    draft: PlanDraftSchema.nullable(),
    currentRevision: PlanRevisionSchema.nullable(),
    approval: PlanApprovalSchema.nullable(),
    stepExecutions: z.array(PlanStepExecutionSchema).max(256),
    eventSequence: RevisionSchema,
  })
  .strict()
  .superRefine((value, context) => {
    const activeRevisionId = value.plan.activePlanRevisionId;
    if (activeRevisionId !== null && value.currentRevision?.planRevisionId !== activeRevisionId) {
      context.addIssue({
        code: "custom",
        path: ["currentRevision"],
        message: "active plan revision is not projected",
      });
    }
    if (value.currentRevision !== null && value.currentRevision.planId !== value.plan.planId) {
      context.addIssue({
        code: "custom",
        path: ["currentRevision", "planId"],
        message: "plan revision owner is invalid",
      });
    }
    if (
      value.approval !== null &&
      (value.approval.planId !== value.plan.planId ||
        value.approval.planRevisionId !== value.currentRevision?.planRevisionId ||
        value.approval.planHash !== value.currentRevision?.planHash)
    ) {
      context.addIssue({
        code: "custom",
        path: ["approval"],
        message: "plan approval binding is invalid",
      });
    }
  });

const GoalProjectionResultSchema = z
  .object({ goal: GoalSchema, eventSequence: RevisionSchema })
  .strict();
const GoalMutationParamsSchema = z
  .object({
    goalId: GoalIdSchema,
    expectedGoalRevision: RevisionSchema,
    idempotencyKey: IdempotencyKeySchema,
  })
  .strict();
const GoalReadParamsSchema = z.object({ goalId: GoalIdSchema }).strict();
const GoalPageParamsSchema = z
  .object({
    goalId: GoalIdSchema,
    cursor: CursorSchema.optional(),
    limit: z.number().int().min(1).max(200).optional(),
  })
  .strict();
const PlanReadParamsSchema = z.object({ threadId: ThreadIdSchema, planId: PlanIdSchema }).strict();
const PlanPageParamsSchema = PlanReadParamsSchema.extend({
  cursor: CursorSchema.optional(),
  limit: z.number().int().min(1).max(200).optional(),
}).strict();
const PlanMutationParamsSchema = PlanReadParamsSchema.extend({
  expectedPlanRevision: RevisionSchema,
  idempotencyKey: IdempotencyKeySchema,
}).strict();

const GoalCreateParamsSchema = z
  .object({
    owner: GoalOwnerSchema,
    objective: SafeTextSchema,
    acceptanceCriteria: z.array(AcceptanceCriterionSchema).max(256),
    expectedGoalRevision: z.literal(0),
    idempotencyKey: IdempotencyKeySchema,
  })
  .strict();
const PlanCreateParamsSchema = z
  .object({
    owner: PlanOwnerSchema,
    objective: SafeTextSchema,
    expectedThreadRevision: RevisionSchema,
    idempotencyKey: IdempotencyKeySchema,
  })
  .strict();
const GoalObserveParamsSchema = GoalReadParamsSchema;
const GoalUnobserveParamsSchema = z.object({ observationId: ObservationIdSchema }).strict();
const GoalInputRespondParamsSchema = GoalMutationParamsSchema.extend({
  inputRequestId: GoalInputIdSchema,
  response: SafeTextSchema,
}).strict();
const PlanDraftSaveParamsSchema = PlanMutationParamsSchema.extend({
  draft: PlanDefinitionSchema,
}).strict();
const PlanApprovalBindingParamsSchema = PlanMutationParamsSchema.extend({
  planRevisionId: PlanRevisionIdSchema,
  planHash: DigestSchema,
}).strict();
const PlanRejectParamsSchema = PlanMutationParamsSchema.extend({ reason: SafeTextSchema }).strict();
const GoalPlanAttachParamsSchema = GoalMutationParamsSchema.extend({
  planId: PlanIdSchema,
  planRevisionId: PlanRevisionIdSchema,
  planHash: DigestSchema,
}).strict();
const GoalEvidenceListParamsSchema = GoalPageParamsSchema.extend({
  goalDefinitionRevision: PositiveRevisionSchema,
  planRevisionId: PlanRevisionIdSchema.optional(),
}).strict();

const GoalEventsResultSchema = z
  .object({
    goalId: GoalIdSchema,
    goalRevision: RevisionSchema,
    eventSequence: RevisionSchema,
    items: z
      .array(
        z
          .object({
            eventSequence: RevisionSchema,
            kind: z.enum([
              "created",
              "plan_attached",
              "plan_detached",
              "run_started",
              "tool_approval_requested",
              "tool_approval_resolved",
              "step_changed",
              "evidence_added",
              "continuation_no_progress",
              "input_requested",
              "input_received",
              "evaluation_started",
              "evaluation_completed",
              "paused",
              "resumed",
              "stopped",
              "achieved",
              "recovery_required",
            ]),
            summary: SafeTextSchema,
            occurredAt: TimestampSchema,
          })
          .strict(),
      )
      .max(200),
    nextCursor: CursorSchema.nullable(),
  })
  .strict();
const PlanRevisionsResultSchema = z
  .object({
    planId: PlanIdSchema,
    planRevision: RevisionSchema,
    eventSequence: RevisionSchema,
    items: z.array(PlanRevisionSchema).max(200),
    nextCursor: CursorSchema.nullable(),
  })
  .strict();
const GoalEvidenceResultSchema = z
  .object({
    goalId: GoalIdSchema,
    goalRevision: RevisionSchema,
    goalDefinitionRevision: PositiveRevisionSchema,
    planRevisionId: PlanRevisionIdSchema.nullable(),
    eventSequence: RevisionSchema,
    items: z.array(AcceptanceEvidenceSchema).max(200),
    nextCursor: CursorSchema.nullable(),
  })
  .strict();
const GoalObserveResultSchema = GoalProjectionResultSchema.extend({
  observationId: ObservationIdSchema,
}).strict();
const AcceptedResultSchema = z.object({ accepted: z.literal(true) }).strict();

/** WebView 的 Goal/Plan 请求闭集与 Java、Rust method catalog 保持一一对应。 */
export const GoalParamsSchemaByMethod = {
  "goal/read": GoalReadParamsSchema,
  "goal/events/read": GoalPageParamsSchema,
  "goal/observe": GoalObserveParamsSchema,
  "goal/unobserve": GoalUnobserveParamsSchema,
  "plan/read": PlanReadParamsSchema,
  "plan/revisions/list": PlanPageParamsSchema,
  "goal/evidence/list": GoalEvidenceListParamsSchema,
  "goal/create": GoalCreateParamsSchema,
  "goal/plan/attach": GoalPlanAttachParamsSchema,
  "goal/plan/detach": GoalMutationParamsSchema,
  "goal/pause": GoalMutationParamsSchema,
  "goal/resume": GoalMutationParamsSchema,
  "goal/stop": GoalMutationParamsSchema,
  "goal/input/respond": GoalInputRespondParamsSchema,
  "plan/create": PlanCreateParamsSchema,
  "plan/draft/save": PlanDraftSaveParamsSchema,
  "plan/draft/discard": PlanMutationParamsSchema,
  "plan/propose": PlanMutationParamsSchema,
  "plan/approve": PlanApprovalBindingParamsSchema,
  "plan/execute": PlanApprovalBindingParamsSchema,
  "plan/reject": PlanRejectParamsSchema,
} as const;

/** Mutation 始终返回对应聚合的完整权威投影，客户端不拼接局部 CAS 状态。 */
export const GoalResultSchemaByMethod = {
  "goal/read": GoalProjectionResultSchema,
  "goal/events/read": GoalEventsResultSchema,
  "goal/observe": GoalObserveResultSchema,
  "goal/unobserve": AcceptedResultSchema,
  "plan/read": PlanProjectionSchema,
  "plan/revisions/list": PlanRevisionsResultSchema,
  "goal/evidence/list": GoalEvidenceResultSchema,
  "goal/create": GoalProjectionResultSchema,
  "goal/plan/attach": GoalProjectionResultSchema,
  "goal/plan/detach": GoalProjectionResultSchema,
  "goal/pause": GoalProjectionResultSchema,
  "goal/resume": GoalProjectionResultSchema,
  "goal/stop": GoalProjectionResultSchema,
  "goal/input/respond": GoalProjectionResultSchema,
  "plan/create": PlanProjectionSchema,
  "plan/draft/save": PlanProjectionSchema,
  "plan/draft/discard": PlanProjectionSchema,
  "plan/propose": PlanProjectionSchema,
  "plan/approve": PlanProjectionSchema,
  "plan/execute": PlanProjectionSchema,
  "plan/reject": PlanProjectionSchema,
} as const;

const GoalEventBaseSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: EventIdSchema,
    sequence: PositiveRevisionSchema,
    generation: PositiveRevisionSchema,
    occurredAt: TimestampSchema,
    goalId: GoalIdSchema,
    goalRevision: RevisionSchema,
    eventSequence: RevisionSchema,
  })
  .strict();

export const GoalChangedParamsSchema = GoalEventBaseSchema.extend({ goal: GoalSchema }).strict();
export const GoalActivityParamsSchema = GoalEventBaseSchema.extend({
  activity: z
    .object({
      kind: z.enum(["run", "step", "evaluation", "recovery"]),
      status: ShortTextSchema,
      summary: SafeTextSchema,
      stepId: PlanStepIdSchema.nullable(),
    })
    .strict(),
}).strict();
export const GoalInputRequestedParamsSchema = GoalEventBaseSchema.extend({
  input: GoalInputRequestSchema,
}).strict();

export type Goal = z.infer<typeof GoalSchema>;
export type Plan = z.infer<typeof PlanSchema>;
export type PlanProjection = z.infer<typeof PlanProjectionSchema>;
export type PlanRevision = z.infer<typeof PlanRevisionSchema>;
export type PlanDraft = z.infer<typeof PlanDraftSchema>;
export type PlanApproval = z.infer<typeof PlanApprovalSchema>;
export type PlanStep = z.infer<typeof PlanStepSchema>;
export type PlanStepExecution = z.infer<typeof PlanStepExecutionSchema>;
export type AcceptanceCriterion = z.infer<typeof AcceptanceCriterionSchema>;
export type AcceptanceEvidence = z.infer<typeof AcceptanceEvidenceSchema>;
export type GoalEvaluation = z.infer<typeof GoalEvaluationSchema>;
export type GoalInputRequest = z.infer<typeof GoalInputRequestSchema>;
export type GoalMethod = keyof typeof GoalParamsSchemaByMethod;
export type GoalMethodParams<M extends GoalMethod> = z.infer<(typeof GoalParamsSchemaByMethod)[M]>;
export type GoalMethodResult<M extends GoalMethod> = z.infer<(typeof GoalResultSchemaByMethod)[M]>;
