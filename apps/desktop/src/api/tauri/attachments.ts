// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Channel, invoke as tauriInvoke } from "@tauri-apps/api/core";
import { z } from "zod";
import { invokeNativeCommand } from "./nativeInvoke";

const ATTACHMENT_COMMANDS = {
  pickerImport: "ja_attachment_picker_import",
  dropImport: "ja_attachment_drop_import",
  clipboardImport: "ja_attachment_clipboard_import",
  retryImport: "ja_attachment_retry",
  cancelImport: "ja_attachment_cancel",
  discardAttempt: "ja_attachment_attempt_discard",
  discardAttachment: "ja_attachment_discard",
} as const;

const OpaqueIdSchema = z.string().min(1).max(128);
const AttachmentIdSchema = z.string().regex(/^att_[A-Za-z0-9_.:-]{1,124}$/);
const MediaKindSchema = z.enum(["text", "image", "pdf", "binary"]);
const MediaTypeSchema = z
  .string()
  .max(128)
  .regex(/^[a-z0-9][a-z0-9.+-]*\/[a-z0-9][a-z0-9.+-]*$/);
const SizeBytesSchema = z
  .number()
  .int()
  .min(0)
  .max(100 * 1024 * 1024);

/** 文件名拒绝 C0/DEL 控制字符，同时保留合法 Unicode，不作 ASCII 降级。 */
function hasSafeFilenameCharacters(value: string): boolean {
  return [...value].every((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && codePoint > 31 && codePoint !== 127;
  });
}

const FileNameSchema = z.string().min(1).max(1_024).refine(hasSafeFilenameCharacters);
const ThumbnailUrlSchema = z
  .string()
  .max(512)
  .regex(/^ja-attachment:\/\/[A-Za-z0-9_./?=&:%-]+$/);
const ImportedAttachmentSchema = z
  .object({
    attachmentId: AttachmentIdSchema,
    fileName: FileNameSchema,
    sizeBytes: SizeBytesSchema,
    mediaKind: MediaKindSchema,
    mediaType: MediaTypeSchema.nullable().optional(),
    thumbnailUrl: ThumbnailUrlSchema.optional(),
    state: z.literal("draft").optional(),
  })
  .strict()
  .transform((value) => ({
    attachmentId: value.attachmentId,
    fileName: value.fileName,
    sizeBytes: value.sizeBytes,
    mediaKind: value.mediaKind,
    mediaType: value.mediaType,
    thumbnailUrl: value.thumbnailUrl,
  }));

const AttachmentImportEventSchema = z.discriminatedUnion("kind", [
  z
    .object({
      kind: z.literal("started"),
      operationId: OpaqueIdSchema,
      attemptId: OpaqueIdSchema,
      itemId: OpaqueIdSchema,
      fileName: FileNameSchema,
      sizeBytes: SizeBytesSchema.optional(),
      mediaKind: MediaKindSchema.optional(),
      mediaType: MediaTypeSchema.nullable().optional(),
    })
    .strict(),
  z
    .object({
      kind: z.literal("progress"),
      operationId: OpaqueIdSchema,
      attemptId: OpaqueIdSchema,
      itemId: OpaqueIdSchema,
      phase: z.enum(["copying", "importing"]),
      bytesCopied: SizeBytesSchema,
      totalBytes: SizeBytesSchema.optional(),
    })
    .strict(),
  z
    .object({
      kind: z.literal("completed"),
      operationId: OpaqueIdSchema,
      attemptId: OpaqueIdSchema,
      itemId: OpaqueIdSchema,
      attachment: ImportedAttachmentSchema,
    })
    .strict(),
  z
    .object({
      kind: z.literal("failed"),
      operationId: OpaqueIdSchema,
      attemptId: OpaqueIdSchema,
      itemId: OpaqueIdSchema,
      fileName: FileNameSchema.optional(),
      sizeBytes: SizeBytesSchema.optional(),
      mediaKind: MediaKindSchema.optional(),
      mediaType: MediaTypeSchema.nullable().optional(),
      code: z.string().min(1).max(64),
      message: z.string().max(512).optional(),
      retryable: z.boolean(),
    })
    .strict(),
  z
    .object({
      kind: z.literal("cancelled"),
      operationId: OpaqueIdSchema,
      attemptId: OpaqueIdSchema,
      itemId: OpaqueIdSchema,
    })
    .strict(),
]);

type ParsedAttachmentImportEvent = z.infer<typeof AttachmentImportEventSchema>;
/** API adapter 只公开已脱敏的 Channel DTO；Feature 通过结构化 port 接收，不反向拥有 wire 类型。 */
export type AttachmentImportEvent =
  | Exclude<ParsedAttachmentImportEvent, { kind: "failed" }>
  | (Omit<Extract<ParsedAttachmentImportEvent, { kind: "failed" }>, "message"> & {
      message: string;
    });

