// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as DialogPrimitive from "@radix-ui/react-dialog";
import type { ComponentPropsWithoutRef, ReactElement } from "react";
import { cn } from "./cn";

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;
export const DialogClose = DialogPrimitive.Close;
export const DialogTitle = DialogPrimitive.Title;
export const DialogDescription = DialogPrimitive.Description;

export interface DialogContentProps
  extends ComponentPropsWithoutRef<typeof DialogPrimitive.Content> {
  readonly overlayClassName?: string;
}

/**
 * 模态内容通过 Radix focus trap 与共享 overlay 组合，调用方不能遗漏遮罩、Portal 或焦点恢复。
 */
export function DialogContent({
  className,
  overlayClassName,
  ...props
}: DialogContentProps): ReactElement {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className={cn("ja-dialog-overlay", overlayClassName)} />
      <DialogPrimitive.Content
        {...props}
        className={cn("ja-floating-surface ja-dialog-content", className)}
      />
    </DialogPrimitive.Portal>
  );
}
