// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import { useShallow } from "zustand/react/shallow";
import type { InputQueue, QueuedInput } from "../domain/timelineContracts";
import type { AttachmentSummary } from "../domain/timelineContracts";
import type { TimelineApproval, TimelineItemAdapter, TimelineTurn } from "../domain/timelineTypes";
import {
  contextReferenceIdentity,
  referenceToUserContent,
  type ConversationContextReference,
  type UserContentBlock,
} from "../domain/userContent";
import { selectTurnsForThread, useTimelineStore, type TimelineRuntimeFence } from "./timelineStore";
import type {
  ConversationAcceptedTurn,
  ConversationAccessMode,
  ConversationCollaborationMode,
  ConversationAttachment,
  ConversationAttachmentDraftItem,
  ConversationAttachmentImportEvent,
  ConversationAttachmentPort,
  ConversationPlanCreationPort,
  ConversationModelOption,
  ConversationModelSelection,
  ConversationPreferencesPort,
  ConversationThreadPreferences,
  ReasoningLevel,
  ConversationSubmit,
  ConversationTurnPort,
} from "./ports";

const TERMINAL_TURN_STATES = new Set<TimelineTurn["status"]>(["completed", "failed", "cancelled"]);
const MAX_DRAFT_ATTACHMENTS = 10;
const MAX_DRAFT_ATTACHMENT_BYTES = 250 * 1024 * 1024;
const RECONCILIATION_INITIAL_DELAY_MS = 500;
const RECONCILIATION_MAX_DELAY_MS = 5_000;
const RECOVERED_RECONCILIATION_SILENCE_MS = 5_000;
const RECOVERED_RECONCILIATION_TURN_STATES = new Set<TimelineTurn["status"]>(["queued", "running"]);

/**
 * 只有 queued/running 会在没有用户输入时自行推进；waiting_approval/suspended 是稳定等待态，
 * 不能因为恢复快照缺少 terminal event 就持续制造 thread/read。
 */
function shouldReconcileRecoveredTurn(status: TimelineTurn["status"]): boolean {
  return RECOVERED_RECONCILIATION_TURN_STATES.has(status);
}

/**
 * 统一 reconciliation 退避阶梯，避免 500ms 后出现未约定的 4s 间隔；达到 5s 后保持封顶。
 */
function nextReconciliationDelay(delayMs: number): number {
  if (delayMs <= 500) return 1_000;
  if (delayMs <= 1_000) return 2_000;
  return RECONCILIATION_MAX_DELAY_MS;
}

export interface ConversationInteractionOptions {
  threadId: string | undefined;
  workspaceId: string | undefined;
  preferences: ConversationThreadPreferences | undefined;
  models: readonly ConversationModelOption[];
  ready: boolean;
  blocked: boolean;
  turnPort: ConversationTurnPort;
  /** 每次恢复 admission 都先从完整权威 thread/read 取得 revision 并收敛 Timeline。 */
  readThreadRevision: (threadId: string) => Promise<number>;
  planCreationPort?: ConversationPlanCreationPort;
  preferencesPort: ConversationPreferencesPort;
  attachmentPort?: ConversationAttachmentPort;
  onAttachmentRemoved?: (attachmentId: string) => void;
  onAttachmentsBound?: (threadId: string, attachmentIds: readonly string[]) => void;
  /** 取消等短暂动作失败交给组合层的 Portal 反馈，避免把一次性错误钉在 Composer 下方。 */
  onTransientError?: (message: string) => void;
}

/**
 * 描述点击发送后、权威 Turn 投影形成前的本地消息记录。失败记录必须留在原消息旁说明结果，
 * 不能退回 Composer 冒充尚未发送的草稿；它仍不拥有服务端 Turn identity 或持久化语义。
 */
export interface ConversationLocalSubmission {
  submissionId: string;
  threadId: string;
  text: string;
  contextReferences: readonly ConversationContextReference[];
  attachments: readonly (AttachmentSummary & { thumbnailUrl?: string })[];
  submittedAt: string;
  status: "pending" | "failed";
  error?: string;
}

export interface ConversationQueuedInputView extends QueuedInput {
  pending?: boolean;
  busyAction?: "prioritize" | "update" | "delete";
  error?: string;
}

/** Conversation 交互 controller 向组合层暴露的窄 view model 与 actions。 */
export interface ConversationInteractionController {
  draft: string;
  contextReferences: readonly ConversationContextReference[];
  preferences: ConversationThreadPreferences | undefined;
  models: readonly ConversationModelOption[];
  attachments: readonly ConversationAttachment[];
  attachmentDraftItems: readonly ConversationAttachmentDraftItem[];
  activeTurn: boolean;
  suspendedTurn: boolean;
  continuationAvailable: boolean;
  editingQuestion: boolean;
  editableSourceMessageId: string | undefined;
  disabled: boolean;
  preferenceBusy: boolean;
  importingAttachments: boolean;
  sending: boolean;
  /** 仅在准入失败且原结构化草稿已恢复后递增，驱动 textarea 恢复焦点与选择区。 */
  draftRecoveryRevision: number;
  localSubmissions: readonly ConversationLocalSubmission[];
  cancelling: boolean;
  resuming: boolean;
  error: string | undefined;
  modelSettingsRequired: boolean;
  clipboardNotice: string | undefined;
  inputQueue: InputQueue | undefined;
  queuedInputs: readonly ConversationQueuedInputView[];
  queueAccepting: boolean;
  updateDraft(text: string): void;
  updateContextReferences(references: readonly ConversationContextReference[]): void;
  changeModel(selectionValue: string): Promise<void>;
  changeReasoning(reasoningLevel: ReasoningLevel | null): Promise<void>;
  changeAccessMode(accessMode: ConversationAccessMode): Promise<void>;
  changeCollaborationMode(collaborationMode: ConversationCollaborationMode): Promise<void>;
  resetPreferences(
    selection: ConversationModelSelection,
    accessMode: ConversationAccessMode,
  ): Promise<void>;
  importAttachments(): Promise<void>;
  importDroppedAttachments(dropToken: string): Promise<void>;
  importClipboard(): Promise<void>;
  retryAttachment(itemId: string): Promise<void>;
  removeAttachment(itemId: string): Promise<void>;
  send(request: ConversationSubmit): Promise<void>;
  continueReply(): Promise<void>;
  editQuestion(item: TimelineItemAdapter): void;
  cancelEditQuestion(): void;
  enqueue(request: ConversationSubmit): Promise<void>;
  prioritizeQueuedInput(inputId: string, expectedInputRevision: number): Promise<void>;
  updateQueuedInput(
    inputId: string,
    expectedInputRevision: number,
    content: readonly UserContentBlock[],
  ): Promise<void>;
  deleteQueuedInput(inputId: string, expectedInputRevision: number): Promise<void>;
  resume(): Promise<void>;
  cancel(): Promise<void>;
  approve(approval: TimelineApproval, decision: "approve" | "deny"): Promise<void>;
  /** 权威关闭 ACK 后清理精确 Thread 集合的本地交互草稿与附件 attempt。 */
  clearClosedThreads(threadIds: readonly string[], fence: TimelineRuntimeFence): boolean;
}

interface ComposerDraftSnapshot {
  text: string;
  references: readonly ConversationContextReference[];
  attachments: readonly ConversationAttachmentDraftItem[];
}

interface EditingQuestionState {
  stoppedTurnId: string;
  previousDraft: ComposerDraftSnapshot;
}

/** 恢复准入在当前问题失效时拒绝 admission；稳定 code 驱动单行提示与草稿恢复。 */
class QuestionAdmissionError extends Error {
  readonly code: string;

  /** 错误只携带可本地化的分类，不泄漏 thread/read 或 Runtime 的内部消息。 */
  constructor(code: "INVALID_STATE" | "TURN_NOT_REASKABLE" | "CONFLICT") {
    super(code);
    this.name = "QuestionAdmissionError";
    this.code = code;
  }
}

/**
 * 判断接纳结果是否已经被 Timeline 终态超越；late ACK 只能补足 pending，不得重新打开
 * 已完成 Turn，也不得用旧 revision 覆盖服务端的新事实。
 */
function isAcceptedTurnTerminal(threadId: string, accepted: ConversationAcceptedTurn): boolean {
  const timeline = useTimelineStore.getState();
  const acceptedTurn = timeline.turns[accepted.turnId];
  if (acceptedTurn !== undefined) return TERMINAL_TURN_STATES.has(acceptedTurn.status);
  const thread = timeline.threads[threadId];
  return thread?.activeTurnId === undefined && (thread?.revision ?? 0) > accepted.threadRevision;
}

/**
 * 只允许对当前路径最后一个已停止且从未形成成功最终答复的问题继续或重问；按钮渲染不是权限，
 * 因此每次动作都重新从唯一 Timeline Store 校验来源、最新 Turn 和成功答复事实；Turn 身份仅在所属
 * Thread 索引中查找，避免当前会话动作遍历其它会话的持久实体。
 */
function reaskableQuestionSource(
  threadId: string,
  stoppedTurn: TimelineTurn | undefined,
  requireSingleUserInput = true,
): TimelineItemAdapter | undefined {
  if (
    stoppedTurn === undefined ||
    stoppedTurn.origin !== "native" ||
    !["failed", "cancelled"].includes(stoppedTurn.status)
  )
    return undefined;
  const timeline = useTimelineStore.getState();
  if (timeline.threads[threadId]?.latestTurnId !== stoppedTurn.turnId) return undefined;
  const currentItems = (timeline.itemIdsByThread[threadId] ?? [])
    .map((itemId) => timeline.items[itemId])
    .filter((item): item is TimelineItemAdapter => item !== undefined);
  const source =
    (stoppedTurn.sourceMessageId == null
      ? undefined
      : timeline.items[stoppedTurn.sourceMessageId]) ??
    currentItems
      .filter((item) => item.kind === "user_message" && item.turnId === stoppedTurn.turnId)
      .at(-1);
  if (source?.kind !== "user_message" || source.origin !== "native") return undefined;
  // 同一 Turn 含多个队列问题时重问会截断整条 Turn，界面应隐藏入口并由服务端再次复验。
  if (
    requireSingleUserInput &&
    currentItems.filter((item) => item.kind === "user_message" && item.turnId === source.turnId)
      .length > 1
  )
    return undefined;
  const relatedTurnIds = new Set(
    (timeline.turnIdsByThread[threadId] ?? [])
      .map((turnId) => timeline.turns[turnId])
      .filter(
        (turn): turn is TimelineTurn =>
          turn !== undefined &&
          turn.origin === "native" &&
          (turn.turnId === source.turnId || turn.sourceMessageId === source.itemId),
      )
      .map((turn) => turn.turnId),
  );
  return currentItems.some(
    (item) =>
      item.kind === "agent_message" &&
      item.origin === "native" &&
      item.final === true &&
      item.metadata?.failureReply !== true &&
      relatedTurnIds.has(item.turnId),
  )
    ? undefined
    : source;
}

/** 只读取稳定 command code；本地错误对象与 Rust 消息都不进入用户可见状态判断。 */
function interactionErrorCode(error: unknown): string | undefined {
  if (typeof error !== "object" || error === null || !("code" in error)) return undefined;
  const code = (error as { code: unknown }).code;
  return typeof code === "string" ? code : undefined;
}

/** 最新 stopped Turn 与其可恢复问题必须来自同一份同步 Timeline Store。 */
function currentStoppedQuestionSource(
  threadId: string,
  requireSingleUserInput: boolean,
): { turn: TimelineTurn; source: TimelineItemAdapter } | undefined {
  const timeline = useTimelineStore.getState();
  const latestTurnId = timeline.threads[threadId]?.latestTurnId;
  const turn = latestTurnId === undefined ? undefined : timeline.turns[latestTurnId];
  if (turn === undefined) return undefined;
  const source = reaskableQuestionSource(threadId, turn, requireSingleUserInput);
  return source === undefined ? undefined : { turn, source };
}

/**
 * continue/reask 的 Thread revision 可能被 threadSeen 等非 Timeline event mutation 推进；完整
 * thread/read 后以稳定 Turn 身份确认仍是同一次失败，再使用快照中的公开 source item ID。
 * Event 与 Snapshot 对同一消息可能使用不同公开 ID，旧 ID 不能阻断合法续答或传给 reask。
 */
async function withAuthoritativeQuestionRevision<T>(input: {
  threadId: string;
  stoppedTurnId: string;
  requireSingleUserInput: boolean;
  readThreadRevision: (threadId: string) => Promise<number>;
  invoke: (expectedThreadRevision: number, sourceMessageId: string) => Promise<T>;
}): Promise<T> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const revision = await input.readThreadRevision(input.threadId);
    if (!Number.isSafeInteger(revision) || revision < 0)
      throw new QuestionAdmissionError("INVALID_STATE");
    const current = currentStoppedQuestionSource(input.threadId, input.requireSingleUserInput);
    if (current?.turn.turnId !== input.stoppedTurnId)
      throw new QuestionAdmissionError("TURN_NOT_REASKABLE");
    try {
      return await input.invoke(revision, current.source.itemId);
    } catch (error) {
      if (attempt !== 0 || interactionErrorCode(error) !== "CONFLICT") throw error;
    }
  }
  throw new QuestionAdmissionError("CONFLICT");
}

/** operation identity 只关联当前进程中的 Channel，不承担服务端资源身份。 */
function createAttachmentOperationId(): string {
  return `op_${crypto.randomUUID()}`;
}

/**
 * 取消错误只读取稳定 code；原生 adapter 的 message 可能包含实现细节，不能成为 UI 状态机
 * 的判断依据。TURN_NOT_FOUND 与状态冲突需要重新读取权威 Thread，其余错误只释放本地重试门。
 */
function cancellationErrorCode(error: unknown): string | undefined {
  if (typeof error !== "object" || error === null) return undefined;
  const code = (error as { code?: unknown }).code;
  return typeof code === "string" ? code : undefined;
}

/**
 * 取消目标可能已被终态事件或服务端清理；重读只失效对应 Thread，不在本地伪造 cancelled。
 */
function cancellationNeedsThreadResync(errorCode: string | undefined): boolean {
  return (
    errorCode === "TURN_NOT_FOUND" ||
    errorCode === "THREAD_NOT_FOUND" ||
    errorCode === "CONFLICT" ||
    errorCode === "INVALID_STATE"
  );
}

/**
 * 将取消失败映射为短且准确的可见提示；运行时门禁与业务目标缺失不能都显示成泛化失败。
 */
function cancellationFailureMessage(errorCode: string | undefined): string {
  switch (errorCode) {
    case "TURN_NOT_FOUND":
      return "运行已不存在，正在重新读取。";
    case "THREAD_NOT_FOUND":
      return "对话已不存在，正在重新读取。";
    case "CONFLICT":
    case "INVALID_STATE":
      return "对话状态已变化，正在重新读取。";
    case "RECOVERY_REQUIRED":
      return "需要先完成运行时恢复。";
    case "RUNTIME_NOT_READY":
      return "运行时尚未就绪，请重试。";
    case "RUNTIME_UNAVAILABLE":
      return "运行时暂不可用，请稍后重试。";
    default:
      return "取消失败，请稍后重试。";
  }
}

