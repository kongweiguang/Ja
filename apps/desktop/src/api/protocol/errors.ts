// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { RpcErrorSchema, type RpcError } from "./protocol";
import { containsTokenShapedText } from "./readyToken";

/** JA-RPC v1 的错误分类闭集；展示消息永远不参与分类。 */
export type ErrorCategory =
  | "protocol"
  | "validation"
  | "conflict"
  | "not_found"
  | "permission"
  | "capacity"
  | "unavailable"
  | "timeout"
  | "cancelled"
  | "internal";

/** 单一 typed 目录同时派生数值码、分类与重试策略，避免三个表之间漂移。 */
export const JA_ERROR_CATALOG = {
  INVALID_FRAME: { code: -32001, category: "protocol", retryable: false },
  FRAME_TOO_LARGE: { code: -32002, category: "capacity", retryable: false },
  PROTOCOL_VERSION_UNSUPPORTED: { code: -32003, category: "protocol", retryable: false },
  NOT_INITIALIZED: { code: -32004, category: "conflict", retryable: false },
  ALREADY_INITIALIZED: { code: -32005, category: "conflict", retryable: false },
  METHOD_NOT_FOUND: { code: -32006, category: "not_found", retryable: false },
  INVALID_PARAMS: { code: -32007, category: "validation", retryable: false },
  QUEUE_FULL: { code: -32008, category: "capacity", retryable: true },
  THREAD_QUEUE_FULL: { code: -32009, category: "capacity", retryable: true },
  CONFIG_INVALID: { code: -32010, category: "validation", retryable: false },
  CONFIG_CONFLICT: { code: -32011, category: "conflict", retryable: true },
  STORAGE_UNAVAILABLE: { code: -32012, category: "unavailable", retryable: true },
  CONFIG_CORRUPTED: { code: -32013, category: "validation", retryable: false },
  REQUEST_DEADLINE_EXCEEDED: { code: -32014, category: "timeout", retryable: true },
  CREDENTIAL_MISSING: { code: -32015, category: "not_found", retryable: false },
  RECOVERY_REQUIRED: { code: -32016, category: "conflict", retryable: false },
  PROVIDER_OR_MODEL_NOT_FOUND: { code: -32017, category: "not_found", retryable: false },
  WORKSPACE_TRUST_REQUIRED: { code: -32018, category: "permission", retryable: false },
  STORAGE_CONFLICT: { code: -32019, category: "conflict", retryable: false },
  SHUTTING_DOWN: { code: -32020, category: "unavailable", retryable: true },
  DATA_DIR_IN_USE: { code: -32021, category: "conflict", retryable: false },
  SCHEMA_MISMATCH: { code: -32024, category: "conflict", retryable: false },
  WORKSPACE_NOT_FOUND: { code: -32025, category: "not_found", retryable: false },
  WORKSPACE_CONFINEMENT: { code: -32026, category: "permission", retryable: false },
  CONFLICT: { code: -32028, category: "conflict", retryable: true },
  THREAD_NOT_FOUND: { code: -32029, category: "not_found", retryable: false },
  THREAD_BUSY: { code: -32030, category: "conflict", retryable: true },
  TURN_NOT_FOUND: { code: -32032, category: "not_found", retryable: false },
  INVALID_STATE: { code: -32034, category: "conflict", retryable: false },
  CANCELLED: { code: -32035, category: "cancelled", retryable: false },
  BUDGET_EXCEEDED: { code: -32036, category: "capacity", retryable: false },
  APPROVAL_NOT_FOUND: { code: -32040, category: "not_found", retryable: false },
  APPROVAL_EXPIRED: { code: -32041, category: "timeout", retryable: false },
  APPROVAL_ALREADY_RESOLVED: { code: -32042, category: "conflict", retryable: false },
  TOOL_DENIED: { code: -32043, category: "permission", retryable: false },
  TOOL_FAILED: { code: -32044, category: "validation", retryable: false },
  TOOL_OUTCOME_UNKNOWN: { code: -32045, category: "internal", retryable: false },
  PROCESS_TIMEOUT: { code: -32046, category: "timeout", retryable: false },
  PROCESS_OUTPUT_LIMIT: { code: -32047, category: "capacity", retryable: false },
  SUMMARY_FAILURE: { code: -32049, category: "unavailable", retryable: true },
  MODEL_PROTOCOL_ERROR: { code: -32050, category: "protocol", retryable: false },
  CONTEXT_LIMIT: { code: -32051, category: "capacity", retryable: false },
  MODEL_UNSUPPORTED: { code: -32052, category: "validation", retryable: false },
  MODEL_UNAVAILABLE: { code: -32053, category: "unavailable", retryable: true },
  SKILL_INVALID: { code: -32054, category: "validation", retryable: false },
  SKILL_UNAVAILABLE: { code: -32055, category: "unavailable", retryable: true },
  MCP_UNSUPPORTED: { code: -32056, category: "validation", retryable: false },
  MCP_SERVER_UNAVAILABLE: { code: -32057, category: "unavailable", retryable: true },
  MCP_TOOL_NOT_FOUND: { code: -32059, category: "not_found", retryable: false },
  MCP_TOOL_FAILED: { code: -32060, category: "validation", retryable: false },
  ATTACHMENT_NOT_FOUND: { code: -32061, category: "not_found", retryable: false },
  ATTACHMENT_LIMIT_EXCEEDED: { code: -32062, category: "capacity", retryable: false },
  ATTACHMENT_CONFLICT: { code: -32063, category: "conflict", retryable: false },
  ATTACHMENT_UNAVAILABLE: { code: -32064, category: "unavailable", retryable: true },
  TURN_NOT_RESUMABLE: { code: -32065, category: "conflict", retryable: false },
  TURN_RESUME_ORDER_CONFLICT: { code: -32066, category: "conflict", retryable: true },
  TURN_INPUT_QUEUE_FULL: { code: -32068, category: "capacity", retryable: true },
  QUEUED_INPUT_NOT_FOUND: { code: -32069, category: "not_found", retryable: false },
  WORKSPACE_REFERENCE_INVALID: { code: -32070, category: "validation", retryable: false },
  SKILL_LOAD_FAILED: { code: -32071, category: "unavailable", retryable: true },
  CONTENT_TOO_LARGE: { code: -32072, category: "capacity", retryable: false },
  TASK_NOT_FOUND: { code: -32073, category: "not_found", retryable: false },
  TASK_RELATION_INVALID: { code: -32074, category: "validation", retryable: false },
  TASK_CONTEXT_REVISION_CONFLICT: { code: -32075, category: "conflict", retryable: true },
  TASK_PERMISSION_DENIED: { code: -32076, category: "permission", retryable: false },
  TASK_DEPTH_LIMIT: { code: -32077, category: "capacity", retryable: false },
  TASK_TREE_LIMIT: { code: -32078, category: "capacity", retryable: false },
  TASK_MAILBOX_FULL: { code: -32079, category: "capacity", retryable: true },
  INTERNAL_ERROR: { code: -32080, category: "internal", retryable: false },
  SIDECAR_CRASHED: { code: -32081, category: "unavailable", retryable: false },
  SHUTDOWN_TIMEOUT: { code: -32082, category: "timeout", retryable: false },
  TASK_TREE_DELETE_REQUIRED: { code: -32083, category: "conflict", retryable: false },
  TASK_OBSERVATION_INVALID: { code: -32084, category: "not_found", retryable: false },
  WORKSPACE_WRITE_LEASE_TIMEOUT: { code: -32085, category: "timeout", retryable: true },
  GOAL_NOT_FOUND: { code: -32086, category: "not_found", retryable: false },
  GOAL_REVISION_CONFLICT: { code: -32087, category: "conflict", retryable: true },
  GOAL_INVALID_STATE: { code: -32088, category: "conflict", retryable: false },
  PLAN_INVALID: { code: -32089, category: "validation", retryable: false },
  PLAN_APPROVAL_STALE: { code: -32090, category: "conflict", retryable: false },
  GOAL_EVIDENCE_INCOMPLETE: { code: -32091, category: "conflict", retryable: false },
  GOAL_RECOVERY_REQUIRED: { code: -32092, category: "conflict", retryable: false },
  GOAL_INPUT_EXPIRED: { code: -32093, category: "timeout", retryable: false },
} as const satisfies Record<string, { code: number; category: ErrorCategory; retryable: boolean }>;

