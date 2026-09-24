// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { create, type StoreApi, type UseBoundStore } from "zustand";
import type { ConversationHostEvent } from "./ports";
import type { TimelineSnapshot, TimelineTaskActivityEntry } from "../domain/timelineContracts";
import type { InputQueue } from "../domain/timelineContracts";
import type { TimelineEvent } from "../domain/timelineContracts";
import type { TimelineItemAdapter } from "../domain/timelineTypes";
import {
  applyLiveEvent,
  applyInputQueue,
  applySnapshot,
  applyTurnAccepted,
  applyRuntimeStatus,
  createTimelineState,
  markThreadResync,
  pruneInactiveThreads as pruneInactiveThreadProjection,
  requireActiveTurnResync,
  type AcceptedTurnProjection,
  type TimelineState,
} from "../domain/timelineReducer";

/** 最近一次已由 Reducer 接纳的 live 事实；receivedAt 使用本地接收时钟，不使用 Provider occurredAt。 */
export interface TimelineLiveActivity {
  threadId: string;
  turnId?: string;
  generation: number;
  serverInstanceId?: string;
  streamSeq?: number;
  receivedAt: number;
}

/** Controller 自己的请求 epoch 与 Store 的有界事件缓冲绑定，旧请求不能提交新恢复结果。 */
export interface TimelineRecoveryToken {
  threadId: string;
  requestEpoch: number;
  mode: "health" | "recovery";
}

export type TimelineRecoveryResult =
  | { status: "applied"; replayedEvents: number }
  | {
      status: "stale" | "overflow" | "invalid" | "late" | "needs_baseline";
      replayedEvents: number;
    };

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
  beginRecovery: (
    threadId: string,
    requestEpoch: number,
    mode?: "health" | "recovery",
  ) => TimelineRecoveryToken | undefined;
  endRecovery: (
    token: TimelineRecoveryToken,
    snapshot: TimelineSnapshot,
    workspaceId: string,
  ) => TimelineRecoveryResult;
  cancelRecovery: (token: TimelineRecoveryToken) => void;
  getLastAcceptedLive: (threadId: string) => TimelineLiveActivity | undefined;
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
type RetryStatusProjection = NonNullable<TimelineState["retryingByTurn"][string]>;
const retryStatusItemByProjection = new WeakMap<RetryStatusProjection, TimelineItemAdapter>();
const EMPTY_TASK_ACTIVITIES: readonly TimelineTaskActivityEntry[] = [];
const MAX_RECOVERY_EVENTS = 256;
const MAX_RECOVERY_BYTES = 1024 * 1024;

/**
 * 选择恢复后的唯一对账目标；liveStream 的 Turn 是最强证据，否则优先最新 running/queued。
 * 快照可能同时带有历史 queued Turn 和当前 running Turn，按数组首项会把 watchdog 绑到旧 Turn，
 * 造成恢复链路看似工作却永远不收敛，因此等待态只作为最后的可恢复兜底。
 */
function selectRecoveredActiveTurnId(
  turns: readonly { turnId: string; status: string }[],
  preferredTurnId?: string,
): string | undefined {
  const terminalStates = new Set(["completed", "failed", "cancelled"]);
  const preferred =
    preferredTurnId === undefined
      ? undefined
      : turns.find((turn) => turn.turnId === preferredTurnId && !terminalStates.has(turn.status));
  if (preferred !== undefined) return preferred.turnId;
  for (const status of ["running", "queued", "waiting_approval", "suspended"]) {
    for (let index = turns.length - 1; index >= 0; index -= 1) {
      const turn = turns[index];
      if (turn?.status === status) return turn.turnId;
    }
  }
  return undefined;
}

interface BufferedLiveEvent {
  event: TimelineEvent;
  receivedAt: number;
}

interface RecoveryBuffer {
  requestEpoch: number;
  mode: "health" | "recovery";
  generation: number;
  serverInstanceId: string | undefined;
  events: BufferedLiveEvent[];
  bytes: number;
  overflow: boolean;
}

/** 只从已校验 Host Event 提取 Thread identity，Recovery buffer 不接受跨 Thread 事件。 */
function hostEventThreadId(event: ConversationHostEvent): string | undefined {
  if (event.kind !== "timeline" || !("threadId" in event.event.params)) return undefined;
  return event.event.params.threadId;
}

/** 提取 live event 的 Turn 与序号，用于静默监测和恢复后的 activity 水位。 */
function liveActivityFromEvent(
  event: TimelineEvent,
  state: TimelineState,
  receivedAt: number,
): TimelineLiveActivity | undefined {
  if (!("threadId" in event.params)) return undefined;
  const params = event.params;
  return {
    threadId: params.threadId,
    ...("turnId" in params && typeof params.turnId === "string" ? { turnId: params.turnId } : {}),
    generation: params.generation,
    ...(state.serverInstanceId === undefined ? {} : { serverInstanceId: state.serverInstanceId }),
    ...("streamSeq" in params && typeof params.streamSeq === "number"
      ? { streamSeq: params.streamSeq }
      : {}),
    receivedAt,
  };
}

