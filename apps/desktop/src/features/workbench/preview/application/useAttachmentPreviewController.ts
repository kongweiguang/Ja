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
  /** ACK 后只关闭授权 Thread 属于服务端关闭集合的当前附件 session。 */
  closeForThreads: (threadIds: readonly string[]) => Promise<void>;
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
  const sessionTargetKeyRef = useRef<string | undefined>(undefined);
  const cleanupFailureRef = useRef<
    { readonly sessionId: string; readonly target: AttachmentPreviewTarget } | undefined
  >(undefined);
  const closingSessionRef = useRef<
    { readonly sessionId: string; readonly promise: Promise<boolean> } | undefined
  >(undefined);
  const openingRef = useRef<
    { readonly targetKey: string; readonly promise: Promise<void> } | undefined
  >(undefined);
  const targetRef = useRef(target);
  const portRef = useRef(port);
  targetRef.current = target;
  portRef.current = port;
  const targetKey = attachmentPreviewTargetKey(target);

  /** sessionId 只由当前 owner 关闭；失败记录保留身份，供现有预览错误动作重试。 */
  const closeOwnedSession = useCallback(
    (sessionId: string, ownerTarget: AttachmentPreviewTarget): Promise<boolean> => {
      const pending = closingSessionRef.current;
      if (pending?.sessionId === sessionId) return pending.promise;
      const operation = (async (): Promise<boolean> => {
        const currentPort = portRef.current;
        if (currentPort === undefined) {
          cleanupFailureRef.current = { sessionId, target: ownerTarget };
          return false;
        }
        try {
          await currentPort.close(sessionId);
          if (sessionRef.current === sessionId) {
            sessionRef.current = undefined;
            sessionTargetKeyRef.current = undefined;
          }
          if (cleanupFailureRef.current?.sessionId === sessionId)
            cleanupFailureRef.current = undefined;
          return true;
        } catch {
          cleanupFailureRef.current = { sessionId, target: ownerTarget };
          return false;
        }
      })();
      const close = { sessionId, promise: operation };
      closingSessionRef.current = close;
      void operation.then(() => {
        if (closingSessionRef.current === close) closingSessionRef.current = undefined;
      });
      return operation;
    },
    [],
  );

  /** 等当前 target 的 open/read 收敛，再确认关闭 session；失败时保留 target 供重试。 */
  const closeTarget = useCallback(
    async (currentTarget: AttachmentPreviewTarget): Promise<boolean> => {
      const key = attachmentPreviewTargetKey(currentTarget);
      const generation = ++generationRef.current;
      const opening = openingRef.current;
      if (opening?.targetKey === key) await opening.promise;
      if (
        generationRef.current !== generation ||
        attachmentPreviewTargetKey(targetRef.current) !== key
      )
        return false;

      const failedSession = cleanupFailureRef.current;
      if (failedSession !== undefined && attachmentPreviewTargetKey(failedSession.target) === key) {
        setProjection({
          status: "error",
          target: currentTarget,
          message: "预览资源清理失败，请重试。",
          retryable: true,
        });
        return false;
      }

      const sessionId = sessionTargetKeyRef.current === key ? sessionRef.current : undefined;
      if (sessionId !== undefined && !(await closeOwnedSession(sessionId, currentTarget))) {
        setProjection({
          status: "error",
          target: currentTarget,
          message: "预览资源清理失败，请重试。",
          retryable: true,
        });
        return false;
      }
      if (
        generationRef.current !== generation ||
        attachmentPreviewTargetKey(targetRef.current) !== key
      )
        return false;
      setProjection(undefined);
      onDismiss?.(currentTarget);
      return true;
    },
    [closeOwnedSession, onDismiss],
  );

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
    const opening = (async (): Promise<void> => {
      let openedSession: string | undefined;
      try {
        const session = await port.open({
          attachmentId: currentTarget.attachmentId,
          authorization: currentTarget.authorization,
        });
        openedSession = session.previewSessionId;
        if (generationRef.current !== generation) {
          await closeOwnedSession(session.previewSessionId, currentTarget);
          return;
        }
        if (session.mediaKind === "image") {
          if (session.resourceUrl === undefined) throw new Error(ATTACHMENT_PREVIEW_ERROR);
          sessionRef.current = session.previewSessionId;
          sessionTargetKeyRef.current = attachmentPreviewTargetKey(currentTarget);
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
          await closeOwnedSession(session.previewSessionId, currentTarget);
          return;
        }
        sessionRef.current = session.previewSessionId;
        sessionTargetKeyRef.current = attachmentPreviewTargetKey(currentTarget);
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
          if (openedSession !== undefined) await closeOwnedSession(openedSession, currentTarget);
          return;
        }
        if (openedSession !== undefined) {
          const closed = await closeOwnedSession(openedSession, currentTarget);
          if (!closed) {
            setProjection({
              status: "error",
              target: currentTarget,
              message: "预览资源清理失败，请重试。",
              retryable: true,
            });
            return;
          }
        }
        if (previousSession !== undefined) await port.close(previousSession).catch(() => undefined);
        if (sessionRef.current === openedSession) {
          sessionRef.current = undefined;
          sessionTargetKeyRef.current = undefined;
        }
        const failure = projectAttachmentError(error);
        setProjection({ status: "error", target: currentTarget, ...failure });
      }
    })();
    const openOperation = {
      targetKey: attachmentPreviewTargetKey(currentTarget),
      promise: opening,
    };
    openingRef.current = openOperation;
    void opening.then(() => {
      if (openingRef.current === openOperation) openingRef.current = undefined;
    });
    return () => {
      if (generationRef.current === generation) generationRef.current += 1;
    };
  }, [closeOwnedSession, port, retryVersion, targetKey]);

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

  /** 返回网页前先确认 native close；关闭失败则保留可重试投影，不丢 session identity。 */
  const dismiss = useCallback((): void => {
    const currentTarget = targetRef.current;
    if (currentTarget !== undefined) void closeTarget(currentTarget);
  }, [closeTarget]);

  /** 关闭回执包含完整 Thread 范围；不匹配的父 Thread 附件和其它 Host 资源不受影响。 */
  const closeForThreads = useCallback(
    async (threadIds: readonly string[]): Promise<void> => {
      const currentTarget = targetRef.current;
      if (
        currentTarget?.authorization.kind !== "thread" ||
        !threadIds.includes(currentTarget.authorization.threadId)
      )
        return;
      await closeTarget(currentTarget);
    },
    [closeTarget],
  );

  /** 重试只推进本地版本，effect 负责关闭旧 session 后重新授权。 */
  const retry = useCallback((): void => {
    const currentTarget = targetRef.current;
    const failedSession = cleanupFailureRef.current;
    if (
      failedSession !== undefined &&
      currentTarget !== undefined &&
      attachmentPreviewTargetKey(failedSession.target) === attachmentPreviewTargetKey(currentTarget)
    ) {
      const generation = ++generationRef.current;
      void closeOwnedSession(failedSession.sessionId, failedSession.target).then((closed) => {
        if (!closed) {
          setProjection({
            status: "error",
            target: failedSession.target,
            message: "预览资源清理失败，请重试。",
            retryable: true,
          });
          return;
        }
        if (
          generationRef.current === generation &&
          attachmentPreviewTargetKey(targetRef.current) ===
            attachmentPreviewTargetKey(failedSession.target)
        ) {
          setProjection(undefined);
          onDismiss?.(failedSession.target);
        }
      });
      return;
    }
    setRetryVersion((current) => current + 1);
  }, [closeOwnedSession, onDismiss]);

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

  /** 图片加载失败不改写 native 签发 origin；用户可走既有显式重试或关闭路径。 */
  const reportImageFailure = useCallback((): void => {
    setProjection((current) => {
      if (current?.status !== "ready" || current.content.kind !== "image") return current;
      return {
        status: "error",
        target: current.target,
        message: ATTACHMENT_PREVIEW_ERROR,
        retryable: true,
      };
    });
  }, []);

  const actions = useMemo<AttachmentPreviewActions>(
    () => ({
      dismiss,
      retry,
      closeForThreads,
      zoomIn,
      zoomOut,
      fit,
      actualSize,
      reportImageFailure,
    }),
    [actualSize, closeForThreads, dismiss, fit, reportImageFailure, retry, zoomIn, zoomOut],
  );
  return { projection, actions };
}
