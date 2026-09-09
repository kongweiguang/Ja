// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import type { WorkspaceProjection } from "@/features/workspace";
import { useTimelineStore } from "./timelineStore";
import type {
  ConversationCompactionResult,
  ConversationHistoryPort,
  ConversationRuntimeState,
  ConversationThread,
  ConversationModelSelection,
  ConversationAccessMode,
  ConversationCollaborationMode,
  ReasoningLevel,
} from "./ports";
import type { TimelineEvent, TimelineSnapshot } from "../domain/timelineContracts";

const MAX_RECENT_THREADS = 100;

interface ConversationControllerOptions {
  history: ConversationHistoryPort;
  workspace: WorkspaceProjection | undefined;
  workspaceRevision: number;
  modelSelection: ConversationModelSelection | undefined;
  accessMode: "approval_required" | "full_access";
  runtimeState: ConversationRuntimeState | undefined;
  metadataEvent?: TimelineEvent;
  activateWorkspace(workspaceId: string): Promise<WorkspaceProjection | undefined>;
}

/** Conversation controller 只公开 Thread 目录、当前选择和会话用例。 */
export interface ConversationController {
  threads: ConversationThread[];
  currentThreadId: string | undefined;
  busy: boolean;
  error: string | undefined;
  mutatingThreadIds: readonly string[];
  compaction: ConversationCompactionView;
  canCompact: boolean;
  create(): Promise<void>;
  select(threadId: string): Promise<void>;
  search(query: string): Promise<ConversationThread[]>;
  rename(threadId: string, title: string): Promise<void>;
  pin(threadId: string, pinned: boolean): Promise<void>;
  archive(threadId: string): Promise<ConversationArchiveUndo | undefined>;
  restore(threadId: string, open: boolean): Promise<void>;
  updatePreferences(input: {
    providerId: string;
    modelId: string;
    reasoningLevel: ReasoningLevel | null;
    accessMode: ConversationAccessMode;
    collaborationMode: ConversationCollaborationMode;
  }): Promise<void>;
  compact(): Promise<void>;
  dismissCompactionFeedback(): void;
}

/** Undo 保存服务端归档后的 revision 与原选择事实，不缓存旧列表快照冒充恢复结果。 */
export interface ConversationArchiveUndo {
  archived: ConversationThread;
  restoreSelection: boolean;
}

/** 手动和自动生命周期共用同一只读反馈，避免菜单维护第二套压缩状态机。 */
export interface ConversationCompactionView {
  phase: "idle" | "running" | "success" | "error";
  message?: string;
  retryable: boolean;
}

const COMPACTION_ERRORS: Readonly<Record<string, { message: string; retryable: boolean }>> = {
  THREAD_NOT_FOUND: { message: "对话不存在或已删除。", retryable: false },
  CONFLICT: { message: "对话状态已更新，请重试压缩。", retryable: true },
  THREAD_BUSY: { message: "对话正在执行，结束后可再次压缩。", retryable: true },
  SUMMARY_FAILURE: { message: "上下文摘要生成失败，请重试。", retryable: true },
  CONTEXT_LIMIT: { message: "当前上下文无法安全压缩到模型窗口内。", retryable: false },
  CANCELLED: { message: "上下文压缩已取消。", retryable: true },
  INVALID_STATE: { message: "对话上下文状态异常，请重新打开会话。", retryable: false },
};

/** 将原生错误或 failed event 收敛为稳定用户反馈，不把内部 message、路径或 Provider 正文带入状态。 */
function compactionError(error: unknown): { message: string; retryable: boolean } {
  const candidate =
    error !== null && typeof error === "object" ? (error as Record<string, unknown>) : undefined;
  const code = typeof candidate?.["code"] === "string" ? candidate["code"] : undefined;
  return (
    (code === undefined ? undefined : COMPACTION_ERRORS[code]) ?? {
      message: "上下文压缩暂时失败，请重试。",
      retryable: true,
    }
  );
}

/** 只读取稳定公开错误码，任何其它原生字段都不进入会话状态。 */
function compactionErrorCode(error: unknown): string | undefined {
  if (error === null || typeof error !== "object") return undefined;
  const code = (error as Record<string, unknown>)["code"];
  return typeof code === "string" ? code : undefined;
}

/** 只格式化 Provider 官方计量结果，缺失值绝不由字符数估算。 */
function compactedMessage(
  result: Pick<ConversationCompactionResult, "inputTokensBefore" | "inputTokensAfter">,
): string {
  return `上下文已压缩：${result.inputTokensBefore.toLocaleString("zh-CN")} → ${result.inputTokensAfter.toLocaleString("zh-CN")} Token。`;
}

/**
 * 最近对话严格属于当前 workspace；侧栏没有展示每行的 scope，保留其它 workspace 的同名
 * Thread 会让用户误点后发生隐式项目切换，因此切换范围时必须整体替换目录投影。
 */
function workspaceThreads(
  workspaceId: string,
  scoped: readonly ConversationThread[],
): ConversationThread[] {
  const seen = new Set<string>();
  const merged: ConversationThread[] = [];
  for (const thread of scoped) {
    if (
      thread.workspaceId !== workspaceId ||
      thread.status === "archived" ||
      seen.has(thread.threadId)
    )
      continue;
    seen.add(thread.threadId);
    merged.push(thread);
    if (merged.length === MAX_RECENT_THREADS) break;
  }
  return merged;
}

/**
 * 权威列表只覆盖请求发出时已存在的目录事实；若 Thread 在该请求之后已由 create ACK
 * 持久化，则保留它直到一个不早于创建 epoch 的列表确认，避免迟到刷新删除当前会话。
 */
function mergeThreadListResponse(
  current: ConversationThread[],
  workspaceId: string,
  scoped: ConversationThread[],
  issuedEpoch: number,
  localCreationEpochs: ReadonlyMap<string, { workspaceId: string; epoch: number }>,
): ConversationThread[] {
  const authoritativeIds = new Set(scoped.map((thread) => thread.threadId));
  const createdAfterRequest = current.filter((thread) => {
    const creation = localCreationEpochs.get(thread.threadId);
    return (
      thread.workspaceId === workspaceId &&
      thread.status === "active" &&
      !authoritativeIds.has(thread.threadId) &&
      creation?.workspaceId === workspaceId &&
      creation.epoch > issuedEpoch
    );
  });
  return workspaceThreads(workspaceId, [...createdAfterRequest, ...scoped]);
}

