// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { JA_GOAL_COMMANDS, TauriGoalAdapter } from "@/api/tauri/goals";
import type { RuntimeNativeBridge } from "@/api/tauri/runtime";
import type { PlanDraft } from "@/features/goals";
import { createGoalPort } from "@/features/goals/infrastructure/goalAdapterPort";

const timestamp = "2026-09-04T08:00:00Z";
const goal = {
  goalId: "goal_demo",
  owner: { kind: "thread", threadId: "thr_root" },
  objective: "交付 Plan Goal",
  goalDefinitionRevision: 1,
  acceptanceCriteria: [],
  status: "active",
  phase: "working",
  revision: 1,
  planLink: null,
  currentRunId: null,
  currentStepId: null,
  completedRequiredSteps: 0,
  totalRequiredSteps: 0,
  attentionReason: null,
  latestEvaluation: null,
  createdAt: timestamp,
  updatedAt: timestamp,
  achievedAt: null,
  stoppedAt: null,
} as const;
const plan = {
  plan: {
    planId: "plan_demo",
    owner: { kind: "thread", threadId: "thr_root" },
    objective: "交付 Plan Goal",
    status: "draft",
    revision: 1,
    activePlanRevisionId: null,
    activeRunId: null,
    createdAt: timestamp,
    updatedAt: timestamp,
  },
  draft: null,
  currentRevision: null,
  approval: null,
  stepExecutions: [],
  eventSequence: 1,
} as const;

/** 测试 bridge 只记录 command/envelope，所有结果仍经过生产 Zod Schema。 */
function bridgeWithResult(result: unknown): {
  bridge: RuntimeNativeBridge;
  invoke: ReturnType<typeof vi.fn>;
} {
  const invoke = vi.fn<(command: string, args?: Record<string, unknown>) => Promise<unknown>>(
    async () => result,
  );
  return {
    bridge: {
      invoke: <T>(command: string, args?: Record<string, unknown>) =>
        invoke(command, args) as Promise<T>,
      listen: vi.fn(async () => () => undefined),
    },
    invoke,
  };
}

