// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useCallback, useEffect, useRef, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  useTerminalWorkspaceController,
  type TerminalEvent,
  type TerminalNativeDropEvent,
  type TerminalSessionInfo,
  type TerminalWorkspaceAdapter,
  type UseTerminalWorkspaceOptions,
} from "@/features/workbench/terminal/application";
import {
  addTerminalTab,
  canAddTerminalTab,
  createDefaultTerminalLayout,
  splitTerminalPane,
} from "@/features/workbench/terminal/domain";
import { TerminalWorkspaceView } from "@/features/workbench/terminal/ui";

vi.mock("@/features/workbench/terminal/ui/TerminalPanel", () => ({
  /** 使用语义叶子隔离 xterm 内部实现，让测试只覆盖命中检测和生命周期边界。 */
  TerminalPanel: ({
    ariaLabel,
    onData,
  }: {
    ariaLabel?: string;
    onData?: (data: string) => void;
  }): ReactElement => (
    <div aria-label={ariaLabel} data-testid="terminal-panel">
      <button
        type="button"
        aria-label={`${ariaLabel ?? "终端"}发送测试输入`}
        onClick={() => onData?.("echo hello\r")}
      >
        输入
      </button>
    </div>
  ),
}));

const SESSION: TerminalSessionInfo = {
  sessionId: "11111111-1111-4111-8111-111111111111",
  generation: 1,
};
const DROP_TOKEN = "22222222-2222-4222-8222-222222222222";
const originalElementFromPoint = Object.getOwnPropertyDescriptor(document, "elementFromPoint");

interface TerminalWorkspaceTestHarnessProps extends UseTerminalWorkspaceOptions {
  active?: boolean;
  onRegisterCloseAll?: (closeAll: (() => Promise<void>) | undefined) => void;
}

/**
 * 测试专用 composition harness 复现 App 的 controller 注入边界；它只存在于 tests，
 * 防止为了组件测试把 adapter 或订阅重新塞回生产 UI。
 */
function TerminalWorkspace({
  active = true,
  onRegisterCloseAll,
  ...options
}: TerminalWorkspaceTestHarnessProps): ReactElement {
  const rootRef = useRef<HTMLElement>(null);
  /** 测试命中策略与 App composition 一致，只接受 harness 根节点内的 pane。 */
  const resolveNativeDropPane = useCallback((x: number, y: number): string | undefined => {
    const root = rootRef.current;
    const target = document.elementFromPoint?.(x, y);
    if (root === null || !(target instanceof Element) || !root.contains(target)) return undefined;
    const pane = target.closest<HTMLElement>("[data-terminal-pane-id]");
    return pane !== null && root.contains(pane) ? pane.dataset["terminalPaneId"] : undefined;
  }, []);
  const controller = useTerminalWorkspaceController({ ...options, active, resolveNativeDropPane });
  /** lifecycle 注册随 harness 卸载清理，避免测试之间复用旧 controller。 */
  useEffect(() => {
    onRegisterCloseAll?.(controller.closeAll);
    return () => onRegisterCloseAll?.(undefined);
  }, [controller.closeAll, onRegisterCloseAll]);
  return <TerminalWorkspaceView active={active} controller={controller} rootRef={rootRef} />;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  if (originalElementFromPoint === undefined) {
    Reflect.deleteProperty(document, "elementFromPoint");
  } else {
    Object.defineProperty(document, "elementFromPoint", originalElementFromPoint);
  }
});

/** 创建固定 profile 的非启动桥，并暴露唯一原生拖放 listener。 */
function fakeAdapter(overrides: Partial<TerminalWorkspaceAdapter> = {}): {
  adapter: TerminalWorkspaceAdapter;
  dropNativePaths: ReturnType<typeof vi.fn>;
  unlisten: ReturnType<typeof vi.fn>;
  listener: () => ((event: TerminalNativeDropEvent) => void) | undefined;
} {
  let nativeDropListener: ((event: TerminalNativeDropEvent) => void) | undefined;
  const dropNativePaths = vi.fn(async () => undefined);
  const unlisten = vi.fn();
  return {
    adapter: {
      profiles: vi.fn(
        async () => ["default", "power_shell", "cmd", "bash", "zsh", "fish"] as const,
      ),
      open: vi.fn(async () => SESSION),
      dropNativePaths,
      input: vi.fn(async () => undefined),
      resize: vi.fn(async () => undefined),
      poll: vi.fn(async (): Promise<TerminalEvent | null> => new Promise(() => undefined)),
      scrollback: vi.fn(async () => new Uint8Array()),
      close: vi.fn(async () => undefined),
      closeAll: vi.fn(async () => undefined),
      subscribeNativeDrop: vi.fn(async (listener) => {
        nativeDropListener = listener;
        return unlisten;
      }),
      ...overrides,
    },
    dropNativePaths,
    unlisten,
    listener: () => nativeDropListener,
  };
}

