// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useRef, useState } from "react";
import type { WindowAction, WindowFrameState } from "../domain/navigationModels";
import type { WindowFrameObserver, WindowFramePort } from "./windowFramePort";

const DEFAULT_WINDOW_FRAME: WindowFrameState = { maximized: false, fullscreen: false };

export interface WindowFrameController {
  readonly frame: WindowFrameState;
  readonly pendingAction?: WindowAction;
  readonly invoke: (action: WindowAction) => void;
}

/**
 * Controller 统一拥有原生 frame 订阅与动作后的 refresh；UI 只能看到投影和封闭 action。
 * `enabled=false` 时不创建桌面 listener，浏览器预览和 Linux 不会伪装原生窗口能力。
 */
export function useWindowFrameController(
  port: WindowFramePort,
  enabled: boolean,
  onFailure: () => void = () => undefined,
): WindowFrameController {
  const [frame, setFrame] = useState<WindowFrameState>(DEFAULT_WINDOW_FRAME);
  const [pendingAction, setPendingAction] = useState<WindowAction | undefined>(undefined);
  const observerRef = useRef<WindowFrameObserver | undefined>(undefined);
  const pendingActionRef = useRef<WindowAction | undefined>(undefined);
  const invocationRevisionRef = useRef(0);

  /** port identity 或平台能力变化时先释放旧 observer，再建立唯一新订阅。 */
  useEffect(() => {
    if (!enabled) return undefined;
    const observer = port.observe(setFrame);
    observerRef.current = observer;
    return () => {
      invocationRevisionRef.current += 1;
      pendingActionRef.current = undefined;
      setPendingAction(undefined);
      observer.dispose();
      if (observerRef.current === observer) observerRef.current = undefined;
    };
  }, [enabled, port]);

  /**
   * 原生动作使用同步 ref 门禁拒绝重复点击，并在失败后恢复控件和发布稳定反馈；
   * 最大化完成后主动 refresh，晚完成动作不能刷新新宿主的 frame。
   */
  const invoke = useCallback(
    (action: WindowAction): void => {
      if (!enabled || pendingActionRef.current !== undefined) return;
      pendingActionRef.current = action;
      setPendingAction(action);
      const revision = ++invocationRevisionRef.current;
      const observerAtInvocation = observerRef.current;
      void port
        .invoke(action)
        .then(() => {
          // port/observer 切换是异步 fence；旧窗口动作晚完成时不得刷新新宿主的 frame。
          if (action === "toggle-maximize" && observerRef.current === observerAtInvocation) {
            observerAtInvocation?.refresh();
            // Windows 在 native Promise 返回后才可能提交最终 frame；一次有界复核覆盖该时序，
            // 不使用轮询或 CSS viewport 推断最大化事实。
            globalThis.setTimeout(() => {
              if (observerRef.current === observerAtInvocation) {
                observerAtInvocation?.refresh();
              }
            }, 120);
          }
        })
        .catch(() => onFailure())
        .finally(() => {
          if (revision !== invocationRevisionRef.current) return;
          pendingActionRef.current = undefined;
          setPendingAction(undefined);
        });
    },
    [enabled, onFailure, port],
  );

  return {
    frame: enabled ? frame : DEFAULT_WINDOW_FRAME,
    pendingAction: enabled ? pendingAction : undefined,
    invoke,
  };
}
