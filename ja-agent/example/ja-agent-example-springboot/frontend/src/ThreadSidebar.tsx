// @author kongweiguang
import { useEffect, useRef, useState } from "react";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import * as Popover from "@radix-ui/react-popover";
import {
  Archive,
  ArchiveRestore,
  ChevronDown,
  LoaderCircle,
  MoreHorizontal,
  Pencil,
  Search,
  SlidersHorizontal,
  SquarePen,
  X,
} from "lucide-react";
import { isTerminal } from "./state";
import type { AppState } from "./state";
import type { ThreadView, ThemePreference } from "./types";

interface ThreadSidebarProps {
  state: AppState;
  collapsed: boolean;
  createBusy: boolean;
  busyThreadId?: string;
  onCreate: () => void;
  onSelect: (threadId: string) => void;
  onPatch: (value: Partial<AppState>) => void;
  onRename: (thread: ThreadView) => void;
  onArchive: (thread: ThreadView) => void;
  onRestore: (thread: ThreadView) => void;
  onLoadMore: () => void;
}

/** 导航保持紧凑，通过 Portal 菜单避免侧栏裁剪，并支持键盘与焦点操作关闭菜单。 */
export function ThreadSidebar({
  state,
  collapsed,
  createBusy,
  busyThreadId,
  onCreate,
  onSelect,
  onPatch,
  onRename,
  onArchive,
  onRestore,
  onLoadMore,
}: ThreadSidebarProps) {
  const [searchOpen, setSearchOpen] = useState(false);
  const [recentOpen, setRecentOpen] = useState(true);
  const searchRef = useRef<HTMLInputElement>(null);
  const searchButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (searchOpen) searchRef.current?.focus();
  }, [searchOpen]);

  /** 关闭搜索时同步清除服务端筛选条件，避免收起的输入框仍悄然隐藏会话。 */
  const closeSearch = () => {
    setSearchOpen(false);
    onPatch({ search: "" });
    searchButtonRef.current?.focus();
  };

  return (
    <aside
      className={`sidebar ${state.sidebarOpen ? "sidebar-open" : ""}`}
      aria-label="会话列表"
      inert={collapsed && !state.sidebarOpen ? true : undefined}
    >
      <div className="sidebar-toolbar">
        <button className="new-thread-button" onClick={onCreate} disabled={createBusy}>
          <span>{createBusy ? <LoaderCircle className="spin" /> : <SquarePen />}</span>
          <span>新会话</span>
        </button>
        <button
          ref={searchButtonRef}
          className={`icon-button ${searchOpen ? "is-selected" : ""}`}
          title="搜索会话"
          aria-label="搜索会话"
          aria-expanded={searchOpen}
          onClick={() => (searchOpen ? closeSearch() : setSearchOpen(true))}
        >
          <Search />
        </button>
        <button
          className="icon-button mobile-only"
          title="关闭会话栏"
          aria-label="关闭会话栏"
          onClick={() => onPatch({ sidebarOpen: false })}
        >
          <X />
        </button>
      </div>
      {searchOpen && (
        <label className="thread-search">
          <Search aria-hidden="true" />
          <input
            ref={searchRef}
            value={state.search}
            onChange={(event) => onPatch({ search: event.target.value })}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                event.stopPropagation();
                closeSearch();
              }
            }}
            placeholder="搜索会话"
            aria-label="搜索会话"
          />
          <button
            className="bare-button"
            title="关闭搜索"
            aria-label="关闭搜索"
            onClick={closeSearch}
          >
            <X />
          </button>
        </label>
      )}
      <div className="sidebar-content">
        <div className="sidebar-section-head">
          <span>Agent</span>
        </div>
        <label className="agent-choice">
          <select
            aria-label="新对话 Agent"
            value={state.newAgentId}
            disabled={createBusy}
            onChange={(event) => onPatch({ newAgentId: event.target.value })}
          >
            {state.agents.map((agent) => (
              <option key={agent.id} value={agent.id}>
                {agent.name}
              </option>
            ))}
          </select>
          <ChevronDown aria-hidden="true" />
        </label>
        <div className="sidebar-section-head recent-section-head">
          <button
            className="section-toggle"
            aria-expanded={recentOpen}
            onClick={() => setRecentOpen(!recentOpen)}
          >
            <ChevronDown className={recentOpen ? "" : "is-collapsed"} />
            {state.archivedView ? "已归档" : "最近对话"}
          </button>
          <button
            className={`icon-button ${state.archivedView ? "is-selected" : ""}`}
            title={state.archivedView ? "返回最近对话" : "查看归档"}
            aria-label={state.archivedView ? "返回最近对话" : "查看归档"}
            onClick={() => {
              setRecentOpen(true);
              onPatch({ archivedView: !state.archivedView });
            }}
          >
            <Archive />
          </button>
        </div>
        {recentOpen && (
          <nav className="thread-list" aria-label={state.archivedView ? "已归档会话" : "最近会话"}>
            {state.threads.map((thread) => {
              const turn = state.sessions[thread.threadId]?.turns.at(-1);
              const active = Boolean(
                turn &&
                  !isTerminal(turn.state) &&
                  turn.state !== "UNKNOWN" &&
                  turn.state !== "HISTORY",
              );
              const working =
                turn && !turn.resumeRequired && ["QUEUED", "RUNNING"].includes(turn.state);
              return (
                <div
                  className={`thread-row ${thread.threadId === state.selectedThreadId ? "active" : ""}`}
                  key={thread.threadId}
                >
                  <button
                    className="thread-select"
                    onClick={() => onSelect(thread.threadId)}
                    title={thread.title}
                    aria-current={thread.threadId === state.selectedThreadId ? "page" : undefined}
                  >
                    <span className="thread-title">{thread.title || "新会话"}</span>
                    {working && (
                      <LoaderCircle className="thread-activity spin" aria-label="正在工作" />
                    )}
                  </button>
                  <DropdownMenu.Root>
                    <DropdownMenu.Trigger
                      className="thread-menu-trigger icon-button"
                      aria-label={`会话操作：${thread.title}`}
                      title="会话操作"
                      disabled={busyThreadId === thread.threadId}
                    >
                      <MoreHorizontal />
                    </DropdownMenu.Trigger>
                    <DropdownMenu.Portal>
                      <DropdownMenu.Content
                        className="thread-menu-popover"
                        align="end"
                        sideOffset={4}
                        collisionPadding={8}
                      >
                        {!state.archivedView && (
                          <DropdownMenu.Item onSelect={() => onRename(thread)}>
                            <Pencil />
                            重命名
                          </DropdownMenu.Item>
                        )}
                        {state.archivedView ? (
                          <DropdownMenu.Item onSelect={() => onRestore(thread)}>
                            <ArchiveRestore />
                            恢复会话
                          </DropdownMenu.Item>
                        ) : (
                          <DropdownMenu.Item disabled={active} onSelect={() => onArchive(thread)}>
                            <Archive />
                            归档
                          </DropdownMenu.Item>
                        )}
                      </DropdownMenu.Content>
                    </DropdownMenu.Portal>
                  </DropdownMenu.Root>
                </div>
              );
            })}
            {!state.threads.length && (
              <div className="sidebar-empty">
                {state.loading
                  ? "正在读取会话"
                  : state.search
                    ? "没有匹配的会话"
                    : state.archivedView
                      ? "没有已归档会话"
                      : "暂无对话"}
              </div>
            )}
            {state.threadHasMore && (
              <button
                className="load-more-threads"
                onClick={onLoadMore}
                disabled={state.threadListLoadingMore}
              >
                {state.threadListLoadingMore ? (
                  <>
                    <LoaderCircle className="spin" />
                    正在读取
                  </>
                ) : (
                  "加载更多会话"
                )}
              </button>
            )}
          </nav>
        )}
      </div>
      <div className="sidebar-footer">
        <Popover.Root>
          <Popover.Trigger className="settings-button">
            <SlidersHorizontal />
            设置
          </Popover.Trigger>
          <Popover.Portal>
            <Popover.Content
              className="appearance-popover"
              side="top"
              align="start"
              sideOffset={12}
              collisionPadding={12}
              aria-label="外观设置"
            >
              <label className="theme-select">
                <span>外观</span>
                <select
                  aria-label="界面主题"
                  value={state.theme}
                  onChange={(event) => onPatch({ theme: event.target.value as ThemePreference })}
                >
                  <option value="system">跟随系统</option>
                  <option value="light">浅色</option>
                  <option value="dark">深色</option>
                </select>
              </label>
            </Popover.Content>
          </Popover.Portal>
        </Popover.Root>
        <span className="sidebar-brand">Ja</span>
      </div>
    </aside>
  );
}
