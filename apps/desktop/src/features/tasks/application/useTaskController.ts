// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type Dispatch,
  type SetStateAction,
} from "react";
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
} from "@/features/conversation";
import { subscribeTaskHostEvents } from "./taskEventBus";
import type { TaskContentBlock, TaskReadModel, TaskState, TaskSummary } from "../domain/taskModel";

const TASK_SEEN_SETTLE_MS = 75;
const TASK_OBSERVE_MAX_ATTEMPTS = 3;

export interface TaskController {
  readonly tasks: readonly TaskSummary[];
  readonly loading: boolean;
  readonly error?: string;
  readonly detail?: TaskReadModel;
  readonly transcript?: TaskTranscriptSnapshot;
  readonly detailLoading: boolean;
  readonly detailError?: string;
  readonly transcriptError?: string;
  readonly progressSummary?: string;
  readonly closingTaskThreadId?: string;
  readonly refresh: () => Promise<void>;
  readonly refreshDetail: () => Promise<void>;
  /** 等待指定侧聊的当前详情、正文和 observe 都完成，避免创建后立即发送落入旧 Thread。 */
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
  readonly renamePort: TaskThreadRenamePort;
  readonly approvalPort: TaskApprovalPort;
  readonly resumePort: TaskResumePort;
  readonly onSubagentDiscovered?: () => void;
}

