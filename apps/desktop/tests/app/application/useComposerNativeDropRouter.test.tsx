// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  useComposerNativeDropRouter,
  type NativeDropProjectionEvent,
  type NativeDropSubscriptionPort,
} from "@/app/application/useComposerNativeDropRouter";

describe("useComposerNativeDropRouter", () => {
  afterEach(() => cleanup());

  /** 坐标命中只投影 Composer 自己的事件，底层 port 仍保持单订阅并在卸载时释放。 */
  it("routes logical-coordinate events and the one-time drop token to the Composer", async () => {
    let listener!: (event: NativeDropProjectionEvent) => void;
    const release = vi.fn();
    const port: NativeDropSubscriptionPort = {
      subscribe: vi.fn(async (next) => {
        listener = next;
        return release;
      }),
    };
    const { result, unmount } = renderHook(() => useComposerNativeDropRouter(port, true));
    const form = document.createElement("form");
    form.getBoundingClientRect = vi.fn(() => ({
      x: 10,
      y: 20,
      top: 20,
      right: 210,
      bottom: 120,
      left: 10,
      width: 200,
      height: 100,
      toJSON: () => ({}),
    }));
    act(() => result.current.registerDropZone(form));
    await waitFor(() => expect(port.subscribe).toHaveBeenCalledOnce());

    act(() => listener({ phase: "enter", x: 30, y: 40, count: 1 }));
    expect(result.current.event).toMatchObject({ phase: "enter", count: 1 });

    act(() => listener({ phase: "over", x: 400, y: 400, count: 1 }));
    expect(result.current.event?.phase).toBe("leave");

    act(() =>
      listener({
        phase: "drop",
        x: 30,
        y: 40,
        count: 1,
        dropToken: "00000000-0000-4000-8000-000000000001",
      }),
    );
    expect(result.current.event).toMatchObject({
      phase: "drop",
      dropToken: "00000000-0000-4000-8000-000000000001",
    });

    unmount();
    await waitFor(() => expect(release).toHaveBeenCalledOnce());
  });

  /** capability 关闭时保留订阅复用，但不向 Composer 暴露残留拖放状态。 */
  it("hides the projection while the Composer capability is disabled", async () => {
    let listener!: (event: NativeDropProjectionEvent) => void;
    const port: NativeDropSubscriptionPort = {
      subscribe: vi.fn(async (next) => {
        listener = next;
        return () => undefined;
      }),
    };
    const { result, rerender } = renderHook(
      ({ enabled }: { enabled: boolean }) => useComposerNativeDropRouter(port, enabled),
      { initialProps: { enabled: true } },
    );
    const form = document.createElement("form");
    form.getBoundingClientRect = vi.fn(() => ({
      x: 0,
      y: 0,
      top: 0,
      right: 100,
      bottom: 100,
      left: 0,
      width: 100,
      height: 100,
      toJSON: () => ({}),
    }));
    act(() => result.current.registerDropZone(form));
    await waitFor(() => expect(port.subscribe).toHaveBeenCalledOnce());
    act(() => listener({ phase: "enter", x: 10, y: 10, count: 1 }));
    expect(result.current.event?.phase).toBe("enter");

    rerender({ enabled: false });
    await waitFor(() => expect(result.current.event).toBeUndefined());
    act(() => listener({ phase: "over", x: 10, y: 10, count: 1 }));
    expect(result.current.event).toBeUndefined();
    rerender({ enabled: true });
    expect(result.current.event).toBeUndefined();
  });
});
