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
import type { TimelineEvent } from "@/features/conversation/domain/timelineContracts";

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

  /** terminal 先保留可见回答，再只读取一次权威历史补齐最终轮摘要，避免丢摘要或刷新循环。 */
  it("terminal 保留 Final 并通过一次快照补齐最终轮公开摘要", async () => {
    const existing = thread("thr_terminal_refresh");
    let releaseTerminalSnapshot!: () => void;
    const terminalSnapshotGate = new Promise<void>((resolve) => {
      releaseTerminalSnapshot = resolve;
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce({
        threadId: existing.threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      })
      .mockImplementation(async ({ threadId }) => {
        await terminalSnapshotGate;
        return {
          threadId,
          revision: 2,
          turns: [
            {
              turnId: "turn_terminal",
              status: "completed" as const,
              requestedAt: "2026-08-30T12:00:00Z",
              updatedAt: "2026-08-30T12:00:02Z",
              completedAt: "2026-08-30T12:00:02Z",
              errorCode: null,
              changeSet: {
                state: "complete" as const,
                incompleteReasons: [],
                files: [
                  {
                    path: "src/main.ts",
                    status: "modified" as const,
                    additions: 3,
                    deletions: 1,
                    binary: false,
                    truncated: false,
                  },
                ],
                stats: {
                  files: 1,
                  additions: 3,
                  deletions: 1,
                  binaryFiles: 0,
                  truncated: false,
                },
                artifactId: "artifact_terminal_diff",
              },
            },
          ],
          items: [
            {
              itemId: "item_terminal_reasoning",
              turnId: "turn_terminal",
              kind: "reasoning_summary" as const,
              modelRound: 3,
              createdAt: "2026-08-30T12:00:01Z",
              text: "两个工具均已完成，现在整理结果。",
            },
            {
              itemId: "item_terminal_final",
              turnId: "turn_terminal",
              kind: "final_answer" as const,
              createdAt: "2026-08-30T12:00:02Z",
              text: "权威最终答复",
            },
          ],
          inputQueue: null,
          contextUsage: null,
          taskActivities: [],
          goalActivities: [],
          nextCursor: null,
        };
      });
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
    expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBe("terminal_snapshot");
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
    expect(threadRead).toHaveBeenCalledTimes(2);

    await act(async () => {
      releaseTerminalSnapshot();
      await terminalSnapshotGate;
    });
    await waitFor(() =>
      expect(useTimelineStore.getState().turns["turn_terminal"]?.changeSet).toMatchObject({
        state: "complete",
        artifactId: "artifact_terminal_diff",
        stats: { files: 1, additions: 3, deletions: 1 },
      }),
    );
    expect(useTimelineStore.getState().items["item_terminal_final"]?.text).toBe("权威最终答复");
    expect(useTimelineStore.getState().items["item_terminal_reasoning"]?.text).toBe(
      "两个工具均已完成，现在整理结果。",
    );
    expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeUndefined();
    await act(async () => Promise.resolve());
    expect(threadRead).toHaveBeenCalledTimes(2);
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
