// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { create, type StoreApi, type UseBoundStore } from "zustand";
import type { ConversationHostEvent, ConversationItemContentReader } from "./ports";
import type { TimelineSnapshot, TimelineTaskActivityEntry } from "../domain/timelineContracts";
import type { InputQueue } from "../domain/timelineContracts";
import type { TimelineEvent } from "../domain/timelineContracts";
import type {
  ApprovalDecision,
  TimelineApproval,
  TimelineItemAdapter,
  TimelineItemContentHydration,
  TimelineTurn,
} from "../domain/timelineTypes";
import {
  applyLiveEvent,
  applyTaskActivity as reduceTaskActivity,
  applyInputQueue,
  applySnapshot,
  applyTurnAccepted,
  applyRuntimeStatus,
  applyProjectionResync,
  createTimelineState,
  markThreadResync,
  pruneInactiveThreads as pruneInactiveThreadProjection,
  removeThreadProjection,
  requireActiveTurnResync,
  type ApplySnapshotOptions,
  type AcceptedTurnProjection,
  type TimelineTaskActivityInput,
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

/** Close 回执的客户端所有者；generation 单独不够区分同一代切换到的新服务实例。 */
export interface TimelineRuntimeFence {
  serverInstanceId: string;
  generation: number;
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
  /** 完整公开正文按 Thread 与 Item identity 缓存，避免虚拟行卸载后重读或混淆同名 Item。 */
  itemContentByThread: Record<string, Record<string, TimelineItemContentHydration>>;
  /** 多 Item 的最终答复缓存与真实单 Item 正文分开，避免把聚合结果伪装成某个 Item 的原文。 */
  answerContentByThread: Record<string, Record<string, TimelineItemContentHydration>>;
  /** 逐页补齐一个公开 Item；失败保留已经确认的 code-point 游标，重试从该处继续。 */
  hydrateItemContent: (
    threadId: string,
    itemId: string,
    readPage: ConversationItemContentReader,
    isCurrent?: () => boolean,
  ) => Promise<string>;
  /** 最终答复由多个公开 Item 组成时，聚合结果仍由 TimelineStore 持有。 */
  hydrateAnswerContent: (
    threadId: string,
    finalItemId: string,
    readAnswer: () => Promise<string>,
    isCurrent?: () => boolean,
  ) => Promise<string>;
  /** 无 request fence 时保持显式快照权威；有 fence 时只约束期间变更后的空 Activity 切片。 @author kongweiguang */
  applySnapshot: (
    snapshot: TimelineSnapshot,
    workspaceId: string,
    options?: Pick<ApplySnapshotOptions, "accumulatedHistory" | "taskActivityReadFence">,
  ) => TimelineState["lastOutcome"];
  /** Task Activity 与 Thread Snapshot 共用唯一 owner-root 实体表，revision/identity 决定覆盖顺序。 @author kongweiguang */
  applyTaskActivity: (input: TimelineTaskActivityInput) => TimelineState["lastOutcome"];
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
  /** Recovery 与普通读取使用同一 Activity fence，不能在提交 baseline 时绕开空切片保护。 @author kongweiguang */
  endRecovery: (
    token: TimelineRecoveryToken,
    snapshot: TimelineSnapshot,
    workspaceId: string,
    options?: Pick<ApplySnapshotOptions, "taskActivityReadFence">,
  ) => TimelineRecoveryResult;
  cancelRecovery: (token: TimelineRecoveryToken) => void;
  getLastAcceptedLive: (threadId: string) => TimelineLiveActivity | undefined;
  pruneInactiveThreads: (threadIds: readonly string[]) => void;
  /** 仅权威 close 回执可删除整个 Thread 投影；runtime fence 与 tombstone 阻止迟到事实复活。 */
  closeThreads: (threadIds: readonly string[], fence: TimelineRuntimeFence) => boolean;
  /** 检查当前服务实例与 generation 下 Thread 是否已经收到权威关闭回执。 */
  isThreadClosed: (threadId: string) => boolean;
  reset: () => void;
}

const TIMELINE_STORE_GLOBAL_KEY = "__JA_TIMELINE_STORE_V2__";

type TimelineStoreRegistry = typeof globalThis & {
  [TIMELINE_STORE_GLOBAL_KEY]?: UseBoundStore<StoreApi<TimelineStore>>;
};

const draftItemByProjection = new WeakMap<
  NonNullable<TimelineState["draftByTurn"][string]>[number],
  TimelineItemAdapter
>();
type RetryStatusProjection = NonNullable<TimelineState["retryingByTurn"][string]>;
const retryStatusItemByProjection = new WeakMap<RetryStatusProjection, TimelineItemAdapter>();
type CommittedItemProjection = {
  bodyHydration: TimelineItemContentHydration | undefined;
  answerHydration: TimelineItemContentHydration | undefined;
  item: TimelineItemAdapter;
};
const committedItemProjectionBySource = new WeakMap<TimelineItemAdapter, CommittedItemProjection>();
const EMPTY_THREAD_IDS: readonly string[] = [];
const EMPTY_TASK_ACTIVITIES: readonly TimelineTaskActivityEntry[] = [];
const MAX_RECOVERY_EVENTS = 256;
const MAX_RECOVERY_BYTES = 1024 * 1024;
const ITEM_CONTENT_PAGE_SIZE = 65_536;

/** Java and JA-RPC offsets count Unicode code points, so UTF-16 string length is not a cursor. */
function codePointLength(value: string): number {
  let length = 0;
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.codePointAt(index);
    if (codePoint !== undefined && codePoint > 0xffff) index += 1;
    length += 1;
  }
  return length;
}

