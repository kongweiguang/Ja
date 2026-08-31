// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { parseMethodParams, parseMethodResult } from "@/api/protocol/methods";
import { parseNotification } from "@/api/protocol/protocol";

describe("JA RPC v2 configuration ownership", () => {
  it("accepts cwd/thread/turn intent without client-owned identity or generation", () => {
    expect(parseMethodParams("workspace/open", { cwd: "C:\\demo" })).toEqual({ cwd: "C:\\demo" });
    expect(parseMethodParams("workspace/open-general", {})).toEqual({});
    expect(() => parseMethodParams("workspace/open-general", { cwd: "C:\\demo" })).toThrow();
    expect(
      parseMethodParams("thread/create", {
        cwd: "C:\\demo",
        title: "Chat",
        providerId: "provider_demo",
        modelId: "model_demo",
        reasoningLevel: "medium",
        accessMode: "approval_required",
      }),
    ).toEqual({
      cwd: "C:\\demo",
      title: "Chat",
      providerId: "provider_demo",
      modelId: "model_demo",
      reasoningLevel: "medium",
      accessMode: "approval_required",
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
        titleSource: "placeholder",
      },
      title: "Chat",
      status: "active" as const,
      revision: 0,
      createdAt: "2026-08-26T00:00:00Z",
      updatedAt: "2026-08-26T00:00:00Z",
    };
    expect(parseMethodResult("thread/create", projection)).toEqual(projection);
    expect(() =>
      parseMethodResult("thread/create", { ...projection, preferences: undefined }),
    ).toThrow();
  });

  /** thread/read 的 nullable Usage 是必需字段，且只能引用同一快照 Turn 并保持总量一致。 */
  it("validates required context Usage in Thread snapshots", () => {
    const turn = {
      turnId: "turn_runtime_a",
      status: "completed" as const,
      runtime: null,
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
      contextUsage: {
        turnId: turn.turnId,
        modelRound: 1,
        inputTokens: 42_000,
        outputTokens: 2_000,
        totalTokens: 44_000,
        measuredAt: "2026-08-31T00:00:01Z",
      },
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);
    expect(parseMethodResult("thread/read", { ...snapshot, contextUsage: null })).toMatchObject({
      contextUsage: null,
    });
    const missingUsage: Record<string, unknown> = { ...snapshot };
    Reflect.deleteProperty(missingUsage, "contextUsage");
    expect(() => parseMethodResult("thread/read", missingUsage)).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        contextUsage: { ...snapshot.contextUsage, turnId: "turn_other" },
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        contextUsage: { ...snapshot.contextUsage, totalTokens: 43_999 },
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
      strategyVersion: "ja-context-v3" as const,
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
        titleSource: "placeholder",
      },
      title: "Demo",
      status: "active" as const,
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
        protocolMajor: 2,
        protocolMinor: 0,
        clientVersion: "2.0.0",
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
