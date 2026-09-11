// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";

const RPC = Object.freeze({
  threadRead: "ja_thread_read",
  planCreate: "ja_runtime_plan_create",
  planRead: "ja_runtime_plan_read",
  planDraftSave: "ja_runtime_plan_draft_save",
  planPropose: "ja_runtime_plan_propose",
  planExecute: "ja_runtime_plan_execute",
});

/**
 * 在一个绝对 deadline 内等待权威状态；暂停、重试和 SQLite 短暂锁等待都不能把预算重置。
 */
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

/**
 * 兼容生产 invoke 的 {ok,value} 外壳和单元测试的直接 RPC 投影，但不吞掉失败 code。
 */
function unwrapRpc(result, label) {
  if (result?.ok === false) throw new Error(`${label} 失败：${result.code ?? "UNKNOWN"}`);
  if (result?.ok === true) return result.value;
  if (result !== null && typeof result === "object") return result;
  throw new Error(`${label} 未返回对象投影`);
}

/**
 * 要求 Plan 投影同时包含当前 revision；后续 CAS identity 只能来自服务端返回，不能由 fixture 猜测。
 */
function requirePlanProjection(result, label) {
  const projection = unwrapRpc(result, label);
  if (
    projection?.plan === null ||
    typeof projection?.plan !== "object" ||
    typeof projection.plan.planId !== "string" ||
    !Number.isSafeInteger(projection.plan.revision)
  ) {
    throw new Error(`${label} 缺少完整 Plan 投影`);
  }
  return projection;
}

/**
 * 将随机幂等键限制在协议允许的字符集内，同时避免把正文、路径或 hash 写入日志。
 */
