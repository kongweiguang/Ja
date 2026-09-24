// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  ArrowLeft,
  ArrowRight,
  Globe,
  Maximize2,
  Minus,
  Plus,
  RefreshCw,
  ScanLine,
  X,
} from "lucide-react";
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type ReactElement,
} from "react";
import { CodeViewer } from "@/features/workbench/editor";
import {
  EmptyState,
  ErrorState,
  IconButton,
  LoadingState,
  MenuItem,
  PointerContextMenu,
} from "@/shared/ui/primitives";
import type { PreviewActions, PreviewViewModel } from "../application/usePreviewController";
import type { PreviewPageProjection } from "../application/ports";
import { ATTACHMENT_ZOOM_MAX, ATTACHMENT_ZOOM_MIN } from "../domain/attachmentPreviewModel";
import "./PreviewPanel.css";

export interface PreviewPanelViewProps {
  viewModel: PreviewViewModel;
  actions: PreviewActions;
  onCopyText?: (text: string) => Promise<void>;
}

interface PreviewPageContextMenuState {
  readonly pageId: string;
  readonly x: number;
  readonly y: number;
  readonly session: number;
}

/** 网页 viewport 保持独立；标签菜单只携带 pageId，不改变页面选择或 child WebView 所有权。 */
function WebPreview({ viewModel, actions, onCopyText }: PreviewPanelViewProps): ReactElement {
  const { changeViewport } = actions;
  /** Thread Host 会并存于 DOM 中；实例 ID 保证地址标签和每页 tab 控制关系不串到隐藏会话。 */
  const instanceId = useId().replaceAll(":", "");
  const addressId = `ja-preview-url-${instanceId}`;
  const pageContentId = `ja-preview-page-content-${instanceId}`;
  const viewportRef = useRef<HTMLDivElement>(null);
  const tabStripRef = useRef<HTMLDivElement>(null);
  const addressRef = useRef<HTMLInputElement>(null);
  const newTabButtonRef = useRef<HTMLButtonElement>(null);
  const previousPageIdRef = useRef<string | undefined>(viewModel.activePageId);
  const focusNewPageRef = useRef(false);
  const contextMenuSessionRef = useRef(0);
  const [contextMenu, setContextMenu] = useState<PreviewPageContextMenuState>();
  const activeTitle = viewModel.pages.find((page) => page.pageId === viewModel.activePageId)?.title;
  const addressIsCurrent = viewModel.url !== "" && viewModel.draft.trim() === viewModel.url;
  const contextTarget =
    contextMenu === undefined
      ? undefined
      : viewModel.pages.find((page) => page.pageId === contextMenu.pageId);

  /** 标签文案优先使用页面标题；本机文件和空白页退化为短且可识别的名称。 */
  const tabLabel = (page: PreviewPageProjection): string => {
    const title = page.title.trim();
    if (title.length > 0) return title;
    if (page.url === "about:blank") return "新标签页";
    try {
      const parsed = new URL(page.url);
      if (parsed.protocol === "file:")
        return decodeURIComponent(parsed.pathname.split("/").filter(Boolean).at(-1) ?? page.url);
      return parsed.host || page.url;
    } catch {
      return page.url;
    }
  };

  /** 只在页面 identity 增长时更新焦点，切换既有标签不会抢走用户操作焦点。 */
  useEffect(() => {
    const previous = previousPageIdRef.current;
    previousPageIdRef.current = viewModel.activePageId;
    if (
      focusNewPageRef.current &&
      previous !== viewModel.activePageId &&
      viewModel.activePageId !== undefined
    ) {
      focusNewPageRef.current = false;
      addressRef.current?.focus({ preventScroll: true });
    }
  }, [viewModel.activePageId]);

  /** 只调整浏览器标签条的水平滚动，防止窄右栏新增页面后当前标签落到可视区外。 */
  useLayoutEffect(() => {
    const strip = tabStripRef.current;
    const tab = Array.from(strip?.querySelectorAll<HTMLElement>(".ja-preview-tab") ?? []).find(
      (candidate) => candidate.dataset["previewPageId"] === viewModel.activePageId,
    );
    if (strip === null || tab === undefined) return;
    const visible = strip.getBoundingClientRect();
    const selected = tab.getBoundingClientRect();
    if (selected.left < visible.left) strip.scrollLeft += selected.left - visible.left;
    else if (selected.right > visible.right) strip.scrollLeft += selected.right - visible.right;
  }, [activeTitle, viewModel.activePageId, viewModel.pages.length]);

  /** 按标准标签键位切换页面并保留键盘焦点；历史与页面内容仍由原生会话持有。 */
  const handleTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number): void => {
    if (event.altKey || event.ctrlKey || event.metaKey || viewModel.pages.length === 0) return;
    const last = viewModel.pages.length - 1;
    let nextIndex: number;
    switch (event.key) {
      case "ArrowRight":
        nextIndex = index === last ? 0 : index + 1;
        break;
      case "ArrowLeft":
        nextIndex = index === 0 ? last : index - 1;
        break;
      case "Home":
        nextIndex = 0;
        break;
      case "End":
        nextIndex = last;
        break;
      default:
        return;
    }
    event.preventDefault();
    const page = viewModel.pages[nextIndex];
    if (page === undefined) return;
    actions.selectPage(page.pageId);
    tabStripRef.current
      ?.querySelectorAll<HTMLButtonElement>(".ja-preview-tab-select")
      .item(nextIndex)
      ?.focus({ preventScroll: true });
  };

  /** 每次打开都换 session key，确保菜单正在打开时再次右击会重定位到新 pageId。 */
  const openPageContextMenu = (pageId: string, x: number, y: number): void => {
    contextMenuSessionRef.current += 1;
    setContextMenu({ pageId, x, y, session: contextMenuSessionRef.current });
  };

  /** 页面行只接管自身右键事件，避免网页内容和其它预览区域失去原生交互。 */
  const handlePageContextMenu = (event: ReactMouseEvent<HTMLDivElement>, pageId: string): void => {
    event.preventDefault();
    event.stopPropagation();
    openPageContextMenu(pageId, event.clientX, event.clientY);
  };

  /** 键盘菜单锚定当前标签行；ContextMenu 与 Shift+F10 不改变当前激活页面。 */
  const handlePageContextKeyDown = (event: KeyboardEvent<HTMLDivElement>, pageId: string): void => {
    if (event.key !== "ContextMenu" && !(event.key === "F10" && event.shiftKey)) return;
    event.preventDefault();
    event.stopPropagation();
    const bounds = event.currentTarget.getBoundingClientRect();
    openPageContextMenu(pageId, bounds.left, bounds.bottom);
  };

  /** 菜单退出后优先回到原标签，若标签已关闭则落到当前选择或新建标签按钮。 */
  const restorePageTabFocus = (pageId: string): void => {
    const tabs = Array.from(
      tabStripRef.current?.querySelectorAll<HTMLButtonElement>(".ja-preview-tab-select") ?? [],
    );
    const target =
      tabs.find((tab) => tab.dataset["previewPageId"] === pageId) ??
      tabs.find((tab) => tab.getAttribute("aria-selected") === "true") ??
      newTabButtonRef.current;
    target?.focus({ preventScroll: true });
  };

  /** 关闭前按最新 view model 复核目标，并复用 controller 的资源清理与错误语义。 */
  const closePageFromContextMenu = async (pageId: string): Promise<void> => {
    try {
      if (viewModel.pages.some((page) => page.pageId === pageId)) {
        await actions.closePage(pageId);
      }
    } catch {
      // 与标签上的直接关闭一致，native 关闭失败由 controller 处理且不泄漏 Promise rejection。
    } finally {
      window.requestAnimationFrame(() => restorePageTabFocus(pageId));
    }
  };

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
      <div className="ja-preview-tab-strip">
        <div ref={tabStripRef} className="ja-preview-tabs" role="tablist" aria-label="浏览器页面">
          {viewModel.pages.map((page, index) => {
            const label = tabLabel(page);
            const selected = page.pageId === viewModel.activePageId;
            return (
              <div
                key={page.pageId}
                className="ja-preview-tab"
                role="presentation"
                data-preview-page-id={page.pageId}
                data-active={selected || undefined}
                onContextMenu={(event) => handlePageContextMenu(event, page.pageId)}
                onKeyDown={(event) => handlePageContextKeyDown(event, page.pageId)}
              >
                <button
                  type="button"
                  role="tab"
                  className="ja-preview-tab-select"
                  aria-label={label}
                  aria-selected={selected}
                  aria-controls={pageContentId}
                  tabIndex={selected ? 0 : -1}
                  data-preview-page-id={page.pageId}
                  title={page.url}
                  onClick={() => actions.selectPage(page.pageId)}
                  onKeyDown={(event) => handleTabKeyDown(event, index)}
                >
                  {page.loading ? (
                    <span className="ja-preview-tab-loading" aria-hidden="true" />
                  ) : null}
                  <span>{label}</span>
                </button>
                <button
                  type="button"
                  className="ja-preview-tab-close"
                  aria-label={`关闭浏览器标签 ${label}`}
                  tabIndex={selected ? 0 : -1}
                  data-tab-close="true"
                  onClick={() => void actions.closePage(page.pageId).catch(() => undefined)}
                >
                  <X aria-hidden="true" />
                </button>
              </div>
            );
          })}
        </div>
        <IconButton
          ref={newTabButtonRef}
          className="ja-preview-icon-button ja-preview-new-tab"
          label="新建浏览器标签"
          onClick={() => {
            focusNewPageRef.current = true;
            void actions.newPage().catch(() => {
              focusNewPageRef.current = false;
            });
          }}
        >
          <Plus aria-hidden="true" />
        </IconButton>
      </div>
      {contextMenu === undefined || contextTarget === undefined ? null : (
        <PointerContextMenu
          key={contextMenu.session}
          x={contextMenu.x}
          y={contextMenu.y}
          label={`浏览器标签 ${tabLabel(contextTarget)}`}
          className="ja-preview-page-context-menu"
          onOpenChange={(open) => {
            if (!open) {
              const session = contextMenu.session;
              setContextMenu((current) => (current?.session === session ? undefined : current));
            }
          }}
          onRestoreFocus={() => restorePageTabFocus(contextMenu.pageId)}
        >
          {onCopyText === undefined || contextTarget.url.length === 0 ? null : (
            <MenuItem
              onSelect={() => {
                const page = viewModel.pages.find(
                  (candidate) => candidate.pageId === contextMenu.pageId,
                );
                if (page === undefined || page.url.length === 0 || onCopyText === undefined) return;
                void onCopyText(page.url).catch(() => undefined);
              }}
            >
              复制地址
            </MenuItem>
          )}
          <MenuItem onSelect={() => void closePageFromContextMenu(contextMenu.pageId)}>
            关闭
          </MenuItem>
        </PointerContextMenu>
      )}
      <form className="ja-preview-toolbar" onSubmit={submit}>
        <IconButton
          className="ja-preview-icon-button"
          label="后退"
          disabled={!viewModel.canGoBack}
          onClick={actions.goBack}
        >
          <ArrowLeft aria-hidden="true" />
        </IconButton>
        <IconButton
          className="ja-preview-icon-button"
          label="前进"
          disabled={!viewModel.canGoForward}
          onClick={actions.goForward}
        >
          <ArrowRight aria-hidden="true" />
        </IconButton>
        <label className="ja-preview-address" htmlFor={addressId}>
          <Globe aria-hidden="true" />
          <input
            ref={addressRef}
            id={addressId}
            aria-label="浏览器地址"
            value={viewModel.draft}
            onChange={(event) => actions.changeDraft(event.target.value)}
            placeholder="网址或本地文件路径"
            inputMode="url"
            autoCapitalize="none"
            autoCorrect="off"
          />
        </label>
        <IconButton
          type="submit"
          className="ja-preview-icon-button"
          label={addressIsCurrent ? "刷新页面" : "访问地址"}
          disabled={viewModel.recovering}
        >
          {addressIsCurrent ? <RefreshCw aria-hidden="true" /> : <ArrowRight aria-hidden="true" />}
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
        id={pageContentId}
        role="tabpanel"
        data-preview-page-id={viewModel.activePageId ?? ""}
        data-preview-page-url={viewModel.projection?.href ?? ""}
        data-preview-page-title={
          viewModel.pages.find((page) => page.pageId === viewModel.activePageId)?.title ?? ""
        }
        data-preview-page-loading={viewModel.loading}
        data-preview-can-go-back={viewModel.canGoBack}
        data-preview-can-go-forward={viewModel.canGoForward}
      >
        {viewModel.recovering ? (
          <LoadingState className="ja-preview-state" label="正在恢复浏览器…" />
        ) : viewModel.loading ? (
          <LoadingState className="ja-preview-state" label="正在加载预览…" />
        ) : viewModel.projection === undefined ? (
          <EmptyState
            className="ja-preview-state"
            title="尚未打开页面"
            description="输入网址或本机文件路径开始浏览。"
          />
        ) : viewModel.url === "about:blank" ? (
          <EmptyState
            className="ja-preview-state"
            title="新标签页"
            description="输入网址或本机文件路径开始浏览。"
          />
        ) : (
          <EmptyState
            className="ja-preview-state"
            title={viewModel.projection.origin}
            description="页面在独立浏览器标签中打开。"
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
