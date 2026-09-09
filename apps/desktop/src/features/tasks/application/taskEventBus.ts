// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { TaskHostEvent } from "../domain/taskModel";

type TaskEventListener = (event: TaskHostEvent) => void;
const listeners = new Set<TaskEventListener>();

/** Composition 在唯一 Runtime subscription 上分发已校验 Task 事件，feature 不另建原生监听。 */
export function publishTaskHostEvent(event: TaskHostEvent): void {
  for (const listener of listeners) listener(event);
}

/** 订阅只持有进程期回调，并返回幂等 cleanup，避免 Tab 开关泄漏 listener。 */
export function subscribeTaskHostEvents(listener: TaskEventListener): () => void {
  listeners.add(listener);
  let active = true;
  return () => {
    if (!active) return;
    active = false;
    listeners.delete(listener);
  };
}
