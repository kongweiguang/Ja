// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Archive,
  ChevronRight,
  CircleAlert,
  CircleMinus,
  CirclePause,
  CircleX,
  LoaderCircle,
  MoreHorizontal,
  Pencil,
  Pin,
  PinOff,
  Plus,
  Search,
  Settings2,
  SquarePen,
  type LucideIcon,
} from "lucide-react";
import { memo, useState, type ReactElement, type ReactNode, type SyntheticEvent } from "react";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuTrigger,
  Popover,
  PopoverContent,
  PopoverTrigger,
  Tooltip,
} from "@/shared/ui/primitives";
import { WorkspaceScopeRow } from "@/features/workspace";
import type { DesktopPlatform, ThreadProjection } from "../domain/navigationModels";
import {
  navigationShortcut,
  type NavigationCommand,
  type NavigationShortcut,
} from "../domain/shortcuts";
import "./navigation.css";
import { ConversationRenameDialog } from "./ConversationRenameDialog";

/** 侧栏只接收最小项目投影，持久化细节必须留在视图之外。 */
interface NavigationProject {
  workspaceId: string;
  displayName: string;
}

/** 实时状态与未读提醒使用同一无障碍命名入口，但终态成功不冒充持续运行状态。 */
function turnStatusLabel(status: NonNullable<ThreadProjection["latestTurnStatus"]>): string {
  return {
    queued: "等待回复",
    running: "正在回复",
    waiting_approval: "等待批准",
    suspended: "回复已暂停",
    completed: "有新回复",
    failed: "回复失败",
    cancelled: "回复已取消",
  }[status];
}

/**
 * 实时阶段与 cancelled 状态始终可见；completed/failed 只在服务端权威未读时提示。
 * cancelled 的中性图标只说明最近结果，并不进入未读边界；Pin 同样保持独立语义。
 */
function ThreadTurnStatus({
  thread,
  active,
}: {
  thread: ThreadProjection;
  active: boolean;
}): ReactElement | null {
  const status = thread.latestTurnStatus;
  if (status === null || (["completed", "failed"].includes(status) && thread.latestTurnSeen))
    return null;
  const label = turnStatusLabel(status);
  const Icon = {
    queued: LoaderCircle,
    running: LoaderCircle,
    waiting_approval: CircleAlert,
    suspended: CirclePause,
    completed: undefined,
    failed: CircleX,
    cancelled: CircleMinus,
  }[status];
  return (
    <Tooltip content={label}>
      <span className={`ja-navigation-thread-state is-${status}`} role="img" aria-label={label}>
        {Icon === undefined ? (
          <span className="ja-navigation-thread-complete-dot" />
        ) : (
          <Icon aria-hidden="true" />
        )}
        {active ? (
          <span className="ja-visually-hidden" aria-live="polite">
            {label}
          </span>
        ) : null}
      </span>
    </Tooltip>
  );
}

/** 描述顶层动作；主会话入口显示文字标签，搜索入口保持紧凑，避免混淆主次。 */
interface NavigationTopAction {
  id: "new-conversation" | "search-conversations";
  accessibleLabel: string;
  visibleLabel?: string;
  iconOnly: boolean;
  closeCompact: boolean;
  icon: LucideIcon;
  enabled: boolean;
  active: boolean;
  shortcut?: NavigationShortcut;
  onSelect: () => void | Promise<void>;
}

