// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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
  pinned: false,
  latestTurnStatus: "completed",
  latestTurnSeen: false,
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
    noProjectSelected: false,
    projectSectionCollapsed: false,
    historySectionCollapsed: false,
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
    onOpenProjectFolder: vi.fn(async () => undefined),
    onOpenConversationSearch: vi.fn(),
    onRenameConversation: vi.fn(async () => undefined),
    onPinConversation: vi.fn(async () => undefined),
    onArchiveConversation: vi.fn(async () => undefined),
    mutatingThreadIds: [],
    onChooseProject: vi.fn(),
    onSelectNoProject: vi.fn(),
    onSelectProject: vi.fn(),
    onProjectSectionCollapsedChange: vi.fn(),
    onHistorySectionCollapsedChange: vi.fn(),
    onRetryProjects: vi.fn(),
    onOpenSettings: vi.fn(),
    onRequestClose: vi.fn(),
    onOpenWorkspaceFolder: vi.fn(async () => undefined),
    onOpenLegacySharedFolder: vi.fn(async () => undefined),
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
    x: 100,
    y: 100,
    top: 100,
    right: 111,
    bottom: 500,
    left: 100,
    width: 11,
    height: 400,
    toJSON: () => ({}),
  });
}

afterEach(() => {
  cleanup();
});

