// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import { useCallback, useMemo } from "react";
import { useUiPreferencesStore } from "@/shared/preferences/uiPreferences";
import type { InterfacePreferences, SettingsInterfacePreferences } from "@/features/settings";

/** 只连接当前显示字段；每次保存读取最新 store，避免旧快照覆盖相邻偏好。 */
export function useInterfacePreferences(): SettingsInterfacePreferences {
  const sendShortcut = useUiPreferencesStore((state) => state.sendShortcut);
  const uiFontSize = useUiPreferencesStore((state) => state.uiFontSize);
  const codeFontSize = useUiPreferencesStore((state) => state.codeFontSize);

  /** Zustand 的持久化失败必须继续拒绝，让设置页如实反馈“已应用但未保存”。 */
  const onChange = useCallback(
    async <K extends keyof InterfacePreferences>(
      key: K,
      value: InterfacePreferences[K],
    ): Promise<void> => {
      const store = useUiPreferencesStore.getState();
      switch (key) {
        case "sendShortcut":
          store.setSendShortcut(value as InterfacePreferences["sendShortcut"]);
          break;
        case "uiFontSize":
          store.setUiFontSize(value as number);
          break;
        case "codeFontSize":
          store.setCodeFontSize(value as number);
          break;
      }
    },
    [],
  );

  return useMemo(
    () => ({ sendShortcut, uiFontSize, codeFontSize, onChange }),
    [sendShortcut, uiFontSize, codeFontSize, onChange],
  );
}
