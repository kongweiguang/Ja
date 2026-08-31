// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type {
  ApprovalDecision,
  TimelineContextUsage,
  TimelineTurnRuntimeSnapshot,
  TimelineTurnState,
  ToolPresentation,
  TurnChangeSet,
} from "./timelineTypes";

interface SnapshotItemBase {
  itemId: string;
  createdAt: string;
  turnId: string;
}

/** 与平坦 items 并列的权威 Turn 元数据，失败码不包含服务端错误正文。 */
interface TimelineSnapshotTurn {
  turnId: string;
  status: TimelineTurnState;
  runtime: TimelineTurnRuntimeSnapshot | null;
  requestedAt: string;
  updatedAt: string;
  completedAt: string | null;
  errorCode: string | null;
  changeSet: TurnChangeSet | null;
}

/** Thread snapshot 的持久 Item 闭集与 JA-RPC v2 保持一致，但不依赖 transport Schema。 */
export type TimelineSnapshotItem =
  | (SnapshotItemBase & { kind: "user_input"; text: string })
  | (SnapshotItemBase & { kind: "assistant_progress"; text: string; modelRound: number })
  | (SnapshotItemBase & { kind: "reasoning_summary"; text: string; modelRound: number })
  | (SnapshotItemBase & { kind: "final_answer"; text: string })
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
    })
  | (SnapshotItemBase & {
      kind: "attachment";
      attachmentId: string;
      displayName: string;
      sizeBytes: number;
      mediaKind: "text" | "image" | "pdf" | "binary";
      mediaType: string;
      state: "draft" | "bound" | "discarded" | "expired";
    });

export interface TimelineSnapshot {
  threadId: string;
  revision: number;
  turns: TimelineSnapshotTurn[];
  items: TimelineSnapshotItem[];
  contextUsage: TimelineContextUsage | null;
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
  strategyVersion: "ja-context-v3";
}

interface UsageProjection {
  inputTokens: number;
  outputTokens: number;
  totalTokens: number;
}

type EventEnvelope<M extends string, P> = { jsonrpc: "2.0"; method: M; params: P };

/**
 * Reducer 只依赖会影响状态转换的事件字段；严格 wire 校验仍由 api adapter 执行，domain
 * 在此保留封闭判别联合以获得穷尽分支，而不是引用 Tauri 或 transport 实现。
 */
export type TimelineEvent =
  | EventEnvelope<
      "turn/state-changed",
      SemanticBase & { from: TimelineTurnState; to: TimelineTurnState }
    >
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
  | EventEnvelope<
      "assistant/text-delta" | "assistant/reasoning-summary-delta",
      SemanticBase & { streamSeq: number; text: string }
    >
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
      "approval/requested",
      SemanticBase & {
        approvalId: string;
        callId: string;
        toolName: string;
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
          | "TOKEN_COUNT_UNAVAILABLE"
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
        finalMessage?: { messageId: string; text: string };
        usage?: UsageProjection & { modelRound: number };
        errorCode?: string;
        errorMessage?: string;
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
  "turn/state-changed",
  "assistant/model-step-committed",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/batch-committed",
  "approval/requested",
  "approval/resolved",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "turn/terminal",
  "runtime/status-changed",
  "thread/metadata-changed",
  "configuration/changed",
]);

const WORKSPACE_SCOPED_METHODS = new Set<TimelineEvent["method"]>([
  "turn/state-changed",
  "assistant/model-step-committed",
  "assistant/text-delta",
  "assistant/reasoning-summary-delta",
  "tool/batch-committed",
  "approval/requested",
  "approval/resolved",
  "context/compaction-started",
  "context/compacted",
  "context/compaction-failed",
  "turn/terminal",
  "thread/metadata-changed",
]);

