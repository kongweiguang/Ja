// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  readFullAnswerContent,
  readFullMessageContent,
} from "@/features/conversation/application/readFullMessageContent";
import type { TimelineSnapshot } from "@/features/conversation/domain/timelineContracts";

describe("readFullMessageContent", () => {
  /** 跨页按 Unicode code point 前进，代理对不会重复或漏掉。 */
  it("reassembles ordered Unicode pages", async () => {
    const read = vi.fn(async ({ offsetCharacters }: { offsetCharacters: number }) =>
      offsetCharacters === 0
        ? {
            messageId: "item_demo",
            offsetCharacters: 0,
            nextOffsetCharacters: 2,
            totalCharacters: 3,
            truncated: true,
            content: "甲😀",
          }
        : {
            messageId: "item_demo",
            offsetCharacters: 2,
            nextOffsetCharacters: null,
            totalCharacters: 3,
            truncated: false,
            content: "乙",
          },
    );
    await expect(readFullMessageContent(read, "thr_demo", "item_demo")).resolves.toBe("甲😀乙");
    expect(read.mock.calls.map((call) => call[0].offsetCharacters)).toEqual([0, 2]);
  });

  /** 错位页或切换后的迟到页不允许交付半条正文。 */
  it("rejects inconsistent progress and a stale thread fence", async () => {
    await expect(
      readFullMessageContent(
        async () => ({
          messageId: "item_demo",
          offsetCharacters: 0,
          nextOffsetCharacters: 0,
          totalCharacters: 2,
          truncated: true,
          content: "",
        }),
        "thr_demo",
        "item_demo",
      ),
    ).rejects.toThrow("分页数据不一致");
    let current = true;
    await expect(
      readFullMessageContent(
        async () => {
          current = false;
          return {
            messageId: "item_demo",
            offsetCharacters: 0,
            nextOffsetCharacters: null,
            totalCharacters: 1,
            truncated: false,
            content: "甲",
          };
        },
        "thr_demo",
        "item_demo",
        () => current,
      ),
    ).rejects.toThrow("对话已切换");
  });

  /** 只在用户请求全文时跨旧页查找模型段，当前可见页缺少前缀也不能静默导出半份答案。 */
  it("finds continuation text in an older history page", async () => {
    const base = {
      threadId: "thr_demo",
      revision: 7,
      turns: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      goalActivities: [],
    } satisfies Omit<TimelineSnapshot, "items" | "nextCursor">;
    const occurredAt = "2026-09-25T00:00:00Z";
    const latest: TimelineSnapshot = {
      ...base,
      items: [
        {
          itemId: "item_final",
          turnId: "turn_current",
          kind: "final_answer",
          text: "ending",
          createdAt: occurredAt,
        },
        ...Array.from({ length: 199 }, (_, index) => ({
          itemId: `item_later_${index}`,
          turnId: "turn_later",
          kind: "assistant_progress" as const,
          text: "later",
          modelRound: 1,
          createdAt: occurredAt,
        })),
      ],
      nextCursor: "cursor_older",
    };
    const older: TimelineSnapshot = {
      ...base,
      items: [
        {
          itemId: "item_question",
          turnId: "turn_current",
          kind: "user_input",
          content: [{ type: "text", text: "question" }],
          attachments: [],
          createdAt: occurredAt,
        },
        {
          itemId: "item_prefix",
          turnId: "turn_current",
          kind: "assistant_progress",
          text: "earlier-",
          modelRound: 1,
          createdAt: occurredAt,
        },
      ],
      nextCursor: null,
    };
    const history = {
      threadRead: vi.fn(async ({ cursor }: { cursor?: string }) =>
        cursor === undefined ? latest : older,
      ),
      messageContentRead: vi.fn(async ({ messageId }: { messageId: string }) => {
        const content = messageId === "item_prefix" ? "earlier-" : "ending";
        return {
          messageId,
          offsetCharacters: 0,
          nextOffsetCharacters: null,
          totalCharacters: content.length,
          truncated: false,
          content,
        };
      }),
    };
    await expect(readFullAnswerContent(history, "thr_demo", "item_final")).resolves.toBe(
      "earlier-ending",
    );
    expect(history.threadRead).toHaveBeenCalledTimes(2);
    expect(history.messageContentRead.mock.calls.map((call) => call[0].messageId)).toEqual([
      "item_prefix",
      "item_final",
    ]);
  });

  /** 终态事件已到但 read 基线暂时落后时先等待权威 revision，不把一次空页当成永久缺失。 */
  it("waits for the terminal revision before declaring a final message missing", async () => {
    const base = {
      threadId: "thr_demo",
      turns: [],
      inputQueue: null,
      contextUsage: null,
      liveStream: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor: null,
    } satisfies Omit<TimelineSnapshot, "items" | "revision">;
    const read = vi
      .fn()
      .mockResolvedValueOnce({ ...base, revision: 6, items: [] })
      .mockResolvedValueOnce({
        ...base,
        revision: 7,
        items: [
          {
            itemId: "item_final",
            turnId: "turn_demo",
            kind: "final_answer",
            text: "complete",
            createdAt: "2026-09-25T00:00:00Z",
          },
        ],
      });
    const history = {
      threadRead: read,
      messageContentRead: vi.fn(async () => ({
        messageId: "item_final",
        offsetCharacters: 0,
        nextOffsetCharacters: null,
        totalCharacters: 8,
        truncated: false,
        content: "complete",
      })),
    };
    await expect(
      readFullAnswerContent(history, "thr_demo", "item_final", () => true, 7),
    ).resolves.toBe("complete");
    expect(read).toHaveBeenCalledTimes(2);
  });

  /** 实时事件和历史页的公开身份不同，仍须凭同一 Turn 找到已提交的完整正文。 */
  it("resolves a live final message through its turn after history changes the item identity", async () => {
    const history = {
      threadRead: vi.fn(async () => ({
        threadId: "thr_demo",
        revision: 8,
        turns: [],
        inputQueue: null,
        contextUsage: null,
        liveStream: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
        items: [
          {
            itemId: "item_public_hash",
            turnId: "turn_demo",
            kind: "final_answer" as const,
            text: "preview",
            createdAt: "2026-09-25T00:00:00Z",
          },
        ],
      })),
      messageContentRead: vi.fn(async ({ messageId }: { messageId: string }) => ({
        messageId,
        offsetCharacters: 0,
        nextOffsetCharacters: null,
        totalCharacters: 8,
        truncated: false,
        content: "complete",
      })),
    };
    await expect(
      readFullAnswerContent(history, "thr_demo", "raw_live_id", () => true, 8, "turn_demo"),
    ).resolves.toBe("complete");
    expect(history.messageContentRead).toHaveBeenCalledWith(
      expect.objectContaining({
        messageId: "item_public_hash",
      }),
    );
  });
});
