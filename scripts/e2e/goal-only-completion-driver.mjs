// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const RPC = Object.freeze({
  threadRead: "ja_thread_read",
  goalRead: "ja_runtime_goal_read",
});

/**
 * 从 Provider 当前 system binding 携带的多个历史/当前 Goal binding 中读取最后一个权威
 * CAS revision，并核对 Goal identity；continuation 不能使用首个匹配或 driver 初始化快照。
 */
export function readCurrentGoalBindingRevision(serializedPayload, expectedGoalId) {
  const matches = [
    ...String(serializedPayload ?? "").matchAll(
      /Current Goal binding:[^\n]*?goalId=([^,\n]+),[^\n]*?expectedGoalRevision=(\d+)/gu,
    ),
  ];
  const current = matches.at(-1);
  if (current === undefined) return undefined;
  if (typeof expectedGoalId === "string" && current[1] !== expectedGoalId) return undefined;
  const rawRevision = current[2];
  if (rawRevision === undefined) return undefined;
  const revision = Number(rawRevision);
  return Number.isSafeInteger(revision) ? revision : undefined;
}

/**
 * 只有会生成 Goal Tool 的 continuation 才需要 CAS binding；独立 evaluator 请求不带 Tool
 * binding，必须继续走专用 JSON 评估响应，不能因缺少 binding 被 fixture 当成 500。
 */
export function readGoalToolBindingRevision({
  instructions,
  expectedGoalId,
  evaluationRequest = false,
} = {}) {
  if (evaluationRequest) return undefined;
  return readCurrentGoalBindingRevision(instructions, expectedGoalId);
}

/** 在固定 deadline 内等待 Goal 权威投影，暂停和重试不能重新计算验收预算。 */
async function waitForCondition(label, predicate, deadline, signal, intervalMs = 100) {
  let lastError;
  while (Date.now() < deadline) {
    if (signal?.aborted) throw signal.reason ?? new Error(`${label} aborted`);
    try {
      const value = await predicate();
      if (value !== false && value !== undefined && value !== null) return value;
    } catch (error) {
      lastError = error;
    }
    const waitMs = Math.min(intervalMs, Math.max(1, deadline - Date.now()));
    await delay(waitMs, undefined, { signal });
  }
  throw new Error(`${label} 未在期限内满足`, { cause: lastError });
}

/** 兼容生产 invoke 外壳和聚焦测试直接投影，同时保留失败 code 供诊断。 */
function unwrapRpc(result, label) {
  if (result?.ok === false) throw new Error(`${label} 失败：${result.code ?? "UNKNOWN"}`);
  if (result?.ok === true) return result.value;
  if (result !== null && typeof result === "object") return result;
  throw new Error(`${label} 未返回对象投影`);
}

/** Goal-only 驱动只接受服务端当前 projection，不能从页面文字猜测 definition 或 run。 */
function requireGoalProjection(result, label) {
  const projection = unwrapRpc(result, label);
  if (
    projection?.goal === null ||
    typeof projection?.goal !== "object" ||
    typeof projection.goal.goalId !== "string" ||
    !Number.isSafeInteger(projection.goal.revision) ||
    !Number.isSafeInteger(projection.goal.goalDefinitionRevision) ||
    typeof projection.goal.currentRunId !== "string"
  ) {
    throw new Error(`${label} 缺少完整 Goal projection`);
  }
  return projection;
}

/** 从 Thread 权威 item 中定位指定未决 Goal Tool，避免按固定 Tool 顺序误批。 */
function pendingToolName(threadSnapshot) {
  const items = Array.isArray(threadSnapshot?.items) ? threadSnapshot.items : [];
  const turns = Array.isArray(threadSnapshot?.turns) ? threadSnapshot.turns : [];
  const approvals = items
    .filter((item) => item?.kind === "approval" && item.decision === null)
    .toReversed();
  for (const approval of approvals) {
    const turn = turns.find((candidate) => candidate?.turnId === approval.turnId);
    const tool = items.find(
      (candidate) =>
        candidate?.kind === "tool_call" &&
        candidate.turnId === approval.turnId &&
        candidate.callId === approval.callId,
    );
    if (
      turn?.status === "waiting_approval" &&
      tool?.presentation?.status === "waiting_approval" &&
      typeof tool.toolName === "string" &&
      tool.toolName.length > 0
    ) {
      return tool.toolName;
    }
  }
  return undefined;
}

