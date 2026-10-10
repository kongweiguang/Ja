// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { LoaderCircle, Send, Square, RotateCcw } from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type ReactElement,
} from "react";
import { createOperationKey } from "@/shared/operationKey";
import type {
  ConversationProcessPort,
  ConversationProcessScope,
  ConversationProcessSnapshot,
} from "../../application/processSessionTypes";
import "./ProcessSessionPanel.css";

const MAX_READ_BYTES = 65_536;
const MAX_INPUT_LENGTH = 1_048_576;
const MAX_VISIBLE_OUTPUT_LENGTH = 131_072;

interface ProcessPanelView {
  readonly phase: "loading" | "ready" | "missing" | "error";
  readonly snapshot?: ConversationProcessSnapshot;
  readonly output: string;
  readonly outputTruncated: boolean;
  readonly error?: string;
}

interface PendingWrite {
  readonly input: string;
  readonly closeInput: boolean;
  readonly operationKey: string;
}

/** 隐藏或已卸载的历史面板不得接收异步终态恢复焦点。 */
function canFocusVisibleElement(element: HTMLElement | null): element is HTMLElement {
  if (element === null || !element.isConnected || !document.body.contains(element)) return false;
  let current: HTMLElement | null = element;
  while (current !== null) {
    const style = window.getComputedStyle(current);
    if (
      current.hidden ||
      current.getAttribute("aria-hidden") === "true" ||
      style.display === "none" ||
      style.visibility === "hidden" ||
      style.visibility === "collapse"
    )
      return false;
    current = current.parentElement;
  }
  return true;
}

export interface ProcessSessionPanelProps {
  readonly enabled: boolean;
  readonly scope: ConversationProcessScope;
  readonly callId: string;
  readonly port: ConversationProcessPort;
  /** 仅在没有匹配的权威会话快照时保留 ToolResult，读取成功后由实时输出取代。 */
  readonly fallbackOutput?: string;
}

