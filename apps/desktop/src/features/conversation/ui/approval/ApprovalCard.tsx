// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { Check, CircleAlert, Clock3, ShieldAlert } from "lucide-react";
import { useEffect, useRef, useState, type ReactElement } from "react";
import type {
  ApprovalDecision,
  TimelineApproval as ApprovalSummary,
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
  onResolve?: (decision: UserApprovalDecision) => void | Promise<void>;
  className?: string;
}

type LocalStatus = "pending" | "submitting" | "resolved" | "error";

function decisionLabel(decision: ApprovalDecision): string {
  switch (decision) {
    case "approve":
      return "已批准";
    case "deny":
      return "已拒绝";
  }
}

/** 只显示 Kernel 批准公开的原因以及稳定 Tool/Call 关联，避免泄露原始 Payload。 */
export function ApprovalCard({
  approval,
  resolvedDecision,
  closedAt,
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
  const approvalIdRef = useRef(approval.approvalId);

  useEffect(() => {
    if (approvalIdRef.current === approval.approvalId) {
      return;
    }
    approvalIdRef.current = approval.approvalId;
    // 复用卡片时必须忘记上一次 Approval 的本地结果；Approval ID 是唯一稳定身份，
    // 只有它能保证 Timeline 投影原位变化后仍安全呈现 Pending 动作。
    setExpired(Number.isFinite(expiresAt) && expiresAt <= Date.now());
    setStatus(resolvedDecision === undefined ? "pending" : "resolved");
    setLocalDecision(undefined);
    setError(undefined);
    pendingRef.current = false;
  }, [approval.approvalId, expiresAt, resolvedDecision]);

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

  const isTerminal =
    expired || closedAt !== undefined || resolvedDecision !== undefined || status === "resolved";
  const isSubmitting = status === "submitting";
  const reasonText = approval.reason;

  /** 将三个按钮作为同一事务互斥保护，避免重复点击导致二次 Resolve。 */
  const resolve = async (decision: UserApprovalDecision): Promise<void> => {
    if (isTerminal || isSubmitting || pendingRef.current || onResolve === undefined) {
      return;
    }
    pendingRef.current = true;
    setStatus("submitting");
    setError(undefined);
    try {
      await onResolve(decision);
      setLocalDecision(decision);
      setStatus("resolved");
    } catch {
      setStatus("error");
      setError("提交失败，请重试。连接断开时请重新发起操作。");
    } finally {
      pendingRef.current = false;
    }
  };

  const displayedDecision =
    resolvedDecision ?? (expired ? "expired" : closedAt !== undefined ? "closed" : localDecision);

  return (
    <section
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
          ) : displayedDecision === "expired" || displayedDecision === "closed" ? (
            <CircleAlert />
          ) : (
            <Check />
          )}
        </span>
        <div>
          <h3 id={`ja-approval-${approval.approvalId}`}>工具调用需要确认</h3>
          <p>{reasonText}</p>
        </div>
      </div>
      <dl className="ja-approval-card__facts">
        <div>
          <dt>工具</dt>
          <dd>
            <code>{approval.toolName}</code>
          </dd>
        </div>
        <div>
          <dt>调用</dt>
          <dd>
            <code>{approval.callId}</code>
          </dd>
        </div>
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
      </dl>
      {displayedDecision !== undefined ? (
        <p className="ja-approval-card__result" role="status">
          {displayedDecision === "expired"
            ? "已过期"
            : displayedDecision === "closed"
              ? "Turn 已结束"
              : decisionLabel(displayedDecision)}
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
