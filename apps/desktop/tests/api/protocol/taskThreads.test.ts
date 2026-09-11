// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { parseMethodParams, parseMethodResult } from "@/api/protocol/methods";
import { parseEvent } from "@/api/protocol/protocol";

const task = {
  taskThreadId: "thr_side_1",
  parentThreadId: "thr_root",
  rootThreadId: "thr_root",
  originTurnId: "turn_parent",
  taskName: "Inspect parser",
  depth: 1,
  taskKind: "side_task",
  lifecycle: "independent",
  state: "running",
  revision: 3,
  latestActivitySequence: 2,
  unreadCount: 0,
  descendantCount: 0,
  runningDescendantCount: 0,
  needsAttentionCount: 0,
  latestSafeSummary: "Inspecting parser.",
  startedAt: "2026-09-03T08:00:00Z",
  completedAt: null,
  updatedAt: "2026-09-03T08:00:01Z",
} as const;

const content = [{ type: "text", text: "Inspect the parser boundary." }] as const;

describe("JA-RPC v1 Task Threads", () => {
  /** 十个方法分别锁定 QueueOnly、Follow-up 唤醒和显式整树删除所需的严格字段。 */
  it("accepts the complete task method and result surface", () => {
    const cases = [
      [
        "task/create",
        {
          parentThreadId: "thr_root",
          parentTurnId: "turn_parent",
          expectedParentRevision: 7,
          taskName: "Inspect parser",
          preferences: {
            providerId: "provider_side",
            modelId: "model_side",
            reasoningLevel: "high",
            accessMode: "full_access",
            collaborationMode: "plan",
          },
        },
        { accepted: true, task },
      ],
      ["task/list", { rootThreadId: "thr_root" }, { items: [task] }],
      [
        "task/read",
        { taskThreadId: "thr_side_1", limit: 50 },
        {
          task,
          thread: {
            threadId: "thr_side_1",
            workspaceId: "ws_root",
            activeGoalId: null,
            preferences: {
              providerId: "provider_side",
              modelId: "model_side",
              reasoningLevel: "high",
              accessMode: "full_access",
              collaborationMode: "plan",
              titleSource: "placeholder",
            },
            title: "Investigate parser",
            status: "active",
            pinned: false,
            latestTurnStatus: "running",
            latestTurnSeen: false,
            revision: 3,
            createdAt: "2026-09-03T08:00:00Z",
            updatedAt: "2026-09-03T08:00:01Z",
          },
          contextSeed: {
            contextSeedId: "seed_side_1",
            parentRevision: 7,
            inheritanceMode: "effective_context",
            taskBrief: null,
            inheritedContextSummary: "Frozen parent context.",
            inheritedContextPreview: [
              { role: "user", text: "Inspect the parser boundary.", attachmentIds: [] },
            ],
            fingerprint: "a".repeat(64),
            createdAt: "2026-09-03T08:00:00Z",
          },
          activities: [],
          mailbox: [],
          nextCursor: null,
        },
      ],
      [
        "task/observe",
        { taskThreadId: "thr_side_1", expectedTaskRevision: 3 },
        { observationId: "observe_side_1", taskThreadId: "thr_side_1", revision: 3 },
      ],
      ["task/unobserve", { observationId: "observe_side_1" }, { accepted: true }],
      [
        "task/seen",
        {
          taskThreadId: "thr_side_1",
          expectedTaskRevision: 3,
          throughActivitySequence: 2,
        },
        { accepted: true, task },
      ],
      [
        "thread/message/send",
        {
          senderThreadId: "thr_root",
          targetThreadId: "thr_side_1",
          content,
          idempotencyKey: "message-1",
        },
        { accepted: true, messageId: "msg_note", mailboxSequence: 2 },
      ],
      [
        "task/followup",
        {
          senderThreadId: "thr_root",
          targetThreadId: "thr_side_1",
          content,
          idempotencyKey: "followup-1",
          expectedTaskRevision: 3,
        },
        { accepted: true, messageId: "msg_followup", turnId: "turn_side_2", task },
      ],
      [
        "task/cancel",
        { taskThreadId: "thr_side_1", expectedTaskRevision: 3 },
        { accepted: true, task },
      ],
      [
        "task/tree/delete",
        {
          taskThreadId: "thr_side_1",
          expectedTaskRevision: 3,
          confirmTaskThreadId: "thr_side_1",
        },
        { accepted: true, deletedTaskCount: 1 },
      ],
    ] as const;

    for (const [method, params, result] of cases) {
      expect(parseMethodParams(method, params)).toEqual(params);
      expect(parseMethodResult(method, result)).toEqual(result);
    }
  });

  /** 侧边任务偏好必须完整闭合，避免服务端先创建再补写模型或权限。 */
  it("rejects incomplete task create preferences", () => {
    expect(() =>
      parseMethodParams("task/create", {
        parentThreadId: "thr_root",
        parentTurnId: null,
        expectedParentRevision: 7,
        taskName: "Inspect parser",
        preferences: {
          providerId: "provider_side",
          modelId: "model_side",
          reasoningLevel: null,
          accessMode: "approval_required",
        },
      }),
    ).toThrow();
  });

  /** 类型与生命周期构成一个不可拆分判别对，防止侧边任务误随父 Turn 取消。 */
  it("rejects invalid task kind and lifecycle pairs", () => {
    expect(() =>
      parseMethodResult("task/list", { items: [{ ...task, lifecycle: "attached" }] }),
    ).toThrow();
    expect(() =>
      parseMethodResult("task/list", {
        items: [{ ...task, taskKind: "subagent", lifecycle: "independent" }],
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("task/list", {
        items: [{ ...task, taskKind: "subagent", lifecycle: "attached", originTurnId: null }],
      }),
    ).toThrow();
  });

  /** 树结果必须完整连根且 depth 可由父节点证明，不能由服务端任意声明。 */
  it("rejects duplicate, detached, and self-referencing task trees", () => {
    expect(() => parseMethodResult("task/list", { items: [task, task] })).toThrow();
    expect(() =>
      parseMethodResult("task/list", {
        items: [{ ...task, depth: 2, parentThreadId: "thr_missing" }],
      }),
    ).toThrow();
    expect(() =>
      parseMethodResult("task/list", { items: [{ ...task, parentThreadId: task.taskThreadId }] }),
    ).toThrow();
  });

  /** Task cursor 使用活动与 Mailbox 双序号，显式 null 和通用 opaque cursor 都被拒绝。 */
  it("accepts only the task cursor grammar", () => {
    expect(
      parseMethodParams("task/read", { taskThreadId: "thr_side_1", cursor: "task:2:3" }),
    ).toEqual({
      taskThreadId: "thr_side_1",
      cursor: "task:2:3",
    });
    expect(() =>
      parseMethodParams("task/read", { taskThreadId: "thr_side_1", cursor: null }),
    ).toThrow();
    expect(() =>
      parseMethodParams("task/read", { taskThreadId: "thr_side_1", cursor: "opaque_cursor" }),
    ).toThrow();
  });

  /** BRIEF_ONLY 禁止携带继承预览，且消息 ACK 的持久序号从 1 开始。 */
  it("rejects inconsistent context seeds and zero mailbox acknowledgements", () => {
    const readResult = {
      task,
      thread: {
        threadId: "thr_other",
        workspaceId: "ws_root",
        activeGoalId: null,
        preferences: null,
        title: "Other thread",
        status: "active",
        pinned: false,
        latestTurnStatus: null,
        latestTurnSeen: true,
        revision: 1,
        createdAt: "2026-09-03T08:00:00Z",
        updatedAt: "2026-09-03T08:00:00Z",
      },
      contextSeed: {
        contextSeedId: "seed_side_1",
        parentRevision: 7,
        inheritanceMode: "brief_only",
        taskBrief: content,
        inheritedContextSummary: null,
        inheritedContextPreview: [{ role: "user", text: "leak", attachmentIds: [] }],
        fingerprint: "a".repeat(64),
        createdAt: "2026-09-03T08:00:00Z",
      },
      activities: [],
      mailbox: [],
      nextCursor: null,
    };
    const mismatchedThreadResult = {
      ...readResult,
      contextSeed: {
        ...readResult.contextSeed,
        inheritanceMode: "effective_context",
        taskBrief: null,
        inheritedContextSummary: "Frozen parent context.",
        inheritedContextPreview: [],
      },
    };
    expect(() => parseMethodResult("task/read", mismatchedThreadResult)).toThrow();
    expect(() => parseMethodResult("task/read", readResult)).toThrow();
    expect(() =>
      parseMethodResult("thread/message/send", {
        accepted: true,
        messageId: "msg_note",
        mailboxSequence: 0,
      }),
    ).toThrow();
  });

  /** thread/read 按 owner 接纳侧聊的直接 Subagent，但禁止独立侧聊自身回流到主 Timeline。 */
  it("validates task activity owner separately from global lineage root", () => {
    const childTask = {
      ...task,
      taskKind: "subagent" as const,
      lifecycle: "attached" as const,
      originTurnId: "turn_parent",
    };
    const childActivity = {
      activitySequence: 2,
      activityId: "activity_child_snapshot",
      rootThreadId: "thr_root",
      taskThreadId: childTask.taskThreadId,
      actorThreadId: "thr_root",
      causalTurnId: "turn_parent",
      kind: "progress" as const,
      summary: { text: childTask.latestSafeSummary },
      createdAt: childTask.updatedAt,
    };
    const snapshot = {
      threadId: "thr_root",
      revision: 3,
      turns: [],
      items: [],
      taskActivities: [{ activity: childActivity, task: childTask }],
      goalActivities: [],
      inputQueue: null,
      contextUsage: null,
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);

    const sideSnapshot = {
      ...snapshot,
      threadId: "thr_side",
      taskActivities: [
        {
          activity: { ...childActivity, activityId: "activity_side_child_snapshot" },
          task: { ...childTask, parentThreadId: "thr_side" },
        },
      ],
    };
    expect(parseMethodResult("thread/read", sideSnapshot)).toEqual(sideSnapshot);

    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        taskActivities: [
          {
            activity: {
              ...childActivity,
              activityId: "activity_side_leak",
            },
            task: {
              ...task,
              originTurnId: null,
              taskKind: "side_task",
              lifecycle: "independent",
            },
          },
        ],
      }),
    ).toThrow();
  });

  /** 删除确认必须精确重复目标 identity；未知字段不能扩大 QueueOnly 或观察权限。 */
  it("fails closed on mismatched delete confirmation and task extensions", () => {
    expect(() =>
      parseMethodParams("task/tree/delete", {
        taskThreadId: "thr_side_1",
        expectedTaskRevision: 3,
        confirmTaskThreadId: "thr_other",
      }),
    ).toThrow();
    expect(() =>
      parseMethodParams("thread/message/send", {
        senderThreadId: "thr_root",
        targetThreadId: "thr_side_1",
        content,
        idempotencyKey: "message-1",
        wake: true,
      }),
    ).toThrow();
  });

  /** 三个事件保持 durable activity、可丢 progress 和 mailbox invalidation 的独立形状。 */
  it("accepts the three strict task events and rejects raw progress", () => {
    const base = {
      serverInstanceId: "srv_demo",
      sequence: 1,
      occurredAt: "2026-09-03T08:00:02Z",
      generation: 1,
      rootThreadId: "thr_root",
      taskThreadId: "thr_side_1",
      taskRevision: 3,
    } as const;
    expect(
      parseEvent({
        jsonrpc: "2.0",
        method: "task/activity",
        params: {
          ...base,
          eventId: "evt_task_activity",
          activity: {
            activitySequence: 2,
            activityId: "activity_progress",
            rootThreadId: "thr_root",
            taskThreadId: "thr_side_1",
            actorThreadId: "thr_root",
            causalTurnId: "turn_side_1",
            kind: "progress",
            summary: { text: task.latestSafeSummary },
            createdAt: "2026-09-03T08:00:02Z",
          },
          task,
        },
      }).method,
    ).toBe("task/activity");
    expect(
      parseEvent({
        jsonrpc: "2.0",
        method: "task/progress",
        params: {
          ...base,
          eventId: "evt_task_progress",
          observationId: "observe_side_1",
          progressRevision: 4,
          safeSummary: "Running.",
        },
      }).method,
    ).toBe("task/progress");
    expect(
      parseEvent({
        jsonrpc: "2.0",
        method: "task/mailbox-changed",
        params: {
          ...base,
          eventId: "evt_task_mailbox",
          mailboxSequence: 2,
          unreadCount: 1,
        },
      }).method,
    ).toBe("task/mailbox-changed");
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "task/progress",
        params: {
          ...base,
          eventId: "evt_task_raw",
          observationId: "observe_side_1",
          progressRevision: 4,
          safeSummary: "Running.",
          rawReasoning: "hidden",
        },
      }),
    ).toThrow();
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "task/activity",
        params: {
          ...base,
          eventId: "evt_task_mismatch",
          activity: {
            activitySequence: 1,
            activityId: "activity_mismatch",
            rootThreadId: "thr_root",
            taskThreadId: "thr_side_1",
            actorThreadId: "thr_root",
            causalTurnId: null,
            kind: "progress",
            summary: { text: "old" },
            createdAt: "2026-09-03T08:00:02Z",
          },
          task,
        },
      }),
    ).toThrow();
  });

  /** 会话消息只允许来源身份与纯文本正文；Snapshot/Event 必须共享目标 Turn，批次 identity 不能重复。 */
  it("validates thread messages in snapshots and mailbox events", () => {
    const threadMessage = {
      itemId: "item_message_one",
      createdAt: "2026-09-03T08:00:03Z",
      turnId: "turn_target",
      kind: "thread_message" as const,
      sourceThreadId: "thr_source_1",
      sourceTitle: "主会话",
      content: "只作为外部会话内容处理",
    };
    const snapshot = {
      threadId: "thr_target_1",
      revision: 4,
      turns: [
        {
          turnId: threadMessage.turnId,
          status: "running" as const,
          requestedAt: "2026-09-03T08:00:00Z",
          updatedAt: threadMessage.createdAt,
          completedAt: null,
          errorCode: null,
          changeSet: null,
        },
      ],
      items: [threadMessage],
      taskActivities: [],
      goalActivities: [],
      inputQueue: null,
      contextUsage: null,
      nextCursor: null,
    };
    expect(parseMethodResult("thread/read", snapshot)).toEqual(snapshot);

    const eventParams = {
      serverInstanceId: "srv_messages",
      eventId: "evt_messages_received",
      sequence: 8,
      occurredAt: threadMessage.createdAt,
      generation: 1,
      workspaceId: "ws_target",
      threadId: snapshot.threadId,
      turnId: threadMessage.turnId,
      threadRevision: 5,
      items: [threadMessage],
    };
    expect(
      parseEvent({ jsonrpc: "2.0", method: "turn/messages_received", params: eventParams }),
    ).toMatchObject({ method: "turn/messages_received", params: eventParams });

    expect(() =>
      parseMethodResult("thread/read", {
        ...snapshot,
        items: [{ ...threadMessage, turnId: "turn_other" }],
      }),
    ).not.toThrow();
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "turn/messages_received",
        params: { ...eventParams, items: [{ ...threadMessage, turnId: "turn_other" }] },
      }),
    ).toThrow();
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "turn/messages_received",
        params: {
          ...eventParams,
          items: [threadMessage, { ...threadMessage, itemId: "item_message_two" }],
        },
      }),
    ).not.toThrow();
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "turn/messages_received",
        params: {
          ...eventParams,
          items: [threadMessage, { ...threadMessage, itemId: threadMessage.itemId }],
        },
      }),
    ).toThrow();
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "turn/messages_received",
        params: {
          ...eventParams,
          items: [{ ...threadMessage, sourceThreadId: "not_a_thread" }],
        },
      }),
    ).toThrow();
    expect(() =>
      parseEvent({
        jsonrpc: "2.0",
        method: "turn/messages_received",
        params: {
          ...eventParams,
          items: [{ ...threadMessage, sourceTitle: "   " }],
        },
      }),
    ).toThrow();
  });
});
