// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  timelineEventFromUnknown,
  timelineSnapshotValidationReason,
} from "@/features/conversation/domain/timelineContracts";
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

/** 构造完整请求级 Usage，避免测试通过旧的 Turn 级运行快照补造画像。 */
function requestUsage(
  modelRound: number,
  inputTokens: number,
  outputTokens: number,
  totalTokens: number,
  overrides: Record<string, unknown> = {},
) {
  const base = {
    requestId: `request_${modelRound}`,
    requestOrdinal: modelRound,
    modelRound,
    purpose: "assistant" as const,
    certainty: "known" as const,
    profile: {
      providerId: "provider_demo",
      modelId: "model_demo",
      api: "openai_responses" as const,
      upstreamModel: "gpt-5.6-sol",
      requestedReasoning: "medium" as const,
      effectiveReasoning: "medium" as const,
      accessMode: "approval_required" as const,
      configGeneration: "cfg_demo",
      promptRevision: "prompt_demo",
      toolCatalogRevision: "tools_demo",
      contextWindowTokens: 128_000,
      maxOutputTokens: 16_000,
    },
    inputTokens,
    outputTokens,
    totalTokens,
    measuredAt: `2026-08-18T00:00:0${Math.min(modelRound, 9)}Z`,
  };
  return { ...base, ...overrides };
}

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