/** 仅在 Shell 详情展开时查询同一 call 的 Java 进程，并按序号读取新增输出。 */
export function ProcessSessionPanel({
  enabled,
  scope,
  callId,
  port,
  fallbackOutput,
}: ProcessSessionPanelProps): ReactElement | null {
  const identity = `${scope.workspaceId}:${scope.threadId}:${callId}`;
  const { workspaceId, threadId } = scope;
  const requestScope = useMemo(() => ({ workspaceId, threadId }), [threadId, workspaceId]);
  const [view, setView] = useState<ProcessPanelView>({
    phase: "loading",
    output: "",
    outputTruncated: false,
  });
  const [draft, setDraft] = useState("");
  const [closeInput, setCloseInput] = useState(false);
  const [pendingWrite, setPendingWrite] = useState<PendingWrite>();
  const [action, setAction] = useState<"write" | "stop">();
  const [actionError, setActionError] = useState<string>();
  const [retrying, setRetrying] = useState(false);
  const cursorRef = useRef(0);
  const sessionIdRef = useRef<string | undefined>(undefined);
  const sessionGenerationRef = useRef<number | undefined>(undefined);
  const registryGenerationRef = useRef<number | undefined>(undefined);
  const outputRef = useRef("");
  const truncatedRef = useRef(false);
  const refreshRef = useRef<() => void>(() => undefined);
  const pollTokenRef = useRef(0);
  const panelRef = useRef<HTMLElement | null>(null);
  const outputElementRef = useRef<HTMLPreElement | null>(null);
  const retryButtonRef = useRef<HTMLButtonElement | null>(null);
  const retryingRef = useRef(false);
  const retryFocusPendingRef = useRef(false);
  const retryFocusMovedRef = useRef(false);
  const terminalFocusPendingRef = useRef(false);
  const terminalFocusMovedRef = useRef(false);
  const terminalFocusOwnerRef = useRef<HTMLElement | null>(null);

  /** 异步终态只归还给起始动作的焦点 owner；任何其它焦点或指针目标都会撤销意图。 */
  useEffect(() => {
    const trackFocus = (event: FocusEvent): void => {
      if (retryFocusPendingRef.current && event.target !== retryButtonRef.current) {
        retryFocusMovedRef.current = true;
      }
      if (terminalFocusPendingRef.current && event.target instanceof Node) {
        const owner = terminalFocusOwnerRef.current;
        if (
          owner !== null &&
          !owner.contains(event.target) &&
          event.target !== document.body &&
          event.target !== document.documentElement
        ) {
          terminalFocusMovedRef.current = true;
        }
      }
    };
    const trackPointer = (event: PointerEvent): void => {
      if (terminalFocusPendingRef.current && event.target instanceof Node) {
        const owner = terminalFocusOwnerRef.current;
        if (owner !== null && !owner.contains(event.target)) {
          terminalFocusMovedRef.current = true;
        }
      }
    };
    window.addEventListener("focusin", trackFocus);
    window.addEventListener("pointerdown", trackPointer, true);
    return () => {
      window.removeEventListener("focusin", trackFocus);
      window.removeEventListener("pointerdown", trackPointer, true);
    };
  }, []);

  /** 重试结果收口后将消失按钮焦点交给输入框、重试入口或 Tool 详情标题。 */
  useEffect(() => {
    if (retrying || !retryFocusPendingRef.current) return;
    retryFocusPendingRef.current = false;
    if (retryFocusMovedRef.current) return;
    const target =
      retryButtonRef.current ??
      panelRef.current?.querySelector<HTMLTextAreaElement>(".ja-process-session__input textarea") ??
      panelRef.current
        ?.closest(".ja-tool-details")
        ?.querySelector<HTMLButtonElement>(".ja-tool-details__trigger");
    target?.focus();
  }, [retrying, view.phase, view.snapshot?.state]);

  /** 命令自然退出或停止确认后，将消失控制器的焦点交给真实输出区域。 */
  useEffect(() => {
    if (view.snapshot?.state !== "exited" || !terminalFocusPendingRef.current) return;
    terminalFocusPendingRef.current = false;
    terminalFocusOwnerRef.current = null;
    if (!terminalFocusMovedRef.current && canFocusVisibleElement(outputElementRef.current)) {
      outputElementRef.current.focus({ preventScroll: true });
    }
  }, [view.snapshot?.state]);

  /** 只在面板内动作控件持有焦点时登记终态归还，并记住 owner 供迟到回包检查。 */
  const beginTerminalFocusReturn = (): void => {
    const active = document.activeElement;
    terminalFocusPendingRef.current =
      active instanceof HTMLElement && panelRef.current?.contains(active) === true;
    terminalFocusOwnerRef.current = terminalFocusPendingRef.current
      ? (active as HTMLElement)
      : null;
    terminalFocusMovedRef.current = false;
  };

  /** 已完成的读请求可能晚到；输出游标决定仅追加未显示的字符区间。 */
  const acceptSnapshot = useCallback(
    (snapshot: ConversationProcessSnapshot, requestedAfterSequence: number): void => {
      if (
        snapshot.workspaceId !== workspaceId ||
        snapshot.threadId !== threadId ||
        snapshot.sessionId !== sessionIdRef.current ||
        snapshot.generation !== sessionGenerationRef.current ||
        snapshot.startedCallId !== callId ||
        snapshot.nextSequence < cursorRef.current
      )
        return;

      const overlap = Math.max(0, cursorRef.current - requestedAfterSequence);
      const unseen = snapshot.output.slice(overlap);
      const joined = outputRef.current + unseen;
      if (joined.length > MAX_VISIBLE_OUTPUT_LENGTH) {
        outputRef.current = joined.slice(-MAX_VISIBLE_OUTPUT_LENGTH);
        truncatedRef.current = true;
      } else {
        outputRef.current = joined;
      }
      cursorRef.current = Math.max(cursorRef.current, snapshot.nextSequence);
      truncatedRef.current ||= snapshot.outputTruncated;
      setView({
        phase: "ready",
        snapshot,
        output: outputRef.current,
        outputTruncated: truncatedRef.current,
      });
    },
    [callId, threadId, workspaceId],
  );

  /** 单次递归定时器避免慢 RPC 叠加；关闭详情或切换身份时取消旧焦点意图。 */
  useEffect(() => {
    terminalFocusPendingRef.current = false;
    terminalFocusMovedRef.current = false;
    terminalFocusOwnerRef.current = null;
    retryFocusPendingRef.current = false;
    retryFocusMovedRef.current = false;
    if (!enabled) return undefined;
    let timer: number | undefined;
    let disposed = false;
    const token = ++pollTokenRef.current;
    cursorRef.current = 0;
    sessionIdRef.current = undefined;
    sessionGenerationRef.current = undefined;
    registryGenerationRef.current = undefined;
    outputRef.current = "";
    truncatedRef.current = false;
    setView({ phase: "loading", output: "", outputTruncated: false });
    setActionError(undefined);

    /** Reconcile the scoped session and read only bytes after the last accepted cursor. */
    const poll = async (): Promise<void> => {
      if (disposed || token !== pollTokenRef.current) return;
      try {
        const page = await port.list({
          ...requestScope,
          limit: 20,
          cursor: null,
          startedCallId: callId,
        });
        const session = page.sessions.find(
          (candidate) =>
            candidate.workspaceId === workspaceId &&
            candidate.threadId === threadId &&
            candidate.startedCallId === callId,
        );
        if (disposed || token !== pollTokenRef.current) return;
        registryGenerationRef.current = page.generation;
        if (session === undefined) {
          setView((current) => ({ ...current, phase: "missing", error: undefined }));
          return;
        }
        if (sessionIdRef.current === undefined) sessionIdRef.current = session.sessionId;
        if (sessionGenerationRef.current === undefined)
          sessionGenerationRef.current = session.generation;
        if (session.sessionId !== sessionIdRef.current) return;
        const afterSequence = cursorRef.current;
        const snapshot = await port.read({
          ...requestScope,
          generation: page.generation,
          sessionId: session.sessionId,
          afterSequence,
          maxBytes: MAX_READ_BYTES,
        });
        if (disposed || token !== pollTokenRef.current) return;
        acceptSnapshot(snapshot, afterSequence);
        if (snapshot.state === "running" || snapshot.state === "stopping") {
          timer = window.setTimeout(() => void poll(), 900);
        }
      } catch {
        if (!disposed && token === pollTokenRef.current) {
          setView((current) => ({
            ...current,
            phase: "error",
            error: "无法读取命令会话。",
          }));
        }
      } finally {
        if (!disposed && token === pollTokenRef.current && retryingRef.current) {
          retryingRef.current = false;
          setRetrying(false);
        }
      }
    };

    refreshRef.current = () => {
      if (timer !== undefined) window.clearTimeout(timer);
      void poll();
    };
    void poll();
    return () => {
      disposed = true;
      pollTokenRef.current += 1;
      terminalFocusPendingRef.current = false;
      terminalFocusOwnerRef.current = null;
      retryFocusPendingRef.current = false;
      if (timer !== undefined) window.clearTimeout(timer);
    };
  }, [acceptSnapshot, enabled, identity, port, callId, requestScope, threadId, workspaceId]);

  /** 失败重试复用完全相同的输入与 operationKey；EOF 只归还仍属于原动作的焦点。 */
  const submitWrite = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    const snapshot = view.snapshot;
    if (snapshot === undefined || snapshot.state !== "running" || action !== undefined) return;
    const pending = pendingWrite ?? {
      input: draft,
      closeInput,
      operationKey: createOperationKey(),
    };
    if (pending.input.length === 0 && !pending.closeInput) return;
    if (pending.closeInput) beginTerminalFocusReturn();
    setPendingWrite(pending);
    setAction("write");
    setActionError(undefined);
    try {
      const afterSequence = cursorRef.current;
      const generation = registryGenerationRef.current;
      if (generation === undefined) return;
      const result = await port.write({
        ...requestScope,
        generation,
        sessionId: snapshot.sessionId,
        afterSequence,
        input: pending.input,
        closeInput: pending.closeInput,
        operationKey: pending.operationKey,
      });
      acceptSnapshot(result, afterSequence);
      setPendingWrite(undefined);
      setDraft("");
      setCloseInput(false);
      refreshRef.current();
    } catch {
      if (pending.closeInput) {
        terminalFocusPendingRef.current = false;
        terminalFocusOwnerRef.current = null;
      }
      setActionError("输入结果尚未确认。重试会复用同一请求标识。");
    } finally {
      setAction(undefined);
    }
  };

  /** 停止沿用幂等状态动作；只有停止按钮仍持有焦点时才转交给输出区域。 */
  const stopProcess = async (button: HTMLButtonElement): Promise<void> => {
    const snapshot = view.snapshot;
    if (snapshot === undefined || snapshot.state !== "running" || action !== undefined) return;
    if (document.activeElement === button) beginTerminalFocusReturn();
    setAction("stop");
    setActionError(undefined);
    try {
      const generation = registryGenerationRef.current;
      if (generation === undefined) return;
      const afterSequence = cursorRef.current;
      const result = await port.stop({
        ...requestScope,
        generation,
        sessionId: snapshot.sessionId,
      });
      acceptSnapshot(result, afterSequence);
      refreshRef.current();
    } catch {
      terminalFocusPendingRef.current = false;
      terminalFocusOwnerRef.current = null;
      setActionError("无法确认命令是否已停止，请重试读取状态。");
    } finally {
      setAction(undefined);
    }
  };

  /** 读取失败后重查 owner 与当前游标；完成后归还焦点而不替用户改写选择。 */
  const retryRead = (): void => {
    if (retryingRef.current) return;
    retryFocusPendingRef.current = retryButtonRef.current === document.activeElement;
    retryFocusMovedRef.current = false;
    retryingRef.current = true;
    setRetrying(true);
    refreshRef.current();
  };

  if (!enabled) return null;
  const hasSnapshot = view.snapshot !== undefined;
  const output = hasSnapshot ? view.output : (fallbackOutput ?? "");

  return (
    <section ref={panelRef} className="ja-process-session" aria-label="持续命令会话">
      {view.phase === "loading" && !hasSnapshot ? (
        <p className="ja-process-session__notice" role="status">
          <LoaderCircle aria-hidden="true" /> 正在连接命令会话…
        </p>
      ) : null}
      {view.phase === "missing" ? (
        <p className="ja-process-session__empty">本次命令没有可继续的进程会话。</p>
      ) : null}
      {view.phase === "error" ? (
        <div className="ja-process-session__error" role="alert">
          <span>{view.error}</span>
          <button
            ref={retryButtonRef}
            type="button"
            disabled={retrying}
            aria-busy={retrying || undefined}
            onClick={retryRead}
            aria-label="重试读取命令会话"
          >
            {retrying ? <LoaderCircle aria-hidden="true" /> : <RotateCcw aria-hidden="true" />}
            {retrying ? "正在重试" : "重试"}
          </button>
        </div>
      ) : null}
      {view.snapshot?.state === "interrupted" ? (
        <p className="ja-process-session__notice" role="status">
          运行时重启前进程仍在运行。副作用状态未知，请先检查工作区。
        </p>
      ) : null}
      {view.outputTruncated ? (
        <p className="ja-process-session__notice">较早的命令输出已截断。</p>
      ) : null}
      <pre
        ref={outputElementRef}
        className="ja-process-session__output"
        aria-label="命令输出"
        tabIndex={-1}
      >
        {output || (view.snapshot?.state === "running" ? "等待命令输出…" : "没有输出。")}
      </pre>
      {view.snapshot?.state === "running" && view.phase !== "missing" ? (
        <>
          <form className="ja-process-session__input" onSubmit={(event) => void submitWrite(event)}>
            <label className="ja-process-session__label" htmlFor={`process-input-${callId}`}>
              输入到命令
            </label>
            <textarea
              id={`process-input-${callId}`}
              value={pendingWrite?.input ?? draft}
              maxLength={MAX_INPUT_LENGTH}
              disabled={action !== undefined || pendingWrite !== undefined}
              rows={2}
              onChange={(event) => setDraft(event.currentTarget.value)}
            />
            <label className="ja-process-session__close-input">
              <input
                type="checkbox"
                checked={pendingWrite?.closeInput ?? closeInput}
                disabled={action !== undefined || pendingWrite !== undefined}
                onChange={(event) => setCloseInput(event.currentTarget.checked)}
              />
              发送后关闭输入
            </label>
            <div className="ja-process-session__actions">
              <button
                type="submit"
                disabled={
                  action !== undefined ||
                  (draft.length === 0 && !closeInput && pendingWrite === undefined)
                }
              >
                {action === "write" ? (
                  <LoaderCircle aria-hidden="true" />
                ) : pendingWrite === undefined ? (
                  <Send aria-hidden="true" />
                ) : (
                  <RotateCcw aria-hidden="true" />
                )}
                {pendingWrite === undefined
                  ? closeInput && draft.length === 0
                    ? "关闭输入"
                    : "发送输入"
                  : pendingWrite.input.length === 0 && pendingWrite.closeInput
                    ? "重试关闭输入"
                    : "重试发送"}
              </button>
              <button
                type="button"
                className="is-danger"
                disabled={action !== undefined}
                onClick={(event) => void stopProcess(event.currentTarget)}
              >
                {action === "stop" ? (
                  <LoaderCircle aria-hidden="true" />
                ) : (
                  <Square aria-hidden="true" />
                )}
                停止进程
              </button>
            </div>
          </form>
          {actionError === undefined ? null : (
            <p className="ja-process-session__error" role="alert">
              {actionError}
            </p>
          )}
        </>
      ) : view.snapshot === undefined ? null : (
        <p
          className={`ja-process-session__notice${
            view.snapshot.state === "exited" &&
            view.snapshot.exitCode !== null &&
            view.snapshot.exitCode !== 0
              ? " is-error"
              : ""
          }`}
          role={
            view.snapshot.state === "exited" &&
            view.snapshot.exitCode !== null &&
            view.snapshot.exitCode !== 0
              ? "alert"
              : "status"
          }
        >
          {view.snapshot?.state === "stopping"
            ? "正在停止进程…"
            : view.snapshot?.exitCode === null || view.snapshot?.exitCode === undefined
              ? "进程已结束。"
              : `进程已结束，退出码 ${view.snapshot.exitCode}。`}
        </p>
      )}
    </section>
  );
}
