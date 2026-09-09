// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { describe, expect, it } from "vitest";
import { parseTurnReviewDiff } from "@/features/workbench/review/domain/turnReviewDiff";

const VALID_DIFF = "--- a/src/main.ts\n+++ b/src/main.ts\n@@ -1 +1 @@\n-old\n+new\n";
const NO_NEWLINE_MARKER = "\\ No newline at end of file";

describe("parseTurnReviewDiff", () => {
  /** 标准文件头与 hunk 必须完整映射为只读行，并保留 UTF-8 正文。 */
  it("解析完整 unified diff", () => {
    expect(parseTurnReviewDiff(VALID_DIFF)).toEqual([
      {
        path: "src/main.ts",
        oldPath: null,
        additions: 1,
        deletions: 1,
        hunks: [
          {
            header: "@@ -1 +1 @@",
            oldStart: 1,
            oldLines: 1,
            newStart: 1,
            newLines: 1,
          },
        ],
        lines: [
          { kind: "deletion", oldLine: 1, newLine: null, text: "old" },
          { kind: "addition", oldLine: null, newLine: 1, text: "new" },
        ],
      },
    ]);
  });

  /** 旧侧、新侧和纯 EOF newline 变化都保留真实正文，但 marker 不进入可见行。 */
  it.each([
    [
      "旧侧无末尾换行",
      `--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n${NO_NEWLINE_MARKER}\n+new\n`,
      ["old", "new"],
    ],
    [
      "新侧无末尾换行",
      `--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n${NO_NEWLINE_MARKER}\n`,
      ["old", "new"],
    ],
    [
      "仅 EOF newline 变化",
      `--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-same\n${NO_NEWLINE_MARKER}\n+same\n`,
      ["same", "same"],
    ],
  ])("过滤%s的非行 marker", (_label, unified, visibleText) => {
    const [file] = parseTurnReviewDiff(unified);
    expect(file).toMatchObject({ additions: 1, deletions: 1 });
    expect(file?.lines.map((line) => line.text)).toEqual(visibleText);
    expect(file?.lines).toHaveLength(2);
  });

  /** 宽松第三方 parser 会忽略的垃圾、空条目、非法计数和尾随内容必须全部失败关闭。 */
  it.each([
    ["纯垃圾", "garbage\n"],
    ["空输入", ""],
    ["缺少 hunk", "--- a/src/main.ts\n+++ b/src/main.ts\n"],
    ["hunk 行数不足", "--- a/src/main.ts\n+++ b/src/main.ts\n@@ -1,2 +1 @@\n-old\n+new\n"],
    ["尾随垃圾", `${VALID_DIFF}trailing\n`],
    ["路径逃逸", "--- a/../secret\n+++ b/../secret\n@@ -1 +1 @@\n-old\n+new\n"],
    ["正文前 marker", `--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n${NO_NEWLINE_MARKER}\n-old\n+new\n`],
    [
      "非末行 marker",
      `--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n-old1\n${NO_NEWLINE_MARKER}\n-old2\n+new1\n+new2\n`,
    ],
  ])("拒绝%s", (_label, unified) => {
    expect(() => parseTurnReviewDiff(unified)).toThrow("invalid unified diff");
  });
});
