// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import test from "node:test";
import {
  readGoalToolBindingRevision,
  readCurrentGoalBindingRevision,
  runGoalOnlyCompletion,
  __test,
} from "./goal-only-completion-driver.mjs";

/** 构造不含 Plan 的 Goal-only projection 与同一 Run 的 append-only 事实。 */
function createHarness({ includeEvidence = true, includePlanEvaluation = true } = {}) {
  const goalId = "goal_only_fixture";
  const threadId = "thr_goal_only_fixture";
  const runId = "run_goal_only_fixture";
  const goalDefinitionRevision = 1;
  const criterionId = "criterion_goal_only";
  let status = "active";
  let goalRevision = 2;
  let approvalIndex = 0;
  const calls = [];
  const ledger = {
    run: {
      goalId,
      planId: null,
      goalDefinitionRevision,
      planRevisionId: null,
      runId,
      status: "COMPLETED",
    },
    attempts: [
      {
        goalId,
        planId: null,
        goalDefinitionRevision,
        runId,
        planRevisionId: null,
        state: "SUCCEEDED",
        callId: "call_goal_shell",
      },
    ],
    evidence: includeEvidence
      ? [
          {
            evidenceId: "evidence_goal_shell",
            goalId,
            planId: null,
            goalDefinitionRevision,
            runId,
            planRevisionId: null,
            criterionId,
            sourceId: "call_goal_shell",
          },
        ]
      : [],
    evaluations: [
      ...(includePlanEvaluation
        ? [
            {
              evaluationId: "eval_old_plan",
              goalId: "another_goal",
              planId: "plan_old",
              goalDefinitionRevision,
              runId: "run_old_plan",
              planRevisionId: "planrev_old",
              status: "COMPLETED",
              verdict: "MET",
            },
          ]
        : []),
      {
        evaluationId: "eval_goal_only",
        goalId,
        goalDefinitionRevision,
        runId,
        planRevisionId: null,
        status: "COMPLETED",
        verdict: "MET",
      },
    ],
    events: [
      {
        eventId: "event_goal_achieved",
        eventSequence: 9,
        goalId,
        activity: "achieved",
      },
    ],
    pendingToolCount: 0,
    pendingInteractionCount: 0,
  };
  const projection = () => ({
    goal: {
      goalId,
      goalDefinitionRevision,
      revision: goalRevision,
      status,
      phase: status === "achieved" ? "achieved" : "working",
      planLink: null,
      currentRunId: runId,
      latestEvaluation: status === "achieved" ? { verdict: "met" } : null,
      acceptanceCriteria: [{ criterionId, required: true }],
    },
  });
  const thread = () =>
    approvalIndex < 2
      ? {
          turns: [{ turnId: `turn_goal_${approvalIndex + 1}`, status: "waiting_approval" }],
          items: [
            {
              kind: "approval",
              approvalId: `approval_goal_${approvalIndex + 1}`,
              turnId: `turn_goal_${approvalIndex + 1}`,
              callId: `call_goal_${approvalIndex + 1}`,
              toolName: approvalIndex === 0 ? "shell" : "goal_request_evaluation",
              decision: null,
            },
            {
              kind: "tool_call",
              turnId: `turn_goal_${approvalIndex + 1}`,
              callId: `call_goal_${approvalIndex + 1}`,
              toolName: approvalIndex === 0 ? "shell" : "goal_request_evaluation",
              presentation: { status: "waiting_approval" },
            },
          ],
        }
      : { turns: [], items: [] };
  const invoke = async (command, input) => {
    calls.push([command, input]);
    if (command === "ja_thread_read") return thread();
    if (command === "ja_runtime_goal_read") return projection();
    throw new Error(`unexpected command ${command}`);
  };
  const providerFixture = {
    resetPlanGoalContext: (mode) => assert.equal(mode, "goal-only"),
    setPlanGoalContext: (context) => {
      assert.equal(context.kind, "goal");
      assert.equal(context.goalId, goalId);
      assert.equal(context.goalRevision, goalRevision);
      assert.equal(context.goalDefinitionRevision, goalDefinitionRevision);
      assert.equal(context.planRevisionId, null);
      assert.equal(context.runId, runId);
      assert.equal(context.criterionId, criterionId);
    },
  };
  const approvalHelper = async (_page, { toolName }) => {
    assert.equal(toolName, approvalIndex === 0 ? "shell" : "goal_request_evaluation");
    approvalIndex += 1;
    if (approvalIndex === 2) {
      status = "achieved";
    }
  };
  return {
    goal: projection(),
    goalId,
    threadId,
    runId,
    ledger,
    invoke,
    providerFixture,
    approvalHelper,
    calls,
    criterionId,
  };
}

