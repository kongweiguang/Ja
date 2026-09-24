// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { countCommittedConversationActivity } from "@/features/conversation";
import type { TimelineItemAdapter } from "@/features/conversation";

/** 测试记录保留服务端投影的稳定身份，避免以显示标题决定消息角色。 */
function item(
  itemId: string,
  kind: TimelineItemAdapter["kind"],
  phase?: "assistant_progress" | "reasoning_summary",
): TimelineItemAdapter {
  return {
    itemId,
    threadId: "thr_current",
    turnId: "turn_current",
    kind,
    status: "completed",
    ...(phase === undefined ? {} : { metadata: { phase } }),
  };
}

describe("countCommittedConversationActivity", () => {
  /** 助手公开文本与协作消息进入加法闭合的消息总数，工具结果不增加第二条调用。 */
  it("按持久语义分类消息与工具调用", () => {
    expect(
      countCommittedConversationActivity([
        item("u1", "user_message"),
        item("a1", "commentary", "assistant_progress"),
        item("a2", "agent_message"),
        item("t1", "thread_message"),
        item("tool1", "tool_call"),
        item("tool2", "command"),
        item("reasoning", "reasoning", "reasoning_summary"),
        item("approval", "commentary"),
      ]),
    ).toEqual({
      userMessages: 1,
      assistantMessages: 2,
      collaborationMessages: 1,
      totalMessages: 4,
      toolCalls: 2,
    });
  });

  /** 空的权威快照可以展示真实零，草稿不应作为本函数的输入。 */
  it("空会话统计为零", () => {
    expect(countCommittedConversationActivity([])).toEqual({
      userMessages: 0,
      assistantMessages: 0,
      collaborationMessages: 0,
      totalMessages: 0,
      toolCalls: 0,
    });
  });
});
