// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PreviewPort } from "@/features/workbench/preview/application/ports";
import { usePreviewController } from "@/features/workbench/preview/application/usePreviewController";

afterEach(() => cleanup());

/** 构造稳定 fake port，使 hook 重渲染不会因测试对象抖动产生额外 effect。 */
function createPort(): PreviewPort {
  return {
    openTarget: vi.fn(async () => undefined),
    newPage: vi.fn(async () => undefined),
    selectPage: vi.fn(),
    closePage: vi.fn(async () => undefined),
    navigate: vi.fn(),
    navigateFile: vi.fn(),
    goBack: vi.fn(),
    goForward: vi.fn(),
    reload: vi.fn(),
    retryRecovery: vi.fn(),
    changeViewport: vi.fn(),
  };
}

describe("usePreviewController", () => {
  it("校验失败时不调用导航 port", () => {
    const port = createPort();
    const { result } = renderHook(() =>
      usePreviewController({ url: "", loading: false, recovering: false, active: true, port }),
    );

    act(() => result.current.actions.changeDraft("javascript:alert(1)"));
    act(() => result.current.actions.submit());

    expect(result.current.viewModel.validationError).toContain("本机文件路径");
    expect(port.navigate).not.toHaveBeenCalled();
    expect(port.reload).not.toHaveBeenCalled();
  });

  it("把同 URL 路由为 reload，把新 URL 路由为 navigate", () => {
    const port = createPort();
    const { result } = renderHook(() =>
      usePreviewController({
        url: "https://example.com/path",
        loading: false,
        recovering: false,
        active: true,
        activePageId: "preview-page-1",
        pages: [],
        port,
      }),
    );

    act(() => result.current.actions.submit());
    expect(port.reload).toHaveBeenCalledOnce();

    act(() => result.current.actions.changeDraft("https://openai.com"));
    act(() => result.current.actions.submit());
    expect(port.navigate).toHaveBeenCalledWith("https://openai.com/");
  });

  it("native URL 变化后同步草稿并更新安全投影", () => {
    const port = createPort();
    const { result, rerender } = renderHook(
      ({ url }) =>
        usePreviewController({
          url,
          loading: false,
          recovering: false,
          active: true,
          activePageId: "preview-page-1",
          pages: [],
          port,
        }),
      { initialProps: { url: "https://example.com" } },
    );
    act(() => result.current.actions.changeDraft("https://draft.local"));

    rerender({ url: "https://openai.com/docs" });

    expect(result.current.viewModel.draft).toBe("https://openai.com/docs");
    expect(result.current.viewModel.projection?.origin).toBe("https://openai.com");
  });

  /** 消息文件链接的打开 action 必须等待 lifecycle ack，并对非法目标返回失败 Promise。 */
  it("rejects invalid open targets and propagates the lifecycle failure receipt", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      usePreviewController({
        url: "",
        loading: false,
        recovering: false,
        active: true,
        port,
      }),
    );

    await expect(
      result.current.actions.openTarget({ kind: "url", url: "javascript:alert(1)" }),
    ).rejects.toThrow("浏览器地址无效或暂不支持此协议。");
    expect(port.openTarget).not.toHaveBeenCalled();

    vi.mocked(port.openTarget!).mockRejectedValueOnce(new Error("文件不存在或已被移动。"));
    await expect(
      result.current.actions.openTarget({ kind: "file", path: "C:\\dev\\ja\\缺失.html" }),
    ).rejects.toThrow("文件不存在或已被移动。");
    expect(port.openTarget).toHaveBeenCalledWith({
      kind: "file",
      path: "C:\\dev\\ja\\缺失.html",
    });
  });

  it("附件投影切入和返回时保留网页 URL 草稿与导航 port", () => {
    const port = createPort();
    const attachmentPort = {
      open: vi.fn(async () => new Promise<never>(() => undefined)),
      read: vi.fn(),
      close: vi.fn(async () => undefined),
    };
    const attachmentTarget = {
      attachmentId: "att_image_1",
      displayName: "shot.png",
      mediaKind: "image" as const,
      authorization: { kind: "draft" as const },
    };
    const { result, rerender } = renderHook(
      ({ target }) =>
        usePreviewController({
          url: "https://example.com/path",
          loading: false,
          recovering: false,
          active: true,
          activePageId: "preview-page-1",
          pages: [],
          port,
          attachmentTarget: target,
          attachmentPort,
        }),
      { initialProps: { target: undefined as typeof attachmentTarget | undefined } },
    );
    act(() => result.current.actions.changeDraft("https://draft.example/path"));

    rerender({ target: attachmentTarget });
    expect(result.current.viewModel.mode).toBe("attachment");
    expect(result.current.viewModel.draft).toBe("https://draft.example/path");

    rerender({ target: undefined });
    expect(result.current.viewModel.mode).toBe("web");
    expect(result.current.viewModel.draft).toBe("https://draft.example/path");
    expect(port.navigate).not.toHaveBeenCalled();
    expect(port.reload).not.toHaveBeenCalled();
  });
});
