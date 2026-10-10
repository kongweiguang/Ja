// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
/** 让真实惰性设置模块在测试收集阶段预热，不占用固定的生命周期 hook 预算。 */
import "@/features/settings";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Profiler, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SettingsView, type SettingsViewProps } from "@/app/composition/SettingsView";
import type {
  SettingsController,
  SettingsPorts,
  SettingsUserConfigurationPorts,
} from "@/features/settings";

afterEach(cleanup);

/** 每个控制器有独立写入端口，真实设置组件的点击必须到达当前可用范围的 owner。 */
function settingsPorts(): SettingsPorts {
  return {
    onCreateProvider: vi.fn(async () => undefined),
    onSaveProvider: vi.fn(async () => undefined),
    onDeleteProvider: vi.fn(async () => undefined),
    onMoveProvider: vi.fn(async () => undefined),
    onSaveModel: vi.fn(async () => undefined),
    onTestModel: vi.fn(async () => ({ responseModel: "fixture", latencyMs: 1 })),
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
    onTestMcp: vi.fn(async () => "unknown" as const),
    onToggleSkill: vi.fn(async () => undefined),
    onAccessModeChange: vi.fn(async () => undefined),
    onClarificationEnabledChange: vi.fn(async () => undefined),
    onAppearanceChange: vi.fn(async () => undefined),
  };
}

/** 全局编辑动作使用独立端口，确保活动 Workspace 的有效配置不会接管用户层写入。 */
function userConfigurationPorts(): SettingsUserConfigurationPorts {
  const ports = settingsPorts();
  return {
    onDefaultSelectionChange: ports.onDefaultSelectionChange,
    onSubagentSettingsChange: ports.onSubagentSettingsChange,
    onAccessModeChange: ports.onAccessModeChange,
    onClarificationEnabledChange: ports.onClarificationEnabledChange,
  };
}

/** 用不同澄清值区分用户与项目权威快照，避免只验证按钮样式而遗漏实际编辑来源。 */
function settingsController(project = false): SettingsController {
  const document: NonNullable<SettingsController["loaded"]>["document"] = {
    schemaVersion: 2,
    revision: 1,
    theme: "system",
    defaultSelection: null,
    defaultAccessMode: "full_access",
    clarificationEnabled: !project,
    subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
    providers: [],
    mcpServers: [],
    disabledSkills: [],
    window: { width: 1280, height: 800, maximized: false },
  };
  const snapshot = {
    revision: document.revision,
    defaultSelection: document.defaultSelection,
    defaultAccessMode: document.defaultAccessMode,
    clarificationEnabled: document.clarificationEnabled,
    subagents: document.subagents,
    providers: [],
    skills: [],
    mcpServers: [],
    appearance: {
      theme: "system" as const,
      palette: "xcode" as const,
      reducedMotion: false,
      reducedTransparency: false,
      highContrast: false,
    },
  };
  return {
    loaded: {
      document,
      userDocument: { ...document, clarificationEnabled: true },
      projectOverrides: {
        defaultSelection: false,
        accessMode: false,
        disabledSkillReferences: [],
      },
      cas: {
        userVersion: "cfg_user",
        projectVersion: "cfg_project",
        credentialVersion: "cfg_credential",
      },
      issues: [],
    },
    snapshot,
    globalSnapshot: { ...snapshot, clarificationEnabled: true },
    skillSettings: { global: [], project: project ? [] : undefined, projectAvailable: project },
    mcpSettings: { global: [], project: project ? [] : undefined, projectAvailable: project },
    scopeReady: true,
    scopeWorkspaceId: project ? "ws_project" : undefined,
    loading: false,
    synchronizing: false,
    error: undefined,
    ports: settingsPorts(),
    userConfigurationPorts: userConfigurationPorts(),
    repairConfiguration: vi.fn(async () => undefined),
    reload: vi.fn(async () => undefined),
  };
}

