// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import parseDiff from "parse-diff";

export interface ParsedTurnReviewLine {
  readonly kind: "context" | "addition" | "deletion";
  readonly oldLine: number | null;
  readonly newLine: number | null;
  readonly text: string;
}

export interface ParsedTurnReviewFile {
  readonly path: string;
  readonly oldPath: string | null;
  readonly additions: number;
  readonly deletions: number;
  readonly hunks: readonly ParsedTurnReviewHunk[];
  readonly lines: readonly ParsedTurnReviewLine[];
}

export interface ParsedTurnReviewHunk {
  readonly header: string;
  readonly oldStart: number;
  readonly oldLines: number;
  readonly newStart: number;
  readonly newLines: number;
}

interface ValidatedHunk {
  readonly oldStart: number;
  readonly oldLines: number;
  readonly newStart: number;
  readonly newLines: number;
}

interface ValidatedFile {
  readonly from: string | null;
  readonly to: string | null;
  readonly additions: number;
  readonly deletions: number;
  readonly hunks: readonly ValidatedHunk[];
}

const HUNK_HEADER = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(?: .*)?$/u;
const SAFE_RELATIVE_PATH = /^(?!\/)(?![A-Za-z]:)(?!.*\\)(?!.*(?:^|\/)\.\.(?:\/|$)).+$/u;
const NO_NEWLINE_MARKER = "\\ No newline at end of file";

/** 所有语法拒绝都使用同一脱敏错误，Worker 不会把 artifact 内容带回 UI。 */
function invalidDiff(): never {
  throw new Error("invalid unified diff");
}

/** 只接受 Java tracker 生成的 a/b 相对路径与 `/dev/null`，拒绝时间戳和路径逃逸。 */
function parseHeaderPath(line: string, side: "from" | "to"): string | null {
  const marker = side === "from" ? "--- " : "+++ ";
  if (!line.startsWith(marker)) return invalidDiff();
  const raw = line.slice(marker.length);
  if (raw === "/dev/null") return null;
  const prefix = side === "from" ? "a/" : "b/";
  if (!raw.startsWith(prefix)) return invalidDiff();
  const relative = raw.slice(prefix.length);
  if (!SAFE_RELATIVE_PATH.test(relative) || relative.includes("\t")) return invalidDiff();
  return relative;
}

/** 将可选 CRLF 规范化为 parser 输入；孤立 CR 会改变正文语义，因此直接拒绝。 */
function canonicalLines(unified: string): { readonly text: string; readonly lines: string[] } {
  if (unified.length === 0 || unified.includes("\0") || /\r(?!\n)/u.test(unified))
    return invalidDiff();
  const text = unified.replaceAll("\r\n", "\n");
  const lines = text.split("\n");
  if (lines.at(-1) === "") lines.pop();
  if (lines.length === 0) return invalidDiff();
  return { text, lines };
}

/**
 * 完整消费 unified diff grammar，并验证每个 hunk 的声明行数；任何未识别尾随行都失败关闭。
 * 可选 `diff --git` 只接受无空白相对路径，生产 Java 输出本身从 `---/+++` 开始。
 */
