// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Settings } from "@/features/settings/ui/Settings";
import type { SettingsDesktopPort, SettingsPorts } from "@/features/settings/application/ports";
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
  appearance: {
    theme: "system",
    palette: "xcode",
    reducedMotion: false,
    reducedTransparency: false,
    highContrast: false,
  },
};

afterEach(cleanup);

/** Settings 测试端口全部使用稳定 mock，未触发能力不会被假成功掩盖。 */
function ports(overrides: Partial<SettingsPorts> = {}): SettingsPorts {
  return {
    onCreateProvider: vi.fn(async () => undefined),
    onSaveProvider: vi.fn(async () => undefined),
    onDeleteProvider: vi.fn(async () => undefined),
    onMoveProvider: vi.fn(async () => undefined),
    onSaveModel: vi.fn(async () => undefined),
    onTestModel: vi.fn(async () => ({ responseModel: "gpt-test", latencyMs: 12 })),
    onDeleteModel: vi.fn(async () => undefined),
    onMoveModel: vi.fn(async () => undefined),
    onDefaultSelectionChange: vi.fn(async () => undefined),
    onReplaceCredential: vi.fn(async () => undefined),
    onClearCredential: vi.fn(async () => undefined),
    onSaveMcp: vi.fn(async () => undefined),
    onDeleteMcp: vi.fn(async () => undefined),
    onTestMcp: vi.fn(async () => "connected" as const),
    onCloseMcp: vi.fn(async () => undefined),
    onToggleSkill: vi.fn(async () => undefined),
    onAccessModeChange: vi.fn(async () => undefined),
    onAppearanceChange: vi.fn(async () => undefined),
    ...overrides,
  };
}

/** 设置页桌面端口默认投影为无更新，避免 UI 单测触发真实插件或网络。 */
function desktop(): SettingsDesktopPort {
  return {
    openExternalUrl: vi.fn(async () => undefined),
    checkForUpdate: vi.fn(async () => ({ kind: "up-to-date" as const })),
    installUpdate: vi.fn(async () => undefined),
    relaunchAfterUpdate: vi.fn(async () => undefined),
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
      desktop={desktop()}
      section={section}
      onSectionChange={vi.fn()}
    />,
  );
}

