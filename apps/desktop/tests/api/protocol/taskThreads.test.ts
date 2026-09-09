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
          content,
        },
        { accepted: true, task, turnId: "turn_side_1" },
      ],
      ["task/list", { rootThreadId: "thr_root" }, { items: [task] }],
      [
        "task/read",
        { taskThreadId: "thr_side_1", limit: 50 },
        {
          task,
          contextSeed: {
            contextSeedId: "seed_side_1",
            parentRevision: 7,
            inheritanceMode: "effective_context",
            taskBrief: content,
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
        "task/message/send",
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
    expect(() => parseMethodResult("task/read", readResult)).toThrow();
    expect(() =>
      parseMethodResult("task/message/send", {
        accepted: true,
        messageId: "msg_note",
        mailboxSequence: 0,
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
      parseMethodParams("task/message/send", {
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
});
