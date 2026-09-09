// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import {
  FilesSaveCoordinator,
  type SaveTimerPort,
} from "@/features/workbench/files/application/FilesSaveCoordinator";
import { createLifecycleUseCases } from "@/features/workbench/files/application/internal/lifecycleUseCases";
import { createSearchUseCases } from "@/features/workbench/files/application/internal/searchUseCases";
import { createTreeUseCases } from "@/features/workbench/files/application/internal/treeUseCases";
import type { FileRevision, OpenDocument, WorkspaceFileNode } from "@/features/workbench/files";

interface StateCell<T> {
  readonly value: T;
  write: (value: T | ((current: T) => T)) => void;
}

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

/** 构造可控 Promise，精确验证 workspace deadline 与迟到保存 ACK 的先后关系。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

/** 模拟 React functional writer，但把断言值留在测试边界，协作者不会获得额外 store。 */
function stateCell<T>(initial: T): StateCell<T> {
  let current = initial;
  return {
    get value(): T {
      return current;
    },
    write: (next) => {
      current = typeof next === "function" ? (next as (value: T) => T)(current) : next;
    },
  };
}

/** 构造完整 CAS identity，确保协作者测试不会用路径代替 revision。 */
function revision(kind: FileRevision["kind"], sha256: string): FileRevision {
  return { kind, size: 8, modifiedUnixMillis: 123, sha256 };
}

/** 构造最小可编辑文档，用于 lifecycle fence 的 flush 后核对。 */
function dirtyDocument(path: string): OpenDocument {
  return {
    path,
    content: "draft",
    savedContent: "saved",
    revision: revision("file", "r1"),
    encoding: "utf8",
    newline: "lf",
    kind: "text",
    readOnly: false,
    status: "dirty",
    draftGeneration: 1,
  };
}

