// @author kongweiguang
import { isValidElement, useEffect, useLayoutEffect, useRef, useState } from "react";
import {
  ArrowDown,
  ArrowUp,
  Check,
  ChevronDown,
  Copy,
  FileText,
  LoaderCircle,
  Paperclip,
  Play,
  Plus,
  RotateCcw,
  Square,
  Wrench,
  X,
} from "lucide-react";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import remarkGfm from "remark-gfm";
import { isTerminal } from "./state";
import { AttachmentPreview } from "./AttachmentPreview";
import type {
  AttachmentCapabilities,
  AttachmentDraft,
  AttachmentMetadata,
  ChatTurn,
  InteractionAnswer,
  MessageContent,
  PendingRecoveryResolution,
  PendingSubmission,
  RecoveryView,
  ThreadSession,
} from "./types";

interface ConversationPaneProps {
  threadId: string;
  session: ThreadSession;
  model: string;
  agentName?: string;
  experienceEnabled?: boolean;
  attachmentCapabilities?: AttachmentCapabilities;
  readOnly?: boolean;
  pending?: PendingSubmission;
  onLoadMoreHistory: (threadId: string) => Promise<void>;
  onSubmit: (content: MessageContent, kind?: string) => void;
  onUploadFiles?: (threadId: string, files: File[]) => void;
  onCancelAttachment?: (threadId: string, operationKey: string) => void;
  onRetryAttachment?: (threadId: string, operationKey: string, file?: File) => void;
  onRemoveAttachment?: (threadId: string, operationKey: string) => void;
  onSessionChange: (threadId: string, patch: Partial<ThreadSession>) => void;
  onAnswer: (threadId: string, turn: ChatTurn, answers: InteractionAnswer[]) => Promise<void>;
  onResume: (threadId: string, turn: ChatTurn) => Promise<void>;
  onError?: (error: unknown) => void;
  onRetry: (pending: PendingSubmission) => void;
  onControl: (threadId: string, turn: ChatTurn, control: "pause" | "cancel") => Promise<void>;
  onWorkbench: () => void;
  pendingRecoveries: Record<string, PendingRecoveryResolution>;
  onRefreshRecoveries: (threadId: string, turnId: string) => Promise<RecoveryView[] | undefined>;
  onResolveRecovery?: (
    threadId: string,
    turn: ChatTurn,
    recovery: RecoveryView,
    action: "RETRY" | "SKIP",
  ) => Promise<void>;
}

