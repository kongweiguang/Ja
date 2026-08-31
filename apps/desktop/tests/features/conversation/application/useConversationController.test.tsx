// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { WorkspaceProjection } from "@/features/workspace";
import { useTimelineStore } from "@/features/conversation/application/timelineStore";
import type {
  ConversationHistoryPort,
  ConversationRuntimeState,
  ConversationThread,
} from "@/features/conversation/application/ports";
import { useConversationController } from "@/features/conversation/application/useConversationController";
import type { TimelineEvent } from "@/features/conversation/domain/timelineContracts";

const WORKSPACE: WorkspaceProjection = {
  kind: "project",
  workspaceId: "ws_project",
  rootPath: "C:\\demo",
  displayName: "demo",
  trust: "trusted",
};

const MODEL_SELECTION = {
  providerId: "provider_1",
  modelId: "model_1",
  reasoningLevel: "medium" as const,
};

type HistoryAdmissionStatus = Extract<
  ConversationRuntimeState["status"],
  "starting" | "ready" | "busy"
>;

/** 构造会话目录项，显式保留 workspace identity 以验证跨域污染会被拒绝。 */
function thread(threadId: string): ConversationThread {
  return {
    threadId,
    workspaceId: WORKSPACE.workspaceId,
    preferences: {
      ...MODEL_SELECTION,
      accessMode: "approval_required",
      titleSource: "placeholder",
    },
    title: "新对话",
    status: "active",
    revision: 0,
    createdAt: "2026-08-28T00:00:00Z",
    updatedAt: "2026-08-28T00:00:00Z",
  };
}

/** 为不关注目录变更的用例提供完整窄端口，避免旧 fixture 隐式缺少生产能力。 */
function historyExtensions(): Pick<
  ConversationHistoryPort,
  "threadSearch" | "threadRename" | "threadPreferencesUpdate"
> {
  return {
    threadSearch: vi.fn(async () => ({ items: [], nextCursor: null })),
    threadRename: vi.fn(async () => thread("thr_unused")),
    threadPreferencesUpdate: vi.fn(async () => thread("thr_unused")),
  };
}

/** 构造已由协议层校验的标题事件，测试只改变 admission fence 所需字段。 */
function metadataEvent(
  overrides: Partial<Extract<TimelineEvent, { method: "thread/metadata-changed" }>["params"]> = {},
): Extract<TimelineEvent, { method: "thread/metadata-changed" }> {
  return {
    jsonrpc: "2.0",
    method: "thread/metadata-changed",
    params: {
      serverInstanceId: "srv_1",
      eventId: "evt_title_1",
      sequence: 1,
      occurredAt: "2026-08-30T12:00:00Z",
      generation: 1,
      workspaceId: WORKSPACE.workspaceId,
      threadId: "thr_metadata",
      revision: 2,
      title: "自动标题",
      titleSource: "auto",
      ...overrides,
    },
  };
}

