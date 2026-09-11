// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import { runStandalonePlanCompletion } from "./standalone-plan-completion-driver.mjs";

/** 构造生产驱动所需的最小但真实形状的 Plan/Thread projection。 */
function createHarness() {
  const planId = "plan_standalone_fixture";
  const threadId = "thr_standalone_fixture";
  const runId = "run_standalone_fixture";
  const revision = {
    planRevisionId: "planrev_standalone_fixture",
    planHash: "a".repeat(64),
    steps: [{ stepId: "step_standalone", title: "step" }],
    acceptanceCriteria: [{ criterionId: "criterion_standalone" }],
  };
  let status = "draft";
  let currentRunId = null;
  let approvalIndex = 0;
  const approvalNames = ["shell", "plan_step_update"];
  const calls = [];
  const ledger = { evaluations: [], evidence: [], events: [], claims: [], run: undefined };
  const thread = () =>
    approvalIndex < 2
      ? {
          revision: 0,
          turns: [{ turnId: `turn_${approvalIndex + 1}`, status: "waiting_approval" }],
          items: [
            {
              kind: "approval",
              approvalId: `approval_${approvalIndex + 1}`,
              turnId: `turn_${approvalIndex + 1}`,
              callId: `call_${approvalIndex + 1}`,
              toolName: approvalNames[approvalIndex],
              decision: null,
            },
            {
              kind: "tool_call",
              turnId: `turn_${approvalIndex + 1}`,
              callId: `call_${approvalIndex + 1}`,
              toolName: approvalNames[approvalIndex],
              presentation: { status: "waiting_approval" },
            },
          ],
        }
      : { revision: 0, turns: [], items: [] };
  const invoke = async (command, input) => {
    calls.push([command, input]);
    if (command === "ja_thread_read") return thread();
    if (command === "ja_runtime_plan_create")
      return { plan: { planId, revision: 0, status, activeRunId: null } };
    if (command === "ja_runtime_plan_draft_save") {
      status = "draft";
      return { plan: { planId, revision: 1, status, activeRunId: null } };
    }
    if (command === "ja_runtime_plan_propose")
      return {
        plan: { planId, revision: 2, status: "awaiting_approval", activeRunId: null },
        currentRevision: revision,
      };
    if (command === "ja_runtime_plan_execute") {
      status = "executing";
      currentRunId = runId;
      return {
        plan: { planId, revision: 3, status, activeRunId: runId },
        currentRevision: revision,
      };
    }
    if (command === "ja_runtime_plan_read")
      return {
        plan: { planId, revision: 3 + approvalIndex, status, activeRunId: currentRunId },
        currentRevision: revision,
      };
    throw new Error(`unexpected command ${command}`);
  };
  const providerFixture = {
    resetPlanGoalContext: (mode) => assert.equal(mode, "standalone"),
    setPlanGoalContext: (context) => assert.equal(context.kind, "plan"),
  };
  const approvalHelper = async (_page, { toolName }) => {
    assert.equal(toolName, approvalNames[approvalIndex]);
    approvalIndex += 1;
    if (approvalIndex === 2) {
      status = "completed";
      ledger.evaluations = [
        {
          planId,
          runId,
          verdict: "NOT_MET",
          completed_at: "2026-01-01T00:00:01Z",
          request_id: "eval_1",
        },
        {
          planId,
          runId,
          verdict: "NOT_MET",
          completed_at: "2026-01-01T00:00:02Z",
          request_id: "eval_2",
        },
        {
          planId,
          runId,
          verdict: "MET",
          completed_at: "2026-01-01T00:00:03Z",
          request_id: "eval_3",
        },
      ];
      ledger.evidence = [{ planId, runId, evidence_id: "evidence_1" }];
      ledger.claims = [
        { runId, turnId: "turn_1" },
        { runId, turnId: "turn_2" },
      ];
      ledger.events = [{ planId, activity: "plan_completed" }];
      ledger.run = { planId, runId };
    }
  };
  return { planId, threadId, runId, invoke, providerFixture, approvalHelper, ledger, calls };
}

test("standalone Plan 通过动态审批和 SQLite-like 完成门闭环", async () => {
  const harness = createHarness();
  const result = await runStandalonePlanCompletion({
    page: {},
    invoke: harness.invoke,
    threadId: harness.threadId,
    definition: {
      objective: "独立验收",
      scope: ["fixture"],
      nonGoals: [],
      constraints: [],
      acceptanceCriteria: [
        { criterionId: "criterion_standalone", description: "evidence", required: true },
      ],
      steps: [
        {
          stepId: "step_standalone",
          title: "step",
          description: "run",
          required: true,
          dependsOn: [],
        },
      ],
      dependencies: [],
      risks: [],
      verificationStrategy: ["ledger"],
    },
    providerFixture: harness.providerFixture,
    deadline: Date.now() + 5_000,
    readLedger: async () => harness.ledger,
    approvalHelper: harness.approvalHelper,
  });
  assert.deepEqual(
    {
      planId: result.planId,
      runId: result.runId,
      status: result.status,
      turnCount: result.turnCount,
      notMet: result.notMetEvaluationCount,
      met: result.metEvaluationCount,
      evidence: result.evidenceCount,
      events: result.completionEventCount,
    },
    {
      planId: harness.planId,
      runId: harness.runId,
      status: "completed",
      turnCount: 2,
      notMet: 2,
      met: 1,
      evidence: 1,
      events: 1,
    },
  );
  assert.deepEqual(
    harness.calls.slice(0, 5).map(([command]) => command),
    [
      "ja_thread_read",
      "ja_runtime_plan_create",
      "ja_runtime_plan_draft_save",
      "ja_runtime_plan_propose",
      "ja_runtime_plan_execute",
    ],
  );
});

test("缺少完成门事实时不以 completed 状态单独收口", async () => {
  const harness = createHarness();
  harness.approvalHelper = async (_page, { toolName }) => {
    assert.equal(toolName, "shell");
  };
  await assert.rejects(
    () =>
      runStandalonePlanCompletion({
        page: {},
        invoke: harness.invoke,
        threadId: harness.threadId,
        definition: { objective: "独立验收" },
        providerFixture: harness.providerFixture,
        deadline: Date.now() + 250,
        readLedger: async () => harness.ledger,
        approvalHelper: harness.approvalHelper,
      }),
    /完成门/u,
  );
});
