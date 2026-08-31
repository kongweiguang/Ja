// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { lazy, Suspense, useEffect, type ReactElement } from "react";
import type {
  DesktopNotificationPreference,
  SettingsController,
  SettingsSection,
} from "@/features/settings";
import { useRuntimeState } from "../RuntimeProvider";
import { RecoveryPanel } from "./RecoveryPanel";

/** Settings editor 及 Radix/form 依赖只在专属页面进入 bundle。 */
const LazySettings = lazy(async () => {
  const settingsModule = await import("@/features/settings");
  return { default: settingsModule.Settings };
});

export interface SettingsViewProps {
  readonly settings: SettingsController;
  readonly required: boolean;
  readonly section: SettingsSection;
  readonly onSectionChange: (section: SettingsSection) => void;
  readonly onOpenConversation: () => void;
  readonly desktopNotifications: DesktopNotificationPreference;
}

/**
 * Settings 使用独立 preferences surface，避免全局导航与分类导航竞争；runtime recovery
 * 仍复用唯一确认视图，不能在设置 feature 中复制生命周期决策。
 */
export function SettingsView({
  settings,
  required,
  section,
  onSectionChange,
  onOpenConversation,
  desktopNotifications,
}: SettingsViewProps): ReactElement {
  const { boot } = useRuntimeState();
  const { setScope } = settings;
  /** 每次重新进入设置都从全局开始；组件存活期间的切换由 controller 保留。 */
  useEffect(() => {
    setScope("global");
  }, [setScope]);
  return (
    <section className="ja-settings-view" aria-label="设置页面">
      {boot.status === "recovery_required" ? <RecoveryPanel /> : null}
      <Suspense
        fallback={
          <section className="ja-loading-state" role="status">
            正在打开设置…
          </section>
        }
      >
        <LazySettings
          snapshot={settings.snapshot}
          ports={settings.ports}
          section={section}
          onSectionChange={onSectionChange}
          desktopNotifications={desktopNotifications}
          disabled={settings.synchronizing}
          required={required}
          onOpenConversation={onOpenConversation}
          scope={settings.scope}
          projectAvailable={settings.projectAvailable}
          onScopeChange={setScope}
        />
      </Suspense>
    </section>
  );
}
