// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  publishTaskHostEvent as publishTaskHostEventRaw,
  useTaskController as useTaskControllerImpl,
  type TaskPort,
  type TaskReadModel,
  type TaskSummary,
} from "@/features/tasks";
import type { TaskHostEvent } from "@/features/tasks/domain/taskModel";
import type {
  TaskApprovalPort,
  TaskResumePort,
  TaskThreadRenamePort,
  TaskTranscriptPort,
  TaskTranscriptSnapshot,
} from "@/features/tasks/application/ports";

type TranscriptProjection = Parameters<typeof useTaskControllerImpl>[0]["applyTranscriptSnapshot"];
type TestTaskControllerOptions = Omit<
  Parameters<typeof useTaskControllerImpl>[0],
  "applyTranscriptSnapshot" | "onSideTaskClosed" | "runtimeFence"
> & {
  applyTranscriptSnapshot?: TranscriptProjection;
  onSideTaskClosed?: Parameters<typeof useTaskControllerImpl>[0]["onSideTaskClosed"];
  runtimeFence?: Parameters<typeof useTaskControllerImpl>[0]["runtimeFence"] | null;
};

type TestRuntimeFence = NonNullable<Parameters<typeof useTaskControllerImpl>[0]["runtimeFence"]>;
const TEST_RUNTIME_FENCE: TestRuntimeFence = { serverInstanceId: "srv_task_test", generation: 1 };

/** Task test event wrapper retains protocol runtime identity so close regressions exercise production fencing. */
function publishTaskHostEvent(
  event: TaskHostEvent,
  fence: typeof TEST_RUNTIME_FENCE = TEST_RUNTIME_FENCE,
): void {
  const params = {
    ...event.params,
    serverInstanceId: fence.serverInstanceId,
    generation: fence.generation,
  };
  publishTaskHostEventRaw({ ...event, params } as TaskHostEvent);
}

/** Most tests use one ready Runtime; null explicitly models an unowned boot state without inventing a fence. @author kongweiguang */
function useTaskController(options: TestTaskControllerOptions) {
  return useTaskControllerImpl({
    ...options,
    runtimeFence:
      options.runtimeFence === null ? undefined : (options.runtimeFence ?? TEST_RUNTIME_FENCE),
    onSideTaskClosed: options.onSideTaskClosed ?? (async () => true),
    applyTranscriptSnapshot: options.applyTranscriptSnapshot ?? acceptTaskTranscriptSnapshot,
  });
}

/** Isolate unrelated controller tests from the TimelineStore adapter contract. */
function acceptTaskTranscriptSnapshot(): boolean {
  return true;
}

const task: TaskSummary = {
  taskThreadId: "thr_child",
  parentThreadId: "thr_root",
  rootThreadId: "thr_root",
  originTurnId: "turn_parent",
  taskName: "检查测试",
  depth: 1,
  taskKind: "subagent",
  lifecycle: "attached",
  state: "running",
  revision: 2,
  latestActivitySequence: 3,
  unreadCount: 0,
  descendantCount: 0,
  runningDescendantCount: 0,
  needsAttentionCount: 0,
  latestSafeSummary: "正在检查",
  startedAt: "2026-09-03T08:00:00Z",
  completedAt: null,
  updatedAt: "2026-09-03T08:00:02Z",
};

