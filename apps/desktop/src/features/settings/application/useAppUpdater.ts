// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import type { SettingsDesktopPort, SettingsUpdateCheckResult } from "./ports";

export type AppUpdaterState =
  | { kind: "checking"; automatic: boolean }
  | { kind: "unavailable" }
  | { kind: "up-to-date" }
  | { kind: "available"; currentVersion: string; version: string }
  | { kind: "installing"; currentVersion: string; version: string; percent?: number }
  | { kind: "restart-required"; version: string; restartFailed?: boolean }
  | { kind: "error"; operation: "check" | "install" };

export interface AppUpdaterController {
  readonly state: AppUpdaterState;
  readonly check: () => Promise<void>;
  readonly install: () => Promise<void>;
  readonly relaunch: () => Promise<void>;
}

/** 把 adapter 投影转换为设置页有限状态，避免 UI 持有 native Update resource。 */
function checkedState(result: SettingsUpdateCheckResult): AppUpdaterState {
  if (result.kind !== "available") return result;
  return {
    kind: "available",
    currentVersion: result.currentVersion,
    version: result.version,
  };
}

/**
 * 设置页生命周期拥有一次自动检查和所有用户动作；operation token 阻止迟到结果覆盖更新中的新状态，
 * 自动检查延迟到可取消的 microtask，以避开 effect 内同步 setState；cleanup 同时撤销排队请求并推进 epoch，
 * 让 Strict Mode 重放和真实卸载都不会遗留重复请求或迟到状态写入。
 */
export function useAppUpdater(desktop: SettingsDesktopPort): AppUpdaterController {
  const [state, setState] = useState<AppUpdaterState>({ kind: "checking", automatic: true });
  const operation = useRef(0);
  const automaticCheckStarted = useRef(false);
  const desktopRef = useRef(desktop);

  /** Desktop port 可随 Settings render 重新投影；稳定 request callback 只读取最新能力 owner。 */
  useEffect(() => {
    desktopRef.current = desktop;
  }, [desktop]);

  /** 自动与手动检查共用相同请求边界，只有当前 operation 能提交结果。 */
  const runCheck = useCallback(async (automatic: boolean): Promise<void> => {
    const token = ++operation.current;
    setState({ kind: "checking", automatic });
    try {
      const result = await desktopRef.current.checkForUpdate();
      if (operation.current === token) setState(checkedState(result));
    } catch {
      if (operation.current === token) setState({ kind: "error", operation: "check" });
    }
  }, []);

  useEffect(() => {
    if (automaticCheckStarted.current) return;
    automaticCheckStarted.current = true;

    let cancelled = false;
    const scheduledOperation = operation.current;
    queueMicrotask(() => {
      if (cancelled || operation.current !== scheduledOperation) return;
      void runCheck(true);
    });

    return () => {
      cancelled = true;
      automaticCheckStarted.current = false;
      operation.current += 1;
    };
  }, [runCheck]);

  /** 仅从 available 状态进入安装；进度回调只更新同一版本，重复点击不会并发安装。 */
  const install = useCallback(async (): Promise<void> => {
    if (state.kind !== "available") return;
    const token = ++operation.current;
    const { currentVersion, version } = state;
    setState({ kind: "installing", currentVersion, version });
    try {
      await desktop.installUpdate((progress) => {
        if (operation.current === token) {
          setState({
            kind: "installing",
            currentVersion,
            version,
            percent: progress.percent,
          });
        }
      });
      if (operation.current === token) setState({ kind: "restart-required", version });
    } catch {
      if (operation.current === token) setState({ kind: "error", operation: "install" });
    }
  }, [desktop, state]);

  /** 重启失败保留可重试状态，不把用户困在不可恢复的成功提示中。 */
  const relaunch = useCallback(async (): Promise<void> => {
    if (state.kind !== "restart-required") return;
    const token = ++operation.current;
    const { version } = state;
    try {
      await desktop.relaunchAfterUpdate();
    } catch {
      if (operation.current === token) {
        setState({ kind: "restart-required", version, restartFailed: true });
      }
    }
  }, [desktop, state]);

  return {
    state,
    check: useCallback(() => runCheck(false), [runCheck]),
    install,
    relaunch,
  };
}
