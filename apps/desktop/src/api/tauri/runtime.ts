// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { listen as tauriListen, type UnlistenFn } from "@tauri-apps/api/event";
import { z } from "zod";
import { invokeNativeCommand } from "./nativeInvoke";
// Credential bytes 由 Rust 拥有，永远不能进入 runtime command DTO。
import {
  assertSafePayload,
  SafeNameSchema,
  TurnContentSchema,
  parseEvent,
  ServerInstanceIdSchema,
  RevisionSchema as RuntimeGenerationSchema,
  WorkspaceIdSchema,
  type JaEvent,
} from "../protocol/protocol";
import {
  parseMethodParams,
  parseMethodResult,
  type ClientMethod,
  type MethodParams,
  type MethodResult,
} from "../protocol/methods";
import { READY_TOKEN_PATTERN } from "../protocol/readyToken";

/** Rust host 拥有全部生命周期 command，并且只公开一个固定事件。 */
export const JA_RUNTIME_COMMANDS = {
  start: "ja_runtime_start",
  stop: "ja_runtime_stop",
  state: "ja_runtime_state",
  storageInfo: "ja_runtime_storage_info",
  generalWorkspace: "ja_runtime_general_workspace",
  recoveryState: "ja_runtime_recovery_state",
  acknowledgeRecovery: "ja_runtime_acknowledge_recovery",
  approvalRespond: "ja_approval_respond",
  turnStart: "ja_turn_start",
  turnCancel: "ja_turn_cancel",
  turnSteer: "ja_turn_steer",
  turnFollowUp: "ja_turn_follow_up",
  query: "ja_runtime_query",
} as const;

/** Settings 只公开冻结的 Skills/MCP 方法；host health 留在 Rust 启动准入内部。 */
export type RuntimeSettingsMethod = Extract<
  ClientMethod,
  "skill/list" | "mcp/list" | "mcp/test" | "model/test" | "mcp/list-tools"
>;
export type RuntimeSettingsParams<M extends RuntimeSettingsMethod> = MethodParams<M>;
export type RuntimeSettingsResult<M extends RuntimeSettingsMethod> = MethodResult<M>;
type RuntimeQuery = <M extends RuntimeSettingsMethod>(
  method: M,
  params: RuntimeSettingsParams<M>,
) => Promise<RuntimeSettingsResult<M>>;

const JA_RUNTIME_EVENTS = {
  frame: "ja://rpc/frame",
} as const;

const RuntimeStatusKindSchema = z.enum([
  "starting",
  "ready",
  "busy",
  "stopping",
  "stopped",
  "recovery_required",
  "crashed",
  "incompatible",
  "faulted",
]);

const RuntimeStatusWireKindSchema = z.enum([
  "starting",
  "ready",
  "busy",
  "stopping",
  "shutting_down",
  "stopped",
  "recovery_required",
  "crashed",
  "incompatible",
  "faulted",
]);

/** 允许原生 host 的 generation-zero 启动/停止投影；一旦存在活动 sidecar 代际就要求正 generation。 */
function isRuntimeGenerationValid(status: string, generation: number): boolean {
  return (
    Number.isSafeInteger(generation) &&
    (((status === "starting" || status === "stopped") && generation >= 0) ||
      (status !== "starting" && status !== "stopped" && generation > 0))
  );
}

/**
 * 接受 host generation-zero 生命周期投影，因为 Rust 会在 sidecar 代际准入前
 * 刻意使用它们表示启动边界。
 */
function isRuntimeCommandGenerationValid(status: string, generation: number): boolean {
  return (
    isRuntimeGenerationValid(status, generation) ||
    (status === "recovery_required" && generation === 0)
  );
}

const RuntimeStatusSchema = z
  .object({
    status: RuntimeStatusKindSchema,
    generation: RuntimeGenerationSchema,
    serverInstanceId: ServerInstanceIdSchema.nullable().optional(),
  })
  .strict()
  .refine((value) => isRuntimeCommandGenerationValid(value.status, value.generation), {
    message: "runtime generation zero is only valid for recovery_required or stopped host",
  });

const RecoveryReasonSchema = z.enum(["SystemRestarted", "ExternallyCleaned"]);

const RuntimeRecoveryStateSchema = z
  .object({
    required: z.boolean(),
    acknowledgeable: z.boolean(),
    recoveryId: z.string().min(1).max(128).nullable().optional(),
    revision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER).nullable().optional(),
  })
  .strict();

const ManualRecoveryConfirmationSchema = z
  .object({
    recoveryId: z.string().min(1).max(128),
    revision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
    reason: RecoveryReasonSchema,
  })
  .strict();

