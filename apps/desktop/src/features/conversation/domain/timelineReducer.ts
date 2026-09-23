// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  timelineEventFromUnknown,
  timelineSnapshotFromUnknown,
  type InputQueue,
  type TimelineEvent,
  type TimelineGoalActivity,
  type TimelineTaskActivityEntry,
  type TimelineSnapshotItem,
  type TimelineLiveStream,
} from "./timelineContracts";
import type {
  ApprovalDecision,
  ItemMetadata,
  TimelineApproval,
  TimelineContextUsage,
  TimelineThreadContextUsage,
  TimelineItemAdapter,
  TimelineItemKind,
  TimelineItemStatus,
  ToolPresentation,
  TimelineTurn,
  TimelineTurnState,
} from "./timelineTypes";
import { contextReferencesFromUserContent, textFromUserContent } from "./userContent";

type SemanticEvent = Exclude<
  TimelineEvent,
  Extract<
    TimelineEvent,
    {
      method:
        | "runtime/status-changed"
        | "assistant/text-delta"
        | "assistant/reasoning-summary-delta";
    }
  >
>;
type ContextCompactionEvent = Extract<
  SemanticEvent,
  {
    method: "context/compaction-started" | "context/compacted" | "context/compaction-failed";
  }
>;
type ThreadSemanticEvent = Exclude<
  Extract<SemanticEvent, { params: { threadRevision: number; threadId: string } }>,
  ContextCompactionEvent
>;
type ModelStepCommittedEvent = Extract<
  ThreadSemanticEvent,
  { method: "assistant/model-step-committed" }
>;
type ToolStartedEvent = Extract<ThreadSemanticEvent, { method: "tool/started" }>;
type ToolBatchCommittedEvent = Extract<ThreadSemanticEvent, { method: "tool/batch-committed" }>;
type InputConsumedEvent = Extract<ThreadSemanticEvent, { method: "turn/input-consumed" }>;
type MessagesReceivedEvent = Extract<ThreadSemanticEvent, { method: "turn/messages_received" }>;
type InputQueueChangedEvent = Extract<TimelineEvent, { method: "turn/input-queue-changed" }>;

const EVENT_DEDUP_WINDOW = 1024;
const MAX_LIVE_SEGMENT_BYTES = 64 * 1024;
const textEncoder = new TextEncoder();

/**
 * 将权威终态机器码投影为最小重试语义；只有目录中明确可恢复的模型不可用允许提示重试，
 * 其余未知码保持保守，避免 UI 擅自扩大失败契约。
 */
function turnError(errorCode: string): NonNullable<TimelineTurn["error"]> {
  return { code: errorCode, retryable: errorCode === "MODEL_UNAVAILABLE" };
}

type ResyncReason =
  | "server_instance_changed"
  | "gap"
  | "late_event"
  | "missing_item"
  | "invalid_event"
  | "projection_fault"
  | "terminal_missing"
  | "snapshot_invalid"
  | "handshake_required"
  | "handshake_failed";

type ApplyOutcome =
  | "applied"
  | "duplicate"
  | "late"
  | "gap"
  | "resync_required"
  | "invalid"
  | "rejected";

interface RuntimeProjection {
  status:
    | "starting"
    | "ready"
    | "busy"
    | "stopping"
    | "degraded"
    | "shutting_down"
    | "stopped"
    | "recovery_required"
    | "crashed"
    | "incompatible"
    | "faulted";
  eventId: string;
  occurredAt: string;
  reason?: string;
}

interface HostProjection {
  phase: "disconnected" | "ready";
  generation: number;
}

/** Thread 投影只属于 Renderer 状态；持久元数据必须通过 History Read 刷新。 */
interface TimelineThreadProjection {
  threadId: string;
  workspaceId: string;
  title: string;
  status: "active" | "archived" | "deleted";
  revision: number;
  activeTurnId?: string;
  latestTurnId?: string;
  updatedAt?: string;
}

/** 保留 Approval 卡片及其终态决定，但不创建第二套请求 Registry。 */
interface TimelineApprovalState {
  threadId: string;
  approval?: TimelineApproval;
  decision?: ApprovalDecision;
  resolvedAt?: string;
  closedAt?: string;
}

/** 只保留最近一次已提交 Workspace Dirty 投影，不能自行推断文件系统状态。 */
/** Context lifecycle 只投影服务端事实，不缓存摘要正文或自行判断压缩收益。 */
interface ContextCompactionProjection {
  compactionId: string;
  threadId: string;
  turnId: string | null;
  trigger: "automatic" | "manual" | "overflow_recovery";
  phase: "started" | "compacted" | "failed";
  sourceRevision: number;
  threadRevision: number;
  inputTokensBefore: number | null;
  inputTokensAfter: number | null;
  strategyVersion: "ja-context-v1";
  checkpointId?: string;
  errorCode?: string;
  occurredAt: string;
}

/** 将权威 turn/start 结果与 Renderer 持有的精确请求文本配对。 */
export interface AcceptedTurnProjection {
  threadId: string;
  turnId: string;
  threadRevision: number;
  submittedText: string;
  /** ACK 的临时用户消息必须保留附件摘要；后续 Snapshot 会用服务端权威事实整体替换。 */
  submittedAttachments?: readonly import("./timelineContracts").AttachmentSummary[];
  submittedAt: string;
}

/**
 * 一个 live stream segment 只覆盖相邻且同语义的 delta；跨 reasoning/text 切换必须保留为新段，
 * 否则 Renderer 会在收到下一种 delta 时覆盖已经展示过的公开推理或回复。segmentStartSeq
 * 只作为 Renderer 的瞬态 identity，不进入 JA-RPC 或持久化协议。
 */
export interface TimelineDraftProjection {
  kind: "assistant" | "reasoning";
  text: string;
  streamSeq: number;
  segmentStartSeq: number;
  occurredAt?: string;
}

/** Snapshot 读取分为健康对账与真实恢复；只有真实恢复允许用 baseline 重置瞬态流状态。 */
export interface ApplySnapshotOptions {
  mode?: "health" | "recovery";
}

/** Zustand 只保存这份状态；Draft 与 Stream Cursor 明确属于瞬态。 */
export interface TimelineState {
  handshake: HostProjection;
  serverInstanceId: string | undefined;
  threads: Record<string, TimelineThreadProjection>;
  turns: Record<string, TimelineTurn>;
  items: Record<string, TimelineItemAdapter>;
  itemThreadById: Record<string, string>;
  itemUtf8BytesById: Record<string, number>;
  itemIdsByThread: Record<string, string[]>;
  toolItemIdByCallId: Record<string, string>;
  pendingToolOrdinalByCallId: Record<string, number>;
  /** 只记录当前 live stream 已接收的 started，不能从 Snapshot 的 running 状态反推。 */
  liveStartedToolCorrelations: Record<string, true>;
  approvalsById: Record<string, TimelineApprovalState>;
  contextCompactionByThread: Record<string, ContextCompactionProjection>;
  /** 成功压缩边界独立于瞬态生命周期，后续重试不得恢复旧 Provider 计量。 */
  contextUsageInvalidatedAtByThread: Record<string, string>;
  contextUsageByThread: Record<string, TimelineThreadContextUsage>;
  taskActivitiesByRootThread: Record<string, readonly TimelineTaskActivityEntry[]>;
  goalActivitiesByOwnerThread: Record<string, readonly TimelineGoalActivity[]>;
  inputQueueByTurn: Record<string, InputQueue>;
  threadRevisionByThread: Record<string, number>;
  /** 最近一次权威 Snapshot 覆盖到的 revision；用于幂等忽略被快照覆盖的迟到 committed event。 */
  snapshotRevisionByThread: Record<string, number>;
  streamSeqByTurn: Record<string, number>;
  /** 未提交的 Assistant/Reasoning segments；发生重连或 Gap 时必须整体丢弃。 */
  draftByTurn: Record<string, readonly TimelineDraftProjection[]>;
  seenEventIds: Record<string, true>;
  seenEventOrder: string[];
  resyncRequired: Record<string, ResyncReason>;
  runtime: RuntimeProjection | undefined;
  lastOutcome: ApplyOutcome | undefined;
}

/** 创建空投影，使每个 Lifecycle Generation 都从没有陈旧业务状态的边界开始。 */
export function createTimelineState(): TimelineState {
  return {
    handshake: { phase: "disconnected", generation: 0 },
    serverInstanceId: undefined,
    threads: {},
    turns: {},
    items: {},
    itemThreadById: {},
    itemUtf8BytesById: {},
    itemIdsByThread: {},
    toolItemIdByCallId: {},
    pendingToolOrdinalByCallId: {},
    liveStartedToolCorrelations: {},
    approvalsById: {},
    contextCompactionByThread: {},
    contextUsageInvalidatedAtByThread: {},
    contextUsageByThread: {},
    taskActivitiesByRootThread: {},
    goalActivitiesByOwnerThread: {},
    inputQueueByTurn: {},
    threadRevisionByThread: {},
    snapshotRevisionByThread: {},
    streamSeqByTurn: {},
    draftByTurn: {},
    seenEventIds: {},
    seenEventOrder: [],
    resyncRequired: {},
    runtime: undefined,
    lastOutcome: undefined,
  };
}

/** 新原生 Generation 或权威 Snapshot 替换状态时清空旧业务投影。 */
function clearBusinessProjection(state: TimelineState): TimelineState {
  return {
    ...state,
    serverInstanceId: undefined,
    threads: {},
    turns: {},
    items: {},
    itemThreadById: {},
    itemUtf8BytesById: {},
    itemIdsByThread: {},
    toolItemIdByCallId: {},
    pendingToolOrdinalByCallId: {},
    liveStartedToolCorrelations: {},
    approvalsById: {},
    contextCompactionByThread: {},
    contextUsageInvalidatedAtByThread: {},
    contextUsageByThread: {},
    taskActivitiesByRootThread: {},
    goalActivitiesByOwnerThread: {},
    inputQueueByTurn: {},
    threadRevisionByThread: {},
    snapshotRevisionByThread: {},
    streamSeqByTurn: {},
    draftByTurn: {},
    seenEventIds: {},
    seenEventOrder: [],
    resyncRequired: {},
  };
}

/**
 * 清理控制器淘汰的非活动 Thread 投影；活跃 Turn、待审批和运行中的 Task 关联必须继续保留，
 * 终态 Goal/Approval 历史则可随缓存淘汰。调用方只应传入自己拥有的缓存候选。
 */
