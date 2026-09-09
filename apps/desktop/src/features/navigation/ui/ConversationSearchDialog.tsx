// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ArchiveRestore, Clock3, LoaderCircle, Search } from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/shared/ui/primitives";
import type { ThreadProjection } from "../domain/navigationModels";
import "./conversationDialogs.css";

export interface ConversationSearchDialogProps {
  open: boolean;
  shortcutLabel: string;
  refreshIdentity?: string;
  onOpenChange(open: boolean): void;
  onSearch(query: string): Promise<readonly ThreadProjection[]>;
  onSelect(threadId: string): void | Promise<void>;
  onRestore(threadId: string): void | Promise<void>;
}

/** 命中高亮只改变视觉片段，不改变服务端标题或使用不安全 HTML。 */
function highlightedTitle(title: string, query: string): ReactNode {
  const normalized = query.trim().toLocaleLowerCase();
  if (normalized === "") return title;
  const index = title.toLocaleLowerCase().indexOf(normalized);
  if (index < 0) return title;
  return (
    <>
      {title.slice(0, index)}
      <mark>{title.slice(index, index + normalized.length)}</mark>
      {title.slice(index + normalized.length)}
    </>
  );
}

/**
 * Command Dialog 只搜索当前 Workspace 标题；空查询读取最近会话，refresh identity
 * 只触发静默的同查询刷新，不会重置打开周期内的输入和选择。
 */
export function ConversationSearchDialog({
  open,
  shortcutLabel,
  refreshIdentity,
  onOpenChange,
  onSearch,
  onSelect,
  onRestore,
}: ConversationSearchDialogProps): ReactElement {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {open ? (
        <ConversationSearchSession
          shortcutLabel={shortcutLabel}
          refreshIdentity={refreshIdentity}
          onOpenChange={onOpenChange}
          onSearch={onSearch}
          onSelect={onSelect}
          onRestore={onRestore}
        />
      ) : null}
    </Dialog>
  );
}

type SearchOutcome = Readonly<{
  query: string;
  results: readonly ThreadProjection[];
  error: boolean;
}>;

const EMPTY_SEARCH_RESULTS: readonly ThreadProjection[] = [];

/**
 * 打开周期拥有独立搜索状态；标题刷新保留已呈现结果与活动 Thread，
 * 关闭时才统一失效晚到请求，避免输入失焦或列表闪动。
 */
