// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  actualSizeAttachmentZoom,
  attachmentPreviewTargetKey,
  ATTACHMENT_TEXT_CHUNK_BYTES,
  ATTACHMENT_TEXT_PREVIEW_LIMIT_BYTES,
  fitAttachmentZoom,
  stepAttachmentZoom,
  type AttachmentPreviewProjection,
} from "../domain/attachmentPreviewModel";
import type { AttachmentPreviewPort, AttachmentPreviewTarget } from "./ports";

const ATTACHMENT_PREVIEW_ERROR = "暂时无法打开附件预览";

export interface AttachmentPreviewControllerOptions {
  target?: AttachmentPreviewTarget;
  port?: AttachmentPreviewPort;
  onDismiss?: (target: AttachmentPreviewTarget) => void;
}

export interface AttachmentPreviewActions {
  dismiss: () => void;
  retry: () => void;
  zoomIn: () => void;
  zoomOut: () => void;
  fit: () => void;
  actualSize: () => void;
  reportImageFailure: () => void;
}

export interface AttachmentPreviewController {
  projection?: AttachmentPreviewProjection;
  actions: AttachmentPreviewActions;
}

/** 读取服务端授权的 UTF-8 分段；客户端仍设置 1 MiB 二次上限并拒绝停滞游标。 */
async function readTextPreview(
  port: AttachmentPreviewPort,
  previewSessionId: string,
): Promise<{ text: string; truncated: boolean }> {
  const chunks: string[] = [];
  let offsetBytes = 0;
  let consumedBytes = 0;
  while (offsetBytes < ATTACHMENT_TEXT_PREVIEW_LIMIT_BYTES) {
    const result = await port.read(
      previewSessionId,
      offsetBytes,
      Math.min(ATTACHMENT_TEXT_CHUNK_BYTES, ATTACHMENT_TEXT_PREVIEW_LIMIT_BYTES - offsetBytes),
    );
    const chunkBytes = new TextEncoder().encode(result.content).byteLength;
    consumedBytes += chunkBytes;
    if (
      result.previewSessionId !== previewSessionId ||
      result.offsetBytes !== offsetBytes ||
      result.nextOffsetBytes < offsetBytes ||
      consumedBytes > ATTACHMENT_TEXT_PREVIEW_LIMIT_BYTES ||
      (!result.endOfFile && !result.truncated && result.nextOffsetBytes === offsetBytes)
    ) {
      throw new Error(ATTACHMENT_PREVIEW_ERROR);
    }
    chunks.push(result.content);
    offsetBytes = result.nextOffsetBytes;
    if (result.endOfFile || result.truncated) {
      return { text: chunks.join(""), truncated: result.truncated };
    }
  }
  return { text: chunks.join(""), truncated: true };
}

/** 错误只消费 adapter 的稳定文案与 retryable，不读取 native cause。 */
function projectAttachmentError(error: unknown): { message: string; retryable: boolean } {
  if (
    error instanceof Error &&
    "retryable" in error &&
    typeof (error as { retryable?: unknown }).retryable === "boolean"
  ) {
    return {
      message: error.message || ATTACHMENT_PREVIEW_ERROR,
      retryable: (error as Error & { retryable: boolean }).retryable,
    };
  }
  return { message: ATTACHMENT_PREVIEW_ERROR, retryable: true };
}

/**
 * 独占一个附件 Preview session：target 切换、重试与卸载都会关闭旧 session，generation
 * 栅栏拒绝迟到 open/read 结果，网页 Preview 的 session 与地址状态不在此 hook 中重建。
 */
