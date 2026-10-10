// @author kongweiguang
import { useCallback, useEffect, useLayoutEffect, useReducer, useRef } from "react";
import {
  ChatHttpError,
  discardAttachments,
  observeChat,
  postChat,
  postExperience,
  readRecoveries,
  resolveRecovery as postRecoveryResolution,
  uploadAttachment,
} from "./api";
import { deletePendingUpload, readPendingUpload, savePendingUpload } from "./attachmentStore";
import {
  appReducer,
  asTurnState,
  createInitialState,
  emptyTurn,
  getSession,
  isTerminal,
  readPreferences,
  writePreferences,
} from "./state";
import type { AppAction, StoredPreferences } from "./state";
import type {
  AgentView,
  AttachmentCapabilities,
  AttachmentDraft,
  AttachmentMetadata,
  ChatTurn,
  HistoryPage,
  InteractionAnswer,
  MessageContent,
  NoteView,
  PendingControl,
  PendingQueueSubmission,
  PendingSubmission,
  RecoveryView,
  ThreadSession,
  ThreadView,
  TurnStatusView,
  WaitView,
  WorkbenchTab,
} from "./types";

const reconnectDelay = [700, 1400, 2800, 5600, 10_000, 15_000];

/** 仅在已受理恢复后跨越旧暂停；人工等待、故障与新暂停仍交还给用户处理。 */
function canContinueAcceptedResume(
  turn: ChatTurn,
  recoveries: RecoveryView[] | undefined,
  initialCursor: number,
): boolean {
  if (
    recoveries === undefined ||
    recoveries.length > 0 ||
    turn.recoveryError ||
    turn.resumeRequired ||
    turn.error ||
    turn.wait?.status === "PENDING"
  )
    return false;

  let userPausePending = false;
  for (const event of turn.events) {
    if (event.sequence <= initialCursor || event.eventType !== "agent.execution.control") continue;
    const payload = JSON.parse(event.payloadJson) as { action?: string; reasonCode?: string };
    if (payload.action === "SUSPEND_REQUESTED" && payload.reasonCode === "USER_PAUSED")
      userPausePending = true;
    if (payload.action === "SUSPEND_CONSUMED") userPausePending = false;
  }
  return !userPausePending;
}

/** 状态事件先于等待引用到达；加载完整的未回答等待后才停止观察并交给用户。 */
function hasPendingWait(turn: ChatTurn): boolean {
  return turn.wait?.status === "PENDING";
}

type LoadHistory = (threadId: string, scrollToEnd?: boolean) => Promise<void>;
type ObserveTurn = (
  threadId: string,
  initialTurn: ChatTurn,
  submitFirst: boolean,
  resumeAccepted?: boolean,
) => Promise<void>;

