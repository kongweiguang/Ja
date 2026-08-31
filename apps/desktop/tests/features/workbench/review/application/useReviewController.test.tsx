// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  ReviewCatalog,
  ReviewFile,
  ReviewFileDiff,
  ReviewInvalidatedEvent,
  ReviewSnapshot,
  ReviewSource,
} from "@/features/workbench/review/domain/types";
import type { ReviewPort } from "@/features/workbench/review/application/ports";
import { useReviewController } from "@/features/workbench/review/application/useReviewController";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const file: ReviewFile = {
  fileId: "file_main",
  path: "src/main.rs",
  oldPath: null,
  status: "modified",
  additions: 2,
  deletions: 1,
  binary: false,
  truncated: false,
  hunks: [
    {
      hunkId: "hunk_main",
      header: "@@ -1 +1,2 @@",
      oldStart: 1,
      oldLines: 1,
      newStart: 1,
      newLines: 2,
    },
  ],
};

const secondaryFile: ReviewFile = {
  fileId: "file_readme",
  path: "README.md",
  oldPath: null,
  status: "added",
  additions: 4,
  deletions: 0,
  binary: false,
  truncated: false,
  hunks: [],
};

/** 构造权威快照，并让 capability 随来源保持真实只读边界。 */
function makeSnapshot(source: ReviewSource = { kind: "unstaged" }): ReviewSnapshot {
  const mutable = source.kind === "unstaged" || source.kind === "staged";
  return {
    workspaceId: "ws_demo",
    source,
    revision: `rev_${source.kind}`,
    files: [file, secondaryFile],
    stats: { files: 2, additions: 6, deletions: 1, binaryFiles: 0, truncated: false },
    capabilities: {
      stage: source.kind === "unstaged",
      unstage: source.kind === "staged",
      revert: mutable,
    },
  };
}

const catalog: ReviewCatalog = {
  workspaceId: "ws_demo",
  repositoryName: "ja",
  currentBranch: "main",
  headCommitId: "abc123",
  baseRefs: [{ refId: "main", label: "main", kind: "base" }],
  commits: [
    { commitId: "abc123", subject: "Initial", author: "Ja", authoredAt: "2026-08-26T00:00:00Z" },
  ],
};

/** 构造与 snapshot revision 严格绑定的有界文本 Diff。 */
function makeDiff(source: ReviewSource, revision: string, selected = file): ReviewFileDiff {
  return {
    workspaceId: "ws_demo",
    source,
    revision,
    fileId: selected.fileId,
    path: selected.path,
    oldPath: selected.oldPath,
    status: selected.status,
    binary: false,
    truncated: false,
    original: "old",
    modified: "new",
    unified: "@@ -1 +1 @@\n-old\n+new",
    hunks: selected.hunks,
    lines: [
      { kind: "deletion", oldLine: 1, newLine: null, text: "old" },
      { kind: "addition", oldLine: null, newLine: 1, text: "new" },
    ],
  };
}

/** 创建可控 fake port，使 application 测试能精确驱动失效事件和失败路径。 */
function createPort(
  overrides: Partial<ReviewPort> = {},
): ReviewPort & { emit: (event: ReviewInvalidatedEvent) => void } {
  let listener: ((event: ReviewInvalidatedEvent) => void) | undefined;
  return {
    catalog: vi.fn(async () => catalog),
    snapshot: vi.fn(async ({ source }) => makeSnapshot(source)),
    fileDiff: vi.fn(async ({ source, revision, fileId }) =>
      makeDiff(source, revision, fileId === secondaryFile.fileId ? secondaryFile : file),
    ),
    apply: vi.fn(async ({ source, operationId }) => ({
      workspaceId: "ws_demo",
      operationId,
      applied: true as const,
      snapshot: makeSnapshot(source),
    })),
    cancel: vi.fn(async ({ operationId }) => ({
      workspaceId: "ws_demo",
      operationId,
      cancelled: true,
    })),
    subscribeInvalidated: vi.fn(async (next) => {
      listener = next;
      return () => {
        listener = undefined;
      };
    }),
    emit: (event) => listener?.(event),
    ...overrides,
  };
}

/** 创建 deferred Promise，用事件栅栏验证旧来源响应晚到时不会覆盖当前状态。 */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolvePromise!: (value: T) => void;
  const promise = new Promise<T>((resolve) => {
    resolvePromise = resolve;
  });
  return { promise, resolve: resolvePromise };
}

