// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";

export type ThemeMode = "system" | "light" | "dark";
/** Ja 只保留一套语义色板，字面量类型用于阻止界面重新引入并行主题系统。 */
export type UiPalette = "xcode";

/** Workbench 只持久化当前真实能力，旧 Tab id 不进入当前 schema。 */
export type RightPanelTab = "review" | "files" | "terminal" | "preview" | "new";

const RIGHT_PANEL_TABS: readonly RightPanelTab[] = [
  "review",
  "files",
  "terminal",
  "preview",
  "new",
];
const DEFAULT_RIGHT_PANEL_TABS: readonly RightPanelTab[] = ["review", "files", "preview"];

export const SIDEBAR_WIDTH_MIN = 220;
export const SIDEBAR_WIDTH_MAX = 960;
export const SIDEBAR_WIDTH_DEFAULT = 240;
/** 导航栏保留相对视口比例，避免 DPI 与窗口尺寸变化后产生固定像素跳变。 */
export const SIDEBAR_RATIO_DEFAULT = 20.273;
export const SIDEBAR_RATIO_MIN = 12;
export const SIDEBAR_RATIO_MAX = 36;
/** Workbench 使用独立宽度比例，边界同时保护会话阅读区与右侧工具的可用尺寸。 */
export const WORKBENCH_SIZE_DEFAULT = 33.725;
export const WORKBENCH_SIZE_MIN = 24;
export const WORKBENCH_SIZE_MAX = 60;
export interface UiPreferences {
  themeMode: ThemeMode;
  palette: UiPalette;
  highContrast: boolean;
  reduceMotion: boolean;
  desktopNotifications: boolean;
  sidebarCollapsed: boolean;
  projectSectionCollapsed: boolean;
  historySectionCollapsed: boolean;
  sidebarWidth: number;
  sidebarRatio: number;
  workbenchSize: number;
  inspectorOpen: boolean;
  rightPanelTab: RightPanelTab;
  rightPanelTabs: RightPanelTab[];
}

export interface UiPreferencesStore extends UiPreferences {
  setThemeMode: (themeMode: ThemeMode) => void;
  setPalette: (palette: UiPalette) => void;
  setHighContrast: (highContrast: boolean) => void;
  setReduceMotion: (reduceMotion: boolean) => void;
  setDesktopNotifications: (desktopNotifications: boolean) => void;
  setSidebarCollapsed: (sidebarCollapsed: boolean) => void;
  setProjectSectionCollapsed: (projectSectionCollapsed: boolean) => void;
  setHistorySectionCollapsed: (historySectionCollapsed: boolean) => void;
  setSidebarWidth: (sidebarWidth: number) => void;
  setSidebarRatio: (sidebarRatio: number) => void;
  setWorkbenchSize: (workbenchSize: number) => void;
  setInspectorOpen: (inspectorOpen: boolean) => void;
  setRightPanelTab: (rightPanelTab: UiPreferences["rightPanelTab"]) => void;
  setRightPanelTabs: (rightPanelTabs: readonly RightPanelTab[]) => void;
}

const initialPreferences: UiPreferences = {
  themeMode: "system",
  palette: "xcode",
  highContrast: false,
  reduceMotion: false,
  desktopNotifications: false,
  sidebarCollapsed: false,
  projectSectionCollapsed: false,
  historySectionCollapsed: false,
  sidebarWidth: SIDEBAR_WIDTH_DEFAULT,
  sidebarRatio: SIDEBAR_RATIO_DEFAULT,
  workbenchSize: WORKBENCH_SIZE_DEFAULT,
  inspectorOpen: false,
  rightPanelTab: "files",
  rightPanelTabs: [...DEFAULT_RIGHT_PANEL_TABS],
};

/** 只接受当前色板；未知值回到默认值而不解释任何历史 Palette。 */
export function normalizeUiPalette(value: unknown): UiPalette {
  return value === "xcode" ? value : "xcode";
}

/**
 * 校验 preference store 持久化的小型 enum；原生 Settings 文档有自己的 Schema，
 * 因此非法 UI-only 值必须在本地修复，不能泄漏到文档级 theme。
 */
function normalizeThemeMode(value: unknown): ThemeMode {
  return value === "light" || value === "dark" || value === "system" ? value : "system";
}

/** 只接受当前可见 Tab；非法介质值回到 Files，绝不推断旧能力别名。 */
export function normalizeRightPanelTab(value: unknown): RightPanelTab {
  switch (value) {
    case "review":
      return "review";
    case "files":
      return "files";
    case "terminal":
      return "terminal";
    case "preview":
      return "preview";
    case "new":
      return "new";
    default:
      return "files";
  }
}

