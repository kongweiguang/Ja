// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useShallow } from "zustand/react/shallow";
import {
  selectApprovalClosedAtForThread,
  selectApprovalDecisionsForThread,
  selectApprovalsForThread,
  selectCommittedItemsForThread,
  selectItemsForThread,
  selectTaskActivitiesForRoot,
  selectTurnsForThread,
  useTimelineStore,
} from "@/features/conversation/application/timelineStore";
import type { ConversationItemContentPage } from "@/features/conversation/application/ports";
import type {
  TimelineEvent,
  TimelineTaskActivityEntry,
} from "@/features/conversation/domain/timelineContracts";

const status = {
  kind: "status" as const,
  status: { status: "ready" as const, generation: 1, serverInstanceId: "srv_store" },
  eventId: "evt_ready",
  occurredAt: "2026-08-18T00:00:00Z",
};

const storeTurn = {
  origin: "native" as const,
  turnId: "turn_store",
  status: "completed" as const,
  requestedAt: "2026-08-18T00:00:00Z",
  updatedAt: "2026-08-18T00:00:01Z",
  completedAt: "2026-08-18T00:00:01Z",
  errorCode: null,
  changeSet: null,
};

function event(
  threadRevision: number,
  from: "queued" | "running",
  to: "running" | "completed",
): TimelineEvent {
  return {
    jsonrpc: "2.0",
    method: "turn/state-changed",
    params: {
      serverInstanceId: "srv_store",
      eventId: `evt_state_${threadRevision}`,
      sequence: threadRevision,
      generation: 1,
      workspaceId: "ws_store",
      threadId: "thr_store",
      turnId: "turn_store",
      threadRevision,
      occurredAt: "2026-08-18T00:00:00Z",
      from,
      to,
    },
  };
}

function prepareStore(): void {
  useTimelineStore.getState().reset();
  expect(useTimelineStore.getState().applyHostEvent(status)).toBe("applied");
  expect(
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_store",
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        nextCursor: null,
      },
      "ws_store",
    ),
  ).toBe("applied");
}

/** 构造只用于清理测试的 Thread 投影，避免把快照协议细节混入淘汰断言。 */
function projectionThread(threadId: string) {
  return {
    threadId,
    workspaceId: "ws_store",
    title: threadId,
    status: "active" as const,
    revision: 0,
  };
}

/** 给多 Thread 选择器测试提供真实 reducer 可接纳的最小终态快照。 */
function snapshotWithTurns(threadId: string, revision: number, turnIds: readonly string[]) {
  return {
    threadId,
    revision,
    turns: turnIds.map((turnId) => ({
      origin: "native" as const,
      turnId,
      status: "completed" as const,
      requestedAt: "2026-08-18T00:00:00Z",
      updatedAt: "2026-08-18T00:00:01Z",
      completedAt: "2026-08-18T00:00:01Z",
      errorCode: null,
      changeSet: null,
    })),
    items: [],
    inputQueue: null,
    contextUsage: null,
    liveStream: null,
    taskActivities: [],
    nextCursor: null,
  };
}

