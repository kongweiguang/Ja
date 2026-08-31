// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { normalizeUiPalette, type ThemeMode } from "@/shared/preferences/uiPreferences";

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
 * 原子应用属性可避免 light/dark 闪烁，并使 palette、contrast 与 reduced-motion
 * 选择可被 CSS 和辅助 UI 一致读取。
 */
export function applyTheme(
  root: HTMLElement,
  options: {
    mode: ThemeMode;
    palette: unknown;
    highContrast: boolean;
    reduceMotion: boolean;
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

  // CSS selector 同时使用 data attribute 与 class：attribute 用于语义 token 分支，
  // class 允许 feature style 选择稳定状态而不耦合持久化 enum 值。
  const stateClasses = [
    "ja-theme-light",
    "ja-theme-dark",
    "ja-theme-mode-system",
    "ja-theme-mode-light",
    "ja-theme-mode-dark",
    "ja-palette-xcode",
    "ja-high-contrast",
    "ja-reduce-motion",
  ];
  root.classList.remove(...stateClasses);
  root.classList.add(
    `ja-theme-${resolvedTheme}`,
    `ja-theme-mode-${options.mode}`,
    "ja-palette-xcode",
  );
  if (options.highContrast) root.classList.add("ja-high-contrast");
  if (options.reduceMotion) root.classList.add("ja-reduce-motion");
}
