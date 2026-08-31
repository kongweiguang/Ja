// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { afterEach, describe, expect, it, vi } from "vitest";
import { bindRuntimeProjectionPort } from "@/app/composition/runtimeProjectionAdapter";
import { useTimelineStore } from "@/features/conversation";

afterEach(() => useTimelineStore.getState().reset());

describe("runtime projection composition adapter", () => {
  it("HMR 模块重求值后仍复用唯一 Timeline Store", async () => {
    const beforeReload = useTimelineStore;

    vi.resetModules();
    const afterReload = await import("@/features/conversation");

    expect(afterReload.useTimelineStore).toBe(beforeReload);
  });

  it("按 Runtime admission 顺序写入唯一 Conversation Timeline Store", () => {
    const projection = bindRuntimeProjectionPort();
    expect(projection.currentGeneration()).toBe(0);

    projection.applyRuntimeStatus({
      status: "ready",
      generation: 1,
      serverInstanceId: "srv_fixture",
      eventId: "evt_ready",
      occurredAt: "2026-08-26T00:00:00Z",
    });
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_fixture",
        revision: 0,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      },
      "ws_runtime_a",
    );
    projection.applyTurnAccepted({
      threadId: "thr_fixture",
      turnId: "turn_fixture",
      threadRevision: 1,
      submittedText: "hello",
      submittedAt: "2026-08-26T00:00:00Z",
    });
    projection.applyHostEvent({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "turn/state-changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_running",
          sequence: 2,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_fixture",
          turnId: "turn_fixture",
          threadRevision: 2,
          occurredAt: "2026-08-26T00:00:01Z",
          from: "queued",
          to: "running",
        },
      },
    });
    projection.applyHostEvent({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "turn/terminal",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_terminal",
          sequence: 3,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_fixture",
          turnId: "turn_fixture",
          threadRevision: 3,
          occurredAt: "2026-08-26T00:00:02Z",
          state: "completed",
          summary: "done",
          finalMessage: { messageId: "item_final", text: "done" },
          usage: { modelRound: 1, inputTokens: 2, outputTokens: 1, totalTokens: 3 },
        },
      },
    });

    const state = useTimelineStore.getState();
    expect(projection.currentGeneration()).toBe(1);
    expect(state.turns["turn_fixture"]?.status).toBe("completed");
    expect(state.threadRevisionByThread["thr_fixture"]).toBe(3);
    expect(state.items["item_final"]?.text).toBe("done");
    expect(
      Object.values(state.items).some(
        (item) => item.kind === "user_message" && item.text === "hello",
      ),
    ).toBe(true);
    expect(state.resyncRequired["thr_fixture"]).toBe("terminal_snapshot");
  });
});
