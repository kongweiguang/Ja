// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
  type InteractionController,
  type InteractionRequest,
  type TimelineSnapshot,
} from "@/features/conversation";
import type { PlanController, PlanReadModel, PlanRevision } from "@/features/plans";
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

/** UI mock 保持 shared controller 窄契约完整，发送和控制动作只从 Composer 回调观察。@author kongweiguang */
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
    editingQuestion: false,
    editableSourceMessageId: undefined,
    disabled: false,
    preferenceBusy: false,
    importingAttachments: false,
    sending: false,
    draftRecoveryRevision: 0,
    localSubmissions: [],
    cancelling: false,
    resuming: false,
    error: undefined,
    modelSettingsRequired: false,
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
    continueReply: vi.fn(async () => undefined),
    editQuestion: vi.fn(),
    cancelEditQuestion: vi.fn(),
    enqueue: vi.fn(async () => undefined),
    prioritizeQueuedInput: vi.fn(async () => undefined),
    updateQueuedInput: vi.fn(async () => undefined),
    deleteQueuedInput: vi.fn(async () => undefined),
    resume: vi.fn(async () => undefined),
    cancel: vi.fn(async () => undefined),
    approve: vi.fn(async () => undefined),
    clearClosedThreads: vi.fn(() => true),
    ...overrides,
    continuationAvailable: overrides.continuationAvailable ?? false,
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
    liveStream: null,
    taskActivities: [],
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
  objective: "交付生产级 Plan",
  scope: ["React 投影", "Plan Workbench"],
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

/** 侧聊和主会话接收相同的 Plan 投影与命令。 */
function planController(ownerThreadId: string): PlanController {
  const planModel: PlanReadModel = {
    plan: {
      planId: "plan_1",
      ownerThreadId,
      objective: taskPlanRevision.objective,
      status: "awaiting_approval",
      revision: 3,
      activePlanRevisionId: taskPlanRevision.planRevisionId,
      activeRunId: null,
      createdAt: taskPlanRevision.createdAt,
      updatedAt: taskPlanRevision.createdAt,
    },
    progress: {
      currentStepId: null,
      currentStepTitle: null,
      completedRequiredSteps: 1,
      totalRequiredSteps: 1,
    },
    revision: taskPlanRevision,
    revisionHydrationRequired: false,
    draft: null,
    approvedPlanRevisionId: null,
    eventSequence: 3,
  };
  return {
    planModel,
    revisions: [taskPlanRevision],
    evidence: [],
    loading: false,
    error: undefined,
    busyAction: undefined,
    refresh: vi.fn(async () => undefined),
    createPlan: vi.fn(async () => true),
    saveDraft: vi.fn(async () => true),
    discardDraft: vi.fn(async () => true),
    propose: vi.fn(async () => true),
    finalizePlan: vi.fn(async () => true),
    execute: vi.fn(async () => true),
    pausePlan: vi.fn(async () => true),
    resumePlan: vi.fn(async () => true),
    stopPlan: vi.fn(async () => true),
    reject: vi.fn(async () => true),
  };
}

