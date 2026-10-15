// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  JA_HISTORY_COMMANDS,
  TauriHistoryAdapter,
  type HistoryNativeBridge,
} from "@/api/tauri/history";
import { exportConversationMarkdown, type TimelineSnapshot } from "@/features/conversation";

/** 生产 adapter 的正文 reader 依赖 `this.bridge`；组合层回归需覆盖真实 adapter 到导出的调用链。 */
describe("conversation export adapter composition", () => {
  /** 整段导出保留 adapter receiver；快照显式携带 null 活动压缩字段满足严格 IPC 契约。 */
  it("exports user and final text through a real history adapter instance", async () => {
    const createdAt = "2026-10-06T00:00:00Z";
    const snapshot: TimelineSnapshot = {
      threadId: "thr_export",
      revision: 7,
      turns: [],
      items: [
        {
          origin: "native" as const,
          itemId: "item_question",
          turnId: "turn_export",
          kind: "user_input",
          createdAt,
          content: [{ type: "text", text: "问题" }],
          attachments: [],
          totalCharacters: 2,
          truncated: false,
        },
        {
          origin: "native" as const,
          itemId: "item_final",
          turnId: "turn_export",
          kind: "final_answer",
          createdAt,
          text: "回复",
          totalCharacters: 2,
          truncated: false,
        },
      ],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      liveStream: null,
      contextCompaction: null,
      nextCursor: null,
    };
    const invoke = vi.fn(async (command: string, args?: Record<string, unknown>) => {
      if (command === JA_HISTORY_COMMANDS.threadRead) return snapshot;
      if (command === JA_HISTORY_COMMANDS.itemContentRead) {
        const input = args?.["input"] as { itemId: string };
        return {
          itemId: input.itemId,
          offsetCharacters: 0,
          nextOffsetCharacters: null,
          totalCharacters: 2,
          truncated: false,
          content: input.itemId === "item_question" ? "问题" : "回复",
        };
      }
      throw new Error(`unexpected history command: ${command}`);
    });
    const adapter = new TauriHistoryAdapter({
      invoke: invoke as unknown as HistoryNativeBridge["invoke"],
    });

    const result = await exportConversationMarkdown(
      adapter as unknown as Parameters<typeof exportConversationMarkdown>[0],
      { threadId: "thr_export", title: "导出回归" },
    );

    expect(result.markdown).toContain("## 用户 · 2026-10-06T00:00:00Z\n\n问题");
    expect(result.markdown).toContain("## 助手 · 2026-10-06T00:00:00Z\n\n回复");
    expect(
      invoke.mock.calls.filter(([command]) => command === JA_HISTORY_COMMANDS.itemContentRead),
    ).toHaveLength(2);
  });
});
