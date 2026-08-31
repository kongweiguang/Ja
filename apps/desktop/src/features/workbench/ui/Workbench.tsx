// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Check,
  FileDiff,
  Files,
  Globe,
  ListFilter,
  MessageCircle,
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
import type { WorkbenchCapabilityTab, WorkbenchTab } from "../domain/tabs";
import "../Workbench.css";

export interface WorkbenchProps {
  selectedTab: WorkbenchTab;
  openTabs: readonly WorkbenchTab[];
  onTabChange: (tab: WorkbenchTab) => void;
  onOpenTabsChange: (tabs: readonly WorkbenchTab[]) => void;
  views: Partial<Record<Exclude<WorkbenchCapabilityTab, "new">, ReactNode>>;
  capabilityShortcuts?: Partial<Record<WorkbenchTab, string>>;
  conversationShortcut?: string;
  onClose?: () => void;
  onFocusConversation?: () => void;
  onTabClose?: (tab: WorkbenchCapabilityTab) => void | Promise<void>;
}

interface TabDefinition {
  value: WorkbenchCapabilityTab;
  label: string;
  Icon: typeof Files;
}

interface CapabilityMenuDefinition {
  value: WorkbenchTab;
  label: string;
  Icon: typeof Files;
}

const TAB_DEFINITIONS: readonly TabDefinition[] = [
  { value: "review", label: "审查", Icon: FileDiff },
  { value: "files", label: "文件", Icon: Files },
  { value: "terminal", label: "终端", Icon: Terminal },
  { value: "preview", label: "浏览器", Icon: Globe },
  { value: "new", label: "新标签页", Icon: Plus },
];

const DEFAULT_SHORTCUTS: Partial<Record<WorkbenchCapabilityTab, string>> = {
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
): WorkbenchCapabilityTab[] {
  const available = new Set(definitions.map(({ value }) => value));
  const normalized: WorkbenchCapabilityTab[] = [];
  for (const tab of tabs) {
    if (available.has(tab) && !normalized.includes(tab)) normalized.push(tab);
  }
  return normalized;
}

/** 使用稳定能力名称，避免文件或 URL 变化反向污染 Shell 的 Tab 身份。 */
function tabDisplayLabel(
  tab: WorkbenchCapabilityTab,
  definitions: readonly TabDefinition[],
): string {
  return definitions.find(({ value }) => value === tab)?.label ?? tab;
}

/** 识别跨 realm Promise，使原生资源关闭 ACK 可以形成可拒绝事务。 */
function isPromiseLike(value: void | Promise<void>): value is Promise<void> {
  return value !== undefined && typeof (value as { then?: unknown }).then === "function";
}

