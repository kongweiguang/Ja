// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readFileSync, readdirSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const DESKTOP_SOURCE = join(process.cwd(), "apps", "desktop", "src");
const COMPOSER_STYLE = join("features", "conversation", "ui", "composer", "composer.css");
const TASKS_STYLE = join("features", "tasks", "ui", "tasks.css");
const TIMELINE_STYLE = join("features", "conversation", "ui", "timeline", "timeline.css");
const NAVIGATION_STYLE = join("features", "navigation", "ui", "navigation.css");
const MCP_STYLE = join("features", "settings", "ui", "mcp.css");
const MODELS_STYLE = join("features", "settings", "ui", "models-overview.css");
const PROVIDER_EDITOR_STYLE = join("features", "settings", "ui", "provider-editor.css");
const SETTINGS_STYLE = join("features", "settings", "ui", "settings.css");
const SKILLS_ABOUT_STYLE = join("features", "settings", "ui", "skills-about.css");

type RadiusException = Readonly<{ selector: string; pixels: number }>;

/**
 * Settings 的卡片、对话框、控件和选择态使用已验收的 Apple 几何；每条记录绑定完整文件
 * 与 selector，避免把设置页的视觉例外扩展成全局圆角豁免，也不通过新增 CSS token 绕过策略。
 */
const SETTINGS_RADIUS_ALLOWLIST: Readonly<Record<string, readonly RadiusException[]>> = {
  [MCP_STYLE]: [
    { selector: ".ja-mcp-list", pixels: 14 },
    { selector: ".ja-mcp-row-icon, .ja-mcp-empty-icon", pixels: 9 },
    { selector: ".ja-settings-dialog.ja-mcp-dialog", pixels: 22 },
    {
      selector:
        ".ja-mcp-basic-fields .ja-settings-input, .ja-mcp-advanced-fields .ja-settings-input",
      pixels: 10,
    },
    { selector: ".ja-mcp-transport", pixels: 11 },
    { selector: ".ja-mcp-transport-option", pixels: 12 },
    { selector: ".ja-mcp-native-select", pixels: 10 },
  ],
  [MODELS_STYLE]: [
    { selector: ".ja-models-page .ja-settings-provider-master", pixels: 12 },
    { selector: ".ja-models-provider-icon", pixels: 9 },
    { selector: ".ja-models-group", pixels: 14 },
    { selector: ".ja-models-default", pixels: 9 },
  ],
  [PROVIDER_EDITOR_STYLE]: [
    { selector: ".ja-settings-sheet.ja-settings-provider-sheet", pixels: 22 },
    { selector: ".ja-provider-editor-body .ja-settings-input", pixels: 10 },
    { selector: ".ja-provider-model-row", pixels: 14 },
    { selector: ".ja-provider-model-index", pixels: 9 },
  ],
  [SETTINGS_STYLE]: [
    { selector: ".ja-settings-group", pixels: 14 },
    {
      selector:
        ".ja-settings-dialog.ja-dialog-content, .ja-settings-confirm-dialog, .ja-settings-confirm-dialog.ja-dialog-content",
      pixels: 22,
    },
    {
      selector:
        ".ja-settings .ja-button, .ja-settings-dialog .ja-button, .ja-settings-confirm-dialog .ja-button, .ja-settings-sheet .ja-button",
      pixels: 10,
    },
  ],
  [SKILLS_ABOUT_STYLE]: [
    { selector: ".ja-skill-group-heading .ja-settings-skill-count", pixels: 999 },
    { selector: ".ja-skill-main .ja-settings-file-icon", pixels: 9 },
    { selector: ".ja-about-identity .ja-settings-about-mark", pixels: 12 },
  ],
};

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
 * 从当前声明定位 CSS selector，并移除前置块注释；这样解释性注释不会改变白名单身份，
 * 同时保留完整组合 selector，避免只匹配其中一个 class 而放宽其它规则。
 */
