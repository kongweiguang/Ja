// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { CopyX, ListX, PanelRightClose, Pencil, X } from "lucide-react";
import type { ReactElement } from "react";
import { MenuItem, MenuSeparator, PointerContextMenu } from "@/shared/ui/primitives";

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
 * 标签专用动作仍由 Workbench owner 提供；共享定位容器统一视口碰撞、键盘漫游和
 * Escape 焦点恢复，避免顶层菜单与文件功能出现两套右键行为。
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
    <PointerContextMenu
      x={x}
      y={y}
      label={`${label} 标签页操作`}
      onOpenChange={onOpenChange}
      onRestoreFocus={onRestoreFocus}
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
    </PointerContextMenu>
  );
}
