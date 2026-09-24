// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Check,
  ChevronDown,
  ChevronRight,
  FileCode2,
  FilePlus2,
  FileX2,
  Folder,
  FolderOpen,
  ListTree,
  Search,
  UnfoldVertical,
} from "lucide-react";
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type MutableRefObject,
  type ReactElement,
} from "react";
import { useVirtualizer } from "@tanstack/react-virtual";
import {
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuItemIndicator,
  MenuRadioGroup,
  MenuRadioItem,
  MenuTrigger,
  MenuSeparator,
  PointerContextMenu,
} from "@/shared/ui/primitives";
import type { ReviewAction } from "../domain/types";
import { actionLabel } from "../domain/model";
import {
  buildReviewTreeRows,
  defaultExpandedReviewTree,
  type ReviewTreeFile,
  type ReviewTreeGrouping,
  type ReviewTreeRow,
} from "../domain/reviewTree";

const GROUPING_OPTIONS = [
  { value: "status", label: "状态与目录" },
  { value: "directory", label: "目录" },
  { value: "flat", label: "平铺" },
] satisfies ReadonlyArray<{ value: ReviewTreeGrouping; label: string }>;

export interface ReviewFileTreeProps {
  readonly files: readonly ReviewTreeFile[];
  readonly selectedId?: string;
  readonly query: string;
  readonly onQueryChange: (query: string) => void;
  readonly onSelect: (file: ReviewTreeFile) => void;
  readonly selectedButtonRef?: MutableRefObject<HTMLButtonElement | null>;
  readonly initialGrouping?: ReviewTreeGrouping;
  readonly allowedGroupings?: readonly ReviewTreeGrouping[];
  readonly loading?: boolean;
  readonly emptyMessage?: string;
  readonly fileAriaLabel?: (file: ReviewTreeFile) => string;
  readonly fileContextActions?: (file: ReviewTreeFile) => readonly ReviewAction[];
  readonly onFileContextAction?: (file: ReviewTreeFile, action: ReviewAction) => void;
  readonly navigationState?: ReviewTreeNavigationState;
  readonly onNavigationStateChange?: (state: ReviewTreeNavigationState) => void;
}

interface FileContextMenuSession {
  readonly sequence: number;
  readonly fileId: string;
  readonly layer: ReviewTreeFile["layer"];
  readonly x: number;
  readonly y: number;
  readonly restoreFocus: () => void;
}

export interface ReviewTreeNavigationState {
  readonly grouping: ReviewTreeGrouping;
  readonly openedIds: readonly string[];
  readonly collapsedIds: readonly string[];
  readonly scrollTop: number;
}

export interface ReviewNavigationState {
  readonly detailOpen: boolean;
  readonly query: string;
  readonly selectedPath?: string;
  /** Turn 路径请求采用递增 revision；持久化已消费值可区分返回恢复与新的精确定位。 */
  readonly consumedPathRequestRevision?: number;
  readonly tree?: ReviewTreeNavigationState;
}

/** 映射状态到简洁图标；颜色只是辅助，状态字母仍提供非颜色语义。 */
function TreeFileIcon({ file }: { file: ReviewTreeFile }): ReactElement {
  const Icon =
    file.status === "added" || file.status === "untracked"
      ? FilePlus2
      : file.status === "deleted" || file.status === "conflicted"
        ? FileX2
        : FileCode2;
  return <Icon aria-hidden="true" />;
}

