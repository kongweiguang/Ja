// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useState } from "react";
import type { SettingsSection } from "@/features/settings";

type AppView = "workspace" | "settings";

export interface PageNavigationController {
  readonly view: AppView;
  readonly settingsVisible: boolean;
  readonly settingsSection: SettingsSection;
  readonly canGoBack: boolean;
  readonly canGoForward: boolean;
  readonly setSettingsSection: (section: SettingsSection) => void;
  readonly navigate: (view: AppView) => void;
  readonly goBack: () => void;
  readonly goForward: () => void;
}

/**
 * 页面历史只记录显式的 workspace/settings 路由，不保存 runtime、workspace 或 Thread 状态；
 * 必填设置通过派生可见性覆盖页面，但不会污染 Back/Forward 历史。
 */
export function usePageNavigationController(settingsRequired: boolean): PageNavigationController {
  const [view, setView] = useState<AppView>("workspace");
  const [backStack, setBackStack] = useState<AppView[]>([]);
  const [forwardStack, setForwardStack] = useState<AppView[]>([]);
  const [settingsSection, setSettingsSection] = useState<SettingsSection>("models");

  /** 必填设置期间不记录不可见导航，避免历史栈出现可点击却不能离开的伪入口。 */
  const navigate = useCallback(
    (nextView: AppView): void => {
      if (settingsRequired || nextView === view) return;
      setBackStack((current) => [...current.slice(-19), view]);
      setForwardStack([]);
      setView(nextView);
    },
    [settingsRequired, view],
  );

  /** 必填设置保留原历史；可导航时才把当前页面压入 forward stack。 */
  const goBack = useCallback((): void => {
    if (settingsRequired) return;
    const target = backStack.at(-1);
    if (target === undefined) return;
    setBackStack(backStack.slice(0, -1));
    setForwardStack((current) => [view, ...current].slice(0, 20));
    setView(target);
  }, [backStack, settingsRequired, view]);

  /** 前进与回退遵守同一必填设置边界，不让快捷键改写暂不可见的页面历史。 */
  const goForward = useCallback((): void => {
    if (settingsRequired) return;
    const [target, ...remaining] = forwardStack;
    if (target === undefined) return;
    setForwardStack(remaining);
    setBackStack((current) => [...current.slice(-19), view]);
    setView(target);
  }, [forwardStack, settingsRequired, view]);

  return {
    view,
    settingsVisible: view === "settings" || settingsRequired,
    settingsSection,
    canGoBack: !settingsRequired && backStack.length > 0,
    canGoForward: !settingsRequired && forwardStack.length > 0,
    setSettingsSection,
    navigate,
    goBack,
    goForward,
  };
}
