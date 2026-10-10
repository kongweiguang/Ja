// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { Settings } from "./ui/Settings";
export { DataStorageSection } from "./ui/storage";
export type { DataStorageProps } from "./ui/storage";
export { useSettingsController } from "./application/useSettingsController";
export type { SettingsController } from "./application/useSettingsController";
export type {
  SettingsAdapter,
  SettingsAppearancePort,
  SettingsDesktopPort,
  SettingsInterfacePreferences,
  SettingsPorts,
  SettingsUserConfigurationPorts,
  SettingsRuntimePort,
} from "./application/ports";
export type { DesktopNotificationPreference } from "./ui/sections";
export type { SettingsSection, SettingsSnapshot, SkillProjection } from "./domain/types";
export type { InterfacePreferences } from "./application/interfacePreferences";
export type { ExecutionScope } from "./domain/executionScope";
