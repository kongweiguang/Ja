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

/**
 * 只匹配两种 native 固定 origin 和 canonical UUID token；WebView 的 UA 不是签发身份，
 * 因此校验不依赖平台猜测，且用原串相等拒绝 URL parser 规范化出的别名。
 */
function isManagedAttachmentUrl(value: string, kind: "preview" | "thumbnail"): boolean {
  try {
    const url = new URL(value);
    const originIsManaged =
      (url.protocol === "http:" && url.host === "ja-attachment.localhost") ||
      (url.protocol === "ja-attachment:" && url.host === "localhost");
    const prefix = `/${kind}/`;
    const token = url.pathname.startsWith(prefix) ? url.pathname.slice(prefix.length) : "";
    return (
      originIsManaged &&
      url.username === "" &&
      url.password === "" &&
      url.port === "" &&
      url.search === "" &&
      url.hash === "" &&
      url.href === value &&
      token.length === 32 &&
      /^[0-9a-f]{32}$/.test(token)
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
const NativePreviewSessionIdSchema = z.string().regex(/^prv_[0-9a-f]{32}$/);

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

/** 只为 Host 自己签发的 prv_<UUID simple> 做失败补偿，不能把畸形回复里的任意字符串发给 close。 */
function nativeSessionIdForCleanup(value: unknown): string | undefined {
  if (
    value === null ||
    typeof value !== "object" ||
    !Object.prototype.hasOwnProperty.call(value, "previewSessionId")
  ) {
    return undefined;
  }
  const sessionId = (value as { previewSessionId?: unknown }).previewSessionId;
  const parsed = NativePreviewSessionIdSchema.safeParse(sessionId);
  return parsed.success ? parsed.data : undefined;
}

/** 仅识别白名单错误码；未知拒绝一律转为可重试的静态错误。 */
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

  /**
   * 打开已授权 session；若 renderer DTO 投影失败，仅以 Host 固定形状的 opaque ID 尝试释放，
   * 并保留原始响应错误，避免坏响应占用本地资源直到 TTL。
   */
  async open(input: AttachmentPreviewOpenInput): Promise<AttachmentPreviewOpenResult> {
    const parsed = parseInput(OpenInputSchema, input);
    const value = await invokeAttachmentPreview(this.bridge, JA_ATTACHMENT_PREVIEW_COMMANDS.open, {
      input: parsed,
    });
    let result: AttachmentPreviewOpenResult;
    try {
      result = parseResult(OpenResultSchema, value);
      if (result.attachmentId !== parsed.attachmentId) {
        throw new AttachmentPreviewAdapterError("invalid_response", true);
      }
    } catch (error) {
      const sessionId = nativeSessionIdForCleanup(value);
      if (sessionId !== undefined) {
        try {
          await this.close(sessionId);
        } catch {
          // 补偿失败不能覆盖原始响应错误；native session TTL 仍提供最终兜底。
        }
      }
      throw error;
    }
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
