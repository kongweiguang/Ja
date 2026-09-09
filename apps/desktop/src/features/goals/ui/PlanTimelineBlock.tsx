// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  AlertCircle,
  CheckCircle2,
  ChevronRight,
  Circle,
  CircleDot,
  Lightbulb,
  LoaderCircle,
  type LucideIcon,
} from "lucide-react";
import type { ReactElement } from "react";
import {
  goalPhaseLabel,
  planStatusLabel,
  planStepStatusLabel,
  type GoalReadModel,
  type PlanReadModel,
  type PlanRevision,
} from "../domain/goalModel";
import "./goals.css";

export interface PlanTimelineBlockProps {
  readonly model?: GoalReadModel;
  readonly planModel?: PlanReadModel;
  readonly onOpenDetails?: () => void;
}

/** 当前步骤越过首屏摘要时保留前三项并补入当前项，避免长计划隐藏正在发生的工作。 */
function visiblePlanSteps(
  plan: PlanRevision,
  currentStepId?: string | null,
): PlanRevision["steps"] {
  if (plan.steps.length <= 4) return plan.steps;
  const current = plan.steps.find((step) => step.stepId === currentStepId);
  if (
    current === undefined ||
    plan.steps.slice(0, 4).some((step) => step.stepId === current.stepId)
  )
    return plan.steps.slice(0, 4);
  return [...plan.steps.slice(0, 3), current];
}

/** 步骤图标与文字并存，状态不会只依赖颜色或动画表达。 */
function stepIcon(status: PlanRevision["steps"][number]["status"]): LucideIcon {
  switch (status) {
    case "succeeded":
      return CheckCircle2;
    case "running":
      return LoaderCircle;
    case "blocked":
    case "failed":
      return AlertCircle;
    case "ready":
      return CircleDot;
    default:
      return Circle;
  }
}

/**
 * Timeline block 提供 Plan 的主要可扫读结构；完整 revision、依赖、证据和编辑仍由显式详情入口
 * 承接，避免默认挂载重型 PlanWorkbench 或把 Timeline 扩成第二个编辑器。
 */
export function PlanTimelineBlock({
  model,
  planModel,
  onOpenDetails,
}: PlanTimelineBlockProps): ReactElement | null {
  const plan = planModel?.revision ?? model?.plan ?? null;
  if (plan === null) return null;
  const planState = planModel?.plan ?? model?.planState ?? null;
  const visibleSteps = visiblePlanSteps(plan, model?.goal.currentStepId);
  const metCriteria = plan.acceptanceCriteria.filter(
    (criterion) => criterion.status === "met",
  ).length;

  return (
    <article
      className="ja-plan-timeline"
      data-goal-ui="plan-timeline"
      data-goal-id={model?.goal.goalId}
      data-plan-id={plan.planId}
      data-plan-revision-id={plan.planRevisionId}
      aria-label={`计划版本 ${plan.revisionNumber}：${plan.objective}`}
    >
      <header className="ja-plan-timeline__header">
        <span className="ja-plan-timeline__icon" aria-hidden="true">
          <Lightbulb />
        </span>
        <div>
          <strong>计划</strong>
          <small>
            版本 {plan.revisionNumber} ·{" "}
            {planState === null
              ? model === undefined
                ? "计划"
                : goalPhaseLabel(model.goal.phase)
              : planStatusLabel(planState.status)}
          </small>
        </div>
      </header>
      <h3 title={plan.objective}>{plan.objective}</h3>
      <ol className="ja-plan-timeline__steps">
        {visibleSteps.map((step) => {
          const StepIcon = stepIcon(step.status);
          return (
            <li key={step.stepId} data-status={step.status}>
              <StepIcon
                aria-hidden="true"
                className={step.status === "running" ? "ja-goal-spin" : undefined}
              />
              <span title={step.title}>{step.title}</span>
              <small>{planStepStatusLabel(step.status)}</small>
            </li>
          );
        })}
      </ol>
      <footer className="ja-plan-timeline__footer">
        <span>
          {plan.steps.length > visibleSteps.length ? `${plan.steps.length} 个步骤` : ""}
          {plan.steps.length > visibleSteps.length && plan.acceptanceCriteria.length > 0
            ? " · "
            : ""}
          {plan.acceptanceCriteria.length > 0
            ? `${metCriteria}/${plan.acceptanceCriteria.length} 项验收`
            : ""}
        </span>
        {onOpenDetails === undefined ? null : (
          <button type="button" onClick={onOpenDetails}>
            查看详情
            <ChevronRight aria-hidden="true" />
          </button>
        )}
      </footer>
    </article>
  );
}
