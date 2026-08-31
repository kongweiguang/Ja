// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * 锁定滚动条双侧占位，避免 Windows 常驻滚动条把 Timeline 的 800px 轨道相对 Composer
 * 偏移半个滚动条宽度；真实像素一致性仍由 WebView2 验收补充。
 */
function timelineStyles(): string {
  return readFileSync(
    join(
      process.cwd(),
      "apps",
      "desktop",
      "src",
      "features",
      "conversation",
      "ui",
      "timeline",
      "timeline.css",
    ),
    "utf8",
  );
}

describe("conversation content rail policy", () => {
  it("reserves symmetric scrollbar gutters around the timeline rail", () => {
    expect(timelineStyles()).toMatch(/scrollbar-gutter:\s*stable both-edges;/u);
  });
});
