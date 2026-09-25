// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { readText, writeText } from "@tauri-apps/plugin-clipboard-manager";
import { recordUiDiagnostic } from "./diagnostics";

const MAX_TERMINAL_CLIPBOARD_TEXT_BYTES = 64 * 1024;

/** 只在终端粘贴动作已触发后读取一次有界 UTF-8 文本，不观察或清空系统剪贴板。 */
export async function readClipboardText(): Promise<string> {
  const value = await readText();
  if (new TextEncoder().encode(value).byteLength > MAX_TERMINAL_CLIPBOARD_TEXT_BYTES) {
    throw new RangeError("clipboard text exceeds the 64 KiB terminal paste limit");
  }
  return value;
}

/** 仅在用户明确操作后写入正文；长度由系统剪贴板处理，不截断用户已读取的完整回复。 */
export async function writeClipboardText(value: string): Promise<void> {
  try {
    await writeText(value);
  } catch (cause) {
    await recordUiDiagnostic("clipboard_write_failed");
    throw cause;
  }
}