/** 已批准的 Plan 保留其冻结版本，供用户明确执行。 */
function standalonePlanController(ownerThreadId: string): PlanController {
  const base = planController(ownerThreadId);
  const planModel = base.planModel!;
  return {
    ...base,
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

/** 卸载后排空 Virtualizer 默认 150ms 的 scroll-end debounce，避免环境销毁后仍访问 window。 */
afterEach(async () => {
  cleanup();
  await new Promise<void>((resolve) => setTimeout(resolve, 200));
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

  /** Side Composer 的回答 ACK 复用主 Timeline 临时投影，并在权威终态到达后原位退让。 */
  it("projects a side-chat answer ACK until the authoritative Tool result arrives", async () => {
    const task = idleSideTask();
    const request: InteractionRequest = {
      interactionId: "interaction_side_ack",
      threadId: task.taskThreadId,
      turnId: "turn_side_ack",
      toolCallId: "call_side_ack",
      questions: [
        {
          questionId: "scope",
          prompt: "采用哪种范围？",
          type: "single",
          required: true,
          allowFreeText: true,
          options: [
            { optionId: "project", label: "按项目覆盖" },
            { optionId: "global", label: "全局统一" },
          ],
        },
      ],
      answers: [{ questionId: "scope", optionIds: ["project"], freeText: null, skipped: false }],
      status: "answered",
      interactionRevision: 2,
      createdAt: "2026-09-03T08:00:00Z",
      updatedAt: "2026-09-03T08:00:01Z",
    };
    const answeredAnswers = {
      scope: { questionId: "scope", optionIds: ["project"], freeText: null, skipped: false },
    };
    const clarification: InteractionController = {
      request: null,
      answeredRequest: request,
      answeredAnswers,
      answers: answeredAnswers,
      pageIndex: 0,
      collapsed: false,
      loading: false,
      saving: false,
      submitting: false,
      answered: true,
      resumeState: "settled",
      conflict: false,
      error: undefined,
      retryAction: undefined,
      setAnswer: vi.fn(),
      goToPage: vi.fn(),
      next: vi.fn(),
      previous: vi.fn(),
      submit: vi.fn(async () => undefined),
      retryDraft: vi.fn(async () => undefined),
      retrySubmit: vi.fn(async () => undefined),
      cancel: vi.fn(async () => undefined),
      setCollapsed: vi.fn(),
      clearError: vi.fn(),
      refresh: vi.fn(async () => undefined),
    };
    const toolCall: Extract<TimelineSnapshot["items"][number], { kind: "tool_call" }> = {
      origin: "native" as const,
      itemId: "item_side_ack",
      createdAt: "2026-09-03T08:00:01Z",
      turnId: "turn_side_ack",
      kind: "tool_call",
      callId: "call_side_ack",
      toolName: "request_user_input",
      ordinal: 0,
      presentation: {
        kind: "read",
        title: "询问偏好",
        status: "running",
        inputPreview: "采用哪种范围？",
        relativePaths: [],
        truncated: false,
      },
    };
    const snapshot: TimelineSnapshot = {
      threadId: task.taskThreadId,
      revision: 5,
      turns: [
        {
          origin: "native" as const,
          turnId: "turn_side_ack",
          status: "running",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:00:01Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [toolCall],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      nextCursor: null,
    };
    prepareSnapshot(task.taskThreadId, snapshot);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={conversation()}
        clarification={clarification}
      />,
    );

    const toolResult = screen.getByRole("button", { name: /request_user_input/u });
    expect(toolResult).toHaveTextContent("已回答 1 个问题");

    act(() => {
      expect(
        useTimelineStore.getState().applySnapshot(
          {
            ...snapshot,
            revision: 6,
            turns: [
              {
                ...snapshot.turns[0]!,
                status: "completed",
                updatedAt: "2026-09-03T08:00:02Z",
                completedAt: "2026-09-03T08:00:02Z",
              },
            ],
            items: [
              {
                ...toolCall,
                presentation: {
                  ...toolCall.presentation,
                  status: "success",
                  summary: "服务端最终摘要",
                },
              },
            ],
          },
          "ws_root",
        ),
      ).toBe("applied");
    });
    await waitFor(() => expect(toolResult).toHaveTextContent("服务端最终摘要"));
    expect(toolResult).not.toHaveTextContent("已回答 1 个问题");
  });

  /** 计数必须和可见委派树共享 owner 过滤，不能把独立侧聊或其子树计入。@author kongweiguang */
  it("子任务总览不展示侧聊及其委派分支，也只统计可见 Subagent", () => {
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
    expect(screen.getByText("共 1 个后代任务")).toBeInTheDocument();
    expect(screen.queryByText("旁支子任务")).not.toBeInTheDocument();
    expect(screen.queryByText(side.taskName)).not.toBeInTheDocument();
  });

  /** 只有独立侧聊及其旁支时，总览应呈现真正的无 Subagent 空态，而非空白树。@author kongweiguang */
  it("子任务总览只含侧聊旁支时显示空态和零派生任务计数", () => {
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
        tasks={[side, sideAgent]}
        loading={false}
        onRefresh={vi.fn(async () => undefined)}
        onOpenTask={vi.fn()}
      />,
    );
    expect(screen.getByText("当前主任务还没有派生任务")).toBeInTheDocument();
    expect(screen.getByText("暂无子智能体")).toBeInTheDocument();
    expect(screen.queryAllByRole("treeitem")).toHaveLength(0);
  });

  /** 无关侧聊摘要不能让真实的 root 任务列表错误变成“无子任务”，错误仍提供原重试入口。@author kongweiguang */
  it("仅有侧聊摘要且 root 读取失败时仍显示真实错误", () => {
    const side = idleSideTask();
    render(
      <SubagentOverview
        ownerThreadId={runningTask.rootThreadId}
        tasks={[side]}
        loading={false}
        error="任务列表读取失败"
        onRefresh={vi.fn(async () => undefined)}
        onOpenTask={vi.fn()}
      />,
    );
    expect(screen.getByText("子智能体暂不可用")).toBeInTheDocument();
    expect(screen.getByText("任务列表读取失败")).toBeInTheDocument();
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
          origin: "native" as const,
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

  it("Plan 内容在侧边 Thread 中可打开详情并由受控回调返回对话", () => {
    const task = idleSideTask();
    const onPlanDetailsChange = vi.fn();
    const shared = conversation({
      preferences: { ...preferences, collaborationMode: "plan" },
    });
    const plan = planController(task.taskThreadId);
    prepareSnapshot(task.taskThreadId);
    const { rerender } = render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
        plan={plan}
        onPlanDetailsChange={onPlanDetailsChange}
      />,
    );

    expect(screen.getAllByText("交付生产级 Plan")).not.toHaveLength(0);
    const planTimeline = screen.getByRole("article", {
      name: "计划版本 2：交付生产级 Plan",
    });
    fireEvent.click(within(planTimeline).getByRole("button", { name: "查看详情" }));
    expect(onPlanDetailsChange).toHaveBeenCalledWith(true);

    rerender(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        conversation={shared}
        composerEnvironment={{ workspaceId: "ws_root" }}
        plan={plan}
        planDetailsOpen
        onPlanDetailsChange={onPlanDetailsChange}
      />,
    );
    expect(screen.getByRole("main")).toHaveAttribute("data-plan-id", "plan_1");
    fireEvent.click(screen.getByRole("button", { name: "返回对话" }));
    expect(onPlanDetailsChange).toHaveBeenCalledWith(false);
  });

  it("独立 Side Task 的 Plan 详情直接执行", async () => {
    const task = idleSideTask();
    const execute = vi.fn(async () => true);
    const plan = {
      ...standalonePlanController(task.taskThreadId),
      execute,
    };
    prepareSnapshot(task.taskThreadId);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        plan={plan}
        planDetailsOpen
      />,
    );

    expect(screen.getByRole("main")).toHaveAttribute("data-plan-id", "plan_1");
    expect(screen.getByRole("button", { name: "执行" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "执行" }));
    await waitFor(() => expect(execute).toHaveBeenCalledOnce());
  });

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
          origin: "native" as const,
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
          origin: "native" as const,
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

  /** CAS 后必须退出旧确认，下一次确认展示刷新后的 attached 子树；终态不再给取消入口。 */
  it("取消冲突后按最新后代范围重新确认，终态 Task 隐藏取消入口", async () => {
    const target = runningTask;
    const oldDescendant = {
      ...runningTask,
      taskThreadId: "thr_attached_old",
      parentThreadId: target.taskThreadId,
      taskName: "旧后代",
      state: "running" as const,
      unreadCount: 0,
    };
    const conflict = Object.assign(new Error("safe conflict"), {
      code: "TASK_CONTEXT_REVISION_CONFLICT" as const,
    });
    const cancel = vi.fn(async (task: TaskSummary) => task);
    cancel.mockRejectedValueOnce(conflict);
    const current = controller({ tasks: [target, oldDescendant], detail: detail(target), cancel });
    const view = render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...target, label: target.taskName })}
        controller={current}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "取消任务" }));
    expect(screen.getByText("将同时取消 1 个未结束后代")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
    await waitFor(() => expect(screen.getByText("safe conflict")).toBeVisible());
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("group", { name: "确认取消任务" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();

    const latest = { ...target, revision: target.revision + 1 };
    const attachedOne = {
      ...oldDescendant,
      taskThreadId: "thr_attached_new_1",
      taskName: "新后代一",
    };
    const attachedTwo = {
      ...oldDescendant,
      taskThreadId: "thr_attached_new_2",
      taskName: "新后代二",
      parentThreadId: attachedOne.taskThreadId,
    };
    const terminalAttached = {
      ...oldDescendant,
      taskThreadId: "thr_attached_terminal",
      taskName: "已结束后代",
      state: "completed" as const,
      completedAt: "2026-09-03T08:02:00Z",
    };
    const independent = {
      ...oldDescendant,
      taskThreadId: "thr_independent",
      taskName: "独立侧聊",
      taskKind: "side_task" as const,
      lifecycle: "independent" as const,
    };
    view.rerender(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...latest, label: latest.taskName })}
        controller={controller({
          tasks: [latest, attachedOne, attachedTwo, terminalAttached, independent],
          detail: detail(latest),
          cancel,
        })}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "取消任务" }));
    expect(screen.getByText("将同时取消 2 个未结束后代")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
    await waitFor(() => expect(cancel).toHaveBeenCalledTimes(2));
    expect(cancel).toHaveBeenNthCalledWith(2, latest);

    const terminal = { ...latest, state: "cancelled" as const, revision: latest.revision + 1 };
    view.rerender(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...terminal, label: terminal.taskName })}
        controller={controller({ tasks: [terminal], detail: detail(terminal), cancel })}
      />,
    );
    expect(screen.queryByRole("button", { name: "取消任务" })).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "确认取消任务" })).not.toBeInTheDocument();
  });

  /** 非 CAS 失败保留原确认控件作为唯一显式重试入口，不额外显示会重复副作用的 Retry。 */
  it("普通取消失败保留确认态并通过同一确认按钮显式重试", async () => {
    const target = runningTask;
    const cancel = vi.fn(async (task: TaskSummary) => task);
    cancel.mockRejectedValueOnce(new Error("transient"));
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...target, label: target.taskName })}
        controller={controller({ tasks: [target], detail: detail(target), cancel })}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "取消任务" }));
    fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
    await waitFor(() => expect(screen.getByText("取消请求未完成，请重试。")).toBeVisible());
    expect(screen.getByRole("group", { name: "确认取消任务" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "确认取消" }));
    await waitFor(() => expect(cancel).toHaveBeenCalledTimes(2));
    expect(cancel).toHaveBeenLastCalledWith(target);
  });

  it("真实 Timeline snapshot 保留安全 Markdown、tool、approval、结果和 Turn error", async () => {
    const approvalRespond = vi.fn(async () => undefined);
    prepareSnapshot(runningTask.taskThreadId, {
      turns: [
        {
          origin: "native" as const,
          turnId: "turn_child",
          status: "waiting_approval",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:00:30Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
        {
          origin: "native" as const,
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
          origin: "native" as const,
          itemId: "item_user",
          createdAt: "2026-09-03T08:00:01Z",
          turnId: "turn_child",
          truncated: false,
          totalCharacters: 9,
          kind: "user_input",
          content: [{ type: "text", text: "检查 **合同**" }],
          attachments: [],
        },
        {
          origin: "native" as const,
          itemId: "item_reasoning",
          createdAt: "2026-09-03T08:00:10Z",
          turnId: "turn_child",
          truncated: false,
          totalCharacters: 6,
          kind: "reasoning_summary",
          text: "正在核对契约",
          modelRound: 1,
        },
        {
          origin: "native" as const,
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
          origin: "native" as const,
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
          origin: "native" as const,
          itemId: "item_final",
          createdAt: "2026-09-03T08:01:01Z",
          turnId: "turn_failed",
          truncated: false,
          totalCharacters: 9,
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
      screen.getByRole("button", { name: /读取，read_file，contracts\/task\.json，失败/u }),
    ).toBeVisible();
    expect(screen.getByText("本次回复未能完成。")).toBeVisible();
    expect(screen.queryByText("已生成安全收口结果")).not.toBeInTheDocument();
    expect(screen.queryByText("INTERNAL_ERROR")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    await waitFor(() => expect(approvalRespond).toHaveBeenCalledOnce());
  });
});