function ConversationSearchSession({
  shortcutLabel,
  refreshIdentity,
  onOpenChange,
  onSearch,
  onSelect,
  onRestore,
}: Omit<ConversationSearchDialogProps, "open">): ReactElement {
  const [query, setQuery] = useState("");
  const [outcome, setOutcome] = useState<SearchOutcome | null>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const requestRef = useRef(0);
  const mountedRef = useRef(true);
  const outcomeRef = useRef<SearchOutcome | null>(null);
  const activeIndexRef = useRef(0);
  const processedRefreshIdentityRef = useRef(refreshIdentity);
  const resultListRef = useRef<HTMLDivElement>(null);
  const listId = useId();
  const currentOutcome = outcome?.query === query ? outcome : null;
  const results = currentOutcome?.results ?? EMPTY_SEARCH_RESULTS;
  const loading = currentOutcome === null;
  const error = currentOutcome?.error ?? false;

  /** Promise 只在 Effect/交互后读取最新快照，避免在 render 阶段把 ref 变成隐式 state。 */
  useEffect(() => {
    outcomeRef.current = outcome;
    activeIndexRef.current = activeIndex;
  }, [activeIndex, outcome]);

  /**
   * 同一 request fence 串行用户查询与后台标题刷新；refresh 失败保留旧投影，
   * 成功时按 Thread identity 恢复活动项，不让服务端重排把 Enter 指向其它会话。
   */
  const executeSearch = useCallback(
    (requestedQuery: string, refresh: boolean): void => {
      const request = requestRef.current + 1;
      requestRef.current = request;
      void Promise.resolve()
        .then(() => onSearch(requestedQuery))
        .then((items) => {
          if (!mountedRef.current || requestRef.current !== request) return;
          const current = outcomeRef.current;
          const activeThreadId =
            refresh && current?.query === requestedQuery
              ? current.results[activeIndexRef.current]?.threadId
              : undefined;
          const nextOutcome = { query: requestedQuery, results: items, error: false } as const;
          outcomeRef.current = nextOutcome;
          setOutcome(nextOutcome);
          if (!refresh) return;
          setActiveIndex((currentIndex) => {
            const preservedIndex = items.findIndex((thread) => thread.threadId === activeThreadId);
            const nextIndex =
              preservedIndex >= 0
                ? preservedIndex
                : Math.min(currentIndex, Math.max(0, items.length - 1));
            activeIndexRef.current = nextIndex;
            return nextIndex;
          });
        })
        .catch(() => {
          if (!mountedRef.current || requestRef.current !== request || refresh) return;
          const nextOutcome = { query: requestedQuery, results: [], error: true } as const;
          outcomeRef.current = nextOutcome;
          setOutcome(nextOutcome);
        });
    },
    [onSearch],
  );

  /** query 变化是用户可见查询，pending 仍由 query/outcome identity 派生。 */
  useEffect(() => {
    executeSearch(query, false);
  }, [executeSearch, query]);

  /**
   * 只消费新的 metadata identity；初次打开已由权威查询覆盖，undefined 回落
   * 不应取消正在进行的静默刷新。
   */
  useEffect(() => {
    if (refreshIdentity === undefined || processedRefreshIdentityRef.current === refreshIdentity)
      return;
    processedRefreshIdentityRef.current = refreshIdentity;
    executeSearch(query, true);
  }, [executeSearch, query, refreshIdentity]);

  /** 关闭 Dialog 时一次性失效所有晚到 Promise，不依赖特定 Effect 的 cleanup 顺序。 */
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestRef.current += 1;
    };
  }, []);

  /**
   * 活动结果仍由输入框持有焦点；键盘跨过可视区域时只滚动列表到最近边缘，避免
   * Command Dialog 抢焦点或产生整页跳动。
   */
  useEffect(() => {
    const activeOption = resultListRef.current?.querySelector<HTMLElement>(
      `[data-search-result-index="${activeIndex}"]`,
    );
    activeOption?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [activeIndex, results]);

  /** 选择动作先关闭浮层；Radix 只做临时焦点归还，应用在 Thread 就绪后接管最终落点。 */
  const selectResult = (thread: ThreadProjection): void => {
    onOpenChange(false);
    const action =
      thread.status === "archived" ? onRestore(thread.threadId) : onSelect(thread.threadId);
    void Promise.resolve(action).catch(() => undefined);
  };

  /** 完整键盘导航不依赖列表焦点；IME 组合期间 Enter 只确认候选，不打开会话。 */
  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>): void => {
    if (event.nativeEvent.isComposing) return;
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setActiveIndex((index) =>
        results.length === 0 ? 0 : Math.min(results.length - 1, index + 1),
      );
      return;
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      setActiveIndex((index) => Math.max(0, index - 1));
      return;
    }
    if (event.key === "Home") {
      event.preventDefault();
      setActiveIndex(0);
      return;
    }
    if (event.key === "End") {
      event.preventDefault();
      setActiveIndex(Math.max(0, results.length - 1));
      return;
    }
    if (event.key === "Enter" && results[activeIndex] !== undefined) {
      event.preventDefault();
      selectResult(results[activeIndex]);
    }
  };

  return (
    <DialogContent
      className="ja-conversation-search-dialog"
      overlayClassName="ja-conversation-search-overlay"
    >
      <DialogTitle className="ja-visually-hidden">搜索对话</DialogTitle>
      <DialogDescription className="ja-visually-hidden">搜索当前工作区的会话标题</DialogDescription>
      <div className="ja-conversation-search-field">
        <Search aria-hidden="true" />
        <input
          autoFocus
          type="search"
          value={query}
          placeholder="搜索对话"
          aria-label="搜索对话"
          aria-controls={listId}
          aria-activedescendant={
            results[activeIndex] === undefined ? undefined : `${listId}-option-${activeIndex}`
          }
          onChange={(event) => {
            setQuery(event.currentTarget.value);
            setActiveIndex(0);
          }}
          onKeyDown={handleKeyDown}
        />
        <kbd>{shortcutLabel}</kbd>
      </div>
      <div className="ja-conversation-search-heading">
        <span>{query.trim() === "" ? "最近会话" : "搜索结果"}</span>
        {loading ? <LoaderCircle aria-label="正在搜索" className="is-spinning" /> : null}
      </div>
      <div
        ref={resultListRef}
        id={listId}
        className="ja-conversation-search-results"
        role="listbox"
      >
        {error ? <p role="alert">搜索暂时不可用</p> : null}
        {!loading && !error && results.length === 0 ? <p>没有匹配的会话</p> : null}
        {results.map((thread, index) => {
          const title = thread.title.trim() || "未命名对话";
          return (
            <button
              id={`${listId}-option-${index}`}
              key={thread.threadId}
              type="button"
              role="option"
              aria-selected={activeIndex === index}
              data-active={activeIndex === index || undefined}
              data-search-result-index={index}
              onPointerMove={() => setActiveIndex(index)}
              aria-label={thread.status === "archived" ? `恢复并打开：${title}` : `打开：${title}`}
              onClick={() => selectResult(thread)}
            >
              {thread.status === "archived" ? (
                <ArchiveRestore aria-hidden="true" />
              ) : (
                <Clock3 aria-hidden="true" />
              )}
              <span>{highlightedTitle(title, query)}</span>
              {thread.status === "archived" ? <small>已归档 · 恢复并打开</small> : null}
            </button>
          );
        })}
      </div>
    </DialogContent>
  );
}
