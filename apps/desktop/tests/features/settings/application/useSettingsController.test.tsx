// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { useState, type PropsWithChildren } from "react";
import { describe, expect, it, vi } from "vitest";
import { useSettingsController } from "@/features/settings/application/useSettingsController";
import type { LoadedSettings, SettingsDocument } from "@/features/settings/domain/types";
import type { SettingsAdapter } from "@/features/settings/application/ports";
import type { ProviderSave } from "@/shared/settings/types";

const NO_PROJECT_OVERRIDES = {
  defaultSelection: false,
  accessMode: false,
  disabledSkillIds: [],
  disabledMcpIds: [],
};

const DOCUMENT: SettingsDocument = {
  schemaVersion: 1,
  revision: 1,
  theme: "system",
  defaultAccessMode: "full_access",
  clarificationEnabled: true,
  defaultSelection: { providerId: "provider_one", modelId: "model_one", reasoningLevel: "high" },
  subagents: { enabled: true, providerId: null, modelId: null, reasoningLevel: null },
  providers: [
    {
      providerId: "provider_one",
      name: "OpenAI",
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

/** 凭据快照夹具只改变脱敏 configured 与 CAS，不把测试 Secret 放进配置文档。 */
function credentialSnapshot(configured: boolean, credentialVersion: string): LoadedSettings {
  const document = structuredClone(DOCUMENT);
  document.providers[0]!.credentialConfigured = configured;
  return {
    document: structuredClone(document),
    userDocument: document,
    projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
    cas: {
      userVersion: "cfg_user",
      projectVersion: "cfg_missing",
      credentialVersion,
    },
  };
}

/** 构造独立身份的新 Provider，复用稳定能力值但不携带脱敏 configured 投影。 */
function newProviderSave(): ProviderSave {
  const source = DOCUMENT.providers[0]!;
  return {
    providerId: "provider_two",
    name: "DeepSeek",
    api: "openai_chat_completions",
    baseUrl: "https://api.deepseek.com",
    credentialId: "cred_deep.seek",
    networkTimeouts: structuredClone(source.networkTimeouts),
    agentDefaults: structuredClone(source.agentDefaults),
    models: [
      {
        ...structuredClone(source.models[0]!),
        modelId: "model_deepseek",
        name: "DeepSeek Chat",
        model: "deepseek-chat",
      },
    ],
  };
}

/** 用 Provider 名区分各作用域快照，让晚到请求是否覆盖当前 UI 可以被直接断言。 */
function loadedSettings(providerName: string): LoadedSettings {
  const document = structuredClone(DOCUMENT);
  document.providers[0]!.name = providerName;
  return {
    document,
    userDocument: structuredClone(document),
    projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
    cas: {
      userVersion: `cfg_user_${providerName}`,
      projectVersion: `cfg_project_${providerName}`,
      credentialVersion: "cfg_auth",
    },
  };
}

describe("useSettingsController v1", () => {
  /** 配置 CAS 冲突时必须丢弃本地草稿并展示权威回读，避免用户继续基于旧版本编辑。 */
  it("reloads the authoritative Provider document after a revision conflict", async () => {
    let authoritativeName = "OpenAI";
    const snapshot = vi.fn(async () => loadedSettings(authoritativeName));
    const save = vi.fn(async () => {
      authoritativeName = "Authoritative Provider";
      throw Object.assign(new Error("redacted"), { code: "revision_conflict" });
    });
    const options = controllerOptions(snapshot, save);
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());
    let failure: unknown;

    await act(async () => {
      try {
        await result.current.ports.onSaveProvider({
          ...DOCUMENT.providers[0]!,
          name: "Local Draft",
        });
      } catch (error) {
        failure = error;
      }
    });

    expect(failure).toMatchObject({ code: "revision_conflict" });
    expect(snapshot).toHaveBeenCalledTimes(2);
    await waitFor(() =>
      expect(result.current.globalSnapshot.providers[0]?.name).toBe("Authoritative Provider"),
    );
  });

  it("reloads authoritative credential CAS once and only projects configured after success", async () => {
    let credentialVersion = "cfg_stale";
    let configured = false;
    const snapshot = vi.fn(async () => credentialSnapshot(configured, credentialVersion));
    const setCredential = vi.fn(
      async (_credentialId: string, _secret: string, expected: string) => {
        if (expected === "cfg_stale") {
          credentialVersion = "cfg_fresh";
          throw Object.assign(new Error("redacted"), { code: "revision_conflict" });
        }
        configured = true;
        credentialVersion = "cfg_written";
        return credentialVersion;
      },
    );
    const options = controllerOptions(snapshot);
    options.adapter.setCredential = setCredential;
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() =>
      expect(result.current.globalSnapshot.providers[0]?.credentialConfigured).toBe(false),
    );
    await act(async () => {
      await result.current.ports.onReplaceCredential("cred_one", "test-secret");
    });

    expect(setCredential).toHaveBeenNthCalledWith(1, "cred_one", "test-secret", "cfg_stale");
    expect(setCredential).toHaveBeenNthCalledWith(2, "cred_one", "test-secret", "cfg_fresh");
    expect(snapshot).toHaveBeenCalledTimes(3);
    await waitFor(() =>
      expect(result.current.globalSnapshot.providers[0]?.credentialConfigured).toBe(true),
    );
  });

  /** 凭据写入必须串行消费权威 CAS，避免同时从同一 credentialVersion 发起互相冲突的提交。 */
  it("serializes concurrent credential mutations through authoritative CAS readback", async () => {
    let credentialVersion = "cfg_auth_1";
    let releaseFirst: (() => void) | undefined;
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    let activeMutations = 0;
    let maxActiveMutations = 0;
    const snapshot = vi.fn(async () => credentialSnapshot(true, credentialVersion));
    const setCredential = vi.fn(
      async (_credentialId: string, secret: string, expectedVersion: string) => {
        activeMutations += 1;
        maxActiveMutations = Math.max(maxActiveMutations, activeMutations);
        try {
          if (secret === "first-secret") {
            expect(expectedVersion).toBe("cfg_auth_1");
            await firstGate;
            credentialVersion = "cfg_auth_2";
          } else {
            expect(expectedVersion).toBe("cfg_auth_2");
            credentialVersion = "cfg_auth_3";
          }
          return credentialVersion;
        } finally {
          activeMutations -= 1;
        }
      },
    );
    const options = controllerOptions(snapshot);
    options.adapter.setCredential = setCredential;
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());
    let firstOperation: Promise<void> | undefined;
    let secondOperation: Promise<void> | undefined;

    act(() => {
      firstOperation = result.current.ports.onReplaceCredential("cred_one", "first-secret");
      secondOperation = result.current.ports.onReplaceCredential("cred_one", "second-secret");
    });
    await waitFor(() => expect(setCredential).toHaveBeenCalledTimes(1));
    expect(setCredential).toHaveBeenNthCalledWith(1, "cred_one", "first-secret", "cfg_auth_1");
    releaseFirst?.();
    await act(async () => {
      await Promise.all([firstOperation, secondOperation]);
    });

    expect(setCredential).toHaveBeenNthCalledWith(2, "cred_one", "second-secret", "cfg_auth_2");
    expect(maxActiveMutations).toBe(1);
    expect(snapshot).toHaveBeenCalledTimes(3);
  });

  it("creates Provider before its credential and exposes configured only after authoritative readback", async () => {
    let document = structuredClone(DOCUMENT);
    let userVersion = 1;
    let credentialVersion = "cfg_auth_1";
    const configured = new Set(["cred_one"]);
    const order: string[] = [];
    const snapshot = vi.fn(async (): Promise<LoadedSettings> => {
      const projected = structuredClone(document);
      for (const provider of projected.providers) {
        provider.credentialConfigured = configured.has(provider.credentialId);
      }
      return {
        document: structuredClone(projected),
        userDocument: projected,
        projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
        cas: {
          userVersion: `cfg_user_${userVersion}`,
          projectVersion: "cfg_missing",
          credentialVersion,
        },
      };
    });
    const save = vi.fn(async (next: SettingsDocument, expectedVersion: string) => {
      order.push("provider");
      expect(expectedVersion).toBe(`cfg_user_${userVersion}`);
      document = structuredClone(next);
      userVersion += 1;
      return `cfg_user_${userVersion}`;
    });
    const setCredential = vi.fn(async (credentialId: string, _secret: string, expected: string) => {
      order.push("credential");
      expect(document.providers.some((provider) => provider.credentialId === credentialId)).toBe(
        true,
      );
      expect(expected).toBe("cfg_auth_1");
      configured.add(credentialId);
      credentialVersion = "cfg_auth_2";
      return credentialVersion;
    });
    const options = controllerOptions(snapshot, save);
    options.adapter.setCredential = setCredential;
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());

    await act(async () => {
      await result.current.ports.onCreateProvider(newProviderSave(), "test-secret");
    });

    expect(order).toEqual(["provider", "credential"]);
    expect(setCredential).toHaveBeenCalledWith("cred_deep.seek", "test-secret", "cfg_auth_1");
    await waitFor(() => expect(result.current.globalSnapshot.providers).toHaveLength(2));
    await waitFor(() =>
      expect(
        result.current.globalSnapshot.providers.find(
          (provider) => provider.providerId === "provider_two",
        )?.credentialConfigured,
      ).toBe(true),
    );
  });

  it("retains one recoverable Provider when credential persistence fails and reuses it on retry", async () => {
    let document = structuredClone(DOCUMENT);
    let userVersion = 1;
    let configured = false;
    let credentialAttempts = 0;
    const snapshot = vi.fn(async (): Promise<LoadedSettings> => {
      const projected = structuredClone(document);
      const provider = projected.providers.find((item) => item.providerId === "provider_two");
      if (provider !== undefined) provider.credentialConfigured = configured;
      return {
        document: structuredClone(projected),
        userDocument: projected,
        projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
        cas: {
          userVersion: `cfg_user_${userVersion}`,
          projectVersion: "cfg_missing",
          credentialVersion: credentialAttempts === 0 ? "cfg_auth_1" : "cfg_auth_2",
        },
      };
    });
    const save = vi.fn(async (next: SettingsDocument) => {
      document = structuredClone(next);
      userVersion += 1;
      return `cfg_user_${userVersion}`;
    });
    const setCredential = vi.fn(async () => {
      credentialAttempts += 1;
      if (credentialAttempts === 1) {
        throw Object.assign(new Error("redacted"), { code: "storage_unavailable" });
      }
      configured = true;
      return "cfg_auth_3";
    });
    const options = controllerOptions(snapshot, save);
    options.adapter.setCredential = setCredential;
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());
    const provider = newProviderSave();
    let firstFailure: unknown;

    await act(async () => {
      try {
        await result.current.ports.onCreateProvider(provider, "first-secret");
      } catch (error) {
        firstFailure = error;
      }
    });
    expect(firstFailure).toMatchObject({ code: "provider_saved_credential_failed" });
    await waitFor(() => expect(result.current.globalSnapshot.providers).toHaveLength(2));
    expect(result.current.globalSnapshot.providers[1]?.credentialConfigured).toBe(false);

    await act(async () => {
      await result.current.ports.onCreateProvider(provider, "second-secret");
    });
    expect(
      document.providers.filter((item) => item.providerId === provider.providerId),
    ).toHaveLength(1);
    await waitFor(() => expect(result.current.globalSnapshot.providers).toHaveLength(2));
    await waitFor(() =>
      expect(result.current.globalSnapshot.providers[1]?.credentialConfigured).toBe(true),
    );
  });

  it("does not touch credentials when the Provider document cannot be saved", async () => {
    const snapshot = vi.fn(async () => credentialSnapshot(true, "cfg_auth"));
    const save = vi.fn(async () => {
      throw Object.assign(new Error("redacted"), { code: "storage_unavailable" });
    });
    const setCredential = vi.fn(async () => "cfg_auth_2");
    const options = controllerOptions(snapshot, save);
    options.adapter.setCredential = setCredential;
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.loaded).toBeDefined());
    let failure: unknown;

    await act(async () => {
      try {
        await result.current.ports.onCreateProvider(newProviderSave(), "test-secret");
      } catch (error) {
        failure = error;
      }
    });
    expect(failure).toMatchObject({ code: "storage_unavailable" });
    expect(setCredential).not.toHaveBeenCalled();
  });

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
    rerender({ version: "cfg_project_2", workspaceId: "ws_project_a" });
    await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(2));
    rerender({ version: "cfg_project_2", workspaceId: "ws_project_a" });
    await act(async () => Promise.resolve());
    expect(snapshot).toHaveBeenCalledTimes(2);

    rerender({ version: "cfg_project_3", workspaceId: "ws_project_b" });
    await act(async () => Promise.resolve());
    expect(snapshot).toHaveBeenCalledTimes(2);
  });

  it("cancels an older same-scope refresh so its late result cannot overwrite a newer version", async () => {
    let call = 0;
    let resolveVersionTwo: ((value: LoadedSettings) => void) | undefined;
    const versionTwo = new Promise<LoadedSettings>((resolve) => {
      resolveVersionTwo = resolve;
    });
    const snapshot = vi.fn(async (): Promise<LoadedSettings> => {
      call += 1;
      if (call === 2) return versionTwo;
      return loadedSettings(call >= 3 ? "Version 3" : "Version 1");
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
    rerender({ version: "cfg_project_2" });
    await waitFor(() => expect(snapshot).toHaveBeenCalledTimes(2));
    expect(result.current.loading).toBe(false);
    expect(result.current.synchronizing).toBe(true);

    rerender({ version: "cfg_project_3" });
    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Version 3"));
    await act(async () => {
      resolveVersionTwo?.(loadedSettings("Version 2"));
      await versionTwo;
    });
    expect(result.current.snapshot.providers[0]?.name).toBe("Version 3");
    expect(snapshot).toHaveBeenCalledTimes(3);
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

  it("keeps theme, palette, motion, transparency, and contrast in the UI preference owner", async () => {
    const save = vi.fn(async () => "cfg_next");
    const snapshot = vi.fn(async () => loadedSettings("Global"));
    const options = controllerOptions(snapshot, save);
    const setThemeMode = vi.fn();
    const setPalette = vi.fn();
    const setHighContrast = vi.fn();
    const setReduceMotion = vi.fn();
    const setReducedTransparency = vi.fn();
    options.appearancePort = {
      themeMode: "system",
      palette: "xcode",
      reducedMotion: false,
      reducedTransparency: false,
      highContrast: false,
      setThemeMode,
      setPalette,
      setHighContrast,
      setReduceMotion,
      setReducedTransparency,
    };
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });
    await waitFor(() => expect(result.current.globalSnapshot.providers).toHaveLength(1));
    setThemeMode.mockClear();
    setHighContrast.mockClear();
    setReduceMotion.mockClear();

    await act(async () =>
      result.current.ports.onAppearanceChange(
        {
          theme: "dark",
          palette: "xcode",
          reducedMotion: true,
          reducedTransparency: false,
          highContrast: false,
        },
        "reducedMotion",
      ),
    );
    await act(async () =>
      result.current.ports.onAppearanceChange(
        {
          theme: "dark",
          palette: "xcode",
          reducedMotion: false,
          reducedTransparency: false,
          highContrast: true,
        },
        "highContrast",
      ),
    );

    expect(save).not.toHaveBeenCalled();
    expect(setThemeMode).not.toHaveBeenCalled();
    expect(setPalette).not.toHaveBeenCalled();
    expect(setReduceMotion).toHaveBeenCalledWith(true);
    expect(setHighContrast).toHaveBeenCalledWith(true);

    await act(async () =>
      result.current.ports.onAppearanceChange(
        {
          theme: "dark",
          palette: "obsidian",
          reducedMotion: true,
          reducedTransparency: false,
          highContrast: true,
        },
        "palette",
      ),
    );
    await act(async () =>
      result.current.ports.onAppearanceChange(
        {
          theme: "dark",
          palette: "obsidian",
          reducedMotion: true,
          reducedTransparency: true,
          highContrast: true,
        },
        "reducedTransparency",
      ),
    );

    expect(setPalette).toHaveBeenCalledWith("obsidian");
    expect(setReducedTransparency).toHaveBeenCalledWith(true);

    await act(async () =>
      result.current.ports.onAppearanceChange(
        {
          theme: "dark",
          palette: "obsidian",
          reducedMotion: true,
          reducedTransparency: true,
          highContrast: true,
        },
        "theme",
      ),
    );

    expect(save).not.toHaveBeenCalled();
    expect(setThemeMode).toHaveBeenCalledWith("dark");
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
        cas: {
          userVersion: "cfg_user",
          projectVersion: "cfg_project",
          credentialVersion: "cfg_auth",
        },
      }),
    );
    const { result } = renderController(snapshot, save, true);
    await waitFor(() => expect(result.current.snapshot.providers).toHaveLength(1));
    const globalProvider = structuredClone(result.current.globalSnapshot.providers[0]!);
    const { credentialConfigured: _credentialConfigured, ...submitted } = globalProvider;
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

  it("saves multiple models together and reconciles root reasoning without losing identities", async () => {
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
        cas: {
          userVersion: "cfg_user",
          projectVersion: "cfg_missing",
          credentialVersion: "cfg_auth",
        },
      }),
    );
    const { result } = renderController(snapshot, save);
    await waitFor(() => expect(result.current.snapshot.providers).toHaveLength(1));
    const provider = structuredClone(userDocument.providers[0]!);
    const model = provider.models[0]!;
    provider.models = [
      { ...model, reasoningLevelMap: { low: "low" }, defaultReasoningLevel: "low" },
      { ...model, modelId: "model_two", model: "custom-second", name: "Second" },
    ];
    await act(async () => result.current.ports.onSaveProvider(provider));
    expect(save).toHaveBeenCalledTimes(1);
    expect(userDocument.providers[0]?.models.map((item) => item.modelId)).toEqual([
      "model_one",
      "model_two",
    ]);
    expect(userDocument.defaultSelection).toEqual({
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: "low",
    });
    expect(userDocument.providers[0]?.credentialConfigured).toBe(true);
    expect(userDocument.providers[0]?.agentDefaults).toEqual(DOCUMENT.providers[0]?.agentDefaults);
    await act(async () => {
      await expect(
        result.current.ports.onSaveProvider({
          ...provider,
          models: [provider.models[1]!],
        }),
      ).rejects.toThrow("replacement model is required");
    });
    expect(save).toHaveBeenCalledTimes(1);
  });

  it("keeps project-effective conversation settings while every settings write targets user CAS", async () => {
    let loaded = loadedSettings("Project B");
    loaded.userDocument.providers[0]!.name = "Global A";
    loaded.userDocument.defaultAccessMode = "approval_required";
    loaded.userDocument.skills = [
      {
        skillId: "skill_review",
        name: "Review",
        scope: "user",
        enabled: true,
        description: "Review changes",
      },
    ];
    loaded.document.skills = [{ ...loaded.userDocument.skills[0]!, enabled: false }];
    const globalMcp = {
      mcpRevision: "mcp_local",
      name: "Local Tools",
      transport: "stdio" as const,
      endpoint: "pwsh.exe",
      protocolVersion: "2025-06-18" as const,
      args: [],
      env: {},
      headers: {},
      auth: { kind: "none" as const },
      enabled: true,
    };
    loaded.userDocument.mcpServers = [globalMcp];
    loaded.document.mcpServers = [{ ...globalMcp, enabled: false }];
    const patch = vi.fn(async () => ({ version: "cfg_project_next" }));
    const reset = vi.fn(async () => ({ version: "cfg_project_next" }));
    const save = vi.fn(async (document: SettingsDocument) => {
      loaded = { ...loaded, userDocument: structuredClone(document) };
      return "cfg_user_next";
    });
    const snapshot = vi.fn(async () => structuredClone(loaded));
    const options = controllerOptions(snapshot, save);
    options.adapter.patch = patch;
    options.adapter.reset = reset;
    options.workspaceScope = { workspaceId: "ws_project", kind: "project" };
    options.runtimePort.listSkills = vi.fn(async () => ({
      items: [
        {
          skillId: "skill_review",
          name: "Review",
          scope: "user" as const,
          enabled: false,
          status: "healthy" as const,
          description: "Review changes",
        },
      ],
      nextCursor: null,
    }));
    options.runtimePort.listMcpServers = vi.fn(async () => ({
      items: [
        {
          mcpId: "mcp_local",
          name: "Local Tools",
          transport: "stdio" as const,
          status: "disabled" as const,
          toolCount: 0,
        },
      ],
      nextCursor: null,
    }));
    const { result } = renderHook(() => useSettingsController(options), { wrapper: QueryWrapper });

    await waitFor(() => expect(result.current.snapshot.providers[0]?.name).toBe("Project B"));
    expect(result.current.globalSnapshot.providers[0]?.name).toBe("Global A");
    expect(result.current.snapshot.skills[0]?.enabled).toBe(false);
    expect(result.current.globalSnapshot.skills[0]?.enabled).toBe(true);
    expect(result.current.globalSnapshot.mcpServers[0]?.enabled).toBe(true);
    expect(result.current.scopeWorkspaceId).toBe("ws_project");

    await act(async () =>
      result.current.ports.onDefaultSelectionChange({
        providerId: "provider_one",
        modelId: "model_one",
        reasoningLevel: "low",
      }),
    );
    await act(async () => result.current.ports.onAccessModeChange("full_access"));
    await act(async () => result.current.ports.onToggleSkill("skill_review", false));
    await act(async () => result.current.ports.onSaveMcp({ ...globalMcp, enabled: false }));

    expect(save).toHaveBeenCalledTimes(4);
    expect(patch).not.toHaveBeenCalled();
    expect(reset).not.toHaveBeenCalled();
  });

  /** 子智能体策略复用用户 CAS，并拒绝不存在的模型引用，避免删除模型后静默改派。 */
  it("saves user-scoped subagent settings only for known models", async () => {
    const save = vi.fn(async () => "cfg_subagents");
    const snapshot = vi.fn(async () => credentialSnapshot(true, "cfg_auth"));
    const { result } = renderController(snapshot, save);
    await waitFor(() => expect(result.current.loaded).toBeDefined());

    await act(async () => {
      await result.current.ports.onSubagentSettingsChange({
        enabled: false,
        providerId: "provider_one",
        modelId: "model_one",
        reasoningLevel: null,
      });
    });
    expect(save).toHaveBeenCalledWith(
      expect.objectContaining({
        subagents: {
          enabled: false,
          providerId: "provider_one",
          modelId: "model_one",
          reasoningLevel: null,
        },
      }),
      "cfg_user",
    );

    await expect(
      result.current.ports.onSubagentSettingsChange({
        enabled: true,
        providerId: "provider_one",
        modelId: "model_missing",
        reasoningLevel: null,
      }),
    ).rejects.toThrow("subagent model unavailable");
    expect(save).toHaveBeenCalledTimes(1);
  });

  it("requires supported reasoning levels and clears invalid levels after model edits", async () => {
    let userDocument = structuredClone(DOCUMENT);
    const save = vi.fn(async (next: SettingsDocument) => {
      userDocument = structuredClone(next);
      return "cfg_subagents";
    });
    const snapshot = vi.fn(
      async (): Promise<LoadedSettings> => ({
        document: structuredClone(userDocument),
        userDocument: structuredClone(userDocument),
        projectOverrides: structuredClone(NO_PROJECT_OVERRIDES),
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
      result.current.ports.onSubagentSettingsChange({
        enabled: true,
        providerId: "provider_one",
        modelId: "model_one",
        reasoningLevel: "high",
      }),
    );
    expect(userDocument.subagents.reasoningLevel).toBe("high");
    await expect(
      result.current.ports.onSubagentSettingsChange({
        enabled: true,
        providerId: "provider_one",
        modelId: "model_one",
        reasoningLevel: "medium",
      }),
    ).rejects.toThrow("subagent reasoning level unavailable");
    await expect(
      result.current.ports.onSubagentSettingsChange({
        enabled: true,
        providerId: null,
        modelId: null,
        reasoningLevel: "high",
      }),
    ).rejects.toThrow("subagent reasoning level requires model");

    await act(async () =>
      result.current.ports.onSaveModel("provider_one", {
        ...structuredClone(userDocument.providers[0]!.models[0]!),
        reasoningLevelMap: { low: "low" },
        defaultReasoningLevel: "low",
      }),
    );
    expect(userDocument.subagents.reasoningLevel).toBeNull();
  });

  /** 被全局子智能体策略引用的 Provider 只能在先改策略后删除，不能生成悬空引用。 */
  it("does not delete a Provider referenced by the subagent policy", async () => {
    const loaded = credentialSnapshot(true, "cfg_auth");
    loaded.userDocument.subagents = {
      enabled: true,
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: null,
    };
    loaded.document.subagents = structuredClone(loaded.userDocument.subagents);
    const save = vi.fn(async () => "cfg_unused");
    const { result } = renderController(
      vi.fn(async () => loaded),
      save,
    );
    await waitFor(() => expect(result.current.loaded).toBeDefined());

    await expect(result.current.ports.onDeleteProvider("provider_one", null)).rejects.toThrow(
      "subagent model replacement is required",
    );
    expect(save).not.toHaveBeenCalled();
  });
});
