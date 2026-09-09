// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ImageIcon, ImageOff } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type ReactElement, type RefObject } from "react";

export type HistoryAttachmentAuthorization =
  | { readonly kind: "draft" }
  | { readonly kind: "thread"; readonly threadId: string };

export interface HistoryAttachmentThumbnailPort {
  open(input: { attachmentId: string; authorization: HistoryAttachmentAuthorization }): Promise<{
    previewSessionId: string;
    attachmentId: string;
    mediaKind: "image" | "text";
    thumbnailUrl?: string;
  }>;
  close(previewSessionId: string): Promise<void>;
}

interface HistoryAttachmentThumbnailProps {
  readonly attachmentId: string;
  readonly displayName: string;
  readonly authorization: HistoryAttachmentAuthorization;
  readonly directUrl?: string;
  readonly port?: HistoryAttachmentThumbnailPort;
}

type ThumbnailProjection =
  | { readonly status: "placeholder" }
  | { readonly status: "loading" }
  | ThumbnailImageProjection
  | { readonly status: "unavailable" };

interface ThumbnailImageProjection {
  readonly status: "loading_image" | "ready";
  readonly generation: number;
  readonly url: string;
  readonly owned?: OwnedThumbnailSession;
}

interface OwnedThumbnailSession {
  readonly id: string;
  readonly port: HistoryAttachmentThumbnailPort;
}

/**
 * 没有 IntersectionObserver 时只接受真实落入 Timeline viewport 的节点，避免兼容回退把
 * Virtualizer 的 overscan 行全部升级为原生附件读取。
 */
function isInsideTimelineViewport(element: HTMLElement): boolean {
  const bounds = element.getBoundingClientRect();
  const viewport = element.closest<HTMLElement>(".ja-chat-timeline__scroll");
  const visibleBounds = viewport?.getBoundingClientRect() ?? {
    top: 0,
    right: window.innerWidth,
    bottom: window.innerHeight,
    left: 0,
  };
  return (
    bounds.width > 0 &&
    bounds.height > 0 &&
    visibleBounds.right > visibleBounds.left &&
    visibleBounds.bottom > visibleBounds.top &&
    bounds.bottom >= visibleBounds.top &&
    bounds.top <= visibleBounds.bottom &&
    bounds.right >= visibleBounds.left &&
    bounds.left <= visibleBounds.right
  );
}

/**
 * 缩略图只在图片行进入可见区后加载一次；IntersectionObserver 使用 Timeline 作为 root，
 * 因而窗口外但仍在虚拟 overscan 中的行不会触发原生 IO。
 */
