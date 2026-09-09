// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
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
  layer: "unstaged",
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
  layer: "untracked",
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
function makeSnapshot(source: ReviewSource = { kind: "uncommitted" }): ReviewSnapshot {
  const mutable =
    source.kind === "uncommitted" || source.kind === "unstaged" || source.kind === "staged";
  return {
    workspaceId: "ws_demo",
    source,
    revision: `rev_${source.kind}`,
    files: [file, secondaryFile],
    stats: { files: 2, additions: 6, deletions: 1, binaryFiles: 0, truncated: false },
    capabilities: {
      stage: source.kind === "uncommitted" || source.kind === "unstaged",
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
    layer: selected.layer,
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
  /** StrictMode 会重放 effect，但同一逻辑读取只能占用一次 native 扫描与正文物化。 */
  it("在 StrictMode 首开时合并 catalog、snapshot 和默认文件 Diff", async () => {
    const pendingCatalog = deferred<ReviewCatalog>();
    const pendingSnapshot = deferred<ReviewSnapshot>();
    const pendingDiff = deferred<ReviewFileDiff>();
    const port = createPort({
      catalog: vi.fn(() => pendingCatalog.promise),
      snapshot: vi.fn(() => pendingSnapshot.promise),
      fileDiff: vi.fn(() => pendingDiff.promise),
    });
    const { result } = renderHook(
      () => useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
      { wrapper: StrictMode },
    );

    await waitFor(() => expect(port.catalog).toHaveBeenCalledTimes(1));
    expect(port.snapshot).toHaveBeenCalledTimes(1);

    await act(async () => {
      pendingCatalog.resolve(catalog);
      pendingSnapshot.resolve(makeSnapshot());
    });
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(1));

    await act(async () =>
      pendingDiff.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted")),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe(file.fileId));
    expect(port.catalog).toHaveBeenCalledTimes(1);
    expect(port.snapshot).toHaveBeenCalledTimes(1);
    expect(port.fileDiff).toHaveBeenCalledTimes(1);
  });

  /** 隐藏态不得触发任何 Git 物化，重新激活后才按当前来源完整加载。 */
  it("隐藏时保持 catalog、snapshot 和 file diff 读取全零", async () => {
    const port = createPort();
    const { result, rerender } = renderHook(
      ({ snapshotEnabled }: { snapshotEnabled: boolean }) =>
        useReviewController({ workspaceId: "ws_demo", snapshotEnabled, adapter: port }),
      { initialProps: { snapshotEnabled: false } },
    );

    expect(port.catalog).not.toHaveBeenCalled();
    expect(port.snapshot).not.toHaveBeenCalled();
    expect(port.fileDiff).not.toHaveBeenCalled();
    expect(result.current.viewModel.state.loading).toBe(false);
    expect(result.current.viewModel.state.catalogLoading).toBe(false);
    expect(result.current.viewModel.state.catalog).toBeUndefined();

    act(() => result.current.actions.setSource({ kind: "staged" }));
    expect(result.current.viewModel.state.loading).toBe(false);

    rerender({ snapshotEnabled: true });

    await waitFor(() => expect(result.current.viewModel.state.catalog?.currentBranch).toBe("main"));
    await waitFor(() =>
      expect(result.current.viewModel.state.snapshot?.source).toEqual({
        kind: "staged",
      }),
    );
    expect(port.snapshot).toHaveBeenCalledTimes(1);
    expect(port.catalog).toHaveBeenCalledTimes(1);
    expect(port.snapshot).toHaveBeenCalledWith({
      workspaceId: "ws_demo",
      source: { kind: "staged" },
    });

    rerender({ snapshotEnabled: false });

    expect(result.current.viewModel.state.loading).toBe(false);
    expect(result.current.viewModel.state.snapshot).toBeUndefined();
    expect(result.current.viewModel.state.diff).toBeUndefined();
    expect(result.current.viewModel.state.catalog).toBeUndefined();
  });

  it("禁用后拒绝仍在途的 snapshot 响应", async () => {
    const late = deferred<ReviewSnapshot>();
    const port = createPort({ snapshot: vi.fn(() => late.promise) });
    const { result, rerender } = renderHook(
      ({ snapshotEnabled }: { snapshotEnabled: boolean }) =>
        useReviewController({ workspaceId: "ws_demo", snapshotEnabled, adapter: port }),
      { initialProps: { snapshotEnabled: true } },
    );
    await waitFor(() => expect(port.snapshot).toHaveBeenCalledTimes(1));

    rerender({ snapshotEnabled: false });
    await act(async () => late.resolve(makeSnapshot()));

    expect(result.current.viewModel.state.loading).toBe(false);
    expect(result.current.viewModel.state.snapshot).toBeUndefined();
    expect(result.current.viewModel.state.diff).toBeUndefined();
  });

  it("读取 catalog、snapshot 和当前文件 Diff", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );

    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe("file_main"));
    expect(result.current.viewModel.state.snapshot?.stats.files).toBe(2);
    expect(result.current.viewModel.sourceOptions).toContainEqual({
      kind: "branch",
      refId: "main",
    });
    expect(port.catalog).toHaveBeenCalledWith({ workspaceId: "ws_demo", maxCommits: 50 });
  });

  /** 同来源与 layer 筛选都只是当前权威快照的本地投影，不得触发扫描或制造加载悬空。 */
  it("同来源和层筛选不重读 snapshot，并保留仍匹配的 Diff", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe(file.fileId));
    const snapshotCalls = vi.mocked(port.snapshot).mock.calls.length;
    const diffCalls = vi.mocked(port.fileDiff).mock.calls.length;

    act(() => result.current.actions.setSource({ kind: "uncommitted" }));
    act(() => result.current.actions.setLayerFilter("unstaged"));

    expect(port.snapshot).toHaveBeenCalledTimes(snapshotCalls);
    expect(port.fileDiff).toHaveBeenCalledTimes(diffCalls);
    expect(result.current.viewModel.state.loading).toBe(false);
    expect(result.current.viewModel.state.diffLoading).toBe(false);
    expect(result.current.viewModel.state.diff?.fileId).toBe(file.fileId);
  });

  /** 即使响应属于同 revision 快照，也必须严格匹配请求 fileId，禁止回退显示另一文件。 */
  it("拒绝合法快照中非请求文件的 Diff 响应", async () => {
    const port = createPort({
      fileDiff: vi.fn(async ({ source, revision }) => makeDiff(source, revision, secondaryFile)),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );

    await waitFor(() => expect(result.current.viewModel.state.diffError).toBeDefined());
    expect(result.current.viewModel.state.diff).toBeUndefined();
    expect(result.current.viewModel.state.diffLoading).toBe(false);
  });

  it("snapshot 超限时仍保留独立成功的 repository catalog", async () => {
    const port = createPort({
      snapshot: vi.fn(async () => {
        throw { code: "REVIEW_LIMIT" };
      }),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
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
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
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

  it("同一 Workspace 切换 Thread 后重置来源且不复用文件选择提示", async () => {
    const port = createPort();
    const { result, rerender } = renderHook(
      ({ selectionScopeId }: { selectionScopeId: string }) =>
        useReviewController({
          workspaceId: "ws_demo",
          selectionScopeId,
          snapshotEnabled: true,
          adapter: port,
        }),
      { initialProps: { selectionScopeId: "thr_one" } },
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe("file_main"));

    act(() => result.current.actions.selectFile("file_readme"));
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe("file_readme"));
    act(() => result.current.actions.setSource({ kind: "staged" }));
    await waitFor(() => expect(result.current.viewModel.state.source).toEqual({ kind: "staged" }));

    rerender({ selectionScopeId: "thr_two" });

    await waitFor(() =>
      expect(result.current.viewModel.state.source).toEqual({ kind: "uncommitted" }),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe("file_main"));
  });

  it("只接受当前 runtime generation 的失效事件", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({
        workspaceId: "ws_demo",
        generation: 3,
        snapshotEnabled: true,
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

  /** watcher burst 只能形成一个收口重读，且工作树变化不应重复枚举 branch/commit catalog。 */
  it("合并在途 snapshot 期间的失效事件且不重读 catalog", async () => {
    const firstRefresh = deferred<ReviewSnapshot>();
    const settledRefresh = deferred<ReviewSnapshot>();
    let snapshotCall = 0;
    const port = createPort({
      snapshot: vi.fn(({ source }) => {
        snapshotCall += 1;
        if (snapshotCall === 1) return Promise.resolve(makeSnapshot(source));
        if (snapshotCall === 2) return firstRefresh.promise;
        return settledRefresh.promise;
      }),
    });
    const { result } = renderHook(() =>
      useReviewController({
        workspaceId: "ws_demo",
        generation: 3,
        snapshotEnabled: true,
        adapter: port,
      }),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe(file.fileId));
    expect(port.catalog).toHaveBeenCalledTimes(1);

    act(() => port.emit({ workspaceId: "ws_demo", generation: 3, reason: "external" }));
    await waitFor(() => expect(port.snapshot).toHaveBeenCalledTimes(2));
    act(() => {
      port.emit({ workspaceId: "ws_demo", generation: 3, reason: "external" });
      port.emit({ workspaceId: "ws_demo", generation: 3, reason: "external" });
    });

    expect(port.snapshot).toHaveBeenCalledTimes(2);
    expect(port.catalog).toHaveBeenCalledTimes(1);
    await act(async () => firstRefresh.resolve(makeSnapshot()));
    await waitFor(() => expect(port.snapshot).toHaveBeenCalledTimes(3));
    expect(port.catalog).toHaveBeenCalledTimes(1);
    await act(async () => settledRefresh.resolve(makeSnapshot()));
  });

  /** 快速 A→B→A 复用仍在途的 A；完成后再回看必须重新 native 校验工作树 freshness。 */
  it("合并在途文件回看但不缓存已完成 Git Diff", async () => {
    const pendingMain = deferred<ReviewFileDiff>();
    const pendingSecondary = deferred<ReviewFileDiff>();
    const port = createPort({
      fileDiff: vi.fn(({ fileId }) =>
        fileId === secondaryFile.fileId ? pendingSecondary.promise : pendingMain.promise,
      ),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(1));

    act(() => result.current.actions.selectFile(secondaryFile.fileId));
    await act(async () => Promise.resolve());
    expect(port.fileDiff).toHaveBeenCalledTimes(1);
    act(() => result.current.actions.selectFile(file.fileId));
    await act(async () => Promise.resolve());
    expect(port.fileDiff).toHaveBeenCalledTimes(1);

    await act(async () =>
      pendingMain.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted", file)),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe(file.fileId));
    act(() => result.current.actions.selectFile(secondaryFile.fileId));
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(2));
    await act(async () =>
      pendingSecondary.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted", secondaryFile)),
    );
    await waitFor(() =>
      expect(result.current.viewModel.state.diff?.fileId).toBe(secondaryFile.fileId),
    );
    act(() => result.current.actions.selectFile(file.fileId));
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(3));

    const requestedIds = vi.mocked(port.fileDiff).mock.calls.map(([request]) => request.fileId);
    expect(requestedIds).toEqual([file.fileId, secondaryFile.fileId, file.fileId]);
  });

  /** Host binding 同时只能处理一个 Diff；快速选择只允许 A active 后启动最终 C，中间 B 不发送。 */
  it("快速选择多个文件时只发送 active 和 latest pending", async () => {
    const thirdFile: ReviewFile = {
      ...file,
      fileId: "file_third",
      path: "src/third.rs",
    };
    const pendingMain = deferred<ReviewFileDiff>();
    const pendingThird = deferred<ReviewFileDiff>();
    const port = createPort({
      snapshot: vi.fn(async ({ source }) => ({
        ...makeSnapshot(source),
        files: [file, secondaryFile, thirdFile],
        stats: { files: 3, additions: 8, deletions: 2, binaryFiles: 0, truncated: false },
      })),
      fileDiff: vi.fn(({ fileId }) =>
        fileId === thirdFile.fileId
          ? pendingThird.promise
          : fileId === file.fileId
            ? pendingMain.promise
            : Promise.reject(new Error("intermediate file must not reach native")),
      ),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(1));

    act(() => result.current.actions.selectFile(secondaryFile.fileId));
    await act(async () => Promise.resolve());
    act(() => result.current.actions.selectFile(thirdFile.fileId));
    await act(async () => Promise.resolve());
    expect(port.fileDiff).toHaveBeenCalledTimes(1);

    await act(async () =>
      pendingMain.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted", file)),
    );
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(2));
    expect(vi.mocked(port.fileDiff).mock.calls.map(([request]) => request.fileId)).toEqual([
      file.fileId,
      thirdFile.fileId,
    ]);
    await act(async () =>
      pendingThird.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted", thirdFile)),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe(thirdFile.fileId));
  });

  /** 隐藏 Review 会撤销尚未进入 native 的 latest pending，active 迟到也不触发后续读取。 */
  it("隐藏时丢弃未发送的 pending Diff", async () => {
    const pendingMain = deferred<ReviewFileDiff>();
    const port = createPort({ fileDiff: vi.fn(() => pendingMain.promise) });
    const { result, rerender } = renderHook(
      ({ snapshotEnabled }: { snapshotEnabled: boolean }) =>
        useReviewController({ workspaceId: "ws_demo", snapshotEnabled, adapter: port }),
      { initialProps: { snapshotEnabled: true } },
    );
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(1));
    act(() => result.current.actions.selectFile(secondaryFile.fileId));
    await act(async () => Promise.resolve());
    expect(port.fileDiff).toHaveBeenCalledTimes(1);

    rerender({ snapshotEnabled: false });
    await act(async () =>
      pendingMain.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted", file)),
    );
    expect(port.fileDiff).toHaveBeenCalledTimes(1);
    expect(result.current.viewModel.state.diff).toBeUndefined();
  });

  /** 组件直接卸载也必须撤销 latest pending，不能在 active 完成后继续占用 native mutex。 */
  it("卸载后不发送排队中的 Diff", async () => {
    const pendingMain = deferred<ReviewFileDiff>();
    const port = createPort({ fileDiff: vi.fn(() => pendingMain.promise) });
    const { result, unmount } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(1));
    act(() => result.current.actions.selectFile(secondaryFile.fileId));
    await act(async () => Promise.resolve());
    expect(port.fileDiff).toHaveBeenCalledTimes(1);

    unmount();
    await act(async () => Promise.resolve());
    await act(async () =>
      pendingMain.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted", file)),
    );

    expect(port.fileDiff).toHaveBeenCalledTimes(1);
  });

  /** UI scope 返回同名 A 时必须使用新的 local epoch，不能接回离开前仍 pending 的请求。 */
  it("scope A 到 B 再回 A 时不复用旧 A snapshot", async () => {
    const pending = [
      deferred<ReviewSnapshot>(),
      deferred<ReviewSnapshot>(),
      deferred<ReviewSnapshot>(),
    ];
    let requestIndex = 0;
    const port = createPort({
      snapshot: vi.fn(() => pending[requestIndex++]!.promise),
    });
    const { rerender } = renderHook(
      ({ selectionScopeId }: { selectionScopeId: string }) =>
        useReviewController({
          workspaceId: "ws_demo",
          selectionScopeId,
          snapshotEnabled: true,
          adapter: port,
        }),
      { initialProps: { selectionScopeId: "scope_a" } },
    );
    await waitFor(() => expect(port.snapshot).toHaveBeenCalledTimes(1));
    rerender({ selectionScopeId: "scope_b" });
    await waitFor(() => expect(port.snapshot).toHaveBeenCalledTimes(2));
    rerender({ selectionScopeId: "scope_a" });
    await waitFor(() => expect(port.snapshot).toHaveBeenCalledTimes(3));

    await act(async () => {
      pending[0]!.resolve(makeSnapshot());
      pending[1]!.resolve(makeSnapshot());
      pending[2]!.resolve(makeSnapshot());
    });
  });

  /** 当前文件的重复点击在 pending 和 completed 两态都必须幂等，不能重置为永久 loading。 */
  it("重复点击当前文件不重启在途或已完成 Diff", async () => {
    const pending = deferred<ReviewFileDiff>();
    const port = createPort({ fileDiff: vi.fn(() => pending.promise) });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(port.fileDiff).toHaveBeenCalledTimes(1));

    act(() => {
      result.current.actions.selectFile(file.fileId);
      result.current.actions.selectFile(file.fileId);
    });
    expect(port.fileDiff).toHaveBeenCalledTimes(1);
    expect(result.current.viewModel.state.diffLoading).toBe(true);

    await act(async () => pending.resolve(makeDiff({ kind: "uncommitted" }, "rev_uncommitted")));
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe(file.fileId));
    act(() => result.current.actions.selectFile(file.fileId));
    expect(port.fileDiff).toHaveBeenCalledTimes(1);
    expect(result.current.viewModel.state.diffLoading).toBe(false);
  });

  /** binary/truncated 元数据可直接解释不可读原因，不应再发没有正文价值的 native 请求。 */
  it("二进制文件只选择元数据且不读取 Diff", async () => {
    const binaryFile = { ...file, binary: true };
    const port = createPort({
      snapshot: vi.fn(async ({ source }) => ({
        ...makeSnapshot(source),
        files: [binaryFile],
        stats: { files: 1, additions: 0, deletions: 0, binaryFiles: 1, truncated: false },
      })),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );

    await waitFor(() => expect(result.current.viewModel.selectedFile?.binary).toBe(true));
    expect(result.current.viewModel.state.diffLoading).toBe(false);
    expect(port.fileDiff).not.toHaveBeenCalled();
  });

  /** 本地筛选切到 metadata-only 条目时不能遗留 loading，也不能补发无意义正文请求。 */
  it("筛选切到二进制文件后立即结束 Diff loading", async () => {
    const binaryFile = { ...secondaryFile, binary: true };
    const port = createPort({
      snapshot: vi.fn(async ({ source }) => ({
        ...makeSnapshot(source),
        files: [file, binaryFile],
        stats: { files: 2, additions: 6, deletions: 1, binaryFiles: 1, truncated: false },
      })),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.fileId).toBe(file.fileId));

    act(() => result.current.actions.setLayerFilter("untracked"));

    expect(result.current.viewModel.selectedFile?.fileId).toBe(binaryFile.fileId);
    expect(result.current.viewModel.state.diffLoading).toBe(false);
    expect(port.fileDiff).toHaveBeenCalledTimes(1);
  });

  /** metadata-first 快照不携带 hunk；写入授权必须来自当前 revision 已验证的单文件 Diff。 */
  it("使用当前文件 Diff 的权威 hunk 身份执行操作", async () => {
    const metadataFile = { ...file, hunks: [] };
    const port = createPort({
      snapshot: vi.fn(async ({ source }) => ({
        ...makeSnapshot(source),
        files: [metadataFile],
        stats: { files: 1, additions: 2, deletions: 1, binaryFiles: 0, truncated: false },
      })),
      fileDiff: vi.fn(async ({ source, revision }) => makeDiff(source, revision, file)),
    });
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(result.current.viewModel.state.diff?.hunks).toHaveLength(1));

    await act(async () =>
      result.current.actions.applyAction("stage", {
        kind: "hunk",
        fileId: file.fileId,
        hunkId: file.hunks[0]!.hunkId,
      }),
    );

    expect(port.apply).toHaveBeenCalledWith(
      expect.objectContaining({
        revision: "rev_uncommitted",
        target: { kind: "hunk", fileId: file.fileId, hunkId: file.hunks[0]!.hunkId },
      }),
    );
  });

  it("缺少 generation admission 时 fail closed", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(result.current.viewModel.state.snapshot).toBeDefined());
    const calls = vi.mocked(port.snapshot).mock.calls.length;

    await act(async () => port.emit({ workspaceId: "ws_demo", generation: 1, reason: "mutation" }));

    expect(vi.mocked(port.snapshot).mock.calls).toHaveLength(calls);
  });

  it("按 revision 和 target 调用 native mutation，并接纳返回的新快照", async () => {
    const port = createPort();
    const { result } = renderHook(() =>
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
    );
    await waitFor(() => expect(result.current.viewModel.state.snapshot).toBeDefined());

    await act(async () =>
      result.current.actions.applyAction("stage", { kind: "file", fileId: "file_main" }),
    );

    expect(port.apply).toHaveBeenCalledWith(
      expect.objectContaining({
        workspaceId: "ws_demo",
        source: { kind: "uncommitted" },
        revision: "rev_uncommitted",
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
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
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
      useReviewController({ workspaceId: "ws_demo", snapshotEnabled: true, adapter: port }),
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
