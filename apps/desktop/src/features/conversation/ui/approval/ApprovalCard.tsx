// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Check, CircleAlert, Clock3, ShieldAlert } from "lucide-react";
import { useEffect, useRef, useState, type ReactElement } from "react";
import type {
  ApprovalDecision,
  TimelineApproval as ApprovalSummary,
  ToolPresentation,
} from "../../domain/timelineTypes";
import { Button } from "@/shared/ui/primitives/Button";
import { cn } from "@/shared/ui/primitives/cn";
import "./approval.css";

export type { ApprovalDecision } from "../../domain/timelineTypes";
export type UserApprovalDecision = Extract<ApprovalDecision, "approve" | "deny">;

export interface ApprovalCardProps {
  approval: ApprovalSummary;
  /** 可选 Host 投影允许实时事件替换本地 Pending 状态，避免形成第二个事实来源。 */
  resolvedDecision?: ApprovalDecision;
  /** Terminal Turn 只关闭 Pending 请求，不能伪造用户响应。 */
  closedAt?: string;
  /** 与审批 call 精确关联的安全工具展示；缺失时只显示工具名和公开原因。 */
  presentation?: ToolPresentation;
  onResolve?: (decision: UserApprovalDecision) => void | Promise<void>;
  className?: string;
}

type LocalStatus = "pending" | "submitting" | "resolved" | "error";
type ApprovalCardDecision = ApprovalDecision | "expired" | "cancelled" | "superseded" | "closed";

/** 隐藏或已卸载的审批卡不能接收迟到决策的焦点。 */
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

/** 终态标题明确退出“需要确认”语义，避免已关闭卡片继续像待操作请求。 */
function decisionHeading(decision: ApprovalCardDecision | undefined): string {
  switch (decision) {
    case undefined:
      return "工具调用需要确认";
    case "approve":
      return "工具调用已批准";
    case "deny":
      return "工具调用已拒绝";
    case "expired":
      return "审批请求已过期";
    case "cancelled":
      return "审批已取消";
    case "superseded":
      return "审批请求已被替代";
    case "closed":
      return "审批请求已关闭";
  }
}

/** 终态状态文案与标题采用同一真实结果，便于扫描并由辅助技术播报。 */
function decisionLabel(decision: ApprovalCardDecision): string {
  switch (decision) {
    case "approve":
      return "已批准";
    case "deny":
      return "已拒绝";
    case "expired":
      return "已过期";
    case "cancelled":
      return "已取消";
    case "superseded":
      return "已被替代";
    case "closed":
      return "已关闭";
  }
}

/** 只展示受控 presentation 的命令或首个相对路径，不从审批参数自行拼出敏感目标。 */
function approvalTarget(presentation: ToolPresentation | undefined): string | undefined {
  const command = presentation?.kind === "shell" ? presentation.command?.trim() : undefined;
  if (command !== undefined && command !== "") return command;
  return presentation?.relativePaths.find((path) => path.trim() !== "")?.trim();
}

