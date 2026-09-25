// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { lazy, Suspense, useMemo, type ReactElement } from "react";
import type {
  DesktopNotificationPreference,
  SettingsController,
  SettingsDesktopPort,
  SettingsSection,
} from "@/features/settings";
import { capabilitySettingsPorts } from "../application/capabilitySettingsPorts";
import type { WorkspaceProjection } from "@/features/workspace";
import type { SettingsInterfacePreferences, ExecutionScope } from "@/features/settings";

/** Settings editor 及 Radix/form 依赖只在专属页面进入 bundle。 */
const LazySettings = lazy(async () => {
  const settingsModule = await import("@/features/settings");
  return { default: settingsModule.Settings };
});

export interface SettingsViewProps {
  readonly settings: SettingsController;
  readonly projectSettings: SettingsController;
  readonly projects: readonly WorkspaceProjection[];
  readonly selectedProjectId: string | undefined;
  readonly onSelectProject: (workspaceId: string) => void;
  readonly interfacePreferences: SettingsInterfacePreferences;
  readonly executionScope: ExecutionScope;
  readonly required: boolean;
  readonly section: SettingsSection;
  readonly onSectionChange: (section: SettingsSection) => void;
  readonly onReturnToApp: () => void;
  readonly desktopNotifications: DesktopNotificationPreference;
  readonly desktop: SettingsDesktopPort;
}

/**
 * Settings 使用独立 preferences surface，避免全局导航与分类导航竞争；runtime recovery
 * 仍复用唯一确认视图，不能在设置 feature 中复制生命周期决策。切换项目时只有权威快照
 * 已就绪才开放编辑，避免上一项目的占位投影被提交到新项目。
 */
export function SettingsView({
  settings,
  projectSettings,
  projects,
  selectedProjectId,
  onSelectProject,
  interfacePreferences,
  executionScope,
  required,
  section,
  onSectionChange,
  onReturnToApp,
  desktopNotifications,
  desktop,
}: SettingsViewProps): ReactElement {
  /** 作用域路由只随两端权威动作变化，避免设置视图自己构造配置快照。 */
  const ports = useMemo(
    () => capabilitySettingsPorts(settings.ports, projectSettings.ports),
    [settings.ports, projectSettings.ports],
  );
  return (
    <section className="ja-settings-view" aria-label="设置页面">
      <Suspense
        fallback={
          <section className="ja-loading-state" role="status">
            正在打开设置…
          </section>
        }
      >
        <LazySettings
          snapshot={settings.globalSnapshot}
          skillSettings={{
            ...settings.skillSettings,
            project: projectSettings.skillSettings.project,
            projectAvailable: projectSettings.skillSettings.projectAvailable,
          }}
          mcpSettings={{
            ...settings.mcpSettings,
            project: projectSettings.mcpSettings.project,
            projectAvailable: projectSettings.mcpSettings.projectAvailable,
            projectWorkspaceId: projectSettings.mcpSettings.projectWorkspaceId,
          }}
          projects={projects}
          selectedProjectId={selectedProjectId}
          onSelectProject={onSelectProject}
          projectLoading={projectSettings.loading || projectSettings.synchronizing}
          issues={[
            ...(settings.loaded?.issues ?? []).filter((issue) => issue.scope !== "project"),
            ...(projectSettings.scopeReady ? (projectSettings.loaded?.issues ?? []) : []).filter(
              (issue) => issue.scope === "project",
            ),
          ]}
          onIssuesRetry={async () => {
            await settings.reload();
            if (selectedProjectId !== undefined) await projectSettings.reload();
          }}
          onIssuesRestore={settings.restoreLastKnownGood}
          interfacePreferences={interfacePreferences}
          executionScope={executionScope}
          ports={ports}
          section={section}
          onSectionChange={onSectionChange}
          desktopNotifications={desktopNotifications}
          desktop={desktop}
          disabled={!settings.scopeReady}
          required={required}
          onReturnToApp={onReturnToApp}
        />
      </Suspense>
    </section>
  );
}
