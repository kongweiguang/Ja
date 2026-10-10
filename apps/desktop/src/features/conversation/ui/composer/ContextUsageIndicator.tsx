// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
  type ReactElement,
} from "react";
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
  /** Hidden retained Composers preserve display state but suspend aggregate reads. */
  active?: boolean;
  /** 圆环投影最近请求；累计数据只在展开层出现，两种口径不可互相替代。 */
  usage?: ContextUsagePresentation;
  /** 查询与缓存必须绑定 Thread 和 runtime 代际，拒绝重连后的迟到响应。 */
  threadId?: string;
  runtimeGeneration?: number;
  usageReader?: ConversationUsageReader;
  /** 仅由结算、恢复或上下文身份变化推进；浮层关闭时绝不因此轮询。 */
  refreshRevision?: string | number;
}

/** 窗口容量与累计 Token 使用同一紧凑单位，避免用户在两种口径间切换阅读规则。 */
function formatContextCapacity(value: number): string {
  return formatTokenCount(value);
}

/** 累计 Token 采用 pi 的稳定阈值，避免大数撑宽浮层，同时保留低数值的可读精度。 */
function formatTokenCount(value: number): string {
  if (value < 1_000) return value.toString();
  if (value < 10_000) return (value / 1_000).toFixed(1).replace(/\.0$/u, "") + "k";
  if (value < 1_000_000) return Math.round(value / 1_000) + "k";
  if (value < 10_000_000) return (value / 1_000_000).toFixed(1).replace(/\.0$/u, "") + "M";
  return Math.round(value / 1_000_000) + "M";
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

/** 请求行可能没有 Provider 用量字段；此时不要堆叠一列未知占位符。 */
function hasReportedUsage(summary: ConversationUsageSummary): boolean {
  return [
    summary.newInputRequestCount,
    summary.outputRequestCount,
    summary.totalRequestCount,
    summary.cacheReadRequestCount,
    summary.cacheWriteRequestCount,
  ].some((covered) => covered > 0);
}

/** 命中率只用服务端归一化的完整输入样本；缺失和零分母均保持横线。 */
function cacheHitRate(summary: ConversationUsageSummary): string | undefined {
  if (summary.cacheCompleteRequestCount === 0 || summary.cacheCompleteInputTokens === 0)
    return undefined;
  return (
    ((summary.cacheCompleteReadTokens / summary.cacheCompleteInputTokens) * 100).toFixed(1) + "%"
  );
}

/** 总量与输出必须覆盖全部已计量请求才能相减；相同的部分覆盖数量不能证明来自同一组请求。 */
function cumulativeInputTokens(summary: ConversationUsageSummary): number | undefined {
  if (
    summary.measuredRequestCount === 0 ||
    summary.totalRequestCount !== summary.measuredRequestCount ||
    summary.outputRequestCount !== summary.measuredRequestCount ||
    summary.totalTokens < summary.outputTokens
  )
    return undefined;
  return summary.totalTokens - summary.outputTokens;
}

/** 单行同时携带覆盖范围；大数使用紧凑单位，缺失计量不因聚合 SQL 的零值误导用户。 */
function UsageMetricRow({
  label,
  value,
  covered,
}: {
  label: string;
  value: number;
  covered: number;
}): ReactElement {
  return (
    <div className="ja-context-usage-popover__row">
      <span>{label}</span>
      <span className="ja-context-usage-popover__value">
        {covered > 0 ? formatTokenCount(value) : "—"}
      </span>
    </div>
  );
}

/**
 * 圆环仍是 Composer 的唯一入口。浮层只按需读取 Thread 账本，hover/focus 与点击固定共享受控
 * Popover；累计输入包含缓存，父总量与缓存明细不重复相加。scope 与递增序号防止迟到响应跨 Thread。
 * 缓存写入按计量覆盖决定是否展示，保留明确报告的零，避免与未报告字段混淆。
 */
export function ContextUsageIndicator({
  active = true,
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
  const restoreFocusOnCloseRef = useRef(false);
  const restoringFocusRef = useRef(false);
  const triggerHoveredRef = useRef(false);
  const contentHoveredRef = useRef(false);
  const [pinned, setPinned] = useState(false);
  const [transientOpen, setTransientOpen] = useState(false);
  const [state, setState] = useState<UsageLoadState>(() => {
    const summary = usageCache.get(scopeKey);
    return { scopeKey, summary, phase: summary === undefined ? "idle" : "ready" };
  });
  // Thread 切换 render 到复位微任务的间隙禁止沿用旧 open 状态，避免向新 Thread 错发一次查询。
  const open = state.scopeKey === scopeKey && (pinned || transientOpen);
  const visibleState =
    state.scopeKey === scopeKey
      ? state
      : { scopeKey, summary: usageCache.get(scopeKey), phase: "idle" as const };

  /** 只由 Escape 请求归还入口焦点；悬停或外部点击关闭时保留用户当前焦点。 */
  const close = useCallback((restoreTriggerFocus = false): void => {
    pinnedRef.current = false;
    restoreFocusOnCloseRef.current = restoreTriggerFocus;
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
        close();
    }, HOVER_CLOSE_DELAY_MS);
  }, [close]);

  /** Portal 覆盖或退出会重复投递 enter；同一入口区域内的悬停只开启一次。 */
  const handleTriggerPointerEnter = useCallback((): void => {
    if (triggerHoveredRef.current) return;
    triggerHoveredRef.current = true;
    keepOpen();
  }, [keepOpen]);

  /** 入口与浮层退出共用入口区域判定，避免 Portal 覆盖造成虚假离开或残留悬停。 */
  const handleTriggerPointerLeave = useCallback(
    (event: ReactPointerEvent<HTMLElement>): void => {
      const rect = triggerRef.current?.getBoundingClientRect();
      if (
        rect !== undefined &&
        rect.width > 0 &&
        rect.height > 0 &&
        event.clientX >= rect.left &&
        event.clientX < rect.right &&
        event.clientY >= rect.top &&
        event.clientY < rect.bottom
      )
        return;
      triggerHoveredRef.current = false;
      scheduleClose();
    },
    [scheduleClose],
  );

  /** 浮层覆盖入口后真实移动只产生浮层 leave，也要结束已离开的入口悬停会话。 */
  const handleContentPointerLeave = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>): void => {
      contentHoveredRef.current = false;
      handleTriggerPointerLeave(event);
    },
    [handleTriggerPointerLeave],
  );

  /** 只忽略同步归还焦点这一回事件，不拦截下一次真实键盘聚焦。 */
  const handleTriggerFocus = useCallback((): void => {
    if (!restoringFocusRef.current) keepOpen();
  }, [keepOpen]);

  /** 在 Radix 卸载后统一恢复 Escape 焦点，避免独立 rAF 与默认恢复相互重开浮层。 */
  const handleCloseAutoFocus = useCallback((event: Event): void => {
    event.preventDefault();
    const restoreTriggerFocus = restoreFocusOnCloseRef.current;
    restoreFocusOnCloseRef.current = false;
    if (!restoreTriggerFocus) return;
    restoringFocusRef.current = true;
    try {
      triggerRef.current?.focus({ preventScroll: true });
    } finally {
      restoringFocusRef.current = false;
    }
  }, []);

  /** Escape 先关闭，实际焦点回归交给 Content 的卸载生命周期负责。 */
  const handleEscapeKeyDown = useCallback(
    (event: KeyboardEvent): void => {
      event.preventDefault();
      close(true);
    },
    [close],
  );

  /** scope 切换先使旧异步请求失格，再在 commit 后复位交互状态，避免 effect 同步级联 render。 */
  useEffect(() => {
    currentScopeRef.current = scopeKey;
    requestSequenceRef.current += 1;
    refreshedRef.current = undefined;
    pinnedRef.current = false;
    restoreFocusOnCloseRef.current = false;
    let cancelled = false;
    void Promise.resolve().then(() => {
      if (cancelled) return;
      setPinned(false);
      setTransientOpen(false);
      const summary = usageCache.get(scopeKey);
      setState({ scopeKey, summary, phase: summary === undefined ? "idle" : "ready" });
    });
    return () => {
      cancelled = true;
    };
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
    if (!active || threadId === undefined) return;
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
  }, [active, scopeKey, threadId, usageReader]);
  /** 首次展开以及展开期间的结算/恢复版本变化才刷新，关闭后不轮询。 */
  useEffect(() => {
    if (!active) {
      refreshedRef.current = undefined;
      requestSequenceRef.current += 1;
      pinnedRef.current = false;
      triggerHoveredRef.current = false;
      contentHoveredRef.current = false;
      restoreFocusOnCloseRef.current = false;
      if (closeTimerRef.current !== undefined) window.clearTimeout(closeTimerRef.current);
      closeTimerRef.current = undefined;
      setPinned(false);
      setTransientOpen(false);
      return;
    }
    if (!open) {
      refreshedRef.current = undefined;
      return;
    }
    const key = scopeKey + ":" + (refreshRevision ?? "initial");
    if (refreshedRef.current !== key) {
      refreshedRef.current = key;
      let cancelled = false;
      // 读取在 commit 后的微任务启动，既保留展开时机，又避免 effect 内同步状态级联。
      void Promise.resolve().then(() => {
        if (!cancelled) refresh();
      });
      return () => {
        cancelled = true;
      };
    }
  }, [active, open, refresh, refreshRevision, scopeKey]);

  const summary = visibleState.summary;
  const inputTokens = summary === undefined ? undefined : cumulativeInputTokens(summary);
  const knownContext = usage !== undefined && usage.certainty !== "unknown";
  const percentageValue =
    knownContext && usage !== undefined
      ? usage.usedTokens > 0 && usage.percentage === 0
        ? "<0.1%"
        : usage.percentage.toFixed(1) + "%"
      : "待确认";
  const contextPercentage =
    knownContext && usage?.certainty === "estimated" ? "约 " + percentageValue : percentageValue;
  const contextLabel =
    knownContext && usage !== undefined
      ? (usage.certainty === "estimated" ? "当前上下文约 " : "当前上下文 ") +
        percentageValue +
        "，" +
        formatContextCapacity(usage.usedTokens) +
        " / " +
        formatContextCapacity(usage.limitTokens) +
        " tokens，" +
        (usage.certainty === "estimated" ? "本地估算" : "Provider 完整用量")
      : "上下文使用量待确认";
  const ratePartial =
    summary !== undefined &&
    summary.cacheCompleteRequestCount > 0 &&
    summary.cacheCompleteRequestCount < summary.requestCount;
  const cacheRate = summary === undefined ? undefined : cacheHitRate(summary);
  const hasUnmeasuredRequests =
    summary !== undefined && summary.measuredRequestCount < summary.requestCount;
  const hasPartialUsage =
    summary !== undefined &&
    [
      summary.newInputRequestCount,
      summary.outputRequestCount,
      summary.totalRequestCount,
      summary.cacheReadRequestCount,
      summary.cacheWriteRequestCount,
      summary.cacheCompleteRequestCount,
    ].some((covered) => covered > 0 && covered < summary.requestCount);
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
          onPointerEnter={handleTriggerPointerEnter}
          onPointerLeave={handleTriggerPointerLeave}
          onFocus={handleTriggerFocus}
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
        onCloseAutoFocus={handleCloseAutoFocus}
        onEscapeKeyDown={handleEscapeKeyDown}
      >
        <div
          ref={contentRef}
          onPointerEnter={() => {
            contentHoveredRef.current = true;
            keepOpen();
          }}
          onPointerLeave={handleContentPointerLeave}
          onBlur={scheduleClose}
        >
          <section className="ja-context-usage-popover__section" aria-label="当前上下文">
            <h2>
              {usage?.certainty === "estimated"
                ? "当前上下文 · 本地估算"
                : usage?.certainty === "known"
                  ? "当前上下文 · Provider 实测"
                  : "当前上下文"}
            </h2>
            <div className="ja-context-usage-popover__row">
              <span>上下文</span>
              <span className="ja-context-usage-popover__value">{contextPercentage}</span>
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
            <p className="ja-context-usage-popover__note">
              最近请求的占用，估算校正或压缩后可能降低。
            </p>
          </section>
          <section className="ja-context-usage-popover__section" aria-label="Token 本会话">
            <h2>Token · 本会话</h2>
            {summary === undefined ? (
              <p className="ja-context-usage-popover__empty">{noSummaryMessage}</p>
            ) : summary.requestCount === 0 ? (
              <p className="ja-context-usage-popover__empty">发送消息后显示用量</p>
            ) : !hasReportedUsage(summary) ? (
              <p className="ja-context-usage-popover__empty">尚无已报告的 Provider 用量</p>
            ) : (
              <>
                <UsageMetricRow
                  label="输入（含缓存）"
                  value={inputTokens ?? 0}
                  covered={inputTokens === undefined ? 0 : summary.measuredRequestCount}
                />
                <UsageMetricRow
                  label="输出"
                  value={summary.outputTokens}
                  covered={summary.outputRequestCount}
                />
                <UsageMetricRow
                  label="含缓存读取"
                  value={summary.cacheReadTokens}
                  covered={summary.cacheReadRequestCount}
                />
                {summary.cacheWriteRequestCount > 0 ? (
                  <UsageMetricRow
                    label="含缓存写入"
                    value={summary.cacheWriteTokens}
                    covered={summary.cacheWriteRequestCount}
                  />
                ) : null}
                <UsageMetricRow
                  label="总计（输入 + 输出）"
                  value={summary.totalTokens}
                  covered={summary.totalRequestCount}
                />
                <div className="ja-context-usage-popover__row ja-context-usage-popover__row--rate">
                  <span>缓存命中率</span>
                  <span
                    className="ja-context-usage-popover__value"
                    data-rate-known={cacheRate !== undefined}
                  >
                    {cacheRate ?? "—"}
                  </span>
                </div>
                <p className="ja-context-usage-popover__note">缓存已计入输入和总计，不重复累加。</p>
                {hasPartialUsage || ratePartial || hasUnmeasuredRequests ? (
                  <p className="ja-context-usage-popover__status">
                    {hasUnmeasuredRequests
                      ? "累计仅含已报告用量，仍有请求未上报用量。"
                      : "部分请求未上报全部用量字段"}
                  </p>
                ) : null}
              </>
            )}
          </section>
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
