// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useResponsiveShellController } from "@/app/application/useResponsiveShellController";
import {
  WORKBENCH_SIZE_DEFAULT,
  useRightPanelSessionStore,
  useUiPreferencesStore,
} from "@/shared/preferences/uiPreferences";

const SCOPE_A = "server-1:1:workspace-1:thread-a";
const SCOPE_B = "server-1:1:workspace-1:thread-b";

/** 每个用例清空会话状态表并恢复全局宽度，避免 Zustand singleton 在测试间传播。 */
function resetShellState(): void {
  act(() => useUiPreferencesStore.setState({ workbenchSize: WORKBENCH_SIZE_DEFAULT }));
  act(() => useRightPanelSessionStore.setState({ scopes: new Map() }));
}

describe("useResponsiveShellController", () => {
  afterEach(() => {
    resetShellState();
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

  /** 新会话从关闭的中性 launcher 开始，不继承任何全局或项目级能力选择。 */
  it("starts every new conversation with a closed launcher", () => {
    const { result } = renderHook(() => useResponsiveShellController(false, false, true, SCOPE_A));

    expect(result.current).toMatchObject({
      inspectorOpen: false,
      workbenchVisible: false,
      workbenchTab: "new",
      workbenchTabs: ["new"],
    });
  });

  /** 拖动只改预览，pointer 事务提交后才更新 durable preference。 */
  it("previews and commits the adjustable workbench width independently", async () => {
    const { result } = renderHook(() => useResponsiveShellController(false, false, true, SCOPE_A));

    act(() => result.current.setInspectorOpen(true));
    await waitFor(() => expect(result.current.workbenchVisible).toBe(true));

    expect(result.current.workbenchSize).toBe(WORKBENCH_SIZE_DEFAULT);

    act(() => result.current.setWorkbenchPreviewSize(45.25));
    expect(result.current.workbenchSize).toBe(45.25);
    expect(useUiPreferencesStore.getState().workbenchSize).toBe(WORKBENCH_SIZE_DEFAULT);

    act(() => result.current.commitWorkbenchSize(45.25));
    expect(result.current.workbenchSize).toBe(45.25);
    expect(useUiPreferencesStore.getState().workbenchSize).toBe(45.25);
  });

  /** 同一项目内切换 A/B 对话时分别恢复各自开关、选中能力和打开列表。 */
  it("isolates and restores right panel state by conversation scope", () => {
    const { result, rerender } = renderHook(
      ({ scope }) => useResponsiveShellController(false, false, true, scope),
      { initialProps: { scope: SCOPE_A } },
    );

    act(() => {
      result.current.setWorkbenchTab("terminal");
      result.current.setInspectorOpen(true);
    });
    expect(result.current).toMatchObject({
      inspectorOpen: true,
      workbenchTab: "terminal",
      workbenchTabs: ["new", "terminal"],
    });

    rerender({ scope: SCOPE_B });
    expect(result.current).toMatchObject({
      inspectorOpen: false,
      workbenchTab: "new",
      workbenchTabs: ["new"],
    });
    act(() => {
      result.current.setWorkbenchTab("preview");
      result.current.setWorkbenchTabs(["preview", "files"]);
      result.current.setInspectorOpen(true);
    });

    rerender({ scope: SCOPE_A });
    expect(result.current).toMatchObject({
      inspectorOpen: true,
      workbenchTab: "terminal",
      workbenchTabs: ["new", "terminal"],
    });

    rerender({ scope: SCOPE_B });
    expect(result.current).toMatchObject({
      inspectorOpen: true,
      workbenchTab: "preview",
      workbenchTabs: ["preview", "files"],
    });
  });

  /** 无 scope 回调不写状态；A 的迟到回调在显示 B 时也只能更新 A。 */
  it("binds delayed callbacks to their originating scope and ignores empty sessions", () => {
    const { result, rerender } = renderHook(
      ({ scope }: { scope: string | undefined }) =>
        useResponsiveShellController(false, false, true, scope),
      { initialProps: { scope: SCOPE_A as string | undefined } },
    );
    const delayedSetTab = result.current.setWorkbenchTab;
    const delayedSetOpen = result.current.setInspectorOpen;

    rerender({ scope: undefined });
    act(() => {
      result.current.setWorkbenchTab("files");
      result.current.setInspectorOpen(true);
    });
    expect(result.current).toMatchObject({
      inspectorOpen: false,
      workbenchTab: "new",
      workbenchTabs: ["new"],
    });
    expect(useRightPanelSessionStore.getState().scopes.size).toBe(0);

    rerender({ scope: SCOPE_B });
    act(() => {
      delayedSetTab("terminal");
      delayedSetOpen(true);
    });
    expect(result.current).toMatchObject({
      inspectorOpen: false,
      workbenchTab: "new",
      workbenchTabs: ["new"],
    });

    rerender({ scope: SCOPE_A });
    expect(result.current).toMatchObject({
      inspectorOpen: true,
      workbenchTab: "terminal",
      workbenchTabs: ["new", "terminal"],
    });
  });

  /**
   * Windows 缩放下 799px renderer 可能呈现为 799.333 CSS px；子像素上界必须仍进入
   * 单栏 Workbench，同时把精确 800px 留给桌面 split 布局。
   */
  it("uses a subpixel-safe boundary for the 799px single-pane workbench", () => {
    const viewportMedia = installViewportMedia();

    const { result } = renderHook(() => useResponsiveShellController(false, false, true, SCOPE_A));

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
