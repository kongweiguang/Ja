// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 仅 UI 使用的投影明确在 JA-RPC Wire Type 之外定义。Wire Contract 包含持久 Item 事实与六态
 * Turn Event；展示层额外需要 Turn 关联、瞬态状态与有界显示元数据，这些绝不能回传 Sidecar。
 */

export type TimelineItemKind =
  | "user_message"
  | "agent_message"
  | "commentary"
  | "tool_call"
  | "command"
  | "approval";

export type TimelineItemStatus = "started" | "in_progress" | "completed" | "failed" | "cancelled";

export interface ToolPresentation {
  kind: "read" | "edit" | "write" | "shell" | "mcp";
  title: string;
  status: "pending" | "running" | "waiting_approval" | "success" | "error" | "cancelled";
  inputPreview?: string;
  outputPreview?: string;
  relativePaths: string[];
  command?: string;
  relativeCwd?: string;
  stdout?: string;
  stderr?: string;
  exitCode?: number;
  durationMs?: number;
  truncated: boolean;
  artifactId?: string;
}

export interface TurnChangeFile {
  path: string;
  status: "added" | "modified" | "deleted";
  additions: number;
  deletions: number;
  binary: boolean;
  truncated: boolean;
}

export interface TurnChangeStats {
  files: number;
  additions: number;
  deletions: number;
  binaryFiles: number;
  truncated: boolean;
}

export type TurnChangeIncompleteReason =
  | "unknown_mutator"
  | "mutation_chain_broken"
  | "outside_workspace"
  | "limit_exceeded"
  | "capture_failed"
  | "commit_unconfirmed"
  | "recovery_boundary";

export interface TurnChangeSet {
  state: "complete" | "partial";
  incompleteReasons: TurnChangeIncompleteReason[];
  files: TurnChangeFile[];
  stats: TurnChangeStats;
  artifactId?: string;
}

export interface ItemMetadata {
  callId?: string;
  toolName?: string;
  toolKind?: string;
  toolOutcome?: "succeeded" | "failed" | "cancelled";
  requiresUserAction?: boolean;
  phase?: string;
  compactionId?: string;
  checkpointId?: string;
  inputTokensBefore?: number;
  inputTokensAfter?: number;
  errorCode?: string;
  throughMessageSequence?: number;
  estimatedTokens?: number;
  inputBytes?: number;
  outputBytes?: number;
  attachmentId?: string;
  sizeBytes?: number;
  mediaKind?: "text" | "image" | "pdf" | "binary";
  mediaType?: string;
  attachmentState?: "draft" | "bound" | "discarded" | "expired";
  usageInputTokens?: number;
  usageOutputTokens?: number;
  usageTotalTokens?: number;
  changedFiles?: number;
  additions?: number;
  deletions?: number;
  relativePaths?: string[];
  truncated?: boolean;
  modelRound?: number;
  presentation?: ToolPresentation;
  /** 标识由 Ja 运行时生成并持久化的失败收口正文，避免与 Provider 半截输出混淆。 */
  failureReply?: boolean;
}

/** 由已提交 Protocol Event 组装、且仅由 Renderer 持有的 Item。 */
export interface TimelineItemAdapter {
  itemId: string;
  threadId: string;
  turnId: string;
  kind: TimelineItemKind;
  status: TimelineItemStatus;
  text?: string;
  /** 用户消息的结构化引用与正文并列展示，永远不包含预读文件或 Skill 正文。 */
  contextReferences?: import("./userContent").ConversationContextReference[];
  /** 附件摘要与所属用户消息共同投影，避免按 Turn 聚合后失去精确消息归属。 */
  attachments?: import("./timelineContracts").AttachmentSummary[];
  title?: string;
  metadata?: ItemMetadata;
  final?: boolean;
  durationMs?: number;
  summary?: string;
  changedFiles?: number;
  additions?: number;
  deletions?: number;
  createdAt?: string;
}

export type WorkStepAdapter = TimelineItemAdapter;

/** 七种公开 Turn 状态必须精确映射冻结的 JA-RPC 合同。 */
export type TimelineTurnState =
  | "queued"
  | "running"
  | "waiting_approval"
  | "suspended"
  | "completed"
  | "failed"
  | "cancelled";