/** 为有界恢复队列估算正文负载，超限即要求重新读取而不接纳残缺事件。 */
function liveEventBytes(event: TimelineEvent): number {
  return new TextEncoder().encode(JSON.stringify(event)).byteLength;
}

/** Stream delta 没有独立持久事实；baseline 为空时只能丢弃这类残缺事件。 */
function isStreamDelta(event: TimelineEvent): boolean {
  return (
    event.method === "assistant/text-delta" || event.method === "assistant/reasoning-summary-delta"
  );
}

/** 终态、Tool 和队列事件有完整持久语义，即使 live baseline 暂不可恢复也必须继续吸收。 */
function isDurableRecoveryEvent(event: TimelineEvent): boolean {
  return !isStreamDelta(event);
}

/**
 * 将暂态 Draft 插入同一 Turn 的已提交步骤之间。已提交列表的原始顺序仍是历史恢复的基线；只在双方都
 * 有权威发生时间时调整 Draft，避免全量排序打乱缺失时间的旧记录，也避免每个流式片段重排整个 Timeline。
 */
function interleaveDraftsWithCommittedItems(
  committed: readonly TimelineItemAdapter[],
  drafts: readonly TimelineItemAdapter[],
): TimelineItemAdapter[] {
  const merged = [...committed];
  for (const draft of drafts) {
    const draftMillis = Date.parse(draft.createdAt ?? "");
    if (!Number.isFinite(draftMillis)) {
      merged.push(draft);
      continue;
    }
    let insertionIndex = merged.length;
    let lastSameTurnIndex = -1;
    for (let index = 0; index < merged.length; index += 1) {
      const candidate = merged[index];
      if (candidate === undefined || candidate.turnId !== draft.turnId) continue;
      lastSameTurnIndex = index;
      const candidateMillis = Date.parse(candidate.createdAt ?? "");
      if (Number.isFinite(candidateMillis) && candidateMillis > draftMillis) {
        insertionIndex = index;
        break;
      }
    }
    if (insertionIndex === merged.length && lastSameTurnIndex >= 0) {
      insertionIndex = lastSameTurnIndex + 1;
    }
    merged.splice(insertionIndex, 0, draft);
  }
  return merged;
}

/**
 * 把同一份 Draft Segment 映射为稳定的 Item 引用；assistant Draft 与 Reasoning 都保留过程语义，
 * Renderer 在 terminal 前将它们固定投影到 WorkProcess。WeakMap 让重复 Selector 保持引用稳定，
 * 并在模型步骤提交或完整历史快照接管后自动释放缓存。
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
    // assistant 草稿保留协议身份，使流式 Draft 与持久 commentary 始终共享 WorkProcess 边界。
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
 * useSyncExternalStore 要求同一 Retry Descriptor 重复选取时返回相同 Item identity；弱缓存避免每次
 * render 都生成新快照，触发浅比较失效与 React 更新循环，同时由 immutable descriptor 自然失效。
 */
function retryStatusItemForTurn(
  threadId: string,
  turnId: string,
  retry: RetryStatusProjection,
): TimelineItemAdapter {
  const cached = retryStatusItemByProjection.get(retry);
  if (cached !== undefined) return cached;
  const item: TimelineItemAdapter = {
    itemId: `retry:${turnId}:${retry.attempt}`,
    threadId,
    turnId,
    kind: "commentary",
    status: "in_progress",
    text: `重试 ${retry.attempt}/${retry.maxAttempts}`,
    title: "重试",
    metadata: { phase: "assistant_retry" },
    createdAt: retry.occurredAt,
  };
  retryStatusItemByProjection.set(retry, item);
  return item;
}

/**
 * 创建单个 WebView 生命周期内的规范化权威投影；Store 不提供持久层，真实 reload 仍从
 * Runtime 与 Java/SQLite 恢复，只有 Vite HMR 的模块重求值需要复用原实例。
 */
