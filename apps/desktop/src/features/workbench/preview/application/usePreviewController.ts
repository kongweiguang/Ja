// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useReducer } from "react";
import {
  createPreviewAddressState,
  projectPreviewUrl,
  reducePreviewAddress,
  resolvePreviewNavigation,
  type PreviewUrlProjection,
} from "../domain/previewModel";
import type { PreviewTarget } from "../domain/previewModel";
import type { AttachmentPreviewProjection } from "../domain/attachmentPreviewModel";
import type {
  AttachmentPreviewPort,
  AttachmentPreviewTarget,
  PreviewPageProjection,
  PreviewPort,
  PreviewViewport,
} from "./ports";
import {
  useAttachmentPreviewController,
  type AttachmentPreviewActions,
} from "./useAttachmentPreviewController";

export interface PreviewControllerOptions {
  url: string;
  loading: boolean;
  recovering: boolean;
  error?: string;
  active: boolean;
  port: PreviewPort;
  pages?: readonly PreviewPageProjection[];
  activePageId?: string;
  canGoBack?: boolean;
  canGoForward?: boolean;
  attachmentTarget?: AttachmentPreviewTarget;
  attachmentPort?: AttachmentPreviewPort;
  onDismissAttachment?: (target: AttachmentPreviewTarget) => void;
}

export interface PreviewViewModel {
  url: string;
  draft: string;
  validationError?: string;
  projection?: PreviewUrlProjection;
  loading: boolean;
  recovering: boolean;
  error?: string;
  active: boolean;
  pages: readonly PreviewPageProjection[];
  activePageId?: string;
  canGoBack: boolean;
  canGoForward: boolean;
  canRetryRecovery: boolean;
  canReportViewport: boolean;
  mode: "web" | "attachment";
  attachment?: AttachmentPreviewProjection;
}

export interface PreviewActions {
  openTarget: (target: PreviewTarget) => Promise<void>;
  newPage: () => Promise<void>;
  selectPage: (pageId: string) => void;
  closePage: (pageId: string) => Promise<void>;
  goBack: () => void;
  goForward: () => void;
  changeDraft: (draft: string) => void;
  submit: () => void;
  retryRecovery: () => void;
  changeViewport: (viewport: PreviewViewport) => void;
  attachment: AttachmentPreviewActions;
}

export interface PreviewController {
  viewModel: PreviewViewModel;
  actions: PreviewActions;
}