export interface NavigationSidebarProps {
  projects: NavigationProject[];
  projectCatalogLoading: boolean;
  projectCatalogError?: string;
  currentWorkspaceId?: string;
  generalWorkspaceSelected: boolean;
  projectSectionCollapsed: boolean;
  historySectionCollapsed: boolean;
  runtimeLabel: string;
  runtimeIssueReason?: string;
  runtimeIssueContent?: ReactNode;
  runtimeTone: "ready" | "busy" | "warning" | "danger" | "idle";
  currentThreadId?: string;
  threads: ThreadProjection[];
  historyBusy: boolean;
  historyError?: string;
  newConversationDisabled: boolean;
  projectBusy: boolean;
  compact: boolean;
  platform: DesktopPlatform;
  activeAction: "workspace" | "settings";
  conversationSearchOpen: boolean;
  onNewConversation: () => void | Promise<void>;
  onSelectConversation: (threadId: string) => void | Promise<void>;
  onOpenConversationSearch: () => void;
  onRenameConversation: (threadId: string, title: string) => Promise<void>;
  onPinConversation: (threadId: string, pinned: boolean) => Promise<void>;
  onArchiveConversation: (threadId: string) => Promise<void>;
  mutatingThreadIds: readonly string[];
  onChooseProject: () => void | Promise<void>;
  onSelectGeneral: () => void | Promise<void>;
  onSelectProject: (workspaceId: string) => void | Promise<void>;
  onProjectSectionCollapsedChange: (collapsed: boolean) => void;
  onHistorySectionCollapsedChange: (collapsed: boolean) => void;
  onRetryProjects: () => void | Promise<void>;
  onOpenSettings: () => void | Promise<void>;
  /** 侧栏可见性由标题栏持有；该回调仅在紧凑抽屉完成选择后使用。 */
  onRequestClose: () => void;
}

/** 将原生/平台快捷键转换为紧凑标题，同时为鼠标用户保留键盘提示。 */
function actionTitle(label: string, shortcut?: NavigationShortcut): string {
  return shortcut === undefined ? `${label}（Enter）` : `${label}（${shortcut.display}）`;
}

/** 构建主工具栏，让对话搜索紧邻新建入口且不冒充 Workspace 搜索。 */
function buildTopActions(props: NavigationSidebarProps): NavigationTopAction[] {
  const shortcut = (command: NavigationCommand): NavigationShortcut =>
    navigationShortcut(command, props.platform);
  return [
    {
      id: "new-conversation",
      accessibleLabel: "新会话",
      visibleLabel: "新会话",
      iconOnly: false,
      closeCompact: true,
      icon: SquarePen,
      enabled: !props.newConversationDisabled,
      active: false,
      shortcut: shortcut("new-conversation"),
      onSelect: props.onNewConversation,
    },
    {
      id: "search-conversations",
      accessibleLabel: "搜索对话",
      iconOnly: true,
      closeCompact: false,
      icon: Search,
      enabled: true,
      active: props.conversationSearchOpen,
      shortcut: navigationShortcut("search-conversations", props.platform),
      onSelect: props.onOpenConversationSearch,
    },
  ];
}

/** 收口导航适配器的拒绝，并仅在动作结束后关闭紧凑抽屉，避免交互状态悬空。 */
function runNavigationAction(
  action: () => void | Promise<void>,
  closeCompact: boolean,
  onRequestClose: () => void,
): void {
  try {
    void Promise.resolve(action())
      .catch(() => undefined)
      .finally(() => {
        if (closeCompact) onRequestClose();
      });
  } catch {
    if (closeCompact) onRequestClose();
  }
}

/** 用稳定无障碍名称、Tooltip、快捷键元数据和抽屉行为渲染单个工具栏动作。 */
function TopActionButton({
  action,
  compact,
  onRequestClose,
}: {
  action: NavigationTopAction;
  compact: boolean;
  onRequestClose: () => void;
}): ReactElement {
  const Icon = action.icon;
  const title = actionTitle(action.accessibleLabel, action.shortcut);
  if (action.iconOnly) {
    return (
      <IconButton
        className="ja-navigation-action is-icon-only"
        data-active={action.active || undefined}
        label={action.accessibleLabel}
        tooltip={title}
        aria-keyshortcuts={action.shortcut?.aria}
        aria-current={action.active ? "page" : undefined}
        disabled={!action.enabled}
        onClick={() => {
          if (action.enabled)
            runNavigationAction(action.onSelect, action.closeCompact && compact, onRequestClose);
        }}
      >
        <Icon aria-hidden="true" focusable="false" />
      </IconButton>
    );
  }
  return (
    <button
      type="button"
      className="ja-navigation-action is-text"
      data-active={action.active || undefined}
      aria-label={action.accessibleLabel}
      aria-keyshortcuts={action.shortcut?.aria}
      aria-current={action.active ? "page" : undefined}
      title={title}
      disabled={!action.enabled}
      onClick={() => {
        if (action.enabled)
          runNavigationAction(action.onSelect, action.closeCompact && compact, onRequestClose);
      }}
    >
      <Icon aria-hidden="true" focusable="false" />
      {action.visibleLabel === undefined ? null : <span>{action.visibleLabel}</span>}
    </button>
  );
}

