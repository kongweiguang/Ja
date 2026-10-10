// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Settings } from "@/features/settings/ui/Settings";
import type { SettingsDesktopPort, SettingsPorts } from "@/features/settings/application/ports";
import type { SettingsInterfacePreferences } from "@/features/settings/application/ports";
import type {
  ConfigurationIssue,
  SettingsSection,
  SettingsSnapshot,
} from "@/features/settings/domain/types";

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
      },
      models: [
        {
          modelId: "model_gpt",
          name: "GPT 5.6",
          model: "gpt-5.6-sol",
          capabilities: {
            contextWindowTokens: 256_000,
            maxOutputTokens: 32_000,
            supportsOutputSchema: false,
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
            supportsOutputSchema: false,
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
    onDiscoverModels: vi.fn(async () => ({ items: [], truncated: false })),
    onDeleteModel: vi.fn(async () => undefined),
    onMoveModel: vi.fn(async () => undefined),
    onDefaultSelectionChange: vi.fn(async () => undefined),
    onSubagentSettingsChange: vi.fn(async () => undefined),
    onReplaceCredential: vi.fn(async () => undefined),
    onClearCredential: vi.fn(async () => undefined),
    onRevealProviderCredential: vi.fn(async () => null),
    onSaveMcp: vi.fn(async () => undefined),
    onDeleteMcp: vi.fn(async () => undefined),
    onTestMcp: vi.fn(async () => "connected" as const),
    onToggleSkill: vi.fn(async () => undefined),
    onAccessModeChange: vi.fn(async () => undefined),
    onInheritProjectOverride: vi.fn(async () => undefined),
    onProjectProviderRuntimeChange: vi.fn(async () => undefined),
    onInheritProjectProvider: vi.fn(async () => undefined),
    onProjectProviderConnectionChange: vi.fn(async () => undefined),
    onInheritProjectProviderConnection: vi.fn(async () => undefined),
    onSaveProjectProviderModels: vi.fn(async () => undefined),
    onInheritProjectProviderModels: vi.fn(async () => undefined),
    onInheritProjectModelOverride: vi.fn(async () => undefined),
    onHideProjectModel: vi.fn(async () => undefined),
    onRestoreProjectModel: vi.fn(async () => undefined),
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
  /** 页面从外部路由进入时，焦点落在当前分类标签，键盘用户可直接继续操作设置导航。 */
  it("focuses the selected category tab when entering Settings", async () => {
    renderSettings("mcp");

    const selectedTab = screen.getByRole("tab", { name: "MCP" });
    await waitFor(() => expect(selectedTab).toHaveFocus());
  });

  /** 项目模型页明确展示运行参数继承与保存。 */
  it("edits only the current project's Provider runtime parameters", async () => {
    const user = userEvent.setup();
    const actions = ports();
    const snapshot: SettingsSnapshot = {
      ...SNAPSHOT,
      providers: SNAPSHOT.providers.map((provider) => ({
        ...provider,
        networkTimeouts: { connectTimeoutMs: 5_000, requestTimeoutMs: 60_000 },
        agentDefaults: { context: { autoCompact: false } },
      })),
    };
    render(
      <Settings
        snapshot={snapshot}
        configScope="project"
        projectConfigAvailable
        projectOverrides={{
          defaultSelection: true,
          accessMode: true,
          clarification: false,
          subagents: false,
          providerIds: ["provider_openai"],
          providerRuntime: [
            {
              providerId: "provider_openai",
              networkTimeouts: { requestTimeoutMs: 60_000 },
              autoCompact: false,
            },
          ],
          disabledSkillReferences: [],
        }}
        ports={actions}
        desktop={desktop()}
        section="models"
        onSectionChange={() => undefined}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={{ ...EXECUTION_SCOPE, projectOverride: true, workspaceKind: "project" }}
      />,
    );

    expect(screen.getByRole("button", { name: "当前项目" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.getByRole("spinbutton", { name: "OpenAI 请求超时（ms）" })).toHaveValue(60_000);
    expect(screen.getByRole("checkbox", { name: "自动压缩上下文" })).not.toBeChecked();
    await user.clear(screen.getByRole("spinbutton", { name: "OpenAI 请求超时（ms）" }));
    await user.type(screen.getByRole("spinbutton", { name: "OpenAI 请求超时（ms）" }), "90000");
    await user.click(screen.getByRole("button", { name: "保存项目参数" }));
    await waitFor(() =>
      expect(actions.onProjectProviderRuntimeChange).toHaveBeenCalledWith("provider_openai", {
        connectTimeoutMs: 5_000,
        requestTimeoutMs: 90_000,
        autoCompact: false,
      }),
    );
    await user.click(
      within(screen.getByRole("region", { name: "项目 Provider 运行参数" })).getByRole("button", {
        name: "使用全局设置",
      }),
    );
    expect(actions.onInheritProjectProvider).toHaveBeenCalledWith("provider_openai");
  });

  /** 更换项目目标时显式提交完整连接三元组；继承动作只清除项目连接覆盖。 */
  it("saves and inherits a project's Provider connection as one unit", async () => {
    const user = userEvent.setup();
    const actions = ports();
    render(
      <Settings
        snapshot={SNAPSHOT}
        globalProviders={SNAPSHOT.providers}
        configScope="project"
        projectConfigAvailable
        projectOverrides={{
          defaultSelection: false,
          accessMode: false,
          providerRuntime: [],
          disabledSkillReferences: [],
        }}
        ports={actions}
        desktop={desktop()}
        section="models"
        onSectionChange={() => undefined}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={{ ...EXECUTION_SCOPE, workspaceKind: "project" }}
      />,
    );

    await user.selectOptions(
      screen.getByRole("combobox", { name: "OpenAI 连接协议" }),
      "anthropic_messages",
    );
    expect(screen.queryByRole("button", { name: "新增供应商" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "编辑供应商" })).not.toBeInTheDocument();
    await user.clear(screen.getByRole("textbox", { name: "OpenAI 项目连接地址" }));
    await user.type(
      screen.getByRole("textbox", { name: "OpenAI 项目连接地址" }),
      "https://example.com/v1",
    );
    await user.click(screen.getByRole("button", { name: "保存项目连接" }));
    await waitFor(() =>
      expect(actions.onProjectProviderConnectionChange).toHaveBeenCalledWith("provider_openai", {
        api: "anthropic_messages",
        baseUrl: "https://example.com/v1",
        credentialId: "cred_openai",
      }),
    );

    const inheritedActions = ports();
    render(
      <Settings
        snapshot={SNAPSHOT}
        globalProviders={SNAPSHOT.providers}
        configScope="project"
        projectConfigAvailable
        projectOverrides={{
          defaultSelection: false,
          accessMode: false,
          providerRuntime: [
            {
              providerId: "provider_openai",
              connection: {
                api: "anthropic_messages",
                baseUrl: "https://example.com/v1",
                credentialId: "cred_openai",
              },
            },
          ],
          disabledSkillReferences: [],
        }}
        ports={inheritedActions}
        desktop={desktop()}
        section="models"
        onSectionChange={() => undefined}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={{ ...EXECUTION_SCOPE, workspaceKind: "project" }}
      />,
    );
    await user.click(screen.getByRole("button", { name: "继承全局连接" }));
    expect(inheritedActions.onInheritProjectProviderConnection).toHaveBeenCalledWith(
      "provider_openai",
    );
  });

  /** 项目模型编辑只依赖已装配的 feature 端口；app 路由在所属层验证，避免测试反向依赖组合根。 */
  it("edits and inherits one project model without opening global credentials", async () => {
    const user = userEvent.setup();
    const actions = ports();
    const projectProvider = {
      ...SNAPSHOT.providers[0]!,
      models: SNAPSHOT.providers[0]!.models.map((model, index) => ({
        ...model,
        model: index === 0 ? "project-gpt" : model.model,
      })),
    };
    render(
      <Settings
        snapshot={{ ...SNAPSHOT, providers: [projectProvider] }}
        globalProviders={SNAPSHOT.providers}
        configScope="project"
        projectConfigAvailable
        projectOverrides={{
          defaultSelection: false,
          accessMode: false,
          providerRuntime: [
            {
              providerId: "provider_openai",
              models: [projectProvider.models[0]!],
            },
          ],
          disabledSkillReferences: [],
        }}
        ports={{ ...actions, onSaveProvider: actions.onSaveProjectProviderModels! }}
        desktop={desktop()}
        section="models"
        onSectionChange={() => undefined}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={{ ...EXECUTION_SCOPE, workspaceKind: "project" }}
      />,
    );

    const inheritedModelRow = document.querySelector(
      '[data-setting-id="model-provider_openai-model_mini"]',
    );
    expect(inheritedModelRow).not.toBeNull();
    await user.click(
      within(inheritedModelRow as HTMLElement).getByRole("button", { name: "从当前项目隐藏" }),
    );
    expect(actions.onHideProjectModel).toHaveBeenCalledWith("provider_openai", "model_mini");

    await user.click(screen.getByRole("button", { name: /编辑项目模型 project-gpt/u }));
    const sheet = screen.getByRole("dialog", { name: "编辑项目模型" });
    expect(within(sheet).queryByLabelText("Base URL")).not.toBeInTheDocument();
    expect(within(sheet).queryByLabelText("API Key")).not.toBeInTheDocument();
    const upstreamModelInput = within(sheet).getAllByRole("textbox", { name: "上游模型标识" })[0]!;
    expect(upstreamModelInput).toHaveFocus();
    await user.clear(upstreamModelInput);
    await user.type(upstreamModelInput, "project-gpt-updated");
    await user.click(within(sheet).getByRole("button", { name: "保存项目模型" }));
    await waitFor(() =>
      expect(actions.onSaveProjectProviderModels).toHaveBeenCalledWith(
        expect.objectContaining({
          providerId: "provider_openai",
          models: expect.arrayContaining([
            expect.objectContaining({ model: "project-gpt-updated" }),
          ]),
        }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("项目模型已保存");
    expect(actions.onRevealProviderCredential).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "继承全局模型" }));
    expect(actions.onInheritProjectModelOverride).toHaveBeenCalledWith(
      "provider_openai",
      "model_gpt",
    );
  });

  /** 语法错误修复须先确认保留内容和备份行为，完成后关闭面板并还原入口焦点。 */
  it("confirms a configuration repair and returns focus after the authoritative read", async () => {
    const user = userEvent.setup();
    const onSectionChange = vi.fn();
    const onIssuesRepair = vi.fn(async (scope: "user" | "project") => {
      expect(["user", "project"]).toContain(scope);
    });
    /** 修复后问题入口随权威状态消失，验证焦点落到仍存在的设置分类。 */
    function RepairableSettings(): ReactElement {
      const [issues, setIssues] = useState<ConfigurationIssue[]>([
        {
          id: "cfg_toml_syntax_user",
          scope: "user",
          field: null,
          entityId: null,
          line: 18,
          column: null,
          reason: "TOML_PARSE_FAILED",
          impact: "valid_prefix_in_use",
          actions: ["repair"],
        },
      ]);
      return (
        <Settings
          snapshot={SNAPSHOT}
          issues={issues}
          onIssuesRepair={async (scope) => {
            await onIssuesRepair(scope);
            setIssues([]);
          }}
          ports={ports()}
          desktop={desktop()}
          section="general"
          onSectionChange={onSectionChange}
          interfacePreferences={INTERFACE_PREFERENCES}
          executionScope={EXECUTION_SCOPE}
        />
      );
    }
    render(<RepairableSettings />);

    const trigger = screen.getByRole("button", { name: "配置问题" });
    await user.click(trigger);
    const sheet = screen.getByRole("dialog", { name: "配置问题" });
    expect(
      within(sheet).getByText("第 18 行之后的内容无法确认，当前只使用此前能读取的设置。"),
    ).toBeInTheDocument();
    await user.click(within(sheet).getByRole("button", { name: "备份并修复" }));
    const confirmation = screen.getByRole("dialog", { name: "备份并修复配置？" });
    expect(
      within(confirmation).getByText(/无法识别的尾部内容将从活动配置中移除/u),
    ).toBeInTheDocument();
    expect(within(confirmation).getByText(/解析在第 18 行中断/u)).toBeInTheDocument();
    await user.click(within(confirmation).getByRole("button", { name: "备份并修复" }));
    await waitFor(() => expect(onIssuesRepair).toHaveBeenCalledWith("user"));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(trigger).not.toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "通用" })).toHaveFocus();
    expect(onSectionChange).not.toHaveBeenCalled();
  });

  /** MCP 条目中的未知字段只影响该条目，详情与编辑必须按稳定 MCP 身份路由。 */
  it("routes an MCP unknown-field issue to the MCP settings section", async () => {
    const user = userEvent.setup();
    const onSectionChange = vi.fn();
    render(
      <Settings
        snapshot={SNAPSHOT}
        issues={[
          {
            id: "cfg_user_mcp_kerminal_unknown_schema_version",
            scope: "user",
            field: "schema_version",
            entityId: "mcp_kerminal",
            line: 18,
            column: 1,
            reason: "UNKNOWN_FIELD",
            impact: "ignored",
            actions: ["edit"],
          },
        ]}
        ports={ports()}
        desktop={desktop()}
        section="general"
        onSectionChange={onSectionChange}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
      />,
    );

    await user.click(screen.getByRole("button", { name: "配置问题" }));
    const sheet = screen.getByRole("dialog", { name: "配置问题" });
    expect(within(sheet).getByText("MCP 服务")).toBeInTheDocument();
    await user.click(within(sheet).getByRole("button", { name: "编辑" }));
    expect(onSectionChange).toHaveBeenCalledWith("mcp");
  });

  /** 高版本配置复用现有备份修复能力，并说明版本不兼容而不暴露原始文件内容。 */
  it("explains an unsupported schema version and keeps the existing backup-repair flow", async () => {
    const user = userEvent.setup();
    const onIssuesRepair = vi.fn(async () => undefined);
    render(
      <Settings
        snapshot={SNAPSHOT}
        issues={[
          {
            id: "cfg_user_unsupported_schema_version",
            scope: "user",
            field: "schema_version",
            entityId: null,
            line: null,
            column: null,
            reason: "UNSUPPORTED_SCHEMA_VERSION",
            impact: "defaults_in_use",
            actions: ["repair"],
          },
        ]}
        onIssuesRepair={onIssuesRepair}
        ports={ports()}
        desktop={desktop()}
        section="general"
        onSectionChange={vi.fn()}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
      />,
    );

    await user.click(screen.getByRole("button", { name: "配置问题" }));
    const issues = screen.getByRole("dialog", { name: "配置问题" });
    expect(within(issues).getByText("配置版本")).toBeInTheDocument();
    expect(
      within(issues).getByText(
        "此配置声明的版本当前不受 Ja 支持，因此该层未能应用。请先备份，再使用现有修复操作恢复配置。",
      ),
    ).toBeInTheDocument();
    await user.click(within(issues).getByRole("button", { name: "备份并修复" }));

    const confirmation = screen.getByRole("dialog", { name: "备份并修复配置？" });
    expect(
      within(confirmation).getByText(
        "Ja 会先备份当前原文件，再写入此版本可识别的配置；原文件内容保留在备份中。",
      ),
    ).toBeInTheDocument();
    expect(within(confirmation).queryByText(/解析在第/u)).toBeNull();
    await user.click(within(confirmation).getByRole("button", { name: "备份并修复" }));
    await waitFor(() => expect(onIssuesRepair).toHaveBeenCalledWith("user"));
  });

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

  /** 全局与项目 Skill 使用独立作用域，切换后操作必须携带实际写入边界。 */
  it("separates global and project Skills while preserving source labels", async () => {
    const onToggleSkill = vi.fn(async () => undefined);
    const globalSkills = [
      {
        id: "builtin:review",
        name: "builtin-review",
        source: "builtin" as const,
        description: "Bundled review",
        enabled: true,
        status: "ready" as const,
      },
      {
        id: "user:review",
        name: "review",
        source: "user" as const,
        description: "Review changes",
        enabled: false,
        status: "disabled" as const,
      },
      {
        id: "ja:tools",
        name: "ja-tools",
        source: "ja" as const,
        description: "Ja tools",
        enabled: true,
        status: "ready" as const,
      },
    ];
    const projectSkills = [
      {
        id: "project:rules",
        name: "project-rules",
        source: "project" as const,
        description: "Project rules",
        enabled: true,
        status: "ready" as const,
      },
    ];
    render(
      <Settings
        snapshot={{ ...SNAPSHOT, skills: globalSkills }}
        skillSettings={{ global: globalSkills, project: projectSkills, projectAvailable: true }}
        ports={ports({ onToggleSkill })}
        desktop={desktop()}
        section="skills"
        onSectionChange={vi.fn()}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
      />,
    );

    for (const label of ["内置", "用户", "Ja"]) {
      expect(screen.getByText(label, { selector: ".ja-skill-row-title span" })).toBeInTheDocument();
    }
    expect(screen.getByRole("heading", { name: "builtin-review" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "review" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "ja-tools" })).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole("switch", { name: "review：已停用" }));
    await waitFor(() => expect(onToggleSkill).toHaveBeenCalledWith("user:review", true, "user"));

    expect(screen.getByRole("region", { name: "项目 Skills" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "project-rules" })).toBeInTheDocument();
    await user.click(screen.getByRole("switch", { name: "project-rules：已启用" }));
    await waitFor(() =>
      expect(onToggleSkill).toHaveBeenCalledWith("project:rules", false, "project"),
    );
  });

  /** 项目侧没有可管理项时保留明确空状态，不复用全局列表误导用户。 */
  it("shows an empty project scope without falling back to global Skills", async () => {
    render(
      <Settings
        snapshot={SNAPSHOT}
        skillSettings={{ global: SNAPSHOT.skills, project: [], projectAvailable: true }}
        ports={ports()}
        desktop={desktop()}
        section="skills"
        onSectionChange={vi.fn()}
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
      />,
    );
    expect(
      within(screen.getByRole("region", { name: "项目 Skills" })).getByRole("status"),
    ).toHaveTextContent("未发现项目 Skills");
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
      "数据与存储",
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
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    expect(dialog).toBeDefined();
    expect(within(dialog).getAllByLabelText("上游模型标识")).toHaveLength(2);
    const credentialInput = within(dialog).getByLabelText("API Key");
    expect(credentialInput.id).toBe("provider-secret");
    expect(credentialInput.getAttribute("autocomplete")).toBe("current-password");
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
    await waitFor(() => expect(onTestMcp).toHaveBeenCalledWith("mcp_local", "user"));
    await user.click(screen.getByRole("switch", { name: "Local Tools：已启用" }));
    await waitFor(() =>
      expect(onSaveMcp).toHaveBeenCalledWith(expect.objectContaining({ enabled: false }), "user"),
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
        "user",
      ),
    );

    await user.click(screen.getByRole("button", { name: "Local Tools 更多操作" }));
    await user.click(screen.getByRole("menuitem", { name: "删除" }));
    await user.click(screen.getByRole("button", { name: "删除" }));
    await waitFor(() => expect(onDeleteMcp).toHaveBeenCalledWith("mcp_local", "user"));
  });

  /** 编辑 Provider 的凭据异步读取短暂禁用连接字段，焦点应在解禁后落到名称输入。 */
  it("saves multiple models, custom budgets, and provider fields as one aggregate", async () => {
    const user = userEvent.setup();
    const onSaveProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onSaveProvider }));
    await user.click(screen.getByRole("button", { name: "编辑供应商" }));
    const dialog = screen.getByRole("dialog", { name: "编辑供应商" });
    await waitFor(() => expect(within(dialog).getByLabelText("供应商名称")).toHaveFocus());
    await user.click(within(dialog).getByRole("button", { name: "手动添加" }));
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
    await waitFor(() => expect(screen.getByRole("button", { name: "编辑供应商" })).toHaveFocus());
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

  /** 首次创建端口负责配置与凭据完整提交，UI 不得再单独重复写凭据。 */
  it("creates a custom DeepSeek supplier with its own credential and any API specification", async () => {
    const user = userEvent.setup();
    const onCreateProvider = vi.fn<SettingsPorts["onCreateProvider"]>(async (provider) => {
      expect(provider.credentialId).toMatch(/^cred_/);
    });
    const onSaveProvider = vi.fn<SettingsPorts["onSaveProvider"]>(async () => undefined);
    const onReplaceCredential = vi.fn<SettingsPorts["onReplaceCredential"]>(async () => undefined);
    renderSettings("models", ports({ onCreateProvider, onSaveProvider, onReplaceCredential }));
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    expect(within(dialog).getByLabelText("供应商名称")).toHaveFocus();
    const secretInput = within(dialog).getByLabelText("API Key");
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
    expect(onReplaceCredential).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const reopenedDialog = screen.getByRole("dialog", { name: "新增供应商" });
    expect(within(reopenedDialog).getByLabelText("API Key")).toHaveValue("");
  });

  /** 取消会丢弃当前 sheet 的 Secret；重新打开新增流程不得恢复已取消的输入。 */
  it("clears a new Provider secret when its sheet is canceled", async () => {
    const user = userEvent.setup();
    renderSettings("models");
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    let dialog = screen.getByRole("dialog", { name: "新增供应商" });
    await user.type(within(dialog).getByLabelText("API Key"), "discarded-secret");
    await user.click(within(dialog).getByRole("button", { name: "取消" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "新增供应商" })).toHaveFocus());

    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    dialog = screen.getByRole("dialog", { name: "新增供应商" });
    expect(within(dialog).getByLabelText("API Key")).toHaveValue("");
  });

  /** 凭据失败保留组件内 Secret；原样重试只补凭据，改动草稿时再提交配置。 */
  it("preserves the secret and stable identities while retrying only unfinished persistence", async () => {
    const user = userEvent.setup();
    const failure = Object.assign(new Error("redacted"), {
      code: "provider_saved_credential_failed",
    });
    const onCreateProvider = vi
      .fn<SettingsPorts["onCreateProvider"]>()
      .mockRejectedValueOnce(failure);
    const onSaveProvider = vi.fn<SettingsPorts["onSaveProvider"]>(async () => undefined);
    const onReplaceCredential = vi
      .fn<SettingsPorts["onReplaceCredential"]>()
      .mockRejectedValueOnce(new Error("vault unavailable"))
      .mockResolvedValueOnce(undefined);
    renderSettings("models", ports({ onCreateProvider, onSaveProvider, onReplaceCredential }));
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    await user.type(within(dialog).getByLabelText("供应商名称"), "DeepSeek");
    await user.type(within(dialog).getByLabelText("Base URL"), "https://api.deepseek.com");
    await user.type(within(dialog).getByLabelText("显示名称"), "DeepSeek V4");
    await user.type(within(dialog).getByLabelText("上游模型标识"), "deepseek-v4-pro");
    await user.click(within(dialog).getByRole("button", { name: "手动添加" }));
    const retryRows = within(dialog).getAllByRole("article");
    await user.type(within(retryRows[1]!).getByLabelText("显示名称"), "DeepSeek Flash");
    await user.type(within(retryRows[1]!).getByLabelText("上游模型标识"), "deepseek-v4-flash");
    const secretInput = within(dialog).getByLabelText("API Key");
    await user.type(secretInput, "first-secret");
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

    await waitFor(() => expect(onCreateProvider).toHaveBeenCalledTimes(1));
    expect(secretInput).toHaveProperty("value", "first-secret");
    expect((await within(dialog).findByRole("alert")).textContent).toContain(
      "供应商配置已保存，密钥操作未完成。输入已保留，可再次保存重试。",
    );
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: "保存供应商" })).toHaveFocus(),
    );
    const firstProvider = onCreateProvider.mock.calls[0]![0];
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

    await waitFor(() => expect(onReplaceCredential).toHaveBeenCalledTimes(1));
    expect(onCreateProvider).toHaveBeenCalledTimes(1);
    expect(onSaveProvider).not.toHaveBeenCalled();
    expect(onReplaceCredential).toHaveBeenLastCalledWith(
      firstProvider.credentialId,
      "first-secret",
    );

    await user.clear(within(dialog).getByLabelText("供应商名称"));
    await user.type(within(dialog).getByLabelText("供应商名称"), "DeepSeek Updated");
    await user.click(within(dialog).getByRole("button", { name: "保存供应商" }));

    await waitFor(() => expect(onReplaceCredential).toHaveBeenCalledTimes(2));
    expect(onCreateProvider).toHaveBeenCalledTimes(1);
    expect(onSaveProvider).toHaveBeenCalledTimes(1);
    const updatedProvider = onSaveProvider.mock.calls[0]![0];
    expect(updatedProvider).toEqual(expect.objectContaining({ name: "DeepSeek Updated" }));
    expect(updatedProvider.providerId).toBe(firstProvider.providerId);
    expect(updatedProvider.models.map((model) => model.modelId)).toEqual(
      firstProvider.models.map((model) => model.modelId),
    );
    expect(onReplaceCredential).toHaveBeenLastCalledWith(
      firstProvider.credentialId,
      "first-secret",
    );
    expect(secretInput).toHaveProperty("value", "first-secret");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "新增供应商" })).toBeNull());
  }, 15_000);

  /** 保存前拒绝重复上游身份，且不调用端口、不丢失统一 sheet 草稿。 */
  it("rejects duplicate upstream model identifiers before persistence", async () => {
    const user = userEvent.setup();
    const onCreateProvider = vi.fn(async () => undefined);
    renderSettings("models", ports({ onCreateProvider }));
    await user.click(screen.getByRole("button", { name: "新增供应商" }));
    const dialog = screen.getByRole("dialog", { name: "新增供应商" });
    await user.type(within(dialog).getByLabelText("供应商名称"), "Gateway");
    await user.type(within(dialog).getByLabelText("Base URL"), "https://gateway.example.test/v1");
    await user.type(within(dialog).getByLabelText("API Key"), "test-secret");
    const first = within(dialog).getAllByRole("article")[0]!;
    await user.type(within(first).getByLabelText("显示名称"), "First");
    await user.type(within(first).getByLabelText("上游模型标识"), "same-model");
    await user.click(within(dialog).getByRole("button", { name: "手动添加" }));
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
    await user.type(within(dialog).getByLabelText("API Key"), "test-secret");
    const row = within(dialog).getAllByRole("article")[0]!;
    await user.type(within(row).getByLabelText("显示名称"), "Small Context");
    await user.type(within(row).getByLabelText("上游模型标识"), "small-context");
    await user.clear(within(row).getByLabelText("上下文 Tokens"));
    await user.type(within(row).getByLabelText("上下文 Tokens"), "4096");
    await user.clear(within(row).getByLabelText("单次最大输出 Tokens"));
    await user.type(within(row).getByLabelText("单次最大输出 Tokens"), "8192");
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
    fireEvent.change(within(dialog).getByLabelText("API Key"), {
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
            capabilities: {
              contextWindowTokens: 1_000_000,
              maxOutputTokens: 16_384,
              supportsOutputSchema: false,
            },
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
    fireEvent.click(screen.getByRole("radio", { name: /需要审批/ }));
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

  /** 切换工作区的权威快照尚未返回时，Skills 不得沿用上一个项目的可编辑投影。 */
  it("disables Skills controls until the current settings scope is ready", () => {
    render(
      <Settings
        interfacePreferences={INTERFACE_PREFERENCES}
        executionScope={EXECUTION_SCOPE}
        snapshot={SNAPSHOT}
        skillSettings={{
          global: [
            {
              id: "user:review",
              name: "review",
              source: "user",
              description: "检查变更。",
              enabled: false,
              status: "disabled",
            },
          ],
          projectAvailable: false,
        }}
        ports={ports()}
        desktop={desktop()}
        section="skills"
        onSectionChange={vi.fn()}
        disabled
      />,
    );

    expect(screen.getByRole("switch", { name: "review：已停用" })).toBeDisabled();
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