/** Task read fixture 集中冻结 context seed，避免 CAS 场景只替换摘要时误改协议字段。 */
function taskRead(summary: TaskSummary = task): TaskReadModel {
  return {
    task: summary,
    thread: {
      threadId: summary.taskThreadId,
      workspaceId: "ws_root",
      preferences: {
        providerId: "provider_test",
        modelId: "model_test",
        reasoningLevel: "medium",
        accessMode: "approval_required",
        collaborationMode: "default",
        titleSource: "manual",
      },
      title: summary.taskName,
      status: "active",
      pinned: false,
      latestTurnStatus: summary.state === "idle" ? null : summary.state,
      latestTurnSeen: true,
      revision: summary.revision,
      createdAt: "2026-09-03T08:00:00Z",
      updatedAt: summary.updatedAt,
    },
    contextSeed: {
      contextSeedId: "seed_child",
      parentRevision: 4,
      inheritanceMode: "brief_only",
      taskBrief: [{ type: "text", text: "检查测试" }],
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

/** 用可控 Promise 精确交换 seen ACK 与 terminal event 顺序，避免依赖 wall-clock sleep。 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

/** 测试只模拟 Native 公开机器码，避免把中文展示文案误当作重试契约。 */
function taskRevisionConflict(): { readonly code: "TASK_CONTEXT_REVISION_CONFLICT" } {
  return { code: "TASK_CONTEXT_REVISION_CONFLICT" };
}

/** 创建 CAS 场景使用根 Thread 的最小合法快照，避免把读取结果写入 child transcript 投影。 */
function rootThreadSnapshot(threadId: string, revision: number) {
  return {
    threadId,
    revision,
    turns: [],
    items: [],
    inputQueue: null,
    contextUsage: null,
    liveStream: null,
    taskActivities: [],
    nextCursor: null,
  };
}

/** 构造可区分 running/terminal 的正文快照，验证迟到旧读取不会覆盖终态答案。 */
function taskTranscriptSnapshot(
  threadId: string,
  revision: number,
  status: "running" | "completed",
  text: string,
): TaskTranscriptSnapshot {
  return {
    threadId,
    revision,
    turns: [
      {
        origin: "native" as const,
        turnId: "turn_child",
        status,
        requestedAt: "2026-09-10T10:00:00Z",
        updatedAt: "2026-09-10T10:00:01Z",
        completedAt: status === "completed" ? "2026-09-10T10:00:01Z" : null,
        errorCode: null,
        changeSet: null,
      },
    ],
    items:
      status === "completed"
        ? [
            {
              origin: "native" as const,
              itemId: "item_final",
              createdAt: "2026-09-10T10:00:01Z",
              turnId: "turn_child",
              kind: "final_answer",
              truncated: false,
              totalCharacters: Array.from(text).length,
              text,
            },
          ]
        : [
            {
              origin: "native" as const,
              itemId: "item_progress",
              createdAt: "2026-09-10T10:00:00Z",
              turnId: "turn_child",
              kind: "assistant_progress",
              truncated: false,
              totalCharacters: Array.from(text).length,
              text,
              modelRound: 1,
            },
          ],
    inputQueue: null,
    contextUsage: null,
    liveStream: null,
    taskActivities: [],
    nextCursor: null,
  };
}

/** 端口 mock 返回同一 revision，测试只关注观察生命周期和摘要/正文 IO 边界。 */
function createPorts(): {
  port: TaskPort;
  transcript: TaskTranscriptPort;
  rename: TaskThreadRenamePort;
  approval: TaskApprovalPort;
  resume: TaskResumePort;
} {
  const port: TaskPort = {
    create: vi.fn(async () => ({ accepted: true as const, task })),
    list: vi.fn(async () => ({ items: [task] })),
    read: vi.fn(async () => taskRead()),
    observe: vi.fn(async () => ({
      observationId: "observation_child",
      taskThreadId: task.taskThreadId,
      revision: task.revision,
    })),
    unobserve: vi.fn(async () => undefined),
    seen: vi.fn(async () => ({ accepted: true as const, task })),
    close: vi.fn(async ({ taskThreadId }) => ({ closedThreadIds: [taskThreadId] })),
    messageSend: vi.fn(async () => ({
      accepted: true as const,
      messageId: "msg_1",
      mailboxSequence: 1,
    })),
    followup: vi.fn(async () => ({
      accepted: true as const,
      messageId: "msg_2",
      turnId: "turn_next",
      task,
    })),
    cancel: vi.fn(async () => ({
      accepted: true as const,
      task: { ...task, state: "cancelled" as const },
    })),
    treeDelete: vi.fn(async () => ({ accepted: true as const, deletedTaskCount: 1 })),
  };
  return {
    port,
    transcript: {
      observe: vi.fn(async ({ threadId }) => ({ accepted: true as const, threadId })),
      unobserve: vi.fn(async ({ threadId }) => ({ accepted: true as const, threadId })),
      read: vi.fn(async () => ({
        threadId: task.taskThreadId,
        revision: task.revision,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        nextCursor: null,
      })),
    },
    rename: {
      rename: vi.fn(async ({ threadId, title }) => ({
        threadId,
        title,
        revision: task.revision + 1,
      })),
    },
    approval: { respond: vi.fn(async () => undefined) },
    resume: { resume: vi.fn(async () => undefined) },
  };
}

/** 侧聊 fixture 明确使用 independent 生命周期，测试不会把 Subagent 取消语义混入关闭路径。 */
function sideTaskSummary(overrides: Partial<TaskSummary> = {}): TaskSummary {
  return {
    ...task,
    taskKind: "side_task",
    lifecycle: "independent",
    ...overrides,
  };
}

/** 通过公开 Task activity 总线注入摘要，模拟隐藏 Workbench 仍可收到的低频事件。 */
function publishTaskActivity(summary: TaskSummary): void {
  publishTaskHostEvent({
    method: "task/activity",
    params: {
      rootThreadId: summary.rootThreadId,
      taskThreadId: summary.taskThreadId,
      taskRevision: summary.revision,
      activity: {
        activitySequence: summary.latestActivitySequence,
        activityId: `activity_${summary.taskThreadId}`,
        rootThreadId: summary.rootThreadId,
        taskThreadId: summary.taskThreadId,
        actorThreadId: summary.parentThreadId,
        causalTurnId: summary.originTurnId,
        kind: "progress",
        summary: { text: summary.latestSafeSummary ?? "更新" },
        createdAt: summary.updatedAt,
      },
      task: summary,
    },
  });
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("useTaskController", () => {
  it("Task 已读版本前进时仍接纳更新的 Thread 偏好，并拒绝旧 Thread metadata 回滚", async () => {
    const ports = createPorts();
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        activeTaskThreadId: "thr_child",
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await waitFor(() => expect(result.current.detail?.thread.revision).toBe(2));
    const newerThread = taskRead({ ...task, revision: 1 });
    newerThread.thread = {
      ...newerThread.thread,
      revision: 8,
      preferences: { ...newerThread.thread.preferences!, modelId: "model_new" },
    };
    vi.mocked(ports.port.read).mockResolvedValue(newerThread);
    await act(async () => result.current.refreshDetail());
    expect(result.current.detail?.task.revision).toBe(2);
    expect(result.current.detail?.thread.preferences?.modelId).toBe("model_new");
    const newerTask = taskRead({ ...task, revision: 4 });
    newerTask.thread.revision = 3;
    vi.mocked(ports.port.read).mockResolvedValue(newerTask);
    await act(async () => result.current.refreshDetail());
    expect(result.current.detail?.task.revision).toBe(4);
    expect(result.current.detail?.thread.revision).toBe(8);
    expect(result.current.detail?.thread.preferences?.modelId).toBe("model_new");
  });

  it("隐藏详情只消费摘要事件且不读取列表、正文或建立观察", async () => {
    const ports = createPorts();
    const discovered = vi.fn();
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: "thr_child",
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
        onSubagentDiscovered: discovered,
      }),
    );

    act(() => {
      publishTaskHostEvent({
        method: "task/activity",
        params: {
          rootThreadId: "thr_root",
          taskThreadId: "thr_child",
          taskRevision: 2,
          activity: {
            activitySequence: 3,
            activityId: "activity_child",
            rootThreadId: "thr_root",
            taskThreadId: "thr_child",
            actorThreadId: "thr_root",
            causalTurnId: "turn_parent",
            kind: "dispatched",
            summary: { text: "已派发" },
            createdAt: "2026-09-03T08:00:00Z",
          },
          task,
        },
      });
    });

    expect(result.current.tasks).toEqual([task]);
    expect(discovered).toHaveBeenCalledTimes(1);
    expect(ports.port.list).not.toHaveBeenCalled();
    expect(ports.port.read).not.toHaveBeenCalled();
    expect(ports.transcript.read).not.toHaveBeenCalled();
    expect(ports.transcript.observe).not.toHaveBeenCalled();
    expect(ports.port.observe).not.toHaveBeenCalled();
  });

  it("仅当前可见实例 observe，隐藏后主动 unobserve 且不 cancel", async () => {
    const ports = createPorts();
    const { rerender } = renderHook(
      ({ visible }) =>
        useTaskController({
          rootThreadId: "thr_root",
          parentRevision: 4,
          activeTaskThreadId: "thr_child",
          visible,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
        }),
      { initialProps: { visible: true } },
    );
    await waitFor(() => expect(ports.port.observe).toHaveBeenCalledTimes(1));
    expect(ports.transcript.observe).toHaveBeenCalledWith({ threadId: "thr_child" });
    await waitFor(() => expect(ports.transcript.read).toHaveBeenCalledTimes(2));

    rerender({ visible: false });
    await waitFor(() =>
      expect(ports.port.unobserve).toHaveBeenCalledWith({ observationId: "observation_child" }),
    );
    expect(ports.transcript.unobserve).toHaveBeenCalledWith({ threadId: "thr_child" });
    expect(ports.port.cancel).not.toHaveBeenCalled();
  });

  it("observe CAS 冲突后权威重读最新 revision 并有界重试", async () => {
    const ports = createPorts();
    const latest = { ...task, revision: 4 };
    vi.mocked(ports.port.read)
      .mockResolvedValueOnce(taskRead(task))
      .mockResolvedValueOnce(taskRead(latest));
    vi.mocked(ports.port.observe)
      .mockRejectedValueOnce(taskRevisionConflict())
      .mockResolvedValueOnce({
        observationId: "observation_latest",
        taskThreadId: latest.taskThreadId,
        revision: latest.revision,
      });

    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: "thr_child",
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );

    await waitFor(() => expect(ports.port.observe).toHaveBeenCalledTimes(2));
    expect(ports.port.observe).toHaveBeenNthCalledWith(1, {
      taskThreadId: "thr_child",
      expectedTaskRevision: 2,
    });
    expect(ports.port.observe).toHaveBeenNthCalledWith(2, {
      taskThreadId: "thr_child",
      expectedTaskRevision: 4,
    });
    expect(ports.port.read).toHaveBeenCalledTimes(3);
    expect(result.current.detail?.task.revision).toBe(4);
    expect(result.current.detailError).toBeUndefined();
  });

  it("observe 连续 CAS 冲突最多尝试三次且不形成重复 observer", async () => {
    const ports = createPorts();
    const revision3 = { ...task, revision: 3 };
    const revision4 = { ...task, revision: 4 };
    vi.mocked(ports.port.read)
      .mockResolvedValueOnce(taskRead(task))
      .mockResolvedValueOnce(taskRead(revision3))
      .mockResolvedValueOnce(taskRead(revision4));
    vi.mocked(ports.port.observe).mockRejectedValue(taskRevisionConflict());

    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: "thr_child",
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );

    await waitFor(() => expect(result.current.detailError).toBeDefined());
    expect(ports.port.observe).toHaveBeenCalledTimes(3);
    expect(ports.port.read).toHaveBeenCalledTimes(3);
    expect(ports.port.unobserve).not.toHaveBeenCalled();
  });

  /** 取消 CAS 冲突只读回一次详情与 root Task 树；下一次显式调用必须使用新 revision。 */
  it("取消 CAS 冲突刷新最新后代树但不自动重发，显式再试使用最新 revision", async () => {
    const ports = createPorts();
    const latest = { ...task, revision: 7, descendantCount: 1, runningDescendantCount: 1 };
    const latestDescendant = {
      ...task,
      taskThreadId: "thr_descendant_latest",
      parentThreadId: task.taskThreadId,
      taskName: "新后代",
      depth: 2,
      revision: 1,
      unreadCount: 0,
    };
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: task.taskThreadId,
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await waitFor(() => expect(result.current.readyTaskThreadId).toBe(task.taskThreadId));
    const readsBeforeConflict = vi.mocked(ports.port.read).mock.calls.length;
    const listsBeforeConflict = vi.mocked(ports.port.list).mock.calls.length;
    const transcriptsBeforeConflict = vi.mocked(ports.transcript.read).mock.calls.length;
    vi.mocked(ports.port.read).mockResolvedValueOnce(taskRead(latest));
    vi.mocked(ports.port.list).mockResolvedValueOnce({ items: [latest, latestDescendant] });
    vi.mocked(ports.port.cancel)
      .mockRejectedValueOnce(taskRevisionConflict())
      .mockResolvedValueOnce({
        accepted: true,
        task: { ...latest, state: "cancelled", revision: latest.revision + 1 },
      });

    let conflict: unknown;
    await act(async () => {
      conflict = await result.current.cancel(task).catch((error: unknown) => error);
    });

    expect(conflict).toMatchObject({ code: "TASK_CONTEXT_REVISION_CONFLICT" });
    expect(vi.mocked(ports.port.cancel)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(ports.port.read)).toHaveBeenCalledTimes(readsBeforeConflict + 1);
    expect(vi.mocked(ports.port.list)).toHaveBeenCalledTimes(listsBeforeConflict + 1);
    expect(vi.mocked(ports.transcript.read)).toHaveBeenCalledTimes(transcriptsBeforeConflict + 1);
    expect(result.current.detail?.task.revision).toBe(latest.revision);
    expect(result.current.tasks).toContainEqual(latest);
    expect(result.current.tasks).toContainEqual(latestDescendant);

    await act(async () => {
      await result.current.cancel(task);
    });
    expect(vi.mocked(ports.port.cancel)).toHaveBeenNthCalledWith(2, {
      taskThreadId: task.taskThreadId,
      expectedTaskRevision: latest.revision,
    });
  });

  /** 切到 B 后迟到的 A 冲突不能发起恢复读取，也不能把 A 状态写进当前详情。 */
  it("迟到的 Thread A 取消冲突不会刷新或污染当前 Thread B", async () => {
    const ports = createPorts();
    const nextTask = { ...task, taskThreadId: "thr_next", taskName: "下一个任务" };
    const pendingCancel = deferred<Awaited<ReturnType<TaskPort["cancel"]>>>();
    vi.mocked(ports.port.list).mockImplementation(async () => ({ items: [task, nextTask] }));
    vi.mocked(ports.port.read).mockImplementation(async ({ taskThreadId }) =>
      taskRead(taskThreadId === nextTask.taskThreadId ? nextTask : task),
    );
    vi.mocked(ports.transcript.read).mockImplementation(async ({ threadId }) =>
      taskTranscriptSnapshot(threadId, 2, "running", ""),
    );
    vi.mocked(ports.port.observe).mockImplementation(async ({ taskThreadId }) => ({
      observationId: `observation_${taskThreadId}`,
      taskThreadId,
      revision: 2,
    }));
    vi.mocked(ports.port.cancel).mockReturnValueOnce(pendingCancel.promise);
    const { result, rerender } = renderHook(
      ({ activeTaskThreadId }) =>
        useTaskController({
          rootThreadId: "thr_root",
          parentRevision: 4,
          activeTaskThreadId,
          visible: true,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
        }),
      { initialProps: { activeTaskThreadId: task.taskThreadId } },
    );
    await waitFor(() => expect(result.current.readyTaskThreadId).toBe(task.taskThreadId));
    const pending = result.current.cancel(task);
    await waitFor(() => expect(ports.port.cancel).toHaveBeenCalledTimes(1));

    rerender({ activeTaskThreadId: nextTask.taskThreadId });
    await waitFor(() => expect(result.current.readyTaskThreadId).toBe(nextTask.taskThreadId));
    const readsAfterSwitch = vi.mocked(ports.port.read).mock.calls.length;
    const listsAfterSwitch = vi.mocked(ports.port.list).mock.calls.length;
    pendingCancel.reject(taskRevisionConflict());
    let conflict: unknown;
    await act(async () => {
      conflict = await pending.catch((error: unknown) => error);
    });

    expect(conflict).toMatchObject({ code: "TASK_CONTEXT_REVISION_CONFLICT" });
    expect(vi.mocked(ports.port.cancel)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(ports.port.read)).toHaveBeenCalledTimes(readsAfterSwitch);
    expect(vi.mocked(ports.port.list)).toHaveBeenCalledTimes(listsAfterSwitch);
    expect(result.current.detail?.task.taskThreadId).toBe(nextTask.taskThreadId);
  });

  it("详情关闭后释放迟到 observe 句柄且不重新订阅", async () => {
    const ports = createPorts();
    const pendingObservation = deferred<{
      observationId: string;
      taskThreadId: string;
      revision: number;
    }>();
    vi.mocked(ports.port.observe).mockReturnValue(pendingObservation.promise);
    const { rerender } = renderHook(
      ({ visible }) =>
        useTaskController({
          rootThreadId: "thr_root",
          parentRevision: 4,
          activeTaskThreadId: "thr_child",
          visible,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
        }),
      { initialProps: { visible: true } },
    );
    await waitFor(() => expect(ports.port.observe).toHaveBeenCalledTimes(1));

    rerender({ visible: false });
    pendingObservation.resolve({
      observationId: "observation_late",
      taskThreadId: "thr_child",
      revision: task.revision,
    });

    await waitFor(() =>
      expect(ports.port.unobserve).toHaveBeenCalledWith({ observationId: "observation_late" }),
    );
    expect(ports.port.observe).toHaveBeenCalledTimes(1);
    expect(ports.port.cancel).not.toHaveBeenCalled();
  });

  /** 关闭旧 Thread 时若 Task observe 尚未 ACK，其迟到句柄只能释放旧身份。 */
  it("旧 Thread 关闭后迟到 observe 不会解除新 Thread 的观察", async () => {
    const ports = createPorts();
    const closedTask = sideTaskSummary();
    const nextTask = sideTaskSummary({
      taskThreadId: "thr_next_child",
      taskName: "新侧聊",
    });
    const pendingObservation = deferred<{
      observationId: string;
      taskThreadId: string;
      revision: number;
    }>();
    vi.mocked(ports.port.read).mockImplementation(async ({ taskThreadId }) =>
      taskRead(taskThreadId === closedTask.taskThreadId ? closedTask : nextTask),
    );
    vi.mocked(ports.transcript.read).mockImplementation(async ({ threadId }) =>
      taskTranscriptSnapshot(
        threadId,
        threadId === closedTask.taskThreadId ? closedTask.revision : nextTask.revision,
        "running",
        "",
      ),
    );
    vi.mocked(ports.port.observe).mockImplementation(async ({ taskThreadId }) =>
      taskThreadId === closedTask.taskThreadId
        ? pendingObservation.promise
        : {
            observationId: "observation_next",
            taskThreadId,
            revision: nextTask.revision,
          },
    );
    const { rerender } = renderHook(
      ({ activeTaskThreadId }) =>
        useTaskController({
          rootThreadId: "thr_root",
          activeTaskThreadId,
          visible: true,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
        }),
      { initialProps: { activeTaskThreadId: closedTask.taskThreadId } },
    );
    await waitFor(() => expect(ports.port.observe).toHaveBeenCalledTimes(1));

    act(() =>
      publishTaskHostEvent({
        method: "task/closed",
        params: {
          rootThreadId: "thr_root",
          taskThreadId: closedTask.taskThreadId,
          closedThreadIds: [closedTask.taskThreadId],
        },
      }),
    );
    rerender({ activeTaskThreadId: nextTask.taskThreadId });
    await waitFor(() => expect(ports.port.observe).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(ports.transcript.observe).toHaveBeenCalledWith({
        threadId: nextTask.taskThreadId,
      }),
    );

    pendingObservation.resolve({
      observationId: "observation_late_closed",
      taskThreadId: closedTask.taskThreadId,
      revision: closedTask.revision,
    });
    await waitFor(() =>
      expect(ports.port.unobserve).toHaveBeenCalledExactlyOnceWith({
        observationId: "observation_late_closed",
      }),
    );
    expect(ports.port.unobserve).not.toHaveBeenCalledWith({
      observationId: "observation_next",
    });
    expect(ports.transcript.unobserve).toHaveBeenCalledExactlyOnceWith({
      threadId: closedTask.taskThreadId,
    });
  });

  it("首次创建与后续 follow-up 使用不同原生用例并保留显式 revision", async () => {
    const ports = createPorts();
    vi.mocked(ports.transcript.read).mockResolvedValueOnce(rootThreadSnapshot("thr_root", 9));
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await act(() =>
      result.current.createSideTask({
        taskName: "检查测试",
      }),
    );
    await act(() => result.current.followup(task, [{ type: "text", text: "继续" }]));

    expect(ports.port.create).toHaveBeenCalledWith(
      expect.objectContaining({
        parentThreadId: "thr_root",
        expectedParentRevision: 9,
        parentTurnId: null,
      }),
    );
    expect(ports.transcript.read).toHaveBeenNthCalledWith(1, {
      threadId: "thr_root",
      limit: 1,
    });
    expect(ports.port.followup).toHaveBeenCalledWith(
      expect.objectContaining({
        senderThreadId: "thr_root",
        targetThreadId: "thr_child",
        expectedTaskRevision: 2,
      }),
    );
    expect(ports.port.messageSend).not.toHaveBeenCalled();
  });

  /** 服务端 readiness waiter 完成后，React 可见标记在后续状态提交中落定。 */
  it("侧聊激活等待 observe 和 snapshot 投影后再发送", async () => {
    const ports = createPorts();
    const initialDetail = deferred<TaskReadModel>();
    const postObserveDetail = deferred<TaskReadModel>();
    const initialTranscript = deferred<TaskTranscriptSnapshot>();
    const postObserveTranscript = deferred<TaskTranscriptSnapshot>();
    const observed = deferred<{
      observationId: string;
      taskThreadId: string;
      revision: number;
    }>();
    const completed = sideTaskSummary({
      state: "completed",
      revision: 4,
      latestSafeSummary: "已完成",
      completedAt: "2026-09-10T10:00:01Z",
      updatedAt: "2026-09-10T10:00:01Z",
    });
    const lateRunning = { ...completed, state: "running" as const, completedAt: null };
    const runningSnapshot = taskTranscriptSnapshot(
      task.taskThreadId,
      task.revision,
      "running",
      "正在处理",
    );
    const completedSnapshot = taskTranscriptSnapshot(
      task.taskThreadId,
      completed.revision,
      "completed",
      "JA_SIDE_CHAT_BTW_DONE",
    );
    const lateRunningSnapshot = taskTranscriptSnapshot(
      task.taskThreadId,
      completed.revision,
      "running",
      "旧的运行中投影",
    );
    vi.mocked(ports.port.read)
      .mockReturnValueOnce(initialDetail.promise)
      .mockReturnValueOnce(postObserveDetail.promise)
      .mockResolvedValue(taskRead(lateRunning));
    vi.mocked(ports.transcript.read)
      .mockReturnValueOnce(initialTranscript.promise)
      .mockReturnValueOnce(postObserveTranscript.promise)
      .mockResolvedValueOnce(completedSnapshot)
      .mockResolvedValue(lateRunningSnapshot);
    vi.mocked(ports.port.observe).mockReturnValueOnce(observed.promise);
    vi.mocked(ports.port.followup).mockResolvedValue({
      accepted: true,
      messageId: "msg_btw",
      turnId: "turn_btw",
      task: completed,
    });
    let projectedSnapshot: TaskTranscriptSnapshot | undefined;
    const applyTranscriptSnapshot = vi.fn((snapshot: TaskTranscriptSnapshot): boolean => {
      projectedSnapshot = snapshot;
      return true;
    });

    const { result, rerender } = renderHook(
      ({ activeTaskThreadId }: { activeTaskThreadId?: string }) =>
        useTaskController({
          rootThreadId: "thr_root",
          activeTaskThreadId,
          visible: true,
          port: ports.port,
          transcriptPort: ports.transcript,
          applyTranscriptSnapshot,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
        }),
      { initialProps: { activeTaskThreadId: undefined as string | undefined } },
    );
    let readySettled = false;
    const ready = result.current.waitForTaskReady(task.taskThreadId).then(() => {
      readySettled = true;
    });

    rerender({ activeTaskThreadId: task.taskThreadId });
    await waitFor(() => expect(ports.port.read).toHaveBeenCalledTimes(1));
    initialDetail.resolve(taskRead(task));
    initialTranscript.resolve(runningSnapshot);
    await waitFor(() => expect(ports.port.observe).toHaveBeenCalledTimes(1));
    expect(readySettled).toBe(false);
    expect(result.current.readyTaskThreadId).toBeUndefined();

    observed.resolve({
      observationId: "observation_btw",
      taskThreadId: task.taskThreadId,
      revision: task.revision,
    });
    await waitFor(() => expect(ports.port.read).toHaveBeenCalledTimes(2));
    expect(readySettled).toBe(false);
    expect(result.current.readyTaskThreadId).toBeUndefined();
    postObserveDetail.resolve(taskRead(task));
    postObserveTranscript.resolve(runningSnapshot);
    await ready;
    expect(readySettled).toBe(true);
    await waitFor(() => expect(result.current.readyTaskThreadId).toBe(task.taskThreadId));
    expect(projectedSnapshot).toBe(runningSnapshot);

    await act(() => result.current.followup(task, [{ type: "text", text: "/btw" }]));
    expect(result.current.detail?.task.state).toBe("completed");
    expect(projectedSnapshot?.items.at(-1)).toEqual(
      expect.objectContaining({ kind: "final_answer", text: "JA_SIDE_CHAT_BTW_DONE" }),
    );

    act(() => publishTaskActivity(lateRunning));
    await waitFor(() => expect(ports.port.read).toHaveBeenCalledTimes(3));
    expect(result.current.detail?.task.state).toBe("completed");
    expect(applyTranscriptSnapshot).toHaveBeenLastCalledWith(lateRunningSnapshot);
  });

  it("侧聊 Composer 创建时使用当前 child 的来源、revision、偏好，并以该来源 follow-up", async () => {
    const ports = createPorts();
    const source = sideTaskSummary({
      taskThreadId: "thr_side_source",
      parentThreadId: "thr_root",
      rootThreadId: "thr_root",
      revision: 7,
    });
    const preferences = {
      providerId: "provider_side",
      modelId: "model_side",
      reasoningLevel: "high" as const,
      accessMode: "full_access" as const,
      collaborationMode: "plan" as const,
    };
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    act(() => publishTaskActivity(source));

    await act(() =>
      result.current.createSideTask({
        taskName: "侧聊",
        sourceThreadId: source.taskThreadId,
        sourceThreadRevision: source.revision,
        preferences,
      }),
    );
    await act(() =>
      result.current.followup(task, [{ type: "text", text: "来自侧聊" }], source.taskThreadId),
    );

    expect(ports.port.create).toHaveBeenCalledWith({
      parentThreadId: source.taskThreadId,
      parentTurnId: null,
      expectedParentRevision: source.revision,
      taskName: "侧聊",
      preferences,
    });
    expect(ports.port.followup).toHaveBeenCalledWith(
      expect.objectContaining({ senderThreadId: source.taskThreadId }),
    );
    expect(ports.transcript.read).toHaveBeenCalledExactlyOnceWith({
      threadId: task.taskThreadId,
    });
  });

  it("创建 CAS 冲突后只重读根 revision 一次并重试创建", async () => {
    const ports = createPorts();
    vi.mocked(ports.transcript.read)
      .mockResolvedValueOnce(rootThreadSnapshot("thr_root", 9))
      .mockResolvedValueOnce(rootThreadSnapshot("thr_root", 10));
    vi.mocked(ports.port.create)
      .mockRejectedValueOnce(taskRevisionConflict())
      .mockResolvedValueOnce({ accepted: true, task });
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );

    await act(() => result.current.createSideTask({ taskName: "检查测试" }));

    expect(ports.transcript.read).toHaveBeenCalledTimes(2);
    expect(ports.port.create).toHaveBeenCalledTimes(2);
    expect(ports.port.create).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ expectedParentRevision: 9 }),
    );
    expect(ports.port.create).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedParentRevision: 10 }),
    );
  });

  it("根 Thread 切换期间根快照迟到时不创建 child", async () => {
    const ports = createPorts();
    const pendingRootRead = deferred<ReturnType<typeof rootThreadSnapshot>>();
    vi.mocked(ports.transcript.read).mockReturnValueOnce(pendingRootRead.promise);
    const { result, rerender } = renderHook(
      ({ rootThreadId }) =>
        useTaskController({
          rootThreadId,
          parentRevision: 4,
          visible: false,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
        }),
      { initialProps: { rootThreadId: "thr_root" } },
    );

    const pending = result.current
      .createSideTask({ taskName: "切换期间创建" })
      .catch((error: unknown) => error);
    await waitFor(() => expect(ports.transcript.read).toHaveBeenCalledTimes(1));
    rerender({ rootThreadId: "thr_other" });
    pendingRootRead.resolve(rootThreadSnapshot("thr_root", 9));

    await expect(pending).resolves.toEqual(new Error("侧聊创建失败，请重试。"));
    expect(ports.port.create).not.toHaveBeenCalled();
  });

  it("非 CAS 创建错误不重试，避免创建结果不确定时重复 child", async () => {
    const ports = createPorts();
    vi.mocked(ports.transcript.read).mockResolvedValueOnce(rootThreadSnapshot("thr_root", 9));
    vi.mocked(ports.port.create).mockRejectedValueOnce(new Error("STORAGE_UNAVAILABLE"));
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );

    await expect(result.current.createSideTask({ taskName: "不重复创建" })).rejects.toEqual(
      new Error("侧聊创建失败，请重试。"),
    );
    expect(ports.transcript.read).toHaveBeenCalledTimes(1);
    expect(ports.port.create).toHaveBeenCalledTimes(1);
  });

  /** 关闭范围必须来自持久 ACK，而不是当前有界摘要里碰巧可见的后代。 */
  it("侧聊关闭等待权威 ACK，按完整 Thread 集合清理预览并压制迟到事件", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const childTask: TaskSummary = {
      ...task,
      taskThreadId: "thr_side_child",
      parentThreadId: sideTask.taskThreadId,
      rootThreadId: sideTask.rootThreadId,
      taskName: "侧聊子任务",
      depth: sideTask.depth + 1,
      taskKind: "subagent",
      lifecycle: "attached",
    };
    const hiddenDescendantId = "thr_hidden_descendant";
    const closedThreadIds = [sideTask.taskThreadId, childTask.taskThreadId, hiddenDescendantId];
    const closeAck = deferred<{ closedThreadIds: string[] }>();
    const previewCleanup = deferred<void>();
    vi.mocked(ports.port.close).mockReturnValueOnce(closeAck.promise);
    const onSideTaskClosed = vi.fn(async () => {
      void previewCleanup.promise;
      return true;
    });
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        activeTaskThreadId: undefined,
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
        onSideTaskClosed,
      }),
    );

    act(() => {
      publishTaskActivity(sideTask);
      publishTaskActivity(childTask);
    });
    expect(result.current.tasks).toEqual(expect.arrayContaining([sideTask, childTask]));

    let pendingClose!: Promise<void>;
    act(() => {
      pendingClose = result.current.close(sideTask);
    });
    await waitFor(() => expect(result.current.closingTaskThreadId).toBe(sideTask.taskThreadId));
    expect(result.current.tasks).toEqual(expect.arrayContaining([sideTask, childTask]));

    await act(async () => {
      closeAck.resolve({ closedThreadIds });
      await pendingClose;
    });
    expect(result.current.tasks).toEqual([]);
    expect(result.current.closingTaskThreadId).toBeUndefined();
    expect(onSideTaskClosed).toHaveBeenCalledWith(closedThreadIds, TEST_RUNTIME_FENCE);

    act(() => publishTaskActivity({ ...childTask, revision: childTask.revision + 1 }));
    act(() =>
      publishTaskActivity({
        ...childTask,
        taskThreadId: hiddenDescendantId,
        parentThreadId: childTask.taskThreadId,
        revision: childTask.revision + 1,
      }),
    );
    expect(result.current.tasks).toEqual([]);
    previewCleanup.resolve();
  });

  /** 远端权威关闭事件按所属 root 收敛 Task，并对同一回执保持幂等。@author kongweiguang */
  it("消费跨窗口 task/closed 完整集合并拒绝错误 root 和迟到活动", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const childTask: TaskSummary = {
      ...task,
      taskThreadId: "thr_closed_child",
      parentThreadId: sideTask.taskThreadId,
      rootThreadId: sideTask.rootThreadId,
      taskName: "侧聊子任务",
      taskKind: "subagent",
      lifecycle: "attached",
    };
    const closedThreadIds = [sideTask.taskThreadId, childTask.taskThreadId, "thr_hidden_child"];
    const onSideTaskClosed = vi.fn(async () => true);
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
        onSideTaskClosed,
      }),
    );
    act(() => {
      publishTaskActivity(sideTask);
      publishTaskActivity(childTask);
      publishTaskHostEvent({
        method: "task/closed",
        params: {
          rootThreadId: "thr_other_root",
          taskThreadId: sideTask.taskThreadId,
          closedThreadIds,
        },
      });
    });
    expect(result.current.tasks).toEqual(expect.arrayContaining([sideTask, childTask]));
    expect(onSideTaskClosed).not.toHaveBeenCalled();

    act(() =>
      publishTaskHostEvent({
        method: "task/closed",
        params: { rootThreadId: "thr_root", taskThreadId: sideTask.taskThreadId, closedThreadIds },
      }),
    );
    await waitFor(() => expect(result.current.tasks).toEqual([]));
    expect(onSideTaskClosed).toHaveBeenCalledExactlyOnceWith(closedThreadIds, TEST_RUNTIME_FENCE);

    act(() => {
      publishTaskActivity({ ...childTask, revision: childTask.revision + 1 });
      publishTaskActivity({
        ...childTask,
        taskThreadId: "thr_hidden_child",
        parentThreadId: childTask.taskThreadId,
        revision: childTask.revision + 1,
      });
      publishTaskHostEvent({
        method: "task/closed",
        params: { rootThreadId: "thr_root", taskThreadId: sideTask.taskThreadId, closedThreadIds },
      });
    });
    expect(result.current.tasks).toEqual([]);
    expect(onSideTaskClosed).toHaveBeenCalledOnce();
  });

  /** 关闭事件必须释放当前 Thread 的两个观察 owner，后续 effect cleanup 不得重复释放。 */
  it("活动侧聊收到关闭事件后恰好解除 Task 与 Transcript 观察", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(sideTask));
    vi.mocked(ports.transcript.read).mockResolvedValue(
      taskTranscriptSnapshot(sideTask.taskThreadId, sideTask.revision, "running", ""),
    );
    const onSideTaskClosed = vi.fn(async () => true);
    const { rerender } = renderHook(
      ({ visible }) =>
        useTaskController({
          rootThreadId: "thr_root",
          activeTaskThreadId: sideTask.taskThreadId,
          visible,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
          onSideTaskClosed,
        }),
      { initialProps: { visible: true } },
    );

    act(() => publishTaskActivity(sideTask));
    await waitFor(() => {
      expect(ports.port.observe).toHaveBeenCalledExactlyOnceWith({
        taskThreadId: sideTask.taskThreadId,
        expectedTaskRevision: sideTask.revision,
      });
      expect(ports.transcript.observe).toHaveBeenCalledExactlyOnceWith({
        threadId: sideTask.taskThreadId,
      });
    });

    act(() =>
      publishTaskHostEvent({
        method: "task/closed",
        params: {
          rootThreadId: "thr_root",
          taskThreadId: sideTask.taskThreadId,
          closedThreadIds: [sideTask.taskThreadId],
        },
      }),
    );
    await waitFor(() => {
      expect(ports.port.unobserve).toHaveBeenCalledExactlyOnceWith({
        observationId: "observation_child",
      });
      expect(ports.transcript.unobserve).toHaveBeenCalledExactlyOnceWith({
        threadId: sideTask.taskThreadId,
      });
    });

    rerender({ visible: false });
    expect(ports.port.unobserve).toHaveBeenCalledOnce();
    expect(ports.transcript.unobserve).toHaveBeenCalledOnce();
    expect(onSideTaskClosed).toHaveBeenCalledExactlyOnceWith(
      [sideTask.taskThreadId],
      TEST_RUNTIME_FENCE,
    );
  });

  /** 单资源缺失不能推导完整关闭集合；Transcript 仍成功时保留 Task 入口与重试反馈。@author kongweiguang */
  it("active task/read 返回 TASK_NOT_FOUND 不冒充 close ACK，也不清理成功的 Transcript", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(sideTask));
    const onSideTaskClosed = vi.fn(async () => true);
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        activeTaskThreadId: sideTask.taskThreadId,
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
        onSideTaskClosed,
      }),
    );
    act(() => publishTaskActivity(sideTask));

    await waitFor(() =>
      expect(result.current.detail?.task.taskThreadId).toBe(sideTask.taskThreadId),
    );
    vi.mocked(ports.port.read).mockRejectedValueOnce({ code: "TASK_NOT_FOUND" });
    await act(async () => result.current.refreshDetail());

    expect(onSideTaskClosed).not.toHaveBeenCalled();
    expect(ports.transcript.read).toHaveBeenCalledWith({
      threadId: sideTask.taskThreadId,
      limit: 200,
    });
    expect(ports.transcript.unobserve).not.toHaveBeenCalled();
    expect(result.current.tasks).toEqual(
      expect.arrayContaining([expect.objectContaining({ taskThreadId: sideTask.taskThreadId })]),
    );
    expect(result.current.detail?.task.taskThreadId).toBe(sideTask.taskThreadId);
    expect(result.current.detailLoading).toBe(false);
    expect(result.current.detailError).toBeDefined();
    expect(result.current.transcriptError).toBeUndefined();
    act(() => publishTaskActivity({ ...sideTask, revision: sideTask.revision + 1 }));
    expect(result.current.tasks).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          taskThreadId: sideTask.taskThreadId,
          revision: sideTask.revision + 1,
        }),
      ]),
    );
  });

  /** 旧 Runtime 的迟到 read 404 仅属于原请求，不能用新 SID 同代的当前 close owner 清理。@author kongweiguang */
  it("同 generation 切换 SID 后忽略旧 task/read 的 TASK_NOT_FOUND", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const oldRead = deferred<TaskReadModel>();
    const newRuntimeFence: TestRuntimeFence = {
      serverInstanceId: "srv_task_restarted",
      generation: 1,
    };
    vi.mocked(ports.port.read).mockReturnValueOnce(oldRead.promise);
    const onSideTaskClosed = vi.fn(async () => true);
    const { result, rerender } = renderHook(
      ({ runtimeFence }: { runtimeFence: TestRuntimeFence }) =>
        useTaskController({
          rootThreadId: "thr_root",
          activeTaskThreadId: sideTask.taskThreadId,
          visible: true,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
          onSideTaskClosed,
          runtimeFence,
        }),
      { initialProps: { runtimeFence: TEST_RUNTIME_FENCE } },
    );
    act(() => publishTaskActivity(sideTask));
    await waitFor(() => expect(ports.port.read).toHaveBeenCalled());

    rerender({ runtimeFence: newRuntimeFence });
    await waitFor(() =>
      expect(result.current.detail?.task.taskThreadId).toBe(sideTask.taskThreadId),
    );

    await act(async () => {
      oldRead.reject({ code: "TASK_NOT_FOUND" });
      await Promise.resolve();
    });
    expect(onSideTaskClosed).not.toHaveBeenCalled();
    expect(result.current.tasks).toEqual(
      expect.arrayContaining([expect.objectContaining({ taskThreadId: sideTask.taskThreadId })]),
    );
    expect(result.current.detail?.task.taskThreadId).toBe(sideTask.taskThreadId);
  });

  /** 权威 close 不随详情 epoch、Tab 或可见性撤销；finally 释放 busy 后同页仍可关闭另一侧聊。@author kongweiguang */
  it("等待 close ACK 时切换并隐藏仍清理目标，且随后可以关闭另一侧聊", async () => {
    const ports = createPorts();
    const firstTask = sideTaskSummary();
    const secondTask = sideTaskSummary({
      taskThreadId: "thr_other_side",
      taskName: "另一侧聊",
    });
    const closeAck = deferred<{ closedThreadIds: string[] }>();
    vi.mocked(ports.port.close).mockReturnValueOnce(closeAck.promise);
    vi.mocked(ports.port.read).mockImplementation(async ({ taskThreadId }) =>
      taskRead(taskThreadId === firstTask.taskThreadId ? firstTask : secondTask),
    );
    vi.mocked(ports.port.observe).mockImplementation(async ({ taskThreadId }) => ({
      observationId: `observation_${taskThreadId}`,
      taskThreadId,
      revision: firstTask.revision,
    }));
    vi.mocked(ports.transcript.read).mockImplementation(async ({ threadId }) =>
      taskTranscriptSnapshot(threadId, firstTask.revision, "running", "正在工作"),
    );
    const onSideTaskClosed = vi.fn(async () => true);
    const { result, rerender } = renderHook(
      ({ activeTaskThreadId, visible }: { activeTaskThreadId?: string; visible: boolean }) =>
        useTaskController({
          rootThreadId: "thr_root",
          activeTaskThreadId,
          visible,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
          onSideTaskClosed,
        }),
      { initialProps: { activeTaskThreadId: firstTask.taskThreadId, visible: true } },
    );
    act(() => {
      publishTaskActivity(firstTask);
      publishTaskActivity(secondTask);
    });
    await waitFor(() =>
      expect(ports.port.observe).toHaveBeenCalledWith(
        expect.objectContaining({ taskThreadId: firstTask.taskThreadId }),
      ),
    );

    let pendingClose!: Promise<void>;
    act(() => {
      pendingClose = result.current.close(firstTask);
    });
    await waitFor(() => expect(result.current.closingTaskThreadId).toBe(firstTask.taskThreadId));
    rerender({ activeTaskThreadId: secondTask.taskThreadId, visible: false });

    await act(async () => {
      closeAck.resolve({ closedThreadIds: [firstTask.taskThreadId] });
      await pendingClose;
    });
    expect(result.current.tasks).not.toContainEqual(firstTask);
    expect(result.current.tasks).toContainEqual(secondTask);
    expect(result.current.closingTaskThreadId).toBeUndefined();
    expect(onSideTaskClosed).toHaveBeenCalledExactlyOnceWith(
      [firstTask.taskThreadId],
      TEST_RUNTIME_FENCE,
    );

    await act(async () => result.current.close(secondTask));
    expect(ports.port.close).toHaveBeenCalledTimes(2);
    expect(result.current.tasks).not.toContainEqual(secondTask);
  });

  /** 关闭拒绝不能清掉 Tab 所依赖的投影，用户可在原位重试。 */
  it("侧聊关闭失败保留投影并返回稳定错误", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    vi.mocked(ports.port.close).mockRejectedValueOnce(new Error("CLOSE_FAILED"));
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    act(() => publishTaskActivity(sideTask));

    await expect(result.current.close(sideTask)).rejects.toEqual(
      new Error("侧聊关闭失败，请重试。"),
    );
    expect(result.current.tasks).toEqual([sideTask]);
    expect(result.current.closingTaskThreadId).toBeUndefined();
  });

  /** 本地权威清理拒绝时，当前请求 scope 必须收到可重试错误，且不能消费关闭 ID。@author kongweiguang */
  it("关闭 ACK 的清理被当前 Runtime 拒绝时保留入口并允许显式重试", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const onSideTaskClosed = vi.fn().mockResolvedValueOnce(false).mockResolvedValueOnce(true);
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
        onSideTaskClosed,
      }),
    );
    act(() => publishTaskActivity(sideTask));

    let firstClose!: Promise<void>;
    act(() => {
      firstClose = result.current.close(sideTask);
    });
    await act(async () => {
      await expect(firstClose).rejects.toEqual(new Error("侧聊关闭失败，请重试。"));
    });
    expect(result.current.tasks).toContainEqual(sideTask);
    expect(ports.port.close).toHaveBeenCalledOnce();

    await act(async () => result.current.close(sideTask));
    expect(result.current.tasks).not.toContainEqual(sideTask);
    expect(ports.port.close).toHaveBeenCalledTimes(2);
    expect(onSideTaskClosed).toHaveBeenCalledTimes(2);
  });

  /** 请求期间切换 SID 后，旧 ACK 只能被丢弃，不能清理新 Runtime 中同名 Thread。@author kongweiguang */
  it("关闭 ACK 跨到同 generation 的新 SID 时不调用新 Runtime 清理", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const closeAck = deferred<{ closedThreadIds: string[] }>();
    vi.mocked(ports.port.close).mockReturnValueOnce(closeAck.promise);
    const newRuntimeFence: TestRuntimeFence = {
      serverInstanceId: "srv_task_restarted",
      generation: 1,
    };
    const onSideTaskClosed = vi.fn(async () => true);
    const { result, rerender } = renderHook(
      ({ runtimeFence }: { runtimeFence: TestRuntimeFence }) =>
        useTaskController({
          rootThreadId: "thr_root",
          visible: false,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
          onSideTaskClosed,
          runtimeFence,
        }),
      { initialProps: { runtimeFence: TEST_RUNTIME_FENCE } },
    );
    act(() => publishTaskActivity(sideTask));

    let pendingClose!: Promise<void>;
    act(() => {
      pendingClose = result.current.close(sideTask);
    });
    await waitFor(() => expect(result.current.closingTaskThreadId).toBe(sideTask.taskThreadId));
    rerender({ runtimeFence: newRuntimeFence });

    await act(async () => {
      closeAck.resolve({ closedThreadIds: [sideTask.taskThreadId] });
      await pendingClose;
    });
    expect(onSideTaskClosed).not.toHaveBeenCalled();
    expect(result.current.tasks).toContainEqual(sideTask);
  });

  /** Same-ID 新 Runtime 可并发重试 close；旧请求 finally 只能释放自己捕获的 owner token。@author kongweiguang */
  it("旧 SID 的 close 拒绝与 finally 不会释放新 SID 同 ID 的关闭锁", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const oldClose = deferred<{ closedThreadIds: string[] }>();
    const newClose = deferred<{ closedThreadIds: string[] }>();
    vi.mocked(ports.port.close)
      .mockReturnValueOnce(oldClose.promise)
      .mockReturnValueOnce(newClose.promise);
    const newRuntimeFence: TestRuntimeFence = {
      serverInstanceId: "srv_task_restarted",
      generation: TEST_RUNTIME_FENCE.generation,
    };
    const onSideTaskClosed = vi.fn(async () => true);
    const { result, rerender } = renderHook(
      ({ runtimeFence }: { runtimeFence: TestRuntimeFence }) =>
        useTaskController({
          rootThreadId: "thr_root",
          visible: false,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
          onSideTaskClosed,
          runtimeFence,
        }),
      { initialProps: { runtimeFence: TEST_RUNTIME_FENCE } },
    );
    act(() => publishTaskActivity(sideTask));

    let oldRequest!: Promise<void>;
    act(() => {
      oldRequest = result.current.close(sideTask);
    });
    rerender({ runtimeFence: newRuntimeFence });

    let currentRequest!: Promise<void>;
    act(() => {
      currentRequest = result.current.close(sideTask);
    });
    await waitFor(() => expect(result.current.closingTaskThreadId).toBe(sideTask.taskThreadId));

    await act(async () => {
      oldClose.reject(new Error("old runtime close failed"));
      await expect(oldRequest).rejects.toEqual(new Error("侧聊关闭失败，请重试。"));
    });
    expect(result.current.closingTaskThreadId).toBe(sideTask.taskThreadId);
    expect(onSideTaskClosed).not.toHaveBeenCalled();

    await act(async () => {
      newClose.resolve({ closedThreadIds: [sideTask.taskThreadId] });
      await currentRequest;
    });
    expect(onSideTaskClosed).toHaveBeenCalledExactlyOnceWith(
      [sideTask.taskThreadId],
      newRuntimeFence,
    );
    expect(result.current.closingTaskThreadId).toBeUndefined();
  });

  /** 没有请求起点 Runtime 身份时不能发送关闭副作用，避免 ACK 无法安全归属。@author kongweiguang */
  it("Runtime fence 尚未建立时拒绝发起关闭 RPC", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
        runtimeFence: null,
      }),
    );

    await expect(result.current.close(sideTask)).rejects.toEqual(
      new Error("侧聊关闭失败，请重试。"),
    );
    expect(ports.port.close).not.toHaveBeenCalled();
  });

  /** 关闭进入服务端 ACK 等待后立即拒绝新的 follow-up，避免向待销毁目标排队输入。 */
  it("侧聊关闭期间拒绝 follow-up 且不调用发送端口", async () => {
    const ports = createPorts();
    const sideTask = sideTaskSummary();
    const closeAck = deferred<{ closedThreadIds: string[] }>();
    vi.mocked(ports.port.close).mockReturnValueOnce(closeAck.promise);
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );

    let pendingClose!: Promise<void>;
    act(() => {
      pendingClose = result.current.close(sideTask);
    });
    await waitFor(() => expect(result.current.closingTaskThreadId).toBe(sideTask.taskThreadId));
    await expect(
      result.current.followup(sideTask, [{ type: "text", text: "不要发送" }]),
    ).rejects.toEqual(new Error("消息未发送，请重试。"));
    expect(ports.port.followup).not.toHaveBeenCalled();

    await act(async () => {
      closeAck.resolve({ closedThreadIds: [sideTask.taskThreadId] });
      await pendingClose;
    });
  });

  it("uses thread rename CAS then accepts the advanced task projection over a stale summary", async () => {
    const ports = createPorts();
    const sideTask = {
      ...task,
      taskName: "旧名称",
      taskKind: "side_task" as const,
      lifecycle: "independent" as const,
    };
    const renamedTask = { ...sideTask, taskName: "新名称", revision: sideTask.revision + 1 };
    vi.mocked(ports.port.list).mockImplementation(async ({ rootThreadId }) => ({
      items: rootThreadId === sideTask.rootThreadId ? [sideTask] : [],
    }));
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(sideTask));
    vi.mocked(ports.transcript.read).mockResolvedValue({
      threadId: sideTask.taskThreadId,
      revision: 9,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      nextCursor: null,
    });
    vi.mocked(ports.rename.rename).mockResolvedValue({
      threadId: sideTask.taskThreadId,
      title: renamedTask.taskName,
      revision: 10,
    });
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: sideTask.taskThreadId,
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await waitFor(() => expect(result.current.tasks).toContainEqual(sideTask));
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(renamedTask));

    await act(async () => {
      await result.current.rename(sideTask, " 新名称 ");
    });

    expect(ports.rename.rename).toHaveBeenCalledWith({
      threadId: sideTask.taskThreadId,
      title: "新名称",
      expectedThreadRevision: 9,
    });
    expect(result.current.tasks).toContainEqual(renamedTask);
    act(() => {
      publishTaskHostEvent({
        method: "task/activity",
        params: {
          rootThreadId: "thr_root",
          taskThreadId: sideTask.taskThreadId,
          taskRevision: sideTask.revision,
          activity: {
            activitySequence: 3,
            activityId: "activity_stale_title",
            rootThreadId: "thr_root",
            taskThreadId: sideTask.taskThreadId,
            actorThreadId: "thr_root",
            causalTurnId: "turn_parent",
            kind: "progress",
            summary: { text: "旧投影" },
            createdAt: "2026-09-03T08:00:00Z",
          },
          task: sideTask,
        },
      });
    });
    expect(result.current.tasks).toContainEqual(renamedTask);
  });

  it("does not apply a rename ACK after switching to another root task tree", async () => {
    const ports = createPorts();
    const sideTask = {
      ...task,
      taskKind: "side_task" as const,
      lifecycle: "independent" as const,
    };
    const renameAck = deferred<{ threadId: string; title: string; revision: number }>();
    vi.mocked(ports.port.list).mockImplementation(async ({ rootThreadId }) => ({
      items: rootThreadId === sideTask.rootThreadId ? [sideTask] : [],
    }));
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(sideTask));
    vi.mocked(ports.rename.rename).mockReturnValue(renameAck.promise);
    const { result, rerender } = renderHook(
      ({ rootThreadId }) =>
        useTaskController({
          rootThreadId,
          parentRevision: 4,
          activeTaskThreadId:
            rootThreadId === sideTask.rootThreadId ? sideTask.taskThreadId : undefined,
          visible: true,
          port: ports.port,
          transcriptPort: ports.transcript,
          renamePort: ports.rename,
          approvalPort: ports.approval,
          resumePort: ports.resume,
        }),
      { initialProps: { rootThreadId: "thr_root" } },
    );
    await waitFor(() => expect(result.current.tasks).toContainEqual(sideTask));
    const pending = result.current.rename(sideTask, "切换后名称").catch((error: unknown) => error);
    await waitFor(() => expect(ports.rename.rename).toHaveBeenCalledTimes(1));

    rerender({ rootThreadId: "thr_other" });
    renameAck.resolve({ threadId: sideTask.taskThreadId, title: "切换后名称", revision: 3 });
    const failure = await pending;

    expect(failure).toEqual(new Error("侧聊重命名失败，请重试。"));
    expect(result.current.tasks).toEqual([]);
  });

  it("follow-up ACK 丢失后相同内容复用幂等键，内容变化立即换键", async () => {
    const ports = createPorts();
    vi.mocked(ports.port.followup)
      .mockRejectedValueOnce(new Error("ack lost"))
      .mockRejectedValueOnce(new Error("ack still lost"));
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    const original = [{ type: "text" as const, text: "继续核对" }];
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await act(async () => {
        await result.current.followup(task, original).catch(() => undefined);
      });
    }
    await act(() => result.current.followup(task, [{ type: "text", text: "改查迁移" }]));

    const calls = vi.mocked(ports.port.followup).mock.calls;
    expect(calls[0]?.[0].operationKey).toBe(calls[1]?.[0].operationKey);
    expect(calls[2]?.[0].operationKey).not.toBe(calls[1]?.[0].operationKey);
  });

  it("task/read 失败时仍投影 thread/read 快照且不建立无 revision 观察", async () => {
    const ports = createPorts();
    vi.mocked(ports.port.read).mockRejectedValue(new Error("private task failure"));
    const applyTranscriptSnapshot = vi.fn(acceptTaskTranscriptSnapshot);
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: "thr_child",
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        applyTranscriptSnapshot,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );

    await waitFor(() => expect(result.current.detailError).toBeDefined());
    expect(result.current.detail).toBeUndefined();
    expect(applyTranscriptSnapshot).toHaveBeenCalledWith(
      expect.objectContaining({ threadId: "thr_child" }),
    );
    expect(result.current.transcriptError).toBeUndefined();
    expect(ports.port.observe).not.toHaveBeenCalled();
  });

  it("suspended Task 使用最早 suspended Turn 与 Transcript revision 恢复并权威重读", async () => {
    const ports = createPorts();
    const applyTranscriptSnapshot = vi.fn(acceptTaskTranscriptSnapshot);
    const suspended = { ...task, state: "suspended" as const, revision: 8 };
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(suspended));
    vi.mocked(ports.transcript.read).mockResolvedValue({
      threadId: suspended.taskThreadId,
      revision: 11,
      turns: [
        {
          origin: "native" as const,
          turnId: "turn_old",
          status: "suspended",
          requestedAt: "2026-09-03T07:00:00Z",
          updatedAt: "2026-09-03T07:01:00Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
        {
          origin: "native" as const,
          turnId: "turn_latest",
          status: "suspended",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:01:00Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      nextCursor: null,
    });
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: "thr_child",
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        applyTranscriptSnapshot,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await waitFor(() =>
      expect(applyTranscriptSnapshot).toHaveBeenCalledWith(
        expect.objectContaining({ revision: 11, threadId: suspended.taskThreadId }),
      ),
    );

    await act(() => result.current.resume(suspended));

    expect(ports.resume.resume).toHaveBeenCalledWith({
      turnId: "turn_old",
      expectedThreadRevision: 11,
    });
    expect(ports.port.read).toHaveBeenCalledTimes(3);
    expect(ports.transcript.read).toHaveBeenCalledTimes(4);
  });

  it("task/seen 提升 revision 后同步详情，后续 follow-up 不提交旧 CAS", async () => {
    const ports = createPorts();
    const unread = { ...task, unreadCount: 1, revision: 2 };
    const seen = { ...unread, unreadCount: 0, revision: 3 };
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(unread));
    vi.mocked(ports.port.seen).mockResolvedValue({ accepted: true, task: seen });
    vi.mocked(ports.port.followup).mockResolvedValue({
      accepted: true,
      messageId: "msg_seen",
      turnId: "turn_seen",
      task: seen,
    });
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: "thr_child",
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await waitFor(() => expect(result.current.detail?.task.revision).toBe(3));

    await act(() =>
      result.current.followup(result.current.detail!.task, [{ type: "text", text: "继续" }]),
    );

    expect(ports.port.followup).toHaveBeenCalledWith(
      expect.objectContaining({ expectedTaskRevision: 3 }),
    );
  });

  it("terminal 与 seen 顺序交换时串行已读并让 follow-up 使用最新 revision", async () => {
    vi.useFakeTimers();
    const ports = createPorts();
    const unread = { ...task, unreadCount: 1, revision: 2, latestActivitySequence: 3 };
    const terminal = {
      ...unread,
      state: "completed" as const,
      revision: 4,
      latestActivitySequence: 5,
      latestSafeSummary: "已完成",
      completedAt: "2026-09-03T08:00:04Z",
      updatedAt: "2026-09-03T08:00:04Z",
    };
    const seenAfterTerminal = { ...terminal, unreadCount: 0, revision: 5 };
    const firstSeen = deferred<{ accepted: true; task: TaskSummary }>();
    vi.mocked(ports.port.read)
      .mockResolvedValueOnce(taskRead(unread))
      .mockResolvedValueOnce(taskRead(unread))
      .mockResolvedValueOnce(taskRead(terminal));
    vi.mocked(ports.port.seen)
      .mockImplementationOnce(() => firstSeen.promise)
      .mockResolvedValueOnce({ accepted: true, task: seenAfterTerminal });
    vi.mocked(ports.port.followup).mockResolvedValue({
      accepted: true,
      messageId: "msg_latest",
      turnId: "turn_latest",
      task: seenAfterTerminal,
    });
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        activeTaskThreadId: "thr_child",
        visible: true,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    await act(async () => {
      vi.advanceTimersByTime(75);
      await Promise.resolve();
    });
    expect(ports.port.seen).toHaveBeenCalledTimes(1);
    expect(ports.port.seen).toHaveBeenLastCalledWith(
      expect.objectContaining({ expectedTaskRevision: 2 }),
    );

    act(() => {
      publishTaskHostEvent({
        method: "task/activity",
        params: {
          rootThreadId: "thr_root",
          taskThreadId: "thr_child",
          taskRevision: terminal.revision,
          activity: {
            activitySequence: terminal.latestActivitySequence,
            activityId: "activity_terminal",
            rootThreadId: "thr_root",
            taskThreadId: "thr_child",
            actorThreadId: "thr_child",
            causalTurnId: "turn_child",
            kind: "completed",
            summary: { text: "已完成" },
            createdAt: "2026-09-03T08:00:04Z",
          },
          task: terminal,
        },
      });
    });
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
      vi.advanceTimersByTime(75);
      await Promise.resolve();
    });
    expect(result.current.detail?.task.revision).toBe(4);
    expect(ports.port.seen).toHaveBeenCalledTimes(1);

    await act(async () => {
      firstSeen.reject(new Error("TASK_CONTEXT_REVISION_CONFLICT"));
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(ports.port.seen).toHaveBeenCalledTimes(2);
    expect(ports.port.seen).toHaveBeenLastCalledWith(
      expect.objectContaining({ expectedTaskRevision: 4, throughActivitySequence: 5 }),
    );
    expect(result.current.detail?.task.revision).toBe(5);
    expect(result.current.detailError).toBeUndefined();

    await act(() => result.current.followup(unread, [{ type: "text", text: "继续" }]));
    expect(ports.port.followup).toHaveBeenCalledWith(
      expect.objectContaining({ expectedTaskRevision: 5 }),
    );
  });

  it("follow-up 冲突后只在权威 revision 前进时使用原幂等键重试一次", async () => {
    const ports = createPorts();
    const latest = { ...task, revision: 4, unreadCount: 0 };
    vi.mocked(ports.port.followup)
      .mockRejectedValueOnce(new Error("STORAGE_UNAVAILABLE"))
      .mockResolvedValueOnce({
        accepted: true,
        messageId: "msg_retry",
        turnId: "turn_retry",
        task: latest,
      });
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(latest));
    const { result } = renderHook(() =>
      useTaskController({
        rootThreadId: "thr_root",
        parentRevision: 4,
        visible: false,
        port: ports.port,
        transcriptPort: ports.transcript,
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );

    await act(() => result.current.followup(task, [{ type: "text", text: "继续" }]));

    const calls = vi.mocked(ports.port.followup).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0]?.[0].expectedTaskRevision).toBe(2);
    expect(calls[1]?.[0].expectedTaskRevision).toBe(4);
    expect(calls[1]?.[0].operationKey).toBe(calls[0]?.[0].operationKey);
  });
});