/** 组合层夹具只注入边界事实；范围切换、惰性加载和表单保存均经过真实组件。 */
function settingsViewProps(): SettingsViewProps {
  return {
    settings: settingsController(),
    projectSettings: settingsController(true),
    projects: [
      {
        workspaceId: "ws_project",
        kind: "project",
        displayName: "Ja",
        rootPath: "C:\\projects\\ja",
        trust: "trusted",
      },
    ],
    selectedProjectId: "ws_project",
    onSelectProject: vi.fn(),
    onSetProjectTrust: vi.fn(async () => undefined),
    interfacePreferences: {
      sendShortcut: "enter",
      uiFontSize: 16,
      codeFontSize: 13,
      onChange: vi.fn(async () => undefined),
    },
    executionScope: { scopedDefault: "full_access", projectOverride: false, scopeReady: true },
    required: false,
    section: "general",
    onSectionChange: vi.fn(),
    onReturnToApp: vi.fn(),
    desktopNotifications: { enabled: false, onChange: vi.fn(async (enabled: boolean) => enabled) },
    desktop: {
      readCloseBehavior: vi.fn(async () => "background" as const),
      saveCloseBehavior: vi.fn(async () => undefined),
      openExternalUrl: vi.fn(async () => undefined),
      checkForUpdate: vi.fn(async () => ({ kind: "up-to-date" as const })),
      installUpdate: vi.fn(async () => undefined),
      relaunchAfterUpdate: vi.fn(async () => undefined),
    },
  };
}

/** 记录每次已提交的真实范围，不能用 effect 最终收口后的 DOM 掩盖短暂失效项目帧。 */
function settingsView(props: SettingsViewProps, projectCommits: boolean[]): ReactElement {
  return (
    <Profiler
      id="settings-scope"
      onRender={() => {
        const projectButton = screen.queryByRole("button", { name: "当前项目" });
        if (projectButton !== null) {
          projectCommits.push(projectButton.getAttribute("aria-pressed") === "true");
        }
      }}
    >
      <SettingsView {...props} />
    </Profiler>
  );
}

