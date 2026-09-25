// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { useEffect, useState, type PropsWithChildren } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useSettingsController } from "@/features/settings/application/useSettingsController";
import type {
  LoadedSettings,
  SettingsDocument,
  SettingsMcpServer,
} from "@/features/settings/domain/types";
import type { SettingsAdapter } from "@/features/settings/application/ports";

const NO_PROJECT_OVERRIDES = {
  defaultSelection: false,
  accessMode: false,
  disabledSkillReferences: [],
};

const MCP: SettingsMcpServer = {
  mcpRevision: "mcp_local",
  name: "Local Tools",
  transport: "stdio",
  endpoint: "node.exe",
  protocolVersion: "2025-06-18",
  args: ["server.mjs"],
  env: { MCP_MODE: "test" },
  headers: {},
  auth: { kind: "none" },
  enabled: true,
};
let activeClient: QueryClient | undefined;

/** 每个用例隔离 Query cache，避免旧 generation 的 MCP 观测泄漏到下一用例。 */
function QueryWrapper({ children }: PropsWithChildren): React.ReactElement {
  const [client] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  );
  useEffect(() => {
    activeClient = client;
    return () => {
      if (activeClient === client) activeClient = undefined;
      client.clear();
    };
  }, [client]);
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** 生成只包含设置 controller 所需字段的文档，测试重点保持在 MCP 状态边界。 */
function documentWithMcp(server: SettingsMcpServer): SettingsDocument {
  return {
    schemaVersion: 2,
    revision: 1,
    theme: "system",
    defaultAccessMode: "full_access",
    clarificationEnabled: true,
    defaultSelection: null,
    subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
    providers: [],
    mcpServers: [structuredClone(server)],
    disabledSkills: [],
    window: { width: 1280, height: 800, maximized: false },
  };
}

/** 每次 snapshot 都从当前文档复制，模拟真实 adapter 的 CAS 保存后权威回读。 */
function loadedFrom(document: SettingsDocument): LoadedSettings {
  return {
    document: structuredClone(document),
    userDocument: structuredClone(document),
    projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
    cas: { userVersion: "cfg_user", projectVersion: "cfg_project", credentialVersion: "cfg_auth" },
    issues: [],
  };
}

/** 等待首屏并行读取落稳，确保每项测试只观察它主动触发的 MCP 结果。 */
async function waitForSettingsQueriesIdle(): Promise<void> {
  await waitFor(() => {
    const queries = activeClient?.getQueryCache().getAll() ?? [];
    expect(queries.length).toBeGreaterThan(0);
    expect(queries.every((query) => query.state.fetchStatus === "idle")).toBe(true);
  });
}

/** 构造真实 hook 端口；测试通过 runtime list/test 结果控制观测状态而非直接改 Query cache。 */
function optionsFor(
  snapshot: SettingsAdapter["snapshot"],
  save: SettingsAdapter["save"],
  runtime: Partial<Parameters<typeof useSettingsController>[0]["runtimePort"]> = {},
): Parameters<typeof useSettingsController>[0] {
  return {
    adapter: {
      snapshot,
      save,
      saveProjectSkills: vi.fn(async () => "cfg_project"),
      saveProjectMcpServers: vi.fn(async () => "cfg_project"),
      patch: vi.fn(async () => ({ version: "cfg_project" })),
      reset: vi.fn(async () => ({ version: "cfg_project" })),
      restoreLastKnownGood: vi.fn(async () => "cfg_user"),
      setCredential: vi.fn(async () => "cfg_auth"),
      deleteCredential: vi.fn(async () => "cfg_auth"),
      revealProviderCredential: vi.fn(async () => null),
    },
    appearancePort: {
      themeMode: "system",
      palette: "xcode",
      reducedMotion: false,
      reducedTransparency: false,
      highContrast: false,
      setThemeMode: vi.fn(),
      setPalette: vi.fn(),
      setHighContrast: vi.fn(),
      setReduceMotion: vi.fn(),
      setReducedTransparency: vi.fn(),
    },
    workspaceScope: undefined,
    runtimeState: { status: "ready", generation: 1, serverInstanceId: "server" },
    boot: { status: "ready" },
    configurationChange: undefined,
    runtimePort: {
      listSkills: vi.fn(async () => ({ items: [], nextCursor: null })),
      listMcpServers: vi.fn(async () => ({ items: [], nextCursor: null })),
      testMcp: vi.fn(async () => ({
        mcpId: MCP.mcpRevision,
        scope: "global" as const,
        status: "healthy" as const,
        toolCount: 1,
      })),
      listMcpTools: vi.fn(async () => ({ items: [], nextCursor: null })),
      testModel: vi.fn(async () => ({ responseModel: "test", latencyMs: 1 })),
      discoverModels: vi.fn(async () => ({ items: [], truncated: false })),
      ...runtime,
    },
  };
}