export function pruneInactiveThreads(
  state: TimelineState,
  candidateThreadIds: readonly string[],
): TimelineState {
  const candidates = new Set(candidateThreadIds);
  const removable = new Set<string>();
  for (const threadId of candidates) {
    if (state.threads[threadId] === undefined) continue;
    if (
      Object.values(state.turns).some(
        (turn) =>
          turn.threadId === threadId && !["completed", "failed", "cancelled"].includes(turn.status),
      )
    )
      continue;
    if (
      Object.values(state.approvalsById).some(
        (projection) =>
          projection.threadId === threadId &&
          projection.decision === undefined &&
          projection.closedAt === undefined,
      )
    )
      continue;
    if (
      Object.values(state.taskActivitiesByRootThread).some((entries) =>
        entries.some((entry) => {
          const activity = entry.activity;
          const task = entry.task;
          const ownsThread = [
            activity.rootThreadId,
            activity.taskThreadId,
            activity.actorThreadId,
            task.taskThreadId,
            task.parentThreadId,
            task.rootThreadId,
          ].includes(threadId);
          const taskIsActive = !["completed", "failed", "cancelled"].includes(task.state);
          return ownsThread && taskIsActive;
        }),
      )
    )
      continue;
    removable.add(threadId);
  }
  if (removable.size === 0) return state;

  const turnIds = new Set(
    Object.values(state.turns)
      .filter((turn) => removable.has(turn.threadId))
      .map((turn) => turn.turnId),
  );
  const itemIds = new Set(
    [...removable].flatMap((threadId) => state.itemIdsByThread[threadId] ?? []),
  );
  const threadPrefixes = [...removable].map((threadId) => `${threadId}:`);
  const filterThreadRecord = <T>(record: Record<string, T>): Record<string, T> =>
    Object.fromEntries(Object.entries(record).filter(([threadId]) => !removable.has(threadId)));

  return outcome(
    {
      ...state,
      threads: filterThreadRecord(state.threads),
      turns: Object.fromEntries(
        Object.entries(state.turns).filter(([turnId]) => !turnIds.has(turnId)),
      ),
      items: Object.fromEntries(
        Object.entries(state.items).filter(([itemId]) => !itemIds.has(itemId)),
      ),
      itemThreadById: Object.fromEntries(
        Object.entries(state.itemThreadById).filter(([itemId]) => !itemIds.has(itemId)),
      ),
      itemUtf8BytesById: Object.fromEntries(
        Object.entries(state.itemUtf8BytesById).filter(([itemId]) => !itemIds.has(itemId)),
      ),
      itemIdsByThread: Object.fromEntries(
        Object.entries(state.itemIdsByThread).filter(([threadId]) => !removable.has(threadId)),
      ),
      toolItemIdByCallId: Object.fromEntries(
        Object.entries(state.toolItemIdByCallId).filter(
          ([correlation, itemId]) =>
            !threadPrefixes.some((prefix) => correlation.startsWith(prefix)) &&
            !itemIds.has(itemId),
        ),
      ),
      pendingToolOrdinalByCallId: Object.fromEntries(
        Object.entries(state.pendingToolOrdinalByCallId).filter(
          ([correlation]) => !threadPrefixes.some((prefix) => correlation.startsWith(prefix)),
        ),
      ),
      liveStartedToolCorrelations: Object.fromEntries(
        Object.entries(state.liveStartedToolCorrelations).filter(
          ([correlation]) => !threadPrefixes.some((prefix) => correlation.startsWith(prefix)),
        ),
      ),
      approvalsById: Object.fromEntries(
        Object.entries(state.approvalsById).filter(
          ([, projection]) => !removable.has(projection.threadId),
        ),
      ),
      contextCompactionByThread: filterThreadRecord(state.contextCompactionByThread),
      contextUsageInvalidatedAtByThread: filterThreadRecord(
        state.contextUsageInvalidatedAtByThread,
      ),
      contextUsageByThread: filterThreadRecord(state.contextUsageByThread),
      taskActivitiesByRootThread: filterThreadRecord(state.taskActivitiesByRootThread),
      goalActivitiesByOwnerThread: filterThreadRecord(state.goalActivitiesByOwnerThread),
      inputQueueByTurn: Object.fromEntries(
        Object.entries(state.inputQueueByTurn).filter(([turnId]) => !turnIds.has(turnId)),
      ),
      threadRevisionByThread: filterThreadRecord(state.threadRevisionByThread),
      snapshotRevisionByThread: filterThreadRecord(state.snapshotRevisionByThread ?? {}),
      streamSeqByTurn: Object.fromEntries(
        Object.entries(state.streamSeqByTurn).filter(([turnId]) => !turnIds.has(turnId)),
      ),
      draftByTurn: Object.fromEntries(
        Object.entries(state.draftByTurn).filter(([turnId]) => !turnIds.has(turnId)),
      ),
      resyncRequired: filterThreadRecord(state.resyncRequired),
    },
    "applied",
  );
}

/** 在不修改前一状态的前提下附加最新 Reducer 结果。 */
function outcome(state: TimelineState, lastOutcome: ApplyOutcome): TimelineState {
  return { ...state, lastOutcome };
}

export interface HostRuntimeStatus {
  status: RuntimeProjection["status"];
  generation: number;
  serverInstanceId?: string | null;
  eventId?: string;
  occurredAt?: string;
  reason?: string;
}

const SAFE_RUNTIME_REASONS = new Set([
  "starting",
  "ready",
  "turn_started",
  "stopping",
  "stopped",
  "start_failed",
  "event_queue_overflow",
]);

/** 原生诊断不得进入 Renderer 状态，但保留稳定 Reason Class 供恢复策略判断。 */
function safeRuntimeReason(reason: string | undefined): string | undefined {
  if (reason === undefined) return undefined;
  return SAFE_RUNTIME_REASONS.has(reason) ? reason : "unknown";
}

/** 投影一次原生 Lifecycle 状态，并在 Generation 变化时清理旧 Entity。 */
export function applyRuntimeStatus(state: TimelineState, status: HostRuntimeStatus): TimelineState {
  const generationValid =
    status.status === "starting" ||
    status.status === "stopped" ||
    status.status === "recovery_required"
      ? Number.isSafeInteger(status.generation) && status.generation >= 0
      : Number.isSafeInteger(status.generation) && status.generation > 0;
  if (!generationValid || status.generation < state.handshake.generation)
    return outcome(state, "rejected");
  const ready = status.status === "ready" || status.status === "busy";
  const changed = status.generation !== state.handshake.generation;
  const handshake: HostProjection = {
    phase: ready ? "ready" : "disconnected",
    generation: status.generation,
  };
  const base = ready && !changed ? state : clearBusinessProjection({ ...state, handshake });
  return outcome(
    {
      ...base,
      handshake,
      serverInstanceId: ready ? (status.serverInstanceId ?? undefined) : undefined,
      runtime: {
        status: status.status,
        eventId: status.eventId ?? `host_state_${status.generation}`,
        occurredAt: status.occurredAt ?? new Date().toISOString(),
        ...(safeRuntimeReason(status.reason) === undefined
          ? {}
          : { reason: safeRuntimeReason(status.reason) }),
      },
    },
    "applied",
  );
}

/** 返回有界投影 Guard 使用的 UTF-8 大小，避免按 UTF-16 字符数低估负载。 */
function utf8ByteLength(value: string): number {
  return textEncoder.encode(value).byteLength;
}

/** 在有界 Ledger 中记录 Event ID，使重复通知保持幂等且不会无限增长。 */
function rememberEvent(state: TimelineState, eventId: string): TimelineState {
  if (state.seenEventIds[eventId] === true) return state;
  const order = [...state.seenEventOrder, eventId];
  const evicted =
    order.length > EVENT_DEDUP_WINDOW ? order.splice(0, order.length - EVENT_DEDUP_WINDOW) : [];
  const seenEventIds: Record<string, true> = { ...state.seenEventIds, [eventId]: true };
  for (const oldId of evicted) delete seenEventIds[oldId];
  return { ...state, seenEventIds, seenEventOrder: order };
}

/** 标记 Thread 需要权威读取，并且只移除其未提交 Draft。 */
function resync(
  state: TimelineState,
  threadId: string,
  reason: ResyncReason,
  result: ApplyOutcome = "resync_required",
): TimelineState {
  const draftByTurn = { ...state.draftByTurn };
  for (const turn of Object.values(state.turns))
    if (turn.threadId === threadId) delete draftByTurn[turn.turnId];
  return outcome(
    { ...state, draftByTurn, resyncRequired: { ...state.resyncRequired, [threadId]: reason } },
    result,
  );
}

/**
 * 仅登记一次后台权威读取意图，保留屏幕上的 Draft 和 Stream Cursor；真实 Gap 仍由 resync 负责清理。
 * 该分离避免健康对账把正在显示的正文误判成失效瞬态，从而触发 Composer/WorkProcess 闪烁。
 */
export function markThreadResync(state: TimelineState, threadId: string): TimelineState {
  const existing = state.resyncRequired[threadId];
  return outcome(
    {
      ...state,
      resyncRequired: {
        ...state.resyncRequired,
        [threadId]: existing ?? "invalid_event",
      },
    },
    "resync_required",
  );
}

/** Event Stream 无效时丢弃所有 Live Thread 的瞬态 Draft，避免把推测内容保留到恢复后。 */
export function requireActiveTurnResync(
  state: TimelineState,
  reason: "projection_fault" | "terminal_missing",
): TimelineState {
  const activeThreadIds = new Set(
    Object.values(state.turns)
      .filter((turn) => !isTerminalState(turn.status))
      .map((turn) => turn.threadId),
  );
  let next = state;
  for (const threadId of activeThreadIds) next = resync(next, threadId, reason);
  return activeThreadIds.size === 0 ? outcome(state, "applied") : next;
}

/** Context lifecycle 拥有可空 Turn identity，必须先于普通 Turn Event 单独分派。 */
function isContextCompactionEvent(event: TimelineEvent): event is ContextCompactionEvent {
  return (
    event.method === "context/compaction-started" ||
    event.method === "context/compacted" ||
    event.method === "context/compaction-failed"
  );
}

/** 仅对携带 Thread Revision CAS 且非 Context lifecycle 的语义通知返回 true。 */
function isThreadEvent(event: TimelineEvent): event is ThreadSemanticEvent {
  return (
    !isContextCompactionEvent(event) &&
    "threadId" in event.params &&
    "threadRevision" in event.params
  );
}

/** 仅对三种 Terminal Turn 状态返回 true；Suspended 仍是阻塞 Thread 的可恢复状态。 */
function isTerminalState(status: TimelineTurnState): boolean {
  return status === "completed" || status === "failed" || status === "cancelled";
}

/** 校验一次七态 Lifecycle 边，不接纳内部 Agent Phase。 */
function isLegalTransition(from: TimelineTurnState, to: TimelineTurnState): boolean {
  const legal: Readonly<Record<TimelineTurnState, readonly TimelineTurnState[]>> = {
    queued: ["running", "suspended", "completed", "failed", "cancelled"],
    running: ["waiting_approval", "suspended", "completed", "failed", "cancelled"],
    waiting_approval: ["running", "suspended", "completed", "failed", "cancelled"],
    suspended: ["queued", "cancelled"],
    completed: [],
    failed: [],
    cancelled: [],
  };
  return legal[from].includes(to);
}

/** 为仅 Stream Delta 解析 UI Turn，且不向 Wire Event 添加 Thread 数据。 */
function turnForStream(state: TimelineState, turnId: string): TimelineTurn | undefined {
  return state.turns[turnId];
}

/** 按 Thread、Turn 与语义 Kind 查找持久 Item，避免依赖展示文本。 */
function findItem(
  state: TimelineState,
  threadId: string,
  turnId: string,
  kind: TimelineItemKind,
): TimelineItemAdapter | undefined {
  return (state.itemIdsByThread[threadId] ?? [])
    .map((itemId) => state.items[itemId])
    .find((item) => item?.turnId === turnId && item.kind === kind);
}

