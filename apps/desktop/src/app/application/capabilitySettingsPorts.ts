// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { SettingsPorts, SettingsUserConfigurationPorts } from "@/features/settings";

/** 全局配置动作固定写用户层；项目编辑动作只交给所选项目 controller。 */
export function capabilitySettingsPorts(
  activeWorkspace: SettingsPorts,
  userConfiguration: SettingsUserConfigurationPorts,
  project: SettingsPorts,
  configScope: "user" | "project" = "user",
): SettingsPorts {
  const configuration = configScope === "project" ? project : userConfiguration;
  return {
    ...activeWorkspace,
    onSaveProvider: (providerValue) => {
      if (configScope !== "project") return activeWorkspace.onSaveProvider(providerValue);
      const save = project.onSaveProjectProviderModels;
      return save === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : save(providerValue);
    },
    onDefaultSelectionChange: (selection) => configuration.onDefaultSelectionChange(selection),
    onSubagentSettingsChange: (settings) => configuration.onSubagentSettingsChange(settings),
    onAccessModeChange: (mode) => configuration.onAccessModeChange(mode),
    onClarificationEnabledChange: (enabled) => configuration.onClarificationEnabledChange(enabled),
    onInheritProjectOverride: (field) => {
      const inherit = project.onInheritProjectOverride;
      return inherit === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : inherit(field);
    },
    onProjectProviderRuntimeChange: (providerId, value) => {
      const save = project.onProjectProviderRuntimeChange;
      return save === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : save(providerId, value);
    },
    onInheritProjectProvider: (providerId) => {
      const inherit = project.onInheritProjectProvider;
      return inherit === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : inherit(providerId);
    },
    onProjectProviderConnectionChange: (providerId, value) => {
      const save = project.onProjectProviderConnectionChange;
      return save === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : save(providerId, value);
    },
    onInheritProjectProviderConnection: (providerId) => {
      const inherit = project.onInheritProjectProviderConnection;
      return inherit === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : inherit(providerId);
    },
    onSaveProjectProviderModels: (providerValue) => {
      const save = project.onSaveProjectProviderModels;
      return save === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : save(providerValue);
    },
    onInheritProjectProviderModels: (providerId) => {
      const inherit = project.onInheritProjectProviderModels;
      return inherit === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : inherit(providerId);
    },
    onInheritProjectModelOverride: (providerId, modelId) => {
      const inherit = project.onInheritProjectModelOverride;
      return inherit === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : inherit(providerId, modelId);
    },
    onHideProjectModel: (providerId, modelId) => {
      const hide = project.onHideProjectModel;
      return hide === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : hide(providerId, modelId);
    },
    onRestoreProjectModel: (providerId, modelId) => {
      const restore = project.onRestoreProjectModel;
      return restore === undefined
        ? Promise.reject(new Error("project settings unavailable"))
        : restore(providerId, modelId);
    },
    onToggleSkill: (id, enabled, scope) =>
      (scope === "project" ? project : activeWorkspace).onToggleSkill(id, enabled, scope),
    onSaveMcp: (server, scope) =>
      (scope === "project" ? project : activeWorkspace).onSaveMcp(server, scope),
    onDeleteMcp: (id, scope) =>
      (scope === "project" ? project : activeWorkspace).onDeleteMcp(id, scope),
    onTestMcp: (id, scope) =>
      (scope === "project" ? project : activeWorkspace).onTestMcp(id, scope),
  };
}
