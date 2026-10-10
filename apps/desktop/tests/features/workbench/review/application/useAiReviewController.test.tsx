// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  AiReviewCapture,
  AiReviewGateway,
  AiReviewHistoryPage,
  AiReviewReport,
  AiReviewResult,
} from "@/features/workbench/review/domain/aiReview";
import {
  isAiReviewRunStale,
  useAiReviewController,
} from "@/features/workbench/review/application/useAiReviewController";

afterEach(() => cleanup());

/** Capture is the only payload the feature retains; its opaque attachment is the ordinary Turn input. */
function capture(): AiReviewCapture {
  return {
    workspaceId: "ws_demo",
    snapshotId: "rev_0123456789abcdef01234567",
    snapshotHash: "a".repeat(64),
    revision: "git_revision_1",
    attachmentId: "att_review_snapshot",
    coverage: {
      complete: true,
      files: 1,
      bytes: 128,
      maxFiles: 2_000,
      maxBytes: 64 * 1024 * 1024,
      partialReasons: [],
    },
  };
}

/** Report evidence stays bound to the captured snapshot and to files the Review read actually covered. */
function report(): AiReviewReport {
  return {
    reportId: "report_0123456789abcdef01234567",
    snapshotId: "rev_0123456789abcdef01234567",
    snapshotHash: "a".repeat(64),
    revision: "b".repeat(64),
    summary: "发现一处边界问题。",
    coverage: {
      complete: true,
      files: 1,
      bytes: 128,
      maxFiles: 2_000,
      maxBytes: 64 * 1024 * 1024,
      partialReasons: [],
      filesReviewed: [{ path: "src/main.rs", layer: "unstaged" }],
      reviewComplete: true,
    },
    findings: [
      {
        findingId: "finding_0123456789abcdef01234567",
        severity: "P1",
        title: "缺少边界校验",
        body: "输入值可能越界。",
        path: "src/main.rs",
        layer: "unstaged",
        startLine: 4,
        endLine: 5,
        side: "current",
        evidence: "索引直接来自用户输入。",
      },
    ],
  };
}

/** A narrow fake gateway exercises the feature contract without importing protocol or Tauri types. */
function createGateway(): AiReviewGateway {
  return {
    captureSnapshot: vi.fn(async () => capture()),
    startAiReview: vi.fn(async () => ({
      threadId: "thread_demo",
      turnId: "turn_review",
      threadRevision: 5,
      queued: false,
    })),
    readAiReview: vi.fn(async ({ threadId, turnId }) => ({
      threadId,
      turnId,
      status: "completed" as const,
      report: report(),
    })),
    readHistoryPage: vi.fn(async ({ threadId }) => ({
      threadId,
      revision: 1,
      turns: [],
      items: [],
      nextCursor: null,
    })),
  };
}

/** 构造可分页的已提交审查历史；工具 artifact 身份只用于发现记录，正文仍经 review/read 获取。 */
function historyPage(
  turnId: string,
  itemPrefix: string,
  revision: number,
  nextCursor: string | null = null,
): AiReviewHistoryPage {
  const requestedAt = turnId === "turn_latest" ? "2026-10-07T02:00:00Z" : "2026-10-06T02:00:00Z";
  return {
    threadId: "thread_demo",
    revision,
    turns: [{ turnId, status: "completed", requestedAt }],
    items: [
      {
        itemId: `${itemPrefix}_list`,
        turnId,
        createdAt: requestedAt,
        toolName: "review_snapshot_list",
        status: "success",
      },
      {
        itemId: `${itemPrefix}_read`,
        turnId,
        createdAt: requestedAt,
        toolName: "review_snapshot_read",
        status: "success",
      },
      {
        itemId: `${itemPrefix}_report`,
        turnId,
        createdAt: requestedAt,
        toolName: "review_report_submit",
        status: "success",
        artifactId: `${itemPrefix}_artifact`,
      },
    ],
    nextCursor,
  };
}

