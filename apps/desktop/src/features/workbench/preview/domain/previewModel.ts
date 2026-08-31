// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { normalizePreviewUrl } from "@/shared/validation/previewUrl";

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
  | { kind: "reload" }
  | { kind: "invalid"; message: string };

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
  const normalized = normalizePreviewUrl(draft);
  if (normalized === undefined)
    return { kind: "invalid", message: "Preview 只支持 http:// 或 https:// 地址。" };
  return normalizePreviewUrl(currentUrl) === normalized
    ? { kind: "reload" }
    : { kind: "navigate", url: normalized };
}

/** 为视图生成安全 URL 投影；解析失败时不把原始输入放入 DOM data 属性。 */
export function projectPreviewUrl(value: string): PreviewUrlProjection | undefined {
  const normalized = normalizePreviewUrl(value);
  if (normalized === undefined) return undefined;
  const parsed = new URL(normalized);
  return { href: parsed.href, origin: parsed.origin };
}
