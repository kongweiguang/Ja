// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { JA_TASK_COMMANDS, TauriTaskAdapter } from "@/api/tauri/tasks";
import type { RuntimeNativeBridge } from "@/api/tauri/runtime";

const task = {
  taskThreadId: "thr_child",
  parentThreadId: "thr_root",
  rootThreadId: "thr_root",
  originTurnId: "turn_parent",
  taskName: "检查测试",
  depth: 1,
  taskKind: "side_task",
  lifecycle: "independent",
  state: "queued",
  revision: 1,
  latestActivitySequence: 1,
  unreadCount: 0,
  descendantCount: 0,
  runningDescendantCount: 0,
  needsAttentionCount: 0,
  latestSafeSummary: "已创建",
  startedAt: null,
  completedAt: null,
  updatedAt: "2026-09-03T08:00:00Z",
} as const;

/** bridge 记录专用 command 与 envelope，结果仍由生产 Schema 解析。 */
function bridgeWithResult(result: unknown): {
  bridge: RuntimeNativeBridge;
  invoke: ReturnType<typeof vi.fn>;
} {
  const invoke = vi.fn<(command: string, args?: Record<string, unknown>) => Promise<unknown>>(
    async () => result,
  );
  return {
    bridge: {
      invoke: <T>(command: string, args?: Record<string, unknown>) =>
        invoke(command, args) as Promise<T>,
      listen: vi.fn(async () => () => undefined),
    },
    invoke,
  };
}

describe("TauriTaskAdapter", () => {
  it("task/create 使用专用 command 且只发送固定 input envelope", async () => {
    const native = bridgeWithResult({ accepted: true, task, turnId: "turn_child" });
    const adapter = new TauriTaskAdapter(native.bridge);
    await expect(
      adapter.create({
        parentThreadId: "thr_root",
        parentTurnId: null,
        expectedParentRevision: 4,
        taskName: "检查测试",
        content: [{ type: "text", text: "开始" }],
      }),
    ).resolves.toMatchObject({ task: { taskThreadId: "thr_child" } });
    expect(native.invoke).toHaveBeenCalledWith(JA_TASK_COMMANDS.create, {
      input: {
        parentThreadId: "thr_root",
        parentTurnId: null,
        expectedParentRevision: 4,
        taskName: "检查测试",
        content: [{ type: "text", text: "开始" }],
      },
    });
  });

  it("非法 Task 输入在 invoke 前失败关闭", async () => {
    const native = bridgeWithResult({ items: [] });
    const adapter = new TauriTaskAdapter(native.bridge);
    await expect(adapter.list({ rootThreadId: "bad" })).rejects.toMatchObject({
      code: "INVALID_INPUT",
    });
    expect(native.invoke).not.toHaveBeenCalled();
  });

  it("unobserve 校验 accepted ACK 且不会复用 cancel command", async () => {
    const native = bridgeWithResult({ accepted: true });
    const adapter = new TauriTaskAdapter(native.bridge);
    await adapter.unobserve({ observationId: "observe_12345678" });
    expect(native.invoke).toHaveBeenCalledWith(JA_TASK_COMMANDS.unobserve, {
      input: { observationId: "observe_12345678" },
    });
    expect(native.invoke).not.toHaveBeenCalledWith(JA_TASK_COMMANDS.cancel, expect.anything());
  });
});
