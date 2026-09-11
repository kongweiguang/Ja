// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TaskSummary } from "./taskModel";
import { taskSection } from "./taskModel";

const SECTIONS = [
  { key: "running", label: "运行中" },
  { key: "attention", label: "需要处理" },
  { key: "completed", label: "已完成" },
] as const;

/** 总览只沿当前会话的委派边展开；来源树仍可用于管理侧聊标签，但不能当作子任务列表。 */
export function selectDelegatedTasks(
  tasks: readonly TaskSummary[],
  ownerThreadId: string | undefined,
): TaskSummary[] {
  if (ownerThreadId === undefined) return [];
  const owners = new Set([ownerThreadId]);
  const selected = new Map<string, TaskSummary>();
  for (let depth = 0; depth < 4; depth += 1) {
    for (const task of tasks) {
      if (
        task.taskKind !== "subagent" ||
        task.lifecycle !== "attached" ||
        !owners.has(task.parentThreadId)
      )
        continue;
      selected.set(task.taskThreadId, task);
      owners.add(task.taskThreadId);
    }
  }
  return [...selected.values()];
}

type TaskSectionKey = (typeof SECTIONS)[number]["key"];

export interface TaskTreeNode {
  readonly task: TaskSummary;
  readonly children: TaskTreeNode[];
}

export interface TaskOverviewSection {
  readonly key: TaskSectionKey;
  readonly label: string;
  readonly roots: TaskTreeNode[];
}

/** 服务端已提供稳定时间和 identity；同级只按更新时间倒序，不使用名称猜父子顺序。 */
function sortTasks(tasks: readonly TaskSummary[]): TaskSummary[] {
  return [...tasks].sort(
    (left, right) =>
      right.updatedAt.localeCompare(left.updatedAt) ||
      left.taskThreadId.localeCompare(right.taskThreadId),
  );
}

/**
 * 从真实 parentThreadId 建树；缺父或损坏环会降级为独立根且每个 Task 最多出现一次，
 * 避免右栏递归挂死或把服务端 depth 当作可见父子关系。
 */
export function buildTaskForest(tasks: readonly TaskSummary[]): TaskTreeNode[] {
  const taskById = new Map(tasks.map((task) => [task.taskThreadId, task]));
  const childrenByParent = new Map<string, TaskSummary[]>();
  for (const task of tasks) {
    const children = childrenByParent.get(task.parentThreadId) ?? [];
    children.push(task);
    childrenByParent.set(task.parentThreadId, children);
  }
  const visited = new Set<string>();
  const active = new Set<string>();
  /** DFS 只接受尚未投影的 identity；active 集合把损坏回边截断在当前位置。 */
  const visit = (task: TaskSummary): TaskTreeNode | undefined => {
    if (visited.has(task.taskThreadId) || active.has(task.taskThreadId)) return undefined;
    active.add(task.taskThreadId);
    const children = sortTasks(childrenByParent.get(task.taskThreadId) ?? []).flatMap((child) => {
      const node = visit(child);
      return node === undefined ? [] : [node];
    });
    active.delete(task.taskThreadId);
    visited.add(task.taskThreadId);
    return { task, children };
  };
  const roots = sortTasks(
    tasks.filter(
      (task) => task.parentThreadId === task.rootThreadId || !taskById.has(task.parentThreadId),
    ),
  ).flatMap((task) => {
    const node = visit(task);
    return node === undefined ? [] : [node];
  });
  for (const task of sortTasks(tasks)) {
    const node = visit(task);
    if (node !== undefined) roots.push(node);
  }
  return roots;
}

/** 分支以最需要注意的后代归组，使父节点与全部后代保持邻接且不会跨分组重复。 */
function branchSection(node: TaskTreeNode): TaskSectionKey {
  const sections = [taskSection(node.task), ...node.children.map(branchSection)];
  if (sections.includes("attention")) return "attention";
  if (sections.includes("running")) return "running";
  return "completed";
}

/** 三个分组只接收完整分支，保证每个 Task 一次出现且真实树结构不被状态过滤打断。 */
export function buildTaskOverviewSections(tasks: readonly TaskSummary[]): TaskOverviewSection[] {
  const forest = buildTaskForest(tasks);
  return SECTIONS.flatMap((section) => {
    const roots = forest.filter((node) => branchSection(node) === section.key);
    return roots.length === 0 ? [] : [{ ...section, roots }];
  });
}
