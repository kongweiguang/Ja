// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  AttachmentPreviewOpenResult,
  AttachmentPreviewPort,
  AttachmentPreviewTarget,
} from "@/features/workbench/preview/application/ports";
import { useAttachmentPreviewController } from "@/features/workbench/preview/application/useAttachmentPreviewController";

const TARGET: AttachmentPreviewTarget = {
  attachmentId: "att_text_1",
  displayName: "notes.txt",
  mediaKind: "text",
  authorization: { kind: "draft" },
};

afterEach(() => cleanup());

/** 构造有序分段 port，让测试观察 open/read/close 生命周期。 */
function textPort(): AttachmentPreviewPort {
  return {
    open: vi.fn(
      async (): Promise<AttachmentPreviewOpenResult> => ({
        previewSessionId: "preview-session-1",
        attachmentId: TARGET.attachmentId,
        displayName: TARGET.displayName,
        sizeBytes: 12,
        mediaKind: "text",
        mediaType: "text/plain",
      }),
    ),
    read: vi
      .fn()
      .mockResolvedValueOnce({
        previewSessionId: "preview-session-1",
        offsetBytes: 0,
        nextOffsetBytes: 6,
        endOfFile: false,
        truncated: false,
        content: "hello ",
      })
      .mockResolvedValueOnce({
        previewSessionId: "preview-session-1",
        offsetBytes: 6,
        nextOffsetBytes: 12,
        endOfFile: true,
        truncated: true,
        content: "world",
      }),
    close: vi.fn(async () => undefined),
  };
}

/** 创建可控 Promise，用于验证 DRAFT -> BOUND handoff 不闪回 loading。 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
} {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

describe("useAttachmentPreviewController", () => {
  it("聚合 UTF-8 文本分段并保留服务端截断事实", async () => {
    const port = textPort();
    const rendered = renderHook(() => useAttachmentPreviewController({ target: TARGET, port }));

    await waitFor(() => expect(rendered.result.current.projection?.status).toBe("ready"));
    expect(rendered.result.current.projection).toMatchObject({
      status: "ready",
      content: { kind: "text", text: "hello world", truncated: true },
    });
    expect(port.read).toHaveBeenNthCalledWith(1, "preview-session-1", 0, 65_536);
    expect(port.read).toHaveBeenNthCalledWith(2, "preview-session-1", 6, 65_536);

    rendered.unmount();
    await waitFor(() => expect(port.close).toHaveBeenCalledWith("preview-session-1"));
  });

  it("关闭附件立即清空投影并通知组合层恢复来源焦点", async () => {
    const port = textPort();
    const onDismiss = vi.fn();
    const { result } = renderHook(() =>
      useAttachmentPreviewController({ target: TARGET, port, onDismiss }),
    );
    await waitFor(() => expect(result.current.projection?.status).toBe("ready"));

    act(() => result.current.actions.dismiss());

    expect(result.current.projection).toBeUndefined();
    expect(onDismiss).toHaveBeenCalledWith(TARGET);
    expect(port.close).toHaveBeenCalledWith("preview-session-1");
  });

  it("图片默认适应窗口，缩放被限制在 25%-400%", async () => {
    const port: AttachmentPreviewPort = {
      open: vi.fn(
        async (): Promise<AttachmentPreviewOpenResult> => ({
          previewSessionId: "preview-session-2",
          attachmentId: "att_image_1",
          displayName: "shot.png",
          sizeBytes: 1024,
          mediaKind: "image",
          mediaType: "image/png",
          resourceUrl: "ja-attachment://localhost/preview/resource-token-1",
        }),
      ),
      read: vi.fn(),
      close: vi.fn(async () => undefined),
    };
    const imageTarget: AttachmentPreviewTarget = {
      attachmentId: "att_image_1",
      displayName: "shot.png",
      mediaKind: "image",
      authorization: { kind: "thread", threadId: "thread-1" },
    };
    const { result } = renderHook(() =>
      useAttachmentPreviewController({ target: imageTarget, port }),
    );
    await waitFor(() => expect(result.current.projection?.status).toBe("ready"));
    expect(result.current.projection).toMatchObject({
      content: { kind: "image", zoom: { mode: "fit", percent: 100 } },
    });

    act(() => result.current.actions.zoomIn());
    expect(result.current.projection).toMatchObject({
      content: { kind: "image", zoom: { mode: "scale", percent: 125 } },
    });
    for (let index = 0; index < 20; index += 1) act(() => result.current.actions.zoomIn());
    expect(result.current.projection).toMatchObject({
      content: { kind: "image", zoom: { mode: "scale", percent: 400 } },
    });
  });

  it("预览读取失败只进入右栏错误投影且允许显式重试", async () => {
    const port = textPort();
    vi.mocked(port.read).mockReset().mockRejectedValue(new Error("private path"));
    const { result } = renderHook(() => useAttachmentPreviewController({ target: TARGET, port }));

    await waitFor(() => expect(result.current.projection?.status).toBe("error"));
    expect(result.current.projection).toMatchObject({
      status: "error",
      message: "暂时无法打开附件预览",
      retryable: true,
    });
  });

  it("发送成功后从 DRAFT 平滑换签 BOUND session", async () => {
    const second = deferred<AttachmentPreviewOpenResult>();
    const first: AttachmentPreviewOpenResult = {
      previewSessionId: "preview-session-draft",
      attachmentId: "att_image_1",
      displayName: "shot.png",
      sizeBytes: 1024,
      mediaKind: "image",
      mediaType: "image/png",
      resourceUrl: "ja-attachment://localhost/preview/resource-token-draft",
    };
    const port: AttachmentPreviewPort = {
      open: vi
        .fn()
        .mockResolvedValueOnce(first)
        .mockImplementationOnce(() => second.promise),
      read: vi.fn(),
      close: vi.fn(async () => undefined),
    };
    const draft: AttachmentPreviewTarget = {
      attachmentId: "att_image_1",
      displayName: "shot.png",
      mediaKind: "image",
      authorization: { kind: "draft" },
    };
    const bound: AttachmentPreviewTarget = {
      ...draft,
      authorization: { kind: "thread", threadId: "thread-1" },
    };
    const rendered = renderHook(({ target }) => useAttachmentPreviewController({ target, port }), {
      initialProps: { target: draft },
    });
    await waitFor(() => expect(rendered.result.current.projection?.status).toBe("ready"));

    rendered.rerender({ target: bound });
    expect(rendered.result.current.projection).toMatchObject({
      status: "ready",
      session: { previewSessionId: "preview-session-draft" },
    });

    second.resolve({
      ...first,
      previewSessionId: "preview-session-bound",
      resourceUrl: "ja-attachment://localhost/preview/resource-token-bound",
    });
    await waitFor(() =>
      expect(rendered.result.current.projection).toMatchObject({
        status: "ready",
        session: { previewSessionId: "preview-session-bound" },
      }),
    );
    expect(port.close).toHaveBeenCalledWith("preview-session-draft");
  });
});
