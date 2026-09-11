// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SubagentOverview,
  TaskActivityCard,
  TaskDetailPanel,
  type TaskController,
  type TaskReadModel,
  type TaskSummary,
} from "@/features/tasks";
import {
  useTimelineStore,
  type ConversationInteractionController,
  type ConversationModelOption,
  type ConversationThreadPreferences,
  type TimelineSnapshot,
} from "@/features/conversation";
import type {
  GoalController,
  GoalReadModel,
  PlanReadModel,
  PlanRevision,
  PlanSummary,
} from "@/features/goals";
import { taskWorkbenchTab } from "@/features/workbench";

const runningTask: TaskSummary = {
  taskThreadId: "thr_child",
  parentThreadId: "thr_root",
  rootThreadId: "thr_root",
  originTurnId: "turn_parent",
  taskName: "检查合同",
  depth: 1,
  taskKind: "subagent",
  lifecycle: "attached",
  state: "waiting_approval",
  revision: 5,
  latestActivitySequence: 9,
  unreadCount: 2,
  descendantCount: 1,
  runningDescendantCount: 1,
  needsAttentionCount: 1,
  latestSafeSummary: "等待确认写入",
  startedAt: "2026-09-03T08:00:00Z",
  completedAt: null,
  updatedAt: "2026-09-03T08:01:00Z",
};

/** Child Thread 创建后的真实 idle projection：没有 origin Turn，也没有 content 假象。 */
function idleSideTask(): TaskSummary {
  return {
    ...runningTask,
    taskThreadId: "thr_side_idle",
    taskName: "新侧边任务",
    originTurnId: null,
    taskKind: "side_task",
    lifecycle: "independent",
    state: "idle",
    latestActivitySequence: 0,
    unreadCount: 0,
    descendantCount: 0,
    runningDescendantCount: 0,
    needsAttentionCount: 0,
    latestSafeSummary: null,
    startedAt: null,
  };
}

/** 详情 fixture 只表达服务端 Task/Thread identity 与上下文，不伪造 Transcript 旧结构。 */
function detail(task: TaskSummary): TaskReadModel {
  return {
    task,
    thread: {
      threadId: task.taskThreadId,
      workspaceId: "ws_root",
      activeGoalId: null,
      preferences: {
        providerId: "provider_test",
        modelId: "model_test",
        reasoningLevel: "medium",
        accessMode: "approval_required",
        collaborationMode: "default",
        titleSource: "placeholder",
      },
      title: task.taskName,
      status: "active",
      pinned: false,
      latestTurnStatus: task.state === "idle" ? null : task.state,
      latestTurnSeen: true,
      revision: task.revision,
      createdAt: "2026-09-03T08:00:00Z",
      updatedAt: task.updatedAt,
    },
    contextSeed: {
      contextSeedId: "seed_child",
      parentRevision: 7,
      inheritanceMode: task.taskKind === "side_task" ? "effective_context" : "brief_only",
      taskBrief: [{ type: "text", text: task.taskName }],
      inheritedContextSummary: null,
      inheritedContextPreview: [],
      fingerprint: "a".repeat(64),
      createdAt: "2026-09-03T08:00:00Z",
    },
    activities: [],
    mailbox: [],
    nextCursor: null,
  };
}

const models: readonly ConversationModelOption[] = [
  {
    value: "selection_one",
    providerId: "provider_one",
    providerLabel: "服务商一",
    modelId: "model_one",
    modelIdentifier: "upstream/model-one",
    modelLabel: "模型一",
    contextWindowTokens: 128_000,
    reasoningLevelMap: { medium: "medium", high: "high" },
    defaultReasoningLevel: "medium",
  },
  {
    value: "selection_two",
    providerId: "provider_two",
    providerLabel: "服务商二",
    modelId: "model_two",
    modelIdentifier: "upstream/model-two",
    modelLabel: "模型二",
    contextWindowTokens: 256_000,
    reasoningLevelMap: { medium: "medium" },
    defaultReasoningLevel: "medium",
  },
];