function radiusSelector(source: string, valueOffset: number): string {
  const ruleStart = source.lastIndexOf("}", valueOffset) + 1;
  const selectorEnd = source.lastIndexOf("{", valueOffset);
  return source
    .slice(ruleStart, selectorEnd)
    .replace(/\/\*[\s\S]*?\*\//gu, "")
    .trim()
    .replace(/\s+/gu, " ");
}

/**
 * 只有滚动条、标准 Switch 轨道和 Task 未读计数可以使用精确 999px 胶囊几何；Task 例外
 * 同时绑定文件与 selector，避免其它文本标签借同名样式或任意大圆角绕过紧凑工作台约束。
 */
function approvedPill(file: string, source: string, valueOffset: number, pixels: number): boolean {
  const selector = radiusSelector(source, valueOffset);
  return (
    ((selector === ".ja-scrollbar-thumb" || selector === ".ja-settings-switch") &&
      pixels === 999) ||
    (relative(DESKTOP_SOURCE, file) === TASKS_STYLE &&
      selector === ".ja-task-unread" &&
      pixels === 999)
  );
}

/**
 * 只放行已在 Settings 真实组件中验证的文件、完整 selector 与像素组合；未知角色、近似
 * selector 或不同值必须继续落入 8px 紧凑工作台约束，避免白名单成为全局逃生口。
 */
function approvedSettingsRadius(
  file: string,
  source: string,
  valueOffset: number,
  pixels: number,
): boolean {
  const rules = SETTINGS_RADIUS_ALLOWLIST[relative(DESKTOP_SOURCE, file)];
  if (rules === undefined) return false;
  const selector = radiusSelector(source, valueOffset);
  return rules.some((rule) => rule.selector === selector && rule.pixels === pixels);
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
  const selector = radiusSelector(source, valueOffset);
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
  const selector = radiusSelector(source, valueOffset);
  return (
    selector === ".ja-chat-message-user .ja-chat-message__body" &&
    value.replace(/\s+/gu, " ") === "18px 18px 7px 18px"
  );
}

/** 运行态恢复详情是独立的短内容浮层；白名单绑定导航样式、选择器与精确 14px，避免放宽全局上限。 */
function approvedNavigationPopoverRadius(
  file: string,
  source: string,
  valueOffset: number,
  pixels: number,
): boolean {
  if (relative(DESKTOP_SOURCE, file) !== NAVIGATION_STYLE || pixels !== 14) return false;
  const selector = radiusSelector(source, valueOffset);
  return selector === ".ja-popover-content.ja-navigation-runtime-popover";
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
          !approvedPill(file, source, offset, pixels) &&
          !approvedSettingsRadius(file, source, offset, pixels) &&
          !approvedComposerRadius(file, source, offset, pixels) &&
          !approvedUserMessageRadius(file, source, offset, value) &&
          !approvedNavigationPopoverRadius(file, source, offset, pixels)
        ) {
          violations.push(`${relative(DESKTOP_SOURCE, file)}: ${value}`);
        }
      }
    }
    expect(violations).toEqual([]);
  });

  /** 精确反例固定白名单边界：同文件的陌生 selector、不同值和注释外的合法 Switch 不得互相污染。 */
  it("keeps settings radius exceptions exact", () => {
    const mcpFile = join(DESKTOP_SOURCE, MCP_STYLE);
    const mcpSource = [
      "/* selector comments must not change the rule identity */",
      ".ja-mcp-list { border-radius: 14px; }",
      ".ja-mcp-list.is-unknown { border-radius: 14px; }",
      ".ja-mcp-list { border-radius: 15px; }",
    ].join("\n");
    const mcpOffsets = [...mcpSource.matchAll(/border-radius\s*:\s*([^;]+);/giu)].map(
      (match) => (match.index ?? 0) + match[0].indexOf(match[1] ?? ""),
    );
    expect(approvedSettingsRadius(mcpFile, mcpSource, mcpOffsets[0] ?? -1, 14)).toBe(true);
    expect(approvedSettingsRadius(mcpFile, mcpSource, mcpOffsets[1] ?? -1, 14)).toBe(false);
    expect(approvedSettingsRadius(mcpFile, mcpSource, mcpOffsets[2] ?? -1, 15)).toBe(false);

    const switchSource = "/* switch geometry */\n.ja-settings-switch { border-radius: 999px; }";
    const switchOffset = switchSource.indexOf("999px");
    const settingsFile = join(DESKTOP_SOURCE, SETTINGS_STYLE);
    expect(approvedPill(settingsFile, switchSource, switchOffset, 999)).toBe(true);
    expect(approvedPill(settingsFile, switchSource, switchOffset, 998)).toBe(false);
  });
});
