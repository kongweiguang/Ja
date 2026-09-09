// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { CheckCircle2, CircleStop } from "lucide-react";
import type { ReactElement } from "react";
import type { TimelineGoalActivity } from "@/features/conversation";
import "./goals.css";

/**
 * 终态卡片是不可操作的审计事实；完整计划入口不在这里伪造，因为历史 Goal 必须先有按 identity
 * 打开的产品能力，避免点击旧卡片却错误展示当前目标。
 */
export function GoalActivityCard({ activity }: { activity: TimelineGoalActivity }): ReactElement {
  const achieved = activity.status === "achieved";
  return (
    <article
      className="ja-goal-activity-card"
      data-goal-id={activity.goalId}
      data-goal-status={activity.status}
      aria-label={`${activity.objective}，${achieved ? "已达成" : "已停止"}`}
    >
      <span className="ja-goal-activity-card__icon" aria-hidden="true">
        {achieved ? <CheckCircle2 /> : <CircleStop />}
      </span>
      <span className="ja-goal-activity-card__copy">
        <strong>{activity.objective}</strong>
        <span>{achieved ? "目标已达成" : "目标已停止"}</span>
      </span>
      <span className="ja-goal-activity-card__state">{achieved ? "已达成" : "已停止"}</span>
    </article>
  );
}
