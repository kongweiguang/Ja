// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useResponsiveShellController } from "@/app/application/useResponsiveShellController";
import { WORKBENCH_SIZE_DEFAULT, useUiPreferencesStore } from "@/shared/preferences/uiPreferences";

/** 每个用例恢复进程期抽屉状态，避免 Zustand singleton 在测试间传播。 */
function closeInspector(): void {
  act(() =>
    useUiPreferencesStore.setState({
      inspectorOpen: false,
      workbenchSize: WORKBENCH_SIZE_DEFAULT,
    }),
  );
}

describe("useResponsiveShellController", () => {
  afterEach(() => {
    closeInspector();
    vi.unstubAllGlobals();
  });

  /**
   * 构造不会派发 MediaQueryList.change 的 WebView2 外壳，调用方只改变 matches；Hook 必须
   * 通过真实 resize signal 重新读取 snapshot，而不是依赖测试主动触发媒体查询回调。
   */
  function installViewportMedia(): {
    readonly matchMedia: ReturnType<typeof vi.fn>;
    readonly setNarrow: (value: boolean) => void;
  } {
    let narrow = false;
    const matchMedia = vi.fn(
      (query: string): MediaQueryList =>
        ({
          get matches() {
            return narrow && (query === "(max-width: 979px)" || query === "(max-width: 799.98px)");
          },
          media: query,
          onchange: null,
          addEventListener: () => undefined,
          removeEventListener: () => undefined,
          addListener: () => undefined,
          removeListener: () => undefined,
          dispatchEvent: () => false,
        }) as MediaQueryList,
    );
    vi.stubGlobal("matchMedia", matchMedia);
    return { matchMedia, setNarrow: (value) => (narrow = value) };
  }

  /**
   * WebView renderer 或 HMR store 即使带着旧的 true 进入新壳层，首次挂载也必须回到
   * 关闭状态；Tab 与已提交的宽度可保留，但不能让右栏自行复活。
   */
  it("closes transient inspector state whenever the desktop shell mounts", async () => {
    act(() => useUiPreferencesStore.getState().setInspectorOpen(true));

    const { result } = renderHook(() => useResponsiveShellController(false, false, true));

    await waitFor(() => expect(result.current.inspectorOpen).toBe(false));
    expect(useUiPreferencesStore.getState().inspectorOpen).toBe(false);
  });

  /** 拖动只改预览，pointer 事务提交后才更新 durable preference。 */
  it("previews and commits the adjustable workbench width independently", async () => {
    const { result } = renderHook(() => useResponsiveShellController(false, false, true));
    await waitFor(() => expect(result.current.inspectorOpen).toBe(false));

    act(() => useUiPreferencesStore.getState().setInspectorOpen(true));
    await waitFor(() => expect(result.current.workbenchVisible).toBe(true));

    expect(result.current.workbenchSize).toBe(WORKBENCH_SIZE_DEFAULT);

    act(() => result.current.setWorkbenchPreviewSize(45.25));
    expect(result.current.workbenchSize).toBe(45.25);
    expect(useUiPreferencesStore.getState().workbenchSize).toBe(WORKBENCH_SIZE_DEFAULT);

    act(() => result.current.commitWorkbenchSize(45.25));
    expect(result.current.workbenchSize).toBe(45.25);
    expect(useUiPreferencesStore.getState().workbenchSize).toBe(45.25);
  });

  /**
   * Windows 缩放下 799px renderer 可能呈现为 799.333 CSS px；子像素上界必须仍进入
   * 单栏 Workbench，同时把精确 800px 留给桌面 split 布局。
   */
  it("uses a subpixel-safe boundary for the 799px single-pane workbench", () => {
    const viewportMedia = installViewportMedia();

    const { result } = renderHook(() => useResponsiveShellController(false, false, true));

    expect(result.current.singlePaneWorkbench).toBe(false);
    act(() => {
      viewportMedia.setNarrow(true);
      window.dispatchEvent(new Event("resize"));
    });
    expect(result.current.compactNavigation).toBe(true);
    expect(result.current.singlePaneWorkbench).toBe(true);
    expect(viewportMedia.matchMedia).toHaveBeenCalledWith("(max-width: 799.98px)");
  });
});
