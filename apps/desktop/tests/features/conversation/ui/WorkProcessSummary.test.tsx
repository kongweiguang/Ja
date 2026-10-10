// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import type {
  TimelineItemAdapter,
  TimelineTurn,
} from "@/features/conversation/domain/timelineTypes";
import { ChatTimeline } from "@/features/conversation/ui/timeline/ChatTimeline";
import { WorkProcess } from "@/features/conversation/ui/timeline/WorkProcess";

/** 统计用真实投影类型构造，刻意复用 callId 以覆盖它只在 Turn 内唯一的约束。 */
function item(itemId: string, kind: TimelineItemAdapter["kind"], turnId = "turn_one") {
  return {
    origin: "native" as const,
    itemId,
    threadId: "thr_one",
    turnId,
    kind,
    status: "completed" as const,
    text: itemId,
    metadata: { callId: "call_one" },
  };
}

const completedTurn: TimelineTurn = {
  origin: "native" as const,
  turnId: "turn_one",
  threadId: "thr_one",
  status: "completed",
  startedAt: "2026-10-05T00:00:00.000Z",
  completedAt: "2026-10-05T00:01:23.000Z",
};

describe("工作过程归档统计", () => {
  afterEach(cleanup);

  /** 同一入口保留统计及键盘操作，展开不能重置数字或插入额外状态标题。 */
  it("显示工作时长和实际工具次数，键盘展开收起仍保留摘要", async () => {
    const user = userEvent.setup();
    render(
      <WorkProcess
        steps={[
          item("公开进展", "commentary"),
          item("公开思考", "reasoning"),
          item("读取代码", "tool_call"),
          item("执行验证", "command"),
          item("审批记录", "approval"),
          {
            ...item("上下文自动压缩", "tool_call"),
            metadata: {
              presentation: {
                kind: "context",
                title: "上下文自动压缩",
                status: "success",
                relativePaths: [],
                truncated: false,
              },
            },
          },
        ]}
        turn={completedTurn}
        displayMode="archived"
      />,
    );
    const trigger = screen.getByRole("button", {
      name: "查看工作过程，工作 1分23秒，调用工具 2 次",
    });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).not.toHaveTextContent("已完成");
    trigger.focus();
    await user.keyboard("{Enter}");
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(trigger).toHaveAccessibleName("收起工作过程，工作 1分23秒，调用工具 2 次");
    await user.keyboard(" ");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
  });

  /** 失败和取消也必须保留已发生的工作事实，不能把终态当成成功或丢失调用。 */
  it.each(["failed", "cancelled"] as const)("%s 时仍显示已记录的时间与工具次数", (status) => {
    render(
      <WorkProcess
        steps={[{ ...item("读取失败", "tool_call"), status: "failed" }]}
        turn={{ ...completedTurn, status }}
        displayMode="archived"
      />,
    );
    expect(
      screen.getByRole("button", { name: /收起工作过程，工作 1分23秒，调用工具 1 次/ }),
    ).toBeVisible();
  });

  /** 只有命令本身的真实失败才自动展开归档，主动停止和取消退出码不伪装成失败。 */
  it.each([
    {
      label: "主动停止",
      toolName: "stop_process",
      presentationStatus: "success" as const,
      stepStatus: "completed" as const,
      turnStatus: "completed" as const,
      exitCode: 143,
      expanded: false,
    },
    {
      label: "取消的工具，但 Turn 已完成",
      toolName: "exec_command",
      presentationStatus: "cancelled" as const,
      stepStatus: "cancelled" as const,
      turnStatus: "completed" as const,
      exitCode: 130,
      expanded: false,
    },
    {
      label: "Turn 取消并保留工作过程",
      toolName: "exec_command",
      presentationStatus: "cancelled" as const,
      stepStatus: "cancelled" as const,
      turnStatus: "cancelled" as const,
      exitCode: 130,
      expanded: true,
    },
    {
      label: "命令非零退出",
      toolName: "exec_command",
      presentationStatus: "success" as const,
      stepStatus: "completed" as const,
      turnStatus: "completed" as const,
      exitCode: 2,
      expanded: true,
    },
  ])(
    "$label 的归档展开状态符合实际结果",
    ({ toolName, presentationStatus, stepStatus, turnStatus, exitCode, expanded }) => {
      render(
        <WorkProcess
          steps={[
            {
              ...item("命令执行", "tool_call"),
              status: stepStatus,
              metadata: {
                callId: "call_command",
                toolName,
                presentation: {
                  kind: "shell",
                  title: "执行命令",
                  status: presentationStatus,
                  relativePaths: [],
                  command: "pnpm build",
                  exitCode,
                  truncated: false,
                },
              },
            },
          ]}
          turn={{ ...completedTurn, status: turnStatus }}
          displayMode="archived"
        />,
      );
      expect(screen.getByRole("button", { name: /工作过程/ })).toHaveAttribute(
        "aria-expanded",
        String(expanded),
      );
      if (turnStatus === "cancelled") {
        expect(screen.getByText("pnpm build")).toBeVisible();
      }
    },
  );

  /** 步骤耗时不能填补缺失或损坏的整轮时间，避免重载时出现看似精确的假统计。 */
  it.each([
    { startedAt: undefined },
    { completedAt: undefined },
    { startedAt: "invalid" },
    { completedAt: "2026-10-04T23:59:59Z" },
  ])("无有效权威时间时只显示调用次数 %#", (timestamps) => {
    render(
      <WorkProcess
        steps={[{ ...item("读取代码", "tool_call"), durationMs: 5_000 }]}
        turn={{ ...completedTurn, ...timestamps }}
        displayMode="archived"
      />,
    );
    const trigger = screen.getByRole("button", { name: "查看工作过程，调用工具 1 次" });
    expect(trigger).not.toHaveTextContent("工作 5秒");
  });

  /** 真实零耗时应保留，纯文字工作不额外制造“调用工具 0 次”占位。 */
  it("保留实测零时长并省略没有调用的计数", () => {
    render(
      <WorkProcess
        steps={[item("公开进展", "commentary")]}
        turn={{ ...completedTurn, completedAt: completedTurn.startedAt }}
        displayMode="archived"
      />,
    );
    expect(screen.getByRole("button", { name: "查看工作过程，工作 0秒" })).toBeVisible();
    expect(screen.queryByText(/调用工具/)).not.toBeInTheDocument();
  });

  /** 运行中继续正文直出，结算到归档与历史重读使用相同数字，不额外引入实时计时器。 */
  it("运行到完成及历史重读时统计稳定", () => {
    const steps = [item("公开进展", "commentary"), item("读取代码", "tool_call")];
    const { rerender } = render(
      <WorkProcess
        steps={steps}
        turn={{ ...completedTurn, status: "running", completedAt: undefined }}
        displayMode="inline"
      />,
    );
    expect(screen.queryByRole("button", { name: /工作过程/ })).not.toBeInTheDocument();
    rerender(<WorkProcess steps={steps} turn={completedTurn} displayMode="archived" />);
    const trigger = screen.getByRole("button", {
      name: "查看工作过程，工作 1分23秒，调用工具 1 次",
    });
    rerender(<WorkProcess steps={[...steps]} turn={{ ...completedTurn }} displayMode="archived" />);
    expect(screen.getByRole("button", { name: "查看工作过程，工作 1分23秒，调用工具 1 次" })).toBe(
      trigger,
    );
  });

  /** 等待卡片只取工具所属的权威 Turn，不能继承合并工作过程里其它 Turn 的 waiting_input。 @author kongweiguang */
  it("仅隐藏同一 waiting_input Turn 的待校验提问目标", () => {
    const waitingTurn: TimelineTurn = {
      origin: "native" as const,
      turnId: "turn_waiting",
      threadId: "thr_one",
      status: "waiting_input",
    };
    const pendingQuestion: TimelineItemAdapter = {
      ...item("询问用户", "tool_call", waitingTurn.turnId),
      status: "in_progress",
      metadata: {
        callId: "call_question",
        toolName: "request_user_input",
        presentation: {
          kind: "mcp",
          title: "User input",
          status: "pending",
          inputPreview: "等待参数校验",
          relativePaths: [],
          truncated: false,
        },
      },
    };

    render(<WorkProcess steps={[pendingQuestion]} turn={waitingTurn} />);

    const trigger = screen.getByRole("button", { name: /询问用户/u });
    expect(trigger).toHaveTextContent("询问用户");
    expect(trigger.querySelector(".ja-tool-details__target")).toBeNull();
    expect(screen.queryByText("等待参数校验")).not.toBeInTheDocument();
  });

  /** 普通 MCP 和其它 Turn 的预览不是当前问题的未校验占位，必须按原事实继续显示。 @author kongweiguang */
  it("不把 waiting_input 状态泄漏到其它步骤或普通 MCP 预览", () => {
    const oldTurn: TimelineTurn = {
      origin: "native" as const,
      turnId: "turn_old",
      threadId: "thr_one",
      status: "running",
    };
    const waitingTurn: TimelineTurn = {
      origin: "native" as const,
      turnId: "turn_waiting",
      threadId: "thr_one",
      status: "waiting_input",
    };
    const oldQuestion: TimelineItemAdapter = {
      ...item("旧问题", "tool_call", oldTurn.turnId),
      status: "in_progress",
      metadata: {
        callId: "call_old_question",
        toolName: "request_user_input",
        presentation: {
          kind: "mcp",
          title: "User input",
          status: "pending",
          inputPreview: "等待参数校验",
          relativePaths: [],
          truncated: false,
        },
      },
    };
    const genericPreview: TimelineItemAdapter = {
      ...item("普通工具", "tool_call", waitingTurn.turnId),
      status: "in_progress",
      metadata: {
        callId: "call_generic_preview",
        toolName: "custom_mcp",
        presentation: {
          kind: "mcp",
          title: "Custom tool",
          status: "pending",
          inputPreview: "等待参数校验",
          relativePaths: [],
          truncated: false,
        },
      },
    };

    render(
      <WorkProcess
        steps={[oldQuestion, genericPreview]}
        turn={waitingTurn}
        relatedTurns={[oldTurn, waitingTurn]}
      />,
    );

    expect(screen.getByRole("button", { name: /request_user_input/u })).toHaveTextContent(
      "等待参数校验",
    );
    expect(screen.getByRole("button", { name: /custom_mcp/u })).toHaveTextContent("等待参数校验");
    expect(screen.getAllByText("等待参数校验")).toHaveLength(2);
  });

  /** 跨 Turn 续答共享过程，但不把暂停后的用户等待计入时长，也不漏算相同 callId 的新调用。 */
  it("源问题和续答合计工作时长与调用，缺少任一次测量时省略总时长", () => {
    const continuation: TimelineTurn = {
      ...completedTurn,
      turnId: "turn_continue",
      sourceMessageId: "源问题",
      startedAt: "2026-10-05T00:10:00.000Z",
      completedAt: "2026-10-05T00:10:17.000Z",
      threadRevision: 2,
    };
    const items = [
      item("源问题", "user_message"),
      item("第一次读取", "tool_call"),
      item("继续验证", "command", continuation.turnId),
      { ...item("最终回复", "agent_message", continuation.turnId), final: true },
    ];
    const failedTurn: TimelineTurn = { ...completedTurn, status: "failed", threadRevision: 1 };
    const turns = [failedTurn, continuation];
    const { rerender } = render(<ChatTimeline threadId="thr_one" items={items} turns={turns} />);
    expect(screen.getByRole("button", { name: /工作 1分40秒，调用工具 2 次/ })).toBeVisible();
    expect(screen.getAllByRole("article", { name: "用户问题" })).toHaveLength(1);
    rerender(
      <ChatTimeline
        threadId="thr_one"
        items={items}
        turns={[{ ...failedTurn, startedAt: undefined }, continuation]}
      />,
    );
    expect(screen.getByRole("button", { name: /工作过程，调用工具 2 次/ })).toBeVisible();
    expect(screen.queryByText(/工作 17秒/)).not.toBeInTheDocument();
  });
});
