// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const DESKTOP_SOURCE = join(process.cwd(), "apps", "desktop", "src");
const COMPOSER_STYLE = join("features", "conversation", "ui", "composer", "composer.css");
const TASKS_STYLE = join("features", "tasks", "ui", "tasks.css");
const TIMELINE_STYLE = join("features", "conversation", "ui", "timeline", "timeline.css");

/** 递归读取生产 CSS，避免新增 Feature 绕过紧凑工作台与明确 Apple 输入器的圆角边界。 */
function cssFiles(root: string): string[] {
  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const path = join(root, entry.name);
    if (entry.isDirectory()) return cssFiles(path);
    return entry.isFile() && entry.name.endsWith(".css") ? [path] : [];
  });
}

/** 将简单长度转换成像素；变量、百分比和继承值由各自语义约束覆盖。 */
function radiusPixels(value: string): number | undefined {
  const normalized = value.trim().toLowerCase();
  if (normalized.endsWith("px")) return Number.parseFloat(normalized);
  if (normalized.endsWith("rem")) return Number.parseFloat(normalized) * 16;
  return undefined;
}

/**
 * 只有滚动条、标准 Switch 轨道和 Task 未读计数可以使用胶囊几何；Task 例外同时绑定
 * 文件与 selector，避免其它文本标签借同名样式或任意 999px 绕过紧凑工作台约束。
 */
function approvedPill(file: string, source: string, valueOffset: number): boolean {
  const ruleStart = source.lastIndexOf("}", valueOffset) + 1;
  const selector = source.slice(ruleStart, source.lastIndexOf("{", valueOffset)).trim();
  return (
    selector === ".ja-scrollbar-thumb" ||
    selector === ".ja-settings-switch" ||
    (relative(DESKTOP_SOURCE, file) === TASKS_STYLE && selector === ".ja-task-unread")
  );
}

/**
 * Composer 是高频输入主表面，允许 Apple 式 20/18px 外壳和 10px 内部控件；白名单同时绑定
 * 文件、选择器与精确值，避免放宽全局紧凑工作台的 8px 上限。
 */
function approvedComposerRadius(
  file: string,
  source: string,
  valueOffset: number,
  pixels: number,
): boolean {
  if (relative(DESKTOP_SOURCE, file) !== COMPOSER_STYLE) return false;
  const ruleStart = source.lastIndexOf("}", valueOffset) + 1;
  const selector = source.slice(ruleStart, source.lastIndexOf("{", valueOffset)).trim();
  if (selector === ".ja-composer") return pixels === 20 || pixels === 18;
  return (
    (selector === ".ja-composer-attachment" || selector === ".ja-composer__model-trigger") &&
    pixels === 10
  );
}

/**
 * 用户请求气泡允许与方向一致的 Apple 消息轮廓；白名单绑定文件、单一 selector 和四角精确值，
 * 避免把大圆角扩散到回复正文、工作过程或其它工作台卡片。
 */
function approvedUserMessageRadius(
  file: string,
  source: string,
  valueOffset: number,
  value: string,
): boolean {
  if (relative(DESKTOP_SOURCE, file) !== TIMELINE_STYLE) return false;
  const ruleStart = source.lastIndexOf("}", valueOffset) + 1;
  const selector = source.slice(ruleStart, source.lastIndexOf("{", valueOffset)).trim();
  return (
    selector === ".ja-chat-message-user .ja-chat-message__body" &&
    value.replace(/\s+/gu, " ") === "18px 18px 7px 18px"
  );
}

describe("shared desktop radius policy", () => {
  it("keeps the workbench compact while allowing explicit Apple input and message geometry", () => {
    const composerSource = readFileSync(join(DESKTOP_SOURCE, COMPOSER_STYLE), "utf8");
    expect(composerSource).toMatch(
      /\.ja-composer\s*\{[\s\S]*?--ja-composer-suggestion-radius:\s*14px;/,
    );
    expect(composerSource).toMatch(
      /\.ja-composer-suggestions\s*\{[\s\S]*?border-radius:\s*var\(--ja-composer-suggestion-radius\);/,
    );

    const violations: string[] = [];
    for (const file of cssFiles(DESKTOP_SOURCE)) {
      const source = readFileSync(file, "utf8");
      for (const match of source.matchAll(/border-radius\s*:\s*([^;]+);/giu)) {
        const value = match[1]?.trim() ?? "";
        const offset = match.index ?? 0;
        const pixels = radiusPixels(value);
        if (
          pixels !== undefined &&
          pixels > 8 &&
          !approvedPill(file, source, offset) &&
          !approvedComposerRadius(file, source, offset, pixels) &&
          !approvedUserMessageRadius(file, source, offset, value)
        ) {
          violations.push(`${relative(DESKTOP_SOURCE, file)}: ${value}`);
        }
      }
    }
    expect(violations).toEqual([]);
  });
});
