// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import type { TerminalWorkspaceCloseAll } from "@/features/workbench/terminal";
import type { JaWorkbenchAdapters } from "../useJaWorkbench";
import type { TerminalWorkspaceLifecycle } from "./workbenchLifecyclePorts";

interface TerminalActivationState {
  readonly selected: boolean;
  readonly tabOpen: boolean;
  readonly activated: boolean;
  readonly closeFenced: boolean;
}

export interface TerminalWorkspaceController {
  readonly active: boolean;
  readonly activated: boolean;
  readonly controllerGeneration: number;
  readonly registerCloseAll: (closeAll: TerminalWorkspaceCloseAll | undefined) => void;
  readonly closeCapability: () => Promise<void>;
}

/**
 * Terminal 生命周期需要在 Tab 隐藏时保留 renderer，又要在显式关闭后阻止中间帧重新激活。
 * 此 controller 只持有 activation fence 与窄 close port，PTY session 仍由 Terminal feature 独占。
 */
export function useTerminalWorkspaceLifecycle(
  workspaceId: string,
  terminalAdapter: JaWorkbenchAdapters["terminal"],
  tabOpen: boolean,
  selected: boolean,
  onRegisterLifecycle: (lifecycle: TerminalWorkspaceLifecycle | undefined) => void,
): TerminalWorkspaceController {
  const active = tabOpen && selected;
  const [activation, setActivation] = useState<TerminalActivationState>(() => ({
    selected: active,
    tabOpen,
    activated: active,
    closeFenced: false,
  }));
  const closeAllRef = useRef<TerminalWorkspaceCloseAll | undefined>(undefined);
  const lifecycleMountedRef = useRef(false);
  const [controllerGeneration, setControllerGeneration] = useState(0);

  // child commit 前派生 latch，避免缓存的 lazy Terminal 比保留状态提前一帧出现；
  // 受保护的 previous-input 比较也避免用 effect 级联渲染。
  if (activation.selected !== active || activation.tabOpen !== tabOpen) {
    const closeFenced = activation.closeFenced && tabOpen;
    setActivation({
      selected: active,
      tabOpen,
      activated: tabOpen && !closeFenced && (activation.activated || active),
      closeFenced,
    });
  }
  const activated = tabOpen && !activation.closeFenced && (activation.activated || active);

  /** 只保存 Terminal controller 的 teardown port，不提升 session identity 或 native handle。 */
  const registerCloseAll = useCallback((closeAll: TerminalWorkspaceCloseAll | undefined): void => {
    closeAllRef.current = closeAll;
  }, []);

  /**
   * workspace 切换优先经过已挂载 controller 的同步 admission fence；终端从未激活时
   * 才调用 workspace 级 native 兜底，确保 renderer 与 sidecar 的 session 都被覆盖。
   */
  const closeForWorkspaceChange = useCallback(async (): Promise<void> => {
    const closeAll = closeAllRef.current;
    if (closeAll !== undefined) {
      await closeAll();
      return;
    }
    await terminalAdapter.closeAll(workspaceId);
  }, [terminalAdapter, workspaceId]);

  /**
   * workspace 切换 intent 失效时重挂当前 controller，解除成功 closeAll 后的 admission fence；
   * 已卸载的旧 workspace 通过 mounted guard 拒绝晚恢复。
   */
  const resumeAfterWorkspaceChange = useCallback((): void => {
    if (!lifecycleMountedRef.current) return;
    setControllerGeneration((current) => current + 1);
  }, []);

  /** 注册稳定的 workspace 端口；cleanup 先失效 mounted fence，再清除 App 引用。 */
  useEffect(() => {
    lifecycleMountedRef.current = true;
    onRegisterLifecycle({ workspaceId, closeForWorkspaceChange, resumeAfterWorkspaceChange });
    return () => {
      lifecycleMountedRef.current = false;
      onRegisterLifecycle(undefined);
    };
  }, [closeForWorkspaceChange, onRegisterLifecycle, resumeAfterWorkspaceChange, workspaceId]);

  /**
   * 显式关闭 Terminal Tab 必须等待 controller 已接纳的 open，再设置 close fence；
   * 隐藏或普通 Tab 切换刻意不触发 native teardown，以保留终端状态。
   */
  const closeCapability = useCallback(async (): Promise<void> => {
    try {
      await closeAllRef.current?.();
    } catch {
      throw new Error("终端关闭失败，请重试。");
    }
    setActivation((current) => ({ ...current, activated: false, closeFenced: true }));
  }, []);

  return { active, activated, controllerGeneration, registerCloseAll, closeCapability };
}
