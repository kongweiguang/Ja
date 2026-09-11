// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { JA_GOAL_COMMANDS, TauriGoalAdapter } from "@/api/tauri/goals";
import { PlanSchema } from "@/api/protocol/goal";

function bridge(result: unknown) {
  return { invoke: vi.fn().mockResolvedValue(result), listen: vi.fn() };
}

describe("TauriGoalAdapter plan recovery", () => {
  /** 停止保留执行身份，确保实时事件与完整 ACK 都能通过相同的生产校验。 */
  it("accepts stopped plans retaining their frozen run and revision", () => {
    const stopped = {
      planId: "plan_demo",
      owner: { kind: "thread", threadId: "thr_demo" },
      objective: "计划",
      status: "stopped",
      revision: 4,
      activePlanRevisionId: "planrev_demo",
      activeRunId: "run_demo",
      createdAt: "2026-09-10T00:00:00Z",
      updatedAt: "2026-09-10T00:01:00Z",
    };
    expect(PlanSchema.parse(stopped)).toEqual(stopped);
    expect(PlanSchema.safeParse({ ...stopped, status: "draft" }).success).toBe(false);
  });
  it("reads the latest plan by thread and preserves an empty current projection", async () => {
    const native = bridge({ current: null });
    const adapter = new TauriGoalAdapter(native);

    await expect(adapter.currentPlanRead({ threadId: "thr_demo" })).resolves.toEqual({
      current: null,
    });
    expect(native.invoke).toHaveBeenCalledWith(JA_GOAL_COMMANDS.currentPlanRead, {
      input: { threadId: "thr_demo" },
    });
  });

  it("rejects malformed thread identity before invoking native", async () => {
    const native = bridge({ current: null });
    const adapter = new TauriGoalAdapter(native);

    await expect(adapter.currentPlanRead({ threadId: "plan_demo" })).rejects.toMatchObject({
      code: "INVALID_INPUT",
    });
    expect(native.invoke).not.toHaveBeenCalled();
  });
});
