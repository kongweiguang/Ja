// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useTimelineStore } from "@/features/conversation";
import type { RuntimeProjectionPort } from "../application/runtimePorts";

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
    /** Host event 已在 Runtime adapter 校验；composition 不重复解释 wire 或复制 reducer。 */
    applyHostEvent: (event) => {
      useTimelineStore.getState().applyHostEvent(event);
    },
  };
}

/** 稳定 port identity 只封装 Store seam，不持有 snapshot，因此可跨 Provider 重渲染复用。 */
export const DEFAULT_RUNTIME_PROJECTION_PORT: RuntimeProjectionPort = bindRuntimeProjectionPort();
