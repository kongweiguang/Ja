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
import { subscribeTaskHostEvents } from "./taskEventBus";
import type { TaskContentBlock, TaskReadModel, TaskSummary } from "../domain/taskModel";

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
  readonly refresh: () => Promise<void>;
  readonly refreshDetail: () => Promise<void>;
  readonly createSideTask: (input: {
    taskName: string;
    content: TaskContentBlock[];
  }) => Promise<TaskSummary>;
  readonly rename: (task: TaskSummary, title: string) => Promise<TaskSummary>;
  readonly followup: (task: TaskSummary, content: TaskContentBlock[]) => Promise<TaskSummary>;
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

/** 安全错误文案不读取 native message/stack，避免绝对路径或 Provider 诊断进入 UI。 */
function taskErrorMessage(
  operation:
    | "list"
    | "detail"
    | "transcript"
    | "create"
    | "rename"
    | "followup"
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
      return "侧边任务创建失败，请保留草稿后重试。";
    case "rename":
      return "侧边任务重命名失败，请重试。";
    case "followup":
      return "消息未发送，请重试。";
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

/** ACK 与事件都按 revision 单调合并，晚响应不能覆盖更新后的服务端投影。 */
function mergeTask(current: readonly TaskSummary[], incoming: TaskSummary): TaskSummary[] {
  const index = current.findIndex((task) => task.taskThreadId === incoming.taskThreadId);
  if (index < 0) return [...current, incoming];
  if ((current[index]?.revision ?? 0) > incoming.revision) return [...current];
  const next = [...current];
  next[index] = incoming;
  return next;
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
 * Controller 常驻消费低频摘要事件，但只有详情可见时才读取 task/read、thread/read 并建立
 * observe；隐藏 Workbench 或切换 Tab 会立即释放观察句柄，不缓存其它 Child Transcript。
 */
export function useTaskController({
  rootThreadId,
  parentRevision,
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
  const detailEpochRef = useRef(0);
  const observationRef = useRef<string | undefined>(undefined);
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
  visibleRef.current = visible;
  activeTaskRef.current = activeTaskThreadId;
  rootThreadRef.current = rootThreadId;
  onSubagentDiscoveredRef.current = onSubagentDiscovered;

  /**
   * 所有 read、event 与 mutation ACK 先进入同步 revision authority，再投影 React state；这样
   * 同一事件循环内的 follow-up 也能看到最新 CAS，不受批量渲染和 Promise 完成顺序影响。
   */
  const acceptTaskSummary = useCallback((incoming: TaskSummary): TaskSummary => {
    const current = tasksRef.current.find(
      (taskSummary) => taskSummary.taskThreadId === incoming.taskThreadId,
    );
    const authoritative =
      current !== undefined && current.revision > incoming.revision ? current : incoming;
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
   * 详情正文只接受不早于当前详情的 task/read；摘要仍替换为全局 authority，避免迟到 read
   * 抹掉 seen/terminal 已提交的 revision，同时保留最后一份完整 activities/mailbox。
   */
  const acceptTaskDetail = useCallback(
    (incoming: TaskReadModel): TaskSummary => {
      const authoritative = acceptTaskSummary(incoming.task);
      setDetail((current) =>
        current?.task.taskThreadId === incoming.task.taskThreadId &&
        current.task.revision > incoming.task.revision
          ? current
          : { ...incoming, task: authoritative },
      );
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
        setTranscript(transcriptResult.value);
        setTranscriptError(undefined);
      } else setTranscriptError(taskErrorMessage("transcript"));
    },
    [acceptTaskDetail, port, scheduleTaskSeen, transcriptPort],
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
      try {
        const [detailResult, transcriptResult] = await Promise.allSettled([
          port.read({ taskThreadId: activeTaskThreadId, limit: 200 }),
          transcriptPort.read({ threadId: activeTaskThreadId, limit: 200 }),
        ]);
        if (!active || epoch !== detailEpochRef.current) return;
        if (transcriptResult.status === "fulfilled") {
          setTranscript(transcriptResult.value);
          setTranscriptError(undefined);
        } else setTranscriptError(taskErrorMessage("transcript"));
        if (detailResult.status === "rejected") {
          setDetailError(taskErrorMessage("detail"));
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
        if (active && epoch === detailEpochRef.current) setDetailError(taskErrorMessage("detail"));
      } finally {
        if (active && epoch === detailEpochRef.current) setDetailLoading(false);
      }
    };
    void setup();
    return () => {
      active = false;
      detailEpochRef.current += 1;
      if (observationRef.current === observationId) observationRef.current = undefined;
      if (observationId !== undefined)
        void port.unobserve({ observationId }).catch(() => undefined);
    };
  }, [activeTaskThreadId, acceptTaskDetail, port, scheduleTaskSeen, transcriptPort, visible]);

  /** Runtime 已完成严格 Schema 校验；这里仅按 root/observation identity 更新局部投影。 */
  useEffect(
    () =>
      subscribeTaskHostEvents((event) => {
        if (rootThreadId === undefined || event.params.rootThreadId !== rootThreadId) return;
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

  /** 首次发送才调用 create；失败保持调用方草稿，成功后返回服务端稳定实例。 */
  const createSideTask = useCallback(
    async (input: { taskName: string; content: TaskContentBlock[] }): Promise<TaskSummary> => {
      if (rootThreadId === undefined || parentRevision === undefined)
        throw new Error(taskErrorMessage("create"));
      try {
        const result = await port.create({
          parentThreadId: rootThreadId,
          parentTurnId: null,
          expectedParentRevision: parentRevision,
          taskName: input.taskName,
          content: input.content,
        });
        return acceptTaskSummary(result.task);
      } catch {
        throw new Error(taskErrorMessage("create"));
      }
    },
    [acceptTaskSummary, parentRevision, port, rootThreadId],
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
   * 已存在侧边任务发送使用 follow-up；同 Task 的相同失败内容保留幂等键以覆盖 ACK 丢失，
   * 成功后或内容变化后释放旧身份，使用户下一次明确发送仍可创建新 Turn。
   */
  const followup = useCallback(
    async (task: TaskSummary, content: TaskContentBlock[]): Promise<TaskSummary> => {
      if (rootThreadId === undefined) throw new Error(taskErrorMessage("followup"));
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
            senderThreadId: rootThreadId,
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
            senderThreadId: rootThreadId,
            targetThreadId: task.taskThreadId,
            content,
            idempotencyKey,
            expectedTaskRevision: refreshed.revision,
          });
        }
        if (followupRetryRef.current.get(task.taskThreadId)?.idempotencyKey === idempotencyKey)
          followupRetryRef.current.delete(task.taskThreadId);
        return acceptTaskSummary(result.task);
      } catch {
        throw new Error(taskErrorMessage("followup"));
      }
    },
    [acceptTaskSummary, port, rootThreadId, taskForMutation],
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
      refresh,
      refreshDetail,
      createSideTask,
      rename,
      followup,
      cancel,
      resume,
      approvalRespond,
    }),
    [
      approvalRespond,
      cancel,
      createSideTask,
      detail,
      detailError,
      detailLoading,
      error,
      followup,
      loading,
      progressSummary,
      refresh,
      refreshDetail,
      rename,
      resume,
      tasks,
      transcript,
      transcriptError,
    ],
  );
}