/** caller-owned operation 与回调是原生附件命令的最小输入，不携带 Feature 状态。 */
export interface AttachmentImportInput {
  operationId: string;
  onEvent: (event: AttachmentImportEvent) => void;
}

const ClipboardImportResultSchema = z
  .object({ outcome: z.enum(["accepted", "nothing_importable", "busy"]) })
  .strict();

export type ClipboardImportResult = z.infer<typeof ClipboardImportResultSchema>;

const CancelImportInputSchema = z
  .object({ operationId: OpaqueIdSchema, itemId: OpaqueIdSchema.optional() })
  .strict();
const DiscardAttemptInputSchema = z.object({ attemptId: OpaqueIdSchema }).strict();
const DiscardAttachmentInputSchema = z.object({ attachmentId: AttachmentIdSchema }).strict();

const SAFE_ATTACHMENT_ERRORS: Readonly<Record<string, { message: string; retryable: boolean }>> = {
  ATTACHMENT_INGRESS_FAILED: { message: "无法添加这个文件", retryable: false },
  ATTACHMENT_SOURCE_UNAVAILABLE: { message: "源文件已失效，请重新选择", retryable: false },
  ATTACHMENT_LIMIT_EXCEEDED: { message: "附件数量或大小超过限制", retryable: false },
  ATTACHMENT_IMAGE_LIMIT_EXCEEDED: { message: "图片尺寸超过限制", retryable: false },
  ATTACHMENT_UNSUPPORTED_PATH: { message: "不支持这个文件位置", retryable: false },
  ATTACHMENT_NOT_REGULAR_FILE: { message: "只能添加普通文件", retryable: false },
  ATTACHMENT_LINK_NOT_ALLOWED: { message: "不能添加链接文件", retryable: false },
  ATTACHMENT_SOURCE_CHANGED: { message: "文件在导入时发生了变化，请重新选择", retryable: false },
  ATTACHMENT_SOURCE_READ_FAILED: { message: "暂时无法读取文件，请重试", retryable: true },
  ATTACHMENT_STAGING_FAILED: { message: "暂时无法准备附件，请重试", retryable: true },
  ATTACHMENT_RUNTIME_FAILED: { message: "附件服务暂不可用", retryable: true },
  ATTACHMENT_DIALOG_FAILED: { message: "无法打开文件选择器", retryable: true },
  INVALID_INPUT: { message: "附件请求无效", retryable: false },
};

export class AttachmentError extends Error {
  readonly code: string;
  readonly retryable: boolean;

  /** 错误实例只保存 allow-list code 与固定本地文案，不保留 native rejection payload。 */
  constructor(code: string, message: string, retryable: boolean) {
    super(message);
    this.name = "AttachmentError";
    this.code = code;
    this.retryable = retryable;
  }
}

/** 未知 invoke/Zod 错误统一降级，防止路径、token、hash 或内部字段进入 React state。 */
function normalizeAttachmentError(error: unknown): AttachmentError {
  if (error instanceof AttachmentError) return error;
  const code =
    error !== null &&
    typeof error === "object" &&
    typeof (error as { code?: unknown }).code === "string"
      ? (error as { code: string }).code
      : undefined;
  const safe = code === undefined ? undefined : SAFE_ATTACHMENT_ERRORS[code];
  const fallback = SAFE_ATTACHMENT_ERRORS["ATTACHMENT_RUNTIME_FAILED"]!;
  return new AttachmentError(
    code !== undefined && safe !== undefined ? code : "ATTACHMENT_RUNTIME_FAILED",
    (safe ?? fallback).message,
    (safe ?? fallback).retryable,
  );
}

/** failed event 只信任 native code；用户文案始终由 renderer allow-list 决定。 */
function sanitizeImportEvent(
  event: z.infer<typeof AttachmentImportEventSchema>,
): AttachmentImportEvent {
  if (event.kind !== "failed") return event;
  const safe = SAFE_ATTACHMENT_ERRORS[event.code];
  const fallback = SAFE_ATTACHMENT_ERRORS["ATTACHMENT_RUNTIME_FAILED"]!;
  return {
    ...event,
    code: safe === undefined ? "ATTACHMENT_RUNTIME_FAILED" : event.code,
    message: (safe ?? fallback).message,
    retryable: safe === undefined ? fallback.retryable : event.retryable && safe.retryable,
  };
}

/**
 * Channel 在 command 建立前绑定，确保 started 不丢；畸形事件被记录为边界错误，
 * command 结束后统一失败，不让未知 payload 进入 application 状态机。
 */
