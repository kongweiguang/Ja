// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { openUrl } from "@tauri-apps/plugin-opener";
import { recordUiDiagnostic } from "./diagnostics";
import { invokeNativeCommand } from "./nativeInvoke";

const MAX_EXTERNAL_URL_LENGTH = 4_096;

/** 不依赖控制字符正则检测 ASCII control character，使 URL 边界显式且符合 lint 策略。 */
function hasAsciiControlCharacter(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0);
    if (codePoint !== undefined && (codePoint <= 0x1f || codePoint === 0x7f)) {
      return true;
    }
  }
  return false;
}

/** 只规范化绝对 HTTP(S) URL；IPC 前拒绝 user info 与控制字符，
 * 即使未来 renderer 漏做链接校验，凭据和自定义协议也不能到达 system opener。 */
export function normalizeExternalHttpUrl(value: string): string | undefined {
  const candidate = value.trim();
  if (
    candidate.length === 0 ||
    candidate.length > MAX_EXTERNAL_URL_LENGTH ||
    hasAsciiControlCharacter(candidate)
  ) {
    return undefined;
  }
  try {
    const url = new URL(candidate);
    if (
      (url.protocol !== "http:" && url.protocol !== "https:") ||
      url.username !== "" ||
      url.password !== ""
    ) {
      return undefined;
    }
    return url.toString();
  } catch {
    return undefined;
  }
}

/** 在系统默认浏览器打开已验证 Web URL；adapter 不暴露 `openPath`、文件 reveal、应用选择或自定义 scheme。 */
export async function openExternalHttpUrl(value: string): Promise<void> {
  const url = normalizeExternalHttpUrl(value);
  if (url === undefined) {
    throw new Error("external URL is not allowed");
  }
  try {
    await invokeNativeCommand("plugin:opener|open_url", { url }, () => openUrl(url));
  } catch (cause) {
    await recordUiDiagnostic("external_link_open_failed");
    throw cause;
  }
}
