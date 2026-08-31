// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Workbench, type WorkbenchProps } from "@/features/workbench/ui/Workbench";
import { WorkbenchResizeHandle } from "@/features/workbench/ui/WorkbenchResizeHandle";
import type { WorkbenchTab } from "@/features/workbench/domain/tabs";

const views: WorkbenchProps["views"] = {
  review: <div>review state</div>,
  files: <div>files state</div>,
  terminal: <input aria-label="终端焦点探针" />,
  preview: <div>preview state</div>,
};

/** 用真实受控状态承接 Shell 意图，避免测试依赖已删除的未受控兼容模式。 */
function Harness({
  initialTab = "files",
  initialOpenTabs = ["review", "files", "preview"],
  onTabClose,
  onClose,
}: {
  initialTab?: WorkbenchTab;
  initialOpenTabs?: readonly WorkbenchTab[];
  onTabClose?: WorkbenchProps["onTabClose"];
  onClose?: () => void;
}): ReactElement {
  const [selectedTab, setSelectedTab] = useState<WorkbenchTab>(initialTab);
  const [openTabs, setOpenTabs] = useState<readonly WorkbenchTab[]>(initialOpenTabs);
  return (
    <Workbench
      selectedTab={selectedTab}
      openTabs={openTabs}
      onTabChange={setSelectedTab}
      onOpenTabsChange={setOpenTabs}
      views={views}
      onTabClose={onTabClose}
      onClose={onClose}
    />
  );
}

/** 以 Shell 的受控方式承接工作台比例，覆盖 pointer 预览与最终提交的重新渲染。 */
function ResizeHarness({ onCommit }: { onCommit: (size: number) => void }): ReactElement {
  const [size, setSize] = useState(34);
  return (
    <div className="ja-workspace-panels">
      <WorkbenchResizeHandle
        size={size}
        minSize={24}
        maxSize={60}
        onPreview={setSize}
        onCommit={(next) => {
          setSize(next);
          onCommit(next);
        }}
      />
    </div>
  );
}

/** jsdom 没有完整 PointerEvent，实现坐标字段即可验证 window 级拖动事务。 */
function dispatchPointer(
  target: Document | Element | Window,
  type: string,
  values: Record<string, number>,
): void {
  const event = new Event(type, { bubbles: true, cancelable: true });
  for (const [key, value] of Object.entries(values)) {
    Object.defineProperty(event, key, { configurable: true, value });
  }
  fireEvent(target, event);
}

describe("Workbench controlled shell", () => {
  afterEach(cleanup);

  it("renders only controlled tabs and their injected feature projections", () => {
    render(<Harness />);
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "审查",
      "文件",
      "浏览器",
    ]);
    expect(screen.getByText("files state")).toBeVisible();
    expect(screen.getByText("review state")).not.toBeVisible();
  });

  it("opens the launcher as a real controlled tab and activates a capability", () => {
    render(<Harness initialOpenTabs={["files"]} />);
    fireEvent.click(screen.getByRole("button", { name: "新建标签页" }));
    expect(screen.getByRole("heading", { name: "打开工作区工具" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: /终端/ }));
    expect(screen.getByRole("tab", { name: "终端" })).toHaveAttribute("aria-selected", "true");
  });

  it("never exposes retired search, diff, or git tab ids", () => {
    render(<Harness initialOpenTabs={["review", "files", "terminal", "preview"]} />);
    expect(screen.queryByRole("tab", { name: /搜索|Diff|Git/u })).not.toBeInTheDocument();
  });

  it("waits for capability teardown ACK before committing close", async () => {
    let resolveClose: (() => void) | undefined;
    const close = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveClose = resolve;
        }),
    );
    render(
      <Harness initialTab="preview" initialOpenTabs={["files", "preview"]} onTabClose={close} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "关闭浏览器" }));
    expect(screen.getByRole("tab", { name: "浏览器" })).toBeInTheDocument();
    resolveClose?.();
    await waitFor(() =>
      expect(screen.queryByRole("tab", { name: "浏览器" })).not.toBeInTheDocument(),
    );
    expect(screen.getByRole("tab", { name: "文件" })).toHaveAttribute("aria-selected", "true");
  });

  it("retains the tab and exposes a retry when teardown rejects", async () => {
    const close = vi
      .fn()
      .mockRejectedValueOnce(new Error("native detail"))
      .mockResolvedValue(undefined);
    render(
      <Harness initialTab="terminal" initialOpenTabs={["files", "terminal"]} onTabClose={close} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "关闭终端" }));
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("终端关闭失败，请重试。"),
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent("native detail");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() =>
      expect(screen.queryByRole("tab", { name: "终端" })).not.toBeInTheDocument(),
    );
  });

  it("collapses only after the last controlled tab closes", () => {
    const onClose = vi.fn();
    render(<Harness initialOpenTabs={["files"]} onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: "关闭文件" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.getByText("工作区面板已收起")).toBeVisible();
  });

  it("uses the same stable right-panel glyph for the drawer close action", () => {
    render(<Harness initialOpenTabs={["files"]} onClose={vi.fn()} />);

    const closeButton = screen.getByRole("button", { name: "收起右侧栏" });
    expect(closeButton.querySelector("svg")).toHaveClass("lucide-panel-right");
  });

  it("resizes with window-level pointer capture and bounded keyboard controls", () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1000 });
    const onCommit = vi.fn();
    render(<ResizeHarness onCommit={onCommit} />);
    const handle = screen.getByRole("separator", { name: "调整工作台宽度" });
    Object.assign(handle, {
      setPointerCapture: vi.fn(),
      hasPointerCapture: vi.fn(() => true),
      releasePointerCapture: vi.fn(),
    });

    dispatchPointer(handle, "pointerdown", { button: 0, pointerId: 9, clientX: 700 });
    dispatchPointer(window, "pointermove", { pointerId: 9, clientX: 660 });
    expect(handle).toHaveAttribute("aria-valuenow", "38");
    dispatchPointer(window, "pointerup", { pointerId: 9, clientX: 660 });
    expect(onCommit).toHaveBeenLastCalledWith(38);

    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle).toHaveAttribute("aria-valuenow", "37.5");
    fireEvent.keyDown(handle, { key: "Home" });
    expect(handle).toHaveAttribute("aria-valuenow", "24");
    fireEvent.keyDown(handle, { key: "End" });
    expect(handle).toHaveAttribute("aria-valuenow", "60");
  });
});
