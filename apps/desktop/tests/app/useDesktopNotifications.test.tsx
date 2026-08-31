// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  useTimelineStore,
  type TimelineApproval as ApprovalSummary,
  type TimelineTurn as Turn,
} from "@/features/conversation";
import {
  collectDesktopNotificationTriggers,
  shouldDeliverDesktopNotification,
  useDesktopNotifications,
} from "@/app/useDesktopNotifications";

const runningTurn: Turn = {
  turnId: "turn_notice",
  threadId: "thr_notice",
  status: "running",
};

const approval: ApprovalSummary = {
  approvalId: "appr_notice",
  threadId: "thr_notice",
  turnId: "turn_notice",
  threadRevision: 1,
  callId: "call_notice",
  toolName: "shell",
  reason: "运行测试",
  expiresAt: "2099-08-22T12:00:00+08:00",
};

/**
 * 只挂载 notification subscription，使测试无需启动 Java/Tauri fixture 就能驱动真实的
 * 已归一化的 Zustand 状态。
 */
function NotificationFixture({
  enabled,
  notify,
  focused = false,
}: {
  enabled: boolean;
  notify: (kind: "completed" | "failed" | "approval") => Promise<void>;
  focused?: boolean;
}): null {
  useDesktopNotifications({ enabled, notify, isWindowFocused: async () => focused });
  return null;
}

describe("desktop notification policy", () => {
  beforeEach(() => {
    useTimelineStore.getState().reset();
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
  });

  afterEach(() => {
    cleanup();
    useTimelineStore.getState().reset();
  });

  it("maps completion, failure and a fresh approval without including payload fields", () => {
    const previous = { turns: { [runningTurn.turnId]: runningTurn }, approvalsById: {} };
    const current = {
      turns: {
        [runningTurn.turnId]: {
          ...runningTurn,
          status: "failed" as const,
          error: { code: "PROVIDER_FAILED", retryable: true },
        },
      },
      approvalsById: { [approval.approvalId]: { threadId: approval.threadId, approval } },
    };
    expect(collectDesktopNotificationTriggers(previous, current)).toEqual([
      { identity: "turn:turn_notice", kind: "failed" },
      { identity: "approval:appr_notice", kind: "approval" },
    ]);
  });

  it("delivers each identity once only while the app is unfocused", async () => {
    const notify = vi.fn(async () => undefined);
    render(<NotificationFixture enabled notify={notify} />);
    act(() => useTimelineStore.setState({ turns: { [runningTurn.turnId]: runningTurn } }));
    act(() =>
      useTimelineStore.setState({
        turns: { [runningTurn.turnId]: { ...runningTurn, status: "completed" } },
      }),
    );
    await waitFor(() => expect(notify).toHaveBeenCalledWith("completed"));

    act(() =>
      useTimelineStore.setState({
        turns: {
          [runningTurn.turnId]: {
            ...runningTurn,
            status: "completed",
            completedAt: "2099-08-22T12:00:00+08:00",
          },
        },
      }),
    );
    act(() =>
      useTimelineStore.setState({
        approvalsById: { [approval.approvalId]: { threadId: approval.threadId, approval } },
      }),
    );
    await waitFor(() => expect(notify).toHaveBeenCalledWith("approval"));
    expect(notify).toHaveBeenCalledTimes(2);
  });

  it("stays silent when disabled, focused, or first mounted over terminal history", async () => {
    const notify = vi.fn(async () => undefined);
    useTimelineStore.setState({
      turns: { [runningTurn.turnId]: { ...runningTurn, status: "completed" } },
    });
    const view = render(<NotificationFixture enabled notify={notify} />);
    await Promise.resolve();
    expect(notify).not.toHaveBeenCalled();

    act(() => useTimelineStore.setState({ turns: { [runningTurn.turnId]: runningTurn } }));
    view.rerender(<NotificationFixture enabled notify={notify} focused />);
    act(() =>
      useTimelineStore.setState({
        turns: { [runningTurn.turnId]: { ...runningTurn, status: "failed" } },
      }),
    );
    await Promise.resolve();
    expect(notify).not.toHaveBeenCalled();

    view.rerender(<NotificationFixture enabled={false} notify={notify} />);
    act(() =>
      useTimelineStore.setState({
        approvalsById: { [approval.approvalId]: { threadId: approval.threadId, approval } },
      }),
    );
    await Promise.resolve();
    expect(notify).not.toHaveBeenCalled();
  });

  it("uses document background directly and fails closed on focus errors", async () => {
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    const focus = vi.fn(async () => true);
    await expect(shouldDeliverDesktopNotification(focus)).resolves.toBe(true);
    expect(focus).not.toHaveBeenCalled();

    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
    await expect(
      shouldDeliverDesktopNotification(async () => {
        throw new Error("unavailable");
      }),
    ).resolves.toBe(false);
  });
});
