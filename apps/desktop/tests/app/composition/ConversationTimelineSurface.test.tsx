// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, render, screen } from "@testing-library/react";
import { useEffect, type ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useShallow } from "zustand/react/shallow";
import { ConversationTimelineSurface } from "@/app/composition/ConversationTimelineSurface";
import {
  selectCommittedItemsForThread,
  useTimelineStore,
  type TimelineEvent,
} from "@/features/conversation";

const THREAD_ID = "thr_stream_surface";
const TURN_ID = "turn_stream_surface";

/** 建立可接纳流式 delta 的最小权威投影，不绕过 reducer 或 Runtime generation gate。 */
function prepareStreamingTimeline(): void {
  const store = useTimelineStore.getState();
  store.reset();
  expect(
    store.applyHostEvent({
      kind: "status",
      status: { status: "ready", generation: 1, serverInstanceId: "srv_stream_surface" },
      eventId: "evt_stream_surface_ready",
      occurredAt: "2026-09-20T00:00:00Z",
    }),
  ).toBe("applied");
  expect(
    store.applySnapshot(
      {
        threadId: THREAD_ID,
        revision: 0,
        turns: [],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_stream_surface",
    ),
  ).toBe("applied");
  expect(
    store.applyTurnAccepted({
      threadId: THREAD_ID,
      turnId: TURN_ID,
      threadRevision: 1,
      submittedText: "测试流式隔离",
      submittedAt: "2026-09-20T00:00:01Z",
    }),
  ).toBe("applied");
  expect(
    store.applyHostEvent({
      kind: "timeline",
      event: {
        jsonrpc: "2.0",
        method: "turn/state-changed",
        params: {
          serverInstanceId: "srv_stream_surface",
          eventId: "evt_stream_surface_running",
          sequence: 1,
          generation: 1,
          workspaceId: "ws_stream_surface",
          threadId: THREAD_ID,
          turnId: TURN_ID,
          threadRevision: 2,
          occurredAt: "2026-09-20T00:00:02Z",
          from: "queued",
          to: "running",
        },
      },
    }),
  ).toBe("applied");
}

/** 先卸载真实 Virtualizer，再越过其 150ms scroll reset debounce，避免回调落到已销毁 jsdom。 */
afterEach(async () => {
  cleanup();
  await new Promise((resolve) => setTimeout(resolve, 180));
  useTimelineStore.getState().reset();
});

describe("ConversationTimelineSurface", () => {
  /** delta 只更新 Timeline owner；相邻回复栏不应跟随高频正文重新渲染。 */
  it("isolates assistant text deltas from the sibling composer surface", async () => {
    prepareStreamingTimeline();
    const onShellCommit = vi.fn();
    const onComposerCommit = vi.fn();

    /** 模拟真实布局中的稳定 Composer sibling，在 commit 后记录而不污染 render 纯度。 */
    function ComposerSurfaceProbe(): ReactElement {
      useEffect(() => onComposerCommit());
      return <form aria-label="测试回复栏" />;
    }

    /** Harness 复现 Workspace 的低频订阅，证明 Draft delta 不会提升到 Composer 共同父级。 */
    function StreamingSurfaceHarness(): ReactElement {
      const committedItems = useTimelineStore(
        useShallow((state) => selectCommittedItemsForThread(THREAD_ID)(state)),
      );
      useEffect(() => onShellCommit());
      return (
        <>
          <div data-testid="committed-count">{committedItems.length}</div>
          <ComposerSurfaceProbe />
          <ConversationTimelineSurface
            threadId={THREAD_ID}
            answeredRequest={null}
            answeredAnswers={{}}
          />
        </>
      );
    }

    render(<StreamingSurfaceHarness />);
    const settledShellCommits = onShellCommit.mock.calls.length;
    const settledComposerCommits = onComposerCommit.mock.calls.length;
    const delta: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_stream_surface",
        eventId: "evt_stream_surface_delta",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_stream_surface",
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: 2,
        occurredAt: "2026-09-20T00:00:03Z",
        streamSeq: 1,
        text: "流式正文仍然即时可见",
      },
    };

    act(() => {
      expect(useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: delta })).toBe(
        "applied",
      );
    });

    expect(await screen.findByText("流式正文仍然即时可见")).toBeDefined();
    expect(onShellCommit).toHaveBeenCalledTimes(settledShellCommits);
    expect(onComposerCommit).toHaveBeenCalledTimes(settledComposerCommits);
  });

  /** Draft 从首个 delta 起属于工作过程，Tool 提交只能替换其权威身份，不能改变展示边界。 */
  it("keeps a Tool-bound draft in WorkProcess without replacing the response shell", async () => {
    prepareStreamingTimeline();
    const runningTurn = useTimelineStore.getState().turns[TURN_ID];
    expect(runningTurn?.status).toBe("running");
    render(
      <ConversationTimelineSurface
        threadId={THREAD_ID}
        answeredRequest={null}
        answeredAnswers={{}}
        turns={runningTurn === undefined ? [] : [runningTurn]}
      />,
    );
    const delta: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/text-delta",
      params: {
        serverInstanceId: "srv_stream_surface",
        eventId: "evt_stream_surface_identity_delta",
        sequence: 2,
        generation: 1,
        workspaceId: "ws_stream_surface",
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: 2,
        occurredAt: "2026-09-20T00:00:03Z",
        streamSeq: 1,
        text: "保持原位的过程正文",
      },
    };
    act(() => {
      expect(useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: delta })).toBe(
        "applied",
      );
    });
    expect(await screen.findByText("保持原位的过程正文")).toBeDefined();
    const response = screen.getByRole("article", { name: "回复状态" });
    expect(response.textContent).not.toContain("保持原位的过程正文");
    expect(response.textContent).toContain("正在工作");
    expect(
      screen.getByText("保持原位的过程正文").closest('[data-role="commentary"]'),
    ).not.toBeNull();

    const committed: TimelineEvent = {
      jsonrpc: "2.0",
      method: "assistant/model-step-committed",
      params: {
        serverInstanceId: "srv_stream_surface",
        eventId: "evt_stream_surface_identity_committed",
        sequence: 3,
        generation: 1,
        workspaceId: "ws_stream_surface",
        threadId: THREAD_ID,
        turnId: TURN_ID,
        threadRevision: 3,
        occurredAt: "2026-09-20T00:00:04Z",
        messageId: "item_stream_surface_identity",
        text: "保持原位的过程正文",
        modelRound: 1,
        toolCalls: [
          {
            callId: "call_stream_surface_identity",
            toolName: "read",
            ordinal: 0,
            presentation: {
              kind: "read",
              title: "读取 fixture",
              status: "pending",
              relativePaths: ["README.md"],
              truncated: false,
            },
          },
        ],
      },
    };
    act(() => {
      expect(
        useTimelineStore.getState().applyHostEvent({ kind: "timeline", event: committed }),
      ).toBe("applied");
    });

    expect(screen.getByRole("article", { name: "回复状态" })).toBe(response);
    expect(response.textContent).not.toContain("保持原位的过程正文");
    expect(response.textContent).toContain("正在工作");
    expect(
      screen.getByText("保持原位的过程正文").closest('[data-role="commentary"]'),
    ).not.toBeNull();
    expect(screen.getByRole("region", { name: "工作过程" })).toBeDefined();
  });
});
