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
  type WorkStepAdapter,
  type TimelineTurn,
} from "../../domain/timelineTypes";
import { MarkdownMessage, type MarkdownFileTarget } from "./MarkdownMessage";
import { ToolStepDetails } from "./ToolStepDetails";
import type { TimelineDisclosureCache } from "./timelineDisclosure";
import "./timeline.css";

export interface WorkProcessProps {
  steps: readonly WorkStepAdapter[];
  turn?: TimelineTurn;
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
    mode?: "explorer",
  ) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  onResolveToolRecovery?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    expectedThreadRevision: number;
    expectedRecoveryRevision: number;
    decision: "retry" | "skip";
    idempotencyKey: string;
  }) => Promise<unknown>;
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
 * reasoning 已由 App Server 做有界投影，不能在这里再次截断，否则用户展开后仍会静默丢正文。
 */
function stepDetail(step: WorkStepAdapter): string | undefined {
  const summary = step.summary?.trim();
  // Tool/Command/File 文本可能含 Path、Argument 或 Provider Output；
  // 只有公开 Commentary/Reasoning Text 与显式 Adapter Summary 可以安全渲染。
  const text =
    summary ||
    (step.kind === "commentary" || isReasoningItem(step) ? step.text?.trim() : undefined);
  if (!text) {
    return undefined;
  }
  if (isReasoningItem(step)) {
    return text;
  }
  return text.length > 2_048 ? `${text.slice(0, 2_048)}…` : text;
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
 * 归档入口只保留处理概览，重试只替换轻状态，避免重复标题和失败正文干扰统一工作状态。
 */
export function WorkProcess({
  steps,
  turn,
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
  onReadToolArtifact,
  onResolveToolRecovery,
  className,
}: WorkProcessProps): ReactElement | null {
  // 安全 Commentary 与公开 Reasoning 保留为正文；空摘要不创建占位行，Tool 继续承担可操作步骤身份。
  const visibleSteps = steps.filter(
    (step) =>
      (step.kind !== "commentary" && !isReasoningItem(step)) || stepDetail(step) !== undefined,
  );
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

  useLayoutEffect(() => {
    if (!archived) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- 切离归档需同步清除本地默认值，防止下一轮复用旧阅读决定。
      setArchivedDefaultOpen(undefined);
      return;
    }
    setArchivedDefaultOpen(
      (previous) =>
        previous ??
        (state !== "completed" || !autoCollapse || processReadingIsActive(rootRef.current)),
    );
  }, [archived, autoCollapse, state]);

  if (
    visibleSteps.length === 0 &&
    approvals.length === 0 &&
    (turn === undefined || turn.status === "completed")
  ) {
    return null;
  }

  // Call 关联把卡片排在对应 Prepared Tool 旁，即使 Approval Event 明确不携带展示 Item Identity。
  const stepIndexByCallId = new Map(
    visibleSteps.flatMap((step, index) =>
      step.metadata?.callId === undefined ? [] : [[step.metadata.callId, index] as const],
    ),
  );
  const orderedApprovals = [...approvals].sort(
    (left, right) =>
      (stepIndexByCallId.get(left.callId) ?? Number.MAX_SAFE_INTEGER) -
      (stepIndexByCallId.get(right.callId) ?? Number.MAX_SAFE_INTEGER),
  );
  // 未决审批只有恢复到 WAITING_APPROVAL 后才重新开放；Suspended 的唯一动作是继续或取消。
  const visibleApprovals = orderedApprovals.filter(
    (approval) =>
      approvalDecisions[approval.approvalId] !== undefined ||
      approvalClosedAt[approval.approvalId] !== undefined ||
      turn === undefined ||
      turn.status === "waiting_approval",
  );

  // Turn 存在时只信任它的权威起止时间；仅无 Turn 的降级投影允许汇总步骤耗时。
  const durationMs =
    turn === undefined ? sumOptional(visibleSteps.map(itemDurationMs)) : turnDurationMs(turn);
  const duration = formatDuration(durationMs);
  const failedStepCount = actionableSteps.filter((step) => step.status === "failed").length;
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
  // 成功收口不重复步骤数量或状态；只有已恢复的 Tool 失败需要在折叠态保留可见提醒。
  const archiveSummary = recoveredFailureCount > 0 ? [`${recoveredFailureCount} 步失败`] : [];
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
      <strong>{headingLabel}</strong>
      <ChevronDown aria-hidden="true" className="ja-work-process__chevron" />
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
            const detail = stepDetail(step);
            if (isReasoningItem(step) && detail !== undefined) {
              return (
                <li
                  key={visibleStepKey(step, index)}
                  className="ja-work-step--reasoning"
                  data-role="reasoning"
                  aria-label="模型思考"
                >
                  <MarkdownMessage
                    content={detail}
                    onOpenLink={onOpenLink}
                    onOpenFile={onOpenFile}
                    onCopyText={onCopyText}
                  />
                </li>
              );
            }
            if (step.kind === "commentary" && detail !== undefined) {
              return (
                <li
                  key={visibleStepKey(step, index)}
                  className="ja-work-step--commentary"
                  data-role="commentary"
                  data-retry-status={
                    step.metadata?.phase === "assistant_retry" ? "true" : undefined
                  }
                  aria-label="助手进展"
                >
                  <MarkdownMessage
                    content={detail}
                    onOpenLink={onOpenLink}
                    onOpenFile={onOpenFile}
                    onCopyText={onCopyText}
                  />
                </li>
              );
            }
            // ToolPresentation 已在详情中显示自身耗时；通用步骤耗时只服务 commentary 等非 Tool 项，避免重复事实。
            const stepDuration =
              step.metadata?.presentation === undefined
                ? formatDuration(itemDurationMs(step))
                : undefined;
            const hasPresentation = step.metadata?.presentation !== undefined;
            return (
              <li key={step.itemId} className={cn("ja-work-step", `ja-work-step-${step.status}`)}>
                <span className="ja-work-step__icon" aria-hidden="true">
                  <StepIcon step={step} />
                </span>
                <div className="ja-work-step__body">
                  {hasPresentation ? null : (
                    <div className="ja-work-step__header">
                      <strong>{workStepLabel(step)}</strong>
                      <span>{presentedStepStatusLabel(step)}</span>
                    </div>
                  )}
                  {!hasPresentation && stepDetail(step) ? (
                    <MarkdownMessage
                      content={stepDetail(step) ?? ""}
                      className="ja-work-step__detail"
                      onOpenLink={onOpenLink}
                      onOpenFile={onOpenFile}
                      onCopyText={onCopyText}
                    />
                  ) : null}
                  {step.metadata?.presentation === undefined ? null : (
                    <ToolStepDetails
                      step={step}
                      disclosureCache={disclosureCache}
                      disclosureKey={`${disclosureKey ?? step.turnId}:${step.itemId}`}
                      disclosureThreadId={disclosureThreadId ?? step.threadId}
                      onReadArtifact={onReadToolArtifact}
                      recoveryThreadRevision={turn?.threadRevision}
                      onResolveRecovery={onResolveToolRecovery}
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