describe("SettingsView scope admission", () => {
  /** 未授信的活动项目不能阻止用户设置写入，且项目范围保持关闭直到可用。 */
  it("allows global user writes while the active project scope is unavailable", async () => {
    const user = userEvent.setup();
    const props: SettingsViewProps = {
      ...settingsViewProps(),
      settings: settingsController(true),
      projectSettings: {
        ...settingsController(true),
        scopeReady: false,
        skillSettings: { global: [], projectAvailable: false },
      },
    };
    render(settingsView(props, []));

    const projectScope = await screen.findByRole("button", { name: "当前项目" });
    expect(projectScope).toBeDisabled();
    const clarification = screen.getByRole("switch", { name: "交互澄清" });
    expect(clarification).toBeChecked();
    await user.click(clarification);

    expect(
      props.settings.userConfigurationPorts.onClarificationEnabledChange,
    ).toHaveBeenCalledExactlyOnceWith(false);
    expect(props.settings.ports.onClarificationEnabledChange).not.toHaveBeenCalled();
    expect(props.projectSettings.ports.onClarificationEnabledChange).not.toHaveBeenCalled();
    expect(projectScope).toBeDisabled();
  });

  /** 项目权限覆盖的继承动作必须按选中范围到达项目 owner。 */
  it("routes project inheritance to the selected project controller", async () => {
    const user = userEvent.setup();
    const props: SettingsViewProps = { ...settingsViewProps(), section: "permissions" };
    const inherit = vi.fn(async () => undefined);
    props.projectSettings.loaded!.projectOverrides.accessMode = true;
    props.projectSettings.ports.onInheritProjectOverride = inherit;
    render(settingsView(props, []));

    const projectScope = await screen.findByRole("button", { name: "当前项目" });
    await user.click(projectScope);
    expect(projectScope).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("button", { name: "使用全局设置" }));

    expect(inherit).toHaveBeenCalledExactlyOnceWith("accessMode");
  });

  /** 任一项目准入事实失效立即收回编辑范围；恢复事实只重新开放选择，不恢复旧写入目标。 */
  it.each(["selection", "snapshot", "capabilities"] as const)(
    "drops unavailable project %s before commit and restores it only after an explicit selection",
    async (unavailable) => {
      const user = userEvent.setup();
      const props = settingsViewProps();
      const projectCommits: boolean[] = [];
      const { rerender } = render(settingsView(props, projectCommits));
      const projectButton = await screen.findByRole("button", { name: "当前项目" });
      expect(projectButton).toBeEnabled();
      expect(projectButton).toHaveAttribute("aria-pressed", "false");
      await user.click(projectButton);
      expect(projectButton).toHaveAttribute("aria-pressed", "true");
      expect(screen.getByRole("switch", { name: "交互澄清" })).not.toBeChecked();

      const unavailableProps: SettingsViewProps = {
        ...props,
        selectedProjectId: unavailable === "selection" ? undefined : props.selectedProjectId,
        projectSettings: {
          ...props.projectSettings,
          scopeReady: unavailable !== "snapshot",
          skillSettings: {
            ...props.projectSettings.skillSettings,
            projectAvailable: unavailable !== "capabilities",
          },
        },
      };
      projectCommits.length = 0;
      rerender(settingsView(unavailableProps, projectCommits));
      expect(projectCommits.length).toBeGreaterThan(0);
      expect(projectCommits).not.toContain(true);
      expect(projectButton).toBeDisabled();
      expect(projectButton).toHaveAttribute("aria-pressed", "false");
      expect(screen.getByRole("switch", { name: "交互澄清" })).toBeChecked();
      await user.click(screen.getByRole("switch", { name: "交互澄清" }));
      expect(
        props.settings.userConfigurationPorts.onClarificationEnabledChange,
      ).toHaveBeenCalledExactlyOnceWith(false);
      expect(props.settings.ports.onClarificationEnabledChange).not.toHaveBeenCalled();
      expect(props.projectSettings.ports.onClarificationEnabledChange).not.toHaveBeenCalled();

      rerender(settingsView(props, projectCommits));
      expect(projectButton).toBeEnabled();
      expect(projectButton).toHaveAttribute("aria-pressed", "false");
      expect(screen.getByRole("switch", { name: "交互澄清" })).toBeChecked();
      await user.click(projectButton);
      expect(projectButton).toHaveAttribute("aria-pressed", "true");
      await user.click(screen.getByRole("switch", { name: "交互澄清" }));
      expect(
        props.projectSettings.ports.onClarificationEnabledChange,
      ).toHaveBeenCalledExactlyOnceWith(true);
    },
  );

  /** 未授信项目保持默认状态；取消确认必须止于界面，不触达服务端 mutation。 */
  it("默认保持未信任，取消信任确认不调用 App Server mutation", async () => {
    const user = userEvent.setup();
    const onSetProjectTrust = vi.fn(async () => undefined);
    const props: SettingsViewProps = {
      ...settingsViewProps(),
      projects: [
        {
          workspaceId: "ws_project",
          kind: "project",
          displayName: "Ja",
          rootPath: "C:\\projects\\ja",
          trust: "untrusted",
        },
      ],
      onSetProjectTrust,
    };
    render(settingsView(props, []));

    await user.click(screen.getByRole("button", { name: "信任项目“Ja”" }));
    expect(screen.getByRole("heading", { name: "信任此项目？" })).toBeInTheDocument();
    expect(screen.getByText("C:\\projects\\ja")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "取消" }));

    expect(onSetProjectTrust).not.toHaveBeenCalled();
    expect(screen.queryByRole("heading", { name: "信任此项目？" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "信任项目“Ja”" })).toHaveFocus());
  });

  /** loading 禁用提交按钮会令 WebView2 丢失焦点；失败后须恢复到可重试动作。 */
  it("在项目授权请求等待后失败时把焦点恢复到可重试按钮", async () => {
    const user = userEvent.setup();
    let rejectTrust!: (error: Error) => void;
    const onSetProjectTrust = vi.fn<SettingsViewProps["onSetProjectTrust"]>(
      () =>
        new Promise<void>((_resolve, reject) => {
          rejectTrust = (error) => reject(error);
        }),
    );
    const props: SettingsViewProps = { ...settingsViewProps(), onSetProjectTrust };
    render(settingsView(props, []));

    await user.click(screen.getByRole("button", { name: "撤销项目“Ja”的信任" }));
    const retryButton = screen.getByRole("button", { name: "撤销信任" });
    await user.click(retryButton);
    await waitFor(() =>
      expect(onSetProjectTrust).toHaveBeenCalledExactlyOnceWith("ws_project", "untrusted"),
    );
    expect(retryButton).toBeDisabled();

    await act(async () => rejectTrust(new Error("transport failed")));

    expect(await screen.findByRole("alert")).toHaveTextContent("修改可能已生效");
    expect(screen.getByRole("heading", { name: "撤销项目的信任？" })).toBeInTheDocument();
    await waitFor(() => expect(retryButton).toHaveFocus());
    expect(retryButton).toBeEnabled();
  });

  /** 首次结果不确定时固定同一项目和目标，确认层只在权威回读成功后关闭。 */
  it("信任失败保留同一项目与目标，重试成功前不关闭确认框", async () => {
    const user = userEvent.setup();
    let resolveTrust!: () => void;
    const onSetProjectTrust = vi
      .fn<SettingsViewProps["onSetProjectTrust"]>(async () => undefined)
      .mockRejectedValueOnce(new Error("project reload failed"))
      .mockImplementationOnce(() => new Promise<void>((resolve) => (resolveTrust = resolve)));
    const props: SettingsViewProps = {
      ...settingsViewProps(),
      projects: [
        {
          workspaceId: "ws_project",
          kind: "project",
          displayName: "Ja",
          rootPath: "C:\\projects\\ja",
          trust: "untrusted",
        },
      ],
      onSetProjectTrust,
    };
    render(settingsView(props, []));

    await user.click(screen.getByRole("button", { name: "信任项目“Ja”" }));
    await user.click(screen.getByRole("button", { name: "信任项目" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("修改可能已生效");
    expect(screen.getByRole("heading", { name: "信任此项目？" })).toBeInTheDocument();
    expect(onSetProjectTrust).toHaveBeenNthCalledWith(1, "ws_project", "trusted");

    await user.click(screen.getByRole("button", { name: "信任项目" }));
    expect(screen.getByRole("heading", { name: "信任此项目？" })).toBeInTheDocument();
    await act(async () => resolveTrust());
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "信任此项目？" })).not.toBeInTheDocument(),
    );
    expect(onSetProjectTrust).toHaveBeenNthCalledWith(2, "ws_project", "trusted");
  });

  /** 撤销也要二次确认，避免点击图标后立即改变 MCP 等项目能力边界。 */
  it("撤销信任必须二次确认并把固定项目 ID 与 untrusted 目标交给 owner", async () => {
    const user = userEvent.setup();
    const onSetProjectTrust = vi.fn(async () => undefined);
    const props: SettingsViewProps = { ...settingsViewProps(), onSetProjectTrust };
    render(settingsView(props, []));

    const trustTrigger = await screen.findByRole("button", { name: "撤销项目“Ja”的信任" });
    await user.click(trustTrigger);
    expect(screen.getByRole("heading", { name: "撤销项目的信任？" })).toBeInTheDocument();
    expect(screen.getByText(/后续项目 MCP 调用将被拒绝/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "取消" }));
    expect(onSetProjectTrust).not.toHaveBeenCalled();
    await waitFor(() => expect(trustTrigger).toHaveFocus());

    await user.click(trustTrigger);
    await user.click(screen.getByRole("button", { name: "撤销信任" }));
    await waitFor(() => expect(onSetProjectTrust).toHaveBeenCalledOnce());
    expect(onSetProjectTrust).toHaveBeenCalledExactlyOnceWith("ws_project", "untrusted");
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "撤销项目的信任？" })).not.toBeInTheDocument(),
    );
    await waitFor(() => expect(trustTrigger).toHaveFocus());
  });
});
