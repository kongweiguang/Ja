// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useState } from "react";
import { selectTaskActivitiesForRoot, useTimelineStore } from "@/features/conversation";
import type { TaskActivity, TaskSummary } from "../domain/taskModel";
import { subscribeTaskHostEvents } from "./taskEventBus";

const TASK_ACTIVITY_LIMIT = 128;
const EMPTY_TASK_ACTIVITIES: readonly TaskTimelineActivity[] = [];

/** 未选择根任务时返回稳定引用，避免 useSyncExternalStore 把空态误判为连续外部更新。 */
function selectEmptyTaskActivities(): readonly TaskTimelineActivity[] {
  return EMPTY_TASK_ACTIVITIES;
}

export interface TaskTimelineActivity {
  readonly activity: TaskActivity;
  readonly task: TaskSummary;
  /** 任务入口的首次可见时间；后续 Activity 只替换内容，不移动 Timeline 位置。 */
  readonly firstOccurredAt: string;
}

/** 优先使用服务端任务创建时间；旧快照缺失时回退到首次看到的 Activity 时间。 */
function firstOccurredAt(entry: Pick<TaskTimelineActivity, "activity" | "task">): string {
  return entry.task.startedAt ?? entry.activity.createdAt;
}

/** 为 Store/事件投影补齐一次性时间锚点，避免把它误当成可更新的 latest Activity 时间。 */
function withFirstOccurredAt(
  entry: Pick<TaskTimelineActivity, "activity" | "task">,
  preserved?: string,
): TaskTimelineActivity {
  return {
    ...entry,
    firstOccurredAt: preserved ?? firstOccurredAt(entry),
  };
}

/**
 * 主/侧聊 Timeline 只展示当前会话直接拥有的 Subagent；独立侧聊是平行会话，不能因为共用
 * 全局 rootThreadId 而把自身活动或旁支活动回流到来源会话。root identity 仍需和 Task 对齐。
 */
function isDirectSubagentActivity(entry: TaskTimelineActivity, ownerThreadId: string): boolean {
  return (
    entry.task.taskKind === "subagent" &&
    entry.task.parentThreadId === ownerThreadId &&
    entry.activity.taskThreadId === entry.task.taskThreadId &&
    entry.activity.rootThreadId === entry.task.rootThreadId
  );
}

/** Snapshot 与实时增量共用 owner 过滤，避免历史和 live 两条路径产生不同可见结果。 */
function filterOwnedActivities(
  entries: readonly TaskTimelineActivity[],
  ownerThreadId: string,
): readonly TaskTimelineActivity[] {
  return entries
    .filter((entry) => isDirectSubagentActivity(entry, ownerThreadId))
    .map((entry) => withFirstOccurredAt(entry, entry.firstOccurredAt));
}

/**
 * Snapshot 与增量统一按 Task identity 收敛：同一子任务始终只有一个入口，后续状态在原位替换。
 * Map 更新已有 key 不会改变插入顺序，因此新任务才会追加到末尾，状态变化不会让入口跳动。
 */
function mergeActivities(
  baseline: readonly TaskTimelineActivity[],
  incoming: readonly TaskTimelineActivity[],
): readonly TaskTimelineActivity[] {
  const byTask = new Map<string, TaskTimelineActivity>();
  const accept = (entry: TaskTimelineActivity): void => {
    const taskThreadId = entry.task.taskThreadId;
    const current = byTask.get(taskThreadId);
    if (
      current === undefined ||
      entry.activity.activitySequence > current.activity.activitySequence ||
      (entry.activity.activitySequence === current.activity.activitySequence &&
        entry.task.revision >= current.task.revision)
    )
      byTask.set(taskThreadId, withFirstOccurredAt(entry, current?.firstOccurredAt));
  };
  for (const entry of baseline) accept(entry);
  for (const entry of incoming) accept(entry);
  return [...byTask.values()].slice(-TASK_ACTIVITY_LIMIT);
}

/**
 * Conversation Timeline 只消费当前 owner Thread 直接委派的低频 Activity。Task 的
 * rootThreadId 仍表示全局 lineage 根，不能拿它替代 owner；投影以 owner identity 隔离并有界去重。
 */
export function useTaskActivityTimeline(
  ownerThreadId?: string,
  runtimeGeneration?: number,
): readonly TaskTimelineActivity[] {
  const storeGeneration = useTimelineStore((state) => state.handshake.generation);
  const storedActivities = useTimelineStore(
    ownerThreadId === undefined
      ? selectEmptyTaskActivities
      : selectTaskActivitiesForRoot(ownerThreadId),
  ) as readonly TaskTimelineActivity[];
  const snapshotActivities =
    runtimeGeneration === storeGeneration && ownerThreadId !== undefined
      ? filterOwnedActivities(storedActivities, ownerThreadId)
      : EMPTY_TASK_ACTIVITIES;
  const scopeKey =
    ownerThreadId === undefined ||
    runtimeGeneration === undefined ||
    runtimeGeneration < 1 ||
    runtimeGeneration !== storeGeneration
      ? ""
      : `${runtimeGeneration}:${ownerThreadId}`;
  const [projection, setProjection] = useState<{
    scopeKey: string;
    items: readonly TaskTimelineActivity[];
  }>({ scopeKey: "", items: [] });

  useEffect(() => {
    if (scopeKey === "" || ownerThreadId === undefined) return undefined;
    return subscribeTaskHostEvents((event) => {
      if (event.method !== "task/activity") return;
      const entry = withFirstOccurredAt({
        activity: event.params.activity,
        task: event.params.task,
      });
      if (
        !isDirectSubagentActivity(entry, ownerThreadId) ||
        event.params.rootThreadId !== entry.task.rootThreadId
      )
        return;
      setProjection((current) => {
        return {
          scopeKey,
          items: mergeActivities(current.scopeKey === scopeKey ? current.items : [], [entry]),
        };
      });
    });
  }, [ownerThreadId, scopeKey]);

  if (scopeKey === "") return EMPTY_TASK_ACTIVITIES;
  return mergeActivities(
    snapshotActivities,
    projection.scopeKey === scopeKey ? projection.items : EMPTY_TASK_ACTIVITIES,
  );
}
