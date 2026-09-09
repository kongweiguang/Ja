// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const DESKTOP_SOURCE = join(process.cwd(), "apps", "desktop", "src");

/** 读取真实生产文件，防止组件测试用测试专用 class 伪造动态分隔器合同。 */
function source(...segments: string[]): string {
  return readFileSync(join(DESKTOP_SOURCE, ...segments), "utf8");
}

/**
 * 只收集公共分隔器 selector 的声明；媒体查询外壳不影响规则提取，且不会误扫 Palette 定义。
 */
function resizeHandleRules(styles: string): string {
  return [...styles.matchAll(/([^{}]*\.ja-resize-handle[^{}]*)\{([^{}]*)\}/gsu)]
    .map(([, selector, declarations]) => `${selector ?? ""}{${declarations ?? ""}}`)
    .join("\n");
}

describe("shared resize handle visual policy", () => {
  const appStyles = source("app", "App.css");
  const navigationStyles = source("features", "navigation", "ui", "navigation.css");
  const tokens = source("shared", "styles", "tokens.css");
  const navigationComponent = source("features", "navigation", "ui", "NavigationResizeHandle.tsx");
  const workbenchComponent = source("features", "workbench", "ui", "WorkbenchResizeHandle.tsx");
  const visualRules = resizeHandleRules(appStyles);

  it("routes both outer splitters through one shell-owned visual class", () => {
    expect(navigationComponent).toMatch(/className=["'][^"']*ja-resize-handle[^"']*["']/u);
    expect(navigationComponent).toMatch(
      /className=["'][^"']*ja-navigation-resize-handle[^"']*["']/u,
    );
    expect(workbenchComponent).toMatch(/className=["'][^"']*ja-resize-handle[^"']*["']/u);
    expect(workbenchComponent).toMatch(/className=["'][^"']*ja-workbench-resize-handle[^"']*["']/u);
    expect(appStyles).toContain(".ja-resize-handle");
    expect(navigationStyles).not.toMatch(/\.ja-navigation-resize-handle::(?:before|after)/u);
  });

  it("builds a restrained vertical spotlight from semantic theme tokens", () => {
    expect(visualRules).toContain("--ja-resize-pointer-y");
    expect(visualRules).toMatch(/top:\s*var\(--ja-resize-pointer-y,\s*50%\);/u);
    expect(visualRules).toMatch(/linear-gradient\(\s*to bottom/iu);
    expect(visualRules).toContain("var(--ja-accent)");
    expect(visualRules).toContain("var(--ja-focus)");
    expect(visualRules).toMatch(/width:\s*3px;/u);
    expect(visualRules).toMatch(/height:\s*clamp\(96px,\s*18vh,\s*160px\);/u);
    expect(visualRules).toMatch(/\.ja-resize-handle:hover[^{}]*\{[^}]*opacity:\s*0\.82;/su);
    expect(visualRules).toMatch(/\.ja-resize-handle\[data-dragging\][^{}]*\{[^}]*opacity:\s*1;/su);
    expect(visualRules).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(|hsla?\(/iu);
  });

  it("keeps keyboard, reduced-motion and forced-colors fallbacks explicit", () => {
    expect(visualRules).toMatch(/\.ja-resize-handle:focus-visible[^{}]*\{/u);
    expect(visualRules).toMatch(/transition:\s*opacity\s+120ms/u);
    expect(tokens).toMatch(/:root\[data-reduce-motion="true"\][\s\S]*transition-duration:/u);
    expect(appStyles).toMatch(
      /:root\[data-reduce-motion="true"\]\s+\.ja-resize-handle::after\s*\{[^}]*transition:\s*none;/su,
    );
    expect(appStyles).toMatch(
      /@media\s*\(prefers-reduced-motion:\s*reduce\)[\s\S]*?\.ja-resize-handle[\s\S]*?transition:\s*none;/u,
    );
    expect(appStyles).toMatch(
      /@media\s*\(forced-colors:\s*active\)[\s\S]*?\.ja-resize-handle[\s\S]*?(?:background|background-image):\s*(?:Highlight|none)/u,
    );
  });
});
