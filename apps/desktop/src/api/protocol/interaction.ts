// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { z } from "zod";

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;
const revision = z.number().int().min(0).max(MAX_SAFE_INTEGER);
const timestamp = z.string().datetime({ offset: true }).max(64);
const threadId = z
  .string()
  .regex(/^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(100);
const requestId = z
  .string()
  .regex(/^interaction_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(128);
const questionId = z
  .string()
  .regex(/^question_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(128);
const optionId = z
  .string()
  .regex(/^option_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(128);
const observationId = z
  .string()
  .regex(/^observe_[A-Za-z0-9][A-Za-z0-9._-]{7,95}$/)
  .max(103);
const turnId = z
  .string()
  .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(108);
const planRevisionId = z
  .string()
  .regex(/^planrev_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(104);
const goalId = z
  .string()
  .regex(/^goal_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
  .max(101);
const idempotencyKey = z
  .string()
  .min(8)
  .max(128)
  .regex(/^[A-Za-z0-9][A-Za-z0-9._:-]+$/);
const text = (max: number, allowEmpty = false) =>
  z
    .string()
    .min(allowEmpty ? 0 : 1)
    .max(max)
    .refine((value) => !value.includes("\u0000"));

export const InteractionOptionSchema = z
  .object({ optionId, label: text(512), description: text(2_000, true), recommended: z.boolean() })
  .strict();

export const InteractionQuestionSchema = z
  .object({
    questionId,
    prompt: text(4_000),
    type: z.enum(["single", "multiple", "text"]),
    required: z.boolean(),
    allowFreeText: z.boolean(),
    options: z.array(InteractionOptionSchema).max(32),
  })
  .strict()
  .superRefine((value, context) => {
    if (new Set(value.options.map((option) => option.optionId)).size !== value.options.length) {
      context.addIssue({
        code: "custom",
        path: ["options"],
        message: "question option IDs must be unique",
      });
    }
    if (value.type === "text" && value.options.length > 0) {
      context.addIssue({
        code: "custom",
        path: ["options"],
        message: "text questions cannot carry options",
      });
    }
  });

export const InteractionAnswerSchema = z
  .object({
    questionId,
    optionIds: z.array(optionId).max(32),
    freeText: text(16_000, true).nullable(),
    skipped: z.boolean(),
  })
  .strict()
  .superRefine((value, context) => {
    if (new Set(value.optionIds).size !== value.optionIds.length) {
      context.addIssue({
        code: "custom",
        path: ["optionIds"],
        message: "answer option IDs must be unique",
      });
    }
    if (value.skipped && (value.optionIds.length > 0 || value.freeText !== null)) {
      context.addIssue({
        code: "custom",
        path: ["skipped"],
        message: "skipped answer cannot carry a value",
      });
    }
  });

export const InteractionRequestSchema = z
  .object({
    requestId,
    threadId,
    turnId: turnId.nullable(),
    toolCallId: z
      .string()
      .regex(/^call_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(101)
      .nullable(),
    planRevisionId: planRevisionId.nullable(),
    runId: z
      .string()
      .regex(/^run_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(100)
      .nullable(),
    goalId: goalId.nullable(),
    status: z.enum(["pending", "answered", "cancelled", "superseded"]),
    revision,
    questions: z.array(InteractionQuestionSchema).min(1).max(3),
    answers: z.array(InteractionAnswerSchema).max(3),
    createdAt: timestamp,
    updatedAt: timestamp,
  })
  .strict()
  .superRefine((value, context) => {
    if (
      new Set(value.questions.map((question) => question.questionId)).size !==
      value.questions.length
    ) {
      context.addIssue({
        code: "custom",
        path: ["questions"],
        message: "question IDs must be unique",
      });
    }
  });

export const InteractionDraftSchema = z
  .object({
    threadId,
    requestId,
    answers: z.array(InteractionAnswerSchema).max(3),
    page: z.number().int().min(0).max(2),
    collapsed: z.boolean(),
    revision,
    updatedAt: timestamp,
  })
  .strict();

export const InteractionSnapshotSchema = z
  .object({
    threadId,
    eventSequence: revision,
    request: InteractionRequestSchema.nullable(),
    draft: InteractionDraftSchema.nullable(),
    resumeState: z.enum([
      "none",
      "waiting_for_answer",
      "waiting_to_resume",
      "resuming",
      "settled",
      "closed",
    ]),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.request !== null && value.request.threadId !== value.threadId) {
      context.addIssue({
        code: "custom",
        path: ["request", "threadId"],
        message: "request owner mismatch",
      });
    }
    if (value.draft !== null && value.draft.threadId !== value.threadId) {
      context.addIssue({
        code: "custom",
        path: ["draft", "threadId"],
        message: "draft owner mismatch",
      });
    }
  });

const readParams = z.object({ threadId, requestId: requestId.optional() }).strict();
const observeParams = z.object({ threadId }).strict();
const unobserveParams = z.object({ observationId }).strict();
const draftMutation = z
  .object({
    threadId,
    requestId,
    expectedDraftRevision: revision,
    idempotencyKey,
    answers: z.array(InteractionAnswerSchema).max(3),
    page: z.number().int().min(0).max(2),
    collapsed: z.boolean(),
  })
  .strict();
const respondMutation = z
  .object({
    threadId,
    requestId,
    expectedRevision: revision,
    idempotencyKey,
    answers: z.array(InteractionAnswerSchema).max(3),
  })
  .strict();
const cancelMutation = z
  .object({ threadId, requestId, expectedRevision: revision, idempotencyKey })
  .strict();

export const InteractionParamsSchemaByMethod = {
  "interaction/read": readParams,
  "interaction/observe": observeParams,
  "interaction/unobserve": unobserveParams,
  "interaction/draft/save": draftMutation,
  "interaction/respond": respondMutation,
  "interaction/cancel": cancelMutation,
} as const;

export const InteractionResultSchemaByMethod = {
  "interaction/read": InteractionSnapshotSchema,
  "interaction/observe": InteractionSnapshotSchema.extend({ observationId }).strict(),
  "interaction/unobserve": z.object({ accepted: z.literal(true) }).strict(),
  "interaction/draft/save": InteractionSnapshotSchema,
  "interaction/respond": InteractionSnapshotSchema,
  "interaction/cancel": InteractionSnapshotSchema,
} as const;

export const InteractionChangedParamsSchema = z
  .object({
    serverInstanceId: z
      .string()
      .regex(/^srv_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(100),
    eventId: z
      .string()
      .regex(/^evt_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(100),
    sequence: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    generation: z.number().int().min(1).max(MAX_SAFE_INTEGER),
    occurredAt: timestamp,
    threadId,
    requestId,
    requestRevision: revision,
    eventSequence: revision,
    kind: z.enum(["created", "draft_changed", "answered", "cancelled", "superseded"]),
  })
  .strict();