/** 修复持久化 Tab 顺序，同时保留关闭最后一个 Tab 后刻意为空的条带。 */
function normalizeRightPanelTabs(value: unknown, active: unknown): RightPanelTab[] {
  if (Array.isArray(value) && value.length === 0) return [];
  const source = Array.isArray(value) ? value : DEFAULT_RIGHT_PANEL_TABS;
  const normalized: RightPanelTab[] = [];
  for (const tab of source) {
    const canonical = normalizeRightPanelTab(tab);
    if (!normalized.includes(canonical)) normalized.push(canonical);
  }
  const selected = normalizeRightPanelTab(active);
  if (!normalized.includes(selected)) normalized.push(selected);
  return normalized.slice(0, RIGHT_PANEL_TABS.length);
}

/** 收紧持久值与 pointer 派生宽度，使损坏介质不能隐藏导航或占满 Workbench。 */
export function clampSidebarWidth(width: number): number {
  if (!Number.isFinite(width)) return SIDEBAR_WIDTH_DEFAULT;
  return Math.min(SIDEBAR_WIDTH_MAX, Math.max(SIDEBAR_WIDTH_MIN, Math.round(width)));
}

/** 保持左栏比例可用，同时允许基准布局使用小数默认值。 */
export function clampSidebarRatio(ratio: number): number {
  if (!Number.isFinite(ratio)) return SIDEBAR_RATIO_DEFAULT;
  return Math.min(SIDEBAR_RATIO_MAX, Math.max(SIDEBAR_RATIO_MIN, Math.round(ratio * 1000) / 1000));
}

/** 约束右侧工作台宽度；无效介质回到平衡默认值，不尝试解释已淘汰的旧比例字段。 */
export function clampWorkbenchSize(size: number): number {
  if (!Number.isFinite(size)) return WORKBENCH_SIZE_DEFAULT;
  return Math.min(WORKBENCH_SIZE_MAX, Math.max(WORKBENCH_SIZE_MIN, Math.round(size * 1000) / 1000));
}

/**
 * 校验当前持久 schema；inspectorOpen 是进程期状态，任何旧 envelope 中的值都不参与恢复，
 * 这样升级前曾打开右栏的用户也始终从专注的单栏工作区启动。
 */
function normalizePersistedPreferences(
  persisted: unknown,
  fallback: UiPreferences = initialPreferences,
): UiPreferences {
  const stored =
    persisted !== null && typeof persisted === "object" && !Array.isArray(persisted)
      ? (persisted as Partial<UiPreferences>)
      : {};
  const activeTab = normalizeRightPanelTab(stored.rightPanelTab ?? fallback.rightPanelTab);
  const rightPanelTabs = normalizeRightPanelTabs(
    stored.rightPanelTabs ?? fallback.rightPanelTabs,
    activeTab,
  );
  return {
    themeMode: normalizeThemeMode(stored.themeMode ?? fallback.themeMode),
    palette: normalizeUiPalette(stored.palette ?? fallback.palette),
    highContrast:
      typeof stored.highContrast === "boolean" ? stored.highContrast : fallback.highContrast,
    reduceMotion:
      typeof stored.reduceMotion === "boolean" ? stored.reduceMotion : fallback.reduceMotion,
    desktopNotifications:
      typeof stored.desktopNotifications === "boolean"
        ? stored.desktopNotifications
        : fallback.desktopNotifications,
    sidebarCollapsed:
      typeof stored.sidebarCollapsed === "boolean"
        ? stored.sidebarCollapsed
        : fallback.sidebarCollapsed,
    projectSectionCollapsed:
      typeof stored.projectSectionCollapsed === "boolean"
        ? stored.projectSectionCollapsed
        : fallback.projectSectionCollapsed,
    historySectionCollapsed:
      typeof stored.historySectionCollapsed === "boolean"
        ? stored.historySectionCollapsed
        : fallback.historySectionCollapsed,
    sidebarWidth: clampSidebarWidth(stored.sidebarWidth ?? fallback.sidebarWidth),
    sidebarRatio: clampSidebarRatio(stored.sidebarRatio ?? fallback.sidebarRatio),
    workbenchSize: clampWorkbenchSize(stored.workbenchSize ?? fallback.workbenchSize),
    inspectorOpen: false,
    rightPanelTab: activeTab,
    rightPanelTabs,
  };
}

/**
 * 投影唯一允许进入 localStorage 的字段；inspectorOpen、actions、runtime 与未知键
 * 即使通过类型逃逸混入 store，也不会被序列化边界保留。
 */
