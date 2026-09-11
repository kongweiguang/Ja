// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Settings } from "@/features/settings/ui/Settings";
import type { SettingsDesktopPort, SettingsPorts } from "@/features/settings/application/ports";
import type { SettingsInterfacePreferences } from "@/features/settings/application/ports";
import type { SettingsSection, SettingsSnapshot } from "@/features/settings/domain/types";

const SNAPSHOT: SettingsSnapshot = {
  revision: 3,
  defaultSelection: {
    providerId: "provider_openai",
    modelId: "model_gpt",
    reasoningLevel: "high",
  },
  subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
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
  clarificationEnabled: true,
  appearance: {
    theme: "system",
    palette: "xcode",
    reducedMotion: false,
    reducedTransparency: false,
    highContrast: false,
  },
};

const INTERFACE_PREFERENCES: SettingsInterfacePreferences = {
  sendShortcut: "enter",
  uiFontSize: 16,
  codeFontSize: 13,
  onChange: vi.fn(async () => undefined),
};

const EXECUTION_SCOPE = {
  scopedDefault: "full_access" as const,
  projectOverride: false,
  scopeReady: true,
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
    onSubagentSettingsChange: vi.fn(async () => undefined),
    onReplaceCredential: vi.fn(async () => undefined),
    onClearCredential: vi.fn(async () => undefined),
    onSaveMcp: vi.fn(async () => undefined),
    onDeleteMcp: vi.fn(async () => undefined),
    onTestMcp: vi.fn(async () => "connected" as const),
    onCloseMcp: vi.fn(async () => undefined),
    onToggleSkill: vi.fn(async () => undefined),
    onAccessModeChange: vi.fn(async () => undefined),
    onClarificationEnabledChange: vi.fn(async () => undefined),
    onAppearanceChange: vi.fn(async () => undefined),
    ...overrides,
  };
}

/** 设置页桌面端口默认投影为无更新，避免 UI 单测触发真实插件或网络。 */
function desktop(): SettingsDesktopPort {
  return {
    readCloseBehavior: vi.fn(async () => "background" as const),
    saveCloseBehavior: vi.fn(async () => undefined),
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
      interfacePreferences={INTERFACE_PREFERENCES}
      executionScope={EXECUTION_SCOPE}
      snapshot={snapshot}
      ports={settingsPorts}
      desktop={desktop()}
      section={section}
      onSectionChange={vi.fn()}
    />,
  );
}

