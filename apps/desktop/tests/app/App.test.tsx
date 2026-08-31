// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "@/app/App";
import type { SettingsAdapter } from "@/features/settings";
import type { LoadedSettings, SettingsDocument } from "@/api/tauri/settings";
import type { HistoryAdapter } from "@/api/tauri/history";
import { WORKBENCH_SIZE_DEFAULT, useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import {
  RuntimeApplicationError,
  type RuntimeHostPort,
  type RuntimeHostEvent,
  type RuntimeStatus,
} from "@/app/application/runtimePorts";

const emptyDocument: SettingsDocument = {
  schemaVersion: 4,
  revision: 0,
  theme: "system",
  defaultAccessMode: "full_access",
  defaultSelection: null,
  providers: [],
  mcpServers: [],
  skills: [],
  window: { width: 1280, height: 800, maximized: false },
};
const configuredDocument: SettingsDocument = {
  ...emptyDocument,
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
      ],
    },
  ],
};
const generalWorkspace = {
  workspaceId: "ws_runtime_a" as const,
  displayName: "无项目" as const,
  trust: "trusted" as const,
  rootPath: "C:\\data\\ja\\general-workspace",
};

/** 构造前 N 次启动失败的 lifecycle adapter，用于验证真实 ready generation 发布前的恢复路径。 */
function runtime(startFailures = 0): RuntimeHostPort {
  let status: RuntimeStatus = { status: "stopped", generation: 0, serverInstanceId: null };
  let remainingStartFailures = startFailures;
  return {
    recoveryState: vi.fn(async () => ({
      required: false,
      acknowledgeable: false,
      recoveryId: null,
      revision: null,
    })),
    subscribe: vi.fn(async () => () => undefined),
    state: vi.fn(async () => status),
    start: vi.fn(async () => {
      if (remainingStartFailures > 0) {
        remainingStartFailures -= 1;
        throw new RuntimeApplicationError("RUNTIME_UNAVAILABLE", "Ja App Server 启动失败", true);
      }
      status = { status: "ready", generation: 1, serverInstanceId: "srv_fixture" };
      return status;
    }),
    stop: vi.fn(async () => {
      status = { status: "stopped", generation: 0, serverInstanceId: null };
      return status;
    }),
    storageInfo: vi.fn(async () => ({
      nativeImage: false,
      dataPath: "C:\\data\\ja",
      logPath: null,
      cachePath: null,
      lastBackup: null,
    })),
    generalWorkspace: vi.fn(async () => generalWorkspace),
    turnStart: vi.fn(async () => ({
      accepted: true as const,
      turnId: "turn_fixture",
      queued: false,
      threadRevision: 1,
    })),
    turnCancel: vi.fn(async (input) => ({
      accepted: true as const,
      turnId: input.turnId,
      status: "cancelled" as const,
      threadRevision: input.expectedThreadRevision + 1,
    })),
    turnSteer: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: "input_steer",
      turnId: input.turnId,
      kind: "steering" as const,
      status: "queued" as const,
    })),
    turnFollowUp: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: "input_follow_up",
      turnId: input.turnId,
      kind: "follow_up" as const,
      status: "queued" as const,
    })),
    approvalRespond: vi.fn(async () => undefined),
    query: (async () => ({ items: [], nextCursor: null })) as RuntimeHostPort["query"],
    acknowledgeRecovery: vi.fn(async () => ({
      required: false,
      acknowledgeable: false,
      recoveryId: null,
      revision: null,
    })),
  };
}

/**
 * 在真实 App composition 测试中保留 Runtime 订阅边界，使标题事件必须经过
 * RuntimeProvider 和 JaApplication，而不会由测试直接改写 Dialog props。
 */
function runtimeWithEvents(): {
  port: RuntimeHostPort;
  emit(event: RuntimeHostEvent): void;
} {
  const port = runtime();
  const listeners = new Set<(event: RuntimeHostEvent) => void>();
  port.subscribe = vi.fn(async (listener) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  });
  return {
    port,
    emit: (event) => listeners.forEach((listener) => listener(event)),
  };
}

