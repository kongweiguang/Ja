// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useEffect, useRef } from "react";
import type { DesktopNotificationKind } from "@/api/tauri/notification";
import { useTimelineStore, type TimelineStore } from "@/features/conversation";

const TERMINAL_NOTIFICATION_KIND: Readonly<Partial<Record<string, DesktopNotificationKind>>> = {
  completed: "completed",
  failed: "failed",
};
const MAX_DEDUPE_IDENTITIES = 512;

export interface DesktopNotificationTrigger {
  identity: string;
  kind: DesktopNotificationKind;
}

type NotificationProjection = Pick<TimelineStore, "turns" | "approvalsById">;

/**
 * 只从 authoritative identity/status transition 派生通知；interrupted Turn 保持静默，
 * Approval detail 不进入结果，避免通知层成为第二个会话事实 owner。
 */
export function collectDesktopNotificationTriggers(
  previous: NotificationProjection,
  current: NotificationProjection,
): DesktopNotificationTrigger[] {
  const triggers: DesktopNotificationTrigger[] = [];
  for (const turn of Object.values(current.turns)) {
    const previousStatus = previous.turns[turn.turnId]?.status;
    const kind = TERMINAL_NOTIFICATION_KIND[turn.status];
    if (kind !== undefined && previousStatus !== turn.status) {
      triggers.push({ identity: `turn:${turn.turnId}`, kind });
    }
  }
  for (const [approvalId, projection] of Object.entries(current.approvalsById)) {
    const wasPending =
      previous.approvalsById[approvalId]?.approval !== undefined &&
      previous.approvalsById[approvalId]?.decision === undefined;
    const isPending = projection.approval !== undefined && projection.decision === undefined;
    if (isPending && !wasPending) {
      triggers.push({ identity: `approval:${approvalId}`, kind: "approval" });
    }
  }
  return triggers;
}

/**
 * 先检查 browser visibility，再读取 native focus；native state 读取失败时抑制通知，
 * 未知 host 不能生成未经请求的 alert。
 */
export async function shouldDeliverDesktopNotification(
  isWindowFocused: () => Promise<boolean>,
): Promise<boolean> {
  if (typeof document === "undefined") {
    return false;
  }
  if (document.visibilityState === "hidden") {
    return true;
  }
  try {
    return !(await isWindowFocused());
  } catch {
    return false;
  }
}

interface UseDesktopNotificationsOptions {
  enabled: boolean;
  notify: (kind: DesktopNotificationKind) => Promise<void>;
  isWindowFocused: () => Promise<boolean>;
}

/**
 * 只订阅一次 normalized timeline，并仅发送后台、opt-in 通知。bounded identity ledger
 * 防止重复 Turn/Approval alert，但不保存业务详情或成为另一 Runtime 事实源。
 */
export function useDesktopNotifications({
  enabled,
  notify,
  isWindowFocused,
}: UseDesktopNotificationsOptions): void {
  const enabledRef = useRef(enabled);
  const notifyRef = useRef(notify);
  const focusRef = useRef(isWindowFocused);
  const seenRef = useRef({ keys: new Set<string>(), order: [] as string[] });

  useEffect(() => {
    enabledRef.current = enabled;
    notifyRef.current = notify;
    focusRef.current = isWindowFocused;
  }, [enabled, isWindowFocused, notify]);

  useEffect(
    () =>
      useTimelineStore.subscribe((current, previous) => {
        if (!enabledRef.current) {
          return;
        }
        for (const trigger of collectDesktopNotificationTriggers(previous, current)) {
          const seen = seenRef.current;
          if (seen.keys.has(trigger.identity)) {
            continue;
          }
          seen.keys.add(trigger.identity);
          seen.order.push(trigger.identity);
          while (seen.order.length > MAX_DEDUPE_IDENTITIES) {
            const expired = seen.order.shift();
            if (expired !== undefined) {
              seen.keys.delete(expired);
            }
          }
          void shouldDeliverDesktopNotification(focusRef.current)
            .then((deliver) =>
              deliver && enabledRef.current ? notifyRef.current(trigger.kind) : undefined,
            )
            .catch(() => undefined);
        }
      }),
    [],
  );
}
