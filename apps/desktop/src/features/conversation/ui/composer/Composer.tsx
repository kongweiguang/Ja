// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  ArrowUp,
  Check,
  ChevronDown,
  ChevronRight,
  File,
  Folder,
  GitBranch,
  Laptop,
  LoaderCircle,
  Plus,
  Square,
  X,
} from "lucide-react";
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent,
  type ReactElement,
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
} from "@/shared/ui/primitives";
import { cn } from "@/shared/ui/primitives/cn";
import type {
  ConversationAccessMode,
  ConversationAttachment,
  ConversationModelOption,
  ConversationQueueMode,
  ConversationSubmit,
  ConversationThreadPreferences,
  ReasoningLevel,
} from "../../application/ports";
import type { ContextUsagePresentation } from "../../domain/contextUsage";
import { ContextUsageIndicator } from "./ContextUsageIndicator";
import "./composer.css";

type ComposerQueueMode = ConversationQueueMode;
export type ComposerSubmit = ConversationSubmit;

export interface ComposerProps {
  preferences?: ConversationThreadPreferences;
  /** 草稿由会话层唯一持有，Composer 只投影文本并上报编辑意图。 */
  text: string;
  onTextChange: (text: string) => void;
  models?: readonly ConversationModelOption[];
  attachments?: readonly ConversationAttachment[];
  activeTurn?: boolean;
  disabled?: boolean;
  preferenceBusy?: boolean;
  importingAttachments?: boolean;
  sending?: boolean;
  cancelling?: boolean;
  error?: string;
  queueStatus?: string;
  /** 仅当 application 已证明模型身份并取得真实 Token 计量时提供。 */
  contextUsage?: ContextUsagePresentation;
  onModelChange?: (selectionValue: string) => void;
  onReasoningChange?: (reasoningLevel: ReasoningLevel | null) => void;
  onAccessModeChange?: (accessMode: ConversationAccessMode) => void;
  onRestoreDefaults?: () => void | Promise<void>;
  onAddAttachments?: () => void | Promise<void>;
  onRemoveAttachment?: (attachmentId: string) => void | Promise<void>;
  onSend: (request: ComposerSubmit) => void | Promise<void>;
  /** 活动 Turn 复用同一输入框，但每次只排入一条纯文本消息。 */
  onQueue?: (text: string, mode: ComposerQueueMode) => void | Promise<void>;
  onCancel?: () => void | Promise<void>;
  className?: string;
}

const TEXTAREA_MIN_HEIGHT = 32;
const TEXTAREA_MAX_LINES = 6;
const MODEL_DEFAULT_REASONING_VALUE = "model_default";

export interface ComposerContextProps {
  workspaceLabel: string;
  gitBranch?: string;
}

/**
 * 将项目、运行环境和真实 Git 分支投影为输入表单上方的独立上下文栏；该组件只展示组合层提供的
 * 脱敏标签，不查询 Workspace 或 Git，也不把上下文混入可提交的表单数据。
 */
