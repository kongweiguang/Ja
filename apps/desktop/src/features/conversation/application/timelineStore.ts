// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { create, type StoreApi, type UseBoundStore } from "zustand";
import type { ConversationHostEvent } from "./ports";
import type { TimelineSnapshot, TimelineTaskActivityEntry } from "../domain/timelineContracts";
import type { InputQueue } from "../domain/timelineContracts";
import type { TimelineItemAdapter } from "../domain/timelineTypes";
import {
  applyLiveEvent,
  applyInputQueue,
  applySnapshot,
  applyTurnAccepted,
  applyRuntimeStatus,
  createTimelineState,
  requireActiveTurnResync,
  requireThreadResync,
  type AcceptedTurnProjection,
  type TimelineState,
} from "../domain/timelineReducer";

export interface TimelineStore extends TimelineState {
  applySnapshot: (snapshot: TimelineSnapshot, workspaceId: string) => TimelineState["lastOutcome"];
  applyHostEvent: (event: ConversationHostEvent) => TimelineState["lastOutcome"];
  applyTurnAccepted: (accepted: AcceptedTurnProjection) => TimelineState["lastOutcome"];
  applyInputQueue: (inputQueue: InputQueue) => TimelineState["lastOutcome"];
  applyRuntimeStatus: (
    status: Parameters<typeof applyRuntimeStatus>[1],
  ) => TimelineState["lastOutcome"];
  recordThreadMetadataRevision: (threadId: string, revision: number) => void;
  requestThreadResync: (threadId: string) => void;
  reset: () => void;
}

const TIMELINE_STORE_GLOBAL_KEY = "__JA_TIMELINE_STORE_V1__";

type TimelineStoreRegistry = typeof globalThis & {
  [TIMELINE_STORE_GLOBAL_KEY]?: UseBoundStore<StoreApi<TimelineStore>>;
};

const draftItemByProjection = new WeakMap<
  TimelineState["draftByTurn"][string],
  TimelineItemAdapter
>();
const EMPTY_TASK_ACTIVITIES: readonly TimelineTaskActivityEntry[] = [];

/**
 * 把同一份 Draft Projection 映射为稳定的 Item 引用；公开回复 delta 直接占用 Agent Message
 * 的最终阅读位置，避免终态到达时从“过程”突跳成答案。Reasoning Summary 仍属于可折叠工作过程。
 * WeakMap 让重复 Selector 保持引用稳定，并在终态清理 Draft 后自动释放缓存。
 */
function draftItemForTurn(
  threadId: string,
  turnId: string,
  draft: TimelineState["draftByTurn"][string],
): TimelineItemAdapter {
  const cached = draftItemByProjection.get(draft);
  if (cached !== undefined) return cached;
  const item: TimelineItemAdapter = {
    itemId: `draft:${turnId}`,
    threadId,
    turnId,
    kind: draft.kind === "reasoning" ? "commentary" : "agent_message",
    status: "in_progress",
    text: draft.text,
    title: draft.kind === "reasoning" ? "思考摘要" : undefined,
    metadata: { phase: draft.kind === "reasoning" ? "reasoning_summary" : "assistant_progress" },
  };
  draftItemByProjection.set(draft, item);
  return item;
}

/**
 * 创建单个 WebView 生命周期内的规范化权威投影；Store 不提供持久层，真实 reload 仍从
 * Runtime 与 Java/SQLite 恢复，只有 Vite HMR 的模块重求值需要复用原实例。
 */
