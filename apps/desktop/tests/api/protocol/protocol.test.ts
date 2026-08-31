// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { parseMethodParams } from "@/api/protocol/methods";
import {
  JA_PROTOCOL_MAJOR,
  JA_PROTOCOL_MINOR,
  LimitsSchema,
  ThreadSchema,
  parseNotification,
  parseRequest,
} from "@/api/protocol/protocol";

const limits = {
  maxFrameBytes: 4_194_304,
  maxInFlightRequests: 64,
  maxInboundQueueFrames: 256,
  maxControlOutboundQueueFrames: 64,
  maxDataOutboundQueueFrames: 1_024,
  maxConcurrentTurns: 8,
  maxAdmittedTurns: 64,
  maxThreadQueuedTurns: 8,
  maxSnapshotPageItems: 200,
  maxToolBatchConcurrency: 8,
};

const completeConfigDocument = {
  schema_version: 4,
  config_revision: 0,
  default_access_mode: "full_access",
  default_provider_id: "provider_demo",
  default_model_id: "model_demo",
  default_reasoning_level: "medium",
  providers: [
    {
      provider_id: "provider_demo",
      name: "Demo",
      provider: "openai",
      api: "openai_responses",
      base_url: "http://127.0.0.1:60842",
      credential_id: "cred_demo",
      network_timeouts: { connect_timeout_ms: 10_000, request_timeout_ms: 120_000 },
      agent_defaults: {
        context: { auto_compact: true },
        turn_limits: { max_model_rounds: 32, max_tool_calls: 128, wall_timeout_ms: 600_000 },
      },
      models: [
        {
          model_id: "model_demo",
          name: "Demo Model",
          model: "gpt-test",
          capabilities: {
            context_window_tokens: 200_000,
            max_output_tokens: 8_192,
          },
          reasoning_level_map: { medium: "medium" },
          default_reasoning_level: "medium",
        },
      ],
    },
  ],
  mcp_servers: [],
  skills: [],
} as const;

