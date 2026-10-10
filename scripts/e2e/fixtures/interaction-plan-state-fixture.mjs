// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * Interaction/Plan 验收专用的确定性状态替身。它只用于 runner 单测与竞争场景构造，
 * 不模拟 JA-RPC 传输，也不替代 Java/SQLite 权威事实。
 */

import { createHash } from "node:crypto";

export class InteractionPlanFixtureError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "InteractionPlanFixtureError";
    this.code = code;
  }
}

function clone(value) {
  return structuredClone(value);
}

function hash(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

/**
 * 构造两个 Thread 的问答、计划执行和恢复状态。所有写操作都要求 revision/幂等键，
 * 用来锁定重复提交、旧回答复活和未知副作用盲重放等生产边界。
 */
export function createInteractionPlanStateFixture() {
  const threads = new Map();
  const interactions = new Map();
  const plans = new Map();
  const idempotency = new Map();
  let interactionSequence = 0;
  let planSequence = 0;
  let runSequence = 0;

  function thread(threadId) {
    let value = threads.get(threadId);
    if (value === undefined) {
      value = { threadId, revision: 0, pendingInteractionId: null };
      threads.set(threadId, value);
    }
    return value;
  }

  function replay(key, operation) {
    const previous = idempotency.get(key);
    if (previous !== undefined) return clone(previous);
    const result = operation();
    idempotency.set(key, clone(result));
    return clone(result);
  }

  function ask({ threadId, questions }) {
    const owner = thread(threadId);
    if (owner.pendingInteractionId !== null) {
      throw new InteractionPlanFixtureError("INTERACTION_ALREADY_PENDING", "another request is pending");
    }
    if (!Array.isArray(questions) || questions.length < 1 || questions.length > 3) {
      throw new InteractionPlanFixtureError("INTERACTION_INVALID", "one to three questions are required");
    }
    const interactionId = `interaction_fixture_${++interactionSequence}`;
    const interaction = {
      interactionId,
      threadId,
      interactionRevision: 0,
      state: "pending",
      questions: clone(questions),
      answers: [],
      collapsed: false,
    };
    interactions.set(interactionId, interaction);
    owner.pendingInteractionId = interactionId;
    owner.revision += 1;
    return clone(interaction);
  }

  function answer({ interactionId, expectedInteractionRevision, response, idempotencyKey }) {
    return replay(idempotencyKey, () => {
      const interaction = interactions.get(interactionId);
      if (interaction === undefined) throw new InteractionPlanFixtureError("INTERACTION_NOT_FOUND", "interaction not found");
      if (interaction.interactionRevision !== expectedInteractionRevision) {
        throw new InteractionPlanFixtureError("INTERACTION_REVISION_CONFLICT", "interaction revision is stale");
      }
      if (interaction.state !== "pending") {
        throw new InteractionPlanFixtureError("INTERACTION_REQUEST_STALE", "interaction is not pending");
      }
      const question = interaction.questions[interaction.answers.length];
      if (question === undefined) throw new InteractionPlanFixtureError("INTERACTION_INVALID", "too many answers");
      if (response?.kind === "skip" && question.required === true) {
        throw new InteractionPlanFixtureError("INTERACTION_REQUIRED", "required question cannot be skipped");
      }
      if (response?.kind === "option" && !question.options?.some((option) => option.id === response.optionId)) {
        throw new InteractionPlanFixtureError("INTERACTION_INVALID", "option identity is invalid");
      }
      if (response?.kind === "custom" && typeof response.text !== "string") {
        throw new InteractionPlanFixtureError("INTERACTION_INVALID", "custom answer text is required");
      }
      interaction.answers.push(clone(response));
      interaction.interactionRevision += 1;
      if (interaction.answers.length === interaction.questions.length) {
        interaction.state = "answered";
        thread(interaction.threadId).pendingInteractionId = null;
      }
      return interaction;
    });
  }

  function collapse(interactionId) {
    const interaction = requireInteraction(interactionId);
    interaction.collapsed = true;
    return clone(interaction);
  }

  function cancel({ interactionId, expectedInteractionRevision, idempotencyKey }) {
    return replay(idempotencyKey, () => {
      const interaction = requireInteraction(interactionId);
      if (interaction.interactionRevision !== expectedInteractionRevision) {
        throw new InteractionPlanFixtureError("INTERACTION_REVISION_CONFLICT", "interaction revision is stale");
      }
      interaction.state = "cancelled";
      thread(interaction.threadId).pendingInteractionId = null;
      interaction.interactionRevision += 1;
      return interaction;
    });
  }

  function restart() {
    return [...interactions.values()].filter((interaction) => interaction.state === "pending").map(clone);
  }

  function createPlan({ threadId, objective }) {
    const planId = `plan_fixture_${++planSequence}`;
    const plan = {
      planId,
      threadId,
      objective,
      revision: 0,
      status: "draft",
      currentRevision: null,
      activeRunId: null,
      runCount: 0,
      toolLedger: [],
    };
    plans.set(planId, plan);
    return clone(plan);
  }

  function saveDraft({ planId, expectedRevision, definition }) {
    const plan = requirePlan(planId);
    if (plan.revision !== expectedRevision) throw new InteractionPlanFixtureError("PLAN_REVISION_CONFLICT", "plan revision is stale");
    plan.draft = clone(definition);
    plan.revision += 1;
    return clone(plan);
  }

  function propose({ planId, expectedRevision }) {
    const plan = requirePlan(planId);
    if (plan.revision !== expectedRevision || plan.draft === undefined) {
      throw new InteractionPlanFixtureError("PLAN_REVISION_CONFLICT", "draft is stale or missing");
    }
    plan.currentRevision = {
      revisionId: `${plan.planId}_revision_${plan.revision + 1}`,
      hash: hash(plan.draft),
      definition: clone(plan.draft),
    };
    delete plan.draft;
    plan.revision += 1;
    plan.status = "ready";
    return clone(plan);
  }

  function execute({ planId, expectedRevision, revisionId, revisionHash, idempotencyKey }) {
    return replay(idempotencyKey, () => {
      const plan = requirePlan(planId);
      if (
        plan.revision !== expectedRevision ||
        plan.currentRevision?.revisionId !== revisionId ||
        plan.currentRevision?.hash !== revisionHash
      ) throw new InteractionPlanFixtureError("PLAN_REVISION_CONFLICT", "exact plan revision is required");
      if (plan.activeRunId !== null) return plan;
      plan.activeRunId = `plan_run_fixture_${++runSequence}`;
      plan.runCount += 1;
      plan.status = "running";
      plan.revision += 1;
      return plan;
    });
  }

  function recordTool({ planId, callId, resultState }) {
    const plan = requirePlan(planId);
    if (plan.toolLedger.some((entry) => entry.callId === callId)) return clone(plan);
    plan.toolLedger.push({ callId, resultState });
    return clone(plan);
  }

  function recover(planId) {
    const plan = requirePlan(planId);
    if (plan.toolLedger.some((entry) => entry.resultState === "unknown")) {
      plan.status = "needs_attention";
      return clone(plan);
    }
    return clone(plan);
  }

  function requireInteraction(interactionId) {
    const interaction = interactions.get(interactionId);
    if (interaction === undefined) throw new InteractionPlanFixtureError("INTERACTION_NOT_FOUND", "interaction not found");
    return interaction;
  }

  function requirePlan(planId) {
    const plan = plans.get(planId);
    if (plan === undefined) throw new InteractionPlanFixtureError("PLAN_NOT_FOUND", "plan not found");
    return plan;
  }

  return Object.freeze({
    ask,
    answer,
    collapse,
    cancel,
    restart,
    createPlan,
    saveDraft,
    propose,
    execute,
    recordTool,
    recover,
    readInteraction: (interactionId) => clone(requireInteraction(interactionId)),
    readPlan: (planId) => clone(requirePlan(planId)),
  });
}
