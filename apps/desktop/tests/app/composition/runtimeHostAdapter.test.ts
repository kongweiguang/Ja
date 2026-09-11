// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type { RuntimeHostAdapter } from "@/api/tauri/runtime";
import { createRuntimeHostPort } from "@/app/composition/runtimeHostAdapter";
import { subscribeGoalHostEvents, type GoalEvent } from "@/features/goals";
import { useTimelineStore } from "@/features/conversation";

describe("runtimeHostAdapter Goal routing", () => {
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

  it("routes all Goal events only to the Goal bus", async () => {
    let emitNative!: Parameters<RuntimeHostAdapter["subscribe"]>[0];
    const adapter = {
      subscribe: vi.fn(async (listener) => {
        emitNative = listener;
        return () => undefined;
      }),
    } as unknown as RuntimeHostAdapter;
    const runtime = createRuntimeHostPort(adapter);
    const conversationListener = vi.fn();
    const goalEvents: GoalEvent[] = [];
    const unsubscribeGoal = subscribeGoalHostEvents((event) => goalEvents.push(event));
    const unsubscribeRuntime = await runtime.subscribe(conversationListener);

    emitNative({
      kind: "goal",
      event: {
        jsonrpc: "2.0",
        method: "goal/changed",
        params: {
          goalId: "goal_demo",
          goalRevision: 3,
          eventSequence: 7,
          occurredAt: "2026-09-04T08:00:00Z",
          goal: { owner: { kind: "thread", threadId: "thr_root" } },
        },
      },
    } as never);
    for (const method of ["goal/activity"] as const) {
      emitNative({
        kind: "goal",
        event: {
          jsonrpc: "2.0",
          method,
          params: {
            goalId: "goal_demo",
            goalRevision: 4,
            eventSequence: method === "goal/activity" ? 8 : 9,
            occurredAt: "2026-09-04T08:00:01Z",
          },
        },
      } as never);
    }

    expect(conversationListener).not.toHaveBeenCalled();
    expect(goalEvents.map((event) => event.method)).toEqual(["goal/changed", "goal/activity"]);
    expect(goalEvents[0]).toMatchObject({ ownerThreadId: "thr_root", goalRevision: 3 });

    unsubscribeGoal();
    unsubscribeRuntime();
  });
});
