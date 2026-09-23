// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { WorkspaceProjection } from "@/features/workspace";
import { useTimelineStore } from "@/features/conversation/application/timelineStore";
import type {
  ConversationHistoryPort,
  ConversationRuntimeState,
  ConversationThread,
} from "@/features/conversation/application/ports";
import { useConversationController } from "@/features/conversation/application/useConversationController";
import type {
  TimelineEvent,
  TimelineSnapshot,
} from "@/features/conversation/domain/timelineContracts";

const WORKSPACE: WorkspaceProjection = {
  kind: "project",
  workspaceId: "ws_project",
  rootPath: "C:\\demo",
  displayName: "demo",
  trust: "trusted",
};

const MODEL_SELECTION = {
  providerId: "provider_1",
  modelId: "model_1",
  reasoningLevel: "medium" as const,
  collaborationMode: "default" as const,
};

type HistoryAdmissionStatus = Extract<
  ConversationRuntimeState["status"],
  "starting" | "ready" | "busy"
>;

/** 构造会话目录项，显式保留 workspace identity 以验证跨域污染会被拒绝。 */
function thread(threadId: string): ConversationThread {
  return {
    threadId,
    workspaceId: WORKSPACE.workspaceId,
    activeGoalId: null,
    preferences: {
      ...MODEL_SELECTION,
      accessMode: "approval_required",
      titleSource: "placeholder",
    },
    title: "新对话",
    status: "active",
    pinned: false,
    latestTurnStatus: null,
    latestTurnSeen: true,
    revision: 0,
    createdAt: "2026-08-28T00:00:00Z",
    updatedAt: "2026-08-28T00:00:00Z",
  };
}

/** 为不关注目录变更的用例提供完整窄端口，避免旧 fixture 隐式缺少生产能力。 */
function historyExtensions(): Pick<
  ConversationHistoryPort,
  | "threadSearch"
  | "threadRename"
  | "threadPreferencesUpdate"
  | "threadPin"
  | "threadSeen"
  | "threadArchive"
  | "threadRestore"
> {
  return {
    threadSearch: vi.fn(async () => ({ items: [], nextCursor: null })),
    threadRename: vi.fn(async () => thread("thr_unused")),
    threadPreferencesUpdate: vi.fn(async () => thread("thr_unused")),
    threadPin: vi.fn(async ({ threadId, pinned }) => ({ ...thread(threadId), pinned })),
    threadSeen: vi.fn(async ({ threadId, expectedThreadRevision }) => ({
      ...thread(threadId),
      latestTurnSeen: true,
      revision: expectedThreadRevision + 1,
    })),
    threadArchive: vi.fn(async ({ threadId }) => ({
      ...thread(threadId),
      status: "archived" as const,
    })),
    threadRestore: vi.fn(async ({ threadId }) => thread(threadId)),
  };
}

/** 构造已由协议层校验的标题事件，测试只改变 admission fence 所需字段。 */
function metadataEvent(
  overrides: Partial<Extract<TimelineEvent, { method: "thread/metadata-changed" }>["params"]> = {},
): Extract<TimelineEvent, { method: "thread/metadata-changed" }> {
  return {
    jsonrpc: "2.0",
    method: "thread/metadata-changed",
    params: {
      serverInstanceId: "srv_1",
      eventId: "evt_title_1",
      sequence: 1,
      occurredAt: "2026-08-30T12:00:00Z",
      generation: 1,
      workspaceId: WORKSPACE.workspaceId,
      threadId: "thr_metadata",
      revision: 2,
      title: "自动标题",
      titleSource: "auto",
      ...overrides,
    },
  };
}

/** 构造不含消息条目的完整 snapshot，测试只需关注 Thread identity 与 revision。 */
function emptySnapshot(threadId: string, revision = 0) {
  return {
    threadId,
    revision,
    turns: [],
    items: [],
    inputQueue: null,
    contextUsage: null,
    liveStream: null,
    taskActivities: [],
    goalActivities: [],
    nextCursor: null,
  };
}

/** 构造带运行 Turn 和持久正文的快照，用于验证热缓存保留真实 Timeline 而非仅保留目录项。 */
function contentSnapshot(
  threadId: string,
  status: "running" | "completed" = "running",
  revision = 0,
): TimelineSnapshot {
  const turnId = `${threadId}:turn`;
  const occurredAt = "2026-08-28T00:00:00Z";
  return {
    threadId,
    revision,
    turns: [
      {
        turnId,
        status,
        requestedAt: occurredAt,
        updatedAt: occurredAt,
        completedAt: status === "completed" ? occurredAt : null,
        errorCode: null,
        changeSet: null,
      },
    ],
    items: [
      {
        itemId: `${threadId}:item`,
        createdAt: occurredAt,
        turnId,
        kind: "assistant_progress",
        text: "缓存中的历史正文",
        modelRound: 1,
      },
    ],
    inputQueue: null,
    contextUsage: null,
    liveStream: null,
    taskActivities: [],
    goalActivities: [],
    nextCursor: null,
  };
}

/** 构造可在非当前 workspace 接收的状态事件，验证全局 Event Stream 不被当前选择过滤。 */
function stateChangedEvent(input: {
  workspaceId: string;
  threadId: string;
  turnId: string;
  threadRevision: number;
  from: "queued" | "running";
  to: "running" | "waiting_approval" | "suspended" | "completed";
}): Extract<TimelineEvent, { method: "turn/state-changed" }> {
  return {
    jsonrpc: "2.0",
    method: "turn/state-changed",
    params: {
      serverInstanceId: "srv_1",
      eventId: `evt_${input.threadId}_${input.threadRevision}`,
      sequence: input.threadRevision,
      generation: 1,
      workspaceId: input.workspaceId,
      threadId: input.threadId,
      turnId: input.turnId,
      threadRevision: input.threadRevision,
      occurredAt: "2026-08-28T00:00:01Z",
      from: input.from,
      to: input.to,
    },
  };
}

/** 构造独立 workspace 目录项，保持每个 fixture 的 owner 与缓存 scope 一一对应。 */
function workspaceThread(
  workspaceId: string,
  threadId: string,
  title = threadId,
): ConversationThread {
  return {
    ...thread(threadId),
    workspaceId,
    title,
  };
}

