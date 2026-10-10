// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  ApprovalDecision,
  ProviderRequestProfile,
  TimelineContextUsage,
  TimelineThreadContextUsage,
  TimelineOrigin,
  TimelineTurnState,
  ToolPresentation,
  TurnChangeSet,
} from "./timelineTypes";
import type { UserContentBlock } from "./userContent";

interface SnapshotItemBase {
  itemId: string;
  createdAt: string;
  turnId: string;
  origin: TimelineOrigin;
}

type WithoutTimelineOrigin<T> = T extends unknown ? Omit<T, "origin"> : never;
type TimelineLiveItem = WithoutTimelineOrigin<TimelineSnapshotItem>;

interface PublicTextPreview {
  truncated: boolean;
  totalCharacters: number;
}

/** 与平坦 items 并列的权威 Turn 元数据，失败码不包含服务端错误正文。 */
interface TimelineSnapshotTurn {
  turnId: string;
  origin: TimelineOrigin;
  /** 隐藏 continuation 在 reload 时回到原问题 exchange；普通与 reask Turn 显式为 null。 */
  sourceMessageId?: string | null;
  status: TimelineTurnState;
  requestedAt: string;
  updatedAt: string;
  completedAt: string | null;
  errorCode: string | null;
  changeSet: TurnChangeSet | null;
}

/** Thread snapshot 的持久 Item 闭集与 JA-RPC v2 保持一致，但不依赖 transport Schema。 */
export type TimelineSnapshotItem =
  | (SnapshotItemBase &
      PublicTextPreview & {
        kind: "user_input";
        content: UserContentBlock[];
        attachments: AttachmentSummary[];
      })
  | (SnapshotItemBase &
      PublicTextPreview & {
        kind: "thread_message";
        sourceThreadId: string;
        sourceTitle: string;
        content: string;
      })
  | (SnapshotItemBase &
      PublicTextPreview & {
        kind: "assistant_progress";
        text: string;
        modelRound: number;
      })
  | (SnapshotItemBase &
      PublicTextPreview & {
        kind: "reasoning_summary";
        text: string;
        modelRound: number;
      })
  | (SnapshotItemBase & PublicTextPreview & { kind: "final_answer"; text: string })
  | (SnapshotItemBase & {
      kind: "tool_call";
      callId: string;
      toolName: string;
      ordinal: number;
      presentation: ToolPresentation;
    })
  | (SnapshotItemBase & {
      kind: "approval";
      approvalId: string;
      callId: string;
      toolName: string;
      reason: string;
      expiresAt: string;
      decision: ApprovalDecision | null;
    });

export interface AttachmentSummary {
  attachmentId: string;
  displayName: string;
  sizeBytes: number;
  mediaKind: "text" | "image" | "pdf" | "binary";
  mediaType: string;
}

/** Renderer 只持有 App Server 签发的队列 identity；数组顺序就是下一步消费顺序。 */
export interface QueuedInput {
  inputId: string;
  turnId: string;
  content: UserContentBlock[];
  attachments: AttachmentSummary[];
  kind: "follow_up" | "steering";
  status: "pending" | "needs_attention";
  issue: { errorCode: string; message: string; retryable: boolean } | null;
  inputRevision: number;
  createdAt: string;
}

export interface InputQueue {
  turnId: string;
  revision: number;
  accepting: boolean;
  items: QueuedInput[];
}

export type TimelineTaskActivityKind =
  | "created"
  | "dispatched"
  | "message_sent"
  | "follow_up_queued"
  | "progress"
  | "waiting_approval"
  | "waiting_input"
  | "resumed"
  | "completed"
  | "failed"
  | "cancelled"
  | "suspended";

/** Task 初始创建阶段没有 Turn，故其状态闭集独立于普通 Turn 状态。 */
export type TimelineTaskState = "idle" | TimelineTurnState;

