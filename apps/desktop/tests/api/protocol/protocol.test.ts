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
  schema_version: 2,
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
  disabled_skills: [],
} as const;

const completeProjectSkillDocument = {
  schema_version: 2,
  config_revision: 0,
  disabled_skills: [],
} as const;

describe("JA RPC v2 protocol", () => {
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
        features: ["task_threads", "plan", "interaction"],
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
        features: ["task_threads", "plan", "interaction"],
        readyToken: "0123456789abcdef0123456789abcdef",
      },
    } as const;

    expect(parseNotification(ready)).toMatchObject(ready);
    expect(() =>
      parseNotification({ ...ready, params: { ...ready.params, features: ["task_threads"] } }),
    ).toThrow();
    expect(() =>
      parseNotification({
        ...ready,
        params: {
          ...ready.params,
          features: ["plan", "task_threads", "interaction"],
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

  it("requires the frozen v2 CAS wire shapes", () => {
    expect(
      parseMethodParams("turn/cancel", {
        turnId: "turn_demo",
      }),
    ).toEqual({ turnId: "turn_demo" });
    expect(() =>
      parseMethodParams("turn/cancel", {
        turnId: "turn_demo",
        expectedThreadRevision: 8,
      }),
    ).toThrow();
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
    /** User Provider/Model 写入只接受稳定 ID map；实体 null 删除，显式配置 null 使用 sentinel。 */
    const sparseUserProviderPatch = {
      providers: {
        provider_demo: {
          network_timeouts: { request_timeout_ms: 60_000 },
          models: {
            model_demo: {
              model: "gpt-tuned",
              default_reasoning_level: { __ja_null: true },
            },
            model_preview: {
              name: "Preview",
              model: "gpt-preview",
              capabilities: { context_window_tokens: 128_000, max_output_tokens: 8_192 },
              reasoning_level_map: {},
              default_reasoning_level: { __ja_null: true },
            },
            model_removed: null,
          },
          model_order: ["model_demo", "model_preview"],
        },
        provider_removed: null,
      },
      provider_order: ["provider_demo"],
    };
    expect(
      parseMethodParams("configuration/patch", {
        scope: "user",
        patch: sparseUserProviderPatch,
        expectedVersion: "cfg_missing",
      }),
    ).toMatchObject({ patch: sparseUserProviderPatch });
    for (const invalidPatch of [
      { providers: [{ ...completeConfigDocument.providers[0] }] },
      { providers: { provider_demo: { provider_id: "provider_demo" } } },
      {
        providers: {
          provider_demo: { models: [{ ...completeConfigDocument.providers[0]!.models[0] }] },
        },
      },
      {
        providers: {
          provider_demo: { models: { model_demo: { default_reasoning_level: null } } },
        },
      },
      { provider_order: ["provider_demo", "provider_demo"] },
      { providers: { invalid: null } },
    ]) {
      expect(() =>
        parseMethodParams("configuration/patch", {
          scope: "user",
          patch: invalidPatch,
          expectedVersion: "cfg_missing",
        }),
      ).toThrow();
    }
    const projectModelPatch = {
      providers: [
        {
          provider_id: "provider_demo",
          models: [{ model_id: "model_demo", model: "project-model" }],
        },
      ],
    };
    expect(
      parseMethodParams("configuration/patch", {
        scope: "project",
        workspaceId: "ws_demo",
        patch: projectModelPatch,
        expectedVersion: "cfg_A",
      }),
    ).toMatchObject({ patch: projectModelPatch });
    expect(
      parseMethodParams("configuration/replace", {
        scope: "project",
        workspaceId: "ws_demo",
        document: completeProjectSkillDocument,
        expectedVersion: "cfg_A",
      }),
    ).toMatchObject({ workspaceId: "ws_demo", document: completeProjectSkillDocument });
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
    expect(
      parseMethodParams("credential/reveal-provider", { providerId: "provider_demo" }),
    ).toEqual({
      providerId: "provider_demo",
    });
    expect(parseMethodResult("credential/reveal-provider", { secret: "fixture-only" })).toEqual({
      secret: "fixture-only",
    });
    expect(() => parseMethodResult("credential/reveal-provider", { secret: "" })).toThrow();
    expect(() =>
      parseMethodResult("credential/reveal-provider", { secret: "fixture", extra: true }),
    ).toThrow();

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

  /** JA-RPC 配置文档允许用户自建的远程 HTTP Provider，不把传输安全策略限定为 loopback。 */
  it("accepts a remote HTTP Provider route", () => {
    const document = {
      ...completeConfigDocument,
      providers: [
        { ...completeConfigDocument.providers[0], base_url: "http://198.51.100.22:8080/v1" },
      ],
    };

    expect(
      parseMethodParams("configuration/replace", {
        scope: "user",
        document,
        expectedVersion: "cfg_missing",
      }),
    ).toMatchObject({ document });
  });

  /** Provider 必须各自持有 credential ID，Renderer 不允许把共享 Secret 引用写入 Wire。 */
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
      boundThreadId: null,
    };
    expect(parseMethodResult("attachment/import", metadata)).toEqual(metadata);
    const forkBoundMetadata = {
      ...metadata,
      boundMessageId: null,
      boundThreadId: "thr_fork",
    };
    expect(parseMethodResult("attachment/import", forkBoundMetadata)).toEqual(forkBoundMetadata);
    expect(() =>
      parseMethodResult("attachment/import", {
        ...metadata,
        boundMessageId: undefined,
        boundThreadId: null,
        boundTurnId: "turn_demo",
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("attachment/import", {
        ...metadata,
        boundThreadId: "thr_fork",
      }),
    ).toThrow();
  });

  /** Fork 身份固定到真实 Item；旧 Turn 锚点不能通过严格请求或回执 schema。 */
  it("anchors fork requests and receipts to the exact source item", () => {
    const input = {
      sourceThreadId: "thr_source",
      sourceItemId: "item_answer",
      expectedThreadRevision: 7,
      operationKey: "op_0123456789abcdef0123456789abcdef",
    };
    expect(parseMethodParams("thread/fork", input)).toEqual(input);
    expect(() =>
      parseMethodParams("thread/fork", {
        sourceThreadId: input.sourceThreadId,
        sourceTurnId: "turn_answer",
        expectedThreadRevision: input.expectedThreadRevision,
        operationKey: input.operationKey,
      }),
    ).toThrow();
    expect(
      parseMethodParams("operation/read", {
        operationKey: input.operationKey,
        sourceThreadId: input.sourceThreadId,
      }),
    ).toEqual({ operationKey: input.operationKey, sourceThreadId: input.sourceThreadId });

    const result = {
      thread: {
        threadId: "thr_fork",
        workspaceId: "ws_demo",
        workspaceKind: "project",
        preferences: null,
        title: "Source title (fork)",
        status: "active",
        pinned: false,
        latestTurnStatus: null,
        latestTurnSeen: true,
        revision: 1,
        createdAt: "2026-10-10T00:00:00Z",
        updatedAt: "2026-10-10T00:00:00Z",
      },
      source: {
        threadId: input.sourceThreadId,
        itemId: input.sourceItemId,
        threadRevision: input.expectedThreadRevision,
      },
    } as const;
    expect(parseMethodResult("thread/fork", result)).toEqual(result);
    expect(
      parseMethodResult("operation/read", {
        status: "committed",
        method: "thread/fork",
        threadId: input.sourceThreadId,
        result,
      }),
    ).toMatchObject({ result: { source: result.source } });
    expect(() =>
      parseMethodResult("thread/fork", {
        ...result,
        source: { threadId: input.sourceThreadId, turnId: "turn_answer", threadRevision: 7 },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/fork", {
        ...result,
        thread: { ...result.thread, threadId: input.sourceThreadId },
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
      origin: "native" as const,
      sourceMessageId: null,
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
      totalCharacters: 0,
      truncated: false,
    };
    const snapshotItem = { ...item, origin: "native" as const };
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
      items: [snapshotItem],
      inputQueue: { turnId: "turn_demo", revision: 1, accepting: true, items: [queuedInput] },
      contextUsage: null,
      taskActivities: [],
      liveStream: null,
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
        items: [{ ...snapshotItem, attachments: [{ ...summary, attachmentId: "att_other" }] }],
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        items: [{ ...snapshotItem, kind: "attachment", state: "bound" }],
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

  /** Skill 发现只使用三种公开来源，Core 专用扩展来源不能出现在 RPC 投影。 */
  it("validates workspace-scoped public Skill discovery", () => {
    expect(parseMethodParams("skill/list", { workspaceId: "ws_project", limit: 20 })).toEqual({
      workspaceId: "ws_project",
      limit: 20,
    });
    expect(() => parseMethodParams("skill/list", { cwd: "C:/work/ja" })).toThrow();
    expect(() =>
      parseMethodResult("skill/list", {
        items: [
          {
            skillId: "bundled:demo",
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
      workspaceKind: "project",
      preferences: null,
      title: "Historical thread",
      status: "archived",
      pinned: false,
      latestTurnStatus: null,
      latestTurnSeen: true,
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
        features: ["task_threads", "plan", "interaction"],
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

  /** MCP 状态元数据遵循配置名称长度上限和已知工具数量上限。 */
  it("accepts MCP status values through their configured bounds", () => {
    const server = {
      serverId: "mcp_bounded",
      name: `${"n".repeat(511)}\n`,
      scope: "global",
      state: "available",
      toolCount: 10_000,
    };
    const valid = {
      threadId: "thr_demo",
      source: "active",
      notices: [],
      servers: [server],
    };
    expect(parseMethodResult("thread/mcp/read", valid)).toEqual(valid);
    for (const invalid of [
      { ...valid, servers: [{ ...server, name: `${"n".repeat(512)}x` }] },
      { ...valid, servers: [{ ...server, name: "invalid\u0000name" }] },
      { ...valid, servers: [{ ...server, toolCount: 10_001 }] },
    ]) {
      expect(() => parseMethodResult("thread/mcp/read", invalid)).toThrow();
    }
  });
});
