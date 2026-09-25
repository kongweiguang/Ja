// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { SettingsPorts } from "@/features/settings";

/** 全局动作保持当前会话的配置 owner；项目动作只交给项目筛选目标的控制器。 */
export function capabilitySettingsPorts(
  global: SettingsPorts,
  project: SettingsPorts,
): SettingsPorts {
  return {
    ...global,
    onToggleSkill: (id, enabled, scope) =>
      (scope === "project" ? project : global).onToggleSkill(id, enabled, scope),
    onSaveMcp: (server, scope) => (scope === "project" ? project : global).onSaveMcp(server, scope),
    onDeleteMcp: (id, scope) => (scope === "project" ? project : global).onDeleteMcp(id, scope),
    onTestMcp: (id, scope) => (scope === "project" ? project : global).onTestMcp(id, scope),
  };
}
