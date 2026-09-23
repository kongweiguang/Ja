// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "@/app/App";
import type { SettingsAdapter } from "@/features/settings";
import type { LoadedSettings, SettingsDocument } from "@/api/tauri/settings";
import type {
  HistoryAdapter,
  HistoryThreadUsageReadInput,
  HistoryThreadUsageSummary,
} from "@/api/tauri/history";
import { DEFAULT_WORKBENCH_ADAPTERS } from "@/app/composition/defaultAdapters";
import {
  WORKBENCH_SIZE_DEFAULT,
  useRightPanelSessionStore,
  useUiPreferencesStore,
} from "@/shared/preferences/uiPreferences";
import {
  RuntimeApplicationError,
  type RuntimeHostPort,
  type RuntimeHostEvent,
  type RuntimeStatus,
} from "@/app/application/runtimePorts";

const emptyDocument: SettingsDocument = {
  schemaVersion: 2,
  revision: 0,
  theme: "system",
  defaultAccessMode: "full_access",
  clarificationEnabled: true,
  defaultSelection: null,
  subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
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
function runtime(
  startFailures = 0,
  features: RuntimeStatus["features"] = ["task_threads_v1", "plan_goal_v1"],
): RuntimeHostPort {
  let status: RuntimeStatus = {
    status: "stopped",
    generation: 0,
    serverInstanceId: null,
    features: [],
  };
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
      status = {
        status: "ready",
        generation: 1,
        serverInstanceId: "srv_fixture",
        features,
      };
      return status;
    }),
    stop: vi.fn(async () => {
      status = { status: "stopped", generation: 0, serverInstanceId: null, features: [] };
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
    turnResume: vi.fn(async (input) => ({
      accepted: true as const,
      turnId: input.turnId,
      queued: true,
      threadRevision: input.expectedThreadRevision + 1,
    })),
    // 恢复裁决 mock 只回显 caller 已绑定的 Turn/选择，避免测试替身虚构 Tool 执行事实。
    turnRecoveryRespond: vi.fn(async (input) => ({
      accepted: true as const,
      turnId: input.turnId,
      threadRevision: input.expectedThreadRevision + 1,
      decision: input.decision,
      resumed: false,
    })),
    turnCancel: vi.fn(async (input) => ({
      accepted: true as const,
      turnId: input.turnId,
      status: "cancelled" as const,
      threadRevision: 2,
    })),
    turnInputEnqueue: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: "input_follow_up",
      inputQueue: { turnId: input.turnId, revision: 1, accepting: true, items: [] },
    })),
    turnInputPrioritize: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: input.inputId,
      inputQueue: { turnId: input.turnId, revision: 2, accepting: true, items: [] },
    })),
    turnInputUpdate: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: input.inputId,
      inputQueue: { turnId: input.turnId, revision: 2, accepting: true, items: [] },
    })),
    turnInputDelete: vi.fn(async (input) => ({
      accepted: true as const,
      inputId: input.inputId,
      inputQueue: { turnId: input.turnId, revision: 2, accepting: true, items: [] },
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
function settings(
  document: SettingsDocument = emptyDocument,
  issues: LoadedSettings["issues"] = [],
): SettingsAdapter {
  const loaded: LoadedSettings = {
    document,
    userDocument: document,
    projectOverrides: {
      defaultSelection: false,
      accessMode: false,
      disabledSkillReferences: [],
      disabledMcpIds: [],
    },
    cas: {
      userVersion: "cfg_user_1",
      projectVersion: "cfg_project_1",
      credentialVersion: "cfg_credential_1",
    },
    issues,
  };
  return {
    snapshot: vi.fn(async () => loaded),
    save: vi.fn(async () => "cfg_user_2"),
    saveProjectSkills: vi.fn(async () => "cfg_project_2"),
    patch: vi.fn(async () => ({ version: "cfg_project_2" })),
    reset: vi.fn(async () => ({ version: "cfg_project_2" })),
    restoreLastKnownGood: vi.fn(async () => "cfg_user_2"),
    setCredential: vi.fn(async () => "cfg_credential_2"),
    deleteCredential: vi.fn(async () => "cfg_credential_2"),
    revealProviderCredential: vi.fn(async () => null),
  };
}

/** 统一 App 测试里的 Thread identity，避免 capability 用例用空草稿冒充已加载会话。 */
function threadFixture() {
  return {
    threadId: "thr_fixture",
    workspaceId: generalWorkspace.workspaceId,
    activeGoalId: null,
    preferences: {
      providerId: "provider_fixture",
      modelId: "model_fixture",
      reasoningLevel: "medium" as const,
      accessMode: "approval_required" as const,
      collaborationMode: "default" as const,
      titleSource: "placeholder" as const,
    },
    title: "新对话",
    status: "active" as const,
    pinned: false,
    latestTurnStatus: null,
    latestTurnSeen: true,
    revision: 0,
    createdAt: "2026-08-26T00:00:00Z",
    updatedAt: "2026-08-26T00:00:00Z",
  };
}

/** 组合测试只模拟 App Server 权威查询，不在 Renderer 内派生会话状态。 */
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
    threadCreate: vi.fn(async () => threadFixture()),
    threadRead: vi.fn(async () => ({
      threadId: "thr_fixture",
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      liveStream: null,
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
    threadPin: vi.fn(async () => {
      throw new Error("unused");
    }),
    threadSeen: vi.fn(async () => {
      throw new Error("unused");
    }),
    threadArchive: vi.fn(async () => {
      throw new Error("unused");
    }),
    threadRestore: vi.fn(async () => {
      throw new Error("unused");
    }),
  };
}

/**
 * 首版壳层仍使用完整生产装配，不保留按历史版本分叉的测试入口。App composition 的完整交互
 * 在 Windows CI 中有多个用例超过默认 5 秒；仅提高本文件的 suite 预算到 10 秒，不扩大其它
 * unit test 或产品运行时的 deadline。
 */
describe("Ja desktop shell v1", { timeout: 10_000 }, () => {
  beforeEach(() => {
    localStorage.clear();
    useUiPreferencesStore.setState({
      projectSectionCollapsed: false,
      historySectionCollapsed: false,
      workbenchSize: WORKBENCH_SIZE_DEFAULT,
    });
    useRightPanelSessionStore.setState({ scopes: new Map() });
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

  /** 必填设置独占可见区域，但保留隐藏工作区树以统一主题切换与正常设置导航的挂载语义。 */
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
      expect(screen.getByRole("region", { name: "设置页面" })).toBeInTheDocument(),
    );
    expect(screen.queryByRole("button", { name: "返回应用" })).not.toBeInTheDocument();
    expect(screen.queryByText("runtime/configure")).not.toBeInTheDocument();
    const workspacePanels = container.querySelector<HTMLElement>(".ja-workspace-panels");
    expect(workspacePanels).toHaveAttribute("hidden");
    expect(workspacePanels).toHaveAttribute("aria-hidden", "true");
    expect(container.querySelector(".ja-navigation-sidebar")).toBeNull();
  });

  /** 用户配置语义损坏时仍可进入实际设置页，不能退回不可操作的读取错误屏。 */
  it("keeps settings usable when a configuration issue uses the last known good snapshot", async () => {
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(emptyDocument, [
          {
            id: "cfg_last_known_good",
            scope: "user",
            field: null,
            entityId: null,
            line: null,
            column: null,
            reason: "LAST_KNOWN_GOOD_IN_USE",
            impact: "snapshot_in_use",
            actions: ["edit", "restore"],
          },
        ])}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole("region", { name: "设置页面" })).toBeInTheDocument(),
    );
    expect(screen.getByText("配置有一处格式问题，正在使用上次可用设置。")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "设置暂时不可用" })).not.toBeInTheDocument();
  });

  /** 启动失败不能替换对话，恢复动作必须由左下角状态提供。 */
  it("keeps the conversation visible with a retryable sidebar runtime failure", async () => {
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
      expect(screen.getByRole("button", { name: "运行时异常详情" })).toBeInTheDocument(),
    );
    expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "本地运行时启动失败" })).not.toBeInTheDocument();
    expect(screen.queryByText("正在读取本地设置…")).not.toBeInTheDocument();
    expect(settingsAdapter.snapshot).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "运行时异常详情" }));
    fireEvent.click(screen.getByRole("button", { name: "重新启动" }));
    await waitFor(() =>
      expect(screen.getByRole("region", { name: "设置页面" })).toBeInTheDocument(),
    );
    expect(screen.queryByRole("button", { name: "返回应用" })).not.toBeInTheDocument();
    expect(settingsAdapter.snapshot).toHaveBeenCalledTimes(1);
  });

  /** 延迟启动用于证明首屏不依赖 runtime promise 完成，且发送准入没有被视图改动放开。 */
  it("renders the conversation immediately while runtime startup is pending", () => {
    const runtimeAdapter = runtime();
    runtimeAdapter.start = vi.fn(() => new Promise<RuntimeStatus>(() => undefined));
    render(
      <App runtime={runtimeAdapter} settingsAdapter={settings()} historyAdapter={history()} />,
    );
    expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument();
    expect(screen.getByRole("status", { name: /本地运行时/ })).toBeInTheDocument();
    expect(screen.queryByText("正在启动本地运行时…")).not.toBeInTheDocument();
    expect(screen.queryByText("正在读取本地设置…")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "运行时异常详情" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "消息" })).toBeDisabled();
  });

  /** 人工恢复只在用户打开状态详情后出现，首屏直达不能自动确认恢复。 */
  it("keeps manual recovery behind the sidebar warning", async () => {
    const runtimeAdapter = runtime();
    runtimeAdapter.recoveryState = vi.fn(async () => ({
      required: true,
      acknowledgeable: true,
      recoveryId: "recovery_fixture",
      revision: 1,
    }));
    render(
      <App runtime={runtimeAdapter} settingsAdapter={settings()} historyAdapter={history()} />,
    );
    const warning = await screen.findByRole("button", { name: "运行时异常详情" });
    expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "运行时需要人工恢复" })).not.toBeInTheDocument();
    expect(runtimeAdapter.start).not.toHaveBeenCalled();
    expect(runtimeAdapter.acknowledgeRecovery).not.toHaveBeenCalled();
    fireEvent.click(warning);
    expect(screen.getByRole("heading", { name: "运行时需要人工恢复" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "系统已重启" })).toBeEnabled();
  });

  /** Runtime 未声明 Goal/Plan capability 时不注册相关 Slash action，避免旧 sidecar 出现无效入口。 */
  it("hides Goal and Plan actions before the runtime advertises plan_goal_v1", async () => {
    render(
      <App
        runtime={runtime(0, [])}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );
    const input = await screen.findByRole("textbox", { name: "消息" });
    fireEvent.change(input, { target: { value: "/" } });
    expect(screen.queryByRole("group", { name: "添加" })).not.toBeInTheDocument();
  });

  /**
   * 用量读取器只拿到 History 的窄能力，但不能丢失 class adapter 的 `this`；生产实现通过
   * `this.bridge` 发起 typed IPC，本回归用同样的 receiver 约束阻止组合层解构方法后裸调用。
   */
  it("binds the History usage reader before handing it to the Composer", async () => {
    const user = userEvent.setup();
    const historyAdapter = history();
    const usage: HistoryThreadUsageSummary = {
      threadId: "thr_fixture",
      snapshotRevision: 1,
      requestCount: 1,
      measuredRequestCount: 1,
      newInputRequestCount: 1,
      newInputTokens: 20,
      outputRequestCount: 1,
      outputTokens: 12,
      totalRequestCount: 1,
      totalTokens: 32,
      cacheReadRequestCount: 1,
      cacheReadTokens: 0,
      cacheWriteRequestCount: 1,
      cacheWriteTokens: 0,
      cacheCompleteRequestCount: 1,
      cacheCompleteInputTokens: 20,
      cacheCompleteReadTokens: 0,
    };
    const readUsage = vi.fn(function (this: HistoryAdapter, input: HistoryThreadUsageReadInput) {
      return Promise.resolve({ ...usage, threadId: input.threadId });
    });
    historyAdapter.threadUsageRead = readUsage;
    historyAdapter.threadList = vi.fn(async () => ({
      items: [
        {
          ...threadFixture(),
          preferences: {
            ...threadFixture().preferences,
            providerId: "provider_openai",
            modelId: "model_gpt",
            reasoningLevel: "high" as const,
          },
        },
      ],
      nextCursor: null,
    }));
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    const trigger = await screen.findByRole("button", { name: "上下文用量详情" });
    await user.click(trigger);
    await waitFor(() => expect(readUsage).toHaveBeenCalledWith({ threadId: "thr_fixture" }));
    expect(readUsage.mock.contexts[0]).toBe(historyAdapter);
    expect(await screen.findByText("Token · 本会话")).toBeVisible();
  });

  /** Runtime 明确声明 Goal/Plan capability 后，Slash 面板才公开对应的真实操作入口。 */
  it("shows Goal and Plan actions after the runtime advertises plan_goal_v1", async () => {
    const historyAdapter = history();
    historyAdapter.threadList = vi.fn(async () => ({ items: [threadFixture()], nextCursor: null }));
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );
    await screen.findByRole("combobox", { name: "访问模式" });
    const input = screen.getByRole("textbox", { name: "消息" });
    fireEvent.change(input, { target: { value: "/" } });
    const group = await screen.findByRole("group", { name: "添加" });
    expect(group).toHaveTextContent("计划");
    expect(group).toHaveTextContent("目标");
    expect(group).toHaveTextContent("/plan");
    expect(group).toHaveTextContent("/goal");
  });

  /**
   * 完整 App composition 通过真实 Runtime event 产生 stream gap，再让 controller 发起挂起的
   * thread/read；后台恢复期间既有历史行、新会话入口和 Composer 都必须保持稳定，且完成快照
   * 后不得由 recovered-Turn timer 再次读取。该测试不直接改 Navigation props，避免 browser fixture
   * 用固定 `newConversationDisabled=false` 掩盖 composition 的 busy 投影错误。
   */
  it("keeps conversation actions stable while an active stream is resynchronized", async () => {
    const user = userEvent.setup();
    const runtimeHarness = runtimeWithEvents();
    const historyAdapter = history();
    const storedThread = {
      ...threadFixture(),
      preferences: {
        ...threadFixture().preferences,
        providerId: "provider_openai",
        modelId: "model_gpt",
        reasoningLevel: "high" as const,
        accessMode: "full_access" as const,
      },
    };
    const emptySnapshot = () => ({
      threadId: storedThread.threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      liveStream: null,
      nextCursor: null,
    });
    const completedSnapshot = () => ({
      ...emptySnapshot(),
      revision: 4,
      turns: [
        {
          turnId: "turn_fixture",
          status: "completed" as const,
          requestedAt: "2026-09-23T00:00:00Z",
          updatedAt: "2026-09-23T00:00:04Z",
          completedAt: "2026-09-23T00:00:04Z",
          errorCode: null,
          changeSet: null,
        },
      ],
    });
    let holdNextRead = false;
    let backgroundReadPending = false;
    let releaseRead: (() => void) | undefined;
    const backgroundRead = new Promise<void>((resolve) => {
      releaseRead = resolve;
    });
    historyAdapter.threadList = vi.fn(async () => ({
      items: [storedThread],
      nextCursor: null,
    }));
    const threadReadMock = vi.fn(async () => {
      if (holdNextRead) {
        holdNextRead = false;
        backgroundReadPending = true;
        await backgroundRead;
        return completedSnapshot();
      }
      return emptySnapshot();
    });
    historyAdapter.threadRead = threadReadMock;
    render(
      <App
        runtime={runtimeHarness.port}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    const input = await screen.findByRole("textbox", { name: "消息" });
    await waitFor(() => expect(input).toBeEnabled());
    const newConversation = screen.getByRole("button", { name: "新会话" });
    await waitFor(() => expect(newConversation).toBeEnabled());
    expect(screen.queryByLabelText("正在读取会话")).not.toBeInTheDocument();

    await user.type(input, "触发活动流恢复验收");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(runtimeHarness.port.turnStart).toHaveBeenCalledTimes(1));
    const readCountBeforeResync = threadReadMock.mock.calls.length;
    holdNextRead = true;
    act(() => {
      runtimeHarness.emit({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/state-changed",
          params: {
            serverInstanceId: "srv_fixture",
            eventId: "evt_active_running",
            sequence: 1,
            occurredAt: "2026-09-23T00:00:01Z",
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
      runtimeHarness.emit({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "assistant/text-delta",
          params: {
            serverInstanceId: "srv_fixture",
            eventId: "evt_active_gap",
            sequence: 2,
            occurredAt: "2026-09-23T00:00:02Z",
            generation: 1,
            workspaceId: generalWorkspace.workspaceId,
            threadId: storedThread.threadId,
            turnId: "turn_fixture",
            threadRevision: 3,
            streamSeq: 2,
            text: "活动流恢复前的公开增量",
          },
        },
      });
    });
    await waitFor(() => expect(backgroundReadPending).toBe(true));
    expect(threadReadMock.mock.calls.length).toBeGreaterThan(readCountBeforeResync);
    expect(newConversation).toBeEnabled();
    expect(input).toBeEnabled();
    expect(screen.queryByLabelText("正在读取会话")).not.toBeInTheDocument();

    releaseRead?.();
    const readCountDuringBackground = threadReadMock.mock.calls.length;
    await waitFor(() => expect(threadReadMock.mock.calls.length).toBe(readCountDuringBackground));
    await waitFor(() => expect(newConversation).toBeEnabled());
    await waitFor(() => expect(input).toBeEnabled());
    const readCountAfterTerminal = threadReadMock.mock.calls.length;
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(threadReadMock.mock.calls.length).toBe(readCountAfterTerminal);
  });

  /** 完整 App 接线只在当前对话正文展示后台恢复诊断，历史栏不重复插入第二个 alert。 */
  it("shows a background recovery error only in the current conversation body", async () => {
    const user = userEvent.setup();
    const runtimeHarness = runtimeWithEvents();
    const historyAdapter = history();
    const storedThread = {
      ...threadFixture(),
      preferences: {
        ...threadFixture().preferences,
        providerId: "provider_openai",
        modelId: "model_gpt",
        reasoningLevel: "high" as const,
        accessMode: "full_access" as const,
      },
    };
    const emptySnapshot = {
      threadId: storedThread.threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      liveStream: null,
      nextCursor: null,
    };
    historyAdapter.threadList = vi.fn(async () => ({
      items: [storedThread],
      nextCursor: null,
    }));
    historyAdapter.threadRead = vi
      .fn<HistoryAdapter["threadRead"]>()
      .mockResolvedValueOnce(emptySnapshot)
      .mockRejectedValueOnce(new Error("temporary background read failure"));
    render(
      <App
        runtime={runtimeHarness.port}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    const input = await screen.findByRole("textbox", { name: "消息" });
    await waitFor(() => expect(input).toBeEnabled());
    const newConversation = screen.getByRole("button", { name: "新会话" });
    await waitFor(() => expect(newConversation).toBeEnabled());

    await user.type(input, "触发后台恢复错误");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(runtimeHarness.port.turnStart).toHaveBeenCalledTimes(1));
    act(() => {
      runtimeHarness.emit({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/state-changed",
          params: {
            serverInstanceId: "srv_fixture",
            eventId: "evt_background_running",
            sequence: 1,
            occurredAt: "2026-09-23T00:00:01Z",
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
      runtimeHarness.emit({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "assistant/text-delta",
          params: {
            serverInstanceId: "srv_fixture",
            eventId: "evt_background_gap",
            sequence: 2,
            occurredAt: "2026-09-23T00:00:02Z",
            generation: 1,
            workspaceId: generalWorkspace.workspaceId,
            threadId: storedThread.threadId,
            turnId: "turn_fixture",
            threadRevision: 3,
            streamSeq: 2,
            text: "触发后台错误",
          },
        },
      });
    });

    await waitFor(() => expect(historyAdapter.threadRead).toHaveBeenCalledTimes(2));
    const alerts = await screen.findAllByRole("alert");
    expect(alerts).toHaveLength(1);
    expect(alerts[0]).toHaveTextContent("会话状态暂时无法自动恢复");
    expect(newConversation).toBeEnabled();
    expect(input).toBeEnabled();
  });

  it("在搜索弹窗打开时将标题元数据事件转为静默刷新 identity", async () => {
    const runtimeHarness = runtimeWithEvents();
    const historyAdapter = history();
    let searchTitle = "首问短标题";
    const storedThread = {
      threadId: "thr_fixture",
      workspaceId: generalWorkspace.workspaceId,
      activeGoalId: null,
      preferences: {
        providerId: "provider_openai",
        modelId: "model_gpt",
        reasoningLevel: "high" as const,
        accessMode: "full_access" as const,
        collaborationMode: "default" as const,
        titleSource: "placeholder" as const,
      },
      title: searchTitle,
      status: "active" as const,
      pinned: false,
      latestTurnStatus: "completed" as const,
      latestTurnSeen: true,
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
    await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toBeEnabled());
    searchButton.click();
    expect(await screen.findByRole("option", { name: "打开：首问短标题" })).toBeVisible();
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

  /**
   * 项目选择与 Settings 编辑层必须相互独立：即使设置页仍默认编辑全局层，Shell 也要先读取
   * 目标项目 effective 配置；项目空 Thread 就绪后再次触发新会话必须复用当前 identity，
   * 并把焦点交给输入框而不是留在侧栏动作上。
   */
  it("selects a persisted project and reuses its current empty conversation", async () => {
    const projectWorkspace = {
      workspaceId: "ws_project_a",
      root: "C:\\dev\\rust\\ja",
      displayName: "ja",
      trust: "trusted" as const,
      revision: 1,
    };
    const threadPreferences = {
      providerId: "provider_openai",
      modelId: "model_gpt",
      reasoningLevel: "high" as const,
      accessMode: "full_access" as const,
      collaborationMode: "default" as const,
      titleSource: "placeholder" as const,
    };
    const generalThread = {
      threadId: "thr_general_existing",
      workspaceId: generalWorkspace.workspaceId,
      activeGoalId: null,
      preferences: threadPreferences,
      title: "无项目会话",
      status: "active" as const,
      pinned: false,
      latestTurnStatus: "completed" as const,
      latestTurnSeen: true,
      revision: 0,
      createdAt: "2026-08-31T00:00:00Z",
      updatedAt: "2026-08-31T00:00:00Z",
    };
    const projectThread = {
      ...generalThread,
      threadId: "thr_project_existing",
      workspaceId: projectWorkspace.workspaceId,
      title: "项目已有会话",
    };
    const secondaryProjectThread = {
      ...projectThread,
      threadId: "thr_project_secondary",
      title: "项目另一个会话",
      updatedAt: "2026-08-30T23:59:00Z",
    };
    const historyAdapter = history();
    let resolveWorkspaceList: (() => void) | undefined;
    const workspaceListReady = new Promise<void>((resolve) => {
      resolveWorkspaceList = resolve;
    });
    historyAdapter.workspaceList = vi.fn(async () => {
      const result = { items: [projectWorkspace], nextCursor: null };
      resolveWorkspaceList?.();
      return result;
    });
    historyAdapter.workspaceOpen = vi.fn(async () => projectWorkspace);
    historyAdapter.threadList = vi.fn(async ({ workspaceId }) => ({
      items:
        workspaceId === projectWorkspace.workspaceId
          ? [projectThread, secondaryProjectThread]
          : [generalThread],
      nextCursor: null,
    }));
    historyAdapter.threadCreate = vi.fn(async () => projectThread);
    historyAdapter.threadRead = vi.fn(async ({ threadId }) => ({
      threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      liveStream: null,
      nextCursor: null,
    }));
    const settingsAdapter = settings(configuredDocument);
    // 项目切换仍执行真实资源事务边界；测试只替换会触发 native IPC 的资源回收 ACK。
    const terminalAdapter = Object.create(
      DEFAULT_WORKBENCH_ADAPTERS.terminal,
    ) as typeof DEFAULT_WORKBENCH_ADAPTERS.terminal;
    terminalAdapter.closeAll = vi.fn(async () => undefined);
    const previewAdapter = Object.create(
      DEFAULT_WORKBENCH_ADAPTERS.preview,
    ) as typeof DEFAULT_WORKBENCH_ADAPTERS.preview;
    previewAdapter.recoverPending = vi.fn(async () => ({
      observed: 0,
      recovered: 0,
      failed: 0,
      pending: 0,
    }));
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settingsAdapter}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
        workbenchAdapters={{
          ...DEFAULT_WORKBENCH_ADAPTERS,
          terminal: terminalAdapter,
          preview: previewAdapter,
        }}
      />,
    );

    // 先等待真实目录 adapter 返回，再让 React 收口异步 catalog 状态，避免把并行负载误判为无项目。
    await act(async () => {
      await workspaceListReady;
    });
    const projectButton = screen.getByRole("button", { name: "切换到项目：ja" });
    await waitFor(() => expect(projectButton).toBeEnabled());
    fireEvent.click(projectButton);
    await waitFor(() =>
      expect(historyAdapter.workspaceOpen).toHaveBeenCalledWith({
        cwd: projectWorkspace.root,
        displayName: projectWorkspace.displayName,
      }),
    );
    expect(terminalAdapter.closeAll).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(settingsAdapter.snapshot).toHaveBeenLastCalledWith({
        workspaceId: projectWorkspace.workspaceId,
      }),
    );
    await screen.findByRole("button", { name: "当前项目：ja" });
    const newConversationButton = screen.getByRole("button", { name: "新会话" });
    await waitFor(() => expect(newConversationButton).toBeEnabled());
    const recentThreads = screen.getByRole("list", { name: "最近对话列表" });
    const threadCountBefore = recentThreads.querySelectorAll("button[data-thread-id]").length;

    await act(async () => {
      fireEvent.click(newConversationButton);
      await Promise.resolve();
    });

    expect(historyAdapter.threadCreate).not.toHaveBeenCalled();
    expect(historyAdapter.threadRead).toHaveBeenLastCalledWith({
      threadId: projectThread.threadId,
    });
    expect(recentThreads.querySelectorAll("button[data-thread-id]")).toHaveLength(
      threadCountBefore,
    );
    await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());

    const secondaryConversationButton = screen.getByRole("button", {
      name: secondaryProjectThread.title,
    });
    secondaryConversationButton.focus();
    expect(secondaryConversationButton).toHaveFocus();
    fireEvent.click(secondaryConversationButton);
    await waitFor(() =>
      expect(historyAdapter.threadRead).toHaveBeenLastCalledWith({
        threadId: secondaryProjectThread.threadId,
      }),
    );
    await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());

    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    expect(await screen.findByRole("region", { name: "设置页面" })).toBeVisible();
    fireEvent.click(await screen.findByRole("button", { name: "返回应用" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());
  });

  /**
   * 普通对话打开真实 Workbench 并提交可持久宽度；进入设置时只隐藏同一 DOM 子树，
   * 返回后仍复用原节点，避免主题切换通过卸载重建 Editor、xterm 或 PTY owner；能力入口
   * 采用真实 Radix 菜单交互，不再依赖已退出主流程的整页启动器。Windows CI 中该完整交互约
   * 需 6 秒，因此使用局部 10 秒预算，不改变全局测试 deadline。
   */
  it("joins general conversation with a resizable workbench", async () => {
    const user = userEvent.setup();
    const historyAdapter = history();
    historyAdapter.threadList = vi.fn(async () => ({
      items: [threadFixture()],
      nextCursor: null,
    }));
    const { container } = render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "显示工作区面板" })).toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(historyAdapter.threadRead).toHaveBeenCalledWith({ threadId: "thr_fixture" }),
    );
    screen.getByRole("button", { name: "显示工作区面板" }).click();
    const workspacePanels = container.querySelector<HTMLElement>("#ja-workspace-layout");
    const conversationPanel = container.querySelector<HTMLElement>("#conversation");
    const workbenchPanel = container.querySelector<HTMLElement>("#workbench");

    await waitFor(() => expect(workbenchPanel).not.toHaveAttribute("hidden"));
    const activeWorkbenchSession = workbenchPanel?.querySelector<HTMLElement>(
      ".ja-thread-workbench-session:not([hidden])",
    );
    expect(workspacePanels).toHaveAttribute("data-layout-mode", "split");
    expect(workspacePanels?.style.getPropertyValue("--ja-workbench-size")).toBe("33.725%");
    expect(conversationPanel).toBeVisible();
    expect(activeWorkbenchSession?.querySelector('[data-workbench-tab="new"]')).toBeVisible();
    expect(activeWorkbenchSession?.querySelector('[data-workbench-tab="files"]')).toBeNull();

    const separator = screen.getByRole("separator", { name: "调整工作台宽度" });
    expect(separator).toHaveAttribute("aria-valuenow", "33.725");
    fireEvent.keyDown(separator, { key: "ArrowLeft" });
    await waitFor(() =>
      expect(workspacePanels?.style.getPropertyValue("--ja-workbench-size")).toBe("34.225%"),
    );
    expect(useUiPreferencesStore.getState().workbenchSize).toBe(34.225);

    await user.click(screen.getByRole("button", { name: "新建标签页" }));
    expect(await screen.findByRole("menu")).toBeVisible();
    expect(screen.getByRole("menuitem", { name: /终端/u })).toBeVisible();
    await user.keyboard("{Escape}");

    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    expect(await screen.findByRole("region", { name: "设置页面" })).toBeVisible();
    expect(workspacePanels).toHaveAttribute("hidden");
    expect(container.querySelector("#workbench")).toBe(workbenchPanel);

    fireEvent.click(await screen.findByRole("button", { name: "返回应用" }));
    await waitFor(() => expect(workspacePanels).not.toHaveAttribute("hidden"));
    expect(container.querySelector("#workbench")).toBe(workbenchPanel);
  });

  /**
   * 真实 App composition 必须把 Workspace 引用交给现有 Files 读取链；关闭单文件 Tab 后
   * 返回来源卡，证明引用没有被伪装成上传附件或只停留在点击 intent。
   */
  it("opens a Composer workspace reference in Files and restores focus after closing it", async () => {
    const user = userEvent.setup();
    const runtimePort = runtime();
    runtimePort.query = vi.fn(async (method, params) => {
      if (method === "workspace/path/search")
        return {
          threadId: "thr_fixture",
          workspaceId: generalWorkspace.workspaceId,
          generation: 1,
          query: (params as { query: string }).query,
          items: [{ relativePath: "src/main.ts", kind: "file" as const }],
          truncated: false,
        };
      return { items: [], nextCursor: null };
    }) as RuntimeHostPort["query"];
    const historyAdapter = history();
    historyAdapter.threadList = vi.fn(async () => ({
      items: [
        {
          ...threadFixture(),
          preferences: {
            ...threadFixture().preferences,
            providerId: "provider_openai",
            modelId: "model_gpt",
            reasoningLevel: "high" as const,
          },
        },
      ],
      nextCursor: null,
    }));
    const revision = {
      kind: "file" as const,
      size: 18,
      modifiedUnixMillis: 1,
      sha256: "sha-main",
    };
    const readFile = vi.fn(async () => ({
      metadata: { kind: "file" as const, size: 18, modifiedUnixMillis: 1, revision },
      kind: "text" as const,
      encoding: "utf8" as const,
      lineEnding: "lf" as const,
      text: "export const ok = 1;",
      bytesRead: 18,
      truncated: false,
    }));
    const workspaceAdapter = {
      ...DEFAULT_WORKBENCH_ADAPTERS.workspace,
      tree: vi.fn(async () => ({
        entries: [],
        directoryRevision: { ...revision, kind: "directory" as const, size: 0 },
        nextCursor: null,
        snapshotToken: "tree-1",
        totalEntries: 0,
        depth: 0,
      })),
      readFile,
      openTargets: vi.fn(async () => ({ targets: [] })),
      watchStart: vi.fn(async ({ generation }: { generation: number }) => ({
        started: true,
        generation,
      })),
      watchRescan: vi.fn(async ({ generation }: { generation: number }) => ({
        generation,
        requiresRescan: false,
        emittedPaths: 0,
      })),
      watchStop: vi.fn(async () => ({ stopped: true })),
      subscribeChanged: vi.fn(async () => () => undefined),
      subscribeNativeDrop: vi.fn(async () => () => undefined),
    };
    render(
      <App
        runtime={runtimePort}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
        workbenchAdapters={{ ...DEFAULT_WORKBENCH_ADAPTERS, workspace: workspaceAdapter }}
      />,
    );

    await screen.findByRole("combobox", { name: "访问模式" });
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    await user.type(composer, "@src");
    await user.click(await screen.findByRole("option", { name: /main\.ts/u }));
    const source = screen.getByRole("button", { name: "在文件中预览 main.ts" });
    fireEvent.click(source);
    await waitFor(() =>
      expect(readFile).toHaveBeenCalledWith({
        workspaceId: generalWorkspace.workspaceId,
        relativePath: "src/main.ts",
      }),
    );
    const closeFile = await screen.findByRole("button", { name: "关闭 main.ts" });
    fireEvent.click(closeFile);
    await waitFor(() => expect(source).toHaveFocus());
  });
});