describe("Files controller collaborators", () => {
  it("Tree 协作者合并分页快照并把 generation/revision 留在注入 refs", async () => {
    const nodes = stateCell<WorkspaceFileNode[]>([]);
    const loading = stateCell(false);
    const error = stateCell<string | undefined>(undefined);
    const tree = vi
      .fn()
      .mockResolvedValueOnce({
        entries: [
          { name: "a.ts", relativePath: "a.ts", kind: "file", revision: revision("file", "a") },
        ],
        directoryRevision: revision("directory", "root"),
        nextCursor: "next",
        snapshotToken: "snapshot",
      })
      .mockResolvedValueOnce({
        entries: [
          { name: "b.ts", relativePath: "b.ts", kind: "file", revision: revision("file", "b") },
        ],
        directoryRevision: revision("directory", "root"),
        nextCursor: null,
        snapshotToken: "snapshot",
      });
    const revisions = { current: new Map<string, FileRevision>() };
    const useCases = createTreeUseCases({
      workspaceId: "ws",
      tree,
      treeRequestSequence: { current: 0 },
      treeRequests: { current: new Map<string, number>() },
      workspaceGeneration: { current: 0 },
      rootTreeReady: { current: false },
      revisions,
      setNodes: nodes.write,
      setTreeLoading: loading.write,
      setTreeError: error.write,
    });

    await expect(useCases.loadDirectory("")).resolves.toHaveLength(2);
    expect(nodes.value.map((node) => node.path)).toEqual(["a.ts", "b.ts"]);
    expect(revisions.current.get("")).toEqual(revision("directory", "root"));
    expect(tree).toHaveBeenLastCalledWith({
      workspaceId: "ws",
      relativePath: "",
      cursor: "next",
      snapshotToken: "snapshot",
    });
    expect(loading.value).toBe(false);
    expect(error.value).toBeUndefined();
  });

  it("Search 协作者通过 request epoch 丢弃旧 debounce 结果", async () => {
    const scheduled: Array<() => void> = [];
    const timer: SaveTimerPort = {
      set: (_delay, callback) => {
        scheduled.push(callback);
        return callback;
      },
      clear: vi.fn(),
    };
    const results = stateCell<Array<{ id: string; path: string; line: number; preview: string }>>(
      [],
    );
    const query = stateCell("");
    const loading = stateCell(false);
    const error = stateCell<string | undefined>(undefined);
    const summary = stateCell<
      { truncated: boolean; scannedEntries: number; skippedFiles: number } | undefined
    >(undefined);
    const useCases = createSearchUseCases({
      workspaceId: "ws",
      search: vi.fn(async ({ query: value }) => ({
        hits: [{ id: value, path: `${value}.ts`, line: 1, preview: value }],
        truncated: false,
        scannedEntries: 1,
        skippedFiles: 0,
      })),
      timer,
      searchTimer: { current: undefined },
      searchRequest: { current: 0 },
      workspaceGeneration: { current: 0 },
      setSearchQuery: query.write,
      setSearchResults: results.write,
      setSearchSummary: summary.write,
      setSearchLoading: loading.write,
      setSearchError: error.write,
    });

    useCases.runSearch("old");
    useCases.runSearch("new");
    scheduled[0]?.();
    scheduled[1]?.();
    await vi.waitFor(() => expect(results.value[0]?.id).toBe("new"));
    expect(query.value).toBe("new");
    expect(loading.value).toBe(false);
    expect(summary.value).toEqual({ truncated: false, scannedEntries: 1, skippedFiles: 0 });
  });

  it("Lifecycle 协作者共享一次 flush barrier，并在最后一个 lease 释放后开放编辑", async () => {
    const documents: { current: Record<string, OpenDocument> } = {
      current: { "main.ts": dirtyDocument("main.ts") },
    };
    const lifecycleFence = { current: false };
    const lifecycleLease = { current: undefined as Promise<void> | undefined };
    const lifecycleLeaseHolders = { current: 0 };
    const closing = stateCell(false);
    const timer: SaveTimerPort = { set: () => 1, clear: vi.fn() };
    const coordinator = new FilesSaveCoordinator({
      timer,
      saveOnce: async () => true,
      shouldContinue: () => false,
    });
    const flush = vi.fn(async (path: string) => {
      const current = documents.current[path];
      if (current !== undefined)
        documents.current[path] = { ...current, status: "clean", savedContent: current.content };
    });
    const useCases = createLifecycleUseCases({
      saveCoordinator: coordinator,
      mounted: { current: true },
      lifecycleFence,
      lifecycleLease,
      lifecycleLeaseHolders,
      documents,
      scheduledSave: { current: vi.fn() },
      flush: { current: flush },
      inFlight: { current: new Map<string, number>() },
      externalConflictChecks: { current: new Set<string>() },
      setLifecycleClosing: closing.write,
    });

    const first = await useCases.flushForWorkspaceChange();
    const second = await useCases.flushForWorkspaceChange();
    expect(flush).toHaveBeenCalledOnce();
    expect(lifecycleFence.current).toBe(true);
    expect(closing.value).toBe(true);
    first.release();
    expect(lifecycleFence.current).toBe(true);
    second.release();
    expect(lifecycleFence.current).toBe(false);
    expect(closing.value).toBe(false);
  });

  it("Lifecycle 保存永久 pending 时有界拒绝切换并恢复旧草稿编辑状态", async () => {
    vi.useFakeTimers();
    try {
      const documents: { current: Record<string, OpenDocument> } = {
        current: { "main.ts": dirtyDocument("main.ts") },
      };
      const lifecycleFence = { current: false };
      const lifecycleLease = { current: undefined as Promise<void> | undefined };
      const lifecycleLeaseHolders = { current: 0 };
      const closing = stateCell(false);
      const scheduledSave = vi.fn();
      const notice = vi.fn();
      const save = deferred<boolean>();
      const deadlineTimer: SaveTimerPort = {
        set: (delay, callback) => globalThis.setTimeout(callback, delay),
        clear: (handle) =>
          globalThis.clearTimeout(handle as ReturnType<typeof globalThis.setTimeout>),
      };
      const coordinator = new FilesSaveCoordinator({
        timer: deadlineTimer,
        saveOnce: () => save.promise,
        shouldContinue: () => false,
      });
      const useCases = createLifecycleUseCases({
        saveCoordinator: coordinator,
        mounted: { current: true },
        lifecycleFence,
        lifecycleLease,
        lifecycleLeaseHolders,
        documents,
        scheduledSave: { current: scheduledSave },
        flush: { current: (path) => coordinator.flush(path) },
        inFlight: { current: new Map<string, number>() },
        externalConflictChecks: { current: new Set<string>() },
        setLifecycleClosing: closing.write,
        onNotice: notice,
        workspaceChangeDeadline: { timeoutMillis: 1_000, timer: deadlineTimer },
      });

      const outcome = useCases.flushForWorkspaceChange().then(
        () => "granted" as const,
        (error: unknown) => error,
      );
      expect(lifecycleFence.current).toBe(true);
      expect(closing.value).toBe(true);

      await vi.advanceTimersByTimeAsync(1_000);
      const timeout = await outcome;
      expect(timeout).toBeInstanceOf(Error);
      expect((timeout as Error).message).toBe("文件保存等待超时，已拒绝切换");
      expect(lifecycleFence.current).toBe(false);
      expect(lifecycleLease.current).toBeUndefined();
      expect(lifecycleLeaseHolders.current).toBe(0);
      expect(closing.value).toBe(false);
      expect(documents.current["main.ts"]?.content).toBe("draft");
      expect(scheduledSave).toHaveBeenCalledWith("main.ts");
      expect(notice).toHaveBeenCalledWith(
        "文件保存等待超时，已取消项目切换；草稿仍保留，请检查后重试。",
      );

      save.resolve(true);
      await vi.runAllTimersAsync();
      expect(await outcome).toBe(timeout);
      expect(lifecycleLeaseHolders.current).toBe(0);
      expect(lifecycleFence.current).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});