interface TaskReadyWaiter {
  readonly resolve: () => void;
  readonly reject: (reason?: unknown) => void;
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
function isTaskRevisionConflict(error: unknown): boolean {
  return (
    error !== null &&
    typeof error === "object" &&
    "code" in error &&
    error.code === "TASK_CONTEXT_REVISION_CONFLICT"
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
 * 侧聊关闭是生命周期边界，已知后代必须一起从本地摘要投影中抑制；仅删除侧聊自身会让
 * 迟到的子任务事件重新出现在原主会话里。集合同时包含根，便于统一处理后续事件与 read。
 */
function collectTaskTreeIds(tasks: readonly TaskSummary[], rootTaskThreadId: string): Set<string> {
  const childrenByParent = new Map<string, string[]>();
  for (const task of tasks) {
    const children = childrenByParent.get(task.parentThreadId) ?? [];
    children.push(task.taskThreadId);
    childrenByParent.set(task.parentThreadId, children);
  }
  const result = new Set<string>([rootTaskThreadId]);
  const queue = [rootTaskThreadId];
  while (queue.length > 0) {
    const parentThreadId = queue.shift();
    if (parentThreadId === undefined) break;
    for (const childThreadId of childrenByParent.get(parentThreadId) ?? []) {
      if (result.has(childThreadId)) continue;
      result.add(childThreadId);
      queue.push(childThreadId);
    }
  }
  return result;
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

/** 幂等键只承担当前显式动作去重，不保存正文或用户身份。 */
function taskIdempotencyKey(prefix: string): string {
  const entropy = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
  return `${prefix}-${entropy}`.slice(0, 128);
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
 * Transcript 是按 Thread revision 单调收敛的投影；激活读取、事件重读和 follow-up ACK
 * 可能交错完成，迟到的旧快照不能把已显示的终态正文退回运行中。相同 revision 仍接受
 * 新快照，因为服务端可能在不改变 Task revision 的情况下补齐正文页内字段。
 */
function mergeTranscriptSnapshots(
  current: TaskTranscriptSnapshot | undefined,
  incoming: TaskTranscriptSnapshot,
): TaskTranscriptSnapshot {
  if (current?.threadId !== incoming.threadId) return incoming;
  if (current.revision > incoming.revision) return current;
  if (
    current.revision === incoming.revision &&
    isTerminalTranscript(current) &&
    !isTerminalTranscript(incoming)
  )
    return current;
  return incoming;
}

/** 将正文写入 React projection 前先更新同步 authority，避免同一事件循环内发生回滚。 */
function acceptTranscriptSnapshot(
  setTranscript: Dispatch<SetStateAction<TaskTranscriptSnapshot | undefined>>,
  current: TaskTranscriptSnapshot | undefined,
  incoming: TaskTranscriptSnapshot,
): TaskTranscriptSnapshot {
  const authoritative = mergeTranscriptSnapshots(current, incoming);
  setTranscript((rendered) => mergeTranscriptSnapshots(rendered, authoritative));
  return authoritative;
}

/** 只比较当前快照最后一个 Turn；同 revision 的旧 running 快照不能抹掉终态正文。 */
function isTerminalTranscript(snapshot: TaskTranscriptSnapshot): boolean {
  const latestTurn = snapshot.turns.at(-1);
  return (
    latestTurn !== undefined &&
    (latestTurn.status === "completed" ||
      latestTurn.status === "failed" ||
      latestTurn.status === "cancelled")
  );
}

/**
 * Controller 常驻消费低频摘要事件，但只有详情可见时才读取 task/read、thread/read 并建立
 * observe；隐藏 Workbench 或切换 Tab 会立即释放观察句柄，不缓存其它 Child Transcript。
 */
export function useTaskController({
  rootThreadId,
  activeTaskThreadId,
  visible,
  port,
  transcriptPort,
  renamePort,
  approvalPort,
  resumePort,
  onSubagentDiscovered,
}: TaskControllerOptions): TaskController {
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [detail, setDetail] = useState<TaskReadModel>();
  const [transcript, setTranscript] = useState<TaskTranscriptSnapshot>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string>();
  const [transcriptError, setTranscriptError] = useState<string>();
  const [progressSummary, setProgressSummary] = useState<string>();
  const [closingTaskThreadId, setClosingTaskThreadId] = useState<string>();
  const detailEpochRef = useRef(0);
  const observationRef = useRef<string | undefined>(undefined);
  const transcriptRef = useRef<TaskTranscriptSnapshot | undefined>(undefined);
  const pendingTranscriptByTaskRef = useRef<Map<string, TaskTranscriptSnapshot>>(new Map());
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
  const followupRetryRef = useRef<
    Map<string, { readonly contentIdentity: string; readonly idempotencyKey: string }>
  >(new Map());
  const closedTaskIdsRef = useRef<Set<string>>(new Set());
  const closingTaskThreadRef = useRef<string | undefined>(undefined);
  visibleRef.current = visible;
  activeTaskRef.current = activeTaskThreadId;
  rootThreadRef.current = rootThreadId;
  onSubagentDiscoveredRef.current = onSubagentDiscovered;

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

  /** 只允许当前 active Thread 更新正文 authority；follow-up 的非 active 快照另存待激活缓存。 */
  const acceptVisibleTranscript = useCallback(
    (incoming: TaskTranscriptSnapshot): TaskTranscriptSnapshot => {
      const authoritative = acceptTranscriptSnapshot(
        setTranscript,
        transcriptRef.current,
        incoming,
      );
      transcriptRef.current = authoritative;
      return authoritative;
    },
    [],
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
    transcriptRef.current = undefined;
    pendingTranscriptByTaskRef.current.clear();
    for (const waiters of taskReadyWaitersRef.current.values())
      for (const waiter of waiters) waiter.reject(new Error("task root was changed"));
    taskReadyWaitersRef.current.clear();
    setClosingTaskThreadId(undefined);
    const retainedTasks = tasksRef.current.filter((task) => task.rootThreadId === rootThreadId);
    tasksRef.current = retainedTasks;
    setTasks(retainedTasks);
    setDetail(undefined);
    setTranscript(undefined);
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
   * 详情与 Transcript 独立收敛：任一读取失败都保留另一份已验证投影，避免旧摘要失败把
   * 可操作 Approval 或最终答复整页抹掉。重读复用当前 observe，不建立第二个句柄。
   */
  const refreshDetailFor = useCallback(
    async (taskThreadId: string, epoch = detailEpochRef.current): Promise<void> => {
      const [detailResult, transcriptResult] = await Promise.allSettled([
        port.read({ taskThreadId, limit: 200 }),
        transcriptPort.read({ threadId: taskThreadId, limit: 200 }),
      ]);
      if (
        epoch !== detailEpochRef.current ||
        !visibleRef.current ||
        activeTaskRef.current !== taskThreadId
      )
        return;
      if (detailResult.status === "fulfilled") {
        const nextDetail = detailResult.value;
        const authoritative = acceptTaskDetail(nextDetail);
        setDetailError(undefined);
        scheduleTaskSeen(authoritative);
      } else {
        setDetailError(taskErrorMessage("detail"));
      }
      if (transcriptResult.status === "fulfilled") {
        if (transcriptResult.value.threadId !== taskThreadId) {
          setTranscriptError(taskErrorMessage("transcript"));
        } else {
          acceptVisibleTranscript(transcriptResult.value);
          setTranscriptError(undefined);
        }
      } else setTranscriptError(taskErrorMessage("transcript"));
    },
    [acceptTaskDetail, acceptVisibleTranscript, port, scheduleTaskSeen, transcriptPort],
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
    transcriptRef.current = undefined;
    setDetail(undefined);
    setTranscript(undefined);
    setProgressSummary(undefined);
    setDetailError(undefined);
    setTranscriptError(undefined);
    if (!visible || activeTaskThreadId === undefined) {
      setDetailLoading(false);
      return;
    }
    let active = true;
    let observationId: string | undefined;
    setDetailLoading(true);
    const setup = async (): Promise<void> => {
      let initialTranscriptReady = false;
      try {
        const [detailResult, transcriptResult] = await Promise.allSettled([
          port.read({ taskThreadId: activeTaskThreadId, limit: 200 }),
          transcriptPort.read({ threadId: activeTaskThreadId, limit: 200 }),
        ]);
        if (!active || epoch !== detailEpochRef.current) return;
        if (transcriptResult.status === "fulfilled") {
          if (transcriptResult.value.threadId !== activeTaskThreadId) {
            setTranscriptError(taskErrorMessage("transcript"));
          } else {
            const pending = pendingTranscriptByTaskRef.current.get(activeTaskThreadId);
            pendingTranscriptByTaskRef.current.delete(activeTaskThreadId);
            acceptVisibleTranscript(
              pending === undefined
                ? transcriptResult.value
                : mergeTranscriptSnapshots(pending, transcriptResult.value),
            );
            initialTranscriptReady = true;
            setTranscriptError(undefined);
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
          if (!active || epoch !== detailEpochRef.current) return;
          try {
            const observed = await port.observe({
              taskThreadId: activeTaskThreadId,
              expectedTaskRevision: authoritative.revision,
            });
            if (!active || epoch !== detailEpochRef.current) {
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
            await refreshDetailFor(activeTaskThreadId, epoch);
            if (initialTranscriptReady) resolveTaskReady(activeTaskThreadId, epoch);
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
      active = false;
      detailEpochRef.current += 1;
      rejectTaskReady(activeTaskThreadId, new Error("task activation was superseded"));
      if (observationRef.current === observationId) observationRef.current = undefined;
      if (observationId !== undefined)
        void port.unobserve({ observationId }).catch(() => undefined);
    };
  }, [
    activeTaskThreadId,
    acceptTaskDetail,
    acceptVisibleTranscript,
    port,
    rejectTaskReady,
    refreshDetailFor,
    resolveTaskReady,
    scheduleTaskSeen,
    transcriptPort,
    visible,
  ]);

  /** Runtime 已完成严格 Schema 校验；这里仅按 root/observation identity 更新局部投影。 */
  useEffect(
    () =>
      subscribeTaskHostEvents((event) => {
        if (rootThreadId === undefined || event.params.rootThreadId !== rootThreadId) return;
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
    [acceptTaskSummary, refreshDetailFor, rootThreadId, scheduleTaskSeen],
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
        setTranscript((current) =>
          current?.threadId === renamed.threadId
            ? { ...current, revision: Math.max(current.revision, renamed.revision) }
            : current,
        );
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
        closingTaskThreadRef.current === task.taskThreadId ||
        closingTaskThreadId === task.taskThreadId ||
        closedTaskIdsRef.current.has(task.taskThreadId)
      )
        throw new Error(taskErrorMessage("followup"));
      const epoch = taskEpochRef.current;
      const authoritative = taskForMutation(task);
      const contentIdentity = taskContentIdentity(content);
      const retained = followupRetryRef.current.get(task.taskThreadId);
      const idempotencyKey =
        retained?.contentIdentity === contentIdentity
          ? retained.idempotencyKey
          : taskIdempotencyKey("side-task-followup");
      followupRetryRef.current.set(task.taskThreadId, { contentIdentity, idempotencyKey });
      try {
        let result;
        try {
          result = await port.followup({
            senderThreadId: expectedSenderThreadId,
            targetThreadId: task.taskThreadId,
            content,
            idempotencyKey,
            expectedTaskRevision: authoritative.revision,
          });
        } catch (firstError) {
          const refreshed = acceptTaskSummary(
            (await port.read({ taskThreadId: task.taskThreadId, limit: 1 })).task,
          );
          if (refreshed.revision <= authoritative.revision) throw firstError;
          result = await port.followup({
            senderThreadId: expectedSenderThreadId,
            targetThreadId: task.taskThreadId,
            content,
            idempotencyKey,
            expectedTaskRevision: refreshed.revision,
          });
        }
        // ACK 后必须读取完整首屏快照；limit=1 会留下 nextCursor，无法准入共享 reducer。
        const snapshot = await transcriptPort.read({ threadId: task.taskThreadId });
        if (snapshot.threadId !== task.taskThreadId)
          throw new Error("task transcript identity mismatch");
        if (epoch === taskEpochRef.current && !closedTaskIdsRef.current.has(task.taskThreadId)) {
          if (activeTaskRef.current === snapshot.threadId) acceptVisibleTranscript(snapshot);
          else {
            const pending = pendingTranscriptByTaskRef.current.get(snapshot.threadId);
            pendingTranscriptByTaskRef.current.set(
              snapshot.threadId,
              mergeTranscriptSnapshots(pending, snapshot),
            );
          }
        }
        const acceptedTask = acceptTaskSummary(result.task);
        if (followupRetryRef.current.get(task.taskThreadId)?.idempotencyKey === idempotencyKey)
          followupRetryRef.current.delete(task.taskThreadId);
        return { task: acceptedTask, turnId: result.turnId, threadRevision: snapshot.revision };
      } catch {
        throw new Error(taskErrorMessage("followup"));
      }
    },
    [
      acceptTaskSummary,
      acceptVisibleTranscript,
      closingTaskThreadId,
      port,
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
   * 关闭只允许独立侧聊使用，并以服务端 ACK 作为唯一清理闸门；本地 projection 在 ACK 后移除，
   * 同时使迟到的 read、seen、follow-up 和事件失效，避免失败重试或竞态重新显示已销毁会话。
   */
  const close = useCallback(
    async (task: TaskSummary): Promise<void> => {
      if (task.taskKind !== "side_task") throw new Error(taskErrorMessage("close"));
      if (closingTaskThreadRef.current !== undefined || closingTaskThreadId !== undefined)
        throw new Error(taskErrorMessage("close"));
      if (closedTaskIdsRef.current.has(task.taskThreadId)) return;
      closingTaskThreadRef.current = task.taskThreadId;
      setClosingTaskThreadId(task.taskThreadId);
      try {
        const result = await port.close({ taskThreadId: task.taskThreadId });
        if (result.closed !== true) throw new Error("side task close was not acknowledged");
        const closedTaskIds = collectTaskTreeIds(tasksRef.current, task.taskThreadId);
        for (const taskThreadId of closedTaskIds) closedTaskIdsRef.current.add(taskThreadId);
        const isActiveTask =
          activeTaskRef.current !== undefined && closedTaskIds.has(activeTaskRef.current);
        if (isActiveTask) detailEpochRef.current += 1;
        const timer = seenTimerByTaskRef.current.get(task.taskThreadId);
        if (timer !== undefined) clearTimeout(timer);
        seenTimerByTaskRef.current.delete(task.taskThreadId);
        seenTargetByTaskRef.current.delete(task.taskThreadId);
        seenChainByTaskRef.current.delete(task.taskThreadId);
        followupRetryRef.current.delete(task.taskThreadId);
        pendingTranscriptByTaskRef.current.delete(task.taskThreadId);
        if (readyTaskRef.current?.taskThreadId === task.taskThreadId) {
          readyTaskRef.current = undefined;
          transcriptRef.current = undefined;
        }
        rejectTaskReady(task.taskThreadId, new Error("side task was closed"));
        tasksRef.current = tasksRef.current.filter(
          (candidate) => !closedTaskIds.has(candidate.taskThreadId),
        );
        setTasks([...tasksRef.current]);
        if (isActiveTask) {
          observationRef.current = undefined;
          setDetail(undefined);
          setTranscript(undefined);
          setDetailLoading(false);
          setDetailError(undefined);
          setTranscriptError(undefined);
          setProgressSummary(undefined);
        }
      } catch {
        throw new Error(taskErrorMessage("close"));
      } finally {
        if (closingTaskThreadRef.current === task.taskThreadId)
          closingTaskThreadRef.current = undefined;
        setClosingTaskThreadId((current) => (current === task.taskThreadId ? undefined : current));
      }
    },
    [closingTaskThreadId, port, rejectTaskReady],
  );

  /** 取消只发生在显式按钮，Subagent 的递归传播范围由服务端 attached 规则决定。 */
  const cancel = useCallback(
    async (task: TaskSummary): Promise<TaskSummary> => {
      const authoritative = taskForMutation(task);
      try {
        const result = await port.cancel({
          taskThreadId: task.taskThreadId,
          expectedTaskRevision: authoritative.revision,
        });
        return acceptTaskSummary(result.task);
      } catch {
        throw new Error(taskErrorMessage("cancel"));
      }
    },
    [acceptTaskSummary, port, taskForMutation],
  );

  /**
   * 恢复只选择当前 Transcript 中最早的 suspended Turn；调用成功后立即权威重读，
   * 不在 UI 中把 Task 乐观改成 running，也不创建新的 follow-up Turn。
   */
  const resume = useCallback(
    async (task: TaskSummary): Promise<void> => {
      const suspendedTurn = transcript?.turns.find((turn) => turn.status === "suspended");
      if (
        task.state !== "suspended" ||
        activeTaskThreadId !== task.taskThreadId ||
        suspendedTurn === undefined ||
        transcript === undefined
      )
        throw new Error(taskErrorMessage("resume"));
      try {
        await resumePort.resume({
          turnId: suspendedTurn.turnId,
          expectedThreadRevision: transcript.revision,
        });
        await refreshDetailFor(task.taskThreadId);
      } catch {
        throw new Error(taskErrorMessage("resume"));
      }
    },
    [activeTaskThreadId, refreshDetailFor, resumePort, transcript],
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
      transcript,
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
      transcript,
      transcriptError,
    ],
  );
}