/** 父组件以会话标识区分界面，滚动状态只初始化一次，实时跟随由用户滚动事件决定。 */
export function ConversationPane(props: ConversationPaneProps) {
  const { threadId, session } = props;
  const scrollRef = useRef<HTMLElement>(null);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const pickerRef = useRef<HTMLInputElement>(null);
  const scrollTimer = useRef<number | undefined>(undefined);
  const restoredThread = useRef<string | undefined>(undefined);
  const followOutput = useRef(true);
  const [showJump, setShowJump] = useState(() => session.scrollTop > 0 && session.turns.length > 0);
  const [inputKind, setInputKind] = useState("STEERING");
  const [composerMenuOpen, setComposerMenuOpen] = useState(false);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const [preview, setPreview] = useState<{
    attachment: AttachmentMetadata;
    trigger: HTMLElement;
  }>();
  const activeTurn = session.turns.at(-1);
  const acceptsInput = Boolean(
    activeTurn &&
      !isTerminal(activeTurn.state) &&
      !activeTurn.resumeRequired &&
      activeTurn.state !== "UNKNOWN" &&
      activeTurn.state !== "HISTORY" &&
      !activeTurn.recoveries.length &&
      !activeTurn.recoveryError,
  );
  const inputDisabled =
    props.readOnly ||
    session.loadingHistory ||
    (session.observing && activeTurn?.state === "SUSPENDED") ||
    activeTurn?.state === "UNKNOWN" ||
    activeTurn?.state === "HISTORY" ||
    Boolean(activeTurn?.recoveries.length || activeTurn?.recoveryError);
  const hasPendingAttachments = session.attachmentDrafts.some(
    (draft) => draft.status !== "uploaded" || !draft.attachment,
  );
  const uploadedAttachments = session.attachmentDrafts.flatMap((draft) =>
    draft.status === "uploaded" && draft.attachment ? [draft.attachment] : [],
  );
  const canSubmit = Boolean(session.draft.trim() || uploadedAttachments.length);
  const submissionDisabled = inputDisabled || Boolean(activeTurn?.resumeRequired);

  useLayoutEffect(() => {
    const scroll = scrollRef.current;
    if (!scroll || session.loadingHistory) return;
    if (restoredThread.current === threadId && !session.historyLoaded) return;
    if (restoredThread.current === threadId) return;
    restoredThread.current = threadId;
    followOutput.current = session.scrollTop === 0;
    scroll.scrollTop = session.scrollTop || scroll.scrollHeight;
  }, [
    threadId,
    session.loadingHistory,
    session.historyLoaded,
    session.scrollTop,
    session.turns.length,
  ]);

  useLayoutEffect(() => {
    if (followOutput.current && scrollRef.current)
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [session.turns]);

  useLayoutEffect(() => {
    if (composerRef.current) resizeComposer(composerRef.current);
  }, [session.draft]);

  useEffect(() => {
    if (!session.loadingHistory) composerRef.current?.focus({ preventScroll: true });
  }, [threadId, session.loadingHistory]);

  useEffect(
    () => () => {
      window.clearTimeout(scrollTimer.current);
    },
    [],
  );

  /** 用户滚动立即决定跟随行为，只对位置持久化防抖，不延迟可见反馈。 */
  const persistScroll = () => {
    const scroll = scrollRef.current;
    if (!scroll) return;
    followOutput.current = scroll.scrollHeight - scroll.clientHeight - scroll.scrollTop < 64;
    setShowJump(!followOutput.current && session.turns.length > 0);
    if (scrollTimer.current !== undefined) window.clearTimeout(scrollTimer.current);
    scrollTimer.current = window.setTimeout(() => {
      const scrollTop = scrollRef.current?.scrollTop ?? 0;
      props.onSessionChange(threadId, { scrollTop });
    }, 160);
  };

  /** 明确返回实时输出，不修改服务端游标或丢弃未读的历史消息。 */
  const jumpToLatest = () => {
    const scroll = scrollRef.current;
    if (!scroll) return;
    followOutput.current = true;
    scroll.scrollTop = scroll.scrollHeight;
    setShowJump(false);
    props.onSessionChange(threadId, { scrollTop: scroll.scrollTop });
    composerRef.current?.focus({ preventScroll: true });
  };

  /** 固定本 Thread 与触发控件，预览字节生命周期由共享对话框管理。 */
  const openPreview = (attachment: AttachmentMetadata, trigger: HTMLElement) => {
    setPreview({ attachment, trigger });
  };

  /** 共享预览负责恢复同 Thread 的输入焦点，这里只结束当前预览生命周期。 */
  const closePreview = () => {
    setPreview(undefined);
  };

  /** 移除会卸载操作按钮，立即回到当前输入框，避免键盘用户失去继续编辑的位置。 */
  const removeDraftAttachment = (operationKey: string) => {
    props.onRemoveAttachment?.(threadId, operationKey);
    composerRef.current?.focus({ preventScroll: true });
  };

  /** 取消会替换上传按钮，先把键盘位置交回输入框，不等待网络核实结局。 */
  const cancelDraftAttachment = (operationKey: string) => {
    props.onCancelAttachment?.(threadId, operationKey);
    composerRef.current?.focus({ preventScroll: true });
  };

  /** 重试保留原操作身份；按钮状态随即变化，选择文件或直接重试均返回当前 Composer。 */
  const retryDraftAttachment = (operationKey: string, file?: File) => {
    props.onRetryAttachment?.(threadId, operationKey, file);
    composerRef.current?.focus({ preventScroll: true });
  };

  /** 文件粘贴仅拦截真正的文件项，不改变普通文本粘贴和输入法行为。 */
  const pasteFiles = (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(event.clipboardData.items)
      .filter((item) => item.kind === "file")
      .flatMap((item) => {
        const file = item.getAsFile();
        return file ? [file] : [];
      });
    if (!files.length) return;
    event.preventDefault();
    props.onUploadFiles?.(threadId, files);
  };

  /** 拖放只接受浏览器提供的文件项，悬停状态独立于滚动与消息选择。 */
  const dropFiles = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDraggingFiles(false);
    const files = Array.from(event.dataTransfer.files);
    if (files.length) props.onUploadFiles?.(threadId, files);
  };

  /** 输入法候选激活时忽略 Enter，Shift+Enter 仍用于输入换行。 */
  const composerKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (
      event.key !== "Enter" ||
      event.shiftKey ||
      event.nativeEvent.isComposing ||
      event.keyCode === 229
    )
      return;
    event.preventDefault();
    event.currentTarget.form?.requestSubmit();
  };

  return (
    <div
      className={`conversation-content ${draggingFiles ? "files-dragging" : ""}`}
      onDragOver={(event) => {
        if (Array.from(event.dataTransfer.types).includes("Files")) {
          event.preventDefault();
          setDraggingFiles(true);
        }
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null))
          setDraggingFiles(false);
      }}
      onDrop={dropFiles}
    >
      {draggingFiles && (
        <div className="attachment-drop-overlay" aria-live="polite">
          松开以上传资料
        </div>
      )}
      <section
        className="conversation-scroll"
        aria-label="对话内容"
        ref={scrollRef}
        onScroll={persistScroll}
      >
        <div className="timeline">
          {session.loadingHistory && !session.turns.length && (
            <div className="history-loading">
              <LoaderCircle className="spin" />
              正在读取历史
            </div>
          )}
          {!session.loadingHistory && !session.turns.length && (
            <div className="welcome-state">
              <h2>Ja</h2>
            </div>
          )}
          {session.turns.map((turn) => (
            <TurnView
              key={turn.turnId}
              threadId={threadId}
              turn={turn}
              pending={props.pending?.turnId === turn.turnId ? props.pending : undefined}
              latest={turn.turnId === activeTurn?.turnId}
              observing={session.observing}
              connectError={session.connectError}
              onAnswer={props.onAnswer}
              onResume={props.onResume}
              onRetry={props.onRetry}
              onControl={props.onControl}
              onPreview={openPreview}
              onResolveRecovery={props.onResolveRecovery}
              pendingRecoveries={props.pendingRecoveries}
              onRefreshRecoveries={props.onRefreshRecoveries}
            />
          ))}
          {session.historyHasMore && (
            <button
              className="load-more-history"
              onClick={() => void props.onLoadMoreHistory(threadId)}
              disabled={session.loadingHistory}
            >
              {session.loadingHistory ? (
                <>
                  <LoaderCircle className="spin" />
                  正在读取历史
                </>
              ) : (
                "继续加载历史"
              )}
            </button>
          )}
        </div>
      </section>

      <footer className="composer-dock">
        {activeTurn?.resumeRequired && (
          <div className="resume-required-composer" role="status">
            <div>
              <strong>执行状态待核实</strong>
              <p>继续后会先核实已提交的保存结果，再恢复这个任务。</p>
            </div>
            <button
              className="small-primary-button"
              type="button"
              onClick={() =>
                void props.onResume(threadId, activeTurn).catch((error) => props.onError?.(error))
              }
            >
              <Play />
              继续以核实保存结果
            </button>
          </div>
        )}
        {showJump && (
          <button
            className="jump-to-latest icon-button"
            type="button"
            title="回到最新消息"
            aria-label="回到最新消息"
            onClick={jumpToLatest}
          >
            <ArrowDown />
          </button>
        )}
        <div
          className="selected-skills"
          aria-label="已选择 Skills"
          hidden={!session.selectedSkills.length}
        >
          {session.selectedSkills.map((skill) => (
            <span className="skill-chip" key={skill}>
              {skillName(skill)}
              <button
                type="button"
                aria-label={`移除 Skill ${skillName(skill)}`}
                title="移除 Skill"
                onClick={() => {
                  props.onSessionChange(threadId, {
                    selectedSkills: session.selectedSkills.filter((value) => value !== skill),
                  });
                  composerRef.current?.focus();
                }}
              >
                <X />
              </button>
            </span>
          ))}
        </div>
        {session.attachmentDrafts.length > 0 && (
          <div className="attachment-tray" aria-label="当前消息附件">
            {session.attachmentDrafts.map((draft) => (
              <AttachmentDraftCard
                key={draft.operationKey}
                threadId={threadId}
                draft={draft}
                onOpen={(attachment, trigger) => void openPreview(attachment, trigger)}
                onCancel={() => cancelDraftAttachment(draft.operationKey)}
                onRetry={(file) => retryDraftAttachment(draft.operationKey, file)}
                onRemove={() => removeDraftAttachment(draft.operationKey)}
              />
            ))}
          </div>
        )}
        <form
          className="composer"
          onSubmit={(event) => {
            event.preventDefault();
            if (submissionDisabled || hasPendingAttachments || !canSubmit) return;
            followOutput.current = true;
            setShowJump(false);
            props.onSubmit(
              {
                text: session.draft,
                skillIds: session.selectedSkills,
                attachmentIds: uploadedAttachments.map((attachment) => attachment.id),
              },
              acceptsInput ? inputKind : undefined,
            );
            composerRef.current?.focus({ preventScroll: true });
          }}
        >
          <textarea
            ref={composerRef}
            data-thread-id={threadId}
            value={session.draft}
            rows={1}
            maxLength={100_000}
            placeholder={props.readOnly ? "已归档会话" : acceptsInput ? "补充消息…" : "随心输入"}
            aria-label="消息"
            disabled={inputDisabled}
            onChange={(event) => props.onSessionChange(threadId, { draft: event.target.value })}
            onKeyDown={composerKeyDown}
            onPaste={pasteFiles}
          />
          <div className="composer-toolbar">
            <input
              ref={pickerRef}
              className="visually-hidden"
              type="file"
              multiple
              accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,.png,.jpg,.jpeg,.gif,.webp,.pdf"
              aria-label="选择图片或 PDF 资料"
              onChange={(event) => {
                const files = Array.from(event.currentTarget.files ?? []);
                event.currentTarget.value = "";
                if (files.length) props.onUploadFiles?.(threadId, files);
                composerRef.current?.focus({ preventScroll: true });
              }}
            />
            <div className="composer-add-menu">
              <button
                className="icon-button"
                type="button"
                title="添加资料或 Skill"
                aria-label="添加资料或 Skill"
                aria-expanded={composerMenuOpen}
                onClick={() => setComposerMenuOpen((open) => !open)}
              >
                <Plus />
              </button>
              {composerMenuOpen && (
                <div className="composer-add-popover" role="menu">
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => {
                      setComposerMenuOpen(false);
                      pickerRef.current?.click();
                    }}
                  >
                    <Paperclip />
                    上传资料
                  </button>
                  {props.experienceEnabled && (
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => {
                        setComposerMenuOpen(false);
                        props.onWorkbench();
                      }}
                    >
                      <FileText />
                      选择 Skill
                    </button>
                  )}
                </div>
              )}
            </div>
            {acceptsInput ? (
              <label className="mode-select">
                <select
                  value={inputKind}
                  onChange={(event) => setInputKind(event.target.value)}
                  aria-label="执行中的消息发送方式"
                >
                  <option value="STEERING">即时指导</option>
                  <option value="FOLLOW_UP">后续消息</option>
                  <option value="MESSAGE">普通消息</option>
                </select>
              </label>
            ) : (
              <span className="composer-agent" title={props.agentName}>
                {props.agentName}
              </span>
            )}
            <span className="composer-model" title={props.model}>
              {props.model || "模型未配置"}
            </span>
            {acceptsInput && activeTurn && (
              <button
                type="button"
                className="send-button composer-cancel"
                title="取消执行"
                aria-label="取消执行"
                disabled={activeTurn.cancelRequested}
                onClick={() => void props.onControl(threadId, activeTurn, "cancel")}
              >
                {activeTurn.cancelRequested ? <LoaderCircle className="spin" /> : <Square />}
              </button>
            )}
            {!activeTurn?.resumeRequired && (!acceptsInput || session.draft.trim()) && (
              <button
                className="send-button"
                type="submit"
                title={acceptsInput ? "加入队列" : "发送"}
                aria-label={acceptsInput ? "加入队列" : "发送"}
                disabled={!canSubmit || hasPendingAttachments || submissionDisabled}
              >
                <ArrowUp />
              </button>
            )}
          </div>
        </form>
        {session.draft.length > 80_000 && (
          <div className="composer-limit">{session.draft.length.toLocaleString()} / 100,000</div>
        )}
        {hasPendingAttachments && (
          <p className="attachment-send-hint" role="status">
            附件上传完成或核实前，消息会保留在草稿中。
          </p>
        )}
      </footer>
      {preview && (
        <AttachmentPreview
          key={`${threadId}:${preview.attachment.id}`}
          threadId={threadId}
          attachment={preview.attachment}
          returnFocusTo={preview.trigger}
          onClose={closePreview}
        />
      )}
    </div>
  );
}

