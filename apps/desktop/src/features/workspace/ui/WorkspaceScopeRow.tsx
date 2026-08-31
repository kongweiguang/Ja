// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { FolderOpen, MessageSquare } from "lucide-react";
import type { ReactElement } from "react";
import "./workspaceScope.css";

export interface WorkspaceScopeRowProps {
  readonly kind: "general" | "project";
  readonly label: string;
  readonly selected: boolean;
  readonly disabled: boolean;
  readonly accessibleLabel: string;
  readonly title: string;
  readonly statusTone?: "ready" | "busy" | "warning" | "danger" | "idle";
  readonly onSelect: () => void;
}

/**
 * 用同一行级控件呈现 general 与 project scope，避免导航分别发明“清除项目”和“选择项目”
 * 两套交互；调用方仍拥有异步切换与紧凑抽屉生命周期。
 */
export function WorkspaceScopeRow({
  kind,
  label,
  selected,
  disabled,
  accessibleLabel,
  title,
  statusTone,
  onSelect,
}: WorkspaceScopeRowProps): ReactElement {
  const Icon = kind === "general" ? MessageSquare : FolderOpen;
  return (
    <button
      type="button"
      className="ja-workspace-scope-row"
      data-scope-kind={kind}
      data-active={selected || undefined}
      aria-current={selected ? "page" : undefined}
      aria-label={accessibleLabel}
      title={title}
      disabled={disabled}
      onClick={onSelect}
    >
      <Icon aria-hidden="true" focusable="false" />
      <span>{label}</span>
      {selected && statusTone !== undefined ? (
        <span className={`ja-workspace-scope-status is-${statusTone}`} aria-hidden="true" />
      ) : null}
    </button>
  );
}