/** 构造父 Timeline 可见的安全 Task 活动，不把 Child Transcript 或思考正文放入 fixture。 */
function taskActivityEntry(activitySequence = 3) {
  return {
    activity: {
      activitySequence,
      activityId: `activity_${activitySequence}`,
      rootThreadId: threadId,
      taskThreadId: "thr_child",
      actorThreadId: threadId,
      causalTurnId: turnId,
      kind: "progress" as const,
      summary: { text: "正在检查" },
      createdAt: "2026-09-03T08:00:02Z",
    },
    task: {
      taskThreadId: "thr_child",
      parentThreadId: threadId,
      rootThreadId: threadId,
      originTurnId: turnId,
      taskName: "检查合同",
      depth: 1,
      taskKind: "subagent" as const,
      lifecycle: "attached" as const,
      state: "running" as const,
      revision: 2,
      latestActivitySequence: activitySequence,
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
}

/** 为 terminal 自动补齐 2.1 冻结 ChangeSet，使各用例只覆盖自己关心的状态字段。 */
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
  if (method === "turn/terminal" && !("changeSet" in params)) {
    baseParams["changeSet"] = {
      state: "complete",
      incompleteReasons: [],
      files: [],
      stats: { files: 0, additions: 0, deletions: 0, binaryFiles: 0, truncated: false },
    };
  }
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
  it("按 root 原子替换 taskActivities，并在 runtime generation 变化时清除旧投影", () => {
    const first = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 4,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [taskActivityEntry()],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    expect(first.taskActivitiesByRootThread[threadId]).toEqual([taskActivityEntry()]);

    const cleared = applySnapshot(
      first,
      {
        threadId,
        revision: 5,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    expect(cleared.taskActivitiesByRootThread[threadId]).toEqual([]);

    const nextGeneration = applyRuntimeStatus(cleared, {
      status: "ready",
      generation: 2,
      serverInstanceId: "srv_two",
    });
    expect(nextGeneration.taskActivitiesByRootThread).toEqual({});
  });

  /** Snapshot owner 必须是直接父会话的 attached Subagent，侧聊和错误 root 均拒绝入 Timeline。 */
  it("拒绝侧聊、嵌套任务和 Activity root 不一致的主 Timeline 投影", () => {
    const snapshot = (entry: unknown) => ({
      threadId,
      revision: 4,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [entry],
      goalActivities: [],
      nextCursor: null,
    });
    expect(timelineSnapshotValidationReason(snapshot(taskActivityEntry()))).toBeUndefined();

    const baseTaskActivity = taskActivityEntry();
    const sideTask = {
      ...baseTaskActivity,
      task: {
        ...baseTaskActivity.task,
        taskKind: "side_task" as const,
        lifecycle: "independent" as const,
        originTurnId: null,
      },
    };
    expect(timelineSnapshotValidationReason(snapshot(sideTask))).toBe("task_activity:0");

    const nestedTask = {
      ...baseTaskActivity,
      task: { ...baseTaskActivity.task, parentThreadId: "thr_other", depth: 2 },
    };
    expect(timelineSnapshotValidationReason(snapshot(nestedTask))).toBe("task_activity:0");

    const mismatchedRoot = {
      ...baseTaskActivity,
      activity: { ...baseTaskActivity.activity, rootThreadId: "thr_other" },
    };
    expect(timelineSnapshotValidationReason(snapshot(mismatchedRoot))).toBe("task_activity:0");
  });

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
      {
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
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
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    expect(invalidSnapshot.lastOutcome).toBe("invalid");
    expect(invalidSnapshot.items["item_unknown"]).toBeUndefined();
  });

  it("接纳 Tool 后连续消费 Steering 与两条 FIFO 的终态快照", () => {
    const snapshot = {
      threadId,
      revision: 16,
      turns: [
        {
          turnId,
          status: "completed",
          requestedAt: "2026-08-18T00:00:00Z",
          updatedAt: "2026-08-18T00:00:16Z",
          completedAt: "2026-08-18T00:00:16Z",
          changeSet: null,
          errorCode: null,
        },
      ],
      items: [
        {
          itemId: "item_prompt",
          turnId,
          kind: "user_input",
          content: [{ type: "text", text: "先执行工具" }],
          attachments: [],
          createdAt: "2026-08-18T00:00:00Z",
        },
        {
          itemId: "item_tool",
          turnId,
          kind: "tool_call",
          callId: "call_read",
          toolName: "read",
          ordinal: 0,
          presentation: presentation("read", "success"),
          createdAt: "2026-08-18T00:00:01Z",
        },
        ...[
          ["steering", "先按新方向处理", "调整方向已优先处理"],
          ["edited", "编辑后的普通消息", "编辑后的普通消息已处理"],
          ["follow_up", "最后一条普通消息", "最后一条普通消息已处理"],
        ].flatMap(([suffix, input, answer], index) => [
          {
            itemId: `item_${suffix}_input`,
            turnId,
            kind: "user_input",
            content: [{ type: "text", text: input }],
            attachments: [],
            createdAt: `2026-08-18T00:00:0${index + 2}Z`,
          },
          {
            itemId: `item_${suffix}_answer`,
            turnId,
            kind: "final_answer",
            text: answer,
            createdAt: `2026-08-18T00:00:0${index + 5}Z`,
          },
        ]),
      ],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor: null,
    };

    expect(timelineSnapshotValidationReason(snapshot)).toBeUndefined();
    const applied = applySnapshot(readyState(), snapshot, "ws_one");
    expect(applied.lastOutcome).toBe("applied");
    expect(
      applied.itemIdsByThread[threadId]
        ?.map((itemId) => applied.items[itemId])
        .filter((item) => item?.kind === "agent_message" && item.final)
        .map((item) => item?.text),
    ).toEqual(["调整方向已优先处理", "编辑后的普通消息已处理", "最后一条普通消息已处理"]);
  });

  it("replays the accepted turn baseline before the committed review event chain", () => {
    let state = readyState();
    state = applySnapshot(
      state,
      {
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
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
        usage: requestUsage(1, 8, 4, 12),
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

  it("projects the Turn lifecycle and keeps semantic revisions monotonic", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_model_step",
        text: "准备运行测试",
        modelRound: 1,
        usage: requestUsage(1, 8, 2, 10),
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
    state = apply(state, event("tool/started", 5, { callId: "call_one", ordinal: 0 }));
    expect(state.items[preparedTool?.itemId ?? ""]?.metadata?.presentation?.status).toBe("running");
    state = apply(
      state,
      event("tool/batch-committed", 6, {
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
    expect(state.threadRevisionByThread[threadId]).toBe(6);

    state = apply(
      state,
      event("turn/terminal", 7, {
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_final", text: "全部完成" },
        usage: requestUsage(2, 12, 3, 15),
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
    expect(state.items["item_final"]?.metadata?.failureReply).toBeUndefined();
    expect(state.lastOutcome).toBe("applied");
    expect(state.resyncRequired[threadId]).toBe("terminal_snapshot");
  });

  /** 同一模型提交的 live 与 snapshot 都必须保持 reasoning、正文、Tool 的稳定阅读顺序。 */
  it("keeps model-step reasoning before text and tools across reload", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_ordered_text",
        text: "准备执行读取",
        reasoningSummary: "先核对读取范围",
        modelRound: 1,
        toolCalls: [
          {
            callId: "call_ordered_read",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read", "pending", { relativePaths: ["README.md"] }),
          },
        ],
      }),
    );

    const liveItems = state.itemIdsByThread[threadId]?.map((itemId) => state.items[itemId]);
    expect(liveItems?.map((item) => item?.kind)).toEqual(["reasoning", "commentary", "tool_call"]);

    const reloaded = applySnapshot(
      state,
      {
        threadId,
        revision: 2,
        turns: [
          {
            turnId,
            status: "running" as const,
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:02Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [
          {
            itemId: "item_ordered_text_reasoning",
            turnId,
            kind: "reasoning_summary" as const,
            text: "先核对读取范围",
            modelRound: 1,
            createdAt: "2026-08-18T00:00:01Z",
          },
          {
            itemId: "item_ordered_text",
            turnId,
            kind: "assistant_progress" as const,
            text: "准备执行读取",
            modelRound: 1,
            createdAt: "2026-08-18T00:00:01Z",
          },
          {
            itemId: "call_ordered_read_call",
            turnId,
            kind: "tool_call" as const,
            callId: "call_ordered_read",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read", "pending", { relativePaths: ["README.md"] }),
            createdAt: "2026-08-18T00:00:01Z",
          },
        ],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    const reloadedItems = reloaded.itemIdsByThread[threadId]?.map(
      (itemId) => reloaded.items[itemId],
    );
    expect(reloadedItems?.map((item) => item?.kind)).toEqual([
      "reasoning",
      "commentary",
      "tool_call",
    ]);
    expect(reloadedItems?.map((item) => item?.kind)).toEqual(liveItems?.map((item) => item?.kind));
  });

  /** Failed Terminal 的固定正文是运行时安全回复，不得被后续 UI 当成 Provider 半截输出。 */
  it("marks a live failed terminal final message as a durable failure reply", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("turn/terminal", 2, {
        state: "failed",
        summary: "本轮未能完成",
        finalMessage: { messageId: "item_failure_reply", text: "本轮未能完成，请检查后重试。" },
        errorCode: "TOOL_STALLED",
        errorMessage: "Tool calls kept failing",
      }),
    );

    expect(state.turns[turnId]).toMatchObject({
      status: "failed",
      error: { code: "TOOL_STALLED" },
    });
    expect(state.items["item_failure_reply"]).toMatchObject({
      kind: "agent_message",
      status: "failed",
      text: "本轮未能完成，请检查后重试。",
      metadata: { failureReply: true },
    });
  });

  /** 重启调和后的 Suspended 继续阻塞 Thread，且只能先回到队列再恢复执行。 */
  it("projects suspended as a resumable blocking state", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(state, event("turn/state-changed", 2, { from: "running", to: "suspended" }));
    expect(state.turns[turnId]?.status).toBe("suspended");
    expect(state.threads[threadId]?.activeTurnId).toBe(turnId);

    state = apply(state, event("turn/state-changed", 3, { from: "suspended", to: "queued" }));
    state = apply(state, event("turn/state-changed", 4, { from: "queued", to: "running" }));
    expect(state.turns[turnId]?.status).toBe("running");
    expect(state.lastOutcome).toBe("applied");
  });

  /** 并发发起的 thread/read 可能晚于挂起事件返回，旧快照不能撤销显式恢复所依赖的 suspended 状态。 */
  it("keeps a newer suspended event when an older running snapshot arrives late", () => {
    let state = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 16,
        turns: [
          {
            turnId,
            status: "running",
            requestedAt: "2026-08-18T00:00:01Z",
            updatedAt: "2026-08-18T00:00:16Z",
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
        nextCursor: null,
      },
      "ws_one",
    );
    state = apply(
      state,
      event("turn/state-changed", 18, {
        sequence: 28,
        from: "running",
        to: "suspended",
      }),
    );

    const late = applySnapshot(
      state,
      {
        threadId,
        revision: 17,
        turns: [
          {
            turnId,
            status: "running",
            requestedAt: "2026-08-18T00:00:01Z",
            updatedAt: "2026-08-18T00:00:17Z",
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
        nextCursor: null,
      },
      "ws_one",
    );

    expect(late.lastOutcome).toBe("late");
    expect(late.threadRevisionByThread[threadId]).toBe(18);
    expect(late.turns[turnId]?.status).toBe("suspended");
    expect(late.turns[turnId]?.threadRevision).toBe(18);
  });

  /** Queue 使用独立 revision；同一 Thread revision 的迟到快照不能撤销已提交的附件修复状态。 */
  it("keeps a newer queue event when a same-thread-revision snapshot arrives late", () => {
    const pendingInput = {
      inputId: "input_attachment",
      turnId,
      content: [{ type: "attachment" as const, attachmentId: "att_unavailable" }],
      attachments: [
        {
          attachmentId: "att_unavailable",
          displayName: "capture.png",
          sizeBytes: 128,
          mediaKind: "image" as const,
          mediaType: "image/png",
        },
      ],
      kind: "follow_up" as const,
      status: "pending" as const,
      issue: null,
      inputRevision: 1,
      createdAt: "2026-08-18T00:00:02Z",
    };
    let state = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 17,
        turns: [
          {
            turnId,
            status: "running",
            requestedAt: "2026-08-18T00:00:01Z",
            updatedAt: "2026-08-18T00:00:17Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: { turnId, revision: 13, accepting: true, items: [pendingInput] },
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    state = apply(state, {
      jsonrpc: "2.0",
      method: "turn/input-queue-changed",
      params: {
        serverInstanceId,
        eventId: "evt_queue_attention",
        sequence: 27,
        occurredAt: "2026-08-18T00:00:18Z",
        generation: 1,
        workspaceId: "ws_one",
        threadId,
        turnId,
        inputQueue: {
          turnId,
          revision: 14,
          accepting: true,
          items: [
            {
              ...pendingInput,
              status: "needs_attention",
              issue: {
                errorCode: "ATTACHMENT_UNAVAILABLE",
                message: "附件不可用，请移除后继续。",
                retryable: true,
              },
              inputRevision: 2,
            },
          ],
        },
      },
    });

    const late = applySnapshot(
      state,
      {
        threadId,
        revision: 17,
        turns: [
          {
            turnId,
            status: "running",
            requestedAt: "2026-08-18T00:00:01Z",
            updatedAt: "2026-08-18T00:00:17Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: { turnId, revision: 13, accepting: true, items: [pendingInput] },
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );

    expect(late.lastOutcome).toBe("applied");
    expect(late.inputQueueByTurn[turnId]).toMatchObject({
      revision: 14,
      items: [
        {
          status: "needs_attention",
          issue: { errorCode: "ATTACHMENT_UNAVAILABLE" },
          inputRevision: 2,
        },
      ],
    });
  });

  /** Suspended 只能由显式 Resume 或 Cancel 离开，不能接受缺少持久恢复语义的直接失败事件。 */
  it("rejects a direct suspended to failed transition", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(state, event("turn/state-changed", 2, { from: "running", to: "suspended" }));

    state = apply(state, event("turn/state-changed", 3, { from: "suspended", to: "failed" }));

    expect(state.turns[turnId]?.status).toBe("suspended");
    expect(state.lastOutcome).toBe("resync_required");
    expect(state.resyncRequired[threadId]).toBe("invalid_event");
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
        usage: requestUsage(1, 40_000, 1_000, 41_000),
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
      requestId: "request_1",
      modelRound: 1,
      inputTokens: 40_000,
    });

    state = apply(
      state,
      event("turn/terminal", 3, {
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_empty_final", text: "" },
        usage: requestUsage(2, 48_000, 2_000, 50_000),
      }),
    );
    expect(state.items["item_empty_final"]).toBeUndefined();
    expect(state.contextUsageByThread[threadId]).toMatchObject({
      requestId: "request_2",
      modelRound: 2,
      inputTokens: 48_000,
      measuredAt: "2026-08-18T00:00:02Z",
    });
  });

  /** 请求序号是 Context Usage 的唯一新旧边界，迟到的旧请求不得覆盖更新事实。 */
  it("拒绝较低 requestOrdinal 覆盖最新请求", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_request_2",
        text: "",
        modelRound: 2,
        usage: requestUsage(2, 20, 2, 22),
        toolCalls: [
          {
            callId: "call_request_2",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("tool/batch-committed", 3, {
        results: [
          {
            callId: "call_request_2",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("read", "success"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("assistant/model-step-committed", 4, {
        messageId: "item_request_1_late",
        text: "",
        modelRound: 1,
        usage: requestUsage(1, 10, 1, 11),
        toolCalls: [
          {
            callId: "call_request_1_late",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );

    expect(state.contextUsageByThread[threadId]).toMatchObject({
      requestId: "request_2",
      requestOrdinal: 2,
      inputTokens: 20,
    });
  });

  /** 最新请求即使尚无可靠计量也必须成为展示事实，不能回退到旧请求的 KNOWN 数值。 */
  it("以较新 UNKNOWN 覆盖旧 KNOWN", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_known_1",
        text: "",
        modelRound: 1,
        usage: requestUsage(1, 10, 1, 11),
        toolCalls: [
          {
            callId: "call_known_1",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("tool/batch-committed", 3, {
        results: [
          {
            callId: "call_known_1",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("read", "success"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("assistant/model-step-committed", 4, {
        messageId: "item_unknown_2",
        text: "",
        modelRound: 2,
        usage: requestUsage(2, 0, 0, 0, {
          certainty: "unknown",
          inputTokens: null,
          outputTokens: null,
          totalTokens: null,
        }),
        toolCalls: [
          {
            callId: "call_unknown_2",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );

    expect(state.contextUsageByThread[threadId]).toMatchObject({
      requestId: "request_2",
      requestOrdinal: 2,
      certainty: "unknown",
      inputTokens: null,
    });
  });

  /** 同一请求只能在画像完全一致时从 UNKNOWN 原位升级，避免混合两次真实 HTTP 请求。 */
  it("只允许相同请求与 Profile 的 UNKNOWN 升级为 KNOWN", () => {
    const unknown = requestUsage(1, 0, 0, 0, {
      certainty: "unknown",
      inputTokens: null,
      outputTokens: null,
      totalTokens: null,
    });
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_unknown_upgrade",
        text: "",
        modelRound: 1,
        usage: unknown,
        toolCalls: [
          {
            callId: "call_unknown_upgrade",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("tool/batch-committed", 3, {
        results: [
          {
            callId: "call_unknown_upgrade",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("read", "success"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("turn/terminal", 4, {
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_known_upgrade", text: "完成" },
        usage: requestUsage(1, 10, 2, 12),
      }),
    );

    expect(state.contextUsageByThread[threadId]).toMatchObject({
      requestId: "request_1",
      requestOrdinal: 1,
      certainty: "known",
      inputTokens: 10,
      totalTokens: 12,
    });

    let conflict = readyState();
    conflict = apply(conflict, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    conflict = apply(
      conflict,
      event("assistant/model-step-committed", 2, {
        messageId: "item_unknown_conflict",
        text: "",
        modelRound: 1,
        usage: unknown,
        toolCalls: [
          {
            callId: "call_unknown_conflict",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );
    conflict = apply(
      conflict,
      event("tool/batch-committed", 3, {
        results: [
          {
            callId: "call_unknown_conflict",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("read", "success"),
          },
        ],
      }),
    );
    conflict = apply(
      conflict,
      event("turn/terminal", 4, {
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_profile_conflict", text: "完成" },
        usage: requestUsage(1, 10, 2, 12, {
          profile: {
            ...requestUsage(1, 10, 2, 12).profile,
            promptRevision: "prompt_changed",
          },
        }),
      }),
    );

    expect(conflict.lastOutcome).toBe("resync_required");
    expect(conflict.contextUsageByThread[threadId]).toEqual({ ...unknown, turnId });
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
    expect(state.draftByTurn[turnId]?.[0]?.text).toBe("先");

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

  /**
   * reasoning 与公开回复共用 Turn streamSeq，但跨语义时必须保留为不同 segment；随后结算只留下
   * 一份持久 reasoning/item，不得把上一段 reasoning 覆盖成回复或重复展示。
   */
  it("preserves interleaved reasoning and assistant segments until model settlement", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/reasoning-summary-delta", 1, {
        eventId: "evt_reasoning_1",
        streamSeq: 1,
        text: "先判断上下文。",
      }),
    );
    state = apply(
      state,
      event("assistant/text-delta", 1, {
        eventId: "evt_text_2",
        streamSeq: 2,
        text: "公开回复的一部分。",
      }),
    );
    state = apply(
      state,
      event("assistant/reasoning-summary-delta", 1, {
        eventId: "evt_reasoning_3",
        streamSeq: 3,
        text: "再核对一个约束。",
      }),
    );

    expect(state.draftByTurn[turnId]).toEqual([
      expect.objectContaining({ kind: "reasoning", text: "先判断上下文。", segmentStartSeq: 1 }),
      expect.objectContaining({
        kind: "assistant",
        text: "公开回复的一部分。",
        segmentStartSeq: 2,
      }),
      expect.objectContaining({ kind: "reasoning", text: "再核对一个约束。", segmentStartSeq: 3 }),
    ]);

    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        eventId: "evt_model_settled",
        messageId: "item_model_settled",
        text: "公开回复的一部分。",
        reasoningSummary: "先判断上下文。再核对一个约束。",
        modelRound: 1,
        toolCalls: [],
      }),
    );

    expect(state.draftByTurn[turnId]).toBeUndefined();
    expect(
      Object.values(state.items).filter(
        (item) => item.turnId === turnId && item.kind === "reasoning",
      ),
    ).toHaveLength(1);
    expect(
      Object.values(state.items).filter(
        (item) => item.turnId === turnId && item.kind === "commentary",
      ),
    ).toHaveLength(1);
    expect(
      Object.values(state.items)
        .filter((item) => item.turnId === turnId && item.kind === "reasoning")
        .map((item) => item.text),
    ).toEqual(["先判断上下文。再核对一个约束。"]);
  });

  /** Reload 后的 reasoning_summary 仍保留独立语义，不能因恢复投影退化成普通 Commentary。 */
  it("restores persisted reasoning summaries as reasoning items", () => {
    const restored = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 1,
        turns: [
          {
            turnId,
            status: "completed" as const,
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:02Z",
            completedAt: "2026-08-18T00:00:02Z",
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [
          {
            itemId: "item_persisted_reasoning",
            turnId,
            kind: "reasoning_summary" as const,
            text: "刷新后仍可见的公开摘要",
            modelRound: 1,
            createdAt: "2026-08-18T00:00:01Z",
          },
        ],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );

    expect(restored.items["item_persisted_reasoning"]).toMatchObject({
      kind: "reasoning",
      text: "刷新后仍可见的公开摘要",
      metadata: { phase: "reasoning_summary", modelRound: 1 },
    });
  });

  /** Event ID 负责幂等；未见旧事实必须重读，但内部 revision 跳跃本身不是事件丢失。 */
  it("deduplicates only the same event id, rejects stale facts, and accepts revision jumps", () => {
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

    const jumped = apply(
      state,
      event("assistant/model-step-committed", 3, {
        messageId: "item_jump",
        text: "合法跳过内部 revision",
        modelRound: 1,
        toolCalls: [],
      }),
    );
    expect(jumped.lastOutcome).toBe("applied");
    expect(jumped.threadRevisionByThread[threadId]).toBe(3);
    expect(jumped.resyncRequired[threadId]).toBeUndefined();
  });

  /**
   * 复刻真实 Provider/Tool 队列事件链；公开 sequence 连续时，未通知的内部事务 revision
   * 不得阻止三条排队消息依次结算并进入 completed 终态。
   */
  it("accepts internal revision jumps across Tool and queued-input rounds", () => {
    let state = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 1,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    state = applyTurnAccepted(state, {
      threadId,
      turnId,
      threadRevision: 2,
      submittedText: "先执行工具",
      submittedAt: "2026-09-01T00:00:00Z",
    });
    state = apply(
      state,
      event("turn/state-changed", 3, { sequence: 1, from: "queued", to: "running" }),
    );
    state = apply(
      state,
      event("assistant/model-step-committed", 4, {
        sequence: 2,
        messageId: "item_tool_round",
        text: "",
        modelRound: 1,
        toolCalls: [
          {
            callId: "call_read",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read", "pending"),
          },
        ],
      }),
    );
    state = apply(
      state,
      event("approval/requested", 5, {
        sequence: 3,
        approvalId: "appr_read",
        callId: "call_read",
        toolName: "read",
        reason: "读取参考",
        expiresAt: "2099-09-01T00:00:00Z",
        from: "running",
        to: "waiting_approval",
      }),
    );
    state = apply(
      state,
      event("approval/resolved", 6, {
        sequence: 4,
        approvalId: "appr_read",
        decision: "approve",
        from: "waiting_approval",
        to: "running",
      }),
    );
    state = apply(
      state,
      event("tool/batch-committed", 8, {
        sequence: 5,
        results: [
          {
            callId: "call_read",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("read", "success"),
          },
        ],
      }),
    );

    const steering = {
      inputId: "input_steering",
      turnId,
      content: [{ type: "text" as const, text: "先按新方向处理" }],
      attachments: [],
      kind: "steering" as const,
      status: "pending" as const,
      issue: null,
      inputRevision: 1,
      createdAt: "2026-09-01T00:00:01Z",
    };
    const edited = {
      inputId: "input_edited",
      turnId,
      content: [{ type: "text" as const, text: "编辑后的普通消息" }],
      attachments: [],
      kind: "follow_up" as const,
      status: "pending" as const,
      issue: null,
      inputRevision: 2,
      createdAt: "2026-09-01T00:00:02Z",
    };
    const last = {
      inputId: "input_last",
      turnId,
      content: [{ type: "text" as const, text: "最后一条普通消息" }],
      attachments: [],
      kind: "follow_up" as const,
      status: "pending" as const,
      issue: null,
      inputRevision: 1,
      createdAt: "2026-09-01T00:00:03Z",
    };
    /** 为 reducer 构造已通过 transport 连续 sequence 校验的完整消费事实。 */
    const consumed = (
      revision: number,
      sequence: number,
      input: typeof steering | typeof edited | typeof last,
      remaining: Array<typeof steering | typeof edited | typeof last>,
      queueRevision: number,
      assistantSettlement?: { messageId: string; text: string; modelRound: number },
    ) =>
      event("turn/input-consumed", revision, {
        sequence,
        input,
        userItem: {
          itemId: `${input.inputId}_item`,
          turnId,
          kind: "user_input",
          content: input.content,
          attachments: input.attachments,
          createdAt: input.createdAt,
        },
        inputQueue: {
          turnId,
          revision: queueRevision,
          accepting: true,
          items: remaining,
        },
        ...(assistantSettlement === undefined ? {} : { assistantSettlement }),
      });

    state = apply(state, consumed(9, 6, steering, [edited, last], 4));
    state = apply(
      state,
      consumed(11, 7, edited, [last], 5, {
        messageId: "item_steering_answer",
        text: "调整方向已处理",
        modelRound: 2,
      }),
    );
    state = apply(
      state,
      consumed(13, 8, last, [], 6, {
        messageId: "item_edited_answer",
        text: "编辑后的普通消息已处理",
        modelRound: 3,
      }),
    );
    state = apply(
      state,
      event("turn/terminal", 15, {
        sequence: 9,
        state: "completed",
        summary: "完成",
        finalMessage: { messageId: "item_last_answer", text: "最后一条普通消息已处理" },
        usage: requestUsage(4, 20, 4, 24),
      }),
    );

    expect(state.threadRevisionByThread[threadId]).toBe(15);
    expect(state.turns[turnId]?.status).toBe("completed");
    expect(
      state.itemIdsByThread[threadId]
        ?.map((itemId) => state.items[itemId])
        .filter((item) => item?.kind === "agent_message" && item.final)
        .map((item) => item?.text),
    ).toEqual(["调整方向已处理", "编辑后的普通消息已处理", "最后一条普通消息已处理"]);
    expect(state.resyncRequired[threadId]).toBe("terminal_snapshot");
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
        strategyVersion: "ja-context-v1",
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
      {
        threadId,
        revision: 4,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
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
        strategyVersion: "ja-context-v1",
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
        strategyVersion: "ja-context-v1",
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
        strategyVersion: "ja-context-v1",
        errorCode: "SUMMARY_FAILURE",
      }),
    );
    expect(state.contextCompactionByThread[threadId]).toMatchObject({
      phase: "failed",
      errorCode: "SUMMARY_FAILURE",
    });
    expect(state.threadRevisionByThread[threadId]).toBe(5);
  });

  /** 压缩后旧 Usage 进入 UNKNOWN；后续生命周期和旧 Snapshot 不得重新启用它，直到新响应到达。 */
  it("在多次压缩生命周期中保持 Usage 未知并允许新响应恢复", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_usage_before_compaction",
        text: "",
        modelRound: 1,
        usage: requestUsage(1, 40_000, 1_000, 41_000),
        toolCalls: [],
      }),
    );
    expect(state.contextUsageByThread[threadId]?.certainty).toBe("known");

    state = apply(
      state,
      event("context/compacted", 3, {
        eventId: "evt_compaction_usage_boundary",
        turnId: null,
        compactionId: "cmp_usage_boundary",
        trigger: "automatic",
        sourceRevision: 2,
        inputTokensBefore: 40_000,
        inputTokensAfter: 12_000,
        strategyVersion: "ja-context-v1",
        occurredAt: "2026-08-18T00:00:03Z",
      }),
    );
    expect(state.contextUsageByThread[threadId]).toMatchObject({
      certainty: "unknown",
      requestOrdinal: 1,
      inputTokens: null,
    });

    state = apply(
      state,
      event("context/compaction-started", 4, {
        eventId: "evt_compaction_usage_retry_started",
        threadRevision: 3,
        turnId: null,
        compactionId: "cmp_usage_retry",
        trigger: "automatic",
        sourceRevision: 3,
        inputTokensBefore: 41_000,
        inputTokensAfter: null,
        strategyVersion: "ja-context-v1",
        occurredAt: "2026-08-18T00:00:04Z",
      }),
    );
    state = apply(
      state,
      event("context/compaction-failed", 5, {
        eventId: "evt_compaction_usage_retry_failed",
        threadRevision: 3,
        turnId: null,
        compactionId: "cmp_usage_retry",
        trigger: "automatic",
        sourceRevision: 3,
        inputTokensBefore: 41_000,
        inputTokensAfter: null,
        strategyVersion: "ja-context-v1",
        errorCode: "SUMMARY_FAILURE",
        occurredAt: "2026-08-18T00:00:05Z",
      }),
    );
    expect(state.contextUsageByThread[threadId]?.certainty).toBe("unknown");

    const recovered = apply(
      state,
      event("assistant/model-step-committed", 6, {
        messageId: "item_usage_after_compaction",
        text: "",
        modelRound: 2,
        usage: requestUsage(2, 12_000, 1_000, 13_000, {
          measuredAt: "2026-08-18T00:00:06Z",
        }),
        toolCalls: [],
      }),
    );
    expect(recovered.contextUsageByThread[threadId]).toMatchObject({
      certainty: "known",
      requestOrdinal: 2,
      inputTokens: 12_000,
    });

    const restored = applySnapshot(
      state,
      {
        threadId,
        revision: 5,
        turns: [
          {
            turnId,
            status: "running",
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:05Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: null,
        contextUsage: {
          ...requestUsage(1, 40_000, 1_000, 41_000),
          turnId,
        },
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    expect(restored.contextCompactionByThread[threadId]).toBeUndefined();
    expect(restored.contextUsageByThread[threadId]).toMatchObject({
      certainty: "unknown",
      requestOrdinal: 1,
      inputTokens: null,
    });
  });

  it("逐 ordinal 结算连续 Tool，首个结果不会刷新或串改仍在等待的第二行", () => {
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
    state = apply(state, event("tool/started", 3, { callId: "call_one", ordinal: 0 }));
    const firstTool = Object.values(state.items).find(
      (item) => item.metadata?.callId === "call_one",
    );
    const secondTool = Object.values(state.items).find(
      (item) => item.metadata?.callId === "call_two",
    );
    expect(firstTool?.metadata?.presentation?.status).toBe("running");
    expect(secondTool?.metadata?.presentation?.status).toBe("pending");
    state = apply(
      state,
      event("tool/batch-committed", 4, {
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
    expect(state.lastOutcome).toBe("applied");
    expect(state.items[firstTool?.itemId ?? ""]?.status).toBe("completed");
    expect(state.items[secondTool?.itemId ?? ""]?.status).toBe("in_progress");
    expect(state.items[secondTool?.itemId ?? ""]?.metadata?.presentation?.status).toBe("pending");
    expect(state.resyncRequired[threadId]).toBeUndefined();

    state = apply(state, event("tool/started", 5, { callId: "call_two", ordinal: 1 }));
    expect(state.items[secondTool?.itemId ?? ""]?.metadata?.presentation?.status).toBe("running");
    state = apply(
      state,
      event("tool/batch-committed", 6, {
        results: [
          {
            callId: "call_two",
            outcome: "succeeded",
            ordinal: 1,
            presentation: presentation("read", "success", { outputPreview: "b" }),
          },
        ],
      }),
    );
    expect(state.items[secondTool?.itemId ?? ""]?.status).toBe("completed");
    expect(state.threadRevisionByThread[threadId]).toBe(6);
  });

  it("未知或终态后的 started 只触发权威重同步，绝不把 Tool 降级为 running", () => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_step",
        text: "one tool",
        modelRound: 1,
        toolCalls: [
          {
            callId: "call_one",
            toolName: "read_file",
            ordinal: 0,
            presentation: presentation("read"),
          },
        ],
      }),
    );
    const prepared = Object.values(state.items).find(
      (item) => item.metadata?.callId === "call_one",
    );
    const beforeUnknown = state.items;
    const unknown = apply(state, event("tool/started", 3, { callId: "call_unknown", ordinal: 0 }));
    expect(unknown.lastOutcome).toBe("resync_required");
    expect(unknown.items).toBe(beforeUnknown);

    state = apply(state, event("tool/started", 3, { callId: "call_one", ordinal: 0 }));
    const firstStartedPresentation = state.items[prepared?.itemId ?? ""]?.metadata?.presentation;
    const duplicateStarted = apply(
      state,
      event("tool/started", 4, { callId: "call_one", ordinal: 0 }),
    );
    expect(duplicateStarted.lastOutcome).toBe("resync_required");
    expect(duplicateStarted.items[prepared?.itemId ?? ""]?.metadata?.presentation).toEqual(
      firstStartedPresentation,
    );
    state = apply(
      state,
      event("tool/batch-committed", 4, {
        results: [
          {
            callId: "call_one",
            outcome: "failed",
            ordinal: 0,
            presentation: presentation("read", "error", { outputPreview: "failed" }),
          },
        ],
      }),
    );
    const terminalPresentation = state.items[prepared?.itemId ?? ""]?.metadata?.presentation;
    const late = apply(state, event("tool/started", 5, { callId: "call_one", ordinal: 0 }));
    expect(late.lastOutcome).toBe("resync_required");
    expect(late.items[prepared?.itemId ?? ""]?.metadata?.presentation).toEqual(
      terminalPresentation,
    );
  });

  it("从权威快照恢复 running Tool，不依赖 started 增量重放", () => {
    const restored = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 3,
        turns: [
          {
            turnId,
            status: "running",
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:01Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [
          {
            itemId: "item_running_tool",
            turnId,
            kind: "tool_call",
            createdAt: "2026-08-18T00:00:01Z",
            callId: "call_running",
            toolName: "read",
            ordinal: 0,
            presentation: presentation("read", "running"),
          },
        ],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );

    expect(restored.lastOutcome).toBe("applied");
    expect(restored.items["item_running_tool"]).toMatchObject({
      status: "in_progress",
      metadata: { presentation: { status: "running" } },
    });
    const settled = apply(
      restored,
      event("tool/batch-committed", 4, {
        results: [
          {
            callId: "call_running",
            outcome: "succeeded",
            ordinal: 0,
            presentation: presentation("read", "success", { outputPreview: "done" }),
          },
        ],
      }),
    );
    expect(settled.lastOutcome).toBe("applied");
    expect(settled.resyncRequired[threadId]).toBeUndefined();
    expect(settled.items["item_running_tool"]).toMatchObject({
      status: "completed",
      metadata: { presentation: { status: "success" } },
    });
  });

  /** 权威快照同时恢复最近 Usage，清除瞬态 Draft，但保留尚未被新 Provider Usage 覆盖的压缩边界。 */
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
            content: [{ type: "text", text: "问题" }],
            attachments: [],
          },
        ],
        inputQueue: null,
        contextUsage: {
          ...requestUsage(2, 52_000, 4_000, 56_000),
          turnId: "turn_snapshot",
          measuredAt: "2026-08-18T00:00:01Z",
        },
        taskActivities: [],
        goalActivities: [],
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
      requestId: "request_2",
      inputTokens: 52_000,
      totalTokens: 56_000,
    });
  });

  /** Snapshot 本身没有压缩字段，重读不能让压缩前的计量重新变成可信上下文长度。 */
  it("保留 Snapshot 前已提交的压缩边界", () => {
    let state = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    state = apply(
      state,
      event("context/compacted", 1, {
        turnId: null,
        compactionId: "cmp_snapshot_boundary",
        checkpointId: "checkpoint_snapshot_boundary",
        trigger: "automatic",
        sourceRevision: 0,
        inputTokensBefore: 48_000,
        inputTokensAfter: 12_000,
        strategyVersion: "ja-context-v1",
        occurredAt: "2026-08-18T00:00:02Z",
      }),
    );
    const restored = applySnapshot(
      state,
      {
        threadId,
        revision: 1,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: {
          ...requestUsage(1, 48_000, 1_000, 49_000),
          turnId: turnId,
          measuredAt: "2026-08-18T00:00:01Z",
        },
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    expect(restored.lastOutcome).toBe("applied");
    expect(restored.contextCompactionByThread[threadId]).toMatchObject({
      phase: "compacted",
      inputTokensAfter: 12_000,
    });
    expect(restored.contextUsageByThread[threadId]).toMatchObject({
      certainty: "unknown",
      inputTokens: null,
      measuredAt: "2026-08-18T00:00:01Z",
    });
  });

  /** 实时请求序号会随 Turn 重置；旧 Turn 的晚到结算只能更新自身内容，不能倒退线程计量。 */
  it.each([1, 10])("按 Turn 身份接纳重置序号并忽略旧计量，旧序号=%s", (oldOrdinal) => {
    let state = readyState();
    state = apply(state, event("turn/state-changed", 1, { from: "queued", to: "running" }));
    state = apply(
      state,
      event("assistant/model-step-committed", 2, {
        messageId: "item_old_usage",
        text: "",
        modelRound: 1,
        usage: requestUsage(oldOrdinal, 40_000, 1_000, 41_000, { modelRound: 1 }),
        toolCalls: [],
      }),
    );
    state = apply(
      state,
      event("turn/state-changed", 3, {
        turnId: "turn_new",
        from: "queued",
        to: "running",
      }),
    );
    state = apply(
      state,
      event("assistant/model-step-committed", 4, {
        turnId: "turn_new",
        messageId: "item_new_usage",
        text: "",
        modelRound: 1,
        usage: requestUsage(1, 12_000, 1_000, 13_000, {
          requestId: "request_new",
          measuredAt: "2026-08-18T00:00:04Z",
        }),
        toolCalls: [],
      }),
    );
    expect(state.lastOutcome).toBe("applied");
    expect(state.contextUsageByThread[threadId]).toMatchObject({
      turnId: "turn_new",
      requestId: "request_new",
      inputTokens: 12_000,
    });
    state = apply(
      state,
      event("assistant/model-step-committed", 5, {
        messageId: "item_old_late_usage",
        text: "",
        modelRound: 2,
        usage: requestUsage(oldOrdinal + 1, 42_000, 1_000, 43_000, { modelRound: 2 }),
        toolCalls: [],
      }),
    );
    expect(state.lastOutcome).toBe("applied");
    expect(state.contextUsageByThread[threadId]).toMatchObject({
      turnId: "turn_new",
      requestId: "request_new",
      inputTokens: 12_000,
    });
  });

  /** Snapshot 请求序号属于各 Turn；普通 UNKNOWN 同请求补齐与压缩后新 Turn 都必须恢复可信计量。 */
  it.each([false, true])("允许新 Snapshot 恢复计量，成功压缩边界=%s", (compacted) => {
    const state = readyState();
    state.contextUsageByThread[threadId] = {
      turnId,
      ...requestUsage(compacted ? 10 : 1, 0, 0, 0),
      certainty: "unknown",
      inputTokens: null,
      outputTokens: null,
      totalTokens: null,
    };
    if (compacted) state.contextUsageInvalidatedAtByThread[threadId] = "2026-08-18T00:00:02Z";
    const restored = applySnapshot(
      state,
      {
        threadId,
        revision: 1,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: {
          ...requestUsage(1, 12_000, 1_000, 13_000),
          turnId: compacted ? "turn_new" : turnId,
          measuredAt: "2026-08-18T00:00:03Z",
        },
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    expect(restored.contextUsageByThread[threadId]).toMatchObject({
      certainty: "known",
      requestOrdinal: 1,
      inputTokens: 12_000,
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
            itemId: "item_attachment_user",
            kind: "user_input",
            createdAt: "2026-08-18T00:00:03Z",
            turnId: "turn_snapshot",
            content: [{ type: "attachment", attachmentId: "att_snapshot" }],
            attachments: [
              {
                attachmentId: "att_snapshot",
                displayName: "设计稿.pdf",
                sizeBytes: 2048,
                mediaKind: "pdf",
                mediaType: "application/pdf",
              },
            ],
          },
        ],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
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
    expect(restored.items["item_attachment_user"]).toMatchObject({
      kind: "user_message",
      attachments: [
        {
          attachmentId: "att_snapshot",
          displayName: "设计稿.pdf",
          sizeBytes: 2048,
          mediaKind: "pdf",
          mediaType: "application/pdf",
        },
      ],
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
            requestedAt: "2026-08-18T00:00:00Z",
            updatedAt: "2026-08-18T00:00:02Z",
            completedAt: "2026-08-18T00:00:02Z",
            errorCode: null,
            changeSet: null,
          },
          {
            turnId: "turn_second",
            status: "failed",
            requestedAt: "2026-08-18T00:01:00Z",
            updatedAt: "2026-08-18T00:01:01Z",
            completedAt: "2026-08-18T00:01:01Z",
            errorCode: "MODEL_UNAVAILABLE",
            changeSet: null,
          },
        ],
        items: [
          {
            itemId: "item_first_user",
            turnId: "turn_first",
            kind: "user_input",
            createdAt: "2026-08-18T00:00:00Z",
            content: [{ type: "text", text: "第一问" }],
            attachments: [],
          },
          {
            itemId: "item_second_user",
            turnId: "turn_second",
            kind: "user_input",
            createdAt: "2026-08-18T00:01:00Z",
            content: [{ type: "text", text: "第二问" }],
            attachments: [],
          },
          {
            itemId: "item_first_final",
            turnId: "turn_first",
            kind: "final_answer",
            createdAt: "2026-08-18T00:00:02Z",
            text: "第一问已完成",
          },
          {
            itemId: "item_second_prior_answer",
            turnId: "turn_second",
            kind: "final_answer",
            createdAt: "2026-08-18T00:00:59Z",
            text: "此前排队输入已处理",
          },
          {
            itemId: "item_second_failure_reply",
            turnId: "turn_second",
            kind: "final_answer",
            createdAt: "2026-08-18T00:01:01Z",
            text: "本轮未能完成，请稍后重试。",
          },
        ],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );

    expect(restored.items["item_first_user"]?.turnId).toBe("turn_first");
    expect(restored.items["item_second_user"]?.turnId).toBe("turn_second");
    expect(restored.turns["turn_first"]?.status).toBe("completed");
    expect(restored.turns["turn_second"]).toMatchObject({
      status: "failed",
      error: { code: "MODEL_UNAVAILABLE", retryable: true },
    });
    expect(restored.items["item_first_final"]?.metadata?.failureReply).toBeUndefined();
    expect(restored.items["item_second_prior_answer"]?.metadata?.failureReply).toBeUndefined();
    expect(restored.items["item_second_failure_reply"]?.metadata?.failureReply).toBe(true);
  });

  it("按 queue revision 收敛乱序全量事件，并在消费 revision 原子迁移消息", () => {
    let state = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    state = applyTurnAccepted(state, {
      threadId,
      turnId,
      threadRevision: 1,
      submittedText: "开始",
      submittedAt: "2026-09-01T00:00:00Z",
    });
    state = apply(state, event("turn/state-changed", 2, { from: "queued", to: "running" }));
    const queuedInput = {
      inputId: "input_follow",
      turnId,
      content: [{ type: "text" as const, text: "补充" }],
      attachments: [],
      kind: "follow_up" as const,
      status: "pending" as const,
      issue: null,
      inputRevision: 1,
      createdAt: "2026-09-01T00:00:01Z",
    };
    const queueEvent = (revision: number, kind: "follow_up" | "steering") => ({
      jsonrpc: "2.0",
      method: "turn/input-queue-changed",
      params: {
        serverInstanceId,
        eventId: `evt_queue_${revision}`,
        sequence: revision + 10,
        occurredAt: "2026-09-01T00:00:01Z",
        generation: 1,
        workspaceId: "ws_one",
        threadId,
        turnId,
        inputQueue: {
          turnId,
          revision,
          accepting: true,
          items: [{ ...queuedInput, kind }],
        },
      },
    });
    state = apply(state, queueEvent(1, "follow_up"));
    state = apply(state, queueEvent(3, "steering"));
    state = apply(state, queueEvent(2, "follow_up"));
    expect(state.inputQueueByTurn[turnId]).toMatchObject({
      revision: 3,
      items: [{ kind: "steering" }],
    });

    state = apply(state, {
      jsonrpc: "2.0",
      method: "turn/input-consumed",
      params: {
        serverInstanceId,
        eventId: "evt_input_consumed",
        sequence: 20,
        occurredAt: "2026-09-01T00:00:02Z",
        generation: 1,
        workspaceId: "ws_one",
        threadId,
        turnId,
        threadRevision: 3,
        input: { ...queuedInput, kind: "steering" },
        userItem: {
          itemId: "item_follow",
          createdAt: "2026-09-01T00:00:02Z",
          turnId,
          kind: "user_input",
          content: [{ type: "text", text: "补充" }],
          attachments: [],
        },
        inputQueue: { turnId, revision: 4, accepting: true, items: [] },
        assistantSettlement: {
          messageId: "item_answer_round_1",
          text: "第一轮答复",
          modelRound: 1,
          usage: requestUsage(1, 4, 2, 6),
        },
      },
    });
    expect(state.threadRevisionByThread[threadId]).toBe(3);
    expect(state.inputQueueByTurn[turnId]).toMatchObject({ revision: 4, items: [] });
    expect(state.items["item_answer_round_1"]).toMatchObject({
      kind: "agent_message",
      text: "第一轮答复",
    });
    expect(state.items["item_follow"]).toMatchObject({ kind: "user_message", text: "补充" });
  });

  /**
   * 首轮图片在 ACK 后不能依赖已删除的本地 submission；终态继续保留临时投影，随后 thread/read
   * 用服务端 user_input 原子替换它，避免重载前消失或重载后重复。
   */
  it("retains an accepted image through terminal settlement and snapshot reload", () => {
    const attachment = {
      attachmentId: "att_history_image",
      displayName: "历史截图.png",
      sizeBytes: 4096,
      mediaKind: "image" as const,
      mediaType: "image/png",
    };
    let state = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    state = applyTurnAccepted(state, {
      threadId,
      turnId,
      threadRevision: 1,
      submittedText: "分析这张图",
      submittedAttachments: [attachment],
      submittedAt: "2026-09-08T00:00:00Z",
    });
    expect(state.items["item_local_one"]).toMatchObject({
      kind: "user_message",
      text: "分析这张图",
      attachments: [attachment],
    });

    state = apply(
      state,
      event("turn/state-changed", 2, { sequence: 1, from: "queued", to: "running" }),
    );
    state = apply(
      state,
      event("turn/terminal", 3, {
        sequence: 2,
        state: "failed",
        summary: "上下文超限",
        errorCode: "CONTEXT_LIMIT",
      }),
    );
    expect(state.items["item_local_one"]?.attachments).toEqual([attachment]);

    state = applySnapshot(
      state,
      {
        threadId,
        revision: 3,
        turns: [
          {
            turnId,
            status: "failed",
            requestedAt: "2026-09-08T00:00:00Z",
            updatedAt: "2026-09-08T00:00:02Z",
            completedAt: "2026-09-08T00:00:02Z",
            errorCode: "CONTEXT_LIMIT",
            changeSet: {
              state: "complete",
              incompleteReasons: [],
              files: [],
              stats: {
                files: 0,
                additions: 0,
                deletions: 0,
                binaryFiles: 0,
                truncated: false,
              },
            },
          },
        ],
        items: [
          {
            itemId: "item_persisted_user",
            turnId,
            kind: "user_input",
            content: [
              { type: "attachment", attachmentId: attachment.attachmentId },
              { type: "text", text: "分析这张图" },
            ],
            attachments: [attachment],
            createdAt: "2026-09-08T00:00:00Z",
          },
        ],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );

    expect(state.lastOutcome).toBe("applied");
    expect(state.items["item_local_one"]).toBeUndefined();
    expect(state.items["item_persisted_user"]?.attachments).toEqual([attachment]);
    expect(
      state.itemIdsByThread[threadId]?.filter(
        (itemId) => state.items[itemId]?.kind === "user_message",
      ),
    ).toEqual(["item_persisted_user"]);
  });

  /** Mailbox 批次只进入目标 Timeline 的独立消息事实，不能伪造用户输入、Task Activity 或额外卡片。 */
  it("projects received thread messages in batch order and deduplicates event identity", () => {
    const messages = [
      {
        itemId: "item_message_first",
        createdAt: "2026-09-09T00:00:01Z",
        turnId,
        kind: "thread_message" as const,
        sourceThreadId: "thr_source_one",
        sourceTitle: "来源会话",
        content: "第一条 **原文**",
      },
      {
        itemId: "item_message_second",
        createdAt: "2026-09-09T00:00:01Z",
        turnId,
        kind: "thread_message" as const,
        sourceThreadId: "thr_source_two",
        sourceTitle: "另一个来源",
        content: "第二条",
      },
    ];
    const firstMessage = messages[0];
    if (firstMessage === undefined) throw new Error("消息 fixture 缺少首项");
    let state = applySnapshot(
      readyState(),
      {
        threadId,
        revision: 1,
        turns: [
          {
            turnId,
            status: "running" as const,
            requestedAt: "2026-09-09T00:00:00Z",
            updatedAt: "2026-09-09T00:00:00Z",
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
        nextCursor: null,
      },
      "ws_one",
    );
    state = apply(
      state,
      event("turn/messages_received", 2, {
        sequence: 12,
        items: messages,
      }),
    );

    expect(state.lastOutcome).toBe("applied");
    expect(state.itemIdsByThread[threadId]).toEqual(messages.map((message) => message.itemId));
    expect(state.items[firstMessage.itemId]).toMatchObject({
      kind: "thread_message",
      text: firstMessage.content,
      sourceThreadId: firstMessage.sourceThreadId,
      sourceTitle: firstMessage.sourceTitle,
    });
    expect(Object.values(state.items).filter((item) => item.kind === "user_message")).toHaveLength(
      0,
    );
    expect(state.taskActivitiesByRootThread[threadId]).toEqual([]);
    expect(state.inputQueueByTurn[turnId]).toBeUndefined();

    const malformed = applyEventValue(
      state,
      event("turn/messages_received", 3, {
        sequence: 13,
        items: [{ ...firstMessage, sourceThreadId: "thr_../escape" }],
      }),
    );
    expect(malformed.lastOutcome).toBe("invalid");

    const duplicate = apply(
      state,
      event("turn/messages_received", 2, { sequence: 12, items: messages }),
    );
    expect(duplicate.lastOutcome).toBe("duplicate");
    expect(duplicate.itemIdsByThread[threadId]).toEqual(messages.map((message) => message.itemId));

    const conflicting = apply(
      state,
      event("turn/messages_received", 3, {
        sequence: 13,
        items: [{ ...firstMessage, content: "不应覆盖原文" }],
      }),
    );
    expect(conflicting.lastOutcome).toBe("resync_required");
    expect(conflicting.items[firstMessage.itemId]?.text).toBe(firstMessage.content);
  });
});