interface TurnViewProps {
  threadId: string;
  turn: ChatTurn;
  pending?: PendingSubmission;
  pendingRecoveries: Record<string, PendingRecoveryResolution>;
  onRefreshRecoveries: ConversationPaneProps["onRefreshRecoveries"];
  onResolveRecovery: ConversationPaneProps["onResolveRecovery"];
  latest: boolean;
  observing?: boolean;
  connectError?: string;
  onAnswer: ConversationPaneProps["onAnswer"];
  onResume: ConversationPaneProps["onResume"];
  onRetry: ConversationPaneProps["onRetry"];
  onControl: ConversationPaneProps["onControl"];
  onPreview: (attachment: AttachmentMetadata, trigger: HTMLElement) => void;
}

/** 测量真实文本框以恢复多行草稿，使用纯辅助函数避免副作用依赖渲染闭包。 */
function resizeComposer(target: HTMLTextAreaElement): void {
  target.style.height = "0px";
  target.style.height = `${Math.min(168, Math.max(50, target.scrollHeight))}px`;
}

/** 展示服务端附件元数据并复用同一 Thread 的受控预览入口。 */
function AttachmentButton({
  threadId,
  attachment,
  onOpen,
}: {
  threadId: string;
  attachment: AttachmentMetadata;
  onOpen: (attachment: AttachmentMetadata, trigger: HTMLElement) => void;
}) {
  const image = attachment.mediaType.startsWith("image/");
  const source = `/api/chat/attachments/${encodeURIComponent(attachment.id)}/content?threadId=${encodeURIComponent(threadId)}`;
  return (
    <button
      className="attachment-chip attachment-open"
      type="button"
      onClick={(event) => onOpen(attachment, event.currentTarget)}
      title={`预览 ${attachment.fileName}`}
    >
      {image ? (
        <img className="attachment-thumb" src={source} alt="" loading="lazy" />
      ) : (
        <span className="attachment-file-icon">
          <FileText aria-hidden="true" />
        </span>
      )}
      <span className="attachment-name">{attachment.fileName}</span>
      <small>{formatBytes(attachment.sizeBytes)}</small>
    </button>
  );
}

