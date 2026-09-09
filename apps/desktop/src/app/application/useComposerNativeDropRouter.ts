// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

/** application 只声明 Composer 路由所需的脱敏投影，由 composition 注入已校验的 native adapter。 */
export type NativeDropProjectionEvent =
  | { phase: "enter" | "over" | "leave"; x: number; y: number; count: number }
  | { phase: "drop"; x: number; y: number; count: number; dropToken: string };

export interface NativeDropSubscriptionPort {
  subscribe(
    listener: (event: NativeDropProjectionEvent) => void,
  ): Promise<() => void | Promise<void>>;
}

export interface ComposerNativeDropProjection {
  event?: NativeDropProjectionEvent;
  registerDropZone(element: HTMLFormElement | null): void;
}

/** DOM 命中只读取逻辑 CSS 坐标；绝对路径与 drop token 的签发仍完全留在 Rust。 */
function containsLogicalPoint(element: HTMLElement | null, x: number, y: number): boolean {
  if (element === null) return false;
  const rect = element.getBoundingClientRect();
  return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
}

/**
 * 在应用层从共享 native router 提取唯一 Composer 投影；Files/Terminal 继续消费同一底层
 * listener 的 commit 分支，但只有各自 DOM 命中的目标会收到可执行 token。
 */
export function useComposerNativeDropRouter(
  port: NativeDropSubscriptionPort | undefined,
  enabled: boolean,
): ComposerNativeDropProjection {
  const dropZoneRef = useRef<HTMLFormElement | null>(null);
  const activeRef = useRef(false);
  const enabledRef = useRef(enabled);
  const enabledEpoch = useMemo(() => ({ enabled }), [enabled]);
  const enabledEpochRef = useRef(enabledEpoch);
  const [projection, setProjection] = useState<{
    event: NativeDropProjectionEvent;
    epoch: object;
  }>();

  /** 同步 listener 的 capability 快照；epoch 让重新启用时无法重放上一周期的一次性 token。 */
  useEffect(() => {
    enabledRef.current = enabled;
    enabledEpochRef.current = enabledEpoch;
    if (!enabled) activeRef.current = false;
  }, [enabled, enabledEpoch]);

  /** callback ref 让组合层拥有命中区域，不要求 Composer 或 Feature 订阅 Tauri 事件。 */
  const registerDropZone = useCallback((element: HTMLFormElement | null): void => {
    dropZoneRef.current = element;
    if (element === null) activeRef.current = false;
  }, []);

  useEffect(() => {
    if (port === undefined) return undefined;
    let disposed = false;
    let unsubscribe: (() => void | Promise<void>) | undefined;
    void port
      .subscribe((next) => {
        if (disposed) return;
        const inside =
          enabledRef.current && containsLogicalPoint(dropZoneRef.current, next.x, next.y);
        if (next.phase === "enter" || next.phase === "over") {
          if (inside) {
            activeRef.current = true;
            setProjection({ event: next, epoch: enabledEpochRef.current });
          } else if (activeRef.current) {
            activeRef.current = false;
            setProjection({
              event: { phase: "leave", x: next.x, y: next.y, count: next.count },
              epoch: enabledEpochRef.current,
            });
          }
          return;
        }
        if (next.phase === "leave") {
          if (!activeRef.current) return;
          activeRef.current = false;
          setProjection({ event: next, epoch: enabledEpochRef.current });
          return;
        }
        if (inside) {
          activeRef.current = false;
          setProjection({ event: next, epoch: enabledEpochRef.current });
        } else if (activeRef.current) {
          activeRef.current = false;
          setProjection({
            event: { phase: "leave", x: next.x, y: next.y, count: next.count },
            epoch: enabledEpochRef.current,
          });
        }
      })
      .then((release) => {
        if (disposed) void release();
        else unsubscribe = release;
      })
      .catch(() => undefined);
    return () => {
      disposed = true;
      activeRef.current = false;
      if (unsubscribe !== undefined) void unsubscribe();
    };
  }, [port]);

  return {
    event: enabled && projection?.epoch === enabledEpoch ? projection.event : undefined,
    registerDropZone,
  };
}
