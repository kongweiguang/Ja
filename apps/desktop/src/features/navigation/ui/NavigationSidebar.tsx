// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Archive,
  ChevronRight,
  CircleAlert,
  CircleMinus,
  CirclePause,
  CircleX,
  FolderOpen,
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
import {
  memo,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type ReactElement,
  type SyntheticEvent,
} from "react";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuTrigger,
  PointerContextMenu,
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

interface NavigationPointerMenu {
  x: number;
  y: number;
  session: number;
}

/** 实时状态与未读提醒使用同一无障碍命名入口，但终态成功不冒充持续运行状态。 */
function turnStatusLabel(status: NonNullable<ThreadProjection["latestTurnStatus"]>): string {
  return {
    queued: "等待回复",
    running: "正在工作",
    waiting_approval: "等待批准",
    suspended: "回复已暂停",
    completed: "有新回复",
    failed: "回复失败",
    cancelled: "回复已取消",
  }[status];
}

/**
 * 实时阶段与 cancelled 状态始终可见；completed/failed 只在服务端权威未读时提示。
 * 只接收状态原子值，使 Thread revision 等无关投影变化不重建 Spinner 并重播合法的旋转动效。
 */
const ThreadTurnStatus = memo(function ThreadTurnStatus({
  status,
  latestTurnSeen,
  active,
}: {
  status: ThreadProjection["latestTurnStatus"];
  latestTurnSeen: boolean;
  active: boolean;
}): ReactElement | null {
  if (status === null || (["completed", "failed"].includes(status) && latestTurnSeen)) return null;
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
});

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
  noProjectSelected: boolean;
  projectSectionCollapsed: boolean;
  historySectionCollapsed: boolean;
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
  onOpenProjectFolder: (workspaceId: string) => Promise<void>;
  onOpenWorkspaceFolder: (threadId: string) => Promise<void>;
  onOpenLegacySharedFolder: (threadId: string) => Promise<void>;
  onOpenConversationSearch: () => void;
  onRenameConversation: (threadId: string, title: string) => Promise<void>;
  onPinConversation: (threadId: string, pinned: boolean) => Promise<void>;
  onArchiveConversation: (threadId: string) => Promise<void>;
  mutatingThreadIds: readonly string[];
  onChooseProject: () => void | Promise<void>;
  onSelectNoProject: () => void | Promise<void>;
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

/** 仅把系统菜单键与 Shift+F10 解释为对象右键，避免截获其它行级快捷键。 */
function isContextMenuKey(event: KeyboardEvent): boolean {
  return event.key === "ContextMenu" || (event.key === "F10" && event.shiftKey);
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
  onOpenProjectFolder,
  onRequestClose,
}: {
  project: NavigationProject;
  selected: boolean;
  runtimeTone: NavigationSidebarProps["runtimeTone"];
  compact: boolean;
  disabled: boolean;
  onSelect: (workspaceId: string) => void | Promise<void>;
  onOpenProjectFolder: (workspaceId: string) => Promise<void>;
  onRequestClose: () => void;
}): ReactElement {
  const displayName = project.displayName.trim() || "未命名项目";
  const title = selected ? `当前项目：${displayName}` : `切换到项目：${displayName}`;
  const rowRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  const menuSessionRef = useRef(0);
  const [contextMenu, setContextMenu] = useState<NavigationPointerMenu>();
  /** 菜单保留目标项目的稳定 ID；打开目录不会经由项目选择器切换 Workspace。 */
  const openContextMenu = (x: number, y: number): void => {
    setContextMenu({ x, y, session: ++menuSessionRef.current });
  };
  /** 项目行自身拥有 Explorer 快捷动作，因此只在这里屏蔽 WebView 原生菜单。 */
  const handleContextMenu = (event: MouseEvent<HTMLDivElement>): void => {
    event.preventDefault();
    restoreFocusRef.current =
      event.target instanceof Element ? event.target.closest<HTMLElement>("button") : null;
    openContextMenu(event.clientX, event.clientY);
  };
  /** 无障碍菜单键把菜单放在行尾，并保存键盘来源以便 Escape 后返回焦点。 */
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (!isContextMenuKey(event)) return;
    event.preventDefault();
    restoreFocusRef.current =
      event.target instanceof Element ? event.target.closest<HTMLElement>("button") : null;
    const bounds = rowRef.current?.getBoundingClientRect();
    if (bounds !== undefined) openContextMenu(bounds.left, bounds.bottom);
  };
  /** 返回焦点到触发按钮；虚拟行消失时改回该项目行的当前按钮。 */
  const restoreContextMenuFocus = (): void => {
    const source = restoreFocusRef.current;
    if (source?.isConnected && !source.hasAttribute("disabled")) source.focus();
    else rowRef.current?.querySelector<HTMLElement>("button")?.focus();
  };
  return (
    <div ref={rowRef} onContextMenu={handleContextMenu} onKeyDown={handleKeyDown}>
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
      {contextMenu === undefined ? null : (
        <PointerContextMenu
          key={`${project.workspaceId}:${contextMenu.session}`}
          x={contextMenu.x}
          y={contextMenu.y}
          label={`项目操作：${displayName}`}
          onOpenChange={(open) => {
            if (!open) setContextMenu(undefined);
          }}
          onRestoreFocus={restoreContextMenuFocus}
        >
          <MenuItem
            disabled={disabled}
            onSelect={() =>
              runNavigationAction(
                () => onOpenProjectFolder(project.workspaceId),
                compact,
                onRequestClose,
              )
            }
          >
            <FolderOpen aria-hidden="true" />
            在资源管理器中打开项目目录
          </MenuItem>
        </PointerContextMenu>
      )}
    </div>
  );
}

