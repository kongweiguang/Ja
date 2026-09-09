// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type {
  WorkspaceChangedEvent,
  WorkspaceFileRevision,
  WorkspaceMutationHostAdapter,
} from "@/api/tauri/workspace";
import { createFilesWorkspaceOperations } from "@/app/workbenchFilesAdapter";

/** 构造精确 native revision，使每条 adapter 断言覆盖全部 CAS 字段。 */
function revision(
  kind: WorkspaceFileRevision["kind"],
  sha256: string | null,
  size = 17,
): WorkspaceFileRevision {
  return { kind, size, modifiedUnixMillis: 1_725_000_000_123, sha256 };
}

interface Deferred<T> {
  promise: Promise<T>;
  resolve: (value: T) => void;
}

/** 为乱序 lifecycle 测试创建确定性 native-command barrier，避免依赖计时。 */
function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => {
    resolve = next;
  });
  return { promise, resolve };
}

/** 提供完整 typed host，每个测试只覆盖自己观察的 boundary，避免 mock 契约缺字段。 */
function createHost(
  overrides: Partial<WorkspaceMutationHostAdapter> = {},
): WorkspaceMutationHostAdapter {
  const fileRevision = revision("file", "file-before");
  return {
    tree: async () => ({
      entries: [],
      directoryRevision: revision("directory", "root"),
      nextCursor: null,
      snapshotToken: "snapshot",
      totalEntries: 0,
      depth: 0,
    }),
    readFile: async () => ({
      metadata: {
        kind: "file",
        size: fileRevision.size,
        modifiedUnixMillis: fileRevision.modifiedUnixMillis,
        revision: fileRevision,
      },
      kind: "text",
      encoding: "utf8",
      lineEnding: "lf",
      text: "content",
      bytesRead: 7,
      truncated: false,
    }),
    search: async () => ({ hits: [], truncated: false, scannedEntries: 0, skippedFiles: 0 }),
    openTargets: async () => ({ targets: [] }),
    open: async ({ target, relativePath }) => ({
      opened: true,
      target,
      relativePath: relativePath ?? "",
      entryKind: "directory",
    }),
    createEntry: async ({ relativePath, kind }) => ({ relativePath, kind, revision: fileRevision }),
    saveFile: async ({ relativePath }) => ({ relativePath, revision: fileRevision }),
    moveEntry: async ({ fromRelativePath, toRelativePath }) => ({
      fromRelativePath,
      toRelativePath,
      revision: fileRevision,
    }),
    trashPrepare: async () => ({
      operationToken: "trash",
      fileCount: 1,
      totalBytes: 17,
      expiresAtUnixMillis: 1_725_000_030_123,
    }),
    trashCommit: async () => ({ committed: true, revision: null }),
    watchStart: async ({ generation }) => ({ started: true, generation }),
    watchRescan: async ({ generation }) => ({ generation, requiresRescan: false, emittedPaths: 0 }),
    watchStop: async () => ({ stopped: true }),
    importDrop: async () => ({ importedRelativePaths: [] }),
    subscribeChanged: async () => () => undefined,
    subscribeNativeDrop: async () => () => undefined,
    ...overrides,
  };
}