/** Hydration 属于读取开始时的 Runtime；重启后 Thread ID 复用也不能接纳旧正文。@author kongweiguang */
function sameRuntimeFence(
  current: TimelineState,
  requested: TimelineRuntimeFence | undefined,
): boolean {
  const currentFence =
    current.handshake.phase === "ready" && current.serverInstanceId !== undefined
      ? { serverInstanceId: current.serverInstanceId, generation: current.handshake.generation }
      : undefined;
  return (
    currentFence?.serverInstanceId === requested?.serverInstanceId &&
    currentFence?.generation === requested?.generation
  );
}

/** Tool/approval and transient retry rows are not public persisted body targets. */
function canReadItemContent(item: TimelineItemAdapter): boolean {
  return ["user_message", "thread_message", "agent_message", "reasoning"].includes(item.kind);
}

/** 缓存同一不可变源 Item 的最近 hydration 投影，保证 useShallow 在同一快照上能稳定复用成员引用。 */
function projectCommittedItem(
  item: TimelineItemAdapter,
  bodyHydration: TimelineItemContentHydration | undefined,
  answerHydration: TimelineItemContentHydration | undefined,
): TimelineItemAdapter {
  if (bodyHydration === undefined && answerHydration === undefined) {
    committedItemProjectionBySource.delete(item);
    return item;
  }

  const cached = committedItemProjectionBySource.get(item);
  if (
    cached !== undefined &&
    cached.bodyHydration === bodyHydration &&
    cached.answerHydration === answerHydration
  ) {
    return cached.item;
  }

  const projected = {
    ...item,
    ...(bodyHydration === undefined ? {} : { bodyHydration }),
    ...(answerHydration === undefined ? {} : { answerHydration }),
  };
  committedItemProjectionBySource.set(item, { bodyHydration, answerHydration, item: projected });
  return projected;
}

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
  if (event.kind === "resync_required") return event.event.threadId;
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
    // Draft/Reasoning 是本次运行中的临时视图，不会进入冻结的历史前缀。
    origin: "native",
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
    itemId: `retry:${turnId}`,
    threadId,
    turnId,
    // Retry projection 描述本会话当前执行的恢复状态，不能继承来源会话的活动态。
    origin: "native",
    kind: "commentary",
    status: "in_progress",
    text: "连接中断，正在恢复",
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
  const itemContentReads = new Map<string, Promise<string>>();
  const answerContentReads = new Map<string, Promise<string>>();
  const closedThreadIds = new Set<string>();
  const closedTurnIds = new Set<string>();
  let closedRuntimeFence: TimelineRuntimeFence | undefined;
  let storeEpoch = 0;
  /** 只在 ready runtime 的 (SID,generation) 改变时换 tombstone owner，普通 reset 不开放旧 Thread。@author kongweiguang */
  const syncClosedRuntimeFence = (state: TimelineState): void => {
    const current =
      state.handshake.phase === "ready" &&
      state.serverInstanceId !== undefined &&
      state.handshake.generation > 0
        ? { serverInstanceId: state.serverInstanceId, generation: state.handshake.generation }
        : undefined;
    if (current === undefined) return;
    if (
      closedRuntimeFence?.serverInstanceId === current.serverInstanceId &&
      closedRuntimeFence.generation === current.generation
    )
      return;
    closedThreadIds.clear();
    closedTurnIds.clear();
    closedRuntimeFence = current;
  };
  /** 所有投影写入口共用同一个闭合 ID 集；这只存身份，不缓存第二份 Timeline 实体。@author kongweiguang */
  const isClosedAtState = (threadId: string, state: TimelineState): boolean => {
    syncClosedRuntimeFence(state);
    return closedThreadIds.has(threadId);
  };
  /** 清理 close 后的 Store 附属投影；运行时 tombstone 单独保留，普通 reset 不能复活旧 Thread。@author kongweiguang */
  const clearClosedThreadExtensions = (threadIds: readonly string[]): void => {
    for (const threadId of threadIds) {
      recoveryBuffers.delete(threadId);
      lastAcceptedLiveByThread.delete(threadId);
      const itemPrefix = `${threadId}\u0000`;
      const answerPrefix = `${threadId}\u0000`;
      for (const key of itemContentReads.keys())
        if (key.startsWith(itemPrefix)) itemContentReads.delete(key);
      for (const key of answerContentReads.keys())
        if (key.startsWith(answerPrefix)) answerContentReads.delete(key);
    }
  };
  return create<TimelineStore>((set, get) => ({
    ...createTimelineState(),
    recoveredActiveTurnByThread: {},
    resyncRequestSequenceByThread: {},
    itemContentByThread: {},
    answerContentByThread: {},
    hydrateItemContent: (threadId, itemId, readPage, isCurrent = () => true) => {
      const requestState = get();
      const requestRuntimeFence =
        requestState.handshake.phase === "ready" && requestState.serverInstanceId !== undefined
          ? {
              serverInstanceId: requestState.serverInstanceId,
              generation: requestState.handshake.generation,
            }
          : undefined;
      const owner = requestState.itemThreadById[itemId];
      const item = requestState.items[itemId];
      const prior = requestState.itemContentByThread[threadId]?.[itemId];
      if (isClosedAtState(threadId, requestState))
        return Promise.reject(new Error("The Thread has been closed."));
      if (owner !== undefined && owner !== threadId)
        return Promise.reject(new Error("Item does not belong to the requested Thread."));
      if (item !== undefined && !canReadItemContent(item))
        return Promise.reject(new Error("This Item does not expose public text content."));
      if (prior?.status === "complete") return Promise.resolve(prior.text);
      if (!isCurrent()) return Promise.reject(new Error("The Thread is no longer visible."));

      const requestKey = `${threadId}\u0000${itemId}`;
      const pending = itemContentReads.get(requestKey);
      if (pending !== undefined) return pending;
      const requestEpoch = storeEpoch;
      const publish = (hydration: TimelineItemContentHydration): void => {
        set((state) => {
          if (
            requestEpoch !== storeEpoch ||
            !sameRuntimeFence(state, requestRuntimeFence) ||
            isClosedAtState(threadId, state) ||
            state.threads[threadId] === undefined ||
            (state.itemThreadById[itemId] !== undefined &&
              state.itemThreadById[itemId] !== threadId)
          )
            return state;
          return {
            itemContentByThread: {
              ...state.itemContentByThread,
              [threadId]: {
                ...(state.itemContentByThread[threadId] ?? {}),
                [itemId]: hydration,
              },
            },
          };
        });
      };
      const read = async (): Promise<string> => {
        const initial = get().itemContentByThread[threadId]?.[itemId];
        publish({
          status: "loading",
          text: initial?.text ?? "",
          offsetCharacters: initial?.offsetCharacters ?? 0,
          ...(initial?.totalCharacters === undefined
            ? {}
            : { totalCharacters: initial.totalCharacters }),
        });
        try {
          for (;;) {
            if (
              requestEpoch !== storeEpoch ||
              !sameRuntimeFence(get(), requestRuntimeFence) ||
              isClosedAtState(threadId, get()) ||
              get().threads[threadId] === undefined
            )
              throw new Error("The Thread content request is stale.");
            if (!isCurrent()) {
              const current = get().itemContentByThread[threadId]?.[itemId];
              publish({
                status: "idle",
                text: current?.text ?? "",
                offsetCharacters: current?.offsetCharacters ?? 0,
                ...(current?.totalCharacters === undefined
                  ? {}
                  : { totalCharacters: current.totalCharacters }),
              });
              throw new Error("The Thread is no longer visible.");
            }
            const current = get().itemContentByThread[threadId]?.[itemId];
            const offsetCharacters = current?.offsetCharacters ?? 0;
            const page = await readPage({
              threadId,
              itemId,
              offsetCharacters,
              limitCharacters: ITEM_CONTENT_PAGE_SIZE,
            });
            const pageCharacters = codePointLength(page.content);
            const nextOffset = offsetCharacters + pageCharacters;
            const pageIsValid =
              page.itemId === itemId &&
              page.offsetCharacters === offsetCharacters &&
              (current?.totalCharacters === undefined ||
                page.totalCharacters === current.totalCharacters) &&
              Number.isSafeInteger(page.totalCharacters) &&
              page.totalCharacters >= nextOffset &&
              page.truncated === (page.nextOffsetCharacters !== null) &&
              (page.truncated
                ? page.nextOffsetCharacters === nextOffset && nextOffset > offsetCharacters
                : page.nextOffsetCharacters === null && nextOffset === page.totalCharacters);
            if (!pageIsValid)
              throw new Error("Item content page identity or offset is inconsistent.");

            const text = (current?.text ?? "") + page.content;
            const complete: TimelineItemContentHydration = {
              status: page.truncated ? "loading" : "complete",
              text,
              offsetCharacters: nextOffset,
              totalCharacters: page.totalCharacters,
            };
            publish(complete);
            if (!page.truncated) return text;
          }
        } catch (failure) {
          const current = get().itemContentByThread[threadId]?.[itemId];
          if (current?.status === "loading") publish({ ...current, status: "error" });
          throw failure;
        }
      };
      const request = Promise.resolve()
        .then(read)
        .finally(() => {
          if (itemContentReads.get(requestKey) === request) itemContentReads.delete(requestKey);
        });
      itemContentReads.set(requestKey, request);
      return request;
    },
    hydrateAnswerContent: (threadId, finalItemId, readAnswer, isCurrent = () => true) => {
      const requestState = get();
      const requestRuntimeFence =
        requestState.handshake.phase === "ready" && requestState.serverInstanceId !== undefined
          ? {
              serverInstanceId: requestState.serverInstanceId,
              generation: requestState.handshake.generation,
            }
          : undefined;
      const owner = requestState.itemThreadById[finalItemId];
      const prior = requestState.answerContentByThread[threadId]?.[finalItemId];
      if (isClosedAtState(threadId, requestState))
        return Promise.reject(new Error("The Thread has been closed."));
      if (owner !== undefined && owner !== threadId)
        return Promise.reject(new Error("Final Item does not belong to the requested Thread."));
      if (prior?.status === "complete") return Promise.resolve(prior.text);
      if (!isCurrent()) return Promise.reject(new Error("The Thread is no longer visible."));

      const requestKey = `${threadId}\u0000${finalItemId}`;
      const pending = answerContentReads.get(requestKey);
      if (pending !== undefined) return pending;
      const requestEpoch = storeEpoch;
      const publish = (hydration: TimelineItemContentHydration): void => {
        set((state) => {
          if (
            requestEpoch !== storeEpoch ||
            !sameRuntimeFence(state, requestRuntimeFence) ||
            isClosedAtState(threadId, state) ||
            state.threads[threadId] === undefined ||
            (state.itemThreadById[finalItemId] !== undefined &&
              state.itemThreadById[finalItemId] !== threadId)
          )
            return state;
          return {
            answerContentByThread: {
              ...state.answerContentByThread,
              [threadId]: {
                ...(state.answerContentByThread[threadId] ?? {}),
                [finalItemId]: hydration,
              },
            },
          };
        });
      };
      const read = async (): Promise<string> => {
        const initial = get().answerContentByThread[threadId]?.[finalItemId];
        publish({
          status: "loading",
          text: initial?.text ?? "",
          offsetCharacters: initial?.offsetCharacters ?? 0,
          ...(initial?.totalCharacters === undefined
            ? {}
            : { totalCharacters: initial.totalCharacters }),
        });
        try {
          const text = await readAnswer();
          if (
            requestEpoch !== storeEpoch ||
            !sameRuntimeFence(get(), requestRuntimeFence) ||
            isClosedAtState(threadId, get()) ||
            get().threads[threadId] === undefined
          )
            throw new Error("The Thread answer request is stale.");
          if (!isCurrent()) {
            publish({
              status: "idle",
              text: initial?.text ?? "",
              offsetCharacters: initial?.offsetCharacters ?? 0,
              ...(initial?.totalCharacters === undefined
                ? {}
                : { totalCharacters: initial.totalCharacters }),
            });
            throw new Error("The Thread is no longer visible.");
          }
          const characterCount = codePointLength(text);
          const complete: TimelineItemContentHydration = {
            status: "complete",
            text,
            offsetCharacters: characterCount,
            totalCharacters: characterCount,
          };
          publish(complete);
          return text;
        } catch (failure) {
          const current = get().answerContentByThread[threadId]?.[finalItemId];
          if (current?.status === "loading") publish({ ...current, status: "error" });
          throw failure;
        }
      };
      const request = Promise.resolve()
        .then(read)
        .finally(() => {
          if (answerContentReads.get(requestKey) === request) answerContentReads.delete(requestKey);
        });
      answerContentReads.set(requestKey, request);
      return request;
    },
    applySnapshot: (snapshot, workspaceId, options) => {
      let nextOutcome: TimelineState["lastOutcome"] = "invalid";
      set((state) => {
        if (isClosedAtState(snapshot.threadId, state)) {
          nextOutcome = "rejected";
          return { ...state, lastOutcome: nextOutcome };
        }
        const next = applySnapshot(state, snapshot, workspaceId, options);
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
    /** 事件只推进已有 root slice；同一 Activity 重投为幂等，迟到 revision 不覆盖新事实。 @author kongweiguang */
    applyTaskActivity: (input) => {
      let nextOutcome: TimelineState["lastOutcome"] = "rejected";
      set((state) => {
        const references = [
          input.activity.rootThreadId,
          input.activity.taskThreadId,
          input.activity.actorThreadId,
          input.task.taskThreadId,
          input.task.parentThreadId,
          input.task.rootThreadId,
        ];
        if (references.some((threadId) => isClosedAtState(threadId, state))) {
          nextOutcome = "rejected";
          return { ...state, lastOutcome: nextOutcome };
        }
        const next = reduceTaskActivity(state, input);
        nextOutcome = next.lastOutcome;
        return next;
      });
      return nextOutcome ?? "rejected";
    },
    /**
     * 只接纳类型化 Host Event，并把隔离的原生 Frame 转换为权威 Snapshot 要求；Malformed Payload
     * 不得泄漏到 Store，也不能让 Active Turn 永久停留在 Busy。
     */
    applyHostEvent: (event) => {
      const threadId = hostEventThreadId(event);
      if (threadId !== undefined && isClosedAtState(threadId, get())) return "rejected";
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
          // 恢复时保留一份重放副本，同时立即应用实时事件。终态和已提交事实会在权威读取期间继续显示。
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
              : event.kind === "resync_required"
                ? applyProjectionResync(state, event.event)
                : requireActiveTurnResync(state, "projection_fault");
        nextOutcome = next.lastOutcome;
        if (event.kind === "resync_required" && nextOutcome === "resync_required") {
          const threadId = event.event.threadId;
          return {
            ...next,
            resyncRequestSequenceByThread: {
              ...(state.resyncRequestSequenceByThread ?? {}),
              [threadId]: ((state.resyncRequestSequenceByThread ?? {})[threadId] ?? 0) + 1,
            },
          };
        }
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
        if (
          event.kind === "status" &&
          (next.handshake.generation !== state.handshake.generation ||
            next.handshake.serverInstanceId !== state.handshake.serverInstanceId)
        ) {
          recoveryBuffers.clear();
          lastAcceptedLiveByThread.clear();
          syncClosedRuntimeFence(next);
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
        if (isClosedAtState(accepted.threadId, state)) {
          nextOutcome = "rejected";
          return { ...state, lastOutcome: nextOutcome };
        }
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
        if (closedTurnIds.has(inputQueue.turnId)) {
          nextOutcome = "rejected";
          return { ...state, lastOutcome: nextOutcome };
        }
        const owner = state.turns[inputQueue.turnId]?.threadId;
        if (owner !== undefined && isClosedAtState(owner, state)) {
          nextOutcome = "rejected";
          return { ...state, lastOutcome: nextOutcome };
        }
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
        if (
          next.handshake.generation !== state.handshake.generation ||
          next.handshake.serverInstanceId !== state.handshake.serverInstanceId
        ) {
          recoveryBuffers.clear();
          lastAcceptedLiveByThread.clear();
          syncClosedRuntimeFence(next);
          return {
            ...next,
            recoveredActiveTurnByThread: {},
            resyncRequestSequenceByThread: {},
          };
        }
        syncClosedRuntimeFence(next);
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
        if (isClosedAtState(threadId, state)) return state;
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
      set((state) => {
        if (isClosedAtState(threadId, state)) return state;
        return {
          ...markThreadResync(state, threadId),
          resyncRequestSequenceByThread: {
            ...(state.resyncRequestSequenceByThread ?? {}),
            [threadId]: ((state.resyncRequestSequenceByThread ?? {})[threadId] ?? 0) + 1,
          },
        };
      }),
    /** 开始一次有界恢复窗口；窗口内 live event 暂存，避免快照提交时产生半帧 Gap。 */
    beginRecovery: (threadId, requestEpoch, mode = "recovery") => {
      if (isClosedAtState(threadId, get())) return undefined;
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
     * 或 replay gap 都只返回需重读结果，不把队列中的残缺正文发布到 UI。Task Activity 请求围栏只约束迟到空切片。
     * @author kongweiguang
     */
    endRecovery: (token, snapshot, workspaceId, options) => {
      if (isClosedAtState(token.threadId, get())) {
        recoveryBuffers.delete(token.threadId);
        return { status: "stale", replayedEvents: 0 };
      }
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
        let next = applySnapshot(state, snapshot, workspaceId, {
          mode: recovery.mode,
          ...options,
        });
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
        const threadTurns = (next.turnIdsByThread[token.threadId] ?? EMPTY_THREAD_IDS)
          .map((turnId) => next.turns[turnId])
          .filter((turn): turn is TimelineTurn => turn !== undefined);
        const hasActiveTurn = threadTurns.some(
          (turn) => !["completed", "failed", "cancelled"].includes(turn.status),
        );
        const recoveredActiveTurnByThread = { ...(state.recoveredActiveTurnByThread ?? {}) };
        if (hasActiveTurn) {
          const activeTurn = selectRecoveredActiveTurnId(threadTurns, snapshot.liveStream?.turnId);
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
      if (isClosedAtState(token.threadId, get())) return;
      const current = recoveryBuffers.get(token.threadId);
      if (current?.requestEpoch === token.requestEpoch) {
        recoveryBuffers.delete(token.threadId);
        set((state) => markThreadResync(state, token.threadId));
      }
    },
    /** 供 Controller 静默监测读取，不建立 Zustand subscription，也不把 delta 扩散到 Shell。 */
    getLastAcceptedLive: (threadId) =>
      isClosedAtState(threadId, get()) ? undefined : lastAcceptedLiveByThread.get(threadId),
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
          itemContentByThread: Object.fromEntries(
            Object.entries(state.itemContentByThread).filter(
              ([threadId]) => !removed.has(threadId),
            ),
          ),
          answerContentByThread: Object.fromEntries(
            Object.entries(state.answerContentByThread).filter(
              ([threadId]) => !removed.has(threadId),
            ),
          ),
        };
      });
    },
    /** 仅匹配当前 ready runtime 的权威 close 可删除内容；先立 ID tombstone，再发布一次 Store 清理。@author kongweiguang */
    closeThreads: (threadIds, fence) => {
      const uniqueIds = [...new Set(threadIds.filter((threadId) => threadId.length > 0))];
      if (uniqueIds.length === 0) return false;
      const current = get();
      syncClosedRuntimeFence(current);
      if (
        current.handshake.phase !== "ready" ||
        current.serverInstanceId !== fence.serverInstanceId ||
        current.handshake.generation !== fence.generation ||
        closedRuntimeFence?.serverInstanceId !== fence.serverInstanceId ||
        closedRuntimeFence.generation !== fence.generation
      )
        return false;
      if (uniqueIds.every((threadId) => closedThreadIds.has(threadId))) return true;
      const closingThreadIds = new Set(uniqueIds);
      for (const threadId of uniqueIds)
        for (const turnId of current.turnIdsByThread[threadId] ?? []) closedTurnIds.add(turnId);
      for (const [turnId, turn] of Object.entries(current.turns))
        if (closingThreadIds.has(turn.threadId)) closedTurnIds.add(turnId);
      for (const threadId of uniqueIds) closedThreadIds.add(threadId);
      clearClosedThreadExtensions(uniqueIds);
      set((state) => {
        const next = removeThreadProjection(state, uniqueIds);
        const removed = new Set(uniqueIds);
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
          itemContentByThread: Object.fromEntries(
            Object.entries(state.itemContentByThread ?? {}).filter(
              ([threadId]) => !removed.has(threadId),
            ),
          ),
          answerContentByThread: Object.fromEntries(
            Object.entries(state.answerContentByThread ?? {}).filter(
              ([threadId]) => !removed.has(threadId),
            ),
          ),
        };
      });
      return true;
    },
    /** 查询当前 runtime tombstone，供异步 Controller 在完成时阻止旧 Thread 副作用复活。@author kongweiguang */
    isThreadClosed: (threadId) => isClosedAtState(threadId, get()),
    reset: () => {
      storeEpoch += 1;
      recoveryBuffers.clear();
      lastAcceptedLiveByThread.clear();
      itemContentReads.clear();
      answerContentReads.clear();
      set({
        ...createTimelineState(),
        recoveredActiveTurnByThread: {},
        resyncRequestSequenceByThread: {},
        itemContentByThread: {},
        answerContentByThread: {},
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

/** 只选权威 Thread 顺序并附加分页正文；稳定 Item 引用供 useShallow 在重复快照间复用。 */
export const selectCommittedItemsForThread = (threadId: string) => (state: TimelineStore) =>
  (state.itemIdsByThread[threadId] ?? [])
    .map((itemId) => state.items[itemId])
    .filter((item) => item !== undefined)
    .map((item) => {
      const bodyHydration = state.itemContentByThread[threadId]?.[item.itemId];
      const answerHydration = state.answerContentByThread[threadId]?.[item.itemId];
      return projectCommittedItem(item, bodyHydration, answerHydration);
    });

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
  const threadTurnIds = state.turnIdsByThread[threadId] ?? EMPTY_THREAD_IDS;
  const drafts = threadTurnIds.flatMap((turnId) => {
    const turnDrafts = state.draftByTurn[turnId];
    if (turnDrafts === undefined) return [];
    return turnDrafts
      .filter((draft) => draft.text.trim() !== "")
      .map((draft) => draftItemForTurn(threadId, turnId, draft));
  });
  const retryStatuses = threadTurnIds.flatMap((turnId) => {
    const retry = state.retryingByTurn[turnId];
    const turn = state.turns[turnId];
    if (retry === undefined || turn?.status !== "running") return [];
    return [retryStatusItemForTurn(threadId, turnId, retry)];
  });
  return interleaveDraftsWithCommittedItems(committed, [...drafts, ...retryStatuses]);
};

/** @author kongweiguang
 * Turn 只存一份实体并通过 owner 索引隔离会话；按当前 Thread 读取，避免订阅者扫描全局 Turn 表。
 */
export const selectTurnsForThread = (threadId: string) => (state: TimelineStore) =>
  (state.turnIdsByThread[threadId] ?? EMPTY_THREAD_IDS)
    .map((turnId) => state.turns[turnId])
    .filter((turn): turn is TimelineTurn => turn !== undefined);

/** 主 Timeline 只读取当前 root 的持久活动切片；稳定空数组避免隐藏视图产生无效重渲染。 */
export const selectTaskActivitiesForRoot = (rootThreadId: string) => (state: TimelineStore) =>
  state.taskActivitiesByRootThread[rootThreadId] ?? EMPTY_TASK_ACTIVITIES;

/** @author kongweiguang
 * Approval 终态墓碑保留在唯一实体表中，但投影只遍历所属 Thread 索引，避免会话间数据串读。
 */
export const selectApprovalsForThread = (threadId: string) => (state: TimelineStore) =>
  (state.approvalIdsByThread[threadId] ?? EMPTY_THREAD_IDS)
    .map((approvalId) => state.approvalsById[approvalId]?.approval)
    .filter((approval): approval is TimelineApproval => approval !== undefined);

/** @author kongweiguang
 * 决策从同一 Thread 的 Approval 切片派生，不另建全局映射或复制另一份可变状态。
 */
export const selectApprovalDecisionsForThread = (threadId: string) => (state: TimelineStore) => {
  const decisions: Record<string, ApprovalDecision> = {};
  for (const approvalId of state.approvalIdsByThread[threadId] ?? EMPTY_THREAD_IDS) {
    const decision = state.approvalsById[approvalId]?.decision;
    if (decision !== undefined) decisions[approvalId] = decision;
  }
  return decisions;
};

/** @author kongweiguang
 * 关闭时间与用户决策是不同事实，但都限定在 owner Thread 索引内，避免全局归约与身份混淆。
 */
export const selectApprovalClosedAtForThread = (threadId: string) => (state: TimelineStore) => {
  const closedAt: Record<string, string> = {};
  for (const approvalId of state.approvalIdsByThread[threadId] ?? EMPTY_THREAD_IDS) {
    const value = state.approvalsById[approvalId]?.closedAt;
    if (value !== undefined) closedAt[approvalId] = value;
  }
  return closedAt;
};
