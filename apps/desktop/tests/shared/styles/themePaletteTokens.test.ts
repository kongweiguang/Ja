// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

type Palette = "xcode" | "fleet" | "obsidian" | "claude";
type Mode = "light" | "dark";

const TOKENS_FILE = join(process.cwd(), "apps", "desktop", "src", "shared", "styles", "tokens.css");
const PRIMITIVES_FILE = join(
  process.cwd(),
  "apps",
  "desktop",
  "src",
  "shared",
  "styles",
  "primitives.css",
);
const APP_STYLES_FILE = join(process.cwd(), "apps", "desktop", "src", "app", "App.css");
const TOKENS_SOURCE = readFileSync(TOKENS_FILE, "utf8").toLowerCase();
const PRIMITIVES_SOURCE = readFileSync(PRIMITIVES_FILE, "utf8").toLowerCase();
const APP_STYLES_SOURCE = readFileSync(APP_STYLES_FILE, "utf8").toLowerCase();
const PALETTES: readonly Palette[] = ["xcode", "fleet", "obsidian", "claude"];
const MODES: readonly Mode[] = ["light", "dark"];

const REQUIRED_COLOR_TOKENS = [
  "--ja-background",
  "--ja-content",
  "--ja-surface",
  "--ja-surface-raised",
  "--ja-surface-muted",
  "--ja-control",
  "--ja-control-hover",
  "--ja-control-active",
  "--ja-selected",
  "--ja-titlebar",
  "--ja-sidebar",
  "--ja-inspector",
  "--ja-editor-background",
  "--ja-floating",
  "--ja-composer",
  "--ja-menu",
  "--ja-dialog",
  "--ja-window-surface",
  "--ja-glass",
  "--ja-glass-strong",
  "--ja-glass-border",
  "--ja-border",
  "--ja-border-strong",
  "--ja-foreground",
  "--ja-foreground-muted",
  "--ja-foreground-tertiary",
  "--ja-accent",
  "--ja-accent-emphasis",
  "--ja-accent-hover",
  "--ja-accent-soft",
  "--ja-on-accent",
  "--ja-focus",
  "--ja-syntax-plain",
  "--ja-syntax-comment",
  "--ja-syntax-keyword",
  "--ja-syntax-type",
  "--ja-syntax-attribute",
  "--ja-syntax-symbol",
  "--ja-syntax-property",
  "--ja-syntax-string",
  "--ja-syntax-number",
  "--ja-syntax-url",
  "--ja-diff-inserted",
  "--ja-diff-deleted",
  "--ja-diff-changed",
  "--ja-scrollbar-thumb",
  "--ja-scrollbar-thumb-hover",
  "--ja-scrollbar-thumb-active",
  "--ja-terminal-background",
  "--ja-terminal-foreground",
  "--ja-terminal-cursor",
  "--ja-terminal-selection",
  "--ja-terminal-ansi-black",
  "--ja-terminal-ansi-red",
  "--ja-terminal-ansi-green",
  "--ja-terminal-ansi-yellow",
  "--ja-terminal-ansi-blue",
  "--ja-terminal-ansi-magenta",
  "--ja-terminal-ansi-cyan",
  "--ja-terminal-ansi-white",
  "--ja-terminal-ansi-bright-black",
  "--ja-terminal-ansi-bright-red",
  "--ja-terminal-ansi-bright-green",
  "--ja-terminal-ansi-bright-yellow",
  "--ja-terminal-ansi-bright-blue",
  "--ja-terminal-ansi-bright-magenta",
  "--ja-terminal-ansi-bright-cyan",
  "--ja-terminal-ansi-bright-white",
] as const;

const ANCHORS: Readonly<Record<`${Palette}-${Mode}`, readonly [string, string, string]>> = {
  "xcode-light": ["#f5f5f5", "#ffffff", "#007aff"],
  "xcode-dark": ["#1c1d2b", "#292a30", "#0a84ff"],
  "fleet-light": ["#f2f2f2", "#ffffff", "#726cf9"],
  "fleet-dark": ["#090909", "#18191b", "#726cf9"],
  "obsidian-light": ["#f6f6f6", "#ffffff", "#9873f7"],
  "obsidian-dark": ["#1e1e1e", "#242424", "#8a5cf5"],
  "claude-light": ["#f5f4ed", "#faf9f5", "#d97757"],
  "claude-dark": ["#141413", "#1a1918", "#c6613f"],
};

/** 只提取精确 selector 的平坦声明，避免媒体查询内同名 root 覆盖测试期事实。 */
function declarationsFor(selector: string): ReadonlyMap<string, string> {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  const match = TOKENS_SOURCE.match(new RegExp(`${escaped}\\s*\\{([^{}]*)\\}`, "u"));
  expect(match, `missing CSS selector ${selector}`).not.toBeNull();
  const declarations = new Map<string, string>();
  for (const item of match?.[1]?.matchAll(/(--ja-[a-z0-9-]+)\s*:\s*([^;]+);/gu) ?? []) {
    const [, tokenName, tokenValue] = item;
    if (tokenName === undefined || tokenValue === undefined) continue;
    declarations.set(tokenName, tokenValue.trim());
  }
  return declarations;
}