describe("Settings v1 UI", () => {
  /** 四个真实来源始终占据稳定位置，空目录不会让页面结构在刷新时跳动。 */
  it("groups discovered Skills by built-in, user, Ja, and project source", async () => {
    const onToggleSkill = vi.fn(async () => undefined);
    renderSettings("skills", ports({ onToggleSkill }), {
      ...SNAPSHOT,
      skills: [
        {
          id: "skill_review",
          name: "review",
          source: "user",
          description: "Review changes",
          enabled: false,
          status: "disabled",
        },
        {
          id: "skill_ja_tools",
          name: "ja-tools",
          source: "ja",
          description: "Ja tools",
          enabled: true,
          status: "ready",
        },
        {
          id: "skill_project_rules",
          name: "project-rules",
          source: "project",
          description: "Project rules",
          enabled: true,
          status: "ready",
        },
      ],
    });

    for (const heading of ["内置", "用户", "Ja", "项目"]) {
      expect(screen.getByRole("heading", { name: heading })).toBeDefined();
    }
    expect(screen.getByText("随 Ja 提供")).toBeDefined();
    expect(screen.getByText("~/.agents/skills")).toBeDefined();
    expect(screen.getByText("~/.ja/skills")).toBeDefined();
    expect(screen.getByText(".agents/skills")).toBeDefined();
    expect(screen.getByText("暂无 Skills")).toBeDefined();

    await userEvent.click(screen.getByRole("switch", { name: "已停用" }));
    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("skill_review", true));
  });

  it("shows one return action without duplicate heading, scope, or conversation controls", async () => {
    const user = userEvent.setup();
    const onReturnToApp = vi.fn();
    render(
      <Settings
        snapshot={SNAPSHOT}
        ports={ports()}
        desktop={desktop()}
        section="models"
        onSectionChange={vi.fn()}
        onReturnToApp={onReturnToApp}
      />,
    );

    expect(screen.getAllByRole("button", { name: "返回应用" })).toHaveLength(1);
    expect(screen.getByRole("textbox", { name: "搜索设置" })).toBeDefined();
    expect(screen.getAllByRole("tab")).toHaveLength(6);
    expect(screen.getByRole("tab", { name: "关于" })).toBeDefined();
    expect(screen.queryByText("Ja 偏好设置")).toBeNull();
    expect(screen.queryByRole("group", { name: "设置作用域" })).toBeNull();
    expect(screen.queryByRole("button", { name: "开始对话" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "返回应用" }));
    expect(onReturnToApp).toHaveBeenCalledTimes(1);
  });

  it("hides the return action while initial model configuration is required", () => {
    render(
      <Settings
        snapshot={SNAPSHOT}
        ports={ports()}
        desktop={desktop()}
        section="models"
        onSectionChange={vi.fn()}
        required
        onReturnToApp={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: "返回应用" })).toBeNull();
    expect(screen.queryByRole("button", { name: "开始对话" })).toBeNull();
  });

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

  it("edits the API specification without a separate provider-brand selector", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveProvider }));
    await user.click(screen.getByRole("button", { name: "编辑" }));

    expect(screen.queryByLabelText("服务商")).toBeNull();
    await user.click(screen.getByLabelText("API 规范"));
    await user.click(screen.getByRole("option", { name: "Anthropic Messages" }));
    await user.click(screen.getByRole("button", { name: "保存 Provider" }));
    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledTimes(1));
    expect(onSaveProvider).toHaveBeenCalledWith(
      expect.objectContaining({ api: "anthropic_messages" }),
    );
  });

  it("creates a custom DeepSeek supplier with its own credential and any API specification", async () => {
    const user = userEvent.setup();
    const onCreateProvider = vi.fn<SettingsPorts["onCreateProvider"]>(async (provider) => {
      expect(provider.credentialId).toMatch(/^cred_/);
    });
    const onSaveProvider = vi.fn<SettingsPorts["onSaveProvider"]>(async () => undefined);
    renderSettings("models", ports({ onCreateProvider, onSaveProvider }));
    await user.click(screen.getByRole("button", { name: "新增 Provider" }));
    const dialog = screen.getByRole("dialog", { name: "新增 Provider" });
    const secretInput = within(dialog).getByLabelText("API key / token");
    expect(secretInput.getAttribute("type")).toBe("password");
    expect(secretInput.getAttribute("autocomplete")).toBe("new-password");
    await user.type(screen.getByLabelText("服务商名称"), "DeepSeek");
    await user.type(screen.getByLabelText("Base URL"), "https://api.deepseek.com");

    await user.click(screen.getByLabelText("API 规范"));
    expect(screen.getByRole("option", { name: "Anthropic Messages" })).toBeDefined();
    expect(screen.getByRole("option", { name: "OpenAI Chat Completions" })).toBeDefined();
    expect(screen.getByRole("option", { name: "OpenAI Responses" })).toBeDefined();
    await user.click(screen.getByRole("option", { name: "OpenAI Chat Completions" }));
    await user.type(screen.getByLabelText("首个模型名称"), "DeepSeek Chat");
    await user.type(screen.getByLabelText("上游模型"), "deepseek-chat");
    await user.type(secretInput, "deepseek-test-secret");
    await user.click(screen.getByRole("button", { name: "保存 Provider" }));

    await waitFor(() => expect(onCreateProvider).toHaveBeenCalledTimes(1));
    expect(onCreateProvider).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "DeepSeek",
        api: "openai_chat_completions",
        baseUrl: "https://api.deepseek.com",
        credentialId: expect.stringMatching(/^cred_/),
      }),
      "deepseek-test-secret",
    );
    expect(onCreateProvider.mock.calls[0]![0].credentialId).not.toBe("cred_openai");
    expect(onSaveProvider).not.toHaveBeenCalled();
  });

  it("keeps stable Provider and model identities when credential persistence is retried", async () => {
    const user = userEvent.setup();
    const failure = Object.assign(new Error("redacted"), {
      code: "provider_saved_credential_failed",
    });
    const onCreateProvider = vi
      .fn<SettingsPorts["onCreateProvider"]>()
      .mockRejectedValueOnce(failure)
      .mockResolvedValueOnce(undefined);
    renderSettings("models", ports({ onCreateProvider }));
    await user.click(screen.getByRole("button", { name: "新增 Provider" }));
    const dialog = screen.getByRole("dialog", { name: "新增 Provider" });
    await user.type(within(dialog).getByLabelText("服务商名称"), "DeepSeek");
    await user.type(within(dialog).getByLabelText("Base URL"), "https://api.deepseek.com");
    await user.type(within(dialog).getByLabelText("首个模型名称"), "DeepSeek Chat");
    await user.type(within(dialog).getByLabelText("上游模型"), "deepseek-chat");
    const secretInput = within(dialog).getByLabelText("API key / token");
    await user.type(secretInput, "first-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存 Provider" }));

    await waitFor(() => expect(onCreateProvider).toHaveBeenCalledTimes(1));
    expect(secretInput).toHaveProperty("value", "");
    expect(
      within(dialog).getByText("Provider 已保存，但密钥保存失败，请重新输入后重试"),
    ).toBeDefined();
    const firstProvider = onCreateProvider.mock.calls[0]![0];
    await user.type(secretInput, "second-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存 Provider" }));

    await waitFor(() => expect(onCreateProvider).toHaveBeenCalledTimes(2));
    expect(onCreateProvider.mock.calls[1]![0].providerId).toBe(firstProvider.providerId);
    expect(onCreateProvider.mock.calls[1]![0].models[0]?.modelId).toBe(
      firstProvider.models[0]?.modelId,
    );
    expect(onCreateProvider.mock.calls[1]![1]).toBe("second-secret");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "新增 Provider" })).toBeNull());
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
    const themeSelect = screen.getByRole("combobox", { name: "外观模式" });
    expect(themeSelect.classList.contains("ja-select-trigger")).toBe(true);
    expect(themeSelect.getAttribute("aria-describedby")).toBe("appearance-theme-hint");
    await user.click(themeSelect);
    expect(document.querySelector(".ja-select-content") !== null).toBe(true);
    expect(document.querySelector(".ja-settings-select-content")).toBe(null);
  });

  it("offers four keyboard-selectable palettes with decorative color previews", async () => {
    const user = userEvent.setup();
    const onAppearanceChange = vi.fn(async () => undefined);
    renderSettings("appearance", ports({ onAppearanceChange }));
    const paletteSelect = screen.getByRole("combobox", { name: "配色主题" });
    expect(paletteSelect.getAttribute("aria-describedby")).toBe("appearance-palette-hint");

    await user.click(paletteSelect);
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Xcode",
      "Fleet",
      "Obsidian",
      "Claude",
    ]);
    expect(document.querySelectorAll(".ja-settings-palette-swatch")).toHaveLength(15);

    await user.keyboard("{ArrowDown}{Enter}");
    await waitFor(() =>
      expect(onAppearanceChange).toHaveBeenCalledWith(
        expect.objectContaining({ palette: "fleet" }),
        "palette",
      ),
    );
    expect(document.activeElement).toBe(paletteSelect);
  });

  it("reports persistence failure without claiming the applied session theme was rolled back", async () => {
    const user = userEvent.setup();
    renderSettings(
      "appearance",
      ports({ onAppearanceChange: vi.fn(async () => Promise.reject(new Error("quota"))) }),
    );

    await user.click(screen.getByRole("combobox", { name: "配色主题" }));
    await user.click(screen.getByRole("option", { name: "Claude" }));

    expect((await screen.findByRole("status")).textContent).toContain("已应用但未保存");
    expect(screen.queryByText(/仍保留上一次设置/)).toBeNull();
  });

  it("identifies the changed appearance field so stale sibling values are not persisted", async () => {
    const user = userEvent.setup();
    const onAppearanceChange = vi.fn(async () => undefined);
    renderSettings("appearance", ports({ onAppearanceChange }));

    await user.click(screen.getByRole("combobox", { name: "外观模式" }));
    await user.click(screen.getByRole("option", { name: "深色" }));
    await waitFor(() =>
      expect(onAppearanceChange).toHaveBeenNthCalledWith(
        1,
        expect.objectContaining({ theme: "dark" }),
        "theme",
      ),
    );

    await user.click(screen.getByRole("combobox", { name: "配色主题" }));
    await user.click(screen.getByRole("option", { name: "Obsidian" }));
    await waitFor(() =>
      expect(onAppearanceChange).toHaveBeenNthCalledWith(
        2,
        expect.objectContaining({ palette: "obsidian" }),
        "palette",
      ),
    );

    await user.click(screen.getByRole("switch", { name: "减少动效" }));
    await waitFor(() =>
      expect(onAppearanceChange).toHaveBeenNthCalledWith(
        3,
        expect.objectContaining({ reducedMotion: true }),
        "reducedMotion",
      ),
    );

    await user.click(screen.getByRole("switch", { name: "降低透明度" }));
    await waitFor(() =>
      expect(onAppearanceChange).toHaveBeenNthCalledWith(
        4,
        expect.objectContaining({ reducedTransparency: true }),
        "reducedTransparency",
      ),
    );

    await user.click(screen.getByRole("switch", { name: "提高对比度" }));
    await waitFor(() =>
      expect(onAppearanceChange).toHaveBeenNthCalledWith(
        5,
        expect.objectContaining({ highContrast: true }),
        "highContrast",
      ),
    );
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
        desktop={desktop()}
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
        desktop={desktop()}
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
});
