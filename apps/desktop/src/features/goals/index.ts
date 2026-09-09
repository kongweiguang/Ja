// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { useGoalController } from "./application/useGoalController";
export type { GoalController } from "./application/useGoalController";
export { createGoalPort } from "./infrastructure/goalAdapterPort";
export type { GoalEventSource } from "./infrastructure/goalAdapterPort";
export { publishGoalHostEvent, subscribeGoalHostEvents } from "./application/goalEventBus";
export type { GoalEvent, GoalMutationAction, GoalPort, GoalPortError } from "./application/ports";
export {
  goalPhaseLabel,
  goalProgress,
  isGoalActive,
  planStatusLabel,
  planStepStatusLabel,
} from "./domain/goalModel";
export type {
  AcceptanceCriterion,
  AcceptanceEvidence,
  CollaborationMode,
  CriterionStatus,
  EvaluationVerdict,
  EvidenceSource,
  GoalEvaluation,
  GoalInputRequest,
  GoalPhase,
  GoalReadModel,
  GoalStatus,
  GoalSummary,
  PlanDraft,
  PlanDraftCriterion,
  PlanDraftStep,
  PlanReadModel,
  PlanRevision,
  PlanStatus,
  PlanStep,
  PlanSummary,
  PlanStepStatus,
} from "./domain/goalModel";
export { ComposerGoalStatus } from "./ui/ComposerGoalStatus";
export type { ComposerGoalStatusProps } from "./ui/ComposerGoalStatus";
export { GoalStatusBar, goalPrimaryAction } from "./ui/GoalStatusBar";
export type { GoalStatusBarProps } from "./ui/GoalStatusBar";
export { GoalActivityCard } from "./ui/GoalActivityCard";
export { PlanTimelineBlock } from "./ui/PlanTimelineBlock";
export type { PlanTimelineBlockProps } from "./ui/PlanTimelineBlock";
export { PlanWorkbench, editableDraft, revisionDiff } from "./ui/PlanWorkbench";
export type { PlanWorkbenchProps } from "./ui/PlanWorkbench";
