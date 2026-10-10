// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { runStandalonePlanCompletion } from "./standalone-plan-completion-driver.mjs";
import { planExecutionLedgerFact } from "./windows-desktop-smoke.mjs";

/**
 * 只应用当前单一 V1 与 Core 原始 schema，旧迁移链不能作为新架构 reader 的验证前提。
 */
async function applyProductionSqlMigrations(database) {
  const migrationDirectory = fileURLToPath(new URL(
    "../../ja-agent/ja-app-server/src/main/resources/db/migration/",
    import.meta.url,
  ));
  const migrationNames = (await readdir(migrationDirectory))
    .filter((name) => /^V[\d_]+__.*\.sql$/u.test(name));
  assert.deepEqual(migrationNames, ["V1__kernel.sql"]);
  database.exec(await readFile(new URL(
    "../../ja-agent/extensions/ja-agent-store-local/src/main/resources/io/github/kongweiguang/ja/agent/store/local/infrastructure/schema.sql",
    import.meta.url,
  ), "utf8"));
  database.exec(await readFile(join(migrationDirectory, "V1__kernel.sql"), "utf8"));
}

/**
 * 写入通过生产 schema 外键和约束的最小完成账本，直接覆盖 driver 实际查询的字段集合。
 */
function seedCompletedPlanLedger(database, { planId, threadId, runId }) {
  const planRevisionId = "planrev_standalone_fixture";
  const criterionId = "criterion_standalone";
  const stepId = "step_standalone";
  const now = Date.UTC(2026, 0, 1);
  database.exec("PRAGMA foreign_keys=ON; PRAGMA defer_foreign_keys=ON; BEGIN");
  try {
    database.prepare(
      "INSERT INTO ja_workspace(workspace_id,root_path,kind,display_name,trust,created_at_ms,updated_at_ms) " +
        "VALUES(?,?,'PROJECT',?,?,?,?)",
    ).run("ws_standalone_fixture", "/standalone-fixture", "Standalone fixture", "TRUSTED", now, now);
    database.prepare(
      "INSERT INTO ja_conversation_thread(thread_id,owner_user_id,workspace_id,title,provider_id,model_id,access_mode,title_source,created_at_ms,updated_at_ms) " +
        "VALUES(?,'user_local',?,?,?,?,?,?,?,?)",
    ).run(threadId, "ws_standalone_fixture", "Standalone Plan", "provider_fixture", "model_fixture",
      "APPROVAL_REQUIRED", "MANUAL", now, now);
    database.prepare(
      "INSERT INTO ja_plan(plan_id,owner_thread_id,objective,create_operation_key,status,revision," +
        "active_plan_revision_id,active_run_id,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?)",
    ).run(planId, threadId, "独立验收", "standalone:create", "DRAFT", 0,
      null, null, now, now);
    database.prepare(
      "INSERT INTO ja_plan_revision(plan_revision_id,plan_id,revision_number,definition_json,plan_hash,created_by,created_at_ms) " +
        "VALUES(?,?,?,?,?,?,?)",
    ).run(planRevisionId, planId, 1, '{"objective":"独立验收"}', "a".repeat(64), "AGENT", now);
    database.prepare(
      "INSERT INTO ja_plan_step(plan_revision_id,step_id,ordinal,title,description,is_required,dependency_ids_json) " +
        "VALUES(?,?,?,?,?,?,?)",
    ).run(planRevisionId, stepId, 0, "step", "run", 1, "[]");
    database.prepare(
      "INSERT INTO ja_plan_acceptance_criterion(plan_revision_id,criterion_id,ordinal,description,is_required) " +
        "VALUES(?,?,?,?,?)",
    ).run(planRevisionId, criterionId, 0, "evidence", 1);
    database.prepare(
      "INSERT INTO ja_plan_execution(run_id,plan_id,plan_revision_id,plan_hash,status,process_generation," +
        "used_model_rounds,used_tool_calls,used_active_millis,started_at_ms,completed_at_ms,created_at_ms,updated_at_ms) " +
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
    ).run(runId, planId, planRevisionId, "a".repeat(64), "COMPLETED", 1, 3, 2, 1,
      now, now, now, now);
    database.prepare(
      "UPDATE ja_plan SET status='COMPLETED',revision=5,active_plan_revision_id=?,active_run_id=?,updated_at_ms=? " +
        "WHERE plan_id=?",
    ).run(planRevisionId, runId, now, planId);
    const insertClaim = database.prepare(
      "INSERT INTO ja_plan_turn_claim(run_id,turn_id,ordinal,claimed_at_ms,state) VALUES(?,?,?,?,?)",
    );
    insertClaim.run(runId, "turn_standalone_1", 1, now, "SETTLED");
    insertClaim.run(runId, "turn_standalone_2", 2, now, "SETTLED");
    database.prepare(
      "INSERT INTO ja_plan_step_execution(run_id,plan_revision_id,step_id,status,attempt_count,started_at_ms,completed_at_ms,updated_at_ms) " +
      "VALUES(?,?,?,'SUCCEEDED',1,?,?,?)",
    ).run(runId, planRevisionId, stepId, now, now, now);
    const insertEvaluation = database.prepare(
      "INSERT INTO ja_plan_evaluation_attempt(evaluation_id,model_request_id,plan_id,plan_revision_id,run_id," +
        "owner_thread_id,input_digest,attempt_ordinal,profile_json,outcome,certainty,verdict,criteria_json,summary," +
        "started_at_ms,completed_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    );
    for (const [ordinal, verdict] of ["NOT_MET", "NOT_MET", "MET"].entries()) {
      const identity = ordinal + 1;
      insertEvaluation.run(
        `evaluation_${identity}`, `modelreq_${identity}`, planId, planRevisionId, runId, threadId,
        String(identity).repeat(64), 1, "{}", "SUCCEEDED", "UNKNOWN", verdict, "[]", "fixture evaluation",
        now + identity, now + identity,
      );
    }
    database.prepare(
      "INSERT INTO ja_plan_acceptance_evidence(evidence_id,plan_id,run_id,plan_revision_id,criterion_id,step_id," +
        "source_type,source_id,summary,digest,observed_at_ms,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
    ).run("evidence_standalone", planId, runId, planRevisionId, criterionId, stepId, "TEST_REPORT",
      "storage-regression", "SQLite ledger read verified", "b".repeat(64), now, now);
    database.prepare(
      "INSERT INTO ja_plan_event(event_id,plan_id,plan_revision,activity,operation_key,created_at_ms) " +
        "VALUES(?,?,?,?,?,?)",
    ).run("event_standalone", planId, 5, "plan_completed", "standalone:completed", now);
    database.exec("COMMIT");
  } catch (error) {
    database.exec("ROLLBACK");
    throw error;
  }
}

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
  const approvalNames = ["exec_command", "plan_step_update"];
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
    resetPlanContext: (mode) => assert.equal(mode, "standalone"),
    setPlanContext: (context) => assert.equal(context.kind, "plan"),
  };
  const approvalHelper = async (_page, { toolName }) => {
    assert.equal(toolName, approvalNames[approvalIndex]);
    approvalIndex += 1;
    if (approvalIndex === 2) {
      status = "completed";
      ledger.evaluations = [
        {
          plan_id: planId,
          run_id: runId,
          plan_revision_id: revision.planRevisionId,
          outcome: "SUCCEEDED",
          criteria_json: "[]",
          verdict: "NOT_MET",
          started_at_ms: 1,
          completed_at_ms: 1,
          evaluation_id: "evaluation_1",
          model_request_id: "modelreq_1",
        },
        {
          plan_id: planId,
          run_id: runId,
          plan_revision_id: revision.planRevisionId,
          outcome: "SUCCEEDED",
          criteria_json: "[]",
          verdict: "NOT_MET",
          started_at_ms: 2,
          completed_at_ms: 2,
          evaluation_id: "evaluation_2",
          model_request_id: "modelreq_2",
        },
        {
          plan_id: planId,
          run_id: runId,
          plan_revision_id: revision.planRevisionId,
          outcome: "SUCCEEDED",
          criteria_json: "[]",
          verdict: "MET",
          started_at_ms: 3,
          completed_at_ms: 3,
          evaluation_id: "evaluation_3",
          model_request_id: "modelreq_3",
        },
      ];
      ledger.evidence = [{ plan_id: planId, run_id: runId, plan_revision_id: revision.planRevisionId, evidence_id: "evidence_1" }];
      ledger.claims = [
        { run_id: runId, turn_id: "turn_1", state: "SETTLED" },
        { run_id: runId, turn_id: "turn_2", state: "SETTLED" },
      ];
      ledger.events = [{ plan_id: planId, activity: "plan_completed" }];
      ledger.run = {
        plan_id: planId, run_id: runId, plan_revision_id: revision.planRevisionId,
        plan_hash: revision.planHash, status: "COMPLETED", completed_at_ms: 3,
      };
      ledger.requiredSteps = [{ step_id: "step_standalone", status: "SUCCEEDED" }];
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
    assert.equal(toolName, "exec_command");
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

/** 已完成 UI 不能掩盖 Run 未提交、错误 revision、失败的最新评估或未完成 required step。 */
test("completion rejects incomplete or mismatched durable facts despite a completed Plan projection", async () => {
  for (const mutate of [
    (ledger) => { ledger.run.status = "RUNNING"; ledger.run.completed_at_ms = null; },
    (ledger) => { ledger.run.plan_hash = "f".repeat(64); },
    (ledger) => { ledger.evaluations.at(-1).outcome = "FAILED"; },
    (ledger) => { ledger.evaluations.push({
      ...ledger.evaluations.at(-1), evaluation_id: "evaluation_4", started_at_ms: 4,
      outcome: "RUNNING", completed_at_ms: null, verdict: null, criteria_json: null,
    }); },
    (ledger) => { ledger.requiredSteps[0].status = "RUNNING"; },
    (ledger) => { ledger.claims[0].state = "CLAIMED"; },
  ]) {
    const harness = createHarness();
    await assert.rejects(() => runStandalonePlanCompletion({
      page: {}, invoke: harness.invoke, threadId: harness.threadId, definition: { objective: "独立验收" },
      providerFixture: harness.providerFixture, deadline: Date.now() + 350,
      approvalHelper: harness.approvalHelper,
      readLedger: async () => { mutate(harness.ledger); return harness.ledger; },
    }), /完成门/u);
  }
});

/**
 * 绕过内存 ledger callback，确保 schema 字段变化必须经过生产只读查询才能被测试捕获。
 */
test("standalone Plan completion 从生产 migration SQLite 读取当前账本列", async () => {
  const harness = createHarness();
  const isolatedDirectory = await mkdtemp(join(tmpdir(), "ja-standalone-plan-ledger-"));
  const databasePath = join(isolatedDirectory, "ja.db");
  const database = new DatabaseSync(databasePath);
  try {
    await applyProductionSqlMigrations(database);
    const executionRunColumns = database.prepare("PRAGMA table_info(ja_plan_execution)").all();
    assert.equal(executionRunColumns.some((column) => column.name === "turns_used"), false);
    seedCompletedPlanLedger(database, harness);
    const smokeLedger = planExecutionLedgerFact({ data: isolatedDirectory }, harness.planId, harness.runId);
    assert.equal(smokeLedger.run.run_count, 1);
    assert.equal(smokeLedger.run.used_model_rounds, 3);
    assert.equal(smokeLedger.run.used_tool_calls, 2);
    assert.equal(smokeLedger.claims.length, 2);
    assert.deepEqual(smokeLedger.toolAttempts, []);
    const evaluationColumns = database.prepare("PRAGMA table_info(ja_plan_evaluation_attempt)").all();
    assert.ok(evaluationColumns.some((column) => column.name === "evaluation_id"));
    assert.ok(evaluationColumns.some((column) => column.name === "model_request_id"));
    database.close();

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
      directories: { databasePath },
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
  } finally {
    if (database.isOpen) database.close();
    await rm(isolatedDirectory, { recursive: true, force: true });
  }
});
