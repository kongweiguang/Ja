// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { CommandDescriptor } from "../domain/commandRegistry";

/** 可用性属于 application 的实时准入条件，domain 不感知状态读取或回调执行。 */
type CommandActionAvailability = boolean | (() => boolean);

/**
 * Command action 将纯描述与真实副作用绑定；只有 composition 可以创建该合同，UI 不直接
 * 持有 invoke，以免展示组件成为第二个执行 owner。
 */
export interface CommandAction extends CommandDescriptor {
  availability: CommandActionAvailability;
  invoke: () => void | Promise<void>;
}

/** availability 求值后的 application 投影；执行前仍会再次核对实时条件。 */
export interface ResolvedCommandAction extends CommandAction {
  available: boolean;
}

/** 求值实时 availability，并把异常谓词收敛为不可用，避免单个 action 击穿 Palette。 */
export function isCommandActionAvailable(action: CommandAction): boolean {
  try {
    return typeof action.availability === "function" ? action.availability() : action.availability;
  } catch {
    return false;
  }
}

/**
 * 一次性投影 availability，让 controller 过滤不可用动作时不为每个搜索 token 重复执行谓词。
 */
export function resolveCommandActions(
  actions: readonly CommandAction[],
): readonly ResolvedCommandAction[] {
  return actions.map((action) => ({ ...action, available: isCommandActionAvailable(action) }));
}
