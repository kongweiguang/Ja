// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  TaskApprovalPort,
  TaskPort,
  TaskResumePort,
  TaskThreadRenamePort,
  TaskTranscriptPort,
  TaskTranscriptSnapshot,
} from "./ports";
import type {
  ConversationAcceptedTurn,
  ConversationAccessMode,
  ConversationCollaborationMode,
  ConversationModelSelection,
  TimelineRuntimeFence,
} from "@/features/conversation";
import { subscribeTaskHostEvents } from "./taskEventBus";
import type { TaskContentBlock, TaskReadModel, TaskState, TaskSummary } from "../domain/taskModel";
import { createOperationKey } from "@/shared/operationKey";

const TASK_SEEN_SETTLE_MS = 75;
const TASK_OBSERVE_MAX_ATTEMPTS = 3;

export interface TaskController {
  readonly tasks: readonly TaskSummary[];
  readonly loading: boolean;
  readonly error?: string;
  readonly detail?: TaskReadModel;
  /** 当前 Child 已投影到共享 Timeline 且 Task/Thread observe 均完成的 Thread。 */
  readonly readyTaskThreadId?: string;
  readonly detailLoading: boolean;
  readonly detailError?: string;
  readonly transcriptError?: string;
  readonly progressSummary?: string;
  readonly closingTaskThreadId?: string;
  readonly refresh: () => Promise<void>;
  readonly refreshDetail: () => Promise<void>;
  /** 等待指定侧聊首屏 detail、Timeline 投影和 observe 收敛，避免创建后发送落入旧 Thread。 */
  readonly waitForTaskReady: (taskThreadId: string) => Promise<void>;
  readonly createSideTask: (input: {
    taskName: string;
    /** 默认使用当前根 Thread；侧聊 Composer 必须显式绑定它所在的 child Thread。 */
    sourceThreadId?: string;
    /** 侧聊 Composer 可携带已读到的来源 revision，CAS 冲突时仍由 controller 重读。 */
    sourceThreadRevision?: number;
    preferences?: ConversationModelSelection & {
      accessMode: ConversationAccessMode;
      collaborationMode: ConversationCollaborationMode;
    };
  }) => Promise<TaskSummary>;
  readonly rename: (task: TaskSummary, title: string) => Promise<TaskSummary>;
  readonly followup: (
    task: TaskSummary,
    content: TaskContentBlock[],
    senderThreadId?: string,
  ) => Promise<TaskSummary>;
  /** 将 TaskCoordinator 的 follow-up ACK 适配为共享 Conversation interaction 的 Turn ACK。 */
  readonly followupTurn: (
    task: TaskSummary,
    content: TaskContentBlock[],
    senderThreadId?: string,
  ) => Promise<ConversationAcceptedTurn>;
  readonly close: (task: TaskSummary) => Promise<void>;
  readonly cancel: (task: TaskSummary) => Promise<TaskSummary>;
  readonly resume: (task: TaskSummary) => Promise<void>;
  readonly approvalRespond: (
    approvalId: string,
    turnId: string,
    expectedThreadRevision: number,
    decision: "approve" | "deny",
  ) => Promise<void>;
}

interface TaskControllerOptions {
  readonly rootThreadId?: string;
  readonly parentRevision?: number;
  readonly activeTaskThreadId?: string;
  readonly visible: boolean;
  readonly port: TaskPort;
  readonly transcriptPort: TaskTranscriptPort;
  /** 将快照直接交给共享 TimelineStore；TaskController 不保留第二份正文状态。 */
  readonly applyTranscriptSnapshot: (snapshot: TaskTranscriptSnapshot) => boolean;
  readonly renamePort: TaskThreadRenamePort;
  readonly approvalPort: TaskApprovalPort;
  readonly resumePort: TaskResumePort;
  readonly onSubagentDiscovered?: () => void;
  /** 只把匹配同一 Runtime 的权威关闭集合交给 Host 清理共享投影与 native Preview。 */
  readonly runtimeFence?: TimelineRuntimeFence;
  readonly onSideTaskClosed: (
    closedThreadIds: readonly string[],
    runtimeFence: TimelineRuntimeFence,
  ) => Promise<boolean>;
}

interface TaskReadyWaiter {
  readonly resolve: () => void;
  readonly reject: (reason?: unknown) => void;
}

interface ClosingTaskRequest {
  readonly taskThreadId: string;
  readonly runtimeFence: TimelineRuntimeFence;
}

interface TaskRefreshScopeFence {
  readonly rootThreadId: string;
  readonly taskEpoch: number;
}

/** 安全错误文案不读取 native message/stack，避免绝对路径或 Provider 诊断进入 UI。 */
function taskErrorMessage(
  operation:
    | "list"
    | "detail"
    | "transcript"
    | "create"
    | "rename"
    | "followup"
    | "close"
    | "cancel"
    | "resume"
    | "approval",
): string {
  switch (operation) {
    case "list":
      return "子任务暂时无法读取，请重试。";
    case "detail":
      return "任务详情暂时无法读取，请重试。";
    case "transcript":
      return "任务对话记录暂时无法读取，请重试。";
    case "create":
      return "侧聊创建失败，请重试。";
    case "rename":
      return "侧聊重命名失败，请重试。";
    case "followup":
      return "消息未发送，请重试。";
    case "close":
      return "侧聊关闭失败，请重试。";
    case "cancel":
      return "取消请求未完成，请重试。";
    case "resume":
      return "任务恢复失败，请确认当前状态后重试。";
    case "approval":
      return "审批响应失败，请确认任务状态后重试。";
  }
}

/** 只按 Native 保留的稳定机器码识别 CAS 冲突，禁止依赖会本地化或脱敏的展示文案。 */
export function isTaskRevisionConflict(error: unknown): boolean {
  return (
    error !== null &&
    typeof error === "object" &&
    "code" in error &&
    error.code === "TASK_CONTEXT_REVISION_CONFLICT"
  );
}

/** 仅向界面传递可安全展示的冲突事实，避免把 Native message/路径带入重确认提示。 */
function taskRevisionConflictError(): Error & {
  readonly code: "TASK_CONTEXT_REVISION_CONFLICT";
} {
  return Object.assign(new Error("任务状态已更新，请查看最新任务及取消范围后重新确认。"), {
    code: "TASK_CONTEXT_REVISION_CONFLICT" as const,
  });
}

/** 读请求不能跨 SID 或 generation 投影；两个 undefined 只表示请求期间始终未建立 Runtime。@author kongweiguang */
function sameRuntimeFence(
  current: TimelineRuntimeFence | undefined,
  requested: TimelineRuntimeFence | undefined,
): boolean {
  return (
    current?.serverInstanceId === requested?.serverInstanceId &&
    current?.generation === requested?.generation
  );
}

/** 同一 revision 下终态优先，避免详情与摘要分别接纳旧 running 投影。 */
function preferTaskSummary(existing: TaskSummary, incoming: TaskSummary): TaskSummary {
  if (
    existing.revision > incoming.revision ||
    (existing.revision === incoming.revision &&
      isTerminalTaskState(existing.state) &&
      !isTerminalTaskState(incoming.state))
  )
    return existing;
  return incoming;
}

