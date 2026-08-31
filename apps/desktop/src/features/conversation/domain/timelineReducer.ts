// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  timelineEventFromUnknown,
  timelineSnapshotFromUnknown,
  type TimelineEvent,
  type TimelineSnapshotItem,
} from "./timelineContracts";
import type {
  ApprovalDecision,
  ItemMetadata,
  TimelineApproval,
  TimelineContextUsage,
  TimelineItemAdapter,
  TimelineItemKind,
  TimelineItemStatus,
  ToolPresentation,
  TimelineTurn,
  TimelineTurnState,
} from "./timelineTypes";

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
type ToolBatchCommittedEvent = Extract<ThreadSemanticEvent, { method: "tool/batch-committed" }>;

const EVENT_DEDUP_WINDOW = 1024;
const textEncoder = new TextEncoder();

type ResyncReason =
  | "server_instance_changed"
  | "gap"
  | "late_event"
  | "missing_item"
  | "invalid_event"
  | "projection_fault"
  | "terminal_missing"
  | "terminal_snapshot"
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
  strategyVersion: "ja-context-v3";
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
  submittedAt: string;
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
  approvalsById: Record<string, TimelineApprovalState>;
  contextCompactionByThread: Record<string, ContextCompactionProjection>;
  contextUsageByThread: Record<string, TimelineContextUsage>;
  threadRevisionByThread: Record<string, number>;
  streamSeqByTurn: Record<string, number>;
  /** 未提交的 Assistant/Reasoning 文本；发生重连或 Gap 时必须丢弃。 */
  draftByTurn: Record<string, { kind: "assistant" | "reasoning"; text: string; streamSeq: number }>;
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
    approvalsById: {},
    contextCompactionByThread: {},
    contextUsageByThread: {},
    threadRevisionByThread: {},
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
    approvalsById: {},
    contextCompactionByThread: {},
    contextUsageByThread: {},
    threadRevisionByThread: {},
    streamSeqByTurn: {},
    draftByTurn: {},
    seenEventIds: {},
    seenEventOrder: [],
    resyncRequired: {},
  };
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

/** 仅对六种 Terminal Turn 状态返回 true，与冻结协议保持一致。 */
function isTerminalState(status: TimelineTurnState): boolean {
  return status === "completed" || status === "failed" || status === "cancelled";
}

