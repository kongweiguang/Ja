// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState, type ReactElement } from "react";
import { Popover, PopoverContent, PopoverTrigger } from "@/shared/ui/primitives";
import type { ConversationUsageReader, ConversationUsageSummary } from "../../application/ports";
import type { ContextUsagePresentation } from "../../domain/contextUsage";

const USAGE_CACHE_LIMIT = 32;
const HOVER_CLOSE_DELAY_MS = 90;

interface UsageLoadState {
  scopeKey: string;
  summary?: ConversationUsageSummary;
  phase: "idle" | "loading" | "ready" | "stale" | "unavailable";
}

const usageCache = new Map<string, ConversationUsageSummary>();

export interface ContextUsageIndicatorProps {
  /** 圆环投影最近请求；累计数据只在展开层出现，两种口径不可互相替代。 */
  usage?: ContextUsagePresentation;
  /** 查询与缓存必须绑定 Thread 和 runtime 代际，拒绝重连后的迟到响应。 */
  threadId?: string;
  runtimeGeneration?: number;
  usageReader?: ConversationUsageReader;
  /** 仅由结算、恢复或上下文身份变化推进；浮层关闭时绝不因此轮询。 */
  refreshRevision?: string | number;
}

/** 窗口容量以 K/M 控制行宽；累计数字则使用单独的千位分隔，避免混淆两种阅读语义。 */
function formatContextCapacity(value: number): string {
  if (value >= 1_000_000) {
    const digits = value >= 10_000_000 ? 0 : 1;
    return (value / 1_000_000).toFixed(digits).replace(/\.0$/u, "") + "M";
  }
  if (value >= 1_000) {
    const digits = value >= 100_000 ? 0 : 1;
    return (value / 1_000).toFixed(digits).replace(/\.0$/u, "") + "K";
  }
  return value.toLocaleString("en-US");
}

/** LRU 上限防止关闭过的 Thread 摘要把前端诊断状态变成无界缓存。 */
function rememberUsage(scopeKey: string, summary: ConversationUsageSummary): void {
  usageCache.delete(scopeKey);
  usageCache.set(scopeKey, summary);
  while (usageCache.size > USAGE_CACHE_LIMIT) {
    const oldest = usageCache.keys().next().value;
    if (oldest === undefined) return;
    usageCache.delete(oldest);
  }
}

/** 命中率只用服务端标记为缓存口径完整的请求，缺失和零分母均保持横线。 */
function cacheHitRate(summary: ConversationUsageSummary): string | undefined {
  if (summary.cacheCompleteRequestCount === 0 || summary.cacheCompleteInputTokens === 0)
    return undefined;
  return (
    ((summary.cacheCompleteReadTokens / summary.cacheCompleteInputTokens) * 100).toFixed(1) + "%"
  );
}

/** 单行同时携带覆盖范围，缺失计量不因聚合 SQL 的零值误导用户。 */
function UsageMetricRow({
  label,
  value,
  covered,
  requestCount,
}: {
  label: string;
  value: number;
  covered: number;
  requestCount: number;
}): ReactElement {
  return (
    <div className="ja-context-usage-popover__row">
      <span>{label}</span>
      <span className="ja-context-usage-popover__value">
        {covered > 0 ? value.toLocaleString("en-US") : "—"}
        {covered > 0 && covered < requestCount ? <small>部分数据</small> : null}
      </span>
    </div>
  );
}

/**
 * 圆环仍是 Composer 的唯一入口。浮层只按需读取 Thread 账本，hover/focus 与点击固定共享受控
 * Popover；scope 与递增序号双重防止迟到响应跨 Thread，读取失败仅保留已确认旧值。
 */
