// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type { ConversationHistoryPort } from "@/features/conversation/application/ports";
import type { TimelineSnapshot } from "@/features/conversation/domain/timelineContracts";
import { exportConversationMarkdown } from "@/features/conversation/application/exportConversationMarkdown";

function snapshot(revision: number): TimelineSnapshot {
  return {
    threadId: "thread_12345678",
    revision,
    turns: [],
    items: [
      {
        origin: "native" as const,
        itemId: "item_12345678",
        turnId: "turn_12345678",
        kind: "user_input",
        createdAt: "2026-10-06T00:00:00Z",
        truncated: true,
        totalCharacters: 8,
        content: [
          { type: "text", text: "preview" },
          { type: "text", text: "" },
        ],
        attachments: [],
      },
    ],
    inputQueue: null,
    contextUsage: null,
    taskActivities: [],
    liveStream: null,
    nextCursor: null,
  };
}

/** 分页夹具的总字符数按 Unicode 码点计量，与服务端游标单位保持一致。 */
function historyFor(revisions: readonly number[]) {
  let pageIndex = 0;
  const threadRead = vi.fn(async () => {
    const revision = revisions[Math.min(pageIndex, revisions.length - 1)] ?? 1;
    pageIndex += 1;
    return snapshot(revision);
  });
  const itemContentRead = vi.fn(async (input: { offsetCharacters: number }) => ({
    itemId: "item_12345678",
    offsetCharacters: input.offsetCharacters,
    nextOffsetCharacters: null,
    totalCharacters: 4,
    truncated: false,
    content: "完整问题",
  }));
  return {
    history: { threadRead, itemContentRead } as unknown as ConversationHistoryPort,
    threadRead,
    itemContentRead,
  };
}

describe("exportConversationMarkdown", () => {
  /** 导出完成前复核 revision；正文读取期间发生写入时必须丢弃整份混合快照。 */
  it("rechecks the same revision after public message bodies are materialized", async () => {
    const { history, threadRead, itemContentRead } = historyFor([7, 7]);
    const result = await exportConversationMarkdown(history, {
      threadId: "thread_12345678",
      title: "会话",
    });

    expect(threadRead).toHaveBeenCalledTimes(2);
    expect(itemContentRead).toHaveBeenCalledOnce();
    expect(result.revision).toBe(7);
    expect(result.markdown.match(/完整问题/gu)).toHaveLength(1);
    expect(result.markdown).not.toContain("preview");
  });

  /** 后置 revision 不一致时不返回任何可写出的 Markdown，避免导出并发拼接历史。 */
  it("rejects a revision change after reading the body", async () => {
    const { history } = historyFor([7, 8]);
    await expect(
      exportConversationMarkdown(history, {
        threadId: "thread_12345678",
        title: "会话",
      }),
    ).rejects.toThrow("对话在导出期间发生变化");
  });
});
