// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { useTaskController } from "./application/useTaskController";
export type { TaskController } from "./application/useTaskController";
export { useTaskActivityTimeline } from "./application/useTaskActivityTimeline";
export type { TaskTimelineActivity } from "./application/useTaskActivityTimeline";
export type {
  TaskApprovalPort,
  TaskPort,
  TaskResumePort,
  TaskThreadRenamePort,
  TaskTranscriptPort,
} from "./application/ports";
export { publishTaskHostEvent, subscribeTaskHostEvents } from "./application/taskEventBus";
export type {
  TaskActivity,
  TaskContentBlock,
  TaskHostEvent,
  TaskKind,
  TaskReadModel,
  TaskSummary,
} from "./domain/taskModel";
export {
  isTaskNonTerminal,
  propagatingTaskDescendantCount,
  taskElapsedLabel,
  taskSection,
  taskStateLabel,
} from "./domain/taskModel";
export { SubagentOverview } from "./ui/SubagentOverview";
export { buildTaskForest, buildTaskOverviewSections } from "./domain/taskOverview";
export type { TaskOverviewSection, TaskTreeNode } from "./domain/taskOverview";
export { TaskActivityCard } from "./ui/TaskActivityCard";
export { TaskDetailPanel } from "./ui/TaskDetailPanel";
export type {
  TaskComposerEnvironment,
  TaskComposerSkillSuggestion,
  TaskTranscriptActions,
} from "./ui/TaskDetailPanel";
