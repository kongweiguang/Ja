// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { Settings } from "./ui/Settings";
export { useSettingsController } from "./application/useSettingsController";
export type { SettingsController } from "./application/useSettingsController";
export type {
  SettingsAdapter,
  SettingsAppearancePort,
  SettingsDesktopPort,
  SettingsRuntimePort,
} from "./application/ports";
export type { DesktopNotificationPreference } from "./ui/sections";
export type { SettingsSection } from "./domain/types";
