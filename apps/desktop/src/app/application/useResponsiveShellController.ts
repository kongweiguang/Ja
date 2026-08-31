// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useState } from "react";
import type { WorkbenchTab } from "@/features/workbench";
import { useMediaQuery } from "@/shared/hooks/useMediaQuery";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";

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
  readonly workbenchTab: WorkbenchTab;
  readonly workbenchTabs: readonly WorkbenchTab[];
  readonly setSidebarPreviewRatio: (ratio: number) => void;
  readonly commitSidebarRatio: (ratio: number) => void;
  readonly setWorkbenchPreviewSize: (size: number) => void;
  readonly commitWorkbenchSize: (size: number) => void;
  readonly setInspectorOpen: (open: boolean) => void;
  readonly setWorkbenchTab: (tab: WorkbenchTab) => void;
  readonly setWorkbenchTabs: (tabs: readonly WorkbenchTab[]) => void;
  readonly toggleInspector: () => void;
  readonly toggleSidebar: () => void;
  readonly closeSidebar: () => void;
}

/**
 * 响应式 Shell controller 只拥有导航、工作台尺寸 preference 与瞬态 Drawer；两个尺寸
 * 都采用“预览后提交”，避免 pointermove 高频写入 localStorage。右栏断点刻意使用子像素
 * 上界，以抵御 Windows 显示缩放造成的 CSS viewport 取整差异。
 */
export function useResponsiveShellController(
  settingsRequired: boolean,
  settingsVisible: boolean,
  workspaceAvailable: boolean,
): ResponsiveShellController {
  const sidebarCollapsed = useUiPreferencesStore((state) => state.sidebarCollapsed);
  const setSidebarCollapsed = useUiPreferencesStore((state) => state.setSidebarCollapsed);
  const sidebarRatio = useUiPreferencesStore((state) => state.sidebarRatio);
  const setSidebarRatio = useUiPreferencesStore((state) => state.setSidebarRatio);
  const persistedWorkbenchSize = useUiPreferencesStore((state) => state.workbenchSize);
  const setWorkbenchSize = useUiPreferencesStore((state) => state.setWorkbenchSize);
  const inspectorOpen = useUiPreferencesStore((state) => state.inspectorOpen);
  const setInspectorOpen = useUiPreferencesStore((state) => state.setInspectorOpen);
  const workbenchTab = useUiPreferencesStore((state) => state.rightPanelTab);
  const setWorkbenchTab = useUiPreferencesStore((state) => state.setRightPanelTab);
  const workbenchTabs = useUiPreferencesStore((state) => state.rightPanelTabs);
  const setWorkbenchTabs = useUiPreferencesStore((state) => state.setRightPanelTabs);
  const [sidebarPreviewRatio, setSidebarPreviewRatio] = useState(sidebarRatio);
  const [workbenchPreviewSize, setWorkbenchPreviewSize] = useState(persistedWorkbenchSize);
  const [compactSidebarOpen, setCompactSidebarOpen] = useState(false);
  const compactNavigation = useMediaQuery(COMPACT_NAVIGATION_QUERY);
  const singlePaneWorkbench = useMediaQuery(SINGLE_PANE_WORKBENCH_QUERY);
  const sidebarVisible =
    !settingsRequired && (compactNavigation ? compactSidebarOpen : !sidebarCollapsed);
  const workbenchVisible = !settingsVisible && inspectorOpen && workspaceAvailable;

  /**
   * Tauri/WebView2 可能在同一浏览器进程中复用 renderer 与 HMR store，单靠 persist merge
   * 不能覆盖这种进程边界。Shell 每次挂载都关闭瞬态 inspector，确保重启后的首屏一致。
   */
  useEffect(() => {
    setInspectorOpen(false);
  }, [setInspectorOpen]);

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

  /** 次级工作面只切换进程期抽屉；宽度偏好独立保留，不让开闭动作改写布局。 */
  const toggleInspector = useCallback((): void => {
    setInspectorOpen(!inspectorOpen);
  }, [inspectorOpen, setInspectorOpen]);

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
