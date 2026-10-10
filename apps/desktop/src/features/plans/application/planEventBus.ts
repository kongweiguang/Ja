// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { PlanEvent } from "./ports";

type PlanEventListener = (event: PlanEvent) => void;
const listeners = new Set<PlanEventListener>();

/** Composition 只发布已通过协议校验的 Plan identity，正文仍通过权威查询重读。 */
export function publishPlanHostEvent(event: PlanEvent): void {
  for (const listener of listeners) listener(event);
}

/** 订阅使用进程期回调且 cleanup 幂等，不能替代服务端 observe lease。 */
export function subscribePlanHostEvents(listener: PlanEventListener): () => void {
  listeners.add(listener);
  let active = true;
  return () => {
    if (!active) return;
    active = false;
    listeners.delete(listener);
  };
}
