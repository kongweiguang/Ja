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
  compaction: ConversationCompactionView;
  canCompact: boolean;
  create(): Promise<void>;
  select(threadId: string): Promise<void>;
  search(query: string): Promise<ConversationThread[]>;
  rename(threadId: string, title: string): Promise<void>;
  updatePreferences(input: {
    providerId: string;
    modelId: string;
    reasoningLevel: ReasoningLevel | null;
    accessMode: ConversationAccessMode;
  }): Promise<void>;
  compact(): Promise<void>;
  dismissCompactionFeedback(): void;
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
  TOKEN_COUNT_UNAVAILABLE: { message: "暂时无法精确计算 Token，请稍后重试。", retryable: true },
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
 * 用当前 workspace 的服务端排序替换同范围历史，同时保留其它 workspace 的最近访问项；
 * 协议没有跨 workspace 全局时间，因此固定当前范围优先并用 identity 去重。
 */
function mergeRecentThreads(
  current: ConversationThread[],
  workspaceId: string,
  scoped: ConversationThread[],
): ConversationThread[] {
  const seen = new Set<string>();
  const merged: ConversationThread[] = [];
  for (const thread of [
    ...scoped,
    ...current.filter((candidate) => candidate.workspaceId !== workspaceId),
  ]) {
    if (thread.status === "archived" || seen.has(thread.threadId)) continue;
    seen.add(thread.threadId);
    merged.push(thread);
    if (merged.length === MAX_RECENT_THREADS) break;
  }
  return merged;
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
  const merged = current.map((thread) => byId.get(thread.threadId) ?? thread);
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
  const [compaction, setCompaction] = useState<ConversationCompactionView>({
    phase: "idle",
    retryable: false,
  });
  const mountedRef = useRef(false);
  const requestRef = useRef(0);
  const compactionRequestRef = useRef(0);
  const compactionInFlightRef = useRef(false);
  const threadMutationGuardsRef = useRef(new Set<string>());
  const workspaceRef = useRef(workspace);
  const modelSelectionRef = useRef(modelSelection);
  const accessModeRef = useRef(accessMode);
  const runtimeStateRef = useRef(runtimeState);
  const automaticResyncAttemptRef = useRef<string | undefined>(undefined);
  const manualWorkspaceTargetRef = useRef<string | undefined>(undefined);
  const currentThreadIdRef = useRef(currentThreadId);
  const threadsRef = useRef(threads);
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
      return useTimelineStore.getState().applySnapshot(snapshot, workspaceId) === "applied";
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
        const first = listed.items[0];
        if (first !== undefined) {
          const snapshot = await history.threadRead({ threadId: first.threadId });
          if (!isCurrentRequest(request, selected.workspaceId)) return;
          if (!applySnapshot(snapshot, selected.workspaceId, first.threadId)) {
            setError("最近的会话无法恢复，请重新选择项目。 ");
            return;
          }
          setThreads((current) => mergeRecentThreads(current, selected.workspaceId, listed.items));
          setCurrentThreadId(first.threadId);
          return;
        }
        const created = await createAndRestore(selected, selection, request);
        if (!isCurrentRequest(request, selected.workspaceId) || created === undefined) return;
        setThreads((current) => mergeRecentThreads(current, selected.workspaceId, [created]));
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
        setThreads((current) =>
          mergeRecentThreads(current, selected.workspaceId, [
            created,
            ...current.filter(
              (thread) =>
                thread.workspaceId === selected.workspaceId && thread.threadId !== created.threadId,
            ),
          ]),
        );
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

  /** 普通“新对话”在点击时读取最新 v3 默认选择，避免 render 闭包冻结旧模型。 */
  const create = useCallback(async (): Promise<void> => {
    const selection = modelSelectionRef.current;
    if (selection === undefined) return;
    await createWithSelection(selection);
  }, [createWithSelection]);

  /**
   * 读取指定 Thread；跨 workspace 时先请求 workspace controller 建立 capability，随后仍由
   * 本 controller 恢复 snapshot，确保 Thread 状态只有一个 owner。
   */
  const select = useCallback(
    async (threadId: string): Promise<void> => {
      const target = threadsRef.current.find((thread) => thread.threadId === threadId);
      let selected = workspaceRef.current;
      if (target === undefined || selected === undefined) return;
      if (target.workspaceId !== selected.workspaceId) {
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
      setBusy(true);
      setError(undefined);
      try {
        if (!canReuseLoadedThread(threadId, selected.workspaceId)) {
          const snapshot = await history.threadRead({ threadId });
          if (!isCurrentRequest(request, selected.workspaceId)) return;
          if (!applySnapshot(snapshot, selected.workspaceId, threadId)) {
            setError("会话无法恢复，请重新选择项目。 ");
            return;
          }
        }
        if (!isCurrentRequest(request, selected.workspaceId)) return;
        setThreads((current) =>
          mergeRecentThreads(
            current,
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
      return result.items.filter((thread) => thread.status === "active");
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
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestRef.current += 1;
      compactionRequestRef.current += 1;
      compactionInFlightRef.current = false;
      threadMutationGuards.clear();
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
          const next = mergeRecentThreads(current, selected.workspaceId, listed.items);
          threadsRef.current = next;
          return next;
        });
        useTimelineStore
          .getState()
          .recordThreadMetadataRevision(authoritative.threadId, authoritative.revision);
      })
      .catch(() => undefined);
    return () => {
      active = false;
    };
  }, [history, metadataEvent]);

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
    requestRef.current += 1;
    setCurrentThreadId(undefined);
    setError(undefined);
    setCompaction({ phase: "idle", retryable: false });
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
    compaction,
    canCompact,
    create,
    select,
    search,
    rename,
    updatePreferences,
    compact,
    dismissCompactionFeedback,
  };
}
