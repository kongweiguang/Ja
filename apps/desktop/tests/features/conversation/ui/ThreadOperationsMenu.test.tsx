// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ThreadOperationsMenu } from "@/features/conversation";

afterEach(() => cleanup());

describe("ThreadOperationsMenu", () => {
  /** 关闭反馈必须走真实 IconButton，鼠标与键盘均只关闭提示，不重复发起压缩。 */
  it.each(["success", "error"] as const)("allows dismissing %s feedback", async (phase) => {
    const user = userEvent.setup();
    const onCompact = vi.fn();
    const onDismissFeedback = vi.fn();
    render(
      <ThreadOperationsMenu
        showCompactAction
        compaction={{ phase, message: "压缩反馈", retryable: false }}
        onCompact={onCompact}
        onDismissFeedback={onDismissFeedback}
      />,
    );
    const close = screen.getByRole("button", { name: "关闭上下文压缩提示" });
    expect(close).toHaveClass("ja-icon-button");
    expect(close.closest(".ja-thread-compaction-feedback")).not.toBeNull();
    await user.click(close);
    expect(onDismissFeedback).toHaveBeenCalledTimes(1);
    close.focus();
    await user.keyboard("{Enter}");
    expect(onDismissFeedback).toHaveBeenCalledTimes(2);
    expect(onCompact).not.toHaveBeenCalled();
  });

  it("routes the real compact action from an accessible Thread menu", async () => {
    const user = userEvent.setup();
    const onCompact = vi.fn();
    render(
      <ThreadOperationsMenu
        showCompactAction
        compaction={{ phase: "idle", retryable: false }}
        onCompact={onCompact}
        onDismissFeedback={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("button", { name: "打开对话操作" }));
    await user.click(screen.getByRole("menuitem", { name: "压缩上下文" }));
    expect(onCompact).toHaveBeenCalledOnce();
  });

  it("removes the known-busy action while preserving stable retry feedback", async () => {
    const user = userEvent.setup();
    const onCompact = vi.fn();
    const { rerender } = render(
      <ThreadOperationsMenu
        showCompactAction={false}
        compaction={{
          phase: "error",
          message: "对话正在执行，结束后可再次压缩。",
          retryable: true,
        }}
        onCompact={onCompact}
        onDismissFeedback={vi.fn()}
      />,
    );
    expect(screen.queryByRole("button", { name: "打开对话操作" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "压缩上下文" })).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("对话正在执行");
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();

    rerender(
      <ThreadOperationsMenu
        showCompactAction
        compaction={{
          phase: "error",
          message: "暂时无法精确计算 Token，请稍后重试。",
          retryable: true,
        }}
        onCompact={onCompact}
        onDismissFeedback={vi.fn()}
      />,
    );
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(onCompact).toHaveBeenCalledOnce();
  });

  it("announces pending and exact success states without offering duplicate work", () => {
    const { rerender } = render(
      <ThreadOperationsMenu
        showCompactAction={false}
        compaction={{ phase: "running", message: "正在压缩上下文…", retryable: false }}
        onCompact={vi.fn()}
        onDismissFeedback={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "上下文压缩中" })).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("正在压缩上下文");
    expect(screen.queryByRole("button", { name: "关闭上下文压缩提示" })).not.toBeInTheDocument();

    rerender(
      <ThreadOperationsMenu
        showCompactAction
        compaction={{
          phase: "success",
          message: "上下文已压缩：12,000 → 5,000 Token。",
          retryable: false,
        }}
        onCompact={vi.fn()}
        onDismissFeedback={vi.fn()}
      />,
    );
    expect(screen.getByRole("status")).toHaveTextContent("12,000 → 5,000 Token");
  });
});
