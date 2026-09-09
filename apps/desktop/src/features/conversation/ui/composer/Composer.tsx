// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  ArrowUp,
  Check,
  ChevronDown,
  ChevronRight,
  CornerUpRight,
  Ellipsis,
  File,
  FileText,
  LoaderCircle,
  Image as ImageIcon,
  ListRestart,
  Pencil,
  Plus,
  Play,
  Square,
  Target,
  Trash2,
  X,
} from "lucide-react";
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ClipboardEvent,
  type ChangeEvent,
  type CSSProperties,
  type FormEvent,
  type KeyboardEvent,
  type MouseEvent,
  type ReactElement,
  type ReactNode,
  type SetStateAction,
} from "react";
import {
  IconButton,
  Menu,
  MenuContent,
  MenuItem,
  MenuItemIndicator,
  MenuLabel,
  MenuRadioGroup,
  MenuRadioItem,
  MenuSeparator,
  MenuSub,
  MenuSubContent,
  MenuSubTrigger,
  MenuTrigger,
  Select,
  Tooltip,
} from "@/shared/ui/primitives";
import { cn } from "@/shared/ui/primitives/cn";
import type {
  ConversationAccessMode,
  ConversationAttachment,
  ConversationAttachmentDraftItem,
  ConversationModelOption,
  ConversationSubmit,
  ConversationThreadPreferences,
  ReasoningLevel,
} from "../../application/ports";
import type { ContextUsagePresentation } from "../../domain/contextUsage";
import type { AttachmentSummary } from "../../domain/timelineContracts";
import {
  contextReferenceIdentity,
  contextReferencesFromUserContent,
  referenceToUserContent,
  resolveSkillReferenceMetadata,
  textFromUserContent,
  type ConversationContextReference,
  type UserContentBlock,
} from "../../domain/userContent";
import { ContextUsageIndicator } from "./ContextUsageIndicator";
import { ComposerContextChips } from "./ComposerContextChips";
import { ComposerSuggestionPanel, type ComposerSuggestionItem } from "./ComposerSuggestionPanel";
import { splitFileName } from "./filePresentation";
import {
  filterComposerCommands,
  filterComposerSkills,
  findComposerTrigger,
  parseComposerSlashInvocation,
  removeComposerTrigger,
  skillSuggestionReference,
  workspaceSuggestionReference,
  type ComposerSkillSuggestion,
  type ComposerSlashCommand,
  type ComposerWorkspaceSearchResult,
} from "./composerSuggestions";
import "./composer.css";

export type ComposerSubmit = ConversationSubmit;

export type ComposerQueuedInputKind = "follow_up" | "steering";
export type ComposerQueuedInputBusyAction = "prioritize" | "update" | "delete";

/**
 * Composer 只接收队列的可渲染投影；顺序、revision 与持久化仍由 application owner 决定，
 * pending/busy/error 只补足 ACK 到达前及失败恢复所需的局部视觉事实。
 */
export interface ComposerQueuedInputView {
  inputId: string;
  content: readonly UserContentBlock[];
  attachments: readonly AttachmentSummary[];
  kind: ComposerQueuedInputKind;
  status: "pending" | "needs_attention";
  issue: { errorCode: string; message: string; retryable: boolean } | null;
  inputRevision: number;
  createdAt: string;
  pending?: boolean;
  busyAction?: ComposerQueuedInputBusyAction;
  error?: string;
}

export interface ComposerProps {
  preferences?: ConversationThreadPreferences;
  /** 非默认 Plan/Goal 状态由 Goal feature 提供，默认态不占用工具栏空间。 */
  modeStatus?: ReactNode;
  /** 活跃 Goal 的紧凑投影由 composition root 注入，终态由时间线负责。 */
  goalStatus?: ReactNode;
  placeholder?: string;
  /** 草稿由会话层唯一持有，Composer 只投影文本并上报编辑意图。 */
  text: string;
  onTextChange: (text: string) => void;
  contextReferences?: readonly ConversationContextReference[];
  onContextReferencesChange?: (references: readonly ConversationContextReference[]) => void;
  threadId?: string;
  workspaceId?: string;
  runtimeGeneration?: number;
  skills?: readonly ComposerSkillSuggestion[];
  slashCommands?: readonly ComposerSlashCommand[];
  onSearchWorkspacePaths?: (query: string) => Promise<ComposerWorkspaceSearchResult>;
  models?: readonly ConversationModelOption[];
  attachments?: readonly ConversationAttachment[];
  attachmentDraftItems?: readonly ConversationAttachmentDraftItem[];
  activeTurn?: boolean;
  suspendedTurn?: boolean;
  disabled?: boolean;
  preferenceBusy?: boolean;
  importingAttachments?: boolean;
  sending?: boolean;
  /** Application 确认准入失败并恢复草稿后递增，避免仅靠 error 文案猜测恢复时机。 */
  draftRecoveryRevision?: number;
  cancelling?: boolean;
  resuming?: boolean;
  error?: string;
  queuedInputs?: readonly ComposerQueuedInputView[];
  queueAccepting?: boolean;
  /** 仅当 application 已证明模型身份并取得真实 Token 计量时提供。 */
  contextUsage?: ContextUsagePresentation;
  onModelChange?: (selectionValue: string) => void;
  onReasoningChange?: (reasoningLevel: ReasoningLevel | null) => void;
  onAccessModeChange?: (accessMode: ConversationAccessMode) => void;
  onRestoreDefaults?: () => void | Promise<void>;
  onAddAttachments?: () => void | Promise<void>;
  onRetryAttachment?: (itemId: string) => void | Promise<void>;
  onRemoveAttachment?: (itemId: string) => void | Promise<void>;
  onPasteAttachments?: () => void | Promise<void>;
  onDropAttachments?: (dropToken: string) => void | Promise<void>;
  onOpenAttachmentPreview?: (attachment: ConversationAttachment, source: HTMLButtonElement) => void;
  onOpenWorkspaceReference?: (
    reference: Extract<ConversationContextReference, { type: "workspace_reference" }>,
    source: HTMLButtonElement,
  ) => void;
  onOpenQueuedAttachmentPreview?: (
    attachment: ConversationAttachment,
    source: HTMLButtonElement,
  ) => void;
  nativeDropEvent?: ComposerNativeDropEvent;
  dropZoneRef?: (element: HTMLFormElement | null) => void;
  onSend: (request: ComposerSubmit) => void | Promise<void>;
  /** 活动 Turn 的默认提交固定为普通 FIFO；优先语义只允许从已入队对象上显式触发。 */
  onEnqueue?: (request: ComposerSubmit) => void | Promise<void>;
  onPrioritizeQueuedInput?: (
    inputId: string,
    expectedInputRevision: number,
  ) => void | Promise<void>;
  onUpdateQueuedInput?: (
    inputId: string,
    expectedInputRevision: number,
    content: readonly UserContentBlock[],
  ) => void | Promise<void>;
  onDeleteQueuedInput?: (inputId: string, expectedInputRevision: number) => void | Promise<void>;
  onResume?: () => void | Promise<void>;
  onCancel?: () => void | Promise<void>;
  className?: string;
}

export interface ComposerNativeDropEvent {
  phase: "enter" | "over" | "leave" | "drop";
  x: number;
  y: number;
  count: number;
  dropToken?: string;
}

const TEXTAREA_MIN_HEIGHT = 32;
const TEXTAREA_MAX_LINES = 6;
const QUEUED_INPUT_MAX_LENGTH = 65_536;
const MODEL_DEFAULT_REASONING_VALUE = "model_default";

interface ComposerSuggestionSessionState {
  scopeIdentity: string;
  dismissedTrigger?: string;
  commandError?: string;
}

interface ComposerCaretState {
  text: string;
  position: number;
}

interface ComposerInlineCommandState {
  scopeIdentity: string;
  command: ComposerSlashCommand;
}

/** 命令名称匹配使用 NFKC 与稳定别名，不让大小写或全角输入制造第二条执行路径。 */
function slashCommandMatches(command: ComposerSlashCommand, name: string): boolean {
  const normalized = name.normalize("NFKC").toLocaleLowerCase();
  return [command.name, ...command.aliases].some(
    (candidate) => candidate.normalize("NFKC").toLocaleLowerCase() === normalized,
  );
}

/** 根据内容和当前字体行高重设高度，限制在 1–6 行以内而不撑破底部面板。 */
function resizeTextarea(element: HTMLTextAreaElement): void {
  const styles = window.getComputedStyle(element);
  const lineHeight = Number.parseFloat(styles.lineHeight) || 22;
  const maxHeight = Math.max(TEXTAREA_MIN_HEIGHT, lineHeight * TEXTAREA_MAX_LINES);
  element.style.height = "0px";
  const contentHeight = Math.max(element.scrollHeight, TEXTAREA_MIN_HEIGHT);
  element.style.height = `${Math.min(contentHeight, maxHeight)}px`;
  element.style.overflowY = contentHeight > maxHeight ? "auto" : "hidden";
}