/**
 * 搜索命中只补充目录中尚未出现的 Thread，不改变最近会话既有排序；这样 Command Dialog
 * 可以打开较旧结果，同时一次查询不会让侧栏历史突然重排。
 */
function mergeSearchThreads(
  current: ConversationThread[],
  workspaceId: string,
  matches: readonly ConversationThread[],
): ConversationThread[] {
  const byId = new Map(matches.map((thread) => [thread.threadId, thread]));
  const merged = current.map((thread) => {
    const match = byId.get(thread.threadId);
    return match !== undefined && match.revision >= thread.revision ? match : thread;
  });
  const existing = new Set(merged.map((thread) => thread.threadId));
  for (const thread of matches) {
    if (
      thread.workspaceId !== workspaceId ||
      thread.status !== "active" ||
      existing.has(thread.threadId)
    )
      continue;
    merged.push(thread);
    existing.add(thread.threadId);
  }
  return merged.slice(0, MAX_RECENT_THREADS);
}

/**
 * 同一 runtime generation 且无 resync 标记时复用 live timeline；snapshot 不包含私有
 * approval 请求，盲目重读会丢失仍可操作的审批卡片。
 */
function canReuseLoadedThread(threadId: string, workspaceId: string): boolean {
  const state = useTimelineStore.getState();
  const runtime = state.runtime;
  return (
    state.handshake.phase === "ready" &&
    state.handshake.generation > 0 &&
    state.serverInstanceId !== undefined &&
    runtime !== undefined &&
    (runtime.status === "ready" || runtime.status === "busy") &&
    state.threads[threadId]?.workspaceId === workspaceId &&
    state.resyncRequired[threadId] === undefined
  );
}

/**
 * “新对话”以权威 Timeline 是否已有 Turn 或持久条目判定，而不依赖可修改的标题；resync
 * 状态不视为空，避免用不完整的本地投影阻止用户离开异常会话。
 */
function isEmptyLoadedThread(threadId: string): boolean {
  const state = useTimelineStore.getState();
  return (
    state.threads[threadId] !== undefined &&
    state.resyncRequired[threadId] === undefined &&
    !Object.values(state.turns).some((turn) => turn.threadId === threadId) &&
    (state.itemIdsByThread[threadId]?.length ?? 0) === 0
  );
}

/**
 * 独占 Thread catalog、当前选择和 timeline snapshot 恢复；workspace/v3 默认模型仅作为输入，
 * 跨项目选择通过窄 activateWorkspace port 请求，不复制 native capability 状态。
 */
