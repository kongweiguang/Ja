// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as CollapsiblePrimitive from "@radix-ui/react-collapsible";
import type { ComponentPropsWithoutRef, ReactElement, ReactNode } from "react";

/**
 * Radix primitive 为未来 timeline/tool group 提供 expanded 状态与键盘切换，
 * 同时不强制内容实现策略。
 */
export function Collapsible(
  props: ComponentPropsWithoutRef<typeof CollapsiblePrimitive.Root>,
): ReactElement {
  return <CollapsiblePrimitive.Root {...props} />;
}

/** 复用 Radix Trigger 的受控开关与键盘语义，使调用方无需重复维护 `aria-expanded` 和关联状态。 */
export function CollapsibleTrigger(
  props: ComponentPropsWithoutRef<typeof CollapsiblePrimitive.Trigger>,
): ReactElement {
  return <CollapsiblePrimitive.Trigger {...props} />;
}

/** Content 只负责组合子内容并保留 Radix 生成的 ARIA 关联，具体展示结构继续由领域 UI 决定。 */
export function CollapsibleContent({
  children,
  ...props
}: ComponentPropsWithoutRef<typeof CollapsiblePrimitive.Content> & {
  children?: ReactNode;
}): ReactElement {
  return <CollapsiblePrimitive.Content {...props}>{children}</CollapsiblePrimitive.Content>;
}
