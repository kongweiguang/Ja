// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ConversationAcceptedTurn,
  ConversationCancelResult,
  ConversationInteractionOptions,
  ConversationPreferencesPort,
  ConversationQueuedInputResult,
  ConversationTurnPort,
} from "@/features/conversation";
import { useConversationInteractionController, useTimelineStore } from "@/features/conversation";

interface Deferred<T> {
  promise: Promise<T>;
  resolve(value: T): void;
  reject(error: unknown): void;
}

/** 用可控 Promise 精确推进 ACK 顺序，避免 sleep 掩盖竞态。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

/** 构造符合 Conversation 窄端口的 fake，测试不依赖 RuntimeProvider 或 Tauri。 */
function createTurnPort(): ConversationTurnPort {
  return {
    submitTurn: vi.fn(
      async (): Promise<ConversationAcceptedTurn> => ({
        accepted: true,
        turnId: "turn_default",
        queued: true,
        threadRevision: 1,
      }),
    ),
    cancelTurn: vi.fn(
      async ({ turnId }): Promise<ConversationCancelResult> => ({
        accepted: true,
        turnId,
        status: "running",
        threadRevision: 1,
      }),
    ),
    steerTurn: vi.fn(
      async ({ turnId }): Promise<ConversationQueuedInputResult> => ({
        accepted: true,
        inputId: "input_steering",
        turnId,
        kind: "steering",
        status: "queued",
      }),
    ),
    followUpTurn: vi.fn(
      async ({ turnId }): Promise<ConversationQueuedInputResult> => ({
        accepted: true,
        inputId: "input_follow_up",
        turnId,
        kind: "follow_up",
        status: "queued",
      }),
    ),
    approvalRespond: vi.fn(async () => undefined),
  };
}

/** 初始化 ready generation 与 Thread snapshot，使 application 竞态测试使用真实 reducer 状态。 */
function prepareThread(threadId = "thr_one", workspaceId = "ws_one"): void {
  useTimelineStore.getState().reset();
  useTimelineStore.getState().applyHostEvent({
    kind: "status",
    status: { status: "ready", generation: 1, serverInstanceId: "srv_test" },
    eventId: "evt_ready",
    occurredAt: "2026-08-28T00:00:00Z",
  });
  useTimelineStore
    .getState()
    .applySnapshot(
      { threadId, revision: 0, turns: [], items: [], contextUsage: null, nextCursor: null },
      workspaceId,
    );
}

/** 创建单个 Thread 的 controller 输入，便于 rerender 时只替换 identity 或 port。 */
function options(
  turnPort: ConversationTurnPort,
  preferencesPort: ConversationPreferencesPort,
  overrides: Partial<ConversationInteractionOptions> = {},
): ConversationInteractionOptions {
  return {
    threadId: "thr_one",
    workspaceId: "ws_one",
    preferences: {
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: "medium",
      accessMode: "approval_required",
      titleSource: "placeholder",
    },
    models: [
      {
        value: "selection_one",
        providerId: "provider_one",
        providerLabel: "服务商",
        modelId: "model_one",
        modelIdentifier: "model-one",
        modelLabel: "模型一",
        contextWindowTokens: 128_000,
        reasoningLevelMap: { medium: "medium" },
        defaultReasoningLevel: "medium",
      },
      {
        value: "selection_two",
        providerId: "provider_two",
        providerLabel: "服务商",
        modelId: "model_two",
        modelIdentifier: "model-two",
        modelLabel: "模型二",
        contextWindowTokens: 256_000,
        reasoningLevelMap: { medium: "medium" },
        defaultReasoningLevel: "medium",
      },
    ],
    ready: true,
    blocked: false,
    turnPort,
    preferencesPort,
    ...overrides,
  };
}

