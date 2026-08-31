// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { TimelineApproval as ApprovalSummary } from "@/features/conversation/domain/timelineTypes";
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

  it("shows the public Tool context and emits an approve decision", async () => {
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

    expect(screen.getByText("运行测试")).toBeVisible();
    expect(screen.getByText("shell")).toBeVisible();
    expect(screen.getByText("call_one")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "批准" }));
    expect(decisions).toEqual(["approve"]);
    expect(screen.getByRole("status")).toHaveTextContent("已批准");
  });

  it("surfaces a replacement reason without inventing private command or path fields", () => {
    render(<ApprovalCard approval={{ ...approval, reason: "允许写入工作区文件" }} />);

    expect(screen.getByText("允许写入工作区文件")).toBeVisible();
    expect(screen.getByText("shell")).toBeVisible();
    expect(screen.queryByText(/pnpm test|C:\\dev\\ja/u)).not.toBeInTheDocument();
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

  it("emits deny as a terminal user decision", async () => {
    const user = userEvent.setup();
    const onResolve = vi.fn();
    render(<ApprovalCard approval={approval} onResolve={onResolve} />);
    await user.click(screen.getByRole("button", { name: "拒绝" }));
    expect(onResolve).toHaveBeenCalledWith("deny");
    expect(screen.getByRole("status")).toHaveTextContent("已拒绝");
  });

  it("closes a pending approval when its Turn terminates without fabricating a decision", () => {
    render(<ApprovalCard approval={approval} closedAt="2099-08-17T12:00:01+08:00" />);
    expect(screen.getByRole("status")).toHaveTextContent("Turn 已结束");
    expect(screen.queryByRole("button", { name: "批准" })).not.toBeInTheDocument();
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
    expect(screen.getByText("call_two")).toBeVisible();
    expect(screen.queryByText("已批准")).not.toBeInTheDocument();
  });
});