/** Conversation 只保存主 Timeline 卡片所需的 Task 摘要，不反向依赖完整 Task feature。 */
export interface TimelineTaskSummary {
  taskThreadId: string;
  parentThreadId: string;
  rootThreadId: string;
  originTurnId: string | null;
  taskName: string;
  depth: number;
  taskKind: "side_task" | "subagent";
  lifecycle: "independent" | "attached";
  state: TimelineTaskState;
  revision: number;
  latestActivitySequence: number;
  unreadCount: number;
  descendantCount: number;
  runningDescendantCount: number;
  needsAttentionCount: number;
  latestSafeSummary: string | null;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

/** Thread snapshot 中的 Activity 只携带可公开摘要，原始思考和 Child Transcript 不进入父投影。 */
export interface TimelineTaskActivity {
  activitySequence: number;
  activityId: string;
  rootThreadId: string;
  taskThreadId: string;
  actorThreadId: string;
  causalTurnId: string | null;
  kind: TimelineTaskActivityKind;
  summary: { text: string };
  createdAt: string;
}

export interface TimelineTaskActivityEntry {
  activity: TimelineTaskActivity;
  task: TimelineTaskSummary;
}

/** thread/read 的可恢复 live 基线；reasoningSummary 是 wire 语义，Renderer 再投影为 reasoning。 */
export interface TimelineLiveStreamSegment {
  kind: "assistant" | "reasoningSummary";
  segmentStartSeq: number;
  streamSeq: number;
  text: string;
  occurredAt: string;
}

/** 非空 segments 的末段必须等于 streamSeq；仅空 segments 可用正序号表示前序已 committed。 */
export interface TimelineLiveStream {
  turnId: string;
  streamSeq: number;
  segments: TimelineLiveStreamSegment[];
}

export interface TimelineSnapshot {
  threadId: string;
  revision: number;
  turns: TimelineSnapshotTurn[];
  items: TimelineSnapshotItem[];
  inputQueue: InputQueue | null;
  contextUsage: TimelineThreadContextUsage | null;
  taskActivities: TimelineTaskActivityEntry[];
  liveStream: TimelineLiveStream | null;
  nextCursor: string | null;
}

interface ThreadSemanticBase {
  serverInstanceId?: string;
  eventId: string;
  sequence: number;
  generation: number;
  workspaceId: string;
  threadId: string;
  threadRevision: number;
  occurredAt: string;
}

interface SemanticBase extends ThreadSemanticBase {
  turnId: string;
}

interface ContextCompactionBase extends ThreadSemanticBase {
  turnId: string | null;
  compactionId: string;
  trigger: "automatic" | "manual" | "overflow_recovery";
  sourceRevision: number;
  strategyVersion: "ja-context-v1";
}

type UsageProjection = TimelineContextUsage;

type EventEnvelope<M extends string, P> = { jsonrpc: "2.0"; method: M; params: P };

/**
 * Reducer 只依赖会影响状态转换的事件字段；严格 wire 校验仍由 api adapter 执行，domain
 * 在此保留封闭判别联合以获得穷尽分支，而不是引用 Tauri 或 transport 实现。
 */
export type TimelineEvent =
  | EventEnvelope<
      "turn/input-queue-changed",
      Omit<ThreadSemanticBase, "threadRevision"> & {
        turnId: string;
        inputQueue: InputQueue;
      }
    >
  | EventEnvelope<
      "turn/input-consumed",
      SemanticBase & {
        input: QueuedInput;
        userItem: Extract<TimelineLiveItem, { kind: "user_input" }>;
        inputQueue: InputQueue;
        assistantSettlement?: {
          messageId: string;
          text: string;
          modelRound: number;
          usage?: UsageProjection;
          reasoningSummary?: string;
        };
      }
    >
  | EventEnvelope<
      "turn/messages_received",
      SemanticBase & {
        items: Array<Extract<TimelineLiveItem, { kind: "thread_message" }>>;
      }
    >
  | EventEnvelope<
      "turn/state-changed",
      SemanticBase & { from: TimelineTurnState; to: TimelineTurnState }
    >
  | EventEnvelope<"turn/retry-started", SemanticBase & { attempt: number }>
  | EventEnvelope<
      "assistant/model-step-committed",
      SemanticBase & {
        messageId: string;
        text: string;
        modelRound: number;
        usage?: UsageProjection;
        reasoningSummary?: string;
        toolCalls: Array<{
          callId: string;
          toolName: string;
          ordinal: number;
          presentation: ToolPresentation;
        }>;
      }
    >
  | EventEnvelope<"assistant/context-estimated", SemanticBase & { usage: UsageProjection }>
  | EventEnvelope<
      "assistant/text-delta" | "assistant/reasoning-summary-delta",
      SemanticBase & { streamSeq: number; text: string }
    >
  | EventEnvelope<"tool/started", SemanticBase & { callId: string; ordinal: number }>
  | EventEnvelope<
      "tool/batch-committed",
      SemanticBase & {
        results: Array<{
          callId: string;
          outcome: "succeeded" | "failed" | "cancelled";
          ordinal: number;
          errorCode?: string;
          presentation: ToolPresentation;
        }>;
      }
    >
  | EventEnvelope<
      "tool/recovery-changed",
      SemanticBase & {
        recoveryId: string;
        callId: string;
        revision: number;
        status: "pending" | "verified" | "retried" | "skipped";
        disposition?: "verified" | "retry" | "skip";
        requiresUserAction: boolean;
      }
    >
  | EventEnvelope<
      "approval/requested",
      SemanticBase & {
        approvalId: string;
        callId: string;
        toolName: string;
        presentation: ToolPresentation;
        reason: string;
        expiresAt: string;
        from: "running";
        to: "waiting_approval";
      }
    >
  | EventEnvelope<
      "approval/resolved",
      SemanticBase & {
        approvalId: string;
        decision: ApprovalDecision;
        from: "waiting_approval";
        to: "running";
      }
    >
  | EventEnvelope<
      "approval/closed",
      SemanticBase & { approvalId: string; reason: "cancelled" | "superseded" | "expired" }
    >
  | EventEnvelope<
      "context/compaction-started",
      ContextCompactionBase & {
        inputTokensBefore: number;
        inputTokensAfter: null;
      }
    >
  | EventEnvelope<
      "context/compacted",
      ContextCompactionBase & {
        checkpointId: string;
        inputTokensBefore: number;
        inputTokensAfter: number;
      }
    >
  | EventEnvelope<
      "context/compaction-failed",
      ContextCompactionBase & {
        inputTokensBefore: number | null;
        inputTokensAfter: null;
        errorCode:
          | "THREAD_NOT_FOUND"
          | "CONFLICT"
          | "THREAD_BUSY"
          | "SUMMARY_FAILURE"
          | "CONTEXT_LIMIT"
          | "CANCELLED"
          | "INVALID_STATE";
      }
    >
  | EventEnvelope<
      "turn/terminal",
      SemanticBase & {
        state: "completed" | "failed" | "cancelled";
        summary: string;
        summaryTotalCharacters: number;
        summaryTruncated: boolean;
        finalMessage?: {
          messageId: string;
          text: string;
          totalCharacters: number;
          truncated: boolean;
        };
        usage?: UsageProjection;
        errorCode?: string;
        errorMessage?: string;
        changeSet: TurnChangeSet | null;
      }
    >
  | EventEnvelope<
      "runtime/status-changed",
      {
        serverInstanceId: string;
        eventId: string;
        sequence: number;
        occurredAt: string;
        status: "starting" | "ready" | "shutting_down" | "stopped" | "failed";
        generation: number;
      }
    >
  | EventEnvelope<
      "thread/metadata-changed",
      {
        serverInstanceId: string;
        eventId: string;
        sequence: number;
        occurredAt: string;
        generation: number;
        workspaceId: string;
        threadId: string;
        revision: number;
        title: string;
        titleSource: "placeholder" | "auto" | "manual";
      }
    >
  | EventEnvelope<
      "workspace/metadata-changed",
      {
        serverInstanceId: string;
        eventId: string;
        sequence: number;
        occurredAt: string;
        generation: number;
        workspaceId: string;
        revision: number;
      }
    >
  | EventEnvelope<
      "configuration/changed",
      {
        serverInstanceId: string;
        eventId: string;
        sequence: number;
        occurredAt: string;
        generation: number;
        scope: "user" | "project";
        workspaceId?: string;
        version: string;
      }
    >;

const TIMELINE_METHODS = new Set<TimelineEvent["method"]>([
  "turn/input-queue-changed",
  "turn/input-consumed",
  "turn/messages_received",
  "turn/state-changed",
  "turn/retry-started",
  "assistant/model-step-committed",
  "assistant/context-estimated",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/started",
  "tool/batch-committed",
  "tool/recovery-changed",
  "approval/requested",
  "approval/resolved",
  "approval/closed",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "turn/terminal",
  "runtime/status-changed",
  "thread/metadata-changed",
  "workspace/metadata-changed",
  "configuration/changed",
]);

const WORKSPACE_SCOPED_METHODS = new Set<TimelineEvent["method"]>([
  "turn/input-queue-changed",
  "turn/input-consumed",
  "turn/messages_received",
  "turn/state-changed",
  "turn/retry-started",
  "assistant/model-step-committed",
  "assistant/context-estimated",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/started",
  "tool/batch-committed",
  "tool/recovery-changed",
  "approval/requested",
  "approval/resolved",
  "approval/closed",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "turn/terminal",
  "thread/metadata-changed",
  "workspace/metadata-changed",
]);

/**
 * 为 domain 直接调用提供 fail-closed 的最小形状 admission；终态缺失 ChangeSet 一律拒绝，
 * completed 必须携带对象，failed/cancelled 的 null 只表示没有已提交的 ChangeSet 事实。
 * Production store 在进入此处前由 canonical JA-RPC Schema 完整校验；live item 在此投影 native 仅用于复用 snapshot payload 校验。 @author kongweiguang
 */
export function timelineEventFromUnknown(value: unknown): TimelineEvent | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const root = value as Record<string, unknown>;
  if (
    root["jsonrpc"] !== "2.0" ||
    typeof root["method"] !== "string" ||
    !TIMELINE_METHODS.has(root["method"] as TimelineEvent["method"])
  )
    return undefined;
  if (
    typeof root["params"] !== "object" ||
    root["params"] === null ||
    Array.isArray(root["params"])
  )
    return undefined;
  const params = root["params"] as Record<string, unknown>;
  if (
    WORKSPACE_SCOPED_METHODS.has(root["method"] as TimelineEvent["method"]) &&
    (typeof params["workspaceId"] !== "string" || params["workspaceId"].trim() === "")
  )
    return undefined;
  if (root["method"] === "assistant/model-step-committed") {
    if (!Array.isArray(params["toolCalls"])) return undefined;
    if (
      params["usage"] !== undefined &&
      (!isTimelineContextUsage(params["usage"]) ||
        params["usage"].modelRound !== params["modelRound"])
    )
      return undefined;
    if (
      !params["toolCalls"].every((call) => {
        if (typeof call !== "object" || call === null) return false;
        return isToolPresentation((call as Record<string, unknown>)["presentation"]);
      })
    )
      return undefined;
  }
  if (root["method"] === "assistant/context-estimated") {
    const usage = params["usage"];
    if (
      !isTimelineContextUsage(usage) ||
      usage.certainty !== "unknown" ||
      usage.purpose !== "assistant" ||
      usage.contextEstimate == null
    )
      return undefined;
  }
  if (root["method"] === "tool/started") {
    if (
      typeof params["callId"] !== "string" ||
      !Number.isSafeInteger(params["ordinal"]) ||
      (params["ordinal"] as number) < 0 ||
      (params["ordinal"] as number) > 1_023
    )
      return undefined;
  }
  if (root["method"] === "tool/batch-committed") {
    if (!Array.isArray(params["results"])) return undefined;
    const callIds = new Set<string>();
    if (
      !params["results"].every((result) => {
        if (typeof result !== "object" || result === null) return false;
        const candidate = result as Record<string, unknown>;
        const callId = candidate["callId"];
        if (typeof callId !== "string") return false;
        const valid =
          Number.isSafeInteger(candidate["ordinal"]) &&
          (candidate["ordinal"] as number) >= 0 &&
          (candidate["ordinal"] as number) <= 1_023 &&
          ["succeeded", "failed", "cancelled"].includes(String(candidate["outcome"])) &&
          isToolPresentation(candidate["presentation"]);
        if (!valid || callIds.has(callId)) return false;
        callIds.add(callId);
        return true;
      })
    )
      return undefined;
  }
  if (root["method"] === "approval/requested") {
    const presentation = params["presentation"];
    if (
      !isToolPresentation(presentation) ||
      presentation.status !== "pending" ||
      (presentation.media !== undefined && presentation.media.length > 0)
    )
      return undefined;
  }
  if (root["method"] === "turn/input-queue-changed") {
    if (!isInputQueue(params["inputQueue"]) || params["turnId"] !== params["inputQueue"].turnId)
      return undefined;
  }
  if (root["method"] === "turn/input-consumed") {
    const input = params["input"];
    const userItem = params["userItem"];
    if (
      !isQueuedInput(input) ||
      !isInputQueue(params["inputQueue"]) ||
      !isLiveUserInputItem(userItem) ||
      userItem.kind !== "user_input" ||
      input.turnId !== params["turnId"] ||
      userItem.turnId !== params["turnId"] ||
      params["inputQueue"].turnId !== params["turnId"] ||
      JSON.stringify(input.content) !== JSON.stringify(userItem.content)
    )
      return undefined;
    const settlement = params["assistantSettlement"] as Record<string, unknown> | undefined;
    if (
      settlement?.["usage"] !== undefined &&
      (!isTimelineContextUsage(settlement["usage"]) ||
        (settlement["usage"] as TimelineContextUsage).modelRound !== settlement["modelRound"])
    )
      return undefined;
  }
  if (root["method"] === "turn/messages_received") {
    const items = params["items"];
    if (
      !Array.isArray(items) ||
      items.length === 0 ||
      items.length > 256 ||
      !items.every((item) => isLiveThreadMessageItem(item, params["turnId"]))
    )
      return undefined;
    const itemIds = items.map((item) => (item as Record<string, unknown>)["itemId"]);
    if (new Set(itemIds).size !== itemIds.length) return undefined;
  }
  if (root["method"] === "turn/terminal") {
    if (params["usage"] !== undefined && !isTimelineContextUsage(params["usage"])) return undefined;
    if (
      typeof params["summary"] !== "string" ||
      !isPublicTextPreview(
        params["summary"],
        params["summaryTruncated"],
        params["summaryTotalCharacters"],
      )
    )
      return undefined;
    if (params["finalMessage"] !== undefined) {
      const finalMessage = params["finalMessage"];
      if (typeof finalMessage !== "object" || finalMessage === null || Array.isArray(finalMessage))
        return undefined;
      const finalMessageRecord = finalMessage as Record<string, unknown>;
      if (
        typeof finalMessageRecord["messageId"] !== "string" ||
        typeof finalMessageRecord["text"] !== "string" ||
        !isPublicTextPreview(
          finalMessageRecord["text"] as string,
          finalMessageRecord["truncated"],
          finalMessageRecord["totalCharacters"],
        )
      )
        return undefined;
    }
    const state = params["state"];
    if (state !== "completed" && state !== "failed" && state !== "cancelled") return undefined;
    const changeSet = params["changeSet"];
    if (
      (changeSet === null && state === "completed") ||
      (changeSet !== null && !isTurnChangeSet(changeSet))
    )
      return undefined;
  }
  return value as TimelineEvent;
}