/** 统一拥有地址草稿与目标路由；链接解析结果仍由注入的 native/application 边界复核。 */
export function usePreviewController({
  url,
  loading,
  recovering,
  error,
  active,
  port,
  pages = [],
  activePageId,
  canGoBack = false,
  canGoForward = false,
  attachmentTarget,
  attachmentPort,
  onDismissAttachment,
}: PreviewControllerOptions): PreviewController {
  const [address, dispatch] = useReducer(reducePreviewAddress, url, createPreviewAddressState);
  const attachment = useAttachmentPreviewController({
    target: attachmentTarget,
    port: attachmentPort,
    onDismiss: onDismissAttachment,
  });
  const mode = attachmentTarget === undefined ? "web" : "attachment";

  /** 点击路径只代表一次显式打开意图；不在渲染阶段读取文件或探测本机路径。 */
  const openTarget = useCallback(
    async (target: PreviewTarget): Promise<void> => {
      if (target.kind === "url") {
        const normalized = resolvePreviewNavigation(target.url, "");
        if (normalized.kind !== "navigate") {
          dispatch({ type: "validation", message: "浏览器地址无效或暂不支持此协议。" });
          throw new Error("浏览器地址无效或暂不支持此协议。");
        }
        target = { kind: "url", url: normalized.url };
      } else {
        const path = target.path.trim();
        if (
          path.length === 0 ||
          path.length > 4_096 ||
          [...path].some((character) => {
            const codePoint = character.codePointAt(0) ?? 0;
            return codePoint <= 31 || codePoint === 127;
          }) ||
          (target.line !== undefined && (!Number.isSafeInteger(target.line) || target.line < 1)) ||
          (target.column !== undefined &&
            (!Number.isSafeInteger(target.column) || target.column < 1))
        ) {
          dispatch({ type: "validation", message: "文件路径无效。" });
          throw new Error("文件路径无效。");
        }
        target = { ...target, path };
      }
      dispatch({ type: "validation" });
      if (port.openTarget === undefined) throw new Error("浏览器打开能力尚未连接。");
      await port.openTarget(target);
    },
    [port],
  );

  /** native URL 更新时重置本地草稿，避免地址栏继续显示已经过期的用户输入。 */
  useEffect(() => {
    dispatch({ type: "sync", url });
  }, [url]);

  /** 只更新本地草稿；URL 校验和跨进程导航延迟到显式提交。 */
  const changeDraft = useCallback((draft: string): void => {
    dispatch({ type: "change", draft });
  }, []);

  /** 空白页 identity 由 Rust 签发，创建失败时以 rejection 交给来源消息显示可恢复反馈。 */
  const newPage = useCallback(async (): Promise<void> => {
    if (port.newPage === undefined) throw new Error("浏览器尚未就绪。");
    await port.newPage();
  }, [port]);

  /** 切换只改变当前原生 WebView 可见性；页面历史与 session 都留在各自 page identity。 */
  const selectPage = useCallback(
    (pageId: string): void => {
      if (pages.some((page) => page.pageId === pageId)) port.selectPage?.(pageId);
    },
    [pages, port],
  );

  /** 关闭动作须等 native ACK，拒绝时保留原标签供用户重试。 */
  const closePage = useCallback(
    async (pageId: string): Promise<void> => {
      if (port.closePage === undefined) throw new Error("浏览器关闭能力尚未连接。");
      await port.closePage(pageId);
    },
    [port],
  );

  /** 后退只使用 native history capability，不在 React 端构造 URL 栈。 */
  const goBack = useCallback((): void => {
    if (canGoBack) port.goBack?.();
  }, [canGoBack, port]);

  /** 前进只使用 native history capability，不在 React 端构造 URL 栈。 */
  const goForward = useCallback((): void => {
    if (canGoForward) port.goForward?.();
  }, [canGoForward, port]);

  /** 将纯领域意图路由到窄 port，同 URL 只触发 reload 而不重复创建 navigation。 */
  const submit = useCallback((): void => {
    const intent = resolvePreviewNavigation(address.draft, url);
    if (intent.kind === "invalid") {
      dispatch({ type: "validation", message: intent.message });
      return;
    }
    dispatch({ type: "validation" });
    if (intent.kind === "reload") port.reload?.();
    else if (intent.kind === "open_file" && activePageId === undefined)
      void openTarget({ kind: "file", path: intent.path }).catch(() => undefined);
    else if (intent.kind === "open_file") port.navigateFile?.(intent.path);
    else if (intent.kind === "navigate" && activePageId === undefined)
      void openTarget({ kind: "url", url: intent.url }).catch(() => undefined);
    else if (intent.kind === "navigate") port.navigate?.(intent.url);
  }, [activePageId, address.draft, openTarget, port, url]);

  /** 恢复动作保持可选，未注入时不会制造无效 native 调用。 */
  const retryRecovery = useCallback((): void => {
    port.retryRecovery?.();
  }, [port]);

  /** DOM 几何仅通过 port 上报，controller 不访问 document 或 WebView。 */
  const changeViewport = useCallback(
    (viewport: PreviewViewport): void => {
      port.changeViewport?.(viewport);
    },
    [port],
  );

  const viewModel = useMemo<PreviewViewModel>(
    () => ({
      url,
      draft: address.draft,
      validationError: address.validationError,
      projection: projectPreviewUrl(url),
      loading,
      recovering,
      error,
      active,
      pages,
      activePageId,
      canGoBack,
      canGoForward,
      canRetryRecovery: port.retryRecovery !== undefined,
      canReportViewport: port.changeViewport !== undefined,
      mode,
      attachment: attachment.projection,
    }),
    [
      active,
      activePageId,
      address.draft,
      address.validationError,
      error,
      pages,
      canGoBack,
      canGoForward,
      loading,
      mode,
      port.changeViewport,
      port.retryRecovery,
      recovering,
      url,
      attachment.projection,
    ],
  );

  return {
    viewModel,
    actions: {
      openTarget,
      newPage,
      selectPage,
      closePage,
      goBack,
      goForward,
      changeDraft,
      submit,
      retryRecovery,
      changeViewport,
      attachment: attachment.actions,
    },
  };
}
