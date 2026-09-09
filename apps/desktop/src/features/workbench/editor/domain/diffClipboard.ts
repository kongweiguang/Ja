// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 复制保持双侧完整正文并明确标注，不把供人阅读的格式冒充可应用的 Unified Patch。 */
export function formatDiffClipboard(filePath: string, original: string, modified: string): string {
  return `文件：${filePath}\n\n--- 原始内容\n${original}\n\n+++ 修改后内容\n${modified}`;
}
