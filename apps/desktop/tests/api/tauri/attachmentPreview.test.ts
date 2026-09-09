// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  AttachmentPreviewAdapterError,
  JA_ATTACHMENT_PREVIEW_COMMANDS,
  TauriAttachmentPreviewAdapter,
  type AttachmentPreviewNativeBridge,
} from "@/api/tauri/attachmentPreview";

const TARGET = {
  attachmentId: "att_image_1",
  authorization: { kind: "draft" },
} as const;
const OPEN_RESULT = {
  previewSessionId: "preview-session-1",
  attachmentId: "att_image_1",
  displayName: "shot.png",
  sizeBytes: 1024,
  mediaKind: "image",
  mediaType: "image/png",
  previewKind: "image",
  resourceUrl: "ja-attachment://localhost/preview/resource-token-1",
  thumbnailUrl: "ja-attachment://localhost/thumbnail/resource-token-1",
} as const;

/** 捕获窄 command envelope，避免测试依赖 Tauri 全局 mock。 */
function bridgeWith(responses: Record<string, unknown>): {
  bridge: AttachmentPreviewNativeBridge;
  calls: Array<{ command: string; args?: Record<string, unknown> }>;
} {
  const calls: Array<{ command: string; args?: Record<string, unknown> }> = [];
  return {
    bridge: {
      invoke: async (command, args) => {
        calls.push({ command, args });
        return responses[command];
      },
    },
    calls,
  };
}

describe("TauriAttachmentPreviewAdapter", () => {
  it("只调用 open/read/close 并保持 session 与 offset identity", async () => {
    const { bridge, calls } = bridgeWith({
      [JA_ATTACHMENT_PREVIEW_COMMANDS.open]: OPEN_RESULT,
      [JA_ATTACHMENT_PREVIEW_COMMANDS.read]: {
        previewSessionId: "preview-session-1",
        offsetBytes: 0,
        nextOffsetBytes: 4,
        endOfFile: true,
        truncated: false,
        content: "test",
      },
      [JA_ATTACHMENT_PREVIEW_COMMANDS.close]: { closed: true },
    });
    const adapter = new TauriAttachmentPreviewAdapter(bridge);

    await expect(adapter.open(TARGET)).resolves.toEqual(OPEN_RESULT);
    await expect(adapter.read("preview-session-1", 0, 65_536)).resolves.toMatchObject({
      content: "test",
      endOfFile: true,
    });
    await expect(adapter.close("preview-session-1")).resolves.toBeUndefined();

    expect(calls).toEqual([
      { command: JA_ATTACHMENT_PREVIEW_COMMANDS.open, args: { input: TARGET } },
      {
        command: JA_ATTACHMENT_PREVIEW_COMMANDS.read,
        args: {
          input: { previewSessionId: "preview-session-1", offsetBytes: 0, maxBytes: 65_536 },
        },
      },
      {
        command: JA_ATTACHMENT_PREVIEW_COMMANDS.close,
        args: { input: { previewSessionId: "preview-session-1" } },
      },
    ]);
  });

  it.each([
    "https://example.com/preview/token",
    "file:///C:/secret.png",
    "data:image/png;base64,AAAA",
    "ja-attachment://localhost/preview/../secret",
  ])("拒绝非受控资源 URL %s", async (resourceUrl) => {
    const { bridge } = bridgeWith({
      [JA_ATTACHMENT_PREVIEW_COMMANDS.open]: { ...OPEN_RESULT, resourceUrl },
    });
    await expect(new TauriAttachmentPreviewAdapter(bridge).open(TARGET)).rejects.toMatchObject({
      code: "invalid_response",
    });
  });

  it("拒绝停滞或串线的文本游标", async () => {
    const { bridge } = bridgeWith({
      [JA_ATTACHMENT_PREVIEW_COMMANDS.read]: {
        previewSessionId: "another-session",
        offsetBytes: 0,
        nextOffsetBytes: 0,
        endOfFile: false,
        truncated: false,
        content: "",
      },
    });
    await expect(
      new TauriAttachmentPreviewAdapter(bridge).read("preview-session-1", 0),
    ).rejects.toBeInstanceOf(AttachmentPreviewAdapterError);
  });

  it("原生异常只投影稳定恢复语义", async () => {
    const failed: AttachmentPreviewNativeBridge = {
      invoke: async () => {
        throw new Error("C:\\Users\\private\\token");
      },
    };
    await expect(new TauriAttachmentPreviewAdapter(failed).open(TARGET)).rejects.toMatchObject({
      code: "unavailable",
      message: "暂时无法打开附件预览",
      retryable: true,
    });
  });
});
