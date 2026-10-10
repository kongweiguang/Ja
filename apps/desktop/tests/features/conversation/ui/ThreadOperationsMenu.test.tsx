// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ThreadOperationsMenu } from "@/features/conversation";
import { NotificationCenter } from "@/shared/ui/notifications";

/** 通知中心使用系统主题时需真实浏览器的 media-query 边界，测试只替代这一浏览器接口。 */
beforeEach(() => {
  vi.stubGlobal("matchMedia", (media: string) => ({
    matches: false,
    media,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }));
  render(<NotificationCenter />);
});
/** 每例撤回浏览器 stub 和通知挂载，避免主题或全局 Toaster 影响后续用例。 */
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("ThreadOperationsMenu", () => {
  /** 模态覆盖期间停用会话提示而不重建 owner，关闭后恢复同一 Sonner 标识。 @author kongweiguang */
  it("pauses Thread feedback while another modal is visible", async () => {
    const props = {
      scope: "thread_modal",
      showCompactAction: true,
      compaction: {
        phase: "success" as const,
        message: "上下文已压缩：12,000 → 5,000 Token。",
        retryable: false,
      },
      onCompact: vi.fn(),
      onCancelCompaction: vi.fn(),
      onDismissFeedback: vi.fn(),
    };
    const { rerender } = render(<ThreadOperationsMenu {...props} />);
    const feedback = await screen.findByRole("status");
    const toastId = feedback.closest<HTMLElement>("[data-sonner-toast]")?.dataset["sonnerToast"];
    expect(toastId).toBeDefined();

    rerender(<ThreadOperationsMenu {...props} enabled={false} />);
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());

    rerender(<ThreadOperationsMenu {...props} enabled />);
    const restored = await screen.findByRole("status");
    expect(restored.closest<HTMLElement>("[data-sonner-toast]")?.dataset["sonnerToast"]).toBe(
      toastId,
    );
  });

  /** 关闭反馈必须走真实 IconButton，鼠标与键盘均只关闭提示，不重复发起压缩。 */
  it.each(["success", "error"] as const)("allows dismissing %s feedback", async (phase) => {
    const user = userEvent.setup();
    const onCompact = vi.fn();
    const onDismissFeedback = vi.fn();
    render(
      <ThreadOperationsMenu
        scope="thread_a"
        showCompactAction
        compaction={{ phase, message: "压缩反馈", retryable: false }}
        onCompact={onCompact}
        onCancelCompaction={vi.fn()}
        onDismissFeedback={onDismissFeedback}
      />,
    );
    const close = await screen.findByRole("button", { name: "关闭上下文压缩提示" });
    expect(close).toHaveClass("ja-icon-button");
    expect(close.closest("[data-sonner-toast]")).not.toBeNull();
    if (phase === "success") await user.click(close);
    else {
      close.focus();
      await user.keyboard("{Enter}");
    }
    expect(onDismissFeedback).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(close).not.toBeInTheDocument());
    expect(onCompact).not.toHaveBeenCalled();
  });

  it("routes the real compact action from an accessible Thread menu", async () => {
    const user = userEvent.setup();
    const onCompact = vi.fn();
    render(
      <ThreadOperationsMenu
        scope="thread_a"
        showCompactAction
        compaction={{ phase: "idle", retryable: false }}
        onCompact={onCompact}
        onCancelCompaction={vi.fn()}
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
        scope="thread_a"
        showCompactAction={false}
        compaction={{
          phase: "error",
          message: "对话正在执行，结束后可再次压缩。",
          retryable: true,
        }}
        onCompact={onCompact}
        onCancelCompaction={vi.fn()}
        onDismissFeedback={vi.fn()}
      />,
    );
    expect(screen.queryByRole("button", { name: "打开对话操作" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "压缩上下文" })).not.toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("对话正在执行");
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();

    rerender(
      <ThreadOperationsMenu
        scope="thread_a"
        showCompactAction
        compaction={{
          phase: "error",
          message: "暂时无法精确计算 Token，请稍后重试。",
          retryable: true,
        }}
        onCompact={onCompact}
        onCancelCompaction={vi.fn()}
        onDismissFeedback={vi.fn()}
      />,
    );
    await user.click(await screen.findByRole("button", { name: "重试" }));
    expect(onCompact).toHaveBeenCalledOnce();
  });

  it("announces pending and exact success states without offering duplicate work", async () => {
    const { rerender } = render(
      <ThreadOperationsMenu
        scope="thread_a"
        showCompactAction={false}
        compaction={{ phase: "running", message: "正在压缩上下文…", retryable: false }}
        onCompact={vi.fn()}
        onCancelCompaction={vi.fn()}
        onDismissFeedback={vi.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: "上下文压缩中" })).toBeDisabled();
    expect(await screen.findByRole("status")).toHaveTextContent("正在压缩上下文");
    expect(screen.queryByRole("button", { name: "关闭上下文压缩提示" })).not.toBeInTheDocument();

    rerender(
      <ThreadOperationsMenu
        scope="thread_a"
        showCompactAction
        compaction={{
          phase: "success",
          message: "上下文已压缩：12,000 → 5,000 Token。",
          retryable: false,
        }}
        onCompact={vi.fn()}
        onCancelCompaction={vi.fn()}
        onDismissFeedback={vi.fn()}
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent("12,000 → 5,000 Token"),
    );
  });

  /** 活动手动压缩保留停止入口，自动压缩没有错误的跨任务取消按钮。 */
  it("stops only a cancellable manual compaction", async () => {
    const user = userEvent.setup();
    const onCancelCompaction = vi.fn();
    const { rerender } = render(
      <ThreadOperationsMenu
        scope="thread_a"
        showCompactAction={false}
        compaction={{
          phase: "running",
          message: "正在压缩上下文…",
          retryable: false,
          cancellable: true,
        }}
        onCompact={vi.fn()}
        onCancelCompaction={onCancelCompaction}
        onDismissFeedback={vi.fn()}
      />,
    );
    await user.click(await screen.findByRole("button", { name: "停止" }));
    expect(onCancelCompaction).toHaveBeenCalledOnce();
    rerender(
      <ThreadOperationsMenu
        scope="thread_a"
        showCompactAction={false}
        compaction={{ phase: "running", message: "正在压缩上下文…", retryable: false }}
        onCompact={vi.fn()}
        onCancelCompaction={onCancelCompaction}
        onDismissFeedback={vi.fn()}
      />,
    );
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "停止" })).not.toBeInTheDocument(),
    );
  });
});
