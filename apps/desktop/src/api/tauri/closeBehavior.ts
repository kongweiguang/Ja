// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { z } from "zod";
import type { CloseBehavior } from "@/shared/settings/closeBehavior";
import { invokeNativeCommand } from "./nativeInvoke";

export type { CloseBehavior } from "@/shared/settings/closeBehavior";

/** 只允许桌面关闭偏好的两个窄命令，避免 feature 依赖 raw invoke。 */
export const JA_CLOSE_BEHAVIOR_COMMANDS = {
  read: "ja_desktop_close_behavior_read",
  save: "ja_desktop_close_behavior_save",
} as const;

const CloseBehaviorSchema = z.enum(["background", "exit"]);

/** 读取原生持久化关闭行为，并拒绝未知 wire 值以保持生命周期 fail closed。 */
export async function readCloseBehavior(): Promise<CloseBehavior> {
  const value = await invokeNativeCommand(JA_CLOSE_BEHAVIOR_COMMANDS.read, {}, () =>
    tauriInvoke<unknown>(JA_CLOSE_BEHAVIOR_COMMANDS.read, {}),
  );
  const parsed = CloseBehaviorSchema.safeParse(value);
  if (!parsed.success) throw new Error("native close behavior response invalid");
  return parsed.data;
}

/** 保存关闭行为；Rust 只有原子写入成功后才切换内存状态。 */
export async function saveCloseBehavior(value: CloseBehavior): Promise<void> {
  const parsed = CloseBehaviorSchema.safeParse(value);
  if (!parsed.success) throw new Error("invalid close behavior");
  await invokeNativeCommand(JA_CLOSE_BEHAVIOR_COMMANDS.save, { value: parsed.data }, () =>
    tauriInvoke<void>(JA_CLOSE_BEHAVIOR_COMMANDS.save, { value: parsed.data }),
  );
}
