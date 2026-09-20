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
  pruneInactiveThreads as pruneInactiveThreadProjection,
  requireActiveTurnResync,
  requireThreadResync,
  type AcceptedTurnProjection,
  type TimelineState,
} from "../domain/timelineReducer";

export interface TimelineStore extends TimelineState {
  /** 只标记由权威 snapshot 恢复出的 active Turn，供可见 Conversation 做有限对账。 */
  recoveredActiveTurnByThread: Record<string, string>;
  /** 每次重读意图的单调序号；相同 reason 的失败重试也必须重新触发 consumer。 */
  resyncRequestSequenceByThread: Record<string, number>;
  applySnapshot: (snapshot: TimelineSnapshot, workspaceId: string) => TimelineState["lastOutcome"];
  applyHostEvent: (event: ConversationHostEvent) => TimelineState["lastOutcome"];
  applyTurnAccepted: (accepted: AcceptedTurnProjection) => TimelineState["lastOutcome"];
  applyInputQueue: (inputQueue: InputQueue) => TimelineState["lastOutcome"];
  applyRuntimeStatus: (
    status: Parameters<typeof applyRuntimeStatus>[1],
  ) => TimelineState["lastOutcome"];
  recordThreadMetadataRevision: (threadId: string, revision: number) => void;
  requestThreadResync: (threadId: string) => void;
  pruneInactiveThreads: (threadIds: readonly string[]) => void;
  reset: () => void;
}

const TIMELINE_STORE_GLOBAL_KEY = "__JA_TIMELINE_STORE_V1__";

type TimelineStoreRegistry = typeof globalThis & {
  [TIMELINE_STORE_GLOBAL_KEY]?: UseBoundStore<StoreApi<TimelineStore>>;
};

const draftItemByProjection = new WeakMap<
  NonNullable<TimelineState["draftByTurn"][string]>[number],
  TimelineItemAdapter
>();
const EMPTY_TASK_ACTIVITIES: readonly TimelineTaskActivityEntry[] = [];

/**
 * 把同一份 Draft Segment 映射为稳定的 Item 引用；assistant Draft 保留 commentary wire 语义，
 * Renderer 将当前未结算正文放入回复阅读位置，Reasoning 始终属于工作过程。WeakMap 让重复 Selector
 * 保持引用稳定，并在模型步骤提交或完整历史快照接管后自动释放缓存。
 */
