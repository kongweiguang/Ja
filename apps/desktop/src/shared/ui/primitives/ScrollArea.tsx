// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as ScrollAreaPrimitive from "@radix-ui/react-scroll-area";
import type { ComponentPropsWithoutRef, ReactElement } from "react";
import { cn } from "./cn";

/**
 * 非虚拟化长面板统一通过 Radix 保持键盘、滚轮与跨 WebView 滚动条语义；需要直接读取
 * scrollTop 或虚拟化几何的 feature 应继续持有原生 viewport，避免公共层隐藏关键坐标系。
 */
export function ScrollArea({
  className,
  children,
  ...props
}: ComponentPropsWithoutRef<typeof ScrollAreaPrimitive.Root>): ReactElement {
  return (
    <ScrollAreaPrimitive.Root {...props} className={cn("ja-scroll-area", className)}>
      <ScrollAreaPrimitive.Viewport className="ja-scroll-area-viewport">
        {children}
      </ScrollAreaPrimitive.Viewport>
      <ScrollAreaPrimitive.Scrollbar orientation="vertical" className="ja-scrollbar">
        <ScrollAreaPrimitive.Thumb className="ja-scrollbar-thumb" />
      </ScrollAreaPrimitive.Scrollbar>
      <ScrollAreaPrimitive.Scrollbar orientation="horizontal" className="ja-scrollbar">
        <ScrollAreaPrimitive.Thumb className="ja-scrollbar-thumb" />
      </ScrollAreaPrimitive.Scrollbar>
      <ScrollAreaPrimitive.Corner />
    </ScrollAreaPrimitive.Root>
  );
}