function projectUiPreferencesForStorage(
  state: UiPreferences,
): Omit<UiPreferences, "inspectorOpen"> {
  return {
    themeMode: state.themeMode,
    palette: normalizeUiPalette(state.palette),
    highContrast: state.highContrast,
    reduceMotion: state.reduceMotion,
    desktopNotifications: state.desktopNotifications,
    sidebarCollapsed: state.sidebarCollapsed,
    projectSectionCollapsed: state.projectSectionCollapsed,
    historySectionCollapsed: state.historySectionCollapsed,
    sidebarWidth: state.sidebarWidth,
    sidebarRatio: state.sidebarRatio,
    workbenchSize: state.workbenchSize,
    rightPanelTab: state.rightPanelTab,
    rightPanelTabs: state.rightPanelTabs,
  };
}

/**
 * 只持久化可逆的显示偏好；凭据、prompt、thread 与 sidecar 状态没有进入本 store 的路径。
 */
export const useUiPreferencesStore = create<UiPreferencesStore>()(
  persist(
    (set) => ({
      ...initialPreferences,
      setThemeMode: (themeMode) => set({ themeMode: normalizeThemeMode(themeMode) }),
      setPalette: (palette) => set({ palette: normalizeUiPalette(palette) }),
      setHighContrast: (highContrast) => set({ highContrast }),
      setReduceMotion: (reduceMotion) => set({ reduceMotion }),
      setDesktopNotifications: (desktopNotifications) => set({ desktopNotifications }),
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
      setProjectSectionCollapsed: (projectSectionCollapsed) => set({ projectSectionCollapsed }),
      setHistorySectionCollapsed: (historySectionCollapsed) => set({ historySectionCollapsed }),
      setSidebarWidth: (sidebarWidth) => set({ sidebarWidth: clampSidebarWidth(sidebarWidth) }),
      setSidebarRatio: (sidebarRatio) => set({ sidebarRatio: clampSidebarRatio(sidebarRatio) }),
      setWorkbenchSize: (workbenchSize) =>
        set({ workbenchSize: clampWorkbenchSize(workbenchSize) }),
      /** 重新打开已完全关闭的 inspector 时从中性 launcher 开始，不复活旧能力。 */
      setInspectorOpen: (inspectorOpen) =>
        set((state) =>
          inspectorOpen && state.rightPanelTabs.length === 0
            ? { inspectorOpen: true, rightPanelTab: "new", rightPanelTabs: ["new"] }
            : { inspectorOpen },
        ),
      /** 选择 feature 时若其先前已关闭则同时恢复，保证选择意图闭环。 */
      setRightPanelTab: (rightPanelTab) =>
        set((state) => {
          const nextTab = normalizeRightPanelTab(rightPanelTab);
          return {
            rightPanelTab: nextTab,
            rightPanelTabs: state.rightPanelTabs.includes(nextTab)
              ? state.rightPanelTabs
              : [...state.rightPanelTabs, nextTab],
          };
        }),
      /** 持久化 Tab 顺序，并在关闭最后一个 Tab 后原子折叠 inspector。 */
      setRightPanelTabs: (rightPanelTabs) =>
        set((state) => {
          const normalized = normalizeRightPanelTabs(rightPanelTabs, state.rightPanelTab);
          return normalized.length === 0
            ? { rightPanelTabs: normalized, inspectorOpen: false }
            : { rightPanelTabs: normalized };
        }),
    }),
    {
      name: "ja-ui-preferences-v10",
      version: 12,
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => projectUiPreferencesForStorage(state),
      /**
       * 存储键保持稳定以保留尺寸与 Tab 偏好；旧 v10 envelope 只做当前字段投影，
       * 其中 inspectorOpen 会被明确丢弃，不建立双轨读取。
       */
      migrate: (persisted) => normalizePersistedPreferences(persisted),
      /** 恢复时明确关闭 inspector，旧 v10/v11 的 true 不能复活启动期抽屉。 */
      merge: (persisted, current) => {
        const stored = normalizePersistedPreferences(persisted, current);
        return {
          ...current,
          themeMode: stored.themeMode ?? current.themeMode,
          palette: stored.palette ?? current.palette,
          highContrast: stored.highContrast ?? current.highContrast,
          reduceMotion: stored.reduceMotion ?? current.reduceMotion,
          desktopNotifications: stored.desktopNotifications ?? current.desktopNotifications,
          sidebarCollapsed: stored.sidebarCollapsed ?? current.sidebarCollapsed,
          projectSectionCollapsed:
            stored.projectSectionCollapsed ?? current.projectSectionCollapsed,
          historySectionCollapsed:
            stored.historySectionCollapsed ?? current.historySectionCollapsed,
          sidebarWidth: stored.sidebarWidth ?? current.sidebarWidth,
          sidebarRatio: stored.sidebarRatio ?? current.sidebarRatio,
          workbenchSize: stored.workbenchSize ?? current.workbenchSize,
          inspectorOpen: false,
          rightPanelTab: stored.rightPanelTab ?? current.rightPanelTab,
          rightPanelTabs: stored.rightPanelTabs ?? current.rightPanelTabs,
        };
      },
    },
  ),
);
