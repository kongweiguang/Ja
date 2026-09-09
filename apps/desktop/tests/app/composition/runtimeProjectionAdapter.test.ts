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
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
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
          changeSet: {
            state: "complete",
            incompleteReasons: [],
            files: [],
            stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
          },
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
    expect(state.resyncRequired["thr_fixture"]).toBeUndefined();
    expect(state.turns["turn_fixture"]?.changeSet?.state).toBe("complete");
  });

  it("隔离未通过 Conversation Snapshot 准入的 Child Turn 与 Approval 事件", () => {
    const projection = bindRuntimeProjectionPort();
    projection.applyRuntimeStatus({
      status: "ready",
      generation: 1,
      serverInstanceId: "srv_fixture",
      eventId: "evt_ready",
      occurredAt: "2026-09-03T00:00:00Z",
    });
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_root",
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_runtime_a",
    );
    const admittedState = useTimelineStore.getState();

    projection.applyHostEvent({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "turn/state-changed",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_child_running",
          sequence: 1,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_child",
          turnId: "turn_child",
          threadRevision: 1,
          occurredAt: "2026-09-03T00:00:01Z",
          from: "queued",
          to: "running",
        },
      },
    });
    projection.applyHostEvent({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "assistant/text-delta",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_child_delta",
          sequence: 2,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_child",
          turnId: "turn_child",
          threadRevision: 1,
          occurredAt: "2026-09-03T00:00:02Z",
          streamSeq: 1,
          text: "child progress",
        },
      },
    });
    projection.applyHostEvent({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "approval/requested",
        params: {
          serverInstanceId: "srv_fixture",
          eventId: "evt_child_approval",
          sequence: 3,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_child",
          turnId: "turn_child",
          threadRevision: 2,
          occurredAt: "2026-09-03T00:00:03Z",
          approvalId: "approval_child",
          callId: "call_child",
          toolName: "shell",
          reason: "需要写入工作区",
          expiresAt: "2026-09-03T00:05:00Z",
          from: "running",
          to: "waiting_approval",
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
          eventId: "evt_child_terminal",
          sequence: 4,
          generation: 1,
          workspaceId: "ws_runtime_a",
          threadId: "thr_child",
          turnId: "turn_child",
          threadRevision: 3,
          occurredAt: "2026-09-03T00:00:04Z",
          state: "completed",
          summary: "done",
          changeSet: {
            state: "complete",
            incompleteReasons: [],
            files: [],
            stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
          },
          finalMessage: { messageId: "item_child_final", text: "done" },
        },
      },
    });

    const state = useTimelineStore.getState();
    expect(state).toBe(admittedState);
    expect(state.threads["thr_child"]).toBeUndefined();
    expect(state.turns["turn_child"]).toBeUndefined();
    expect(state.items["item_child_final"]).toBeUndefined();
    expect(state.approvalsById["approval_child"]).toBeUndefined();
    expect(state.resyncRequired["thr_child"]).toBeUndefined();
    expect(state.resyncRequired).toEqual({});
  });
});
