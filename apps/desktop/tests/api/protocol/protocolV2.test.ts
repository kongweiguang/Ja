// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { ConfigSkillReferenceSchema } from "@/api/protocol/configDocument";
import { parseMethodParams, parseMethodResult } from "@/api/protocol/methods";
import { parseNotification } from "@/api/protocol/protocol";

const REQUEST_PROFILE = {
  providerId: "provider_demo",
  modelId: "model_demo",
  api: "openai_responses" as const,
  upstreamModel: "gpt-5.6-sol",
  requestedReasoning: "medium" as const,
  effectiveReasoning: "medium" as const,
  accessMode: "approval_required" as const,
  collaborationMode: "default" as const,
  configGeneration: "cfg_demo",
  promptRevision: "prompt_demo",
  toolCatalogRevision: "tools_demo",
  contextWindowTokens: 128_000,
  maxOutputTokens: 16_000,
};

describe("JA RPC v2 configuration ownership", () => {
  /** 公开 Skill 身份在输入和目录投影共用 256 字符 ASCII kebab 边界。 */
  it("accepts all public skill sources at the maximum name length", () => {
    const name = "a".repeat(256);
    const operationKey = "op_0123456789abcdef0123456789abcdef";

    for (const source of ["user", "ja", "project"] as const) {
      const skillId = `${source}:${name}`;
      const parsed = parseMethodParams("turn/start", {
        threadId: "thr_demo",
        content: [
          { type: "skill_reference", skillId },
          { type: "text", text: "run" },
        ],
        operationKey,
      });
      expect(parsed).toMatchObject({
        content: [
          { type: "skill_reference", skillId },
          { type: "text", text: "run" },
        ],
      });
    }

    expect(
      parseMethodResult("skill/list", {
        items: [
          {
            skillId: `project:${name}`,
            name,
            scope: "project",
            enabled: true,
            status: "healthy",
            description: "long name",
          },
        ],
        nextCursor: null,
      }),
    ).toMatchObject({ items: [{ skillId: `project:${name}`, name }] });
    expect(() =>
      parseMethodResult("skill/list", {
        items: [
          {
            skillId: `project:${"a".repeat(257)}`,
            name: "a".repeat(257),
            scope: "project",
            enabled: true,
            status: "healthy",
            description: "overlong name",
          },
        ],
        nextCursor: null,
      }),
    ).toThrow();
  });

  /** 非 ASCII、大写、尾随行终止符、超长和 Core 专用来源都必须失败。 */
  it("rejects skill references outside the public wire grammar", () => {
    const invalidReferences = [
      `user:${"a".repeat(257)}`,
      "user:天气",
      "user:Upper",
      "user:demo\n",
      "user:demo\r\n",
      "user:demo\u2028",
      "user:demo\u2029",
      "bundled:demo",
    ];

    for (const skillId of invalidReferences) {
      expect(() =>
        parseMethodParams("turn/start", {
          threadId: "thr_demo",
          content: [
            { type: "skill_reference", skillId },
            { type: "text", text: "run" },
          ],
          operationKey: "op_0123456789abcdef0123456789abcdef",
        }),
      ).toThrow();
    }
  });

  /** 配置引用共享三来源语法；来源是否适用于用户或项目由对应容器 Schema 再限制。 */
  it("uses the same bounded ASCII name grammar for configuration references", () => {
    const name = "a".repeat(256);
    for (const source of ["user", "ja", "project"]) {
      expect(ConfigSkillReferenceSchema.safeParse(`${source}:${name}`).success).toBe(true);
    }
    for (const invalid of [
      `project:${"a".repeat(257)}`,
      "user:天气",
      "user:Upper",
      "bundled:demo",
    ]) {
      expect(ConfigSkillReferenceSchema.safeParse(invalid).success).toBe(false);
    }
  });

  it("matches explicit shared daemon shutdown without widening renderer commands", () => {
    expect(parseMethodParams("runtime/shutdown", {})).toEqual({});
    expect(parseMethodParams("runtime/shutdown", { force: true })).toEqual({ force: true });
    expect(() => parseMethodParams("runtime/shutdown", { force: "true" })).toThrow();
  });

  /** Native lifetime 与运行实例、请求 ID 分离；缺失或伪造形状不得退化成无归属连接。 */
  it("requires the current native lifetime contract without accepting old registration", () => {
    const params = {
      environment: { PATH: "C:\\tools", JA_TEST: "isolated" },
      shell: null,
      clientProcessId: 1234,
      clientLifetimeId: "native_0123456789abcdef0123456789abcdef",
    };
    expect(parseMethodParams("runtime/context/register", params)).toEqual(params);
    for (const invalid of [
      { environment: params.environment, shell: null },
      { ...params, clientProcessId: 0 },
      { ...params, clientProcessId: -1 },
      { ...params, clientProcessId: 1.5 },
      { ...params, clientProcessId: Number.MAX_SAFE_INTEGER + 1 },
      { ...params, clientProcessId: "1234" },
      { ...params, clientLifetimeId: "native_0123456789ABCDEF0123456789ABCDEF" },
      { ...params, clientLifetimeId: "native_0123456789abcdef0123456789abcdef\n" },
      { ...params, clientLifetimeId: "srv_0123456789abcdef0123456789abcdef" },
      { ...params, serverInstanceId: "srv_demo" },
    ]) {
      expect(() => parseMethodParams("runtime/context/register", invalid)).toThrow();
    }
  });

  /** 清理范围只能来自连接归属；退出预算有界，ACK 只证明该客户端的清理已经提交。 */
  it("admits only scoped native close and its exact acknowledgement", () => {
    for (const timeoutMs of [1, 20_000]) {
      expect(parseMethodParams("runtime/client/close", { timeoutMs })).toEqual({ timeoutMs });
    }
    for (const invalid of [
      {},
      { timeoutMs: 0 },
      { timeoutMs: 20_001 },
      { timeoutMs: 1.5 },
      { timeoutMs: "1000" },
      { timeoutMs: 1000, clientProcessId: 1234 },
      { timeoutMs: 1000, taskThreadIds: ["thr_other"] },
    ]) {
      expect(() => parseMethodParams("runtime/client/close", invalid)).toThrow();
    }
    expect(parseMethodResult("runtime/client/close", { accepted: true })).toEqual({
      accepted: true,
    });
    expect(() => parseMethodResult("runtime/client/close", { accepted: false })).toThrow();
    expect(() =>
      parseMethodResult("runtime/client/close", { accepted: true, status: "stopped" }),
    ).toThrow();
  });

  /** Close notification 的 root 必须存活，且集合必须严格包含唯一合法删除身份。 */
  it("validates task closed notification routing and exact deletion identities", () => {
    const notification = {
      jsonrpc: "2.0",
      method: "task/closed",
      params: {
        serverInstanceId: "srv_12345678",
        eventId: "evt_12345678",
        sequence: 8,
        occurredAt: "2026-09-03T08:00:00Z",
        generation: 2,
        rootThreadId: "thr_top_root",
        taskThreadId: "thr_closed_root",
        closedThreadIds: ["thr_closed_root", "thr_subagent"],
      },
    } as const;
    expect(parseNotification(notification)).toEqual(notification);
    for (const params of [
      { ...notification.params, closedThreadIds: [] },
      { ...notification.params, closedThreadIds: ["thr_subagent"] },
      { ...notification.params, closedThreadIds: ["thr_closed_root", "thr_closed_root"] },
      { ...notification.params, rootThreadId: "thr_closed_root" },
      { ...notification.params, taskRevision: 4 },
    ]) {
      expect(() => parseNotification({ ...notification, params })).toThrow();
    }
  });

  it("accepts cwd/thread/turn intent without client-owned identity or generation", () => {
    expect(parseMethodParams("workspace/open", { cwd: "C:\\demo" })).toEqual({ cwd: "C:\\demo" });
    expect(
      parseMethodParams("workspace/open", { cwd: "C:\\demo", displayName: "项".repeat(1024) }),
    ).toMatchObject({ displayName: "项".repeat(1024) });
    expect(() =>
      parseMethodParams("workspace/open", { cwd: "C:\\demo", displayName: "项".repeat(1025) }),
    ).toThrow();
    expect(parseMethodParams("workspace/open", { workspaceId: "ws_session" })).toEqual({
      workspaceId: "ws_session",
    });
    expect(
      parseMethodParams("thread/seen", {
        threadId: "thr_seen",
        expectedThreadRevision: 4,
      }),
    ).toEqual({ threadId: "thr_seen", expectedThreadRevision: 4 });
    expect(
      parseMethodParams("thread/create", {
        cwd: "C:\\demo",
        title: "Chat",
        providerId: "provider_demo",
        modelId: "model_demo",
        reasoningLevel: "medium",
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
    ).toEqual({
      cwd: "C:\\demo",
      title: "Chat",
      providerId: "provider_demo",
      modelId: "model_demo",
      reasoningLevel: "medium",
      accessMode: "approval_required",
      collaborationMode: "default",
    });
    expect(() =>
      parseMethodParams("thread/create", {
        cwd: null,
        title: "Chat",
        providerId: "provider_demo",
        modelId: "model_demo",
        reasoningLevel: null,
        accessMode: "approval_required",
        collaborationMode: "default",
      }),
    ).toThrow();
    expect(
      parseMethodParams("turn/start", {
        threadId: "thr_demo",
        content: [{ type: "text", text: "hello" }],
        operationKey: "op_0123456789abcdef0123456789abcdef",
      }),
    ).toEqual({
      threadId: "thr_demo",
      content: [{ type: "text", text: "hello" }],
      operationKey: "op_0123456789abcdef0123456789abcdef",
    });
  });

  it("validates hidden continuation/reask requests and the bounded retry notification", () => {
    const continueParams = {
      threadId: "thr_demo",
      expectedThreadRevision: 4,
      operationKey: "op_0123456789abcdef0123456789abcdef",
    };
    expect(parseMethodParams("turn/continue", continueParams)).toEqual(continueParams);
    const reaskParams = {
      ...continueParams,
      sourceMessageId: "item_question",
      content: [
        { type: "attachment" as const, attachmentId: "att_question" },
        { type: "text" as const, text: "修订后的问题" },
      ],
    };
    expect(parseMethodParams("turn/reask", reaskParams)).toEqual(reaskParams);
    expect(
      parseMethodParams("operation/read", { operationKey: continueParams.operationKey }),
    ).toEqual({ operationKey: continueParams.operationKey });
    expect(
      parseMethodParams("operation/read", {
        operationKey: continueParams.operationKey,
        sourceThreadId: "thr_demo",
      }),
    ).toEqual({ operationKey: continueParams.operationKey, sourceThreadId: "thr_demo" });
    expect(parseMethodResult("operation/read", { status: "unknown" })).toEqual({
      status: "unknown",
    });
    expect(
      parseMethodResult("operation/read", {
        status: "committed",
        method: "turn/continue",
        threadId: "thr_demo",
        result: { accepted: true, queued: true, turnId: "turn_continue", threadRevision: 5 },
      }),
    ).toMatchObject({ status: "committed", threadId: "thr_demo" });
    expect(() => parseMethodParams("turn/continue", { ...continueParams, content: [] })).toThrow();
    expect(() =>
      parseMethodParams("operation/read", {
        operationKey: continueParams.operationKey,
        sourceThreadId: "thr_demo",
        sourceTurnId: "turn_demo",
      }),
    ).toThrow();
    expect(
      parseMethodResult("turn/continue", {
        accepted: true,
        queued: true,
        turnId: "turn_continue",
        threadRevision: 5,
      }),
    ).toMatchObject({ turnId: "turn_continue", threadRevision: 5 });

    const retryBase = {
      serverInstanceId: "srv_retry",
      eventId: "evt_retry_two",
      sequence: 10,
      occurredAt: "2026-09-23T00:00:00Z",
      generation: 1,
      workspaceId: "ws_retry",
      threadId: "thr_demo",
      turnId: "turn_continue",
      threadRevision: 8,
    };
    expect(
      parseNotification({
        jsonrpc: "2.0",
        method: "turn/retry-started",
        params: { ...retryBase, attempt: 2 },
      }),
    ).toMatchObject({ method: "turn/retry-started", params: { attempt: 2 } });
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "turn/retry-started",
        params: { ...retryBase, attempt: 1 },
      }),
    ).toThrow();
  });

  /** 分叉只能按持久化 Item 定位；回执和 owner-scoped operation/read 必须保留该身份。 */
  it("validates exact thread fork source item and rejects the former turn anchor", () => {
    const request = {
      sourceThreadId: "thr_source",
      sourceItemId: "item_answer",
      expectedThreadRevision: 7,
      operationKey: "op_0123456789abcdef0123456789abcdef",
    };
    expect(parseMethodParams("thread/fork", request)).toEqual(request);
    expect(() =>
      parseMethodParams("thread/fork", {
        sourceThreadId: "thr_source",
        sourceTurnId: "turn_answer",
        expectedThreadRevision: 7,
        operationKey: request.operationKey,
      }),
    ).toThrow();

    const result = {
      thread: {
        threadId: "thr_forked",
        workspaceId: "ws_demo",
        workspaceKind: "project" as const,
        preferences: null,
        title: "Demo（分叉）",
        status: "active" as const,
        pinned: false,
        latestTurnStatus: null,
        latestTurnSeen: true,
        revision: 0,
        createdAt: "2026-09-23T00:00:00Z",
        updatedAt: "2026-09-23T00:00:00Z",
      },
      source: {
        threadId: request.sourceThreadId,
        itemId: request.sourceItemId,
        threadRevision: request.expectedThreadRevision,
      },
    };
    expect(parseMethodResult("thread/fork", result)).toEqual(result);
    expect(() =>
      parseMethodResult("thread/fork", {
        ...result,
        source: { ...result.source, sourceTurnId: "turn_answer" },
      }),
    ).toThrow();
    expect(
      parseMethodResult("operation/read", {
        status: "committed",
        method: "thread/fork",
        threadId: request.sourceThreadId,
        result,
      }),
    ).toMatchObject({ method: "thread/fork", threadId: request.sourceThreadId });
  });

  /** 估算通知必须绑定完整请求窗口与 UNKNOWN Assistant 身份，不能冒充或覆盖 Provider 实测。 */
  it("validates dispatch context estimate notifications", () => {
    const params = {
      serverInstanceId: "srv_estimate",
      eventId: "evt_estimate",
      sequence: 11,
      occurredAt: "2026-09-26T00:00:00Z",
      generation: 1,
      workspaceId: "ws_estimate",
      threadId: "thr_demo",
      turnId: "turn_continue",
      threadRevision: 9,
      usage: {
        modelRequestId: "modelreq_estimate",
        modelRequestOrdinal: 2,
        modelRound: 2,
        purpose: "assistant",
        certainty: "unknown",
        profile: REQUEST_PROFILE,
        inputTokens: null,
        outputTokens: null,
        totalTokens: null,
        measuredAt: "2026-09-26T00:00:00Z",
        contextEstimate: {
          certainty: "estimated",
          source: "local",
          usedTokens: 18_500,
          limitTokens: REQUEST_PROFILE.contextWindowTokens,
          measuredAt: "2026-09-26T00:00:00Z",
        },
      },
    };
    expect(
      parseNotification({ jsonrpc: "2.0", method: "assistant/context-estimated", params }),
    ).toMatchObject({
      method: "assistant/context-estimated",
      params: { usage: { certainty: "unknown" } },
    });
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "assistant/context-estimated",
        params: { ...params, usage: { ...params.usage, certainty: "known" } },
      }),
    ).toThrow();
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "assistant/context-estimated",
        params: {
          ...params,
          usage: {
            ...params.usage,
            contextEstimate: { ...params.usage.contextEstimate, limitTokens: 1 },
          },
        },
      }),
    ).toThrow();
  });

  /** 运行态完整保留 Provider callId，并按恢复裁决的安全 ASCII operationKey 契约拒绝非法键。 */
  it("accepts opaque provider tool ids and the explicit waiting-input transition", () => {
    const common = {
      serverInstanceId: "srv_demo",
      eventId: "evt_tool_started",
      sequence: 1,
      occurredAt: "2026-09-26T00:00:00Z",
      generation: 1,
      workspaceId: "ws_demo",
      threadId: "thr_demo",
      turnId: "turn_demo",
      threadRevision: 1,
    };
    const callId = "toolu_01HABC123";
    expect(
      parseNotification({
        jsonrpc: "2.0",
        method: "tool/started",
        params: { ...common, callId, ordinal: 0 },
      }),
    ).toMatchObject({ method: "tool/started", params: { callId } });
    expect(
      parseMethodParams("turn/recovery/respond", {
        turnId: "turn_demo",
        callId,
        expectedThreadRevision: 2,
        expectedRecoveryRevision: 1,
        decision: "retry",
        operationKey: "recovery_demo_1",
      }),
    ).toMatchObject({ callId });
    expect(() =>
      parseMethodParams("turn/recovery/respond", {
        turnId: "turn_demo",
        callId,
        expectedThreadRevision: 2,
        expectedRecoveryRevision: 1,
        decision: "retry",
        operationKey: "recovery key",
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("turn/recovery/respond", {
        turnId: "turn_demo",
        callId: "toolu/unsafe",
        expectedThreadRevision: 2,
        expectedRecoveryRevision: 1,
        decision: "retry",
        operationKey: "recovery_demo_2",
      }),
    ).toThrow();
    expect(
      parseNotification({
        jsonrpc: "2.0",
        method: "turn/state-changed",
        params: { ...common, from: "running", to: "waiting_input" },
      }),
    ).toMatchObject({ method: "turn/state-changed", params: { to: "waiting_input" } });
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "turn/state-changed",
        params: { ...common, from: "waiting_approval", to: "waiting_input" },
      }),
    ).toThrow();
  });

  /** Recovery 与系统关闭审批使用各自的持久事件；系统关卡不能带用户 approve/deny 决定。 */
  it("validates durable tool recovery and approval close events", () => {
    const common = {
      serverInstanceId: "srv_demo",
      eventId: "evt_recovery_1",
      sequence: 2,
      occurredAt: "2026-09-26T00:00:00Z",
      generation: 1,
      workspaceId: "ws_demo",
      threadId: "thr_demo",
      turnId: "turn_demo",
      threadRevision: 2,
    };
    const recovery = {
      ...common,
      recoveryId: "recovery_demo",
      callId: "toolu_01HABC123:part.2",
      revision: 1,
      status: "pending",
      requiresUserAction: true,
    };
    expect(
      parseNotification({ jsonrpc: "2.0", method: "tool/recovery-changed", params: recovery }),
    ).toMatchObject({ method: "tool/recovery-changed", params: recovery });
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "tool/recovery-changed",
        params: { ...recovery, requiresUserAction: false },
      }),
    ).toThrow();

    const closed = { ...common, approvalId: "appr_demo", reason: "cancelled" };
    expect(
      parseNotification({ jsonrpc: "2.0", method: "approval/closed", params: closed }),
    ).toMatchObject({ method: "approval/closed", params: closed });
    const expired = { ...closed, reason: "expired" };
    expect(
      parseNotification({ jsonrpc: "2.0", method: "approval/closed", params: expired }),
    ).toMatchObject({ method: "approval/closed", params: expired });
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "approval/closed",
        params: { ...closed, reason: "deny", decision: "deny" },
      }),
    ).toThrow();
  });

  /** 项目/会话是唯一 Workspace 类型，旧共享根及关联字段必须被严格拒绝。 */
  it("validates only project and session workspaces", () => {
    const projection = {
      workspaceId: "ws_runtime_a",
      root: "C:\\data\\ja\\project",
      displayName: "Project",
      kind: "project" as const,
      trust: "trusted" as const,
      revision: 4,
    };
    expect(parseMethodResult("workspace/open", projection)).toEqual(projection);
    expect(() => parseMethodResult("workspace/open", { ...projection, extra: true })).toThrow();
    expect(() =>
      parseMethodResult("workspace/open", { ...projection, workspaceId: "general" }),
    ).toThrow();
    expect(() =>
      parseMethodResult("workspace/open", { ...projection, kind: "legacy_shared" }),
    ).toThrow();
    expect(() =>
      parseMethodResult("workspace/open", { ...projection, legacySharedWorkspaceId: null }),
    ).toThrow();
  });

  it("accepts the Java-owned Provider/Model preferences in Thread results", () => {
    const projection = {
      threadId: "thr_runtime_a",
      workspaceId: "ws_runtime_a",
      workspaceKind: "session" as const,
      preferences: {
        providerId: "provider_demo",
        modelId: "model_demo",
        reasoningLevel: "medium",
        accessMode: "approval_required",
        collaborationMode: "default",
        titleSource: "placeholder",
      },
      title: "Chat",
      status: "active" as const,
      pinned: false,
      latestTurnStatus: null,
      latestTurnSeen: true,
      revision: 0,
      createdAt: "2026-08-26T00:00:00Z",
      updatedAt: "2026-08-26T00:00:00Z",
    };
    expect(parseMethodResult("thread/create", projection)).toEqual(projection);
    expect(() =>
      parseMethodResult("thread/create", { ...projection, preferences: undefined }),
    ).toThrow();
  });

  /**
   * @author kongweiguang
   * Thread snapshot 必须接受协议完整 Turn 状态闭集，不能因存储态待答而拒绝仍在等待输入的会话。
   */
  it.each([
    "queued",
    "running",
    "waiting_input",
    "waiting_approval",
    "suspended",
    "completed",
    "failed",
    "cancelled",
  ] as const)("接受 Thread snapshot 的合法 Turn 状态 %s", (status) => {
    const snapshot = {
      threadId: "thr_snapshot_status",
      revision: 1,
      turns: [
        {
          turnId: "turn_snapshot_status",
          origin: "native" as const,
          sourceMessageId: null,
          status,
          requestedAt: "2026-08-31T00:00:00Z",
          updatedAt: "2026-08-31T00:00:01Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [],
      taskActivities: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);
  });

  /** thread/read 的 nullable Usage 是必需字段，且必须绑定完整请求画像并保持总量一致。 */
  it("validates required context Usage in Thread snapshots", () => {
    const turn = {
      turnId: "turn_runtime_a",
      origin: "native" as const,
      sourceMessageId: null,
      status: "completed" as const,
      requestedAt: "2026-08-31T00:00:00Z",
      updatedAt: "2026-08-31T00:00:01Z",
      completedAt: "2026-08-31T00:00:01Z",
      errorCode: null,
      changeSet: null,
    };
    const snapshot = {
      threadId: "thr_runtime_a",
      revision: 2,
      turns: [turn],
      items: [],
      inputQueue: null,
      taskActivities: [],
      contextUsage: {
        turnId: "turn_runtime_a",
        modelRequestId: "modelreq_demo",
        modelRequestOrdinal: 1,
        modelRound: 1,
        purpose: "assistant" as const,
        certainty: "known",
        profile: REQUEST_PROFILE,
        inputTokens: 42_000,
        outputTokens: 2_000,
        totalTokens: 44_000,
        measuredAt: "2026-08-31T00:00:01Z",
        contextEstimate: null,
      },
      liveStream: null,
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);
    const { sourceMessageId: _sourceMessageId, ...turnWithoutSource } = turn;
    void _sourceMessageId;
    expect(() =>
      parseMethodResult("thread/read", { ...snapshot, turns: [turnWithoutSource] }),
    ).toThrow();
    expect(() => parseMethodResult("thread/read", { ...snapshot, goalActivities: [] })).toThrow();
    const unknownUsage = {
      ...snapshot.contextUsage,
      certainty: "unknown",
      inputTokens: null,
      outputTokens: null,
      totalTokens: null,
    };
    expect(
      parseMethodResult("thread/read", { ...snapshot, contextUsage: unknownUsage }),
    ).toMatchObject({
      contextUsage: unknownUsage,
    });
    const checkpointEstimate = {
      certainty: "estimated" as const,
      source: "local" as const,
      usedTokens: 12_000,
      limitTokens: REQUEST_PROFILE.contextWindowTokens,
      measuredAt: "2026-08-31T00:00:02Z",
    };
    expect(
      parseMethodResult("thread/read", {
        ...snapshot,
        contextUsage: { ...unknownUsage, contextEstimate: checkpointEstimate },
      }),
    ).toMatchObject({ contextUsage: { contextEstimate: checkpointEstimate } });
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        contextUsage: {
          ...unknownUsage,
          contextEstimate: { ...checkpointEstimate, limitTokens: 2 },
        },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        contextUsage: { ...unknownUsage, inputTokens: 0 },
      }),
    ).toThrow();
    expect(parseMethodResult("thread/read", { ...snapshot, contextUsage: null })).toMatchObject({
      contextUsage: null,
    });
    const missingUsage: Record<string, unknown> = { ...snapshot };
    Reflect.deleteProperty(missingUsage, "contextUsage");
    expect(() => parseMethodResult("thread/read", missingUsage)).toThrow();
    const missingEstimateUsage: Record<string, unknown> = { ...snapshot.contextUsage };
    Reflect.deleteProperty(missingEstimateUsage, "contextEstimate");
    expect(() =>
      parseMethodResult("thread/read", { ...snapshot, contextUsage: missingEstimateUsage }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        contextUsage: { ...snapshot.contextUsage, profile: null },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        contextUsage: { ...snapshot.contextUsage, totalTokens: 43_999 },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        turns: [{ ...turn, runtime: null }],
      }),
    ).toThrow();
  });

  /** 活动流恢复必须绑定活动 Turn，非空段连续覆盖 baseline，并按 UTF-8 字节而非字符限制正文。 */
  it("validates live stream baselines and byte budgets", () => {
    const snapshot = {
      threadId: "thr_live_stream",
      revision: 8,
      turns: [
        {
          turnId: "turn_live_stream",
          origin: "native" as const,
          sourceMessageId: null,
          status: "running" as const,
          requestedAt: "2026-08-31T00:00:00Z",
          updatedAt: "2026-08-31T00:00:01Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [],
      taskActivities: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: {
        turnId: "turn_live_stream",
        streamSeq: 2,
        segments: [
          {
            kind: "assistant" as const,
            segmentStartSeq: 1,
            streamSeq: 1,
            text: "draft",
            occurredAt: "2026-08-31T00:00:00Z",
          },
          {
            kind: "reasoningSummary" as const,
            segmentStartSeq: 2,
            streamSeq: 2,
            text: "thinking",
            occurredAt: "2026-08-31T00:00:01Z",
          },
        ],
      },
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);
    expect(
      parseMethodResult("thread/read", {
        ...snapshot,
        liveStream: { ...snapshot.liveStream, segments: [] },
      }),
    ).toMatchObject({ liveStream: { streamSeq: 2, segments: [] } });
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        liveStream: {
          ...snapshot.liveStream,
          segments: [{ ...snapshot.liveStream.segments[0], streamSeq: 1 }],
        },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        liveStream: {
          ...snapshot.liveStream,
          segments: [
            { ...snapshot.liveStream.segments[0], text: "" },
            snapshot.liveStream.segments[1],
          ],
        },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        liveStream: {
          ...snapshot.liveStream,
          segments: [
            { ...snapshot.liveStream.segments[0], text: "界".repeat(21_846) },
            snapshot.liveStream.segments[1],
          ],
        },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        liveStream: {
          ...snapshot.liveStream,
          segments: [
            { ...snapshot.liveStream.segments[0], occurredAt: "2026-02-30T00:00:00Z" },
            snapshot.liveStream.segments[1],
          ],
        },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        liveStream: { ...snapshot.liveStream, turnId: "turn_completed" },
      }),
    ).toThrow();

    const missingLiveStream = { ...snapshot } as Record<string, unknown>;
    delete missingLiveStream["liveStream"];
    expect(() => parseMethodResult("thread/read", missingLiveStream)).toThrow();
  });

  /** 问答展示只接纳可见文案闭集，跳过状态与答案数组必须保持一致。 */
  it("validates structured interaction answers in Tool presentations", () => {
    const turn = {
      turnId: "turn_interaction_answer",
      origin: "native" as const,
      sourceMessageId: null,
      status: "completed" as const,
      requestedAt: "2026-09-21T00:00:00Z",
      updatedAt: "2026-09-21T00:00:01Z",
      completedAt: "2026-09-21T00:00:01Z",
      errorCode: null,
      changeSet: null,
    };
    const presentation = {
      kind: "read" as const,
      title: "询问用户",
      status: "success" as const,
      summary: "已回答 2 个问题",
      interactionAnswers: [
        { question: "如何同步？", answers: ["保留本地改动", "合并远程提交"], skipped: false },
        { question: "是否立即推送？", answers: [], skipped: true },
      ],
      relativePaths: [],
      truncated: false,
    };
    const snapshot = {
      threadId: "thr_interaction_answer",
      revision: 2,
      turns: [turn],
      items: [
        {
          itemId: "item_interaction_answer",
          createdAt: "2026-09-21T00:00:01Z",
          turnId: turn.turnId,
          origin: "native" as const,
          kind: "tool_call" as const,
          callId: "call_interaction_answer",
          toolName: "request_user_input",
          ordinal: 0,
          presentation,
        },
      ],
      inputQueue: null,
      taskActivities: [],
      contextUsage: null,
      liveStream: null,
      nextCursor: null,
    };

    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        items: [
          {
            ...snapshot.items[0],
            presentation: {
              ...presentation,
              interactionAnswers: [
                { question: "是否立即推送？", answers: ["立即推送"], skipped: true },
              ],
            },
          },
        ],
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        items: [
          {
            ...snapshot.items[0],
            presentation: {
              ...presentation,
              interactionAnswers: [
                {
                  question: "如何同步？",
                  answers: ["保留本地改动"],
                  skipped: false,
                  questionId: "question_sync",
                },
              ],
            },
          },
        ],
      }),
    ).toThrow();
  });

  it("freezes manual compaction results and nullable Thread-level lifecycle events", () => {
    expect(
      parseMethodParams("thread/compact", {
        threadId: "thr_runtime_a",
        expectedThreadRevision: 4,
      }),
    ).toEqual({ threadId: "thr_runtime_a", expectedThreadRevision: 4 });
    const compacted = {
      outcome: "compacted" as const,
      compactionId: "cmp_runtime_a",
      checkpointId: "checkpoint_runtime_a",
      threadRevision: 5,
      inputTokensBefore: 12_000,
      inputTokensAfter: 5_000,
    };
    expect(parseMethodResult("thread/compact", compacted)).toEqual(compacted);
    expect(() =>
      parseMethodResult("thread/compact", { ...compacted, compactionId: null }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/compact", {
        ...compacted,
        outcome: "unchanged",
        compactionId: null,
        checkpointId: null,
      }),
    ).toThrow();

    const base = {
      serverInstanceId: "srv_runtime_a",
      eventId: "evt_compaction_a",
      sequence: 9,
      generation: 1,
      workspaceId: "ws_runtime_a",
      threadId: "thr_runtime_a",
      turnId: null,
      threadRevision: 4,
      occurredAt: "2026-08-29T00:00:00Z",
      compactionId: "cmp_runtime_a",
      trigger: "manual" as const,
      sourceRevision: 4,
      inputTokensBefore: 12_000,
      inputTokensAfter: null,
      strategyVersion: "ja-context-v1" as const,
    };
    expect(
      parseNotification({ jsonrpc: "2.0", method: "context/compaction-started", params: base }),
    ).toMatchObject({ method: "context/compaction-started", params: { turnId: null } });
    expect(() =>
      parseNotification({
        jsonrpc: "2.0",
        method: "context/compaction-started",
        params: { ...base, inputTokensAfter: 5_000 },
      }),
    ).toThrow();
  });

  /** 完成态必须有 ChangeSet；失败和取消只在没有持久事实时以 null 表示。 */
  it("accepts failed terminals without an assistant reply and rejects contradictory fields", () => {
    const base = {
      serverInstanceId: "srv_runtime_a",
      eventId: "evt_terminal_a",
      sequence: 10,
      occurredAt: "2026-09-03T00:00:00Z",
      generation: 1,
      workspaceId: "ws_runtime_a",
      threadId: "thr_runtime_a",
      turnId: "turn_runtime_a",
      threadRevision: 5,
      summary: "",
      summaryTotalCharacters: 0,
      summaryTruncated: false,
      changeSet: {
        state: "complete",
        incompleteReasons: [],
        files: [],
        stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
      },
    };
    const finalMessageText = "本轮未能完成，请重试。";
    const finalMessage = {
      messageId: "item_terminal_a",
      text: finalMessageText,
      totalCharacters: Array.from(finalMessageText).length,
      truncated: false,
    };
    const notification = (params: Record<string, unknown>) =>
      parseNotification({ jsonrpc: "2.0", method: "turn/terminal", params });

    expect(notification({ ...base, state: "completed", finalMessage })).toMatchObject({
      params: { state: "completed", finalMessage },
    });
    expect(
      notification({
        ...base,
        state: "failed",
        finalMessage,
        errorCode: "INTERNAL_ERROR",
        errorMessage: "turn failed",
      }),
    ).toMatchObject({ params: { state: "failed", finalMessage, changeSet: base.changeSet } });
    expect(
      notification({
        ...base,
        state: "failed",
        errorCode: "INTERNAL_ERROR",
        errorMessage: "上游请求失败",
      }),
    ).toMatchObject({ params: { state: "failed", errorMessage: "上游请求失败" } });
    expect(notification({ ...base, state: "cancelled" })).toMatchObject({
      params: { state: "cancelled", changeSet: base.changeSet },
    });
    expect(
      notification({
        ...base,
        state: "failed",
        errorCode: "INTERNAL_ERROR",
        errorMessage: "turn failed",
        changeSet: null,
      }),
    ).toMatchObject({ params: { state: "failed", changeSet: null } });
    expect(notification({ ...base, state: "cancelled", changeSet: null })).toMatchObject({
      params: { state: "cancelled", changeSet: null },
    });

    expect(() => notification({ ...base, state: "completed" })).toThrow();
    expect(() =>
      notification({ ...base, state: "completed", finalMessage, changeSet: null }),
    ).toThrow();
    expect(() => notification({ ...base, state: "failed", errorCode: "INTERNAL_ERROR" })).toThrow();
    expect(() => notification({ ...base, state: "cancelled", finalMessage })).toThrow();
    expect(() =>
      notification({ ...base, state: "cancelled", errorCode: "CANCELLED", errorMessage: "x" }),
    ).toThrow();
    for (const state of ["completed", "failed", "cancelled"] as const) {
      const withoutChangeSet: Record<string, unknown> = { ...base, state };
      delete withoutChangeSet["changeSet"];
      expect(() =>
        notification({
          ...withoutChangeSet,
          ...(state === "completed" ? { finalMessage } : {}),
          ...(state === "failed" ? { errorCode: "INTERNAL_ERROR", errorMessage: "failed" } : {}),
        }),
      ).toThrow();
    }
  });

  /** 所有分页成功结果只接受 items/nextCursor，并拒绝领域专用旧列表键与身份回显。 */
  it("uses one list result envelope across all domains", () => {
    const workspace = {
      workspaceId: "ws_demo",
      root: "C:\\demo",
      displayName: "Demo",
      kind: "project" as const,
      trust: "trusted" as const,
      revision: 1,
    };
    const thread = {
      threadId: "thr_demo",
      workspaceId: "ws_demo",
      workspaceKind: "project" as const,
      preferences: {
        providerId: "provider_demo",
        modelId: "model_demo",
        reasoningLevel: "medium",
        accessMode: "approval_required",
        collaborationMode: "default",
        titleSource: "placeholder",
      },
      title: "Demo",
      status: "active" as const,
      pinned: false,
      latestTurnStatus: null,
      latestTurnSeen: true,
      revision: 1,
      createdAt: "2026-08-26T00:00:00Z",
      updatedAt: "2026-08-26T00:00:00Z",
    };
    const skill = {
      skillId: "user:demo",
      name: "Demo",
      scope: "user" as const,
      enabled: true,
      status: "healthy" as const,
      description: "demo",
    };
    const server = {
      mcpId: "mcp_demo",
      name: "Demo",
      scope: "global" as const,
      transport: "stdio" as const,
      status: "configured" as const,
      toolCount: 1,
    };
    const tool = { name: "read_file", description: "read", inputSchema: { type: "object" } };
    expect(
      parseMethodResult("workspace/list", { items: [workspace], nextCursor: null }),
    ).toMatchObject({ items: [workspace] });
    expect(() =>
      parseMethodResult("workspace/list", {
        items: [{ ...workspace, legacySharedWorkspaceId: "ws_legacy_shared" }],
        nextCursor: null,
      }),
    ).toThrow();
    expect(parseMethodParams("workspace/list", { kind: "project", limit: 200 })).toEqual({
      kind: "project",
      limit: 200,
    });
    expect(() => parseMethodParams("workspace/list", { kind: "unknown" })).toThrow();
    expect(parseMethodResult("thread/list", { items: [thread], nextCursor: null })).toMatchObject({
      items: [thread],
    });
    expect(() =>
      parseMethodResult("thread/list", {
        items: [{ ...thread, legacySharedWorkspaceId: "ws_legacy_shared" }],
        nextCursor: null,
      }),
    ).toThrow();
    expect(parseMethodResult("skill/list", { items: [skill], nextCursor: null })).toMatchObject({
      items: [skill],
    });
    expect(parseMethodResult("mcp/list", { items: [server], nextCursor: null })).toMatchObject({
      items: [server],
    });
    expect(
      parseMethodResult("mcp/test", {
        mcpId: "mcp_demo",
        name: "Demo",
        scope: "global",
        transport: "stdio",
        status: "available",
        toolCount: 1,
      }),
    ).toMatchObject({ status: "available" });
    expect(parseMethodResult("mcp/list-tools", { items: [tool], nextCursor: null })).toMatchObject({
      items: [tool],
    });
    expect(() =>
      parseMethodResult("workspace/list", { workspaces: [workspace], nextCursor: null }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/list", { threads: [thread], nextCursor: null }),
    ).toThrow();
    expect(() => parseMethodResult("skill/list", { skills: [skill], nextCursor: null })).toThrow();
    expect(() => parseMethodResult("mcp/list", { servers: [server], nextCursor: null })).toThrow();
    expect(() =>
      parseMethodResult("mcp/list-tools", { mcpId: "mcp_demo", tools: [tool], nextCursor: null }),
    ).toThrow();
  });

  it("paginates session history and search by server workspace kind", () => {
    const sessionParams = { workspaceKind: "session" as const, limit: 20 };
    const sessionSearch = { workspaceKind: "session" as const, query: "侧聊", limit: 20 };
    const result = {
      items: [
        {
          threadId: "thr_session",
          workspaceId: "ws_session",
          workspaceKind: "session" as const,
          preferences: null,
          title: "临时侧聊",
          status: "active" as const,
          pinned: false,
          latestTurnStatus: null,
          latestTurnSeen: true,
          revision: 1,
          createdAt: "2026-08-26T00:00:00Z",
          updatedAt: "2026-08-26T00:00:00Z",
        },
      ],
      nextCursor: null,
    };

    expect(parseMethodParams("thread/list", sessionParams)).toEqual(sessionParams);
    expect(parseMethodParams("thread/search", sessionSearch)).toEqual(sessionSearch);
    expect(parseMethodResult("thread/list", result)).toEqual(result);
    expect(parseMethodResult("thread/search", result)).toEqual(result);
    expect(() => parseMethodParams("thread/list", { scope: "all", limit: 20 })).toThrow();
    expect(() =>
      parseMethodParams("thread/search", {
        workspaceKind: "session",
        workspaceId: "ws_project",
        query: "x",
      }),
    ).toThrow();
  });

  it("rejects removed snapshot, workspace hash, and turn override fields", () => {
    expect(() =>
      parseMethodParams("runtime/initialize", {
        protocolMajor: 1,
        protocolMinor: 0,
        clientVersion: "0.1.0",
        configSnapshot: {},
        capabilities: {
          methods: [],
          events: [],
          hostTools: { version: "v1", methods: ["write_file", "apply_patch"] },
          accessModes: [],
        },
        limits: {},
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("thread/create", {
        workspaceId: "ws_hash",
        title: "Chat",
        profileId: "profile_demo",
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("turn/start", {
        threadId: "thr_demo",
        content: [{ type: "text", text: "hello" }],
        accessMode: "approval_required",
        cwd: "C:\\demo",
        profileId: "profile_demo",
        configRevision: "cfg_old",
      }),
    ).toThrow();
  });
});

describe("StorageRecovery v2 contract", () => {
  /** UI 只接受当前恢复生命周期，旧迁移阶段与旧原因必须在协议边界拒绝。 */
  it("accepts fresh recovery states and rejects removed migration vocabulary", () => {
    const result = {
      status: "blocked",
      components: [
        { name: "sidecar", status: "healthy" },
        { name: "sqlite", status: "unavailable" },
        { name: "kernel", status: "unavailable" },
        { name: "configuration", status: "healthy" },
      ],
      storageRecovery: {
        id: "11111111-1111-4111-8111-111111111111",
        phase: "rebuilding",
        reason: "INITIALIZATION_FAILED",
        historyAvailable: false,
        backupAvailable: false,
        rebuilt: false,
      },
    } as const;

    expect(parseMethodResult("runtime/health", result)).toEqual(result);
    const ready = {
      ...result,
      status: "ready",
      components: result.components.map((component) => ({ ...component, status: "healthy" })),
      storageRecovery: { ...result.storageRecovery, phase: "ready", reason: null },
    } as const;
    expect(parseMethodResult("runtime/health", ready)).toEqual(ready);
    expect(() =>
      parseMethodResult("runtime/health", {
        ...result,
        storageRecovery: { ...result.storageRecovery, phase: "upgrading" },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("runtime/health", {
        ...result,
        storageRecovery: { ...result.storageRecovery, reason: null },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("runtime/health", {
        ...result,
        storageRecovery: { ...result.storageRecovery, reason: "SCHEMA_UPGRADE" },
      }),
    ).toThrow();
  });
});