describe("timeline Zustand seam", () => {
  afterEach(cleanup);

  it("hydrates a visible mailbox Item by code-point cursor and projects the body from TimelineStore", async () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(
      store.applySnapshot(
        {
          threadId: "thr_store",
          revision: 1,
          turns: [storeTurn],
          items: [
            {
              origin: "native" as const,
              itemId: "item_mailbox",
              turnId: "turn_store",
              kind: "thread_message",
              createdAt: "2026-08-18T00:00:00Z",
              sourceThreadId: "thr_source",
              sourceTitle: "Side chat",
              content: "甲",
              truncated: true,
              totalCharacters: 3,
            },
          ],
          inputQueue: null,
          contextUsage: null,
          liveStream: null,
          taskActivities: [],
          nextCursor: null,
        },
        "ws_store",
      ),
    ).toBe("applied");

    const readPage = vi
      .fn()
      .mockResolvedValueOnce({
        itemId: "item_mailbox",
        offsetCharacters: 0,
        nextOffsetCharacters: 2,
        totalCharacters: 3,
        truncated: true,
        content: "甲😀",
      })
      .mockResolvedValueOnce({
        itemId: "item_mailbox",
        offsetCharacters: 2,
        nextOffsetCharacters: null,
        totalCharacters: 3,
        truncated: false,
        content: "乙",
      });
    await expect(
      useTimelineStore.getState().hydrateItemContent("thr_store", "item_mailbox", readPage),
    ).resolves.toBe("甲😀乙");
    expect(readPage.mock.calls.map(([input]) => input.offsetCharacters)).toEqual([0, 2]);
    const item = selectItemsForThread("thr_store")(useTimelineStore.getState()).find(
      (candidate) => candidate.itemId === "item_mailbox",
    );
    expect(item?.bodyHydration).toEqual({
      status: "complete",
      text: "甲😀乙",
      offsetCharacters: 3,
      totalCharacters: 3,
    });
  });

  /** 验证真实 Zustand/useShallow 消费在 hydration 快照重复读取时稳定，并随正文或源事实更新。 */
  it("keeps hydrated committed item references stable for the shallow Timeline subscriber", async () => {
    prepareStore();
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_store",
        revision: 1,
        turns: [storeTurn],
        items: [
          {
            origin: "native" as const,
            itemId: "item_mailbox",
            turnId: "turn_store",
            kind: "thread_message",
            createdAt: "2026-08-18T00:00:00Z",
            sourceThreadId: "thr_source",
            sourceTitle: "Side chat",
            content: "甲",
            truncated: true,
            totalCharacters: 2,
          },
        ],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        nextCursor: null,
      },
      "ws_store",
    );

    let renderCount = 0;
    const { result, unmount } = renderHook(() => {
      renderCount += 1;
      return useTimelineStore(
        useShallow((state: ReturnType<typeof useTimelineStore.getState>) =>
          selectCommittedItemsForThread("thr_store")(state),
        ),
      );
    });
    const originalProjection = result.current;

    let releasePage!: (page: ConversationItemContentPage) => void;
    const readPage = vi.fn(
      () => new Promise<ConversationItemContentPage>((resolve) => (releasePage = resolve)),
    );
    let hydration: Promise<string> | undefined;
    await act(async () => {
      hydration = useTimelineStore
        .getState()
        .hydrateItemContent("thr_store", "item_mailbox", readPage);
      await Promise.resolve();
    });
    const loadingProjection = result.current;
    expect(loadingProjection[0]?.bodyHydration?.status).toBe("loading");
    expect(loadingProjection[0]).not.toBe(originalProjection[0]);

    const loadingRenderCount = renderCount;
    act(() => {
      useTimelineStore.setState((state) => ({
        resyncRequestSequenceByThread: {
          ...state.resyncRequestSequenceByThread,
          thr_store: 1,
        },
      }));
    });
    expect(result.current).toBe(loadingProjection);
    expect(renderCount).toBe(loadingRenderCount);

    await act(async () => {
      releasePage({
        itemId: "item_mailbox",
        offsetCharacters: 0,
        nextOffsetCharacters: null,
        totalCharacters: 2,
        truncated: false,
        content: "甲乙",
      });
      await hydration;
    });
    const completedProjection = result.current;
    expect(completedProjection[0]?.bodyHydration).toMatchObject({
      status: "complete",
      text: "甲乙",
    });
    expect(completedProjection[0]).not.toBe(loadingProjection[0]);
    expect(renderCount).toBe(loadingRenderCount + 1);

    const completedRenderCount = renderCount;
    act(() => {
      useTimelineStore.setState((state) => {
        const source = state.items["item_mailbox"];
        return source === undefined
          ? state
          : {
              items: {
                ...state.items,
                item_mailbox: { ...source, createdAt: "2026-08-18T00:00:01Z" },
              },
            };
      });
    });
    expect(result.current[0]).not.toBe(completedProjection[0]);
    expect(result.current[0]?.createdAt).toBe("2026-08-18T00:00:01Z");
    expect(renderCount).toBe(completedRenderCount + 1);
    unmount();
  });

  /** 查看完整回复只写 answer hydration；保持 body 为空并验证 shallow 订阅与源 Item 更新。 */
  it("keeps answer-only committed item references stable for the shallow Timeline subscriber", async () => {
    prepareStore();
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_store",
        revision: 1,
        turns: [storeTurn],
        items: [
          {
            origin: "native" as const,
            itemId: "item_mailbox",
            turnId: "turn_store",
            kind: "thread_message",
            createdAt: "2026-08-18T00:00:00Z",
            sourceThreadId: "thr_source",
            sourceTitle: "Side chat",
            content: "甲",
            truncated: true,
            totalCharacters: 2,
          },
        ],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        nextCursor: null,
      },
      "ws_store",
    );

    let renderCount = 0;
    const { result, unmount } = renderHook(() => {
      renderCount += 1;
      return useTimelineStore(
        useShallow((state: ReturnType<typeof useTimelineStore.getState>) =>
          selectCommittedItemsForThread("thr_store")(state),
        ),
      );
    });
    const originalProjection = result.current;

    let releaseAnswer!: (text: string) => void;
    const readAnswer = () => new Promise<string>((resolve) => (releaseAnswer = resolve));
    let hydration: Promise<string> | undefined;
    await act(async () => {
      hydration = useTimelineStore
        .getState()
        .hydrateAnswerContent("thr_store", "item_mailbox", readAnswer);
      await Promise.resolve();
    });
    const loadingProjection = result.current;
    expect(loadingProjection[0]?.bodyHydration).toBeUndefined();
    expect(loadingProjection[0]?.answerHydration?.status).toBe("loading");
    expect(loadingProjection[0]).not.toBe(originalProjection[0]);

    const loadingRenderCount = renderCount;
    act(() => {
      useTimelineStore.setState((state) => ({
        resyncRequestSequenceByThread: {
          ...state.resyncRequestSequenceByThread,
          thr_store: 1,
        },
      }));
    });
    expect(result.current).toBe(loadingProjection);
    expect(renderCount).toBe(loadingRenderCount);

    await act(async () => {
      releaseAnswer("完整回复");
      await hydration;
    });
    const completedProjection = result.current;
    expect(completedProjection[0]?.bodyHydration).toBeUndefined();
    expect(completedProjection[0]?.answerHydration).toMatchObject({
      status: "complete",
      text: "完整回复",
    });
    expect(completedProjection[0]).not.toBe(loadingProjection[0]);
    expect(renderCount).toBe(loadingRenderCount + 1);

    const completedRenderCount = renderCount;
    act(() => {
      useTimelineStore.setState((state) => {
        const source = state.items["item_mailbox"];
        return source === undefined
          ? state
          : {
              items: {
                ...state.items,
                item_mailbox: { ...source, createdAt: "2026-08-18T00:00:01Z" },
              },
            };
      });
    });
    expect(result.current[0]).not.toBe(completedProjection[0]);
    expect(result.current[0]?.createdAt).toBe("2026-08-18T00:00:01Z");
    expect(result.current[0]?.answerHydration).toBe(completedProjection[0]?.answerHydration);
    expect(result.current[0]?.bodyHydration).toBeUndefined();
    expect(renderCount).toBe(completedRenderCount + 1);
    unmount();
  });

  it("keeps the accepted cursor after a failed page and resumes from that code-point offset", async () => {
    prepareStore();
    const store = useTimelineStore.getState();
    store.applySnapshot(
      {
        threadId: "thr_store",
        revision: 1,
        turns: [storeTurn],
        items: [
          {
            origin: "native" as const,
            itemId: "item_long_user",
            turnId: "turn_store",
            kind: "user_input",
            createdAt: "2026-08-18T00:00:00Z",
            content: [{ type: "text", text: "甲" }],
            attachments: [],
            truncated: true,
            totalCharacters: 3,
          },
        ],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        nextCursor: null,
      },
      "ws_store",
    );
    const failedReader = vi
      .fn()
      .mockResolvedValueOnce({
        itemId: "item_long_user",
        offsetCharacters: 0,
        nextOffsetCharacters: 2,
        totalCharacters: 3,
        truncated: true,
        content: "甲😀",
      })
      .mockRejectedValueOnce(new Error("connection lost"));
    await expect(
      useTimelineStore.getState().hydrateItemContent("thr_store", "item_long_user", failedReader),
    ).rejects.toThrow("connection lost");
    expect(
      useTimelineStore.getState().itemContentByThread["thr_store"]?.["item_long_user"],
    ).toMatchObject({ status: "error", text: "甲😀", offsetCharacters: 2, totalCharacters: 3 });

    const retryReader = vi.fn(async (input: { offsetCharacters: number }) => ({
      itemId: "item_long_user",
      offsetCharacters: input.offsetCharacters,
      nextOffsetCharacters: null,
      totalCharacters: 3,
      truncated: false,
      content: "乙",
    }));
    await expect(
      useTimelineStore.getState().hydrateItemContent("thr_store", "item_long_user", retryReader),
    ).resolves.toBe("甲😀乙");
    expect(retryReader).toHaveBeenCalledWith({
      threadId: "thr_store",
      itemId: "item_long_user",
      offsetCharacters: 2,
      limitCharacters: 65_536,
    });
  });

  it("does not start an item-content read when its Thread is hidden", async () => {
    prepareStore();
    const readPage = vi.fn();
    await expect(
      useTimelineStore
        .getState()
        .hydrateItemContent("thr_store", "item_hidden", readPage, () => false),
    ).rejects.toThrow("no longer visible");
    expect(readPage).not.toHaveBeenCalled();
  });

  it("同代际切换后台实例时清除旧快照与恢复状态", () => {
    prepareStore();
    const token = useTimelineStore.getState().beginRecovery("thr_store", 1, "recovery");
    expect(token).toBeDefined();
    expect(
      useTimelineStore.getState().applyRuntimeStatus({
        status: "ready",
        generation: 1,
        serverInstanceId: "srv_restarted",
      }),
    ).toBe("applied");
    const next = useTimelineStore.getState();
    expect(next.threads).toEqual({});
    expect(next.serverInstanceId).toBe("srv_restarted");
    expect(next.handshake.serverInstanceId).toBe("srv_restarted");
  });

  it("requires the ready runtime projection before a snapshot or event can enter", () => {
    useTimelineStore.getState().reset();
    expect(
      useTimelineStore.getState().applySnapshot(
        {
          threadId: "thr_store",
          revision: 0,
          turns: [],
          items: [],
          inputQueue: null,
          contextUsage: null,
          liveStream: null,
          taskActivities: [],
          nextCursor: null,
        },
        "ws_store",
      ),
    ).toBe("rejected");
    expect(useTimelineStore.getState().runtime).toBeUndefined();
  });

  it("applies a flat snapshot and semantic event through the typed host seam", () => {
    prepareStore();
    expect(
      useTimelineStore
        .getState()
        .applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") }),
    ).toBe("applied");
    expect(useTimelineStore.getState().turns["turn_store"]?.status).toBe("running");
    expect(useTimelineStore.getState().threadRevisionByThread["thr_store"]).toBe(1);
  });

  /** Entity selector 只触达单 Thread index；无序全局对象迭代会令隐藏/无关会话产生线性工作。 */
  it("uses Thread-local entity indexes without enumerating global entity tables", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applySnapshot(snapshotWithTurns("thr_store", 1, ["turn_store"]), "ws_store")).toBe(
      "applied",
    );
    const current = useTimelineStore.getState();
    const turns = new Proxy(current.turns, {
      ownKeys: () => {
        throw new Error("selector enumerated turns for every Thread");
      },
    });
    const approvalsById = new Proxy(current.approvalsById, {
      ownKeys: () => {
        throw new Error("selector enumerated approvals for every Thread");
      },
    });
    const scopedState = { ...current, turns, approvalsById };

    expect(selectTurnsForThread("thr_store")(scopedState).map((turn) => turn.turnId)).toEqual([
      "turn_store",
    ]);
    expect(selectItemsForThread("thr_store")(scopedState)).toEqual([]);
    expect(selectApprovalsForThread("thr_store")(scopedState)).toEqual([]);
    expect(selectApprovalDecisionsForThread("thr_store")(scopedState)).toEqual({});
    expect(selectApprovalClosedAtForThread("thr_store")(scopedState)).toEqual({});
  });

  /** 单 Thread shallow selection 必须对其它 Thread 的快照提交保持引用稳定，避免隐藏会话重渲染。 */
  it("does not commit a Thread selector when another Thread appends Turns", async () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applySnapshot(snapshotWithTurns("thr_store", 1, ["turn_store"]), "ws_store")).toBe(
      "applied",
    );
    expect(
      store.applySnapshot(snapshotWithTurns("thr_other", 0, ["turn_other_1"]), "ws_store"),
    ).toBe("applied");
    const onCommit = vi.fn();
    const { unmount } = renderHook(() => {
      const turns = useTimelineStore(useShallow(selectTurnsForThread("thr_store")));
      useEffect(() => onCommit(turns), [turns]);
      return turns;
    });
    const settledCommits = onCommit.mock.calls.length;

    act(() => {
      expect(
        useTimelineStore
          .getState()
          .applySnapshot(
            snapshotWithTurns("thr_other", 1, ["turn_other_1", "turn_other_2"]),
            "ws_store",
          ),
      ).toBe("applied");
    });

    expect(onCommit).toHaveBeenCalledTimes(settledCommits);
    expect(
      selectTurnsForThread("thr_store")(useTimelineStore.getState()).map((turn) => turn.turnId),
    ).toEqual(["turn_store"]);
    unmount();
  });

  it("重试仅投影一条轻量状态并清除旧草稿，首个新 Delta 关闭状态提示", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") })).toBe(
      "applied",
    );
    const priorDelta: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_retry_old_delta",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: "2026-08-18T00:00:01Z",
        streamSeq: 1,
        text: "失败请求半截正文",
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: priorDelta })).toBe("applied");
    const retryStarted: TimelineEvent = {
      jsonrpc: "2.0",
      method: "turn/retry-started",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_retry_started",
        sequence: 3,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 2,
        occurredAt: "2026-08-18T00:00:02Z",
        attempt: 2,
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: retryStarted })).toBe("applied");
    const duringRetry = selectItemsForThread("thr_store")(useTimelineStore.getState());
    const retryStatuses = duringRetry.filter((item) => item.metadata?.phase === "assistant_retry");
    expect(retryStatuses).toHaveLength(1);
    expect(retryStatuses[0]?.text).toBe("连接中断，正在恢复");
    expect(duringRetry.some((item) => item.text === "失败请求半截正文")).toBe(false);

    const newDelta: TimelineEvent = {
      ...priorDelta,
      params: {
        ...priorDelta.params,
        eventId: "evt_retry_new_delta",
        sequence: 4,
        threadRevision: 2,
        occurredAt: "2026-08-18T00:00:03Z",
        streamSeq: 2,
        text: "新请求正文",
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: newDelta })).toBe("applied");
    const afterFirstDelta = selectItemsForThread("thr_store")(useTimelineStore.getState());
    expect(afterFirstDelta.some((item) => item.metadata?.phase === "assistant_retry")).toBe(false);
    expect(afterFirstDelta.map((item) => item.text).filter(Boolean)).toEqual(["新请求正文"]);
    expect(useTimelineStore.getState().streamSeqByTurn["turn_store"]).toBe(2);
  });

  it("exposes the current root task activity snapshot without materializing task detail", () => {
    prepareStore();
    const entry = {
      activity: {
        activitySequence: 3,
        activityId: "activity_store",
        rootThreadId: "thr_store",
        taskThreadId: "thr_child",
        actorThreadId: "thr_store",
        causalTurnId: "turn_store",
        kind: "progress" as const,
        summary: { text: "正在检查" },
        createdAt: "2026-09-03T08:00:02Z",
      },
      task: {
        taskThreadId: "thr_child",
        parentThreadId: "thr_store",
        rootThreadId: "thr_store",
        originTurnId: "turn_store",
        taskName: "检查合同",
        depth: 1,
        taskKind: "subagent" as const,
        lifecycle: "attached" as const,
        state: "running" as const,
        revision: 2,
        latestActivitySequence: 3,
        unreadCount: 1,
        descendantCount: 0,
        runningDescendantCount: 0,
        needsAttentionCount: 0,
        latestSafeSummary: "正在检查",
        startedAt: "2026-09-03T08:00:00Z",
        completedAt: null,
        updatedAt: "2026-09-03T08:00:02Z",
      },
    };
    expect(
      useTimelineStore.getState().applySnapshot(
        {
          threadId: "thr_store",
          revision: 1,
          turns: [],
          items: [],
          inputQueue: null,
          contextUsage: null,
          liveStream: null,
          taskActivities: [entry],
          nextCursor: null,
        },
        "ws_store",
      ),
    ).toBe("applied");
    expect(selectTaskActivitiesForRoot("thr_store")(useTimelineStore.getState())).toEqual([entry]);
    expect(selectTaskActivitiesForRoot("thr_unknown")(useTimelineStore.getState())).toEqual([]);
  });

  it("恢复快照同时包含历史 queued 与当前 running Turn 时选择 running 对账目标", () => {
    prepareStore();
    expect(
      useTimelineStore.getState().applySnapshot(
        {
          threadId: "thr_store",
          revision: 1,
          turns: [
            {
              origin: "native" as const,
              turnId: "turn_queued_history",
              status: "queued",
              requestedAt: "2026-08-18T00:00:00Z",
              updatedAt: "2026-08-18T00:00:01Z",
              completedAt: null,
              errorCode: null,
              changeSet: null,
            },
            {
              origin: "native" as const,
              turnId: "turn_running_current",
              status: "running",
              requestedAt: "2026-08-18T00:00:02Z",
              updatedAt: "2026-08-18T00:00:03Z",
              completedAt: null,
              errorCode: null,
              changeSet: null,
            },
          ],
          items: [],
          inputQueue: null,
          contextUsage: null,
          liveStream: null,
          taskActivities: [],
          nextCursor: null,
        },
        "ws_store",
      ),
    ).toBe("applied");
    expect(useTimelineStore.getState().recoveredActiveTurnByThread["thr_store"]).toBe(
      "turn_running_current",
    );
  });

  it("uses the turn/start revision as the baseline for the independent event stream", () => {
    prepareStore();
    expect(
      useTimelineStore.getState().applyTurnAccepted({
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        submittedText: "hello",
        submittedAt: "2026-08-18T00:00:00Z",
      }),
    ).toBe("applied");
    expect(
      useTimelineStore
        .getState()
        .applyHostEvent({ kind: "timeline", event: event(2, "queued", "running") }),
    ).toBe("applied");
    expect(useTimelineStore.getState().turns["turn_store"]?.status).toBe("running");
    expect(
      Object.values(useTimelineStore.getState().items).some(
        (item) => item.kind === "user_message" && item.text === "hello",
      ),
    ).toBe(true);
  });

  /** 首轮临时标题与 Turn 准入共享 revision；metadata 先到不能吞掉随后到达的 ACK。 */
  it("projects the first user message when provisional title metadata arrives before the ACK", () => {
    prepareStore();
    useTimelineStore.getState().recordThreadMetadataRevision("thr_store", 1);
    expect(useTimelineStore.getState().threadRevisionByThread["thr_store"]).toBe(1);

    expect(
      useTimelineStore.getState().applyTurnAccepted({
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        submittedText: "首条消息必须立即可见",
        submittedAt: "2026-08-18T00:00:00Z",
      }),
    ).toBe("applied");

    const accepted = useTimelineStore.getState();
    expect(accepted.turns["turn_store"]).toMatchObject({
      threadId: "thr_store",
      status: "queued",
      threadRevision: 1,
    });
    expect(accepted.threads["thr_store"]?.activeTurnId).toBe("turn_store");
    expect(selectItemsForThread("thr_store")(accepted)).toContainEqual(
      expect.objectContaining({
        kind: "user_message",
        status: "completed",
        text: "首条消息必须立即可见",
      }),
    );
    expect(
      accepted.applyHostEvent({ kind: "timeline", event: event(2, "queued", "running") }),
    ).toBe("applied");
  });

  it("projects assistant deltas as visible items before the terminal event", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") })).toBe(
      "applied",
    );
    const delta: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_delta_visible",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: "2026-08-18T00:00:01Z",
        streamSeq: 1,
        text: "终态前可见",
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: delta })).toBe("applied");
    const firstSelection = selectItemsForThread("thr_store")(useTimelineStore.getState());
    const secondSelection = selectItemsForThread("thr_store")(useTimelineStore.getState());
    expect(firstSelection).toContainEqual(
      expect.objectContaining({
        itemId: "draft:turn_store:1",
        kind: "commentary",
        status: "in_progress",
        text: "终态前可见",
      }),
    );
    expect(secondSelection.at(-1)).toBe(firstSelection.at(-1));
  });

  /** 持久压缩步骤到达时不能越过此前草稿，selector 必须按发生时间保留正文、Tool、正文的阅读顺序。 */
  it("interleaves context compaction between surrounding live reply segments", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") })).toBe(
      "applied",
    );
    const reasoning: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/reasoning-summary-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_context_before",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: "2026-08-18T00:00:01Z",
        streamSeq: 1,
        text: "先确认当前上下文。",
      },
    };
    const compactionStarted: TimelineEvent = {
      jsonrpc: "2.0",
      method: "context/compaction-started",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_context_started",
        sequence: 3,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: "2026-08-18T00:00:02Z",
        compactionId: "cmp_store_context",
        trigger: "automatic",
        sourceRevision: 1,
        inputTokensBefore: 12_000,
        inputTokensAfter: null,
        strategyVersion: "ja-context-v1",
      },
    };
    const compacted: TimelineEvent = {
      jsonrpc: "2.0",
      method: "context/compacted",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_context_completed",
        sequence: 4,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 2,
        occurredAt: "2026-08-18T00:00:03Z",
        compactionId: "cmp_store_context",
        checkpointId: "checkpoint_store_context",
        trigger: "automatic",
        sourceRevision: 1,
        inputTokensBefore: 12_000,
        inputTokensAfter: 4_000,
        strategyVersion: "ja-context-v1",
      },
    };
    const commentary: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_context_after",
        sequence: 5,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 2,
        occurredAt: "2026-08-18T00:00:04Z",
        streamSeq: 2,
        text: "压缩后继续回复。",
      },
    };

    for (const timelineEvent of [reasoning, compactionStarted, compacted, commentary]) {
      expect(store.applyHostEvent({ kind: "timeline", event: timelineEvent })).toBe("applied");
    }

    const selected = selectItemsForThread("thr_store")(useTimelineStore.getState());
    expect(selected.map((item) => item.kind)).toEqual(["reasoning", "tool_call", "commentary"]);
    expect(selected[1]).toMatchObject({
      title: "上下文自动压缩",
      metadata: { presentation: { kind: "context", status: "success" } },
    });
  });

  /** Store selector 必须把跨 reasoning/text 的每个 live segment 映射为独立且稳定的时间线 Item。 */
  it("projects interleaved reasoning segments with semantic kinds", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") })).toBe(
      "applied",
    );
    const reasoning = (streamSeq: number, text: string): TimelineEvent => ({
      jsonrpc: "2.0",
      method: "assistant/reasoning-summary-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: `evt_reasoning_${streamSeq}`,
        sequence: 1,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: `2026-08-18T00:00:0${streamSeq}Z`,
        streamSeq,
        text,
      },
    });
    const assistant: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_text_2",
        sequence: 1,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: "2026-08-18T00:00:02Z",
        streamSeq: 2,
        text: "公开内容",
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: reasoning(1, "先想一下") })).toBe(
      "applied",
    );
    expect(store.applyHostEvent({ kind: "timeline", event: assistant })).toBe("applied");
    expect(store.applyHostEvent({ kind: "timeline", event: reasoning(3, "再核对") })).toBe(
      "applied",
    );

    const selected = selectItemsForThread("thr_store")(useTimelineStore.getState());
    expect(selected.map((item) => item.kind)).toEqual(["reasoning", "commentary", "reasoning"]);
    expect(selected.filter((item) => item.kind === "reasoning")).toEqual([
      expect.objectContaining({ itemId: "draft:turn_store:1", text: "先想一下" }),
      expect.objectContaining({ itemId: "draft:turn_store:3", text: "再核对" }),
    ]);
    expect(selected.filter((item) => item.kind === "commentary")).toEqual([
      expect.objectContaining({ itemId: "draft:turn_store:2", text: "公开内容" }),
    ]);
    expect(selectItemsForThread("thr_store")(useTimelineStore.getState())).toEqual(selected);
  });

  it("marks the active thread for authoritative resync after a projection fault", () => {
    prepareStore();
    useTimelineStore
      .getState()
      .applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") });
    expect(
      useTimelineStore
        .getState()
        .applyHostEvent({ kind: "projection_fault", reason: "invalid_native_event" }),
    ).toBe("resync_required");
    expect(useTimelineStore.getState().resyncRequired["thr_store"]).toBe("projection_fault");
  });

  /** 新通知只让当前 Thread 重新读取权威快照；重复投递不能制造额外读取请求。 */
  it("deduplicates projection resync notices while requesting one authoritative snapshot", () => {
    prepareStore();
    const notification = {
      kind: "resync_required" as const,
      event: {
        serverInstanceId: "srv_store",
        eventId: "evt_projection_resync",
        sequence: 66,
        occurredAt: "2026-09-27T00:00:00Z",
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        reasonCode: "projection_failed" as const,
      },
    };
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent(notification)).toBe("resync_required");
    expect(useTimelineStore.getState().resyncRequired["thr_store"]).toBe("projection_fault");
    expect(useTimelineStore.getState().resyncRequestSequenceByThread["thr_store"]).toBe(1);

    expect(useTimelineStore.getState().applyHostEvent(notification)).toBe("duplicate");
    expect(useTimelineStore.getState().resyncRequestSequenceByThread["thr_store"]).toBe(1);
  });

  it("does not reopen a terminal Turn when a late event arrives", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") })).toBe(
      "applied",
    );
    expect(
      store.applyHostEvent({ kind: "timeline", event: event(2, "running", "completed") }),
    ).toBe("applied");
    expect(useTimelineStore.getState().turns["turn_store"]?.status).toBe("completed");
    expect(
      store.applyHostEvent({ kind: "timeline", event: event(3, "running", "completed") }),
    ).toBe("resync_required");
    expect(useTimelineStore.getState().turns["turn_store"]?.status).toBe("completed");
  });

  it("publishes each composite commit once with every atomic child projection already visible", () => {
    prepareStore();
    expect(
      useTimelineStore
        .getState()
        .applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") }),
    ).toBe("applied");
    let notifications = 0;
    const unsubscribe = useTimelineStore.subscribe(() => {
      notifications += 1;
    });
    try {
      const modelStep: TimelineEvent = {
        jsonrpc: "2.0",
        method: "assistant/model-step-committed",
        params: {
          serverInstanceId: "srv_store",
          eventId: "evt_model_step",
          sequence: 2,
          generation: 1,
          workspaceId: "ws_store",
          threadId: "thr_store",
          turnId: "turn_store",
          threadRevision: 2,
          occurredAt: "2026-08-18T00:00:01Z",
          messageId: "item_model_step",
          text: "run tool",
          modelRound: 1,
          usage: {
            modelRequestId: "modelreq_store_1",
            modelRequestOrdinal: 1,
            modelRound: 1,
            purpose: "assistant",
            certainty: "known",
            profile: {
              providerId: "provider_demo",
              modelId: "model_demo",
              api: "openai_responses",
              upstreamModel: "gpt-5.6-sol",
              requestedReasoning: "medium",
              effectiveReasoning: "medium",
              accessMode: "approval_required",
              configGeneration: "cfg_demo",
              promptRevision: "prompt_demo",
              toolCatalogRevision: "tools_demo",
              contextWindowTokens: 128_000,
              maxOutputTokens: 16_000,
            },
            inputTokens: 4,
            outputTokens: 1,
            totalTokens: 5,
            measuredAt: "2026-08-18T00:00:01Z",
          },
          toolCalls: [
            {
              callId: "call_store",
              toolName: "write_file",
              ordinal: 0,
              presentation: {
                kind: "write",
                title: "写入文件",
                status: "running",
                relativePaths: ["src/store.ts"],
                truncated: false,
              },
            },
          ],
        },
      };
      expect(
        useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: modelStep }),
      ).toBe("applied");
      expect(notifications).toBe(1);
      const afterModel = useTimelineStore.getState();
      expect(afterModel.items["item_model_step"]?.metadata?.usageTotalTokens).toBe(5);
      expect(
        Object.values(afterModel.items).find((item) => item.metadata?.callId === "call_store")
          ?.status,
      ).toBe("in_progress");

      const batch: TimelineEvent = {
        jsonrpc: "2.0",
        method: "tool/batch-committed",
        params: {
          serverInstanceId: "srv_store",
          eventId: "evt_tool_batch",
          sequence: 3,
          generation: 1,
          workspaceId: "ws_store",
          threadId: "thr_store",
          turnId: "turn_store",
          threadRevision: 3,
          occurredAt: "2026-08-18T00:00:02Z",
          results: [
            {
              callId: "call_store",
              outcome: "succeeded",
              ordinal: 0,
              presentation: {
                kind: "write",
                title: "写入文件",
                status: "success",
                outputPreview: "written",
                relativePaths: ["src/store.ts"],
                truncated: false,
              },
            },
          ],
        },
      };
      expect(useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: batch })).toBe(
        "applied",
      );
      expect(notifications).toBe(2);
      const afterBatch = useTimelineStore.getState();
      expect(afterBatch.items["item_change_store"]).toBeUndefined();
      expect(afterBatch.items["item_model_step_tool_0"]?.metadata?.presentation).toMatchObject({
        status: "success",
        outputPreview: "written",
      });
    } finally {
      unsubscribe();
    }
  });

  it("prunes owned inactive projections while retaining active, pending-approval, and side-task threads", () => {
    prepareStore();
    const sideTask: TimelineTaskActivityEntry = {
      activity: {
        activitySequence: 1,
        activityId: "activity_side",
        rootThreadId: "thr_side",
        taskThreadId: "thr_side_child",
        actorThreadId: "thr_side",
        causalTurnId: null,
        kind: "progress",
        summary: { text: "side task" },
        createdAt: "2026-09-05T00:00:00Z",
      },
      task: {
        taskThreadId: "thr_side_child",
        parentThreadId: "thr_side",
        rootThreadId: "thr_side",
        originTurnId: null,
        taskName: "side task",
        depth: 1,
        taskKind: "side_task",
        lifecycle: "independent",
        state: "running",
        revision: 1,
        latestActivitySequence: 1,
        unreadCount: 1,
        descendantCount: 0,
        runningDescendantCount: 0,
        needsAttentionCount: 0,
        latestSafeSummary: "side task",
        startedAt: "2026-09-05T00:00:00Z",
        completedAt: null,
        updatedAt: "2026-09-05T00:00:00Z",
      },
    };
    useTimelineStore.setState((state) => ({
      ...state,
      threads: {
        ...state.threads,
        thr_idle: projectionThread("thr_idle"),
        thr_active: projectionThread("thr_active"),
        thr_pending: projectionThread("thr_pending"),
        thr_side: projectionThread("thr_side"),
        thr_resolved: projectionThread("thr_resolved"),
        thr_extra: projectionThread("thr_extra"),
      },
      turns: {
        ...state.turns,
        turn_idle: {
          origin: "native" as const,
          turnId: "turn_idle",
          threadId: "thr_idle",
          status: "completed" as const,
        },
        turn_active: {
          origin: "native" as const,
          turnId: "turn_active",
          threadId: "thr_active",
          status: "running" as const,
        },
      },
      turnIdsByThread: {
        ...state.turnIdsByThread,
        thr_idle: ["turn_idle"],
        thr_active: ["turn_active"],
      },
      items: {
        ...state.items,
        item_idle: {
          origin: "native" as const,
          itemId: "item_idle",
          threadId: "thr_idle",
          turnId: "turn_idle",
          kind: "agent_message" as const,
          status: "completed" as const,
          text: "done",
        },
      },
      itemThreadById: { ...state.itemThreadById, item_idle: "thr_idle" },
      itemUtf8BytesById: { ...state.itemUtf8BytesById, item_idle: 4 },
      itemIdsByThread: { ...state.itemIdsByThread, thr_idle: ["item_idle"] },
      toolItemIdByCallId: {
        ...state.toolItemIdByCallId,
        "thr_idle:turn_idle:call_idle": "item_idle",
      },
      pendingToolOrdinalByCallId: {
        ...state.pendingToolOrdinalByCallId,
        "thr_idle:turn_idle:call_idle": 0,
      },
      liveStartedToolCorrelations: {
        ...state.liveStartedToolCorrelations,
        "thr_idle:turn_idle:call_idle": true,
      },
      approvalsById: {
        ...state.approvalsById,
        approval_pending: {
          threadId: "thr_pending",
          approval: {
            approvalId: "approval_pending",
            threadId: "thr_pending",
            turnId: "turn_pending",
            threadRevision: 1,
            callId: "call_pending",
            toolName: "shell",
            reason: "need approval",
            expiresAt: "2026-09-05T00:01:00Z",
          },
        },
        approval_resolved: {
          threadId: "thr_resolved",
          approval: {
            approvalId: "approval_resolved",
            threadId: "thr_resolved",
            turnId: "turn_resolved",
            threadRevision: 1,
            callId: "call_resolved",
            toolName: "shell",
            reason: "already resolved",
            expiresAt: "2026-09-05T00:01:00Z",
          },
          decision: "deny",
        },
      },
      approvalIdsByThread: {
        ...state.approvalIdsByThread,
        thr_pending: ["approval_pending"],
        thr_resolved: ["approval_resolved"],
      },
      taskActivitiesByRootThread: {
        ...state.taskActivitiesByRootThread,
        thr_side: [sideTask],
      },
      threadRevisionByThread: {
        ...state.threadRevisionByThread,
        thr_idle: 1,
      },
      streamSeqByTurn: { ...state.streamSeqByTurn, turn_idle: 1 },
      draftByTurn: { ...state.draftByTurn, turn_idle: [] },
      resyncRequired: { ...state.resyncRequired, thr_idle: "invalid_event" },
    }));

    useTimelineStore
      .getState()
      .pruneInactiveThreads([
        "thr_idle",
        "thr_active",
        "thr_pending",
        "thr_side",
        "thr_resolved",
        "thr_extra",
      ]);

    const next = useTimelineStore.getState();
    expect(next.threads["thr_idle"]).toBeUndefined();
    expect(next.turns["turn_idle"]).toBeUndefined();
    expect(next.items["item_idle"]).toBeUndefined();
    expect(next.toolItemIdByCallId["thr_idle:turn_idle:call_idle"]).toBeUndefined();
    expect(next.pendingToolOrdinalByCallId["thr_idle:turn_idle:call_idle"]).toBeUndefined();
    expect(next.liveStartedToolCorrelations["thr_idle:turn_idle:call_idle"]).toBeUndefined();
    expect(next.resyncRequired["thr_idle"]).toBeUndefined();
    expect(next.threads["thr_active"]).toBeDefined();
    expect(next.threads["thr_pending"]).toBeDefined();
    expect(next.approvalsById["approval_pending"]).toBeDefined();
    expect(next.threads["thr_side"]).toBeDefined();
    expect(next.taskActivitiesByRootThread["thr_side"]).toEqual([sideTask]);
    expect(next.threads["thr_resolved"]).toBeUndefined();
    expect(next.threads["thr_extra"]).toBeUndefined();
  });

  it("在恢复读取期间立即投影 live event，并在 baseline 后只重放新序号", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") })).toBe(
      "applied",
    );
    const firstDelta: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_recovery_first",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: "2026-08-18T00:00:01Z",
        streamSeq: 1,
        text: "基线",
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: firstDelta })).toBe("applied");
    const token = store.beginRecovery("thr_store", 10, "recovery");
    expect(token).toEqual({ threadId: "thr_store", requestEpoch: 10, mode: "recovery" });
    const secondDelta: TimelineEvent = {
      ...firstDelta,
      params: {
        ...firstDelta.params,
        eventId: "evt_recovery_second",
        sequence: 3,
        streamSeq: 2,
        text: "期间事件",
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: secondDelta })).toBe("applied");
    expect(useTimelineStore.getState().draftByTurn["turn_store"]?.[0]?.text).toBe("基线期间事件");
    const snapshot = {
      threadId: "thr_store",
      revision: 1,
      turns: [
        {
          origin: "native" as const,
          turnId: "turn_store",
          status: "running" as const,
          requestedAt: "2026-08-18T00:00:00Z",
          updatedAt: "2026-08-18T00:00:01Z",
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      liveStream: {
        turnId: "turn_store",
        streamSeq: 1,
        segments: [
          {
            kind: "assistant" as const,
            segmentStartSeq: 1,
            streamSeq: 1,
            text: "基线",
            occurredAt: "2026-08-18T00:00:01Z",
          },
        ],
      },
      nextCursor: null,
    };
    expect(store.endRecovery(token!, snapshot, "ws_store")).toEqual({
      status: "applied",
      replayedEvents: 1,
    });
    const recovered = useTimelineStore.getState();
    expect(recovered.streamSeqByTurn["turn_store"]).toBe(2);
    expect(recovered.draftByTurn["turn_store"]?.[0]?.text).toBe("基线期间事件");
    expect(recovered.getLastAcceptedLive("thr_store")?.receivedAt).toEqual(expect.any(Number));
  });

  it("恢复快照缺少 live baseline 时不重放残缺 delta，并继续要求基线", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") })).toBe(
      "applied",
    );
    const token = store.beginRecovery("thr_store", 10, "recovery");
    expect(token).toBeDefined();
    const delta: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_recovery_missing_baseline_delta",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        occurredAt: "2026-08-18T00:00:01Z",
        streamSeq: 1,
        text: "不应冒充完整正文",
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: delta })).toBe("applied");

    const result = store.endRecovery(
      token!,
      {
        threadId: "thr_store",
        revision: 1,
        turns: [
          {
            origin: "native" as const,
            turnId: "turn_store",
            status: "running",
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:01Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        nextCursor: null,
      },
      "ws_store",
    );
    expect(result).toEqual({ status: "needs_baseline", replayedEvents: 0 });
    const recovered = useTimelineStore.getState();
    expect(recovered.draftByTurn["turn_store"]).toBeUndefined();
    expect(recovered.streamSeqByTurn["turn_store"]).toBe(0);
    expect(recovered.resyncRequired["thr_store"]).toBe("gap");
  });

  it("恢复取消不会丢失已即时投影的 terminal，并留下非破坏性对账标记", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    store.applyHostEvent({ kind: "timeline", event: event(1, "queued", "running") });
    const token = store.beginRecovery("thr_store", 11, "recovery");
    expect(token).toBeDefined();
    const terminal: TimelineEvent = {
      jsonrpc: "2.0",
      method: "turn/terminal",
      params: {
        serverInstanceId: "srv_store",
        eventId: "evt_recovery_terminal",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_store",
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 2,
        occurredAt: "2026-08-18T00:00:02Z",
        state: "completed",
        summary: "完成",
        summaryTotalCharacters: 2,
        summaryTruncated: false,
        finalMessage: {
          messageId: "item_recovery_terminal",
          text: "完成",
          totalCharacters: 2,
          truncated: false,
        },
        changeSet: {
          state: "complete",
          incompleteReasons: [],
          files: [],
          stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
        },
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: terminal })).toBe("applied");
    store.cancelRecovery(token!);
    const afterCancel = useTimelineStore.getState();
    expect(afterCancel.turns["turn_store"]?.status).toBe("completed");
    expect(afterCancel.resyncRequired["thr_store"]).toBe("invalid_event");
  });

  it("恢复快照被 runtime 拒绝时不重放缓冲事件冒充 applied", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    const token = store.beginRecovery("thr_store", 12, "recovery");
    expect(token).toBeDefined();
    // 保留 server identity，只切换 phase，精确覆盖 applySnapshot 的 rejected 分支。
    useTimelineStore.setState((state) => ({
      ...state,
      handshake: { ...state.handshake, phase: "disconnected" },
    }));
    const result = store.endRecovery(
      token!,
      {
        threadId: "thr_store",
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        nextCursor: null,
      },
      "ws_store",
    );
    expect(result).toEqual({ status: "invalid", replayedEvents: 0 });
  });

  /** 关闭时 turn.threadId 是缺失 turnIdsByThread 索引后的所有权回退，迟到队列仍应被 tombstone 拒绝。@author kongweiguang */
  it("关闭回退扫描 Thread 所属 Turn 并阻止迟到队列重建", () => {
    prepareStore();
    const store = useTimelineStore.getState();
    expect(
      store.applyTurnAccepted({
        threadId: "thr_store",
        turnId: "turn_store",
        threadRevision: 1,
        submittedText: "请求关闭前的正文",
        submittedAt: "2026-08-18T00:00:00Z",
      }),
    ).toBe("applied");
    useTimelineStore.setState((state) => ({
      ...state,
      turnIdsByThread: { ...state.turnIdsByThread, thr_store: [] },
    }));
    expect(useTimelineStore.getState().turns["turn_store"]?.threadId).toBe("thr_store");

    expect(
      useTimelineStore.getState().closeThreads(["thr_store"], {
        serverInstanceId: "srv_store",
        generation: 1,
      }),
    ).toBe(true);
    expect(useTimelineStore.getState().turns["turn_store"]).toBeUndefined();
    expect(
      useTimelineStore.getState().applyInputQueue({
        turnId: "turn_store",
        revision: 1,
        accepting: true,
        items: [],
      }),
    ).toBe("rejected");
  });
});
