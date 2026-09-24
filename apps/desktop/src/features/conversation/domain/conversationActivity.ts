// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TimelineItemAdapter } from "./timelineTypes";

export interface ConversationActivityCounts {
  userMessages: number;
  assistantMessages: number;
  collaborationMessages: number;
  totalMessages: number;
  toolCalls: number;
}

/**
 * 只统计权威顺序里的公开文字与单次 Tool 记录；审批和推理摘要属于处理过程，
 * ToolResult 已并入对应调用，不能再按消息重复计数。
 */
export function countCommittedConversationActivity(
  items: readonly TimelineItemAdapter[],
): ConversationActivityCounts {
  let userMessages = 0;
  let assistantMessages = 0;
  let collaborationMessages = 0;
  let toolCalls = 0;
  for (const item of items) {
    switch (item.kind) {
      case "user_message":
        userMessages += 1;
        break;
      case "agent_message":
        assistantMessages += 1;
        break;
      case "commentary":
        if (item.metadata?.phase === "assistant_progress") assistantMessages += 1;
        break;
      case "thread_message":
        collaborationMessages += 1;
        break;
      case "tool_call":
      case "command":
        toolCalls += 1;
        break;
      default:
        break;
    }
  }
  return {
    userMessages,
    assistantMessages,
    collaborationMessages,
    totalMessages: userMessages + assistantMessages + collaborationMessages,
    toolCalls,
  };
}