/** 按真实 cascade 合并基础、模式、通用 Palette 与精确组合，验证的是最终可消费合同。 */
function resolvedTokens(palette: Palette, mode: Mode): ReadonlyMap<string, string> {
  const resolved = new Map(declarationsFor(":root"));
  if (palette === "xcode") {
    for (const [name, value] of declarationsFor(':root[data-palette="xcode"]')) {
      resolved.set(name, value);
    }
  }
  if (mode === "dark") {
    for (const [name, value] of declarationsFor(':root[data-theme="dark"]')) {
      resolved.set(name, value);
    }
  }
  const exactSelector = `:root[data-theme="${mode}"][data-palette="${palette}"]`;
  if (!(palette === "xcode" && mode === "light")) {
    for (const [name, value] of declarationsFor(exactSelector)) {
      resolved.set(name, value);
    }
  }
  return resolved;
}

/** 对比度断言只接受确定的十六进制锚点，避免 alpha 混色让测试伪造未知背景。 */
function parseHex(value: string): readonly [number, number, number] {
  expect(value).toMatch(/^#[0-9a-f]{6}$/u);
  return [1, 3, 5].map((index) =>
    Number.parseInt(value.slice(index, index + 2), 16),
  ) as unknown as readonly [number, number, number];
}

/** WCAG 相对亮度在 sRGB 线性空间计算，确保八组合使用同一客观阈值。 */
function relativeLuminance(value: string): number {
  const channels = parseHex(value).map((channel) => {
    const normalized = channel / 255;
    return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * channels[0]! + 0.7152 * channels[1]! + 0.0722 * channels[2]!;
}

/** 比值采用 WCAG 定义，较亮与较暗颜色顺序不会影响测试结果。 */
function contrastRatio(first: string, second: string): number {
  const firstLuminance = relativeLuminance(first);
  const secondLuminance = relativeLuminance(second);
  return (
    (Math.max(firstLuminance, secondLuminance) + 0.05) /
    (Math.min(firstLuminance, secondLuminance) + 0.05)
  );
}

describe("four-palette semantic token contract", () => {
  it("resolves every required role for all eight palette and mode combinations", () => {
    for (const palette of PALETTES) {
      for (const mode of MODES) {
        const tokens = resolvedTokens(palette, mode);
        const missing = REQUIRED_COLOR_TOKENS.filter((token) => !tokens.has(token));
        expect(missing, `${palette}-${mode}`).toEqual([]);
      }
    }
  });

  it("keeps the approved background, content/editor and accent anchors", () => {
    for (const palette of PALETTES) {
      for (const mode of MODES) {
        const tokens = resolvedTokens(palette, mode);
        const [background, contentOrEditor, accent] = ANCHORS[`${palette}-${mode}`];
        expect(tokens.get("--ja-background"), `${palette}-${mode} background`).toBe(background);
        expect(
          tokens.get(mode === "light" ? "--ja-content" : "--ja-editor-background"),
          `${palette}-${mode} content`,
        ).toBe(contentOrEditor);
        expect(tokens.get("--ja-accent"), `${palette}-${mode} accent`).toBe(accent);
      }
    }
  });

  it("keeps semantic status colors independent from Palette", () => {
    for (const mode of MODES) {
      const baseline = resolvedTokens("xcode", mode);
      for (const palette of PALETTES) {
        const tokens = resolvedTokens(palette, mode);
        for (const role of ["--ja-success", "--ja-warning", "--ja-danger"] as const) {
          expect(tokens.get(role), `${palette}-${mode} ${role}`).toBe(baseline.get(role));
        }
      }
    }
  });

  it("meets 4.5 to 1 for text, focus and primary button in every combination", () => {
    for (const palette of PALETTES) {
      for (const mode of MODES) {
        const tokens = resolvedTokens(palette, mode);
        const background = tokens.get("--ja-background")!;
        const checks = [
          ["primary text", tokens.get("--ja-foreground")!, background],
          ["secondary text", tokens.get("--ja-foreground-muted")!, background],
          [
            "conversation heading",
            tokens.get("--ja-foreground")!,
            tokens.get("--ja-window-surface")!,
          ],
          ["focus", tokens.get("--ja-focus")!, background],
          ["primary button", tokens.get("--ja-on-accent")!, tokens.get("--ja-accent-emphasis")!],
        ] as const;
        for (const [label, foreground, surface] of checks) {
          expect(
            contrastRatio(foreground, surface),
            `${palette}-${mode} ${label}`,
          ).toBeGreaterThanOrEqual(4.5);
        }
      }
    }
  });

  it("keeps the conversation title and folder icon on one theme-owned foreground", () => {
    expect(APP_STYLES_SOURCE).toMatch(
      /\.ja-conversation-heading\s*\{[^}]*color:\s*var\(--ja-foreground\);[^}]*\}/u,
    );
    expect(APP_STYLES_SOURCE).toMatch(
      /\.ja-conversation-heading svg\s*\{[^}]*color:\s*currentcolor;[^}]*\}/u,
    );
    expect(APP_STYLES_SOURCE).not.toMatch(
      /\.ja-conversation-heading svg\s*\{[^}]*color:\s*var\(--ja-foreground-muted\);[^}]*\}/u,
    );
  });

  it("makes reduced transparency, high contrast and forced colors stronger than Palette", () => {
    expect(TOKENS_SOURCE).toContain(':root[data-reduced-transparency="true"]');
    expect(TOKENS_SOURCE).toContain(':root[data-high-contrast="true"]');
    expect(TOKENS_SOURCE).toContain("@media (prefers-reduced-transparency: reduce)");
    expect(TOKENS_SOURCE).toContain("@media (prefers-contrast: more)");
    expect(TOKENS_SOURCE).toContain("@media (forced-colors: active)");
    expect(PRIMITIVES_SOURCE).toContain(
      ':root[data-reduced-transparency="true"] .ja-floating-surface',
    );
    expect(PRIMITIVES_SOURCE).toContain("backdrop-filter: none");
  });
});