function useVisibleThumbnailTarget(elementRef: RefObject<HTMLSpanElement | null>): boolean {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    const element = elementRef.current;
    if (element === null || visible) return undefined;
    if (globalThis.IntersectionObserver === undefined) {
      // 回退探测延后到可取消的回调，避免 effect 初始化同步触发级联渲染。
      const timeout = globalThis.setTimeout(() => {
        if (isInsideTimelineViewport(element)) setVisible(true);
      }, 0);
      return () => globalThis.clearTimeout(timeout);
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        setVisible(true);
        observer.disconnect();
      },
      { root: element.closest(".ja-chat-timeline__scroll"), rootMargin: "0px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [elementRef, visible]);
  return visible;
}

/**
 * 历史缩略图独占最小 Preview session：可见时按 draft/thread 身份授权，图片失败、目标变化
 * 或卸载都关闭 session；directUrl 由附件导入端口拥有，不在此重复开原生资源。
 */
export function HistoryAttachmentThumbnail({
  attachmentId,
  displayName,
  authorization,
  directUrl,
  port,
}: HistoryAttachmentThumbnailProps): ReactElement {
  const elementRef = useRef<HTMLSpanElement>(null);
  const ownedSessionRef = useRef<OwnedThumbnailSession | undefined>(undefined);
  const imageGenerationRef = useRef(0);
  const currentImageRef = useRef<ThumbnailImageProjection | undefined>(undefined);
  const visible = useVisibleThumbnailTarget(elementRef);
  const [projection, setProjection] = useState<ThumbnailProjection>(() =>
    directUrl === undefined
      ? { status: "placeholder" }
      : { status: "loading_image", generation: 0, url: directUrl },
  );
  const threadId = authorization.kind === "thread" ? authorization.threadId : undefined;

  /** 只关闭当前组件仍持有的 session，避免迟到 cleanup 误关后续重开的资源。 */
  const releaseOwnedSession = useCallback((owned: OwnedThumbnailSession | undefined): void => {
    const current = ownedSessionRef.current;
    if (owned === undefined || current !== owned) return;
    ownedSessionRef.current = undefined;
    void owned.port.close(owned.id).catch(() => undefined);
  }, []);

  /** 每个图片来源分配单调 generation；即使 URL 相同，React 也不会复用旧事件目标。 */
  const installImage = useCallback(
    (url: string, owned?: OwnedThumbnailSession): ThumbnailImageProjection => {
      imageGenerationRef.current += 1;
      const target: ThumbnailImageProjection = {
        status: "loading_image",
        generation: imageGenerationRef.current,
        url,
        ...(owned === undefined ? {} : { owned }),
      };
      currentImageRef.current = target;
      setProjection(target);
      return target;
    },
    [],
  );

  /** directUrl 与授权目标变化时重建唯一图片来源，所有迟到 open 结果都会立即关闭。 */
  useEffect(() => {
    if (directUrl !== undefined) {
      const target = installImage(directUrl);
      return () => {
        if (currentImageRef.current === target) currentImageRef.current = undefined;
      };
    }
    if (!visible || port === undefined) return undefined;
    let active = true;
    let owned: OwnedThumbnailSession | undefined;
    currentImageRef.current = undefined;
    // loading 与异步预览统一按 active 身份提交，卸载后不能再更新投影。
    globalThis.queueMicrotask(() => {
      if (active) setProjection({ status: "loading" });
    });
    void port
      .open({
        attachmentId,
        authorization:
          authorization.kind === "draft"
            ? { kind: "draft" }
            : { kind: "thread", threadId: threadId! },
      })
      .then((session) => {
        if (!active) {
          void port.close(session.previewSessionId).catch(() => undefined);
          return;
        }
        if (
          session.attachmentId !== attachmentId ||
          session.mediaKind !== "image" ||
          session.thumbnailUrl === undefined
        ) {
          void port.close(session.previewSessionId).catch(() => undefined);
          setProjection({ status: "unavailable" });
          return;
        }
        owned = { id: session.previewSessionId, port };
        ownedSessionRef.current = owned;
        installImage(session.thumbnailUrl, owned);
      })
      .catch(() => {
        if (active) setProjection({ status: "unavailable" });
      });
    return () => {
      active = false;
      if (currentImageRef.current?.owned === owned) currentImageRef.current = undefined;
      releaseOwnedSession(owned);
    };
  }, [
    attachmentId,
    authorization.kind,
    directUrl,
    installImage,
    port,
    releaseOwnedSession,
    threadId,
    visible,
  ]);

  /** 旧图片事件必须同时匹配 target identity、generation 与 URL，不能污染或关闭新 session。 */
  const isCurrentImage = useCallback((target: ThumbnailImageProjection): boolean => {
    const current = currentImageRef.current;
    return (
      current === target && current.generation === target.generation && current.url === target.url
    );
  }, []);

  /** 图片协议读取失败只释放事件所属 target 的 session，并留下明确降级图标。 */
  const reportImageFailure = useCallback(
    (target: ThumbnailImageProjection): void => {
      if (!isCurrentImage(target)) return;
      currentImageRef.current = undefined;
      releaseOwnedSession(target.owned);
      setProjection({ status: "unavailable" });
    },
    [isCurrentImage, releaseOwnedSession],
  );

  /** 图片成功事件同样受 target fence 保护，旧 load 不得把新来源提前标记为 ready。 */
  const reportImageReady = useCallback(
    (target: ThumbnailImageProjection): void => {
      if (!isCurrentImage(target)) return;
      const ready: ThumbnailImageProjection = { ...target, status: "ready" };
      currentImageRef.current = ready;
      setProjection(ready);
    },
    [isCurrentImage],
  );

  return (
    <span
      ref={elementRef}
      className="ja-chat-attachment__thumbnail"
      data-state={projection.status}
      {...(projection.status === "unavailable"
        ? { role: "img", "aria-label": `${displayName} 缩略图不可用` }
        : { "aria-hidden": true })}
    >
      {projection.status === "loading_image" || projection.status === "ready" ? (
        <img
          key={`${projection.generation}:${projection.url}`}
          src={projection.url}
          alt=""
          draggable={false}
          onLoad={() => reportImageReady(projection)}
          onError={() => reportImageFailure(projection)}
        />
      ) : projection.status === "unavailable" ? (
        <ImageOff aria-hidden="true" />
      ) : (
        <ImageIcon aria-hidden="true" />
      )}
    </span>
  );
}