/** 每次 Provider 请求的实际非敏感执行画像；下一安全点可与同一 Turn 的上一请求不同。 */
export interface ProviderRequestProfile {
  providerId: string;
  modelId: string;
  api: "anthropic_messages" | "openai_responses" | "openai_chat_completions";
  upstreamModel: string;
  requestedReasoning: "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max" | null;
  effectiveReasoning: "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max" | null;
  accessMode: "approval_required" | "full_access";
  configGeneration: string;
  promptRevision: string;
  toolCatalogRevision: string;
  contextWindowTokens: number;
  maxOutputTokens: number;
}

/** Usage 公共身份字段把计量绑定到唯一 Provider 请求，而不是整个 Turn。 */
interface TimelineContextUsageBase {
  requestId: string;
  requestOrdinal: number;
  modelRound: number;
  purpose: "assistant" | "summary";
  measuredAt: string;
}

/** 首版请求始终携带完整画像；UNKNOWN 只表示 Provider 未提供可信计量。 */
export type TimelineContextUsage =
  | (TimelineContextUsageBase & {
      profile: ProviderRequestProfile;
      certainty: "known";
      inputTokens: number;
      outputTokens: number;
      totalTokens: number;
    })
  | (TimelineContextUsageBase & {
      profile: ProviderRequestProfile;
      certainty: "unknown";
      inputTokens: null;
      outputTokens: null;
      totalTokens: null;
    });

/** Thread 重读没有事件外壳，因此必须显式携带 Usage 所属 Turn。 */
export type TimelineThreadContextUsage = TimelineContextUsage & { turnId: string };

export interface TimelineTurn {
  turnId: string;
  threadId: string;
  status: TimelineTurnState;
  threadRevision?: number;
  startedAt?: string;
  completedAt?: string;
  error?: { code: string; retryable: boolean };
  /** undefined 表示 terminal 后仍在重读；null 表示权威历史确认该 Turn 没有差异记录。 */
  changeSet?: TurnChangeSet | null;
}

export interface TimelineApproval {
  approvalId: string;
  threadId: string;
  turnId: string;
  /** 随请求观察到的 Revision；approval/respond 必须使用它执行 CAS。 */
  threadRevision: number;
  callId: string;
  toolName: string;
  reason: string;
  expiresAt: string;
}

export type ApprovalDecision = "approve" | "deny";

/** Work Row 只从语义 Projection Kind 推导，不能依赖 Title Text。 */
export function isWorkItem(item: TimelineItemAdapter): boolean {
  return (
    item.kind === "commentary" ||
    item.kind === "tool_call" ||
    item.kind === "command" ||
    item.kind === "approval"
  );
}

/** 返回稳定的用户可见 Label，避免重复 Role Chrome。 */
function itemKindLabel(item: TimelineItemAdapter): string {
  switch (item.kind) {
    case "user_message":
      return "用户问题";
    case "agent_message":
      return "最终答复";
    case "commentary":
      return "进度";
    case "tool_call":
      return "工具";
    case "command":
      return "命令";
    case "approval":
      return "需要确认";
  }
}

/** 返回第一个可安全描述 Work Step 的文本字段，不能回退到原始 Payload。 */
export function workStepLabel(step: WorkStepAdapter): string {
  const title = step.title?.trim();
  if (title) return title;
  const toolName = step.metadata?.toolName?.trim();
  if (toolName) return toolName;
  switch (step.kind) {
    case "tool_call":
      return "调用工具";
    case "command":
      return "执行命令";
    case "commentary":
      return "记录进度";
    default:
      return itemKindLabel(step);
  }
}

/** 读取已提交 Host Metadata 提供的有界 Duration。 */
export function itemDurationMs(item: TimelineItemAdapter): number | undefined {
  return boundedMetric(item.durationMs) ?? boundedMetric(item.metadata?.presentation?.durationMs);
}

/** 缺失 Usage Metric 必须保持缺失，不能渲染合成的零。 */
export function itemTokenCount(item: TimelineItemAdapter): number | undefined {
  const metadata = item.metadata;
  if (metadata === undefined) return undefined;
  if (metadata.usageInputTokens !== undefined || metadata.usageOutputTokens !== undefined) {
    return boundedMetric((metadata.usageInputTokens ?? 0) + (metadata.usageOutputTokens ?? 0));
  }
  return boundedMetric(metadata.usageTotalTokens);
}

