// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { create } from "zustand";
import { createJSONStorage, persist } from "zustand/middleware";
import {
  CODE_FONT_SIZE_OPTIONS,
  DEFAULT_CODE_FONT_SIZE,
  DEFAULT_UI_FONT_SIZE,
  UI_FONT_SIZE_OPTIONS,
  type SendShortcut,
} from "@/shared/settings/interfacePreferences";
import { normalizeUiPalette, type ThemeMode, type UiPalette } from "@/shared/styles/theme";
export {
  DEFAULT_CODE_FONT_SIZE,
  DEFAULT_UI_FONT_SIZE,
} from "@/shared/settings/interfacePreferences";
export type { SendShortcut } from "@/shared/settings/interfacePreferences";
export { normalizeUiPalette } from "@/shared/styles/theme";
export type { ThemeMode, UiPalette } from "@/shared/styles/theme";

/** 偏好只保存稳定 key；Workbench 实际渲染使用带 kind/label 的受控描述符。 */
export type RightPanelTab =
  | "review"
  | "files"
  | "terminal"
  | "preview"
  | "plan"
  | "agents"
  | "new"
  | `side-task:${string}`
  | `subagent:${string}`;

const RIGHT_PANEL_TABS: readonly RightPanelTab[] = [
  "review",
  "files",
  "terminal",
  "preview",
  "plan",
  "agents",
  "new",
];
const DEFAULT_RIGHT_PANEL_STATE: RightPanelSessionState = {
  inspectorOpen: false,
  rightPanelTab: "new",
  rightPanelTabs: ["new"],
};

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
  reducedTransparency: boolean;
  desktopNotifications: boolean;
  sidebarCollapsed: boolean;
  projectSectionCollapsed: boolean;
  historySectionCollapsed: boolean;
  sidebarWidth: number;
  sidebarRatio: number;
  workbenchSize: number;
  sendShortcut: SendShortcut;
  uiFontSize: number;
  codeFontSize: number;
}

export interface UiPreferencesStore extends UiPreferences {
  setThemeMode: (themeMode: ThemeMode) => void;
  setPalette: (palette: UiPalette) => void;
  setHighContrast: (highContrast: boolean) => void;
  setReduceMotion: (reduceMotion: boolean) => void;
  setReducedTransparency: (reducedTransparency: boolean) => void;
  setDesktopNotifications: (desktopNotifications: boolean) => void;
  setSidebarCollapsed: (sidebarCollapsed: boolean) => void;
  setProjectSectionCollapsed: (projectSectionCollapsed: boolean) => void;
  setHistorySectionCollapsed: (historySectionCollapsed: boolean) => void;
  setSidebarWidth: (sidebarWidth: number) => void;
  setSidebarRatio: (sidebarRatio: number) => void;
  setWorkbenchSize: (workbenchSize: number) => void;
  setSendShortcut: (sendShortcut: SendShortcut) => void;
  setUiFontSize: (uiFontSize: number) => void;
  setCodeFontSize: (codeFontSize: number) => void;
}

export interface RightPanelSessionState {
  readonly inspectorOpen: boolean;
  readonly rightPanelTab: RightPanelTab;
  readonly rightPanelTabs: readonly RightPanelTab[];
}

export interface RightPanelSessionStore {
  readonly scopes: ReadonlyMap<string, RightPanelSessionState>;
  readonly setInspectorOpen: (scopeIdentity: string, inspectorOpen: boolean) => void;
  readonly setRightPanelTab: (scopeIdentity: string, rightPanelTab: RightPanelTab) => void;
  readonly setRightPanelTabs: (
    scopeIdentity: string,
    rightPanelTabs: readonly RightPanelTab[],
  ) => void;
  readonly toggleInspector: (scopeIdentity: string) => void;
}

const initialPreferences: UiPreferences = {
  themeMode: "system",
  palette: "ja",
  highContrast: false,
  reduceMotion: false,
  reducedTransparency: false,
  desktopNotifications: false,
  sidebarCollapsed: false,
  projectSectionCollapsed: false,
  historySectionCollapsed: false,
  sidebarWidth: SIDEBAR_WIDTH_DEFAULT,
  sidebarRatio: SIDEBAR_RATIO_DEFAULT,
  workbenchSize: WORKBENCH_SIZE_DEFAULT,
  sendShortcut: "enter",
  uiFontSize: DEFAULT_UI_FONT_SIZE,
  codeFontSize: DEFAULT_CODE_FONT_SIZE,
};