/**
 * 返回稳定且不含业务正文的 Snapshot 拒绝原因，供 reducer 单测与隔离真窗诊断复用。
 * Production UI 只消费通过/拒绝结果；已验证页面的本地合并可跨 200 条，其余 wire 页仍维持
 * 单页合同。这里刻意不返回字段值，避免诊断 seam 变成数据旁路。
 */
export function timelineSnapshotValidationReason(
  value: unknown,
  accumulatedHistory = false,
): string | undefined {
  if (typeof value !== "object" || value === null) return "root_type";
  const root = value as Record<string, unknown>;
  if (
    typeof root["threadId"] !== "string" ||
    !Number.isSafeInteger(root["revision"]) ||
    (root["revision"] as number) < 0
  )
    return "identity_or_revision";
  if (!Array.isArray(root["turns"]) || (!accumulatedHistory && root["turns"].length > 200))
    return "turns_shape";
  const invalidTurn = root["turns"].findIndex((turn) => !isTimelineSnapshotTurn(turn));
  if (invalidTurn >= 0) return `turn:${invalidTurn}`;
  if (!Array.isArray(root["items"]) || (!accumulatedHistory && root["items"].length > 200))
    return "items_shape";
  if (!Array.isArray(root["taskActivities"]) || root["taskActivities"].length > 128)
    return "task_activities_shape";
  const invalidTaskActivity = root["taskActivities"].findIndex(
    (entry) => !isTimelineTaskActivityEntry(entry, root["threadId"] as string),
  );
  if (invalidTaskActivity >= 0) return `task_activity:${invalidTaskActivity}`;
  if (!("inputQueue" in root) || (root["inputQueue"] !== null && !isInputQueue(root["inputQueue"])))
    return "input_queue";
  if (!("contextUsage" in root)) return "context_usage_missing";
  if (root["contextUsage"] !== null && !isThreadContextUsage(root["contextUsage"]))
    return "context_usage";
  if (!("liveStream" in root)) return "live_stream_missing";
  if (root["liveStream"] !== null) {
    if (!isTimelineLiveStream(root["liveStream"])) return "live_stream";
    const liveStream = root["liveStream"] as TimelineLiveStream;
    const owner = (root["turns"] as TimelineSnapshotTurn[]).find(
      (turn) => turn.turnId === liveStream.turnId,
    );
    if (owner === undefined || ["completed", "failed", "cancelled"].includes(owner.status))
      return "live_stream_owner";
  }
  if (root["nextCursor"] !== null && typeof root["nextCursor"] !== "string") return "next_cursor";
  const invalidItem = root["items"].findIndex((item) => !isTimelineSnapshotItem(item));
  if (invalidItem >= 0) return `item:${invalidItem}`;
  const turns = root["turns"] as TimelineSnapshotTurn[];
  const items = root["items"] as TimelineSnapshotItem[];
  if (
    items.some((item) => {
      const owner = turns.find((turn) => turn.turnId === item.turnId);
      return owner === undefined || owner.origin !== item.origin;
    })
  )
    return "item_origin_owner";
  return undefined;
}

