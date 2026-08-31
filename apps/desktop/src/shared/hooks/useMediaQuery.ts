// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useSyncExternalStore } from "react";

/**
 * 订阅浏览器拥有的 media query，不把临时窗口尺寸写入持久化偏好。
 * 无 `matchMedia` 的测试或非浏览器环境使用显式 fallback，避免伪造响应式状态。
 */
export function useMediaQuery(queryText: string, fallback = false): boolean {
  /** 只订阅当前 query；React 负责在 query 变化时先退订旧实例，避免遗留 listener。 */
  const subscribe = useCallback(
    (notify: () => void): (() => void) => {
      if (typeof window.matchMedia !== "function") return () => undefined;
      const query = window.matchMedia(queryText);
      const observesViewport = /(?:min|max)-(?:width|height)\s*:/.test(queryText);
      query.addEventListener("change", notify);
      // WebView2 的 CDP viewport emulation 会更新 matches 却可能不派发 MediaQueryList.change；
      // 尺寸查询同时监听 resize，snapshot 仍只读取浏览器事实且不会保存镜像状态。
      if (observesViewport) window.addEventListener("resize", notify);
      return () => {
        query.removeEventListener("change", notify);
        if (observesViewport) window.removeEventListener("resize", notify);
      };
    },
    [queryText],
  );
  /** snapshot 直接读取浏览器事实，不在 effect 中维护可能滞后的镜像状态。 */
  const snapshot = useCallback(
    (): boolean =>
      typeof window.matchMedia === "function" ? window.matchMedia(queryText).matches : fallback,
    [fallback, queryText],
  );
  return useSyncExternalStore(subscribe, snapshot, () => fallback);
}
