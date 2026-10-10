// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "@/app/App";
import { useTimelineStore } from "@/features/conversation";
import type { SettingsAdapter } from "@/features/settings";
import type { LoadedSettings, SettingsDocument } from "@/api/tauri/settings";
import { RuntimeHostError } from "@/api/tauri/runtime";
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

/** App 组合测试保留真实 Terminal controller 与所有权适配器，只替换依赖浏览器几何的 xterm 叶视图。 */
vi.mock("@/features/workbench/terminal/ui/TerminalPanel", () => {
  /** 提供稳定可访问的终端表面，让测试聚焦 PTY 打开和关闭 ACK。 */
  function TerminalPanelMock({ ariaLabel }: { ariaLabel?: string }) {
    return <div role="region" aria-label={ariaLabel} data-testid="terminal-panel" />;
  }

  return { TerminalPanel: TerminalPanelMock };
});

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
  disabledSkills: [],
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
      ],
    },
  ],
};
const sessionWorkspace = {
  workspaceId: "ws_runtime_a" as const,
  kind: "session" as const,
  displayName: "会话测试目录" as const,
  trust: "trusted" as const,
  rootPath: "C:\\data\\ja\\workspaces\\thr_fixture",
};
const terminalSession = {
  sessionId: "11111111-1111-4111-8111-111111111111",
  generation: 1,
} as const;