const preferences: ConversationThreadPreferences = {
  providerId: "provider_one",
  modelId: "model_one",
  reasoningLevel: "medium",
  accessMode: "approval_required",
  collaborationMode: "default",
  titleSource: "placeholder",
};

/** UI mock 保持 shared controller 窄契约完整，发送和控制动作只从 Composer 回调观察。 */
function conversation(
  overrides: Partial<ConversationInteractionController> = {},
): ConversationInteractionController {
  return {
    draft: "",
    contextReferences: [],
    preferences,
    models,
    attachments: [],
    attachmentDraftItems: [],
    activeTurn: false,
    suspendedTurn: false,
    disabled: false,
    preferenceBusy: false,
    importingAttachments: false,
    sending: false,
    draftRecoveryRevision: 0,
    localSubmissions: [],
    cancelling: false,
    resuming: false,
    error: undefined,
    clipboardNotice: undefined,
    inputQueue: undefined,
    queuedInputs: [],
    queueAccepting: false,
    updateDraft: vi.fn(),
    updateContextReferences: vi.fn(),
    changeModel: vi.fn(async () => undefined),
    changeReasoning: vi.fn(async () => undefined),
    changeAccessMode: vi.fn(async () => undefined),
    changeCollaborationMode: vi.fn(async () => undefined),
    resetPreferences: vi.fn(async () => undefined),
    importAttachments: vi.fn(async () => undefined),
    importDroppedAttachments: vi.fn(async () => undefined),
    importClipboard: vi.fn(async () => undefined),
    retryAttachment: vi.fn(async () => undefined),
    removeAttachment: vi.fn(async () => undefined),
    send: vi.fn(async () => undefined),
    enqueue: vi.fn(async () => undefined),
    prioritizeQueuedInput: vi.fn(async () => undefined),
    updateQueuedInput: vi.fn(async () => undefined),
    deleteQueuedInput: vi.fn(async () => undefined),
    resume: vi.fn(async () => undefined),
    cancel: vi.fn(async () => undefined),
    approve: vi.fn(async () => undefined),
    ...overrides,
  };
}

/** 注入真实 Timeline reducer 的完整 snapshot，避免 UI 测试退回 Record 或空 store 假数据。 */
function prepareSnapshot(threadId: string, overrides: Partial<TimelineSnapshot> = {}): void {
  useTimelineStore.getState().reset();
  expect(
    useTimelineStore.getState().applyRuntimeStatus({
      status: "ready",
      generation: 1,
      serverInstanceId: "srv_task_panel",
    }),
  ).toBe("applied");
  const snapshot: TimelineSnapshot = {
    threadId,
    revision: 5,
    turns: [],
    items: [],
    inputQueue: null,
    contextUsage: null,
    taskActivities: [],
    goalActivities: [],
    nextCursor: null,
    ...overrides,
  };
  expect(useTimelineStore.getState().applySnapshot(snapshot, "ws_root")).toBe("applied");
}

/** Task controller 的其余动作保持可观察 mock，避免测试自行模拟服务端 projection。 */
function controller(overrides: Partial<TaskController> = {}): TaskController {
  return {
    tasks: [],
    loading: false,
    detailLoading: false,
    refresh: vi.fn(async () => undefined),
    refreshDetail: vi.fn(async () => undefined),
    waitForTaskReady: vi.fn(async () => undefined),
    createSideTask: vi.fn(async () => idleSideTask()),
    rename: vi.fn(async (task, title) => ({ ...task, taskName: title })),
    followup: vi.fn(async (task) => task),
    followupTurn: vi.fn(async () => ({
      accepted: true as const,
      turnId: "turn_followup",
      queued: true,
      threadRevision: 6,
    })),
    close: vi.fn(async () => undefined),
    cancel: vi.fn(async (task) => task),
    resume: vi.fn(async () => undefined),
    approvalRespond: vi.fn(async () => undefined),
    ...overrides,
  };
}

