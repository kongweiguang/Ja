// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useTimelineStore } from "@/features/conversation";
import type { RuntimeHostEvent, RuntimeProjectionPort } from "../application/runtimePorts";

type RuntimeProjectionEvent = Exclude<RuntimeHostEvent, { kind: "status" }>;

/**
 * 提取 Timeline 事件携带的 Thread identity；全局配置事件和 projection fault 没有 Thread，
 * 仍需交给原有 reducer 处理，不能因 Child 隔离而吞掉 Runtime 级故障信号。
 */
function timelineThreadId(event: RuntimeProjectionEvent): string | undefined {
  if (event.kind !== "timeline") return undefined;
  const threadId = (event.event.params as { readonly threadId?: unknown }).threadId;
  return typeof threadId === "string" ? threadId : undefined;
}

/**
 * 在 composition 边界把 Runtime 的已准入 projection intent 交给唯一 Conversation Store。
 * Adapter 每次读取当前 store state，避免缓存 Zustand snapshot 或复制第二份 Timeline 状态。
 */
export function bindRuntimeProjectionPort(): RuntimeProjectionPort {
  return {
    /** 仅在 generation-zero stop 投影时读取握手基线，不把完整 Store snapshot带回 Runtime。 */
    currentGeneration: () => useTimelineStore.getState().handshake.generation,
    /** 生命周期状态已经过 Runtime generation fence，adapter 只转交唯一 reducer owner。 */
    applyRuntimeStatus: (status) => {
      useTimelineStore.getState().applyRuntimeStatus(status);
    },
    /** ACK 必须先于缓存事件写入，以便 Timeline reducer 建立正确 revision 基线。 */
    applyTurnAccepted: (accepted) => {
      useTimelineStore.getState().applyTurnAccepted(accepted);
    },
    /** 队列 ACK 先写入全量 projection，随后重放的 Event 只允许更高 revision 覆盖。 */
    applyInputQueue: (inputQueue) => {
      useTimelineStore.getState().applyInputQueue(inputQueue);
    },
    /**
     * Thread Snapshot 是 Conversation 投影的 admission：未加载的 Child Thread 只由 Task
     * activity/progress 与 thread/read 驱动，不能把其 Turn、Delta 或 Approval 物化进主 Store。
     */
    applyHostEvent: (event) => {
      const store = useTimelineStore.getState();
      const threadId = timelineThreadId(event);
      if (threadId !== undefined && store.threads[threadId] === undefined) return;
      store.applyHostEvent(event);
    },
  };
}

/** 稳定 port identity 只封装 Store seam，不持有 snapshot，因此可跨 Provider 重渲染复用。 */
export const DEFAULT_RUNTIME_PROJECTION_PORT: RuntimeProjectionPort = bindRuntimeProjectionPort();
