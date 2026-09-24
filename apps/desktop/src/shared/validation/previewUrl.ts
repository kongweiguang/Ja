// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 以字符码判断危险控制字符，避免把换行等控制符带入 URL 或本机路径。 */
export function containsControlCharacters(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0);
    if (codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f)) return true;
  }
  return false;
}

/**
 * 规范化 Web 地址或可交由 Rust 解析的本机文件引用；文件 URI 不表示路径已存在或获授权。
 */
export function normalizePreviewUrl(value: string): string | undefined {
  const trimmed = value.trim();
  if (
    trimmed.length === 0 ||
    containsControlCharacters(trimmed) ||
    (/^(?:https?|file):/iu.test(trimmed) && trimmed.includes("\\"))
  )
    return undefined;
  try {
    const parsed = new URL(trimmed);
    if (parsed.protocol === "http:" || parsed.protocol === "https:") return parsed.href;
    if (
      parsed.protocol === "file:" &&
      parsed.pathname.startsWith("/") &&
      parsed.username.length === 0 &&
      parsed.password.length === 0
    )
      return parsed.href;
  } catch {
    // Windows 路径和 UNC 路径不是有效的 Web URL；后续由 Rust 验证真实路径及权限。
  }
  if (/^[a-z]:[\\/]/iu.test(trimmed)) {
    try {
      return new URL(`file:///${trimmed.replaceAll("\\", "/")}`).href;
    } catch {
      return undefined;
    }
  }
  if (trimmed.startsWith("\\\\")) {
    const [host, ...parts] = trimmed.slice(2).split(/[\\/]+/u);
    if (host === undefined || host.length === 0 || parts.length === 0) return undefined;
    try {
      return new URL(`file://${host}/${parts.map(encodeURIComponent).join("/")}`).href;
    } catch {
      return undefined;
    }
  }
  return undefined;
}

/** 子 WebView 的普通 navigate 仍限 HTTP(S)；file URI 只能经 Rust 文件解析/open 命令进入。 */
export function normalizePreviewWebUrl(value: string): string | undefined {
  const normalized = normalizePreviewUrl(value);
  if (normalized === undefined) return undefined;
  try {
    const parsed = new URL(normalized);
    return parsed.protocol === "http:" || parsed.protocol === "https:" ? parsed.href : undefined;
  } catch {
    return undefined;
  }
}
