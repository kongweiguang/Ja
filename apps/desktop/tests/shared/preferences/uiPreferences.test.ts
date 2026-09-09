// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  SIDEBAR_RATIO_DEFAULT,
  SIDEBAR_WIDTH_DEFAULT,
  SIDEBAR_WIDTH_MAX,
  SIDEBAR_WIDTH_MIN,
  WORKBENCH_SIZE_DEFAULT,
  WORKBENCH_SIZE_MAX,
  WORKBENCH_SIZE_MIN,
  clampSidebarRatio,
  clampSidebarWidth,
  clampWorkbenchSize,
  getRightPanelSessionState,
  normalizeRightPanelTab,
  normalizeUiPalette,
  useRightPanelSessionStore,
  useUiPreferencesStore,
} from "@/shared/preferences/uiPreferences";

/** 重置当前 UI-only schema；Terminal 已迁入自己的领域持久化 adapter。 */
function resetPreferences(): void {
  localStorage.clear();
  useUiPreferencesStore.setState({
    themeMode: "system",
    palette: "xcode",
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
  });
  useRightPanelSessionStore.setState({ scopes: new Map() });
}

describe("ui navigation preferences", () => {
  beforeEach(resetPreferences);

  it("keeps both desktop split ratios within usable numeric bounds", () => {
    expect([SIDEBAR_RATIO_DEFAULT, WORKBENCH_SIZE_DEFAULT]).toEqual([20.273, 33.725]);
    expect(clampSidebarWidth(Number.NaN)).toBe(SIDEBAR_WIDTH_DEFAULT);
    expect(clampSidebarWidth(10)).toBe(SIDEBAR_WIDTH_MIN);
    expect(clampSidebarWidth(1_200)).toBe(SIDEBAR_WIDTH_MAX);
    expect(clampSidebarRatio(-1)).toBeGreaterThan(0);
    expect(clampWorkbenchSize(0)).toBe(WORKBENCH_SIZE_MIN);
    expect(clampWorkbenchSize(100)).toBe(WORKBENCH_SIZE_MAX);
  });

  it("persists only durable global UI preferences and never stores conversation state", () => {
    useUiPreferencesStore.getState().setSidebarCollapsed(true);
    useUiPreferencesStore.getState().setProjectSectionCollapsed(true);
    useUiPreferencesStore.getState().setHistorySectionCollapsed(true);
    useUiPreferencesStore.getState().setWorkbenchSize(44.5);
    useUiPreferencesStore.getState().setDesktopNotifications(true);
    useUiPreferencesStore.getState().setPalette("claude");
    useUiPreferencesStore.getState().setReducedTransparency(true);
    const raw = localStorage.getItem("ja-ui-preferences-v1") ?? "";
    expect(raw).toContain('"sidebarCollapsed":true');
    expect(raw).toContain('"desktopNotifications":true');
    expect(raw).toContain('"projectSectionCollapsed":true');
    expect(raw).toContain('"historySectionCollapsed":true');
    expect(raw).toContain('"workbenchSize":44.5');
    expect(raw).toContain('"palette":"claude"');
    expect(raw).toContain('"reducedTransparency":true');
    expect(raw).toContain('"version":1');
    expect(raw).not.toContain("inspectorOpen");
    expect(raw).not.toContain("rightPanelTab");
    expect(raw).not.toContain("rightPanelTabs");
    expect(raw).not.toContain("terminalLayouts");
    expect(raw).not.toContain("sessionId");
  });

  /** 首版只读取当前 key 与 envelope 版本，开发期 key 和不支持版本都不能进入 store。 */
  it("ignores retired keys and unsupported envelope versions", async () => {
    localStorage.removeItem("ja-ui-preferences-v1");
    localStorage.setItem(
      "ja-ui-preferences-v10",
      JSON.stringify({ version: 14, state: { palette: "obsidian", rightPanelTab: "terminal" } }),
    );
    await useUiPreferencesStore.persist.rehydrate();
    expect(useUiPreferencesStore.getState()).toMatchObject({ palette: "xcode" });
    expect(useUiPreferencesStore.getState()).not.toHaveProperty("rightPanelTab");

    localStorage.setItem(
      "ja-ui-preferences-v1",
      JSON.stringify({ version: 2, state: { palette: "obsidian", rightPanelTab: "terminal" } }),
    );
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    await useUiPreferencesStore.persist.rehydrate();
    expect(useUiPreferencesStore.getState()).toMatchObject({ palette: "xcode" });
    expect(useUiPreferencesStore.getState()).not.toHaveProperty("rightPanelTab");
    expect(error).toHaveBeenCalled();
    error.mockRestore();
  });

  it("keeps selected workbench tabs reachable inside their owning conversation", () => {
    const scope = "server-1:1:workspace-1:thread-1";
    useRightPanelSessionStore.getState().setRightPanelTabs(scope, []);
    expect(
      getRightPanelSessionState(useRightPanelSessionStore.getState().scopes, scope),
    ).toMatchObject({
      inspectorOpen: false,
      rightPanelTabs: [],
    });
    useRightPanelSessionStore.getState().setRightPanelTab(scope, "terminal");
    expect(
      getRightPanelSessionState(useRightPanelSessionStore.getState().scopes, scope),
    ).toMatchObject({
      rightPanelTab: "terminal",
      rightPanelTabs: ["terminal"],
    });
  });

  it("keeps all right panel capabilities process-local and outside global persistence", () => {
    const scope = "server-1:1:workspace-1:thread-1";
    useRightPanelSessionStore.getState().setRightPanelTab(scope, "agents");
    useRightPanelSessionStore.getState().setRightPanelTab(scope, "subagent:thr_child");
    useRightPanelSessionStore.getState().setRightPanelTab(scope, "side-task:draft_12345678");
    const state = getRightPanelSessionState(useRightPanelSessionStore.getState().scopes, scope);
    expect(state.rightPanelTabs).toEqual([
      "new",
      "agents",
      "subagent:thr_child",
      "side-task:draft_12345678",
    ]);
    const raw = localStorage.getItem("ja-ui-preferences-v1") ?? "";
    expect(raw).not.toContain('"agents"');
    expect(raw).not.toContain("thr_child");
    expect(raw).not.toContain("draft_12345678");
  });

  it("rejects values outside the current enum closures", () => {
    expect(normalizeUiPalette("legacy" as never)).toBe("xcode");
    expect(["xcode", "fleet", "obsidian", "claude"].map(normalizeUiPalette)).toEqual([
      "xcode",
      "fleet",
      "obsidian",
      "claude",
    ]);
    expect(normalizeRightPanelTab("git" as never)).toBe("files");
  });

  it("restores palette and transparency while normalizing a damaged palette", async () => {
    localStorage.setItem(
      "ja-ui-preferences-v1",
      JSON.stringify({
        version: 1,
        state: { palette: "obsidian", reducedTransparency: true },
      }),
    );
    await useUiPreferencesStore.persist.rehydrate();
    expect(useUiPreferencesStore.getState()).toMatchObject({
      palette: "obsidian",
      reducedTransparency: true,
    });

    localStorage.setItem(
      "ja-ui-preferences-v1",
      JSON.stringify({ version: 1, state: { palette: "damaged" } }),
    );
    await useUiPreferencesStore.persist.rehydrate();
    expect(useUiPreferencesStore.getState().palette).toBe("xcode");
  });

  it("keeps the applied session value when localStorage persistence fails", () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("quota", "QuotaExceededError");
    });

    expect(() => useUiPreferencesStore.getState().setPalette("fleet")).toThrow();
    expect(useUiPreferencesStore.getState().palette).toBe("fleet");
    setItem.mockRestore();
  });
});