describe("Settings v1 UI", () => {
  /** 搜索需要先切换供应商再聚焦真实模型；同一搜索再次执行也必须生效。 */
  it("selects an unselected provider when opening its model search result", async () => {
    const user = userEvent.setup();
    const extraProvider = {
      ...SNAPSHOT.providers[0]!,
      providerId: "provider_second",
      name: "Second Gateway",
      models: [
        {
          ...SNAPSHOT.providers[0]!.models[0]!,
          modelId: "remote",
          model: "remote-model",
          name: "Remote",
        },
      ],
    };
    /** Shell 受控导航保留真实渲染顺序，避免 mock 回调掩盖目标未挂载的问题。 */
    function SearchSettings(): React.ReactElement {
      const [section, setSection] = useState<SettingsSection>("general");
      return (
        <Settings
          snapshot={{ ...SNAPSHOT, providers: [...SNAPSHOT.providers, extraProvider] }}
          ports={ports()}
          desktop={desktop()}
          section={section}
          onSectionChange={setSection}
          interfacePreferences={INTERFACE_PREFERENCES}
          executionScope={EXECUTION_SCOPE}
        />
      );
    }
    render(<SearchSettings />);
    for (let attempt = 0; attempt < 2; attempt++) {
      await user.type(screen.getByRole("textbox", { name: "搜索设置" }), "remote-model");
      await user.keyboard("{ArrowDown}{Enter}");
      await waitFor(() =>
        expect(document.activeElement).toBe(
          screen.getByRole("button", { name: /编辑模型 remote-model/ }),
        ),
      );
    }
    expect(
      screen.getByRole("button", { name: /Second Gateway 1 个模型/ }).getAttribute("aria-pressed"),
    ).toBe("true");
  });

  /** 切分类恢复各自阅读位置；搜索取消与清空均返回输入框，不把焦点留在卸载按钮上。 */
  it("preserves category scroll and keeps search cancellation keyboard accessible", async () => {
    const user = userEvent.setup();
    /** 分类状态仍由 Shell wrapper 持有，便于验证生产导航与布局副作用。 */
    function NavigableSettings(): React.ReactElement {
      const [section, setSection] = useState<SettingsSection>("appearance");
      return (
        <Settings
          snapshot={SNAPSHOT}
          ports={ports()}
          desktop={desktop()}
          section={section}
          onSectionChange={setSection}
          interfacePreferences={INTERFACE_PREFERENCES}
          executionScope={EXECUTION_SCOPE}
        />
      );
    }
    const { container } = render(<NavigableSettings />);
    const viewport = container.querySelector<HTMLElement>(".ja-scroll-area-viewport")!;
    viewport.scrollTop = 300;
    await user.click(screen.getByRole("tab", { name: "通用" }));
    expect(viewport.scrollTop).toBe(0);
    viewport.scrollTop = 75;
    await user.click(screen.getByRole("tab", { name: "外观" }));
    expect(viewport.scrollTop).toBe(300);
    const search = screen.getByRole("textbox", { name: "搜索设置" });
    await user.type(search, "字号");
    await user.keyboard("{ArrowDown}");
    expect(document.activeElement?.getAttribute("role")).toBe("option");
    await user.keyboard("{Escape}");
    expect(document.activeElement).toBe(search);
    expect(search).toHaveProperty("value", "");
    await user.type(search, "字号");
    await user.click(screen.getByRole("button", { name: "清空设置搜索" }));
    expect(document.activeElement).toBe(search);
  });

  /** 搜索须完成实际分类切换和聚焦；通知拒绝授权不能被误报为已开启。 */
  it("routes notification search to General and preserves permission-denied feedback", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn(async () => false);
    const settingsPorts = ports();
    const desktopPort = desktop();
    /** 模拟 Shell 的唯一分类 owner，避免只验证回调却未实际切换面板。 */
    function ControlledSettings(): React.ReactElement {
      const [section, setSection] = useState<SettingsSection>("appearance");
      return (
        <Settings
          interfacePreferences={INTERFACE_PREFERENCES}
          executionScope={EXECUTION_SCOPE}
          snapshot={SNAPSHOT}
          ports={settingsPorts}
          desktop={desktopPort}
          section={section}
          onSectionChange={setSection}
          desktopNotifications={{ enabled: false, onChange }}
        />
      );
    }
    render(<ControlledSettings />);
    expect(
      within(screen.getByRole("tabpanel", { name: "外观" })).queryByRole("switch", {
        name: "桌面通知",
      }),
    ).toBeNull();
    await user.type(screen.getByRole("textbox", { name: "搜索设置" }), "桌面通知");
    expect(screen.getAllByRole("option")).toHaveLength(1);
    await user.click(screen.getByRole("option", { name: /桌面通知/ }));
    const general = screen.getByRole("tabpanel", { name: "通用" });
    const notifications = within(general).getByRole("switch", { name: "桌面通知" });
    await waitFor(() => expect(document.activeElement).toBe(notifications));
    await user.click(notifications);
    expect(onChange).toHaveBeenCalledWith(true);
    expect((await within(general).findByRole("status")).textContent).toContain(
      "系统未授予通知权限",
    );
    expect(notifications.getAttribute("aria-checked")).toBe("false");
    expect(settingsPorts.onAppearanceChange).not.toHaveBeenCalled();
  });

  /** 缺少原生通知端口时搜索不能生成无法操作的结果。 */
  it("does not advertise notifications without a desktop notification capability", async () => {
    const user = userEvent.setup();
    renderSettings("general");
    await user.type(screen.getByRole("textbox", { name: "搜索设置" }), "桌面通知");
    expect(screen.queryByRole("option")).toBeNull();
    expect(screen.getByText("没有匹配的设置")).toBeDefined();
  });

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

    await userEvent.click(screen.getByRole("switch", { name: "review：已停用" }));
    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("skill_review", true));
  });

  it("shows one return action without duplicate heading, scope, or conversation controls", async () => {
    const user = userEvent.setup();
    const onReturnToApp = vi.fn();
    render(
      <Settings
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
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
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual([
      "通用",
      "外观",
      "模型",
      "子智能体",
      "执行确认",
      "Skills",
      "MCP",
      "关于",
    ]);
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
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
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

  it("keeps Provider connection fields and model rows in one compact sheet", async () => {
    const user = userEvent.setup();
    renderSettings("models");
    expect(screen.getAllByText("OpenAI").length).toBeGreaterThan(0);
    expect(screen.getByText("GPT 5.6")).toBeDefined();
    expect(screen.getByText("Mini")).toBeDefined();
    expect(screen.getAllByText("当前默认")).toHaveLength(1);
    expect(screen.queryByRole("button", { name: "当前默认" })).toBeNull();
    expect(screen.queryByLabelText("API key / token")).toBeNull();
    expect(screen.queryByLabelText("Base URL")).toBeNull();

    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    expect(screen.getByRole("dialog", { name: "编辑供应商" })).toBeDefined();
    expect(
      within(screen.getByRole("dialog", { name: "编辑供应商" })).getAllByLabelText("上游模型标识"),
    ).toHaveLength(2);
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
    await user.click(screen.getByRole("button", { name: "新增服务" }));
    expect(screen.queryByLabelText("Protocol version")).toBe(null);
    await user.click(screen.getByRole("button", { name: "高级设置" }));
    expect(screen.getByLabelText("认证方式")).toBeDefined();
    expect(screen.queryByLabelText("Credential ref")).toBe(null);
  });

  it("completes MCP edit, enable, test, and delete actions through real ports", async () => {
    const user = userEvent.setup();
    const onSaveMcp = vi.fn(async () => undefined);
    const onTestMcp = vi.fn(async () => "connected" as const);
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
      enabled: true,
      status: "disabled" as const,
      tools: [],
    };
    renderSettings("mcp", ports({ onSaveMcp, onTestMcp, onDeleteMcp }), {
      ...SNAPSHOT,
      mcpServers: [server],
    });

    await user.click(screen.getByRole("button", { name: "测试" }));
    await waitFor(() => expect(onTestMcp).toHaveBeenCalledWith("mcp_local"));
    await user.click(screen.getByRole("switch", { name: "Local Tools：已启用" }));
    await waitFor(() =>
      expect(onSaveMcp).toHaveBeenCalledWith(expect.objectContaining({ enabled: false })),
    );

    await user.click(screen.getByRole("button", { name: "Local Tools 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "编辑" }));
    expect(screen.getByRole("dialog", { name: "编辑 Local Tools" })).toBeDefined();
    await user.clear(screen.getByLabelText("名称"));
    await user.type(screen.getByLabelText("名称"), "Local Tools 2");
    await user.click(screen.getByRole("button", { name: "保存服务" }));
    await waitFor(() =>
      expect(onSaveMcp).toHaveBeenLastCalledWith(
        expect.objectContaining({ mcpRevision: "mcp_local", name: "Local Tools 2" }),
      ),
    );

    await user.click(screen.getByRole("button", { name: "Local Tools 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "删除" }));
    await user.click(screen.getByRole("button", { name: "删除" }));
    await waitFor(() => expect(onDeleteMcp).toHaveBeenCalledWith("mcp_local"));
  });

  it("saves multiple models, custom budgets, and provider fields as one aggregate", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveProvider }));
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    await user.click(within(dialog).getByRole("button", { name: "添加模型" }));
    const rows = within(dialog).getAllByRole("article");
    expect(rows).toHaveLength(3);
    const added = rows[2]!;
    await user.type(within(added).getByLabelText("显示名称"), "Reasoner");
    await user.type(within(added).getByLabelText("上游模型标识"), "reasoner-v1");
    const context = within(added).getByLabelText("上下文 Tokens");
    await user.clear(context);
    await user.type(context, "777777");
    await user.click(within(dialog).getByRole("button", { name: "保存更改" }));
    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledTimes(1));
    expect(onSaveProvider).toHaveBeenCalledWith(
      expect.objectContaining({
        providerId: "provider_openai",
        models: expect.arrayContaining([
          expect.objectContaining({
            name: "Reasoner",
            model: "reasoner-v1",
            capabilities: expect.objectContaining({ contextWindowTokens: 777777 }),
          }),
        ]),
      }),
    );
  });

  it("keeps the Provider draft open when the save port rejects it", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => Promise.reject(new Error("CAS conflict")));
    renderSettings("models", ports({ onSaveProvider }));

    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const name = within(dialog).getByLabelText("供应商名称");
    await user.clear(name);
    await user.type(name, "OpenAI 项目草稿");
    await user.click(screen.getByRole("button", { name: "保存更改" }));

    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("dialog", { name: "编辑供应商" })).toBeDefined();
    expect(within(dialog).getByLabelText("供应商名称")).toHaveProperty("value", "OpenAI 项目草稿");
  });

  it("edits the API specification without a separate provider-brand selector", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveProvider }));
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));

    expect(screen.queryByLabelText("服务商")).toBeNull();
    await user.click(screen.getByLabelText("API 规范"));
    await user.click(screen.getByRole("option", { name: "Anthropic Messages" }));
    await user.click(screen.getByRole("button", { name: "保存更改" }));
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
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    const secretInput = within(dialog).getByLabelText("API key / token");
    expect(secretInput.getAttribute("type")).toBe("password");
    expect(secretInput.getAttribute("autocomplete")).toBe("new-password");
    await user.type(within(dialog).getByLabelText("供应商名称"), "DeepSeek");
    await user.type(within(dialog).getByLabelText("Base URL"), "https://api.deepseek.com");

    await user.click(within(dialog).getByLabelText("API 规范"));
    expect(screen.getByRole("option", { name: "Anthropic Messages" })).toBeDefined();
    expect(screen.getByRole("option", { name: "OpenAI Chat Completions" })).toBeDefined();
    expect(screen.getByRole("option", { name: "OpenAI Responses" })).toBeDefined();
    await user.click(screen.getByRole("option", { name: "OpenAI Chat Completions" }));
    await user.type(within(dialog).getByLabelText("显示名称"), "DeepSeek V4");
    await user.type(within(dialog).getByLabelText("上游模型标识"), "deepseek-v4-pro");
    await user.type(secretInput, "deepseek-test-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

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
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    await user.type(within(dialog).getByLabelText("供应商名称"), "DeepSeek");
    await user.type(within(dialog).getByLabelText("Base URL"), "https://api.deepseek.com");
    await user.type(within(dialog).getByLabelText("显示名称"), "DeepSeek V4");
    await user.type(within(dialog).getByLabelText("上游模型标识"), "deepseek-v4-pro");
    await user.click(within(dialog).getByRole("button", { name: "添加模型" }));
    const retryRows = within(dialog).getAllByRole("article");
    await user.type(within(retryRows[1]!).getByLabelText("显示名称"), "DeepSeek Flash");
    await user.type(within(retryRows[1]!).getByLabelText("上游模型标识"), "deepseek-v4-flash");
    const secretInput = within(dialog).getByLabelText("API key / token");
    await user.type(secretInput, "first-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

    await waitFor(() => expect(onCreateProvider).toHaveBeenCalledTimes(1));
    expect(secretInput).toHaveProperty("value", "");
    expect((await within(dialog).findByRole("alert")).textContent).toContain(
      "Provider 已保存，但密钥保存失败，请重新输入后重试",
    );
    const firstProvider = onCreateProvider.mock.calls[0]![0];
    await user.type(secretInput, "second-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

    await waitFor(() => expect(onCreateProvider).toHaveBeenCalledTimes(2));
    expect(onCreateProvider.mock.calls[1]![0].providerId).toBe(firstProvider.providerId);
    expect(onCreateProvider.mock.calls[1]![0].models.map((model) => model.modelId)).toEqual(
      firstProvider.models.map((model) => model.modelId),
    );
    expect(onCreateProvider.mock.calls[1]![1]).toBe("second-secret");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "新增供应商" })).toBeNull());
  });

  /** 保存前拒绝重复上游身份，且不调用端口、不丢失统一 sheet 草稿。 */
  it("rejects duplicate upstream model identifiers before persistence", async () => {
    const user = userEvent.setup();
    const onCreateProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onCreateProvider }));
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    await user.type(within(dialog).getByLabelText("供应商名称"), "Gateway");
    await user.type(within(dialog).getByLabelText("Base URL"), "https://gateway.example.test/v1");
    await user.type(within(dialog).getByLabelText("API key / token"), "test-secret");
    const first = within(dialog).getAllByRole("article")[0]!;
    await user.type(within(first).getByLabelText("显示名称"), "First");
    await user.type(within(first).getByLabelText("上游模型标识"), "same-model");
    await user.click(within(dialog).getByRole("button", { name: "添加模型" }));
    const second = within(dialog).getAllByRole("article")[1]!;
    await user.type(within(second).getByLabelText("显示名称"), "Second");
    await user.type(within(second).getByLabelText("上游模型标识"), "same-model");
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

    expect(onCreateProvider).not.toHaveBeenCalled();
    expect((await within(dialog).findByRole("alert")).textContent).toContain("重复");
  });

  /** 输出预算不得超过上下文预算，避免保存后上游请求在应用层才失败。 */
  it("rejects an output budget larger than the context budget", async () => {
    const user = userEvent.setup();
    const onCreateProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onCreateProvider }));
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    await user.type(within(dialog).getByLabelText("供应商名称"), "Gateway");
    await user.type(within(dialog).getByLabelText("Base URL"), "https://gateway.example.test/v1");
    await user.type(within(dialog).getByLabelText("API key / token"), "test-secret");
    const row = within(dialog).getAllByRole("article")[0]!;
    await user.type(within(row).getByLabelText("显示名称"), "Small Context");
    await user.type(within(row).getByLabelText("上游模型标识"), "small-context");
    await user.clear(within(row).getByLabelText("上下文 Tokens"));
    await user.type(within(row).getByLabelText("上下文 Tokens"), "4096");
    await user.clear(within(row).getByLabelText("最大输出 Tokens"));
    await user.type(within(row).getByLabelText("最大输出 Tokens"), "8192");
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

    expect(onCreateProvider).not.toHaveBeenCalled();
    expect((await within(dialog).findByRole("alert")).textContent).toContain("最大输出预算无效");
  });

  /** 厂商推荐只在显式应用时覆盖预算；可选别名不应阻断真实上游模型的保存。 */
  it("applies recommendations explicitly and saves the upstream identifier without an alias", async () => {
    const user = userEvent.setup();
    const onCreateProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onCreateProvider }));
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    fireEvent.change(within(dialog).getByLabelText("供应商名称"), {
      target: { value: "DeepSeek" },
    });
    fireEvent.change(within(dialog).getByLabelText("Base URL"), {
      target: { value: "https://api.deepseek.com" },
    });
    fireEvent.change(within(dialog).getByLabelText("上下文 Tokens"), {
      target: { value: "777777" },
    });
    fireEvent.change(within(dialog).getByLabelText("上游模型标识"), {
      target: { value: "deepseek-v4-pro" },
    });
    expect(within(dialog).getByLabelText("上下文 Tokens")).toHaveProperty("value", "777777");
    await user.click(within(dialog).getByRole("button", { name: "使用推荐值" }));
    expect(within(dialog).getByLabelText("上下文 Tokens")).toHaveProperty("value", "1000000");
    fireEvent.change(within(dialog).getByLabelText("API key / token"), {
      target: { value: "test-secret" },
    });
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));
    await waitFor(() => expect(onCreateProvider).toHaveBeenCalledTimes(1));
    expect(onCreateProvider).toHaveBeenCalledWith(
      expect.objectContaining({
        models: [
          expect.objectContaining({
            name: "deepseek-v4-pro",
            model: "deepseek-v4-pro",
            capabilities: { contextWindowTokens: 1_000_000, maxOutputTokens: 8_192 },
          }),
        ],
      }),
      "test-secret",
    );
  });

  /** 取消是纯本地路径，不能把尚未提交的连接、模型或 Secret 写入端口。 */
  it("cancels a new provider without persistence or stale draft", async () => {
    const user = userEvent.setup();
    const onCreateProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onCreateProvider }));
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    let dialog = screen.getByRole("dialog", { name: "新增供应商" });
    await user.type(within(dialog).getByLabelText("供应商名称"), "Discarded");
    await user.click(within(dialog).getByRole("button", { name: "取消" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "新增供应商" })).toBeNull());
    expect(onCreateProvider).not.toHaveBeenCalled();

    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    dialog = screen.getByRole("dialog", { name: "新增供应商" });
    expect(within(dialog).getByLabelText("供应商名称")).toHaveProperty("value", "");
  });

  /** 编辑只改变连接字段时仍提交原模型、默认值、网络超时和凭据引用。 */
  it("preserves other models, defaults, and credential identity when editing", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveProvider }));
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const name = within(dialog).getByLabelText("供应商名称");
    await user.clear(name);
    await user.type(name, "OpenAI Renamed");
    await user.click(within(dialog).getByRole("button", { name: "保存更改" }));
    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledTimes(1));
    expect(onSaveProvider).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "OpenAI Renamed",
        credentialId: "cred_openai",
        models: SNAPSHOT.providers[0]!.models,
        agentDefaults: SNAPSHOT.providers[0]!.agentDefaults,
        networkTimeouts: SNAPSHOT.providers[0]!.networkTimeouts,
      }),
    );
  });

  /** 保存进行中锁住关闭和重复提交，完成后才允许 sheet 退出。 */
  it("prevents duplicate save and close while the provider save is busy", async () => {
    const user = userEvent.setup();
    let release!: () => void;
    const onSaveProvider = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    renderSettings("models", ports({ onSaveProvider }));
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    const saveButton = within(dialog).getByRole("button", { name: "保存更改" });
    await user.click(saveButton);
    await waitFor(() => expect(onSaveProvider).toHaveBeenCalledTimes(1));
    expect(saveButton).toHaveProperty("disabled", true);
    expect(within(dialog).getByRole("button", { name: "取消" })).toHaveProperty("disabled", true);
    await user.click(saveButton);
    expect(onSaveProvider).toHaveBeenCalledTimes(1);
    release();
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "编辑供应商" })).toBeNull());
  });

  it("edits the full reasoning map without exposing renderer-owned input modalities", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveProvider }));
    await user.click(screen.getByRole("button", { name: /GPT 5.6/ }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    expect(screen.queryByRole("checkbox", { name: "PDF" })).toBeNull();
    const modelRow = within(dialog).getAllByRole("article")[0]!;
    await user.click(within(modelRow).getByRole("checkbox", { name: "最少 (minimal)" }));
    await user.click(within(dialog).getByRole("button", { name: "保存更改" }));
    await waitFor(() =>
      expect(onSaveProvider).toHaveBeenCalledWith(
        expect.objectContaining({
          models: expect.arrayContaining([
            expect.objectContaining({
              modelId: "model_gpt",
              capabilities: expect.objectContaining({ contextWindowTokens: 256_000 }),
              reasoningLevelMap: {
                minimal: "minimal",
                low: "low",
                medium: "medium",
                high: "high",
              },
              defaultReasoningLevel: "high",
            }),
          ]),
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

  it("offers five keyboard-selectable palettes with decorative color previews", async () => {
    const user = userEvent.setup();
    const onAppearanceChange = vi.fn(async () => undefined);
    renderSettings("appearance", ports({ onAppearanceChange }));
    const paletteSelect = screen.getByRole("combobox", { name: "配色主题" });
    expect(paletteSelect.getAttribute("aria-describedby")).toBe("appearance-palette-hint");

    await user.click(paletteSelect);
    expect(screen.getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Ja",
      "JetBrains",
      "Xcode",
      "Obsidian",
      "Claude",
    ]);
    expect(document.querySelectorAll(".ja-settings-palette-swatch")).toHaveLength(18);

    await user.keyboard("{ArrowUp}{Enter}");
    await waitFor(() =>
      expect(onAppearanceChange).toHaveBeenCalledWith(
        expect.objectContaining({ palette: "jetbrains" }),
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
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
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
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
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