const MAX_LIVE_STREAM_SEGMENTS = 256;
const MAX_LIVE_STREAM_SEGMENT_BYTES = 64 * 1024;
const MAX_LIVE_STREAM_TOTAL_BYTES = 1024 * 1024;
const LIVE_STREAM_KEYS = new Set(["turnId", "streamSeq", "segments"]);
const LIVE_STREAM_SEGMENT_KEYS = new Set([
  "kind",
  "segmentStartSeq",
  "streamSeq",
  "text",
  "occurredAt",
]);

/** 独立复核 TimestampSchema 的 RFC3339 offset 形状与真实日历，避免 domain 绕过 transport 边界。 */
function isWireTimestamp(value: unknown): value is string {
  if (typeof value !== "string" || value.length > 64) return false;
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (match === null) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const offset = match[7];
  if (offset === undefined) return false;
  const offsetHour = offset === "Z" ? 0 : Number(offset.slice(1, 3));
  const offsetMinute = offset === "Z" ? 0 : Number(offset.slice(4, 6));
  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  return (
    month >= 1 &&
    month <= 12 &&
    day >= 1 &&
    day <= daysInMonth &&
    hour <= 23 &&
    minute <= 59 &&
    second <= 59 &&
    offsetHour <= 23 &&
    offsetMinute <= 59 &&
    !Number.isNaN(Date.parse(value))
  );
}

/** 保持 liveStream 与其 segment 的 strict wire object 语义，未知键不得进入 Renderer。 */
function hasOnlyKeys(value: Record<string, unknown>, keys: ReadonlySet<string>): boolean {
  return Object.keys(value).every((key) => keys.has(key));
}

/**
 * 校验 live baseline 的序列连续性和容量边界；首段可从任意正序号开始，因为前序段可能已提交。
 * 空 segments 与正 streamSeq 合法，表示当前 Turn 的前序 stream 已经落入持久 committed 事实。
 */