/**
 * 校验 preference store 持久化的小型 enum；原生 Settings 文档有自己的 Schema，
 * 因此非法 UI-only 值必须在本地修复，不能泄漏到文档级 theme。
 */
function normalizeThemeMode(value: unknown): ThemeMode {
  return value === "light" || value === "dark" || value === "system" ? value : "system";
}

/** 快捷键只接受设置页公开的两档，损坏或历史值回到默认 Enter 发送。 */
export function normalizeSendShortcut(value: unknown): SendShortcut {
  return value === "modifier-enter" ? "modifier-enter" : "enter";
}

/** 字号采用离散档位，避免任意持久值改变布局比例或造成不可读的 UI。 */
export function normalizeUiFontSize(value: unknown, fallback = DEFAULT_UI_FONT_SIZE): number {
  return typeof value === "number" &&
    UI_FONT_SIZE_OPTIONS.includes(value as (typeof UI_FONT_SIZE_OPTIONS)[number])
    ? value
    : fallback;
}

/** 代码与终端字号共享有限档位，确保 CodeMirror 与 xterm 的网格仍可稳定拟合。 */
export function normalizeCodeFontSize(value: unknown, fallback = DEFAULT_CODE_FONT_SIZE): number {
  return typeof value === "number" &&
    CODE_FONT_SIZE_OPTIONS.includes(value as (typeof CODE_FONT_SIZE_OPTIONS)[number])
    ? value
    : fallback;
}

/** 只接受当前可见 Tab；非法介质值回到 Files，绝不推断旧能力别名。 */
export function normalizeRightPanelTab(value: unknown): RightPanelTab {
  if (
    typeof value === "string" &&
    /^(?:side-task|subagent):thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}$/u.test(value)
  )
    return value as RightPanelTab;
  if (typeof value === "string" && /^side-task:draft_[A-Za-z0-9-]{8,64}$/u.test(value))
    return value as RightPanelTab;
  switch (value) {
    case "review":
      return "review";
    case "files":
      return "files";
    case "terminal":
      return "terminal";
    case "preview":
      return "preview";
    case "plan":
      return "plan";
    case "agents":
      return "agents";
    case "new":
      return "new";
    default:
      return "files";
  }
}

/** 规范化会话内 Tab 顺序，同时保留关闭最后一个 Tab 后刻意为空的条带。 */
function normalizeRightPanelTabs(value: unknown, active: unknown): RightPanelTab[] {
  if (Array.isArray(value) && value.length === 0) return [];
  const source = Array.isArray(value) ? value : DEFAULT_RIGHT_PANEL_STATE.rightPanelTabs;
  const normalized: RightPanelTab[] = [];
  for (const tab of source) {
    const canonical = normalizeRightPanelTab(tab);
    if (!normalized.includes(canonical)) normalized.push(canonical);
  }
  const selected = normalizeRightPanelTab(active);
  if (!normalized.includes(selected)) normalized.push(selected);
  return normalized.slice(0, RIGHT_PANEL_TABS.length + 64);
}

/** 未建立会话 identity 时只返回不可变默认视图，避免空会话共用一个伪 scope。 */
export function getRightPanelSessionState(
  scopes: ReadonlyMap<string, RightPanelSessionState>,
  scopeIdentity: string | undefined,
): RightPanelSessionState {
  if (!scopeIdentity) return DEFAULT_RIGHT_PANEL_STATE;
  return scopes.get(scopeIdentity) ?? DEFAULT_RIGHT_PANEL_STATE;
}

/** 每次替换 Map 与单个 scope 快照，让 Zustand selector 只通知真正受影响的当前会话。 */
function updateRightPanelSessionState(
  scopes: ReadonlyMap<string, RightPanelSessionState>,
  scopeIdentity: string,
  update: (current: RightPanelSessionState) => RightPanelSessionState,
): ReadonlyMap<string, RightPanelSessionState> {
  if (!scopeIdentity) return scopes;
  const next = new Map(scopes);
  next.set(scopeIdentity, update(getRightPanelSessionState(scopes, scopeIdentity)));
  return next;
}