/** 构造前 N 次启动失败的 lifecycle adapter，用于验证真实 ready generation 发布前的恢复路径。 */
function runtime(
  startFailures = 0,
  features: RuntimeStatus["features"] = ["task_threads", "plan"],
): RuntimeHostPort {
  let status: RuntimeStatus = {
    status: "stopped",
    generation: 0,
    serverInstanceId: null,
    features: [],
  };
  let remainingStartFailures = startFailures;
  return {
    pendingOperations: vi.fn(async () => []),
    recheckPendingOperations: vi.fn(async () => []),
    acknowledgePendingOperation: vi.fn(async () => ({
      status: "unknown_acknowledged" as const,
      pending: [],
    })),
    subscribePendingOperations: vi.fn(() => () => undefined),
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
    activateWorkspace: vi.fn(async (workspaceId: string) => ({
      ...sessionWorkspace,
      workspaceId,
      rootPath: `C:\\data\\ja\\workspaces\\${workspaceId.slice("ws_".length)}`,
    })),
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
    turnContinue: vi.fn(async (input) => ({
      accepted: true as const,
      turnId: "turn_continue_fixture",
      queued: false,
      threadRevision: input.expectedThreadRevision + 1,
    })),
    turnReask: vi.fn(async (input) => ({
      accepted: true as const,
      turnId: "turn_reask_fixture",
      queued: false,
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
    saveProjectMcpServers: vi.fn(async () => "cfg_project_2"),
    patch: vi.fn(async () => ({ version: "cfg_project_2" })),
    reset: vi.fn(async () => ({ version: "cfg_project_2" })),
    repairConfiguration: vi.fn(async () => ({ version: "cfg_user_2" })),
    setCredential: vi.fn(async () => "cfg_credential_2"),
    deleteCredential: vi.fn(async () => "cfg_credential_2"),
    revealProviderCredential: vi.fn(async () => null),
  };
}

/** 统一 App 测试里的 Thread identity，避免 capability 用例用空草稿冒充已加载会话。 */
function threadFixture() {
  return {
    threadId: "thr_fixture",
    workspaceId: sessionWorkspace.workspaceId,
    workspaceKind: "session" as const,
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

/** 无项目 Thread 只出现在聚合导航中；App 交互测试显式选择目标会话以验证其真实 owner。 */
async function selectSessionThread(
  title = "新对话",
  runtimePort?: RuntimeHostPort,
): Promise<HTMLButtonElement> {
  const row = await screen.findByRole("button", { name: title });
  expect(row).toHaveClass("ja-navigation-thread");
  fireEvent.click(row);
  if (runtimePort !== undefined)
    await waitFor(() =>
      expect(runtimePort.activateWorkspace).toHaveBeenCalledWith(sessionWorkspace.workspaceId),
    );
  await waitFor(() =>
    expect(screen.getByRole("button", { name: title })).toHaveAttribute("data-active", "true"),
  );
  await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toBeEnabled());
  return screen.getByRole("button", { name: title }) as HTMLButtonElement;
}

/** 组合测试只模拟 App Server 权威查询；读取响应的 identity 必须沿用请求，才能真实切换 Thread。 */
function history(): HistoryAdapter {
  return {
    itemContentRead: vi.fn(async ({ itemId, offsetCharacters }) => ({
      itemId,
      offsetCharacters,
      nextOffsetCharacters: null,
      totalCharacters: 0,
      truncated: false,
      content: "",
    })),
    threadObserve: vi.fn(async ({ threadId }) => ({ accepted: true as const, threadId })),
    threadUnobserve: vi.fn(async ({ threadId }) => ({ accepted: true as const, threadId })),
    workspaceList: vi.fn(async () => ({ items: [], nextCursor: null })),
    workspaceSetTrust: vi.fn(async () => ({ accepted: true as const })),
    threadList: vi.fn(async () => ({ items: [], nextCursor: null })),
    threadSearch: vi.fn(async () => ({ items: [], nextCursor: null })),
    threadRename: vi.fn(async () => {
      throw new Error("unused");
    }),
    threadPreferencesUpdate: vi.fn(async () => {
      throw new Error("unused");
    }),
    threadCreate: vi.fn(async () => threadFixture()),
    threadRead: vi.fn(async ({ threadId }) => ({
      threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
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
    threadCompactCancel: vi.fn(async () => ({ accepted: false })),
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

/** 将路径搜索响应绑定到真实请求 Thread，防止跨会话焦点回归被固定 fixture identity 阻断。 */
function runtimeWithWorkspacePathSearch(): RuntimeHostPort {
  const runtimePort = runtime();
  runtimePort.query = vi.fn(async (method, params) => {
    if (method === "workspace/path/search") {
      const input = params as { threadId: string; workspaceId: string; query: string };
      return {
        threadId: input.threadId,
        workspaceId: input.workspaceId,
        generation: 1,
        query: input.query,
        items: [{ relativePath: "src/main.ts", kind: "file" as const }],
        truncated: false,
      };
    }
    return { items: [], nextCursor: null };
  }) as RuntimeHostPort["query"];
  return runtimePort;
}

/** 共享真实 Files 生命周期边界，保存端口可单独延迟以验证关闭 ACK 期间的焦点所有权。 */
function focusWorkspaceAdapter(
  overrides: Partial<typeof DEFAULT_WORKBENCH_ADAPTERS.workspace> = {},
): typeof DEFAULT_WORKBENCH_ADAPTERS.workspace {
  const revision = {
    kind: "file" as const,
    size: 18,
    modifiedUnixMillis: 1,
    sha256: "sha-main",
  };
  return {
    ...DEFAULT_WORKBENCH_ADAPTERS.workspace,
    tree: vi.fn(async () => ({
      entries: [],
      directoryRevision: { ...revision, kind: "directory" as const, size: 0 },
      nextCursor: null,
      snapshotToken: "tree-1",
      totalEntries: 0,
      depth: 0,
    })),
    readFile: vi.fn(async () => ({
      metadata: { kind: "file" as const, size: 18, modifiedUnixMillis: 1, revision },
      kind: "text" as const,
      encoding: "utf8" as const,
      lineEnding: "lf" as const,
      text: "export const ok = 1;",
      bytesRead: 18,
      truncated: false,
    })),
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
    ...overrides,
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
    useTimelineStore.getState().reset();
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

  /** 待核查通知失败可重试、确认可取消；解除本机阻挡始终不重新发送原操作。 */
  it("启动残留未知提交时显示待核实提示，重新检查不触发新 Turn", async () => {
    const runtimePort = runtime();
    const pending = [
      {
        operationKey: "op_0123456789abcdef0123456789abcdef",
        method: "approval/respond" as const,
        threadId: null,
        createdAt: "2026-09-24T00:00:00.000Z",
      },
    ];
    vi.mocked(runtimePort.recheckPendingOperations)
      .mockResolvedValueOnce(pending)
      .mockRejectedValueOnce(new Error("recheck unavailable"))
      .mockResolvedValue(pending);
    render(
      <App
        runtime={runtimePort}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );
    expect(await screen.findByText(/上一次操作结果待核实，可能已产生副作用/)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "重新检查" }));
    fireEvent.click(screen.getByRole("button", { name: "重新检查" }));
    await waitFor(() => expect(runtimePort.recheckPendingOperations).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("重新检查失败，请重试。")).toBeVisible();
    fireEvent.click(await screen.findByRole("button", { name: "重新检查" }));
    fireEvent.click(await screen.findByRole("button", { name: "我已检查，允许新操作" }));
    expect(await screen.findByText(/原操作仍可能已经执行/)).toBeVisible();
    fireEvent.click(await screen.findByRole("button", { name: "取消" }));
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "确认解除阻挡" })).not.toBeInTheDocument(),
    );
    expect(runtimePort.acknowledgePendingOperation).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole("button", { name: "我已检查，允许新操作" }));
    fireEvent.click(await screen.findByRole("button", { name: "确认解除阻挡" }));
    await waitFor(() =>
      expect(runtimePort.acknowledgePendingOperation).toHaveBeenCalledWith(
        pending[0]?.operationKey,
      ),
    );
    expect(await screen.findByText(/原操作是否执行仍未确认/)).toBeVisible();
    expect(runtimePort.turnStart).not.toHaveBeenCalled();
  });

  /** 空配置不锁住工作区；用户仍可浏览历史并从导航进入设置补齐模型。 */
  it("keeps workspace and Settings reachable with an empty app-server-owned configuration", async () => {
    const user = userEvent.setup();
    const { container } = render(
      <App
        runtime={runtime()}
        settingsAdapter={settings()}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument(),
    );
    expect(screen.queryByText("runtime/configure")).not.toBeInTheDocument();
    const workspacePanels = container.querySelector<HTMLElement>(".ja-workspace-panels");
    expect(workspacePanels).not.toHaveAttribute("hidden");
    expect(screen.getByRole("button", { name: "设置" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(await screen.findByRole("region", { name: "设置页面" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "当前项目" })).toBeDisabled();
  });

  /** 用户配置语法损坏只回退可读前缀；配置问题可从普通工作区导航修复。 */
  it("keeps the workspace usable when a configuration has an unreadable suffix", async () => {
    const user = userEvent.setup();
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(emptyDocument, [
          {
            id: "cfg_toml_syntax_user",
            scope: "user",
            field: null,
            entityId: null,
            line: null,
            column: null,
            reason: "TOML_PARSE_FAILED",
            impact: "valid_prefix_in_use",
            actions: ["repair"],
          },
        ])}
        historyAdapter={history()}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    await waitFor(() => expect(screen.getByRole("button", { name: "设置" })).toBeVisible());
    expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(await screen.findByRole("region", { name: "设置页面" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "配置问题" }));
    expect(
      screen.getByText("语法错误之后的内容已忽略，当前使用错误之前能读取的设置。"),
    ).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "设置暂时不可用" })).not.toBeInTheDocument();
  });

  /** 启动失败仍保留对话与发送门禁，导航中不再渲染运行时状态块。 */
  it("keeps the conversation visible without a sidebar runtime status block", async () => {
    const runtimeAdapter = runtime(1);
    render(
      <App runtime={runtimeAdapter} settingsAdapter={settings()} historyAdapter={history()} />,
    );

    await waitFor(() => expect(runtimeAdapter.start).toHaveBeenCalledOnce());
    expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "本地运行时启动失败" })).not.toBeInTheDocument();
    expect(screen.queryByText("正在读取本地设置…")).not.toBeInTheDocument();
    expect(screen.queryByRole("status", { name: /本地运行时/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "运行时异常详情" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "设置" })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "消息" })).toBeDisabled();
    expect(await screen.findByRole("button", { name: "重新连接" })).toBeEnabled();
  });

  /** 连接失败只给稳定重试反馈；再次显式重连成功后移除入口，不创建或恢复任何 Turn。 */
  it("retries a failed runtime connection from the titlebar without resuming a turn", async () => {
    const user = userEvent.setup();
    const runtimeAdapter = runtime(2);
    const settingsAdapter = settings(configuredDocument);
    render(
      <App runtime={runtimeAdapter} settingsAdapter={settingsAdapter} historyAdapter={history()} />,
    );
    await user.click(await screen.findByRole("button", { name: "重新连接" }));
    await waitFor(() => expect(runtimeAdapter.start).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("重新连接失败，请稍后重试。")).toBeVisible();
    const reconnect = screen.getByRole("button", { name: "重新连接" });
    await waitFor(() => expect(reconnect).toBeEnabled());
    await user.click(reconnect);
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument(),
    );
    expect(runtimeAdapter.start).toHaveBeenCalledTimes(3);
    await waitFor(() => expect(settingsAdapter.snapshot).toHaveBeenCalledOnce());
    await waitFor(() => expect(screen.getByRole("button", { name: "隐藏侧边栏" })).toHaveFocus());
    expect(runtimeAdapter.turnStart).not.toHaveBeenCalled();
    expect(runtimeAdapter.turnResume).not.toHaveBeenCalled();
    expect(runtimeAdapter.turnContinue).not.toHaveBeenCalled();
  });

  /** 同一渲染批次的连续点击必须先由 ref 去重；随后连接等待态也不能重新发起启动。 */
  it("keeps runtime reconnection single-flight before and after the pending state commits", async () => {
    const runtimeAdapter = runtime();
    const startReadyRuntime = runtimeAdapter.start;
    let initialStartup = true;
    let finishReconnect: (() => void) | undefined;
    const reconnectGate = new Promise<void>((resolve) => {
      finishReconnect = resolve;
    });
    runtimeAdapter.start = vi.fn(async () => {
      if (initialStartup) {
        initialStartup = false;
        throw new RuntimeApplicationError("RUNTIME_UNAVAILABLE", "startup unavailable", true);
      }
      await reconnectGate;
      return startReadyRuntime();
    });
    render(
      <App
        runtime={runtimeAdapter}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={history()}
      />,
    );
    const reconnect = await screen.findByRole("button", { name: "重新连接" });
    act(() => {
      fireEvent.click(reconnect);
      fireEvent.click(reconnect);
    });
    await waitFor(() => expect(runtimeAdapter.start).toHaveBeenCalledTimes(2));
    expect(reconnect).toBeDisabled();
    expect(reconnect).toHaveAttribute("aria-busy", "true");
    fireEvent.click(reconnect);
    expect(runtimeAdapter.start).toHaveBeenCalledTimes(2);
    await act(async () => finishReconnect?.());
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument(),
    );
    expect(runtimeAdapter.start).toHaveBeenCalledTimes(2);
    expect(runtimeAdapter.turnStart).not.toHaveBeenCalled();
    expect(runtimeAdapter.turnResume).not.toHaveBeenCalled();
    expect(runtimeAdapter.turnContinue).not.toHaveBeenCalled();
  });

  /** 共享顶栏在设置页面同样保留崩溃后的显式入口，成功后把焦点归还当前设置分类。 */
  it("reconnects a crashed runtime from Settings and restores its selected-tab focus", async () => {
    const user = userEvent.setup();
    const { port: runtimeAdapter, emit } = runtimeWithEvents();
    const settingsAdapter = settings(configuredDocument);
    render(
      <App runtime={runtimeAdapter} settingsAdapter={settingsAdapter} historyAdapter={history()} />,
    );
    await waitFor(() => expect(settingsAdapter.snapshot).toHaveBeenCalledOnce());
    expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("region", { name: "设置页面" });
    act(() => {
      emit({
        kind: "status",
        status: {
          status: "crashed",
          generation: 1,
          serverInstanceId: "srv_fixture",
          features: ["task_threads", "plan"],
        },
        eventId: "runtime_crash_fixture",
        occurredAt: "2026-09-29T00:00:00Z",
      });
    });
    await user.click(await screen.findByRole("button", { name: "重新连接" }));
    await waitFor(() => expect(runtimeAdapter.start).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument(),
    );
    const page = within(await screen.findByRole("region", { name: "设置页面" }));
    await waitFor(() => expect(page.getByRole("tab", { selected: true })).toHaveFocus());
    expect(runtimeAdapter.turnStart).not.toHaveBeenCalled();
    expect(runtimeAdapter.turnResume).not.toHaveBeenCalled();
    expect(runtimeAdapter.turnContinue).not.toHaveBeenCalled();
  });

  /** 协议不兼容与人工恢复须使用各自处理路径，普通重连不能冒充已解决这些裁决。 */
  it.each(["incompatible", "recovery_required"] as const)(
    "does not offer runtime reconnection for %s",
    async (status) => {
      const runtimeAdapter = runtime();
      runtimeAdapter.state = vi.fn(async () => ({
        status,
        generation: 1,
        serverInstanceId: "srv_blocked",
        features: [],
      }));
      render(
        <App runtime={runtimeAdapter} settingsAdapter={settings()} historyAdapter={history()} />,
      );
      await waitFor(() => expect(runtimeAdapter.state).toHaveBeenCalled());
      expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument();
      expect(runtimeAdapter.start).not.toHaveBeenCalled();
    },
  );

  /** 延迟启动用于证明首屏不依赖 runtime promise 完成，且发送准入没有被视图改动放开。 */
  it("renders the conversation immediately while runtime startup is pending", () => {
    const runtimeAdapter = runtime();
    runtimeAdapter.start = vi.fn(() => new Promise<RuntimeStatus>(() => undefined));
    render(
      <App runtime={runtimeAdapter} settingsAdapter={settings()} historyAdapter={history()} />,
    );
    expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: /本地运行时/ })).not.toBeInTheDocument();
    expect(screen.queryByText("正在启动本地运行时…")).not.toBeInTheDocument();
    expect(screen.queryByText("正在读取本地设置…")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "运行时异常详情" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "消息" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument();
  });

  /** 空白 session catalog 保持无 owner；“新会话”必须先创建再激活目录，之后才开放 Composer。 */
  it("creates and activates a session before enabling the first Composer", async () => {
    const user = userEvent.setup();
    const events: string[] = [];
    const runtimeAdapter = runtime();
    runtimeAdapter.activateWorkspace = vi.fn(async (workspaceId: string) => {
      events.push("activate");
      return { ...sessionWorkspace, workspaceId };
    });
    const historyAdapter = history();
    historyAdapter.threadCreate = vi.fn(async () => {
      events.push("create");
      return {
        ...threadFixture(),
        preferences: {
          ...threadFixture().preferences,
          providerId: "provider_openai",
          modelId: "model_gpt",
          reasoningLevel: "high" as const,
          accessMode: "full_access" as const,
        },
      };
    });
    const createThread = historyAdapter.threadCreate as ReturnType<typeof vi.fn>;
    historyAdapter.threadRead = vi.fn(async () => {
      events.push("read");
      return {
        threadId: "thr_fixture",
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        liveStream: null,
        nextCursor: null,
      };
    });
    render(
      <App
        runtime={runtimeAdapter}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    const input = await screen.findByRole("textbox", { name: "消息" });
    await waitFor(() => expect(screen.getByRole("button", { name: "新会话" })).toBeEnabled());
    expect(input).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "新会话" }));

    await waitFor(() => expect(events.slice(0, 3)).toEqual(["create", "activate", "read"]));
    await waitFor(() => expect(input).toBeEnabled());
    expect(historyAdapter.threadCreate).toHaveBeenCalledOnce();
    expect(createThread.mock.calls[0]?.[0]).not.toHaveProperty("cwd");
    expect(runtimeAdapter.activateWorkspace).toHaveBeenCalledExactlyOnceWith(
      sessionWorkspace.workspaceId,
    );
  });

  /** 历史失败只在当前 Conversation 通知呈现一次；显式重试沿原目录读取并恢复真实空态。@author kongweiguang */
  it("offers a scoped history retry without duplicating the sidebar error", async () => {
    const user = userEvent.setup();
    const historyAdapter = history();
    let readable = false;
    const threadList = vi.fn<HistoryAdapter["threadList"]>(async () => {
      if (!readable) throw new Error("history temporarily unavailable");
      return { items: [], nextCursor: null };
    });
    historyAdapter.threadList = threadList;
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    await screen.findByText("历史会话暂时不可用，请重试。", { exact: true });
    await waitFor(() =>
      expect(
        screen
          .getAllByRole("alert")
          .filter((alert) => alert.textContent?.includes("历史会话暂时不可用，请重试。")),
      ).toHaveLength(1),
    );
    const failure = screen.getByText("历史会话暂时不可用，请重试。", { exact: true });
    const notification = failure.closest<HTMLElement>("[data-sonner-toast]");
    expect(notification).not.toBeNull();
    expect(within(notification as HTMLElement).getByRole("button", { name: "重试" })).toBeEnabled();
    expect(document.querySelector('.ja-navigation-history-list [role="alert"]')).toBeNull();
    expect(screen.queryByText("还没有历史对话。")).not.toBeInTheDocument();

    const readsBeforeRetry = threadList.mock.calls.length;
    readable = true;
    await user.click(within(notification as HTMLElement).getByRole("button", { name: "重试" }));
    await waitFor(() => expect(threadList).toHaveBeenCalledTimes(readsBeforeRetry + 1));
    await waitFor(() => expect(screen.getByText("还没有历史对话。")).toBeVisible());
    await waitFor(() =>
      expect(
        screen.queryByText("历史会话暂时不可用，请重试。", { exact: true }),
      ).not.toBeInTheDocument(),
    );
  });

  /** 项目提示属于可见 Workspace owner；Thread 切换与快速重试复用一张提示卡，隐藏时不留 Portal。 @author kongweiguang */
  it("keeps one visible directory notice across Thread changes, hidden views, and retry", async () => {
    const user = userEvent.setup();
    const projectWorkspace = {
      workspaceId: "ws_notice_project",
      kind: "project" as const,
      root: "C:\\removed\\notice-project",
      displayName: "通知项目",
      trust: "trusted" as const,
      revision: 1,
    };
    const preferences = {
      providerId: "provider_openai",
      modelId: "model_gpt",
      reasoningLevel: "high" as const,
      accessMode: "full_access" as const,
      collaborationMode: "default" as const,
      titleSource: "placeholder" as const,
    };
    const projectThread = {
      ...threadFixture(),
      threadId: "thr_notice_first",
      workspaceId: projectWorkspace.workspaceId,
      workspaceKind: "project" as const,
      preferences,
      title: "通知项目会话一",
    };
    const secondProjectThread = {
      ...projectThread,
      threadId: "thr_notice_second",
      title: "通知项目会话二",
    };
    const historyAdapter = history();
    historyAdapter.workspaceList = vi.fn(async () => ({
      items: [projectWorkspace],
      nextCursor: null,
    }));
    historyAdapter.workspaceRead = vi.fn(async () => projectWorkspace);
    let retryPending = false;
    let rejectRetry!: (reason: Error) => void;
    historyAdapter.workspaceOpen = vi.fn(async (input) => {
      if ("workspaceId" in input) {
        if (retryPending)
          return await new Promise<never>((_resolve, reject) => {
            rejectRetry = reject;
          });
        throw new Error("project directory was removed");
      }
      return projectWorkspace;
    });
    historyAdapter.threadList = vi.fn(async (input) => ({
      items:
        "workspaceId" in input && input.workspaceId === projectWorkspace.workspaceId
          ? [projectThread, secondProjectThread]
          : [],
      nextCursor: null,
    }));
    historyAdapter.threadRead = vi.fn(async ({ threadId }) => ({
      threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      liveStream: null,
      nextCursor: null,
    }));
    // 显式复核经过真实项目资源事务；空资源的 native 回收 ACK 由 fixture 提供，不跳过清理边界。
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
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
        workbenchAdapters={{
          ...DEFAULT_WORKBENCH_ADAPTERS,
          terminal: terminalAdapter,
          preview: previewAdapter,
        }}
      />,
    );

    const projectButton = await screen.findByRole("button", { name: "切换到项目：通知项目" });
    await waitFor(() => expect(projectButton).toBeEnabled());
    await user.click(projectButton);
    await screen.findByText("项目目录不可用", { exact: true });
    await waitFor(() =>
      expect(screen.getAllByText("项目目录不可用", { exact: true })).toHaveLength(1),
    );

    await user.click(await screen.findByRole("button", { name: "通知项目会话一" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "通知项目会话一" })).toHaveAttribute(
        "data-active",
        "true",
      ),
    );
    expect(screen.getAllByText("项目目录不可用", { exact: true })).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "通知项目会话二" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "通知项目会话二" })).toHaveAttribute(
        "data-active",
        "true",
      ),
    );
    expect(screen.getAllByText("项目目录不可用", { exact: true })).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "设置" }));
    await screen.findByRole("region", { name: "设置页面" });
    await waitFor(() =>
      expect(screen.queryByText("项目目录不可用", { exact: true })).not.toBeInTheDocument(),
    );
    await user.click(screen.getByRole("button", { name: "返回应用" }));
    await screen.findByText("项目目录不可用", { exact: true });
    expect(screen.getAllByText("项目目录不可用", { exact: true })).toHaveLength(1);

    const directoryToast = screen
      .getByText("项目目录不可用", { exact: true })
      .closest<HTMLElement>("[data-sonner-toast]");
    const directoryToastId = directoryToast?.getAttribute("data-sonner-toast");
    expect(directoryToastId).not.toBeNull();
    fireEvent.contextMenu(screen.getByRole("button", { name: "当前项目：通知项目" }), {
      clientX: 48,
      clientY: 64,
    });
    await user.click(await screen.findByRole("menuitem", { name: "编辑项目…" }));
    expect(await screen.findByRole("heading", { name: "编辑项目" })).toBeVisible();
    await waitFor(() =>
      expect(screen.queryByText("项目目录不可用", { exact: true })).not.toBeInTheDocument(),
    );
    expect(document.querySelectorAll("[data-sonner-toast]")).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "取消" }));
    await waitFor(() => {
      const restoredToast = screen
        .getByText("项目目录不可用", { exact: true })
        .closest<HTMLElement>("[data-sonner-toast]");
      expect(restoredToast?.getAttribute("data-sonner-toast")).toBe(directoryToastId);
    });

    retryPending = true;
    const currentToast = screen
      .getByText("项目目录不可用", { exact: true })
      .closest<HTMLElement>("[data-sonner-toast]");
    expect(currentToast).not.toBeNull();
    await user.click(within(currentToast as HTMLElement).getByRole("button", { name: "重新检查" }));
    await waitFor(() => expect(historyAdapter.workspaceOpen).toHaveBeenCalledTimes(2));
    expect(screen.getAllByText("项目目录不可用", { exact: true })).toHaveLength(1);
    expect(
      within(
        screen
          .getByText("项目目录不可用", { exact: true })
          .closest<HTMLElement>("[data-sonner-toast]") as HTMLElement,
      ).getByRole("button", { name: "重新检查" }),
    ).toBeDisabled();

    await act(async () => rejectRetry(new Error("directory is still unavailable")));
    await waitFor(() => {
      // 失败只恢复按钮，不自动重开目录，防止目录失效提示重新闪烁。
      expect(historyAdapter.workspaceOpen).toHaveBeenCalledTimes(2);
      expect(
        within(
          screen
            .getByText("项目目录不可用", { exact: true })
            .closest<HTMLElement>("[data-sonner-toast]") as HTMLElement,
        ).getByRole("button", { name: "重新检查" }),
      ).toBeEnabled();
    });
    expect(screen.getAllByText("项目目录不可用", { exact: true })).toHaveLength(1);
  });

  /** 人工恢复仍由 Settings 的 RecoveryPanel 提供，移除侧栏状态不触发自动确认。 */
  it("keeps manual recovery available from Settings", async () => {
    const user = userEvent.setup();
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
    expect(screen.getByRole("region", { name: "coding 对话" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "运行时需要人工恢复" })).not.toBeInTheDocument();
    expect(runtimeAdapter.start).not.toHaveBeenCalled();
    expect(runtimeAdapter.acknowledgeRecovery).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "设置" }));
    expect(await screen.findByRole("heading", { name: "运行时需要人工恢复" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "系统已重启" })).toBeEnabled();
  });

  /** 数据库阻断使配置读取不可用时，详情仍依赖 Runtime 裁决提供恢复动作而不开放配置编辑。 */
  it("opens blocked storage details when settings cannot be read", async () => {
    const user = userEvent.setup();
    const recovery = {
      id: "storage_blocked_fixture",
      phase: "blocked" as const,
      reason: "CORRUPT_DATABASE" as const,
      historyAvailable: false,
      backupAvailable: true,
      rebuilt: false,
    };
    const blocked: RuntimeStatus = {
      status: "faulted",
      generation: 1,
      serverInstanceId: "srv_blocked_fixture",
      features: [],
      storageRecovery: recovery,
    };
    const runtimeAdapter = runtime();
    runtimeAdapter.start = vi.fn(async () => blocked);
    runtimeAdapter.state = vi.fn(async () => blocked);
    runtimeAdapter.storageInfo = vi.fn(async () => ({
      nativeImage: false,
      dataPath: "C:\\data\\ja",
      logPath: null,
      cachePath: null,
      lastBackup: null,
      storageRecovery: recovery,
    }));
    runtimeAdapter.retryStorage = vi.fn(async () => {
      throw new Error("still blocked");
    });
    runtimeAdapter.openStorageBackup = vi.fn(async () => undefined);
    const settingsAdapter = settings(configuredDocument);
    vi.mocked(settingsAdapter.snapshot).mockRejectedValue(new Error("database blocked"));

    render(
      <App runtime={runtimeAdapter} settingsAdapter={settingsAdapter} historyAdapter={history()} />,
    );
    await user.click(await screen.findByRole("button", { name: "查看详情" }));
    expect(await screen.findByRole("tab", { name: "数据与存储" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    const details = within(screen.getByRole("tabpanel", { name: "数据与存储" }));
    expect(details.getByText("本地数据库无法读取，初始化已停止")).toBeVisible();
    expect(details.getByRole("button", { name: "重试" })).toBeEnabled();
    expect(details.getByRole("button", { name: "打开备份" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "重新连接" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "设置暂时不可用" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "模型" })).not.toBeInTheDocument();
    await user.click(details.getByRole("button", { name: "打开备份" }));
    await waitFor(() => expect(runtimeAdapter.openStorageBackup).toHaveBeenCalledWith(recovery.id));
  });

  /** 恢复完成不依赖配置快照成功；等待或失败时仍保留原数据事实、备份和返回路径。 */
  it.each(["pending", "error"] as const)(
    "keeps storage details and return available after recovery with settings %s",
    async (configurationState) => {
      const user = userEvent.setup();
      const blockedRecovery = {
        id: "storage_ready_fixture",
        phase: "blocked" as const,
        reason: "CORRUPT_DATABASE" as const,
        historyAvailable: false,
        backupAvailable: true,
        rebuilt: false,
      };
      let currentStatus: RuntimeStatus = {
        status: "ready",
        generation: 1,
        serverInstanceId: "srv_storage_fixture",
        features: ["task_threads", "plan"],
        storageRecovery: blockedRecovery,
      };
      const runtimeAdapter = runtime();
      runtimeAdapter.start = vi.fn(async () => currentStatus);
      runtimeAdapter.state = vi.fn(async () => currentStatus);
      runtimeAdapter.storageInfo = vi.fn(async () => ({
        nativeImage: false,
        dataPath: "C:\\data\\ja",
        logPath: null,
        cachePath: null,
        lastBackup: null,
        storageRecovery: currentStatus.storageRecovery,
      }));
      runtimeAdapter.retryStorage = vi.fn(async () => {
        currentStatus = {
          ...currentStatus,
          storageRecovery: { ...blockedRecovery, phase: "ready", rebuilt: true },
        };
        return currentStatus;
      });
      runtimeAdapter.openStorageBackup = vi.fn(async () => undefined);
      const settingsAdapter = settings(configuredDocument);
      settingsAdapter.snapshot = vi.fn(() =>
        configurationState === "pending"
          ? new Promise<LoadedSettings>(() => undefined)
          : Promise.reject(new Error("settings unavailable")),
      );

      render(
        <App
          runtime={runtimeAdapter}
          settingsAdapter={settingsAdapter}
          historyAdapter={history()}
        />,
      );
      let detailsAction: HTMLElement | undefined;
      await waitFor(() => {
        detailsAction = screen.queryAllByRole("button", { name: "查看详情" }).find((button) => {
          const notification = button.closest("[data-sonner-toast]");
          return (
            notification?.getAttribute("data-type") === "error" &&
            notification.getAttribute("data-removed") !== "true"
          );
        });
        expect(detailsAction).toBeDefined();
      });
      await user.click(detailsAction!);
      const blockedDetails = within(await screen.findByRole("tabpanel", { name: "数据与存储" }));
      expect(settingsAdapter.snapshot).not.toHaveBeenCalled();
      await user.click(blockedDetails.getByRole("button", { name: "重试" }));

      const details = within(await screen.findByRole("tabpanel", { name: "数据与存储" }));
      expect(await details.findByText("本地数据已就绪，历史记录暂不可用")).toBeVisible();
      await waitFor(() => expect(settingsAdapter.snapshot).toHaveBeenCalledOnce());
      expect(screen.queryByRole("heading", { name: "设置暂时不可用" })).not.toBeInTheDocument();
      expect(details.getByText("C:\\data\\ja")).toBeVisible();
      await user.click(details.getByRole("button", { name: "打开备份" }));
      expect(runtimeAdapter.openStorageBackup).toHaveBeenCalledExactlyOnceWith(blockedRecovery.id);

      await user.click(screen.getByRole("button", { name: "返回应用" }));
      expect(screen.getByRole("region", { name: "coding 对话" })).toBeVisible();
      expect(screen.queryByRole("region", { name: "设置页面" })).not.toBeInTheDocument();
    },
  );

  /** Runtime 未声明 Plan capability 时不注册 Slash action，避免无效入口。 */
  it("hides Plan actions before the runtime advertises Plan capability", async () => {
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
   * 用量读取器只拿到 History 的窄能力，但不能丢失类适配器的 `this`；生产实现通过
   * `this.bridge` 发起类型化 IPC，本回归用相同的接收者约束，防止组合层解构方法后裸调用。
   */
  it("binds the History usage reader before handing it to the Composer", async () => {
    const user = userEvent.setup();
    const runtimePort = runtime();
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
        runtime={runtimePort}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );

    await selectSessionThread("新对话", runtimePort);
    await waitFor(() =>
      expect(historyAdapter.threadRead).toHaveBeenCalledWith({
        threadId: "thr_fixture",
        tail: true,
        limit: 200,
      }),
    );
    const trigger = await screen.findByRole("button", { name: "上下文用量详情" });
    await user.click(trigger);
    await waitFor(() => expect(readUsage).toHaveBeenCalledWith({ threadId: "thr_fixture" }));
    expect(readUsage.mock.contexts[0]).toBe(historyAdapter);
    expect(await screen.findByText("Token · 本会话")).toBeVisible();
  });

  /** Runtime 明确声明 Plan capability 后，Slash 面板才公开真实计划入口。 */
  it("shows Plan actions after the runtime advertises Plan capability", async () => {
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
    await selectSessionThread("新对话");
    await screen.findByRole("combobox", { name: "访问模式" });
    const input = screen.getByRole("textbox", { name: "消息" });
    fireEvent.change(input, { target: { value: "/" } });
    const group = await screen.findByRole("group", { name: "添加" });
    expect(group).toHaveTextContent("计划");
    expect(group).toHaveTextContent("/plan");
    expect(group).not.toHaveTextContent("/goal");
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
      liveStream: null,
      nextCursor: null,
    });
    const completedSnapshot = () => ({
      ...emptySnapshot(),
      revision: 4,
      turns: [
        {
          origin: "native" as const,
          turnId: "turn_fixture",
          sourceMessageId: null,
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
    await selectSessionThread();
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
            workspaceId: sessionWorkspace.workspaceId,
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
            workspaceId: sessionWorkspace.workspaceId,
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

  /** 后台恢复仍会自动重试，完整 App 应用中用状态提示而不是错误警报打断用户。 */
  it("shows an automatic background recovery status without blocking the current composer", async () => {
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
    await selectSessionThread();
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
            workspaceId: sessionWorkspace.workspaceId,
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
            workspaceId: sessionWorkspace.workspaceId,
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
    const recoveryStatus = await screen.findByText("会话状态正在自动恢复，请稍候。");
    expect(recoveryStatus.closest('[role="status"]')).not.toBeNull();
    expect(screen.queryByText("请重新选择该会话重试")).not.toBeInTheDocument();
    expect(newConversation).toBeEnabled();
    expect(input).toBeEnabled();
  });

  it("在搜索弹窗打开时将标题元数据事件转为静默刷新 identity", async () => {
    const runtimeHarness = runtimeWithEvents();
    const historyAdapter = history();
    let searchTitle = "首问短标题";
    const storedThread = {
      threadId: "thr_fixture",
      workspaceId: sessionWorkspace.workspaceId,
      workspaceKind: "session" as const,
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
    await selectSessionThread("首问短标题");
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
            workspaceId: sessionWorkspace.workspaceId,
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
            workspaceId: sessionWorkspace.workspaceId,
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

  /** Runtime 未 ready 时目录动作不能成为可点击 no-op；完成同一启动后自动恢复，不需要重启页面。 */
  it("waits for startup admission before enabling project actions", async () => {
    const runtimePort = runtime();
    const start = runtimePort.start;
    let releaseStartup: (() => void) | undefined;
    runtimePort.start = vi.fn(
      () =>
        new Promise<RuntimeStatus>((resolve, reject) => {
          releaseStartup = () => void start().then(resolve, reject);
        }),
    );
    const picker = { pick: vi.fn(async () => null) };
    const historyAdapter = history();
    historyAdapter.workspaceOpen = vi.fn();
    render(
      <App
        runtime={runtimePort}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={picker}
      />,
    );
    const choose = await screen.findByRole("button", { name: "添加项目" });
    expect(choose).toBeDisabled();
    fireEvent.click(choose);
    expect(picker.pick).not.toHaveBeenCalled();
    await waitFor(() => expect(releaseStartup).toBeDefined());
    await act(async () => releaseStartup?.());
    await waitFor(() => expect(choose).toBeEnabled());
    fireEvent.click(choose);
    await waitFor(() => expect(picker.pick).toHaveBeenCalledOnce());
  });

  /** 真实目录选择失败必须在壳层反馈；重试取消只清除旧错误，不改变已确认的工作区身份。 */
  it("shows project selection failures and keeps cancellation retryable", async () => {
    const picker = {
      pick: vi
        .fn<() => Promise<string | null>>()
        .mockRejectedValueOnce(new Error("picker unavailable"))
        .mockResolvedValue(null),
    };
    const historyAdapter = history();
    historyAdapter.workspaceOpen = vi.fn();
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={picker}
      />,
    );
    const choose = await screen.findByRole("button", { name: "添加项目" });
    await waitFor(() => expect(choose).toBeEnabled());
    fireEvent.click(choose);
    const failure = await screen.findByText("项目未能打开，请检查目录和运行时状态后重试。", {
      exact: false,
    });
    expect(failure.closest("[data-sonner-toast]")).toHaveAttribute("data-type", "error");
    expect(screen.getByRole("button", { name: "当前范围：无项目对话" })).toBeVisible();
    await waitFor(() => expect(choose).toBeEnabled());
    fireEvent.click(choose);
    await waitFor(() => expect(picker.pick).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(failure.closest("[data-sonner-toast]")).toHaveAttribute("data-removed", "true"),
    );
    expect(historyAdapter.workspaceOpen).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "当前范围：无项目对话" })).toBeVisible();
  });

  /**
   * 项目选择与 Settings 编辑层必须相互独立：即使设置页仍默认编辑全局层，Shell 也要先读取
   * 目标项目 effective 配置；信任同步失败但已落库时仍重读该配置并保留原确认目标；项目空
   * Thread 就绪后再次触发新会话必须复用当前 identity，并把焦点交给输入框而不是侧栏动作。
   * 导航与信任恢复分例验证，保留全部断言及原超时，避免独立交互累加挤占同一等待窗口。
   */
  it.each([
    { scenario: "reuses its current empty conversation", verifyTrust: false },
    { scenario: "reloads persisted trust after synchronization fails", verifyTrust: true },
  ])("selects a persisted project and $scenario", async ({ verifyTrust }) => {
    let projectWorkspace = {
      workspaceId: "ws_project_a",
      kind: "project" as const,
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
      workspaceId: sessionWorkspace.workspaceId,
      workspaceKind: "session" as const,
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
      workspaceKind: "project" as const,
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
    historyAdapter.threadList = vi.fn(async (input) => ({
      items:
        "workspaceId" in input && input.workspaceId === projectWorkspace.workspaceId
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
        workspaceId: projectWorkspace.workspaceId,
      }),
    );
    expect(terminalAdapter.closeAll).not.toHaveBeenCalled();
    await waitFor(() =>
      expect(settingsAdapter.snapshot).toHaveBeenLastCalledWith({
        workspaceId: projectWorkspace.workspaceId,
      }),
    );
    await screen.findByRole("button", { name: "当前项目：ja" });
    if (!verifyTrust) {
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
        tail: true,
        limit: 200,
      });
      expect(recentThreads.querySelectorAll("button[data-thread-id]")).toHaveLength(
        threadCountBefore,
      );
      await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());

      const secondaryConversationButton = within(recentThreads).getByRole("button", {
        name: secondaryProjectThread.title,
      });
      secondaryConversationButton.focus();
      expect(secondaryConversationButton).toHaveFocus();
      fireEvent.click(secondaryConversationButton);
      await waitFor(() =>
        expect(historyAdapter.threadRead).toHaveBeenLastCalledWith({
          threadId: secondaryProjectThread.threadId,
          tail: true,
          limit: 200,
        }),
      );
      await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());
      return;
    }

    fireEvent.click(screen.getByRole("button", { name: "设置" }));
    expect(await screen.findByRole("region", { name: "设置页面" })).toBeVisible();
    const trustTrigger = await screen.findByRole("button", { name: "撤销项目“ja”的信任" });
    const snapshot = vi.mocked(settingsAdapter.snapshot);
    await waitFor(() =>
      expect(snapshot).toHaveBeenCalledWith({ workspaceId: projectWorkspace.workspaceId }),
    );
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    snapshot.mockClear();
    historyAdapter.workspaceSetTrust = vi.fn(async ({ trust }) => {
      projectWorkspace = { ...projectWorkspace, trust };
      throw new Error("trust persisted but synchronization failed");
    });
    fireEvent.click(trustTrigger);
    expect(await screen.findByRole("heading", { name: "撤销项目的信任？" })).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "撤销信任" }));
    await waitFor(() =>
      expect(historyAdapter.workspaceSetTrust).toHaveBeenCalledExactlyOnceWith({
        workspaceId: projectWorkspace.workspaceId,
        trust: "untrusted",
      }),
    );
    await waitFor(() => expect(snapshot).toHaveBeenCalledOnce());
    expect(snapshot).toHaveBeenCalledExactlyOnceWith({ workspaceId: projectWorkspace.workspaceId });
    expect(screen.getByRole("heading", { name: "撤销项目的信任？" })).toBeVisible();
    expect(
      screen.getByText(
        "信任状态或项目设置尚未完成确认。修改可能已生效；可重试同一操作，或取消后重新读取实际状态。",
      ),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "撤销信任" })).toBeEnabled();
    expect(screen.getByText(projectWorkspace.root)).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    fireEvent.click(await screen.findByRole("button", { name: "返回应用" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());
  });

  /** 冲突恢复必须贯通真实 Dialog、Controller 与组合层，保留改名草稿且不把并发新目录退回旧值。 @author kongweiguang */
  it("rechecks a project edit conflict without losing the draft or reverting an untouched directory", async () => {
    const user = userEvent.setup();
    let persisted = {
      workspaceId: "ws_project_edit_conflict",
      kind: "project" as const,
      root: "C:\\work\\original",
      displayName: "原项目",
      trust: "trusted" as const,
      revision: 1,
    };
    const historyAdapter = history();
    /** 列表与纯读取共同投影同一权威记录，不靠独立 mock 制造不同版本。 */
    historyAdapter.workspaceList = vi.fn(async () => ({ items: [persisted], nextCursor: null }));
    historyAdapter.workspaceRead = vi.fn(async () => persisted);
    historyAdapter.workspaceOpen = vi.fn(async () => persisted);
    /** 首次提交模拟另一调用者的原子修改；复核后的第二次提交才接受新的 CAS 版本。 */
    historyAdapter.workspaceUpdate = vi.fn(async (input) => {
      if (input.expectedRevision === 1) {
        persisted = {
          ...persisted,
          root: "D:\\work\\moved-by-another-window",
          displayName: "另一个窗口的名称",
          revision: 2,
        };
        throw new RuntimeHostError("CONFLICT", "项目版本已变化", true);
      }
      expect(input.expectedRevision).toBe(2);
      persisted = {
        ...persisted,
        displayName: input.displayName ?? persisted.displayName,
        root: input.cwd ?? persisted.root,
        revision: 3,
      };
      return persisted;
    });
    render(
      <App
        runtime={runtime()}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
      />,
    );
    const projectRow = await screen.findByRole("button", { name: "切换到项目：原项目" });
    await waitFor(() => expect(projectRow).toBeEnabled());
    fireEvent.contextMenu(projectRow, { clientX: 48, clientY: 64 });
    await user.click(await screen.findByRole("menuitem", { name: "编辑项目…" }));
    const dialog = await screen.findByRole("dialog", { name: "编辑项目" });
    const name = within(dialog).getByRole("textbox", { name: "项目名称" });
    const path = within(dialog).getByRole("textbox", { name: "项目目录" });
    await user.clear(name);
    await user.type(name, "我的改名草稿");
    await user.click(within(dialog).getByRole("button", { name: "保存" }));
    await within(dialog).findByRole("alert");
    expect(name).toHaveValue("我的改名草稿");
    expect(path).toHaveValue("C:\\work\\original");
    expect(within(dialog).getByRole("button", { name: "保存" })).toBeDisabled();
    await user.click(within(dialog).getByRole("button", { name: "重新检查" }));
    await waitFor(() => expect(path).toHaveValue("D:\\work\\moved-by-another-window"));
    expect(name).toHaveValue("我的改名草稿");
    await user.click(within(dialog).getByRole("button", { name: "保存" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "编辑项目" })).toBeNull());
    expect(historyAdapter.workspaceUpdate).toHaveBeenCalledTimes(2);
    expect(historyAdapter.workspaceUpdate).toHaveBeenLastCalledWith({
      workspaceId: "ws_project_edit_conflict",
      expectedRevision: 2,
      displayName: "我的改名草稿",
    });
    expect(persisted.root).toBe("D:\\work\\moved-by-another-window");
    expect(persisted.displayName).toBe("我的改名草稿");
  });

  /** 验证编辑器来源行卸载后按 workspaceId 恢复重建行；行仍不可见时退回标题栏侧栏开关。 @author kongweiguang */
  it.each(["narrow", "narrow-then-wide"] as const)(
    "restores project editor focus after responsive row unmount (%s)",
    async (viewportTransition) => {
      const user = userEvent.setup();
      const originalMatchMedia = window.matchMedia;
      let narrowViewport = false;
      const projectWorkspace = {
        workspaceId: "ws_project_focus",
        kind: "project" as const,
        root: "C:\\work\\focus",
        displayName: "焦点项目",
        trust: "trusted" as const,
        revision: 1,
      };
      const historyAdapter = history();
      historyAdapter.workspaceList = vi.fn(async () => ({
        items: [projectWorkspace],
        nextCursor: null,
      }));
      historyAdapter.workspaceRead = vi.fn(async () => projectWorkspace);
      historyAdapter.workspaceOpen = vi.fn(async () => projectWorkspace);
      Object.defineProperty(window, "matchMedia", {
        configurable: true,
        value: (query: string) => ({
          matches: narrowViewport && /max-width/u.test(query),
          media: query,
          onchange: null,
          addEventListener: () => undefined,
          removeEventListener: () => undefined,
          addListener: () => undefined,
          removeListener: () => undefined,
          dispatchEvent: () => false,
        }),
      });

      try {
        const { container } = render(
          <App
            runtime={runtime()}
            settingsAdapter={settings(configuredDocument)}
            historyAdapter={historyAdapter}
            projectPicker={{ pick: vi.fn(async () => null) }}
          />,
        );
        const projectButton = await screen.findByRole("button", { name: "切换到项目：焦点项目" });
        await waitFor(() => expect(projectButton).toBeEnabled());
        await user.click(projectButton);
        const selectedProject = await screen.findByRole("button", {
          name: "当前项目：焦点项目",
        });
        fireEvent.contextMenu(selectedProject, { clientX: 48, clientY: 64 });
        await user.click(await screen.findByRole("menuitem", { name: "编辑项目…" }));
        expect(await screen.findByRole("heading", { name: "编辑项目" })).toBeVisible();

        narrowViewport = true;
        act(() => window.dispatchEvent(new Event("resize")));
        await waitFor(() => expect(container.querySelector(".ja-navigation-shell")).toBeNull());
        expect(selectedProject).not.toBeInTheDocument();

        let focusTarget: HTMLElement;
        if (viewportTransition === "narrow-then-wide") {
          narrowViewport = false;
          act(() => window.dispatchEvent(new Event("resize")));
          let rebuiltProject: HTMLButtonElement | null = null;
          await waitFor(() => {
            rebuiltProject = container.querySelector<HTMLButtonElement>(
              '.ja-navigation-project-list [data-workspace-id="ws_project_focus"] button[data-scope-kind="project"]',
            );
            expect(rebuiltProject).not.toBeNull();
          });
          expect(rebuiltProject).not.toBe(selectedProject);
          focusTarget = rebuiltProject!;
        } else {
          const sidebarToggle = container.querySelector<HTMLButtonElement>(
            '.ja-titlebar button[aria-label="显示侧边栏"]',
          );
          expect(sidebarToggle).not.toBeNull();
          if (typeof sidebarToggle?.checkVisibility === "function")
            vi.spyOn(sidebarToggle, "checkVisibility").mockReturnValue(true);
          focusTarget = sidebarToggle!;
        }

        await user.keyboard("{Escape}");

        await waitFor(() => expect(focusTarget).toHaveFocus());
      } finally {
        Object.defineProperty(window, "matchMedia", {
          configurable: true,
          value: originalMatchMedia,
        });
      }
    },
  );

  /**
   * 真实 Radix Portal 返回主对话后归焦 Composer；Ctrl+, 与 Alt+方向键的设置导航在隐藏
   * Workbench 前迁移焦点到持久标题栏，返回应用仍复用原 DOM 子树。
   */
  it("opens a session conversation with a resizable workbench", async () => {
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

    await selectSessionThread();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "显示工作区面板" })).toBeInTheDocument(),
    );
    await waitFor(() =>
      expect(historyAdapter.threadRead).toHaveBeenCalledWith({
        threadId: "thr_fixture",
        tail: true,
        limit: 200,
      }),
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
    const capabilityMenu = await screen.findByRole("menu");
    expect(capabilityMenu).toBeVisible();
    expect(workbenchPanel?.contains(capabilityMenu)).toBe(false);
    await user.click(within(capabilityMenu).getByRole("menuitem", { name: /返回主对话/u }));
    await waitFor(() => expect(workbenchPanel).toHaveAttribute("hidden"));
    expect(workspacePanels).toHaveAttribute("data-layout-mode", "conversation");
    const composer = screen.getByRole("textbox", { name: "消息" });
    await waitFor(() => expect(composer).toHaveFocus());

    await user.click(await screen.findByRole("button", { name: "显示工作区面板" }));
    await waitFor(() => expect(workbenchPanel).not.toHaveAttribute("hidden"));
    await user.click(screen.getByRole("button", { name: "新建标签页" }));
    await user.click(await screen.findByRole("menuitem", { name: /终端/u }));
    const terminalTab = await waitFor(() => {
      const tab = workbenchPanel?.querySelector<HTMLElement>('[data-workbench-tab="terminal"]');
      expect(tab).not.toBeNull();
      return tab;
    });
    terminalTab?.focus();
    fireEvent.keyDown(document, { key: ",", ctrlKey: true });
    const settingsRegion = await screen.findByRole("region", { name: "设置页面" });
    expect(workspacePanels).toHaveAttribute("hidden");
    await waitFor(() => {
      const selectedSettingsTab = within(settingsRegion).getByRole("tab", { selected: true });
      expect(selectedSettingsTab).toBeVisible();
      expect(selectedSettingsTab).toHaveFocus();
      expect(selectedSettingsTab.closest<HTMLElement>("[hidden], [inert]")).toBeNull();
    });
    expect(document.activeElement).not.toBe(composer);
    expect(container.querySelector("#workbench")).toBe(workbenchPanel);

    fireEvent.keyDown(document, { key: "ArrowLeft", altKey: true });
    await waitFor(() => expect(workspacePanels).not.toHaveAttribute("hidden"));
    terminalTab?.focus();
    fireEvent.keyDown(document, { key: "ArrowRight", altKey: true });
    const settingsRegionAfterReturn = await screen.findByRole("region", { name: "设置页面" });
    await waitFor(() => {
      const selectedSettingsTab = within(settingsRegionAfterReturn).getByRole("tab", {
        selected: true,
      });
      expect(selectedSettingsTab).toBeVisible();
      expect(selectedSettingsTab).toHaveFocus();
      expect(selectedSettingsTab.closest<HTMLElement>("[hidden], [inert]")).toBeNull();
    });
    expect(document.activeElement).not.toBe(composer);

    fireEvent.click(await screen.findByRole("button", { name: "返回应用" }));
    await waitFor(() => expect(workspacePanels).not.toHaveAttribute("hidden"));
    expect(container.querySelector("#workbench")).toBe(workbenchPanel);
  });

  /** 两个真实能力 Tab 可独立关闭；ACK 先迁移焦点再收起，回程按浏览器帧顺序执行未取消的回调。 */
  it("waits for the final narrow Workbench tab close acknowledgement before collapsing", async () => {
    const user = userEvent.setup();
    const originalMatchMedia = window.matchMedia;
    const originalRequestAnimationFrame = window.requestAnimationFrame;
    const originalCancelAnimationFrame = window.cancelAnimationFrame;
    const pendingFrames = new Map<number, FrameRequestCallback>();
    let nextFrameId = 0;
    let narrowViewport = false;
    let holdTerminalClose = true;
    let acknowledgeTerminalClose: (() => void) | undefined;
    const historyAdapter = history();
    historyAdapter.threadList = vi.fn(async () => ({
      items: [threadFixture()],
      nextCursor: null,
    }));
    const terminalAdapter = Object.create(
      DEFAULT_WORKBENCH_ADAPTERS.terminal,
    ) as typeof DEFAULT_WORKBENCH_ADAPTERS.terminal;
    /** 保留共享适配器关闭入口引用；Thread 包装器的同名方法只负责逐会话 teardown。 */
    const sharedTerminalCloseAll = vi.fn(async () => undefined);
    terminalAdapter.closeAll = sharedTerminalCloseAll;
    terminalAdapter.profiles = vi.fn(async () => ["default"] as const);
    terminalAdapter.open = vi.fn(async () => terminalSession);
    terminalAdapter.poll = vi.fn(() => new Promise<null>(() => undefined));
    terminalAdapter.subscribeNativeDrop = vi.fn(async () => () => undefined);
    terminalAdapter.close = vi.fn(() =>
      holdTerminalClose
        ? new Promise<void>((resolve) => {
            acknowledgeTerminalClose = resolve;
          })
        : Promise.resolve(),
    );
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: (query: string) => ({
        matches: narrowViewport && /max-width/u.test(query),
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      }),
    });
    Object.defineProperty(window, "requestAnimationFrame", {
      configurable: true,
      /** 单调身份让取消只影响对应回调，清空旧帧后不会复用已发出的 id。 */
      value: (callback: FrameRequestCallback) => {
        const frameId = ++nextFrameId;
        pendingFrames.set(frameId, callback);
        return frameId;
      },
    });
    Object.defineProperty(window, "cancelAnimationFrame", {
      configurable: true,
      /** 同批回调开始前仍可取消，避免已失效的布局或焦点意图被测试重新执行。 */
      value: (frameId: number) => pendingFrames.delete(frameId),
    });

    try {
      const { container } = render(
        <App
          runtime={runtime()}
          settingsAdapter={settings(configuredDocument)}
          historyAdapter={historyAdapter}
          projectPicker={{ pick: vi.fn(async () => null) }}
          workbenchAdapters={{ ...DEFAULT_WORKBENCH_ADAPTERS, terminal: terminalAdapter }}
        />,
      );

      await selectSessionThread();
      narrowViewport = true;
      act(() => window.dispatchEvent(new Event("resize")));
      await user.click(await screen.findByRole("button", { name: "显示工作区面板" }));
      const workbench = container.querySelector<HTMLElement>("#workbench");
      await waitFor(() => expect(workbench).not.toHaveAttribute("hidden"));
      await user.click(screen.getByRole("button", { name: "新建标签页" }));
      await user.click(await screen.findByRole("menuitem", { name: /审查/u }));
      const reviewTab = await waitFor(() => {
        const tab = workbench?.querySelector<HTMLElement>('[data-workbench-tab="review"]');
        expect(tab).not.toBeNull();
        return tab;
      });
      await user.click(screen.getByRole("button", { name: "新建标签页" }));
      await user.click(await screen.findByRole("menuitem", { name: /终端/u }));

      const terminalTab = await waitFor(() => {
        const tab = workbench?.querySelector<HTMLElement>('[data-workbench-tab="terminal"]');
        expect(tab).not.toBeNull();
        return tab;
      });
      await waitFor(() =>
        expect(screen.getByRole("region", { name: "终端窗格 终端 1" })).toBeVisible(),
      );
      await waitFor(() => expect(terminalAdapter.open).toHaveBeenCalledOnce());
      await waitFor(() => expect(terminalAdapter.poll).toHaveBeenCalled());
      const closeReviewTab =
        reviewTab?.parentElement?.querySelector<HTMLButtonElement>('[data-tab-close="true"]');
      expect(closeReviewTab).toBeInstanceOf(HTMLButtonElement);
      await user.click(closeReviewTab!);
      await waitFor(() =>
        expect(workbench?.querySelector('[data-workbench-tab="review"]')).toBeNull(),
      );

      const closeTerminalTab =
        terminalTab?.parentElement?.querySelector<HTMLButtonElement>('[data-tab-close="true"]');
      expect(closeTerminalTab).toBeInstanceOf(HTMLButtonElement);
      const titlebarButtons = Array.from(
        container.querySelectorAll<HTMLButtonElement>(".ja-titlebar button.ja-titlebar-button"),
      ).filter(
        (button) => button.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null,
      );
      expect(titlebarButtons.length).toBeGreaterThan(0);
      const titlebarFocusTarget = titlebarButtons[0];
      expect(titlebarFocusTarget).toBeDefined();
      titlebarButtons.forEach((button) => {
        button.disabled = true;
      });
      titlebarFocusTarget!.disabled = false;
      pendingFrames.clear();
      terminalTab?.focus();
      fireEvent.click(closeTerminalTab!);
      await waitFor(() => expect(terminalAdapter.close).toHaveBeenCalledOnce());
      expect(terminalAdapter.close).toHaveBeenCalledExactlyOnceWith(terminalSession);
      expect(sharedTerminalCloseAll).not.toHaveBeenCalled();

      expect(workbench).not.toHaveAttribute("hidden");
      expect(workbench?.querySelector('[data-workbench-tab="terminal"]')).toBe(terminalTab);
      expect(document.activeElement).toBe(terminalTab);
      let focusLeftBeforeCollapse = false;
      workbench?.addEventListener("focusout", () => {
        focusLeftBeforeCollapse = !workbench.hasAttribute("hidden");
      });

      expect(acknowledgeTerminalClose).toBeDefined();
      holdTerminalClose = false;
      await act(async () => {
        acknowledgeTerminalClose?.();
        await Promise.resolve();
      });
      await waitFor(() => expect(workbench).toHaveAttribute("hidden"));
      expect(focusLeftBeforeCollapse).toBe(true);
      expect(document.activeElement).toBe(titlebarFocusTarget);

      expect(pendingFrames.size).toBeGreaterThan(0);
      /** 一个真实帧执行整批未取消回调；本帧新排的动效留到下一帧，避免无限清空动画循环。 */
      await act(async () => {
        for (const frameId of [...pendingFrames.keys()]) {
          const callback = pendingFrames.get(frameId);
          pendingFrames.delete(frameId);
          callback?.(0);
        }
      });
      await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());
    } finally {
      holdTerminalClose = false;
      acknowledgeTerminalClose?.();
      cleanup();
      Object.defineProperty(window, "matchMedia", {
        configurable: true,
        value: originalMatchMedia,
      });
      Object.defineProperty(window, "requestAnimationFrame", {
        configurable: true,
        value: originalRequestAnimationFrame,
      });
      Object.defineProperty(window, "cancelAnimationFrame", {
        configurable: true,
        value: originalCancelAnimationFrame,
      });
    }
  });

  /** 延迟 teardown 只归还来源 Thread 的焦点证据，隐藏 Host 完成时不能清理当前引用或抢焦点。 */
  it("preserves newer focus and ignores a close callback from a hidden Thread host", async () => {
    const user = userEvent.setup();
    const originalMatchMedia = window.matchMedia;
    const originalRequestAnimationFrame = window.requestAnimationFrame;
    const pendingFrames: FrameRequestCallback[] = [];
    let narrowViewport = false;
    let holdTerminalClose = true;
    let acknowledgeTerminalClose: (() => void) | undefined;
    const primaryThread = threadFixture();
    const secondaryThread = { ...threadFixture(), threadId: "thr_secondary", title: "另一个会话" };
    const runtimePort = runtimeWithWorkspacePathSearch();
    const historyAdapter = history();
    const terminalAdapter = Object.create(
      DEFAULT_WORKBENCH_ADAPTERS.terminal,
    ) as typeof DEFAULT_WORKBENCH_ADAPTERS.terminal;
    /** 保留共享适配器关闭入口引用；Thread 包装器的同名方法只负责逐会话 teardown。 */
    const sharedTerminalCloseAll = vi.fn(async () => undefined);
    terminalAdapter.closeAll = sharedTerminalCloseAll;
    terminalAdapter.profiles = vi.fn(async () => ["default"] as const);
    terminalAdapter.open = vi.fn(async () => terminalSession);
    terminalAdapter.poll = vi.fn(() => new Promise<null>(() => undefined));
    terminalAdapter.subscribeNativeDrop = vi.fn(async () => () => undefined);
    terminalAdapter.close = vi.fn(() =>
      holdTerminalClose
        ? new Promise<void>((resolve) => {
            acknowledgeTerminalClose = resolve;
          })
        : Promise.resolve(),
    );
    const workspaceAdapter = focusWorkspaceAdapter();
    historyAdapter.threadList = vi.fn(async () => ({
      items: [primaryThread, secondaryThread],
      nextCursor: null,
    }));
    Object.defineProperty(window, "matchMedia", {
      configurable: true,
      value: (query: string) => ({
        matches: narrowViewport && /max-width/u.test(query),
        media: query,
        onchange: null,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        addListener: () => undefined,
        removeListener: () => undefined,
        dispatchEvent: () => false,
      }),
    });
    Object.defineProperty(window, "requestAnimationFrame", {
      configurable: true,
      value: (callback: FrameRequestCallback) => {
        pendingFrames.push(callback);
        return pendingFrames.length;
      },
    });

    try {
      const { container } = render(
        <App
          runtime={runtimePort}
          settingsAdapter={settings(configuredDocument)}
          historyAdapter={historyAdapter}
          projectPicker={{ pick: vi.fn(async () => null) }}
          workbenchAdapters={{
            ...DEFAULT_WORKBENCH_ADAPTERS,
            terminal: terminalAdapter,
            workspace: workspaceAdapter,
          }}
        />,
      );

      await selectSessionThread(primaryThread.title, runtimePort);
      narrowViewport = true;
      act(() => window.dispatchEvent(new Event("resize")));
      await user.click(await screen.findByRole("button", { name: "显示工作区面板" }));
      const workbench = container.querySelector<HTMLElement>("#workbench");
      await waitFor(() => expect(workbench).not.toHaveAttribute("hidden"));
      const visibleTitlebarButtons = Array.from(
        container.querySelectorAll<HTMLButtonElement>(".ja-titlebar button.ja-titlebar-button"),
      ).filter(
        (button) => button.closest<HTMLElement>('[hidden], [aria-hidden="true"], [inert]') === null,
      );
      expect(visibleTitlebarButtons.length).toBeGreaterThan(1);
      const expectedFocus = visibleTitlebarButtons[0];
      const externalFocus = visibleTitlebarButtons[1];
      expect(expectedFocus).toBeDefined();
      expect(externalFocus).toBeDefined();
      visibleTitlebarButtons.forEach((button) => {
        button.disabled = true;
      });
      expectedFocus!.disabled = false;
      externalFocus!.disabled = false;

      const closeWorkbench = screen.getByRole("button", { name: "收起右侧栏" });
      pendingFrames.length = 0;
      closeWorkbench.focus();
      fireEvent.click(closeWorkbench);
      expect(workbench).toHaveAttribute("hidden");
      expect(expectedFocus).toHaveFocus();
      const externalFocusRestore = pendingFrames[pendingFrames.length - 1];
      expect(externalFocusRestore).toBeDefined();
      externalFocus!.focus();
      await act(async () => externalFocusRestore?.(0));
      expect(externalFocus).toHaveFocus();

      await user.click(await screen.findByRole("button", { name: "显示工作区面板" }));
      await waitFor(() => expect(workbench).not.toHaveAttribute("hidden"));
      const secondCloseWorkbench = screen.getByRole("button", { name: "收起右侧栏" });
      pendingFrames.length = 0;
      secondCloseWorkbench.focus();
      fireEvent.click(secondCloseWorkbench);
      expect(workbench).toHaveAttribute("hidden");
      const staleCloseRestore = pendingFrames[pendingFrames.length - 1];
      expect(staleCloseRestore).toBeDefined();

      const sidebarToggle = screen.getByRole("button", { name: "显示侧边栏" });
      (sidebarToggle as HTMLButtonElement).disabled = false;
      fireEvent.click(sidebarToggle);
      const secondaryConversation = await screen.findByRole("button", {
        name: secondaryThread.title,
      });
      fireEvent.click(secondaryConversation);
      await waitFor(() =>
        expect(historyAdapter.threadRead).toHaveBeenLastCalledWith({
          threadId: secondaryThread.threadId,
          tail: true,
          limit: 200,
        }),
      );
      await waitFor(() => expect(sidebarToggle).toHaveAttribute("aria-expanded", "false"));
      fireEvent.click(sidebarToggle);
      const selectedSecondaryConversation = await screen.findByRole("button", {
        name: secondaryThread.title,
      });
      await waitFor(() =>
        expect(selectedSecondaryConversation).toHaveAttribute("data-active", "true"),
      );
      expect(workbench).toHaveAttribute("hidden");
      const primaryWorkbenchHost = container.querySelector<HTMLElement>(
        `#workbench .ja-thread-workbench-session[data-thread-workbench-scope="${sessionWorkspace.workspaceId}:${primaryThread.threadId}"]`,
      );
      expect(primaryWorkbenchHost).not.toBeNull();
      expect(primaryWorkbenchHost).toHaveAttribute("hidden");
      expect(primaryWorkbenchHost).toHaveAttribute("inert");

      if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
      expect(document.activeElement).toBe(document.body);
      await act(async () => staleCloseRestore?.(0));
      expect(document.activeElement).toBe(document.body);

      narrowViewport = false;
      act(() => window.dispatchEvent(new Event("resize")));
      await user.click(await screen.findByRole("button", { name: primaryThread.title }));
      await waitFor(() =>
        expect(screen.getByRole("button", { name: primaryThread.title })).toHaveAttribute(
          "data-active",
          "true",
        ),
      );
      await user.click(await screen.findByRole("button", { name: "显示工作区面板" }));
      await waitFor(() => expect(workbench).not.toHaveAttribute("hidden"));
      await user.click(screen.getByRole("button", { name: "新建标签页" }));
      await user.click(await screen.findByRole("menuitem", { name: /终端/u }));
      const terminalTab = await waitFor(() => {
        const tab = workbench?.querySelector<HTMLElement>('[data-workbench-tab="terminal"]');
        expect(tab).not.toBeNull();
        return tab;
      });
      await waitFor(() => expect(terminalAdapter.open).toHaveBeenCalledOnce());
      await waitFor(() => expect(terminalAdapter.poll).toHaveBeenCalled());
      await waitFor(() =>
        expect(within(workbench!).getByRole("region", { name: "终端窗格 终端 1" })).toBeVisible(),
      );
      const closeTerminalTab =
        terminalTab?.parentElement?.querySelector<HTMLButtonElement>('[data-tab-close="true"]');
      expect(closeTerminalTab).toBeInstanceOf(HTMLButtonElement);
      await user.click(closeTerminalTab!);
      await waitFor(() => expect(terminalAdapter.close).toHaveBeenCalledOnce());
      expect(terminalAdapter.close).toHaveBeenCalledExactlyOnceWith(terminalSession);
      expect(sharedTerminalCloseAll).not.toHaveBeenCalled();
      expect(acknowledgeTerminalClose).toBeDefined();

      const primaryConversation = screen.getByRole("button", { name: primaryThread.title });
      const secondaryConversationAfterReturn = screen.getByRole("button", {
        name: secondaryThread.title,
      });
      await user.click(secondaryConversationAfterReturn);
      await waitFor(() =>
        expect(secondaryConversationAfterReturn).toHaveAttribute("data-active", "true"),
      );
      await waitFor(() =>
        expect(historyAdapter.threadRead).toHaveBeenLastCalledWith({
          threadId: secondaryThread.threadId,
          tail: true,
          limit: 200,
        }),
      );
      await user.click(await screen.findByRole("button", { name: "显示工作区面板" }));
      const secondaryWorkbench = container.querySelector<HTMLElement>(
        `#workbench .ja-thread-workbench-session[data-thread-workbench-scope="${sessionWorkspace.workspaceId}:${secondaryThread.threadId}"]`,
      );
      await waitFor(() => {
        expect(secondaryWorkbench).not.toHaveAttribute("hidden");
        expect(secondaryWorkbench).not.toHaveAttribute("inert");
      });

      const composer = screen.getByRole("textbox", { name: "消息" });
      await user.click(composer);
      await user.type(composer, "@src");
      await waitFor(() =>
        expect(runtimePort.query).toHaveBeenCalledWith(
          "workspace/path/search",
          expect.objectContaining({
            threadId: secondaryThread.threadId,
            workspaceId: sessionWorkspace.workspaceId,
            query: "src",
          }),
        ),
      );
      await user.click(await screen.findByRole("option", { name: /main\.ts/u }));
      const source = screen.getByRole("button", { name: "在文件中预览 main.ts" });
      const pendingFramesBeforeReferenceOpen = pendingFrames.length;
      await user.click(source);
      await waitFor(() =>
        expect(workspaceAdapter.readFile).toHaveBeenCalledWith({
          workspaceId: sessionWorkspace.workspaceId,
          relativePath: "src/main.ts",
        }),
      );
      const closeFile = await within(secondaryWorkbench!).findByRole("button", {
        name: "关闭 main.ts",
      });
      const referencedFileTab = secondaryWorkbench?.querySelector<HTMLButtonElement>(
        '[data-file-tab-path="src/main.ts"] [role="tab"]',
      );
      expect(referencedFileTab).toBeInstanceOf(HTMLButtonElement);
      await waitFor(() =>
        expect(pendingFrames.length).toBeGreaterThan(pendingFramesBeforeReferenceOpen),
      );
      const referenceOpenFrame = pendingFrames[pendingFramesBeforeReferenceOpen];
      expect(referenceOpenFrame).toBeDefined();
      await act(async () => referenceOpenFrame?.(0));
      await waitFor(() => expect(referencedFileTab).toHaveFocus());

      await user.click(composer);
      expect(composer).toHaveFocus();
      holdTerminalClose = false;
      await act(async () => {
        acknowledgeTerminalClose?.();
        await Promise.resolve();
      });
      expect(secondaryWorkbench).not.toHaveAttribute("hidden");
      expect(secondaryWorkbench).not.toHaveAttribute("inert");
      expect(primaryWorkbenchHost).toHaveAttribute("hidden");
      expect(primaryWorkbenchHost).toHaveAttribute("inert");
      expect(secondaryWorkbench?.querySelector('[data-workbench-tab="files"]')).not.toBeNull();
      expect(closeFile).toBeInTheDocument();
      expect(source).toBeInTheDocument();
      expect(primaryConversation).toHaveAttribute("data-thread-id", primaryThread.threadId);
      expect(primaryConversation).not.toHaveAttribute("aria-current");
      expect(composer).toHaveFocus();

      const pendingFramesBeforeFileClose = pendingFrames.length;
      await user.click(closeFile);
      await waitFor(() =>
        expect(pendingFrames.length).toBeGreaterThan(pendingFramesBeforeFileClose),
      );
      const sourceRestoreFrame = pendingFrames[pendingFramesBeforeFileClose];
      expect(sourceRestoreFrame).toBeDefined();
      await act(async () => sourceRestoreFrame?.(0));
      await waitFor(() => expect(source).toHaveFocus());
      expect(source).toBeInTheDocument();
    } finally {
      holdTerminalClose = false;
      acknowledgeTerminalClose?.();
      cleanup();
      Object.defineProperty(window, "matchMedia", {
        configurable: true,
        value: originalMatchMedia,
      });
      Object.defineProperty(window, "requestAnimationFrame", {
        configurable: true,
        value: originalRequestAnimationFrame,
      });
    }
  }, 30_000);

  /**
   * 关闭未提交引用时撤销旧打开帧；正常重开仍聚焦文件标签，用户在帧提交前改焦点时则保留该选择。
   */
  it("cancels a fast-closed reference frame and preserves normal and user-selected reopen focus", async () => {
    const user = userEvent.setup();
    const originalRequestAnimationFrame = window.requestAnimationFrame;
    const originalCancelAnimationFrame = window.cancelAnimationFrame;
    const pendingFrames: {
      id: number;
      callback: FrameRequestCallback;
      cancelled: boolean;
      fired: boolean;
    }[] = [];
    let nextFrameId = 0;
    /** 按浏览器帧快照顺序推进当前待处理回调；回调中新申请的帧留给下一次显式 tick。 */
    const advanceAnimationFrameBatch = async (afterFrameId: number): Promise<number> => {
      const frameIdCutoff = nextFrameId;
      const batch = pendingFrames.filter(
        (frame) =>
          frame.id > afterFrameId && frame.id <= frameIdCutoff && !frame.cancelled && !frame.fired,
      );
      await act(async () => {
        for (const frame of batch) {
          frame.fired = true;
          frame.callback(0);
        }
      });
      return frameIdCutoff;
    };
    const thread = threadFixture();
    const runtimePort = runtimeWithWorkspacePathSearch();
    const historyAdapter = history();
    historyAdapter.threadList = vi.fn(async () => ({ items: [thread], nextCursor: null }));
    const workspaceAdapter = focusWorkspaceAdapter();
    const readFile = vi.mocked(workspaceAdapter.readFile);
    Object.defineProperty(window, "requestAnimationFrame", {
      configurable: true,
      value: (callback: FrameRequestCallback) => {
        const id = ++nextFrameId;
        pendingFrames.push({
          id,
          callback,
          cancelled: false,
          fired: false,
        });
        return id;
      },
    });
    Object.defineProperty(window, "cancelAnimationFrame", {
      configurable: true,
      value: (id: number) => {
        const frame = pendingFrames.find((candidate) => candidate.id === id);
        if (frame !== undefined) frame.cancelled = true;
      },
    });

    try {
      const { container } = render(
        <App
          runtime={runtimePort}
          settingsAdapter={settings(configuredDocument)}
          historyAdapter={historyAdapter}
          projectPicker={{ pick: vi.fn(async () => null) }}
          workbenchAdapters={{ ...DEFAULT_WORKBENCH_ADAPTERS, workspace: workspaceAdapter }}
        />,
      );

      await selectSessionThread(thread.title, runtimePort);
      const composer = screen.getByRole("textbox", { name: "消息" });
      await user.click(composer);
      await user.type(composer, "@src");
      await waitFor(() =>
        expect(runtimePort.query).toHaveBeenCalledWith(
          "workspace/path/search",
          expect.objectContaining({
            threadId: thread.threadId,
            workspaceId: sessionWorkspace.workspaceId,
            query: "src",
          }),
        ),
      );
      await user.click(await screen.findByRole("option", { name: /main\.ts/u }));
      const source = screen.getByRole("button", { name: "在文件中预览 main.ts" });
      const framesBeforeOpen = nextFrameId;
      const readsBeforeOpen = readFile.mock.calls.length;
      await user.click(source);
      await waitFor(() => expect(readFile.mock.calls.length).toBeGreaterThan(readsBeforeOpen));

      const workbench = container.querySelector<HTMLElement>("#workbench");
      const workbenchHost = container.querySelector<HTMLElement>(
        `#workbench .ja-thread-workbench-session[data-thread-workbench-scope="${sessionWorkspace.workspaceId}:${thread.threadId}"]`,
      );
      await waitFor(() => {
        expect(workbench).not.toHaveAttribute("hidden");
        expect(workbenchHost).not.toHaveAttribute("hidden");
        expect(workbenchHost).not.toHaveAttribute("inert");
      });
      const closeFile = await within(workbenchHost!).findByRole("button", {
        name: "关闭 main.ts",
      });
      const openedDocument = workbenchHost?.querySelector<HTMLElement>(
        '[data-document-path="src/main.ts"]',
      );
      expect(openedDocument).not.toBeNull();
      const pendingOpenBatch = pendingFrames.filter(
        (frame) => frame.id > framesBeforeOpen && !frame.cancelled && !frame.fired,
      );
      expect(pendingOpenBatch.length).toBeGreaterThan(0);
      const pendingOpenFrameIds = new Set(pendingOpenBatch.map((frame) => frame.id));

      await user.click(closeFile);
      await waitFor(() =>
        expect(workbenchHost?.querySelector('[data-document-path="src/main.ts"]')).toBeNull(),
      );
      const lateCancelledOpenFrames = pendingFrames.filter(
        (frame) => pendingOpenFrameIds.has(frame.id) && frame.cancelled,
      );
      expect(lateCancelledOpenFrames.length).toBeGreaterThan(0);
      await advanceAnimationFrameBatch(framesBeforeOpen);
      await waitFor(() => expect(source).toHaveFocus());

      // 显式迟到投递取消的旧帧，确认其 request fence 不会抢走刚恢复的来源焦点。
      await act(async () => {
        for (const frame of lateCancelledOpenFrames) {
          frame.fired = true;
          frame.callback(0);
        }
      });
      expect(source).toHaveFocus();

      const framesBeforeReopen = nextFrameId;
      const readsBeforeReopen = readFile.mock.calls.length;
      await user.click(source);
      await waitFor(() => expect(readFile.mock.calls.length).toBeGreaterThan(readsBeforeReopen));
      const reopenedCloseFile = await within(workbenchHost!).findByRole("button", {
        name: "关闭 main.ts",
      });
      const reopenedFileTab = workbenchHost?.querySelector<HTMLButtonElement>(
        '[data-file-tab-path="src/main.ts"] [role="tab"]',
      );
      expect(reopenedFileTab).toBeInstanceOf(HTMLButtonElement);
      expect(
        pendingFrames.some(
          (frame) => frame.id > framesBeforeReopen && !frame.cancelled && !frame.fired,
        ),
      ).toBe(true);
      await advanceAnimationFrameBatch(framesBeforeReopen);
      await waitFor(() => expect(reopenedFileTab).toHaveFocus());
      expect(reopenedCloseFile).toBeInTheDocument();

      const framesBeforeSecondClose = nextFrameId;
      await user.click(reopenedCloseFile);
      await waitFor(() => {
        expect(workbenchHost?.querySelector('[data-document-path="src/main.ts"]')).toBeNull();
      });
      await advanceAnimationFrameBatch(framesBeforeSecondClose);
      await waitFor(() => expect(source).toHaveFocus());

      const framesBeforeUserFocusReopen = nextFrameId;
      const readsBeforeUserFocusReopen = readFile.mock.calls.length;
      await user.click(source);
      await waitFor(() =>
        expect(readFile.mock.calls.length).toBeGreaterThan(readsBeforeUserFocusReopen),
      );
      const userFocusedCloseFile = await within(workbenchHost!).findByRole("button", {
        name: "关闭 main.ts",
      });
      const userFocusedFileTab = workbenchHost?.querySelector<HTMLButtonElement>(
        '[data-file-tab-path="src/main.ts"] [role="tab"]',
      );
      expect(userFocusedFileTab).toBeInstanceOf(HTMLButtonElement);
      const userFocusFrameIds = new Set(
        pendingFrames
          .filter(
            (frame) => frame.id > framesBeforeUserFocusReopen && !frame.cancelled && !frame.fired,
          )
          .map((frame) => frame.id),
      );
      expect(userFocusFrameIds.size).toBeGreaterThan(0);
      await user.click(composer);
      expect(composer).toHaveFocus();
      expect(
        pendingFrames.some(
          (frame) => userFocusFrameIds.has(frame.id) && !frame.cancelled && !frame.fired,
        ),
      ).toBe(true);
      await advanceAnimationFrameBatch(framesBeforeUserFocusReopen);
      expect(composer).toHaveFocus();
      expect(userFocusedCloseFile).toBeInTheDocument();
      expect(workbenchHost?.querySelector('[data-document-path="src/main.ts"]')).not.toBeNull();

      const framesBeforeUserFocusClose = nextFrameId;
      await user.click(userFocusedCloseFile);
      await waitFor(() =>
        expect(workbenchHost?.querySelector('[data-document-path="src/main.ts"]')).toBeNull(),
      );
      await advanceAnimationFrameBatch(framesBeforeUserFocusClose);
      await waitFor(() => expect(source).toHaveFocus());
      expect(workbenchHost?.querySelector('[data-document-path="src/main.ts"]')).toBeNull();
    } finally {
      cleanup();
      Object.defineProperty(window, "requestAnimationFrame", {
        configurable: true,
        value: originalRequestAnimationFrame,
      });
      Object.defineProperty(window, "cancelAnimationFrame", {
        configurable: true,
        value: originalCancelAnimationFrame,
      });
    }
  });

  /** 同一 workspace 的同路径文档各有 Thread owner；打开提交只能将当前可见 Host 的标签设为焦点。 */
  it("keeps same-path workspace file focus scoped to the active Thread host", async () => {
    const user = userEvent.setup();
    const primaryThread = threadFixture();
    const secondaryThread = { ...threadFixture(), threadId: "thr_secondary", title: "另一个会话" };
    const runtimePort = runtimeWithWorkspacePathSearch();
    const historyAdapter = history();
    historyAdapter.threadList = vi.fn(async () => ({
      items: [primaryThread, secondaryThread],
      nextCursor: null,
    }));
    const workspaceAdapter = focusWorkspaceAdapter();
    const readFile = vi.mocked(workspaceAdapter.readFile);

    const { container } = render(
      <App
        runtime={runtimePort}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
        workbenchAdapters={{ ...DEFAULT_WORKBENCH_ADAPTERS, workspace: workspaceAdapter }}
      />,
    );

    await selectSessionThread(primaryThread.title, runtimePort);
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    await user.type(composer, "@src");
    await waitFor(() =>
      expect(runtimePort.query).toHaveBeenCalledWith(
        "workspace/path/search",
        expect.objectContaining({
          threadId: primaryThread.threadId,
          workspaceId: sessionWorkspace.workspaceId,
          query: "src",
        }),
      ),
    );
    await user.click(await screen.findByRole("option", { name: /main\.ts/u }));
    const readsBeforePrimaryOpen = readFile.mock.calls.length;
    await user.click(screen.getByRole("button", { name: "在文件中预览 main.ts" }));
    await waitFor(() => expect(readFile.mock.calls.length).toBeGreaterThan(readsBeforePrimaryOpen));

    const primaryHost = container.querySelector<HTMLElement>(
      `#workbench .ja-thread-workbench-session[data-thread-workbench-scope="${sessionWorkspace.workspaceId}:${primaryThread.threadId}"]`,
    );
    const primaryTab = primaryHost?.querySelector<HTMLButtonElement>(
      '[data-file-tab-path="src/main.ts"] [role="tab"]',
    );
    expect(primaryTab).toBeInstanceOf(HTMLButtonElement);
    await waitFor(() => expect(primaryTab).toHaveFocus());

    const secondaryConversation = screen.getByRole("button", { name: secondaryThread.title });
    await user.click(secondaryConversation);
    await waitFor(() => expect(secondaryConversation).toHaveAttribute("data-active", "true"));
    await waitFor(() =>
      expect(historyAdapter.threadRead).toHaveBeenLastCalledWith({
        threadId: secondaryThread.threadId,
        tail: true,
        limit: 200,
      }),
    );
    const secondaryComposer = screen.getByRole("textbox", { name: "消息" });
    await user.click(secondaryComposer);
    await user.type(secondaryComposer, "@src");
    await waitFor(() =>
      expect(runtimePort.query).toHaveBeenLastCalledWith(
        "workspace/path/search",
        expect.objectContaining({
          threadId: secondaryThread.threadId,
          workspaceId: sessionWorkspace.workspaceId,
          query: "src",
        }),
      ),
    );
    await user.click(await screen.findByRole("option", { name: /main\.ts/u }));
    const readsBeforeSecondaryOpen = readFile.mock.calls.length;
    await user.click(screen.getByRole("button", { name: "在文件中预览 main.ts" }));
    await waitFor(() =>
      expect(readFile.mock.calls.length).toBeGreaterThan(readsBeforeSecondaryOpen),
    );

    const secondaryHost = container.querySelector<HTMLElement>(
      `#workbench .ja-thread-workbench-session[data-thread-workbench-scope="${sessionWorkspace.workspaceId}:${secondaryThread.threadId}"]`,
    );
    const secondaryTab = secondaryHost?.querySelector<HTMLButtonElement>(
      '[data-file-tab-path="src/main.ts"] [role="tab"]',
    );
    expect(secondaryTab).toBeInstanceOf(HTMLButtonElement);
    await waitFor(() => {
      expect(primaryHost).toHaveAttribute("hidden");
      expect(primaryHost).toHaveAttribute("inert");
      expect(secondaryHost).not.toHaveAttribute("hidden");
      expect(secondaryHost).not.toHaveAttribute("inert");
      expect(secondaryTab).toHaveFocus();
    });
    expect(primaryTab).not.toHaveFocus();
    expect(primaryHost?.querySelector('[data-document-path="src/main.ts"]')).not.toBeNull();
    expect(secondaryHost?.querySelector('[data-document-path="src/main.ts"]')).not.toBeNull();
  });

  /**
   * Workspace 引用沿用真实 Files 文档生命周期：内部文件关闭回到来源；能力 Tab flush 未完成时
   * 保留用户新焦点，移除引用后关掉最后 Files Tab 则返回 Composer。
   */
  it("preserves workspace reference focus across file and Files capability closes", async () => {
    const user = userEvent.setup();
    const runtimePort = runtimeWithWorkspacePathSearch();
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
    let acknowledgeSave: (() => void) | undefined;
    const savedRevision = {
      kind: "file" as const,
      size: 24,
      modifiedUnixMillis: 2,
      sha256: "sha-saved",
    };
    const saveFile: typeof DEFAULT_WORKBENCH_ADAPTERS.workspace.saveFile = ({ relativePath }) =>
      new Promise((resolve) => {
        acknowledgeSave = () => resolve({ relativePath, revision: savedRevision });
      });
    const workspaceAdapter = focusWorkspaceAdapter({ saveFile: vi.fn(saveFile) });
    const { container } = render(
      <App
        runtime={runtimePort}
        settingsAdapter={settings(configuredDocument)}
        historyAdapter={historyAdapter}
        projectPicker={{ pick: vi.fn(async () => null) }}
        workbenchAdapters={{ ...DEFAULT_WORKBENCH_ADAPTERS, workspace: workspaceAdapter }}
      />,
    );

    await selectSessionThread("新对话", runtimePort);
    await screen.findByRole("combobox", { name: "访问模式" });
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    await user.type(composer, "@src");
    await waitFor(() =>
      expect(runtimePort.query).toHaveBeenCalledWith(
        "workspace/path/search",
        expect.objectContaining({
          threadId: "thr_fixture",
          workspaceId: sessionWorkspace.workspaceId,
          query: "src",
        }),
      ),
    );
    await user.click(await screen.findByRole("option", { name: /main\.ts/u }));
    const source = screen.getByRole("button", { name: "在文件中预览 main.ts" });
    await user.click(source);
    await waitFor(() =>
      expect(workspaceAdapter.readFile).toHaveBeenCalledWith({
        workspaceId: sessionWorkspace.workspaceId,
        relativePath: "src/main.ts",
      }),
    );
    const closeFile = await screen.findByRole("button", { name: "关闭 main.ts" });
    await user.click(closeFile);
    await waitFor(() => expect(source).toHaveFocus());

    await user.click(source);
    const editor = await screen.findByRole("textbox", { name: "编辑文件 src/main.ts" });
    await user.click(editor);
    await user.keyboard("{Control>}a{/Control}export const updated = 2;");
    await waitFor(() =>
      expect(
        editor
          .closest<HTMLElement>("[data-document-path='src/main.ts']")
          ?.querySelector('.ja-files-editor-status[data-status="dirty"]'),
      ).not.toBeNull(),
    );

    const workbench = container.querySelector<HTMLElement>("#workbench");
    const workbenchSession = workbench?.querySelector<HTMLElement>(
      ".ja-thread-workbench-session:not([hidden])",
    );
    await user.click(screen.getByRole("button", { name: "新建标签页" }));
    await user.click(await screen.findByRole("menuitem", { name: /终端/u }));
    const terminalTab = await waitFor(() => {
      const tab = workbenchSession?.querySelector<HTMLElement>('[data-workbench-tab="terminal"]');
      expect(tab).not.toBeNull();
      return tab;
    });
    const filesTab = workbenchSession?.querySelector<HTMLElement>('[data-workbench-tab="files"]');
    const closeFilesTab =
      filesTab?.parentElement?.querySelector<HTMLButtonElement>('[data-tab-close="true"]');
    expect(closeFilesTab).toBeInstanceOf(HTMLButtonElement);
    await user.click(closeFilesTab!);
    await waitFor(() => expect(workspaceAdapter.saveFile).toHaveBeenCalledOnce());
    expect(acknowledgeSave).toBeDefined();
    expect(workbenchSession?.querySelector('[data-workbench-tab="files"]')).toBe(filesTab);

    await user.click(terminalTab!);
    expect(terminalTab).toHaveFocus();
    await act(async () => {
      acknowledgeSave?.();
      await Promise.resolve();
    });
    await waitFor(() =>
      expect(workbenchSession?.querySelector('[data-workbench-tab="files"]')).toBeNull(),
    );
    expect(terminalTab).toHaveFocus();

    const removeReference = screen.getByRole("button", { name: /移除上下文/u });
    await user.click(removeReference);
    expect(source).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "新建标签页" }));
    await user.click(await screen.findByRole("menuitem", { name: /文件/u }));
    const reopenedFilesTab = await waitFor(() => {
      const tab = workbenchSession?.querySelector<HTMLElement>('[data-workbench-tab="files"]');
      expect(tab).not.toBeNull();
      return tab;
    });
    const closeTerminalTab =
      terminalTab?.parentElement?.querySelector<HTMLButtonElement>('[data-tab-close="true"]');
    expect(closeTerminalTab).toBeInstanceOf(HTMLButtonElement);
    await user.click(closeTerminalTab!);
    await waitFor(() =>
      expect(workbenchSession?.querySelector('[data-workbench-tab="terminal"]')).toBeNull(),
    );
    const closeLastFilesTab =
      reopenedFilesTab?.parentElement?.querySelector<HTMLButtonElement>('[data-tab-close="true"]');
    expect(closeLastFilesTab).toBeInstanceOf(HTMLButtonElement);
    await user.click(closeLastFilesTab!);
    await waitFor(() => expect(workbench).toHaveAttribute("hidden"));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "消息" })).toHaveFocus());
  }, 15_000);
});
