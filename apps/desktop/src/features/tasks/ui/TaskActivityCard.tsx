// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Bot, CheckCircle2, CircleAlert, Clock3, MessageSquare, Play } from "lucide-react";
import type { ReactElement } from "react";
import type { TaskActivity, TaskSummary } from "../domain/taskModel";
import { taskStateLabel } from "../domain/taskModel";
import "./tasks.css";

/** 活动类型只映射到语义图标，不根据摘要正文猜测状态。 */
function ActivityIcon({ activity }: { activity: TaskActivity }): ReactElement {
  switch (activity.kind) {
    case "completed":
      return <CheckCircle2 aria-hidden="true" />;
    case "failed":
    case "cancelled":
    case "suspended":
      return <CircleAlert aria-hidden="true" />;
    case "message_sent":
    case "follow_up_queued":
      return <MessageSquare aria-hidden="true" />;
    case "waiting_approval":
      return <Clock3 aria-hidden="true" />;
    case "dispatched":
    case "resumed":
      return <Play aria-hidden="true" />;
    case "progress":
      return <Bot aria-hidden="true" />;
  }
}

/** 主 Timeline 与详情复用同一紧凑卡，点击只打开 Task，不改变或取消其生命周期。 */
export function TaskActivityCard({
  activity,
  task,
  onOpen,
}: {
  activity: TaskActivity;
  task: TaskSummary;
  onOpen: (task: TaskSummary) => void;
}): ReactElement {
  return (
    <button
      type="button"
      className="ja-task-activity-card"
      onClick={() => onOpen(task)}
      aria-label={`打开${task.taskName}，${taskStateLabel(task.state)}`}
    >
      <span className="ja-task-activity-icon">
        <ActivityIcon activity={activity} />
      </span>
      <span className="ja-task-activity-copy">
        <strong>{task.taskName}</strong>
        <span>{activity.summary.text}</span>
      </span>
      <span className="ja-task-state" data-state={task.state}>
        {taskStateLabel(task.state)}
      </span>
    </button>
  );
}
