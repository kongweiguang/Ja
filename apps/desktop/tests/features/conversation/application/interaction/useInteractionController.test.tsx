// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useInteractionController } from "@/features/conversation/application/useInteractionController";
import type {
  InteractionEvent,
  InteractionPort,
  InteractionSnapshot,
} from "@/features/conversation/application/interactionPort";

const request = {
  requestId: "input_one",
  threadId: "thr_one",
  turnId: null,
  toolCallId: null,
  planRevisionId: null,
  runId: null,
  goalId: null,
  status: "pending" as const,
  revision: 3,
  questions: [
    {
      questionId: "question_one",
      prompt: "选择",
      type: "single" as const,
      required: false,
      allowFreeText: true,
      options: [{ optionId: "option_one", label: "一" }],
    },
  ],
  answers: [],
  createdAt: "2026-09-10T00:00:00Z",
  updatedAt: "2026-09-10T00:00:00Z",
};

const multipleRequest = {
  ...request,
  requestId: "input_multiple",
  questions: [
    {
      questionId: "question_multiple",
      prompt: "选择多个",
      type: "multiple" as const,
      required: false,
      allowFreeText: false,
      options: [
        { optionId: "option_one", label: "一" },
        { optionId: "option_two", label: "二" },
      ],
    },
  ],
};

const multipleOtherRequest = {
  ...multipleRequest,
  requestId: "input_multiple_other",
  questions: [
    {
      ...multipleRequest.questions[0]!,
      allowFreeText: true,
    },
  ],
};

const pagedRequest = {
  ...request,
  requestId: "input_paged",
  questions: [
    request.questions[0]!,
    {
      ...request.questions[0]!,
      questionId: "question_two",
      prompt: "第二题",
    },
  ],
};

/** 创建可控异步边界，让测试确定性复现旧 Thread 结果晚到的竞态。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** 用最小语义 port 隔离 controller 测试，避免把 Tauri 或 Java 状态带入单测。 */
function portWith(snapshot: InteractionSnapshot): InteractionPort {
  return {
    read: vi.fn(async () => snapshot),
    subscribe: vi.fn(() => () => undefined),
    saveDraft: vi.fn(async () => snapshot),
    submit: vi.fn(async () => snapshot),
    cancel: vi.fn(async () => snapshot),
  };
}

