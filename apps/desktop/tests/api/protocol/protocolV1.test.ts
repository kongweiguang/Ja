// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
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

describe("JA RPC v1 configuration ownership", () => {
  it("accepts cwd/thread/turn intent without client-owned identity or generation", () => {
    expect(parseMethodParams("workspace/open", { cwd: "C:\\demo" })).toEqual({ cwd: "C:\\demo" });
    expect(parseMethodParams("workspace/open-general", {})).toEqual({});
    expect(() => parseMethodParams("workspace/open-general", { cwd: "C:\\demo" })).toThrow();
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
    expect(
      parseMethodParams("turn/start", {
        threadId: "thr_demo",
        content: [{ type: "text", text: "hello" }],
      }),
    ).toEqual({ threadId: "thr_demo", content: [{ type: "text", text: "hello" }] });
  });

  it("validates the server-owned general workspace as a standard workspace projection", () => {
    const projection = {
      workspaceId: "ws_runtime_a",
      root: "C:\\data\\ja\\general-workspace",
      displayName: "无项目",
      trust: "trusted" as const,
      revision: 4,
    };
    expect(parseMethodResult("workspace/open-general", projection)).toEqual(projection);
    expect(() =>
      parseMethodResult("workspace/open-general", { ...projection, extra: true }),
    ).toThrow();
    expect(() =>
      parseMethodResult("workspace/open-general", { ...projection, workspaceId: "general" }),
    ).toThrow();
  });

  it("accepts the Java-owned Provider/Model preferences in Thread results", () => {
    const projection = {
      threadId: "thr_runtime_a",
      workspaceId: "ws_runtime_a",
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
      activeGoalId: null,
      revision: 0,
      createdAt: "2026-08-26T00:00:00Z",
      updatedAt: "2026-08-26T00:00:00Z",
    };
    expect(parseMethodResult("thread/create", projection)).toEqual(projection);
    expect(() =>
      parseMethodResult("thread/create", { ...projection, preferences: undefined }),
    ).toThrow();
  });

  /** thread/read 的 nullable Usage 是必需字段，且必须绑定完整请求画像并保持总量一致。 */
  it("validates required context Usage in Thread snapshots", () => {
    const turn = {
      turnId: "turn_runtime_a",
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
      goalActivities: [],
      contextUsage: {
        turnId: "turn_runtime_a",
        requestId: "request_demo",
        requestOrdinal: 1,
        modelRound: 1,
        purpose: "assistant" as const,
        certainty: "known",
        profile: REQUEST_PROFILE,
        inputTokens: 42_000,
        outputTokens: 2_000,
        totalTokens: 44_000,
        measuredAt: "2026-08-31T00:00:01Z",
      },
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);
    const terminalGoal = {
      goalId: "goal_done",
      objective: "完成生产验收",
      status: "achieved" as const,
      goalRevision: 8,
      eventSequence: 21,
      occurredAt: "2026-09-05T00:00:00Z",
    };
    expect(
      parseMethodResult("thread/read", { ...snapshot, goalActivities: [terminalGoal] }),
    ).toMatchObject({ goalActivities: [terminalGoal] });
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        goalActivities: [{ ...terminalGoal, status: "active" }],
      }),
    ).toThrow();
    const missingGoals: Record<string, unknown> = { ...snapshot };
    Reflect.deleteProperty(missingGoals, "goalActivities");
    expect(() => parseMethodResult("thread/read", missingGoals)).toThrow();
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

  /** 终态通知必须携带与状态一致的最终消息和错误对，避免失败被投影成无回复。 */
  it("rejects terminal states with incomplete or contradictory final projections", () => {
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
      changeSet: {
        state: "complete",
        incompleteReasons: [],
        files: [],
        stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
      },
    };
    const finalMessage = { messageId: "item_terminal_a", text: "本轮未能完成，请重试。" };
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
    ).toMatchObject({ params: { state: "failed", finalMessage } });
    expect(notification({ ...base, state: "cancelled" })).toMatchObject({
      params: { state: "cancelled" },
    });

    expect(() => notification({ ...base, state: "completed" })).toThrow();
    expect(() =>
      notification({ ...base, state: "failed", errorCode: "INTERNAL_ERROR", errorMessage: "x" }),
    ).toThrow();
    expect(() => notification({ ...base, state: "cancelled", finalMessage })).toThrow();
    expect(() =>
      notification({ ...base, state: "cancelled", errorCode: "CANCELLED", errorMessage: "x" }),
    ).toThrow();
  });

  /** 所有分页成功结果只接受 items/nextCursor，并拒绝领域专用旧列表键与身份回显。 */
  it("uses one list result envelope across all domains", () => {
    const workspace = {
      workspaceId: "ws_demo",
      root: "C:\\demo",
      displayName: "Demo",
      trust: "trusted" as const,
      revision: 1,
    };
    const thread = {
      threadId: "thr_demo",
      workspaceId: "ws_demo",
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
      activeGoalId: null,
      revision: 1,
      createdAt: "2026-08-26T00:00:00Z",
      updatedAt: "2026-08-26T00:00:00Z",
    };
    const skill = {
      skillId: "skill_demo",
      name: "Demo",
      scope: "builtin" as const,
      enabled: true,
      status: "healthy" as const,
      description: "demo",
    };
    const server = {
      mcpId: "mcp_demo",
      name: "Demo",
      transport: "stdio" as const,
      status: "healthy" as const,
      toolCount: 1,
    };
    const tool = { name: "read_file", description: "read", inputSchema: { type: "object" } };
    expect(
      parseMethodResult("workspace/list", { items: [workspace], nextCursor: null }),
    ).toMatchObject({ items: [workspace] });
    expect(parseMethodResult("thread/list", { items: [thread], nextCursor: null })).toMatchObject({
      items: [thread],
    });
    expect(parseMethodResult("skill/list", { items: [skill], nextCursor: null })).toMatchObject({
      items: [skill],
    });
    expect(parseMethodResult("mcp/list", { items: [server], nextCursor: null })).toMatchObject({
      items: [server],
    });
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
