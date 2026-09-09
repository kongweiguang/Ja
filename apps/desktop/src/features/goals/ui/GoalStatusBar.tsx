// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  AlertCircle,
  ArrowRight,
  ChevronRight,
  CirclePause,
  CirclePlay,
  LoaderCircle,
} from "lucide-react";
import type { ReactElement } from "react";
import {
  goalPhaseLabel,
  goalProgress,
  isGoalActive,
  type GoalEvaluation,
  type GoalSummary,
} from "../domain/goalModel";
import "./goals.css";

/* eslint-disable react-refresh/only-export-components -- 纯状态策略与唯一消费组件必须同步演进。 */

type GoalPrimaryAction = "pause" | "resume" | "resolve" | "continue";

export interface GoalStatusBarProps {
  readonly goal: GoalSummary;
  readonly evaluation?: GoalEvaluation | null;
  readonly busy?: boolean;
  readonly currentStepTitle?: string;
  readonly onOpen: () => void;
  readonly onPause?: () => void;
  readonly onResume?: () => void;
  readonly onResolve?: () => void;
  readonly onContinue?: () => void;
}

/**
 * 一个 Goal 阶段最多派生一个主动作；当前 revision 的 not_met 优先于 working 的暂停，
 * 让用户明确发起下一轮处理，同时拒绝把旧计划的 evaluator 结论投影到新版本。
 */
export function goalPrimaryAction(
  goal: GoalSummary,
  evaluation?: GoalEvaluation | null,
): GoalPrimaryAction | undefined {
  if (
    goal.phase === "working" &&
    evaluation?.verdict === "not_met" &&
    evaluation.planRevisionId === goal.activePlanRevisionId
  ) {
    return "continue";
  }
  switch (goal.phase) {
    case "working":
      return "pause";
    case "paused":
      return "resume";
    case "waiting_approval":
    case "waiting_input":
      return "resolve";
    case "needs_attention":
      return "continue";
    default:
      return undefined;
  }
}

/** 主动作只在有真实回调时出现；缺少接线不会展示不可操作的占位按钮。 */
function actionPresentation(
  props: GoalStatusBarProps,
): { label: string; icon: typeof ArrowRight; run: () => void } | undefined {
  const action = goalPrimaryAction(props.goal, props.evaluation);
  if (action === "pause" && props.onPause !== undefined)
    return { label: "暂停目标", icon: CirclePause, run: props.onPause };
  if (action === "resume" && props.onResume !== undefined)
    return { label: "恢复目标", icon: CirclePlay, run: props.onResume };
  if (action === "resolve" && props.onResolve !== undefined)
    return { label: "处理问题", icon: AlertCircle, run: props.onResolve };
  if (action === "continue" && props.onContinue !== undefined)
    return { label: "继续处理", icon: ArrowRight, run: props.onContinue };
  return undefined;
}

/**
 * 常驻行只投影活跃 Goal 的最小上下文，终态返回空内容并由时间线承接；完整计划和证据
 * 通过展开入口进入 Workbench，避免 Composer 长期变成第二个管理面板。
 */
export function GoalStatusBar(props: GoalStatusBarProps): ReactElement | null {
  const { goal, busy = false, currentStepTitle, onOpen } = props;
  if (!isGoalActive(goal)) return null;
  const progress = goalProgress(goal);
  const action = actionPresentation(props);
  const ActionIcon = action?.icon;
  const statusText = goal.attentionSummary ?? currentStepTitle ?? goalPhaseLabel(goal.phase);
  const hasStepProgress = goal.totalRequiredSteps > 0;

  return (
    <section
      className="ja-goal-status"
      aria-label="当前目标"
      data-goal-ui="status"
      data-goal-id={goal.goalId}
      data-phase={goal.phase}
    >
      <button type="button" className="ja-goal-status__summary" onClick={onOpen}>
        <span className="ja-goal-status__copy">
          <strong title={goal.objective}>{goal.objective}</strong>
          <small title={statusText}>{statusText}</small>
        </span>
        {hasStepProgress ? (
          <>
            <span
              className="ja-goal-status__progress"
              role="progressbar"
              aria-label="目标进度"
              aria-valuemin={0}
              aria-valuemax={goal.totalRequiredSteps}
              aria-valuenow={goal.completedRequiredSteps}
            >
              <span style={{ width: `${Math.round(progress * 100)}%` }} />
            </span>
            <span className="ja-goal-status__count">
              {goal.completedRequiredSteps}/{goal.totalRequiredSteps}
            </span>
          </>
        ) : null}
        <span className="ja-goal-status__phase">{goalPhaseLabel(goal.phase)}</span>
        <ChevronRight aria-hidden="true" />
      </button>
      {action === undefined || ActionIcon === undefined ? null : (
        <button
          type="button"
          className="ja-goal-status__action"
          aria-label={action.label}
          title={action.label}
          aria-busy={busy || undefined}
          data-goal-action={goalPrimaryAction(goal, props.evaluation)}
          disabled={busy}
          onClick={action.run}
        >
          {busy ? (
            <LoaderCircle className="ja-goal-spin" aria-hidden="true" />
          ) : (
            <ActionIcon aria-hidden="true" />
          )}
          {action === undefined ||
          action.label === "暂停目标" ||
          action.label === "恢复目标" ? null : (
            <span>{action.label}</span>
          )}
        </button>
      )}
    </section>
  );
}
