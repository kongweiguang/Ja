// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { useFilesController } from "./application/useFilesController";
export { FilesWorkspace } from "./ui/FilesWorkspace";
export { FileTree } from "./ui/FileTree";
export type {
  FilesActions,
  FilesControllerPorts,
  FilesViewModel,
  FilesWorkspaceCloseLease,
  FilesWorkspaceLifecycle,
  FilesWorkspaceProps,
  OpenDocument,
} from "./application/types";
export type {
  FileReadDto,
  FileSaveResult,
  FilesWorkspaceOperations,
  TrashPrepareResult,
  WorkspaceChangedEvent,
  WorkspaceNativeDropEvent,
  WorkspaceTreeEntryDto,
  WorkspaceTreePageDto,
  WatchSubscription,
} from "./application/ports";
export type { FileRevision, NewlineStyle, WorkspaceFileNode } from "./domain/types";
export { mapTreeEntries } from "./application/treeProjection";
export { findTreeNode, parentPath } from "./domain/filesModel";
