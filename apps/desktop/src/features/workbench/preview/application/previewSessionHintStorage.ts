// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

const PREVIEW_PAGE_HINT_PREFIX = "ja-preview-pages-v2:";
const PREVIEW_SESSION_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

/** application 只依赖恢复所需的三个语义操作，不获得浏览器 Storage 的通用读写能力。 */
export interface PreviewSessionHintStorage {
  read(workspaceId: string): readonly string[];
  remember(workspaceId: string, pageId: string): void;
  forget(workspaceId: string, expectedPageId?: string): void;
}

/** composition 注入最窄键值介质，使 application 与浏览器全局对象保持隔离。 */
export interface PreviewSessionHintMedia {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** Workspace identity 隔离浏览器页 ID 列表；页面事实仍须向 Rust 重新核验。 */
function hintKey(workspaceId: string): string {
  return `${PREVIEW_PAGE_HINT_PREFIX}${workspaceId}`;
}

export class MediaPreviewSessionHintStorage implements PreviewSessionHintStorage {
  /** 延迟取得介质，兼容 WebView 尚未初始化或隐私策略禁用存储的降级路径。 */
  constructor(private readonly media: () => PreviewSessionHintMedia | undefined) {}

  /** 只接受去重 UUID 列表；损坏介质视为没有恢复线索，不恢复本机路径或浏览权限。 */
  read(workspaceId: string): readonly string[] {
    try {
      const value = this.media()?.getItem(hintKey(workspaceId));
      if (value === null || value === undefined) return [];
      const parsed: unknown = JSON.parse(value);
      if (!Array.isArray(parsed)) return [];
      return [
        ...new Set(
          parsed.filter(
            (item): item is string =>
              typeof item === "string" && PREVIEW_SESSION_ID_PATTERN.test(item),
          ),
        ),
      ];
    } catch {
      return [];
    }
  }

  /** 页面 ID 只记录本机 tab 恢复线索；失败不影响 Rust 当前持有的原生 WebView。 */
  remember(workspaceId: string, pageId: string): void {
    if (!PREVIEW_SESSION_ID_PATTERN.test(pageId)) return;
    try {
      const media = this.media();
      if (media === undefined) return;
      const pageIds = this.read(workspaceId);
      media.setItem(
        hintKey(workspaceId),
        JSON.stringify([...pageIds.filter((id) => id !== pageId), pageId]),
      );
    } catch {
      // Rust 仍是 session owner；介质失败不升级为预览失败。
    }
  }

  /** 仅删除目标页面 hint，防止迟到 close 擦除稍后保留的其它浏览器页。 */
  forget(workspaceId: string, expectedPageId?: string): void {
    try {
      const media = this.media();
      if (media === undefined) return;
      const key = hintKey(workspaceId);
      if (expectedPageId === undefined) {
        media.removeItem(key);
        return;
      }
      const pageIds = this.read(workspaceId).filter((pageId) => pageId !== expectedPageId);
      if (pageIds.length === 0) media.removeItem(key);
      else media.setItem(key, JSON.stringify(pageIds));
    } catch {
      // 下一次读取仍会重新鉴权并淘汰无效 hint，不伪造本地清理成功。
    }
  }
}
