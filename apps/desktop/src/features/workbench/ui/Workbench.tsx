// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Bot,
  Check,
  FileDiff,
  Files,
  Globe,
  MessageCircle,
  ListChecks,
  Plus,
  Terminal,
  X,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent,
  type PointerEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { RightPanelIcon } from "@/shared/ui/RightPanelIcon";
import {
  EmptyState,
  ErrorState,
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuSeparator,
  MenuTrigger,
} from "@/shared/ui/primitives";
import type {
  WorkbenchCapability,
  WorkbenchCapabilityTab,
  WorkbenchTab,
  WorkbenchTaskTab,
} from "../domain/tabs";
import { capabilityWorkbenchTab } from "../domain/tabs";
import { WorkbenchTabContextMenu } from "./WorkbenchTabContextMenu";
import "../Workbench.css";

export interface WorkbenchProps {
  selectedTab: WorkbenchTab;
  openTabs: readonly WorkbenchTab[];
  onTabChange: (tab: WorkbenchTab) => void;
  onOpenTabsChange: (tabs: readonly WorkbenchTab[]) => void;
  views: Partial<Record<Exclude<WorkbenchCapability, "new">, ReactNode>>;
  renderTaskView?: (tab: WorkbenchTaskTab) => ReactNode;
  onCreateSideTask?: () => WorkbenchTaskTab | undefined;
  capabilityShortcuts?: Partial<Record<WorkbenchCapability, string>>;
  conversationShortcut?: string;
  onClose?: () => void;
  onFocusConversation?: () => void;
  onTabClose?: (tab: WorkbenchTab) => void | Promise<void>;
  onTaskTabRename?: (tab: WorkbenchTaskTab, label: string) => Promise<void>;
  onTabContextMenuOpenChange?: (open: boolean) => void;
}

interface TaskTabRenameSession {
  readonly tabKey: string;
  readonly value: string;
  readonly failed: boolean;
}

interface TabContextMenuSession {
  readonly tabKey: string;
  readonly x: number;
  readonly y: number;
}

interface TabDefinition {
  value: WorkbenchCapability;
  label: string;
  Icon: typeof Files;
}

interface CapabilityMenuDefinition {
  value: WorkbenchCapability;
  label: string;
  Icon: typeof Files;
}

const TAB_DEFINITIONS: readonly TabDefinition[] = [
  { value: "review", label: "审查", Icon: FileDiff },
  { value: "files", label: "文件", Icon: Files },
  { value: "terminal", label: "终端", Icon: Terminal },
  { value: "preview", label: "浏览器", Icon: Globe },
  { value: "agents", label: "子智能体", Icon: Bot },
  { value: "plan", label: "计划", Icon: ListChecks },
  { value: "new", label: "新标签页", Icon: Plus },
];

const DEFAULT_SHORTCUTS: Partial<Record<WorkbenchCapability, string>> = {
  review: "Ctrl+Shift+G",
  terminal: "Ctrl+`",
  preview: "Ctrl+T",
  files: "Ctrl+P",
};

/** 只暴露已有真实投影的能力；`new` 是 Shell 自身能力，因此始终可用。 */
function availableTabDefinitions(views: WorkbenchProps["views"]): TabDefinition[] {
  return TAB_DEFINITIONS.filter(({ value }) => value === "new" || views[value] !== undefined);
}

/** 清理受控输入中的重复或不可用 Tab，但不在 Shell 内制造第二份持久状态。 */
function normalizeOpenTabs(
  tabs: readonly WorkbenchTab[],
  definitions: readonly TabDefinition[],
): WorkbenchTab[] {
  const available = new Set(definitions.map(({ value }) => value));
  const normalized: WorkbenchTab[] = [];
  const keys = new Set<string>();
  for (const tab of tabs) {
    if (keys.has(tab.key)) continue;
    if (tab.kind === "task" || available.has(tab.capability)) {
      normalized.push(tab);
      keys.add(tab.key);
    }
  }
  return normalized;
}

/** 标签来自受控描述符；文件路径、URL 或任务摘要正文都不会成为稳定 key。 */
function tabDisplayLabel(tab: WorkbenchTab, definitions: readonly TabDefinition[]): string {
  return tab.kind === "task"
    ? tab.label
    : (definitions.find(({ value }) => value === tab.capability)?.label ?? tab.label);
}

/** 识别跨 realm Promise，使原生资源关闭 ACK 可以形成可拒绝事务。 */
function isPromiseLike(value: void | Promise<void>): value is Promise<void> {
  return value !== undefined && typeof (value as { then?: unknown }).then === "function";
}