/**
 * 为 domain 直接调用提供 fail-closed 的最小形状 admission；production store 在进入此处前
 * 已由 canonical JA-RPC Schema 完整校验，异常字段会在 reducer 的 try/catch 中变为 invalid。
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
      !params["toolCalls"].every((call) => {
        if (typeof call !== "object" || call === null) return false;
        return isToolPresentation((call as Record<string, unknown>)["presentation"]);
      })
    )
      return undefined;
  }
  if (root["method"] === "tool/batch-committed") {
    if (!Array.isArray(params["results"])) return undefined;
    if (
      !params["results"].every((result) => {
        if (typeof result !== "object" || result === null) return false;
        const candidate = result as Record<string, unknown>;
        return (
          ["succeeded", "failed", "cancelled"].includes(String(candidate["outcome"])) &&
          isToolPresentation(candidate["presentation"])
        );
      })
    )
      return undefined;
  }
  return value as TimelineEvent;
}

/** 只接纳 reducer 投影所需的 snapshot 闭集，避免 domain 依赖 Zod 或 transport module。 */
export function timelineSnapshotFromUnknown(value: unknown): TimelineSnapshot | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const root = value as Record<string, unknown>;
  if (
    typeof root["threadId"] !== "string" ||
    !Number.isSafeInteger(root["revision"]) ||
    (root["revision"] as number) < 0
  )
    return undefined;
  if (!Array.isArray(root["turns"]) || root["turns"].length > 200) return undefined;
  if (!root["turns"].every(isTimelineSnapshotTurn)) return undefined;
  if (!Array.isArray(root["items"]) || root["items"].length > 200) return undefined;
  if (!("contextUsage" in root)) return undefined;
  if (root["contextUsage"] !== null && !isTimelineContextUsage(root["contextUsage"]))
    return undefined;
  if (
    root["contextUsage"] !== null &&
    !root["turns"].some(
      (turn) =>
        (turn as TimelineSnapshotTurn).turnId ===
        (root["contextUsage"] as TimelineContextUsage).turnId,
    )
  )
    return undefined;
  if (root["nextCursor"] !== null && typeof root["nextCursor"] !== "string") return undefined;
  if (!root["items"].every(isTimelineSnapshotItem)) return undefined;
  return value as TimelineSnapshot;
}

/** Usage 必须保持 JavaScript 精确整数并满足总量关系；domain seam 不接受悬空或估算字段。 */
function isTimelineContextUsage(value: unknown): value is TimelineContextUsage {
  if (typeof value !== "object" || value === null) return false;
  const usage = value as Record<string, unknown>;
  const inputTokens = usage["inputTokens"];
  const outputTokens = usage["outputTokens"];
  const totalTokens = usage["totalTokens"];
  return (
    typeof usage["turnId"] === "string" &&
    Number.isSafeInteger(usage["modelRound"]) &&
    (usage["modelRound"] as number) >= 1 &&
    (usage["modelRound"] as number) <= 128 &&
    Number.isSafeInteger(inputTokens) &&
    (inputTokens as number) >= 0 &&
    Number.isSafeInteger(outputTokens) &&
    (outputTokens as number) >= 0 &&
    Number.isSafeInteger(totalTokens) &&
    (totalTokens as number) >= (inputTokens as number) + (outputTokens as number) &&
    typeof usage["measuredAt"] === "string"
  );
}

/** 对 domain reducer 再做一次最小闭集校验，拒绝 transport seam 之外注入的伪造 Turn。 */
function isTimelineSnapshotTurn(value: unknown): boolean {
  if (typeof value !== "object" || value === null) return false;
  const turn = value as Record<string, unknown>;
  return (
    typeof turn["turnId"] === "string" &&
    ["queued", "running", "waiting_approval", "completed", "failed", "cancelled"].includes(
      String(turn["status"]),
    ) &&
    typeof turn["requestedAt"] === "string" &&
    typeof turn["updatedAt"] === "string" &&
    (turn["completedAt"] === null || typeof turn["completedAt"] === "string") &&
    (turn["errorCode"] === null || typeof turn["errorCode"] === "string") &&
    (turn["changeSet"] === null || isTurnChangeSet(turn["changeSet"])) &&
    (turn["runtime"] === null || isTimelineRuntimeSnapshot(turn["runtime"]))
  );
}

