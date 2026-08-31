// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it, vi } from "vitest";
import { discardAttachment, importAttachments } from "@/api/tauri/attachments";

const native = vi.hoisted(() => ({ invoke: vi.fn() }));

vi.mock("@tauri-apps/api/core", () => ({ invoke: native.invoke }));

describe("attachment native adapter", () => {
  beforeEach(() => native.invoke.mockReset());

  /** 导入结果只允许脱敏展示字段，任何 token/path/hash 或额外 Java metadata 都必须被 strict schema 拒绝。 */
  it("accepts the redacted metadata shape and routes the picker command without path args", async () => {
    native.invoke.mockResolvedValueOnce([
      {
        attachmentId: "att_fixture",
        fileName: "设计 说明.txt",
        sizeBytes: 3,
        mediaType: "text/plain",
        state: "draft",
      },
    ]);

    await expect(importAttachments()).resolves.toEqual([
      expect.objectContaining({ attachmentId: "att_fixture", fileName: "设计 说明.txt" }),
    ]);
    expect(native.invoke).toHaveBeenCalledWith("ja_attachment_import");
    expect(JSON.stringify(native.invoke.mock.calls)).not.toMatch(/path|token|sha256/i);
  });

  /** 原生 dialog 取消是空成功，不应被组件当作异常状态。 */
  it("preserves cancel as an empty successful result", async () => {
    native.invoke.mockResolvedValueOnce([]);
    await expect(importAttachments()).resolves.toEqual([]);
  });

  /** 未经声明的字段可能包含内部路径或 hash，边界必须 fail closed 而不是 strip 后继续。 */
  it("rejects unsafe or malformed native results", async () => {
    native.invoke.mockResolvedValueOnce([
      {
        attachmentId: "att_fixture",
        fileName: "safe.txt",
        sizeBytes: 1,
        state: "draft",
        ingressToken: "must-not-cross",
      },
    ]);
    await expect(importAttachments()).rejects.toMatchObject({
      code: "ATTACHMENT_RUNTIME_FAILED",
      message: "附件服务暂不可用",
    });
  });

  /** discard 仅发送不透明 identity，并在 invoke 前拒绝畸形对象与额外字段。 */
  it("routes discard with the strict identity envelope", async () => {
    native.invoke.mockResolvedValueOnce(undefined);
    await expect(discardAttachment({ attachmentId: "att_fixture" })).resolves.toBeUndefined();
    expect(native.invoke).toHaveBeenCalledWith("ja_attachment_discard", {
      attachmentId: "att_fixture",
    });
    await expect(
      discardAttachment({ attachmentId: "att_fixture", sourcePath: "C:\\private" } as never),
    ).rejects.toThrow();
    expect(native.invoke).toHaveBeenCalledOnce();
  });
});
