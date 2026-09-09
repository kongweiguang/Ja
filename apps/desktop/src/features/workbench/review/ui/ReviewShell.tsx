// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ArrowLeft, ChevronLeft, ChevronRight, LoaderCircle, RefreshCw } from "lucide-react";
import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { IconButton } from "@/shared/ui/primitives";
import "./ReviewShell.css";

export interface ReviewShellStats {
  readonly files: number;
  readonly additions: number;
  readonly deletions: number;
}

export interface ReviewShellProps {
  readonly ariaLabel: string;
  readonly scopeLabel: string;
  readonly sourceNavigation?: ReactNode;
  readonly stats: ReviewShellStats;
  readonly refreshing: boolean;
  readonly onRefresh: () => void;
  readonly refreshLabel: string;
  readonly notice?: ReactNode;
  readonly toolbar?: ReactNode;
  readonly tree: ReactNode;
  readonly diff: ReactNode;
  readonly detailOpen: boolean;
  readonly onBack: () => void;
  readonly onPreviousFile?: () => void;
  readonly onNextFile?: () => void;
  readonly previousDisabled?: boolean;
  readonly nextDisabled?: boolean;
  readonly dataAttributes?: Readonly<Record<string, string | number | undefined>>;
}

const NARROW_WIDTH = 760;
const MIN_TREE_WIDTH = 208;
const MAX_TREE_WIDTH = 420;

/** Clamp 树宽，避免拖拽让 Diff 或文件名失去最小可用区域。 */
function clampTreeWidth(width: number, containerWidth: number): number {
  return Math.min(
    Math.max(width, MIN_TREE_WIDTH),
    Math.min(MAX_TREE_WIDTH, Math.max(MIN_TREE_WIDTH, containerWidth * 0.45)),
  );
}

/**
 * Git 与 Turn Review 共用同一个内容优先外壳：容器宽度决定窄/宽布局，
 * 玻璃只留给宿主提供的范围菜单，Diff 与文件树始终使用稳定实色表面。
 */