/** Provider continuation 保留历史输入时，必须选择最后一个服务端 Goal binding revision。 */
test("Goal binding revision parser ignores historical binding", () => {
  const request =
    "Current Goal binding: goalId=goal_only_fixture, expectedGoalRevision=2, runId=run_old\n" +
    "Current Goal binding: goalId=goal_only_fixture, expectedGoalRevision=4, runId=run_current";
  assert.equal(readCurrentGoalBindingRevision(request, "goal_only_fixture"), 4);
  assert.equal(
    readCurrentGoalBindingRevision(
      "Current Goal binding: goalId=another_goal, expectedGoalRevision=9, runId=run_other",
      "goal_only_fixture",
    ),
    undefined,
  );
  assert.equal(readCurrentGoalBindingRevision("no Goal binding"), undefined);
});

/** 独立 evaluator 没有 Tool binding 时必须跳过 CAS 解析并保留 JSON 评估路径。 */
test("独立 evaluator 不要求 Goal tool binding", () => {
  assert.equal(
    readGoalToolBindingRevision({
      instructions: "独立 evaluator 无 Current Goal binding",
      expectedGoalId: "goal_only_fixture",
      evaluationRequest: true,
    }),
    undefined,
  );
  assert.equal(
    readGoalToolBindingRevision({
      instructions:
        "Current Goal binding: goalId=goal_only_fixture, expectedGoalRevision=4, runId=run_current",
      expectedGoalId: "goal_only_fixture",
      evaluationRequest: false,
    }),
    4,
  );
});

/** Goal-only 只在当前 Goal/Run 的 evidence、MET evaluator 与唯一 achieved 事件齐全时通过。 */
test("Goal-only completion driver 通过真实 identity 完成门", async () => {
  const harness = createHarness();
  const result = await runGoalOnlyCompletion({
    page: {},
    invoke: harness.invoke,
    threadId: harness.threadId,
    goal: harness.goal,
    providerFixture: harness.providerFixture,
    deadline: Date.now() + 2_000,
    readLedger: async () => harness.ledger,
    approvalHelper: harness.approvalHelper,
    criterionId: harness.criterionId,
  });
  assert.equal(result.status, "achieved");
  assert.equal(result.completionProof.achieved, true);
  assert.equal(result.completionProof.planLinkNull, true);
  assert.deepEqual(result.completionProof.evidence, {
    goalId: harness.goalId,
    goalDefinitionRevision: 1,
    runId: harness.runId,
    count: 1,
    criterionIds: [harness.criterionId],
    sourceIds: ["call_goal_shell"],
  });
  assert.equal(result.completionProof.evaluation.verdict, "MET");
  assert.equal(result.completionProof.completionEvent.count, 1);
  assert.deepEqual(
    harness.calls.slice(0, 3).map(([command]) => command),
    ["ja_runtime_goal_read", "ja_thread_read", "ja_runtime_goal_read"],
  );
});

/** 旧 Plan evaluator 即使为 MET，也不能替代当前 Goal-only criterion 的证据。 */
test("缺少当前 Goal evidence 时不以 achieved 或旧 Plan evaluation 收口", () => {
  const harness = createHarness({ includeEvidence: false });
  const proof = __test.buildCompletionProof(
    harness.goal.goal,
    __test.normalizeLedger(harness.ledger, harness.goalId, 1, harness.runId),
    harness.goalId,
    1,
    harness.runId,
    harness.criterionId,
  );
  assert.equal(proof.planLinkNull, true);
  assert.equal(proof.evaluation.verdict, "MET");
  assert.equal(proof.evidence.count, 0);
  assert.equal(proof.completionGateVerified, false);
});

/** 过期 Goal identity 必须在 context 入口失败关闭，不能绑定另一个 run 或 Plan revision。 */
test("Goal-only context 拒绝 stale revision 和 Plan 绑定", () => {
  const harness = createHarness();
  assert.throws(
    () =>
      harness.providerFixture.setPlanGoalContext({
        kind: "goal",
        goalId: harness.goalId,
        goalRevision: harness.goal.goal.revision - 1,
        runId: harness.runId,
        planRevisionId: "plan_revision_must_be_null",
        stepId: "step_goal_only",
        criterionId: harness.criterionId,
      }),
    /Expected values|Plan revision/u,
  );
});