function createTimelineStore(): UseBoundStore<StoreApi<TimelineStore>> {
  return create<TimelineStore>((set) => ({
    ...createTimelineState(),
    applySnapshot: (snapshot, workspaceId) => {
      let nextOutcome: TimelineState["lastOutcome"] = "invalid";
      set((state) => {
        const next = applySnapshot(state, snapshot, workspaceId);
        nextOutcome = next.lastOutcome;
        return next;
      });
      return nextOutcome ?? "invalid";
    },
    /**
     * 只接纳类型化 Host Event，并把隔离的原生 Frame 转换为权威 Snapshot 要求；Malformed Payload
     * 不得泄漏到 Store，也不能让 Active Turn 永久停留在 Busy。
     */
    applyHostEvent: (event) => {
      let nextOutcome: TimelineState["lastOutcome"] = "rejected";
      set((state) => {
        const next =
          event.kind === "status"
            ? applyRuntimeStatus(state, event.status)
            : event.kind === "timeline"
              ? applyLiveEvent(state, event.event)
              : requireActiveTurnResync(state, "projection_fault");
        nextOutcome = next.lastOutcome;
        return next;
      });
      return nextOutcome;
    },
    /** 在重放独立投递的 Notification 前提交 turn/start Response，建立正确线性化顺序。 */
    applyTurnAccepted: (accepted) => {
      let nextOutcome: TimelineState["lastOutcome"] = "rejected";
      set((state) => {
        const next = applyTurnAccepted(state, accepted);
        nextOutcome = next.lastOutcome;
        return next;
      });
      return nextOutcome;
    },
    /** Mutation ACK 与队列事件共享同一个 reducer，按 queue revision 幂等收敛。 */
    applyInputQueue: (inputQueue) => {
      let nextOutcome: TimelineState["lastOutcome"] = "rejected";
      set((state) => {
        const next = applyInputQueue(state, inputQueue);
        nextOutcome = next.lastOutcome;
        return next;
      });
      return nextOutcome;
    },
    applyRuntimeStatus: (status) => {
      let nextOutcome: TimelineState["lastOutcome"] = "rejected";
      set((state) => {
        const next = applyRuntimeStatus(state, status);
        nextOutcome = next.lastOutcome;
        return next;
      });
      return nextOutcome;
    },
    /**
     * 标题 metadata 不属于 Timeline item，但会推进 Thread CAS revision；只允许单调前进，
     * 避免自动标题完成后下一次偏好更新仍使用旧 revision。
     */
    recordThreadMetadataRevision: (threadId, revision) =>
      set((state) => {
        const current = state.threadRevisionByThread[threadId] ?? -1;
        if (revision <= current) return state;
        return {
          threadRevisionByThread: {
            ...state.threadRevisionByThread,
            [threadId]: revision,
          },
        };
      }),
    /** 队列 CAS 冲突只建立一次 authoritative read 意图，不在 Renderer 猜测条目现状。 */
    requestThreadResync: (threadId) => set((state) => requireThreadResync(state, threadId)),
    reset: () => set(createTimelineState()),
  }));
}

const timelineStoreRegistry = globalThis as TimelineStoreRegistry;

/**
 * 在同一 WebView 中固定唯一 Timeline store，避免 HMR 后 Controller、Runtime projection 与 UI
 * 分别持有不同模块实例；页面真实 reload 会重建 globalThis，因此不跨运行时缓存业务状态。
 */
export const useTimelineStore =
  timelineStoreRegistry[TIMELINE_STORE_GLOBAL_KEY] ?? createTimelineStore();

timelineStoreRegistry[TIMELINE_STORE_GLOBAL_KEY] = useTimelineStore;

/**
 * 按 Thread 组装持久 Item，并把当前 Assistant delta 作为唯一瞬态答复附在对应 Turn；终态事件会在
 * Reducer 中原子移除 Draft，因此 UI 原位切换到持久最终答复，不会重复展示或把草稿反写给 Java。
 */
export const selectItemsForThread = (threadId: string) => (state: TimelineStore) => {
  const committed = (state.itemIdsByThread[threadId] ?? [])
    .map((itemId) => state.items[itemId])
    .filter((item) => item !== undefined);
  const drafts = Object.values(state.turns).flatMap((turn) => {
    const draft = state.draftByTurn[turn.turnId];
    if (turn.threadId !== threadId || draft === undefined) return [];
    return [draftItemForTurn(threadId, turn.turnId, draft)];
  });
  return [...committed, ...drafts];
};

/** 主 Timeline 只读取当前 root 的持久活动切片；稳定空数组避免隐藏视图产生无效重渲染。 */
export const selectTaskActivitiesForRoot = (rootThreadId: string) => (state: TimelineStore) =>
  state.taskActivitiesByRootThread[rootThreadId] ?? EMPTY_TASK_ACTIVITIES;

const EMPTY_GOAL_ACTIVITIES: readonly import("../domain/timelineContracts").TimelineGoalActivity[] =
  [];

/** Goal 终态由 thread/read 恢复，稳定空数组避免没有历史目标时触发无效重渲染。 */
export const selectGoalActivitiesForOwner = (ownerThreadId: string) => (state: TimelineStore) =>
  state.goalActivitiesByOwnerThread[ownerThreadId] ?? EMPTY_GOAL_ACTIVITIES;

/**
 * Resolution Tombstone 对卡片隐藏但保留在状态中，避免使用同一 approvalId 的迟到请求复活用户任务。
 */
export const selectApprovals = (state: TimelineStore) =>
  Object.values(state.approvalsById)
    .map((projection) => projection.approval)
    .filter((approval): approval is NonNullable<typeof approval> => approval !== undefined);

/**
 * 生成现有 Card Adapter 的 Decision Map，但不让 Approval Event 成为第二事实来源，
 * 也不把未解决条目暴露成 Decision。
 */
export const selectApprovalDecisions = (state: TimelineStore) =>
  Object.fromEntries(
    Object.entries(state.approvalsById)
      .filter(([, projection]) => projection.decision !== undefined)
      .map(([approvalId, projection]) => [approvalId, projection.decision]),
  );

/** 单独暴露 Terminal Closure，使 UI 永远不会伪造 Deny Decision。 */
export const selectApprovalClosedAt = (state: TimelineStore) =>
  Object.fromEntries(
    Object.entries(state.approvalsById)
      .filter(([, projection]) => projection.closedAt !== undefined)
      .map(([approvalId, projection]) => [approvalId, projection.closedAt]),
  );
