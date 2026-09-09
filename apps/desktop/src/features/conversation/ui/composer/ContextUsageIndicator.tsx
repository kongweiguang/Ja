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
  if (usage.certainty === "unknown") {
    return (
      <Tooltip
        delayDuration={220}
        sideOffset={8}
        className="ja-context-usage-tooltip"
        content={
          <div className="ja-context-usage-tooltip__content">最近一次模型请求的 Token 用量未知</div>
        }
      >
        <span
          className="ja-context-usage"
          data-tone="warning"
          role="status"
          aria-label="上下文使用量未知"
          tabIndex={0}
        >
          <span aria-hidden="true">?</span>
        </span>
      </Tooltip>
    );
  }
  const used = formatTokenCount(usage.usedTokens);
  const limit = formatTokenCount(usage.limitTokens);
  const valueNow = Math.min(100, Math.max(0, usage.percentage));
  const sourceLabel = usage.source === "compaction" ? "压缩后计量" : "最近模型请求";
  const valueText = `已使用 ${usage.percentage}%，${used} / ${limit} tokens，${sourceLabel}`;

  return (
    <Tooltip
      delayDuration={220}
      sideOffset={8}
      className="ja-context-usage-tooltip"
      content={
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
      }
    >
      <span
        className="ja-context-usage"
        data-tone={usage.tone}
        role="progressbar"
        aria-label="上下文使用量"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={valueNow}
        aria-valuetext={valueText}
        tabIndex={0}
      >
        <svg viewBox="0 0 20 20" aria-hidden="true">
          <circle className="ja-context-usage__track" cx="10" cy="10" r="7.5" />
          <circle
            className="ja-context-usage__value"
            cx="10"
            cy="10"
            r="7.5"
            pathLength="100"
            strokeDasharray="100"
            strokeDashoffset={100 - usage.ringPercentage}
          />
        </svg>
      </span>
    </Tooltip>
  );
}