/** 独立渲染单个项目行，使选择项目不会改变历史列表的状态所有权或布局。 */
function ProjectRow({
  project,
  selected,
  runtimeTone,
  compact,
  disabled,
  onSelect,
  onRequestClose,
}: {
  project: NavigationProject;
  selected: boolean;
  runtimeTone: NavigationSidebarProps["runtimeTone"];
  compact: boolean;
  disabled: boolean;
  onSelect: (workspaceId: string) => void | Promise<void>;
  onRequestClose: () => void;
}): ReactElement {
  const displayName = project.displayName.trim() || "未命名项目";
  const title = selected ? `当前项目：${displayName}` : `切换到项目：${displayName}`;
  return (
    <WorkspaceScopeRow
      kind="project"
      label={displayName}
      selected={selected}
      disabled={disabled}
      accessibleLabel={title}
      title={actionTitle(title)}
      statusTone={selected ? runtimeTone : undefined}
      onSelect={() =>
        runNavigationAction(() => onSelect(project.workspaceId), compact, onRequestClose)
      }
    />
  );
}

/**
 * 将受管的 general workspace 作为项目范围列表中的稳定首项；显式入口比清除按钮更易发现，
 * 也让“项目 → 无项目对话”的返回路径拥有与项目切换相同的状态和忙碌约束。
 */
function GeneralWorkspaceRow({
  selected,
  runtimeTone,
  compact,
  disabled,
  onSelect,
  onRequestClose,
}: {
  selected: boolean;
  runtimeTone: NavigationSidebarProps["runtimeTone"];
  compact: boolean;
  disabled: boolean;
  onSelect: () => void | Promise<void>;
  onRequestClose: () => void;
}): ReactElement {
  const title = selected ? "当前范围：无项目对话" : "切换到无项目对话";
  return (
    <WorkspaceScopeRow
      kind="general"
      label="无项目对话"
      selected={selected}
      disabled={disabled}
      accessibleLabel={title}
      title={actionTitle(title)}
      statusTone={selected ? runtimeTone : undefined}
      onSelect={() => runNavigationAction(onSelect, compact, onRequestClose)}
    />
  );
}