/** 添加或替换 Item 时同步维护 Thread 顺序与字节计数不变量。 */
function putItem(state: TimelineState, item: TimelineItemAdapter): TimelineState {
  const previous = state.items[item.itemId];
  const itemIds = state.itemIdsByThread[item.threadId] ?? [];
  const itemIdsByThread =
    previous === undefined && !itemIds.includes(item.itemId)
      ? { ...state.itemIdsByThread, [item.threadId]: [...itemIds, item.itemId] }
      : state.itemIdsByThread;
  return {
    ...state,
    items: { ...state.items, [item.itemId]: item },
    itemThreadById: { ...state.itemThreadById, [item.itemId]: item.threadId },
    itemUtf8BytesById: {
      ...state.itemUtf8BytesById,
      [item.itemId]: utf8ByteLength(item.text ?? ""),
    },
    itemIdsByThread,
  };
}

/** 只有 Identity 与 Thread 所有权都一致时才更新已有 Item。 */
function updateItem(
  state: TimelineState,
  itemId: string,
  patch: Partial<TimelineItemAdapter>,
): TimelineState | undefined {
  const existing = state.items[itemId];
  if (existing === undefined) return undefined;
  return putItem(state, { ...existing, ...patch });
}

/** 将持久 ToolPresentation 的终态映射回 Live Reducer 使用的 Item 状态，保证刷新前后样式一致。 */
function snapshotToolStatus(presentation: ToolPresentation): TimelineItemStatus {
  switch (presentation.status) {
    case "pending":
      return "started";
    case "running":
    case "waiting_approval":
      return "in_progress";
    case "success":
      return "completed";
    case "error":
      return "failed";
    case "cancelled":
      return "cancelled";
  }
}

/** Snapshot 只为仍可能收到 live 结算事件的 Tool 重建关联，终态 presentation 永不回流。 */
function isTerminalToolPresentation(presentation: ToolPresentation): boolean {
  return ["success", "error", "cancelled"].includes(presentation.status);
}

/**
 * 转换持久 Snapshot 事实，但不假装它仍携带 Live Event Stream 的逐 Turn 进度。
 * Tool 历史按 callId 只投影一行，presentation 已包含最新持久状态，与 Live 原位更新保持一致。
 */
function projectSnapshotItem(
  item: TimelineSnapshotItem,
  threadId: string,
  failureReply = false,
): TimelineItemAdapter {
  const base = {
    itemId: item.itemId,
    threadId,
    turnId: item.turnId,
    status: "completed" as const,
    createdAt: item.createdAt,
  };
  switch (item.kind) {
    case "user_input":
      return {
        ...base,
        kind: "user_message",
        text: textFromUserContent(item.content),
        contextReferences: contextReferencesFromUserContent(item.content),
        attachments: item.attachments,
      };
    case "thread_message":
      return {
        ...base,
        kind: "thread_message",
        text: item.content,
        sourceThreadId: item.sourceThreadId,
        sourceTitle: item.sourceTitle,
      };
    case "assistant_progress":
      return {
        ...base,
        kind: "commentary",
        text: item.text,
        title: "回复过程",
        metadata: { phase: "assistant_progress", modelRound: item.modelRound },
      };
    case "reasoning_summary":
      return {
        ...base,
        kind: "reasoning",
        text: item.text,
        title: "思考摘要",
        metadata: { phase: "reasoning_summary", modelRound: item.modelRound },
      };
    case "final_answer":
      return {
        ...base,
        kind: "agent_message",
        text: item.text,
        final: true,
        title: "Final",
        ...(failureReply ? { metadata: { failureReply: true } } : {}),
      };
    case "tool_call":
      return {
        ...base,
        status: snapshotToolStatus(item.presentation),
        kind: "tool_call",
        title: item.presentation.title,
        metadata: {
          callId: item.callId,
          toolName: item.toolName,
          toolKind: item.presentation.kind,
          presentation: item.presentation,
          relativePaths: item.presentation.relativePaths,
          truncated: item.presentation.truncated,
        },
      };
    case "approval":
      return {
        ...base,
        kind: "commentary",
        title:
          item.decision === "approve"
            ? "审批已批准"
            : item.decision === "deny"
              ? "审批已拒绝"
              : `确认 ${item.toolName}`,
        metadata: {
          callId: item.callId,
          toolName: item.toolName,
          requiresUserAction: item.decision === null,
        },
      };
  }
}

/** 将 Wire baseline 映射为 Renderer 的 Draft segment，保持同一段正文的对象身份可复用。 */
function draftSegmentsFromBaseline(
  baseline: TimelineLiveStream,
): readonly TimelineDraftProjection[] {
  return baseline.segments.map((segment): TimelineDraftProjection => {
    const kind: TimelineDraftProjection["kind"] =
      segment.kind === "reasoningSummary" ? "reasoning" : "assistant";
    return {
      kind,
      text: segment.text,
      streamSeq: segment.streamSeq,
      segmentStartSeq: segment.segmentStartSeq,
      occurredAt: segment.occurredAt,
    };
  });
}

/** 只有 baseline 与当前 Draft 完全相同才复用原对象，避免健康读取重新触发正文入场动效。 */
function matchesLiveStreamBaseline(state: TimelineState, baseline: TimelineLiveStream): boolean {
  const currentStreamSeq = state.streamSeqByTurn[baseline.turnId] ?? 0;
  if (currentStreamSeq < baseline.streamSeq) return false;
  const current = state.draftByTurn[baseline.turnId] ?? [];
  if (baseline.segments.length === 0) return true;
  if (current.length < baseline.segments.length) return false;
  const exact = currentStreamSeq === baseline.streamSeq;
  if (exact && current.length !== baseline.segments.length) return false;
  const prefixMatches = baseline.segments.every((segment, index) => {
    const draft = current[index];
    return (
      segment !== undefined &&
      draft !== undefined &&
      draft.kind === (segment.kind === "reasoningSummary" ? "reasoning" : "assistant") &&
      (exact ? draft.text === segment.text : draft.text.startsWith(segment.text)) &&
      draft.streamSeq >= segment.streamSeq &&
      draft.segmentStartSeq === segment.segmentStartSeq &&
      draft.occurredAt === segment.occurredAt
    );
  });
  return prefixMatches && (baseline.streamSeq < currentStreamSeq || exact);
}

/** same-revision 健康快照不应替换仍在屏幕上的 live Draft；恢复模式则必须由 baseline 接管。 */
function shouldPreserveLiveDraft(
  state: TimelineState,
  threadId: string,
  snapshotRevision: number,
  currentRevision: number | undefined,
  liveStream: TimelineLiveStream | null,
  mode: ApplySnapshotOptions["mode"],
): boolean {
  // Recovery 的 baseline 是新的提交边界；即使内容看似相同也必须重建对象，随后只重放窗口内事件，
  // 否则 gap 后的旧 Draft/游标会绕过权威边界重新进入 UI。
  if (mode === "recovery") return false;
  if (currentRevision !== snapshotRevision) return false;
  if (liveStream !== null) return matchesLiveStreamBaseline(state, liveStream);
  return Object.values(state.turns).some(
    (turn) =>
      turn.threadId === threadId &&
      !isTerminalState(turn.status) &&
      ((state.draftByTurn[turn.turnId]?.length ?? 0) > 0 ||
        (state.streamSeqByTurn[turn.turnId] ?? 0) > 0),
  );
}

/**
 * 应用完整且未分页的 Thread Snapshot，并丢弃无法由快照继续确认的 In-flight 投影。
 * Workspace 由 History 调用方提供，因为 Wire Snapshot 明确省略其所有权；已提交的 live 修改摘要
 * 只有在同一 runtime 身份且 Turn 仍可持有 tracker 时保留，避免恢复读取让摘要在 Tool 间歇消失。
 */
