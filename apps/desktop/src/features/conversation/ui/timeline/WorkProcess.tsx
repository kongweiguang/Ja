// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronDown, Clock3, Files, ListTree, ShieldAlert, Terminal, Wrench } from "lucide-react";
import { useLayoutEffect, useRef, useState, type ReactElement } from "react";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/shared/ui/primitives/Collapsible";
import { cn } from "@/shared/ui/primitives/cn";
import {
  ApprovalCard,
  type ApprovalDecision,
  type UserApprovalDecision,
} from "../approval/ApprovalCard";
import type { TimelineApproval as ApprovalSummary } from "../../domain/timelineTypes";
import {
  isReasoningItem,
  itemDurationMs,
  turnDurationMs,
  workStepLabel,
  type ToolPresentation,
  type WorkStepAdapter,
  type TimelineTurn,
} from "../../domain/timelineTypes";
import { MarkdownMessage, type MarkdownFileTarget } from "./MarkdownMessage";
import { ToolStepDetails } from "./ToolStepDetails";
import type { TimelineDisclosureCache } from "./timelineDisclosure";
import type { ConversationProcessBinding } from "../../application/processSessionTypes";
import { countCommittedConversationActivity } from "../../domain/conversationActivity";
import { useFullPublicText } from "./useFullPublicText";
import { exportPublicText } from "./exportPublicText";
import "./timeline.css";

export interface WorkProcessProps {
  steps: readonly WorkStepAdapter[];
  turn?: TimelineTurn;
  /** 续答共享同一过程；仅合计各次尝试的权威耗时，排除用户等待继续的间隔。 */
  relatedTurns?: readonly TimelineTurn[];
  /**
   * Timeline 根据权威 Turn 和最终答复决定何时把过程归档；inline 保持正文直出，避免流式阶段
   * 先出现一层无操作价值的总折叠栏。未传入时保留独立复用场景的既有折叠语义。
   */
  displayMode?: WorkProcessDisplayMode;
  /** 用户没有跟随最新内容时不自动收口，避免完成事件改变正在阅读的历史位置。 */
  autoCollapse?: boolean;
  /** Workspace 生命周期内的瞬态折叠选择，不进入协议或持久化。 */
  disclosureCache?: TimelineDisclosureCache;
  /** 当前 exchange 的稳定 identity；与 Thread 一起隔离多轮回复。 */
  disclosureKey?: string;
  disclosureThreadId?: string;
  approvals?: readonly ApprovalSummary[];
  approvalDecisions?: Readonly<Record<string, ApprovalDecision | undefined>>;
  approvalClosedAt?: Readonly<Record<string, string | undefined>>;
  onApprovalDecision?: (
    approval: ApprovalSummary,
    decision: UserApprovalDecision,
  ) => void | Promise<void>;
  onOpenLink?: (url: string, source?: HTMLElement) => void | Promise<void>;
  onOpenFile?: (
    target: MarkdownFileTarget,
    source: HTMLElement,
    mode?: "explorer" | "browser",
  ) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  onReadMessageContent?: (threadId: string, itemId: string) => Promise<string>;
  onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  onOpenAttachmentPreview?: (
    attachment: {
      attachmentId: string;
      displayName: string;
      mediaKind: "image" | "text";
      threadId: string;
      authorization: { kind: "thread"; threadId: string };
    },
    source: HTMLButtonElement,
  ) => void;
  onResolveToolRecovery?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    expectedThreadRevision: number;
    expectedRecoveryRevision: number;
    decision: "retry" | "skip";
    operationKey: string;
  }) => Promise<unknown>;
  processSession?: ConversationProcessBinding;
  className?: string;
}

export type WorkProcessDisplayMode = "inline" | "archived";

type WorkProcessState =
  | "queued"
  | "active"
  | "waiting"
  | "suspended"
  | "failed"
  | "cancelled"
  | "completed";

