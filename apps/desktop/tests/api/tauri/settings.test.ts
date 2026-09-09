// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  JA_SETTINGS_COMMANDS,
  parseSettingsConfigurationChange,
  SettingsAdapterError,
  TauriSettingsAdapter,
  type ConfigReadResult,
  type SettingsDocument,
  type SettingsNativeBridge,
} from "@/api/tauri/settings";

const CONFIG = {
  schema_version: 1,
  config_revision: 7,
  default_access_mode: "approval_required",
  default_provider_id: "provider_openai",
  default_model_id: "model_gpt",
  default_reasoning_level: "high",
  providers: [
    {
      provider_id: "provider_openai",
      name: "OpenAI",
      api: "openai_responses",
      base_url: "https://api.openai.com/v1",
      credential_id: "cred_openai",
      network_timeouts: { connect_timeout_ms: 10_000, request_timeout_ms: 120_000 },
      agent_defaults: {
        context: { auto_compact: true },
        turn_limits: { max_model_rounds: 32, max_tool_calls: 128, wall_timeout_ms: 3_600_000 },
      },
      models: [
        {
          model_id: "model_gpt",
          name: "GPT",
          model: "gpt-5.6-sol",
          capabilities: {
            context_window_tokens: 256_000,
            max_output_tokens: 32_000,
          },
          reasoning_level_map: { low: "low", medium: "medium", high: "high" },
          default_reasoning_level: "high",
        },
      ],
    },
  ],
  mcp_servers: [],
  skills: [],
} as const;

/** 构造 configuration/read 的完整脱敏 envelope，避免测试跳过 CAS 和 layer 契约。 */
function readResult(): ConfigReadResult {
  return {
    workspaceId: null,
    trusted: true,
    effective: CONFIG,
    user: { present: true, trusted: true, status: "valid", document: CONFIG },
    project: { present: false, trusted: true, status: "missing", document: null },
    credentials: { cred_openai: { configured: true } },
    cas: { userVersion: "cfg_user", projectVersion: "cfg_missing", credentialVersion: "cfg_auth" },
    diagnostics: [],
  };
}

/** 生成 UI 文档时只增加展示偏好和脱敏凭据状态。 */
function uiDocument(): SettingsDocument {
  return {
    schemaVersion: 1,
    revision: 7,
    theme: "system",
    defaultAccessMode: "approval_required",
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
            name: "GPT",
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
    mcpServers: [],
    skills: [],
    window: { width: 1280, height: 800, maximized: false },
  };
}

