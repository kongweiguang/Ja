// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  HistoryAttachmentThumbnail,
  type HistoryAttachmentThumbnailPort,
} from "@/features/conversation/ui/timeline/HistoryAttachmentThumbnail";

let intersectionCallback: IntersectionObserverCallback | undefined;

/** 安装可控的 IntersectionObserver，测试只在显式进入 viewport 后准许原生读取。 */
function installIntersectionObserver(): void {
  vi.stubGlobal(
    "IntersectionObserver",
    class implements IntersectionObserver {
      readonly root = null;
      readonly rootMargin = "0px";
      readonly thresholds = [0];

      /** 保存 production callback，observe 本身不伪造可见状态。 */
      constructor(callback: IntersectionObserverCallback) {
        intersectionCallback = callback;
      }

      /** 测试显式触发交叉事件，注册保持无副作用。 */
      observe(): void {}

      /** 单节点解除不需要额外状态。 */
      unobserve(): void {}

      /** 测试 observer 不持有后台任务。 */
      disconnect(): void {}

      /** 无排队记录，返回空集合。 */
      takeRecords(): IntersectionObserverEntry[] {
        return [];
      }
    },
  );
}

/** 提交一次真实可见信号，避免组件测试依赖 jsdom 不存在的布局引擎。 */
function revealThumbnail(container: HTMLElement): void {
  const target = container.querySelector(".ja-chat-attachment__thumbnail");
  if (target === null || intersectionCallback === undefined)
    throw new Error("thumbnail observer was not registered");
  act(() => {
    intersectionCallback!(
      [{ isIntersecting: true, target } as IntersectionObserverEntry],
      {} as IntersectionObserver,
    );
  });
}

/** 构造最小预览端口，默认返回一个受管历史缩略图 session。 */
function createPort(
  overrides: Partial<HistoryAttachmentThumbnailPort> = {},
): HistoryAttachmentThumbnailPort {
  return {
    open: vi.fn(async ({ attachmentId }) => ({
      previewSessionId: "preview_history_1",
      attachmentId,
      mediaKind: "image" as const,
      thumbnailUrl: "ja-attachment://localhost/thumbnail/thumb_history_1",
    })),
    close: vi.fn(async () => undefined),
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
  intersectionCallback = undefined;
  vi.unstubAllGlobals();
});