/** ACK 与事件都按 revision 单调合并，晚响应不能覆盖更新后的服务端投影。 */
function mergeTask(current: readonly TaskSummary[], incoming: TaskSummary): TaskSummary[] {
  const index = current.findIndex((task) => task.taskThreadId === incoming.taskThreadId);
  if (index < 0) return [...current, incoming];
  const existing = current[index];
  if (existing === undefined) return [...current];
  if (preferTaskSummary(existing, incoming) === existing) return [...current];
  const next = [...current];
  next[index] = incoming;
  return next;
}

/** 同一 revision 下终态优先，防止 observe 前后迟到的 running 摘要覆盖已确认结果。 */
function isTerminalTaskState(state: TaskState): boolean {
  return state === "completed" || state === "failed" || state === "cancelled";
}

/**
 * 事件可能晚于关闭抵达，且新事件未必已进入摘要列表；沿已知 parentThreadId 链并结合事件
 * 自带的父身份判断其是否属于已销毁侧聊子树，避免只按 taskThreadId 做浅层过滤。
 */
function belongsToClosedTaskTree(
  tasks: readonly TaskSummary[],
  taskThreadId: string,
  closedTaskIds: ReadonlySet<string>,
  eventParentThreadId?: string,
): boolean {
  let currentThreadId = taskThreadId;
  let parentThreadId = eventParentThreadId;
  const visited = new Set<string>();
  while (!visited.has(currentThreadId)) {
    visited.add(currentThreadId);
    if (closedTaskIds.has(currentThreadId)) return true;
    const known = tasks.find((task) => task.taskThreadId === currentThreadId);
    const nextParentThreadId = known?.parentThreadId ?? parentThreadId;
    if (nextParentThreadId === undefined || nextParentThreadId === currentThreadId) return false;
    currentThreadId = nextParentThreadId;
    parentThreadId = undefined;
  }
  return false;
}

/**
 * 按协议字段显式序列化内容 identity，避免对象属性插入顺序影响 ACK 丢失后的重试判断。
 * identity 只短暂保存在当前 WebView 内存，不进入日志、持久化或幂等键正文。
 */
function taskContentIdentity(content: readonly TaskContentBlock[]): string {
  return JSON.stringify(
    content.map((block) => {
      switch (block.type) {
        case "text":
          return [block.type, block.text];
        case "attachment":
          return [block.type, block.attachmentId];
        case "workspace_reference":
          return [block.type, block.workspaceId, block.relativePath, block.kind];
        case "skill_reference":
          return [block.type, block.skillId];
      }
    }),
  );
}

/**
 * Controller 常驻消费低频摘要事件，但只有详情可见时才读取 task/read、thread/read 并建立
 * observe；快照立即交由共享 TimelineStore 投影，不在 Task state 中另存完整正文。
 * @author kongweiguang
 */
