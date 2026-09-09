// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { GoalEvent } from "./ports";

type GoalEventListener = (event: GoalEvent) => void;
const listeners = new Set<GoalEventListener>();

/** Composition 只发布已通过协议校验的 Goal identity，业务内容仍通过权威查询重读。 */
export function publishGoalHostEvent(event: GoalEvent): void {
  for (const listener of listeners) listener(event);
}

/** 订阅使用进程期回调且 cleanup 幂等，不能替代服务端 observe lease。 */
export function subscribeGoalHostEvents(listener: GoalEventListener): () => void {
  listeners.add(listener);
  let active = true;
  return () => {
    if (!active) return;
    active = false;
    listeners.delete(listener);
  };
}
