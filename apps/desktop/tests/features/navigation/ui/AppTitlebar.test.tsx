// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppTitlebar, type AppTitlebarProps, type DesktopPlatform } from "@/features/navigation";

const invokeWindowActionMock = vi.hoisted(() => vi.fn());

/** 构造完整 view model/actions；窗口原生生命周期由 application controller 单独验证。 */
function titlebarProps(platform: DesktopPlatform = "windows"): AppTitlebarProps {
  return {
    platform,
    sidebarOpen: true,
    onToggleSidebar: vi.fn(),
    canGoBack: true,
    canGoForward: false,
    onBack: vi.fn(),
    onForward: vi.fn(),
    windowFrame: { maximized: false, fullscreen: false },
    onWindowAction: invokeWindowActionMock,
  };
}

/** 构造确定性的标题栏 fixture，同时让原生动作保持可观察，便于验证真实调用边界。 */
function renderTitlebar(platform: DesktopPlatform = "windows") {
  return render(<AppTitlebar {...titlebarProps(platform)} />);
}

describe("AppTitlebar", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    invokeWindowActionMock.mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
  });

  it("renders only real Tauri window controls on Windows", async () => {
    const user = userEvent.setup();
    renderTitlebar("windows");

    const root = screen.getByRole("banner", { name: "应用标题栏" });
    expect(root).toHaveAttribute("data-tauri-drag-region");
    expect(root).toHaveAttribute("data-platform", "windows");
    expect(screen.getAllByRole("button")).toHaveLength(6);
    expect(root.querySelector(".ja-titlebar-caption")).not.toBeInTheDocument();
    expect(screen.queryByText("Ja")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "最小化" }));
    await user.click(screen.getByRole("button", { name: "最大化" }));
    await user.click(screen.getByRole("button", { name: "关闭" }));
    expect(invokeWindowActionMock.mock.calls).toEqual([
      ["minimize"],
      ["toggle-maximize"],
      ["close"],
    ]);
    for (const button of screen.getAllByRole("button")) {
      expect(button).toHaveAttribute("aria-label");
    }
    expect(screen.getByRole("button", { name: "隐藏侧边栏" })).toHaveAttribute(
      "aria-keyshortcuts",
      "Control+B",
    );
    expect(root.querySelector('[data-tauri-drag-region="false"]')).not.toBeInTheDocument();
  });

  it.each(["macos", "linux", "unknown"] as const)(
    "does not render pseudo Windows controls on %s",
    (platform) => {
      renderTitlebar(platform);

      expect(screen.queryByRole("button", { name: "最小化" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "最大化" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "关闭" })).not.toBeInTheDocument();
      expect(screen.getByRole("banner", { name: "应用标题栏" })).toHaveAttribute(
        "data-platform",
        platform,
      );
      expect(invokeWindowActionMock).not.toHaveBeenCalled();
    },
  );

  it("reserves macOS traffic-light space and never maximizes on macOS double click", () => {
    renderTitlebar("macos");
    const root = screen.getByRole("banner", { name: "应用标题栏" });

    expect(root).toHaveClass("is-macos");
    expect(screen.getByRole("button", { name: "隐藏侧边栏" })).toHaveAttribute(
      "aria-keyshortcuts",
      "Meta+B",
    );
    fireEvent.doubleClick(root);
    expect(invokeWindowActionMock).not.toHaveBeenCalled();
  });

  it("releases the native traffic-light inset while macOS owns fullscreen", () => {
    const props = titlebarProps("macos");
    const { rerender } = render(<AppTitlebar {...props} />);
    const root = screen.getByRole("banner", { name: "应用标题栏" });

    rerender(<AppTitlebar {...props} windowFrame={{ maximized: false, fullscreen: true }} />);
    expect(root).toHaveClass("is-fullscreen");
    expect(root).toHaveAttribute("data-window-fullscreen", "true");
    expect(screen.queryByRole("button", { name: "还原" })).not.toBeInTheDocument();
  });

  it("changes the maximize control into a restore control after native maximize", async () => {
    const user = userEvent.setup();
    const props = titlebarProps("windows");
    const { container, rerender } = render(<AppTitlebar {...props} />);

    expect(screen.getByRole("button", { name: "最大化" })).toBeVisible();
    expect(container.querySelector(".lucide-square")).toBeInTheDocument();
    rerender(<AppTitlebar {...props} windowFrame={{ maximized: true, fullscreen: false }} />);
    expect(screen.getByRole("button", { name: "还原" })).toBeVisible();
    expect(container.querySelector(".lucide-copy")).toBeInTheDocument();
    expect(screen.getByRole("banner", { name: "应用标题栏" })).toHaveAttribute(
      "data-window-maximized",
      "true",
    );

    await user.click(screen.getByRole("button", { name: "还原" }));
    expect(invokeWindowActionMock).toHaveBeenCalledWith("toggle-maximize");
  });

  it("maximizes only from a Windows drag surface, not from child controls", () => {
    renderTitlebar("windows");
    const root = screen.getByRole("banner", { name: "应用标题栏" });
    const sidebarButton = screen.getByRole("button", { name: "隐藏侧边栏" });

    fireEvent.doubleClick(sidebarButton);
    expect(invokeWindowActionMock).not.toHaveBeenCalled();
    fireEvent.doubleClick(root);
    expect(invokeWindowActionMock).toHaveBeenCalledOnce();
    expect(invokeWindowActionMock).toHaveBeenCalledWith("toggle-maximize");
  });

  it("locks all native controls while one window action is pending", () => {
    render(<AppTitlebar {...titlebarProps("windows")} windowActionPending="close" />);

    expect(screen.getByRole("button", { name: "最小化" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "最大化" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "关闭" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "关闭" })).toHaveAttribute("aria-busy", "true");
    fireEvent.doubleClick(screen.getByRole("banner", { name: "应用标题栏" }));
    expect(invokeWindowActionMock).not.toHaveBeenCalled();
  });

  it("keeps navigation state accessible and dispatches enabled actions", async () => {
    const user = userEvent.setup();
    const onToggleSidebar = vi.fn();
    const onBack = vi.fn();
    const onForward = vi.fn();
    render(
      <AppTitlebar
        platform="unknown"
        sidebarOpen={false}
        onToggleSidebar={onToggleSidebar}
        canGoBack
        canGoForward={false}
        onBack={onBack}
        onForward={onForward}
        windowFrame={{ maximized: false, fullscreen: false }}
        onWindowAction={invokeWindowActionMock}
      />,
    );

    const sidebarButton = screen.getByRole("button", { name: "显示侧边栏" });
    expect(sidebarButton).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("button", { name: "后退" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "前进" })).toBeDisabled();
    expect(screen.queryByText("工作区")).not.toBeInTheDocument();

    await user.click(sidebarButton);
    await user.click(screen.getByRole("button", { name: "后退" }));
    expect(onToggleSidebar).toHaveBeenCalledTimes(1);
    expect(onBack).toHaveBeenCalledTimes(1);
    expect(onForward).not.toHaveBeenCalled();
  });
});
