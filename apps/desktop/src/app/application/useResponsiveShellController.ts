// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useState } from "react";
import type { WorkbenchTabKey } from "@/features/workbench";
import { useMediaQuery } from "@/shared/hooks/useMediaQuery";
import {
  getRightPanelSessionState,
  useRightPanelSessionStore,
  useUiPreferencesStore,
} from "@/shared/preferences/uiPreferences";

const COMPACT_NAVIGATION_QUERY = "(max-width: 979px)";
// Windows 显示缩放会把 799px CDP viewport 投影为 799.333 CSS px；保留与 800px
// 桌面态之间的子像素间隙，才能让相邻断点在 WebView2 中稳定落到不同布局。
const SINGLE_PANE_WORKBENCH_QUERY = "(max-width: 799.98px)";

export interface ResponsiveShellController {
  readonly compactNavigation: boolean;
  readonly singlePaneWorkbench: boolean;
  readonly sidebarVisible: boolean;
  readonly sidebarPreviewRatio: number;
  readonly inspectorOpen: boolean;
  readonly workbenchSize: number;
  readonly workbenchVisible: boolean;
  readonly workbenchTab: WorkbenchTabKey;
  readonly workbenchTabs: readonly WorkbenchTabKey[];
  readonly setSidebarPreviewRatio: (ratio: number) => void;
  readonly commitSidebarRatio: (ratio: number) => void;
  readonly setWorkbenchPreviewSize: (size: number) => void;
  readonly commitWorkbenchSize: (size: number) => void;
  readonly setInspectorOpen: (open: boolean) => void;
  readonly setWorkbenchTab: (tab: WorkbenchTabKey) => void;
  readonly setWorkbenchTabs: (tabs: readonly WorkbenchTabKey[]) => void;
  readonly toggleInspector: () => void;
  readonly toggleSidebar: () => void;
  readonly closeSidebar: () => void;
}

/**
 * 响应式 Shell controller 让导航与工作台尺寸保持全局 preference，但把瞬态 Drawer
 * 绑定到 composition 提供的会话 scope。两个尺寸采用“预览后提交”，避免 pointermove
 * 高频写入 localStorage；右栏断点使用子像素上界以抵御 Windows 显示缩放取整差异。
 */