export function applySnapshot(
  state: TimelineState,
  value: unknown,
  workspaceId: string,
  options: ApplySnapshotOptions = {},
): TimelineState {
  const parsed = timelineSnapshotFromUnknown(value);
  if (parsed === undefined || parsed.nextCursor !== null || state.handshake.phase !== "ready") {
    return outcome(
      state,
      parsed !== undefined && state.handshake.phase !== "ready" ? "rejected" : "invalid",
    );
  }
  const snapshot = parsed;
  const liveStream = snapshot.liveStream;
  const priorThread = state.threads[snapshot.threadId];
  if (
    workspaceId.trim() === "" ||
    (priorThread !== undefined && priorThread.workspaceId !== workspaceId)
  ) {
    return outcome(state, "invalid");
  }
  const currentRevision = state.threadRevisionByThread[snapshot.threadId];
  if (currentRevision !== undefined && snapshot.revision < currentRevision) {
    // thread/read 可能早于随后到达的 committed event 发起；晚到快照不能让 Turn 状态和消息归属倒退。
    return outcome(state, "late");
  }
  if (liveStream !== null) {
    const owner = snapshot.turns.find((turn) => turn.turnId === liveStream.turnId);
    if (owner === undefined || isTerminalState(owner.status)) return outcome(state, "invalid");
  }
  const preserveLiveDraft = shouldPreserveLiveDraft(
    state,
    snapshot.threadId,
    snapshot.revision,
    currentRevision,
    liveStream,
    options.mode,
  );
  const preservedLiveTurnId = preserveLiveDraft
    ? (liveStream?.turnId ??
      Object.values(state.turns).find(
        (turn) =>
          turn.threadId === snapshot.threadId &&
          !isTerminalState(turn.status) &&
          ((state.draftByTurn[turn.turnId]?.length ?? 0) > 0 ||
            (state.streamSeqByTurn[turn.turnId] ?? 0) > 0),
      )?.turnId)
    : undefined;
  let next = state;
  const threadTurnIds = new Set(
    Object.values(next.turns)
      .filter((turn) => turn.threadId === snapshot.threadId)
      .map((turn) => turn.turnId),
  );
  const oldItemIds = next.itemIdsByThread[snapshot.threadId] ?? [];
  const items = { ...next.items };
  const itemThreadById = { ...next.itemThreadById };
  const itemUtf8BytesById = { ...next.itemUtf8BytesById };
  for (const itemId of oldItemIds) {
    delete items[itemId];
    delete itemThreadById[itemId];
    delete itemUtf8BytesById[itemId];
  }
  const turns = { ...next.turns };
  const streamSeqByTurn = { ...next.streamSeqByTurn };
  const draftByTurn = { ...next.draftByTurn };
  for (const threadTurnId of threadTurnIds) {
    delete turns[threadTurnId];
    if (threadTurnId !== preservedLiveTurnId) {
      const snapshotTurn = snapshot.turns.find((turn) => turn.turnId === threadTurnId);
      // Terminal snapshot 不携带 live baseline，但已有即时流水位仍是迟到 delta 的安全覆盖边界；
      // 保留它可以在重读后幂等忽略 seq<=watermark，未知更大序号仍按终态非法事实处理。
      if (
        snapshotTurn === undefined ||
        !isTerminalState(snapshotTurn.status) ||
        (streamSeqByTurn[threadTurnId] ?? 0) <= 0
      )
        delete streamSeqByTurn[threadTurnId];
      delete draftByTurn[threadTurnId];
    }
  }
  const approvalsById: TimelineState["approvalsById"] = Object.fromEntries(
    Object.entries(next.approvalsById).filter(
      ([, projection]) => projection.threadId !== snapshot.threadId,
    ),
  );
  for (const item of snapshot.items) {
    if (item.kind !== "approval") continue;
    const owner = snapshot.turns.find((turn) => turn.turnId === item.turnId);
    if (owner === undefined) return outcome(state, "invalid");
    approvalsById[item.approvalId] = {
      threadId: snapshot.threadId,
      approval: {
        approvalId: item.approvalId,
        threadId: snapshot.threadId,
        turnId: item.turnId,
        threadRevision: snapshot.revision,
        callId: item.callId,
        toolName: item.toolName,
        reason: item.reason,
        expiresAt: item.expiresAt,
      },
      ...(item.decision === null ? {} : { decision: item.decision }),
      ...(item.decision === null && isTerminalState(owner.status) && owner.completedAt !== null
        ? { closedAt: owner.completedAt }
        : {}),
    };
  }
  const toolItemIdByCallId = Object.fromEntries(
    Object.entries(next.toolItemIdByCallId).filter(
      ([, itemId]) => next.itemThreadById[itemId] !== snapshot.threadId,
    ),
  );
  const pendingToolOrdinalByCallId = Object.fromEntries(
    Object.entries(next.pendingToolOrdinalByCallId).filter(
      ([correlation]) => !correlation.startsWith(`${snapshot.threadId}:`),
    ),
  );
  const liveStartedToolCorrelations = Object.fromEntries(
    Object.entries(next.liveStartedToolCorrelations).filter(
      ([correlation]) => !correlation.startsWith(`${snapshot.threadId}:`),
    ),
  );
  for (const item of snapshot.items) {
    if (item.kind !== "tool_call") continue;
    const owner = snapshot.turns.find((turn) => turn.turnId === item.turnId);
    if (owner === undefined) return outcome(state, "invalid");
    if (isTerminalState(owner.status) || isTerminalToolPresentation(item.presentation)) continue;
    const correlation = toolCorrelation(snapshot.threadId, item.turnId, item.callId);
    if (
      toolItemIdByCallId[correlation] !== undefined ||
      pendingToolOrdinalByCallId[correlation] !== undefined
    )
      return outcome(state, "invalid");
    toolItemIdByCallId[correlation] = item.itemId;
    pendingToolOrdinalByCallId[correlation] = item.ordinal;
  }
  // Snapshot 不携带压缩 lifecycle；只保留成功压缩的失效边界，started/failed 不得让 UI 永久卡在处理中。
  const contextCompactionByThread = { ...next.contextCompactionByThread };
  if (contextCompactionByThread[snapshot.threadId]?.phase !== "compacted")
    delete contextCompactionByThread[snapshot.threadId];
  const contextUsageByThread = { ...next.contextUsageByThread };
  if (snapshot.contextUsage !== null) {
    const incoming = usageAfterCompaction(snapshot.contextUsage);
    const currentUsage = contextUsageByThread[snapshot.threadId];
    const currentTurnStartedAt =
      currentUsage === undefined ? undefined : next.turns[currentUsage.turnId]?.startedAt;
    const incomingTurnStartedAt = snapshot.turns.find(
      (turn) => turn.turnId === snapshot.contextUsage?.turnId,
    )?.requestedAt;
    contextUsageByThread[snapshot.threadId] = retainKnownContextUsage(
      currentUsage,
      incoming,
      snapshot.contextUsage.turnId,
      currentTurnStartedAt,
      incomingTurnStartedAt,
      next.contextUsageInvalidatedAtByThread[snapshot.threadId],
    );
  }
  const inputQueueByTurn = Object.fromEntries(
    Object.entries(next.inputQueueByTurn).filter(([turnId]) => !threadTurnIds.has(turnId)),
  );
  if (snapshot.inputQueue !== null) {
    const queueTurn = snapshot.turns.find((turn) => turn.turnId === snapshot.inputQueue?.turnId);
    if (queueTurn === undefined || isTerminalState(queueTurn.status))
      return outcome(state, "invalid");
    const currentQueue = next.inputQueueByTurn[snapshot.inputQueue.turnId];
    if (
      currentQueue?.revision === snapshot.inputQueue.revision &&
      JSON.stringify(currentQueue) !== JSON.stringify(snapshot.inputQueue)
    )
      return resync(state, snapshot.threadId, "invalid_event");
    // Queue revision 不占用 Thread revision；同 revision 的迟到 thread/read 仍可能早于队列事件取样，
    // 因此快照只能补齐或前进队列，不能撤销已由 ACK/Event 提交的附件修复事实。
    inputQueueByTurn[snapshot.inputQueue.turnId] =
      currentQueue !== undefined && currentQueue.revision > snapshot.inputQueue.revision
        ? currentQueue
        : snapshot.inputQueue;
  }
  const rebuiltStreamSeqByTurn = {
    ...streamSeqByTurn,
  };
  for (const turn of snapshot.turns) {
    if (
      turn.turnId !== preservedLiveTurnId &&
      !(isTerminalState(turn.status) && (rebuiltStreamSeqByTurn[turn.turnId] ?? 0) > 0)
    )
      rebuiltStreamSeqByTurn[turn.turnId] = 0;
  }
  const rebuiltDraftByTurn = { ...draftByTurn };
  if (!preserveLiveDraft && liveStream !== null) {
    rebuiltStreamSeqByTurn[liveStream.turnId] = liveStream.streamSeq;
    const baselineDraft = draftSegmentsFromBaseline(liveStream);
    if (baselineDraft.length === 0) delete rebuiltDraftByTurn[liveStream.turnId];
    else rebuiltDraftByTurn[liveStream.turnId] = baselineDraft;
  }
  const recoveryNeedsBaseline =
    options.mode === "recovery" &&
    liveStream === null &&
    snapshot.turns.some((turn) => !isTerminalState(turn.status));
  const snapshotThread: TimelineThreadProjection = {
    ...(priorThread ?? {
      threadId: snapshot.threadId,
      title: "对话",
      status: "active" as const,
    }),
    threadId: snapshot.threadId,
    workspaceId,
    revision: snapshot.revision,
    activeTurnId: undefined,
    latestTurnId: snapshot.turns.at(-1)?.turnId,
  };
  next = {
    ...next,
    items,
    itemThreadById,
    itemUtf8BytesById,
    itemIdsByThread: { ...next.itemIdsByThread, [snapshot.threadId]: [] },
    turns: {
      ...turns,
      ...Object.fromEntries(
        snapshot.turns.map((turn) => [
          turn.turnId,
          {
            turnId: turn.turnId,
            threadId: snapshot.threadId,
            status: turn.status,
            startedAt: turn.requestedAt,
            ...(turn.completedAt === null ? {} : { completedAt: turn.completedAt }),
            ...(turn.errorCode === null ? {} : { error: turnError(turn.errorCode) }),
            changeSet: turn.changeSet,
            threadRevision: snapshot.revision,
          },
        ]),
      ),
    },
    threads: { ...next.threads, [snapshot.threadId]: snapshotThread },
    approvalsById,
    toolItemIdByCallId,
    pendingToolOrdinalByCallId,
    liveStartedToolCorrelations,
    contextCompactionByThread,
    contextUsageByThread,
    taskActivitiesByRootThread: {
      ...next.taskActivitiesByRootThread,
      [snapshot.threadId]: snapshot.taskActivities,
    },
    goalActivitiesByOwnerThread: {
      ...next.goalActivitiesByOwnerThread,
      [snapshot.threadId]: snapshot.goalActivities,
    },
    inputQueueByTurn,
    threadRevisionByThread: {
      ...next.threadRevisionByThread,
      [snapshot.threadId]: snapshot.revision,
    },
    snapshotRevisionByThread: {
      ...(next.snapshotRevisionByThread ?? {}),
      [snapshot.threadId]: snapshot.revision,
    },
    streamSeqByTurn: rebuiltStreamSeqByTurn,
    // 健康同 revision 快照保留同一 Draft 引用；真实恢复只接纳权威 baseline，null 不冒充 seq=0。
    draftByTurn: rebuiltDraftByTurn,
    resyncRequired: recoveryNeedsBaseline
      ? { ...next.resyncRequired, [snapshot.threadId]: "gap" }
      : Object.fromEntries(
          Object.entries(next.resyncRequired).filter(([id]) => id !== snapshot.threadId),
        ),
  };
  // 失败 Turn 可能已因队列输入产生过早期 Final；只有终态事务最后追加的 Final 才是安全收口回复。
  const failedTurnIds = new Set(
    snapshot.turns.filter((turn) => turn.status === "failed").map((turn) => turn.turnId),
  );
  const failureReplyByTurn = new Map<string, string>();
  for (const item of snapshot.items) {
    if (item.kind === "final_answer" && failedTurnIds.has(item.turnId))
      failureReplyByTurn.set(item.turnId, item.itemId);
  }
  const failureReplyItemIds = new Set(failureReplyByTurn.values());
  for (const item of snapshot.items)
    next = putItem(
      next,
      projectSnapshotItem(item, snapshot.threadId, failureReplyItemIds.has(item.itemId)),
    );
  return outcome(next, recoveryNeedsBaseline ? "resync_required" : "applied");
}

/**
 * 在独立 Event Stream 排空前安装 turn/start 已提交 Revision。Tauri Command Reply 与 Event
 * 使用不同 Channel，因此该 Baseline 是线性化点：它允许 Revision N+1 Event 合法进入，
 * 同时不放宽普通 Gap 校验。首轮临时标题与准入共享同一 Revision，metadata 若先到只会推进
 * Revision 索引而不会创建 Turn；这种精确同 Revision 竞态仍须由 ACK 补齐用户消息与 Turn identity。
 */
export function applyTurnAccepted(
  state: TimelineState,
  accepted: AcceptedTurnProjection,
): TimelineState {
  if (state.handshake.phase !== "ready") return outcome(state, "rejected");
  const currentRevision = state.threadRevisionByThread[accepted.threadId] ?? 0;
  const existing = state.turns[accepted.turnId];
  if (existing !== undefined) {
    return existing.threadId === accepted.threadId &&
      (existing.threadRevision ?? -1) >= accepted.threadRevision
      ? outcome(state, "duplicate")
      : resync(state, accepted.threadId, "invalid_event");
  }
  const thread = state.threads[accepted.threadId];
  if (thread === undefined) return resync(state, accepted.threadId, "missing_item");
  const metadataPrecededAdmissionAck =
    accepted.threadRevision === currentRevision && accepted.threadRevision > thread.revision;
  if (
    accepted.threadRevision < currentRevision ||
    (accepted.threadRevision === currentRevision && !metadataPrecededAdmissionAck)
  )
    return outcome(state, "late");
  if (accepted.threadRevision !== currentRevision + 1 && !metadataPrecededAdmissionAck)
    return resync(state, accepted.threadId, "gap", "gap");
  if (
    Object.values(state.turns).some(
      (turn) => turn.threadId === accepted.threadId && !isTerminalState(turn.status),
    )
  ) {
    return resync(state, accepted.threadId, "invalid_event");
  }
  const turn: TimelineTurn = {
    turnId: accepted.turnId,
    threadId: accepted.threadId,
    status: "queued",
    startedAt: accepted.submittedAt,
    threadRevision: accepted.threadRevision,
  };
  let next: TimelineState = {
    ...state,
    turns: { ...state.turns, [accepted.turnId]: turn },
    threads: {
      ...state.threads,
      [accepted.threadId]: {
        ...thread,
        revision: accepted.threadRevision,
        activeTurnId: accepted.turnId,
        latestTurnId: accepted.turnId,
        updatedAt: accepted.submittedAt,
      },
    },
    threadRevisionByThread: {
      ...state.threadRevisionByThread,
      [accepted.threadId]: accepted.threadRevision,
    },
    resyncRequired: Object.fromEntries(
      Object.entries(state.resyncRequired).filter(([threadId]) => threadId !== accepted.threadId),
    ),
  };
  if (accepted.submittedText.trim() !== "" || (accepted.submittedAttachments?.length ?? 0) > 0) {
    next = putItem(next, {
      itemId: `item_local_${accepted.turnId.slice("turn_".length)}`,
      threadId: accepted.threadId,
      turnId: accepted.turnId,
      kind: "user_message",
      status: "completed",
      text: accepted.submittedText,
      attachments: [...(accepted.submittedAttachments ?? [])],
      createdAt: accepted.submittedAt,
    });
  }
  return outcome(next, "applied");
}

