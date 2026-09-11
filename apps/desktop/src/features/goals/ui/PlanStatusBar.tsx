// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  CheckCircle2,
  ChevronRight,
  CirclePause,
  CirclePlay,
  LoaderCircle,
  MessageCircleQuestion,
  Square,
} from "lucide-react";
import type { ReactElement } from "react";
import { planStatusLabel, type PlanReadModel } from "../domain/goalModel";
import "./goals.css";

export interface PlanStatusBarProps {
  readonly planModel: PlanReadModel;
  readonly busy?: boolean;
  readonly onOpen: () => void;
  readonly onPause?: () => void;
  readonly onResume?: () => void;
  readonly onStop?: () => void;
  readonly hasPendingQuestion?: boolean;
  readonly onAnswerQuestion?: () => void;
}

/** PlanStatus 只映射后端闭集；执行中与验证中分开，避免把运行事实误标为验收结果。 */
function verificationLabel(status: PlanReadModel["plan"]["status"]): string {
  switch (status) {
    case "executing":
      return "执行中";
    case "verifying":
      return "验证中";
    case "completed":
      return "验收通过";
    default:
      return "等待验证";
  }
}

/** 当前步骤只从冻结 revision 的执行投影派生，未加载详情时不伪造步骤文案。 */
function activeStepTitle(
  steps: NonNullable<PlanReadModel["revision"]>["steps"],
): string | undefined {
  return steps.find((step) => step.status === "running" || step.status === "blocked")?.title;
}

/** 必要步骤进度只统计服务端已成功的步骤，不把模型自报成功当作完成事实。 */
function requiredProgress(steps: NonNullable<PlanReadModel["revision"]>["steps"]): {
  completed: number;
  total: number;
} {
  const required = steps.filter((step) => step.required);
  return {
    completed: required.filter((step) => step.status === "succeeded").length,
    total: required.length,
  };
}

/**
 * 独立 Plan 的常驻投影不读取 Goal 状态；控制动作只在有真实回调和活动 run 时出现，
 * 轻量步骤与验收语义来自同一 PlanReadModel，详情正文仍留在 Workbench。
 */
export function PlanStatusBar({
  planModel,
  busy = false,
  onOpen,
  onPause,
  onResume,
  onStop,
  hasPendingQuestion = false,
  onAnswerQuestion,
}: PlanStatusBarProps): ReactElement {
  const { plan, revision } = planModel;
  const steps = revision?.steps ?? [];
  const derivedProgress = requiredProgress(steps);
  const progress = planModel.progress ?? {
    completedRequiredSteps: derivedProgress.completed,
    totalRequiredSteps: derivedProgress.total,
  };
  const currentStepTitle = planModel.progress?.currentStepTitle ?? activeStepTitle(steps);
  const hasRun = plan.activeRunId !== null;
  const waitingForAnswer = hasPendingQuestion;
  const verificationText = verificationLabel(plan.status);
  const statusText = waitingForAnswer
    ? "等待回答"
    : currentStepTitle === undefined
      ? verificationText
      : `${currentStepTitle} / ${verificationText}`;
  const primaryAction =
    waitingForAnswer && onAnswerQuestion !== undefined
      ? { label: "回答问题", icon: MessageCircleQuestion, run: onAnswerQuestion }
      : !waitingForAnswer &&
          (plan.status === "executing" || plan.status === "verifying") &&
          hasRun &&
          onPause !== undefined
        ? { label: "暂停计划", icon: CirclePause, run: onPause }
        : !waitingForAnswer && plan.status === "paused" && hasRun && onResume !== undefined
          ? { label: "继续计划", icon: CirclePlay, run: onResume }
          : undefined;
  const PrimaryIcon = primaryAction?.icon;
  const canStop =
    hasRun && onStop !== undefined && plan.status !== "completed" && plan.status !== "stopped";

  return (
    <section
      className="ja-plan-status"
      aria-label="当前计划"
      data-goal-ui="plan-status"
      data-plan-id={plan.planId}
      data-plan-status={plan.status}
      data-plan-pending-question={hasPendingQuestion || undefined}
    >
      <button type="button" className="ja-plan-status__summary" onClick={onOpen}>
        <span className="ja-plan-status__copy">
          <strong title={plan.objective}>{plan.objective}</strong>
          <small title={statusText}>{statusText}</small>
        </span>
        {progress.totalRequiredSteps > 0 ? (
          <>
            <span
              className="ja-plan-status__progress"
              role="progressbar"
              aria-label="计划必要步骤进度"
              aria-valuemin={0}
              aria-valuemax={progress.totalRequiredSteps}
              aria-valuenow={progress.completedRequiredSteps}
            >
              <span
                style={{
                  width: `${Math.round((progress.completedRequiredSteps / progress.totalRequiredSteps) * 100)}%`,
                }}
              />
            </span>
            <span className="ja-plan-status__count">
              {progress.completedRequiredSteps}/{progress.totalRequiredSteps}
            </span>
          </>
        ) : null}
        <span className="ja-plan-status__phase">{planStatusLabel(plan.status)}</span>
        <ChevronRight aria-hidden="true" />
      </button>
      {PrimaryIcon === undefined || primaryAction === undefined ? null : (
        <button
          type="button"
          className="ja-plan-status__action"
          aria-label={primaryAction.label}
          title={primaryAction.label}
          aria-busy={busy || undefined}
          disabled={busy}
          onClick={primaryAction.run}
        >
          {busy ? <LoaderCircle className="ja-goal-spin" aria-hidden="true" /> : <PrimaryIcon />}
          {primaryAction.label === "回答问题" ? <span>{primaryAction.label}</span> : null}
        </button>
      )}
      {canStop ? (
        <button
          type="button"
          className="ja-plan-status__stop"
          aria-label="停止计划"
          title="停止计划"
          disabled={busy}
          onClick={onStop}
        >
          <Square aria-hidden="true" />
        </button>
      ) : null}
      {plan.status === "completed" ? (
        <CheckCircle2 className="ja-plan-status__indicator" aria-label="验收通过" />
      ) : null}
    </section>
  );
}