/** 每次审批前重新读取 Goal 与 Thread，保证异步 continuation 使用当前 owner identity。 */
async function approveNextPendingGoalTool({
  page,
  invoke,
  threadId,
  goalId,
  approvalHelper,
  deadline,
  signal,
}) {
  const goal = requireGoalProjection(await invoke(RPC.goalRead, { goalId }), "Goal read");
  if (goal.goal.status === "achieved") return { goal, completed: true };
  if (["paused", "stopped"].includes(goal.goal.status)) {
    throw new Error(`Goal-only 在完成前进入 ${goal.goal.status}`);
  }
  if (goal.goal.planLink !== null) throw new Error("Goal-only unexpectedly has a Plan link");
  const thread = unwrapRpc(await invoke(RPC.threadRead, { threadId, limit: 200 }), "Thread read");
  const toolName = pendingToolName(thread);
  if (toolName === undefined) return { goal, completed: false };
  if (typeof approvalHelper !== "function") {
    throw new TypeError("Goal-only completion driver 缺少 approvalHelper");
  }
  await approvalHelper(page, { goalId, threadId, toolName }, deadline, signal);
  return { goal, completed: false, toolName };
}

/** 从隔离 SQLite 读取 Goal-only run 的完整证据账本，所有查询都按 Goal/Run 参数化过滤。 */
function readGoalLedgerFromSqlite(directories, goalId, goalDefinitionRevision, runId) {
  const databasePath = directories?.databasePath ?? join(directories?.data ?? "", "ja.db");
  if (!directories?.databasePath && !directories?.data) {
    throw new Error("缺少 readLedger 或 directories.data/databasePath");
  }
  const database = new DatabaseSync(databasePath, { readOnly: true });
  try {
    database.exec("PRAGMA busy_timeout = 5000");
    const run = database
      .prepare(
        "SELECT run_id,goal_id,plan_id,goal_definition_revision,plan_revision_id,status " +
          "FROM execution_runs WHERE goal_id=? AND run_id=?",
      )
      .get(goalId, runId);
    if (run === undefined) return undefined;
    const attempts = database
      .prepare(
        "SELECT tool_attempt_id,goal_id,plan_id,goal_definition_revision,run_id,plan_revision_id," +
          "step_id,turn_id,call_id,state,side_effect,result_digest FROM goal_tool_attempts " +
          "WHERE goal_id=? AND run_id=? ORDER BY prepared_at,tool_attempt_id",
      )
      .all(goalId, runId);
    const evidence = database
      .prepare(
        "SELECT evidence_id,goal_id,plan_id,goal_definition_revision,run_id,plan_revision_id," +
          "criterion_id,step_id,source_type,source_id FROM acceptance_evidence " +
          "WHERE goal_id=? AND run_id=? ORDER BY created_at,evidence_id",
      )
      .all(goalId, runId);
    const evaluations = database
      .prepare(
        "SELECT evaluation_id,goal_id,goal_definition_revision,run_id,plan_revision_id,status," +
          "verdict,completed_at FROM goal_evaluations WHERE goal_id=? AND run_id=? " +
          "ORDER BY completed_at,evaluation_id",
      )
      .all(goalId, runId);
    const events = database
      .prepare(
        "SELECT event_sequence,event_id,goal_id,goal_revision,kind," +
          "json_extract(payload_json,'$.activity') AS activity,created_at FROM goal_events " +
          "WHERE goal_id=? AND json_extract(payload_json,'$.activity')='achieved' " +
          "ORDER BY event_sequence",
      )
      .all(goalId);
    const pendingToolCount =
      database
        .prepare(
          "SELECT COUNT(*) AS count FROM goal_tool_attempts WHERE goal_id=? AND run_id=? " +
            "AND state IN ('PREPARED','STARTED','UNKNOWN')",
        )
        .get(goalId, runId)?.count ?? 0;
    const pendingInteractionCount =
      database
        .prepare(
          "SELECT COUNT(*) AS count FROM interaction_requests WHERE goal_id=? AND run_id=? " +
            "AND status='PENDING'",
        )
        .get(goalId, runId)?.count ?? 0;
    return {
      run,
      attempts,
      evidence,
      evaluations,
      events,
      pendingToolCount: Number(pendingToolCount),
      pendingInteractionCount: Number(pendingInteractionCount),
      goalId,
      goalDefinitionRevision,
      runId,
    };
  } finally {
    database.close();
  }
}

