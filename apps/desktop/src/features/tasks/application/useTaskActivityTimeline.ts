// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useMemo, useSyncExternalStore } from "react";
import { selectTaskActivitiesForRoot, useTimelineStore } from "@/features/conversation";
import type { TimelineTaskActivityEntry } from "@/features/conversation";
import type { TaskActivity, TaskSummary } from "../domain/taskModel";

const TASK_ACTIVITY_LIMIT = 128;
const EMPTY_TASK_ACTIVITIES: readonly TaskTimelineActivity[] = [];
type TimelineStoreSnapshot = ReturnType<typeof useTimelineStore.getState>;
const EMPTY_STORED_TASK_ACTIVITIES: readonly TimelineTaskActivityEntry[] = [];

/** 隐藏时不注册 Zustand listener；恢复时 getSnapshot 直接读取最新权威切片，无需轮询或补读。 @author kongweiguang */
function useTimelineSelectionWhenActive<T>(
  active: boolean,
  selector: (state: TimelineStoreSnapshot) => T,
  inactiveValue: T,
): T {
  const subscribe = useCallback(
    (onStoreChange: () => void) =>
      active ? useTimelineStore.subscribe(() => onStoreChange()) : () => undefined,
    [active],
  );
  const getSnapshot = useCallback(
    () => (active ? selector(useTimelineStore.getState()) : inactiveValue),
    [active, inactiveValue, selector],
  );
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/** 复用 Snapshot owner-root 索引，空 owner 不触碰全局实体表。 @author kongweiguang */
function selectOwnerTaskActivities(
  ownerThreadId?: string,
): (state: TimelineStoreSnapshot) => readonly TimelineTaskActivityEntry[] {
  if (ownerThreadId === undefined) return () => EMPTY_STORED_TASK_ACTIVITIES;
  return selectTaskActivitiesForRoot(ownerThreadId);
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
 * 主/侧聊只展示当前会话直接拥有的 Subagent；全局 lineage root 不能替代 owner Thread。
 * @author kongweiguang
 */
function isDirectSubagentActivity(
  entry: Pick<TaskTimelineActivity, "activity" | "task">,
  ownerThreadId: string,
): boolean {
  return (
    entry.task.taskKind === "subagent" &&
    entry.task.parentThreadId === ownerThreadId &&
    entry.activity.taskThreadId === entry.task.taskThreadId &&
    entry.activity.rootThreadId === entry.task.rootThreadId
  );
}

/**
 * Snapshot 与实时增量共用 owner 过滤；首次出现时间按 Task 的最早事实固定，避免后续摘要更新移动入口。
 * @author kongweiguang
 */
function filterOwnedActivities(
  entries: readonly TimelineTaskActivityEntry[],
  ownerThreadId: string,
): readonly TaskTimelineActivity[] {
  const ownedEntries = entries.filter((entry) => isDirectSubagentActivity(entry, ownerThreadId));
  const firstOccurrenceByTask = new Map<string, string>();
  for (const entry of ownedEntries) {
    const taskThreadId = entry.task.taskThreadId;
    const occurredAt = firstOccurredAt(entry);
    const current = firstOccurrenceByTask.get(taskThreadId);
    if (current === undefined || occurredAt < current)
      firstOccurrenceByTask.set(taskThreadId, occurredAt);
  }
  return ownedEntries.map((entry) =>
    withFirstOccurredAt(entry, firstOccurrenceByTask.get(entry.task.taskThreadId)),
  );
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
 * rootThreadId 仍表示全局 lineage 根，不能拿它替代 owner。隐藏时解除展示订阅，所有实时事实
 * 已由 Runtime composition 归约到同一 TimelineStore，重新显示直接读取最新 owner slice。
 * @author kongweiguang
 */
export function useTaskActivityTimeline(
  ownerThreadId?: string,
  runtimeGeneration?: number,
  active = true,
): readonly TaskTimelineActivity[] {
  const scopeCanSubscribe =
    active &&
    ownerThreadId !== undefined &&
    runtimeGeneration !== undefined &&
    runtimeGeneration >= 1;
  const storeGeneration = useTimelineSelectionWhenActive(
    scopeCanSubscribe,
    (state) => state.handshake.generation,
    0,
  );
  const activitiesActive = scopeCanSubscribe && runtimeGeneration === storeGeneration;
  const activitySelector = useMemo(() => selectOwnerTaskActivities(ownerThreadId), [ownerThreadId]);
  const storedActivities = useTimelineSelectionWhenActive(
    activitiesActive,
    activitySelector,
    EMPTY_STORED_TASK_ACTIVITIES,
  );
  const scopeKey =
    !activitiesActive ||
    ownerThreadId === undefined ||
    runtimeGeneration === undefined ||
    runtimeGeneration !== storeGeneration
      ? ""
      : `${runtimeGeneration}:${ownerThreadId}`;
  const snapshotActivities = useMemo(
    () =>
      scopeKey === "" || ownerThreadId === undefined
        ? EMPTY_TASK_ACTIVITIES
        : filterOwnedActivities(storedActivities, ownerThreadId),
    [ownerThreadId, scopeKey, storedActivities],
  );
  return useMemo(
    () => (scopeKey === "" ? EMPTY_TASK_ACTIVITIES : mergeActivities(snapshotActivities, [])),
    [scopeKey, snapshotActivities],
  );
}
