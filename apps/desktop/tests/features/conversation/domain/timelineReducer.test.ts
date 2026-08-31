// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { timelineEventFromUnknown } from "@/features/conversation/domain/timelineContracts";
import {
  applyEventValue,
  applyRuntimeStatus,
  applySnapshot,
  applyTurnAccepted,
  createTimelineState,
  type TimelineState,
} from "@/features/conversation/domain/timelineReducer";

const serverInstanceId = "srv_one";
const threadId = "thr_one";
const turnId = "turn_one";

/** 构造已由 App Server 脱敏的 Tool 展示事实，避免测试重新引入 raw arguments/result。 */
function presentation(
  kind: "read" | "edit" | "write" | "shell" | "mcp",
  status:
    | "pending"
    | "running"
    | "waiting_approval"
    | "success"
    | "error"
    | "cancelled" = "pending",
  overrides: Record<string, unknown> = {},
) {
  return {
    kind,
    title: kind === "shell" ? "运行命令" : "读取文件",
    status,
    relativePaths: [],
    truncated: false,
    ...overrides,
  };
}

function readyState(): TimelineState {
  return applyRuntimeStatus(createTimelineState(), {
    status: "ready",
    generation: 1,
    serverInstanceId,
  });
}

function event(
  method: string,
  threadRevision: number,
  params: Record<string, unknown> = {},
): unknown {
  const baseParams: Record<string, unknown> = {
    serverInstanceId,
    eventId: `evt_${threadRevision}`,
    sequence: threadRevision,
    generation: 1,
    workspaceId: "ws_one",
    threadId,
    turnId,
    threadRevision,
    occurredAt: `2026-08-18T00:00:0${Math.min(threadRevision, 9)}Z`,
    ...params,
  };
  return {
    jsonrpc: "2.0",
    method,
    params: baseParams,
  };
}

function apply(state: TimelineState, value: unknown): TimelineState {
  const parsed = timelineEventFromUnknown(value);
  if (parsed === undefined) throw new Error("测试事件形状无效");
  return applyEventValue(state, parsed);
}

