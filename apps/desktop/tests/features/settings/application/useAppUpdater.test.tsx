// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { StrictMode, type ReactElement, type ReactNode } from "react";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type {
  SettingsDesktopPort,
  SettingsUpdateCheckResult,
} from "@/features/settings/application/ports";
import { useAppUpdater } from "@/features/settings/application/useAppUpdater";

afterEach(() => cleanup());

/** StrictMode wrapper 复现开发态 effect 重放，确保自动更新检查仍只提交一份 operation。 */
function StrictModeWrapper({ children }: { children: ReactNode }): ReactElement {
  return <StrictMode>{children}</StrictMode>;
}

/** 受控 Promise 让测试确定性地反转请求完成顺序，验证旧 operation 不覆盖新状态。 */
function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
} {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((accept) => {
    resolve = accept;
  });
  return { promise, resolve };
}

/** Hook 只使用 updater 三个端口；其它设置能力保持显式无副作用的最小 stub。 */
function desktopPort(checkForUpdate: SettingsDesktopPort["checkForUpdate"]): SettingsDesktopPort {
  return {
    readCloseBehavior: async () => "exit",
    saveCloseBehavior: async () => undefined,
    openExternalUrl: async () => undefined,
    checkForUpdate,
    installUpdate: async () => undefined,
    relaunchAfterUpdate: async () => undefined,
  };
}

describe("useAppUpdater", () => {
  it("在自动检查 microtask 执行前卸载时不调用 updater", async () => {
    const checkForUpdate = vi.fn<SettingsDesktopPort["checkForUpdate"]>();
    const { unmount } = renderHook(() => useAppUpdater(desktopPort(checkForUpdate)), {
      wrapper: StrictModeWrapper,
    });

    unmount();
    await act(async () => {
      await Promise.resolve();
    });

    expect(checkForUpdate).not.toHaveBeenCalled();
  });

  it("手动检查先启动时自动 microtask 不抢占 operation", async () => {
    const manual = deferred<SettingsUpdateCheckResult>();
    const checkForUpdate = vi
      .fn<SettingsDesktopPort["checkForUpdate"]>()
      .mockReturnValue(manual.promise);
    const { result } = renderHook(() => useAppUpdater(desktopPort(checkForUpdate)), {
      wrapper: StrictModeWrapper,
    });

    let manualCheck!: Promise<void>;
    act(() => {
      manualCheck = result.current.check();
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(checkForUpdate).toHaveBeenCalledTimes(1);
    expect(result.current.state).toEqual({ kind: "checking", automatic: false });
    await act(async () => {
      manual.resolve({ kind: "up-to-date" });
      await manualCheck;
    });
    expect(result.current.state).toEqual({ kind: "up-to-date" });
  });

  it("自动与手动检查共用 operation，迟到自动结果不会覆盖最新手动结果", async () => {
    const automatic = deferred<SettingsUpdateCheckResult>();
    const manual = deferred<SettingsUpdateCheckResult>();
    const checkForUpdate = vi
      .fn<SettingsDesktopPort["checkForUpdate"]>()
      .mockReturnValueOnce(automatic.promise)
      .mockReturnValueOnce(manual.promise);
    const { result } = renderHook(() => useAppUpdater(desktopPort(checkForUpdate)), {
      wrapper: StrictModeWrapper,
    });

    await waitFor(() => expect(checkForUpdate).toHaveBeenCalledTimes(1));
    expect(result.current.state).toEqual({ kind: "checking", automatic: true });

    let manualCheck!: Promise<void>;
    act(() => {
      manualCheck = result.current.check();
    });
    expect(result.current.state).toEqual({ kind: "checking", automatic: false });

    await act(async () => {
      manual.resolve({ kind: "available", currentVersion: "0.1.0", version: "0.2.0" });
      await manualCheck;
    });
    expect(result.current.state).toEqual({
      kind: "available",
      currentVersion: "0.1.0",
      version: "0.2.0",
    });

    await act(async () => {
      automatic.resolve({ kind: "up-to-date" });
      await automatic.promise;
    });
    expect(checkForUpdate).toHaveBeenCalledTimes(2);
    expect(result.current.state.kind).toBe("available");
  });
});