/** 由单一归约器管理可见对话状态，镜像引用确保异步 SSE 回调按协议接收顺序更新。 */
export function useWorkbench() {
  const [state, reactDispatch] = useReducer(appReducer, undefined, createInitialState);
  const stateRef = useRef(state);
  const bootstrapped = useRef(false);
  const listGeneration = useRef(0);
  const threadListContext = useRef("");
  const threadGeneration = useRef(0);
  const historyRequests = useRef(new Map<string, symbol>());
  const recoveryRequests = useRef(new Map<string, symbol>());
  const streamGeneration = useRef(0);
  const workbenchGeneration = useRef(0);
  const workbenchContext = useRef("");
  const activeStream = useRef<{ threadId: string; abort: AbortController } | null>(null);
  const enqueueRequests = useRef(new Map<string, Promise<void>>());
  const cancelInFlight = useRef(new Set<string>());
  const attachmentUploads = useRef(new Map<string, AbortController>());
  const automaticUploadRetries = useRef(new Set<string>());
  const creating = useRef(false);
  const loadHistoryImplementation = useRef<LoadHistory>(async () => undefined);
  const observeTurnImplementation = useRef<ObserveTurn>(async () => undefined);

  /**
   * @author kongweiguang
   * 所有 reducer 更新都经过 commit；仅在派发入口同步镜像，避免被较旧渲染的被动 effect 回写覆盖。
   */
  const commit = useCallback((action: AppAction) => {
    stateRef.current = appReducer(stateRef.current, action);
    reactDispatch(action);
  }, []);

  /** 保持历史恢复回调的引用稳定，避免 SSE 重新同步通过副作用依赖重复启动恢复。 */
  const loadHistory = useCallback<LoadHistory>(
    (threadId, scrollToEnd = false) => loadHistoryImplementation.current(threadId, scrollToEnd),
    [],
  );
  /** 稳定监听引用并完整转发恢复语义，避免重渲染重启监听或把已受理恢复误判为普通重连。 */
  const observeTurn = useCallback<ObserveTurn>(
    (threadId, initialTurn, submitFirst, resumeAccepted) =>
      observeTurnImplementation.current(threadId, initialTurn, submitFirst, resumeAccepted),
    [],
  );

  /** 保持轮次投影更新函数稳定，因为异步流回调可能跨越多次 React 渲染。 */
  const patchTurn = useCallback(
    (threadId: string, turnId: string, patch: Partial<ChatTurn>): ChatTurn => {
      const turn = getSession(stateRef.current, threadId).turns.find(
        (item) => item.turnId === turnId,
      );
      if (!turn) return emptyTurn(turnId, "", []);
      const next = { ...turn, ...patch };
      commit({ type: "session-turn", threadId, turn: next });
      return next;
    },
    [commit],
  );

  /** 只把当前 Core revision 的待恢复项投影到原 Thread，避免迟到查询覆盖更新裁决。 */
  const refreshRecoveries = useCallback(
    async (threadId: string, turnId: string): Promise<RecoveryView[] | undefined> => {
      const requestKey = `${threadId}:${turnId}`;
      const requestId = Symbol(requestKey);
      recoveryRequests.current.set(requestKey, requestId);
      try {
        const recoveries = await readRecoveries(threadId, turnId);
        if (recoveryRequests.current.get(requestKey) !== requestId) return undefined;
        const current = getSession(stateRef.current, threadId).turns.find(
          (item) => item.turnId === turnId,
        );
        if (current)
          commit({
            type: "session-turn",
            threadId,
            turn: { ...current, recoveries, recoveryError: undefined },
          });
        return recoveries;
      } catch (error) {
        if (recoveryRequests.current.get(requestKey) === requestId) {
          const current = getSession(stateRef.current, threadId).turns.find(
            (item) => item.turnId === turnId,
          );
          if (current)
            commit({
              type: "session-turn",
              threadId,
              turn: { ...current, recoveryError: errorMessage(error) },
            });
        }
        return undefined;
      } finally {
        if (recoveryRequests.current.get(requestKey) === requestId)
          recoveryRequests.current.delete(requestKey);
      }
    },
    [commit],
  );

  /** 回读服务端原始阶段与需显式 resume 的标记，不用本地推断覆盖 Core 状态。 */
  const refreshTurnStatus = useCallback(
    async (threadId: string, turnId: string): Promise<TurnStatusView | undefined> => {
      try {
        const status = await postChat<TurnStatusView>("status", { threadId, turnId });
        const current = getSession(stateRef.current, threadId).turns.find(
          (turn) => turn.turnId === turnId,
        );
        const reported = asTurnState(status.state);
        const state =
          current && isTerminal(current.state) && !isTerminal(reported) ? current.state : reported;
        const resumeRequired = status.resumeRequired === true && !isTerminal(state);
        if (current)
          commit({
            type: "session-turn",
            threadId,
            turn: {
              ...current,
              state,
              resumeRequired,
              cancelRequested: current.cancelRequested && !isTerminal(state),
            },
          });
        return { ...status, state, resumeRequired };
      } catch {
        return undefined;
      }
    },
    [commit],
  );

  /** 仅序列化显示偏好、草稿和服务端结果仍未确认的操作。 */
  const persist = useCallback(
    (override: Partial<StoredPreferences> = {}) => {
      const current = stateRef.current;
      const drafts: Record<string, string> = {};
      const selectedSkills: Record<string, string[]> = {};
      const attachmentDrafts: Record<string, AttachmentDraft[]> = {};
      const scrollTops: Record<string, number> = {};
      for (const [threadId, session] of Object.entries(current.sessions)) {
        if (session.draft) drafts[threadId] = session.draft;
        if (session.selectedSkills.length) selectedSkills[threadId] = session.selectedSkills;
        if (session.attachmentDrafts.length) attachmentDrafts[threadId] = session.attachmentDrafts;
        if (session.scrollTop) scrollTops[threadId] = session.scrollTop;
      }
      const existing = readPreferences();
      const saved = {
        ...existing,
        activeThreadId: current.selectedThreadId,
        theme: current.theme,
        drafts,
        selectedSkills,
        attachmentDrafts,
        scrollTops,
        pending: current.pending,
        pendingQueue: current.pendingQueue,
        pendingControls: current.pendingControls,
        pendingRecoveries: current.pendingRecoveries,
        pendingCreateKey: current.pendingCreateKey,
        pendingCreateAgentId: current.pendingCreateAgentId,
        ...override,
      };
      if (!writePreferences(saved))
        commit({
          type: "patch",
          value: {
            notice: { tone: "error", text: "浏览器无法保存恢复信息；服务端执行仍会继续。" },
          },
        });
    },
    [commit],
  );

  /** 只有状态查询或受理事件确认服务端已知晓操作后，才清除未确认标记。 */
  const clearPending = useCallback(
    (threadId: string) => {
      const pending = { ...stateRef.current.pending };
      delete pending[threadId];
      commit({ type: "patch", value: { pending } });
      persist({ pending });
    },
    [commit, persist],
  );

  /** 只删除已确认或被明确拒绝的裁决凭据，未知结果必须保留原操作键。 */
  const clearPendingRecovery = useCallback(
    (operationId: string) => {
      const pendingRecoveries = { ...stateRef.current.pendingRecoveries };
      delete pendingRecoveries[operationId];
      commit({ type: "patch", value: { pendingRecoveries } });
      persist({ pendingRecoveries });
    },
    [commit, persist],
  );

  /** 允许使用相同键重试前先查询待确认提交，避免重复提交已受理的工作。 */
  const reconcilePending = useCallback(
    async (threadId: string, pending: PendingSubmission | undefined, generation: number) => {
      if (!pending) return;
      try {
        const status = await postChat<TurnStatusView>("status", {
          threadId,
          turnId: pending.turnId,
        });
        if (generation !== threadGeneration.current) return;
        const existing = getSession(stateRef.current, threadId).turns.find(
          (turn) => turn.turnId === pending.turnId,
        );
        const turn =
          existing ??
          emptyTurn(pending.turnId, pending.text, pending.skillIds ?? [], pending.operationKey);
        commit({
          type: "session-turn",
          threadId,
          turn: {
            ...turn,
            attachments: pending.attachments ?? [],
            state: asTurnState(status.state),
            resumeRequired: status.resumeRequired,
            operationKey: pending.operationKey,
          },
        });
        clearPending(threadId);
      } catch (error) {
        if (generation !== threadGeneration.current) return;
        const currentSession = getSession(stateRef.current, threadId);
        if (!currentSession.turns.some((turn) => turn.turnId === pending.turnId)) {
          commit({
            type: "session-turn",
            threadId,
            turn: {
              ...emptyTurn(pending.turnId, pending.text, pending.skillIds, pending.operationKey),
              state: "UNKNOWN",
            },
          });
        }
        if (!isNotFound(error))
          commit({
            type: "session-patch",
            threadId,
            value: { connectError: `提交状态读取失败：${errorMessage(error)}` },
          });
      }
    },
    [clearPending, commit],
  );

  /** 仅刷新当前请求的有界投影，防止过期搜索响应覆盖较新的结果。 */
  const refreshThreads = useCallback(
    async (search = stateRef.current.search, archived = stateRef.current.archivedView) => {
      const generation = ++listGeneration.current;
      threadListContext.current = `${archived}:${search}`;
      commit({ type: "patch", value: { threadListLoadingMore: false } });
      try {
        const threads = await postChat<ThreadView[]>("threads/list", {
          archived,
          search,
          limit: 50,
          offset: 0,
        });
        if (
          generation === listGeneration.current &&
          stateRef.current.search === search &&
          stateRef.current.archivedView === archived
        ) {
          commit({
            type: "patch",
            value: {
              threads,
              threadHasMore: threads.length === 50,
              threadListLoadingMore: false,
              loadingError: undefined,
            },
          });
        }
        return threads;
      } catch (error) {
        if (
          generation === listGeneration.current &&
          stateRef.current.search === search &&
          stateRef.current.archivedView === archived
        ) {
          commit({
            type: "patch",
            value: {
              threadHasMore: false,
              threadListLoadingMore: false,
              notice: { tone: "error", text: errorMessage(error) },
            },
          });
        }
        return [];
      }
    },
    [commit],
  );

  /** 续读当前服务端列表页，禁止将旧搜索结果追加到新搜索中。 */
  const loadMoreThreads = useCallback(async () => {
    const current = stateRef.current;
    if (!current.threadHasMore || current.threadListLoadingMore) return;
    const generation = listGeneration.current;
    const { archivedView: archived, search } = current;
    if (threadListContext.current !== `${archived}:${search}`) return;
    const offset = current.threads.length;
    commit({ type: "patch", value: { threadListLoadingMore: true } });
    try {
      const page = await postChat<ThreadView[]>("threads/list", {
        archived,
        search,
        limit: 50,
        offset,
      });
      const latest = stateRef.current;
      if (
        generation !== listGeneration.current ||
        latest.search !== search ||
        latest.archivedView !== archived
      )
        return;
      const combined = [...latest.threads];
      const known = new Set(combined.map((thread) => thread.threadId));
      for (const thread of page) if (!known.has(thread.threadId)) combined.push(thread);
      commit({
        type: "patch",
        value: {
          threads: combined,
          threadHasMore: page.length === 50,
          threadListLoadingMore: false,
        },
      });
    } catch (error) {
      if (
        generation === listGeneration.current &&
        stateRef.current.search === search &&
        stateRef.current.archivedView === archived
      ) {
        commit({
          type: "patch",
          value: {
            threadListLoadingMore: false,
            notice: { tone: "error", text: `读取更多会话失败：${errorMessage(error)}` },
          },
        });
      }
    }
  }, [commit]);

  /** 持久保存幂等键，使创建响应丢失后仍能安全恢复同一服务端会话。 */
  const createThread = useCallback(
    async (select = true) => {
      if (creating.current) return undefined;
      creating.current = true;
      let operationKey = stateRef.current.pendingCreateKey;
      const agentId = stateRef.current.pendingCreateAgentId ?? stateRef.current.newAgentId;
      if (!operationKey) {
        operationKey = `thread_${randomId()}`;
        commit({
          type: "patch",
          value: { pendingCreateKey: operationKey, pendingCreateAgentId: agentId },
        });
        persist({ pendingCreateKey: operationKey, pendingCreateAgentId: agentId });
      }
      try {
        const thread = await postChat<ThreadView>("threads/create", { operationKey, agentId });
        commit({
          type: "patch",
          value: {
            pendingCreateKey: undefined,
            pendingCreateAgentId: undefined,
            threads: [
              thread,
              ...stateRef.current.threads.filter((item) => item.threadId !== thread.threadId),
            ],
            selectedThreadId: select ? thread.threadId : stateRef.current.selectedThreadId,
            sidebarOpen: false,
            notice: undefined,
          },
        });
        persist({
          pendingCreateKey: undefined,
          pendingCreateAgentId: undefined,
          activeThreadId: select ? thread.threadId : stateRef.current.selectedThreadId,
        });
        if (select) await loadHistory(thread.threadId, true);
        void refreshThreads();
        return thread;
      } catch (error) {
        commit({
          type: "patch",
          value: { notice: { tone: "error", text: `创建会话失败：${errorMessage(error)}` } },
        });
        return undefined;
      } finally {
        creating.current = false;
      }
    },
    [commit, loadHistory, persist, refreshThreads],
  );

  /**
   * @author kongweiguang
   * 历史恢复只处理快照/pending 中的既有 Turn，并在 await 后核对 Thread 与流代际，避免迟到恢复接管新提交。
   */
  const loadHistoryImpl = useCallback(
    async (threadId: string, scrollToEnd = false) => {
      const generation = ++threadGeneration.current;
      const streamGenerationAtStart = streamGeneration.current;
      const requestId = Symbol(threadId);
      historyRequests.current.set(threadId, requestId);
      activeStream.current?.abort.abort();
      activeStream.current = null;
      const current = stateRef.current;
      commit({
        type: "session-patch",
        threadId,
        value: { ...getSession(current, threadId), loadingHistory: true },
      });
      const allItems: HistoryPage["items"] = [];
      let afterSequence = 0;
      let throughSequence: number | undefined;
      let page: HistoryPage | undefined;
      let pageCount = 0;
      try {
        do {
          page = await postChat<HistoryPage>("threads/history", {
            threadId,
            afterSequence,
            ...(throughSequence === undefined ? {} : { throughSequence }),
            limit: 200,
          });
          if (generation !== threadGeneration.current) {
            return;
          }
          throughSequence ??= page.throughSequence;
          allItems.push(...page.items);
          if (!page.hasMore || page.nextSequence <= afterSequence) break;
          afterSequence = page.nextSequence;
          pageCount += 1;
        } while (page?.hasMore && pageCount < 200);

        const capped = Boolean(page?.hasMore && pageCount >= 200);
        commit({
          type: "history",
          threadId,
          items: allItems,
          latestTurn: page?.latestTurn,
          throughSequence: throughSequence ?? 0,
          nextSequence: page?.nextSequence ?? 0,
          hasMore: Boolean(page?.hasMore || capped),
        });
        const restored = getSession(stateRef.current, threadId).turns.at(-1);
        const restoredTurnId = stateRef.current.pending[threadId]?.turnId ?? restored?.turnId;
        if (restored?.waitRecordId && !isTerminal(restored.state)) {
          const waitRecordId = restored.waitRecordId;
          const wait = await postChat<WaitView>("wait", {
            threadId,
            turnId: restored.turnId,
            recordId: waitRecordId,
          });
          if (
            generation !== threadGeneration.current ||
            streamGenerationAtStart !== streamGeneration.current
          )
            return;
          const latest = stateRef.current.sessions[threadId]?.turns.find(
            (turn) => turn.turnId === restored.turnId,
          );
          if (latest?.waitRecordId === waitRecordId && latest.cursor <= restored.cursor)
            commit({
              type: "session-turn",
              threadId,
              turn: { ...latest, wait, waitAnswered: wait.status !== "PENDING" },
            });
        }
        const pending = stateRef.current.pending[threadId];
        await reconcilePending(threadId, pending, generation);
        if (
          generation !== threadGeneration.current ||
          streamGenerationAtStart !== streamGeneration.current
        )
          return;
        if (scrollToEnd) requestAnimationFrame(() => scrollConversationToEnd(threadId));
        const session = getSession(stateRef.current, threadId);
        const latestBeforeRecovery = restoredTurnId
          ? session.turns.find((turn) => turn.turnId === restoredTurnId)
          : undefined;
        if (
          latestBeforeRecovery &&
          !isTerminal(latestBeforeRecovery.state) &&
          latestBeforeRecovery.state !== "HISTORY"
        )
          await refreshTurnStatus(threadId, latestBeforeRecovery.turnId);
        if (
          generation !== threadGeneration.current ||
          streamGenerationAtStart !== streamGeneration.current
        )
          return;
        if (latestBeforeRecovery) await refreshRecoveries(threadId, latestBeforeRecovery.turnId);
        if (
          generation !== threadGeneration.current ||
          streamGenerationAtStart !== streamGeneration.current
        )
          return;
        const latest = restoredTurnId
          ? getSession(stateRef.current, threadId).turns.find(
              (turn) => turn.turnId === restoredTurnId,
            )
          : undefined;
        const uncertain = stateRef.current.pending[threadId]?.turnId === latest?.turnId;
        if (
          latest &&
          !latest.resumeRequired &&
          !uncertain &&
          !isTerminal(latest.state) &&
          latest.state !== "SUSPENDED"
        )
          void observeTurn(threadId, latest, false);
        if (capped)
          commit({
            type: "patch",
            value: {
              notice: {
                tone: "info",
                text: "历史记录较多，已显示前一部分；可在对话底部继续加载。",
              },
            },
          });
      } catch (error) {
        if (generation === threadGeneration.current) {
          commit({
            type: "patch",
            value: { notice: { tone: "error", text: `读取历史失败：${errorMessage(error)}` } },
          });
        }
      } finally {
        if (historyRequests.current.get(threadId) === requestId) {
          historyRequests.current.delete(threadId);
          if (getSession(stateRef.current, threadId).loadingHistory) {
            commit({ type: "session-patch", threadId, value: { loadingHistory: false } });
          }
        }
      }
    },
    [commit, observeTurn, reconcilePending, refreshRecoveries, refreshTurnStatus],
  );

  /**
   * @author kongweiguang
   * 沿用固定历史水位分页；后续状态/恢复读取仅操作请求开始时的 Turn，避免迟到分页重启新提交。
   */
  const loadMoreHistory = useCallback(
    async (threadId: string) => {
      const session = getSession(stateRef.current, threadId);
      if (
        !session.historyHasMore ||
        session.loadingHistory ||
        session.historyNextSequence === undefined
      )
        return;
      const generation = ++threadGeneration.current;
      const streamGenerationAtStart = streamGeneration.current;
      const recoveryTurnId =
        session.turns.at(-1)?.turnId ?? stateRef.current.pending[threadId]?.turnId;
      const requestId = Symbol(threadId);
      historyRequests.current.set(threadId, requestId);
      activeStream.current?.abort.abort();
      activeStream.current = null;
      let afterSequence = session.historyNextSequence;
      const throughSequence = session.historyThroughSequence;
      let page: HistoryPage | undefined;
      let pageCount = 0;
      const items: HistoryPage["items"] = [];
      commit({ type: "session-patch", threadId, value: { loadingHistory: true } });
      try {
        do {
          page = await postChat<HistoryPage>("threads/history", {
            threadId,
            afterSequence,
            ...(throughSequence === undefined ? {} : { throughSequence }),
            limit: 200,
          });
          if (generation !== threadGeneration.current) {
            return;
          }
          items.push(...page.items);
          if (!page.hasMore || page.nextSequence <= afterSequence) break;
          afterSequence = page.nextSequence;
          pageCount += 1;
        } while (page?.hasMore && pageCount < 20);
        const hasMore = Boolean(page?.hasMore);
        commit({
          type: "history-append",
          threadId,
          items,
          latestTurn: page?.latestTurn,
          nextSequence: page?.nextSequence ?? afterSequence,
          hasMore,
        });
        if (
          generation !== threadGeneration.current ||
          streamGenerationAtStart !== streamGeneration.current
        )
          return;
        let latest = recoveryTurnId
          ? getSession(stateRef.current, threadId).turns.find(
              (turn) => turn.turnId === recoveryTurnId,
            )
          : undefined;
        const pending = stateRef.current.pending[threadId]?.turnId === latest?.turnId;
        if (
          latest &&
          !pending &&
          !isTerminal(latest.state) &&
          latest.state !== "SUSPENDED" &&
          latest.state !== "HISTORY"
        ) {
          await refreshTurnStatus(threadId, latest.turnId);
          if (
            generation !== threadGeneration.current ||
            streamGenerationAtStart !== streamGeneration.current
          )
            return;
          latest = getSession(stateRef.current, threadId).turns.find(
            (turn) => turn.turnId === recoveryTurnId,
          );
        }
        if (latest?.state === "SUSPENDED") {
          await refreshRecoveries(threadId, latest.turnId);
          if (
            generation !== threadGeneration.current ||
            streamGenerationAtStart !== streamGeneration.current
          )
            return;
          latest = getSession(stateRef.current, threadId).turns.find(
            (turn) => turn.turnId === recoveryTurnId,
          );
        }
        const latestIsPending = stateRef.current.pending[threadId]?.turnId === latest?.turnId;
        if (
          latest &&
          !latest.resumeRequired &&
          !latestIsPending &&
          !isTerminal(latest.state) &&
          latest.state !== "SUSPENDED"
        )
          void observeTurn(threadId, latest, false);
        if (hasMore)
          commit({
            type: "patch",
            value: { notice: { tone: "info", text: "已继续加载一部分历史，仍有记录可读取。" } },
          });
      } catch (error) {
        if (generation === threadGeneration.current) {
          commit({
            type: "patch",
            value: { notice: { tone: "error", text: `继续读取历史失败：${errorMessage(error)}` } },
          });
        }
      } finally {
        if (historyRequests.current.get(threadId) === requestId) {
          historyRequests.current.delete(threadId);
          if (getSession(stateRef.current, threadId).loadingHistory) {
            commit({ type: "session-patch", threadId, value: { loadingHistory: false } });
          }
        }
      }
    },
    [commit, observeTurn, refreshRecoveries, refreshTurnStatus],
  );

  /** 仅在用户明确重试未确认提交时，复用原操作及完全相同的请求正文。 */
  const retrySubmission = useCallback(
    (pending: PendingSubmission) => {
      const session = getSession(stateRef.current, pending.threadId);
      const turn = session.turns.find((item) => item.turnId === pending.turnId);
      if (!turn) return;
      void (async () => {
        try {
          const status = await postChat<TurnStatusView>("status", {
            threadId: pending.threadId,
            turnId: pending.turnId,
          });
          const known = patchTurn(pending.threadId, pending.turnId, {
            state: asTurnState(status.state),
            resumeRequired: status.resumeRequired,
            operationKey: pending.operationKey,
          });
          clearPending(pending.threadId);
          if (!status.resumeRequired && !isTerminal(status.state) && status.state !== "SUSPENDED")
            void observeTurn(pending.threadId, known, false);
        } catch (error) {
          if (isNotFound(error)) {
            void observeTurn(
              pending.threadId,
              {
                ...turn,
                operationKey: pending.operationKey,
                userText: pending.text,
                skillIds: pending.skillIds,
              },
              true,
            );
            return;
          }
          commit({
            type: "session-patch",
            threadId: pending.threadId,
            value: { connectError: `状态读取失败，尚未重发：${errorMessage(error)}` },
          });
        }
      })();
    },
    [clearPending, commit, observeTurn, patchTurn],
  );

  /** 选择服务端会话时，保留该会话自己的草稿、技能选择、滚动位置和事件游标。 */
  const selectThread = useCallback(
    (threadId: string) => {
      if (stateRef.current.selectedThreadId === threadId) {
        commit({ type: "patch", value: { sidebarOpen: false } });
        return;
      }
      const previousThreadId = stateRef.current.selectedThreadId;
      activeStream.current?.abort.abort();
      activeStream.current = null;
      threadGeneration.current += 1;
      streamGeneration.current += 1;
      if (previousThreadId)
        commit({ type: "session-patch", threadId: previousThreadId, value: { observing: false } });
      const session = getSession(stateRef.current, threadId);
      commit({
        type: "patch",
        value: { selectedThreadId: threadId, sidebarOpen: false, notice: undefined },
      });
      if (!session.historyLoaded) void loadHistory(threadId, true);
      else {
        const latest = session.turns.at(-1);
        const uncertain = stateRef.current.pending[threadId]?.turnId === latest?.turnId;
        if (latest && !uncertain && !isTerminal(latest.state) && latest.state !== "SUSPENDED")
          void observeTurn(threadId, latest, false);
      }
      persist({ activeThreadId: threadId });
    },
    [commit, loadHistory, observeTurn, persist],
  );

  /** 使用已读取的版本更新会话元数据，显式展示冲突，避免无提示覆盖。 */
  const updateThread = useCallback(
    async (thread: ThreadView, patch: { title?: string; archived?: boolean }) => {
      try {
        const updated = await postChat<ThreadView>("threads/update", {
          threadId: thread.threadId,
          expectedVersion: thread.version,
          ...patch,
        });
        const next = stateRef.current.threads.filter((item) => item.threadId !== thread.threadId);
        commit({ type: "patch", value: { threads: [updated, ...next] } });
        void refreshThreads();
        return updated;
      } catch (error) {
        await refreshThreads();
        throw error;
      }
    },
    [commit, refreshThreads],
  );

  /** 只读取当前能力页所需摘要；记录正文由 Records 按需读取，避免长会话一次性发起大量请求。 */
  const loadWorkbench = useCallback(
    async (tab: WorkbenchTab, threadId = stateRef.current.selectedThreadId) => {
      if (tab === "queue" && !threadId) return;
      const generation = ++workbenchGeneration.current;
      workbenchContext.current = `${tab}:${threadId ?? ""}`;
      const currentData = { ...stateRef.current.workbenchData };
      if (tab === "queue" || tab === "records") delete currentData[tab];
      commit({ type: "patch", value: { workbenchTab: tab, workbenchLoading: true } });
      if (tab === "queue" || tab === "records")
        commit({ type: "patch", value: { workbenchData: currentData } });
      try {
        let value: unknown;
        if (tab === "skills") value = await postExperience("skills");
        else if (tab === "mcp") value = await postExperience("mcp");
        else if (tab === "tools") value = await postExperience("tools");
        else if (tab === "queue") {
          const active = getSession(stateRef.current, threadId!).turns.at(-1);
          value = active
            ? await postChat("queue", { threadId, turnId: active.turnId })
            : { entries: [] };
        } else {
          const notes = threadId ? await postExperience<NoteView[]>("notes", { threadId }) : [];
          value = { notes };
        }
        if (
          generation !== workbenchGeneration.current ||
          !stateRef.current.workbenchOpen ||
          stateRef.current.workbenchTab !== tab ||
          stateRef.current.selectedThreadId !== threadId
        )
          return;
        const workbenchData = { ...stateRef.current.workbenchData, [tab]: value };
        commit({ type: "patch", value: { workbenchData, workbenchLoading: false } });
      } catch (error) {
        if (
          generation === workbenchGeneration.current &&
          stateRef.current.workbenchOpen &&
          stateRef.current.workbenchTab === tab &&
          stateRef.current.selectedThreadId === threadId
        ) {
          commit({
            type: "patch",
            value: {
              workbenchLoading: false,
              notice: { tone: "error", text: `能力面板读取失败：${errorMessage(error)}` },
            },
          });
        }
      }
    },
    [commit],
  );

  /** 把附件阶段变更写回指定会话，确保切换会话后的迟到响应不污染当前草稿。 */
  const patchAttachmentDraft = useCallback(
    (threadId: string, operationKey: string, patch: Partial<AttachmentDraft>) => {
      const session = getSession(stateRef.current, threadId);
      const attachmentDrafts = session.attachmentDrafts.map((draft) =>
        draft.operationKey === operationKey ? { ...draft, ...patch } : draft,
      );
      commit({ type: "session-patch", threadId, value: { attachmentDrafts } });
    },
    [commit],
  );

  /** 同一操作键重放同一 File 可找回已成功但响应丢失的上传结果。 */
  const uploadDraftFile = useCallback(
    async (
      threadId: string,
      draft: AttachmentDraft,
      file: File,
    ): Promise<AttachmentMetadata | undefined> => {
      const requestKey = `${threadId}:${draft.operationKey}`;
      attachmentUploads.current.get(requestKey)?.abort();
      const controller = new AbortController();
      attachmentUploads.current.set(requestKey, controller);
      patchAttachmentDraft(threadId, draft.operationKey, { status: "uploading", error: undefined });
      try {
        await savePendingUpload(draft.operationKey, file);
        const attachment = await uploadAttachment(
          threadId,
          draft.operationKey,
          file,
          controller.signal,
        );
        const stillSelected = getSession(stateRef.current, threadId).attachmentDrafts.find(
          (item) => item.operationKey === draft.operationKey,
        );
        if (!stillSelected) {
          await discardAttachmentIfPresent(threadId, attachment.id);
          await deletePendingUpload(draft.operationKey).catch(() => undefined);
          return attachment;
        }
        if (stillSelected.removeRequested) {
          patchAttachmentDraft(threadId, draft.operationKey, {
            status: "unknown",
            attachment,
            error: "文件已上传，正在确认移除。",
          });
          await discardAttachmentIfPresent(threadId, attachment.id);
          await deletePendingUpload(draft.operationKey).catch(() => undefined);
          const attachmentDrafts = getSession(stateRef.current, threadId).attachmentDrafts.filter(
            (item) => item.operationKey !== draft.operationKey,
          );
          commit({ type: "session-patch", threadId, value: { attachmentDrafts } });
          return attachment;
        }
        if (stillSelected.cancelRequested) {
          patchAttachmentDraft(threadId, draft.operationKey, {
            status: "unknown",
            attachment,
            error: "上传已完成，正在确认取消。",
          });
          await discardAttachmentIfPresent(threadId, attachment.id);
          await deletePendingUpload(draft.operationKey).catch(() => undefined);
          patchAttachmentDraft(threadId, draft.operationKey, {
            status: "error",
            attachment: undefined,
            error: "上传已取消。",
            cancelRequested: false,
          });
          return undefined;
        }
        await deletePendingUpload(draft.operationKey).catch(() => undefined);
        patchAttachmentDraft(threadId, draft.operationKey, {
          status: "uploaded",
          attachment,
          error: undefined,
        });
        return attachment;
      } catch (error) {
        const latest = getSession(stateRef.current, threadId).attachmentDrafts.find(
          (item) => item.operationKey === draft.operationKey,
        );
        if (error instanceof ChatHttpError && error.status === 410 && latest?.removeRequested) {
          await deletePendingUpload(draft.operationKey).catch(() => undefined);
          const attachmentDrafts = getSession(stateRef.current, threadId).attachmentDrafts.filter(
            (item) => item.operationKey !== draft.operationKey,
          );
          commit({ type: "session-patch", threadId, value: { attachmentDrafts } });
          return undefined;
        }
        if (error instanceof ChatHttpError && error.status === 410 && latest?.cancelRequested) {
          await deletePendingUpload(draft.operationKey).catch(() => undefined);
          patchAttachmentDraft(threadId, draft.operationKey, {
            status: "error",
            attachment: undefined,
            error: "上传已取消或已过期。",
            cancelRequested: false,
          });
          return undefined;
        }
        if (controller.signal.aborted) {
          patchAttachmentDraft(threadId, draft.operationKey, {
            status: "unknown",
            error: "上传已中止，服务器结果待核实。",
          });
          return undefined;
        }
        const knownRejection =
          error instanceof ChatHttpError &&
          error.status >= 400 &&
          error.status < 500 &&
          error.status !== 408 &&
          error.status !== 429;
        patchAttachmentDraft(threadId, draft.operationKey, {
          status: knownRejection ? "error" : "unknown",
          error: attachmentErrorMessage(error),
        });
        return undefined;
      } finally {
        if (attachmentUploads.current.get(requestKey) === controller)
          attachmentUploads.current.delete(requestKey);
      }
    },
    [commit, patchAttachmentDraft],
  );

  /** 校验浏览器可见的模型格式和大小，再持久保存操作键并开始单文件上传。 */
  const addAttachments = useCallback(
    async (threadId: string, files: File[]) => {
      const session = getSession(stateRef.current, threadId);
      const capabilities = stateRef.current.attachmentCapabilities;
      let totalBytes = session.attachmentDrafts
        .filter((item) => item.status !== "error")
        .reduce((sum, item) => sum + item.sizeBytes, 0);
      let count = session.attachmentDrafts.filter((item) => item.status !== "error").length;
      for (const file of files) {
        const mediaType = attachmentMediaType(file);
        const operationKey = `upload_${randomId()}`;
        const draft: AttachmentDraft = {
          operationKey,
          fileName: file.name,
          mediaType: mediaType ?? file.type ?? "application/octet-stream",
          sizeBytes: file.size,
          status: "uploading",
        };
        let reason: string | undefined;
        if (!mediaType) reason = "支持 PNG、JPEG、GIF、WebP 图片和 PDF。";
        else if (capabilities && !capabilities.effectiveFormats.includes(mediaType))
          reason = `当前模型不支持 ${mediaType} 输入。`;
        else if (capabilities && file.size > capabilities.maxSingleBytes)
          reason = `文件超过当前模型单文件上限 ${formatBytes(capabilities.maxSingleBytes)}。`;
        else if (capabilities && totalBytes + file.size > capabilities.maxTotalBytes)
          reason = `文件超过当前模型单次请求总量上限 ${formatBytes(capabilities.maxTotalBytes)}。`;
        else if (capabilities && count + 1 > capabilities.maxAttachments)
          reason = `当前模型最多接收 ${capabilities.maxAttachments} 个附件。`;
        commit({
          type: "session-patch",
          threadId,
          value: {
            attachmentDrafts: [...getSession(stateRef.current, threadId).attachmentDrafts, draft],
          },
        });
        if (reason) {
          patchAttachmentDraft(threadId, operationKey, { status: "error", error: reason });
          continue;
        }
        totalBytes += file.size;
        count += 1;
        try {
          await savePendingUpload(operationKey, file);
        } catch (error) {
          patchAttachmentDraft(threadId, operationKey, {
            status: "error",
            error: errorMessage(error),
          });
          continue;
        }
        void uploadDraftFile(threadId, draft, file);
      }
    },
    [commit, patchAttachmentDraft, uploadDraftFile],
  );

  /** 未知上传通过相同操作键和浏览器保存的原文件核对；缺少原文件时保持未知状态。 */
  const retryAttachment = useCallback(
    async (threadId: string, operationKey: string, selectedFile?: File) => {
      const draft = getSession(stateRef.current, threadId).attachmentDrafts.find(
        (item) => item.operationKey === operationKey,
      );
      if (!draft) return;
      try {
        const file = selectedFile ?? (await readPendingUpload(operationKey));
        if (!file) {
          patchAttachmentDraft(threadId, operationKey, {
            status: "unknown",
            error: "本机没有待重试文件，请先移除该项并重新选择原文件。",
          });
          return;
        }
        if (file.name !== draft.fileName || file.size !== draft.sizeBytes) {
          patchAttachmentDraft(threadId, operationKey, {
            status: "unknown",
            error: "所选文件名称或大小与原上传不同，未重用原操作键。",
          });
          return;
        }
        const capabilities = stateRef.current.attachmentCapabilities;
        const mediaType = attachmentMediaType(file);
        const otherDrafts = getSession(stateRef.current, threadId).attachmentDrafts.filter(
          (item) => item.operationKey !== operationKey,
        );
        const totalBytes = otherDrafts.reduce((sum, item) => sum + item.sizeBytes, file.size);
        const reason = !mediaType
          ? "支持 PNG、JPEG、GIF、WebP 图片和 PDF。"
          : capabilities && !capabilities.effectiveFormats.includes(mediaType)
            ? `当前模型不支持 ${mediaType} 输入。`
            : capabilities && file.size > capabilities.maxSingleBytes
              ? `文件超过当前模型单文件上限 ${formatBytes(capabilities.maxSingleBytes)}。`
              : capabilities && totalBytes > capabilities.maxTotalBytes
                ? `文件超过当前模型单次请求总量上限 ${formatBytes(capabilities.maxTotalBytes)}。`
                : capabilities && otherDrafts.length + 1 > capabilities.maxAttachments
                  ? `当前模型最多接收 ${capabilities.maxAttachments} 个附件。`
                  : undefined;
        if (reason) {
          patchAttachmentDraft(threadId, operationKey, { status: "error", error: reason });
          return;
        }
        await savePendingUpload(operationKey, file);
        await uploadDraftFile(threadId, draft, file);
      } catch (error) {
        patchAttachmentDraft(threadId, operationKey, {
          status: "unknown",
          error: attachmentErrorMessage(error),
        });
      }
    },
    [patchAttachmentDraft, uploadDraftFile],
  );

  /** 只丢弃服务端确认未引用的上传；结果未知时先以原文件核对，避免留下孤立正文。 */
  const removeAttachment = useCallback(
    async (threadId: string, operationKey: string) => {
      const draft = getSession(stateRef.current, threadId).attachmentDrafts.find(
        (item) => item.operationKey === operationKey,
      );
      if (!draft) return;
      if (draft.status === "uploading") {
        attachmentUploads.current.get(`${threadId}:${operationKey}`)?.abort();
        patchAttachmentDraft(threadId, operationKey, {
          status: "unknown",
          cancelRequested: true,
          removeRequested: true,
          error: "上传已中止，正在核实后移除。",
        });
        return;
      }
      let attachment = draft.attachment;
      if (!attachment && draft.status === "unknown") {
        const file = await readPendingUpload(operationKey).catch(() => undefined);
        if (!file) {
          patchAttachmentDraft(threadId, operationKey, {
            error: "上传结果未知；请重选原文件核实后移除。",
            cancelRequested: true,
            removeRequested: true,
          });
          return;
        }
        patchAttachmentDraft(threadId, operationKey, {
          cancelRequested: true,
          removeRequested: true,
        });
        attachment = await uploadDraftFile(threadId, draft, file);
        if (!attachment) return;
      }
      try {
        if (attachment) await discardAttachmentIfPresent(threadId, attachment.id);
        await deletePendingUpload(operationKey).catch(() => undefined);
        const attachmentDrafts = getSession(stateRef.current, threadId).attachmentDrafts.filter(
          (item) => item.operationKey !== operationKey,
        );
        commit({ type: "session-patch", threadId, value: { attachmentDrafts } });
      } catch (error) {
        patchAttachmentDraft(threadId, operationKey, { error: `移除失败：${errorMessage(error)}` });
      }
    },
    [commit, patchAttachmentDraft, uploadDraftFile],
  );

  /** 取消只停止本地等待；上传是否已在服务端完成必须由幂等重试确认。 */
  const cancelAttachmentUpload = useCallback(
    (threadId: string, operationKey: string) => {
      patchAttachmentDraft(threadId, operationKey, {
        cancelRequested: true,
        error: "正在取消上传；服务器结果将继续核实。",
      });
      attachmentUploads.current.get(`${threadId}:${operationKey}`)?.abort();
    },
    [patchAttachmentDraft],
  );

  /** 响应结果未知时复用同一持久标识，避免重试新增重复的队列项。 */
  const enqueue = useCallback(
    async (threadId: string, turn: ChatTurn, message: MessageContent, kind: string) => {
      const skillIds =
        kind === "MESSAGE" ? [] : [...getSession(stateRef.current, threadId).selectedSkills];
      const text = message.text.trim();
      const attachmentDrafts = getSession(stateRef.current, threadId).attachmentDrafts;
      const attachments = attachmentDrafts.flatMap((draft) =>
        draft.status === "uploaded" &&
        draft.attachment &&
        message.attachmentIds.includes(draft.attachment.id)
          ? [draft.attachment]
          : [],
      );
      const attachmentIds = attachments.map((item) => item.id);
      const sameSubmission = (pending: PendingQueueSubmission) =>
        pending.threadId === threadId &&
        pending.turnId === turn.turnId &&
        pending.kind === kind &&
        pending.text === text &&
        (pending.skillIds ?? []).join("\0") === skillIds.join("\0") &&
        (pending.attachmentIds ?? []).join("\0") === attachmentIds.join("\0");
      const existing = Object.values(stateRef.current.pendingQueue).find(sameSubmission);
      const pending: PendingQueueSubmission = existing ?? {
        threadId,
        turnId: turn.turnId,
        operationKey: `enqueue_${randomId()}`,
        inputId: `input_${randomId()}`,
        kind,
        text,
        skillIds,
        attachmentIds,
        attachments,
      };
      const inFlight = enqueueRequests.current.get(pending.operationKey);
      if (inFlight) return inFlight;
      if (!existing) {
        const pendingQueue = { ...stateRef.current.pendingQueue, [pending.operationKey]: pending };
        commit({ type: "patch", value: { pendingQueue } });
        persist({ pendingQueue });
      }
      const request = (async () => {
        try {
          await postChat("queue/mutate", {
            threadId,
            turnId: turn.turnId,
            action: "ENQUEUE",
            inputId: pending.inputId,
            operationKey: pending.operationKey,
            kind: pending.kind,
            content: {
              text: pending.text,
              skillIds: pending.skillIds,
              attachmentIds: pending.attachmentIds,
            },
          });
          const confirmedQueue = { ...stateRef.current.pendingQueue };
          delete confirmedQueue[pending.operationKey];
          commit({ type: "patch", value: { pendingQueue: confirmedQueue } });
          persist({ pendingQueue: confirmedQueue });
          if (getSession(stateRef.current, threadId).draft === text) {
            const session = getSession(stateRef.current, threadId);
            const consumed = new Set(pending.attachmentIds);
            const remaining = session.attachmentDrafts.filter(
              (draft) => !draft.attachment || !consumed.has(draft.attachment.id),
            );
            commit({
              type: "session-patch",
              threadId,
              value: { draft: "", attachmentDrafts: remaining },
            });
            await Promise.all(
              pending.attachments.map((attachment) => {
                const draft = attachmentDrafts.find(
                  (item) => item.attachment?.id === attachment.id,
                );
                return draft
                  ? deletePendingUpload(draft.operationKey).catch(() => undefined)
                  : Promise.resolve();
              }),
            );
          }
          commit({
            type: "patch",
            value: { notice: { tone: "success", text: "消息已进入队列。" } },
          });
          if (stateRef.current.workbenchOpen && stateRef.current.workbenchTab === "queue")
            void loadWorkbench("queue", threadId);
        } catch (error) {
          const rejected =
            error instanceof ChatHttpError &&
            error.status >= 400 &&
            error.status < 500 &&
            error.status !== 408 &&
            error.status !== 429;
          if (rejected) {
            const confirmedQueue = { ...stateRef.current.pendingQueue };
            delete confirmedQueue[pending.operationKey];
            commit({ type: "patch", value: { pendingQueue: confirmedQueue } });
            persist({ pendingQueue: confirmedQueue });
          }
          const detail = attachmentErrorMessage(error);
          commit({
            type: "patch",
            value: {
              notice: {
                tone: "error",
                text: `排队${rejected ? "未接受" : "结果尚未确认"}：${detail}${detail.endsWith("。") ? "" : "。"}重试原文会沿用原操作身份。`,
              },
            },
          });
        }
      })();
      enqueueRequests.current.set(pending.operationKey, request);
      try {
        await request;
      } finally {
        enqueueRequests.current.delete(pending.operationKey);
      }
    },
    [commit, loadWorkbench, persist],
  );

  /** 发送规范化文本前保留原编辑草稿，使确定性服务端拒绝可以完整还原输入。 */
  const submit = useCallback(
    (submitted: MessageContent, kind = "STEERING") => {
      const threadId = stateRef.current.selectedThreadId;
      if (!threadId) return;
      const text = submitted.text.trim();
      const session = getSession(stateRef.current, threadId);
      const drafts = session.attachmentDrafts;
      if (drafts.some((draft) => draft.status !== "uploaded" || !draft.attachment)) {
        commit({
          type: "patch",
          value: {
            notice: { tone: "info", text: "请先完成或重试附件上传，文件选择会保留在当前会话。" },
          },
        });
        return;
      }
      const attachments = drafts.flatMap((draft) => (draft.attachment ? [draft.attachment] : []));
      const capabilities = stateRef.current.attachmentCapabilities;
      const totalBytes = attachments.reduce((sum, attachment) => sum + attachment.sizeBytes, 0);
      const unsupported = attachments.find(
        (attachment) =>
          capabilities && !capabilities.effectiveFormats.includes(attachment.mediaType),
      );
      if (
        unsupported ||
        (capabilities &&
          (attachments.length > capabilities.maxAttachments ||
            totalBytes > capabilities.maxTotalBytes ||
            attachments.some((attachment) => attachment.sizeBytes > capabilities.maxSingleBytes)))
      ) {
        commit({
          type: "patch",
          value: {
            notice: {
              tone: "error",
              text: unsupported
                ? `当前模型不支持 ${unsupported.mediaType} 输入；附件和文字仍保留。`
                : "附件超出当前模型输入上限；附件和文字仍保留。",
            },
          },
        });
        return;
      }
      const skillIds = kind === "MESSAGE" ? [] : [...session.selectedSkills];
      const content: MessageContent = {
        text,
        skillIds,
        attachmentIds: attachments.map((attachment) => attachment.id),
      };
      if (!content.text && !content.attachmentIds.length) return;
      const active = session.turns.at(-1);
      if (active && (active.state === "UNKNOWN" || active.state === "HISTORY")) {
        commit({
          type: "patch",
          value: { notice: { tone: "error", text: "请先检查上一条提交的状态，再继续发送。" } },
        });
        return;
      }
      if (active && !isTerminal(active.state)) {
        void enqueue(threadId, active, content, kind);
        return;
      }
      const turnId = `turn_${randomId()}`;
      const operationKey = `submit_${randomId()}`;
      const pending: PendingSubmission = {
        threadId,
        turnId,
        operationKey,
        text,
        draftText: submitted.text,
        skillIds,
        attachmentIds: content.attachmentIds,
        attachments,
      };
      const turn = { ...emptyTurn(turnId, text, pending.skillIds, operationKey), attachments };
      commit({ type: "session-turn", threadId, turn });
      commit({ type: "session-patch", threadId, value: { draft: "", attachmentDrafts: [] } });
      void Promise.all(
        drafts.map((draft) => deletePendingUpload(draft.operationKey).catch(() => undefined)),
      );
      const pendingByThread = { ...stateRef.current.pending, [threadId]: pending };
      commit({ type: "patch", value: { pending: pendingByThread, notice: undefined } });
      persist({ pending: pendingByThread });
      void observeTurn(threadId, turn, true);
    },
    [commit, enqueue, observeTurn, persist],
  );

  /**
   * @author kongweiguang
   * 202 只表示恢复受理；仅本次显式恢复可跨越旧暂停快照继续核对事件，且旧异步回调不得越过代际边界。
   */
  const observeTurnImpl: ObserveTurn = async (
    threadId,
    initialTurn,
    submitFirst,
    resumeAccepted = false,
  ) => {
    activeStream.current?.abort.abort();
    activeStream.current = null;
    const generation = ++streamGeneration.current;
    let turn = initialTurn;
    let resumeTransitionPending = resumeAccepted;
    if (turn.resumeRequired || turn.error || hasPendingWait(turn) || isTerminal(turn.state)) {
      commit({ type: "session-patch", threadId, value: { observing: false } });
      return;
    }
    let action: "stream" | "reconnect" = submitFirst ? "stream" : "reconnect";
    let attempt = 0;
    commit({
      type: "session-patch",
      threadId,
      value: { observing: true, connectError: undefined },
    });
    try {
      while (generation === streamGeneration.current) {
        const abort = new AbortController();
        activeStream.current = { threadId, abort };
        try {
          const payload =
            action === "stream"
              ? {
                  threadId,
                  turnId: turn.turnId,
                  operationKey: turn.operationKey,
                  content: {
                    text: turn.userText,
                    skillIds: turn.skillIds,
                    attachmentIds: turn.attachments.map((attachment) => attachment.id),
                  },
                }
              : { threadId, turnId: turn.turnId, afterSequence: turn.cursor };
          await observeChat(action, payload, abort.signal, async (frame) => {
            if (generation !== streamGeneration.current) return;
            if (frame.event === "accepted") {
              clearPending(threadId);
              if (turn.state === "UNKNOWN" || turn.state === "QUEUED")
                turn = patchTurn(threadId, turn.turnId, {
                  state: "RUNNING",
                  resumeRequired: false,
                });
              return;
            }
            if (frame.event === "recovery-required") {
              turn = patchTurn(threadId, turn.turnId, { resumeRequired: true });
              await refreshTurnStatus(threadId, turn.turnId);
              await refreshRecoveries(threadId, turn.turnId);
              turn =
                getSession(stateRef.current, threadId).turns.find(
                  (item) => item.turnId === turn.turnId,
                ) ?? turn;
              abort.abort();
              return;
            }
            if (frame.event === "reset") {
              const data = parseData(frame.data) as { modelRequestId?: string };
              const drafts = { ...turn.drafts };
              if (data.modelRequestId) delete drafts[data.modelRequestId];
              turn = patchTurn(threadId, turn.turnId, { drafts });
              return;
            }
            if (frame.event === "resync") {
              turn = patchTurn(threadId, turn.turnId, { drafts: {} });
              void loadHistory(threadId);
              return;
            }
            if (frame.event === "text-delta" || frame.event === "reasoning-delta") {
              const data = parseData(frame.data) as { modelRequestId?: string; text?: string };
              if (!data.modelRequestId || isTerminal(turn.state)) return;
              const drafts = {
                ...turn.drafts,
                [data.modelRequestId]: {
                  ...(turn.drafts[data.modelRequestId] ?? { text: "", reasoning: "" }),
                  [frame.event === "text-delta" ? "text" : "reasoning"]:
                    `${turn.drafts[data.modelRequestId]?.[frame.event === "text-delta" ? "text" : "reasoning"] ?? ""}${data.text ?? ""}`,
                },
              };
              turn = patchTurn(threadId, turn.turnId, { drafts });
              return;
            }
            if (frame.event === "agent-event") {
              const event = parseData(frame.data) as {
                sequence: number;
                eventType: string;
                payloadJson: string;
              };
              const applied = await applyDurable(
                threadId,
                turn,
                event,
                () => generation === streamGeneration.current && !abort.signal.aborted,
              );
              if (!applied) {
                abort.abort();
                return;
              }
              turn = applied;
              if (hasPendingWait(turn)) abort.abort();
            }
          });
          if (generation !== streamGeneration.current || abort.signal.aborted) return;
          turn =
            getSession(stateRef.current, threadId).turns.find(
              (item) => item.turnId === turn.turnId,
            ) ?? turn;
          if (turn.state !== "SUSPENDED") resumeTransitionPending = false;
          if (isTerminal(turn.state) || turn.resumeRequired || turn.error || hasPendingWait(turn))
            break;
          if (turn.state === "SUSPENDED") {
            const recoveries = await refreshRecoveries(threadId, turn.turnId);
            turn =
              getSession(stateRef.current, threadId).turns.find(
                (item) => item.turnId === turn.turnId,
              ) ?? turn;
            if (
              !resumeTransitionPending ||
              !canContinueAcceptedResume(turn, recoveries, initialTurn.cursor)
            )
              break;
          }
          action = "reconnect";
          const status = await refreshTurnStatus(threadId, turn.turnId);
          turn =
            getSession(stateRef.current, threadId).turns.find(
              (item) => item.turnId === turn.turnId,
            ) ?? turn;
          if (turn.state !== "SUSPENDED") resumeTransitionPending = false;
          if (isTerminal(turn.state)) break;
          if (turn.state === "SUSPENDED") {
            const recoveries = await refreshRecoveries(threadId, turn.turnId);
            turn =
              getSession(stateRef.current, threadId).turns.find(
                (item) => item.turnId === turn.turnId,
              ) ?? turn;
            if (
              !resumeTransitionPending ||
              !canContinueAcceptedResume(turn, recoveries, initialTurn.cursor)
            )
              break;
          } else if (status?.resumeRequired || turn.resumeRequired) {
            break;
          }
        } catch (error) {
          if (abort.signal.aborted || generation !== streamGeneration.current) return;
          action = "reconnect";
          if (submitFirst && error instanceof ChatHttpError && error.code === "REQUEST_TOO_LARGE") {
            const currentPending = stateRef.current.pending[threadId];
            if (currentPending?.turnId === turn.turnId) {
              const session = getSession(stateRef.current, threadId);
              commit({
                type: "session-patch",
                threadId,
                value: {
                  turns: session.turns.filter((item) => item.turnId !== turn.turnId),
                  draft: currentPending.draftText ?? currentPending.text,
                  attachmentDrafts: turn.attachments.map((attachment, index) => ({
                    operationKey: `restore_${turn.turnId}_${index}`,
                    fileName: attachment.fileName,
                    mediaType: attachment.mediaType,
                    sizeBytes: attachment.sizeBytes,
                    status: "uploaded",
                    attachment,
                  })),
                  connectError: undefined,
                },
              });
              clearPending(threadId);
              commit({
                type: "patch",
                value: { notice: { tone: "error", text: attachmentErrorMessage(error) } },
              });
            }
            break;
          }
          const status = await refreshTurnStatus(threadId, turn.turnId);
          if (!status && submitFirst) {
            turn = patchTurn(threadId, turn.turnId, {
              state: "UNKNOWN",
              error: errorMessage(error),
            });
            commit({
              type: "session-patch",
              threadId,
              value: {
                connectError:
                  "提交结果尚未确认。请先重查状态，确认服务端未受理后再使用相同身份重试。",
              },
            });
            break;
          }
          turn =
            getSession(stateRef.current, threadId).turns.find(
              (item) => item.turnId === turn.turnId,
            ) ?? turn;
          if (turn.state !== "SUSPENDED") resumeTransitionPending = false;
          if (isTerminal(turn.state) || turn.error || hasPendingWait(turn)) break;
          if (turn.state === "SUSPENDED") {
            const recoveries = await refreshRecoveries(threadId, turn.turnId);
            turn =
              getSession(stateRef.current, threadId).turns.find(
                (item) => item.turnId === turn.turnId,
              ) ?? turn;
            if (
              !resumeTransitionPending ||
              !canContinueAcceptedResume(turn, recoveries, initialTurn.cursor)
            )
              break;
          }
          if (status?.resumeRequired || turn.resumeRequired || turn.error || hasPendingWait(turn))
            break;
          commit({ type: "session-patch", threadId, value: { connectError: errorMessage(error) } });
        }
        const delayMs = reconnectDelay[Math.min(attempt, reconnectDelay.length - 1)] ?? 15_000;
        await delay(delayMs, generation, streamGeneration, abort.signal);
        attempt += 1;
      }
    } finally {
      if (generation === streamGeneration.current && activeStream.current?.threadId === threadId) {
        activeStream.current = null;
        if (stateRef.current.sessions[threadId])
          commit({ type: "session-patch", threadId, value: { observing: false } });
      }
    }
  };

  /**
   * @author kongweiguang
   * 仅消息正文、工具结果和 wait 在投影前异步回读；返回后核对流代际与同 Thread/Turn，并以最新快照重算该事件。
   * 若最新游标已到达事件序号，权威事件已被消费，不能再次投影；recoveries 只在提交后补充状态。
   */
  const applyDurable = useCallback(
    async (
      threadId: string,
      current: ChatTurn,
      event: { sequence: number; eventType: string; payloadJson: string },
      isCurrentStream: () => boolean,
    ): Promise<ChatTurn | undefined> => {
      if (!isCurrentStream()) return undefined;
      if (!Number.isFinite(event.sequence) || event.sequence <= current.cursor) return current;
      const data = JSON.parse(event.payloadJson) as Record<string, unknown>;
      let next: ChatTurn = { ...current, events: [...current.events, event].slice(-500) };
      let shouldRefreshRecoveries = false;
      const add = (
        kind: ChatTurn["items"][number]["kind"],
        text: string,
        key: string,
        extra: Partial<ChatTurn["items"][number]> = {},
      ) => {
        if (!text && kind !== "tool") return;
        if (
          next.items.some(
            (item) =>
              item.key === key ||
              (extra.recordId && item.kind === kind && item.recordId === extra.recordId),
          )
        )
          return;
        next = { ...next, items: [...next.items, { key, kind, text, ...extra }] };
      };
      switch (event.eventType) {
        case "agent.execution.state":
          next = {
            ...next,
            state: asTurnState(String(data["to"] ?? "UNKNOWN")),
            resumeRequired: false,
          };
          break;
        case "agent.model.completed": {
          const messageRecordId = String(data["messageRecordId"] ?? "");
          const message = await postChat<{
            modelRequestId?: string;
            text: string;
            reasoning?: string;
            calls: Array<{ callId: string; name: string }>;
          }>("message", {
            threadId,
            turnId: current.turnId,
            recordId: data["messageRecordId"],
            usageRecordId: data["usageRecordId"],
          });
          if (!isCurrentStream()) return undefined;
          const latest = stateRef.current.sessions[threadId]?.turns.find(
            (turn) => turn.turnId === current.turnId,
          );
          if (!latest || latest.cursor >= event.sequence) return latest;
          next = {
            ...latest,
            events: latest.events.some((item) => item.sequence === event.sequence)
              ? latest.events
              : [...latest.events, event].slice(-500),
          };
          const modelRequestId = message.modelRequestId;
          const drafts = { ...next.drafts };
          if (modelRequestId) delete drafts[modelRequestId];
          next = {
            ...next,
            drafts,
            usageRecordIds: data["usageRecordId"]
              ? [...new Set([...next.usageRecordIds, String(data["usageRecordId"])])]
              : next.usageRecordIds,
          };
          if (message.reasoning)
            add("reasoning", message.reasoning, `record:${messageRecordId}:reasoning`, {
              recordId: messageRecordId,
            });
          if (message.text)
            add("assistant", message.text, `record:${messageRecordId}:assistant`, {
              recordId: messageRecordId,
            });
          for (const call of message.calls ?? []) {
            if (!next.items.some((item) => item.callId === call.callId && item.kind === "tool"))
              add("tool", "", `tool:${call.callId}`, {
                callId: call.callId,
                name: call.name,
                state: "PENDING",
              });
          }
          break;
        }
        case "agent.tool.started":
          next = updateTool(next, String(data["callId"] ?? ""), { state: "RUNNING" });
          break;
        case "agent.tool.completed": {
          const result = await postChat<{ text: string }>("message", {
            threadId,
            turnId: current.turnId,
            recordId: data["resultRecordId"],
          });
          if (!isCurrentStream()) return undefined;
          const latest = stateRef.current.sessions[threadId]?.turns.find(
            (turn) => turn.turnId === current.turnId,
          );
          if (!latest || latest.cursor >= event.sequence) return latest;
          next = {
            ...latest,
            events: latest.events.some((item) => item.sequence === event.sequence)
              ? latest.events
              : [...latest.events, event].slice(-500),
          };
          next = updateTool(next, String(data["callId"] ?? ""), {
            state: String(data["state"] ?? "COMPLETED"),
          });
          const resultRecordId = String(data["resultRecordId"] ?? "");
          add("tool-result", result.text ?? "", `record:${resultRecordId}:tool-result`, {
            callId: String(data["callId"] ?? ""),
            state: String(data["state"] ?? "COMPLETED"),
            recordId: resultRecordId,
          });
          shouldRefreshRecoveries = true;
          break;
        }
        case "agent.wait.requested": {
          const wait = await postChat<WaitView>("wait", {
            threadId,
            turnId: current.turnId,
            recordId: data["requestRecordId"],
          });
          if (!isCurrentStream()) return undefined;
          const waitState = wait.kind === "approval" ? "WAITING_APPROVAL" : "WAITING_INPUT";
          const latest = stateRef.current.sessions[threadId]?.turns.find(
            (turn) => turn.turnId === current.turnId,
          );
          if (!latest) return undefined;
          const waitRecordId = String(data["requestRecordId"] ?? "");
          if (latest.cursor >= event.sequence) return latest;
          const base = latest;
          const eventIsCurrent = base.cursor <= event.sequence;
          next = {
            ...base,
            events: base.events.some((item) => item.sequence === event.sequence)
              ? base.events
              : [...base.events, event].slice(-500),
            wait,
            waitRecordId,
            waitAnswered: eventIsCurrent
              ? wait.status !== "PENDING"
              : (base.waitAnswered ?? wait.status !== "PENDING"),
            state: eventIsCurrent && wait.status === "PENDING" ? waitState : base.state,
            cursor: Math.max(base.cursor, event.sequence),
          };
          break;
        }
        case "agent.execution.control":
          if (data["action"] === "WAIT_RESOLVED")
            next = { ...next, waitAnswered: true, state: "SUSPENDED", resumeRequired: false };
          break;
        case "agent.execution.terminal": {
          const summary = typeof data["summary"] === "string" ? data["summary"] : "";
          let items = next.items;
          const last = items.at(-1);
          if (last?.kind === "assistant" && last.text === summary) items = items.slice(0, -1);
          next = {
            ...next,
            items,
            state: asTurnState(String(data["state"] ?? "UNKNOWN")),
            resumeRequired: false,
            answer: summary || undefined,
            error: typeof data["errorMessage"] === "string" ? data["errorMessage"] : undefined,
            drafts: {},
            wait: next.wait ? { ...next.wait, status: "CLOSED" } : undefined,
            cancelRequested: false,
          };
          break;
        }
        case "agent.model.retry":
          commit({
            type: "patch",
            value: { notice: { tone: "info", text: "模型连接中断，正在重试。" } },
          });
          break;
      }
      if (!isCurrentStream()) return undefined;
      next = { ...next, cursor: event.sequence };
      const latest = stateRef.current.sessions[threadId]?.turns.find(
        (turn) => turn.turnId === current.turnId,
      );
      if (!latest) return undefined;
      if (latest.cursor >= event.sequence) return latest;
      next = { ...next, cursor: event.sequence };
      commit({ type: "session-turn", threadId, turn: next });
      if (shouldRefreshRecoveries) {
        await refreshRecoveries(threadId, current.turnId);
        if (!isCurrentStream()) return undefined;
        return stateRef.current.sessions[threadId]?.turns.find(
          (item) => item.turnId === current.turnId,
        );
      }
      return next;
    },
    [commit, refreshRecoveries],
  );

  /** 用户明确恢复原 Turn；异步 202 后的旧 SUSPENDED 快照只在本次恢复中继续观察。 */
  const resumeTurn = useCallback(
    async (threadId: string, turn: ChatTurn) => {
      let resumeError: unknown;
      try {
        await postChat("resume", { threadId, turnId: turn.turnId });
      } catch (error) {
        resumeError = error;
      }
      const status = await refreshTurnStatus(threadId, turn.turnId);
      if (!status) {
        throw resumeError ?? new Error("继续请求已提交，但执行状态暂不可用；请稍后重查。");
      }
      if (status.resumeRequired) {
        throw resumeError ?? new Error("执行状态仍待核实；请稍后再次继续，以核实保存结果。");
      }
      if (resumeError && status.state === "SUSPENDED") throw resumeError;
      const resumed = patchTurn(threadId, turn.turnId, {
        state: asTurnState(status.state),
        resumeRequired: false,
        waitAnswered: turn.wait?.status === "PENDING" ? turn.waitAnswered : true,
      });
      if (resumed.state === "SUSPENDED") {
        const recoveries = await refreshRecoveries(threadId, turn.turnId);
        const current =
          getSession(stateRef.current, threadId).turns.find(
            (item) => item.turnId === turn.turnId,
          ) ?? resumed;
        if (canContinueAcceptedResume(current, recoveries, turn.cursor) && !hasPendingWait(current))
          void observeTurn(threadId, current, false, true);
        return;
      }
      if (!isTerminal(resumed.state)) {
        await refreshRecoveries(threadId, turn.turnId);
        const current =
          getSession(stateRef.current, threadId).turns.find(
            (item) => item.turnId === turn.turnId,
          ) ?? resumed;
        if (!current.resumeRequired && current.state !== "SUSPENDED")
          void observeTurn(threadId, current, false);
      }
    },
    [observeTurn, patchTurn, refreshRecoveries, refreshTurnStatus],
  );

  /** 查询 Core 最新 revision 后显式提交用户裁决，并以原操作键处理丢失响应。 */
  const resolveRecoveryTurn = useCallback(
    async (
      threadId: string,
      turn: ChatTurn,
      recovery: RecoveryView,
      decision: "RETRY" | "SKIP",
    ) => {
      const operationId = recoveryOperationId(threadId, turn.turnId, recovery.recoveryId);
      let pending = stateRef.current.pendingRecoveries[operationId];
      const reconcilingPriorDecision = Boolean(pending);
      if (pending && pending.decision !== decision)
        throw new Error("上一项裁决结果尚未确认，请先继续核实原操作。");
      if (!pending) {
        pending = {
          threadId,
          turnId: turn.turnId,
          recoveryId: recovery.recoveryId,
          callId: recovery.callId,
          expectedRecoveryRevision: recovery.revision,
          decision,
          operationKey: `recovery_${randomId()}`,
        };
        const pendingRecoveries = { ...stateRef.current.pendingRecoveries, [operationId]: pending };
        commit({ type: "patch", value: { pendingRecoveries } });
        persist({ pendingRecoveries });
      }
      recoveryRequests.current.delete(`${threadId}:${turn.turnId}`);
      const submitDecision = () =>
        postRecoveryResolution({
          threadId: pending!.threadId,
          turnId: pending!.turnId,
          recoveryId: pending!.recoveryId,
          callId: pending!.callId,
          expectedRecoveryRevision: pending!.expectedRecoveryRevision,
          operationKey: pending!.operationKey,
          decision: pending!.decision,
        });
      try {
        if (reconcilingPriorDecision) {
          const currentRecoveries = await refreshRecoveries(threadId, turn.turnId);
          if (!currentRecoveries) throw new Error("恢复状态查询失败，裁决尚未重试。");
          const currentRecovery = currentRecoveries.find(
            (item) => item.recoveryId === pending!.recoveryId,
          );
          if (!currentRecovery) {
            // 该项已不再待处理，上一请求已生效；继续复用其身份完成 Turn。
          } else if (currentRecovery.revision !== pending.expectedRecoveryRevision) {
            clearPendingRecovery(operationId);
            throw new Error("恢复项版本已变化，请检查最新状态后再作决定。");
          } else {
            await submitDecision();
          }
        } else {
          try {
            await submitDecision();
          } catch (error) {
            const knownRejection =
              error instanceof ChatHttpError &&
              error.status >= 400 &&
              error.status < 500 &&
              error.status !== 408 &&
              error.status !== 429;
            const currentRecoveries = await refreshRecoveries(threadId, turn.turnId);
            if (knownRejection) {
              clearPendingRecovery(operationId);
              throw error;
            }
            if (!currentRecoveries) throw error;
            const currentRecovery = currentRecoveries.find(
              (item) => item.recoveryId === pending!.recoveryId,
            );
            if (
              currentRecovery?.status === "PENDING" &&
              currentRecovery.revision === pending.expectedRecoveryRevision
            ) {
              await submitDecision();
            } else if (currentRecovery) {
              clearPendingRecovery(operationId);
              throw new Error("恢复项版本已变化，请检查最新状态后再作决定。");
            }
          }
        }
        clearPendingRecovery(operationId);
        const currentTurn =
          getSession(stateRef.current, threadId).turns.find(
            (item) => item.turnId === turn.turnId,
          ) ?? turn;
        await resumeTurn(threadId, currentTurn);
      } catch (error) {
        commit({
          type: "patch",
          value: {
            notice: {
              tone: "error",
              text: `恢复操作未完成：${errorMessage(error)}。未确认时可继续使用同一裁决身份核实。`,
            },
          },
        });
        throw error;
      }
    },
    [clearPendingRecovery, commit, persist, refreshRecoveries, resumeTurn],
  );

  /** 先确认答案，再明确恢复执行；恢复失败时，已回答的等待仍可恢复。 */
  const answerAndResume = useCallback(
    async (threadId: string, turn: ChatTurn, answers: InteractionAnswer[]) => {
      if (!turn.wait) return;
      const current =
        getSession(stateRef.current, threadId).turns.find((item) => item.turnId === turn.turnId) ??
        turn;
      if (current.waitAnswered || current.wait?.status !== "PENDING") {
        await resumeTurn(threadId, current);
        return;
      }
      const interactionId = turn.wait.interactionId;
      let resolvedWait = current.wait;
      const operationKey = current.answerOperationKey ?? `answer_${randomId()}`;
      patchTurn(threadId, turn.turnId, { answerOperationKey: operationKey });
      try {
        await postChat("answers", {
          threadId,
          turnId: turn.turnId,
          operationKey,
          interactionId,
          expectedInteractionRevision: turn.wait.interactionRevision,
          answers,
        });
      } catch (error) {
        const refreshed = current.waitRecordId
          ? await postChat<WaitView>("wait", {
              threadId,
              turnId: turn.turnId,
              recordId: current.waitRecordId,
            }).catch(() => undefined)
          : undefined;
        if (!refreshed || refreshed.status === "PENDING") {
          commit({
            type: "patch",
            value: { notice: { tone: "error", text: `答案状态尚未确认：${errorMessage(error)}` } },
          });
          throw error;
        }
        resolvedWait = refreshed;
      }
      const answered = patchTurn(threadId, turn.turnId, {
        waitAnswered: true,
        state: "SUSPENDED",
        wait: { ...resolvedWait!, status: "ANSWERED" },
      });
      try {
        await resumeTurn(threadId, answered);
      } catch (error) {
        commit({
          type: "patch",
          value: {
            notice: { tone: "error", text: `答案已保存，继续执行失败：${errorMessage(error)}` },
          },
        });
        throw error;
      }
    },
    [commit, patchTurn, resumeTurn],
  );

  /** 同一 Turn 的取消串行发送；409 后回读状态，未终结时保留操作键供用户明确重试。 */
  const controlTurn = useCallback(
    async (threadId: string, turn: ChatTurn, control: "pause" | "cancel") => {
      const turnKey = `${threadId}:${turn.turnId}`;
      if (control === "cancel" && cancelInFlight.current.has(turnKey)) return;
      if (control === "cancel") cancelInFlight.current.add(turnKey);
      try {
        const operationId = `${threadId}:${turn.turnId}:${control}`;
        const pending: PendingControl = stateRef.current.pendingControls[operationId] ?? {
          threadId,
          turnId: turn.turnId,
          control,
          operationKey: `${control}_${randomId()}`,
        };
        const pendingControls = { ...stateRef.current.pendingControls, [operationId]: pending };
        commit({ type: "patch", value: { pendingControls } });
        persist({ pendingControls });
        const clearPending = () => {
          const next = { ...stateRef.current.pendingControls };
          delete next[operationId];
          commit({ type: "patch", value: { pendingControls: next } });
          persist({ pendingControls: next });
        };
        if (control === "cancel") patchTurn(threadId, turn.turnId, { cancelRequested: true });
        try {
          await postChat(control, {
            threadId,
            turnId: turn.turnId,
            operationKey: pending.operationKey,
            ...(control === "cancel" ? { reason: "User cancelled." } : {}),
          });
          clearPending();
          if (!activeStream.current || activeStream.current.threadId !== threadId)
            void observeTurn(threadId, turn, false);
        } catch (error) {
          const rejected =
            error instanceof ChatHttpError &&
            error.status >= 400 &&
            error.status < 500 &&
            error.status !== 408 &&
            error.status !== 429;
          if (control === "cancel") {
            const status = await refreshTurnStatus(threadId, turn.turnId);
            patchTurn(threadId, turn.turnId, { cancelRequested: false });
            if (status && isTerminal(status.state)) {
              clearPending();
              commit({
                type: "patch",
                value: {
                  notice: {
                    tone: "info",
                    text:
                      status.state === "CANCELLED"
                        ? "执行已取消，状态已同步。"
                        : "执行已在取消生效前结束，状态已同步。",
                  },
                },
              });
            } else if (error instanceof ChatHttpError && error.status === 409) {
              commit({
                type: "patch",
                value: {
                  notice: {
                    tone: "error",
                    text: status
                      ? "取消请求未被接受，服务端显示执行仍在进行。再次点击可沿用同一操作身份重试。"
                      : "取消状态冲突，服务端状态暂不可用。操作身份已保留，再次点击会沿用同一操作身份。",
                  },
                },
              });
            } else {
              if (rejected) clearPending();
              commit({
                type: "patch",
                value: {
                  notice: {
                    tone: "error",
                    text: `${rejected ? "取消请求未受理" : "取消结果尚未确认"}：${errorMessage(error)}。再次操作会复用原操作身份。`,
                  },
                },
              });
            }
          } else {
            if (rejected) clearPending();
            commit({
              type: "patch",
              value: {
                notice: {
                  tone: "error",
                  text: `暂停请求${rejected ? "未接受" : "结果尚未确认"}：${errorMessage(error)}。再次操作会复用原操作身份。`,
                },
              },
            });
          }
          if (!activeStream.current || activeStream.current.threadId !== threadId)
            void observeTurn(threadId, turn, false);
        }
      } finally {
        if (control === "cancel") cancelInFlight.current.delete(turnKey);
      }
    },
    [commit, observeTurn, patchTurn, persist, refreshTurnStatus],
  );

  /** 将压缩作为独立轮次启动并监听，其事件流仍使用当前对话标识。 */
  const compact = useCallback(
    async (threadId: string, source: ChatTurn) => {
      const accepted = await postChat<{ identity?: { turnId?: string } }>("compact", {
        threadId,
        turnId: source.turnId,
        operationKey: `compact_${randomId()}`,
      });
      const turnId = accepted.identity?.turnId;
      if (!turnId) throw new Error("服务端没有返回压缩执行身份。");
      const turn = emptyTurn(turnId, "压缩上下文", []);
      commit({ type: "session-turn", threadId, turn });
      void observeTurn(threadId, turn, false);
    },
    [commit, observeTurn],
  );

  /** 在初始化副作用或浏览器事件进入恢复流程前，先发布当前回调。 */
  useLayoutEffect(() => {
    loadHistoryImplementation.current = loadHistoryImpl;
    observeTurnImplementation.current = observeTurnImpl;
  });

  /**
   * @author kongweiguang
   * 组件卸载拥有当前 SSE 与其退避循环；仅存在活动流时推进代际，避免 StrictMode 初次 effect 回放打断异步 bootstrap。
   */
  useEffect(
    () => () => {
      const stream = activeStream.current;
      if (!stream) return;
      stream.abort.abort();
      activeStream.current = null;
      streamGeneration.current += 1;
    },
    [],
  );

  /** 可见面板切换到其他服务端会话时，重新加载队列和执行记录。 */
  useEffect(() => {
    if (!state.workbenchOpen) {
      workbenchContext.current = "";
      workbenchGeneration.current += 1;
      return;
    }
    if (state.workbenchTab !== "queue" && state.workbenchTab !== "records") return;
    const context = `${state.workbenchTab}:${state.selectedThreadId ?? ""}`;
    if (context !== workbenchContext.current)
      void loadWorkbench(state.workbenchTab, state.selectedThreadId);
  }, [state.selectedThreadId, state.workbenchOpen, state.workbenchTab, loadWorkbench]);

  /** 从服务端管理的会话初始化，仅展示已配置的上游模型标签。 */
  useEffect(() => {
    if (bootstrapped.current) return;
    bootstrapped.current = true;
    const saved = readPreferences();
    void (async () => {
      const [metadata, threads, agents] = await Promise.allSettled([
        postChat<{
          model?: string;
          experience?: boolean;
          attachmentCapabilities?: AttachmentCapabilities;
        }>("model", {}),
        postChat<ThreadView[]>("threads/list", {
          archived: false,
          search: "",
          limit: 50,
          offset: 0,
        }),
        postChat<AgentView[]>("threads/agents", {}),
      ]);
      if (agents.status === "fulfilled") commit({ type: "patch", value: { agents: agents.value } });
      if (metadata.status === "fulfilled")
        commit({
          type: "patch",
          value: {
            model: metadata.value.model ?? "",
            experienceEnabled: metadata.value.experience ?? false,
            attachmentCapabilities: metadata.value.attachmentCapabilities,
          },
        });
      else
        commit({
          type: "patch",
          value: {
            notice: { tone: "error", text: `模型信息读取失败：${errorMessage(metadata.reason)}` },
          },
        });
      if (threads.status !== "fulfilled") {
        commit({
          type: "patch",
          value: { loading: false, loadingError: errorMessage(threads.reason) },
        });
        return;
      }
      const first =
        threads.value.find((thread) => thread.threadId === saved.activeThreadId) ??
        threads.value[0];
      commit({
        type: "patch",
        value: {
          threads: threads.value,
          threadHasMore: threads.value.length === 50,
          selectedThreadId: first?.threadId,
          loading: false,
        },
      });
      if (first) {
        if (!getSession(stateRef.current, first.threadId).historyLoaded)
          await loadHistory(first.threadId, true);
      } else await createThread();
    })();
  }, [commit, createThread, loadHistory]);

  /** 刷新后仅对本机已持久保存的未知上传重用原文件与操作键做一次核对。 */
  useEffect(() => {
    const threadId = state.selectedThreadId;
    if (!threadId) return;
    const drafts = getSession(stateRef.current, threadId).attachmentDrafts;
    for (const draft of drafts) {
      if (draft.status !== "unknown") continue;
      const key = `${threadId}:${draft.operationKey}`;
      if (automaticUploadRetries.current.has(key)) continue;
      automaticUploadRetries.current.add(key);
      void readPendingUpload(draft.operationKey)
        .then((file) => {
          if (file) return uploadDraftFile(threadId, draft, file);
          patchAttachmentDraft(threadId, draft.operationKey, {
            status: "unknown",
            error: "本机未保留原文件；请重选原文件以核实此次上传。",
          });
          return undefined;
        })
        .catch((error: unknown) => {
          patchAttachmentDraft(threadId, draft.operationKey, {
            status: "unknown",
            error: errorMessage(error),
          });
        });
    }
  }, [state.selectedThreadId, state.sessions, patchAttachmentDraft, uploadDraftFile]);

  /** 对服务端元数据搜索防抖，并为快速连续查询保留严格的请求代际边界。 */
  useEffect(() => {
    if (state.loading) return;
    const timer = window.setTimeout(() => {
      void refreshThreads(state.search, state.archivedView);
    }, 180);
    return () => window.clearTimeout(timer);
  }, [state.search, state.archivedView, state.loading, refreshThreads]);

  /** 跟随系统配色变化，同时保持用户明确选择的浅色或深色模式。 */
  useEffect(() => {
    const root = document.documentElement;
    const query = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const dark = state.theme === "dark" || (state.theme === "system" && query.matches);
      root.dataset["theme"] = dark ? "dark" : "light";
      root.style.colorScheme = dark ? "dark" : "light";
    };
    apply();
    query.addEventListener("change", apply);
    return () => query.removeEventListener("change", apply);
  }, [state.theme]);

  /** 归约器更新后持久保存显示状态，不在本地缓存对话正文或供应商数据。 */
  useEffect(() => {
    persist();
  }, [persist, state.sessions, state.selectedThreadId, state.theme, state.pending]);

  const updateSession = (threadId: string, patch: Partial<ThreadSession>) =>
    commit({ type: "session-patch", threadId, value: patch });
  const setNotice = (text: string, tone: "info" | "error" | "success" = "info") =>
    commit({ type: "patch", value: { notice: text ? { text, tone } : undefined } });

  return {
    state,
    commit,
    createThread,
    refreshThreads,
    loadMoreThreads,
    loadHistory,
    loadMoreHistory,
    selectThread,
    updateThread,
    submit,
    retrySubmission,
    answerAndResume,
    resumeTurn,
    controlTurn,
    compact,
    resolveRecovery: resolveRecoveryTurn,
    refreshRecoveries,
    loadWorkbench,
    addAttachments,
    cancelAttachmentUpload,
    retryAttachment,
    removeAttachment,
    updateSession,
    setNotice,
  };

  /** 会话固定历史快照加载完成后，恢复该会话自己的视口位置。 */
  function scrollConversationToEnd(threadId: string) {
    if (stateRef.current.selectedThreadId !== threadId) return;
    document.querySelector(".conversation-scroll")?.scrollTo({
      top: document.querySelector(".conversation-scroll")?.scrollHeight ?? 0,
      behavior: "instant" as ScrollBehavior,
    });
  }
}