export function useResponsiveShellController(
  settingsRequired: boolean,
  settingsVisible: boolean,
  workspaceAvailable: boolean,
  rightPanelScopeIdentity?: string,
): ResponsiveShellController {
  const sidebarCollapsed = useUiPreferencesStore((state) => state.sidebarCollapsed);
  const setSidebarCollapsed = useUiPreferencesStore((state) => state.setSidebarCollapsed);
  const sidebarRatio = useUiPreferencesStore((state) => state.sidebarRatio);
  const setSidebarRatio = useUiPreferencesStore((state) => state.setSidebarRatio);
  const persistedWorkbenchSize = useUiPreferencesStore((state) => state.workbenchSize);
  const setWorkbenchSize = useUiPreferencesStore((state) => state.setWorkbenchSize);
  const rightPanelState = useRightPanelSessionStore((state) =>
    getRightPanelSessionState(state.scopes, rightPanelScopeIdentity),
  );
  const {
    inspectorOpen,
    rightPanelTab: workbenchTab,
    rightPanelTabs: workbenchTabs,
  } = rightPanelState;
  const [sidebarPreviewRatio, setSidebarPreviewRatio] = useState(sidebarRatio);
  const [workbenchPreviewSize, setWorkbenchPreviewSize] = useState(persistedWorkbenchSize);
  const [compactSidebarOpen, setCompactSidebarOpen] = useState(false);
  const compactNavigation = useMediaQuery(COMPACT_NAVIGATION_QUERY);
  const singlePaneWorkbench = useMediaQuery(SINGLE_PANE_WORKBENCH_QUERY);
  const sidebarVisible =
    !settingsRequired && (compactNavigation ? compactSidebarOpen : !sidebarCollapsed);
  const workbenchVisible = !settingsVisible && inspectorOpen && workspaceAvailable;

  /** preferences rehydrate 后同步 live resize 预览，不在 pointer move 时反复持久化。 */
  useEffect(() => {
    setSidebarPreviewRatio(sidebarRatio);
  }, [sidebarRatio]);

  /** 恢复或外部更新持久值时同步右栏预览；拖动期间仍由分隔器控制本地状态。 */
  useEffect(() => {
    setWorkbenchPreviewSize(persistedWorkbenchSize);
  }, [persistedWorkbenchSize]);

  /** breakpoint 或专属页面变化时关闭瞬态 Drawer，但保留桌面 collapse preference。 */
  useEffect(() => {
    setCompactSidebarOpen(false);
  }, [compactNavigation, settingsVisible]);

  /**
   * setter 捕获创建它的 scope；异步操作晚到时仍只写回发起会话，空会话则不产生共享状态。
   */
  const setInspectorOpen = useCallback(
    (open: boolean): void => {
      if (!rightPanelScopeIdentity) return;
      useRightPanelSessionStore.getState().setInspectorOpen(rightPanelScopeIdentity, open);
    },
    [rightPanelScopeIdentity],
  );

  /** 选择能力只更新回调创建时的会话 scope，避免旧请求改写当前对话。 */
  const setWorkbenchTab = useCallback(
    (tab: WorkbenchTabKey): void => {
      if (!rightPanelScopeIdentity) return;
      useRightPanelSessionStore.getState().setRightPanelTab(rightPanelScopeIdentity, tab);
    },
    [rightPanelScopeIdentity],
  );

  /** 打开列表只更新回调创建时的会话 scope，并保留关闭最后一项时的折叠语义。 */
  const setWorkbenchTabs = useCallback(
    (tabs: readonly WorkbenchTabKey[]): void => {
      if (!rightPanelScopeIdentity) return;
      useRightPanelSessionStore.getState().setRightPanelTabs(rightPanelScopeIdentity, tabs);
    },
    [rightPanelScopeIdentity],
  );

  /** 次级工作面切换所属会话的进程期抽屉；宽度偏好仍独立全局保留。 */
  const toggleInspector = useCallback((): void => {
    if (!rightPanelScopeIdentity) return;
    useRightPanelSessionStore.getState().toggleInspector(rightPanelScopeIdentity);
  }, [rightPanelScopeIdentity]);

  /** 紧凑宽度修改瞬态 Drawer，桌面宽度才修改持久 collapse preference。 */
  const toggleSidebar = useCallback((): void => {
    if (compactNavigation) setCompactSidebarOpen((open) => !open);
    else setSidebarCollapsed(!useUiPreferencesStore.getState().sidebarCollapsed);
  }, [compactNavigation, setSidebarCollapsed]);

  /** 关闭当前 sidebar mode，不修改另一种布局的 preference。 */
  const closeSidebar = useCallback((): void => {
    if (compactNavigation) setCompactSidebarOpen(false);
    else setSidebarCollapsed(true);
  }, [compactNavigation, setSidebarCollapsed]);

  /** sidebar resize commit 同步预览与持久值，避免鼠标释放后视觉回跳。 */
  const commitSidebarRatio = useCallback(
    (ratio: number): void => {
      setSidebarPreviewRatio(ratio);
      setSidebarRatio(ratio);
    },
    [setSidebarRatio],
  );

  /** workbench resize commit 同步预览与持久值，鼠标释放后不会因 store clamp 发生回跳。 */
  const commitWorkbenchSize = useCallback(
    (size: number): void => {
      setWorkbenchPreviewSize(size);
      setWorkbenchSize(size);
    },
    [setWorkbenchSize],
  );

  return {
    compactNavigation,
    singlePaneWorkbench,
    sidebarVisible,
    sidebarPreviewRatio,
    inspectorOpen,
    workbenchSize: workbenchPreviewSize,
    workbenchVisible,
    workbenchTab,
    workbenchTabs,
    setSidebarPreviewRatio,
    commitSidebarRatio,
    setWorkbenchPreviewSize,
    commitWorkbenchSize,
    setInspectorOpen,
    setWorkbenchTab,
    setWorkbenchTabs,
    toggleInspector,
    toggleSidebar,
    closeSidebar,
  };
}
