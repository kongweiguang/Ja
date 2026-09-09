// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  PreviewController,
  PreviewViewModel,
} from "@/features/workbench/preview/application/usePreviewController";
import { PreviewPanelView } from "@/features/workbench/preview/ui/PreviewPanelView";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/** 构造纯视图 controller；所有 action 都是可观测 fake，不连接 native。 */
function makeController(overrides: Partial<PreviewViewModel> = {}): PreviewController {
  return {
    viewModel: {
      url: "https://example.com/path",
      draft: "https://example.com/path",
      projection: { href: "https://example.com/path", origin: "https://example.com" },
      loading: false,
      recovering: false,
      active: true,
      canRetryRecovery: false,
      canReportViewport: false,
      mode: "web",
      ...overrides,
    },
    actions: {
      changeDraft: vi.fn(),
      submit: vi.fn(),
      retryRecovery: vi.fn(),
      changeViewport: vi.fn(),
      attachment: {
        dismiss: vi.fn(),
        retry: vi.fn(),
        zoomIn: vi.fn(),
        zoomOut: vi.fn(),
        fit: vi.fn(),
        actualSize: vi.fn(),
        reportImageFailure: vi.fn(),
      },
    },
  };
}

describe("PreviewPanelView", () => {
  it("展示 URL 投影并把表单事件委托给 controller", async () => {
    const user = userEvent.setup();
    const controller = makeController();
    render(<PreviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText("https://example.com")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "刷新或访问" }));
    expect(controller.actions.submit).toHaveBeenCalledOnce();
  });

  it("展示恢复状态并提供显式重试动作", async () => {
    const user = userEvent.setup();
    const recovering = makeController({ recovering: true, canRetryRecovery: true });
    const rendered = render(
      <PreviewPanelView viewModel={recovering.viewModel} actions={recovering.actions} />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("正在恢复浏览器");
    expect(screen.getByRole("button", { name: "刷新或访问" })).toBeDisabled();
    const failed = makeController({ error: "浏览器恢复未完成，请重试。", canRetryRecovery: true });
    rendered.rerender(<PreviewPanelView viewModel={failed.viewModel} actions={failed.actions} />);
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(failed.actions.retryRecovery).toHaveBeenCalledOnce();
  });

  it("上报 CSS pixel 边界，并在 inactive 与卸载时隐藏 native 子视图", () => {
    const rect = {
      x: 840,
      y: 250,
      width: 430,
      height: 540,
      top: 250,
      right: 1270,
      bottom: 790,
      left: 840,
      toJSON: () => undefined,
    };
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue(rect);
    const active = makeController({ canReportViewport: true });
    const rendered = render(
      <PreviewPanelView viewModel={active.viewModel} actions={active.actions} />,
    );
    expect(active.actions.changeViewport).toHaveBeenLastCalledWith({
      x: 840,
      y: 250,
      width: 430,
      height: 540,
      visible: true,
    });

    const inactive = makeController({ active: false, canReportViewport: true });
    rendered.rerender(
      <PreviewPanelView viewModel={inactive.viewModel} actions={inactive.actions} />,
    );
    expect(inactive.actions.changeViewport).toHaveBeenLastCalledWith({
      x: 840,
      y: 250,
      width: 430,
      height: 540,
      visible: false,
    });

    rendered.unmount();
    expect(inactive.actions.changeViewport).toHaveBeenLastCalledWith({
      x: 840,
      y: 250,
      width: 430,
      height: 540,
      visible: false,
    });
  });

  it("附件模式隐藏地址栏，焦点进入标题区并提供图片缩放与返回", async () => {
    const user = userEvent.setup();
    const controller = makeController({
      mode: "attachment",
      attachment: {
        status: "ready",
        target: {
          attachmentId: "att_image_1",
          displayName: "screenshot.png",
          mediaKind: "image",
          authorization: { kind: "draft" },
        },
        session: {
          previewSessionId: "preview-session-1",
          attachmentId: "att_image_1",
          displayName: "screenshot.png",
          sizeBytes: 1024,
          mediaKind: "image",
          mediaType: "image/png",
          resourceUrl: "ja-attachment://localhost/preview/resource-token-1",
        },
        content: {
          kind: "image",
          resourceUrl: "ja-attachment://localhost/preview/resource-token-1",
          zoom: { mode: "fit", percent: 100 },
        },
      },
    });
    render(<PreviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.queryByLabelText("Preview 地址")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "screenshot.png" })).toHaveFocus();
    expect(screen.getByRole("group", { name: "图片缩放" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "放大" }));
    expect(controller.actions.attachment.zoomIn).toHaveBeenCalledOnce();
    await user.click(screen.getByRole("button", { name: "返回网页预览" }));
    expect(controller.actions.attachment.dismiss).toHaveBeenCalledOnce();
  });

  it("从网页切到附件时隐藏但不关闭 native 网页 viewport", () => {
    const rect = {
      x: 840,
      y: 250,
      width: 430,
      height: 540,
      top: 250,
      right: 1270,
      bottom: 790,
      left: 840,
      toJSON: () => undefined,
    };
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue(rect);
    const web = makeController({ canReportViewport: true });
    const rendered = render(<PreviewPanelView viewModel={web.viewModel} actions={web.actions} />);
    const attachment = makeController({
      mode: "attachment",
      attachment: {
        status: "loading",
        target: {
          attachmentId: "att_image_1",
          displayName: "shot.png",
          mediaKind: "image",
          authorization: { kind: "draft" },
        },
      },
    });

    rendered.rerender(
      <PreviewPanelView viewModel={attachment.viewModel} actions={attachment.actions} />,
    );

    expect(web.actions.changeViewport).toHaveBeenLastCalledWith({
      x: 840,
      y: 250,
      width: 430,
      height: 540,
      visible: false,
    });
    expect(web.actions.attachment.dismiss).not.toHaveBeenCalled();
  });

  it("文本附件复用只读 CodeMirror 并明确展示 1 MiB 截断", () => {
    const controller = makeController({
      mode: "attachment",
      attachment: {
        status: "ready",
        target: {
          attachmentId: "att_text_1",
          displayName: "notes.txt",
          mediaKind: "text",
          authorization: { kind: "thread", threadId: "thread-1" },
        },
        session: {
          previewSessionId: "preview-session-1",
          attachmentId: "att_text_1",
          displayName: "notes.txt",
          sizeBytes: 2_000_000,
          mediaKind: "text",
          mediaType: "text/plain",
        },
        content: { kind: "text", text: "hello preview", truncated: true },
      },
    });
    render(<PreviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByRole("status")).toHaveTextContent("仅展示前 1 MiB");
    expect(screen.getByLabelText("只读文件 notes.txt")).toBeVisible();
    expect(document.querySelector(".cm-content")).toHaveTextContent("hello preview");
  });

  it("附件错误保留返回路径并只在可重试时展示重试", async () => {
    const user = userEvent.setup();
    const controller = makeController({
      mode: "attachment",
      attachment: {
        status: "error",
        target: {
          attachmentId: "att_text_1",
          displayName: "notes.txt",
          mediaKind: "text",
          authorization: { kind: "draft" },
        },
        message: "暂时无法打开附件预览",
        retryable: true,
      },
    });
    render(<PreviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByText("暂时无法打开附件预览")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(controller.actions.attachment.retry).toHaveBeenCalledOnce();
  });
});
