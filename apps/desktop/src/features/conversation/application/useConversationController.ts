// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { WorkspaceProjection } from "@/features/workspace";
import { useTimelineStore, type TimelineRecoveryToken } from "./timelineStore";
import type {
  ConversationCompactionResult,
  ConversationHistoryPort,
  ConversationThreadForkInput,
  ConversationThreadForkOutcome,
  ConversationThreadForkRecovery,
  ConversationThreadForkResult,
  ConversationRuntimeState,
  ConversationThread,
  ConversationModelSelection,
  ConversationAccessMode,
  ConversationCollaborationMode,
  ReasoningLevel,
} from "./ports";
import type {
  TimelineEvent,
  TimelineSnapshot,
  TimelineTaskActivityEntry,
} from "../domain/timelineContracts";
import { readFullAnswerContent } from "./readFullMessageContent";
import { createOperationKey } from "@/shared/operationKey";

const MAX_RECENT_THREADS = 100;
const MAX_WORKSPACE_HISTORY_CACHES = 8;
const THREAD_HISTORY_PAGE_SIZE = 200;
const AUTOMATIC_RESYNC_INITIAL_DELAY_MS = 500;
const AUTOMATIC_RESYNC_MAX_DELAY_MS = 5_000;

/** 项目按 id 隔离目录，所有 session thread 共用一个服务端 kind-filtered 列表 scope。 */
function catalogScopeKey(workspace: WorkspaceProjection | undefined): string {
  return workspace?.kind === "project" ? workspace.workspaceId : "session";
}

/** 激活意图绑定当前已就绪的 generation 与 server instance，不能用可能滞后的目录effect记账代替。 @author kongweiguang */
function runtimeAdmissionKey(
  runtimeState: ConversationRuntimeState | undefined,
): string | undefined {
  return runtimeState !== undefined && ["ready", "busy"].includes(runtimeState.status)
    ? `${runtimeState.generation}:${runtimeState.serverInstanceId ?? ""}`
    : undefined;
}

/** thread/read fence 绑定 runtime 身份，并在实际请求发出时冻结 owner 的不可变 Task Activity 切片引用。 */
interface ThreadSnapshotReadFence {
  workspaceId: string;
  generation: number;
  serverInstanceId: string;
  taskActivityReadFence?: {
    threadId: string;
    entries: readonly TimelineTaskActivityEntry[] | undefined;
  };
}

/** 后台恢复诊断必须绑定当前可见 scope，避免旧 Thread 的失败提示穿透切换后的会话。 */
interface BackgroundRecoveryError {
  scope: string;
  message: string;
}

/** 首屏只保留最近一页；旧页由用户显式请求，cursor 不进入 Timeline reducer 的完整窗口合同。 */
interface ThreadHistoryWindow {
  threadId: string;
  workspaceId: string;
  snapshot: TimelineSnapshot;
  olderCursor: string | null;
  seenCursors: Set<string>;
}

/** 观察订阅按 history 端口与 runtime 代际分 owner，迟到的 cleanup 不可释放新 owner 的订阅。 */
interface ThreadObservationOwner {
  history: ConversationHistoryPort;
  observedThreadId: string | undefined;
  ownedThreadIds: Set<string>;
}

interface ThreadSnapshotPage {
  snapshot: TimelineSnapshot;
  olderCursor: string | null;
  readFence: ThreadSnapshotReadFence | undefined;
}

interface ForkReplyIdentity {
  threadId: string;
  itemId: string;
}

interface ForkReplyAttempt {
  input: ConversationThreadForkInput;
  result?: ConversationThreadForkResult;
}

interface ForkReplyViewState extends ForkReplyIdentity {
  state: "pending" | "created" | "open_failed" | "conflict" | "unconfirmed";
}

/** JSON tuple keys preserve arbitrary opaque IDs without delimiter collisions. */
function forkReplyKey(identity: ForkReplyIdentity): string {
  return JSON.stringify([identity.threadId, identity.itemId]);
}

/** A receipt is usable only when its source echo matches the exact frozen request. */
function forkReceiptMatches(
  input: ConversationThreadForkInput,
  result: ConversationThreadForkResult,
): boolean {
  return (
    result.source.threadId === input.sourceThreadId &&
    result.source.itemId === input.sourceItemId &&
    result.source.threadRevision === input.expectedThreadRevision &&
    result.thread.threadId !== input.sourceThreadId &&
    result.thread.status === "active"
  );
}

/**
 * 目录响应只在 revision 严格前进时覆盖已有行；同 revision 的迟到响应视为重复取样，保留
 * 当前对象，避免旧标题、seen 或 preferences 在 ACK 后倒退。该 helper 只合并单行事件，
 * 不改变服务端列表的排序与 workspace 过滤边界。
 */
function mergeThreadProjection(
  current: readonly ConversationThread[],
  incoming: ConversationThread,
): ConversationThread[] {
  const index = current.findIndex((thread) => thread.threadId === incoming.threadId);
  if (index < 0) return [...current, incoming];
  const existing = current[index];
  if (existing === undefined || incoming.revision <= existing.revision) return [...current];
  return current.map((thread, threadIndex) => (threadIndex === index ? incoming : thread));
}

/** 自动恢复只使用 0.5s/1s/2s/5s 阶梯，避免连续失败把 UI 变成不可预测的轮询。 */
function nextAutomaticResyncDelay(delayMs: number): number {
  if (delayMs <= AUTOMATIC_RESYNC_INITIAL_DELAY_MS) return 1_000;
  if (delayMs <= 1_000) return 2_000;
  return AUTOMATIC_RESYNC_MAX_DELAY_MS;
}

/** 只有明确缺失 baseline 的 gap/terminal 情况才开启 recovery buffer；健康对账保留 live Draft。 */
function requiresSnapshotBaseline(reason: string): boolean {
  return new Set([
    "gap",
    "terminal_missing",
    "projection_fault",
    "snapshot_invalid",
    "server_instance_changed",
    "handshake_required",
    "handshake_failed",
  ]).has(reason);
}

interface WorkspaceHistoryCache {
  threads: ConversationThread[];
  currentThreadId: string | undefined;
  admission: string;
  lastUsed: number;
}

interface ConversationControllerOptions {
  history: ConversationHistoryPort;
  workspace: WorkspaceProjection | undefined;
  workspaceRevision: number;
  modelSelection: ConversationModelSelection | undefined;
  accessMode: "approval_required" | "full_access";
  runtimeState: ConversationRuntimeState | undefined;
  metadataEvent?: TimelineEvent;
  activateWorkspace(workspaceId: string): Promise<WorkspaceProjection | undefined>;
}

type ConversationControllerErrorState =
  | { source: "operation"; message: string }
  | {
      source: "history-load";
      message: string;
      scopeKey: string;
      workspaceId: string | undefined;
      workspaceRevision: number;
      runtimeAdmission: string;
      threadId?: string;
    };

/** Conversation controller 只公开 Thread 目录、当前选择和会话用例。 */
export interface ConversationController {
  threads: ConversationThread[];
  currentThreadId: string | undefined;
  hasOlderHistory: boolean;
  loadingOlderHistory: boolean;
  olderHistoryError: string | undefined;
  busy: boolean;
  error: { source: "operation" | "history-load"; message: string } | undefined;
  /** 后台恢复诊断独立于前台加载错误，避免健康对账抢占新建/Composer 的用户反馈。 */
  backgroundError: string | undefined;
  mutatingThreadIds: readonly string[];
  forkingReplies: readonly ForkReplyIdentity[];
  forkCreatedReplies: readonly ForkReplyIdentity[];
  forkOpenFailures: readonly ForkReplyIdentity[];
  forkConflictReplies: readonly ForkReplyIdentity[];
  forkUnconfirmedReplies: readonly ForkReplyIdentity[];
  compaction: ConversationCompactionView;
  canCompact: boolean;
  /** 项目菜单传稳定 workspaceId，创建不依赖尚未提交到 React 的 workspace props。 */
  create(options?: { workspaceId?: string }): Promise<void>;
  retryHistory(): Promise<void>;
  /** 以已结算答复 Item 为锚点，冻结来源 revision 与 operation key 后可安全重试。 */
  forkReply(anchor: ForkReplyIdentity): Promise<ConversationThreadForkOutcome>;
  select(threadId: string): Promise<void>;
  search(query: string): Promise<ConversationThread[]>;
  /** 完整重读当前 Thread，并把同 revision 权威快照收敛到 Timeline 后返回 CAS revision。 */
  readThreadRevision(threadId: string): Promise<number>;
  readMessageContent(threadId: string, itemId: string): Promise<string>;
  readAnswerContent(
    finalMessageId: string,
    minimumRevision?: number,
    turnId?: string,
  ): Promise<string>;
  loadOlderHistory(): Promise<void>;
  rename(threadId: string, title: string): Promise<void>;
  pin(threadId: string, pinned: boolean): Promise<void>;
  archive(threadId: string): Promise<ConversationArchiveUndo | undefined>;
  restore(threadId: string, open: boolean): Promise<void>;
  updatePreferences(input: {
    providerId: string;
    modelId: string;
    reasoningLevel: ReasoningLevel | null;
    accessMode: ConversationAccessMode;
    collaborationMode: ConversationCollaborationMode;
  }): Promise<void>;
  compact(): Promise<void>;
  cancelCompaction(): Promise<void>;
  dismissCompactionFeedback(): void;
}

/** Undo 保存服务端归档后的 revision 与原选择事实，不缓存旧列表快照冒充恢复结果。 */
export interface ConversationArchiveUndo {
  archived: ConversationThread;
  restoreSelection: boolean;
}

/** 手动和自动生命周期共用同一只读反馈，避免菜单维护第二套压缩状态机。 */
export interface ConversationCompactionView {
  phase: "idle" | "running" | "success" | "error";
  message?: string;
  retryable: boolean;
  cancellable?: boolean;
}

const COMPACTION_ERRORS: Readonly<Record<string, { message: string; retryable: boolean }>> = {
  THREAD_NOT_FOUND: { message: "对话不存在或已删除。", retryable: false },
  CONFLICT: { message: "对话状态已更新，请重试压缩。", retryable: true },
  THREAD_BUSY: { message: "对话正在执行，结束后可再次压缩。", retryable: true },
  SUMMARY_FAILURE: { message: "上下文摘要生成失败，请重试。", retryable: true },
  CONTEXT_LIMIT: { message: "当前上下文无法安全压缩到模型窗口内。", retryable: false },
  CANCELLED: { message: "上下文压缩已取消。", retryable: true },
  INVALID_STATE: { message: "对话上下文状态异常，请重新打开会话。", retryable: false },
};

/** 将原生错误或 failed event 收敛为稳定用户反馈，不把内部 message、路径或 Provider 正文带入状态。 */
function compactionError(error: unknown): { message: string; retryable: boolean } {
  const candidate =
    error !== null && typeof error === "object" ? (error as Record<string, unknown>) : undefined;
  const code = typeof candidate?.["code"] === "string" ? candidate["code"] : undefined;
  return (
    (code === undefined ? undefined : COMPACTION_ERRORS[code]) ?? {
      message: "上下文压缩暂时失败，请重试。",
      retryable: true,
    }
  );
}

/** 只读取稳定公开错误码，任何其它原生字段都不进入会话状态。 */
function compactionErrorCode(error: unknown): string | undefined {
  if (error === null || typeof error !== "object") return undefined;
  const code = (error as Record<string, unknown>)["code"];
  return typeof code === "string" ? code : undefined;
}

/** 只格式化 Provider 官方计量结果，缺失值绝不由字符数估算。 */
function compactedMessage(
  result: Pick<ConversationCompactionResult, "inputTokensBefore" | "inputTokensAfter">,
): string {
  return `上下文已压缩：${result.inputTokensBefore.toLocaleString("zh-CN")} → ${result.inputTokensAfter.toLocaleString("zh-CN")} Token。`;
}

/**
 * 最近对话严格属于当前 workspace；侧栏没有展示每行的 scope，保留其它 workspace 的同名
 * Thread 会让用户误点后发生隐式项目切换，因此切换范围时必须整体替换目录投影。
 */
function workspaceThreads(
  scopeKey: string,
  scoped: readonly ConversationThread[],
): ConversationThread[] {
  const seen = new Set<string>();
  const merged: ConversationThread[] = [];
  for (const thread of scoped) {
    if (
      (scopeKey === "session"
        ? thread.workspaceKind !== "session"
        : thread.workspaceId !== scopeKey) ||
      thread.status === "archived" ||
      seen.has(thread.threadId)
    )
      continue;
    seen.add(thread.threadId);
    merged.push(thread);
    if (merged.length === MAX_RECENT_THREADS) break;
  }
  return merged;
}

/**
 * 权威列表只覆盖请求发出时已存在的目录事实；若 Thread 在该请求之后已由 create ACK
 * 持久化，则保留它直到一个不早于创建 epoch 的列表确认，避免迟到刷新删除当前会话。
 */
function mergeThreadListResponse(
  current: ConversationThread[],
  scopeKey: string,
  scoped: ConversationThread[],
  issuedEpoch: number,
  localCreationEpochs: ReadonlyMap<string, { scopeKey: string; epoch: number }>,
): ConversationThread[] {
  const currentById = new Map(
    current
      .filter(
        (thread) =>
          (scopeKey === "session"
            ? thread.workspaceKind === "session"
            : thread.workspaceId === scopeKey) && thread.status === "active",
      )
      .map((thread) => [thread.threadId, thread]),
  );
  const merged: ConversationThread[] = [];
  for (const incoming of scoped) {
    if (
      (scopeKey === "session"
        ? incoming.workspaceKind !== "session"
        : incoming.workspaceId !== scopeKey) ||
      incoming.status !== "active"
    )
      continue;
    const existing = currentById.get(incoming.threadId);
    // 同 revision 可能只是迟到的 list/page 响应；只接受严格前进，避免元数据倒退。
    merged.push(
      existing !== undefined && existing.revision >= incoming.revision ? existing : incoming,
    );
    currentById.delete(incoming.threadId);
  }
  for (const existing of currentById.values()) {
    // 只有请求发出后由 create ACK 产生的行可暂时脱离 list；归档/删除等权威目录结果仍可移除旧行。
    const creation = localCreationEpochs.get(existing.threadId);
    if (
      existing.status === "active" &&
      creation?.scopeKey === scopeKey &&
      creation.epoch > issuedEpoch
    )
      merged.unshift(existing);
  }
  return workspaceThreads(scopeKey, merged);
}

/**
 * 搜索命中只补充目录中尚未出现的 Thread，不改变最近会话既有排序；这样 Command Dialog
 * 可以打开较旧结果，同时一次查询不会让侧栏历史突然重排。
 */
function mergeSearchThreads(
  current: ConversationThread[],
  scopeKey: string,
  matches: readonly ConversationThread[],
): ConversationThread[] {
  const byId = new Map(matches.map((thread) => [thread.threadId, thread]));
  const merged = current.map((thread) => {
    const match = byId.get(thread.threadId);
    return match !== undefined && match.revision > thread.revision ? match : thread;
  });
  const existing = new Set(merged.map((thread) => thread.threadId));
  for (const thread of matches) {
    if (
      (scopeKey === "session"
        ? thread.workspaceKind !== "session"
        : thread.workspaceId !== scopeKey) ||
      thread.status !== "active" ||
      existing.has(thread.threadId)
    )
      continue;
    merged.push(thread);
    existing.add(thread.threadId);
  }
  return merged.slice(0, MAX_RECENT_THREADS);
}

/**
 * 同一 runtime generation 且无 resync 标记时复用 live timeline；snapshot 不包含私有
 * approval 请求，盲目重读会丢失仍可操作的审批卡片。
 */
function canReuseLoadedThread(threadId: string, workspaceId: string): boolean {
  const state = useTimelineStore.getState();
  const runtime = state.runtime;
  return (
    state.handshake.phase === "ready" &&
    state.handshake.generation > 0 &&
    state.serverInstanceId !== undefined &&
    runtime !== undefined &&
    (runtime.status === "ready" || runtime.status === "busy") &&
    state.threads[threadId]?.workspaceId === workspaceId &&
    state.resyncRequired[threadId] === undefined
  );
}

/**
 * “新对话”以权威 Timeline 是否已有 Turn 或持久条目判定，而不依赖可修改的标题；resync
 * 状态不视为空，避免用不完整的本地投影阻止用户离开异常会话。
 */