describe("NavigationSidebar", () => {
  /** Runtime 展示已移出导航，底部只保留设置入口。 */
  it("omits the runtime status block while keeping Settings available", () => {
    render(<NavigationSidebar {...sidebarProps({ runtimeTone: "danger" })} />);

    expect(screen.queryByRole("status", { name: /本地运行时/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "运行时异常详情" })).not.toBeInTheDocument();
    expect(screen.getByRole("contentinfo").querySelector(".ja-navigation-runtime")).toBeNull();
    expect(screen.getByRole("button", { name: "设置" })).toBeVisible();
  });

  it("keeps new conversation and general history available without an active project", () => {
    render(
      <NavigationSidebar
        {...sidebarProps({ currentWorkspaceId: undefined, noProjectSelected: true })}
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
    expect(screen.getByRole("button", { name: "修复导航" })).toBeVisible();
    expect(screen.queryByText("就绪")).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: "有新回复" })).toBeVisible();
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
    expect(screen.getByRole("button", { name: "修复导航" })).toBeVisible();
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
    await user.click(screen.getByRole("button", { name: "修复导航" }));

    expect(props.onNewConversation).toHaveBeenCalledOnce();
    expect(props.onChooseProject).toHaveBeenCalledOnce();
    expect(props.onSelectNoProject).toHaveBeenCalledOnce();
    expect(props.onSelectProject).toHaveBeenCalledWith("workspace-2");
    expect(props.onOpenSettings).toHaveBeenCalledOnce();
    expect(props.onSelectConversation).toHaveBeenCalledWith("thread-1");

    const footer = screen.getByRole("contentinfo");
    expect(within(footer).getByRole("button")).toHaveAccessibleName("设置");
  });

  /** 项目右键以行内 Workspace ID 打开 Explorer，不经过会改变当前项目的选择动作。 */
  it("opens a project directory from the project row context menu", async () => {
    const props = sidebarProps();
    render(<NavigationSidebar {...props} />);

    const project = screen.getByRole("button", { name: "切换到项目：agent-studio" });
    fireEvent.contextMenu(project, { clientX: 48, clientY: 64 });
    const openFolder = await screen.findByRole("menuitem", {
      name: "在资源管理器中打开项目目录",
    });
    fireEvent.click(openFolder);

    expect(props.onOpenProjectFolder).toHaveBeenCalledExactlyOnceWith("workspace-2");
    expect(props.onSelectProject).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "当前项目：ja" })).toBeVisible();
  });

  /** 项目行同时支持系统菜单键；Escape 应将焦点还给发起菜单的行按钮。 */
  it("opens the project context menu by keyboard and restores focus after Escape", async () => {
    render(<NavigationSidebar {...sidebarProps()} />);
    const project = screen.getByRole("button", { name: "切换到项目：agent-studio" });
    project.focus();
    fireEvent.keyDown(project, { key: "F10", shiftKey: true });

    const openFolder = await screen.findByRole("menuitem", {
      name: "在资源管理器中打开项目目录",
    });
    fireEvent.keyDown(openFolder, { key: "Escape" });
    await waitFor(() => expect(project).toHaveFocus());
  });

  /** 无项目范围没有目录目标，保留 WebView 对空范围行的原生右键行为。 */
  it("does not show a directory menu for the no-project scope row", () => {
    render(
      <NavigationSidebar
        {...sidebarProps({ currentWorkspaceId: undefined, noProjectSelected: true })}
      />,
    );
    fireEvent.contextMenu(screen.getByRole("button", { name: "当前范围：无项目对话" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
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
    expect(screen.getByRole("status", { name: "正在读取会话" })).toBeVisible();
    expect(screen.queryByText("正在读取会话…")).not.toBeInTheDocument();
    expect(screen.queryByText("还没有历史对话。")).not.toBeInTheDocument();

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

  /** 首次空目录恢复显示进度；已有目录的后台校验保持静默，避免标题行反复闪出旋转图标。 */
  it("shows history loading only before the first directory projection", async () => {
    const { rerender } = render(
      <NavigationSidebar
        {...sidebarProps({
          historyBusy: true,
          threads: [],
        })}
      />,
    );

    const heading = screen.getByRole("heading", { name: "最近对话" });
    const loading = screen.getByRole("status", { name: "正在读取会话" });
    expect(heading.parentElement).toContainElement(loading);
    expect(loading.querySelector(".lucide-loader-circle")).toBeInTheDocument();

    rerender(
      <NavigationSidebar
        {...sidebarProps({
          historyBusy: true,
          threads: [thread, { ...thread, threadId: "thread-2", title: "第二个对话" }],
        })}
      />,
    );
    expect(screen.queryByRole("status", { name: "正在读取会话" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "修复导航" })).toBeVisible();
    expect(screen.getByRole("button", { name: "第二个对话" })).toBeVisible();
    expect(screen.getByRole("list", { name: "最近对话列表" })).toHaveAttribute("aria-busy", "true");
  });

  it("keeps an existing history list spatially stable while another thread snapshot loads", () => {
    render(
      <NavigationSidebar
        {...sidebarProps({
          threads: [
            thread,
            { ...thread, threadId: "thread-2", title: "第二个对话", status: "active" },
          ],
          historyBusy: true,
        })}
      />,
    );

    expect(screen.queryByText("正在读取会话…")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "修复导航" })).toBeVisible();
    expect(screen.getByRole("button", { name: "第二个对话" })).toBeVisible();
  });

  /** 实时与取消状态持续可见；成功/失败只呈现未读提醒，已读和空 Thread 不伪造状态。 */
  it("separates live Turn status from persisted unread reminders", () => {
    const statuses = [
      ["queued", "等待回复"],
      ["running", "正在工作"],
      ["waiting_approval", "等待批准"],
      ["suspended", "回复已暂停"],
      ["completed", "有新回复"],
      ["failed", "回复失败"],
    ] as const;
    render(
      <NavigationSidebar
        {...sidebarProps({
          threads: [
            ...statuses.map(([status], index) => ({
              ...thread,
              threadId: `thread-${status}`,
              title: `状态 ${index}`,
              pinned: index === 0,
              latestTurnStatus: status,
            })),
            {
              ...thread,
              threadId: "thread-completed-seen",
              title: "已读成功",
              latestTurnStatus: "completed",
              latestTurnSeen: true,
            },
            {
              ...thread,
              threadId: "thread-failed-seen",
              title: "已读失败",
              latestTurnStatus: "failed",
              latestTurnSeen: true,
            },
            {
              ...thread,
              threadId: "thread-cancelled",
              title: "已取消",
              latestTurnStatus: "cancelled",
              latestTurnSeen: false,
            },
            { ...thread, threadId: "thread-empty", title: "尚无 Turn", latestTurnStatus: null },
          ],
          currentThreadId: "thread-running",
        })}
      />,
    );

    for (const [, label] of statuses)
      expect(screen.getByRole("img", { name: label })).toBeVisible();
    expect(screen.getByRole("img", { name: "已置顶" })).toBeVisible();
    expect(screen.getByRole("button", { name: "尚无 Turn" })).toBeVisible();
    expect(screen.queryByText("就绪")).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: "回复已取消" })).toBeVisible();
    expect(
      screen
        .getByRole("button", { name: "已读成功" })
        .parentElement?.querySelector(".ja-navigation-thread-state"),
    ).toBeNull();
    expect(
      screen
        .getByRole("button", { name: "已读失败" })
        .parentElement?.querySelector(".ja-navigation-thread-state"),
    ).toBeNull();
    expect(
      document.querySelector(".ja-navigation-thread-state.is-running .lucide-loader-circle"),
    ).toBeInTheDocument();
    expect(document.querySelector(".ja-navigation-thread-complete-dot")).toBeInTheDocument();
    expect(
      document.querySelector(".ja-navigation-thread-state.is-failed .lucide-circle-x"),
    ).toBeInTheDocument();
    expect(
      document.querySelector(".ja-navigation-thread-state.is-cancelled .lucide-circle-minus"),
    ).toBeInTheDocument();
  });

  /** 行内快捷动作必须阻止行选择；非终态归档保持可解释但不可提交。 */
  it("routes pin and archive row actions without selecting the conversation", () => {
    const onSelectConversation = vi.fn();
    const onPinConversation = vi.fn(async () => undefined);
    const onArchiveConversation = vi.fn(async () => undefined);
    render(
      <NavigationSidebar
        {...sidebarProps({ onSelectConversation, onPinConversation, onArchiveConversation })}
      />,
    );
    const row = screen
      .getByRole("button", { name: "修复导航" })
      .closest<HTMLElement>(".ja-navigation-thread-row")!;
    fireEvent.click(within(row).getByRole("button", { name: "置顶" }));
    fireEvent.click(within(row).getByRole("button", { name: "归档" }));
    expect(onPinConversation).toHaveBeenCalledWith("thread-1", true);
    expect(onArchiveConversation).toHaveBeenCalledWith("thread-1");
    expect(onSelectConversation).not.toHaveBeenCalled();

    cleanup();
    render(
      <NavigationSidebar
        {...sidebarProps({
          threads: [{ ...thread, latestTurnStatus: "running" }],
          onArchiveConversation,
        })}
      />,
    );
    const busyRow = screen
      .getByRole("button", { name: "修复导航" })
      .closest<HTMLElement>(".ja-navigation-thread-row")!;
    const blocked = within(busyRow).getByRole("button", { name: "回复结束后可归档" });
    expect(blocked).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(blocked);
    expect(onArchiveConversation).toHaveBeenCalledOnce();
  });

  /** 文件夹菜单只按 Thread 身份发起打开，不得把菜单操作变成会话切换。 */
  it("opens a thread folder and exposes the legacy folder only for migrated sessions", async () => {
    const onOpenWorkspaceFolder = vi.fn(async () => undefined);
    const onOpenLegacySharedFolder = vi.fn(async () => undefined);
    const onSelectConversation = vi.fn();
    render(
      <NavigationSidebar
        {...sidebarProps({
          threads: [{ ...thread, legacySharedWorkspaceId: "ws_legacy_shared" }],
          onOpenWorkspaceFolder,
          onOpenLegacySharedFolder,
          onSelectConversation,
        })}
      />,
    );
    fireEvent.keyDown(screen.getByRole("button", { name: "对话菜单：修复导航" }), {
      key: "Enter",
    });
    await screen.findByRole("menuitem", { name: "打开工作文件夹" });
    fireEvent.click(screen.getByRole("menuitem", { name: "打开工作文件夹" }));
    expect(onOpenWorkspaceFolder).toHaveBeenCalledExactlyOnceWith(thread.threadId);
    expect(onSelectConversation).not.toHaveBeenCalled();

    fireEvent.keyDown(screen.getByRole("button", { name: "对话菜单：修复导航" }), {
      key: "Enter",
    });
    await screen.findByRole("menuitem", { name: "打开旧共享文件夹" });
    fireEvent.click(screen.getByRole("menuitem", { name: "打开旧共享文件夹" }));
    expect(onOpenLegacySharedFolder).toHaveBeenCalledExactlyOnceWith(thread.threadId);
    expect(onSelectConversation).not.toHaveBeenCalled();

    cleanup();
    render(<NavigationSidebar {...sidebarProps({ threads: [thread] })} />);
    fireEvent.keyDown(screen.getByRole("button", { name: "对话菜单：修复导航" }), {
      key: "Enter",
    });
    await screen.findByRole("menuitem", { name: "打开工作文件夹" });
    expect(screen.queryByRole("menuitem", { name: "打开旧共享文件夹" })).not.toBeInTheDocument();
  });

  /** 右键菜单绑定指针下 Thread，复用更多菜单项并保留旧共享目录的条件显示。 */
  it("uses the same thread actions from the row context menu without selecting the row", async () => {
    const onOpenWorkspaceFolder = vi.fn(async () => undefined);
    const onSelectConversation = vi.fn();
    render(
      <NavigationSidebar
        {...sidebarProps({
          threads: [
            thread,
            {
              ...thread,
              threadId: "target-thread",
              title: "目标会话",
              pinned: true,
              legacySharedWorkspaceId: "ws_legacy_shared",
            },
          ],
          onOpenWorkspaceFolder,
          onSelectConversation,
        })}
      />,
    );

    fireEvent.contextMenu(screen.getByRole("button", { name: "目标会话" }), {
      clientX: 120,
      clientY: 160,
    });
    expect(await screen.findByRole("menu", { name: "对话操作：目标会话" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "取消置顶" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "重命名" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "打开工作文件夹" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "打开旧共享文件夹" })).toBeVisible();

    fireEvent.click(screen.getByRole("menuitem", { name: "打开工作文件夹" }));
    expect(onOpenWorkspaceFolder).toHaveBeenCalledExactlyOnceWith("target-thread");
    expect(onSelectConversation).not.toHaveBeenCalled();
  });

  /** Thread 正在变更时右键保留状态反馈，但沿用更多菜单的逐项禁用条件。 */
  it("disables thread context actions while that thread is mutating", async () => {
    render(<NavigationSidebar {...sidebarProps({ mutatingThreadIds: [thread.threadId] })} />);
    fireEvent.contextMenu(screen.getByRole("button", { name: "修复导航" }));
    await screen.findByRole("menu", { name: "对话操作：修复导航" });
    for (const name of ["置顶", "重命名", "打开工作文件夹", "归档"]) {
      expect(screen.getByRole("menuitem", { name })).toHaveAttribute("aria-disabled", "true");
    }
  });

  /** 状态覆盖层位于整行按钮之上，点击旋转状态仍必须打开该对话而不是吞掉指针事件。 */
  it("opens a running conversation when its spinner is clicked", () => {
    const onSelectConversation = vi.fn();
    render(
      <NavigationSidebar
        {...sidebarProps({
          threads: [{ ...thread, latestTurnStatus: "running" }],
          onSelectConversation,
        })}
      />,
    );

    const spinner = screen.getByRole("img", { name: "正在工作" });
    const threadButton = screen.getByRole("button", { name: "修复导航" });
    expect(spinner.closest(".ja-navigation-thread")).toBe(threadButton);
    expect(spinner).toHaveClass("ja-navigation-thread-state", "is-running");

    fireEvent.click(spinner);

    expect(onSelectConversation).toHaveBeenCalledWith(thread.threadId);
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

    rerender(
      <NavigationSidebar
        {...sidebarProps({
          projectCatalogLoading: true,
          projectCatalogError: undefined,
        })}
      />,
    );
    expect(screen.queryByText("正在读取项目…")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "当前项目：ja" })).toBeVisible();
  });
});