/** 关闭错误只包含稳定能力名称，不能把原生路径或诊断信息带入界面。 */
function tabCloseErrorMessage(
  tab: WorkbenchCapabilityTab,
  definitions: readonly TabDefinition[],
): string {
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
  capabilityShortcuts,
  conversationShortcut,
  onClose,
  onFocusConversation,
  onTabClose,
}: WorkbenchProps): ReactElement {
  const definitions = availableTabDefinitions(views);
  const openTabs = normalizeOpenTabs(controlledOpenTabs, definitions);
  const activeTab = openTabs.includes(selectedTab) ? selectedTab : openTabs[0];
  const openTabsKey = openTabs.join("\u0000");
  const [draggingTab, setDraggingTab] = useState<WorkbenchCapabilityTab>();
  const [dropTarget, setDropTarget] = useState<WorkbenchCapabilityTab>();
  const [closingTab, setClosingTab] = useState<WorkbenchCapabilityTab>();
  const [closeError, setCloseError] = useState<{ tab: WorkbenchCapabilityTab; message: string }>();
  const dragRef = useRef<
    | { tab: WorkbenchCapabilityTab; pointerId: number; dropTarget: WorkbenchCapabilityTab }
    | undefined
  >(undefined);
  const finishTabDragRef = useRef<(pointerId: number) => void>(() => undefined);
  const closingTabRef = useRef<WorkbenchCapabilityTab | undefined>(undefined);
  const openTabsRef = useRef(openTabs);
  const activeTabRef = useRef<WorkbenchCapabilityTab | undefined>(activeTab);
  const openTabsKeyRef = useRef<string | undefined>(undefined);
  const workbenchRef = useRef<HTMLDivElement>(null);
  const pendingFocusTabRef = useRef<WorkbenchCapabilityTab | undefined>(undefined);

  /** 清理纯指针排序状态，不提交任何 Tab 变化。 */
  const clearTabDrag = useCallback((): void => {
    dragRef.current = undefined;
    setDraggingTab(undefined);
    setDropTarget(undefined);
  }, []);

  /** 基于最后一次已提交投影排序，避免 window pointerup 使用过期 render。 */
  const reorderTabs = (from: WorkbenchCapabilityTab, to: WorkbenchCapabilityTab): void => {
    if (from === to) return;
    const nextTabs = [...openTabsRef.current];
    const fromIndex = nextTabs.indexOf(from);
    const toIndex = nextTabs.indexOf(to);
    if (fromIndex < 0 || toIndex < 0) return;
    nextTabs.splice(fromIndex, 1);
    nextTabs.splice(toIndex, 0, from);
    onOpenTabsChange(nextTabs);
  };

  /** 一次 pointer transaction 最多提交一次排序，随后立即释放临时状态。 */
  const finishTabDrag = (pointerId: number, releaseTarget?: WorkbenchCapabilityTab): void => {
    const drag = dragRef.current;
    if (drag === undefined || drag.pointerId !== pointerId) return;
    const target = releaseTarget ?? drag.dropTarget;
    if (target !== drag.tab) reorderTabs(drag.tab, target);
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

  useLayoutEffect(() => {
    const changed = activeTabRef.current !== activeTab || openTabsKeyRef.current !== openTabsKey;
    openTabsRef.current = openTabs;
    activeTabRef.current = activeTab;
    openTabsKeyRef.current = openTabsKey;
    if (changed && activeTab !== undefined) {
      workbenchRef.current
        ?.querySelector<HTMLButtonElement>(`[data-workbench-tab="${activeTab}"]`)
        ?.closest<HTMLElement>(".ja-workbench-tab-shell")
        ?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
    }
    const pendingFocus = pendingFocusTabRef.current;
    if (pendingFocus !== undefined && openTabs.includes(pendingFocus)) {
      const target = workbenchRef.current?.querySelector<HTMLButtonElement>(
        `[data-workbench-tab="${pendingFocus}"]`,
      );
      if (target !== null && target !== undefined) {
        pendingFocusTabRef.current = undefined;
        target.focus();
      }
    }
  }, [activeTab, openTabs, openTabsKey]);

  /** 打开动作只提交受控意图；真实 feature 初始化仍由 composition root 决定。 */
  const addTab = (tab: WorkbenchCapabilityTab): void => {
    if (!openTabs.includes(tab)) onOpenTabsChange([...openTabs, tab]);
    onTabChange(tab);
  };

  /** 资源释放成功后再提交关闭，活动 Tab 切换先于列表写入以避免归一化回插。 */
  const commitTabClose = (tab: WorkbenchCapabilityTab): void => {
    const currentTabs = openTabsRef.current;
    const index = currentTabs.indexOf(tab);
    if (index < 0) return;
    const nextTabs = currentTabs.filter((openTab) => openTab !== tab);
    if (activeTabRef.current === tab && nextTabs.length > 0) {
      const nextActive = nextTabs[Math.min(index, nextTabs.length - 1)];
      if (nextActive !== undefined) {
        pendingFocusTabRef.current = nextActive;
        onTabChange(nextActive);
      }
    }
    onOpenTabsChange(nextTabs);
    setCloseError((current) => (current?.tab === tab ? undefined : current));
    if (nextTabs.length === 0) onClose?.();
  };

  /** 异步 teardown 是关闭事务的 ACK；拒绝时保留 Tab 并只提供同一动作重试。 */
  const closeTab = (tab: WorkbenchCapabilityTab): void => {
    if (closingTabRef.current !== undefined || !openTabsRef.current.includes(tab)) return;
    setCloseError((current) => (current?.tab === tab ? undefined : current));
    let teardown: void | Promise<void>;
    try {
      teardown = onTabClose?.(tab);
    } catch {
      setCloseError({ tab, message: tabCloseErrorMessage(tab, definitions) });
      return;
    }
    if (!isPromiseLike(teardown)) {
      commitTabClose(tab);
      return;
    }
    closingTabRef.current = tab;
    setClosingTab(tab);
    void Promise.resolve(teardown)
      .then(() => commitTabClose(tab))
      .catch(() => setCloseError({ tab, message: tabCloseErrorMessage(tab, definitions) }))
      .finally(() => {
        if (closingTabRef.current !== tab) return;
        closingTabRef.current = undefined;
        setClosingTab(undefined);
      });
  };

  /** 记录排序起点；关闭按钮必须留在独立事务中。 */
  const handleTabPointerDown = (
    event: PointerEvent<HTMLDivElement>,
    tab: WorkbenchCapabilityTab,
  ): void => {
    if ((event.target as HTMLElement).closest('[data-tab-close="true"]') !== null) return;
    if (event.button !== 0 || event.isPrimary === false) return;
    dragRef.current = { tab, pointerId: event.pointerId, dropTarget: tab };
    setDraggingTab(tab);
    setDropTarget(tab);
  };

  /** 同步记录最后穿过的真实 Tab，供条外释放提交最终目标。 */
  const handleTabPointerEnter = (tab: WorkbenchCapabilityTab): void => {
    if (dragRef.current === undefined) return;
    dragRef.current.dropTarget = tab;
    setDropTarget(tab);
  };

  const focusConversation = onFocusConversation ?? onClose;
  const menuDefinitions = capabilityMenuDefinitions(definitions);

  return (
    <div
      ref={workbenchRef}
      className="ja-workbench"
      data-active-tab={activeTab}
      data-open-tabs={openTabs.join(",")}
      aria-busy={closingTab === undefined ? undefined : true}
    >
      <header className="ja-workbench-tabbar">
        <div className="ja-workbench-tabs" role="tablist" aria-label="工作区标签">
          {openTabs.map((tab) => {
            const label = tabDisplayLabel(tab, definitions);
            const Icon = definitions.find(({ value }) => value === tab)?.Icon ?? Files;
            const active = tab === activeTab;
            return (
              <div
                key={tab}
                className="ja-workbench-tab-shell"
                data-state={active ? "active" : "inactive"}
                data-tab={tab}
                data-drop-target={dropTarget === tab ? "true" : undefined}
                aria-grabbed={draggingTab === tab}
                onPointerDown={(event) => handleTabPointerDown(event, tab)}
                onPointerEnter={() => handleTabPointerEnter(tab)}
                onPointerUp={(event) => finishTabDrag(event.pointerId, tab)}
                onPointerCancel={clearTabDrag}
                onLostPointerCapture={clearTabDrag}
              >
                <button
                  type="button"
                  role="tab"
                  aria-selected={active}
                  data-workbench-tab={tab}
                  className="ja-workbench-tab"
                  onClick={() => onTabChange(tab)}
                >
                  <Icon aria-hidden="true" />
                  <span>{label}</span>
                </button>
                <IconButton
                  className="ja-workbench-tab-close"
                  data-tab-close="true"
                  label={closingTab === tab ? `正在关闭${label}` : `关闭${label}`}
                  disabled={closingTab !== undefined}
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
        <IconButton
          className="ja-workbench-add-tab"
          label="新建标签页"
          onClick={() => addTab("new")}
        >
          <Plus aria-hidden="true" />
        </IconButton>
        <WorkbenchCapabilityMenu
          definitions={menuDefinitions}
          activeTab={activeTab}
          shortcuts={capabilityShortcuts}
          conversationShortcut={conversationShortcut}
          onSelect={addTab}
          onFocusConversation={focusConversation}
        />
        {onClose === undefined ? null : (
          <IconButton className="ja-workbench-drawer-toggle" label="收起右侧栏" onClick={onClose}>
            <RightPanelIcon />
          </IconButton>
        )}
      </header>
      {closeError !== undefined && openTabs.includes(closeError.tab) ? (
        <ErrorState
          className="ja-feature-state ja-feature-error"
          title="关闭失败"
          message={closeError.message}
          onRetry={() => closeTab(closeError.tab)}
        />
      ) : null}
      {openTabs
        .filter((tab) => tab !== "new")
        .map((tab) => (
          <div
            key={tab}
            className="ja-workbench-content"
            data-tab-panel={tab}
            hidden={activeTab !== tab}
            inert={activeTab !== tab}
          >
            <div className="ja-workbench-slot">{views[tab]}</div>
          </div>
        ))}
      {openTabs.includes("new") && activeTab === "new" ? (
        <div className="ja-workbench-content" data-tab-panel="new">
          <NewTabLauncher
            definitions={definitions}
            shortcuts={capabilityShortcuts}
            onSelect={addTab}
            focusConversation={focusConversation}
            conversationShortcut={conversationShortcut}
          />
        </div>
      ) : null}
      {openTabs.length === 0 ? (
        <EmptyState className="ja-workbench-empty" title="工作区面板已收起" />
      ) : null}
    </div>
  );
}

/** 启动器只列出有真实投影的 feature，不创建占位能力。 */
function NewTabLauncher({
  definitions,
  shortcuts,
  onSelect,
  focusConversation,
  conversationShortcut,
}: {
  definitions: readonly TabDefinition[];
  shortcuts?: Partial<Record<WorkbenchTab, string>>;
  onSelect: (tab: WorkbenchCapabilityTab) => void;
  focusConversation?: () => void;
  conversationShortcut?: string;
}): ReactElement {
  const launcherDefinitions = (["review", "terminal", "preview", "files"] as const)
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
        {focusConversation === undefined ? null : (
          <button
            type="button"
            className="ja-workbench-launcher-action"
            onClick={focusConversation}
          >
            <MessageCircle aria-hidden="true" />
            <span>侧边聊天</span>
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

/** 能力菜单只发出 Tab 意图，快捷键处理仍由 App composition 统一拥有。 */
function WorkbenchCapabilityMenu({
  definitions,
  activeTab,
  shortcuts,
  conversationShortcut,
  onSelect,
  onFocusConversation,
}: {
  definitions: readonly CapabilityMenuDefinition[];
  activeTab?: WorkbenchCapabilityTab;
  shortcuts?: Partial<Record<WorkbenchTab, string>>;
  conversationShortcut?: string;
  onSelect: (tab: WorkbenchTab) => void;
  onFocusConversation?: () => void;
}): ReactElement {
  return (
    <Menu>
      <MenuTrigger asChild>
        <IconButton className="ja-workbench-capability-menu" label="打开右侧栏能力">
          <ListFilter aria-hidden="true" />
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
            {value === activeTab ? (
              <Check className="ja-workbench-menu-check" aria-hidden="true" />
            ) : null}
          </MenuItem>
        ))}
        {onFocusConversation === undefined ? null : (
          <>
            <MenuSeparator className="ja-workbench-add-menu-separator" />
            <MenuItem className="ja-workbench-add-menu-item" onSelect={onFocusConversation}>
              <MessageCircle aria-hidden="true" />
              <span>侧边聊天</span>
              <kbd>{conversationShortcut ?? "Ctrl+Alt+S"}</kbd>
            </MenuItem>
          </>
        )}
      </MenuContent>
    </Menu>
  );
}
