// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import type { SendShortcut } from "@/shared/settings/interfacePreferences";

/** Composer 只订阅发送键位，避免 conversation feature 直接持有全局 preference store。 */
export function useSendShortcut(): SendShortcut {
  return useUiPreferencesStore((state) => state.sendShortcut);
}

/** Terminal 只订阅代码字号，保持终端字体变化原地更新且隔离其它界面偏好。 */
export function useCodeFontSize(): number {
  return useUiPreferencesStore((state) => state.codeFontSize);
}

/** Review 行高跟随 rem 根字号，避免 UI 字号变化后虚拟行估算落后于 CSS 实际高度。 */
export function useUiFontSize(): number {
  return useUiPreferencesStore((state) => state.uiFontSize);
}
