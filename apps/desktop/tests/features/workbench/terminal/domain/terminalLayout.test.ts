// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  MAX_PANES_PER_TERMINAL_TAB,
  MAX_TERMINAL_LAYOUT_DEPTH,
  MAX_TERMINAL_LAYOUT_INSPECTION_STEPS,
  MAX_TERMINAL_LAYOUT_TABS,
  MAX_TERMINAL_RELATIVE_CWD_LENGTH,
  MAX_TERMINAL_PANES,
  addTerminalTab,
  canAddTerminalTab,
  canSplitTerminalPane,
  countPanes,
  countTerminalPanes,
  createDefaultTerminalLayout,
  flattenPanes,
  isValidTerminalRelativeCwd,
  normalizeRelativeCwd,
  parseTerminalLayout,
  removeTerminalPane,
  removeTerminalTab,
  repairTerminalLayoutProfiles,
  sanitizeTerminalLayout,
  setActiveTerminalPane,
  setTerminalSplitRatio,
  splitTerminalPane,
  type TerminalLayoutNode,
  type TerminalLayoutV1,
  type TerminalProfile,
} from "@/features/workbench/terminal/domain/terminalLayout";

/** 汇总持久化 UI 标识，用来证明不同节点类型也不能共享同一标识。 */
function collectLayoutIdentityIds(layout: TerminalLayoutV1): string[] {
  const ids: string[] = [];
  for (const tab of layout.tabs) {
    ids.push(tab.tabId);
    collectNodeIdentityIds(tab.root, ids);
  }
  return ids;
}

/** 按视觉顺序遍历分屏树，测试过程不读取任何原生运行态。 */
function collectNodeIdentityIds(node: TerminalLayoutNode, ids: string[]): void {
  if (node.kind === "pane") {
    ids.push(node.paneId);
    return;
  }
  ids.push(node.splitId);
  collectNodeIdentityIds(node.first, ids);
  collectNodeIdentityIds(node.second, ids);
}