/** Snapshot 的修改事实必须具有闭集状态；详细数值仍由 transport Zod 负责上限。 */
function isTurnChangeSet(value: unknown): boolean {
  if (typeof value !== "object" || value === null) return false;
  const changeSet = value as Record<string, unknown>;
  if (changeSet["state"] !== "available" && changeSet["state"] !== "unavailable") return false;
  if (!Array.isArray(changeSet["files"]) || typeof changeSet["stats"] !== "object") return false;
  return (
    changeSet["state"] === "available" ||
    ["concurrent_turn", "not_git", "capture_failed", "diff_too_large"].includes(
      String(changeSet["reason"]),
    )
  );
}

/** ToolPresentation 是唯一可进入 domain 的 Tool 内容，禁止 raw value 重新出现。 */
function isToolPresentation(value: unknown): value is ToolPresentation {
  if (typeof value !== "object" || value === null) return false;
  const presentation = value as Record<string, unknown>;
  return (
    ["read", "edit", "write", "shell", "mcp"].includes(String(presentation["kind"])) &&
    typeof presentation["title"] === "string" &&
    ["pending", "running", "waiting_approval", "success", "error", "cancelled"].includes(
      String(presentation["status"]),
    ) &&
    Array.isArray(presentation["relativePaths"]) &&
    typeof presentation["truncated"] === "boolean"
  );
}

/** 运行快照只接受两类锁定 Provider API，避免 UI 为旧 API 建立隐式回退。 */
function isTimelineRuntimeSnapshot(value: unknown): boolean {
  if (typeof value !== "object" || value === null) return false;
  const runtime = value as Record<string, unknown>;
  return (
    typeof runtime["providerId"] === "string" &&
    typeof runtime["modelId"] === "string" &&
    (runtime["provider"] === "openai" || runtime["provider"] === "anthropic") &&
    (runtime["api"] === "openai_responses" || runtime["api"] === "anthropic_messages") &&
    typeof runtime["upstreamModel"] === "string" &&
    (runtime["reasoningLevel"] === null ||
      ["off", "minimal", "low", "medium", "high", "xhigh", "max"].includes(
        String(runtime["reasoningLevel"]),
      )) &&
    (runtime["accessMode"] === "approval_required" || runtime["accessMode"] === "full_access") &&
    typeof runtime["configGeneration"] === "string"
  );
}

/** 按判别字段校验 snapshot item 的最小必填数据，未知 kind 必须拒绝。 */
function isTimelineSnapshotItem(value: unknown): value is TimelineSnapshotItem {
  if (typeof value !== "object" || value === null) return false;
  const item = value as Record<string, unknown>;
  if (
    typeof item["itemId"] !== "string" ||
    typeof item["createdAt"] !== "string" ||
    typeof item["turnId"] !== "string"
  )
    return false;
  switch (item["kind"]) {
    case "user_input":
    case "final_answer":
      return typeof item["text"] === "string";
    case "assistant_progress":
    case "reasoning_summary":
      return typeof item["text"] === "string" && Number.isSafeInteger(item["modelRound"]);
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
    case "attachment":
      return (
        typeof item["attachmentId"] === "string" &&
        typeof item["displayName"] === "string" &&
        Number.isSafeInteger(item["sizeBytes"]) &&
        (item["sizeBytes"] as number) >= 0 &&
        (item["sizeBytes"] as number) <= 100 * 1024 * 1024 &&
        (item["mediaKind"] === "text" ||
          item["mediaKind"] === "image" ||
          item["mediaKind"] === "pdf" ||
          item["mediaKind"] === "binary") &&
        typeof item["mediaType"] === "string" &&
        (item["state"] === "draft" ||
          item["state"] === "bound" ||
          item["state"] === "discarded" ||
          item["state"] === "expired")
      );
    default:
      return false;
  }
}