/** 文件大小只作展示并采用稳定量级，不对受管附件能力或内容类型作推断。 */
function formatFileSize(sizeBytes: number): string {
  if (sizeBytes < 1024) return `${sizeBytes} B`;
  if (sizeBytes < 1024 * 1024) return `${(sizeBytes / 1024).toFixed(1)} KB`;
  return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 只有服务端明确分类为图片或 UTF-8 文本的 ready 附件拥有预览按钮语义。 */
function canPreviewAttachment(attachment: Pick<ConversationAttachment, "mediaKind">): boolean {
  return attachment.mediaKind === "image" || attachment.mediaKind === "text";
}

/** 导入进度只在复制阶段显示确定百分比；App Server 阶段没有虚构进度。 */
function attachmentProgress(
  item: Extract<ConversationAttachmentDraftItem, { state: "importing" }>,
) {
  if (item.phase !== "copying" || item.totalBytes === undefined || item.totalBytes <= 0)
    return undefined;
  return Math.min(100, Math.round((item.bytesCopied / item.totalBytes) * 100));
}

interface AttachmentDraftCardProps {
  item: ConversationAttachmentDraftItem;
  sending: boolean;
  onRetry?: (itemId: string) => void | Promise<void>;
  onRemove?: (itemId: string) => void | Promise<void>;
  onOpenPreview?: (attachment: ConversationAttachment, source: HTMLButtonElement) => void;
}

/**
 * 单项卡片保持预览与移除为两个独立命中区域；状态文案、进度和恢复动作留在对象内部，
 * 不把整个附件带升级为会打断输入的全局错误面板。
 */
function AttachmentDraftCard({
  item,
  sending,
  onRetry,
  onRemove,
  onOpenPreview,
}: AttachmentDraftCardProps): ReactElement {
  const fileName = splitFileName(item.fileName);
  const ready = item.state === "ready" ? item : undefined;
  const previewable = ready !== undefined && canPreviewAttachment(ready);
  const hasThumbnail = ready?.mediaKind === "image" && ready.thumbnailUrl !== undefined;
  const progress = item.state === "importing" ? attachmentProgress(item) : undefined;
  const visual = hasThumbnail ? (
    <img src={ready.thumbnailUrl} alt="" draggable={false} />
  ) : item.mediaKind === "image" ? (
    <ImageIcon aria-hidden="true" />
  ) : item.mediaKind === "text" ? (
    <FileText aria-hidden="true" />
  ) : (
    <File aria-hidden="true" />
  );
  const content = (
    <>
      <span className="ja-composer-attachment__visual">{visual}</span>
      <span className="ja-composer-attachment__copy">
        <span className="ja-composer-attachment__name">
          <span>{fileName.stem}</span>
          {fileName.extension === undefined ? null : <span>{fileName.extension}</span>}
        </span>
        <small>
          {item.state === "importing"
            ? item.cancelRequested
              ? "正在取消"
              : progress === undefined
                ? "正在导入"
                : `${progress}%`
            : item.state === "failed"
              ? item.message
              : item.state === "removing"
                ? "正在移除"
                : formatFileSize(item.sizeBytes)}
        </small>
      </span>
    </>
  );
  return (
    <li
      className="ja-composer-attachment"
      data-state={item.state}
      data-media-kind={item.mediaKind}
      data-has-thumbnail={hasThumbnail || undefined}
      data-error-code={item.state === "failed" ? item.code : undefined}
    >
      <Tooltip content={item.fileName}>
        {previewable && ready !== undefined && onOpenPreview !== undefined ? (
          <button
            type="button"
            className="ja-composer-attachment__content is-previewable"
            aria-label={`预览附件 ${item.fileName}`}
            onClick={(event: MouseEvent<HTMLButtonElement>) =>
              onOpenPreview(ready, event.currentTarget)
            }
          >
            {content}
          </button>
        ) : (
          <span className="ja-composer-attachment__content">{content}</span>
        )}
      </Tooltip>
      {item.state === "failed" && item.retryable ? (
        <button
          type="button"
          className="ja-composer-attachment__retry"
          disabled={sending || onRetry === undefined}
          onClick={() => void onRetry?.(item.itemId)}
        >
          重试
        </button>
      ) : null}
      <IconButton
        className="ja-composer-attachment__remove"
        label={`${item.state === "importing" ? "取消" : "移除"}附件 ${item.fileName}`}
        tooltip={item.state === "importing" ? "取消导入" : "移除附件"}
        disabled={
          sending ||
          item.state === "removing" ||
          (item.state === "importing" && item.cancelRequested) ||
          onRemove === undefined
        }
        onClick={() => void onRemove?.(item.itemId)}
      >
        {item.state === "removing" ? (
          <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
        ) : (
          <X aria-hidden="true" />
        )}
      </IconButton>
      {item.state === "importing" && progress !== undefined ? (
        <span
          className="ja-composer-attachment__progress"
          role="progressbar"
          aria-label={`导入附件 ${item.fileName}`}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progress}
          style={{ "--ja-attachment-progress": `${progress}%` } as CSSProperties}
        />
      ) : item.state === "importing" ? (
        <LoaderCircle
          aria-label={`导入附件 ${item.fileName}`}
          className="ja-composer-attachment__spinner ja-composer__spin"
        />
      ) : null}
    </li>
  );
}

/** 只保留能补充真实模型身份的用户别名，重复的 Provider 名或模型标识不再制造视觉噪音。 */
function modelAlias(model: ConversationModelOption): string | undefined {
  const alias = model.modelLabel.trim();
  const normalizedAlias = alias.toLocaleLowerCase();
  if (
    alias === "" ||
    normalizedAlias === model.modelIdentifier.trim().toLocaleLowerCase() ||
    normalizedAlias === model.providerLabel.trim().toLocaleLowerCase()
  )
    return undefined;
  return alias;
}

/** 将扁平模型投影为 Provider 分组，真实上游标识始终是可选行的主信息。 */
function modelGroups(models: readonly ConversationModelOption[]) {
  const groups = new Map<
    string,
    {
      providerId: string;
      label: string;
      options: Array<{ value: string; modelIdentifier: string; alias?: string }>;
    }
  >();
  for (const model of models) {
    const group = groups.get(model.providerId) ?? {
      providerId: model.providerId,
      label: model.providerLabel,
      options: [],
    };
    group.options.push({
      value: model.value,
      modelIdentifier: model.modelIdentifier,
      alias: modelAlias(model),
    });
    groups.set(model.providerId, group);
  }
  return [...groups.values()];
}

/** 推理档位只使用模型声明的闭集，并在紧凑入口中复用一致中文名称。 */
function reasoningLabel(effort: ReasoningLevel): string {
  switch (effort) {
    case "off":
      return "关闭";
    case "minimal":
      return "最少";
    case "low":
      return "低";
    case "medium":
      return "中";
    case "high":
      return "高";
    case "xhigh":
      return "极高";
    case "max":
      return "最大";
  }
}

interface QueuedInputRowProps {
  item: ComposerQueuedInputView;
  position: number;
  skills: readonly ComposerSkillSuggestion[];
  onPrioritize?: ComposerProps["onPrioritizeQueuedInput"];
  onUpdate?: ComposerProps["onUpdateQueuedInput"];
  onDelete?: ComposerProps["onDeleteQueuedInput"];
  onOpenPreview?: ComposerProps["onOpenAttachmentPreview"];
}

/** 将长消息折叠为稳定的可访问对象名，避免 Tooltip 和读屏器重复朗读整段正文。 */
function queuedInputLabel(
  text: string,
  position: number,
  attachments: readonly AttachmentSummary[],
): string {
  const summary = text.replace(/\s+/g, " ").trim();
  const visibleSummary = summary === "" ? (attachments[0]?.displayName ?? "") : summary;
  const clipped = visibleSummary.length > 44 ? `${visibleSummary.slice(0, 44)}…` : visibleSummary;
  return `第 ${position + 1} 条消息${clipped === "" ? "" : `：${clipped}`}`;
}

interface QueuedAttachmentListProps {
  attachments: readonly AttachmentSummary[];
  label: string;
  disabled: boolean;
  onRemove?: (attachmentId: string) => void;
  onOpenPreview?: ComposerProps["onOpenAttachmentPreview"];
}

/**
 * 队列附件只展示服务端摘要，并把移除动作贴附到具体对象；renderer 不尝试读取已预留内容，
 * 避免绕过 Thread 授权边界。
 */