describe("timeline reducer", () => {
  it("拒绝缺失权威 Workspace owner 的 Snapshot 与 turn/start，不生成假身份", () => {
    const withoutThread = applyTurnAccepted(readyState(), {
      threadId,
      turnId,
      threadRevision: 1,
      submittedText: "hello",
      submittedAt: "2026-08-18T00:00:00Z",
    });
    expect(withoutThread.lastOutcome).toBe("resync_required");
    expect(withoutThread.turns[turnId]).toBeUndefined();
    expect(withoutThread.threads[threadId]).toBeUndefined();
    expect(
      Object.values(withoutThread.threads).some((thread) => thread.workspaceId === "ws_unknown"),
    ).toBe(false);

    const emptyWorkspaceSnapshot = applySnapshot(
      readyState(),
      { threadId, revision: 0, turns: [], items: [], contextUsage: null, nextCursor: null },
      "",
    );
    expect(emptyWorkspaceSnapshot.lastOutcome).toBe("invalid");
    expect(emptyWorkspaceSnapshot.threads[threadId]).toBeUndefined();
    expect(
      timelineEventFromUnknown(
        event("turn/state-changed", 1, {
          workspaceId: "",
          from: "queued",
          to: "running",
        }),
      ),
    ).toBeUndefined();
  });

  it("对未知 Tool 状态与 outcome fail closed，不让 Reducer 建立回退语义", () => {
    expect(
      timelineEventFromUnknown(
        event("assistant/model-step-committed", 1, {
          messageId: "item_unknown_tool",
          text: "",
          modelRound: 1,
          toolCalls: [
            {
              callId: "call_unknown",
              toolName: "read",
              ordinal: 0,
              presentation: presentation("read", "pending", { status: "unknown" }),
            },
          ],
        }),
      ),
    ).toBeUndefined();
    expect(
      timelineEventFromUnknown(
        event("tool/batch-committed", 1, {
          results: [
            {
              callId: "call_unknown",
              outcome: "unknown",
              ordinal: 0,
              presentation: presentation("read", "success"),
            },
          ],
        }),
      ),
    ).toBeUndefined();

    const invalidSnapshot = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 1,
        turns: [],
        items: [
          {
            itemId: "item_unknown",
            turnId,
            kind: "tool_call",
            createdAt: "2026-08-18T00:00:00Z",
            callId: "call_unknown",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read", "pending", { status: "unknown" }),
          },
        ],
        contextUsage: null,
        nextCursor: null,
      },
      "ws_one",
    );
    expect(invalidSnapshot.lastOutcome).toBe("invalid");
    expect(invalidSnapshot.items["item_unknown"]).toBeUndefined();
  });

  it("replays the accepted turn baseline before the committed review event chain", () => {
    let state = readyState();
    state = applySnapshot(
      state,
      { threadId, revision: 0, turns: [], items: [], contextUsage: null, nextCursor: null },
      "ws_one",
    );
    state = applyTurnAccepted(state, {
      threadId,
      turnId,
      threadRevision: 1,
      submittedText: "JA_FAKE_REVIEW_FIXTURE",
      submittedAt: "2026-08-18T00:00:01Z",
    });
    state = apply(state, event("turn/state-changed", 2, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 3, {
        messageId: "item_review_step",
        text: "",
        modelRound: 1,
        usage: { inputTokens: 8, outputTokens: 4, totalTokens: 12 },
        toolCalls: [
          {
            callId: "call_fake_review_read",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read", "pending", {
              inputPreview: "README.md",
              relativePaths: ["README.md"],
            }),
          },
        ],
      }),
    );
    const preparedTool = Object.values(state.items).find(
      (item) => item.metadata?.callId === "call_fake_review_read",
    );
    state = apply(
      state,
      event("approval/requested", 4, {
        approvalId: "appr_review_read",
        callId: "call_fake_review_read",
        toolName: "read",
        reason: "Tool requires approval",
        expiresAt: "2099-08-18T00:00:01Z",
        from: "running",
        to: "waiting_approval",
      }),
    );

    expect(state.lastOutcome).toBe("applied");
    expect(state.threadRevisionByThread[threadId]).toBe(4);
    expect(state.turns[turnId]?.status).toBe("waiting_approval");
    expect(state.items[preparedTool?.itemId ?? ""]?.metadata?.presentation?.status).toBe(
      "waiting_approval",
    );
    expect(state.resyncRequired[threadId]).toBeUndefined();
    expect(state.approvalsById["appr_review_read"]?.approval?.callId).toBe("call_fake_review_read");
  });

  it("projects the six-state Turn lifecycle and keeps semantic revisions monotonic", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_model_step",
        text: "准备运行测试",
        modelRound: 1,
        usage: { inputTokens: 8, outputTokens: 2, totalTokens: 10 },
        toolCalls: [
          {
            callId: "call_one",
            toolName: "shell",
            ordinal: 0,
            presentation: presentation("shell", "pending", {
              command: "pnpm test",
              relativeCwd: "workspace",
            }),
          },
        ],
      }),
    );
    const preparedTool = Object.values(state.items).find(
      (item) => item.metadata?.callId === "call_one",
    );
    expect(state.items["item_model_step"]?.metadata?.usageTotalTokens).toBe(10);
    expect(preparedTool?.status).toBe("in_progress");
    expect(state.threadRevisionByThread[threadId]).toBe(2);
    state = apply(
      state,
      event("approval/requested", 3, {
        approvalId: "appr_one",
        callId: "call_one",
        toolName: "shell",
        reason: "运行测试",
        expiresAt: "2099-08-18T00:00:01Z",
        from: "running",
        to: "waiting_approval",
      }),
    );

    expect(state.turns[turnId]?.status).toBe("waiting_approval");
    expect(state.items[preparedTool?.itemId ?? ""]?.metadata?.presentation?.status).toBe(
      "waiting_approval",
    );
    expect(state.threadRevisionByThread[threadId]).toBe(3);
    expect(state.approvalsById["appr_one"]?.approval?.threadRevision).toBe(3);

    state = apply(
      state,
      event("approval/resolved", 4, {
        approvalId: "appr_one",
        decision: "approve",
        from: "waiting_approval",
        to: "running",
      }),
    );
    expect(state.items[preparedTool?.itemId ?? ""]?.metadata?.presentation?.status).toBe("running");
    state = apply(
      state,
      event("tool/batch-committed", 5, {
        results: [
          {
            callId: "call_one",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("shell", "success", {
              command: "pnpm test",
              relativeCwd: "workspace",
              outputPreview: "测试通过",
              stdout: "测试通过",
              exitCode: 0,
              durationMs: 18,
            }),
          },
        ],
      }),
    );
    expect(state.items[preparedTool?.itemId ?? ""]?.status).toBe("completed");
    expect(state.items["item_change_one"]).toBeUndefined();
    expect(state.items[preparedTool?.itemId ?? ""]?.metadata?.presentation).toMatchObject({
      status: "success",
      outputPreview: "测试通过",
    });
    expect(state.threadRevisionByThread[threadId]).toBe(5);

    state = apply(
      state,
      event("turn/terminal", 6, {
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_final", text: "全部完成" },
        usage: { modelRound: 2, inputTokens: 12, outputTokens: 3, totalTokens: 15 },
      }),
    );

    expect(state.turns[turnId]?.status).toBe("completed");
    expect(state.approvalsById["appr_one"]?.decision).toBe("approve");
    expect(state.items).toEqual(
      expect.objectContaining({
        item_final: expect.objectContaining({ final: true, status: "completed", text: "全部完成" }),
      }),
    );
    expect(state.items["item_final"]?.metadata?.usageTotalTokens).toBe(15);
    expect(state.lastOutcome).toBe("resync_required");
    expect(state.resyncRequired[threadId]).toBe("terminal_snapshot");
  });

  /** Usage 是模型事务事实而非文案附属物，纯 Tool 轮次与空 Final 都必须更新 Thread 计量。 */
  it("在 Tool-only ModelStep 与无可见文本的 Terminal 中保存 Usage", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_tool_only",
        text: "",
        modelRound: 1,
        usage: { inputTokens: 40_000, outputTokens: 1_000, totalTokens: 41_000 },
        toolCalls: [
          {
            callId: "call_tool_only",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );
    expect(state.items["item_tool_only"]).toBeUndefined();
    expect(state.contextUsageByThread[threadId]).toMatchObject({
      turnId,
      modelRound: 1,
      inputTokens: 40_000,
    });

    state = apply(
      state,
      event("turn/terminal", 3, {
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_empty_final", text: "" },
        usage: { modelRound: 2, inputTokens: 48_000, outputTokens: 2_000, totalTokens: 50_000 },
      }),
    );
    expect(state.items["item_empty_final"]).toBeUndefined();
    expect(state.contextUsageByThread[threadId]).toMatchObject({
      turnId,
      modelRound: 2,
      inputTokens: 48_000,
      measuredAt: "2026-08-18T00:00:03Z",
    });
  });

  it("treats deltas as transient and drops the draft when streamSeq has a gap", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/text-delta", 1, {
        eventId: "evt_delta_1",
        sequence: 2,
        streamSeq: 1,
        text: "先",
      }),
    );
    expect(state.draftByTurn[turnId]?.text).toBe("先");

    state = apply(
      state,
      event("assistant/text-delta", 1, {
        eventId: "evt_delta_3",
        sequence: 3,
        streamSeq: 3,
        text: "后",
      }),
    );
    expect(state.lastOutcome).toBe("gap");
    expect(state.draftByTurn[turnId]).toBeUndefined();
    expect(state.resyncRequired[threadId]).toBe("gap");
  });

  it("deduplicates only the same event id and resyncs unseen stale revisions", () => {
    let state = readyState();
    const first = event("turn/state-changed", 1, { from: "queued", to: "running" });
    state = apply(state, first);
    const duplicate = apply(state, first);
    expect(duplicate.lastOutcome).toBe("duplicate");
    expect(duplicate.threadRevisionByThread).toEqual(state.threadRevisionByThread);

    const firstObject = first as { params: Record<string, unknown> };
    const sameRevisionDifferentEvent = apply(state, {
      ...(first as Record<string, unknown>),
      params: {
        ...firstObject.params,
        eventId: "evt_same_revision_different_payload",
        to: "completed",
      },
    });
    expect(sameRevisionDifferentEvent.lastOutcome).toBe("late");
    expect(sameRevisionDifferentEvent.resyncRequired[threadId]).toBe("late_event");

    const gap = apply(state, event("turn/state-changed", 3, { from: "running", to: "completed" }));
    expect(gap.lastOutcome).toBe("gap");
    expect(gap.resyncRequired[threadId]).toBe("gap");
  });

  it("closes unresolved approvals at a terminal boundary without fabricating a decision", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_pending_step",
        text: "准备写入",
        modelRound: 1,
        toolCalls: [
          {
            callId: "call_pending",
            toolName: "write_file",
            ordinal: 0,
            presentation: presentation("write"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("approval/requested", 3, {
        approvalId: "appr_pending",
        callId: "call_pending",
        toolName: "write_file",
        reason: "写入文件",
        expiresAt: "2099-08-18T00:00:01Z",
        from: "running",
        to: "waiting_approval",
      }),
    );
    state = apply(state, event("turn/terminal", 4, { state: "cancelled", summary: "用户取消" }));
    expect(state.approvalsById["appr_pending"]?.decision).toBeUndefined();
    expect(state.approvalsById["appr_pending"]?.closedAt).toBeDefined();
    expect(state.turns[turnId]?.status).toBe("cancelled");
  });

  it("does not advance a terminal thread from late context or workspace events", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("turn/terminal", 2, {
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_terminal", text: "完成" },
      }),
    );

    const context = apply(
      state,
      event("context/compacted", 3, {
        compactionId: "cmp_after_terminal",
        checkpointId: "checkpoint_after_terminal",
        trigger: "automatic",
        sourceRevision: 2,
        inputTokensBefore: 8,
        inputTokensAfter: 4,
        strategyVersion: "ja-context-v3",
      }),
    );
    expect(context.lastOutcome).toBe("resync_required");
    expect(context.resyncRequired[threadId]).toBe("invalid_event");
    expect(context.threadRevisionByThread[threadId]).toBe(2);
  });

  it("projects manual context lifecycle without inventing a Turn or advancing revision before commit", () => {
    let state = readyState();
    state = applySnapshot(
      state,
      { threadId, revision: 4, turns: [], items: [], contextUsage: null, nextCursor: null },
      "ws_one",
    );
    state = apply(
      state,
      event("context/compaction-started", 4, {
        eventId: "evt_compaction_started",
        turnId: null,
        compactionId: "cmp_manual",
        trigger: "manual",
        sourceRevision: 4,
        inputTokensBefore: 12_000,
        inputTokensAfter: null,
        strategyVersion: "ja-context-v3",
      }),
    );
    expect(state.contextCompactionByThread[threadId]).toMatchObject({
      phase: "started",
      turnId: null,
      inputTokensBefore: 12_000,
    });
    expect(state.threadRevisionByThread[threadId]).toBe(4);

    state = apply(
      state,
      event("context/compacted", 5, {
        eventId: "evt_compaction_completed",
        turnId: null,
        compactionId: "cmp_manual",
        trigger: "manual",
        sourceRevision: 4,
        inputTokensBefore: 12_000,
        inputTokensAfter: 5_000,
        strategyVersion: "ja-context-v3",
        checkpointId: "checkpoint_manual",
      }),
    );
    expect(state.contextCompactionByThread[threadId]).toMatchObject({
      phase: "compacted",
      checkpointId: "checkpoint_manual",
      inputTokensAfter: 5_000,
    });
    expect(state.threadRevisionByThread[threadId]).toBe(5);
    expect(state.itemIdsByThread[threadId]).toEqual([]);

    state = apply(
      state,
      event("context/compaction-failed", 5, {
        eventId: "evt_compaction_failed",
        turnId: null,
        compactionId: "cmp_manual_retry",
        trigger: "manual",
        sourceRevision: 5,
        inputTokensBefore: null,
        inputTokensAfter: null,
        strategyVersion: "ja-context-v3",
        errorCode: "TOKEN_COUNT_UNAVAILABLE",
      }),
    );
    expect(state.contextCompactionByThread[threadId]).toMatchObject({
      phase: "failed",
      errorCode: "TOKEN_COUNT_UNAVAILABLE",
    });
    expect(state.threadRevisionByThread[threadId]).toBe(5);
  });

  it("rejects a partial Tool batch without exposing any part of the composite projection", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_step",
        text: "two tools",
        modelRound: 1,
        toolCalls: [
          {
            callId: "call_one",
            toolName: "read_file",
            ordinal: 0,
            presentation: presentation("read"),
          },
          {
            callId: "call_two",
            toolName: "read_file",
            ordinal: 1,
            presentation: presentation("read"),
          },
        ],
      }),
    );
    const beforeItems = state.items;
    state = apply(
      state,
      event("tool/batch-committed", 3, {
        results: [
          {
            callId: "call_one",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("read", "success", { outputPreview: "a" }),
          },
        ],
      }),
    );
    expect(state.lastOutcome).toBe("resync_required");
    expect(state.items).toBe(beforeItems);
    expect(state.threadRevisionByThread[threadId]).toBe(2);
  });

  /** 权威快照同时恢复最近 Usage，并清除该 Thread 的瞬态 Draft 与压缩投影。 */
  it("restores a flat authoritative snapshot and removes in-flight drafts", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/text-delta", 1, {
        eventId: "evt_delta_draft",
        sequence: 2,
        streamSeq: 1,
        text: "draft",
      }),
    );
    const restored = applySnapshot(
      state,
      {
        threadId,
        revision: 4,
        turns: [
          {
            turnId: "turn_snapshot",
            status: "completed",
            runtime: null,
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:01Z",
            completedAt: "2026-08-18T00:00:01Z",
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [
          {
            itemId: "item_user",
            turnId: "turn_snapshot",
            kind: "user_input",
            createdAt: "2026-08-18T00:00:00Z",
            text: "问题",
          },
        ],
        contextUsage: {
          turnId: "turn_snapshot",
          modelRound: 2,
          inputTokens: 52_000,
          outputTokens: 4_000,
          totalTokens: 56_000,
          measuredAt: "2026-08-18T00:00:01Z",
        },
        nextCursor: null,
      },
      "ws_one",
    );
    expect(restored.lastOutcome).toBe("applied");
    expect(restored.threadRevisionByThread[threadId]).toBe(4);
    expect(restored.draftByTurn).toEqual({});
    expect(restored.items["item_user"]?.kind).toBe("user_message");
    expect(restored.turns["turn_snapshot"]?.changeSet).toBeNull();
    expect(restored.contextUsageByThread[threadId]).toMatchObject({
      turnId: "turn_snapshot",
      inputTokens: 52_000,
      totalTokens: 56_000,
    });
  });

  it("restores Java Tool and approval snapshot items without rejecting strict wire fields", () => {
    const restored = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 8,
        turns: [
          {
            turnId: "turn_snapshot",
            status: "completed",
            runtime: null,
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:04Z",
            completedAt: "2026-08-18T00:00:04Z",
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [
          {
            itemId: "item_call",
            turnId: "turn_snapshot",
            kind: "tool_call",
            createdAt: "2026-08-18T00:00:00Z",
            callId: "call_snapshot",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read", "success", {
              relativePaths: ["README.md"],
              inputPreview: "README.md",
              outputPreview: "content",
            }),
          },
          {
            itemId: "item_failed_call",
            turnId: "turn_snapshot",
            kind: "tool_call",
            createdAt: "2026-08-18T00:00:01Z",
            callId: "call_failed_snapshot",
            toolName: "read",
            ordinal: 1,
            presentation: presentation("read", "error", {
              relativePaths: ["missing.md"],
              inputPreview: "missing.md",
            }),
          },
          {
            itemId: "item_approval",
            kind: "approval",
            createdAt: "2026-08-18T00:00:02Z",
            approvalId: "appr_snapshot",
            turnId: "turn_snapshot",
            expiresAt: "2026-08-18T00:05:00Z",
            callId: "call_snapshot",
            toolName: "read",
            reason: "读取文件需要确认",
            decision: "approve",
          },
          {
            itemId: "item_attachment",
            kind: "attachment",
            createdAt: "2026-08-18T00:00:03Z",
            attachmentId: "att_snapshot",
            turnId: "turn_snapshot",
            displayName: "设计稿.pdf",
            sizeBytes: 2048,
            mediaKind: "pdf",
            mediaType: "application/pdf",
            state: "bound",
          },
        ],
        contextUsage: null,
        nextCursor: null,
      },
      "ws_one",
    );

    expect(restored.lastOutcome).toBe("applied");
    expect(restored.items["item_call"]).toMatchObject({
      kind: "tool_call",
      status: "completed",
      title: "读取文件",
      metadata: {
        callId: "call_snapshot",
        toolName: "read",
        presentation: { status: "success", outputPreview: "content" },
      },
    });
    expect(restored.items["item_failed_call"]).toMatchObject({
      kind: "tool_call",
      status: "failed",
      metadata: { presentation: { status: "error" } },
    });
    expect(restored.items["item_approval"]).toMatchObject({
      kind: "commentary",
      title: "审批已批准",
    });
    expect(restored.items["item_attachment"]).toMatchObject({
      kind: "commentary",
      title: "设计稿.pdf",
      summary: "PDF · 2.0 KB",
      metadata: {
        attachmentId: "att_snapshot",
        sizeBytes: 2048,
        mediaKind: "pdf",
        mediaType: "application/pdf",
        attachmentState: "bound",
      },
    });
  });

  it("按真实 Turn 恢复多轮历史，并保留失败 Turn 的稳定错误状态", () => {
    const restored = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 9,
        turns: [
          {
            turnId: "turn_first",
            status: "completed",
            runtime: null,
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:02Z",
            completedAt: "2026-08-18T00:00:02Z",
            errorCode: null,
            changeSet: null,
          },
          {
            turnId: "turn_second",
            status: "failed",
            runtime: null,
            requestedAt: "2026-08-18T00:01:00Z",
            updatedAt: "2026-08-18T00:01:01Z",
            completedAt: "2026-08-18T00:01:01Z",
            errorCode: "INTERNAL_ERROR",
            changeSet: null,
          },
        ],
        items: [
          {
            itemId: "item_first_user",
            turnId: "turn_first",
            kind: "user_input",
            createdAt: "2026-08-18T00:00:00Z",
            text: "第一问",
          },
          {
            itemId: "item_second_user",
            turnId: "turn_second",
            kind: "user_input",
            createdAt: "2026-08-18T00:01:00Z",
            text: "第二问",
          },
        ],
        contextUsage: null,
        nextCursor: null,
      },
      "ws_one",
    );

    expect(restored.items["item_first_user"]?.turnId).toBe("turn_first");
    expect(restored.items["item_second_user"]?.turnId).toBe("turn_second");
    expect(restored.turns["turn_first"]?.status).toBe("completed");
    expect(restored.turns["turn_second"]).toMatchObject({
      status: "failed",
      error: { code: "INTERNAL_ERROR", retryable: false },
    });
  });
});