/** 只显示 Kernel 批准公开的原因以及稳定 Tool/Call 关联，避免泄露原始 Payload。 */
export function ApprovalCard({
  approval,
  resolvedDecision,
  closedAt,
  presentation,
  onResolve,
  className,
}: ApprovalCardProps): ReactElement {
  const expiresAt = Date.parse(approval.expiresAt);
  const [expired, setExpired] = useState(
    () => Number.isFinite(expiresAt) && expiresAt <= Date.now(),
  );
  const [status, setStatus] = useState<LocalStatus>(
    resolvedDecision === undefined ? "pending" : "resolved",
  );
  const [localDecision, setLocalDecision] = useState<UserApprovalDecision>();
  const [error, setError] = useState<string>();
  const pendingRef = useRef(false);
  const approvalIdentity = JSON.stringify([
    approval.threadId,
    approval.approvalId,
    approval.callId,
  ]);
  const currentApprovalIdentityRef = useRef(approvalIdentity);
  currentApprovalIdentityRef.current = approvalIdentity;
  const approvalIdentityRef = useRef(approvalIdentity);
  const focusReturnIdentityRef = useRef(approvalIdentity);
  const cardRef = useRef<HTMLElement | null>(null);
  const resultRef = useRef<HTMLParagraphElement | null>(null);
  const focusReturnPendingRef = useRef(false);
  const focusMovedRef = useRef(false);

  /** 审批等待期间记录用户是否离开卡片，异步结果不得抢走其它区域的焦点。 */
  useEffect(() => {
    const markFocusMoved = (target: EventTarget | null): void => {
      if (
        focusReturnPendingRef.current &&
        target instanceof Node &&
        !cardRef.current?.contains(target)
      ) {
        focusMovedRef.current = true;
      }
    };
    const trackFocus = (event: FocusEvent): void => markFocusMoved(event.target);
    const trackPointer = (event: PointerEvent): void => markFocusMoved(event.target);
    window.addEventListener("focusin", trackFocus);
    window.addEventListener("pointerdown", trackPointer, true);
    return () => {
      window.removeEventListener("focusin", trackFocus);
      window.removeEventListener("pointerdown", trackPointer, true);
    };
  }, []);

  useEffect(() => {
    if (approvalIdentityRef.current === approvalIdentity) {
      return;
    }
    approvalIdentityRef.current = approvalIdentity;
    // 复用卡片时按 Thread、Approval 与 Tool 调用整体更换身份，旧请求不得改写新卡片结果。
    setExpired(Number.isFinite(expiresAt) && expiresAt <= Date.now());
    setStatus(resolvedDecision === undefined ? "pending" : "resolved");
    setLocalDecision(undefined);
    setError(undefined);
    pendingRef.current = false;
    focusReturnPendingRef.current = false;
    focusMovedRef.current = false;
  }, [approvalIdentity, expiresAt, resolvedDecision]);

  useEffect(() => {
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      setExpired(true);
      return undefined;
    }
    // 浏览器会把超长 Delay 限制为有符号 32 位区间；跳过多年 Timer 可避免把有效的未来
    // Approval 变成立即过期，最终仍由 Host 发送权威 Resolved/Expired 事件。
    const delay = expiresAt - Date.now();
    if (delay > 2_147_483_647) {
      return undefined;
    }
    const timer = window.setTimeout(() => setExpired(true), delay);
    return () => window.clearTimeout(timer);
  }, [expiresAt]);

  useEffect(() => {
    if (resolvedDecision !== undefined) {
      setStatus("resolved");
      setLocalDecision(undefined);
      setError(undefined);
      pendingRef.current = false;
    }
  }, [resolvedDecision]);

  const displayedDecision: ApprovalCardDecision | undefined =
    resolvedDecision ??
    localDecision ??
    (approval.closedReason === "expired"
      ? "expired"
      : approval.closedReason === "cancelled"
        ? "cancelled"
        : approval.closedReason === "superseded"
          ? "superseded"
          : expired
            ? "expired"
            : closedAt !== undefined
              ? "closed"
              : undefined);
  const isTerminal = displayedDecision !== undefined || status === "resolved";
  const isSubmitting = status === "submitting";
  const reasonText = approval.reason;
  const operationLabel = presentation?.title.trim() || approval.toolName;
  const target = approvalTarget(presentation);

  /** 三个按钮共享一次 Resolve；结果只写回发起时的 Thread/Approval/Tool 调用身份。 */
  const resolve = async (decision: UserApprovalDecision): Promise<void> => {
    if (isTerminal || isSubmitting || pendingRef.current || onResolve === undefined) {
      return;
    }
    const decisionIdentity = currentApprovalIdentityRef.current;
    const active = document.activeElement;
    focusReturnPendingRef.current =
      active instanceof HTMLElement && cardRef.current?.contains(active) === true;
    focusReturnIdentityRef.current = decisionIdentity;
    focusMovedRef.current = false;
    pendingRef.current = true;
    setStatus("submitting");
    setError(undefined);
    try {
      await onResolve(decision);
      if (currentApprovalIdentityRef.current !== decisionIdentity) return;
      setLocalDecision(decision);
      setStatus("resolved");
    } catch {
      if (currentApprovalIdentityRef.current !== decisionIdentity) return;
      focusReturnPendingRef.current = false;
      setStatus("error");
      setError("提交失败，请重试。连接断开时请重新发起操作。");
    } finally {
      if (currentApprovalIdentityRef.current === decisionIdentity) {
        pendingRef.current = false;
      }
    }
  };

  /** 用户决策替换按钮后聚焦稳定结果；超时和运行时投影不继承旧焦点意图。 */
  useEffect(() => {
    if (status !== "resolved" || displayedDecision === undefined || !focusReturnPendingRef.current)
      return;
    focusReturnPendingRef.current = false;
    if (focusReturnIdentityRef.current !== currentApprovalIdentityRef.current) return;
    if (!focusMovedRef.current && canFocusVisibleElement(resultRef.current)) {
      resultRef.current.focus({ preventScroll: true });
    }
  }, [approvalIdentity, displayedDecision, status]);

  return (
    <section
      ref={cardRef}
      className={cn(
        "ja-approval-card",
        `ja-approval-card-${displayedDecision === undefined ? "pending" : displayedDecision}`,
        className,
      )}
      aria-labelledby={`ja-approval-${approval.approvalId}`}
    >
      <div className="ja-approval-card__heading">
        <span className="ja-approval-card__icon" aria-hidden="true">
          {displayedDecision === undefined ? (
            <ShieldAlert />
          ) : displayedDecision === "approve" ? (
            <Check />
          ) : (
            <CircleAlert />
          )}
        </span>
        <div>
          <h3 id={`ja-approval-${approval.approvalId}`}>{decisionHeading(displayedDecision)}</h3>
          {displayedDecision === undefined ? <p>{reasonText}</p> : null}
        </div>
      </div>
      <dl className="ja-approval-card__facts">
        <div>
          <dt>操作</dt>
          <dd>
            <code>{operationLabel}</code>
          </dd>
        </div>
        {target === undefined ? null : (
          <div>
            <dt>{presentation?.kind === "shell" ? "命令" : "目标"}</dt>
            <dd>
              <code className="ja-approval-card__target" title={target}>
                {target}
              </code>
            </dd>
          </div>
        )}
        {displayedDecision === undefined ? (
          <div>
            <dt>有效期</dt>
            <dd>
              <Clock3 aria-hidden="true" />
              {new Date(approval.expiresAt).toLocaleTimeString([], {
                hour: "2-digit",
                minute: "2-digit",
              })}
            </dd>
          </div>
        ) : null}
      </dl>
      {displayedDecision !== undefined ? (
        <p ref={resultRef} className="ja-approval-card__result" role="status" tabIndex={-1}>
          {decisionLabel(displayedDecision)}
        </p>
      ) : (
        <div className="ja-approval-card__actions" aria-label="审批决策">
          <Button
            type="button"
            variant="primary"
            size="sm"
            disabled={isSubmitting || onResolve === undefined}
            loading={isSubmitting}
            onClick={() => void resolve("approve")}
          >
            批准
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={isSubmitting || onResolve === undefined}
            onClick={() => void resolve("deny")}
          >
            拒绝
          </Button>
        </div>
      )}
      {error ? (
        <p className="ja-approval-card__error" role="alert">
          {error}
        </p>
      ) : null}
    </section>
  );
}