describe("useReviewController", () => {
  it("读取 catalog、snapshot 和当前文件 Diff", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", adapter: port }),
    );

    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe("file_main"));
    expect(result.current.viewModel.state.snapshot?.stats.files).toBe(2);
    expect(result.current.viewModel.sourceOptions).toContainEqual({
      kind: "branch",
      refId: "main",
    });
    expect(port.catalog).toHaveBeenCalledWith({ workspaceId: "ws_demo", maxCommits: 50 });
  });

  it("snapshot 超限时仍保留独立成功的 repository catalog", async () => {
    const port = createPort({
      snapshot: vi.fn(async () => {
        throw { code: "REVIEW_LIMIT" };
      }),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", adapter: port }),
    );

    await waitFor(() => expect(result.current.viewModel.state.error?.code).toBe("REVIEW_LIMIT"));

    expect(result.current.viewModel.state.catalog?.currentBranch).toBe("main");
    expect(result.current.viewModel.state.snapshot).toBeUndefined();
    expect(result.current.viewModel.sourceOptions).toContainEqual({
      kind: "branch",
      refId: "main",
    });
  });

  it("来源切换后拒绝迟到的旧 snapshot", async () => {
    const late = deferred<ReviewSnapshot>();
    const port = createPort({
      snapshot: vi.fn(({ source }) =>
        source.kind === "unstaged" ? late.promise : Promise.resolve(makeSnapshot(source)),
      ),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", adapter: port }),
    );
    await waitFor(() =>
      expect(result.current.viewModel.sourceOptions).toContainEqual({
        kind: "branch",
        refId: "main",
      }),
    );

    act(() => result.current.actions.setSource({ kind: "branch", refId: "main" }));
    await waitFor(() =>
      expect(result.current.viewModel.state.snapshot?.source).toEqual({
        kind: "branch",
        refId: "main",
      }),
    );
    await act(async () => late.resolve(makeSnapshot({ kind: "unstaged" })));

    expect(result.current.viewModel.state.source).toEqual({ kind: "branch", refId: "main" });
    expect(result.current.viewModel.state.snapshot?.source).toEqual({
      kind: "branch",
      refId: "main",
    });
  });

  it("只接受当前 runtime generation 的失效事件", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({
        workspaceId: "ws_demo",
        generation: 3,
        adapter: port,
      }),
    );
    await waitFor(() => expect(result.current.viewModel.state.snapshot).toBeDefined());
    const calls = vi.mocked(port.snapshot).mock.calls.length;

    await act(async () => port.emit({ workspaceId: "ws_demo", generation: 2, reason: "mutation" }));
    await act(async () =>
      port.emit({ workspaceId: "ws_other", generation: 3, reason: "mutation" }),
    );
    expect(vi.mocked(port.snapshot).mock.calls).toHaveLength(calls);

    await act(async () => port.emit({ workspaceId: "ws_demo", generation: 3, reason: "mutation" }));
    await waitFor(() => expect(vi.mocked(port.snapshot).mock.calls.length).toBeGreaterThan(calls));
  });

  it("缺少 generation admission 时 fail closed", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", adapter: port }),
    );
    await waitFor(() => expect(result.current.viewModel.state.snapshot).toBeDefined());
    const calls = vi.mocked(port.snapshot).mock.calls.length;

    await act(async () => port.emit({ workspaceId: "ws_demo", generation: 1, reason: "mutation" }));

    expect(vi.mocked(port.snapshot).mock.calls).toHaveLength(calls);
  });

  it("按 revision 和 target 调用 native mutation，并接纳返回的新快照", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", adapter: port }),
    );
    await waitFor(() => expect(result.current.viewModel.state.snapshot).toBeDefined());

    await act(async () =>
      result.current.actions.applyAction("stage", { kind: "file", fileId: "file_main" }),
    );

    expect(port.apply).toHaveBeenCalledWith(
      expect.objectContaining({
        workspaceId: "ws_demo",
        source: { kind: "unstaged" },
        revision: "rev_unstaged",
        action: "stage",
        target: { kind: "file", fileId: "file_main" },
      }),
    );
    expect(result.current.viewModel.state.notice).toBe("已暂存。");
    expect(result.current.viewModel.state.pendingOperationIds.size).toBe(0);
  });

  it("把未知 adapter 异常收敛为安全错误", async () => {
    const port = createPort({
      catalog: vi.fn(async () => {
        throw new Error("C:\\secret\\repository");
      }),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", adapter: port }),
    );

    await waitFor(() =>
      expect(result.current.viewModel.state.error).toEqual({
        code: "RUNTIME_UNAVAILABLE",
        message: "运行时暂不可用，请稍后重试。",
        retryable: true,
      }),
    );
  });

  it("把非 Git 工作区保留为不可重试的真实能力边界", async () => {
    const port = createPort({
      catalog: vi.fn(async () => {
        throw { code: "NOT_GIT_REPOSITORY" };
      }),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", adapter: port }),
    );

    await waitFor(() =>
      expect(result.current.viewModel.state.error).toEqual({
        code: "NOT_GIT_REPOSITORY",
        message: "当前目录不是 Git 工作区，审查不可用。",
        retryable: false,
      }),
    );
  });
});