function isTimelineLiveStream(value: unknown): value is TimelineLiveStream {
  if (typeof value !== "object" || value === null) return false;
  const root = value as Record<string, unknown>;
  const segments = root["segments"];
  if (
    !hasOnlyKeys(root, LIVE_STREAM_KEYS) ||
    typeof root["turnId"] !== "string" ||
    !/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/.test(root["turnId"] as string) ||
    !Number.isSafeInteger(root["streamSeq"]) ||
    (root["streamSeq"] as number) < 0 ||
    !Array.isArray(segments) ||
    segments.length > MAX_LIVE_STREAM_SEGMENTS
  )
    return false;
  let totalBytes = 0;
  let previousStreamSeq: number | undefined;
  for (const segment of segments) {
    if (typeof segment !== "object" || segment === null) return false;
    const candidate = segment as Record<string, unknown>;
    const segmentStartSeq = candidate["segmentStartSeq"];
    const streamSeq = candidate["streamSeq"];
    const text = candidate["text"];
    if (
      !hasOnlyKeys(candidate, LIVE_STREAM_SEGMENT_KEYS) ||
      (candidate["kind"] !== "assistant" && candidate["kind"] !== "reasoningSummary") ||
      !Number.isSafeInteger(segmentStartSeq) ||
      (segmentStartSeq as number) < 1 ||
      !Number.isSafeInteger(streamSeq) ||
      (streamSeq as number) < (segmentStartSeq as number) ||
      (previousStreamSeq !== undefined && (streamSeq as number) <= previousStreamSeq) ||
      typeof text !== "string" ||
      text.length === 0 ||
      text.includes("\u0000") ||
      !isWireTimestamp(candidate["occurredAt"])
    )
      return false;
    const bytes = new TextEncoder().encode(text).byteLength;
    if (bytes > MAX_LIVE_STREAM_SEGMENT_BYTES) return false;
    totalBytes += bytes;
    if (totalBytes > MAX_LIVE_STREAM_TOTAL_BYTES) return false;
    if (previousStreamSeq !== undefined && (segmentStartSeq as number) !== previousStreamSeq + 1)
      return false;
    previousStreamSeq = streamSeq as number;
  }
  return previousStreamSeq === undefined || previousStreamSeq === (root["streamSeq"] as number);
}

/** 只接纳 reducer 投影所需的 snapshot 闭集；累计标记只用于已验证页的本地合并。 */
export function timelineSnapshotFromUnknown(
  value: unknown,
  accumulatedHistory = false,
): TimelineSnapshot | undefined {
  if (timelineSnapshotValidationReason(value, accumulatedHistory) !== undefined) return undefined;
  return value as TimelineSnapshot;
}

/** Domain seam 重复容量与 identity 不变量，阻止测试或本地调用绕开 transport Zod。 */
function isInputQueue(value: unknown): value is InputQueue {
  if (typeof value !== "object" || value === null) return false;
  const queue = value as Record<string, unknown>;
  if (
    typeof queue["turnId"] !== "string" ||
    !Number.isSafeInteger(queue["revision"]) ||
    (queue["revision"] as number) < 0 ||
    typeof queue["accepting"] !== "boolean" ||
    !Array.isArray(queue["items"]) ||
    queue["items"].length > 8 ||
    !queue["items"].every(isQueuedInput)
  )
    return false;
  const items = queue["items"] as QueuedInput[];
  return (
    items.every((item) => item.turnId === queue["turnId"]) &&
    new Set(items.map((item) => item.inputId)).size === items.length &&
    items.reduce(
      (total, item) => total + new TextEncoder().encode(JSON.stringify(item.content)).byteLength,
      0,
    ) <=
      512 * 1024
  );
}

/** 条目 revision 与时间只作边界校验，不在 Renderer 推断服务端优先序。 */
function isQueuedInput(value: unknown): value is QueuedInput {
  if (typeof value !== "object" || value === null) return false;
  const input = value as Record<string, unknown>;
  return (
    typeof input["inputId"] === "string" &&
    typeof input["turnId"] === "string" &&
    Array.isArray(input["content"]) &&
    input["content"].length > 0 &&
    input["content"].length <= 64 &&
    input["content"].every(isUserContentBlock) &&
    isAttachmentSummaryList(input["attachments"], input["content"]) &&
    (input["kind"] === "follow_up" || input["kind"] === "steering") &&
    (input["status"] === "pending" || input["status"] === "needs_attention") &&
    ((input["status"] === "pending" && input["issue"] === null) ||
      (input["status"] === "needs_attention" && isQueuedInputIssue(input["issue"]))) &&
    Number.isSafeInteger(input["inputRevision"]) &&
    (input["inputRevision"] as number) >= 0 &&
    typeof input["createdAt"] === "string"
  );
}

/** 附件摘要必须与 content block 一一同序，防止展示元数据与实际绑定对象发生错配。 */
function isAttachmentSummaryList(value: unknown, content: unknown): value is AttachmentSummary[] {
  if (!Array.isArray(value) || !Array.isArray(content) || value.length > 32) return false;
  const attachmentIds = content
    .filter(
      (block): block is { type: "attachment"; attachmentId: string } =>
        typeof block === "object" && block !== null && block["type"] === "attachment",
    )
    .map((block) => block.attachmentId);
  return (
    value.length === attachmentIds.length &&
    value.every((summary, index) => {
      if (typeof summary !== "object" || summary === null) return false;
      const item = summary as Record<string, unknown>;
      return (
        item["attachmentId"] === attachmentIds[index] &&
        typeof item["displayName"] === "string" &&
        item["displayName"].length > 0 &&
        Number.isSafeInteger(item["sizeBytes"]) &&
        (item["sizeBytes"] as number) >= 0 &&
        ["text", "image", "pdf", "binary"].includes(String(item["mediaKind"])) &&
        typeof item["mediaType"] === "string" &&
        item["mediaType"].length > 0
      );
    })
  );
}

/** 队列 issue 是可恢复的持久事实，WebView 只接纳稳定码、用户文案和 retryability。 */
function isQueuedInputIssue(value: unknown): boolean {
  if (typeof value !== "object" || value === null) return false;
  const issue = value as Record<string, unknown>;
  return (
    typeof issue["errorCode"] === "string" &&
    /^[A-Z][A-Z0-9_]{1,63}$/u.test(issue["errorCode"]) &&
    typeof issue["message"] === "string" &&
    issue["message"].length > 0 &&
    issue["message"].length <= 2_048 &&
    typeof issue["retryable"] === "boolean"
  );
}

/** 结构化用户内容只接纳四种当前合同 block，拒绝 unknown field 由 transport Zod 负责。 */
function isUserContentBlock(value: unknown): value is UserContentBlock {
  if (typeof value !== "object" || value === null) return false;
  const block = value as Record<string, unknown>;
  switch (block["type"]) {
    case "text":
      return typeof block["text"] === "string" && block["text"].length > 0;
    case "attachment":
      return typeof block["attachmentId"] === "string";
    case "workspace_reference":
      return (
        typeof block["workspaceId"] === "string" &&
        typeof block["relativePath"] === "string" &&
        (block["kind"] === "file" || block["kind"] === "directory")
      );
    case "skill_reference":
      return typeof block["skillId"] === "string";
    default:
      return false;
  }
}

