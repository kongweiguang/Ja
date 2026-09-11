// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  isInteractionRevisionConflict,
  type InteractionAnswer,
  type InteractionEvent,
  type InteractionPort,
  type InteractionRequest,
  type InteractionSnapshot,
} from "./interactionPort";

export interface InteractionControllerOptions {
  threadId: string | undefined;
  visible?: boolean;
  port: InteractionPort | undefined;
}

/** Clarification 是产品层名称；interaction 是可供 Plan 与其它模式复用的技术名称。 */
export const useClarificationController = useInteractionController;

export interface InteractionController {
  request: InteractionRequest | null;
  answers: Readonly<Record<string, InteractionAnswer>>;
  pageIndex: number;
  collapsed: boolean;
  loading: boolean;
  saving: boolean;
  submitting: boolean;
  answered: boolean;
  resumeState: NonNullable<InteractionSnapshot["resumeState"]>;
  conflict: boolean;
  error: string | undefined;
  retryAction: "draft" | "submit" | undefined;
  setAnswer(questionId: string, answer: InteractionAnswer): void;
  goToPage(index: number): void;
  next(): void;
  previous(): void;
  submit(): Promise<void>;
  retryDraft(): Promise<void>;
  retrySubmit(): Promise<void>;
  cancel(): Promise<void>;
  setCollapsed(collapsed: boolean): void;
  clearError(): void;
  refresh(): Promise<void>;
}

const EMPTY_ANSWERS: Readonly<Record<string, InteractionAnswer>> = {};

/** 编辑控件内的按键交给浏览器和中文输入法，卡片快捷键不应截断用户文本。 */
function isEditableTarget(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  if (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement
  )
    return true;
  const editable = target.closest<HTMLElement>("[contenteditable]");
  return editable !== null && editable.getAttribute("contenteditable") !== "false";
}

/** 原生控件负责自己的 Enter/方向键行为，避免窗口级快捷键替换浏览器语义。 */
function isNativeControlTarget(target: EventTarget | null): boolean {
  if (isEditableTarget(target)) return true;
  return (
    target instanceof Element &&
    target.closest('button, a, [role="button"], [role="radio"], [role="checkbox"]') !== null
  );
}

/** 全局快捷键只接受当前交互卡片的事件，避免主会话与侧任务共享 window 时串线。 */
function belongsToInteractionCard(
  target: EventTarget | null,
  threadId: string | undefined,
): boolean {
  if (threadId === undefined || !(target instanceof Element)) return false;
  const card = target.closest<HTMLElement>("[data-interaction-card]");
  return card?.dataset["interactionThreadId"] === threadId;
}

/** 判断结构化答案是否真的变化，避免每次点击同一选项都触发草稿写入。 */
function sameAnswer(left: InteractionAnswer | undefined, right: InteractionAnswer): boolean {
  return (
    left !== undefined &&
    left.questionId === right.questionId &&
    left.skipped === right.skipped &&
    left.freeText === right.freeText &&
    left.optionIds.length === right.optionIds.length &&
    left.optionIds.every((id, index) => id === right.optionIds[index])
  );
}

/** 服务端提交前再次检查全部必填题，防止通过分页绕过必答约束。 */
function requiredAnswerMissing(
  question: InteractionRequest["questions"][number],
  answer: InteractionAnswer | undefined,
): boolean {
  if (answer === undefined) return true;
  if (answer.skipped) return question.required;
  if (question.type === "text") return !answer.freeText?.trim();
  return (
    (answer.optionIds.length === 0 && !answer.freeText?.trim()) ||
    (question.allowFreeText && answer.freeText !== null && answer.freeText.trim() === "")
  );
}

/** 将界面便于索引的答案 Map 转成后端要求的稳定 questionId 数组。 */
function answerList(answers: Readonly<Record<string, InteractionAnswer>>): InteractionAnswer[] {
  return Object.values(answers).sort((left, right) =>
    left.questionId.localeCompare(right.questionId),
  );
}

/** 已回答摘要必须采用服务端确认答案；待回答状态才允许从本地草稿恢复答案。 */
function answersFromSnapshot(
  next: InteractionSnapshot,
): Readonly<Record<string, InteractionAnswer>> {
  const answers =
    next.request?.status === "answered"
      ? next.request.answers
      : (next.draft?.answers ?? next.request?.answers ?? []);
  return Object.fromEntries(
    answers.map((answer) => [answer.questionId, { ...answer, optionIds: [...answer.optionIds] }]),
  );
}

