// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  containsControlCharacters,
  normalizePreviewUrl,
  normalizePreviewWebUrl,
} from "@/shared/validation/previewUrl";

export interface PreviewAddressState {
  draft: string;
  validationError?: string;
}

export type PreviewAddressAction =
  | { type: "sync"; url: string }
  | { type: "change"; draft: string }
  | { type: "validation"; message?: string };

export type PreviewNavigationIntent =
  | { kind: "navigate"; url: string }
  | { kind: "open_file"; path: string }
  | { kind: "reload" }
  | { kind: "invalid"; message: string };

/** 对外只传用户明确选中的地址或文件目标；路径解析和文件权限始终由 Rust 复核。 */
export type PreviewTarget =
  | { kind: "url"; url: string }
  | { kind: "file"; path: string; line?: number; column?: number };

/** Rust 已显式解析的本机目标投影；这只允许在用户点击后取得，不构成文件能力 token。 */
export interface PreviewFileResolution {
  canonicalPath: string;
  displayName: string;
  workspaceId: string | null;
  workspaceRelativePath: string | null;
  withinWorkspace: boolean;
  kind: "text" | "browser" | "unsupported";
  mimeType: string | null;
  fileUrl: string;
  content: string | null;
  truncated: boolean;
  line: number | null;
  column: number | null;
  readOnly: boolean;
}

export interface PreviewUrlProjection {
  href: string;
  origin: string;
}

/** 创建地址栏初态；draft 属于 UI 草稿，不能反向成为 native 的权威 URL。 */
export function createPreviewAddressState(url: string): PreviewAddressState {
  return { draft: url };
}

/** 纯 reducer 只处理地址栏状态，跨进程导航仍由 application port 编排。 */
export function reducePreviewAddress(
  state: PreviewAddressState,
  action: PreviewAddressAction,
): PreviewAddressState {
  switch (action.type) {
    case "sync":
      return { draft: action.url };
    case "change":
      return { ...state, draft: action.draft };
    case "validation":
      return { ...state, validationError: action.message };
  }
}

/** 从草稿派生封闭导航意图，确保 UI 与 native adapter 共享 HTTP(S) 约束。 */
export function resolvePreviewNavigation(
  draft: string,
  currentUrl: string,
): PreviewNavigationIntent {
  if (draft.trim().toLowerCase() === "about:blank")
    return currentUrl === "about:blank"
      ? { kind: "reload" }
      : { kind: "invalid", message: "请使用“新建浏览器标签”打开空白页。" };
  const trimmed = draft.trim();
  const normalized = normalizePreviewUrl(trimmed);
  if (normalized === undefined && isWorkspaceRelativePath(trimmed))
    return { kind: "open_file", path: trimmed };
  if (normalized === undefined)
    return { kind: "invalid", message: "请输入有效的 http(s) 地址或本机文件路径。" };
  const webUrl = normalizePreviewWebUrl(normalized);
  if (webUrl === undefined) {
    return normalizePreviewUrl(currentUrl) === normalized
      ? { kind: "reload" }
      : { kind: "open_file", path: normalized };
  }
  return normalizePreviewWebUrl(currentUrl) === webUrl
    ? { kind: "reload" }
    : { kind: "navigate", url: webUrl };
}

/** 裸相对路径只能交给 Rust 按当前 workspace 解析，明确协议与 UNC 不走该分支。 */
function isWorkspaceRelativePath(value: string): boolean {
  return (
    value.length > 0 &&
    value.length <= 4_096 &&
    !containsControlCharacters(value) &&
    !/^[a-z][a-z\d+.-]*:/iu.test(value) &&
    !value.startsWith("//") &&
    !value.startsWith("\\\\")
  );
}

/** 为视图生成安全 URL 投影；解析失败时不把原始输入放入 DOM data 属性。 */
export function projectPreviewUrl(value: string): PreviewUrlProjection | undefined {
  if (value === "about:blank") return { href: value, origin: "新标签页" };
  const normalized = normalizePreviewUrl(value);
  if (normalized === undefined) return undefined;
  const parsed = new URL(normalized);
  return {
    href: parsed.href,
    origin: parsed.protocol === "file:" ? "本机文件" : parsed.origin,
  };
}
