// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { WorkspaceOpenTarget, WorkspaceOpenTargetInfo } from "../domain/openTarget";
import type { TimelineEvent, TimelineSnapshot } from "../domain/timelineContracts";

/** Conversation 目录使用的服务端 Thread 投影，不包含 timeline item 正文。 */
export interface ConversationThread {
  threadId: string;
  workspaceId: string;
  preferences: ConversationThreadPreferences | null;
  title: string;
  status: "active" | "archived" | "deleted";
  revision: number;
  createdAt: string;
  updatedAt: string;
}

/** Runtime 投影只用于判断当前 generation 是否允许恢复 authoritative snapshot。 */
export interface ConversationRuntimeState {
  status:
    | "starting"
    | "ready"
    | "busy"
    | "stopping"
    | "stopped"
    | "recovery_required"
    | "crashed"
    | "incompatible"
    | "faulted";
  generation: number;
  serverInstanceId?: string | null;
}

/** 手动压缩只返回 Java 投影的 Token 边界和身份，前端不接收摘要或策略参数。 */
export interface ConversationCompactionResult {
  outcome: "compacted" | "unchanged";
  compactionId: string | null;
  checkpointId: string | null;
  threadRevision: number;
  inputTokensBefore: number;
  inputTokensAfter: number;
}

/**
 * Conversation 端口只包含 Thread catalog、create 和 read；workspace 打开由独立端口负责，
 * 从类型层阻止会话 controller 成为第二个 workspace capability owner。
 */
export interface ConversationHistoryPort {
  threadCreate(input: {
    cwd?: string | null;
    title: string;
    providerId: string;
    modelId: string;
    reasoningLevel: "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max" | null;
    accessMode: "approval_required" | "full_access";
  }): Promise<ConversationThread>;
  threadList(input: { workspaceId: string; cursor?: string; limit?: number }): Promise<{
    items: ConversationThread[];
    nextCursor?: string | null;
  }>;
  threadSearch(input: {
    workspaceId: string;
    query: string;
    cursor?: string;
    limit?: number;
  }): Promise<{ items: ConversationThread[]; nextCursor?: string | null }>;
  threadRename(input: {
    threadId: string;
    title: string;
    expectedThreadRevision: number;
  }): Promise<ConversationThread>;
  threadPreferencesUpdate(input: {
    threadId: string;
    providerId: string;
    modelId: string;
    reasoningLevel: ReasoningLevel | null;
    accessMode: ConversationAccessMode;
    expectedThreadRevision: number;
  }): Promise<ConversationThread>;
  threadRead(input: {
    threadId: string;
    cursor?: string;
    limit?: number;
  }): Promise<TimelineSnapshot>;
  threadCompact(input: {
    threadId: string;
    expectedThreadRevision: number;
  }): Promise<ConversationCompactionResult>;
}

/** Conversation application 使用的模型选项，不反向依赖 Settings 的完整配置结构。 */
export type ReasoningLevel = "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max";
export type ConversationAccessMode = "approval_required" | "full_access";

/**
 * Composer 模型项同时保留真实上游模型标识与用户别名；前者用于避免 Provider/配置名称遮蔽
 * 实际调用对象，view value 仍只用于控件选择而非运行时身份。
 */
export interface ConversationModelOption {
  value: string;
  providerId: string;
  providerLabel: string;
  modelId: string;
  modelIdentifier: string;
  modelLabel: string;
  contextWindowTokens: number;
  reasoningLevelMap: Readonly<Partial<Record<ReasoningLevel, string>>>;
  defaultReasoningLevel: ReasoningLevel | null;
}

/** Conversation 只传递稳定的 v4 模型选择，不复制 Provider 连接配置。 */
export interface ConversationModelSelection {
  providerId: string;
  modelId: string;
  reasoningLevel: ReasoningLevel | null;
}

/** Thread 偏好是下一 Turn 的唯一选择事实，既有 Turn 的 runtime snapshot 不受此对象变化影响。 */
export interface ConversationThreadPreferences extends ConversationModelSelection {
  accessMode: ConversationAccessMode;
  titleSource: "placeholder" | "auto" | "manual";
}

/** Turn 接纳结果只保留 Conversation 并发编排所需的稳定字段。 */
export interface ConversationAcceptedTurn {
  accepted: true;
  turnId: string;
  queued: boolean;
  threadRevision: number;
}

