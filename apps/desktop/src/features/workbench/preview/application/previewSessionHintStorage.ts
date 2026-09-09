// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

const PREVIEW_SESSION_HINT_PREFIX = "ja-preview-session-v1:";
const PREVIEW_SESSION_ID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu;

/** application 只依赖恢复所需的三个语义操作，不获得浏览器 Storage 的通用读写能力。 */
export interface PreviewSessionHintStorage {
  read(workspaceId: string): string | undefined;
  remember(workspaceId: string, sessionId: string): void;
  forget(workspaceId: string, expectedSessionId?: string): void;
}

/** composition 注入最窄键值介质，使 application 与浏览器全局对象保持隔离。 */
export interface PreviewSessionHintMedia {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

/** Workspace identity 只用于隔离当前 WebView hint；真实存在性与授权仍由 Rust 每次重新判定。 */
function hintKey(workspaceId: string): string {
  return `${PREVIEW_SESSION_HINT_PREFIX}${workspaceId}`;
}

export class MediaPreviewSessionHintStorage implements PreviewSessionHintStorage {
  /** 延迟取得介质，兼容 WebView 尚未初始化或隐私策略禁用存储的降级路径。 */
  constructor(private readonly media: () => PreviewSessionHintMedia | undefined) {}

  /** 只接受 UUID 形状的 opaque hint，损坏或不可读介质等价于没有恢复线索。 */
  read(workspaceId: string): string | undefined {
    try {
      const value = this.media()?.getItem(hintKey(workspaceId));
      return value !== null && value !== undefined && PREVIEW_SESSION_ID_PATTERN.test(value)
        ? value
        : undefined;
    } catch {
      return undefined;
    }
  }

  /** 写失败只失去 reload 连续性，不能影响已由 Rust 持有的当前 Preview session。 */
  remember(workspaceId: string, sessionId: string): void {
    if (!PREVIEW_SESSION_ID_PATTERN.test(sessionId)) return;
    try {
      this.media()?.setItem(hintKey(workspaceId), sessionId);
    } catch {
      // Rust 仍是 session owner；介质失败不升级为预览失败。
    }
  }

  /** 仅删除仍指向预期 session 的 hint，防止迟到 close 擦除随后打开的新 session。 */
  forget(workspaceId: string, expectedSessionId?: string): void {
    try {
      const media = this.media();
      if (media === undefined) return;
      const key = hintKey(workspaceId);
      if (expectedSessionId === undefined || media.getItem(key) === expectedSessionId)
        media.removeItem(key);
    } catch {
      // 下一次读取仍会重新鉴权并淘汰无效 hint，不伪造本地清理成功。
    }
  }
}