/** Usage 按唯一请求和必填画像严格校验；未知计量不能伪造 token，快照估算必须匹配同一窗口。 */
function isTimelineContextUsage(value: unknown): value is TimelineContextUsage {
  if (typeof value !== "object" || value === null) return false;
  const usage = value as Record<string, unknown>;
  const inputTokens = usage["inputTokens"];
  const outputTokens = usage["outputTokens"];
  const totalTokens = usage["totalTokens"];
  const contextEstimate = usage["contextEstimate"];
  const commonValid =
    typeof usage["modelRequestId"] === "string" &&
    usage["modelRequestId"].startsWith("modelreq_") &&
    Number.isSafeInteger(usage["modelRequestOrdinal"]) &&
    (usage["modelRequestOrdinal"] as number) >= 1 &&
    Number.isSafeInteger(usage["modelRound"]) &&
    (usage["modelRound"] as number) >= 1 &&
    (usage["purpose"] === "assistant" || usage["purpose"] === "summary") &&
    typeof usage["measuredAt"] === "string" &&
    isTimelineContextEstimate(contextEstimate, usage["profile"]);
  if (!commonValid) return false;
  if (!isProviderRequestProfile(usage["profile"])) return false;
  if (contextEstimate != null && usage["certainty"] !== "unknown") return false;
  if (usage["certainty"] === "unknown") {
    return inputTokens === null && outputTokens === null && totalTokens === null;
  }
  return (
    usage["certainty"] === "known" &&
    Number.isSafeInteger(inputTokens) &&
    (inputTokens as number) >= 0 &&
    Number.isSafeInteger(outputTokens) &&
    (outputTokens as number) >= 0 &&
    Number.isSafeInteger(totalTokens) &&
    (totalTokens as number) >= (inputTokens as number) + (outputTokens as number)
  );
}

/** 本地派发或检查点估算必须注明来源和请求冻结的上下文窗口。 */
function isTimelineContextEstimate(value: unknown, profile: unknown): boolean {
  if (value === undefined || value === null) return true;
  if (typeof value !== "object") return false;
  const estimate = value as Record<string, unknown>;
  const profileWindow =
    typeof profile === "object" && profile !== null
      ? (profile as Record<string, unknown>)["contextWindowTokens"]
      : undefined;
  return (
    estimate["certainty"] === "estimated" &&
    estimate["source"] === "local" &&
    Number.isSafeInteger(estimate["usedTokens"]) &&
    (estimate["usedTokens"] as number) >= 0 &&
    Number.isSafeInteger(estimate["limitTokens"]) &&
    estimate["limitTokens"] === profileWindow &&
    typeof estimate["measuredAt"] === "string"
  );
}

/** Thread 快照必须带 Turn 身份与估算 nullable 槽位，重启后仍能恢复一致的上下文投影。 */
function isThreadContextUsage(value: unknown): value is TimelineThreadContextUsage {
  const usage = value as unknown as Record<string, unknown>;
  return (
    isTimelineContextUsage(value) &&
    Object.prototype.hasOwnProperty.call(usage, "contextEstimate") &&
    typeof usage["turnId"] === "string" &&
    (usage["turnId"] as string).startsWith("turn_")
  );
}

/**
 * @author kongweiguang
 * 持久快照 Turn 状态须覆盖当前 JA-RPC 合法闭集；身份、nullable 槽位与 changeSet 仍由 domain 严格复核。
 */
function isTimelineSnapshotTurn(value: unknown): boolean {
  if (typeof value !== "object" || value === null) return false;
  const turn = value as Record<string, unknown>;
  return (
    typeof turn["turnId"] === "string" &&
    (turn["origin"] === "native" || turn["origin"] === "inherited") &&
    [
      "queued",
      "running",
      "waiting_input",
      "waiting_approval",
      "suspended",
      "completed",
      "failed",
      "cancelled",
    ].includes(String(turn["status"])) &&
    typeof turn["requestedAt"] === "string" &&
    typeof turn["updatedAt"] === "string" &&
    (turn["completedAt"] === null || typeof turn["completedAt"] === "string") &&
    (turn["errorCode"] === null || typeof turn["errorCode"] === "string") &&
    (turn["changeSet"] === null || isTurnChangeSet(turn["changeSet"])) &&
    !("runtime" in turn)
  );
}

/** Task activity 快照重复最小闭集与血缘约束，阻止 domain 测试绕过 transport 严格 Schema。 */
function isTimelineTaskActivityEntry(value: unknown, rootThreadId: string): boolean {
  if (typeof value !== "object" || value === null) return false;
  const entry = value as Record<string, unknown>;
  if (
    typeof entry["activity"] !== "object" ||
    entry["activity"] === null ||
    typeof entry["task"] !== "object" ||
    entry["task"] === null
  )
    return false;
  const activity = entry["activity"] as Record<string, unknown>;
  const task = entry["task"] as Record<string, unknown>;
  // Conversation Timeline 只展示当前会话直接委派的子任务；独立侧聊不能回流到来源会话。
  const validTaskKind = task["taskKind"] === "subagent";
  const validLifecycle = task["taskKind"] === "subagent" && task["lifecycle"] === "attached";
  const validState = [
    "idle",
    "queued",
    "running",
    "waiting_approval",
    "suspended",
    "completed",
    "failed",
    "cancelled",
  ].includes(String(task["state"]));
  const validActivityKind = [
    "created",
    "dispatched",
    "message_sent",
    "follow_up_queued",
    "progress",
    "waiting_approval",
    "resumed",
    "completed",
    "failed",
    "cancelled",
    "suspended",
  ].includes(String(activity["kind"]));
  const summary = activity["summary"] as Record<string, unknown> | undefined;
  return (
    typeof activity["activityId"] === "string" &&
    typeof activity["rootThreadId"] === "string" &&
    typeof activity["taskThreadId"] === "string" &&
    typeof activity["actorThreadId"] === "string" &&
    (activity["causalTurnId"] === null || typeof activity["causalTurnId"] === "string") &&
    Number.isSafeInteger(activity["activitySequence"]) &&
    (activity["activitySequence"] as number) >= 1 &&
    validActivityKind &&
    summary !== undefined &&
    typeof summary["text"] === "string" &&
    typeof activity["createdAt"] === "string" &&
    typeof task["taskThreadId"] === "string" &&
    task["taskThreadId"] === activity["taskThreadId"] &&
    typeof task["parentThreadId"] === "string" &&
    task["parentThreadId"] === rootThreadId &&
    task["rootThreadId"] === activity["rootThreadId"] &&
    (task["originTurnId"] === null || typeof task["originTurnId"] === "string") &&
    typeof task["taskName"] === "string" &&
    Number.isSafeInteger(task["depth"]) &&
    (task["depth"] as number) >= 1 &&
    (task["depth"] as number) <= 4 &&
    validTaskKind &&
    validLifecycle &&
    (task["taskKind"] !== "subagent" || task["originTurnId"] !== null) &&
    validState &&
    Number.isSafeInteger(task["revision"]) &&
    (task["revision"] as number) >= 0 &&
    Number.isSafeInteger(task["latestActivitySequence"]) &&
    (task["latestActivitySequence"] as number) >= (activity["activitySequence"] as number) &&
    ["unreadCount", "descendantCount", "runningDescendantCount", "needsAttentionCount"].every(
      (field) => Number.isSafeInteger(task[field]) && (task[field] as number) >= 0,
    ) &&
    (task["latestSafeSummary"] === null || typeof task["latestSafeSummary"] === "string") &&
    (task["startedAt"] === null || typeof task["startedAt"] === "string") &&
    (task["completedAt"] === null || typeof task["completedAt"] === "string") &&
    typeof task["updatedAt"] === "string"
  );
}

