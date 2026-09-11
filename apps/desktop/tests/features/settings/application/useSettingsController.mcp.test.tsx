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
  disabledSkillIds: [],
  disabledMcpIds: [],
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
    schemaVersion: 1,
    revision: 1,
    theme: "system",
    defaultAccessMode: "full_access",
    clarificationEnabled: true,
    defaultSelection: null,
    subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
    providers: [],
    mcpServers: [structuredClone(server)],
    skills: [],
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
  };
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
      patch: vi.fn(async () => ({ version: "cfg_project" })),
      reset: vi.fn(async () => ({ version: "cfg_project" })),
      setCredential: vi.fn(async () => "cfg_auth"),
      deleteCredential: vi.fn(async () => "cfg_auth"),
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
        status: "healthy" as const,
        toolCount: 1,
      })),
      listMcpTools: vi.fn(async () => ({ items: [], nextCursor: null })),
      testModel: vi.fn(async () => ({ responseModel: "test", latencyMs: 1 })),
      ...runtime,
    },
  };
}

afterEach(() => {
  cleanup();
  activeClient = undefined;
});

describe("useSettingsController MCP state", () => {
  it("停用后以配置事实覆盖旧 connected/tools/error 观测", async () => {
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
          name: MCP.name,
          transport: MCP.transport,
          status: "healthy" as const,
          toolCount: 0,
        },
      ],
      nextCursor: null,
    }));
    const options = optionsFor(snapshot, save, {
      listMcpServers,
      testMcp: vi.fn(async () => ({
        mcpId: MCP.mcpRevision,
        status: "unavailable" as const,
        toolCount: 0,
      })),
      listMcpTools: vi.fn(async () => ({
        items: [{ name: "stale_tool", description: "stale" }],
        nextCursor: null,
      })),
    });
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.globalSnapshot.mcpServers).toHaveLength(1));
    await waitFor(() => expect(listMcpServers).toHaveBeenCalled());
    await waitFor(() =>
      expect(activeClient?.getQueryCache().getAll().at(-1)?.state.fetchStatus).toBe("idle"),
    );
    await act(async () => result.current.ports.onTestMcp(MCP.mcpRevision));
    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("error"));
    expect(result.current.globalSnapshot.mcpServers[0]?.tools).toHaveLength(1);

    await act(async () => result.current.ports.onCloseMcp(MCP.mcpRevision));
    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.enabled).toBe(false));
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.enabled).toBe(false);
    expect(server?.status).toBe("disabled");
    expect(server?.tools).toEqual([]);
    expect(server?.lastError).toBeUndefined();
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
    await act(async () => result.current.ports.onTestMcp(MCP.mcpRevision));

    await waitFor(() =>
      expect(result.current.globalSnapshot.mcpServers[0]?.status).toBe("connected"),
    );
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.id).toBe(MCP.mcpRevision);
    expect(server?.status).toBe("connected");
    expect(server?.tools).toEqual([{ name: "fresh_tool", policy: "ask" }]);
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
    await act(async () => result.current.ports.onSaveMcp({ ...MCP, enabled: true }));

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
    let resolveProbe!: (value: { mcpId: string; status: "healthy"; toolCount: number }) => void;
    const probe = new Promise<{ mcpId: string; status: "healthy"; toolCount: number }>(
      (resolve) => {
        resolveProbe = resolve;
      },
    );
    const options = optionsFor(snapshot, save, {
      listMcpServers: vi.fn(async () => ({ items: [], nextCursor: null })),
      testMcp: vi.fn(() => probe),
    });
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());

    let pendingProbe: Promise<unknown>;
    await act(async () => {
      pendingProbe = result.current.ports.onTestMcp(MCP.mcpRevision);
    });
    await act(async () => result.current.ports.onCloseMcp(MCP.mcpRevision));
    resolveProbe({ mcpId: MCP.mcpRevision, status: "healthy", toolCount: 1 });
    await act(async () => pendingProbe);

    await waitFor(() => expect(result.current.globalSnapshot.mcpServers[0]?.enabled).toBe(false));
    const server = result.current.globalSnapshot.mcpServers[0];
    expect(server?.enabled).toBe(false);
    expect(server?.status).toBe("disabled");
    expect(server?.tools).toEqual([]);
  });
});
