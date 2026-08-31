// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { open } from "@tauri-apps/plugin-dialog";

/**
 * 将原生 dialog 的宽返回类型投影为 Ja 的单目录契约；数组直接拒绝，
 * 不静默选择用户未明确指定为 workspace root 的路径。
 */
export async function pickDirectory(): Promise<string | null> {
  const selected = await open({ directory: true, multiple: false });
  return typeof selected === "string" ? selected : null;
}
