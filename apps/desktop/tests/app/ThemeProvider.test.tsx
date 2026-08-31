// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { act, cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { ThemeProvider } from "@/app/ThemeProvider";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";

/**
 * 构造单一可变 MediaQueryList，确保测试验证共享订阅的系统主题变化，
 * 而不是依赖 jsdom 不存在的 matchMedia 实现。
 */
function installColorSchemeMedia(initial: boolean): { setMatches(value: boolean): void } {
  let matches = initial;
  const listeners = new Set<() => void>();
  const media = {
    get matches() {
      return matches;
    },
    media: "(prefers-color-scheme: dark)",
    onchange: null,
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
    dispatchEvent: () => false,
  } as unknown as MediaQueryList;
  Object.defineProperty(window, "matchMedia", { configurable: true, value: () => media });
  return {
    /** 通知订阅者模拟操作系统主题变化，React 应重新读取同一浏览器事实。 */
    setMatches(value: boolean): void {
      matches = value;
      for (const listener of listeners) listener();
    },
  };
}

describe("ThemeProvider", () => {
  beforeEach(() => {
    useUiPreferencesStore.setState({
      themeMode: "system",
      palette: "xcode",
      highContrast: false,
      reduceMotion: false,
    });
  });

  afterEach(() => cleanup());

  it("通过共享 media query 订阅响应系统主题变化", async () => {
    const media = installColorSchemeMedia(false);
    render(
      <ThemeProvider>
        <span>content</span>
      </ThemeProvider>,
    );
    expect(document.documentElement.dataset["theme"]).toBe("light");

    act(() => media.setMatches(true));

    await waitFor(() => expect(document.documentElement.dataset["theme"]).toBe("dark"));
  });
});
