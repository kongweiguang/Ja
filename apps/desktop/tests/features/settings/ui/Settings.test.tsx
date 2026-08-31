// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Settings } from "@/features/settings/ui/Settings";
import type { SettingsPorts } from "@/features/settings/application/ports";
import type { SettingsSection, SettingsSnapshot } from "@/features/settings/domain/types";

const SNAPSHOT: SettingsSnapshot = {
  revision: 3,
  defaultSelection: {
    providerId: "provider_openai",
    modelId: "model_gpt",
    reasoningLevel: "high",
  },
  providers: [
    {
      providerId: "provider_openai",
      name: "OpenAI",
      provider: "openai",
      api: "openai_responses",
      baseUrl: "https://api.openai.com/v1",
      credentialId: "cred_openai",
      credentialConfigured: true,
      networkTimeouts: { connectTimeoutMs: 10_000, requestTimeoutMs: 120_000 },
      agentDefaults: {
        context: { autoCompact: true },
        turnLimits: { maxModelRounds: 32, maxToolCalls: 128, wallTimeoutMs: 3_600_000 },
      },
      models: [
        {
          modelId: "model_gpt",
          name: "GPT 5.6",
          model: "gpt-5.6-sol",
          capabilities: {
            contextWindowTokens: 256_000,
            maxOutputTokens: 32_000,
          },
          reasoningLevelMap: { low: "low", medium: "medium", high: "high" },
          defaultReasoningLevel: "high",
        },
        {
          modelId: "model_mini",
          name: "Mini",
          model: "gpt-mini",
          capabilities: {
            contextWindowTokens: 128_000,
            maxOutputTokens: 8_192,
          },
          reasoningLevelMap: {},
          defaultReasoningLevel: null,
        },
      ],
    },
  ],
  skills: [],
  mcpServers: [],
  defaultAccessMode: "full_access",
  globalAccessMode: "full_access",
  projectOverrides: {
    defaultSelection: false,
    accessMode: false,
    disabledSkillIds: [],
    disabledMcpIds: [],
  },
  appearance: { theme: "system", palette: "xcode", reducedMotion: false, highContrast: false },
};

afterEach(cleanup);

/** Settings 测试端口全部使用稳定 mock，未触发能力不会被假成功掩盖。 */
function ports(overrides: Partial<SettingsPorts> = {}): SettingsPorts {
  return {
    onSaveProvider: vi.fn(async () => undefined),
    onDeleteProvider: vi.fn(async () => undefined),
    onMoveProvider: vi.fn(async () => undefined),
    onSaveModel: vi.fn(async () => undefined),
    onTestModel: vi.fn(async () => ({ responseModel: "gpt-test", latencyMs: 12 })),
    onDeleteModel: vi.fn(async () => undefined),
    onMoveModel: vi.fn(async () => undefined),
    onDefaultSelectionChange: vi.fn(async () => undefined),
    onRestoreDefaultSelection: vi.fn(async () => undefined),
    onReplaceCredential: vi.fn(async () => undefined),
    onClearCredential: vi.fn(async () => undefined),
    onSaveMcp: vi.fn(async () => undefined),
    onDeleteMcp: vi.fn(async () => undefined),
    onTestMcp: vi.fn(async () => "connected" as const),
    onCloseMcp: vi.fn(async () => undefined),
    onToggleSkill: vi.fn(async () => undefined),
    onAccessModeChange: vi.fn(async () => undefined),
    onAppearanceChange: vi.fn(async () => undefined),
    onResetProject: vi.fn(async () => undefined),
    ...overrides,
  };
}

/** 受控分类由 Shell owner 持有，测试 wrapper 只固定当前页。 */
function renderSettings(
  section: SettingsSection,
  settingsPorts = ports(),
  snapshot = SNAPSHOT,
): void {
  render(
    <Settings
      snapshot={snapshot}
      ports={settingsPorts}
      section={section}
      onSectionChange={vi.fn()}
    />,
  );
}