/**
 * 将“无项目对话”作为项目列表中的稳定入口，保留现有侧栏位置与忙碌约束而不绑定共享目录。
 */
function NoProjectWorkspaceRow({
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

/** 更多菜单与右键菜单共用同一份动作集合，使条件可用性和归档规则不会随入口漂移。 */
function ThreadRowMenuItems({
  thread,
  pending,
  canArchive,
  archiveLabel,
  compact,
  onRequestClose,
  onRequestRename,
  onPin,
  onOpenWorkspaceFolder,
  onOpenLegacySharedFolder,
  onArchive,
}: {
  thread: ThreadProjection;
  pending: boolean;
  canArchive: boolean;
  archiveLabel: string;
  compact: boolean;
  onRequestClose: () => void;
  onRequestRename: (thread: ThreadProjection) => void;
  onPin: (threadId: string, pinned: boolean) => Promise<void>;
  onOpenWorkspaceFolder: (threadId: string) => Promise<void>;
  onOpenLegacySharedFolder: (threadId: string) => Promise<void>;
  onArchive: (threadId: string) => Promise<void>;
}): ReactElement {
  return (
    <>
      <MenuItem disabled={pending} onSelect={() => void onPin(thread.threadId, !thread.pinned)}>
        {thread.pinned ? <PinOff aria-hidden="true" /> : <Pin aria-hidden="true" />}
        {thread.pinned ? "取消置顶" : "置顶"}
      </MenuItem>
      <MenuItem disabled={pending} onSelect={() => onRequestRename(thread)}>
        <Pencil aria-hidden="true" />
        重命名
      </MenuItem>
      <MenuItem
        disabled={pending}
        onSelect={() =>
          runNavigationAction(() => onOpenWorkspaceFolder(thread.threadId), compact, onRequestClose)
        }
      >
        <FolderOpen aria-hidden="true" />
        打开工作文件夹
      </MenuItem>
      {thread.legacySharedWorkspaceId == null ? null : (
        <MenuItem
          disabled={pending}
          onSelect={() =>
            runNavigationAction(
              () => onOpenLegacySharedFolder(thread.threadId),
              compact,
              onRequestClose,
            )
          }
        >
          <FolderOpen aria-hidden="true" />
          打开旧共享文件夹
        </MenuItem>
      )}
      <MenuItem
        disabled={pending || !canArchive}
        title={canArchive ? undefined : archiveLabel}
        onSelect={() => void onArchive(thread.threadId)}
      >
        <Archive aria-hidden="true" />
        归档
      </MenuItem>
    </>
  );
}

/**
 * 历史行同时呈现持久状态文案和 active thread 标记；菜单触发器绕过 Tooltip clone，保证 Radix
 * pointer/key handlers 到达原生 button，并让文件夹动作只依赖 Thread identity 而不改变选中项。
 */
function HistoryRow({
  thread,
  active,
  compact,
  onSelect,
  onOpenWorkspaceFolder,
  onOpenLegacySharedFolder,
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
  onOpenWorkspaceFolder: (threadId: string) => Promise<void>;
  onOpenLegacySharedFolder: (threadId: string) => Promise<void>;
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
  const rowRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  const menuSessionRef = useRef(0);
  const [contextMenu, setContextMenu] = useState<NavigationPointerMenu>();
  const stopRowAction = (event: SyntheticEvent): void => event.stopPropagation();
  /** 当前 Thread ID 与菜单内容保持绑定，不使用活动 Thread 来推断右键目标。 */
  const openContextMenu = (x: number, y: number): void => {
    setContextMenu({ x, y, session: ++menuSessionRef.current });
  };
  /** 对话区域有独立右键语义；右击只拦截当前行，不触发选择或更改焦点 owner。 */
  const handleContextMenu = (event: MouseEvent<HTMLDivElement>): void => {
    event.preventDefault();
    restoreFocusRef.current =
      event.target instanceof Element ? event.target.closest<HTMLElement>("button") : null;
    openContextMenu(event.clientX, event.clientY);
  };
  /** 支持标准 ContextMenu 键和 Shift+F10，并把 Escape 恢复到原操作按钮。 */
  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (!isContextMenuKey(event)) return;
    event.preventDefault();
    restoreFocusRef.current =
      event.target instanceof Element ? event.target.closest<HTMLElement>("button") : null;
    const bounds = rowRef.current?.getBoundingClientRect();
    if (bounds !== undefined) openContextMenu(bounds.left, bounds.bottom);
  };
  /** 如果行在菜单开启期间卸载，焦点退回当前 Thread 主按钮而不落到 document.body。 */
  const restoreContextMenuFocus = (): void => {
    const source = restoreFocusRef.current;
    if (source?.isConnected && !source.hasAttribute("disabled")) source.focus();
    else rowRef.current?.querySelector<HTMLElement>(".ja-navigation-thread")?.focus();
  };
  return (
    <div
      ref={rowRef}
      className="ja-navigation-thread-row"
      data-active={active || undefined}
      data-pending={pending || undefined}
      onContextMenu={handleContextMenu}
      onKeyDown={handleKeyDown}
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
          <ThreadTurnStatus
            status={thread.latestTurnStatus}
            latestTurnSeen={thread.latestTurnSeen}
            active={active}
          />
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
              tooltip={false}
              aria-disabled={pending || undefined}
            >
              <MoreHorizontal aria-hidden="true" />
            </IconButton>
          </MenuTrigger>
          <MenuContent align="end" onCloseAutoFocus={(event) => event.preventDefault()}>
            <ThreadRowMenuItems
              thread={thread}
              pending={pending}
              canArchive={canArchive}
              archiveLabel={archiveLabel}
              compact={compact}
              onRequestClose={onRequestClose}
              onRequestRename={onRequestRename}
              onPin={onPin}
              onOpenWorkspaceFolder={onOpenWorkspaceFolder}
              onOpenLegacySharedFolder={onOpenLegacySharedFolder}
              onArchive={onArchive}
            />
          </MenuContent>
        </Menu>
      </div>
      {contextMenu === undefined ? null : (
        <PointerContextMenu
          key={`${thread.threadId}:${contextMenu.session}`}
          x={contextMenu.x}
          y={contextMenu.y}
          label={`对话操作：${title}`}
          onOpenChange={(open) => {
            if (!open) setContextMenu(undefined);
          }}
          onRestoreFocus={restoreContextMenuFocus}
        >
          <ThreadRowMenuItems
            thread={thread}
            pending={pending}
            canArchive={canArchive}
            archiveLabel={archiveLabel}
            compact={compact}
            onRequestClose={onRequestClose}
            onRequestRename={onRequestRename}
            onPin={onPin}
            onOpenWorkspaceFolder={onOpenWorkspaceFolder}
            onOpenLegacySharedFolder={onOpenLegacySharedFolder}
            onArchive={onArchive}
          />
        </PointerContextMenu>
      )}
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
 * 历史读取只在“最近对话”标题行保留固定尺寸的状态指示器，避免用列表占位替换既有内容，
 * 从而让跨项目恢复保持空间稳定；异常详情在状态旁按需打开并居中排版，悬停只解释原因，
 * 恢复操作仍由组合层持有。
 */
export const NavigationSidebar = memo(function NavigationSidebar(
  props: NavigationSidebarProps,
): ReactElement {
  const [renameThread, setRenameThread] = useState<ThreadProjection>();
  const topActions = buildTopActions(props);
  const settingsShortcut = navigationShortcut("open-settings", props.platform);
  // 已有目录保持可读可操作时，后台校验不再反复挂载旋转图标；首次空目录恢复仍给出明确进度。
  const showHistoryLoading = props.historyBusy && props.threads.length === 0;

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
                  <NoProjectWorkspaceRow
                    selected={props.noProjectSelected}
                    runtimeTone={props.runtimeTone}
                    compact={props.compact}
                    disabled={props.projectBusy}
                    onSelect={props.onSelectNoProject}
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
                      onOpenProjectFolder={props.onOpenProjectFolder}
                      onRequestClose={props.onRequestClose}
                    />
                  </div>
                ))}
              </div>
              {props.projectCatalogLoading && props.projects.length === 0 ? (
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
              {showHistoryLoading ? (
                <span
                  className="ja-navigation-history-loading"
                  role="status"
                  aria-label="正在读取会话"
                  aria-live="polite"
                >
                  <LoaderCircle aria-hidden="true" focusable="false" />
                </span>
              ) : null}
            </div>
            <CollapsibleContent className="ja-navigation-section-content">
              <div
                className="ja-navigation-history-list"
                role="list"
                aria-label="最近对话列表"
                aria-busy={props.historyBusy || undefined}
              >
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
                      onOpenWorkspaceFolder={props.onOpenWorkspaceFolder}
                      onOpenLegacySharedFolder={props.onOpenLegacySharedFolder}
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
