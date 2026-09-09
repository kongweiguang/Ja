// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useLayoutEffect, type PropsWithChildren, type ReactElement } from "react";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import { useResolvedTheme } from "@/shared/hooks/useResolvedTheme";
import { applyTheme } from "@/shared/styles/theme";

/**
 * Provider 原子投影全部 document-level 外观属性，使 Portal 与 leaf component 只消费 semantic token，
 * 避免 Palette 或辅助功能切换期间出现两个主题事实。
 */
export function ThemeProvider({ children }: PropsWithChildren): ReactElement {
  const mode = useUiPreferencesStore((state) => state.themeMode);
  const palette = useUiPreferencesStore((state) => state.palette);
  const highContrast = useUiPreferencesStore((state) => state.highContrast);
  const reduceMotion = useUiPreferencesStore((state) => state.reduceMotion);
  const reducedTransparency = useUiPreferencesStore((state) => state.reducedTransparency);
  const resolvedTheme = useResolvedTheme();

  useLayoutEffect(() => {
    // 主题副作用只消费 hook 的浏览器事实，matchMedia 订阅与 React 生命周期由共享 hook 统一管理。
    applyTheme(document.documentElement, {
      mode,
      palette,
      highContrast,
      reduceMotion,
      reducedTransparency,
      prefersDark: resolvedTheme === "dark",
    });
  }, [highContrast, mode, palette, reduceMotion, reducedTransparency, resolvedTheme]);

  return <>{children}</>;
}
