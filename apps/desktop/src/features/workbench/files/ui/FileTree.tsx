// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  ChevronDown,
  ChevronRight,
  ExternalLink,
  File as FileIcon,
  FileQuestion,
  Folder,
  FolderOpen,
  Link2,
  LoaderCircle,
  RefreshCw,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type DragEvent as ReactDragEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactElement,
} from "react";
import { Tree, type NodeApi, type NodeRendererProps } from "react-arborist";
import { EmptyState, ErrorState, IconButton, LoadingState } from "@/shared/ui/primitives";
import type { WorkspaceFileNode } from "../domain/types";
import { canMoveEntry, findTreeNode, findTreeNodeById, parentPath } from "../domain/filesModel";
import type { FileTreeProps } from "./types";
import "./files.css";

type FileTreeNodeProps = NodeRendererProps<WorkspaceFileNode> & {
  renamePath?: string;
  renameValue: string;
  onRenameValueChange: (value: string) => void;
  onRenameCommit: () => void;
  onRenameCancel: () => void;
  onPointerDown: (node: WorkspaceFileNode, event: ReactPointerEvent<HTMLDivElement>) => void;
  onContextMenu: (node: WorkspaceFileNode, event: ReactMouseEvent<HTMLDivElement>) => void;
  dragTargetPath?: string;
};

interface ContextMenuState {
  node?: WorkspaceFileNode;
  parentPath: string;
  x: number;
  y: number;
}

interface ElementSize {
  width: number;
  height: number;
}

interface DragState {
  source: WorkspaceFileNode;
  active: boolean;
  targetPath?: string;
  pointerId: number;
  startX: number;
  startY: number;
}

/** 只有常规文件和目录拥有完整操作语义；link/reparse/other 保持可见但 fail-closed。 */
function isManagedEntry(node: WorkspaceFileNode): boolean {
  return node.kind === "file" || node.kind === "directory";
}

/** 给特殊节点提供明确、短小的产品标签，避免用户把它们误认成无法展开的普通文件。 */
function specialKindLabel(kind: WorkspaceFileNode["kind"]): string | undefined {
  switch (kind) {
    case "symlink":
      return "链接";
    case "reparse_point":
      return "重解析";
    case "other":
      return "特殊";
    case "file":
    case "directory":
      return undefined;
  }
}

/** 菜单文案按目标表达真实动作；进程发现与参数仍完全由 Rust owner 决定。 */
function openTargetLabel(displayName: string, target: string, hasNode: boolean): string {
  if (target === "file_explorer") return hasNode ? "在文件资源管理器中显示" : "打开文件资源管理器";
  if (target === "terminal") return "在终端中打开";
  return `使用 ${displayName} 打开`;
}

/**
 * 复用 typed workspace 的相对路径语法做 UI 侧预检，避免受污染的树 projection
 * 把绝对路径、盘符或 `..` 继续传给移动回调；Rust 仍负责最终权威校验。
 */
function isSafeTreePath(path: string, allowRoot = false): boolean {
  if (path === "") return allowRoot;
  if (path.length > 4_096 || path.startsWith("/") || path.includes("\\") || path.includes(":"))
    return false;
  if (
    [...path].some((character) => {
      const codePoint = character.codePointAt(0) ?? 0;
      return codePoint <= 31 || codePoint === 127;
    })
  )
    return false;
  return path
    .split("/")
    .every((component) => component.length > 0 && component !== "." && component !== "..");
}

/**
 * 只承认显式根目录 drop zone 或当前 typed tree 中真实存在的目录节点，避免
 * pointer capture 改写 event.target 后误把普通文件、树外元素当作移动目标。
 */
function resolvePointerDropDirectory(
  nodes: readonly WorkspaceFileNode[],
  clientX: number,
  clientY: number,
): string | undefined {
  const targetElement = document
    .elementFromPoint(clientX, clientY)
    ?.closest<HTMLElement>("[data-drop-directory], [data-path]");
  const explicitDirectory = targetElement?.dataset["dropDirectory"];
  if (explicitDirectory !== undefined)
    return isSafeTreePath(explicitDirectory, true) ? explicitDirectory : undefined;
  const targetPath = targetElement?.dataset["path"];
  if (targetPath === undefined || !isSafeTreePath(targetPath)) return undefined;
  const targetNode = findTreeNode(nodes, targetPath);
  return targetNode?.kind === "directory" ? targetNode.path : undefined;
}

/**
 * WebView 在节点刚卸载或 pointer 已隐式结束时可能拒绝 capture；失败时继续使用
 * document 监听兜底，不能让一次平台差异中断文件树交互。
 */
function tryCapturePointer(element: HTMLDivElement, pointerId: number): void {
  if (typeof element.setPointerCapture !== "function") return;
  try {
    element.setPointerCapture(pointerId);
  } catch {
    // capture 不可用时继续依靠 Document listener 完成本次手势，不能中断交互。
  }
}

