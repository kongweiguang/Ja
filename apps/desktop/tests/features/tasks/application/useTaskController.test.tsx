// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  publishTaskHostEvent,
  useTaskController,
  type TaskPort,
  type TaskReadModel,
  type TaskSummary,
} from "@/features/tasks";
import type {
  TaskApprovalPort,
  TaskResumePort,
  TaskThreadRenamePort,
  TaskTranscriptPort,
} from "@/features/tasks";

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

/** 端口 mock 返回同一 revision，测试只关注观察生命周期和摘要/正文 IO 边界。 */
function createPorts(): {
  port: TaskPort;
  transcript: TaskTranscriptPort;
  rename: TaskThreadRenamePort;
  approval: TaskApprovalPort;
  resume: TaskResumePort;
} {
  const port: TaskPort = {
    create: vi.fn(async () => ({ accepted: true as const, task, turnId: "turn_child" })),
    list: vi.fn(async () => ({ items: [task] })),
    read: vi.fn(async () => taskRead()),
    observe: vi.fn(async () => ({
      observationId: "observation_child",
      taskThreadId: task.taskThreadId,
      revision: task.revision,
    })),
    unobserve: vi.fn(async () => undefined),
    seen: vi.fn(async () => ({ accepted: true as const, task })),
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
      read: vi.fn(async () => ({
        threadId: task.taskThreadId,
        revision: task.revision,
        turns: [],
        items: [],
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

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("useTaskController", () => {
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
    await waitFor(() => expect(ports.transcript.read).toHaveBeenCalledTimes(1));

    rerender({ visible: false });
    await waitFor(() =>
      expect(ports.port.unobserve).toHaveBeenCalledWith({ observationId: "observation_child" }),
    );
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
    expect(ports.port.read).toHaveBeenCalledTimes(2);
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

  it("首次创建与后续 follow-up 使用不同原生用例并保留显式 revision", async () => {
    const ports = createPorts();
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
        content: [{ type: "text", text: "开始" }],
      }),
    );
    await act(() => result.current.followup(task, [{ type: "text", text: "继续" }]));

    expect(ports.port.create).toHaveBeenCalledWith(
      expect.objectContaining({
        parentThreadId: "thr_root",
        expectedParentRevision: 4,
        parentTurnId: null,
      }),
    );
    expect(ports.port.followup).toHaveBeenCalledWith(
      expect.objectContaining({
        senderThreadId: "thr_root",
        targetThreadId: "thr_child",
        expectedTaskRevision: 2,
      }),
    );
    expect(ports.port.messageSend).not.toHaveBeenCalled();
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

    expect(failure).toEqual(new Error("侧边任务重命名失败，请重试。"));
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
    expect(calls[0]?.[0].idempotencyKey).toBe(calls[1]?.[0].idempotencyKey);
    expect(calls[2]?.[0].idempotencyKey).not.toBe(calls[1]?.[0].idempotencyKey);
  });

  it("task/read 失败时仍独立保留 thread/read Transcript 且不建立无 revision 观察", async () => {
    const ports = createPorts();
    vi.mocked(ports.port.read).mockRejectedValue(new Error("private task failure"));
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
    expect(result.current.detail).toBeUndefined();
    expect(result.current.transcript?.threadId).toBe("thr_child");
    expect(result.current.transcriptError).toBeUndefined();
    expect(ports.port.observe).not.toHaveBeenCalled();
  });

  it("suspended Task 使用最早 suspended Turn 与 Transcript revision 恢复并权威重读", async () => {
    const ports = createPorts();
    const suspended = { ...task, state: "suspended" as const, revision: 8 };
    vi.mocked(ports.port.read).mockResolvedValue(taskRead(suspended));
    vi.mocked(ports.transcript.read).mockResolvedValue({
      threadId: suspended.taskThreadId,
      revision: 11,
      turns: [
        {
          turnId: "turn_old",
          status: "suspended",
          requestedAt: "2026-09-03T07:00:00Z",
          updatedAt: "2026-09-03T07:01:00Z",
          completedAt: null,
          errorCode: null,
        },
        {
          turnId: "turn_latest",
          status: "suspended",
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: "2026-09-03T08:01:00Z",
          completedAt: null,
          errorCode: null,
        },
      ],
      items: [],
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
        renamePort: ports.rename,
        approvalPort: ports.approval,
        resumePort: ports.resume,
      }),
    );
    await waitFor(() => expect(result.current.transcript?.revision).toBe(11));

    await act(() => result.current.resume(suspended));

    expect(ports.resume.resume).toHaveBeenCalledWith({
      turnId: "turn_old",
      expectedThreadRevision: 11,
    });
    expect(ports.port.read).toHaveBeenCalledTimes(2);
    expect(ports.transcript.read).toHaveBeenCalledTimes(2);
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
    expect(calls[1]?.[0].idempotencyKey).toBe(calls[0]?.[0].idempotencyKey);
  });
});
