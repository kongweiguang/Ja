// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { useTerminalWorkspace } from "./useTerminalWorkspace";
export { useTerminalWorkspaceController } from "./useTerminalWorkspaceController";
export { useTerminalLayoutPersistence } from "./useTerminalLayoutPersistence";
export { LocalTerminalLayoutStorage } from "./terminalLayoutStorage";
export type {
  TerminalPaneRuntime,
  TerminalWorkspaceCloseAll,
  TerminalWorkspaceController,
  UseTerminalWorkspaceOptions,
} from "./useTerminalWorkspace";
export type {
  TerminalDropFailure,
  TerminalWorkspaceViewController,
} from "./useTerminalWorkspaceController";
export type {
  TerminalEvent,
  TerminalNativeDropEvent,
  TerminalOutputChunk,
  TerminalSessionInfo,
  TerminalWorkspaceAdapter,
} from "./terminalPorts";
