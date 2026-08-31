// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  NavigationResizeHandle,
  NavigationSidebar,
  type NavigationSidebarProps,
  type ThreadProjection,
} from "@/features/navigation";

const SIDEBAR_RATIO_MIN = 17;
const SIDEBAR_RATIO_DEFAULT = 22;
const SIDEBAR_RATIO_MAX = 33;

const thread: ThreadProjection = {
  threadId: "thread-1",
  title: "修复导航",
  status: "active",
};

/** 构造完整侧栏合同，同时让每项能力可独立观察，避免测试相互污染。 */
function sidebarProps(overrides: Partial<NavigationSidebarProps> = {}): NavigationSidebarProps {
  return {
    projects: [
      { workspaceId: "workspace-1", displayName: "ja" },
      { workspaceId: "workspace-2", displayName: "agent-studio" },
    ],
    projectCatalogLoading: false,
    currentWorkspaceId: "workspace-1",
    generalWorkspaceSelected: false,
    projectSectionCollapsed: false,
    historySectionCollapsed: false,
    runtimeLabel: "已连接",
    runtimeTone: "ready",
    currentThreadId: thread.threadId,
    threads: [thread],
    historyBusy: false,
    newConversationDisabled: false,
    projectBusy: false,
    compact: false,
    platform: "windows",
    activeAction: "workspace",
    conversationSearchOpen: false,
    onNewConversation: vi.fn(),
    onSelectConversation: vi.fn(),
    onOpenConversationSearch: vi.fn(),
    onRenameConversation: vi.fn(async () => undefined),
    onChooseProject: vi.fn(),
    onSelectGeneral: vi.fn(),
    onSelectProject: vi.fn(),
    onProjectSectionCollapsedChange: vi.fn(),
    onHistorySectionCollapsedChange: vi.fn(),
    onRetryProjects: vi.fn(),
    onOpenSettings: vi.fn(),
    onRequestClose: vi.fn(),
    ...overrides,
  };
}

/** 以真实受控状态承接两个分组开闭，验证 Radix 语义与内容生命周期同步。 */
function CollapsibleSidebarFixture(): ReactElement {
  const [projectSectionCollapsed, setProjectSectionCollapsed] = useState(false);
  const [historySectionCollapsed, setHistorySectionCollapsed] = useState(false);
  return (
    <NavigationSidebar
      {...sidebarProps({
        projectSectionCollapsed,
        historySectionCollapsed,
        onProjectSectionCollapsedChange: setProjectSectionCollapsed,
        onHistorySectionCollapsedChange: setHistorySectionCollapsed,
      })}
    />
  );
}

/** 按 App 的真实方式受控维护比例状态，使键盘更新能重新渲染 ARIA 值。 */
function ResizeFixture({ onCommit }: { onCommit: (ratio: number) => void }): ReactElement {
  const [ratio, setRatio] = useState(SIDEBAR_RATIO_DEFAULT);
  return (
    <NavigationResizeHandle
      ratio={ratio}
      minRatio={SIDEBAR_RATIO_MIN}
      maxRatio={SIDEBAR_RATIO_MAX}
      onPreview={setRatio}
      onCommit={(next) => {
        setRatio(next);
        onCommit(next);
      }}
    />
  );
}

/** jsdom 未实现 PointerEvent，因此显式提供 pointer 坐标以贴近真实 WebView 输入。 */
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

afterEach(() => {
  cleanup();
});

