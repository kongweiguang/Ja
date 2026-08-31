// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useLayoutEffect, type PropsWithChildren, type ReactElement } from "react";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import { useMediaQuery } from "@/shared/hooks/useMediaQuery";
import { applyTheme } from "@/shared/styles/theme";

/**
 * Provider 独占 document-level theme side effect，使 leaf component 只消费 semantic CSS token。
 */
export function ThemeProvider({ children }: PropsWithChildren): ReactElement {
  const mode = useUiPreferencesStore((state) => state.themeMode);
  const palette = useUiPreferencesStore((state) => state.palette);
  const highContrast = useUiPreferencesStore((state) => state.highContrast);
  const reduceMotion = useUiPreferencesStore((state) => state.reduceMotion);
  const prefersDark = useMediaQuery("(prefers-color-scheme: dark)");

  useLayoutEffect(() => {
    // 主题副作用只消费 hook 的浏览器事实，matchMedia 订阅与 React 生命周期由共享 hook 统一管理。
    applyTheme(document.documentElement, {
      mode,
      palette,
      highContrast,
      reduceMotion,
      prefersDark,
    });
  }, [highContrast, mode, palette, prefersDark, reduceMotion]);

  return <>{children}</>;
}
