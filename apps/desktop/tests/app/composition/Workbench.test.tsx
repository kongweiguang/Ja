// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ComponentProps, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  capabilityWorkbenchTab,
  parseTaskWorkbenchTabKey,
  taskWorkbenchTab,
  Workbench,
  WorkbenchResizeHandle,
  type WorkbenchCapability,
  type WorkbenchTab,
  type WorkbenchTaskTab,
} from "@/features/workbench";
import {
  getRightPanelSessionState,
  useRightPanelSessionStore,
  type RightPanelTab,
} from "@/shared/preferences/uiPreferences";

const WORKBENCH_SCOPE = "server-1:1:workspace-1:thread-workbench";
type WorkbenchProps = ComponentProps<typeof Workbench>;

const views: WorkbenchProps["views"] = {
  review: <div>review state</div>,
  files: <div>files state</div>,
  terminal: <input aria-label="终端焦点探针" />,
  preview: <div>preview state</div>,
};

/** 测试同生产一样通过工厂创建描述符，避免回退到字符串 Tab 双轨。 */
function capabilityTabs(...capabilities: WorkbenchCapability[]): WorkbenchTab[] {
  return capabilities.map(capabilityWorkbenchTab);
}

/** 即使空白侧边任务也使用服务端 Thread 格式，测试不再制造未持久化草稿 Tab。 */
function sideTaskTab(rootThreadId: string, suffix: string): WorkbenchTaskTab {
  return taskWorkbenchTab({
    rootThreadId,
    taskThreadId: `thr_${suffix}`,
    taskKind: "side_task",
    label: "新侧聊",
  });
}

/** 用真实受控状态承接 Shell 意图，避免测试依赖已删除的未受控兼容模式。 */
function Harness({
  initialTab = capabilityWorkbenchTab("files"),
  initialOpenTabs = capabilityTabs("review", "files", "preview"),
  onTabClose,
  onClose,
  onCreateSideTask = () => sideTaskTab("thr_root", "12345678"),
  onTaskTabRename,
  onTabContextMenuOpenChange,
}: {
  initialTab?: WorkbenchTab;
  initialOpenTabs?: readonly WorkbenchTab[];
  onTabClose?: WorkbenchProps["onTabClose"];
  onClose?: () => void;
  onCreateSideTask?: WorkbenchProps["onCreateSideTask"];
  onTaskTabRename?: WorkbenchProps["onTaskTabRename"];
  onTabContextMenuOpenChange?: WorkbenchProps["onTabContextMenuOpenChange"];
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
      onCreateSideTask={onCreateSideTask}
      onTaskTabRename={onTaskTabRename}
      onTabContextMenuOpenChange={onTabContextMenuOpenChange}
    />
  );
}

/** 将偏好 store 的稳定 key 投影成 Workbench 描述符，测试真实的选择后关闭顺序。 */
function storedCapabilityTab(key: RightPanelTab): WorkbenchTab {
  return capabilityWorkbenchTab(key as WorkbenchCapability);
}