/**
 * 合并 ACK 或无 Thread-revision 事件携带的全量队列；队列 revision 可以跨过未观察的中间值，
 * 因为每个投影都是完整快照。相同 revision 的不同正文则必须重读，不能按到达顺序猜 owner。
 */
export function applyInputQueue(state: TimelineState, inputQueue: InputQueue): TimelineState {
  if (state.handshake.phase !== "ready") return outcome(state, "rejected");
  const turn = state.turns[inputQueue.turnId];
  if (turn === undefined) return outcome(state, "invalid");
  const current = state.inputQueueByTurn[inputQueue.turnId];
  if (current !== undefined && inputQueue.revision < current.revision)
    return outcome(state, "late");
  if (current !== undefined && inputQueue.revision === current.revision) {
    return JSON.stringify(current) === JSON.stringify(inputQueue)
      ? outcome(state, "duplicate")
      : resync(state, turn.threadId, "invalid_event");
  }
  return outcome(
    {
      ...state,
      inputQueueByTurn: { ...state.inputQueueByTurn, [inputQueue.turnId]: inputQueue },
    },
    "applied",
  );
}

/**
 * 进入语义投影前校验 Event 所有权与 Revision 单调性。
 *
 * Thread revision 是持久业务事实的版本，不是公开通知的连续序号；服务端内部事务可以推进
 * revision 而不产生语义事件。连接内漏事件由 transport 的 sequence/generation 检测，因此这里
 * 只拒绝相同或倒退 revision，避免把合法的内部版本跳跃误判为通知丢失。
 */
function admitThreadEvent(
  state: TimelineState,
  event: ThreadSemanticEvent,
): TimelineState | undefined {
  const params = event.params;
  const currentRevision = state.threadRevisionByThread[params.threadId] ?? 0;
  // v1 Tool Receipt 明确省略 serverInstanceId；只对真实携带该字段的 Event Family 比较身份。
  const eventServerInstanceId =
    "serverInstanceId" in params ? params["serverInstanceId"] : undefined;
  if (
    state.serverInstanceId !== undefined &&
    eventServerInstanceId !== undefined &&
    state.serverInstanceId !== eventServerInstanceId
  )
    return undefined;
  // Event ID 去重发生在 Admission 之前。因此，旧 Revision 或相等 Revision 上未见过的 Event
  // 是 Stream 不一致而非无害重复；接纳它会允许不同 Payload 覆盖历史。
  if (params.threadRevision <= currentRevision) return undefined;
  if (params.workspaceId.trim() === "") return undefined;
  const existingThread = state.threads[params.threadId];
  if (existingThread !== undefined && existingThread.workspaceId !== params.workspaceId)
    return undefined;
  const thread = existingThread ?? {
    threadId: params.threadId,
    workspaceId: params.workspaceId,
    title: "对话",
    status: "active" as const,
    revision: currentRevision,
  };
  return {
    ...state,
    serverInstanceId: state.serverInstanceId ?? eventServerInstanceId,
    threads: { ...state.threads, [params.threadId]: thread },
  };
}

/** 提交一个语义 Event，并且只推进一次权威 Thread Revision。 */
function commitThreadRevision(
  state: TimelineState,
  event: { params: { threadId: string; threadRevision: number; occurredAt: string } },
): TimelineState {
  const params = event.params;
  const thread = state.threads[params.threadId];
  return {
    ...state,
    threadRevisionByThread: {
      ...state.threadRevisionByThread,
      [params.threadId]: params.threadRevision,
    },
    threads:
      thread === undefined
        ? state.threads
        : {
            ...state.threads,
            [params.threadId]: {
              ...thread,
              revision: params.threadRevision,
              updatedAt: params.occurredAt,
            },
          },
  };
}

/** 构建 Reducer 私有关联，不把它暴露成 Wire Identity。 */
function toolCorrelation(threadId: string, turnId: string, callId: string): string {
  return `${threadId}:${turnId}:${callId}`;
}

/** 审批事件只携带关联 identity；Reducer 更新既有安全 presentation，不重建 Tool 内容。 */
function updateToolPresentationStatus(
  state: TimelineState,
  threadId: string,
  turnId: string,
  callId: string,
  status: ToolPresentation["status"],
): TimelineState | undefined {
  const itemId = state.toolItemIdByCallId[toolCorrelation(threadId, turnId, callId)];
  const item = itemId === undefined ? undefined : state.items[itemId];
  const presentation = item?.metadata?.presentation;
  if (itemId === undefined || item === undefined || presentation === undefined) return undefined;
  return updateItem(state, itemId, {
    metadata: {
      ...item.metadata,
      presentation: { ...presentation, status },
      requiresUserAction: status === "waiting_approval",
    },
  });
}

/** 仅在已提交 Event 携带 Provider Usage 时投影；缺失必须继续保持缺失。 */
function usageMetadata(usage: TimelineContextUsage | undefined): ItemMetadata | undefined {
  return usage === undefined || usage.certainty === "unknown"
    ? undefined
    : {
        usageInputTokens: usage.inputTokens,
        usageOutputTokens: usage.outputTokens,
        usageTotalTokens: usage.totalTokens,
      };
}

/** 压缩只记录服务端边界；展示层继续保留最近一次可信 Usage，直到新 KNOWN 到达。 */
function usageAfterCompaction(usage: TimelineContextUsage): TimelineContextUsage {
  return usage;
}

/** 对协议时间做有限比较；无法解析时返回 undefined，让调用方采取保守的保留策略。 */
function compareUsageTimestamp(
  left: string | undefined,
  right: string | undefined,
): number | undefined {
  if (left === undefined || right === undefined) return undefined;
  if (left === right) return 0;
  const leftMs = Date.parse(left);
  const rightMs = Date.parse(right);
  if (!Number.isFinite(leftMs) || !Number.isFinite(rightMs)) return undefined;
  return leftMs < rightMs ? -1 : 1;
}

/** 请求序号相同才允许使用 requestId/Profile 校验；跨 Turn 的 ordinal 会重新计数。 */
function sameUsageRequestIdentity(
  left: TimelineContextUsage,
  right: TimelineContextUsage,
): boolean {
  return (
    left.requestId === right.requestId &&
    JSON.stringify(left.profile) === JSON.stringify(right.profile)
  );
}

/** 比较完整 Usage 时去掉 Snapshot 额外携带的 turnId，避免身份外壳影响幂等判断。 */
function sameUsageMeasurement(
  left: TimelineContextUsage | TimelineThreadContextUsage,
  right: TimelineContextUsage | TimelineThreadContextUsage,
): boolean {
  const leftValue = { ...left } as Record<string, unknown>;
  const rightValue = { ...right } as Record<string, unknown>;
  delete leftValue["turnId"];
  delete rightValue["turnId"];
  return JSON.stringify(leftValue) === JSON.stringify(rightValue);
}

/**
 * 快照可能比事件晚到但携带更旧的请求级计量；只有能证明 incoming 更新时才替换，
 * UNKNOWN 永远不能把已知环清空，无法判断先后时保留当前事实。
 */
function retainKnownContextUsage(
  current: TimelineThreadContextUsage | undefined,
  incoming: TimelineContextUsage,
  turnId: string,
  currentTurnStartedAt?: string,
  incomingTurnStartedAt?: string,
  contextInvalidatedAt?: string,
): TimelineThreadContextUsage {
  const next = (): TimelineThreadContextUsage => ({ ...incoming, turnId });
  if (current === undefined) return next();

  const currentKnown = current.certainty === "known";
  const incomingKnown = incoming.certainty === "known";
  const incomingAfterCompaction =
    incomingKnown && compareUsageTimestamp(incoming.measuredAt, contextInvalidatedAt) === 1;
  const currentAfterCompaction =
    compareUsageTimestamp(current.measuredAt, contextInvalidatedAt) === 1;
  if (current.turnId === turnId) {
    if (incoming.requestOrdinal < current.requestOrdinal) return current;
    if (incoming.requestOrdinal > current.requestOrdinal) return incomingKnown ? next() : current;
    if (!sameUsageRequestIdentity(current, incoming)) return current;
    if (!incomingKnown) return current;
    if (!currentKnown) return next();
    if (incomingAfterCompaction && !currentAfterCompaction) return next();
    return compareUsageTimestamp(incoming.measuredAt, current.measuredAt) === 1 ? next() : current;
  }

  const turnOrder = compareUsageTimestamp(incomingTurnStartedAt, currentTurnStartedAt);
  if (turnOrder === -1) return current;
  if (turnOrder === 1) return incomingKnown ? next() : current;
  if (incomingKnown && !currentKnown) return next();
  const measuredOrder = compareUsageTimestamp(incoming.measuredAt, current.measuredAt);
  if (measuredOrder === -1) return current;
  if (incomingAfterCompaction && !currentAfterCompaction) return next();
  return current;
}

/**
 * 仅在同 Turn 内比较 requestOrdinal；跨 Turn 先检查已确认的开始时间，等时按已接纳的事件顺序推进。
 * 同一 requestId 只允许 UNKNOWN 原位升级为相同画像的 KNOWN。
 * 迟到低序请求不覆盖，身份或画像冲突则拒绝事件，避免 UI 展示混合代际事实。
 */
function recordContextUsage(
  state: TimelineState,
  threadId: string,
  turnId: string,
  usage: TimelineContextUsage,
): TimelineState | undefined {
  const current = state.contextUsageByThread[threadId];
  if (current !== undefined && current.turnId !== turnId) {
    const previousStartedAt = state.turns[current.turnId]?.startedAt;
    const incomingStartedAt = state.turns[turnId]?.startedAt;
    const turnOrder = compareUsageTimestamp(incomingStartedAt, previousStartedAt);
    if (turnOrder === undefined) return undefined;
    if (turnOrder === -1) return state;
    // 新请求的 UNKNOWN 只能让排序边界前进，不能让 UI 暂时降级为空。
    if (current.certainty === "known" && usage.certainty === "unknown") return state;
    return {
      ...state,
      contextUsageByThread: {
        ...state.contextUsageByThread,
        [threadId]: { ...usageAfterCompaction(usage), turnId },
      },
    };
  }
  if (current !== undefined && current.turnId === turnId) {
    if (usage.requestOrdinal < current.requestOrdinal) return state;
    if (usage.requestOrdinal === current.requestOrdinal) {
      if (!sameUsageRequestIdentity(current, usage)) return undefined;
      if (usage.certainty === "unknown") {
        if (current.certainty === "known") return state;
        return sameUsageMeasurement(current, usage) ? state : undefined;
      }
      if (current.certainty === "known")
        return sameUsageMeasurement(current, usage) ? state : undefined;
    }
    // 只有确认了请求身份和顺序之后，才允许 UNKNOWN 保留旧 KNOWN。
    if (current.certainty === "known" && usage.certainty === "unknown") return state;
  }
  return {
    ...state,
    contextUsageByThread: {
      ...state.contextUsageByThread,
      [threadId]: {
        ...usageAfterCompaction(usage),
        turnId,
      },
    },
  };
}

