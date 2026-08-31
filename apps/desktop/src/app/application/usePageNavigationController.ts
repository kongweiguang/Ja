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

  /** 导航只提交新的可见页面，并把历史限制为二十项以避免无界 renderer 状态。 */
  const navigate = useCallback(
    (nextView: AppView): void => {
      if (nextView === view) return;
      setBackStack((current) => [...current.slice(-19), view]);
      setForwardStack([]);
      setView(nextView);
    },
    [view],
  );

  /** 回退时把当前页面压入 forward stack，页面切换不触碰领域 controller。 */
  const goBack = useCallback((): void => {
    const target = backStack.at(-1);
    if (target === undefined) return;
    setBackStack(backStack.slice(0, -1));
    setForwardStack((current) => [view, ...current].slice(0, 20));
    setView(target);
  }, [backStack, view]);

  /** 前进只重放先前回退的页面，保持与浏览器历史相同的栈语义。 */
  const goForward = useCallback((): void => {
    const [target, ...remaining] = forwardStack;
    if (target === undefined) return;
    setForwardStack(remaining);
    setBackStack((current) => [...current.slice(-19), view]);
    setView(target);
  }, [forwardStack, view]);

  return {
    view,
    settingsVisible: view === "settings" || settingsRequired,
    settingsSection,
    canGoBack: backStack.length > 0,
    canGoForward: forwardStack.length > 0,
    setSettingsSection,
    navigate,
    goBack,
    goForward,
  };
}
