// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import type { ReviewFile, ReviewSnapshot } from "@/features/workbench/review/domain/types";
import {
  actionLabel,
  canRenderUnifiedDiff,
  canRenderTextDiff,
  diffMatchesSnapshot,
  filterReviewFiles,
  fileStatusLabel,
  retainFileSelection,
  sourceKey,
  sourceLabel,
  targetKey,
} from "@/features/workbench/review/domain/model";

const files: ReviewFile[] = [
  {
    fileId: "file_a",
    layer: "unstaged",
    path: "src/a.ts",
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
  {
    fileId: "file_b",
    layer: "unstaged",
    path: "README.md",
    oldPath: null,
    status: "added",
    additions: 4,
    deletions: 0,
    binary: false,
    truncated: false,
    hunks: [],
  },
  {
    fileId: "file_c",
    layer: "unstaged",
    path: "assets/icon.bin",
    oldPath: null,
    status: "conflicted",
    additions: null,
    deletions: null,
    binary: true,
    truncated: false,
    hunks: [],
  },
];

/** 构造最小权威快照，测试只验证纯领域规则而不引入 React 或 native adapter。 */
function snapshot(source: ReviewSnapshot["source"] = { kind: "unstaged" }): ReviewSnapshot {
  return {
    workspaceId: "ws_demo",
    source,
    revision: "rev_1",
    files,
    stats: { files: files.length, additions: 6, deletions: 1, binaryFiles: 1, truncated: false },
    capabilities: {
      stage: source.kind === "unstaged",
      unstage: source.kind === "staged",
      revert: source.kind !== "branch" && source.kind !== "commit",
    },
  };
}

describe("Review model", () => {
  it("keeps source keys unambiguous across source variants", () => {
    expect(sourceKey({ kind: "uncommitted" })).toBe("uncommitted");
    expect(sourceLabel({ kind: "uncommitted" })).toBe("未提交");
    expect(sourceKey({ kind: "unstaged" })).toBe("unstaged");
    expect(sourceKey({ kind: "branch", refId: "main" })).toBe("branch:main");
    expect(sourceLabel({ kind: "commit", commitId: "abcdef012345" })).toBe("提交 abcdef01");
  });

  it("filters status and query from authoritative file metadata", () => {
    expect(filterReviewFiles(files, "all", "").map((file) => file.fileId)).toEqual([
      "file_a",
      "file_b",
      "file_c",
    ]);
    expect(filterReviewFiles(files, "added", "read").map((file) => file.fileId)).toEqual([
      "file_b",
    ]);
    expect(filterReviewFiles(files, "all", "BIN").map((file) => file.fileId)).toEqual(["file_c"]);
    expect(fileStatusLabel("conflicted")).toBe("冲突");
  });

  it("retains selection only inside the current snapshot", () => {
    expect(retainFileSelection(files, "file_b")).toBe("file_b");
    expect(retainFileSelection(files, "missing")).toBe("file_a");
    expect(retainFileSelection([], "missing")).toBeUndefined();
  });

  it("rejects stale Diff identities", () => {
    const current = snapshot();
    expect(
      diffMatchesSnapshot(
        {
          workspaceId: "ws_demo",
          source: current.source,
          revision: "rev_1",
          fileId: "file_a",
          layer: "unstaged",
          path: "src/a.ts",
          oldPath: null,
          status: "modified",
          binary: false,
          truncated: false,
          original: "old",
          modified: "new",
          unified: null,
          hunks: [],
          lines: [],
        },
        current,
      ),
    ).toBe(true);
    expect(
      diffMatchesSnapshot(
        {
          workspaceId: "ws_demo",
          source: current.source,
          revision: "rev_old",
          fileId: "file_a",
          layer: "unstaged",
          path: "src/a.ts",
          oldPath: null,
          status: "modified",
          binary: false,
          truncated: false,
          original: "old",
          modified: "new",
          unified: null,
          hunks: [],
          lines: [],
        },
        current,
      ),
    ).toBe(false);
    expect(
      diffMatchesSnapshot(
        {
          workspaceId: "ws_demo",
          source: current.source,
          revision: "rev_1",
          fileId: "file_a",
          layer: "staged",
          path: "src/a.ts",
          oldPath: null,
          status: "modified",
          binary: false,
          truncated: false,
          original: "old",
          modified: "new",
          unified: null,
          hunks: [],
          lines: [],
        },
        current,
      ),
    ).toBe(false);
  });

  it("builds target and action labels without client patches", () => {
    expect(targetKey({ kind: "file", fileId: "file_a" })).toBe("file:file_a");
    expect(targetKey({ kind: "hunk", fileId: "file_a", hunkId: "hunk_a" })).toBe(
      "hunk:file_a:hunk_a",
    );
    expect(actionLabel("revert", { kind: "all" })).toBe("撤销全部");
    expect(
      canRenderTextDiff({
        workspaceId: "ws_demo",
        source: { kind: "unstaged" },
        revision: "rev_1",
        fileId: "file_a",
        layer: "unstaged",
        path: "src/a.ts",
        oldPath: null,
        status: "modified",
        binary: false,
        truncated: false,
        original: "old",
        modified: "new",
        unified: null,
        hunks: [],
        lines: [],
      }),
    ).toBe(true);
    expect(
      canRenderUnifiedDiff({
        workspaceId: "ws_demo",
        source: { kind: "unstaged" },
        revision: "rev_1",
        fileId: "file_a",
        layer: "unstaged",
        path: "src/a.ts",
        oldPath: null,
        status: "modified",
        binary: false,
        truncated: false,
        original: null,
        modified: null,
        unified: "@@ -1 +1 @@\n-old\n+new",
        hunks: [],
        lines: [],
      }),
    ).toBe(true);
  });
});