afterEach(() => {
  cleanup();
  activeClient = undefined;
});

describe("useSettingsController MCP state", () => {
  /** 不健康 probe 不应再请求目录；它必须立即替换旧的 unknown/工具数投影。 */
  it("unavailable probe 跳过工具目录读取并写入错误状态", async () => {
    let document = documentWithMcp(MCP);
    const snapshot = vi.fn(async () => loadedFrom(document));
    const save = vi.fn(async (next: SettingsDocument) => {
      document = structuredClone(next);
      return "cfg_next";
    });
    const listMcpServers = vi.fn(async () => ({
      items: [
        {
          mcpId: MCP.mcpRevision,
          scope: "global" as const,
          name: MCP.name,
          transport: MCP.transport,
          status: "healthy" as const,
          toolCount: 0,
        },
      ],
      nextCursor: null,
    }));
    const listMcpTools = vi.fn(async () => ({
      items: [{ name: "stale_tool", description: "stale" }],
      nextCursor: null,
    }));
    const options = optionsFor(snapshot, save, {
      listMcpServers,
      testMcp: vi.fn(async () => ({
        mcpId: MCP.mcpRevision,
        scope: "global" as const,
        status: "unavailable" as const,
        toolCount: 0,
      })),
      listMcpTools,
    });
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.globalSnapshot.mcpServers).toHaveLength(1));
    await waitFor(() => expect(listMcpServers).toHaveBeenCalled());
    await waitFor(() =>
      expect(activeClient?.getQueryCache().getAll().at(-1)?.state.fetchStatus).toBe("idle"),
    );
    await act(async () => result.current.ports.onTestMcp(MCP.mcpRevision, "user"));
    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("error"));
    expect(listMcpTools).not.toHaveBeenCalled();
    expect(result.current.globalSnapshot.mcpServers[0]?.tools).toEqual([]);
    expect(result.current.globalSnapshot.mcpServers[0]?.lastError).toBe(
      "MCP 服务不可用；请检查服务状态和连接配置。",
    );

    await act(async () => result.current.ports.onSaveMcp({ ...MCP, enabled: false }, "user"));
    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.enabled).toBe(false));
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.enabled).toBe(false);
    expect(server?.status).toBe("disabled");
    expect(server?.tools).toEqual([]);
    expect(server?.lastError).toBeUndefined();
  });

  /** RPC rejection also becomes an authoritative card error instead of leaving the prior unknown state. */
  it("MCP probe RPC 异常写入错误状态且不读取工具目录", async () => {
    const document = documentWithMcp(MCP);
    const listMcpTools = vi.fn(async () => ({ items: [], nextCursor: null }));
    const options = optionsFor(
      vi.fn(async () => loadedFrom(document)),
      vi.fn(async () => "cfg_next"),
      {
        testMcp: vi.fn(async () => {
          throw new Error("RPC transport failed");
        }),
        listMcpTools,
      },
    );
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.loaded).toBeDefined());
    await waitForSettingsQueriesIdle();
    await act(async () => {
      await expect(result.current.ports.onTestMcp(MCP.mcpRevision, "user")).resolves.toBe("error");
    });

    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("error"));
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.status).toBe("error");
    expect(server?.tools).toEqual([]);
    expect(server?.lastError).toBe("MCP 检查请求未完成；请检查 Ja 本地运行时后重试。");
    expect(listMcpTools).not.toHaveBeenCalled();
  });

  /** 健康检查与工具发现是两项观测；目录 RPC 失败要清除数量并给卡片明确错误。 */
  it("工具目录 RPC 异常写入可见错误而不显示零工具", async () => {
    const document = documentWithMcp(MCP);
    const options = optionsFor(
      vi.fn(async () => loadedFrom(document)),
      vi.fn(async () => "cfg_next"),
      {
        testMcp: vi.fn(async () => ({
          mcpId: MCP.mcpRevision,
          scope: "global" as const,
          status: "available" as const,
          toolCount: 68,
        })),
        listMcpTools: vi.fn(async () => {
          throw new Error("MCP tool catalog RPC failed");
        }),
      },
    );
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.loaded).toBeDefined());
    await waitForSettingsQueriesIdle();
    await act(async () => {
      await expect(result.current.ports.onTestMcp(MCP.mcpRevision, "user")).resolves.toBe("error");
    });

    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("error"));
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.status).toBe("error");
    expect(server?.tools).toEqual([]);
    expect(server?.lastError).toBe("MCP 工具目录读取失败；请重试或检查 MCP 服务兼容性。");
  });

  it("probe 新保存但尚未出现在 catalog 的 Server 时补齐 runtime projection", async () => {
    const document = documentWithMcp(MCP);
    const snapshot = vi.fn(async () => loadedFrom(document));
    const listMcpServers = vi.fn(async () => ({ items: [], nextCursor: null }));
    const options = optionsFor(
      snapshot,
      vi.fn(async () => "cfg_next"),
      {
        listMcpServers,
        testMcp: vi.fn(async () => ({
          mcpId: MCP.mcpRevision,
          scope: "global" as const,
          status: "healthy" as const,
          toolCount: 1,
        })),
        listMcpTools: vi.fn(async () => ({
          items: [{ name: "fresh_tool", description: "fresh" }],
          nextCursor: null,
        })),
      },
    );
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.loaded).toBeDefined());
    await waitFor(() => expect(listMcpServers).toHaveBeenCalled());
    await waitFor(() =>
      expect(activeClient?.getQueryCache().getAll().at(-1)?.state.fetchStatus).toBe("idle"),
    );
    await act(async () => result.current.ports.onTestMcp(MCP.mcpRevision, "user"));

    await waitFor(() =>
      expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("connected"),
    );
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.id).toBe(MCP.mcpRevision);
    expect(server?.status).toBe("connected");
    expect(server?.tools).toEqual([{ name: "fresh_tool", policy: "ask" }]);
  });

  /** 真实 Kerminal 数量在 200 项协议页上限内，应完整保留 68 个安全工具摘要。 */
  it("available probe projects all 68 tools from one catalog page", async () => {
    const document = documentWithMcp(MCP);
    const listMcpTools = vi.fn(async () => ({
      items: Array.from({ length: 68 }, (_, index) => ({
        name: `tool_${index}`,
        description: `Tool ${index}`,
        inputSchema: { type: "object", properties: {} },
      })),
      nextCursor: null,
    }));
    const options = optionsFor(
      vi.fn(async () => loadedFrom(document)),
      vi.fn(async () => "cfg_next"),
      {
        testMcp: vi.fn(async () => ({
          mcpId: MCP.mcpRevision,
          scope: "global" as const,
          status: "available" as const,
          toolCount: 68,
        })),
        listMcpTools,
      },
    );
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());
    await waitForSettingsQueriesIdle();

    await act(async () => {
      await expect(result.current.ports.onTestMcp(MCP.mcpRevision, "user")).resolves.toBe(
        "connected",
      );
    });

    await waitFor(() =>
      expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("connected"),
    );
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(listMcpTools).toHaveBeenCalledTimes(1);
    expect(server?.status).toBe("connected");
    expect(server?.tools).toHaveLength(68);
    expect(server?.tools[0]).toEqual({ name: "tool_0", policy: "ask" });
    expect(server?.lastError).toBeUndefined();
  });

  it("编辑重新启用时失效同一 generation 的旧 disabled 健康 cache", async () => {
    let document = documentWithMcp({ ...MCP, enabled: false });
    const snapshot = vi.fn(async () => loadedFrom(document));
    const save = vi.fn(async (next: SettingsDocument) => {
      document = structuredClone(next);
      return "cfg_next";
    });
    const listMcpServers = vi.fn(async () => ({
      items: [
        {
          mcpId: MCP.mcpRevision,
          scope: "global" as const,
          name: MCP.name,
          transport: MCP.transport,
          status: document.mcpServers[0]?.enabled ? ("healthy" as const) : ("disabled" as const),
          toolCount: 0,
        },
      ],
      nextCursor: null,
    }));
    const options = optionsFor(snapshot, save, { listMcpServers });
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() =>
      expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("disabled"),
    );
    await act(async () => result.current.ports.onSaveMcp({ ...MCP, enabled: true }, "user"));

    await waitFor(() =>
      expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("connected"),
    );
    expect(listMcpServers).toHaveBeenCalledTimes(2);
  });

  it("配置保存跨过 probe 后丢弃晚到结果，保留在途 batch 的后端 lease 边界", async () => {
    let document = documentWithMcp(MCP);
    const snapshot = vi.fn(async () => loadedFrom(document));
    const save = vi.fn(async (next: SettingsDocument) => {
      document = structuredClone(next);
      return "cfg_next";
    });
    let resolveProbe!: (value: {
      mcpId: string;
      scope: "global";
      status: "healthy";
      toolCount: number;
    }) => void;
    const probe = new Promise<{
      mcpId: string;
      scope: "global";
      status: "healthy";
      toolCount: number;
    }>((resolve) => {
      resolveProbe = resolve;
    });
    const options = optionsFor(snapshot, save, {
      listMcpServers: vi.fn(async () => ({ items: [], nextCursor: null })),
      testMcp: vi.fn(() => probe),
    });
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());

    let pendingProbe: Promise<unknown>;
    await act(async () => {
      pendingProbe = result.current.ports.onTestMcp(MCP.mcpRevision, "user");
    });
    await act(async () => result.current.ports.onSaveMcp({ ...MCP, enabled: false }, "user"));
    resolveProbe({ mcpId: MCP.mcpRevision, scope: "global", status: "healthy", toolCount: 1 });
    await act(async () => pendingProbe);

    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.enabled).toBe(false));
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.enabled).toBe(false);
    expect(server?.status).toBe("disabled");
    expect(server?.tools).toEqual([]);
  });

  /** 新探测推进身份序号，旧 probe 晚到时不能覆盖最新健康状态或重读目录。 */
  it("忽略较新测试之后返回的旧 probe", async () => {
    const document = documentWithMcp(MCP);
    type ProbeResult = {
      mcpId: string;
      scope: "global";
      status: "unavailable" | "available";
      toolCount: number;
    };
    let resolveOldProbe!: (result: ProbeResult) => void;
    const oldProbe = new Promise<ProbeResult>((resolve) => {
      resolveOldProbe = resolve;
    });
    const testMcp = vi
      .fn()
      .mockReturnValueOnce(oldProbe)
      .mockResolvedValueOnce({
        mcpId: MCP.mcpRevision,
        scope: "global" as const,
        status: "available" as const,
        toolCount: 1,
      });
    const listMcpTools = vi.fn(async () => ({
      items: [{ name: "fresh_tool", description: "fresh" }],
      nextCursor: null,
    }));
    const options = optionsFor(
      vi.fn(async () => loadedFrom(document)),
      vi.fn(async () => "cfg_next"),
      { testMcp, listMcpTools },
    );
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());
    await waitForSettingsQueriesIdle();

    let oldRequest!: Promise<unknown>;
    await act(async () => {
      oldRequest = result.current.ports.onTestMcp(MCP.mcpRevision, "user");
    });
    await waitFor(() => expect(testMcp).toHaveBeenCalledTimes(1));
    let latestRequest!: Promise<unknown>;
    await act(async () => {
      latestRequest = result.current.ports.onTestMcp(MCP.mcpRevision, "user");
    });
    await waitFor(() =>
      expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("connected"),
    );
    await act(async () => latestRequest);

    resolveOldProbe({
      mcpId: MCP.mcpRevision,
      scope: "global",
      status: "unavailable",
      toolCount: 0,
    });
    await act(async () => oldRequest);

    expect(listMcpTools).toHaveBeenCalledTimes(1);
    expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("connected");
    expect(result.current.globalSnapshot.mcpServers[0]?.tools).toEqual([
      { name: "fresh_tool", policy: "ask" },
    ]);
  });

  /** 项目 MCP 只提交项目字段 CAS patch，保存和删除均不重写全局文档。 */
  it("saves and deletes project MCP through the project adapter", async () => {
    const global = documentWithMcp(MCP);
    const projectServer: SettingsMcpServer = {
      ...MCP,
      mcpRevision: "mcp_project",
      name: "Project Tools",
    };
    let projectServers = [projectServer];
    const saveUser = vi.fn(async () => "cfg_user_next");
    const snapshot = vi.fn(
      async (): Promise<LoadedSettings> => ({
        ...loadedFrom(global),
        projectMcpServers: structuredClone(projectServers),
      }),
    );
    const options = optionsFor(snapshot, saveUser);
    const saveProject = vi.fn(async (servers: SettingsMcpServer[], workspaceId: string) => {
      expect(workspaceId).toBe("ws_project");
      projectServers = structuredClone(servers);
      return "cfg_project_next";
    });
    options.adapter.saveProjectMcpServers = saveProject;
    options.workspaceScope = { kind: "project", workspaceId: "ws_project" };
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() =>
      expect(result.current.mcpSettings.project?.[0]?.name).toBe("Project Tools"),
    );
    await act(async () =>
      result.current.ports.onSaveMcp({ ...projectServer, enabled: false }, "project"),
    );
    await waitFor(() => expect(result.current.mcpSettings.project?.[0]?.enabled).toBe(false));
    await act(async () => result.current.ports.onDeleteMcp("mcp_project", "project"));
    await waitFor(() => expect(result.current.mcpSettings.project).toEqual([]));
    expect(saveProject).toHaveBeenCalledTimes(2);
    expect(saveUser).not.toHaveBeenCalled();
  });
});
