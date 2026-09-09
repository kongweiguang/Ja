// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ArrowLeft, Globe, Maximize2, Minus, Plus, RefreshCw, ScanLine } from "lucide-react";
import { useEffect, useRef, useState, type FormEvent, type ReactElement } from "react";
import { CodeViewer } from "@/features/workbench/editor";
import { EmptyState, ErrorState, IconButton, LoadingState } from "@/shared/ui/primitives";
import type { PreviewActions, PreviewViewModel } from "../application/usePreviewController";
import { ATTACHMENT_ZOOM_MAX, ATTACHMENT_ZOOM_MIN } from "../domain/attachmentPreviewModel";
import "./PreviewPanel.css";

export interface PreviewPanelViewProps {
  viewModel: PreviewViewModel;
  actions: PreviewActions;
  onCopyText?: (text: string) => Promise<void>;
}

/** 网页 viewport 保持单独边界；附件投影出现时卸载此 DOM，cleanup 会立即隐藏 child WebView。 */
function WebPreview({ viewModel, actions }: PreviewPanelViewProps): ReactElement {
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
      // native 子 WebView 的生命周期可能略长于 DOM，卸载时必须显式隐藏，避免遮住附件。
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
    <>
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
    </>
  );
}

/** 附件标题栏是进入右栏后的焦点落点，返回动作由组合层恢复到来源附件。 */
function AttachmentPreview({
  viewModel,
  actions,
  onCopyText,
}: PreviewPanelViewProps): ReactElement {
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [imageSize, setImageSize] = useState<{ width: number; height: number }>();
  const projection = viewModel.attachment;
  useEffect(() => {
    headingRef.current?.focus({ preventScroll: true });
  }, [projection?.target.attachmentId]);

  const title = projection?.target.displayName ?? "附件预览";
  const content = projection?.status === "ready" ? projection.content : undefined;
  const image = content?.kind === "image" ? content : undefined;
  const zoom = image?.zoom;
  const percent = zoom?.percent ?? 100;
  const imageStyle =
    zoom?.mode === "scale" && imageSize !== undefined
      ? { width: `${(imageSize.width * zoom.percent) / 100}px`, height: "auto" }
      : undefined;

  return (
    <section className="ja-attachment-preview" aria-labelledby="ja-attachment-preview-title">
      <header className="ja-attachment-preview-toolbar">
        <IconButton
          className="ja-preview-icon-button"
          label="返回网页预览"
          onClick={actions.attachment.dismiss}
        >
          <ArrowLeft aria-hidden="true" />
        </IconButton>
        <h2 id="ja-attachment-preview-title" ref={headingRef} tabIndex={-1} title={title}>
          {title}
        </h2>
        {image === undefined ? null : (
          <div className="ja-attachment-preview-zoom" role="group" aria-label="图片缩放">
            <IconButton
              className="ja-preview-icon-button"
              label="缩小"
              disabled={zoom?.mode === "scale" && percent <= ATTACHMENT_ZOOM_MIN}
              onClick={actions.attachment.zoomOut}
            >
              <Minus aria-hidden="true" />
            </IconButton>
            <output aria-label="当前缩放">{zoom?.mode === "fit" ? "适应" : `${percent}%`}</output>
            <IconButton
              className="ja-preview-icon-button"
              label="放大"
              disabled={zoom?.mode === "scale" && percent >= ATTACHMENT_ZOOM_MAX}
              onClick={actions.attachment.zoomIn}
            >
              <Plus aria-hidden="true" />
            </IconButton>
            <IconButton
              className="ja-preview-icon-button"
              label="适应窗口"
              aria-pressed={zoom?.mode === "fit"}
              onClick={actions.attachment.fit}
            >
              <Maximize2 aria-hidden="true" />
            </IconButton>
            <IconButton
              className="ja-preview-icon-button"
              label="实际尺寸"
              aria-pressed={zoom?.mode === "scale" && percent === 100}
              onClick={actions.attachment.actualSize}
            >
              <ScanLine aria-hidden="true" />
            </IconButton>
          </div>
        )}
      </header>
      {projection === undefined || projection.status === "loading" ? (
        <LoadingState
          className="ja-preview-state ja-attachment-preview-state"
          label="正在打开附件…"
        />
      ) : projection.status === "error" ? (
        <ErrorState
          className="ja-preview-state ja-attachment-preview-state"
          title="无法预览附件"
          message={projection.message}
          onRetry={projection.retryable ? actions.attachment.retry : undefined}
        />
      ) : projection.content.kind === "image" ? (
        <div
          className="ja-attachment-image-stage"
          data-zoom-mode={projection.content.zoom.mode}
          aria-label={`${projection.session.displayName} 图片预览`}
        >
          <img
            src={projection.content.resourceUrl}
            alt={projection.session.displayName}
            draggable={false}
            style={imageStyle}
            onLoad={(event) =>
              setImageSize({
                width: event.currentTarget.naturalWidth,
                height: event.currentTarget.naturalHeight,
              })
            }
            onError={actions.attachment.reportImageFailure}
          />
        </div>
      ) : (
        <div className="ja-attachment-text-preview">
          {projection.content.truncated ? (
            <p className="ja-attachment-preview-notice" role="status">
              仅展示前 1 MiB
            </p>
          ) : null}
          <CodeViewer
            filePath={projection.session.displayName}
            content={projection.content.text}
            revision={projection.session.previewSessionId}
            onCopyText={onCopyText}
          />
        </div>
      )}
    </section>
  );
}

/** 右栏只投影一个目标；附件模式完全替换地址栏与网页 viewport，但不关闭网页 session。 */
export function PreviewPanelView(props: PreviewPanelViewProps): ReactElement {
  return (
    <div className="ja-preview-panel" data-preview-mode={props.viewModel.mode}>
      {props.viewModel.mode === "attachment" ? (
        <AttachmentPreview {...props} />
      ) : (
        <WebPreview {...props} />
      )}
    </div>
  );
}