describe("NavigationSidebar", () => {
  it("keeps new conversation and general history available without an active project", () => {
    render(
      <NavigationSidebar
        {...sidebarProps({ currentWorkspaceId: undefined, generalWorkspaceSelected: true })}
      />,
    );

    expect(screen.getByRole("button", { name: "新会话" })).toBeVisible();
    expect(screen.getByRole("button", { name: "搜索对话" })).toBeVisible();
    expect(screen.getByRole("button", { name: "添加项目" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "选择项目" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "项目" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "最近对话" })).toBeVisible();
    expect(screen.getByRole("button", { name: "当前范围：无项目对话" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("button", { name: "修复导航 就绪" })).toBeVisible();
    expect(screen.queryByText(/尚未选择项目/)).not.toBeInTheDocument();
    expect(screen.queryByText("Ja")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "收起导航栏" })).not.toBeInTheDocument();
  });

  it("routes the search action beside new conversation to the application-owned dialog", async () => {
    const user = userEvent.setup();
    const onOpenConversationSearch = vi.fn();
    render(
      <NavigationSidebar
        {...sidebarProps({
          threads: [
            thread,
            { ...thread, threadId: "thread-2", title: "设计设置页", status: "active" },
          ],
          onOpenConversationSearch,
        })}
      />,
    );

    await user.click(screen.getByRole("button", { name: "搜索对话" }));
    expect(onOpenConversationSearch).toHaveBeenCalledOnce();
    expect(screen.getByRole("button", { name: "修复导航 就绪" })).toBeVisible();
  });

  it("uses an independent project list with selected runtime state and routes every action", async () => {
    const user = userEvent.setup();
    const props = sidebarProps();
    render(<NavigationSidebar {...props} />);

    const projectList = screen.getByRole("list", { name: "项目列表" });
    expect(within(projectList).getByRole("button", { name: "切换到无项目对话" })).toBeVisible();
    expect(within(projectList).getByRole("button", { name: "当前项目：ja" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(
      within(projectList).getByRole("button", { name: "切换到项目：agent-studio" }),
    ).not.toHaveAttribute("aria-current");

    await user.click(screen.getByRole("button", { name: "新会话" }));
    await user.click(screen.getByRole("button", { name: "添加项目" }));
    await user.click(screen.getByRole("button", { name: "切换到无项目对话" }));
    await user.click(screen.getByRole("button", { name: "切换到项目：agent-studio" }));
    await user.click(screen.getByRole("button", { name: "设置" }));
    await user.click(screen.getByRole("button", { name: "修复导航 就绪" }));

    expect(props.onNewConversation).toHaveBeenCalledOnce();
    expect(props.onChooseProject).toHaveBeenCalledOnce();
    expect(props.onSelectGeneral).toHaveBeenCalledOnce();
    expect(props.onSelectProject).toHaveBeenCalledWith("workspace-2");
    expect(props.onOpenSettings).toHaveBeenCalledOnce();
    expect(props.onSelectConversation).toHaveBeenCalledWith("thread-1");
    expect(screen.getByRole("status", { name: "本地运行时：已连接" })).toHaveTextContent(
      "本地运行时已连接",
    );

    const footer = screen.getByRole("contentinfo");
    expect(within(footer).getByRole("status")).toHaveAccessibleName("本地运行时：已连接");
    expect(within(footer).getByRole("button")).toHaveAccessibleName("设置");
  });

  it("folds projects and recent conversations with explicit expanded semantics", async () => {
    const user = userEvent.setup();
    render(<CollapsibleSidebarFixture />);

    const projectToggle = screen.getByRole("button", { name: "折叠项目" });
    const historyToggle = screen.getByRole("button", { name: "折叠最近对话" });
    expect(projectToggle).toHaveAttribute("aria-expanded", "true");
    expect(historyToggle).toHaveAttribute("aria-expanded", "true");

    await user.click(projectToggle);
    expect(screen.getByRole("button", { name: "展开项目" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(screen.queryByRole("list", { name: "项目列表" })).not.toBeInTheDocument();

    await user.click(historyToggle);
    expect(screen.getByRole("button", { name: "展开最近对话" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
    expect(screen.queryByRole("list", { name: "最近对话列表" })).not.toBeInTheDocument();
  });

  it("disables every scope row while a project transition is in flight", () => {
    render(<NavigationSidebar {...sidebarProps({ projectBusy: true })} />);

    expect(screen.getByRole("button", { name: "切换到无项目对话" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "当前项目：ja" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "切换到项目：agent-studio" })).toBeDisabled();
  });

  it("exposes native shortcut metadata and closes compact drawers after selection", async () => {
    const user = userEvent.setup();
    const onRequestClose = vi.fn();
    const props = sidebarProps({ compact: true, platform: "macos", onRequestClose });
    render(<NavigationSidebar {...props} />);

    expect(screen.getByRole("button", { name: "新会话" })).toHaveAttribute(
      "aria-keyshortcuts",
      "Meta+N",
    );
    expect(screen.getByRole("button", { name: "新会话" })).toHaveAttribute("title", "新会话（⌘N）");
    expect(
      screen.getByRole("button", { name: "新会话" }).querySelector(".lucide-square-pen"),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "选择项目" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "添加项目" })).toHaveAttribute(
      "aria-keyshortcuts",
      "Enter",
    );
    expect(screen.getByRole("button", { name: "搜索对话" })).toHaveAttribute(
      "aria-keyshortcuts",
      "Meta+K",
    );

    await user.click(screen.getByRole("button", { name: "搜索对话" }));
    expect(onRequestClose).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "切换到项目：agent-studio" }));
    expect(onRequestClose).toHaveBeenCalledOnce();
  });

  it("contains rejected action promises and still closes a compact drawer", async () => {
    const user = userEvent.setup();
    const onRequestClose = vi.fn();
    render(
      <NavigationSidebar
        {...sidebarProps({
          compact: true,
          onRequestClose,
          onNewConversation: vi.fn(async () => {
            throw new Error("native rejected");
          }),
        })}
      />,
    );

    await expect(
      user.click(screen.getByRole("button", { name: "新会话" })),
    ).resolves.toBeUndefined();
    expect(onRequestClose).toHaveBeenCalledOnce();
  });

  it("retains busy, error, and empty history states without coupling them to projects", () => {
    const { rerender } = render(
      <NavigationSidebar {...sidebarProps({ projects: [], threads: [], historyBusy: true })} />,
    );
    expect(screen.getByRole("status", { name: "" })).toHaveTextContent("正在读取会话");

    rerender(
      <NavigationSidebar
        {...sidebarProps({
          projects: [],
          threads: [],
          historyBusy: false,
          historyError: "历史读取失败",
        })}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("历史读取失败");

    rerender(
      <NavigationSidebar {...sidebarProps({ projects: [], threads: [], historyBusy: false })} />,
    );
    expect(screen.getByText("还没有历史对话。")).toBeVisible();
  });

  it("shows independent project catalog progress and routes a visible retry", async () => {
    const user = userEvent.setup();
    const onRetryProjects = vi.fn();
    const { rerender } = render(
      <NavigationSidebar
        {...sidebarProps({ projects: [], projectCatalogLoading: true, onRetryProjects })}
      />,
    );
    expect(screen.getByRole("status", { name: "" })).toHaveTextContent("正在读取项目");

    rerender(
      <NavigationSidebar
        {...sidebarProps({
          projects: [],
          projectCatalogLoading: false,
          projectCatalogError: "项目列表暂时不可用，请重试。",
          onRetryProjects,
        })}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("项目列表暂时不可用");
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(onRetryProjects).toHaveBeenCalledOnce();
  });
});

describe("NavigationResizeHandle", () => {
  it("supports pointer capture cleanup and bounded keyboard resizing", () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1000 });
    const onCommit = vi.fn();
    render(<ResizeFixture onCommit={onCommit} />);
    const handle = screen.getByRole("separator", { name: "调整导航栏宽度" });
    Object.assign(handle, {
      setPointerCapture: vi.fn(),
      hasPointerCapture: vi.fn(() => true),
      releasePointerCapture: vi.fn(),
    });

    dispatchPointer(handle, "pointerdown", { button: 0, pointerId: 7, clientX: 100 });
    dispatchPointer(window, "pointermove", { pointerId: 7, clientX: 140 });
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_DEFAULT + 4));
    dispatchPointer(window, "pointerup", { pointerId: 7, clientX: 140 });
    expect(onCommit).toHaveBeenLastCalledWith(SIDEBAR_RATIO_DEFAULT + 4);

    handle.focus();
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_DEFAULT + 4.5));
    fireEvent.keyDown(handle, { key: "Home" });
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_MIN));
    fireEvent.keyDown(handle, { key: "End" });
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_MAX));
  });
});
