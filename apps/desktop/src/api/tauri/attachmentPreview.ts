// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { z } from "zod";
import { invokeNativeCommand } from "./nativeInvoke";

export const JA_ATTACHMENT_PREVIEW_COMMANDS = {
  open: "ja_attachment_preview_open",
  read: "ja_attachment_preview_read",
  close: "ja_attachment_preview_close",
} as const;

type AttachmentPreviewCommand =
  (typeof JA_ATTACHMENT_PREVIEW_COMMANDS)[keyof typeof JA_ATTACHMENT_PREVIEW_COMMANDS];

const AttachmentIdSchema = z.string().regex(/^att_[A-Za-z0-9_.:-]{1,124}$/);
const PreviewSessionIdSchema = z.string().regex(/^[A-Za-z0-9_.:-]{8,160}$/);
const ThreadIdSchema = z.string().min(1).max(256);
const OffsetSchema = z
  .number()
  .int()
  .min(0)
  .max(1024 * 1024);
const MediaTypeSchema = z
  .string()
  .max(128)
  .regex(/^[a-z0-9][a-z0-9.+-]*\/[a-z0-9][a-z0-9.+-]*$/);

const AuthorizationSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("draft") }).strict(),
  z.object({ kind: z.literal("thread"), threadId: ThreadIdSchema }).strict(),
]);

const OpenInputSchema = z
  .object({ attachmentId: AttachmentIdSchema, authorization: AuthorizationSchema })
  .strict();

/** 自定义协议 URL 必须由 Rust 整体签发；renderer 不能自行拼接 opaque token。 */
function isManagedAttachmentUrl(value: string, kind: "preview" | "thumbnail"): boolean {
  try {
    const parsed = new URL(value);
    return (
      parsed.protocol === "ja-attachment:" &&
      parsed.hostname === "localhost" &&
      parsed.port === "" &&
      parsed.username === "" &&
      parsed.password === "" &&
      parsed.search === "" &&
      parsed.hash === "" &&
      new RegExp(`^/${kind}/[A-Za-z0-9_.:-]{8,192}$`).test(parsed.pathname)
    );
  } catch {
    return false;
  }
}

const OpenResultSchema = z
  .object({
    previewSessionId: PreviewSessionIdSchema,
    attachmentId: AttachmentIdSchema,
    displayName: z.string().min(1).max(1024),
    sizeBytes: z
      .number()
      .int()
      .min(0)
      .max(100 * 1024 * 1024),
    mediaKind: z.enum(["image", "text"]),
    mediaType: MediaTypeSchema.nullish().transform((value) => value ?? undefined),
    previewKind: z.enum(["image", "text"]),
    resourceUrl: z
      .string()
      .refine((value) => isManagedAttachmentUrl(value, "preview"))
      .optional(),
    thumbnailUrl: z
      .string()
      .refine((value) => isManagedAttachmentUrl(value, "thumbnail"))
      .optional(),
  })
  .strict()
  .superRefine((value, context) => {
    if (value.previewKind !== value.mediaKind) {
      context.addIssue({ code: "custom", message: "preview kind must match media kind" });
    }
    if (value.mediaKind === "image" && value.resourceUrl === undefined) {
      context.addIssue({ code: "custom", message: "image preview requires managed resource" });
    }
    if (value.mediaKind === "text" && value.resourceUrl !== undefined) {
      context.addIssue({ code: "custom", message: "text preview cannot expose resource URL" });
    }
  });

const ReadInputSchema = z
  .object({
    previewSessionId: PreviewSessionIdSchema,
    offsetBytes: OffsetSchema,
    maxBytes: z
      .number()
      .int()
      .min(4)
      .max(64 * 1024),
  })
  .strict();

const ReadResultSchema = z
  .object({
    previewSessionId: PreviewSessionIdSchema,
    offsetBytes: OffsetSchema,
    nextOffsetBytes: OffsetSchema,
    endOfFile: z.boolean(),
    truncated: z.boolean(),
    content: z.string().max(64 * 1024),
  })
  .strict();

const CloseInputSchema = z.object({ previewSessionId: PreviewSessionIdSchema }).strict();
const CloseResultSchema = z.object({ closed: z.literal(true) }).strict();

export type AttachmentPreviewAuthorization = z.infer<typeof AuthorizationSchema>;
export type AttachmentPreviewOpenInput = z.infer<typeof OpenInputSchema>;
export type AttachmentPreviewOpenResult = z.infer<typeof OpenResultSchema>;
export type AttachmentPreviewReadResult = z.infer<typeof ReadResultSchema>;

export interface AttachmentPreviewNativeBridge {
  invoke(command: AttachmentPreviewCommand, args?: Record<string, unknown>): Promise<unknown>;
}

