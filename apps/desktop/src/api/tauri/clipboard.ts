// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { recordUiDiagnostic } from "./diagnostics";

const MAX_CLIPBOARD_TEXT_BYTES = 4_194_304;

/** 仅在用户明确操作后写入有界 UTF-8 文本；Ja 不引入读取、清空、图片或剪贴板观察 API。 */
export async function writeClipboardText(value: string): Promise<void> {
  if (new TextEncoder().encode(value).byteLength > MAX_CLIPBOARD_TEXT_BYTES) {
    throw new Error("clipboard text exceeds the supported size");
  }
  try {
    await writeText(value);
  } catch (cause) {
    await recordUiDiagnostic("clipboard_write_failed");
    throw cause;
  }
}
