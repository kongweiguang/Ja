// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { z } from "zod";
import { invokeNativeCommand } from "./nativeInvoke";

const ATTACHMENT_COMMANDS = {
  import: "ja_attachment_import",
  discard: "ja_attachment_discard",
} as const;

const AttachmentStateSchema = z.enum(["draft", "bound", "discarded", "expired"]);

/**
 * 文件名按 Unicode code point 拒绝 C0/DEL 控制字符，避免正则控制字符范围触发 lint，
 * 同时保持 Windows 文件名入站边界不接受制表符或不可见换行。
 */
function hasSafeFilenameCharacters(value: string): boolean {
  return [...value].every((character) => {
    const codePoint = character.codePointAt(0);
    return codePoint !== undefined && codePoint > 31 && codePoint !== 127;
  });
}

const ImportedAttachmentSchema = z
  .object({
    attachmentId: z.string().regex(/^att_[A-Za-z0-9_.:-]{1,124}$/),
    fileName: z.string().min(1).max(1_024).refine(hasSafeFilenameCharacters),
    sizeBytes: z
      .number()
      .int()
      .min(0)
      .max(100 * 1024 * 1024),
    mediaType: z
      .string()
      .max(128)
      .regex(/^[a-z0-9][a-z0-9.+-]*\/[a-z0-9][a-z0-9.+-]*$/)
      .optional(),
    state: AttachmentStateSchema,
  })
  .strict();

const ImportedAttachmentsSchema = z.array(ImportedAttachmentSchema).max(10);

const DiscardAttachmentInputSchema = z
  .object({
    attachmentId: z.string().regex(/^att_[A-Za-z0-9_.:-]{1,124}$/),
  })
  .strict();

export type ImportedAttachment = z.infer<typeof ImportedAttachmentSchema>;
export type DiscardAttachmentInput = z.infer<typeof DiscardAttachmentInputSchema>;

const SAFE_ATTACHMENT_ERRORS: Readonly<Record<string, { message: string; retryable: boolean }>> = {
  ATTACHMENT_INGRESS_FAILED: { message: "无法添加这个文件", retryable: false },
  ATTACHMENT_RUNTIME_FAILED: { message: "附件服务暂不可用", retryable: true },
  ATTACHMENT_DIALOG_FAILED: { message: "无法打开文件选择器", retryable: true },
  INVALID_INPUT: { message: "附件请求无效", retryable: false },
};

class AttachmentError extends Error {
  readonly code: string;
  readonly retryable: boolean;

  /** 错误实例只保存 allow-list code 与固定本地文案，绝不保留 native rejection 的 cause/payload。 */
  constructor(code: string, message: string, retryable: boolean) {
    super(message);
    this.name = "AttachmentError";
    this.code = code;
    this.retryable = retryable;
  }
}

/** 未知 invoke/Zod 错误统一降级，防止路径、token、hash 或内部字段进入 React state 和 toast。 */
function normalizeAttachmentError(error: unknown): AttachmentError {
  if (error instanceof AttachmentError) return error;
  const code =
    error !== null &&
    typeof error === "object" &&
    typeof (error as { code?: unknown }).code === "string"
      ? (error as { code: string }).code
      : undefined;
  const safe = code === undefined ? undefined : SAFE_ATTACHMENT_ERRORS[code];
  const fallback = SAFE_ATTACHMENT_ERRORS["ATTACHMENT_RUNTIME_FAILED"] ?? {
    message: "附件服务暂不可用",
    retryable: true,
  };
  return new AttachmentError(
    code !== undefined && safe !== undefined ? code : "ATTACHMENT_RUNTIME_FAILED",
    (safe ?? fallback).message,
    (safe ?? fallback).retryable,
  );
}

/** 原生 command 内完成 dialog、staging 与 Java import；adapter 只接收最终脱敏 metadata。 */
export async function importAttachments(): Promise<readonly ImportedAttachment[]> {
  try {
    const value = await invokeNativeCommand(ATTACHMENT_COMMANDS.import, undefined, () =>
      tauriInvoke<unknown>(ATTACHMENT_COMMANDS.import),
    );
    return ImportedAttachmentsSchema.parse(value);
  } catch (error) {
    throw normalizeAttachmentError(error);
  }
}

/** 丢弃草稿只发送 Java attachment identity；路径、workspace 与 ingress token 不存在于该调用面。 */
export async function discardAttachment(input: DiscardAttachmentInput): Promise<void> {
  try {
    const parsed = DiscardAttachmentInputSchema.parse(input);
    await invokeNativeCommand(
      ATTACHMENT_COMMANDS.discard,
      { attachmentId: parsed.attachmentId },
      () => tauriInvoke<void>(ATTACHMENT_COMMANDS.discard, { attachmentId: parsed.attachmentId }),
    );
  } catch (error) {
    if (error instanceof z.ZodError) {
      throw new AttachmentError("INVALID_INPUT", "附件请求无效", false);
    }
    throw normalizeAttachmentError(error);
  }
}
