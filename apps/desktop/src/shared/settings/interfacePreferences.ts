// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 界面偏好的闭集与默认值由共享 settings value 层定义，避免 UI feature 反向依赖持久化 owner。 */
export type SendShortcut = "enter" | "modifier-enter";

export const DEFAULT_UI_FONT_SIZE = 16;
export const DEFAULT_CODE_FONT_SIZE = 13;
export const UI_FONT_SIZE_OPTIONS = [14, 16, 18] as const;
export const CODE_FONT_SIZE_OPTIONS = [12, 13, 14, 16] as const;
