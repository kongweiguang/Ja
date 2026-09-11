// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import {
  publishTaskHostEvent,
  useTaskActivityTimeline,
  type TaskActivity,
  type TaskSummary,
} from "@/features/tasks";
import { useTimelineStore } from "@/features/conversation";

const task: TaskSummary = {
  taskThreadId: "thr_child",
  parentThreadId: "thr_root",
  rootThreadId: "thr_root",
  originTurnId: "turn_parent",
  taskName: "检查合同",
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

/** 创建最小持久 Activity 投影，调用方可覆盖 identity 以验证根隔离与幂等替换。 */
function activity(overrides: Partial<TaskActivity> = {}): TaskActivity {
  return {
    activitySequence: 3,
    activityId: "activity_child",
    rootThreadId: "thr_root",
    taskThreadId: "thr_child",
    actorThreadId: "thr_root",
    causalTurnId: "turn_parent",
    kind: "progress",
    summary: { text: "正在检查" },
    createdAt: "2026-09-03T08:00:02Z",
    ...overrides,
  };
}

/** 发布路径与生产 Runtime composition 一致，测试不直接改写 hook 内部状态。 */
function publish(rootThreadId: string, nextActivity: TaskActivity, nextTask = task): void {
  const taskProjection =
    nextTask.rootThreadId === rootThreadId
      ? nextTask
      : { ...nextTask, rootThreadId, parentThreadId: rootThreadId };
  publishTaskHostEvent({
    method: "task/activity",
    params: {
      rootThreadId,
      taskThreadId: taskProjection.taskThreadId,
      taskRevision: taskProjection.revision,
      activity: { ...nextActivity, rootThreadId },
      task: taskProjection,
    },
  });
}

/** 通过真实 Timeline reducer 安装 thread/read 快照，验证 hook 不依赖 Task 面板额外读取。 */
function prepareSnapshot(
  rootThreadId = "thr_root",
  activities: Array<{ activity: TaskActivity; task: TaskSummary }> = [],
  generation = 1,
): void {
  useTimelineStore.getState().reset();
  expect(
    useTimelineStore.getState().applyRuntimeStatus({
      status: "ready",
      generation,
      serverInstanceId: `srv_${generation}`,
    }),
  ).toBe("applied");
  expect(
    useTimelineStore.getState().applySnapshot(
      {
        threadId: rootThreadId,
        revision: 4,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: activities,
        goalActivities: [],
        nextCursor: null,
      },
      "ws_tasks",
    ),
  ).toBe("applied");
}

afterEach(() => {
  cleanup();
  useTimelineStore.getState().reset();
});

describe("useTaskActivityTimeline", () => {
  it("从 thread/read 快照恢复活动，并与乱序实时事件按 identity 去重排序", () => {
    prepareSnapshot("thr_root", [{ activity: activity(), task }]);
    const { result, rerender } = renderHook(
      ({ rootThreadId, generation }) => useTaskActivityTimeline(rootThreadId, generation),
      { initialProps: { rootThreadId: "thr_root", generation: 1 } },
    );

    expect(result.current.map((entry) => entry.activity.activityId)).toEqual(["activity_child"]);
    expect(result.current[0]?.firstOccurredAt).toBe(task.startedAt);

    act(() => publish("thr_other", activity({ activityId: "activity_other" })));
    expect(result.current).toHaveLength(1);

    act(() =>
      publish(
        "thr_root",
        activity({
          summary: { text: "已更新" },
          activitySequence: 4,
          createdAt: "2026-09-03T08:00:10Z",
        }),
        { ...task, startedAt: "2026-09-03T08:00:09Z", revision: 4 },
      ),
    );
    expect(result.current).toHaveLength(1);
    expect(result.current[0]?.activity.summary.text).toBe("已更新");
    expect(result.current[0]?.firstOccurredAt).toBe(task.startedAt);
    act(() =>
      publish("thr_root", activity({ activityId: "activity_earlier", activitySequence: 2 })),
    );
    expect(result.current.map((entry) => entry.activity.activitySequence)).toEqual([4]);

    rerender({ rootThreadId: "thr_other", generation: 1 });
    expect(result.current).toEqual([]);
    act(() => publish("thr_other", activity({ activityId: "activity_other" })));
    expect(result.current).toHaveLength(1);
    expect(result.current[0]?.task.rootThreadId).toBe("thr_other");
  });

  it("空快照替换会清除旧活动，runtime generation 改变时不会泄漏上一代投影", () => {
    prepareSnapshot("thr_root", [{ activity: activity(), task }]);
    const { result, rerender } = renderHook(
      ({ generation }) => useTaskActivityTimeline("thr_root", generation),
      { initialProps: { generation: 1 } },
    );
    expect(result.current).toHaveLength(1);

    act(() => prepareSnapshot("thr_root", [], 1));
    expect(result.current).toEqual([]);

    act(() => publish("thr_root", activity({ activityId: "activity_live" })));
    expect(result.current).toHaveLength(1);
    rerender({ generation: 2 });
    expect(result.current).toEqual([]);

    act(() => {
      useTimelineStore.getState().applyRuntimeStatus({
        status: "ready",
        generation: 2,
        serverInstanceId: "srv_2",
      });
    });
    expect(result.current).toEqual([]);
  });

  /** 侧聊与嵌套子任务共享 lineage root，但都不能回流到当前主会话 Timeline。 */
  it("只展示当前会话直接委派的 Subagent 活动", () => {
    prepareSnapshot("thr_root", [{ activity: activity(), task }]);
    const { result } = renderHook(() => useTaskActivityTimeline("thr_root", 1));
    expect(result.current.map((entry) => entry.activity.activityId)).toEqual(["activity_child"]);

    const sideTask: TaskSummary = {
      ...task,
      taskThreadId: "thr_side",
      parentThreadId: "thr_root",
      originTurnId: null,
      taskKind: "side_task",
      lifecycle: "independent",
      taskName: "侧聊",
    };
    const nestedTask: TaskSummary = {
      ...task,
      taskThreadId: "thr_nested",
      parentThreadId: task.taskThreadId,
      depth: 2,
      taskName: "嵌套子任务",
    };
    act(() => {
      publish(
        "thr_root",
        activity({ activityId: "activity_side", taskThreadId: sideTask.taskThreadId }),
        sideTask,
      );
      publish(
        "thr_root",
        activity({ activityId: "activity_nested", taskThreadId: nestedTask.taskThreadId }),
        nestedTask,
      );
    });
    expect(result.current.map((entry) => entry.activity.activityId)).toEqual(["activity_child"]);

    act(() =>
      publish("thr_root", activity({ activityId: "activity_direct_new", activitySequence: 4 }), {
        ...task,
        revision: 3,
        latestActivitySequence: 4,
      }),
    );
    expect(result.current.map((entry) => entry.activity.activityId)).toEqual([
      "activity_direct_new",
    ]);
  });

  /** 侧聊 child 的全局 lineage root 可不同于当前 owner，仍须在侧聊自己的 Timeline 内可见。 */
  it("按 owner Thread 接纳侧聊直接子任务而不误用全局 root", () => {
    const sideChild: TaskSummary = {
      ...task,
      taskThreadId: "thr_side_child",
      parentThreadId: "thr_side",
      rootThreadId: "thr_root",
      taskName: "侧聊子任务",
    };
    const sideActivity = activity({
      activityId: "activity_side_child",
      rootThreadId: "thr_root",
      taskThreadId: sideChild.taskThreadId,
    });
    prepareSnapshot("thr_side", [{ activity: sideActivity, task: sideChild }]);
    const { result } = renderHook(() => useTaskActivityTimeline("thr_side", 1));
    expect(result.current.map((entry) => entry.activity.activityId)).toEqual([
      "activity_side_child",
    ]);

    act(() =>
      publish(
        "thr_root",
        activity({
          activityId: "activity_side_child_live",
          rootThreadId: "thr_root",
          taskThreadId: sideChild.taskThreadId,
          activitySequence: 4,
        }),
        { ...sideChild, revision: 3, latestActivitySequence: 4 },
      ),
    );
    expect(result.current.map((entry) => entry.activity.activityId)).toEqual([
      "activity_side_child_live",
    ]);
  });
});