export function ReviewShell({
  ariaLabel,
  scopeLabel,
  sourceNavigation,
  stats,
  refreshing,
  onRefresh,
  refreshLabel,
  notice,
  toolbar,
  tree,
  diff,
  detailOpen,
  onBack,
  onPreviousFile,
  onNextFile,
  previousDisabled = false,
  nextDisabled = false,
  dataAttributes,
}: ReviewShellProps): ReactElement {
  const rootRef = useRef<HTMLElement>(null);
  const dragRef = useRef<{ pointerId: number; startX: number; startWidth: number } | undefined>(
    undefined,
  );
  const [layout, setLayout] = useState<"narrow" | "wide">("wide");
  const [treeWidth, setTreeWidth] = useState(240);

  /** ResizeObserver 绑定实际 Review 容器，Workbench 分栏变化无需依赖 window viewport。 */
  useEffect(() => {
    const root = rootRef.current;
    if (root === null) return undefined;
    const update = (width: number): void => {
      if (!Number.isFinite(width) || width <= 0) return;
      setLayout(width < NARROW_WIDTH ? "narrow" : "wide");
      setTreeWidth((current) => clampTreeWidth(current, width));
    };
    update(root.getBoundingClientRect().width);
    const cancelResize = (): void => {
      dragRef.current = undefined;
    };
    window.addEventListener("blur", cancelResize);
    if (typeof ResizeObserver === "undefined")
      return () => window.removeEventListener("blur", cancelResize);
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0];
      if (entry !== undefined) update(entry.contentRect.width);
    });
    observer.observe(root);
    return () => {
      observer.disconnect();
      window.removeEventListener("blur", cancelResize);
    };
  }, []);

  /** Pointer capture 让拖拽在越过分隔线后仍连续，树位于右侧所以水平增量方向取反。 */
  const beginResize = (event: PointerEvent<HTMLDivElement>): void => {
    if (layout !== "wide" || event.button !== 0) return;
    dragRef.current = { pointerId: event.pointerId, startX: event.clientX, startWidth: treeWidth };
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  /** 拖拽只更新一个 CSS 尺寸，不读写树节点，避免大量文件下重算数据模型。 */
  const resize = (event: PointerEvent<HTMLDivElement>): void => {
    const drag = dragRef.current;
    const root = rootRef.current;
    if (drag === undefined || root === null || drag.pointerId !== event.pointerId) return;
    setTreeWidth(clampTreeWidth(drag.startWidth - (event.clientX - drag.startX), root.clientWidth));
  };

  /** 键盘以 16px 步进调整树宽，并复用同一边界约束。 */
  const resizeByKeyboard = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const rootWidth = rootRef.current?.clientWidth ?? 1000;
    setTreeWidth((current) =>
      event.key === "Home"
        ? clampTreeWidth(MIN_TREE_WIDTH, rootWidth)
        : event.key === "End"
          ? clampTreeWidth(MAX_TREE_WIDTH, rootWidth)
          : clampTreeWidth(current + (event.key === "ArrowLeft" ? 16 : -16), rootWidth),
    );
  };

  return (
    <section
      ref={rootRef}
      className={`ja-review-shell${detailOpen ? " is-detail-open" : ""}`}
      aria-label={ariaLabel}
      data-ja-review-shell
      data-review-layout={layout}
      style={{ "--ja-review-tree-width": `${treeWidth}px` } as React.CSSProperties}
      {...dataAttributes}
    >
      <header className="ja-review-shell-header">
        <div className="ja-review-shell-scope">
          {sourceNavigation ?? <strong>{scopeLabel}</strong>}
        </div>
        <div className="ja-review-shell-summary" aria-label="变更统计">
          <span>{stats.files} 文件</span>
          <span className="is-added">+{stats.additions}</span>
          <span className="is-removed">-{stats.deletions}</span>
        </div>
        <div className="ja-review-shell-navigation">
          <IconButton label={refreshLabel} tooltip="刷新" disabled={refreshing} onClick={onRefresh}>
            {refreshing ? (
              <LoaderCircle className="ja-review-spin" aria-hidden="true" />
            ) : (
              <RefreshCw aria-hidden="true" />
            )}
          </IconButton>
        </div>
      </header>
      {notice === undefined ? null : <div className="ja-review-shell-notice">{notice}</div>}
      {toolbar === undefined && onPreviousFile === undefined && onNextFile === undefined ? null : (
        <div className="ja-review-shell-toolbar">
          {toolbar}
          {onPreviousFile === undefined && onNextFile === undefined ? null : (
            <div className="ja-review-shell-file-navigation" aria-label="文件导航">
              {onPreviousFile === undefined ? null : (
                <IconButton
                  label="上一个文件"
                  tooltip="上一个文件"
                  disabled={previousDisabled}
                  onClick={onPreviousFile}
                >
                  <ChevronLeft aria-hidden="true" />
                </IconButton>
              )}
              {onNextFile === undefined ? null : (
                <IconButton
                  label="下一个文件"
                  tooltip="下一个文件"
                  disabled={nextDisabled}
                  onClick={onNextFile}
                >
                  <ChevronRight aria-hidden="true" />
                </IconButton>
              )}
            </div>
          )}
        </div>
      )}
      <div className="ja-review-shell-body">
        <main className="ja-review-shell-diff">
          <div className="ja-review-shell-mobile-bar">
            <IconButton label="返回变更文件" tooltip="返回" onClick={onBack}>
              <ArrowLeft aria-hidden="true" />
            </IconButton>
            <span title={scopeLabel}>{scopeLabel}</span>
          </div>
          {diff}
        </main>
        <div
          className="ja-review-tree-resizer"
          role="separator"
          aria-label="调整文件树宽度"
          aria-orientation="vertical"
          aria-valuemin={MIN_TREE_WIDTH}
          aria-valuemax={MAX_TREE_WIDTH}
          aria-valuenow={Math.round(treeWidth)}
          tabIndex={0}
          data-ja-review-tree-resizer
          onPointerDown={beginResize}
          onPointerMove={resize}
          onPointerUp={(event) => {
            dragRef.current = undefined;
            if (event.currentTarget.hasPointerCapture(event.pointerId))
              event.currentTarget.releasePointerCapture(event.pointerId);
          }}
          onPointerCancel={() => {
            dragRef.current = undefined;
          }}
          onLostPointerCapture={() => {
            dragRef.current = undefined;
          }}
          onKeyDown={resizeByKeyboard}
        />
        <aside className="ja-review-shell-tree" aria-label="变更文件列表">
          {tree}
        </aside>
      </div>
    </section>
  );
}
