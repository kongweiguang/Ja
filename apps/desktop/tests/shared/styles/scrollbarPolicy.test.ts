// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const DESKTOP_SOURCE = join(process.cwd(), "apps", "desktop", "src");

/** 读取生产样式的真实文本，使布局合同测试不会通过测试专用 class 重建另一套滚动模型。 */
function styles(...segments: string[]): string {
  return readFileSync(join(DESKTOP_SOURCE, ...segments), "utf8");
}

describe("desktop scrollbar policy", () => {
  it("uses one natural-height navigation scroll surface instead of nested project lists", () => {
    const navigation = styles("features", "navigation", "ui", "navigation.css");

    expect(navigation).toMatch(
      /\.ja-navigation-content\s*\{[^}]*overflow-y:\s*auto;[^}]*scrollbar-gutter:\s*stable;/su,
    );
    expect(navigation).toMatch(/\.ja-navigation-projects\s*\{[^}]*flex:\s*0 0 auto;/su);
    expect(navigation).not.toMatch(/\.ja-navigation-projects\s*\{[^}]*max-height:/su);
    expect(navigation).toMatch(
      /\.ja-navigation-project-list,\s*\.ja-navigation-history-list\s*\{[^}]*overflow:\s*visible;/su,
    );
  });

  it("shares theme-aware native and Radix scrollbar states without Windows arrow buttons", () => {
    const tokens = styles("shared", "styles", "tokens.css");
    const app = styles("app", "App.css");
    const primitives = styles("shared", "styles", "primitives.css");

    expect(tokens).toMatch(/--ja-scrollbar-thumb:\s*color-mix\(/u);
    expect(tokens).toMatch(/--ja-scrollbar-thumb-hover:\s*color-mix\(/u);
    expect(app).toMatch(
      /\*::-webkit-scrollbar-thumb\s*\{[^}]*background:\s*var\(--ja-scrollbar-thumb\);/su,
    );
    expect(app).toMatch(/\*::-webkit-scrollbar-button\s*\{[^}]*display:\s*none;/su);
    expect(primitives).toMatch(
      /\.ja-scrollbar-thumb\s*\{[^}]*background:\s*var\(--ja-scrollbar-thumb\);/su,
    );
    expect(primitives).toMatch(
      /\.ja-scrollbar:hover \.ja-scrollbar-thumb,[^}]*background:\s*var\(--ja-scrollbar-thumb-hover\);/su,
    );
  });
});
