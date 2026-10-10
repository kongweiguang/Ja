// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useRef, useState, type ReactElement } from "react";
import { Notification } from "@/shared/ui/notifications";
import type { RuntimePendingOperation } from "../application/runtimePorts";
import { useRuntimeLifecycle } from "../application/runtimeContexts";

interface PendingOperationNotificationProps {
  readonly scope: string;
  readonly pending?: RuntimePendingOperation;
  /** 对话框覆盖工作面时保留 owner 但隐藏提示，关闭后沿用原有 toast identity。 */
  readonly enabled?: boolean;
}

/**
 * 本机解除阻挡必须先核查再确认；状态跟随会话挂载，不允许迟到结果回写另一会话。
 * 请求去重只限制 UI 动作，不重发原始请求；未知结果始终保留副作用提醒。
 * 覆盖对话框时仅停用共享提示 owner，保留本地确认状态以便关闭后无损恢复。
 * @author kongweiguang
 */
export function PendingOperationNotification({
  scope,
  pending,
  enabled = true,
}: PendingOperationNotificationProps): ReactElement | null {
  const { recheckPendingOperations, acknowledgePendingOperation } = useRuntimeLifecycle();
  const [checkedId, setCheckedId] = useState<string>();
  const [confirmId, setConfirmId] = useState<string>();
  const [action, setAction] = useState<"check" | "acknowledge">();
  const [error, setError] = useState<string>();
  const [acknowledged, setAcknowledged] = useState(false);
  const inFlight = useRef(false);
  const mounted = useRef(false);

  /** 异步动作可以继续由 Runtime owner 结算，但卸载后不得恢复已离开会话的局部反馈。 */
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  /** 两个确认阶段只修改本机门禁，失败原地保留重试入口且不掩盖核查错误。 */
  const perform = async (nextAction: "check" | "acknowledge"): Promise<void> => {
    if (pending === undefined || inFlight.current) return;
    inFlight.current = true;
    setAction(nextAction);
    setError(undefined);
    try {
      if (nextAction === "check") {
        const remaining = await recheckPendingOperations();
        if (
          mounted.current &&
          remaining.some((record) => record.operationKey === pending.operationKey)
        )
          setCheckedId(pending.operationKey);
      } else {
        const outcome = await acknowledgePendingOperation(pending.operationKey);
        if (mounted.current) {
          setConfirmId(undefined);
          setAcknowledged(outcome.status === "unknown_acknowledged");
        }
      }
    } catch {
      if (mounted.current)
        setError(nextAction === "check" ? "重新检查失败，请重试。" : "解除阻挡失败，请重试。");
    } finally {
      inFlight.current = false;
      if (mounted.current) setAction(undefined);
    }
  };

  if (pending === undefined)
    return acknowledged ? (
      <Notification
        scope={scope}
        tone="warning"
        message="已解除本机阻挡；原操作是否执行仍未确认。请避免重复同一副作用。"
        enabled={enabled}
      />
    ) : null;

  return (
    <Notification
      scope={`${scope}:${pending.operationKey}`}
      tone="warning"
      dismissible={false}
      enabled={enabled}
      message={`上一次操作结果待核实，可能已产生副作用。${
        pending.threadId === null
          ? "审批可能属于其他会话，请检查相关会话。"
          : "请先查看当前会话状态。"
      }`}
    >
      <button type="button" disabled={action !== undefined} onClick={() => void perform("check")}>
        {action === "check" ? "正在检查…" : "重新检查"}
      </button>
      {checkedId === pending.operationKey && confirmId !== pending.operationKey ? (
        <button
          type="button"
          disabled={action !== undefined}
          onClick={() => setConfirmId(pending.operationKey)}
        >
          我已检查，允许新操作
        </button>
      ) : null}
      {confirmId === pending.operationKey ? (
        <>
          <span>原操作仍可能已经执行。确认后仅解除本机阻挡，不会重发原请求。</span>
          <button
            type="button"
            disabled={action !== undefined}
            onClick={() => void perform("acknowledge")}
          >
            {action === "acknowledge" ? "正在解除…" : "确认解除阻挡"}
          </button>
          <button
            type="button"
            disabled={action !== undefined}
            onClick={() => setConfirmId(undefined)}
          >
            取消
          </button>
        </>
      ) : null}
      {error === undefined ? null : <span role="alert">{error}</span>}
    </Notification>
  );
}
