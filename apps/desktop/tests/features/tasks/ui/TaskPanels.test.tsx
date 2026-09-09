// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import {
  SubagentOverview,
  TaskActivityCard,
  TaskDetailPanel,
  type TaskController,
  type TaskReadModel,
  type TaskSummary,
} from "@/features/tasks";
import type { ConversationAttachmentPort } from "@/features/conversation";
import { sideTaskDraftWorkbenchTab, taskWorkbenchTab } from "@/features/workbench";

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

/** UI mock 保持 controller 契约完整；Tab 改名与持久化仍由对应 owner 的测试覆盖。 */
function controller(overrides: Partial<TaskController> = {}): TaskController {
  return {
    tasks: [],
    loading: false,
    detailLoading: false,
    refresh: vi.fn(async () => undefined),
    refreshDetail: vi.fn(async () => undefined),
    createSideTask: vi.fn(async () => ({
      ...runningTask,
      taskKind: "side_task" as const,
      lifecycle: "independent" as const,
    })),
    followup: vi.fn(async (task) => task),
    rename: vi.fn(async (task, title) => ({ ...task, taskName: title })),
    cancel: vi.fn(async (task) => task),
    resume: vi.fn(async () => undefined),
    approvalRespond: vi.fn(async () => undefined),
    ...overrides,
  };
}