/** 将上传不确定、失败重试和可移除状态明确分开，避免把中止误当作服务器取消。 */
function AttachmentDraftCard({
  threadId,
  draft,
  onOpen,
  onCancel,
  onRetry,
  onRemove,
}: {
  threadId: string;
  draft: AttachmentDraft;
  onOpen: (attachment: AttachmentMetadata, trigger: HTMLElement) => void;
  onCancel: () => void;
  onRetry: (file?: File) => void;
  onRemove: () => void;
}) {
  const fileInput = useRef<HTMLInputElement>(null);
  const status =
    draft.status === "uploading"
      ? "正在上传"
      : draft.status === "unknown"
        ? "结果待核实"
        : draft.status === "error"
          ? "上传失败"
          : "已上传";
  const reconcileLabel = draft.removeRequested
    ? "核实并移除"
    : draft.cancelRequested
      ? "核实取消"
      : "使用原文件重试";
  return (
    <article className={`attachment-draft attachment-${draft.status}`}>
      {draft.attachment ? (
        <AttachmentButton threadId={threadId} attachment={draft.attachment} onOpen={onOpen} />
      ) : (
        <div className="attachment-draft-info">
          <span className="attachment-file-icon">
            <FileText aria-hidden="true" />
          </span>
          <span className="attachment-name">{draft.fileName}</span>
          <small>{formatBytes(draft.sizeBytes)}</small>
        </div>
      )}
      <span className="attachment-status">
        {draft.status === "uploading" && <LoaderCircle className="spin" aria-hidden="true" />}
        {status}
      </span>
      {draft.error && (
        <span className="attachment-error" role="status">
          {draft.error}
        </span>
      )}
      {draft.status === "uploading" ? (
        <button
          className="icon-button attachment-action"
          type="button"
          title="取消上传"
          aria-label={`取消上传 ${draft.fileName}`}
          onClick={onCancel}
        >
          <X />
        </button>
      ) : (
        <>
          {draft.status !== "uploaded" && (
            <>
              <button
                className="icon-button attachment-action"
                type="button"
                title={reconcileLabel}
                aria-label={`${reconcileLabel} ${draft.fileName}`}
                onClick={() => onRetry()}
              >
                <RotateCcw />
              </button>
              <input
                ref={fileInput}
                className="visually-hidden"
                type="file"
                accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,.png,.jpg,.jpeg,.gif,.webp,.pdf"
                aria-label={`重新选择 ${draft.fileName}`}
                onChange={(event) => {
                  const file = event.currentTarget.files?.[0];
                  event.currentTarget.value = "";
                  if (file) onRetry(file);
                }}
              />
              {draft.status === "unknown" && (
                <button
                  className="text-button attachment-reselect"
                  type="button"
                  onClick={() => fileInput.current?.click()}
                >
                  重选原文件
                </button>
              )}
            </>
          )}
          <button
            className="icon-button attachment-action"
            type="button"
            title="移除附件"
            aria-label={`移除附件 ${draft.fileName}`}
            onClick={onRemove}
          >
            <X />
          </button>
        </>
      )}
    </article>
  );
}