/** 直接接入生产会话 action，覆盖关闭全部 Tab 时所属 scope 的原子折叠行为。 */
function PreferencesHarness({
  onTabClose,
}: {
  onTabClose?: WorkbenchProps["onTabClose"];
}): ReactElement {
  const panel = useRightPanelSessionStore((state) =>
    getRightPanelSessionState(state.scopes, WORKBENCH_SCOPE),
  );
  const setSelected = useRightPanelSessionStore((state) => state.setRightPanelTab);
  const setOpen = useRightPanelSessionStore((state) => state.setRightPanelTabs);
  return (
    <Workbench
      selectedTab={storedCapabilityTab(panel.rightPanelTab)}
      openTabs={panel.rightPanelTabs.map(storedCapabilityTab)}
      onTabChange={(tab) => setSelected(WORKBENCH_SCOPE, tab.key)}
      onOpenTabsChange={(tabs) =>
        setOpen(
          WORKBENCH_SCOPE,
          tabs.map((tab) => tab.key),
        )
      }
      views={views}
      onTabClose={onTabClose}
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

/**
 * 将逐帧写入变成测试可控队列，既验证生产代码确实合并更新，也避免依赖 jsdom 的绘制时机。
 */
function installAnimationFrameHarness(): { flush: () => void } {
  let nextFrameId = 1;
  const callbacks = new Map<number, FrameRequestCallback>();
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => {
    const frameId = nextFrameId;
    nextFrameId += 1;
    callbacks.set(frameId, callback);
    return frameId;
  });
  vi.stubGlobal("cancelAnimationFrame", (frameId: number) => callbacks.delete(frameId));
  return {
    /** 同一批 pointermove 只交付一个浏览器帧，保持断言与真实合帧语义一致。 */
    flush: () => {
      const pending = [...callbacks.values()];
      callbacks.clear();
      for (const callback of pending) callback(performance.now());
    },
  };
}

/** 提供稳定的垂直坐标系，避免 jsdom 的零尺寸布局掩盖百分比换算错误。 */
function installResizeHandleRect(handle: HTMLElement): void {
  vi.spyOn(handle, "getBoundingClientRect").mockReturnValue({
    x: 700,
    y: 100,
    top: 100,
    right: 711,
    bottom: 500,
    left: 700,
    width: 11,
    height: 400,
    toJSON: () => ({}),
  });
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

  it("rejects impossible Subagent draft identities", () => {
    expect(parseTaskWorkbenchTabKey("side-task:draft_12345678")).toBeUndefined();
    expect(parseTaskWorkbenchTabKey("subagent:draft_12345678")).toBeUndefined();
  });

  /** 原生验收按可访问名称定位；锁定 Radix 由触发器命名菜单的实际语义，而非只看 aria-label 属性。 */
  it("replaces the initial launcher placeholder and uses plus as the only capability menu", async () => {
    const user = userEvent.setup();
    render(
      <Harness
        initialTab={capabilityWorkbenchTab("new")}
        initialOpenTabs={capabilityTabs("new")}
      />,
    );
    expect(screen.getByRole("heading", { name: "打开工作区工具" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: /终端/ }));
    expect(screen.getByRole("tab", { name: "终端" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("tab", { name: "新标签页" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "新建标签页" }));
    expect(screen.getByRole("menu")).toHaveAccessibleName("新建标签页");
    expect(screen.getByRole("menuitem", { name: "浏览器" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "打开右侧栏能力" })).not.toBeInTheDocument();
  });

  it("selects the real tab before removing the launcher placeholder", () => {
    const calls: string[] = [];
    render(
      <Workbench
        selectedTab={capabilityWorkbenchTab("new")}
        openTabs={capabilityTabs("new")}
        onTabChange={(tab) => calls.push(`select:${tab.key}`)}
        onOpenTabsChange={(tabs) => calls.push(`tabs:${tabs.map((tab) => tab.key).join(",")}`)}
        views={views}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /终端/ }));
    expect(calls).toEqual(["select:terminal", "tabs:terminal"]);
  });

  it("reorders the exact descriptor selected at pointer-down", () => {
    render(<Harness initialOpenTabs={capabilityTabs("review", "files", "preview")} />);
    const reviewShell = screen
      .getByRole("tab", { name: "审查" })
      .closest(".ja-workbench-tab-shell");
    const previewShell = screen
      .getByRole("tab", { name: "浏览器" })
      .closest(".ja-workbench-tab-shell");
    expect(reviewShell).not.toBeNull();
    expect(previewShell).not.toBeNull();

    dispatchPointer(reviewShell!, "pointerdown", { button: 0, pointerId: 17 });
    dispatchPointer(previewShell!, "pointerenter", { pointerId: 17 });
    dispatchPointer(previewShell!, "pointerup", { pointerId: 17 });

    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "文件",
      "浏览器",
      "审查",
    ]);
  });

  it("creates a local side-task descriptor from launcher and capability menu", async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    const { unmount } = render(
      <Harness
        initialTab={capabilityWorkbenchTab("new")}
        initialOpenTabs={capabilityTabs("new")}
        onClose={onClose}
      />,
    );

    await user.click(screen.getByRole("button", { name: "新建侧聊" }));
    expect(screen.getByRole("tab", { name: "新侧聊" })).toHaveAttribute("aria-selected", "true");
    expect(screen.queryByRole("tab", { name: "新标签页" })).not.toBeInTheDocument();
    unmount();

    render(<Harness initialOpenTabs={capabilityTabs("files")} onClose={onClose} />);
    await user.click(screen.getByRole("button", { name: "新建标签页" }));
    expect(screen.getByRole("menuitem", { name: "新建侧聊" })).toBeEnabled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("renames a side-task inline exactly once across Enter and blur without changing identity", async () => {
    const rename = vi.fn(async () => undefined);
    const draft = sideTaskTab("thr_root", "12345678");
    const user = userEvent.setup();
    render(<Harness initialTab={draft} initialOpenTabs={[draft]} onTaskTabRename={rename} />);

    const tab = screen.getByRole("tab", { name: "新侧聊" });
    await user.dblClick(tab);
    const input = screen.getByRole("textbox", { name: "侧聊名称" });
    await user.clear(input);
    await user.type(input, "排查启动问题{Enter}");
    fireEvent.blur(input);

    await waitFor(() => expect(rename).toHaveBeenCalledTimes(1));
    expect(rename).toHaveBeenCalledWith(
      expect.objectContaining({ key: draft.key }),
      "排查启动问题",
    );
    expect(screen.getByRole("tab")).toHaveAttribute("data-workbench-tab", draft.key);
  });

  it("keeps failed rename editable for retry and Escape cancels without a second request", async () => {
    const rename = vi
      .fn()
      .mockRejectedValueOnce(new Error("native detail"))
      .mockResolvedValue(undefined);
    const draft = sideTaskTab("thr_root", "87654321");
    const user = userEvent.setup();
    render(<Harness initialTab={draft} initialOpenTabs={[draft]} onTaskTabRename={rename} />);

    screen.getByRole("tab", { name: "新侧聊" }).focus();
    await user.keyboard("{F2}");
    const input = screen.getByRole("textbox", { name: "侧聊名称" });
    await user.clear(input);
    await user.type(input, "新的名称{Enter}");
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("重命名失败，请重试。"),
    );
    expect(screen.getByRole("alert")).not.toHaveTextContent("native detail");
    await user.type(input, "{Enter}");
    await waitFor(() => expect(rename).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.queryByRole("textbox", { name: "侧聊名称" })).not.toBeInTheDocument(),
    );

    screen.getByRole("tab").focus();
    await user.keyboard("{F2}");
    await user.type(screen.getByRole("textbox", { name: "侧聊名称" }), "取消{Escape}");
    expect(rename).toHaveBeenCalledTimes(2);
  });

  it("does not submit Enter while a side-task name is in IME composition", () => {
    const rename = vi.fn(async () => undefined);
    const draft = sideTaskTab("thr_root", "12344321");
    render(<Harness initialTab={draft} initialOpenTabs={[draft]} onTaskTabRename={rename} />);
    fireEvent.doubleClick(screen.getByRole("tab", { name: "新侧聊" }));
    const input = screen.getByRole("textbox", { name: "侧聊名称" });
    fireEvent.change(input, { target: { value: "输入中" } });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    expect(rename).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input, { key: "Enter" });
    expect(rename).toHaveBeenCalledTimes(1);
  });

  it("opens an inactive tab context menu without selecting it and restores focus on Escape", async () => {
    const openChange = vi.fn();
    const user = userEvent.setup();
    render(<Harness onTabContextMenuOpenChange={openChange} />);
    const filesTab = screen.getByRole("tab", { name: "文件" });
    const previewTab = screen.getByRole("tab", { name: "浏览器" });
    previewTab.focus();

    fireEvent.contextMenu(previewTab.closest(".ja-workbench-tab-shell")!, {
      clientX: window.innerWidth - 1,
      clientY: window.innerHeight - 1,
    });

    expect(filesTab).toHaveAttribute("aria-selected", "true");
    expect(previewTab).toHaveAttribute("aria-selected", "false");
    expect(await screen.findByRole("menu", { name: "浏览器 标签页操作" })).toBeVisible();
    expect(openChange).toHaveBeenLastCalledWith(true);
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    await waitFor(() => expect(previewTab).toHaveFocus());
    expect(openChange).toHaveBeenLastCalledWith(false);
  });

  it("retargets an open context menu and supports the keyboard ContextMenu entry", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const filesTab = screen.getByRole("tab", { name: "文件" });
    const previewTab = screen.getByRole("tab", { name: "浏览器" });
    fireEvent.contextMenu(previewTab.closest(".ja-workbench-tab-shell")!, {
      clientX: 120,
      clientY: 40,
    });
    expect(await screen.findByRole("menu", { name: "浏览器 标签页操作" })).toBeVisible();
    fireEvent.contextMenu(filesTab.closest(".ja-workbench-tab-shell")!, {
      clientX: 16,
      clientY: 18,
    });
    expect(await screen.findByRole("menu", { name: "文件 标签页操作" })).toBeVisible();

    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    filesTab.focus();
    fireEvent.keyDown(filesTab, { key: "ContextMenu" });
    expect(await screen.findByRole("menu", { name: "文件 标签页操作" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "关闭右侧标签页" })).toBeEnabled();
  });

  it("offers a real rename action only for side-task tabs", async () => {
    const rename = vi.fn(async () => undefined);
    const draft = sideTaskTab("thr_root", "12121212");
    const user = userEvent.setup();
    render(
      <Harness
        initialTab={draft}
        initialOpenTabs={[capabilityWorkbenchTab("files"), draft]}
        onTaskTabRename={rename}
      />,
    );
    const taskTab = screen.getByRole("tab", { name: "新侧聊" });
    fireEvent.contextMenu(taskTab.closest(".ja-workbench-tab-shell")!);
    await user.click(await screen.findByRole("menuitem", { name: "重命名" }));
    expect(screen.getByRole("textbox", { name: "侧聊名称" })).toHaveFocus();

    await user.keyboard("{Escape}");
    fireEvent.contextMenu(
      screen.getByRole("tab", { name: "文件" }).closest(".ja-workbench-tab-shell")!,
    );
    expect(screen.queryByRole("menuitem", { name: "重命名" })).not.toBeInTheDocument();
  });

  it("never exposes retired search, diff, or git tab ids", () => {
    render(<Harness initialOpenTabs={capabilityTabs("review", "files", "terminal", "preview")} />);
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
      <Harness
        initialTab={capabilityWorkbenchTab("preview")}
        initialOpenTabs={capabilityTabs("files", "preview")}
        onTabClose={close}
      />,
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
      <Harness
        initialTab={capabilityWorkbenchTab("terminal")}
        initialOpenTabs={capabilityTabs("files", "terminal")}
        onTabClose={close}
      />,
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

  it("closes right-side tabs serially and stops at the first rejected ACK", async () => {
    const acknowledgements = new Map<string, { resolve: () => void; reject: () => void }>();
    const close = vi.fn(
      (tab: WorkbenchTab) =>
        new Promise<void>((resolve, reject) => {
          acknowledgements.set(tab.key, { resolve, reject: () => reject(new Error("failed")) });
        }),
    );
    const user = userEvent.setup();
    render(
      <Harness
        initialTab={capabilityWorkbenchTab("files")}
        initialOpenTabs={capabilityTabs("files", "terminal", "preview", "review")}
        onTabClose={close}
      />,
    );
    fireEvent.contextMenu(
      screen.getByRole("tab", { name: "文件" }).closest(".ja-workbench-tab-shell")!,
    );
    await user.click(await screen.findByRole("menuitem", { name: "关闭右侧标签页" }));
    expect(close.mock.calls.map(([tab]) => tab.key)).toEqual(["terminal"]);

    acknowledgements.get("terminal")?.resolve();
    await waitFor(() => expect(close).toHaveBeenCalledTimes(2));
    expect(close.mock.calls.map(([tab]) => tab.key)).toEqual(["terminal", "preview"]);
    acknowledgements.get("preview")?.reject();
    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent("浏览器关闭失败，请重试。"),
    );
    expect(close).toHaveBeenCalledTimes(2);
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "文件",
      "浏览器",
      "审查",
    ]);
    expect(screen.getByRole("tab", { name: "文件" })).toHaveAttribute("aria-selected", "true");
  });

  it("uses active-first store updates so close-all cannot reinsert the last active tab", async () => {
    useRightPanelSessionStore.setState({
      scopes: new Map([
        [
          WORKBENCH_SCOPE,
          {
            inspectorOpen: true,
            rightPanelTab: "preview",
            rightPanelTabs: ["files", "terminal", "preview"],
          },
        ],
      ]),
    });
    const close = vi.fn();
    const user = userEvent.setup();
    render(<PreferencesHarness onTabClose={close} />);
    fireEvent.contextMenu(
      screen.getByRole("tab", { name: "文件" }).closest(".ja-workbench-tab-shell")!,
    );
    await user.click(await screen.findByRole("menuitem", { name: "关闭全部标签页" }));

    await waitFor(() =>
      expect(
        getRightPanelSessionState(useRightPanelSessionStore.getState().scopes, WORKBENCH_SCOPE)
          .rightPanelTabs,
      ).toEqual([]),
    );
    expect(
      getRightPanelSessionState(useRightPanelSessionStore.getState().scopes, WORKBENCH_SCOPE)
        .inspectorOpen,
    ).toBe(false);
    expect(close.mock.calls.map(([tab]) => tab.key)).toEqual(["files", "terminal", "preview"]);
  });

  it("stops a pending batch after unmount instead of closing stale workspace tabs", async () => {
    let resolveFirst: (() => void) | undefined;
    const close = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveFirst = resolve;
        }),
    );
    const user = userEvent.setup();
    const { unmount } = render(
      <Harness
        initialOpenTabs={capabilityTabs("files", "terminal", "preview")}
        onTabClose={close}
      />,
    );
    fireEvent.contextMenu(
      screen.getByRole("tab", { name: "文件" }).closest(".ja-workbench-tab-shell")!,
    );
    await user.click(await screen.findByRole("menuitem", { name: "关闭右侧标签页" }));
    expect(close).toHaveBeenCalledTimes(1);
    unmount();
    resolveFirst?.();
    await Promise.resolve();
    await Promise.resolve();
    expect(close).toHaveBeenCalledTimes(1);
  });

  it("collapses only after the last controlled tab closes", () => {
    const onClose = vi.fn();
    render(<Harness initialOpenTabs={capabilityTabs("files")} onClose={onClose} />);
    fireEvent.click(screen.getByRole("button", { name: "关闭文件" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.getByText("工作区面板已收起")).toBeVisible();
  });

  it("uses the same stable right-panel glyph for the drawer close action", () => {
    render(<Harness initialOpenTabs={capabilityTabs("files")} onClose={vi.fn()} />);

    const closeButton = screen.getByRole("button", { name: "收起右侧栏" });
    expect(closeButton.querySelector("svg")).toHaveClass("lucide-panel-right");
  });

  // 两侧共用相同的淡出边界，未绘制的新坐标不能在离开后继续执行。
  it("fades at the last painted position and resets only on keyboard focus", () => {
    const frame = installAnimationFrameHarness();
    render(<ResizeHarness onCommit={vi.fn()} />);
    const handle = screen.getByRole("separator", { name: "调整工作台宽度" });
    installResizeHandleRect(handle);

    expect(handle).toHaveClass("ja-workbench-resize-handle", "ja-resize-handle");
    dispatchPointer(handle, "pointerover", { pointerId: 5, clientY: 200 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("25%");
    dispatchPointer(handle, "pointermove", { pointerId: 5, clientY: 400 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("75%");
    dispatchPointer(handle, "pointermove", { pointerId: 5, clientY: -100 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("0%");
    dispatchPointer(handle, "pointermove", { pointerId: 5, clientY: 700 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("100%");
    dispatchPointer(handle, "pointermove", { pointerId: 5, clientY: 300 });
    dispatchPointer(handle, "pointerout", { pointerId: 5, clientY: 300 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("100%");
    fireEvent.focus(handle);
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("");
  });

  it("keeps spotlight and resize tracking outside the hit area while dragging", () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1000 });
    const frame = installAnimationFrameHarness();
    const onCommit = vi.fn();
    render(<ResizeHarness onCommit={onCommit} />);
    const handle = screen.getByRole("separator", { name: "调整工作台宽度" });
    installResizeHandleRect(handle);
    Object.assign(handle, {
      setPointerCapture: vi.fn(),
      hasPointerCapture: vi.fn(() => true),
      releasePointerCapture: vi.fn(),
    });

    dispatchPointer(handle, "pointerdown", {
      button: 0,
      pointerId: 9,
      clientX: 700,
      clientY: 200,
    });
    frame.flush();
    dispatchPointer(window, "pointermove", { pointerId: 9, clientX: 660, clientY: 400 });
    frame.flush();
    expect(handle).toHaveAttribute("aria-valuenow", "38");
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("75%");
    dispatchPointer(handle, "pointerout", { pointerId: 9, clientY: 400 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("75%");
    dispatchPointer(window, "pointermove", { pointerId: 9, clientX: 640, clientY: 700 });
    frame.flush();
    expect(handle).toHaveAttribute("aria-valuenow", "40");
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("100%");
    dispatchPointer(window, "pointerup", { pointerId: 9, clientX: 640, clientY: 700 });
    dispatchPointer(window, "pointercancel", { pointerId: 9, clientX: 640, clientY: 700 });
    fireEvent(window, new Event("blur"));
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenLastCalledWith(40);
  });

  it.each(["pointerup", "pointercancel", "blur"] as const)(
    "commits exactly once when %s ends the pointer transaction",
    (endEvent) => {
      Object.defineProperty(window, "innerWidth", { configurable: true, value: 1000 });
      const onCommit = vi.fn();
      render(<ResizeHarness onCommit={onCommit} />);
      const handle = screen.getByRole("separator", { name: "调整工作台宽度" });
      Object.assign(handle, {
        setPointerCapture: vi.fn(),
        hasPointerCapture: vi.fn(() => true),
        releasePointerCapture: vi.fn(),
      });
      dispatchPointer(handle, "pointerdown", {
        button: 0,
        pointerId: 19,
        clientX: 700,
        clientY: 200,
      });
      dispatchPointer(window, "pointermove", { pointerId: 19, clientX: 680, clientY: 300 });
      if (endEvent === "blur") fireEvent(window, new Event("blur"));
      else dispatchPointer(window, endEvent, { pointerId: 19, clientX: 680, clientY: 300 });
      dispatchPointer(window, "pointerup", { pointerId: 19, clientX: 680, clientY: 300 });
      dispatchPointer(window, "pointercancel", { pointerId: 19, clientX: 680, clientY: 300 });
      fireEvent(window, new Event("blur"));
      expect(onCommit).toHaveBeenCalledTimes(1);
      expect(onCommit).toHaveBeenLastCalledWith(36);
    },
  );

  it("keeps bounded keyboard resizing semantics", () => {
    const onCommit = vi.fn();
    render(<ResizeHarness onCommit={onCommit} />);
    const handle = screen.getByRole("separator", { name: "调整工作台宽度" });

    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle).toHaveAttribute("aria-valuenow", "33.5");
    fireEvent.keyDown(handle, { key: "Home" });
    expect(handle).toHaveAttribute("aria-valuenow", "24");
    fireEvent.keyDown(handle, { key: "End" });
    expect(handle).toHaveAttribute("aria-valuenow", "60");
  });
});