/**
 * 原子投影一次已提交 Model 事务：Assistant Message、Usage 与所有 Prepared Tool Call
 * 必须在 Event 的单一 Thread Revision 下同时可见。
 */
function applyModelStepCommitted(
  state: TimelineState,
  event: ModelStepCommittedEvent,
): TimelineState | undefined {
  const params = event.params;
  const turn = state.turns[params.turnId];
  if (turn?.status !== "running") return undefined;
  const pendingPrefix = `${params.threadId}:${params.turnId}:`;
  if (Object.keys(state.pendingToolOrdinalByCallId).some((key) => key.startsWith(pendingPrefix)))
    return undefined;

  let next = state;
  if (params.usage !== undefined) {
    const recorded = recordContextUsage(next, params.threadId, params.turnId, params.usage);
    if (recorded === undefined) return undefined;
    next = recorded;
  }
  // Snapshot 的稳定语义排序把同一模型提交的公开 reasoning 放在正文前；live 也按该顺序插入，
  // 这样刷新前后的 itemIdsByThread 不会因持久化排序规则而跳变。没有公共 segment DTO 时，
  // 这里只保证常见的 reasoning -> text -> tool 提交顺序，不推断更细粒度的 Provider 交错位置。
  if (params.reasoningSummary?.trim()) {
    next = putItem(
      next,
      projectItem(
        next,
        `${params.messageId}_reasoning`,
        params.threadId,
        params.turnId,
        "reasoning",
        "completed",
        params.reasoningSummary,
        {
          title: "思考摘要",
          metadata: { phase: "reasoning_summary", modelRound: params.modelRound },
        },
      ),
    );
  }
  if (params.text.trim() !== "") {
    next = putItem(
      next,
      projectItem(
        next,
        params.messageId,
        params.threadId,
        params.turnId,
        "commentary",
        "completed",
        params.text,
        {
          title: "回复过程",
          metadata: {
            phase: "assistant_progress",
            modelRound: params.modelRound,
            ...usageMetadata(params.usage),
          },
        },
      ),
    );
  }
  next = clearDraft(next, params.turnId);
  const toolItemIdByCallId = { ...next.toolItemIdByCallId };
  const pendingToolOrdinalByCallId = { ...next.pendingToolOrdinalByCallId };
  for (const call of params.toolCalls) {
    const correlation = toolCorrelation(params.threadId, params.turnId, call.callId);
    if (
      toolItemIdByCallId[correlation] !== undefined ||
      pendingToolOrdinalByCallId[correlation] !== undefined
    )
      return undefined;
    const itemId = `item_${params.eventId.slice("evt_".length)}_tool_${call.ordinal}`;
    next = putItem(
      next,
      projectItem(
        next,
        itemId,
        params.threadId,
        params.turnId,
        "tool_call",
        "in_progress",
        undefined,
        {
          title: call.presentation.title,
          metadata: {
            callId: call.callId,
            toolName: call.toolName,
            toolKind: call.presentation.kind,
            presentation: call.presentation,
            relativePaths: call.presentation.relativePaths,
            truncated: call.presentation.truncated,
          },
        },
      ),
    );
    toolItemIdByCallId[correlation] = itemId;
    pendingToolOrdinalByCallId[correlation] = call.ordinal;
  }
  return { ...next, toolItemIdByCallId, pendingToolOrdinalByCallId };
}

/**
 * started 只推进 Prepared Tool 的既有安全投影；callId、ordinal、Turn 与未终结状态必须同时
 * 命中，防止连续调用串线或迟到事件把终态降回 running。
 */
function applyToolStarted(
  state: TimelineState,
  event: ToolStartedEvent,
): TimelineState | undefined {
  const params = event.params;
  const turn = state.turns[params.turnId];
  if (turn?.status !== "running") return undefined;
  const correlation = toolCorrelation(params.threadId, params.turnId, params.callId);
  const itemId = state.toolItemIdByCallId[correlation];
  const item = itemId === undefined ? undefined : state.items[itemId];
  const presentation = item?.metadata?.presentation;
  if (
    itemId === undefined ||
    item === undefined ||
    presentation === undefined ||
    state.pendingToolOrdinalByCallId[correlation] !== params.ordinal ||
    state.liveStartedToolCorrelations[correlation] === true ||
    (item.status !== "started" && item.status !== "in_progress") ||
    (presentation.status !== "pending" && presentation.status !== "running")
  )
    return undefined;
  const updated = updateItem(state, itemId, {
    status: "in_progress",
    metadata: {
      ...item.metadata,
      presentation: { ...presentation, status: "running" },
      requiresUserAction: false,
    },
  });
  if (updated === undefined) return undefined;
  return {
    ...updated,
    liveStartedToolCorrelations: {
      ...updated.liveStartedToolCorrelations,
      [correlation]: true,
    },
  };
}

/**
 * 在一个 Reducer 结果中应用本次已结算的非空 Tool 子集；Java 按 ordinal 逐调用提交，
 * 因此未出现在 results 中的 Tool 必须继续保留 pending/running，不能触发全量 resync。
 */
function applyToolBatchCommitted(
  state: TimelineState,
  event: ToolBatchCommittedEvent,
): TimelineState | undefined {
  const params = event.params;
  const turn = state.turns[params.turnId];
  if (turn?.status !== "running") return undefined;

  let next = state;
  const pendingToolOrdinalByCallId = { ...state.pendingToolOrdinalByCallId };
  const liveStartedToolCorrelations = { ...state.liveStartedToolCorrelations };
  const resultCallIds = new Set<string>();
  for (const result of params.results) {
    if (resultCallIds.has(result.callId)) return undefined;
    resultCallIds.add(result.callId);
    const correlation = toolCorrelation(params.threadId, params.turnId, result.callId);
    const itemId = next.toolItemIdByCallId[correlation];
    const item = itemId === undefined ? undefined : next.items[itemId];
    const presentation = item?.metadata?.presentation;
    if (
      itemId === undefined ||
      item === undefined ||
      presentation === undefined ||
      pendingToolOrdinalByCallId[correlation] !== result.ordinal ||
      (item.status !== "started" && item.status !== "in_progress") ||
      !["pending", "running", "waiting_approval"].includes(presentation.status)
    )
      return undefined;
    const status: TimelineItemStatus =
      result.outcome === "succeeded"
        ? "completed"
        : result.outcome === "cancelled"
          ? "cancelled"
          : "failed";
    const updated = updateItem(next, itemId, {
      status,
      metadata: {
        ...next.items[itemId]?.metadata,
        callId: result.callId,
        toolOutcome: result.outcome,
        toolKind: result.presentation.kind,
        presentation: result.presentation,
        relativePaths: result.presentation.relativePaths,
        truncated: result.presentation.truncated,
        requiresUserAction: false,
      },
    });
    if (updated === undefined) return undefined;
    next = updated;
    delete pendingToolOrdinalByCallId[correlation];
    delete liveStartedToolCorrelations[correlation];
  }
  return { ...next, pendingToolOrdinalByCallId, liveStartedToolCorrelations };
}

/**
 * 自动压缩不是模型可调用工具，仍需占据与其发生位置一致的 Timeline 步骤。用本地 context
 * Presentation 接入既有 Tool 详情组件，可在不扩展 JA-RPC 或持久化模型的前提下保留简短的
 * 生命周期与安全计量事实；失败只显示稳定错误码，避免把服务端诊断正文泄露到阅读区。
 */
function contextCompactionPresentation(
  phase: ContextCompactionProjection["phase"],
  inputTokensBefore: number | null,
  inputTokensAfter: number | null,
  errorCode: string | undefined,
): ToolPresentation {
  const summary =
    phase === "started"
      ? "正在整理已完成的对话内容。"
      : phase === "compacted" && inputTokensBefore !== null && inputTokensAfter !== null
        ? `上下文已从 ${inputTokensBefore.toLocaleString("zh-CN")} Token 压缩至 ${inputTokensAfter.toLocaleString("zh-CN")} Token。`
        : phase === "failed"
          ? `自动压缩未完成：${errorCode ?? "UNKNOWN"}。`
          : "上下文已压缩。";
  return {
    kind: "context",
    title: "上下文自动压缩",
    status: phase === "started" ? "running" : phase === "compacted" ? "success" : "error",
    summary,
    relativePaths: [],
    truncated: false,
  };
}

/**
 * 投影统一 Context lifecycle。started/failed 不推进 durable revision，只有 compacted receipt
 * 在 Checkpoint CAS 后推进一次；手动事件没有 Turn，因此不会制造虚假 Timeline Turn。自动事件
 * 以 compactionId 原位更新为 context Tool Step，确保它留在产生它的回复阅读区而不挤入右侧操作区。
 */