describe("workbench files adapter", () => {
  it("adapts native focus observation without exposing a window object", async () => {
    const dispose = vi.fn();
    let emitFocus: ((focused: boolean) => void) | undefined;
    const focusObserver = vi.fn((listener: (focused: boolean) => void) => {
      emitFocus = listener;
      return { dispose };
    });
    const operations = createFilesWorkspaceOperations(createHost(), focusObserver);
    const listener = vi.fn();

    const unlisten = await operations.subscribeWindowFocus?.(listener);
    emitFocus?.(true);
    expect(listener).toHaveBeenCalledWith(true);
    await unlisten?.();
    expect(dispose).toHaveBeenCalledOnce();
  });

  it("preserves complete revisions across tree, read, save, create, move and root drop", async () => {
    const rootRevision = revision("directory", null, 4096);
    const beforeRevision = revision("file", "before-sha", 23);
    const afterRevision = revision("file", "after-sha", 31);
    const moveRevision = revision("file", null, 31);
    const tree = vi.fn(async () => ({
      entries: [
        {
          name: "main.ts",
          relativePath: "src/main.ts",
          metadata: {
            kind: "file" as const,
            size: beforeRevision.size,
            modifiedUnixMillis: beforeRevision.modifiedUnixMillis,
            revision: beforeRevision,
          },
          canExpand: false,
        },
      ],
      directoryRevision: rootRevision,
      nextCursor: null,
      snapshotToken: "snapshot-1",
      totalEntries: 1,
      depth: 1,
    }));
    const readFile = vi.fn(async () => ({
      metadata: {
        kind: "file" as const,
        size: beforeRevision.size,
        modifiedUnixMillis: beforeRevision.modifiedUnixMillis,
        revision: beforeRevision,
      },
      kind: "text" as const,
      encoding: "utf16_le" as const,
      lineEnding: "crlf" as const,
      text: "hello\r\n",
      bytesRead: 16,
      truncated: false,
    }));
    const saveFile = vi.fn(
      async ({ relativePath }: Parameters<WorkspaceMutationHostAdapter["saveFile"]>[0]) => ({
        relativePath,
        revision: afterRevision,
      }),
    );
    const createEntry = vi.fn(
      async ({
        relativePath,
        kind,
      }: Parameters<WorkspaceMutationHostAdapter["createEntry"]>[0]) => ({
        relativePath,
        kind,
        revision: afterRevision,
      }),
    );
    const moveEntry = vi.fn(
      async ({
        fromRelativePath,
        toRelativePath,
      }: Parameters<WorkspaceMutationHostAdapter["moveEntry"]>[0]) => ({
        fromRelativePath,
        toRelativePath,
        revision: moveRevision,
      }),
    );
    const importDrop = vi.fn(async () => ({ importedRelativePaths: ["dropped.txt"] }));
    const operations = createFilesWorkspaceOperations(
      createHost({ tree, readFile, saveFile, createEntry, moveEntry, importDrop }),
    );

    await expect(
      operations.tree({ workspaceId: "ws_test", relativePath: "" }),
    ).resolves.toMatchObject({
      directoryRevision: rootRevision,
      entries: [{ relativePath: "src/main.ts", revision: beforeRevision }],
    });
    await expect(
      operations.readFile({ workspaceId: "ws_test", relativePath: "src/main.ts" }),
    ).resolves.toMatchObject({
      revision: beforeRevision,
      encoding: "utf16_le",
      newline: "crlf",
    });
    await expect(
      operations.saveFile({
        workspaceId: "ws_test",
        relativePath: "src/main.ts",
        expectedRevision: beforeRevision,
        mutationId: "save-1",
        content: "next\r\n",
        encoding: "utf16_le",
        newline: "crlf",
      }),
    ).resolves.toEqual({ revision: afterRevision, mutationId: "save-1" });
    await expect(
      operations.createEntry?.({
        workspaceId: "ws_test",
        relativePath: "src/new.ts",
        expectedRevision: null,
        mutationId: "create-1",
        kind: "file",
        initialContent: { content: "copy\r\n", encoding: "utf16_le", newline: "crlf" },
      }),
    ).resolves.toEqual({ revision: afterRevision });
    await expect(
      operations.moveEntry?.({
        workspaceId: "ws_test",
        relativePath: "src/main.ts",
        expectedRevision: afterRevision,
        mutationId: "move-1",
        targetDirectory: "tests",
        newName: "app.ts",
      }),
    ).resolves.toEqual({ revision: moveRevision });
    await operations.importDrop?.({
      workspaceId: "ws_test",
      targetDirectory: "",
      expectedRevision: rootRevision,
      mutationId: "drop-1",
      dropToken: "opaque-drop",
    });

    expect(tree).toHaveBeenCalledWith(expect.objectContaining({ pageSize: 200 }));
    expect(saveFile).toHaveBeenCalledWith(
      expect.objectContaining({ expectedRevision: beforeRevision }),
    );
    expect(createEntry).toHaveBeenCalledWith(
      expect.objectContaining({
        relativePath: "src/new.ts",
        expectedRevision: null,
        content: { text: "copy\r\n", encoding: "utf16_le", lineEnding: "crlf" },
      }),
    );
    expect(moveEntry).toHaveBeenCalledWith(
      expect.objectContaining({
        fromRelativePath: "src/main.ts",
        toRelativePath: "tests/app.ts",
        expectedRevision: afterRevision,
      }),
    );
    expect(importDrop).toHaveBeenCalledWith({
      workspaceId: "ws_test",
      destinationRelativePath: "",
      expectedRevision: rootRevision,
      dropToken: "opaque-drop",
      mutationId: "drop-1",
    });
  });

  it("preserves special node kinds and bounded search facts while hiding unavailable open targets", async () => {
    const tree = vi.fn(async () => ({
      entries: [
        {
          name: "linked",
          relativePath: "linked",
          metadata: {
            kind: "symlink" as const,
            size: 0,
            modifiedUnixMillis: 1_725_000_000_123,
            revision: revision("symlink", null, 0),
          },
          canExpand: false,
        },
        {
          name: "junction",
          relativePath: "junction",
          metadata: {
            kind: "reparse_point" as const,
            size: 0,
            modifiedUnixMillis: 1_725_000_000_123,
            revision: revision("reparse_point", null, 0),
          },
          canExpand: false,
        },
        {
          name: "device",
          relativePath: "device",
          metadata: {
            kind: "other" as const,
            size: 0,
            modifiedUnixMillis: 1_725_000_000_123,
            revision: revision("other", null, 0),
          },
          canExpand: false,
        },
      ],
      directoryRevision: revision("directory", null, 0),
      nextCursor: null,
      snapshotToken: "special-snapshot",
      totalEntries: 3,
      depth: 0,
    }));
    const search = vi.fn(async () => ({
      hits: [
        {
          relativePath: "main.ts",
          line: 7,
          column: 3,
          snippet: "bounded match",
          encoding: "utf8" as const,
        },
      ],
      truncated: true,
      scannedEntries: 2_000,
      skippedFiles: 4,
    }));
    const openTargets = vi.fn(async () => ({
      targets: [
        {
          target: "file_explorer" as const,
          displayName: "文件资源管理器",
          available: true,
          reason: null,
        },
        {
          target: "visual_studio" as const,
          displayName: "Visual Studio",
          available: false,
          reason: "not_installed" as const,
        },
      ],
    }));
    const open = vi.fn(async ({ target, relativePath }) => ({
      opened: true as const,
      target,
      relativePath: relativePath ?? "",
      entryKind: "directory" as const,
    }));
    const operations = createFilesWorkspaceOperations(
      createHost({ tree, search, openTargets, open }),
    );

    await expect(
      operations.tree({ workspaceId: "ws_test", relativePath: "" }),
    ).resolves.toMatchObject({
      entries: [
        { kind: "symlink", relativePath: "linked" },
        { kind: "reparse_point", relativePath: "junction" },
        { kind: "other", relativePath: "device" },
      ],
    });
    await expect(
      operations.search?.({ workspaceId: "ws_test", relativePath: "", query: "match" }),
    ).resolves.toMatchObject({
      truncated: true,
      scannedEntries: 2_000,
      skippedFiles: 4,
      hits: [{ path: "main.ts", preview: "bounded match" }],
    });
    await expect(operations.openTargets?.({ workspaceId: "ws_test" })).resolves.toEqual([
      { target: "file_explorer", displayName: "文件资源管理器" },
    ]);
    await operations.openTarget?.({
      workspaceId: "ws_test",
      target: "file_explorer",
      relativePath: "",
    });
    expect(open).toHaveBeenCalledWith({
      workspaceId: "ws_test",
      target: "file_explorer",
      relativePath: "",
    });
  });

  it("subscribes before native start, forwards the early event and cleans up exactly once", async () => {
    const order: string[] = [];
    const earlyRevision = revision("file", "early");
    const listener = vi.fn();
    const unlisten = vi.fn(async () => undefined);
    let nativeListener: ((event: WorkspaceChangedEvent) => void) | undefined;
    const subscribeChanged = vi.fn(async (callback: (event: WorkspaceChangedEvent) => void) => {
      order.push("subscribe");
      nativeListener = callback;
      return unlisten;
    });
    const watchStart = vi.fn(
      async ({ generation }: Parameters<WorkspaceMutationHostAdapter["watchStart"]>[0]) => {
        order.push(`start:${generation}`);
        nativeListener?.({
          relativePath: "main.ts",
          generation,
          revision: earlyRevision,
          requiresRescan: false,
        });
        return { started: true, generation };
      },
    );
    const watchStop = vi.fn(async () => ({ stopped: true }));
    const operations = createFilesWorkspaceOperations(
      createHost({ subscribeChanged, watchStart, watchStop }),
    );

    const subscription = await operations.watchStart?.({ workspaceId: "ws_test" }, listener);
    const generation = vi.mocked(watchStart).mock.calls[0]?.[0].generation;
    expect(generation).toBeDefined();
    expect(order).toEqual(["subscribe", `start:${generation}`]);
    expect(listener).toHaveBeenCalledWith({
      relativePath: "main.ts",
      generation,
      revision: earlyRevision,
      requiresRescan: false,
    });

    await subscription?.stop();
    await subscription?.stop();
    expect(watchStop).toHaveBeenCalledTimes(1);
    expect(watchStop).toHaveBeenCalledWith({ workspaceId: "ws_test", generation });
    expect(unlisten).toHaveBeenCalledTimes(1);
  });

  it("buffers early events and treats focus reconciliation before start ACK as a no-op", async () => {
    const start = deferred<{ started: boolean; generation: number }>();
    const listener = vi.fn();
    let nativeListener: ((event: WorkspaceChangedEvent) => void) | undefined;
    let requestedGeneration = 0;
    const watchRescan = vi.fn(async () => ({
      generation: requestedGeneration,
      requiresRescan: false,
      emittedPaths: 0,
    }));
    const operations = createFilesWorkspaceOperations(
      createHost({
        subscribeChanged: async (next) => {
          nativeListener = next;
          return () => undefined;
        },
        watchStart: async ({ generation }) => {
          requestedGeneration = generation;
          return start.promise;
        },
        watchRescan,
      }),
    );

    const pendingStart = operations.watchStart?.({ workspaceId: "ws_test" }, listener);
    await vi.waitFor(() => expect(requestedGeneration).toBeGreaterThan(0));
    nativeListener?.({
      relativePath: "src/main.rs",
      generation: requestedGeneration,
      revision: revision("file", "early"),
      requiresRescan: false,
    });
    await expect(operations.watchRescan?.({ workspaceId: "ws_test" })).resolves.toBeUndefined();
    expect(watchRescan).not.toHaveBeenCalled();
    expect(listener).not.toHaveBeenCalled();

    start.resolve({ started: true, generation: requestedGeneration });
    await pendingStart;
    expect(listener).toHaveBeenCalledOnce();
    await operations.watchRescan?.({ workspaceId: "ws_test" });
    expect(watchRescan).toHaveBeenCalledWith({
      workspaceId: "ws_test",
      generation: requestedGeneration,
    });
  });

  it("scopes a late watcher cleanup to its own generation", async () => {
    const starts = new Map<number, Deferred<{ started: boolean; generation: number }>>();
    const watchStart = vi.fn(
      ({ generation }: Parameters<WorkspaceMutationHostAdapter["watchStart"]>[0]) => {
        const gate = deferred<{ started: boolean; generation: number }>();
        starts.set(generation, gate);
        return gate.promise;
      },
    );
    const watchStop = vi.fn(async () => ({ stopped: true }));
    const unlisteners = [vi.fn(async () => undefined), vi.fn(async () => undefined)];
    let subscriptionIndex = 0;
    const subscribeChanged = vi.fn(async () => unlisteners[subscriptionIndex++] ?? vi.fn());
    const operations = createFilesWorkspaceOperations(
      createHost({ subscribeChanged, watchStart, watchStop }),
    );

    const firstPending = operations.watchStart?.({ workspaceId: "ws_test" }, () => undefined);
    const secondPending = operations.watchStart?.({ workspaceId: "ws_test" }, () => undefined);
    await vi.waitFor(() => expect(starts.size).toBe(2));
    const generations = [...starts.keys()].sort((left, right) => left - right);
    const firstGeneration = generations[0];
    const secondGeneration = generations[1];
    expect(firstGeneration).toBeDefined();
    expect(secondGeneration).toBeDefined();
    starts.get(secondGeneration!)?.resolve({ started: true, generation: secondGeneration! });
    const second = await secondPending;
    starts.get(firstGeneration!)?.resolve({ started: true, generation: firstGeneration! });
    const first = await firstPending;

    await first?.stop();
    expect(watchStop).toHaveBeenNthCalledWith(1, {
      workspaceId: "ws_test",
      generation: firstGeneration,
    });
    await second?.stop();
    expect(watchStop).toHaveBeenNthCalledWith(2, {
      workspaceId: "ws_test",
      generation: secondGeneration,
    });
    expect(unlisteners[0]).toHaveBeenCalledTimes(1);
    expect(unlisteners[1]).toHaveBeenCalledTimes(1);
  });

  it("rejects late events from another workspace or replaced generation", async () => {
    const nativeListeners: Array<(event: WorkspaceChangedEvent) => void> = [];
    const requestedGenerations: number[] = [];
    const subscribeChanged = vi.fn(async (listener: (event: WorkspaceChangedEvent) => void) => {
      nativeListeners.push(listener);
      return () => undefined;
    });
    const watchStart = vi.fn(
      async ({ generation }: Parameters<WorkspaceMutationHostAdapter["watchStart"]>[0]) => {
        requestedGenerations.push(generation);
        return { started: true, generation };
      },
    );
    const operations = createFilesWorkspaceOperations(createHost({ subscribeChanged, watchStart }));
    const oldListener = vi.fn();
    const nextListener = vi.fn();
    const replacementListener = vi.fn();

    const oldSubscription = await operations.watchStart?.({ workspaceId: "ws_old" }, oldListener);
    const nextSubscription = await operations.watchStart?.(
      { workspaceId: "ws_next" },
      nextListener,
    );
    const oldGeneration = requestedGenerations[0];
    const nextGeneration = requestedGenerations[1];
    expect(oldGeneration).toBeDefined();
    expect(nextGeneration).toBeDefined();
    expect(nextGeneration).not.toBe(oldGeneration);

    const lateOldEvent: WorkspaceChangedEvent = {
      relativePath: "old.txt",
      generation: oldGeneration!,
      revision: null,
      requiresRescan: false,
    };
    const currentEvent: WorkspaceChangedEvent = {
      relativePath: "current.txt",
      generation: nextGeneration!,
      revision: null,
      requiresRescan: false,
    };
    nativeListeners[1]?.(lateOldEvent);
    nativeListeners[0]?.(currentEvent);
    expect(nextListener).not.toHaveBeenCalled();
    expect(oldListener).not.toHaveBeenCalled();

    nativeListeners[1]?.(currentEvent);
    expect(nextListener).toHaveBeenCalledOnce();
    expect(nextListener).toHaveBeenCalledWith(currentEvent);

    const replacementSubscription = await operations.watchStart?.(
      { workspaceId: "ws_next" },
      replacementListener,
    );
    const replacementGeneration = requestedGenerations[2];
    expect(replacementGeneration).toBeDefined();
    nativeListeners[2]?.(currentEvent);
    expect(replacementListener).not.toHaveBeenCalled();
    const replacementEvent: WorkspaceChangedEvent = {
      ...currentEvent,
      generation: replacementGeneration!,
    };
    nativeListeners[2]?.(replacementEvent);
    expect(replacementListener).toHaveBeenCalledWith(replacementEvent);

    await oldSubscription?.stop();
    await nextSubscription?.stop();
    await replacementSubscription?.stop();
  });

  it("unlistens when watcher start fails and when native stop rejects", async () => {
    const startUnlisten = vi.fn(async () => undefined);
    const startFailure = createFilesWorkspaceOperations(
      createHost({
        subscribeChanged: async () => startUnlisten,
        watchStart: async () => {
          throw new Error("start failed");
        },
      }),
    );
    await expect(
      startFailure.watchStart?.({ workspaceId: "ws_test" }, () => undefined),
    ).rejects.toThrow("start failed");
    expect(startUnlisten).toHaveBeenCalledTimes(1);

    const stopUnlisten = vi.fn(async () => undefined);
    const stopFailure = createFilesWorkspaceOperations(
      createHost({
        subscribeChanged: async () => stopUnlisten,
        watchStop: async () => {
          throw new Error("stop failed");
        },
      }),
    );
    const subscription = await stopFailure.watchStart?.(
      { workspaceId: "ws_test" },
      () => undefined,
    );
    await expect(subscription?.stop()).rejects.toThrow("stop failed");
    expect(stopUnlisten).toHaveBeenCalledTimes(1);
  });
});