/** 恢复动作直接来自 Core 允许列表；工具结果未知时不会自动重放副作用。 */
function RecoveryCard({
  threadId,
  turn,
  recovery,
  pending,
  onResolve,
}: {
  threadId: string;
  turn: ChatTurn;
  recovery: RecoveryView;
  pending?: PendingRecoveryResolution;
  onResolve: NonNullable<ConversationPaneProps["onResolveRecovery"]>;
}) {
  const [busy, setBusy] = useState(false);
  const toolName = turn.items.find(
    (item) => item.kind === "tool" && item.callId === recovery.callId,
  )?.name;
  const actions = pending ? [pending.decision] : recovery.allowedActions;
  const resolve = async (decision: "RETRY" | "SKIP") => {
    setBusy(true);
    try {
      await onResolve(threadId, turn, recovery, decision);
    } catch {
      // 控制器负责同步裁决状态和可见错误。
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="recovery-card" aria-label="待核实操作">
      <div>
        <strong>操作结果待核实</strong>
        <p>
          {toolName ? `${toolName} 尚未确认是否生效。` : "有一项工具操作尚未确认是否生效。"}
          先检查持久结果，再决定是否重试或跳过。
        </p>
        {pending && <small role="status">裁决结果待核实，继续操作会沿用原身份。</small>}
      </div>
      <div className="recovery-actions">
        {actions.includes("RETRY") && (
          <button
            className="small-primary-button"
            type="button"
            disabled={busy}
            onClick={() => void resolve("RETRY")}
          >
            {busy ? <LoaderCircle className="spin" /> : <RotateCcw />}
            {pending ? "检查并继续" : "明确重试"}
          </button>
        )}
        {actions.includes("SKIP") && (
          <button
            className="text-button"
            type="button"
            disabled={busy}
            onClick={() => void resolve("SKIP")}
          >
            {busy ? <LoaderCircle className="spin" /> : <Check />}
            {pending ? "检查并跳过" : "跳过本次操作"}
          </button>
        )}
      </div>
    </section>
  );
}

/** 按事件顺序展示模型输出与工具工作，失败详情展开，常规工具详情折叠。 */
function TurnView(props: TurnViewProps) {
  const { turn } = props;
  const tools = new Set(
    turn.items.filter((item) => item.kind === "tool").map((item) => item.callId),
  );
  const lastAssistant = [...turn.items].reverse().find((item) => item.kind === "assistant");
  const showFinalAnswer = Boolean(turn.answer && lastAssistant?.text !== turn.answer);
  return (
    <article className="turn-block" aria-label="对话轮次">
      {(turn.userText || turn.attachments.length > 0) && (
        <div className="user-message-row">
          <div className="user-message">
            {turn.userText && <div>{turn.userText}</div>}
            {turn.attachments.length > 0 && (
              <div className="message-attachments" aria-label="消息资料">
                {turn.attachments.map((attachment) => (
                  <AttachmentButton
                    key={attachment.id}
                    threadId={props.threadId}
                    attachment={attachment}
                    onOpen={(value, trigger) => void props.onPreview(value, trigger)}
                  />
                ))}
              </div>
            )}
          </div>
        </div>
      )}
      <div className="assistant-message">
        {turn.items.map((item) => {
          if (item.kind === "reasoning")
            return (
              <div className="reasoning-message" key={item.key}>
                <span className="reasoning-mark" aria-hidden="true" />
                {renderMarkdown(item.text)}
              </div>
            );
          if (item.kind === "assistant")
            return (
              <div className="assistant-prose" key={item.key}>
                {renderMarkdown(item.text)}
              </div>
            );
          if (item.kind === "tool") {
            const output = turn.items.find(
              (candidate) => candidate.kind === "tool-result" && candidate.callId === item.callId,
            );
            const failed = item.state === "FAILED" || output?.state === "FAILED";
            return (
              <details
                className={`tool-detail ${failed ? "tool-failed" : ""}`}
                key={item.key}
                open={failed}
              >
                <summary>
                  <Wrench aria-hidden="true" />
                  <span>{item.name || "工具调用"}</span>
                  <ChevronDown className="tool-chevron" aria-hidden="true" />
                </summary>
                {output && <pre>{output.text || "（没有返回文本）"}</pre>}
              </details>
            );
          }
          if (item.kind === "tool-result" && !tools.has(item.callId))
            return (
              <details
                className={`tool-detail ${item.state === "FAILED" ? "tool-failed" : ""}`}
                key={item.key}
                open={item.state === "FAILED"}
              >
                <summary>
                  <Wrench aria-hidden="true" />
                  <span>工具结果</span>
                  <ChevronDown className="tool-chevron" aria-hidden="true" />
                </summary>
                <pre>{item.text || "（没有返回文本）"}</pre>
              </details>
            );
          return null;
        })}
        {Object.entries(turn.drafts).map(([modelRequestId, draft]) => (
          <div className="stream-draft" key={modelRequestId}>
            {draft.reasoning && (
              <div className="reasoning-message">{renderMarkdown(draft.reasoning)}</div>
            )}
            {draft.text && <div className="assistant-prose">{renderMarkdown(draft.text)}</div>}
          </div>
        ))}
        {showFinalAnswer && turn.answer && (
          <div className="final-answer">{renderMarkdown(turn.answer)}</div>
        )}
        {turn.error && <p className="turn-error">{turn.error}</p>}
        {props.latest && props.connectError && <p className="turn-error">{props.connectError}</p>}
        {props.latest && turn.state === "UNKNOWN" && props.pending && (
          <div className="pending-recovery">
            <span>提交结果尚未确认</span>
            <button className="text-button" onClick={() => props.onRetry(props.pending!)}>
              检查并恢复发送
            </button>
          </div>
        )}
        {props.latest && turn.recoveryError && (
          <div className="pending-recovery" role="alert">
            <span>恢复状态暂不可用：{turn.recoveryError}</span>
            <button
              className="text-button"
              type="button"
              onClick={() => void props.onRefreshRecoveries(props.threadId, turn.turnId)}
            >
              重新检查
            </button>
          </div>
        )}
        {props.latest &&
          props.onResolveRecovery &&
          turn.recoveries.map((recovery) => (
            <RecoveryCard
              key={recovery.recoveryId}
              threadId={props.threadId}
              turn={turn}
              recovery={recovery}
              pending={
                props.pendingRecoveries[
                  recoveryOperationId(props.threadId, turn.turnId, recovery.recoveryId)
                ]
              }
              onResolve={props.onResolveRecovery!}
            />
          ))}
        {props.latest && turn.wait && turn.wait.status === "PENDING" && !turn.waitAnswered && (
          <WaitForm
            threadId={props.threadId}
            turn={turn}
            onAnswer={props.onAnswer}
            onPreview={props.onPreview}
          />
        )}
        {props.latest &&
          turn.waitAnswered &&
          turn.state === "SUSPENDED" &&
          !turn.recoveries.length &&
          !turn.recoveryError &&
          !props.observing && (
            <div className="wait-answered">
              <span>回答已保存</span>
              <button
                className="text-button"
                onClick={() => void props.onResume(props.threadId, turn).catch(() => undefined)}
              >
                <Play />
                继续执行
              </button>
            </div>
          )}
        {props.latest &&
          turn.state === "SUSPENDED" &&
          !turn.waitAnswered &&
          !turn.recoveries.length &&
          !turn.recoveryError &&
          !props.observing && (
            <button
              className="text-button resume-inline"
              onClick={() => void props.onResume(props.threadId, turn).catch(() => undefined)}
            >
              <Play />
              继续执行
            </button>
          )}
        {props.latest && turn.state === "WAITING_APPROVAL" && (
          <p className="wait-hint">等待确认后继续。</p>
        )}
        {props.latest && turn.state === "SUSPENDED" && props.observing && (
          <span className="connection-label" role="status">
            继续请求已受理，正在确认执行状态。
          </span>
        )}
        {props.latest && !turn.resumeRequired && ["QUEUED", "RUNNING"].includes(turn.state) && (
          <span className="working-indicator">
            正在工作
            <span className="working-dots" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
          </span>
        )}
        {props.latest && turn.state === "HISTORY" && (
          <span className="historical-label">历史记录</span>
        )}
        {props.latest && turn.state === "UNKNOWN" && !props.connectError && (
          <span className="connection-label">执行状态未确认</span>
        )}
        {props.latest && turn.resumeRequired && (
          <span className="connection-label" role="status">
            执行状态待核实，继续操作将先核实保存结果。
          </span>
        )}
      </div>
    </article>
  );
}

/** 仅渲染服务端过滤后的 Markdown 节点，避免模型文本引入原始 HTML 或不安全链接。 */
function renderMarkdown(value: string) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      rehypePlugins={[rehypeSanitize]}
      components={{ img: () => null, pre: CodeBlock }}
    >
      {value}
    </ReactMarkdown>
  );
}

