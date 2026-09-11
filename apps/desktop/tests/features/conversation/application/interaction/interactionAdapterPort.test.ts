// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import { createInteractionPort } from "@/features/conversation/application/interactionAdapterPort";
import type { InteractionSnapshot } from "@/features/conversation/application/interactionPort";

const snapshot: InteractionSnapshot = {
  threadId: "thr_one",
  eventSequence: 1,
  request: null,
  draft: null,
  resumeState: "none",
};

/** 通过真实 application adapter 验证内部状态元数据不会穿透 JA-RPC wire 边界。 */
function createAdapter() {
  return {
    read: vi.fn(async () => snapshot),
    observe: vi.fn(async () => ({ ...snapshot, observationId: "obs_one" })),
    unobserve: vi.fn(async () => undefined),
    draftSave: vi.fn(async () => snapshot),
    respond: vi.fn(async () => snapshot),
    cancel: vi.fn(async () => snapshot),
  };
}

describe("createInteractionPort", () => {
  it("只向 draftSave 透传协议字段，不携带保存队列的竞态元数据", async () => {
    const adapter = createAdapter();
    const events = { subscribe: vi.fn(() => () => undefined) };
    const port = createInteractionPort(adapter, events);
    const input = {
      threadId: "thr_one",
      requestId: "req_one",
      expectedDraftRevision: 4,
      answers: [
        {
          questionId: "q_one",
          optionIds: ["option_one"],
          freeText: null,
          skipped: false,
          editRevision: 9,
        },
      ],
      page: 1,
      collapsed: false,
      idempotencyKey: "draft_one",
      scopeEpoch: 3,
      editRevision: 9,
    } as unknown as Parameters<typeof port.saveDraft>[0];
    await port.saveDraft(input);

    expect(adapter.draftSave).toHaveBeenCalledWith({
      threadId: "thr_one",
      requestId: "req_one",
      expectedDraftRevision: 4,
      answers: [
        {
          questionId: "q_one",
          optionIds: ["option_one"],
          freeText: null,
          skipped: false,
        },
      ],
      page: 1,
      collapsed: false,
      idempotencyKey: "draft_one",
    });
  });
});