describe("TauriGoalAdapter", () => {
  it("maps current Plan recovery and its independent observation handle", async () => {
    const currentNative = bridgeWithResult({ current: plan });
    const currentPort = createGoalPort(new TauriGoalAdapter(currentNative.bridge), {
      subscribe: () => () => undefined,
    });

    await expect(currentPort.currentPlan("thr_root")).resolves.toMatchObject({
      plan: { planId: "plan_demo" },
    });
    expect(currentNative.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.currentPlanRead, {
      input: { threadId: "thr_root" },
    });

    const observationId = "observe_plan_12345678";
    const observeNative = bridgeWithResult({ ...plan, observationId });
    observeNative.invoke.mockImplementation(async (command) =>
      command === JA_GOAL_COMMANDS.unobservePlan ? { accepted: true } : { ...plan, observationId },
    );
    const adapter = new TauriGoalAdapter(observeNative.bridge);
    const observedPort = createGoalPort(adapter, { subscribe: () => () => undefined });
    await expect(
      observedPort.observePlan({ ownerThreadId: "thr_root", planId: "plan_demo" }),
    ).resolves.toMatchObject({ observationId });
    expect(observeNative.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.observePlan, {
      input: { threadId: "thr_root", planId: "plan_demo" },
    });
    await observedPort.unobservePlan({ observationId });
    expect(observeNative.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.unobservePlan, {
      input: { observationId },
    });
  });

  it("pausePlan 绑定 run/CAS/idempotency 且走专用 command", async () => {
    const native = bridgeWithResult(plan);
    const adapter = new TauriGoalAdapter(native.bridge);
    const input = {
      threadId: "thr_root",
      planId: "plan_demo",
      expectedPlanRevision: 1,
      idempotencyKey: "pause-plan:demo:1",
      runId: "run_demo",
    };
    await expect(adapter.pausePlan(input)).resolves.toMatchObject({
      plan: { planId: "plan_demo" },
    });
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.pausePlan, { input });
  });

  it("reads standalone Plan evidence with the frozen revision and run identity", async () => {
    const native = bridgeWithResult({
      planId: "plan_demo",
      planRevision: 1,
      eventSequence: 2,
      planRevisionId: "planrev_demo",
      runId: "run_demo",
      nextCursor: null,
      items: [
        {
          evidenceId: "evidence_demo",
          goalId: null,
          planId: "plan_demo",
          goalDefinitionRevision: null,
          runId: "run_demo",
          planRevisionId: "planrev_demo",
          criterionId: "criterion_demo",
          stepId: null,
          sourceType: "test_report",
          sourceId: "report:demo",
          summary: "standalone Plan evidence",
          digest: "b".repeat(64),
          observedAt: timestamp,
          createdAt: timestamp,
        },
      ],
    });
    const port = createGoalPort(new TauriGoalAdapter(native.bridge), {
      subscribe: () => () => undefined,
    });

    await expect(
      port.readPlanEvidence({
        ownerThreadId: "thr_root",
        planId: "plan_demo",
        planRevisionId: "planrev_demo",
        runId: "run_demo",
      }),
    ).resolves.toMatchObject({ items: [{ evidenceId: "evidence_demo", runId: "run_demo" }] });
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.planEvidenceList, {
      input: {
        threadId: "thr_root",
        planId: "plan_demo",
        planRevisionId: "planrev_demo",
        runId: "run_demo",
        limit: 200,
      },
    });
  });

  /** execute 是批准后的独立用户意图，必须走专用 command 才能创建 standalone run。 */
  it("execute explicitly starts the approved standalone Plan", async () => {
    const native = bridgeWithResult(plan);
    const adapter = new TauriGoalAdapter(native.bridge);
    const input = {
      threadId: "thr_root",
      planId: "plan_demo",
      expectedPlanRevision: 1,
      idempotencyKey: "execute:demo:1",
      planRevisionId: "planrev_demo",
      planHash: "a".repeat(64),
    };

    await adapter.execute(input);
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.execute, { input });
  });

  /** attach 只绑定精确批准版本，不能复用 standalone execute command。 */
  it("attaches an approved Plan to Goal through the distinct Goal mutation", async () => {
    const linkedGoal = {
      ...goal,
      planLink: {
        planId: "plan_demo",
        planRevisionId: "planrev_demo",
        planHash: "a".repeat(64),
        linkRevision: 1,
        attachedAt: timestamp,
      },
    };
    const native = bridgeWithResult({ goal: linkedGoal, eventSequence: 5 });
    const adapter = new TauriGoalAdapter(native.bridge);
    const input = {
      goalId: "goal_demo",
      expectedGoalRevision: 1,
      idempotencyKey: "attach:demo:1",
      planId: "plan_demo",
      planRevisionId: "planrev_demo",
      planHash: "a".repeat(64),
    };

    await adapter.attachPlan(input);
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.goalPlanAttach, { input });
  });

  it("非法 mutation 在 invoke 前失败关闭", async () => {
    const native = bridgeWithResult({ goal, eventSequence: 4 });
    const adapter = new TauriGoalAdapter(native.bridge);
    await expect(
      adapter.pause({ goalId: "bad", expectedGoalRevision: 1, idempotencyKey: "pause:demo:1" }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(native.invoke).not.toHaveBeenCalled();
  });

  /** 独立 Goal 必须把 child Thread 映射为 independent_task，不能退化成父 Thread owner。 */
  it("maps an independent side-task Goal owner without changing its task identity", async () => {
    const sideGoal = {
      ...goal,
      owner: { kind: "independent_task", taskThreadId: "thr_side" },
    } as const;
    const native = bridgeWithResult({ goal: sideGoal, eventSequence: 2 });
    const port = createGoalPort(new TauriGoalAdapter(native.bridge), {
      subscribe: () => () => undefined,
    });

    await port.create({
      ownerThreadId: "thr_side",
      ownerKind: "independent_task",
      objective: "侧边任务目标",
      expectedGoalRevision: 0,
      idempotencyKey: "goal:create:side",
    });

    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.goalCreate, {
      input: {
        owner: { kind: "independent_task", taskThreadId: "thr_side" },
        objective: "侧边任务目标",
        acceptanceCriteria: [],
        expectedGoalRevision: 0,
        idempotencyKey: "goal:create:side",
      },
    });
  });

  it("unobserve 只释放观察句柄且要求 accepted ACK", async () => {
    const native = bridgeWithResult({ accepted: true });
    const adapter = new TauriGoalAdapter(native.bridge);
    await adapter.unobserve({ observationId: "observe_12345678" });
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.unobserve, {
      input: { observationId: "observe_12345678" },
    });
  });

  /** Goal-only evidence 在 feature port 使用显式 null，进入 JA-RPC 时省略 optional Plan binding。 */
  it("preserves nullable Goal-only evidence without an extra Goal read", async () => {
    const wireEvidence = {
      evidenceId: "evidence_goal_only",
      goalId: "goal_demo",
      planId: null,
      goalDefinitionRevision: 1,
      runId: "run_demo",
      planRevisionId: null,
      criterionId: null,
      stepId: null,
      sourceType: "test_report",
      sourceId: "vitest-goal-only",
      summary: "Goal-only recovery verified",
      digest: "b".repeat(64),
      observedAt: timestamp,
      createdAt: timestamp,
    } as const;
    const native = bridgeWithResult({
      goalId: "goal_demo",
      goalRevision: 1,
      goalDefinitionRevision: 1,
      planRevisionId: null,
      eventSequence: 2,
      items: [wireEvidence],
      nextCursor: null,
    });
    const port = createGoalPort(new TauriGoalAdapter(native.bridge), {
      subscribe: () => () => undefined,
    });

    await expect(
      port.evidence({
        goalId: "goal_demo",
        goalDefinitionRevision: 1,
        planRevisionId: null,
      }),
    ).resolves.toEqual({
      items: [
        expect.objectContaining({
          evidenceId: "evidence_goal_only",
          goalDefinitionRevision: 1,
          criterionId: null,
          planRevisionId: null,
        }),
      ],
    });
    expect(native.invoke).toHaveBeenCalledOnce();
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.evidenceList, {
      input: { goalId: "goal_demo", goalDefinitionRevision: 1, limit: 200 },
    });
  });

  it("maps a complete UI draft to the strict structured wire definition", async () => {
    const native = bridgeWithResult(plan);
    const port = createGoalPort(new TauriGoalAdapter(native.bridge), {
      subscribe: () => () => undefined,
    });
    const draft: PlanDraft = {
      draftId: "draft_local",
      planId: "plan_demo",
      draftRevision: 0,
      basePlanRevisionId: null,
      objective: "交付 Plan Goal",
      scope: ["前端"],
      nonGoals: ["发布"],
      constraints: ["Java 是 owner"],
      dependencies: ["合同"],
      steps: [
        {
          stepId: "step_contract",
          title: "同步合同",
          description: "保持三端一致",
          required: true,
          dependencyStepIds: [],
        },
      ],
      acceptanceCriteria: [
        {
          criterionId: "criterion_contract",
          title: "仅 UI 使用的标题",
          description: "合同 Gate 通过",
          required: true,
        },
      ],
      risks: [],
      verificationStrategy: ["运行合同 Gate"],
      updatedAt: timestamp,
    };

    const saved = await port.saveDraft({
      ownerThreadId: "thr_root",
      planId: "plan_demo",
      draft,
      expectedPlanRevision: 1,
      idempotencyKey: "draft:save:1",
    });

    expect(saved.eventSequence).toBe(1);
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.draftSave, {
      input: {
        threadId: "thr_root",
        planId: "plan_demo",
        expectedPlanRevision: 1,
        idempotencyKey: "draft:save:1",
        draft: {
          objective: "交付 Plan Goal",
          scope: ["前端"],
          nonGoals: ["发布"],
          constraints: ["Java 是 owner"],
          acceptanceCriteria: [
            {
              criterionId: "criterion_contract",
              description: "合同 Gate 通过",
              required: true,
            },
          ],
          steps: [
            {
              stepId: "step_contract",
              title: "同步合同",
              description: "保持三端一致",
              required: true,
              dependsOn: [],
            },
          ],
          dependencies: ["合同"],
          risks: [],
          verificationStrategy: ["运行合同 Gate"],
        },
      },
    });
  });
});