type CatalogErrorCode = keyof typeof JA_ERROR_CATALOG;
type CatalogEntry = {
  errorCode: CatalogErrorCode;
  code: number;
  category: ErrorCategory;
  retryable: boolean;
};

/** 保留既有数值码查询入口，但值只从单一 typed 目录派生。 */
export const JA_ERROR_CODES = Object.freeze(
  Object.fromEntries(
    Object.entries(JA_ERROR_CATALOG).map(([errorCode, entry]) => [errorCode, entry.code]),
  ),
) as Readonly<{ [K in CatalogErrorCode]: (typeof JA_ERROR_CATALOG)[K]["code"] }>;

const CATALOG = new Map<number, CatalogEntry>(
  Object.entries(JA_ERROR_CATALOG).map(([errorCode, entry]) => [
    entry.code,
    { errorCode: errorCode as CatalogErrorCode, ...entry },
  ]),
);

/** 本地传输与校验错误使用相同属性名，但不会冒充服务端目录项。 */
export type ErrorCode = CatalogErrorCode | "UNKNOWN_ERROR" | "TRANSPORT_ERROR" | "VALIDATION_ERROR";

const ABSOLUTE_PATH_PATTERN =
  /(?:[A-Za-z]:[\\/]|\\\\|file:\/\/|(?:^|[\s("'`=,:;])\/(?:[^/\s"'`]+(?:[\\/][^/\s"'`]+)*))/iu;
const URI_PATTERN = /\b[a-z][a-z\d+.-]*:\/\//iu;

/** 提供不保留原始 cause、Provider payload 或主机细节的安全错误对象。 */
export class JaError extends Error {
  readonly code: number;
  readonly errorCode: ErrorCode;
  readonly category: ErrorCategory;
  readonly retryable: boolean;
  readonly errorId?: string;
  readonly field?: string;
  readonly retryAfterMs?: number;
  readonly details?: Record<string, unknown>;

  /** 固定公开错误属性并收紧可选退避边界，避免无效值被静默改写成 0。 */
  constructor(
    message: string,
    options: {
      code?: number;
      errorCode?: ErrorCode;
      category?: ErrorCategory;
      retryable?: boolean;
      errorId?: string;
      field?: string;
      retryAfterMs?: number;
      details?: Record<string, unknown>;
    } = {},
  ) {
    super(sanitizeMessage(message));
    this.name = "JaError";
    this.code =
      typeof options.code === "number" && Number.isSafeInteger(options.code)
        ? options.code
        : JA_ERROR_CODES.INTERNAL_ERROR;
    this.errorCode = isErrorCode(options.errorCode) ? options.errorCode : "UNKNOWN_ERROR";
    this.category = options.category ?? "internal";
    this.retryable = options.retryable === true;
    this.errorId = options.errorId?.match(/^err_[0-9a-f]{32}$/u)?.[0];
    this.field = safeField(options.field);
    this.retryAfterMs = validRetryAfter(options.retryAfterMs, this.retryable);
    this.details = options.details === undefined ? undefined : sanitizeDetails(options.details);
  }
}

/** 只映射目录批准的 Wire 错误；任一元组漂移都收敛为通用内部失败。 */
export function mapRpcError(value: unknown): JaError {
  const parsed = RpcErrorSchema.safeParse(value);
  if (!parsed.success) return safeInternalError();
  const catalog = CATALOG.get(parsed.data.code);
  if (
    catalog === undefined ||
    parsed.data.data.errorCode !== catalog.errorCode ||
    parsed.data.data.category !== catalog.category ||
    parsed.data.data.retryable !== catalog.retryable
  ) {
    return safeInternalError();
  }
  return fromRpcError(parsed.data, catalog);
}

/** 将已验证 Wire 错误转换为只包含稳定目录元数据的客户端错误。 */
function fromRpcError(error: RpcError, catalog: CatalogEntry): JaError {
  return new JaError(error.message, {
    code: error.code,
    errorCode: catalog.errorCode,
    category: catalog.category,
    retryable: catalog.retryable,
    retryAfterMs: error.data.retryAfterMs,
    errorId: error.data.errorId,
  });
}

/** 创建无法信任输入帧时使用的唯一安全 fallback。 */
function safeInternalError(): JaError {
  return new JaError("Ja sidecar returned an internal error", {
    code: JA_ERROR_CODES.INTERNAL_ERROR,
    errorCode: "INTERNAL_ERROR",
    category: "internal",
    retryable: false,
  });
}

/** 移除路径、URI authority 和 token 形状内容，避免展示或日志泄密。 */
function sanitizeMessage(value: unknown): string {
  const bounded = (typeof value === "string" ? value : "Ja sidecar returned an error").slice(
    0,
    512,
  );
  return containsTokenShapedText(bounded) ||
    ABSOLUTE_PATH_PATTERN.test(bounded) ||
    URI_PATTERN.test(bounded)
    ? "Ja sidecar returned an error"
    : bounded;
}

/** 将公开标识限制为有界且不含主机敏感内容的文本。 */
function safeField(value: string | undefined): string | undefined {
  if (value === undefined) return undefined;
  const bounded = value.slice(0, 256);
  return containsTokenShapedText(bounded) ||
    ABSOLUTE_PATH_PATTERN.test(bounded) ||
    URI_PATTERN.test(bounded)
    ? undefined
    : bounded;
}

/** 脱敏本地故障观察 details；Wire 错误不接受该扩展字段。 */
function sanitizeDetails(value: Record<string, unknown>): Record<string, unknown> | undefined {
  const output: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value).slice(0, 64)) {
    const safeKey =
      key.length > 128 ||
      /(?:token|secret|password|authorization|api.?key|path|private|cause|stack)/iu.test(key)
        ? "[redacted-key]"
        : key;
    output[safeKey] =
      typeof child === "string" &&
      (containsTokenShapedText(child) ||
        ABSOLUTE_PATH_PATTERN.test(child) ||
        URI_PATTERN.test(child))
        ? "[redacted]"
        : child;
  }
  return output;
}

/** 只接受可重试错误的显式正整数退避，不自动推导或截断无效值。 */
function validRetryAfter(value: number | undefined, retryable: boolean): number | undefined {
  return retryable &&
    value !== undefined &&
    Number.isInteger(value) &&
    value >= 1 &&
    value <= 3_600_000
    ? value
    : undefined;
}

/** 将 adapter 失败归一化，禁止序列化原始异常。 */
export function mapTransportError(_value: unknown): JaError {
  void _value;
  return new JaError("Unable to communicate with the Ja sidecar", {
    errorCode: "TRANSPORT_ERROR",
    category: "unavailable",
    retryable: true,
  });
}

/** 将客户端 Schema 失败转换为稳定校验错误。 */
export function mapValidationError(value: unknown): JaError {
  const field = value instanceof Error ? safeField(value.message) : undefined;
  return new JaError("Invalid request data", {
    code: JA_ERROR_CODES.INVALID_PARAMS,
    errorCode: "VALIDATION_ERROR",
    category: "validation",
    retryable: false,
    field,
  });
}

/** 将非法帧或结果转换为脱敏协议错误。 */
export function mapProtocolError(
  phase: "request" | "response" | "frame",
  method?: string,
  _cause?: unknown,
): JaError {
  void _cause;
  return new JaError("Invalid Ja protocol payload", {
    code: JA_ERROR_CODES.INVALID_FRAME,
    errorCode: "INVALID_FRAME",
    category: "protocol",
    retryable: false,
    details: {
      phase,
      ...(method === undefined ? {} : { method: safeField(method) ?? "[redacted]" }),
    },
  });
}

/** 将本地错误判别码限制在声明的机器目录和三个客户端专用值中。 */
function isErrorCode(value: unknown): value is ErrorCode {
  return (
    value === "UNKNOWN_ERROR" ||
    value === "TRANSPORT_ERROR" ||
    value === "VALIDATION_ERROR" ||
    (typeof value === "string" && Object.prototype.hasOwnProperty.call(JA_ERROR_CODES, value))
  );
}