/** 代码复制入口紧邻代码块，Clipboard API 不可用时使用明确的降级处理。 */
function CodeBlock({ children }: React.ComponentProps<"pre">) {
  const [copied, setCopied] = useState(false);
  const [copyFailed, setCopyFailed] = useState(false);
  const copy = async () => {
    try {
      await copyPlainText(flattenText(children));
      setCopied(true);
      setCopyFailed(false);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {
      setCopyFailed(true);
    }
  };
  return (
    <div className="code-block">
      <div className="code-toolbar">
        <span>代码</span>
        <button
          className="icon-button"
          type="button"
          title={copyFailed ? "复制失败" : copied ? "已复制" : "复制代码"}
          aria-label={copyFailed ? "复制失败" : copied ? "已复制" : "复制代码"}
          onClick={() => void copy()}
        >
          {copied ? <Check /> : <Copy />}
        </button>
      </div>
      <pre>{children}</pre>
    </div>
  );
}

/** 仅提取代码子节点中的文本，避免复制操作序列化 React 元素元数据。 */
function flattenText(value: React.ReactNode): string {
  if (typeof value === "string" || typeof value === "number") return String(value);
  if (Array.isArray(value)) return value.map(flattenText).join("");
  if (isValidElement<{ children?: React.ReactNode }>(value))
    return flattenText(value.props.children);
  return "";
}

/** 优先使用异步剪贴板，仅在浏览器不支持时通过临时选区复制。 */
async function copyPlainText(value: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const field = document.createElement("textarea");
  field.value = value;
  field.setAttribute("readonly", "");
  field.style.position = "fixed";
  field.style.opacity = "0";
  document.body.append(field);
  field.select();
  const copied = document.execCommand("copy");
  field.remove();
  if (!copied) throw new Error("Clipboard access unavailable");
}

/** 展示 Skill 的通用名称，在状态与请求中保留带来源信息的标识。 */
function skillName(reference: string): string {
  const separator = reference.lastIndexOf(":");
  return separator < 0 ? reference : reference.slice(separator + 1);
}

/** 展示文件元数据的精确十进制 MB 预算，与模型声明的字节上限保持一致。 */
function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) return "未知大小";
  if (value < 1_000_000) return `${value.toLocaleString()} B`;
  return `${(value / 1_000_000).toLocaleString(undefined, { maximumFractionDigits: 1 })} MB`;
}