/** 按稳定调用标识更新工具记录，同时保留已有结果记录的时间顺序。 */
function updateTool(turn: ChatTurn, callId: string, patch: { state?: string }) {
  const items = turn.items.map((item) =>
    item.kind === "tool" && item.callId === callId ? { ...item, ...patch } : item,
  );
  if (!items.some((item) => item.kind === "tool" && item.callId === callId))
    items.push({
      key: `tool:${callId}`,
      kind: "tool",
      text: "",
      callId,
      name: "工具调用",
      state: patch.state ?? "PENDING",
    });
  return { ...turn, items };
}

/** 为一项恢复裁决构造不与其他 Thread/Turn 冲突的持久化字典键。 */
function recoveryOperationId(threadId: string, turnId: string, recoveryId: string): string {
  return JSON.stringify([threadId, turnId, recoveryId]);
}

/** 410 表示草稿附件已过期或已丢弃，其他错误需保留本地恢复信息。 */
async function discardAttachmentIfPresent(threadId: string, attachmentId: string): Promise<void> {
  try {
    await discardAttachments(threadId, [attachmentId]);
  } catch (error) {
    if (!(error instanceof ChatHttpError) || error.status !== 410) throw error;
  }
}

/** 协议解析器合并多行数据字段后，才解码 SSE 事件内容。 */
function parseData(data: string[]): unknown {
  return JSON.parse(data.join("\n"));
}