describe("useConversationController", () => {
  afterEach(() => {
    cleanup();
    useTimelineStore.getState().reset();
  });

  it("空 workspace 历史通过 cwd/title/model 创建 Thread 并恢复 snapshot", async () => {
    const created = thread("thr_created");
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [], nextCursor: null })),
      threadCreate: vi.fn(async () => created),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const runtimeState = { status: "ready" as const, generation: 1, serverInstanceId: "srv_1" };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState,
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_created"));
    expect(history.threadCreate).toHaveBeenCalledWith({
      cwd: "C:\\demo",
      title: "新对话",
      providerId: "provider_1",
      modelId: "model_1",
      reasoningLevel: "medium",
      accessMode: "approval_required",
      collaborationMode: "default",
    });
    expect(useTimelineStore.getState().threads["thr_created"]?.workspaceId).toBe("ws_project");
  });

  /** 长历史的 thread/read 需要消费完整 keyset 页面，不能把首个 nextCursor 当成恢复失败。 */
  it("自动恢复会合并分页 Thread 快照并清除 resync 错误", async () => {
    const existing = thread("thr_paginated_recovery");
    const first = contentSnapshot(existing.threadId, "running", 4);
    const firstItem = first.items[0];
    if (firstItem === undefined) throw new Error("test snapshot item missing");
    const secondItem = {
      ...firstItem,
      itemId: "item_paginated_second",
      text: "分页后的历史正文",
    };
    const threadRead = vi.fn<ConversationHistoryPort["threadRead"]>();
    threadRead
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce({ ...first, items: [firstItem], nextCursor: "cursor_page_2" })
      .mockResolvedValueOnce({ ...first, items: [secondItem], nextCursor: null });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    act(() => useTimelineStore.getState().requestThreadResync(existing.threadId));
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(3));
    await waitFor(() => expect(result.current.error).toBeUndefined());

    expect(threadRead).toHaveBeenNthCalledWith(3, {
      threadId: existing.threadId,
      cursor: "cursor_page_2",
    });
    expect(useTimelineStore.getState().items[secondItem.itemId]?.text).toBe("分页后的历史正文");
    expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeUndefined();
  });

  /** 分页提交边界连续变化时只从第一页重取三次，不能把混合 revision 发布成历史正文。 */
  it("分页 revision 变化最多重取三次并保留 resync 意图", async () => {
    const existing = thread("thr_paginated_revision_race");
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(emptySnapshot(existing.threadId, 0))
      .mockResolvedValueOnce({ ...emptySnapshot(existing.threadId, 1), nextCursor: "cursor_1" })
      .mockResolvedValueOnce({ ...emptySnapshot(existing.threadId, 2), nextCursor: null })
      .mockResolvedValueOnce({ ...emptySnapshot(existing.threadId, 3), nextCursor: "cursor_2" })
      .mockResolvedValueOnce({ ...emptySnapshot(existing.threadId, 4), nextCursor: null })
      .mockResolvedValueOnce({ ...emptySnapshot(existing.threadId, 5), nextCursor: "cursor_3" })
      .mockResolvedValueOnce({ ...emptySnapshot(existing.threadId, 6), nextCursor: null });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, unmount } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    vi.useFakeTimers();
    try {
      act(() => useTimelineStore.getState().requestThreadResync(existing.threadId));
      await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(threadRead).toHaveBeenCalledTimes(7);
      expect(threadRead).toHaveBeenNthCalledWith(2, { threadId: existing.threadId });
      expect(threadRead).toHaveBeenNthCalledWith(4, { threadId: existing.threadId });
      expect(threadRead).toHaveBeenNthCalledWith(6, { threadId: existing.threadId });
      expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeDefined();
    } finally {
      unmount();
      vi.useRealTimers();
    }
  });

  /**
   * active snapshot 没有 terminal event 时仍由可见 Thread controller 消费 resync；首次 read
   * 失败后按 retry timer 重试，第二次权威 terminal snapshot 到达即可解除 active projection。
   */
  it("恢复 active Turn 的 read 失败后重试并以 terminal snapshot 解锁", async () => {
    const existing = thread("thr_active_recovery");
    const active = contentSnapshot(existing.threadId, "running", 4);
    const terminal = contentSnapshot(existing.threadId, "completed", 5);
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(active)
      .mockRejectedValueOnce(new Error("temporary read failure"))
      .mockResolvedValueOnce(terminal);
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    expect(useTimelineStore.getState().turns[`${existing.threadId}:turn`]?.status).toBe("running");

    vi.useFakeTimers();
    try {
      act(() => useTimelineStore.getState().requestThreadResync(existing.threadId));
      await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(threadRead).toHaveBeenCalledTimes(2);
      expect(result.current.backgroundError).toContain("会话状态暂时无法自动恢复");

      await act(async () => {
        await vi.advanceTimersByTimeAsync(1_000);
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(threadRead).toHaveBeenCalledTimes(3);
      expect(useTimelineStore.getState().turns[`${existing.threadId}:turn`]?.status).toBe(
        "completed",
      );
      expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeUndefined();
      expect(result.current.backgroundError).toBeUndefined();
    } finally {
      vi.useRealTimers();
    }
  });

  /** 过旧 recovery snapshot 不是成功；迟到读取必须保留 resync 并沿有界退避继续取样。 */
  it("late recovery snapshot 不清除 resync，并在退避后接纳新终态", async () => {
    const existing = thread("thr_late_recovery");
    const active = contentSnapshot(existing.threadId, "running", 4);
    const terminal = contentSnapshot(existing.threadId, "completed", 6);
    let releaseLate!: (snapshot: TimelineSnapshot) => void;
    const lateRead = new Promise<TimelineSnapshot>((resolve) => {
      releaseLate = resolve;
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(active)
      .mockImplementationOnce(async () => lateRead)
      .mockResolvedValueOnce(terminal);
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    vi.useFakeTimers();
    try {
      act(() => useTimelineStore.getState().requestThreadResync(existing.threadId));
      await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(threadRead).toHaveBeenCalledTimes(2);
      let deltaOutcome: ReturnType<typeof useTimelineStore.getState>["lastOutcome"];
      let stateChangeOutcome: ReturnType<typeof useTimelineStore.getState>["lastOutcome"];
      act(() => {
        deltaOutcome = useTimelineStore.getState().applyHostEvent({
          kind: "timeline",
          event: {
            jsonrpc: "2.0",
            method: "assistant/text-delta",
            params: {
              serverInstanceId: "srv_1",
              eventId: "evt_late_recovery_delta",
              sequence: 5,
              occurredAt: "2026-09-01T00:00:01Z",
              generation: 1,
              workspaceId: WORKSPACE.workspaceId,
              threadId: existing.threadId,
              turnId: `${existing.threadId}:turn`,
              threadRevision: 5,
              streamSeq: 1,
              text: "仍在输出",
            },
          },
        });
        stateChangeOutcome = useTimelineStore.getState().applyHostEvent({
          kind: "timeline",
          event: stateChangedEvent({
            workspaceId: WORKSPACE.workspaceId,
            threadId: existing.threadId,
            turnId: `${existing.threadId}:turn`,
            threadRevision: 5,
            from: "running",
            to: "waiting_approval",
          }),
        });
        releaseLate(active);
      });
      expect(deltaOutcome).toBe("applied");
      expect(stateChangeOutcome).toBe("applied");
      await act(async () => {
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeDefined();
      expect(threadRead).toHaveBeenCalledTimes(2);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(499);
      });
      expect(threadRead).toHaveBeenCalledTimes(2);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1);
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(threadRead).toHaveBeenCalledTimes(3);
      expect(useTimelineStore.getState().turns[`${existing.threadId}:turn`]?.status).toBe(
        "completed",
      );
      expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeUndefined();
    } finally {
      vi.useRealTimers();
    }
  });

  /** 健康对账只保留当前 Thread 的请求；切换后旧 read 的 finally 不能占住切回后的 single-flight。 */
  it("健康 resync 切换 Thread 后丢弃迟到 snapshot，并允许切回重新读取", async () => {
    const first = thread("thr_health_first");
    const second = thread("thr_health_second");
    let releaseHealth!: (snapshot: TimelineSnapshot) => void;
    const healthRead = new Promise<TimelineSnapshot>((resolve) => {
      releaseHealth = resolve;
    });
    let releaseNextHealth!: (snapshot: TimelineSnapshot) => void;
    const nextHealthRead = new Promise<TimelineSnapshot>((resolve) => {
      releaseNextHealth = resolve;
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(emptySnapshot(first.threadId, 1))
      .mockImplementationOnce(async () => healthRead)
      .mockResolvedValueOnce(emptySnapshot(first.threadId, 3))
      .mockImplementationOnce(async () => nextHealthRead);
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [first, second], nextCursor: null })),
      threadCreate: vi.fn(async () => first),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(first.threadId));
    act(() => {
      expect(
        useTimelineStore
          .getState()
          .applySnapshot(emptySnapshot(second.threadId), WORKSPACE.workspaceId),
      ).toBe("applied");
      useTimelineStore.getState().requestThreadResync(first.threadId);
    });
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(2));

    await act(async () => result.current.select(second.threadId));
    await act(async () => result.current.select(first.threadId));
    expect(threadRead).toHaveBeenCalledTimes(3);
    act(() => useTimelineStore.getState().requestThreadResync(first.threadId));
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(4));

    // 旧 health read 此时晚到；它不能清理 call 4 的 in-flight 标记或再开 call 5。
    releaseHealth(contentSnapshot(first.threadId, "running", 2));
    await act(async () => {
      await healthRead;
      await Promise.resolve();
    });
    act(() => useTimelineStore.getState().requestThreadResync(first.threadId));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(threadRead).toHaveBeenCalledTimes(4);
    expect(useTimelineStore.getState().turns[`${first.threadId}:turn`]).toBeUndefined();

    releaseNextHealth(emptySnapshot(first.threadId, 4));
    await act(async () => {
      await nextHealthRead;
      await Promise.resolve();
    });
    expect(result.current.currentThreadId).toBe(first.threadId);
    expect(useTimelineStore.getState().resyncRequired[first.threadId]).toBeUndefined();
  });

  /** generation/server 变化会取消 health read；旧 snapshot 不能污染新 runtime 的同名 Thread。 */
  it("健康 resync 遇到 runtime 代际切换会清理在途请求", async () => {
    const existing = thread("thr_health_generation");
    let releaseHealth!: (snapshot: TimelineSnapshot) => void;
    const healthRead = new Promise<TimelineSnapshot>((resolve) => {
      releaseHealth = resolve;
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(emptySnapshot(existing.threadId, 1))
      .mockImplementationOnce(async () => healthRead)
      .mockResolvedValueOnce(emptySnapshot(existing.threadId, 0));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ generation }: { generation: number }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: generation,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation, serverInstanceId: `srv_${generation}` },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { generation: 1 } },
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    act(() => useTimelineStore.getState().requestThreadResync(existing.threadId));
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(2));

    rerender({ generation: 2 });
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(3));
    releaseHealth(contentSnapshot(existing.threadId, "running", 2));
    await act(async () => {
      await healthRead;
      await Promise.resolve();
    });

    expect(result.current.currentThreadId).toBe(existing.threadId);
    expect(useTimelineStore.getState().turns[`${existing.threadId}:turn`]).toBeUndefined();
    expect(useTimelineStore.getState().handshake.generation).toBe(2);
  });

  /** 后台失败提示与当前 scope 绑定；切到其它会话或成功前台读取后都不能继续显示旧错误。 */
  it("后台恢复错误不会从旧 Thread 污染新 Thread，并在重新读取成功后清除", async () => {
    const first = thread("thr_background_error_first");
    const second = thread("thr_background_error_second");
    let rejectHealth!: (reason?: unknown) => void;
    const healthRead = new Promise<TimelineSnapshot>((_resolve, reject) => {
      rejectHealth = reject;
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(emptySnapshot(first.threadId, 1))
      .mockImplementationOnce(async () => healthRead)
      .mockResolvedValueOnce(emptySnapshot(first.threadId, 2));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [first, second], nextCursor: null })),
      threadCreate: vi.fn(async () => first),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(first.threadId));
    act(() => {
      expect(
        useTimelineStore
          .getState()
          .applySnapshot(emptySnapshot(second.threadId), WORKSPACE.workspaceId),
      ).toBe("applied");
      useTimelineStore.getState().requestThreadResync(first.threadId);
    });
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(2));
    await act(async () => {
      rejectHealth(new Error("health read failed"));
      try {
        await healthRead;
      } catch {
        // Controller owns the failure projection; the test only releases the deferred read.
      }
      await Promise.resolve();
    });
    expect(result.current.backgroundError).toContain("会话状态暂时无法自动恢复");

    await act(async () => result.current.select(second.threadId));
    expect(result.current.currentThreadId).toBe(second.threadId);
    expect(result.current.backgroundError).toBeUndefined();

    await act(async () => result.current.select(first.threadId));
    expect(threadRead).toHaveBeenCalledTimes(3);
    expect(result.current.currentThreadId).toBe(first.threadId);
    expect(result.current.backgroundError).toBeUndefined();
  });

  /** 同 Workspace 切换期间，旧 Thread 的在途 read 返回后不能写入 recovered 标记或 Timeline。 */
  it("切换 Thread 后丢弃旧 Thread 的迟到 resync snapshot", async () => {
    const first = thread("thr_resync_first");
    const second = thread("thr_resync_second");
    let releaseLateRead!: (snapshot: TimelineSnapshot) => void;
    const lateRead = new Promise<TimelineSnapshot>((resolve) => {
      releaseLateRead = resolve;
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(emptySnapshot(first.threadId))
      .mockImplementationOnce(async () => lateRead);
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [first, second], nextCursor: null })),
      threadCreate: vi.fn(async () => first),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(first.threadId));
    act(() => {
      useTimelineStore
        .getState()
        .applySnapshot(emptySnapshot(second.threadId), WORKSPACE.workspaceId);
      useTimelineStore.getState().requestThreadResync(first.threadId);
    });
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(2));

    await act(async () => result.current.select(second.threadId));
    releaseLateRead(contentSnapshot(first.threadId, "running", 4));
    await act(async () => {
      await lateRead;
      await Promise.resolve();
    });

    expect(result.current.currentThreadId).toBe(second.threadId);
    expect(useTimelineStore.getState().turns[`${first.threadId}:turn`]).toBeUndefined();
    expect(useTimelineStore.getState().recoveredActiveTurnByThread[first.threadId]).toBeUndefined();
  });

  it("当前会话没有任何 Turn 时重复新建仍复用同一个 Thread", async () => {
    const existing = thread("thr_empty");
    const threadCreate = vi.fn(async () => thread("thr_duplicate"));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate,
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    await act(async () => {
      await Promise.all([result.current.create(), result.current.create()]);
    });

    expect(threadCreate).not.toHaveBeenCalled();
    expect(result.current.currentThreadId).toBe(existing.threadId);
    expect(result.current.threads).toHaveLength(1);
  });

  /** 当前范围切换后不得保留其它 Workspace 的同名 Thread，避免点击“新对话”隐式切回旧范围。 */
  it("切换 workspace 后最近对话只保留当前范围", async () => {
    const generalWorkspace: WorkspaceProjection = {
      kind: "general",
      workspaceId: "ws_general",
      rootPath: "C:\\data\\general",
      displayName: "无项目",
      trust: "trusted",
    };
    const projectThread = thread("thr_project");
    const generalThread = {
      ...thread("thr_general"),
      workspaceId: generalWorkspace.workspaceId,
    };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async ({ workspaceId }) => ({
        items: workspaceId === WORKSPACE.workspaceId ? [projectThread] : [generalThread],
        nextCursor: null,
      })),
      threadCreate: vi.fn(async () => projectThread),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ workspace, revision }: { workspace: WorkspaceProjection; revision: number }) =>
        useConversationController({
          history,
          workspace,
          workspaceRevision: revision,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { workspace: WORKSPACE, revision: 1 } },
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(projectThread.threadId));

    rerender({ workspace: generalWorkspace, revision: 2 });

    await waitFor(() => expect(result.current.currentThreadId).toBe(generalThread.threadId));
    expect(result.current.threads).toEqual([generalThread]);
  });

  /** 热切只替换目录投影；正文和旧 workspace 的实时 Turn 必须留在共享 Timeline 中。 */
  it("项目切换先恢复缓存会话，目录校验在后台完成", async () => {
    const otherWorkspace: WorkspaceProjection = {
      kind: "project",
      workspaceId: "ws_other_project",
      rootPath: "C:\\other",
      displayName: "other",
      trust: "trusted",
    };
    const projectThread = { ...thread("thr_cached_project"), title: "项目会话" };
    const otherThread = {
      ...thread("thr_cached_other"),
      workspaceId: otherWorkspace.workspaceId,
      title: "另一个项目会话",
    };
    let otherListCalls = 0;
    let releaseOtherRefresh!: () => void;
    const otherRefresh = new Promise<void>((resolve) => {
      releaseOtherRefresh = resolve;
    });
    const threadList = vi.fn<ConversationHistoryPort["threadList"]>(async ({ workspaceId }) => {
      if (workspaceId === otherWorkspace.workspaceId) {
        otherListCalls += 1;
        if (otherListCalls === 2) await otherRefresh;
        return { items: [otherThread], nextCursor: null };
      }
      return { items: [projectThread], nextCursor: null };
    });
    const threadRead = vi.fn<ConversationHistoryPort["threadRead"]>(async ({ threadId }) =>
      threadId === projectThread.threadId ? contentSnapshot(threadId) : emptySnapshot(threadId),
    );
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => projectThread),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const runtimeState = { status: "ready" as const, generation: 1, serverInstanceId: "srv_1" };
    const { result, rerender } = renderHook(
      ({ workspace, revision }: { workspace: WorkspaceProjection; revision: number }) =>
        useConversationController({
          history,
          workspace,
          workspaceRevision: revision,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { workspace: WORKSPACE, revision: 1 } },
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(projectThread.threadId));
    const projectTurnId = `${projectThread.threadId}:turn`;
    expect(useTimelineStore.getState().items[`${projectThread.threadId}:item`]?.text).toBe(
      "缓存中的历史正文",
    );
    rerender({ workspace: otherWorkspace, revision: 2 });
    await waitFor(() => expect(result.current.currentThreadId).toBe(otherThread.threadId));
    expect(
      useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: stateChangedEvent({
          workspaceId: WORKSPACE.workspaceId,
          threadId: projectThread.threadId,
          turnId: projectTurnId,
          threadRevision: 1,
          from: "running",
          to: "completed",
        }),
      }),
    ).toBe("applied");
    expect(useTimelineStore.getState().turns[projectTurnId]?.status).toBe("completed");
    rerender({ workspace: WORKSPACE, revision: 3 });
    await waitFor(() => expect(result.current.currentThreadId).toBe(projectThread.threadId));
    expect(threadRead).toHaveBeenCalledTimes(2);
    expect(useTimelineStore.getState().items[`${projectThread.threadId}:item`]?.text).toBe(
      "缓存中的历史正文",
    );
    expect(useTimelineStore.getState().turns[projectTurnId]?.status).toBe("completed");

    rerender({ workspace: otherWorkspace, revision: 4 });
    expect(result.current.currentThreadId).toBe(otherThread.threadId);
    expect(result.current.threads).toEqual([otherThread]);
    expect(threadRead).toHaveBeenCalledTimes(2);

    releaseOtherRefresh();
    await waitFor(() => expect(result.current.busy).toBe(false));
    expect(result.current.currentThreadId).toBe(otherThread.threadId);
  });

  it("旧 workspace 目录响应迟到时不能覆盖当前项目的首帧投影", async () => {
    const otherWorkspace: WorkspaceProjection = {
      kind: "project",
      workspaceId: "ws_race_other",
      rootPath: "C:\\race-other",
      displayName: "race-other",
      trust: "trusted",
    };
    const oldThread = thread("thr_race_old");
    const newThread = {
      ...thread("thr_race_new"),
      workspaceId: otherWorkspace.workspaceId,
    };
    let releaseOldList!: () => void;
    const oldList = new Promise<void>((resolve) => {
      releaseOldList = resolve;
    });
    const threadList = vi.fn<ConversationHistoryPort["threadList"]>(async ({ workspaceId }) => {
      if (workspaceId === WORKSPACE.workspaceId) await oldList;
      return {
        items: workspaceId === WORKSPACE.workspaceId ? [oldThread] : [newThread],
        nextCursor: null,
      };
    });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => newThread),
      threadRead: vi.fn(async ({ threadId }) => emptySnapshot(threadId)),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ workspace, revision }: { workspace: WorkspaceProjection; revision: number }) =>
        useConversationController({
          history,
          workspace,
          workspaceRevision: revision,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { workspace: WORKSPACE, revision: 1 } },
    );

    rerender({ workspace: otherWorkspace, revision: 2 });
    expect(result.current.currentThreadId).toBeUndefined();
    expect(result.current.threads).toEqual([]);
    await waitFor(() => expect(result.current.currentThreadId).toBe(newThread.threadId));
    releaseOldList();
    await oldList;
    await waitFor(() => expect(result.current.currentThreadId).toBe(newThread.threadId));
    expect(result.current.threads).toEqual([newThread]);
  });

  it("目录 revision 前进时不会把旧 timeline 当成已同步快照", async () => {
    const initial = thread("thr_revision_refresh");
    const advanced = { ...initial, revision: 1, title: "更新后的目录" };
    const threadList = vi
      .fn<ConversationHistoryPort["threadList"]>()
      .mockResolvedValueOnce({ items: [initial], nextCursor: null })
      .mockResolvedValueOnce({ items: [advanced], nextCursor: null });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(emptySnapshot(initial.threadId, 0))
      .mockResolvedValueOnce(emptySnapshot(initial.threadId, 1));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => initial),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ revision }: { revision: number }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: revision,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { revision: 1 } },
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(initial.threadId));
    rerender({ revision: 2 });
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(2));
    expect(useTimelineStore.getState().threadRevisionByThread[initial.threadId]).toBe(1);
    expect(result.current.threads[0]).toEqual(advanced);
  });

  /** 权威目录归档或删除当前 Thread 后，选择必须落到有效行或新建的持久 identity。 */
  it("目录移除当前会话时不会重新选中归档 Thread", async () => {
    const existing = thread("thr_removed_current");
    const fallback = thread("thr_removed_fallback");
    const created = thread("thr_removed_created");
    const archived = { ...existing, status: "archived" as const };
    const threadList = vi
      .fn<ConversationHistoryPort["threadList"]>()
      .mockResolvedValueOnce({ items: [existing], nextCursor: null })
      .mockResolvedValueOnce({ items: [archived, fallback], nextCursor: null })
      .mockResolvedValueOnce({ items: [archived], nextCursor: null });
    const threadRead = vi.fn<ConversationHistoryPort["threadRead"]>(async ({ threadId }) =>
      emptySnapshot(threadId),
    );
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => created),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ revision }: { revision: number }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: revision,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { revision: 1 } },
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    rerender({ revision: 2 });
    await waitFor(() => expect(result.current.currentThreadId).toBe(fallback.threadId));
    expect(result.current.threads).toEqual([fallback]);
    rerender({ revision: 3 });
    await waitFor(() => expect(result.current.currentThreadId).toBe(created.threadId));
    expect(result.current.threads).toEqual([created]);
    expect(history.threadCreate).toHaveBeenCalledTimes(1);
    expect(threadRead).toHaveBeenCalledTimes(3);
  });

  it("新的 ready generation 会使 workspace 缓存和旧 timeline 同时失效", async () => {
    const existing = thread("thr_generation_refresh");
    const threadRead = vi.fn<ConversationHistoryPort["threadRead"]>(async ({ threadId }) =>
      emptySnapshot(threadId),
    );
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ generation }: { generation: number }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation, serverInstanceId: `srv_${generation}` },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { generation: 1 } },
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    rerender({ generation: 2 });
    expect(result.current.currentThreadId).toBeUndefined();
    expect(result.current.threads).toEqual([]);
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    expect(threadRead).toHaveBeenCalledTimes(2);
  });

  /** 缓存只保留有限 workspace 目录；淘汰时不能误删仍有运行 Turn 的共享 Timeline。 */
  it("缓存达到上限时淘汰最老普通 Timeline，但保留活跃 Turn", async () => {
    const workspaces = Array.from({ length: 10 }, (_, index) => ({
      ...WORKSPACE,
      workspaceId: `ws_cache_bound_${index}`,
      rootPath: `C:\\cache-bound-${index}`,
      displayName: `cache-${index}`,
    }));
    const threads = workspaces.map((candidate, index) =>
      workspaceThread(candidate.workspaceId, `thr_cache_bound_${index}`),
    );
    const threadByWorkspace = new Map(
      threads.map((candidate) => [candidate.workspaceId, candidate]),
    );
    const threadList = vi.fn<ConversationHistoryPort["threadList"]>(async ({ workspaceId }) => {
      const listed = threadByWorkspace.get(workspaceId);
      if (listed === undefined) throw new Error(`missing fixture for ${workspaceId}`);
      return { items: [listed], nextCursor: null };
    });
    const threadRead = vi.fn<ConversationHistoryPort["threadRead"]>(async ({ threadId }) => {
      const active = threadId === threads[1]?.threadId;
      return active ? contentSnapshot(threadId, "running") : emptySnapshot(threadId);
    });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => threads[0]!),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ index }: { index: number }) =>
        useConversationController({
          history,
          workspace: workspaces[index],
          workspaceRevision: index + 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { index: 0 } },
    );

    for (let index = 0; index < workspaces.length; index += 1) {
      const expectedThread = threads[index];
      if (expectedThread === undefined) throw new Error("cache bound fixture is incomplete");
      await waitFor(() => expect(result.current.currentThreadId).toBe(expectedThread.threadId));
      if (index + 1 < workspaces.length) rerender({ index: index + 1 });
    }

    const state = useTimelineStore.getState();
    expect(state.threads[threads[0]!.threadId]).toBeUndefined();
    expect(state.threads[threads[1]!.threadId]).toBeDefined();
    expect(state.turns[`${threads[1]!.threadId}:turn`]?.status).toBe("running");
    expect(state.threads[threads[9]!.threadId]).toBeDefined();
    expect(threadRead).toHaveBeenCalledTimes(workspaces.length);
  });

  /** 已读确认只在终态 snapshot 已进入当前 Timeline 后发生，并在 CAS 竞争时仅重放一次。 */
  it("marks the visible latest terminal Turn seen with one bounded conflict retry", async () => {
    const existing = {
      ...thread("thr_unseen"),
      latestTurnStatus: "failed" as const,
      latestTurnSeen: false,
      revision: 3,
    };
    const terminalSnapshot = (revision: number) => ({
      threadId: existing.threadId,
      revision,
      turns: [
        {
          turnId: "turn_failed",
          status: "failed" as const,
          requestedAt: "2026-09-01T00:00:00Z",
          updatedAt: "2026-09-01T00:00:01Z",
          completedAt: "2026-09-01T00:00:01Z",
          errorCode: "MODEL_FAILED",
          changeSet: null,
        },
      ],
      items: [
        {
          itemId: "item_failed_summary",
          turnId: "turn_failed",
          kind: "final_answer" as const,
          createdAt: "2026-09-01T00:00:01Z",
          text: "回复失败",
        },
      ],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor: null,
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(terminalSnapshot(3))
      .mockResolvedValueOnce(terminalSnapshot(4));
    const threadSeen = vi
      .fn<ConversationHistoryPort["threadSeen"]>()
      .mockRejectedValueOnce({ code: "CONFLICT" })
      .mockResolvedValueOnce({ ...existing, latestTurnSeen: true, revision: 5 });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadSeen,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    await waitFor(() => expect(threadSeen).toHaveBeenCalledTimes(2));
    expect(threadSeen).toHaveBeenNthCalledWith(1, {
      threadId: existing.threadId,
      expectedThreadRevision: 3,
    });
    expect(threadSeen).toHaveBeenNthCalledWith(2, {
      threadId: existing.threadId,
      expectedThreadRevision: 4,
    });
    await waitFor(() =>
      expect(
        result.current.threads.find((value) => value.threadId === existing.threadId),
      ).toMatchObject({ latestTurnStatus: "failed", latestTurnSeen: true, revision: 5 }),
    );
  });

  /**
   * CAS 重读即使只为取得 revision 也必须消费完整分页；否则应用首个半快照会擦掉第二页正文，
   * 并让 ChatTimeline 的 turn + exchangeOrdinal 行身份在一次正常重试中发生漂移。
   */
  it("CAS 冲突重读使用完整同 revision 快照而不擦除分页正文", async () => {
    const existing = {
      ...thread("thr_unseen_paginated_reread"),
      latestTurnStatus: "failed" as const,
      latestTurnSeen: false,
      revision: 3,
    };
    const firstItem: TimelineSnapshot["items"][number] = {
      itemId: "item_failed_summary",
      turnId: "turn_failed",
      kind: "final_answer",
      createdAt: "2026-09-01T00:00:01Z",
      text: "完整正文前半段",
    };
    const secondItem: TimelineSnapshot["items"][number] = {
      itemId: "item_failed_details",
      turnId: "turn_failed",
      kind: "assistant_progress",
      createdAt: "2026-09-01T00:00:02Z",
      text: "完整正文后半段",
      modelRound: 1,
    };
    const snapshot = (
      revision: number,
      items: TimelineSnapshot["items"],
      nextCursor: string | null,
    ): TimelineSnapshot => ({
      threadId: existing.threadId,
      revision,
      turns: [
        {
          turnId: "turn_failed",
          status: "failed",
          requestedAt: "2026-09-01T00:00:00Z",
          updatedAt: "2026-09-01T00:00:02Z",
          completedAt: "2026-09-01T00:00:02Z",
          errorCode: "MODEL_FAILED",
          changeSet: null,
        },
      ],
      items,
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor,
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce(snapshot(3, [firstItem, secondItem], null))
      .mockResolvedValueOnce(snapshot(4, [firstItem], "cas_page_2"))
      .mockResolvedValueOnce(snapshot(4, [secondItem], null));
    const threadSeen = vi
      .fn<ConversationHistoryPort["threadSeen"]>()
      .mockRejectedValueOnce({ code: "CONFLICT" })
      .mockResolvedValueOnce({ ...existing, latestTurnSeen: true, revision: 5 });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadSeen,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    await waitFor(() => expect(threadSeen).toHaveBeenCalledTimes(2));

    expect(threadRead).toHaveBeenNthCalledWith(2, { threadId: existing.threadId });
    expect(threadRead).toHaveBeenNthCalledWith(3, {
      threadId: existing.threadId,
      cursor: "cas_page_2",
    });
    expect(useTimelineStore.getState().items[firstItem.itemId]?.text).toBe("完整正文前半段");
    expect(useTimelineStore.getState().items[secondItem.itemId]?.text).toBe("完整正文后半段");
    expect(threadSeen).toHaveBeenNthCalledWith(2, {
      threadId: existing.threadId,
      expectedThreadRevision: 4,
    });
  });

  /** 新一轮与上一轮同为 completed 时，Timeline revision 仍能触发一次新的已读确认。 */
  it("上一轮已读后新终态仍按 Timeline revision 确认未读", async () => {
    const existing = {
      ...thread("thr_seen_then_new_terminal"),
      latestTurnStatus: "completed" as const,
      latestTurnSeen: true,
      revision: 2,
    };
    const threadSeen = vi.fn(async () => ({ ...existing, latestTurnSeen: true, revision: 5 }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async () => contentSnapshot(existing.threadId, "completed", 2)),
      threadSeen,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    expect(threadSeen).not.toHaveBeenCalled();

    let acceptedOutcome: ReturnType<typeof useTimelineStore.getState>["lastOutcome"];
    let eventOutcome: ReturnType<typeof useTimelineStore.getState>["lastOutcome"];
    act(() => {
      acceptedOutcome = useTimelineStore.getState().applyTurnAccepted({
        threadId: existing.threadId,
        turnId: "turn_new_terminal",
        threadRevision: 3,
        submittedText: "新一轮",
        submittedAt: "2026-09-01T00:00:00Z",
      });
      eventOutcome = useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: stateChangedEvent({
          workspaceId: WORKSPACE.workspaceId,
          threadId: existing.threadId,
          turnId: "turn_new_terminal",
          threadRevision: 4,
          from: "queued",
          to: "completed",
        }),
      });
    });
    expect(acceptedOutcome).toBe("applied");
    expect(eventOutcome).toBe("applied");
    await waitFor(() => expect(threadSeen).toHaveBeenCalledOnce());
    expect(threadSeen).toHaveBeenCalledWith({
      threadId: existing.threadId,
      expectedThreadRevision: 4,
    });
  });

  /** seen 失败必须释放精确终态去重键；提醒保持未读，切离再打开才能发起下一次有界确认。 */
  it("retries the unread confirmation after reopening when the first seen mutation fails", async () => {
    const existing = {
      ...thread("thr_unseen_failure"),
      latestTurnStatus: "completed" as const,
      latestTurnSeen: false,
      revision: 2,
    };
    const neutral = thread("thr_seen_retry_neutral");
    const threadSeen = vi
      .fn<ConversationHistoryPort["threadSeen"]>()
      .mockRejectedValueOnce({ code: "RUNTIME_UNAVAILABLE" })
      .mockResolvedValueOnce({ ...existing, latestTurnSeen: true, revision: 3 });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing, neutral], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 2,
        turns:
          threadId === existing.threadId
            ? [
                {
                  turnId: "turn_completed",
                  status: "completed" as const,
                  requestedAt: "2026-09-01T00:00:00Z",
                  updatedAt: "2026-09-01T00:00:01Z",
                  completedAt: "2026-09-01T00:00:01Z",
                  errorCode: null,
                  changeSet: null,
                },
              ]
            : [],
        items:
          threadId === existing.threadId
            ? [
                {
                  itemId: "item_completed",
                  turnId: "turn_completed",
                  kind: "final_answer" as const,
                  createdAt: "2026-09-01T00:00:01Z",
                  text: "完成",
                },
              ]
            : [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadSeen,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.error).toContain("未读状态暂时无法同步"));
    expect(threadSeen).toHaveBeenCalledOnce();
    expect(result.current.threads[0]).toMatchObject({ latestTurnSeen: false });

    await act(async () => {
      await result.current.select(neutral.threadId);
    });
    await waitFor(() => expect(result.current.currentThreadId).toBe(neutral.threadId));
    await act(async () => {
      await result.current.select(existing.threadId);
    });

    await waitFor(() => expect(threadSeen).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(
        result.current.threads.find((value) => value.threadId === existing.threadId),
      ).toMatchObject({ latestTurnSeen: true, revision: 3 }),
    );
  });

  /** 只有已加载 Timeline 的 latest Turn 转换可覆盖目录；未加载行继续保留服务端权威状态。 */
  it("preserves unselected server Turn status while the selected Timeline changes", async () => {
    const selected = { ...thread("thr_selected"), latestTurnStatus: null };
    const unselected = {
      ...thread("thr_unselected"),
      latestTurnStatus: "failed" as const,
      latestTurnSeen: false,
    };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [selected, unselected], nextCursor: null })),
      threadCreate: vi.fn(async () => selected),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(selected.threadId));
    expect(
      result.current.threads.find((value) => value.threadId === unselected.threadId)
        ?.latestTurnStatus,
    ).toBe("failed");
    expect(history.threadSeen).not.toHaveBeenCalled();

    act(() => {
      useTimelineStore.getState().applyTurnAccepted({
        threadId: selected.threadId,
        turnId: "turn_selected",
        threadRevision: 1,
        submittedText: "开始",
        submittedAt: "2026-09-01T00:00:00Z",
      });
    });
    await waitFor(() =>
      expect(
        result.current.threads.find((value) => value.threadId === selected.threadId)
          ?.latestTurnStatus,
      ).toBe("queued"),
    );
    expect(
      result.current.threads.find((value) => value.threadId === unselected.threadId)
        ?.latestTurnStatus,
    ).toBe("failed");
  });

  /** 归档最后一个会话后清空当前 Timeline，避免空会话界面继续显示已归档内容。 */
  it("returns to the empty conversation state after archiving the only thread", async () => {
    const only = { ...thread("thr_only"), latestTurnStatus: "completed" as const };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadArchive: vi.fn(async () => ({ ...only, status: "archived" as const, revision: 1 })),
      threadList: vi.fn(async () => ({ items: [only], nextCursor: null })),
      threadCreate: vi.fn(async () => only),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(only.threadId));

    let undo: Awaited<ReturnType<typeof result.current.archive>> | undefined;
    await act(async () => {
      undo = await result.current.archive(only.threadId);
    });
    expect(undo?.archived.status).toBe("archived");
    expect(result.current.currentThreadId).toBeUndefined();
    expect(result.current.threads).toEqual([]);
    expect(useTimelineStore.getState().threads).toEqual({});
  });

  it("当前会话已有 Turn 后允许新建，并把连续触发收口为一次创建", async () => {
    const existing = thread("thr_started");
    const created = thread("thr_created_after_turn");
    let releaseCreate!: () => void;
    const createGate = new Promise<void>((resolve) => {
      releaseCreate = resolve;
    });
    const threadCreate = vi.fn(async () => {
      await createGate;
      return created;
    });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate,
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: threadId === existing.threadId ? 0 : 1,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    act(() => {
      expect(
        useTimelineStore.getState().applyTurnAccepted({
          threadId: existing.threadId,
          turnId: "turn_started",
          threadRevision: 1,
          submittedText: "开始处理",
          submittedAt: "2026-09-01T00:00:00Z",
        }),
      ).toBe("applied");
    });

    let firstCreate!: Promise<void>;
    let secondCreate!: Promise<void>;
    act(() => {
      firstCreate = result.current.create();
      secondCreate = result.current.create();
    });
    expect(threadCreate).toHaveBeenCalledOnce();
    releaseCreate();
    await act(async () => Promise.all([firstCreate, secondCreate]));

    expect(threadCreate).toHaveBeenCalledOnce();
    expect(result.current.currentThreadId).toBe(created.threadId);
    expect(result.current.threads[0]?.threadId).toBe(created.threadId);
  });

  /** 迟到的目录响应只能覆盖发起时的事实，不能删除其后已经由 create ACK 持久化的新 Thread。 */
  it("保留旧目录请求发出后创建并激活的新 Thread", async () => {
    const existing = thread("thr_existing");
    const discovered = { ...thread("thr_metadata"), revision: 2 };
    const created = thread("thr_created_after_list");
    let releaseStaleList!: () => void;
    const staleListGate = new Promise<void>((resolve) => {
      releaseStaleList = resolve;
    });
    const threadList = vi
      .fn<ConversationHistoryPort["threadList"]>()
      .mockResolvedValueOnce({ items: [existing], nextCursor: null })
      .mockImplementationOnce(async () => {
        await staleListGate;
        return { items: [existing, discovered], nextCursor: null };
      });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => created),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: threadId === created.threadId ? 0 : 1,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    act(() => {
      expect(
        useTimelineStore.getState().applyTurnAccepted({
          threadId: existing.threadId,
          turnId: "turn_started",
          threadRevision: 2,
          submittedText: "开始处理",
          submittedAt: "2026-09-01T00:00:00Z",
        }),
      ).toBe("applied");
    });

    rerender({ event: metadataEvent({ threadId: discovered.threadId, revision: 2 }) });
    await waitFor(() => expect(threadList).toHaveBeenCalledTimes(2));
    await act(async () => result.current.create());
    expect(result.current.currentThreadId).toBe(created.threadId);
    expect(result.current.threads[0]?.threadId).toBe(created.threadId);

    await act(async () => {
      releaseStaleList();
      await staleListGate;
    });
    await waitFor(() =>
      expect(result.current.threads.some((item) => item.threadId === discovered.threadId)).toBe(
        true,
      ),
    );
    expect(result.current.currentThreadId).toBe(created.threadId);
    expect(result.current.threads[0]?.threadId).toBe(created.threadId);
  });

  /** workspace 投影刷新不得抢占 create 的 request token，否则 durable Thread 会存在但永远不进入 UI。 */
  it("创建进行中忽略自动历史恢复并在完成后保持新 Thread 激活", async () => {
    const existing = thread("thr_existing");
    const created = thread("thr_created_during_refresh");
    let releaseCreate!: () => void;
    const createGate = new Promise<void>((resolve) => {
      releaseCreate = resolve;
    });
    const threadList = vi.fn(async () => ({ items: [existing], nextCursor: null }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => {
        await createGate;
        return created;
      }),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ workspaceRevision }: { workspaceRevision: number }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { workspaceRevision: 1 } },
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    act(() => {
      expect(
        useTimelineStore.getState().applyTurnAccepted({
          threadId: existing.threadId,
          turnId: "turn_started",
          threadRevision: 1,
          submittedText: "开始处理",
          submittedAt: "2026-09-01T00:00:00Z",
        }),
      ).toBe("applied");
    });

    let creation!: Promise<void>;
    act(() => {
      creation = result.current.create();
    });
    await waitFor(() => expect(history.threadCreate).toHaveBeenCalledOnce());
    rerender({ workspaceRevision: 2 });
    act(() => {
      expect(
        useTimelineStore.getState().applyTurnAccepted({
          threadId: existing.threadId,
          turnId: "turn_gap",
          threadRevision: 3,
          submittedText: "制造需要后台重读的 revision gap",
          submittedAt: "2026-09-01T00:00:01Z",
        }),
      ).toBe("gap");
    });
    await act(async () => {
      releaseCreate();
      await creation;
    });

    expect(threadList).toHaveBeenCalledOnce();
    expect(history.threadRead).toHaveBeenCalledTimes(2);
    expect(result.current.currentThreadId).toBe(created.threadId);
    expect(result.current.threads[0]?.threadId).toBe(created.threadId);
  });

  it("模型切换用显式 Provider/Model 创建并选中新 Thread，不受旧活动选择闭包影响", async () => {
    const initial = thread("thr_initial");
    const switched = {
      ...thread("thr_switched"),
      threadId: "thr_initial",
      // 成功的 CAS 响应必须携带前进 revision；同 revision 的迟到 list 不能覆盖它。
      revision: 1,
      preferences: {
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "approval_required" as const,
        titleSource: "placeholder" as const,
      },
    };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadPreferencesUpdate: vi.fn(async () => switched),
      threadList: vi.fn(async () => ({ items: [initial], nextCursor: null })),
      threadCreate: vi.fn(async () => switched),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_initial"));

    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "approval_required",
      }),
    );

    expect(history.threadPreferencesUpdate).toHaveBeenCalledWith({
      threadId: "thr_initial",
      providerId: "provider_1",
      modelId: "model_2",
      reasoningLevel: "medium",
      accessMode: "approval_required",
      collaborationMode: "default",
      expectedThreadRevision: 0,
    });
    expect(result.current.currentThreadId).toBe("thr_initial");
    expect(result.current.threads[0]?.preferences?.modelId).toBe("model_2");
  });

  it("连续更新偏好使用前一次 RPC 返回的新 revision，不被滞后的 Timeline revision 覆盖", async () => {
    const initial = thread("thr_preferences");
    const first = {
      ...initial,
      revision: 1,
      preferences: {
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "full_access" as const,
        titleSource: "placeholder" as const,
      },
    };
    const second = {
      ...first,
      revision: 2,
      preferences: { ...first.preferences, accessMode: "approval_required" as const },
    };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadPreferencesUpdate: vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second),
      threadList: vi.fn(async () => ({ items: [initial], nextCursor: null })),
      threadCreate: vi.fn(async () => initial),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_preferences"));

    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "full_access",
      }),
    );
    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "approval_required",
      }),
    );

    expect(history.threadPreferencesUpdate).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedThreadRevision: 1, accessMode: "approval_required" }),
    );
    expect(result.current.threads[0]?.revision).toBe(2);
  });

  it("取消与续发后偏好 CAS 冲突会权威重读并只重放一次", async () => {
    const initial = { ...thread("thr_recover_preferences"), revision: 17 };
    const updated = {
      ...initial,
      revision: 19,
      preferences: {
        ...initial.preferences!,
        accessMode: "approval_required" as const,
      },
    };
    const threadPreferencesUpdate = vi
      .fn<ConversationHistoryPort["threadPreferencesUpdate"]>()
      .mockRejectedValueOnce({ code: "CONFLICT" })
      .mockResolvedValueOnce(updated);
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockImplementationOnce(async ({ threadId }) => ({
        threadId,
        revision: 17,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      }))
      .mockImplementationOnce(async ({ threadId }) => ({
        threadId,
        revision: 18,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadPreferencesUpdate,
      threadList: vi.fn(async () => ({ items: [initial], nextCursor: null })),
      threadCreate: vi.fn(async () => initial),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "full_access",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(initial.threadId));
    threadRead.mockClear();

    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        accessMode: "approval_required",
      }),
    );

    expect(threadRead).toHaveBeenCalledOnce();
    expect(threadPreferencesUpdate).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ expectedThreadRevision: 17 }),
    );
    expect(threadPreferencesUpdate).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedThreadRevision: 18 }),
    );
    expect(threadPreferencesUpdate).toHaveBeenCalledTimes(2);
    expect(useTimelineStore.getState().threadRevisionByThread[initial.threadId]).toBe(18);
    expect(result.current.threads[0]?.revision).toBe(19);
  });

  it("uses the authoritative revision, reports exact Token reduction, and hides the action for active Turns", async () => {
    const existing = thread("thr_existing");
    const threadCompact = vi.fn(async (input: { expectedThreadRevision: number }) => ({
      outcome: "compacted" as const,
      compactionId: "cmp_existing",
      checkpointId: "checkpoint_existing",
      threadRevision: input.expectedThreadRevision + 1,
      inputTokensBefore: 12_000,
      inputTokensAfter: 5_000,
    }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 7,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact,
    };
    const runtimeState = { status: "ready" as const, generation: 1, serverInstanceId: "srv_1" };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState,
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.canCompact).toBe(true));
    await act(async () => result.current.compact());
    expect(threadCompact).toHaveBeenCalledWith({
      threadId: "thr_existing",
      expectedThreadRevision: 7,
    });
    expect(result.current.compaction).toEqual({
      phase: "success",
      message: "上下文已压缩：12,000 → 5,000 Token。",
      retryable: false,
    });

    act(() => {
      useTimelineStore.getState().applyTurnAccepted({
        threadId: "thr_existing",
        turnId: "turn_active",
        threadRevision: 8,
        submittedText: "继续",
        submittedAt: "2026-08-29T00:00:00Z",
      });
    });

    await waitFor(() => expect(result.current.canCompact).toBe(false));
    await act(async () => result.current.compact());
    expect(threadCompact).toHaveBeenCalledTimes(1);
  });

  /** 流式正文只应唤醒 Timeline owner；壳层 controller 不能因每个 delta 重投影导航。 */
  it("does not rerender the shell controller for an assistant text delta", async () => {
    const existing = thread("thr_stream_shell");
    const turnId = `${existing.threadId}:turn`;
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async () => contentSnapshot(existing.threadId, "running", 1)),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    let renderCount = 0;
    const { result } = renderHook(() => {
      renderCount += 1;
      return useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      });
    });

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    await waitFor(() => expect(result.current.threads[0]?.latestTurnStatus).toBe("running"));
    const settledRenderCount = renderCount;

    act(() => {
      expect(
        useTimelineStore.getState().applyHostEvent({
          kind: "timeline",
          event: {
            jsonrpc: "2.0",
            method: "assistant/text-delta",
            params: {
              serverInstanceId: "srv_1",
              eventId: "evt_stream_shell_delta",
              sequence: 2,
              occurredAt: "2026-08-30T12:00:02Z",
              generation: 1,
              workspaceId: WORKSPACE.workspaceId,
              threadId: existing.threadId,
              threadRevision: 1,
              turnId,
              streamSeq: 1,
              text: " 继续",
            },
          },
        }),
      ).toBe("applied");
    });

    expect(renderCount).toBe(settledRenderCount);

    act(() => {
      // metadata revision 变化仍必须留在命令读取边界，不能唤醒应用壳层导航。
      useTimelineStore.getState().recordThreadMetadataRevision(existing.threadId, 2);
    });

    expect(renderCount).toBe(settledRenderCount);
  });

  it("等待 runtime ready 后只恢复一次历史，busy 切换不会重载当前会话", async () => {
    const existing = thread("thr_after_ready");
    const threadList = vi.fn(async () => ({ items: [existing], nextCursor: null }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const initialRuntimeProps: { status: HistoryAdmissionStatus } = {
      status: "starting",
    };
    const { result, rerender } = renderHook(
      ({ status }: { status: "starting" | "ready" | "busy" }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status, generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: initialRuntimeProps },
    );

    expect(threadList).not.toHaveBeenCalled();
    expect(result.current.error).toBeUndefined();

    rerender({ status: "ready" });
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    expect(threadList).toHaveBeenCalledTimes(1);

    rerender({ status: "busy" });
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    expect(threadList).toHaveBeenCalledTimes(1);
  });

  it("重复点击与已加载会话切换直接复用 timeline，不进入读取态", async () => {
    const first = thread("thr_first");
    const second = thread("thr_second");
    const threadRead = vi.fn<ConversationHistoryPort["threadRead"]>(async ({ threadId }) => ({
      threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor: null,
    }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [first, second], nextCursor: null })),
      threadCreate: vi.fn(async () => first),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(first.threadId));
    expect(threadRead).toHaveBeenCalledOnce();
    act(() => {
      expect(
        useTimelineStore.getState().applySnapshot(
          {
            threadId: second.threadId,
            revision: 0,
            turns: [],
            items: [],
            inputQueue: null,
            contextUsage: null,
            liveStream: null,
            taskActivities: [],
            goalActivities: [],
            nextCursor: null,
          },
          WORKSPACE.workspaceId,
        ),
      ).toBe("applied");
    });

    await act(async () => result.current.select(first.threadId));
    expect(result.current.busy).toBe(false);
    expect(threadRead).toHaveBeenCalledOnce();

    await act(async () => result.current.select(second.threadId));
    expect(result.current.currentThreadId).toBe(second.threadId);
    expect(result.current.busy).toBe(false);
    expect(threadRead).toHaveBeenCalledOnce();
  });

  /** terminal 自带完整终态事实；保留可见答复，但不得触发第二次历史读取。 */
  it("terminal 在单个事件内保留 Final、ChangeSet 与终态，不发起第二次读取", async () => {
    const existing = thread("thr_terminal_refresh");
    const threadRead = vi.fn<ConversationHistoryPort["threadRead"]>(async () => ({
      threadId: existing.threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor: null,
    }));
    const threadSeen = vi.fn<ConversationHistoryPort["threadSeen"]>(
      async ({ expectedThreadRevision }) => ({
        ...existing,
        latestTurnStatus: "completed",
        latestTurnSeen: true,
        revision: expectedThreadRevision + 1,
      }),
    );
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadSeen,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));

    act(() => {
      useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/state-changed",
          params: {
            serverInstanceId: "srv_1",
            eventId: "evt_terminal_running",
            sequence: 1,
            generation: 1,
            workspaceId: WORKSPACE.workspaceId,
            threadId: existing.threadId,
            threadRevision: 1,
            turnId: "turn_terminal",
            occurredAt: "2026-08-30T12:00:01Z",
            from: "queued",
            to: "running",
          },
        },
      });
      useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/terminal",
          params: {
            serverInstanceId: "srv_1",
            eventId: "evt_terminal_completed",
            sequence: 2,
            generation: 1,
            workspaceId: WORKSPACE.workspaceId,
            threadId: existing.threadId,
            threadRevision: 2,
            turnId: "turn_terminal",
            occurredAt: "2026-08-30T12:00:02Z",
            state: "completed",
            summary: "完成",
            finalMessage: { messageId: "item_terminal_final", text: "可见最终答复" },
            changeSet: {
              state: "complete",
              incompleteReasons: [],
              files: [
                {
                  path: "src/main.ts",
                  status: "modified",
                  additions: 3,
                  deletions: 1,
                  binary: false,
                  truncated: false,
                },
              ],
              stats: { files: 1, additions: 3, deletions: 1, binaryFiles: 0, truncated: false },
              artifactId: "terminal_event",
            },
          },
        },
      });
    });

    expect(useTimelineStore.getState().items["item_terminal_final"]?.text).toBe("可见最终答复");
    expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeUndefined();
    expect(useTimelineStore.getState().turns["turn_terminal"]?.changeSet).toMatchObject({
      state: "complete",
      artifactId: "terminal_event",
      stats: { files: 1, additions: 3, deletions: 1 },
    });
    await waitFor(() =>
      expect(threadSeen).toHaveBeenCalledWith({
        threadId: existing.threadId,
        expectedThreadRevision: 2,
      }),
    );
    await waitFor(() =>
      expect(result.current.threads[0]).toMatchObject({
        latestTurnStatus: "completed",
        latestTurnSeen: true,
      }),
    );
    expect(threadRead).toHaveBeenCalledTimes(1);
    await act(async () => Promise.resolve());
    expect(threadRead).toHaveBeenCalledTimes(1);
  });

  it("keeps retryable compaction failures stable and redacted", async () => {
    const existing = thread("thr_retry");
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 3,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async () => {
        throw { code: "SUMMARY_FAILURE", detail: "private provider payload" };
      }),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.canCompact).toBe(true));
    await act(async () => result.current.compact());
    expect(result.current.compaction).toEqual({
      phase: "error",
      message: "上下文摘要生成失败，请重试。",
      retryable: true,
    });
    expect(result.current.compaction.message).not.toContain("private");
  });

  /** 三段式标题都走同一事件门，先证明首问短标题不会在前端被静默丢弃。 */
  it("只接纳当前 runtime/workspace 中 revision 前进的临时、自动与人工标题", async () => {
    const existing = { ...thread("thr_metadata"), revision: 1 };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 1,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const runtimeState = { status: "ready" as const, generation: 1, serverInstanceId: "srv_1" };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState,
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_metadata"));

    rerender({
      event: metadataEvent({ title: "首问短标题", titleSource: "placeholder" }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("首问短标题"));
    expect(result.current.threads[0]?.preferences?.titleSource).toBe("placeholder");
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(2);

    rerender({
      event: metadataEvent({
        eventId: "evt_title_2",
        sequence: 2,
        revision: 3,
      }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("自动标题"));
    expect(result.current.threads[0]?.preferences?.titleSource).toBe("auto");
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(3);

    rerender({
      event: metadataEvent({
        eventId: "evt_title_3",
        sequence: 3,
        revision: 4,
        title: "人工标题",
        titleSource: "manual",
      }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("人工标题"));
    expect(result.current.threads[0]?.preferences?.titleSource).toBe("manual");
    expect(result.current.threads[0]?.revision).toBe(4);
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(4);
  });

  it("拒绝陈旧 generation、server、workspace 与未前进 revision 的标题事件", async () => {
    const existing = { ...thread("thr_metadata"), revision: 5, title: "权威标题" };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 5,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("权威标题"));

    for (const event of [
      metadataEvent({ generation: 2, revision: 6 }),
      metadataEvent({ serverInstanceId: "srv_stale", revision: 6 }),
      metadataEvent({ workspaceId: "ws_other", revision: 6 }),
      metadataEvent({ revision: 5 }),
    ]) {
      rerender({ event });
      expect(result.current.threads[0]?.title).toBe("权威标题");
      expect(result.current.threads[0]?.revision).toBe(5);
    }
  });

  /** 迟到目录、seen 与 rename 都只能按 revision 合并；Timeline 新一轮运行事实必须继续可见。 */
  it("迟到 list、seen、rename 不覆盖新 metadata 与 Timeline 运行状态", async () => {
    const existing = {
      ...thread("thr_metadata_interleave"),
      title: "旧标题",
      revision: 1,
    };
    let releaseLateList!: () => void;
    const lateList = new Promise<void>((resolve) => {
      releaseLateList = resolve;
    });
    let releaseRename!: (value: ConversationThread) => void;
    const renameResponse = new Promise<ConversationThread>((resolve) => {
      releaseRename = resolve;
    });
    let releaseSeen!: (value: ConversationThread) => void;
    const seenResponse = new Promise<ConversationThread>((resolve) => {
      releaseSeen = resolve;
    });
    const threadList = vi
      .fn<ConversationHistoryPort["threadList"]>()
      .mockResolvedValueOnce({ items: [existing], nextCursor: null })
      .mockImplementationOnce(async () => {
        await lateList;
        return {
          items: [
            {
              ...existing,
              title: "迟到列表标题",
              revision: 4,
              latestTurnStatus: "completed",
              latestTurnSeen: true,
            },
          ],
          nextCursor: null,
        };
      });
    const threadRename = vi.fn(async () => renameResponse);
    const threadSeen = vi.fn(async () => seenResponse);
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => contentSnapshot(threadId, "running", 1)),
      threadRename,
      threadSeen,
      threadPin: vi.fn(async ({ threadId, pinned }) => ({
        ...existing,
        threadId,
        pinned,
        revision: 6,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    rerender({
      event: metadataEvent({
        threadId: existing.threadId,
        revision: 2,
        title: "新元数据标题",
        titleSource: "auto",
      }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("新元数据标题"));

    let renameRequest!: Promise<void>;
    act(() => {
      renameRequest = result.current.rename(existing.threadId, "人工标题");
    });
    await waitFor(() =>
      expect(threadRename).toHaveBeenCalledWith({
        threadId: existing.threadId,
        title: "人工标题",
        expectedThreadRevision: 2,
      }),
    );

    act(() => {
      expect(
        useTimelineStore.getState().applyHostEvent({
          kind: "timeline",
          event: stateChangedEvent({
            workspaceId: WORKSPACE.workspaceId,
            threadId: existing.threadId,
            turnId: `${existing.threadId}:turn`,
            threadRevision: 3,
            from: "running",
            to: "completed",
          }),
        }),
      ).toBe("applied");
    });
    await waitFor(() =>
      expect(threadSeen).toHaveBeenCalledWith({
        threadId: existing.threadId,
        expectedThreadRevision: 3,
      }),
    );

    rerender({
      event: metadataEvent({
        eventId: "evt_metadata_interleave_4",
        sequence: 4,
        threadId: existing.threadId,
        revision: 4,
        title: "最新元数据标题",
        titleSource: "manual",
      }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("最新元数据标题"));

    act(() => {
      expect(
        useTimelineStore.getState().applyTurnAccepted({
          threadId: existing.threadId,
          turnId: "turn_new_interleave",
          threadRevision: 5,
          submittedText: "新一轮",
          submittedAt: "2026-09-01T00:00:01Z",
        }),
      ).toBe("applied");
      expect(
        useTimelineStore.getState().applyHostEvent({
          kind: "timeline",
          event: stateChangedEvent({
            workspaceId: WORKSPACE.workspaceId,
            threadId: existing.threadId,
            turnId: "turn_new_interleave",
            threadRevision: 6,
            from: "queued",
            to: "running",
          }),
        }),
      ).toBe("applied");
    });
    await waitFor(() => expect(result.current.threads[0]?.latestTurnStatus).toBe("running"));

    let pinRequest!: Promise<void>;
    act(() => {
      pinRequest = result.current.pin(existing.threadId, true);
    });
    await waitFor(() => expect(threadList).toHaveBeenCalledTimes(2));
    releaseLateList();
    releaseRename({
      ...existing,
      title: "迟到重命名",
      revision: 2,
    });
    releaseSeen({
      ...existing,
      title: "迟到已读",
      latestTurnStatus: "completed",
      latestTurnSeen: true,
      revision: 3,
    });
    await act(async () => {
      await Promise.all([renameRequest, pinRequest]);
      await Promise.resolve();
    });

    expect(result.current.threads[0]).toMatchObject({
      title: "最新元数据标题",
      revision: 4,
      latestTurnStatus: "running",
    });
    expect(useTimelineStore.getState().turns["turn_new_interleave"]?.status).toBe("running");
  });

  it("目录未缓存事件目标时回读权威列表，不从 metadata 补造 Thread", async () => {
    const existing = thread("thr_existing");
    const discovered = {
      ...thread("thr_metadata"),
      revision: 2,
      title: "权威人工标题",
      preferences: {
        ...thread("thr_metadata").preferences!,
        titleSource: "manual" as const,
      },
    };
    const threadList = vi
      .fn<ConversationHistoryPort["threadList"]>()
      .mockResolvedValueOnce({ items: [existing], nextCursor: null })
      .mockResolvedValueOnce({ items: [existing, discovered], nextCursor: null });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_existing"));

    rerender({ event: metadataEvent({ title: "不可直接信任" }) });
    await waitFor(() => expect(threadList).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(result.current.threads.find((item) => item.threadId === "thr_metadata")?.title).toBe(
        "权威人工标题",
      ),
    );
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(2);
  });
});
