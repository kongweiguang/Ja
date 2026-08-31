// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as TooltipPrimitive from "@radix-ui/react-tooltip";
import type { ReactElement, ReactNode } from "react";
import { cn } from "./cn";

export interface TooltipProps {
  content: ReactNode;
  children: ReactElement;
  className?: string;
  sideOffset?: number;
  delayDuration?: number;
}

/**
 * Radix 负责键盘焦点、Escape、hover 与 Portal 生命周期；Provider 留在 primitive 内，
 * 使独立 feature 与测试无需依赖 App 壳，同时仍不接受领域状态或原生回调。
 */
export function Tooltip({
  content,
  children,
  className,
  sideOffset = 6,
  delayDuration = 350,
}: TooltipProps): ReactElement {
  return (
    <TooltipPrimitive.Provider delayDuration={delayDuration} skipDelayDuration={120}>
      <TooltipPrimitive.Root>
        <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
        <TooltipPrimitive.Portal>
          <TooltipPrimitive.Content
            className={cn("ja-floating-surface ja-tooltip-content", className)}
            sideOffset={sideOffset}
            collisionPadding={8}
          >
            {content}
            <TooltipPrimitive.Arrow className="ja-tooltip-arrow" />
          </TooltipPrimitive.Content>
        </TooltipPrimitive.Portal>
      </TooltipPrimitive.Root>
    </TooltipPrimitive.Provider>
  );
}
