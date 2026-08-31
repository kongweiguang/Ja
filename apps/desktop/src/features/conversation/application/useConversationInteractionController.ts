// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import type { TimelineApproval, TimelineTurn } from "../domain/timelineTypes";
import { useTimelineStore } from "./timelineStore";
import type {
  ConversationAcceptedTurn,
  ConversationAccessMode,
  ConversationAttachment,
  ConversationAttachmentPort,
  ConversationModelOption,
  ConversationModelSelection,
  ConversationPreferencesPort,
  ConversationQueueMode,
  ConversationThreadPreferences,
  ReasoningLevel,
  ConversationSubmit,
  ConversationTurnPort,
} from "./ports";

const TERMINAL_TURN_STATES = new Set<TimelineTurn["status"]>(["completed", "failed", "cancelled"]);

export interface ConversationInteractionOptions {
  threadId: string | undefined;
  workspaceId: string | undefined;
  preferences: ConversationThreadPreferences | undefined;
  models: readonly ConversationModelOption[];
  ready: boolean;
  blocked: boolean;
  turnPort: ConversationTurnPort;
  preferencesPort: ConversationPreferencesPort;
  attachmentPort?: ConversationAttachmentPort;
}

/** Conversation 交互 controller 向组合层暴露的窄 view model 与 actions。 */
export interface ConversationInteractionController {
  draft: string;
  preferences: ConversationThreadPreferences | undefined;
  models: readonly ConversationModelOption[];
  attachments: readonly ConversationAttachment[];
  activeTurn: boolean;
  disabled: boolean;
  preferenceBusy: boolean;
  importingAttachments: boolean;
  sending: boolean;
  cancelling: boolean;
  error: string | undefined;
  queueStatus: string | undefined;
  updateDraft(text: string): void;
  changeModel(selectionValue: string): Promise<void>;
  changeReasoning(reasoningLevel: ReasoningLevel | null): Promise<void>;
  changeAccessMode(accessMode: ConversationAccessMode): Promise<void>;
  resetPreferences(
    selection: ConversationModelSelection,
    accessMode: ConversationAccessMode,
  ): Promise<void>;
  importAttachments(): Promise<void>;
  removeAttachment(attachmentId: string): Promise<void>;
  send(request: ConversationSubmit): Promise<void>;
  queue(text: string, mode: ConversationQueueMode): Promise<void>;
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

/**
 * 发送失败时把已提交内容恢复到输入框；若用户等待期间已经开始下一条消息，则把失败内容放回前面，
 * 既不覆盖新编辑，也不因即时清空输入框而丢失原请求。
 */
function restoreSubmittedDraft(currentDraft: string, submittedDraft: string): string {
  if (submittedDraft.length === 0 || currentDraft === submittedDraft) return currentDraft;
  if (currentDraft.length === 0) return submittedDraft;
  if (currentDraft.startsWith(`${submittedDraft}\n\n`)) return currentDraft;
  return `${submittedDraft}\n\n${currentDraft}`;
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
  preferencesPort,
  attachmentPort,
}: ConversationInteractionOptions): ConversationInteractionController {
  const [draftsByThread, setDraftsByThread] = useState<Record<string, string>>({});
  const [attachmentsByThread, setAttachmentsByThread] = useState<
    Record<string, readonly ConversationAttachment[]>
  >({});
  const [pendingTurns, setPendingTurns] = useState<Record<string, ConversationAcceptedTurn>>({});
  const [sendingThreadIds, setSendingThreadIds] = useState<Record<string, true>>({});
  const [cancellingTurnIds, setCancellingTurnIds] = useState<Record<string, true>>({});
  const [errorsByThread, setErrorsByThread] = useState<Record<string, string>>({});
  const [queueStatusByThread, setQueueStatusByThread] = useState<Record<string, string>>({});
  const [preferenceBusyByThread, setPreferenceBusyByThread] = useState<Record<string, true>>({});
  const [preferenceErrorsByThread, setPreferenceErrorsByThread] = useState<Record<string, string>>(
    {},
  );
  const [importingAttachments, setImportingAttachments] = useState(false);
  const mountedRef = useRef(false);
  const submitGuardsRef = useRef(new Set<string>());
  const queueGuardsRef = useRef(new Set<string>());
  const cancelGuardsRef = useRef(new Set<string>());
  const approvalGuardsRef = useRef(new Set<string>());
  const preferenceGuardsRef = useRef(new Set<string>());
  const attachmentImportGuardRef = useRef(false);
  const attachmentDiscardGuardsRef = useRef(new Set<string>());
  const pendingTurnsRef = useRef<Record<string, ConversationAcceptedTurn>>({});
  const currentTurns = useTimelineStore((state) => state.turns);
  const currentThreads = useTimelineStore((state) => state.threads);
  const activeTurn =
    threadId === undefined
      ? undefined
      : Object.values(currentTurns).find(
          (turn) => turn.threadId === threadId && !TERMINAL_TURN_STATES.has(turn.status),
        );
  const pendingTurn = threadId === undefined ? undefined : pendingTurns[threadId];
  const activeTurnId = activeTurn?.turnId ?? pendingTurn?.turnId;
  const activeTurnRevision = activeTurn?.threadRevision ?? pendingTurn?.threadRevision;
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
      setQueueStatusByThread((current) => {
        if (current[threadId] === undefined) return current;
        const next = { ...current };
        delete next[threadId];
        return next;
      });
    },
    [threadId],
  );

  /**
   * 同一 Thread 的 turn/start 严格 single-flight；提交意图成立后立即清空当前输入，让点击在下一帧
   * 得到反馈。ACK 只写回原 Thread，失败则无损恢复提交快照，Timeline 已终态时不重建 pending。
   */
  const send = useCallback(
    async ({ text, attachmentIds }: ConversationSubmit): Promise<void> => {
      const requestThreadId = threadId;
      const submittedText = text.trim();
      const availableAttachments =
        requestThreadId === undefined ? [] : (attachmentsByThread[requestThreadId] ?? []);
      const submittedAttachments =
        attachmentIds ?? availableAttachments.map((attachment) => attachment.attachmentId);
      if (
        requestThreadId === undefined ||
        workspaceId === undefined ||
        preferences === undefined ||
        (submittedText.length === 0 && submittedAttachments.length === 0) ||
        blocked ||
        !ready ||
        preferenceBusy ||
        submitGuardsRef.current.has(requestThreadId) ||
        pendingTurnsRef.current[requestThreadId] !== undefined
      )
        return;
      const submittedDraft = draftsByThread[requestThreadId] ?? text;
      const submittedAttachmentSet = new Set(submittedAttachments);
      const submittedAttachmentItems = availableAttachments.filter((attachment) =>
        submittedAttachmentSet.has(attachment.attachmentId),
      );
      submitGuardsRef.current.add(requestThreadId);
      setSendingThreadIds((current) => ({ ...current, [requestThreadId]: true }));
      setDraftsByThread((current) =>
        (current[requestThreadId] ?? submittedDraft) === submittedDraft
          ? { ...current, [requestThreadId]: "" }
          : current,
      );
      setAttachmentsByThread((current) => {
        const observed = current[requestThreadId] ?? [];
        const remaining = observed.filter(
          (attachment) => !submittedAttachmentSet.has(attachment.attachmentId),
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
        // Runtime port 负责真正接纳；即时 UI 反馈不伪造 Turn identity 或持久 Timeline 事实。
        const accepted = await turnPort.submitTurn({
          threadId: requestThreadId,
          content: [
            ...(submittedText === "" ? [] : [{ type: "text" as const, text: submittedText }]),
            ...submittedAttachments.map((attachmentId) => ({
              type: "attachment" as const,
              attachmentId,
            })),
          ],
        });
        if (!mountedRef.current) return;
        // 阶段二：独立事件流可能早于 ACK 到达，先核对终态再决定是否建立 pending 投影。
        if (!isAcceptedTurnTerminal(requestThreadId, accepted)) {
          const nextPending = { ...pendingTurnsRef.current, [requestThreadId]: accepted };
          pendingTurnsRef.current = nextPending;
          setPendingTurns(nextPending);
        }
      } catch {
        if (mountedRef.current) {
          setDraftsByThread((current) => ({
            ...current,
            [requestThreadId]: restoreSubmittedDraft(
              current[requestThreadId] ?? "",
              submittedDraft,
            ),
          }));
          setAttachmentsByThread((current) => {
            const observed = current[requestThreadId] ?? [];
            const observedIds = new Set(observed.map((attachment) => attachment.attachmentId));
            const restored = submittedAttachmentItems.filter(
              (attachment) => !observedIds.has(attachment.attachmentId),
            );
            return restored.length === 0
              ? current
              : { ...current, [requestThreadId]: [...restored, ...observed] };
          });
          setErrorsByThread((current) => ({
            ...current,
            [requestThreadId]: "发送失败，请检查运行时连接后重试。",
          }));
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
      attachmentsByThread,
      blocked,
      draftsByThread,
      preferenceBusy,
      preferences,
      ready,
      threadId,
      turnPort,
      workspaceId,
    ],
  );

  /**
   * Steering 与 follow-up 共用一个 Thread single-flight；排队意图成立后立即清空当前输入，
   * 失败时合并恢复提交快照并释放 guard，使连续编辑与显式重试都不会丢内容。
   */
  const queue = useCallback(
    async (text: string, mode: ConversationQueueMode): Promise<void> => {
      const requestThreadId = threadId;
      const requestTurnId = activeTurnId;
      const submittedText = text.trim();
      if (
        requestThreadId === undefined ||
        requestTurnId === undefined ||
        submittedText.length === 0 ||
        queueGuardsRef.current.has(requestThreadId)
      )
        return;
      const submittedDraft = draftsByThread[requestThreadId] ?? text;
      queueGuardsRef.current.add(requestThreadId);
      setSendingThreadIds((current) => ({ ...current, [requestThreadId]: true }));
      setDraftsByThread((current) =>
        (current[requestThreadId] ?? submittedDraft) === submittedDraft
          ? { ...current, [requestThreadId]: "" }
          : current,
      );
      setErrorsByThread((current) => {
        const next = { ...current };
        delete next[requestThreadId];
        return next;
      });
      try {
        // Turn identity 在点击时冻结；后续 Thread 切换不能把输入排入另一个活动 Turn。
        if (mode === "steering")
          await turnPort.steerTurn({ turnId: requestTurnId, text: submittedText });
        else await turnPort.followUpTurn({ turnId: requestTurnId, text: submittedText });
        if (!mountedRef.current) return;
        setQueueStatusByThread((current) => ({
          ...current,
          [requestThreadId]: mode === "steering" ? "已加入立即引导队列" : "已加入后续消息队列",
        }));
      } catch {
        if (mountedRef.current) {
          setDraftsByThread((current) => ({
            ...current,
            [requestThreadId]: restoreSubmittedDraft(
              current[requestThreadId] ?? "",
              submittedDraft,
            ),
          }));
          setErrorsByThread((current) => ({
            ...current,
            [requestThreadId]: "排队失败，Turn 可能已结束，请重试。",
          }));
        }
      } finally {
        queueGuardsRef.current.delete(requestThreadId);
        if (mountedRef.current) {
          setSendingThreadIds((current) => {
            const next = { ...current };
            delete next[requestThreadId];
            return next;
          });
        }
      }
    },
    [activeTurnId, draftsByThread, threadId, turnPort],
  );

  /**
   * 取消操作冻结点击时的 Turn 与 revision CAS，并按 Turn single-flight；事件终态仍是唯一
   * 完成事实，controller 不做 optimistic completion。
   */
  const cancel = useCallback(async (): Promise<void> => {
    const requestThreadId = threadId;
    const requestTurnId = activeTurnId;
    const requestRevision = activeTurnRevision;
    if (
      requestThreadId === undefined ||
      requestTurnId === undefined ||
      requestRevision === undefined ||
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
  }, [activeTurnId, activeTurnRevision, threadId, turnPort]);

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
   * 因为 App Server 只会把新偏好冻结到下一 Turn。
   */
  const updatePreference = useCallback(
    async (next: {
      providerId: string;
      modelId: string;
      reasoningLevel: ReasoningLevel | null;
      accessMode: ConversationAccessMode;
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
        preferences.accessMode === next.accessMode
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
      });
    },
    [models, preferences, updatePreference],
  );

  /** Reasoning 只修改下一 Turn 参数，null 表示沿用模型默认而不是关闭模型思考。 */
  const changeReasoning = useCallback(
    async (reasoningLevel: ReasoningLevel | null): Promise<void> => {
      if (preferences === undefined) return;
      await updatePreference({
        providerId: preferences.providerId,
        modelId: preferences.modelId,
        reasoningLevel,
        accessMode: preferences.accessMode,
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
      });
    },
    [models, updatePreference],
  );

  /**
   * 附件导入由一个原生 adapter 完成 picker、Rust ingress 与 App Server import；renderer
   * 只合并返回的 opaque identity，取消选择则得到空数组且不显示错误。
   */
  const importAttachments = useCallback(async (): Promise<void> => {
    const requestThreadId = threadId;
    if (
      requestThreadId === undefined ||
      attachmentPort === undefined ||
      attachmentImportGuardRef.current
    )
      return;
    attachmentImportGuardRef.current = true;
    setImportingAttachments(true);
    setErrorsByThread((current) => {
      const next = { ...current };
      delete next[requestThreadId];
      return next;
    });
    try {
      const imported = await attachmentPort.importAttachments();
      if (!mountedRef.current || imported.length === 0) return;
      setAttachmentsByThread((current) => {
        const existing = current[requestThreadId] ?? [];
        const known = new Set(existing.map((attachment) => attachment.attachmentId));
        return {
          ...current,
          [requestThreadId]: [
            ...existing,
            ...imported.filter((attachment) => !known.has(attachment.attachmentId)),
          ],
        };
      });
    } catch {
      if (mountedRef.current)
        setErrorsByThread((current) => ({
          ...current,
          [requestThreadId]: "附件导入失败，请检查文件后重试。",
        }));
    } finally {
      attachmentImportGuardRef.current = false;
      if (mountedRef.current) setImportingAttachments(false);
    }
  }, [attachmentPort, threadId]);

  /** 先由 App Server 丢弃草稿附件，再移除 chip，失败时保留可恢复的可见状态。 */
  const removeAttachment = useCallback(
    async (attachmentId: string): Promise<void> => {
      const requestThreadId = threadId;
      if (
        requestThreadId === undefined ||
        attachmentPort === undefined ||
        attachmentDiscardGuardsRef.current.has(attachmentId)
      )
        return;
      attachmentDiscardGuardsRef.current.add(attachmentId);
      try {
        await attachmentPort.discardAttachment({ attachmentId });
        if (!mountedRef.current) return;
        setAttachmentsByThread((current) => ({
          ...current,
          [requestThreadId]: (current[requestThreadId] ?? []).filter(
            (attachment) => attachment.attachmentId !== attachmentId,
          ),
        }));
      } catch {
        if (mountedRef.current)
          setErrorsByThread((current) => ({
            ...current,
            [requestThreadId]: "附件暂时无法移除，请重试。",
          }));
      } finally {
        attachmentDiscardGuardsRef.current.delete(attachmentId);
      }
    },
    [attachmentPort, threadId],
  );

  const hasActiveTurn = activeTurn !== undefined || pendingTurn !== undefined;
  const sending = threadId !== undefined && sendingThreadIds[threadId] === true;
  const cancelling = activeTurnId !== undefined && cancellingTurnIds[activeTurnId] === true;
  return {
    draft: threadId === undefined ? "" : (draftsByThread[threadId] ?? ""),
    preferences,
    models,
    attachments: threadId === undefined ? [] : (attachmentsByThread[threadId] ?? []),
    activeTurn: hasActiveTurn,
    disabled:
      !ready ||
      blocked ||
      workspaceId === undefined ||
      threadId === undefined ||
      preferences === undefined,
    preferenceBusy,
    importingAttachments,
    sending,
    cancelling,
    error:
      threadId === undefined
        ? undefined
        : (errorsByThread[threadId] ?? preferenceErrorsByThread[threadId]),
    queueStatus: threadId === undefined ? undefined : queueStatusByThread[threadId],
    updateDraft,
    changeModel,
    changeReasoning,
    changeAccessMode,
    resetPreferences,
    importAttachments,
    removeAttachment,
    send,
    queue,
    cancel,
    approve,
  };
}