const defaultNativeBridge: AttachmentPreviewNativeBridge = {
  invoke: (command, args) =>
    invokeNativeCommand(command, args, () => tauriInvoke<unknown>(command, args)),
};

export type AttachmentPreviewAdapterErrorCode =
  | "invalid_input"
  | "invalid_response"
  | "unsupported"
  | "unavailable";

/** 附件预览错误仅保存稳定恢复语义，不保留 native rejection、路径或 opaque identity。 */
export class AttachmentPreviewAdapterError extends Error {
  /** 固定本地文案避免底层错误内容进入右栏或日志投影。 */
  constructor(
    readonly code: AttachmentPreviewAdapterErrorCode,
    readonly retryable: boolean,
  ) {
    super(
      code === "unsupported"
        ? "此附件不支持预览"
        : code === "invalid_input"
          ? "附件预览请求无效"
          : code === "invalid_response"
            ? "附件预览返回无效"
            : "暂时无法打开附件预览",
    );
    this.name = "AttachmentPreviewAdapterError";
  }
}

/** Zod 只负责 renderer 边界形状，任何 issue 细节都不向上游传播。 */
function parseInput<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new AttachmentPreviewAdapterError("invalid_input", false);
  }
}

/** 原生结果必须完整满足闭集 DTO，额外字段同样拒绝。 */
function parseResult<T>(schema: z.ZodType<T>, value: unknown): T {
  try {
    return schema.parse(value);
  } catch {
    throw new AttachmentPreviewAdapterError("invalid_response", true);
  }
}

/** 仅识别 allow-list code；未知 rejection 一律转为可重试的静态错误。 */
function normalizeFailure(error: unknown): AttachmentPreviewAdapterError {
  if (error instanceof AttachmentPreviewAdapterError) return error;
  const code =
    error !== null && typeof error === "object" && "code" in error
      ? String((error as { code: unknown }).code)
      : "";
  return code === "ATTACHMENT_PREVIEW_UNSUPPORTED"
    ? new AttachmentPreviewAdapterError("unsupported", false)
    : new AttachmentPreviewAdapterError("unavailable", true);
}

/** invoke 只接受三个固定 command，组件无法获得通用 RPC 或资源读取能力。 */
async function invokeAttachmentPreview(
  bridge: AttachmentPreviewNativeBridge,
  command: AttachmentPreviewCommand,
  args: Record<string, unknown>,
): Promise<unknown> {
  try {
    return await bridge.invoke(command, args);
  } catch (error) {
    throw normalizeFailure(error);
  }
}

/** Tauri adapter 集中验证 session、分段上限与受控协议 URL。 */
export class TauriAttachmentPreviewAdapter {
  constructor(private readonly bridge: AttachmentPreviewNativeBridge = defaultNativeBridge) {}

  /** 打开一个已由 Java 授权的 image/text session，不接受路径或任意 URL。 */
  async open(input: AttachmentPreviewOpenInput): Promise<AttachmentPreviewOpenResult> {
    const parsed = parseInput(OpenInputSchema, input);
    const value = await invokeAttachmentPreview(this.bridge, JA_ATTACHMENT_PREVIEW_COMMANDS.open, {
      input: parsed,
    });
    const result = parseResult(OpenResultSchema, value);
    if (result.attachmentId !== parsed.attachmentId)
      throw new AttachmentPreviewAdapterError("invalid_response", true);
    return result;
  }

  /** 文本读取固定为 4..64 KiB，并拒绝 session/offset 不回显的串线结果。 */
  async read(
    previewSessionId: string,
    offsetBytes: number,
    maxBytes = 64 * 1024,
  ): Promise<AttachmentPreviewReadResult> {
    const input = parseInput(ReadInputSchema, { previewSessionId, offsetBytes, maxBytes });
    const value = await invokeAttachmentPreview(this.bridge, JA_ATTACHMENT_PREVIEW_COMMANDS.read, {
      input,
    });
    const result = parseResult(ReadResultSchema, value);
    if (
      result.previewSessionId !== input.previewSessionId ||
      result.offsetBytes !== input.offsetBytes ||
      result.nextOffsetBytes < result.offsetBytes ||
      (!result.endOfFile && !result.truncated && result.nextOffsetBytes === result.offsetBytes)
    ) {
      throw new AttachmentPreviewAdapterError("invalid_response", true);
    }
    return result;
  }

  /** 关闭单个 opaque session；只有明确 ACK 才视为资源已释放。 */
  async close(previewSessionId: string): Promise<void> {
    const input = parseInput(CloseInputSchema, { previewSessionId });
    const value = await invokeAttachmentPreview(this.bridge, JA_ATTACHMENT_PREVIEW_COMMANDS.close, {
      input,
    });
    parseResult(CloseResultSchema, value);
  }
}
