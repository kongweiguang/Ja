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
const RESOURCE_TOKEN = "0123456789abcdef0123456789abcdef";
const NATIVE_PREVIEW_SESSION_ID = `prv_${RESOURCE_TOKEN}`;
const OPEN_RESULT = {
  previewSessionId: "preview-session-1",
  attachmentId: "att_image_1",
  displayName: "shot.png",
  sizeBytes: 1024,
  mediaKind: "image",
  mediaType: "image/png",
  previewKind: "image",
  resourceUrl: `ja-attachment://localhost/preview/${RESOURCE_TOKEN}`,
  thumbnailUrl: `ja-attachment://localhost/thumbnail/${RESOURCE_TOKEN}`,
} as const;
/** 对照 Rust Host 按 target_os 签发的确定性跨端 fixture；真实 token 是 UUID simple。 */
const WINDOWS_OPEN_RESULT = {
  ...OPEN_RESULT,
  resourceUrl: `http://ja-attachment.localhost/preview/${RESOURCE_TOKEN}`,
  thumbnailUrl: `http://ja-attachment.localhost/thumbnail/${RESOURCE_TOKEN}`,
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
    { origin: "Windows HTTP", fixture: WINDOWS_OPEN_RESULT },
    { origin: "custom scheme", fixture: OPEN_RESULT },
  ])("接受 $origin 的 canonical preview/thumbnail URL", async ({ fixture }) => {
    const { bridge } = bridgeWith({ [JA_ATTACHMENT_PREVIEW_COMMANDS.open]: fixture });
    await expect(new TauriAttachmentPreviewAdapter(bridge).open(TARGET)).resolves.toMatchObject({
      resourceUrl: fixture.resourceUrl,
      thumbnailUrl: fixture.thumbnailUrl,
    });
  });

  it.each([
    {
      origin: "Windows HTTP",
      fixture: WINDOWS_OPEN_RESULT,
      invalid: [
        "http://ja-attachment.localhost:80/preview/0123456789abcdef0123456789abcdef",
        "http://ja-attachment.localhost:8080/preview/0123456789abcdef0123456789abcdef",
        "http://user@ja-attachment.localhost/preview/0123456789abcdef0123456789abcdef",
        "http://ja-attachment.localhost/preview/0123456789abcdef0123456789abcdef?x=1",
        "http://ja-attachment.localhost/preview/0123456789abcdef0123456789abcdef#x",
        "http://ja-attachment.localhost/preview/../0123456789abcdef0123456789abcdef",
        "http://ja-attachment.localhost/thumbnail/0123456789abcdef0123456789abcdef",
        "http://ja-attachment.localhost/preview/0123456789abcdef0123456789abcdeG",
        "http://ja-attachment.localhost/preview/0123456789abcdef0123456789abcdef/extra",
        "https://ja-attachment.localhost/preview/0123456789abcdef0123456789abcdef",
        "http://ja-attachment.localhost.evil/preview/0123456789abcdef0123456789abcdef",
      ],
    },
    {
      origin: "custom scheme",
      fixture: OPEN_RESULT,
      invalid: [
        "ja-attachment://localhost:80/preview/0123456789abcdef0123456789abcdef",
        "ja-attachment://localhost:123/preview/0123456789abcdef0123456789abcdef",
        "ja-attachment://user@localhost/preview/0123456789abcdef0123456789abcdef",
        "ja-attachment://localhost/preview/0123456789abcdef0123456789abcdef?x=1",
        "ja-attachment://localhost/preview/0123456789abcdef0123456789abcdef#x",
        "ja-attachment://localhost/preview/../0123456789abcdef0123456789abcdef",
        "ja-attachment://localhost/thumbnail/0123456789abcdef0123456789abcdef",
        "ja-attachment://localhost/preview/0123456789abcdef0123456789abcdeG",
        "ja-attachment://localhost/preview/0123456789abcdef0123456789abcdef/extra",
        "ja-attachmentx://localhost/preview/0123456789abcdef0123456789abcdef",
        "ja-attachment://localhost.evil/preview/0123456789abcdef0123456789abcdef",
      ],
    },
  ])("拒绝 $origin 中端口、凭据、附加成分或错误路径", async ({ fixture, invalid }) => {
    for (const resourceUrl of invalid) {
      const maliciousBridge = bridgeWith({
        [JA_ATTACHMENT_PREVIEW_COMMANDS.open]: { ...fixture, resourceUrl },
      }).bridge;
      await expect(
        new TauriAttachmentPreviewAdapter(maliciousBridge).open(TARGET),
      ).rejects.toMatchObject({ code: "invalid_response" });
    }
  });

  it("DTO 校验失败时仅对 native session 做补偿关闭", async () => {
    const malformed = {
      ...OPEN_RESULT,
      previewSessionId: NATIVE_PREVIEW_SESSION_ID,
      resourceUrl: "https://example.com/preview/0123456789abcdef0123456789abcdef",
    };
    const { bridge, calls } = bridgeWith({
      [JA_ATTACHMENT_PREVIEW_COMMANDS.open]: malformed,
      [JA_ATTACHMENT_PREVIEW_COMMANDS.close]: { closed: true },
    });

    await expect(new TauriAttachmentPreviewAdapter(bridge).open(TARGET)).rejects.toMatchObject({
      code: "invalid_response",
    });
    expect(calls).toEqual([
      { command: JA_ATTACHMENT_PREVIEW_COMMANDS.open, args: { input: TARGET } },
      {
        command: JA_ATTACHMENT_PREVIEW_COMMANDS.close,
        args: { input: { previewSessionId: NATIVE_PREVIEW_SESSION_ID } },
      },
    ]);
  });

  it("best-effort close 失败时仍保留原始 response error", async () => {
    const calls: string[] = [];
    const bridge: AttachmentPreviewNativeBridge = {
      invoke: async (command) => {
        calls.push(command);
        if (command === JA_ATTACHMENT_PREVIEW_COMMANDS.close) {
          throw new Error("cleanup failure");
        }
        return {
          ...OPEN_RESULT,
          previewSessionId: NATIVE_PREVIEW_SESSION_ID,
          resourceUrl: "https://example.com/invalid",
        };
      },
    };

    await expect(new TauriAttachmentPreviewAdapter(bridge).open(TARGET)).rejects.toMatchObject({
      code: "invalid_response",
    });
    expect(calls).toEqual([
      JA_ATTACHMENT_PREVIEW_COMMANDS.open,
      JA_ATTACHMENT_PREVIEW_COMMANDS.close,
    ]);
  });

  it.each(["preview-session-1", "prv_NOT-a-native-token"])(
    "不对非 Host 签发形状的 session 发 close (%s)",
    async (previewSessionId) => {
      const { bridge, calls } = bridgeWith({
        [JA_ATTACHMENT_PREVIEW_COMMANDS.open]: {
          ...OPEN_RESULT,
          previewSessionId,
          resourceUrl: "https://example.com/invalid",
        },
        [JA_ATTACHMENT_PREVIEW_COMMANDS.close]: { closed: true },
      });
      await expect(new TauriAttachmentPreviewAdapter(bridge).open(TARGET)).rejects.toMatchObject({
        code: "invalid_response",
      });
      expect(calls.map(({ command }) => command)).toEqual([JA_ATTACHMENT_PREVIEW_COMMANDS.open]);
    },
  );

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