export function ComposerContext({ workspaceLabel, gitBranch }: ComposerContextProps): ReactElement {
  return (
    <div className="ja-composer__context" aria-label="当前执行上下文">
      <span title={workspaceLabel}>
        <Folder aria-hidden="true" />
        <span>{workspaceLabel}</span>
      </span>
      <span>
        <Laptop aria-hidden="true" />
        <span>本地</span>
      </span>
      {gitBranch === undefined ? null : (
        <span title={gitBranch}>
          <GitBranch aria-hidden="true" />
          <span>{gitBranch}</span>
        </span>
      )}
    </div>
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

/**
 * 渲染同一内容轨道上的生产输入器；Thread 偏好通过 CAS 回调更新，发送只提交文本与 App Server
 * 签发的 attachmentId。项目上下文由组合层放在表单上方，避免输入器承担非表单信息。
 */
export function Composer({
  preferences,
  text,
  onTextChange,
  models = [],
  attachments = [],
  activeTurn = false,
  disabled = false,
  preferenceBusy = false,
  importingAttachments = false,
  sending = false,
  cancelling = false,
  error,
  queueStatus,
  contextUsage,
  onModelChange,
  onReasoningChange,
  onAccessModeChange,
  onRestoreDefaults,
  onAddAttachments,
  onRemoveAttachment,
  onSend,
  onQueue,
  onCancel,
  className,
}: ComposerProps): ReactElement {
  const [queueMode, setQueueMode] = useState<ComposerQueueMode>("steering");
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const composingRef = useRef(false);
  const feedbackId = useId();
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
  const hasDraftContent = text.trim().length > 0 || (!activeTurn && attachments.length > 0);
  const canSend =
    hasDraftContent &&
    !disabled &&
    !preferenceBusy &&
    !sending &&
    !cancelling &&
    (!activeTurn || onQueue !== undefined);
  const canCancel = activeTurn && !disabled && !cancelling && onCancel !== undefined;

  /** 只把发送或排队意图上报给 application controller，组件不建立第二把并发锁。 */
  const submit = (): void => {
    if (!canSend) return;
    if (activeTurn && onQueue !== undefined) {
      void onQueue(text.trim(), queueMode);
      return;
    }
    void onSend({
      text: text.trim(),
      attachmentIds: attachments.map((attachment) => attachment.attachmentId),
    });
  };

  /** 取消只发布用户意图；Turn identity、CAS 和 single-flight 由 application controller 冻结。 */
  const cancel = (): void => {
    if (!canCancel || onCancel === undefined) return;
    void onCancel();
  };

  /** 同步测量输入高度后把编辑意图交回会话 owner，避免 Composer 复制第二份草稿事实。 */
  const handleTextChange = (event: ChangeEvent<HTMLTextAreaElement>): void => {
    resizeTextarea(event.currentTarget);
    onTextChange(event.currentTarget.value);
  };

  /** 输入内容变化后重新测量 DOM，受控草稿从线程切换回来时也能恢复高度。 */
  useLayoutEffect(() => {
    if (inputRef.current !== null) resizeTextarea(inputRef.current);
  }, [text]);

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

  /** 统一表单提交入口，阻止浏览器导航并把真实提交交给并发保护的 submit。 */
  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    submit();
  };

  /** Enter 发送、Shift+Enter 换行；IME composition 期间绝不提交未确认的中文候选。 */
  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>): void => {
    if (
      event.key === "Enter" &&
      !event.shiftKey &&
      !composingRef.current &&
      !event.nativeEvent.isComposing
    ) {
      event.preventDefault();
      submit();
    }
  };

  const composerState = disabled
    ? "disabled"
    : activeTurn
      ? "active"
      : sending
        ? "loading"
        : "ready";

  return (
    <form
      className={cn("ja-composer", className)}
      onSubmit={handleSubmit}
      aria-label="发送消息"
      data-state={composerState}
      aria-busy={activeTurn || sending || undefined}
    >
      {attachments.length === 0 ? null : (
        <ul className="ja-composer__attachments" aria-label="待发送附件">
          {attachments.map((attachment) => (
            <li key={attachment.attachmentId}>
              <File aria-hidden="true" />
              <span title={attachment.fileName}>{attachment.fileName}</span>
              <small>{formatFileSize(attachment.sizeBytes)}</small>
              <IconButton
                label={`移除附件 ${attachment.fileName}`}
                tooltip="移除附件"
                disabled={sending || onRemoveAttachment === undefined}
                onClick={() => void onRemoveAttachment?.(attachment.attachmentId)}
              >
                <X aria-hidden="true" />
              </IconButton>
            </li>
          ))}
        </ul>
      )}
      <textarea
        ref={inputRef}
        className="ja-composer__input"
        aria-label="消息"
        placeholder="随心输入"
        aria-describedby={error || queueStatus ? feedbackId : undefined}
        value={text}
        maxLength={1_048_576}
        rows={1}
        disabled={disabled || cancelling}
        onChange={handleTextChange}
        onCompositionStart={() => {
          composingRef.current = true;
        }}
        onCompositionEnd={() => {
          composingRef.current = false;
        }}
        onKeyDown={handleKeyDown}
      />
      <div className="ja-composer__toolbar">
        <div className="ja-composer__leading">
          {onAddAttachments === undefined ? null : (
            <IconButton
              className="ja-composer__add-button"
              label="添加附件"
              tooltip={activeTurn ? "当前 Turn 结束后可添加附件" : "添加附件"}
              disabled={disabled || activeTurn || sending || importingAttachments}
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
          {activeTurn ? (
            <Select
              ariaLabel="消息队列"
              size="compact"
              className="ja-composer__queue-select"
              value={queueMode}
              disabled={disabled || sending || cancelling}
              onValueChange={(value) => setQueueMode(value as ComposerQueueMode)}
              options={[
                { value: "steering", label: "立即引导" },
                { value: "follow_up", label: "后续消息" },
              ]}
            />
          ) : null}
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
          {activeTurn ? (
            <IconButton
              className="ja-composer__action-button is-cancel"
              disabled={!canCancel}
              label="取消"
              aria-busy={cancelling || undefined}
              tooltip="取消当前生成"
              onClick={cancel}
            >
              {cancelling ? (
                <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
              ) : (
                <Square aria-hidden="true" />
              )}
            </IconButton>
          ) : null}
          <IconButton
            type="submit"
            className="ja-composer__action-button is-send"
            disabled={!canSend}
            label={activeTurn ? "加入队列" : "发送"}
            aria-busy={sending || undefined}
            tooltip={activeTurn ? "加入消息队列" : "发送消息"}
          >
            {sending ? (
              <LoaderCircle aria-hidden="true" className="ja-composer__spin" />
            ) : (
              <ArrowUp aria-hidden="true" />
            )}
          </IconButton>
        </div>
      </div>
      {queueStatus ? (
        <p id={feedbackId} className="ja-composer__feedback" role="status">
          {queueStatus}
        </p>
      ) : error ? (
        <p id={feedbackId} className="ja-composer__error" role="alert">
          {error}
        </p>
      ) : null}
    </form>
  );
}
