// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as PopoverPrimitive from "@radix-ui/react-popover";
import type { ComponentPropsWithoutRef, ReactElement } from "react";
import { cn } from "./cn";

export const Popover = PopoverPrimitive.Root;
export const PopoverTrigger = PopoverPrimitive.Trigger;
export const PopoverArrow = PopoverPrimitive.Arrow;

/** Popover 只承载轻量临时选择，并复用统一 surface、碰撞和 Portal 主题边界。 */
export function PopoverContent({
  className,
  sideOffset = 6,
  collisionPadding = 8,
  ...props
}: ComponentPropsWithoutRef<typeof PopoverPrimitive.Content>): ReactElement {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Content
        {...props}
        className={cn("ja-floating-surface ja-popover-content", className)}
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
      />
    </PopoverPrimitive.Portal>
  );
}
