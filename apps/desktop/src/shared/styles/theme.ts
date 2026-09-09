// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type ThemeMode = "system" | "light" | "dark";
/** Palette 顺序是无状态主题合同，由持久化、Settings 与渲染器共同消费而不反向依赖 store。 */
export const UI_PALETTE_ORDER = ["xcode", "fleet", "obsidian", "claude"] as const;
export type UiPalette = (typeof UI_PALETTE_ORDER)[number];

/** 中文标签只描述配色气质来源，不暗示 Ja 复制对应产品的布局或品牌资源。 */
export const UI_PALETTE_LABELS: Readonly<Record<UiPalette, string>> = {
  xcode: "Xcode",
  fleet: "Fleet",
  obsidian: "Obsidian",
  claude: "Claude",
};

/** 只接受当前四套 Palette；损坏或未知介质统一回到 Xcode，不解释旧别名。 */
export function normalizeUiPalette(value: unknown): UiPalette {
  return typeof value === "string" && UI_PALETTE_ORDER.includes(value as UiPalette)
    ? (value as UiPalette)
    : "xcode";
}

export type ResolvedTheme = "light" | "dark";

/**
 * system mode 在 document 边界解析，使组件只消费语义 token，
 * 不重复实现 OS media-query 逻辑。
 */
export function resolveTheme(mode: ThemeMode, prefersDark: boolean): ResolvedTheme {
  if (mode === "system") {
    return prefersDark ? "dark" : "light";
  }
  return mode;
}

/**
 * 原子应用属性可避免 light/dark 闪烁，并使 Palette、对比度、动效与透明度偏好
 * 被 CSS、Portal 和辅助 UI 从同一根节点一致读取。
 */
export function applyTheme(
  root: HTMLElement,
  options: {
    mode: ThemeMode;
    palette: unknown;
    highContrast: boolean;
    reduceMotion: boolean;
    reducedTransparency: boolean;
    prefersDark: boolean;
  },
): void {
  const resolvedTheme = resolveTheme(options.mode, options.prefersDark);
  const palette = normalizeUiPalette(options.palette);

  root.dataset["theme"] = resolvedTheme;
  root.dataset["themeMode"] = options.mode;
  root.dataset["palette"] = palette;
  root.dataset["highContrast"] = String(options.highContrast);
  root.dataset["reduceMotion"] = String(options.reduceMotion);
  root.dataset["reducedTransparency"] = String(options.reducedTransparency);

  // CSS selector 同时使用 data attribute 与 class：attribute 用于语义 token 分支，
  // class 允许 feature style 选择稳定状态而不耦合持久化 enum 值。
  const stateClasses = [
    "ja-theme-light",
    "ja-theme-dark",
    "ja-theme-mode-system",
    "ja-theme-mode-light",
    "ja-theme-mode-dark",
    ...UI_PALETTE_ORDER.map((value) => `ja-palette-${value}`),
    "ja-high-contrast",
    "ja-reduce-motion",
    "ja-reduced-transparency",
  ];
  root.classList.remove(...stateClasses);
  root.classList.add(
    `ja-theme-${resolvedTheme}`,
    `ja-theme-mode-${options.mode}`,
    `ja-palette-${palette}`,
  );
  if (options.highContrast) root.classList.add("ja-high-contrast");
  if (options.reduceMotion) root.classList.add("ja-reduce-motion");
  if (options.reducedTransparency) root.classList.add("ja-reduced-transparency");
}
