// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactElement } from "react";
import { Tooltip } from "@/shared/ui/primitives";
import type { ContextUsagePresentation } from "../../domain/contextUsage";

export interface ContextUsageIndicatorProps {
  usage: ContextUsagePresentation;
}

/** 以紧凑的十进制 K/M 表示 Token，保留一次小数只在它确实能帮助区分量级时出现。 */
function formatTokenCount(value: number): string {
  if (value >= 1_000_000) {
    const digits = value >= 10_000_000 ? 0 : 1;
    return `${(value / 1_000_000).toFixed(digits).replace(/\.0$/u, "")}M`;
  }
  if (value >= 1_000) {
    const digits = value >= 100_000 ? 0 : 1;
    return `${(value / 1_000).toFixed(digits).replace(/\.0$/u, "")}K`;
  }
  return value.toLocaleString("en-US");
}

/**
 * 默认只呈现 28px 环形状态，hover 或键盘 focus 时才展开结构化事实；组件没有点击行为，
 * 因而使用可聚焦 progressbar 语义，避免把纯信息伪装成按钮。
 */
export function ContextUsageIndicator({ usage }: ContextUsageIndicatorProps): ReactElement {
  const known = usage.certainty === "known";
  const used = known ? formatTokenCount(usage.usedTokens) : undefined;
  const limit = known ? formatTokenCount(usage.limitTokens) : undefined;
  const valueNow = known ? Math.min(100, Math.max(0, usage.percentage)) : undefined;
  const sourceLabel = "最近模型请求";
  const valueText =
    known && used !== undefined && limit !== undefined
      ? `已使用 ${usage.percentage}%，${used} / ${limit} tokens，${sourceLabel}`
      : undefined;

  return (
    <Tooltip
      delayDuration={220}
      sideOffset={8}
      className="ja-context-usage-tooltip"
      content={
        known ? (
          <div className="ja-context-usage-tooltip__content">
            <div className="ja-context-usage-tooltip__heading">
              <span>上下文</span>
              <strong>{usage.percentage}%</strong>
            </div>
            <p>
              <strong>{used}</strong>
              <span> / {limit} tokens</span>
            </p>
            <small>{sourceLabel}</small>
          </div>
        ) : (
          <div className="ja-context-usage-tooltip__content">
            当前上下文用量尚未确认，等待下一次模型响应
          </div>
        )
      }
    >
      <span
        className="ja-context-usage"
        data-tone={known ? usage.tone : "unknown"}
        role={known ? "progressbar" : "status"}
        aria-label={known ? "上下文使用量" : "上下文使用量待确认"}
        aria-valuemin={known ? 0 : undefined}
        aria-valuemax={known ? 100 : undefined}
        aria-valuenow={valueNow}
        aria-valuetext={valueText}
        tabIndex={0}
      >
        <svg viewBox="0 0 20 20" aria-hidden="true">
          <circle className="ja-context-usage__track" cx="10" cy="10" r="7.5" />
          {known ? (
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
        {known ? null : <span className="ja-context-usage__unknown-mark" aria-hidden="true" />}
      </span>
    </Tooltip>
  );
}
