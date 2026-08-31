// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { forwardRef, type ButtonHTMLAttributes, type ReactElement, type ReactNode } from "react";
import { cn } from "./cn";
import { Tooltip } from "./Tooltip";

export interface IconButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "aria-label" | "children"> {
  label: string;
  children: ReactNode;
  tooltip?: ReactNode | false;
}

/**
 * IconButton 强制要求可访问名称，并默认把同一名称投影为 Tooltip；组件只统一
 * 交互语义，尺寸与领域视觉继续由调用方 className 决定，避免公共层侵入布局。
 */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, tooltip = label, title, className, type = "button", children, ...props },
  ref,
): ReactElement {
  const fallbackTitle = tooltip === false ? (title ?? label) : undefined;
  const control = (
    <button
      {...props}
      ref={ref}
      type={type}
      className={cn("ja-icon-button", className)}
      aria-label={label}
      title={fallbackTitle}
    >
      {children}
    </button>
  );
  return tooltip === false ? control : <Tooltip content={tooltip}>{control}</Tooltip>;
});
