// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { projectAnsweredInteractionResult } from "@/features/conversation/application/interactionTimeline";
import type { InteractionRequest } from "@/features/conversation/application/interactionPort";
import type { TimelineItemAdapter } from "@/features/conversation/domain/timelineTypes";

/** 构造与真实 request_user_input 相同的最小已回答请求，测试只关注 Timeline 归属。 */
function answeredRequest(): InteractionRequest {
  return {
    requestId: "interaction_demo",
    threadId: "thr_one",
    turnId: "turn_one",
    toolCallId: "call_question",
    questions: [
      {
        questionId: "scope",
        prompt: "采用哪种范围？",
        type: "single",
        required: true,
        allowFreeText: true,
        options: [
          { optionId: "project", label: "按项目覆盖" },
          { optionId: "global", label: "全局统一" },
        ],
      },
    ],
    answers: [{ questionId: "scope", optionIds: ["project"], freeText: null, skipped: false }],
    status: "answered",
    revision: 2,
    createdAt: "2026-09-18T00:00:00Z",
    updatedAt: "2026-09-18T00:00:01Z",
  };
}

/** 构造仍在等待的 Tool 行，验证回答 ACK 会原位替换而不是新增重复行。 */
function pendingTool(): TimelineItemAdapter {
  return {
    itemId: "item_question",
    threadId: "thr_one",
    turnId: "turn_one",
    kind: "tool_call",
    status: "in_progress",
    title: "询问偏好",
    metadata: {
      callId: "call_question",
      toolName: "request_user_input",
      toolKind: "read",
      presentation: {
        kind: "read",
        title: "询问偏好",
        status: "running",
        inputPreview: "采用哪种范围？",
        relativePaths: [],
        truncated: false,
      },
    },
    createdAt: "2026-09-18T00:00:00Z",
  };
}

describe("projectAnsweredInteractionResult", () => {
  it("在 ACK 与规范 ToolResult 之间原位展示人类可读答案", () => {
    const projected = projectAnsweredInteractionResult([pendingTool()], answeredRequest(), {
      scope: { questionId: "scope", optionIds: ["project"], freeText: null, skipped: false },
    });

    expect(projected).toHaveLength(1);
    expect(projected[0]).toMatchObject({
      itemId: "item_question",
      status: "completed",
      metadata: {
        toolName: "request_user_input",
        presentation: {
          status: "success",
          summary: "已回答 1 个问题",
          interactionAnswers: [
            { question: "采用哪种范围？", answers: ["按项目覆盖"], skipped: false },
          ],
        },
      },
    });
  });

  it("规范终态到达后不覆盖服务端摘要", () => {
    const base = pendingTool();
    const terminal = {
      ...base,
      status: "completed" as const,
      metadata: {
        ...base.metadata,
        presentation: {
          ...base.metadata!.presentation!,
          status: "success" as const,
          summary: "服务端固定摘要",
        },
      },
    };

    expect(
      projectAnsweredInteractionResult([terminal], answeredRequest(), {
        scope: { questionId: "scope", optionIds: ["project"], freeText: null, skipped: false },
      }).map((item) => item.metadata?.presentation?.summary),
    ).toEqual(["服务端固定摘要"]);
  });

  it("规范 Tool 行尚未恢复时补一条稳定 identity 的 Timeline 记录", () => {
    const projected = projectAnsweredInteractionResult([], answeredRequest(), {
      scope: { questionId: "scope", optionIds: ["project"], freeText: null, skipped: false },
    });

    expect(projected).toHaveLength(1);
    expect(projected[0]).toMatchObject({
      itemId: "interaction_result_interaction_demo",
      threadId: "thr_one",
      turnId: "turn_one",
      kind: "tool_call",
      status: "completed",
    });
  });
});