/** 在对话提示中展示可读的轮次传输错误，不暴露响应正文。 */
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "发生未知错误。";
}

/** 跨历史附件累计量被服务端拒绝时给出可执行建议，并明确用户输入仍然保留。 */
function attachmentErrorMessage(error: unknown): string {
  if (error instanceof ChatHttpError && error.code === "REQUEST_TOO_LARGE") {
    return "当前会话附件总量超过模型请求上限。请压缩当前会话，或移除新资料后再试；消息文字和文件选择已保留。";
  }
  return errorMessage(error);
}

/** 仅按浏览器 MIME 或稳定扩展名归类，实际文件格式仍由服务端字节校验。 */
function attachmentMediaType(file: File): string | undefined {
  const normalized =
    file.type.toLowerCase() === "image/jpg" ? "image/jpeg" : file.type.toLowerCase();
  const supported = new Set([
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
    "application/pdf",
  ]);
  if (supported.has(normalized)) return normalized;
  const extension = file.name.toLowerCase().split(".").at(-1);
  return (
    {
      png: "image/png",
      jpg: "image/jpeg",
      jpeg: "image/jpeg",
      gif: "image/gif",
      webp: "image/webp",
      pdf: "application/pdf",
    } as Record<string, string>
  )[extension ?? ""];
}

