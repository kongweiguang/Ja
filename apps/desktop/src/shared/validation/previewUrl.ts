// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/**
 * 使用平台 URL parser 规范化，并在非 Web scheme 到达 Tauri command 或子 WebView 前全部拒绝。
 */
export function normalizePreviewUrl(value: string): string | undefined {
  const trimmed = value.trim();
  if (trimmed.length === 0) return undefined;
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return undefined;
    return parsed.href;
  } catch {
    return undefined;
  }
}