const taskPlanRevision: PlanRevision = {
  planRevisionId: "planrev_2",
  planId: "plan_1",
  revisionNumber: 2,
  planHash: "a".repeat(64),
  objective: "交付生产级 Plan 与 Goal",
  scope: ["React 投影", "Goal Workbench"],
  nonGoals: ["发布"],
  constraints: ["Java 是唯一状态 owner"],
  steps: [
    {
      stepId: "step_contract",
      title: "冻结契约",
      description: "完成三端类型闭集",
      required: true,
      dependencyStepIds: [],
      status: "succeeded",
      blockingReason: null,
    },
  ],
  acceptanceCriteria: [
    {
      criterionId: "criterion_ui",
      description: "计划编辑与审批形成闭环",
      required: true,
      status: "met",
      evidenceIds: [],
    },
  ],
  risks: ["崩溃恢复需真窗验证"],
  verificationStrategy: ["运行聚焦组件测试"],
  createdAt: "2026-09-04T10:00:00+08:00",
  approvedAt: null,
};

/** Goal/Plan projection 在测试文件内闭合，避免 UI 切片跨 feature 借用 fixture。 */
function taskGoalModel(ownerThreadId: string): GoalReadModel {
  const plan: PlanSummary = {
    planId: "plan_1",
    ownerThreadId,
    objective: taskPlanRevision.objective,
    status: "awaiting_approval",
    revision: 3,
    activePlanRevisionId: null,
    activeRunId: null,
    createdAt: "2026-09-04T10:00:00+08:00",
    updatedAt: "2026-09-04T10:10:00+08:00",
  };
  return {
    goal: {
      goalId: "goal_1",
      ownerThreadId,
      revision: 7,
      status: "active",
      phase: "working",
      objective: taskPlanRevision.objective,
      goalDefinitionRevision: 1,
      acceptanceCriteria: [],
      activePlanId: plan.planId,
      activePlanRevisionId: taskPlanRevision.planRevisionId,
      activePlanHash: taskPlanRevision.planHash,
      currentStepId: taskPlanRevision.steps[0]?.stepId ?? null,
      completedRequiredSteps: 1,
      totalRequiredSteps: 1,
      attentionSummary: null,
      updatedAt: "2026-09-04T10:10:00+08:00",
    },
    eventSequence: 7,
    planState: plan,
    planEventSequence: 3,
    plan: taskPlanRevision,
    draft: null,
    evaluation: null,
  };
}

/** Goal controller 只暴露 TaskDetailPanel 实际读取的 projection 与动作契约。 */
function goalController(ownerThreadId: string): GoalController {
  const model = taskGoalModel(ownerThreadId);
  const planModel: PlanReadModel = {
    plan: model.planState!,
    progress: {
      currentStepId: model.goal.currentStepId,
      currentStepTitle:
        taskPlanRevision.steps.find((step) => step.stepId === model.goal.currentStepId)?.title ??
        null,
      completedRequiredSteps: model.goal.completedRequiredSteps,
      totalRequiredSteps: model.goal.totalRequiredSteps,
    },
    revision: model.plan!,
    revisionHydrationRequired: false,
    draft: model.draft,
    approvedPlanRevisionId: null,
    eventSequence: model.planEventSequence!,
  };
  return {
    model,
    planModel,
    revisions: [model.plan!],
    evidence: [],
    loading: false,
    error: undefined,
    busyAction: undefined,
    refresh: vi.fn(async () => undefined),
    create: vi.fn(async () => true),
    createPlan: vi.fn(async () => true),
    pause: vi.fn(async () => true),
    resume: vi.fn(async () => true),
    stop: vi.fn(async () => true),
    saveDraft: vi.fn(async () => true),
    discardDraft: vi.fn(async () => true),
    propose: vi.fn(async () => true),
    finalizePlan: vi.fn(async () => true),
    execute: vi.fn(async () => true),
    pausePlan: vi.fn(async () => true),
    resumePlan: vi.fn(async () => true),
    stopPlan: vi.fn(async () => true),
    attachPlan: vi.fn(async () => true),
    detachPlan: vi.fn(async () => true),
    reject: vi.fn(async () => true),
  };
}

