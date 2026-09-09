// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export type WorkbenchCapability =
  | "review"
  | "files"
  | "terminal"
  | "preview"
  | "agents"
  | "plan"
  | "new";
export type WorkbenchTaskKind = "side_task" | "subagent";
export type WorkbenchTaskTabKey = `side-task:${string}` | `subagent:${string}`;
export type WorkbenchTabKey = WorkbenchCapability | WorkbenchTaskTabKey;

export interface WorkbenchCapabilityTab {
  readonly kind: "capability";
  readonly key: WorkbenchCapability;
  readonly capability: WorkbenchCapability;
  readonly label: string;
}

export interface WorkbenchTaskTab {
  readonly kind: "task";
  readonly key: WorkbenchTaskTabKey;
  readonly taskKind: WorkbenchTaskKind;
  readonly taskThreadId?: string;
  readonly rootThreadId: string;
  readonly label: string;
}

/** 描述符把稳定身份与展示标签分离，任务重命名不会破坏已打开实例的唯一性。 */
export type WorkbenchTab = WorkbenchCapabilityTab | WorkbenchTaskTab;

const CAPABILITY_LABELS: Readonly<Record<WorkbenchCapability, string>> = {
  review: "审查",
  files: "文件",
  terminal: "终端",
  preview: "浏览器",
  agents: "子智能体",
  plan: "计划",
  new: "新标签页",
};

/** Singleton descriptor 始终由闭集工厂创建，调用方不能伪造重复 key。 */
export function capabilityWorkbenchTab(capability: WorkbenchCapability): WorkbenchCapabilityTab {
  return { kind: "capability", key: capability, capability, label: CAPABILITY_LABELS[capability] };
}

/** 服务端 Task summary 只决定实例标签；identity 始终使用不可变 Child Thread ID。 */
export function taskWorkbenchTab(input: {
  taskThreadId: string;
  taskKind: WorkbenchTaskKind;
  rootThreadId: string;
  label: string;
}): WorkbenchTaskTab {
  return {
    kind: "task",
    key: `${input.taskKind === "side_task" ? "side-task" : "subagent"}:${input.taskThreadId}`,
    taskKind: input.taskKind,
    taskThreadId: input.taskThreadId,
    rootThreadId: input.rootThreadId,
    label: input.label,
  };
}

/** 草稿获得进程期 identity，但没有 taskThreadId，因此空白 Tab 不可能触发后端持久化。 */
export function sideTaskDraftWorkbenchTab(rootThreadId: string, draftId: string): WorkbenchTaskTab {
  return {
    kind: "task",
    key: `side-task:draft_${draftId}`,
    taskKind: "side_task",
    rootThreadId,
    label: "新侧边任务",
  };
}

/** Task key 解析只接受协议 Thread ID 或本地 draft ID，损坏偏好不会生成可调用后端的身份。 */
export function parseTaskWorkbenchTabKey(
  key: string,
): { taskKind: WorkbenchTaskKind; taskThreadId?: string } | undefined {
  const match =
    /^(side-task|subagent):(thr_[A-Za-z0-9][A-Za-z0-9._-]{0,95}|draft_[A-Za-z0-9-]{8,64})$/u.exec(
      key,
    );
  if (match === null) return undefined;
  if (match[1] === "subagent" && match[2]?.startsWith("draft_")) return undefined;
  return {
    taskKind: match[1] === "side-task" ? "side_task" : "subagent",
    ...(match[2]?.startsWith("thr_") ? { taskThreadId: match[2] } : {}),
  };
}

/** 对象比较只使用稳定 key，避免刷新 summary 后对象引用变化导致 Tab 失焦。 */
export function sameWorkbenchTab(left: WorkbenchTab, right: WorkbenchTab): boolean {
  return left.key === right.key;
}