function createTimelineStore(): UseBoundStore<StoreApi<TimelineStore>> {
  const recoveryBuffers = new Map<string, RecoveryBuffer>();
  const lastAcceptedLiveByThread = new Map<string, TimelineLiveActivity>();
  return create<TimelineStore>((set, get) => ({
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
        const recoveredActiveTurn = selectRecoveredActiveTurnId(
          snapshot.turns,
          snapshot.liveStream?.turnId,
        );
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
      const threadId = hostEventThreadId(event);
      if (threadId !== undefined && event.kind === "timeline") {
        const recovery = recoveryBuffers.get(threadId);
        if (recovery !== undefined) {
          const receivedAt = Date.now();
          if (!recovery.overflow) {
            const bytes = liveEventBytes(event.event);
            if (
              recovery.events.length >= MAX_RECOVERY_EVENTS ||
              recovery.bytes + bytes > MAX_RECOVERY_BYTES
            ) {
              recovery.overflow = true;
              recovery.events = [];
              recovery.bytes = 0;
            } else {
              recovery.events.push({ event: event.event, receivedAt });
              recovery.bytes += bytes;
            }
          }
          // Recovery keeps a replay copy but applies the live event immediately. Terminal and
          // committed facts therefore remain visible while the authoritative read is in flight.
          let nextOutcome: TimelineState["lastOutcome"] = "rejected";
          set((state) => {
            const next = applyLiveEvent(state, event.event);
            nextOutcome = next.lastOutcome;
            if (next.lastOutcome === "applied") {
              const activity = liveActivityFromEvent(event.event, next, receivedAt);
              if (activity !== undefined) lastAcceptedLiveByThread.set(threadId, activity);
            }
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
          });
          return nextOutcome;
        }
      }
      let nextOutcome: TimelineState["lastOutcome"] = "rejected";
      set((state) => {
        const next =
          event.kind === "status"
            ? applyRuntimeStatus(state, event.status)
            : event.kind === "timeline"
              ? applyLiveEvent(state, event.event)
              : requireActiveTurnResync(state, "projection_fault");
        nextOutcome = next.lastOutcome;
        if (event.kind === "timeline" && next.lastOutcome === "applied") {
          const activity = liveActivityFromEvent(event.event, next, Date.now());
          if (activity !== undefined) lastAcceptedLiveByThread.set(activity.threadId, activity);
        }
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
        if (event.kind === "status" && next.handshake.generation !== state.handshake.generation) {
          recoveryBuffers.clear();
          lastAcceptedLiveByThread.clear();
          return {
            ...next,
            recoveredActiveTurnByThread: {},
            resyncRequestSequenceByThread: {},
          };
        }
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
        if (next.handshake.generation !== state.handshake.generation) {
          recoveryBuffers.clear();
          lastAcceptedLiveByThread.clear();
          return {
            ...next,
            recoveredActiveTurnByThread: {},
            resyncRequestSequenceByThread: {},
          };
        }
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
        ...markThreadResync(state, threadId),
        resyncRequestSequenceByThread: {
          ...(state.resyncRequestSequenceByThread ?? {}),
          [threadId]: ((state.resyncRequestSequenceByThread ?? {})[threadId] ?? 0) + 1,
        },
      })),
    /** 开始一次有界恢复窗口；窗口内 live event 暂存，避免快照提交时产生半帧 Gap。 */
    beginRecovery: (threadId, requestEpoch, mode = "recovery") => {
      const current = recoveryBuffers.get(threadId);
      if (current !== undefined && current.requestEpoch >= requestEpoch) return undefined;
      const state = get();
      recoveryBuffers.set(threadId, {
        requestEpoch,
        mode,
        generation: state.handshake.generation,
        serverInstanceId: state.serverInstanceId,
        events: [],
        bytes: 0,
        overflow: false,
      });
      return { threadId, requestEpoch, mode };
    },
    /**
     * 以单个 Zustand commit 应用 authoritative baseline 并重放读取期间的 live event；溢出、空 baseline
     * 或 replay gap 都只返回需重读结果，不把队列中的残缺正文发布到 UI。
     */
    endRecovery: (token, snapshot, workspaceId) => {
      const recovery = recoveryBuffers.get(token.threadId);
      if (
        recovery === undefined ||
        recovery.requestEpoch !== token.requestEpoch ||
        recovery.mode !== token.mode
      ) {
        return { status: "stale", replayedEvents: 0 };
      }
      const current = get();
      if (
        current.handshake.generation !== recovery.generation ||
        current.serverInstanceId !== recovery.serverInstanceId
      ) {
        recoveryBuffers.delete(token.threadId);
        set((state) => markThreadResync(state, token.threadId));
        return { status: "stale", replayedEvents: 0 };
      }
      recoveryBuffers.delete(token.threadId);
      if (recovery.overflow) {
        set((state) => markThreadResync(state, token.threadId));
        return { status: "overflow", replayedEvents: 0 };
      }
      let result: TimelineRecoveryResult = { status: "invalid", replayedEvents: 0 };
      set((state) => {
        let next = applySnapshot(state, snapshot, workspaceId, { mode: recovery.mode });
        const snapshotNeedsBaseline =
          recovery.mode === "recovery" &&
          snapshot.liveStream === null &&
          snapshot.turns.some(
            (turn) => !["completed", "failed", "cancelled"].includes(turn.status),
          ) &&
          next.lastOutcome === "resync_required";
        if (next.lastOutcome === "late") {
          result = { status: "late", replayedEvents: 0 };
          return next;
        }
        if (next.lastOutcome !== "applied") {
          // rejected/其它 resync_required 也必须停止本轮重放；只有明确缺少 live baseline
          // 才返回 needs_baseline，避免无效快照被缓冲事件伪装成 applied。
          result = {
            status: snapshotNeedsBaseline ? "needs_baseline" : "invalid",
            replayedEvents: 0,
          };
          return next;
        }
        const hasBaseline = snapshot.liveStream !== null;
        let replayedEvents = 0;
        for (const buffered of recovery.events) {
          if (!hasBaseline && !isDurableRecoveryEvent(buffered.event)) continue;
          next = applyLiveEvent(next, buffered.event);
          replayedEvents += 1;
          if (
            next.lastOutcome === "gap" ||
            next.lastOutcome === "resync_required" ||
            next.lastOutcome === "invalid" ||
            next.lastOutcome === "rejected"
          ) {
            result = { status: "needs_baseline", replayedEvents };
            return next;
          }
          if (next.lastOutcome === "applied") {
            const activity = liveActivityFromEvent(buffered.event, next, buffered.receivedAt);
            if (activity !== undefined) lastAcceptedLiveByThread.set(token.threadId, activity);
          }
        }
        const hasActiveTurn = Object.values(next.turns).some(
          (turn) =>
            turn.threadId === token.threadId &&
            !["completed", "failed", "cancelled"].includes(turn.status),
        );
        const recoveredActiveTurnByThread = { ...(state.recoveredActiveTurnByThread ?? {}) };
        if (hasActiveTurn) {
          const activeTurn = selectRecoveredActiveTurnId(
            Object.values(next.turns).filter((turn) => turn.threadId === token.threadId),
            snapshot.liveStream?.turnId,
          );
          if (activeTurn !== undefined) recoveredActiveTurnByThread[token.threadId] = activeTurn;
        } else {
          delete recoveredActiveTurnByThread[token.threadId];
          if (snapshotNeedsBaseline) {
            const resyncRequired = { ...next.resyncRequired };
            delete resyncRequired[token.threadId];
            next = { ...next, resyncRequired };
          }
        }
        result =
          snapshotNeedsBaseline && hasActiveTurn
            ? { status: "needs_baseline", replayedEvents }
            : { status: "applied", replayedEvents };
        return { ...next, recoveredActiveTurnByThread };
      });
      return result;
    },
    /** 取消读取时保留当前即时投影，并标记一次非破坏性对账，避免已展示 terminal 静默丢失。 */
    cancelRecovery: (token) => {
      const current = recoveryBuffers.get(token.threadId);
      if (current?.requestEpoch === token.requestEpoch) {
        recoveryBuffers.delete(token.threadId);
        set((state) => markThreadResync(state, token.threadId));
      }
    },
    /** 供 Controller 静默监测读取，不建立 Zustand subscription，也不把 delta 扩散到 Shell。 */
    getLastAcceptedLive: (threadId) => lastAcceptedLiveByThread.get(threadId),
    /** 仅清理控制器淘汰的非活动缓存 Thread；Reducer 会再次保护实时任务与审批投影。 */
    pruneInactiveThreads: (threadIds) => {
      for (const threadId of threadIds) {
        recoveryBuffers.delete(threadId);
        lastAcceptedLiveByThread.delete(threadId);
      }
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
      });
    },
    reset: () => {
      recoveryBuffers.clear();
      lastAcceptedLiveByThread.clear();
      set({
        ...createTimelineState(),
        recoveredActiveTurnByThread: {},
        resyncRequestSequenceByThread: {},
      });
    },
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
/**
 * 按同一 Turn 的权威发生时间交错暂态正文与持久步骤。不能简单把 Draft 追加到所有 Tool 后面，
 * 否则自动上下文压缩等中途步骤会越过此前正文；无时间的恢复记录继续保留原有稳定顺序。
 * 重试提示作为单一瞬态步骤投影，失败尝试正文已由 reducer 清除，避免和新一轮输出叠加。
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
  const retryStatuses = Object.entries(state.retryingByTurn).flatMap(([turnId, retry]) => {
    const turn = state.turns[turnId];
    if (turn?.threadId !== threadId || turn.status !== "running") return [];
    return [retryStatusItemForTurn(threadId, turnId, retry)];
  });
  return interleaveDraftsWithCommittedItems(committed, [...drafts, ...retryStatuses]);
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
