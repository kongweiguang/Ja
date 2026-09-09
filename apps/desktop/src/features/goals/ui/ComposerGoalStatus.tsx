// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronRight, Lightbulb, Target, X } from "lucide-react";
import { useState, type ReactElement } from "react";
import { Popover, PopoverArrow, PopoverContent, PopoverTrigger } from "@/shared/ui/primitives";
import {
  goalPhaseLabel,
  goalProgress,
  isGoalActive,
  type CollaborationMode,
  type GoalSummary,
} from "../domain/goalModel";
import "./goals.css";

export interface ComposerGoalStatusProps {
  readonly mode: CollaborationMode;
  readonly goal?: GoalSummary;
  readonly currentStepTitle?: string;
  readonly busy?: boolean;
  readonly onOpenGoal?: () => void;
  readonly onDisablePlan?: () => void | Promise<void>;
}

/**
 * 默认态不占 Composer 空间；Goal 与 Plan 同时存在时只显示 Target，详细浮层内再呈现 Plan 状态，
 * 避免把 lifecycle、协作模板和 AccessMode 混成多个常驻标签。
 */
export function ComposerGoalStatus({
  mode,
  goal,
  currentStepTitle,
  busy = false,
  onOpenGoal,
  onDisablePlan,
}: ComposerGoalStatusProps): ReactElement | null {
  const [open, setOpen] = useState(false);
  const [actionError, setActionError] = useState<string>();
  const activeGoal = goal !== undefined && isGoalActive(goal) ? goal : undefined;
  if (activeGoal === undefined && mode === "default") return null;

  const GoalIcon = activeGoal === undefined ? Lightbulb : Target;
  const label = activeGoal === undefined ? "计划" : "目标";
  const stateLabel = activeGoal === undefined ? "已开启" : goalPhaseLabel(activeGoal.phase);

  /** 关闭 Plan 必须等待偏好 ACK；失败时保留 Popover，用户可原位重试。 */
  const disablePlan = (): void => {
    if (onDisablePlan === undefined || busy) return;
    setActionError(undefined);
    void Promise.resolve(onDisablePlan()).then(
      () => setOpen(false),
      () => setActionError("计划模式未关闭，请重试。"),
    );
  };

  /** 查看详情先关闭 transient surface，再把显式导航交给 composition owner。 */
  const openGoal = (): void => {
    setOpen(false);
    onOpenGoal?.();
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
          className="ja-composer-goal-status"
          data-goal-ui="mode-status"
          data-kind={activeGoal === undefined ? "plan" : "goal"}
          aria-label={`${label}状态：${stateLabel}`}
        >
          <GoalIcon aria-hidden="true" />
          <span>{label}</span>
        </button>
      </PopoverTrigger>
      <PopoverContent
        className="ja-composer-goal-popover"
        side="top"
        align="start"
        sideOffset={8}
        aria-label={`${label}详情`}
      >
        {activeGoal === undefined ? (
          <header className="ja-composer-goal-popover__header">
            <Lightbulb aria-hidden="true" />
            <div>
              <strong>计划模式</strong>
              <small>已开启</small>
            </div>
          </header>
        ) : (
          <>
            <header className="ja-composer-goal-popover__header">
              <Target aria-hidden="true" />
              <div>
                <strong title={activeGoal.objective}>{activeGoal.objective}</strong>
                <small>{currentStepTitle ?? goalPhaseLabel(activeGoal.phase)}</small>
              </div>
            </header>
            {activeGoal.totalRequiredSteps <= 0 ? null : (
              <div
                className="ja-composer-goal-popover__progress"
                role="progressbar"
                aria-label="目标进度"
                aria-valuemin={0}
                aria-valuemax={activeGoal.totalRequiredSteps}
                aria-valuenow={activeGoal.completedRequiredSteps}
              >
                <span style={{ width: `${Math.round(goalProgress(activeGoal) * 100)}%` }} />
                <small>
                  {activeGoal.completedRequiredSteps}/{activeGoal.totalRequiredSteps}
                </small>
              </div>
            )}
          </>
        )}

        <div className="ja-composer-goal-popover__actions">
          {activeGoal === undefined || onOpenGoal === undefined ? null : (
            <button type="button" onClick={openGoal}>
              查看目标
              <ChevronRight aria-hidden="true" />
            </button>
          )}
          {mode !== "plan" || onDisablePlan === undefined ? null : (
            <button type="button" disabled={busy} onClick={disablePlan}>
              <X aria-hidden="true" />
              关闭计划模式
            </button>
          )}
        </div>
        {actionError === undefined ? null : <p role="alert">{actionError}</p>}
        <PopoverArrow className="ja-composer-goal-popover__arrow" aria-hidden="true" />
      </PopoverContent>
    </Popover>
  );
}