/** 把服务端模型字节上限转为可读单位，避免在界面中硬编码另一套预算。 */
function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return "未知";
  const megabytes = value / 1_000_000;
  return `${megabytes.toLocaleString(undefined, { maximumFractionDigits: 1 })} MB`;
}

/** 仅在状态接口确认原轮次不存在时，才允许使用相同键重试。 */
function isNotFound(error: unknown): boolean {
  return error instanceof Error && /404|not found|不存在|未找到/i.test(error.message);
}

/** 通过浏览器加密 API 生成操作标识，采用保守的降级方案保证本地演示可用。 */
function randomId(): string {
  return (
    globalThis.crypto?.randomUUID?.().replaceAll("-", "") ??
    `${Date.now()}${Math.random().toString(16).slice(2)}`
  );
}

/** 用户切换会话时及时停止退避等待，并为当前视图保留唯一监听器。 */
async function delay(
  milliseconds: number,
  generation: number,
  current: { current: number },
  signal: AbortSignal,
): Promise<void> {
  if (generation !== current.current) return;
  await new Promise<void>((resolve) => {
    let timer = 0;
    const finish = () => {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", finish);
      resolve();
    };
    if (signal.aborted) {
      resolve();
      return;
    }
    timer = window.setTimeout(finish, milliseconds);
    signal.addEventListener("abort", finish, { once: true });
  });
}
