// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  observeElementOffset,
  type Virtualizer,
  type VirtualizerOptions,
} from "@tanstack/react-virtual";

/** 保留 TanStack 原生 offset 计算，只屏蔽其不可取消的 debounce 在 dispose 后触发的回调。 */
export function observeElementOffsetWhileActive<
  TScrollElement extends Element,
  TItemElement extends Element,
>(
  instance: Virtualizer<TScrollElement, TItemElement>,
  onChange: Parameters<VirtualizerOptions<TScrollElement, TItemElement>["observeElementOffset"]>[1],
): void | (() => void) {
  let active = true;
  const unsubscribe = observeElementOffset(instance, (offset, isScrolling) => {
    if (active) onChange(offset, isScrolling);
  });
  return () => {
    active = false;
    unsubscribe?.();
  };
}
