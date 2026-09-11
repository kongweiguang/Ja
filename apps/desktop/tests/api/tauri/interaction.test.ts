// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { JA_INTERACTION_COMMANDS, TauriInteractionAdapter } from "@/api/tauri/interaction";

const snapshot = {
  threadId: "thr_demo",
  eventSequence: 2,
  resumeState: "waiting_for_answer",
  request: {
    requestId: "interaction_demo",
    threadId: "thr_demo",
    turnId: "turn_demo",
    toolCallId: "call_demo",
    planRevisionId: null,
    runId: null,
    goalId: null,
    status: "pending",
    revision: 1,
    questions: [
      {
        questionId: "question_mode",
        prompt: "选择模式",
        type: "single",
        required: true,
        allowFreeText: false,
        options: [
          { optionId: "option_safe", label: "安全", description: "只读", recommended: true },
        ],
      },
    ],
    answers: [],
    createdAt: "2026-09-10T00:00:00Z",
    updatedAt: "2026-09-10T00:00:00Z",
  },
  draft: null,
};

function bridge(result: unknown) {
  return { invoke: vi.fn().mockResolvedValue(result), listen: vi.fn() };
}

describe("TauriInteractionAdapter", () => {
  it("reads the active request without synthesizing an ID", async () => {
    const native = bridge(snapshot);
    const adapter = new TauriInteractionAdapter(native);

    await adapter.read({ threadId: "thr_demo" });

    expect(native.invoke).toHaveBeenCalledWith(JA_INTERACTION_COMMANDS.read, {
      input: { threadId: "thr_demo" },
    });
  });

  it("submits answers through the typed respond command", async () => {
    const native = bridge(snapshot);
    const adapter = new TauriInteractionAdapter(native);

    await adapter.respond({
      threadId: "thr_demo",
      requestId: "interaction_demo",
      expectedRevision: 1,
      idempotencyKey: "interaction:respond:1",
      answers: [
        { questionId: "question_mode", optionIds: ["option_safe"], freeText: null, skipped: false },
      ],
    });

    expect(native.invoke).toHaveBeenCalledWith(JA_INTERACTION_COMMANDS.respond, {
      input: expect.objectContaining({ requestId: "interaction_demo", expectedRevision: 1 }),
    });
  });

  it("rejects an invalid request before invoking native", async () => {
    const native = bridge(snapshot);
    const adapter = new TauriInteractionAdapter(native);

    await expect(
      adapter.cancel({
        threadId: "wrong",
        requestId: "input_demo",
        expectedRevision: 1,
        idempotencyKey: "interaction:cancel:1",
      }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    expect(native.invoke).not.toHaveBeenCalled();
  });
});
