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
  features: ["task_threads", "plan"],
};
const sessionWorkspace = {
  workspaceId: "ws_runtime_a" as const,
  kind: "session" as const,
  displayName: "会话 A" as const,
  trust: "trusted" as const,
  rootPath: "C:\\data\\ja\\workspaces\\thr_runtime_a",
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
  modelRequestId: "modelreq_fixture",
  modelRequestOrdinal: 1,
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
  modelRequestId: "modelreq_unknown",
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
      if (command === JA_RUNTIME_COMMANDS.workspaceActivate) return sessionWorkspace;
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
      if (command === JA_RUNTIME_COMMANDS.turnContinue || command === JA_RUNTIME_COMMANDS.turnReask)
        return { accepted: true, turnId: "turn_fixture", queued: true, threadRevision: 3 };
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

/** Fixture exposes the target Thread revision only under thread.revision, matching the fork receipt contract. */
function forkReceipt(
  sourceThreadId = "thr_source",
  sourceItemId = "item_answer",
  sourceRevision = 7,
) {
  return {
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
      revision: 2,
      createdAt: "2026-10-01T00:00:00Z",
      updatedAt: "2026-10-01T00:00:00Z",
    },
    source: {
      threadId: sourceThreadId,
      itemId: sourceItemId,
      threadRevision: sourceRevision,
    },
  };
}