function applyContextCompactionEvent(
  state: TimelineState,
  event: ContextCompactionEvent,
): TimelineState {
  const params = event.params;
  const currentRevision = state.threadRevisionByThread[params.threadId] ?? 0;
  const eventServerInstanceId = params.serverInstanceId;
  if (
    state.serverInstanceId !== undefined &&
    eventServerInstanceId !== undefined &&
    state.serverInstanceId !== eventServerInstanceId
  )
    return resync(state, params.threadId, "server_instance_changed");
  if (params.sourceRevision !== currentRevision) {
    return params.sourceRevision < currentRevision
      ? outcome(state, "late")
      : resync(state, params.threadId, "gap", "gap");
  }
  const advancesRevision = event.method === "context/compacted";
  const expectedRevision = advancesRevision ? currentRevision + 1 : currentRevision;
  if (params.threadRevision !== expectedRevision)
    return resync(state, params.threadId, "invalid_event");
  const turn = params.turnId === null ? undefined : state.turns[params.turnId];
  if (params.turnId !== null && (turn === undefined || isTerminalState(turn.status)))
    return resync(state, params.threadId, "invalid_event");

  const phase =
    event.method === "context/compaction-started"
      ? "started"
      : event.method === "context/compacted"
        ? "compacted"
        : "failed";
  const checkpointId = event.method === "context/compacted" ? event.params.checkpointId : undefined;
  const errorCode =
    event.method === "context/compaction-failed" ? event.params.errorCode : undefined;
  const projection: ContextCompactionProjection = {
    compactionId: params.compactionId,
    threadId: params.threadId,
    turnId: params.turnId,
    trigger: params.trigger,
    phase,
    sourceRevision: params.sourceRevision,
    threadRevision: params.threadRevision,
    inputTokensBefore: params.inputTokensBefore,
    inputTokensAfter: params.inputTokensAfter,
    strategyVersion: params.strategyVersion,
    ...(checkpointId === undefined ? {} : { checkpointId }),
    ...(errorCode === undefined ? {} : { errorCode }),
    occurredAt: params.occurredAt,
  };
  let next: TimelineState = {
    ...state,
    serverInstanceId: state.serverInstanceId ?? eventServerInstanceId,
    contextCompactionByThread: {
      ...state.contextCompactionByThread,
      [params.threadId]: projection,
    },
  };
  if (phase === "compacted") {
    next = {
      ...next,
      contextUsageInvalidatedAtByThread: {
        ...next.contextUsageInvalidatedAtByThread,
        [params.threadId]: params.occurredAt,
      },
    };
  }
  if (params.turnId !== null) {
    next = putItem(next, {
      itemId: `item_compaction_${params.compactionId.slice("cmp_".length)}`,
      threadId: params.threadId,
      turnId: params.turnId,
      kind: "tool_call",
      status: phase === "started" ? "in_progress" : phase === "compacted" ? "completed" : "failed",
      title: "上下文自动压缩",
      metadata: {
        phase,
        compactionId: params.compactionId,
        toolName: "context_compaction",
        toolKind: "context",
        presentation: contextCompactionPresentation(
          phase,
          params.inputTokensBefore,
          params.inputTokensAfter,
          errorCode,
        ),
        inputTokensBefore: params.inputTokensBefore ?? undefined,
        inputTokensAfter: params.inputTokensAfter ?? undefined,
        ...(checkpointId === undefined ? {} : { checkpointId }),
        ...(errorCode === undefined ? {} : { errorCode }),
      },
      createdAt: params.occurredAt,
    });
  }
  if (advancesRevision) next = commitThreadRevision(next, event);
  return { ...rememberEvent(next, params.eventId), lastOutcome: "applied" };
}

/** 队列变化不占用 Thread revision；全量 revision 单调覆盖即可恢复 ACK/Event 乱序。 */
function applyInputQueueChanged(
  state: TimelineState,
  event: InputQueueChangedEvent,
): TimelineState {
  const params = event.params;
  if (state.seenEventIds[params.eventId] === true) return outcome(state, "duplicate");
  if (
    state.handshake.generation !== params.generation ||
    (state.serverInstanceId !== undefined && state.serverInstanceId !== params.serverInstanceId)
  )
    return resync(state, params.threadId, "server_instance_changed");
  const turn = state.turns[params.turnId];
  const thread = state.threads[params.threadId];
  if (
    turn?.threadId !== params.threadId ||
    thread?.workspaceId !== params.workspaceId ||
    params.inputQueue.turnId !== params.turnId
  )
    return resync(state, params.threadId, "invalid_event");
  const merged = applyInputQueue(state, params.inputQueue);
  if (merged.lastOutcome === "invalid" || merged.lastOutcome === "resync_required") return merged;
  return {
    ...rememberEvent(merged, params.eventId),
    lastOutcome: merged.lastOutcome === "late" ? "late" : "applied",
  };
}

/**
 * 消费事件在同一 Thread revision 中提交上一轮 STOP 回复、用户消息和剩余队列，确保队列行
 * 原子迁移进 Timeline；较新的 ACK 队列不会被迟到消费事件回退。
 */
function applyInputConsumed(
  state: TimelineState,
  event: InputConsumedEvent,
): TimelineState | undefined {
  const params = event.params;
  const turn = state.turns[params.turnId];
  if (turn === undefined || isTerminalState(turn.status)) return undefined;
  let next = clearDraft(state, params.turnId);
  const settlement = params.assistantSettlement;
  if (settlement !== undefined) {
    if (settlement.usage !== undefined) {
      const recorded = recordContextUsage(next, params.threadId, params.turnId, settlement.usage);
      if (recorded === undefined) return undefined;
      next = recorded;
    }
    if (settlement.reasoningSummary?.trim())
      next = putItem(next, {
        itemId: `${settlement.messageId}_reasoning`,
        threadId: params.threadId,
        turnId: params.turnId,
        kind: "reasoning",
        status: "completed",
        text: settlement.reasoningSummary,
        title: "思考摘要",
        metadata: { phase: "reasoning_summary", modelRound: settlement.modelRound },
        createdAt: params.occurredAt,
      });
    if (settlement.text.trim())
      next = putItem(next, {
        itemId: settlement.messageId,
        threadId: params.threadId,
        turnId: params.turnId,
        kind: "agent_message",
        status: "completed",
        text: settlement.text,
        title: "Final",
        final: true,
        metadata: {
          modelRound: settlement.modelRound,
          ...usageMetadata(settlement.usage),
        },
        createdAt: params.occurredAt,
      });
  }
  next = putItem(next, {
    itemId: params.userItem.itemId,
    threadId: params.threadId,
    turnId: params.turnId,
    kind: "user_message",
    status: "completed",
    text: textFromUserContent(params.userItem.content),
    contextReferences: contextReferencesFromUserContent(params.userItem.content),
    attachments: params.userItem.attachments,
    createdAt: params.userItem.createdAt,
  });
  const merged = applyInputQueue(next, params.inputQueue);
  return merged.lastOutcome === "invalid" || merged.lastOutcome === "resync_required"
    ? undefined
    : merged;
}

/**
 * 将 Mailbox 消费事件中的每条消息直接投影为独立 Timeline item；它不创建用户输入、活动或
 * 额外状态卡片。事件 identity 负责幂等，item identity 再做一次冲突校验，防止不同事件覆盖
 * 已提交正文。
 */
function applyMessagesReceived(
  state: TimelineState,
  event: MessagesReceivedEvent,
): TimelineState | undefined {
  const params = event.params;
  const turn = state.turns[params.turnId];
  if (turn === undefined || isTerminalState(turn.status)) return undefined;
  const itemIds = new Set<string>();
  let next = state;
  for (const item of params.items) {
    if (itemIds.has(item.itemId) || next.items[item.itemId] !== undefined) return undefined;
    itemIds.add(item.itemId);
    next = putItem(next, projectSnapshotItem(item, params.threadId));
  }
  return next;
}

/**
 * 应用持久事件；终态通知已携带冻结的最终答复、Usage 与 ChangeSet，因此在同一投影事务内收口。
 *
 * 只有 gap、缺失关联或非法事实才请求权威快照；每轮终态后再读历史会造成第二次可见重投影，
 * 却不能补充 v1 terminal 合同之外的信息。
 */
function applyThreadEvent(state: TimelineState, event: ThreadSemanticEvent): TimelineState {
  const currentRevision = state.threadRevisionByThread[event.params.threadId] ?? 0;
  if (event.params.threadRevision <= currentRevision) {
    // Snapshot 已覆盖该 Revision 时，迟到 committed event 只是幂等旧事实，不能再次清除 live Draft。
    return event.params.threadRevision <=
      (state.snapshotRevisionByThread?.[event.params.threadId] ?? -1)
      ? outcome(state, "late")
      : resync(state, event.params.threadId, "late_event", "late");
  }
  const admitted = admitThreadEvent(state, event);
  if (admitted === undefined) return resync(state, event.params.threadId, "gap", "gap");
  let next = admitted;
  const turn = next.turns[event.params.turnId];
  switch (event.method) {
    case "turn/state-changed": {
      const params = event.params;
      const previous = turn?.status ?? params.from;
      if (previous !== params.from || !isLegalTransition(previous, params.to))
        return resync(state, params.threadId, "invalid_event");
      const nextTurn: TimelineTurn = {
        ...(turn ?? { turnId: params.turnId, threadId: params.threadId }),
        turnId: params.turnId,
        threadId: params.threadId,
        status: params.to,
        threadRevision: params.threadRevision,
        ...(turn?.startedAt === undefined ? { startedAt: params.occurredAt } : {}),
      };
      next = { ...next, turns: { ...next.turns, [params.turnId]: nextTurn } };
      const thread = next.threads[params.threadId];
      if (thread !== undefined)
        next = {
          ...next,
          threads: {
            ...next.threads,
            [params.threadId]: {
              ...thread,
              activeTurnId: isTerminalState(params.to) ? undefined : params.turnId,
              latestTurnId: params.turnId,
            },
          },
        };
      break;
    }
    case "assistant/model-step-committed": {
      const projected = applyModelStepCommitted(next, event);
      if (projected === undefined) return resync(state, event.params.threadId, "invalid_event");
      next = projected;
      break;
    }
    case "tool/started": {
      const projected = applyToolStarted(next, event);
      if (projected === undefined) return resync(state, event.params.threadId, "missing_item");
      next = projected;
      break;
    }
    case "tool/batch-committed": {
      const projected = applyToolBatchCommitted(next, event);
      if (projected === undefined) return resync(state, event.params.threadId, "missing_item");
      next = projected;
      break;
    }
    case "turn/input-consumed": {
      const projected = applyInputConsumed(next, event);
      if (projected === undefined) return resync(state, event.params.threadId, "invalid_event");
      next = projected;
      break;
    }
    case "turn/messages_received": {
      const projected = applyMessagesReceived(next, event);
      if (projected === undefined) return resync(state, event.params.threadId, "invalid_event");
      next = projected;
      break;
    }
    case "approval/requested": {
      const params = event.params;
      if (turn?.status !== params.from || !isLegalTransition(params.from, params.to))
        return resync(state, params.threadId, "invalid_event");
      const approval: TimelineApproval = {
        approvalId: params.approvalId,
        threadId: params.threadId,
        turnId: params.turnId,
        threadRevision: params.threadRevision,
        callId: params.callId,
        toolName: params.toolName,
        reason: params.reason,
        expiresAt: params.expiresAt,
      };
      const existing = next.approvalsById[approval.approvalId];
      if (
        existing?.approval !== undefined &&
        JSON.stringify(existing.approval) !== JSON.stringify(approval)
      )
        return resync(state, params.threadId, "invalid_event");
      next = {
        ...next,
        turns: {
          ...next.turns,
          [params.turnId]: { ...turn, status: params.to, threadRevision: params.threadRevision },
        },
        approvalsById: {
          ...next.approvalsById,
          [approval.approvalId]: {
            threadId: params.threadId,
            approval,
            ...(existing?.decision === undefined ? {} : { decision: existing.decision }),
          },
        },
      };
      const waitingTool = updateToolPresentationStatus(
        next,
        params.threadId,
        params.turnId,
        params.callId,
        "waiting_approval",
      );
      if (waitingTool === undefined) return resync(state, params.threadId, "missing_item");
      next = waitingTool;
      next = putItem(
        next,
        projectItem(
          next,
          `item_${params.eventId.slice("evt_".length)}`,
          params.threadId,
          params.turnId,
          "approval",
          existing?.decision === undefined ? "in_progress" : "completed",
          undefined,
          {
            title: `确认 ${params.toolName}`,
            metadata: {
              callId: params.callId,
              toolName: params.toolName,
              requiresUserAction: existing?.decision === undefined,
            },
          },
        ),
      );
      break;
    }
    case "approval/resolved": {
      const params = event.params;
      if (turn?.status !== params.from || !isLegalTransition(params.from, params.to))
        return resync(state, params.threadId, "invalid_event");
      const existing = next.approvalsById[params.approvalId];
      if (existing === undefined) return resync(state, params.threadId, "missing_item");
      next = {
        ...next,
        turns: {
          ...next.turns,
          [params.turnId]: { ...turn, status: params.to, threadRevision: params.threadRevision },
        },
        approvalsById: {
          ...next.approvalsById,
          [params.approvalId]: {
            ...existing,
            decision: params.decision,
            resolvedAt: params.occurredAt,
            closedAt: undefined,
          },
        },
      };
      if (existing.approval === undefined) return resync(state, params.threadId, "missing_item");
      const resumedTool = updateToolPresentationStatus(
        next,
        params.threadId,
        params.turnId,
        existing.approval.callId,
        "running",
      );
      if (resumedTool === undefined) return resync(state, params.threadId, "missing_item");
      next = resumedTool;
      const approvalItem = findItem(next, params.threadId, params.turnId, "approval");
      if (approvalItem !== undefined)
        next = putItem(next, {
          ...approvalItem,
          status: "completed",
          metadata: { ...approvalItem.metadata, requiresUserAction: false },
        });
      break;
    }
    case "turn/terminal": {
      const params = event.params;
      if (
        turn === undefined ||
        !isTerminalState(params.state) ||
        !isLegalTransition(turn.status, params.state)
      )
        return resync(state, params.threadId, "invalid_event");
      next = settleTerminalDraft(next, params.turnId, params.state);
      const terminalStatus: TimelineItemStatus =
        params.state === "completed"
          ? "completed"
          : params.state === "cancelled"
            ? "cancelled"
            : "failed";
      if (params.usage !== undefined) {
        const recorded = recordContextUsage(next, params.threadId, params.turnId, params.usage);
        if (recorded === undefined) return resync(state, params.threadId, "invalid_event");
        next = recorded;
      }
      const terminalText = params.finalMessage?.text;
      const terminalItemId =
        params.finalMessage?.messageId ?? `item_${params.eventId.slice("evt_".length)}`;
      const terminalItem =
        terminalText === undefined || terminalText.trim() === ""
          ? undefined
          : {
              ...projectItem(
                next,
                terminalItemId,
                params.threadId,
                params.turnId,
                "agent_message",
                terminalStatus,
                terminalText,
                {
                  final: true,
                  title: "Final",
                  metadata: {
                    ...usageMetadata(params.usage),
                    ...(params.state === "failed" ? { failureReply: true } : {}),
                  },
                },
              ),
              summary: params.summary,
            };
      if (terminalItem !== undefined) next = putItem(next, terminalItem);
      next = {
        ...next,
        turns: {
          ...next.turns,
          [params.turnId]: {
            ...turn,
            status: params.state,
            completedAt: params.occurredAt,
            threadRevision: params.threadRevision,
            changeSet: params.changeSet,
            ...(params.errorCode === undefined ? {} : { error: turnError(params.errorCode) }),
          },
        },
      };
      const thread = next.threads[params.threadId];
      if (thread !== undefined)
        next = {
          ...next,
          threads: { ...next.threads, [params.threadId]: { ...thread, activeTurnId: undefined } },
        };
      next = closePendingApprovals(next, params.threadId, params.turnId, params.occurredAt);
      const inputQueueByTurn = { ...next.inputQueueByTurn };
      delete inputQueueByTurn[params.turnId];
      // terminal 已在合同层冻结答复、用量和 ChangeSet；只提交一次投影，避免终态后再次 thread/read。
      const resyncRequired = { ...next.resyncRequired };
      if (resyncRequired[params.threadId] === "gap") delete resyncRequired[params.threadId];
      next = { ...next, inputQueueByTurn, resyncRequired };
      break;
    }
    default:
      return outcome(state, "invalid");
  }
  next = rememberEvent(commitThreadRevision(next, event), event.params.eventId);
  return { ...next, lastOutcome: "applied" };
}