/** Snapshot 的修改事实必须具有闭集状态；详细数值仍由 transport Zod 负责上限。 */
function isTurnChangeSet(value: unknown): boolean {
  if (typeof value !== "object" || value === null) return false;
  const changeSet = value as Record<string, unknown>;
  if (changeSet["state"] !== "complete" && changeSet["state"] !== "partial") return false;
  if (!Array.isArray(changeSet["files"]) || typeof changeSet["stats"] !== "object") return false;
  if (!Array.isArray(changeSet["incompleteReasons"])) return false;
  const reasons = changeSet["incompleteReasons"] as unknown[];
  const validReasons = reasons.every((reason) =>
    [
      "unknown_mutator",
      "mutation_chain_broken",
      "outside_workspace",
      "limit_exceeded",
      "capture_failed",
      "commit_unconfirmed",
      "recovery_boundary",
    ].includes(String(reason)),
  );
  return (
    validReasons &&
    ((changeSet["state"] === "complete" && reasons.length === 0) ||
      (changeSet["state"] === "partial" && reasons.length > 0))
  );
}

/** 结构化问答展示必须保持三题上限，且只允许题目、答案文案和跳过事实。 */
function isToolInteractionAnswers(value: unknown): boolean {
  return (
    value === undefined ||
    (Array.isArray(value) &&
      value.length <= 3 &&
      value.every(
        (answer) =>
          typeof answer === "object" &&
          answer !== null &&
          typeof (answer as Record<string, unknown>)["question"] === "string" &&
          ((answer as Record<string, unknown>)["question"] as string).trim() !== "" &&
          Array.isArray((answer as Record<string, unknown>)["answers"]) &&
          ((answer as Record<string, unknown>)["answers"] as unknown[]).length <= 64 &&
          ((answer as Record<string, unknown>)["answers"] as unknown[]).every(
            (label) => typeof label === "string" && label.trim() !== "",
          ) &&
          typeof (answer as Record<string, unknown>)["skipped"] === "boolean" &&
          ((answer as Record<string, unknown>)["skipped"] !== true ||
            ((answer as Record<string, unknown>)["answers"] as unknown[]).length === 0),
      ))
  );
}

/** ToolPresentation 是唯一可进入 domain 的 Tool 内容，禁止 raw value 重新出现。 */
function isToolPresentation(value: unknown): value is ToolPresentation {
  if (typeof value !== "object" || value === null) return false;
  const presentation = value as Record<string, unknown>;
  const recovery = presentation["recovery"];
  const validRecovery =
    recovery === undefined ||
    (typeof recovery === "object" &&
      recovery !== null &&
      Object.keys(recovery).length === 1 &&
      Number.isSafeInteger((recovery as Record<string, unknown>)["revision"]) &&
      ((recovery as Record<string, unknown>)["revision"] as number) >= 1);
  return (
    ["read", "edit", "write", "shell", "mcp"].includes(String(presentation["kind"])) &&
    typeof presentation["title"] === "string" &&
    ["pending", "running", "waiting_approval", "success", "error", "cancelled"].includes(
      String(presentation["status"]),
    ) &&
    Array.isArray(presentation["relativePaths"]) &&
    (presentation["summary"] === undefined || typeof presentation["summary"] === "string") &&
    isToolInteractionAnswers(presentation["interactionAnswers"]) &&
    typeof presentation["truncated"] === "boolean" &&
    validRecovery
  );
}

/** 请求画像必须完整且有界，避免 renderer 为缺失字段补当前偏好。 */
function isProviderRequestProfile(value: unknown): value is ProviderRequestProfile {
  if (typeof value !== "object" || value === null) return false;
  const runtime = value as Record<string, unknown>;
  return (
    typeof runtime["providerId"] === "string" &&
    typeof runtime["modelId"] === "string" &&
    (runtime["api"] === "openai_responses" ||
      runtime["api"] === "anthropic_messages" ||
      runtime["api"] === "openai_chat_completions") &&
    typeof runtime["upstreamModel"] === "string" &&
    (runtime["requestedReasoning"] === null ||
      ["off", "minimal", "low", "medium", "high", "xhigh", "max"].includes(
        String(runtime["requestedReasoning"]),
      )) &&
    (runtime["effectiveReasoning"] === null ||
      ["off", "minimal", "low", "medium", "high", "xhigh", "max"].includes(
        String(runtime["effectiveReasoning"]),
      )) &&
    (runtime["accessMode"] === "approval_required" || runtime["accessMode"] === "full_access") &&
    typeof runtime["configGeneration"] === "string" &&
    typeof runtime["promptRevision"] === "string" &&
    typeof runtime["toolCatalogRevision"] === "string" &&
    Number.isSafeInteger(runtime["contextWindowTokens"]) &&
    (runtime["contextWindowTokens"] as number) > 0 &&
    Number.isSafeInteger(runtime["maxOutputTokens"]) &&
    (runtime["maxOutputTokens"] as number) > 0
  );
}

