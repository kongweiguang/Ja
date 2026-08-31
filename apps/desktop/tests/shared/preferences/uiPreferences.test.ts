// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { beforeEach, describe, expect, it } from "vitest";
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
  normalizeRightPanelTab,
  normalizeUiPalette,
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
    desktopNotifications: false,
    sidebarCollapsed: false,
    projectSectionCollapsed: false,
    historySectionCollapsed: false,
    sidebarWidth: SIDEBAR_WIDTH_DEFAULT,
    sidebarRatio: SIDEBAR_RATIO_DEFAULT,
    workbenchSize: WORKBENCH_SIZE_DEFAULT,
    inspectorOpen: false,
    rightPanelTab: "files",
    rightPanelTabs: ["review", "files", "preview"],
  });
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

  it("persists only durable UI preferences and never stores transient inspector state", () => {
    useUiPreferencesStore.getState().setInspectorOpen(true);
    useUiPreferencesStore.getState().setSidebarCollapsed(true);
    useUiPreferencesStore.getState().setProjectSectionCollapsed(true);
    useUiPreferencesStore.getState().setHistorySectionCollapsed(true);
    useUiPreferencesStore.getState().setWorkbenchSize(44.5);
    useUiPreferencesStore.getState().setDesktopNotifications(true);
    const raw = localStorage.getItem("ja-ui-preferences-v10") ?? "";
    expect(raw).toContain('"sidebarCollapsed":true');
    expect(raw).toContain('"desktopNotifications":true');
    expect(raw).toContain('"projectSectionCollapsed":true');
    expect(raw).toContain('"historySectionCollapsed":true');
    expect(raw).toContain('"workbenchSize":44.5');
    expect(raw).not.toContain("inspectorOpen");
    expect(raw).not.toContain("terminalLayouts");
    expect(raw).not.toContain("sessionId");
  });

  /** 旧介质中的打开状态与已淘汰尺寸字段不能进入当前单一 schema。 */
  it.each([10, 11])(
    "drops retired inspector layout state from stored v%s state",
    async (version) => {
      useUiPreferencesStore.setState({ inspectorOpen: true });
      localStorage.setItem(
        "ja-ui-preferences-v10",
        JSON.stringify({
          version,
          state: {
            inspectorOpen: true,
            inspectorSize: 42,
            conversationRatio: 58,
            rightPanelTab: "terminal",
            rightPanelTabs: ["terminal", "files"],
          },
        }),
      );
      await useUiPreferencesStore.persist.rehydrate();

      const state = useUiPreferencesStore.getState();
      expect(state).toMatchObject({
        inspectorOpen: false,
        workbenchSize: WORKBENCH_SIZE_DEFAULT,
        rightPanelTab: "terminal",
        rightPanelTabs: ["terminal", "files"],
      });
      expect(state).not.toHaveProperty("inspectorSize");
      expect(state).not.toHaveProperty("inspectorRatio");
      expect(state).not.toHaveProperty("conversationRatio");
    },
  );

  it("keeps selected workbench tabs reachable without reviving closed capabilities", () => {
    useUiPreferencesStore.getState().setRightPanelTabs([]);
    expect(useUiPreferencesStore.getState()).toMatchObject({
      inspectorOpen: false,
      rightPanelTabs: [],
    });
    useUiPreferencesStore.getState().setRightPanelTab("terminal");
    expect(useUiPreferencesStore.getState()).toMatchObject({
      rightPanelTab: "terminal",
      rightPanelTabs: ["terminal"],
    });
  });

  it("rejects values outside the current enum closures", () => {
    expect(normalizeUiPalette("legacy" as never)).toBe("xcode");
    expect(normalizeRightPanelTab("git" as never)).toBe("files");
  });
});