describe("RuntimeHost v1 typed adapter", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  /** 先持久化来源身份，确认回执后留到成功打开才清理，避免打开失败时重复创建。 */
  it("freezes a fork tuple and retains the created receipt until the target is opened", async () => {
    const input = {
      sourceThreadId: "thr_source",
      sourceItemId: "item_answer",
      expectedThreadRevision: 7,
      operationKey: "op_0123456789abcdef0123456789abcdef",
    };
    const result = forkReceipt();
    const invoke = vi.fn(async (command: string, args: unknown): Promise<unknown> => {
      expect(command).toBe(JA_RUNTIME_COMMANDS.threadFork);
      expect(args).toEqual({ input });
      expect(JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]")).toMatchObject([
        {
          method: "thread/fork",
          threadId: input.sourceThreadId,
          sourceItemId: input.sourceItemId,
          expectedThreadRevision: input.expectedThreadRevision,
          operationKey: input.operationKey,
        },
      ]);
      return result;
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );

    await expect(adapter.threadFork(input)).resolves.toEqual(result);
    const journal = JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]");
    expect(journal).toMatchObject([
      { method: "thread/fork", sourceItemId: input.sourceItemId, createdResult: result },
    ]);
    expect(JSON.stringify(journal)).not.toContain("transcript");
    await expect(adapter.recoverThreadForks()).resolves.toEqual([
      { state: "created", input, result },
    ]);
    await expect(
      adapter.threadFork({ ...input, sourceItemId: "item_other" }),
    ).rejects.toMatchObject({ code: "CONFLICT" });
    await adapter.acknowledgeThreadForkOpened(input.operationKey);
    expect(localStorage.getItem("ja.pending-operations.v1")).toBe("[]");
  });

  /** Fork 原子回执已证明创建；恢复只持久化目标身份，避免为未打开会话读取历史。 */
  it("recovers an unknown fork receipt without reading the child thread", async () => {
    const input = {
      sourceThreadId: "thr_source",
      sourceItemId: "item_answer",
      expectedThreadRevision: 7,
      operationKey: "op_1123456789abcdef0123456789abcdef",
    };
    const result = forkReceipt();
    let operationReadCount = 0;
    const invoke = vi.fn(async (command: string, args: unknown): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.threadFork) throw new Error("connection lost");
      if (command === JA_RUNTIME_COMMANDS.operationRead) {
        expect(args).toEqual({
          input: { operationKey: input.operationKey, sourceThreadId: input.sourceThreadId },
        });
        operationReadCount += 1;
        return operationReadCount === 1
          ? { status: "unknown" }
          : {
              status: "committed",
              method: "thread/fork",
              threadId: input.sourceThreadId,
              result,
            };
      }
      throw new Error(`unexpected command: ${command}`);
    });
    const makeAdapter = () =>
      new TauriRuntimeHostAdapter(
        createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
      );

    await expect(makeAdapter().threadFork(input)).rejects.toMatchObject({
      code: "OPERATION_UNCONFIRMED",
    });
    expect(JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]")).toMatchObject([
      { operationKey: input.operationKey, method: "thread/fork", sourceItemId: input.sourceItemId },
    ]);
    await expect(makeAdapter().recoverThreadForks()).resolves.toEqual([
      { state: "created", input, result },
    ]);
    expect(operationReadCount).toBe(2);
    expect(invoke.mock.calls.map(([command]) => command)).toEqual([
      JA_RUNTIME_COMMANDS.threadFork,
      JA_RUNTIME_COMMANDS.operationRead,
      JA_RUNTIME_COMMANDS.operationRead,
    ]);
    expect(JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]")).toMatchObject([
      {
        operationKey: input.operationKey,
        method: "thread/fork",
        threadId: input.sourceThreadId,
        sourceItemId: input.sourceItemId,
        expectedThreadRevision: input.expectedThreadRevision,
        createdResult: result,
      },
    ]);

    await expect(makeAdapter().recoverThreadForks()).resolves.toEqual([
      { state: "created", input, result },
    ]);
    expect(invoke).toHaveBeenCalledTimes(3);
  });

  /** Receipt envelope 必须仍属于发起 fork 的来源 Thread，即使嵌套结果和 Item 三元组合法。 */
  it("keeps a fork pending when the committed receipt has a different outer owner", async () => {
    const input = {
      sourceThreadId: "thr_source",
      sourceItemId: "item_answer",
      expectedThreadRevision: 7,
      operationKey: "op_4123456789abcdef0123456789abcdef",
    };
    const result = forkReceipt();
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.threadFork) throw new Error("connection lost");
      if (command === JA_RUNTIME_COMMANDS.operationRead) {
        return {
          status: "committed",
          method: "thread/fork",
          threadId: "thr_other_source",
          result,
        };
      }
      throw new Error(`unexpected command: ${command}`);
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );

    await expect(adapter.threadFork(input)).rejects.toMatchObject({
      code: "OPERATION_UNCONFIRMED",
    });
    await expect(adapter.recoverThreadForks()).resolves.toEqual([{ state: "pending", input }]);
    expect(invoke.mock.calls.map(([command]) => command)).toEqual([
      JA_RUNTIME_COMMANDS.threadFork,
      JA_RUNTIME_COMMANDS.operationRead,
      JA_RUNTIME_COMMANDS.operationRead,
    ]);
    const pending = JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]") as Array<
      Record<string, unknown>
    >;
    expect(pending).toMatchObject([
      { operationKey: input.operationKey, method: "thread/fork", threadId: input.sourceThreadId },
    ]);
    expect(pending[0]).not.toHaveProperty("createdResult");
  });

  /** 回执查询失败代表状态未知而非提交失败；保留原键，待下一次 owner 查证后打开既有子会话。 */
  it("does not resubmit a pending fork when receipt lookup fails", async () => {
    const input = {
      sourceThreadId: "thr_source",
      sourceItemId: "item_answer",
      expectedThreadRevision: 7,
      operationKey: "op_2123456789abcdef0123456789abcdef",
    };
    const result = forkReceipt();
    let forkCount = 0;
    let operationReadCount = 0;
    const invoke = vi.fn(async (command: string, args: unknown): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.threadFork) {
        forkCount += 1;
        expect(args).toEqual({ input });
        throw new Error("connection lost after submit");
      }
      if (command === JA_RUNTIME_COMMANDS.operationRead) {
        expect(args).toEqual({
          input: { operationKey: input.operationKey, sourceThreadId: input.sourceThreadId },
        });
        operationReadCount += 1;
        if (operationReadCount === 1) throw new Error("receipt store unavailable");
        return {
          status: "committed",
          method: "thread/fork",
          threadId: input.sourceThreadId,
          result,
        };
      }
      throw new Error(`unexpected command: ${command}`);
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );

    await expect(adapter.threadFork(input)).rejects.toMatchObject({
      code: "OPERATION_UNCONFIRMED",
    });
    expect(forkCount).toBe(1);
    expect(JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]")).toMatchObject([
      {
        operationKey: input.operationKey,
        method: "thread/fork",
        threadId: input.sourceThreadId,
        sourceItemId: input.sourceItemId,
        expectedThreadRevision: input.expectedThreadRevision,
      },
    ]);

    await expect(adapter.threadFork(input)).resolves.toEqual(result);
    expect(forkCount).toBe(1);
    expect(operationReadCount).toBe(2);
    expect(invoke.mock.calls.map(([command]) => command)).toEqual([
      JA_RUNTIME_COMMANDS.threadFork,
      JA_RUNTIME_COMMANDS.operationRead,
      JA_RUNTIME_COMMANDS.operationRead,
    ]);
    expect(JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]")).toMatchObject([
      { operationKey: input.operationKey, method: "thread/fork", createdResult: result },
    ]);
  });

  /** 只有 owner 查询明确返回 unknown 才能重提，并且重提参数必须与持久 tuple 完全相同。 */
  it("resubmits a pending fork only after an explicit unknown receipt", async () => {
    const input = {
      sourceThreadId: "thr_source",
      sourceItemId: "item_answer",
      expectedThreadRevision: 7,
      operationKey: "op_3123456789abcdef0123456789abcdef",
    };
    const result = forkReceipt();
    let forkCount = 0;
    let operationReadCount = 0;
    const invoke = vi.fn(async (command: string, args: unknown): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.threadFork) {
        forkCount += 1;
        expect(args).toEqual({ input });
        if (forkCount === 1) throw new Error("connection lost after submit");
        return result;
      }
      if (command === JA_RUNTIME_COMMANDS.operationRead) {
        expect(args).toEqual({
          input: { operationKey: input.operationKey, sourceThreadId: input.sourceThreadId },
        });
        operationReadCount += 1;
        return { status: "unknown" };
      }
      throw new Error(`unexpected command: ${command}`);
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );

    await expect(adapter.threadFork(input)).rejects.toMatchObject({
      code: "OPERATION_UNCONFIRMED",
    });
    await expect(adapter.threadFork(input)).resolves.toEqual(result);
    expect(forkCount).toBe(2);
    expect(operationReadCount).toBe(2);
    expect(JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]")).toMatchObject([
      {
        operationKey: input.operationKey,
        method: "thread/fork",
        sourceItemId: input.sourceItemId,
        expectedThreadRevision: input.expectedThreadRevision,
        createdResult: result,
      },
    ]);
  });

  /** 持久提交日志先于 RPC，但仍在等待 ACK 的请求不能成为结果未知警告或被人工解除。 */
  it.each(["turn/start", "turn/continue", "turn/reask", "approval/respond"] as const)(
    "%s 等待正常回执时保留日志但不发布待核实提示",
    async (method) => {
      let resolveAck!: (value: unknown) => void;
      const ack = new Promise<unknown>((resolve) => {
        resolveAck = resolve;
      });
      const command = {
        "turn/start": JA_RUNTIME_COMMANDS.turnStart,
        "turn/continue": JA_RUNTIME_COMMANDS.turnContinue,
        "turn/reask": JA_RUNTIME_COMMANDS.turnReask,
        "approval/respond": JA_RUNTIME_COMMANDS.approvalRespond,
      }[method];
      const invoke = vi.fn(async (nextCommand: string): Promise<unknown> => {
        if (nextCommand === command) return ack;
        if (nextCommand === JA_RUNTIME_COMMANDS.operationRead) return { status: "unknown" };
        throw new Error("unexpected diagnostic read");
      });
      const adapter = new TauriRuntimeHostAdapter(
        createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
      );
      const listener = vi.fn();
      const unsubscribe = adapter.subscribePendingOperations(listener);
      const input = {
        threadId: "thr_fixture",
        content: [{ type: "text" as const, text: "hello" }],
      };
      const submission =
        method === "turn/start"
          ? adapter.turnStart(input)
          : method === "turn/continue"
            ? adapter.turnContinue({ threadId: input.threadId, expectedThreadRevision: 1 })
            : method === "turn/reask"
              ? adapter.turnReask({
                  ...input,
                  expectedThreadRevision: 1,
                  sourceMessageId: "item_fixture",
                })
              : adapter.approvalRespond({
                  approvalId: "appr_fixture",
                  turnId: "turn_fixture",
                  decision: "approve",
                  expectedThreadRevision: 1,
                });
      try {
        await vi.waitFor(() => expect(invoke).toHaveBeenCalledWith(command, expect.anything()));
        const journal = JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]");
        expect(journal).toHaveLength(1);
        expect(journal[0]).toMatchObject({ method });
        expect(JSON.stringify(journal)).not.toContain("hello");
        expect(listener.mock.calls.every(([records]) => records.length === 0)).toBe(true);
        await expect(adapter.pendingOperations()).resolves.toEqual([]);
        await expect(adapter.recheckPendingOperations()).resolves.toEqual([]);
        expect(invoke).toHaveBeenCalledTimes(1);
        await expect(
          adapter.acknowledgePendingOperation(journal[0].operationKey),
        ).rejects.toMatchObject({ code: "OPERATION_UNCONFIRMED" });
        expect(localStorage.getItem("ja.acknowledged-unknown-operations.v1")).toBeNull();
      } finally {
        resolveAck(
          method === "approval/respond"
            ? null
            : { accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 1 },
        );
        await submission;
        unsubscribe();
      }
      expect(localStorage.getItem("ja.pending-operations.v1")).toBe("[]");
      expect(listener.mock.calls.every(([records]) => records.length === 0)).toBe(true);
    },
  );

  /** 关闭未知提示不等于放宽在途提交门禁；不同 adapter 也必须共享同一 Thread 的保护。 */
  it("在途请求不显示警告，但仍阻止同一会话重复提交", async () => {
    let resolveAck!: (value: unknown) => void;
    const ack = new Promise<unknown>((resolve) => {
      resolveAck = resolve;
    });
    const invoke = vi.fn(async (): Promise<unknown> => ack);
    const bridge = createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] });
    const adapter = new TauriRuntimeHostAdapter(bridge);
    const otherAdapter = new TauriRuntimeHostAdapter(bridge);
    const input = {
      threadId: "thr_fixture",
      content: [{ type: "text" as const, text: "hello" }],
    };
    const submission = adapter.turnStart(input);
    try {
      await vi.waitFor(() => expect(invoke).toHaveBeenCalledTimes(1));
      await expect(otherAdapter.pendingOperations()).resolves.toEqual([]);
      await expect(otherAdapter.turnStart(input)).rejects.toMatchObject({
        code: "OPERATION_UNCONFIRMED",
      });
      expect(invoke).toHaveBeenCalledTimes(1);
    } finally {
      resolveAck({ accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 1 });
      await submission;
    }
  });

  /** 独立 Thread 可并发；一个 ACK 清理日志时不能把另一个在途请求提升为未知结果。 */
  it("不同会话的并发提交保留各自日志且不产生核查警告", async () => {
    const resolvers = new Map<string, (value: unknown) => void>();
    const invoke = vi.fn(
      async (command: string, args?: Record<string, unknown>): Promise<unknown> => {
        if (command !== JA_RUNTIME_COMMANDS.turnStart) throw new Error("unexpected read");
        const input = args?.["input"] as { threadId: string };
        return new Promise((resolve) => resolvers.set(input.threadId, resolve));
      },
    );
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    const listener = vi.fn();
    const unsubscribe = adapter.subscribePendingOperations(listener);
    const first = adapter.turnStart({
      threadId: "thr_first",
      content: [{ type: "text", text: "first" }],
    });
    const second = adapter.turnStart({
      threadId: "thr_second",
      content: [{ type: "text", text: "second" }],
    });
    try {
      await vi.waitFor(() => expect(resolvers.size).toBe(2));
      const readJournal = (): { threadId: string }[] =>
        JSON.parse(localStorage.getItem("ja.pending-operations.v1") ?? "[]");
      expect(readJournal()).toHaveLength(2);
      resolvers.get("thr_first")?.({
        accepted: true,
        turnId: "turn_first",
        queued: false,
        threadRevision: 1,
      });
      await first;
      expect(readJournal()).toMatchObject([{ threadId: "thr_second" }]);
      await expect(adapter.pendingOperations()).resolves.toEqual([]);
      expect(listener.mock.calls.every(([records]) => records.length === 0)).toBe(true);
    } finally {
      for (const [threadId, resolve] of resolvers)
        resolve({
          accepted: true,
          turnId: threadId === "thr_first" ? "turn_first" : "turn_second",
          queued: false,
          threadRevision: 1,
        });
      await Promise.all([first, second]);
      unsubscribe();
    }
    expect(localStorage.getItem("ja.pending-operations.v1")).toBe("[]");
  });

  /** 自动查询持久回执仍属于原请求结算；短暂丢 ACK 后成功收敛不能先闪未知警告。 */
  it("自动回查确认已提交的请求保持静默并清除日志", async () => {
    let resolveReceipt!: (value: unknown) => void;
    const receipt = new Promise<unknown>((resolve) => {
      resolveReceipt = resolve;
    });
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.turnStart) throw new Error("ACK lost");
      if (command === JA_RUNTIME_COMMANDS.operationRead) return receipt;
      if (command === JA_RUNTIME_COMMANDS.threadReadForOperation)
        return {
          threadId: "thr_fixture",
          revision: 1,
          turns: [],
          items: [],
          taskActivities: [],
          inputQueue: null,
          contextUsage: null,
          liveStream: null,
          nextCursor: null,
        };
      throw new Error("unexpected invoke");
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    const listener = vi.fn();
    const unsubscribe = adapter.subscribePendingOperations(listener);
    const submission = adapter.turnStart({
      threadId: "thr_fixture",
      content: [{ type: "text", text: "hello" }],
    });
    try {
      await vi.waitFor(() => expect(invoke).toHaveBeenCalledTimes(2));
      await expect(adapter.pendingOperations()).resolves.toEqual([]);
      expect(listener.mock.calls.every(([records]) => records.length === 0)).toBe(true);
    } finally {
      resolveReceipt({
        status: "committed",
        method: "turn/start",
        threadId: "thr_fixture",
        result: { accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 1 },
      });
      await submission;
      unsubscribe();
    }
    expect(localStorage.getItem("ja.pending-operations.v1")).toBe("[]");
    expect(listener.mock.calls.every(([records]) => records.length === 0)).toBe(true);
    expect(invoke).toHaveBeenCalledTimes(3);
  });

  it("丢失提交响应后持久保留同一操作 ID，重载只查回执不自动重发", async () => {
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.turnStart) throw new Error("connection lost");
      if (command === JA_RUNTIME_COMMANDS.operationRead) return { status: "unknown" };
      return readyStatus;
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    const listener = vi.fn();
    const unsubscribe = adapter.subscribePendingOperations(listener);
    const input = { threadId: "thr_fixture", content: [{ type: "text" as const, text: "hello" }] };
    await expect(adapter.turnStart(input)).rejects.toMatchObject({ code: "OPERATION_UNCONFIRMED" });
    const pending = await adapter.pendingOperations();
    expect(pending).toHaveLength(1);
    expect(pending[0]).toMatchObject({ method: "turn/start", threadId: "thr_fixture" });
    expect(JSON.stringify(pending)).not.toContain("hello");
    expect(listener).toHaveBeenLastCalledWith(pending);
    unsubscribe();

    const reloaded = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    await expect(reloaded.turnStart(input)).rejects.toMatchObject({
      code: "OPERATION_UNCONFIRMED",
    });
    expect(
      invoke.mock.calls.filter(([command]) => command === JA_RUNTIME_COMMANDS.turnStart),
    ).toHaveLength(1);
  });

  it("已提交回执经权威 Thread revision 对账后清除重载残留", async () => {
    const operationId = "op_0123456789abcdef0123456789abcdef";
    localStorage.setItem(
      "ja.pending-operations.v1",
      JSON.stringify([
        {
          operationKey: operationId,
          method: "turn/start",
          threadId: "thr_fixture",
          createdAt: "2026-09-24T00:00:00.000Z",
        },
      ]),
    );
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.operationRead)
        return {
          status: "committed",
          method: "turn/start",
          threadId: "thr_fixture",
          result: {
            accepted: true,
            queued: false,
            turnId: "turn_fixture",
            threadRevision: 1,
          },
        };
      if (command === JA_RUNTIME_COMMANDS.threadReadForOperation)
        return {
          threadId: "thr_fixture",
          revision: 1,
          turns: [],
          items: [],
          taskActivities: [],
          inputQueue: null,
          contextUsage: null,
          liveStream: null,
          nextCursor: null,
        };
      return readyStatus;
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    await expect(adapter.recheckPendingOperations()).resolves.toEqual([]);
    expect(invoke.mock.calls.map(([command]) => command)).toEqual([
      JA_RUNTIME_COMMANDS.operationRead,
      JA_RUNTIME_COMMANDS.threadReadForOperation,
    ]);
    expect(localStorage.getItem("ja.pending-operations.v1")).toBe("[]");
  });

  it("人工二次确认仅解除 unknown 的本机阻挡并保留不确定审计", async () => {
    const operationId = "op_0123456789abcdef0123456789abcdef";
    localStorage.setItem(
      "ja.pending-operations.v1",
      JSON.stringify([
        {
          operationKey: operationId,
          method: "turn/start",
          threadId: "thr_fixture",
          createdAt: "2026-09-24T00:00:00.000Z",
        },
      ]),
    );
    const invoke = vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_RUNTIME_COMMANDS.operationRead) return { status: "unknown" };
      throw new Error("new request must not be sent");
    });
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    await expect(adapter.acknowledgePendingOperation(operationId)).resolves.toMatchObject({
      status: "unknown_acknowledged",
      pending: [],
    });
    expect(
      JSON.parse(localStorage.getItem("ja.acknowledged-unknown-operations.v1") ?? "[]"),
    ).toMatchObject([
      {
        operationKey: operationId,
        method: "turn/start",
        threadId: "thr_fixture",
      },
    ]);
    expect(invoke.mock.calls.map(([command]) => command)).toEqual([
      JA_RUNTIME_COMMANDS.operationRead,
    ]);
  });

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
      if (command === JA_RUNTIME_COMMANDS.workspaceActivate) return sessionWorkspace;
      if (command === JA_RUNTIME_COMMANDS.turnStart)
        return { accepted: true, turnId: "turn_fixture", queued: false, threadRevision: 1 };
      if (command === JA_RUNTIME_COMMANDS.turnContinue || command === JA_RUNTIME_COMMANDS.turnReask)
        return { accepted: true, turnId: "turn_fixture", queued: true, threadRevision: 3 };
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
    await expect(adapter.activateWorkspace(sessionWorkspace.workspaceId)).resolves.toEqual(
      sessionWorkspace,
    );
    await expect(
      adapter.turnStart({ threadId: "thr_fixture", content: [{ type: "text", text: "hello" }] }),
    ).resolves.toMatchObject({ accepted: true });
    await expect(
      adapter.turnContinue({ threadId: "thr_fixture", expectedThreadRevision: 2 }),
    ).resolves.toEqual({
      accepted: true,
      turnId: "turn_fixture",
      queued: true,
      threadRevision: 3,
    });
    await expect(
      adapter.turnReask({
        threadId: "thr_fixture",
        expectedThreadRevision: 2,
        sourceMessageId: "item_question",
        content: [{ type: "text", text: "edited" }],
      }),
    ).resolves.toMatchObject({ accepted: true, queued: true });
    await expect(adapter.turnCancel({ turnId: "turn_fixture" })).resolves.toMatchObject({
      status: "cancelled",
    });
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
      input: {
        threadId: "thr_fixture",
        content: [{ type: "text", text: "hello" }],
        operationKey: expect.stringMatching(/^op_[0-9a-f]{32}$/),
      },
    });
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnContinue, {
      input: {
        threadId: "thr_fixture",
        expectedThreadRevision: 2,
        operationKey: expect.stringMatching(/^op_[0-9a-f]{32}$/),
      },
    });
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnReask, {
      input: {
        threadId: "thr_fixture",
        expectedThreadRevision: 2,
        sourceMessageId: "item_question",
        content: [{ type: "text", text: "edited" }],
        operationKey: expect.stringMatching(/^op_[0-9a-f]{32}$/),
      },
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
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnCancel, {
      input: { turnId: "turn_fixture" },
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

  /** Runtime feature 合同使用无后缀标识；旧 key 必须拒绝，避免静默关闭新能力。 */
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
        features: ["task_threads", "plan"],
        reason: "turn_started",
      },
    });
    expect(event).toMatchObject({
      kind: "status",
      status: {
        status: "ready",
        generation: 1,
        serverInstanceId: "srv_fixture",
        features: ["task_threads", "plan"],
      },
      reason: "turn_started",
    });
    for (const legacyFeature of ["task_threads_v1", "plan_v1", "interaction_v1"] as const) {
      expect(() =>
        parseRuntimeHostEvent({
          jsonrpc: "2.0",
          method: "runtime/status-changed",
          params: {
            serverInstanceId: "srv_fixture",
            eventId: "evt_legacy_feature",
            occurredAt: "2026-08-18T00:00:00Z",
            status: "ready",
            generation: 1,
            features: [legacyFeature],
            reason: "turn_started",
          },
        }),
      ).toThrow();
    }
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
          features: ["task_threads", "plan"],
          readyToken: "0123456789abcdef0123456789abcdef",
        },
      }),
    ).toThrow();
  });

  /** 恢复信号保留连接排序序号，并拒绝把它或额外 Thread revision 当成快照游标。 */
  it("routes projection recovery notifications to authoritative thread resync", () => {
    const notification = {
      jsonrpc: "2.0",
      method: "turn/resync-required",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_resync_fixture",
        sequence: 66,
        occurredAt: "2026-09-27T00:00:00Z",
        generation: 2,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: "turn_fixture",
        reasonCode: "projection_failed",
      },
    };
    expect(parseRuntimeHostEvent(notification)).toEqual({
      kind: "resync_required",
      event: notification.params,
    });
    expect(() =>
      parseRuntimeHostEvent({
        ...notification,
        params: { ...notification.params, sequence: 0 },
      }),
    ).toThrow();
    expect(() =>
      parseRuntimeHostEvent({
        ...notification,
        params: { ...notification.params, threadRevision: 7 },
      }),
    ).toThrow();
    expect(() =>
      parseRuntimeHostEvent({
        ...notification,
        params: { ...notification.params, reasonCode: "terminal_missing" },
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
        totalCharacters: 0,
        truncated: false,
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
        totalCharacters: 0,
        truncated: false,
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
        summaryTotalCharacters: 4,
        summaryTruncated: false,
        finalMessage: {
          messageId: "item_unknown_usage",
          text: "done",
          totalCharacters: 4,
          truncated: false,
        },
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
          origin: "native" as const,
          sourceMessageId: null,
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
      inputQueue: null,
      contextUsage: { ...knownUsage, turnId: "turn_fixture", contextEstimate: null },
      liveStream: null,
      nextCursor: null,
    };
    const invoke = vi
      .fn(async (): Promise<unknown> => snapshot)
      .mockResolvedValueOnce(snapshot)
      .mockResolvedValueOnce({
        ...snapshot,
        contextUsage: { ...unknownUsage, turnId: "turn_fixture", contextEstimate: null },
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
        totalCharacters: 0,
        truncated: false,
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
          totalCharacters: 0,
          truncated: false,
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
        callId: "toolu_01HABC123",
        ordinal: 3,
      },
    };

    expect(parseRuntimeHostEvent(started)).toMatchObject({
      kind: "timeline",
      event: { method: "tool/started", params: { callId: "toolu_01HABC123", ordinal: 3 } },
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

  /** Tool 恢复 RPC 保留 Provider 原始 callId，并在调用 native bridge 前拒绝路径字符。 */
  it("preserves opaque provider ids when responding to Tool recovery", async () => {
    const invoke = vi.fn(async () => ({
      accepted: true,
      turnId: "turn_fixture",
      threadRevision: 8,
      decision: "retry",
      resumed: true,
    }));
    const adapter = new TauriRuntimeHostAdapter(
      createBridge({ invoke: invoke as RuntimeNativeBridge["invoke"] }),
    );
    const input = {
      turnId: "turn_fixture",
      callId: "toolu_01HABC123",
      expectedThreadRevision: 7,
      expectedRecoveryRevision: 1,
      decision: "retry" as const,
      operationKey: "recovery_fixture_1",
    };

    await expect(adapter.turnRecoveryRespond(input)).resolves.toMatchObject({
      accepted: true,
      turnId: input.turnId,
      decision: input.decision,
      resumed: true,
    });
    expect(invoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.turnRecoveryRespond, { input });
    await expect(
      adapter.turnRecoveryRespond({ ...input, callId: "toolu/unsafe" }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(invoke).toHaveBeenCalledTimes(1);
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

  /** 局部上下文估算是公开用量，但仅放行合同定义的非负/正整数，避免被 Secret 扫描误杀。 */
  it("accepts validated context-estimate token metrics and rejects malformed values", () => {
    const contextEstimate = {
      certainty: "estimated" as const,
      source: "local" as const,
      usedTokens: 12_345,
      limitTokens: requestProfile.contextWindowTokens,
      measuredAt: unknownUsage.measuredAt,
    };
    const usage = { ...unknownUsage, contextEstimate };
    const event = {
      jsonrpc: "2.0",
      method: "assistant/context-estimated",
      params: {
        serverInstanceId: "srv_fixture",
        eventId: "evt_context_estimated",
        sequence: 6,
        occurredAt: unknownUsage.measuredAt,
        generation: 1,
        workspaceId: "ws_fixture",
        threadId: "thr_fixture",
        turnId: "turn_fixture",
        threadRevision: 4,
        usage,
      },
    };

    expect(parseRuntimeHostEvent(event)).toMatchObject({
      kind: "timeline",
      event: { method: "assistant/context-estimated", params: { usage } },
    });
    for (const invalidEstimate of [
      { ...contextEstimate, usedTokens: -1 },
      { ...contextEstimate, usedTokens: "token=secret" },
      { ...contextEstimate, limitTokens: 0 },
    ]) {
      expect(() =>
        parseRuntimeHostEvent({
          ...event,
          params: {
            ...event.params,
            usage: { ...usage, contextEstimate: invalidEstimate },
          },
        }),
      ).toThrow();
    }
  });

  /** 项目繁忙摘要需要穿过重复的 adapter 规范化；不符合公开业务边界的文本不能靠错误码透传。 */
  it("retains bounded workspace blocker summaries through repeated normalization", () => {
    const message =
      "暂时无法更改项目目录：集成验证（等待补充信息）；修复代码（Plan 已暂停）。请先处理或取消这些任务。";
    const normalized = normalizeRuntimeError({ code: "WORKSPACE_BUSY", message });
    expect(normalized).toMatchObject({ code: "WORKSPACE_BUSY", message, retryable: true });
    expect(normalizeRuntimeError(normalized)).toMatchObject({ message });

    const publicTitle =
      "暂时无法更改项目目录：会话“token /src/README.md 0123456789abcdef0123456789abcdef”正在运行，请结束或取消后重试。";
    expect(normalizeRuntimeError({ code: "WORKSPACE_BUSY", message: publicTitle }).message).toBe(
      publicTitle,
    );

    for (const diagnostic of [
      "C:\\private\\token=secret",
      "暂时无法更改项目目录：诊断\nsecret",
      `暂时无法更改项目目录：${"x".repeat(512)}`,
      `暂时无法更改项目目录：${"目".repeat(170)}`,
    ]) {
      expect(
        normalizeRuntimeError({ code: "WORKSPACE_BUSY", message: diagnostic }).message,
      ).not.toBe(diagnostic);
    }
    expect(normalizeRuntimeError({ code: "RUNTIME_UNAVAILABLE", message }).message).toBe(
      "运行时暂不可用",
    );
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
    expect(normalizeRuntimeError({ code: "TURN_NOT_FOUND" })).toMatchObject({
      code: "TURN_NOT_FOUND",
      message: "运行不存在或已结束",
      retryable: false,
    });
    expect(normalizeRuntimeError({ code: "WORKSPACE_BUSY" })).toMatchObject({
      code: "WORKSPACE_BUSY",
      message: expect.stringContaining("项目"),
      retryable: true,
    });
    expect(normalizeRuntimeError({ code: "WORKSPACE_NOT_FOUND" })).toMatchObject({
      code: "WORKSPACE_NOT_FOUND",
      message: "项目不存在或已被移除",
      retryable: false,
    });
    expect(normalizeRuntimeError({ code: "WORKSPACE_CONFINEMENT" })).toMatchObject({
      code: "WORKSPACE_CONFINEMENT",
      message: expect.stringContaining("目录"),
      retryable: false,
    });
    const malformed = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(
          async (): Promise<unknown> => ({ ...sessionWorkspace, unexpected: true }),
        ) as RuntimeNativeBridge["invoke"],
      }),
    );
    await expect(malformed.activateWorkspace(sessionWorkspace.workspaceId)).rejects.toMatchObject({
      code: "RUNTIME_UNAVAILABLE",
    });
    const unsafeRoot = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(
          async (): Promise<unknown> => ({ ...sessionWorkspace, rootPath: "C:\\private\n" }),
        ) as RuntimeNativeBridge["invoke"],
      }),
    );
    await expect(unsafeRoot.activateWorkspace(sessionWorkspace.workspaceId)).rejects.toMatchObject({
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

  it("activates only the requested Java workspace identity without sending a root path", async () => {
    const first = { ...sessionWorkspace, workspaceId: "ws_runtime_a" };
    const second = {
      ...sessionWorkspace,
      workspaceId: "ws_runtime_b",
      rootPath: "C:\\data\\ja\\workspaces\\thr_runtime_b",
    };
    const firstInvoke = vi.fn(
      async (command: string): Promise<unknown> =>
        command === JA_RUNTIME_COMMANDS.workspaceActivate ? first : readyStatus,
    );
    const firstAdapter = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: firstInvoke as RuntimeNativeBridge["invoke"],
      }),
    );
    const secondAdapter = new TauriRuntimeHostAdapter(
      createBridge({
        invoke: vi.fn(
          async (command: string): Promise<unknown> =>
            command === JA_RUNTIME_COMMANDS.workspaceActivate ? second : readyStatus,
        ) as RuntimeNativeBridge["invoke"],
      }),
    );

    await expect(firstAdapter.activateWorkspace(first.workspaceId)).resolves.toEqual(first);
    expect(firstInvoke).toHaveBeenCalledWith(JA_RUNTIME_COMMANDS.workspaceActivate, {
      input: { workspaceId: first.workspaceId },
    });
    await expect(secondAdapter.activateWorkspace(second.workspaceId)).resolves.toEqual(second);
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
            scope: "global",
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
