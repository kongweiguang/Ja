// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type TaskContentBlock =
  | { type: "text"; text: string }
  | { type: "attachment"; attachmentId: string }
  | {
      type: "workspace_reference";
      workspaceId: string;
      relativePath: string;
      kind: "file" | "directory";
    }
  | { type: "skill_reference"; skillId: string };

export type TaskKind = "side_task" | "subagent";
export type TaskLifecycle = "independent" | "attached";
export type TaskState =
  | "idle"
  | "queued"
  | "running"
  | "waiting_approval"
  | "suspended"
  | "completed"
  | "failed"
  | "cancelled";

export interface TaskSummary {
  taskThreadId: string;
  parentThreadId: string;
  rootThreadId: string;
  originTurnId: string | null;
  taskName: string;
  depth: number;
  taskKind: TaskKind;
  lifecycle: TaskLifecycle;
  state: TaskState;
  revision: number;
  latestActivitySequence: number;
  unreadCount: number;
  descendantCount: number;
  runningDescendantCount: number;
  needsAttentionCount: number;
  latestSafeSummary: string | null;
  startedAt: string | null;
  completedAt: string | null;
  updatedAt: string;
}

export type TaskActivityKind =
  | "created"
  | "dispatched"
  | "message_sent"
  | "follow_up_queued"
  | "progress"
  | "waiting_approval"
  | "resumed"
  | "completed"
  | "failed"
  | "cancelled"
  | "suspended";

export interface TaskActivity {
  activitySequence: number;
  activityId: string;
  rootThreadId: string;
  taskThreadId: string;
  actorThreadId: string;
  causalTurnId: string | null;
  kind: TaskActivityKind;
  summary: { text: string };
  createdAt: string;
}

export interface TaskMailboxMessage {
  mailboxSequence: number;
  messageId: string;
  senderThreadId: string;
  targetThreadId: string;
  causalTurnId: string | null;
  kind: "message" | "follow_up" | "final_answer";
  content: TaskContentBlock[];
  state: "pending" | "bound" | "consumed" | "cancelled";
  boundTurnId: string | null;
  createdAt: string;
  updatedAt: string;
  consumedAt: string | null;
}

/** Child Thread 详情携带主任务同构的设置快照，供侧边栏直接驱动对话控制。 */
export interface TaskThreadSummary {
  threadId: string;
  workspaceId: string;
  activeGoalId: string | null;
  preferences: {
    providerId: string;
    modelId: string;
    reasoningLevel: "off" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max" | null;
    accessMode: "approval_required" | "full_access";
    collaborationMode: "default" | "plan";
    titleSource: "placeholder" | "auto" | "manual";
  } | null;
  title: string;
  status: "active" | "archived" | "deleted";
  pinned: boolean;
  latestTurnStatus:
    | "queued"
    | "running"
    | "waiting_approval"
    | "suspended"
    | "completed"
    | "failed"
    | "cancelled"
    | null;
  latestTurnSeen: boolean;
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface TaskReadModel {
  task: TaskSummary;
  thread: TaskThreadSummary;
  contextSeed: {
    contextSeedId: string;
    parentRevision: number;
    inheritanceMode: "effective_context" | "brief_only";
    taskBrief: TaskContentBlock[] | null;
    inheritedContextSummary: string | null;
    inheritedContextPreview: Array<{
      role: "user" | "assistant";
      text: string | null;
      attachmentIds: string[];
    }>;
    fingerprint: string;
    createdAt: string;
  };
  activities: TaskActivity[];
  mailbox: TaskMailboxMessage[];
  nextCursor: string | null;
}

export type TaskHostEvent =
  | {
      method: "task/activity";
      params: {
        rootThreadId: string;
        taskThreadId: string;
        taskRevision: number;
        activity: TaskActivity;
        task: TaskSummary;
      };
    }
  | {
      method: "task/progress";
      params: {
        rootThreadId: string;
        taskThreadId: string;
        taskRevision: number;
        observationId: string;
        progressRevision: number;
        safeSummary: string;
      };
    }
  | {
      method: "task/mailbox-changed";
      params: {
        rootThreadId: string;
        taskThreadId: string;
        taskRevision: number;
        mailboxSequence: number;
        unreadCount: number;
      };
    };

/** 右栏分组把“需要人工处理”置于终态之前，失败与挂起不会被完成列表掩盖。 */
export function taskSection(summary: TaskSummary): "running" | "attention" | "completed" {
  if (["waiting_approval", "suspended", "failed"].includes(summary.state)) return "attention";
  if (["idle", "queued", "running"].includes(summary.state)) return "running";
  return "completed";
}

/** 状态文案保持短促且不推断 Provider 或 Tool 细节。 */
export function taskStateLabel(state: TaskState): string {
  switch (state) {
    case "idle":
      return "未开始";
    case "queued":
      return "排队中";
    case "running":
      return "运行中";
    case "waiting_approval":
      return "等待确认";
    case "suspended":
      return "已挂起";
    case "completed":
      return "已完成";
    case "failed":
      return "失败";
    case "cancelled":
      return "已取消";
  }
}

/** 耗时只使用服务端时间戳；非法或未来时间不显示伪造的负时长。 */
export function taskElapsedLabel(summary: TaskSummary, now = Date.now()): string | undefined {
  if (summary.startedAt === null) return undefined;
  const start = Date.parse(summary.startedAt);
  const end = summary.completedAt === null ? now : Date.parse(summary.completedAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return undefined;
  const seconds = Math.floor((end - start) / 1_000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  return minutes < 60 ? `${minutes} 分钟` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`;
}

/** 终态不会继续接收父取消传播；suspended 仍持有可恢复 Operation，必须计入影响范围。 */
export function isTaskNonTerminal(state: TaskState): boolean {
  return !["completed", "failed", "cancelled"].includes(state);
}

/**
 * 取消范围沿 parentThreadId 递归且只穿过 ATTACHED 边；INDEPENDENT Side Task 是明确的
 * 生命周期断点，即使它位于目标后代树内也不能被提示为将被取消。
 */
export function propagatingTaskDescendantCount(
  tasks: readonly TaskSummary[],
  target: TaskSummary,
): number {
  if (target.lifecycle !== "attached") return 0;
  const childrenByParent = new Map<string, TaskSummary[]>();
  for (const task of tasks) {
    const children = childrenByParent.get(task.parentThreadId) ?? [];
    children.push(task);
    childrenByParent.set(task.parentThreadId, children);
  }
  const visited = new Set<string>([target.taskThreadId]);
  const queue = [target.taskThreadId];
  let count = 0;
  while (queue.length > 0) {
    const parentThreadId = queue.shift();
    if (parentThreadId === undefined) break;
    for (const child of childrenByParent.get(parentThreadId) ?? []) {
      if (visited.has(child.taskThreadId)) continue;
      visited.add(child.taskThreadId);
      if (child.lifecycle !== "attached") continue;
      if (isTaskNonTerminal(child.state)) count += 1;
      queue.push(child.taskThreadId);
    }
  }
  return count;
}