function draftItemForTurn(
  threadId: string,
  turnId: string,
  draft: NonNullable<TimelineState["draftByTurn"][string]>[number],
): TimelineItemAdapter {
  const cached = draftItemByProjection.get(draft);
  if (cached !== undefined) return cached;
  const item: TimelineItemAdapter = {
    itemId: `draft:${turnId}:${draft.segmentStartSeq}`,
    threadId,
    turnId,
    // assistant 草稿保留协议身份；Tool 模型步提交后会由持久 commentary 原位接管工作过程。
    kind: draft.kind === "reasoning" ? "reasoning" : "commentary",
    status: "in_progress",
    text: draft.text,
    title: draft.kind === "reasoning" ? "思考摘要" : "回复过程",
    metadata: { phase: draft.kind === "reasoning" ? "reasoning_summary" : "assistant_progress" },
    createdAt: draft.occurredAt,
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
    recoveredActiveTurnByThread: {},
    resyncRequestSequenceByThread: {},
    applySnapshot: (snapshot, workspaceId) => {
      let nextOutcome: TimelineState["lastOutcome"] = "invalid";
      set((state) => {
        const next = applySnapshot(state, snapshot, workspaceId);
        nextOutcome = next.lastOutcome;
        if (next.lastOutcome !== "applied")
          return {
            ...next,
            recoveredActiveTurnByThread: state.recoveredActiveTurnByThread ?? {},
          };
        const recoveredActiveTurn = snapshot.turns.find(
          (turn) => !["completed", "failed", "cancelled"].includes(turn.status),
        )?.turnId;
        const recoveredActiveTurnByThread = { ...(state.recoveredActiveTurnByThread ?? {}) };
        if (recoveredActiveTurn === undefined)
          delete recoveredActiveTurnByThread[snapshot.threadId];
        else recoveredActiveTurnByThread[snapshot.threadId] = recoveredActiveTurn;
        return { ...next, recoveredActiveTurnByThread };
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
        if (event.kind === "timeline") {
          const threadId =
            "threadId" in event.event.params ? event.event.params.threadId : undefined;
          if (threadId === undefined) return next;
          const recoveredActiveTurnByThread = { ...(state.recoveredActiveTurnByThread ?? {}) };
          const recoveredTurnId = recoveredActiveTurnByThread[threadId];
          const projectedTurn =
            recoveredTurnId === undefined ? undefined : next.turns[recoveredTurnId];
          if (
            recoveredTurnId !== undefined &&
            (projectedTurn === undefined ||
              ["completed", "failed", "cancelled"].includes(projectedTurn.status))
          )
            delete recoveredActiveTurnByThread[threadId];
          return { ...next, recoveredActiveTurnByThread };
        }
        if (event.kind === "status" && next.handshake.generation !== state.handshake.generation)
          return {
            ...next,
            recoveredActiveTurnByThread: {},
            resyncRequestSequenceByThread: {},
          };
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
        if (next.lastOutcome !== "applied") return next;
        const recoveredActiveTurnByThread = { ...(state.recoveredActiveTurnByThread ?? {}) };
        delete recoveredActiveTurnByThread[accepted.threadId];
        return { ...next, recoveredActiveTurnByThread };
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
        if (next.handshake.generation !== state.handshake.generation)
          return {
            ...next,
            recoveredActiveTurnByThread: {},
            resyncRequestSequenceByThread: {},
          };
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
    requestThreadResync: (threadId) =>
      set((state) => ({
        ...requireThreadResync(state, threadId),
        resyncRequestSequenceByThread: {
          ...(state.resyncRequestSequenceByThread ?? {}),
          [threadId]: ((state.resyncRequestSequenceByThread ?? {})[threadId] ?? 0) + 1,
        },
      })),
    /** 仅清理控制器淘汰的非活动缓存 Thread；Reducer 会再次保护实时任务与审批投影。 */
    pruneInactiveThreads: (threadIds) =>
      set((state) => {
        const next = pruneInactiveThreadProjection(state, threadIds);
        const removed = new Set(threadIds);
        return {
          ...next,
          recoveredActiveTurnByThread: Object.fromEntries(
            Object.entries(state.recoveredActiveTurnByThread ?? {}).filter(
              ([threadId]) => !removed.has(threadId),
            ),
          ),
          resyncRequestSequenceByThread: Object.fromEntries(
            Object.entries(state.resyncRequestSequenceByThread ?? {}).filter(
              ([threadId]) => !removed.has(threadId),
            ),
          ),
        };
      }),
    reset: () =>
      set({
        ...createTimelineState(),
        recoveredActiveTurnByThread: {},
        resyncRequestSequenceByThread: {},
      }),
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
 * 只选择已经进入权威 Thread 顺序的条目；页头摘要、文件入口和空态不读取逐段 Draft，
 * 避免高频正文把 Timeline 之外的 Composer 与导航共同父级带入 React commit。
 */
export const selectCommittedItemsForThread = (threadId: string) => (state: TimelineStore) =>
  (state.itemIdsByThread[threadId] ?? [])
    .map((itemId) => state.items[itemId])
    .filter((item) => item !== undefined);

/**
 * 按 Thread 组装持久 Item，并把尚未结算的公开回复/Reasoning segments 附在对应 Turn；Tool 模型步
 * 将正文结算到工作过程，terminal 以 finalMessage 校准回复并保留取消前正文，整个过程不反写 Java。
 */
export const selectItemsForThread = (threadId: string) => (state: TimelineStore) => {
  const committed = selectCommittedItemsForThread(threadId)(state);
  const drafts = Object.values(state.turns).flatMap((turn) => {
    const drafts = state.draftByTurn[turn.turnId];
    if (turn.threadId !== threadId || drafts === undefined) return [];
    return drafts
      .filter((draft) => draft.text.trim() !== "")
      .map((draft) => draftItemForTurn(threadId, turn.turnId, draft));
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
