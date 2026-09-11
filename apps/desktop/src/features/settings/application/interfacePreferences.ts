// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  CODE_FONT_SIZE_OPTIONS,
  DEFAULT_CODE_FONT_SIZE,
  DEFAULT_UI_FONT_SIZE,
  UI_FONT_SIZE_OPTIONS,
  type SendShortcut,
} from "@/shared/settings/interfacePreferences";

export type { SendShortcut } from "@/shared/settings/interfacePreferences";

/** 设置页只承载 renderer 的即时界面偏好，不把这些值混入 App Server 配置文档。 */
export interface InterfacePreferences {
  sendShortcut: SendShortcut;
  uiFontSize: number;
  codeFontSize: number;
}

const defaultInterfacePreferences: InterfacePreferences = {
  sendShortcut: "enter",
  uiFontSize: DEFAULT_UI_FONT_SIZE,
  codeFontSize: DEFAULT_CODE_FONT_SIZE,
};

export const sendShortcutOptions: ReadonlyArray<{ value: SendShortcut; label: string }> = [
  { value: "enter", label: "Enter 发送" },
  { value: "modifier-enter", label: "Ctrl/Cmd + Enter 发送" },
];

export const uiFontSizeOptions = UI_FONT_SIZE_OPTIONS.map((value) => ({
  value: String(value),
  label: value === defaultInterfacePreferences.uiFontSize ? "默认" : value === 14 ? "小" : "大",
}));

export const codeFontSizeOptions = CODE_FONT_SIZE_OPTIONS.map((value) => ({
  value: String(value),
  label: value === defaultInterfacePreferences.codeFontSize ? `${value}px（默认）` : `${value}px`,
}));
