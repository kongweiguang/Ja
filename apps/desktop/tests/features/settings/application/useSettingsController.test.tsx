// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useState, type PropsWithChildren } from "react";
import { describe, expect, it, vi } from "vitest";
import { useSettingsController } from "@/features/settings/application/useSettingsController";
import type { LoadedSettings, SettingsDocument } from "@/features/settings/domain/types";
import type { SettingsAdapter } from "@/features/settings/application/ports";

const NO_PROJECT_OVERRIDES = {
  defaultSelection: false,
  accessMode: false,
  disabledSkillIds: [],
  disabledMcpIds: [],
};

const DOCUMENT: SettingsDocument = {
  schemaVersion: 4,
  revision: 1,
  theme: "system",
  defaultAccessMode: "full_access",
  defaultSelection: { providerId: "provider_one", modelId: "model_one", reasoningLevel: "high" },
  providers: [
    {
      providerId: "provider_one",
      name: "OpenAI",
      provider: "openai",
      api: "openai_responses",
      baseUrl: "https://api.openai.com/v1",
      credentialId: "cred_one",
      credentialConfigured: true,
      networkTimeouts: { connectTimeoutMs: 10_000, requestTimeoutMs: 120_000 },
      agentDefaults: {
        context: { autoCompact: true },
        turnLimits: { maxModelRounds: 32, maxToolCalls: 128, wallTimeoutMs: 3_600_000 },
      },
      models: [
        {
          modelId: "model_one",
          name: "GPT",
          model: "gpt-5.6-sol",
          capabilities: {
            contextWindowTokens: 256_000,
            maxOutputTokens: 32_000,
          },
          reasoningLevelMap: { low: "low", high: "high" },
          defaultReasoningLevel: "high",
        },
      ],
    },
  ],
  mcpServers: [],
  skills: [],
  window: { width: 1280, height: 800, maximized: false },
};

/** Query wrapper 为每个测试隔离 cache，避免 CAS 快照在用例间串联。 */
function QueryWrapper({ children }: PropsWithChildren): React.ReactElement {
  const [client] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: false } } }),
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** 构造动态 scope 回归共用的窄依赖，测试只改写配置读取与 workspace/event 输入。 */
function controllerOptions(
  snapshot: SettingsAdapter["snapshot"],
  save: SettingsAdapter["save"] = vi.fn(async () => "cfg_saved"),
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
      reducedMotion: false,
      highContrast: false,
      setThemeMode: vi.fn(),
      setHighContrast: vi.fn(),
      setReduceMotion: vi.fn(),
    },
    workspaceScope: undefined,
    runtimeState: { status: "ready", generation: 1, serverInstanceId: "server" },
    boot: { status: "ready" },
    configurationChange: undefined,
    runtimePort: {
      listSkills: vi.fn(async () => ({ items: [], nextCursor: null })),
      listMcpServers: vi.fn(async () => ({ items: [], nextCursor: null })),
      testMcp: vi.fn(async () => ({
        mcpId: "mcp",
        status: "healthy" as const,
        toolCount: 0,
      })),
      testModel: vi.fn(async () => ({ responseModel: "gpt-test", latencyMs: 12 })),
      listMcpTools: vi.fn(async () => ({ items: [], nextCursor: null })),
    },
  };
}

/** 为写入不变量测试提供相同 runtime 边界，避免每个用例重新定义无关端口。 */
function renderController(
  snapshot: SettingsAdapter["snapshot"],
  save: SettingsAdapter["save"],
  project = false,
) {
  return renderHook(
    () =>
      useSettingsController({
        ...controllerOptions(snapshot, save),
        workspaceScope: project
          ? { workspaceId: "ws_project", kind: "project" as const }
          : undefined,
      }),
    { wrapper: QueryWrapper },
  );
}

/** 用 Provider 名区分各作用域快照，让晚到请求是否覆盖当前 UI 可以被直接断言。 */
function loadedSettings(providerName: string): LoadedSettings {
  const document = structuredClone(DOCUMENT);
  document.providers[0]!.name = providerName;
  return {
    document,
    userDocument: structuredClone(document),
    projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
    source: "Primary",
    recovered: false,
    cas: {
      userVersion: `cfg_user_${providerName}`,
      projectVersion: `cfg_project_${providerName}`,
      credentialVersion: "cfg_auth",
    },
  };
}