/** 返回指定 UI 文档的脱敏 Settings adapter，避免 Shell 测试绕过真实配置门禁。 */
function settings(document: SettingsDocument = emptyDocument): SettingsAdapter {
  const loaded: LoadedSettings = {
    document,
    userDocument: document,
    projectOverrides: {
      defaultSelection: false,
      accessMode: false,
      disabledSkillIds: [],
      disabledMcpIds: [],
    },
    source: "Primary",
    recovered: false,
    cas: {
      userVersion: "cfg_user_1",
      projectVersion: "cfg_project_1",
      credentialVersion: "cfg_credential_1",
    },
  };
  return {
    snapshot: vi.fn(async () => loaded),
    save: vi.fn(async () => "cfg_user_2"),
    patch: vi.fn(async () => ({ version: "cfg_project_2" })),
    reset: vi.fn(async () => ({ version: "cfg_project_2" })),
    setCredential: vi.fn(async () => "cfg_credential_2"),
    deleteCredential: vi.fn(async () => "cfg_credential_2"),
  };
}

function history(): HistoryAdapter {
  return {
    workspaceList: vi.fn(async () => ({ items: [], nextCursor: null })),
    threadList: vi.fn(async () => ({ items: [], nextCursor: null })),
    threadSearch: vi.fn(async () => ({ items: [], nextCursor: null })),
    threadRename: vi.fn(async () => {
      throw new Error("unused");
    }),
    threadPreferencesUpdate: vi.fn(async () => {
      throw new Error("unused");
    }),
    threadCreate: vi.fn(async () => ({
      threadId: "thr_fixture",
      workspaceId: generalWorkspace.workspaceId,
      preferences: {
        providerId: "provider_fixture",
        modelId: "model_fixture",
        reasoningLevel: "medium" as const,
        accessMode: "approval_required" as const,
        titleSource: "placeholder" as const,
      },
      title: "新对话",
      status: "active" as const,
      revision: 0,
      createdAt: "2026-08-26T00:00:00Z",
      updatedAt: "2026-08-26T00:00:00Z",
    })),
    threadRead: vi.fn(async () => ({
      threadId: "thr_fixture",
      revision: 0,
      turns: [],
      items: [],
      contextUsage: null,
      nextCursor: null,
    })),
    threadCompact: vi.fn(async (input) => ({
      outcome: "unchanged" as const,
      compactionId: null,
      checkpointId: null,
      threadRevision: input.expectedThreadRevision,
      inputTokensBefore: 0,
      inputTokensAfter: 0,
    })),
  };
}

