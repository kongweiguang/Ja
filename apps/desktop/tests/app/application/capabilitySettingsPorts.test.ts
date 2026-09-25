// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { capabilitySettingsPorts } from "@/app/application/capabilitySettingsPorts";
import type { SettingsPorts } from "@/features/settings";

/** 验证浏览其它项目时写入目标仍由动作作用域决定，避免当前会话配置被项目筛选重定向。 */
describe("capabilitySettingsPorts", () => {
  it("routes global and selected-project capability mutations independently", async () => {
    const global = {
      onToggleSkill: vi.fn(async () => undefined),
      onSaveMcp: vi.fn(async () => undefined),
      onDeleteMcp: vi.fn(async () => undefined),
      onTestMcp: vi.fn(async () => "unknown" as const),
    } as unknown as SettingsPorts;
    const project = {
      onToggleSkill: vi.fn(async () => undefined),
      onSaveMcp: vi.fn(async () => undefined),
      onDeleteMcp: vi.fn(async () => undefined),
      onTestMcp: vi.fn(async () => "unknown" as const),
    } as unknown as SettingsPorts;
    const ports = capabilitySettingsPorts(global, project);

    await ports.onToggleSkill("user:review", false, "user");
    await ports.onToggleSkill("project:review", false, "project");
    await ports.onDeleteMcp("mcp_global", "user");
    await ports.onDeleteMcp("mcp_project", "project");

    expect(global.onToggleSkill).toHaveBeenCalledOnce();
    expect(global.onToggleSkill).toHaveBeenCalledWith("user:review", false, "user");
    expect(project.onToggleSkill).toHaveBeenCalledOnce();
    expect(project.onToggleSkill).toHaveBeenCalledWith("project:review", false, "project");
    expect(global.onDeleteMcp).toHaveBeenCalledWith("mcp_global", "user");
    expect(project.onDeleteMcp).toHaveBeenCalledWith("mcp_project", "project");
  });
});