describe("useInteractionController", () => {
  afterEach(() => {
    cleanup();
    document.body.innerHTML = "";
    vi.useRealTimers();
  });

  it("只响应事件目标所在的同 Thread 卡片，不串到另一个 pending controller", async () => {
    const first = portWith({
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    });
    const secondRequest = { ...request, requestId: "input_two", threadId: "thr_two" };
    const second = portWith({
      threadId: "thr_two",
      eventSequence: 1,
      request: secondRequest,
      draft: { revision: 1, answers: [] },
    });
    const firstCard = document.createElement("div");
    firstCard.dataset["interactionCard"] = "true";
    firstCard.dataset["interactionThreadId"] = "thr_one";
    const secondCard = document.createElement("div");
    secondCard.dataset["interactionCard"] = "true";
    secondCard.dataset["interactionThreadId"] = "thr_two";
    document.body.append(firstCard, secondCard);
    const firstHook = renderHook(() =>
      useInteractionController({ threadId: "thr_one", port: first }),
    );
    const secondHook = renderHook(() =>
      useInteractionController({ threadId: "thr_two", port: second }),
    );
    await waitFor(() => {
      expect(firstHook.result.current.request?.requestId).toBe("input_one");
      expect(secondHook.result.current.request?.requestId).toBe("input_two");
    });

    act(() => {
      secondCard.dispatchEvent(new KeyboardEvent("keydown", { key: "1", bubbles: true }));
    });

    await waitFor(() =>
      expect(secondHook.result.current.answers["question_one"]?.optionIds).toEqual(["option_one"]),
    );
    expect(firstHook.result.current.answers).toEqual({});
  });

  it("隐藏 controller 不注册交互快捷键", async () => {
    const port = portWith({
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    });
    const card = document.createElement("div");
    card.dataset["interactionCard"] = "true";
    card.dataset["interactionThreadId"] = "thr_one";
    document.body.append(card);
    const { result } = renderHook(() =>
      useInteractionController({ threadId: "thr_one", visible: false, port }),
    );

    act(() => {
      card.dispatchEvent(new KeyboardEvent("keydown", { key: "1", bubbles: true }));
    });

    expect(result.current.answers).toEqual({});
    expect(port.submit).not.toHaveBeenCalled();
    expect(port.read).not.toHaveBeenCalled();
  });

  it("多选数字快捷键只切换当前选项并保留其它选项", async () => {
    const port = portWith({
      threadId: "thr_one",
      eventSequence: 1,
      request: multipleRequest,
      draft: { revision: 1, answers: [] },
    });
    const card = document.createElement("div");
    card.dataset["interactionCard"] = "true";
    card.dataset["interactionThreadId"] = "thr_one";
    document.body.append(card);
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await waitFor(() => expect(result.current.request?.requestId).toBe("input_multiple"));

    act(() => card.dispatchEvent(new KeyboardEvent("keydown", { key: "1", bubbles: true })));
    act(() => card.dispatchEvent(new KeyboardEvent("keydown", { key: "2", bubbles: true })));
    await waitFor(() =>
      expect(result.current.answers["question_multiple"]?.optionIds).toEqual([
        "option_one",
        "option_two",
      ]),
    );

    act(() => card.dispatchEvent(new KeyboardEvent("keydown", { key: "1", bubbles: true })));
    await waitFor(() =>
      expect(result.current.answers["question_multiple"]?.optionIds).toEqual(["option_two"]),
    );
  });

  it("多选数字快捷键切换选项时保留已填写的 Other 文本", async () => {
    const port = portWith({
      threadId: "thr_one",
      eventSequence: 1,
      request: multipleOtherRequest,
      draft: { revision: 1, answers: [] },
    });
    const card = document.createElement("div");
    card.dataset["interactionCard"] = "true";
    card.dataset["interactionThreadId"] = "thr_one";
    document.body.append(card);
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await waitFor(() => expect(result.current.request?.requestId).toBe("input_multiple_other"));

    act(() =>
      result.current.setAnswer("question_multiple", {
        questionId: "question_multiple",
        optionIds: [],
        freeText: "需要兼容旧版",
        skipped: false,
      }),
    );
    act(() => card.dispatchEvent(new KeyboardEvent("keydown", { key: "1", bubbles: true })));
    await waitFor(() =>
      expect(result.current.answers["question_multiple"]).toEqual({
        questionId: "question_multiple",
        optionIds: ["option_one"],
        freeText: "需要兼容旧版",
        skipped: false,
      }),
    );

    act(() => card.dispatchEvent(new KeyboardEvent("keydown", { key: "1", bubbles: true })));
    await waitFor(() =>
      expect(result.current.answers["question_multiple"]).toEqual({
        questionId: "question_multiple",
        optionIds: [],
        freeText: "需要兼容旧版",
        skipped: false,
      }),
    );
  });

  it("原生控件和带修饰键的按键不被窗口级分页快捷键拦截", async () => {
    const port = portWith({
      threadId: "thr_one",
      eventSequence: 1,
      request: pagedRequest,
      draft: { revision: 1, answers: [] },
    });
    const card = document.createElement("div");
    card.dataset["interactionCard"] = "true";
    card.dataset["interactionThreadId"] = "thr_one";
    const radio = document.createElement("input");
    radio.type = "radio";
    card.append(radio);
    document.body.append(card);
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await waitFor(() => expect(result.current.request?.requestId).toBe("input_paged"));

    act(() =>
      radio.dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true })),
    );
    act(() =>
      card.dispatchEvent(new KeyboardEvent("keydown", { key: "1", ctrlKey: true, bubbles: true })),
    );
    act(() => radio.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true })));

    expect(result.current.pageIndex).toBe(0);
    expect(result.current.answers).toEqual({});
    expect(port.submit).not.toHaveBeenCalled();
  });

  it("restores draft page and collapsed state from snapshot without marking pending as answered", async () => {
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 9,
      request,
      draft: { revision: 4, answers: [], page: 0, collapsed: true },
    };
    const port = portWith(snapshot);
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await waitFor(() => expect(result.current.collapsed).toBe(true));
    expect(result.current.answered).toBe(false);
    expect(result.current.request?.status).toBe("pending");
  });

  it("does not let an old Thread submit response overwrite the new Thread", async () => {
    const first = deferred<InteractionSnapshot>();
    const old = portWith({
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    });
    vi.mocked(old.submit).mockReturnValueOnce(first.promise);
    const secondRequest = { ...request, requestId: "input_two", threadId: "thr_two" };
    const next = portWith({
      threadId: "thr_two",
      eventSequence: 1,
      request: secondRequest,
      draft: { revision: 1, answers: [] },
    });
    const { result, rerender } = renderHook(
      ({ threadId, port }: { threadId: string; port: InteractionPort }) =>
        useInteractionController({ threadId, port }),
      { initialProps: { threadId: "thr_one", port: old } },
    );
    await waitFor(() => expect(result.current.request?.requestId).toBe("input_one"));
    act(() => {
      void result.current.submit();
    });
    rerender({ threadId: "thr_two", port: next });
    await waitFor(() => expect(result.current.request?.requestId).toBe("input_two"));
    first.resolve({
      threadId: "thr_one",
      eventSequence: 2,
      request: { ...request, status: "answered" },
      draft: { revision: 2, answers: [] },
    });
    await act(async () => {
      await first.promise;
    });
    expect(result.current.request?.requestId).toBe("input_two");
  });

  it("已回答快照优先显示服务端答案，不让本地草稿冒充确认摘要", async () => {
    const pendingSnapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const serverAnswer = {
      questionId: "question_one",
      optionIds: ["option_one"],
      freeText: null,
      skipped: false,
    };
    const answeredSnapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 2,
      request: { ...request, status: "answered", answers: [serverAnswer], revision: 4 },
      draft: {
        revision: 3,
        answers: [
          { questionId: "question_one", optionIds: [], freeText: "本地未确认", skipped: false },
        ],
      },
    };
    let listener: ((event: InteractionEvent) => void) | undefined;
    const port = portWith(pendingSnapshot);
    vi.mocked(port.subscribe).mockImplementation((_input, next) => {
      listener = next;
      return () => undefined;
    });
    vi.mocked(port.read)
      .mockResolvedValueOnce(pendingSnapshot)
      .mockResolvedValueOnce(answeredSnapshot);
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await waitFor(() => expect(result.current.request?.status).toBe("pending"));
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: [],
        freeText: "本地未确认",
        skipped: false,
      }),
    );
    act(() => listener?.({ kind: "snapshot_changed", threadId: "thr_one", eventSequence: 2 }));
    await waitFor(() => expect(result.current.request?.status).toBe("answered"));
    expect(result.current.answers["question_one"]).toEqual(serverAnswer);
  });

  it("does not advance the event watermark when reconciliation fails", async () => {
    let listener: ((event: InteractionEvent) => void) | undefined;
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const port = portWith(snapshot);
    vi.mocked(port.subscribe).mockImplementation((_input, next) => {
      listener = next;
      return () => undefined;
    });
    vi.mocked(port.read)
      .mockResolvedValueOnce(snapshot)
      .mockRejectedValueOnce(new Error("offline"));
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await waitFor(() => expect(result.current.request?.requestId).toBe("input_one"));
    act(() => listener?.({ kind: "snapshot_changed", threadId: "thr_one", eventSequence: 3 }));
    await waitFor(() => expect(result.current.error).toContain("暂时不可用"));
    vi.mocked(port.read).mockResolvedValueOnce({ ...snapshot, eventSequence: 3 });
    act(() => listener?.({ kind: "snapshot_changed", threadId: "thr_one", eventSequence: 3 }));
    await waitFor(() => expect(port.read).toHaveBeenCalledTimes(3));
  });

  it("keeps a delayed draft save alive while a background event is reconciled", async () => {
    vi.useFakeTimers();
    let listener: ((event: InteractionEvent) => void) | undefined;
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const port = portWith(snapshot);
    vi.mocked(port.subscribe).mockImplementation((_input, next) => {
      listener = next;
      return () => undefined;
    });
    vi.mocked(port.read).mockResolvedValue({ ...snapshot, eventSequence: 2 });
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await act(async () => {
      await Promise.resolve();
    });
    act(() => {
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: ["option_one"],
        freeText: null,
        skipped: false,
      });
      listener?.({ kind: "snapshot_changed", threadId: "thr_one", eventSequence: 2 });
    });
    await act(async () => {
      await Promise.resolve();
    });
    act(() => vi.advanceTimersByTime(350));
    expect(port.saveDraft).toHaveBeenCalledOnce();
  });

  it("does not clear dirty state when an older draft ACK follows a newer edit", async () => {
    vi.useFakeTimers();
    const firstSave = deferred<InteractionSnapshot>();
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const port = portWith(snapshot);
    vi.mocked(port.saveDraft).mockReturnValueOnce(firstSave.promise);
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await act(async () => {
      await Promise.resolve();
    });
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: ["option_one"],
        freeText: null,
        skipped: false,
      }),
    );
    act(() => vi.advanceTimersByTime(350));
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: [],
        freeText: "自定义",
        skipped: false,
      }),
    );
    firstSave.resolve({
      ...snapshot,
      eventSequence: 2,
      draft: {
        revision: 2,
        answers: [
          { questionId: "question_one", optionIds: ["option_one"], freeText: null, skipped: false },
        ],
      },
    });
    await act(async () => {
      await firstSave.promise;
    });
    act(() => vi.advanceTimersByTime(350));
    expect(port.saveDraft).toHaveBeenCalledTimes(2);
    expect(result.current.answers["question_one"]?.freeText).toBe("自定义");
  });

  it("草稿冲突先更新服务端 revision，后续保存不会重复使用旧 revision", async () => {
    vi.useFakeTimers();
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const conflictSnapshot: InteractionSnapshot = {
      ...snapshot,
      eventSequence: 2,
      draft: { revision: 2, answers: [] },
    };
    const port = portWith(snapshot);
    vi.mocked(port.saveDraft).mockRejectedValueOnce({
      code: "INTERACTION_REVISION_CONFLICT",
      snapshot: conflictSnapshot,
    });
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await act(async () => {
      await Promise.resolve();
    });
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: ["option_one"],
        freeText: null,
        skipped: false,
      }),
    );
    act(() => vi.advanceTimersByTime(350));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(result.current.conflict).toBe(true);

    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: [],
        freeText: "更新后重试",
        skipped: false,
      }),
    );
    act(() => vi.advanceTimersByTime(350));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(port.saveDraft).toHaveBeenCalledTimes(2);
    expect(port.saveDraft).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({ expectedDraftRevision: 2 }),
    );
  });

  it("草稿失败的重试只重新保存，不会把恢复动作升级为回答提交", async () => {
    vi.useFakeTimers();
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const port = portWith(snapshot);
    vi.mocked(port.saveDraft)
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(snapshot);
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await act(async () => {
      await Promise.resolve();
    });
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: ["option_one"],
        freeText: null,
        skipped: false,
      }),
    );
    act(() => vi.advanceTimersByTime(350));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(result.current.retryAction).toBe("draft");

    await act(async () => {
      await result.current.retryDraft();
    });
    act(() => vi.advanceTimersByTime(350));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(port.saveDraft).toHaveBeenCalledTimes(2);
    expect(port.submit).not.toHaveBeenCalled();
  });

  it("flushes the last draft when the Thread scope unmounts", async () => {
    vi.useFakeTimers();
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const port = portWith(snapshot);
    const { result, unmount } = renderHook(() =>
      useInteractionController({ threadId: "thr_one", port }),
    );
    await act(async () => {
      await Promise.resolve();
    });
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: ["option_one"],
        freeText: null,
        skipped: false,
      }),
    );
    unmount();
    expect(port.saveDraft).toHaveBeenCalledOnce();
  });

  it("卸载时遇到 CAS 冲突停止自动写入，并在切回同 Thread 后明确重试本地草稿", async () => {
    vi.useFakeTimers();
    const snapshot: InteractionSnapshot = {
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    };
    const serverDraft = {
      questionId: "question_one",
      optionIds: [],
      freeText: "服务端较新编辑",
      skipped: false,
    };
    const conflictSnapshot: InteractionSnapshot = {
      ...snapshot,
      eventSequence: 2,
      draft: { revision: 2, answers: [serverDraft] },
    };
    const acknowledgedSnapshot: InteractionSnapshot = {
      ...conflictSnapshot,
      eventSequence: 3,
      draft: { revision: 3, answers: [] },
    };
    const port = portWith(snapshot);
    vi.mocked(port.saveDraft)
      .mockRejectedValueOnce({
        code: "INTERACTION_REVISION_CONFLICT",
        snapshot: conflictSnapshot,
      })
      .mockResolvedValueOnce(acknowledgedSnapshot);
    const { result, unmount } = renderHook(() =>
      useInteractionController({ threadId: "thr_one", port }),
    );
    await act(async () => {
      await Promise.resolve();
    });
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: ["option_one"],
        freeText: null,
        skipped: false,
      }),
    );
    act(() => vi.advanceTimersByTime(350));
    expect(port.saveDraft).toHaveBeenCalledOnce();
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: [],
        freeText: "卸载前最后编辑",
        skipped: false,
      }),
    );
    unmount();

    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    // 冲突表示服务端已有较新草稿，卸载阶段不得自动用新 revision 覆盖它。
    expect(port.saveDraft).toHaveBeenCalledOnce();

    vi.mocked(port.read).mockResolvedValue(conflictSnapshot);
    const resumed = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(resumed.result.current.request?.requestId).toBe("input_one");
    expect(resumed.result.current.answers["question_one"]?.freeText).toBe("卸载前最后编辑");
    expect(resumed.result.current.retryAction).toBe("draft");

    await act(async () => {
      await resumed.result.current.retryDraft();
    });
    act(() => vi.advanceTimersByTime(350));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(port.saveDraft).toHaveBeenCalledTimes(2);
    expect(port.saveDraft).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({
        expectedDraftRevision: 2,
        answers: [
          { questionId: "question_one", optionIds: [], freeText: "卸载前最后编辑", skipped: false },
        ],
      }),
    );
    resumed.unmount();
  });

  it("uses a new idempotency key when retrying after the answer changed", async () => {
    const port = portWith({
      threadId: "thr_one",
      eventSequence: 1,
      request,
      draft: { revision: 1, answers: [] },
    });
    vi.mocked(port.submit)
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue({
        threadId: "thr_one",
        eventSequence: 2,
        request: { ...request, status: "answered" },
        draft: { revision: 2, answers: [] },
      });
    const { result } = renderHook(() => useInteractionController({ threadId: "thr_one", port }));
    await waitFor(() => expect(result.current.request?.requestId).toBe("input_one"));
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: ["option_one"],
        freeText: null,
        skipped: false,
      }),
    );
    await act(async () => {
      await result.current.submit();
    });
    act(() =>
      result.current.setAnswer("question_one", {
        questionId: "question_one",
        optionIds: [],
        freeText: "另一个答案",
        skipped: false,
      }),
    );
    await act(async () => {
      await result.current.retrySubmit();
    });
    const firstKey = vi.mocked(port.submit).mock.calls[0]?.[0].idempotencyKey;
    const secondKey = vi.mocked(port.submit).mock.calls[1]?.[0].idempotencyKey;
    expect(firstKey).toBeDefined();
    expect(secondKey).toBeDefined();
    expect(secondKey).not.toBe(firstKey);
  });
});
