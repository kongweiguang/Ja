// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const styles = readFileSync(join(process.cwd(), "apps/desktop/src/app/App.css"), "utf8");

describe("workspace corner theme policy", () => {
  // 两层都检查，防止任一不透明祖先重新覆盖圆角外露区域的主题底色。
  it.each(["ja-shell", "ja-layout"])("keeps %s on the theme background", (className) => {
    const sheet = new CSSStyleSheet();
    sheet.insertRule(styles.match(new RegExp(`\\.${className} \\{[^}]*\\}`, "u"))![0]);
    const rule = sheet.cssRules[0] as CSSStyleRule;
    expect(rule.style.getPropertyValue("background")).toBe("var(--ja-background)");
  });

  // 修正衬底而非抹平圆角，保留既有导航展开状态下的内容边界。
  it("preserves the workspace rounded clipping", () => {
    expect(styles).toMatch(/\.ja-workspace-stage\s*\{[^}]*overflow:\s*hidden;/u);
    expect(styles).toMatch(
      /\.ja-workspace-stage\s*\{[^}]*border-top-left-radius:\s*var\(--ja-radius-pane\);/u,
    );
    expect(styles).not.toMatch(/\.ja-workspace-stage\s*\{[^}]*box-shadow:/u);
  });

  // 光带必须沿真实圆角轮廓转弯，而非仅把直线顶部截断；伪元素不能扩大命中区。
  it("curves navigation feedback along the workspace radius", () => {
    expect(styles).toMatch(/\.ja-navigation-resize-handle::before\s*\{\s*content:\s*none;/u);
    expect(styles).toMatch(
      /\.ja-navigation-resize-handle::after\s*\{[^}]*border-radius:\s*var\(--ja-radius-pane\) 0 0 0;/u,
    );
    expect(styles).toContain("mask-composite: exclude;");
    expect(styles).toContain("ellipse 72px 80px at 0 var(--ja-resize-pointer-y, 50%)");
    expect(styles).toMatch(
      /\.ja-navigation-resize-handle::after\s*\{[^}]*background-origin:\s*border-box;\s*background-repeat:\s*no-repeat;/u,
    );
    expect(styles).toMatch(/\.ja-resize-handle::after\s*\{[^}]*pointer-events:\s*none;/u);
    expect(styles).toMatch(/\.ja-resize-handle\s*\{\s*width:\s*11px;/u);
    expect(styles).toContain(".ja-resize-handle:focus-visible::after");
  });
});