/**
 * 按 itemId 原位投影事件，避免并行导入重排；completed 会将临时 attempt 收敛为唯一可提交
 * 的 ready 状态，cancelled 则彻底移除对应 UI 项。
 */
function applyAttachmentImportEvent(
  current: readonly ConversationAttachmentDraftItem[],
  event: ConversationAttachmentImportEvent,
): readonly ConversationAttachmentDraftItem[] {
  const index = current.findIndex((item) => item.itemId === event.itemId);
  if (event.kind === "cancelled") {
    return index < 0 ? current : current.filter((item) => item.itemId !== event.itemId);
  }
  if (event.kind === "started") {
    const next: ConversationAttachmentDraftItem = {
      state: "importing",
      operationId: event.operationId,
      attemptId: event.attemptId,
      itemId: event.itemId,
      fileName: event.fileName,
      sizeBytes: event.sizeBytes,
      mediaKind: event.mediaKind,
      mediaType: event.mediaType,
      phase: "copying",
      bytesCopied: 0,
      totalBytes: event.sizeBytes,
    };
    return index < 0
      ? [...current, next]
      : current.map((item, itemIndex) => (itemIndex === index ? next : item));
  }
  const previous = index < 0 ? undefined : current[index];
  if (event.kind === "progress") {
    if (previous?.state !== "importing") return current;
    const next: ConversationAttachmentDraftItem = {
      ...previous,
      operationId: event.operationId,
      attemptId: event.attemptId,
      phase: event.phase,
      bytesCopied: event.bytesCopied,
      totalBytes: event.totalBytes ?? previous.totalBytes,
    };
    return current.map((item, itemIndex) => (itemIndex === index ? next : item));
  }
  if (event.kind === "completed") {
    const next: ConversationAttachmentDraftItem = {
      state: "ready",
      itemId: event.itemId,
      ...event.attachment,
    };
    return index < 0
      ? [...current, next]
      : current.map((item, itemIndex) => (itemIndex === index ? next : item));
  }
  const next: ConversationAttachmentDraftItem = {
    state: "failed",
    operationId: event.operationId,
    attemptId: event.attemptId,
    itemId: event.itemId,
    fileName: event.fileName ?? previous?.fileName ?? "未命名附件",
    sizeBytes: event.sizeBytes ?? previous?.sizeBytes,
    mediaKind: event.mediaKind ?? previous?.mediaKind,
    mediaType: event.mediaType ?? previous?.mediaType,
    code: event.code,
    message: event.message,
    retryable: event.retryable,
  };
  return index < 0
    ? [...current, next]
    : current.map((item, itemIndex) => (itemIndex === index ? next : item));
}

interface PendingQueuedInput {
  inputId: string;
  turnId: string;
  content: UserContentBlock[];
  attachments: AttachmentSummary[];
  status: "pending";
  issue: null;
  createdAt: string;
}

interface ThreadTurnOperationOwner {
  readonly key: string;
  readonly threadId: string;
  readonly turnId: string;
  readonly fence: TimelineRuntimeFence;
  readonly token: symbol;
}

/** 异步返回前再询问唯一 Store tombstone，避免 ACK、失败恢复或附件事件重写已关闭 Thread。@author kongweiguang */
function timelineThreadIsClosed(threadId: string): boolean {
  return useTimelineStore.getState().isThreadClosed(threadId);
}

/** 延续旧 Runtime 的异步请求必须同时匹配服务实例与 generation，不能在同 ID 新实例复活。@author kongweiguang */
function captureTimelineRuntimeFence(): TimelineRuntimeFence | undefined {
  const state = useTimelineStore.getState();
  return state.handshake.phase === "ready" && state.serverInstanceId !== undefined
    ? { serverInstanceId: state.serverInstanceId, generation: state.handshake.generation }
    : undefined;
}

/** 异步回调写入前验证原 Thread 仍属于发起请求的 Runtime，且未收到精确 close ACK。@author kongweiguang */
function timelineRequestIsCurrent(
  threadId: string,
  fence: TimelineRuntimeFence | undefined,
): boolean {
  if (fence === undefined) return false;
  const state = useTimelineStore.getState();
  return (
    state.handshake.phase === "ready" &&
    state.serverInstanceId === fence.serverInstanceId &&
    state.handshake.generation === fence.generation &&
    !state.isThreadClosed(threadId)
  );
}

/** 同一 Thread 内顺序执行 mutation；调用方不等待 lane 即可继续编辑和追加下一条草稿。 */
function enqueueThreadMutation<T>(
  lanes: Map<string, Promise<void>>,
  threadId: string,
  operation: () => Promise<T>,
): Promise<T> {
  const prior = lanes.get(threadId) ?? Promise.resolve();
  const execution = prior.catch(() => undefined).then(operation);
  const settled = execution.then(
    () => undefined,
    () => undefined,
  );
  lanes.set(threadId, settled);
  void settled.finally(() => {
    if (lanes.get(threadId) === settled) lanes.delete(threadId);
  });
  return execution;
}

/** Mutation lane 同时隔离 Thread 与 Runtime，旧服务实例的慢请求不应阻塞同 ID 新会话。@author kongweiguang */
function threadMutationLaneKey(threadId: string, fence: TimelineRuntimeFence | undefined): string {
  return `${fence?.serverInstanceId ?? ""}\u0000${fence?.generation ?? 0}\u0000${threadId}`;
}

/** 本地 single-flight 按 Runtime、Thread 和资源三重身份隔离，旧 finally 不能释放同名新请求。@author kongweiguang */
function threadRuntimeOperationKey(
  threadId: string,
  fence: TimelineRuntimeFence,
  operationId: string,
): string {
  return JSON.stringify([fence.serverInstanceId, fence.generation, threadId, operationId]);
}

/**
 * Conversation application 独占 Draft、Turn single-flight、pending、取消、追加输入、审批和
 * 模型切换编排；所有状态按 Thread identity 隔离，外部 owner 只通过窄端口注入能力。
 * @author kongweiguang
 */