/**
 * 右栏开关、当前能力和打开列表由 composition 提供的会话 identity 唯一归属。
 * 它们只在 renderer 生命周期内保留，不进入全局 UI preference；原生工作面不因 Java 重连销毁。
 */
export const useRightPanelSessionStore = create<RightPanelSessionStore>()((set) => ({
  scopes: new Map(),
  /** 打开已清空的会话右栏时只恢复中性 launcher，不继承其它会话能力。 */
  setInspectorOpen: (scopeIdentity, inspectorOpen) =>
    set((state) => ({
      scopes: updateRightPanelSessionState(state.scopes, scopeIdentity, (current) =>
        inspectorOpen && current.rightPanelTabs.length === 0
          ? { inspectorOpen: true, rightPanelTab: "new", rightPanelTabs: ["new"] }
          : { ...current, inspectorOpen },
      ),
    })),
  /** 选择能力时在同一 scope 原子补回其 Tab，避免 active 指向不可达内容。 */
  setRightPanelTab: (scopeIdentity, rightPanelTab) =>
    set((state) => ({
      scopes: updateRightPanelSessionState(state.scopes, scopeIdentity, (current) => {
        const nextTab = normalizeRightPanelTab(rightPanelTab);
        return {
          ...current,
          rightPanelTab: nextTab,
          rightPanelTabs: current.rightPanelTabs.includes(nextTab)
            ? current.rightPanelTabs
            : [...current.rightPanelTabs, nextTab],
        };
      }),
    })),
  /** 关闭最后一个 Tab 时只折叠所属会话，不能改写其它会话或全局布局。 */
  setRightPanelTabs: (scopeIdentity, rightPanelTabs) =>
    set((state) => ({
      scopes: updateRightPanelSessionState(state.scopes, scopeIdentity, (current) => {
        const normalized = normalizeRightPanelTabs(rightPanelTabs, current.rightPanelTab);
        return normalized.length === 0
          ? { ...current, rightPanelTabs: normalized, inspectorOpen: false }
          : { ...current, rightPanelTabs: normalized };
      }),
    })),
  /** 基于目标 scope 的最新快照切换，迟到回调不会读取当前另一个会话的开关。 */
  toggleInspector: (scopeIdentity) =>
    set((state) => ({
      scopes: updateRightPanelSessionState(state.scopes, scopeIdentity, (current) =>
        !current.inspectorOpen && current.rightPanelTabs.length === 0
          ? { inspectorOpen: true, rightPanelTab: "new", rightPanelTabs: ["new"] }
          : { ...current, inspectorOpen: !current.inspectorOpen },
      ),
    })),
}));

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

/** 校验首版持久 schema；右栏会话状态不属于这个全局偏好 envelope。 */
function normalizePersistedPreferences(
  persisted: unknown,
  fallback: UiPreferences = initialPreferences,
): UiPreferences {
  const stored =
    persisted !== null && typeof persisted === "object" && !Array.isArray(persisted)
      ? (persisted as Partial<UiPreferences>)
      : {};
  return {
    themeMode: normalizeThemeMode(stored.themeMode ?? fallback.themeMode),
    palette: normalizeUiPalette(stored.palette ?? fallback.palette),
    highContrast:
      typeof stored.highContrast === "boolean" ? stored.highContrast : fallback.highContrast,
    reduceMotion:
      typeof stored.reduceMotion === "boolean" ? stored.reduceMotion : fallback.reduceMotion,
    reducedTransparency:
      typeof stored.reducedTransparency === "boolean"
        ? stored.reducedTransparency
        : fallback.reducedTransparency,
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
    sendShortcut: normalizeSendShortcut(stored.sendShortcut ?? fallback.sendShortcut),
    uiFontSize: normalizeUiFontSize(stored.uiFontSize, fallback.uiFontSize),
    codeFontSize: normalizeCodeFontSize(stored.codeFontSize, fallback.codeFontSize),
  };
}

/**
 * 投影唯一允许进入 localStorage 的字段；右栏会话状态、actions、runtime 与未知键
 * 即使通过类型逃逸混入 store，也不会被序列化边界保留。
 */
