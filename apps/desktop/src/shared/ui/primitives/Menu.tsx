// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as MenuPrimitive from "@radix-ui/react-dropdown-menu";
import type { ComponentPropsWithoutRef, ReactElement } from "react";
import { cn } from "./cn";

export const Menu = MenuPrimitive.Root;
export const MenuTrigger = MenuPrimitive.Trigger;
export const MenuLabel = MenuPrimitive.Label;
export const MenuSeparator = MenuPrimitive.Separator;
export const MenuSub = MenuPrimitive.Sub;
export const MenuRadioGroup = MenuPrimitive.RadioGroup;
export const MenuItemIndicator = MenuPrimitive.ItemIndicator;

/** 菜单浮层统一 Portal、碰撞留白与堆叠层，避免密集工作台中的局部容器裁切。 */
export function MenuContent({
  className,
  sideOffset = 6,
  collisionPadding = 8,
  ...props
}: ComponentPropsWithoutRef<typeof MenuPrimitive.Content>): ReactElement {
  return (
    <MenuPrimitive.Portal>
      <MenuPrimitive.Content
        {...props}
        className={cn("ja-floating-surface ja-menu-content", className)}
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
      />
    </MenuPrimitive.Portal>
  );
}

/** 菜单项固定使用共享高亮与焦点语义，领域层只提供动作和 disabled 状态。 */
export function MenuItem({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof MenuPrimitive.Item>): ReactElement {
  return <MenuPrimitive.Item {...props} className={cn("ja-menu-item", className)} />;
}

/** RadioItem 统一复用菜单行语义，使组合选择器保留原生单选状态与键盘导航。 */
export function MenuRadioItem({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof MenuPrimitive.RadioItem>): ReactElement {
  return <MenuPrimitive.RadioItem {...props} className={cn("ja-menu-item", className)} />;
}

/** 子菜单触发项沿用普通菜单的稳定焦点行，同时允许 feature 叠加领域布局样式。 */
export function MenuSubTrigger({
  className,
  ...props
}: ComponentPropsWithoutRef<typeof MenuPrimitive.SubTrigger>): ReactElement {
  return <MenuPrimitive.SubTrigger {...props} className={cn("ja-menu-item", className)} />;
}

/** 子菜单也必须经过共享 Portal 和碰撞边界，避免嵌套浮层退回 feature 自行装配。 */
export function MenuSubContent({
  className,
  sideOffset = 6,
  collisionPadding = 8,
  ...props
}: ComponentPropsWithoutRef<typeof MenuPrimitive.SubContent>): ReactElement {
  return (
    <MenuPrimitive.Portal>
      <MenuPrimitive.SubContent
        {...props}
        className={cn("ja-floating-surface ja-menu-content", className)}
        sideOffset={sideOffset}
        collisionPadding={collisionPadding}
      />
    </MenuPrimitive.Portal>
  );
}
