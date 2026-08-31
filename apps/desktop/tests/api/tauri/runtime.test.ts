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

const readyStatus: RuntimeStatus = {
  status: "ready",
  generation: 1,
  serverInstanceId: "srv_fixture",
};
const generalWorkspace = {
  workspaceId: "ws_runtime_a" as const,
  displayName: "无项目" as const,
  trust: "trusted" as const,
  rootPath: "C:\\data\\ja\\data\\general-workspace",
};

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
      if (command === JA_RUNTIME_COMMANDS.turnCancel)
        return { accepted: true, turnId: "turn_fixture", status: "cancelled", threadRevision: 2 };
      if (command === JA_RUNTIME_COMMANDS.turnSteer)
        return {
          accepted: true,
          inputId: "input_steer",
          turnId: "turn_fixture",
          kind: "steering",
          status: "queued",
        };
      if (command === JA_RUNTIME_COMMANDS.turnFollowUp)
        return {
          accepted: true,
          inputId: "input_follow_up",
          turnId: "turn_fixture",
          kind: "follow_up",
          status: "queued",
        };
      if (command === JA_RUNTIME_COMMANDS.approvalRespond) return null;
      if (command === JA_RUNTIME_COMMANDS.recoveryState)
        return { required: false, acknowledgeable: false, recoveryId: null, revision: null };
      return readyStatus;
    }) as unknown as RuntimeNativeBridge["invoke"],
    listen: vi.fn(async () => () => undefined),
    ...overrides,
  };
}

describe("RuntimeHost v2 typed adapter", () => {
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
      if (command === JA_RUNTIME_COMMANDS.turnCancel)
        return { accepted: true, turnId: "turn_fixture", status: "cancelled", threadRevision: 2 };
      if (command === JA_RUNTIME_COMMANDS.turnSteer)
        return {
          accepted: true,
          inputId: "input_steer",
          turnId: "turn_fixture",
          kind: "steering",
          status: "queued",
        };
      if (command === JA_RUNTIME_COMMANDS.turnFollowUp)
        return {
          accepted: true,
          inputId: "input_follow_up",
          turnId: "turn_fixture",
          kind: "follow_up",
          status: "queued",
        };
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
      adapter.turnSteer({ turnId: "turn_fixture", text: "guide" }),
    ).resolves.toMatchObject({ kind: "steering" });
    await expect(
      adapter.turnFollowUp({ turnId: "turn_fixture", text: "later" }),
    ).resolves.toMatchObject({ kind: "follow_up" });
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
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnSteer, {
      input: { turnId: "turn_fixture", text: "guide" },
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
        reason: "turn_started",
      },
    });
    expect(event).toMatchObject({
      kind: "status",
      status: { status: "ready", generation: 1, serverInstanceId: "srv_fixture" },
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
        strategyVersion: "ja-context-v3",
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
        if (command !== JA_RUNTIME_COMMANDS.query) return readyStatus;
        const method = (args?.["input"] as { method?: string } | undefined)?.method;
        if (method === "skill/list") return { items: [], nextCursor: null };
        if (method === "mcp/list") return { items: [], nextCursor: null };
        if (method === "mcp/test") return { mcpId: "mcp_fixture", status: "healthy", toolCount: 0 };
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
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.query, {
      input: { method: "skill/list", params: {} },
    });
  });
});
