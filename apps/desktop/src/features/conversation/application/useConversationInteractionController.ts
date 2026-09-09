// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import type { InputQueue, QueuedInput } from "../domain/timelineContracts";
import type { AttachmentSummary } from "../domain/timelineContracts";
import type { TimelineApproval, TimelineTurn } from "../domain/timelineTypes";
import {
  contextReferenceIdentity,
  referenceToUserContent,
  type ConversationContextReference,
  type UserContentBlock,
} from "../domain/userContent";
import { useTimelineStore } from "./timelineStore";
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

export interface ConversationInteractionOptions {
  threadId: string | undefined;
  workspaceId: string | undefined;
  preferences: ConversationThreadPreferences | undefined;
  models: readonly ConversationModelOption[];
  ready: boolean;
  blocked: boolean;
  turnPort: ConversationTurnPort;
  planCreationPort?: ConversationPlanCreationPort;
  preferencesPort: ConversationPreferencesPort;
  attachmentPort?: ConversationAttachmentPort;
  onAttachmentRemoved?: (attachmentId: string) => void;
  onAttachmentsBound?: (threadId: string, attachmentIds: readonly string[]) => void;
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

/** operation identity 只关联当前进程中的 Channel，不承担服务端资源身份。 */
function createAttachmentOperationId(): string {
  return `op_${crypto.randomUUID()}`;
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

/**
 * Conversation application 独占 Draft、Turn single-flight、pending、取消、追加输入、审批和
 * 模型切换编排；所有状态按 Thread identity 隔离，外部 owner 只通过窄端口注入能力。
 */
export function useConversationInteractionController({
  threadId,
  workspaceId,
  preferences,
  models,
  ready,
  blocked,
  turnPort,
  planCreationPort,
  preferencesPort,
  attachmentPort,
  onAttachmentRemoved,
  onAttachmentsBound,
}: ConversationInteractionOptions): ConversationInteractionController {
  const [draftsByThread, setDraftsByThread] = useState<Record<string, string>>({});
  const [contextDraftsByThread, setContextDraftsByThread] = useState<
    Record<string, readonly ConversationContextReference[]>
  >({});
  const [attachmentDraftsByThread, setAttachmentDraftsByThread] = useState<
    Record<string, readonly ConversationAttachmentDraftItem[]>
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
  const queueMutationLanesRef = useRef(new Map<string, Promise<void>>());
  const failedQueuedTextsRef = useRef<Record<string, string[]>>({});
  const cancelGuardsRef = useRef(new Set<string>());
  const resumeGuardsRef = useRef(new Set<string>());
  const approvalGuardsRef = useRef(new Set<string>());
  const preferenceGuardsRef = useRef(new Set<string>());
  const attachmentOperationGuardsRef = useRef(new Set<string>());
  const attachmentDiscardGuardsRef = useRef(new Set<string>());
  const overflowDiscardGuardsRef = useRef(new Set<string>());
  const readyAttachmentSizesRef = useRef<Record<string, Map<string, number>>>({});
  const pendingTurnsRef = useRef<Record<string, ConversationAcceptedTurn>>({});
  const currentTurns = useTimelineStore((state) => state.turns);
  const currentThreads = useTimelineStore((state) => state.threads);
  const blockingTurn =
    threadId === undefined
      ? undefined
      : Object.values(currentTurns)
          .filter((turn) => turn.threadId === threadId && !TERMINAL_TURN_STATES.has(turn.status))
          .sort(
            (left, right) =>
              (left.startedAt ?? "").localeCompare(right.startedAt ?? "") ||
              left.turnId.localeCompare(right.turnId),
          )[0];
  const pendingTurn = threadId === undefined ? undefined : pendingTurns[threadId];
  const suspendedTurn = blockingTurn?.status === "suspended" ? blockingTurn : undefined;
  const executingTurn = blockingTurn?.status === "suspended" ? undefined : blockingTurn;
  const blockingTurnId = blockingTurn?.turnId ?? pendingTurn?.turnId;
  const blockingTurnRevision = blockingTurn?.threadRevision ?? pendingTurn?.threadRevision;
  const executingTurnId = executingTurn?.turnId ?? pendingTurn?.turnId;
  const inputQueue = useTimelineStore((state) =>
    blockingTurnId === undefined ? undefined : state.inputQueueByTurn[blockingTurnId],
  );
  const preferenceBusy = threadId !== undefined && preferenceBusyByThread[threadId] === true;

  /** 卸载后所有异步 continuation 只释放 ref guard，不再写 React 状态。 */
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
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

  /** 权威状态离开 suspended 后释放 Resume single-flight；同一 Turn 日后再次中断仍可重新授权。 */
  useEffect(() => {
    setResumingTurnIds((current) => {
      let changed = false;
      const next = { ...current };
      for (const turnId of Object.keys(current)) {
        if (currentTurns[turnId]?.status === "suspended") continue;
        resumeGuardsRef.current.delete(turnId);
        delete next[turnId];
        changed = true;
      }
      return changed ? next : current;
    });
  }, [currentTurns]);

  /** Draft 只写入当前有效 Thread，并在用户继续编辑时清除该 Thread 的旧反馈。 */
  const updateDraft = useCallback(
    (text: string): void => {
      if (threadId === undefined) return;
      setDraftsByThread((current) => ({ ...current, [threadId]: text }));
      setErrorsByThread((current) => {
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

  /** Context Chip 与文本共享 Thread 隔离，但独立更新避免 textarea 编辑覆盖已选引用。 */
  const updateContextReferences = useCallback(
    (references: readonly ConversationContextReference[]): void => {
      if (threadId === undefined) return;
      const seen = new Set<string>();
      const unique = references.filter((reference) => {
        const identity = contextReferenceIdentity(reference);
        if (seen.has(identity)) return false;
        seen.add(identity);
        return true;
      });
      setContextDraftsByThread((current) => ({ ...current, [threadId]: unique }));
      setErrorsByThread((current) => {
        if (current[threadId] === undefined) return current;
        const next = { ...current };
        delete next[threadId];
        return next;
      });
    },
    [threadId],
  );

  /**
   * 同一 Thread 的 turn/start 严格 single-flight；提交意图成立后立即形成消息记录并清空输入。
   * ACK 后由权威 Timeline 接管，失败则把错误固定在原消息旁，不把已发送内容退回 Composer。
   */
  const send = useCallback(
    async ({ text, attachmentIds, contextReferences }: ConversationSubmit): Promise<void> => {
      const requestThreadId = threadId;
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
      if (
        requestThreadId === undefined ||
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
        submitGuardsRef.current.has(requestThreadId) ||
        pendingTurnsRef.current[requestThreadId] !== undefined
      )
        return;
      const submittedAt = new Date().toISOString();
      const submissionId = `submission:${crypto.randomUUID()}`;
      const submittedAttachmentSet = new Set(submittedAttachments);
      const submittedAttachmentItems = availableAttachments.filter((attachment) =>
        submittedAttachmentSet.has(attachment.attachmentId),
      );
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
        if (preferences.collaborationMode === "plan") {
          const expectedThreadRevision = currentThreads[requestThreadId]?.revision;
          if (
            expectedThreadRevision === undefined ||
            planCreationPort === undefined ||
            !(await planCreationPort.create(requestThreadId, submittedText, expectedThreadRevision))
          ) {
            throw new Error("plan artifact was not acknowledged");
          }
        }
        // Runtime port 负责真正接纳；即时 UI 反馈不伪造 Turn identity 或持久 Timeline 事实。
        const projectionAttachments = submittedAttachmentItems.map((attachment) => ({
          attachmentId: attachment.attachmentId,
          displayName: attachment.fileName,
          sizeBytes: attachment.sizeBytes,
          mediaKind: attachment.mediaKind,
          mediaType: attachment.mediaType ?? "application/octet-stream",
        }));
        const accepted = await turnPort.submitTurn({
          threadId: requestThreadId,
          content: [
            ...submittedReferences.map(referenceToUserContent),
            ...submittedAttachments.map((attachmentId) => ({
              type: "attachment" as const,
              attachmentId,
            })),
            ...(submittedText === "" ? [] : [{ type: "text" as const, text: submittedText }]),
          ],
          ...(projectionAttachments.length === 0 ? {} : { projectionAttachments }),
        });
        if (!mountedRef.current) return;
        if (submittedAttachments.length > 0) {
          const readySizes = readyAttachmentSizesRef.current[requestThreadId];
          for (const attachmentId of submittedAttachments) readySizes?.delete(attachmentId);
          onAttachmentsBound?.(requestThreadId, submittedAttachments);
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
      } catch (error) {
        if (mountedRef.current) {
          const code =
            typeof error === "object" && error !== null && "code" in error
              ? String((error as { code: unknown }).code)
              : undefined;
          const preserveDraft = new Set([
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
                code === "CONTENT_TOO_LARGE"
                  ? "消息内容过大，请精简后重试。"
                  : "引用已变化，请调整后重试。",
            }));
            setDraftRecoveryRevisionsByThread((current) => ({
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? 0) + 1,
            }));
          }
        }
      } finally {
        submitGuardsRef.current.delete(requestThreadId);
        if (mountedRef.current) {
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
      planCreationPort,
      onAttachmentsBound,
      preferenceBusy,
      preferences,
      ready,
      threadId,
      turnPort,
      workspaceId,
    ],
  );

  /**
   * 默认入队立即清空草稿并加入本地 pending 行；每个调用仍进入 Thread lane，因此相同文本连续
   * 点击不会被合并。失败文本按提交顺序插回且保留用户等待期间的新草稿。
   */
  const enqueue = useCallback(
    async ({ text, attachmentIds, contextReferences = [] }: ConversationSubmit): Promise<void> => {
      const requestThreadId = threadId;
      const requestTurnId = executingTurnId;
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
          requestThreadId,
          () => turnPort.enqueueTurnInput({ turnId: requestTurnId, content }),
        );
        // Runtime projection 通常已先写入；这里复用同一幂等 reducer，保证窄端口 fake/替代实现也收敛。
        useTimelineStore.getState().applyInputQueue(result.inputQueue);
        if (submittedAttachmentIds.length > 0) {
          const readySizes = readyAttachmentSizesRef.current[requestThreadId];
          for (const attachmentId of submittedAttachmentIds) readySizes?.delete(attachmentId);
          onAttachmentsBound?.(requestThreadId, submittedAttachmentIds);
        }
      } catch {
        if (mountedRef.current) {
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
        if (mountedRef.current) {
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
      executingTurnId,
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
      const requestTurnId = blockingTurnId;
      if (
        requestThreadId === undefined ||
        requestTurnId === undefined ||
        queueActionsByInputId[inputId] !== undefined
      )
        return;
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
          requestThreadId,
          () => {
            const identity = { turnId: requestTurnId, inputId, expectedInputRevision };
            if (action === "prioritize") return turnPort.prioritizeTurnInput(identity);
            if (action === "delete") return turnPort.deleteTurnInput(identity);
            return turnPort.updateTurnInput({ ...identity, content: [...(content ?? [])] });
          },
        );
        useTimelineStore.getState().applyInputQueue(result.inputQueue);
      } catch (error) {
        if (!mountedRef.current) return;
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
        if (mountedRef.current)
          setQueueActionsByInputId((current) => {
            if (current[inputId] === undefined) return current;
            const next = { ...current };
            delete next[inputId];
            return next;
          });
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
   * ACK 本身不被当作状态转换，避免用户重复点击产生第二次恢复授权。
   */
  const resume = useCallback(async (): Promise<void> => {
    const requestThreadId = threadId;
    const requestTurnId = suspendedTurn?.turnId;
    const requestRevision = suspendedTurn?.threadRevision;
    if (
      requestThreadId === undefined ||
      requestTurnId === undefined ||
      requestRevision === undefined ||
      cancelGuardsRef.current.has(requestTurnId) ||
      resumeGuardsRef.current.has(requestTurnId)
    )
      return;
    resumeGuardsRef.current.add(requestTurnId);
    setResumingTurnIds((current) => ({ ...current, [requestTurnId]: true }));
    setErrorsByThread((current) => {
      const next = { ...current };
      delete next[requestThreadId];
      return next;
    });
    try {
      await turnPort.resumeTurn({
        turnId: requestTurnId,
        expectedThreadRevision: requestRevision,
      });
    } catch (error) {
      resumeGuardsRef.current.delete(requestTurnId);
      if (mountedRef.current) {
        setResumingTurnIds((current) => {
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
        setErrorsByThread((current) => ({ ...current, [requestThreadId]: message }));
      }
    }
  }, [suspendedTurn, threadId, turnPort]);

  /**
   * 取消操作冻结点击时的 Turn 与 revision CAS，并按 Turn single-flight；事件终态仍是唯一
   * 完成事实，controller 不做 optimistic completion。
   */
  const cancel = useCallback(async (): Promise<void> => {
    const requestThreadId = threadId;
    const requestTurnId = blockingTurnId;
    const requestRevision = blockingTurnRevision;
    if (
      requestThreadId === undefined ||
      requestTurnId === undefined ||
      requestRevision === undefined ||
      resumeGuardsRef.current.has(requestTurnId) ||
      cancelGuardsRef.current.has(requestTurnId)
    )
      return;
    cancelGuardsRef.current.add(requestTurnId);
    setCancellingTurnIds((current) => ({ ...current, [requestTurnId]: true }));
    setErrorsByThread((current) => {
      const next = { ...current };
      delete next[requestThreadId];
      return next;
    });
    try {
      await turnPort.cancelTurn({ turnId: requestTurnId, expectedThreadRevision: requestRevision });
    } catch {
      if (mountedRef.current) {
        setErrorsByThread((current) => ({
          ...current,
          [requestThreadId]: "取消失败，请稍后重试。",
        }));
      }
    } finally {
      cancelGuardsRef.current.delete(requestTurnId);
      if (mountedRef.current) {
        setCancellingTurnIds((current) => {
          const next = { ...current };
          delete next[requestTurnId];
          return next;
        });
      }
    }
  }, [blockingTurnId, blockingTurnRevision, threadId, turnPort]);

  /** Approval 使用请求携带的 revision CAS，视图不能改写 identity，也不能重复提交同一审批。 */
  const approve = useCallback(
    async (approval: TimelineApproval, decision: "approve" | "deny"): Promise<void> => {
      if (approvalGuardsRef.current.has(approval.approvalId)) return;
      approvalGuardsRef.current.add(approval.approvalId);
      setErrorsByThread((current) => {
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
        if (mountedRef.current) {
          setErrorsByThread((current) => ({
            ...current,
            [approval.threadId]: "审批响应失败，请确认 Turn 状态后重试。",
          }));
        }
      } finally {
        approvalGuardsRef.current.delete(approval.approvalId);
      }
    },
    [turnPort],
  );

  /**
   * Thread 偏好更新保持 single-flight，并只调用 CAS 窄端口；active Turn 期间也允许变更，
   * App Server 会在下一次 Provider 请求安全点解析新偏好，已发出的请求不受影响。
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
      if (
        requestThreadId === undefined ||
        preferences === undefined ||
        preferenceGuardsRef.current.has(requestThreadId)
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
      preferenceGuardsRef.current.add(requestThreadId);
      setPreferenceBusyByThread((current) => ({ ...current, [requestThreadId]: true }));
      setPreferenceErrorsByThread((current) => {
        if (current[requestThreadId] === undefined) return current;
        const updated = { ...current };
        delete updated[requestThreadId];
        return updated;
      });
      try {
        await preferencesPort.updatePreferences(next);
      } catch {
        if (mountedRef.current) {
          setPreferenceErrorsByThread((current) => ({
            ...current,
            [requestThreadId]: "会话偏好更新失败，请刷新会话状态后重试。",
          }));
        }
      } finally {
        preferenceGuardsRef.current.delete(requestThreadId);
        if (mountedRef.current) {
          setPreferenceBusyByThread((current) => {
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
   */
  const runAttachmentImport = useCallback(
    async (
      invoke: (
        operationId: string,
        onEvent: (event: ConversationAttachmentImportEvent) => void,
      ) => Promise<unknown>,
      onResult?: (result: unknown, requestThreadId: string) => void,
    ): Promise<void> => {
      const requestThreadId = threadId;
      if (requestThreadId === undefined || attachmentPort === undefined) return;
      const operationId = createAttachmentOperationId();
      attachmentOperationGuardsRef.current.add(operationId);
      setErrorsByThread((current) => {
        const next = { ...current };
        delete next[requestThreadId];
        return next;
      });
      const onEvent = (event: ConversationAttachmentImportEvent): void => {
        if (!mountedRef.current || event.operationId !== operationId) return;
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
            setAttachmentDraftsByThread((current) => ({
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? []).filter(
                (item) => item.itemId !== event.itemId,
              ),
            }));
            setErrorsByThread((current) => ({
              ...current,
              [requestThreadId]: "每轮最多添加 10 个附件，总大小不能超过 250 MiB。",
            }));
            if (!overflowDiscardGuardsRef.current.has(event.attachment.attachmentId)) {
              overflowDiscardGuardsRef.current.add(event.attachment.attachmentId);
              void attachmentPort
                .discardAttachment({ attachmentId: event.attachment.attachmentId })
                .catch(() => {
                  if (mountedRef.current)
                    setErrorsByThread((current) => ({
                      ...current,
                      [requestThreadId]: "超出限制的附件暂时无法清理，将由附件回收机制处理。",
                    }));
                })
                .finally(() =>
                  overflowDiscardGuardsRef.current.delete(event.attachment.attachmentId),
                );
            }
            return;
          }
          sizes.set(event.attachment.attachmentId, event.attachment.sizeBytes);
        }
        setAttachmentDraftsByThread((current) => {
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
      };
      try {
        const result = await invoke(operationId, onEvent);
        if (mountedRef.current) onResult?.(result, requestThreadId);
      } catch {
        if (mountedRef.current) {
          setAttachmentDraftsByThread((current) => ({
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
          }));
          setErrorsByThread((current) => ({
            ...current,
            [requestThreadId]: "附件导入失败，请检查文件后重试。",
          }));
        }
      } finally {
        attachmentOperationGuardsRef.current.delete(operationId);
      }
    },
    [attachmentPort, threadId],
  );

  /** Picker、drop 和 clipboard 仅决定来源，状态收敛完全复用同一 Channel reducer。 */
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
        (result, requestThreadId) => {
          const outcome = (result as { outcome?: unknown }).outcome;
          const notice =
            outcome === "busy"
              ? "剪贴板正被其他应用占用，请稍后重试。"
              : outcome === "nothing_importable"
                ? "剪贴板中没有可导入的文件或图片。"
                : "已从剪贴板添加附件。";
          setClipboardNoticesByThread((current) => ({
            ...current,
            [requestThreadId]: notice,
          }));
        },
      ),
    [attachmentPort, runAttachmentImport],
  );

  /** Retry 保留 itemId 的视觉位置并换用新 operation，Rust attempt 仍是唯一源能力。 */
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
      await runAttachmentImport(async (operationId, onEvent) => {
        setAttachmentDraftsByThread((current) => ({
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
        }));
        await attachmentPort.retryImport({ operationId, attemptId: failed.attemptId, onEvent });
      });
    },
    [attachmentDraftsByThread, attachmentPort, runAttachmentImport, threadId],
  );

  /**
   * X 的语义按状态区分：importing 协作取消、failed 释放短期 attempt、ready 先进入 removing
   * 再丢弃 App Server 草稿；任何失败都保留可见对象供用户恢复。
   */
  const removeAttachment = useCallback(
    async (itemId: string): Promise<void> => {
      const requestThreadId = threadId;
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
        attachmentPort === undefined ||
        attachmentDiscardGuardsRef.current.has(itemId)
      )
        return;
      attachmentDiscardGuardsRef.current.add(itemId);
      try {
        if (item.state === "importing") {
          setAttachmentDraftsByThread((current) => ({
            ...current,
            [requestThreadId]: (current[requestThreadId] ?? []).map((candidate) =>
              candidate.itemId === itemId && candidate.state === "importing"
                ? { ...candidate, cancelRequested: true }
                : candidate,
            ),
          }));
          await attachmentPort.cancelImport({
            operationId: item.operationId,
            itemId: item.itemId,
          });
          return;
        }
        if (item.state === "failed") {
          await attachmentPort.discardAttempt({ attemptId: item.attemptId });
        } else {
          const ready = item;
          setAttachmentDraftsByThread((current) => ({
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
          }));
          await attachmentPort.discardAttachment({ attachmentId: ready.attachmentId });
          readyAttachmentSizesRef.current[requestThreadId]?.delete(ready.attachmentId);
          onAttachmentRemoved?.(ready.attachmentId);
        }
        if (!mountedRef.current) return;
        setAttachmentDraftsByThread((current) => ({
          ...current,
          [requestThreadId]: (current[requestThreadId] ?? []).filter(
            (candidate) => candidate.itemId !== itemId,
          ),
        }));
      } catch {
        if (mountedRef.current) {
          if (item.state === "ready")
            setAttachmentDraftsByThread((current) => ({
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? []).map((candidate) =>
                candidate.itemId === itemId ? item : candidate,
              ),
            }));
          else if (item.state === "importing")
            setAttachmentDraftsByThread((current) => ({
              ...current,
              [requestThreadId]: (current[requestThreadId] ?? []).map((candidate) =>
                candidate.itemId === itemId && candidate.state === "importing"
                  ? { ...candidate, cancelRequested: false }
                  : candidate,
              ),
            }));
          setErrorsByThread((current) => ({
            ...current,
            [requestThreadId]: "附件暂时无法移除，请重试。",
          }));
        }
      } finally {
        attachmentDiscardGuardsRef.current.delete(itemId);
      }
    },
    [attachmentDraftsByThread, attachmentPort, onAttachmentRemoved, threadId],
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
    enqueue,
    prioritizeQueuedInput,
    updateQueuedInput,
    deleteQueuedInput,
    resume,
    cancel,
    approve,
  };
}
