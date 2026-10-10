// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { lazy, Suspense, useMemo, useState, type ReactElement } from "react";
import type {
  DesktopNotificationPreference,
  SettingsController,
  SettingsDesktopPort,
  SettingsSection,
} from "@/features/settings";
import { capabilitySettingsPorts } from "../application/capabilitySettingsPorts";
import type { WorkspaceProjection } from "@/features/workspace";
import type { SettingsInterfacePreferences, ExecutionScope } from "@/features/settings";
import type { DataStorageProps } from "@/features/settings";

/** Settings editor 及 Radix/form 依赖只在专属页面进入 bundle。 */
const LazySettings = lazy(async () => {
  const settingsModule = await import("@/features/settings");
  return { default: settingsModule.Settings };
});

export interface SettingsViewProps {
  readonly dataStorage?: DataStorageProps;
  readonly settings: SettingsController;
  readonly projectSettings: SettingsController;
  readonly projects: readonly WorkspaceProjection[];
  readonly selectedProjectId: string | undefined;
  readonly onSelectProject: (workspaceId: string) => void;
  readonly onSetProjectTrust: (
    workspaceId: string,
    trust: "trusted" | "untrusted",
  ) => Promise<void>;
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
 * 项目失效时在子树提交前收回范围选择，恢复就绪后仍要求用户显式重新选择项目。
 * 项目 trust 变更由 Workspace owner 执行，确认层等待同一配置目标回读后再关闭。
 */
export function SettingsView({
  dataStorage,
  settings,
  projectSettings,
  projects,
  selectedProjectId,
  onSelectProject,
  onSetProjectTrust,
  interfacePreferences,
  executionScope,
  required,
  section,
  onSectionChange,
  onReturnToApp,
  desktopNotifications,
  desktop,
}: SettingsViewProps): ReactElement {
  const [configScope, setConfigScope] = useState<"user" | "project">("user");
  const projectConfigAvailable =
    selectedProjectId !== undefined &&
    projectSettings.scopeReady &&
    projectSettings.skillSettings.projectAvailable;
  if (configScope === "project" && !projectConfigAvailable) {
    setConfigScope("user");
  }
  /** 全局写入绑定用户文档，项目写入绑定当前选择的可信项目 controller。 */
  const ports = useMemo(
    () =>
      capabilitySettingsPorts(
        settings.ports,
        settings.userConfigurationPorts,
        projectSettings.ports,
        configScope,
      ),
    [configScope, settings.ports, settings.userConfigurationPorts, projectSettings.ports],
  );
  const activeSettings = configScope === "project" ? projectSettings : settings;
  /** 全局栏不继承活动会话项目的 effective 来源，项目栏则显示所选项目覆盖层。 */
  const selectedScopeSources =
    configScope === "project"
      ? (projectSettings.loaded?.sources ?? {})
      : { default_provider_id: "user" as const, default_access_mode: "user" as const };
  const activeExecutionScope: ExecutionScope = {
    ...executionScope,
    scopedDefault: activeSettings.snapshot.defaultAccessMode,
    projectOverride:
      configScope === "project" && projectSettings.loaded?.projectOverrides.accessMode === true,
    workspaceKind: configScope === "project" ? "project" : "general",
  };
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
          dataStorage={dataStorage}
          snapshot={configScope === "project" ? projectSettings.snapshot : settings.globalSnapshot}
          configScope={configScope}
          onConfigScopeChange={setConfigScope}
          projectConfigAvailable={projectConfigAvailable}
          globalProviders={settings.globalSnapshot.providers}
          projectOverrides={projectSettings.loaded?.projectOverrides}
          sources={selectedScopeSources}
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
          onSetProjectTrust={onSetProjectTrust}
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
          onIssuesRepair={async (scope) => {
            await (scope === "project" ? projectSettings : settings).repairConfiguration(scope);
          }}
          interfacePreferences={interfacePreferences}
          executionScope={activeExecutionScope}
          ports={ports}
          section={section}
          onSectionChange={onSectionChange}
          desktopNotifications={desktopNotifications}
          desktop={desktop}
          disabled={configScope === "project" ? !projectSettings.scopeReady : !settings.scopeReady}
          required={required}
          onReturnToApp={onReturnToApp}
        />
      </Suspense>
    </section>
  );
}
