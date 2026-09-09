// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { cleanup, renderHook, waitFor } from "@testing-library/react";
import { StrictMode, useState, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useLatestTurnReviewPublisher } from "@/app/application/useLatestTurnReviewPublisher";
import type { TurnReviewTarget } from "@/features/workbench/review";

const TARGET: TurnReviewTarget = {
  kind: "frozen_turn",
  workspaceId: "ws_fixture",
  threadId: "thr_fixture",
  turnId: "turn_fixture",
  threadRevision: 4,
  state: "complete",
  incompleteReasons: [],
  files: [],
  stats: { files: 1, additions: 2, deletions: 0, binaryFiles: 0, truncated: false },
  artifactId: "artifact_fixture",
};

describe("useLatestTurnReviewPublisher", () => {
  afterEach(() => cleanup());

  it("只发布最近终态目标，新 Turn 不制造中间空值", () => {
    const publish = vi.fn();
    const { rerender } = renderHook(
      ({ target }: { target: TurnReviewTarget }) =>
        useLatestTurnReviewPublisher({
          workspaceId: target.workspaceId,
          threadId: target.threadId,
          target,
          publish,
        }),
      { initialProps: { target: TARGET } },
    );
    const next = { ...TARGET, turnId: "turn_next", artifactId: "artifact_next" };
    rerender({ target: next });
    expect(publish.mock.calls).toEqual([[TARGET], [next]]);
  });

  it("StrictMode 发布到父状态时稳定收敛", async () => {
    const { result } = renderHook(
      () => {
        const [published, publish] = useState<TurnReviewTarget>();
        useLatestTurnReviewPublisher({
          workspaceId: TARGET.workspaceId,
          threadId: TARGET.threadId,
          target: TARGET,
          publish,
        });
        return published;
      },
      { wrapper: ({ children }: { children: ReactNode }) => <StrictMode>{children}</StrictMode> },
    );
    await waitFor(() => expect(result.current).toBe(TARGET));
  });

  it("Thread 或 Workspace 切换时先撤销旧身份再发布新目标", () => {
    const publish = vi.fn();
    const { rerender } = renderHook(
      ({ target }: { target: TurnReviewTarget }) =>
        useLatestTurnReviewPublisher({
          workspaceId: target.workspaceId,
          threadId: target.threadId,
          target,
          publish,
        }),
      { initialProps: { target: TARGET } },
    );
    const next = {
      ...TARGET,
      workspaceId: "ws_next",
      threadId: "thr_next",
      turnId: "turn_next",
      artifactId: "artifact_next",
    };
    rerender({ target: next });
    expect(publish.mock.calls).toEqual([[TARGET], [undefined], [next]]);
  });

  it("卸载时使用最新 publisher 撤销一次", () => {
    const first = vi.fn();
    const latest = vi.fn();
    const { rerender, unmount } = renderHook(
      ({ publish }: { publish: (target: TurnReviewTarget | undefined) => void }) =>
        useLatestTurnReviewPublisher({
          workspaceId: TARGET.workspaceId,
          threadId: TARGET.threadId,
          target: TARGET,
          publish,
        }),
      { initialProps: { publish: first } },
    );
    rerender({ publish: latest });
    latest.mockClear();
    unmount();
    expect(first).toHaveBeenCalledTimes(1);
    expect(latest.mock.calls).toEqual([[undefined]]);
  });
});
