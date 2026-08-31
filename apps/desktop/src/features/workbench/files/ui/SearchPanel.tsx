// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useVirtualizer } from "@tanstack/react-virtual";
import { Search } from "lucide-react";
import { useEffect, useRef, useState, type ReactElement } from "react";
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
  onRetry,
}: SearchPanelProps): ReactElement {
  const [localQuery, setLocalQuery] = useState(query ?? "");
  useEffect(() => {
    if (query !== undefined) setLocalQuery(query);
  }, [query]);
  const scrollRef = useRef<HTMLDivElement>(null);
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
          value={query ?? localQuery}
          placeholder="搜索工作区…"
          onChange={(event) => {
            if (query === undefined) setLocalQuery(event.target.value);
            onQueryChange?.(event.target.value);
          }}
        />
      </label>
      {loading ? <LoadingState className="ja-feature-state" label="正在搜索…" /> : null}
      {error !== undefined ? (
        <ErrorState
          className="ja-feature-state ja-feature-error"
          title="搜索失败"
          message={error}
          onRetry={onRetry}
        />
      ) : null}
      {!loading && error === undefined && summary !== undefined ? (
        <p className={`ja-search-summary${summary.truncated ? " is-truncated" : ""}`} role="status">
          {results.length} 个结果 · 已扫描 {summary.scannedEntries} 个条目
          {summary.skippedFiles > 0 ? ` · 跳过 ${summary.skippedFiles} 个文件` : ""}
          {summary.truncated ? " · 结果已达到安全上限，可能不完整" : ""}
        </p>
      ) : null}
      {!loading && error === undefined && results.length === 0 ? (
        <EmptyState className="ja-feature-state" title="输入关键词后显示匹配结果。" />
      ) : null}
      {!loading && error === undefined && results.length > 0 ? (
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
