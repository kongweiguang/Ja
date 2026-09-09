// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { ChevronDown, Clock3, Files, ShieldAlert, Terminal, Wrench } from "lucide-react";
import { useState, type ReactElement } from "react";
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
  itemDurationMs,
  turnDurationMs,
  workStepLabel,
  type WorkStepAdapter,
  type TimelineTurn,
} from "../../domain/timelineTypes";
import { MarkdownMessage } from "./MarkdownMessage";
import { ToolStepDetails } from "./ToolStepDetails";
import "./timeline.css";

export interface WorkProcessProps {
  steps: readonly WorkStepAdapter[];
  turn?: TimelineTurn;
  approvals?: readonly ApprovalSummary[];
  approvalDecisions?: Readonly<Record<string, ApprovalDecision | undefined>>;
  approvalClosedAt?: Readonly<Record<string, string | undefined>>;
  onApprovalDecision?: (
    approval: ApprovalSummary,
    decision: UserApprovalDecision,
  ) => void | Promise<void>;
  onOpenLink?: (url: string) => void | Promise<void>;
  onCopyText?: (text: string) => Promise<void>;
  onReadToolArtifact?: (input: {
    threadId: string;
    turnId: string;
    callId: string;
    artifactId: string;
  }) => Promise<string>;
  className?: string;
}

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
      return "运行被中断";
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
 * Detail 只渲染 Commentary Text 与显式 Item Summary。Item Kind 已在 Protocol 边界列入 Allowlist，
 * 因此 Hidden Reasoning Record 不能借由兜底展示通道进入组件。
 */
function stepDetail(step: WorkStepAdapter): string | undefined {
  const summary = step.summary?.trim();
  // Tool/Command/File 文本可能含 Path、Argument 或 Provider Output；
  // 只有 Commentary Text 与显式 Adapter Summary 可以安全渲染。
  const text = summary || (step.kind === "commentary" ? step.text?.trim() : undefined);
  if (!text) {
    return undefined;
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
 * 将相邻 Tool Work 分组到单个 Radix Disclosure，使繁忙 Turn 保持可读；
 * Active 与 Failed Work 保持展开，Completed Work 默认折叠。
 *
 * 未决审批是继续执行的阻塞点，即使关联 command 已完成也必须保持展开，
 * 否则用户会看不到唯一可解除阻塞的审批按钮。
 * 标题左侧只保留过程名称，状态、局部失败、耗时与步骤数集中在右侧，避免重复图标和数字徽标抢占内容层级。
 */
export function WorkProcess({
  steps,
  turn,
  approvals = [],
  approvalDecisions = {},
  approvalClosedAt = {},
  onApprovalDecision,
  onOpenLink,
  onCopyText,
  onReadToolArtifact,
  className,
}: WorkProcessProps): ReactElement | null {
  // 安全 Commentary 保留为无标签叙事；空摘要不创建占位行，Tool 继续承担可操作步骤身份。
  const visibleSteps = steps.filter(
    (step) => step.kind !== "commentary" || stepDetail(step) !== undefined,
  );
  const actionableSteps = visibleSteps.filter((step) => step.kind !== "commentary");
  const approvalPending =
    turn?.status !== "suspended" &&
    approvals.some(
      (approval) =>
        approvalDecisions[approval.approvalId] === undefined &&
        approvalClosedAt[approval.approvalId] === undefined,
    );
  const state = processState(visibleSteps, turn, approvalPending);
  const [manualDisclosure, setManualDisclosure] = useState<{
    state: WorkProcessState;
    open: boolean;
  }>();
  // 手动选择只属于同一 Status；Running Process 转为 Completed 时，状态变化自然将其重置为折叠。
  const open =
    manualDisclosure?.state === state
      ? manualDisclosure.open
      : state !== "completed" || approvalPending;

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
  const statusLabel = processStatusLabel(state);
  const recoveredFailureCount = state === "completed" ? failedStepCount : 0;
  const hasDetails = visibleSteps.length > 0 || visibleApprovals.length > 0;
  const accessibleSummary = [
    "工作过程",
    statusLabel,
    recoveredFailureCount > 0 ? `${recoveredFailureCount} 步失败` : undefined,
    duration,
    actionableSteps.length > 0 ? `${actionableSteps.length} 步` : undefined,
  ]
    .filter((label): label is string => label !== undefined)
    .join("，");
  const headerContent = (
    <>
      <span className="ja-work-process__heading">
        <strong>工作过程</strong>
      </span>
      <span className="ja-work-process__meta">
        <span className="ja-work-process__summary" aria-live="polite">
          {statusLabel}
        </span>
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

  return (
    <section
      className={cn("ja-work-process", `ja-work-process-${state}`, className)}
      aria-label="工作过程"
      data-state={state}
    >
      <Collapsible
        open={open}
        onOpenChange={(nextOpen) => setManualDisclosure({ state, open: nextOpen })}
      >
        {hasDetails ? (
          <CollapsibleTrigger className="ja-work-process__trigger" aria-label={accessibleSummary}>
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
            {visibleSteps.length > 0 ? (
              <ol className="ja-work-process__steps">
                {visibleSteps.map((step) => {
                  const detail = stepDetail(step);
                  if (step.kind === "commentary" && detail !== undefined) {
                    return (
                      <li key={step.itemId} className="ja-work-step--commentary">
                        <MarkdownMessage
                          content={detail}
                          onOpenLink={onOpenLink}
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
                    <li
                      key={step.itemId}
                      className={cn("ja-work-step", `ja-work-step-${step.status}`)}
                    >
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
                            onCopyText={onCopyText}
                          />
                        ) : null}
                        {step.metadata?.presentation === undefined ? null : (
                          <ToolStepDetails step={step} onReadArtifact={onReadToolArtifact} />
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
          </CollapsibleContent>
        ) : null}
      </Collapsible>
    </section>
  );
}
