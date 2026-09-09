// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { parseRuntimeHostEvent } from "@/api/tauri/runtime";

describe("Runtime Task event routing", () => {
  it("把 task/progress 与主 Timeline 分流并保留观察 identity", () => {
    const event = parseRuntimeHostEvent({
      jsonrpc: "2.0",
      method: "task/progress",
      params: {
        serverInstanceId: "srv_12345678",
        eventId: "evt_12345678",
        sequence: 8,
        occurredAt: "2026-09-03T08:00:00Z",
        generation: 2,
        rootThreadId: "thr_root",
        taskThreadId: "thr_child",
        taskRevision: 4,
        observationId: "observe_12345678",
        progressRevision: 6,
        safeSummary: "正在检查合同",
      },
    });
    expect(event).toMatchObject({
      kind: "task",
      event: {
        method: "task/progress",
        params: { taskThreadId: "thr_child", observationId: "observe_12345678" },
      },
    });
  });
});