function QueuedAttachmentList({
  attachments,
  label,
  disabled,
  onRemove,
  onOpenPreview,
}: QueuedAttachmentListProps): ReactElement | null {
  if (attachments.length === 0) return null;
  return (
    <ul className="ja-composer-queue-attachments" aria-label={`${label}的附件`}>
      {attachments.map((attachment) => {
        const content = (
          <>
            {attachment.mediaKind === "image" ? (
              <ImageIcon aria-hidden="true" />
            ) : attachment.mediaKind === "text" ? (
              <FileText aria-hidden="true" />
            ) : (
              <File aria-hidden="true" />
            )}
            <span>{attachment.displayName}</span>
          </>
        );
        const previewable = canPreviewAttachment(attachment) && onOpenPreview !== undefined;
        return (
          <li key={attachment.attachmentId} title={attachment.displayName}>
            {previewable ? (
              <button
                type="button"
                className="ja-composer-queue-attachment__preview"
                aria-label={`预览附件 ${attachment.displayName}`}
                title={`预览 ${attachment.displayName}`}
                disabled={disabled}
                onClick={(event) =>
                  onOpenPreview(
                    {
                      attachmentId: attachment.attachmentId,
                      fileName: attachment.displayName,
                      sizeBytes: attachment.sizeBytes,
                      mediaKind: attachment.mediaKind,
                      mediaType: attachment.mediaType,
                    },
                    event.currentTarget,
                  )
                }
              >
                {content}
              </button>
            ) : (
              <span className="ja-composer-queue-attachment__preview" data-previewable="false">
                {content}
              </span>
            )}
            <small>{formatFileSize(attachment.sizeBytes)}</small>
            {onRemove === undefined ? null : (
              <button
                type="button"
                aria-label={`从${label}移除附件 ${attachment.displayName}`}
                title="移除附件"
                disabled={disabled}
                onClick={() => onRemove(attachment.attachmentId)}
              >
                <X aria-hidden="true" />
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}

/**
 * 单行队列对象保留直接调整与删除，把低频编辑收入更多菜单；局部 pending 只用于阻止重复意图，
 * 最终排序、revision 和错误仍由 application 投影覆盖。
 */
function QueuedInputRow({
  item,
  position,
  skills,
  onPrioritize,
  onUpdate,
  onDelete,
  onOpenPreview,
}: QueuedInputRowProps): ReactElement {
  const itemText = textFromUserContent(item.content);
  const itemReferences = resolveSkillReferenceMetadata(
    contextReferencesFromUserContent(item.content),
    skills,
  );
  const [editing, setEditing] = useState(false);
  const [editText, setEditText] = useState(itemText);
  const [editReferences, setEditReferences] =
    useState<ConversationContextReference[]>(itemReferences);
  const [editAttachments, setEditAttachments] = useState<AttachmentSummary[]>([
    ...item.attachments,
  ]);
  const [localBusy, setLocalBusy] = useState<ComposerQueuedInputBusyAction>();
  const [localError, setLocalError] = useState<string>();
  const editorRef = useRef<HTMLTextAreaElement>(null);
  const errorId = useId();
  const label = queuedInputLabel(itemText, position, item.attachments);
  const busyAction = item.busyAction ?? localBusy;
  const busy = item.pending === true || busyAction !== undefined;
  const rowError = item.error ?? item.issue?.message ?? localError;

  /** 菜单关闭后再聚焦内联编辑器，避免 Portal 的焦点归还覆盖编辑起点。 */
  useEffect(() => {
    if (!editing) return;
    const frame = window.requestAnimationFrame(() => {
      editorRef.current?.focus();
      editorRef.current?.select();
    });
    return () => window.cancelAnimationFrame(frame);
  }, [editing]);

  /** 对单条对象串行化动作，补住 controller 投影返回前的双击窗口但不改变权威队列。 */
  const runAction = async (
    action: ComposerQueuedInputBusyAction,
    operation: (() => void | Promise<void>) | undefined,
  ): Promise<boolean> => {
    if (busy || operation === undefined) return false;
    setLocalError(undefined);
    setLocalBusy(action);
    try {
      await operation();
      return true;
    } catch {
      setLocalError("操作未完成，请重试");
      return false;
    } finally {
      setLocalBusy(undefined);
    }
  };

  /** 编辑按当前 Chip 与附件摘要重建内容，使 needs_attention 可移除失效对象后原位重试。 */
  const saveEdit = async (): Promise<void> => {
    const normalized = editText.trim();
    if (
      normalized === "" &&
      !editReferences.some((reference) => reference.type === "workspace_reference") &&
      editAttachments.length === 0
    ) {
      setLocalError("消息不能为空");
      return;
    }
    const content = [
      ...editReferences.map(referenceToUserContent),
      ...editAttachments.map((attachment) => ({
        type: "attachment" as const,
        attachmentId: attachment.attachmentId,
      })),
      ...(normalized === "" ? [] : [{ type: "text" as const, text: normalized }]),
    ];
    const completed = await runAction("update", () =>
      onUpdate?.(item.inputId, item.inputRevision, content),
    );
    if (completed) setEditing(false);
  };

  /** 编辑器沿用 Composer 的 Enter 约定，并让 Escape 始终无损返回原消息。 */
  const handleEditKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (event.key === "Escape") {
      event.preventDefault();
      setEditText(itemText);
      setEditReferences(itemReferences);
      setEditAttachments([...item.attachments]);
      setLocalError(undefined);
      setEditing(false);
      return;
    }
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing && !busy) {
      event.preventDefault();
      void saveEdit();
    }
  };

  /**
   * 非编辑态移除附件立即提交 CAS；如果它是消息的唯一内容，则删除整条队列项，避免制造无法
   * 消费的空输入。编辑态仅调整本地草稿，统一由保存动作提交一次更新。
   */
  const removeQueuedAttachment = (attachmentId: string): void => {
    const remainingAttachments = item.attachments.filter(
      (attachment) => attachment.attachmentId !== attachmentId,
    );
    const remainingContent = item.content.filter(
      (block) => block.type !== "attachment" || block.attachmentId !== attachmentId,
    );
    const hasRemainingContent =
      textFromUserContent(remainingContent).trim() !== "" ||
      contextReferencesFromUserContent(remainingContent).some(
        (reference) => reference.type === "workspace_reference",
      ) ||
      remainingAttachments.length > 0;
    if (hasRemainingContent) {
      void runAction("update", () =>
        onUpdate?.(item.inputId, item.inputRevision, remainingContent),
      );
      return;
    }
    void runAction("delete", () => onDelete?.(item.inputId, item.inputRevision));
  };

  return (
    <li
      className="ja-composer-queue__item"
      data-kind={item.kind}
      data-state={
        item.pending
          ? "pending"
          : item.status === "needs_attention" || rowError
            ? "error"
            : busy
              ? "busy"
              : "ready"
      }
      aria-busy={busy || undefined}
      aria-describedby={rowError ? errorId : undefined}
    >
      <span className="ja-composer-queue__leading" aria-hidden="true">
        {item.pending ? <LoaderCircle className="ja-composer__spin" /> : <ListRestart />}
      </span>
      {editing ? (
        <div className="ja-composer-queue__editor">
          <ComposerContextChips
            references={editReferences}
            compact
            label={`${label}的上下文`}
            onRemove={(reference) =>
              setEditReferences((current) =>
                current.filter(
                  (candidate) =>
                    contextReferenceIdentity(candidate) !== contextReferenceIdentity(reference),
                ),
              )
            }
          />
          <QueuedAttachmentList
            attachments={editAttachments}
            label={label}
            disabled={busy}
            onOpenPreview={onOpenPreview}
            onRemove={(attachmentId) =>
              setEditAttachments((current) =>
                current.filter((attachment) => attachment.attachmentId !== attachmentId),
              )
            }
          />
          <textarea
            ref={editorRef}
            aria-label={`编辑${label}`}
            aria-invalid={rowError !== undefined || undefined}
            aria-describedby={rowError ? errorId : undefined}
            value={editText}
            maxLength={QUEUED_INPUT_MAX_LENGTH}
            rows={1}
            disabled={busy}
            onChange={(event) => {
              setEditText(event.currentTarget.value);
              setLocalError(undefined);
            }}
            onKeyDown={handleEditKeyDown}
          />
          <div className="ja-composer-queue__edit-actions">
            <IconButton
              type="button"
              label={`取消编辑${label}`}
              tooltip="取消编辑"
              disabled={busy}
              onClick={() => {
                setEditText(itemText);
                setEditReferences(itemReferences);
                setEditAttachments([...item.attachments]);
                setLocalError(undefined);
                setEditing(false);
              }}
            >
              <X aria-hidden="true" />
            </IconButton>
            <IconButton
              type="button"
              label={`保存编辑${label}`}
              tooltip="保存编辑"
              disabled={
                busy ||
                (editText.trim() === "" &&
                  !editReferences.some((reference) => reference.type === "workspace_reference") &&
                  editAttachments.length === 0) ||
                onUpdate === undefined
              }
              onClick={() => void saveEdit()}
            >
              {busyAction === "update" ? (
                <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
              ) : (
                <Check aria-hidden="true" />
              )}
            </IconButton>
          </div>
          {rowError ? (
            <small id={errorId} className="ja-composer-queue__edit-error" role="alert">
              {rowError}
            </small>
          ) : null}
        </div>
      ) : (
        <div className="ja-composer-queue__copy" title={itemText}>
          <ComposerContextChips references={itemReferences} compact label={`${label}的上下文`} />
          {itemText === "" ? null : <span>{itemText}</span>}
          <QueuedAttachmentList
            attachments={item.attachments}
            label={label}
            disabled={busy}
            onOpenPreview={onOpenPreview}
            onRemove={
              onUpdate === undefined && onDelete === undefined ? undefined : removeQueuedAttachment
            }
          />
          {rowError ? (
            <small id={errorId} role="alert" title={rowError}>
              {rowError}
            </small>
          ) : item.pending ? (
            <small>正在排队</small>
          ) : null}
        </div>
      )}
      {editing ? null : (
        <div className="ja-composer-queue__actions">
          <Tooltip content={item.kind === "steering" ? "将在下一安全点优先处理" : "调整方向"}>
            <button
              type="button"
              className="ja-composer-queue__prioritize"
              aria-label={`${item.kind === "steering" ? "已调整方向" : "调整方向"}：${label}`}
              disabled={busy || item.kind === "steering" || onPrioritize === undefined}
              onClick={() =>
                void runAction("prioritize", () => onPrioritize?.(item.inputId, item.inputRevision))
              }
            >
              {busyAction === "prioritize" ? (
                <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
              ) : (
                <CornerUpRight aria-hidden="true" />
              )}
              <span>{item.kind === "steering" ? "已调整方向" : "调整方向"}</span>
            </button>
          </Tooltip>
          <IconButton
            type="button"
            className="ja-composer-queue__icon-button"
            label={`删除${label}`}
            tooltip="删除"
            disabled={busy || onDelete === undefined}
            onClick={() =>
              void runAction("delete", () => onDelete?.(item.inputId, item.inputRevision))
            }
          >
            {busyAction === "delete" ? (
              <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
            ) : (
              <Trash2 aria-hidden="true" />
            )}
          </IconButton>
          <Menu modal={false}>
            <MenuTrigger asChild>
              <button
                type="button"
                className="ja-composer-queue__more"
                aria-label={`更多操作：${label}`}
                title="更多操作"
                disabled={busy || onUpdate === undefined}
              >
                <Ellipsis aria-hidden="true" />
              </button>
            </MenuTrigger>
            <MenuContent align="end" className="ja-composer-queue__menu" aria-label={label}>
              <MenuItem
                disabled={busy || onUpdate === undefined}
                onSelect={() => {
                  setEditText(itemText);
                  setEditReferences(itemReferences);
                  setEditAttachments([...item.attachments]);
                  setLocalError(undefined);
                  setEditing(true);
                }}
              >
                <Pencil aria-hidden="true" />
                编辑消息
              </MenuItem>
            </MenuContent>
          </Menu>
        </div>
      )}
    </li>
  );
}

/**
 * 渲染同一内容轨道上的生产输入器；Thread 偏好通过 CAS 回调更新，发送只提交文本与 App Server
 * 签发的 attachmentId。项目上下文由组合层放在表单上方，避免输入器承担非表单信息。
 */
export function Composer({
  preferences,
  modeStatus,
  goalStatus,
  placeholder = "随心输入",
  text,
  onTextChange,
  contextReferences = [],
  onContextReferencesChange,
  threadId,
  workspaceId,
  runtimeGeneration,
  skills = [],
  slashCommands = [],
  onSearchWorkspacePaths,
  models = [],
  attachments = [],
  attachmentDraftItems,
  activeTurn = false,
  suspendedTurn = false,
  disabled = false,
  preferenceBusy = false,
  importingAttachments = false,
  sending = false,
  draftRecoveryRevision = 0,
  cancelling = false,
  resuming = false,
  error,
  queuedInputs = [],
  queueAccepting = true,
  contextUsage,
  onModelChange,
  onReasoningChange,
  onAccessModeChange,
  onRestoreDefaults,
  onAddAttachments,
  onRetryAttachment,
  onRemoveAttachment,
  onPasteAttachments,
  onDropAttachments,
  onOpenAttachmentPreview,
  onOpenWorkspaceReference,
  onOpenQueuedAttachmentPreview,
  nativeDropEvent,
  dropZoneRef,
  onSend,
  onEnqueue,
  onPrioritizeQueuedInput,
  onUpdateQueuedInput,
  onDeleteQueuedInput,
  onResume,
  onCancel,
  className,
}: ComposerProps): ReactElement {
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const lastSelectionRef = useRef({ start: text.length, end: text.length });
  const observedRecoveryRevisionRef = useRef(draftRecoveryRevision);
  const composingRef = useRef(false);
  const suggestionRequestRef = useRef(0);
  const commandBusyRef = useRef(false);
  const composerScopeIdentity = `${threadId ?? ""}\u0000${workspaceId ?? ""}\u0000${runtimeGeneration ?? ""}`;
  const composerContextRef = useRef(composerScopeIdentity);
  const consumedDropTokenRef = useRef<string | undefined>(undefined);
  const feedbackId = useId();
  const suggestionListId = useId();
  const [caretState, setCaretState] = useState<ComposerCaretState>(() => ({
    text,
    position: text.length,
  }));
  const [inputFocused, setInputFocused] = useState(false);
  const [composing, setComposing] = useState(false);
  const [suggestionRetry, setSuggestionRetry] = useState(0);
  const [workspaceSuggestions, setWorkspaceSuggestions] = useState<{
    state: "idle" | "loading" | "ready" | "error";
    items: ComposerWorkspaceSearchResult["items"];
    truncated: boolean;
    error?: string;
  }>({ state: "idle", items: [], truncated: false });
  const [ownedSuggestionSession, setOwnedSuggestionSession] =
    useState<ComposerSuggestionSessionState>(() => ({ scopeIdentity: composerScopeIdentity }));
  const [ownedInlineCommand, setOwnedInlineCommand] = useState<ComposerInlineCommandState>();
  const inlineCommand =
    ownedInlineCommand?.scopeIdentity === composerScopeIdentity
      ? ownedInlineCommand.command
      : undefined;

  /** 在浏览器绘制前更新异步命令的 scope 栅栏，避免 render 期间写 ref，也不给旧会话结果留下可见窗口。 */
  useLayoutEffect(() => {
    composerContextRef.current = composerScopeIdentity;
  }, [composerScopeIdentity]);

  /** Scope 切换立即结束临时命令编辑，目标正文仍由外层 Thread 草稿 owner 决定是否恢复。 */
  useEffect(() => {
    if (
      ownedInlineCommand === undefined ||
      ownedInlineCommand.scopeIdentity === composerScopeIdentity
    )
      return;
    const frame = window.requestAnimationFrame(() => setOwnedInlineCommand(undefined));
    return () => window.cancelAnimationFrame(frame);
  }, [composerScopeIdentity, ownedInlineCommand]);

  /** 未聚焦的外部草稿替换从末尾解析 trigger；聚焦输入继续服从 WebView 原生 selection。 */
  const caret =
    caretState.text === text || inputFocused
      ? Math.min(caretState.position, text.length)
      : text.length;
  const trigger = useMemo(() => findComposerTrigger(text, caret), [caret, text]);
  const triggerIdentity =
    trigger === undefined ? undefined : `${trigger.kind}:${trigger.start}:${text}`;
  const suggestionSession = useMemo<ComposerSuggestionSessionState>(
    () =>
      ownedSuggestionSession.scopeIdentity === composerScopeIdentity
        ? ownedSuggestionSession
        : {
            scopeIdentity: composerScopeIdentity,
            dismissedTrigger: triggerIdentity,
          },
    [composerScopeIdentity, ownedSuggestionSession, triggerIdentity],
  );
  const { commandError } = suggestionSession;
  const dismissedTrigger =
    suggestionSession.dismissedTrigger === triggerIdentity
      ? suggestionSession.dismissedTrigger
      : undefined;
  const visibleTrigger =
    inlineCommand !== undefined ||
    composing ||
    triggerIdentity === undefined ||
    triggerIdentity === dismissedTrigger
      ? undefined
      : trigger;

  /** 建议局部状态始终落在当前 scope；事件早于异步收口时也不能写回上一会话。 */
  const updateSuggestionSession = (
    updates: Partial<Omit<ComposerSuggestionSessionState, "scopeIdentity">>,
  ): void => {
    setOwnedSuggestionSession((current) => ({
      ...(current.scopeIdentity === composerScopeIdentity ? current : suggestionSession),
      ...updates,
      scopeIdentity: composerScopeIdentity,
    }));
  };
  const filteredSkills = useMemo(
    () =>
      filterComposerSkills(skills, visibleTrigger?.kind === "skill" ? visibleTrigger.query : ""),
    [skills, visibleTrigger?.kind, visibleTrigger?.query],
  );
  const filteredCommands = useMemo(
    () =>
      filterComposerCommands(
        slashCommands,
        visibleTrigger?.kind === "command" ? visibleTrigger.query : "",
      ),
    [slashCommands, visibleTrigger],
  );
  const suggestionItems = useMemo<ComposerSuggestionItem[]>(() => {
    if (visibleTrigger?.kind === "workspace")
      return workspaceSuggestions.items.map((value, index) => ({
        kind: "workspace" as const,
        id: `${suggestionListId}-workspace-${index}`,
        value,
      }));
    if (visibleTrigger?.kind === "skill")
      return filteredSkills.map((value, index) => ({
        kind: "skill" as const,
        id: `${suggestionListId}-skill-${index}`,
        value,
      }));
    if (visibleTrigger?.kind === "command")
      return filteredCommands.map((value, index) => ({
        kind: "command" as const,
        id: `${suggestionListId}-command-${index}`,
        value,
      }));
    return [];
  }, [
    filteredCommands,
    filteredSkills,
    suggestionListId,
    visibleTrigger?.kind,
    workspaceSuggestions.items,
  ]);
  const defaultActiveSuggestionId =
    suggestionItems.find((item) => item.kind !== "command" || item.value.available)?.id ??
    suggestionItems[0]?.id;
  const suggestionResetKey = suggestionItems
    .map((item) => `${item.id}:${item.kind === "command" ? item.value.available : true}`)
    .join("\u0000");
  const [activeSuggestion, setActiveSuggestion] = useState<{
    resetKey: string;
    activeId: string | undefined;
  }>(() => ({ resetKey: suggestionResetKey, activeId: defaultActiveSuggestionId }));
  const activeSuggestionId =
    activeSuggestion.resetKey === suggestionResetKey
      ? activeSuggestion.activeId
      : defaultActiveSuggestionId;

  /**
   * 交互更新绑定当前建议集合；若异步目录刚换代，先从新集合默认项计算，禁止旧 activeId 跨集合漂移。
   */
  const setActiveSuggestionId = useCallback(
    (next: SetStateAction<string | undefined>): void => {
      setActiveSuggestion((current) => {
        const currentId =
          current.resetKey === suggestionResetKey ? current.activeId : defaultActiveSuggestionId;
        return {
          resetKey: suggestionResetKey,
          activeId: typeof next === "function" ? next(currentId) : next,
        };
      });
    },
    [defaultActiveSuggestionId, suggestionResetKey],
  );

  /** Workspace 搜索延迟 120ms 并绑定四重身份，任何晚到结果都不能跨 Thread 或代际显示。 */
  useEffect(() => {
    if (visibleTrigger?.kind !== "workspace") {
      suggestionRequestRef.current += 1;
      const frame = window.requestAnimationFrame(() =>
        setWorkspaceSuggestions((current) =>
          current.state === "idle" ? current : { state: "idle", items: [], truncated: false },
        ),
      );
      return () => window.cancelAnimationFrame(frame);
    }
    if (
      threadId === undefined ||
      workspaceId === undefined ||
      runtimeGeneration === undefined ||
      onSearchWorkspacePaths === undefined
    ) {
      const frame = window.requestAnimationFrame(() =>
        setWorkspaceSuggestions({
          state: "error",
          items: [],
          truncated: false,
          error: "当前工作空间尚未就绪。",
        }),
      );
      return () => window.cancelAnimationFrame(frame);
    }
    const request = ++suggestionRequestRef.current;
    const query = visibleTrigger.query;
    const loadingFrame = window.requestAnimationFrame(() =>
      setWorkspaceSuggestions({ state: "loading", items: [], truncated: false }),
    );
    const timer = window.setTimeout(() => {
      void onSearchWorkspacePaths(query).then(
        (result) => {
          if (
            request !== suggestionRequestRef.current ||
            result.threadId !== threadId ||
            result.workspaceId !== workspaceId ||
            result.generation !== runtimeGeneration ||
            result.query !== query
          )
            return;
          setWorkspaceSuggestions({
            state: "ready",
            items: result.items,
            truncated: result.truncated,
          });
        },
        () => {
          if (request !== suggestionRequestRef.current) return;
          setWorkspaceSuggestions({
            state: "error",
            items: [],
            truncated: false,
            error: "文件与目录暂时不可用，请重试。",
          });
        },
      );
    }, 120);
    return () => {
      window.cancelAnimationFrame(loadingFrame);
      window.clearTimeout(timer);
    };
  }, [
    onSearchWorkspacePaths,
    runtimeGeneration,
    suggestionRetry,
    threadId,
    visibleTrigger?.kind,
    visibleTrigger?.query,
    workspaceId,
  ]);

  /** Scope 切换先派生隐藏态，再用 keyed CAS 收口；微任务不依赖 WebView 绘制帧。 */
  useEffect(() => {
    if (ownedSuggestionSession.scopeIdentity === composerScopeIdentity) return;
    const previousScopeIdentity = ownedSuggestionSession.scopeIdentity;
    const nextSession = suggestionSession;
    suggestionRequestRef.current += 1;
    void Promise.resolve().then(() => {
      setOwnedSuggestionSession((current) =>
        current.scopeIdentity === previousScopeIdentity ? nextSession : current,
      );
    });
  }, [composerScopeIdentity, ownedSuggestionSession.scopeIdentity, suggestionSession]);

  /** 键盘改变 active option 时只滚动面板内部，textarea 继续持有实际焦点。 */
  useEffect(() => {
    if (activeSuggestionId === undefined) return;
    document.getElementById(activeSuggestionId)?.scrollIntoView?.({ block: "nearest" });
  }, [activeSuggestionId]);

  /** 根据 Composer 的实时顶部空间限制向上面板高度，缩放和矮窗下也不越出视口。 */
  useLayoutEffect(() => {
    if (visibleTrigger === undefined) return undefined;
    const composer = inputRef.current?.closest<HTMLElement>(".ja-composer");
    if (composer === undefined || composer === null) return undefined;
    const updateAvailableSpace = (): void => {
      const available = Math.max(48, Math.floor(composer.getBoundingClientRect().top - 12));
      composer.style.setProperty("--ja-composer-suggestion-space", `${available}px`);
    };
    const observer =
      typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(updateAvailableSpace);
    observer?.observe(composer);
    window.addEventListener("resize", updateAvailableSpace);
    updateAvailableSpace();
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", updateAvailableSpace);
      composer.style.removeProperty("--ja-composer-suggestion-space");
    };
  }, [visibleTrigger]);
  const selectedModel = models.find(
    (model) =>
      model.providerId === preferences?.providerId && model.modelId === preferences.modelId,
  );
  const groupedModels = modelGroups(models);
  const selectedReasoning =
    preferences?.reasoningLevel ?? selectedModel?.defaultReasoningLevel ?? null;
  const modelLabel =
    selectedModel?.modelIdentifier ?? (models.length === 0 ? "未配置模型" : "选择模型");
  const selectionLabel =
    selectedReasoning === null
      ? modelLabel
      : `${modelLabel} · ${reasoningLabel(selectedReasoning)}`;
  const selectionAccessibilityLabel =
    selectedModel === undefined
      ? modelLabel
      : `当前模型：${modelLabel}，提供商：${selectedModel.providerLabel}${
          selectedReasoning === null ? "" : `，推理强度：${reasoningLabel(selectedReasoning)}`
        }`;
  const draftItems =
    attachmentDraftItems ??
    attachments.map(
      (attachment): ConversationAttachmentDraftItem => ({
        state: "ready",
        itemId: attachment.attachmentId,
        ...attachment,
      }),
    );
  const readyAttachments = draftItems.filter(
    (item): item is Extract<ConversationAttachmentDraftItem, { state: "ready" }> =>
      item.state === "ready",
  );
  const hasUnresolvedAttachments = draftItems.some((item) => item.state !== "ready");
  const blockedTurn = activeTurn || suspendedTurn;
  const hasDraftContent =
    text.trim().length > 0 ||
    contextReferences.some((reference) => reference.type === "workspace_reference") ||
    readyAttachments.length > 0;
  const canSend =
    hasDraftContent &&
    !disabled &&
    !preferenceBusy &&
    !sending &&
    !cancelling &&
    !resuming &&
    !hasUnresolvedAttachments &&
    !suspendedTurn &&
    (!activeTurn || (queueAccepting && onEnqueue !== undefined));
  const canCancel = blockedTurn && !disabled && !cancelling && !resuming && onCancel !== undefined;
  const canResume =
    suspendedTurn && !disabled && !cancelling && !resuming && onResume !== undefined;
  const dropActive =
    (nativeDropEvent?.phase === "enter" || nativeDropEvent?.phase === "over") &&
    !disabled &&
    !sending;

  /** 只把发送或排队意图上报给 application controller，组件不建立第二把并发锁。 */
  const submit = (): void => {
    if (!canSend) return;
    const input = inputRef.current;
    if (input !== null) {
      lastSelectionRef.current = {
        start: input.selectionStart ?? text.length,
        end: input.selectionEnd ?? text.length,
      };
    }
    if (activeTurn && onEnqueue !== undefined) {
      void onEnqueue({
        text: text.trim(),
        attachmentIds: readyAttachments.map((attachment) => attachment.attachmentId),
        contextReferences,
      });
      return;
    }
    void onSend({
      text: text.trim(),
      attachmentIds: readyAttachments.map((attachment) => attachment.attachmentId),
      contextReferences,
    });
  };

  /** 取消只发布用户意图；Turn identity、CAS 和 single-flight 由 application controller 冻结。 */
  const cancel = (): void => {
    if (!canCancel || onCancel === undefined) return;
    void onCancel();
  };

  /** Resume 只发布明确授权；Turn identity、revision CAS 与 single-flight 仍由 application owner 冻结。 */
  const resume = (): void => {
    if (!canResume || onResume === undefined) return;
    void onResume();
  };

  /**
   * 在原生 input 事件边界同步结束已离开的 Esc 会话，避免 React 合并“清空草稿 + 导航”时
   * 丢失中间 render，导致用户重输完全相同的 token 后仍被旧 identity 抑制。
   */
  const handleTextChange = (event: ChangeEvent<HTMLTextAreaElement>): void => {
    const nextText = event.currentTarget.value;
    const nextCaret = event.currentTarget.selectionStart ?? nextText.length;
    const nextTrigger = findComposerTrigger(nextText, nextCaret);
    const nextTriggerIdentity =
      nextTrigger === undefined
        ? undefined
        : `${nextTrigger.kind}:${nextTrigger.start}:${nextText}`;
    resizeTextarea(event.currentTarget);
    setCaretState({
      text: nextText,
      position: nextCaret,
    });
    updateSuggestionSession({
      commandError: undefined,
      ...(dismissedTrigger !== undefined && nextTriggerIdentity !== dismissedTrigger
        ? { dismissedTrigger: undefined }
        : {}),
    });
    onTextChange(nextText);
  };

  /** 更新受控 textarea 后在下一帧恢复光标，面板点击始终把焦点留在原生输入控件。 */
  const focusTextareaAt = (position: number): void => {
    focusTextareaSelection(position, position);
  };

  /** 命令失败需要恢复完整 selection；普通选择仍通过相同入口折叠到 token 起点。 */
  const focusTextareaSelection = (start: number, end: number): void => {
    window.requestAnimationFrame(() => {
      const input = inputRef.current;
      if (input === null) return;
      input.focus({ preventScroll: true });
      input.setSelectionRange(start, end);
      lastSelectionRef.current = { start, end };
      setCaretState({ text: input.value, position: end });
    });
  };

  /** Context 引用按稳定 identity 去重，重复选择只移除 token 而不制造第二枚 Chip。 */
  const appendContextReference = (reference: ConversationContextReference): void => {
    if (onContextReferencesChange === undefined) return;
    const identity = contextReferenceIdentity(reference);
    if (contextReferences.some((candidate) => contextReferenceIdentity(candidate) === identity))
      return;
    onContextReferencesChange([...contextReferences, reference]);
  };

  /**
   * Slash action 在执行前先提交可见草稿变化；失败时仅在原 scope 恢复命令正文和 selection，
   * 内联目标命令额外恢复编辑态，避免异步 ACK 覆盖用户已切换会话后的输入。
   */
  const runSlashCommand = (
    command: ComposerSlashCommand,
    argument: string,
    visibleText: string,
    recovery: { text: string; start: number; end: number; inline?: boolean },
  ): void => {
    if (!command.available || commandBusyRef.current) return;
    const commandContext = composerContextRef.current;
    commandBusyRef.current = true;
    updateSuggestionSession({ commandError: undefined, dismissedTrigger: undefined });
    setOwnedInlineCommand(undefined);
    onTextChange(visibleText);
    void Promise.resolve(command.execute({ argument }))
      .then(() => {
        if (composerContextRef.current === commandContext) focusTextareaAt(visibleText.length);
      })
      .catch(() => {
        if (composerContextRef.current !== commandContext) return;
        onTextChange(recovery.text);
        if (recovery.inline)
          setOwnedInlineCommand({ scopeIdentity: composerScopeIdentity, command });
        updateSuggestionSession({ commandError: "指令未完成，请重试。" });
        focusTextareaSelection(recovery.start, recovery.end);
      })
      .finally(() => {
        commandBusyRef.current = false;
      });
  };

  /** 必填参数缺失时复用主 textarea 收集，不弹出第二层表单或丢失当前草稿。 */
  const beginInlineCommand = (command: ComposerSlashCommand, initialText = ""): void => {
    setOwnedInlineCommand({ scopeIdentity: composerScopeIdentity, command });
    updateSuggestionSession({ commandError: undefined, dismissedTrigger: undefined });
    onTextChange(initialText);
    focusTextareaAt(initialText.length);
  };

  /** 内联命令只在参数非空时执行；失败恢复同一编辑态供原位修正。 */
  const submitInlineCommand = (): void => {
    if (inlineCommand === undefined) return;
    const argument = text.trim();
    if (argument === "") {
      updateSuggestionSession({
        commandError: `${inlineCommand.argument?.label ?? "参数"}不能为空。`,
      });
      return;
    }
    const selection = inputRef.current;
    runSlashCommand(inlineCommand, argument, "", {
      text,
      start: selection?.selectionStart ?? text.length,
      end: selection?.selectionEnd ?? text.length,
      inline: true,
    });
  };

  /** 退出内联 command 时保留正文并把焦点归还同一 textarea，鼠标和 Escape 语义一致。 */
  const cancelInlineCommand = (): void => {
    setOwnedInlineCommand(undefined);
    updateSuggestionSession({ commandError: undefined });
    focusTextareaAt(text.length);
  };

  /** 统一选择入口先无损替换触发 token；带参数命令消费参数，普通动作保留 token 后正文。 */
  const selectSuggestion = (item: ComposerSuggestionItem): void => {
    if (visibleTrigger === undefined) return;
    const originalText = text;
    const originalSelection = {
      start: inputRef.current?.selectionStart ?? caret,
      end: inputRef.current?.selectionEnd ?? caret,
    };
    const nextText = removeComposerTrigger(text, visibleTrigger);
    if (item.kind === "workspace" && workspaceId !== undefined) {
      appendContextReference(workspaceSuggestionReference(workspaceId, item.value));
      onTextChange(nextText);
      updateSuggestionSession({ dismissedTrigger: undefined });
      focusTextareaAt(visibleTrigger.start);
      return;
    }
    if (item.kind === "skill") {
      appendContextReference(skillSuggestionReference(item.value));
      onTextChange(nextText);
      updateSuggestionSession({ dismissedTrigger: undefined });
      focusTextareaAt(visibleTrigger.start);
      return;
    }
    if (item.kind !== "command" || !item.value.available || commandBusyRef.current) return;
    const argument = item.value.argument === undefined ? "" : nextText.trim();
    if (item.value.argument?.mode === "required" && argument === "") {
      beginInlineCommand(item.value);
      return;
    }
    runSlashCommand(item.value, argument, item.value.argument === undefined ? nextText : "", {
      text: originalText,
      start: originalSelection.start,
      end: originalSelection.end,
    });
  };

  /**
   * 受控草稿变化后同步高度与逻辑 caret：聚焦时以原生 selection 为准，未聚焦的 Thread/编辑草稿替换
   * 则落到文本末尾，避免旧 scope 的 caret 截断新 token，同时不干预 IME 和键盘选区。
   */
  useLayoutEffect(() => {
    const input = inputRef.current;
    if (input === null) return;
    resizeTextarea(input);
    if (document.activeElement === input) return;
    lastSelectionRef.current = { start: text.length, end: text.length };
  }, [text]);

  /** 准入失败恢复由显式 revision 驱动，鼠标提交后也恢复原选择区与 textarea 焦点。 */
  useLayoutEffect(() => {
    if (observedRecoveryRevisionRef.current === draftRecoveryRevision) return;
    observedRecoveryRevisionRef.current = draftRecoveryRevision;
    const input = inputRef.current;
    if (input === null) return;
    const start = Math.min(lastSelectionRef.current.start, text.length);
    const end = Math.min(lastSelectionRef.current.end, text.length);
    input.focus({ preventScroll: true });
    input.setSelectionRange(start, end);
    setCaretState({ text: input.value, position: end });
  }, [draftRecoveryRevision, text]);

  /** 宽度、缩放和字体加载会改变换行数量；ResizeObserver 让高度跟随真实排版。 */
  useEffect(() => {
    const input = inputRef.current;
    const container = input?.closest<HTMLElement>(".ja-composer");
    if (input === undefined || input === null || container === undefined || container === null)
      return undefined;
    let frame = 0;
    let disposed = false;
    const scheduleResize = (): void => {
      if (frame !== 0) window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        if (!disposed) resizeTextarea(input);
      });
    };
    const observer =
      typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(scheduleResize);
    observer?.observe(container);
    window.addEventListener("resize", scheduleResize);
    void document.fonts?.ready.then(scheduleResize);
    scheduleResize();
    return () => {
      disposed = true;
      if (frame !== 0) window.cancelAnimationFrame(frame);
      observer?.disconnect();
      window.removeEventListener("resize", scheduleResize);
    };
  }, []);

  /**
   * 原生 drop 已由应用级路由器完成坐标命中并唯一分发；Composer 只消费自己的事件和 token，
   * leave、取消和 drop 由派生视觉态立即清理，不注册第二个 Tauri listener。
   */
  useEffect(() => {
    if (nativeDropEvent === undefined || onDropAttachments === undefined) return;
    if (
      nativeDropEvent.phase === "drop" &&
      !disabled &&
      !sending &&
      nativeDropEvent.dropToken !== undefined &&
      consumedDropTokenRef.current !== nativeDropEvent.dropToken
    ) {
      consumedDropTokenRef.current = nativeDropEvent.dropToken;
      void onDropAttachments(nativeDropEvent.dropToken);
    }
  }, [disabled, nativeDropEvent, onDropAttachments, sending]);

  /** 表单提交优先收口内联 command；普通消息仍交给并发保护的 submit。 */
  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (inlineCommand !== undefined) {
      submitInlineCommand();
      return;
    }
    submit();
  };

  /**
   * 建议面板优先消费导航键；内联 command 的 Escape 只退出模式并保留正文，Enter 执行；
   * IME composition 期间绝不选择或提交未确认的中文候选。
   */
  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (composingRef.current || event.nativeEvent.isComposing) return;
    if (inlineCommand !== undefined) {
      if (event.key === "Escape") {
        event.preventDefault();
        cancelInlineCommand();
        return;
      }
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        submitInlineCommand();
        return;
      }
    }
    if (visibleTrigger !== undefined) {
      if (event.key === "Escape") {
        event.preventDefault();
        updateSuggestionSession({ dismissedTrigger: triggerIdentity });
        return;
      }
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        if (suggestionItems.length === 0) return;
        const enabledSuggestions = suggestionItems.filter(
          (item) => item.kind !== "command" || item.value.available,
        );
        if (enabledSuggestions.length === 0) return;
        const delta = event.key === "ArrowDown" ? 1 : -1;
        setActiveSuggestionId((currentId) => {
          const current = enabledSuggestions.findIndex((item) => item.id === currentId);
          const next =
            current < 0
              ? event.key === "ArrowDown"
                ? 0
                : enabledSuggestions.length - 1
              : (current + delta + enabledSuggestions.length) % enabledSuggestions.length;
          return enabledSuggestions[next]?.id;
        });
        return;
      }
      if ((event.key === "Enter" && !event.shiftKey) || event.key === "Tab") {
        event.preventDefault();
        const exactCommand =
          visibleTrigger.kind === "command" &&
          text.trim() === visibleTrigger.raw &&
          contextReferences.length === 0 &&
          readyAttachments.length === 0
            ? filteredCommands.find((command) => slashCommandMatches(command, visibleTrigger.query))
            : undefined;
        const selected =
          exactCommand === undefined
            ? suggestionItems.find((item) => item.id === activeSuggestionId)
            : suggestionItems.find(
                (item) => item.kind === "command" && item.value.id === exactCommand.id,
              );
        if (selected !== undefined) selectSuggestion(selected);
        return;
      }
    }
    if (event.key === "Enter" && !event.shiftKey && !composingRef.current) {
      const invocation = parseComposerSlashInvocation(text);
      const command =
        invocation === undefined
          ? undefined
          : slashCommands.find((candidate) => slashCommandMatches(candidate, invocation.name));
      if (
        invocation !== undefined &&
        command !== undefined &&
        (invocation.argument === "" || command.argument !== undefined)
      ) {
        event.preventDefault();
        if (!command.available || commandBusyRef.current) return;
        if (command.argument?.mode === "required" && invocation.argument === "") {
          beginInlineCommand(command);
          return;
        }
        const start = event.currentTarget.selectionStart ?? text.length;
        const end = event.currentTarget.selectionEnd ?? start;
        runSlashCommand(command, invocation.argument, "", { text, start, end });
        return;
      }
      event.preventDefault();
      submit();
    }
  };

  /** 只要存在非空纯文本就保留 WebView 原生粘贴；否则交给 Rust 识别 CF_HDROP 或位图。 */
  const handlePaste = (event: ClipboardEvent<HTMLTextAreaElement>): void => {
    if (event.clipboardData.getData("text/plain").length > 0) return;
    if (onPasteAttachments === undefined || disabled || sending) return;
    event.preventDefault();
    void onPasteAttachments();
  };

  const composerState = disabled
    ? "disabled"
    : suspendedTurn
      ? "suspended"
      : activeTurn
        ? "active"
        : sending
          ? "loading"
          : "ready";
  const activeTurnHasDraft = activeTurn && hasDraftContent;

  return (
    <form
      ref={dropZoneRef}
      className={cn("ja-composer", className)}
      onSubmit={handleSubmit}
      aria-label="发送消息"
      data-state={composerState}
      data-has-queue={queuedInputs.length > 0 || undefined}
      data-drop-active={dropActive || undefined}
      aria-busy={activeTurn || sending || cancelling || resuming || undefined}
    >
      {goalStatus}
      {dropActive ? (
        <span className="ja-composer__drop-indicator" aria-hidden="true">
          <Plus />
        </span>
      ) : null}
      {queuedInputs.length === 0 ? null : (
        <ol className="ja-composer-queue" aria-label="排队消息">
          {queuedInputs.map((item, position) => (
            <QueuedInputRow
              key={item.inputId}
              item={item}
              position={position}
              skills={skills}
              onPrioritize={onPrioritizeQueuedInput}
              onUpdate={onUpdateQueuedInput}
              onDelete={onDeleteQueuedInput}
              onOpenPreview={onOpenQueuedAttachmentPreview}
            />
          ))}
        </ol>
      )}
      {draftItems.length === 0 ? null : (
        <ul className="ja-composer__attachments" aria-label="待发送附件">
          {draftItems.map((item) => (
            <AttachmentDraftCard
              key={item.itemId}
              item={item}
              sending={sending}
              onRetry={onRetryAttachment}
              onRemove={onRemoveAttachment}
              onOpenPreview={onOpenAttachmentPreview}
            />
          ))}
        </ul>
      )}
      <ComposerContextChips
        references={contextReferences}
        onOpenWorkspaceReference={onOpenWorkspaceReference}
        onRemove={
          onContextReferencesChange === undefined
            ? undefined
            : (reference) =>
                onContextReferencesChange(
                  contextReferences.filter(
                    (candidate) =>
                      contextReferenceIdentity(candidate) !== contextReferenceIdentity(reference),
                  ),
                )
        }
      />
      {inlineCommand === undefined ? null : (
        <div
          className="ja-composer__inline-command"
          data-command={inlineCommand.name}
          role="status"
        >
          <Target aria-hidden="true" />
          <span>{inlineCommand.argument?.label ?? inlineCommand.label}</span>
          <button
            type="button"
            aria-label={`退出${inlineCommand.argument?.label ?? inlineCommand.label}编辑`}
            title="退出编辑"
            onClick={cancelInlineCommand}
          >
            <X aria-hidden="true" />
          </button>
        </div>
      )}
      {visibleTrigger === undefined ? null : (
        <ComposerSuggestionPanel
          id={suggestionListId}
          kind={visibleTrigger.kind}
          state={
            visibleTrigger.kind === "workspace"
              ? workspaceSuggestions.state === "idle"
                ? "loading"
                : workspaceSuggestions.state
              : "ready"
          }
          items={suggestionItems}
          activeId={activeSuggestionId}
          error={workspaceSuggestions.error}
          truncated={visibleTrigger.kind === "workspace" && workspaceSuggestions.truncated}
          onActiveChange={setActiveSuggestionId}
          onSelect={selectSuggestion}
          onRetry={
            visibleTrigger.kind === "workspace"
              ? () => setSuggestionRetry((current) => current + 1)
              : undefined
          }
        />
      )}
      <textarea
        ref={inputRef}
        className="ja-composer__input"
        aria-label={inlineCommand?.argument?.placeholder ?? "消息"}
        placeholder={inlineCommand?.argument?.placeholder ?? placeholder}
        aria-describedby={error || commandError ? feedbackId : undefined}
        aria-controls={visibleTrigger === undefined ? undefined : suggestionListId}
        aria-expanded={visibleTrigger !== undefined}
        aria-haspopup="listbox"
        aria-autocomplete="list"
        aria-activedescendant={visibleTrigger === undefined ? undefined : activeSuggestionId}
        value={text}
        maxLength={1_048_576}
        rows={1}
        disabled={disabled || cancelling || resuming}
        onChange={handleTextChange}
        onFocus={(event) => {
          const start = event.currentTarget.selectionStart ?? event.currentTarget.value.length;
          const end = event.currentTarget.selectionEnd ?? start;
          setInputFocused(true);
          lastSelectionRef.current = { start, end };
          setCaretState({ text: event.currentTarget.value, position: start });
        }}
        onBlur={(event) => {
          setInputFocused(false);
          if (caretState.text === event.currentTarget.value) return;
          const position = event.currentTarget.value.length;
          lastSelectionRef.current = { start: position, end: position };
          setCaretState({ text: event.currentTarget.value, position });
        }}
        onCompositionStart={() => {
          composingRef.current = true;
          setComposing(true);
        }}
        onCompositionEnd={(event) => {
          composingRef.current = false;
          setComposing(false);
          setCaretState({
            text: event.currentTarget.value,
            position: event.currentTarget.selectionStart ?? event.currentTarget.value.length,
          });
        }}
        onKeyDown={handleKeyDown}
        onKeyUp={(event) => {
          const start = event.currentTarget.selectionStart ?? text.length;
          const end = event.currentTarget.selectionEnd ?? start;
          lastSelectionRef.current = { start, end };
          setCaretState({ text: event.currentTarget.value, position: start });
        }}
        onClick={(event) => {
          const start = event.currentTarget.selectionStart ?? text.length;
          const end = event.currentTarget.selectionEnd ?? start;
          lastSelectionRef.current = { start, end };
          setCaretState({ text: event.currentTarget.value, position: start });
        }}
        onSelect={(event) => {
          const start = event.currentTarget.selectionStart ?? text.length;
          const end = event.currentTarget.selectionEnd ?? start;
          lastSelectionRef.current = { start, end };
          setCaretState({ text: event.currentTarget.value, position: start });
        }}
        onPaste={handlePaste}
      />
      <div className="ja-composer__toolbar">
        <div className="ja-composer__leading">
          {onAddAttachments === undefined ? null : (
            <IconButton
              className="ja-composer__add-button"
              label="添加附件"
              tooltip="添加附件"
              disabled={disabled || sending || importingAttachments}
              aria-busy={importingAttachments || undefined}
              onClick={() => void onAddAttachments()}
            >
              {importingAttachments ? (
                <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
              ) : (
                <Plus aria-hidden="true" />
              )}
            </IconButton>
          )}
          {preferences === undefined ? null : (
            <Select
              ariaLabel="访问模式"
              size="compact"
              className="ja-composer__access-select"
              value={preferences.accessMode}
              disabled={disabled || preferenceBusy || onAccessModeChange === undefined}
              onValueChange={(value) => onAccessModeChange?.(value as ConversationAccessMode)}
              options={[
                { value: "approval_required", label: "需要审批" },
                { value: "full_access", label: "完全访问" },
              ]}
            />
          )}
          {modeStatus}
        </div>
        <div className="ja-composer__trailing">
          {contextUsage === undefined ? null : <ContextUsageIndicator usage={contextUsage} />}
          <Menu modal={false}>
            <MenuTrigger asChild>
              <button
                type="button"
                className="ja-composer__model-trigger"
                aria-label={selectionAccessibilityLabel}
                title={selectionAccessibilityLabel}
                disabled={disabled || preferenceBusy || models.length === 0}
              >
                <span>{selectionLabel}</span>
                <ChevronDown aria-hidden="true" />
              </button>
            </MenuTrigger>
            <MenuContent
              className="ja-composer__selection-menu"
              align="end"
              aria-label="模型与推理设置"
            >
              <MenuRadioGroup
                value={selectedModel?.value ?? ""}
                onValueChange={(value) => onModelChange?.(value)}
                aria-label="模型"
              >
                {groupedModels.map((group, groupIndex) => (
                  <div key={group.providerId} className="ja-composer__provider-group">
                    {groupIndex === 0 ? null : <MenuSeparator />}
                    <MenuLabel className="ja-composer__provider-label" title={group.label}>
                      {group.label}
                    </MenuLabel>
                    {group.options.map((option) => (
                      <MenuRadioItem
                        key={option.value}
                        value={option.value}
                        disabled={onModelChange === undefined}
                        className="ja-composer__radio-item ja-composer__model-item"
                        title={`${option.modelIdentifier} · ${group.label}`}
                      >
                        <span className="ja-composer__model-copy">
                          <span>{option.modelIdentifier}</span>
                          {option.alias === undefined ? null : <small>{option.alias}</small>}
                        </span>
                        <MenuItemIndicator className="ja-composer__radio-indicator">
                          <Check aria-hidden="true" />
                        </MenuItemIndicator>
                      </MenuRadioItem>
                    ))}
                  </div>
                ))}
              </MenuRadioGroup>
              {selectedModel !== undefined &&
              Object.keys(selectedModel.reasoningLevelMap).length > 0 ? (
                <>
                  <MenuSeparator />
                  <MenuSub>
                    <MenuSubTrigger className="ja-composer__selection-row">
                      <span>推理强度</span>
                      <span className="ja-composer__selection-current">
                        {preferences?.reasoningLevel === null
                          ? "跟随模型"
                          : selectedReasoning === null
                            ? "默认"
                            : reasoningLabel(selectedReasoning)}
                      </span>
                      <ChevronRight aria-hidden="true" />
                    </MenuSubTrigger>
                    <MenuSubContent alignOffset={-4} aria-label="选择推理强度">
                      <MenuRadioGroup
                        value={preferences?.reasoningLevel ?? MODEL_DEFAULT_REASONING_VALUE}
                        onValueChange={(value) =>
                          onReasoningChange?.(
                            value === MODEL_DEFAULT_REASONING_VALUE
                              ? null
                              : (value as ReasoningLevel),
                          )
                        }
                      >
                        <MenuRadioItem
                          value={MODEL_DEFAULT_REASONING_VALUE}
                          disabled={onReasoningChange === undefined}
                          className="ja-composer__radio-item"
                        >
                          <span>跟随模型默认</span>
                          <MenuItemIndicator className="ja-composer__radio-indicator">
                            <Check aria-hidden="true" />
                          </MenuItemIndicator>
                        </MenuRadioItem>
                        {(Object.keys(selectedModel.reasoningLevelMap) as ReasoningLevel[]).map(
                          (effort) => (
                            <MenuRadioItem
                              key={effort}
                              value={effort}
                              disabled={onReasoningChange === undefined}
                              className="ja-composer__radio-item"
                            >
                              <span>{reasoningLabel(effort)}</span>
                              <MenuItemIndicator className="ja-composer__radio-indicator">
                                <Check aria-hidden="true" />
                              </MenuItemIndicator>
                            </MenuRadioItem>
                          ),
                        )}
                      </MenuRadioGroup>
                    </MenuSubContent>
                  </MenuSub>
                </>
              ) : null}
              {onRestoreDefaults === undefined ? null : (
                <>
                  <MenuSeparator />
                  <MenuItem onSelect={() => void onRestoreDefaults()}>恢复默认设置</MenuItem>
                </>
              )}
            </MenuContent>
          </Menu>
          {suspendedTurn ? (
            <>
              <span className="ja-composer__interruption" role="status">
                运行被中断
              </span>
              <IconButton
                type="button"
                className="ja-composer__action-button is-cancel"
                disabled={!canCancel}
                label="取消运行"
                tooltip="取消当前中断的运行"
                onClick={cancel}
              >
                {cancelling ? (
                  <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
                ) : (
                  <Square aria-hidden="true" />
                )}
              </IconButton>
              <IconButton
                type="button"
                className="ja-composer__action-button is-send"
                disabled={!canResume}
                label="继续运行"
                aria-busy={resuming || undefined}
                tooltip="从已保存的位置继续"
                onClick={resume}
              >
                {resuming ? (
                  <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
                ) : (
                  <Play aria-hidden="true" />
                )}
              </IconButton>
            </>
          ) : (
            <IconButton
              type={
                inlineCommand === undefined && activeTurn && !activeTurnHasDraft
                  ? "button"
                  : "submit"
              }
              className={cn(
                "ja-composer__action-button",
                inlineCommand === undefined && activeTurn && !activeTurnHasDraft
                  ? "is-cancel"
                  : "is-send",
              )}
              disabled={
                inlineCommand === undefined && activeTurn && !activeTurnHasDraft
                  ? !canCancel
                  : !canSend
              }
              label={
                inlineCommand !== undefined
                  ? `创建${inlineCommand.argument?.label ?? inlineCommand.label}`
                  : activeTurn && !activeTurnHasDraft
                    ? "停止生成"
                    : activeTurn
                      ? "排队发送"
                      : "发送"
              }
              aria-busy={(activeTurn && !activeTurnHasDraft ? cancelling : sending) || undefined}
              tooltip={
                inlineCommand !== undefined
                  ? `创建${inlineCommand.argument?.label ?? inlineCommand.label}`
                  : activeTurn && !activeTurnHasDraft
                    ? "停止当前生成"
                    : activeTurn
                      ? "排队发送"
                      : "发送消息"
              }
              onClick={
                inlineCommand === undefined && activeTurn && !activeTurnHasDraft
                  ? cancel
                  : undefined
              }
            >
              {(activeTurn && !activeTurnHasDraft ? cancelling : sending) ? (
                <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
              ) : inlineCommand !== undefined ? (
                <Target aria-hidden="true" />
              ) : activeTurn && !activeTurnHasDraft ? (
                <Square aria-hidden="true" />
              ) : (
                <ArrowUp aria-hidden="true" />
              )}
            </IconButton>
          )}
        </div>
      </div>
      {error || commandError ? (
        <p id={feedbackId} className="ja-composer__error" role="alert">
          {error ?? commandError}
        </p>
      ) : null}
    </form>
  );
}
