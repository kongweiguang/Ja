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
}

/** Snapshot 与增量统一按持久 identity 收敛；排序和截断在同一处保证 reload 与 live 行为一致。 */
function mergeActivities(
  baseline: readonly TaskTimelineActivity[],
  incoming: readonly TaskTimelineActivity[],
): readonly TaskTimelineActivity[] {
  const byId = new Map(baseline.map((entry) => [entry.activity.activityId, entry]));
  for (const entry of incoming) byId.set(entry.activity.activityId, entry);
  return [...byId.values()]
    .sort(
      (left, right) =>
        left.activity.activitySequence - right.activity.activitySequence ||
        left.activity.activityId.localeCompare(right.activity.activityId),
    )
    .slice(-TASK_ACTIVITY_LIMIT);
}

/**
 * 主 Timeline 只消费当前根任务的低频持久 Activity 通知。投影以 root identity 隔离并有界
 * 去重，因此 Thread 切换不会闪现旧树，重复投递也不会让 Renderer 状态无限增长。
 */
export function useTaskActivityTimeline(
  rootThreadId?: string,
  runtimeGeneration?: number,
): readonly TaskTimelineActivity[] {
  const storeGeneration = useTimelineStore((state) => state.handshake.generation);
  const storedActivities = useTimelineStore(
    rootThreadId === undefined
      ? selectEmptyTaskActivities
      : selectTaskActivitiesForRoot(rootThreadId),
  ) as readonly TaskTimelineActivity[];
  const snapshotActivities =
    runtimeGeneration === storeGeneration ? storedActivities : EMPTY_TASK_ACTIVITIES;
  const scopeKey =
    rootThreadId === undefined ||
    runtimeGeneration === undefined ||
    runtimeGeneration < 1 ||
    runtimeGeneration !== storeGeneration
      ? ""
      : `${runtimeGeneration}:${rootThreadId}`;
  const [projection, setProjection] = useState<{
    scopeKey: string;
    items: readonly TaskTimelineActivity[];
  }>({ scopeKey: "", items: [] });

  useEffect(() => {
    if (scopeKey === "" || rootThreadId === undefined) return undefined;
    return subscribeTaskHostEvents((event) => {
      if (event.method !== "task/activity" || event.params.rootThreadId !== rootThreadId) return;
      setProjection((current) => {
        return {
          scopeKey,
          items: mergeActivities(current.scopeKey === scopeKey ? current.items : [], [
            { activity: event.params.activity, task: event.params.task },
          ]),
        };
      });
    });
  }, [rootThreadId, scopeKey]);

  if (scopeKey === "") return EMPTY_TASK_ACTIVITIES;
  return mergeActivities(
    snapshotActivities,
    projection.scopeKey === scopeKey ? projection.items : EMPTY_TASK_ACTIVITIES,
  );
}