/** 统一 SQLite/测试 callback 的字段形状，拒绝跨 Goal 或跨 Run 拼接证据。 */
function normalizeLedger(raw, goalId, goalDefinitionRevision, runId) {
  if (raw === undefined || raw === null) return undefined;
  const run = raw.run;
  const attempts = raw.attempts ?? raw.toolAttempts ?? [];
  const evidence = raw.evidence ?? raw.acceptanceEvidence ?? [];
  const evaluations = raw.evaluations ?? raw.goalEvaluations ?? [];
  const events = raw.events ?? raw.goalEvents ?? [];
  if (
    !Array.isArray(attempts) ||
    !Array.isArray(evidence) ||
    !Array.isArray(evaluations) ||
    !Array.isArray(events)
  ) {
    throw new Error("Goal-only ledger 形状无效");
  }
  const sameIdentity = (value) =>
    (value?.goalId ?? value?.goal_id) === goalId &&
    (value?.goalDefinitionRevision ?? value?.goal_definition_revision) === goalDefinitionRevision &&
    (value?.runId ?? value?.run_id) === runId;
  return {
    run,
    attempts: attempts.filter(sameIdentity),
    evidence: evidence.filter(sameIdentity),
    evaluations: evaluations.filter(sameIdentity),
    events: events.filter((value) => (value?.goalId ?? value?.goal_id) === goalId),
    pendingToolCount: Number(raw.pendingToolCount ?? 0),
    pendingInteractionCount: Number(raw.pendingInteractionCount ?? 0),
  };
}

/** 读取兼容 DTO 的可空身份字段；`null` 是 Goal-only 的真实断言，不能被 `??` 吃掉。 */
function readNullableField(value, camelName, snakeName) {
  if (
    value !== null &&
    typeof value === "object" &&
    Object.prototype.hasOwnProperty.call(value, camelName)
  ) {
    return value[camelName];
  }
  return value?.[snakeName];
}

/** 计算 Goal-only 完成门；模型文本、旧 Plan evaluator 与 status 单独变化都不能完成目标。 */
function buildCompletionProof(goal, ledger, goalId, goalDefinitionRevision, runId, criterionId) {
  if (ledger === undefined) throw new Error("Goal-only ledger 不存在");
  const identity = (value) =>
    (value?.goalId ?? value?.goal_id) === goalId &&
    (value?.goalDefinitionRevision ?? value?.goal_definition_revision) === goalDefinitionRevision &&
    (value?.runId ?? value?.run_id) === runId;
  const runIdentity = identity(ledger.run);
  const evidence = ledger.evidence.filter(identity);
  const evaluations = ledger.evaluations.filter(identity);
  const completedEvaluations = evaluations.filter(
    (value) => String(value.status ?? "").toUpperCase() === "COMPLETED",
  );
  const metEvaluations = completedEvaluations.filter(
    (value) => String(value.verdict ?? "").toUpperCase() === "MET",
  );
  const completionEvents = ledger.events.filter(
    (value) => String(value.activity ?? value.kind ?? "").toLowerCase() === "achieved",
  );
  const criterionEvidence = evidence.filter(
    (value) => (value.criterionId ?? value.criterion_id) === criterionId,
  );
  const shellAttempts = ledger.attempts.filter(
    (value) =>
      String(value.state ?? "").toUpperCase() === "SUCCEEDED" &&
      readNullableField(value, "planId", "plan_id") === null,
  );
  const finalEvaluation = completedEvaluations.at(-1);
  const planLinkNull =
    goal.planLink === null &&
    readNullableField(ledger.run, "planId", "plan_id") === null &&
    evidence.every((value) => readNullableField(value, "planId", "plan_id") === null) &&
    evaluations.every(
      (value) => readNullableField(value, "planRevisionId", "plan_revision_id") === null,
    );
  const evidenceFact = {
    goalId,
    goalDefinitionRevision,
    runId,
    count: criterionEvidence.length,
    criterionIds: [
      ...new Set(criterionEvidence.map((value) => value.criterionId ?? value.criterion_id)),
    ],
    sourceIds: criterionEvidence.map((value) => value.sourceId ?? value.source_id),
  };
  const evaluationFact = {
    goalId,
    goalDefinitionRevision,
    runId,
    count: completedEvaluations.length,
    metCount: metEvaluations.length,
    evaluationId: finalEvaluation?.evaluationId ?? finalEvaluation?.evaluation_id ?? null,
    verdict: String(finalEvaluation?.verdict ?? "").toUpperCase() || null,
  };
  const event = completionEvents.at(-1);
  const completionEventFact = {
    goalId,
    goalDefinitionRevision,
    runId,
    count: completionEvents.length,
    eventId: event?.eventId ?? event?.event_id ?? null,
    eventSequence: event?.eventSequence ?? event?.event_sequence ?? null,
  };
  const achieved = goal.status === "achieved";
  const completionGateVerified =
    achieved &&
    planLinkNull &&
    runIdentity &&
    (ledger.run?.status ?? "").toUpperCase() === "COMPLETED" &&
    shellAttempts.length > 0 &&
    evidenceFact.count > 0 &&
    evaluationFact.verdict === "MET" &&
    completionEventFact.count === 1 &&
    ledger.pendingToolCount === 0 &&
    ledger.pendingInteractionCount === 0;
  return {
    achieved,
    planLinkNull,
    goalId,
    goalDefinitionRevision,
    runId,
    evidence: evidenceFact,
    evaluation: evaluationFact,
    completionEvent: completionEventFact,
    pendingToolCount: ledger.pendingToolCount,
    pendingInteractionCount: ledger.pendingInteractionCount,
    completionGateVerified,
  };
}

