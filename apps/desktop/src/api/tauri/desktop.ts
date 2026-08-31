// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { writeClipboardText } from "./clipboard";
import {
  enableDesktopNotifications,
  sendDesktopNotification,
  type DesktopNotificationKind,
} from "./notification";
import { openExternalHttpUrl } from "./opener";
import {
  isCurrentWindowFocused,
  TauriNativeShortcutAdapter,
  type NativeShortcutPort,
} from "./window";

/** 在 React composition root 注入窄桌面能力集，feature 不直接依赖 Tauri plugin。 */
export interface DesktopIntegrationAdapters {
  openExternalUrl: (url: string) => Promise<void>;
  writeText: (text: string) => Promise<void>;
  enableNotifications: () => Promise<boolean>;
  notify: (kind: DesktopNotificationKind) => Promise<void>;
  isWindowFocused: () => Promise<boolean>;
  nativeShortcuts: NativeShortcutPort;
}

/** 只创建一次生产 adapter 集合，不向 feature 或 UI 组件暴露 plugin 的宽泛 API。 */
export function createDesktopIntegrationAdapters(): DesktopIntegrationAdapters {
  return {
    openExternalUrl: openExternalHttpUrl,
    writeText: writeClipboardText,
    enableNotifications: enableDesktopNotifications,
    notify: sendDesktopNotification,
    isWindowFocused: isCurrentWindowFocused,
    nativeShortcuts: new TauriNativeShortcutAdapter(),
  };
}
