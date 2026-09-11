// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { Settings } from "./ui/Settings";
export { useSettingsController } from "./application/useSettingsController";
export type { SettingsController } from "./application/useSettingsController";
export type {
  SettingsAdapter,
  SettingsAppearancePort,
  SettingsDesktopPort,
  SettingsInterfacePreferences,
  SettingsPorts,
  SettingsRuntimePort,
} from "./application/ports";
export type { DesktopNotificationPreference } from "./ui/sections";
export type { SettingsSection, SettingsSnapshot } from "./domain/types";
export type { InterfacePreferences } from "./application/interfacePreferences";
export type { ExecutionScope } from "./domain/executionScope";
