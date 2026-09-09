// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import {
  buildReviewTreeRows,
  defaultExpandedReviewTree,
  type ReviewTreeFile,
  type ReviewTreeLayer,
} from "@/features/workbench/review/domain/reviewTree";

/** 以最小可变字段构造文件，确保测试关心树规则而不是重复 DTO 样板。 */
function reviewFile(
  id: string,
  path: string,
  layer: ReviewTreeLayer,
  status = "modified",
): ReviewTreeFile {
  return {
    id,
    path,
    layer,
    status,
    additions: 1,
    deletions: 1,
    binary: false,
  };
}

describe("reviewTree", () => {
  it("按冲突、未暂存、已暂存、未跟踪、比较排序并隐藏空组", () => {
    const files = [
      reviewFile("comparison", "z/compare.ts", "comparison"),
      reviewFile("staged", "a/staged.ts", "staged"),
      reviewFile("conflict", "b/conflict.ts", "unstaged", "conflicted"),
      reviewFile("untracked", "c/new.ts", "untracked", "untracked"),
      reviewFile("unstaged", "d/working.ts", "unstaged"),
    ];

    const rows = buildReviewTreeRows(
      files,
      "status",
      "",
      defaultExpandedReviewTree(files, "status"),
    );

    expect(rows.filter((row) => row.kind === "group").map((row) => row.label)).toEqual([
      "冲突",
      "未暂存",
      "已暂存",
      "未跟踪",
      "比较",
    ]);
    expect(rows.filter((row) => row.kind === "group").map((row) => row.count)).toEqual([
      1, 1, 1, 1, 1,
    ]);
  });

  it("目录按名称排序并压缩单子目录链", () => {
    const files = [
      reviewFile("source-b", "src/main/java/B.java", "comparison"),
      reviewFile("docs", "docs/Guide.md", "comparison"),
      reviewFile("source-a", "src/main/java/A.java", "comparison"),
    ];

    const rows = buildReviewTreeRows(
      files,
      "directory",
      "",
      defaultExpandedReviewTree(files, "directory"),
    );

    expect(rows.filter((row) => row.kind === "folder").map((row) => row.label)).toEqual([
      "docs",
      "src/main/java",
    ]);
    expect(rows.filter((row) => row.kind === "file").map((row) => row.label)).toEqual([
      "Guide.md",
      "A.java",
      "B.java",
    ]);
  });

  it("同一路径在不同暂存层保持两个唯一可选身份", () => {
    const files = [
      reviewFile("src-main:unstaged", "src/main.ts", "unstaged"),
      reviewFile("src-main:staged", "src/main.ts", "staged"),
    ];

    const rows = buildReviewTreeRows(files, "flat", "", new Set()).filter(
      (row) => row.kind === "file",
    );

    expect(rows).toHaveLength(2);
    expect(rows.map((row) => row.id)).toEqual([
      "flat:file:src-main:unstaged",
      "flat:file:src-main:staged",
    ]);
    expect(rows.map((row) => row.file.layer)).toEqual(["unstaged", "staged"]);
  });

  it("搜索完整路径时临时展开祖先，清空后仍服从原折叠集合", () => {
    const files = [reviewFile("needle", "packages/client/src/Needle.tsx", "comparison")];
    const collapsed = new Set<string>();

    const beforeSearch = buildReviewTreeRows(files, "directory", "", collapsed);
    const duringSearch = buildReviewTreeRows(files, "directory", "CLIENT/SRC", collapsed);
    const afterSearch = buildReviewTreeRows(files, "directory", "", collapsed);

    expect(beforeSearch.map((row) => row.kind)).toEqual(["folder"]);
    expect(duringSearch.map((row) => row.kind)).toEqual(["folder", "file"]);
    expect(duringSearch.at(-1)).toMatchObject({
      kind: "file",
      label: "Needle.tsx",
      file: { id: "needle" },
    });
    expect(afterSearch).toEqual(beforeSearch);
    expect(collapsed.size).toBe(0);
  });
});
