// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  selectItemsForThread,
  useTimelineStore,
} from "@/features/conversation/application/timelineStore";
import type { TimelineEvent } from "@/features/conversation/domain/timelineContracts";

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
        contextUsage: null,
        nextCursor: null,
      },
      "ws_store",
    ),
  ).toBe("applied");
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
          contextUsage: null,
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
        itemId: "draft:turn_store",
        kind: "agent_message",
        status: "in_progress",
        text: "终态前可见",
      }),
    );
    expect(secondSelection.at(-1)).toBe(firstSelection.at(-1));
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
          usage: { inputTokens: 4, outputTokens: 1, totalTokens: 5 },
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
});
