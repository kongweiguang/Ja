// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it, vi } from "vitest";
import type { RuntimeNativeBridge } from "@/api/tauri/runtime";
import {
  JA_REVIEW_COMMANDS,
  JA_REVIEW_EVENTS,
  TauriReviewAdapter,
  normalizeReviewError,
  type ReviewCatalog,
  type ReviewFileDiff,
  type ReviewSnapshot,
} from "@/api/tauri/review";

const source = { kind: "unstaged" } as const;
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

const snapshot: ReviewSnapshot = {
  workspaceId: "ws_demo",
  source,
  revision: "rev_1",
  files: [
    {
      fileId: "file_a",
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
          hunkId: "hunk_a",
          header: "@@ -1 +1,2 @@",
          oldStart: 1,
          oldLines: 1,
          newStart: 1,
          newLines: 2,
        },
      ],
    },
  ],
  stats: { files: 1, additions: 2, deletions: 1, binaryFiles: 0, truncated: false },
  capabilities: { stage: true, unstage: false, revert: true },
};

const fileDiff: ReviewFileDiff = {
  workspaceId: "ws_demo",
  source,
  revision: "rev_1",
  fileId: "file_a",
  layer: "unstaged",
  path: "src/main.rs",
  oldPath: null,
  status: "modified",
  binary: false,
  truncated: false,
  original: null,
  modified: null,
  unified: "@@ -1 +1,2 @@\n-old\n+new\n+line",
  hunks: snapshot.files[0]?.hunks ?? [],
  lines: [
    { kind: "deletion", oldLine: 1, newLine: null, text: "old" },
    { kind: "addition", oldLine: null, newLine: 1, text: "new" },
  ],
};

/** 创建严格测试 bridge，捕获事件并按 command 返回结果，避免通用 mock 掩盖 wire drift。 */
function createBridge(): RuntimeNativeBridge & { emit: (payload: unknown) => void } {
  let handler: ((payload: unknown) => void) | undefined;
  const bridge: RuntimeNativeBridge & { emit: (payload: unknown) => void } = {
    invoke: vi.fn(async (command: string): Promise<unknown> => {
      if (command === JA_REVIEW_COMMANDS.catalog) return catalog;
      if (command === JA_REVIEW_COMMANDS.snapshot) return snapshot;
      if (command === JA_REVIEW_COMMANDS.fileDiff) return fileDiff;
      if (command === JA_REVIEW_COMMANDS.apply)
        return { workspaceId: "ws_demo", operationId: "review_op", applied: true, snapshot };
      return { workspaceId: "ws_demo", operationId: "review_op", cancelled: true };
    }) as RuntimeNativeBridge["invoke"],
    listen: vi.fn(async (_event: string, next: (payload: unknown) => void) => {
      handler = next as (payload: unknown) => void;
      return () => {
        handler = undefined;
      };
    }),
    emit: (payload) => handler?.(payload),
  };
  return bridge;
}

