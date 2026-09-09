// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useMediaQuery } from "@/shared/hooks/useMediaQuery";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import { resolveTheme, type ResolvedTheme, type UiPalette } from "@/shared/styles/theme";

/**
 * 统一把持久化 ThemeMode 与实时系统配色合成为渲染器可消费的最终主题；
 * CodeMirror、xterm 与 document theme 因而共享同一事实，而不各自监听 OS 并产生切换竞态。
 */
export function useResolvedTheme(): ResolvedTheme {
  const mode = useUiPreferencesStore((state) => state.themeMode);
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)");
  return resolveTheme(mode, prefersDark);
}

/**
 * Feature 只读取 Palette 事实，不直接依赖 Zustand 持久化 owner；该窄 hook 让 Editor 与 xterm
 * 可订阅同一状态，同时保持 shared/preferences 不泄漏到 feature 责任层。
 */
export function useUiPalette(): UiPalette {
  return useUiPreferencesStore((state) => state.palette);
}
