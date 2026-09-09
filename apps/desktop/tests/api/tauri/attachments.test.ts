// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  cancelImport,
  clipboardImport,
  discardAttachment,
  discardAttempt,
  dropImport,
  pickerImport,
  retryImport,
} from "@/api/tauri/attachments";

const native = vi.hoisted(() => ({ invoke: vi.fn() }));

vi.mock("@tauri-apps/api/core", () => ({
  invoke: native.invoke,
  Channel: class Channel<T> {
    onmessage: (value: T) => void;

    /** 测试 Channel 只保留 caller handler，原生 fake 可按真实 args 主动推送帧。 */
    constructor(onmessage: (value: T) => void) {
      this.onmessage = onmessage;
    }
  },
}));

describe("attachment native adapter", () => {
  beforeEach(() => native.invoke.mockReset());

  /** Channel 逐帧解析脱敏事件，并将 App Server mediaKind/mediaType 投影给 application。 */
  it("routes picker import with a caller-owned channel and validates completed metadata", async () => {
    native.invoke.mockImplementationOnce(async (_command, args) => {
      args.onEvent.onmessage({
        kind: "started",
        operationId: "op_fixture",
        attemptId: "attempt_fixture",
        itemId: "item_fixture",
        fileName: "设计 说明.txt",
        sizeBytes: 3,
        mediaKind: "text",
        mediaType: "text/plain",
      });
      args.onEvent.onmessage({
        kind: "completed",
        operationId: "op_fixture",
        attemptId: "attempt_fixture",
        itemId: "item_fixture",
        attachment: {
          attachmentId: "att_fixture",
          fileName: "设计 说明.txt",
          sizeBytes: 3,
          mediaKind: "text",
          mediaType: "text/plain",
          state: "draft",
        },
      });
    });
    const events: unknown[] = [];

    await expect(
      pickerImport({ operationId: "op_fixture", onEvent: (event) => events.push(event) }),
    ).resolves.toBeUndefined();

    expect(native.invoke).toHaveBeenCalledWith("ja_attachment_picker_import", {
      operationId: "op_fixture",
      onEvent: expect.anything(),
    });
    expect(events).toEqual([
      expect.objectContaining({ kind: "started", fileName: "设计 说明.txt" }),
      expect.objectContaining({
        kind: "completed",
        attachment: expect.objectContaining({
          attachmentId: "att_fixture",
          mediaKind: "text",
        }),
      }),
    ]);
    expect(JSON.stringify(native.invoke.mock.calls)).not.toMatch(/sourcePath|sha256|blobKey/i);
  });

  /** drop 与 retry 只发送不透明 capability，绝对路径不在 TypeScript 调用面。 */
  it("routes drop and retry through their narrow commands", async () => {
    native.invoke.mockResolvedValue(undefined);
    await dropImport({
      operationId: "op_drop",
      dropToken: "drop_token",
      onEvent: vi.fn(),
    });
    await retryImport({
      operationId: "op_retry",
      attemptId: "attempt_retry",
      onEvent: vi.fn(),
    });
    expect(native.invoke).toHaveBeenNthCalledWith(1, "ja_attachment_drop_import", {
      operationId: "op_drop",
      dropToken: "drop_token",
      onEvent: expect.anything(),
    });
    expect(native.invoke).toHaveBeenNthCalledWith(2, "ja_attachment_retry", {
      operationId: "op_retry",
      attemptId: "attempt_retry",
      onEvent: expect.anything(),
    });
  });

  /** 新剪贴板命令返回严格 outcome，并继续用同一 Channel 投递已接纳项。 */
  it("routes native clipboard import and validates the accepted outcome", async () => {
    native.invoke.mockImplementationOnce(async (command, args) => {
      expect(command).toBe("ja_attachment_clipboard_import");
      args.onEvent.onmessage({
        kind: "started",
        operationId: "op_clipboard",
        attemptId: "attempt_clipboard",
        itemId: "item_clipboard",
        fileName: "pasted-image.png",
        sizeBytes: 68,
      });
      return { outcome: "accepted" };
    });
    const listener = vi.fn();

    await expect(
      clipboardImport({ operationId: "op_clipboard", onEvent: listener }),
    ).resolves.toEqual({ outcome: "accepted" });
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({ kind: "started", fileName: "pasted-image.png" }),
    );
    expect(native.invoke).toHaveBeenCalledWith("ja_attachment_clipboard_import", {
      operationId: "op_clipboard",
      onEvent: expect.anything(),
    });
  });

  /** 空格式与短暂占用是无附件的正常结果，adapter 不伪造 failed Channel 帧。 */
  it.each(["nothing_importable", "busy"] as const)(
    "preserves the clipboard %s outcome without fake events",
    async (outcome) => {
      native.invoke.mockResolvedValueOnce({ outcome });
      const listener = vi.fn();

      await expect(
        clipboardImport({ operationId: `op_${outcome}`, onEvent: listener }),
      ).resolves.toEqual({ outcome });
      expect(listener).not.toHaveBeenCalled();
    },
  );

  /** outcome 拒绝旧布尔、未知值和附加字段，避免 renderer 猜测原生剪贴板状态。 */
  it("rejects malformed clipboard outcomes", async () => {
    native.invoke.mockResolvedValueOnce({ outcome: "empty", legacy: true });

    await expect(
      clipboardImport({ operationId: "op_clipboard", onEvent: vi.fn() }),
    ).rejects.toMatchObject({ code: "ATTACHMENT_RUNTIME_FAILED" });
  });

  /** native message 与未知 code 都不能穿透 allow-list，避免路径或内部错误进入 UI。 */
  it("normalizes failed channel frames to a safe retryable error", async () => {
    native.invoke.mockImplementationOnce(async (_command, args) => {
      args.onEvent.onmessage({
        kind: "failed",
        operationId: "op_fixture",
        attemptId: "attempt_fixture",
        itemId: "item_fixture",
        code: "PRIVATE_PATH_FAILURE",
        message: "C:\\private\\secret.txt",
        retryable: false,
      });
    });
    const listener = vi.fn();
    await pickerImport({ operationId: "op_fixture", onEvent: listener });
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({
        code: "ATTACHMENT_RUNTIME_FAILED",
        message: "附件服务暂不可用",
        retryable: true,
      }),
    );
    expect(JSON.stringify(listener.mock.calls)).not.toContain("private");
  });

  /** 未声明字段可能携带路径或 hash，边界必须 fail closed。 */
  it("rejects malformed channel results", async () => {
    native.invoke.mockImplementationOnce(async (_command, args) => {
      args.onEvent.onmessage({
        kind: "completed",
        operationId: "op_fixture",
        attemptId: "attempt_fixture",
        itemId: "item_fixture",
        attachment: {
          attachmentId: "att_fixture",
          fileName: "safe.txt",
          sizeBytes: 1,
          mediaKind: "text",
          sourcePath: "C:\\private",
        },
      });
    });
    await expect(
      pickerImport({ operationId: "op_fixture", onEvent: vi.fn() }),
    ).rejects.toMatchObject({
      code: "ATTACHMENT_RUNTIME_FAILED",
      message: "附件服务暂不可用",
    });
  });

  /** cancel/discard 对应三种资源阶段，且额外字段在 invoke 前被拒绝。 */
  it("routes item cancel, attempt discard and ready discard with strict identities", async () => {
    native.invoke.mockResolvedValue(undefined);
    await cancelImport({ operationId: "op_fixture", itemId: "item_fixture" });
    await discardAttempt({ attemptId: "attempt_fixture" });
    await discardAttachment({ attachmentId: "att_fixture" });
    expect(native.invoke).toHaveBeenNthCalledWith(1, "ja_attachment_cancel", {
      operationId: "op_fixture",
      itemId: "item_fixture",
    });
    expect(native.invoke).toHaveBeenNthCalledWith(2, "ja_attachment_attempt_discard", {
      attemptId: "attempt_fixture",
    });
    expect(native.invoke).toHaveBeenNthCalledWith(3, "ja_attachment_discard", {
      attachmentId: "att_fixture",
    });
    await expect(
      discardAttachment({ attachmentId: "att_fixture", sourcePath: "C:\\private" } as never),
    ).rejects.toThrow();
    expect(native.invoke).toHaveBeenCalledTimes(3);
  });
});