const TurnStartInputSchema = z
  .object({
    threadId: z
      .string()
      .regex(/^thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(128),
    content: TurnContentSchema,
    deadlineMs: z.number().int().min(1_000).max(86_400_000).optional(),
  })
  .strict();

/**
 * 镜像 Rust cancel DTO；revision CAS 取代旧 thread/reason hint，
 * 陈旧点击不能取消同一 Turn 的更新状态。
 */
const TurnCancelInputSchema = z
  .object({
    turnId: z
      .string()
      .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(101),
    expectedThreadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

const TurnCancelResultSchema = z
  .object({
    accepted: z.literal(true),
    turnId: z
      .string()
      .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(101),
    status: z.enum(["queued", "running", "waiting_approval", "completed", "failed", "cancelled"]),
    threadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

/** active-Turn 输入刻意小于第二个 turn/start envelope；其 thread、Provider/Model、权限和 deadline 已由 Java 拥有。 */
const TurnQueuedInputSchema = z
  .object({
    turnId: z
      .string()
      .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(101),
    text: z
      .string()
      .min(1)
      .max(4_000_000)
      .refine((value) => !value.includes("\0"), "text contains NUL"),
  })
  .strict();

const TurnQueuedInputResultSchema = z
  .object({
    accepted: z.literal(true),
    inputId: z
      .string()
      .regex(/^input_[A-Za-z0-9][A-Za-z0-9._-]{0,121}$/)
      .max(128),
    turnId: z
      .string()
      .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(101),
    kind: z.enum(["steering", "follow_up"]),
    status: z.literal("queued"),
  })
  .strict();

const TurnAcceptedSchema = z
  .object({
    accepted: z.literal(true),
    turnId: z
      .string()
      .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(128),
    queued: z.boolean(),
    threadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

const ApprovalResponseInputSchema = z
  .object({
    approvalId: z
      .string()
      .regex(/^appr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(128),
    turnId: z
      .string()
      .regex(/^turn_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(128),
    decision: z.enum(["approve", "deny"]),
    expectedThreadRevision: z.number().int().min(0).max(Number.MAX_SAFE_INTEGER),
  })
  .strict();

const RuntimeStatusEventParamsSchema = z
  .object({
    serverInstanceId: ServerInstanceIdSchema,
    eventId: z
      .string()
      .regex(/^evt_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/)
      .max(100),
    occurredAt: z.string().datetime({ offset: true }).max(64),
    status: RuntimeStatusWireKindSchema,
    reason: z.string().max(1024).optional(),
    // Rust 在 WebView 交付前消费 ready challenge；generation zero 只会在 host
    // 启动/停止投影中省略，并在下方恢复。
    generation: RuntimeGenerationSchema.optional(),
  })
  .strict()
  .superRefine((value, context) => {
    const generation = value.generation ?? 0;
    if (!isRuntimeCommandGenerationValid(value.status, generation)) {
      context.addIssue({
        code: "custom",
        path: ["generation"],
        message: "runtime status generation does not match its lifecycle state",
      });
    }
  });

const RuntimeStorageInfoSchema = z
  .object({
    nativeImage: z.boolean(),
    dataPath: z.string().min(1).max(4096),
    logPath: z.string().min(1).max(4096).nullable(),
    cachePath: z.string().min(1).max(4096).nullable(),
    lastBackup: z.string().min(1).max(256).nullable(),
  })
  .strict();

/**
 * 校验 Java 签发的通用 workspace 投影，同时保留现有 Tauri rootPath 映射；
 * 服务端身份不得合成，也不能收窄为客户端选择的字面量。
 */
const GeneralWorkspaceSchema = z
  .object({
    workspaceId: WorkspaceIdSchema,
    displayName: SafeNameSchema,
    trust: z.literal("trusted"),
    rootPath: z
      .string()
      .min(1)
      .max(4096)
      .refine(
        (value) =>
          !Array.from(value).some((character) => {
            const code = character.codePointAt(0) ?? 0;
            return code <= 0x1f || code === 0x7f;
          }),
        "workspace path contains control characters",
      ),
  })
  .strict();

const RuntimeStatusEventSchema = z
  .object({
    jsonrpc: z.literal("2.0"),
    method: z.literal("runtime/status-changed"),
    params: RuntimeStatusEventParamsSchema,
  })
  .strict()
  .refine((value) => !("id" in value) && !("result" in value) && !("error" in value), {
    message: "runtime event envelope is not a response",
  });

type RuntimeStatusKind = z.infer<typeof RuntimeStatusKindSchema>;
export type RuntimeStatus = z.infer<typeof RuntimeStatusSchema>;
export type RuntimeStorageInfo = z.infer<typeof RuntimeStorageInfoSchema>;
export type GeneralWorkspace = z.infer<typeof GeneralWorkspaceSchema>;
export type RuntimeRecoveryState = z.infer<typeof RuntimeRecoveryStateSchema>;
export type ManualRecoveryConfirmation = z.infer<typeof ManualRecoveryConfirmationSchema>;
export type TurnStartInput = z.infer<typeof TurnStartInputSchema>;
export type TurnAccepted = z.infer<typeof TurnAcceptedSchema>;
export type TurnCancelInput = z.infer<typeof TurnCancelInputSchema>;
export type TurnCancelResult = z.infer<typeof TurnCancelResultSchema>;
export type TurnQueuedInput = z.infer<typeof TurnQueuedInputSchema>;
export type TurnQueuedInputResult = z.infer<typeof TurnQueuedInputResultSchema>;
export type ApprovalResponseInput = z.infer<typeof ApprovalResponseInputSchema>;

const SAFE_RUNTIME_REASONS = new Set([
  "starting",
  "ready",
  "turn_started",
  "stopping",
  "stopped",
  "start_failed",
  "event_queue_overflow",
  "sidecar_protocol_invalid_envelope",
  "sidecar_protocol_frame_failed",
  "sidecar_protocol_invalid_id",
  "sidecar_protocol_invalid_error_catalog",
  "sidecar_protocol_invalid_json",
  "sidecar_protocol_partial_frame",
  "sidecar_protocol_io",
  "sidecar_protocol_invalid_utf8",
  "sidecar_protocol_duplicate_key",
  "sidecar_protocol_empty_frame",
  "sidecar_protocol_non_object",
  "sidecar_protocol_frame_too_large",
  "sidecar_protocol_invalid_limit",
]);
type RuntimeReason =
  | "starting"
  | "ready"
  | "turn_started"
  | "stopping"
  | "stopped"
  | "start_failed"
  | "event_queue_overflow"
  | "sidecar_protocol_invalid_envelope"
  | "sidecar_protocol_frame_failed"
  | "sidecar_protocol_invalid_id"
  | "sidecar_protocol_invalid_error_catalog"
  | "sidecar_protocol_invalid_json"
  | "sidecar_protocol_partial_frame"
  | "sidecar_protocol_io"
  | "sidecar_protocol_invalid_utf8"
  | "sidecar_protocol_duplicate_key"
  | "sidecar_protocol_empty_frame"
  | "sidecar_protocol_non_object"
  | "sidecar_protocol_frame_too_large"
  | "sidecar_protocol_invalid_limit"
  | "unknown";

/** 将 Rust 事件专用拼写规范化为 command DTO 状态集，避免 UI 维护第二套生命周期。 */
function normalizeWireStatus(
  status: z.infer<typeof RuntimeStatusWireKindSchema>,
): RuntimeStatusKind {
  return status === "shutting_down" ? "stopping" : status;
}

/** 将不可信诊断转换为有限且不敏感的 reason class。 */
function safeRuntimeReason(reason: string | undefined): RuntimeReason | undefined {
  if (reason === undefined) {
    return undefined;
  }
  return SAFE_RUNTIME_REASONS.has(reason) ? (reason as RuntimeReason) : "unknown";
}

export type RuntimeHostEvent =
  | {
      kind: "status";
      status: RuntimeStatus;
      eventId: string;
      occurredAt: string;
      reason?: RuntimeReason;
    }
  | { kind: "timeline"; event: RuntimeTimelineEvent }
  | { kind: "projection_fault"; reason: "invalid_native_event" };

export type RuntimeHostUnsubscribe = () => void | Promise<void>;
export type RuntimeHostListener = (event: RuntimeHostEvent) => void;

export interface RuntimeNativeBridge {
  invoke<T>(command: string, args?: Record<string, unknown>): Promise<T>;
  listen<T>(event: string, handler: (payload: T) => void): Promise<UnlistenFn>;
}

export const defaultNativeBridge: RuntimeNativeBridge = {
  /** 所有 command 统一穿过 nativeInvoke 的错误与可用性边界，禁止 adapter 直接散落 tauriInvoke。 */
  invoke: <T>(command: string, args?: Record<string, unknown>): Promise<T> =>
    invokeNativeCommand(command, args, () => tauriInvoke<T>(command, args)),
  /** 只在 bridge 边界拆除 Tauri Event envelope；payload 仍保持泛型直到专用 Schema 解析。 */
  listen: async <T>(event: string, handler: (payload: T) => void) => {
    const unlisten = await tauriListen(event, (eventPayload) => {
      handler(eventPayload.payload as T);
    });
    return unlisten;
  },
};

/** 只有稳定且已脱敏的 Rust error code 可以对 React 可见。 */
const SAFE_RUNTIME_ERRORS: Record<string, { message: string; retryable: boolean }> = {
  RUNTIME_CONFIG_INVALID: { message: "运行时配置不可用", retryable: false },
  INVALID_PARAMS: { message: "运行时请求参数无效", retryable: false },
  RUNTIME_UNAVAILABLE: { message: "运行时暂不可用", retryable: true },
  PROFILE_UNAVAILABLE: { message: "未配置可用模型", retryable: false },
  CONFIG_INVALID: { message: "配置文件需要修复", retryable: false },
  CONFIG_MISSING: { message: "尚未配置模型", retryable: false },
  CREDENTIAL_UNAVAILABLE: { message: "模型凭据不可用", retryable: false },
  CONFIG_VERSION_CONFLICT: { message: "配置已被其他窗口修改，请重新读取后重试", retryable: true },
  RUNTIME_QUEUE_FULL: { message: "运行时队列已满，请稍后重试", retryable: true },
  RUNTIME_COMMAND_DEADLINE: { message: "运行时请求超时", retryable: true },
  RUNTIME_SHUTDOWN_TIMEOUT: { message: "运行时未能在期限内停止", retryable: true },
  RUNTIME_EVENT_DELIVERY_FAILED: { message: "运行时事件通道不可用", retryable: true },
  RECOVERY_REQUIRED: { message: "需要先完成运行时恢复", retryable: false },
  RECOVERY_STALE: { message: "恢复状态已变化，请重新读取", retryable: true },
  SENSITIVE_EVENT_BLOCKED: { message: "运行时事件包含受保护数据", retryable: false },
  PROTOCOL_INCOMPATIBLE: { message: "运行时协议不兼容", retryable: false },
  RUNTIME_FAULTED: { message: "运行时已故障", retryable: false },
  RUNTIME_BACKOFF: { message: "运行时正在退避", retryable: true },
  SHUTTING_DOWN: { message: "运行时正在关闭", retryable: true },
  RUNTIME_NOT_READY: { message: "运行时未就绪", retryable: true },
  RUNTIME_TIMEOUT: { message: "运行时超时", retryable: true },
  SIDECAR_CRASHED: { message: "运行时进程已退出", retryable: true },
  RUNTIME_PROTOCOL_ERROR: { message: "运行时协议错误", retryable: false },
  APPROVAL_ALREADY_RESOLVED: { message: "审批已处理", retryable: false },
  APPROVAL_NOT_FOUND: { message: "审批不存在", retryable: false },
  THREAD_BUSY: { message: "对话正在执行", retryable: true },
  THREAD_NOT_FOUND: { message: "对话不存在或已删除", retryable: false },
  CONFLICT: { message: "对话状态已变化，请刷新后重试", retryable: true },
  TOKEN_COUNT_UNAVAILABLE: { message: "暂时无法精确计算上下文 Token", retryable: true },
  SUMMARY_FAILURE: { message: "上下文摘要生成失败", retryable: true },
  CONTEXT_LIMIT: { message: "当前上下文无法安全压缩到模型窗口内", retryable: false },
  INVALID_STATE: { message: "对话上下文状态异常，请重新打开会话", retryable: false },
  NOT_CONFIGURED: { message: "工作区尚未配置", retryable: true },
  UNKNOWN_WORKSPACE: { message: "工作区不存在", retryable: false },
  INVALID_INPUT: { message: "请求参数无效", retryable: false },
  INVALID_PATH: { message: "路径无效", retryable: false },
  PATH_REJECTED: { message: "路径不在工作区内", retryable: false },
  NOT_FOUND: { message: "文件或目录不存在", retryable: false },
  NOT_DIRECTORY: { message: "目标不是目录", retryable: false },
  NOT_FILE: { message: "目标不是文件", retryable: false },
  STALE_CURSOR: { message: "文件树游标已失效", retryable: true },
  LIMIT_EXCEEDED: { message: "请求超出限制", retryable: false },
  CHANGED_DURING_READ: { message: "文件读取期间发生变化", retryable: true },
  WORKSPACE_RECOVERY_REQUIRED: { message: "工作区需要先完成恢复才能继续操作", retryable: false },
  ALREADY_EXISTS: { message: "文件或目录已存在", retryable: false },
  REVISION_CONFLICT: { message: "文件已被其他修改，请重新载入后再保存", retryable: false },
  MUTATION_ALREADY_USED: { message: "该文件操作已处理，请刷新后重试", retryable: false },
  INVALID_MUTATION_ID: { message: "文件操作标识无效", retryable: false },
  UNSUPPORTED_CONTENT: { message: "文件内容不支持写入", retryable: false },
  TRASH_TOKEN_INVALID: { message: "回收站操作已失效，请重新确认", retryable: false },
  TRASH_TOKEN_EXPIRED: { message: "回收站确认已过期，请重新确认", retryable: false },
  RECYCLE_UNAVAILABLE: { message: "当前磁盘未启用系统回收站，文件没有被删除", retryable: false },
  DROP_TOKEN_INVALID: { message: "拖入授权无效或已过期，请重新拖入", retryable: false },
  WATCH_UNAVAILABLE: { message: "文件监视暂不可用，请刷新工作区", retryable: true },
  IO: { message: "工作区操作失败", retryable: true },
  EXTERNAL_WORKTREE: { message: "Git 工作树不受支持", retryable: false },
  GIT_UNAVAILABLE: { message: "Git 不可用", retryable: true },
  COMMAND_FAILED: { message: "Git 命令执行失败", retryable: true },
  TIMED_OUT: { message: "Git 命令超时", retryable: true },
  CANCELLED: { message: "Git 命令已取消", retryable: true },
  OUTPUT_LIMIT_EXCEEDED: { message: "Git 输出超出限制", retryable: false },
  CLEANUP_TIMED_OUT: { message: "Git 进程清理超时", retryable: true },
  PARSE: { message: "Git 输出解析失败", retryable: false },
};

export class RuntimeHostError extends Error {
  readonly code: string;
  readonly retryable: boolean;

  /** 错误实例只保存 allow-list code、固定文案和重试语义，不保留原始 cause 或跨进程 payload。 */
  constructor(code: string, message: string, retryable: boolean) {
    super(message);
    this.name = "RuntimeHostError";
    this.code = code;
    this.retryable = retryable;
  }
}

/**
 * 将 typed command 输入故障转换为稳定本地错误；直接返回 ZodError 会通过 issue
 * 列表暴露用户路径片段，而 Runtime UI 不需要这些细节。
 */
function parseRuntimeInput<T>(schema: z.ZodType<T>, input: unknown): T {
  try {
    return schema.parse(input);
  } catch {
    throw new RuntimeHostError("INVALID_INPUT", "请求参数无效", false);
  }
}

/**
 * 将 invoke rejection 规范化为刻意精简的公开错误；Java/Rust 路径、stack trace、
 * token 与子进程诊断不能进入 React 状态，但 shell 所需的重试判断仍保留。
 */
export function normalizeRuntimeError(error: unknown): RuntimeHostError {
  if (error instanceof RuntimeHostError) {
    const safeKnown = SAFE_RUNTIME_ERRORS[error.code];
    return safeKnown === undefined
      ? new RuntimeHostError(
          "RUNTIME_UNAVAILABLE",
          SAFE_RUNTIME_ERRORS["RUNTIME_UNAVAILABLE"]?.message ?? "运行时暂不可用",
          true,
        )
      : new RuntimeHostError(error.code, safeKnown.message, safeKnown.retryable);
  }
  const candidate =
    error !== null && typeof error === "object" ? (error as Record<string, unknown>) : undefined;
  const code = typeof candidate?.["code"] === "string" ? candidate["code"] : undefined;
  const safe = code === undefined ? undefined : SAFE_RUNTIME_ERRORS[code];
  if (code !== undefined && safe !== undefined) {
    return new RuntimeHostError(code, safe.message, safe.retryable);
  }
  return new RuntimeHostError(
    "RUNTIME_UNAVAILABLE",
    SAFE_RUNTIME_ERRORS["RUNTIME_UNAVAILABLE"]?.message ?? "运行时暂不可用",
    true,
  );
}

const SENSITIVE_KEY_PATTERN = /(?:^|[_-])(path|cwd|stack|cause|token|secret|credential)(?:$|[_-])/i;
const SAFE_TOKEN_METRIC_KEYS = new Set([
  "estimatedTokens",
  "inputTokens",
  "outputTokens",
  "totalTokens",
  "usageInputTokens",
  "usageOutputTokens",
  "usageTotalTokens",
  "inputTokensBefore",
  "inputTokensAfter",
]);
const NULLABLE_TOKEN_METRIC_KEYS = new Set(["inputTokensBefore", "inputTokensAfter"]);

/**
 * pre-Zod 安全遍历只允许 Schema 拥有的 Token 指标；Context started/failed 的未产生指标
 * 显式为 null，其余值仍要求安全整数，防止相似字符串字段绕过敏感键策略。
 */
function isSafeTokenMetric(key: string, value: unknown): boolean {
  return (
    SAFE_TOKEN_METRIC_KEYS.has(key) &&
    ((typeof value === "number" && Number.isSafeInteger(value) && value >= 0) ||
      (value === null && NULLABLE_TOKEN_METRIC_KEYS.has(key)))
  );
}

/** 只允许不透明 MCP credential reference；Secret 材料仍只存在于原生层。 */
function isSafeCredentialReference(key: string, value: unknown): boolean {
  return (
    key === "credentialRef" &&
    typeof value === "string" &&
    /^cred_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/.test(value)
  );
}

type RuntimeTimelineEvent = JaEvent;

/** Java 只在 ToolPresentation 中发布工作区相对路径；其它 path/cwd 字段仍应拒绝。 */
function isSafePresentationLocation(key: string, value: unknown, path: readonly string[]): boolean {
  if (!path.includes("presentation")) return false;
  if (key === "relativeCwd") return typeof value === "string";
  return key === "relativePaths" && Array.isArray(value);
}

/**
 * 投影前在每个嵌套层级拒绝敏感诊断键。Java 已将 Tool 数据收敛为安全 presentation，
 * Renderer 不再接收需要二次删除的 raw arguments 或 result。
 */
function assertHostPayloadSafe(value: unknown): void {
  try {
    assertSafePayload(value);
  } catch (error) {
    if (error instanceof RuntimeHostError) {
      throw error;
    }
    throw new RuntimeHostError(
      "SENSITIVE_EVENT_BLOCKED",
      SAFE_RUNTIME_ERRORS["SENSITIVE_EVENT_BLOCKED"]?.message ?? "运行时事件包含受保护数据",
      false,
    );
  }
  const root =
    value !== null && typeof value === "object" ? (value as Record<string, unknown>) : undefined;
  const rootParams = root?.["params"];
  const allowsReadyEcho =
    root?.["method"] === "runtime/status-changed" &&
    rootParams !== null &&
    typeof rootParams === "object" &&
    (rootParams as Record<string, unknown>)["status"] === "ready";
  const visit = (current: unknown, seen = new WeakSet<object>(), path: string[] = []): void => {
    if (current === null || typeof current !== "object") {
      return;
    }
    if (seen.has(current)) {
      return;
    }
    seen.add(current);
    for (const [key, child] of Object.entries(current)) {
      const childPath = [...path, key];
      const legalReadyEcho =
        allowsReadyEcho &&
        key === "readyToken" &&
        childPath.length === 2 &&
        childPath[0] === "params" &&
        typeof child === "string" &&
        READY_TOKEN_PATTERN.test(child);
      if (
        !isSafeTokenMetric(key, child) &&
        !isSafeCredentialReference(key, child) &&
        !legalReadyEcho &&
        !isSafePresentationLocation(key, child, path) &&
        (SENSITIVE_KEY_PATTERN.test(key) ||
          /(?:path|cwd|stack|cause|token|secret|credential)/i.test(key))
      ) {
        throw new RuntimeHostError(
          "SENSITIVE_EVENT_BLOCKED",
          SAFE_RUNTIME_ERRORS["SENSITIVE_EVENT_BLOCKED"]?.message ?? "运行时事件包含受保护数据",
          false,
        );
      }
      visit(child, seen, childPath);
    }
    seen.delete(current);
  };
  visit(value);
}

/**
 * 在 IPC 边缘统一解析 host 事件，使 store 与组件只看到可信 DTO。
 * Rust 已消费私有 ready challenge，因此 renderer 只校验公开 host 生命周期投影。
 */
export function parseRuntimeHostEvent(value: unknown): RuntimeHostEvent {
  const root =
    value !== null && typeof value === "object" ? (value as Record<string, unknown>) : undefined;
  assertHostPayloadSafe(value);
  if (root?.["method"] === "runtime/status-changed") {
    const parsed = RuntimeStatusEventSchema.parse(value);
    const params = parsed.params;
    return {
      kind: "status",
      status: {
        status: normalizeWireStatus(params.status),
        generation: params.generation ?? 0,
        serverInstanceId: params.serverInstanceId,
      },
      eventId: params.eventId,
      occurredAt: params.occurredAt,
      ...(safeRuntimeReason(params.reason) === undefined
        ? {}
        : { reason: safeRuntimeReason(params.reason) }),
    };
  }
  return {
    kind: "timeline",
    event: parseEvent(value),
  };
}

export interface RuntimeHostAdapter {
  /** 启动由 Rust 拥有的唯一 RuntimeHost，不接受 renderer 配置快照。 */
  start(): Promise<RuntimeStatus>;
  /** 停止当前 generation，进程树与恢复 marker 仍由 Rust 处理。 */
  stop(): Promise<RuntimeStatus>;
  /** 读取脱敏生命周期投影，不查询或推断 sidecar 内部状态。 */
  state(): Promise<RuntimeStatus>;
  /** 读取原生层拥有的 storage 投影，不暴露进程细节。 */
  storageInfo(): Promise<RuntimeStorageInfo>;
  /** 返回原生层拥有且供无项目 thread 使用的固定 workspace。 */
  generalWorkspace(): Promise<GeneralWorkspace>;
  /** 读取恢复门状态；未确认时其他启动操作必须继续 fail closed。 */
  recoveryState(): Promise<RuntimeRecoveryState>;
  /** 使用 recovery identity 与 revision CAS 确认人工恢复，拒绝陈旧点击。 */
  acknowledgeRecovery(confirmation: ManualRecoveryConfirmation): Promise<RuntimeRecoveryState>;
  /** 通过普通客户端请求发送业务 approval decision，不建立控制面旁路。 */
  approvalRespond(input: ApprovalResponseInput): Promise<void>;
  /** 发送一次已校验 Turn 输入，Provider/Model 与权限快照由 Ja App Server 冻结。 */
  turnStart(input: TurnStartInput): Promise<TurnAccepted>;
  /** 请求取消，但终态仍以事件为权威，UI 不提前猜测完成。 */
  turnCancel(input: TurnCancelInput): Promise<TurnCancelResult>;
  /** 为活动 Turn 的下一个 Tool 边界排队 guidance。 */
  turnSteer(input: TurnQueuedInput): Promise<TurnQueuedInputResult>;
  /** 为活动 Turn 的完成边界排队 message。 */
  turnFollowUp(input: TurnQueuedInput): Promise<TurnQueuedInputResult>;
  /** 查询固定 Skills/MCP settings surface，不公开通用 method tunnel。 */
  query: RuntimeQuery;
  /** 订阅唯一 RuntimeHost 事件并只发布解析后的领域投影。 */
  subscribe(listener: RuntimeHostListener): Promise<RuntimeHostUnsubscribe>;
}

/**
 * 固定原生 command 与单一原生事件的 typed adapter；调用方不能选择 executable、
 * 路径、环境、request id 或 handshake token。
 */
export class TauriRuntimeHostAdapter implements RuntimeHostAdapter {
  /** bridge 注入仅用于合同测试；生产 adapter 固定连接 RuntimeHost 的 command/event allow-list。 */
  constructor(private readonly bridge: RuntimeNativeBridge = defaultNativeBridge) {}

  /** 启动不接收 WebView 配置，确保 executable、环境和 workspace identity 仍由 Rust owner 决定。 */
  async start(): Promise<RuntimeStatus> {
    // 输入校验阶段固定为空对象，renderer 无法向启动链路夹带配置或路径。
    // 跨进程与结果解析、错误脱敏统一由 invoke 完成，避免 start 形成第二套处理分支。
    return this.invoke(JA_RUNTIME_COMMANDS.start, {}, RuntimeStatusSchema);
  }

  /** 停止只表达生命周期意图；清理进程树、超时与 recovery marker 均由 Rust 负责。 */
  async stop(): Promise<RuntimeStatus> {
    // 固定空输入后调用唯一 stop command；返回状态必须通过同一 RuntimeStatus Schema。
    return this.invoke(JA_RUNTIME_COMMANDS.stop, {}, RuntimeStatusSchema);
  }

  /** 读取 Host 权威状态，不依据最后一个事件在 renderer 推断 generation。 */
  async state(): Promise<RuntimeStatus> {
    // state 同样使用空输入与公共 invoke，畸形状态和原生诊断不会进入 React。
    return this.invoke(JA_RUNTIME_COMMANDS.state, {}, RuntimeStatusSchema);
  }

  /** 只读取原生 host 的稳定 storage 投影；executable 与环境细节永远不穿过 IPC 边界。 */
  async storageInfo(): Promise<RuntimeStorageInfo> {
    try {
      // 这个 typed command 刻意返回用户可见的本地数据路径；只有 Rust 已投影固定
      // run directory 并移除 executable、argument、环境与凭据数据后，才可绕过
      // 通用 event/error path-key filter。
      const result = await this.bridge.invoke<unknown>(JA_RUNTIME_COMMANDS.storageInfo, {});
      return RuntimeStorageInfoSchema.parse(result);
    } catch (error) {
      if (error instanceof z.ZodError) {
        throw new RuntimeHostError(
          "RUNTIME_UNAVAILABLE",
          SAFE_RUNTIME_ERRORS["RUNTIME_UNAVAILABLE"]?.message ?? "运行时暂不可用",
          true,
        );
      }
      throw normalizeRuntimeError(error);
    }
  }

  /**
   * 请求固定通用 workspace 且不接受 WebView 路径；显式 Schema 只允许预期的
   * 原生层拥有 root 投影。
   */
  async generalWorkspace(): Promise<GeneralWorkspace> {
    try {
      const result = await this.bridge.invoke<unknown>(JA_RUNTIME_COMMANDS.generalWorkspace, {});
      return GeneralWorkspaceSchema.parse(result);
    } catch (error) {
      if (error instanceof z.ZodError) {
        throw new RuntimeHostError(
          "RUNTIME_UNAVAILABLE",
          SAFE_RUNTIME_ERRORS["RUNTIME_UNAVAILABLE"]?.message ?? "运行时暂不可用",
          true,
        );
      }
      throw normalizeRuntimeError(error);
    }
  }

  /** 读取当前恢复门投影；固定 command 保证 renderer 不能自行检查或修改 recovery 文件。 */
  async recoveryState(): Promise<RuntimeRecoveryState> {
    // 无输入查询只跨进程读取权威 marker 投影，并由 invoke 校验严格结果形状。
    return this.invoke(JA_RUNTIME_COMMANDS.recoveryState, {}, RuntimeRecoveryStateSchema);
  }

  /** 使用 recoveryId/revision CAS 提交人工确认，避免另一个窗口状态变化后继续清理。 */
  async acknowledgeRecovery(
    confirmation: ManualRecoveryConfirmation,
  ): Promise<RuntimeRecoveryState> {
    // 校验阶段先收紧 reason、identity 与 revision，非法值不得触达 Rust 文件恢复边界。
    const parsed = parseRuntimeInput(ManualRecoveryConfirmationSchema, confirmation);
    // 跨进程与结果阶段由 invoke 统一完成，冲突与原生错误只返回稳定公开分类。
    return this.invoke(
      JA_RUNTIME_COMMANDS.acknowledgeRecovery,
      { confirmation: parsed },
      RuntimeRecoveryStateSchema,
    );
  }

  /**
   * 通过专用原生 command 发送公开 approval identity 与 revision CAS。
   * Rust 发起普通 `approval/respond` 客户端请求，WebView 不接收也不重建通用协议 envelope。
   */
  async approvalRespond(input: ApprovalResponseInput): Promise<void> {
    // 校验阶段固定 approval/turn identity、decision 与 revision CAS，禁止附加通用 RPC 字段。
    const parsed = parseRuntimeInput(ApprovalResponseInputSchema, input);
    try {
      // 跨进程阶段只调用专用 command；Rust 再以普通 JA-RPC request 转发到 App Server。
      const result = await this.bridge.invoke<unknown>(JA_RUNTIME_COMMANDS.approvalRespond, {
        input: parsed,
      });
      // 解析阶段先做敏感字段扫描，再要求 void 结果；任意额外 payload 都 fail closed。
      assertHostPayloadSafe(result);
      if (result !== null && result !== undefined) {
        throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
      }
    } catch (error) {
      throw normalizeRuntimeError(error);
    }
  }

  /** Turn admission 只提交 thread 与文本输入；Provider/Model、权限、预算和 generation 由服务端冻结。 */
  async turnStart(input: TurnStartInput): Promise<TurnAccepted> {
    // 校验阶段拒绝未知字段、畸形 thread id、空输入和无界 deadline。
    const parsed = parseRuntimeInput(TurnStartInputSchema, input);
    // 跨进程和结果阶段复用公共 invoke，accepted identity 与 revision 必须满足严格 Schema。
    return this.invoke(JA_RUNTIME_COMMANDS.turnStart, { input: parsed }, TurnAcceptedSchema);
  }

  /**
   * 通过 Rust 有界 bridge 请求取消，使 sidecar 发出权威终态事件，
   * 而不是由 UI 猜测状态。
   */
  async turnCancel(input: TurnCancelInput): Promise<TurnCancelResult> {
    // 校验阶段固定 turn identity 与 revision CAS，陈旧 UI 不能取消更新后的 Turn。
    const parsed = parseRuntimeInput(TurnCancelInputSchema, input);
    // 跨进程阶段使用专用 cancel command；终态仍由后续 Runtime 事件确认。
    const result = await this.invoke(
      JA_RUNTIME_COMMANDS.turnCancel,
      { input: parsed },
      TurnCancelResultSchema,
    );
    // 关联阶段要求返回同一 turnId，防止合法形状但错误关联的响应进入状态层。
    if (result.turnId !== parsed.turnId) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
    }
    return result;
  }

  /** 通过专用原生 command 排队即时 guidance，并校验返回身份属于请求的 Turn。 */
  async turnSteer(input: TurnQueuedInput): Promise<TurnQueuedInputResult> {
    return this.queueTurnInput(JA_RUNTIME_COMMANDS.turnSteer, "steering", input);
  }

  /** 为完成边界排队 message，但不向 WebView 暴露队列管理 API 或通用 method name。 */
  async turnFollowUp(input: TurnQueuedInput): Promise<TurnQueuedInputResult> {
    return this.queueTurnInput(JA_RUNTIME_COMMANDS.turnFollowUp, "follow_up", input);
  }

  /** 共用严格校验，同时保持封闭 command/kind 对。 */
  private async queueTurnInput(
    command: typeof JA_RUNTIME_COMMANDS.turnSteer | typeof JA_RUNTIME_COMMANDS.turnFollowUp,
    expectedKind: "steering" | "follow_up",
    input: TurnQueuedInput,
  ): Promise<TurnQueuedInputResult> {
    // 校验阶段只允许当前 Turn identity 与有界文本，不能携带第二份 thread/Provider/Model 上下文。
    const parsed = parseRuntimeInput(TurnQueuedInputSchema, input);
    // 跨进程阶段 command 与 expected kind 由 adapter 闭集决定，调用方不能选择通用 method。
    const result = await this.invoke(command, { input: parsed }, TurnQueuedInputResultSchema);
    // 解析后的关联校验同时锁定 turnId 与 steering/follow-up kind，错配一律 fail closed。
    if (result.turnId !== parsed.turnId || result.kind !== expectedKind) {
      throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "运行时暂不可用", true);
    }
    return result;
  }

  /**
   * 通过 Rust 发送 allow-list Settings 查询，并在 WebView 边界校验方法专用输入与结果。
   * sidecar 请求始终是产品能力，不能成为调用方选择的 RPC path。
   */
  async query<M extends RuntimeSettingsMethod>(
    method: M,
    params: RuntimeSettingsParams<M>,
  ): Promise<RuntimeSettingsResult<M>> {
    // 校验阶段按具体 allow-list method 解析 params，application 无法借此访问通用 JA-RPC surface。
    let parsedParams: RuntimeSettingsParams<M>;
    try {
      parsedParams = parseMethodParams(method, params) as RuntimeSettingsParams<M>;
    } catch {
      throw new RuntimeHostError("INVALID_INPUT", "请求参数无效", false);
    }
    try {
      // 跨进程阶段只调用统一 query command，method/params 被封装在固定 input 字段中。
      const result = await this.bridge.invoke<unknown>(JA_RUNTIME_COMMANDS.query, {
        input: { method, params: parsedParams },
      });
      // 结果阶段先拒绝敏感字段，再按原始 method 解析专用 result Schema。
      assertHostPayloadSafe(result);
      return parseMethodResult(method, result) as RuntimeSettingsResult<M>;
    } catch (error) {
      if (error instanceof RuntimeHostError) {
        throw normalizeRuntimeError(error);
      }
      if (error instanceof z.ZodError) {
        throw new RuntimeHostError(
          "RUNTIME_UNAVAILABLE",
          SAFE_RUNTIME_ERRORS["RUNTIME_UNAVAILABLE"]?.message ?? "运行时暂不可用",
          true,
        );
      }
      throw normalizeRuntimeError(error);
    }
  }

  /**
   * 将非法原生帧转换为不含 payload 的恢复信号；raw frame 隔离在 IPC 边缘，
   * React 可重新请求权威 snapshot，而不是让活动 Turn 永久锁定。
   */
  async subscribe(listener: RuntimeHostListener): Promise<RuntimeHostUnsubscribe> {
    try {
      // 订阅阶段只绑定唯一事件名；raw payload 不会从此回调边界直接交给 application。
      return await this.bridge.listen<unknown>(JA_RUNTIME_EVENTS.frame, (payload) => {
        let event: RuntimeHostEvent;
        try {
          // 解析阶段执行敏感字段扫描、生命周期 Schema 与业务事件 Schema。
          event = parseRuntimeHostEvent(payload);
        } catch {
          // 畸形帧仅降级为无 payload 的恢复信号，避免路径或 Secret 进入 observer。
          listener({ kind: "projection_fault", reason: "invalid_native_event" });
          return;
        }
        listener(event);
      });
    } catch (error) {
      // 订阅建立失败使用同一 allow-list 错误映射，不传播 Tauri/系统原始异常。
      throw normalizeRuntimeError(error);
    }
  }

  /** 返回 DTO 穿过 adapter 边界前完成校验，畸形原生状态不能进入 React。 */
  private async invoke<T>(
    command: string,
    args: Record<string, unknown>,
    schema: z.ZodType<T>,
  ): Promise<T> {
    try {
      // 跨进程阶段只使用调用方法选定的固定 command/args，bridge rejection 在下方统一脱敏。
      const result = await this.bridge.invoke<unknown>(command, args);
      // 解析阶段先递归拒绝敏感字段，再执行方法专用严格 Schema。
      assertHostPayloadSafe(result);
      return schema.parse(result);
    } catch (error) {
      if (error instanceof RuntimeHostError) {
        throw normalizeRuntimeError(error);
      }
      if (error instanceof z.ZodError) {
        throw new RuntimeHostError(
          "RUNTIME_UNAVAILABLE",
          SAFE_RUNTIME_ERRORS["RUNTIME_UNAVAILABLE"]?.message ?? "运行时暂不可用",
          true,
        );
      }
      throw normalizeRuntimeError(error);
    }
  }
}

/** 组合根只取得窄 RuntimeHost Port，不能访问 bridge 或构造任意 Tauri command。 */
export function createRuntimeHostAdapter(): RuntimeHostAdapter {
  return new TauriRuntimeHostAdapter();
}