/** jsdom 没有原生 PointerEvent 构造器，因此显式注入测试所需坐标。 */
function dispatchPointer(
  target: Element | Window,
  type: string,
  properties: Readonly<Record<string, number | boolean>>,
): void {
  const event = new Event(type, { bubbles: true, cancelable: true });
  for (const [name, value] of Object.entries(properties)) {
    Object.defineProperty(event, name, { configurable: true, value });
  }
  fireEvent(target, event);
}

describe("TerminalWorkspace", () => {
  /** 只有当前活动窗格内的坐标才能消费一次性拖放 token。 */
  it("forwards a token only when the active terminal pane owns the hit point", async () => {
    const bridge = fakeAdapter();
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active
      />,
    );
    await waitFor(() => expect(bridge.listener()).toBeTypeOf("function"));
    await waitFor(() => expect(bridge.adapter.open).toHaveBeenCalledOnce());
    const pane = rendered.container.querySelector<HTMLElement>("[data-terminal-pane-id]");
    const panel = rendered.getByTestId("terminal-panel");
    expect(pane).not.toBeNull();
    await waitFor(() =>
      expect(pane).toHaveAttribute("data-terminal-session-id", SESSION.sessionId),
    );
    expect(pane).toHaveAttribute("data-terminal-session-generation", String(SESSION.generation));
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => panel),
    });

    await act(async () => {
      bridge.listener()?.({ dropToken: DROP_TOKEN, x: 10, y: 20 });
    });
    await waitFor(() => expect(bridge.dropNativePaths).toHaveBeenCalledWith(SESSION, DROP_TOKEN));

    const outside = document.createElement("div");
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => outside),
    });
    await act(async () => {
      bridge.listener()?.({ dropToken: "33333333-3333-4333-8333-333333333333", x: 30, y: 40 });
    });
    expect(bridge.dropNativePaths).toHaveBeenCalledOnce();

    const inactivePane = document.createElement("div");
    inactivePane.dataset["terminalPaneId"] = "inactive-pane";
    pane?.append(inactivePane);
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => inactivePane),
    });
    await act(async () => {
      bridge.listener()?.({ dropToken: "44444444-4444-4444-8444-444444444444", x: 50, y: 60 });
    });
    expect(bridge.dropNativePaths).toHaveBeenCalledOnce();

    rendered.rerender(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active={false}
      />,
    );
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => panel),
    });
    await act(async () => {
      bridge.listener()?.({ dropToken: "55555555-5555-4555-8555-555555555555", x: 70, y: 80 });
    });
    expect(bridge.dropNativePaths).toHaveBeenCalledOnce();

    rendered.unmount();
    expect(bridge.unlisten).toHaveBeenCalledOnce();
  });

  /** 已消费或竞争失败的原生 token 必须变成脱敏且可关闭的窗格提示，不能静默无操作。 */
  it("renders a retryable native drop failure without exposing native diagnostics", async () => {
    const dropNativePaths = vi
      .fn<TerminalWorkspaceAdapter["dropNativePaths"]>()
      .mockRejectedValueOnce(new Error("DROP_TOKEN_INVALID C:\\private\\workspace"));
    const bridge = fakeAdapter({ dropNativePaths });
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active
      />,
    );
    await waitFor(() => expect(bridge.listener()).toBeTypeOf("function"));
    await waitFor(() => expect(bridge.adapter.open).toHaveBeenCalledOnce());
    const pane = rendered.container.querySelector<HTMLElement>("[data-terminal-pane-id]");
    const panel = rendered.getByTestId("terminal-panel");
    expect(pane).not.toBeNull();
    Object.defineProperty(document, "elementFromPoint", {
      configurable: true,
      value: vi.fn(() => panel),
    });

    await act(async () => {
      bridge.listener()?.({ dropToken: DROP_TOKEN, x: 10, y: 20 });
    });

    const alert = await rendered.findByRole("alert");
    expect(alert).toHaveTextContent("文件拖入失败，请重新拖入。");
    expect(alert).toHaveTextContent("请重新拖入");
    expect(alert).not.toHaveTextContent("private\\workspace");
    await act(async () => {
      fireEvent.click(rendered.getByRole("button", { name: "重新拖入" }));
    });
    expect(rendered.queryByRole("alert")).not.toBeInTheDocument();
    expect(dropNativePaths).toHaveBeenCalledOnce();

    dropNativePaths.mockImplementationOnce(async () => undefined);
    await act(async () => {
      bridge.listener()?.({ dropToken: "33333333-3333-4333-8333-333333333333", x: 10, y: 20 });
    });
    await waitFor(() => expect(dropNativePaths).toHaveBeenCalledTimes(2));
    expect(rendered.queryByRole("alert")).not.toBeInTheDocument();
  });

  /** 工具栏展示原生关闭失败，并保留同一窗格供用户重试。 */
  it("renders a retryable close error without removing the pane", async () => {
    const bridge = fakeAdapter({
      close: vi.fn(async () => {
        throw new Error("close failed");
      }),
    });
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active
      />,
    );
    await waitFor(() => expect(bridge.adapter.open).toHaveBeenCalledOnce());

    fireEvent.click(rendered.getByRole("button", { name: "关闭终端窗格" }));

    expect(await rendered.findByRole("alert")).toHaveTextContent("终端关闭失败，可重试");
    expect(
      rendered.getByRole("tabpanel", { name: initialLayout.tabs[0]!.title }),
    ).toBeInTheDocument();
    expect(rendered.getByRole("button", { name: "重启终端" })).toBeInTheDocument();
  });

  /** shell 只接收 close-all 闭包，cleanup 在后续工作区复用 ref 前将其移除。 */
  it("registers and unregisters the narrow close-all lifecycle operation", async () => {
    const bridge = fakeAdapter();
    const onRegisterCloseAll = vi.fn();
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active={false}
        onRegisterCloseAll={onRegisterCloseAll}
      />,
    );
    await waitFor(() => expect(onRegisterCloseAll).toHaveBeenCalledWith(expect.any(Function)));
    const registered = onRegisterCloseAll.mock.calls.find(
      ([value]) => typeof value === "function",
    )?.[0] as (() => Promise<void>) | undefined;
    expect(registered).toBeTypeOf("function");

    await act(async () => {
      await registered?.();
    });
    expect(bridge.adapter.closeAll).toHaveBeenCalledWith("ws_fixture");

    rendered.unmount();
    expect(onRegisterCloseAll).toHaveBeenLastCalledWith(undefined);
  });

  /** 被拒绝的原生写入变成脱敏窗格提示，而不是未处理的 xterm callback Promise。 */
  it("renders a stable input error with a restart action", async () => {
    const bridge = fakeAdapter({
      input: vi.fn(async () => {
        throw new Error("C:\\private\\workspace");
      }),
    });
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active
      />,
    );
    await waitFor(() => expect(bridge.adapter.open).toHaveBeenCalledOnce());

    fireEvent.click(
      rendered.getByRole("button", {
        name: `终端窗格 ${initialLayout.tabs[0]!.title}发送测试输入`,
      }),
    );

    expect(await rendered.findByRole("alert")).toHaveTextContent("终端输入失败，请重启后重试");
    expect(rendered.getByRole("button", { name: "重启终端" })).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("private\\workspace");
  });

  /** 输出截断是持续且可访问的数据警告，不等同于 PTY 生命周期失败。 */
  it("renders an accessible output-truncation warning while the terminal keeps running", async () => {
    const events: TerminalEvent[] = [
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 1,
        kind: { type: "output_dropped", bytes: 1024 },
      },
      {
        session_id: SESSION.sessionId,
        generation: SESSION.generation,
        sequence: 2,
        kind: { type: "output", data: Uint8Array.from([0x61]) },
      },
    ];
    const bridge = fakeAdapter({
      poll: vi.fn(async () => events.shift() ?? new Promise<TerminalEvent | null>(() => undefined)),
    });
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active
      />,
    );

    const warning = await rendered.findByRole("status", { name: "输出已截断（丢弃 1024 字节）" });
    expect(warning).toHaveTextContent("输出已截断（丢弃 1024 字节）");
    expect(warning).toHaveTextContent(
      "后续输出仍会继续显示；如需完整输出，请重启终端后重新执行命令。",
    );
    expect(warning).toHaveAccessibleDescription(
      "后续输出仍会继续显示；如需完整输出，请重启终端后重新执行命令。",
    );
    expect(rendered.getByText("运行中")).toBeInTheDocument();
    expect(rendered.queryByRole("alert")).not.toBeInTheDocument();
  });

  /** 原生 cleanup 待完成时禁用重复动作，并在布局删除前展示 closing 生命周期。 */
  it("shows a non-clickable closing state until native close succeeds", async () => {
    let resolveClose: (() => void) | undefined;
    const bridge = fakeAdapter({
      close: vi.fn(
        async () =>
          new Promise<void>((resolve) => {
            resolveClose = resolve;
          }),
      ),
    });
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active
      />,
    );
    await waitFor(() => expect(bridge.adapter.open).toHaveBeenCalledOnce());

    fireEvent.click(rendered.getByRole("button", { name: "关闭终端窗格" }));

    expect(await rendered.findByRole("button", { name: "终端窗格正在关闭" })).toBeDisabled();
    expect(rendered.getByRole("status")).toHaveTextContent("关闭中");
    await act(async () => {
      resolveClose?.();
    });
    await waitFor(() => expect(rendered.getByText("没有打开的终端标签页。")).toBeInTheDocument());
  });

  /** 达到八个叶子后，加载完成的创建入口必须给出明确且可检查的禁用原因。 */
  it("disables add-tab at the workspace pane budget", async () => {
    let layout = createDefaultTerminalLayout("ws_fixture");
    while (canAddTerminalTab(layout)) layout = addTerminalTab(layout);

    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={fakeAdapter().adapter}
        initialLayout={layout}
        active={false}
      />,
    );

    const addButton = await rendered.findByRole("button", { name: "已达到 8 个终端窗格上限" });
    expect(addButton).toBeDisabled();
    fireEvent.click(addButton);
    expect(rendered.queryByRole("dialog", { name: "新建终端" })).not.toBeInTheDocument();
  });

  /** 一次提交只持久化选定休眠意图，活动窗格 effect 用精确 profile/cwd 打开。 */
  it("creates a tab with allow-listed profile and workspace-relative cwd", async () => {
    const user = userEvent.setup();
    const bridge = fakeAdapter();
    const onLayoutChange = vi.fn();
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        onLayoutChange={onLayoutChange}
        active
      />,
    );
    await waitFor(() => expect(bridge.adapter.open).toHaveBeenCalledOnce());

    fireEvent.click(rendered.getByRole("button", { name: "新建终端标签页" }));
    expect(rendered.getByRole("dialog", { name: "新建终端" })).toBeInTheDocument();
    rendered.getByRole("combobox", { name: "Shell profile" }).focus();
    await user.keyboard("{Enter}{ArrowDown}{Enter}");
    fireEvent.change(rendered.getByRole("textbox", { name: "工作目录" }), {
      target: { value: "packages/desktop" },
    });
    fireEvent.click(rendered.getByRole("button", { name: "创建终端" }));

    await waitFor(() => expect(bridge.adapter.open).toHaveBeenCalledTimes(2));
    expect(bridge.adapter.open).toHaveBeenLastCalledWith({
      workspaceId: "ws_fixture",
      profile: "power_shell",
      relativeCwd: "packages/desktop",
      size: { rows: 24, cols: 80, pixel_width: 0, pixel_height: 0 },
    });
    const persisted = onLayoutChange.mock.calls.at(-1)?.[0];
    expect(persisted?.tabs.at(-1)).toMatchObject({
      profile: "power_shell",
      relativeCwd: "packages/desktop",
    });
    expect(rendered.queryByRole("dialog", { name: "新建终端" })).not.toBeInTheDocument();
    expect(rendered.getByText("packages/desktop")).toBeInTheDocument();
  });

  /** 创建器逐项投影 Rust 返回集合，Windows 闭集不会泄漏 Bash、Zsh 或 Fish。 */
  it("shows only profiles returned by the native adapter", async () => {
    const user = userEvent.setup();
    const bridge = fakeAdapter({
      profiles: vi.fn(async () => ["default", "power_shell", "cmd"] as const),
    });
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        active={false}
      />,
    );

    const trigger = await rendered.findByRole("button", { name: "新建终端标签页" });
    fireEvent.click(trigger);
    rendered.getByRole("combobox", { name: "Shell profile" }).focus();
    await user.keyboard("{Enter}");
    const options = rendered.getAllByRole("option").map((option) => option.textContent);

    expect(options).toEqual(["系统默认", "PowerShell", "命令提示符"]);
    expect(rendered.queryByRole("option", { name: "Bash" })).not.toBeInTheDocument();
    expect(rendered.queryByRole("option", { name: "Zsh" })).not.toBeInTheDocument();
    expect(rendered.queryByRole("option", { name: "Fish" })).not.toBeInTheDocument();
  });

  /** 非法 cwd 保留为可编辑草稿，不能触发持久化或原生 open。 */
  it("rejects unsafe cwd in the creator before any side effect", async () => {
    const bridge = fakeAdapter();
    const onLayoutChange = vi.fn();
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        onLayoutChange={onLayoutChange}
        active={false}
      />,
    );

    fireEvent.click(await rendered.findByRole("button", { name: "新建终端标签页" }));
    fireEvent.change(rendered.getByRole("textbox", { name: "工作目录" }), {
      target: { value: "C:\\outside" },
    });
    fireEvent.click(rendered.getByRole("button", { name: "创建终端" }));

    expect(rendered.getByRole("alert")).toHaveTextContent("不能使用盘符、绝对路径或 ..");
    expect(rendered.getByRole("textbox", { name: "工作目录" })).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    expect(onLayoutChange).not.toHaveBeenCalled();
    expect(bridge.adapter.open).not.toHaveBeenCalled();
    expect(rendered.getByRole("dialog", { name: "新建终端" })).toBeInTheDocument();
  });

  /** 取消会丢弃本地 profile/cwd 草稿，不触碰布局和 PTY 所有权。 */
  it("cancels terminal creation with zero side effect", async () => {
    const user = userEvent.setup();
    const bridge = fakeAdapter();
    const onLayoutChange = vi.fn();
    const initialLayout = createDefaultTerminalLayout("ws_fixture");
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={initialLayout}
        onLayoutChange={onLayoutChange}
        active={false}
      />,
    );

    const trigger = await rendered.findByRole("button", { name: "新建终端标签页" });
    expect(trigger).toBeEnabled();
    fireEvent.click(trigger);
    rendered.getByRole("combobox", { name: "Shell profile" }).focus();
    await user.keyboard("{Enter}{ArrowDown}{ArrowDown}{ArrowDown}{Enter}");
    fireEvent.change(rendered.getByRole("textbox", { name: "工作目录" }), {
      target: { value: "packages/desktop" },
    });
    fireEvent.click(rendered.getByRole("button", { name: "取消" }));

    expect(rendered.queryByRole("dialog", { name: "新建终端" })).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(onLayoutChange).not.toHaveBeenCalled();
    expect(bridge.adapter.open).not.toHaveBeenCalled();
  });

  /** 单标签达到四个叶子后禁用两个分屏方向，不能保留可点击的空操作。 */
  it("disables split controls at the per-tab pane budget", () => {
    let layout = createDefaultTerminalLayout("ws_fixture");
    const tabId = layout.activeTabId as string;
    for (let index = 1; index < 4; index += 1) {
      layout = splitTerminalPane(layout, tabId, layout.tabs[0]!.activePaneId, "horizontal");
    }

    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={fakeAdapter().adapter}
        initialLayout={layout}
        active={false}
      />,
    );

    expect(rendered.getAllByRole("button", { name: /横向分屏不可用/u })).toHaveLength(4);
    expect(rendered.getAllByRole("button", { name: /纵向分屏不可用/u })).toHaveLength(4);
    expect(
      rendered
        .getAllByRole("button", { name: /分屏不可用/u })
        .every((button) => button.hasAttribute("disabled")),
    ).toBe(true);
  });

  it("previews split dragging locally and persists once on release while keyboard changes remain immediate", async () => {
    const bridge = fakeAdapter();
    const onLayoutChange = vi.fn();
    let layout = createDefaultTerminalLayout("ws_fixture");
    layout = splitTerminalPane(
      layout,
      layout.activeTabId!,
      layout.tabs[0]!.activePaneId,
      "horizontal",
    );
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={layout}
        onLayoutChange={onLayoutChange}
        active={false}
      />,
    );
    await rendered.findByRole("button", { name: "新建终端标签页" });
    onLayoutChange.mockClear();
    const separator = rendered.getByRole("separator", { name: "调整终端分屏比例" });
    const splitContainer = separator.parentElement!;
    const firstChild = splitContainer.querySelector<HTMLElement>(".ja-terminal-split-child")!;
    Object.defineProperty(splitContainer, "getBoundingClientRect", {
      configurable: true,
      value: () => ({
        left: 0,
        top: 0,
        width: 1_000,
        height: 500,
        right: 1_000,
        bottom: 500,
        x: 0,
        y: 0,
        toJSON: () => ({}),
      }),
    });
    Object.defineProperty(separator, "setPointerCapture", { configurable: true, value: vi.fn() });
    Object.defineProperty(separator, "hasPointerCapture", {
      configurable: true,
      value: vi.fn(() => false),
    });

    dispatchPointer(separator, "pointerdown", {
      button: 0,
      isPrimary: true,
      pointerId: 31,
      clientX: 500,
    });
    dispatchPointer(window, "pointermove", { pointerId: 31, clientX: 700 });
    expect(firstChild).toHaveStyle({ flexBasis: "70%" });
    expect(onLayoutChange).not.toHaveBeenCalled();
    dispatchPointer(window, "pointerup", { pointerId: 31, clientX: 750 });

    expect(onLayoutChange).toHaveBeenCalledOnce();
    expect(
      onLayoutChange.mock.calls[0]?.[0].tabs[0].root.kind === "split"
        ? onLayoutChange.mock.calls[0][0].tabs[0].root.ratio
        : undefined,
    ).toBe(0.75);
    fireEvent.keyDown(separator, { key: "ArrowRight" });
    expect(onLayoutChange).toHaveBeenCalledTimes(2);
    expect(
      onLayoutChange.mock.calls[1]?.[0].tabs[0].root.kind === "split"
        ? onLayoutChange.mock.calls[1][0].tabs[0].root.ratio
        : undefined,
    ).toBe(0.8);
  });

  it("commits the last visible split ratio once on cancel, capture loss, and window blur", async () => {
    const bridge = fakeAdapter();
    const onLayoutChange = vi.fn();
    let layout = createDefaultTerminalLayout("ws_fixture");
    layout = splitTerminalPane(
      layout,
      layout.activeTabId!,
      layout.tabs[0]!.activePaneId,
      "horizontal",
    );
    const rendered = render(
      <TerminalWorkspace
        workspaceId="ws_fixture"
        adapter={bridge.adapter}
        initialLayout={layout}
        onLayoutChange={onLayoutChange}
        active={false}
      />,
    );
    await rendered.findByRole("button", { name: "新建终端标签页" });
    onLayoutChange.mockClear();
    const separator = rendered.getByRole("separator", { name: "调整终端分屏比例" });
    const splitContainer = separator.parentElement!;
    Object.defineProperty(splitContainer, "getBoundingClientRect", {
      configurable: true,
      value: () => ({
        left: 0,
        top: 0,
        width: 1_000,
        height: 500,
        right: 1_000,
        bottom: 500,
        x: 0,
        y: 0,
        toJSON: () => ({}),
      }),
    });
    Object.defineProperty(separator, "setPointerCapture", { configurable: true, value: vi.fn() });
    Object.defineProperty(separator, "hasPointerCapture", {
      configurable: true,
      value: vi.fn(() => false),
    });

    dispatchPointer(separator, "pointerdown", {
      button: 0,
      isPrimary: true,
      pointerId: 41,
      clientX: 500,
    });
    dispatchPointer(window, "pointermove", { pointerId: 41, clientX: 600 });
    dispatchPointer(separator, "pointercancel", { pointerId: 41 });
    expect(onLayoutChange).toHaveBeenCalledTimes(1);

    dispatchPointer(separator, "pointerdown", {
      button: 0,
      isPrimary: true,
      pointerId: 42,
      clientX: 600,
    });
    dispatchPointer(window, "pointermove", { pointerId: 42, clientX: 650 });
    dispatchPointer(separator, "lostpointercapture", { pointerId: 42 });
    expect(onLayoutChange).toHaveBeenCalledTimes(2);

    dispatchPointer(separator, "pointerdown", {
      button: 0,
      isPrimary: true,
      pointerId: 43,
      clientX: 650,
    });
    dispatchPointer(window, "pointermove", { pointerId: 43, clientX: 700 });
    fireEvent.blur(window);
    expect(onLayoutChange).toHaveBeenCalledTimes(3);
    expect(
      onLayoutChange.mock.calls.map(([next]) =>
        next.tabs[0].root.kind === "split" ? next.tabs[0].root.ratio : undefined,
      ),
    ).toEqual([0.6, 0.65, 0.7]);
  });
});