function isEmptyLoadedThread(threadId: string): boolean {
  const state = useTimelineStore.getState();
  return (
    state.threads[threadId] !== undefined &&
    state.resyncRequired[threadId] === undefined &&
    (state.turnIdsByThread[threadId]?.length ?? 0) === 0 &&
    (state.itemIdsByThread[threadId]?.length ?? 0) === 0
  );
}

/**
 * 独占 Thread catalog、当前选择和 timeline snapshot 恢复；workspace/v3 默认模型仅作为输入，
 * 跨项目选择通过窄 activateWorkspace port 请求，不复制 native capability 状态。
 * @author kongweiguang
 */
export function useConversationController({
  history,
  workspace,
  workspaceRevision,
  modelSelection,
  accessMode,
  runtimeState,
  metadataEvent,
  activateWorkspace,
}: ConversationControllerOptions): ConversationController {
  const [threads, setThreads] = useState<ConversationThread[]>([]);
  const [currentThreadId, setCurrentThreadId] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setErrorState] = useState<ConversationControllerErrorState>();
  const errorStateRef = useRef<ConversationControllerErrorState | undefined>(undefined);
  /** 错误来源与 ref 同步写入，通知动作可在 React 重渲染前拒绝过期点击。@author kongweiguang */
  const publishError = useCallback((next: ConversationControllerErrorState | undefined): void => {
    errorStateRef.current = next;
    setErrorState(next);
  }, []);
  /** 普通操作错误不具备历史读取重试权限；空值统一清除同一权威错误事实。@author kongweiguang */
  const setError = useCallback(
    (message?: string): void => {
      publishError(message === undefined ? undefined : { source: "operation", message });
    },
    [publishError],
  );
  /** 只有带请求 owner 的目录/快照读取失败才允许从当前会话范围重读。@author kongweiguang */
  const setHistoryLoadError = useCallback(
    (failure: Extract<ConversationControllerErrorState, { source: "history-load" }>): void => {
      publishError(failure);
    },
    [publishError],
  );
  const [backgroundError, setBackgroundError] = useState<BackgroundRecoveryError>();
  const [hasOlderHistory, setHasOlderHistory] = useState(false);
  const [loadingOlderHistory, setLoadingOlderHistory] = useState(false);
  const [olderHistoryError, setOlderHistoryError] = useState<string>();
  const [mutatingThreadIds, setMutatingThreadIds] = useState<readonly string[]>([]);
  const [forkReplyView, setForkReplyView] = useState<readonly ForkReplyViewState[]>([]);
  const [compaction, setCompaction] = useState<ConversationCompactionView>({
    phase: "idle",
    retryable: false,
  });
  const mountedRef = useRef(false);
  // 前台加载/选择/创建的 fence 与后台恢复完全分离；后台 read 不能令前台 finally 失效或清除 busy。
  const foregroundRequestRef = useRef(0);
  const compactionRequestRef = useRef(new Map<string, number>());
  const compactionInFlightRef = useRef(new Set<string>());
  const forkAttemptsRef = useRef(new Map<string, ForkReplyAttempt>());
  const forkInFlightRef = useRef(new Map<string, Promise<ConversationThreadForkOutcome>>());
  const creationInFlightRef = useRef<Promise<void> | undefined>(undefined);
  const directoryMutationEpochRef = useRef(0);
  const localCreationEpochsRef = useRef(new Map<string, { scopeKey: string; epoch: number }>());
  const threadMutationGuardsRef = useRef(new Set<string>());
  const workspaceHistoryCacheRef = useRef(new Map<string, WorkspaceHistoryCache>());
  const workspaceHistoryCacheClockRef = useRef(0);
  const workspaceRef = useRef(workspace);
  const historyWorkspaceIdRef = useRef<string | undefined>(undefined);
  const historyScopeKeyRef = useRef<string | undefined>(undefined);
  const historyWorkspaceRevisionRef = useRef<number | undefined>(undefined);
  const historyModelAvailableRef = useRef(false);
  const historyRuntimeAdmissionRef = useRef<string | undefined>(undefined);
  // 与现有 pending workspace intent 配对；目录effect可能暂缓记账，但新 Runtime 仍不能继承旧 intent。
  const pendingWorkspaceActivationAdmissionRef = useRef<string | undefined>(undefined);
  const historyObservationOwnerRef = useRef<ThreadObservationOwner | undefined>(undefined);
  const modelSelectionRef = useRef(modelSelection);
  const accessModeRef = useRef(accessMode);
  const runtimeStateRef = useRef(runtimeState);
  const automaticResyncAttemptRef = useRef<string | undefined>(undefined);
  const automaticResyncInFlightRef = useRef(new Set<string>());
  const automaticResyncRetryTimersRef = useRef(new Map<string, ReturnType<typeof setTimeout>>());
  const automaticResyncRetryDelayRef = useRef(new Map<string, number>());
  const automaticResyncRequestRef = useRef(0);
  const automaticResyncRecoveryTokenRef = useRef(new Map<string, TimelineRecoveryToken>());
  const automaticResyncActiveRequestRef = useRef(new Map<string, number>());
  const automaticResyncScopeRef = useRef<string | undefined>(undefined);
  const manualWorkspaceTargetRef = useRef<string | undefined>(undefined);
  // React 可能在原生调用和快照读取完成后才提交已激活的 Workspace；视图切换意图需独立于请求栅栏保留，
  // 直到匹配的布局副作用将其消费。
  const pendingWorkspaceActivationRef = useRef<string | undefined>(undefined);
  const pendingThreadActivationRef = useRef<string | undefined>(undefined);
  const manualThreadTargetRef = useRef<string | undefined>(undefined);
  const currentThreadIdRef = useRef(currentThreadId);
  const observationChainRef = useRef<Promise<void>>(Promise.resolve());
  const historyWindowRef = useRef<ThreadHistoryWindow | undefined>(undefined);
  const olderLoadInFlightRef = useRef(false);
  const threadsRef = useRef(threads);
  const seenAttemptKeysRef = useRef(new Set<string>());
  const historyRuntimeAdmission = runtimeAdmissionKey(runtimeState);
  const observationOwner = useMemo<ThreadObservationOwner>(
    () => ({
      history,
      observedThreadId: undefined,
      ownedThreadIds: new Set(),
    }),
    [history],
  );
  const observationOwnerRef = useRef<ThreadObservationOwner | undefined>(observationOwner);
  observationOwnerRef.current = observationOwner;
  const modelSelectionAvailable = modelSelection !== undefined;
  workspaceRef.current = workspace;
  modelSelectionRef.current = modelSelection;
  accessModeRef.current = accessMode;
  runtimeStateRef.current = runtimeState;
  currentThreadIdRef.current = currentThreadId;
  threadsRef.current = threads;
  const currentResyncReason = useTimelineStore((state) =>
    currentThreadId === undefined ? undefined : state.resyncRequired[currentThreadId],
  );
  const currentResyncSequence = useTimelineStore((state) =>
    currentThreadId === undefined
      ? 0
      : (state.resyncRequestSequenceByThread?.[currentThreadId] ?? 0),
  );
  const activeTurnPresent = useTimelineStore((state) =>
    currentThreadId === undefined
      ? false
      : (state.turnIdsByThread[currentThreadId] ?? []).some((turnId) => {
          const turn = state.turns[turnId];
          return (
            turn !== undefined &&
            turn.origin === "native" &&
            !["completed", "failed", "cancelled"].includes(turn.status)
          );
        }),
  );
  const observedCompaction = useTimelineStore((state) =>
    currentThreadId === undefined ? undefined : state.contextCompactionByThread[currentThreadId],
  );
  const timelineStatusSignature = useTimelineStore((state) =>
    threads
      .map((thread) => {
        const latestTurnId = state.threads[thread.threadId]?.latestTurnId;
        const latestTurn = latestTurnId === undefined ? undefined : state.turns[latestTurnId];
        const status = latestTurn?.origin !== "native" ? "none" : (latestTurn.status ?? "none");
        return `${thread.threadId}:${latestTurnId ?? "none"}:${status}`;
      })
      .join("|"),
  );
  const currentLatestTerminalKey = useTimelineStore((state) => {
    if (currentThreadId === undefined) return undefined;
    const latestTurnId = state.threads[currentThreadId]?.latestTurnId;
    if (latestTurnId === undefined) return undefined;
    const latestTurn = state.turns[latestTurnId];
    const status = latestTurn?.origin === "native" ? latestTurn.status : undefined;
    return status === "completed" || status === "failed" ? `${latestTurnId}:${status}` : undefined;
  });
  const currentLatestTurnStatus = useTimelineStore((state) => {
    if (currentThreadId === undefined) return undefined;
    const latestTurnId = state.threads[currentThreadId]?.latestTurnId;
    const latestTurn = latestTurnId === undefined ? undefined : state.turns[latestTurnId];
    return latestTurn?.origin === "native" ? latestTurn.status : undefined;
  });
  const currentTimelineThreadRevision = useTimelineStore((state) => {
    if (currentThreadId === undefined) return undefined;
    const latestTurnId = state.threads[currentThreadId]?.latestTurnId;
    return latestTurnId === undefined ? undefined : state.turns[latestTurnId]?.threadRevision;
  });
  // generation/server identity 是自动恢复的生命周期边界；即使 resync reason 不变，代际变化也
  // 必须立即让旧 read 失效，避免旧 runtime 的 promise 占住新 runtime 的 single-flight。
  const currentTimelineFence = useTimelineStore(
    (state) => `${state.handshake.generation}:${state.serverInstanceId ?? ""}`,
  );
  // 与自动对账共用同一 scope key；渲染阶段即可隐藏旧错误，effect 再清理其 state，避免切换首帧污染。
  const backgroundErrorScope = `${workspace?.workspaceId ?? "none"}:${currentTimelineFence}:${currentThreadId ?? "none"}`;

  /**
   * 判断 snapshot 操作是否仍属于 active workspace 和最新 request；新建/跨 workspace 激活
   * 已由 native 确认但 React 尚未 commit 时，精确 manual target 临时承担同一 fence。
   */
  const isCurrentRequest = useCallback((request: number, workspaceId: string): boolean => {
    return (
      mountedRef.current &&
      foregroundRequestRef.current === request &&
      (workspaceRef.current?.workspaceId === workspaceId ||
        manualWorkspaceTargetRef.current === workspaceId ||
        pendingWorkspaceActivationRef.current === workspaceId)
    );
  }, []);

  /** session catalog 不依赖当前激活的是哪个 session workspace，只受列表 scope 与 request 栅栏约束。 */
  const isCurrentCatalogRequest = useCallback(
    (request: number, scopeKey: string): boolean =>
      mountedRef.current &&
      foregroundRequestRef.current === request &&
      (catalogScopeKey(workspaceRef.current) === scopeKey ||
        manualWorkspaceTargetRef.current === scopeKey),
    [],
  );

  /** 历史重试还须绑定请求开始时的具体 Workspace、revision 与 Runtime identity。@author kongweiguang */
  const isCurrentHistoryLoadRequest = useCallback(
    (
      request: number,
      scopeKey: string,
      workspaceId: string | undefined,
      expectedWorkspaceRevision: number,
      expectedRuntimeAdmission: string,
    ): boolean =>
      isCurrentCatalogRequest(request, scopeKey) &&
      workspaceRef.current?.workspaceId === workspaceId &&
      historyWorkspaceRevisionRef.current === expectedWorkspaceRevision &&
      runtimeAdmissionKey(runtimeStateRef.current) === expectedRuntimeAdmission &&
      historyRuntimeAdmissionRef.current === expectedRuntimeAdmission,
    [isCurrentCatalogRequest],
  );

  /** 设计原因：观察先于基线读取；串行 owner 切换让旧端口的迟到 ACK 不能接管新代际订阅。 */
  const switchObservedThread = useCallback(
    (threadId: string | undefined): Promise<boolean> => {
      let changed = false;
      const owner = observationOwner;
      const operation = observationChainRef.current.then(async () => {
        if (!mountedRef.current || observationOwnerRef.current !== owner)
          throw new Error("thread observation owner was unmounted");
        const previous = owner.observedThreadId;
        // Release always uses the same port and Set that acquired the observation.
        const release = async (observedThreadId: string): Promise<void> => {
          if (!owner.ownedThreadIds.has(observedThreadId)) return;
          try {
            const result = await owner.history.threadUnobserve({ threadId: observedThreadId });
            if (result.accepted && result.threadId === observedThreadId)
              owner.ownedThreadIds.delete(observedThreadId);
          } catch {
            // Keep ownership recorded so a later switch or unmount can retry a lost ACK.
          }
        };
        if (previous === threadId) {
          for (const observedThreadId of owner.ownedThreadIds) {
            if (observedThreadId !== threadId) await release(observedThreadId);
          }
          return;
        }
        if (threadId !== undefined) {
          const result = await owner.history.threadObserve({ threadId });
          if (!result.accepted || result.threadId !== threadId)
            throw new Error("thread observation identity changed");
          owner.ownedThreadIds.add(threadId);
          if (!mountedRef.current || observationOwnerRef.current !== owner) {
            await release(threadId);
            throw new Error("thread observation owner was unmounted");
          }
        }
        owner.observedThreadId = threadId;
        changed = true;
        historyWindowRef.current = undefined;
        setHasOlderHistory(false);
        setOlderHistoryError(undefined);
        for (const observedThreadId of owner.ownedThreadIds) {
          if (observedThreadId !== threadId) await release(observedThreadId);
        }
      });
      observationChainRef.current = operation.catch(() => undefined);
      return operation.then(() => changed);
    },
    [observationOwner],
  );

  /** 取消指定 recovery token；epoch 比对保证旧 read finally 不会删除后来建立的同 Thread 窗口。 */
  const cancelAutomaticRecovery = useCallback((threadId: string, requestEpoch: number): void => {
    const current = automaticResyncRecoveryTokenRef.current.get(threadId);
    if (current?.requestEpoch !== requestEpoch) return;
    useTimelineStore.getState().cancelRecovery(current);
    automaticResyncRecoveryTokenRef.current.delete(threadId);
    if (automaticResyncActiveRequestRef.current.get(threadId) === requestEpoch) {
      automaticResyncActiveRequestRef.current.delete(threadId);
      automaticResyncInFlightRef.current.delete(threadId);
    }
  }, []);

  /** 捕获 thread/read 发起时的 runtime identity；完成前 generation/server instance 变化即丢弃结果。 */
  const captureSnapshotReadFence = useCallback((workspaceId: string): ThreadSnapshotReadFence => {
    const state = useTimelineStore.getState();
    if (
      state.handshake.phase !== "ready" ||
      state.handshake.generation <= 0 ||
      state.serverInstanceId === undefined
    )
      throw new Error("thread snapshot runtime is not ready");
    return {
      workspaceId,
      generation: state.handshake.generation,
      serverInstanceId: state.serverInstanceId,
    };
  }, []);

  /**
   * snapshot 只有在当前 ready generation 下才进入 timeline reducer；同时校验 threadId，
   * 防止 native adapter 返回错误实体导致跨 Thread 投影污染。请求期 Activity fence 只阻止迟到空切片。
   * @author kongweiguang
   */
  const applySnapshot = useCallback(
    (
      snapshot: TimelineSnapshot,
      workspaceId: string,
      expectedThreadId: string,
      olderCursor?: string | null,
      readFence?: ThreadSnapshotReadFence,
    ): boolean => {
      const state = useTimelineStore.getState();
      const runtime = state.runtime;
      if (!mountedRef.current) return false;
      if (
        state.handshake.phase !== "ready" ||
        state.handshake.generation <= 0 ||
        state.serverInstanceId === undefined ||
        runtime === undefined ||
        !["ready", "busy"].includes(runtime.status)
      )
        return false;
      if (snapshot.threadId !== expectedThreadId) return false;
      const activityFence =
        readFence?.taskActivityReadFence?.threadId === expectedThreadId
          ? readFence.taskActivityReadFence
          : undefined;
      const outcome = useTimelineStore
        .getState()
        .applySnapshot(
          snapshot,
          workspaceId,
          activityFence === undefined ? undefined : { taskActivityReadFence: activityFence },
        );
      // 并发 thread/read 若晚于更新的 committed event 返回，Reducer 会保留新投影；这仍是成功收敛。
      const accepted = outcome === "applied" || outcome === "late";
      if (outcome === "applied" && olderCursor !== undefined) {
        historyWindowRef.current = {
          threadId: expectedThreadId,
          workspaceId,
          snapshot,
          olderCursor,
          seenCursors: new Set(),
        };
        setHasOlderHistory(olderCursor !== null);
        setOlderHistoryError(undefined);
      }
      return accepted;
    },
    [],
  );

  /**
   * 校验 read continuation 仍属于发起时的 runtime；切换 workspace/generation 或 server instance
   * 后，旧 promise 只能失败退出，不能把同名 Thread 的历史投影写回当前 Timeline。
   */
  const assertSnapshotReadFence = useCallback((fence: ThreadSnapshotReadFence): void => {
    const state = useTimelineStore.getState();
    if (
      !mountedRef.current ||
      (workspaceRef.current?.workspaceId !== fence.workspaceId &&
        manualWorkspaceTargetRef.current !== fence.workspaceId &&
        pendingWorkspaceActivationRef.current !== fence.workspaceId) ||
      state.handshake.phase !== "ready" ||
      state.handshake.generation !== fence.generation ||
      state.serverInstanceId !== fence.serverInstanceId
    )
      throw new Error("thread snapshot runtime fence changed");
  }, []);

  /**
   * 恢复首屏只读取最近一页，旧页需用户显式触发，避免打开长会话物化全部历史；Activity 切片在 RPC 前冻结，
   * 使同 revision 的空响应不能覆盖请求期间到达的事实。
   * @author kongweiguang
   */
  const readCompleteThreadSnapshot = useCallback(
    async (threadId: string, fence?: ThreadSnapshotReadFence): Promise<ThreadSnapshotPage> => {
      if (fence !== undefined) assertSnapshotReadFence(fence);
      const requestFence =
        fence === undefined
          ? undefined
          : {
              ...fence,
              taskActivityReadFence: {
                threadId,
                entries: useTimelineStore.getState().taskActivitiesByRootThread[threadId],
              },
            };
      const page = await history.threadRead({
        threadId,
        tail: true,
        limit: THREAD_HISTORY_PAGE_SIZE,
      });
      if (requestFence !== undefined) assertSnapshotReadFence(requestFence);
      if (page.threadId !== threadId) throw new Error("thread snapshot identity changed");
      return {
        snapshot: { ...page, nextCursor: null },
        olderCursor: page.nextCursor,
        readFence: requestFence,
      };
    },
    [assertSnapshotReadFence, history],
  );

  /**
   * 旧页只在可见 Thread 的用户操作中读取，复用虚拟 Timeline；分页快照只扩充消息历史，Task Activity 取提交时
   * 的唯一权威切片，避免旧窗口缓存借历史分页清空期间的新活动。
   * @author kongweiguang
   */
  const loadOlderHistory = useCallback(async (): Promise<void> => {
    const window = historyWindowRef.current;
    const selected = workspaceRef.current;
    if (
      olderLoadInFlightRef.current ||
      window === undefined ||
      window.olderCursor === null ||
      selected?.workspaceId !== window.workspaceId ||
      currentThreadIdRef.current !== window.threadId
    )
      return;
    const cursor = window.olderCursor;
    const runtimeFence = captureSnapshotReadFence(window.workspaceId);
    olderLoadInFlightRef.current = true;
    setLoadingOlderHistory(true);
    setOlderHistoryError(undefined);
    try {
      const fence: ThreadSnapshotReadFence = {
        ...runtimeFence,
        taskActivityReadFence: {
          threadId: window.threadId,
          entries: useTimelineStore.getState().taskActivitiesByRootThread[window.threadId],
        },
      };
      const page = await history.threadRead({
        threadId: window.threadId,
        tail: true,
        cursor,
        limit: THREAD_HISTORY_PAGE_SIZE,
      });
      assertSnapshotReadFence(fence);
      if (
        historyWindowRef.current !== window ||
        currentThreadIdRef.current !== window.threadId ||
        page.threadId !== window.threadId ||
        page.revision !== window.snapshot.revision ||
        page.nextCursor === cursor ||
        (page.nextCursor !== null && window.seenCursors.has(page.nextCursor))
      )
        throw new Error("thread history page changed during read");
      const items = new Map(page.items.map((item) => [item.itemId, item]));
      for (const item of window.snapshot.items) {
        const existing = items.get(item.itemId);
        if (existing !== undefined && JSON.stringify(existing) !== JSON.stringify(item))
          throw new Error("thread history item changed across pages");
        items.set(item.itemId, item);
      }
      const turns = new Map(page.turns.map((turn) => [turn.turnId, turn]));
      for (const turn of window.snapshot.turns) turns.set(turn.turnId, turn);
      const merged: TimelineSnapshot = {
        ...window.snapshot,
        items: [...items.values()],
        turns: [...turns.values()],
        taskActivities: [
          ...(useTimelineStore.getState().taskActivitiesByRootThread[window.threadId] ?? []),
        ],
        nextCursor: null,
      };
      if (
        useTimelineStore.getState().applySnapshot(merged, window.workspaceId, {
          accumulatedHistory: true,
          taskActivityReadFence: fence.taskActivityReadFence,
        }) !== "applied"
      )
        throw new Error("thread history page was not applied");
      window.seenCursors.add(cursor);
      historyWindowRef.current = {
        ...window,
        snapshot: merged,
        olderCursor: page.nextCursor,
      };
      setHasOlderHistory(page.nextCursor !== null);
    } catch {
      if (historyWindowRef.current === window)
        setOlderHistoryError("更早的记录暂时无法读取，请重试。");
    } finally {
      olderLoadInFlightRef.current = false;
      setLoadingOlderHistory(false);
    }
  }, [assertSnapshotReadFence, captureSnapshotReadFence, history]);

  /**
   * 保存当前列表 scope 的目录和选择；session cache 按 kind 聚合，重启后仍需权威 list/read。
   * 淘汰目录缓存时只把本控制器曾持有的 Thread 候选交给 Timeline 的安全清理入口。
   */
  const rememberWorkspaceHistory = useCallback(
    (
      scopeKey: string | undefined,
      sourceThreads: readonly ConversationThread[],
      selectedThreadId: string | undefined,
    ): void => {
      const admission = historyRuntimeAdmissionRef.current;
      if (scopeKey === undefined || admission === undefined) return;
      const scopedThreads = workspaceThreads(scopeKey, sourceThreads);
      const currentThreadId = scopedThreads.some((thread) => thread.threadId === selectedThreadId)
        ? selectedThreadId
        : undefined;
      const cache = workspaceHistoryCacheRef.current;
      cache.set(scopeKey, {
        threads: scopedThreads,
        currentThreadId,
        admission,
        lastUsed: ++workspaceHistoryCacheClockRef.current,
      });
      while (cache.size > MAX_WORKSPACE_HISTORY_CACHES) {
        const oldest = [...cache.entries()].reduce<[string, WorkspaceHistoryCache] | undefined>(
          (candidate, entry) => {
            if (entry[0] === scopeKey) return candidate;
            return candidate === undefined || entry[1].lastUsed < candidate[1].lastUsed
              ? entry
              : candidate;
          },
          undefined,
        );
        if (oldest === undefined) break;
        cache.delete(oldest[0]);
        useTimelineStore
          .getState()
          .pruneInactiveThreads(oldest[1].threads.map((thread) => thread.threadId));
      }
    },
    [],
  );

  /**
   * 读取同一 generation 下的 workspace 缓存并更新 LRU 顺序；不匹配的 admission 立即丢弃，
   * 让恢复路径回到权威 thread/list 与 thread/read，而不是展示旧 server 的正文。
   */
  const cachedWorkspaceHistory = useCallback(
    (scopeKey: string): WorkspaceHistoryCache | undefined => {
      const cache = workspaceHistoryCacheRef.current;
      const entry = cache.get(scopeKey);
      const admission = historyRuntimeAdmissionRef.current;
      if (entry === undefined) return undefined;
      if (admission === undefined || entry.admission !== admission) {
        cache.delete(scopeKey);
        return undefined;
      }
      entry.lastUsed = ++workspaceHistoryCacheClockRef.current;
      return entry;
    },
    [],
  );

  /**
   * 创建 durable Thread 后立即读取 authoritative snapshot；只有 snapshot 通过 generation
   * gate 才向 UI 暴露 threadId；session create 不携带当前 cwd，ACK 后先激活新 workspace id，
   * 且 pending intent 绑定实际开始激活时的 Runtime 身份，避免迟到目录记账错误取消它。
   * @author kongweiguang
   */
  const createAndRestore = useCallback(
    async (
      selected: WorkspaceProjection | undefined,
      selection: ConversationModelSelection | undefined,
      request: number,
      scopeKey: string,
    ): Promise<ConversationThread | undefined> => {
      if (selection === undefined) return undefined;
      const isProject = selected?.kind === "project";
      const fence = isProject ? captureSnapshotReadFence(selected.workspaceId) : undefined;
      const created = await history.threadCreate({
        ...(isProject ? { cwd: selected.rootPath } : {}),
        title: "新对话",
        providerId: selection.providerId,
        modelId: selection.modelId,
        reasoningLevel: selection.reasoningLevel,
        accessMode: accessModeRef.current,
        collaborationMode: "default",
      });
      if (!isCurrentCatalogRequest(request, scopeKey)) return undefined;
      if (isProject) {
        if (created.workspaceId !== selected.workspaceId || created.workspaceKind !== "project")
          throw new Error("created thread belongs to another workspace");
      } else {
        if (created.workspaceKind !== "session")
          throw new Error("session create returned a non-session thread");
        manualWorkspaceTargetRef.current = created.workspaceId;
        pendingWorkspaceActivationRef.current = created.workspaceId;
        pendingWorkspaceActivationAdmissionRef.current = runtimeAdmissionKey(
          runtimeStateRef.current,
        );
        manualThreadTargetRef.current = created.threadId;
        pendingThreadActivationRef.current = created.threadId;
        const activated = await activateWorkspace(created.workspaceId);
        if (
          !isCurrentCatalogRequest(request, scopeKey) ||
          activated?.workspaceId !== created.workspaceId ||
          activated.kind !== "session"
        ) {
          if (pendingWorkspaceActivationRef.current === created.workspaceId) {
            pendingWorkspaceActivationRef.current = undefined;
            pendingWorkspaceActivationAdmissionRef.current = undefined;
          }
          if (pendingThreadActivationRef.current === created.threadId)
            pendingThreadActivationRef.current = undefined;
          return undefined;
        }
      }
      if (
        created.preferences === null ||
        created.preferences.providerId !== selection.providerId ||
        created.preferences.modelId !== selection.modelId
      )
        throw new Error("created thread belongs to another model selection");
      const workspaceId = created.workspaceId;
      const snapshotFence = fence ?? captureSnapshotReadFence(workspaceId);
      await switchObservedThread(created.threadId);
      const { snapshot, olderCursor, readFence } = await readCompleteThreadSnapshot(
        created.threadId,
        snapshotFence,
      );
      if (!isCurrentRequest(request, workspaceId)) return undefined;
      if (!applySnapshot(snapshot, workspaceId, created.threadId, olderCursor, readFence)) {
        throw new Error("created thread snapshot was not applied");
      }
      return created;
    },
    [
      applySnapshot,
      activateWorkspace,
      captureSnapshotReadFence,
      history,
      isCurrentCatalogRequest,
      isCurrentRequest,
      readCompleteThreadSnapshot,
      switchObservedThread,
    ],
  );

  /**
   * 项目列表维持原有自动恢复；session kind 列表只聚合目录，不在空白类别自动选中或创建会话。
   * 失败按读取阶段归属，显式重试复用同一 loader，并由 request、scope、revision 与 Runtime identity 拒绝迟到回包。
   * @author kongweiguang
   */
  const loadWorkspaceHistory = useCallback(
    async (
      selected: WorkspaceProjection | undefined,
      selection: ConversationModelSelection | undefined,
      preferredThreadId: string | undefined,
      scopeKey: string,
      expectedWorkspaceRevision: number,
      expectedRuntimeAdmission: string,
    ): Promise<void> => {
      const request = foregroundRequestRef.current + 1;
      foregroundRequestRef.current = request;
      let snapshotThreadId: string | undefined;
      let failureSource: "history-load" | "operation" = "history-load";
      setBusy(true);
      setError(undefined);
      try {
        const listed = await history.threadList(
          scopeKey === "session"
            ? { workspaceKind: "session", limit: 200 }
            : { workspaceId: scopeKey, limit: 200 },
        );
        if (
          !isCurrentHistoryLoadRequest(
            request,
            scopeKey,
            selected?.workspaceId,
            expectedWorkspaceRevision,
            expectedRuntimeAdmission,
          )
        )
          return;
        if (
          listed.items.some((thread) =>
            scopeKey === "session"
              ? thread.workspaceKind !== "session"
              : thread.workspaceId !== scopeKey,
          )
        ) {
          setHistoryLoadError({
            source: "history-load",
            message: "历史会话数据无法恢复，请重新选择工作范围。 ",
            scopeKey,
            workspaceId: selected?.workspaceId,
            workspaceRevision: expectedWorkspaceRevision,
            runtimeAdmission: expectedRuntimeAdmission,
            threadId: preferredThreadId,
          });
          return;
        }
        const scopedThreads = workspaceThreads(scopeKey, listed.items);
        threadsRef.current = scopedThreads;
        setThreads(scopedThreads);
        const currentThread = preferredThreadId ?? currentThreadIdRef.current;
        const first = scopedThreads.find((thread) => thread.threadId === currentThread);
        if (scopeKey === "session") {
          if (selected === undefined) {
            // 分类空白时只展示聚合历史；隐藏 A 的 Timeline/Composer owner，避免无 workspace 仍可操作。
            failureSource = "operation";
            await switchObservedThread(undefined);
            if (
              !isCurrentHistoryLoadRequest(
                request,
                scopeKey,
                undefined,
                expectedWorkspaceRevision,
                expectedRuntimeAdmission,
              )
            )
              return;
            currentThreadIdRef.current = undefined;
            setCurrentThreadId(undefined);
            return;
          }
          if (first === undefined) {
            failureSource = "operation";
            await switchObservedThread(undefined);
            if (
              !isCurrentHistoryLoadRequest(
                request,
                scopeKey,
                selected.workspaceId,
                expectedWorkspaceRevision,
                expectedRuntimeAdmission,
              )
            )
              return;
            currentThreadIdRef.current = undefined;
            setCurrentThreadId(undefined);
            return;
          }
          if (first.workspaceId !== selected.workspaceId) return;
        }
        const target = first ?? (scopeKey === "session" ? undefined : scopedThreads[0]);
        if (target !== undefined) {
          failureSource = "operation";
          const observationChanged = await switchObservedThread(target.threadId);
          if (
            !isCurrentHistoryLoadRequest(
              request,
              scopeKey,
              selected?.workspaceId,
              expectedWorkspaceRevision,
              expectedRuntimeAdmission,
            ) ||
            !isCurrentRequest(request, target.workspaceId)
          )
            return;
          const loadedRevision =
            useTimelineStore.getState().threadRevisionByThread[target.threadId] ?? -1;
          if (
            !observationChanged &&
            canReuseLoadedThread(target.threadId, target.workspaceId) &&
            loadedRevision >= target.revision
          ) {
            currentThreadIdRef.current = target.threadId;
            setCurrentThreadId(target.threadId);
            return;
          }
          snapshotThreadId = target.threadId;
          failureSource = "history-load";
          const { snapshot, olderCursor, readFence } = await readCompleteThreadSnapshot(
            target.threadId,
            captureSnapshotReadFence(target.workspaceId),
          );
          if (
            !isCurrentHistoryLoadRequest(
              request,
              scopeKey,
              selected?.workspaceId,
              expectedWorkspaceRevision,
              expectedRuntimeAdmission,
            ) ||
            !isCurrentRequest(request, target.workspaceId)
          )
            return;
          if (
            !applySnapshot(snapshot, target.workspaceId, target.threadId, olderCursor, readFence)
          ) {
            await switchObservedThread(undefined).catch(() => undefined);
            if (
              !isCurrentHistoryLoadRequest(
                request,
                scopeKey,
                selected?.workspaceId,
                expectedWorkspaceRevision,
                expectedRuntimeAdmission,
              ) ||
              !isCurrentRequest(request, target.workspaceId)
            )
              return;
            if (currentThreadIdRef.current === target.threadId) {
              currentThreadIdRef.current = undefined;
              setCurrentThreadId(undefined);
            }
            setHistoryLoadError({
              source: "history-load",
              message: "最近的会话无法恢复，请重新选择工作范围。 ",
              scopeKey,
              workspaceId: selected?.workspaceId,
              workspaceRevision: expectedWorkspaceRevision,
              runtimeAdmission: expectedRuntimeAdmission,
              threadId: target.threadId,
            });
            return;
          }
          setBackgroundError(undefined);
          currentThreadIdRef.current = target.threadId;
          setCurrentThreadId(target.threadId);
          return;
        }
        if (scopeKey === "session") return;
        if (selected === undefined) return;
        failureSource = "operation";
        currentThreadIdRef.current = undefined;
        setCurrentThreadId(undefined);
        const created = await createAndRestore(selected, selection, request, scopeKey);
        if (
          created === undefined ||
          !isCurrentHistoryLoadRequest(
            request,
            scopeKey,
            selected.workspaceId,
            expectedWorkspaceRevision,
            expectedRuntimeAdmission,
          ) ||
          !isCurrentRequest(request, created.workspaceId)
        )
          return;
        setThreads(workspaceThreads(scopeKey, [created]));
        currentThreadIdRef.current = created.threadId;
        setCurrentThreadId(created.threadId);
      } catch {
        if (
          isCurrentHistoryLoadRequest(
            request,
            scopeKey,
            selected?.workspaceId,
            expectedWorkspaceRevision,
            expectedRuntimeAdmission,
          )
        ) {
          if (snapshotThreadId !== undefined) {
            await switchObservedThread(undefined).catch(() => undefined);
            if (
              !isCurrentHistoryLoadRequest(
                request,
                scopeKey,
                selected?.workspaceId,
                expectedWorkspaceRevision,
                expectedRuntimeAdmission,
              )
            )
              return;
            currentThreadIdRef.current = undefined;
            setCurrentThreadId(undefined);
          }
          if (failureSource === "history-load") {
            setHistoryLoadError({
              source: "history-load",
              message: "历史会话暂时不可用，请重试。 ",
              scopeKey,
              workspaceId: selected?.workspaceId,
              workspaceRevision: expectedWorkspaceRevision,
              runtimeAdmission: expectedRuntimeAdmission,
              threadId: snapshotThreadId ?? preferredThreadId,
            });
          } else {
            setError("历史会话暂时不可用，请重试。 ");
          }
        }
      } finally {
        if (isCurrentCatalogRequest(request, scopeKey)) setBusy(false);
      }
    },
    [
      applySnapshot,
      captureSnapshotReadFence,
      createAndRestore,
      history,
      isCurrentCatalogRequest,
      isCurrentHistoryLoadRequest,
      isCurrentRequest,
      readCompleteThreadSnapshot,
      setHistoryLoadError,
      setError,
      switchObservedThread,
    ],
  );

  /**
   * 重试闭包绑定渲染时的具体失败记录；旧通知退出动画晚到时不能借 ref 重试后来出现的新错误。
   * 同一帧双击仍由同步清空 errorStateRef 合并，目录与 Runtime 身份继续由 loader fence 校验。
   * @author kongweiguang
   */
  const retryHistory = useCallback(async (): Promise<void> => {
    const failure = error;
    if (failure === undefined || errorStateRef.current !== failure) return;
    const selected = workspaceRef.current;
    const scopeKey = catalogScopeKey(selected);
    const admission = runtimeAdmissionKey(runtimeStateRef.current);
    if (
      failure?.source !== "history-load" ||
      failure.scopeKey !== scopeKey ||
      failure.workspaceId !== selected?.workspaceId ||
      failure.workspaceRevision !== historyWorkspaceRevisionRef.current ||
      failure.runtimeAdmission !== admission ||
      admission === undefined ||
      historyScopeKeyRef.current !== scopeKey ||
      historyRuntimeAdmissionRef.current !== admission
    )
      return;
    await loadWorkspaceHistory(
      selected,
      modelSelectionRef.current,
      failure.threadId ?? currentThreadIdRef.current,
      scopeKey,
      failure.workspaceRevision,
      failure.runtimeAdmission,
    );
  }, [error, loadWorkspaceHistory]);

  /**
   * 为“新对话”明确冻结当前 Provider/Model 默认选择；仅新建动作使用该选择，已有 Thread
   * 的模型变化必须走 preferences/update，避免把普通切换错误实现成另一个会话。清理只由本次
   * 前台请求拥有，不能让旧 create 的失败收尾抹掉更新的 workspace activation intent。
   * @author kongweiguang
   */
  const createWithSelection = useCallback(
    async (
      selection: ConversationModelSelection,
      selected: WorkspaceProjection | undefined = workspaceRef.current,
      scopeKey = catalogScopeKey(selected),
      requestOverride?: number,
    ): Promise<void> => {
      const request = foregroundRequestRef.current + 1;
      const effectiveRequest = requestOverride ?? request;
      foregroundRequestRef.current = effectiveRequest;
      setBusy(true);
      setError(undefined);
      try {
        const created = await createAndRestore(selected, selection, effectiveRequest, scopeKey);
        if (
          created === undefined ||
          !isCurrentRequest(effectiveRequest, created.workspaceId) ||
          !isCurrentCatalogRequest(effectiveRequest, scopeKey)
        )
          return;
        const creationEpoch = directoryMutationEpochRef.current + 1;
        directoryMutationEpochRef.current = creationEpoch;
        localCreationEpochsRef.current.set(created.threadId, {
          scopeKey,
          epoch: creationEpoch,
        });
        setThreads((current) =>
          workspaceThreads(scopeKey, [
            created,
            ...current.filter(
              (thread) =>
                (scopeKey === "session"
                  ? thread.workspaceKind === "session"
                  : thread.workspaceId === scopeKey) && thread.threadId !== created.threadId,
            ),
          ]),
        );
        currentThreadIdRef.current = created.threadId;
        setCurrentThreadId(created.threadId);
      } catch {
        if (
          foregroundRequestRef.current === effectiveRequest &&
          pendingWorkspaceActivationRef.current !== undefined &&
          workspaceRef.current?.workspaceId !== pendingWorkspaceActivationRef.current
        ) {
          pendingWorkspaceActivationRef.current = undefined;
          pendingWorkspaceActivationAdmissionRef.current = undefined;
          pendingThreadActivationRef.current = undefined;
        }
        if (isCurrentCatalogRequest(effectiveRequest, scopeKey))
          setError("新会话暂时无法创建，请重试。 ");
      } finally {
        if (foregroundRequestRef.current === effectiveRequest) {
          manualWorkspaceTargetRef.current = undefined;
          manualThreadTargetRef.current = undefined;
        }
        if (isCurrentCatalogRequest(effectiveRequest, scopeKey)) setBusy(false);
      }
    },
    [createAndRestore, isCurrentCatalogRequest, isCurrentRequest, setError],
  );

  /**
   * 普通“新对话”在点击时读取最新 v3 默认选择；当前 Thread 仍为空时复用它，并用 single-flight
   * 收口同一事件循环内的连续触发；项目菜单可将稳定 workspaceId 作为显式目标，避免父组件
   * 尚未提交新 props 时在旧项目中创建 Thread。
   * @author kongweiguang
   */
  const create = useCallback(
    async (options?: { workspaceId?: string }): Promise<void> => {
      const targetWorkspaceId = options?.workspaceId;
      const currentThreadId = currentThreadIdRef.current;
      if (
        targetWorkspaceId === undefined &&
        currentThreadId !== undefined &&
        isEmptyLoadedThread(currentThreadId)
      )
        return;
      const pending = creationInFlightRef.current;
      if (pending !== undefined) {
        await pending;
        return;
      }
      const selection = modelSelectionRef.current;
      if (selection === undefined) {
        setError("尚未选择可用模型，请先在设置中完成模型配置。");
        return;
      }
      const operation = (async (): Promise<void> => {
        if (targetWorkspaceId === undefined) {
          await createWithSelection(selection);
          return;
        }
        const request = foregroundRequestRef.current + 1;
        foregroundRequestRef.current = request;
        manualWorkspaceTargetRef.current = targetWorkspaceId;
        pendingWorkspaceActivationRef.current = targetWorkspaceId;
        pendingWorkspaceActivationAdmissionRef.current = historyRuntimeAdmission;
        setBusy(true);
        setError(undefined);
        let activationConfirmed = false;
        try {
          const selected = await activateWorkspace(targetWorkspaceId);
          if (
            selected === undefined ||
            selected.workspaceId !== targetWorkspaceId ||
            selected.kind !== "project" ||
            !isCurrentRequest(request, targetWorkspaceId)
          )
            throw new Error("项目目录当前不可用，无法创建新对话。");
          activationConfirmed = true;
          await createWithSelection(selection, selected, targetWorkspaceId, request);
        } catch (error) {
          if (isCurrentRequest(request, targetWorkspaceId))
            setError(error instanceof Error ? error.message : "新会话暂时无法创建，请重试。 ");
        } finally {
          if (foregroundRequestRef.current === request) {
            manualWorkspaceTargetRef.current = undefined;
            manualThreadTargetRef.current = undefined;
            if (!activationConfirmed || workspaceRef.current?.workspaceId === targetWorkspaceId) {
              pendingWorkspaceActivationRef.current = undefined;
              pendingWorkspaceActivationAdmissionRef.current = undefined;
            }
            setBusy(false);
          }
        }
      })();
      creationInFlightRef.current = operation;
      try {
        await operation;
      } finally {
        if (creationInFlightRef.current === operation) creationInFlightRef.current = undefined;
      }
    },
    [activateWorkspace, createWithSelection, historyRuntimeAdmission, isCurrentRequest, setError],
  );

  /** 快照无法接管目标 Thread 时，先释放其观察再清除当前身份，避免旧 Timeline 冒充可操作会话。 */
  const failCurrentThreadSelection = useCallback(
    async (request: number, workspaceId: string, message: string): Promise<void> => {
      if (!isCurrentRequest(request, workspaceId)) return;
      await switchObservedThread(undefined).catch(() => undefined);
      if (!isCurrentRequest(request, workspaceId)) return;
      currentThreadIdRef.current = undefined;
      setCurrentThreadId(undefined);
      setError(message);
    },
    [isCurrentRequest, setError, switchObservedThread],
  );

  /**
   * 选择指定 Thread；稳定 project ID 足以授权只读历史快照，所以 Java 可在目录失效时返回未绑定投影，
   * 而 session 聚合 owner 仍需成功激活后才能成为当前交互 Workspace。身份不匹配时停止选择。
   * 同目标重复激活仍由前台请求序号区分，避免旧响应抢占新意图。
   * Loaded selection 同步推进 ref，确保 await select 的调用方不必等待 React render 才能校验目标身份。
   * @author kongweiguang
   */
  const select = useCallback(
    async (threadId: string): Promise<void> => {
      const target = threadsRef.current.find((thread) => thread.threadId === threadId);
      let selected = workspaceRef.current;
      if (target === undefined) return;
      // session 聚合页没有当前项目 workspace，但目录行仍携带唯一的持久 owner；恢复快照和
      // request fence 必须使用该 owner，不能把“无项目”UI 状态当成缺失所有权。
      const sessionAggregateOwner =
        selected === undefined && target.workspaceKind === "session"
          ? target.workspaceId
          : undefined;
      const selectedWorkspaceId = selected?.workspaceId ?? sessionAggregateOwner;
      const workspaceChanged =
        selected === undefined
          ? target.workspaceKind === "session"
          : target.workspaceId !== selectedWorkspaceId;
      const activationRequest = workspaceChanged ? foregroundRequestRef.current + 1 : undefined;
      if (activationRequest !== undefined) {
        // 先占用现有前台请求序号，区分同一 Thread 的并发激活；目标 ID 相同不代表请求相同。
        foregroundRequestRef.current = activationRequest;
        manualWorkspaceTargetRef.current = target.workspaceId;
        pendingWorkspaceActivationRef.current = target.workspaceId;
        pendingWorkspaceActivationAdmissionRef.current = historyRuntimeAdmission;
        manualThreadTargetRef.current = threadId;
        pendingThreadActivationRef.current = threadId;
        let activation: WorkspaceProjection | undefined;
        try {
          activation = await activateWorkspace(target.workspaceId);
        } catch {
          activation = undefined;
        }
        const ownsActivationIntent =
          foregroundRequestRef.current === activationRequest &&
          manualWorkspaceTargetRef.current === target.workspaceId &&
          manualThreadTargetRef.current === threadId;
        if (!ownsActivationIntent) return;
        if (
          activation === undefined ||
          activation.workspaceId !== target.workspaceId ||
          activation.kind !== target.workspaceKind
        ) {
          if (manualWorkspaceTargetRef.current === target.workspaceId)
            manualWorkspaceTargetRef.current = undefined;
          if (pendingWorkspaceActivationRef.current === target.workspaceId) {
            pendingWorkspaceActivationRef.current = undefined;
            pendingWorkspaceActivationAdmissionRef.current = undefined;
          }
          if (manualThreadTargetRef.current === threadId) manualThreadTargetRef.current = undefined;
          if (pendingThreadActivationRef.current === threadId)
            pendingThreadActivationRef.current = undefined;
          setError(
            target.workspaceKind === "session"
              ? "会话工作目录暂时无法打开，请重试。 "
              : "会话所属项目已不可用。 ",
          );
          return;
        }
        selected = activation;
      }
      const effectiveWorkspaceId = selected?.workspaceId;
      if (effectiveWorkspaceId === undefined) return;
      if (sessionAggregateOwner !== undefined) {
        pendingWorkspaceActivationRef.current = sessionAggregateOwner;
        pendingWorkspaceActivationAdmissionRef.current ??= historyRuntimeAdmission;
      }
      const request = activationRequest ?? foregroundRequestRef.current + 1;
      foregroundRequestRef.current = request;
      setError(undefined);
      let observationChanged: boolean;
      try {
        observationChanged = await switchObservedThread(threadId);
      } catch {
        if (isCurrentRequest(request, effectiveWorkspaceId))
          setError("会话实时状态暂时无法连接，请重试。 ");
        return;
      }
      if (!isCurrentRequest(request, effectiveWorkspaceId)) return;
      const requiresSnapshot =
        observationChanged || !canReuseLoadedThread(threadId, effectiveWorkspaceId);
      if (!requiresSnapshot) {
        setBusy(false);
        setBackgroundError(undefined);
        if (currentThreadIdRef.current !== threadId) {
          setThreads((current) => {
            const scopeKey = catalogScopeKey(selected);
            const scoped = workspaceThreads(
              scopeKey,
              current.filter((thread) =>
                selected?.kind === "project"
                  ? thread.workspaceId === selected?.workspaceId
                  : thread.workspaceKind === "session",
              ),
            );
            return scoped.some((thread) => thread.threadId === threadId)
              ? scoped
              : workspaceThreads(scopeKey, [target, ...scoped]);
          });
          currentThreadIdRef.current = threadId;
          setCurrentThreadId(threadId);
        }
        manualWorkspaceTargetRef.current = undefined;
        manualThreadTargetRef.current = undefined;
        return;
      }
      setBusy(true);
      try {
        const { snapshot, olderCursor, readFence } = await readCompleteThreadSnapshot(
          threadId,
          captureSnapshotReadFence(effectiveWorkspaceId),
        );
        if (!isCurrentRequest(request, effectiveWorkspaceId)) return;
        if (!applySnapshot(snapshot, effectiveWorkspaceId, threadId, olderCursor, readFence)) {
          await failCurrentThreadSelection(
            request,
            effectiveWorkspaceId,
            "会话无法恢复，请重新选择项目。 ",
          );
          return;
        }
        setBackgroundError(undefined);
        if (!isCurrentRequest(request, effectiveWorkspaceId)) return;
        setThreads((current) => {
          const scopeKey = catalogScopeKey(selected);
          const scoped = workspaceThreads(
            scopeKey,
            current.filter((thread) =>
              selected?.kind === "project"
                ? thread.workspaceId === selected?.workspaceId
                : thread.workspaceKind === "session",
            ),
          );
          return scoped.some((thread) => thread.threadId === threadId)
            ? scoped
            : workspaceThreads(scopeKey, [target, ...scoped]);
        });
        currentThreadIdRef.current = threadId;
        setCurrentThreadId(threadId);
      } catch {
        if (isCurrentRequest(request, effectiveWorkspaceId))
          await failCurrentThreadSelection(
            request,
            effectiveWorkspaceId,
            "会话暂时无法读取，请重试。 ",
          );
      } finally {
        manualWorkspaceTargetRef.current = undefined;
        manualThreadTargetRef.current = undefined;
        if (isCurrentRequest(request, effectiveWorkspaceId)) setBusy(false);
      }
    },
    [
      activateWorkspace,
      applySnapshot,
      captureSnapshotReadFence,
      isCurrentRequest,
      failCurrentThreadSelection,
      readCompleteThreadSnapshot,
      historyRuntimeAdmission,
      setError,
      switchObservedThread,
    ],
  );

  /**
   * 标题搜索始终绑定调用时的当前 Workspace；晚到结果若已跨 Workspace 便直接丢弃，命中项只
   * 补入目录供后续 select 使用，不把查询结果误当最近排序。
   */
  const search = useCallback(
    async (query: string): Promise<ConversationThread[]> => {
      const selected = workspaceRef.current;
      const scopeKey = catalogScopeKey(selected);
      const result = await history.threadSearch({
        ...(scopeKey === "session"
          ? { workspaceKind: "session" as const }
          : { workspaceId: scopeKey }),
        query: query.trim(),
        limit: 100,
      });
      if (catalogScopeKey(workspaceRef.current) !== scopeKey) return [];
      if (
        result.items.some((thread) =>
          scopeKey === "session"
            ? thread.workspaceKind !== "session"
            : thread.workspaceId !== scopeKey,
        )
      ) {
        throw new Error("thread search crossed catalog scope boundary");
      }
      setThreads((current) => {
        const next = mergeSearchThreads(current, scopeKey, result.items);
        threadsRef.current = next;
        return next;
      });
      return result.items;
    },
    [history],
  );

  /**
   * 取 Timeline 与目录投影中的最大 CAS revision。偏好/重命名 RPC 会先返回新目录 revision，
   * Timeline 则可能仍停在上一个事件；只固定优先 Timeline 会让紧接着的第二次设置永久冲突。
   */
  const threadRevision = useCallback((threadId: string): number | undefined => {
    const timelineRevision = useTimelineStore.getState().threadRevisionByThread[threadId];
    const directoryRevision = threadsRef.current.find(
      (thread) => thread.threadId === threadId,
    )?.revision;
    if (timelineRevision === undefined) return directoryRevision;
    if (directoryRevision === undefined) return timelineRevision;
    return Math.max(timelineRevision, directoryRevision);
  }, []);

  /** 行级 pending 只锁定目标会话；Set 保证同一行多类动作不会互相覆盖释放。 */
  const setThreadMutationPending = useCallback((threadId: string, pending: boolean): void => {
    setMutatingThreadIds((current) => {
      const next = new Set(current);
      if (pending) next.add(threadId);
      else next.delete(threadId);
      return [...next];
    });
  }, []);

  /**
   * CONFLICT 后读取同一提交边界的完整快照；虽然调用者只需要 revision，当前 Thread 仍可能
   * 应用这次快照，不能用 limit:1 的半快照擦除已经显示的正文或破坏 Timeline key 稳定性。
   */
  const rereadThreadRevision = useCallback(
    async (threadId: string): Promise<number> => {
      const selected = workspaceRef.current;
      const fence =
        selected === undefined ? undefined : captureSnapshotReadFence(selected.workspaceId);
      if (fence !== undefined) assertSnapshotReadFence(fence);
      const { snapshot, olderCursor, readFence } = await readCompleteThreadSnapshot(
        threadId,
        fence,
      );
      if (fence !== undefined) assertSnapshotReadFence(fence);
      if (selected !== undefined && currentThreadIdRef.current === threadId) {
        applySnapshot(snapshot, selected.workspaceId, threadId, olderCursor, readFence);
      }
      return snapshot.revision;
    },
    [applySnapshot, assertSnapshotReadFence, captureSnapshotReadFence, readCompleteThreadSnapshot],
  );

  /** 恢复入口只允许读取并应用仍在前台的 Thread，避免用旧页面的无效快照发起 CAS。 */
  const readCurrentThreadRevision = useCallback(
    async (threadId: string): Promise<number> => {
      if (currentThreadIdRef.current !== threadId)
        throw new Error("current conversation changed before thread revision read");
      const revision = await rereadThreadRevision(threadId);
      const timeline = useTimelineStore.getState();
      if (
        currentThreadIdRef.current !== threadId ||
        (timeline.threadRevisionByThread[threadId] ?? -1) < revision
      )
        throw new Error("current conversation revision did not converge");
      return revision;
    },
    [rereadThreadRevision],
  );

  /** 更新持久操作身份的轻量 UI 投影；Timeline 虚拟行卸载时 controller 仍持有真实请求。 */
  const publishForkReplyState = useCallback(
    (anchor: ForkReplyIdentity, state: ForkReplyViewState["state"] | undefined): void => {
      if (!mountedRef.current) return;
      const key = forkReplyKey(anchor);
      setForkReplyView((current) => {
        const retained = current.filter((entry) => forkReplyKey(entry) !== key);
        return state === undefined ? retained : [...retained, { ...anchor, state }];
      });
    },
    [],
  );

  /** 新 Thread 按来源 owner 写入目录缓存，切换到其它 workspace 时不污染当前侧栏选择。 */
  const rememberForkedThread = useCallback(
    (
      sourceThread: ConversationThread | undefined,
      forkedThread: ConversationThread,
      admission: string,
    ): void => {
      if (!mountedRef.current || historyRuntimeAdmissionRef.current !== admission) return;
      const scopeKey =
        sourceThread?.workspaceKind === "session" || forkedThread.workspaceKind === "session"
          ? "session"
          : (sourceThread?.workspaceId ?? forkedThread.workspaceId);
      const epoch = directoryMutationEpochRef.current + 1;
      directoryMutationEpochRef.current = epoch;
      localCreationEpochsRef.current.set(forkedThread.threadId, { scopeKey, epoch });
      const cached = workspaceHistoryCacheRef.current.get(scopeKey);
      const existingCache = cached?.admission === admission ? cached : undefined;
      const cachedThreads = workspaceThreads(scopeKey, [
        forkedThread,
        ...(sourceThread === undefined ? [] : [sourceThread]),
        ...(existingCache?.threads ?? []),
      ]);
      workspaceHistoryCacheRef.current.set(scopeKey, {
        threads: cachedThreads,
        currentThreadId: existingCache?.currentThreadId,
        admission,
        lastUsed: ++workspaceHistoryCacheClockRef.current,
      });
      while (workspaceHistoryCacheRef.current.size > MAX_WORKSPACE_HISTORY_CACHES) {
        const oldest = [...workspaceHistoryCacheRef.current.entries()].reduce<
          [string, WorkspaceHistoryCache] | undefined
        >((candidate, entry) => {
          if (entry[0] === scopeKey) return candidate;
          return candidate === undefined || entry[1].lastUsed < candidate[1].lastUsed
            ? entry
            : candidate;
        }, undefined);
        if (oldest === undefined) break;
        workspaceHistoryCacheRef.current.delete(oldest[0]);
        useTimelineStore
          .getState()
          .pruneInactiveThreads(oldest[1].threads.map((thread) => thread.threadId));
      }

      if (
        historyRuntimeAdmissionRef.current === admission &&
        catalogScopeKey(workspaceRef.current) === scopeKey
      ) {
        const nextThreads = workspaceThreads(scopeKey, [forkedThread, ...threadsRef.current]);
        threadsRef.current = nextThreads;
        setThreads(nextThreads);
      }
    },
    [],
  );

  /**
   * The visible answer identity is the last settled FINAL_ANSWER item, while the source Turn may still be active.
   * We freeze the current revision only once, share the promise for duplicate clicks, and keep a committed receipt
   * until target selection succeeds so a failed open can never create a second Thread.
   */
  const forkReply = useCallback(
    (anchor: ForkReplyIdentity): Promise<ConversationThreadForkOutcome> => {
      const attemptKey = forkReplyKey(anchor);
      const shared = forkInFlightRef.current.get(attemptKey);
      if (shared !== undefined) return shared;

      const sourceThreadId = anchor.threadId;
      const sourceThread = threadsRef.current.find((thread) => thread.threadId === sourceThreadId);
      const sourceRequestEpoch = foregroundRequestRef.current;
      const sourceItem = useTimelineStore.getState().items[anchor.itemId];
      const sourceTurn =
        sourceItem?.threadId === sourceThreadId
          ? useTimelineStore.getState().turns[sourceItem.turnId]
          : undefined;
      const admission = historyRuntimeAdmissionRef.current;
      const threadFork = history.threadFork;
      const validSource =
        currentThreadIdRef.current === sourceThreadId &&
        sourceThread?.status === "active" &&
        sourceItem?.threadId === sourceThreadId &&
        sourceItem.kind === "agent_message" &&
        sourceItem.final === true &&
        sourceItem.status === "completed" &&
        sourceItem.metadata?.failureReply !== true &&
        sourceTurn?.threadId === sourceThreadId;
      if (threadFork === undefined) {
        return Promise.reject(new Error("当前运行时暂不支持从答复分叉。"));
      }
      const existingAttempt = forkAttemptsRef.current.get(attemptKey);
      if (existingAttempt?.result === undefined && !validSource) {
        return Promise.reject(new Error("当前答复已不可分叉，请重新载入历史后重试。"));
      }
      if (sourceThread === undefined || admission === undefined) {
        return Promise.reject(new Error("当前会话尚未就绪，暂时无法分叉。"));
      }

      const request = (async (): Promise<ConversationThreadForkOutcome> => {
        publishForkReplyState(anchor, "pending");
        let preserveView = false;
        try {
          let attempt = forkAttemptsRef.current.get(attemptKey);
          preserveView = attempt?.result !== undefined;
          if (attempt === undefined) {
            const expectedThreadRevision = await readCurrentThreadRevision(sourceThreadId);
            if (
              currentThreadIdRef.current !== sourceThreadId ||
              foregroundRequestRef.current !== sourceRequestEpoch ||
              historyRuntimeAdmissionRef.current !== admission
            )
              return "deferred";
            attempt = {
              input: {
                sourceThreadId,
                sourceItemId: anchor.itemId,
                expectedThreadRevision,
                operationKey: createOperationKey(),
              },
            };
            forkAttemptsRef.current.set(attemptKey, attempt);
          }
          if (attempt.result !== undefined) {
            preserveView = true;
            publishForkReplyState(anchor, "created");
          }

          if (attempt.result === undefined) {
            const result = await threadFork(attempt.input);
            if (!forkReceiptMatches(attempt.input, result))
              throw new Error("分叉回执与所选答复不匹配。");
            if (
              result.thread.workspaceId !== sourceThread.workspaceId ||
              result.thread.workspaceKind !== sourceThread.workspaceKind
            )
              throw new Error("新会话没有继承来源工作区身份。");
            attempt.result = result;
            forkAttemptsRef.current.set(attemptKey, attempt);
            rememberForkedThread(sourceThread, result.thread, admission);
            publishForkReplyState(anchor, "created");
            preserveView = true;
          }

          const result = attempt.result;
          if (result === undefined) throw new Error("分叉回执暂时无法读取。");
          if (
            currentThreadIdRef.current !== sourceThreadId ||
            foregroundRequestRef.current !== sourceRequestEpoch ||
            historyRuntimeAdmissionRef.current !== admission ||
            catalogScopeKey(workspaceRef.current) !==
              (sourceThread.workspaceKind === "session" ? "session" : sourceThread.workspaceId)
          )
            return "deferred";

          const targetSelectionEpoch = foregroundRequestRef.current + 1;
          await select(result.thread.threadId);
          if (
            currentThreadIdRef.current === result.thread.threadId &&
            foregroundRequestRef.current === targetSelectionEpoch &&
            historyRuntimeAdmissionRef.current === admission &&
            catalogScopeKey(workspaceRef.current) ===
              (sourceThread.workspaceKind === "session" ? "session" : sourceThread.workspaceId)
          ) {
            try {
              await history.acknowledgeThreadForkOpened?.(attempt.input.operationKey);
              forkAttemptsRef.current.delete(attemptKey);
              publishForkReplyState(anchor, undefined);
              preserveView = false;
            } catch {
              // The target is open; retaining the receipt only makes a later click/reload reopen this same Thread.
            }
            return "opened";
          }

          const targetOpenFailedForThisIntent =
            foregroundRequestRef.current === targetSelectionEpoch &&
            historyRuntimeAdmissionRef.current === admission &&
            catalogScopeKey(workspaceRef.current) ===
              (sourceThread.workspaceKind === "session" ? "session" : sourceThread.workspaceId) &&
            (currentThreadIdRef.current === sourceThreadId ||
              currentThreadIdRef.current === undefined);
          if (targetOpenFailedForThisIntent) {
            if (currentThreadIdRef.current === undefined) {
              // select clears the old selection after a failed target read; restore the same source only while this
              // fork still owns the foreground request, so a late error cannot steal a later user selection.
              const sourceSelectionEpoch = foregroundRequestRef.current + 1;
              await select(sourceThreadId);
              if (
                currentThreadIdRef.current !== sourceThreadId ||
                foregroundRequestRef.current !== sourceSelectionEpoch ||
                historyRuntimeAdmissionRef.current !== admission ||
                catalogScopeKey(workspaceRef.current) !==
                  (sourceThread.workspaceKind === "session" ? "session" : sourceThread.workspaceId)
              )
                return "deferred";
            }
            publishForkReplyState(anchor, "open_failed");
            preserveView = true;
            return "open_failed";
          }
          return "deferred";
        } catch (error) {
          const code = compactionErrorCode(error);
          if (code === "CONFLICT") {
            forkAttemptsRef.current.delete(attemptKey);
            if (currentThreadIdRef.current === sourceThreadId) {
              try {
                await readCurrentThreadRevision(sourceThreadId);
              } catch {
                // Keep the original conflict as the visible result if the refresh is also unavailable.
              }
            }
            publishForkReplyState(anchor, "conflict");
            preserveView = true;
          } else if (code === "OPERATION_UNCONFIRMED") {
            // Keep the original tuple so a retry can reconcile/replay the same idempotent operation key.
            publishForkReplyState(anchor, "unconfirmed");
            preserveView = true;
          }
          throw error;
        } finally {
          if (!preserveView) publishForkReplyState(anchor, undefined);
        }
      })();
      forkInFlightRef.current.set(attemptKey, request);
      void request.then(
        () => {
          if (forkInFlightRef.current.get(attemptKey) === request)
            forkInFlightRef.current.delete(attemptKey);
        },
        () => {
          if (forkInFlightRef.current.get(attemptKey) === request)
            forkInFlightRef.current.delete(attemptKey);
        },
      );
      return request;
    },
    [history, publishForkReplyState, readCurrentThreadRevision, rememberForkedThread, select],
  );

  /** 单 Item 正文由共享 TimelineStore 缓存；每页校验 Thread/Workspace，切换后不交付旧内容。 */
  const hydrateItemContent = useCallback(
    (threadId: string, itemId: string, isCurrent: () => boolean): Promise<string> =>
      useTimelineStore
        .getState()
        .hydrateItemContent(threadId, itemId, (input) => history.itemContentRead(input), isCurrent),
    [history],
  );

  /** 只有用户主动展开或复制时才分页物化正文；快照外的旧 Thread/Item 不能借用当前身份。 */
  const readMessageContent = useCallback(
    async (requestedThreadId: string, itemId: string): Promise<string> => {
      const threadId = currentThreadIdRef.current;
      const workspaceId = workspaceRef.current?.workspaceId;
      if (threadId === undefined || requestedThreadId !== threadId)
        throw new Error("完整内容暂时不可用。");
      return hydrateItemContent(
        threadId,
        itemId,
        () =>
          currentThreadIdRef.current === threadId &&
          workspaceRef.current?.workspaceId === workspaceId,
      );
    },
    [hydrateItemContent],
  );

  /** 最终答复可跨多页模型步骤；片段与组合结果均进入 TimelineStore 的 Thread 作用域缓存。 */
  const readAnswerContent = useCallback(
    async (finalItemId: string, minimumRevision?: number, turnId?: string): Promise<string> => {
      const threadId = currentThreadIdRef.current;
      const workspaceId = workspaceRef.current?.workspaceId;
      if (threadId === undefined) throw new Error("完整回复暂时不可用。");
      const isCurrent = () =>
        currentThreadIdRef.current === threadId &&
        workspaceRef.current?.workspaceId === workspaceId;
      return useTimelineStore
        .getState()
        .hydrateAnswerContent(
          threadId,
          finalItemId,
          () =>
            readFullAnswerContent(
              history,
              threadId,
              finalItemId,
              isCurrent,
              minimumRevision,
              turnId,
              hydrateItemContent,
            ),
          isCurrent,
        );
    },
    [history, hydrateItemContent],
  );

  /** 目录类 CAS 只允许一次权威重读和有界重试，持续竞争会原样失败给 UI。 */
  const retryThreadMutation = useCallback(
    async <T>(
      threadId: string,
      revision: number,
      invoke: (expectedThreadRevision: number) => Promise<T>,
    ): Promise<T> => {
      try {
        return await invoke(revision);
      } catch (failure) {
        if (compactionErrorCode(failure) !== "CONFLICT") throw failure;
        return invoke(await rereadThreadRevision(threadId));
      }
    },
    [rereadThreadRevision],
  );

  /**
   * 终态内容完成一次 React commit 后才提交已读 CAS；失败不清除目录提醒，CONFLICT 只允许
   * 一次权威重读和重放，避免后台确认与 Pin/Archive 并发时形成无限请求环。
   */
  const markLatestTurnSeen = useCallback(
    async (threadId: string): Promise<boolean> => {
      const revision = threadRevision(threadId);
      const guard = `seen:${threadId}`;
      if (revision === undefined || threadMutationGuardsRef.current.has(guard)) return false;
      threadMutationGuardsRef.current.add(guard);
      try {
        const seen = await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadSeen({ threadId, expectedThreadRevision }),
        );
        if (mountedRef.current) {
          setThreads((current) => {
            const next = mergeThreadProjection(current, seen);
            threadsRef.current = next;
            return next;
          });
          // threadSeen 是 Thread CAS mutation；它的 ACK 可能在终态事件后再推进一版 revision，
          // 因此要同步 Timeline 的 revision fence，避免下一次恢复操作继续使用旧终态版本。
          useTimelineStore.getState().recordThreadMetadataRevision(threadId, seen.revision);
        }
        return true;
      } catch {
        if (mountedRef.current && currentThreadIdRef.current === threadId) {
          setError("未读状态暂时无法同步，请重新打开会话重试。 ");
        }
        return false;
      } finally {
        threadMutationGuardsRef.current.delete(guard);
      }
    },
    [history, retryThreadMutation, setError, threadRevision],
  );

  /** 重新读取当前 Workspace 的 active 列表，确保 Pin 排序完全服从服务端 pinned_at 规则。 */
  const refreshActiveThreads = useCallback(async (): Promise<void> => {
    const selected = workspaceRef.current;
    const scopeKey = catalogScopeKey(selected);
    const issuedEpoch = directoryMutationEpochRef.current;
    const listed = await history.threadList(
      scopeKey === "session"
        ? { workspaceKind: "session", limit: 200 }
        : { workspaceId: scopeKey, limit: 200 },
    );
    if (!mountedRef.current || catalogScopeKey(workspaceRef.current) !== scopeKey) return;
    setThreads((current) => {
      const next = mergeThreadListResponse(
        current,
        scopeKey,
        listed.items,
        issuedEpoch,
        localCreationEpochsRef.current,
      );
      threadsRef.current = next;
      return next;
    });
    for (const [threadId, creation] of localCreationEpochsRef.current) {
      if (creation.scopeKey === scopeKey && creation.epoch <= issuedEpoch)
        localCreationEpochsRef.current.delete(threadId);
    }
  }, [history]);

  /** Pin 成功后才重排；失败时保留原行投影，不做乐观反转。 */
  const pin = useCallback(
    async (threadId: string, pinned: boolean): Promise<void> => {
      const revision = threadRevision(threadId);
      const guard = `pin:${threadId}`;
      if (revision === undefined || threadMutationGuardsRef.current.has(guard)) return;
      threadMutationGuardsRef.current.add(guard);
      setThreadMutationPending(threadId, true);
      try {
        await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadPin({ threadId, pinned, expectedThreadRevision }),
        );
        await refreshActiveThreads();
      } finally {
        threadMutationGuardsRef.current.delete(guard);
        setThreadMutationPending(threadId, false);
      }
    },
    [history, refreshActiveThreads, retryThreadMutation, setThreadMutationPending, threadRevision],
  );

  /** 归档成功后才移除行；当前项改选原数组中的相邻项，无相邻项时回到既有空会话界面。 */
  const archive = useCallback(
    async (threadId: string): Promise<ConversationArchiveUndo | undefined> => {
      const current = threadsRef.current;
      const index = current.findIndex((thread) => thread.threadId === threadId);
      const revision = threadRevision(threadId);
      const guard = `archive:${threadId}`;
      if (index < 0 || revision === undefined || threadMutationGuardsRef.current.has(guard)) return;
      threadMutationGuardsRef.current.add(guard);
      setThreadMutationPending(threadId, true);
      try {
        const archived = await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadArchive({ threadId, expectedThreadRevision }),
        );
        if (!mountedRef.current) return undefined;
        const restoreSelection = currentThreadIdRef.current === threadId;
        const remaining = threadsRef.current.filter((thread) => thread.threadId !== threadId);
        threadsRef.current = remaining;
        setThreads(remaining);
        if (restoreSelection) {
          const neighbor = remaining[Math.min(index, remaining.length - 1)];
          setCurrentThreadId(neighbor?.threadId);
          if (neighbor !== undefined) void select(neighbor.threadId);
          else {
            void switchObservedThread(undefined).catch(() => undefined);
            useTimelineStore.getState().reset();
          }
        }
        return { archived, restoreSelection };
      } finally {
        threadMutationGuardsRef.current.delete(guard);
        setThreadMutationPending(threadId, false);
      }
    },
    [
      history,
      retryThreadMutation,
      select,
      setThreadMutationPending,
      switchObservedThread,
      threadRevision,
    ],
  );

  /** Restore 先权威读取归档 revision；成功后刷新 active 列表，并按调用意图恢复选择。 */
  const restore = useCallback(
    async (threadId: string, open: boolean): Promise<void> => {
      const guard = `restore:${threadId}`;
      if (threadMutationGuardsRef.current.has(guard)) return;
      threadMutationGuardsRef.current.add(guard);
      setThreadMutationPending(threadId, true);
      try {
        const revision = await rereadThreadRevision(threadId);
        const restored = await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadRestore({ threadId, expectedThreadRevision }),
        );
        if (!mountedRef.current) return;
        await refreshActiveThreads();
        if (open) {
          setThreads((current) => {
            const next = mergeThreadProjection(current, restored);
            threadsRef.current = next;
            return next;
          });
          await select(threadId);
        }
      } finally {
        threadMutationGuardsRef.current.delete(guard);
        setThreadMutationPending(threadId, false);
      }
    },
    [
      history,
      refreshActiveThreads,
      rereadThreadRevision,
      retryThreadMutation,
      select,
      setThreadMutationPending,
    ],
  );

  /**
   * 人工标题以 Thread identity 做 single-flight，并只接受服务端 CAS 后返回的完整投影；
   * 这保证 manual title 永远不会由 renderer 的乐观状态冒充成功。
   */
  const rename = useCallback(
    async (threadId: string, title: string): Promise<void> => {
      const normalized = title.trim();
      const revision = threadRevision(threadId);
      const guard = `rename:${threadId}`;
      if (normalized === "" || revision === undefined || threadMutationGuardsRef.current.has(guard))
        return;
      threadMutationGuardsRef.current.add(guard);
      try {
        const renamed = await history.threadRename({
          threadId,
          title: normalized,
          expectedThreadRevision: revision,
        });
        if (!mountedRef.current) return;
        setThreads((current) => {
          const next = mergeThreadProjection(current, renamed);
          threadsRef.current = next;
          return next;
        });
      } finally {
        threadMutationGuardsRef.current.delete(guard);
      }
    },
    [history, threadRevision],
  );

  /**
   * Provider、Model、Reasoning 与 Access 作为完整偏好一次 CAS 替换；若事件投影暂时落后，
   * 只在首次 CONFLICT 后读取一次权威 snapshot 并以新 revision 重放。单次重放既能收敛
   * 取消/终态后的事件窗口，也不会掩盖持续并发写入或形成无限重试。
   */
  const updatePreferences = useCallback(
    async (input: {
      providerId: string;
      modelId: string;
      reasoningLevel: ReasoningLevel | null;
      accessMode: ConversationAccessMode;
      collaborationMode: ConversationCollaborationMode;
    }): Promise<void> => {
      const threadId = currentThreadIdRef.current;
      const revision = threadId === undefined ? undefined : threadRevision(threadId);
      const guard = threadId === undefined ? "" : `preferences:${threadId}`;
      if (
        threadId === undefined ||
        revision === undefined ||
        threadMutationGuardsRef.current.has(guard)
      )
        return;
      threadMutationGuardsRef.current.add(guard);
      try {
        let updated: ConversationThread;
        try {
          updated = await history.threadPreferencesUpdate({
            threadId,
            ...input,
            expectedThreadRevision: revision,
          });
        } catch (failure) {
          if (compactionErrorCode(failure) !== "CONFLICT") throw failure;
          const selected = workspaceRef.current;
          if (selected === undefined || currentThreadIdRef.current !== threadId) throw failure;
          const { snapshot, olderCursor, readFence } = await readCompleteThreadSnapshot(
            threadId,
            captureSnapshotReadFence(selected.workspaceId),
          );
          if (
            !mountedRef.current ||
            currentThreadIdRef.current !== threadId ||
            !applySnapshot(snapshot, selected.workspaceId, threadId, olderCursor, readFence)
          )
            throw failure;
          updated = await history.threadPreferencesUpdate({
            threadId,
            ...input,
            expectedThreadRevision: snapshot.revision,
          });
        }
        if (!mountedRef.current || currentThreadIdRef.current !== threadId) return;
        setThreads((current) => {
          const next = mergeThreadProjection(current, updated);
          threadsRef.current = next;
          return next;
        });
      } finally {
        threadMutationGuardsRef.current.delete(guard);
      }
    },
    [applySnapshot, captureSnapshotReadFence, history, readCompleteThreadSnapshot, threadRevision],
  );

  /**
   * 以当前权威 Timeline revision 发起单次 CAS 压缩；响应只用于反馈，Checkpoint 与 revision
   * 真相仍由 Java/Rust event 投影更新，重复点击通过独立 request fence 合并。
   */
  const compact = useCallback(async (): Promise<void> => {
    const threadId = currentThreadIdRef.current;
    if (
      threadId === undefined ||
      activeTurnPresent ||
      compaction.phase === "running" ||
      compactionInFlightRef.current.has(threadId)
    )
      return;
    const revision =
      useTimelineStore.getState().threadRevisionByThread[threadId] ??
      threads.find((thread) => thread.threadId === threadId)?.revision;
    if (revision === undefined) {
      setCompaction({
        phase: "error",
        message: "对话状态尚未就绪，请重新打开会话。",
        retryable: false,
      });
      return;
    }
    const request = (compactionRequestRef.current.get(threadId) ?? 0) + 1;
    compactionRequestRef.current.set(threadId, request);
    compactionInFlightRef.current.add(threadId);
    setCompaction({
      phase: "running",
      message: "正在压缩上下文…",
      retryable: false,
      cancellable: true,
    });
    try {
      const result = await history.threadCompact({
        threadId,
        expectedThreadRevision: revision,
      });
      if (
        !mountedRef.current ||
        compactionRequestRef.current.get(threadId) !== request ||
        currentThreadIdRef.current !== threadId
      )
        return;
      setCompaction(
        result.outcome === "unchanged"
          ? { phase: "success", message: "当前对话无需压缩。", retryable: false }
          : { phase: "success", message: compactedMessage(result), retryable: false },
      );
    } catch (failure) {
      if (
        !mountedRef.current ||
        compactionRequestRef.current.get(threadId) !== request ||
        currentThreadIdRef.current !== threadId
      )
        return;
      if (compactionErrorCode(failure) === "CONFLICT") {
        const selected = workspaceRef.current;
        if (selected !== undefined) {
          try {
            const { snapshot, olderCursor, readFence } = await readCompleteThreadSnapshot(
              threadId,
              captureSnapshotReadFence(selected.workspaceId),
            );
            if (
              mountedRef.current &&
              compactionRequestRef.current.get(threadId) === request &&
              currentThreadIdRef.current === threadId
            )
              applySnapshot(snapshot, selected.workspaceId, threadId, olderCursor, readFence);
          } catch {
            // 重读失败不覆盖原始稳定冲突语义；下一次显式选择仍可恢复 authoritative snapshot。
          }
        }
      }
      const feedback = compactionError(failure);
      setCompaction({ phase: "error", ...feedback });
    } finally {
      if (compactionRequestRef.current.get(threadId) === request)
        compactionInFlightRef.current.delete(threadId);
    }
  }, [
    activeTurnPresent,
    applySnapshot,
    captureSnapshotReadFence,
    compaction.phase,
    history,
    readCompleteThreadSnapshot,
    threads,
  ]);

  /** 停止仅作用于当前连接的手动压缩；受理回执不是最终结果，仍等待原请求或事件收口。 */
  const cancelCompaction = useCallback(async (): Promise<void> => {
    const threadId = currentThreadIdRef.current;
    if (threadId === undefined || !compactionInFlightRef.current.has(threadId)) return;
    const request = compactionRequestRef.current.get(threadId);
    try {
      const result = await history.threadCompactCancel({ threadId });
      if (
        mountedRef.current &&
        currentThreadIdRef.current === threadId &&
        compactionRequestRef.current.get(threadId) === request &&
        result.accepted
      )
        setCompaction({
          phase: "running",
          message: "正在停止上下文压缩…",
          retryable: false,
          cancellable: true,
        });
    } catch {
      if (
        mountedRef.current &&
        currentThreadIdRef.current === threadId &&
        compactionRequestRef.current.get(threadId) === request
      )
        setCompaction({
          phase: "running",
          message: "停止请求失败，请重试。",
          retryable: false,
          cancellable: true,
        });
    }
  }, [history]);

  /** 用户关闭的仅是 Renderer 反馈；不会取消或改写服务端压缩生命周期。 */
  const dismissCompactionFeedback = useCallback((): void => {
    if (compaction.phase !== "running") setCompaction({ phase: "idle", retryable: false });
  }, [compaction.phase]);

  /** 观察释放绑定 acquisition owner；新 owner 的恢复先排在旧 owner 的 unobserve 之后。 */
  useLayoutEffect(() => {
    const owner = observationOwner;
    observationOwnerRef.current = owner;
    return () => {
      if (observationOwnerRef.current === owner) observationOwnerRef.current = undefined;
      owner.observedThreadId = undefined;
      const cleanup = observationChainRef.current.then(async () => {
        for (const observedThreadId of owner.ownedThreadIds) {
          try {
            const result = await owner.history.threadUnobserve({ threadId: observedThreadId });
            if (result.accepted && result.threadId === observedThreadId)
              owner.ownedThreadIds.delete(observedThreadId);
          } catch {
            // Preserve the exact owner's unresolved subscription without leaking cleanup rejection.
          }
        }
      });
      observationChainRef.current = cleanup.catch(() => undefined);
    };
  }, [observationOwner]);

  /** 挂载 fence 统一阻止晚到 history promise 写入已卸载 controller。 */
  useEffect(() => {
    const threadMutationGuards = threadMutationGuardsRef.current;
    const seenAttemptKeys = seenAttemptKeysRef.current;
    const automaticResyncRetryTimers = automaticResyncRetryTimersRef.current;
    const automaticResyncInFlight = automaticResyncInFlightRef.current;
    const automaticResyncRetryDelays = automaticResyncRetryDelayRef.current;
    const automaticResyncRecoveryTokens = automaticResyncRecoveryTokenRef.current;
    const automaticResyncActiveRequests = automaticResyncActiveRequestRef.current;
    const compactionRequests = compactionRequestRef.current;
    const compactionsInFlight = compactionInFlightRef.current;
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      foregroundRequestRef.current += 1;
      automaticResyncRequestRef.current += 1;
      compactionRequests.clear();
      compactionsInFlight.clear();
      threadMutationGuards.clear();
      seenAttemptKeys.clear();
      for (const timer of automaticResyncRetryTimers.values()) clearTimeout(timer);
      automaticResyncRetryTimers.clear();
      automaticResyncRetryDelays.clear();
      automaticResyncInFlight.clear();
      for (const token of automaticResyncRecoveryTokens.values())
        useTimelineStore.getState().cancelRecovery(token);
      automaticResyncRecoveryTokens.clear();
      automaticResyncActiveRequests.clear();
      automaticResyncScopeRef.current = undefined;
    };
  }, []);

  /** 连接断开后原订阅自动失效；即使后台实例未变，重连也必须重新建立观察。 */
  useEffect(() => {
    if (runtimeState === undefined || !["ready", "busy"].includes(runtimeState.status)) {
      observationOwner.observedThreadId = undefined;
      observationOwner.ownedThreadIds.clear();
    }
  }, [observationOwner, runtimeState]);

  /**
   * 标题事件按当前目录 scope 校验；session 分类接受任一 session root 的事件并回读聚合列表，
   * 项目分类仍严格限制 workspaceId，绝不从 event 单独补造 Thread。
   */
  useEffect(() => {
    if (metadataEvent?.method !== "thread/metadata-changed") return undefined;
    const params = metadataEvent.params;
    const selected = workspaceRef.current;
    const scopeKey = catalogScopeKey(selected);
    const runtime = runtimeStateRef.current;
    if (
      runtime === undefined ||
      !["ready", "busy"].includes(runtime.status) ||
      params.generation !== runtime.generation ||
      params.serverInstanceId !== runtime.serverInstanceId ||
      (scopeKey !== "session" && params.workspaceId !== scopeKey)
    )
      return undefined;

    const existing = threadsRef.current.find((thread) => thread.threadId === params.threadId);
    if (
      existing !== undefined &&
      (existing.workspaceId !== params.workspaceId ||
        (scopeKey === "session" && existing.workspaceKind !== "session"))
    )
      return undefined;
    if (existing !== undefined && params.revision <= existing.revision) return undefined;
    if (existing !== undefined && existing.preferences !== null) {
      setThreads((current) => {
        const latest = current.find((thread) => thread.threadId === params.threadId);
        if (
          latest === undefined ||
          latest.preferences === null ||
          params.revision <= latest.revision
        )
          return current;
        const next = current.map((thread) => {
          if (thread.threadId !== params.threadId || thread.preferences === null) return thread;
          return {
            ...thread,
            title: params.title,
            revision: params.revision,
            updatedAt: params.occurredAt,
            preferences: { ...thread.preferences, titleSource: params.titleSource },
          };
        });
        threadsRef.current = next;
        return next;
      });
      useTimelineStore.getState().recordThreadMetadataRevision(params.threadId, params.revision);
      return undefined;
    }

    let active = true;
    const issuedEpoch = directoryMutationEpochRef.current;
    void history
      .threadList(
        scopeKey === "session"
          ? { workspaceKind: "session", limit: 200 }
          : { workspaceId: scopeKey, limit: 200 },
      )
      .then((listed) => {
        const currentRuntime = runtimeStateRef.current;
        if (
          !active ||
          !mountedRef.current ||
          catalogScopeKey(workspaceRef.current) !== scopeKey ||
          currentRuntime?.generation !== params.generation ||
          currentRuntime.serverInstanceId !== params.serverInstanceId ||
          listed.items.some((thread) =>
            scopeKey === "session"
              ? thread.workspaceKind !== "session"
              : thread.workspaceId !== scopeKey,
          )
        )
          return;
        const authoritative = listed.items.find((thread) => thread.threadId === params.threadId);
        if (authoritative === undefined || authoritative.revision < params.revision) return;
        setThreads((current) => {
          const next = mergeThreadListResponse(
            current,
            scopeKey,
            listed.items,
            issuedEpoch,
            localCreationEpochsRef.current,
          );
          threadsRef.current = next;
          return next;
        });
        for (const [threadId, creation] of localCreationEpochsRef.current) {
          if (creation.scopeKey === scopeKey && creation.epoch <= issuedEpoch)
            localCreationEpochsRef.current.delete(threadId);
        }
        useTimelineStore
          .getState()
          .recordThreadMetadataRevision(authoritative.threadId, authoritative.revision);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [history, metadataEvent]);

  /**
   * 副作用发生在当前 Timeline 与侧栏终态提醒完成提交之后，因此只有当前会话才会
   * 确认已读；非活动会话保留未读标记，实时 queued/running/approval/suspended 状态不受影响。
   */
  useEffect(() => {
    if (currentThreadId === undefined || currentLatestTerminalKey === undefined) return;
    const thread = threads.find((candidate) => candidate.threadId === currentThreadId);
    const directoryCoversTerminal =
      thread !== undefined &&
      thread.latestTurnSeen &&
      thread.latestTurnStatus === currentLatestTurnStatus &&
      (currentTimelineThreadRevision === undefined ||
        currentTimelineThreadRevision <= thread.revision);
    if (
      thread === undefined ||
      directoryCoversTerminal ||
      (currentLatestTurnStatus !== "completed" && currentLatestTurnStatus !== "failed")
    )
      return;
    const attemptKey = `${currentThreadId}:${currentLatestTerminalKey}`;
    if (seenAttemptKeysRef.current.has(attemptKey)) return;
    seenAttemptKeysRef.current.add(attemptKey);
    void markLatestTurnSeen(currentThreadId).then((succeeded) => {
      // 只释放失败的精确 terminal identity；成功键继续去重，避免无关重渲染重复确认已读。
      if (!succeeded) seenAttemptKeysRef.current.delete(attemptKey);
    });
  }, [
    currentLatestTerminalKey,
    currentLatestTurnStatus,
    currentTimelineThreadRevision,
    currentThreadId,
    markLatestTurnSeen,
    threads,
  ]);

  /** 统一消费自动与手动 Context event；命令响应只在 event 尚未到达时提供同口径反馈。 */
  useEffect(() => {
    if (observedCompaction === undefined) {
      setCompaction(
        currentThreadId !== undefined && compactionInFlightRef.current.has(currentThreadId)
          ? { phase: "running", message: "正在压缩上下文…", retryable: false, cancellable: true }
          : { phase: "idle", retryable: false },
      );
      return;
    }
    if (observedCompaction.phase === "started") {
      setCompaction((previous) => ({
        phase: "running",
        message: previous.message === "正在停止上下文压缩…" ? previous.message : "正在压缩上下文…",
        retryable: false,
        cancellable:
          observedCompaction.trigger === "manual" &&
          compactionInFlightRef.current.has(observedCompaction.threadId),
      }));
      return;
    }
    if (observedCompaction.phase === "compacted") {
      if (
        observedCompaction.inputTokensBefore === null ||
        observedCompaction.inputTokensAfter === null
      ) {
        setCompaction({
          phase: "error",
          message: "对话上下文状态异常，请重新打开会话。",
          retryable: false,
        });
        return;
      }
      setCompaction({
        phase: "success",
        message: compactedMessage({
          inputTokensBefore: observedCompaction.inputTokensBefore,
          inputTokensAfter: observedCompaction.inputTokensAfter,
        }),
        retryable: false,
      });
      return;
    }
    const feedback = compactionError({ code: observedCompaction.errorCode });
    setCompaction({ phase: "error", ...feedback });
  }, [currentThreadId, observedCompaction]);

  /** 每次目录或选择提交后更新当前列表 scope 缓存；session cache 保留所有会话行。 */
  useEffect(() => {
    const currentScopeKey = catalogScopeKey(workspaceRef.current);
    if (historyScopeKeyRef.current !== currentScopeKey) return;
    rememberWorkspaceHistory(currentScopeKey, threads, currentThreadId);
  }, [currentThreadId, rememberWorkspaceHistory, threads]);

  /**
   * workspace/scope commit、首次模型选择或 ready generation 到达后恢复列表；空白与活动 session
   * 都读取 kind-filtered session catalog，只有项目首次进入才自动创建会话。pending 激活按发起时
   * 的 generation/server identity 归属：同身份的迟到目录投影不能抢请求，新身份则必须撤销旧意图。
   * @author kongweiguang
   */
  useLayoutEffect(() => {
    const nextWorkspaceId = workspace?.workspaceId;
    const nextScopeKey = catalogScopeKey(workspace);
    if (
      creationInFlightRef.current !== undefined &&
      historyWorkspaceIdRef.current === nextWorkspaceId
    ) {
      // 创建与首份 snapshot 已覆盖同一 workspace 的目录变更；消费 revision 可避免创建收尾后
      // 因被暂缓的壳层投影更新重复读取 session/project 列表，runtime 与模型变化仍由各自 fence 处理。
      historyWorkspaceRevisionRef.current = workspaceRevision;
      return;
    }

    const observationOwnerChanged = historyObservationOwnerRef.current !== observationOwner;
    historyObservationOwnerRef.current = observationOwner;

    const previousWorkspaceId = historyWorkspaceIdRef.current;
    const previousScopeKey = historyScopeKeyRef.current;
    const workspaceChanged = previousWorkspaceId !== nextWorkspaceId;
    const scopeChanged = previousScopeKey !== nextScopeKey;
    const firstWorkspaceAdmission = previousScopeKey === undefined;
    const runtimeAdmissionChanged =
      historyRuntimeAdmission !== undefined &&
      historyRuntimeAdmissionRef.current !== undefined &&
      historyRuntimeAdmission !== historyRuntimeAdmissionRef.current;
    const runtimeBecameAvailable =
      historyRuntimeAdmission !== undefined && historyRuntimeAdmissionRef.current === undefined;
    const workspaceRevisionChanged = historyWorkspaceRevisionRef.current !== workspaceRevision;
    const pendingWorkspaceId = pendingWorkspaceActivationRef.current;
    const pendingActivationAdmission = pendingWorkspaceActivationAdmissionRef.current;
    const pendingActivationRuntimeChanged =
      pendingWorkspaceId !== undefined &&
      pendingActivationAdmission !== undefined &&
      historyRuntimeAdmission !== undefined &&
      pendingActivationAdmission !== historyRuntimeAdmission;
    const pendingActivationNavigatedAway =
      pendingWorkspaceId !== undefined &&
      workspaceChanged &&
      nextWorkspaceId !== pendingWorkspaceId;
    if (pendingActivationRuntimeChanged || pendingActivationNavigatedAway) {
      // 真正的 SID/generation 换代或导航离开目标 Workspace 都撤销旧 intent；revision-only投影不取消它。
      if (pendingWorkspaceActivationRef.current === pendingWorkspaceId) {
        pendingWorkspaceActivationRef.current = undefined;
        pendingWorkspaceActivationAdmissionRef.current = undefined;
      }
      if (manualWorkspaceTargetRef.current === pendingWorkspaceId)
        manualWorkspaceTargetRef.current = undefined;
      const staleThreadId = pendingThreadActivationRef.current;
      if (staleThreadId !== undefined) {
        if (manualThreadTargetRef.current === staleThreadId)
          manualThreadTargetRef.current = undefined;
        pendingThreadActivationRef.current = undefined;
      }
    }
    const pendingActivationForCurrentRuntime =
      pendingWorkspaceActivationRef.current !== undefined &&
      (pendingWorkspaceActivationAdmissionRef.current === undefined ||
        historyRuntimeAdmission === undefined ||
        pendingWorkspaceActivationAdmissionRef.current === historyRuntimeAdmission);
    const manualWorkspaceActivation =
      workspace !== undefined && pendingWorkspaceActivationRef.current === workspace.workspaceId;
    const modelBecameAvailable = modelSelectionAvailable && !historyModelAvailableRef.current;
    const shouldRefresh =
      firstWorkspaceAdmission ||
      scopeChanged ||
      workspaceChanged ||
      observationOwnerChanged ||
      runtimeAdmissionChanged ||
      runtimeBecameAvailable ||
      workspaceRevisionChanged ||
      modelBecameAvailable;
    historyWorkspaceRevisionRef.current = workspaceRevision;
    historyModelAvailableRef.current = modelSelectionAvailable;
    if (!shouldRefresh) return;

    if (runtimeAdmissionChanged) {
      workspaceHistoryCacheRef.current.clear();
      useTimelineStore.getState().reset();
      // 新后台连接上的订阅集合为空；相同 Thread ID 也必须重新 observe 再读取基线。
      observationOwner.observedThreadId = undefined;
      observationOwner.ownedThreadIds.clear();
    } else if (scopeChanged && previousScopeKey !== undefined) {
      rememberWorkspaceHistory(previousScopeKey, threadsRef.current, currentThreadIdRef.current);
    }
    if (historyRuntimeAdmission !== undefined)
      historyRuntimeAdmissionRef.current = historyRuntimeAdmission;
    historyWorkspaceIdRef.current = nextWorkspaceId;
    historyScopeKeyRef.current = nextScopeKey;
    // 新 Thread 的 session activation 是当前 create/select 的一部分，不能用同 Runtime 的 Workspace 投影
    // 刷新抢占其 snapshot request token；普通导航和旧身份结果仍推进 fence 取消旧读取。
    if (!manualWorkspaceActivation && !pendingActivationForCurrentRuntime)
      foregroundRequestRef.current += 1;
    setError(undefined);
    setBackgroundError(undefined);
    if (scopeChanged || workspaceChanged || firstWorkspaceAdmission || runtimeAdmissionChanged) {
      setCompaction({ phase: "idle", retryable: false });
      seenAttemptKeysRef.current.clear();
      const cached = runtimeAdmissionChanged ? undefined : cachedWorkspaceHistory(nextScopeKey);
      const liveSessionThreads =
        (manualWorkspaceActivation || pendingActivationForCurrentRuntime) &&
        nextScopeKey === "session"
          ? workspaceThreads(nextScopeKey, threadsRef.current)
          : undefined;
      const cachedThreads = liveSessionThreads ?? cached?.threads ?? [];
      const manualThreadId = pendingThreadActivationRef.current;
      // “无项目对话”是聚合列表而不是已选 Thread；空白分类必须清空 composer owner，
      // 防止上次打开的 session 被误当作当前会话继续写入。
      const preferredThreadId =
        workspace === undefined
          ? undefined
          : manualThreadId !== undefined &&
              cachedThreads.some((thread) => thread.threadId === manualThreadId)
            ? manualThreadId
            : cached?.currentThreadId;
      if (preferredThreadId === undefined && !manualWorkspaceActivation)
        void switchObservedThread(undefined).catch(() => undefined);
      threadsRef.current = cachedThreads;
      currentThreadIdRef.current = preferredThreadId;
      setThreads(cachedThreads);
      setCurrentThreadId(preferredThreadId);
      if (manualWorkspaceActivation) {
        pendingWorkspaceActivationRef.current = undefined;
        pendingWorkspaceActivationAdmissionRef.current = undefined;
        pendingThreadActivationRef.current = undefined;
      }
    }
    const admittedRuntime = runtimeStateRef.current;
    if (
      (firstWorkspaceAdmission || runtimeAdmissionChanged || runtimeBecameAvailable) &&
      admittedRuntime !== undefined &&
      ["ready", "busy"].includes(admittedRuntime.status)
    ) {
      // 首次 ready 可能晚于 Workspace admission；先发布 generation/server identity，snapshot fence 才能验证基线。
      useTimelineStore.getState().applyRuntimeStatus(admittedRuntime);
    }
    const admittedSelection = modelSelectionRef.current;
    if (historyRuntimeAdmission === undefined) {
      setBusy(false);
      return;
    }
    if (manualWorkspaceActivation || pendingActivationForCurrentRuntime) return;
    void loadWorkspaceHistory(
      workspace,
      admittedSelection,
      currentThreadIdRef.current,
      nextScopeKey,
      workspaceRevision,
      historyRuntimeAdmission,
    );
  }, [
    cachedWorkspaceHistory,
    historyRuntimeAdmission,
    loadWorkspaceHistory,
    modelSelectionAvailable,
    observationOwner,
    rememberWorkspaceHistory,
    setError,
    switchObservedThread,
    workspace,
    workspaceRevision,
  ]);

  /** 每次 Runtime 准入后核对 fork journal；未知操作保留原 tuple，已提交回执只更新来源 owner 的目录，不抢当前选择。 @author kongweiguang */
  useEffect(() => {
    const recover = history.recoverThreadForks;
    const admission = historyRuntimeAdmission;
    if (recover === undefined || admission === undefined) return undefined;
    const timeline = useTimelineStore.getState();
    const generation = timeline.handshake.generation;
    const serverInstanceId = timeline.serverInstanceId;
    let active = true;
    void recover()
      .then((recoveries: readonly ConversationThreadForkRecovery[]) => {
        const currentTimeline = useTimelineStore.getState();
        if (
          !active ||
          !mountedRef.current ||
          historyRuntimeAdmissionRef.current !== admission ||
          currentTimeline.handshake.generation !== generation ||
          currentTimeline.serverInstanceId !== serverInstanceId
        )
          return;
        for (const recovery of recoveries) {
          const identity = {
            threadId: recovery.input.sourceThreadId,
            itemId: recovery.input.sourceItemId,
          };
          const key = forkReplyKey(identity);
          if (recovery.state === "pending") {
            if (!forkAttemptsRef.current.has(key))
              forkAttemptsRef.current.set(key, { input: recovery.input });
            publishForkReplyState(identity, "unconfirmed");
            continue;
          }
          if (!forkReceiptMatches(recovery.input, recovery.result)) continue;
          const ownerThreads = [
            ...threadsRef.current,
            ...[...workspaceHistoryCacheRef.current.values()].flatMap((entry) => entry.threads),
          ];
          const sourceThread = ownerThreads.find(
            (thread) => thread.threadId === recovery.input.sourceThreadId,
          );
          if (
            sourceThread !== undefined &&
            (sourceThread.workspaceId !== recovery.result.thread.workspaceId ||
              sourceThread.workspaceKind !== recovery.result.thread.workspaceKind)
          )
            continue;
          forkAttemptsRef.current.set(key, {
            input: recovery.input,
            result: recovery.result,
          });
          publishForkReplyState(identity, "created");
          rememberForkedThread(sourceThread, recovery.result.thread, admission);
        }
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [history, historyRuntimeAdmission, publishForkReplyState, rememberForkedThread]);

  /**
   * timeline 出现 gap、恢复 active Turn 或 terminal 需要补齐 ChangeSet 时，同一 Thread 同时只
   * 保留一个 thread/read。该后台对账拥有独立 request epoch，不参与 foreground busy/focus，健康
   * 快照走普通 applySnapshot，真实 gap 才交给 Timeline recovery buffer 原子提交并重放 live event。
   * 暂时读失败仍会退避重试，因此只发布“正在恢复”状态，不误报为需要用户手动重选的终态错误。
   */
  useEffect(() => {
    const automaticResyncScope = backgroundErrorScope;
    if (automaticResyncScopeRef.current !== automaticResyncScope) {
      // Thread、workspace 或 runtime 代际任一变化都使旧 continuation 失效；清理 health 请求
      // 的 in-flight 标记同样重要，否则切回同一 Thread 时会被旧 promise 长时间挡住。
      automaticResyncScopeRef.current = automaticResyncScope;
      // 错误只属于旧 scope；成功切换 Thread、workspace 或 runtime 代际后立即隐藏它。
      setBackgroundError(undefined);
      automaticResyncRequestRef.current += 1;
      automaticResyncAttemptRef.current = undefined;
      for (const [threadId, requestEpoch] of automaticResyncActiveRequestRef.current) {
        const token = automaticResyncRecoveryTokenRef.current.get(threadId);
        if (token?.requestEpoch === requestEpoch) useTimelineStore.getState().cancelRecovery(token);
        automaticResyncRecoveryTokenRef.current.delete(threadId);
        automaticResyncActiveRequestRef.current.delete(threadId);
        automaticResyncInFlightRef.current.delete(threadId);
      }
      for (const timer of automaticResyncRetryTimersRef.current.values()) clearTimeout(timer);
      automaticResyncRetryTimersRef.current.clear();
      automaticResyncRetryDelayRef.current.clear();
    }
    for (const [threadId, timer] of automaticResyncRetryTimersRef.current) {
      if (threadId !== currentThreadId) {
        clearTimeout(timer);
        automaticResyncRetryTimersRef.current.delete(threadId);
        automaticResyncRetryDelayRef.current.delete(threadId);
      }
    }
    for (const [threadId, token] of automaticResyncRecoveryTokenRef.current) {
      if (threadId !== currentThreadId || workspace === undefined) {
        cancelAutomaticRecovery(threadId, token.requestEpoch);
      }
    }
    if (
      creationInFlightRef.current !== undefined ||
      currentThreadId === undefined ||
      currentResyncReason === undefined ||
      workspace === undefined
    ) {
      automaticResyncAttemptRef.current = undefined;
      return;
    }
    const timeline = useTimelineStore.getState();
    const key = [
      timeline.handshake.generation,
      timeline.serverInstanceId ?? "none",
      workspace.workspaceId,
      currentThreadId,
      currentResyncReason,
      currentResyncSequence,
    ].join(":");
    const resyncSequenceAtStart = currentResyncSequence;
    if (automaticResyncInFlightRef.current.has(currentThreadId)) return;
    if (automaticResyncAttemptRef.current === key) return;
    automaticResyncAttemptRef.current = key;
    automaticResyncInFlightRef.current.add(currentThreadId);
    const retryThreadId = currentThreadId;
    const retryWorkspaceId = workspace.workspaceId;
    const request = automaticResyncRequestRef.current + 1;
    automaticResyncRequestRef.current = request;
    automaticResyncActiveRequestRef.current.set(retryThreadId, request);
    const fence: ThreadSnapshotReadFence = {
      workspaceId: retryWorkspaceId,
      generation: timeline.handshake.generation,
      serverInstanceId: timeline.serverInstanceId ?? "",
    };
    const useRecoveryBuffer = requiresSnapshotBaseline(currentResyncReason);
    const recoveryToken = useRecoveryBuffer
      ? useTimelineStore.getState().beginRecovery(retryThreadId, request, "recovery")
      : undefined;
    const recoveryStarted = recoveryToken !== undefined;
    if (useRecoveryBuffer && !recoveryStarted) {
      if (automaticResyncActiveRequestRef.current.get(retryThreadId) === request)
        automaticResyncActiveRequestRef.current.delete(retryThreadId);
      automaticResyncInFlightRef.current.delete(currentThreadId);
      return;
    }
    if (recoveryToken !== undefined) {
      automaticResyncRecoveryTokenRef.current.set(retryThreadId, recoveryToken);
      automaticResyncActiveRequestRef.current.set(retryThreadId, request);
    }
    void (async (): Promise<void> => {
      let recoveryEnded = false;
      const currentAutomaticRequest = (): boolean => {
        const state = useTimelineStore.getState();
        return (
          mountedRef.current &&
          automaticResyncRequestRef.current === request &&
          currentThreadIdRef.current === retryThreadId &&
          workspaceRef.current?.workspaceId === retryWorkspaceId &&
          state.handshake.generation === fence.generation &&
          state.serverInstanceId === fence.serverInstanceId
        );
      };
      try {
        const { snapshot, olderCursor, readFence } = await readCompleteThreadSnapshot(
          retryThreadId,
          fence,
        );
        if (!currentAutomaticRequest()) return;
        const snapshotOptions =
          readFence?.taskActivityReadFence === undefined
            ? undefined
            : { taskActivityReadFence: readFence.taskActivityReadFence };
        if (recoveryToken !== undefined) {
          const result = useTimelineStore
            .getState()
            .endRecovery(recoveryToken, snapshot, retryWorkspaceId, snapshotOptions);
          recoveryEnded = true;
          // late/needs_baseline/overflow 等均表示本次 snapshot 没有接管当前事实；保留
          // resync 意图并进入同一条有界退避，不能把“读取成功但过旧”误当作收敛。
          if (result.status !== "applied") {
            throw new Error(`thread recovery ${result.status}`);
          }
        } else {
          const outcome = useTimelineStore
            .getState()
            .applySnapshot(snapshot, retryWorkspaceId, snapshotOptions);
          if (outcome !== "applied") throw new Error(`thread snapshot ${outcome}`);
        }
        if (useTimelineStore.getState().resyncRequired[retryThreadId] !== undefined)
          throw new Error("thread snapshot recovery remains pending");
        historyWindowRef.current = {
          threadId: retryThreadId,
          workspaceId: retryWorkspaceId,
          snapshot,
          olderCursor,
          seenCursors: new Set(),
        };
        setHasOlderHistory(olderCursor !== null);
        setBackgroundError(undefined);
        automaticResyncRetryDelayRef.current.delete(retryThreadId);
      } catch {
        if (currentAutomaticRequest()) {
          setBackgroundError({
            scope: automaticResyncScope,
            message: "会话状态正在自动恢复，请稍候。",
          });
          const prior = automaticResyncRetryTimersRef.current.get(retryThreadId);
          if (prior !== undefined) clearTimeout(prior);
          const delay =
            automaticResyncRetryDelayRef.current.get(retryThreadId) ??
            AUTOMATIC_RESYNC_INITIAL_DELAY_MS;
          automaticResyncRetryDelayRef.current.set(retryThreadId, nextAutomaticResyncDelay(delay));
          const retryTimer = setTimeout(() => {
            automaticResyncRetryTimersRef.current.delete(retryThreadId);
            const state = useTimelineStore.getState();
            if (
              currentThreadIdRef.current === retryThreadId &&
              workspaceRef.current?.workspaceId === retryWorkspaceId &&
              state.handshake.generation === fence.generation &&
              state.serverInstanceId === fence.serverInstanceId &&
              state.resyncRequired[retryThreadId] !== undefined
            )
              useTimelineStore.getState().requestThreadResync(retryThreadId);
          }, delay);
          automaticResyncRetryTimersRef.current.set(retryThreadId, retryTimer);
        }
      } finally {
        if (recoveryToken !== undefined && !recoveryEnded)
          useTimelineStore.getState().cancelRecovery(recoveryToken);
        if (
          recoveryToken !== undefined &&
          automaticResyncRecoveryTokenRef.current.get(retryThreadId) === recoveryToken
        )
          automaticResyncRecoveryTokenRef.current.delete(retryThreadId);
        // active request 与 in-flight 必须作为同一 epoch 原子清理；旧 read 晚到时不能删除
        // 切回同一 Thread 后已经建立的新 single-flight 标记。
        const ownsCurrentSingleFlight =
          automaticResyncActiveRequestRef.current.get(retryThreadId) === request;
        if (ownsCurrentSingleFlight) {
          automaticResyncActiveRequestRef.current.delete(retryThreadId);
          automaticResyncInFlightRef.current.delete(retryThreadId);
        }
        // 无游标恢复通知可能在这次 thread/read 中到达；合并成一次后续快照，避免通知被
        // single-flight guard 吃掉，也不靠定时轮询重复读取没有新失效信号的 Thread。
        const latestResyncSequence =
          useTimelineStore.getState().resyncRequestSequenceByThread?.[retryThreadId] ?? 0;
        const currentState = useTimelineStore.getState();
        if (
          ownsCurrentSingleFlight &&
          latestResyncSequence > resyncSequenceAtStart &&
          mountedRef.current &&
          currentThreadIdRef.current === retryThreadId &&
          workspaceRef.current?.workspaceId === retryWorkspaceId &&
          currentState.handshake.generation === fence.generation &&
          currentState.serverInstanceId === fence.serverInstanceId
        )
          useTimelineStore.getState().requestThreadResync(retryThreadId);
      }
    })();
  }, [
    currentResyncReason,
    currentResyncSequence,
    currentThreadId,
    currentTimelineFence,
    backgroundErrorScope,
    cancelAutomaticRecovery,
    readCompleteThreadSnapshot,
    workspace,
  ]);

  /**
   * 目录项存在即可证明当前 Thread 已完成历史准入；具体 CAS revision 在点击操作时从
   * Timeline/目录 ref 读取，避免把每个流式 delta 的 revision 投影提升到应用壳层。
   */
  const currentThreadLoaded =
    currentThreadId !== undefined && threads.some((thread) => thread.threadId === currentThreadId);
  const canCompact =
    currentThreadLoaded &&
    !activeTurnPresent &&
    !busy &&
    compaction.phase !== "running" &&
    runtimeState !== undefined &&
    ["ready", "busy"].includes(runtimeState.status);

  /**
   * workspace prop 变化到 layout effect 之间仍可能保留上一轮 React state；返回值先按当前
   * scope 派生，避免子组件在新项目首帧看到旧 Thread 或沿用旧会话的操作能力。
   */
  const visibleScopeKey = catalogScopeKey(workspace);
  const visibleThreads = useMemo(() => {
    const scoped = threads.every((thread) =>
      visibleScopeKey === "session"
        ? thread.workspaceKind === "session"
        : thread.workspaceId === visibleScopeKey,
    )
      ? threads
      : threads.filter((thread) =>
          visibleScopeKey === "session"
            ? thread.workspaceKind === "session"
            : thread.workspaceId === visibleScopeKey,
        );
    // 没有 Timeline projection 时目录对象本身就是完整结果；同时显式消费 primitive
    // signature，使流状态变化能重新计算派生列表而不订阅高频正文对象。
    if (timelineStatusSignature === "" || scoped.length === 0) return scoped;
    const timeline = useTimelineStore.getState();
    return scoped.map((thread) => {
      const projection = timeline.threads[thread.threadId];
      if (projection === undefined) return thread;
      const latestTurnId = projection.latestTurnId;
      const latestTurn = latestTurnId === undefined ? undefined : timeline.turns[latestTurnId];
      const status = latestTurn?.origin === "native" ? latestTurn.status : null;
      const timelineRevision =
        latestTurnId === undefined ? undefined : timeline.turns[latestTurnId]?.threadRevision;
      const timelineAdvanced = timelineRevision !== undefined && timelineRevision > thread.revision;
      if (status === thread.latestTurnStatus && !timelineAdvanced) return thread;
      return {
        ...thread,
        latestTurnStatus: status,
        latestTurnSeen:
          status === "completed" || status === "failed" ? false : thread.latestTurnSeen,
      };
    });
  }, [threads, timelineStatusSignature, visibleScopeKey]);
  const visibleCurrentThreadId = visibleThreads.some(
    (thread) => thread.threadId === currentThreadId,
  )
    ? currentThreadId
    : undefined;
  const scopeReady = historyScopeKeyRef.current === visibleScopeKey;
  const visibleConversationError =
    !scopeReady ||
    error === undefined ||
    (error.source === "history-load" &&
      (error.scopeKey !== visibleScopeKey ||
        error.workspaceId !== workspace?.workspaceId ||
        error.workspaceRevision !== workspaceRevision ||
        error.runtimeAdmission !== historyRuntimeAdmission))
      ? undefined
      : { source: error.source, message: error.message };
  const visibleBackgroundError =
    scopeReady && backgroundError?.scope === backgroundErrorScope
      ? backgroundError.message
      : undefined;
  const forkingReplies = useMemo(
    () =>
      forkReplyView
        .filter((entry) => entry.state === "pending")
        .map(({ threadId, itemId }) => ({ threadId, itemId })),
    [forkReplyView],
  );
  const forkCreatedReplies = useMemo(
    () =>
      forkReplyView
        .filter((entry) => entry.state === "created")
        .map(({ threadId, itemId }) => ({ threadId, itemId })),
    [forkReplyView],
  );
  const forkOpenFailures = useMemo(
    () =>
      forkReplyView
        .filter((entry) => entry.state === "open_failed")
        .map(({ threadId, itemId }) => ({ threadId, itemId })),
    [forkReplyView],
  );
  const forkConflictReplies = useMemo(
    () =>
      forkReplyView
        .filter((entry) => entry.state === "conflict")
        .map(({ threadId, itemId }) => ({ threadId, itemId })),
    [forkReplyView],
  );
  const forkUnconfirmedReplies = useMemo(
    () =>
      forkReplyView
        .filter((entry) => entry.state === "unconfirmed")
        .map(({ threadId, itemId }) => ({ threadId, itemId })),
    [forkReplyView],
  );

  return {
    threads: visibleThreads,
    currentThreadId: visibleCurrentThreadId,
    hasOlderHistory: visibleCurrentThreadId !== undefined && hasOlderHistory,
    loadingOlderHistory: visibleCurrentThreadId !== undefined && loadingOlderHistory,
    olderHistoryError: visibleCurrentThreadId === undefined ? undefined : olderHistoryError,
    busy,
    error: visibleConversationError,
    backgroundError: visibleBackgroundError,
    mutatingThreadIds,
    forkingReplies,
    forkCreatedReplies,
    forkOpenFailures,
    forkConflictReplies,
    forkUnconfirmedReplies,
    compaction:
      visibleCurrentThreadId === undefined ? { phase: "idle", retryable: false } : compaction,
    canCompact: visibleCurrentThreadId !== undefined && canCompact,
    create,
    retryHistory,
    forkReply,
    select,
    search,
    readThreadRevision: readCurrentThreadRevision,
    readMessageContent,
    readAnswerContent,
    loadOlderHistory,
    rename,
    pin,
    archive,
    restore,
    updatePreferences,
    compact,
    cancelCompaction,
    dismissCompactionFeedback,
  };
}