export function useConversationController({
  history,
  workspace,
  workspaceRevision,
  modelSelection,
  accessMode,
  runtimeState,
  metadataEvent,
  activateWorkspace,
}: ConversationControllerOptions): ConversationController {
  const [threads, setThreads] = useState<ConversationThread[]>([]);
  const [currentThreadId, setCurrentThreadId] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [mutatingThreadIds, setMutatingThreadIds] = useState<readonly string[]>([]);
  const [compaction, setCompaction] = useState<ConversationCompactionView>({
    phase: "idle",
    retryable: false,
  });
  const mountedRef = useRef(false);
  const requestRef = useRef(0);
  const compactionRequestRef = useRef(0);
  const compactionInFlightRef = useRef(false);
  const creationInFlightRef = useRef<Promise<void> | undefined>(undefined);
  const directoryMutationEpochRef = useRef(0);
  const localCreationEpochsRef = useRef(new Map<string, { workspaceId: string; epoch: number }>());
  const threadMutationGuardsRef = useRef(new Set<string>());
  const workspaceRef = useRef(workspace);
  const historyWorkspaceIdRef = useRef(workspace?.workspaceId);
  const modelSelectionRef = useRef(modelSelection);
  const accessModeRef = useRef(accessMode);
  const runtimeStateRef = useRef(runtimeState);
  const automaticResyncAttemptRef = useRef<string | undefined>(undefined);
  const manualWorkspaceTargetRef = useRef<string | undefined>(undefined);
  const currentThreadIdRef = useRef(currentThreadId);
  const threadsRef = useRef(threads);
  const latestTurnProjectionRef = useRef(
    new Map<string, { turnId: string; status: ConversationThread["latestTurnStatus"] }>(),
  );
  const seenAttemptKeysRef = useRef(new Set<string>());
  const historyRuntimeAdmission =
    runtimeState !== undefined && ["ready", "busy"].includes(runtimeState.status)
      ? `${runtimeState.generation}:${runtimeState.serverInstanceId ?? ""}`
      : undefined;
  const modelSelectionAvailable = modelSelection !== undefined;
  workspaceRef.current = workspace;
  modelSelectionRef.current = modelSelection;
  accessModeRef.current = accessMode;
  runtimeStateRef.current = runtimeState;
  currentThreadIdRef.current = currentThreadId;
  threadsRef.current = threads;
  const currentResyncReason = useTimelineStore((state) =>
    currentThreadId === undefined ? undefined : state.resyncRequired[currentThreadId],
  );
  const currentThreadRevision = useTimelineStore((state) =>
    currentThreadId === undefined ? undefined : state.threadRevisionByThread[currentThreadId],
  );
  const activeTurnPresent = useTimelineStore((state) =>
    currentThreadId === undefined
      ? false
      : Object.values(state.turns).some(
          (turn) =>
            turn.threadId === currentThreadId &&
            !["completed", "failed", "cancelled"].includes(turn.status),
        ),
  );
  const observedCompaction = useTimelineStore((state) =>
    currentThreadId === undefined ? undefined : state.contextCompactionByThread[currentThreadId],
  );
  const timelineStatusSignature = useTimelineStore((state) =>
    Object.values(state.threads)
      .map((thread) => {
        const turnId = thread.latestTurnId;
        return `${thread.threadId}:${turnId ?? "none"}:${turnId === undefined ? "none" : (state.turns[turnId]?.status ?? "none")}`;
      })
      .sort()
      .join("|"),
  );
  const currentLatestTerminalKey = useTimelineStore((state) => {
    if (currentThreadId === undefined) return undefined;
    const latestTurnId = state.threads[currentThreadId]?.latestTurnId;
    if (latestTurnId === undefined) return undefined;
    const status = state.turns[latestTurnId]?.status;
    return status === "completed" || status === "failed" ? `${latestTurnId}:${status}` : undefined;
  });

  /**
   * 判断异步历史结果是否仍属于当前 workspace 和最新 request；workspace 切换、卸载或
   * 后续用户选择都会让旧 continuation 失效。
   */
  const isCurrentRequest = useCallback(
    (request: number, workspaceId: string): boolean =>
      mountedRef.current &&
      requestRef.current === request &&
      workspaceRef.current?.workspaceId === workspaceId,
    [],
  );

  /**
   * snapshot 只有在当前 ready generation 下才进入 timeline reducer；同时校验 threadId，
   * 防止 native adapter 返回错误实体导致跨 Thread 投影污染。
   */
  const applySnapshot = useCallback(
    (snapshot: TimelineSnapshot, workspaceId: string, expectedThreadId: string): boolean => {
      if (!mountedRef.current) return false;
      const state = useTimelineStore.getState();
      const runtime = state.runtime;
      if (
        state.handshake.phase !== "ready" ||
        state.handshake.generation <= 0 ||
        state.serverInstanceId === undefined ||
        runtime === undefined ||
        !["ready", "busy"].includes(runtime.status)
      )
        return false;
      if (snapshot.threadId !== expectedThreadId) return false;
      const outcome = useTimelineStore.getState().applySnapshot(snapshot, workspaceId);
      // 并发 thread/read 若晚于更新的 committed event 返回，Reducer 会保留新投影；这仍是成功收敛。
      return outcome === "applied" || outcome === "late";
    },
    [],
  );

  /**
   * 创建 durable Thread 后立即读取 authoritative snapshot；只有 snapshot 通过 generation
   * gate 才向 UI 暴露 threadId，避免出现目录有行但 timeline 不可用的半状态。
   */
  const createAndRestore = useCallback(
    async (
      selected: WorkspaceProjection,
      selection: ConversationModelSelection | undefined,
      request: number,
    ): Promise<ConversationThread | undefined> => {
      if (selection === undefined) return undefined;
      const created = await history.threadCreate({
        ...(selected.kind === "project" ? { cwd: selected.rootPath } : {}),
        title: "新对话",
        providerId: selection.providerId,
        modelId: selection.modelId,
        reasoningLevel: selection.reasoningLevel,
        accessMode: accessModeRef.current,
        collaborationMode: "default",
      });
      if (!isCurrentRequest(request, selected.workspaceId)) return undefined;
      if (created.workspaceId !== selected.workspaceId)
        throw new Error("created thread belongs to another workspace");
      if (
        created.preferences === null ||
        created.preferences.providerId !== selection.providerId ||
        created.preferences.modelId !== selection.modelId
      )
        throw new Error("created thread belongs to another model selection");
      const snapshot = await history.threadRead({ threadId: created.threadId });
      if (!isCurrentRequest(request, selected.workspaceId)) return undefined;
      if (!applySnapshot(snapshot, selected.workspaceId, created.threadId)) {
        throw new Error("created thread snapshot was not applied");
      }
      return created;
    },
    [applySnapshot, history, isCurrentRequest],
  );

  /**
   * 恢复 workspace 的服务端排序历史；list/read/create 共用一个 request token，快速切换
   * 不会让旧 promise 覆盖新 workspace 的 sidebar 或 timeline。
   */
  const loadWorkspaceHistory = useCallback(
    async (
      selected: WorkspaceProjection,
      selection: ConversationModelSelection | undefined,
    ): Promise<void> => {
      const request = requestRef.current + 1;
      requestRef.current = request;
      setBusy(true);
      setError(undefined);
      try {
        const listed = await history.threadList({ workspaceId: selected.workspaceId, limit: 200 });
        if (!isCurrentRequest(request, selected.workspaceId)) return;
        if (listed.items.some((thread) => thread.workspaceId !== selected.workspaceId)) {
          setError("历史会话数据无法恢复，请重新选择项目。 ");
          return;
        }
        const currentThread = currentThreadIdRef.current;
        const first =
          listed.items.find((thread) => thread.threadId === currentThread) ?? listed.items[0];
        if (first !== undefined) {
          const snapshot = await history.threadRead({ threadId: first.threadId });
          if (!isCurrentRequest(request, selected.workspaceId)) return;
          if (!applySnapshot(snapshot, selected.workspaceId, first.threadId)) {
            setError("最近的会话无法恢复，请重新选择项目。 ");
            return;
          }
          setThreads(workspaceThreads(selected.workspaceId, listed.items));
          currentThreadIdRef.current = first.threadId;
          setCurrentThreadId(first.threadId);
          return;
        }
        const created = await createAndRestore(selected, selection, request);
        if (!isCurrentRequest(request, selected.workspaceId) || created === undefined) return;
        setThreads(workspaceThreads(selected.workspaceId, [created]));
        currentThreadIdRef.current = created.threadId;
        setCurrentThreadId(created.threadId);
      } catch {
        if (isCurrentRequest(request, selected.workspaceId))
          setError("历史会话暂时不可用，请重试。 ");
      } finally {
        if (isCurrentRequest(request, selected.workspaceId)) setBusy(false);
      }
    },
    [applySnapshot, createAndRestore, history, isCurrentRequest],
  );

  /**
   * 为“新对话”明确冻结当前 Provider/Model 默认选择；仅新建动作使用该选择，已有 Thread
   * 的模型变化必须走 preferences/update，避免把普通切换错误实现成另一个会话。
   */
  const createWithSelection = useCallback(
    async (selection: ConversationModelSelection): Promise<void> => {
      const selected = workspaceRef.current;
      if (selected === undefined) return;
      const request = requestRef.current + 1;
      requestRef.current = request;
      setBusy(true);
      setError(undefined);
      try {
        const created = await createAndRestore(selected, selection, request);
        if (!isCurrentRequest(request, selected.workspaceId) || created === undefined) return;
        const creationEpoch = directoryMutationEpochRef.current + 1;
        directoryMutationEpochRef.current = creationEpoch;
        localCreationEpochsRef.current.set(created.threadId, {
          workspaceId: selected.workspaceId,
          epoch: creationEpoch,
        });
        setThreads((current) =>
          workspaceThreads(selected.workspaceId, [
            created,
            ...current.filter(
              (thread) =>
                thread.workspaceId === selected.workspaceId && thread.threadId !== created.threadId,
            ),
          ]),
        );
        currentThreadIdRef.current = created.threadId;
        setCurrentThreadId(created.threadId);
      } catch {
        if (isCurrentRequest(request, selected.workspaceId))
          setError("新会话暂时无法创建，请重试。 ");
      } finally {
        if (isCurrentRequest(request, selected.workspaceId)) setBusy(false);
      }
    },
    [createAndRestore, isCurrentRequest],
  );

  /**
   * 普通“新对话”在点击时读取最新 v3 默认选择；当前 Thread 仍为空时复用它，并用 single-flight
   * 收口同一事件循环内的连续触发，避免侧栏、快捷键或命令面板并发落下多个空 Thread。
   */
  const create = useCallback(async (): Promise<void> => {
    const currentThreadId = currentThreadIdRef.current;
    if (currentThreadId !== undefined && isEmptyLoadedThread(currentThreadId)) return;
    const pending = creationInFlightRef.current;
    if (pending !== undefined) {
      await pending;
      return;
    }
    const selection = modelSelectionRef.current;
    if (selection === undefined) return;
    const operation = createWithSelection(selection);
    creationInFlightRef.current = operation;
    try {
      await operation;
    } finally {
      if (creationInFlightRef.current === operation) creationInFlightRef.current = undefined;
    }
  }, [createWithSelection]);

  /**
   * 选择指定 Thread；当前 generation 内的已加载 timeline 直接切换，只有缓存失效或
   * 跨 workspace 恢复才进入读取态。这既避免重复点击产生虚假刷新，也保留 request fence，
   * 使缓存切换可以取消旧的异步读取投影。
   */
  const select = useCallback(
    async (threadId: string): Promise<void> => {
      const target = threadsRef.current.find((thread) => thread.threadId === threadId);
      let selected = workspaceRef.current;
      if (target === undefined || selected === undefined) return;
      const workspaceChanged = target.workspaceId !== selected.workspaceId;
      if (workspaceChanged) {
        manualWorkspaceTargetRef.current = target.workspaceId;
        selected = await activateWorkspace(target.workspaceId);
        if (selected === undefined) {
          manualWorkspaceTargetRef.current = undefined;
          setError("会话所属项目已不可用。 ");
          return;
        }
      }
      const request = requestRef.current + 1;
      requestRef.current = request;
      setError(undefined);
      const requiresSnapshot = !canReuseLoadedThread(threadId, selected.workspaceId);
      if (!requiresSnapshot) {
        setBusy(false);
        if (currentThreadIdRef.current !== threadId) {
          setThreads((current) =>
            workspaceThreads(
              selected.workspaceId,
              current.filter((thread) => thread.workspaceId === selected.workspaceId),
            ),
          );
          setCurrentThreadId(threadId);
        }
        manualWorkspaceTargetRef.current = undefined;
        return;
      }
      setBusy(true);
      try {
        const snapshot = await history.threadRead({ threadId });
        if (!isCurrentRequest(request, selected.workspaceId)) return;
        if (!applySnapshot(snapshot, selected.workspaceId, threadId)) {
          setError("会话无法恢复，请重新选择项目。 ");
          return;
        }
        if (!isCurrentRequest(request, selected.workspaceId)) return;
        setThreads((current) =>
          workspaceThreads(
            selected.workspaceId,
            current.filter((thread) => thread.workspaceId === selected.workspaceId),
          ),
        );
        setCurrentThreadId(threadId);
      } catch {
        if (isCurrentRequest(request, selected.workspaceId))
          setError("会话暂时无法读取，请重试。 ");
      } finally {
        manualWorkspaceTargetRef.current = undefined;
        if (isCurrentRequest(request, selected.workspaceId)) setBusy(false);
      }
    },
    [activateWorkspace, applySnapshot, history, isCurrentRequest],
  );

  /**
   * 标题搜索始终绑定调用时的当前 Workspace；晚到结果若已跨 Workspace 便直接丢弃，命中项只
   * 补入目录供后续 select 使用，不把查询结果误当最近排序。
   */
  const search = useCallback(
    async (query: string): Promise<ConversationThread[]> => {
      const selected = workspaceRef.current;
      if (selected === undefined) return [];
      const result = await history.threadSearch({
        workspaceId: selected.workspaceId,
        query: query.trim(),
        limit: 100,
      });
      if (workspaceRef.current?.workspaceId !== selected.workspaceId) return [];
      if (result.items.some((thread) => thread.workspaceId !== selected.workspaceId)) {
        throw new Error("thread search crossed workspace boundary");
      }
      setThreads((current) => {
        const next = mergeSearchThreads(current, selected.workspaceId, result.items);
        threadsRef.current = next;
        return next;
      });
      return result.items;
    },
    [history],
  );

  /**
   * 取 Timeline 与目录投影中的最大 CAS revision。偏好/重命名 RPC 会先返回新目录 revision，
   * Timeline 则可能仍停在上一个事件；只固定优先 Timeline 会让紧接着的第二次设置永久冲突。
   */
  const threadRevision = useCallback((threadId: string): number | undefined => {
    const timelineRevision = useTimelineStore.getState().threadRevisionByThread[threadId];
    const directoryRevision = threadsRef.current.find(
      (thread) => thread.threadId === threadId,
    )?.revision;
    if (timelineRevision === undefined) return directoryRevision;
    if (directoryRevision === undefined) return timelineRevision;
    return Math.max(timelineRevision, directoryRevision);
  }, []);

  /** 行级 pending 只锁定目标会话；Set 保证同一行多类动作不会互相覆盖释放。 */
  const setThreadMutationPending = useCallback((threadId: string, pending: boolean): void => {
    setMutatingThreadIds((current) => {
      const next = new Set(current);
      if (pending) next.add(threadId);
      else next.delete(threadId);
      return [...next];
    });
  }, []);

  /** CONFLICT 后只重读一次权威 revision；调用者随后最多重放一次同一显式意图。 */
  const rereadThreadRevision = useCallback(
    async (threadId: string): Promise<number> => {
      const snapshot = await history.threadRead({ threadId, limit: 1 });
      const selected = workspaceRef.current;
      if (selected !== undefined && currentThreadIdRef.current === threadId) {
        applySnapshot(snapshot, selected.workspaceId, threadId);
      }
      return snapshot.revision;
    },
    [applySnapshot, history],
  );

  /** 目录类 CAS 只允许一次权威重读和有界重试，持续竞争会原样失败给 UI。 */
  const retryThreadMutation = useCallback(
    async <T>(
      threadId: string,
      revision: number,
      invoke: (expectedThreadRevision: number) => Promise<T>,
    ): Promise<T> => {
      try {
        return await invoke(revision);
      } catch (failure) {
        if (compactionErrorCode(failure) !== "CONFLICT") throw failure;
        return invoke(await rereadThreadRevision(threadId));
      }
    },
    [rereadThreadRevision],
  );

  /**
   * 终态内容完成一次 React commit 后才提交已读 CAS；失败不清除目录提醒，CONFLICT 只允许
   * 一次权威重读和重放，避免后台确认与 Pin/Archive 并发时形成无限请求环。
   */
  const markLatestTurnSeen = useCallback(
    async (threadId: string): Promise<boolean> => {
      const revision = threadRevision(threadId);
      const guard = `seen:${threadId}`;
      if (revision === undefined || threadMutationGuardsRef.current.has(guard)) return false;
      threadMutationGuardsRef.current.add(guard);
      try {
        const seen = await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadSeen({ threadId, expectedThreadRevision }),
        );
        if (mountedRef.current) {
          setThreads((current) => {
            const next = current.map((thread) => (thread.threadId === threadId ? seen : thread));
            threadsRef.current = next;
            return next;
          });
        }
        return true;
      } catch {
        if (mountedRef.current && currentThreadIdRef.current === threadId) {
          setError("未读状态暂时无法同步，请重新打开会话重试。 ");
        }
        return false;
      } finally {
        threadMutationGuardsRef.current.delete(guard);
      }
    },
    [history, retryThreadMutation, threadRevision],
  );

  /** 重新读取当前 Workspace 的 active 列表，确保 Pin 排序完全服从服务端 pinned_at 规则。 */
  const refreshActiveThreads = useCallback(async (): Promise<void> => {
    const selected = workspaceRef.current;
    if (selected === undefined) return;
    const issuedEpoch = directoryMutationEpochRef.current;
    const listed = await history.threadList({ workspaceId: selected.workspaceId, limit: 200 });
    if (!mountedRef.current || workspaceRef.current?.workspaceId !== selected.workspaceId) return;
    setThreads((current) => {
      const next = mergeThreadListResponse(
        current,
        selected.workspaceId,
        listed.items,
        issuedEpoch,
        localCreationEpochsRef.current,
      );
      threadsRef.current = next;
      return next;
    });
    for (const [threadId, creation] of localCreationEpochsRef.current) {
      if (creation.workspaceId === selected.workspaceId && creation.epoch <= issuedEpoch)
        localCreationEpochsRef.current.delete(threadId);
    }
  }, [history]);

  /** Pin 成功后才重排；失败时保留原行投影，不做乐观反转。 */
  const pin = useCallback(
    async (threadId: string, pinned: boolean): Promise<void> => {
      const revision = threadRevision(threadId);
      const guard = `pin:${threadId}`;
      if (revision === undefined || threadMutationGuardsRef.current.has(guard)) return;
      threadMutationGuardsRef.current.add(guard);
      setThreadMutationPending(threadId, true);
      try {
        await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadPin({ threadId, pinned, expectedThreadRevision }),
        );
        await refreshActiveThreads();
      } finally {
        threadMutationGuardsRef.current.delete(guard);
        setThreadMutationPending(threadId, false);
      }
    },
    [history, refreshActiveThreads, retryThreadMutation, setThreadMutationPending, threadRevision],
  );

  /** 归档成功后才移除行；当前项改选原数组中的相邻项，无相邻项时回到既有空会话界面。 */
  const archive = useCallback(
    async (threadId: string): Promise<ConversationArchiveUndo | undefined> => {
      const current = threadsRef.current;
      const index = current.findIndex((thread) => thread.threadId === threadId);
      const revision = threadRevision(threadId);
      const guard = `archive:${threadId}`;
      if (index < 0 || revision === undefined || threadMutationGuardsRef.current.has(guard)) return;
      threadMutationGuardsRef.current.add(guard);
      setThreadMutationPending(threadId, true);
      try {
        const archived = await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadArchive({ threadId, expectedThreadRevision }),
        );
        if (!mountedRef.current) return undefined;
        const restoreSelection = currentThreadIdRef.current === threadId;
        const remaining = threadsRef.current.filter((thread) => thread.threadId !== threadId);
        threadsRef.current = remaining;
        setThreads(remaining);
        if (restoreSelection) {
          const neighbor = remaining[Math.min(index, remaining.length - 1)];
          setCurrentThreadId(neighbor?.threadId);
          if (neighbor !== undefined) void select(neighbor.threadId);
          else useTimelineStore.getState().reset();
        }
        return { archived, restoreSelection };
      } finally {
        threadMutationGuardsRef.current.delete(guard);
        setThreadMutationPending(threadId, false);
      }
    },
    [history, retryThreadMutation, select, setThreadMutationPending, threadRevision],
  );

  /** Restore 先权威读取归档 revision；成功后刷新 active 列表，并按调用意图恢复选择。 */
  const restore = useCallback(
    async (threadId: string, open: boolean): Promise<void> => {
      const guard = `restore:${threadId}`;
      if (threadMutationGuardsRef.current.has(guard)) return;
      threadMutationGuardsRef.current.add(guard);
      setThreadMutationPending(threadId, true);
      try {
        const revision = await rereadThreadRevision(threadId);
        const restored = await retryThreadMutation(threadId, revision, (expectedThreadRevision) =>
          history.threadRestore({ threadId, expectedThreadRevision }),
        );
        if (!mountedRef.current) return;
        await refreshActiveThreads();
        if (open) {
          setThreads((current) => {
            const next = current.some((thread) => thread.threadId === threadId)
              ? current.map((thread) => (thread.threadId === threadId ? restored : thread))
              : [restored, ...current];
            threadsRef.current = next;
            return next;
          });
          await select(threadId);
        }
      } finally {
        threadMutationGuardsRef.current.delete(guard);
        setThreadMutationPending(threadId, false);
      }
    },
    [
      history,
      refreshActiveThreads,
      rereadThreadRevision,
      retryThreadMutation,
      select,
      setThreadMutationPending,
    ],
  );

  /**
   * 人工标题以 Thread identity 做 single-flight，并只接受服务端 CAS 后返回的完整投影；
   * 这保证 manual title 永远不会由 renderer 的乐观状态冒充成功。
   */
  const rename = useCallback(
    async (threadId: string, title: string): Promise<void> => {
      const normalized = title.trim();
      const revision = threadRevision(threadId);
      const guard = `rename:${threadId}`;
      if (normalized === "" || revision === undefined || threadMutationGuardsRef.current.has(guard))
        return;
      threadMutationGuardsRef.current.add(guard);
      try {
        const renamed = await history.threadRename({
          threadId,
          title: normalized,
          expectedThreadRevision: revision,
        });
        if (!mountedRef.current) return;
        setThreads((current) => {
          const next = current.map((thread) => (thread.threadId === threadId ? renamed : thread));
          threadsRef.current = next;
          return next;
        });
      } finally {
        threadMutationGuardsRef.current.delete(guard);
      }
    },
    [history, threadRevision],
  );

  /**
   * Provider、Model、Reasoning 与 Access 作为完整偏好一次 CAS 替换；若事件投影暂时落后，
   * 只在首次 CONFLICT 后读取一次权威 snapshot 并以新 revision 重放。单次重放既能收敛
   * 取消/终态后的事件窗口，也不会掩盖持续并发写入或形成无限重试。
   */
  const updatePreferences = useCallback(
    async (input: {
      providerId: string;
      modelId: string;
      reasoningLevel: ReasoningLevel | null;
      accessMode: ConversationAccessMode;
      collaborationMode: ConversationCollaborationMode;
    }): Promise<void> => {
      const threadId = currentThreadIdRef.current;
      const revision = threadId === undefined ? undefined : threadRevision(threadId);
      const guard = threadId === undefined ? "" : `preferences:${threadId}`;
      if (
        threadId === undefined ||
        revision === undefined ||
        threadMutationGuardsRef.current.has(guard)
      )
        return;
      threadMutationGuardsRef.current.add(guard);
      try {
        let updated: ConversationThread;
        try {
          updated = await history.threadPreferencesUpdate({
            threadId,
            ...input,
            expectedThreadRevision: revision,
          });
        } catch (failure) {
          if (compactionErrorCode(failure) !== "CONFLICT") throw failure;
          const selected = workspaceRef.current;
          if (selected === undefined || currentThreadIdRef.current !== threadId) throw failure;
          const snapshot = await history.threadRead({ threadId });
          if (
            !mountedRef.current ||
            currentThreadIdRef.current !== threadId ||
            !applySnapshot(snapshot, selected.workspaceId, threadId)
          )
            throw failure;
          updated = await history.threadPreferencesUpdate({
            threadId,
            ...input,
            expectedThreadRevision: snapshot.revision,
          });
        }
        if (!mountedRef.current || currentThreadIdRef.current !== threadId) return;
        setThreads((current) => {
          const next = current.map((thread) => (thread.threadId === threadId ? updated : thread));
          threadsRef.current = next;
          return next;
        });
      } finally {
        threadMutationGuardsRef.current.delete(guard);
      }
    },
    [applySnapshot, history, threadRevision],
  );

  /**
   * 以当前权威 Timeline revision 发起单次 CAS 压缩；响应只用于反馈，Checkpoint 与 revision
   * 真相仍由 Java/Rust event 投影更新，重复点击通过独立 request fence 合并。
   */
  const compact = useCallback(async (): Promise<void> => {
    const threadId = currentThreadIdRef.current;
    if (
      threadId === undefined ||
      activeTurnPresent ||
      compaction.phase === "running" ||
      compactionInFlightRef.current
    )
      return;
    const revision =
      useTimelineStore.getState().threadRevisionByThread[threadId] ??
      threads.find((thread) => thread.threadId === threadId)?.revision;
    if (revision === undefined) {
      setCompaction({
        phase: "error",
        message: "对话状态尚未就绪，请重新打开会话。",
        retryable: false,
      });
      return;
    }
    const request = compactionRequestRef.current + 1;
    compactionRequestRef.current = request;
    compactionInFlightRef.current = true;
    setCompaction({ phase: "running", message: "正在压缩上下文…", retryable: false });
    try {
      const result = await history.threadCompact({
        threadId,
        expectedThreadRevision: revision,
      });
      if (
        !mountedRef.current ||
        compactionRequestRef.current !== request ||
        currentThreadIdRef.current !== threadId
      )
        return;
      setCompaction(
        result.outcome === "unchanged"
          ? { phase: "success", message: "上下文已是最新，无需再次压缩。", retryable: false }
          : { phase: "success", message: compactedMessage(result), retryable: false },
      );
    } catch (failure) {
      if (
        !mountedRef.current ||
        compactionRequestRef.current !== request ||
        currentThreadIdRef.current !== threadId
      )
        return;
      if (compactionErrorCode(failure) === "CONFLICT") {
        const selected = workspaceRef.current;
        if (selected !== undefined) {
          try {
            const snapshot = await history.threadRead({ threadId });
            if (
              mountedRef.current &&
              compactionRequestRef.current === request &&
              currentThreadIdRef.current === threadId
            )
              applySnapshot(snapshot, selected.workspaceId, threadId);
          } catch {
            // 重读失败不覆盖原始稳定冲突语义；下一次显式选择仍可恢复 authoritative snapshot。
          }
        }
      }
      const feedback = compactionError(failure);
      setCompaction({ phase: "error", ...feedback });
    } finally {
      if (compactionRequestRef.current === request) compactionInFlightRef.current = false;
    }
  }, [activeTurnPresent, applySnapshot, compaction.phase, history, threads]);

  /** 用户关闭的仅是 Renderer 反馈；不会取消或改写服务端压缩生命周期。 */
  const dismissCompactionFeedback = useCallback((): void => {
    if (compaction.phase !== "running") setCompaction({ phase: "idle", retryable: false });
  }, [compaction.phase]);

  /** 挂载 fence 统一阻止晚到 history promise 写入已卸载 controller。 */
  useEffect(() => {
    const threadMutationGuards = threadMutationGuardsRef.current;
    const latestTurnProjections = latestTurnProjectionRef.current;
    const seenAttemptKeys = seenAttemptKeysRef.current;
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestRef.current += 1;
      compactionRequestRef.current += 1;
      compactionInFlightRef.current = false;
      threadMutationGuards.clear();
      latestTurnProjections.clear();
      seenAttemptKeys.clear();
    };
  }, []);

  /**
   * 自动/人工标题事件只有同时匹配 runtime generation、server instance、当前 Workspace 且
   * revision 单调前进时才更新目录；缺失目录项或缺失 v3 偏好时回读权威列表，不凭事件补造实体。
   */
  useEffect(() => {
    if (metadataEvent?.method !== "thread/metadata-changed") return undefined;
    const params = metadataEvent.params;
    const selected = workspaceRef.current;
    const runtime = runtimeStateRef.current;
    if (
      selected === undefined ||
      runtime === undefined ||
      !["ready", "busy"].includes(runtime.status) ||
      params.generation !== runtime.generation ||
      params.serverInstanceId !== runtime.serverInstanceId ||
      params.workspaceId !== selected.workspaceId
    )
      return undefined;

    const existing = threadsRef.current.find((thread) => thread.threadId === params.threadId);
    if (existing !== undefined && params.revision <= existing.revision) return undefined;
    if (existing !== undefined && existing.preferences !== null) {
      setThreads((current) => {
        const latest = current.find((thread) => thread.threadId === params.threadId);
        if (
          latest === undefined ||
          latest.preferences === null ||
          params.revision <= latest.revision
        )
          return current;
        const next = current.map((thread) => {
          if (thread.threadId !== params.threadId || thread.preferences === null) return thread;
          return {
            ...thread,
            title: params.title,
            revision: params.revision,
            updatedAt: params.occurredAt,
            preferences: { ...thread.preferences, titleSource: params.titleSource },
          };
        });
        threadsRef.current = next;
        return next;
      });
      useTimelineStore.getState().recordThreadMetadataRevision(params.threadId, params.revision);
      return undefined;
    }

    let active = true;
    const issuedEpoch = directoryMutationEpochRef.current;
    void history
      .threadList({ workspaceId: selected.workspaceId, limit: 200 })
      .then((listed) => {
        const currentRuntime = runtimeStateRef.current;
        if (
          !active ||
          !mountedRef.current ||
          workspaceRef.current?.workspaceId !== selected.workspaceId ||
          currentRuntime?.generation !== params.generation ||
          currentRuntime.serverInstanceId !== params.serverInstanceId ||
          listed.items.some((thread) => thread.workspaceId !== selected.workspaceId)
        )
          return;
        const authoritative = listed.items.find((thread) => thread.threadId === params.threadId);
        if (authoritative === undefined || authoritative.revision < params.revision) return;
        setThreads((current) => {
          const next = mergeThreadListResponse(
            current,
            selected.workspaceId,
            listed.items,
            issuedEpoch,
            localCreationEpochsRef.current,
          );
          threadsRef.current = next;
          return next;
        });
        for (const [threadId, creation] of localCreationEpochsRef.current) {
          if (creation.workspaceId === selected.workspaceId && creation.epoch <= issuedEpoch)
            localCreationEpochsRef.current.delete(threadId);
        }
        useTimelineStore
          .getState()
          .recordThreadMetadataRevision(authoritative.threadId, authoritative.revision);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [history, metadataEvent]);

  /**
   * 仅在 latest Turn identity/status 转换时覆盖目录状态；selector 的稳定字符串让正文与 Tool
   * delta 保持同值，不会触发整栏 React 更新。
   */
  useEffect(() => {
    if (timelineStatusSignature === "") return;
    const timeline = useTimelineStore.getState();
    setThreads((current) => {
      let changed = false;
      const next = current.map((thread) => {
        const projection = timeline.threads[thread.threadId];
        if (projection === undefined) return thread;
        const latestTurnId = projection.latestTurnId;
        const status =
          latestTurnId === undefined ? null : (timeline.turns[latestTurnId]?.status ?? null);
        const previous = latestTurnProjectionRef.current.get(thread.threadId);
        if (latestTurnId !== undefined) {
          latestTurnProjectionRef.current.set(thread.threadId, { turnId: latestTurnId, status });
        }
        const newlyTerminal =
          latestTurnId !== undefined &&
          (status === "completed" || status === "failed") &&
          (previous === undefined
            ? thread.latestTurnStatus !== status
            : previous.turnId !== latestTurnId ||
              (previous.status !== "completed" && previous.status !== "failed"));
        if (thread.latestTurnStatus === status && !newlyTerminal) return thread;
        changed = true;
        return {
          ...thread,
          latestTurnStatus: status,
          latestTurnSeen: newlyTerminal ? false : thread.latestTurnSeen,
        };
      });
      if (!changed) return current;
      threadsRef.current = next;
      return next;
    });
  }, [timelineStatusSignature]);

  /**
   * Effect 发生在当前 Timeline 与侧栏终态提醒完成 commit 之后，因此 only-current 会话才会
   * 确认 seen；非活动会话保留未读标记，实时 queued/running/approval/suspended 不受影响。
   */
  useEffect(() => {
    if (currentThreadId === undefined || currentLatestTerminalKey === undefined) return;
    const thread = threads.find((candidate) => candidate.threadId === currentThreadId);
    if (
      thread === undefined ||
      thread.latestTurnSeen ||
      (thread.latestTurnStatus !== "completed" && thread.latestTurnStatus !== "failed")
    )
      return;
    const attemptKey = `${currentThreadId}:${currentLatestTerminalKey}`;
    if (seenAttemptKeysRef.current.has(attemptKey)) return;
    seenAttemptKeysRef.current.add(attemptKey);
    void markLatestTurnSeen(currentThreadId).then((succeeded) => {
      // 只释放失败的精确 terminal identity；成功键继续去重，避免无关重渲染重复确认已读。
      if (!succeeded) seenAttemptKeysRef.current.delete(attemptKey);
    });
  }, [currentLatestTerminalKey, currentThreadId, markLatestTurnSeen, threads]);

  /** 统一消费自动与手动 Context event；命令响应只在 event 尚未到达时提供同口径反馈。 */
  useEffect(() => {
    if (observedCompaction === undefined) return;
    if (observedCompaction.phase === "started") {
      setCompaction({ phase: "running", message: "正在压缩上下文…", retryable: false });
      return;
    }
    if (observedCompaction.phase === "compacted") {
      if (
        observedCompaction.inputTokensBefore === null ||
        observedCompaction.inputTokensAfter === null
      ) {
        setCompaction({
          phase: "error",
          message: "对话上下文状态异常，请重新打开会话。",
          retryable: false,
        });
        return;
      }
      setCompaction({
        phase: "success",
        message: compactedMessage({
          inputTokensBefore: observedCompaction.inputTokensBefore,
          inputTokensAfter: observedCompaction.inputTokensAfter,
        }),
        retryable: false,
      });
      return;
    }
    const feedback = compactionError({ code: observedCompaction.errorCode });
    setCompaction({ phase: "error", ...feedback });
  }, [observedCompaction]);

  /**
   * workspace commit、首次模型选择或新的 ready generation 到达后恢复历史；admission key 不含
   * ready/busy 状态，避免 Turn 运行导致目录反复重载，同时允许迁移较慢的 sidecar 就绪后补跑。
   * 手动跨项目 Thread 选择会自行恢复目标 snapshot，因此跳过默认 first-thread 加载。
   */
  useEffect(() => {
    const nextWorkspaceId = workspace?.workspaceId;
    if (
      creationInFlightRef.current !== undefined &&
      historyWorkspaceIdRef.current === nextWorkspaceId
    )
      return;
    historyWorkspaceIdRef.current = nextWorkspaceId;
    requestRef.current += 1;
    currentThreadIdRef.current = undefined;
    setCurrentThreadId(undefined);
    setError(undefined);
    setCompaction({ phase: "idle", retryable: false });
    latestTurnProjectionRef.current.clear();
    seenAttemptKeysRef.current.clear();
    useTimelineStore.getState().reset();
    const admittedRuntime = runtimeStateRef.current;
    if (admittedRuntime !== undefined && ["ready", "busy"].includes(admittedRuntime.status)) {
      useTimelineStore.getState().applyRuntimeStatus(admittedRuntime);
    }
    const admittedSelection = modelSelectionRef.current;
    if (
      workspace === undefined ||
      admittedSelection === undefined ||
      historyRuntimeAdmission === undefined
    ) {
      setBusy(false);
      return;
    }
    if (manualWorkspaceTargetRef.current === workspace.workspaceId) return;
    void loadWorkspaceHistory(workspace, admittedSelection);
  }, [
    historyRuntimeAdmission,
    loadWorkspaceHistory,
    modelSelectionAvailable,
    workspace,
    workspaceRevision,
  ]);

  /**
   * timeline 出现 gap 或 terminal 需要补齐 ChangeSet 时，同一 generation/reason 只自动恢复一次；
   * 失败保持可见，用户重新选择 Thread 是显式 bounded retry，避免 effect 重试环。
   */
  useEffect(() => {
    if (
      creationInFlightRef.current !== undefined ||
      currentThreadId === undefined ||
      currentResyncReason === undefined ||
      workspace === undefined
    ) {
      automaticResyncAttemptRef.current = undefined;
      return;
    }
    const timeline = useTimelineStore.getState();
    const key = [
      timeline.handshake.generation,
      timeline.serverInstanceId ?? "none",
      workspace.workspaceId,
      currentThreadId,
      currentResyncReason,
    ].join(":");
    if (automaticResyncAttemptRef.current === key) return;
    automaticResyncAttemptRef.current = key;
    const request = requestRef.current + 1;
    requestRef.current = request;
    setBusy(true);
    setError(undefined);
    void (async (): Promise<void> => {
      try {
        const snapshot = await history.threadRead({ threadId: currentThreadId });
        if (!isCurrentRequest(request, workspace.workspaceId)) return;
        if (!applySnapshot(snapshot, workspace.workspaceId, currentThreadId)) {
          setError("会话状态无法自动恢复，请重新选择该会话重试。 ");
        }
      } catch {
        if (isCurrentRequest(request, workspace.workspaceId))
          setError("会话状态暂时无法自动恢复，请重新选择该会话重试。 ");
      } finally {
        if (isCurrentRequest(request, workspace.workspaceId)) setBusy(false);
      }
    })();
  }, [applySnapshot, currentResyncReason, currentThreadId, history, isCurrentRequest, workspace]);

  const canCompact =
    currentThreadId !== undefined &&
    currentThreadRevision !== undefined &&
    !activeTurnPresent &&
    !busy &&
    compaction.phase !== "running" &&
    runtimeState !== undefined &&
    ["ready", "busy"].includes(runtimeState.status);

  return {
    threads,
    currentThreadId,
    busy,
    error,
    mutatingThreadIds,
    compaction,
    canCompact,
    create,
    select,
    search,
    rename,
    pin,
    archive,
    restore,
    updatePreferences,
    compact,
    dismissCompactionFeedback,
  };
}
