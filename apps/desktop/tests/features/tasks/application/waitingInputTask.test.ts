// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { taskSection, taskStateLabel, type TaskSummary } from "@/features/tasks/domain/taskModel";

describe("waiting task recovery", () => {
  /** 澄清等待需要用户动作，重载后的任务必须保持在待处理分组并说明下一步。 */
  it("keeps a persisted clarification visible as needing attention", () => {
    const task: TaskSummary = {
      taskThreadId: "thr_side",
      parentThreadId: "thr_root",
      rootThreadId: "thr_root",
      originTurnId: null,
      taskName: "Review",
      depth: 1,
      taskKind: "side_task",
      lifecycle: "independent",
      state: "waiting_input",
      revision: 3,
      latestActivitySequence: 3,
      unreadCount: 1,
      descendantCount: 0,
      runningDescendantCount: 0,
      needsAttentionCount: 1,
      latestSafeSummary: "Waiting for an answer",
      startedAt: "2026-09-03T08:00:00Z",
      completedAt: null,
      updatedAt: "2026-09-03T08:00:02Z",
    };

    expect(taskSection(task)).toBe("attention");
    expect(taskStateLabel(task.state)).toBe("等待回答");
  });
});
