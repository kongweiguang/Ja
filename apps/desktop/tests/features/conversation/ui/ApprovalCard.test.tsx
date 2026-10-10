// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  TimelineApproval as ApprovalSummary,
  ToolPresentation,
} from "@/features/conversation/domain/timelineTypes";
import {
  ApprovalCard,
  type UserApprovalDecision,
} from "@/features/conversation/ui/approval/ApprovalCard";

const approval: ApprovalSummary = {
  approvalId: "appr_one",
  threadId: "thr_one",
  turnId: "turn_one",
  threadRevision: 1,
  callId: "call_one",
  toolName: "shell",
  reason: "运行测试",
  expiresAt: "2099-08-17T12:00:00+08:00",
};

describe("ApprovalCard", () => {
  afterEach(() => cleanup());

  /** 成功决策替换操作按钮后，焦点应落在明确的审批结果上。 */
  it("shows the public Tool context without exposing call identity", async () => {
    const user = userEvent.setup();
    const decisions: UserApprovalDecision[] = [];
    render(
      <ApprovalCard
        approval={approval}
        onResolve={(decision) => {
          decisions.push(decision);
        }}
      />,
    );

    expect(screen.getByRole("heading", { name: "工具调用需要确认" })).toBeVisible();
    expect(screen.getByText("运行测试")).toBeVisible();
    expect(screen.getByText("有效期")).toBeVisible();
    expect(screen.getByText("shell")).toBeVisible();
    expect(screen.queryByText("call_one")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "批准" }));
    expect(decisions).toEqual(["approve"]);
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "工具调用已批准" })).toBeVisible();
      expect(screen.getByRole("status")).toHaveTextContent("已批准");
      expect(screen.getByRole("status")).toHaveFocus();
    });
    expect(screen.queryByText("运行测试")).not.toBeInTheDocument();
    expect(screen.queryByText("有效期")).not.toBeInTheDocument();
    expect(screen.getByRole("status").closest(".ja-approval-card")).toHaveClass(
      "ja-approval-card-approve",
    );
  });

  it("surfaces a replacement reason without inventing private command or path fields", () => {
    render(<ApprovalCard approval={{ ...approval, reason: "允许写入工作区文件" }} />);

    expect(screen.getByText("允许写入工作区文件")).toBeVisible();
    expect(screen.getByText("shell")).toBeVisible();
    expect(screen.queryByText(/pnpm test|C:\\dev\\ja/u)).not.toBeInTheDocument();
  });

  /** 只显示 Reducer 投影的安全操作和命令，界面不需要公开内部 callId。 */
  it("shows the safe operation and command from its associated Tool presentation", () => {
    const presentation: ToolPresentation = {
      kind: "shell",
      title: "执行命令",
      status: "waiting_approval",
      command: "Write-Output 'safe command'",
      relativePaths: [],
      truncated: false,
    };
    render(<ApprovalCard approval={approval} presentation={presentation} />);

    expect(screen.getByText("执行命令")).toBeVisible();
    expect(screen.getByText("Write-Output 'safe command'")).toBeVisible();
    expect(screen.queryByText("call_one")).not.toBeInTheDocument();
  });

  /** 已知 Plan 工具沿用 Java 的本地化标题，审批卡不从 preview 拼造身份或目标。 */
  it("shows the localized Plan operation supplied by its Tool presentation", () => {
    const planApproval: ApprovalSummary = {
      ...approval,
      toolName: "plan_step_update",
      reason: "需要确认步骤状态更新",
    };
    const presentation: ToolPresentation = {
      kind: "mcp",
      title: "更新执行步骤",
      status: "waiting_approval",
      inputPreview: 'planId: "plan_private", runId: "run_private", operationKey: "op_private"',
      summary: "请求步骤状态：待执行 → 执行中",
      relativePaths: [],
      truncated: false,
    };
    render(<ApprovalCard approval={planApproval} presentation={presentation} />);

    expect(screen.getByText("更新执行步骤")).toBeVisible();
    expect(screen.getByText("需要确认步骤状态更新")).toBeVisible();
    expect(
      screen.queryByText(/plan_step_update|plan_private|run_private|op_private/u),
    ).not.toBeInTheDocument();
  });

  it("prevents duplicate resolution while the callback is pending", async () => {
    const user = userEvent.setup();
    let release: (() => void) | undefined;
    const onResolve = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    );
    render(<ApprovalCard approval={approval} onResolve={onResolve} />);

    const button = screen.getByRole("button", { name: "批准" });
    await user.click(button);
    await user.click(button);
    expect(onResolve).toHaveBeenCalledTimes(1);
    release?.();
    await vi.waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("已批准"));
  });

  /** 拒绝结果与批准结果使用相同的稳定焦点目标。 */
  it("emits deny as a terminal user decision", async () => {
    const user = userEvent.setup();
    const onResolve = vi.fn();
    render(<ApprovalCard approval={approval} onResolve={onResolve} />);
    await user.click(screen.getByRole("button", { name: "拒绝" }));
    expect(onResolve).toHaveBeenCalledWith("deny");
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "工具调用已拒绝" })).toBeVisible();
      expect(screen.getByRole("status")).toHaveTextContent("已拒绝");
      expect(screen.getByRole("status")).toHaveFocus();
    });
    expect(screen.queryByText("运行测试")).not.toBeInTheDocument();
    expect(screen.queryByText("有效期")).not.toBeInTheDocument();
    expect(screen.getByRole("status").closest(".ja-approval-card")).toHaveClass(
      "ja-approval-card-deny",
    );
  });

  /** 等待审批时用户切到卡片外，异步决策完成不得把焦点夺回结果文本。 */
  it("does not steal focus after the user leaves the card while resolving", async () => {
    const user = userEvent.setup();
    let finishResolution: (() => void) | undefined;
    const onResolve = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishResolution = resolve;
        }),
    );
    render(
      <>
        <ApprovalCard approval={approval} onResolve={onResolve} />
        <textarea aria-label="消息" />
      </>,
    );

    await user.click(screen.getByRole("button", { name: "批准" }));
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    finishResolution?.();

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("已批准"));
    expect(composer).toHaveFocus();
  });

  /** 隐藏 Thread 中返回的审批结果不能聚焦仍挂载但不可见的状态节点。 */
  it("does not focus an approval result after its card becomes hidden", async () => {
    const user = userEvent.setup();
    let finishResolution: (() => void) | undefined;
    const onResolve = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishResolution = resolve;
        }),
    );
    const view = render(
      <div>
        <ApprovalCard approval={approval} onResolve={onResolve} />
      </div>,
    );

    await user.click(screen.getByRole("button", { name: "批准" }));
    view.rerender(
      <div hidden>
        <ApprovalCard approval={approval} onResolve={onResolve} />
      </div>,
    );
    finishResolution?.();
    await waitFor(() =>
      expect(view.container.querySelector(".ja-approval-card__result")?.textContent).toContain(
        "已批准",
      ),
    );
    const result = view.container.querySelector(".ja-approval-card__result");
    expect(result).not.toHaveFocus();
  });

  /** Thread 改变期间旧审批完成不得把结果或焦点写入复用后的卡片。 */
  it("ignores a late decision after the card changes Threads", async () => {
    const user = userEvent.setup();
    let finishResolution: (() => void) | undefined;
    const onResolve = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishResolution = resolve;
        }),
    );
    const view = render(
      <>
        <ApprovalCard approval={approval} onResolve={onResolve} />
        <textarea aria-label="消息" />
      </>,
    );

    await user.click(screen.getByRole("button", { name: "批准" }));
    view.rerender(
      <>
        <ApprovalCard approval={{ ...approval, threadId: "thr_other" }} onResolve={onResolve} />
        <textarea aria-label="消息" />
      </>,
    );
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    finishResolution?.();

    expect(await screen.findByRole("button", { name: "批准" })).toBeEnabled();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(composer).toHaveFocus();
  });

  /** 卸载中的审批请求回包不能将焦点转交给已离开的结果节点。 */
  it("does not restore focus after its approval card unmounts", async () => {
    const user = userEvent.setup();
    let finishResolution: (() => void) | undefined;
    const onResolve = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishResolution = resolve;
        }),
    );
    const view = render(
      <>
        <ApprovalCard approval={approval} onResolve={onResolve} />
        <textarea aria-label="消息" />
      </>,
    );

    await user.click(screen.getByRole("button", { name: "批准" }));
    view.rerender(<textarea aria-label="消息" />);
    const composer = screen.getByRole("textbox", { name: "消息" });
    await user.click(composer);
    finishResolution?.();

    expect(composer).toHaveFocus();
  });

  /** 提交失败仍保留原决策按钮和焦点，用户可以立即重试。 */
  it("keeps the decision controls available after a failed resolution", async () => {
    const user = userEvent.setup();
    const onResolve = vi.fn().mockRejectedValue(new Error("offline"));
    render(<ApprovalCard approval={approval} onResolve={onResolve} />);

    const approve = screen.getByRole("button", { name: "批准" });
    await user.click(approve);

    expect(await screen.findByRole("alert")).toHaveTextContent("提交失败，请重试");
    expect(approve).toBeEnabled();
    expect(approve).toHaveFocus();
    expect(screen.getByRole("button", { name: "拒绝" })).toBeEnabled();
  });

  /** 系统关闭不伪造用户决定，也移除仅属于待确认阶段的说明与有效期。 */
  it("shows a generic system close when its Turn terminates without fabricating a decision", () => {
    render(<ApprovalCard approval={approval} closedAt="2099-08-17T12:00:01+08:00" />);
    expect(screen.getByRole("heading", { name: "审批请求已关闭" })).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("已关闭");
    expect(screen.queryByText("运行测试")).not.toBeInTheDocument();
    expect(screen.queryByText("有效期")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "批准" })).not.toBeInTheDocument();
  });

  /** 权威到期状态应与拒绝区分，并隐藏只属于等待阶段的说明和有效期。 */
  it("shows a persisted expiry close without fabricating a user decision", () => {
    render(
      <ApprovalCard
        approval={{ ...approval, closedReason: "expired" }}
        closedAt="2099-08-17T12:00:01+08:00"
      />,
    );

    expect(screen.getByRole("heading", { name: "审批请求已过期" })).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("已过期");
    expect(screen.queryByText("运行测试")).not.toBeInTheDocument();
    expect(screen.queryByText("有效期")).not.toBeInTheDocument();
    expect(screen.queryByText("已拒绝")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "批准" })).not.toBeInTheDocument();
  });

  /** 持久关闭原因分别呈现取消和替代，避免把系统变化伪装成用户拒绝。 */
  it("shows cancellation and supersession as distinct terminal states", () => {
    const view = render(
      <ApprovalCard
        approval={{ ...approval, closedReason: "cancelled" }}
        closedAt="2099-08-17T12:00:01+08:00"
      />,
    );

    expect(screen.getByRole("heading", { name: "审批已取消" })).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("已取消");
    expect(screen.queryByText("运行测试")).not.toBeInTheDocument();
    expect(screen.queryByText("有效期")).not.toBeInTheDocument();

    view.rerender(
      <ApprovalCard
        approval={{ ...approval, closedReason: "superseded" }}
        closedAt="2099-08-17T12:00:01+08:00"
      />,
    );
    expect(screen.getByRole("heading", { name: "审批请求已被替代" })).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("已被替代");
    expect(screen.queryByText("运行测试")).not.toBeInTheDocument();
    expect(screen.queryByText("有效期")).not.toBeInTheDocument();
  });

  it("resets local resolution when a reused card receives another approval", async () => {
    const approvalB: ApprovalSummary = {
      ...approval,
      approvalId: "appr_two",
      callId: "call_two",
      toolName: "read",
      reason: "读取工作区文件",
    };
    const onResolve = vi.fn();
    const { rerender } = render(<ApprovalCard approval={approval} resolvedDecision="approve" />);

    expect(screen.getByRole("status")).toHaveTextContent("已批准");
    rerender(<ApprovalCard approval={approvalB} onResolve={onResolve} />);

    await waitFor(() => {
      expect(screen.queryByRole("status")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "批准" })).toBeEnabled();
    });
    expect(screen.getByText("读取工作区文件")).toBeVisible();
    expect(screen.getByText("read")).toBeVisible();
    expect(screen.queryByText("call_two")).not.toBeInTheDocument();
    expect(screen.queryByText("已批准")).not.toBeInTheDocument();
  });
});