export function useTaskController({
  rootThreadId,
  activeTaskThreadId,
  visible,
  port,
  transcriptPort,
  applyTranscriptSnapshot,
  renamePort,
  approvalPort,
  resumePort,
  onSubagentDiscovered,
  onSideTaskClosed,
  runtimeFence,
}: TaskControllerOptions): TaskController {
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [detail, setDetail] = useState<TaskReadModel>();
  const [readyTaskThreadId, setReadyTaskThreadId] = useState<string>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string>();
  const [transcriptError, setTranscriptError] = useState<string>();
  const [progressSummary, setProgressSummary] = useState<string>();
  const [closingTaskRequest, setClosingTaskRequest] = useState<ClosingTaskRequest>();
  const detailEpochRef = useRef(0);
  const observationRef = useRef<string | undefined>(undefined);
  const activeObservationOwnerRef = useRef<
    { readonly taskThreadId: string; stop: () => void } | undefined
  >(undefined);
  const readyTaskRef = useRef<{ taskThreadId: string; epoch: number } | undefined>(undefined);
  const taskReadyWaitersRef = useRef<Map<string, Set<TaskReadyWaiter>>>(new Map());
  const tasksRef = useRef<readonly TaskSummary[]>(tasks);
  const taskEpochRef = useRef(0);
  const seenTargetByTaskRef = useRef<Map<string, number>>(new Map());
  const seenTimerByTaskRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  const seenChainByTaskRef = useRef<Map<string, Promise<void>>>(new Map());
  const visibleRef = useRef(visible);
  const activeTaskRef = useRef(activeTaskThreadId);
  const rootThreadRef = useRef(rootThreadId);
  const onSubagentDiscoveredRef = useRef(onSubagentDiscovered);
  const onSideTaskClosedRef = useRef(onSideTaskClosed);
  const runtimeFenceRef = useRef(runtimeFence);
  const closedRuntimeFenceRef = useRef(runtimeFence);
  const followupRetryRef = useRef<
    Map<string, { readonly contentIdentity: string; readonly operationKey: string }>
  >(new Map());
  const closedTaskIdsRef = useRef<Set<string>>(new Set());
  const closingTaskThreadRef = useRef<ClosingTaskRequest | undefined>(undefined);
  visibleRef.current = visible;
  activeTaskRef.current = activeTaskThreadId;
  rootThreadRef.current = rootThreadId;
  onSubagentDiscoveredRef.current = onSubagentDiscovered;
  onSideTaskClosedRef.current = onSideTaskClosed;
  runtimeFenceRef.current = runtimeFence;
  const closingTaskThreadId =
    closingTaskRequest !== undefined &&
    sameRuntimeFence(runtimeFence, closingTaskRequest.runtimeFence)
      ? closingTaskRequest.taskThreadId
      : undefined;
  if (
    closedRuntimeFenceRef.current?.serverInstanceId !== runtimeFence?.serverInstanceId ||
    closedRuntimeFenceRef.current?.generation !== runtimeFence?.generation
  ) {
    closedTaskIdsRef.current.clear();
    closedRuntimeFenceRef.current = runtimeFence;
  }

  /** 创建侧聊后的 follow-up 只等待对应实例，不把任意其它 Child 的 ready 当作完成信号。 */
  const waitForTaskReady = useCallback((taskThreadId: string): Promise<void> => {
    if (
      readyTaskRef.current?.taskThreadId === taskThreadId &&
      readyTaskRef.current.epoch === detailEpochRef.current &&
      activeTaskRef.current === taskThreadId &&
      visibleRef.current &&
      !closedTaskIdsRef.current.has(taskThreadId)
    )
      return Promise.resolve();
    return new Promise<void>((resolve, reject) => {
      const waiters = taskReadyWaitersRef.current.get(taskThreadId) ?? new Set<TaskReadyWaiter>();
      waiters.add({ resolve, reject });
      taskReadyWaitersRef.current.set(taskThreadId, waiters);
    });
  }, []);

  /** 只兑现相同 active epoch 的 waiter；切换 Tab 后旧 setup 无权让新侧聊提前发送。 */
  const resolveTaskReady = useCallback((taskThreadId: string, epoch: number): void => {
    if (epoch !== detailEpochRef.current || activeTaskRef.current !== taskThreadId) return;
    readyTaskRef.current = { taskThreadId, epoch };
    setReadyTaskThreadId(taskThreadId);
    const waiters = taskReadyWaitersRef.current.get(taskThreadId);
    if (waiters === undefined) return;
    taskReadyWaitersRef.current.delete(taskThreadId);
    for (const waiter of waiters) waiter.resolve();
  }, []);

  /** 失败或生命周期切换时结束 waiter，避免创建流程永远等待一个已失效的 Tab。 */
  const rejectTaskReady = useCallback((taskThreadId: string, reason: unknown): void => {
    const waiters = taskReadyWaitersRef.current.get(taskThreadId);
    if (waiters === undefined) return;
    taskReadyWaitersRef.current.delete(taskThreadId);
    for (const waiter of waiters) waiter.reject(reason);
  }, []);

  /** 仅接受匹配当前 SID+generation 的 ACK/事件；旧运行时的同名 ID 不得清理新会话。@author kongweiguang */
  const acceptClosedTaskThreads = useCallback(
    async (
      threadIds: readonly string[],
      fence: TimelineRuntimeFence | undefined,
    ): Promise<boolean> => {
      const currentFence = runtimeFenceRef.current;
      if (
        fence === undefined ||
        currentFence === undefined ||
        fence.serverInstanceId !== currentFence.serverInstanceId ||
        fence.generation !== currentFence.generation
      )
        return false;
      const closedTaskIds = new Set(threadIds.filter((threadId) => threadId.length > 0));
      if (closedTaskIds.size === 0) return false;
      const newThreadIds = [...closedTaskIds].filter(
        (taskThreadId) => !closedTaskIdsRef.current.has(taskThreadId),
      );
      if (newThreadIds.length === 0) return true;
      for (const taskThreadId of newThreadIds) {
        closedTaskIdsRef.current.add(taskThreadId);
      }
      let accepted = false;
      try {
        accepted = await onSideTaskClosedRef.current([...closedTaskIds], fence);
        const latestFence = runtimeFenceRef.current;
        accepted &&=
          latestFence?.serverInstanceId === fence.serverInstanceId &&
          latestFence.generation === fence.generation;
      } catch {
        accepted = false;
      }
      if (!accepted) {
        const latestFence = runtimeFenceRef.current;
        if (
          latestFence?.serverInstanceId === fence.serverInstanceId &&
          latestFence.generation === fence.generation
        )
          for (const taskThreadId of newThreadIds) closedTaskIdsRef.current.delete(taskThreadId);
        return false;
      }
      for (const taskThreadId of newThreadIds) {
        const timer = seenTimerByTaskRef.current.get(taskThreadId);
        if (timer !== undefined) clearTimeout(timer);
        seenTimerByTaskRef.current.delete(taskThreadId);
        seenTargetByTaskRef.current.delete(taskThreadId);
        seenChainByTaskRef.current.delete(taskThreadId);
        followupRetryRef.current.delete(taskThreadId);
        rejectTaskReady(taskThreadId, new Error("side task was closed"));
      }
      const isActiveTask =
        activeTaskRef.current !== undefined && closedTaskIds.has(activeTaskRef.current);
      if (isActiveTask) {
        const observationOwner = activeObservationOwnerRef.current;
        if (
          observationOwner !== undefined &&
          observationOwner.taskThreadId === activeTaskRef.current
        )
          observationOwner.stop();
        detailEpochRef.current += 1;
        setDetail(undefined);
        setDetailLoading(false);
        setDetailError(undefined);
        setTranscriptError(undefined);
        setProgressSummary(undefined);
      }
      if (
        readyTaskRef.current !== undefined &&
        closedTaskIds.has(readyTaskRef.current.taskThreadId)
      ) {
        readyTaskRef.current = undefined;
        setReadyTaskThreadId(undefined);
      }
      const nextTasks = tasksRef.current.filter((task) => !closedTaskIds.has(task.taskThreadId));
      if (nextTasks.length !== tasksRef.current.length) {
        tasksRef.current = nextTasks;
        setTasks(nextTasks);
      }
      return true;
    },
    [rejectTaskReady],
  );

  /** Thread snapshot 只进入共享 Timeline；请求起点 Runtime 不匹配时拒绝旧响应。@author kongweiguang */
  const projectTranscriptSnapshot = useCallback(
    (snapshot: TaskTranscriptSnapshot, requestFence: TimelineRuntimeFence | undefined): boolean =>
      sameRuntimeFence(runtimeFenceRef.current, requestFence) && applyTranscriptSnapshot(snapshot),
    [applyTranscriptSnapshot],
  );

  /**
   * 所有 read、event 与 mutation ACK 先进入同步 revision authority，再投影 React state；这样
   * 同一事件循环内的 follow-up 也能看到最新 CAS，不受批量渲染和 Promise 完成顺序影响。
   */
  const acceptTaskSummary = useCallback((incoming: TaskSummary): TaskSummary => {
    const current = tasksRef.current.find(
      (taskSummary) => taskSummary.taskThreadId === incoming.taskThreadId,
    );
    if (
      belongsToClosedTaskTree(
        tasksRef.current,
        incoming.taskThreadId,
        closedTaskIdsRef.current,
        incoming.parentThreadId,
      )
    )
      return current ?? incoming;
    const authoritative = current === undefined ? incoming : preferTaskSummary(current, incoming);
    const nextTasks = mergeTask(tasksRef.current, authoritative);
    tasksRef.current = nextTasks;
    setTasks(nextTasks);
    setDetail((current) =>
      current?.task.taskThreadId === authoritative.taskThreadId &&
      current.task.revision <= authoritative.revision
        ? { ...current, task: authoritative }
        : current,
    );
    return authoritative;
  }, []);

  /**
   * Task 与 Thread 使用独立 revision：已读事件可以先推进 Task，不能因此丢弃新模型/权限。
   * 正文、摘要和 Thread metadata 分别单调合并，迟到响应不能回滚任一权威状态。
   */
  const acceptTaskDetail = useCallback(
    (incoming: TaskReadModel): TaskSummary => {
      const authoritative = acceptTaskSummary(incoming.task);
      setDetail((current) => {
        if (current?.task.taskThreadId !== incoming.task.taskThreadId)
          return { ...incoming, task: authoritative };
        const content =
          preferTaskSummary(current.task, incoming.task) === current.task ? current : incoming;
        const thread =
          current.thread.revision > incoming.thread.revision ? current.thread : incoming.thread;
        return { ...content, task: authoritative, thread };
      });
      return authoritative;
    },
    [acceptTaskSummary],
  );

  /**
   * 自动已读先短暂合并活动突发，再挂到每个 Task 的 Promise lane；执行时重新读取同步
   * authority，因此 read、terminal 与 seen 的任意完成顺序都不会并发提交陈旧 revision。
   */
  const scheduleTaskSeen = useCallback(
    (candidate: TaskSummary): void => {
      const authoritative =
        tasksRef.current.find(
          (taskSummary) => taskSummary.taskThreadId === candidate.taskThreadId,
        ) ?? candidate;
      if (closedTaskIdsRef.current.has(authoritative.taskThreadId)) return;
      const taskThreadId = authoritative.taskThreadId;
      const priorTimer = seenTimerByTaskRef.current.get(taskThreadId);
      if (priorTimer !== undefined) clearTimeout(priorTimer);
      seenTimerByTaskRef.current.delete(taskThreadId);
      if (authoritative.unreadCount <= 0) {
        seenTargetByTaskRef.current.delete(taskThreadId);
        return;
      }
      seenTargetByTaskRef.current.set(
        taskThreadId,
        Math.max(
          seenTargetByTaskRef.current.get(taskThreadId) ?? 0,
          authoritative.latestActivitySequence,
        ),
      );
      const epoch = taskEpochRef.current;
      const timer = setTimeout(() => {
        seenTimerByTaskRef.current.delete(taskThreadId);
        const prior = seenChainByTaskRef.current.get(taskThreadId) ?? Promise.resolve();
        const operation: Promise<void> = prior
          .catch(() => undefined)
          .then(async () => {
            if (epoch !== taskEpochRef.current) return;
            if (closedTaskIdsRef.current.has(taskThreadId)) return;
            const throughActivitySequence = seenTargetByTaskRef.current.get(taskThreadId);
            const latest = tasksRef.current.find(
              (taskSummary) => taskSummary.taskThreadId === taskThreadId,
            );
            if (
              throughActivitySequence === undefined ||
              latest === undefined ||
              latest.unreadCount <= 0
            ) {
              seenTargetByTaskRef.current.delete(taskThreadId);
              return;
            }
            seenTargetByTaskRef.current.delete(taskThreadId);
            try {
              const seen = await port.seen({
                taskThreadId,
                expectedTaskRevision: latest.revision,
                throughActivitySequence: Math.max(
                  throughActivitySequence,
                  latest.latestActivitySequence,
                ),
              });
              if (epoch === taskEpochRef.current) acceptTaskSummary(seen.task);
            } catch {
              // 新 activity/read 会携带权威 revision 并重新排队；相同 revision 原地重试会形成热循环。
            }
          })
          .finally(() => {
            if (seenChainByTaskRef.current.get(taskThreadId) === operation)
              seenChainByTaskRef.current.delete(taskThreadId);
          });
        seenChainByTaskRef.current.set(taskThreadId, operation);
      }, TASK_SEEN_SETTLE_MS);
      seenTimerByTaskRef.current.set(taskThreadId, timer);
    },
    [acceptTaskSummary, port],
  );

  /** 调用动作始终取同步 authority 中较新的摘要，组件传入对象只承担 Task identity。 */
  const taskForMutation = useCallback((candidate: TaskSummary): TaskSummary => {
    const authoritative = tasksRef.current.find(
      (taskSummary) => taskSummary.taskThreadId === candidate.taskThreadId,
    );
    return authoritative !== undefined && authoritative.revision > candidate.revision
      ? authoritative
      : candidate;
  }, []);

  /** 根切换后只保留同一任务树的摘要，防止右栏短暂展示上一主任务后代。 */
  useEffect(() => {
    const seenTimers = seenTimerByTaskRef.current;
    const seenTargets = seenTargetByTaskRef.current;
    taskEpochRef.current += 1;
    for (const timer of seenTimers.values()) clearTimeout(timer);
    seenTimers.clear();
    seenTargets.clear();
    seenChainByTaskRef.current.clear();
    closedTaskIdsRef.current.clear();
    closingTaskThreadRef.current = undefined;
    readyTaskRef.current = undefined;
    setReadyTaskThreadId(undefined);
    for (const waiters of taskReadyWaitersRef.current.values())
      for (const waiter of waiters) waiter.reject(new Error("task root was changed"));
    taskReadyWaitersRef.current.clear();
    setClosingTaskRequest(undefined);
    const retainedTasks = tasksRef.current.filter((task) => task.rootThreadId === rootThreadId);
    tasksRef.current = retainedTasks;
    setTasks(retainedTasks);
    setDetail(undefined);
    setProgressSummary(undefined);
    followupRetryRef.current.clear();
    return () => {
      taskEpochRef.current += 1;
      for (const timer of seenTimers.values()) clearTimeout(timer);
      seenTimers.clear();
      seenTargets.clear();
    };
  }, [rootThreadId]);

  /** 读取摘要只在右栏真实可见时发生；事件到达仍可在后台无 IO 更新已知条目。 */
  const refresh = useCallback(async (): Promise<void> => {
    if (!visibleRef.current || rootThreadId === undefined) return;
    setLoading(true);
    setError(undefined);
    try {
      const result = await port.list({ rootThreadId });
      if (visibleRef.current)
        for (const taskSummary of result.items) acceptTaskSummary(taskSummary);
    } catch {
      if (visibleRef.current) setError(taskErrorMessage("list"));
    } finally {
      if (visibleRef.current) setLoading(false);
    }
  }, [acceptTaskSummary, port, rootThreadId]);

  useEffect(() => {
    if (visible) void refresh();
  }, [refresh, visible]);

  /**
   * Task detail 与 Timeline snapshot 独立收敛；snapshot 经共享 Store 的 revision reducer 接纳，
   * 重读复用当前 observe，不建立第二个句柄，也不在 controller 缓存正文。冲突恢复可额外传入
   * root/task epoch fence，避免切换期间的迟到读取提交到新工作面。
   * @author kongweiguang
   */
  const refreshDetailFor = useCallback(
    async (
      taskThreadId: string,
      epoch = detailEpochRef.current,
      scopeFence?: TaskRefreshScopeFence,
    ): Promise<{ detailReady: boolean; transcriptApplied: boolean }> => {
      if (
        epoch !== detailEpochRef.current ||
        !visibleRef.current ||
        activeTaskRef.current !== taskThreadId ||
        (scopeFence !== undefined &&
          (rootThreadRef.current !== scopeFence.rootThreadId ||
            taskEpochRef.current !== scopeFence.taskEpoch))
      )
        return { detailReady: false, transcriptApplied: false };
      const requestRuntimeFence = runtimeFenceRef.current;
      const [detailResult, transcriptResult] = await Promise.allSettled([
        port.read({ taskThreadId, limit: 200 }),
        transcriptPort.read({ threadId: taskThreadId, limit: 200 }),
      ]);
      if (
        epoch !== detailEpochRef.current ||
        !visibleRef.current ||
        activeTaskRef.current !== taskThreadId ||
        !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence) ||
        (scopeFence !== undefined &&
          (rootThreadRef.current !== scopeFence.rootThreadId ||
            taskEpochRef.current !== scopeFence.taskEpoch))
      )
        return { detailReady: false, transcriptApplied: false };
      let detailReady = false;
      let transcriptApplied = false;
      if (detailResult.status === "fulfilled") {
        const nextDetail = detailResult.value;
        const authoritative = acceptTaskDetail(nextDetail);
        setDetailError(undefined);
        scheduleTaskSeen(authoritative);
        detailReady = true;
      } else {
        setDetailError(taskErrorMessage("detail"));
      }
      if (transcriptResult.status === "fulfilled") {
        if (
          transcriptResult.value.threadId !== taskThreadId ||
          !projectTranscriptSnapshot(transcriptResult.value, requestRuntimeFence)
        ) {
          setTranscriptError(taskErrorMessage("transcript"));
        } else {
          transcriptApplied = true;
          setTranscriptError(undefined);
        }
      } else setTranscriptError(taskErrorMessage("transcript"));
      return { detailReady, transcriptApplied };
    },
    [acceptTaskDetail, port, projectTranscriptSnapshot, scheduleTaskSeen, transcriptPort],
  );

  /** 显式重试只针对当前可见实例，并以当前 epoch 拒绝切换后迟到结果。 */
  const refreshDetail = useCallback(async (): Promise<void> => {
    const taskThreadId = activeTaskRef.current;
    if (taskThreadId === undefined || !visibleRef.current) return;
    setDetailLoading(true);
    try {
      await refreshDetailFor(taskThreadId);
    } finally {
      if (activeTaskRef.current === taskThreadId) setDetailLoading(false);
    }
  }, [refreshDetailFor]);

  /**
   * 观察生命周期严格绑定当前可见实例；CAS 冲突只允许有界权威重读，cleanup 只 unobserve，
   * 绝不调用 task/cancel，也不允许关闭期间完成的 read 再次建立观察。
   */
  useEffect(() => {
    const epoch = detailEpochRef.current + 1;
    detailEpochRef.current = epoch;
    observationRef.current = undefined;
    readyTaskRef.current = undefined;
    setReadyTaskThreadId(undefined);
    setDetail(undefined);
    setProgressSummary(undefined);
    setDetailError(undefined);
    setTranscriptError(undefined);
    if (!visible || activeTaskThreadId === undefined) {
      setDetailLoading(false);
      return;
    }
    let active = true;
    let observationId: string | undefined;
    let transcriptObserved = false;
    const observationOwner: { readonly taskThreadId: string; stop: () => void } = {
      taskThreadId: activeTaskThreadId,
      stop: () => undefined,
    };
    /** 关闭事件与 React cleanup 共用一次性释放，防止 effect 仍挂载时遗留服务端观察。 */
    const stopObservation = (): void => {
      if (!active) return;
      active = false;
      rejectTaskReady(activeTaskThreadId, new Error("task activation was superseded"));
      if (observationRef.current === observationId) observationRef.current = undefined;
      if (observationId !== undefined) {
        const stoppedObservationId = observationId;
        observationId = undefined;
        void port.unobserve({ observationId: stoppedObservationId }).catch(() => undefined);
      }
      if (transcriptObserved) {
        transcriptObserved = false;
        void transcriptPort.unobserve({ threadId: activeTaskThreadId }).catch(() => undefined);
      }
      if (activeObservationOwnerRef.current === observationOwner)
        activeObservationOwnerRef.current = undefined;
    };
    observationOwner.stop = stopObservation;
    activeObservationOwnerRef.current = observationOwner;
    setDetailLoading(true);
    const requestRuntimeFence = runtimeFenceRef.current;
    const setup = async (): Promise<void> => {
      let initialTranscriptApplied = false;
      try {
        // Child 正文的连接订阅先于初始快照；Task Activity 的 observationId 不覆盖 Turn 流。
        const transcriptAck = await transcriptPort.observe({ threadId: activeTaskThreadId });
        if (!transcriptAck.accepted || transcriptAck.threadId !== activeTaskThreadId)
          throw new Error("child transcript observation identity changed");
        transcriptObserved = true;
        if (
          !active ||
          epoch !== detailEpochRef.current ||
          !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence)
        ) {
          transcriptObserved = false;
          await transcriptPort.unobserve({ threadId: activeTaskThreadId }).catch(() => undefined);
          return;
        }
        const [detailResult, transcriptResult] = await Promise.allSettled([
          port.read({ taskThreadId: activeTaskThreadId, limit: 200 }),
          transcriptPort.read({ threadId: activeTaskThreadId, limit: 200 }),
        ]);
        if (
          !active ||
          epoch !== detailEpochRef.current ||
          !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence)
        )
          return;
        if (transcriptResult.status === "fulfilled") {
          if (transcriptResult.value.threadId !== activeTaskThreadId) {
            setTranscriptError(taskErrorMessage("transcript"));
          } else {
            initialTranscriptApplied = projectTranscriptSnapshot(
              transcriptResult.value,
              requestRuntimeFence,
            );
            setTranscriptError(
              initialTranscriptApplied ? undefined : taskErrorMessage("transcript"),
            );
          }
        } else setTranscriptError(taskErrorMessage("transcript"));
        if (detailResult.status === "rejected") {
          setDetailError(taskErrorMessage("detail"));
          rejectTaskReady(activeTaskThreadId, new Error(taskErrorMessage("detail")));
          return;
        }
        let authoritative = acceptTaskDetail(detailResult.value);
        setDetailError(undefined);
        for (let attempt = 0; attempt < TASK_OBSERVE_MAX_ATTEMPTS; attempt += 1) {
          if (
            !active ||
            epoch !== detailEpochRef.current ||
            !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence)
          )
            return;
          try {
            const observed = await port.observe({
              taskThreadId: activeTaskThreadId,
              expectedTaskRevision: authoritative.revision,
            });
            if (
              !active ||
              epoch !== detailEpochRef.current ||
              !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence)
            ) {
              await port
                .unobserve({ observationId: observed.observationId })
                .catch(() => undefined);
              return;
            }
            observationId = observed.observationId;
            observationRef.current = observationId;
            const latest =
              tasksRef.current.find(
                (taskSummary) => taskSummary.taskThreadId === activeTaskThreadId,
              ) ?? authoritative;
            scheduleTaskSeen(latest);
            // 初始 read 与 observe ACK 之间可能错过一次快速终态事件；ACK 后权威重读补齐
            // 该窗口，且由单调 Transcript 投影拒绝迟到的旧 running 快照。
            const refreshed = await refreshDetailFor(activeTaskThreadId, epoch);
            if (initialTranscriptApplied || refreshed.transcriptApplied)
              resolveTaskReady(activeTaskThreadId, epoch);
            else rejectTaskReady(activeTaskThreadId, new Error(taskErrorMessage("transcript")));
            return;
          } catch (error) {
            if (!active || epoch !== detailEpochRef.current) return;
            if (!isTaskRevisionConflict(error) || attempt === TASK_OBSERVE_MAX_ATTEMPTS - 1)
              throw error;
            const refreshed = await port.read({ taskThreadId: activeTaskThreadId, limit: 200 });
            if (!active || epoch !== detailEpochRef.current) return;
            authoritative = acceptTaskDetail(refreshed);
          }
        }
      } catch {
        if (active && epoch === detailEpochRef.current) {
          setDetailError(taskErrorMessage("detail"));
          rejectTaskReady(activeTaskThreadId, new Error(taskErrorMessage("detail")));
        }
      } finally {
        if (active && epoch === detailEpochRef.current) setDetailLoading(false);
      }
    };
    void setup();
    return () => {
      detailEpochRef.current += 1;
      stopObservation();
    };
  }, [
    activeTaskThreadId,
    runtimeFence?.generation,
    runtimeFence?.serverInstanceId,
    acceptTaskDetail,
    acceptClosedTaskThreads,
    projectTranscriptSnapshot,
    port,
    rejectTaskReady,
    refreshDetailFor,
    resolveTaskReady,
    scheduleTaskSeen,
    transcriptPort,
    visible,
  ]);

  /** Runtime 已完成严格 Schema 校验；close 事件还必须携带其真实 runtime pair。@author kongweiguang */
  useEffect(
    () =>
      subscribeTaskHostEvents((event) => {
        if (rootThreadId === undefined || event.params.rootThreadId !== rootThreadId) return;
        if (event.method === "task/closed") {
          const params = event.params as typeof event.params & {
            serverInstanceId?: unknown;
            generation?: unknown;
          };
          const eventFence =
            typeof params.serverInstanceId === "string" &&
            Number.isSafeInteger(params.generation) &&
            (params.generation as number) > 0
              ? {
                  serverInstanceId: params.serverInstanceId,
                  generation: params.generation as number,
                }
              : undefined;
          if (event.params.closedThreadIds.includes(event.params.taskThreadId))
            void acceptClosedTaskThreads(event.params.closedThreadIds, eventFence);
          return;
        }
        const eventParentThreadId =
          event.method === "task/activity" ? event.params.task.parentThreadId : undefined;
        if (
          belongsToClosedTaskTree(
            tasksRef.current,
            event.params.taskThreadId,
            closedTaskIdsRef.current,
            eventParentThreadId,
          )
        )
          return;
        if (event.method === "task/activity") {
          const wasKnown = tasksRef.current.some(
            (task) => task.taskThreadId === event.params.task.taskThreadId,
          );
          const authoritative = acceptTaskSummary(event.params.task);
          if (!wasKnown && event.params.task.taskKind === "subagent")
            onSubagentDiscoveredRef.current?.();
          if (visibleRef.current && activeTaskRef.current === event.params.taskThreadId) {
            scheduleTaskSeen(authoritative);
            void refreshDetailFor(event.params.taskThreadId);
          }
          return;
        }
        if (event.method === "task/progress") {
          if (
            event.params.observationId === observationRef.current &&
            activeTaskRef.current === event.params.taskThreadId
          )
            setProgressSummary(event.params.safeSummary);
          return;
        }
        const current = tasksRef.current.find(
          (taskSummary) => taskSummary.taskThreadId === event.params.taskThreadId,
        );
        if (current !== undefined && current.revision <= event.params.taskRevision)
          acceptTaskSummary({
            ...current,
            revision: event.params.taskRevision,
            unreadCount: event.params.unreadCount,
          });
      }),
    [acceptClosedTaskThreads, acceptTaskSummary, refreshDetailFor, rootThreadId, scheduleTaskSeen],
  );

  /**
   * 创建空闲 child thread 并冻结首轮偏好；CAS 必须取点击时根 Thread 的权威 revision，
   * 不能复用可能在主任务更新后过期的父级摘要。重读只在明确的 CAS 冲突后发生一次，
   * 其它错误不重试，避免 ACK 不确定时意外创建重复 child。
   */
  const createSideTask = useCallback(
    async (input: {
      taskName: string;
      sourceThreadId?: string;
      sourceThreadRevision?: number;
      preferences?: ConversationModelSelection & {
        accessMode: ConversationAccessMode;
        collaborationMode: ConversationCollaborationMode;
      };
    }): Promise<TaskSummary> => {
      if (rootThreadId === undefined) throw new Error(taskErrorMessage("create"));
      const epoch = taskEpochRef.current;
      const expectedRootThreadId = rootThreadId;
      const expectedSourceThreadId = input.sourceThreadId ?? expectedRootThreadId;
      const suppliedSourceRevision = input.sourceThreadRevision;

      /** 来源必须属于当前根树；具体 parent 关系仍由 App Server 在 create CAS 中裁决。 */
      const sourceIsOwned = (): boolean =>
        expectedSourceThreadId === expectedRootThreadId ||
        tasksRef.current.some(
          (task) =>
            task.taskThreadId === expectedSourceThreadId &&
            task.rootThreadId === expectedRootThreadId &&
            !closedTaskIdsRef.current.has(task.taskThreadId),
        );

      /** 来源快照只承担创建 CAS，不投影到当前 child transcript。 */
      const readSourceSnapshot = async (): Promise<TaskTranscriptSnapshot> => {
        if (!sourceIsOwned()) throw new Error("side task source thread is unavailable");
        const snapshot = await transcriptPort.read({
          threadId: expectedSourceThreadId,
          limit: 1,
        });
        if (
          epoch !== taskEpochRef.current ||
          rootThreadRef.current !== expectedRootThreadId ||
          snapshot.threadId !== expectedSourceThreadId
        )
          throw new Error("side task source thread identity mismatch");
        return snapshot;
      };

      try {
        if (!sourceIsOwned()) throw new Error("side task source thread is unavailable");
        let expectedParentRevision =
          suppliedSourceRevision ?? (await readSourceSnapshot()).revision;
        let result;
        try {
          result = await port.create({
            parentThreadId: expectedSourceThreadId,
            parentTurnId: null,
            expectedParentRevision: expectedParentRevision,
            taskName: input.taskName,
            preferences: input.preferences,
          });
        } catch (error) {
          if (!isTaskRevisionConflict(error)) throw error;
          expectedParentRevision = (await readSourceSnapshot()).revision;
          result = await port.create({
            parentThreadId: expectedSourceThreadId,
            parentTurnId: null,
            expectedParentRevision,
            taskName: input.taskName,
            preferences: input.preferences,
          });
        }
        return acceptTaskSummary(result.task);
      } catch {
        throw new Error(taskErrorMessage("create"));
      }
    },
    [acceptTaskSummary, port, rootThreadId, transcriptPort],
  );

  /**
   * 显式重命名先读取最新 Thread revision 再复用既有 rename CAS；Task projection revision
   * 与 Thread revision 是独立序列，不能错误地互换。成功后才更新本地展示投影。
   */
  const rename = useCallback(
    async (task: TaskSummary, title: string): Promise<TaskSummary> => {
      const normalized = title.trim();
      if (task.taskKind !== "side_task" || normalized === "" || normalized.length > 96)
        throw new Error(taskErrorMessage("rename"));
      const epoch = taskEpochRef.current;
      const expectedRootThreadId = rootThreadId;
      try {
        // ACK 后必须读取完整首屏快照；limit=1 会留下 nextCursor，无法准入共享 reducer。
        const snapshot = await transcriptPort.read({ threadId: task.taskThreadId, limit: 1 });
        if (
          epoch !== taskEpochRef.current ||
          expectedRootThreadId !== rootThreadRef.current ||
          snapshot.threadId !== task.taskThreadId
        )
          throw new Error("task transcript identity mismatch");
        const renamed = await renamePort.rename({
          threadId: task.taskThreadId,
          title: normalized,
          expectedThreadRevision: snapshot.revision,
        });
        if (
          epoch !== taskEpochRef.current ||
          expectedRootThreadId !== rootThreadRef.current ||
          renamed.threadId !== task.taskThreadId
        )
          throw new Error("renamed thread identity mismatch");
        const refreshed = await port.read({ taskThreadId: task.taskThreadId, limit: 1 });
        if (
          epoch !== taskEpochRef.current ||
          expectedRootThreadId !== rootThreadRef.current ||
          refreshed.task.taskThreadId !== renamed.threadId ||
          refreshed.task.taskName !== renamed.title
        )
          throw new Error("renamed task projection mismatch");
        return acceptTaskDetail(refreshed);
      } catch {
        throw new Error(taskErrorMessage("rename"));
      }
    },
    [acceptTaskDetail, port, renamePort, rootThreadId, transcriptPort],
  );

  /**
   * followup 与 followupTurn 共用一次 TaskCoordinator 调用和一次 child thread/read；只有 ACK
   * 与 Thread revision 都确认后才释放幂等键，避免 ACK 后 read 失败时重试制造第二个 Turn。
   * 每次 await 后都确认发起时的 Runtime 与 root 仍拥有结果，关闭/切换后的迟到 ACK 不复活摘要。
   * @author kongweiguang
   */
  const executeFollowup = useCallback(
    async (
      task: TaskSummary,
      content: TaskContentBlock[],
      senderThreadId?: string,
    ): Promise<{
      readonly task: TaskSummary;
      readonly turnId: string;
      readonly threadRevision: number;
    }> => {
      if (rootThreadId === undefined) throw new Error(taskErrorMessage("followup"));
      const requestRuntimeFence = runtimeFenceRef.current;
      if (requestRuntimeFence === undefined) throw new Error(taskErrorMessage("followup"));
      const requestTaskEpoch = taskEpochRef.current;
      const expectedSenderThreadId = senderThreadId ?? rootThreadId;
      if (
        expectedSenderThreadId !== rootThreadId &&
        !tasksRef.current.some(
          (candidate) =>
            candidate.taskThreadId === expectedSenderThreadId &&
            candidate.rootThreadId === rootThreadId &&
            !closedTaskIdsRef.current.has(candidate.taskThreadId),
        )
      )
        throw new Error(taskErrorMessage("followup"));
      if (
        closingTaskThreadRef.current?.taskThreadId === task.taskThreadId ||
        closingTaskThreadId === task.taskThreadId ||
        closedTaskIdsRef.current.has(task.taskThreadId)
      )
        throw new Error(taskErrorMessage("followup"));
      const authoritative = taskForMutation(task);
      const contentIdentity = taskContentIdentity(content);
      const retained = followupRetryRef.current.get(task.taskThreadId);
      const operationKey =
        retained?.contentIdentity === contentIdentity
          ? retained.operationKey
          : createOperationKey();
      followupRetryRef.current.set(task.taskThreadId, { contentIdentity, operationKey });
      try {
        let result;
        try {
          result = await port.followup({
            senderThreadId: expectedSenderThreadId,
            targetThreadId: task.taskThreadId,
            content,
            operationKey,
            expectedTaskRevision: authoritative.revision,
          });
        } catch (firstError) {
          if (
            !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence) ||
            rootThreadRef.current !== rootThreadId ||
            taskEpochRef.current !== requestTaskEpoch ||
            closedTaskIdsRef.current.has(task.taskThreadId)
          )
            throw firstError;
          const refreshedRead = await port.read({ taskThreadId: task.taskThreadId, limit: 1 });
          if (
            !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence) ||
            rootThreadRef.current !== rootThreadId ||
            taskEpochRef.current !== requestTaskEpoch ||
            closedTaskIdsRef.current.has(task.taskThreadId)
          )
            throw firstError;
          const refreshed = acceptTaskSummary(refreshedRead.task);
          if (refreshed.revision <= authoritative.revision) throw firstError;
          result = await port.followup({
            senderThreadId: expectedSenderThreadId,
            targetThreadId: task.taskThreadId,
            content,
            operationKey,
            expectedTaskRevision: refreshed.revision,
          });
        }
        if (
          !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence) ||
          rootThreadRef.current !== rootThreadId ||
          taskEpochRef.current !== requestTaskEpoch ||
          closedTaskIdsRef.current.has(task.taskThreadId)
        )
          throw new Error("side task follow-up result is stale");
        // ACK 后必须读取完整首屏快照；limit=1 会留下 nextCursor，无法准入共享 reducer。
        const snapshot = await transcriptPort.read({ threadId: task.taskThreadId });
        if (
          snapshot.threadId !== task.taskThreadId ||
          !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence) ||
          rootThreadRef.current !== rootThreadId ||
          taskEpochRef.current !== requestTaskEpoch ||
          closedTaskIdsRef.current.has(task.taskThreadId)
        )
          throw new Error("task transcript identity mismatch");
        if (!projectTranscriptSnapshot(snapshot, requestRuntimeFence))
          throw new Error("task transcript projection failed");
        const acceptedTask = acceptTaskSummary(result.task);
        if (followupRetryRef.current.get(task.taskThreadId)?.operationKey === operationKey)
          followupRetryRef.current.delete(task.taskThreadId);
        return { task: acceptedTask, turnId: result.turnId, threadRevision: snapshot.revision };
      } catch {
        throw new Error(taskErrorMessage("followup"));
      }
    },
    [
      acceptTaskSummary,
      closingTaskThreadId,
      port,
      projectTranscriptSnapshot,
      rootThreadId,
      taskForMutation,
      transcriptPort,
    ],
  );

  const followup = useCallback(
    async (
      task: TaskSummary,
      content: TaskContentBlock[],
      senderThreadId?: string,
    ): Promise<TaskSummary> => (await executeFollowup(task, content, senderThreadId)).task,
    [executeFollowup],
  );

  /** TaskCoordinator ACK 适配为共享 interaction 的 Turn identity，禁止直接走 turn/start。 */
  const followupTurn = useCallback(
    async (
      task: TaskSummary,
      content: TaskContentBlock[],
      senderThreadId?: string,
    ): Promise<ConversationAcceptedTurn> => {
      const accepted = await executeFollowup(task, content, senderThreadId);
      return {
        accepted: true,
        turnId: accepted.turnId,
        queued: !["completed", "failed", "cancelled"].includes(accepted.task.state),
        threadRevision: accepted.threadRevision,
      };
    },
    [executeFollowup],
  );

  /**
   * 关闭只允许独立侧聊使用；收到权威 ACK 后立即压制并移除完整 Thread 集合，再把附件
   * session 清理交给预览 owner 异步处理，避免 native 资源释放延迟或失败回滚业务状态。
   * 清理拒绝只有在请求起点的 Thread/Runtime scope 仍有效时才展示为可重试失败；锁按
   * SID+generation 对象所有权释放，旧运行时 finally 不能释放新实例的同名 Thread 锁。
   * @author kongweiguang
   */
  const close = useCallback(
    async (task: TaskSummary): Promise<void> => {
      if (task.taskKind !== "side_task") throw new Error(taskErrorMessage("close"));
      if (closedTaskIdsRef.current.has(task.taskThreadId)) return;
      const requestRuntimeFence = runtimeFenceRef.current;
      if (requestRuntimeFence === undefined) throw new Error(taskErrorMessage("close"));
      const currentClose = closingTaskThreadRef.current;
      if (
        currentClose !== undefined &&
        sameRuntimeFence(currentClose.runtimeFence, requestRuntimeFence)
      )
        throw new Error(taskErrorMessage("close"));
      const requestRootThreadId = rootThreadRef.current;
      const closeRequest: ClosingTaskRequest = {
        taskThreadId: task.taskThreadId,
        runtimeFence: requestRuntimeFence,
      };
      closingTaskThreadRef.current = closeRequest;
      setClosingTaskRequest(closeRequest);
      try {
        let result: Awaited<ReturnType<TaskPort["close"]>>;
        try {
          result = await port.close({ taskThreadId: task.taskThreadId });
        } catch {
          if (closedTaskIdsRef.current.has(task.taskThreadId)) return;
          throw new Error(taskErrorMessage("close"));
        }
        if (!result.closedThreadIds.includes(task.taskThreadId)) {
          if (closedTaskIdsRef.current.has(task.taskThreadId)) return;
          throw new Error(taskErrorMessage("close"));
        }
        const accepted = await acceptClosedTaskThreads(result.closedThreadIds, requestRuntimeFence);
        const currentRuntimeFence = runtimeFenceRef.current;
        const requestScopeStillCurrent =
          rootThreadRef.current === requestRootThreadId &&
          (currentRuntimeFence === undefined ||
            sameRuntimeFence(currentRuntimeFence, requestRuntimeFence));
        if (!accepted && requestScopeStillCurrent) throw new Error(taskErrorMessage("close"));
      } finally {
        if (closingTaskThreadRef.current === closeRequest) {
          closingTaskThreadRef.current = undefined;
          setClosingTaskRequest((current) => (current === closeRequest ? undefined : current));
        }
      }
    },
    [acceptClosedTaskThreads, port],
  );

  /**
   * 取消只由显式确认触发；CAS 冲突并行重读当前详情与 root Task 树一次，依现有 identity fence
   * 收敛最新范围后交回用户重确认，绝不自动重发副作用。
   */
  const cancel = useCallback(
    async (task: TaskSummary): Promise<TaskSummary> => {
      const requestRootThreadId = rootThreadRef.current;
      const requestTaskEpoch = taskEpochRef.current;
      const requestDetailEpoch = detailEpochRef.current;
      /** 仅原 root、Task/详情 epoch 与可见 Thread 仍拥有请求时，才允许恢复取消上下文。 */
      const ownsCancelContext = (): boolean =>
        requestRootThreadId === rootThreadRef.current &&
        requestTaskEpoch === taskEpochRef.current &&
        requestDetailEpoch === detailEpochRef.current &&
        visibleRef.current &&
        activeTaskRef.current === task.taskThreadId &&
        !closedTaskIdsRef.current.has(task.taskThreadId);
      const authoritative = taskForMutation(task);
      try {
        const result = await port.cancel({
          taskThreadId: task.taskThreadId,
          expectedTaskRevision: authoritative.revision,
        });
        return acceptTaskSummary(result.task);
      } catch (error) {
        if (!isTaskRevisionConflict(error)) throw new Error(taskErrorMessage("cancel"));
        if (ownsCancelContext()) {
          if (requestRootThreadId === undefined) {
            setError(taskErrorMessage("list"));
          } else {
            const [detailResult, taskListResult] = await Promise.allSettled([
              refreshDetailFor(task.taskThreadId, requestDetailEpoch, {
                rootThreadId: requestRootThreadId,
                taskEpoch: requestTaskEpoch,
              }),
              port.list({ rootThreadId: requestRootThreadId }),
            ]);
            if (ownsCancelContext()) {
              if (taskListResult.status === "fulfilled") {
                setError(undefined);
                for (const summary of taskListResult.value.items)
                  if (summary.rootThreadId === requestRootThreadId) acceptTaskSummary(summary);
              } else {
                setError(taskErrorMessage("list"));
              }
              if (detailResult.status === "rejected") setDetailError(taskErrorMessage("detail"));
            }
          }
        }
        throw taskRevisionConflictError();
      }
    },
    [acceptTaskSummary, port, refreshDetailFor, taskForMutation],
  );

  /**
   * 恢复从一次新的权威 Thread 快照读取最早 suspended Turn 与 revision，避免在 controller
   * 缓存正文；调用成功后重读 Task/Timeline，不乐观改成 running，也不创建新 Turn。
   * Runtime pair 与 task epoch 固定在点击时，迟到读取或 ACK 不能投影到新 owner。
   * @author kongweiguang
   */
  const resume = useCallback(
    async (task: TaskSummary): Promise<void> => {
      if (task.state !== "suspended" || activeTaskThreadId !== task.taskThreadId)
        throw new Error(taskErrorMessage("resume"));
      try {
        const epoch = taskEpochRef.current;
        const requestRuntimeFence = runtimeFenceRef.current;
        if (requestRuntimeFence === undefined) throw new Error("task Runtime is unavailable");
        const snapshot = await transcriptPort.read({ threadId: task.taskThreadId, limit: 200 });
        if (
          epoch !== taskEpochRef.current ||
          activeTaskRef.current !== task.taskThreadId ||
          snapshot.threadId !== task.taskThreadId ||
          closedTaskIdsRef.current.has(task.taskThreadId) ||
          !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence) ||
          !projectTranscriptSnapshot(snapshot, requestRuntimeFence)
        )
          throw new Error("task resume snapshot is stale");
        const suspendedTurn = snapshot.turns.find((turn) => turn.status === "suspended");
        if (suspendedTurn === undefined) throw new Error("suspended turn unavailable");
        await resumePort.resume({
          turnId: suspendedTurn.turnId,
          expectedThreadRevision: snapshot.revision,
        });
        if (
          epoch !== taskEpochRef.current ||
          activeTaskRef.current !== task.taskThreadId ||
          closedTaskIdsRef.current.has(task.taskThreadId) ||
          !sameRuntimeFence(runtimeFenceRef.current, requestRuntimeFence)
        )
          throw new Error("task resume result is stale");
        await refreshDetailFor(task.taskThreadId);
      } catch {
        throw new Error(taskErrorMessage("resume"));
      }
    },
    [activeTaskThreadId, projectTranscriptSnapshot, refreshDetailFor, resumePort, transcriptPort],
  );

  /** Approval ACK 后重读当前详情，避免 UI 提前猜测 Tool 是否继续执行。 */
  const approvalRespond = useCallback(
    async (
      approvalId: string,
      turnId: string,
      expectedThreadRevision: number,
      decision: "approve" | "deny",
    ): Promise<void> => {
      try {
        await approvalPort.respond({ approvalId, turnId, expectedThreadRevision, decision });
        if (activeTaskThreadId !== undefined) await refreshDetailFor(activeTaskThreadId);
      } catch {
        throw new Error(taskErrorMessage("approval"));
      }
    },
    [activeTaskThreadId, approvalPort, refreshDetailFor],
  );

  return useMemo(
    () => ({
      tasks,
      loading,
      error,
      detail,
      readyTaskThreadId,
      detailLoading,
      detailError,
      transcriptError,
      progressSummary,
      closingTaskThreadId,
      refresh,
      refreshDetail,
      waitForTaskReady,
      createSideTask,
      rename,
      followup,
      followupTurn,
      close,
      cancel,
      resume,
      approvalRespond,
    }),
    [
      approvalRespond,
      cancel,
      close,
      closingTaskThreadId,
      createSideTask,
      detail,
      detailError,
      detailLoading,
      error,
      followup,
      followupTurn,
      loading,
      progressSummary,
      refresh,
      refreshDetail,
      rename,
      resume,
      waitForTaskReady,
      tasks,
      readyTaskThreadId,
      transcriptError,
    ],
  );
}
