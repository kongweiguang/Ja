// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import type { DesktopPlatform } from "./navigationModels";

export type NavigationCommand =
  | "toggle-sidebar"
  | "new-conversation"
  | "search-conversations"
  | "command-palette"
  | "open-review"
  | "open-files"
  | "open-terminal"
  | "open-preview"
  | "focus-conversation"
  | "open-settings"
  | "go-back"
  | "go-forward";

export interface NavigationShortcut {
  display: string;
  aria: string;
}

interface KeyboardShortcutEvent {
  readonly altKey: boolean;
  readonly ctrlKey: boolean;
  readonly defaultPrevented: boolean;
  readonly isComposing: boolean;
  readonly key: string;
  readonly metaKey: boolean;
  readonly repeat: boolean;
  readonly shiftKey: boolean;
}

/**
 * 从同一 registry 生成面向用户与 ARIA 的快捷键形式，避免 macOS Command 约定与键盘分发器漂移。
 */
export function navigationShortcut(
  command: NavigationCommand,
  platform: DesktopPlatform,
): NavigationShortcut {
  const macos = platform === "macos";
  const modDisplay = macos ? "⌘" : "Ctrl+";
  const modAria = macos ? "Meta+" : "Control+";
  switch (command) {
    case "toggle-sidebar":
      return { display: `${modDisplay}B`, aria: `${modAria}B` };
    case "new-conversation":
      return { display: `${modDisplay}N`, aria: `${modAria}N` };
    case "search-conversations":
      return { display: `${modDisplay}K`, aria: `${modAria}K` };
    case "command-palette":
      return {
        display: macos ? "⌘⇧P" : "Ctrl+Shift+P",
        aria: `${modAria}Shift+P`,
      };
    case "open-review":
      return { display: macos ? "⌘⇧G" : "Ctrl+Shift+G", aria: `${modAria}Shift+G` };
    case "open-files":
      return { display: `${modDisplay}P`, aria: `${modAria}P` };
    case "open-terminal":
      return { display: `${modDisplay}\``, aria: `${modAria}\`` };
    case "open-preview":
      return { display: `${modDisplay}T`, aria: `${modAria}T` };
    case "focus-conversation":
      return { display: macos ? "⌘⌥S" : "Ctrl+Alt+S", aria: `${modAria}Alt+S` };
    case "open-settings":
      return { display: `${modDisplay},`, aria: `${modAria},` };
    case "go-back":
      return macos
        ? { display: "⌘[", aria: "Meta+[" }
        : { display: "Alt+←", aria: "Alt+ArrowLeft" };
    case "go-forward":
      return macos
        ? { display: "⌘]", aria: "Meta+]" }
        : { display: "Alt+→", aria: "Alt+ArrowRight" };
  }
}

/**
 * 只解析应用 Shell 持有的快捷键。unknown 平台为浏览器预览同时接受 Ctrl 与 Command，
 * 桌面宿主只使用平台原生修饰键以避免重复触发系统行为。AltGraph 必须显式处理，
 * 因为 Windows 可能暴露物理 RightAlt，却不通过当前键盘布局的
 * `getModifierState("AltGraph")` 报告该状态。
 */
export function matchNavigationShortcut(
  event: KeyboardShortcutEvent,
  platform: DesktopPlatform,
  altGraphActive: boolean,
): NavigationCommand | undefined {
  if (event.defaultPrevented || event.isComposing || event.repeat || altGraphActive)
    return undefined;
  const key = event.key.toLowerCase();
  const mod =
    platform === "macos"
      ? event.metaKey && !event.ctrlKey
      : platform === "unknown"
        ? event.ctrlKey !== event.metaKey
        : event.ctrlKey && !event.metaKey;

  if (!event.altKey && mod) {
    if (!event.shiftKey && key === "b") return "toggle-sidebar";
    if (!event.shiftKey && key === "n") return "new-conversation";
    if (!event.shiftKey && key === "k") return "search-conversations";
    if (event.shiftKey && key === "p") return "command-palette";
    if (event.shiftKey && key === "g") return "open-review";
    if (!event.shiftKey && key === "p") return "open-files";
    if (!event.shiftKey && key === "`") return "open-terminal";
    if (!event.shiftKey && key === "t") return "open-preview";
    if (!event.shiftKey && key === ",") return "open-settings";
    if (platform === "macos" && !event.shiftKey && key === "[") return "go-back";
    if (platform === "macos" && !event.shiftKey && key === "]") return "go-forward";
  }

  if (event.altKey && !event.shiftKey && mod && key === "s") return "focus-conversation";

  if (platform !== "macos" && !event.ctrlKey && !event.metaKey && !event.shiftKey && event.altKey) {
    if (key === "arrowleft") return "go-back";
    if (key === "arrowright") return "go-forward";
  }
  return undefined;
}

/**
 * 阻止编辑器、Terminal 和表单按键触发全局导航；Command Palette 因明确属于全局能力而单独处理。
 */
function isEditableShortcutTarget(target: EventTarget | null): boolean {
  return (
    typeof Element !== "undefined" &&
    target instanceof Element &&
    target.closest('input, textarea, select, [contenteditable="true"], [role="textbox"]') !== null
  );
}

/**
 * 编辑区保留普通输入语义，但新建会话和五个公开 Workbench 动作必须能在 xterm/CodeMirror
 * 的 textarea 上先到达壳层；AltGraph 仍优先属于字符输入而不是 Side Chat。
 */
export function shouldPreserveEditableShortcut(
  command: NavigationCommand,
  event: KeyboardEvent,
): boolean {
  if (!isEditableShortcutTarget(event.target)) return false;
  switch (command) {
    case "new-conversation":
    case "search-conversations":
    case "command-palette":
    case "open-review":
    case "open-files":
    case "open-terminal":
    case "open-preview":
      return false;
    case "focus-conversation":
      return event.getModifierState("AltGraph");
    default:
      return true;
  }
}