describe("useConversationController", () => {
  afterEach(() => {
    cleanup();
    useTimelineStore.getState().reset();
  });

  it("空 workspace 历史通过 cwd/title/model 创建 Thread 并恢复 snapshot", async () => {
    const created = thread("thr_created");
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [], nextCursor: null })),
      threadCreate: vi.fn(async () => created),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const runtimeState = { status: "ready" as const, generation: 1, serverInstanceId: "srv_1" };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState,
        activateWorkspace: async () => undefined,
      }),
    );

    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_created"));
    expect(history.threadCreate).toHaveBeenCalledWith({
      cwd: "C:\\demo",
      title: "新对话",
      providerId: "provider_1",
      modelId: "model_1",
      reasoningLevel: "medium",
      accessMode: "approval_required",
    });
    expect(useTimelineStore.getState().threads["thr_created"]?.workspaceId).toBe("ws_project");
  });

  it("模型切换用显式 Provider/Model 创建并选中新 Thread，不受旧活动选择闭包影响", async () => {
    const initial = thread("thr_initial");
    const switched = {
      ...thread("thr_switched"),
      threadId: "thr_initial",
      preferences: {
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "approval_required" as const,
        titleSource: "placeholder" as const,
      },
    };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadPreferencesUpdate: vi.fn(async () => switched),
      threadList: vi.fn(async () => ({ items: [initial], nextCursor: null })),
      threadCreate: vi.fn(async () => switched),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_initial"));

    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "approval_required",
      }),
    );

    expect(history.threadPreferencesUpdate).toHaveBeenCalledWith({
      threadId: "thr_initial",
      providerId: "provider_1",
      modelId: "model_2",
      reasoningLevel: "medium",
      accessMode: "approval_required",
      expectedThreadRevision: 0,
    });
    expect(result.current.currentThreadId).toBe("thr_initial");
    expect(result.current.threads[0]?.preferences?.modelId).toBe("model_2");
  });

  it("连续更新偏好使用前一次 RPC 返回的新 revision，不被滞后的 Timeline revision 覆盖", async () => {
    const initial = thread("thr_preferences");
    const first = {
      ...initial,
      revision: 1,
      preferences: {
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "full_access" as const,
        titleSource: "placeholder" as const,
      },
    };
    const second = {
      ...first,
      revision: 2,
      preferences: { ...first.preferences, accessMode: "approval_required" as const },
    };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadPreferencesUpdate: vi.fn().mockResolvedValueOnce(first).mockResolvedValueOnce(second),
      threadList: vi.fn(async () => ({ items: [initial], nextCursor: null })),
      threadCreate: vi.fn(async () => initial),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_preferences"));

    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "full_access",
      }),
    );
    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        modelId: "model_2",
        accessMode: "approval_required",
      }),
    );

    expect(history.threadPreferencesUpdate).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedThreadRevision: 1, accessMode: "approval_required" }),
    );
    expect(result.current.threads[0]?.revision).toBe(2);
  });

  it("取消与续发后偏好 CAS 冲突会权威重读并只重放一次", async () => {
    const initial = { ...thread("thr_recover_preferences"), revision: 17 };
    const updated = {
      ...initial,
      revision: 19,
      preferences: {
        ...initial.preferences!,
        accessMode: "approval_required" as const,
      },
    };
    const threadPreferencesUpdate = vi
      .fn<ConversationHistoryPort["threadPreferencesUpdate"]>()
      .mockRejectedValueOnce({ code: "CONFLICT" })
      .mockResolvedValueOnce(updated);
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockImplementationOnce(async ({ threadId }) => ({
        threadId,
        revision: 17,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      }))
      .mockImplementationOnce(async ({ threadId }) => ({
        threadId,
        revision: 18,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadPreferencesUpdate,
      threadList: vi.fn(async () => ({ items: [initial], nextCursor: null })),
      threadCreate: vi.fn(async () => initial),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "full_access",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(initial.threadId));
    threadRead.mockClear();

    await act(async () =>
      result.current.updatePreferences({
        ...MODEL_SELECTION,
        accessMode: "approval_required",
      }),
    );

    expect(threadRead).toHaveBeenCalledOnce();
    expect(threadPreferencesUpdate).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ expectedThreadRevision: 17 }),
    );
    expect(threadPreferencesUpdate).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedThreadRevision: 18 }),
    );
    expect(threadPreferencesUpdate).toHaveBeenCalledTimes(2);
    expect(useTimelineStore.getState().threadRevisionByThread[initial.threadId]).toBe(18);
    expect(result.current.threads[0]?.revision).toBe(19);
  });

  it("uses the authoritative revision, reports exact Token reduction, and hides the action for active Turns", async () => {
    const existing = thread("thr_existing");
    const threadCompact = vi.fn(async (input: { expectedThreadRevision: number }) => ({
      outcome: "compacted" as const,
      compactionId: "cmp_existing",
      checkpointId: "checkpoint_existing",
      threadRevision: input.expectedThreadRevision + 1,
      inputTokensBefore: 12_000,
      inputTokensAfter: 5_000,
    }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 7,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact,
    };
    const runtimeState = { status: "ready" as const, generation: 1, serverInstanceId: "srv_1" };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState,
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.canCompact).toBe(true));
    await act(async () => result.current.compact());
    expect(threadCompact).toHaveBeenCalledWith({
      threadId: "thr_existing",
      expectedThreadRevision: 7,
    });
    expect(result.current.compaction).toEqual({
      phase: "success",
      message: "上下文已压缩：12,000 → 5,000 Token。",
      retryable: false,
    });

    act(() => {
      useTimelineStore.getState().applyTurnAccepted({
        threadId: "thr_existing",
        turnId: "turn_active",
        threadRevision: 8,
        submittedText: "继续",
        submittedAt: "2026-08-29T00:00:00Z",
      });
    });

    await waitFor(() => expect(result.current.canCompact).toBe(false));
    await act(async () => result.current.compact());
    expect(threadCompact).toHaveBeenCalledTimes(1);
  });

  it("等待 runtime ready 后只恢复一次历史，busy 切换不会重载当前会话", async () => {
    const existing = thread("thr_after_ready");
    const threadList = vi.fn(async () => ({ items: [existing], nextCursor: null }));
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const initialRuntimeProps: { status: HistoryAdmissionStatus } = {
      status: "starting",
    };
    const { result, rerender } = renderHook(
      ({ status }: { status: "starting" | "ready" | "busy" }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status, generation: 1, serverInstanceId: "srv_1" },
          activateWorkspace: async () => undefined,
        }),
      { initialProps: initialRuntimeProps },
    );

    expect(threadList).not.toHaveBeenCalled();
    expect(result.current.error).toBeUndefined();

    rerender({ status: "ready" });
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    expect(threadList).toHaveBeenCalledTimes(1);

    rerender({ status: "busy" });
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));
    expect(threadList).toHaveBeenCalledTimes(1);
  });

  it("terminal 后保留可见 Final 并只权威重读一次以恢复冻结 ChangeSet", async () => {
    const existing = thread("thr_terminal_refresh");
    let releaseTerminalSnapshot!: () => void;
    const terminalSnapshotGate = new Promise<void>((resolve) => {
      releaseTerminalSnapshot = resolve;
    });
    const threadRead = vi
      .fn<ConversationHistoryPort["threadRead"]>()
      .mockResolvedValueOnce({
        threadId: existing.threadId,
        revision: 0,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })
      .mockImplementation(async ({ threadId }) => {
        await terminalSnapshotGate;
        return {
          threadId,
          revision: 2,
          turns: [
            {
              turnId: "turn_terminal",
              status: "completed" as const,
              runtime: null,
              requestedAt: "2026-08-30T12:00:00Z",
              updatedAt: "2026-08-30T12:00:02Z",
              completedAt: "2026-08-30T12:00:02Z",
              errorCode: null,
              changeSet: {
                state: "available" as const,
                files: [
                  {
                    path: "src/main.ts",
                    status: "modified" as const,
                    additions: 3,
                    deletions: 1,
                    binary: false,
                    truncated: false,
                  },
                ],
                stats: {
                  files: 1,
                  additions: 3,
                  deletions: 1,
                  binaryFiles: 0,
                  truncated: false,
                },
                artifactId: "artifact_terminal_diff",
              },
            },
          ],
          items: [
            {
              itemId: "item_terminal_final",
              turnId: "turn_terminal",
              kind: "final_answer" as const,
              createdAt: "2026-08-30T12:00:02Z",
              text: "权威最终答复",
            },
          ],
          contextUsage: null,
          nextCursor: null,
        };
      });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead,
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe(existing.threadId));

    act(() => {
      useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/state-changed",
          params: {
            serverInstanceId: "srv_1",
            eventId: "evt_terminal_running",
            sequence: 1,
            generation: 1,
            workspaceId: WORKSPACE.workspaceId,
            threadId: existing.threadId,
            threadRevision: 1,
            turnId: "turn_terminal",
            occurredAt: "2026-08-30T12:00:01Z",
            from: "queued",
            to: "running",
          },
        },
      });
      useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/terminal",
          params: {
            serverInstanceId: "srv_1",
            eventId: "evt_terminal_completed",
            sequence: 2,
            generation: 1,
            workspaceId: WORKSPACE.workspaceId,
            threadId: existing.threadId,
            threadRevision: 2,
            turnId: "turn_terminal",
            occurredAt: "2026-08-30T12:00:02Z",
            state: "completed",
            summary: "完成",
            finalMessage: { messageId: "item_terminal_final", text: "可见最终答复" },
          },
        },
      });
    });

    expect(useTimelineStore.getState().items["item_terminal_final"]?.text).toBe("可见最终答复");
    expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBe("terminal_snapshot");
    await waitFor(() => expect(threadRead).toHaveBeenCalledTimes(2));

    await act(async () => {
      releaseTerminalSnapshot();
      await terminalSnapshotGate;
    });
    await waitFor(() =>
      expect(useTimelineStore.getState().turns["turn_terminal"]?.changeSet).toMatchObject({
        state: "available",
        stats: { files: 1, additions: 3, deletions: 1 },
      }),
    );
    expect(useTimelineStore.getState().items["item_terminal_final"]?.text).toBe("权威最终答复");
    expect(useTimelineStore.getState().resyncRequired[existing.threadId]).toBeUndefined();
    await act(async () => Promise.resolve());
    expect(threadRead).toHaveBeenCalledTimes(2);
  });

  it("keeps retryable compaction failures stable and redacted", async () => {
    const existing = thread("thr_retry");
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 3,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async () => {
        throw { code: "TOKEN_COUNT_UNAVAILABLE", detail: "private provider payload" };
      }),
    };
    const { result } = renderHook(() =>
      useConversationController({
        history,
        workspace: WORKSPACE,
        workspaceRevision: 1,
        modelSelection: MODEL_SELECTION,
        accessMode: "approval_required",
        runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
        activateWorkspace: async () => undefined,
      }),
    );
    await waitFor(() => expect(result.current.canCompact).toBe(true));
    await act(async () => result.current.compact());
    expect(result.current.compaction).toEqual({
      phase: "error",
      message: "暂时无法精确计算 Token，请稍后重试。",
      retryable: true,
    });
    expect(result.current.compaction.message).not.toContain("private");
  });

  /** 三段式标题都走同一事件门，先证明首问短标题不会在前端被静默丢弃。 */
  it("只接纳当前 runtime/workspace 中 revision 前进的临时、自动与人工标题", async () => {
    const existing = { ...thread("thr_metadata"), revision: 1 };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 1,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const runtimeState = { status: "ready" as const, generation: 1, serverInstanceId: "srv_1" };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState,
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_metadata"));

    rerender({
      event: metadataEvent({ title: "首问短标题", titleSource: "placeholder" }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("首问短标题"));
    expect(result.current.threads[0]?.preferences?.titleSource).toBe("placeholder");
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(2);

    rerender({
      event: metadataEvent({
        eventId: "evt_title_2",
        sequence: 2,
        revision: 3,
      }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("自动标题"));
    expect(result.current.threads[0]?.preferences?.titleSource).toBe("auto");
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(3);

    rerender({
      event: metadataEvent({
        eventId: "evt_title_3",
        sequence: 3,
        revision: 4,
        title: "人工标题",
        titleSource: "manual",
      }),
    });
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("人工标题"));
    expect(result.current.threads[0]?.preferences?.titleSource).toBe("manual");
    expect(result.current.threads[0]?.revision).toBe(4);
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(4);
  });

  it("拒绝陈旧 generation、server、workspace 与未前进 revision 的标题事件", async () => {
    const existing = { ...thread("thr_metadata"), revision: 5, title: "权威标题" };
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList: vi.fn(async () => ({ items: [existing], nextCursor: null })),
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 5,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );
    await waitFor(() => expect(result.current.threads[0]?.title).toBe("权威标题"));

    for (const event of [
      metadataEvent({ generation: 2, revision: 6 }),
      metadataEvent({ serverInstanceId: "srv_stale", revision: 6 }),
      metadataEvent({ workspaceId: "ws_other", revision: 6 }),
      metadataEvent({ revision: 5 }),
    ]) {
      rerender({ event });
      expect(result.current.threads[0]?.title).toBe("权威标题");
      expect(result.current.threads[0]?.revision).toBe(5);
    }
  });

  it("目录未缓存事件目标时回读权威列表，不从 metadata 补造 Thread", async () => {
    const existing = thread("thr_existing");
    const discovered = {
      ...thread("thr_metadata"),
      revision: 2,
      title: "权威人工标题",
      preferences: {
        ...thread("thr_metadata").preferences!,
        titleSource: "manual" as const,
      },
    };
    const threadList = vi
      .fn<ConversationHistoryPort["threadList"]>()
      .mockResolvedValueOnce({ items: [existing], nextCursor: null })
      .mockResolvedValueOnce({ items: [existing, discovered], nextCursor: null });
    const history: ConversationHistoryPort = {
      ...historyExtensions(),
      threadList,
      threadCreate: vi.fn(async () => existing),
      threadRead: vi.fn(async ({ threadId }) => ({
        threadId,
        revision: 0,
        turns: [],
        items: [],
        contextUsage: null,
        nextCursor: null,
      })),
      threadCompact: vi.fn(async (input) => ({
        outcome: "unchanged" as const,
        compactionId: null,
        checkpointId: null,
        threadRevision: input.expectedThreadRevision,
        inputTokensBefore: 0,
        inputTokensAfter: 0,
      })),
    };
    const { result, rerender } = renderHook(
      ({ event }: { event?: TimelineEvent }) =>
        useConversationController({
          history,
          workspace: WORKSPACE,
          workspaceRevision: 1,
          modelSelection: MODEL_SELECTION,
          accessMode: "approval_required",
          runtimeState: { status: "ready", generation: 1, serverInstanceId: "srv_1" },
          metadataEvent: event,
          activateWorkspace: async () => undefined,
        }),
      { initialProps: { event: undefined as TimelineEvent | undefined } },
    );
    await waitFor(() => expect(result.current.currentThreadId).toBe("thr_existing"));

    rerender({ event: metadataEvent({ title: "不可直接信任" }) });
    await waitFor(() => expect(threadList).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(result.current.threads.find((item) => item.threadId === "thr_metadata")?.title).toBe(
        "权威人工标题",
      ),
    );
    expect(useTimelineStore.getState().threadRevisionByThread["thr_metadata"]).toBe(2);
  });
});
