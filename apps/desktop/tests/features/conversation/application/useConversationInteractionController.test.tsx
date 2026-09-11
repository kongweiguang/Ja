// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ConversationAcceptedTurn,
  ConversationAttachmentPort,
  ConversationCancelResult,
  ConversationPlanCreationPort,
  ConversationInteractionOptions,
  ConversationPreferencesPort,
  ConversationInputQueueMutationResult,
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
    resumeTurn: vi.fn(async ({ turnId, expectedThreadRevision }) => ({
      accepted: true as const,
      turnId,
      queued: true,
      threadRevision: expectedThreadRevision + 1,
    })),
    cancelTurn: vi.fn(
      async ({ turnId }): Promise<ConversationCancelResult> => ({
        accepted: true,
        turnId,
        status: "running",
        threadRevision: 1,
      }),
    ),
    enqueueTurnInput: vi.fn(
      async ({ turnId, content }): Promise<ConversationInputQueueMutationResult> => ({
        accepted: true,
        inputId: "input_follow_up",
        inputQueue: {
          turnId,
          revision: 1,
          accepting: true,
          items: [
            {
              inputId: "input_follow_up",
              turnId,
              content,
              attachments: [],
              kind: "follow_up",
              status: "pending",
              issue: null,
              inputRevision: 0,
              createdAt: "2026-09-01T00:00:00Z",
            },
          ],
        },
      }),
    ),
    prioritizeTurnInput: vi.fn(async ({ turnId, inputId }) => ({
      accepted: true as const,
      inputId,
      inputQueue: { turnId, revision: 2, accepting: true, items: [] },
    })),
    updateTurnInput: vi.fn(async ({ turnId, inputId }) => ({
      accepted: true as const,
      inputId,
      inputQueue: { turnId, revision: 2, accepting: true, items: [] },
    })),
    deleteTurnInput: vi.fn(
      async ({ turnId, inputId }): Promise<ConversationInputQueueMutationResult> => ({
        accepted: true,
        inputId,
        inputQueue: { turnId, revision: 2, accepting: true, items: [] },
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
  useTimelineStore.getState().applySnapshot(
    {
      threadId,
      revision: 0,
      turns: [],
      items: [],
      inputQueue: null,
      contextUsage: null,
      taskActivities: [],
      goalActivities: [],
      nextCursor: null,
    },
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
      collaborationMode: "default",
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
    expect(result.current.localSubmissions).toEqual([
      expect.objectContaining({
        submissionId: expect.stringMatching(/^submission:/),
        threadId: "thr_one",
        text: "检查竞态",
        status: "pending",
      }),
    ]);
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
    expect(result.current.localSubmissions).toEqual([]);
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
        { type: "attachment", attachmentId: "att_content_one" },
        { type: "text", text: "分析附件" },
      ],
    });
    expect(vi.mocked(turnPort.submitTurn).mock.calls[0]?.[0]).not.toHaveProperty("input");
  });

  it("首次 Plan 提交只在独立 Plan 创建 ACK 后启动 Turn", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    const created = deferred<boolean>();
    const planCreationPort: ConversationPlanCreationPort = {
      create: vi.fn(() => created.promise),
    };
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(turnPort, modelPort, {
          preferences: {
            providerId: "provider_one",
            modelId: "model_one",
            reasoningLevel: "medium",
            accessMode: "full_access",
            collaborationMode: "plan",
            titleSource: "placeholder",
          },
          planCreationPort,
        }),
      ),
    );

    let sending!: Promise<void>;
    act(() => {
      sending = result.current.send({ text: "制定并实施 Goal" });
    });
    await waitFor(() => expect(planCreationPort.create).toHaveBeenCalledOnce());
    expect(planCreationPort.create).toHaveBeenCalledWith("thr_one", "制定并实施 Goal", 0);
    expect(turnPort.submitTurn).not.toHaveBeenCalled();

    created.resolve(true);
    await act(async () => sending);
    expect(turnPort.submitTurn).toHaveBeenCalledOnce();
  });

  it("Plan artifact 创建未获 ACK 时失败关闭且不启动 Turn", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    const planCreationPort: ConversationPlanCreationPort = {
      create: vi.fn(async () => false),
    };
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(turnPort, modelPort, {
          preferences: {
            providerId: "provider_one",
            modelId: "model_one",
            reasoningLevel: "medium",
            accessMode: "approval_required",
            collaborationMode: "plan",
            titleSource: "placeholder",
          },
          planCreationPort,
        }),
      ),
    );

    await act(async () => result.current.send({ text: "先创建目标" }));
    expect(planCreationPort.create).toHaveBeenCalledOnce();
    expect(turnPort.submitTurn).not.toHaveBeenCalled();
  });

  it("Default 模式不创建 Plan，Plan 模式即使已有 Goal 也只创建独立 Plan", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    const planCreationPort: ConversationPlanCreationPort = {
      create: vi.fn(async () => true),
    };
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result, rerender } = renderHook(
      (props: ConversationInteractionOptions) => useConversationInteractionController(props),
      { initialProps: options(turnPort, modelPort, { planCreationPort }) },
    );

    await act(async () => result.current.send({ text: "普通执行" }));
    expect(planCreationPort.create).not.toHaveBeenCalled();

    prepareThread("thr_two");
    rerender(
      options(turnPort, modelPort, {
        threadId: "thr_two",
        preferences: {
          providerId: "provider_one",
          modelId: "model_one",
          reasoningLevel: "medium",
          accessMode: "approval_required",
          collaborationMode: "plan",
          titleSource: "placeholder",
        },
        planCreationPort,
      }),
    );
    await act(async () => result.current.send({ text: "继续已有目标" }));
    expect(planCreationPort.create).toHaveBeenCalledOnce();
    expect(planCreationPort.create).toHaveBeenCalledWith("thr_two", "继续已有目标", 0);
    expect(turnPort.submitTurn).toHaveBeenCalledTimes(2);
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
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ operationId, onEvent }) => {
        onEvent({
          kind: "started",
          operationId,
          attemptId: "attempt_one",
          itemId: "item_one",
          fileName: "设计稿.png",
          sizeBytes: 2048,
          mediaKind: "image",
          mediaType: "image/png",
        });
        onEvent({
          kind: "completed",
          operationId,
          attemptId: "attempt_one",
          itemId: "item_one",
          attachment: {
            attachmentId: "att_one",
            fileName: "设计稿.png",
            sizeBytes: 2048,
            mediaKind: "image",
            mediaType: "image/png",
            thumbnailUrl: "ja-attachment://thumb_one",
          },
        });
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const onAttachmentsBound = vi.fn();
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(turnPort, modelPort, {
          attachmentPort,
          onAttachmentsBound,
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
    expect(result.current.localSubmissions[0]?.attachments).toEqual([
      {
        attachmentId: "att_one",
        displayName: "设计稿.png",
        sizeBytes: 2048,
        mediaKind: "image",
        mediaType: "image/png",
        thumbnailUrl: "ja-attachment://thumb_one",
      },
    ]);
    expect(turnPort.submitTurn).toHaveBeenNthCalledWith(1, {
      threadId: "thr_one",
      content: [{ type: "attachment", attachmentId: "att_one" }],
      projectionAttachments: [
        {
          attachmentId: "att_one",
          displayName: "设计稿.png",
          sizeBytes: 2048,
          mediaKind: "image",
          mediaType: "image/png",
        },
      ],
    });
    await act(async () => {
      ack.reject(new Error("private failure"));
      await pending;
    });
    expect(onAttachmentsBound).not.toHaveBeenCalled();
    expect(result.current.attachments).toEqual([
      {
        state: "ready",
        itemId: "item_one",
        attachmentId: "att_one",
        fileName: "设计稿.png",
        sizeBytes: 2048,
        mediaKind: "image",
        mediaType: "image/png",
        thumbnailUrl: "ja-attachment://thumb_one",
      },
    ]);

    await act(async () => result.current.send({ text: "", attachmentIds: ["att_one"] }));
    expect(turnPort.submitTurn).toHaveBeenCalledTimes(2);
    expect(onAttachmentsBound).toHaveBeenCalledWith("thr_one", ["att_one"]);
    expect(result.current.attachments).toHaveLength(0);
  });

  /** 剪贴板无可导入内容或短暂占用只进入轻量状态区，不伪造失败附件对象。 */
  it("按严格 outcome 投影剪贴板反馈且不创建虚假附件", async () => {
    prepareThread();
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async () => undefined),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi
        .fn()
        .mockResolvedValueOnce({ outcome: "busy" as const })
        .mockResolvedValueOnce({ outcome: "nothing_importable" as const })
        .mockResolvedValueOnce({ outcome: "accepted" as const }),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(
          createTurnPort(),
          { updatePreferences: vi.fn(async () => undefined) },
          { attachmentPort },
        ),
      ),
    );

    await act(async () => result.current.importClipboard());
    expect(result.current.clipboardNotice).toBe("剪贴板正被其他应用占用，请稍后重试。");
    await act(async () => result.current.importClipboard());
    expect(result.current.clipboardNotice).toBe("剪贴板中没有可导入的文件或图片。");
    await act(async () => result.current.importClipboard());
    expect(result.current.clipboardNotice).toBe("已从剪贴板添加附件。");
    expect(result.current.attachmentDraftItems).toEqual([]);
  });

  /** 每个 Channel item 独立推进，未决态阻止发送，取消只作用于用户点击的那一项。 */
  it("投影逐项进度并对 importing 执行协作取消", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    let emit!: Parameters<ConversationAttachmentPort["pickerImport"]>[0]["onEvent"];
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ onEvent }) => {
        emit = onEvent;
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async ({ operationId, itemId }) => {
        emit({
          kind: "cancelled",
          operationId,
          attemptId: "attempt_copy",
          itemId: itemId!,
        });
      }),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(turnPort, { updatePreferences: vi.fn(async () => undefined) }, { attachmentPort }),
      ),
    );
    await act(async () => result.current.importAttachments());
    act(() => {
      emit({
        kind: "started",
        operationId: vi.mocked(attachmentPort.pickerImport).mock.calls[0]![0].operationId,
        attemptId: "attempt_copy",
        itemId: "item_copy",
        fileName: "数据.txt",
        sizeBytes: 100,
        mediaKind: "text",
      });
      emit({
        kind: "progress",
        operationId: vi.mocked(attachmentPort.pickerImport).mock.calls[0]![0].operationId,
        attemptId: "attempt_copy",
        itemId: "item_copy",
        phase: "copying",
        bytesCopied: 40,
        totalBytes: 100,
      });
    });
    expect(result.current.attachmentDraftItems).toEqual([
      expect.objectContaining({ state: "importing", itemId: "item_copy", bytesCopied: 40 }),
    ]);
    await act(async () => result.current.send({ text: "不能静默漏发" }));
    expect(turnPort.submitTurn).not.toHaveBeenCalled();

    await act(async () => result.current.removeAttachment("item_copy"));
    expect(attachmentPort.cancelImport).toHaveBeenCalledWith({
      operationId: expect.stringMatching(/^op_/),
      itemId: "item_copy",
    });
    expect(result.current.attachmentDraftItems).toHaveLength(0);
  });

  /** command rejection 也必须收敛 started 项，避免没有 failed 事件时 Composer 永久卡在导入中。 */
  it("Channel 命令在 started 后失败时保留可重试项", async () => {
    prepareThread();
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ operationId, onEvent }) => {
        onEvent({
          kind: "started",
          operationId,
          attemptId: "attempt_command_failure",
          itemId: "item_command_failure",
          fileName: "恢复说明.txt",
          sizeBytes: 24,
          mediaKind: "text",
        });
        throw new Error("private native failure");
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(
          createTurnPort(),
          { updatePreferences: vi.fn(async () => undefined) },
          {
            attachmentPort,
          },
        ),
      ),
    );

    await act(async () => result.current.importAttachments());

    expect(result.current.attachmentDraftItems).toEqual([
      expect.objectContaining({
        state: "failed",
        attemptId: "attempt_command_failure",
        code: "IMPORT_COMMAND_FAILED",
        retryable: true,
      }),
    ]);
    expect(result.current.importingAttachments).toBe(false);
    expect(result.current.error).toBe("附件导入失败，请检查文件后重试。");
  });

  /** 多次 picker 共享每个 Thread 的累计计数，溢出项必须立即从服务端草稿中丢弃。 */
  it("跨多次添加累计限制为每轮 10 个附件", async () => {
    prepareThread();
    let sequence = 0;
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ operationId, onEvent }) => {
        sequence += 1;
        onEvent({
          kind: "completed",
          operationId,
          attemptId: `attempt_${sequence}`,
          itemId: `item_${sequence}`,
          attachment: {
            attachmentId: `att_${sequence}`,
            fileName: `附件-${sequence}.txt`,
            sizeBytes: 1,
            mediaKind: "text",
          },
        });
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(
          createTurnPort(),
          { updatePreferences: vi.fn(async () => undefined) },
          {
            attachmentPort,
          },
        ),
      ),
    );

    for (let index = 0; index < 11; index += 1)
      await act(async () => result.current.importAttachments());

    expect(result.current.attachments).toHaveLength(10);
    await waitFor(() =>
      expect(attachmentPort.discardAttachment).toHaveBeenCalledWith({ attachmentId: "att_11" }),
    );
    expect(result.current.error).toBe("每轮最多添加 10 个附件，总大小不能超过 250 MiB。");
  });

  /** 总量限制按服务端签发的真实字节数累计，不能因分批导入绕过 250 MiB。 */
  it("跨多次添加累计限制为 250 MiB", async () => {
    prepareThread();
    const sizes = [80, 80, 80, 20].map((mib) => mib * 1024 * 1024);
    let sequence = 0;
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ operationId, onEvent }) => {
        const index = sequence++;
        onEvent({
          kind: "completed",
          operationId,
          attemptId: `attempt_bytes_${index}`,
          itemId: `item_bytes_${index}`,
          attachment: {
            attachmentId: `att_bytes_${index}`,
            fileName: `大文件-${index}.bin`,
            sizeBytes: sizes[index]!,
            mediaKind: "binary",
          },
        });
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(
          createTurnPort(),
          { updatePreferences: vi.fn(async () => undefined) },
          {
            attachmentPort,
          },
        ),
      ),
    );

    for (let index = 0; index < sizes.length; index += 1)
      await act(async () => result.current.importAttachments());

    expect(result.current.attachments).toHaveLength(3);
    await waitFor(() =>
      expect(attachmentPort.discardAttachment).toHaveBeenCalledWith({
        attachmentId: "att_bytes_3",
      }),
    );
  });

  /** retry 用 Rust attempt 恢复同一视觉项，完成后才生成唯一可提交 attachmentId。 */
  it("保留失败快照并从 retry 收敛为 ready", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    let pickerEmit!: Parameters<ConversationAttachmentPort["pickerImport"]>[0]["onEvent"];
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ onEvent }) => {
        pickerEmit = onEvent;
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async ({ operationId, attemptId, onEvent }) => {
        onEvent({
          kind: "started",
          operationId,
          attemptId,
          itemId: "item_retry",
          fileName: "说明.txt",
          sizeBytes: 12,
          mediaKind: "text",
        });
        onEvent({
          kind: "completed",
          operationId,
          attemptId,
          itemId: "item_retry",
          attachment: {
            attachmentId: "att_retry",
            fileName: "说明.txt",
            sizeBytes: 12,
            mediaKind: "text",
            mediaType: "text/plain",
          },
        });
      }),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const onAttachmentRemoved = vi.fn();
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(
          turnPort,
          { updatePreferences: vi.fn(async () => undefined) },
          {
            attachmentPort,
            onAttachmentRemoved,
          },
        ),
      ),
    );
    await act(async () => result.current.importAttachments());
    const operationId = vi.mocked(attachmentPort.pickerImport).mock.calls[0]![0].operationId;
    act(() => {
      pickerEmit({
        kind: "started",
        operationId,
        attemptId: "attempt_retry",
        itemId: "item_retry",
        fileName: "说明.txt",
        sizeBytes: 12,
        mediaKind: "text",
      });
      pickerEmit({
        kind: "failed",
        operationId,
        attemptId: "attempt_retry",
        itemId: "item_retry",
        fileName: "说明.txt",
        sizeBytes: 12,
        mediaKind: "text",
        code: "ATTACHMENT_RUNTIME_FAILED",
        message: "附件服务暂不可用",
        retryable: true,
      });
    });
    expect(result.current.attachmentDraftItems[0]).toMatchObject({
      state: "failed",
      attemptId: "attempt_retry",
      retryable: true,
    });

    await act(async () => result.current.retryAttachment("item_retry"));
    expect(attachmentPort.retryImport).toHaveBeenCalledWith(
      expect.objectContaining({ attemptId: "attempt_retry" }),
    );
    expect(result.current.attachments).toEqual([
      expect.objectContaining({ attachmentId: "att_retry", mediaKind: "text" }),
    ]);
    await act(async () => result.current.removeAttachment("item_retry"));
    expect(onAttachmentRemoved).toHaveBeenCalledWith("att_retry");
  });

  /** 未发送附件与失败快照按 Thread 保存在当前进程内，切换只改变投影而不跨会话泄漏。 */
  it("切换 Thread 后恢复各自附件草稿", async () => {
    prepareThread();
    prepareThread("thr_two", "ws_one");
    const turnPort = createTurnPort();
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ operationId, onEvent }) => {
        onEvent({
          kind: "completed",
          operationId,
          attemptId: "attempt_thread",
          itemId: "item_thread",
          attachment: {
            attachmentId: "att_thread",
            fileName: "线程说明.txt",
            sizeBytes: 12,
            mediaKind: "text",
          },
        });
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const initial = options(
      turnPort,
      { updatePreferences: vi.fn(async () => undefined) },
      { attachmentPort },
    );
    const { result, rerender } = renderHook(
      (props: ConversationInteractionOptions) => useConversationInteractionController(props),
      { initialProps: initial },
    );
    await act(async () => result.current.importAttachments());
    expect(result.current.attachments).toHaveLength(1);

    rerender({ ...initial, threadId: "thr_two" });
    expect(result.current.attachments).toHaveLength(0);
    rerender(initial);
    expect(result.current.attachments).toEqual([
      expect.objectContaining({ attachmentId: "att_thread", fileName: "线程说明.txt" }),
    ]);
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
    expect(useTimelineStore.getState().resyncRequired["thr_one"]).toBeUndefined();
  });

  /** 取消 ACK 已确认终态但没有 live event 时，只请求权威重读，不伪造 Timeline 终态。 */
  it("终态取消 ACK 无事件时请求原 Thread 重读并保留非乐观状态", async () => {
    prepareThread();
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_one",
        revision: 4,
        turns: [
          {
            turnId: "turn_cancel_terminal",
            status: "suspended",
            requestedAt: "2026-08-28T00:00:01Z",
            updatedAt: "2026-08-28T00:00:02Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    const turnPort = createTurnPort();
    vi.mocked(turnPort.cancelTurn).mockResolvedValue({
      accepted: true,
      turnId: "turn_cancel_terminal",
      status: "cancelled",
      threadRevision: 5,
    });
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );

    await act(async () => result.current.cancel());

    expect(turnPort.cancelTurn).toHaveBeenCalledWith({
      turnId: "turn_cancel_terminal",
      expectedThreadRevision: 4,
    });
    expect(useTimelineStore.getState().resyncRequired["thr_one"]).toBe("invalid_event");
    expect(useTimelineStore.getState().turns["turn_cancel_terminal"]?.status).toBe("suspended");
    expect(result.current.suspendedTurn).toBe(true);
  });

  /** 迟到 ACK 仍携带原始 Thread 身份，切换会话后不得让当前 Thread 进入重读态。 */
  it("切换 Thread 后的终态取消 ACK 只失效原 Thread", async () => {
    prepareThread();
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_one",
        revision: 4,
        turns: [
          {
            turnId: "turn_cancel_late",
            status: "suspended",
            requestedAt: "2026-08-28T00:00:01Z",
            updatedAt: "2026-08-28T00:00:02Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    const cancellation = deferred<ConversationCancelResult>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.cancelTurn).mockImplementation(() => cancellation.promise);
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const initial = options(turnPort, modelPort);
    const { result, rerender } = renderHook(
      (props: ConversationInteractionOptions) => useConversationInteractionController(props),
      { initialProps: initial },
    );

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.cancel();
    });
    rerender(options(turnPort, modelPort, { threadId: "thr_two", workspaceId: "ws_two" }));
    await act(async () => {
      cancellation.resolve({
        accepted: true,
        turnId: "turn_cancel_late",
        status: "cancelled",
        threadRevision: 5,
      });
      await pending;
    });

    expect(useTimelineStore.getState().resyncRequired["thr_one"]).toBe("invalid_event");
    expect(useTimelineStore.getState().resyncRequired["thr_two"]).toBeUndefined();
  });

  /** 取消 RPC 失败不能制造重读或终态，错误必须留在当前 Thread 供用户重试。 */
  it("取消失败时不标记成功且保留可见错误", async () => {
    prepareThread();
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_one",
        revision: 4,
        turns: [
          {
            turnId: "turn_cancel_failure",
            status: "suspended",
            requestedAt: "2026-08-28T00:00:01Z",
            updatedAt: "2026-08-28T00:00:02Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: null,
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    const turnPort = createTurnPort();
    vi.mocked(turnPort.cancelTurn).mockRejectedValueOnce(new Error("cancel failed"));
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );

    await act(async () => result.current.cancel());

    expect(useTimelineStore.getState().resyncRequired["thr_one"]).toBeUndefined();
    expect(useTimelineStore.getState().turns["turn_cancel_failure"]?.status).toBe("suspended");
    expect(result.current.error).toBe("取消失败，请稍后重试。");
  });

  /**
   * Suspended 禁止接纳新输入，但瞬时权威重读不能锁住本地草稿和原队列修复；
   * submit/enqueue 仍由独立状态门拒绝，避免把可编辑误解为可提交。
   */
  it("中断 Turn 阻止新提交和排队但允许修复原队列", async () => {
    prepareThread();
    useTimelineStore.getState().applySnapshot(
      {
        threadId: "thr_one",
        revision: 7,
        turns: [
          {
            turnId: "turn_suspended_later",
            status: "suspended",
            requestedAt: "2026-08-28T00:00:02Z",
            updatedAt: "2026-08-28T00:00:08Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
          {
            turnId: "turn_suspended",
            status: "suspended",
            requestedAt: "2026-08-28T00:00:01Z",
            updatedAt: "2026-08-28T00:00:07Z",
            completedAt: null,
            errorCode: null,
            changeSet: null,
          },
        ],
        items: [],
        inputQueue: {
          turnId: "turn_suspended",
          revision: 3,
          accepting: false,
          items: [
            {
              inputId: "input_attention",
              turnId: "turn_suspended",
              content: [{ type: "attachment", attachmentId: "att_missing" }],
              attachments: [
                {
                  attachmentId: "att_missing",
                  displayName: "失效附件.txt",
                  sizeBytes: 128,
                  mediaKind: "text",
                  mediaType: "text/plain",
                },
              ],
              kind: "follow_up",
              status: "needs_attention",
              issue: {
                errorCode: "ATTACHMENT_UNAVAILABLE",
                message: "附件已不可用",
                retryable: true,
              },
              inputRevision: 2,
              createdAt: "2026-09-03T00:00:00Z",
            },
          ],
        },
        contextUsage: null,
        taskActivities: [],
        goalActivities: [],
        nextCursor: null,
      },
      "ws_one",
    );
    const turnPort = createTurnPort();
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort, { blocked: true })),
    );

    expect(result.current.activeTurn).toBe(false);
    expect(result.current.suspendedTurn).toBe(true);
    expect(result.current.disabled).toBe(false);
    expect(result.current.queueAccepting).toBe(false);
    expect(result.current.queuedInputs).toEqual([
      expect.objectContaining({ inputId: "input_attention", attachments: [expect.any(Object)] }),
    ]);
    await act(async () => {
      await result.current.send({ text: "不得创建新 Turn" });
      await result.current.enqueue({ text: "不得排队" });
      await result.current.updateQueuedInput("input_attention", 2, [
        { type: "text", text: "移除失效附件后继续" },
      ]);
      await result.current.resume();
    });

    expect(turnPort.submitTurn).not.toHaveBeenCalled();
    expect(turnPort.enqueueTurnInput).not.toHaveBeenCalled();
    expect(turnPort.updateTurnInput).toHaveBeenCalledWith({
      turnId: "turn_suspended",
      inputId: "input_attention",
      expectedInputRevision: 2,
      content: [{ type: "text", text: "移除失效附件后继续" }],
    });
    expect(turnPort.resumeTurn).toHaveBeenCalledWith({
      turnId: "turn_suspended",
      expectedThreadRevision: 7,
    });
    expect(result.current.resuming).toBe(true);
  });

  it("连续入队立即清空草稿并在 ACK 后保留晚编辑内容", async () => {
    prepareThread();
    useTimelineStore.getState().applyTurnAccepted({
      threadId: "thr_one",
      turnId: "turn_queue",
      threadRevision: 1,
      submittedText: "执行任务",
      submittedAt: "2026-08-28T00:00:01Z",
    });
    const queued = deferred<ConversationInputQueueMutationResult>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.enqueueTurnInput).mockImplementation(() => queued.promise);
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    act(() => result.current.updateDraft("先检查"));
    await waitFor(() => expect(result.current.draft).toBe("先检查"));
    let pending!: Promise<void>;
    act(() => {
      pending = result.current.enqueue({ text: "先检查" });
    });
    expect(result.current.draft).toBe("");
    expect(result.current.queuedInputs).toEqual([
      expect.objectContaining({
        content: [{ type: "text", text: "先检查" }],
        pending: true,
        kind: "follow_up",
      }),
    ]);
    act(() => result.current.updateDraft("补充新内容"));
    await act(async () => {
      queued.resolve({
        accepted: true,
        inputId: "input_queue",
        inputQueue: {
          turnId: "turn_queue",
          revision: 1,
          accepting: true,
          items: [
            {
              inputId: "input_queue",
              turnId: "turn_queue",
              content: [{ type: "text", text: "先检查" }],
              attachments: [],
              kind: "follow_up",
              status: "pending",
              issue: null,
              inputRevision: 0,
              createdAt: "2026-09-01T00:00:00Z",
            },
          ],
        },
      });
      await pending;
    });

    expect(turnPort.enqueueTurnInput).toHaveBeenCalledWith({
      turnId: "turn_queue",
      content: [{ type: "text", text: "先检查" }],
    });
    expect(result.current.draft).toBe("补充新内容");
    expect(result.current.queuedInputs).toEqual([
      expect.objectContaining({ inputId: "input_queue", kind: "follow_up" }),
    ]);
  });

  /** 附件-only 入队携带可渲染摘要；失败恢复原附件时不得覆盖等待期间的新草稿。 */
  it("附件-only 入队失败后无损恢复附件并保留新草稿", async () => {
    prepareThread();
    useTimelineStore.getState().applyTurnAccepted({
      threadId: "thr_one",
      turnId: "turn_attachment_queue",
      threadRevision: 1,
      submittedText: "执行任务",
      submittedAt: "2026-09-03T00:00:00Z",
    });
    const queued = deferred<ConversationInputQueueMutationResult>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.enqueueTurnInput).mockImplementation(() => queued.promise);
    const attachmentPort: ConversationAttachmentPort = {
      pickerImport: vi.fn(async ({ operationId, onEvent }) => {
        onEvent({
          kind: "started",
          operationId,
          attemptId: "attempt_queue",
          itemId: "item_queue",
          fileName: "队列截图.png",
          sizeBytes: 4096,
          mediaKind: "image",
          mediaType: "image/png",
        });
        onEvent({
          kind: "completed",
          operationId,
          attemptId: "attempt_queue",
          itemId: "item_queue",
          attachment: {
            attachmentId: "att_queue",
            fileName: "队列截图.png",
            sizeBytes: 4096,
            mediaKind: "image",
            mediaType: "image/png",
          },
        });
      }),
      dropImport: vi.fn(async () => undefined),
      clipboardImport: vi.fn(async () => ({ outcome: "accepted" as const })),
      retryImport: vi.fn(async () => undefined),
      cancelImport: vi.fn(async () => undefined),
      discardAttempt: vi.fn(async () => undefined),
      discardAttachment: vi.fn(async () => undefined),
    };
    const onAttachmentsBound = vi.fn();
    const { result } = renderHook(() =>
      useConversationInteractionController(
        options(
          turnPort,
          { updatePreferences: vi.fn(async () => undefined) },
          { attachmentPort, onAttachmentsBound },
        ),
      ),
    );
    await act(async () => result.current.importAttachments());

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.enqueue({ text: "", attachmentIds: ["att_queue"] });
    });
    expect(result.current.attachments).toHaveLength(0);
    expect(result.current.queuedInputs).toEqual([
      expect.objectContaining({
        pending: true,
        content: [{ type: "attachment", attachmentId: "att_queue" }],
        attachments: [
          {
            attachmentId: "att_queue",
            displayName: "队列截图.png",
            sizeBytes: 4096,
            mediaKind: "image",
            mediaType: "image/png",
          },
        ],
      }),
    ]);
    act(() => result.current.updateDraft("等待期间的新草稿"));
    await act(async () => {
      queued.reject(new Error("private queue failure"));
      await pending;
    });

    expect(turnPort.enqueueTurnInput).toHaveBeenCalledWith({
      turnId: "turn_attachment_queue",
      content: [{ type: "attachment", attachmentId: "att_queue" }],
    });
    expect(result.current.draft).toBe("等待期间的新草稿");
    expect(result.current.attachments).toEqual([
      expect.objectContaining({ attachmentId: "att_queue", fileName: "队列截图.png" }),
    ]);
    expect(onAttachmentsBound).not.toHaveBeenCalled();
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
    const queued = deferred<ConversationInputQueueMutationResult>();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.enqueueTurnInput)
      .mockImplementationOnce(() => queued.promise)
      .mockResolvedValueOnce({
        accepted: true,
        inputId: "input_retry",
        inputQueue: {
          turnId: "turn_queue_retry",
          revision: 1,
          accepting: true,
          items: [],
        },
      });
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    act(() => result.current.updateDraft("完成后补充"));
    await waitFor(() => expect(result.current.draft).toBe("完成后补充"));

    let pending!: Promise<void>;
    act(() => {
      pending = result.current.enqueue({ text: "完成后补充" });
    });
    expect(result.current.draft).toBe("");
    act(() => result.current.updateDraft("等待期间的新内容"));
    await act(async () => {
      queued.reject(new Error("private queue failure"));
      await pending;
    });
    expect(result.current.draft).toBe("完成后补充\n\n等待期间的新内容");
    expect(result.current.error).toBe("排队失败，Turn 可能已结束，请重试。");
    await act(async () => result.current.enqueue({ text: result.current.draft }));
    expect(turnPort.enqueueTurnInput).toHaveBeenCalledTimes(2);
    expect(turnPort.enqueueTurnInput).toHaveBeenLastCalledWith({
      turnId: "turn_queue_retry",
      content: [{ type: "text", text: "完成后补充\n\n等待期间的新内容" }],
    });
    expect(result.current.draft).toBe("");
  });

  it("提交失败保留消息级错误且不回填 Composer，并释放 single-flight 允许继续发送", async () => {
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
    expect(result.current.draft).toBe("等待期间的新问题");
    expect(result.current.localSubmissions).toEqual([
      expect.objectContaining({
        text: "保留草稿",
        status: "failed",
        error: "发送失败，请检查运行时连接后重试。",
      }),
    ]);
    expect(result.current.error).toBeUndefined();
    await act(async () => result.current.send({ text: result.current.draft }));
    expect(turnPort.submitTurn).toHaveBeenCalledTimes(2);
    expect(turnPort.submitTurn).toHaveBeenLastCalledWith({
      threadId: "thr_one",
      content: [{ type: "text", text: "等待期间的新问题" }],
    });
    expect(result.current.draft).toBe("");
    expect(result.current.localSubmissions).toEqual([
      expect.objectContaining({ text: "保留草稿", status: "failed" }),
    ]);
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
      collaborationMode: "default",
    });
    expect(modelPort.updatePreferences).toHaveBeenNthCalledWith(2, {
      providerId: "provider_one",
      modelId: "model_one",
      reasoningLevel: "medium",
      accessMode: "full_access",
      collaborationMode: "default",
    });
  });

  it("结构化准入失败恢复草稿与引用并发布恢复 revision", async () => {
    prepareThread();
    const turnPort = createTurnPort();
    vi.mocked(turnPort.submitTurn).mockRejectedValue({ code: "SKILL_UNAVAILABLE" });
    const modelPort = { updatePreferences: vi.fn(async () => undefined) };
    const { result } = renderHook(() =>
      useConversationInteractionController(options(turnPort, modelPort)),
    );
    const reference = {
      type: "skill_reference" as const,
      skillId: "skill_removed",
      name: "Removed Skill",
      description: "fixture",
      scope: "project" as const,
    };

    await act(async () => {
      await result.current.send({ text: "继续处理", contextReferences: [reference] });
    });

    expect(result.current.draft).toBe("继续处理");
    expect(result.current.contextReferences).toEqual([reference]);
    expect(result.current.draftRecoveryRevision).toBe(1);
    expect(result.current.error).toBe("引用已变化，请调整后重试。");
    expect(result.current.localSubmissions).toEqual([]);
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