/** Cancel command 只确认请求被接纳；最终状态仍由 Timeline event 决定。 */
export interface ConversationCancelResult {
  accepted: true;
  turnId: string;
  status: "queued" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled";
  threadRevision: number;
}

/** 追加输入 ACK 保留服务端签发的 input identity，前端不自行生成队列项。 */
export interface ConversationQueuedInputResult {
  accepted: true;
  inputId: string;
  turnId: string;
  kind: "steering" | "follow_up";
  status: "queued";
}

/** 活动 Turn 的追加输入只区分两个服务端消费时机，不在前端复制队列实现。 */
export type ConversationQueueMode = "steering" | "follow_up";

/** Composer 提交意图只包含文本与当前 UI 模型选择，避免 UI 拼装 Runtime DTO。 */
export interface ConversationSubmit {
  text: string;
  attachmentIds?: readonly string[];
}

/** WebView 只接收受管附件 identity 与展示元数据，绝不接收用户绝对路径或 staging token。 */
export interface ConversationAttachment {
  attachmentId: string;
  fileName: string;
  sizeBytes: number;
  mediaType?: string | null;
}

/** 原生 picker、Rust ingress 与 App Server import 必须在一次 adapter 调用内完成。 */
export interface ConversationAttachmentPort {
  importAttachments(): Promise<readonly ConversationAttachment[]>;
  discardAttachment(input: { attachmentId: string }): Promise<void>;
}

/** 冻结 artifact 读取只接收稳定身份；workspaceId 仅用于 Rust active binding 授权。 */
export interface ConversationArtifactPort {
  readToolArtifact(input: {
    workspaceId: string;
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }): Promise<string>;
  readTurnDiff(input: {
    workspaceId: string;
    threadId: string;
    turnId: string;
    artifactId: string;
  }): Promise<string>;
}

/**
 * Conversation Turn 端口封闭提交、取消、追加和审批能力；application 只依赖语义方法，
 * 不接触 Tauri command、JA-RPC envelope 或 RuntimeProvider 的内部状态机。
 */
export interface ConversationTurnPort {
  submitTurn(input: {
    threadId: string;
    content: Array<{ type: "text"; text: string } | { type: "attachment"; attachmentId: string }>;
  }): Promise<ConversationAcceptedTurn>;
  cancelTurn(input: {
    turnId: string;
    expectedThreadRevision: number;
  }): Promise<ConversationCancelResult>;
  steerTurn(input: { turnId: string; text: string }): Promise<ConversationQueuedInputResult>;
  followUpTurn(input: { turnId: string; text: string }): Promise<ConversationQueuedInputResult>;
  approvalRespond(input: {
    approvalId: string;
    turnId: string;
    decision: "approve" | "deny";
    expectedThreadRevision: number;
  }): Promise<void>;
}

/** 偏好更新通过 Thread CAS 落盘，只影响下一 Turn，不触发 Settings 默认值或新建会话。 */
export interface ConversationPreferencesPort {
  updatePreferences(input: {
    providerId: string;
    modelId: string;
    reasoningLevel: ReasoningLevel | null;
    accessMode: ConversationAccessMode;
  }): Promise<void>;
}

/**
 * Conversation 只需要 target discovery 与受控 open，用窄端口阻止 UI 获得 tree、mutation
 * 或 watcher 能力；Tauri workspace adapter 通过结构类型注入。
 */
export interface WorkspaceOpenPort {
  openTargets(input: { workspaceId: string }): Promise<{ targets: WorkspaceOpenTargetInfo[] }>;
  open(input: {
    workspaceId: string;
    target: WorkspaceOpenTarget;
    relativePath?: string;
  }): Promise<{
    opened: true;
    target: WorkspaceOpenTarget;
    relativePath: string;
    entryKind: "file" | "directory";
  }>;
}

/** RuntimeProvider 已完成 native frame 校验，Store 只接收这一封闭投影。 */
export type ConversationHostEvent =
  | {
      kind: "status";
      status: ConversationRuntimeState;
      eventId: string;
      occurredAt: string;
      reason?: string;
    }
  | { kind: "timeline"; event: TimelineEvent }
  | { kind: "projection_fault"; reason: "invalid_native_event" };