/** 按判别字段校验 snapshot item 的最小必填数据，未知 kind 必须拒绝。 */
function isTimelineSnapshotItem(value: unknown): value is TimelineSnapshotItem {
  if (typeof value !== "object" || value === null) return false;
  const item = value as Record<string, unknown>;
  if (
    typeof item["itemId"] !== "string" ||
    typeof item["createdAt"] !== "string" ||
    typeof item["turnId"] !== "string" ||
    (item["origin"] !== "native" && item["origin"] !== "inherited")
  )
    return false;
  switch (item["kind"]) {
    case "user_input": {
      const preview = Array.isArray(item["content"])
        ? item["content"]
            .filter(
              (block): block is Record<string, unknown> =>
                typeof block === "object" && block !== null && "type" in block,
            )
            .filter((block) => block["type"] === "text")
            .map((block) => block["text"])
            .filter((text): text is string => typeof text === "string")
            .join("")
        : undefined;
      return (
        Array.isArray(item["content"]) &&
        item["content"].every(isUserContentBlock) &&
        isAttachmentSummaryList(item["attachments"], item["content"]) &&
        isPublicTextPreview(preview, item["truncated"], item["totalCharacters"])
      );
    }
    case "thread_message":
      return isThreadMessageItem(item);
    case "final_answer":
      return (
        typeof item["text"] === "string" &&
        isPublicTextPreview(item["text"], item["truncated"], item["totalCharacters"])
      );
    case "assistant_progress":
    case "reasoning_summary":
      return (
        typeof item["text"] === "string" &&
        Number.isSafeInteger(item["modelRound"]) &&
        isPublicTextPreview(item["text"], item["truncated"], item["totalCharacters"])
      );
    case "tool_call":
      return (
        typeof item["callId"] === "string" &&
        typeof item["toolName"] === "string" &&
        Number.isSafeInteger(item["ordinal"]) &&
        isToolPresentation(item["presentation"])
      );
    case "approval":
      return (
        typeof item["approvalId"] === "string" &&
        typeof item["callId"] === "string" &&
        typeof item["toolName"] === "string" &&
        typeof item["reason"] === "string" &&
        typeof item["expiresAt"] === "string" &&
        (item["decision"] === null || item["decision"] === "approve" || item["decision"] === "deny")
      );
    default:
      return false;
  }
}

/** 对消费事件的无来源元数据 User item 投影原生来源复用 Snapshot payload 校验，并拒绝 wire 多字段。 @author kongweiguang */
function isLiveUserInputItem(
  value: unknown,
): value is Extract<TimelineLiveItem, { kind: "user_input" }> {
  if (
    !hasExactObjectKeys(value, [
      "itemId",
      "createdAt",
      "turnId",
      "kind",
      "content",
      "attachments",
      "totalCharacters",
      "truncated",
    ])
  )
    return false;
  const snapshotProjection = { ...value, origin: "native" };
  return isTimelineSnapshotItem(snapshotProjection) && snapshotProjection.kind === "user_input";
}

/** live 消息不携带 Snapshot origin；临时投影为 native 后复用严格的来源、正文与身份校验。 @author kongweiguang */
function isLiveThreadMessageItem(
  value: unknown,
  expectedTurnId?: unknown,
): value is Extract<TimelineLiveItem, { kind: "thread_message" }> {
  if (
    !hasExactObjectKeys(value, [
      "itemId",
      "createdAt",
      "turnId",
      "kind",
      "sourceThreadId",
      "sourceTitle",
      "content",
      "totalCharacters",
      "truncated",
    ])
  )
    return false;
  return isThreadMessageItem({ ...value, origin: "native" }, expectedTurnId);
}

/** Wire item 必须和当前方法的字段集合完全一致，避免未定义字段借由 domain seam 穿入 Timeline。 @author kongweiguang */
function hasExactObjectKeys(
  value: unknown,
  expectedKeys: readonly string[],
): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const keys = Object.keys(value).sort();
  const sortedExpectedKeys = [...expectedKeys].sort();
  return (
    keys.length === sortedExpectedKeys.length &&
    keys.every((key, index) => key === sortedExpectedKeys[index])
  );
}

/** 预览必须是正文的完整 code-point 前缀，禁止把有限预览误当全文交付。 */
function isPublicTextPreview(
  preview: string | undefined,
  truncated: unknown,
  totalCharacters: unknown,
): boolean {
  if (
    preview === undefined ||
    typeof truncated !== "boolean" ||
    !Number.isSafeInteger(totalCharacters) ||
    (totalCharacters as number) < 0
  )
    return false;
  const previewCharacters = Array.from(preview).length;
  return (
    previewCharacters <= (totalCharacters as number) &&
    (truncated
      ? previewCharacters < (totalCharacters as number)
      : previewCharacters === totalCharacters)
  );
}

/** Snapshot 跨会话消息保留原生/继承归属和冻结来源，精确字段集防止消息伪装成用户或系统输入。 @author kongweiguang */
function isThreadMessageItem(value: unknown, expectedTurnId?: unknown): boolean {
  if (
    !hasExactObjectKeys(value, [
      "content",
      "createdAt",
      "itemId",
      "kind",
      "origin",
      "sourceThreadId",
      "sourceTitle",
      "totalCharacters",
      "truncated",
      "turnId",
    ])
  )
    return false;
  const item = value;
  return (
    item["kind"] === "thread_message" &&
    (item["origin"] === "native" || item["origin"] === "inherited") &&
    isThreadMessageIdentifier(item["itemId"], "item_", 101) &&
    typeof item["createdAt"] === "string" &&
    isThreadMessageIdentifier(item["turnId"], "turn_", 101) &&
    (expectedTurnId === undefined || item["turnId"] === expectedTurnId) &&
    isThreadMessageIdentifier(item["sourceThreadId"], "thr_", 100) &&
    typeof item["sourceTitle"] === "string" &&
    item["sourceTitle"].length > 0 &&
    item["sourceTitle"].length <= 512 &&
    item["sourceTitle"].trim().length > 0 &&
    !item["sourceTitle"].includes("\u0000") &&
    !item["sourceTitle"].includes("\r") &&
    !item["sourceTitle"].includes("\n") &&
    typeof item["content"] === "string" &&
    item["content"].length <= 1_048_576 &&
    !item["content"].includes("\u0000") &&
    isPublicTextPreview(item["content"], item["truncated"], item["totalCharacters"])
  );
}

/** 复用 JA-RPC opaque identity grammar，避免 domain seam 接受 transport 已拒绝的路径或控制字符。 */
function isThreadMessageIdentifier(
  value: unknown,
  prefix: string,
  maximum: number,
): value is string {
  return (
    typeof value === "string" &&
    value.length <= maximum &&
    new RegExp(`^${prefix}[A-Za-z0-9][A-Za-z0-9._-]{0,95}$`).test(value)
  );
}