/** 合并有界 Byte Alias，但不对缺失数据作出声明。 */
export function itemByteCount(item: TimelineItemAdapter): number | undefined {
  const metadata = item.metadata;
  if (metadata === undefined) return undefined;
  if (metadata.inputBytes !== undefined || metadata.outputBytes !== undefined) {
    return boundedMetric((metadata.inputBytes ?? 0) + (metadata.outputBytes ?? 0));
  }
  return boundedMetric(metadata.sizeBytes);
}

/** 解析 File Count Alias，同时让缺失状态继续可观察。 */
export function itemChangedFiles(item: TimelineItemAdapter): number | undefined {
  return boundedMetric(item.metadata?.changedFiles) ?? boundedMetric(item.changedFiles);
}

/** 仅在 Runtime 提供至少一个 Metric 时返回 Diff Stat。 */
export function itemDiffStat(
  item: TimelineItemAdapter,
): { additions?: number; deletions?: number } | undefined {
  const additions = item.metadata?.additions ?? item.additions;
  const deletions = item.metadata?.deletions ?? item.deletions;
  if (additions === undefined && deletions === undefined) return undefined;
  return { additions: boundedMetric(additions), deletions: boundedMetric(deletions) };
}

/** 返回 Tool 涉及的已校验相对路径，但不对 Read/Shell/MCP 推断文件被修改。 */
export function itemPresentedPaths(item: TimelineItemAdapter): readonly string[] {
  const paths = item.metadata?.presentation?.relativePaths ?? item.metadata?.relativePaths;
  return paths === undefined ? [] : [...paths];
}

/** 只返回能够由 Edit/Write 或显式 Diff Metric 证明为修改的路径，Read 永不进入修改集合。 */
export function itemChangedPaths(item: TimelineItemAdapter): readonly string[] {
  const presentation = item.metadata?.presentation;
  if (presentation !== undefined) {
    return presentation.kind === "edit" || presentation.kind === "write"
      ? [...presentation.relativePaths]
      : [];
  }
  return itemChangedFiles(item) !== undefined || itemDiffStat(item) !== undefined
    ? [...(item.metadata?.relativePaths ?? [])]
    : [];
}

/** 防御性格式化 Adapter 创建对象中的 Metric，避免不可信形状破坏视图。 */
function boundedMetric(value: number | undefined): number | undefined {
  return value !== undefined && Number.isSafeInteger(value) && value >= 0 ? value : undefined;
}

/** 将六种权威 Turn 状态映射为简洁中文 UI 文案。 */
export function turnStatusLabel(status: TimelineTurnState): string {
  switch (status) {
    case "queued":
      return "排队中";
    case "running":
      return "生成中";
    case "waiting_approval":
      return "等待确认";
    case "suspended":
      return "运行被中断";
    case "completed":
      return "已完成";
    case "cancelled":
      return "已中断";
    case "failed":
      return "失败";
  }
}

/** 只使用权威 Turn Timestamp 计算 Duration。 */
export function turnDurationMs(turn: TimelineTurn | undefined): number | undefined {
  if (turn?.startedAt === undefined || turn.completedAt === undefined) return undefined;
  const startedAt = Date.parse(turn.startedAt);
  const completedAt = Date.parse(turn.completedAt);
  if (!Number.isFinite(startedAt) || !Number.isFinite(completedAt) || completedAt < startedAt)
    return undefined;
  return completedAt - startedAt;
}

/** 为 Stream Metadata 变化创建稳定 Signature，避免无意义重渲染。 */
export function itemRevision(item: TimelineItemAdapter | undefined): string {
  if (item === undefined) return "";
  const metadata = item.metadata;
  return [
    item.itemId,
    item.status,
    item.text?.length ?? 0,
    item.title?.length ?? 0,
    itemDurationMs(item) ?? "",
    itemTokenCount(item) ?? "",
    itemByteCount(item) ?? "",
    itemChangedFiles(item) ?? "",
    itemDiffStat(item)?.additions ?? "",
    itemDiffStat(item)?.deletions ?? "",
    itemChangedPaths(item).join("|"),
    itemPresentedPaths(item).join("|"),
    metadata?.truncated === true ? "truncated" : "",
    metadata?.toolName ?? "",
    metadata?.toolKind ?? "",
    metadata?.phase ?? "",
    metadata?.inputBytes ?? "",
    metadata?.outputBytes ?? "",
    metadata?.usageInputTokens ?? "",
    metadata?.usageOutputTokens ?? "",
  ].join(":");
}