/** 历史行同时呈现持久状态文案和 active thread 标记，供键盘与屏幕阅读器导航共享。 */
function HistoryRow({
  thread,
  active,
  compact,
  onSelect,
  onRequestRename,
  onPin,
  onArchive,
  pending,
  onRequestClose,
}: {
  thread: ThreadProjection;
  active: boolean;
  compact: boolean;
  onSelect: (threadId: string) => void | Promise<void>;
  onRequestRename: (thread: ThreadProjection) => void;
  onPin: (threadId: string, pinned: boolean) => Promise<void>;
  onArchive: (threadId: string) => Promise<void>;
  pending: boolean;
  onRequestClose: () => void;
}): ReactElement {
  const title = thread.title || "未命名对话";
  const canArchive =
    thread.latestTurnStatus === null ||
    ["completed", "failed", "cancelled"].includes(thread.latestTurnStatus);
  const archiveLabel = canArchive ? "归档" : "回复结束后可归档";
  const stopRowAction = (event: SyntheticEvent): void => event.stopPropagation();
  return (
    <div
      className="ja-navigation-thread-row"
      data-active={active || undefined}
      data-pending={pending || undefined}
    >
      <button
        type="button"
        className="ja-navigation-thread"
        data-thread-id={thread.threadId}
        data-active={active || undefined}
        aria-current={active ? "page" : undefined}
        aria-label={title}
        title={actionTitle(title)}
        onClick={() =>
          runNavigationAction(() => onSelect(thread.threadId), compact, onRequestClose)
        }
      >
        <span>{title}</span>
        <span className="ja-navigation-thread-static" aria-hidden={false}>
          {thread.pinned ? (
            <Tooltip content="已置顶">
              <span className="ja-navigation-thread-pin" role="img" aria-label="已置顶">
                <Pin aria-hidden="true" />
              </span>
            </Tooltip>
          ) : null}
          <ThreadTurnStatus thread={thread} active={active} />
        </span>
      </button>
      <div
        className="ja-navigation-thread-actions"
        onClick={stopRowAction}
        onPointerDown={stopRowAction}
      >
        <IconButton
          className="ja-navigation-thread-action is-pin"
          label={thread.pinned ? "取消置顶" : "置顶"}
          tooltip={thread.pinned ? "取消置顶" : "置顶"}
          aria-disabled={pending || undefined}
          onClick={() => {
            if (!pending) void onPin(thread.threadId, !thread.pinned);
          }}
        >
          {thread.pinned ? <PinOff aria-hidden="true" /> : <Pin aria-hidden="true" />}
        </IconButton>
        <IconButton
          className="ja-navigation-thread-action is-archive"
          label={archiveLabel}
          tooltip={archiveLabel}
          aria-disabled={pending || !canArchive || undefined}
          onClick={() => {
            if (!pending && canArchive) void onArchive(thread.threadId);
          }}
        >
          <Archive aria-hidden="true" />
        </IconButton>
        <Menu>
          <MenuTrigger asChild>
            <IconButton
              className="ja-navigation-thread-action ja-navigation-thread-menu"
              label={`对话菜单：${title}`}
              tooltip="对话菜单"
              aria-disabled={pending || undefined}
            >
              <MoreHorizontal aria-hidden="true" />
            </IconButton>
          </MenuTrigger>
          <MenuContent align="end" onCloseAutoFocus={(event) => event.preventDefault()}>
            <MenuItem
              disabled={pending}
              onSelect={() => void onPin(thread.threadId, !thread.pinned)}
            >
              {thread.pinned ? <PinOff aria-hidden="true" /> : <Pin aria-hidden="true" />}
              {thread.pinned ? "取消置顶" : "置顶"}
            </MenuItem>
            <MenuItem disabled={pending} onSelect={() => onRequestRename(thread)}>
              <Pencil aria-hidden="true" />
              重命名
            </MenuItem>
            <MenuItem
              disabled={pending || !canArchive}
              title={canArchive ? undefined : archiveLabel}
              onSelect={() => void onArchive(thread.threadId)}
            >
              <Archive aria-hidden="true" />
              归档
            </MenuItem>
          </MenuContent>
        </Menu>
      </div>
    </div>
  );
}

/** 项目创建入口紧邻分区标题；文本搜索只属于上方对话历史，避免职责混杂。 */
function ProjectSectionActions({ props }: { props: NavigationSidebarProps }): ReactElement {
  return (
    <div className="ja-navigation-section-actions">
      <IconButton
        className="ja-navigation-section-action"
        label="添加项目"
        tooltip="添加项目（Enter）"
        aria-keyshortcuts="Enter"
        disabled={props.projectBusy}
        onClick={() => {
          if (!props.projectBusy)
            runNavigationAction(props.onChooseProject, props.compact, props.onRequestClose);
        }}
      >
        <Plus aria-hidden="true" focusable="false" />
      </IconButton>
    </div>
  );
}

