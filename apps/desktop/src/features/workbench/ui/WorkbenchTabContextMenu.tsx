// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { CopyX, ListX, PanelRightClose, Pencil, X } from "lucide-react";
import type { ReactElement } from "react";
import { Menu, MenuContent, MenuItem, MenuSeparator, MenuTrigger } from "@/shared/ui/primitives";

export interface WorkbenchTabContextMenuProps {
  readonly label: string;
  readonly x: number;
  readonly y: number;
  readonly busy: boolean;
  readonly canRename: boolean;
  readonly canCloseOthers: boolean;
  readonly canCloseRight: boolean;
  readonly onOpenChange: (open: boolean) => void;
  readonly onRestoreFocus: () => void;
  readonly onRename: () => void;
  readonly onClose: () => void;
  readonly onCloseOthers: () => void;
  readonly onCloseRight: () => void;
  readonly onCloseAll: () => void;
}

/**
 * 右键菜单用固定的零尺寸 Radix Trigger 锚定指针坐标，使鼠标和键盘入口共用
 * Radix 的视口碰撞、焦点漫游与 Portal 语义，而不让普通左击 Tab 意外打开菜单。
 */
export function WorkbenchTabContextMenu({
  label,
  x,
  y,
  busy,
  canRename,
  canCloseOthers,
  canCloseRight,
  onOpenChange,
  onRestoreFocus,
  onRename,
  onClose,
  onCloseOthers,
  onCloseRight,
  onCloseAll,
}: WorkbenchTabContextMenuProps): ReactElement {
  return (
    <Menu open onOpenChange={onOpenChange} modal={false}>
      <MenuTrigger asChild>
        <button
          type="button"
          tabIndex={-1}
          aria-hidden="true"
          className="ja-workbench-tab-context-anchor"
          style={{ left: x, top: y }}
        />
      </MenuTrigger>
      <MenuContent
        className="ja-workbench-tab-context-menu"
        align="start"
        side="bottom"
        sideOffset={2}
        aria-label={`${label} 标签页操作`}
        aria-labelledby={undefined}
        onEscapeKeyDown={() => {
          window.requestAnimationFrame(onRestoreFocus);
        }}
        onCloseAutoFocus={(event) => {
          event.preventDefault();
        }}
      >
        {canRename ? (
          <>
            <MenuItem className="ja-workbench-tab-context-item" disabled={busy} onSelect={onRename}>
              <Pencil aria-hidden="true" />
              <span>重命名</span>
              <kbd aria-hidden="true">F2</kbd>
            </MenuItem>
            <MenuSeparator className="ja-workbench-add-menu-separator" />
          </>
        ) : null}
        <MenuItem className="ja-workbench-tab-context-item" disabled={busy} onSelect={onClose}>
          <X aria-hidden="true" />
          <span>关闭</span>
        </MenuItem>
        <MenuItem
          className="ja-workbench-tab-context-item"
          disabled={busy || !canCloseOthers}
          onSelect={onCloseOthers}
        >
          <CopyX aria-hidden="true" />
          <span>关闭其他标签页</span>
        </MenuItem>
        <MenuItem
          className="ja-workbench-tab-context-item"
          disabled={busy || !canCloseRight}
          onSelect={onCloseRight}
        >
          <PanelRightClose aria-hidden="true" />
          <span>关闭右侧标签页</span>
        </MenuItem>
        <MenuSeparator className="ja-workbench-add-menu-separator" />
        <MenuItem className="ja-workbench-tab-context-item" disabled={busy} onSelect={onCloseAll}>
          <ListX aria-hidden="true" />
          <span>关闭全部标签页</span>
        </MenuItem>
      </MenuContent>
    </Menu>
  );
}