describe("TauriReviewAdapter", () => {
  it("invokes each closed command with typed input and exact DTOs", async () => {
    const bridge = createBridge();
    const adapter = new TauriReviewAdapter(bridge);
    await expect(adapter.catalog({ workspaceId: "ws_demo", maxCommits: 10 })).resolves.toEqual(
      catalog,
    );
    await expect(adapter.snapshot({ workspaceId: "ws_demo", source })).resolves.toEqual(snapshot);
    await expect(
      adapter.fileDiff({ workspaceId: "ws_demo", source, revision: "rev_1", fileId: "file_a" }),
    ).resolves.toEqual(fileDiff);
    await expect(
      adapter.apply({
        workspaceId: "ws_demo",
        source,
        revision: "rev_1",
        action: "stage",
        target: { kind: "file", fileId: "file_a" },
        operationId: "review_op",
      }),
    ).resolves.toMatchObject({ applied: true });
    await expect(
      adapter.cancel({ workspaceId: "ws_demo", operationId: "review_op" }),
    ).resolves.toMatchObject({ cancelled: true });
    expect(bridge.invoke).toHaveBeenNthCalledWith(1, JA_REVIEW_COMMANDS.catalog, {
      input: { workspaceId: "ws_demo", maxCommits: 10 },
    });
    expect(bridge.invoke).toHaveBeenNthCalledWith(4, JA_REVIEW_COMMANDS.apply, {
      input: {
        workspaceId: "ws_demo",
        source,
        revision: "rev_1",
        action: "stage",
        target: { kind: "file", fileId: "file_a" },
        operationId: "review_op",
      },
    });
  });

  it("rejects removed review sources before IPC", async () => {
    const bridge = createBridge();
    const adapter = new TauriReviewAdapter(bridge);
    await expect(
      adapter.snapshot({
        workspaceId: "ws_demo",
        source: { kind: "unknown" } as never,
      }),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
  });

  it("accepts the aggregate uncommitted source and requires authoritative file layers", async () => {
    const bridge = createBridge();
    bridge.invoke = vi.fn(async () => ({
      ...snapshot,
      source: { kind: "uncommitted" },
      capabilities: { stage: true, unstage: true, revert: true },
    })) as RuntimeNativeBridge["invoke"];
    await expect(
      new TauriReviewAdapter(bridge).snapshot({
        workspaceId: "ws_demo",
        source: { kind: "uncommitted" },
      }),
    ).resolves.toMatchObject({ source: { kind: "uncommitted" } });

    bridge.invoke = vi.fn(async () => ({
      ...snapshot,
      files: snapshot.files.map((file) => {
        // 主动删除必填 layer，以验证 adapter 拒绝缺字段而非仅拒绝非法值。
        const incomplete: Partial<typeof file> = { ...file };
        delete incomplete.layer;
        return incomplete;
      }),
    })) as RuntimeNativeBridge["invoke"];
    await expect(
      new TauriReviewAdapter(bridge).snapshot({ workspaceId: "ws_demo", source }),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE" });
  });

  it("drops malformed invalidation events and forwards valid metadata hints", async () => {
    expect(JA_REVIEW_EVENTS.invalidated).toBe("review/invalidated");
    const bridge = createBridge();
    const adapter = new TauriReviewAdapter(bridge);
    const received: unknown[] = [];
    await adapter.subscribeInvalidated((event) => received.push(event));
    bridge.emit({ workspaceId: "ws_demo", generation: 3, reason: "mutation" });
    bridge.emit({ workspaceId: "ws_demo", generation: 3, reason: "mutation", path: "C:\\private" });
    bridge.emit({ workspaceId: "ws_demo", generation: 3, reason: "unknown" });
    expect(received).toEqual([{ workspaceId: "ws_demo", generation: 3, reason: "mutation" }]);
    expect(bridge.listen).toHaveBeenCalledWith(JA_REVIEW_EVENTS.invalidated, expect.any(Function));
  });

  it("redacts unknown native failures and preserves stable review codes", () => {
    expect(
      normalizeReviewError({ code: "REVIEW_STALE", message: "C:\\private\\file" }),
    ).toMatchObject({ code: "REVIEW_STALE", message: "工作区已经变化，请重新读取 Review。" });
    expect(
      normalizeReviewError({ code: "NOT_GIT_REPOSITORY", message: "C:\\private\\file" }),
    ).toMatchObject({
      code: "NOT_GIT_REPOSITORY",
      message: "当前目录不是 Git 工作区，审查不可用。",
      retryable: false,
    });
    expect(normalizeReviewError({ code: "UNKNOWN", message: "C:\\private\\file" }).code).toBe(
      "RUNTIME_UNAVAILABLE",
    );
  });

  it("rejects malformed paths and unknown DTO fields at the boundary", async () => {
    const bridge = createBridge();
    const adapter = new TauriReviewAdapter(bridge);
    await expect(
      adapter.snapshot({ workspaceId: "ws_demo", source, extra: true } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    await expect(
      adapter.fileDiff({
        workspaceId: "ws_demo",
        source,
        revision: "rev_1",
        fileId: "file_a",
        path: "../outside",
      } as never),
    ).rejects.toMatchObject({ code: "INVALID_INPUT" });
    const malformedBridge = createBridge();
    malformedBridge.invoke = vi.fn(async () => ({
      ...snapshot,
      files: [{ ...snapshot.files[0], extra: true }],
    })) as RuntimeNativeBridge["invoke"];
    await expect(
      new TauriReviewAdapter(malformedBridge).snapshot({ workspaceId: "ws_demo", source }),
    ).rejects.toMatchObject({ code: "RUNTIME_UNAVAILABLE" });
  });
});
