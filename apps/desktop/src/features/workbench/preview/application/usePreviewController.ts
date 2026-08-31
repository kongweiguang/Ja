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
import type { PreviewPort, PreviewViewport } from "./ports";

export interface PreviewControllerOptions {
  url: string;
  loading: boolean;
  recovering: boolean;
  error?: string;
  active: boolean;
  port: PreviewPort;
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
  canRetryRecovery: boolean;
  canReportViewport: boolean;
}

export interface PreviewActions {
  changeDraft: (draft: string) => void;
  submit: () => void;
  retryRecovery: () => void;
  changeViewport: (viewport: PreviewViewport) => void;
}

export interface PreviewController {
  viewModel: PreviewViewModel;
  actions: PreviewActions;
}

/** 统一拥有地址草稿与导航编排；UI 只消费投影和动作，native 能力由 App 注入。 */
export function usePreviewController({
  url,
  loading,
  recovering,
  error,
  active,
  port,
}: PreviewControllerOptions): PreviewController {
  const [address, dispatch] = useReducer(reducePreviewAddress, url, createPreviewAddressState);

  /** native URL 更新时重置本地草稿，避免地址栏继续显示已经过期的用户输入。 */
  useEffect(() => {
    dispatch({ type: "sync", url });
  }, [url]);

  /** 只更新本地草稿；URL 校验和跨进程导航延迟到显式提交。 */
  const changeDraft = useCallback((draft: string): void => {
    dispatch({ type: "change", draft });
  }, []);

  /** 将纯领域意图路由到窄 port，同 URL 只触发 reload 而不重复创建 navigation。 */
  const submit = useCallback((): void => {
    const intent = resolvePreviewNavigation(address.draft, url);
    if (intent.kind === "invalid") {
      dispatch({ type: "validation", message: intent.message });
      return;
    }
    dispatch({ type: "validation" });
    if (intent.kind === "reload") port.reload?.();
    else port.navigate?.(intent.url);
  }, [address.draft, port, url]);

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
      canRetryRecovery: port.retryRecovery !== undefined,
      canReportViewport: port.changeViewport !== undefined,
    }),
    [
      active,
      address.draft,
      address.validationError,
      error,
      loading,
      port.changeViewport,
      port.retryRecovery,
      recovering,
      url,
    ],
  );

  return { viewModel, actions: { changeDraft, submit, retryRecovery, changeViewport } };
}
