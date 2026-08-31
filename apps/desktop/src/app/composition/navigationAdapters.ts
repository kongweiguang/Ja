// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  TauriNativeShortcutAdapter,
  invokeWindowAction,
  observeWindowFrameState,
} from "@/api/tauri/window";
import type { NativeShortcutPort } from "../application/nativeShortcutPort";
import type { WindowFramePort } from "@/features/navigation";

export interface NavigationNativeAdapters {
  readonly nativeShortcuts: NativeShortcutPort;
  readonly windowFrame: WindowFramePort;
}

/**
 * 生产导航原生能力只在 composition 创建一次；feature application 仅接收窄端口，无法选择
 * command/event 名称，也不能取得 Tauri Window 句柄。
 */
function createNavigationNativeAdapters(): NavigationNativeAdapters {
  return {
    nativeShortcuts: new TauriNativeShortcutAdapter(),
    windowFrame: {
      observe: (listener) => observeWindowFrameState(listener),
      invoke: (action) => invokeWindowAction(action),
    },
  };
}

/** 稳定 adapter identity 防止 App 重渲染重建 native lease 与 window listener。 */
export const DEFAULT_NAVIGATION_NATIVE_ADAPTERS: NavigationNativeAdapters =
  createNavigationNativeAdapters();
