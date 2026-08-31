// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Globe, RefreshCw } from "lucide-react";
import { useEffect, useRef, type FormEvent, type ReactElement } from "react";
import { EmptyState, ErrorState, IconButton, LoadingState } from "@/shared/ui/primitives";
import type { PreviewActions, PreviewViewModel } from "../application/usePreviewController";
import "./PreviewPanel.css";

export interface PreviewPanelViewProps {
  viewModel: PreviewViewModel;
  actions: PreviewActions;
}

/** 纯 UI 只消费 Preview view model/actions，地址规则与 native 导航编排由下层负责。 */
export function PreviewPanelView({ viewModel, actions }: PreviewPanelViewProps): ReactElement {
  const { changeViewport } = actions;
  const viewportRef = useRef<HTMLDivElement>(null);

  /** 订阅 viewport 几何变化，使 native 子 WebView 与当前 DOM 区域保持同步。 */
  useEffect(() => {
    const element = viewportRef.current;
    if (element === null || !viewModel.canReportViewport) return undefined;
    /** 上报 CSS pixel 边界，Tauri 再按当前显示器 DPI 转换 logical coordinates。 */
    const publish = (): void => {
      const rect = element.getBoundingClientRect();
      changeViewport({
        x: rect.x,
        y: rect.y,
        width: rect.width,
        height: rect.height,
        visible: viewModel.active && rect.width >= 1 && rect.height >= 1,
      });
    };
    publish();
    const observer =
      typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(publish);
    observer?.observe(element);
    window.addEventListener("resize", publish);
    return () => {
      // native 子 WebView 的生命周期可能略长于 DOM，卸载时必须显式隐藏，避免遮住相邻 Tab。
      const rect = element.getBoundingClientRect();
      changeViewport({
        x: rect.x,
        y: rect.y,
        width: rect.width,
        height: rect.height,
        visible: false,
      });
      observer?.disconnect();
      window.removeEventListener("resize", publish);
    };
  }, [changeViewport, viewModel.active, viewModel.canReportViewport]);

  /** 表单只触发 controller action，避免 JSX 内复制 URL 校验与 reload 判断。 */
  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    actions.submit();
  };
  return (
    <div className="ja-preview-panel">
      <form className="ja-preview-toolbar" onSubmit={submit}>
        <label className="ja-preview-address" htmlFor="ja-preview-url">
          <Globe aria-hidden="true" />
          <input
            id="ja-preview-url"
            aria-label="Preview 地址"
            value={viewModel.draft}
            onChange={(event) => actions.changeDraft(event.target.value)}
            placeholder="https://example.com"
            inputMode="url"
            autoCapitalize="none"
            autoCorrect="off"
          />
        </label>
        <IconButton
          type="submit"
          className="ja-preview-icon-button"
          label="刷新或访问"
          disabled={viewModel.recovering}
        >
          <RefreshCw aria-hidden="true" />
        </IconButton>
      </form>
      {viewModel.validationError !== undefined ? (
        <p className="ja-preview-error" role="alert">
          {viewModel.validationError}
        </p>
      ) : null}
      {viewModel.error !== undefined ? (
        <ErrorState
          className="ja-preview-recovery-row"
          title="浏览器预览异常"
          message={viewModel.error}
          onRetry={
            viewModel.canRetryRecovery && !viewModel.recovering ? actions.retryRecovery : undefined
          }
        />
      ) : null}
      <div
        ref={viewportRef}
        className="ja-preview-viewport"
        aria-live="polite"
        data-url={viewModel.projection?.href ?? ""}
      >
        {viewModel.recovering ? (
          <LoadingState className="ja-preview-state" label="正在恢复浏览器…" />
        ) : viewModel.loading ? (
          <LoadingState className="ja-preview-state" label="正在加载预览…" />
        ) : viewModel.projection === undefined ? (
          <EmptyState
            className="ja-preview-state"
            title="尚未打开预览"
            description="输入 http:// 或 https:// 地址开始预览。"
          />
        ) : (
          <EmptyState
            className="ja-preview-state"
            title={viewModel.projection.origin}
            description="页面由 Rust 管理的独立 WebView 承载。"
          />
        )}
      </div>
    </div>
  );
}