/** 用一致的 Chevron 与 Radix 状态呈现分组开闭，标题本身保持简短且可被扫描。 */
function SectionToggle({ title, open }: { title: string; open: boolean }): ReactElement {
  return (
    <CollapsibleTrigger asChild>
      <button
        type="button"
        className="ja-navigation-section-toggle"
        aria-label={`${open ? "折叠" : "展开"}${title}`}
        title={`${open ? "折叠" : "展开"}${title}`}
      >
        <ChevronRight aria-hidden="true" focusable="false" />
        <span>{title}</span>
      </button>
    </CollapsibleTrigger>
  );
}

/**
 * 按 Codex 结构渲染侧栏，并用 props identity 隔离 Timeline 高频更新；真实目录、选择或状态变化
 * 仍正常提交，流式正文与 Tool metadata 不应让整个导航树重复渲染或重启动画。
 * 异常详情在状态旁按需打开并居中排版，悬停只解释原因，恢复操作仍由组合层持有。
 */
export const NavigationSidebar = memo(function NavigationSidebar(
  props: NavigationSidebarProps,
): ReactElement {
  const [renameThread, setRenameThread] = useState<ThreadProjection>();
  const topActions = buildTopActions(props);
  const settingsShortcut = navigationShortcut("open-settings", props.platform);

  return (
    <aside
      className="ja-navigation-sidebar"
      aria-label="项目与对话导航"
      data-compact={props.compact || undefined}
    >
      <nav className="ja-navigation-toolbar" aria-label="快捷操作">
        {topActions.map((action) => (
          <TopActionButton
            key={action.id}
            action={action}
            compact={props.compact}
            onRequestClose={props.onRequestClose}
          />
        ))}
      </nav>

      <div className="ja-navigation-content">
        <Collapsible
          asChild
          open={!props.projectSectionCollapsed}
          onOpenChange={(open) => props.onProjectSectionCollapsedChange(!open)}
        >
          <section
            className="ja-navigation-section ja-navigation-projects"
            aria-labelledby="ja-navigation-projects-title"
          >
            <div className="ja-navigation-section-heading">
              <h2 id="ja-navigation-projects-title" aria-label="项目">
                <SectionToggle title="项目" open={!props.projectSectionCollapsed} />
              </h2>
              <ProjectSectionActions props={props} />
            </div>
            <CollapsibleContent className="ja-navigation-section-content">
              <div className="ja-navigation-project-list" role="list" aria-label="项目列表">
                <div role="listitem">
                  <GeneralWorkspaceRow
                    selected={props.generalWorkspaceSelected}
                    runtimeTone={props.runtimeTone}
                    compact={props.compact}
                    disabled={props.projectBusy}
                    onSelect={props.onSelectGeneral}
                    onRequestClose={props.onRequestClose}
                  />
                </div>
                {props.projects.map((project) => (
                  <div key={project.workspaceId} role="listitem">
                    <ProjectRow
                      project={project}
                      selected={project.workspaceId === props.currentWorkspaceId}
                      runtimeTone={props.runtimeTone}
                      compact={props.compact}
                      disabled={props.projectBusy}
                      onSelect={props.onSelectProject}
                      onRequestClose={props.onRequestClose}
                    />
                  </div>
                ))}
              </div>
              {props.projectCatalogLoading ? (
                <p className="ja-navigation-catalog-status" role="status">
                  正在读取项目…
                </p>
              ) : null}
              {props.projectCatalogError === undefined ? null : (
                <div className="ja-navigation-catalog-error" role="alert">
                  <span>{props.projectCatalogError}</span>
                  <button
                    type="button"
                    disabled={props.projectCatalogLoading}
                    onClick={() =>
                      runNavigationAction(props.onRetryProjects, false, props.onRequestClose)
                    }
                  >
                    重试
                  </button>
                </div>
              )}
            </CollapsibleContent>
          </section>
        </Collapsible>

        <Collapsible
          asChild
          open={!props.historySectionCollapsed}
          onOpenChange={(open) => props.onHistorySectionCollapsedChange(!open)}
        >
          <section
            className="ja-navigation-section ja-navigation-history"
            aria-labelledby="ja-navigation-history-title"
          >
            <div className="ja-navigation-section-heading">
              <h2 id="ja-navigation-history-title" aria-label="最近对话">
                <SectionToggle title="最近对话" open={!props.historySectionCollapsed} />
              </h2>
            </div>
            <CollapsibleContent className="ja-navigation-section-content">
              <div className="ja-navigation-history-list" role="list" aria-label="最近对话列表">
                {props.historyBusy && props.threads.length === 0 ? (
                  <p className="ja-navigation-empty" role="status">
                    正在读取会话…
                  </p>
                ) : null}
                {props.historyError === undefined ? null : (
                  <p className="ja-navigation-error" role="alert">
                    {props.historyError}
                  </p>
                )}
                {!props.historyBusy &&
                props.threads.length === 0 &&
                props.historyError === undefined ? (
                  <p className="ja-navigation-empty">还没有历史对话。</p>
                ) : null}
                {props.threads.map((thread) => (
                  <div key={thread.threadId} role="listitem">
                    <HistoryRow
                      thread={thread}
                      active={props.currentThreadId === thread.threadId}
                      compact={props.compact}
                      onSelect={props.onSelectConversation}
                      onRequestRename={setRenameThread}
                      onPin={props.onPinConversation}
                      onArchive={props.onArchiveConversation}
                      pending={props.mutatingThreadIds.includes(thread.threadId)}
                      onRequestClose={props.onRequestClose}
                    />
                  </div>
                ))}
              </div>
            </CollapsibleContent>
          </section>
        </Collapsible>
      </div>

      <footer className="ja-navigation-footer">
        <div
          className="ja-navigation-runtime"
          role="status"
          aria-label={`本地运行时：${props.runtimeLabel}`}
          title={`本地运行时：${props.runtimeLabel}`}
        >
          <span className={`ja-navigation-status-dot is-${props.runtimeTone}`} aria-hidden="true" />
          <span className="ja-navigation-runtime-label">
            <strong>本地运行时</strong>
            <small aria-live="polite">{props.runtimeLabel}</small>
          </span>
          {props.runtimeIssueReason === undefined ? null : (
            <Popover>
              <Tooltip content={props.runtimeIssueReason}>
                <PopoverTrigger asChild>
                  <button
                    type="button"
                    className="ja-navigation-runtime-issue"
                    aria-label="运行时异常详情"
                  >
                    <CircleAlert aria-hidden="true" />
                  </button>
                </PopoverTrigger>
              </Tooltip>
              <PopoverContent
                className="ja-navigation-runtime-popover"
                side="top"
                align="center"
                sideOffset={10}
                collisionPadding={12}
                aria-label="运行时异常详情"
              >
                {props.runtimeIssueContent ?? props.runtimeIssueReason}
              </PopoverContent>
            </Popover>
          )}
        </div>
        <button
          type="button"
          className="ja-navigation-settings"
          data-active={props.activeAction === "settings" || undefined}
          aria-label="设置"
          aria-current={props.activeAction === "settings" ? "page" : undefined}
          aria-keyshortcuts={settingsShortcut.aria}
          title={actionTitle("设置", settingsShortcut)}
          onClick={() =>
            runNavigationAction(props.onOpenSettings, props.compact, props.onRequestClose)
          }
        >
          <Settings2 aria-hidden="true" focusable="false" />
          <span>设置</span>
        </button>
      </footer>
      <ConversationRenameDialog
        thread={renameThread}
        open={renameThread !== undefined}
        onOpenChange={(open) => {
          if (!open) setRenameThread(undefined);
        }}
        onRename={props.onRenameConversation}
      />
    </aside>
  );
});