/** 详情 fixture 保持上下文与 Task identity 一致，单测只覆盖 UI 状态闭环。 */
function detail(task: TaskSummary): TaskReadModel {
  return {
    task,
    contextSeed: {
      contextSeedId: "seed_child",
      parentRevision: 7,
      inheritanceMode:
        task.taskKind === "side_task" ? ("effective_context" as const) : ("brief_only" as const),
      taskBrief: [{ type: "text" as const, text: task.taskName }],
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

/** Side Task fixture 只替换类型与状态，保留稳定 identity 方便断言 follow-up。 */
function sideTask(state: TaskSummary["state"]): TaskSummary {
  return {
    ...runningTask,
    taskKind: "side_task",
    lifecycle: "independent",
    state,
    completedAt: ["completed", "failed", "cancelled"].includes(state)
      ? "2026-09-03T08:02:00Z"
      : null,
  };
}

afterEach(cleanup);

describe("Task product panels", () => {
  it("主 Timeline 活动卡通过稳定 Task identity 请求打开详情", () => {
    const onOpen = vi.fn();
    render(
      <TaskActivityCard
        activity={{
          activitySequence: 9,
          activityId: "activity_child",
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
        loading={false}
        onRefresh={vi.fn(async () => undefined)}
        onOpenTask={onOpenTask}
      />,
    );
    expect(screen.getByRole("heading", { name: "需要处理" })).toBeVisible();
    const rows = screen.getAllByRole("treeitem");
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining("检查合同"),
      expect.stringContaining("检查子合同"),
    ]);
    expect(rows[0]).toHaveAttribute("aria-level", "1");
    expect(rows[0]).toHaveAttribute("aria-expanded", "true");
    expect(rows[1]).toHaveAttribute("aria-level", "2");
    fireEvent.click(rows[1]!);
    expect(onOpenTask).toHaveBeenCalledWith(child);
  });

  /** 空白 Tab 不产生服务端任务，也不为内部草稿状态新增可见界面。 */
  it("空白侧边草稿直到生产 Composer 首次发送才创建", async () => {
    const current = controller();
    const onCreated = vi.fn();
    render(
      <TaskDetailPanel
        tab={sideTaskDraftWorkbenchTab("thr_root", "12345678")}
        controller={current}
        onCreated={onCreated}
      />,
    );
    expect(current.createSideTask).not.toHaveBeenCalled();
    expect(screen.queryByText("草稿")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "侧边任务名称" })).not.toBeInTheDocument();
    const region = screen.getByRole("region", { name: "新建侧边任务" });
    fireEvent.change(within(region).getByRole("textbox", { name: "消息" }), {
      target: { value: "研究恢复边界" },
    });
    fireEvent.click(within(region).getByRole("button", { name: "发送" }));
    await waitFor(() => expect(current.createSideTask).toHaveBeenCalledTimes(1));
    expect(current.createSideTask).toHaveBeenCalledWith({
      taskName: "研究恢复边界",
      content: [{ type: "text", text: "研究恢复边界" }],
    });
    expect(onCreated).toHaveBeenCalledTimes(1);
  });

  /** Tab 的编辑名称参与首次创建，默认名称仍从首条消息推导。 */
  it("首次发送使用 Tab 编辑后的名称并复用主对话内容轨道", async () => {
    const current = controller();
    const { container } = render(
      <TaskDetailPanel
        tab={{ ...sideTaskDraftWorkbenchTab("thr_root", "renamed123"), label: "核对边界" }}
        controller={current}
        onCreated={vi.fn()}
      />,
    );
    expect(container.querySelector(".ja-task-composer")).toHaveClass(
      "ja-conversation-content-rail",
    );
    expect(container.querySelector(".ja-task-detail-header")).toBeNull();
    fireEvent.change(screen.getByRole("textbox", { name: "消息" }), {
      target: { value: "研究恢复边界" },
    });
    fireEvent.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() =>
      expect(current.createSideTask).toHaveBeenCalledWith({
        taskName: "核对边界",
        content: [{ type: "text", text: "研究恢复边界" }],
      }),
    );
  });

  it("已有侧边任务加载权威详情前不显示可发送 Composer", () => {
    const current = controller({ detailLoading: true });
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({
          ...sideTask("running"),
          taskThreadId: "thr_pending_detail",
          label: "读取中的侧边任务",
        })}
        controller={current}
        onCreated={vi.fn()}
      />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("正在读取任务");
    expect(screen.queryByRole("textbox", { name: "消息" })).not.toBeInTheDocument();
    expect(current.createSideTask).not.toHaveBeenCalled();
    expect(current.followup).not.toHaveBeenCalled();
  });

  it("已有侧边任务详情读取失败时只提供重试，不显示误导 Composer", () => {
    const refreshDetail = vi.fn(async () => undefined);
    const current = controller({
      detailError: "任务详情暂时无法读取，请重试。",
      refreshDetail,
    });
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({
          ...sideTask("running"),
          taskThreadId: "thr_failed_detail",
          label: "读取失败的侧边任务",
        })}
        controller={current}
        onCreated={vi.fn()}
      />,
    );

    expect(screen.getByRole("heading", { name: "任务详情暂不可用" })).toBeVisible();
    expect(screen.queryByRole("textbox", { name: "消息" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(refreshDetail).toHaveBeenCalledTimes(1);
    expect(current.createSideTask).not.toHaveBeenCalled();
    expect(current.followup).not.toHaveBeenCalled();
  });

  /** 显式提供附件预览能力，避免依赖共享 Composer 已移除的无操作按钮。 */
  it("侧边任务 Composer 将附件、Workspace 与 Skill 冻结为结构化首次输入", async () => {
    const user = userEvent.setup();
    const current = controller();
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ operationId, onEvent }) => {
        onEvent({
          kind: "started",
          operationId,
          attemptId: "attempt_task",
          itemId: "item_task",
          fileName: "contract.md",
          sizeBytes: 128,
          mediaKind: "text",
          mediaType: "text/markdown",
        });
        onEvent({
          kind: "completed",
          operationId,
          attemptId: "attempt_task",
          itemId: "item_task",
          attachment: {
            attachmentId: "att_task",
            fileName: "contract.md",
            sizeBytes: 128,
            mediaKind: "text",
            mediaType: "text/markdown",
          },
        });
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "nothing_importable" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    render(
      <TaskDetailPanel
        tab={sideTaskDraftWorkbenchTab("thr_root", "structured")}
        controller={current}
        onCreated={vi.fn()}
        composerEnvironment={{
          workspaceId: "ws_root",
          runtimeGeneration: 7,
          attachmentPort,
          onOpenAttachmentPreview: vi.fn(),
          skills: [
            {
              skillId: "skill_contract",
              name: "Contract Review",
              description: "检查协议闭集",
              scope: "project",
            },
          ],
          onSearchWorkspacePaths: vi.fn(async (query) => ({
            threadId: "thr_root",
            workspaceId: "ws_root",
            generation: 7,
            query,
            items: [{ relativePath: "contracts/task.json", kind: "file" as const }],
            truncated: false,
          })),
        }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "添加附件" }));
    expect(await screen.findByRole("button", { name: "预览附件 contract.md" })).toBeVisible();
    const input = screen.getByRole("textbox", { name: "消息" });
    await user.type(input, "核对 @task");
    await user.click(await screen.findByRole("option", { name: /task\.json/u }));
    await user.type(input, "$contract");
    await user.click(await screen.findByRole("option", { name: /Contract Review/u }));
    await user.click(screen.getByRole("button", { name: "发送" }));

    await waitFor(() => expect(current.createSideTask).toHaveBeenCalledTimes(1));
    expect(current.createSideTask).toHaveBeenCalledWith({
      taskName: "核对",
      content: [
        {
          type: "workspace_reference",
          workspaceId: "ws_root",
          relativePath: "contracts/task.json",
          kind: "file",
        },
        { type: "skill_reference", skillId: "skill_contract" },
        { type: "attachment", attachmentId: "att_task" },
        { type: "text", text: "核对" },
      ],
    });
  });

  it.each(["completed", "failed"] as const)(
    "%s Side Task 保留显式 follow-up 输入",
    async (state) => {
      const task = sideTask(state);
      const current = controller({ tasks: [task], detail: detail(task) });
      render(
        <TaskDetailPanel
          tab={taskWorkbenchTab({ ...task, label: task.taskName })}
          controller={current}
          onCreated={vi.fn()}
        />,
      );
      const region = screen.getByRole("region", { name: task.taskName });
      fireEvent.change(within(region).getByRole("textbox", { name: "消息" }), {
        target: { value: "继续核对边界" },
      });
      fireEvent.click(within(region).getByRole("button", { name: "发送" }));
      await waitFor(() =>
        expect(current.followup).toHaveBeenCalledWith(task, [
          { type: "text", text: "继续核对边界" },
        ]),
      );
    },
  );

  it("cancelled Side Task 不显示 Composer，也不伪造恢复动作", () => {
    const task = sideTask("cancelled");
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task) })}
        onCreated={vi.fn()}
      />,
    );
    expect(screen.queryByRole("textbox", { name: "消息" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "继续运行" })).not.toBeInTheDocument();
    expect(screen.getByText("此任务已取消，不能在界面中伪恢复。")).toBeVisible();
  });

  it("suspended Child 明确恢复既有 Turn，失败后 alert 保持按钮可重试", async () => {
    const task = { ...runningTask, state: "suspended" as const };
    const resume = vi
      .fn()
      .mockRejectedValueOnce(new Error("恢复失败"))
      .mockResolvedValue(undefined);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: detail(task), resume })}
        onCreated={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "继续运行" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("恢复失败");
    fireEvent.click(within(screen.getByRole("alert")).getByRole("button", { name: "重试" }));
    await waitFor(() => expect(resume).toHaveBeenCalledTimes(2));
  });

  /** 移除重复抬头不影响失败恢复入口和可访问的会话名称。 */
  it("详情与 Transcript 旧投影失败为非阻断 alert，并可权威重读", () => {
    const task = sideTask("completed");
    const refreshDetail = vi.fn(async () => undefined);
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({
          tasks: [task],
          detail: detail(task),
          detailError: "任务详情暂时无法读取，请重试。",
          transcriptError: "任务对话记录暂时无法读取，请重试。",
          refreshDetail,
        })}
        onCreated={vi.fn()}
      />,
    );
    expect(screen.getByRole("region", { name: task.taskName })).toBeVisible();
    expect(screen.queryByRole("heading", { name: task.taskName })).not.toBeInTheDocument();
    const alerts = screen.getAllByRole("alert");
    expect(alerts).toHaveLength(2);
    fireEvent.click(within(alerts[0]!).getByRole("button", { name: "重试" }));
    expect(refreshDetail).toHaveBeenCalledTimes(1);
  });

  it("完整 Transcript 无标题展示思考摘要、Tool、Approval、结果与 Turn error", async () => {
    const approvalRespond = vi
      .fn()
      .mockRejectedValueOnce(new Error("审批响应失败，请确认任务状态后重试。"))
      .mockResolvedValue(undefined);
    const current = controller({
      tasks: [runningTask],
      detail: detail(runningTask),
      approvalRespond,
      transcript: {
        threadId: "thr_child",
        revision: 5,
        turns: [
          {
            turnId: "turn_child",
            status: "waiting_approval",
            requestedAt: "2026-09-03T08:00:00Z",
            updatedAt: "2026-09-03T08:00:30Z",
            completedAt: null,
            errorCode: null,
          },
          {
            turnId: "turn_failed",
            status: "failed",
            requestedAt: "2026-09-03T08:00:00Z",
            updatedAt: "2026-09-03T08:01:00Z",
            completedAt: "2026-09-03T08:01:00Z",
            errorCode: "INTERNAL_ERROR",
          },
        ],
        items: [
          {
            itemId: "item_user",
            turnId: "turn_child",
            kind: "user_input",
            content: [{ type: "text", text: "检查合同" }],
            attachments: [],
          },
          {
            itemId: "item_reasoning",
            turnId: "turn_child",
            kind: "reasoning_summary",
            text: "正在核对契约",
          },
          {
            itemId: "item_tool",
            turnId: "turn_child",
            kind: "tool_call",
            callId: "call_child",
            toolName: "read_file",
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
            turnId: "turn_failed",
            kind: "final_answer",
            text: "已生成安全收口结果",
          },
        ],
        nextCursor: null,
      },
    });
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...runningTask, label: runningTask.taskName })}
        controller={current}
        onCreated={vi.fn()}
      />,
    );
    expect(screen.getByText("检查合同", { selector: "p" })).toBeVisible();
    expect(screen.getByText("正在核对契约")).toBeVisible();
    expect(screen.queryByText("回复过程")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /读取，contracts\/task\.json，失败/ })).toBeVisible();
    expect(screen.getByText("已生成安全收口结果")).toBeVisible();
    expect(screen.getByText("INTERNAL_ERROR")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    expect(await screen.findByText("提交失败，请重试。连接断开时请重新发起操作。")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    await waitFor(() => expect(approvalRespond).toHaveBeenCalledTimes(2));
  });

  it("侧边任务展开后只读展示创建 revision 冻结的上下文预览", () => {
    const task = sideTask("completed");
    const taskDetail = detail(task);
    taskDetail.contextSeed.inheritedContextSummary = "2 条冻结消息";
    taskDetail.contextSeed.inheritedContextPreview = [
      { role: "user", text: "先核对 V1 约束", attachmentIds: ["att_seed"] },
      { role: "assistant", text: "已读取迁移", attachmentIds: [] },
    ];
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...task, label: task.taskName })}
        controller={controller({ tasks: [task], detail: taskDetail })}
        onCreated={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByText("继承自主任务 revision 7"));
    const preview = screen.getByRole("list", { name: "创建时继承的上下文" });
    expect(within(preview).getByText("先核对 V1 约束")).toBeVisible();
    expect(within(preview).getByText("已读取迁移")).toBeVisible();
    expect(within(preview).getByText("1 个附件")).toBeVisible();
  });

  it("取消提示递归计算所有 attached 非终态后代并跳过 independent 分支", () => {
    const target = { ...runningTask, state: "running" as const };
    const attached = {
      ...runningTask,
      taskThreadId: "thr_attached",
      parentThreadId: target.taskThreadId,
      taskName: "运行后代",
      state: "suspended" as const,
      unreadCount: 0,
    };
    const independent = {
      ...sideTask("running"),
      taskThreadId: "thr_independent",
      parentThreadId: target.taskThreadId,
      taskName: "独立后代",
    };
    render(
      <TaskDetailPanel
        tab={taskWorkbenchTab({ ...target, label: target.taskName })}
        controller={controller({ tasks: [target, attached, independent], detail: detail(target) })}
        onCreated={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "取消任务" }));
    expect(screen.getByText("将同时取消 1 个未结束后代")).toBeVisible();
  });
});
