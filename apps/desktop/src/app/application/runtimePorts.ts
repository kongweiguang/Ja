// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ConversationHostEvent } from "@/features/conversation";

type RuntimeStatusKind =
  | "starting"
  | "ready"
  | "busy"
  | "stopping"
  | "stopped"
  | "recovery_required"
  | "crashed"
  | "incompatible"
  | "faulted";

/** Application 只保留 lifecycle admission 所需字段，不复用 Tauri command DTO。 */
export interface RuntimeStatus {
  status: RuntimeStatusKind;
  generation: number;
  serverInstanceId?: string | null;
}

/** 恢复投影携带人工确认所需 CAS，不暴露 recovery 文件或原生诊断。 */
export interface RuntimeRecoveryState {
  required: boolean;
  acknowledgeable: boolean;
  recoveryId?: string | null;
  revision?: number | null;
}

export type RecoveryReason = "SystemRestarted" | "ExternallyCleaned";

/** 人工恢复确认由 application 从当前权威投影构造，UI 不能直接提交 identity。 */
export interface ManualRecoveryConfirmation {
  recoveryId: string;
  revision: number;
  reason: RecoveryReason;
}

/** Runtime storage 仅投影设置页可展示的脱敏路径与 Native Image 状态。 */
export interface RuntimeStorageInfo {
  nativeImage: boolean;
  dataPath: string;
  logPath: string | null;
  cachePath: string | null;
  lastBackup: string | null;
}

/** 无项目会话只消费 Java 签发的固定 workspace identity，不允许 renderer 合成。 */
export interface GeneralWorkspace {
  workspaceId: string;
  displayName: string;
  trust: "trusted";
  rootPath: string;
}

export interface TurnStartInput {
  threadId: string;
  content: Array<{ type: "text"; text: string } | { type: "attachment"; attachmentId: string }>;
  deadlineMs?: number;
}

export interface TurnAccepted {
  accepted: true;
  turnId: string;
  queued: boolean;
  threadRevision: number;
}

export interface TurnCancelInput {
  turnId: string;
  expectedThreadRevision: number;
}

export interface TurnCancelResult {
  accepted: true;
  turnId: string;
  status: "queued" | "running" | "waiting_approval" | "completed" | "failed" | "cancelled";
  threadRevision: number;
}

export interface TurnQueuedInput {
  turnId: string;
  text: string;
}

export interface TurnQueuedInputResult {
  accepted: true;
  inputId: string;
  turnId: string;
  kind: "steering" | "follow_up";
  status: "queued";
}

export interface ApprovalResponseInput {
  approvalId: string;
  turnId: string;
  decision: "approve" | "deny";
  expectedThreadRevision: number;
}

interface RuntimeSkillListResult {
  items: Array<{
    skillId: string;
    name: string;
    scope: "builtin" | "user" | "workspace";
    enabled: boolean;
    status: "healthy" | "invalid" | "unavailable";
    description: string;
  }>;
  nextCursor: string | null;
}

interface RuntimeMcpListResult {
  items: Array<{
    mcpId: string;
    name: string;
    transport: "stdio" | "streamable_http";
    status: "healthy" | "degraded" | "unavailable" | "disabled";
    toolCount: number;
  }>;
  nextCursor: string | null;
}

interface RuntimeMcpTestResult {
  mcpId: string;
  status: "healthy" | "degraded" | "unavailable";
  toolCount: number;
}

interface RuntimeMcpToolsResult {
  items: Array<{ name: string; description: string; inputSchema: Record<string, unknown> }>;
  nextCursor: string | null;
}

interface RuntimeModelTestResult {
  responseModel: string;
  latencyMs: number;
}

interface RuntimeSettingsOperations {
  "skill/list": { params: { cursor?: string; limit?: number }; result: RuntimeSkillListResult };
  "mcp/list": { params: { cursor?: string; limit?: number }; result: RuntimeMcpListResult };
  "mcp/test": { params: { mcpId: string }; result: RuntimeMcpTestResult };
  "model/test": {
    params: { providerId: string; modelId: string };
    result: RuntimeModelTestResult;
  };
  "mcp/list-tools": {
    params: { mcpId: string; cursor?: string; limit?: number };
    result: RuntimeMcpToolsResult;
  };
}

export type RuntimeSettingsMethod = keyof RuntimeSettingsOperations;
export type RuntimeSettingsParams<M extends RuntimeSettingsMethod> =
  RuntimeSettingsOperations[M]["params"];
export type RuntimeSettingsResult<M extends RuntimeSettingsMethod> =
  RuntimeSettingsOperations[M]["result"];
export type RuntimeQuery = <M extends RuntimeSettingsMethod>(
  method: M,
  params: RuntimeSettingsParams<M>,
) => Promise<RuntimeSettingsResult<M>>;

type RuntimeTimelineEvent = Extract<ConversationHostEvent, { kind: "timeline" }>;
type RuntimeProjectionFaultEvent = Extract<ConversationHostEvent, { kind: "projection_fault" }>;

