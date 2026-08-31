// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { AppTitlebar } from "./ui/AppTitlebar";
export type { AppTitlebarProps } from "./ui/AppTitlebar";
export { NavigationResizeHandle } from "./ui/NavigationResizeHandle";
export { NavigationSidebar } from "./ui/NavigationSidebar";
export type { NavigationSidebarProps } from "./ui/NavigationSidebar";
export { ConversationSearchDialog } from "./ui/ConversationSearchDialog";
export { ConversationRenameDialog } from "./ui/ConversationRenameDialog";
export { detectDesktopPlatform } from "./application/detectDesktopPlatform";
export type { NavigatorLike } from "./application/detectDesktopPlatform";
export { useWindowFrameController } from "./application/useWindowFrameController";
export type { WindowFramePort } from "./application/windowFramePort";
export {
  matchNavigationShortcut,
  navigationShortcut,
  shouldPreserveEditableShortcut,
} from "./domain/shortcuts";
export type { NavigationCommand } from "./domain/shortcuts";
export type {
  DesktopPlatform,
  ThreadProjection,
  WindowFrameState,
} from "./domain/navigationModels";
