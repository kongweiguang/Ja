// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { parseMethodParams, parseMethodResult } from "@/api/protocol/methods";
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
  maxTurnQueuedInputs: 8,
  maxTurnQueuedInputBytes: 512 * 1024,
  maxSnapshotPageItems: 200,
  maxToolBatchConcurrency: 8,
};

const completeConfigDocument = {
  schema_version: 1,
  config_revision: 0,
  default_access_mode: "full_access",
  interaction: { clarification_enabled: true },
  default_provider_id: "provider_demo",
  default_model_id: "model_demo",
  default_reasoning_level: "medium",
  subagents: { enabled: true, provider_id: null, model_id: null, reasoning_level: null },
  providers: [
    {
      provider_id: "provider_demo",
      name: "Demo",
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

describe("JA RPC v1 protocol", () => {
  it("accepts a configuration-free initialize request", () => {
    const params = {
      protocolMajor: JA_PROTOCOL_MAJOR,
      protocolMinor: JA_PROTOCOL_MINOR,
      clientVersion: "0.1.0",
      capabilities: {
        methods: ["runtime/initialize"],
        events: ["runtime/status-changed"],
        accessModes: ["approval_required", "full_access"],
        collaborationModes: ["default", "plan"],
        features: ["task_threads_v1", "plan_goal_v1", "interaction_v1"],
      },
      limits,
    } as const;
    expect(parseMethodParams("runtime/initialize", params)).toEqual(params);
    expect(
      parseRequest({ jsonrpc: "2.0", id: "c:init", method: "runtime/initialize", params }),
    ).toMatchObject({ method: "runtime/initialize" });
    // 首版不设升级窗口；旧 2.1 offer 必须在入站边界直接失败关闭。
    expect(() =>
      parseMethodParams("runtime/initialize", {
        ...params,
        protocolMajor: 2,
        protocolMinor: 1,
      }),
    ).toThrow();
  });

  it("requires the exact negotiated feature set on every runtime status event", () => {
    const ready = {
      jsonrpc: "2.0",
      method: "runtime/status-changed",
      params: {
        serverInstanceId: "srv_demo",
        eventId: "evt_ready",
        sequence: 1,
        occurredAt: "2026-09-04T00:00:00Z",
        status: "ready",
        generation: 1,
        features: ["task_threads_v1", "plan_goal_v1", "interaction_v1"],
        readyToken: "0123456789abcdef0123456789abcdef",
      },
    } as const;

    expect(parseNotification(ready)).toMatchObject(ready);
    expect(() =>
      parseNotification({ ...ready, params: { ...ready.params, features: ["task_threads_v1"] } }),
    ).toThrow();
    expect(() =>
      parseNotification({
        ...ready,
        params: {
          ...ready.params,
          features: ["plan_goal_v1", "task_threads_v1", "interaction_v1"],
        },
      }),
    ).toThrow();
  });

  it("rejects removed snapshot/configure and client-owned identity fields", () => {
    expect(() =>
      parseMethodParams("runtime/initialize", {
        protocolMajor: 1,
        protocolMinor: 0,
        clientVersion: "0.1.0",
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

  it("requires the frozen v1 CAS wire shapes", () => {
    expect(
      parseMethodParams("turn/resume", {
        turnId: "turn_demo",
        expectedThreadRevision: 8,
      }),
    ).toEqual({ turnId: "turn_demo", expectedThreadRevision: 8 });
    expect(() =>
      parseMethodParams("turn/resume", {
        turnId: "turn_demo",
        expectedThreadRevision: 8,
        forceCurrentConfiguration: true,
      }),
    ).toThrow();
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

  /** Provider 名称不参与 Wire 路由；三种 API 平级可选，遗留品牌字段必须被严格拒绝。 */
  it("routes custom providers by API and rejects the legacy provider brand field", () => {
    for (const api of [
      "anthropic_messages",
      "openai_chat_completions",
      "openai_responses",
    ] as const) {
      const document = {
        ...completeConfigDocument,
        providers: [{ ...completeConfigDocument.providers[0], name: "DeepSeek", api }],
      };
      expect(
        parseMethodParams("configuration/replace", {
          scope: "user",
          document,
          expectedVersion: "cfg_missing",
        }),
      ).toMatchObject({ document });
    }

    const legacyDocument = {
      ...completeConfigDocument,
      providers: [{ ...completeConfigDocument.providers[0], provider: "deepseek" }],
    };
    expect(() =>
      parseMethodParams("configuration/replace", {
        scope: "user",
        document: legacyDocument,
        expectedVersion: "cfg_missing",
      }),
    ).toThrow();
  });

  /** Provider 必须各自持有 credential ID，Renderer 不允许把共享 Secret 引用写入 v1。 */
  it("rejects shared credential identities across providers", () => {
    const duplicate = {
      ...structuredClone(completeConfigDocument.providers[0]),
      provider_id: "provider_second",
      models: [
        {
          ...structuredClone(completeConfigDocument.providers[0].models[0]),
          model_id: "model_second",
        },
      ],
    };
    const document = {
      ...completeConfigDocument,
      providers: [...completeConfigDocument.providers, duplicate],
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

  /** Preview scope 与附件 metadata 使用唯一新合同，旧 bound/Turn 归属不得被宽松接受。 */
  it("enforces thread preview authorization and message-level attachment ownership", () => {
    expect(
      parseMethodParams("attachment/preview/open", {
        attachmentId: "att_capture",
        authorization: { kind: "draft", workspaceId: "ws_demo" },
      }),
    ).toMatchObject({ authorization: { kind: "draft", workspaceId: "ws_demo" } });
    expect(
      parseMethodParams("attachment/preview/open", {
        attachmentId: "att_capture",
        authorization: { kind: "thread", threadId: "thr_demo" },
      }),
    ).toMatchObject({ authorization: { kind: "thread", threadId: "thr_demo" } });
    expect(() =>
      parseMethodParams("attachment/preview/open", {
        attachmentId: "att_capture",
        authorization: { kind: "bound", threadId: "thr_demo" },
      }),
    ).toThrow();

    const metadata = {
      attachmentId: "att_capture",
      workspaceId: "ws_demo",
      displayName: "capture.png",
      sizeBytes: 128,
      mediaKind: "image" as const,
      mediaType: "image/png",
      state: "bound" as const,
      createdAt: "2026-09-03T01:00:00Z",
      expiresAt: "2026-09-04T01:00:00Z",
      boundMessageId: "item_user",
    };
    expect(parseMethodResult("attachment/import", metadata)).toEqual(metadata);
    expect(() =>
      parseMethodResult("attachment/import", {
        ...metadata,
        boundMessageId: undefined,
        boundTurnId: "turn_demo",
      }),
    ).toThrow();
  });

  /** USER/Queue 摘要逐项跟随 attachment block，独立 snapshot item 与任意问题码均失败关闭。 */
  it("keeps attachment summaries inside their owning user input", () => {
    const summary = {
      attachmentId: "att_capture",
      displayName: "capture.png",
      sizeBytes: 128,
      mediaKind: "image" as const,
      mediaType: "image/png",
    };
    const turn = {
      turnId: "turn_demo",
      status: "running" as const,
      requestedAt: "2026-09-03T01:00:00Z",
      updatedAt: "2026-09-03T01:00:00Z",
      completedAt: null,
      errorCode: null,
      changeSet: null,
    };
    const item = {
      itemId: "item_user",
      createdAt: "2026-09-03T01:00:00Z",
      turnId: "turn_demo",
      kind: "user_input" as const,
      content: [{ type: "attachment" as const, attachmentId: "att_capture" }],
      attachments: [summary],
    };
    const queuedInput = {
      inputId: "input_capture",
      turnId: "turn_demo",
      content: item.content,
      attachments: [summary],
      kind: "follow_up" as const,
      status: "needs_attention" as const,
      issue: {
        errorCode: "ATTACHMENT_UNAVAILABLE" as const,
        message: "附件暂不可用",
        retryable: false,
      },
      inputRevision: 1,
      createdAt: "2026-09-03T01:00:00Z",
    };
    const snapshot = {
      threadId: "thr_demo",
      revision: 2,
      turns: [turn],
      items: [item],
      inputQueue: { turnId: "turn_demo", revision: 1, accepting: true, items: [queuedInput] },
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);
    expect(
      parseMethodResult("turn/input/enqueue", {
        accepted: true,
        inputId: "input_capture",
        inputQueue: {
          ...snapshot.inputQueue,
          items: [
            {
              ...queuedInput,
              issue: { ...queuedInput.issue, message: "附件暂不可用\t请修复后重试" },
            },
          ],
        },
      }),
    ).toBeDefined();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        items: [{ ...item, attachments: [{ ...summary, attachmentId: "att_other" }] }],
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        items: [{ ...item, kind: "attachment", state: "bound" }],
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("turn/input/enqueue", {
        accepted: true,
        inputId: "input_capture",
        inputQueue: {
          ...snapshot.inputQueue,
          items: [
            {
              ...queuedInput,
              issue: { ...queuedInput.issue, errorCode: "UNKNOWN_ATTACHMENT_FAILURE" },
            },
          ],
        },
      }),
    ).toThrow();

    const consumed = {
      jsonrpc: "2.0" as const,
      method: "turn/input-consumed" as const,
      params: {
        serverInstanceId: "srv_demo",
        eventId: "evt_attachment_consumed",
        sequence: 1,
        occurredAt: "2026-09-03T01:00:01Z",
        generation: 1,
        workspaceId: "ws_demo",
        threadId: "thr_demo",
        turnId: "turn_demo",
        threadRevision: 3,
        input: { ...queuedInput, status: "pending" as const, issue: null },
        userItem: item,
        inputQueue: { turnId: "turn_demo", revision: 2, accepting: true, items: [] },
      },
    };
    expect(parseNotification(consumed)).toEqual(consumed);
    expect(() =>
      parseNotification({
        ...consumed,
        params: {
          ...consumed.params,
          userItem: {
            ...item,
            attachments: [{ ...summary, displayName: "other.png" }],
          },
        },
      }),
    ).toThrow();
  });

  /** Skill 发现按 Workspace identity 取项目来源，同时来源闭集不接受旧 workspace 别名。 */
  it("validates workspace-scoped four-source Skill discovery", () => {
    expect(parseMethodParams("skill/list", { workspaceId: "ws_project", limit: 20 })).toEqual({
      workspaceId: "ws_project",
      limit: 20,
    });
    expect(() => parseMethodParams("skill/list", { cwd: "C:/work/ja" })).toThrow();
    expect(() =>
      parseMethodResult("skill/list", {
        items: [
          {
            skillId: "skill_demo",
            name: "Demo",
            scope: "workspace",
            enabled: false,
            status: "healthy",
            description: "legacy source",
          },
        ],
        nextCursor: null,
      }),
    ).toThrow();
  });

  /** admission 与后台标题共用一个严格事件闭集，placeholder 不能被误判成未知来源。 */
  it("accepts historical nullable preferences and strictly validates thread metadata changes", () => {
    const historicalThread = {
      threadId: "thr_legacy",
      workspaceId: "ws_demo",
      preferences: null,
      title: "Historical thread",
      status: "archived",
      pinned: false,
      latestTurnStatus: null,
      latestTurnSeen: true,
      activeGoalId: null,
      revision: 2,
      createdAt: "2026-08-24T12:00:00Z",
      updatedAt: "2026-08-25T12:00:00Z",
    } as const;
    expect(ThreadSchema.parse(historicalThread)).toMatchObject({ preferences: null });
    expect(() => ThreadSchema.parse({ ...historicalThread, latestTurnSeen: false })).toThrow();
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
      clientVersion: "0.1.0",
      capabilities: {
        methods: [],
        events: [],
        accessModes: ["approval_required", "full_access"],
        collaborationModes: ["default", "plan"],
        features: ["task_threads_v1", "plan_goal_v1", "interaction_v1"],
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
      { ...base.capabilities, features: [] },
      { ...base.capabilities, features: ["task_threads_v2"] },
    ];
    for (const capabilities of malformed) {
      expect(() => parseMethodParams("runtime/initialize", { ...base, capabilities })).toThrow();
    }
  });

  /** 冻结审阅一次返回所选文件，分页字段与旧明文结果必须关闭失败。 */
  it("requires one complete frozen turn diff file", () => {
    const params = {
      threadId: "thr_demo",
      turnId: "turn_demo",
      artifactId: "artifact_demo",
      filePath: "src/main.ts",
    };
    const result = {
      artifactId: "artifact_demo",
      filePath: "src/main.ts",
      byteLength: 1,
      sha256: "2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881",
      contentBase64: "eA==",
    };

    expect(parseMethodParams("turn/change-set/read", params)).toEqual(params);
    expect(parseMethodResult("turn/change-set/read", result)).toEqual(result);
    expect(
      parseMethodResult("turn/change-set/read", {
        ...result,
        byteLength: 6,
        sha256: "670d9743542cae3ea7ebe36af56bd53648b0a1126162e78d81a32934a711302e",
        contentBase64: "5L2g5aW9",
      }),
    ).toMatchObject({ byteLength: 6, contentBase64: "5L2g5aW9" });
    expect(() =>
      parseMethodParams("turn/change-set/read", {
        threadId: params.threadId,
        turnId: params.turnId,
        artifactId: params.artifactId,
        offsetBytes: 0,
        limitBytes: 65_536,
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("turn/change-set/read", {
        artifactId: result.artifactId,
        byteLength: result.byteLength,
        sha256: result.sha256,
        content: "x",
      }),
    ).toThrow();
    for (const invalid of [
      { ...result, contentBase64: "eA=" },
      { ...result, contentBase64: "eB==" },
      { ...result, contentBase64: "eA-_" },
      { ...result, contentBase64: "eA==\n" },
      { ...result, byteLength: 2 },
      { ...result, sha256: result.sha256.toUpperCase() },
    ]) {
      expect(() => parseMethodResult("turn/change-set/read", invalid)).toThrow();
    }
  });

  /** 最大正文连同最长 wire identity 仍须小于协商帧上限，且 decoded 上限不能靠编码长度绕过。 */
  it("bounds complete frozen diff files below one protocol frame", () => {
    const maximumContentBase64 = `${"A".repeat(2_796_203)}=`;
    const maximumResult = {
      artifactId: `artifact_${"a".repeat(96)}`,
      filePath: "a".repeat(4_096),
      byteLength: 2_097_152,
      sha256: "0".repeat(64),
      contentBase64: maximumContentBase64,
    };
    const parsed = parseMethodResult("turn/change-set/read", maximumResult);
    expect(parsed.byteLength).toBe(maximumResult.byteLength);
    expect(parsed.contentBase64).toBe(maximumContentBase64);
    expect(
      new TextEncoder().encode(
        JSON.stringify({ jsonrpc: "2.0", id: "c:max", result: maximumResult }),
      ).byteLength,
    ).toBeLessThan(4 * 1_024 * 1_024);
    expect(() =>
      parseMethodResult("turn/change-set/read", {
        ...maximumResult,
        byteLength: 2_097_153,
      }),
    ).toThrow();
  });
});