describe("JA RPC v2 protocol", () => {
  it("accepts a configuration-free initialize request", () => {
    const params = {
      protocolMajor: JA_PROTOCOL_MAJOR,
      protocolMinor: JA_PROTOCOL_MINOR,
      clientVersion: "2.0.0",
      capabilities: {
        methods: ["runtime/initialize"],
        events: ["runtime/status-changed"],
        accessModes: ["approval_required", "full_access"],
      },
      limits,
    } as const;
    expect(parseMethodParams("runtime/initialize", params)).toEqual(params);
    expect(
      parseRequest({ jsonrpc: "2.0", id: "c:init", method: "runtime/initialize", params }),
    ).toMatchObject({ method: "runtime/initialize" });
  });

  it("rejects v1 snapshot/configure and client-owned identity fields", () => {
    expect(() =>
      parseMethodParams("runtime/initialize", {
        protocolMajor: 2,
        protocolMinor: 0,
        clientVersion: "2.0.0",
        configSnapshot: {},
        capabilities: { methods: ["runtime/initialize"], events: [], accessModes: [] },
        limits,
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("thread/create", {
        workspaceId: "ws_client",
        title: "Chat",
        profileId: "profile_demo",
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("turn/start", {
        threadId: "thr_demo",
        content: [{ type: "text", text: "hello" }],
        accessMode: "approval_required",
        profileId: "profile_demo",
        configRevision: "cfg_old",
      }),
    ).toThrow();
  });

  it("requires the frozen v2 CAS wire shapes", () => {
    expect(
      parseMethodParams("configuration/patch", {
        scope: "user",
        patch: { default_provider_id: "provider_demo", default_model_id: "model_demo" },
        expectedVersion: "cfg_missing",
      }),
    ).toMatchObject({
      patch: { default_provider_id: "provider_demo", default_model_id: "model_demo" },
    });
    expect(
      parseMethodParams("configuration/replace", {
        scope: "project",
        workspaceId: "ws_demo",
        document: completeConfigDocument,
        expectedVersion: "cfg_A",
      }),
    ).toMatchObject({ workspaceId: "ws_demo", document: completeConfigDocument });
    expect(
      parseMethodParams("configuration/reset", { scope: "user", expectedVersion: "cfg_missing" }),
    ).toEqual({
      scope: "user",
      expectedVersion: "cfg_missing",
    });
    expect(
      parseMethodParams("credential/set", {
        credentialId: "cred_demo",
        secret: "fixture-secret",
        expectedVersion: "cfg_missing",
      }),
    ).toMatchObject({ credentialId: "cred_demo" });
    expect(
      parseMethodParams("credential/delete", {
        credentialId: "cred_demo",
        expectedVersion: "cfg_A",
      }),
    ).toMatchObject({ credentialId: "cred_demo" });

    expect(() =>
      parseMethodParams("configuration/patch", {
        scope: "user",
        patch: { default_provider_id: "provider_demo" },
        expectedVersion: null,
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("credential/set", {
        credentialId: "cred_demo",
        secret: "fixture-secret",
        expectedVersion: null,
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("configuration/patch", {
        scope: "user",
        patch: { default_provider_id: "provider_demo" },
        expectedVersion: "v1",
      }),
    ).toThrow();
  });

  /** 配置 v3 只接受当前原生 Codec，已删除的 Chat Completions 不保留兼容别名。 */
  it("rejects removed OpenAI Chat Completions providers", () => {
    const document = {
      ...completeConfigDocument,
      providers: [
        { ...completeConfigDocument.providers[0], api: "openai_chat_completions" as const },
      ],
    };
    expect(() =>
      parseMethodParams("configuration/replace", {
        scope: "user",
        document,
        expectedVersion: "cfg_missing",
      }),
    ).toThrow();
  });

  /** 三个配置命令保持单一语义，禁止重新引入旧 mode 分支。 */
  it("keeps reset and replace as separate strict commands", () => {
    const base = { scope: "user" as const, expectedVersion: "cfg_missing" };
    expect(parseMethodParams("configuration/reset", base)).toEqual(base);
    expect(
      parseMethodParams("configuration/replace", { ...base, document: completeConfigDocument }),
    ).toMatchObject({ document: completeConfigDocument });
    expect(() =>
      parseMethodParams("configuration/reset", { ...base, document: completeConfigDocument }),
    ).toThrow();
    expect(() => parseMethodParams("configuration/replace", base)).toThrow();
    expect(() =>
      parseMethodParams("configuration/replace", {
        ...base,
        document: { ...completeConfigDocument, unexpected: true },
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("configuration/replace", {
        ...base,
        document: {
          ...completeConfigDocument,
          providers: [
            {
              ...completeConfigDocument.providers[0],
              agent_defaults: {
                ...completeConfigDocument.providers[0].agent_defaults,
                context: {
                  ...completeConfigDocument.providers[0].agent_defaults.context,
                  windowTokens: 200_000,
                },
              },
            },
          ],
        },
      }),
    ).toThrow();
  });

  it("keeps limits bounded at the IPC edge", () => {
    expect(() => LimitsSchema.parse({ ...limits, maxFrameBytes: 100 })).toThrow();
  });

  /** admission 与后台标题共用一个严格事件闭集，placeholder 不能被误判成未知来源。 */
  it("accepts historical nullable preferences and strictly validates thread metadata changes", () => {
    expect(
      ThreadSchema.parse({
        threadId: "thr_legacy",
        workspaceId: "ws_demo",
        preferences: null,
        title: "Historical thread",
        status: "archived",
        revision: 2,
        createdAt: "2026-08-24T12:00:00Z",
        updatedAt: "2026-08-25T12:00:00Z",
      }),
    ).toMatchObject({ preferences: null });
    const event = {
      jsonrpc: "2.0",
      method: "thread/metadata-changed",
      params: {
        serverInstanceId: "srv_demo",
        eventId: "evt_thread_metadata",
        sequence: 4,
        occurredAt: "2026-08-25T12:00:03Z",
        generation: 1,
        workspaceId: "ws_demo",
        threadId: "thr_demo",
        revision: 2,
        title: "Migration plan",
        titleSource: "auto",
      },
    } as const;
    expect(parseNotification(event)).toEqual(event);
    const provisionalEvent = {
      ...event,
      params: { ...event.params, titleSource: "placeholder" },
    } as const;
    expect(parseNotification(provisionalEvent)).toEqual(provisionalEvent);
    expect(() =>
      parseNotification({
        ...event,
        params: { ...event.params, titleSource: "generated" },
      }),
    ).toThrow();
    expect(() =>
      parseNotification({
        ...event,
        params: { ...event.params, profileId: "profile_demo" },
      }),
    ).toThrow();
  });

  it("rejects removed reverse host-tool and change-ledger frames", () => {
    expect(() =>
      parseRequest({
        jsonrpc: "2.0",
        id: "h:patch-demo",
        method: "host-tool/invoke",
        params: {},
      }),
    ).toThrow();
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "host-tool/cancel",
        params: { generation: 1, operationId: "op_demo" },
      }),
    ).toThrow();
    expect(() =>
      parseRequest({
        jsonrpc: "2.0",
        id: "c:change",
        method: "change/list",
        params: { threadId: "thr_demo" },
      }),
    ).toThrow();
    expect(() =>
      parseNotification({ jsonrpc: "2.0", method: "change/updated", params: {} }),
    ).toThrow();
  });

  /** 镜像原生握手栅栏，保证 renderer 与 Rust 接受同一个最小闭集。 */
  it("requires only the minimal capability closure and two access modes", () => {
    const base = {
      protocolMajor: JA_PROTOCOL_MAJOR,
      protocolMinor: JA_PROTOCOL_MINOR,
      clientVersion: "2.0.0",
      capabilities: {
        methods: [],
        events: [],
        accessModes: ["approval_required", "full_access"],
      },
      limits,
    };
    expect(parseMethodParams("runtime/initialize", base)).toMatchObject({
      capabilities: base.capabilities,
    });

    const malformed = [
      {
        ...base.capabilities,
        hostTools: { version: "v1", methods: ["write_file", "apply_patch"] },
      },
      { ...base.capabilities, accessModes: ["workspace"] },
      { ...base.capabilities, accessModes: ["read_only"] },
      { ...base.capabilities, accessModes: ["allow_session"] },
    ];
    for (const capabilities of malformed) {
      expect(() => parseMethodParams("runtime/initialize", { ...base, capabilities })).toThrow();
    }
  });
});
