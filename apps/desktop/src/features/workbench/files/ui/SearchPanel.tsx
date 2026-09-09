// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useVirtualizer } from "@tanstack/react-virtual";
import { Search } from "lucide-react";
import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactElement,
} from "react";
import { EmptyState, ErrorState, LoadingState } from "@/shared/ui/primitives";
import type { FilesSearchResult } from "../application/types";
import type { SearchPanelProps } from "./types";
import "./SearchPanel.css";

/**
 * Search Index 始终留在 Runtime，Files UI 只 virtualize application projection，
 * 避免前端建立第二套文件系统索引或越过 Files controller。
 */
export function SearchPanel({
  query,
  results,
  summary,
  loading = false,
  error,
  onQueryChange,
  onOpenResult,
  onAddToConversation,
  onRetry,
  idleContent,
}: SearchPanelProps): ReactElement {
  const [localQuery, setLocalQuery] = useState(query ?? "");
  const [contextResult, setContextResult] = useState<{
    result: FilesSearchResult;
    x: number;
    y: number;
  }>();
  const contextMenuRef = useRef<HTMLDivElement>(null);
  const contextTriggerRef = useRef<HTMLButtonElement | undefined>(undefined);
  useEffect(() => {
    if (query !== undefined) setLocalQuery(query);
  }, [query]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const effectiveQuery = query ?? localQuery;

  /** 搜索结果菜单只保存相对路径投影；关闭时不读取文件，也不改变当前编辑文档。 */
  const closeContextMenu = useCallback((restoreFocus: boolean): void => {
    setContextResult(undefined);
    if (!restoreFocus) return;
    window.requestAnimationFrame(() => contextTriggerRef.current?.focus());
  }, []);

  useEffect(() => {
    if (contextResult === undefined) return undefined;
    const close = (): void => closeContextMenu(false);
    document.addEventListener("pointerdown", close);
    window.addEventListener("blur", close);
    return () => {
      document.removeEventListener("pointerdown", close);
      window.removeEventListener("blur", close);
    };
  }, [closeContextMenu, contextResult]);

  /** 菜单进入视图后立即聚焦唯一命令，让鼠标右键与 Shift+F10 拥有同一键盘终点。 */
  useLayoutEffect(() => {
    if (contextResult === undefined) return;
    const menu = contextMenuRef.current;
    if (menu === null) return;
    const bounds = menu.getBoundingClientRect();
    const margin = 8;
    const nextX = Math.max(
      margin,
      Math.min(contextResult.x, window.innerWidth - bounds.width - margin),
    );
    const nextY = Math.max(
      margin,
      Math.min(contextResult.y, window.innerHeight - bounds.height - margin),
    );
    if (nextX !== contextResult.x || nextY !== contextResult.y) {
      setContextResult((current) =>
        current === undefined ? current : { ...current, x: nextX, y: nextY },
      );
      return;
    }
    menu.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
  }, [contextResult]);
  // TanStack Virtual 持有 Measurement 与 Scroll Math；其 API 明确暴露 React Compiler
  // 无法安全 Memoize 的命令式函数。
  // eslint-disable-next-line react-hooks/incompatible-library
  const rowVirtualizer = useVirtualizer({
    count: results.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => 58,
    initialRect: { width: 0, height: 400 },
    overscan: 8,
  });
  return (
    <div className="ja-search-panel">
      <label className="ja-search-input-wrap" htmlFor="ja-workbench-search">
        <Search aria-hidden="true" />
        <input
          id="ja-workbench-search"
          aria-label="搜索工作区"
          type="search"
          value={effectiveQuery}
          placeholder="筛选文件…"
          onChange={(event) => {
            if (query === undefined) setLocalQuery(event.target.value);
            onQueryChange?.(event.target.value);
          }}
        />
      </label>
      {effectiveQuery.trim() === "" ? idleContent : null}
      {effectiveQuery.trim() !== "" && loading ? (
        <LoadingState className="ja-feature-state" label="正在搜索…" />
      ) : null}
      {effectiveQuery.trim() !== "" && error !== undefined ? (
        <ErrorState
          className="ja-feature-state ja-feature-error"
          title="搜索失败"
          message={error}
          onRetry={onRetry}
        />
      ) : null}
      {effectiveQuery.trim() !== "" && !loading && error === undefined && summary !== undefined ? (
        <p className={`ja-search-summary${summary.truncated ? " is-truncated" : ""}`} role="status">
          {results.length} 个结果 · 已扫描 {summary.scannedEntries} 个条目
          {summary.skippedFiles > 0 ? ` · 跳过 ${summary.skippedFiles} 个文件` : ""}
          {summary.truncated ? " · 结果已达到安全上限，可能不完整" : ""}
        </p>
      ) : null}
      {effectiveQuery.trim() !== "" && !loading && error === undefined && results.length === 0 ? (
        <EmptyState className="ja-feature-state" title="没有匹配的文件。" />
      ) : null}
      {effectiveQuery.trim() !== "" && !loading && error === undefined && results.length > 0 ? (
        <div className="ja-search-results" ref={scrollRef} role="list" aria-label="搜索结果">
          <div
            style={{
              height: Math.max(rowVirtualizer.getTotalSize(), results.length * 58),
              position: "relative",
              width: "100%",
            }}
          >
            {(rowVirtualizer.getVirtualItems().length === 0
              ? [{ index: 0, start: 0 }]
              : rowVirtualizer.getVirtualItems()
            ).map((virtualRow) => {
              const result = results[virtualRow.index];
              if (result === undefined) return null;
              return (
                <div
                  key={result.id}
                  className="ja-search-result-row"
                  role="listitem"
                  style={{
                    position: "absolute",
                    left: 0,
                    top: 0,
                    width: "100%",
                    transform: `translateY(${virtualRow.start}px)`,
                  }}
                  ref={rowVirtualizer.measureElement}
                  data-index={virtualRow.index}
                >
                  <button
                    type="button"
                    className="ja-search-result"
                    onClick={() => onOpenResult?.(result)}
                    onContextMenu={(event) => {
                      if (onAddToConversation === undefined) return;
                      event.preventDefault();
                      contextTriggerRef.current = event.currentTarget;
                      setContextResult({ result, x: event.clientX, y: event.clientY });
                    }}
                    onKeyDown={(event) => {
                      if (
                        onAddToConversation === undefined ||
                        (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10"))
                      )
                        return;
                      event.preventDefault();
                      const bounds = event.currentTarget.getBoundingClientRect();
                      contextTriggerRef.current = event.currentTarget;
                      setContextResult({ result, x: bounds.left + 24, y: bounds.bottom });
                    }}
                  >
                    <span className="ja-search-result-path">{result.path}</span>
                    <span className="ja-search-result-line">
                      {result.line}
                      {result.column === undefined ? "" : `:${result.column}`}
                    </span>
                    <span className="ja-search-result-preview">{renderPreview(result)}</span>
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      ) : null}
      {contextResult === undefined ? null : (
        <div
          ref={contextMenuRef}
          className="ja-file-tree-context-menu"
          role="menu"
          aria-label={`${contextResult.result.path} 文件操作`}
          style={{ left: contextResult.x, top: contextResult.y }}
          onPointerDown={(event) => event.stopPropagation()}
          onKeyDown={(event) => {
            if (event.key !== "Escape") return;
            event.preventDefault();
            closeContextMenu(true);
          }}
        >
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              const result = contextResult.result;
              closeContextMenu(false);
              onAddToConversation?.(result);
            }}
          >
            添加到对话
          </button>
        </div>
      )}
    </div>
  );
}

/** 只高亮 Runtime 提供的 offset；UI 不猜测 match，确保结果忠于 Search Provider。 */
function renderPreview(result: FilesSearchResult): ReactElement {
  const start = result.matchStart ?? -1;
  const length = result.matchLength ?? 0;
  if (start < 0 || length <= 0 || start >= result.preview.length) return <>{result.preview}</>;
  const end = Math.min(result.preview.length, start + length);
  return (
    <>
      {result.preview.slice(0, start)}
      <mark>{result.preview.slice(start, end)}</mark>
      {result.preview.slice(end)}
    </>
  );
}
