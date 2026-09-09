// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { Workbench } from "./ui/Workbench";
export { WorkbenchResizeHandle } from "./ui/WorkbenchResizeHandle";
export {
  capabilityWorkbenchTab,
  parseTaskWorkbenchTabKey,
  sameWorkbenchTab,
  sideTaskDraftWorkbenchTab,
  taskWorkbenchTab,
} from "./domain/tabs";
export type {
  WorkbenchCapability,
  WorkbenchCapabilityTab,
  WorkbenchTab,
  WorkbenchTabKey,
  WorkbenchTaskKind,
  WorkbenchTaskTab,
  WorkbenchTaskTabKey,
} from "./domain/tabs";