/** 为并发恢复测试显式控制 native history 请求的完成时序。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

describe("useAiReviewController", () => {
  /** 首屏只读取 Thread 最新一页，再按 Turn 身份惰性取回报告正文。 */
  it("recovers a report from the tail page and reads its body by frozen turn", async () => {
    const gateway = createGateway();
    vi.mocked(gateway.readHistoryPage).mockResolvedValue(historyPage("turn_latest", "latest", 2));
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 8,
        runtimeGeneration: 3,
        serverInstanceId: "server_review_3",
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_current",
        gateway,
      }),
    );

    await waitFor(() => expect(result.current.selectedRun?.report).toEqual(report()));
    expect(gateway.readHistoryPage).toHaveBeenCalledWith({ threadId: "thread_demo", tail: true });
    expect(gateway.readAiReview).toHaveBeenCalledWith({
      threadId: "thread_demo",
      turnId: "turn_latest",
    });
    expect(result.current.selectedRun).toMatchObject({
      runId: "history:turn_latest",
      historical: true,
      status: "completed",
      reportExpected: true,
    });
    expect(result.current.selectedRun?.sourceRevision).toBeUndefined();
    expect(
      isAiReviewRunStale(
        result.current.selectedRun!,
        { kind: "uncommitted" },
        "git_revision_current",
      ),
    ).toBe(false);
  });

  /** 只有已成功提交并带 artifact 的历史报告才允许调用正文读取接口。 */
  it.each([
    { label: "error submission", status: "error" as const, artifactId: "artifact_error" },
    { label: "pending submission", status: "pending" as const, artifactId: "artifact_pending" },
    { label: "missing artifact", status: "success" as const, artifactId: undefined },
  ])("does not read a history report after $label", async ({ status, artifactId }) => {
    const gateway = createGateway();
    vi.mocked(gateway.readHistoryPage).mockResolvedValue({
      threadId: "thread_demo",
      revision: 2,
      turns: [
        {
          turnId: "turn_unsubmitted",
          status: "completed",
          requestedAt: "2026-10-06T02:00:00Z",
        },
      ],
      items: [
        {
          itemId: "item_unsubmitted_report",
          turnId: "turn_unsubmitted",
          createdAt: "2026-10-06T02:01:00Z",
          toolName: "review_report_submit",
          status,
          ...(artifactId === undefined ? {} : { artifactId }),
        },
      ],
      nextCursor: null,
    });
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 8,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_current",
        gateway,
      }),
    );

    await waitFor(() =>
      expect(result.current.selectedRun).toMatchObject({
        turnId: "turn_unsubmitted",
        historical: true,
        reportExpected: false,
      }),
    );
    expect(gateway.readAiReview).not.toHaveBeenCalled();
  });

  /** 选择失败提交的终态历史要清除旧读取指示，且只能回读仍持有 artifact 的另一 Turn。 */
  it("clears report loading when selecting history without an artifact", async () => {
    const gateway = createGateway();
    const pendingReportRead = deferred<AiReviewResult>();
    vi.mocked(gateway.readAiReview).mockImplementation(({ threadId, turnId }) => {
      if (turnId === "turn_report") return pendingReportRead.promise;
      return Promise.resolve({ threadId, turnId, status: "completed", report: report() });
    });
    vi.mocked(gateway.readHistoryPage).mockResolvedValue({
      threadId: "thread_demo",
      revision: 4,
      turns: [
        {
          turnId: "turn_report",
          status: "completed",
          requestedAt: "2026-10-07T02:00:00Z",
        },
        {
          turnId: "turn_unsubmitted",
          status: "completed",
          requestedAt: "2026-10-06T02:00:00Z",
        },
      ],
      items: [
        {
          itemId: "item_report",
          turnId: "turn_report",
          createdAt: "2026-10-07T02:01:00Z",
          toolName: "review_report_submit",
          status: "success",
          artifactId: "artifact_report",
        },
        {
          itemId: "item_unsubmitted",
          turnId: "turn_unsubmitted",
          createdAt: "2026-10-06T02:01:00Z",
          toolName: "review_report_submit",
          status: "error",
        },
      ],
      nextCursor: null,
    });
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 8,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_current",
        gateway,
      }),
    );

    await waitFor(() => expect(result.current.state.loadingReportId).toBe("history:turn_report"));
    await act(async () => result.current.actions.select("history:turn_unsubmitted"));

    expect(result.current.selectedRun?.reportExpected).toBe(false);
    expect(result.current.state.loadingReportId).toBeUndefined();
    expect(gateway.readAiReview).toHaveBeenCalledTimes(1);
    expect(gateway.readAiReview).toHaveBeenCalledWith({
      threadId: "thread_demo",
      turnId: "turn_report",
    });
  });

  /** 同一 Turn 的 artifact 可落在旧页；确认后只升级 reportExpected 并读取冻结报告。 */
  it("reads a report after an older page confirms its artifact", async () => {
    const gateway = createGateway();
    vi.mocked(gateway.readHistoryPage).mockImplementation(async ({ threadId, cursor }) =>
      cursor === undefined
        ? {
            threadId,
            revision: 3,
            turns: [
              {
                turnId: "turn_split_report",
                status: "completed",
                requestedAt: "2026-10-06T02:00:00Z",
              },
            ],
            items: [
              {
                itemId: "tail_snapshot_list",
                turnId: "turn_split_report",
                createdAt: "2026-10-06T02:00:01Z",
                toolName: "review_snapshot_list",
                status: "success",
              },
            ],
            nextCursor: "cursor_older",
          }
        : {
            threadId,
            revision: 3,
            turns: [
              {
                turnId: "turn_split_report",
                status: "completed",
                requestedAt: "2026-10-06T02:00:00Z",
              },
            ],
            items: [
              {
                itemId: "older_report_submit",
                turnId: "turn_split_report",
                createdAt: "2026-10-06T02:01:00Z",
                toolName: "review_report_submit",
                status: "success",
                artifactId: "artifact_split_report",
              },
            ],
            nextCursor: null,
          },
    );
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 9,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_current",
        gateway,
      }),
    );

    await waitFor(() => expect(result.current.selectedRun?.reportExpected).toBe(false));
    expect(gateway.readAiReview).not.toHaveBeenCalled();

    await act(async () => result.current.actions.loadOlderHistory());

    await waitFor(() => expect(result.current.selectedRun?.report).toEqual(report()));
    expect(result.current.selectedRun?.reportExpected).toBe(true);
    expect(gateway.readAiReview).toHaveBeenCalledTimes(1);
    expect(gateway.readAiReview).toHaveBeenCalledWith({
      threadId: "thread_demo",
      turnId: "turn_split_report",
    });
  });

  /** 更早页只有用户请求才读取，并且下一游标必须来自同 revision 的 tail 响应。 */
  it("loads an older page only on request", async () => {
    const gateway = createGateway();
    vi.mocked(gateway.readHistoryPage).mockImplementation(async ({ threadId, cursor }) =>
      cursor === undefined
        ? {
            ...historyPage("turn_latest", "latest", 3, "cursor_older"),
            threadId,
          }
        : { ...historyPage("turn_older", "older", 3), threadId },
    );
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 9,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_current",
        gateway,
      }),
    );

    await waitFor(() => expect(result.current.state.historyHasMore).toBe(true));
    expect(gateway.readHistoryPage).toHaveBeenCalledTimes(1);
    await act(async () => result.current.actions.loadOlderHistory());

    expect(gateway.readHistoryPage).toHaveBeenLastCalledWith({
      threadId: "thread_demo",
      cursor: "cursor_older",
    });
    expect(result.current.state.runs.map((run) => run.turnId)).toEqual([
      "turn_latest",
      "turn_older",
    ]);
  });

  /** 新 tail 可能与先前加载的旧页重叠；去重窗口必须绑定一次 revision 分页序列。 */
  it("starts a fresh item identity window when the Thread revision reloads the tail", async () => {
    const gateway = createGateway();
    let tailReads = 0;
    const overlappingTail = {
      ...historyPage("turn_latest", "latest_refresh", 15, "cursor_older"),
      turns: [
        {
          turnId: "turn_latest",
          status: "completed" as const,
          requestedAt: "2026-10-07T02:00:00Z",
        },
        {
          turnId: "turn_older",
          status: "completed" as const,
          requestedAt: "2026-10-06T02:00:00Z",
        },
      ],
      items: [
        ...historyPage("turn_latest", "latest_refresh", 15).items,
        ...historyPage("turn_older", "older", 15).items,
      ],
    };
    vi.mocked(gateway.readHistoryPage).mockImplementation(async ({ threadId, cursor }) => {
      if (cursor !== undefined)
        return { ...historyPage("turn_older", "older", 14, "cursor_olderest"), threadId };
      tailReads += 1;
      return tailReads === 1
        ? { ...historyPage("turn_latest", "latest", 14, "cursor_older"), threadId }
        : { ...overlappingTail, threadId };
    });
    const options = {
      active: true,
      workspaceId: "ws_demo",
      threadId: "thread_demo",
      threadRevision: 14,
      source: { kind: "uncommitted" } as const,
      snapshotRevision: "git_revision_current",
      gateway,
    };
    const { result, rerender } = renderHook(
      ({ threadRevision }: { threadRevision: number }) =>
        useAiReviewController({ ...options, threadRevision }),
      { initialProps: { threadRevision: options.threadRevision } },
    );

    await waitFor(() => expect(result.current.state.historyHasMore).toBe(true));
    await act(async () => result.current.actions.loadOlderHistory());
    expect(result.current.state.historyRevision).toBe(14);

    rerender({ threadRevision: 15 });

    await waitFor(() => expect(result.current.state.historyRevision).toBe(15));
    expect(result.current.state.historyError).toBeUndefined();
    expect(result.current.state.runs.map((run) => run.turnId)).toEqual(
      expect.arrayContaining(["turn_latest", "turn_older"]),
    );
  });

  /** 分页失败不清空已恢复报告；原游标可以重试并继续同一历史 revision。 */
  it("retries a failed older page while preserving the loaded report", async () => {
    const gateway = createGateway();
    let olderAttempts = 0;
    vi.mocked(gateway.readHistoryPage).mockImplementation(async ({ threadId, cursor }) => {
      if (cursor === undefined)
        return {
          ...historyPage("turn_latest", "latest", 4, "cursor_older"),
          threadId,
        };
      olderAttempts += 1;
      if (olderAttempts === 1) throw { code: "RUNTIME_UNAVAILABLE" };
      return { ...historyPage("turn_older", "older", 4), threadId };
    });
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 10,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_current",
        gateway,
      }),
    );

    await waitFor(() => expect(result.current.selectedRun?.report).toEqual(report()));
    const loadedReport = result.current.selectedRun?.report;
    await act(async () => result.current.actions.loadOlderHistory());
    expect(result.current.state.historyError).toBeDefined();
    expect(result.current.selectedRun?.report).toEqual(loadedReport);

    await act(async () => result.current.actions.retryHistory());
    expect(result.current.state.historyError).toBeUndefined();
    expect(result.current.selectedRun?.report).toEqual(loadedReport);
    expect(result.current.state.runs.map((run) => run.turnId)).toEqual([
      "turn_latest",
      "turn_older",
    ]);
  });

  /** 隐藏 Review 时不读取历史，恢复可见后再启动最近页查询。 */
  it("does not read history until the Review panel is visible", async () => {
    const gateway = createGateway();
    const { rerender } = renderHook(
      ({ active }: { active: boolean }) =>
        useAiReviewController({
          active,
          workspaceId: "ws_demo",
          threadId: "thread_demo",
          threadRevision: 11,
          source: { kind: "uncommitted" },
          snapshotRevision: "git_revision_current",
          gateway,
        }),
      { initialProps: { active: false } },
    );

    expect(gateway.readHistoryPage).not.toHaveBeenCalled();
    rerender({ active: true });
    await waitFor(() => expect(gateway.readHistoryPage).toHaveBeenCalledOnce());
  });

  /** Runtime 重启后只接受新 generation/server instance 的响应，旧回执不覆盖新 Thread 投影。 */
  it("ignores a late history response from the previous runtime instance", async () => {
    const gateway = createGateway();
    const stale = deferred<AiReviewHistoryPage>();
    vi.mocked(gateway.readHistoryPage)
      .mockImplementationOnce(() => stale.promise)
      .mockResolvedValueOnce(historyPage("turn_latest", "latest", 6));
    const options = {
      active: true,
      workspaceId: "ws_demo",
      threadId: "thread_demo",
      threadRevision: 12,
      runtimeGeneration: 3,
      serverInstanceId: "server_review_old",
      source: { kind: "uncommitted" } as const,
      snapshotRevision: "git_revision_current",
      gateway,
    };
    const { result, rerender } = renderHook(
      ({ runtimeGeneration, serverInstanceId }: typeof options) =>
        useAiReviewController({ ...options, runtimeGeneration, serverInstanceId }),
      { initialProps: options },
    );

    await waitFor(() => expect(gateway.readHistoryPage).toHaveBeenCalledOnce());
    rerender({ ...options, runtimeGeneration: 4, serverInstanceId: "server_review_new" });
    await waitFor(() => expect(result.current.selectedRun?.turnId).toBe("turn_latest"));
    await act(async () => stale.resolve(historyPage("turn_stale", "stale", 5)));

    expect(result.current.state.historyRevision).toBe(6);
    expect(result.current.state.runs.map((run) => run.turnId)).toEqual(["turn_latest"]);
  });

  /** 请求发出后若面板隐藏，失败回执不能出现在后续可见会话中。 */
  it("discards a failed response after the Review panel becomes hidden", async () => {
    const gateway = createGateway();
    const pending = deferred<AiReviewHistoryPage>();
    vi.mocked(gateway.readHistoryPage).mockImplementationOnce(() => pending.promise);
    const { result, rerender } = renderHook(
      ({ active }: { active: boolean }) =>
        useAiReviewController({
          active,
          workspaceId: "ws_demo",
          threadId: "thread_demo",
          threadRevision: 13,
          source: { kind: "uncommitted" },
          snapshotRevision: "git_revision_current",
          gateway,
        }),
      { initialProps: { active: true } },
    );

    await waitFor(() => expect(gateway.readHistoryPage).toHaveBeenCalledOnce());
    rerender({ active: false });
    await act(async () => pending.reject({ code: "RUNTIME_UNAVAILABLE" }));

    expect(result.current.state.historyError).toBeUndefined();
    expect(result.current.state.historyLoading).toBe(false);
  });

  /** owner 改变后旧 Thread 的迟到成功回执不能把历史记录合入新 Thread。 */
  it("discards a late history page from the previously selected Thread", async () => {
    const gateway = createGateway();
    const stale = deferred<AiReviewHistoryPage>();
    vi.mocked(gateway.readHistoryPage)
      .mockImplementationOnce(() => stale.promise)
      .mockImplementationOnce(async ({ threadId }) => ({
        ...historyPage("turn_thread_b", "thread_b", 23),
        threadId,
      }));
    const options = {
      active: true,
      workspaceId: "ws_demo",
      threadId: "thread_a",
      threadRevision: 22,
      source: { kind: "uncommitted" } as const,
      snapshotRevision: "git_revision_current",
      gateway,
    };
    const { result, rerender } = renderHook(
      ({ threadId }: { threadId: string }) => useAiReviewController({ ...options, threadId }),
      { initialProps: { threadId: options.threadId } },
    );

    await waitFor(() => expect(gateway.readHistoryPage).toHaveBeenCalledOnce());
    rerender({ threadId: "thread_b" });
    await waitFor(() => expect(result.current.state.runs[0]?.turnId).toBe("turn_thread_b"));
    await act(async () =>
      stale.resolve({ ...historyPage("turn_thread_a", "thread_a", 22), threadId: "thread_a" }),
    );

    expect(result.current.state.runs.map((run) => run.turnId)).toEqual(["turn_thread_b"]);
    expect(result.current.state.historyError).toBeUndefined();
  });

  /** 历史刷新只能合并已受理 Turn，必须保留未受理请求的选择、错误和重试身份。 */
  it("preserves a pending local review across history refresh and retries a rejected admission", async () => {
    const gateway = createGateway();
    const pendingCapture = deferred<AiReviewCapture>();
    let historyRevision = 24;
    vi.mocked(gateway.readHistoryPage).mockImplementation(async () =>
      historyPage("turn_latest", "latest", historyRevision++),
    );
    vi.mocked(gateway.captureSnapshot).mockImplementationOnce(() => pendingCapture.promise);
    vi.mocked(gateway.startAiReview).mockRejectedValueOnce({ code: "CONFLICT" });
    const { result, rerender } = renderHook(
      ({ threadRevision }: { threadRevision: number }) =>
        useAiReviewController({
          active: true,
          workspaceId: "ws_demo",
          threadId: "thread_demo",
          threadRevision,
          source: { kind: "uncommitted" },
          snapshotRevision: "git_revision_1",
          gateway,
        }),
      { initialProps: { threadRevision: 24 } },
    );
    await waitFor(() => expect(result.current.selectedRun?.report).toEqual(report()));
    let startPromise!: Promise<void>;
    await act(async () => {
      startPromise = result.current.actions.start();
    });
    const localRun = result.current.selectedRun!;
    expect(localRun.status).toBe("capturing");

    rerender({ threadRevision: 25 });
    await waitFor(() => expect(result.current.state.historyRevision).toBe(25));
    expect(result.current.selectedRun?.runId).toBe(localRun.runId);
    expect(result.current.selectedRun?.status).toBe("capturing");
    await act(async () => {
      pendingCapture.resolve(capture());
      await startPromise;
    });
    expect(result.current.selectedRun).toMatchObject({
      runId: localRun.runId,
      status: "failed",
      capture: capture(),
      error: { code: "CONFLICT" },
    });

    rerender({ threadRevision: 26 });
    await waitFor(() => expect(result.current.state.historyRevision).toBe(26));
    expect(result.current.selectedRun?.runId).toBe(localRun.runId);
    expect(result.current.selectedRun?.error?.code).toBe("CONFLICT");
    await act(async () => result.current.actions.retry(localRun.runId));

    expect(gateway.captureSnapshot).toHaveBeenCalledOnce();
    expect(gateway.startAiReview).toHaveBeenNthCalledWith(1, {
      threadId: "thread_demo",
      expectedThreadRevision: 25,
      operationKey: localRun.operationKey,
      snapshotAttachmentId: "att_review_snapshot",
    });
    expect(gateway.startAiReview).toHaveBeenNthCalledWith(2, {
      threadId: "thread_demo",
      expectedThreadRevision: 26,
      operationKey: localRun.operationKey,
      snapshotAttachmentId: "att_review_snapshot",
    });
    expect(result.current.selectedRun?.turnId).toBe("turn_review");
    expect(result.current.selectedRun?.error).toBeUndefined();
    expect(result.current.state.runs.find((run) => run.turnId === "turn_latest")?.report).toEqual(
      report(),
    );
  });

  /** 启动失败不得重置同一 Thread 已读历史的 revision、cursor 或分页错误。 */
  it("keeps older history pageable after a new review capture fails", async () => {
    const gateway = createGateway();
    let oldestAttempts = 0;
    vi.mocked(gateway.readHistoryPage).mockImplementation(async ({ threadId, cursor }) => {
      if (cursor === undefined)
        return { ...historyPage("turn_latest", "latest", 24, "cursor_older"), threadId };
      if (cursor === "cursor_older")
        return { ...historyPage("turn_older", "older", 24, "cursor_oldest"), threadId };
      oldestAttempts += 1;
      if (oldestAttempts === 1) throw { code: "RUNTIME_UNAVAILABLE" };
      return { ...historyPage("turn_oldest", "oldest", 24), threadId };
    });
    vi.mocked(gateway.captureSnapshot).mockRejectedValueOnce({ code: "REVIEW_CAPTURE_FAILED" });
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 24,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_current",
        gateway,
      }),
    );

    await waitFor(() => expect(result.current.selectedRun?.report).toEqual(report()));
    await act(async () => result.current.actions.loadOlderHistory());
    await act(async () => result.current.actions.loadOlderHistory());
    const paginationError = result.current.state.historyError;
    expect(paginationError).toBeDefined();
    expect(result.current.state.historyNextCursor).toBe("cursor_oldest");

    await act(async () => result.current.actions.start());

    expect(result.current.selectedRun?.status).toBe("failed");
    expect(result.current.state.historyError).toEqual(paginationError);
    expect(result.current.state.historyRevision).toBe(24);
    expect(result.current.state.historyNextCursor).toBe("cursor_oldest");
    expect(result.current.state.runs.find((run) => run.turnId === "turn_latest")?.report).toEqual(
      report(),
    );

    await act(async () => result.current.actions.loadOlderHistory());

    expect(gateway.readHistoryPage).toHaveBeenLastCalledWith({
      threadId: "thread_demo",
      cursor: "cursor_oldest",
    });
    expect(result.current.state.historyError).toBeUndefined();
    expect(result.current.state.runs.map((run) => run.turnId)).toEqual(
      expect.arrayContaining(["turn_latest", "turn_older", "turn_oldest"]),
    );
  });

  /** Start, ACK and historical report reads remain tied to the selected Thread and frozen attachment. */
  it("captures a snapshot, starts an ordinary review turn, and reads its authoritative report", async () => {
    const gateway = createGateway();
    const onTurnAccepted = vi.fn();
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 4,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_1",
        gateway,
        onTurnAccepted,
      }),
    );

    await waitFor(() => expect(result.current.state.historyLoading).toBe(false));
    await act(async () => result.current.actions.start());

    expect(gateway.captureSnapshot).toHaveBeenCalledWith({
      workspaceId: "ws_demo",
      source: { kind: "uncommitted" },
      expectedSnapshotRevision: "git_revision_1",
      operationId: expect.any(String),
    });
    expect(gateway.startAiReview).toHaveBeenCalledWith({
      threadId: "thread_demo",
      expectedThreadRevision: 4,
      operationKey: expect.any(String),
      snapshotAttachmentId: "att_review_snapshot",
    });
    expect(onTurnAccepted).toHaveBeenCalledWith({
      threadId: "thread_demo",
      turnId: "turn_review",
      threadRevision: 5,
      queued: false,
    });

    const runId = result.current.selectedRun?.runId;
    expect(runId).toBeDefined();
    await act(async () => result.current.actions.refresh(runId!));

    expect(gateway.readAiReview).toHaveBeenCalledWith({
      threadId: "thread_demo",
      turnId: "turn_review",
    });
    expect(result.current.selectedRun).toMatchObject({
      status: "completed",
      report: { snapshotId: "rev_0123456789abcdef01234567" },
    });
    expect(result.current.selectedRun?.sourceRevision).toBe("git_revision_1");
    expect(
      isAiReviewRunStale(result.current.selectedRun!, { kind: "uncommitted" }, "git_revision_1"),
    ).toBe(false);
  });

  /** 排队 Turn 可能先读到 running；Thread history 观察到终态后仍须按原 Turn 取回报告正文。 */
  it("reads a queued review report after history observes its terminal Turn", async () => {
    const gateway = createGateway();
    let historyReads = 0;
    vi.mocked(gateway.startAiReview).mockResolvedValue({
      threadId: "thread_demo",
      turnId: "turn_review",
      threadRevision: 5,
      queued: true,
    });
    vi.mocked(gateway.readAiReview)
      .mockResolvedValueOnce({
        threadId: "thread_demo",
        turnId: "turn_review",
        status: "running",
        report: null,
      })
      .mockResolvedValueOnce({
        threadId: "thread_demo",
        turnId: "turn_review",
        status: "completed",
        report: report(),
      });
    vi.mocked(gateway.readHistoryPage).mockImplementation(async ({ threadId }) => {
      historyReads += 1;
      return historyReads === 1
        ? { threadId, revision: 1, turns: [], items: [], nextCursor: null }
        : { ...historyPage("turn_review", "review_done", 2), threadId };
    });
    const options = {
      active: true,
      workspaceId: "ws_demo",
      threadId: "thread_demo",
      threadRevision: 4,
      source: { kind: "uncommitted" } as const,
      snapshotRevision: "git_revision_1",
      gateway,
    };
    const { result, rerender } = renderHook(
      ({ threadRevision }: { threadRevision: number }) =>
        useAiReviewController({ ...options, threadRevision }),
      { initialProps: { threadRevision: options.threadRevision } },
    );

    await waitFor(() => expect(result.current.state.historyLoading).toBe(false));
    await act(async () => result.current.actions.start());
    const runId = result.current.selectedRun!.runId;
    await act(async () => result.current.actions.refresh(runId));
    expect(result.current.selectedRun).toMatchObject({ status: "running", report: undefined });

    rerender({ threadRevision: 5 });

    await waitFor(() => expect(result.current.selectedRun?.report).toEqual(report()));
    expect(result.current.selectedRun).toMatchObject({
      turnId: "turn_review",
      status: "completed",
      reportExpected: true,
    });
    expect(gateway.readAiReview).toHaveBeenCalledTimes(2);
    expect(gateway.readAiReview).toHaveBeenLastCalledWith({
      threadId: "thread_demo",
      turnId: "turn_review",
    });
  });

  /** Review cancellation only delegates the ordinary Turn ID; ACK does not forge its terminal status. */
  it("delegates cancellation without replacing the server-owned Turn status", async () => {
    const gateway = createGateway();
    const cancelTurn = vi.fn(async () => undefined);
    const { result } = renderHook(() =>
      useAiReviewController({
        active: true,
        workspaceId: "ws_demo",
        threadId: "thread_demo",
        threadRevision: 4,
        source: { kind: "uncommitted" },
        snapshotRevision: "git_revision_1",
        gateway,
        cancelTurn,
      }),
    );

    await waitFor(() => expect(result.current.state.historyLoading).toBe(false));
    await act(async () => result.current.actions.start());
    const runId = result.current.selectedRun!.runId;
    await act(async () => result.current.actions.cancel(runId));

    expect(cancelTurn).toHaveBeenCalledWith("turn_review");
    expect(result.current.selectedRun).toMatchObject({ status: "running", cancelPending: false });
  });
});
