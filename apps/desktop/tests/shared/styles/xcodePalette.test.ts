// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const DESKTOP_SOURCE = join(process.cwd(), "apps", "desktop", "src");
const TOKENS_FILE = join(DESKTOP_SOURCE, "shared", "styles", "tokens.css");
const TERMINAL_UI = join(DESKTOP_SOURCE, "features", "workbench", "terminal", "ui");

const XCODE_DARK_SURFACES = [
  "--ja-sidebar: #1c1d2b",
  "--ja-titlebar: #21222f",
  "--ja-inspector: #21222f",
  "--ja-editor-background: #292a30",
  "--ja-floating: #23242e",
  "--ja-selected: #464646",
  "--ja-debug-active: #304435",
] as const;

const RETIRED_TERMINAL_LITERALS = [
  "#25262a",
  "#a7abb4",
  "#8fafff",
  "#383a40",
  "#303239",
  "#4768ae",
  "#5377c3",
  "#202126",
  "#17181b",
  "#2d2618",
  "#f4ca72",
  "#ff9d9d",
  "#cc6666",
  "#5575bf",
] as const;

/** 只递归读取 Terminal UI 的人工源码，避免把锁文件或第三方默认主题误报成生产绕行。 */
function terminalThemeSources(root: string): string[] {
  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const path = join(root, entry.name);
    if (entry.isDirectory()) return terminalThemeSources(path);
    return entry.isFile() && /\.(?:css|tsx?)$/u.test(entry.name) ? [path] : [];
  });
}

describe("Xcode dark palette contract", () => {
  it("keeps every official sampled surface in the single semantic token owner", () => {
    const source = readFileSync(TOKENS_FILE, "utf8").toLowerCase();
    for (const declaration of XCODE_DARK_SURFACES) {
      expect(source, declaration).toContain(declaration);
    }
  });

  it("prevents terminal renderers from restoring retired one-off dark colors", () => {
    const violations: string[] = [];
    for (const file of terminalThemeSources(TERMINAL_UI)) {
      const source = readFileSync(file, "utf8").toLowerCase();
      for (const color of RETIRED_TERMINAL_LITERALS) {
        if (source.includes(color)) {
          violations.push(`${relative(DESKTOP_SOURCE, file)}: ${color}`);
        }
      }
    }
    expect(violations).toEqual([]);
  });
});