describe("NavigationResizeHandle", () => {
  // 离开时保留已绘制位置并丢弃排队帧，键盘重新聚焦才恢复中心。
  it("fades at the last painted position and resets only on keyboard focus", () => {
    const frame = installAnimationFrameHarness();
    render(<ResizeFixture onCommit={vi.fn()} />);
    const handle = screen.getByRole("separator", { name: "调整导航栏宽度" });
    installResizeHandleRect(handle);

    expect(handle).toHaveClass("ja-navigation-resize-handle", "ja-resize-handle");
    dispatchPointer(handle, "pointerover", { pointerId: 3, clientY: 200 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("25%");
    dispatchPointer(handle, "pointermove", { pointerId: 3, clientY: 400 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("75%");
    dispatchPointer(handle, "pointermove", { pointerId: 3, clientY: -100 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("0%");
    dispatchPointer(handle, "pointermove", { pointerId: 3, clientY: 700 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("100%");
    dispatchPointer(handle, "pointermove", { pointerId: 3, clientY: 300 });
    dispatchPointer(handle, "pointerout", { pointerId: 3, clientY: 300 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("100%");
    fireEvent.focus(handle);
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("");
  });

  it("keeps spotlight and resize tracking outside the hit area while dragging", () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: 1000 });
    const frame = installAnimationFrameHarness();
    const onCommit = vi.fn();
    render(<ResizeFixture onCommit={onCommit} />);
    const handle = screen.getByRole("separator", { name: "调整导航栏宽度" });
    installResizeHandleRect(handle);
    Object.assign(handle, {
      setPointerCapture: vi.fn(),
      hasPointerCapture: vi.fn(() => true),
      releasePointerCapture: vi.fn(),
    });

    dispatchPointer(handle, "pointerdown", {
      button: 0,
      pointerId: 7,
      clientX: 100,
      clientY: 200,
    });
    frame.flush();
    dispatchPointer(window, "pointermove", { pointerId: 7, clientX: 140, clientY: 400 });
    frame.flush();
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_DEFAULT + 4));
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("75%");
    dispatchPointer(handle, "pointerout", { pointerId: 7, clientY: 400 });
    frame.flush();
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("75%");
    dispatchPointer(window, "pointermove", { pointerId: 7, clientX: 160, clientY: 700 });
    frame.flush();
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_DEFAULT + 6));
    expect(handle.style.getPropertyValue("--ja-resize-pointer-y")).toBe("100%");
    dispatchPointer(window, "pointerup", { pointerId: 7, clientX: 160, clientY: 700 });
    dispatchPointer(window, "pointercancel", { pointerId: 7, clientX: 160, clientY: 700 });
    fireEvent(window, new Event("blur"));
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenLastCalledWith(SIDEBAR_RATIO_DEFAULT + 6);
  });

  it.each(["pointerup", "pointercancel", "blur"] as const)(
    "commits exactly once when %s ends the pointer transaction",
    (endEvent) => {
      Object.defineProperty(window, "innerWidth", { configurable: true, value: 1000 });
      const onCommit = vi.fn();
      render(<ResizeFixture onCommit={onCommit} />);
      const handle = screen.getByRole("separator", { name: "调整导航栏宽度" });
      Object.assign(handle, {
        setPointerCapture: vi.fn(),
        hasPointerCapture: vi.fn(() => true),
        releasePointerCapture: vi.fn(),
      });
      dispatchPointer(handle, "pointerdown", {
        button: 0,
        pointerId: 13,
        clientX: 100,
        clientY: 200,
      });
      dispatchPointer(window, "pointermove", { pointerId: 13, clientX: 120, clientY: 300 });
      if (endEvent === "blur") fireEvent(window, new Event("blur"));
      else dispatchPointer(window, endEvent, { pointerId: 13, clientX: 120, clientY: 300 });
      dispatchPointer(window, "pointerup", { pointerId: 13, clientX: 120, clientY: 300 });
      dispatchPointer(window, "pointercancel", { pointerId: 13, clientX: 120, clientY: 300 });
      fireEvent(window, new Event("blur"));
      expect(onCommit).toHaveBeenCalledTimes(1);
      expect(onCommit).toHaveBeenLastCalledWith(SIDEBAR_RATIO_DEFAULT + 2);
    },
  );

  it("keeps bounded keyboard resizing semantics", () => {
    const onCommit = vi.fn();
    render(<ResizeFixture onCommit={onCommit} />);
    const handle = screen.getByRole("separator", { name: "调整导航栏宽度" });

    handle.focus();
    fireEvent.keyDown(handle, { key: "ArrowRight" });
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_DEFAULT + 0.5));
    fireEvent.keyDown(handle, { key: "Home" });
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_MIN));
    fireEvent.keyDown(handle, { key: "End" });
    expect(handle).toHaveAttribute("aria-valuenow", String(SIDEBAR_RATIO_MAX));
  });
});