async function invokeImportCommand(
  command: string,
  args: Record<string, unknown>,
  onEvent: (event: AttachmentImportEvent) => void,
): Promise<void> {
  let boundaryError: AttachmentError | undefined;
  const onEventChannel = new Channel<unknown>((value) => {
    try {
      onEvent(sanitizeImportEvent(AttachmentImportEventSchema.parse(value)));
    } catch {
      boundaryError = new AttachmentError("ATTACHMENT_RUNTIME_FAILED", "附件服务暂不可用", true);
    }
  });
  try {
    await invokeNativeCommand(command, args, () =>
      tauriInvoke<void>(command, { ...args, onEvent: onEventChannel }),
    );
    if (boundaryError !== undefined) throw boundaryError;
  } catch (error) {
    throw normalizeAttachmentError(error);
  }
}

/** 原生 picker 是唯一选择入口，operationId 由 caller 创建以便取消和关联进度。 */
export function pickerImport(input: AttachmentImportInput): Promise<void> {
  const operationId = OpaqueIdSchema.parse(input.operationId);
  return invokeImportCommand(ATTACHMENT_COMMANDS.pickerImport, { operationId }, input.onEvent);
}

/** Drop command 只接收应用级路由器签发的一次性 token，不接收 renderer 路径。 */
export function dropImport(input: AttachmentImportInput & { dropToken: string }): Promise<void> {
  const operationId = OpaqueIdSchema.parse(input.operationId);
  const dropToken = OpaqueIdSchema.parse(input.dropToken);
  return invokeImportCommand(
    ATTACHMENT_COMMANDS.dropImport,
    { operationId, dropToken },
    input.onEvent,
  );
}

/** 无纯文本 paste 才调用原生剪贴板；结果只表达接纳、空内容或短暂占用。 */
export async function clipboardImport(
  input: AttachmentImportInput,
): Promise<ClipboardImportResult> {
  const operationId = OpaqueIdSchema.parse(input.operationId);
  let boundaryError: AttachmentError | undefined;
  const onEvent = new Channel<unknown>((value) => {
    try {
      input.onEvent(sanitizeImportEvent(AttachmentImportEventSchema.parse(value)));
    } catch {
      boundaryError = new AttachmentError("ATTACHMENT_RUNTIME_FAILED", "附件服务暂不可用", true);
    }
  });
  try {
    const value = await invokeNativeCommand(
      ATTACHMENT_COMMANDS.clipboardImport,
      { operationId },
      () => tauriInvoke<unknown>(ATTACHMENT_COMMANDS.clipboardImport, { operationId, onEvent }),
    );
    if (boundaryError !== undefined) throw boundaryError;
    return ClipboardImportResultSchema.parse(value);
  } catch (error) {
    throw normalizeAttachmentError(error);
  }
}

/** Retry 只引用 Rust 短期 attempt，renderer 不持有源路径或 staging token。 */
export function retryImport(input: AttachmentImportInput & { attemptId: string }): Promise<void> {
  const operationId = OpaqueIdSchema.parse(input.operationId);
  const attemptId = OpaqueIdSchema.parse(input.attemptId);
  return invokeImportCommand(
    ATTACHMENT_COMMANDS.retryImport,
    { operationId, attemptId },
    input.onEvent,
  );
}

/** Cancel 可精确到 item；省略 itemId 仅用于 caller 明确取消整批 operation。 */
export async function cancelImport(input: { operationId: string; itemId?: string }): Promise<void> {
  try {
    const parsed = CancelImportInputSchema.parse(input);
    await invokeNativeCommand(ATTACHMENT_COMMANDS.cancelImport, parsed, () =>
      tauriInvoke<void>(ATTACHMENT_COMMANDS.cancelImport, parsed),
    );
  } catch (error) {
    if (error instanceof z.ZodError)
      throw new AttachmentError("INVALID_INPUT", "附件请求无效", false);
    throw normalizeAttachmentError(error);
  }
}

/** failed 项移除时显式释放 Rust attempt，防止 UI 消失后遗留可重试材料。 */
export async function discardAttempt(input: { attemptId: string }): Promise<void> {
  try {
    const parsed = DiscardAttemptInputSchema.parse(input);
    await invokeNativeCommand(ATTACHMENT_COMMANDS.discardAttempt, parsed, () =>
      tauriInvoke<void>(ATTACHMENT_COMMANDS.discardAttempt, parsed),
    );
  } catch (error) {
    if (error instanceof z.ZodError)
      throw new AttachmentError("INVALID_INPUT", "附件请求无效", false);
    throw normalizeAttachmentError(error);
  }
}

/** 丢弃 ready 草稿只发送 attachment identity，不发送 workspace 或 ingress token。 */
export async function discardAttachment(input: { attachmentId: string }): Promise<void> {
  try {
    const parsed = DiscardAttachmentInputSchema.parse(input);
    await invokeNativeCommand(ATTACHMENT_COMMANDS.discardAttachment, parsed, () =>
      tauriInvoke<void>(ATTACHMENT_COMMANDS.discardAttachment, parsed),
    );
  } catch (error) {
    if (error instanceof z.ZodError)
      throw new AttachmentError("INVALID_INPUT", "附件请求无效", false);
    throw normalizeAttachmentError(error);
  }
}
