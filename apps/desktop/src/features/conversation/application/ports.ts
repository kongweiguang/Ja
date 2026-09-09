// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { WorkspaceOpenTarget, WorkspaceOpenTargetInfo } from "../domain/openTarget";
import type {
  AttachmentSummary,
  InputQueue,
  TimelineEvent,
  TimelineSnapshot,
} from "../domain/timelineContracts";
import type { ConversationContextReference, UserContentBlock } from "../domain/userContent";

/** Conversation 目录使用的服务端 Thread 投影，不包含 timeline item 正文。 */
export interface ConversationThread {
  threadId: string;
  workspaceId: string;
  activeGoalId: string | null;
  preferences: ConversationThreadPreferences | null;
  title: string;
  status: "active" | "archived" | "deleted";
  pinned: boolean;
  latestTurnStatus:
    | "queued"
    | "running"
    | "waiting_approval"
    | "suspended"
    | "completed"
    | "failed"
    | "cancelled"
    | null;
  latestTurnSeen: boolean;
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
    collaborationMode: ConversationCollaborationMode;
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
    collaborationMode: ConversationCollaborationMode;
    expectedThreadRevision: number;
  }): Promise<ConversationThread>;
  threadPin(input: {
    threadId: string;
    pinned: boolean;
    expectedThreadRevision: number;
  }): Promise<ConversationThread>;
  threadSeen(input: {
    threadId: string;
    expectedThreadRevision: number;
  }): Promise<ConversationThread>;
  threadArchive(input: {
    threadId: string;
    expectedThreadRevision: number;
  }): Promise<ConversationThread>;
  threadRestore(input: {
    threadId: string;
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
export type ConversationCollaborationMode = "default" | "plan";

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

/** Conversation 只传递稳定的 v1 模型选择，不复制 Provider 连接配置。 */
export interface ConversationModelSelection {
  providerId: string;
  modelId: string;
  reasoningLevel: ReasoningLevel | null;
}

/** Thread 偏好在下一次 Provider 请求安全点生效，不改变已经发出的请求或已准备 Tool batch。 */
export interface ConversationThreadPreferences extends ConversationModelSelection {
  accessMode: ConversationAccessMode;
  collaborationMode: ConversationCollaborationMode;
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
  status:
    | "queued"
    | "running"
    | "waiting_approval"
    | "suspended"
    | "completed"
    | "failed"
    | "cancelled";
  threadRevision: number;
}

/** 队列 mutation ACK 总是返回全量权威投影，避免 Response 与 Event 形成双 owner。 */
export interface ConversationInputQueueMutationResult {
  accepted: true;
  inputId: string;
  inputQueue: InputQueue;
}

/** Composer 提交意图只包含文本与当前 UI 模型选择，避免 UI 拼装 Runtime DTO。 */
export interface ConversationSubmit {
  text: string;
  attachmentIds?: readonly string[];
  contextReferences?: readonly ConversationContextReference[];
}

/** WebView 只接收受管附件 identity 与展示元数据，绝不接收用户绝对路径或 staging token。 */
export interface ConversationAttachment {
  attachmentId: string;
  fileName: string;
  sizeBytes: number;
  mediaKind: "text" | "image" | "pdf" | "binary";
  mediaType?: string | null;
  /** 缩略图只能来自 Rust 受控协议，缺失时 UI 使用文件类型图标而不是读取本地路径。 */
  thumbnailUrl?: string;
}

/** 单个导入尝试的阶段只描述可观察进度，不暴露 staging 或 App Server 内部边界。 */
export type ConversationAttachmentImportPhase = "copying" | "importing";

/** Channel 事件按 operation、attempt 与 item 三重 identity 隔离，允许多文件并发且不串进度。 */
export type ConversationAttachmentImportEvent =
  | {
      kind: "started";
      operationId: string;
      attemptId: string;
      itemId: string;
      fileName: string;
      sizeBytes?: number;
      mediaKind?: ConversationAttachment["mediaKind"];
      mediaType?: string | null;
    }
  | {
      kind: "progress";
      operationId: string;
      attemptId: string;
      itemId: string;
      phase: ConversationAttachmentImportPhase;
      bytesCopied: number;
      totalBytes?: number;
    }
  | {
      kind: "completed";
      operationId: string;
      attemptId: string;
      itemId: string;
      attachment: ConversationAttachment;
    }
  | {
      kind: "failed";
      operationId: string;
      attemptId: string;
      itemId: string;
      fileName?: string;
      sizeBytes?: number;
      mediaKind?: ConversationAttachment["mediaKind"];
      mediaType?: string | null;
      code: string;
      message: string;
      retryable: boolean;
    }
  | {
      kind: "cancelled";
      operationId: string;
      attemptId: string;
      itemId: string;
    };

/** Composer 草稿附件显式枚举恢复状态；只有 ready 项持有可提交 attachmentId。 */
export type ConversationAttachmentDraftItem =
  | {
      state: "importing";
      operationId: string;
      attemptId: string;
      itemId: string;
      fileName: string;
      sizeBytes?: number;
      mediaKind?: ConversationAttachment["mediaKind"];
      mediaType?: string | null;
      phase: ConversationAttachmentImportPhase;
      bytesCopied: number;
      totalBytes?: number;
      cancelRequested?: boolean;
    }
  | ({ state: "ready"; itemId: string } & ConversationAttachment)
  | {
      state: "failed";
      operationId: string;
      attemptId: string;
      itemId: string;
      fileName: string;
      sizeBytes?: number;
      mediaKind?: ConversationAttachment["mediaKind"];
      mediaType?: string | null;
      code: string;
      message: string;
      retryable: boolean;
    }
  | {
      state: "removing";
      itemId: string;
      fileName: string;
      sizeBytes: number;
      mediaKind: ConversationAttachment["mediaKind"];
      mediaType?: string | null;
      thumbnailUrl?: string;
    };

export interface ConversationAttachmentImportInput {
  operationId: string;
  onEvent: (event: ConversationAttachmentImportEvent) => void;
}

export interface ConversationClipboardImportResult {
  outcome: "accepted" | "nothing_importable" | "busy";
}

/** 原生 picker/drop/clipboard/retry 各有窄入口，长任务进度只通过 caller-owned Channel 返回。 */
export interface ConversationAttachmentPort {
  pickerImport(input: ConversationAttachmentImportInput): Promise<void>;
  dropImport(input: ConversationAttachmentImportInput & { dropToken: string }): Promise<void>;
  clipboardImport(
    input: ConversationAttachmentImportInput,
  ): Promise<ConversationClipboardImportResult>;
  retryImport(input: ConversationAttachmentImportInput & { attemptId: string }): Promise<void>;
  cancelImport(input: { operationId: string; itemId?: string }): Promise<void>;
  discardAttempt(input: { attemptId: string }): Promise<void>;
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
  readTurnDiff(
    input: {
      workspaceId: string;
      threadId: string;
      turnId: string;
      artifactId: string;
      filePath: string;
    },
    signal?: AbortSignal,
  ): Promise<{
    artifactId: string;
    filePath: string;
    byteLength: number;
    sha256: string;
    content: string;
  }>;
}

/**
 * Conversation Turn 端口封闭提交、取消、追加和审批能力；application 只依赖语义方法，
 * 不接触 Tauri command、JA-RPC envelope 或 RuntimeProvider 的内部状态机。
 */
export interface ConversationTurnPort {
  submitTurn(input: {
    threadId: string;
    content: UserContentBlock[];
    /**
     * 附件摘要仅用于 turn/start ACK 前后的本地 Timeline 投影；Runtime adapter 必须在调用
     * JA-RPC 前剥离它，服务端仍根据 attachmentId 建立权威绑定与持久摘要。
     */
    projectionAttachments?: readonly AttachmentSummary[];
  }): Promise<ConversationAcceptedTurn>;
  resumeTurn(input: {
    turnId: string;
    expectedThreadRevision: number;
  }): Promise<ConversationAcceptedTurn>;
  cancelTurn(input: {
    turnId: string;
    expectedThreadRevision: number;
  }): Promise<ConversationCancelResult>;
  enqueueTurnInput(input: {
    turnId: string;
    content: UserContentBlock[];
  }): Promise<ConversationInputQueueMutationResult>;
  prioritizeTurnInput(input: {
    turnId: string;
    inputId: string;
    expectedInputRevision: number;
  }): Promise<ConversationInputQueueMutationResult>;
  updateTurnInput(input: {
    turnId: string;
    inputId: string;
    expectedInputRevision: number;
    content: UserContentBlock[];
  }): Promise<ConversationInputQueueMutationResult>;
  deleteTurnInput(input: {
    turnId: string;
    inputId: string;
    expectedInputRevision: number;
  }): Promise<ConversationInputQueueMutationResult>;
  approvalRespond(input: {
    approvalId: string;
    turnId: string;
    decision: "approve" | "deny";
    expectedThreadRevision: number;
  }): Promise<void>;
}

/** Plan mode 只等待独立 Plan artifact 的持久化 ACK，不读取、创建或改写 Goal 聚合。 */
export interface ConversationPlanCreationPort {
  create(
    ownerThreadId: string,
    objective: string,
    expectedThreadRevision: number,
  ): Promise<boolean>;
}

/** 偏好更新通过 Thread CAS 落盘，在下一次 Provider 请求安全点生效且不触发 Settings 默认值。 */
export interface ConversationPreferencesPort {
  updatePreferences(input: {
    providerId: string;
    modelId: string;
    reasoningLevel: ReasoningLevel | null;
    accessMode: ConversationAccessMode;
    collaborationMode: ConversationCollaborationMode;
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