/**
 * 清理阶段只释放本次 pointer 的 capture，并容忍浏览器已经自动释放，确保取消、
 * 失焦和正常抬起都能复用同一幂等清理路径。
 */
function tryReleasePointer(element: HTMLDivElement, pointerId: number): void {
  if (typeof element.releasePointerCapture !== "function") return;
  try {
    if (typeof element.hasPointerCapture === "function" && !element.hasPointerCapture(pointerId))
      return;
    element.releasePointerCapture(pointerId);
  } catch {
    // cleanup 观察到事件前浏览器可能已经释放 capture，幂等清理无需报错。
  }
}

/**
 * 测量专用虚拟列表 viewport，而不是把 DOM 几何塞进业务 ViewModel；宽高都来自
 * ResizeObserver，窗口、分栏和缩放变化会共享同一条更新路径。
 */
function useElementSize(): {
  ref: (element: HTMLDivElement | null) => void;
  size: ElementSize;
} {
  const [size, setSize] = useState<ElementSize>({ width: 0, height: 0 });
  const observerRef = useRef<ResizeObserver | undefined>(undefined);

  const ref = useCallback((element: HTMLDivElement | null): void => {
    observerRef.current?.disconnect();
    observerRef.current = undefined;
    if (element === null) return;
    /** 对整数像素去重，避免 ResizeObserver 在缩放边界形成无意义的渲染循环。 */
    const update = (): void => {
      const bounds = element.getBoundingClientRect();
      const next = {
        width: Math.max(1, Math.floor(bounds.width)),
        height: Math.max(1, Math.floor(bounds.height)),
      };
      setSize((current) =>
        current.width === next.width && current.height === next.height ? current : next,
      );
    };
    update();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(update);
    observer.observe(element);
    observerRef.current = observer;
  }, []);

  useEffect(() => () => observerRef.current?.disconnect(), []);
  return { ref, size };
}

/**
 * 行只负责渲染并把 mutation 委托给 feature controller。action props 全部可选，
 * 没有真实 callback 时不渲染虚假菜单，因此树也可用于只读 Workbench projection。
 */
function FileTreeNode({
  node,
  style,
  renamePath,
  renameValue,
  onRenameValueChange,
  onRenameCommit,
  onRenameCancel,
  onPointerDown,
  onContextMenu,
  dragTargetPath,
}: FileTreeNodeProps): ReactElement {
  const isDirectory = node.data.kind === "directory";
  const Icon = isDirectory
    ? node.isOpen
      ? FolderOpen
      : Folder
    : node.data.kind === "file"
      ? FileIcon
      : node.data.kind === "other"
        ? FileQuestion
        : Link2;
  const DisclosureIcon = node.isOpen ? ChevronDown : ChevronRight;
  const specialLabel = specialKindLabel(node.data.kind);
  const toggle = (event: React.MouseEvent<HTMLButtonElement>): void => {
    event.stopPropagation();
    node.toggle();
  };
  const editing = renamePath === node.data.path;
  /** 阻止 disclosure 点击进入 Arborist 行选择与拖拽处理。 */
  return (
    <div
      style={style}
      className={`ja-file-tree-row${node.isSelected ? " is-selected" : ""}${node.isFocused ? " is-focused" : ""}${dragTargetPath === node.data.path ? " is-drag-target" : ""}`}
      data-path={node.data.path}
      data-kind={node.data.kind}
      onPointerDown={(event) => onPointerDown(node.data, event)}
      onContextMenu={(event) => onContextMenu(node.data, event)}
    >
      {isDirectory ? (
        <IconButton
          className="ja-file-tree-disclosure"
          label={`${node.isOpen ? "折叠" : "展开"}${node.data.name}`}
          tooltip={false}
          tabIndex={-1}
          onClick={toggle}
        >
          <DisclosureIcon aria-hidden="true" />
        </IconButton>
      ) : (
        <span className="ja-file-tree-disclosure-spacer" aria-hidden="true" />
      )}
      <Icon aria-hidden="true" className="ja-file-tree-icon" />
      {editing ? (
        <input
          autoFocus
          className="ja-file-tree-inline-input"
          aria-label={`重命名 ${node.data.name}`}
          value={renameValue}
          onChange={(event) => onRenameValueChange(event.target.value)}
          onBlur={onRenameCommit}
          onKeyDown={(event) => {
            if (event.key === "Enter") onRenameCommit();
            if (event.key === "Escape") onRenameCancel();
          }}
          onClick={(event) => event.stopPropagation()}
        />
      ) : (
        <span className="ja-file-tree-name" title={node.data.path}>
          {node.data.name}
        </span>
      )}
      {specialLabel === undefined ? null : (
        <span className="ja-file-tree-kind" title={`${specialLabel}节点仅供查看`}>
          {specialLabel}
        </span>
      )}
      {node.data.loading ? (
        <LoaderCircle aria-label="加载中" className="ja-file-tree-loading" />
      ) : null}
    </div>
  );
}

/**
 * 将虚拟树适配到只读 projection 与 typed 文件控制器；内部拖拽只提交已知相对
 * 目录，根目录使用固定空字符串，取消和失焦不会触发任何文件写入。
 */