describe("Settings v4 UI", () => {
  it("keeps Provider connection fields in a dialog while the main list stays compact", async () => {
    const user = userEvent.setup();
    renderSettings("models");
    expect(screen.getAllByText("OpenAI").length).toBeGreaterThan(0);
    expect(screen.getByText("GPT 5.6")).toBeDefined();
    expect(screen.getByText("Mini")).toBeDefined();
    expect(screen.getByRole("button", { name: "当前默认" })).toHaveProperty("disabled", true);
    expect(screen.queryByLabelText("API key / token")).toBeNull();
    expect(screen.queryByLabelText("Base URL")).toBeNull();

    await user.click(screen.getByRole("button", { name: "编辑" }));
    expect(screen.getByText("已配置 · 密钥不会回显")).toBeDefined();
    const credentialInput = screen.getByLabelText("API key / token");
    expect(credentialInput.id).not.toBe("credential-vault-secret");
    expect(credentialInput.getAttribute("aria-describedby")).toContain("credential-status");
  });

  it("calls the paid model probe only after explicit confirmation", async () => {
    const user = userEvent.setup();
    const testModel = vi.fn(async () => ({ responseModel: "gpt-test", latencyMs: 12 }));
    renderSettings("models", ports({ onTestModel: testModel }));

    await user.click(screen.getAllByRole("button", { name: "验证模型" })[0]!);
    expect(testModel).not.toHaveBeenCalled();
    expect(screen.getByText(/可能产生少量费用/)).toBeDefined();
    await user.click(screen.getByRole("button", { name: "验证" }));
    await waitFor(() => expect(testModel).toHaveBeenCalledWith("provider_openai", "model_gpt"));
  });

  it("does not expose an MCP protocol field that is not persisted", async () => {
    const user = userEvent.setup();
    renderSettings("mcp");
    await user.click(screen.getByRole("button", { name: "新增 Server" }));
    expect(screen.queryByLabelText("Protocol version")).toBe(null);
    expect(screen.getByLabelText("认证")).toBeDefined();
    expect(screen.queryByLabelText("Credential ref")).toBe(null);
  });

  it("completes MCP edit, enable, test, close, and delete actions through real ports", async () => {
    const user = userEvent.setup();
    const onSaveMcp = vi.fn(async () => undefined);
    const onTestMcp = vi.fn(async () => "connected" as const);
    const onCloseMcp = vi.fn(async () => undefined);
    const onDeleteMcp = vi.fn(async () => undefined);
    const server = {
      id: "mcp_local",
      mcpRevision: "mcp_local",
      name: "Local Tools",
      transport: "stdio" as const,
      endpoint: "pwsh.exe",
      protocolVersion: "2025-06-18" as const,
      args: ["-File", "server.ps1"],
      env: {},
      headers: {},
      auth: { kind: "none" as const },
      enabled: false,
      status: "disabled" as const,
      tools: [],
      globallyEnabled: false,
      projectOverridden: false,
    };
    renderSettings("mcp", ports({ onSaveMcp, onTestMcp, onCloseMcp, onDeleteMcp }), {
      ...SNAPSHOT,
      mcpServers: [server],
    });

    await user.click(screen.getByRole("switch", { name: "已停用" }));
    await waitFor(() =>
      expect(onSaveMcp).toHaveBeenCalledWith(expect.objectContaining({ enabled: true })),
    );
    await user.click(screen.getByRole("button", { name: "测试" }));
    await waitFor(() => expect(onTestMcp).toHaveBeenCalledWith("mcp_local"));

    await user.click(screen.getByRole("button", { name: "Local Tools 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "编辑" }));
    expect(screen.getByRole("dialog", { name: "编辑 Local Tools" })).toBeDefined();
    await user.clear(screen.getByLabelText("Server 名称"));
    await user.type(screen.getByLabelText("Server 名称"), "Local Tools 2");
    await user.click(screen.getByRole("button", { name: "保存 Server" }));
    await waitFor(() =>
      expect(onSaveMcp).toHaveBeenLastCalledWith(
        expect.objectContaining({ mcpRevision: "mcp_local", name: "Local Tools 2" }),
      ),
    );

    await user.click(screen.getByRole("button", { name: "Local Tools 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "关闭连接" }));
    await waitFor(() => expect(onCloseMcp).toHaveBeenCalledWith("mcp_local"));
    await user.click(screen.getByRole("button", { name: "Local Tools 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "删除" }));
    await user.click(screen.getByRole("button", { name: "删除" }));
    await waitFor(() => expect(onDeleteMcp).toHaveBeenCalledWith("mcp_local"));
  });

  it("restores project Skill and MCP inheritance without exposing global editors", async () => {
    const user = userEvent.setup();
    const onToggleSkill = vi.fn(async () => undefined);
    const projectSnapshot: SettingsSnapshot = {
      ...SNAPSHOT,
      skills: [
        {
          id: "skill_review",
          name: "Review",
          source: "builtin",
          description: "Review changes",
          enabled: false,
          status: "disabled",
          globallyEnabled: true,
          projectOverridden: true,
        },
      ],
      mcpServers: [
        {
          id: "mcp_local",
          mcpRevision: "mcp_local",
          name: "Local Tools",
          transport: "stdio",
          endpoint: "pwsh.exe",
          protocolVersion: "2025-06-18",
          args: [],
          env: {},
          headers: {},
          auth: { kind: "none" },
          enabled: false,
          status: "disabled",
          tools: [],
          globallyEnabled: true,
          projectOverridden: true,
        },
      ],
      projectOverrides: {
        ...SNAPSHOT.projectOverrides,
        disabledSkillIds: ["skill_review"],
        disabledMcpIds: ["mcp_local"],
      },
    };
    render(
      <Settings
        snapshot={projectSnapshot}
        ports={ports({ onToggleSkill })}
        section="skills"
        onSectionChange={vi.fn()}
        scope="project"
        projectAvailable
      />,
    );
    expect(screen.queryByRole("button", { name: "新增 Provider" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "恢复继承" }));
    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("skill_review", true));

    cleanup();
    const onSaveMcp = vi.fn(async () => undefined);
    render(
      <Settings
        snapshot={projectSnapshot}
        ports={ports({ onSaveMcp })}
        section="mcp"
        onSectionChange={vi.fn()}
        scope="project"
        projectAvailable
      />,
    );
    expect(screen.queryByRole("button", { name: "新增 Server" })).toBeNull();
    expect(screen.queryByRole("button", { name: "测试" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "恢复继承" }));
    await waitFor(() =>
      expect(onSaveMcp).toHaveBeenCalledWith(
        expect.objectContaining({ mcpRevision: "mcp_local", enabled: true }),
      ),
    );
  });

  it("adds a model through the Provider-scoped model action", async () => {
    const user = userEvent.setup();
    const onSaveModel = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveModel }));
    await user.click(screen.getByRole("button", { name: "添加模型" }));
    await user.type(screen.getAllByLabelText("模型名称")[0]!, "Reasoner");
    await user.type(screen.getAllByLabelText("模型标识")[0]!, "reasoner-v1");
    await user.click(screen.getAllByRole("button", { name: "添加模型" })[0]!);
    await waitFor(() =>
      expect(onSaveModel).toHaveBeenCalledWith(
        "provider_openai",
        expect.objectContaining({
          name: "Reasoner",
          model: "reasoner-v1",
          capabilities: expect.objectContaining({ contextWindowTokens: 128_000 }),
        }),
      ),
    );
  });

  it("keeps the Provider draft open when the save port rejects it", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => Promise.reject(new Error("CAS conflict")));
    renderSettings("models", ports({ onSaveProvider }));

    await user.click(screen.getByRole("button", { name: "编辑" }));
    const name = screen.getByLabelText("服务商名称");
    await user.clear(name);
    await user.type(name, "OpenAI 项目草稿");
    await user.click(screen.getByRole("button", { name: "保存 Provider" }));

    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("dialog", { name: "编辑 Provider" })).toBeDefined();
    expect(screen.getByLabelText("服务商名称")).toHaveProperty("value", "OpenAI 项目草稿");
  });

  it("edits the full reasoning map without exposing renderer-owned input modalities", async () => {
    const user = userEvent.setup();
    const onSaveModel = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveModel }));
    await user.click(screen.getByRole("button", { name: /GPT 5.6/ }));
    expect(screen.queryByRole("checkbox", { name: "PDF" })).toBeNull();
    await user.click(screen.getByRole("checkbox", { name: "最少" }));
    await user.click(screen.getByRole("button", { name: "保存模型能力" }));
    await waitFor(() =>
      expect(onSaveModel).toHaveBeenCalledWith(
        "provider_openai",
        expect.objectContaining({
          modelId: "model_gpt",
          capabilities: expect.objectContaining({ contextWindowTokens: 256_000 }),
          reasoningLevelMap: { minimal: "minimal", low: "low", medium: "medium", high: "high" },
          defaultReasoningLevel: "high",
        }),
      ),
    );
  });

  it("changes the root default access mode through the real port", async () => {
    const onAccessModeChange = vi.fn(async () => undefined);
    renderSettings("permissions", ports({ onAccessModeChange }));
    fireEvent.click(screen.getByRole("radio", { name: /需要确认/ }));
    await waitFor(() => expect(onAccessModeChange).toHaveBeenCalledWith("approval_required"));
  });

  it("uses the shared Select surface and preserves Field ARIA wiring", async () => {
    const user = userEvent.setup();
    renderSettings("appearance");
    const themeSelect = screen.getByRole("combobox", { name: "主题" });
    expect(themeSelect.classList.contains("ja-select-trigger")).toBe(true);
    expect(themeSelect.getAttribute("aria-describedby")).toBe("appearance-theme-hint");
    await user.click(themeSelect);
    expect(document.querySelector(".ja-select-content") !== null).toBe(true);
    expect(document.querySelector(".ja-settings-select-content")).toBe(null);
  });

  it("searches nested model names without indexing credential secrets", async () => {
    const user = userEvent.setup();
    renderSettings("models");
    await user.type(screen.getByRole("textbox", { name: "搜索设置" }), "gpt-mini");
    expect(screen.getByRole("option", { name: /Mini OpenAI · gpt-mini/ })).toBeDefined();
    expect(screen.queryByRole("tab", { name: "模型" })).toBeNull();
  });

  it("opens a search result by stable setting identity and focuses its real control", async () => {
    const user = userEvent.setup();
    const onSectionChange = vi.fn();
    render(
      <Settings
        snapshot={SNAPSHOT}
        ports={ports()}
        section="models"
        onSectionChange={onSectionChange}
      />,
    );

    await user.type(screen.getByRole("textbox", { name: "搜索设置" }), "gpt-mini");
    await user.click(screen.getByRole("option", { name: /Mini OpenAI · gpt-mini/ }));

    expect(onSectionChange).toHaveBeenCalledWith("models");
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole("button", { name: /Mini/ })),
    );
  });

  it("uses instant search positioning when reduced motion is enabled", async () => {
    const user = userEvent.setup();
    const scrollIntoView = vi.spyOn(HTMLElement.prototype, "scrollIntoView");
    scrollIntoView.mockClear();
    render(
      <Settings
        snapshot={{
          ...SNAPSHOT,
          appearance: { ...SNAPSHOT.appearance, reducedMotion: true },
        }}
        ports={ports()}
        section="models"
        onSectionChange={vi.fn()}
      />,
    );

    await user.type(screen.getByRole("textbox", { name: "搜索设置" }), "gpt-mini");
    await user.click(screen.getByRole("option", { name: /Mini OpenAI · gpt-mini/ }));

    await waitFor(() =>
      expect(scrollIntoView).toHaveBeenCalledWith({ block: "center", behavior: "auto" }),
    );
  });

  it("shows project inheritance recovery and disables permission expansion", async () => {
    const restore = vi.fn(async () => undefined);
    render(
      <Settings
        snapshot={{
          ...SNAPSHOT,
          defaultAccessMode: "approval_required",
          globalAccessMode: "approval_required",
          projectOverrides: {
            ...SNAPSHOT.projectOverrides,
            defaultSelection: true,
            accessMode: true,
          },
        }}
        ports={ports({ onRestoreDefaultSelection: restore })}
        section="models"
        onSectionChange={vi.fn()}
        scope="project"
        projectAvailable
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "恢复继承" }));
    await waitFor(() => expect(restore).toHaveBeenCalledTimes(1));

    cleanup();
    render(
      <Settings
        snapshot={{
          ...SNAPSHOT,
          defaultAccessMode: "approval_required",
          globalAccessMode: "approval_required",
          projectOverrides: { ...SNAPSHOT.projectOverrides, accessMode: true },
        }}
        ports={ports()}
        section="permissions"
        onSectionChange={vi.fn()}
        scope="project"
        projectAvailable
      />,
    );
    expect(screen.getByRole("radio", { name: /全部执行/ })).toHaveProperty("disabled", true);
    expect(screen.getByText(/不能扩大权限/)).toBeDefined();
  });
});
