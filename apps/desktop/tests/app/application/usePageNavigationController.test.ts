// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { usePageNavigationController } from "@/app/application/usePageNavigationController";

describe("page navigation", () => {
  /** 首配导航不能产生无效后退；配置完成后应直接回到正常工作面。 */
  it("does not add hidden history while settings are required", () => {
    const { result, rerender } = renderHook(
      ({ required }) => usePageNavigationController(required),
      {
        initialProps: { required: true },
      },
    );
    act(() => {
      result.current.navigate("settings");
      result.current.navigate("workspace");
      result.current.goBack();
      result.current.goForward();
    });
    expect(result.current.settingsVisible).toBe(true);
    expect(result.current.canGoBack).toBe(false);
    expect(result.current.canGoForward).toBe(false);
    rerender({ required: false });
    expect(result.current.settingsVisible).toBe(false);
    expect(result.current.canGoBack).toBe(false);
  });

  /** 配置短暂失效只冻结历史；恢复后仍能往返此前显式访问的页面。 */
  it("preserves back and forward history across required settings", () => {
    const { result, rerender } = renderHook(
      ({ required }) => usePageNavigationController(required),
      {
        initialProps: { required: false },
      },
    );
    act(() => result.current.navigate("settings"));
    expect(result.current.canGoBack).toBe(true);
    rerender({ required: true });
    act(() => result.current.goBack());
    expect(result.current.view).toBe("settings");
    expect(result.current.canGoBack).toBe(false);
    rerender({ required: false });
    act(() => result.current.goBack());
    expect(result.current.settingsVisible).toBe(false);
    expect(result.current.canGoForward).toBe(true);
    rerender({ required: true });
    act(() => result.current.goForward());
    expect(result.current.view).toBe("workspace");
    expect(result.current.canGoForward).toBe(false);
    rerender({ required: false });
    act(() => result.current.goForward());
    expect(result.current.settingsVisible).toBe(true);
  });
});
