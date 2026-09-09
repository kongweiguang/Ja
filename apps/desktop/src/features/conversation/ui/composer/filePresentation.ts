// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 把扩展名保留为不可压缩尾部，避免附件与 Workspace 文件引用在窄 Composer 中失去类型识别。
 */
export function splitFileName(fileName: string): { stem: string; extension?: string } {
  const separator = fileName.lastIndexOf(".");
  if (separator <= 0 || separator === fileName.length - 1) return { stem: fileName };
  return { stem: fileName.slice(0, separator), extension: fileName.slice(separator) };
}