describe("terminal layout model", () => {
  it("rejects malformed or runtime-bearing storage and initializes the exact default", () => {
    const raw = {
      version: 1,
      workspaceId: "ws_fixture",
      tabs: Array.from({ length: 5 }, (_, index) => ({
        tabId: `tab-${index}`,
        profile: "not-a-profile",
        root: {
          kind: "split",
          splitId: `split-${index}`,
          orientation: "diagonal",
          ratio: 3,
          first: {
            kind: "pane",
            paneId: `pane-${index}-a`,
            sessionId: "native-secret",
            session_id: "native-secret-snake",
            generation: 9,
          },
          second: {
            kind: "pane",
            paneId: `pane-${index}-b`,
            scrollback: "forbidden",
            outputs: ["forbidden-output"],
          },
        },
        activePaneId: "missing",
        environment: { SECRET: "not persisted" },
        command: "Remove-Item -Recurse C:\\private",
      })),
      activeTabId: "missing",
      runtime: { sessionId: "top-level-secret" },
    };
    const initialized = sanitizeTerminalLayout(raw, "ws_fixture");
    const serialized = JSON.stringify(initialized);
    expect(initialized).toEqual(createDefaultTerminalLayout("ws_fixture"));
    for (const forbidden of [
      "sessionId",
      "session_id",
      "generation",
      "environment",
      "command",
      "scrollback",
      "outputs",
      "runtime",
    ]) {
      expect(serialized).not.toContain(`"${forbidden}"`);
    }
    expect(parseTerminalLayout(raw, "ws_fixture")).toBeUndefined();
    expect(parseTerminalLayout(initialized, "ws_fixture")).toEqual(initialized);
  });

  /**
   * 复现旧版 64KiB 内 20,000 个空 Tab 的同步冻结输入；Tab 长度必须在读取
   * 任一元素前 O(1) 回退，墙钟预算用于捕获数量上限被未来改动移到遍历之后。
   */
  it("rejects twenty thousand empty tabs within a bounded synchronous budget", () => {
    const tabsJson = `[${"{},".repeat(19_999)}{}]`;
    const payload = `{"version":1,"workspaceId":"ws_fixture","tabs":${tabsJson},"activeTabId":null}`;
    expect(new TextEncoder().encode(payload).byteLength).toBeLessThanOrEqual(64 * 1024);

    const startedAt = performance.now();
    const parsed = JSON.parse(payload) as { tabs: unknown[] };
    const repaired = sanitizeTerminalLayout(parsed, "ws_fixture");
    const elapsedMillis = performance.now() - startedAt;

    let numericTabReads = 0;
    const observedTabs = new Proxy(parsed.tabs, {
      get(target, property, receiver) {
        if (typeof property === "string" && /^\d+$/u.test(property)) numericTabReads += 1;
        return Reflect.get(target, property, receiver);
      },
    });
    sanitizeTerminalLayout({ ...parsed, tabs: observedTabs }, "ws_fixture");

    expect(parsed.tabs).toHaveLength(20_000);
    expect(repaired.tabs).toHaveLength(1);
    expect(numericTabReads).toBe(0);
    expect(MAX_TERMINAL_LAYOUT_INSPECTION_STEPS).toBeLessThan(100);
    expect(elapsedMillis).toBeLessThan(100);
  });

  it("rejects oversized tab arrays, cyclic nodes, and over-depth trees before recursive inspection", () => {
    const tooManyTabs = {
      version: 1,
      workspaceId: "ws_fixture",
      tabs: Array.from({ length: MAX_TERMINAL_LAYOUT_TABS + 1 }, (_, index) => ({
        tabId: `tab-${index}`,
        profile: "default",
        root: { kind: "pane", paneId: `pane-${index}` },
        activePaneId: `pane-${index}`,
      })),
      activeTabId: "tab-0",
    };
    const cyclicRoot: Record<string, unknown> = {
      kind: "split",
      orientation: "horizontal",
      ratio: 0.5,
    };
    cyclicRoot["first"] = cyclicRoot;
    cyclicRoot["second"] = { kind: "pane", paneId: "pane-safe" };
    let deepRoot: Record<string, unknown> = { kind: "pane", paneId: "pane-tail" };
    for (let depth = 0; depth <= MAX_TERMINAL_LAYOUT_DEPTH; depth += 1) {
      deepRoot = {
        kind: "split",
        orientation: "horizontal",
        ratio: 0.5,
        first: deepRoot,
        second: { kind: "pane", paneId: `pane-${depth}` },
      };
    }

    for (const raw of [
      tooManyTabs,
      { version: 1, workspaceId: "ws_fixture", tabs: [{ root: cyclicRoot }], activeTabId: null },
      { version: 1, workspaceId: "ws_fixture", tabs: [{ root: deepRoot }], activeTabId: null },
    ]) {
      const repaired = sanitizeTerminalLayout(raw, "ws_fixture");
      expect(repaired.tabs).toHaveLength(1);
      expect(countTerminalPanes(repaired)).toBe(1);
    }
  });

  it("splits, focuses, resizes and removes panes by immutable operations", () => {
    let layout = createDefaultTerminalLayout("ws_fixture");
    const tabId = layout.activeTabId as string;
    const firstPane = layout.tabs[0]?.activePaneId as string;
    layout = splitTerminalPane(layout, tabId, firstPane, "vertical");
    const panes = flattenPanes(layout.tabs[0]!.root);
    expect(panes).toHaveLength(2);
    const secondPane = panes[1]?.paneId as string;
    layout = setActiveTerminalPane(layout, tabId, secondPane);
    expect(layout.tabs[0]?.activePaneId).toBe(secondPane);
    const splitId = layout.tabs[0]?.root.kind === "split" ? layout.tabs[0].root.splitId : "";
    layout = setTerminalSplitRatio(layout, tabId, splitId, 0.99);
    expect(layout.tabs[0]?.root.kind === "split" ? layout.tabs[0].root.ratio : 0).toBe(0.8);
    layout = removeTerminalPane(layout, tabId, firstPane);
    expect(layout.tabs[0] === undefined ? 0 : countPanes(layout.tabs[0].root)).toBe(1);
  });

  /** 精确恢复的标识必须参与后续分配，因为模块重新加载后不存在可依赖的全局计数器。 */
  it("allocates globally unique tab, pane and split ids after restored add and split", async () => {
    vi.resetModules();
    const restoredModel = await import("@/features/workbench/terminal/domain/terminalLayout");
    const restored = restoredModel.sanitizeTerminalLayout(
      {
        version: 1,
        workspaceId: "ws_fixture",
        tabs: [
          {
            tabId: "tab-1",
            title: "已恢复终端",
            profile: "default",
            root: {
              kind: "split",
              splitId: "split-1",
              orientation: "horizontal",
              ratio: 0.5,
              first: { kind: "pane", paneId: "pane-1" },
              second: { kind: "pane", paneId: "pane-2" },
            },
            activePaneId: "pane-1",
          },
        ],
        activeTabId: "tab-1",
      },
      "ws_fixture",
    );
    const withTab = restoredModel.addTerminalTab(restored);
    const addedTab = withTab.tabs.at(-1)!;
    const afterSplit = restoredModel.splitTerminalPane(
      withTab,
      addedTab.tabId,
      addedTab.activePaneId,
      "vertical",
    );
    const ids = collectLayoutIdentityIds(afterSplit);

    expect(new Set(ids).size).toBe(ids.length);
    expect(afterSplit.tabs).toHaveLength(2);
    expect(restoredModel.countPanes(afterSplit.tabs.at(-1)!.root)).toBe(2);
  });

  /** 重复或跨类型标识不属于精确 v1 schema，必须整体回到默认值而不是静默迁移。 */
  it("defaults duplicate and cross-kind identities instead of repairing legacy state", () => {
    const corrupted = {
      version: 1,
      workspaceId: "ws_fixture",
      tabs: [
        {
          tabId: "shared-id",
          title: "损坏布局",
          profile: "default",
          root: {
            kind: "split",
            splitId: "split-duplicate",
            orientation: "horizontal",
            ratio: 0.4,
            first: {
              kind: "split",
              splitId: "split-duplicate",
              orientation: "vertical",
              ratio: 0.35,
              first: { kind: "pane", paneId: "shared-id" },
              second: { kind: "pane", paneId: "pane-b" },
            },
            second: { kind: "pane", paneId: "pane-c" },
          },
          activePaneId: "shared-id",
        },
      ],
      activeTabId: "shared-id",
    };
    expect(sanitizeTerminalLayout(corrupted, "ws_fixture")).toEqual(
      createDefaultTerminalLayout("ws_fixture"),
    );
  });

  it("removes the last tab cleanly so the outer panel can collapse", () => {
    const layout = createDefaultTerminalLayout("ws_fixture");
    const tabId = layout.activeTabId as string;
    const empty = removeTerminalTab(layout, tabId);
    expect(empty.tabs).toEqual([]);
    expect(empty.activeTabId).toBeNull();
    const reopened = addTerminalTab(empty);
    expect(reopened.tabs).toHaveLength(1);
    expect(reopened.activeTabId).toBe(reopened.tabs[0]?.tabId);
  });

  /** 创建操作只持久化 typed dormant 意图，不再接受历史字符串重载。 */
  it("persists typed profile and relative cwd options without native identity", () => {
    const initial = createDefaultTerminalLayout("ws_fixture");
    const created = addTerminalTab(initial, {
      profile: "power_shell",
      relativeCwd: "  packages/desktop  ",
    });
    expect(created.tabs.at(-1)).toMatchObject({
      title: "终端 2",
      profile: "power_shell",
      relativeCwd: "packages/desktop",
    });
    expect(JSON.stringify(created.tabs.at(-1))).not.toContain("sessionId");

    const titled = addTerminalTab(created, { title: "自定义终端" });
    expect(titled.tabs.at(-1)).toMatchObject({ title: "自定义终端", profile: "default" });
    expect(titled.tabs.at(-1)).not.toHaveProperty("relativeCwd");
  });

  /** Windows 探测闭集会把陈旧 Unix profile 修复为 default，缺少 default 时按固定闭集顺序回退。 */
  it("deterministically repairs persisted profiles against the supported closed set", () => {
    const initial = createDefaultTerminalLayout("ws_fixture");
    const persisted = {
      ...initial,
      tabs: initial.tabs.map((tab) => ({ ...tab, profile: "bash" })),
    };
    const windowsProfiles: readonly TerminalProfile[] = ["default", "power_shell", "cmd"];
    const repaired = sanitizeTerminalLayout(persisted, "ws_fixture", windowsProfiles);

    expect(repaired.tabs[0]?.profile).toBe("default");
    expect(repairTerminalLayoutProfiles(repaired, windowsProfiles)).toBe(repaired);
    expect(repairTerminalLayoutProfiles(repaired, ["cmd"]).tabs[0]?.profile).toBe("cmd");
    expect(addTerminalTab(repaired, { profile: "bash" }, windowsProfiles)).toBe(repaired);
    expect(addTerminalTab(repaired, undefined, ["cmd"]).tabs.at(-1)?.profile).toBe("cmd");
  });

  /** 渲染侧校验拒绝所有可能逃逸或重新解释规范工作区的路径形式。 */
  it("rejects absolute, drive-relative, traversal, control and overlong cwd values", () => {
    const invalid = [
      "C:\\workspace",
      "c:workspace",
      "\\\\server\\share",
      "/usr/src",
      "../secret",
      "src/../secret",
      "src\u0000secret",
      "x".repeat(MAX_TERMINAL_RELATIVE_CWD_LENGTH + 1),
    ];
    for (const value of invalid) {
      expect(isValidTerminalRelativeCwd(value), value).toBe(false);
      expect(normalizeRelativeCwd(value), value).toBeUndefined();
    }
    expect(isValidTerminalRelativeCwd("")).toBe(true);
    expect(normalizeRelativeCwd("packages\\desktop")).toBe("packages\\desktop");

    const initial = createDefaultTerminalLayout("ws_fixture");
    expect(addTerminalTab(initial, { relativeCwd: "src/../secret" })).toBe(initial);
    expect(addTerminalTab(initial, { profile: "arbitrary.exe" as TerminalProfile })).toBe(initial);
  });

  /** 可见的新建入口必须在超过八个原生会话预算前变为不可用。 */
  it("caps add-tab at eight total panes", () => {
    let layout = createDefaultTerminalLayout("ws_fixture");
    while (canAddTerminalTab(layout)) layout = addTerminalTab(layout);

    expect(countTerminalPanes(layout)).toBe(MAX_TERMINAL_PANES);
    expect(layout.tabs).toHaveLength(MAX_TERMINAL_PANES);
    expect(addTerminalTab(layout)).toBe(layout);
  });

  /** 分屏准入同时遵守单标签和全局预算，越界前就把不可用状态投影给 UI。 */
  it("caps splits at four panes per tab and eight panes per workspace", () => {
    let layout = createDefaultTerminalLayout("ws_fixture");
    const firstTabId = layout.activeTabId as string;
    while (countPanes(layout.tabs[0]!.root) < MAX_PANES_PER_TERMINAL_TAB) {
      const activePaneId = layout.tabs[0]!.activePaneId;
      expect(canSplitTerminalPane(layout, firstTabId, activePaneId)).toBe(true);
      layout = splitTerminalPane(layout, firstTabId, activePaneId, "horizontal");
    }
    expect(canSplitTerminalPane(layout, firstTabId, layout.tabs[0]!.activePaneId)).toBe(false);

    while (canAddTerminalTab(layout)) layout = addTerminalTab(layout);
    expect(countTerminalPanes(layout)).toBe(MAX_TERMINAL_PANES);
    const lastTab = layout.tabs.at(-1)!;
    expect(countPanes(lastTab.root)).toBe(1);
    expect(canSplitTerminalPane(layout, lastTab.tabId, lastTab.activePaneId)).toBe(false);
  });
});