export function ContextUsageIndicator({
  usage,
  threadId,
  runtimeGeneration,
  usageReader,
  refreshRevision,
}: ContextUsageIndicatorProps): ReactElement {
  const scopeKey = JSON.stringify([threadId ?? null, runtimeGeneration ?? null]);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const closeTimerRef = useRef<number | undefined>(undefined);
  const requestSequenceRef = useRef(0);
  const currentScopeRef = useRef(scopeKey);
  const refreshedRef = useRef<string | undefined>(undefined);
  const pinnedRef = useRef(false);
  const triggerHoveredRef = useRef(false);
  const contentHoveredRef = useRef(false);
  const [pinned, setPinned] = useState(false);
  const [transientOpen, setTransientOpen] = useState(false);
  const [state, setState] = useState<UsageLoadState>(() => {
    const summary = usageCache.get(scopeKey);
    return { scopeKey, summary, phase: summary === undefined ? "idle" : "ready" };
  });
  // Thread 切换 render 到 effect 的间隙禁止沿用旧 open 状态，避免向新 Thread 错发一次查询。
  const open = state.scopeKey === scopeKey && (pinned || transientOpen);
  const visibleState =
    state.scopeKey === scopeKey
      ? state
      : { scopeKey, summary: usageCache.get(scopeKey), phase: "idle" as const };

  /** 关闭统一取消固定与临时展开，外部点击、Escape 与再次点击均不留下隐藏刷新。 */
  const close = useCallback((): void => {
    pinnedRef.current = false;
    setPinned(false);
    setTransientOpen(false);
  }, []);
  /** 进入入口或浮层取消离开计时，允许用户横移并选择数字。 */
  const keepOpen = useCallback((): void => {
    if (closeTimerRef.current !== undefined) window.clearTimeout(closeTimerRef.current);
    closeTimerRef.current = undefined;
    setTransientOpen(true);
  }, []);
  /** hover/focus 离开只在两个区域均不再交互时关闭，点击固定不受该计时影响。 */
  const scheduleClose = useCallback((): void => {
    if (closeTimerRef.current !== undefined) window.clearTimeout(closeTimerRef.current);
    closeTimerRef.current = window.setTimeout(() => {
      const active = document.activeElement;
      const focused =
        active !== null &&
        (triggerRef.current?.contains(active) || contentRef.current?.contains(active));
      if (
        !pinnedRef.current &&
        !triggerHoveredRef.current &&
        !contentHoveredRef.current &&
        !focused
      )
        setTransientOpen(false);
    }, HOVER_CLOSE_DELAY_MS);
  }, []);

  /** scope 改变立即失效旧 promise 与展开展示，切换会话不会短暂泄露前一账本。 */
  useEffect(() => {
    currentScopeRef.current = scopeKey;
    requestSequenceRef.current += 1;
    refreshedRef.current = undefined;
    pinnedRef.current = false;
    setPinned(false);
    setTransientOpen(false);
    const summary = usageCache.get(scopeKey);
    setState({ scopeKey, summary, phase: summary === undefined ? "idle" : "ready" });
  }, [scopeKey]);
  /** 卸载清除计时和异步资格，重启后的旧 Composer 不可写回状态。 */
  useEffect(
    () => () => {
      requestSequenceRef.current += 1;
      if (closeTimerRef.current !== undefined) window.clearTimeout(closeTimerRef.current);
    },
    [],
  );

  /** 只读调用 App Server 聚合而不读取 Timeline；失败仅显示陈旧性并保留缓存。 */
  const refresh = useCallback((): void => {
    if (threadId === undefined) return;
    const cached = usageCache.get(scopeKey);
    if (usageReader === undefined) {
      setState((current) =>
        current.scopeKey === scopeKey
          ? {
              scopeKey,
              summary: current.summary ?? cached,
              phase: cached === undefined ? "unavailable" : "stale",
            }
          : current,
      );
      return;
    }
    const requestSequence = ++requestSequenceRef.current;
    setState((current) =>
      current.scopeKey === scopeKey
        ? { scopeKey, summary: current.summary ?? cached, phase: "loading" }
        : current,
    );
    void usageReader.read({ threadId }).then(
      (summary) => {
        if (
          requestSequenceRef.current !== requestSequence ||
          currentScopeRef.current !== scopeKey ||
          summary.threadId !== threadId
        )
          return;
        rememberUsage(scopeKey, summary);
        setState({ scopeKey, summary, phase: "ready" });
      },
      () => {
        if (requestSequenceRef.current !== requestSequence || currentScopeRef.current !== scopeKey)
          return;
        const retained = usageCache.get(scopeKey);
        setState({
          scopeKey,
          summary: retained,
          phase: retained === undefined ? "unavailable" : "stale",
        });
      },
    );
  }, [scopeKey, threadId, usageReader]);
  /** 首次展开以及展开期间的结算/恢复版本变化才刷新，关闭后不轮询。 */
  useEffect(() => {
    if (!open) {
      refreshedRef.current = undefined;
      return;
    }
    const key = scopeKey + ":" + (refreshRevision ?? "initial");
    if (refreshedRef.current !== key) {
      refreshedRef.current = key;
      refresh();
    }
  }, [open, refresh, refreshRevision, scopeKey]);

  const summary = visibleState.summary;
  const knownContext = usage?.certainty === "known";
  const contextLabel =
    knownContext && usage !== undefined
      ? "已使用 " +
        usage.percentage.toFixed(1) +
        "%，" +
        formatContextCapacity(usage.usedTokens) +
        " / " +
        formatContextCapacity(usage.limitTokens) +
        " tokens，最近模型请求"
      : "上下文使用量待确认";
  const ratePartial =
    summary !== undefined &&
    summary.cacheCompleteRequestCount > 0 &&
    summary.cacheCompleteRequestCount < summary.requestCount;
  const noSummaryMessage =
    visibleState.phase === "idle"
      ? "发送消息后显示用量"
      : visibleState.phase === "loading"
        ? "正在读取用量"
        : "用量暂不可用";

  return (
    <Popover open={open} onOpenChange={(nextOpen) => (nextOpen ? keepOpen() : close())}>
      <PopoverTrigger asChild>
        <button
          ref={triggerRef}
          type="button"
          className="ja-context-usage-trigger"
          aria-label="上下文用量详情"
          aria-haspopup="dialog"
          aria-expanded={open}
          onPointerEnter={() => {
            triggerHoveredRef.current = true;
            keepOpen();
          }}
          onPointerLeave={() => {
            triggerHoveredRef.current = false;
            scheduleClose();
          }}
          onFocus={keepOpen}
          onBlur={scheduleClose}
          onClick={(event) => {
            event.preventDefault();
            if (pinnedRef.current) close();
            else {
              pinnedRef.current = true;
              setPinned(true);
              keepOpen();
            }
          }}
        >
          <span
            className="ja-context-usage"
            data-tone={knownContext && usage !== undefined ? usage.tone : "unknown"}
            role={knownContext ? "progressbar" : "img"}
            aria-label={knownContext ? "上下文使用量" : "上下文使用量待确认"}
            aria-valuemin={knownContext ? 0 : undefined}
            aria-valuemax={knownContext ? 100 : undefined}
            aria-valuenow={knownContext && usage !== undefined ? usage.percentage : undefined}
            aria-valuetext={knownContext ? contextLabel : undefined}
          >
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <circle className="ja-context-usage__track" cx="10" cy="10" r="7.5" />
              {knownContext && usage !== undefined ? (
                <circle
                  className="ja-context-usage__value"
                  cx="10"
                  cy="10"
                  r="7.5"
                  pathLength="100"
                  strokeDasharray="100"
                  strokeDashoffset={100 - usage.ringPercentage}
                />
              ) : null}
            </svg>
            {knownContext ? null : (
              <span className="ja-context-usage__unknown-mark" aria-hidden="true" />
            )}
          </span>
        </button>
      </PopoverTrigger>
      <PopoverContent
        className="ja-context-usage-popover"
        side="top"
        align="end"
        sideOffset={8}
        collisionPadding={12}
        aria-label="上下文用量详情"
        onOpenAutoFocus={(event) => event.preventDefault()}
        onEscapeKeyDown={(event) => {
          event.preventDefault();
          close();
          window.requestAnimationFrame(() => triggerRef.current?.focus());
        }}
      >
        <div
          ref={contentRef}
          onPointerEnter={() => {
            contentHoveredRef.current = true;
            keepOpen();
          }}
          onPointerLeave={() => {
            contentHoveredRef.current = false;
            scheduleClose();
          }}
          onBlur={scheduleClose}
        >
          {summary === undefined ? (
            <p className="ja-context-usage-popover__empty">{noSummaryMessage}</p>
          ) : summary.requestCount === 0 ? (
            <p className="ja-context-usage-popover__empty">发送消息后显示用量</p>
          ) : (
            <>
              <section className="ja-context-usage-popover__section" aria-label="Token 本会话">
                <h2>Token · 本会话</h2>
                <UsageMetricRow
                  label="输入（未缓存）"
                  value={summary.newInputTokens}
                  covered={summary.newInputRequestCount}
                  requestCount={summary.requestCount}
                />
                <UsageMetricRow
                  label="输出"
                  value={summary.outputTokens}
                  covered={summary.outputRequestCount}
                  requestCount={summary.requestCount}
                />
                <UsageMetricRow
                  label="缓存读取"
                  value={summary.cacheReadTokens}
                  covered={summary.cacheReadRequestCount}
                  requestCount={summary.requestCount}
                />
                {summary.cacheWriteTokens > 0 ? (
                  <UsageMetricRow
                    label="缓存写入"
                    value={summary.cacheWriteTokens}
                    covered={summary.cacheWriteRequestCount}
                    requestCount={summary.requestCount}
                  />
                ) : null}
                <UsageMetricRow
                  label="总计"
                  value={summary.totalTokens}
                  covered={summary.totalRequestCount}
                  requestCount={summary.requestCount}
                />
                <div className="ja-context-usage-popover__row">
                  <span>缓存命中率</span>
                  <span className="ja-context-usage-popover__value">
                    {cacheHitRate(summary) ?? "—"}
                    {ratePartial ? <small>基于已报告数据</small> : null}
                  </span>
                </div>
              </section>
              <section className="ja-context-usage-popover__section" aria-label="上下文 最近请求">
                <h2>上下文 · 最近请求</h2>
                <div className="ja-context-usage-popover__row">
                  <span>上下文</span>
                  <span className="ja-context-usage-popover__value">
                    {knownContext && usage !== undefined ? usage.percentage.toFixed(1) + "%" : "—"}
                  </span>
                </div>
                <div className="ja-context-usage-popover__row">
                  <span>已用</span>
                  <span className="ja-context-usage-popover__value">
                    {knownContext && usage !== undefined
                      ? formatContextCapacity(usage.usedTokens) +
                        " / " +
                        formatContextCapacity(usage.limitTokens)
                      : "—"}
                  </span>
                </div>
              </section>
            </>
          )}
          {visibleState.phase === "stale" ? (
            <p className="ja-context-usage-popover__status" role="status">
              暂未更新
            </p>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  );
}