describe("HistoryAttachmentThumbnail", () => {
  /** display:none/零尺寸 fallback 不能把虚拟列表的隐藏图片升级为原生 IO。 */
  it("does not load a zero-sized thumbnail without IntersectionObserver", async () => {
    vi.stubGlobal("IntersectionObserver", undefined);
    const port = createPort();
    render(
      <HistoryAttachmentThumbnail
        attachmentId="att_hidden"
        displayName="隐藏图.png"
        authorization={{ kind: "thread", threadId: "thr_one" }}
        port={port}
      />,
    );

    await act(async () => Promise.resolve());
    expect(port.open).not.toHaveBeenCalled();
  });

  /** 可见历史图只使用 Thread 授权，图片加载后一直持有 URL，卸载时关闭 session。 */
  it("loads a visible bound thumbnail and closes its session on unmount", async () => {
    installIntersectionObserver();
    const port = createPort();
    const { container, unmount } = render(
      <HistoryAttachmentThumbnail
        attachmentId="att_history"
        displayName="历史图.png"
        authorization={{ kind: "thread", threadId: "thr_one" }}
        port={port}
      />,
    );

    expect(port.open).not.toHaveBeenCalled();
    revealThumbnail(container);
    const image = await waitFor(() => {
      const value = container.querySelector<HTMLImageElement>("img");
      expect(value).not.toBeNull();
      return value!;
    });
    expect(port.open).toHaveBeenCalledWith({
      attachmentId: "att_history",
      authorization: { kind: "thread", threadId: "thr_one" },
    });
    fireEvent.load(image);
    expect(container.querySelector(".ja-chat-attachment__thumbnail")).toHaveAttribute(
      "data-state",
      "ready",
    );

    unmount();
    expect(port.close).toHaveBeenCalledWith("preview_history_1");
  });

  /** 协议图片加载失败时立即关闭受管 session，并留下可访问的降级状态。 */
  it("closes the session when the managed thumbnail fails to load", async () => {
    installIntersectionObserver();
    const port = createPort();
    const { container } = render(
      <HistoryAttachmentThumbnail
        attachmentId="att_broken"
        displayName="损坏图.png"
        authorization={{ kind: "thread", threadId: "thr_one" }}
        port={port}
      />,
    );
    revealThumbnail(container);
    const image = await waitFor(() => {
      const value = container.querySelector<HTMLImageElement>("img");
      expect(value).not.toBeNull();
      return value!;
    });
    fireEvent.error(image);

    expect(port.close).toHaveBeenCalledWith("preview_history_1");
    expect(container.querySelector('[data-state="unavailable"]')).toHaveAccessibleName(
      "损坏图.png 缩略图不可用",
    );
  });

  /** open 在卸载后才返回时不得写回 DOM，迟到 session 仍必须关闭。 */
  it("closes a late session after the thumbnail unmounts", async () => {
    installIntersectionObserver();
    let resolveOpen!: (value: Awaited<ReturnType<HistoryAttachmentThumbnailPort["open"]>>) => void;
    const port = createPort({
      open: vi.fn(
        () =>
          new Promise<Awaited<ReturnType<HistoryAttachmentThumbnailPort["open"]>>>((resolve) => {
            resolveOpen = resolve;
          }),
      ),
    });
    const { container, unmount } = render(
      <HistoryAttachmentThumbnail
        attachmentId="att_late"
        displayName="迟到图.png"
        authorization={{ kind: "thread", threadId: "thr_one" }}
        port={port}
      />,
    );
    revealThumbnail(container);
    await waitFor(() => expect(port.open).toHaveBeenCalledOnce());
    unmount();
    await act(async () => {
      resolveOpen({
        previewSessionId: "preview_late_1",
        attachmentId: "att_late",
        mediaKind: "image",
        thumbnailUrl: "ja-attachment://localhost/thumbnail/thumb_late_1",
      });
      await Promise.resolve();
    });

    expect(port.close).toHaveBeenCalledWith("preview_late_1");
  });

  /** 相同协议 URL 的新 target 也必须换节点；旧 load/error 不能污染状态或关闭新 session。 */
  it("ignores stale image events after rebuilding the same URL target", async () => {
    installIntersectionObserver();
    const sharedUrl = "ja-attachment://localhost/thumbnail/shared";
    const port = createPort({
      open: vi.fn(async ({ attachmentId }) => ({
        previewSessionId: attachmentId === "att_old" ? "preview_old" : "preview_new",
        attachmentId,
        mediaKind: "image" as const,
        thumbnailUrl: sharedUrl,
      })),
    });
    const { container, rerender } = render(
      <HistoryAttachmentThumbnail
        attachmentId="att_old"
        displayName="旧图.png"
        authorization={{ kind: "thread", threadId: "thr_one" }}
        port={port}
      />,
    );
    revealThumbnail(container);
    const oldImage = await waitFor(() => {
      const value = container.querySelector<HTMLImageElement>("img");
      expect(value).not.toBeNull();
      return value!;
    });

    rerender(
      <HistoryAttachmentThumbnail
        attachmentId="att_new"
        displayName="新图.png"
        authorization={{ kind: "thread", threadId: "thr_one" }}
        port={port}
      />,
    );
    await waitFor(() => expect(port.open).toHaveBeenCalledTimes(2));
    const newImage = await waitFor(() => {
      const value = container.querySelector<HTMLImageElement>("img");
      expect(value).not.toBe(oldImage);
      return value!;
    });
    fireEvent.load(newImage);
    expect(container.querySelector(".ja-chat-attachment__thumbnail")).toHaveAttribute(
      "data-state",
      "ready",
    );

    fireEvent.load(oldImage);
    fireEvent.error(oldImage);
    expect(container.querySelector(".ja-chat-attachment__thumbnail")).toHaveAttribute(
      "data-state",
      "ready",
    );
    expect(port.close).toHaveBeenCalledWith("preview_old");
    expect(port.close).not.toHaveBeenCalledWith("preview_new");
  });
});
