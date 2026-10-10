// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  TimelineItemAdapter,
  TimelineTurn,
} from "@/features/conversation/domain/timelineTypes";
import { ChatTimeline } from "@/features/conversation/ui/timeline/ChatTimeline";

/** 构造完整 Timeline 投影，避免 UI 测试借缺省来源身份掩盖 inherited 行为。 */
function item(
  itemId: string,
  kind: TimelineItemAdapter["kind"],
  overrides: Partial<TimelineItemAdapter> = {},
): TimelineItemAdapter {
  return {
    origin: "native",
    itemId,
    threadId: "thr_fork_ui",
    turnId: "turn_fork_ui",
    kind,
    status: "completed",
    createdAt: "2026-10-10T00:00:00Z",
    ...overrides,
  };
}

/** 同 Turn 多个 exchange 的行状态只供 display projection 使用，不作为分叉锚点。 */
const runningTurn: TimelineTurn = {
  origin: "native",
  turnId: "turn_fork_ui",
  threadId: "thr_fork_ui",
  status: "running",
};

describe("ChatTimeline reply fork", () => {
  afterEach(() => cleanup());

  /** 合并回复仍用最后一条 settled item 定位；不同用户 exchange 保持各自的锚点和图标顺序。 */
  it("forks the last persisted final item for each visible reply", async () => {
    const onForkReply = vi.fn(async () => "opened" as const);
    const onCopyText = vi.fn(async () => undefined);
    const items = [
      item("item_user_one", "user_message", { text: "第一问" }),
      item("item_answer_part_one", "agent_message", {
        final: true,
        text: "第一段答复",
      }),
      item("item_answer_part_last", "agent_message", {
        final: true,
        text: "续接的答复片段",
      }),
      item("item_user_two", "user_message", { text: "第二问" }),
      item("item_answer_two", "agent_message", {
        final: true,
        text: "第二个答复",
      }),
    ];

    render(
      <ChatTimeline
        threadId="thr_fork_ui"
        items={items}
        turns={[runningTurn]}
        onCopyText={onCopyText}
        onForkReply={onForkReply}
      />,
    );

    const forkButtons = screen.getAllByTestId("fork-reply");
    expect(forkButtons).toHaveLength(2);
    expect(forkButtons[0]).toHaveAttribute("data-source-item-id", "item_answer_part_last");
    expect(forkButtons[1]).toHaveAttribute("data-source-item-id", "item_answer_two");
    expect(forkButtons[0]).toHaveAccessibleName("从此回复新建会话");
    expect(forkButtons[0]).toHaveAttribute("title", "从此回复新建会话");

    const mergedReply = document.querySelector<HTMLElement>(
      '[data-fork-source-item-id="item_answer_part_last"]',
    );
    expect(mergedReply).toHaveAttribute("data-item-id", "item_answer_part_one");
    expect(mergedReply).toHaveTextContent(/第一段答复\s+续接的答复片段/u);
    const actionButtons = mergedReply?.querySelectorAll<HTMLButtonElement>(
      ".ja-chat-message__actions button",
    );
    expect(actionButtons?.[0]).toHaveAccessibleName("复制回复");
    expect(actionButtons?.[1]).toBe(forkButtons[0]);

    fireEvent.click(forkButtons[0]!);
    expect(onForkReply).toHaveBeenCalledExactlyOnceWith({
      threadId: "thr_fork_ui",
      itemId: "item_answer_part_last",
    });
  });

  /**
   * 冻结前缀可有同毫秒、独立 remap 的 display Turn ID；行序必须服从 snapshot 因果次序且锚定目标 Item ID。
   * @author kongweiguang
   */
  it("preserves inherited snapshot order and fork anchors for same-millisecond turns", () => {
    const targetThreadId = "thr_fork_target";
    const timestamp = "2026-10-10T00:00:00.000Z";
    const onForkReply = vi.fn(async () => "opened" as const);

    render(
      <ChatTimeline
        threadId={targetThreadId}
        turns={[
          {
            origin: "inherited",
            turnId: "turn_z",
            threadId: targetThreadId,
            status: "completed",
          },
          {
            origin: "inherited",
            turnId: "turn_a",
            threadId: targetThreadId,
            status: "completed",
          },
        ]}
        items={[
          item("target_user_z", "user_message", {
            origin: "inherited",
            threadId: targetThreadId,
            turnId: "turn_z",
            createdAt: timestamp,
            text: "先发生的问题",
          }),
          item("target_final_z", "agent_message", {
            origin: "inherited",
            threadId: targetThreadId,
            turnId: "turn_z",
            createdAt: timestamp,
            final: true,
            text: "先发生的回复",
          }),
          item("target_user_a", "user_message", {
            origin: "inherited",
            threadId: targetThreadId,
            turnId: "turn_a",
            createdAt: timestamp,
            text: "后发生的问题",
          }),
          item("target_final_a", "agent_message", {
            origin: "inherited",
            threadId: targetThreadId,
            turnId: "turn_a",
            createdAt: timestamp,
            final: true,
            text: "后发生的回复",
          }),
        ]}
        onForkReply={onForkReply}
      />,
    );

    const visibleReplies = Array.from(
      document.querySelectorAll<HTMLElement>("[data-fork-source-item-id]"),
      (reply) => reply.getAttribute("data-fork-source-item-id"),
    );
    const forkButtons = screen.getAllByTestId("fork-reply");
    expect(visibleReplies).toEqual(["target_final_z", "target_final_a"]);
    expect(forkButtons.map((button) => button.getAttribute("data-source-item-id"))).toEqual([
      "target_final_z",
      "target_final_a",
    ]);

    fireEvent.click(forkButtons[0]!);
    expect(onForkReply).toHaveBeenCalledExactlyOnceWith({
      threadId: targetThreadId,
      itemId: "target_final_z",
    });
  });

  /** 未结算 assistant、Reasoning 与 Tool 是工作过程，不得因聚合成响应行而获得分叉入口。 */
  it("does not expose fork for unsettled assistant content, reasoning, or tools", () => {
    render(
      <ChatTimeline
        threadId="thr_fork_ui"
        items={[
          item("item_user", "user_message", { text: "处理中" }),
          item("item_reasoning", "reasoning", { text: "思考摘要" }),
          item("item_tool", "tool_call", { title: "读取文件" }),
          item("item_unsettled", "agent_message", {
            status: "in_progress",
            text: "尚未提交的正文",
          }),
        ]}
        turns={[runningTurn]}
        onForkReply={vi.fn(async () => "opened" as const)}
      />,
    );

    expect(screen.queryByTestId("fork-reply")).not.toBeInTheDocument();
  });

  /** 继承前缀只保留可阅读能力；来源的活动状态、恢复控制与审批不能进入新 Thread。 */
  it("treats inherited answers as readable and forkable without source execution controls", async () => {
    const onForkReply = vi.fn(async () => "opened" as const);
    render(
      <ChatTimeline
        threadId="thr_fork_target"
        items={[
          item("item_inherited_tool", "tool_call", {
            origin: "inherited",
            threadId: "thr_fork_target",
            status: "started",
            title: "执行命令",
            metadata: {
              callId: "call_source_tool",
              toolName: "shell",
              presentation: {
                kind: "shell",
                title: "执行命令",
                status: "running",
                command: "npm test",
                relativePaths: [],
                truncated: false,
                recovery: { revision: 4 },
              },
            },
          }),
          item("item_inherited_answer", "agent_message", {
            origin: "inherited",
            threadId: "thr_fork_target",
            final: true,
            text: "继承的已结算答复",
          }),
        ]}
        turns={[
          {
            ...runningTurn,
            origin: "inherited",
            threadId: "thr_fork_target",
            status: "running",
          },
        ]}
        onForkReply={onForkReply}
        onResolveToolRecovery={vi.fn(async () => undefined)}
        onOpenFile={vi.fn()}
      />,
    );

    const answer = screen.getByRole("article", { name: "最终答复" });
    expect(answer).toHaveAttribute("data-response-state", "completed");
    expect(answer).not.toHaveAttribute("aria-busy", "true");
    expect(screen.getByTestId("fork-reply")).toHaveAttribute(
      "data-source-item-id",
      "item_inherited_answer",
    );
    expect(screen.queryByRole("button", { name: "重新执行" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "跳过这一步" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^批准$/ })).not.toBeInTheDocument();
  });

  /** 锚点状态由 Controller 持有，虚拟行重新挂载后仍显示可执行的安全重试语义。 */
  it("retains distinct conflict and unknown-result guidance on the anchored button", () => {
    const anchor = { threadId: "thr_fork_ui", itemId: "item_fork_notice" };
    const props = {
      threadId: anchor.threadId,
      items: [
        item(anchor.itemId, "agent_message", {
          final: true,
          text: "可以分叉的已结算回复",
        }),
      ],
      onForkReply: vi.fn(async () => "opened" as const),
    };
    const { rerender } = render(<ChatTimeline {...props} forkUnconfirmedReplies={[anchor]} />);

    let button = screen.getByTestId("fork-reply");
    expect(button).toHaveAttribute("data-fork-state", "unconfirmed");
    expect(button).toHaveAccessibleName("分叉结果待核实；重试将继续使用此回复");
    expect(button).toHaveAttribute("title", expect.stringContaining("原操作身份重试"));

    rerender(<ChatTimeline {...props} forkConflictReplies={[anchor]} />);
    button = screen.getByTestId("fork-reply");
    expect(button).toHaveAttribute("data-fork-state", "conflict");
    expect(button).toHaveAccessibleName("会话已更新；请重试此回复");
    expect(button).toHaveAttribute("title", expect.stringContaining("刷新后点击重试"));
  });
});
