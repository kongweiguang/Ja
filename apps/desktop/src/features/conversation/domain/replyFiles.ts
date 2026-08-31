// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { itemChangedPaths, type TimelineItemAdapter } from "./timelineTypes";

/**
 * 镜像 native containment 的最小相对路径语法；这里只做 UI admission，Rust 仍会在打开前
 * 执行唯一权威校验，因此 domain 不依赖 Tauri Schema，也不冒充安全边界 owner。
 */
function isSafeReplyRelativePath(value: string): boolean {
  if (
    value.length === 0 ||
    value.length > 4_096 ||
    value.includes("\u0000") ||
    value.includes("\\") ||
    value.includes(":")
  )
    return false;
  if (value.startsWith("/")) return false;
  return value.split("/").every((component) => component !== "." && component !== "..");
}

/**
 * 只返回与最近 User Turn 关联且通过相对路径 admission 的文件；在最后一条 User Message
 * 处停止，避免旧 Reply 文件看似属于当前 Response。
 */
export function latestReplyFilePaths(items: readonly TimelineItemAdapter[]): readonly string[] {
  let turnStart = items.length - 1;
  while (turnStart >= 0 && items[turnStart]?.kind !== "user_message") turnStart -= 1;
  if (turnStart < 0) return [];

  const paths: string[] = [];
  const seen = new Set<string>();
  for (let index = turnStart + 1; index < items.length; index += 1) {
    for (const path of itemChangedPaths(items[index]!)) {
      if (!seen.has(path) && isSafeReplyRelativePath(path)) {
        seen.add(path);
        paths.push(path);
      }
    }
  }
  return paths;
}
