// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
      pages: [],
      canGoBack: false,
      canGoForward: false,
      canRetryRecovery: false,
      canReportViewport: false,
      mode: "web",
      ...overrides,
    },
    actions: {
      openTarget: vi.fn(async () => undefined),
      newPage: vi.fn(async () => undefined),
      selectPage: vi.fn(),
      closePage: vi.fn(async () => undefined),
      goBack: vi.fn(),
      goForward: vi.fn(),
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
    await user.click(screen.getByRole("button", { name: "刷新页面" }));
    expect(controller.actions.submit).toHaveBeenCalledOnce();
  });

  /** 地址草稿改变后，提交按钮应表达访问新目标，避免刷新图标误导本地路径输入。 */
  it("区分访问新地址与刷新当前页面", async () => {
    const user = userEvent.setup();
    const controller = makeController({ draft: String.raw`C:\docs\演示.svg` });
    render(<PreviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByRole("textbox", { name: "浏览器地址" })).toHaveAttribute(
      "placeholder",
      "网址或本地文件路径",
    );
    await user.click(screen.getByRole("button", { name: "访问地址" }));
    expect(controller.actions.submit).toHaveBeenCalledOnce();
  });

  /** 标签选择和浏览器控制栏必须暴露可访问名称与稳定 page identity。 */
  it("呈现多页面标签并将新建、切换、关闭和历史动作交给 controller", async () => {
    const user = userEvent.setup();
    const firstPageId = "11111111-1111-4111-8111-111111111111";
    const secondPageId = "22222222-2222-4222-8222-222222222222";
    const controller = makeController({
      url: "https://second.example/",
      projection: { href: "https://second.example/", origin: "https://second.example" },
      pages: [
        {
          pageId: firstPageId,
          url: "https://first.example/",
          title: "First",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
        {
          pageId: secondPageId,
          url: "https://second.example/",
          title: "Second",
          loading: true,
          canGoBack: true,
          canGoForward: false,
        },
      ],
      activePageId: secondPageId,
      canGoBack: true,
    });
    render(<PreviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    expect(screen.getByRole("tablist", { name: "浏览器页面" })).toBeVisible();
    expect(screen.getByRole("tab", { name: "First" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("tab", { name: "Second" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "First" })).toHaveAttribute("tabindex", "-1");
    expect(screen.getByRole("tab", { name: "Second" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("button", { name: "关闭浏览器标签 First" })).toHaveAttribute(
      "tabindex",
      "-1",
    );
    expect(screen.getByRole("button", { name: "关闭浏览器标签 Second" })).toHaveAttribute(
      "tabindex",
      "0",
    );
    expect(
      document.querySelector(`[data-preview-page-id="${secondPageId}"][role="tabpanel"]`),
    ).toHaveAttribute("data-preview-page-url", "https://second.example/");

    await user.click(screen.getByRole("tab", { name: "First" }));
    await user.keyboard("{ArrowRight}");
    expect(controller.actions.selectPage).toHaveBeenCalledWith(secondPageId);
    expect(screen.getByRole("tab", { name: "Second" })).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "关闭浏览器标签 First" }));
    await user.click(screen.getByRole("button", { name: "新建浏览器标签" }));
    await user.click(screen.getByRole("button", { name: "后退" }));
    expect(controller.actions.selectPage).toHaveBeenCalledWith(firstPageId);
    expect(controller.actions.closePage).toHaveBeenCalledWith(firstPageId);
    expect(controller.actions.newPage).toHaveBeenCalledOnce();
    expect(controller.actions.goBack).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "前进" })).toBeDisabled();
  });

  /** 右键必须按指针下的 pageId 操作，复制当前 URL 且绝不隐式选择该页。 */
  it("为浏览器标签提供复制地址与关闭菜单，不切换当前页面", async () => {
    const user = userEvent.setup();
    const firstPageId = "11111111-1111-4111-8111-111111111111";
    const secondPageId = "22222222-2222-4222-8222-222222222222";
    const controller = makeController({
      pages: [
        {
          pageId: firstPageId,
          url: "https://first.example/path",
          title: "First",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
        {
          pageId: secondPageId,
          url: "https://second.example/",
          title: "Second",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
      ],
      activePageId: secondPageId,
    });
    const onCopyText = vi.fn(async () => undefined);
    render(
      <PreviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        onCopyText={onCopyText}
      />,
    );

    fireEvent.contextMenu(screen.getByRole("tab", { name: "First" }), {
      clientX: 24,
      clientY: 48,
    });
    expect(await screen.findByRole("menu", { name: "浏览器标签 First" })).toBeVisible();
    await user.click(screen.getByRole("menuitem", { name: "复制地址" }));

    expect(onCopyText).toHaveBeenCalledWith("https://first.example/path");
    expect(controller.actions.selectPage).not.toHaveBeenCalled();
    expect(controller.actions.closePage).not.toHaveBeenCalled();
  });

  /** 不存在可复制地址时隐藏复制项，但仍保留始终有效的 pageId 关闭入口。 */
  it("地址为空时不展示复制菜单项", async () => {
    const pageId = "11111111-1111-4111-8111-111111111111";
    const controller = makeController({
      pages: [
        {
          pageId,
          url: "",
          title: "新标签页",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
      ],
      activePageId: pageId,
    });
    render(
      <PreviewPanelView
        viewModel={controller.viewModel}
        actions={controller.actions}
        onCopyText={vi.fn(async () => undefined)}
      />,
    );

    fireEvent.contextMenu(screen.getByRole("tab", { name: "新标签页" }), {
      clientX: 24,
      clientY: 48,
    });

    expect(await screen.findByRole("menu", { name: "浏览器标签 新标签页" })).toBeVisible();
    expect(screen.queryByRole("menuitem", { name: "复制地址" })).not.toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "关闭" })).toBeVisible();
  });

  /** 连续右击应以最新 pageId 重定位；ContextMenu 与 Shift+F10 支持 Escape 焦点返回。 */
  it("重新右击时切换菜单目标并支持键盘打开和 Escape 恢复焦点", async () => {
    const user = userEvent.setup();
    const firstPageId = "11111111-1111-4111-8111-111111111111";
    const secondPageId = "22222222-2222-4222-8222-222222222222";
    const controller = makeController({
      pages: [
        {
          pageId: firstPageId,
          url: "https://first.example/",
          title: "First",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
        {
          pageId: secondPageId,
          url: "https://second.example/",
          title: "Second",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
      ],
      activePageId: secondPageId,
    });
    render(<PreviewPanelView viewModel={controller.viewModel} actions={controller.actions} />);

    const firstTab = screen.getByRole("tab", { name: "First" });
    const secondTab = screen.getByRole("tab", { name: "Second" });
    fireEvent.contextMenu(firstTab, { clientX: 20, clientY: 40 });
    expect(await screen.findByRole("menu", { name: "浏览器标签 First" })).toBeVisible();
    fireEvent.contextMenu(secondTab, { clientX: 80, clientY: 40 });
    expect(await screen.findByRole("menu", { name: "浏览器标签 Second" })).toBeVisible();
    expect(screen.queryByRole("menu", { name: "浏览器标签 First" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "复制地址" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "关闭" }));
    expect(controller.actions.closePage).toHaveBeenNthCalledWith(1, secondPageId);
    await waitFor(() => expect(secondTab).toHaveFocus());

    fireEvent.keyDown(secondTab, { key: "ContextMenu" });
    expect(await screen.findByRole("menu", { name: "浏览器标签 Second" })).toBeVisible();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(secondTab).toHaveFocus());

    fireEvent.keyDown(firstTab, { key: "F10", shiftKey: true });
    expect(await screen.findByRole("menu", { name: "浏览器标签 First" })).toBeVisible();
    await user.click(screen.getByRole("menuitem", { name: "关闭" }));
    expect(controller.actions.closePage).toHaveBeenNthCalledWith(2, firstPageId);
    expect(controller.actions.selectPage).not.toHaveBeenCalled();
    await waitFor(() => expect(firstTab).toHaveFocus());
  });

  /** 当前页增长到窄栏可视区外时只滚动浏览器标签条，不能把聊天主区域一起带走。 */
  it("让窄栏内新选中的页面标签保持可见", () => {
    const firstPageId = "11111111-1111-4111-8111-111111111111";
    const secondPageId = "22222222-2222-4222-8222-222222222222";
    const pages = [
      {
        pageId: firstPageId,
        url: "https://first.example/",
        title: "First",
        loading: false,
        canGoBack: false,
        canGoForward: false,
      },
      {
        pageId: secondPageId,
        url: "https://second.example/",
        title: "Second",
        loading: false,
        canGoBack: false,
        canGoForward: false,
      },
    ];
    const first = makeController({ pages, activePageId: firstPageId });
    const rendered = render(
      <PreviewPanelView viewModel={first.viewModel} actions={first.actions} />,
    );
    const strip = screen.getByRole("tablist", { name: "浏览器页面" });
    const secondTab = screen
      .getByRole("tab", { name: "Second" })
      .closest<HTMLElement>(".ja-preview-tab");
    expect(secondTab).not.toBeNull();
    vi.spyOn(strip, "getBoundingClientRect").mockReturnValue({ left: 0, right: 100 } as DOMRect);
    vi.spyOn(secondTab!, "getBoundingClientRect").mockReturnValue({
      left: 120,
      right: 200,
    } as DOMRect);
    const second = makeController({ pages, activePageId: secondPageId });
    rendered.rerender(<PreviewPanelView viewModel={second.viewModel} actions={second.actions} />);
    expect(strip.scrollLeft).toBe(100);
  });

  /** 多个 Thread Host 同时保留在 DOM 时，标签和 tabpanel 关系必须各自唯一且配对。 */
  it("为并存的 Thread 预览生成独立 DOM id 与 ARIA 引用", () => {
    const firstPageId = "11111111-1111-4111-8111-111111111111";
    const secondPageId = "22222222-2222-4222-8222-222222222222";
    const first = makeController({
      pages: [
        {
          pageId: firstPageId,
          url: "https://first.example/",
          title: "First",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
      ],
      activePageId: firstPageId,
    });
    const second = makeController({
      pages: [
        {
          pageId: secondPageId,
          url: "https://second.example/",
          title: "Second",
          loading: false,
          canGoBack: false,
          canGoForward: false,
        },
      ],
      activePageId: secondPageId,
    });
    render(
      <>
        <PreviewPanelView viewModel={first.viewModel} actions={first.actions} />
        <PreviewPanelView viewModel={second.viewModel} actions={second.actions} />
      </>,
    );

    const addresses = screen.getAllByRole("textbox", { name: "浏览器地址" });
    const tabs = screen.getAllByRole("tab");
    const panels = screen.getAllByRole("tabpanel");
    expect(addresses).toHaveLength(2);
    expect(addresses[0]?.id).not.toBe(addresses[1]?.id);
    expect(addresses[0]?.closest("label")).toHaveAttribute("for", addresses[0]?.id);
    expect(addresses[1]?.closest("label")).toHaveAttribute("for", addresses[1]?.id);
    expect(panels).toHaveLength(2);
    expect(panels[0]?.id).not.toBe(panels[1]?.id);
    expect(tabs[0]).toHaveAttribute("aria-controls", panels[0]?.id);
    expect(tabs[1]).toHaveAttribute("aria-controls", panels[1]?.id);
  });

  it("展示恢复状态并提供显式重试动作", async () => {
    const user = userEvent.setup();
    const recovering = makeController({ recovering: true, canRetryRecovery: true });
    const rendered = render(
      <PreviewPanelView viewModel={recovering.viewModel} actions={recovering.actions} />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("正在恢复浏览器");
    expect(screen.getByRole("button", { name: "刷新页面" })).toBeDisabled();
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

    expect(screen.queryByLabelText("浏览器地址")).not.toBeInTheDocument();
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
