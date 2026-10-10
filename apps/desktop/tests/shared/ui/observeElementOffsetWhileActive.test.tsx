// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useRef, type ReactElement } from "react";
import { useVirtualizer, type Virtualizer } from "@tanstack/react-virtual";
import { afterEach, describe, expect, it, vi } from "vitest";
import { observeElementOffsetWhileActive } from "@/shared/ui/observeElementOffsetWhileActive";

interface ObserverHarnessProps {
  readonly onChange: (instance: Virtualizer<HTMLDivElement, HTMLDivElement>, sync: boolean) => void;
}

/** 使用真实 adapter 与默认 observer 验证滚动状态和卸载时的回调寿命，不 mock core 算法。 */
function ObserverHarness({ onChange }: ObserverHarnessProps): ReactElement {
  const viewportRef = useRef<HTMLDivElement>(null);
  // Keep the imperative virtualizer inside this lifecycle fixture; its API does not escape to the test.
  // eslint-disable-next-line react-hooks/incompatible-library
  useVirtualizer<HTMLDivElement, HTMLDivElement>({
    count: 1,
    getScrollElement: () => viewportRef.current,
    estimateSize: () => 32,
    initialRect: { width: 200, height: 100 },
    observeElementOffset: observeElementOffsetWhileActive,
    onChange,
  });
  return <div ref={viewportRef} data-testid="virtualizer-viewport" />;
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("observeElementOffsetWhileActive", () => {
  it("保留挂载期 offset 更新，并阻止 dispose 后 150ms debounce 再通知", () => {
    vi.useFakeTimers();
    const snapshots: Array<{ offset: number | null; isScrolling: boolean }> = [];
    const onChange = (instance: Virtualizer<HTMLDivElement, HTMLDivElement>): void => {
      snapshots.push({ offset: instance.scrollOffset, isScrolling: instance.isScrolling });
    };
    const rendered = render(<ObserverHarness onChange={onChange} />);
    const viewport = screen.getByTestId("virtualizer-viewport") as HTMLDivElement;
    viewport.scrollTop = 64;
    fireEvent.scroll(viewport);

    expect(snapshots).toContainEqual({ offset: 64, isScrolling: true });
    const callsAtUnmount = snapshots.length;
    rendered.unmount();
    act(() => vi.advanceTimersByTime(150));

    expect(snapshots).toHaveLength(callsAtUnmount);
  });
});