/** 独立 Side Task 只有 Plan projection 时仍可执行，不能伪造一个待挂载的 Goal。 */
function standalonePlanController(ownerThreadId: string): GoalController {
  const base = goalController(ownerThreadId);
  const planModel = base.planModel!;
  return {
    ...base,
    model: undefined,
    planModel: {
      ...planModel,
      plan: {
        ...planModel.plan,
        status: "approved",
        activePlanRevisionId: taskPlanRevision.planRevisionId,
      },
      approvedPlanRevisionId: taskPlanRevision.planRevisionId,
    },
  };
}

/** 终态 Goal projection 模拟 mutation ACK，owner 与当前 Side Task 保持同一 identity。 */
function terminalGoalController(
  ownerThreadId: string,
  status: "achieved" | "stopped",
): GoalController {
  const base = goalController(ownerThreadId);
  if (base.model === undefined) throw new Error("terminal Goal fixture requires a model");
  return {
    ...base,
    model: {
      ...base.model,
      goal: {
        ...base.model.goal,
        status,
        phase: status,
      },
    },
  };
}

afterEach(() => {
  cleanup();
  useTimelineStore.getState().reset();
});

describe("Task product panels", () => {
  it("主 Timeline 活动卡通过稳定 Task identity 请求打开详情", () => {
    const onOpen = vi.fn();
    render(
      <TaskActivityCard
        activity={{
          activitySequence: 9,
          activityId: "activity_child",
          rootThreadId: runningTask.rootThreadId,
          taskThreadId: runningTask.taskThreadId,
          actorThreadId: runningTask.parentThreadId,
          causalTurnId: runningTask.originTurnId,
          kind: "waiting_approval",
          summary: { text: "等待确认写入" },
          createdAt: "2026-09-03T08:01:00Z",
        }}
        task={runningTask}
        onOpen={onOpen}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /打开检查合同/u }));
    expect(onOpen).toHaveBeenCalledWith(runningTask);
  });

  it("总览按 parentThreadId 保持父子邻接与真实 ARIA 层级", () => {
    const child = {
      ...runningTask,
      taskThreadId: "thr_grandchild",
      parentThreadId: runningTask.taskThreadId,
      taskName: "检查子合同",
      depth: 2,
      unreadCount: 0,
    };
    const onOpenTask = vi.fn();
    render(
      <SubagentOverview
        tasks={[child, runningTask]}
        ownerThreadId={runningTask.rootThreadId}
        loading={false}
        onRefresh={vi.fn(async () => undefined)}
        onOpenTask={onOpenTask}
      />,
    );
    const rows = screen.getAllByRole("treeitem");
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining("检查合同"),
      expect.stringContaining("检查子合同"),
    ]);
    expect(rows[0]).toHaveAttribute("aria-level", "1");
    expect(rows[1]).toHaveAttribute("aria-level", "2");
    fireEvent.click(rows[1]!);
    expect(onOpenTask).toHaveBeenCalledWith(child);
  });

  it("idle 侧边 Child Thread 没有 content/turnId 也能用 shared controller 首次发送", async () => {
    const task = idleSideTask();
    const current = controller({ tasks: [task], detail: detail(task) });
    const shared = conversation({ draft: "研究恢复边界" });
    prepareSnapshot(task.taskThreadId);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={current}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
      />,
    );

    expect(screen.getByRole("region", { name: task.taskName })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "消息" })).toHaveValue("研究恢复边界");
    fireEvent.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() =>
      expect(shared.send).toHaveBeenCalledWith({
        text: "研究恢复边界",
        attachmentIds: [],
        contextReferences: [],
      }),
    );
    expect(current.createSideTask).not.toHaveBeenCalled();
    expect(current.followup).not.toHaveBeenCalled();
  });

  it("子任务总览不展示侧聊及其委派分支", () => {
    const side = idleSideTask();
    const sideAgent = {
      ...runningTask,
      taskThreadId: "thr_side_agent",
      parentThreadId: side.taskThreadId,
      taskName: "旁支子任务",
      depth: 2,
    };
    render(
      <SubagentOverview
        ownerThreadId={runningTask.rootThreadId}
        tasks={[side, sideAgent, runningTask]}
        loading={false}
        onRefresh={vi.fn(async () => undefined)}
        onOpenTask={vi.fn()}
      />,
    );
    expect(screen.getAllByRole("treeitem")).toHaveLength(1);
    expect(screen.queryByText("旁支子任务")).not.toBeInTheDocument();
    expect(screen.queryByText(side.taskName)).not.toBeInTheDocument();
  });

  it("侧边 Composer 的模型、推理强度和访问权限都回调 shared controller", async () => {
    const task = idleSideTask();
    const shared = conversation();
    const user = userEvent.setup();
    prepareSnapshot(task.taskThreadId);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
      />,
    );

    await user.click(
      screen.getByTitle("当前模型：upstream/model-one，提供商：服务商一，推理强度：中 (medium)"),
    );
    await user.click(await screen.findByRole("menuitemradio", { name: /upstream\/model-two/u }));
    expect(shared.changeModel).toHaveBeenCalledWith("selection_two");

    await user.click(
      screen.getByTitle("当前模型：upstream/model-one，提供商：服务商一，推理强度：中 (medium)"),
    );
    const reasoningMenu = screen.getByRole("menuitem", { name: /^推理强度/u });
    reasoningMenu.focus();
    await user.keyboard("{ArrowRight}");
    await user.click(await screen.findByRole("menuitemradio", { name: "高 (high)" }));
    expect(shared.changeReasoning).toHaveBeenCalledWith("high");

    await user.click(screen.getByRole("combobox", { name: "访问模式" }));
    await user.click(await screen.findByRole("option", { name: "完全访问" }));
    expect(shared.changeAccessMode).toHaveBeenCalledWith("full_access");
  });

  it("取消 active Turn 后仍保留可编辑 Composer，并把后续输入交给 shared controller", async () => {
    const task = { ...idleSideTask(), state: "running" as const };
    const cancel = vi.fn(async () => undefined);
    const shared = conversation({ activeTurn: true, queueAccepting: true, cancel });
    prepareSnapshot(task.taskThreadId, {
      turns: [
        {
          turnId: "turn_running",
          status: "running",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:00:30Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
    });
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "停止生成" }));
    expect(cancel).toHaveBeenCalledOnce();
    const input = screen.getByRole("textbox", { name: "消息" });
    expect(input).not.toBeDisabled();
    fireEvent.change(input, { target: { value: "取消后继续输入" } });
    expect(shared.updateDraft).toHaveBeenCalledWith("取消后继续输入");
  });

  it("活动 Turn 的新输入走队列，服务端队列对象按真实 identity 展示", async () => {
    const task = { ...idleSideTask(), state: "running" as const };
    const enqueue = vi.fn(async () => undefined);
    const shared = conversation({
      draft: "追加检查",
      activeTurn: true,
      queueAccepting: true,
      enqueue,
      queuedInputs: [
        {
          inputId: "input_1",
          turnId: "turn_running",
          content: [{ type: "text", text: "先前排队" }],
          attachments: [],
          kind: "follow_up",
          status: "pending",
          issue: null,
          inputRevision: 2,
          createdAt: "2026-09-03T08:00:20Z",
        },
      ],
    });
    prepareSnapshot(task.taskThreadId);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
      />,
    );

    expect(screen.getByRole("list", { name: "排队消息" })).toHaveTextContent("先前排队");
    fireEvent.click(screen.getByRole("button", { name: "排队发送" }));
    await waitFor(() =>
      expect(enqueue).toHaveBeenCalledWith({
        text: "追加检查",
        attachmentIds: [],
        contextReferences: [],
      }),
    );
  });

  it("Plan/Goal 内容在侧边 Thread 中可打开详情并由受控回调返回对话", () => {
    const task = idleSideTask();
    const onPlanDetailsChange = vi.fn();
    const shared = conversation({
      preferences: { ...preferences, collaborationMode: "plan" },
    });
    const goal = goalController(task.taskThreadId);
    prepareSnapshot(task.taskThreadId);
    const { rerender } = render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
        goal={goal}
        onPlanDetailsChange={onPlanDetailsChange}
      />,
    );

    expect(screen.getAllByText("交付生产级 Plan 与 Goal")).not.toHaveLength(0);
    const planTimeline = screen.getByRole("article", {
      name: "计划版本 2：交付生产级 Plan 与 Goal",
    });
    fireEvent.click(within(planTimeline).getByRole("button", { name: "查看详情" }));
    expect(onPlanDetailsChange).toHaveBeenCalledWith(true);

    rerender(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
        goal={goal}
        planDetailsOpen
        onPlanDetailsChange={onPlanDetailsChange}
      />,
    );
    expect(screen.getByRole("main")).toHaveAttribute("data-goal-id", "goal_1");
    fireEvent.click(screen.getByRole("button", { name: "返回对话" }));
    expect(onPlanDetailsChange).toHaveBeenCalledWith(false);
  });

  it("独立 Side Task 的 Plan 详情直接执行，不把 Plan 伪装成当前 Goal", async () => {
    const task = idleSideTask();
    const execute = vi.fn(async () => true);
    const attachPlan = vi.fn(async () => true);
    const goal = {
      ...standalonePlanController(task.taskThreadId),
      execute,
      attachPlan,
    };
    prepareSnapshot(task.taskThreadId);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        goal={goal}
        planDetailsOpen
      />,
    );

    expect(screen.getByRole("main")).toHaveAttribute("data-plan-id", "plan_1");
    expect(screen.getByRole("button", { name: "执行" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "用于当前目标" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "执行" }));
    await waitFor(() => expect(execute).toHaveBeenCalledOnce());
    expect(attachPlan).not.toHaveBeenCalled();
  });

  /** 终态 ACK 先行、持久 snapshot 后到时，GoalActivityCard 必须按 Goal identity 去重。 */
  it.each(["achieved", "stopped"] as const)(
    "Side Task 的 %s Goal ACK 立即显示，持久 snapshot 不重复卡片",
    async (status) => {
      const task = idleSideTask();
      const goal = terminalGoalController(task.taskThreadId, status);
      const objective = goal.model!.goal.objective;
      const activityName = `${objective}，${status === "achieved" ? "已达成" : "已停止"}`;
      prepareSnapshot(task.taskThreadId);
      render(
        <TaskDetailPanel
          tab={taskWorkbenchTab({ ...task, label: task.taskName })}
          controller={controller({ tasks: [task], detail: detail(task) })}
          goal={goal}
        />,
      );

      expect(screen.getAllByRole("article", { name: activityName })).toHaveLength(1);
      const persistedSnapshot: TimelineSnapshot = {
        threadId: task.taskThreadId,
        revision: 6,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [
          {
            goalId: goal.model!.goal.goalId,
            objective,
            status,
            goalRevision: goal.model!.goal.revision,
            eventSequence: goal.model!.eventSequence!,
            occurredAt: goal.model!.goal.updatedAt,
          },
        ],
        nextCursor: null,
      };
      expect(useTimelineStore.getState().applySnapshot(persistedSnapshot, "ws_root")).toBe(
        "applied",
      );
      await waitFor(() =>
        expect(screen.getAllByRole("article", { name: activityName })).toHaveLength(1),
      );
    },
  );

  it("Subagent 保持无 Composer，但审批仍走 Task controller 且取消需确认所有 attached 后代", async () => {
    const approvalRespond = vi.fn(async () => undefined);
    const cancel = vi.fn(async (task: TaskSummary) => task);
    const target = runningTask;
    const attached = {
      ...runningTask,
      taskThreadId: "thr_attached",
      parentThreadId: target.taskThreadId,
      taskName: "运行后代",
      state: "running" as const,
      unreadCount: 0,
    };
    prepareSnapshot(target.taskThreadId, {
      turns: [
        {
          turnId: "turn_child",
          status: "waiting_approval",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:00:30Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [
        {
          itemId: "item_approval",
          createdAt: "2026-09-03T08:00:20Z",
          turnId: "turn_child",
          kind: "approval",
          approvalId: "approval_child",
          callId: "call_child",
          toolName: "read_file",
          reason: "允许读取工作区",
          expiresAt: "2099-09-03T08:02:00Z",
          decision: null,
        },
      ],
    });
    const current = controller({
      tasks: [target, attached],
      detail: detail(target),
      approvalRespond,
      cancel,
    });
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...target, label: target.taskName })}
        controller={current}
      />,
    );

    expect(screen.queryByRole("textbox", { name: "消息" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    await waitFor(() =>
      expect(approvalRespond).toHaveBeenCalledWith("approval_child", "turn_child", 5, "approve"),
    );
    fireEvent.click(screen.getByRole("button", { name: "取消任务" }));
    expect(screen.getByText("将同时取消 1 个未结束后代")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
    await waitFor(() => expect(cancel).toHaveBeenCalledWith(target));
  });

  it("真实 Timeline snapshot 保留安全 Markdown、tool、approval、结果和 Turn error", async () => {
    const approvalRespond = vi.fn(async () => undefined);
    prepareSnapshot(runningTask.taskThreadId, {
      turns: [
        {
          turnId: "turn_child",
          status: "waiting_approval",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:00:30Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
        {
          turnId: "turn_failed",
          status: "failed",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:01:00Z",
          completedAt: "2026-09-03T08:01:00Z",
          errorCode: "INTERNAL_ERROR",
          changeSet: null,
        },
      ],
      items: [
        {
          itemId: "item_user",
          createdAt: "2026-09-03T08:00:01Z",
          turnId: "turn_child",
          kind: "user_input",
          content: [{ type: "text", text: "检查 **合同**" }],
          attachments: [],
        },
        {
          itemId: "item_reasoning",
          createdAt: "2026-09-03T08:00:10Z",
          turnId: "turn_child",
          kind: "reasoning_summary",
          text: "正在核对契约",
          modelRound: 1,
        },
        {
          itemId: "item_tool",
          createdAt: "2026-09-03T08:00:20Z",
          turnId: "turn_child",
          kind: "tool_call",
          callId: "call_child",
          toolName: "read_file",
          ordinal: 1,
          presentation: {
            kind: "read",
            title: "读取合同",
            status: "error",
            outputPreview: "读取失败",
            relativePaths: ["contracts/task.json"],
            truncated: false,
          },
        },
        {
          itemId: "item_approval",
          createdAt: "2026-09-03T08:00:21Z",
          turnId: "turn_child",
          kind: "approval",
          approvalId: "approval_child",
          callId: "call_child",
          toolName: "read_file",
          reason: "允许读取工作区",
          expiresAt: "2099-09-03T08:02:00Z",
          decision: null,
        },
        {
          itemId: "item_final",
          createdAt: "2026-09-03T08:01:01Z",
          turnId: "turn_failed",
          kind: "final_answer",
          text: "已生成安全收口结果",
        },
      ],
    });
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...runningTask, label: runningTask.taskName })}
        controller={controller({
          tasks: [runningTask],
          detail: detail(runningTask),
          approvalRespond,
        })}
      />,
    );

    const userMessage = screen.getByRole("article", { name: "用户问题" });
    expect(userMessage).toHaveTextContent("检查 合同");
    expect(within(userMessage).getByText("合同", { selector: "strong" })).toBeVisible();
    expect(screen.getByText("正在核对契约")).toBeVisible();
    expect(
      screen.getByRole("button", { name: /读取，contracts\/task\.json，失败/u }),
    ).toBeVisible();
    expect(screen.getByText("已生成安全收口结果")).toBeVisible();
    expect(screen.getByText("INTERNAL_ERROR")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    await waitFor(() => expect(approvalRespond).toHaveBeenCalledOnce());
  });
});