/**
 * 从权威 Turn、审批与 Item Status 派生单一用户可见状态，避免视图复制状态机。
 *
 * 只要存在 Turn，它的六态就是整轮状态的唯一依据；运行中出现的 Tool 失败仍可能被后续步骤恢复，
 * 因而不能提前把整轮标成失败。没有 Turn 时，未决审批和活动步骤优先于局部失败，确保当前阻塞点可见。
 */
function processState(
  steps: readonly WorkStepAdapter[],
  turn: TimelineTurn | undefined,
  approvalPending: boolean,
): WorkProcessState {
  switch (turn?.status) {
    case "queued":
      return "queued";
    case "running":
      return "active";
    case "waiting_approval":
      return "waiting";
    case "suspended":
      return "suspended";
    case "completed":
      return "completed";
    case "failed":
      return "failed";
    case "cancelled":
      return "cancelled";
    case undefined:
      break;
  }
  if (approvalPending) {
    return "waiting";
  }
  if (steps.some((step) => step.status === "started" || step.status === "in_progress")) {
    return "active";
  }
  if (steps.some((step) => step.status === "failed")) {
    return "failed";
  }
  if (steps.some((step) => step.status === "cancelled")) {
    return "cancelled";
  }
  return "completed";
}

/** 将内部过程状态压缩为单一用户文案；省略号只用于仍会继续推进的运行态。 */
function processStatusLabel(state: WorkProcessState): string {
  switch (state) {
    case "queued":
      return "排队中";
    case "active":
      return "进行中…";
    case "waiting":
      return "等待确认";
    case "suspended":
      return "已暂停";
    case "completed":
      return "已完成";
    case "failed":
      return "失败";
    case "cancelled":
      return "已取消";
  }
}

/** 使用熟悉的 Tool Icon，但不增加第二套前端 Item Taxonomy。 */
function StepIcon({ step }: { step: WorkStepAdapter }): ReactElement {
  if (step.metadata?.presentation?.kind === "context") {
    return <ListTree aria-hidden="true" />;
  }
  if (step.metadata?.presentation?.kind === "shell") {
    return <Terminal aria-hidden="true" />;
  }
  switch (step.kind) {
    case "command":
      return <Terminal aria-hidden="true" />;
    case "tool_call":
      return <Wrench aria-hidden="true" />;
    case "approval":
      return <ShieldAlert aria-hidden="true" />;
    default:
      return <Files aria-hidden="true" />;
  }
}

/**
 * 冻结历史可能来自一个仍在来源 Thread 中运行的 Turn；展示时把遗留活动态收口为历史完成态，
 * 同时保留其公开输入、结果和附件，但不携带可操作的来源进程授权。
 * @author kongweiguang
 */
function projectWorkStepForDisplay(step: WorkStepAdapter): WorkStepAdapter {
  if (step.origin !== "inherited") return step;
  const status = step.status === "started" || step.status === "in_progress" ? "completed" : step.status;
  const presentation = step.metadata?.presentation;
  const presentationStatus =
    presentation?.status === "pending" ||
    presentation?.status === "running" ||
    presentation?.status === "waiting_approval"
      ? "success"
      : presentation?.status;
  const hasActivePresentation =
    presentation !== undefined && presentationStatus !== presentation.status;
  return {
    ...step,
    status,
    metadata:
      !hasActivePresentation || presentation === undefined || presentationStatus === undefined
        ? step.metadata
        : { ...step.metadata, presentation: { ...presentation, status: presentationStatus } },
  };
}

/**
 * 将 Host 提供的 elapsed milliseconds 压缩为中文阅读单位；秒级保留一位小数，
 * 分钟以上逐级省略低价值精度，避免过程摘要被英文单位或过长数字抢占。
 */