describe("useConversationInteractionController", () => {
  afterEach(() => {
    cleanup();
    useTimelineStore.getState().reset();
  });

  it("同一 Thread 重复提交只调用一次 Turn port，且不等待 ACK 就清空草稿", async () => {
    prepareThread();
    const ack = deferred<ConversationAcceptedTurn>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.submitTurn).mockImplementation(() => ack.promise);
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );

    act(() => result.current.updateDraft("检查竞态"));
    await waitFor(() => expect(result.current.draft).toBe("检查竞态"));
    let first!: Promise<void>;
    let duplicate!: Promise<void>;
    act(() => {
      first = result.current.send({ text: "检查竞态" });
      duplicate = result.current.send({ text: "检查竞态" });
    });

    expect(result.current.draft).toBe("");
    expect(result.current.sending).toBe(true);
    expect(turnPort.submitTurn).toHaveBeenCalledTimes(1);
    expect(turnPort.submitTurn).toHaveBeenCalledWith({
      threadId: "thr_one",
      content: [{ type: "text", text: "检查竞态" }],
    });
    await act(async () => {
      ack.resolve({ accepted: true, turnId: "turn_one", queued: true, threadRevision: 1 });
      await Promise.all([first, duplicate]);
    });
    expect(result.current.draft).toBe("");
  });

  it("提交只向 Runtime 发送当前 content blocks，不恢复旧 input 字段", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );

    await act(async () =>
      result.current.send({ text: "分析附件", attachmentIds: ["att_content_one"] }),
    );

    expect(turnPort.submitTurn).toHaveBeenCalledWith({
      threadId: "thr_one",
      content: [
        { type: "text", text: "分析附件" },
        { type: "attachment", attachmentId: "att_content_one" },
      ],
    });
    expect(vi.mocked(turnPort.submitTurn).mock.calls[0]?.[0]).not.toHaveProperty("input");
  });

  it("附件在等待 ACK 时立即移出输入器，提交失败后按 identity 恢复", async () => {
    prepareThread();
    const ack = deferred<ConversationAcceptedTurn>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.submitTurn)
      .mockImplementationOnce(() => ack.promise)
      .mockResolvedValueOnce({
        accepted: true,
        turnId: "turn_attachment_retry",
        queued: true,
        threadRevision: 1,
      });
    const attachmentPort = {
      importAttachments: vi.fn(async () => [
        { attachmentId: "att_one", fileName: "设计稿.pdf", sizeBytes: 2048 },
      ]),
      discardAttachment: vi.fn(async () => undefined),
    };
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(turnPort, modelPort, {
          attachmentPort,
        }),
      ),
    );
    await act(async () => result.current.importAttachments());
    expect(result.current.attachments).toHaveLength(1);

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.send({ text: "", attachmentIds: ["att_one"] });
    });
    expect(result.current.attachments).toHaveLength(0);
    await act(async () => {
      ack.reject(new Error("private failure"));
      await pending;
    });
    expect(result.current.attachments).toEqual([
      { attachmentId: "att_one", fileName: "设计稿.pdf", sizeBytes: 2048 },
    ]);

    await act(async () => result.current.send({ text: "", attachmentIds: ["att_one"] }));
    expect(turnPort.submitTurn).toHaveBeenCalledTimes(2);
    expect(result.current.attachments).toHaveLength(0);
  });

  it("旧 Thread 的晚到 ACK 不覆盖当前 Thread 草稿", async () => {
    prepareThread();
    const ack = deferred<ConversationAcceptedTurn>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.submitTurn).mockImplementation(() => ack.promise);
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const initial = options(turnPort, modelPort);
    const { result, rerender } = renderHook(
      (props: ConversationInteractionOptions) => useConversationInteractionController(props),
      { initialProps: initial },
    );

    act(() => result.current.updateDraft("旧请求"));
    await waitFor(() => expect(result.current.draft).toBe("旧请求"));
    let pending!: Promise<void>;
    act(() => {
      pending = result.current.send({ text: "旧请求" });
    });
    rerender(options(turnPort, modelPort, { threadId: "thr_two", workspaceId: "ws_two" }));
    act(() => result.current.updateDraft("当前草稿"));
    await waitFor(() => expect(result.current.draft).toBe("当前草稿"));

    await act(async () => {
      ack.resolve({ accepted: true, turnId: "turn_old", queued: true, threadRevision: 1 });
      await pending;
    });
    expect(result.current.draft).toBe("当前草稿");
    expect(result.current.activeTurn).toBe(false);
  });

  it("终态事件先于 ACK 到达时不会重新建立 pending", async () => {
    prepareThread();
    const ack = deferred<ConversationAcceptedTurn>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.submitTurn).mockImplementation(() => ack.promise);
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    act(() => result.current.updateDraft("快速完成"));
    await waitFor(() => expect(result.current.draft).toBe("快速完成"));
    let pending!: Promise<void>;
    act(() => {
      pending = result.current.send({ text: "快速完成" });
    });

    act(() => {
      useTimelineStore.getState().applyTurnAccepted({
        threadId: "thr_one",
        turnId: "turn_fast",
        threadRevision: 1,
        submittedText: "快速完成",
        submittedAt: "2026-08-28T00:00:01Z",
      });
      // 独立事件流按真实 revision 顺序先推进到终态，随后才释放 turn/start ACK。
      useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/state-changed",
          params: {
            serverInstanceId: "srv_test",
            eventId: "evt_fast_running",
            sequence: 1,
            generation: 1,
            workspaceId: "ws_one",
            threadId: "thr_one",
            turnId: "turn_fast",
            threadRevision: 2,
            occurredAt: "2026-08-28T00:00:02Z",
            from: "queued",
            to: "running",
          },
        },
      });
      useTimelineStore.getState().applyHostEvent({
        kind: "timeline",
        event: {
          jsonrpc: "2.0",
          method: "turn/state-changed",
          params: {
            serverInstanceId: "srv_test",
            eventId: "evt_fast_completed",
            sequence: 2,
            generation: 1,
            workspaceId: "ws_one",
            threadId: "thr_one",
            turnId: "turn_fast",
            threadRevision: 3,
            occurredAt: "2026-08-28T00:00:03Z",
            from: "running",
            to: "completed",
          },
        },
      });
    });
    await act(async () => {
      ack.resolve({ accepted: true, turnId: "turn_fast", queued: true, threadRevision: 1 });
      await pending;
    });
    expect(result.current.activeTurn).toBe(false);
  });

  it("取消冻结点击时的 Turn revision，切换 Thread 后不改变 CAS", async () => {
    prepareThread();
    useTimelineStore.getState().applyTurnAccepted({
      threadId: "thr_one",
      turnId: "turn_cancel",
      threadRevision: 1,
      submittedText: "执行任务",
      submittedAt: "2026-08-28T00:00:01Z",
    });
    const cancellation = deferred<ConversationCancelResult>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.cancelTurn).mockImplementation(() => cancellation.promise);
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result, rerender } = renderHook(
      (props: ConversationInteractionOptions) => useConversationInteractionController(props),
      { initialProps: options(turnPort, modelPort) },
    );

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.cancel();
    });
    expect(turnPort.cancelTurn).toHaveBeenCalledWith({
      turnId: "turn_cancel",
      expectedThreadRevision: 1,
    });
    rerender(options(turnPort, modelPort, { threadId: "thr_two", workspaceId: "ws_two" }));
    await act(async () => {
      cancellation.resolve({
        accepted: true,
        turnId: "turn_cancel",
        status: "running",
        threadRevision: 1,
      });
      await pending;
    });
    expect(turnPort.cancelTurn).toHaveBeenCalledTimes(1);
  });

  it("追加输入成功后只清空未晚编辑的 Draft", async () => {
    prepareThread();
    useTimelineStore.getState().applyTurnAccepted({
      threadId: "thr_one",
      turnId: "turn_queue",
      threadRevision: 1,
      submittedText: "执行任务",
      submittedAt: "2026-08-28T00:00:01Z",
    });
    const queued = deferred<ConversationQueuedInputResult>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.steerTurn).mockImplementation(() => queued.promise);
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    act(() => result.current.updateDraft("先检查"));
    await waitFor(() => expect(result.current.draft).toBe("先检查"));
    let pending!: Promise<void>;
    act(() => {
      pending = result.current.queue("先检查", "steering");
    });
    expect(result.current.draft).toBe("");
    expect(result.current.sending).toBe(true);
    act(() => result.current.updateDraft("补充新内容"));
    await act(async () => {
      queued.resolve({
        accepted: true,
        inputId: "input_queue",
        turnId: "turn_queue",
        kind: "steering",
        status: "queued",
      });
      await pending;
    });

    expect(turnPort.steerTurn).toHaveBeenCalledWith({ turnId: "turn_queue", text: "先检查" });
    expect(result.current.draft).toBe("补充新内容");
    expect(result.current.queueStatus).toBe("已加入立即引导队列");
  });

  it("追加输入失败把原文合并到晚编辑之前，并释放 guard 允许重试", async () => {
    prepareThread();
    useTimelineStore.getState().applyTurnAccepted({
      threadId: "thr_one",
      turnId: "turn_queue_retry",
      threadRevision: 1,
      submittedText: "执行任务",
      submittedAt: "2026-08-28T00:00:01Z",
    });
    const queued = deferred<ConversationQueuedInputResult>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.followUpTurn)
      .mockImplementationOnce(() => queued.promise)
      .mockResolvedValueOnce({
        accepted: true,
        inputId: "input_retry",
        turnId: "turn_queue_retry",
        kind: "follow_up",
        status: "queued",
      });
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    act(() => result.current.updateDraft("完成后补充"));
    await waitFor(() => expect(result.current.draft).toBe("完成后补充"));

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.queue("完成后补充", "follow_up");
    });
    expect(result.current.draft).toBe("");
    act(() => result.current.updateDraft("等待期间的新内容"));
    await act(async () => {
      queued.reject(new Error("private queue failure"));
      await pending;
    });
    expect(result.current.draft).toBe("完成后补充\n\n等待期间的新内容");
    expect(result.current.error).toBe("排队失败，Turn 可能已结束，请重试。");
    await act(async () => result.current.queue(result.current.draft, "follow_up"));
    expect(turnPort.followUpTurn).toHaveBeenCalledTimes(2);
    expect(turnPort.followUpTurn).toHaveBeenLastCalledWith({
      turnId: "turn_queue_retry",
      text: "完成后补充\n\n等待期间的新内容",
    });
    expect(result.current.draft).toBe("");
  });

  it("提交失败把原文合并到晚编辑之前，并释放 single-flight 允许重试", async () => {
    prepareThread();
    const ack = deferred<ConversationAcceptedTurn>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.submitTurn)
      .mockImplementationOnce(() => ack.promise)
      .mockResolvedValueOnce({
        accepted: true,
        turnId: "turn_retry",
        queued: true,
        threadRevision: 1,
      });
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    act(() => result.current.updateDraft("保留草稿"));
    await waitFor(() => expect(result.current.draft).toBe("保留草稿"));

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.send({ text: "保留草稿" });
    });
    expect(result.current.draft).toBe("");
    act(() => result.current.updateDraft("等待期间的新问题"));
    await act(async () => {
      ack.reject(new Error("private failure"));
      await pending;
    });
    expect(result.current.draft).toBe("保留草稿\n\n等待期间的新问题");
    expect(result.current.error).toBe("发送失败，请检查运行时连接后重试。");
    await act(async () => result.current.send({ text: result.current.draft }));
    expect(turnPort.submitTurn).toHaveBeenCalledTimes(2);
    expect(turnPort.submitTurn).toHaveBeenLastCalledWith({
      threadId: "thr_one",
      content: [{ type: "text", text: "保留草稿\n\n等待期间的新问题" }],
    });
    expect(result.current.draft).toBe("");
  });

  it("模型切换失败只显示脱敏错误并可重试", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    const modelPort = {
      updatePreferences: vi
        .fn()
        .mockRejectedValueOnce(new Error("secret adapter detail"))
        .mockResolvedValueOnce(undefined),
    };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );

    await act(async () => result.current.changeModel("selection_two"));
    expect(result.current.error).toBe("会话偏好更新失败，请刷新会话状态后重试。");
    await act(async () => result.current.changeModel("selection_two"));
    expect(modelPort.updatePreferences).toHaveBeenCalledTimes(2);
  });

  it("Reasoning 与访问模式只提交可写偏好字段", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );

    await act(async () => result.current.changeReasoning(null));
    await act(async () => result.current.changeAccessMode("full_access"));

    expect(modelPort.updatePreferences).toHaveBeenNthCalledWith(1, {
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: null,
      accessMode: "approval_required",
    });
    expect(modelPort.updatePreferences).toHaveBeenNthCalledWith(2, {
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: "medium",
      accessMode: "full_access",
    });
  });

  it("旧 Thread 偏好失败后切换新 Thread 不继承错误或 busy", async () => {
    prepareThread();
    prepareThread("thr_two", "ws_two");
    const turnPort = createTurnPort();
    const modelPort = {
      updatePreferences: vi.fn().mockRejectedValueOnce(new Error("private failure")),
    };
    const initial = options(turnPort, modelPort);
    const { result, rerender } = renderHook(
      (props: ConversationInteractionOptions) => useConversationInteractionController(props),
      { initialProps: initial },
    );

    await act(async () => result.current.changeModel("selection_two"));
    expect(result.current.error).toBe("会话偏好更新失败，请刷新会话状态后重试。");
    rerender(options(turnPort, modelPort, { threadId: "thr_two", workspaceId: "ws_two" }));

    expect(result.current.error).toBeUndefined();
    expect(result.current.preferenceBusy).toBe(false);
  });

  it("Approval 只发送请求携带的 typed identity 与 revision", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    const approval = {
      approvalId: "appr_one",
      threadId: "thr_one",
      turnId: "turn_one",
      threadRevision: 7,
      callId: "call_one",
      toolName: "shell",
      reason: "需要确认",
      expiresAt: "2026-08-28T00:10:00Z",
    };

    await act(async () => result.current.approve(approval, "approve"));
    expect(turnPort.approvalRespond).toHaveBeenCalledWith({
      approvalId: "appr_one",
      turnId: "turn_one",
      decision: "approve",
      expectedThreadRevision: 7,
    });
  });
});