describe("TauriSettingsAdapter v1", () => {
  it("maps Provider models and credential status without exposing a secret", async () => {
    const bridge: SettingsNativeBridge = { invoke: vi.fn(async () => readResult()) };
    const loaded = await new TauriSettingsAdapter(bridge).snapshot();

    expect(loaded.document.defaultSelection).toEqual({
      providerId: "provider_openai",
      modelId: "model_gpt",
      reasoningLevel: "high",
    });
    expect(loaded.document.providers[0]?.models[0]?.capabilities.contextWindowTokens).toBe(256_000);
    expect(loaded.document.providers[0]?.credentialConfigured).toBe(true);
    expect(loaded.userDocument.providers[0]?.models[0]?.model).toBe("gpt-5.6-sol");
    expect(JSON.stringify(loaded)).not.toMatch(/apiKey|secret/i);
  });

  it("projects sparse project override identities without retaining the raw project document", async () => {
    const result: ConfigReadResult = {
      ...readResult(),
      workspaceId: "ws_project",
      project: {
        present: true,
        trusted: true,
        status: "valid",
        document: {
          default_provider_id: "provider_openai",
          default_model_id: "model_gpt",
          default_reasoning_level: null,
          default_access_mode: "approval_required",
          skills: [{ skill_id: "skill_one", enabled: false }],
          mcp_servers: [{ mcp_id: "mcp_one", enabled: false }],
        },
      },
    };
    const bridge: SettingsNativeBridge = { invoke: vi.fn(async () => result) };

    const loaded = await new TauriSettingsAdapter(bridge).snapshot({ workspaceId: "ws_project" });

    expect(loaded.projectOverrides).toEqual({
      defaultSelection: true,
      accessMode: true,
      disabledSkillIds: ["skill_one"],
      disabledMcpIds: ["mcp_one"],
    });
    expect(loaded).not.toHaveProperty("projectDocument");
  });

  it("fails closed when effective v1 is invalid instead of returning an empty recovery document", async () => {
    const bridge: SettingsNativeBridge = {
      invoke: vi.fn(async () => ({ ...readResult(), effective: { schema_version: 1 } })),
    };

    await expect(new TauriSettingsAdapter(bridge).snapshot()).rejects.toEqual(
      expect.objectContaining<Partial<SettingsAdapterError>>({ code: "invalid_response" }),
    );
  });

  it("saves only strict snake_case v1 and strips UI credential status", async () => {
    const invoke = vi.fn(async (command: string, args?: Record<string, unknown>) => {
      void args;
      return command === JA_SETTINGS_COMMANDS.replace
        ? { accepted: true, scope: "user", version: "cfg_next" }
        : readResult();
    });
    const adapter = new TauriSettingsAdapter({ invoke });

    await expect(adapter.save(uiDocument(), "cfg_user")).resolves.toBe("cfg_next");
    const replace = invoke.mock.calls.find(([command]) => command === JA_SETTINGS_COMMANDS.replace);
    expect(replace?.[1]).toMatchObject({
      input: {
        scope: "user",
        expectedVersion: "cfg_user",
        document: {
          schema_version: 1,
          providers: [{ provider_id: "provider_openai", models: [{ model_id: "model_gpt" }] }],
        },
      },
    });
    expect(JSON.stringify(replace?.[1])).not.toContain("credentialConfigured");
    expect(JSON.stringify(replace?.[1])).not.toContain("profiles");
  });

  it("round-trips DeepSeek Chat with a Provider-owned credential reference", async () => {
    const native = structuredClone(CONFIG) as unknown as Record<string, unknown> & {
      providers: Array<Record<string, unknown>>;
    };
    native.providers.push({
      ...structuredClone(CONFIG.providers[0]),
      provider_id: "provider_deepseek",
      name: "DeepSeek",
      api: "openai_chat_completions",
      base_url: "https://api.deepseek.com",
      credential_id: "cred_deepseek",
      models: [
        {
          ...structuredClone(CONFIG.providers[0]!.models[0]),
          model_id: "model_deepseek",
          name: "DeepSeek Chat",
          model: "deepseek-chat",
        },
      ],
    });
    const result = readResult();
    const invoke = vi.fn(async (command: string, args?: Record<string, unknown>) => {
      void args;
      return command === JA_SETTINGS_COMMANDS.replace
        ? { accepted: true, scope: "user", version: "cfg_next" }
        : {
            ...result,
            effective: native,
            user: { ...result.user, document: native },
            credentials: {
              cred_openai: { configured: true },
              cred_deepseek: { configured: false },
            },
          };
    });
    const adapter = new TauriSettingsAdapter({ invoke });

    const loaded = await adapter.snapshot();
    expect(loaded.document.providers[1]).toMatchObject({
      api: "openai_chat_completions",
      credentialId: "cred_deepseek",
      credentialConfigured: false,
    });
    expect(loaded.document.providers[0]?.credentialId).toBe("cred_openai");
    await expect(adapter.save(loaded.userDocument, "cfg_user")).resolves.toBe("cfg_next");
    const replace = invoke.mock.calls.find(([command]) => command === JA_SETTINGS_COMMANDS.replace);
    expect(replace?.[1]).toMatchObject({
      input: {
        document: {
          providers: [
            { credential_id: "cred_openai" },
            {
              api: "openai_chat_completions",
              credential_id: "cred_deepseek",
            },
          ],
        },
      },
    });
  });

  it("round-trips MCP transport fields and explicit auth without transport guessing", async () => {
    const native = structuredClone(CONFIG) as unknown as Record<string, unknown> & {
      mcp_servers: unknown[];
    };
    native.mcp_servers = [
      {
        mcp_id: "mcp_docs",
        name: "Docs",
        transport: "streamable_http",
        endpoint: "https://mcp.example.com/rpc",
        args: [],
        env: {},
        headers: { "X-Tenant": "ja" },
        auth: { kind: "header", name: "X-API-Key", credential_id: "cred_mcp_docs" },
        enabled: true,
      },
    ];
    const invoke = vi.fn(async (command: string, args?: Record<string, unknown>) => {
      void args;
      return command === JA_SETTINGS_COMMANDS.replace
        ? { accepted: true, scope: "user", version: "cfg_next" }
        : { ...readResult(), effective: native, user: { ...readResult().user, document: native } };
    });
    const adapter = new TauriSettingsAdapter({ invoke });
    const loaded = await adapter.snapshot();

    expect(loaded.document.mcpServers[0]).toMatchObject({
      args: [],
      env: {},
      headers: { "X-Tenant": "ja" },
      auth: { kind: "header", name: "X-API-Key", credentialRef: "cred_mcp_docs" },
    });
    await adapter.save(loaded.userDocument, "cfg_user");
    const replace = invoke.mock.calls.find(([command]) => command === JA_SETTINGS_COMMANDS.replace);
    expect(replace?.[1]).toMatchObject({
      input: {
        document: {
          mcp_servers: [
            {
              args: [],
              env: {},
              headers: { "X-Tenant": "ja" },
              auth: { kind: "header", name: "X-API-Key", credential_id: "cred_mcp_docs" },
            },
          ],
        },
      },
    });
  });

  it("rejects an unsupported root reasoning selection before invoke", async () => {
    const invoke = vi.fn();
    const document = uiDocument();
    document.defaultSelection = { ...document.defaultSelection!, reasoningLevel: "low" };
    document.providers[0]!.models[0]!.reasoningLevelMap = { high: "high" };

    await expect(new TauriSettingsAdapter({ invoke }).save(document, "cfg_user")).rejects.toEqual(
      expect.objectContaining<Partial<SettingsAdapterError>>({ code: "invalid_input" }),
    );
    expect(invoke).not.toHaveBeenCalled();
  });

  it.each([
    ["CONFIG_CONFLICT", "revision_conflict"],
    ["STORAGE_UNAVAILABLE", "storage_unavailable"],
    ["CONFIG_INVALID", "invalid_input"],
    ["CONFIG_CORRUPTED", "invalid_response"],
  ] as const)(
    "maps current JA-RPC %s without exposing native diagnostics",
    async (nativeCode, code) => {
      const bridge: SettingsNativeBridge = {
        invoke: vi.fn(async () => {
          throw { code: nativeCode, message: "private storage path" };
        }),
      };

      await expect(
        new TauriSettingsAdapter(bridge).setCredential("cred_openai", "test-secret", "cfg_auth"),
      ).rejects.toEqual(expect.objectContaining<Partial<SettingsAdapterError>>({ code }));
    },
  );

  it("projects only typed configuration change invalidation metadata", () => {
    expect(
      parseSettingsConfigurationChange({
        kind: "timeline",
        event: {
          method: "configuration/changed",
          params: { serverInstanceId: "server", generation: 2, version: "cfg_next", scope: "user" },
        },
      }),
    ).toEqual({ serverInstanceId: "server", generation: 2, version: "cfg_next", scope: "user" });
    expect(
      parseSettingsConfigurationChange({
        kind: "timeline",
        event: {
          method: "configuration/changed",
          params: { serverInstanceId: "server", generation: 0, version: "cfg_next", scope: "user" },
        },
      }),
    ).toBeUndefined();
  });
});
