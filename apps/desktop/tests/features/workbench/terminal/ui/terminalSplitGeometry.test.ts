// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  createDefaultTerminalLayout,
  splitTerminalPane,
  type TerminalSplitLayout,
} from "@/features/workbench/terminal/domain";
import {
  terminalNodeMinimumExtent,
  terminalSplitGeometry,
} from "@/features/workbench/terminal/ui/terminalSplitGeometry";

/** 以实际可新增叶子的顺序构造四窗格树，固定深层分屏的 R48 约束场景。 */
function fourHorizontalPanes(): TerminalSplitLayout {
  let layout = createDefaultTerminalLayout("ws_fixture");
  const tabId = layout.activeTabId;
  if (tabId === null) throw new Error("缺少默认终端标签");
  for (let index = 1; index < 4; index += 1) {
    const tab = layout.tabs.find((candidate) => candidate.tabId === tabId);
    if (tab === undefined) throw new Error("终端标签未保留");
    layout = splitTerminalPane(layout, tabId, tab.activePaneId, "horizontal");
  }
  const root = layout.tabs.find((candidate) => candidate.tabId === tabId)?.root;
  if (root?.kind !== "split") throw new Error("四窗格布局根节点无效");
  return root;
}

describe("terminal split geometry", () => {
  it("keeps all four panes inside a 233px panel by constraining each ancestor", () => {
    const root = fourHorizontalPanes();
    const measurement = { axisExtent: 233, handleExtent: 12, paneMinimumExtent: 32 };
    const rootGeometry = terminalSplitGeometry(root, measurement, root.ratio);

    expect(terminalNodeMinimumExtent(root, "horizontal", 32, 12)).toBe(164);
    expect(rootGeometry.fitsMinimums).toBe(true);
    expect(rootGeometry.firstExtent).toBeCloseTo(101);
    expect(rootGeometry.minimumSecondExtent).toBe(120);

    if (root.second.kind !== "split" || root.second.second.kind !== "split")
      throw new Error("预期的四级分屏树结构已变化");
    const secondGeometry = terminalSplitGeometry(
      root.second,
      { ...measurement, axisExtent: rootGeometry.availableExtent - rootGeometry.firstExtent },
      root.second.ratio,
    );
    const thirdGeometry = terminalSplitGeometry(
      root.second.second,
      {
        ...measurement,
        axisExtent: secondGeometry.availableExtent - secondGeometry.firstExtent,
      },
      root.second.second.ratio,
    );

    expect(secondGeometry.fitsMinimums).toBe(true);
    expect(thirdGeometry.fitsMinimums).toBe(true);
    expect(thirdGeometry.minimumFirstExtent).toBe(32);
    expect(thirdGeometry.minimumSecondExtent).toBe(32);
  });

  it("returns finite geometry when the viewport cannot satisfy CSS pane minima", () => {
    const root = fourHorizontalPanes();
    const geometry = terminalSplitGeometry(
      root,
      { axisExtent: 120, handleExtent: 12, paneMinimumExtent: 32 },
      Number.NaN,
    );

    expect(geometry.fitsMinimums).toBe(false);
    expect(geometry.minimumRatio).toBe(32 / (32 + 120));
    expect(geometry.maximumRatio).toBe(32 / (32 + 120));
    expect(geometry.firstExtent).toBeCloseTo((120 - 12) * (32 / (32 + 120)));
    expect(
      [
        geometry.availableExtent,
        geometry.firstExtent,
        geometry.minimumFirstExtent,
        geometry.minimumSecondExtent,
        geometry.minimumRatio,
        geometry.maximumRatio,
        geometry.ratio,
      ].every(Number.isFinite),
    ).toBe(true);
  });

  it("does not derive interactive geometry from a zero-size pane", () => {
    const root = fourHorizontalPanes();
    const geometry = terminalSplitGeometry(
      root,
      { axisExtent: 0, handleExtent: 0, paneMinimumExtent: 32 },
      0.75,
    );

    expect(geometry.availableExtent).toBe(0);
    expect(geometry.ratio).toBe(0.75);
    expect(
      [
        geometry.availableExtent,
        geometry.firstExtent,
        geometry.minimumFirstExtent,
        geometry.minimumSecondExtent,
        geometry.minimumRatio,
        geometry.maximumRatio,
        geometry.ratio,
      ].every(Number.isFinite),
    ).toBe(true);
  });

  it("sums descendant minima only along the matching split orientation", () => {
    let layout = createDefaultTerminalLayout("ws_fixture");
    const tabId = layout.activeTabId;
    if (tabId === null) throw new Error("缺少默认终端标签");
    layout = splitTerminalPane(layout, tabId, layout.tabs[0]!.activePaneId, "vertical");
    const root = layout.tabs[0]?.root;
    if (root?.kind !== "split") throw new Error("垂直分屏布局根节点无效");

    expect(terminalNodeMinimumExtent(root, "vertical", 32, 12)).toBe(76);
    expect(terminalNodeMinimumExtent(root, "horizontal", 32, 12)).toBe(0);
  });
});
