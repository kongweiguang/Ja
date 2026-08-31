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
      ...overrides,
    },
    actions: {
      changeDraft: vi.fn(),
      submit: vi.fn(),
      retryRecovery: vi.fn(),
      changeViewport: vi.fn(),
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
});