export function useAttachmentPreviewController({
  target,
  port,
  onDismiss,
}: AttachmentPreviewControllerOptions): AttachmentPreviewController {
  const [projection, setProjection] = useState<AttachmentPreviewProjection>();
  const [retryVersion, setRetryVersion] = useState(0);
  const generationRef = useRef(0);
  const sessionRef = useRef<string | undefined>(undefined);
  const targetRef = useRef(target);
  const portRef = useRef(port);
  targetRef.current = target;
  portRef.current = port;
  const targetKey = attachmentPreviewTargetKey(target);

  /** target identity 或 retry 变化时重开唯一 session，并在 text 场景内完成有界聚合。 */
  useEffect(() => {
    const currentTarget = targetRef.current;
    const generation = ++generationRef.current;
    const previousSession = sessionRef.current;
    if (currentTarget === undefined) {
      sessionRef.current = undefined;
      if (previousSession !== undefined) void port?.close(previousSession).catch(() => undefined);
      setProjection(undefined);
      return undefined;
    }
    if (port === undefined) {
      sessionRef.current = undefined;
      setProjection({
        status: "error",
        target: currentTarget,
        message: ATTACHMENT_PREVIEW_ERROR,
        retryable: false,
      });
      return undefined;
    }
    setProjection((current) =>
      current?.status === "ready" && current.target.attachmentId === currentTarget.attachmentId
        ? current
        : { status: "loading", target: currentTarget },
    );
    void (async (): Promise<void> => {
      let openedSession: string | undefined;
      try {
        const session = await port.open({
          attachmentId: currentTarget.attachmentId,
          authorization: currentTarget.authorization,
        });
        openedSession = session.previewSessionId;
        if (generationRef.current !== generation) {
          await port.close(session.previewSessionId).catch(() => undefined);
          return;
        }
        if (session.mediaKind === "image") {
          if (session.resourceUrl === undefined) throw new Error(ATTACHMENT_PREVIEW_ERROR);
          sessionRef.current = session.previewSessionId;
          setProjection({
            status: "ready",
            target: currentTarget,
            session,
            content: { kind: "image", resourceUrl: session.resourceUrl, zoom: fitAttachmentZoom() },
          });
          if (previousSession !== undefined && previousSession !== session.previewSessionId)
            await port.close(previousSession).catch(() => undefined);
          return;
        }
        const content = await readTextPreview(port, session.previewSessionId);
        if (generationRef.current !== generation) {
          await port.close(session.previewSessionId).catch(() => undefined);
          return;
        }
        sessionRef.current = session.previewSessionId;
        setProjection({
          status: "ready",
          target: currentTarget,
          session,
          content: { kind: "text", ...content },
        });
        if (previousSession !== undefined && previousSession !== session.previewSessionId)
          await port.close(previousSession).catch(() => undefined);
      } catch (error) {
        if (generationRef.current !== generation) {
          if (openedSession !== undefined) await port.close(openedSession).catch(() => undefined);
          return;
        }
        if (openedSession !== undefined) await port.close(openedSession).catch(() => undefined);
        if (previousSession !== undefined) await port.close(previousSession).catch(() => undefined);
        sessionRef.current = undefined;
        const failure = projectAttachmentError(error);
        setProjection({ status: "error", target: currentTarget, ...failure });
      }
    })();
    return () => {
      if (generationRef.current === generation) generationRef.current += 1;
    };
  }, [port, retryVersion, targetKey]);

  /** 组件最终卸载时释放最后一个 session；target 间切换由上方 handoff 保持无闪断。 */
  useEffect(
    () => () => {
      generationRef.current += 1;
      const sessionId = sessionRef.current;
      sessionRef.current = undefined;
      if (sessionId !== undefined) void portRef.current?.close(sessionId).catch(() => undefined);
    },
    [],
  );

  /** 返回网页前先使当前 generation 失效；close 失败也不能继续把附件资源显示在 DOM 中。 */
  const dismiss = useCallback((): void => {
    const currentTarget = targetRef.current;
    generationRef.current += 1;
    const sessionId = sessionRef.current;
    sessionRef.current = undefined;
    setProjection(undefined);
    if (sessionId !== undefined && port !== undefined)
      void port.close(sessionId).catch(() => undefined);
    if (currentTarget !== undefined) onDismiss?.(currentTarget);
  }, [onDismiss, port]);

  /** 重试只推进本地版本，effect 负责关闭旧 session 后重新授权。 */
  const retry = useCallback((): void => setRetryVersion((current) => current + 1), []);

  /** 图片缩放转换集中更新 ready/image 分支，文本与错误投影保持不变。 */
  const updateZoom = useCallback(
    (
      resolve: (
        current: Extract<AttachmentPreviewProjection, { status: "ready" }>["content"] & {
          kind: "image";
        },
      ) => ReturnType<typeof fitAttachmentZoom>,
    ): void => {
      setProjection((current) => {
        if (current?.status !== "ready" || current.content.kind !== "image") return current;
        return { ...current, content: { ...current.content, zoom: resolve(current.content) } };
      });
    },
    [],
  );

  /** 放大固定步进且不超过 400%。 */
  const zoomIn = useCallback(
    (): void => updateZoom((content) => stepAttachmentZoom(content.zoom, 1)),
    [updateZoom],
  );
  /** 缩小固定步进且不低于 25%。 */
  const zoomOut = useCallback(
    (): void => updateZoom((content) => stepAttachmentZoom(content.zoom, -1)),
    [updateZoom],
  );
  /** 适应窗口恢复响应式初态。 */
  const fit = useCallback((): void => updateZoom(() => fitAttachmentZoom()), [updateZoom]);
  /** 实际尺寸使用像素 1:1 的 100% 投影。 */
  const actualSize = useCallback(
    (): void => updateZoom(() => actualSizeAttachmentZoom()),
    [updateZoom],
  );

  /** 图片协议请求失败只替换右栏投影，不影响附件自身可发送状态。 */
  const reportImageFailure = useCallback((): void => {
    setProjection((current) =>
      current?.status === "ready"
        ? {
            status: "error",
            target: current.target,
            message: ATTACHMENT_PREVIEW_ERROR,
            retryable: true,
          }
        : current,
    );
  }, []);

  const actions = useMemo<AttachmentPreviewActions>(
    () => ({ dismiss, retry, zoomIn, zoomOut, fit, actualSize, reportImageFailure }),
    [actualSize, dismiss, fit, reportImageFailure, retry, zoomIn, zoomOut],
  );
  return { projection, actions };
}
