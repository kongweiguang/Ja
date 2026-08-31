// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from "@tauri-apps/plugin-notification";
import { recordUiDiagnostic } from "./diagnostics";

export type DesktopNotificationKind = "completed" | "failed" | "approval";

const SAFE_NOTIFICATIONS: Readonly<
  Record<DesktopNotificationKind, Readonly<{ title: string; body: string }>>
> = {
  completed: { title: "Ja 已完成", body: "后台任务已完成。" },
  failed: { title: "Ja 任务失败", body: "后台任务未能完成，请返回 Ja 查看。" },
  approval: { title: "Ja 需要确认", body: "有一项操作等待你的确认。" },
};

/** 仅在用户明确启用桌面通知后请求 OS 权限；拒绝是正常结果，不自动打开系统设置。 */
export async function enableDesktopNotifications(): Promise<boolean> {
  try {
    if (await isPermissionGranted()) {
      return true;
    }
    return (await requestPermission()) === "granted";
  } catch (cause) {
    await recordUiDiagnostic("notification_permission_failed");
    throw cause;
  }
}

/** 重新检查权限后才投递封闭且不敏感的摘要，OS 撤权不能被当成永久缓存的授权。 */
export async function sendDesktopNotification(kind: DesktopNotificationKind): Promise<void> {
  try {
    if (!(await isPermissionGranted())) {
      return;
    }
    await Promise.resolve(sendNotification(SAFE_NOTIFICATIONS[kind]));
  } catch (cause) {
    await recordUiDiagnostic("notification_delivery_failed");
    throw cause;
  }
}
