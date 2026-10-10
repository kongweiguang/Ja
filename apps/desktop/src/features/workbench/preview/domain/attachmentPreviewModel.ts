// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export const ATTACHMENT_TEXT_PREVIEW_LIMIT_BYTES = 1024 * 1024;
export const ATTACHMENT_TEXT_CHUNK_BYTES = 64 * 1024;
export const ATTACHMENT_ZOOM_MIN = 25;
export const ATTACHMENT_ZOOM_MAX = 400;
export const ATTACHMENT_ZOOM_STEP = 25;

export type AttachmentImageZoom =
  | { mode: "fit"; percent: 100 }
  | { mode: "scale"; percent: number };

export type AttachmentPreviewAuthorization =
  | { kind: "draft" }
  | { kind: "thread"; threadId: string };

/** 预览目标是纯业务 identity；domain 不接触 Tauri URL、路径或 application port。 */
export interface AttachmentPreviewTarget {
  attachmentId: string;
  displayName: string;
  mediaKind: "image" | "text";
  authorization: AttachmentPreviewAuthorization;
}

/** 已授权 session 的领域投影只包含 UI 所需元数据与受控资源 URL。 */
export interface AttachmentPreviewSession {
  previewSessionId: string;
  attachmentId: string;
  displayName: string;
  sizeBytes: number;
  mediaKind: "image" | "text";
  mediaType?: string;
  resourceUrl?: string;
  thumbnailUrl?: string;
}

export type AttachmentPreviewProjection =
  | { status: "loading"; target: AttachmentPreviewTarget }
  | {
      status: "ready";
      target: AttachmentPreviewTarget;
      session: AttachmentPreviewSession;
      content:
        | { kind: "image"; resourceUrl: string; zoom: AttachmentImageZoom }
        | { kind: "text"; text: string; truncated: boolean };
    }
  | {
      status: "error";
      target: AttachmentPreviewTarget;
      message: string;
      retryable: boolean;
    };

/** target key 只组合业务 identity，禁止把授权内容序列化进 DOM 或日志。 */
export function attachmentPreviewTargetKey(target: AttachmentPreviewTarget | undefined): string {
  if (target === undefined) return "web";
  const scope =
    target.authorization.kind === "draft" ? "draft" : `thread:${target.authorization.threadId}`;
  return `${scope}:${target.attachmentId}`;
}

/** 缩放步进保持 25% 边界，fit 转固定缩放时从 100% 开始。 */
export function stepAttachmentZoom(
  zoom: AttachmentImageZoom,
  direction: -1 | 1,
): AttachmentImageZoom {
  const current = zoom.mode === "fit" ? 100 : zoom.percent;
  return {
    mode: "scale",
    percent: Math.min(
      ATTACHMENT_ZOOM_MAX,
      Math.max(ATTACHMENT_ZOOM_MIN, current + direction * ATTACHMENT_ZOOM_STEP),
    ),
  };
}

/** 实际尺寸固定映射为 100%，与适应窗口保持两个可逆的显式模式。 */
export function actualSizeAttachmentZoom(): AttachmentImageZoom {
  return { mode: "scale", percent: 100 };
}

/** 新图片默认适应面板，窄窗和 200% 缩放时不会先产生双向溢出。 */
export function fitAttachmentZoom(): AttachmentImageZoom {
  return { mode: "fit", percent: 100 };
}
