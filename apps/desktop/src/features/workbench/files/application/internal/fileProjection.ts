// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { FileReadDto, FileSearchHit } from "../ports";
import type { FilesSearchResult, OpenDocument } from "../types";
import { detectNewlineStyle } from "../../domain/filesModel";
import type { FileRevision } from "../../domain/types";

/** 将 runtime 搜索结果收窄成面板投影，不把 native DTO 继续向 UI 扩散。 */
export function mapSearchHits(hits: readonly FileSearchHit[]): FilesSearchResult[] {
  return hits.map((hit) => ({
    id: hit.id,
    path: hit.path,
    line: hit.line,
    column: hit.column,
    preview: hit.preview,
    matchStart: hit.matchStart,
    matchLength: hit.matchLength,
  }));
}

/** 比较完整 native CAS 身份，避免 Watcher 回声掩盖真实并发写入。 */
export function revisionsEqual(
  left: FileRevision | null | undefined,
  right: FileRevision | null | undefined,
): boolean {
  return (
    left !== null &&
    left !== undefined &&
    right !== null &&
    right !== undefined &&
    left.kind === right.kind &&
    left.size === right.size &&
    left.modifiedUnixMillis === right.modifiedUnixMillis &&
    left.sha256 === right.sha256
  );
}

/**
 * 把读取 DTO 投影为安全编辑状态；编码或换行不确定时 fail-closed 为只读，
 * 不由 controller 或 UI 猜测可能破坏原文件字节的保存参数。
 */
export function documentFromRead(
  result: FileReadDto,
  reveal?: { line: number; column?: number },
): OpenDocument {
  const content = result.content ?? "";
  const encoding = result.encoding ?? "utf8";
  const newline = result.newline ?? detectNewlineStyle(content);
  const supportedText = result.kind === "text" && result.content !== null;
  const safeEncoding = result.encoding !== null && result.encoding !== undefined;
  const safeNewline = newline !== "mixed" && newline !== "unknown";
  const readOnly = !supportedText || !safeEncoding || !safeNewline;
  const readOnlyReason =
    result.readOnlyReason ??
    (!supportedText
      ? result.kind === "binary"
        ? "二进制文件，只读"
        : "文件编码或大小不支持编辑"
      : !safeEncoding
        ? "文件编码未知，只读"
        : !safeNewline
          ? "混合或未知换行，只读"
          : undefined);
  return {
    path: result.path,
    content,
    savedContent: content,
    revision: result.revision,
    encoding,
    newline,
    kind: result.kind,
    readOnly,
    readOnlyReason,
    status: "clean",
    draftGeneration: 0,
    reveal,
  };
}