/** 构造与恢复状态字典相同的 Thread/Turn/recovery 组合键。 */
function recoveryOperationId(threadId: string, turnId: string, recoveryId: string): string {
  return JSON.stringify([threadId, turnId, recoveryId]);
}

interface WaitFormProps {
  threadId: string;
  turn: ChatTurn;
  onAnswer: ConversationPaneProps["onAnswer"];
  onPreview: TurnViewProps["onPreview"];
}

/**
 * @author kongweiguang
 * 审批摘要只改变呈现；回答始终携带 Core 原问题和稳定选项标识，最终参数保持冻结。
 */
function WaitForm({ threadId, turn, onAnswer, onPreview }: WaitFormProps) {
  const [choices, setChoices] = useState<Record<string, string[]>>({});
  const [freeText, setFreeText] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  if (!turn.wait) return null;
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const answers: InteractionAnswer[] = turn.wait!.questions.map((question) => {
      const optionIds = choices[question.questionId] ?? [];
      const text = freeText[question.questionId]?.trim() || null;
      return {
        questionId: question.questionId,
        optionIds,
        freeText: text,
        skipped: !optionIds.length && !text,
      };
    });
    if (
      turn.wait!.questions.some(
        (question) =>
          question.required &&
          !choices[question.questionId]?.length &&
          !freeText[question.questionId]?.trim(),
      )
    )
      return;
    setBusy(true);
    try {
      await onAnswer(threadId, turn, answers);
    } catch {
      /* 控制器保留已回答或待处理状态的可见性，供用户明确重试。 */
    } finally {
      setBusy(false);
    }
  };
  return (
    <form className="wait-form" onSubmit={(event) => void submit(event)}>
      <div className="wait-heading">
        {turn.wait.kind === "approval" ? "需要你的确认" : "请回答以下问题"}
      </div>
      {turn.wait.questions.map((question) => {
        const note = noteApprovalSummary(turn.wait!.kind, question.questionId, question.prompt);
        return (
          <fieldset key={question.questionId}>
            <legend>
              {note ? "是否保存这份笔记？" : question.prompt}
              {question.required && <span aria-hidden="true"> *</span>}
            </legend>
            {note && (
              <div className="approval-note">
                <dl>
                  <dt>笔记标题</dt>
                  <dd>{note.title}</dd>
                  <dt>笔记正文</dt>
                  <dd
                    className="approval-note-body"
                    role="region"
                    aria-label="待保存的笔记正文"
                    tabIndex={0}
                  >
                    {note.body}
                  </dd>
                  <dt>关联资料 · {note.sourceAttachmentIds.length} 份</dt>
                  <dd className="queue-attachments">
                    {note.sourceAttachmentIds.map((id, index) => {
                      const attachment = turn.attachments.find((value) => value.id === id);
                      return attachment ? (
                        <AttachmentButton
                          key={id}
                          threadId={threadId}
                          attachment={attachment}
                          onOpen={onPreview}
                        />
                      ) : (
                        <span key={id}>资料 {index + 1}（名称未加载）</span>
                      );
                    })}
                    {!note.sourceAttachmentIds.length && <span>未关联资料</span>}
                  </dd>
                </dl>
                <details className="schema-detail approval-technical">
                  <summary>
                    <ChevronDown />
                    技术详情
                  </summary>
                  <pre>{question.prompt}</pre>
                </details>
              </div>
            )}
            {question.options.map((option) => {
              const checked = (choices[question.questionId] ?? []).includes(option.optionId);
              const multiple = question.type === "MULTIPLE";
              const descriptionId = `${turn.turnId}:${question.questionId}:${option.optionId}:description`;
              return (
                <label
                  className={`choice-row ${checked ? "choice-selected" : ""}`}
                  key={option.optionId}
                >
                  <input
                    type={multiple ? "checkbox" : "radio"}
                    name={`${turn.turnId}:${question.questionId}`}
                    value={option.optionId}
                    aria-label={option.label}
                    aria-describedby={descriptionId}
                    checked={checked}
                    onChange={() => {
                      setFreeText((current) => ({ ...current, [question.questionId]: "" }));
                      setChoices((current) => {
                        const old = current[question.questionId] ?? [];
                        const next = multiple
                          ? checked
                            ? old.filter((value) => value !== option.optionId)
                            : [...old, option.optionId]
                          : [option.optionId];
                        return { ...current, [question.questionId]: next };
                      });
                    }}
                  />
                  <span className="choice-content">
                    <span className="choice-label">
                      {option.label}
                      {option.recommended && <small className="choice-recommended">推荐</small>}
                    </span>
                    <small id={descriptionId} className="choice-description">
                      {option.description}
                    </small>
                  </span>
                </label>
              );
            })}
            {(question.type === "TEXT" || question.allowFreeText) && (
              <textarea
                aria-label={question.prompt}
                rows={2}
                value={freeText[question.questionId] ?? ""}
                onChange={(event) => {
                  const value = event.target.value;
                  setFreeText((current) => ({ ...current, [question.questionId]: value }));
                  if (value.trim())
                    setChoices((current) => ({ ...current, [question.questionId]: [] }));
                }}
              />
            )}
          </fieldset>
        );
      })}
      <div className="wait-actions">
        <button className="primary-button" type="submit" disabled={busy}>
          {busy ? (
            <>
              <LoaderCircle className="spin" />
              提交中
            </>
          ) : (
            <>
              <Play />
              确认并继续
            </>
          )}
        </button>
      </div>
    </form>
  );
}

/** 仅解读宿主生成的原生 save_note 审批；未知结构原样展示，避免隐藏额外授权参数或把普通问题当审批。 */
function noteApprovalSummary(kind: string, questionId: string, prompt: string) {
  const prefix = "允许执行 save_note？\n";
  if (kind !== "approval" || questionId !== "question_approval" || !prompt.startsWith(prefix))
    return undefined;
  try {
    const value: unknown = JSON.parse(prompt.slice(prefix.length));
    if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
    const parameters = value as Record<string, unknown>;
    if (
      Object.keys(parameters).some(
        (key) => !["title", "body", "sourceAttachmentIds"].includes(key),
      ) ||
      typeof parameters["title"] !== "string" ||
      typeof parameters["body"] !== "string" ||
      !Array.isArray(parameters["sourceAttachmentIds"]) ||
      !parameters["sourceAttachmentIds"].every((id: unknown) => typeof id === "string")
    )
      return undefined;
    return {
      title: parameters["title"],
      body: parameters["body"],
      sourceAttachmentIds: parameters["sourceAttachmentIds"] as string[],
    };
  } catch {
    return undefined;
  }
}
