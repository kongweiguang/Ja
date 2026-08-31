// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

/** 只保留会改变标题栏交互与快捷键约定的宿主族，未知环境必须安全降级。 */
export type DesktopPlatform = "macos" | "windows" | "linux" | "unknown";

/** Navigation 只允许三种真实窗口动作；hide 明确表达关闭按钮只收起到托盘。 */
export type WindowAction = "minimize" | "toggle-maximize" | "hide";

/** 原生 frame 的最小只读投影；maximize 与 fullscreen 不能从 CSS 或 viewport 推断。 */
export interface WindowFrameState {
  readonly maximized: boolean;
  readonly fullscreen: boolean;
}

/** 历史导航只消费可展示字段，不复用 History adapter 的持久化 DTO。 */
export interface ThreadProjection {
  readonly threadId: string;
  readonly title: string;
  readonly status: "active" | "archived" | "deleted";
}
