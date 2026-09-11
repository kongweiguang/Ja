// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { TimelineItemAdapter } from "@/features/conversation/domain/timelineTypes";
import { ChatTimeline } from "@/features/conversation/ui/timeline/ChatTimeline";

/** 每条父消息使用独立 Turn，使测试观察统一虚拟 Timeline 的跨 exchange 顺序。 */
function parentMessage(
  itemId: string,
  turnId: string,
  text: string,
  createdAt: string,
): TimelineItemAdapter {
  return {
    itemId,
    threadId: "thr_root",
    turnId,
    kind: "user_message",
    status: "completed",
    text,
    createdAt,
  };
}

/** DOM position 比数组断言更接近真实用户看到的可视顺序。 */
function expectBefore(leftText: string, rightText: string): void {
  const left = screen.getByText(leftText);
  const right = screen.getByText(rightText);
  expect(left.compareDocumentPosition(right) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
}

afterEach(() => cleanup());

describe("ChatTimeline external rows", () => {
  it("让 Task Activity 按服务端时间与父 Timeline exchange 交错，而不是统一挂在尾部", () => {
    render(
      <ChatTimeline
        items={[
          parentMessage("item_first", "turn_first", "父消息一", "2026-09-04T08:00:00Z"),
          parentMessage("item_second", "turn_second", "父消息二", "2026-09-04T08:00:02Z"),
        ]}
        externalRows={[
          {
            rowId: "activity_completed",
            occurredAt: "2026-09-04T08:00:03Z",
            revision: "3",
            content: <button type="button">子任务已完成</button>,
          },
          {
            rowId: "activity_dispatched",
            occurredAt: "2026-09-04T08:00:01Z",
            revision: "1",
            content: <button type="button">已派发子任务</button>,
          },
        ]}
      />,
    );

    expectBefore("父消息一", "已派发子任务");
    expectBefore("已派发子任务", "父消息二");
    expectBefore("父消息二", "子任务已完成");
  });

  it("相同时间戳、重复 Activity 与 reload 输入逆序仍保持相同可视总序", () => {
    const timestamp = "2026-09-04T08:00:01Z";
    const { rerender } = render(
      <ChatTimeline
        items={[parentMessage("item_root", "turn_root", "父消息", "2026-09-04T08:00:00Z")]}
        externalRows={[
          {
            rowId: "activity_b",
            occurredAt: timestamp,
            revision: "1",
            content: <span>旧进度 B</span>,
          },
          {
            rowId: "activity_a",
            occurredAt: timestamp,
            revision: "1",
            content: <span>进度 A</span>,
          },
          {
            rowId: "activity_b",
            occurredAt: timestamp,
            revision: "2",
            content: <span>新进度 B</span>,
          },
        ]}
      />,
    );

    expect(screen.queryByText("旧进度 B")).not.toBeInTheDocument();
    expectBefore("进度 A", "新进度 B");

    rerender(
      <ChatTimeline
        items={[parentMessage("item_root", "turn_root", "父消息", "2026-09-04T08:00:00Z")]}
        externalRows={[
          {
            rowId: "activity_b",
            occurredAt: timestamp,
            revision: "2",
            content: <span>新进度 B</span>,
          },
          {
            rowId: "activity_a",
            occurredAt: timestamp,
            revision: "1",
            content: <span>进度 A</span>,
          },
        ]}
      />,
    );

    expectBefore("进度 A", "新进度 B");
  });

  it("流式父回复更新只修改原 exchange，不会跨过已排序的 Activity 行", () => {
    const user = parentMessage("item_user", "turn_stream", "开始处理", "2026-09-04T08:00:00Z");
    const draft: TimelineItemAdapter = {
      ...user,
      itemId: "draft:turn_stream",
      kind: "commentary",
      status: "in_progress",
      title: "回复过程",
      text: "正在处理",
      createdAt: "2026-09-04T08:00:00.500Z",
    };
    const activity = {
      rowId: "activity_progress",
      occurredAt: "2026-09-04T08:00:01Z",
      revision: "1",
      content: <span>子任务进度</span>,
    };
    const { rerender } = render(<ChatTimeline items={[user, draft]} externalRows={[activity]} />);

    expectBefore("正在处理", "子任务进度");
    rerender(
      <ChatTimeline
        items={[user, { ...draft, text: "正在处理更多内容" }]}
        externalRows={[activity]}
      />,
    );
    expectBefore("正在处理更多内容", "子任务进度");
  });

  it("同一父 exchange 内仍按 User、Task Activity、Assistant 的服务端时间交错", () => {
    const user = parentMessage(
      "item_user",
      "turn_same_exchange",
      "派发检查",
      "2026-09-04T08:00:00Z",
    );
    const answer: TimelineItemAdapter = {
      ...user,
      itemId: "item_answer",
      kind: "agent_message",
      text: "父任务答复",
      createdAt: "2026-09-04T08:00:02Z",
      final: true,
    };
    render(
      <ChatTimeline
        items={[user, answer]}
        externalRows={[
          {
            rowId: "activity_between",
            occurredAt: "2026-09-04T08:00:01Z",
            revision: "1",
            content: <span>已派发检查</span>,
          },
        ]}
      />,
    );

    expectBefore("派发检查", "已派发检查");
    expectBefore("已派发检查", "父任务答复");
  });
});