export function useConversationInteractionController({
  threadId,
  workspaceId,
  preferences,
  models,
  ready,
  blocked,
  turnPort,
  readThreadRevision,
  planCreationPort,
  preferencesPort,
  attachmentPort,
  onAttachmentRemoved,
  onAttachmentsBound,
  onTransientError,
}: ConversationInteractionOptions): ConversationInteractionController {
  const [draftsByThread, setDraftsByThread] = useState<Record<string, string>>({});
  const [contextDraftsByThread, setContextDraftsByThread] = useState<
    Record<string, readonly ConversationContextReference[]>
  >({});
  const [attachmentDraftsByThread, setAttachmentDraftsByThread] = useState<
    Record<string, readonly ConversationAttachmentDraftItem[]>
  >({});
  const [editingQuestionByThread, setEditingQuestionByThread] = useState<
    Record<string, EditingQuestionState>
  >({});
  const [pendingTurns, setPendingTurns] = useState<Record<string, ConversationAcceptedTurn>>({});
  const [localSubmissionsByThread, setLocalSubmissionsByThread] = useState<
    Record<string, readonly ConversationLocalSubmission[]>
  >({});
  const [sendingThreadIds, setSendingThreadIds] = useState<Record<string, true>>({});
  const [draftRecoveryRevisionsByThread, setDraftRecoveryRevisionsByThread] = useState<
    Record<string, number>
  >({});
  const [cancellingTurnIds, setCancellingTurnIds] = useState<Record<string, true>>({});
  const [resumingTurnIds, setResumingTurnIds] = useState<Record<string, true>>({});
  const [errorsByThread, setErrorsByThread] = useState<Record<string, string>>({});
  const [modelSettingsRequiredByThread, setModelSettingsRequiredByThread] = useState<
    Record<string, true>
  >({});
  const [clipboardNoticesByThread, setClipboardNoticesByThread] = useState<Record<string, string>>(
    {},
  );
  const [pendingQueuedInputsByThread, setPendingQueuedInputsByThread] = useState<
    Record<string, readonly PendingQueuedInput[]>
  >({});
  const [queueActionsByInputId, setQueueActionsByInputId] = useState<
    Record<string, "prioritize" | "update" | "delete">
  >({});
  const [queueErrorsByInputId, setQueueErrorsByInputId] = useState<Record<string, string>>({});
  const [preferenceBusyByThread, setPreferenceBusyByThread] = useState<Record<string, true>>({});
  const [preferenceErrorsByThread, setPreferenceErrorsByThread] = useState<Record<string, string>>(
    {},
  );
  const mountedRef = useRef(false);
  const submitGuardsRef = useRef(new Set<string>());
  const submitOwnerByThreadRef = useRef(
    new Map<string, { readonly token: symbol; readonly fence: TimelineRuntimeFence | undefined }>(),
  );
  const queueMutationLanesRef = useRef(new Map<string, Promise<void>>());
  const queueInputOwnerByIdRef = useRef(new Map<string, string>());
  const queueActionOwnerByInputIdRef = useRef(new Map<string, symbol>());
  const failedQueuedTextsRef = useRef<Record<string, string[]>>({});
  const cancelGuardsRef = useRef(new Map<string, ThreadTurnOperationOwner>());
  const cancelThreadByTurnRef = useRef(new Map<string, ThreadTurnOperationOwner>());
  const cancelReconciliationRef = useRef(
    new Map<
      string,
      {
        owner: ThreadTurnOperationOwner;
        delayMs: number;
        timer: ReturnType<typeof setTimeout>;
      }
    >(),
  );
  const recoveredTurnReconciliationRef = useRef(
    new Map<
      string,
      {
        threadId: string;
        generation: number;
        serverInstanceId: string;
        delayMs: number;
        timer: ReturnType<typeof setTimeout>;
      }
    >(),
  );
  const recoveredTurnReconciliationKeyRef = useRef<string | undefined>(undefined);
  const resumeGuardsRef = useRef(new Map<string, ThreadTurnOperationOwner>());
  const approvalGuardsRef = useRef(new Map<string, ThreadTurnOperationOwner>());
  const preferenceGuardsRef = useRef(new Map<string, symbol>());
  const attachmentOperationGuardsRef = useRef(new Map<string, symbol>());
  const attachmentDiscardGuardsRef = useRef(new Map<string, symbol>());
  const overflowDiscardGuardsRef = useRef(new Map<string, symbol>());
  const readyAttachmentSizesRef = useRef<Record<string, Map<string, number>>>({});
  const pendingTurnsRef = useRef<Record<string, ConversationAcceptedTurn>>({});
  const currentThreadIdRef = useRef(threadId);
  currentThreadIdRef.current = threadId;
  /** 只阻塞当前 Runtime 的同 Thread 提交；旧 pair 的 ACK 不得释放新 pair 的 single-flight。@author kongweiguang */
  const hasCurrentSubmitGuard = useCallback((requestThreadId: string): boolean => {
    const owner = submitOwnerByThreadRef.current.get(requestThreadId);
    if (owner === undefined) return submitGuardsRef.current.has(requestThreadId);
    if (timelineRequestIsCurrent(requestThreadId, owner.fence)) return true;
    submitOwnerByThreadRef.current.delete(requestThreadId);
    submitGuardsRef.current.delete(requestThreadId);
    return false;
  }, []);
  /** 当前订阅只覆盖可见 Thread 与显式 pending Turn，避免无关会话高频流触发交互重渲染。@author kongweiguang */
  const currentThreadTurns = useTimelineStore(useShallow(selectTurnsForThread(threadId ?? "")));
  const watchedTurnIds = new Set([
    ...currentThreadTurns.map((turn) => turn.turnId),
    ...Object.values(pendingTurns).map((turn) => turn.turnId),
    ...Object.keys(cancellingTurnIds),
    ...Object.keys(resumingTurnIds),
  ]);
  const currentTurns = useTimelineStore(
    useShallow((state) =>
      Object.fromEntries(
        [...watchedTurnIds].flatMap((turnId) => {
          const turn = state.turns[turnId];
          return turn === undefined ? [] : [[turnId, turn] as const];
        }),
      ),
    ),
  );
  const watchedThreadIds = new Set([
    ...(threadId === undefined ? [] : [threadId]),
    ...Object.keys(pendingTurns),
  ]);
  const currentThreads = useTimelineStore(
    useShallow((state) =>
      Object.fromEntries(
        [...watchedThreadIds].flatMap((watchedThreadId) => {
          const thread = state.threads[watchedThreadId];
          return thread === undefined ? [] : [[watchedThreadId, thread] as const];
        }),
      ),
    ),
  );
  const recoveredActiveTurnId = useTimelineStore((state) =>
    threadId === undefined ? undefined : state.recoveredActiveTurnByThread?.[threadId],
  );
  const recoveredResyncReason = useTimelineStore((state) =>
    threadId === undefined ? undefined : state.resyncRequired[threadId],
  );
  const recoveredRuntimeFence = useTimelineStore(
    (state) => `${state.handshake.generation}:${state.serverInstanceId ?? ""}`,
  );
  const blockingTurn =
    threadId === undefined
      ? undefined
      : currentThreadTurns
          .filter((turn) => !TERMINAL_TURN_STATES.has(turn.status))
          .filter((turn) => turn.origin === "native")
          .sort(
            (left, right) =>
              (left.startedAt ?? "").localeCompare(right.startedAt ?? "") ||
              left.turnId.localeCompare(right.turnId),
          )[0];
  const pendingTurn = threadId === undefined ? undefined : pendingTurns[threadId];
  const suspendedTurn = blockingTurn?.status === "suspended" ? blockingTurn : undefined;
  const executingTurn = blockingTurn?.status === "suspended" ? undefined : blockingTurn;
  const latestStoppedTurn =
    threadId === undefined
      ? undefined
      : (() => {
          const latestTurnId = currentThreads[threadId]?.latestTurnId;
          const latestTurn = latestTurnId === undefined ? undefined : currentTurns[latestTurnId];
          return latestTurn !== undefined && latestTurn.origin === "native" && ["failed", "cancelled"].includes(latestTurn.status)
            ? latestTurn
            : undefined;
        })();
  const reaskableSource =
    threadId === undefined ? undefined : reaskableQuestionSource(threadId, latestStoppedTurn);
  const continuationSource =
    threadId === undefined
      ? undefined
      : reaskableQuestionSource(threadId, latestStoppedTurn, false);
  const editingQuestion = threadId !== undefined && editingQuestionByThread[threadId] !== undefined;
  const blockingTurnId = blockingTurn?.turnId ?? pendingTurn?.turnId;
  const executingTurnId = executingTurn?.turnId ?? pendingTurn?.turnId;
  const inputQueue = useTimelineStore((state) =>
    blockingTurnId === undefined ? undefined : state.inputQueueByTurn[blockingTurnId],
  );
  const preferenceBusy = threadId !== undefined && preferenceBusyByThread[threadId] === true;

  /**
   * 取消 guard 的释放必须同时清理 reconciliation timer；集中处理可避免失败、终态事件、
   * 目标消失和超时四条路径互相遗漏，尤其不能让旧 Thread 的 timer 写入当前会话状态。
   * @author kongweiguang
   */
  const releaseCancellation = useCallback((owner: ThreadTurnOperationOwner): void => {
    if (cancelGuardsRef.current.get(owner.key) !== owner) return;
    const { turnId } = owner;
    const reconciliation = cancelReconciliationRef.current.get(turnId);
    if (reconciliation?.owner === owner) {
      clearTimeout(reconciliation.timer);
      cancelReconciliationRef.current.delete(turnId);
    }
    cancelGuardsRef.current.delete(owner.key);
    if (cancelThreadByTurnRef.current.get(turnId) === owner)
      cancelThreadByTurnRef.current.delete(turnId);
    if (mountedRef.current && timelineRequestIsCurrent(owner.threadId, owner.fence)) {
      setCancellingTurnIds((current) => {
        if (!timelineRequestIsCurrent(owner.threadId, owner.fence)) return current;
        if (current[turnId] === undefined) return current;
        const next = { ...current };
        delete next[turnId];
        return next;
      });
    }
  }, []);

  /**
   * ACK 后持续观察 authoritative read；每次读回仍为非终态时继续请求 resync，直到权威
   * terminal/目标消失或会话被卸载，绝不把本地 projection 改写成 cancelled；每条 timer
   * 都属于请求起点 Runtime 与操作 token，旧回调不能删除同 ID 新实例的对账链。
   * @author kongweiguang
   */
  const scheduleCancelReconciliation = useCallback(
    (owner: ThreadTurnOperationOwner, delayMs = RECONCILIATION_INITIAL_DELAY_MS): void => {
      const { threadId: requestThreadId, turnId: requestTurnId } = owner;
      if (
        !mountedRef.current ||
        currentThreadIdRef.current !== requestThreadId ||
        cancelGuardsRef.current.get(owner.key) !== owner ||
        !timelineRequestIsCurrent(requestThreadId, owner.fence)
      )
        return;
      const current = useTimelineStore.getState().turns[requestTurnId];
      if (current === undefined || TERMINAL_TURN_STATES.has(current.status)) {
        releaseCancellation(owner);
        return;
      }
      const entry = {
        owner,
        delayMs,
        timer: setTimeout(() => {
          if (cancelReconciliationRef.current.get(requestTurnId) !== entry) return;
          cancelReconciliationRef.current.delete(requestTurnId);
          if (
            !mountedRef.current ||
            currentThreadIdRef.current !== requestThreadId ||
            cancelGuardsRef.current.get(owner.key) !== owner ||
            !timelineRequestIsCurrent(requestThreadId, owner.fence)
          )
            return;
          const latest = useTimelineStore.getState().turns[requestTurnId];
          if (latest === undefined || TERMINAL_TURN_STATES.has(latest.status)) {
            releaseCancellation(owner);
            return;
          }
          if (useTimelineStore.getState().resyncRequired[requestThreadId] === undefined)
            useTimelineStore.getState().requestThreadResync(requestThreadId);
          scheduleCancelReconciliation(owner, nextReconciliationDelay(delayMs));
        }, delayMs),
      };
      cancelReconciliationRef.current.set(requestTurnId, entry);
    },
    [releaseCancellation],
  );

  /**
   * 清理从 snapshot 恢复出的 active Turn 对账 timer 与启动 key；Thread 切换或显式 cancel
   * 后必须允许同一可见 Turn 在后续状态变化时重新建立对账，避免 key 残留永久阻断恢复。
   */
  const releaseRecoveredTurnReconciliation = useCallback((turnId: string): void => {
    const reconciliation = recoveredTurnReconciliationRef.current.get(turnId);
    if (reconciliation === undefined) {
      recoveredTurnReconciliationKeyRef.current = undefined;
      return;
    }
    clearTimeout(reconciliation.timer);
    recoveredTurnReconciliationRef.current.delete(turnId);
    recoveredTurnReconciliationKeyRef.current = undefined;
  }, []);

  /**
   * 对恢复快照中仍为 active 的 Turn 持续做 authoritative read；terminal/消失或 Thread
   * 切换立即停止；健康状态只保留一个 5 秒静默 timer，不在已有 resync pending 时叠加
   * read，异常退避由 Conversation controller 的单一恢复链负责。
   * @author kongweiguang
   */
  const scheduleRecoveredTurnReconciliation = useCallback(
    (
      requestThreadId: string,
      requestTurnId: string,
      delayMs = RECOVERED_RECONCILIATION_SILENCE_MS,
    ): void => {
      const stateAtStart = useTimelineStore.getState();
      if (
        !mountedRef.current ||
        currentThreadIdRef.current !== requestThreadId ||
        !timelineRequestIsCurrent(requestThreadId, {
          serverInstanceId: stateAtStart.serverInstanceId ?? "",
          generation: stateAtStart.handshake.generation,
        })
      )
        return;
      const current = useTimelineStore.getState().turns[requestTurnId];
      if (
        current === undefined ||
        TERMINAL_TURN_STATES.has(current.status) ||
        !shouldReconcileRecoveredTurn(current.status)
      ) {
        releaseRecoveredTurnReconciliation(requestTurnId);
        return;
      }
      if (stateAtStart.serverInstanceId === undefined) return;
      const generation = stateAtStart.handshake.generation;
      const serverInstanceId = stateAtStart.serverInstanceId;
      const entry = {
        threadId: requestThreadId,
        generation,
        serverInstanceId,
        delayMs,
        timer: setTimeout(() => {
          if (recoveredTurnReconciliationRef.current.get(requestTurnId) !== entry) return;
          recoveredTurnReconciliationRef.current.delete(requestTurnId);
          recoveredTurnReconciliationKeyRef.current = undefined;
          if (!mountedRef.current || currentThreadIdRef.current !== requestThreadId) return;
          if (!timelineRequestIsCurrent(requestThreadId, { serverInstanceId, generation })) return;
          const latest = useTimelineStore.getState().turns[requestTurnId];
          if (
            latest === undefined ||
            TERMINAL_TURN_STATES.has(latest.status) ||
            !shouldReconcileRecoveredTurn(latest.status)
          ) {
            releaseRecoveredTurnReconciliation(requestTurnId);
            return;
          }
          const state = useTimelineStore.getState();
          if (
            state.handshake.generation !== generation ||
            state.serverInstanceId !== serverInstanceId
          )
            return;
          const lastAcceptedLive = state.getLastAcceptedLive(requestThreadId);
          const silenceElapsed =
            lastAcceptedLive === undefined
              ? RECOVERED_RECONCILIATION_SILENCE_MS
              : Date.now() - lastAcceptedLive.receivedAt;
          if (silenceElapsed < RECOVERED_RECONCILIATION_SILENCE_MS) {
            // 不订阅每个 delta，避免 Composer/导航跟随正文高频重渲染；timer 到点时读取本地
            // 接收时钟即可把健康事件后的剩余静默窗口顺延，正常输出不制造读取请求。
            scheduleRecoveredTurnReconciliation(
              requestThreadId,
              requestTurnId,
              Math.max(50, RECOVERED_RECONCILIATION_SILENCE_MS - silenceElapsed),
            );
            return;
          }
          // 当前没有待处理 resync 时，5 秒静默才触发一次健康对账；读失败的异常退避由
          // Conversation controller 负责，避免这里再叠加第二套 0.5/1/2/5 轮询。
          if (state.resyncRequired[requestThreadId] === undefined)
            state.requestThreadResync(requestThreadId);
        }, delayMs),
      };
      recoveredTurnReconciliationRef.current.set(requestTurnId, entry);
    },
    [releaseRecoveredTurnReconciliation],
  );

  /** 卸载后所有异步 continuation 只释放 ref guard，不再写 React 状态。 */
  useEffect(() => {
    const cancelReconciliation = cancelReconciliationRef.current;
    const recoveredTurnReconciliation = recoveredTurnReconciliationRef.current;
    const cancelThreadByTurn = cancelThreadByTurnRef.current;
    const cancelGuards = cancelGuardsRef.current;
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      currentThreadIdRef.current = undefined;
      recoveredTurnReconciliationKeyRef.current = undefined;
      for (const { timer } of cancelReconciliation.values()) clearTimeout(timer);
      cancelReconciliation.clear();
      for (const { timer } of recoveredTurnReconciliation.values()) clearTimeout(timer);
      recoveredTurnReconciliation.clear();
      cancelThreadByTurn.clear();
      cancelGuards.clear();
    };
  }, []);

  /**
   * Timeline 终态按 Thread 回收 pending admission；依赖完整 Turn/Thread 投影，确保切到其它
   * 会话后到达的终态也会释放原 Thread，而不是等待用户切回才清理。
   */
  useEffect(() => {
    if (Object.keys(pendingTurnsRef.current).length === 0) return;
    setPendingTurns((current) => {
      let changed = false;
      const next = { ...current };
      for (const [pendingThreadId, accepted] of Object.entries(current)) {
        const turn = currentTurns[accepted.turnId];
        const thread = currentThreads[pendingThreadId];
        const terminal =
          turn !== undefined
            ? TERMINAL_TURN_STATES.has(turn.status)
            : thread?.activeTurnId === undefined &&
              (thread?.revision ?? 0) > accepted.threadRevision;
        if (!terminal) continue;
        delete next[pendingThreadId];
        changed = true;
      }
      if (!changed) return current;
      pendingTurnsRef.current = next;
      return next;
    });
  }, [currentThreads, currentTurns]);

  /**
   * 权威状态离开 suspended 后释放当前 Runtime/Thread 的 Resume single-flight；按请求 pair
   * 计算 key，旧 SID 同名 Turn 的收尾不能释放新服务实例的 guard。@author kongweiguang
   */
  useEffect(() => {
    const fence = captureTimelineRuntimeFence();
    const currentTurnOperationKey =
      threadId !== undefined && fence !== undefined
        ? (turnId: string) => threadRuntimeOperationKey(threadId, fence, turnId)
        : undefined;
    setResumingTurnIds((current) => {
      let changed = false;
      const next = { ...current };
      for (const turnId of Object.keys(current)) {
        if (currentTurns[turnId]?.status === "suspended") continue;
        const key = currentTurnOperationKey?.(turnId);
        if (key !== undefined) resumeGuardsRef.current.delete(key);
        delete next[turnId];
        changed = true;
      }
      return changed ? next : current;
    });
  }, [currentTurns, threadId]);

  /**
   * Cancel ACK 只是接纳事实；只有 Timeline 权威终态或 Turn 从权威投影消失后，才能释放
   * single-flight。这样 ACK 后到 terminal event 的窗口内，重复点击仍不会发出第二个请求。
   */
  useEffect(() => {
    if (cancelGuardsRef.current.size === 0) return;
    const completedTurnIds = Object.keys(cancellingTurnIds).filter((turnId) => {
      const turn = currentTurns[turnId];
      return turn === undefined || TERMINAL_TURN_STATES.has(turn.status);
    });
    for (const turnId of completedTurnIds) {
      const owner = cancelThreadByTurnRef.current.get(turnId);
      if (owner !== undefined) releaseCancellation(owner);
    }
  }, [cancellingTurnIds, currentTurns, releaseCancellation]);

  /**
   * 取消中的 Turn 切出当前会话时暂停重读但保留 guard；切回且 Turn 仍非终态时恢复同一条
   * 对账链，保证隐藏会话不占用 read，同时不会丢掉原取消意图。
   */
  useEffect(() => {
    for (const [turnId, reconciliation] of cancelReconciliationRef.current) {
      if (reconciliation.owner.threadId !== threadId) {
        clearTimeout(reconciliation.timer);
        if (cancelReconciliationRef.current.get(turnId) === reconciliation)
          cancelReconciliationRef.current.delete(turnId);
      }
    }
    if (threadId === undefined) return;
    for (const [turnId, owner] of cancelThreadByTurnRef.current) {
      if (
        owner.threadId !== threadId ||
        !timelineRequestIsCurrent(owner.threadId, owner.fence) ||
        cancelReconciliationRef.current.has(turnId)
      )
        continue;
      const turn = currentTurns[turnId];
      if (turn === undefined || TERMINAL_TURN_STATES.has(turn.status)) continue;
      scheduleCancelReconciliation(owner);
    }
  }, [currentTurns, scheduleCancelReconciliation, threadId]);

  /**
   * 仅对当前可见 Conversation 的 snapshot active Turn 启动 5 秒静默 watchdog；已有 resync
   * 意图由 Conversation controller 单独消费，避免 interaction 再造第二条 read 链。
   */
  useEffect(() => {
    const fence = captureTimelineRuntimeFence();
    const currentTurnOperationKey =
      threadId !== undefined && fence !== undefined
        ? (turnId: string) => threadRuntimeOperationKey(threadId, fence, turnId)
        : undefined;
    for (const [turnId, reconciliation] of recoveredTurnReconciliationRef.current) {
      const turn = currentTurns[turnId];
      const cancelKey = currentTurnOperationKey?.(turnId);
      const keepTimer =
        reconciliation.threadId === threadId &&
        recoveredActiveTurnId === turnId &&
        reconciliation.generation === useTimelineStore.getState().handshake.generation &&
        reconciliation.serverInstanceId === useTimelineStore.getState().serverInstanceId &&
        recoveredResyncReason === undefined &&
        !(cancelKey !== undefined && cancelGuardsRef.current.has(cancelKey)) &&
        turn !== undefined &&
        !TERMINAL_TURN_STATES.has(turn.status) &&
        shouldReconcileRecoveredTurn(turn.status);
      if (!keepTimer) {
        releaseRecoveredTurnReconciliation(turnId);
      }
    }
    const activeCancelKey =
      recoveredActiveTurnId === undefined
        ? undefined
        : currentTurnOperationKey?.(recoveredActiveTurnId);
    if (
      threadId === undefined ||
      recoveredActiveTurnId === undefined ||
      (activeCancelKey !== undefined && cancelGuardsRef.current.has(activeCancelKey)) ||
      recoveredResyncReason !== undefined
    ) {
      if (recoveredActiveTurnId === undefined)
        recoveredTurnReconciliationKeyRef.current = undefined;
      return;
    }
    const turn = currentTurns[recoveredActiveTurnId];
    if (
      turn === undefined ||
      TERMINAL_TURN_STATES.has(turn.status) ||
      !shouldReconcileRecoveredTurn(turn.status)
    ) {
      releaseRecoveredTurnReconciliation(recoveredActiveTurnId);
      recoveredTurnReconciliationKeyRef.current = undefined;
      return;
    }
    const generation = useTimelineStore.getState().handshake.generation;
    if (generation <= 0) return;
    const key = `${generation}:${threadId}:${recoveredActiveTurnId}`;
    if (recoveredTurnReconciliationKeyRef.current === key) return;
    recoveredTurnReconciliationKeyRef.current = key;
    scheduleRecoveredTurnReconciliation(
      threadId,
      recoveredActiveTurnId,
      RECOVERED_RECONCILIATION_SILENCE_MS,
    );
  }, [
    currentTurns,
    recoveredActiveTurnId,
    recoveredResyncReason,
    recoveredRuntimeFence,
    releaseRecoveredTurnReconciliation,
    scheduleRecoveredTurnReconciliation,
    threadId,
  ]);

  /** Draft 只写入当前 Runtime 中尚未关闭的 Thread；延迟 updater 再验一次，拒绝 close 后迟到输入。@author kongweiguang */
  const updateDraft = useCallback(
    (text: string): void => {
      const requestFence = captureTimelineRuntimeFence();
      if (threadId === undefined || !timelineRequestIsCurrent(threadId, requestFence)) return;
      setDraftsByThread((current) =>
        timelineRequestIsCurrent(threadId, requestFence)
          ? { ...current, [threadId]: text }
          : current,
      );
      setErrorsByThread((current) => {
        if (!timelineRequestIsCurrent(threadId, requestFence)) return current;
        if (current[threadId] === undefined) return current;
        const next = { ...current };
        delete next[threadId];
        return next;
      });
      const restored = failedQueuedTextsRef.current[threadId] ?? [];
      if (restored.length > 0 && !text.startsWith(restored.join("\n\n")))
        delete failedQueuedTextsRef.current[threadId];
    },
    [threadId],
  );

  /** Context Chip 与文本共享 Runtime/Thread 隔离，独立更新但不允许已关闭 Thread 复活。@author kongweiguang */
  const updateContextReferences = useCallback(
    (references: readonly ConversationContextReference[]): void => {
      const requestFence = captureTimelineRuntimeFence();
      if (threadId === undefined || !timelineRequestIsCurrent(threadId, requestFence)) return;
      const seen = new Set<string>();
      const unique = references.filter((reference) => {
        const identity = contextReferenceIdentity(reference);
        if (seen.has(identity)) return false;
        seen.add(identity);
        return true;
      });
      setContextDraftsByThread((current) =>
        timelineRequestIsCurrent(threadId, requestFence)
          ? { ...current, [threadId]: unique }
          : current,
      );
      setErrorsByThread((current) => {
        if (!timelineRequestIsCurrent(threadId, requestFence)) return current;
        if (current[threadId] === undefined) return current;
        const next = { ...current };
        delete next[threadId];
        return next;
      });
    },
    [threadId],
  );

  /** 编辑从历史 USER 恢复结构化引用与附件；先保存原草稿快照，取消或提交后再还给 Composer。 */
  const editQuestion = useCallback(
    (item: TimelineItemAdapter): void => {
      const requestThreadId = threadId;
      const source = reaskableQuestionSource(requestThreadId ?? "", latestStoppedTurn);
      if (
        requestThreadId === undefined ||
        timelineThreadIsClosed(requestThreadId) ||
        latestStoppedTurn === undefined ||
        source === undefined ||
        source.itemId !== item.itemId ||
        item.threadId !== requestThreadId ||
        sendingThreadIds[requestThreadId] === true ||
        (attachmentDraftsByThread[requestThreadId] ?? []).some(
          (draft) => draft.state === "importing",
        ) ||
        editingQuestionByThread[requestThreadId] !== undefined
      )
        return;
      const sourceAttachments: ConversationAttachmentDraftItem[] = (item.attachments ?? []).map(
        (attachment) => ({
          state: "ready",
          historyBound: true,
          itemId: `history:${attachment.attachmentId}`,
          attachmentId: attachment.attachmentId,
          fileName: attachment.displayName,
          sizeBytes: attachment.sizeBytes,
          mediaKind: attachment.mediaKind,
          mediaType: attachment.mediaType,
        }),
      );
      setEditingQuestionByThread((current) => ({
        ...current,
        [requestThreadId]: {
          stoppedTurnId: latestStoppedTurn.turnId,
          previousDraft: {
            text: draftsByThread[requestThreadId] ?? "",
            references: contextDraftsByThread[requestThreadId] ?? [],
            attachments: attachmentDraftsByThread[requestThreadId] ?? [],
          },
        },
      }));
      setDraftsByThread((current) => ({ ...current, [requestThreadId]: item.text ?? "" }));
      setContextDraftsByThread((current) => ({
        ...current,
        [requestThreadId]: item.contextReferences ?? [],
      }));
      setAttachmentDraftsByThread((current) => ({
        ...current,
        [requestThreadId]: sourceAttachments,
      }));
      setErrorsByThread((current) => {
        const next = { ...current };
        delete next[requestThreadId];
        return next;
      });
      setModelSettingsRequiredByThread((current) => {
        const next = { ...current };
        delete next[requestThreadId];
        return next;
      });
      setDraftRecoveryRevisionsByThread((current) => ({
        ...current,
        [requestThreadId]: (current[requestThreadId] ?? 0) + 1,
      }));
    },
    [
      attachmentDraftsByThread,
      contextDraftsByThread,
      draftsByThread,
      editingQuestionByThread,
      latestStoppedTurn,
      sendingThreadIds,
      threadId,
    ],
  );

  /** 取消只撤销 Composer 的编辑态；原始问题、历史附件绑定与当前输入草稿均保持不变。 */
  const cancelEditQuestion = useCallback((): void => {
    if (threadId === undefined) return;
    const editing = editingQuestionByThread[threadId];
    if (editing === undefined) return;
    setDraftsByThread((current) => ({ ...current, [threadId]: editing.previousDraft.text }));
    setContextDraftsByThread((current) => ({
      ...current,
      [threadId]: editing.previousDraft.references,
    }));
    setAttachmentDraftsByThread((current) => ({
      ...current,
      [threadId]: editing.previousDraft.attachments,
    }));
    setEditingQuestionByThread((current) => {
      const next = { ...current };
      delete next[threadId];
      return next;
    });
    setErrorsByThread((current) => {
      const next = { ...current };
      delete next[threadId];
      return next;
    });
    setDraftRecoveryRevisionsByThread((current) => ({
      ...current,
      [threadId]: (current[threadId] ?? 0) + 1,
    }));
  }, [editingQuestionByThread, threadId]);

  /**
   * 普通发送创建新问题，编辑态则以源消息和 Thread revision 走 turn/reask；两种入口共享
   * 草稿恢复与 single-flight，但隐藏 continue 由独立 action 执行，避免生成伪 USER 气泡。
   */
  const send = useCallback(
    async ({ text, attachmentIds, contextReferences }: ConversationSubmit): Promise<void> => {
      const requestThreadId = threadId;
      const requestFence = captureTimelineRuntimeFence();
      const editing =
        requestThreadId === undefined ? undefined : editingQuestionByThread[requestThreadId];
      const submittedText = text.trim();
      const submittedReferences =
        contextReferences ??
        (requestThreadId === undefined ? [] : (contextDraftsByThread[requestThreadId] ?? []));
      const attachmentDraftItems =
        requestThreadId === undefined ? [] : (attachmentDraftsByThread[requestThreadId] ?? []);
      const availableAttachments = attachmentDraftItems.filter(
        (item): item is Extract<ConversationAttachmentDraftItem, { state: "ready" }> =>
          item.state === "ready",
      );
      const submittedAttachments =
        attachmentIds ?? availableAttachments.map((attachment) => attachment.attachmentId);
      const sourceStillReaskable =
        requestThreadId === undefined || editing === undefined
          ? undefined
          : reaskableQuestionSource(requestThreadId, latestStoppedTurn);
      if (
        requestThreadId === undefined ||
        !timelineRequestIsCurrent(requestThreadId, requestFence) ||
        timelineThreadIsClosed(requestThreadId) ||
        workspaceId === undefined ||
        preferences === undefined ||
        (submittedText.length === 0 &&
          submittedAttachments.length === 0 &&
          !submittedReferences.some((reference) => reference.type === "workspace_reference")) ||
        blocked ||
        !ready ||
        blockingTurn !== undefined ||
        preferenceBusy ||
        attachmentDraftItems.some((item) => item.state !== "ready") ||
        (editing !== undefined &&
          (latestStoppedTurn?.turnId !== editing.stoppedTurnId ||
            sourceStillReaskable === undefined)) ||
        hasCurrentSubmitGuard(requestThreadId) ||
        pendingTurnsRef.current[requestThreadId] !== undefined
      )
        return;
      const submittedAt = new Date().toISOString();
      const submissionId = `submission:${crypto.randomUUID()}`;
      const submittedAttachmentSet = new Set(submittedAttachments);
      const submittedAttachmentItems = availableAttachments.filter((attachment) =>
        submittedAttachmentSet.has(attachment.attachmentId),
      );
      const submitToken = Symbol("conversation-submit");
      submitOwnerByThreadRef.current.set(requestThreadId, {
        token: submitToken,
        fence: requestFence,
      });
      submitGuardsRef.current.add(requestThreadId);
      setSendingThreadIds((current) => ({ ...current, [requestThreadId]: true }));
      setLocalSubmissionsByThread((current) => ({
        ...current,
        [requestThreadId]: [
          ...(current[requestThreadId] ?? []),
          {
            submissionId,
            threadId: requestThreadId,
            text: submittedText,
            contextReferences: submittedReferences,
            attachments: submittedAttachmentItems.map((attachment) => ({
              attachmentId: attachment.attachmentId,
              displayName: attachment.fileName,
              sizeBytes: attachment.sizeBytes,
              mediaKind: attachment.mediaKind,
              mediaType: attachment.mediaType ?? "application/octet-stream",
              thumbnailUrl: attachment.thumbnailUrl,
            })),
            submittedAt,
            status: "pending",
          },
        ],
      }));
      setDraftsByThread((current) =>
        (current[requestThreadId] ?? text) === text
          ? { ...current, [requestThreadId]: "" }
          : current,
      );
      setContextDraftsByThread((current) =>
        (current[requestThreadId] ?? submittedReferences) === submittedReferences
          ? { ...current, [requestThreadId]: [] }
          : current,
      );
      setAttachmentDraftsByThread((current) => {
        const observed = current[requestThreadId] ?? [];
        const remaining = observed.filter(
          (attachment) =>
            attachment.state !== "ready" || !submittedAttachmentSet.has(attachment.attachmentId),
        );
        return remaining.length === observed.length
          ? current
          : { ...current, [requestThreadId]: remaining };
      });
      setErrorsByThread((current) => {
        const next = { ...current };
        delete next[requestThreadId];
        return next;
      });
      try {
        if (editing === undefined && preferences.collaborationMode === "plan") {
          const expectedThreadRevision = currentThreads[requestThreadId]?.revision;
          if (expectedThreadRevision === undefined || planCreationPort === undefined) {
            throw new Error("plan artifact was not acknowledged");
          }
          const planCreated = await planCreationPort.create(
            requestThreadId,
            submittedText,
            expectedThreadRevision,
          );
          if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return;
          if (!planCreated) throw new Error("plan artifact was not acknowledged");
        }
        // Runtime port 负责真正接纳；即时 UI 反馈不伪造 Turn identity 或持久 Timeline 事实。
        const projectionAttachments = submittedAttachmentItems.map((attachment) => ({
          attachmentId: attachment.attachmentId,
          displayName: attachment.fileName,
          sizeBytes: attachment.sizeBytes,
          mediaKind: attachment.mediaKind,
          mediaType: attachment.mediaType ?? "application/octet-stream",
        }));
        const content: UserContentBlock[] = [
          ...submittedReferences.map(referenceToUserContent),
          ...submittedAttachments.map((attachmentId) => ({
            type: "attachment" as const,
            attachmentId,
          })),
          ...(submittedText === "" ? [] : [{ type: "text" as const, text: submittedText }]),
        ];
        const accepted =
          editing === undefined
            ? await turnPort.submitTurn({
                threadId: requestThreadId,
                content,
                ...(projectionAttachments.length === 0 ? {} : { projectionAttachments }),
              })
            : await withAuthoritativeQuestionRevision({
                threadId: requestThreadId,
                stoppedTurnId: editing.stoppedTurnId,
                requireSingleUserInput: true,
                readThreadRevision,
                invoke: (expectedThreadRevision, sourceMessageId) =>
                  turnPort.reaskTurn({
                    threadId: requestThreadId,
                    expectedThreadRevision,
                    sourceMessageId,
                    content,
                    ...(projectionAttachments.length === 0 ? {} : { projectionAttachments }),
                  }),
              });
        if (
          !mountedRef.current ||
          !timelineRequestIsCurrent(requestThreadId, requestFence) ||
          timelineThreadIsClosed(requestThreadId)
        )
          return;
        const newlyBoundAttachments = submittedAttachmentItems.filter(
          (attachment) => attachment.historyBound !== true,
        );
        if (newlyBoundAttachments.length > 0) {
          const readySizes = readyAttachmentSizesRef.current[requestThreadId];
          for (const attachment of newlyBoundAttachments)
            readySizes?.delete(attachment.attachmentId);
          onAttachmentsBound?.(
            requestThreadId,
            newlyBoundAttachments.map((attachment) => attachment.attachmentId),
          );
        }
        // 阶段二：独立事件流可能早于 ACK 到达，先核对终态再决定是否建立 pending 投影。
        if (!isAcceptedTurnTerminal(requestThreadId, accepted)) {
          const nextPending = { ...pendingTurnsRef.current, [requestThreadId]: accepted };
          pendingTurnsRef.current = nextPending;
          setPendingTurns(nextPending);
        }
        setLocalSubmissionsByThread((current) => ({
          ...current,
          [requestThreadId]: (current[requestThreadId] ?? []).filter(
            (submission) => submission.submissionId !== submissionId,
          ),
        }));
        if (editing !== undefined) {
          setDraftsByThread((current) => ({
            ...current,
            [requestThreadId]: editing.previousDraft.text,
          }));
          setContextDraftsByThread((current) => ({
            ...current,
            [requestThreadId]: editing.previousDraft.references,
          }));
          setAttachmentDraftsByThread((current) => ({
            ...current,
            [requestThreadId]: editing.previousDraft.attachments,
          }));
          setEditingQuestionByThread((current) => {
            const next = { ...current };
            delete next[requestThreadId];
            return next;
          });
          setDraftRecoveryRevisionsByThread((current) => ({
            ...current,
            [requestThreadId]: (current[requestThreadId] ?? 0) + 1,
          }));
          useTimelineStore.getState().requestThreadResync(requestThreadId);
        }
      } catch (error) {
        if (
          mountedRef.current &&
          timelineRequestIsCurrent(requestThreadId, requestFence) &&
          !timelineThreadIsClosed(requestThreadId)
        ) {
          const code =
            typeof error === "object" && error !== null && "code" in error
              ? String((error as { code: unknown }).code)
              : undefined;
          const modelSettingsRequired = new Set([
            "PROVIDER_OR_MODEL_NOT_FOUND",
            "CREDENTIAL_MISSING",
            "MODEL_UNAVAILABLE",
          ]).has(code ?? "");
          const preserveDraft =
            editing !== undefined ||
            modelSettingsRequired ||
            new Set([
              "WORKSPACE_REFERENCE_INVALID",
              "SKILL_UNAVAILABLE",
              "SKILL_LOAD_FAILED",
              "CONTENT_TOO_LARGE",
            ]).has(code ?? "");
          if (preserveDraft) {
            setDraftsByThread((current) => ({
              ...current,
              [requestThreadId]:
                (current[requestThreadId] ?? "").trim() === ""
                  ? submittedText
                  : current[requestThreadId]!,
            }));
            setContextDraftsByThread((current) => {
              const observed = current[requestThreadId] ?? [];
              const seen = new Set(observed.map(contextReferenceIdentity));
              return {
                ...current,
                [requestThreadId]: [
                  ...submittedReferences.filter(
                    (reference) => !seen.has(contextReferenceIdentity(reference)),
                  ),
                  ...observed,
                ],
              };
            });
          }
          setAttachmentDraftsByThread((current) => {
            const observed = current[requestThreadId] ?? [];
            const observedIds = new Set(
              observed.flatMap((attachment) =>
                attachment.state === "ready" ? [attachment.attachmentId] : [],
              ),
            );
            const restored = submittedAttachmentItems.filter(
              (attachment) => !observedIds.has(attachment.attachmentId),
            );
            return restored.length === 0
              ? current
              : { ...current, [requestThreadId]: [...restored, ...observed] };
          });
          setLocalSubmissionsByThread((current) => ({
            ...current,
            [requestThreadId]: preserveDraft
              ? (current[requestThreadId] ?? []).filter(
                  (submission) => submission.submissionId !== submissionId,
                )
              : (current[requestThreadId] ?? []).map((submission) =>
                  submission.submissionId === submissionId
                    ? {
                        ...submission,
                        status: "failed",
                        error: "发送失败，请检查运行时连接后重试。",
                      }
                    : submission,
                ),
          }));
          if (preserveDraft) {
            setErrorsByThread((current) => ({
              ...current,
              [requestThreadId]:
                code === "PROVIDER_OR_MODEL_NOT_FOUND"
                  ? "当前会话模型已不可用，输入草稿已保留。请配置模型后重试。"
                  : code === "CREDENTIAL_MISSING"
                    ? "当前模型缺少凭据，输入草稿已保留。请检查模型设置中的凭据。"
                    : code === "MODEL_UNAVAILABLE"
                      ? "模型连接暂不可用，输入草稿已保留。请检查连接和凭据。"
                      : code === "TURN_NOT_REASKABLE"
                        ? "当前问题已无法编辑，请刷新对话。"
                        : code === "CONFLICT"
                          ? "对话已更新，请刷新后重试。"
                          : code === "CONTENT_TOO_LARGE"
                            ? "消息内容过大，请精简后重试。"
                            : editing !== undefined
                              ? "编辑后的问题未能提交，请重试。"
                              : "引用已变化，请调整后重试。",
            }));
            if (modelSettingsRequired) {
              setModelSettingsRequiredByThread((current) => ({
                ...current,
                [requestThreadId]: true,
              }));
            }
            setDraftRecoveryRevisionsByThread((current) => ({
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? 0) + 1,
            }));
          }
        }
      } finally {
        if (submitOwnerByThreadRef.current.get(requestThreadId)?.token === submitToken) {
          submitOwnerByThreadRef.current.delete(requestThreadId);
          submitGuardsRef.current.delete(requestThreadId);
        }
        if (
          mountedRef.current &&
          timelineRequestIsCurrent(requestThreadId, requestFence) &&
          !timelineThreadIsClosed(requestThreadId)
        ) {
          setSendingThreadIds((current) => {
            const next = { ...current };
            delete next[requestThreadId];
            return next;
          });
        }
      }
    },
    [
      attachmentDraftsByThread,
      blocked,
      blockingTurn,
      contextDraftsByThread,
      currentThreads,
      editingQuestionByThread,
      latestStoppedTurn,
      planCreationPort,
      onAttachmentsBound,
      preferenceBusy,
      preferences,
      ready,
      readThreadRevision,
      threadId,
      turnPort,
      workspaceId,
      hasCurrentSubmitGuard,
    ],
  );

  /** 关闭或 Runtime 切换期间的迟到 ACK 不得复活旧 pending，也不能清掉新 Runtime 的提交 guard。@author kongweiguang */
  const continueReply = useCallback(async (): Promise<void> => {
    const requestThreadId = threadId;
    const requestFence = captureTimelineRuntimeFence();
    const hasDraftIntent =
      (requestThreadId === undefined ? "" : (draftsByThread[requestThreadId] ?? "")).trim() !==
        "" ||
      (requestThreadId === undefined ? [] : (contextDraftsByThread[requestThreadId] ?? [])).length >
        0 ||
      (requestThreadId === undefined ? [] : (attachmentDraftsByThread[requestThreadId] ?? []))
        .length > 0;
    if (
      requestThreadId === undefined ||
      !timelineRequestIsCurrent(requestThreadId, requestFence) ||
      timelineThreadIsClosed(requestThreadId) ||
      latestStoppedTurn === undefined ||
      continuationSource === undefined ||
      hasDraftIntent ||
      editingQuestionByThread[requestThreadId] !== undefined ||
      hasCurrentSubmitGuard(requestThreadId) ||
      pendingTurnsRef.current[requestThreadId] !== undefined ||
      blockingTurn !== undefined ||
      !ready ||
      blocked
    )
      return;
    const timeline = useTimelineStore.getState();
    if (
      timeline.threads[requestThreadId]?.latestTurnId !== latestStoppedTurn.turnId ||
      !["failed", "cancelled"].includes(timeline.turns[latestStoppedTurn.turnId]?.status ?? "") ||
      reaskableQuestionSource(requestThreadId, timeline.turns[latestStoppedTurn.turnId], false)
        ?.itemId !== continuationSource.itemId
    )
      return;
    const submitToken = Symbol("conversation-continue");
    submitOwnerByThreadRef.current.set(requestThreadId, {
      token: submitToken,
      fence: requestFence,
    });
    submitGuardsRef.current.add(requestThreadId);
    setSendingThreadIds((current) => ({ ...current, [requestThreadId]: true }));
    setErrorsByThread((current) => {
      const next = { ...current };
      delete next[requestThreadId];
      return next;
    });
    try {
      const accepted = await withAuthoritativeQuestionRevision({
        threadId: requestThreadId,
        stoppedTurnId: latestStoppedTurn.turnId,
        requireSingleUserInput: false,
        readThreadRevision,
        invoke: (expectedThreadRevision, sourceMessageId) =>
          turnPort.continueTurn({
            threadId: requestThreadId,
            expectedThreadRevision,
            sourceMessageId,
          }),
      });
      if (
        !mountedRef.current ||
        !timelineRequestIsCurrent(requestThreadId, requestFence) ||
        timelineThreadIsClosed(requestThreadId)
      )
        return;
      if (!isAcceptedTurnTerminal(requestThreadId, accepted)) {
        const nextPending = { ...pendingTurnsRef.current, [requestThreadId]: accepted };
        pendingTurnsRef.current = nextPending;
        setPendingTurns(nextPending);
      }
    } catch (error) {
      if (
        mountedRef.current &&
        timelineRequestIsCurrent(requestThreadId, requestFence) &&
        !timelineThreadIsClosed(requestThreadId)
      ) {
        const code = interactionErrorCode(error);
        setErrorsByThread((current) => ({
          ...current,
          [requestThreadId]:
            code === "TURN_NOT_REASKABLE"
              ? "当前问题已无法继续，请刷新对话。"
              : code === "CONFLICT"
                ? "对话已更新，请刷新后重试。"
                : "无法继续回复，请重试。",
        }));
      }
    } finally {
      if (submitOwnerByThreadRef.current.get(requestThreadId)?.token === submitToken) {
        submitOwnerByThreadRef.current.delete(requestThreadId);
        submitGuardsRef.current.delete(requestThreadId);
      }
      if (
        mountedRef.current &&
        timelineRequestIsCurrent(requestThreadId, requestFence) &&
        !timelineThreadIsClosed(requestThreadId)
      )
        setSendingThreadIds((current) => {
          const next = { ...current };
          delete next[requestThreadId];
          return next;
        });
    }
  }, [
    attachmentDraftsByThread,
    blocked,
    blockingTurn,
    contextDraftsByThread,
    draftsByThread,
    editingQuestionByThread,
    latestStoppedTurn,
    ready,
    readThreadRevision,
    continuationSource,
    threadId,
    turnPort,
    hasCurrentSubmitGuard,
  ]);

  /**
   * 默认入队立即清空草稿并加入本地 pending 行；每个调用仍进入 Thread lane，因此相同文本连续
   * 点击不会被合并。失败文本按提交顺序插回且保留用户等待期间的新草稿。
   */
  const enqueue = useCallback(
    async ({ text, attachmentIds, contextReferences = [] }: ConversationSubmit): Promise<void> => {
      const requestThreadId = threadId;
      const requestFence = captureTimelineRuntimeFence();
      const requestTurnId = blockingTurnId;
      const submittedText = text.trim();
      const submittedReferences = [...contextReferences];
      const observedAttachmentDrafts =
        requestThreadId === undefined ? [] : (attachmentDraftsByThread[requestThreadId] ?? []);
      const readyAttachmentDrafts = observedAttachmentDrafts.filter(
        (item): item is Extract<ConversationAttachmentDraftItem, { state: "ready" }> =>
          item.state === "ready",
      );
      const submittedAttachmentIds =
        attachmentIds ?? readyAttachmentDrafts.map((attachment) => attachment.attachmentId);
      const submittedAttachmentIdSet = new Set(submittedAttachmentIds);
      const submittedAttachmentItems = readyAttachmentDrafts.filter((attachment) =>
        submittedAttachmentIdSet.has(attachment.attachmentId),
      );
      const content: UserContentBlock[] = [
        ...submittedReferences.map(referenceToUserContent),
        ...submittedAttachmentIds.map((attachmentId) => ({
          type: "attachment" as const,
          attachmentId,
        })),
        ...(submittedText === "" ? [] : [{ type: "text" as const, text: submittedText }]),
      ];
      if (
        requestThreadId === undefined ||
        !timelineRequestIsCurrent(requestThreadId, requestFence) ||
        timelineThreadIsClosed(requestThreadId) ||
        requestTurnId === undefined ||
        (submittedText.length === 0 &&
          submittedReferences.length === 0 &&
          submittedAttachmentIds.length === 0) ||
        observedAttachmentDrafts.some((item) => item.state !== "ready") ||
        inputQueue?.accepting === false
      )
        return;
      const submittedDraft = draftsByThread[requestThreadId] ?? text;
      const pendingInput: PendingQueuedInput = {
        inputId: `pending_${crypto.randomUUID()}`,
        turnId: requestTurnId,
        content,
        attachments: submittedAttachmentItems.map((attachment) => ({
          attachmentId: attachment.attachmentId,
          displayName: attachment.fileName,
          sizeBytes: attachment.sizeBytes,
          mediaKind: attachment.mediaKind,
          mediaType: attachment.mediaType ?? "application/octet-stream",
        })),
        status: "pending",
        issue: null,
        createdAt: new Date().toISOString(),
      };
      setPendingQueuedInputsByThread((current) => ({
        ...current,
        [requestThreadId]: [...(current[requestThreadId] ?? []), pendingInput],
      }));
      setDraftsByThread((current) =>
        (current[requestThreadId] ?? submittedDraft) === submittedDraft
          ? { ...current, [requestThreadId]: "" }
          : current,
      );
      setContextDraftsByThread((current) => ({ ...current, [requestThreadId]: [] }));
      setAttachmentDraftsByThread((current) => {
        const observed = current[requestThreadId] ?? [];
        const remaining = observed.filter(
          (attachment) =>
            attachment.state !== "ready" || !submittedAttachmentIdSet.has(attachment.attachmentId),
        );
        return remaining.length === observed.length
          ? current
          : { ...current, [requestThreadId]: remaining };
      });
      setErrorsByThread((current) => {
        const next = { ...current };
        delete next[requestThreadId];
        return next;
      });
      try {
        const result = await enqueueThreadMutation(
          queueMutationLanesRef.current,
          threadMutationLaneKey(requestThreadId, requestFence),
          () => turnPort.enqueueTurnInput({ turnId: requestTurnId, content }),
        );
        if (
          !timelineRequestIsCurrent(requestThreadId, requestFence) ||
          timelineThreadIsClosed(requestThreadId)
        )
          return;
        // Runtime projection 通常已先写入；这里复用同一幂等 reducer，保证窄端口 fake/替代实现也收敛。
        useTimelineStore.getState().applyInputQueue(result.inputQueue);
        if (submittedAttachmentIds.length > 0) {
          const readySizes = readyAttachmentSizesRef.current[requestThreadId];
          for (const attachmentId of submittedAttachmentIds) readySizes?.delete(attachmentId);
          onAttachmentsBound?.(requestThreadId, submittedAttachmentIds);
        }
      } catch {
        if (
          mountedRef.current &&
          timelineRequestIsCurrent(requestThreadId, requestFence) &&
          !timelineThreadIsClosed(requestThreadId)
        ) {
          setDraftsByThread((current) => ({
            ...current,
            [requestThreadId]: (() => {
              const previousFailures = failedQueuedTextsRef.current[requestThreadId] ?? [];
              const prefix = previousFailures.join("\n\n");
              const observed = current[requestThreadId] ?? "";
              const liveDraft =
                prefix.length === 0
                  ? observed
                  : observed === prefix
                    ? ""
                    : observed.startsWith(`${prefix}\n\n`)
                      ? observed.slice(prefix.length + 2)
                      : observed;
              const failures = [...previousFailures, submittedText];
              failedQueuedTextsRef.current[requestThreadId] = failures;
              return [...failures, liveDraft].filter((part) => part.length > 0).join("\n\n");
            })(),
          }));
          setContextDraftsByThread((current) => {
            const observed = current[requestThreadId] ?? [];
            const seen = new Set(observed.map(contextReferenceIdentity));
            return {
              ...current,
              [requestThreadId]: [
                ...submittedReferences.filter(
                  (reference) => !seen.has(contextReferenceIdentity(reference)),
                ),
                ...observed,
              ],
            };
          });
          setAttachmentDraftsByThread((current) => {
            const observed = current[requestThreadId] ?? [];
            const observedIds = new Set(
              observed.flatMap((attachment) =>
                attachment.state === "ready" ? [attachment.attachmentId] : [],
              ),
            );
            const restored = submittedAttachmentItems.filter(
              (attachment) => !observedIds.has(attachment.attachmentId),
            );
            return restored.length === 0
              ? current
              : { ...current, [requestThreadId]: [...restored, ...observed] };
          });
          setErrorsByThread((current) => ({
            ...current,
            [requestThreadId]: "排队失败，Turn 可能已结束，请重试。",
          }));
        }
      } finally {
        if (
          mountedRef.current &&
          timelineRequestIsCurrent(requestThreadId, requestFence) &&
          !timelineThreadIsClosed(requestThreadId)
        ) {
          setPendingQueuedInputsByThread((current) => {
            const observed = current[requestThreadId] ?? [];
            const nextItems = observed.filter((item) => item.inputId !== pendingInput.inputId);
            return nextItems.length === observed.length
              ? current
              : { ...current, [requestThreadId]: nextItems };
          });
        }
      }
    },
    [
      attachmentDraftsByThread,
      draftsByThread,
      blockingTurnId,
      inputQueue?.accepting,
      onAttachmentsBound,
      threadId,
      turnPort,
    ],
  );

  /**
   * 编辑类操作保留权威队列行，只标记局部 busy；SUSPENDED 仍允许修复阻塞队首，CAS 失败请求
   * 一次 snapshot 恢复，但新输入依然只能进入正在执行且 accepting 的 Turn。
   */
  const mutateQueuedInput = useCallback(
    async (
      action: "prioritize" | "update" | "delete",
      inputId: string,
      expectedInputRevision: number,
      content?: readonly UserContentBlock[],
    ): Promise<void> => {
      const requestThreadId = threadId;
      const requestFence = captureTimelineRuntimeFence();
      const requestTurnId = blockingTurnId;
      if (
        requestThreadId === undefined ||
        !timelineRequestIsCurrent(requestThreadId, requestFence) ||
        timelineThreadIsClosed(requestThreadId) ||
        requestTurnId === undefined ||
        queueActionsByInputId[inputId] !== undefined
      )
        return;
      const actionToken = Symbol("queued-input-action");
      queueInputOwnerByIdRef.current.set(inputId, requestThreadId);
      queueActionOwnerByInputIdRef.current.set(inputId, actionToken);
      setQueueActionsByInputId((current) => ({ ...current, [inputId]: action }));
      setQueueErrorsByInputId((current) => {
        if (current[inputId] === undefined) return current;
        const next = { ...current };
        delete next[inputId];
        return next;
      });
      try {
        const result = await enqueueThreadMutation(
          queueMutationLanesRef.current,
          threadMutationLaneKey(requestThreadId, requestFence),
          () => {
            const identity = { turnId: requestTurnId, inputId, expectedInputRevision };
            if (action === "prioritize") return turnPort.prioritizeTurnInput(identity);
            if (action === "delete") return turnPort.deleteTurnInput(identity);
            return turnPort.updateTurnInput({ ...identity, content: [...(content ?? [])] });
          },
        );
        if (
          !timelineRequestIsCurrent(requestThreadId, requestFence) ||
          timelineThreadIsClosed(requestThreadId)
        )
          return;
        useTimelineStore.getState().applyInputQueue(result.inputQueue);
        if (!result.inputQueue.items.some((item) => item.inputId === inputId))
          if (queueActionOwnerByInputIdRef.current.get(inputId) === actionToken)
            queueInputOwnerByIdRef.current.delete(inputId);
      } catch (error) {
        if (
          !mountedRef.current ||
          !timelineRequestIsCurrent(requestThreadId, requestFence) ||
          timelineThreadIsClosed(requestThreadId)
        )
          return;
        const code =
          typeof error === "object" && error !== null && "code" in error
            ? String((error as { code: unknown }).code)
            : undefined;
        if (code === "CONFLICT" || code === "QUEUED_INPUT_NOT_FOUND")
          useTimelineStore.getState().requestThreadResync(requestThreadId);
        setQueueErrorsByInputId((current) => ({
          ...current,
          [inputId]:
            code === "QUEUED_INPUT_NOT_FOUND"
              ? "消息已被处理"
              : code === "CONFLICT"
                ? "消息已更新，请重试"
                : "操作失败，请重试",
        }));
      } finally {
        if (queueActionOwnerByInputIdRef.current.get(inputId) === actionToken) {
          queueActionOwnerByInputIdRef.current.delete(inputId);
          if (mountedRef.current)
            setQueueActionsByInputId((current) => {
              if (current[inputId] === undefined) return current;
              const next = { ...current };
              delete next[inputId];
              return next;
            });
        }
      }
    },
    [blockingTurnId, queueActionsByInputId, threadId, turnPort],
  );

  const prioritizeQueuedInput = useCallback(
    (inputId: string, revision: number) => mutateQueuedInput("prioritize", inputId, revision),
    [mutateQueuedInput],
  );
  const updateQueuedInput = useCallback(
    (inputId: string, revision: number, content: readonly UserContentBlock[]) =>
      mutateQueuedInput("update", inputId, revision, content),
    [mutateQueuedInput],
  );
  const deleteQueuedInput = useCallback(
    (inputId: string, revision: number) => mutateQueuedInput("delete", inputId, revision),
    [mutateQueuedInput],
  );

  /**
   * Resume 冻结 Suspended Turn 与 revision CAS，并保留 busy 状态直到权威事件离开 suspended；
   * 请求 pair 不再当前或 Thread 已关闭时，迟到失败不得恢复 busy/error 草稿。@author kongweiguang
   */
  const resume = useCallback(async (): Promise<void> => {
    const requestThreadId = threadId;
    const requestTurnId = suspendedTurn?.turnId;
    const requestRevision = suspendedTurn?.threadRevision;
    const requestFence = captureTimelineRuntimeFence();
    if (
      requestThreadId === undefined ||
      requestTurnId === undefined ||
      requestRevision === undefined ||
      requestFence === undefined ||
      !timelineRequestIsCurrent(requestThreadId, requestFence)
    )
      return;
    const operationKey = threadRuntimeOperationKey(requestThreadId, requestFence, requestTurnId);
    if (cancelGuardsRef.current.has(operationKey) || resumeGuardsRef.current.has(operationKey))
      return;
    const owner: ThreadTurnOperationOwner = {
      key: operationKey,
      threadId: requestThreadId,
      turnId: requestTurnId,
      fence: requestFence,
      token: Symbol("turn-resume"),
    };
    resumeGuardsRef.current.set(operationKey, owner);
    setResumingTurnIds((current) =>
      timelineRequestIsCurrent(requestThreadId, requestFence)
        ? { ...current, [requestTurnId]: true }
        : current,
    );
    setErrorsByThread((current) => {
      if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
      const next = { ...current };
      delete next[requestThreadId];
      return next;
    });
    try {
      await turnPort.resumeTurn({
        turnId: requestTurnId,
        expectedThreadRevision: requestRevision,
      });
      if (
        resumeGuardsRef.current.get(operationKey) !== owner ||
        !timelineRequestIsCurrent(requestThreadId, requestFence)
      ) {
        if (resumeGuardsRef.current.get(operationKey) === owner)
          resumeGuardsRef.current.delete(operationKey);
      }
    } catch (error) {
      if (resumeGuardsRef.current.get(operationKey) !== owner) return;
      resumeGuardsRef.current.delete(operationKey);
      if (mountedRef.current && timelineRequestIsCurrent(requestThreadId, requestFence)) {
        setResumingTurnIds((current) => {
          if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
          const next = { ...current };
          delete next[requestTurnId];
          return next;
        });
        const code =
          typeof error === "object" && error !== null && "code" in error
            ? String((error as { code: unknown }).code)
            : undefined;
        const message =
          code === "TURN_RESUME_ORDER_CONFLICT"
            ? "请先处理更早中断的运行。"
            : code === "TURN_NOT_RESUMABLE"
              ? "当前运行已无法继续，请取消后重新发起。"
              : "继续失败，请确认运行时状态后重试。";
        setErrorsByThread((current) =>
          timelineRequestIsCurrent(requestThreadId, requestFence)
            ? { ...current, [requestThreadId]: message }
            : current,
        );
      }
    }
  }, [suspendedTurn, threadId, turnPort]);

  /**
   * 取消操作冻结点击时的 Turn identity 与 Runtime pair 并按 owner token single-flight；ACK
   * 后保持 guard 直到权威终态或目标消失，close tombstone 会阻止迟到结果重写错误/队列。@author kongweiguang
   */
  const cancel = useCallback(async (): Promise<void> => {
    const requestThreadId = threadId;
    const requestTurnId = blockingTurnId;
    const requestFence = captureTimelineRuntimeFence();
    if (
      requestThreadId === undefined ||
      requestTurnId === undefined ||
      requestFence === undefined ||
      !timelineRequestIsCurrent(requestThreadId, requestFence)
    )
      return;
    const operationKey = threadRuntimeOperationKey(requestThreadId, requestFence, requestTurnId);
    if (resumeGuardsRef.current.has(operationKey) || cancelGuardsRef.current.has(operationKey))
      return;
    const owner: ThreadTurnOperationOwner = {
      key: operationKey,
      threadId: requestThreadId,
      turnId: requestTurnId,
      fence: requestFence,
      token: Symbol("turn-cancel"),
    };
    cancelGuardsRef.current.set(operationKey, owner);
    cancelThreadByTurnRef.current.set(requestTurnId, owner);
    releaseRecoveredTurnReconciliation(requestTurnId);
    setCancellingTurnIds((current) =>
      timelineRequestIsCurrent(requestThreadId, requestFence)
        ? { ...current, [requestTurnId]: true }
        : current,
    );
    setErrorsByThread((current) => {
      if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
      const next = { ...current };
      delete next[requestThreadId];
      return next;
    });
    try {
      await turnPort.cancelTurn({
        turnId: requestTurnId,
      });
      if (
        cancelGuardsRef.current.get(operationKey) !== owner ||
        !timelineRequestIsCurrent(requestThreadId, requestFence)
      ) {
        releaseCancellation(owner);
        return;
      }
      if (!mountedRef.current || currentThreadIdRef.current !== requestThreadId) return;
      // ACK 只代表服务端接纳；无论 ACK status 是否终态都重读一次，覆盖 terminal event 丢失。
      useTimelineStore.getState().requestThreadResync(requestThreadId);
      scheduleCancelReconciliation(owner);
    } catch (error: unknown) {
      if (
        !mountedRef.current ||
        cancelGuardsRef.current.get(operationKey) !== owner ||
        !timelineRequestIsCurrent(requestThreadId, requestFence)
      ) {
        releaseCancellation(owner);
        return;
      }
      const errorCode = cancellationErrorCode(error);
      if (currentThreadIdRef.current !== requestThreadId) {
        releaseCancellation(owner);
        return;
      }
      if (cancellationNeedsThreadResync(errorCode)) {
        useTimelineStore.getState().requestThreadResync(requestThreadId);
      }
      releaseCancellation(owner);
      if (mountedRef.current) {
        const message = cancellationFailureMessage(errorCode);
        if (onTransientError === undefined) {
          setErrorsByThread((current) => ({ ...current, [requestThreadId]: message }));
        } else if (currentThreadIdRef.current === requestThreadId) {
          onTransientError(message);
        }
      }
    }
  }, [
    blockingTurnId,
    onTransientError,
    releaseCancellation,
    releaseRecoveredTurnReconciliation,
    scheduleCancelReconciliation,
    threadId,
    turnPort,
  ]);

  /**
   * Approval 使用请求携带的 revision CAS；失败反馈和 single-flight 释放都受 Thread、Runtime
   * 与 approval owner 约束，避免关闭后旧响应污染同名新实例。@author kongweiguang
   */
  const approve = useCallback(
    async (approval: TimelineApproval, decision: "approve" | "deny"): Promise<void> => {
      const requestFence = captureTimelineRuntimeFence();
      if (!timelineRequestIsCurrent(approval.threadId, requestFence) || requestFence === undefined)
        return;
      const operationKey = threadRuntimeOperationKey(
        approval.threadId,
        requestFence,
        approval.approvalId,
      );
      if (approvalGuardsRef.current.has(operationKey)) return;
      const owner: ThreadTurnOperationOwner = {
        key: operationKey,
        threadId: approval.threadId,
        turnId: approval.turnId,
        fence: requestFence,
        token: Symbol("approval-response"),
      };
      approvalGuardsRef.current.set(operationKey, owner);
      setErrorsByThread((current) => {
        if (!timelineRequestIsCurrent(approval.threadId, requestFence)) return current;
        const next = { ...current };
        delete next[approval.threadId];
        return next;
      });
      try {
        await turnPort.approvalRespond({
          approvalId: approval.approvalId,
          turnId: approval.turnId,
          decision,
          expectedThreadRevision: approval.threadRevision,
        });
      } catch {
        if (
          mountedRef.current &&
          approvalGuardsRef.current.get(operationKey) === owner &&
          timelineRequestIsCurrent(approval.threadId, requestFence)
        ) {
          setErrorsByThread((current) =>
            timelineRequestIsCurrent(approval.threadId, requestFence)
              ? { ...current, [approval.threadId]: "审批响应失败，请确认 Turn 状态后重试。" }
              : current,
          );
        }
      } finally {
        if (approvalGuardsRef.current.get(operationKey) === owner)
          approvalGuardsRef.current.delete(operationKey);
      }
    },
    [turnPort],
  );

  /**
   * Thread 偏好更新保持 single-flight，并只调用 CAS 窄端口；active Turn 期间也允许变更，
   * App Server 会在下一次 Provider 请求安全点解析新偏好，已发出的请求不受影响；异步结果
   * 只释放自己的 Runtime owner，旧完成不能清除同 ID 新会话的 busy/error 状态。@author kongweiguang
   */
  const updatePreference = useCallback(
    async (next: {
      providerId: string;
      modelId: string;
      reasoningLevel: ReasoningLevel | null;
      accessMode: ConversationAccessMode;
      collaborationMode: ConversationCollaborationMode;
    }): Promise<void> => {
      const requestThreadId = threadId;
      const requestFence = captureTimelineRuntimeFence();
      if (
        requestThreadId === undefined ||
        preferences === undefined ||
        requestFence === undefined ||
        !timelineRequestIsCurrent(requestThreadId, requestFence)
      )
        return;
      if (
        preferences.providerId === next.providerId &&
        preferences.modelId === next.modelId &&
        preferences.reasoningLevel === next.reasoningLevel &&
        preferences.accessMode === next.accessMode &&
        preferences.collaborationMode === next.collaborationMode
      )
        return;
      const operationKey = threadRuntimeOperationKey(requestThreadId, requestFence, "preferences");
      if (preferenceGuardsRef.current.has(operationKey)) return;
      const operationToken = Symbol("thread-preferences");
      preferenceGuardsRef.current.set(operationKey, operationToken);
      setPreferenceBusyByThread((current) =>
        timelineRequestIsCurrent(requestThreadId, requestFence)
          ? { ...current, [requestThreadId]: true }
          : current,
      );
      setPreferenceErrorsByThread((current) => {
        if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
        if (current[requestThreadId] === undefined) return current;
        const updated = { ...current };
        delete updated[requestThreadId];
        return updated;
      });
      try {
        await preferencesPort.updatePreferences(next);
      } catch {
        if (
          mountedRef.current &&
          preferenceGuardsRef.current.get(operationKey) === operationToken &&
          timelineRequestIsCurrent(requestThreadId, requestFence)
        ) {
          setPreferenceErrorsByThread((current) =>
            timelineRequestIsCurrent(requestThreadId, requestFence)
              ? { ...current, [requestThreadId]: "会话偏好更新失败，请刷新会话状态后重试。" }
              : current,
          );
        }
      } finally {
        if (preferenceGuardsRef.current.get(operationKey) === operationToken)
          preferenceGuardsRef.current.delete(operationKey);
        if (mountedRef.current && timelineRequestIsCurrent(requestThreadId, requestFence)) {
          setPreferenceBusyByThread((current) => {
            if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
            if (current[requestThreadId] === undefined) return current;
            const updated = { ...current };
            delete updated[requestThreadId];
            return updated;
          });
        }
      }
    },
    [preferences, preferencesPort, threadId],
  );

  /** 模型变化同步收敛不受支持的 reasoning，避免保存悬空档位。 */
  const changeModel = useCallback(
    async (selectionValue: string): Promise<void> => {
      const model = models.find((candidate) => candidate.value === selectionValue);
      if (model === undefined || preferences === undefined) return;
      const reasoningLevel =
        preferences.reasoningLevel !== null &&
        model.reasoningLevelMap[preferences.reasoningLevel] !== undefined
          ? preferences.reasoningLevel
          : model.defaultReasoningLevel;
      await updatePreference({
        providerId: model.providerId,
        modelId: model.modelId,
        reasoningLevel,
        accessMode: preferences.accessMode,
        collaborationMode: preferences.collaborationMode,
      });
    },
    [models, preferences, updatePreference],
  );

  /** Reasoning 在下一次 Provider 请求安全点生效；null 表示沿用模型默认而不是关闭模型思考。 */
  const changeReasoning = useCallback(
    async (reasoningLevel: ReasoningLevel | null): Promise<void> => {
      if (preferences === undefined) return;
      await updatePreference({
        providerId: preferences.providerId,
        modelId: preferences.modelId,
        reasoningLevel,
        accessMode: preferences.accessMode,
        collaborationMode: preferences.collaborationMode,
      });
    },
    [preferences, updatePreference],
  );

  /** Access mode 是 Thread 默认值；审批卡的既有 revision 和状态不在此改写。 */
  const changeAccessMode = useCallback(
    async (accessMode: ConversationAccessMode): Promise<void> => {
      if (preferences === undefined) return;
      await updatePreference({
        providerId: preferences.providerId,
        modelId: preferences.modelId,
        reasoningLevel: preferences.reasoningLevel,
        accessMode,
        collaborationMode: preferences.collaborationMode,
      });
    },
    [preferences, updatePreference],
  );

  /** Collaboration mode 只改变下一轮协作模板，不批准计划，也不扩大当前 AccessMode。 */
  const changeCollaborationMode = useCallback(
    async (collaborationMode: ConversationCollaborationMode): Promise<void> => {
      if (preferences === undefined) return;
      await updatePreference({
        providerId: preferences.providerId,
        modelId: preferences.modelId,
        reasoningLevel: preferences.reasoningLevel,
        accessMode: preferences.accessMode,
        collaborationMode,
      });
    },
    [preferences, updatePreference],
  );

  /** 默认恢复通过一次 CAS 原子更新模型、推理和访问模式，避免连续请求形成半恢复状态。 */
  const resetPreferences = useCallback(
    async (
      selection: ConversationModelSelection,
      accessMode: ConversationAccessMode,
    ): Promise<void> => {
      const model = models.find(
        (candidate) =>
          candidate.providerId === selection.providerId && candidate.modelId === selection.modelId,
      );
      if (model === undefined) return;
      const reasoningLevel =
        selection.reasoningLevel !== null &&
        model.reasoningLevelMap[selection.reasoningLevel] !== undefined
          ? selection.reasoningLevel
          : model.defaultReasoningLevel;
      await updatePreference({
        providerId: model.providerId,
        modelId: model.modelId,
        reasoningLevel,
        accessMode,
        collaborationMode: preferences?.collaborationMode ?? "default",
      });
    },
    [models, preferences, updatePreference],
  );

  /**
   * 启动一个 caller-owned Channel operation；事件始终写回点击时的 Thread，切换会话不会把
   * 晚到进度投影到当前草稿，command-level 失败则只写脱敏的可恢复反馈。
   * @author kongweiguang
   */
  const runAttachmentImport = useCallback(
    async (
      invoke: (
        operationId: string,
        onEvent: (event: ConversationAttachmentImportEvent) => void,
        requestFence: TimelineRuntimeFence,
      ) => Promise<unknown>,
      onResult?: (
        result: unknown,
        requestThreadId: string,
        requestFence: TimelineRuntimeFence,
      ) => void,
    ): Promise<void> => {
      const requestThreadId = threadId;
      const requestFence = captureTimelineRuntimeFence();
      if (
        requestThreadId === undefined ||
        requestFence === undefined ||
        attachmentPort === undefined ||
        !timelineRequestIsCurrent(requestThreadId, requestFence)
      )
        return;
      const operationId = createAttachmentOperationId();
      const operationKey = threadRuntimeOperationKey(requestThreadId, requestFence, operationId);
      const operationToken = Symbol("attachment-import");
      attachmentOperationGuardsRef.current.set(operationKey, operationToken);
      setErrorsByThread((current) => {
        if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
        const next = { ...current };
        delete next[requestThreadId];
        return next;
      });
      const onEvent = (event: ConversationAttachmentImportEvent): void => {
        if (
          !mountedRef.current ||
          event.operationId !== operationId ||
          attachmentOperationGuardsRef.current.get(operationKey) !== operationToken ||
          !timelineRequestIsCurrent(requestThreadId, requestFence)
        )
          return;
        if (event.kind === "completed") {
          const sizes =
            readyAttachmentSizesRef.current[requestThreadId] ??
            (readyAttachmentSizesRef.current[requestThreadId] = new Map());
          const knownSize = sizes.get(event.attachment.attachmentId);
          const totalBytes = [...sizes.values()].reduce((total, size) => total + size, 0);
          if (
            knownSize === undefined &&
            (sizes.size >= MAX_DRAFT_ATTACHMENTS ||
              totalBytes + event.attachment.sizeBytes > MAX_DRAFT_ATTACHMENT_BYTES)
          ) {
            setAttachmentDraftsByThread((current) => {
              if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
              return {
                ...current,
                [requestThreadId]: (current[requestThreadId] ?? []).filter(
                  (item) => item.itemId !== event.itemId,
                ),
              };
            });
            setErrorsByThread((current) =>
              timelineRequestIsCurrent(requestThreadId, requestFence)
                ? {
                    ...current,
                    [requestThreadId]: "每轮最多添加 10 个附件，总大小不能超过 250 MiB。",
                  }
                : current,
            );
            const overflowKey = threadRuntimeOperationKey(
              requestThreadId,
              requestFence,
              event.attachment.attachmentId,
            );
            if (!overflowDiscardGuardsRef.current.has(overflowKey)) {
              const overflowToken = Symbol("attachment-overflow-discard");
              overflowDiscardGuardsRef.current.set(overflowKey, overflowToken);
              void attachmentPort
                .discardAttachment({ attachmentId: event.attachment.attachmentId })
                .catch(() => {
                  if (mountedRef.current && timelineRequestIsCurrent(requestThreadId, requestFence))
                    setErrorsByThread((current) =>
                      timelineRequestIsCurrent(requestThreadId, requestFence)
                        ? {
                            ...current,
                            [requestThreadId]: "超出限制的附件暂时无法清理，将由附件回收机制处理。",
                          }
                        : current,
                    );
                })
                .finally(() => {
                  if (overflowDiscardGuardsRef.current.get(overflowKey) === overflowToken)
                    overflowDiscardGuardsRef.current.delete(overflowKey);
                });
            }
            return;
          }
          sizes.set(event.attachment.attachmentId, event.attachment.sizeBytes);
        }
        setAttachmentDraftsByThread((current) => {
          if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
          const observed = current[requestThreadId] ?? [];
          const updated = applyAttachmentImportEvent(observed, event);
          if (updated === observed) return current;
          if (event.kind !== "completed") return { ...current, [requestThreadId]: updated };
          const seen = new Set<string>();
          return {
            ...current,
            [requestThreadId]: updated.filter((item) => {
              if (item.state !== "ready") return true;
              if (seen.has(item.attachmentId)) return false;
              seen.add(item.attachmentId);
              return true;
            }),
          };
        });
        if (event.kind === "completed" || event.kind === "failed" || event.kind === "cancelled")
          if (attachmentOperationGuardsRef.current.get(operationKey) === operationToken)
            attachmentOperationGuardsRef.current.delete(operationKey);
      };
      try {
        const result = await invoke(operationId, onEvent, requestFence);
        if (
          mountedRef.current &&
          attachmentOperationGuardsRef.current.get(operationKey) === operationToken &&
          timelineRequestIsCurrent(requestThreadId, requestFence)
        )
          onResult?.(result, requestThreadId, requestFence);
        const outcome = (result as { outcome?: unknown } | undefined)?.outcome;
        if (
          onResult !== undefined &&
          (outcome === "busy" || outcome === "nothing_importable") &&
          attachmentOperationGuardsRef.current.get(operationKey) === operationToken
        )
          attachmentOperationGuardsRef.current.delete(operationKey);
      } catch {
        if (
          mountedRef.current &&
          attachmentOperationGuardsRef.current.get(operationKey) === operationToken &&
          timelineRequestIsCurrent(requestThreadId, requestFence)
        ) {
          setAttachmentDraftsByThread((current) => {
            if (!timelineRequestIsCurrent(requestThreadId, requestFence)) return current;
            return {
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? []).map((item) =>
                item.state === "importing" && item.operationId === operationId
                  ? {
                      state: "failed",
                      operationId,
                      attemptId: item.attemptId,
                      itemId: item.itemId,
                      fileName: item.fileName,
                      sizeBytes: item.sizeBytes,
                      mediaKind: item.mediaKind,
                      mediaType: item.mediaType,
                      code: "IMPORT_COMMAND_FAILED",
                      message: "附件导入中断，请重试。",
                      retryable: true,
                    }
                  : item,
              ),
            };
          });
          setErrorsByThread((current) =>
            timelineRequestIsCurrent(requestThreadId, requestFence)
              ? { ...current, [requestThreadId]: "附件导入失败，请检查文件后重试。" }
              : current,
          );
        }
        if (attachmentOperationGuardsRef.current.get(operationKey) === operationToken)
          attachmentOperationGuardsRef.current.delete(operationKey);
      }
    },
    [attachmentPort, threadId],
  );

  /** Picker、drop 和 clipboard 仅决定来源，状态收敛完全复用同一 Channel reducer。@author kongweiguang */
  const importAttachments = useCallback(
    () =>
      runAttachmentImport(
        (operationId, onEvent) =>
          attachmentPort?.pickerImport({ operationId, onEvent }) ?? Promise.resolve(),
      ),
    [attachmentPort, runAttachmentImport],
  );
  const importDroppedAttachments = useCallback(
    (dropToken: string) =>
      runAttachmentImport(
        (operationId, onEvent) =>
          attachmentPort?.dropImport({ operationId, dropToken, onEvent }) ?? Promise.resolve(),
      ),
    [attachmentPort, runAttachmentImport],
  );
  const importClipboard = useCallback(
    () =>
      runAttachmentImport(
        (operationId, onEvent) =>
          attachmentPort?.clipboardImport({ operationId, onEvent }) ??
          Promise.resolve({ outcome: "nothing_importable" as const }),
        (result, requestThreadId, requestFence) => {
          const outcome = (result as { outcome?: unknown }).outcome;
          const notice =
            outcome === "busy"
              ? "剪贴板正被其他应用占用，请稍后重试。"
              : outcome === "nothing_importable"
                ? "剪贴板中没有可导入的文件或图片。"
                : "已从剪贴板添加附件。";
          setClipboardNoticesByThread((current) =>
            timelineRequestIsCurrent(requestThreadId, requestFence)
              ? { ...current, [requestThreadId]: notice }
              : current,
          );
        },
      ),
    [attachmentPort, runAttachmentImport],
  );

  /** Retry 保留 itemId 的视觉位置并换用新 operation，Rust attempt 仍是唯一源能力。@author kongweiguang */
  const retryAttachment = useCallback(
    async (itemId: string): Promise<void> => {
      const requestThreadId = threadId;
      const failed =
        requestThreadId === undefined
          ? undefined
          : (attachmentDraftsByThread[requestThreadId] ?? []).find(
              (item): item is Extract<ConversationAttachmentDraftItem, { state: "failed" }> =>
                item.itemId === itemId && item.state === "failed",
            );
      if (failed === undefined || !failed.retryable || attachmentPort === undefined) return;
      await runAttachmentImport(async (operationId, onEvent, requestFence) => {
        setAttachmentDraftsByThread((current) => {
          if (!timelineRequestIsCurrent(requestThreadId!, requestFence)) return current;
          return {
            ...current,
            [requestThreadId!]: (current[requestThreadId!] ?? []).map((item) =>
              item.itemId === itemId
                ? {
                    state: "importing",
                    operationId,
                    attemptId: failed.attemptId,
                    itemId,
                    fileName: failed.fileName,
                    sizeBytes: failed.sizeBytes,
                    mediaKind: failed.mediaKind,
                    mediaType: failed.mediaType,
                    phase: "copying",
                    bytesCopied: 0,
                    totalBytes: failed.sizeBytes,
                  }
                : item,
            ),
          };
        });
        await attachmentPort.retryImport({ operationId, attemptId: failed.attemptId, onEvent });
      });
    },
    [attachmentDraftsByThread, attachmentPort, runAttachmentImport, threadId],
  );

  /**
   * X 的语义按状态区分：importing 协作取消、failed 释放短期 attempt、ready 先进入 removing
   * 再丢弃 App Server 草稿；任何失败都保留可见对象供用户恢复。await 返回只能写入仍拥有请求的
   * SID+generation+Thread+token，close tombstone 先于附件清理生效，旧结果不得复活 projection。
   * @author kongweiguang
   */
  const removeAttachment = useCallback(
    async (itemId: string): Promise<void> => {
      const requestThreadId = threadId;
      const requestFence = captureTimelineRuntimeFence();
      const item =
        requestThreadId === undefined
          ? undefined
          : (attachmentDraftsByThread[requestThreadId] ?? []).find(
              (candidate) => candidate.itemId === itemId,
            );
      if (
        requestThreadId === undefined ||
        item === undefined ||
        item.state === "removing" ||
        requestFence === undefined ||
        attachmentPort === undefined ||
        !timelineRequestIsCurrent(requestThreadId, requestFence)
      )
        return;
      const operationKey = threadRuntimeOperationKey(requestThreadId, requestFence, itemId);
      if (attachmentDiscardGuardsRef.current.has(operationKey)) return;
      const operationToken = Symbol("attachment-remove");
      attachmentDiscardGuardsRef.current.set(operationKey, operationToken);
      /** React updater 可能晚于 close ACK 调度，故每次实际写入都重新验证同一请求 owner。@author kongweiguang */
      const ownsRemoval = (): boolean =>
        mountedRef.current &&
        attachmentDiscardGuardsRef.current.get(operationKey) === operationToken &&
        timelineRequestIsCurrent(requestThreadId, requestFence);
      try {
        if (item.state === "importing") {
          setAttachmentDraftsByThread((current) => {
            if (!ownsRemoval()) return current;
            return {
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? []).map((candidate) =>
                candidate.itemId === itemId && candidate.state === "importing"
                  ? { ...candidate, cancelRequested: true }
                  : candidate,
              ),
            };
          });
          await attachmentPort.cancelImport({
            operationId: item.operationId,
            itemId: item.itemId,
          });
          return;
        }
        if (item.state === "failed") {
          await attachmentPort.discardAttempt({ attemptId: item.attemptId });
        } else if (item.historyBound !== true) {
          const ready = item;
          setAttachmentDraftsByThread((current) => {
            if (!ownsRemoval()) return current;
            return {
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? []).map((candidate) =>
                candidate.itemId === itemId
                  ? {
                      state: "removing",
                      itemId,
                      fileName: ready.fileName,
                      sizeBytes: ready.sizeBytes,
                      mediaKind: ready.mediaKind,
                      mediaType: ready.mediaType,
                      thumbnailUrl: ready.thumbnailUrl,
                    }
                  : candidate,
              ),
            };
          });
          await attachmentPort.discardAttachment({ attachmentId: ready.attachmentId });
          if (!ownsRemoval()) return;
          readyAttachmentSizesRef.current[requestThreadId]?.delete(ready.attachmentId);
          onAttachmentRemoved?.(ready.attachmentId);
        }
        if (!ownsRemoval()) return;
        setAttachmentDraftsByThread((current) => {
          if (!ownsRemoval()) return current;
          return {
            ...current,
            [requestThreadId]: (current[requestThreadId] ?? []).filter(
              (candidate) => candidate.itemId !== itemId,
            ),
          };
        });
      } catch {
        if (ownsRemoval()) {
          if (item.state === "ready")
            setAttachmentDraftsByThread((current) => {
              if (!ownsRemoval()) return current;
              return {
                ...current,
                [requestThreadId]: (current[requestThreadId] ?? []).map((candidate) =>
                  candidate.itemId === itemId ? item : candidate,
                ),
              };
            });
          else if (item.state === "importing")
            setAttachmentDraftsByThread((current) => {
              if (!ownsRemoval()) return current;
              return {
                ...current,
                [requestThreadId]: (current[requestThreadId] ?? []).map((candidate) =>
                  candidate.itemId === itemId && candidate.state === "importing"
                    ? { ...candidate, cancelRequested: false }
                    : candidate,
                ),
              };
            });
          setErrorsByThread((current) =>
            ownsRemoval()
              ? { ...current, [requestThreadId]: "附件暂时无法移除，请重试。" }
              : current,
          );
        }
      } finally {
        if (attachmentDiscardGuardsRef.current.get(operationKey) === operationToken)
          attachmentDiscardGuardsRef.current.delete(operationKey);
      }
    },
    [attachmentDraftsByThread, attachmentPort, onAttachmentRemoved, threadId],
  );

  /**
   * 仅当前 Runtime 的服务端 close ACK 可清理草稿；索引缺项时按实体 owner 补全 Turn/审批，
   * 并在延迟 React updater 中复核 tombstone，避免旧 ACK 擦除新 SID 的同名 Thread 状态。
   * 已绑定历史附件不属于 Renderer 回收范围。@author kongweiguang
   */
  const clearClosedThreads = useCallback(
    (threadIds: readonly string[], fence: TimelineRuntimeFence): boolean => {
      const ids = [...new Set(threadIds.filter((id) => id.length > 0))];
      const before = useTimelineStore.getState();
      if (ids.length === 0 || !before.closeThreads(ids, fence)) return false;
      const closed = new Set(ids);
      const turnIds = new Set([
        ...ids.flatMap((id) => before.turnIdsByThread[id] ?? []),
        ...Object.values(before.turns)
          .filter((turn) => closed.has(turn.threadId))
          .map((turn) => turn.turnId),
      ]);
      const threadByTurnId = new Map<string, string>();
      for (const id of ids)
        for (const turnId of before.turnIdsByThread[id] ?? []) threadByTurnId.set(turnId, id);
      for (const turn of Object.values(before.turns))
        if (closed.has(turn.threadId)) threadByTurnId.set(turn.turnId, turn.threadId);
      const approvalIds = new Set([
        ...ids.flatMap((id) => before.approvalIdsByThread[id] ?? []),
        ...Object.entries(before.approvalsById)
          .filter(([, entry]) => closed.has(entry.threadId))
          .map(([approvalId]) => approvalId),
      ]);
      const threadByApprovalId = new Map<string, string>();
      for (const id of ids)
        for (const approvalId of before.approvalIdsByThread[id] ?? [])
          threadByApprovalId.set(approvalId, id);
      for (const [approvalId, entry] of Object.entries(before.approvalsById))
        if (closed.has(entry.threadId)) threadByApprovalId.set(approvalId, entry.threadId);
      const inputIds = new Set([
        ...[...turnIds].flatMap(
          (turnId) => before.inputQueueByTurn[turnId]?.items.map((item) => item.inputId) ?? [],
        ),
        ...Object.entries(pendingQueuedInputsByThread)
          .filter(([threadId]) => closed.has(threadId))
          .flatMap(([, items]) => items.map((item) => item.inputId)),
        ...[...queueInputOwnerByIdRef.current]
          .filter(([, threadId]) => closed.has(threadId))
          .map(([inputId]) => inputId),
      ]);
      const closedAttachmentItems = ids.flatMap((id) =>
        (attachmentDraftsByThread[id] ?? []).map((item) => ({ threadId: id, item })),
      );
      const closedTurnSet = new Set(turnIds);
      const clearTurnMap = <T>(record: Record<string, T>): Record<string, T> =>
        Object.fromEntries(Object.entries(record).filter(([id]) => !closedTurnSet.has(id)));
      /** 交错 React updater 只在这次精确 close 仍属于原 Runtime 时清理本地映射。@author kongweiguang */
      const closeStillCurrent = (): boolean => {
        const state = useTimelineStore.getState();
        return (
          state.handshake.phase === "ready" &&
          state.handshake.generation === fence.generation &&
          state.serverInstanceId === fence.serverInstanceId &&
          ids.every((id) => state.isThreadClosed(id))
        );
      };
      for (const id of ids) {
        const submitOwner = submitOwnerByThreadRef.current.get(id);
        if (
          submitOwner !== undefined &&
          submitOwner.fence?.generation === fence.generation &&
          submitOwner.fence.serverInstanceId === fence.serverInstanceId
        ) {
          submitOwnerByThreadRef.current.delete(id);
          submitGuardsRef.current.delete(id);
        }
        readyAttachmentSizesRef.current[id]?.clear();
        delete readyAttachmentSizesRef.current[id];
        delete failedQueuedTextsRef.current[id];
        preferenceGuardsRef.current.delete(threadRuntimeOperationKey(id, fence, "preferences"));
        for (const [turnId, owner] of cancelThreadByTurnRef.current)
          if (
            owner.threadId === id &&
            owner.fence.serverInstanceId === fence.serverInstanceId &&
            owner.fence.generation === fence.generation
          ) {
            cancelThreadByTurnRef.current.delete(turnId);
            cancelGuardsRef.current.delete(owner.key);
          }
        for (const [turnId, pending] of recoveredTurnReconciliationRef.current)
          if (
            pending.threadId === id &&
            pending.serverInstanceId === fence.serverInstanceId &&
            pending.generation === fence.generation
          ) {
            clearTimeout(pending.timer);
            recoveredTurnReconciliationRef.current.delete(turnId);
          }
        queueMutationLanesRef.current.delete(threadMutationLaneKey(id, fence));
      }
      for (const approvalId of approvalIds) {
        const approvalThreadId = threadByApprovalId.get(approvalId);
        if (approvalThreadId !== undefined)
          approvalGuardsRef.current.delete(
            threadRuntimeOperationKey(approvalThreadId, fence, approvalId),
          );
      }
      for (const turnId of turnIds) {
        const turnThreadId = threadByTurnId.get(turnId);
        if (turnThreadId !== undefined) {
          const operationKey = threadRuntimeOperationKey(turnThreadId, fence, turnId);
          cancelGuardsRef.current.delete(operationKey);
          resumeGuardsRef.current.delete(operationKey);
          const cancelOwner = cancelThreadByTurnRef.current.get(turnId);
          if (
            cancelOwner?.key === operationKey &&
            cancelOwner.fence.serverInstanceId === fence.serverInstanceId &&
            cancelOwner.fence.generation === fence.generation
          )
            cancelThreadByTurnRef.current.delete(turnId);
        }
        const timer = cancelReconciliationRef.current.get(turnId);
        if (
          timer !== undefined &&
          closed.has(timer.owner.threadId) &&
          timer.owner.fence.serverInstanceId === fence.serverInstanceId &&
          timer.owner.fence.generation === fence.generation
        ) {
          clearTimeout(timer.timer);
          cancelReconciliationRef.current.delete(turnId);
        }
      }
      for (const inputId of inputIds) queueInputOwnerByIdRef.current.delete(inputId);
      for (const inputId of inputIds) queueActionOwnerByInputIdRef.current.delete(inputId);
      if (threadId !== undefined && closed.has(threadId))
        recoveredTurnReconciliationKeyRef.current = undefined;
      const removeThreads = <T>(record: Record<string, T>): Record<string, T> =>
        closeStillCurrent()
          ? Object.fromEntries(Object.entries(record).filter(([id]) => !closed.has(id)))
          : record;
      setDraftsByThread(removeThreads);
      setContextDraftsByThread(removeThreads);
      setAttachmentDraftsByThread(removeThreads);
      setEditingQuestionByThread(removeThreads);
      setPendingTurns((current) => {
        if (!closeStillCurrent()) return current;
        const next = removeThreads(current);
        pendingTurnsRef.current = next;
        return next;
      });
      setLocalSubmissionsByThread(removeThreads);
      setSendingThreadIds(removeThreads);
      setDraftRecoveryRevisionsByThread(removeThreads);
      setCancellingTurnIds((current) => (closeStillCurrent() ? clearTurnMap(current) : current));
      setResumingTurnIds((current) => (closeStillCurrent() ? clearTurnMap(current) : current));
      setErrorsByThread(removeThreads);
      setModelSettingsRequiredByThread(removeThreads);
      setClipboardNoticesByThread(removeThreads);
      setPendingQueuedInputsByThread(removeThreads);
      setPreferenceBusyByThread(removeThreads);
      setPreferenceErrorsByThread(removeThreads);
      setQueueActionsByInputId((current) =>
        closeStillCurrent()
          ? Object.fromEntries(Object.entries(current).filter(([id]) => !inputIds.has(id)))
          : current,
      );
      setQueueErrorsByInputId((current) =>
        closeStillCurrent()
          ? Object.fromEntries(Object.entries(current).filter(([id]) => !inputIds.has(id)))
          : current,
      );
      for (const { threadId: closedThreadId, item } of closedAttachmentItems) {
        if (!closeStillCurrent()) break;
        const discardKey = threadRuntimeOperationKey(closedThreadId, fence, item.itemId);
        attachmentDiscardGuardsRef.current.delete(discardKey);
        if (item.state === "importing") {
          const operationKey = threadRuntimeOperationKey(closedThreadId, fence, item.operationId);
          attachmentOperationGuardsRef.current.delete(operationKey);
        }
        if (item.state === "ready")
          overflowDiscardGuardsRef.current.delete(
            threadRuntimeOperationKey(closedThreadId, fence, item.attachmentId),
          );
        if (attachmentPort !== undefined) {
          const cleanup =
            item.state === "importing"
              ? attachmentPort.cancelImport({ operationId: item.operationId, itemId: item.itemId })
              : item.state === "failed"
                ? attachmentPort.discardAttempt({ attemptId: item.attemptId })
                : item.state === "ready" && item.historyBound !== true
                  ? attachmentPort.discardAttachment({ attachmentId: item.attachmentId })
                  : undefined;
          if (cleanup !== undefined) void cleanup.catch(() => undefined);
        }
      }
      return true;
    },
    [attachmentDraftsByThread, attachmentPort, pendingQueuedInputsByThread, threadId],
  );

  const hasActiveTurn = executingTurn !== undefined || pendingTurn !== undefined;
  const sending = threadId !== undefined && sendingThreadIds[threadId] === true;
  const cancelling = blockingTurnId !== undefined && cancellingTurnIds[blockingTurnId] === true;
  const resuming = suspendedTurn !== undefined && resumingTurnIds[suspendedTurn.turnId] === true;
  const attachmentDraftItems =
    threadId === undefined ? [] : (attachmentDraftsByThread[threadId] ?? []);
  const readyAttachments = attachmentDraftItems.filter(
    (item): item is Extract<ConversationAttachmentDraftItem, { state: "ready" }> =>
      item.state === "ready",
  );
  const queuedInputs: ConversationQueuedInputView[] = [
    ...(inputQueue?.items ?? []).map((item) => ({
      ...item,
      busyAction: queueActionsByInputId[item.inputId],
      error: queueErrorsByInputId[item.inputId],
    })),
    ...((threadId === undefined ? [] : pendingQueuedInputsByThread[threadId]) ?? []).map(
      (item) => ({
        ...item,
        kind: "follow_up" as const,
        inputRevision: 0,
        pending: true,
      }),
    ),
  ];
  return {
    draft: threadId === undefined ? "" : (draftsByThread[threadId] ?? ""),
    contextReferences: threadId === undefined ? [] : (contextDraftsByThread[threadId] ?? []),
    preferences,
    models,
    attachments: readyAttachments,
    attachmentDraftItems,
    activeTurn: hasActiveTurn,
    suspendedTurn: suspendedTurn !== undefined,
    continuationAvailable:
      continuationSource !== undefined && pendingTurn === undefined && blockingTurn === undefined,
    editingQuestion,
    editableSourceMessageId: editingQuestion ? undefined : reaskableSource?.itemId,
    disabled:
      !ready ||
      (blocked && suspendedTurn === undefined) ||
      workspaceId === undefined ||
      threadId === undefined ||
      preferences === undefined,
    preferenceBusy,
    importingAttachments: attachmentDraftItems.some((item) => item.state === "importing"),
    sending,
    draftRecoveryRevision:
      threadId === undefined ? 0 : (draftRecoveryRevisionsByThread[threadId] ?? 0),
    localSubmissions: threadId === undefined ? [] : (localSubmissionsByThread[threadId] ?? []),
    cancelling,
    resuming,
    modelSettingsRequired:
      threadId === undefined ? false : modelSettingsRequiredByThread[threadId] === true,
    error:
      threadId === undefined
        ? undefined
        : (errorsByThread[threadId] ?? preferenceErrorsByThread[threadId]),
    inputQueue,
    queuedInputs,
    queueAccepting: inputQueue?.accepting ?? (executingTurnId !== undefined && !suspendedTurn),
    updateDraft,
    updateContextReferences,
    changeModel,
    changeReasoning,
    changeAccessMode,
    changeCollaborationMode,
    resetPreferences,
    importAttachments,
    importDroppedAttachments,
    clipboardNotice: threadId === undefined ? undefined : clipboardNoticesByThread[threadId],
    importClipboard,
    retryAttachment,
    removeAttachment,
    send,
    continueReply,
    editQuestion,
    cancelEditQuestion,
    enqueue,
    prioritizeQueuedInput,
    updateQueuedInput,
    deleteQueuedInput,
    resume,
    cancel,
    approve,
    clearClosedThreads,
  };
}