/** 关闭错误只包含稳定能力名称，不能把原生路径或诊断信息带入界面。 */
function tabCloseErrorMessage(tab: WorkbenchTab, definitions: readonly TabDefinition[]): string {
  return `${tabDisplayLabel(tab, definitions)}关闭失败，请重试。`;
}

/**
 * Workbench 是严格受控 Shell，只管理 Tab 打开、选择、排序与关闭意图。
 * feature 生命周期和内容由 composition root 通过 `views` 注入，Shell 不持有领域状态。
 */
export function Workbench({
  selectedTab,
  openTabs: controlledOpenTabs,
  onTabChange,
  onOpenTabsChange,
  views,
  renderTaskView,
  onCreateSideTask,
  capabilityShortcuts,
  conversationShortcut,
  onClose,
  onFocusConversation,
  onTabClose,
  onTaskTabRename,
  onTabContextMenuOpenChange,
}: WorkbenchProps): ReactElement {
  const definitions = availableTabDefinitions(views);
  const openTabs = normalizeOpenTabs(controlledOpenTabs, definitions);
  const activeTab = openTabs.find((tab) => tab.key === selectedTab.key) ?? openTabs[0];
  const openTabsKey = openTabs.map((tab) => tab.key).join("\u0000");
  const [draggingTab, setDraggingTab] = useState<string>();
  const [dropTarget, setDropTarget] = useState<string>();
  const [closingTab, setClosingTab] = useState<string>();
  const [closingSequence, setClosingSequence] = useState(false);
  const [closeError, setCloseError] = useState<{ tab: WorkbenchTab; message: string }>();
  const [taskTabRename, setTaskTabRename] = useState<TaskTabRenameSession>();
  const taskTabRenameKey = taskTabRename?.tabKey;
  const [renamingTaskTab, setRenamingTaskTab] = useState<string>();
  const [tabContextMenu, setTabContextMenu] = useState<TabContextMenuSession>();
  const dragRef = useRef<{ tabKey: string; pointerId: number; dropTargetKey: string } | undefined>(
    undefined,
  );
  const finishTabDragRef = useRef<(pointerId: number) => void>(() => undefined);
  const closingTabRef = useRef<string | undefined>(undefined);
  const closingSequenceRef = useRef(false);
  const tabContextMenuTriggerRef = useRef<HTMLButtonElement | undefined>(undefined);
  const contextMenuOpenRef = useRef(false);
  const mountedRef = useRef(false);
  const contextMenuOpenChangeRef = useRef(onTabContextMenuOpenChange);
  const onTabCloseRef = useRef(onTabClose);
  const openTabsRef = useRef(openTabs);
  const activeTabRef = useRef<WorkbenchTab | undefined>(activeTab);
  const openTabsKeyRef = useRef<string | undefined>(undefined);
  const workbenchRef = useRef<HTMLDivElement>(null);
  const pendingFocusTabRef = useRef<string | undefined>(undefined);
  const renameInputRef = useRef<HTMLInputElement | null>(null);
  const renameSubmittingRef = useRef<string | undefined>(undefined);
  const renameComposingRef = useRef(false);
  contextMenuOpenChangeRef.current = onTabContextMenuOpenChange;
  onTabCloseRef.current = onTabClose;

  /** 清理纯指针排序状态，不提交任何 Tab 变化。 */
  const clearTabDrag = useCallback((): void => {
    dragRef.current = undefined;
    setDraggingTab(undefined);
    setDropTarget(undefined);
  }, []);

  /** 基于最后一次已提交投影排序，避免 window pointerup 使用过期 render。 */
  const reorderTabs = (fromKey: string, toKey: string): void => {
    if (fromKey === toKey) return;
    const nextTabs = [...openTabsRef.current];
    const fromIndex = nextTabs.findIndex((tab) => tab.key === fromKey);
    const toIndex = nextTabs.findIndex((tab) => tab.key === toKey);
    if (fromIndex < 0 || toIndex < 0) return;
    const moved = nextTabs[fromIndex];
    if (moved === undefined) return;
    nextTabs.splice(fromIndex, 1);
    nextTabs.splice(toIndex, 0, moved);
    onOpenTabsChange(nextTabs);
  };

  /** 一次 pointer transaction 最多提交一次排序，随后立即释放临时状态。 */
  const finishTabDrag = (pointerId: number, releaseTargetKey?: string): void => {
    const drag = dragRef.current;
    if (drag === undefined || drag.pointerId !== pointerId) return;
    const targetKey = releaseTargetKey ?? drag.dropTargetKey;
    if (targetKey !== drag.tabKey) reorderTabs(drag.tabKey, targetKey);
    clearTabDrag();
  };

  useLayoutEffect(() => {
    finishTabDragRef.current = finishTabDrag;
  });

  useEffect(() => {
    /** window 级释放负责收口在 Tab 条外结束的排序事务。 */
    const finishPointer = (event: globalThis.PointerEvent): void => {
      finishTabDragRef.current(event.pointerId);
    };
    window.addEventListener("pointerup", finishPointer);
    window.addEventListener("pointercancel", finishPointer);
    window.addEventListener("blur", clearTabDrag);
    return () => {
      window.removeEventListener("pointerup", finishPointer);
      window.removeEventListener("pointercancel", finishPointer);
      window.removeEventListener("blur", clearTabDrag);
    };
  }, [clearTabDrag]);

  /** 卸载会使正在等待的关闭 ACK 失效，避免旧 Workspace 继续提交偏好或释放后续资源。 */
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  /** 真窗在 DOM 菜单显示前先隐藏原生 child WebView，关闭后再恢复当前 Preview。 */
  useLayoutEffect(() => {
    const open = tabContextMenu !== undefined;
    if (contextMenuOpenRef.current === open) return;
    contextMenuOpenRef.current = open;
    contextMenuOpenChangeRef.current?.(open);
  }, [tabContextMenu]);

  /** 非正常卸载同样发出关闭通知，避免原生 Preview 永久停留在隐藏态。 */
  useEffect(
    () => () => {
      if (contextMenuOpenRef.current) contextMenuOpenChangeRef.current?.(false);
    },
    [],
  );

  /** 外部受控投影移除菜单目标时同步关闭悬空菜单。 */
  useEffect(() => {
    if (
      tabContextMenu !== undefined &&
      !openTabs.some((tab) => tab.key === tabContextMenu.tabKey)
    ) {
      setTabContextMenu(undefined);
    }
  }, [openTabs, tabContextMenu]);

  useLayoutEffect(() => {
    const changed = activeTabRef.current !== activeTab || openTabsKeyRef.current !== openTabsKey;
    openTabsRef.current = openTabs;
    activeTabRef.current = activeTab;
    openTabsKeyRef.current = openTabsKey;
    if (changed && activeTab !== undefined) {
      workbenchRef.current
        ?.querySelector<HTMLButtonElement>(`[data-workbench-tab="${activeTab.key}"]`)
        ?.closest<HTMLElement>(".ja-workbench-tab-shell")
        ?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
    }
    const pendingFocus = pendingFocusTabRef.current;
    if (pendingFocus !== undefined && openTabs.some((tab) => tab.key === pendingFocus)) {
      const target = workbenchRef.current?.querySelector<HTMLButtonElement>(
        `[data-workbench-tab="${pendingFocus}"]`,
      );
      if (target !== null && target !== undefined) {
        pendingFocusTabRef.current = undefined;
        target.focus();
      }
    }
  }, [activeTab, openTabs, openTabsKey, taskTabRename]);

  useLayoutEffect(() => {
    if (taskTabRenameKey === undefined) return;
    renameInputRef.current?.focus();
    renameInputRef.current?.select();
  }, [taskTabRenameKey]);

  /** 只允许侧聊进入原位命名；选择与编辑共用同一稳定 Tab identity。 */
  const beginTaskTabRename = (tab: WorkbenchTaskTab): void => {
    if (
      tab.taskKind !== "side_task" ||
      onTaskTabRename === undefined ||
      renamingTaskTab !== undefined
    )
      return;
    onTabChange(tab);
    renameComposingRef.current = false;
    setTaskTabRename({ tabKey: tab.key, value: tab.label, failed: false });
  };

  /** Enter 与 blur 共用 single-flight 提交；失败保留输入供重试，空值或未变化只退出编辑。 */
  const submitTaskTabRename = (tab: WorkbenchTaskTab, value: string): void => {
    if (renameSubmittingRef.current !== undefined) return;
    const rename = onTaskTabRename;
    if (rename === undefined) return;
    const normalized = value.trim();
    if (normalized === "" || normalized === tab.label) {
      pendingFocusTabRef.current = tab.key;
      setTaskTabRename(undefined);
      return;
    }
    renameSubmittingRef.current = tab.key;
    setRenamingTaskTab(tab.key);
    setTaskTabRename((current) =>
      current?.tabKey === tab.key ? { ...current, failed: false } : current,
    );
    void rename(tab, normalized)
      .then(() => {
        pendingFocusTabRef.current = tab.key;
        setTaskTabRename((current) => (current?.tabKey === tab.key ? undefined : current));
      })
      .catch(() =>
        setTaskTabRename((current) =>
          current?.tabKey === tab.key ? { ...current, failed: true } : current,
        ),
      )
      .finally(() => {
        if (renameSubmittingRef.current !== tab.key) return;
        renameSubmittingRef.current = undefined;
        setRenamingTaskTab(undefined);
      });
  };

  /** F2 提供键盘入口；IME 组合中的 Enter 只确认候选，不提交标题。 */
  const handleTaskTabKeyDown = (event: KeyboardEvent<HTMLElement>, tab: WorkbenchTaskTab): void => {
    if (event.currentTarget instanceof HTMLInputElement) {
      if (event.key === "Escape" && renamingTaskTab === undefined) {
        event.preventDefault();
        pendingFocusTabRef.current = tab.key;
        setTaskTabRename(undefined);
        return;
      }
      if (event.key === "Enter") {
        event.preventDefault();
        if (!renameComposingRef.current && !event.nativeEvent.isComposing)
          submitTaskTabRename(tab, event.currentTarget.value);
      }
      return;
    }
    if (event.key === "F2") {
      event.preventDefault();
      beginTaskTabRename(tab);
    }
  };

  /**
   * 启动占位只在没有实际 Tab 时存在；首次选择会结束该状态，后续选择才追加实例。
   * 这里统一能力与侧聊入口，避免不同入口留下不可交互的“新标签页”。
   */
  const openTab = (tab: WorkbenchTab): void => {
    const actualTabs = openTabs.filter((openTab) => openTab.key !== "new");
    const nextTabs = actualTabs.some((openTab) => openTab.key === tab.key)
      ? actualTabs
      : [...actualTabs, tab];
    onTabChange(tab);
    if (
      nextTabs.length !== openTabs.length ||
      nextTabs.some((item, index) => item !== openTabs[index])
    ) {
      onOpenTabsChange(nextTabs);
    }
  };

  /** 打开能力只提交受控意图；真实 feature 初始化仍由 composition root 决定。 */
  const addTab = (capability: WorkbenchCapability): void => {
    openTab(capabilityWorkbenchTab(capability));
  };

  /** 创建由组合层交给服务端；Shell 只接纳已确认的任务描述符，不制造会话身份。 */
  const addSideTask = (): void => {
    const tab = onCreateSideTask?.();
    if (tab === undefined) return;
    openTab(tab);
  };

  /**
   * 资源释放成功后按 key 从最新受控投影提交关闭；同步推进 refs 让串行批量不等待 React
   * 重绘，同时活动 Tab 切换必须先于列表写入，避免偏好 store 把刚关闭项重新补回。
   */
  const commitTabClose = (tabKey: string): void => {
    const currentTabs = openTabsRef.current;
    const index = currentTabs.findIndex((openTab) => openTab.key === tabKey);
    if (index < 0) return;
    const nextTabs = currentTabs.filter((openTab) => openTab.key !== tabKey);
    openTabsRef.current = nextTabs;
    if (activeTabRef.current?.key === tabKey && nextTabs.length > 0) {
      const nextActive = nextTabs[Math.min(index, nextTabs.length - 1)];
      if (nextActive !== undefined) {
        activeTabRef.current = nextActive;
        pendingFocusTabRef.current = nextActive.key;
        onTabChange(nextActive);
      }
    } else if (nextTabs.length === 0) {
      activeTabRef.current = undefined;
    }
    onOpenTabsChange(nextTabs);
    setCloseError((current) => (current?.tab.key === tabKey ? undefined : current));
    if (nextTabs.length === 0) onClose?.();
  };

  /**
   * 单个 Tab 的 teardown 是关闭 ACK；目标在等待期间会按 key 回查最新投影，拒绝时保留
   * 当前及后续 Tab。回调引用更新不会取消已经开始的事务，也不会触发并发关闭。
   */
  const requestTabClose = (tabKey: string): Promise<boolean> => {
    if (!mountedRef.current || closingTabRef.current !== undefined) return Promise.resolve(false);
    const tab = openTabsRef.current.find((openTab) => openTab.key === tabKey);
    if (tab === undefined) return Promise.resolve(true);
    setCloseError((current) => (current?.tab.key === tab.key ? undefined : current));
    closingTabRef.current = tab.key;
    setClosingTab(tab.key);
    let teardown: void | Promise<void>;
    try {
      teardown = onTabCloseRef.current?.(tab);
    } catch {
      setCloseError({ tab, message: tabCloseErrorMessage(tab, definitions) });
      closingTabRef.current = undefined;
      setClosingTab(undefined);
      return Promise.resolve(false);
    }
    if (!isPromiseLike(teardown)) {
      commitTabClose(tab.key);
      closingTabRef.current = undefined;
      setClosingTab(undefined);
      return Promise.resolve(true);
    }
    return Promise.resolve(teardown)
      .then(() => {
        if (!mountedRef.current) return false;
        commitTabClose(tab.key);
        return true;
      })
      .catch(() => {
        if (mountedRef.current)
          setCloseError({ tab, message: tabCloseErrorMessage(tab, definitions) });
        return false;
      })
      .finally(() => {
        if (closingTabRef.current !== tab.key) return;
        closingTabRef.current = undefined;
        if (mountedRef.current) setClosingTab(undefined);
      });
  };

  /** 普通关闭同样经过全局 single-flight，批量事务运行时不会插入第二个资源释放。 */
  const closeTab = (tab: WorkbenchTab): void => {
    if (closingSequenceRef.current) return;
    void requestTabClose(tab.key);
  };

  /**
   * 批量关闭冻结目标 key 与视觉顺序，但每一步从最新受控投影取实体并等待 ACK；首个
   * 拒绝即停止，因此失败项和所有尚未处理的 Tab 都保持原样。
   */
  const closeTabsSerially = (tabKeys: readonly string[], preferredFocusKey?: string): void => {
    if (closingSequenceRef.current || closingTabRef.current !== undefined || tabKeys.length === 0)
      return;
    closingSequenceRef.current = true;
    setClosingSequence(true);
    void (async () => {
      try {
        for (const tabKey of tabKeys) {
          if (!mountedRef.current) break;
          if (!(await requestTabClose(tabKey))) break;
        }
      } finally {
        closingSequenceRef.current = false;
        if (mountedRef.current) {
          setClosingSequence(false);
          const focusKey = openTabsRef.current.some((tab) => tab.key === preferredFocusKey)
            ? preferredFocusKey
            : activeTabRef.current?.key;
          if (focusKey !== undefined) {
            window.requestAnimationFrame(() => {
              workbenchRef.current
                ?.querySelector<HTMLButtonElement>(`[data-workbench-tab="${focusKey}"]`)
                ?.focus();
            });
          }
        }
      }
    })();
  };

  /** 右键只建立菜单上下文，不选择 Tab；文本输入保留 WebView2 的原生编辑菜单。 */
  const openTabContextMenu = (event: MouseEvent<HTMLDivElement>, tab: WorkbenchTab): void => {
    const target = event.target instanceof Element ? event.target : undefined;
    if (
      target !== undefined &&
      target.closest("input, textarea, [contenteditable='true']") !== null
    )
      return;
    event.preventDefault();
    event.stopPropagation();
    clearTabDrag();
    tabContextMenuTriggerRef.current =
      event.currentTarget.querySelector<HTMLButtonElement>("[data-workbench-tab]") ?? undefined;
    setTabContextMenu({ tabKey: tab.key, x: event.clientX, y: event.clientY });
  };

  /** ContextMenu 与 Shift+F10 锚定 Tab 下缘，并保留原焦点供 Escape 恢复。 */
  const openTabContextMenuFromKeyboard = (
    event: KeyboardEvent<HTMLButtonElement>,
    tab: WorkbenchTab,
  ): boolean => {
    if (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10")) return false;
    event.preventDefault();
    event.stopPropagation();
    const bounds = event.currentTarget.getBoundingClientRect();
    tabContextMenuTriggerRef.current = event.currentTarget;
    setTabContextMenu({
      tabKey: tab.key,
      x: bounds.left + Math.min(24, Math.max(0, bounds.width)),
      y: bounds.bottom,
    });
    return true;
  };

  /** 记录排序起点；关闭按钮必须留在独立事务中。 */
  const handleTabPointerDown = (event: PointerEvent<HTMLDivElement>, tab: WorkbenchTab): void => {
    if (
      (event.target as HTMLElement).closest('[data-tab-close="true"],[data-tab-rename="true"]') !==
      null
    )
      return;
    if (event.button !== 0 || event.isPrimary === false) return;
    dragRef.current = { tabKey: tab.key, pointerId: event.pointerId, dropTargetKey: tab.key };
    setDraggingTab(tab.key);
    setDropTarget(tab.key);
  };

  /** 同步记录最后穿过的真实 Tab，供条外释放提交最终目标。 */
  const handleTabPointerEnter = (tab: WorkbenchTab): void => {
    if (dragRef.current === undefined) return;
    dragRef.current.dropTargetKey = tab.key;
    setDropTarget(tab.key);
  };

  const focusConversation = onFocusConversation ?? onClose;
  const menuDefinitions = capabilityMenuDefinitions(definitions);

  return (
    <div
      ref={workbenchRef}
      className="ja-workbench"
      data-active-tab={activeTab?.key}
      data-open-tabs={openTabs.map((tab) => tab.key).join(",")}
      aria-busy={
        closingTab === undefined && !closingSequence && renamingTaskTab === undefined
          ? undefined
          : true
      }
    >
      <header className="ja-workbench-tabbar">
        <div className="ja-workbench-tabs" role="tablist" aria-label="工作区标签">
          {openTabs.map((tab) => {
            const label = tabDisplayLabel(tab, definitions);
            const Icon =
              tab.kind === "task"
                ? Bot
                : (definitions.find(({ value }) => value === tab.capability)?.Icon ?? Files);
            const active = tab.key === activeTab?.key;
            const renameSession = taskTabRename?.tabKey === tab.key ? taskTabRename : undefined;
            return (
              <div
                key={tab.key}
                className="ja-workbench-tab-shell"
                data-state={active ? "active" : "inactive"}
                data-tab={tab.key}
                data-drop-target={dropTarget === tab.key ? "true" : undefined}
                aria-grabbed={draggingTab === tab.key}
                onPointerDown={(event) => handleTabPointerDown(event, tab)}
                onPointerEnter={() => handleTabPointerEnter(tab)}
                onPointerUp={(event) => finishTabDrag(event.pointerId, tab.key)}
                onPointerCancel={clearTabDrag}
                onLostPointerCapture={clearTabDrag}
                onContextMenu={(event) => openTabContextMenu(event, tab)}
              >
                {tab.kind === "task" && renameSession !== undefined ? (
                  <div
                    role="tab"
                    aria-selected={active}
                    data-workbench-tab={tab.key}
                    className="ja-workbench-tab is-renaming"
                  >
                    <Icon aria-hidden="true" />
                    <input
                      ref={renameInputRef}
                      data-tab-rename="true"
                      className="ja-workbench-tab-rename-input"
                      aria-label="侧聊名称"
                      aria-invalid={renameSession.failed || undefined}
                      title={renameSession.failed ? "重命名失败，请重试。" : undefined}
                      value={renameSession.value}
                      maxLength={96}
                      disabled={renamingTaskTab === tab.key}
                      onChange={(event) =>
                        setTaskTabRename({
                          tabKey: tab.key,
                          value: event.currentTarget.value,
                          failed: false,
                        })
                      }
                      onCompositionStart={() => {
                        renameComposingRef.current = true;
                      }}
                      onCompositionEnd={() => {
                        renameComposingRef.current = false;
                      }}
                      onKeyDown={(event) => handleTaskTabKeyDown(event, tab)}
                      onBlur={(event) => submitTaskTabRename(tab, event.currentTarget.value)}
                    />
                    {renameSession.failed ? (
                      <span className="ja-visually-hidden" role="alert">
                        重命名失败，请重试。
                      </span>
                    ) : null}
                  </div>
                ) : (
                  <button
                    type="button"
                    role="tab"
                    aria-selected={active}
                    data-workbench-tab={tab.key}
                    className="ja-workbench-tab"
                    title={
                      tab.kind === "task" &&
                      tab.taskKind === "side_task" &&
                      onTaskTabRename !== undefined
                        ? "双击或按 F2 重命名"
                        : undefined
                    }
                    onClick={() => onTabChange(tab)}
                    onDoubleClick={() => tab.kind === "task" && beginTaskTabRename(tab)}
                    onKeyDown={(event) => {
                      if (openTabContextMenuFromKeyboard(event, tab)) return;
                      if (tab.kind === "task") handleTaskTabKeyDown(event, tab);
                    }}
                  >
                    <Icon aria-hidden="true" />
                    <span>{label}</span>
                  </button>
                )}
                <IconButton
                  className="ja-workbench-tab-close"
                  data-tab-close="true"
                  label={closingTab === tab.key ? `正在关闭${label}` : `关闭${label}`}
                  disabled={
                    closingTab !== undefined || closingSequence || renamingTaskTab === tab.key
                  }
                  onPointerDown={(event) => event.stopPropagation()}
                  onClick={(event) => {
                    event.stopPropagation();
                    closeTab(tab);
                  }}
                >
                  <X aria-hidden="true" />
                </IconButton>
              </div>
            );
          })}
        </div>
        <WorkbenchCapabilityMenu
          definitions={menuDefinitions}
          activeTab={activeTab}
          shortcuts={capabilityShortcuts}
          conversationShortcut={conversationShortcut}
          onSelect={addTab}
          onFocusConversation={focusConversation}
          onCreateSideTask={onCreateSideTask === undefined ? undefined : addSideTask}
        />
        {onClose === undefined ? null : (
          <IconButton className="ja-workbench-drawer-toggle" label="收起右侧栏" onClick={onClose}>
            <RightPanelIcon />
          </IconButton>
        )}
      </header>
      {tabContextMenu === undefined
        ? null
        : (() => {
            const contextTab = openTabs.find((tab) => tab.key === tabContextMenu.tabKey);
            if (contextTab === undefined) return null;
            const contextIndex = openTabs.findIndex((tab) => tab.key === contextTab.key);
            const busy =
              closingSequence || closingTab !== undefined || renamingTaskTab !== undefined;
            return (
              <WorkbenchTabContextMenu
                label={tabDisplayLabel(contextTab, definitions)}
                x={tabContextMenu.x}
                y={tabContextMenu.y}
                busy={busy}
                canRename={
                  contextTab.kind === "task" &&
                  contextTab.taskKind === "side_task" &&
                  onTaskTabRename !== undefined
                }
                canCloseOthers={openTabs.length > 1}
                canCloseRight={contextIndex >= 0 && contextIndex < openTabs.length - 1}
                onOpenChange={(open) => {
                  if (!open) setTabContextMenu(undefined);
                }}
                onRestoreFocus={() => {
                  const trigger = tabContextMenuTriggerRef.current;
                  if (trigger?.isConnected) trigger.focus();
                }}
                onRename={() => {
                  if (contextTab.kind === "task") beginTaskTabRename(contextTab);
                }}
                onClose={() => closeTabsSerially([contextTab.key], contextTab.key)}
                onCloseOthers={() =>
                  closeTabsSerially(
                    openTabs.filter((tab) => tab.key !== contextTab.key).map((tab) => tab.key),
                    contextTab.key,
                  )
                }
                onCloseRight={() =>
                  closeTabsSerially(
                    openTabs.slice(contextIndex + 1).map((tab) => tab.key),
                    contextTab.key,
                  )
                }
                onCloseAll={() => closeTabsSerially(openTabs.map((tab) => tab.key))}
              />
            );
          })()}
      {closeError !== undefined && openTabs.some((tab) => tab.key === closeError.tab.key) ? (
        <ErrorState
          className="ja-feature-state ja-feature-error"
          title="关闭失败"
          message={closeError.message}
          onRetry={() => closeTab(closeError.tab)}
        />
      ) : null}
      {openTabs
        .filter(
          (tab): tab is WorkbenchCapabilityTab =>
            tab.kind === "capability" && tab.capability !== "new",
        )
        .map((tab) => (
          <div
            key={tab.key}
            className="ja-workbench-content"
            data-tab-panel={tab.key}
            hidden={activeTab?.key !== tab.key}
            inert={activeTab?.key !== tab.key}
          >
            <div className="ja-workbench-slot">
              {views[tab.capability as Exclude<WorkbenchCapability, "new">]}
            </div>
          </div>
        ))}
      {activeTab?.kind === "task" ? (
        <div className="ja-workbench-content" data-tab-panel={activeTab.key}>
          <div className="ja-workbench-slot">{renderTaskView?.(activeTab)}</div>
        </div>
      ) : null}
      {activeTab?.kind === "capability" && activeTab.capability === "new" ? (
        <div className="ja-workbench-content" data-tab-panel="new">
          <NewTabLauncher
            definitions={definitions}
            shortcuts={capabilityShortcuts}
            onSelect={addTab}
            focusConversation={focusConversation}
            conversationShortcut={conversationShortcut}
            onCreateSideTask={onCreateSideTask === undefined ? undefined : addSideTask}
          />
        </div>
      ) : null}
      {openTabs.length === 0 ? (
        <EmptyState className="ja-workbench-empty" title="工作区面板已收起" />
      ) : null}
    </div>
  );
}

/** 启动器只列出真实 feature，侧聊创建由组合层处理服务端 ACK。 */
function NewTabLauncher({
  definitions,
  shortcuts,
  onSelect,
  focusConversation,
  conversationShortcut,
  onCreateSideTask,
}: {
  definitions: readonly TabDefinition[];
  shortcuts?: Partial<Record<WorkbenchCapability, string>>;
  onSelect: (tab: WorkbenchCapability) => void;
  focusConversation?: () => void;
  conversationShortcut?: string;
  onCreateSideTask?: () => void;
}): ReactElement {
  /** 启动器顺序是稳定的产品导航约束，定义缺失时才从视图中收缩对应能力。 */
  const launcherDefinitions = (
    ["review", "terminal", "preview", "files", "agents", "plan"] as const
  )
    .map((value) => definitions.find((definition) => definition.value === value))
    .filter((definition): definition is TabDefinition => definition !== undefined);
  return (
    <section className="ja-workbench-launcher" aria-label="新标签页启动器">
      <div className="ja-workbench-launcher-heading">
        <Plus aria-hidden="true" />
        <div>
          <h2>打开工作区工具</h2>
          <p>选择一个能力在右侧标签页中继续。</p>
        </div>
      </div>
      <div className="ja-workbench-launcher-actions">
        {launcherDefinitions.map(({ value, label, Icon }) => (
          <button
            type="button"
            key={value}
            className="ja-workbench-launcher-action"
            onClick={() => onSelect(value)}
          >
            <Icon aria-hidden="true" />
            <span>{label}</span>
            <kbd>{shortcuts?.[value] ?? DEFAULT_SHORTCUTS[value] ?? ""}</kbd>
          </button>
        ))}
        {onCreateSideTask === undefined ? null : (
          <button type="button" className="ja-workbench-launcher-action" onClick={onCreateSideTask}>
            <MessageCircle aria-hidden="true" />
            <span>新建侧聊</span>
            <kbd />
          </button>
        )}
        {focusConversation === undefined ? null : (
          <button
            type="button"
            className="ja-workbench-launcher-action"
            onClick={focusConversation}
          >
            <MessageCircle aria-hidden="true" />
            <span>返回主对话</span>
            <kbd>{conversationShortcut ?? "Ctrl+Alt+S"}</kbd>
          </button>
        )}
      </div>
    </section>
  );
}

/** 菜单复用当前定义，确保 Tab 条、启动器和菜单没有各自的能力清单。 */
function capabilityMenuDefinitions(
  definitions: readonly TabDefinition[],
): CapabilityMenuDefinition[] {
  return definitions.filter(({ value }) => value !== "new");
}

/** `+` 统一承载可用 Tab 菜单；侧聊创建仍由 composition root 签发实例身份。 */
function WorkbenchCapabilityMenu({
  definitions,
  activeTab,
  shortcuts,
  conversationShortcut,
  onCreateSideTask,
  onSelect,
  onFocusConversation,
}: {
  definitions: readonly CapabilityMenuDefinition[];
  activeTab?: WorkbenchTab;
  shortcuts?: Partial<Record<WorkbenchCapability, string>>;
  conversationShortcut?: string;
  onSelect: (tab: WorkbenchCapability) => void;
  onFocusConversation?: () => void;
  onCreateSideTask?: () => void;
}): ReactElement {
  return (
    <Menu>
      <MenuTrigger asChild>
        <IconButton className="ja-workbench-add-tab" label="新建标签页">
          <Plus aria-hidden="true" />
        </IconButton>
      </MenuTrigger>
      <MenuContent
        className="ja-workbench-add-menu"
        align="end"
        sideOffset={6}
        aria-label="右侧栏能力"
      >
        {definitions.map(({ value, label, Icon }) => (
          <MenuItem
            key={value}
            className="ja-workbench-add-menu-item"
            onSelect={() => onSelect(value)}
          >
            <Icon aria-hidden="true" />
            <span>{label}</span>
            {shortcuts?.[value] === undefined ? null : <kbd>{shortcuts[value]}</kbd>}
            {activeTab?.kind === "capability" && value === activeTab.capability ? (
              <Check className="ja-workbench-menu-check" aria-hidden="true" />
            ) : null}
          </MenuItem>
        ))}
        {onCreateSideTask === undefined && onFocusConversation === undefined ? null : (
          <>
            <MenuSeparator className="ja-workbench-add-menu-separator" />
            {onCreateSideTask === undefined ? null : (
              <MenuItem className="ja-workbench-add-menu-item" onSelect={onCreateSideTask}>
                <MessageCircle aria-hidden="true" />
                <span>新建侧聊</span>
              </MenuItem>
            )}
            {onFocusConversation === undefined ? null : (
              <MenuItem className="ja-workbench-add-menu-item" onSelect={onFocusConversation}>
                <MessageCircle aria-hidden="true" />
                <span>返回主对话</span>
                <kbd>{conversationShortcut ?? "Ctrl+Alt+S"}</kbd>
              </MenuItem>
            )}
          </>
        )}
      </MenuContent>
    </Menu>
  );
}
