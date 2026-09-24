// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { ReactElement, ReactNode } from "react";
import { Menu, MenuContent, MenuTrigger } from "./Menu";

export interface PointerContextMenuProps {
  readonly x: number;
  readonly y: number;
  readonly label: string;
  readonly onOpenChange: (open: boolean) => void;
  readonly onRestoreFocus: () => void;
  readonly className?: string;
  readonly children: ReactNode;
}

/**
 * 右键面板只保留指针坐标和关闭回调，把碰撞、漫游和 Portal 交给 Radix，
 * 避免每个 feature 再维护一份易失焦且容易越界的手工菜单实现。
 */
export function PointerContextMenu({
  x,
  y,
  label,
  onOpenChange,
  onRestoreFocus,
  className,
  children,
}: PointerContextMenuProps): ReactElement {
  return (
    <Menu open onOpenChange={onOpenChange} modal={false}>
      <MenuTrigger asChild>
        <button
          type="button"
          tabIndex={-1}
          aria-hidden="true"
          className="ja-pointer-context-anchor"
          style={{ left: x, top: y }}
        />
      </MenuTrigger>
      <MenuContent
        className={
          className === undefined
            ? "ja-pointer-context-menu"
            : `ja-pointer-context-menu ${className}`
        }
        align="start"
        side="bottom"
        sideOffset={2}
        aria-label={label}
        aria-labelledby={undefined}
        onEscapeKeyDown={() => window.requestAnimationFrame(onRestoreFocus)}
        onCloseAutoFocus={(event) => event.preventDefault()}
        onContextMenu={(event) => {
          event.preventDefault();
          event.stopPropagation();
        }}
      >
        {children}
      </MenuContent>
    </Menu>
  );
}
