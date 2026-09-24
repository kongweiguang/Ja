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
        liveStream: null,
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

  /** 贯通真实 Host Event、Zustand selector 与 Surface，防止自动重试只在 reducer 单测中可见。 */
  it("renders retry status from the live event and replaces it on the next delta", async () => {
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

    /** 所有帧都经同一个 Host adapter 入口，以覆盖同步 store 通知与 React selector 重渲染。 */
    const applyEvent = (event: TimelineEvent): void => {
      expect(useTimelineStore.getState().applyHostEvent({ kind: "timeline", event })).toBe(
        "applied",
      );
    };
    act(() => {
      applyEvent({
        jsonrpc: "2.0",
        method: "assistant/text-delta",
        params: {
          serverInstanceId: "srv_stream_surface",
          eventId: "evt_retry_surface_old_delta",
          sequence: 2,
          generation: 1,
          workspaceId: "ws_stream_surface",
          threadId: THREAD_ID,
          turnId: TURN_ID,
          threadRevision: 2,
          occurredAt: "2026-09-20T00:00:03Z",
          streamSeq: 1,
          text: "旧请求半截正文",
        },
      });
      applyEvent({
        jsonrpc: "2.0",
        method: "turn/retry-started",
        params: {
          serverInstanceId: "srv_stream_surface",
          eventId: "evt_retry_surface_started",
          sequence: 3,
          generation: 1,
          workspaceId: "ws_stream_surface",
          threadId: THREAD_ID,
          turnId: TURN_ID,
          threadRevision: 3,
          occurredAt: "2026-09-20T00:00:04Z",
          attempt: 2,
          maxAttempts: 6,
        },
      });
    });

    expect(await screen.findByText("正在工作 · 重试 2/6")).toBeDefined();
    expect(document.querySelector('[data-retry-status="true"]')).not.toBeNull();
    expect(screen.queryByText("旧请求半截正文")).toBeNull();
    expect(screen.getByRole("article", { name: "回复状态" }).textContent).toContain("正在工作");

    act(() => {
      applyEvent({
        jsonrpc: "2.0",
        method: "assistant/text-delta",
        params: {
          serverInstanceId: "srv_stream_surface",
          eventId: "evt_retry_surface_new_delta",
          sequence: 4,
          generation: 1,
          workspaceId: "ws_stream_surface",
          threadId: THREAD_ID,
          turnId: TURN_ID,
          threadRevision: 3,
          occurredAt: "2026-09-20T00:00:05Z",
          streamSeq: 2,
          text: "新请求正文",
        },
      });
    });
    expect(await screen.findByText("新请求正文")).toBeDefined();
    expect(document.querySelector('[data-retry-status="true"]')).toBeNull();
    expect(screen.queryByText("旧请求半截正文")).toBeNull();
  });

  /** thread/read 用持久 user_input 替换 turn/start 的临时条目时，完整 Surface 仍复用响应壳。 */
  it("keeps the authoritative response node when a healthy read replaces the local user item", async () => {
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

    const response = screen.getByRole("article", { name: "回复状态" });
    act(() => {
      expect(
        useTimelineStore.getState().applySnapshot(
          {
            threadId: THREAD_ID,
            revision: 2,
            turns: [
              {
                turnId: TURN_ID,
                status: "running",
                requestedAt: "2026-09-20T00:00:01Z",
                updatedAt: "2026-09-20T00:00:02Z",
                completedAt: null,
                errorCode: null,
                changeSet: null,
              },
            ],
            items: [
              {
                itemId: "item_persisted_stream_surface",
                turnId: TURN_ID,
                kind: "user_input",
                content: [{ type: "text", text: "测试流式隔离" }],
                attachments: [],
                createdAt: "2026-09-20T00:00:01Z",
              },
              {
                itemId: "item_tool_stream_surface",
                turnId: TURN_ID,
                kind: "tool_call",
                callId: "call_stream_surface_read",
                toolName: "read",
                ordinal: 0,
                presentation: {
                  kind: "read",
                  title: "读取 fixture",
                  status: "running",
                  relativePaths: ["fixture.txt"],
                  truncated: false,
                },
                createdAt: "2026-09-20T00:00:02Z",
              },
            ],
            inputQueue: null,
            contextUsage: null,
            taskActivities: [],
            goalActivities: [],
            liveStream: null,
            nextCursor: null,
          },
          "ws_stream_surface",
        ),
      ).toBe("applied");
    });

    expect(
      await screen.findByRole("button", { name: /读取，read，fixture\.txt，进行中/ }),
    ).toBeDefined();
    expect(screen.getByRole("article", { name: "回复状态" })).toBe(response);
    expect(response.textContent).toContain("正在工作");
  });
});
