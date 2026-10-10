// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronRight, Lightbulb, X } from "lucide-react";
import { useState, type ReactElement } from "react";
import { Popover, PopoverArrow, PopoverContent, PopoverTrigger } from "@/shared/ui/primitives";
import type { CollaborationMode } from "../domain/planModel";
import "./plans.css";

export interface ComposerPlanStatusProps {
  readonly mode: CollaborationMode;
  readonly busy?: boolean;
  readonly onOpenPlan?: () => void;
  readonly onDisablePlan?: () => void | Promise<void>;
}

/** 默认对话不占 Composer 空间；仅在计划模式显示可回到持久 Plan 的入口。 */
export function ComposerPlanStatus({
  mode,
  busy = false,
  onOpenPlan,
  onDisablePlan,
}: ComposerPlanStatusProps): ReactElement | null {
  const [open, setOpen] = useState(false);
  const [actionError, setActionError] = useState<string>();
  if (mode === "default") return null;

  /** 偏好设置的确认回执决定关闭边界；变更失败时可在原位置重试。 */
  const disablePlan = (): void => {
    if (onDisablePlan === undefined || busy) return;
    setActionError(undefined);
    void Promise.resolve(onDisablePlan()).then(
      () => setOpen(false),
      () => setActionError("计划模式未关闭，请重试。"),
    );
  };

  /** 跳转到已保存的 Plan 前先关闭临时界面。 */
  const openPlan = (): void => {
    setOpen(false);
    onOpenPlan?.();
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) setActionError(undefined);
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          className="ja-composer-plan-status"
          data-plan-ui="mode-status"
          data-kind="plan"
          aria-label="计划状态：已开启"
        >
          <Lightbulb aria-hidden="true" />
          <span>计划</span>
        </button>
      </PopoverTrigger>
      <PopoverContent
        className="ja-composer-plan-popover"
        side="top"
        align="start"
        sideOffset={8}
        aria-label="计划详情"
      >
        <header className="ja-composer-plan-popover__header">
          <Lightbulb aria-hidden="true" />
          <div>
            <strong>计划模式</strong>
            <small>已开启</small>
          </div>
        </header>
        <div className="ja-composer-plan-popover__actions">
          {onOpenPlan === undefined ? null : (
            <button type="button" onClick={openPlan}>
              查看计划
              <ChevronRight aria-hidden="true" />
            </button>
          )}
          {onDisablePlan === undefined ? null : (
            <button type="button" disabled={busy} onClick={disablePlan}>
              <X aria-hidden="true" />
              关闭计划模式
            </button>
          )}
        </div>
        {actionError === undefined ? null : <p role="alert">{actionError}</p>}
        <PopoverArrow className="ja-composer-plan-popover__arrow" aria-hidden="true" />
      </PopoverContent>
    </Popover>
  );
}
