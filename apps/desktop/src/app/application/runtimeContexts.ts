// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { createContext, useContext } from "react";
import type {
  RuntimeLifecycleController,
  RuntimeStateController,
} from "./useRuntimeLifecycleController";
import type { RuntimeTurnController } from "./useRuntimeTurnController";

export type RuntimeTurnsContract = Omit<RuntimeTurnController, "applyHostEvent">;

export const RuntimeStateContext = createContext<RuntimeStateController | null>(null);
export const RuntimeLifecycleContext = createContext<RuntimeLifecycleController | null>(null);
export const RuntimeTurnsContext = createContext<RuntimeTurnsContract | null>(null);

export type RuntimeStateContract = RuntimeStateController;
export type RuntimeLifecycleContract = RuntimeLifecycleController;

/** 读取只读 Runtime 状态；缺少 Provider 时立即失败，避免静默使用伪默认值。 */
export function useRuntimeState(): RuntimeStateContract {
  const value = useContext(RuntimeStateContext);
  if (value === null) throw new Error("Runtime hooks must be used inside RuntimeProvider");
  return value;
}

/** 读取启动、停止、恢复与查询用例，所有操作继续共用 lifecycle 串行队列。 */
export function useRuntimeLifecycle(): RuntimeLifecycleContract {
  const value = useContext(RuntimeLifecycleContext);
  if (value === null) throw new Error("Runtime hooks must be used inside RuntimeProvider");
  return value;
}

/** 读取 Turn 用例；generation admission fence 仍由 lifecycle controller 提供。 */
export function useRuntimeTurns(): RuntimeTurnsContract {
  const value = useContext(RuntimeTurnsContext);
  if (value === null) throw new Error("Runtime hooks must be used inside RuntimeProvider");
  return value;
}