/** 校验一次六态 Lifecycle 边，不接纳内部 Agent Phase。 */
function isLegalTransition(from: TimelineTurnState, to: TimelineTurnState): boolean {
  const legal: Readonly<Record<TimelineTurnState, readonly TimelineTurnState[]>> = {
    queued: ["running", "completed", "failed", "cancelled"],
    running: ["waiting_approval", "completed", "failed", "cancelled"],
    waiting_approval: ["running", "completed", "failed", "cancelled"],
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

/**
 * 转换持久 Snapshot 事实，但不假装它仍携带 Live Event Stream 的逐 Turn 进度。
 * Tool 历史按 callId 只投影一行，presentation 已包含最新持久状态，与 Live 原位更新保持一致。
 */
function projectSnapshotItem(item: TimelineSnapshotItem, threadId: string): TimelineItemAdapter {
  const base = {
    itemId: item.itemId,
    threadId,
    turnId: item.turnId,
    status: "completed" as const,
    createdAt: item.createdAt,
  };
  switch (item.kind) {
    case "user_input":
      return { ...base, kind: "user_message", text: item.text };
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
        kind: "commentary",
        text: item.text,
        title: "思考摘要",
        metadata: { phase: "reasoning_summary", modelRound: item.modelRound },
      };
    case "final_answer":
      return { ...base, kind: "agent_message", text: item.text, final: true, title: "Final" };
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
    case "attachment": {
      const size =
        item.sizeBytes < 1024
          ? `${item.sizeBytes} B`
          : item.sizeBytes < 1024 * 1024
            ? `${(item.sizeBytes / 1024).toFixed(1)} KB`
            : `${(item.sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
      const kind =
        item.mediaKind === "pdf"
          ? "PDF"
          : item.mediaKind === "image"
            ? "图片"
            : item.mediaKind === "text"
              ? "文本"
              : "文件";
      return {
        ...base,
        kind: "commentary",
        title: item.displayName,
        summary: `${kind} · ${size}`,
        metadata: {
          attachmentId: item.attachmentId,
          sizeBytes: item.sizeBytes,
          mediaKind: item.mediaKind,
          mediaType: item.mediaType,
          attachmentState: item.state,
        },
      };
    }
  }
}

/**
 * 应用完整且未分页的 Thread Snapshot，并丢弃该 Thread 所有 In-flight 投影。
 * Workspace 由 History 调用方提供，因为 Wire Snapshot 明确省略其所有权。
 */
export function applySnapshot(
  state: TimelineState,
  value: unknown,
  workspaceId: string,
): TimelineState {
  const parsed = timelineSnapshotFromUnknown(value);
  if (parsed === undefined || parsed.nextCursor !== null || state.handshake.phase !== "ready") {
    return outcome(
      state,
      parsed !== undefined && state.handshake.phase !== "ready" ? "rejected" : "invalid",
    );
  }
  const snapshot = parsed;
  const priorThread = state.threads[snapshot.threadId];
  if (
    workspaceId.trim() === "" ||
    (priorThread !== undefined && priorThread.workspaceId !== workspaceId)
  ) {
    return outcome(state, "invalid");
  }
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
    delete streamSeqByTurn[threadTurnId];
    delete draftByTurn[threadTurnId];
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
  const contextCompactionByThread = { ...next.contextCompactionByThread };
  delete contextCompactionByThread[snapshot.threadId];
  const contextUsageByThread = { ...next.contextUsageByThread };
  if (snapshot.contextUsage === null) delete contextUsageByThread[snapshot.threadId];
  else contextUsageByThread[snapshot.threadId] = snapshot.contextUsage;
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
            ...(turn.errorCode === null
              ? {}
              : { error: { code: turn.errorCode, retryable: false } }),
            ...(turn.runtime === null ? {} : { runtime: turn.runtime }),
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
    contextCompactionByThread,
    contextUsageByThread,
    threadRevisionByThread: {
      ...next.threadRevisionByThread,
      [snapshot.threadId]: snapshot.revision,
    },
    streamSeqByTurn: {
      ...streamSeqByTurn,
      ...Object.fromEntries(snapshot.turns.map((turn) => [turn.turnId, 0])),
    },
    // Snapshot 是重连的线性化点：该 Thread 中每个 Turn 的 Draft 都是推测状态，不能跨越 Resync。
    draftByTurn,
    resyncRequired: Object.fromEntries(
      Object.entries(next.resyncRequired).filter(([id]) => id !== snapshot.threadId),
    ),
  };
  for (const item of snapshot.items)
    next = putItem(next, projectSnapshotItem(item, snapshot.threadId));
  return outcome(next, "applied");
}

/**
 * 在独立 Event Stream 排空前安装 turn/start 已提交 Revision。Tauri Command Reply 与 Event
 * 使用不同 Channel，因此该 Baseline 是线性化点：它允许 Revision N+1 Event 合法进入，
 * 同时不放宽普通 Gap 校验。
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
  if (accepted.threadRevision <= currentRevision) return outcome(state, "late");
  if (accepted.threadRevision !== currentRevision + 1)
    return resync(state, accepted.threadId, "gap", "gap");
  if (
    Object.values(state.turns).some(
      (turn) => turn.threadId === accepted.threadId && !isTerminalState(turn.status),
    )
  ) {
    return resync(state, accepted.threadId, "invalid_event");
  }
  const thread = state.threads[accepted.threadId];
  if (thread === undefined) return resync(state, accepted.threadId, "missing_item");
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
  if (accepted.submittedText.trim() !== "") {
    next = putItem(next, {
      itemId: `item_local_${accepted.turnId.slice("turn_".length)}`,
      threadId: accepted.threadId,
      turnId: accepted.turnId,
      kind: "user_message",
      status: "completed",
      text: accepted.submittedText,
      createdAt: accepted.submittedAt,
    });
  }
  return outcome(next, "applied");
}

/** 进入语义投影前校验 Event 所有权与 Revision 单调性。 */
function admitThreadEvent(
  state: TimelineState,
  event: ThreadSemanticEvent,
): TimelineState | undefined {
  const params = event.params;
  const currentRevision = state.threadRevisionByThread[params.threadId] ?? 0;
  // v2 Tool Receipt 明确省略 serverInstanceId；只对真实携带该字段的 Event Family 比较身份。
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
  if (params.threadRevision !== currentRevision + 1) return undefined;
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
function usageMetadata(
  usage: { inputTokens: number; outputTokens: number; totalTokens: number } | undefined,
): ItemMetadata | undefined {
  return usage === undefined
    ? undefined
    : {
        usageInputTokens: usage.inputTokens,
        usageOutputTokens: usage.outputTokens,
        usageTotalTokens: usage.totalTokens,
      };
}

/**
 * 将 Provider Usage 独立于可见文本保存到 Thread；Tool-only ModelStep 也必须推进真实计量。
 * 任一数值越界或总量不一致都会拒绝整个事件，不能用部分字段渲染貌似精确的百分比。
 */
function recordContextUsage(
  state: TimelineState,
  threadId: string,
  turnId: string,
  modelRound: number,
  usage: { inputTokens: number; outputTokens: number; totalTokens: number },
  measuredAt: string,
): TimelineState | undefined {
  const minimumTotal = usage.inputTokens + usage.outputTokens;
  if (
    !Number.isSafeInteger(modelRound) ||
    modelRound < 1 ||
    modelRound > 128 ||
    !Number.isSafeInteger(usage.inputTokens) ||
    usage.inputTokens < 0 ||
    !Number.isSafeInteger(usage.outputTokens) ||
    usage.outputTokens < 0 ||
    !Number.isSafeInteger(usage.totalTokens) ||
    usage.totalTokens < 0 ||
    !Number.isSafeInteger(minimumTotal) ||
    usage.totalTokens < minimumTotal ||
    !Number.isFinite(Date.parse(measuredAt))
  )
    return undefined;
  return {
    ...state,
    contextUsageByThread: {
      ...state.contextUsageByThread,
      [threadId]: {
        turnId,
        modelRound,
        inputTokens: usage.inputTokens,
        outputTokens: usage.outputTokens,
        totalTokens: usage.totalTokens,
        measuredAt,
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
    const recorded = recordContextUsage(
      next,
      params.threadId,
      params.turnId,
      params.modelRound,
      params.usage,
      params.occurredAt,
    );
    if (recorded === undefined) return undefined;
    next = recorded;
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
  if (params.reasoningSummary?.trim()) {
    next = putItem(
      next,
      projectItem(
        next,
        `${params.messageId}_reasoning`,
        params.threadId,
        params.turnId,
        "commentary",
        "completed",
        params.reasoningSummary,
        {
          title: "思考摘要",
          metadata: { phase: "reasoning_summary", modelRound: params.modelRound },
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
 * 在一个 Reducer 结果中应用完整 Tool 事务，使结果 presentation 与状态不会在不同
 * Revision 或 Subscription 中被观察到。
 */
function applyToolBatchCommitted(
  state: TimelineState,
  event: ToolBatchCommittedEvent,
): TimelineState | undefined {
  const params = event.params;
  const turn = state.turns[params.turnId];
  if (turn?.status !== "running") return undefined;
  const pendingPrefix = `${params.threadId}:${params.turnId}:`;
  const pending = Object.entries(state.pendingToolOrdinalByCallId).filter(([key]) =>
    key.startsWith(pendingPrefix),
  );
  if (pending.length !== params.results.length) return undefined;

  let next = state;
  const pendingToolOrdinalByCallId = { ...state.pendingToolOrdinalByCallId };
  for (const result of params.results) {
    const correlation = toolCorrelation(params.threadId, params.turnId, result.callId);
    const itemId = next.toolItemIdByCallId[correlation];
    if (itemId === undefined || pendingToolOrdinalByCallId[correlation] !== result.ordinal)
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
  }
  return { ...next, pendingToolOrdinalByCallId };
}

/**
 * 投影统一 Context lifecycle。started/failed 不推进 durable revision，只有 compacted receipt
 * 在 Checkpoint CAS 后推进一次；手动事件没有 Turn，因此不会制造虚假 Timeline Turn。
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
  if (params.turnId !== null) {
    next = putItem(next, {
      itemId: `item_compaction_${params.compactionId.slice("cmp_".length)}`,
      threadId: params.threadId,
      turnId: params.turnId,
      kind: "commentary",
      status: phase === "started" ? "in_progress" : phase === "compacted" ? "completed" : "failed",
      title:
        phase === "started"
          ? "正在压缩上下文"
          : phase === "compacted"
            ? "上下文已压缩"
            : "上下文压缩失败",
      metadata: {
        phase,
        compactionId: params.compactionId,
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

/** 按公开六态 Lifecycle 与已提交 Item 规则应用一个语义 Event。 */
function applyThreadEvent(state: TimelineState, event: ThreadSemanticEvent): TimelineState {
  const currentRevision = state.threadRevisionByThread[event.params.threadId] ?? 0;
  if (event.params.threadRevision <= currentRevision) {
    return resync(state, event.params.threadId, "late_event", "late");
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
    case "tool/batch-committed": {
      const projected = applyToolBatchCommitted(next, event);
      if (projected === undefined) return resync(state, event.params.threadId, "missing_item");
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
      next = clearDraft(next, params.turnId);
      const terminalStatus: TimelineItemStatus =
        params.state === "completed"
          ? "completed"
          : params.state === "cancelled"
            ? "cancelled"
            : "failed";
      if (params.usage !== undefined) {
        const recorded = recordContextUsage(
          next,
          params.threadId,
          params.turnId,
          params.usage.modelRound,
          params.usage,
          params.occurredAt,
        );
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
                  metadata: usageMetadata(params.usage),
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
            ...(params.errorCode === undefined
              ? {}
              : { error: { code: params.errorCode, retryable: false } }),
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
      break;
    }
    default:
      return outcome(state, "invalid");
  }
  next = rememberEvent(commitThreadRevision(next, event), event.params.eventId);
  // Rust 在转发 terminal 前已经提交本轮 ChangeSet，但 terminal frame 不重复携带该大对象；
  // 先保留可见终态与 Final，再请求一次权威 Snapshot 补齐冻结差异。
  return event.method === "turn/terminal"
    ? resync(next, event.params.threadId, "terminal_snapshot")
    : { ...next, lastOutcome: "applied" };
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

/** Assistant Message 提交或 Terminal Event 到达后移除对应瞬态 Draft。 */
function clearDraft(state: TimelineState, turnId: string): TimelineState {
  const draftByTurn = { ...state.draftByTurn };
  delete draftByTurn[turnId];
  return { ...state, draftByTurn };
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

/** 应用仅 Stream 的 Assistant/Reasoning Delta，并在 Gap 时丢弃 Draft。 */
function applyDelta(
  state: TimelineState,
  turnId: string,
  streamSeq: number,
  text: string,
  kind: "assistant" | "reasoning",
): TimelineState {
  const turn = turnForStream(state, turnId);
  if (turn === undefined || isTerminalState(turn.status))
    return resync(state, turn?.threadId ?? "runtime", "invalid_event");
  const previous = state.streamSeqByTurn[turnId] ?? 0;
  if (streamSeq <= previous) return outcome(state, "duplicate");
  if (streamSeq !== previous + 1) return resync(state, turn.threadId, "gap", "gap");
  const prior = state.draftByTurn[turnId];
  const draft =
    prior === undefined || prior.kind !== kind
      ? { kind, text, streamSeq }
      : { kind, text: prior.text + text, streamSeq };
  return outcome(
    {
      ...state,
      streamSeqByTurn: { ...state.streamSeqByTurn, [turnId]: streamSeq },
      draftByTurn: { ...state.draftByTurn, [turnId]: draft },
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
    return applyDelta(
      state,
      event.params.turnId,
      event.params.streamSeq,
      event.params.text,
      event.method === "assistant/text-delta" ? "assistant" : "reasoning",
    );
  if (state.seenEventIds[event.params.eventId] === true) return outcome(state, "duplicate");
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