function validateUnifiedDiff(unified: string): {
  readonly text: string;
  readonly files: readonly ValidatedFile[];
} {
  const canonical = canonicalLines(unified);
  const files: ValidatedFile[] = [];
  let index = 0;
  while (index < canonical.lines.length) {
    let gitPaths: readonly [string, string] | undefined;
    const gitHeader = canonical.lines[index];
    if (gitHeader?.startsWith("diff --git ")) {
      const match = /^diff --git a\/(\S+) b\/(\S+)$/u.exec(gitHeader);
      if (match === null) return invalidDiff();
      gitPaths = [match[1]!, match[2]!];
      index += 1;
    }
    const from = parseHeaderPath(canonical.lines[index] ?? "", "from");
    index += 1;
    const to = parseHeaderPath(canonical.lines[index] ?? "", "to");
    index += 1;
    if (from === null && to === null) return invalidDiff();
    if (
      gitPaths !== undefined &&
      ((from !== null && gitPaths[0] !== from) || (to !== null && gitPaths[1] !== to))
    )
      return invalidDiff();

    const hunks: ValidatedHunk[] = [];
    let additions = 0;
    let deletions = 0;
    while (index < canonical.lines.length && canonical.lines[index]!.startsWith("@@ ")) {
      const match = HUNK_HEADER.exec(canonical.lines[index]!);
      if (match === null) return invalidDiff();
      const hunk: ValidatedHunk = {
        oldStart: Number(match[1]),
        oldLines: match[2] === undefined ? 1 : Number(match[2]),
        newStart: Number(match[3]),
        newLines: match[4] === undefined ? 1 : Number(match[4]),
      };
      index += 1;
      let oldLines = 0;
      let newLines = 0;
      let sawBody = false;
      let markerSide: " " | "+" | "-" | undefined;
      let changed = false;
      while (index < canonical.lines.length) {
        const line = canonical.lines[index]!;
        if (line === NO_NEWLINE_MARKER) {
          if (
            markerSide === undefined ||
            (markerSide === "-" && oldLines !== hunk.oldLines) ||
            (markerSide === "+" && newLines !== hunk.newLines) ||
            (markerSide === " " && (oldLines !== hunk.oldLines || newLines !== hunk.newLines))
          )
            return invalidDiff();
          markerSide = undefined;
          index += 1;
          continue;
        }
        if (oldLines === hunk.oldLines && newLines === hunk.newLines) break;
        const prefix = line[0];
        if (prefix !== " " && prefix !== "+" && prefix !== "-") return invalidDiff();
        sawBody = true;
        markerSide = prefix;
        if (prefix === " ") {
          oldLines += 1;
          newLines += 1;
        } else if (prefix === "+") {
          newLines += 1;
          additions += 1;
          changed = true;
        } else {
          oldLines += 1;
          deletions += 1;
          changed = true;
        }
        if (oldLines > hunk.oldLines || newLines > hunk.newLines) return invalidDiff();
        index += 1;
      }
      if (!sawBody || !changed || oldLines !== hunk.oldLines || newLines !== hunk.newLines)
        return invalidDiff();
      hunks.push(hunk);
    }
    if (hunks.length === 0) return invalidDiff();
    files.push({ from, to, additions, deletions, hunks });
  }
  return { text: canonical.text, files };
}

/** 标准化 parser 的 `/dev/null` 与可选路径，避免文件选择把新增/删除误配到哨兵路径。 */
function normalizedPath(path: string | undefined): string | null {
  if (path === undefined || path === "/dev/null") return null;
  return path;
}

/**
 * 严格解析受信 artifact 的 Unified Diff；EOF newline marker 已在 grammar 校验，但不会投影成
 * 重复的增删行，真正表达 newline 变化的正文行仍保留。
 */
export function parseTurnReviewDiff(unified: string): ParsedTurnReviewFile[] {
  const validated = validateUnifiedDiff(unified);
  const parsed = parseDiff(validated.text);
  if (parsed.length !== validated.files.length) return invalidDiff();
  return parsed.map((file, fileIndex) => {
    const expected = validated.files[fileIndex]!;
    const from = normalizedPath(file.from);
    const to = normalizedPath(file.to);
    const path = to ?? from;
    if (
      path === null ||
      from !== expected.from ||
      to !== expected.to ||
      file.additions !== expected.additions ||
      file.deletions !== expected.deletions ||
      file.chunks.length !== expected.hunks.length ||
      file.chunks.some((chunk, index) => {
        const hunk = expected.hunks[index]!;
        return (
          chunk.oldStart !== hunk.oldStart ||
          chunk.oldLines !== hunk.oldLines ||
          chunk.newStart !== hunk.newStart ||
          chunk.newLines !== hunk.newLines
        );
      })
    )
      return invalidDiff();
    const lines = file.chunks.flatMap((chunk) =>
      chunk.changes
        .filter((change) => change.content !== NO_NEWLINE_MARKER)
        .map((change): ParsedTurnReviewLine => {
          if (change.type === "add")
            return {
              kind: "addition",
              oldLine: null,
              newLine: change.ln,
              text: change.content.slice(1),
            };
          if (change.type === "del")
            return {
              kind: "deletion",
              oldLine: change.ln,
              newLine: null,
              text: change.content.slice(1),
            };
          return {
            kind: "context",
            oldLine: change.ln1,
            newLine: change.ln2,
            text: change.content.startsWith(" ") ? change.content.slice(1) : change.content,
          };
        }),
    );
    return {
      path,
      oldPath: from === path ? null : from,
      additions: file.additions,
      deletions: file.deletions,
      hunks: file.chunks.map((chunk) => ({
        header: chunk.content,
        oldStart: chunk.oldStart,
        oldLines: chunk.oldLines,
        newStart: chunk.newStart,
        newLines: chunk.newLines,
      })),
      lines,
    };
  });
}
