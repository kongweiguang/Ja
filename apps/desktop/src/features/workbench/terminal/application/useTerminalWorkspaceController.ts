// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import {
  useTerminalWorkspace,
  type TerminalWorkspaceController,
  type UseTerminalWorkspaceOptions,
} from "./useTerminalWorkspace";

const TERMINAL_DROP_FAILURE_MESSAGE =
  "文件拖入失败，请重新拖入。授权可能已过期、目标已被其他能力消费、当前 Shell 不支持该路径或终端通信失败。";

export interface TerminalDropFailure {
  paneId: string;
  message: string;
}

export interface UseTerminalWorkspaceControllerOptions extends UseTerminalWorkspaceOptions {
  active: boolean;
  resolveNativeDropPane: (x: number, y: number) => string | undefined;
}

export interface TerminalWorkspaceViewController extends TerminalWorkspaceController {
  dropFailure?: TerminalDropFailure;
  dismissDropFailure: (paneId: string) => void;
}

/**
 * application controller 统一拥有 native drop 订阅、活动窗格校验和竞态栅栏；
 * UI 只提供当前 DOM 命中解析函数，不接触 adapter 或一次性 drop token。
 */
export function useTerminalWorkspaceController({
  active,
  resolveNativeDropPane,
  ...options
}: UseTerminalWorkspaceControllerOptions): TerminalWorkspaceViewController {
  const controller = useTerminalWorkspace(options);
  const [dropFailure, setDropFailure] = useState<TerminalDropFailure>();
  const activeTabId = controller.layout.activeTabId;
  const activePaneId = controller.layout.tabs.find(
    (tab) => tab.tabId === activeTabId,
  )?.activePaneId;
  const dropStateRef = useRef({
    active,
    activePaneId,
    dropNativePaths: controller.dropNativePaths,
    resolveNativeDropPane,
  });
  const dropAttemptRef = useRef(0);

  /** commit 后刷新订阅快照，使长期 listener 使用最新活动窗格和 DOM 命中策略。 */
  useEffect(() => {
    dropStateRef.current = {
      active,
      activePaneId,
      dropNativePaths: controller.dropNativePaths,
      resolveNativeDropPane,
    };
  }, [active, activePaneId, controller.dropNativePaths, resolveNativeDropPane]);

  /** 只关闭指定窗格的短暂失败提示，新拖放会获得新的不可复用 token。 */
  const dismissDropFailure = useCallback((paneId: string): void => {
    setDropFailure((current) => (current?.paneId === paneId ? undefined : current));
  }, []);

  /**
   * 一个 controller 实例只注册一个原生 listener；attempt sequence 阻止旧 rejection
   * 覆盖较新的成功结果，卸载后异步返回的 unsubscribe 会立即释放。
   */
  useEffect(() => {
    let disposed = false;
    let unsubscribe: (() => void | Promise<void>) | undefined;
    void options.adapter
      .subscribeNativeDrop((event) => {
        const state = dropStateRef.current;
        if (disposed || !state.active || state.activePaneId === undefined) return;
        if (state.resolveNativeDropPane(event.x, event.y) !== state.activePaneId) return;
        const paneId = state.activePaneId;
        const attempt = ++dropAttemptRef.current;
        setDropFailure(undefined);
        void state
          .dropNativePaths(paneId, event.dropToken)
          .then(() => {
            if (!disposed && dropAttemptRef.current === attempt) setDropFailure(undefined);
          })
          .catch(() => {
            if (!disposed && dropAttemptRef.current === attempt)
              setDropFailure({ paneId, message: TERMINAL_DROP_FAILURE_MESSAGE });
          });
      })
      .then((release) => {
        if (disposed) void release();
        else unsubscribe = release;
      })
      .catch(() => {
        // 浏览器预览没有原生事件源，订阅失败不能阻塞 PTY 的其它能力。
      });
    return () => {
      disposed = true;
      dropAttemptRef.current += 1;
      if (unsubscribe !== undefined) void unsubscribe();
    };
  }, [options.adapter]);

  return { ...controller, dropFailure, dismissDropFailure };
}