describe("Ja desktop shell v2", () => {
  beforeEach(() => {
    localStorage.clear();
    useUiPreferencesStore.setState({
      inspectorOpen: false,
      projectSectionCollapsed: false,
      historySectionCollapsed: false,
      workbenchSize: WORKBENCH_SIZE_DEFAULT,
      rightPanelTab: "files",
      rightPanelTabs: ["review", "files", "preview"],
    });
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      }),
    });
  });

  afterEach(() => cleanup());

  it("keeps Settings reachable with an empty app-server-owned configuration", async () => {
    const { container } = render(
      <App
        runtime={runtime()}
        settingsAdapter={settings()}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "先配置一个模型" })).toBeInTheDocument(),
    );
    expect(screen.queryByText("runtime/configure")).not.toBeInTheDocument();
    const workspacePanels = container.querySelector<HTMLElement>(".ja-workspace-panels");
    expect(workspacePanels).toBeNull();
    expect(container.querySelector(".ja-navigation-sidebar")).toBeNull();
  });

  it("shows a retryable runtime failure instead of an endless settings loader", async () => {
    const runtimeAdapter = runtime(1);
    const settingsAdapter = settings();
    render(
      <App
        runtime={runtimeAdapter}
        settingsAdapter={settingsAdapter}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "本地运行时启动失败" })).toBeInTheDocument(),
    );
    expect(screen.queryByText("正在读取本地设置…")).not.toBeInTheDocument();
    expect(settingsAdapter.snapshot).not.toHaveBeenCalled();

    screen.getByRole("button", { name: "重新启动" }).click();
    await waitFor(() =>
      expect(screen.getByRole("heading", { name: "先配置一个模型" })).toBeInTheDocument(),
    );
    expect(settingsAdapter.snapshot).toHaveBeenCalledTimes(1);
  });

  it("在搜索弹窗打开时将标题元数据事件转为静默刷新 identity", async () => {
    const runtimeHarness = runtimeWithEvents();
    const historyAdapter = history();
    let searchTitle = "首问短标题";
    const storedThread = {
      threadId: "thr_fixture",
      workspaceId: generalWorkspace.workspaceId,
      preferences: {
        providerId: "provider_openai",
        modelId: "model_gpt",
        reasoningLevel: "high" as const,
        accessMode: "full_access" as const,
        titleSource: "placeholder" as const,
      },
      title: searchTitle,
      status: "active" as const,
      revision: 1,
      createdAt: "2026-08-31T00:00:00Z",
      updatedAt: "2026-08-31T00:00:00Z",
    };
    historyAdapter.threadList = vi.fn(async () => ({ items: [storedThread], nextCursor: null }));
    historyAdapter.threadSearch = vi.fn(async () => ({
      items: [
        { ...storedThread, title: searchTitle, revision: searchTitle === "首问短标题" ? 1 : 2 },
      ],
      nextCursor: null,
    }));
    render(
      <App
        runtime={runtimeHarness.port}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    const searchButton = await screen.findByRole("button", { name: "搜索对话" });
    searchButton.click();
    expect(await screen.findByRole("option", { name: "首问短标题" })).toBeVisible();
    expect(historyAdapter.threadSearch).toHaveBeenCalledTimes(1);
    const searchInput = screen.getByRole("searchbox", { name: "搜索对话" });
    fireEvent.change(searchInput, { target: { value: "标题" } });
    await waitFor(() => expect(historyAdapter.threadSearch).toHaveBeenCalledTimes(2));
    expect(searchInput).toHaveFocus();

    searchTitle = "智能总结标题";
    act(() => {
      runtimeHarness.emit({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "thread/metadata-changed",
          params: {
            serverInstanceId: "srv_fixture",
            eventId: "evt_title_refresh",
            sequence: 1,
            occurredAt: "2026-08-31T00:00:01Z",
            generation: 1,
            workspaceId: generalWorkspace.workspaceId,
            threadId: storedThread.threadId,
            revision: 2,
            title: searchTitle,
            titleSource: "auto",
          },
        },
      });
      // admission 后的运行事件可能与标题事件落入同一 React 批次；它不能吞掉独立的标题刷新 identity。
      runtimeHarness.emit({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/state-changed",
          params: {
            serverInstanceId: "srv_fixture",
            eventId: "evt_turn_running",
            sequence: 2,
            occurredAt: "2026-08-31T00:00:02Z",
            generation: 1,
            workspaceId: generalWorkspace.workspaceId,
            threadId: storedThread.threadId,
            turnId: "turn_fixture",
            threadRevision: 2,
            from: "queued",
            to: "running",
          },
        },
      });
    });

    const refreshedOption = await screen.findByRole("option", { name: /智能总结.*标题/ });
    expect(refreshedOption).toHaveTextContent("智能总结标题");
    expect(refreshedOption).toBeVisible();
    expect(historyAdapter.threadSearch).toHaveBeenCalledTimes(3);
    expect(searchInput).toHaveValue("标题");
    expect(searchInput).toHaveFocus();
  });

  /** 普通对话打开真实 Workbench，并通过唯一分隔器提交可持久宽度。 */
  it("joins general conversation with a resizable workbench", async () => {
    const { container } = render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "显示工作区面板" })).toBeInTheDocument(),
    );
    screen.getByRole("button", { name: "显示工作区面板" }).click();
    const workspacePanels = container.querySelector<HTMLElement>("#ja-workspace-layout");
    const conversationPanel = container.querySelector<HTMLElement>("#conversation");
    const workbenchPanel = container.querySelector<HTMLElement>("#workbench");

    await waitFor(() => expect(workbenchPanel).not.toHaveAttribute("hidden"));
    expect(workspacePanels).toHaveAttribute("data-layout-mode", "split");
    expect(workspacePanels?.style.getPropertyValue("--ja-workbench-size")).toBe("33.725%");
    expect(conversationPanel).toBeVisible();
    expect(workbenchPanel?.querySelector('[data-workbench-tab="files"]')).toBeVisible();
    expect(workbenchPanel?.querySelector('[data-workbench-tab="preview"]')).toBeVisible();

    const separator = screen.getByRole("separator", { name: "调整工作台宽度" });
    expect(separator).toHaveAttribute("aria-valuenow", "33.725");
    fireEvent.keyDown(separator, { key: "ArrowLeft" });
    await waitFor(() =>
      expect(workspacePanels?.style.getPropertyValue("--ja-workbench-size")).toBe("34.225%"),
    );
    expect(useUiPreferencesStore.getState().workbenchSize).toBe(34.225);

    fireEvent.click(screen.getByRole("button", { name: "新建标签页" }));
    expect(await screen.findByRole("heading", { name: "打开工作区工具" })).toBeVisible();
    expect(screen.getByRole("button", { name: /终端/u })).toBeVisible();
  });
});