export function FileTree({
  nodes,
  selectedPath,
  loading = false,
  error,
  onSelect,
  onDirectoryToggle,
  onRetry,
  onCreateFile,
  onCreateDirectory,
  onRename,
  onMove,
  onTrash,
  onRefresh,
  onContextMenu,
  onAddToConversation,
  onNativeDropToken,
  openTargets = [],
  onOpenTarget,
}: FileTreeProps): ReactElement {
  const { ref: viewportRef, size: viewportSize } = useElementSize();
  const selectedNodeId =
    selectedPath === undefined ? undefined : findTreeNode(nodes, selectedPath)?.id;
  const selectedNode = selectedPath === undefined ? undefined : findTreeNode(nodes, selectedPath);
  const [renamePath, setRenamePath] = useState<string>();
  const [renameValue, setRenameValue] = useState("");
  const [createKind, setCreateKind] = useState<"file" | "directory">();
  const [createParent, setCreateParent] = useState("");
  const [createValue, setCreateValue] = useState("");
  const [contextMenu, setContextMenu] = useState<ContextMenuState>();
  const [dragTargetPath, setDragTargetPath] = useState<string>();
  const dragRef = useRef<DragState | undefined>(undefined);
  const dragCleanupRef = useRef<(() => void) | undefined>(undefined);
  const renameCommitRef = useRef<string | undefined>(undefined);
  const createCommitRef = useRef(false);
  const hostRef = useRef<HTMLDivElement | null>(null);
  const contextMenuRef = useRef<HTMLDivElement | null>(null);
  const contextMenuTriggerRef = useRef<HTMLElement | null>(null);
  const mutationMenuAvailable =
    onCreateFile !== undefined ||
    onCreateDirectory !== undefined ||
    onRename !== undefined ||
    onTrash !== undefined ||
    onRefresh !== undefined;
  const openMenuAvailable = onOpenTarget !== undefined && openTargets.length > 0;
  const rootMenuAvailable =
    onCreateFile !== undefined ||
    onCreateDirectory !== undefined ||
    onRefresh !== undefined ||
    openMenuAvailable;
  const toolbarAvailable =
    mutationMenuAvailable || onRefresh !== undefined || onMove !== undefined || openMenuAvailable;
  const fileExplorerTarget = openTargets.find((target) => target.target === "file_explorer");

  /** 特殊节点不继承普通文件动作；只有确实能渲染至少一个条目时才接管系统右键菜单。 */
  const rowMenuAvailableFor = useCallback(
    (node: WorkspaceFileNode): boolean =>
      onCreateFile !== undefined ||
      onCreateDirectory !== undefined ||
      onRefresh !== undefined ||
      (isManagedEntry(node) &&
        (onAddToConversation !== undefined ||
          onRename !== undefined ||
          onTrash !== undefined ||
          openMenuAvailable)),
    [
      onAddToConversation,
      onCreateDirectory,
      onCreateFile,
      onRefresh,
      onRename,
      onTrash,
      openMenuAvailable,
    ],
  );

  /** 用户继续编辑代表新的 Rename 候选，清除前一次 Enter/blur 的幂等哨兵后才允许提交。 */
  const changeRenameValue = useCallback((value: string): void => {
    renameCommitRef.current = undefined;
    setRenameValue(value);
  }, []);

  /** 关闭菜单时可选择恢复到来源 TreeItem；外部点击保留用户的新焦点，键盘关闭才恢复。 */
  const closeContextMenu = useCallback((restoreFocus = false): void => {
    setContextMenu(undefined);
    if (!restoreFocus) return;
    const trigger = contextMenuTriggerRef.current;
    window.requestAnimationFrame(() => {
      if (trigger?.isConnected) trigger.focus();
      else hostRef.current?.querySelector<HTMLElement>('[role="tree"]')?.focus();
    });
  }, []);

  /** 统一保存菜单目标、父目录和焦点来源，根目录与行菜单不会复制定位或恢复规则。 */
  const openContextMenu = useCallback(
    (
      node: WorkspaceFileNode | undefined,
      parent: string,
      x: number,
      y: number,
      trigger: HTMLElement,
    ): void => {
      contextMenuTriggerRef.current = trigger;
      setContextMenu({ node, parentPath: parent, x, y });
    },
    [],
  );

  useEffect(() => {
    if (contextMenu === undefined) return undefined;
    /** 指针移到菜单外代表用户已经转移上下文，此时关闭但不抢回焦点。 */
    const close = (): void => closeContextMenu(false);
    document.addEventListener("pointerdown", close);
    window.addEventListener("blur", close);
    window.addEventListener("resize", close);
    window.addEventListener("scroll", close, true);
    return () => {
      document.removeEventListener("pointerdown", close);
      window.removeEventListener("blur", close);
      window.removeEventListener("resize", close);
      window.removeEventListener("scroll", close, true);
    };
  }, [closeContextMenu, contextMenu]);

  useLayoutEffect(() => {
    if (contextMenu === undefined) return;
    const menu = contextMenuRef.current;
    if (menu === null) return;
    const bounds = menu.getBoundingClientRect();
    const margin = 8;
    const nextX = Math.max(
      margin,
      Math.min(contextMenu.x, window.innerWidth - bounds.width - margin),
    );
    const nextY = Math.max(
      margin,
      Math.min(contextMenu.y, window.innerHeight - bounds.height - margin),
    );
    if (nextX !== contextMenu.x || nextY !== contextMenu.y) {
      setContextMenu((current) =>
        current === undefined ? current : { ...current, x: nextX, y: nextY },
      );
      return;
    }
    menu.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
  }, [contextMenu]);

  useEffect(() => () => dragCleanupRef.current?.(), []);

  /**
   * 只有正常 pointerup 才能调用本提交路径；提交前同时拒绝路径逃逸、同目录空操作、
   * 目标覆盖以及目录自包含，避免 UI 手势绕过 typed move 的安全语义。
   */
  const finishDrag = useCallback((): void => {
    const current = dragRef.current;
    dragRef.current = undefined;
    setDragTargetPath(undefined);
    if (
      current === undefined ||
      !current.active ||
      onMove === undefined ||
      current.targetPath === undefined
    )
      return;
    if (!isSafeTreePath(current.source.path) || !isSafeTreePath(current.targetPath, true)) return;
    if (current.targetPath === parentPath(current.source.path)) return;
    if (current.targetPath === "") {
      const rootSiblingNames = nodes
        .filter((child) => child.path !== current.source.path)
        .map((child) => child.name);
      if (rootSiblingNames.includes(current.source.name)) return;
      void Promise.resolve(onMove(current.source, "")).catch(() => undefined);
      return;
    }
    const target = findTreeNode(nodes, current.targetPath);
    const siblingNames =
      target?.children
        ?.filter((child) => child.path !== current.source.path)
        .map((child) => child.name) ?? [];
    if (target === undefined || !canMoveEntry(current.source, target, siblingNames)) return;
    void Promise.resolve(onMove(current.source, target.path)).catch(() => undefined);
  }, [nodes, onMove]);

  /**
   * 幂等取消当前手势，只清理监听器、capture 与视觉状态；pointercancel、窗口失焦、
   * 页面隐藏和 capture 丢失统一走这里，永远不会调用 typed move。
   */
  const cancelDrag = useCallback((): void => {
    dragCleanupRef.current?.();
    dragRef.current = undefined;
    setDragTargetPath(undefined);
  }, []);

  /**
   * 使用 pointer capture 配合 document 监听跨越虚拟行追踪目标；只有匹配 pointerId
   * 的 pointerup 提交，其余取消信号均在副作用前终止手势。交互控件的 SVG 等后代
   * 必须通过 closest 一并排除，否则行 capture 会吞掉 disclosure 的真实 click。
   */
  const onPointerDown = useCallback(
    (source: WorkspaceFileNode, event: ReactPointerEvent<HTMLDivElement>): void => {
      const interactiveTarget =
        event.target instanceof Element ? event.target.closest("button,input") : null;
      if (
        onMove === undefined ||
        !isManagedEntry(source) ||
        !isSafeTreePath(source.path) ||
        event.button !== 0 ||
        interactiveTarget !== null
      )
        return;
      cancelDrag();
      const state: DragState = {
        source,
        active: false,
        pointerId: event.pointerId,
        startX: event.clientX,
        startY: event.clientY,
      };
      const captureElement = event.currentTarget;
      dragRef.current = state;

      /** 达到阈值后只更新瞬态目标，不在移动阶段执行文件副作用。 */
      const handleMove = (move: PointerEvent): void => {
        const current = dragRef.current;
        if (current === undefined || move.pointerId !== current.pointerId) return;
        const distance = Math.hypot(move.clientX - current.startX, move.clientY - current.startY);
        if (!current.active && distance < 5) return;
        current.active = true;
        current.targetPath = resolvePointerDropDirectory(nodes, move.clientX, move.clientY);
        setDragTargetPath(current.targetPath);
        move.preventDefault();
      };

      /** 仅匹配本次 capture 的正常抬起可在清理后进入唯一提交函数。 */
      const handleUp = (up: PointerEvent): void => {
        if (up.pointerId !== state.pointerId) return;
        dragCleanupRef.current?.();
        finishDrag();
      };

      /** pointercancel 与 capture 丢失都只能取消，防止系统手势被误判为 drop。 */
      const handleCancel = (cancel: PointerEvent): void => {
        if (cancel.pointerId !== state.pointerId) return;
        cancelDrag();
      };

      /** 窗口失焦会丢失可靠的抬起位置，因此直接取消而不是猜测用户意图。 */
      const handleBlur = (): void => cancelDrag();

      /** 页面转入后台时终止手势，避免恢复后消费陈旧的 pointerup。 */
      const handleVisibilityChange = (): void => {
        if (document.visibilityState !== "visible") cancelDrag();
      };

      /** 统一移除全局监听并释放 capture，保证多次取消和组件卸载都可安全重复。 */
      dragCleanupRef.current = () => {
        document.removeEventListener("pointermove", handleMove);
        document.removeEventListener("pointerup", handleUp);
        document.removeEventListener("pointercancel", handleCancel);
        document.removeEventListener("visibilitychange", handleVisibilityChange);
        captureElement.removeEventListener("lostpointercapture", handleCancel);
        window.removeEventListener("blur", handleBlur);
        tryReleasePointer(captureElement, state.pointerId);
        dragCleanupRef.current = undefined;
      };
      document.addEventListener("pointermove", handleMove, { passive: false });
      document.addEventListener("pointerup", handleUp);
      document.addEventListener("pointercancel", handleCancel);
      document.addEventListener("visibilitychange", handleVisibilityChange);
      captureElement.addEventListener("lostpointercapture", handleCancel);
      window.addEventListener("blur", handleBlur);
      tryCapturePointer(captureElement, state.pointerId);
    },
    [cancelDrag, finishDrag, nodes, onMove],
  );

  /** 每次只打开一个内联 Rename，并保持原节点不可变。 */
  const startRename = useCallback(
    (node: WorkspaceFileNode): void => {
      if (onRename === undefined || !isManagedEntry(node)) return;
      setContextMenu(undefined);
      createCommitRef.current = true;
      setCreateKind(undefined);
      renameCommitRef.current = undefined;
      setRenamePath(node.path);
      setRenameValue(node.name);
    },
    [onRename],
  );

  /** 委托类型化 Rename/Move 前先校验叶子名，避免 UI 生成路径片段。 */
  const commitRename = useCallback(
    (nodePath: string): void => {
      const node = findTreeNode(nodes, nodePath);
      const value = renameValue.trim();
      if (renameCommitRef.current === nodePath) return;
      renameCommitRef.current = nodePath;
      setRenamePath(undefined);
      if (
        node === undefined ||
        onRename === undefined ||
        value.length === 0 ||
        value === node.name ||
        value.includes("/") ||
        value.includes("\\")
      )
        return;
      void Promise.resolve(onRename(node, value)).catch(() => undefined);
    },
    [nodes, onRename, renameValue],
  );

  /** 只有对应 native capability 存在时才打开 Create 输入框。 */
  const startCreate = useCallback(
    (kind: "file" | "directory", parent: string): void => {
      if ((kind === "file" ? onCreateFile : onCreateDirectory) === undefined) return;
      setContextMenu(undefined);
      if (renamePath !== undefined) renameCommitRef.current = renamePath;
      setRenamePath(undefined);
      createCommitRef.current = false;
      setCreateKind(kind);
      setCreateParent(parent);
      setCreateValue("");
    },
    [onCreateDirectory, onCreateFile, renamePath],
  );

  /** 只提交一次已校验叶子名，并合并 Enter/失焦的重复提交。 */
  const commitCreate = useCallback((): void => {
    const value = createValue.trim();
    const kind = createKind;
    if (createCommitRef.current) return;
    createCommitRef.current = true;
    setCreateKind(undefined);
    if (kind === undefined || value.length === 0 || value.includes("/") || value.includes("\\"))
      return;
    const callback = kind === "file" ? onCreateFile : onCreateDirectory;
    if (callback !== undefined)
      void Promise.resolve(callback(createParent, value)).catch(() => undefined);
  }, [createKind, createParent, createValue, onCreateDirectory, onCreateFile]);

  /** Escape 先写入幂等哨兵再卸载输入框，防止随后的 blur 把取消误提交为 Rename。 */
  const cancelRename = useCallback((): void => {
    if (renamePath !== undefined) renameCommitRef.current = renamePath;
    setRenamePath(undefined);
  }, [renamePath]);

  /** Escape 先封闭本次 Create 租约，确保输入框卸载时的 blur 永远是零写入。 */
  const cancelCreate = useCallback((): void => {
    createCommitRef.current = true;
    setCreateKind(undefined);
  }, []);

  /** 先让宿主处理 Context Menu，再仅为真实 action 渲染本地菜单。 */
  const onRowContextMenu = useCallback(
    (node: WorkspaceFileNode, event: ReactMouseEvent<HTMLDivElement>): void => {
      onContextMenu?.(node, event.nativeEvent);
      if (!rowMenuAvailableFor(node)) return;
      event.preventDefault();
      event.stopPropagation();
      const trigger =
        event.currentTarget.closest<HTMLElement>('[role="treeitem"]') ?? event.currentTarget;
      openContextMenu(
        node,
        node.kind === "directory" ? node.path : parentPath(node.path),
        event.clientX,
        event.clientY,
        trigger,
      );
    },
    [onContextMenu, openContextMenu, rowMenuAvailableFor],
  );

  /** 空白区属于 workspace root；只提供已有真实 owner 的新建与刷新，不伪造节点动作。 */
  const onHostContextMenu = useCallback(
    (event: ReactMouseEvent<HTMLDivElement>): void => {
      const target = event.target instanceof Element ? event.target : undefined;
      if (
        !rootMenuAvailable ||
        target?.closest("[data-path]") !== null ||
        target?.closest(".ja-file-tree-context-menu") !== null
      )
        return;
      event.preventDefault();
      event.stopPropagation();
      const tree = event.currentTarget.querySelector<HTMLElement>('[role="tree"]');
      openContextMenu(undefined, "", event.clientX, event.clientY, tree ?? event.currentTarget);
    },
    [openContextMenu, rootMenuAvailable],
  );

  /** 菜单使用 roving focus；方向键循环，Escape 返回来源，Tab 则尊重用户离开菜单的意图。 */
  const onContextMenuKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLDivElement>): void => {
      const items = [
        ...event.currentTarget.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'),
      ];
      if (items.length === 0) return;
      const activeIndex = items.findIndex((item) => item === document.activeElement);
      let nextIndex: number | undefined;
      switch (event.key) {
        case "ArrowDown":
          nextIndex = (activeIndex + 1 + items.length) % items.length;
          break;
        case "ArrowUp":
          nextIndex = (activeIndex - 1 + items.length) % items.length;
          break;
        case "Home":
          nextIndex = 0;
          break;
        case "End":
          nextIndex = items.length - 1;
          break;
        case "Escape":
          event.preventDefault();
          event.stopPropagation();
          closeContextMenu(true);
          return;
        case "Tab":
          closeContextMenu(false);
          return;
        default:
          return;
      }
      event.preventDefault();
      event.stopPropagation();
      items[nextIndex]?.focus();
    },
    [closeContextMenu],
  );

  /** 从键盘锚定当前选择；没有选择时 Context Menu 键落到根目录，保持鼠标与键盘同权。 */
  const onTreeKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLDivElement>): void => {
      const target = event.target instanceof Element ? event.target : undefined;
      if (
        target?.closest(
          "button, input, textarea, [contenteditable='true'], .ja-file-tree-context-menu",
        ) !== null
      )
        return;
      const wantsContextMenu =
        event.key === "ContextMenu" || (event.shiftKey && event.key === "F10");
      if (wantsContextMenu) {
        const node = selectedNode;
        if (
          (node === undefined && !rootMenuAvailable) ||
          (node !== undefined && !rowMenuAvailableFor(node))
        )
          return;
        event.preventDefault();
        event.stopPropagation();
        const renderedNode =
          node === undefined
            ? undefined
            : [...(hostRef.current?.querySelectorAll<HTMLElement>("[data-path]") ?? [])].find(
                (element) => element.dataset["path"] === node.path,
              );
        const trigger =
          renderedNode?.closest<HTMLElement>('[role="treeitem"]') ??
          hostRef.current?.querySelector<HTMLElement>('[role="tree"]') ??
          hostRef.current;
        if (trigger === null || trigger === undefined) return;
        const bounds = (renderedNode ?? trigger).getBoundingClientRect();
        openContextMenu(
          node,
          node === undefined ? "" : node.kind === "directory" ? node.path : parentPath(node.path),
          bounds.left + Math.min(24, Math.max(0, bounds.width)),
          bounds.bottom,
          trigger,
        );
        return;
      }
      if (
        event.key === "F2" &&
        selectedNode !== undefined &&
        isManagedEntry(selectedNode) &&
        onRename !== undefined
      ) {
        event.preventDefault();
        startRename(selectedNode);
        return;
      }
      if (
        event.key === "Delete" &&
        selectedNode !== undefined &&
        isManagedEntry(selectedNode) &&
        onTrash !== undefined
      ) {
        event.preventDefault();
        void Promise.resolve(onTrash(selectedNode)).catch(() => undefined);
        return;
      }
      if (event.key === "F5" && onRefresh !== undefined) {
        event.preventDefault();
        const refreshPath =
          selectedNode === undefined
            ? ""
            : selectedNode.kind === "directory"
              ? selectedNode.path
              : parentPath(selectedNode.path);
        void Promise.resolve(onRefresh(refreshPath)).catch(() => undefined);
      }
    },
    [
      onRefresh,
      onRename,
      onTrash,
      openContextMenu,
      rowMenuAvailableFor,
      rootMenuAvailable,
      selectedNode,
      startRename,
    ],
  );

  /** 只提取一次性 native token，并从 typed tree 派生相对目标目录。 */
  const onNativeDrop = useCallback(
    (event: ReactDragEvent<HTMLDivElement>): void => {
      if (onNativeDropToken === undefined) return;
      const token = event.dataTransfer.getData("application/x-ja-drop-token").trim();
      if (token.length === 0) return;
      event.preventDefault();
      // Arborist 会安装 document 级 DnD backend；Ja native token 必须在本宿主停止传播，
      // 防止该 backend 对并非自己创建的 drag session 执行 hover/drop。
      event.stopPropagation();
      const targetElement = (event.target as HTMLElement | null)?.closest<HTMLElement>(
        "[data-path]",
      );
      const targetPath = targetElement?.dataset["path"];
      const targetNode = targetPath === undefined ? undefined : findTreeNode(nodes, targetPath);
      const targetDirectory =
        targetNode === undefined
          ? ""
          : targetNode.kind === "directory"
            ? targetNode.path
            : parentPath(targetNode.path);
      void Promise.resolve(onNativeDropToken(token, targetDirectory)).catch(() => undefined);
    },
    [nodes, onNativeDropToken],
  );

  /** 把 Arborist selection 投影到 controller 的 typed node callback。 */
  const handleSelect = useCallback(
    (selected: NodeApi<WorkspaceFileNode>[]): void => {
      const node = selected[0];
      if (node !== undefined && node.data.path !== selectedPath) onSelect?.(node.data);
    },
    [onSelect, selectedPath],
  );

  /** 请求 controller 懒加载前先解析稳定 Arborist id。 */
  const handleDirectoryToggle = useCallback(
    (id: string): void => {
      const node = findTreeNodeById(nodes, id) ?? findTreeNode(nodes, id);
      if (node !== undefined) onDirectoryToggle?.(node);
    },
    [nodes, onDirectoryToggle],
  );

  /** action 状态变化时保持行 renderer identity 稳定，避免虚拟列表重建。 */
  const renderNode = useCallback(
    (props: NodeRendererProps<WorkspaceFileNode>): ReactElement => (
      <FileTreeNode
        {...props}
        renamePath={renamePath}
        renameValue={renameValue}
        onRenameValueChange={changeRenameValue}
        onRenameCommit={() => commitRename(props.node.data.path)}
        onRenameCancel={cancelRename}
        onPointerDown={onPointerDown}
        onContextMenu={onRowContextMenu}
        dragTargetPath={dragTargetPath}
      />
    ),
    [
      cancelRename,
      changeRenameValue,
      commitRename,
      dragTargetPath,
      onPointerDown,
      onRowContextMenu,
      renamePath,
      renameValue,
    ],
  );

  if (loading)
    return <LoadingState className="ja-feature-state ja-feature-loading" label="正在读取文件树…" />;
  if (error !== undefined)
    return (
      <ErrorState
        className="ja-feature-state ja-feature-error"
        title="文件树读取失败"
        message={error}
        onRetry={onRetry}
      />
    );

  return (
    <div
      className="ja-file-tree ja-file-tree-host"
      ref={hostRef}
      tabIndex={-1}
      data-testid="file-tree"
      onContextMenu={onHostContextMenu}
      onKeyDown={onTreeKeyDown}
      onDrop={onNativeDrop}
      onDragOver={(event) => {
        if (
          onNativeDropToken !== undefined &&
          event.dataTransfer.types.includes("application/x-ja-drop-token")
        ) {
          event.preventDefault();
          // native drop 留在 typed host，不转发给没有 active source item 的 Arborist drag manager。
          event.stopPropagation();
        }
      }}
    >
      {toolbarAvailable ? (
        <div className="ja-file-tree-toolbar" aria-label="文件操作">
          <div
            className={`ja-file-tree-row ja-file-tree-root${dragTargetPath === "" ? " is-drag-target" : ""}`}
            role="group"
            aria-label={onMove === undefined ? "工作区根目录" : "工作区根目录拖放区"}
            title={onMove === undefined ? "工作区根目录" : "拖到此处移动到工作区根目录"}
            data-drop-directory=""
            data-testid="file-tree-root-drop-zone"
          >
            <FolderOpen aria-hidden="true" className="ja-file-tree-icon" />
            <span className="ja-file-tree-name">工作区根目录</span>
          </div>
          {onCreateFile === undefined ? null : (
            <IconButton label="新建文件" onClick={() => startCreate("file", "")}>
              <FileIcon aria-hidden="true" />
            </IconButton>
          )}
          {onCreateDirectory === undefined ? null : (
            <IconButton label="新建目录" onClick={() => startCreate("directory", "")}>
              <Folder aria-hidden="true" />
            </IconButton>
          )}
          {onRefresh === undefined ? null : (
            <IconButton
              label="刷新文件树"
              onClick={() => void Promise.resolve(onRefresh("")).catch(() => undefined)}
            >
              <RefreshCw aria-hidden="true" />
            </IconButton>
          )}
          {fileExplorerTarget === undefined || onOpenTarget === undefined ? null : (
            <IconButton
              label="在文件资源管理器中打开工作区"
              onClick={() =>
                void Promise.resolve(onOpenTarget(fileExplorerTarget.target, "")).catch(
                  () => undefined,
                )
              }
            >
              <ExternalLink aria-hidden="true" />
            </IconButton>
          )}
          {createKind === undefined ? null : (
            <input
              autoFocus
              className="ja-file-tree-create-input"
              aria-label={createKind === "file" ? "新建文件名" : "新建目录名"}
              value={createValue}
              placeholder={createKind === "file" ? "文件名" : "目录名"}
              onChange={(event) => setCreateValue(event.target.value)}
              onBlur={commitCreate}
              onKeyDown={(event) => {
                if (event.key === "Enter") commitCreate();
                if (event.key === "Escape") cancelCreate();
              }}
            />
          )}
        </div>
      ) : null}
      <div
        ref={viewportRef}
        className="ja-file-tree-viewport"
        data-drop-directory=""
        data-testid="file-tree-viewport"
      >
        {nodes.length === 0 ? (
          <EmptyState className="ja-feature-state" title="工作区没有可显示的文件" />
        ) : viewportSize.width === 0 || viewportSize.height === 0 ? null : (
          <Tree<WorkspaceFileNode>
            data={nodes}
            width={viewportSize.width}
            height={viewportSize.height}
            rowHeight={30}
            overscanCount={8}
            indent={16}
            openByDefault={false}
            selection={selectedNodeId}
            selectionFollowsFocus
            disableMultiSelection
            disableDeselectOnClick
            disableEdit
            disableDrag
            disableDrop
            childrenAccessor={(item) =>
              item.kind === "directory" ? (item.children ?? (item.hasChildren ? [] : null)) : null
            }
            onSelect={handleSelect}
            onToggle={handleDirectoryToggle}
            aria-label="工作区文件"
          >
            {renderNode}
          </Tree>
        )}
      </div>
      {contextMenu === undefined ? null : (
        <div
          ref={contextMenuRef}
          className="ja-file-tree-context-menu"
          role="menu"
          aria-label={
            contextMenu.node === undefined
              ? "工作区根目录操作"
              : `${contextMenu.node.name} 文件操作`
          }
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onPointerDown={(event) => event.stopPropagation()}
          onContextMenu={(event) => {
            event.preventDefault();
            event.stopPropagation();
          }}
          onKeyDown={onContextMenuKeyDown}
        >
          {onAddToConversation === undefined ||
          contextMenu.node === undefined ||
          !isManagedEntry(contextMenu.node) ? null : (
            <button
              type="button"
              role="menuitem"
              onClick={() => {
                const node = contextMenu.node;
                if (node === undefined) return;
                closeContextMenu(false);
                onAddToConversation(node);
              }}
            >
              <span>添加到对话</span>
            </button>
          )}
          {onCreateFile === undefined ? null : (
            <button
              type="button"
              role="menuitem"
              onClick={() => startCreate("file", contextMenu.parentPath)}
            >
              <span>新建文件</span>
            </button>
          )}
          {onCreateDirectory === undefined ? null : (
            <button
              type="button"
              role="menuitem"
              onClick={() => startCreate("directory", contextMenu.parentPath)}
            >
              <span>新建目录</span>
            </button>
          )}
          {onRename === undefined ||
          contextMenu.node === undefined ||
          !isManagedEntry(contextMenu.node) ? null : (
            <button
              type="button"
              role="menuitem"
              aria-keyshortcuts="F2"
              onClick={() => {
                const node = contextMenu.node;
                if (node !== undefined) startRename(node);
              }}
            >
              <span>重命名</span>
              <kbd aria-hidden="true">F2</kbd>
            </button>
          )}
          {onTrash === undefined ||
          contextMenu.node === undefined ||
          !isManagedEntry(contextMenu.node) ? null : (
            <button
              type="button"
              role="menuitem"
              aria-keyshortcuts="Delete"
              className="is-danger"
              onClick={() => {
                const node = contextMenu.node;
                if (node === undefined) return;
                closeContextMenu(false);
                void Promise.resolve(onTrash(node)).catch(() => undefined);
              }}
            >
              <span>移入回收站</span>
              <kbd aria-hidden="true">Delete</kbd>
            </button>
          )}
          {!openMenuAvailable ||
          (contextMenu.node !== undefined && !isManagedEntry(contextMenu.node)) ? null : (
            <>
              <div className="ja-file-tree-context-separator" role="separator" />
              {openTargets.map((target) => (
                <button
                  type="button"
                  role="menuitem"
                  key={target.target}
                  onClick={() => {
                    const relativePath = contextMenu.node?.path ?? "";
                    closeContextMenu(false);
                    void Promise.resolve(onOpenTarget?.(target.target, relativePath)).catch(
                      () => undefined,
                    );
                  }}
                >
                  <span>
                    {openTargetLabel(
                      target.displayName,
                      target.target,
                      contextMenu.node !== undefined,
                    )}
                  </span>
                </button>
              ))}
            </>
          )}
          {onRefresh === undefined ? null : (
            <button
              type="button"
              role="menuitem"
              aria-keyshortcuts="F5"
              onClick={() => {
                const path = contextMenu.parentPath;
                closeContextMenu(true);
                void Promise.resolve(onRefresh(path)).catch(() => undefined);
              }}
            >
              <span>{contextMenu.node === undefined ? "刷新工作区" : "刷新此目录"}</span>
              <kbd aria-hidden="true">F5</kbd>
            </button>
          )}
        </div>
      )}
    </div>
  );
}
