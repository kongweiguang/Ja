// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { error } from "@tauri-apps/plugin-log";

/** 使用封闭诊断词汇；调用方不能附带 prompt、路径、stack trace、命令输出、凭据或任意异常消息。 */
export type UiDiagnosticCode =
  | "clipboard_write_failed"
  | "external_link_open_failed"
  | "notification_delivery_failed"
  | "notification_permission_failed"
  | "react_error_boundary";

const UI_DIAGNOSTIC_MESSAGES: Readonly<Record<UiDiagnosticCode, string>> = {
  clipboard_write_failed: "ui.clipboard_write_failed",
  external_link_open_failed: "ui.external_link_open_failed",
  notification_delivery_failed: "ui.notification_delivery_failed",
  notification_permission_failed: "ui.notification_permission_failed",
  react_error_boundary: "ui.react_error_boundary",
};

/** 通过官方 log plugin 的 tracing bridge 发送固定 code；诊断保持 best-effort，
 * 日志不得替代原始可见错误，也不能制造新的未处理 rejection。 */
export async function recordUiDiagnostic(code: UiDiagnosticCode): Promise<void> {
  try {
    await error(UI_DIAGNOSTIC_MESSAGES[code]);
  } catch {
    // 浏览器预览和启动早期可能没有原生 plugin；诊断缺席属于预期降级路径。
  }
}
