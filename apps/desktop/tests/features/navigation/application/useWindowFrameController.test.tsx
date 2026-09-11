// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  useWindowFrameController,
  type WindowFramePort,
  type WindowFrameState,
} from "@/features/navigation";

/** 暴露动作完成时机，用于验证 port identity 切换后的晚结果 fence。 */
function deferred(): { readonly promise: Promise<void>; readonly resolve: () => void } {
  let resolvePromise: (() => void) | undefined;
  const promise = new Promise<void>((resolve) => {
    resolvePromise = resolve;
  });
  return { promise, resolve: () => resolvePromise?.() };
}

interface FramePortFixture {
  readonly port: WindowFramePort;
  readonly invoke: ReturnType<typeof vi.fn>;
  readonly refresh: ReturnType<typeof vi.fn>;
  readonly dispose: ReturnType<typeof vi.fn>;
  emit(state: WindowFrameState): void;
}

/** 用同步 observer fixture 固定 application 所需的最小生命周期，不模拟 Tauri 细节。 */
function framePort(): FramePortFixture {
  const invoke = vi.fn(async () => undefined);
  const refresh = vi.fn();
  const dispose = vi.fn();
  let listener: ((state: WindowFrameState) => void) | undefined;
  return {
    port: {
      observe: vi.fn((nextListener) => {
        listener = nextListener;
        return { refresh, dispose };
      }),
      invoke,
    },
    invoke,
    refresh,
    dispose,
    emit: (state) => listener?.(state),
  };
}

afterEach(() => cleanup());

describe("useWindowFrameController", () => {
  it("投影权威 frame，并只在最大化动作完成后主动 refresh", async () => {
    const fixture = framePort();
    const { result } = renderHook(() => useWindowFrameController(fixture.port, true));

    act(() => fixture.emit({ maximized: true, fullscreen: false }));
    expect(result.current.frame).toEqual({ maximized: true, fullscreen: false });

    act(() => result.current.invoke("minimize"));
    await waitFor(() => expect(fixture.invoke).toHaveBeenCalledWith("minimize"));
    expect(fixture.refresh).not.toHaveBeenCalled();

    act(() => result.current.invoke("toggle-maximize"));
    await waitFor(() => expect(fixture.refresh).toHaveBeenCalledOnce());
    await waitFor(() => expect(fixture.refresh).toHaveBeenCalledTimes(2));
  });

  it("切换 port identity 时释放旧 observer，并让禁用平台回退保守投影", () => {
    const first = framePort();
    const second = framePort();
    const { result, rerender } = renderHook(
      ({ port, enabled }) => useWindowFrameController(port, enabled),
      { initialProps: { port: first.port, enabled: true } },
    );
    act(() => first.emit({ maximized: true, fullscreen: true }));
    expect(result.current.frame.fullscreen).toBe(true);

    rerender({ port: second.port, enabled: true });
    expect(first.dispose).toHaveBeenCalledOnce();
    act(() => second.emit({ maximized: true, fullscreen: false }));
    expect(result.current.frame).toEqual({ maximized: true, fullscreen: false });

    rerender({ port: second.port, enabled: false });
    expect(second.dispose).toHaveBeenCalledOnce();
    expect(result.current.frame).toEqual({ maximized: false, fullscreen: false });
  });

  it("收口原生动作拒绝，不刷新或向 React 事件循环泄漏 rejection", async () => {
    const fixture = framePort();
    const onFailure = vi.fn();
    fixture.invoke.mockRejectedValueOnce(new Error("native unavailable"));
    const { result } = renderHook(() => useWindowFrameController(fixture.port, true, onFailure));

    act(() => result.current.invoke("toggle-maximize"));
    await waitFor(() => expect(fixture.invoke).toHaveBeenCalledOnce());
    await waitFor(() => expect(onFailure).toHaveBeenCalledOnce());
    expect(fixture.refresh).not.toHaveBeenCalled();
    expect(result.current.pendingAction).toBeUndefined();
  });

  it("原生动作 pending 时拒绝重复点击，并在完成后重新开放", async () => {
    const fixture = framePort();
    const completion = deferred();
    fixture.invoke.mockImplementationOnce(() => completion.promise);
    const { result } = renderHook(() => useWindowFrameController(fixture.port, true));

    act(() => {
      result.current.invoke("close");
      result.current.invoke("minimize");
    });
    expect(fixture.invoke).toHaveBeenCalledTimes(1);
    expect(fixture.invoke).toHaveBeenCalledWith("close");
    expect(result.current.pendingAction).toBe("close");

    await act(async () => {
      completion.resolve();
      await completion.promise;
    });
    expect(result.current.pendingAction).toBeUndefined();
  });

  it("拒绝旧 port 动作的晚完成结果刷新新 observer", async () => {
    const first = framePort();
    const second = framePort();
    const completion = deferred();
    first.invoke.mockImplementationOnce(() => completion.promise);
    const { result, rerender } = renderHook(({ port }) => useWindowFrameController(port, true), {
      initialProps: { port: first.port },
    });

    act(() => result.current.invoke("toggle-maximize"));
    rerender({ port: second.port });
    await act(async () => {
      completion.resolve();
      await completion.promise;
    });
    expect(first.invoke).toHaveBeenCalledOnce();
    expect(first.refresh).not.toHaveBeenCalled();
    expect(second.refresh).not.toHaveBeenCalled();
  });
});
