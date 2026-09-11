// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";
import {
  JA_RUNTIME_COMMANDS,
  RuntimeHostError,
  TauriRuntimeHostAdapter,
  normalizeRuntimeError,
  parseRuntimeHostEvent,
  type RuntimeNativeBridge,
  type RuntimeStatus,
} from "@/api/tauri/runtime";
import { TauriHistoryAdapter, type HistoryNativeBridge } from "@/api/tauri/history";

const readyStatus: RuntimeStatus = {
  status: "ready",
  generation: 1,
  serverInstanceId: "srv_fixture",
  features: ["task_threads_v1", "plan_goal_v1"],
};
const generalWorkspace = {
  workspaceId: "ws_runtime_a" as const,
  displayName: "无项目" as const,
  trust: "trusted" as const,
  rootPath: "C:\\data\\ja\\data\\general-workspace",
};
const requestProfile = {
  providerId: "provider_fixture",
  modelId: "model_fixture",
  api: "openai_responses" as const,
  upstreamModel: "gpt-5.6-sol",
  requestedReasoning: "high" as const,
  effectiveReasoning: "high" as const,
  accessMode: "approval_required" as const,
  collaborationMode: "default" as const,
  configGeneration: "cfg_fixture",
  promptRevision: "prompt_fixture",
  toolCatalogRevision: "tools_fixture",
  contextWindowTokens: 258_000,
  maxOutputTokens: 32_000,
};
const knownUsage = {
  requestId: "request_fixture",
  requestOrdinal: 1,
  modelRound: 1,
  purpose: "assistant" as const,
  measuredAt: "2026-09-07T00:00:01Z",
  profile: requestProfile,
  certainty: "known" as const,
  inputTokens: 12_000,
  outputTokens: 2_000,
  totalTokens: 14_000,
};
const unknownUsage = {
  ...knownUsage,
  requestId: "request_unknown",
  certainty: "unknown" as const,
  inputTokens: null,
  outputTokens: null,
  totalTokens: null,
};
const completeChangeSet = {
  state: "complete" as const,
  incompleteReasons: [],
  files: [],
  stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
};
const queueResult = (inputId: string, kind: "follow_up" | "steering" = "follow_up") => ({
  accepted: true as const,
  inputId,
  inputQueue: {
    turnId: "turn_fixture",
    revision: 1,
    accepting: true,
    items:
      inputId === "input_deleted"
        ? []
        : [
            {
              inputId,
              turnId: "turn_fixture",
              content: [{ type: "text", text: "later" }],
              attachments: [],
              kind,
              status: "pending",
              issue: null,
              inputRevision: 1,
              createdAt: "2026-09-01T00:00:00Z",
            },
          ],
  },
});

function createBridge(overrides: Partial<RuntimeNativeBridge> = {}): RuntimeNativeBridge {
  return {
    invoke: vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.generalWorkspace) return generalWorkspace;
      if (command === JA_RUNTIME_COMMANDS.storageInfo)
        return {
          nativeImage: false,
          dataPath: "C:\\data\\ja",
          logPath: null,
          cachePath: null,
          lastBackup: null,
        };
      if (command === JA_RUNTIME_COMMANDS.turnStart)
        return { accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 1 };
      if (command === JA_RUNTIME_COMMANDS.turnResume)
        return { accepted: true, turnId: "turn_fixture", queued: true, threadRevision: 2 };
      if (command === JA_RUNTIME_COMMANDS.turnCancel)
        return { accepted: true, turnId: "turn_fixture", status: "cancelled", threadRevision: 2 };
      if (command === JA_RUNTIME_COMMANDS.turnInputEnqueue) return queueResult("input_follow_up");
      if (command === JA_RUNTIME_COMMANDS.turnInputPrioritize)
        return queueResult("input_follow_up", "steering");
      if (command === JA_RUNTIME_COMMANDS.turnInputUpdate) return queueResult("input_follow_up");
      if (command === JA_RUNTIME_COMMANDS.turnInputDelete) return queueResult("input_deleted");
      if (command === JA_RUNTIME_COMMANDS.approvalRespond) return null;
      if (command === JA_RUNTIME_COMMANDS.recoveryState)
        return { required: false, acknowledgeable: false, recoveryId: null, revision: null };
      return readyStatus;
    }) as unknown as RuntimeNativeBridge["invoke"],
    listen: vi.fn(async () => () => undefined),
    ...overrides,
  };
}

