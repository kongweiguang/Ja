// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { InteractionEvent } from "./interactionPort";

const listeners = new Set<(event: InteractionEvent) => void>();

/** 共用宿主唯一事件源，卡片不会为每个 Thread 再建立一个原生监听器。 */
export function publishInteractionHostEvent(event: InteractionEvent): void {
  for (const listener of [...listeners]) listener(event);
}

/** 订阅只持有 UI 回调，取消不会改变 Java 的待回答事实。 */
export function subscribeInteractionHostEvents(
  listener: (event: InteractionEvent) => void,
): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