function idempotencyKey(stage) {
  return `standalone-plan:${stage}:${Date.now().toString(36)}:${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * 从 Thread 权威快照找到当前真正等待审批的 Tool；Plan read 先于此读取执行，防止使用过期审批。
 */
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

/**
 * 每轮先回读 Plan，再从 Thread 取真实审批 Tool；这样不会等待不存在的审批或按固定序列误批。
 */
async function approveNextPendingTool({ page, invoke, threadId, planId, approvalHelper, deadline, signal }) {
  const plan = requirePlanProjection(
    await invoke(RPC.planRead, { threadId, planId }),
    "Plan read",
  );
  if (plan.plan.status === "completed") return { plan, completed: true };
  if (["paused", "stopped"].includes(plan.plan.status)) {
    throw new Error(`独立 Plan 在完成前进入 ${plan.plan.status}`);
  }
  const thread = unwrapRpc(
    await invoke(RPC.threadRead, { threadId, limit: 200 }),
    "Thread read",
  );
  const toolName = pendingToolName(thread);
  if (toolName === undefined) return { plan, completed: false };
  if (typeof approvalHelper !== "function") {
    throw new TypeError("standalone Plan completion driver 缺少 approvalHelper");
  }
  await approvalHelper(page, { threadId, toolName }, deadline, signal);
  return { plan, completed: false, toolName };
}

/**
 * 只读读取独立 Plan 的 SQLite 事实；查询按 Plan/Run 过滤，不能影响正在运行的 App Server 事务。
 */
function readLedgerFromSqlite(directories, planId, runId) {
  const databasePath = directories?.databasePath ?? join(directories?.data ?? "", "ja.db");
  if (!directories?.databasePath && !directories?.data) {
    throw new Error("缺少 readLedger 或 directories.data/databasePath");
  }
  const database = new DatabaseSync(databasePath, { readOnly: true });
  try {
    database.exec("PRAGMA busy_timeout = 5000");
    const run = database
      .prepare(
        "SELECT run_id,plan_id,status,turns_used,used_model_rounds,used_tool_calls " +
          "FROM execution_runs WHERE plan_id=? AND run_id=?",
      )
      .get(planId, runId);
    if (run === undefined) return undefined;
    const claims = database
      .prepare(
        "SELECT run_id,turn_id,ordinal,state FROM plan_turn_claims WHERE run_id=? ORDER BY ordinal",
      )
      .all(runId);
    const evaluations = database
      .prepare(
        "SELECT request_id,plan_id,plan_revision_id,run_id,outcome,verdict,completed_at " +
          "FROM plan_evaluation_requests WHERE plan_id=? AND run_id=? ORDER BY completed_at,request_id",
      )
      .all(planId, runId);
    const evidence = database
      .prepare(
        "SELECT evidence_id,plan_id,run_id,plan_revision_id,criterion_id,step_id,source_type,source_id " +
          "FROM acceptance_evidence WHERE plan_id=? AND run_id=? ORDER BY created_at,evidence_id",
      )
      .all(planId, runId);
    const events = database
      .prepare(
        "SELECT event_sequence,event_id,plan_id,plan_revision,activity,created_at " +
          "FROM plan_events WHERE plan_id=? AND activity='plan_completed' ORDER BY event_sequence",
      )
      .all(planId);
    return { run, claims, evaluations, evidence, events };
  } finally {
    database.close();
  }
}

/**
 * 统一 callback 与 SQLite 账本形状；只接受同一 Plan/Run 的事实，拒绝跨运行拼接完成证据。
 */
function normalizeLedger(raw, planId, runId) {
  if (raw === undefined || raw === null) return undefined;
  const evaluations = raw.evaluations ?? raw.evaluationRequests ?? raw.planEvaluationRequests ?? [];
  const evidence = raw.evidence ?? raw.acceptanceEvidence ?? raw.planEvidence ?? [];
  const events = raw.events ?? raw.planEvents ?? [];
  const run = raw.run;
  const claims = raw.claims ?? raw.turnClaims ?? [];
  if (!Array.isArray(evaluations) || !Array.isArray(evidence) || !Array.isArray(events) || !Array.isArray(claims)) {
    throw new Error("Plan ledger 形状无效");
  }
  const same = (value) =>
    (value?.planId ?? value?.plan_id) === planId &&
    (value?.runId ?? value?.run_id) === runId;
  const scopedEvaluations = evaluations.filter(same);
  const scopedEvidence = evidence.filter(same);
  const scopedEvents = events.filter((value) => (value?.planId ?? value?.plan_id) === planId);
  const scopedClaims = claims.filter(
    (value) => (value?.runId ?? value?.run_id) === undefined || (value?.runId ?? value?.run_id) === runId,
  );
  return {
    run,
    claims: scopedClaims,
    evaluations: scopedEvaluations,
    evidence: scopedEvidence,
    events: scopedEvents,
  };
}

/**
 * 计算完成门的独立证据，不把模型文本、fixture boolean 或 Plan status 单独当作完成事实。
 */
function completionEvidence(plan, ledger, planId, runId) {
  const evaluations = (ledger?.evaluations ?? []).filter((value) => {
    const outcome = value.outcome ?? value.status;
    return outcome === undefined || ["SUCCEEDED", "COMPLETED"].includes(String(outcome).toUpperCase());
  });
  const notMet = evaluations.filter((value) => String(value.verdict ?? "").toUpperCase() === "NOT_MET");
  const met = evaluations.filter((value) => String(value.verdict ?? "").toUpperCase() === "MET");
  const ordered = evaluations.toSorted((left, right) => {
    const a = String(left.completed_at ?? left.completedAt ?? "");
    const b = String(right.completed_at ?? right.completedAt ?? "");
    return a.localeCompare(b) || String(left.request_id ?? left.requestId ?? "").localeCompare(String(right.request_id ?? right.requestId ?? ""));
  });
  const finalEvaluation = ordered.at(-1);
  const claims = ledger?.claims ?? [];
  const turnIds = new Set(claims.map((value) => value.turn_id ?? value.turnId).filter(Boolean));
  const events = (ledger?.events ?? []).filter((value) =>
    String(value.activity ?? value.kind ?? "").toLowerCase() === "plan_completed",
  );
  const evidenceCount = (ledger?.evidence ?? []).filter(
    (value) => (value.planId ?? value.plan_id) === planId && (value.runId ?? value.run_id) === runId,
  ).length;
  return {
    status: plan.plan.status,
    sameRun: ledger?.run === undefined
      ? false
      : (ledger.run.run_id ?? ledger.run.runId) === runId &&
        (ledger.run.plan_id ?? ledger.run.planId) === planId,
    turnCount: turnIds.size,
    notMetEvaluationCount: notMet.length,
    metEvaluationCount: met.length,
    evidenceCount,
    completionEventCount: events.length,
    finalEvaluationVerdict: String(finalEvaluation?.verdict ?? "").toUpperCase() || null,
    completionGateVerified:
      plan.plan.status === "completed" &&
      (ledger?.run?.run_id ?? ledger?.run?.runId) === runId &&
      (ledger?.run?.plan_id ?? ledger?.run?.planId) === planId &&
      notMet.length >= 1 &&
      met.length >= 1 &&
      String(finalEvaluation?.verdict ?? "").toUpperCase() === "MET" &&
      turnIds.size >= 2 &&
      evidenceCount > 0 &&
      events.length === 1,
  };
}

/**
 * 创建、定稿、原子执行并持续推进独立 Plan；完成只在服务端状态与 SQLite 完成门同时满足时返回。
 */
export async function runStandalonePlanCompletion(options) {
  const {
    page,
    invoke,
    threadId,
    definition,
    providerFixture,
    deadline,
    signal,
    readLedger,
    directories,
    approvalHelper,
  } = options ?? {};
  if (typeof invoke !== "function") throw new TypeError("standalone Plan completion driver 缺少 invoke");
  if (typeof threadId !== "string" || threadId.length === 0) throw new TypeError("threadId is required");
  if (definition === null || typeof definition !== "object") throw new TypeError("definition is required");
  if (providerFixture === null || typeof providerFixture !== "object") throw new TypeError("providerFixture is required");
  if (!Number.isFinite(deadline)) throw new TypeError("deadline is required");
  if (typeof providerFixture.resetPlanGoalContext !== "function" || typeof providerFixture.setPlanGoalContext !== "function") {
    throw new TypeError("providerFixture 缺少 Plan context API");
  }
  if (typeof readLedger !== "function" && directories === undefined) {
    throw new TypeError("readLedger or directories is required");
  }

  providerFixture.resetPlanGoalContext("standalone");
  const thread = unwrapRpc(await invoke(RPC.threadRead, { threadId, limit: 200 }), "Thread read");
  if (!Number.isSafeInteger(thread?.revision)) throw new Error("Thread read 缺少 revision");
  const created = requirePlanProjection(
    await invoke(RPC.planCreate, {
      owner: { kind: "thread", threadId },
      objective: definition.objective,
      expectedThreadRevision: thread.revision,
      idempotencyKey: idempotencyKey("create"),
    }),
    "Plan create",
  );
  const planId = created.plan.planId;
  let current = requirePlanProjection(
    await invoke(RPC.planDraftSave, {
      threadId,
      planId,
      expectedPlanRevision: created.plan.revision,
      idempotencyKey: idempotencyKey("draft"),
      draft: definition,
    }),
    "Plan draft save",
  );
  current = requirePlanProjection(
    await invoke(RPC.planPropose, {
      threadId,
      planId,
      expectedPlanRevision: current.plan.revision,
      idempotencyKey: idempotencyKey("propose"),
    }),
    "Plan propose",
  );
  const revision = current.currentRevision;
  if (revision === null || typeof revision?.planRevisionId !== "string" || typeof revision?.planHash !== "string") {
    throw new Error("Plan propose 缺少冻结 revision identity");
  }
  current = requirePlanProjection(
    await invoke(RPC.planExecute, {
      threadId,
      planId,
      expectedPlanRevision: current.plan.revision,
      planRevisionId: revision.planRevisionId,
      planHash: revision.planHash,
      idempotencyKey: idempotencyKey("execute"),
    }),
    "Plan execute",
  );
  const runId = current.plan.activeRunId;
  if (typeof runId !== "string") throw new Error("Plan execute 未创建 Run");
  const step = revision.steps?.[0];
  const criterion = revision.acceptanceCriteria?.[0];
  if (typeof step?.stepId !== "string" || typeof criterion?.criterionId !== "string") {
    throw new Error("Plan revision 缺少 step/criterion identity");
  }
  providerFixture.setPlanGoalContext({
    kind: "plan",
    planId,
    planRevision: current.plan.revision,
    runId,
    planRevisionId: revision.planRevisionId,
    stepId: step.stepId,
    criterionId: criterion.criterionId,
  });

  let latestPlan = current;
  const approvalDeadline = deadline;
  while (Date.now() < approvalDeadline) {
    const round = await approveNextPendingTool({
      page,
      invoke,
      threadId,
      planId,
      approvalHelper,
      deadline: approvalDeadline,
      signal,
    });
    latestPlan = round.plan;
    if (round.completed) break;
    if (latestPlan.plan.status === "executing" && latestPlan.plan.revision !== current.plan.revision) {
      providerFixture.setPlanGoalContext({
        kind: "plan",
        planId,
        planRevision: latestPlan.plan.revision,
        stepStatus: latestPlan.stepExecutions?.find((execution) => execution.stepId === step.stepId)?.status,
        runId,
        planRevisionId: revision.planRevisionId,
        stepId: step.stepId,
        criterionId: criterion.criterionId,
      });
    }
    await delay(100, undefined, { signal });
  }
  if (latestPlan.plan.status !== "completed") {
    latestPlan = requirePlanProjection(
      await invoke(RPC.planRead, { threadId, planId }),
      "Final Plan read",
    );
  }
  const ledgerReader = typeof readLedger === "function"
    ? readLedger
    : (selectedPlanId, selectedRunId) => readLedgerFromSqlite(directories, selectedPlanId, selectedRunId);
  const evidence = await waitForCondition(
    "独立 Plan 完成门",
    async () => {
      const projection = requirePlanProjection(
        await invoke(RPC.planRead, { threadId, planId }),
        "Completion Plan read",
      );
      const rawLedger = await ledgerReader(planId, runId);
      const ledger = normalizeLedger(rawLedger, planId, runId);
      const result = completionEvidence(projection, ledger, planId, runId);
      return result.completionGateVerified ? result : false;
    },
    deadline,
    signal,
  );
  assert.equal(evidence.completionGateVerified, true);
  return { planId, runId, ...evidence, completionEvidence: evidence };
}
