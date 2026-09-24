// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useVirtualizer } from "@tanstack/react-virtual";
import { ExternalLink, MessageSquarePlus, Search } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type ReactElement } from "react";
import {
  EmptyState,
  ErrorState,
  LoadingState,
  MenuItem,
  MenuSeparator,
  PointerContextMenu,
} from "@/shared/ui/primitives";
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
    resultId: string;
    x: number;
    y: number;
    key: number;
  }>();
  const contextTriggerRef = useRef<HTMLButtonElement | undefined>(undefined);
  const contextMenuSessionRef = useRef(0);
  useEffect(() => {
    if (query !== undefined) setLocalQuery(query);
  }, [query]);
  const scrollRef = useRef<HTMLDivElement>(null);
  const effectiveQuery = query ?? localQuery;
  const contextMenuResult =
    contextResult === undefined
      ? undefined
      : results.find((result) => result.id === contextResult.resultId);

  /** 搜索结果菜单只保存稳定结果 id；选中前从最新投影复核结果仍然存在。 */
  const closeContextMenu = useCallback((restoreFocus: boolean): void => {
    setContextResult(undefined);
    if (!restoreFocus) return;
    window.requestAnimationFrame(() => {
      if (contextTriggerRef.current?.isConnected) contextTriggerRef.current.focus();
    });
  }, []);

  /** 键盘与鼠标共享同一定位入口，每次开启都换 key 让 Radix 重新测量指针锚点。 */
  const openResultContextMenu = useCallback(
    (result: FilesSearchResult, x: number, y: number, trigger: HTMLButtonElement): void => {
      if (onOpenResult === undefined && onAddToConversation === undefined) return;
      contextTriggerRef.current = trigger;
      contextMenuSessionRef.current += 1;
      setContextResult({ resultId: result.id, x, y, key: contextMenuSessionRef.current });
    },
    [onAddToConversation, onOpenResult],
  );

  useEffect(() => {
    if (contextResult === undefined) return undefined;
    /** 窗口失焦后关闭临时菜单，避免恢复时把旧结果作为当前操作目标。 */
    const close = (): void => closeContextMenu(false);
    window.addEventListener("blur", close);
    return () => {
      window.removeEventListener("blur", close);
    };
  }, [closeContextMenu, contextResult]);

  useEffect(() => {
    if (contextResult === undefined || contextMenuResult !== undefined) return;
    /** 列表刷新移除了右键目标时关闭菜单，避免把操作应用到相似路径的新结果。 */
    closeContextMenu(false);
  }, [closeContextMenu, contextMenuResult, contextResult]);
  // TanStack Virtual 持有 Measurement 与 Scroll Math；其 API 明确暴露 React Compiler
  // 无法安全 Memoize 的命令式函数。
  // eslint-disable-next-line react-hooks/incompatible-library
  const rowVirtualizer = useVirtualizer({
    // React 19 的测量 ref 可能在 commit 生命周期内同步校正；避免 adapter 在该阶段调用 flushSync。
    useFlushSync: false,
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
                      if (onOpenResult === undefined && onAddToConversation === undefined) return;
                      event.preventDefault();
                      event.stopPropagation();
                      openResultContextMenu(
                        result,
                        event.clientX,
                        event.clientY,
                        event.currentTarget,
                      );
                    }}
                    onKeyDown={(event) => {
                      if (
                        (onOpenResult === undefined && onAddToConversation === undefined) ||
                        (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10"))
                      )
                        return;
                      event.preventDefault();
                      const bounds = event.currentTarget.getBoundingClientRect();
                      openResultContextMenu(
                        result,
                        bounds.left + 24,
                        bounds.bottom,
                        event.currentTarget,
                      );
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
      {contextResult === undefined || contextMenuResult === undefined ? null : (
        <PointerContextMenu
          key={contextResult.key}
          x={contextResult.x}
          y={contextResult.y}
          label={`${contextMenuResult.path} 文件操作`}
          onOpenChange={(open) => {
            if (!open) closeContextMenu(false);
          }}
          onRestoreFocus={() => closeContextMenu(true)}
        >
          {onOpenResult === undefined ? null : (
            <MenuItem
              onSelect={() => {
                const result = contextMenuResult;
                if (result === undefined) return;
                closeContextMenu(false);
                onOpenResult(result);
              }}
            >
              <ExternalLink aria-hidden="true" />
              <span>打开</span>
            </MenuItem>
          )}
          {onOpenResult !== undefined && onAddToConversation !== undefined ? (
            <MenuSeparator />
          ) : null}
          {onAddToConversation === undefined ? null : (
            <MenuItem
              onSelect={() => {
                const result = contextMenuResult;
                if (result === undefined) return;
                closeContextMenu(false);
                onAddToConversation(result);
              }}
            >
              <MessageSquarePlus aria-hidden="true" />
              <span>添加到对话</span>
            </MenuItem>
          )}
        </PointerContextMenu>
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