function projectUiPreferencesForStorage(state: UiPreferences): UiPreferences {
  return {
    themeMode: state.themeMode,
    palette: normalizeUiPalette(state.palette),
    highContrast: state.highContrast,
    reduceMotion: state.reduceMotion,
    reducedTransparency: state.reducedTransparency,
    desktopNotifications: state.desktopNotifications,
    sidebarCollapsed: state.sidebarCollapsed,
    projectSectionCollapsed: state.projectSectionCollapsed,
    historySectionCollapsed: state.historySectionCollapsed,
    sidebarWidth: state.sidebarWidth,
    sidebarRatio: state.sidebarRatio,
    workbenchSize: state.workbenchSize,
    sendShortcut: normalizeSendShortcut(state.sendShortcut),
    uiFontSize: normalizeUiFontSize(state.uiFontSize),
    codeFontSize: normalizeCodeFontSize(state.codeFontSize),
  };
}

/** 只持久化全局显示偏好；凭据、prompt、thread 与 sidecar 状态不进入这个 store。 */
export const useUiPreferencesStore = create<UiPreferencesStore>()(
  persist(
    (set) => ({
      ...initialPreferences,
      setThemeMode: (themeMode) => set({ themeMode: normalizeThemeMode(themeMode) }),
      /** Palette 在唯一 store 边界规范化，避免调用方绕过闭集并污染持久化 envelope。 */
      setPalette: (palette) => set({ palette: normalizeUiPalette(palette) }),
      setHighContrast: (highContrast) => set({ highContrast }),
      setReduceMotion: (reduceMotion) => set({ reduceMotion }),
      /** 透明度偏好只改变材质降级策略，不与高对比度或系统辅助功能合并成同一个状态。 */
      setReducedTransparency: (reducedTransparency) => set({ reducedTransparency }),
      setDesktopNotifications: (desktopNotifications) => set({ desktopNotifications }),
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
      setProjectSectionCollapsed: (projectSectionCollapsed) => set({ projectSectionCollapsed }),
      setHistorySectionCollapsed: (historySectionCollapsed) => set({ historySectionCollapsed }),
      setSidebarWidth: (sidebarWidth) => set({ sidebarWidth: clampSidebarWidth(sidebarWidth) }),
      setSidebarRatio: (sidebarRatio) => set({ sidebarRatio: clampSidebarRatio(sidebarRatio) }),
      setWorkbenchSize: (workbenchSize) =>
        set({ workbenchSize: clampWorkbenchSize(workbenchSize) }),
      setSendShortcut: (sendShortcut) => set({ sendShortcut: normalizeSendShortcut(sendShortcut) }),
      setUiFontSize: (uiFontSize) => set({ uiFontSize: normalizeUiFontSize(uiFontSize) }),
      setCodeFontSize: (codeFontSize) => set({ codeFontSize: normalizeCodeFontSize(codeFontSize) }),
    }),
    {
      name: "ja-ui-preferences-v1",
      version: 1,
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => projectUiPreferencesForStorage(state),
      /** 恢复只接受 Zustand 已确认版本一致的首版 envelope，并继续隔离损坏字段。 */
      merge: (persisted, current) => {
        const stored = normalizePersistedPreferences(persisted, current);
        return {
          ...current,
          themeMode: stored.themeMode ?? current.themeMode,
          palette: stored.palette ?? current.palette,
          highContrast: stored.highContrast ?? current.highContrast,
          reduceMotion: stored.reduceMotion ?? current.reduceMotion,
          reducedTransparency: stored.reducedTransparency ?? current.reducedTransparency,
          desktopNotifications: stored.desktopNotifications ?? current.desktopNotifications,
          sidebarCollapsed: stored.sidebarCollapsed ?? current.sidebarCollapsed,
          projectSectionCollapsed:
            stored.projectSectionCollapsed ?? current.projectSectionCollapsed,
          historySectionCollapsed:
            stored.historySectionCollapsed ?? current.historySectionCollapsed,
          sidebarWidth: stored.sidebarWidth ?? current.sidebarWidth,
          sidebarRatio: stored.sidebarRatio ?? current.sidebarRatio,
          workbenchSize: stored.workbenchSize ?? current.workbenchSize,
          sendShortcut: stored.sendShortcut ?? current.sendShortcut,
          uiFontSize: stored.uiFontSize ?? current.uiFontSize,
          codeFontSize: stored.codeFontSize ?? current.codeFontSize,
        };
      },
    },
  ),
);