/** Adapter 已解析事件后，application 只接收 status、timeline 与恢复信号的封闭联合。 */
export type RuntimeHostEvent =
  | { kind: "status"; status: RuntimeStatus; eventId: string; occurredAt: string; reason?: string }
  | RuntimeTimelineEvent
  | RuntimeProjectionFaultEvent;

type RuntimeHostUnsubscribe = () => void | Promise<void>;

/** Timeline owner 只接收经过 Runtime admission 校验的状态字段，不暴露 lifecycle controller。 */
export interface RuntimeStatusProjection {
  readonly status: RuntimeStatusKind;
  readonly generation: number;
  readonly serverInstanceId?: string | null;
  readonly eventId?: string;
  readonly occurredAt?: string;
  readonly reason?: string;
}

/** turn/start ACK 建立事件重放基线，submitted text 仅用于生成当前用户消息投影。 */
interface RuntimeAcceptedTurnProjection {
  readonly threadId: string;
  readonly turnId: string;
  readonly threadRevision: number;
  readonly submittedText: string;
  readonly submittedAt: string;
}

/**
 * Runtime application 只发布已准入的 projection intent，不知道 Conversation Store、Reducer
 * 或 Zustand。composition adapter 负责把这些意图交给唯一 Timeline owner，不能建立第二份状态。
 */
export interface RuntimeProjectionPort {
  currentGeneration(): number;
  applyRuntimeStatus(status: RuntimeStatusProjection): void;
  applyTurnAccepted(accepted: RuntimeAcceptedTurnProjection): void;
  applyHostEvent(event: Exclude<RuntimeHostEvent, { kind: "status" }>): void;
}

/**
 * RuntimeHostPort 是 application 的唯一原生边界；实现必须在 composition 注入，controller
 * 无权创建 adapter、选择 command 或接触 wire payload。
 */
export interface RuntimeHostPort {
  start(): Promise<RuntimeStatus>;
  stop(): Promise<RuntimeStatus>;
  state(): Promise<RuntimeStatus>;
  storageInfo(): Promise<RuntimeStorageInfo>;
  generalWorkspace(): Promise<GeneralWorkspace>;
  recoveryState(): Promise<RuntimeRecoveryState>;
  acknowledgeRecovery(confirmation: ManualRecoveryConfirmation): Promise<RuntimeRecoveryState>;
  approvalRespond(input: ApprovalResponseInput): Promise<void>;
  turnStart(input: TurnStartInput): Promise<TurnAccepted>;
  turnCancel(input: TurnCancelInput): Promise<TurnCancelResult>;
  turnSteer(input: TurnQueuedInput): Promise<TurnQueuedInputResult>;
  turnFollowUp(input: TurnQueuedInput): Promise<TurnQueuedInputResult>;
  query: RuntimeQuery;
  subscribe(listener: (event: RuntimeHostEvent) => void): Promise<RuntimeHostUnsubscribe>;
}

export type RuntimeApplicationErrorCode =
  | "INVALID_INPUT"
  | "RUNTIME_UNAVAILABLE"
  | "RECOVERY_REQUIRED"
  | "RUNTIME_NOT_READY"
  | "SENSITIVE_EVENT_BLOCKED";

const RUNTIME_ERROR_CATALOG: Record<
  RuntimeApplicationErrorCode,
  { message: string; retryable: boolean }
> = {
  INVALID_INPUT: { message: "请求参数无效", retryable: false },
  RUNTIME_UNAVAILABLE: { message: "运行时暂不可用", retryable: true },
  RECOVERY_REQUIRED: { message: "需要先完成运行时恢复", retryable: false },
  RUNTIME_NOT_READY: { message: "运行时尚未就绪，请重试", retryable: true },
  SENSITIVE_EVENT_BLOCKED: { message: "运行时事件包含受保护数据", retryable: false },
};

/** Application error 只暴露本地稳定 code/message/retryable，不继承 adapter 诊断文本。 */
export class RuntimeApplicationError extends Error {
  /** 显式保存稳定 code/retryable，避免 React 依赖 adapter Error 的可变字段。 */
  constructor(
    readonly code: RuntimeApplicationErrorCode,
    message: string,
    readonly retryable: boolean,
  ) {
    super(message);
    this.name = "RuntimeApplicationError";
  }
}

/**
 * 将任意 adapter rejection 映射到本地 allow-list；只读取稳定 code，不信任 message、stack
 * 或 Tauri error shape，防止原生诊断进入 React 状态。
 */
export function normalizeRuntimeApplicationError(error: unknown): RuntimeApplicationError {
  if (error instanceof RuntimeApplicationError) return error;
  const candidate =
    typeof error === "object" && error !== null ? (error as { code?: unknown }).code : undefined;
  const code =
    typeof candidate === "string" && candidate in RUNTIME_ERROR_CATALOG
      ? (candidate as RuntimeApplicationErrorCode)
      : "RUNTIME_UNAVAILABLE";
  const definition = RUNTIME_ERROR_CATALOG[code];
  return new RuntimeApplicationError(code, definition.message, definition.retryable);
}
