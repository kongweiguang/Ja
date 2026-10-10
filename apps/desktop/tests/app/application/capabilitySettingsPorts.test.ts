// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { capabilitySettingsPorts } from "@/app/application/capabilitySettingsPorts";
import type { SettingsPorts, SettingsUserConfigurationPorts } from "@/features/settings";

/** 验证浏览其它项目时写入目标仍由动作作用域决定，避免当前会话配置被项目筛选重定向。 */
describe("capabilitySettingsPorts", () => {
  /** 项目模型保存必须到项目补丁端口，防止组合根误写全局 Provider 或凭据。 */
  it("routes project model saves without invoking the global provider writer", async () => {
    const global = {
      onSaveProvider: vi.fn(async () => undefined),
    } as unknown as SettingsPorts;
    const userConfiguration = {} as SettingsUserConfigurationPorts;
    const project = {
      onSaveProjectProviderModels: vi.fn(async () => undefined),
    } as unknown as SettingsPorts;
    const provider = { providerId: "provider_project" } as Parameters<
      SettingsPorts["onSaveProvider"]
    >[0];

    await capabilitySettingsPorts(global, userConfiguration, project, "project").onSaveProvider(
      provider,
    );

    expect(project.onSaveProjectProviderModels).toHaveBeenCalledWith(provider);
    expect(global.onSaveProvider).not.toHaveBeenCalled();
  });

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
    const userConfiguration = {} as SettingsUserConfigurationPorts;
    const ports = capabilitySettingsPorts(global, userConfiguration, project);

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

  /** 四个共享设置字段必须由被选配置层 owner 接收，不能取活动 Workspace ports。 */
  it("routes model, access, subagent, and clarification writes to the selected owner", async () => {
    const activeWorkspace = {
      onDefaultSelectionChange: vi.fn(async () => undefined),
      onSubagentSettingsChange: vi.fn(async () => undefined),
      onAccessModeChange: vi.fn(async () => undefined),
      onClarificationEnabledChange: vi.fn(async () => undefined),
    } as unknown as SettingsPorts;
    const userConfiguration: SettingsUserConfigurationPorts = {
      onDefaultSelectionChange: vi.fn(async () => undefined),
      onSubagentSettingsChange: vi.fn(async () => undefined),
      onAccessModeChange: vi.fn(async () => undefined),
      onClarificationEnabledChange: vi.fn(async () => undefined),
    };
    const project = {
      onDefaultSelectionChange: vi.fn(async () => undefined),
      onSubagentSettingsChange: vi.fn(async () => undefined),
      onAccessModeChange: vi.fn(async () => undefined),
      onClarificationEnabledChange: vi.fn(async () => undefined),
    } as unknown as SettingsPorts;
    const selection = { providerId: "provider_one", modelId: "model_one", reasoningLevel: null };
    const subagents = {
      enabled: true,
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: null,
    };

    const globalPorts = capabilitySettingsPorts(
      activeWorkspace,
      userConfiguration,
      project,
      "user",
    );
    await globalPorts.onDefaultSelectionChange(selection);
    await globalPorts.onSubagentSettingsChange(subagents);
    await globalPorts.onAccessModeChange("approval_required");
    await globalPorts.onClarificationEnabledChange(false);

    expect(userConfiguration.onDefaultSelectionChange).toHaveBeenCalledExactlyOnceWith(selection);
    expect(userConfiguration.onSubagentSettingsChange).toHaveBeenCalledExactlyOnceWith(subagents);
    expect(userConfiguration.onAccessModeChange).toHaveBeenCalledExactlyOnceWith(
      "approval_required",
    );
    expect(userConfiguration.onClarificationEnabledChange).toHaveBeenCalledExactlyOnceWith(false);
    expect(activeWorkspace.onAccessModeChange).not.toHaveBeenCalled();

    const projectPorts = capabilitySettingsPorts(
      activeWorkspace,
      userConfiguration,
      project,
      "project",
    );
    await projectPorts.onDefaultSelectionChange(selection);
    await projectPorts.onSubagentSettingsChange(subagents);
    await projectPorts.onAccessModeChange("full_access");
    await projectPorts.onClarificationEnabledChange(true);

    expect(project.onDefaultSelectionChange).toHaveBeenCalledExactlyOnceWith(selection);
    expect(project.onSubagentSettingsChange).toHaveBeenCalledExactlyOnceWith(subagents);
    expect(project.onAccessModeChange).toHaveBeenCalledExactlyOnceWith("full_access");
    expect(project.onClarificationEnabledChange).toHaveBeenCalledExactlyOnceWith(true);
    expect(activeWorkspace.onDefaultSelectionChange).not.toHaveBeenCalled();
    expect(activeWorkspace.onSubagentSettingsChange).not.toHaveBeenCalled();
  });
});