describe("useSettingsController v4", () => {
  it("keeps the current UI while a workspace scope synchronizes and ignores a late old result", async () => {
    let resolveProjectA: ((value: LoadedSettings) => void) | undefined;
    const projectA = new Promise<LoadedSettings>((resolve) => {
      resolveProjectA = resolve;
    });
    const snapshot = vi.fn(async (scope?: { workspaceId: string }): Promise<LoadedSettings> => {
      if (scope?.workspaceId === "ws_project_a") return projectA;
      if (scope?.workspaceId === "ws_project_b") return loadedSettings("Project B");
      return loadedSettings("General");
    });
    const base = controllerOptions(snapshot);
    const { result, rerender } = renderHook(
      ({ workspaceId }: { workspaceId?: string }) =>
        useSettingsController({
          ...base,
          workspaceScope: workspaceId === undefined ? undefined : { workspaceId, kind: "project" },
        }),
      { wrapper: QueryWrapper, initialProps: {} as { workspaceId?: string } },
    );

    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("General"));
    expect(result.current.scopeReady).toBe(true);
    rerender({ workspaceId: "ws_project_a" });
    act(() => result.current.setScope("project"));
    await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(2));
    expect(result.current.loading).toBe(false);
    expect(result.current.synchronizing).toBe(true);
    expect(result.current.scopeReady).toBe(false);
    expect(result.current.snapshot.providers[0]?.name).toBe("General");

    rerender({ workspaceId: "ws_project_b" });
    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Project B"));
    expect(result.current.scopeReady).toBe(true);
    expect(result.current.scopeWorkspaceId).toBe("ws_project_b");

    await act(async () => {
      resolveProjectA?.(loadedSettings("Project A"));
      await projectA;
    });
    expect(result.current.snapshot.providers[0]?.name).toBe("Project B");
    expect(
      snapshot.mock.calls.filter(([scope]) => scope?.workspaceId === "ws_project_a"),
    ).toHaveLength(1);
    expect(
      snapshot.mock.calls.filter(([scope]) => scope?.workspaceId === "ws_project_b"),
    ).toHaveLength(1);
  });

  it("reads once for an equal configuration event and ignores another project's event", async () => {
    const snapshot = vi.fn(async () => loadedSettings("Project A"));
    const base = controllerOptions(snapshot);
    const { result, rerender } = renderHook(
      ({ version, workspaceId }: { version?: string; workspaceId?: string }) =>
        useSettingsController({
          ...base,
          workspaceScope: { workspaceId: "ws_project_a", kind: "project" },
          configurationChange:
            version === undefined
              ? undefined
              : {
                  serverInstanceId: "server",
                  generation: 1,
                  version,
                  scope: "project",
                  workspaceId,
                },
        }),
      { wrapper: QueryWrapper, initialProps: {} as { version?: string; workspaceId?: string } },
    );

    await waitFor(() => expect(result.current.scopeReady).toBe(true));
    expect(snapshot).toHaveBeenCalledTimes(1);
    act(() => result.current.setScope("project"));
    await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(2));
    rerender({ version: "cfg_project_2", workspaceId: "ws_project_a" });
    await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(3));
    rerender({ version: "cfg_project_2", workspaceId: "ws_project_a" });
    await act(async () => Promise.resolve());
    expect(snapshot).toHaveBeenCalledTimes(3);

    rerender({ version: "cfg_project_3", workspaceId: "ws_project_b" });
    await act(async () => Promise.resolve());
    expect(snapshot).toHaveBeenCalledTimes(3);
  });

  it("cancels an older same-scope refresh so its late result cannot overwrite a newer version", async () => {
    let call = 0;
    let resolveVersionTwo: ((value: LoadedSettings) => void) | undefined;
    const versionTwo = new Promise<LoadedSettings>((resolve) => {
      resolveVersionTwo = resolve;
    });
    const snapshot = vi.fn(async (): Promise<LoadedSettings> => {
      call += 1;
      if (call === 3) return versionTwo;
      return loadedSettings(call >= 4 ? "Version 3" : call === 2 ? "Project Initial" : "Version 1");
    });
    const base = controllerOptions(snapshot);
    const { result, rerender } = renderHook(
      ({ version }: { version?: string }) =>
        useSettingsController({
          ...base,
          workspaceScope: { workspaceId: "ws_project_a", kind: "project" },
          configurationChange:
            version === undefined
              ? undefined
              : {
                  serverInstanceId: "server",
                  generation: 1,
                  version,
                  scope: "project",
                  workspaceId: "ws_project_a",
                },
        }),
      { wrapper: QueryWrapper, initialProps: {} as { version?: string } },
    );

    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Version 1"));
    act(() => result.current.setScope("project"));
    await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Project Initial"));
    rerender({ version: "cfg_project_2" });
    await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(3));
    expect(result.current.loading).toBe(false);
    expect(result.current.synchronizing).toBe(true);

    rerender({ version: "cfg_project_3" });
    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Version 3"));
    await act(async () => {
      resolveVersionTwo?.(loadedSettings("Version 2"));
      await versionTwo;
    });
    expect(result.current.snapshot.providers[0]?.name).toBe("Version 3");
    expect(snapshot).toHaveBeenCalledTimes(4);
  });

  it("loads Provider projection and saves model/default/access changes through one document owner", async () => {
    let document = structuredClone(DOCUMENT);
    let version = 1;
    const save = vi.fn(async (next: SettingsDocument) => {
      document = structuredClone(next);
      version += 1;
      return `cfg_${version}`;
    });
    const snapshot = vi.fn(
      async (): Promise<LoadedSettings> => ({
        document: structuredClone(document),
        userDocument: structuredClone(document),
        projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
        source: "Primary",
        recovered: false,
        cas: {
          userVersion: `cfg_${version}`,
          projectVersion: "cfg_missing",
          credentialVersion: "cfg_auth",
        },
      }),
    );
    const { result } = renderHook(
      () =>
        useSettingsController({
          adapter: {
            snapshot,
            save,
            patch: vi.fn(async () => ({ version: "cfg_project" })),
            reset: vi.fn(async () => ({ version: "cfg_project" })),
            setCredential: vi.fn(async () => "cfg_auth2"),
            deleteCredential: vi.fn(async () => "cfg_auth3"),
          },
          appearancePort: {
            reducedMotion: false,
            highContrast: false,
            setThemeMode: vi.fn(),
            setHighContrast: vi.fn(),
            setReduceMotion: vi.fn(),
          },
          workspaceScope: undefined,
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "server" },
          boot: { status: "ready" },
          configurationChange: undefined,
          runtimePort: {
            listSkills: vi.fn(async () => ({ items: [], nextCursor: null })),
            listMcpServers: vi.fn(async () => ({ items: [], nextCursor: null })),
            testMcp: vi.fn(async () => ({
              mcpId: "mcp",
              status: "healthy" as const,
              toolCount: 0,
            })),
            testModel: vi.fn(async () => ({ responseModel: "gpt-test", latencyMs: 12 })),
            listMcpTools: vi.fn(async () => ({ items: [], nextCursor: null })),
          },
        }),
      { wrapper: QueryWrapper },
    );

    await waitFor(() =>
      expect(result.current.snapshot.providers[0]?.models[0]?.model).toBe("gpt-5.6-sol"),
    );
    await act(async () =>
      result.current.ports.onSaveModel("provider_one", {
        modelId: "model_two",
        name: "Mini",
        model: "gpt-mini",
        capabilities: {
          contextWindowTokens: 128_000,
          maxOutputTokens: 8_192,
        },
        reasoningLevelMap: {},
        defaultReasoningLevel: null,
      }),
    );
    expect(document.providers[0]?.models).toHaveLength(2);

    await act(async () =>
      result.current.ports.onDefaultSelectionChange({
        providerId: "provider_one",
        modelId: "model_two",
        reasoningLevel: null,
      }),
    );
    await act(async () => result.current.ports.onAccessModeChange("approval_required"));
    expect(document.defaultSelection?.modelId).toBe("model_two");
    expect(document.defaultAccessMode).toBe("approval_required");
    expect(save).toHaveBeenCalledTimes(3);
  });

  it("deleting the default Provider requires and applies an explicit replacement", async () => {
    const second = {
      ...structuredClone(DOCUMENT.providers[0]!),
      providerId: "provider_two",
      credentialId: "cred_two",
      models: [{ ...structuredClone(DOCUMENT.providers[0]!.models[0]!), modelId: "model_two" }],
    };
    let document = { ...structuredClone(DOCUMENT), providers: [...DOCUMENT.providers, second] };
    const save = vi.fn(async (next: SettingsDocument) => {
      document = structuredClone(next);
      return "cfg_next";
    });
    const { result } = renderHook(
      () =>
        useSettingsController({
          adapter: {
            snapshot: vi.fn(async () => ({
              document: structuredClone(document),
              userDocument: structuredClone(document),
              projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
              source: "Primary" as const,
              recovered: false,
              cas: {
                userVersion: "cfg_user",
                projectVersion: "cfg_missing",
                credentialVersion: "cfg_auth",
              },
            })),
            save,
            patch: vi.fn(async () => ({ version: "cfg_project" })),
            reset: vi.fn(async () => ({ version: "cfg_project" })),
            setCredential: vi.fn(async () => "cfg_auth"),
            deleteCredential: vi.fn(async () => "cfg_auth"),
          },
          appearancePort: {
            reducedMotion: false,
            highContrast: false,
            setThemeMode: vi.fn(),
            setHighContrast: vi.fn(),
            setReduceMotion: vi.fn(),
          },
          workspaceScope: undefined,
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "server" },
          boot: { status: "ready" },
          configurationChange: undefined,
          runtimePort: {
            listSkills: vi.fn(async () => ({ items: [], nextCursor: null })),
            listMcpServers: vi.fn(async () => ({ items: [], nextCursor: null })),
            testMcp: vi.fn(),
            testModel: vi.fn(async () => ({ responseModel: "gpt-test", latencyMs: 12 })),
            listMcpTools: vi.fn(async () => ({ items: [], nextCursor: null })),
          },
        }),
      { wrapper: QueryWrapper },
    );
    await waitFor(() => expect(result.current.snapshot.providers).toHaveLength(2));
    await expect(result.current.ports.onDeleteProvider("provider_one", null)).rejects.toThrow(
      "replacement model is required",
    );
    expect(save).not.toHaveBeenCalled();
    await act(async () =>
      result.current.ports.onDeleteProvider("provider_one", {
        providerId: "provider_two",
        modelId: "model_two",
        reasoningLevel: "high",
      }),
    );
    expect(document.defaultSelection).toEqual({
      providerId: "provider_two",
      modelId: "model_two",
      reasoningLevel: "high",
    });
  });

  it("edits the user layer without flattening project overlay values", async () => {
    let userDocument = structuredClone(DOCUMENT);
    const effectiveDocument = structuredClone(DOCUMENT);
    effectiveDocument.providers[0]!.networkTimeouts.requestTimeoutMs = 60_000;
    effectiveDocument.providers[0]!.models[0]!.capabilities.maxOutputTokens = 8_192;
    const save = vi.fn(async (next: SettingsDocument, expectedVersion: string) => {
      expect(expectedVersion).toBe("cfg_user");
      userDocument = structuredClone(next);
      return "cfg_next";
    });
    const snapshot = vi.fn(
      async (): Promise<LoadedSettings> => ({
        document: structuredClone(effectiveDocument),
        userDocument: structuredClone(userDocument),
        projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
        source: "Primary",
        recovered: false,
        cas: {
          userVersion: "cfg_user",
          projectVersion: "cfg_project",
          credentialVersion: "cfg_auth",
        },
      }),
    );
    const { result } = renderController(snapshot, save, true);
    await waitFor(() => expect(result.current.snapshot.providers).toHaveLength(1));
    const effectiveProvider = structuredClone(result.current.snapshot.providers[0]!);
    const { credentialConfigured: _credentialConfigured, ...submitted } = effectiveProvider;
    expect(_credentialConfigured).toBe(true);

    await act(async () =>
      result.current.ports.onSaveProvider({ ...submitted, name: "User Provider" }),
    );

    expect(userDocument.providers[0]?.name).toBe("User Provider");
    expect(userDocument.providers[0]?.networkTimeouts.requestTimeoutMs).toBe(120_000);
    expect(userDocument.providers[0]?.models[0]?.capabilities.maxOutputTokens).toBe(32_000);
  });

  it("reconciles root reasoning when the default model no longer supports it", async () => {
    let userDocument = structuredClone(DOCUMENT);
    const save = vi.fn(async (next: SettingsDocument) => {
      userDocument = structuredClone(next);
      return "cfg_next";
    });
    const snapshot = vi.fn(
      async (): Promise<LoadedSettings> => ({
        document: structuredClone(userDocument),
        userDocument: structuredClone(userDocument),
        projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
        source: "Primary",
        recovered: false,
        cas: {
          userVersion: "cfg_user",
          projectVersion: "cfg_missing",
          credentialVersion: "cfg_auth",
        },
      }),
    );
    const { result } = renderController(snapshot, save);
    await waitFor(() => expect(result.current.snapshot.providers).toHaveLength(1));

    await act(async () =>
      result.current.ports.onSaveModel("provider_one", {
        ...structuredClone(userDocument.providers[0]!.models[0]!),
        reasoningLevelMap: { low: "low" },
        defaultReasoningLevel: "low",
      }),
    );

    expect(userDocument.defaultSelection?.reasoningLevel).toBe("low");
  });

  it("stays global by default and writes project model selection as a scoped merge patch", async () => {
    const snapshot = vi.fn(async (input?: { workspaceId?: string }) =>
      loadedSettings(input?.workspaceId === undefined ? "Global" : "Project"),
    );
    const patch = vi.fn(async () => ({ version: "cfg_project_next" }));
    const options = controllerOptions(snapshot);
    options.adapter.patch = patch;
    options.workspaceScope = { workspaceId: "ws_project", kind: "project" };
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Global"));
    expect(snapshot).toHaveBeenLastCalledWith(undefined);
    act(() => result.current.setScope("project"));
    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Project"));
    await act(async () =>
      result.current.ports.onDefaultSelectionChange({
        providerId: "provider_one",
        modelId: "model_one",
        reasoningLevel: "low",
      }),
    );

    expect(patch).toHaveBeenCalledWith({
      scope: "project",
      workspaceId: "ws_project",
      expectedVersion: "cfg_project_Project",
      patch: {
        default_provider_id: "provider_one",
        default_model_id: "model_one",
        default_reasoning_level: "low",
      },
    });
  });

  it("restores project model inheritance by deleting the complete selection tuple", async () => {
    const patch = vi.fn(async () => ({ version: "cfg_project_next" }));
    const options = controllerOptions(vi.fn(async () => loadedSettings("Project")));
    options.adapter.patch = patch;
    options.workspaceScope = { workspaceId: "ws_project", kind: "project" };
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.scopeReady).toBe(true));
    act(() => result.current.setScope("project"));
    await waitFor(() => expect(result.current.scopeWorkspaceId).toBe("ws_project"));
    await act(async () => result.current.ports.onRestoreDefaultSelection());

    expect(patch).toHaveBeenCalledWith({
      scope: "project",
      workspaceId: "ws_project",
      expectedVersion: "cfg_project_Project",
      patch: {
        default_provider_id: null,
        default_model_id: null,
        default_reasoning_level: null,
      },
    });
  });

  it("rejects project access expansion before issuing a patch", async () => {
    const loaded = loadedSettings("Project");
    loaded.userDocument.defaultAccessMode = "approval_required";
    loaded.document.defaultAccessMode = "approval_required";
    const patch = vi.fn(async () => ({ version: "cfg_project_next" }));
    const options = controllerOptions(vi.fn(async () => loaded));
    options.adapter.patch = patch;
    options.workspaceScope = { workspaceId: "ws_project", kind: "project" };
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.scopeReady).toBe(true));
    act(() => result.current.setScope("project"));
    await waitFor(() => expect(result.current.scopeWorkspaceId).toBe("ws_project"));
    await expect(
      act(async () => result.current.ports.onAccessModeChange("full_access")),
    ).rejects.toThrow("project access cannot exceed global access");
    expect(patch).not.toHaveBeenCalled();
  });

  it("resets with the project CAS version and reloads after a conflicting patch", async () => {
    const snapshot = vi.fn(async () => loadedSettings("Project"));
    const conflict = new Error("revision conflict");
    const patch = vi.fn(async () => Promise.reject(conflict));
    const reset = vi.fn(async () => ({ version: "cfg_project_next" }));
    const options = controllerOptions(snapshot);
    options.adapter.patch = patch;
    options.adapter.reset = reset;
    options.workspaceScope = { workspaceId: "ws_project", kind: "project" };
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.scopeReady).toBe(true));
    act(() => result.current.setScope("project"));
    await waitFor(() => expect(result.current.scopeWorkspaceId).toBe("ws_project"));
    const callsBeforeConflict = snapshot.mock.calls.length;
    let rejected: unknown;
    await act(async () => {
      try {
        await result.current.ports.onDefaultSelectionChange({
          providerId: "provider_one",
          modelId: "model_one",
          reasoningLevel: "low",
        });
      } catch (error) {
        rejected = error;
      }
    });
    expect(rejected).toBe(conflict);
    await waitFor(() => expect(snapshot.mock.calls.length).toBeGreaterThan(callsBeforeConflict));

    await act(async () => result.current.ports.onResetProject());
    expect(reset).toHaveBeenCalledWith({
      scope: "project",
      workspaceId: "ws_project",
      expectedVersion: "cfg_project_Project",
    });
  });
});
