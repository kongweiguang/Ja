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
  const requests = new Map();
  const plans = new Map();
  const idempotency = new Map();
  let requestSequence = 0;
  let planSequence = 0;
  let runSequence = 0;

  function thread(threadId) {
    let value = threads.get(threadId);
    if (value === undefined) {
      value = { threadId, revision: 0, pendingRequestId: null };
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
    if (owner.pendingRequestId !== null) {
      throw new InteractionPlanFixtureError("INTERACTION_ALREADY_PENDING", "another request is pending");
    }
    if (!Array.isArray(questions) || questions.length < 1 || questions.length > 3) {
      throw new InteractionPlanFixtureError("INTERACTION_INVALID", "one to three questions are required");
    }
    const requestId = `input_fixture_${++requestSequence}`;
    const request = {
      requestId,
      threadId,
      revision: 0,
      state: "pending",
      questions: clone(questions),
      answers: [],
      collapsed: false,
    };
    requests.set(requestId, request);
    owner.pendingRequestId = requestId;
    owner.revision += 1;
    return clone(request);
  }

  function answer({ requestId, expectedRevision, response, idempotencyKey }) {
    return replay(idempotencyKey, () => {
      const request = requests.get(requestId);
      if (request === undefined) throw new InteractionPlanFixtureError("INTERACTION_NOT_FOUND", "request not found");
      if (request.revision !== expectedRevision) {
        throw new InteractionPlanFixtureError("INTERACTION_REVISION_CONFLICT", "request revision is stale");
      }
      if (request.state !== "pending") {
        throw new InteractionPlanFixtureError("INTERACTION_REQUEST_STALE", "request is not pending");
      }
      const question = request.questions[request.answers.length];
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
      request.answers.push(clone(response));
      request.revision += 1;
      if (request.answers.length === request.questions.length) {
        request.state = "answered";
        thread(request.threadId).pendingRequestId = null;
      }
      return request;
    });
  }

  function collapse(requestId) {
    const request = requireRequest(requestId);
    request.collapsed = true;
    return clone(request);
  }

  function cancel({ requestId, expectedRevision, idempotencyKey }) {
    return replay(idempotencyKey, () => {
      const request = requireRequest(requestId);
      if (request.revision !== expectedRevision) {
        throw new InteractionPlanFixtureError("INTERACTION_REVISION_CONFLICT", "request revision is stale");
      }
      request.state = "cancelled";
      thread(request.threadId).pendingRequestId = null;
      request.revision += 1;
      return request;
    });
  }

  function restart() {
    return [...requests.values()].filter((request) => request.state === "pending").map(clone);
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
      budget: { total: 8, used: 0 },
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

  function requireRequest(requestId) {
    const request = requests.get(requestId);
    if (request === undefined) throw new InteractionPlanFixtureError("INTERACTION_NOT_FOUND", "request not found");
    return request;
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
    readRequest: (requestId) => clone(requireRequest(requestId)),
    readPlan: (planId) => clone(requirePlan(planId)),
  });
}
