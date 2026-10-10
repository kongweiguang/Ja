// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type { RuntimeHostAdapter } from "@/api/tauri/runtime";
import { createRuntimeHostPort } from "@/app/composition/runtimeHostAdapter";
import { subscribePlanHostEvents, type PlanEvent } from "@/features/plans";
import { useTimelineStore } from "@/features/conversation";
import { subscribeTaskHostEvents, type TaskActivity, type TaskSummary } from "@/features/tasks";

describe("runtimeHostAdapter Plan routing", () => {
  /** 目录发现经受限 settings query 转交，composition 不形成可注入 method 或 Provider 配置的通道。 */
  it("routes saved provider model discovery through the fixed native method", async () => {
    const query = vi.fn(async () => ({ items: ["gpt-5.6-sol"], truncated: false }));
    const adapter = { query } as unknown as RuntimeHostAdapter;
    const runtime = createRuntimeHostPort(adapter);

    await expect(
      runtime.query("model/discover", { providerId: "provider_openai" }),
    ).resolves.toEqual({ items: ["gpt-5.6-sol"], truncated: false });
    expect(query).toHaveBeenCalledWith("model/discover", { providerId: "provider_openai" });
  });

  /** 取消只使对应 Thread 的投影失效；不制造模型事件，也不对其它隐藏会话发起读取。 */
  it("invalidates only the cancelled interaction owner", async () => {
    let emitNative!: Parameters<RuntimeHostAdapter["subscribe"]>[0];
    const adapter = {
      subscribe: vi.fn(async (listener) => {
        emitNative = listener;
        return () => undefined;
      }),
    } as unknown as RuntimeHostAdapter;
    useTimelineStore.getState().reset();
    const listener = vi.fn();
    const unsubscribe = await createRuntimeHostPort(adapter).subscribe(listener);
    emitNative({
      kind: "interaction",
      event: { params: { kind: "cancelled", threadId: "thr_question", eventSequence: 3 } },
    } as never);
    expect(Object.keys(useTimelineStore.getState().resyncRequired)).toEqual(["thr_question"]);
    expect(listener).not.toHaveBeenCalled();
    await unsubscribe();
    useTimelineStore.getState().reset();
  });

  it("routes Plan changes only to the Plan bus", async () => {
    let emitNative!: Parameters<RuntimeHostAdapter["subscribe"]>[0];
    const adapter = {
      subscribe: vi.fn(async (listener) => {
        emitNative = listener;
        return () => undefined;
      }),
    } as unknown as RuntimeHostAdapter;
    const runtime = createRuntimeHostPort(adapter);
    const conversationListener = vi.fn();
    const planEvents: PlanEvent[] = [];
    const unsubscribePlan = subscribePlanHostEvents((event) => planEvents.push(event));
    const unsubscribeRuntime = await runtime.subscribe(conversationListener);

    emitNative({
      kind: "plan",
      event: {
        jsonrpc: "2.0",
        method: "plan/changed",
        params: {
          planId: "plan_demo",
          ownerThreadId: "thr_root",
          planRevision: 3,
          eventSequence: 7,
          occurredAt: "2026-09-04T08:00:00Z",
          plan: {
            planId: "plan_demo",
            owner: { kind: "thread", threadId: "thr_root" },
            status: "executing",
            revision: 3,
          },
          progress: {
            currentStepId: null,
            currentStepTitle: null,
            completedRequiredSteps: 0,
            totalRequiredSteps: 1,
          },
        },
      },
    } as never);
    expect(conversationListener).not.toHaveBeenCalled();
    expect(planEvents.map((event) => event.method)).toEqual(["plan/changed"]);
    expect(planEvents[0]).toMatchObject({ ownerThreadId: "thr_root", planRevision: 3 });

    unsubscribePlan();
    unsubscribeRuntime();
  });

  /** Task Activity 先进入唯一 Timeline 实体表再发给常驻控制器；过期/重复事实不覆盖新 revision。 @author kongweiguang */
  it("reduces Task Activity by owner, task revision and activity identity before bus fan-out", async () => {
    let emitNative!: Parameters<RuntimeHostAdapter["subscribe"]>[0];
    const adapter = {
      subscribe: vi.fn(async (listener) => {
        emitNative = listener;
        return () => undefined;
      }),
    } as unknown as RuntimeHostAdapter;
    useTimelineStore.getState().reset();
    const controlEvents: unknown[] = [];
    const stopTaskEvents = subscribeTaskHostEvents((event) => controlEvents.push(event));
    const conversationListener = vi.fn();
    const stopRuntime = await createRuntimeHostPort(adapter).subscribe(conversationListener);
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
      unreadCount: 1,
      descendantCount: 0,
      runningDescendantCount: 0,
      needsAttentionCount: 0,
      latestSafeSummary: "正在检查",
      startedAt: "2026-09-03T08:00:00Z",
      completedAt: null,
      updatedAt: "2026-09-03T08:00:02Z",
    };
    const activity: TaskActivity = {
      activitySequence: 3,
      activityId: "activity_child",
      rootThreadId: "thr_root",
      taskThreadId: "thr_child",
      actorThreadId: "thr_child",
      causalTurnId: "turn_child",
      kind: "progress",
      summary: { text: "正在检查" },
      createdAt: "2026-09-03T08:00:02Z",
    };
    /** 模拟 adapter 已严格校验的原生事件；控制器 fan-out 应保留原始事件顺序。 @author kongweiguang */
    const emitActivity = (nextActivity: TaskActivity, nextTask: TaskSummary): void => {
      emitNative({
        kind: "task",
        event: {
          method: "task/activity",
          params: {
            rootThreadId: "thr_root",
            taskThreadId: "thr_child",
            taskRevision: nextTask.revision,
            activity: nextActivity,
            task: nextTask,
          },
        },
      } as never);
    };

    try {
      emitActivity(activity, task);
      emitActivity(activity, task);
      emitActivity(
        { ...activity, activityId: "activity_new", activitySequence: 4 },
        { ...task, revision: 3, latestActivitySequence: 4, updatedAt: "2026-09-03T08:00:04Z" },
      );

      const stored = useTimelineStore.getState().taskActivitiesByRootThread["thr_root"] ?? [];
      expect(stored.map((entry) => entry.activity.activityId)).toEqual([
        "activity_child",
        "activity_new",
      ]);
      expect(stored.every((entry) => entry.task.revision === 3)).toBe(true);
      expect(controlEvents).toHaveLength(3);
      expect(conversationListener).not.toHaveBeenCalled();
    } finally {
      stopTaskEvents();
      await stopRuntime();
      useTimelineStore.getState().reset();
    }
  });
});