type PendingDraft = {
  threadId: string;
  requestId: string;
  expectedDraftRevision: number;
  answers: readonly InteractionAnswer[];
  page: number;
  collapsed: boolean;
  idempotencyKey: string;
  scopeEpoch: number;
  editRevision: number;
};

const unconfirmedDrafts = new Map<string, PendingDraft>();

/** 用 Thread 与请求稳定定位未确认草稿，避免切回同一会话时串到其它问题。 */
function pendingDraftKey(threadId: string, requestId: string): string {
  return `${threadId}\u0000${requestId}`;
}

/** 复制未确认草稿的可变数组，缓存只保存用户本地事实，不共享 React 快照引用。 */
function clonePendingDraft(payload: PendingDraft): PendingDraft {
  return {
    ...payload,
    answers: payload.answers.map((answer) => ({
      ...answer,
      optionIds: [...answer.optionIds],
    })),
  };
}

/** 记录尚未得到服务端确认的编辑，供同 Thread 切回后明确重试。 */
function rememberPendingDraft(payload: PendingDraft): void {
  unconfirmedDrafts.set(
    pendingDraftKey(payload.threadId, payload.requestId),
    clonePendingDraft(payload),
  );
}

/** 仅在同一编辑代次已收到成功 ACK 时移除本地缓存，避免误删更新后的输入。 */
function forgetPendingDraft(payload: PendingDraft): void {
  const key = pendingDraftKey(payload.threadId, payload.requestId);
  if (unconfirmedDrafts.get(key)?.editRevision === payload.editRevision)
    unconfirmedDrafts.delete(key);
}

/** 将缓存的未确认草稿投影为答案索引，保持服务端草稿与本地编辑明确分层。 */
function answersFromPendingDraft(
  payload: PendingDraft,
): Readonly<Record<string, InteractionAnswer>> {
  return Object.fromEntries(
    payload.answers.map((answer) => [
      answer.questionId,
      { ...answer, optionIds: [...answer.optionIds] },
    ]),
  );
}

/** 将保存队列的作用域元数据剥离，防止内部竞态字段进入 adapter 或 JA-RPC。 */
function toDraftSaveInput(
  payload: PendingDraft,
): Omit<PendingDraft, "scopeEpoch" | "editRevision"> {
  return {
    threadId: payload.threadId,
    requestId: payload.requestId,
    expectedDraftRevision: payload.expectedDraftRevision,
    answers: payload.answers,
    page: payload.page,
    collapsed: payload.collapsed,
    idempotencyKey: payload.idempotencyKey,
  };
}

