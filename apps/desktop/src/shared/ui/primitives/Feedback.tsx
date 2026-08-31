// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactElement, ReactNode } from "react";
import { Button } from "./Button";
import { cn } from "./cn";

/**
 * Empty state 提供稳定 live-region 边界，使 loading 与 no-data 状态
 * 不会意外移动周围桌面布局。
 */
export function EmptyState({
  title,
  description,
  action,
  className,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  className?: string;
}): ReactElement {
  return (
    <section className={cn("ja-empty-state", className)} aria-live="polite">
      <h2>{title}</h2>
      {description ? <p>{description}</p> : null}
      {action}
    </section>
  );
}

/**
 * Loading state 只表达忙碌，不嵌入 timer 或伪进度；面对延迟未知的 sidecar
 * 操作时仍保持真实。
 */
export function LoadingState({
  label = "加载中…",
  className,
}: {
  label?: string;
  className?: string;
}): ReactElement {
  return (
    <div className={cn("ja-loading-state", className)} role="status" aria-live="polite">
      <span className="ja-loading-spinner" aria-hidden="true" />
      {label}
    </div>
  );
}

/**
 * Error state 刻意只接受 retry callback 而非 raw exception 输出，
 * 保持 IPC 边界的脱敏保证；重试复用 Button 以统一焦点和键盘语义。
 */
export function ErrorState({
  title = "加载失败",
  message,
  onRetry,
  className,
}: {
  title?: string;
  message: string;
  onRetry?: () => void;
  className?: string;
}): ReactElement {
  return (
    <section className={cn("ja-error-state", className)} role="alert">
      <h2>{title}</h2>
      <p>{message}</p>
      {onRetry ? (
        <Button type="button" variant="secondary" size="sm" onClick={onRetry}>
          重试
        </Button>
      ) : null}
    </section>
  );
}