function formatDuration(durationMs: number | undefined): string | undefined {
  if (durationMs === undefined || !Number.isFinite(durationMs) || durationMs < 0) {
    return undefined;
  }
  if (durationMs < 60_000) {
    return `${Math.round(durationMs / 100) / 10}秒`;
  }
  if (durationMs < 3_600_000) {
    const totalSeconds = Math.floor(durationMs / 1_000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return seconds === 0 ? `${minutes}分` : `${minutes}分${seconds}秒`;
  }
  const totalMinutes = Math.floor(durationMs / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return minutes === 0 ? `${hours}小时` : `${hours}小时${minutes}分`;
}

/** 将单个权威 Work Status 映射为可读文案，并明确覆盖 Cancellation。 */
function stepStatusLabel(status: WorkStepAdapter["status"]): string {
  switch (status) {
    case "started":
    case "in_progress":
      return "进行中";
    case "completed":
      return "完成";
    case "failed":
      return "失败";
    case "cancelled":
      return "已取消";
  }
}

/** ToolPresentation 是工具生命周期的权威展示事实；缺失时才回退到通用 Item 状态。 */
function presentedStepStatusLabel(step: WorkStepAdapter): string {
  switch (step.metadata?.presentation?.status) {
    case "pending":
      return "等待执行";
    case "running":
      return "进行中";
    case "waiting_approval":
      return "等待确认";
    case "success":
      return "完成";
    case "error":
      return "失败";
    case "cancelled":
      return "已取消";
    case undefined:
      return stepStatusLabel(step.status);
  }
}

/**
 * Detail 只渲染公开 Commentary/Reasoning Text 与显式 Item Summary。Item Kind 已在 Protocol
 * 边界列入 Allowlist，因此 Hidden Reasoning Record 不能借由兜底展示通道进入组件；公开
 * 持久正文可能只给安全预览；视图不再额外截断 Commentary，完整内容由用户按需读取。
 */
function stepDetail(step: WorkStepAdapter): string | undefined {
  const summary = step.summary?.trim();
  // Tool/Command/File 文本可能含 Path、Argument 或 Provider Output；
  // 只有公开 Commentary/Reasoning Text 与显式 Adapter Summary 可以安全渲染。
  const text =
    summary || (step.kind === "commentary" || isReasoningItem(step) ? step.text : undefined);
  if (!text) {
    return undefined;
  }
  return text;
}

/** 长公开过程和持续增长的草稿只渲染一个文本节点，避免每个 delta 重解析整段 Markdown；
 * 已提交内容的查看和复制仍通过同一 item 分页读取全文。 */
function PublicTextStep({
  step,
  detail,
  onReadMessageContent,
  onCopyText,
  onOpenLink,
  onOpenFile,
}: {
  step: WorkStepAdapter;
  detail: string;
  onReadMessageContent?: (threadId: string, itemId: string) => Promise<string>;
  onCopyText?: (text: string) => Promise<void>;
  onOpenLink?: WorkProcessProps["onOpenLink"];
  onOpenFile?: WorkProcessProps["onOpenFile"];
}): ReactElement {
  const long = detail.length >= 4_095;
  const plainDuringLongStream = step.status === "in_progress" && detail.length >= 2_048;
  const persistedPublicText =
    isReasoningItem(step) ||
    (step.kind === "commentary" && step.metadata?.phase === "assistant_progress");
  const canRead =
    long &&
    persistedPublicText &&
    detail === step.text &&
    step.status === "completed" &&
    onReadMessageContent !== undefined;
  const { text, loading, error, load } = useFullPublicText(
    step,
    canRead ? () => onReadMessageContent(step.threadId, step.itemId) : undefined,
  );
  const [exportFeedback, setExportFeedback] = useState<{
    itemId: string;
    phase: "saving" | "saved" | "unsupported" | "error";
  }>();
  const exportPhase = exportFeedback?.itemId === step.itemId ? exportFeedback.phase : undefined;
  /** 长过程只由用户点击后读取并写入系统保存句柄，取消不制造错误状态。 */
  const exportFullText = async (): Promise<void> => {
    setExportFeedback({ itemId: step.itemId, phase: "saving" });
    try {
      const outcome = await exportPublicText(
        await load(),
        isReasoningItem(step) ? "Ja-思考摘要.txt" : "Ja-过程正文.txt",
      );
      setExportFeedback(
        outcome === "cancelled"
          ? undefined
          : { itemId: step.itemId, phase: outcome === "saved" ? "saved" : "unsupported" },
      );
    } catch {
      setExportFeedback({ itemId: step.itemId, phase: "error" });
    }
  };
  const visibleText = text ?? detail;
  const plainLongText = plainDuringLongStream || visibleText.length >= 65_535;
  return (
    <>
      {plainLongText ? (
        <pre className="ja-chat-response__long-text">{visibleText}</pre>
      ) : (
        <MarkdownMessage
          content={visibleText}
          onOpenLink={onOpenLink}
          onOpenFile={onOpenFile}
          onCopyText={onCopyText}
        />
      )}
      {canRead ? (
        <div className="ja-public-text-actions">
          {text === undefined ? (
            <button
              type="button"
              className="ja-chat-response__full-action"
              disabled={loading}
              onClick={() => void load().catch(() => {})}
            >
              {loading
                ? "正在读取全文…"
                : error
                  ? "重试读取全文"
                  : isReasoningItem(step)
                    ? "查看完整思考摘要"
                    : "查看完整过程正文"}
            </button>
          ) : null}
          {onCopyText === undefined ? null : (
            <button
              type="button"
              className="ja-chat-response__full-action"
              onClick={() =>
                void load()
                  .then(onCopyText)
                  .catch(() => {})
              }
            >
              复制全文
            </button>
          )}
          <button
            type="button"
            className="ja-chat-response__full-action"
            disabled={exportPhase === "saving"}
            onClick={() => void exportFullText()}
          >
            {exportPhase === "saving" ? "正在导出…" : "导出全文"}
          </button>
        </div>
      ) : null}
      {exportPhase === "saved" ? (
        <p className="ja-chat-response__full-note" role="status">
          已导出全文。
        </p>
      ) : null}
      {exportPhase === "unsupported" ? (
        <p className="ja-chat-response__full-error" role="alert">
          当前窗口不支持直接导出，可复制全文。
        </p>
      ) : null}
      {exportPhase === "error" ? (
        <p className="ja-chat-response__full-error" role="alert">
          导出未完成，请重试。
        </p>
      ) : null}
      {error ? (
        <p className="ja-chat-response__full-error" role="alert">
          完整内容暂时无法读取。
        </p>
      ) : null}
    </>
  );
}

/** 只有至少一个 Source Item 提供 Metric 时才合计可选值。 */
function sumOptional(values: readonly (number | undefined)[]): number | undefined {
  const supplied = values.filter((value): value is number => value !== undefined);
  if (supplied.length === 0) {
    return undefined;
  }
  const total = supplied.reduce((sum, value) => sum + value, 0);
  return Number.isSafeInteger(total) ? total : undefined;
}

/**
 * 自动归档只在用户没有选择或聚焦过程内容时进行。该检查只在 layout effect 中读取上一轮已提交的
 * DOM，恰好覆盖流式 Turn 切为 completed 的渲染边界；一旦保留展开，后续事件不会反复改写阅读决定。
 */
function processReadingIsActive(element: HTMLElement | null): boolean {
  if (element === null || typeof document === "undefined" || typeof window === "undefined") {
    return false;
  }
  if (document.activeElement !== null && element.contains(document.activeElement)) return true;
  const selection = window.getSelection();
  return (
    selection !== null &&
    selection.toString().trim() !== "" &&
    (element.contains(selection.anchorNode) || element.contains(selection.focusNode))
  );
}

/**
 * 流式、失败、取消与未决审批保持可操作内容展开，只有权威最终答复到达后才归档；
 * 自动归档尊重已聚焦或选中的过程内容，独立复用且未传 displayMode 时沿用原折叠策略。
 * 归档入口以次要文字保留工作时长和真实调用次数；缺失时间不猜测，统计不进入持久化。
 * 一个过程可合并续答 Turn，步骤展示必须按 Thread/Turn 复合身份读取状态，不能用当前行状态覆盖旧步骤。
 * @author kongweiguang
 */
export function WorkProcess({
  steps,
  turn,
  relatedTurns,
  displayMode,
  autoCollapse = true,
  disclosureCache,
  disclosureKey,
  disclosureThreadId,
  approvals = [],
  approvalDecisions = {},
  approvalClosedAt = {},
  onApprovalDecision,
  onOpenLink,
  onOpenFile,
  onCopyText,
  onReadMessageContent,
  onReadToolArtifact,
  onResolveToolRecovery,
  onOpenAttachmentPreview,
  processSession,
  className,
}: WorkProcessProps): ReactElement | null {
  // 安全 Commentary 与公开 Reasoning 保留为正文；空摘要不创建占位行，Tool 继续承担可操作步骤身份。
  const visibleApprovalIds = new Set(approvals.map((approval) => approval.approvalId));
  const visibleSteps = steps.filter((step) => {
    // 有权威 ApprovalCard 时，approval Item 只重复标题和状态，动作与身份由卡片承载。
    if (
      step.kind === "approval" &&
      step.metadata?.approvalId !== undefined &&
      visibleApprovalIds.has(step.metadata.approvalId)
    ) {
      return false;
    }
    return (step.kind !== "commentary" && !isReasoningItem(step)) || stepDetail(step) !== undefined;
  });
  const actionableSteps = visibleSteps.filter(
    (step) => step.kind !== "commentary" && !isReasoningItem(step),
  );
  const approvalPending =
    turn?.status !== "suspended" &&
    approvals.some(
      (approval) =>
        approvalDecisions[approval.approvalId] === undefined &&
        approvalClosedAt[approval.approvalId] === undefined,
    );
  const state = processState(visibleSteps, turn, approvalPending);
  const [manualOpen, setManualOpen] = useState<boolean>();
  const rootRef = useRef<HTMLElement>(null);
  const [archivedDefaultOpen, setArchivedDefaultOpen] = useState<boolean>();
  const inline = displayMode === "inline";
  const archived = displayMode === "archived";
  // stop_process 成功执行的动作和取消中的退出码不代表命令失败；普通命令非零退出仍保留诊断。
  const failedStepCount = actionableSteps.filter((step) => {
    const presentation = step.metadata?.presentation;
    const nonZeroProcessExit =
      presentation?.exitCode !== undefined &&
      presentation.exitCode !== 0 &&
      presentation.status !== "cancelled" &&
      step.metadata?.toolName !== "stop_process";
    return step.status === "failed" || nonZeroProcessExit;
  }).length;

  useLayoutEffect(() => {
    if (!archived) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 切离归档需同步清除本地默认值，防止下一轮复用旧阅读决定。
      setArchivedDefaultOpen(undefined);
      return;
    }
    setArchivedDefaultOpen(
      (previous) =>
        previous ??
        (state !== "completed" ||
          !autoCollapse ||
          failedStepCount > 0 ||
          processReadingIsActive(rootRef.current)),
    );
  }, [archived, autoCollapse, failedStepCount, state]);

  if (
    visibleSteps.length === 0 &&
    approvals.length === 0 &&
    (turn === undefined || turn.status === "completed")
  ) {
    return null;
  }

  // Call 只在 Turn 内唯一；continuation 会合并多个 Turn 的步骤，因此审批位置必须使用复合身份。
  const stepIndexByCallId = new Map(
    visibleSteps.flatMap((step, index) =>
      step.metadata?.callId === undefined
        ? []
        : [[JSON.stringify([step.threadId, step.turnId, step.metadata.callId]), index] as const],
    ),
  );
  const presentationByCall = new Map<string, ToolPresentation>(
    visibleSteps.flatMap((step) =>
      step.metadata?.callId === undefined || step.metadata.presentation === undefined
        ? []
        : [
            [
              JSON.stringify([step.threadId, step.turnId, step.metadata.callId]),
              step.metadata.presentation,
            ] as const,
          ],
    ),
  );
  const orderedApprovals = [...approvals].sort(
    (left, right) =>
      (stepIndexByCallId.get(JSON.stringify([left.threadId, left.turnId, left.callId])) ??
        Number.MAX_SAFE_INTEGER) -
      (stepIndexByCallId.get(JSON.stringify([right.threadId, right.turnId, right.callId])) ??
        Number.MAX_SAFE_INTEGER),
  );
  // 未决审批只有恢复到 WAITING_APPROVAL 后才重新开放；Suspended 的唯一动作是继续或取消。
  const visibleApprovals = orderedApprovals.filter(
    (approval) =>
      approvalDecisions[approval.approvalId] !== undefined ||
      approvalClosedAt[approval.approvalId] !== undefined ||
      turn === undefined ||
      turn.status === "waiting_approval",
  );

  // 任一次续答缺少起止时间就省略总时长，避免把部分测量冒充整个过程；工具耗时可能并行，不能代替 Turn。
  const turnDurations = (relatedTurns ?? [turn]).map(turnDurationMs);
  const durationMs =
    turn === undefined
      ? sumOptional(visibleSteps.map(itemDurationMs))
      : turnDurations.some((value) => value === undefined)
        ? undefined
        : sumOptional(turnDurations);
  const duration = formatDuration(durationMs);
  const toolCallCount = countCommittedConversationActivity(
    visibleSteps.filter((step) => step.metadata?.presentation?.kind !== "context"),
  ).toolCalls;
  const cachedOpen =
    disclosureCache !== undefined && disclosureThreadId !== undefined && disclosureKey !== undefined
      ? disclosureCache.get(disclosureThreadId, "process", disclosureKey)
      : undefined;
  // 归档阶段仅在跟随最新内容且用户未阅读过程时默认收起；其余状态仍以可见性优先。
  const open = inline
    ? true
    : (cachedOpen ??
      manualOpen ??
      (archived
        ? (archivedDefaultOpen ?? (state !== "completed" || !autoCollapse))
        : state !== "completed" || approvalPending || failedStepCount > 0));
  const statusLabel = processStatusLabel(state);
  const recoveredFailureCount = state === "completed" ? failedStepCount : 0;
  const hasDetails = visibleSteps.length > 0 || visibleApprovals.length > 0;
  const completedCollapsed = state === "completed" && !open;
  const headingLabel = archived
    ? open
      ? "收起工作过程"
      : "查看工作过程"
    : completedCollapsed
      ? "查看工作过程"
      : "工作过程";
  // 只显示有依据的统计；公开进展、推理和审批不算工具调用，展开与折叠保留同一摘要。
  const archiveSummary = [
    duration === undefined ? undefined : `工作 ${duration}`,
    toolCallCount > 0 ? `调用工具 ${toolCallCount} 次` : undefined,
    recoveredFailureCount > 0 ? `${recoveredFailureCount} 步失败` : undefined,
  ].filter((label): label is string => label !== undefined);
  const accessibleSummary = archived
    ? [headingLabel, ...archiveSummary].join("，")
    : [
        headingLabel,
        statusLabel,
        recoveredFailureCount > 0 ? `${recoveredFailureCount} 步失败` : undefined,
        duration,
        actionableSteps.length > 0 ? `${actionableSteps.length} 步` : undefined,
      ]
        .filter((label): label is string => label !== undefined)
        .join("，");
  /** 将用户选择同时写入当前挂载与 Workspace 缓存，避免受控折叠等待下一次外部渲染才响应。 */
  const updateOpen = (nextOpen: boolean): void => {
    const content = rootRef.current?.querySelector<HTMLElement>(".ja-work-process__content");
    const activeElement = typeof document === "undefined" ? null : document.activeElement;
    const handOffFocus =
      !nextOpen &&
      activeElement instanceof HTMLElement &&
      content !== null &&
      content !== undefined &&
      content.contains(activeElement);
    if (
      disclosureCache !== undefined &&
      disclosureThreadId !== undefined &&
      disclosureKey !== undefined
    ) {
      disclosureCache.set(disclosureThreadId, "process", disclosureKey, nextOpen);
    }
    setManualOpen(nextOpen);
    if (handOffFocus) {
      // Radix 会保留关闭动画中的子树；主动交还焦点，避免键盘焦点留在即将隐藏的详情内。
      queueMicrotask(() => {
        rootRef.current?.querySelector<HTMLButtonElement>(".ja-work-process__trigger")?.focus();
      });
    }
  };

  const headerContent = archived ? (
    <span className="ja-work-process__heading ja-work-process__heading--archive">
      <span className="ja-work-process__archive-label">
        <strong>{headingLabel}</strong>
        <ChevronDown aria-hidden="true" className="ja-work-process__chevron" />
      </span>
      {archiveSummary.map((summary) => (
        <span className="ja-work-process__archive-summary" key={summary}>
          <span aria-hidden="true">·</span>
          {summary}
        </span>
      ))}
    </span>
  ) : (
    <>
      <span className="ja-work-process__heading">
        <strong>{headingLabel}</strong>
      </span>
      <span className="ja-work-process__meta">
        {completedCollapsed ? null : (
          <span className="ja-work-process__summary" aria-live="polite">
            {statusLabel}
          </span>
        )}
        {recoveredFailureCount > 0 ? (
          <span className="ja-work-process__failure">
            <span className="ja-work-process__separator" aria-hidden="true">
              ·
            </span>
            <span className="ja-work-process__failure-label">{recoveredFailureCount} 步失败</span>
          </span>
        ) : null}
        {duration ? (
          <span className="ja-work-process__duration">
            <span className="ja-work-process__separator" aria-hidden="true">
              ·
            </span>
            {duration}
          </span>
        ) : null}
        {actionableSteps.length > 0 ? (
          <span className="ja-work-process__step-count">
            <span className="ja-work-process__separator" aria-hidden="true">
              ·
            </span>
            {actionableSteps.length} 步
          </span>
        ) : null}
        {hasDetails ? (
          <ChevronDown aria-hidden="true" className="ja-work-process__chevron" />
        ) : null}
      </span>
    </>
  );

  const turnStatusByIdentity = new Map(
    [...(relatedTurns ?? []), ...(turn === undefined ? [] : [turn])].map(
      (candidate) =>
        [JSON.stringify([candidate.threadId, candidate.turnId]), candidate.status] as const,
    ),
  );
  /**
   * 公开正文从 Draft 结算为持久 Item 时服务端 identity 会变化；语义位置才是同一可见段落的稳定身份。
   * Tool 与审批仍使用服务端 ID，避免同一批次的可操作条目因位置变化错误复用 DOM。
   */
  const visibleStepKey = (step: WorkStepAdapter, index: number): string =>
    step.kind === "commentary" || isReasoningItem(step)
      ? `${step.turnId}:${step.metadata?.phase ?? step.kind}:${index}`
      : step.itemId;

  const processContent = (
    <>
      {visibleSteps.length > 0 ? (
        <ol className="ja-work-process__steps">
          {visibleSteps.map((step, index) => {
            const displayStep = projectWorkStepForDisplay(step);
            const detail = stepDetail(displayStep);
            if (isReasoningItem(displayStep) && detail !== undefined) {
              return (
                <li
                  key={visibleStepKey(displayStep, index)}
                  className="ja-work-step--reasoning"
                  data-role="reasoning"
                  aria-label="模型思考"
                >
                  <PublicTextStep
                    step={displayStep}
                    detail={detail}
                    onReadMessageContent={onReadMessageContent}
                    onOpenLink={onOpenLink}
                    onOpenFile={onOpenFile}
                    onCopyText={onCopyText}
                  />
                </li>
              );
            }
            if (displayStep.kind === "commentary" && detail !== undefined) {
              return (
                <li
                  key={visibleStepKey(displayStep, index)}
                  className="ja-work-step--commentary"
                  data-role="commentary"
                  data-retry-status={
                    step.metadata?.phase === "assistant_retry" ? "true" : undefined
                  }
                  aria-label="助手进展"
                >
                  <PublicTextStep
                    step={displayStep}
                    detail={detail}
                    onReadMessageContent={onReadMessageContent}
                    onOpenLink={onOpenLink}
                    onOpenFile={onOpenFile}
                    onCopyText={onCopyText}
                  />
                </li>
              );
            }
            // ToolPresentation 已在详情中显示自身耗时；通用步骤耗时只服务 commentary 等非 Tool 项，避免重复事实。
            const stepDuration =
              displayStep.metadata?.presentation === undefined
                ? formatDuration(itemDurationMs(displayStep))
                : undefined;
            const hasPresentation = displayStep.metadata?.presentation !== undefined;
            return (
              <li key={displayStep.itemId} className={cn("ja-work-step", `ja-work-step-${displayStep.status}`)}>
                <span className="ja-work-step__icon" aria-hidden="true">
                  <StepIcon step={displayStep} />
                </span>
                <div className="ja-work-step__body">
                  {hasPresentation ? null : (
                    <div className="ja-work-step__header">
                      <strong>{workStepLabel(displayStep)}</strong>
                      <span>{presentedStepStatusLabel(displayStep)}</span>
                    </div>
                  )}
                  {!hasPresentation && stepDetail(displayStep) ? (
                    <MarkdownMessage
                      content={stepDetail(displayStep) ?? ""}
                      className="ja-work-step__detail"
                      onOpenLink={onOpenLink}
                      onOpenFile={onOpenFile}
                      onCopyText={onCopyText}
                    />
                  ) : null}
                  {step.metadata?.presentation === undefined ? null : (
                    <ToolStepDetails
                      step={displayStep}
                      owningTurnAwaitingInput={
                        step.origin === "native" &&
                        turnStatusByIdentity.get(JSON.stringify([step.threadId, step.turnId])) ===
                        "waiting_input"
                      }
                      disclosureCache={disclosureCache}
                      disclosureKey={`${disclosureKey ?? step.turnId}:${step.itemId}`}
                      disclosureThreadId={disclosureThreadId ?? step.threadId}
                      onReadArtifact={onReadToolArtifact}
                      onOpenAttachmentPreview={onOpenAttachmentPreview}
                      recoveryThreadRevision={step.origin === "native" ? turn?.threadRevision : undefined}
                      onResolveRecovery={step.origin === "native" ? onResolveToolRecovery : undefined}
                      processSession={step.origin === "native" ? processSession : undefined}
                    />
                  )}
                  {stepDuration ? (
                    <span className="ja-work-step__duration">
                      <Clock3 aria-hidden="true" />
                      {stepDuration}
                    </span>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ol>
      ) : null}
      {visibleApprovals.map((approval) => (
        <ApprovalCard
          key={approval.approvalId}
          approval={approval}
          presentation={presentationByCall.get(
            JSON.stringify([approval.threadId, approval.turnId, approval.callId]),
          )}
          resolvedDecision={approvalDecisions[approval.approvalId]}
          closedAt={approvalClosedAt[approval.approvalId]}
          onResolve={
            onApprovalDecision === undefined
              ? undefined
              : (decision) => onApprovalDecision(approval, decision)
          }
        />
      ))}
    </>
  );

  if (inline && !hasDetails) return null;

  return (
    <section
      className={cn(
        "ja-work-process",
        `ja-work-process-${state}`,
        inline && "ja-work-process--inline",
        archived && "ja-work-process--archived",
        className,
      )}
      aria-label="工作过程"
      data-state={state}
      ref={rootRef}
    >
      {inline ? (
        <div className="ja-work-process__content ja-work-process__content--inline">
          {processContent}
        </div>
      ) : (
        <Collapsible open={open} onOpenChange={updateOpen}>
          {hasDetails ? (
            <CollapsibleTrigger
              className={cn(
                "ja-work-process__trigger",
                archived && "ja-work-process__trigger--archive",
              )}
              aria-label={accessibleSummary}
            >
              {headerContent}
            </CollapsibleTrigger>
          ) : (
            <div
              className="ja-work-process__trigger ja-work-process__trigger--static"
              role="status"
              aria-label={accessibleSummary}
            >
              {headerContent}
            </div>
          )}
          {hasDetails ? (
            <CollapsibleContent className="ja-work-process__content">
              {processContent}
            </CollapsibleContent>
          ) : null}
        </Collapsible>
      )}
    </section>
  );
}