/** 校验有界文本与元数据后创建投影 Item，防止超限内容进入状态。 */
function projectItem(
  state: TimelineState,
  itemId: string,
  threadId: string,
  turnId: string,
  kind: TimelineItemKind,
  status: TimelineItemStatus,
  text?: string,
  extras: { title?: string; final?: boolean; metadata?: ItemMetadata } = {},
): TimelineItemAdapter {
  void state;
  return {
    itemId,
    threadId,
    turnId,
    kind,
    status,
    ...(text === undefined ? {} : { text }),
    ...extras,
  };
}

/** Tool 模型步已持久化全部公开片段，因此清理对应瞬态 Draft，防止同一正文重复出现。 */
function clearDraft(state: TimelineState, turnId: string): TimelineState {
  const draftByTurn = { ...state.draftByTurn };
  delete draftByTurn[turnId];
  return { ...state, draftByTurn };
}

/**
 * Terminal 只清理由权威 finalMessage 替代的 assistant Draft；公开 reasoning 需要留到完整历史快照
 * 接管，取消/失败态都保留用户已经看到的半成品正文。失败终态的固定安全回复仍单独投影，
 * 因此半截 Provider 正文只属于 WorkProcess，不会冒充失败答复。
 */
function settleTerminalDraft(
  state: TimelineState,
  turnId: string,
  terminalState: TimelineTurnState,
): TimelineState {
  const current = state.draftByTurn[turnId];
  if (current === undefined) return state;
  const retained = current.filter(
    (draft) =>
      draft.kind === "reasoning" ||
      ((terminalState === "cancelled" || terminalState === "failed") && draft.kind === "assistant"),
  );
  const draftByTurn = { ...state.draftByTurn };
  if (retained.length === 0) delete draftByTurn[turnId];
  else draftByTurn[turnId] = retained;
  return { ...state, draftByTurn };
}

/**
 * Delta 没有持久 Revision 可供恢复，必须先验证原生 generation、server identity、Workspace 与 Turn 归属；
 * 否则旧 WebView/旧 App Server 的首个 delta 会被误当作新流的 seq=1。
 */
function validateLiveDeltaIdentity(
  state: TimelineState,
  threadId: string,
  turnId: string,
  workspaceId: string,
  generation: number,
  serverInstanceId: string | undefined,
): TimelineState | undefined {
  const turn = state.turns[turnId];
  const thread = state.threads[threadId];
  if (
    turn === undefined ||
    turn.threadId !== threadId ||
    thread === undefined ||
    thread.workspaceId !== workspaceId ||
    generation !== state.handshake.generation ||
    serverInstanceId === undefined ||
    serverInstanceId !== state.serverInstanceId
  )
    return resync(state, threadId, "invalid_event");
  return undefined;
}

/** 在 Terminal 边界关闭未解决卡片，但不伪造用户 Decision。 */
function closePendingApprovals(
  state: TimelineState,
  threadId: string,
  turnId: string,
  closedAt: string,
): TimelineState {
  const approvalsById = { ...state.approvalsById };
  for (const [approvalId, projection] of Object.entries(approvalsById)) {
    if (
      projection.threadId === threadId &&
      projection.approval?.turnId === turnId &&
      projection.decision === undefined
    )
      approvalsById[approvalId] = { ...projection, closedAt };
  }
  return { ...state, approvalsById };
}

/**
 * 应用仅 Stream 的 Assistant/Reasoning Delta，并按连续语义保存 segment；Gap 时整体丢弃 Draft。
 * streamSeq 是 Turn 内全局顺序，因此跨语义切换仍能保留 reasoning 与 text 的真实交错关系。
 */
function applyDelta(
  state: TimelineState,
  event: Extract<
    TimelineEvent,
    { method: "assistant/text-delta" | "assistant/reasoning-summary-delta" }
  >,
): TimelineState {
  const params = event.params;
  const identityFailure = validateLiveDeltaIdentity(
    state,
    params.threadId,
    params.turnId,
    params.workspaceId,
    params.generation,
    params.serverInstanceId,
  );
  if (identityFailure !== undefined) return identityFailure;
  const { turnId, streamSeq, text, occurredAt } = params;
  const kind: TimelineDraftProjection["kind"] =
    event.method === "assistant/text-delta" ? "assistant" : "reasoning";
  const turn = turnForStream(state, turnId);
  if (turn === undefined) return resync(state, params.threadId, "invalid_event");
  if (state.resyncRequired[turn.threadId] === "gap") return outcome(state, "resync_required");
  const previous = state.streamSeqByTurn[turnId] ?? 0;
  // Terminal 会保留已展示流的最高游标；覆盖边界以内的迟到 delta 是幂等旧事实，不能再次触发恢复。
  // 边界之后仍到达新 delta 则属于未知事实，继续 fail closed 并等待权威快照。
  if (streamSeq <= previous) return outcome(state, "duplicate");
  if (isTerminalState(turn.status)) {
    const coveredRevision = state.snapshotRevisionByThread?.[turn.threadId];
    if (coveredRevision !== undefined && params.threadRevision <= coveredRevision)
      return outcome(state, "late");
    return resync(state, turn.threadId, "invalid_event");
  }
  if (streamSeq !== previous + 1) return resync(state, turn.threadId, "gap", "gap");
  const priorSegments = state.draftByTurn[turnId] ?? [];
  const prior = priorSegments.at(-1);
  const segments =
    prior === undefined ||
    prior.kind !== kind ||
    utf8ByteLength(prior.text) + utf8ByteLength(text) > MAX_LIVE_SEGMENT_BYTES
      ? [
          ...priorSegments,
          {
            kind,
            text,
            streamSeq,
            segmentStartSeq: streamSeq,
            occurredAt,
          },
        ]
      : [
          ...priorSegments.slice(0, -1),
          {
            ...prior,
            text: prior.text + text,
            streamSeq,
            occurredAt: prior.occurredAt ?? occurredAt,
          },
        ];
  return outcome(
    {
      ...state,
      streamSeqByTurn: { ...state.streamSeqByTurn, [turnId]: streamSeq },
      draftByTurn: { ...state.draftByTurn, [turnId]: segments },
    },
    "applied",
  );
}

/** 应用已校验 JA-RPC Notification；Malformed、Late 与 Gapped 输入统一通过 thread/read 恢复。 */
export function applyLiveEvent(state: TimelineState, event: TimelineEvent): TimelineState {
  if (state.handshake.phase !== "ready") return outcome(state, "rejected");
  if (event.method === "runtime/status-changed" || event.method === "thread/metadata-changed")
    return outcome(state, "rejected");
  if (
    event.method === "assistant/text-delta" ||
    event.method === "assistant/reasoning-summary-delta"
  )
    return applyDelta(state, event);
  if (state.seenEventIds[event.params.eventId] === true) return outcome(state, "duplicate");
  if (event.method === "turn/input-queue-changed") return applyInputQueueChanged(state, event);
  if (isContextCompactionEvent(event)) return applyContextCompactionEvent(state, event);
  if (!isThreadEvent(event)) return outcome(state, "invalid");
  return applyThreadEvent(state, event);
}

/** 在 Zustand 边界解析并应用一个不可信 Event，避免未验证 Payload 进入 Store。 */
export function applyEventValue(state: TimelineState, value: unknown): TimelineState {
  try {
    const event = timelineEventFromUnknown(value);
    return event === undefined ? outcome(state, "invalid") : applyLiveEvent(state, event);
  } catch {
    return outcome(state, "invalid");
  }
}