/** 文件行保持稳定节奏；右键只发出被点中文件意图，不改变当前 Diff 选择。 */
function FileRow({
  row,
  selected,
  selectedButtonRef,
  onSelect,
  fileAriaLabel,
  focusable,
  onFocus,
  fallbackFocus,
  onRequestContextMenu,
}: {
  row: Extract<ReviewTreeRow, { kind: "file" }>;
  selected: boolean;
  selectedButtonRef?: MutableRefObject<HTMLButtonElement | null>;
  onSelect: (file: ReviewTreeFile) => void;
  fileAriaLabel?: (file: ReviewTreeFile) => string;
  focusable: boolean;
  onFocus: () => void;
  fallbackFocus: () => void;
  onRequestContextMenu?: (
    file: ReviewTreeFile,
    x: number,
    y: number,
    restoreFocus: () => void,
  ) => boolean;
}): ReactElement {
  const layerLabel =
    row.file.layer === "staged"
      ? "已暂存"
      : row.file.layer === "unstaged"
        ? "未暂存"
        : row.file.layer === "untracked"
          ? "未跟踪"
          : "比较";
  const statusMark =
    row.file.status === "conflicted"
      ? "!"
      : row.file.status === "added" || row.file.status === "untracked"
        ? "A"
        : row.file.status === "deleted"
          ? "D"
          : row.file.status === "renamed"
            ? "R"
            : "M";
  return (
    <button
      ref={selected ? selectedButtonRef : undefined}
      type="button"
      role="treeitem"
      className="ja-review-tree-file"
      aria-label={fileAriaLabel?.(row.file) ?? `查看 ${row.file.path} 的${layerLabel}变更`}
      aria-selected={selected}
      aria-pressed={selected}
      aria-level={row.depth + 1}
      tabIndex={focusable ? 0 : -1}
      data-review-file-id={row.file.id}
      data-review-layer={row.file.layer}
      style={{ "--ja-review-tree-depth": row.depth } as CSSProperties}
      onClick={() => onSelect(row.file)}
      onFocus={onFocus}
      onContextMenu={(event) => {
        const rowButton = event.currentTarget;
        if (
          onRequestContextMenu?.(row.file, event.clientX, event.clientY, () => {
            if (rowButton.isConnected) rowButton.focus();
            else fallbackFocus();
          })
        )
          event.preventDefault();
      }}
      onKeyDown={(event) => {
        if (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10")) return;
        const rowButton = event.currentTarget;
        const bounds = event.currentTarget.getBoundingClientRect();
        if (
          onRequestContextMenu?.(row.file, bounds.left, bounds.bottom, () => {
            if (rowButton.isConnected) rowButton.focus();
            else fallbackFocus();
          })
        ) {
          event.preventDefault();
          event.stopPropagation();
        }
      }}
    >
      <span className="ja-review-tree-file-icon" data-status={row.file.status}>
        <TreeFileIcon file={row.file} />
      </span>
      <span className="ja-review-tree-file-name" title={row.file.path}>
        {row.label}
      </span>
      <span className="ja-review-tree-file-status" aria-label={`${layerLabel} ${row.file.status}`}>
        {statusMark}
      </span>
      {row.file.binary ? (
        <span className="ja-review-tree-file-binary" title="二进制文件">
          二进制
        </span>
      ) : row.file.additions === null || row.file.deletions === null ? null : (
        <span
          className="ja-review-tree-file-delta"
          aria-label={`增加 ${row.file.additions} 行，删除 ${row.file.deletions} 行`}
        >
          <span>+{row.file.additions}</span>
          <span>-{row.file.deletions}</span>
        </span>
      )}
    </button>
  );
}

/** 目录/状态节点共用展开行为；节点 identity 由 group 与规范路径稳定派生。 */
function BranchRow({
  row,
  expanded,
  onToggle,
  focusable,
  onFocus,
}: {
  row: Exclude<ReviewTreeRow, { kind: "file" }>;
  expanded: boolean;
  onToggle: (id: string) => void;
  focusable: boolean;
  onFocus: () => void;
}): ReactElement {
  const FolderIcon = expanded ? FolderOpen : Folder;
  return (
    <button
      type="button"
      role="treeitem"
      className={`ja-review-tree-branch is-${row.kind}`}
      aria-expanded={expanded}
      aria-level={row.depth + 1}
      tabIndex={focusable ? 0 : -1}
      data-review-group={row.kind === "group" ? row.group : undefined}
      style={{ "--ja-review-tree-depth": row.depth } as CSSProperties}
      onClick={() => onToggle(row.id)}
      onFocus={onFocus}
    >
      {expanded ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
      {row.kind === "folder" ? <FolderIcon aria-hidden="true" /> : <ListTree aria-hidden="true" />}
      <span title={row.label}>{row.label}</span>
      <small>{row.count}</small>
    </button>
  );
}

/**
 * 共享文件树只投影传入快照：分组、搜索、目录压缩和虚拟化都不访问文件系统。
 * 查询期间强制展开命中祖先，清空后通过 collapsed/opened 双集合恢复原折叠状态。
 */
export function ReviewFileTree({
  files,
  selectedId,
  query,
  onQueryChange,
  onSelect,
  selectedButtonRef,
  initialGrouping = "status",
  allowedGroupings = ["status", "directory", "flat"],
  loading = false,
  emptyMessage = "当前范围没有变更。",
  fileAriaLabel,
  fileContextActions,
  onFileContextAction,
  navigationState,
  onNavigationStateChange,
}: ReviewFileTreeProps): ReactElement {
  const scrollRef = useRef<HTMLDivElement>(null);
  const [grouping, setGrouping] = useState<ReviewTreeGrouping>(
    navigationState?.grouping ?? initialGrouping,
  );
  const [opened, setOpened] = useState<Set<string>>(
    () => new Set(navigationState?.openedIds ?? defaultExpandedReviewTree(files, initialGrouping)),
  );
  const [collapsed, setCollapsed] = useState<Set<string>>(
    () => new Set(navigationState?.collapsedIds ?? []),
  );
  const defaults = useMemo(() => defaultExpandedReviewTree(files, grouping), [files, grouping]);
  const expanded = useMemo(() => {
    const result = new Set([...defaults, ...opened]);
    for (const id of collapsed) result.delete(id);
    return result;
  }, [collapsed, defaults, opened]);
  const rows = useMemo(
    () => buildReviewTreeRows(files, grouping, query, expanded),
    [expanded, files, grouping, query],
  );
  const selectedRowId = rows.find((row) => row.kind === "file" && row.file.id === selectedId)?.id;
  const [focusedId, setFocusedId] = useState<string | undefined>(selectedRowId ?? rows[0]?.id);
  const [contextMenuSession, setContextMenuSession] = useState<
    FileContextMenuSession | undefined
  >();
  const contextMenuSessionRef = useRef<FileContextMenuSession | undefined>(undefined);
  const contextMenuSequenceRef = useRef(0);
  const effectiveFocusedId = rows.some((row) => row.id === focusedId)
    ? focusedId
    : (selectedRowId ?? rows[0]?.id);
  // TanStack Virtual 的 imperative helpers 不可安全 memoize，仅留在当前纯视图中。
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    // React 19 的测量 ref 可能在 commit 生命周期内同步校正；避免 adapter 在该阶段调用 flushSync。
    useFlushSync: false,
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 32,
    overscan: 12,
  });
  const measuredRows = virtualizer.getVirtualItems();
  const renderedRows =
    measuredRows.length > 0 || rows.length === 0
      ? measuredRows
      : rows.slice(0, 40).map((_, index) => ({
          index,
          start: index * 32,
          size: 32,
          end: (index + 1) * 32,
          key: rows[index]!.id,
          lane: 0,
        }));
  const restoredScrollTop = navigationState?.scrollTop;
  const contextMenuFile =
    contextMenuSession === undefined
      ? undefined
      : files.find(
          (file) =>
            file.id === contextMenuSession.fileId && file.layer === contextMenuSession.layer,
        );
  const contextMenuActions =
    contextMenuFile === undefined || fileContextActions === undefined
      ? []
      : fileContextActions(contextMenuFile);

  /** 目标从最新投影中消失或失去权限时立即收起菜单，防止旧操作在后来复活。 */
  useEffect(() => {
    if (
      contextMenuSession === undefined ||
      (contextMenuFile !== undefined && contextMenuActions.length > 0)
    )
      return;
    if (contextMenuSessionRef.current?.sequence === contextMenuSession.sequence) {
      window.requestAnimationFrame(() => {
        if (contextMenuSessionRef.current?.sequence !== contextMenuSession.sequence) return;
        contextMenuSessionRef.current = undefined;
        setContextMenuSession(undefined);
      });
    }
  }, [contextMenuActions.length, contextMenuFile, contextMenuSession]);

  /** 右键与键盘入口都冻结 file id/layer；真正执行前仍重查当前文件和可用 action。 */
  const requestFileContextMenu = (
    file: ReviewTreeFile,
    x: number,
    y: number,
    restoreFocus: () => void,
  ): boolean => {
    if (fileContextActions === undefined || onFileContextAction === undefined) return false;
    const currentFile = files.find(
      (candidate) => candidate.id === file.id && candidate.layer === file.layer,
    );
    if (currentFile === undefined || fileContextActions(currentFile).length === 0) return false;
    contextMenuSequenceRef.current += 1;
    const nextSession: FileContextMenuSession = {
      sequence: contextMenuSequenceRef.current,
      fileId: currentFile.id,
      layer: currentFile.layer,
      x,
      y,
      restoreFocus,
    };
    contextMenuSessionRef.current = nextSession;
    setContextMenuSession(nextSession);
    return true;
  };

  /** 关闭只撤销对应菜单 generation，旧 portal 的 close 事件不能关闭新目标菜单。 */
  const closeFileContextMenu = (session: FileContextMenuSession): void => {
    if (contextMenuSessionRef.current?.sequence !== session.sequence) return;
    contextMenuSessionRef.current = undefined;
    setContextMenuSession(undefined);
  };

  /** 菜单项选择时按最新文件投影与 capability 复核，避免快照刷新后作用到旧行。 */
  const applyFileContextAction = (action: ReviewAction): void => {
    if (
      contextMenuSession === undefined ||
      fileContextActions === undefined ||
      onFileContextAction === undefined
    )
      return;
    const currentFile = files.find(
      (candidate) =>
        candidate.id === contextMenuSession.fileId && candidate.layer === contextMenuSession.layer,
    );
    if (currentFile === undefined || !fileContextActions(currentFile).includes(action)) return;
    onFileContextAction(currentFile, action);
  };

  /** 仅在外部恢复的滚动位置改变时同步 DOM，避免 ref 回调在每次渲染时抢回用户滚动。 */
  useEffect(() => {
    if (restoredScrollTop !== undefined && scrollRef.current !== null)
      scrollRef.current.scrollTop = restoredScrollTop;
  }, [restoredScrollTop]);

  /** 展开操作显式记录用户意图，避免查询临时展开污染清空后的状态。 */
  const toggle = (id: string): void => {
    if (expanded.has(id)) {
      const nextOpened = new Set(opened);
      nextOpened.delete(id);
      const nextCollapsed = new Set(collapsed).add(id);
      setOpened(nextOpened);
      setCollapsed(nextCollapsed);
      onNavigationStateChange?.({
        grouping,
        openedIds: [...nextOpened],
        collapsedIds: [...nextCollapsed],
        scrollTop: scrollRef.current?.scrollTop ?? 0,
      });
      return;
    }
    const nextCollapsed = new Set(collapsed);
    nextCollapsed.delete(id);
    const nextOpened = new Set(opened).add(id);
    setCollapsed(nextCollapsed);
    setOpened(nextOpened);
    onNavigationStateChange?.({
      grouping,
      openedIds: [...nextOpened],
      collapsedIds: [...nextCollapsed],
      scrollTop: scrollRef.current?.scrollTop ?? 0,
    });
  };

  /** 切换分组重建当前投影，但不改变查询和文件选择。 */
  const changeGrouping = (value: string): void => {
    const next = value as ReviewTreeGrouping;
    if (!allowedGroupings.includes(next)) return;
    setGrouping(next);
    const nextOpened = defaultExpandedReviewTree(files, next);
    setOpened(nextOpened);
    setCollapsed(new Set());
    onNavigationStateChange?.({
      grouping: next,
      openedIds: [...nextOpened],
      collapsedIds: [],
      scrollTop: scrollRef.current?.scrollTop ?? 0,
    });
  };

  /** 折叠当前所有可见分支，不触碰 native 快照或选择。 */
  const collapseAll = (): void => {
    const branchIds = rows.filter((row) => row.kind !== "file").map((row) => row.id);
    setOpened(new Set());
    const nextCollapsed = new Set(branchIds);
    setCollapsed(nextCollapsed);
    onNavigationStateChange?.({
      grouping,
      openedIds: [],
      collapsedIds: [...nextCollapsed],
      scrollTop: scrollRef.current?.scrollTop ?? 0,
    });
  };

  const groupingOptions = GROUPING_OPTIONS.filter((option) =>
    allowedGroupings.includes(option.value),
  );
  /** 提供桌面树的 roving focus 与标准方向键，虚拟行会先滚入再聚焦。 */
  const navigateTree = (event: KeyboardEvent<HTMLDivElement>): void => {
    const keys = ["ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "Home", "End", "Enter", " "];
    if (!keys.includes(event.key)) return;
    const current = (event.target as HTMLElement).closest<HTMLElement>("[role='treeitem']");
    if (current === null) return;
    const index = rows.findIndex(
      (row) =>
        row.id === current.closest(".ja-review-tree-virtual-row")?.getAttribute("data-row-id"),
    );
    if (index < 0) return;
    if ((event.key === "Enter" || event.key === " ") && current instanceof HTMLButtonElement) {
      event.preventDefault();
      current.click();
      return;
    }
    const row = rows[index]!;
    if (event.key === "ArrowLeft" && row.kind !== "file" && expanded.has(row.id)) {
      event.preventDefault();
      toggle(row.id);
      return;
    }
    if (event.key === "ArrowRight" && row.kind !== "file" && !expanded.has(row.id)) {
      event.preventDefault();
      toggle(row.id);
      return;
    }
    let nextIndex = index;
    if (
      event.key === "ArrowRight" &&
      row.kind !== "file" &&
      rows[index + 1]?.depth === row.depth + 1
    )
      nextIndex = index + 1;
    else if (event.key === "ArrowLeft" && "parentId" in row && row.parentId !== undefined)
      nextIndex = rows.findIndex((candidate) => candidate.id === row.parentId);
    else if (event.key === "ArrowDown") nextIndex = Math.min(rows.length - 1, index + 1);
    else if (event.key === "ArrowUp") nextIndex = Math.max(0, index - 1);
    else if (event.key === "Home") nextIndex = 0;
    else if (event.key === "End") nextIndex = rows.length - 1;
    else return;
    event.preventDefault();
    virtualizer.scrollToIndex(nextIndex, { align: "auto" });
    requestAnimationFrame(() => {
      const nextId = rows[nextIndex]!.id;
      setFocusedId(nextId);
      const rowElement = [
        ...(scrollRef.current?.querySelectorAll<HTMLElement>(".ja-review-tree-virtual-row") ?? []),
      ].find((candidate) => candidate.dataset["rowId"] === nextId);
      rowElement?.querySelector<HTMLElement>("[role='treeitem']")?.focus();
    });
  };
  return (
    <section className="ja-review-tree" data-ja-review-tree aria-label="变更文件">
      <div className="ja-review-tree-tools">
        <label className="ja-review-tree-search">
          <Search aria-hidden="true" />
          <span className="ja-visually-hidden">筛选文件</span>
          <input
            type="search"
            value={query}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder="筛选文件…"
          />
        </label>
        {groupingOptions.length > 1 ? (
          <Menu>
            <MenuTrigger asChild>
              <IconButton
                className="ja-review-tree-grouping-trigger"
                label={`文件分组：${groupingOptions.find((option) => option.value === grouping)?.label ?? "分组"}`}
                tooltip="文件分组"
              >
                <ListTree aria-hidden="true" />
              </IconButton>
            </MenuTrigger>
            <MenuContent className="ja-review-tree-grouping-menu" align="end" aria-label="文件分组">
              <MenuRadioGroup value={grouping} onValueChange={changeGrouping}>
                {groupingOptions.map((option) => (
                  <MenuRadioItem key={option.value} value={option.value}>
                    <span>{option.label}</span>
                    <MenuItemIndicator className="ja-review-tree-grouping-indicator">
                      <Check aria-hidden="true" />
                    </MenuItemIndicator>
                  </MenuRadioItem>
                ))}
              </MenuRadioGroup>
            </MenuContent>
          </Menu>
        ) : null}
        <IconButton label="全部折叠" tooltip="全部折叠" onClick={collapseAll}>
          <UnfoldVertical aria-hidden="true" />
        </IconButton>
      </div>
      <div
        ref={scrollRef}
        className="ja-review-tree-scroll"
        role="tree"
        aria-label="审查文件"
        tabIndex={-1}
        onKeyDown={navigateTree}
        onScroll={(event) =>
          onNavigationStateChange?.({
            grouping,
            openedIds: [...opened],
            collapsedIds: [...collapsed],
            scrollTop: event.currentTarget.scrollTop,
          })
        }
      >
        {loading ? (
          <p className="ja-review-tree-state" role="status">
            正在读取文件…
          </p>
        ) : rows.length === 0 ? (
          <p className="ja-review-tree-state">
            {query.trim().length > 0 ? "没有匹配的文件。" : emptyMessage}
          </p>
        ) : (
          <div
            className="ja-review-tree-spacer"
            style={{ height: Math.max(virtualizer.getTotalSize(), rows.length * 32) }}
          >
            {renderedRows.map((virtualRow) => {
              const row = rows[virtualRow.index];
              if (row === undefined) return null;
              return (
                <div
                  key={row.id}
                  data-row-id={row.id}
                  role="none"
                  className="ja-review-tree-virtual-row"
                  style={{ transform: `translateY(${virtualRow.start}px)` }}
                >
                  {row.kind === "file" ? (
                    <FileRow
                      row={row}
                      selected={row.file.id === selectedId}
                      selectedButtonRef={selectedButtonRef}
                      onSelect={onSelect}
                      fileAriaLabel={fileAriaLabel}
                      focusable={effectiveFocusedId === row.id}
                      onFocus={() => setFocusedId(row.id)}
                      fallbackFocus={() => scrollRef.current?.focus()}
                      onRequestContextMenu={
                        fileContextActions === undefined || onFileContextAction === undefined
                          ? undefined
                          : requestFileContextMenu
                      }
                    />
                  ) : (
                    <BranchRow
                      row={row}
                      expanded={query.trim().length > 0 || expanded.has(row.id)}
                      onToggle={toggle}
                      focusable={effectiveFocusedId === row.id}
                      onFocus={() => setFocusedId(row.id)}
                    />
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
      {contextMenuSession === undefined || contextMenuFile === undefined ? null : (
        <PointerContextMenu
          key={contextMenuSession.sequence}
          x={contextMenuSession.x}
          y={contextMenuSession.y}
          label={`${contextMenuFile.path} 文件操作`}
          onOpenChange={(open) => {
            if (!open) closeFileContextMenu(contextMenuSession);
          }}
          onRestoreFocus={contextMenuSession.restoreFocus}
        >
          {contextMenuActions.flatMap((action, index) => [
            ...(action === "revert" && index > 0
              ? [<MenuSeparator key={`separator:${contextMenuSession.sequence}:${action}`} />]
              : []),
            <MenuItem
              key={`${contextMenuSession.sequence}:${action}`}
              style={action === "revert" ? { color: "var(--ja-danger)" } : undefined}
              onSelect={() => applyFileContextAction(action)}
            >
              {actionLabel(action, { kind: "file", fileId: contextMenuFile.id })}
            </MenuItem>,
          ])}
        </PointerContextMenu>
      )}
    </section>
  );
}