/** 创建 Goal-only completion proof；Goal 由调用方先创建并暂停/继续，驱动只负责真实收口。 */
export async function runGoalOnlyCompletion(options) {
  const {
    page,
    invoke,
    threadId,
    goal,
    providerFixture,
    deadline,
    signal,
    readLedger,
    directories,
    approvalHelper,
    criterionId,
    stepId = "step_goal_only",
  } = options ?? {};
  if (typeof invoke !== "function") throw new TypeError("Goal-only completion driver 缺少 invoke");
  if (typeof threadId !== "string" || threadId.length === 0)
    throw new TypeError("threadId is required");
  if (goal === null || typeof goal !== "object" || goal.goal === null) {
    throw new TypeError("goal projection is required");
  }
  if (goal.goal.planLink !== null) throw new Error("Goal-only completion 要求 planLink === null");
  if (
    typeof providerFixture?.resetPlanGoalContext !== "function" ||
    typeof providerFixture?.setPlanGoalContext !== "function"
  ) {
    throw new TypeError("providerFixture 缺少 Goal-only context API");
  }
  if (typeof readLedger !== "function" && directories === undefined) {
    throw new TypeError("readLedger or directories is required");
  }
  const goalId = goal.goal.goalId;
  const goalDefinitionRevision = goal.goal.goalDefinitionRevision;
  const runId = goal.goal.currentRunId;
  const selectedCriterionId = criterionId ?? goal.goal.acceptanceCriteria?.[0]?.criterionId;
  if (typeof selectedCriterionId !== "string" || selectedCriterionId.length === 0) {
    throw new Error("Goal-only 缺少稳定 criterionId");
  }
  providerFixture.resetPlanGoalContext("goal-only");
  providerFixture.setPlanGoalContext({
    kind: "goal",
    goalId,
    goalRevision: goal.goal.revision,
    goalDefinitionRevision,
    runId,
    planRevisionId: null,
    stepId,
    criterionId: selectedCriterionId,
    projection: { latestEvaluation: goal.goal.latestEvaluation },
  });

  let latestGoal = goal;
  while (Date.now() < deadline) {
    const round = await approveNextPendingGoalTool({
      page,
      invoke,
      threadId,
      goalId,
      approvalHelper,
      deadline,
      signal,
    });
    latestGoal = round.goal;
    if (round.completed) break;
    await delay(100, undefined, { signal });
  }
  latestGoal = await waitForCondition(
    "Goal-only achieved projection",
    async () => {
      const current = requireGoalProjection(
        await invoke(RPC.goalRead, { goalId }),
        "Goal-only final read",
      );
      return current.goal.status === "achieved" ? current : false;
    },
    deadline,
    signal,
  );
  const ledgerReader =
    typeof readLedger === "function"
      ? readLedger
      : (selectedGoalId, selectedDefinitionRevision, selectedRunId) =>
          readGoalLedgerFromSqlite(
            directories,
            selectedGoalId,
            selectedDefinitionRevision,
            selectedRunId,
          );
  const completionProof = await waitForCondition(
    "Goal-only completion proof",
    async () => {
      const current = requireGoalProjection(
        await invoke(RPC.goalRead, { goalId }),
        "Goal-only proof read",
      );
      const rawLedger = await ledgerReader(goalId, goalDefinitionRevision, runId);
      const ledger = normalizeLedger(rawLedger, goalId, goalDefinitionRevision, runId);
      const proof = buildCompletionProof(
        current.goal,
        ledger,
        goalId,
        goalDefinitionRevision,
        runId,
        selectedCriterionId,
      );
      return proof.completionGateVerified ? proof : false;
    },
    deadline,
    signal,
  );
  assert.equal(completionProof.achieved, true);
  assert.equal(completionProof.planLinkNull, true);
  assert.equal(completionProof.completionEvent.count, 1);
  return {
    goalId,
    goalDefinitionRevision,
    runId,
    status: latestGoal.goal.status,
    ...completionProof,
    completionProof,
  };
}

export const __test = Object.freeze({
  buildCompletionProof,
  normalizeLedger,
  readGoalLedgerFromSqlite,
});