/** 以 Thread 绑定订阅并用 snapshot 修复事件缺口；CAS 冲突时不覆盖用户本地答案。 */
export function useInteractionController({
  threadId,
  visible = true,
  port,
}: InteractionControllerOptions): InteractionController {
  const [snapshot, setSnapshot] = useState<InteractionSnapshot>();
  const [answers, setAnswers] =
    useState<Readonly<Record<string, InteractionAnswer>>>(EMPTY_ANSWERS);
  const [pageIndex, setPageIndex] = useState(0);
  const [collapsed, setCollapsed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [error, setError] = useState<string>();
  const [retryAction, setRetryAction] = useState<"draft" | "submit">();
  const [answered, setAnswered] = useState(false);
  const [resumeState, setResumeState] =
    useState<NonNullable<InteractionSnapshot["resumeState"]>>("none");
  const draftTimerRef = useRef<number | undefined>(undefined);
  // 作用域代次只在 Thread/可见性边界变化时递增，不能拿来表示一次普通 snapshot 读取。
  const scopeEpochRef = useRef(0);
  // 事件水位只描述服务端快照顺序，和异步请求是否属于当前作用域完全正交。
  const readSequenceRef = useRef(0);
  const editRevisionRef = useRef(0);
  const answersRef = useRef(answers);
  const snapshotRef = useRef(snapshot);
  const pageRef = useRef(pageIndex);
  const collapsedRef = useRef(collapsed);
  const dirtyRef = useRef(false);
  const submitKeyRef = useRef<string | undefined>(undefined);
  const submitFingerprintRef = useRef<string | undefined>(undefined);
  const submitPromiseRef = useRef<Promise<void> | undefined>(undefined);
  const draftSaveInFlightRef = useRef(false);
  const draftSavePromiseRef = useRef<Promise<InteractionSnapshot> | undefined>(undefined);
  const draftSavePayloadRef = useRef<PendingDraft | undefined>(undefined);
  const pendingDraftRef = useRef<PendingDraft | undefined>(undefined);
  const queueDraftSaveRef = useRef<(() => void) | undefined>(undefined);

  useEffect(() => {
    answersRef.current = answers;
  }, [answers]);
  useEffect(() => {
    snapshotRef.current = snapshot;
  }, [snapshot]);
  useEffect(() => {
    pageRef.current = pageIndex;
  }, [pageIndex]);
  useEffect(() => {
    collapsedRef.current = collapsed;
  }, [collapsed]);

  /** 应用按事件水位校验过的服务端快照；已确认答案始终优先于本地草稿。 */
  const applySnapshot = useCallback(
    (next: InteractionSnapshot): void => {
      if (next.threadId !== threadId || next.eventSequence < readSequenceRef.current) return;
      const previousRequestId = snapshotRef.current?.request?.requestId;
      const nextRequestId = next.request?.requestId;
      if (previousRequestId !== undefined && previousRequestId !== nextRequestId) {
        unconfirmedDrafts.delete(pendingDraftKey(threadId, previousRequestId));
      }
      if (next.request !== null && next.request?.status !== "pending") {
        unconfirmedDrafts.delete(pendingDraftKey(threadId, next.request.requestId));
      }
      const pendingLocalDraft =
        next.request?.status === "pending"
          ? unconfirmedDrafts.get(pendingDraftKey(threadId, next.request.requestId))
          : undefined;
      if (previousRequestId !== nextRequestId) {
        dirtyRef.current = false;
        submitKeyRef.current = undefined;
        submitFingerprintRef.current = undefined;
        editRevisionRef.current = 0;
        const restoredAnswers =
          pendingLocalDraft === undefined
            ? answersFromSnapshot(next)
            : answersFromPendingDraft(pendingLocalDraft);
        answersRef.current = restoredAnswers;
        setAnswers(restoredAnswers);
        pageRef.current = pendingLocalDraft?.page ?? next.draft?.page ?? 0;
        setPageIndex(pageRef.current);
        collapsedRef.current = pendingLocalDraft?.collapsed ?? next.draft?.collapsed ?? false;
        setCollapsed(collapsedRef.current);
      }
      readSequenceRef.current = next.eventSequence;
      // 让同一 tick 内的草稿 ACK 也能读取最新服务端 draft revision，而不等待 React effect。
      snapshotRef.current = next;
      setSnapshot(next);
      // 已回答请求是服务端确认事实，即使本地尚有未发送草稿，也不能把草稿显示成确认摘要。
      if (next.request?.status === "answered") dirtyRef.current = false;
      if (pendingLocalDraft !== undefined) {
        dirtyRef.current = true;
        editRevisionRef.current = Math.max(editRevisionRef.current, pendingLocalDraft.editRevision);
        const restoredAnswers = answersFromPendingDraft(pendingLocalDraft);
        answersRef.current = restoredAnswers;
        setAnswers(restoredAnswers);
        pageRef.current = pendingLocalDraft.page;
        setPageIndex(pendingLocalDraft.page);
        collapsedRef.current = pendingLocalDraft.collapsed;
        setCollapsed(pendingLocalDraft.collapsed);
      } else if (!dirtyRef.current || next.request?.status === "answered") {
        const restoredAnswers = answersFromSnapshot(next);
        answersRef.current = restoredAnswers;
        setAnswers(restoredAnswers);
      }
      if (!dirtyRef.current) {
        pageRef.current = next.draft?.page ?? 0;
        setPageIndex(next.draft?.page ?? 0);
        collapsedRef.current = next.draft?.collapsed ?? false;
        setCollapsed(next.draft?.collapsed ?? false);
      }
      setAnswered(next.request?.status === "answered");
      setResumeState(next.resumeState ?? "none");
      if (pendingLocalDraft !== undefined) {
        setConflict(true);
        setError("本地草稿尚未得到确认；请明确重试保存。");
        setRetryAction("draft");
      } else {
        setConflict(false);
        setError(undefined);
        setRetryAction(undefined);
      }
    },
    [threadId],
  );

  /** 读取失败只保留当前编辑并提示恢复，避免断线时清空用户答案。 */
  const readSnapshot = useCallback(async (): Promise<void> => {
    if (!visible || threadId === undefined || port === undefined) return;
    const scopeEpoch = scopeEpochRef.current;
    setLoading(true);
    try {
      const next = await port.read({ threadId });
      if (scopeEpoch === scopeEpochRef.current) applySnapshot(next);
    } catch {
      if (scopeEpoch === scopeEpochRef.current) setError("交互问题暂时不可用，请重试。");
    } finally {
      if (scopeEpoch === scopeEpochRef.current) setLoading(false);
    }
  }, [applySnapshot, port, threadId, visible]);

  useEffect(() => {
    scopeEpochRef.current += 1;
    readSequenceRef.current = 0;
    editRevisionRef.current = 0;
    dirtyRef.current = false;
    submitKeyRef.current = undefined;
    submitFingerprintRef.current = undefined;
    submitPromiseRef.current = undefined;
    pendingDraftRef.current = undefined;
    setSnapshot(undefined);
    setAnswers(EMPTY_ANSWERS);
    answersRef.current = EMPTY_ANSWERS;
    setPageIndex(0);
    pageRef.current = 0;
    setCollapsed(false);
    collapsedRef.current = false;
    setConflict(false);
    setError(undefined);
    setRetryAction(undefined);
    setAnswered(false);
    setResumeState("none");
    setLoading(false);
    setSaving(false);
    setSubmitting(false);
    draftSaveInFlightRef.current = false;
    draftSavePromiseRef.current = undefined;
    if (!visible || threadId === undefined || port === undefined) return;
    let disposed = false;
    void readSnapshot();
    const unsubscribe = port.subscribe(
      { threadId, afterSequence: readSequenceRef.current },
      (event: InteractionEvent) => {
        if (
          disposed ||
          event.threadId !== threadId ||
          event.eventSequence <= readSequenceRef.current
        )
          return;
        void readSnapshot();
      },
    );
    return () => {
      disposed = true;
      unsubscribe();
      if (draftTimerRef.current !== undefined) {
        window.clearTimeout(draftTimerRef.current);
        draftTimerRef.current = undefined;
      }
      // 先使旧作用域的回调失效；卸载 flush 只在前一笔成功后继续，冲突或失败则停止并缓存本地编辑。
      scopeEpochRef.current += 1;
      const pending = pendingDraftRef.current;
      const inFlight = draftSavePromiseRef.current;
      const inFlightPayload = draftSavePayloadRef.current;
      pendingDraftRef.current = undefined;
      const latestLocalDraft = pending ?? inFlightPayload;
      if (latestLocalDraft !== undefined && port !== undefined) {
        const input = toDraftSaveInput(latestLocalDraft);
        const remember = (): void => rememberPendingDraft(latestLocalDraft);
        const forget = (): void => forgetPendingDraft(latestLocalDraft);
        if (inFlight === undefined) {
          // 卸载时的首次 flush 仍尝试一次；任何失败都缓存本地输入，不假设写入成功。
          void port.saveDraft(input).then(forget, remember);
        } else if (pending !== undefined) {
          // CAS 冲突代表服务端已有更新，停止自动写入，避免用新 revision 覆盖服务端草稿。
          const flush = inFlight.then(
            (next) =>
              port.saveDraft({
                ...input,
                expectedDraftRevision: next.draft?.revision ?? input.expectedDraftRevision,
              }),
            (reason: unknown) => {
              remember();
              return Promise.reject(reason);
            },
          );
          void flush.then(forget, remember);
        } else {
          // 没有更晚编辑时，inflight 结果本身决定缓存是否需要保留。
          void inFlight.then(forget, remember);
        }
      }
    };
  }, [port, readSnapshot, threadId, visible]);

  const request = snapshot?.request ?? null;
  const currentQuestion = request?.questions[pageIndex];

  /** 延迟保存答案、页码和折叠状态；编辑代次保证旧 ACK 不能覆盖 ACK 期间的新输入。 */
  const queueDraftSave = useCallback((): void => {
    dirtyRef.current = true;
    if (draftTimerRef.current !== undefined) window.clearTimeout(draftTimerRef.current);
    if (threadId === undefined || port === undefined) return;
    const current = snapshotRef.current;
    const currentRequest = current?.request;
    if (currentRequest === null || currentRequest === undefined) return;
    const payload: PendingDraft = {
      threadId,
      requestId: currentRequest.requestId,
      expectedDraftRevision: current?.draft?.revision ?? 0,
      answers: answerList(answersRef.current),
      page: pageRef.current,
      collapsed: collapsedRef.current,
      idempotencyKey: `interaction-draft-${crypto.randomUUID()}`,
      scopeEpoch: scopeEpochRef.current,
      editRevision: editRevisionRef.current,
    };
    pendingDraftRef.current = payload;
    if (unconfirmedDrafts.has(pendingDraftKey(payload.threadId, payload.requestId))) {
      rememberPendingDraft(payload);
    }
    draftTimerRef.current = window.setTimeout(() => {
      draftTimerRef.current = undefined;
      const pending = pendingDraftRef.current;
      pendingDraftRef.current = undefined;
      if (
        pending === undefined ||
        pending.scopeEpoch !== scopeEpochRef.current ||
        snapshotRef.current?.request?.requestId !== pending.requestId
      )
        return;
      if (draftSaveInFlightRef.current) {
        pendingDraftRef.current = pending;
        return;
      }
      draftSaveInFlightRef.current = true;
      setSaving(true);
      const savePromise = port.saveDraft(toDraftSaveInput(pending));
      draftSavePromiseRef.current = savePromise;
      draftSavePayloadRef.current = pending;
      void savePromise
        .then((next) => {
          if (
            pending.scopeEpoch !== scopeEpochRef.current ||
            snapshotRef.current?.request?.requestId !== pending.requestId
          )
            return;
          forgetPendingDraft(pending);
          applySnapshot(next);
          if (pending.editRevision === editRevisionRef.current) {
            dirtyRef.current = false;
          } else {
            // 新输入已经产生，沿用新的服务端 draft revision 再排一笔，避免 CAS 使用旧版本。
            dirtyRef.current = true;
            queueDraftSaveRef.current?.();
          }
        })
        .catch((reason: unknown) => {
          if (pending.scopeEpoch !== scopeEpochRef.current) return;
          // 保存未确认时保留最新本地编辑；后续切回同 Thread 只能显式重试。
          rememberPendingDraft(pendingDraftRef.current ?? pending);
          if (isInteractionRevisionConflict(reason)) {
            applySnapshot(reason.snapshot);
            setConflict(true);
            setRetryAction("draft");
            setError("服务端已有更新，当前编辑已保留；请先比较后重试保存。");
          } else {
            setRetryAction("draft");
            setError("草稿保存失败，已保留当前答案。");
          }
        })
        .finally(() => {
          const isCurrentSave = draftSavePromiseRef.current === savePromise;
          if (!isCurrentSave) return;
          draftSavePromiseRef.current = undefined;
          draftSavePayloadRef.current = undefined;
          draftSaveInFlightRef.current = false;
          if (pending.scopeEpoch === scopeEpochRef.current) setSaving(false);
          if (pendingDraftRef.current !== undefined && draftTimerRef.current === undefined) {
            queueDraftSaveRef.current?.();
          }
        });
    }, 350);
  }, [applySnapshot, port, threadId]);

  useEffect(() => {
    queueDraftSaveRef.current = queueDraftSave;
    return () => {
      if (queueDraftSaveRef.current === queueDraftSave) queueDraftSaveRef.current = undefined;
    };
  }, [queueDraftSave]);

  const updateDraft = useCallback(
    (nextAnswers: Readonly<Record<string, InteractionAnswer>>): void => {
      answersRef.current = nextAnswers;
      editRevisionRef.current += 1;
      setAnswers(nextAnswers);
      queueDraftSave();
    },
    [queueDraftSave],
  );

  const setAnswer = useCallback(
    (questionId: string, answer: InteractionAnswer): void => {
      if (request === null || request.status !== "pending") return;
      const question = request.questions.find((item) => item.questionId === questionId);
      if (
        question === undefined ||
        answer.questionId !== questionId ||
        sameAnswer(answersRef.current[questionId], answer)
      )
        return;
      const next = { ...answersRef.current, [questionId]: answer };
      answersRef.current = next;
      // 答案变更后，之前失败提交的 key 只允许用于同一 payload；新 payload 必须生成新 key。
      submitKeyRef.current = undefined;
      submitFingerprintRef.current = undefined;
      updateDraft(next);
    },
    [request, updateDraft],
  );

  const submit = useCallback(async (): Promise<void> => {
    if (submitPromiseRef.current !== undefined) return submitPromiseRef.current;
    if (threadId === undefined || port === undefined || request === null || snapshot === undefined)
      return;
    const missingIndex = request.questions.findIndex((question) =>
      requiredAnswerMissing(question, answersRef.current[question.questionId]),
    );
    if (missingIndex >= 0) {
      setPageIndex(missingIndex);
      setError("请先完成问题，或明确跳过可选问题。");
      return;
    }
    const submissionAnswers = answerList(answersRef.current);
    const fingerprint = `${request.requestId}:${request.revision}:${JSON.stringify(submissionAnswers)}`;
    const idempotencyKey =
      submitKeyRef.current !== undefined && submitFingerprintRef.current === fingerprint
        ? submitKeyRef.current
        : `interaction-submit-${crypto.randomUUID()}`;
    const operationEpoch = scopeEpochRef.current;
    const submissionEditRevision = editRevisionRef.current;
    submitKeyRef.current = idempotencyKey;
    submitFingerprintRef.current = fingerprint;
    setSubmitting(true);
    setError(undefined);
    const promise = port
      .submit({
        threadId,
        requestId: request.requestId,
        expectedRevision: request.revision,
        answers: submissionAnswers,
        idempotencyKey,
      })
      .then((next) => {
        if (operationEpoch !== scopeEpochRef.current) return;
        applySnapshot(next);
        if (submissionEditRevision === editRevisionRef.current) {
          dirtyRef.current = false;
          submitKeyRef.current = undefined;
          submitFingerprintRef.current = undefined;
          collapsedRef.current = true;
          setCollapsed(true);
        }
      })
      .catch((reason: unknown) => {
        if (operationEpoch !== scopeEpochRef.current) return;
        if (isInteractionRevisionConflict(reason)) {
          applySnapshot(reason.snapshot);
          setConflict(true);
          setRetryAction("submit");
          setError("问题已有新版本，当前答案未被覆盖；请刷新后重新提交。");
        } else {
          setRetryAction("submit");
          setError("提交失败，答案仍保留在当前问题中，可重试。");
        }
      })
      .finally(() => {
        if (operationEpoch === scopeEpochRef.current) {
          submitPromiseRef.current = undefined;
          setSubmitting(false);
        }
      });
    submitPromiseRef.current = promise;
    return promise;
  }, [applySnapshot, port, request, snapshot, threadId]);

  /** 草稿冲突重试只保存当前编辑，不把恢复动作误升级为回答提交。 */
  const retryDraft = useCallback(async (): Promise<void> => {
    if (request === null || request.status !== "pending") return;
    setConflict(false);
    setError(undefined);
    setRetryAction(undefined);
    queueDraftSave();
  }, [queueDraftSave, request]);
  const retrySubmit = useCallback(async (): Promise<void> => submit(), [submit]);
  const cancel = useCallback(async (): Promise<void> => {
    if (threadId === undefined || port === undefined || request === null || snapshot === undefined)
      return;
    const operationEpoch = scopeEpochRef.current;
    try {
      const next = await port.cancel({
        threadId,
        requestId: request.requestId,
        expectedRevision: request.revision,
        idempotencyKey: `interaction-cancel-${crypto.randomUUID()}`,
      });
      if (operationEpoch !== scopeEpochRef.current) return;
      dirtyRef.current = false;
      submitKeyRef.current = undefined;
      submitFingerprintRef.current = undefined;
      applySnapshot(next);
      collapsedRef.current = true;
      setCollapsed(true);
    } catch (reason: unknown) {
      if (operationEpoch !== scopeEpochRef.current) return;
      if (isInteractionRevisionConflict(reason)) setConflict(true);
      setError("取消问题失败，请重试。");
    }
  }, [applySnapshot, port, request, snapshot, threadId]);

  /** 页码是可恢复的交互草稿状态，翻页也必须进入同一 CAS 保存队列。 */
  const goToPage = useCallback(
    (index: number): void => {
      if (request === null) return;
      const nextPage = Math.min(Math.max(index, 0), Math.max(request.questions.length - 1, 0));
      if (nextPage === pageRef.current) return;
      pageRef.current = nextPage;
      editRevisionRef.current += 1;
      setPageIndex(nextPage);
      queueDraftSave();
    },
    [queueDraftSave, request],
  );
  const next = useCallback(() => goToPage(pageIndex + 1), [goToPage, pageIndex]);
  const previous = useCallback(() => goToPage(pageIndex - 1), [goToPage, pageIndex]);

  /** 折叠状态属于用户草稿而非纯 UI 临时状态，刷新和跨窗口切换后仍应恢复。 */
  const setCollapsedValue = useCallback(
    (nextCollapsed: boolean): void => {
      if (nextCollapsed === collapsedRef.current) return;
      collapsedRef.current = nextCollapsed;
      editRevisionRef.current += 1;
      setCollapsed(nextCollapsed);
      if (snapshotRef.current?.request?.status === "pending") queueDraftSave();
    },
    [queueDraftSave],
  );

  useEffect(() => {
    if (!visible || request === null || request.status !== "pending" || collapsed) return;
    const onKeyDown = (event: KeyboardEvent): void => {
      if (
        event.isComposing ||
        event.ctrlKey ||
        event.altKey ||
        event.metaKey ||
        isNativeControlTarget(event.target) ||
        !belongsToInteractionCard(event.target, threadId)
      )
        return;
      if (
        currentQuestion !== undefined &&
        /^[1-9]$/.test(event.key) &&
        currentQuestion.type !== "text"
      ) {
        const option = currentQuestion.options?.[Number(event.key) - 1];
        if (option !== undefined) {
          event.preventDefault();
          const currentAnswer = answersRef.current[currentQuestion.questionId];
          const currentOptionIds = currentAnswer?.optionIds ?? [];
          const optionIds =
            currentQuestion.type === "multiple"
              ? currentOptionIds.includes(option.optionId)
                ? currentOptionIds.filter((id) => id !== option.optionId)
                : [...currentOptionIds, option.optionId]
              : [option.optionId];
          setAnswer(currentQuestion.questionId, {
            questionId: currentQuestion.questionId,
            optionIds,
            // 多选数字键只是切换选项，不能破坏同一答案中已填写的 Other 文本；单选仍保持互斥。
            freeText:
              currentQuestion.type === "multiple" ? (currentAnswer?.freeText ?? null) : null,
            skipped: false,
          });
        }
      } else if (event.key === "ArrowLeft") {
        event.preventDefault();
        previous();
      } else if (event.key === "ArrowRight") {
        event.preventDefault();
        next();
      } else if (event.key === "Enter") {
        event.preventDefault();
        if (pageIndex === request.questions.length - 1) void submit();
        else next();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [
    collapsed,
    currentQuestion,
    next,
    pageIndex,
    previous,
    request,
    setAnswer,
    submit,
    threadId,
    visible,
  ]);

  return useMemo(
    () => ({
      request,
      answers,
      pageIndex,
      collapsed,
      loading,
      saving,
      submitting,
      answered,
      resumeState,
      conflict,
      error,
      retryAction,
      setAnswer,
      goToPage,
      next,
      previous,
      submit,
      retrySubmit,
      retryDraft,
      cancel,
      setCollapsed: setCollapsedValue,
      clearError: () => setError(undefined),
      refresh: readSnapshot,
    }),
    [
      answered,
      resumeState,
      answers,
      cancel,
      collapsed,
      conflict,
      error,
      goToPage,
      loading,
      next,
      pageIndex,
      previous,
      request,
      retryAction,
      retryDraft,
      retrySubmit,
      readSnapshot,
      saving,
      setAnswer,
      setCollapsedValue,
      submit,
      submitting,
    ],
  );
}
