// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  selectItemsForThread,
  selectTaskActivitiesForRoot,
  useTimelineStore,
} from "@/features/conversation/application/timelineStore";
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
        goalActivities: [],
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

describe("timeline Zustand seam", () => {
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
          goalActivities: [],
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
        maxAttempts: 6,
      },
    };
    expect(store.applyHostEvent({ kind: "timeline", event: retryStarted })).toBe("applied");
    const duringRetry = selectItemsForThread("thr_store")(useTimelineStore.getState());
    const retryStatuses = duringRetry.filter((item) => item.metadata?.phase === "assistant_retry");
    expect(retryStatuses).toHaveLength(1);
    expect(retryStatuses[0]?.text).toBe("重试 2/6");
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
          goalActivities: [],
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
              turnId: "turn_queued_history",
              status: "queued",
              requestedAt: "2026-08-18T00:00:00Z",
              updatedAt: "2026-08-18T00:00:01Z",
              completedAt: null,
              errorCode: null,
              changeSet: null,
            },
            {
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
          goalActivities: [],
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
            requestId: "request_store_1",
            requestOrdinal: 1,
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
        thr_goal: projectionThread("thr_goal"),
      },
      turns: {
        ...state.turns,
        turn_idle: {
          turnId: "turn_idle",
          threadId: "thr_idle",
          status: "completed" as const,
        },
        turn_active: {
          turnId: "turn_active",
          threadId: "thr_active",
          status: "running" as const,
        },
      },
      items: {
        ...state.items,
        item_idle: {
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
      taskActivitiesByRootThread: {
        ...state.taskActivitiesByRootThread,
        thr_side: [sideTask],
      },
      goalActivitiesByOwnerThread: {
        ...state.goalActivitiesByOwnerThread,
        thr_goal: [
          {
            goalId: "goal_terminal",
            objective: "terminal goal",
            status: "achieved" as const,
            goalRevision: 1,
            eventSequence: 1,
            occurredAt: "2026-09-05T00:00:00Z",
          },
        ],
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
        "thr_goal",
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
    expect(next.threads["thr_goal"]).toBeUndefined();
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
      goalActivities: [],
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
        goalActivities: [],
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
        finalMessage: { messageId: "item_recovery_terminal", text: "完成" },
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
        goalActivities: [],
        nextCursor: null,
      },
      "ws_store",
    );
    expect(result).toEqual({ status: "invalid", replayedEvents: 0 });
  });
});