describe("RuntimeHost v1 typed adapter", () => {
  afterEach(() => vi.restoreAllMocks());

  it("keeps raw invoke private and routes lifecycle/turn calls without config snapshot fields", async () => {
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.storageInfo)
        return {
          nativeImage: false,
          dataPath: "C:\\data\\ja",
          logPath: null,
          cachePath: null,
          lastBackup: null,
        };
      if (command === JA_RUNTIME_COMMANDS.generalWorkspace) return generalWorkspace;
      if (command === JA_RUNTIME_COMMANDS.turnStart)
        return { accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 1 };
      if (command === JA_RUNTIME_COMMANDS.turnResume)
        return { accepted: true, turnId: "turn_fixture", queued: true, threadRevision: 2 };
      if (command === JA_RUNTIME_COMMANDS.turnCancel)
        return { accepted: true, turnId: "turn_fixture", status: "cancelled", threadRevision: 2 };
      if (command === JA_RUNTIME_COMMANDS.turnInputEnqueue) return queueResult("input_follow_up");
      if (command === JA_RUNTIME_COMMANDS.turnInputPrioritize)
        return queueResult("input_follow_up", "steering");
      if (command === JA_RUNTIME_COMMANDS.turnInputUpdate) return queueResult("input_follow_up");
      if (command === JA_RUNTIME_COMMANDS.turnInputDelete) return queueResult("input_deleted");
      if (command === JA_RUNTIME_COMMANDS.approvalRespond) return null;
      return readyStatus;
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );

    await expect(adapter.start()).resolves.toEqual(readyStatus);
    await expect(adapter.stop()).resolves.toEqual(readyStatus);
    await expect(adapter.state()).resolves.toEqual(readyStatus);
    await expect(adapter.storageInfo()).resolves.toMatchObject({ dataPath: "C:\\data\\ja" });
    await expect(adapter.generalWorkspace()).resolves.toEqual(generalWorkspace);
    await expect(
      adapter.turnStart({ threadId: "thr_fixture", content: [{ type: "text", text: "hello" }] }),
    ).resolves.toMatchObject({ accepted: true });
    await expect(
      adapter.turnCancel({ turnId: "turn_fixture", expectedThreadRevision: 1 }),
    ).resolves.toMatchObject({ status: "cancelled" });
    await expect(
      adapter.turnResume({ turnId: "turn_fixture", expectedThreadRevision: 1 }),
    ).resolves.toEqual({
      accepted: true,
      turnId: "turn_fixture",
      queued: true,
      threadRevision: 2,
    });
    await expect(
      adapter.turnInputEnqueue({
        turnId: "turn_fixture",
        content: [{ type: "text", text: "later" }],
      }),
    ).resolves.toMatchObject({ inputId: "input_follow_up" });
    await expect(
      adapter.turnInputPrioritize({
        turnId: "turn_fixture",
        inputId: "input_follow_up",
        expectedInputRevision: 1,
      }),
    ).resolves.toMatchObject({ inputQueue: { items: [{ kind: "steering" }] } });
    await expect(
      adapter.turnInputUpdate({
        turnId: "turn_fixture",
        inputId: "input_follow_up",
        expectedInputRevision: 1,
        content: [{ type: "text", text: "later" }],
      }),
    ).resolves.toMatchObject({ inputId: "input_follow_up" });
    await expect(
      adapter.approvalRespond({
        approvalId: "appr_fixture",
        turnId: "turn_fixture",
        decision: "approve",
        expectedThreadRevision: 1,
      }),
    ).resolves.toBeUndefined();

    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.start, {});
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnStart, {
      input: { threadId: "thr_fixture", content: [{ type: "text", text: "hello" }] },
    });
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnInputEnqueue, {
      input: {
        turnId: "turn_fixture",
        content: [{ type: "text", text: "later" }],
      },
    });
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnResume, {
      input: { turnId: "turn_fixture", expectedThreadRevision: 1 },
    });
    expect(JSON.stringify(invoke.mock.calls)).not.toContain("configRevision");
    expect(JSON.stringify(invoke.mock.calls)).not.toContain("profileId");
  });

  it("rejects client-owned turn overrides before native invoke", async () => {
    const invoke = vi.fn(async (): Promise<unknown> => readyStatus);
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    await expect(
      adapter.turnStart({
        threadId: "thr_fixture",
        content: [{ type: "text", text: "hello" }],
        profileId: "profile_fixture",
        configRevision: "cfg_old",
        cwd: "C:\\private",
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).not.toHaveBeenCalled();
  });

  /** Resume 只接受 CAS 输入与 queued=true 同 Turn 结果，不能借恢复入口注入执行游标或宽化 ACK。 */
  it("fails closed on invalid turn resume input and result fixtures", async () => {
    const invalidInputInvoke = vi.fn(async (): Promise<unknown> => readyStatus);
    const invalidInput = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invalidInputInvoke as RuntimeNativeBridge["invoke"] }),
    );
    await expect(
      invalidInput.turnResume({
        turnId: "turn_fixture",
        expectedThreadRevision: 1,
        executionCursor: "private-cursor",
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invalidInputInvoke).not.toHaveBeenCalled();

    for (const fixture of [
      { accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 2 },
      {
        accepted: true,
        turnId: "turn_fixture",
        queued: true,
        threadRevision: 2,
        runtimeFingerprint: "private-fingerprint",
      },
      { accepted: true, turnId: "turn_other", queued: true, threadRevision: 2 },
    ]) {
      const adapter = new TauriRuntimeHostAdapter(
        createBridge({
          invoke: vi.fn(
            async (command): Promise<unknown> =>
              command === JA_RUNTIME_COMMANDS.turnResume ? fixture : readyStatus,
          ) as RuntimeNativeBridge["invoke"],
        }),
      );
      await expect(
        adapter.turnResume({ turnId: "turn_fixture", expectedThreadRevision: 1 }),
      ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE" });
    }
  });

  it("redacts invalid native frames and accepts the public host lifecycle projection", async () => {
    let handler: ((payload: unknown) => void) | undefined;
    const listener = vi.fn();
    const bridge = createBridge({
      listen: vi.fn(async (_event, next) => {
        handler = next;
        return () => undefined;
      }),
    });
    const adapter = new TauriRuntimeHostAdapter(bridge);
    await adapter.subscribe(listener);
    handler?.({
      jsonrpc: "2.0",
      method: "unsupported/event",
      params: { secret: "must-not-cross" },
    });
    expect(listener).toHaveBeenCalledWith({
      kind: "projection_fault",
      reason: "invalid_native_event",
    });
    expect(JSON.stringify(listener.mock.calls)).not.toContain("must-not-cross");

    const event = parseRuntimeHostEvent({
      jsonrpc: "2.0",
      method: "runtime/status-changed",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_ready",
        occurredAt: "2026-08-18T00:00:00Z",
        status: "ready",
        generation: 1,
        features: ["task_threads_v1", "plan_goal_v1"],
        reason: "turn_started",
      },
    });
    expect(event).toMatchObject({
      kind: "status",
      status: {
        status: "ready",
        generation: 1,
        serverInstanceId: "srv_fixture",
        features: ["task_threads_v1", "plan_goal_v1"],
      },
      reason: "turn_started",
    });
    expect(() =>
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "runtime/status-changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_secret",
          occurredAt: "2026-08-18T00:00:00Z",
          status: "ready",
          generation: 1,
          features: ["task_threads_v1", "plan_goal_v1"],
          readyToken: "0123456789abcdef0123456789abcdef",
        },
      }),
    ).toThrow();
  });

  it("只接收 App Server 安全 ToolPresentation 并拒绝 raw arguments", () => {
    const event = parseRuntimeHostEvent({
      jsonrpc: "2.0",
      method: "assistant/model-step-committed",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_model_step",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: "turn_fixture",
        threadRevision: 2,
        occurredAt: "2026-08-18T00:00:01Z",
        messageId: "item_fixture",
        text: "",
        modelRound: 1,
        toolCalls: [
          {
            callId: "call_fixture",
            toolName: "read",
            ordinal: 0,
            presentation: {
              kind: "read",
              title: "读取文件",
              status: "pending",
              inputPreview: "workspace.txt",
              relativePaths: ["workspace.txt"],
              truncated: false,
            },
          },
        ],
      },
    });

    expect(event).toMatchObject({
      kind: "timeline",
      event: {
        method: "assistant/model-step-committed",
        params: {
          toolCalls: [
            {
              callId: "call_fixture",
              toolName: "read",
              ordinal: 0,
              presentation: expect.objectContaining({
                kind: "read",
                inputPreview: "workspace.txt",
              }),
            },
          ],
        },
      },
    });
    expect(JSON.stringify(event)).not.toContain("arguments");
    expect(JSON.stringify(event)).not.toContain("must-not-enter-store");
    expect(JSON.stringify(event)).toContain("workspace.txt");
    expect(() =>
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "assistant/model-step-committed",
        params: {
          ...(event.kind === "timeline" ? event.event.params : {}),
          eventId: "evt_raw_tool",
          toolCalls: [
            {
              callId: "call_fixture",
              toolName: "read",
              ordinal: 0,
              arguments: { secret: "must-not-enter-store" },
            },
          ],
        },
      }),
    ).toThrow();
  });

  /** 完整请求画像是当前协议事实，安全预扫描只能过滤 Secret，不能先于 Zod 拒绝合法 Token 字段。 */
  it("projects complete known and unknown Usage without weakening strict protocol branches", () => {
    const knownEvent = {
      jsonrpc: "2.0",
      method: "assistant/model-step-committed",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_known_usage",
        sequence: 8,
        generation: 1,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: "turn_fixture",
        threadRevision: 8,
        occurredAt: "2026-09-07T00:00:01Z",
        messageId: "item_known_usage",
        text: "",
        modelRound: 1,
        usage: knownUsage,
        toolCalls: [
          {
            callId: "call_known_usage",
            toolName: "read",
            ordinal: 0,
            presentation: {
              kind: "read",
              title: "读取文件",
              status: "success",
              inputPreview: "README.md",
              outputPreview: "ok",
              relativePaths: ["README.md"],
              truncated: false,
            },
          },
        ],
      },
    };
    const unknownEvent = {
      jsonrpc: "2.0",
      method: "turn/terminal",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_unknown_usage",
        sequence: 9,
        generation: 1,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: "turn_fixture",
        threadRevision: 9,
        occurredAt: "2026-09-07T00:00:02Z",
        state: "completed",
        summary: "done",
        finalMessage: { messageId: "item_unknown_usage", text: "done" },
        usage: unknownUsage,
        changeSet: completeChangeSet,
      },
    };

    expect(parseRuntimeHostEvent(knownEvent)).toMatchObject({
      kind: "timeline",
      event: { params: { usage: knownUsage } },
    });
    expect(parseRuntimeHostEvent(unknownEvent)).toMatchObject({
      kind: "timeline",
      event: { params: { usage: unknownUsage } },
    });

    for (const invalidCapacity of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) {
      expect(() =>
        parseRuntimeHostEvent({
          ...knownEvent,
          params: {
            ...knownEvent.params,
            usage: {
              ...knownUsage,
              profile: { ...requestProfile, contextWindowTokens: invalidCapacity },
            },
          },
        }),
      ).toThrow();
      expect(() =>
        parseRuntimeHostEvent({
          ...knownEvent,
          params: {
            ...knownEvent.params,
            usage: {
              ...knownUsage,
              profile: { ...requestProfile, maxOutputTokens: invalidCapacity },
            },
          },
        }),
      ).toThrow();
    }
    for (const metric of ["inputTokens", "outputTokens", "totalTokens"] as const) {
      expect(() =>
        parseRuntimeHostEvent({
          ...knownEvent,
          params: { ...knownEvent.params, usage: { ...knownUsage, [metric]: null } },
        }),
      ).toThrow();
      expect(() =>
        parseRuntimeHostEvent({
          ...unknownEvent,
          params: { ...unknownEvent.params, usage: { ...unknownUsage, [metric]: 0 } },
        }),
      ).toThrow();
    }
  });

  /** 真实 History adapter 必须接收两种完整快照 Usage，确保首启事件与重启查询共享同一当前词汇。 */
  it("reads complete known and unknown Usage through the production history adapter", async () => {
    const snapshot = {
      threadId: "thr_fixture",
      revision: 9,
      turns: [
        {
          turnId: "turn_fixture",
          status: "completed" as const,
          requestedAt: "2026-09-07T00:00:00Z",
          updatedAt: "2026-09-07T00:00:02Z",
          completedAt: "2026-09-07T00:00:02Z",
          changeSet: completeChangeSet,
          errorCode: null,
        },
      ],
      items: [],
      taskActivities: [],
      goalActivities: [],
      inputQueue: null,
      contextUsage: { ...knownUsage, turnId: "turn_fixture" },
      nextCursor: null,
    };
    const invoke = vi
      .fn(async (): Promise<unknown> => snapshot)
      .mockResolvedValueOnce(snapshot)
      .mockResolvedValueOnce({
        ...snapshot,
        contextUsage: { ...unknownUsage, turnId: "turn_fixture" },
      });
    const adapter = new TauriHistoryAdapter({
      invoke: invoke as unknown as HistoryNativeBridge["invoke"],
    });

    await expect(adapter.threadRead({ threadId: "thr_fixture" })).resolves.toMatchObject({
      contextUsage: { certainty: "known", profile: requestProfile },
    });
    await expect(adapter.threadRead({ threadId: "thr_fixture" })).resolves.toMatchObject({
      contextUsage: {
        certainty: "unknown",
        inputTokens: null,
        outputTokens: null,
        totalTokens: null,
        profile: requestProfile,
      },
    });
  });

  /** Secret 邻接字段仍须在安全扫描阶段失败，并只向订阅者暴露无 payload 的恢复信号。 */
  it("redacts sensitive fields adjacent to otherwise valid profile and Usage", async () => {
    let handler: ((payload: unknown) => void) | undefined;
    const listener = vi.fn();
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({
        listen: vi.fn(async (_event, next) => {
          handler = next;
          return () => undefined;
        }),
      }),
    );
    await adapter.subscribe(listener);

    const unsafeUsageEvent = {
      jsonrpc: "2.0",
      method: "assistant/model-step-committed",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_sensitive_usage",
        sequence: 10,
        generation: 1,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: "turn_fixture",
        threadRevision: 10,
        occurredAt: "2026-09-07T00:00:03Z",
        messageId: "item_sensitive_usage",
        text: "",
        modelRound: 1,
        usage: {
          ...knownUsage,
          profile: { ...requestProfile, accessToken: "must-not-cross" },
          secret: "must-not-cross",
        },
        toolCalls: [
          {
            callId: "call_sensitive_usage",
            toolName: "read",
            ordinal: 0,
            presentation: {
              kind: "read",
              title: "读取文件",
              status: "success",
              inputPreview: "README.md",
              outputPreview: "ok",
              relativePaths: ["README.md"],
              truncated: false,
            },
          },
        ],
      },
    };

    expect(() => parseRuntimeHostEvent(unsafeUsageEvent)).toThrow();
    handler?.(unsafeUsageEvent);
    expect(listener).toHaveBeenCalledOnce();
    expect(listener).toHaveBeenCalledWith({
      kind: "projection_fault",
      reason: "invalid_native_event",
    });
    expect(JSON.stringify(listener.mock.calls)).not.toContain("must-not-cross");
  });

  /**
   * Java 在整个 Turn 内单调分配 Tool ordinal；前端只能校验当前批次连续，不能要求每轮重新从零开始。
   */
  it("accepts turn-global tool ordinals while rejecting an in-batch gap", () => {
    const parseModelStep = (modelRound: number, ordinals: readonly number[]) =>
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "assistant/model-step-committed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: `evt_model_step_${modelRound}`,
          sequence: modelRound,
          generation: 1,
          workspaceId: "ws_fixture",
          threadId: "thr_fixture",
          turnId: "turn_fixture",
          threadRevision: modelRound,
          occurredAt: `2026-08-18T00:00:0${modelRound}Z`,
          messageId: `item_fixture_${modelRound}`,
          text: "",
          modelRound,
          toolCalls: ordinals.map((ordinal, index) => ({
            callId: `call_fixture_${modelRound}_${index}`,
            toolName: "read",
            ordinal,
            presentation: {
              kind: "read",
              title: "读取文件",
              status: "pending",
              inputPreview: `fixture-${ordinal}.txt`,
              relativePaths: [`fixture-${ordinal}.txt`],
              truncated: false,
            },
          })),
        },
      });

    expect(parseModelStep(1, [0])).toMatchObject({
      kind: "timeline",
      event: { params: { toolCalls: [{ ordinal: 0 }] } },
    });
    expect(parseModelStep(2, [1])).toMatchObject({
      kind: "timeline",
      event: { params: { toolCalls: [{ ordinal: 1 }] } },
    });
    expect(parseModelStep(3, [2, 3])).toMatchObject({
      kind: "timeline",
      event: { params: { toolCalls: [{ ordinal: 2 }, { ordinal: 3 }] } },
    });
    expect(() => parseModelStep(4, [4, 6])).toThrow();
  });

  /** Renderer 只接收 started 的稳定关联，任何命令或 presentation 扩展都必须 fail closed。 */
  it("严格解析 tool/started 且不接受敏感或未知字段", () => {
    const started = {
      jsonrpc: "2.0",
      method: "tool/started",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_tool_started",
        sequence: 7,
        generation: 1,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: "turn_fixture",
        threadRevision: 7,
        occurredAt: "2026-08-30T10:00:01Z",
        callId: "call_fixture",
        ordinal: 3,
      },
    };

    expect(parseRuntimeHostEvent(started)).toMatchObject({
      kind: "timeline",
      event: { method: "tool/started", params: { callId: "call_fixture", ordinal: 3 } },
    });
    expect(() =>
      parseRuntimeHostEvent({
        ...started,
        params: { ...started.params, arguments: { command: "must-not-cross" } },
      }),
    ).toThrow();
    expect(() =>
      parseRuntimeHostEvent({ ...started, params: { ...started.params, ordinal: -1 } }),
    ).toThrow();
  });

  it("accepts workspace-scoped config invalidations and rejects cwd leakage", () => {
    expect(
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "configuration/changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_cfg_user",
          sequence: 1,
          occurredAt: "2026-08-18T00:00:01Z",
          generation: 1,
          scope: "user",
          version: "cfg_user_1",
        },
      }),
    ).toMatchObject({ kind: "timeline", event: { method: "configuration/changed" } });
    expect(
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "configuration/changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_cfg_project",
          sequence: 2,
          occurredAt: "2026-08-18T00:00:02Z",
          generation: 1,
          scope: "project",
          workspaceId: "ws_project",
          version: "cfg_project_1",
        },
      }),
    ).toMatchObject({ kind: "timeline", event: { method: "configuration/changed" } });
    expect(() =>
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "configuration/changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_cfg_missing",
          sequence: 3,
          occurredAt: "2026-08-18T00:00:03Z",
          generation: 1,
          scope: "project",
          version: "cfg_project_1",
        },
      }),
    ).toThrow();
    expect(() =>
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "configuration/changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_cfg_unsafe",
          sequence: 4,
          occurredAt: "2026-08-18T00:00:04Z",
          generation: 1,
          scope: "project",
          workspaceId: "ws_project",
          cwd: "C:\\private",
          version: "cfg_project_1",
        },
      }),
    ).toThrow();
  });

  it("admits strict nullable context Token metrics without weakening secret filtering", () => {
    const event = parseRuntimeHostEvent({
      jsonrpc: "2.0",
      method: "context/compaction-started",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_compaction_started",
        sequence: 5,
        occurredAt: "2026-08-18T00:00:05Z",
        generation: 1,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: null,
        threadRevision: 4,
        compactionId: "cmp_fixture",
        trigger: "manual",
        sourceRevision: 4,
        inputTokensBefore: 12_000,
        inputTokensAfter: null,
        strategyVersion: "ja-context-v1",
      },
    });
    expect(event).toMatchObject({
      kind: "timeline",
      event: {
        method: "context/compaction-started",
        params: { inputTokensBefore: 12_000, inputTokensAfter: null },
      },
    });
    expect(() =>
      parseRuntimeHostEvent({
        jsonrpc: "2.0",
        method: "context/compaction-started",
        params: {
          ...(event.kind === "timeline" ? event.event.params : {}),
          eventId: "evt_compaction_unsafe",
          accessToken: "secret",
        },
      }),
    ).toThrow();
  });

  it("normalizes native diagnostics and rejects malformed projections", async () => {
    expect(
      normalizeRuntimeError({ code: "RUNTIME_UNAVAILABLE", message: "C:\\private\\token=secret" }),
    ).toMatchObject({ code: "RUNTIME_UNAVAILABLE", message: "运行时暂不可用" });
    expect(normalizeRuntimeError({ code: "TURN_RESUME_ORDER_CONFLICT" })).toMatchObject({
      code: "TURN_RESUME_ORDER_CONFLICT",
      message: "请先处理更早中断的运行",
      retryable: true,
    });
    const malformed = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(
          async (): Promise<unknown> => ({ ...generalWorkspace, unexpected: true }),
        ) as RuntimeNativeBridge["invoke"],
      }),
    );
    await expect(malformed.generalWorkspace()).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
    });
    const unsafeRoot = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(
          async (): Promise<unknown> => ({ ...generalWorkspace, rootPath: "C:\\private\n" }),
        ) as RuntimeNativeBridge["invoke"],
      }),
    );
    await expect(unsafeRoot.generalWorkspace()).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
    });
    const rejected = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(async (): Promise<unknown> => {
          throw new RuntimeHostError("RUNTIME_UNAVAILABLE", "C:\\private", true);
        }) as RuntimeNativeBridge["invoke"],
      }),
    );
    await expect(rejected.state()).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
      message: "运行时暂不可用",
    });
  });

  it("keeps one Java general identity stable per response while allowing restart identity changes", async () => {
    const first = { ...generalWorkspace, workspaceId: "ws_runtime_a" };
    const second = { ...generalWorkspace, workspaceId: "ws_runtime_b" };
    const firstAdapter = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(
          async (command: string): Promise<unknown> =>
            command === JA_RUNTIME_COMMANDS.generalWorkspace ? first : readyStatus,
        ) as RuntimeNativeBridge["invoke"],
      }),
    );
    const secondAdapter = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(
          async (command: string): Promise<unknown> =>
            command === JA_RUNTIME_COMMANDS.generalWorkspace ? second : readyStatus,
        ) as RuntimeNativeBridge["invoke"],
      }),
    );

    await expect(firstAdapter.generalWorkspace()).resolves.toEqual(first);
    await expect(firstAdapter.generalWorkspace()).resolves.toEqual(first);
    await expect(secondAdapter.generalWorkspace()).resolves.toEqual(second);
    expect(first.workspaceId).not.toBe(second.workspaceId);
  });

  it("accepts the fixed settings query allowlist", async () => {
    const invoke = vi.fn(
      async (command: string, args?: Record<string, unknown>): Promise<unknown> => {
        if (command === JA_RUNTIME_COMMANDS.workspacePathSearch)
          return {
            threadId: "thr_fixture",
            workspaceId: "ws_runtime_a",
            generation: 1,
            query: "src",
            items: [{ relativePath: "src", kind: "directory" }],
            truncated: false,
          };
        if (command !== JA_RUNTIME_COMMANDS.query) return readyStatus;
        const method = (args?.["input"] as { method?: string } | undefined)?.method;
        if (method === "skill/list") return { items: [], nextCursor: null };
        if (method === "mcp/list") return { items: [], nextCursor: null };
        if (method === "mcp/test")
          return {
            mcpId: "mcp_fixture",
            name: "Fixture",
            transport: "stdio",
            status: "healthy",
            toolCount: 0,
          };
        return { items: [], nextCursor: null };
      },
    );
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    await expect(adapter.query("skill/list", {})).resolves.toMatchObject({ items: [] });
    await expect(adapter.query("mcp/test", { mcpId: "mcp_fixture" })).resolves.toMatchObject({
      mcpId: "mcp_fixture",
    });
    await expect(
      adapter.query("workspace/path/search", {
        threadId: "thr_fixture",
        workspaceId: "ws_runtime_a",
        query: "src",
      }),
    ).resolves.toMatchObject({ items: [{ relativePath: "src", kind: "directory" }] });
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.query, {
      input: { method: "skill/list", params: {} },
    });
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.workspacePathSearch, {
      input: {
        threadId: "thr_fixture",
        workspaceId: "ws_runtime_a",
        query: "src",
      },
    });
  });
});
